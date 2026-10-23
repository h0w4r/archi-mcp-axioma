package io.github.h0w4r.axioma.headless.core;

import static io.github.h0w4r.axioma.headless.core.Arguments.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.github.h0w4r.axioma.headless.archi.NativeOperations;
import io.github.h0w4r.axioma.headless.archi.ImageOperations;
import com.archimatetool.model.IArchimateModel;

/** Casos de uso compartidos por transportes: no conoce SWT ni el SDK MCP. */
public final class AxiomaService {
    private final ModelRegistry registry;
    private final NativeOperations operations;
    private final RenderPort renderer;
    private final java.util.function.BooleanSupplier workbenchRunning;
    private final String buildVersion;
    private final String nativeVersion;

    public AxiomaService(ModelRegistry registry, NativeOperations operations, RenderPort renderer, java.util.function.BooleanSupplier workbenchRunning,
            String buildVersion, String nativeVersion) {
        this.registry = registry; this.operations = operations; this.renderer = renderer; this.workbenchRunning = workbenchRunning;
        this.buildVersion = buildVersion; this.nativeVersion = nativeVersion;
    }

    public Object execute(String domain, Map<String, Object> args) {
        String correlation = UUID.randomUUID().toString();
        try {
            String action = required(args, "action");
            System.err.println("AXIOMA inicio correlationId=" + correlation + " operation=" + domain + "." + action);
            InputContract.validate(domain, action, args, false);
            Object result = dispatch(domain, action, args);
            System.err.println("AXIOMA completado correlationId=" + correlation);
            return Map.of("correlationId", correlation, "data", result);
        } catch (RuntimeException error) {
            System.err.println("AXIOMA fallo correlationId=" + correlation + " type=" + error.getClass().getSimpleName() + " message=" + error.getMessage());
            if (error.getCause() != null) error.getCause().printStackTrace(System.err);
            if (error instanceof AxiomaException known) throw known.correlated(correlation);
            throw new AxiomaException("INTERNAL_ERROR", "La operación falló; consulte el diagnóstico con su correlationId.", error).correlated(correlation);
        }
    }

    private Object dispatch(String domain, String action, Map<String, Object> args) {
        if (bool(args, "dryRun", false) && ((domain.equals("model") && List.of("create", "open", "reload", "close").contains(action))
                || (domain.equals("view") && action.equals("export")))) {
            throw new AxiomaException("DRY_RUN_UNSUPPORTED", "Esta operación de ciclo de vida o exportación no admite dryRun.");
        }
        if (domain.equals("runtime")) {
            if (!action.equals("info")) throw new AxiomaException("UNSUPPORTED_OPERATION", "Acción de runtime no soportada.");
            return Map.of("name", "Archi MCP Axioma", "version", "0.1.0", "mode", "headless",
                    "workbenchRunning", workbenchRunning.getAsBoolean(), "render", renderer.capabilities(),
                    "approvalMode", "direct", "workspace", registry.files().root().toString(),
                    "openModels", registry.list().size(), "buildVersion", buildVersion, "nativeVersion", nativeVersion);
        }
        if (domain.equals("model")) {
            return switch (action) {
                case "create" -> registry.create(required(args, "name"), required(args, "path"));
                case "open" -> registry.open(required(args, "path"));
                case "list" -> registry.list();
                case "info" -> registry.info(required(args, "modelId"));
                case "close" -> registry.close(required(args, "modelId"));
                case "reload" -> registry.reload(required(args, "modelId"));
                case "validate" -> Map.of("errors", registry.validate(required(args, "modelId")));
                case "history" -> registry.history(required(args, "modelId"));
                case "restore" -> registry.restore(required(args, "modelId"), args);
                case "save" -> registry.mutate(required(args, "modelId"), args, model -> Map.of("saved", true));
                case "update" -> registry.mutate(required(args, "modelId"), args, model -> operations.execute(model, domain, args));
                default -> throw new AxiomaException("UNSUPPORTED_OPERATION", "Acción de modelo no soportada: " + action);
            };
        }
        if (domain.equals("batch")) {
            if (!action.equals("apply")) throw new AxiomaException("UNSUPPORTED_OPERATION", "Solo está disponible batch.apply.");
            return registry.mutate(required(args, "modelId"), args, model -> {
                if (!(args.get("operations") instanceof List<?> batch) || batch.isEmpty()) {
                    throw new AxiomaException("INVALID_ARGUMENT", "operations debe contener al menos una operación.");
                }
                List<Object> results = new ArrayList<>();
                Map<String, Object> references = new LinkedHashMap<>();
                java.util.Set<String> affected = new java.util.LinkedHashSet<>();
                for (Object entry : batch) {
                    if (!(entry instanceof Map<?, ?> raw)) throw new AxiomaException("INVALID_ARGUMENT", "Cada operación debe ser un objeto.");
                    @SuppressWarnings("unchecked") Map<String, Object> op = (Map<String, Object>) resolve(raw, references);
                    String opDomain = required(op, "domain");
                    String opAction = required(op, "action");
                    InputContract.validate(opDomain, opAction, op, true);
                    if (op.containsKey("modelId") && !required(op, "modelId").equals(model.getId())) throw new AxiomaException("CROSS_MODEL_BATCH", "El lote sólo opera sobre su modelId.");
                    if (op.containsKey("dryRun") || op.containsKey("expectedRevision") || op.containsKey("expectedFingerprint")) throw new AxiomaException("INVALID_BATCH_CONTROL", "Los controles de transacción corresponden al lote, no a cada operación.");
                    if (!List.of("element", "relationship", "view", "folder", "profile", "image").contains(opDomain)) {
                        throw new AxiomaException("INVALID_BATCH_DOMAIN", "Dominio no permitido en batch: " + opDomain);
                    }
                    Object result = executeNative(model, opDomain, op);
                    if (result instanceof Map<?, ?> value && value.get("id") instanceof String objectId
                            && !opDomain.equals("image")) affected.add(objectId);
                    results.add(result);
                    System.err.println("AXIOMA lote: operaciones completadas=" + results.size() + "/" + batch.size());
                    if (op.containsKey("as")) {
                        String alias = required(op, "as");
                        if (!alias.matches("[A-Za-z][A-Za-z0-9_-]*")) throw new AxiomaException("INVALID_ALIAS", "El alias debe comenzar con una letra y contener letras, números, guion o guion bajo.");
                        if (references.putIfAbsent(alias, result) != null) throw new AxiomaException("DUPLICATE_ALIAS", "Alias duplicado: " + alias);
                    }
                }
                // Los resultados de cada paso son instantáneas intermedias, no el estado final del lote.
                Map<String, Object> effective = new LinkedHashMap<>();
                for (String objectId : affected) {
                    var object = com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, objectId);
                    effective.put(objectId, object == null ? Map.of("id", objectId, "deleted", true) : operations.describe(object));
                }
                references.replaceAll((alias, value) -> value instanceof Map<?, ?> row && row.get("id") instanceof String objectId
                        && effective.containsKey(objectId) ? effective.get(objectId) : value);
                return Map.of("operations", results, "operationResultsAreIntermediate", true,
                        "effectiveObjects", effective, "references", references);
            });
        }
        if (List.of("element", "relationship").contains(domain) && List.of("types", "schema").contains(action)) return operations.execute(null, domain, args);
        String modelId = required(args, "modelId");
        if (domain.equals("view") && action.equals("export")) return export(modelId, args);
        if (domain.equals("image")) {
            if (action.equals("list")) return registry.read(modelId, model -> executeNative(model, domain, args));
            return registry.mutate(modelId, args, model -> executeNative(model, domain, args));
        }
        if (operations.readOnly(domain, action)) return registry.read(modelId, model -> operations.execute(model, domain, args));
        return registry.mutate(modelId, args, model -> operations.execute(model, domain, args));
    }

    private Object executeNative(IArchimateModel model, String domain, Map<String, Object> args) {
        return domain.equals("image") ? ImageOperations.execute(model, registry.files(), operations, args) : operations.execute(model, domain, args);
    }

    private Object export(String modelId, Map<String, Object> args) {
        String format = text(args, "format", "svg").toLowerCase(java.util.Locale.ROOT);
        var path = registry.files().resolve(required(args, "path"));
        if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith("." + format)) throw new AxiomaException("INVALID_ARGUMENT", "La extensión debe coincidir con el formato.");
        return registry.read(modelId, model -> {
            var object = operations.find(model, required(args, "viewId"));
            if (!(object instanceof com.archimatetool.model.IDiagramModel view)) throw new AxiomaException("WRONG_OBJECT_TYPE", "viewId no es una vista.");
            if (java.nio.file.Files.exists(path) && !bool(args, "overwrite", false)) throw new AxiomaException("FILE_EXISTS", "La exportación requiere otra ruta u overwrite=true.");
            byte[] bytes = renderer.render(view, format);
            java.nio.file.Path temporary = null;
            try {
                registry.files().resolve(path.toString());
                java.nio.file.Files.createDirectories(path.getParent());
                temporary = java.nio.file.Files.createTempFile(path.getParent(), ".axioma-export-", ".tmp");
                java.nio.file.Files.write(temporary, bytes);
                // No reemplazar archivos existentes de forma implícita.
                if (bool(args, "overwrite", false)) java.nio.file.Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                else java.nio.file.Files.move(temporary, path);
                return Map.of("path", path.toString(), "format", format, "bytes", bytes.length,
                        "fingerprint", io.github.h0w4r.axioma.headless.persistence.WorkspaceFiles.fingerprint(path), "transparentBackground", true);
            } catch (java.io.IOException error) { throw new AxiomaException("EXPORT_WRITE_FAILED", "No se pudo publicar la exportación.", error); }
            finally { if (temporary != null) try { java.nio.file.Files.deleteIfExists(temporary); } catch (java.io.IOException ignored) {} }
        });
    }

    /** Sustituye referencias completas también dentro de listas y objetos anidados. */
    private Object resolve(Object input, Map<String, Object> references) {
        if (input instanceof String text && text.matches("\\$[A-Za-z][A-Za-z0-9_-]*\\.id")) {
            String alias = text.substring(1, text.length() - 3);
            if (!(references.get(alias) instanceof Map<?, ?> value) || value.get("id") == null) {
                throw new AxiomaException("UNRESOLVED_REFERENCE", "Referencia no disponible: " + text);
            }
            return value.get("id");
        }
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), resolve(value, references)));
            return result;
        }
        if (input instanceof List<?> list) return list.stream().map(value -> resolve(value, references)).toList();
        return input;
    }
}
