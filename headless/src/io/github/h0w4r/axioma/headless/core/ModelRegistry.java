package io.github.h0w4r.axioma.headless.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import com.archimatetool.model.IArchimateModel;
import io.github.h0w4r.axioma.headless.persistence.NativeModelStore;
import io.github.h0w4r.axioma.headless.persistence.WorkspaceFiles;

/** Registro multimodelo: cada transacción trabaja sobre una copia aislada y durable. */
public final class ModelRegistry {
    private static final class Session {
        final ReentrantLock lock = new ReentrantLock();
        final Path path;
        IArchimateModel model;
        String fingerprint;
        long revision;
        boolean closed;
        Session(IArchimateModel model, Path path, String fingerprint) {
            this.model = model; this.path = path; this.fingerprint = fingerprint;
        }
    }

    private final Map<String, Session> models = new ConcurrentHashMap<>();
    private final NativeModelStore store;
    private final WorkspaceFiles files;

    public ModelRegistry(NativeModelStore store, WorkspaceFiles files) {
        this.store = store; this.files = files;
    }

    public WorkspaceFiles files() { return files; }

    public synchronized Map<String, Object> create(String name, String input) {
        Path path = files.resolve(input);
        if (!WorkspaceFiles.fingerprint(path).equals("absent")) {
            throw new AxiomaException("FILE_EXISTS", "No se sobrescribe un modelo existente al crear uno nuevo.");
        }
        IArchimateModel model = store.create(name, path);
        String fingerprint = store.save(model, path, "absent");
        Session session = new Session(model, path, fingerprint);
        models.put(model.getId(), session);
        return summary(session);
    }

    public synchronized Map<String, Object> open(String input) {
        Path path = files.resolve(input);
        for (Session session : models.values()) {
            if (session.path.equals(path)) return read(session.model.getId(), this::summaryModel);
        }
        var opened = store.loadWithFingerprint(path);
        IArchimateModel loaded = opened.model();
        if (models.containsKey(loaded.getId())) {
            store.dispose(loaded);
            throw new AxiomaException("DUPLICATE_MODEL_ID", "Otro archivo abierto utiliza el mismo identificador de modelo.");
        }
        Session session = new Session(loaded, path, opened.fingerprint());
        models.put(loaded.getId(), session);
        return summary(session);
    }

    public synchronized List<Map<String, Object>> list() {
        return models.keySet().stream().sorted().map(id -> read(id, this::summaryModel)).toList();
    }

    public <T> T read(String id, Function<IArchimateModel, T> operation) {
        Session session = session(id);
        session.lock.lock();
        try { checkOpen(session); return operation.apply(session.model); }
        finally { session.lock.unlock(); }
    }

    public Map<String, Object> info(String id) {
        Session session = session(id);
        session.lock.lock();
        try { checkOpen(session); return summary(session); }
        finally { session.lock.unlock(); }
    }

    public Map<String, Object> mutate(String id, Map<String, Object> arguments,
            Function<IArchimateModel, Object> operation) {
        return transact(id, arguments, store::copy, operation);
    }

    public List<Map<String, Object>> history(String id) { return read(id, model -> store.history(model.getFile().toPath())); }

    public Map<String, Object> restore(String id, Map<String, Object> arguments) {
        String fingerprint = Arguments.required(arguments, "fingerprint");
        return transact(id, arguments, model -> store.loadSnapshot(model.getFile().toPath(), fingerprint),
                model -> Map.of("restoredFrom", fingerprint));
    }

    private Map<String, Object> transact(String id, Map<String, Object> arguments,
            Function<IArchimateModel, IArchimateModel> prepare, Function<IArchimateModel, Object> operation) {
        Session session = session(id);
        session.lock.lock();
        IArchimateModel candidate = null;
        try {
            checkOpen(session);
            files.resolve(session.path.toString());
            Arguments.expectedRevision(arguments, session.revision);
            if (arguments.containsKey("expectedFingerprint") && !Arguments.required(arguments, "expectedFingerprint").equals(session.fingerprint)) {
                throw new AxiomaException("FINGERPRINT_CONFLICT", "La huella esperada no coincide con el estado abierto.");
            }
            candidate = prepare.apply(session.model);
            if (!id.equals(candidate.getId())) throw new AxiomaException("MODEL_ID_CHANGED", "La revisión histórica pertenece a otro modelo.");
            Object result = operation.apply(candidate);
            var archive = (com.archimatetool.editor.model.IArchiveManager) candidate.getAdapter(com.archimatetool.editor.model.IArchiveManager.class);
            var previousArchive = (com.archimatetool.editor.model.IArchiveManager) session.model.getAdapter(com.archimatetool.editor.model.IArchiveManager.class);
            var imported = new java.util.HashSet<>(archive.getLoadedImagePaths());
            imported.removeAll(previousArchive.getLoadedImagePaths());
            imported.removeAll(archive.getImagePaths());
            if (!imported.isEmpty()) throw new AxiomaException("UNREFERENCED_IMAGE", "Archi sólo conserva imágenes referenciadas. Use image.add con id de destino o un lote que añada la imagen y su representación.");
            var errors = store.validate(candidate);
            if (!errors.isEmpty()) throw new AxiomaException("INVALID_MODEL", String.join("; ", errors));
            boolean dryRun = Arguments.bool(arguments, "dryRun", false);
            if (dryRun) {
                return Map.of("status", "preview", "persisted", false, "modelId", id,
                        "revision", session.revision, "preview", result);
            }
            // Publicar el nuevo estado en memoria únicamente después del guardado real.
            String fingerprint = store.save(candidate, session.path, session.fingerprint);
            IArchimateModel previous = session.model;
            session.model = candidate;
            candidate = null;
            session.fingerprint = fingerprint;
            session.revision++;
            store.dispose(previous);
            Map<String, Object> response = new LinkedHashMap<>(summary(session));
            response.put("result", result);
            response.put("persisted", true);
            return response;
        } finally { if (candidate != null) store.dispose(candidate); session.lock.unlock(); }
    }

    public Map<String, Object> reload(String id) {
        Session session = session(id);
        session.lock.lock();
        try {
            checkOpen(session);
            files.resolve(session.path.toString());
            var opened = store.loadWithFingerprint(session.path);
            IArchimateModel loaded = opened.model();
            if (!id.equals(loaded.getId())) { store.dispose(loaded); throw new AxiomaException("MODEL_ID_CHANGED", "El archivo fue sustituido por otro modelo."); }
            store.dispose(session.model);
            session.model = loaded;
            session.fingerprint = opened.fingerprint();
            session.revision++;
            return summary(session);
        } finally { session.lock.unlock(); }
    }

    public synchronized Map<String, Object> close(String id) {
        Session removed = session(id);
        removed.lock.lock();
        try { checkOpen(removed); removed.closed = true; models.remove(id, removed); store.dispose(removed.model); return Map.of("modelId", id, "closed", true); }
        finally { removed.lock.unlock(); }
    }

    public List<String> validate(String id) { return read(id, store::validate); }

    private Session session(String id) {
        Session session = models.get(id);
        if (session == null) throw new AxiomaException("MODEL_NOT_OPEN", "El modelo solicitado no está abierto: " + id);
        return session;
    }

    private void checkOpen(Session session) {
        if (session.closed) throw new AxiomaException("MODEL_NOT_OPEN", "La sesión del modelo ya se cerró.");
    }

    private Map<String, Object> summaryModel(IArchimateModel model) { return summary(session(model.getId())); }

    private Map<String, Object> summary(Session session) {
        return Map.of("modelId", session.model.getId(), "name", session.model.getName(),
                "path", session.path.toString(), "revision", session.revision,
                "fingerprint", session.fingerprint, "views", session.model.getDiagramModels().size());
    }
}
