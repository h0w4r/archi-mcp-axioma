package io.github.h0w4r.axioma.live;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.draw2d.FigureCanvas;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.gef.EditPart;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.IWorkbenchPage;
import com.archimatetool.editor.diagram.DiagramEditorInput;
import com.archimatetool.editor.diagram.IDiagramModelEditor;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModel;

/** Mantiene los editores y el viewport, separando el ciclo de vida GEF del cambio de datos EMF. */
final class GuiRefreshTransaction implements AutoCloseable {
    private record Scene(IDiagramModelEditor editor, List<Object> selected, Point location) {}
    private final List<Scene> scenes = new ArrayList<>();
    private record RemovedView(IWorkbenchPage page, IDiagramModel view, String editorId) {}
    private final List<RemovedView> removed = new ArrayList<>();
    private final IArchimateModel model;

    GuiRefreshTransaction(IArchimateModel model, java.util.Set<String> incomingViews) {
        this.model = model;
        if (!PlatformUI.isWorkbenchRunning()) return;
        for (var window : PlatformUI.getWorkbench().getWorkbenchWindows()) for (var page : window.getPages()) {
            for (var reference : page.getEditorReferences()) {
                if (reference.getEditor(false) instanceof IDiagramModelEditor editor && editor.getModel().getArchimateModel() == model) {
                    if (!incomingViews.contains(editor.getModel().getId())) {
                        removed.add(new RemovedView(page, editor.getModel(), editor.getEditorSite().getId()));
                        // Cerrar antes de borrar las referencias que el dispose nativo necesita consultar.
                        if (!page.closeEditor(editor, false)) throw new IllegalStateException("No se pudo cerrar una vista eliminada externamente.");
                        continue;
                    }
                    var viewer = editor.getGraphicalViewer();
                    var selected = viewer.getSelectedEditParts().stream().map(EditPart::getModel).toList();
                    Point location = viewer.getControl() instanceof FigureCanvas canvas ? canvas.getViewport().getViewLocation().getCopy() : null;
                    scenes.add(new Scene(editor, selected, location));
                    viewer.getControl().setRedraw(false);
                }
            }
        }
        suspend();
    }

    /** Desactivar mientras las referencias antiguas siguen íntegras, antes de borrar o reconectar objetos. */
    void suspend() {
        for (var scene : scenes) {
            var viewer = scene.editor().getGraphicalViewer();
            if (!viewer.getControl().isDisposed()) {
                viewer.deselectAll(); viewer.setContents((EditPart) null);
            }
        }
    }

    /** Recrear sólo las figuras; el editor, el modelo de vista, su control y el zoom siguen siendo los mismos. */
    void restore() {
        for (var old : removed) {
            if (old.view().getArchimateModel() == model && old.page().findEditor(new DiagramEditorInput(old.view())) == null) {
                // Sólo ocurre si el cambio de datos fue revertido: recuperar también la pestaña.
                try { old.page().openEditor(new DiagramEditorInput(old.view()), old.editorId(), false); }
                catch (org.eclipse.ui.PartInitException error) { throw new IllegalStateException("No se pudo recuperar la pestaña tras revertir.", error); }
            }
        }
        for (var scene : scenes) {
            var viewer = scene.editor().getGraphicalViewer();
            if (viewer.getControl().isDisposed()) continue;
            viewer.setContents(scene.editor().getModel());
            viewer.flush();
            List<EditPart> selected = new ArrayList<>();
            for (Object object : scene.selected()) {
                var part = viewer.getEditPartRegistry().get(object);
                if (part != null) selected.add(part);
            }
            viewer.setSelection(new StructuredSelection(selected));
            if (scene.location() != null && viewer.getControl() instanceof FigureCanvas canvas) canvas.getViewport().setViewLocation(scene.location());
        }
    }

    @Override public void close() {
        for (var scene : scenes) {
            var control = scene.editor().getGraphicalViewer().getControl();
            if (!control.isDisposed()) { control.setRedraw(true); control.redraw(); }
        }
    }
}
