package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.archimatetool.model.*;
import com.archimatetool.model.util.ArchimateModelUtils;
import io.github.h0w4r.axioma.headless.core.AxiomaException;

/** Puente del motor geométrico original hacia la transacción headless de Axioma. */
public final class AxiomaLayoutBridge {
    private AxiomaLayoutBridge() {}

    public static synchronized Object layout(IArchimateModel model, IDiagramModel view, String direction, int spacing) {
        if (!List.of("DOWN", "UP", "LEFT", "RIGHT").contains(direction) || spacing < 1) {
            throw new AxiomaException("INVALID_ARGUMENT", "direction: DOWN/UP/LEFT/RIGHT; spacing debe ser positivo.");
        }
        List<LayoutNode> nodes = new ArrayList<>();
        List<LayoutEdge> edges = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        view.eAllContents().forEachRemaining(object -> {
            if (object instanceof IDiagramModelObject visual) {
                IBounds b = visual.getBounds();
                String parent = visual.eContainer() instanceof IDiagramModelObject p ? p.getId() : null;
                nodes.add(new LayoutNode(visual.getId(), b.getX(), b.getY(), b.getWidth(), b.getHeight(), parent));
            }
            if (object instanceof IDiagramModelConnection connection) {
                if (connection.getSource() instanceof IDiagramModelObject && connection.getTarget() instanceof IDiagramModelObject) {
                    int label = connection.isNameVisible() ? connection.getName().length() * 7 + 10 : 0;
                    edges.add(new LayoutEdge(connection.getSource().getId(), connection.getTarget().getId(), connection.getId(), label));
                } else excluded.add(connection.getId());
            }
        });
        if (nodes.isEmpty()) return Map.of("elementsRepositioned", 0, "connectionsRouted", 0, "notRouted", excluded);
        var result = new ElkLayoutEngine().computeLayout(nodes, edges, direction, spacing);
        for (var position : result.positions()) {
            var visual = (IDiagramModelObject) ArchimateModelUtils.getObjectByID(model, position.viewObjectId());
            visual.setBounds(position.x(), position.y(), position.width(), position.height());
        }
        for (var entry : result.connectionBendpoints().entrySet()) {
            var connection = (IDiagramModelConnection) ArchimateModelUtils.getObjectByID(model, entry.getKey());
            int[] source = center((IDiagramModelObject) connection.getSource());
            int[] target = center((IDiagramModelObject) connection.getTarget());
            connection.getBendpoints().clear();
            for (var point : entry.getValue()) {
                var bend = IArchimateFactory.eINSTANCE.createDiagramModelBendpoint();
                bend.setStartX(point.x() - source[0]); bend.setStartY(point.y() - source[1]);
                bend.setEndX(point.x() - target[0]); bend.setEndY(point.y() - target[1]);
                connection.getBendpoints().add(bend);
            }
        }
        view.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_BENDPOINT);
        return Map.of("elementsRepositioned", result.elementsRepositioned(),
                "connectionsRouted", result.connectionsRouted(), "notRouted", excluded);
    }

    /** Los bendpoints nativos guardan offsets desde los centros absolutos de los extremos. */
    public static int[] center(IDiagramModelObject object) {
        int x = object.getBounds().getX() + object.getBounds().getWidth() / 2;
        int y = object.getBounds().getY() + object.getBounds().getHeight() / 2;
        for (var parent = object.eContainer(); parent instanceof IDiagramModelObject p; parent = parent.eContainer()) {
            x += p.getBounds().getX(); y += p.getBounds().getY();
        }
        return new int[] {x, y};
    }
}
