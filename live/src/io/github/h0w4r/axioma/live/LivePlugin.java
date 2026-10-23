package io.github.h0w4r.axioma.live;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/** Ciclo de vida del adaptador gráfico opcional; nunca inicia el servidor MCP. */
public final class LivePlugin extends AbstractUIPlugin {
    public static final String ENABLED = "actualizacionEnVivo";
    private static LivePlugin instance;
    private LiveSyncService service;
    private org.eclipse.ui.IWorkbench observedWorkbench;
    private final org.eclipse.ui.IWorkbenchListener shutdown = new org.eclipse.ui.IWorkbenchListener() {
        @Override public boolean preShutdown(org.eclipse.ui.IWorkbench workbench, boolean forced) { return true; }
        @Override public void postShutdown(org.eclipse.ui.IWorkbench workbench) {
            if (service != null) { service.close(); service = null; }
        }
    };
    public static LivePlugin get() { return instance; }
    @Override public void start(BundleContext context) throws Exception { super.start(context); instance = this; }
    public LiveSyncService service() { return service; }
    public void enable(boolean enabled) {
        getPreferenceStore().setValue(ENABLED, enabled);
        // earlyStartup puede coincidir con el primer clic del operador: habilitar debe ser idempotente.
        if (enabled) {
            if (service == null && PlatformUI.isWorkbenchRunning()) {
                if (observedWorkbench == null) { observedWorkbench = PlatformUI.getWorkbench(); observedWorkbench.addWorkbenchListener(shutdown); }
                service = new LiveSyncService(Display.getCurrent(), 750);
            }
        } else if (service != null) { service.close(); service = null; }
    }
    @Override public void stop(BundleContext context) throws Exception {
        if (service != null) { service.close(); service = null; }
        if (observedWorkbench != null) observedWorkbench.removeWorkbenchListener(shutdown);
        instance = null; super.stop(context);
    }
}
