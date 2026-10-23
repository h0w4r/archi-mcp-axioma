package io.github.h0w4r.axioma.live;

import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

/** Activación opcional persistente, deshabilitada en una instalación nueva. */
public final class LiveStartup implements IStartup {
    @Override public void earlyStartup() {
        PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
            var plugin = LivePlugin.get();
            if (plugin != null && plugin.getPreferenceStore().getBoolean(LivePlugin.ENABLED)) plugin.enable(true);
        });
    }
}
