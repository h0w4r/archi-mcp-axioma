package io.github.h0w4r.axioma.live;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import org.eclipse.emf.ecore.EObject;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.util.LightweightEContentAdapter;

/** Aísla a GEF de estados intermedios sin desactivar EMF ni el registro transaccional. */
final class GuiNotificationFence implements AutoCloseable {
    private final IArchimateModel model;
    private final Map<LightweightEContentAdapter, java.util.List<EObject>> registrations = new IdentityHashMap<>();

    GuiNotificationFence(IArchimateModel model) {
        this.model = model;
        var objects = new ArrayList<EObject>(); objects.add(model);
        model.eAllContents().forEachRemaining(objects::add);
        // Un mismo adaptador de GEF puede observar el concepto lógico Y su representación visual.
        // Conservar todas las inscripciones, no sólo una raíz de contención.
        for (EObject object : objects) for (var adapter : new ArrayList<>(object.eAdapters())) {
            if (adapter instanceof LightweightEContentAdapter gui) registrations.computeIfAbsent(gui, key -> new ArrayList<>()).add(object);
        }
        registrations.forEach((adapter, owners) -> owners.forEach(owner -> owner.eAdapters().remove(adapter)));
    }

    @Override public void close() {
        registrations.forEach((adapter, owners) -> owners.forEach(root -> {
            // No volver a conectar listeners de controles cuyo objeto dejó de pertenecer al modelo.
            boolean attached = root == model;
            for (EObject current = root; !attached && current != null; current = current.eContainer()) attached = current == model;
            if (attached && !root.eAdapters().contains(adapter)) root.eAdapters().add(adapter);
        }));
    }
}
