package io.github.h0w4r.axioma.live.verification;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Collectors;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.gef.GraphicalEditPart;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.application.IWorkbenchConfigurer;
import com.archimatetool.editor.ArchiWorkbenchAdvisor;
import com.archimatetool.editor.diagram.IDiagramModelEditor;
import com.archimatetool.editor.diagram.util.DiagramUtils;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.ui.services.EditorManager;
import com.archimatetool.model.*;
import com.archimatetool.model.util.ArchimateModelUtils;
import io.github.h0w4r.axioma.live.LiveSyncService;
import io.github.h0w4r.axioma.live.LivePlugin;

/** Aplicación de aceptación: workbench y editor nativos, sin dobles de prueba. */
public final class LiveVerificationApplication implements IApplication {
    private Display display;
    private LiveSyncService service;
    private IArchimateModel original;
    private IDiagramModel originalView;
    private IDiagramModelEditor editor;
    private IArchimateElement actor;
    private IArchimateModel otherModel;
    private IArchimateElement otherActor;
    private IDiagramModelEditor otherEditor;
    private org.eclipse.swt.widgets.Control otherControl;
    private Path evidence;
    private String failure;
    private String screenshot = "";
    private long stateSequence;
    private boolean nativePackageAccepted;

    @Override public Object start(IApplicationContext context) throws Exception {
        evidence = Path.of(System.getProperty("axioma.verify.evidence"));
        Files.createDirectories(evidence);
        display = PlatformUI.createDisplay();
        try {
            PlatformUI.createAndRunWorkbench(display, new ArchiWorkbenchAdvisor() {
                @Override public void initialize(IWorkbenchConfigurer configurer) {
                    super.initialize(configurer); configurer.setSaveAndRestore(false);
                }
                @Override public void postStartup() {
                    // El perfil y el workbench son exclusivamente de esta aceptación.
                    for (var window : PlatformUI.getWorkbench().getWorkbenchWindows()) window.getShell().setVisible(false);
                    display.asyncExec(() -> {
                        try { initializeVerification(); }
                        catch (Throwable error) { fail(error); }
                    });
                }
            });
        } finally {
            if (service != null) service.close();
            if (!display.isDisposed()) display.dispose();
        }
        return failure == null ? EXIT_OK : Integer.valueOf(1);
    }

    private void initializeVerification() throws Exception {
        String pluginPackage = System.getProperty("axioma.verify.package", "");
        if (!pluginPackage.isBlank()) {
            // Usar el validador del instalador nativo; no confundir este control con instalar en la GUI del operador.
            var handler = new com.archimatetool.editor.p2.DropinsPluginHandler();
            var validate = handler.getClass().getDeclaredMethod("isPluginZipFile", java.io.File.class);
            validate.setAccessible(true);
            nativePackageAccepted = Boolean.TRUE.equals(validate.invoke(handler, Path.of(pluginPackage).toFile()));
            if (!nativePackageAccepted) throw new IllegalStateException("El instalador de Archi no reconoce el paquete.");
        }
        original = IEditorModelManager.INSTANCE.openModel(Path.of(System.getProperty("axioma.verify.model")).toFile());
        if (original == null) throw new IllegalStateException("No se pudo abrir el modelo en Archi.");
        originalView = original.getDiagramModels().get(0);
        actor = (IArchimateElement) ArchimateModelUtils.getObjectByID(original, System.getProperty("axioma.verify.actor"));
        editor = EditorManager.openDiagramEditor(originalView);
        if (editor == null || editor.getGraphicalViewer().getContents() == null) throw new IllegalStateException("No se abrió el editor gráfico real.");
        String otherPath = System.getProperty("axioma.verify.otherModel", "");
        if (!otherPath.isBlank()) {
            otherModel = IEditorModelManager.INSTANCE.openModel(Path.of(otherPath).toFile());
            otherActor = (IArchimateElement) ArchimateModelUtils.getObjectByID(otherModel, System.getProperty("axioma.verify.otherActor"));
            otherEditor = EditorManager.openDiagramEditor(otherModel.getDiagramModels().get(0), false);
            otherControl = otherEditor.getGraphicalViewer().getControl();
        }
        // Contexto no predeterminado: comprobar que la sincronización no lo reinicia silenciosamente.
        var root = (org.eclipse.gef.editparts.ScalableFreeformRootEditPart) editor.getGraphicalViewer().getRootEditPart();
        root.getZoomManager().setZoom(1.25);
        var selected = editor.getGraphicalViewer().getEditPartRegistry().get(originalView.getChildren().get(0));
        editor.getGraphicalViewer().select(selected);
        activateMenu("Actualizar modelos abiertos en vivo", true);
        service = LivePlugin.get().service();
        if (service == null) throw new IllegalStateException("La acción real del menú no activó el adaptador.");
        Files.writeString(evidence.resolve("ready"), "Workbench y editor nativos abiertos");
        pollCommands();
    }

    private void pollCommands() {
        if (display.isDisposed()) return;
        try {
            if (request("human-edit")) {
                String previous = actor.getName();
                ((CommandStack) original.getAdapter(CommandStack.class)).execute(new Command("Edición humana de aceptación") {
                    @Override public void execute() { actor.setName("Edición humana no guardada"); }
                    @Override public void undo() { actor.setName(previous); }
                    @Override public void redo() { execute(); }
                });
            }
            if (request("undo")) ((CommandStack) original.getAdapter(CommandStack.class)).undo();
            if (request("resume")) activateMenu("Reintentar tras resolver conflictos", false);
            if (request("snapshot")) {
                screenshot = "editor-" + System.currentTimeMillis() + ".png";
                Image image = DiagramUtils.createImage(editor.getGraphicalViewer(), 1, 10);
                try {
                    ImageLoader loader = new ImageLoader(); loader.data = new ImageData[] {image.getImageData()};
                    loader.save(evidence.resolve(screenshot).toString(), SWT.IMAGE_PNG);
                } finally { image.dispose(); }
            }
            writeState();
            if (request("stop")) {
                activateMenu("Actualizar modelos abiertos en vivo", false); service = null;
                // El controlador debe resolver dirty antes de pedir la salida, nunca descartarlo aquí.
                if (IEditorModelManager.INSTANCE.isModelDirty(original)) throw new IllegalStateException("La aceptación intentó cerrar con cambios humanos pendientes.");
                PlatformUI.getWorkbench().close(); return;
            }
            display.timerExec(200, this::pollCommands);
        } catch (Throwable error) { fail(error); }
    }

    private boolean request(String name) throws Exception {
        return Files.deleteIfExists(evidence.resolve(name + ".request"));
    }

    private void writeState() throws Exception {
        Properties state = new Properties();
        state.setProperty("name", actor.getName());
        state.setProperty("dirty", String.valueOf(IEditorModelManager.INSTANCE.isModelDirty(original)));
        state.setProperty("sameModelInstance", String.valueOf(IEditorModelManager.INSTANCE.getModels().contains(original)));
        state.setProperty("sameEditorInstance", String.valueOf(editor.getEditorSite().getPage().getEditorReferences().length > 0
                && editor.getEditorSite().getPage().findEditor(editor.getEditorInput()) == editor));
        state.setProperty("sameViewInstance", String.valueOf(editor.getModel() == originalView));
        state.setProperty("workbenchRunning", String.valueOf(PlatformUI.isWorkbenchRunning()));
        state.setProperty("liveVersion", org.osgi.framework.FrameworkUtil.getBundle(LivePlugin.class).getVersion().toString());
        state.setProperty("liveLocation", org.osgi.framework.FrameworkUtil.getBundle(LivePlugin.class).getLocation());
        state.setProperty("nativePackageAccepted", String.valueOf(nativePackageAccepted));
        state.setProperty("registeredVisuals", String.valueOf(editor.getGraphicalViewer().getEditPartRegistry().size()));
        state.setProperty("zoom", String.valueOf(((org.eclipse.gef.editparts.ScalableFreeformRootEditPart) editor.getGraphicalViewer().getRootEditPart()).getZoomManager().getZoom()));
        state.setProperty("selection", editor.getGraphicalViewer().getSelectedEditParts().stream().map(org.eclipse.gef.EditPart::getModel)
                .filter(IIdentifier.class::isInstance).map(IIdentifier.class::cast).map(IIdentifier::getId).collect(Collectors.joining("|")));
        if (otherModel != null) {
            state.setProperty("otherName", otherActor.getName());
            state.setProperty("otherDirty", String.valueOf(IEditorModelManager.INSTANCE.isModelDirty(otherModel)));
            state.setProperty("otherEditorClosed", String.valueOf(otherControl.isDisposed()));
            var otherTexts = new java.util.ArrayList<String>();
            if (!otherControl.isDisposed() && otherEditor.getGraphicalViewer().getContents() instanceof GraphicalEditPart part) figureTexts(part.getFigure(), otherTexts);
            state.setProperty("otherFigureTexts", String.join("|", otherTexts));
        }
        state.setProperty("menuActivated", String.valueOf(LivePlugin.get().service() == service));
        state.setProperty("registryConsistent", String.valueOf(registryConsistent(editor.getGraphicalViewer().getContents())));
        var names = new java.util.ArrayList<String>();
        original.eAllContents().forEachRemaining(o -> { if (o instanceof INameable n && !n.getName().isBlank()) names.add(n.getName()); });
        state.setProperty("modelNames", String.join("|", names));
        var figures = new java.util.ArrayList<String>();
        if (editor.getGraphicalViewer().getContents() instanceof GraphicalEditPart part) figureTexts(part.getFigure(), figures);
        state.setProperty("figureTexts", String.join("|", figures));
        var status = service.status().stream().filter(row -> row.get("modelId").equals(original.getId())).findFirst().orElse(java.util.Map.of());
        state.setProperty("liveState", String.valueOf(status.getOrDefault("state", "INITIALIZING")));
        state.setProperty("liveDetail", String.valueOf(status.getOrDefault("detail", "")));
        state.setProperty("captures", String.valueOf(status.getOrDefault("captures", 0)));
        state.setProperty("observations", String.valueOf(status.getOrDefault("observations", 0)));
        state.setProperty("fingerprint", String.valueOf(status.getOrDefault("fingerprint", "")));
        state.setProperty("screenshot", screenshot);
        Path temporary = evidence.resolve("state.tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) { state.store(output, "Estado observado del editor nativo"); }
        // Snapshots inmutables: un lector Windows no debe bloquear el reemplazo de su archivo abierto.
        Files.move(temporary, evidence.resolve(String.format(java.util.Locale.ROOT, "state-%08d.properties", ++stateSequence)),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private void figureTexts(org.eclipse.draw2d.IFigure figure, java.util.List<String> texts) {
        if (figure instanceof org.eclipse.draw2d.Label label) texts.add(label.getText());
        if (figure instanceof org.eclipse.draw2d.text.TextFlow text) texts.add(text.getText());
        for (Object child : figure.getChildren()) figureTexts((org.eclipse.draw2d.IFigure) child, texts);
    }

    private boolean registryConsistent(org.eclipse.gef.EditPart part) {
        if (part.getModel() != null && editor.getGraphicalViewer().getEditPartRegistry().get(part.getModel()) != part) return false;
        for (Object child : part.getChildren()) if (!registryConsistent((org.eclipse.gef.EditPart) child)) return false;
        return true;
    }

    /** Disparar el menú SWT/JFace que utilizará el operador, no llamar directamente al servicio. */
    private void activateMenu(String text, boolean selected) {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        var item = findMenu(window.getShell().getMenuBar(), text);
        if (item == null) throw new IllegalStateException("No se encontró la acción nativa del menú: " + text);
        item.setSelection(selected);
        item.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
    }

    private org.eclipse.swt.widgets.MenuItem findMenu(org.eclipse.swt.widgets.Menu menu, String text) {
        if (menu == null) return null;
        for (var item : menu.getItems()) {
            if (item.getText().replace("&", "").equals(text)) return item;
            var found = findMenu(item.getMenu(), text);
            if (found != null) return found;
        }
        return null;
    }

    private void fail(Throwable error) {
        failure = error.toString(); error.printStackTrace(System.err);
        try { Files.writeString(evidence.resolve("error.txt"), failure); }
        catch (Exception writeError) { writeError.printStackTrace(System.err); }
        if (service != null) { service.close(); service = null; }
        // No preguntar por guardar un ensayo fallido: mantener el proceso para inspección.
        // La limpieza del controlador sólo administra este proceso aislado.
    }

    @Override public void stop() {
        if (display != null && !display.isDisposed()) display.asyncExec(() -> PlatformUI.getWorkbench().close());
    }
}
