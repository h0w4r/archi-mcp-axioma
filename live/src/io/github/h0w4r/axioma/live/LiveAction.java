package io.github.h0w4r.axioma.live;

import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkbenchWindowActionDelegate;

/** Acciones de la GUI: el operador habilita la observación o reintenta un conflicto real. */
public final class LiveAction implements IWorkbenchWindowActionDelegate {
    @Override public void init(IWorkbenchWindow window) {}
    @Override public void run(IAction action) {
        var plugin = LivePlugin.get();
        if (action.getId().endsWith(".resume")) { if (plugin.service() != null) plugin.service().resume(); }
        else plugin.enable(action.isChecked());
    }
    @Override public void selectionChanged(IAction action, ISelection selection) {
        if (action.getId().endsWith(".toggle") && LivePlugin.get() != null) action.setChecked(LivePlugin.get().getPreferenceStore().getBoolean(LivePlugin.ENABLED));
    }
    @Override public void dispose() {}
}
