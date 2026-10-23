package io.github.h0w4r.axioma.headless.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Catálogo público con parámetros descubribles, sin esquemas abiertos e implícitos. */
final class ToolCatalog {
    private ToolCatalog() {}
    static Map<String, List<String>> domains() {
        Map<String, List<String>> domains = new LinkedHashMap<>();
        domains.put("runtime", List.of("info"));
        domains.put("model", List.of("create", "open", "list", "info", "update", "save", "reload", "close", "validate", "history", "restore"));
        domains.put("element", List.of("create", "get", "list", "update", "delete", "types", "schema"));
        domains.put("relationship", List.of("create", "get", "list", "update", "delete", "types", "schema", "validTypes", "reconnect"));
        domains.put("view", List.of("create", "get", "list", "update", "delete", "addElement", "addConnection", "addGroup", "addNote", "addReference", "addImage", "addLine", "updateObject", "removeObject", "moveObject", "setBendpoints", "layout", "export"));
        domains.put("folder", List.of("create", "get", "list", "update", "move", "delete"));
        domains.put("profile", List.of("create", "get", "list", "update", "delete", "assign"));
        domains.put("image", List.of("add", "list", "assign"));
        domains.put("batch", List.of("apply"));
        return domains;
    }

    static Map<String, Object> schema(String domain, List<String> actions) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", actions));
        text(properties, "modelId", "ID explícito del modelo, devuelto al crear o abrir; no hay modelo activo global.");
        properties.put("expectedRevision", Map.of("type", "integer", "minimum", 0, "description", "Revisión de sesión esperada; consultar model.info tras reiniciar."));
        text(properties, "expectedFingerprint", "SHA-256 esperado del archivo, útil entre reinicios.");
        text(properties, "fingerprint", "model.restore: SHA-256 de la versión a recuperar, consultable con model.history.");
        properties.put("dryRun", Map.of("type", "boolean", "default", false, "description", "Valida una mutación sin persistirla; no se aplica a create/open/reload/close/export."));
        text(properties, "id", "ID del objeto de este modelo para get/update/delete; en view.updateObject es el ID visual.");
        text(properties, "name", "Nombre del elemento, relación, vista, carpeta o perfil.");
        text(properties, "documentation", "Documentación del objeto; en model.update corresponde al propósito.");
        text(properties, "content", "Texto de una nota visual, usado por view.addNote o view.updateObject.");
        text(properties, "query", "Filtro de nombres para list, sin distinguir mayúsculas.");
        text(properties, "type", "Tipo nativo, consultable con element.types o relationship.types. Ej.: BusinessActor, AssignmentRelationship.");
        properties.put("properties", Map.of("type", "object", "additionalProperties", Map.of("type", List.of("string", "null")), "description", "Propiedades de usuario: clave/texto; null elimina la clave."));
        properties.put("features", Map.of("type", "object", "additionalProperties", Map.of("type", List.of("string", "number", "boolean", "null")), "description", "Features nativas de presentación; null elimina la feature."));
        properties.put("attributes", Map.of("type", "object", "additionalProperties", Map.of("type", List.of("string", "number", "boolean", "null")), "description", "Atributos escalares del metamodelo, por ejemplo fillColor, font, type de Junction, accessType. No referencias ni IDs."));
        text(properties, "folderId", "Carpeta destino de la capa correspondiente.");
        text(properties, "parentId", "Carpeta padre al crear carpeta; contenedor visual al añadir/mover objetos en una vista.");
        text(properties, "path", "Ruta dentro del workspace. model.create no sobrescribe; image.add lee; view.export escribe.");
        text(properties, "sourceId", "Concepto origen lógico, puede ser un elemento o una relación.");
        text(properties, "targetId", "Concepto destino lógico, puede ser un elemento o una relación.");
        text(properties, "viewId", "ID de la vista que contiene los objetos visuales.");
        text(properties, "elementId", "ID del elemento lógico que se representará.");
        text(properties, "relationshipId", "ID de la relación lógica que se representará.");
        text(properties, "sourceVisualId", "ID del extremo visual origen, objeto o conexión.");
        text(properties, "targetVisualId", "ID del extremo visual destino, objeto o conexión.");
        text(properties, "referencedViewId", "Vista referenciada por view.addReference.");
        text(properties, "viewpoint", "Identificador nativo del punto de vista; el modelo no se restringe a una plantilla fija.");
        text(properties, "imagePath", "ID de imagen incorporada al archivo, devuelto por image.add.");
        text(properties, "conceptId", "Concepto al que se asigna el perfil indicado por id.");
        properties.put("remove", Map.of("type", "boolean", "description", "profile.assign: retirar la asignación."));
        for (String key : List.of("x", "y", "width", "height", "spacing")) {
            Map<String, Object> number = new LinkedHashMap<>(Map.of("type", "integer"));
            if (List.of("width", "height", "spacing").contains(key)) number.put("minimum", 1);
            number.put("description", "Píxeles; x/y relativos al contenedor. spacing se usa en ELK layout.");
            properties.put(key, number);
        }
        properties.put("direction", Map.of("type", "string", "enum", List.of("DOWN", "UP", "LEFT", "RIGHT"), "default", "DOWN"));
        properties.put("format", Map.of("type", "string", "enum", List.of("svg", "png"), "default", "svg"));
        properties.put("overwrite", Map.of("type", "boolean", "default", false));
        Map<String, Object> bend = new LinkedHashMap<>();
        for (String key : List.of("startX", "startY", "endX", "endY")) bend.put(key, Map.of("type", "integer"));
        properties.put("bendpoints", Map.of("type", "array", "description", "Offsets nativos desde los centros origen/destino; lista vacía limpia la ruta.",
                "items", Map.of("type", "object", "properties", bend, "required", List.of("startX", "startY", "endX", "endY"), "additionalProperties", false)));
        properties.put("operations", Map.of("type", "array", "minItems", 1, "description", "Lote atómico. Cada objeto usa domain, action, parámetros y as opcional. $alias.id refiere a un resultado anterior.",
                "items", Map.of("type", "object", "required", List.of("domain", "action"))));
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
        List<Object> alternatives = new java.util.ArrayList<>();
        for (String action : actions) {
            var fields = io.github.h0w4r.axioma.headless.core.InputContract.fields(domain, action);
            allowed.addAll(fields.allowed());
            List<String> required = new java.util.ArrayList<>(fields.required()); required.add("action");
            alternatives.add(Map.of("properties", Map.of("action", Map.of("const", action)), "required", required));
        }
        properties.keySet().retainAll(allowed);
        return Map.of("type", "object", "properties", properties, "required", List.of("action"), "oneOf", alternatives, "additionalProperties", false);
    }

    private static void text(Map<String, Object> properties, String key, String description) {
        properties.put(key, Map.of("type", "string", "description", description));
    }
}
