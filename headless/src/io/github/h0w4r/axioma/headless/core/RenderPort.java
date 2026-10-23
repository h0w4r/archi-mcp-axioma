package io.github.h0w4r.axioma.headless.core;

import java.util.Map;
import com.archimatetool.model.IDiagramModel;

/** Puerto de render: el núcleo no importa SWT, Batik ni un proveedor de exportación. */
public interface RenderPort {
    byte[] render(IDiagramModel view, String format);
    Map<String, Object> capabilities();
}
