package io.github.h0w4r.axioma.live;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.core.runtime.ILog;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import com.archimatetool.editor.diagram.IDiagramModelEditor;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.util.IModelContentListener;

/** Observación opcional de archivos: toda mutación del modelo visible se ejecuta en UI. */
public final class LiveSyncService implements AutoCloseable {
    private static final class Tracked {
        final IArchimateModel model;
        final Path path;
        final AtomicLong epoch = new AtomicLong();
        final IModelContentListener listener = notification -> epoch.incrementAndGet();
        String fingerprint;
        String observed;
        String state = "INITIALIZING";
        String detail = "";
        int differences;
        long captures;
        long observations;
        boolean inFlight;
        boolean conflict;
        Tracked(IArchimateModel model) { this.model = model; path = model.getFile().toPath().toAbsolutePath().normalize(); }
    }

    private final Display display;
    private final Map<IArchimateModel, Tracked> tracked = new IdentityHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "axioma-live-io"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;

    public LiveSyncService(Display display, long intervalMillis) {
        if (intervalMillis < 100) throw new IllegalArgumentException("Intervalo mínimo de observación: 100 ms.");
        this.display = display;
        worker.scheduleWithFixedDelay(() -> { if (!closed && !display.isDisposed()) display.asyncExec(this::pollUi); }, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void pollUi() {
        if (closed || display.isDisposed()) return;
        var open = IEditorModelManager.INSTANCE.getModels();
        for (var entry : new ArrayList<>(tracked.entrySet())) {
            if (!open.contains(entry.getKey()) || entry.getKey().getFile() == null
                    || !entry.getValue().path.equals(entry.getKey().getFile().toPath().toAbsolutePath().normalize())) {
                entry.getKey().removeModelContentListener(entry.getValue().listener); tracked.remove(entry.getKey());
            }
        }
        for (IArchimateModel model : List.copyOf(open)) {
            if (model.getFile() == null) continue;
            Tracked target = tracked.computeIfAbsent(model, key -> {
                Tracked created = new Tracked(key); key.addModelContentListener(created.listener); return created;
            });
            if (target.inFlight || target.conflict) continue;
            target.inFlight = true;
            boolean dirty = dirty(model);
            worker.execute(() -> inspect(target, dirty));
        }
    }

    private void inspect(Tracked target, boolean wasDirty) {
        try {
            String hash = LiveComparison.fingerprint(target.path);
            if (wasDirty || hash.equals(target.fingerprint)) {
                publish(target, () -> {
                    target.observations++;
                    target.observed = hash;
                    if (wasDirty && target.fingerprint != null && !hash.equals(target.fingerprint)) {
                        target.conflict = true; status(target, "CONFLICT_UNSAVED", "Archivo externo modificado y cambios humanos sin guardar. No se fusiona.");
                    } else status(target, wasDirty ? "LOCAL_CHANGES" : "IN_SYNC", "");
                });
                return;
            }
            if (!hash.equals(target.observed)) {
                publish(target, () -> {
                    target.observations++; target.observed = hash;
                    status(target, "WAITING_STABLE_FILE", "Se agrupan cambios hasta observar dos huellas consecutivas iguales.");
                });
                return;
            }
            // Copiar el modelo sólo si el archivo cambió, no en cada latido de observación.
            display.asyncExec(() -> {
                if (closed || !tracked.containsKey(target.model)) { target.inFlight = false; return; }
                target.observations++;
                if (dirty(target.model)) {
                    target.conflict = true; status(target, "CONFLICT_UNSAVED", "Hay cambios humanos antes de capturar el modelo."); target.inFlight = false; return;
                }
                try {
                    long epoch = target.epoch.get();
                    var capture = LiveComparison.capture(target.model);
                    target.captures++;
                    worker.execute(() -> prepare(target, capture, epoch));
                } catch (Exception error) { failure(target, error); }
            });
        } catch (Exception error) { failure(target, error); }
    }

    private void prepare(Tracked target, LiveComparison.Capture capture, long epoch) {
        LiveComparison prepared = null;
        try {
            prepared = LiveComparison.prepare(capture, target.path);
            LiveComparison incoming = prepared;
            display.asyncExec(() -> {
                try {
                    if (closed || !tracked.containsKey(target.model)) return;
                    target.observed = incoming.fingerprint();
                    if (dirty(target.model)) {
                        target.conflict = true; status(target, "CONFLICT_UNSAVED", "Se detectó una edición humana durante la preparación; no se aplica."); return;
                    }
                    if (target.epoch.get() != epoch) { status(target, "RETRY_CHANGED_MODEL", "El modelo cambió durante la comparación."); return; }
                    try (var ui = new GuiRefreshTransaction(target.model, incoming.viewIds())) {
                        target.differences = incoming.apply(ui::restore, ui::suspend);
                    }
                    target.fingerprint = incoming.fingerprint();
                    status(target, "IN_SYNC", "Aplicadas diferencias=" + target.differences);
                } catch (Exception error) {
                    target.conflict = true; status(target, "ERROR_PAUSED", error.toString()); ILog.get().error("Axioma Live: fusión revertida", error);
                } finally { target.inFlight = false; incoming.close(); }
            });
            prepared = null; // La tarea UI asume su cierre, incluido el caso de conflicto.
        } catch (Exception error) {
            failure(target, error);
        } finally { if (prepared != null) prepared.close(); }
    }

    private void failure(Tracked target, Exception error) {
        publish(target, () -> {
            if (error instanceof LiveComparison.UnstableRead || error instanceof java.nio.file.FileSystemException) {
                status(target, "WAITING_STABLE_FILE", error.getMessage()); return;
            }
            target.conflict = true; status(target, "ERROR_PAUSED", error.toString());
            ILog.get().error("Axioma Live: no se modificó el modelo abierto", error);
        });
    }

    private void publish(Tracked target, Runnable action) {
        if (display.isDisposed()) return;
        display.asyncExec(() -> { try { if (!closed && tracked.containsKey(target.model)) action.run(); } finally { target.inFlight = false; } });
    }

    /** Comprobar tanto el CommandStack del modelo como editores nativos que puedan tener estado pendiente. */
    private boolean dirty(IArchimateModel model) {
        if (!(model.getAdapter(CommandStack.class) instanceof CommandStack)) return true;
        if (IEditorModelManager.INSTANCE.isModelDirty(model)) return true;
        if (PlatformUI.isWorkbenchRunning()) for (var window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (var page : window.getPages()) for (var reference : page.getEditorReferences()) {
                var editor = reference.getEditor(false);
                if (editor instanceof IDiagramModelEditor diagram && diagram.getModel().getArchimateModel() == model && editor.isDirty()) return true;
            }
        }
        return false;
    }

    private void status(Tracked target, String state, String detail) {
        boolean changed = !target.state.equals(state);
        target.state = state; target.detail = detail;
        if (changed) ILog.get().info("Axioma Live: " + state + " archivo=" + target.path + " " + detail);
        if (PlatformUI.isWorkbenchRunning()) for (var window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            var page = window.getActivePage();
            if (page != null && page.getActiveEditor() instanceof IDiagramModelEditor editor && editor.getModel().getArchimateModel() == target.model) {
                editor.getEditorSite().getActionBars().getStatusLineManager().setMessage("Axioma: " + state + (detail.isBlank() ? "" : " | " + detail));
            }
        }
    }

    /** Reintento explícito: no descarta cambios, y dirty() volverá a comprobarse antes de fusionar. */
    public void resume() { for (var target : tracked.values()) { target.conflict = false; target.state = "RETRY_REQUESTED"; } }

    public List<Map<String, Object>> status() {
        return tracked.values().stream().map(t -> Map.<String, Object>of("modelId", t.model.getId(), "path", t.path.toString(),
                "state", t.state, "detail", t.detail, "differences", t.differences,
                "captures", t.captures, "observations", t.observations,
                "fingerprint", t.fingerprint == null ? "" : t.fingerprint, "observed", t.observed == null ? "" : t.observed)).toList();
    }

    @Override public void close() {
        closed = true; worker.shutdown();
        Runnable cleanup = () -> {
            for (var target : tracked.values()) target.model.removeModelContentListener(target.listener);
            tracked.clear();
        };
        if (Display.getCurrent() == display || display.isDisposed()) cleanup.run();
        else display.asyncExec(cleanup);
    }
}
