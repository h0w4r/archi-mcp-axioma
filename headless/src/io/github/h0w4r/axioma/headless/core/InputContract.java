package io.github.h0w4r.axioma.headless.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Contrato transversal: un parámetro escrito incorrectamente nunca se ignora. */
public final class InputContract {
    private static final String METADATA = "name documentation properties attributes features content";
    private static final String VISUAL = METADATA + " parentId x y width height";
    private static final Set<String> READ = Set.of("info", "get", "list", "types", "schema", "validTypes", "validate", "history");
    public record Fields(Set<String> allowed, List<String> required) {}
    private InputContract() {}

    public static Fields fields(String domain, String action) {
        String required = "";
        String optional = "";
        switch (domain) {
            case "runtime" -> { if (!action.equals("info")) unsupported(domain, action); }
            case "model" -> {
                switch (action) {
                    case "create" -> required = "name path";
                    case "open" -> required = "path";
                    case "list" -> {}
                    case "info", "close", "reload", "save", "validate", "history" -> {}
                    case "update" -> optional = METADATA;
                    case "restore" -> required = "fingerprint";
                    default -> unsupported(domain, action);
                }
            }
            case "element", "relationship" -> {
                switch (action) {
                    case "types" -> {}
                    case "schema" -> required = "type";
                    case "list" -> optional = "query";
                    case "get", "delete" -> required = "id";
                    case "update" -> { required = "id"; optional = METADATA; }
                    case "create" -> {
                        required = domain.equals("element") ? "type name" : "type sourceId targetId";
                        optional = METADATA + " folderId";
                    }
                    case "validTypes", "reconnect" -> {
                        if (!domain.equals("relationship")) unsupported(domain, action);
                        required = action.equals("reconnect") ? "id sourceId targetId" : "sourceId targetId";
                    }
                    default -> unsupported(domain, action);
                }
            }
            case "view" -> {
                switch (action) {
                    case "create" -> { required = "name"; optional = METADATA + " folderId viewpoint"; }
                    case "get", "delete", "removeObject" -> required = "id";
                    case "list" -> optional = "query";
                    case "update" -> { required = "id"; optional = METADATA; }
                    case "updateObject" -> { required = "id"; optional = METADATA + " x y width height"; }
                    case "addElement" -> { required = "viewId elementId"; optional = VISUAL; }
                    case "addGroup", "addNote" -> { required = "viewId"; optional = VISUAL; }
                    case "addReference" -> { required = "viewId referencedViewId"; optional = VISUAL; }
                    case "addImage" -> { required = "viewId imagePath"; optional = VISUAL; }
                    case "addConnection" -> { required = "viewId relationshipId sourceVisualId targetVisualId"; optional = METADATA; }
                    case "addLine" -> { required = "viewId sourceVisualId targetVisualId"; optional = METADATA; }
                    case "moveObject" -> { required = "id viewId"; optional = "parentId x y width height"; }
                    case "setBendpoints" -> required = "id bendpoints";
                    case "layout" -> { required = "viewId"; optional = "direction spacing"; }
                    case "export" -> { required = "viewId path"; optional = "format overwrite"; }
                    default -> unsupported(domain, action);
                }
            }
            case "folder" -> {
                switch (action) {
                    case "create" -> { required = "name parentId"; }
                    case "list" -> optional = "query";
                    case "get", "delete" -> required = "id";
                    case "move" -> required = "id folderId";
                    case "update" -> { required = "id"; optional = METADATA; }
                    default -> unsupported(domain, action);
                }
            }
            case "profile" -> {
                switch (action) {
                    case "create" -> { required = "name type"; optional = METADATA; }
                    case "get", "delete" -> required = "id";
                    case "list" -> optional = "query";
                    case "update" -> { required = "id"; optional = METADATA; }
                    case "assign" -> { required = "id conceptId"; optional = "remove"; }
                    default -> unsupported(domain, action);
                }
            }
            case "image" -> {
                switch (action) {
                    case "add" -> { required = "path"; optional = "id"; }
                    case "assign" -> { required = "id"; optional = "imagePath"; }
                    case "list" -> {}
                    default -> unsupported(domain, action);
                }
            }
            case "batch" -> { if (!action.equals("apply")) unsupported(domain, action); required = "operations"; }
            default -> unsupported(domain, action);
        }
        List<String> needed = words(required);
        boolean catalog = domain.equals("runtime") || (List.of("element", "relationship").contains(domain) && List.of("types", "schema").contains(action));
        boolean lifecycleWithoutId = domain.equals("model") && List.of("create", "open", "list").contains(action);
        if (!catalog && !lifecycleWithoutId) needed.add("modelId");
        Set<String> allowed = new LinkedHashSet<>(needed);
        allowed.add("action"); allowed.addAll(words(optional));
        // Permitir modelId opcional en catálogos por compatibilidad, sin depender de él.
        if (catalog && !domain.equals("runtime")) allowed.add("modelId");
        if (!READ.contains(action)) allowed.add("dryRun");
        boolean transaction = !READ.contains(action) && !(domain.equals("model") && List.of("create", "open", "close", "reload").contains(action))
                && !(domain.equals("view") && action.equals("export"));
        if (transaction) { allowed.add("expectedRevision"); allowed.add("expectedFingerprint"); }
        return new Fields(Set.copyOf(allowed), List.copyOf(needed));
    }

    public static void validate(String domain, String action, Map<String, Object> args, boolean batch) {
        Fields fields = fields(domain, action);
        for (String key : args.keySet()) {
            if (!fields.allowed().contains(key) && !(batch && Set.of("domain", "as").contains(key))) {
                throw new AxiomaException("UNKNOWN_ARGUMENT", "Parámetro no admitido para " + domain + "." + action + ": " + key);
            }
        }
        for (String key : fields.required()) {
            if (batch && key.equals("modelId")) continue;
            if (!args.containsKey(key) || args.get(key) == null) throw new AxiomaException("INVALID_ARGUMENT", "Falta el parámetro requerido: " + key);
        }
    }

    private static List<String> words(String text) {
        return text.isBlank() ? new ArrayList<>() : new ArrayList<>(List.of(text.split(" +")));
    }
    private static void unsupported(String domain, String action) {
        throw new AxiomaException("UNSUPPORTED_OPERATION", "Operación no admitida: " + domain + "." + action);
    }
}
