package io.github.h0w4r.axioma.headless.archi;

import static io.github.h0w4r.axioma.headless.core.Arguments.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import com.archimatetool.model.*;
import com.archimatetool.model.util.ArchimateModelUtils;
import io.github.h0w4r.axioma.headless.core.AxiomaException;
import net.vheerden.archi.mcp.model.AxiomaLayoutBridge;

/** Adaptador del metamodelo oficial: no necesita selección ni hilo de un editor. */
public final class NativeOperations {
    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;

    public Object execute(IArchimateModel model, String domain, Map<String, Object> args) {
        String action = required(args, "action");
        // Un ID válido nunca habilita acciones de otro dominio por accidente.
        if (args.containsKey("id")) {
            EObject target = find(model, required(args, "id"));
            boolean visual = domain.equals("view") && Set.of("updateObject", "removeObject", "setBendpoints", "moveObject").contains(action);
            Class<?> expected = visual ? IDiagramModelComponent.class : switch (domain) {
                case "element" -> IArchimateElement.class;
                case "relationship" -> IArchimateRelationship.class;
                case "view" -> IDiagramModel.class;
                case "folder" -> action.equals("move") ? IArchimateModelObject.class : IFolder.class;
                case "profile" -> IProfile.class;
                default -> EObject.class;
            };
            require(target, expected, "id");
        }
        return switch (domain + "." + action) {
            case "model.update" -> update(model, args);
            case "element.types" -> types(false);
            case "relationship.types" -> types(true);
            case "element.schema", "relationship.schema" -> schema(resolveType(required(args, "type"), domain.equals("relationship")));
            case "element.list" -> list(model, IArchimateElement.class, args);
            case "relationship.list" -> list(model, IArchimateRelationship.class, args);
            case "view.list" -> list(model, IDiagramModel.class, args);
            case "folder.list" -> list(model, IFolder.class, args);
            case "profile.list" -> list(model, IProfile.class, args);
            case "element.get", "relationship.get", "folder.get", "profile.get" -> describe(find(model, required(args, "id")));
            case "view.get" -> viewContents(find(model, required(args, "id")));
            case "element.create" -> createElement(model, args);
            case "relationship.create" -> createRelationship(model, args);
            case "relationship.validTypes" -> validTypes(model, args);
            case "relationship.reconnect" -> reconnect(model, args);
            case "view.create" -> createView(model, args);
            case "view.addElement" -> addElement(model, args);
            case "view.addConnection" -> addConnection(model, args);
            case "view.addGroup" -> addAnnotation(model, args, true);
            case "view.addNote" -> addAnnotation(model, args, false);
            case "view.addReference" -> addReference(model, args);
            case "view.addImage" -> addImage(model, args);
            case "view.addLine" -> addLine(model, args);
            case "view.setBendpoints" -> setBendpoints(model, args);
            case "view.moveObject" -> moveObject(model, args);
            case "view.layout" -> AxiomaLayoutBridge.layout(model, require(find(model, required(args, "viewId")), IDiagramModel.class, "viewId"),
                    text(args, "direction", "DOWN"), integer(args, "spacing", 50));
            case "profile.create" -> createProfile(model, args);
            case "profile.assign" -> assignProfile(model, args);
            case "folder.create" -> createFolder(model, args);
            case "folder.move" -> move(model, args);
            case "element.update", "relationship.update", "view.update", "view.updateObject", "folder.update", "profile.update" ->
                update(find(model, required(args, "id")), args);
            case "element.delete", "relationship.delete", "view.delete", "folder.delete", "view.removeObject", "profile.delete" ->
                delete(model, args);
            default -> throw new AxiomaException("UNSUPPORTED_OPERATION", "Operación no disponible: " + domain + "." + action);
        };
    }

    public boolean readOnly(String domain, String action) {
        return Set.of("get", "list", "types", "validTypes", "schema").contains(action);
    }

    private Object schema(EClass type) {
        return Map.of("type", type.getName(), "attributes", type.getEAllAttributes().stream()
                .filter(a -> a.isChangeable() && !a.isDerived() && !a.isMany() && !Set.of("id", "name", "documentation").contains(a.getName()))
                .map(a -> Map.of("name", a.getName(), "type", a.getEAttributeType().getName(),
                        "default", String.valueOf(a.getDefaultValue()))).toList());
    }

    private List<EObject> contents(IArchimateModel model) {
        List<EObject> result = new ArrayList<>();
        result.add(model);
        model.eAllContents().forEachRemaining(result::add);
        return result;
    }

    public EObject find(IArchimateModel model, String id) {
        EObject found = ArchimateModelUtils.getObjectByID(model, id);
        if (found == null) throw new AxiomaException("OBJECT_NOT_FOUND", "El objeto no existe en el modelo solicitado: " + id);
        return found;
    }

    private <T> T require(EObject object, Class<T> type, String label) {
        if (!type.isInstance(object)) throw new AxiomaException("WRONG_OBJECT_TYPE", label + " tiene un tipo incompatible.");
        return type.cast(object);
    }

    public List<String> types(boolean relationship) {
        EClass base = relationship ? IArchimatePackage.eINSTANCE.getArchimateRelationship()
                : IArchimatePackage.eINSTANCE.getArchimateElement();
        return IArchimatePackage.eINSTANCE.getEClassifiers().stream().filter(EClass.class::isInstance)
                .map(EClass.class::cast).filter(c -> !c.isAbstract() && base.isSuperTypeOf(c))
                .map(EClass::getName).sorted().toList();
    }

    private EClass resolveType(String input, boolean relationship) {
        String normalized = input.replaceAll("[^A-Za-z0-9]", "");
        String match = types(relationship).stream().filter(t -> t.equalsIgnoreCase(normalized)
                || (relationship && t.equalsIgnoreCase(normalized + "Relationship")))
                .findFirst().orElseThrow(() -> new AxiomaException("INVALID_ARCHIMATE_TYPE", "Tipo no reconocido: " + input));
        return (EClass) IArchimatePackage.eINSTANCE.getEClassifier(match);
    }

    private Object createElement(IArchimateModel model, Map<String, Object> args) {
        IArchimateElement element = (IArchimateElement) factory.create(resolveType(required(args, "type"), false));
        update(element, args);
        targetFolder(model, element, args).getElements().add(element);
        return describe(element);
    }

    private Object createRelationship(IArchimateModel model, Map<String, Object> args) {
        // Los extremos son conceptos: también pueden ser otras relaciones ArchiMate.
        IArchimateConcept source = require(find(model, required(args, "sourceId")), IArchimateConcept.class, "sourceId");
        IArchimateConcept target = require(find(model, required(args, "targetId")), IArchimateConcept.class, "targetId");
        EClass type = resolveType(required(args, "type"), true);
        if (!ArchimateModelUtils.isValidRelationship(source, target, type)) {
            throw new AxiomaException("INVALID_RELATIONSHIP", "Relación no permitida por Archi. Alternativas: " + validTypes(model, args));
        }
        IArchimateRelationship relationship = (IArchimateRelationship) factory.create(type);
        relationship.connect(source, target);
        update(relationship, args);
        targetFolder(model, relationship, args).getElements().add(relationship);
        return describe(relationship);
    }

    private List<String> validTypes(IArchimateModel model, Map<String, Object> args) {
        IArchimateConcept source = require(find(model, required(args, "sourceId")), IArchimateConcept.class, "sourceId");
        IArchimateConcept target = require(find(model, required(args, "targetId")), IArchimateConcept.class, "targetId");
        return types(true).stream().filter(t -> ArchimateModelUtils.isValidRelationship(source, target,
                (EClass) IArchimatePackage.eINSTANCE.getEClassifier(t))).toList();
    }

    private Object reconnect(IArchimateModel model, Map<String, Object> args) {
        var relationship = require(find(model, required(args, "id")), IArchimateRelationship.class, "id");
        var source = require(find(model, required(args, "sourceId")), IArchimateConcept.class, "sourceId");
        var target = require(find(model, required(args, "targetId")), IArchimateConcept.class, "targetId");
        if (!relationship.getReferencingDiagramConnections().isEmpty()) throw new AxiomaException("RELATIONSHIP_IN_USE", "Quite las conexiones visuales y reconecte dentro de un lote; después añada las representaciones de los nuevos extremos.");
        if (source == relationship || target == relationship || !ArchimateModelUtils.isValidRelationship(source, target, relationship.eClass())) throw new AxiomaException("INVALID_RELATIONSHIP", "Los nuevos extremos no admiten esta relación.");
        relationship.disconnect(); relationship.connect(source, target);
        return describe(relationship);
    }

    private IFolder targetFolder(IArchimateModel model, EObject object, Map<String, Object> args) {
        IFolder root = object instanceof IFolder folder ? folder : model.getDefaultFolderForObject(object);
        while (root != null && root.getType() == FolderType.USER && root.eContainer() instanceof IFolder parent) root = parent;
        if (root == null) throw new AxiomaException("WRONG_OBJECT_TYPE", "El objeto no puede alojarse en una carpeta de conceptos o vistas.");
        if (!args.containsKey("folderId")) return root;
        IFolder candidate = require(find(model, required(args, "folderId")), IFolder.class, "folderId");
        IFolder ancestor = candidate;
        while (ancestor.getType() == FolderType.USER && ancestor.eContainer() instanceof IFolder parent) ancestor = parent;
        if (root == null || ancestor.getType() != root.getType()) {
            throw new AxiomaException("FOLDER_LAYER_MISMATCH", "La carpeta no corresponde a la capa del objeto.");
        }
        return candidate;
    }

    private Object createView(IArchimateModel model, Map<String, Object> args) {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        update(view, args);
        if (args.containsKey("viewpoint")) view.setViewpoint(text(args, "viewpoint", ""));
        targetFolder(model, view, args).getElements().add(view);
        return describe(view);
    }

    private IDiagramModelContainer container(IArchimateModel model, Map<String, Object> args) {
        EObject view = find(model, required(args, "viewId"));
        require(view, IDiagramModel.class, "viewId");
        if (!args.containsKey("parentId")) return (IDiagramModelContainer) view;
        EObject parent = find(model, required(args, "parentId"));
        IDiagramModelContainer container = require(parent, IDiagramModelContainer.class, "parentId");
        if (((IDiagramModelComponent) parent).getDiagramModel() != view) {
            throw new AxiomaException("CROSS_VIEW_REFERENCE", "El contenedor pertenece a otra vista.");
        }
        return container;
    }

    private Object addElement(IArchimateModel model, Map<String, Object> args) {
        IArchimateElement element = require(find(model, required(args, "elementId")), IArchimateElement.class, "elementId");
        IDiagramModelArchimateObject visual = factory.createDiagramModelArchimateObject();
        visual.setArchimateElement(element);
        update(visual, args);
        setBounds(visual, args);
        container(model, args).getChildren().add(visual);
        return describe(visual);
    }

    private Object addConnection(IArchimateModel model, Map<String, Object> args) {
        IDiagramModel view = require(find(model, required(args, "viewId")), IDiagramModel.class, "viewId");
        IArchimateRelationship rel = require(find(model, required(args, "relationshipId")), IArchimateRelationship.class, "relationshipId");
        IConnectable source = require(find(model, required(args, "sourceVisualId")), IConnectable.class, "sourceVisualId");
        IConnectable target = require(find(model, required(args, "targetVisualId")), IConnectable.class, "targetVisualId");
        if (source.getDiagramModel() != view || target.getDiagramModel() != view) {
            throw new AxiomaException("CROSS_VIEW_REFERENCE", "Los extremos visuales deben pertenecer a la vista solicitada.");
        }
        if (!(source instanceof IDiagramModelArchimateComponent s) || !(target instanceof IDiagramModelArchimateComponent t)
                || s.getArchimateConcept() != rel.getSource() || t.getArchimateConcept() != rel.getTarget()) {
            throw new AxiomaException("CONNECTION_ENDPOINT_MISMATCH", "Los extremos visuales no representan los conceptos de la relación.");
        }
        IDiagramModelArchimateConnection connection = factory.createDiagramModelArchimateConnection();
        connection.setArchimateRelationship(rel);
        connection.connect(source, target);
        update(connection, args);
        return describe(connection);
    }

    private Object addReference(IArchimateModel model, Map<String, Object> args) {
        IDiagramModelReference reference = factory.createDiagramModelReference();
        reference.setReferencedModel(require(find(model, required(args, "referencedViewId")), IDiagramModel.class, "referencedViewId"));
        update(reference, args); setBounds(reference, args);
        container(model, args).getChildren().add(reference);
        return describe(reference);
    }

    private Object addImage(IArchimateModel model, Map<String, Object> args) {
        var archive = (com.archimatetool.editor.model.IArchiveManager) model.getAdapter(com.archimatetool.editor.model.IArchiveManager.class);
        String image = required(args, "imagePath");
        if (!archive.getImagePaths().contains(image) && !archive.getLoadedImagePaths().contains(image)) throw new AxiomaException("IMAGE_NOT_FOUND", "La imagen no existe en este modelo.");
        var visual = factory.createDiagramModelImage(); visual.setImagePath(image);
        update(visual, args); setBounds(visual, args);
        container(model, args).getChildren().add(visual);
        return describe(visual);
    }

    private Object addLine(IArchimateModel model, Map<String, Object> args) {
        IDiagramModel view = require(find(model, required(args, "viewId")), IDiagramModel.class, "viewId");
        IConnectable source = require(find(model, required(args, "sourceVisualId")), IConnectable.class, "sourceVisualId");
        IConnectable target = require(find(model, required(args, "targetVisualId")), IConnectable.class, "targetVisualId");
        if (source.getDiagramModel() != view || target.getDiagramModel() != view) throw new AxiomaException("CROSS_VIEW_REFERENCE", "Extremos de otra vista.");
        var line = factory.createDiagramModelConnection();
        line.connect(source, target); update(line, args);
        return describe(line);
    }

    private Object moveObject(IArchimateModel model, Map<String, Object> args) {
        var object = require(find(model, required(args, "id")), IDiagramModelObject.class, "id");
        var destination = container(model, args);
        if (object.getDiagramModel() != find(model, required(args, "viewId"))) throw new AxiomaException("CROSS_VIEW_REFERENCE", "No se mueve un objeto entre vistas.");
        for (EObject parent = destination; parent != null; parent = parent.eContainer()) {
            if (parent == object) throw new AxiomaException("CONTAINER_CYCLE", "El movimiento crearía un ciclo.");
        }
        destination.getChildren().add(object); update(object, args);
        return describe(object);
    }

    private Object setBendpoints(IArchimateModel model, Map<String, Object> args) {
        var connection = require(find(model, required(args, "id")), IDiagramModelConnection.class, "id");
        if (!(args.get("bendpoints") instanceof List<?> points)) throw new AxiomaException("INVALID_ARGUMENT", "bendpoints debe ser una lista de offsets nativos.");
        connection.getBendpoints().clear();
        for (Object point : points) {
            if (!(point instanceof Map<?, ?> raw)) throw new AxiomaException("INVALID_ARGUMENT", "Cada bendpoint debe ser un objeto.");
            @SuppressWarnings("unchecked") var values = (Map<String, Object>) raw;
            var bend = factory.createDiagramModelBendpoint();
            bend.setStartX(integer(values, "startX", 0)); bend.setStartY(integer(values, "startY", 0));
            bend.setEndX(integer(values, "endX", 0)); bend.setEndY(integer(values, "endY", 0));
            connection.getBendpoints().add(bend);
        }
        connection.getDiagramModel().setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_BENDPOINT);
        return describe(connection);
    }

    private Object createProfile(IArchimateModel model, Map<String, Object> args) {
        var profile = factory.createProfile();
        String type = required(args, "type");
        EClass concept = resolveType(type, type.toLowerCase(java.util.Locale.ROOT).endsWith("relationship"));
        profile.setConceptType(concept.getName()); profile.setSpecialization(true);
        update(profile, args); model.getProfiles().add(profile);
        return describe(profile);
    }

    private Object assignProfile(IArchimateModel model, Map<String, Object> args) {
        var profile = require(find(model, required(args, "id")), IProfile.class, "id");
        var concept = require(find(model, required(args, "conceptId")), IArchimateConcept.class, "conceptId");
        if (!concept.eClass().getName().equals(profile.getConceptType())) throw new AxiomaException("PROFILE_TYPE_MISMATCH", "El perfil no corresponde al tipo del concepto.");
        if (bool(args, "remove", false)) concept.getProfiles().remove(profile);
        else if (!concept.getProfiles().contains(profile)) concept.getProfiles().add(profile);
        return describe(concept);
    }

    private Object addAnnotation(IArchimateModel model, Map<String, Object> args, boolean group) {
        IDiagramModelObject object = group ? factory.createDiagramModelGroup() : factory.createDiagramModelNote();
        update(object, args);
        setBounds(object, args);
        container(model, args).getChildren().add(object);
        return describe(object);
    }

    private void setBounds(IDiagramModelObject visual, Map<String, Object> args) {
        int width = integer(args, "width", 160);
        int height = integer(args, "height", 70);
        if (width <= 0 || height <= 0) throw new AxiomaException("INVALID_ARGUMENT", "width y height deben ser positivos.");
        visual.setBounds(integer(args, "x", 30), integer(args, "y", 30), width, height);
    }

    private Object createFolder(IArchimateModel model, Map<String, Object> args) {
        IFolder parent = require(find(model, required(args, "parentId")), IFolder.class, "parentId");
        IFolder folder = factory.createFolder();
        folder.setType(FolderType.USER);
        folder.setName(required(args, "name"));
        parent.getFolders().add(folder);
        return describe(folder);
    }

    private Object move(IArchimateModel model, Map<String, Object> args) {
        required(args, "folderId");
        EObject object = find(model, required(args, "id"));
        IFolder target = targetFolder(model, object, args);
        if (object instanceof IFolder folder) {
            if (folder.getType() != FolderType.USER) throw new AxiomaException("SYSTEM_FOLDER", "No se mueve una carpeta de sistema.");
            for (EObject parent = target; parent != null; parent = parent.eContainer()) {
                if (parent == folder) throw new AxiomaException("FOLDER_CYCLE", "El movimiento crearía un ciclo de carpetas.");
            }
            target.getFolders().add(folder);
        } else target.getElements().add(require(object, IArchimateModelObject.class, "id"));
        return describe(object);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> update(EObject object, Map<String, Object> args) {
        if (args.containsKey("name")) {
            require(object, INameable.class, "objeto").setName(text(args, "name", ""));
        }
        if (args.containsKey("documentation")) {
            String value = text(args, "documentation", "");
            if (object instanceof IArchimateModel model) model.setPurpose(value);
            else require(object, IDocumentable.class, "objeto").setDocumentation(value);
        }
        if (args.containsKey("content")) require(object, ITextContent.class, "objeto").setContent(text(args, "content", ""));
        if (args.containsKey("properties")) {
            if (!(args.get("properties") instanceof Map<?, ?> properties)) {
                throw new AxiomaException("INVALID_ARGUMENT", "properties debe ser un objeto de claves y textos o null.");
            }
            IProperties owner = require(object, IProperties.class, "objeto");
            for (var entry : properties.entrySet()) {
                String key = String.valueOf(entry.getKey());
                owner.getProperties().removeIf(p -> p.getKey().equals(key));
                if (entry.getValue() != null) {
                    if (!(entry.getValue() instanceof String text)) throw new AxiomaException("INVALID_ARGUMENT", "Las propiedades deben ser textos o null.");
                    owner.getProperties().add(factory.createProperty(key, text));
                }
            }
        }
        if (object instanceof IDiagramModelObject visual && (args.containsKey("x") || args.containsKey("y")
                || args.containsKey("width") || args.containsKey("height"))) {
            IBounds original = visual.getBounds();
            Map<String, Object> bounds = new LinkedHashMap<>(original == null ? Map.of() : Map.of("x", original.getX(), "y", original.getY(),
                    "width", original.getWidth(), "height", original.getHeight()));
            bounds.putAll(args);
            setBounds(visual, bounds);
        }
        if (args.containsKey("attributes")) {
            if (!(args.get("attributes") instanceof Map<?, ?> attrs)) throw new AxiomaException("INVALID_ARGUMENT", "attributes debe ser un objeto.");
            // Admitir atributos reales del metamodelo, nunca referencias EMF arbitrarias.
            for (var entry : attrs.entrySet()) {
                var feature = object.eClass().getEStructuralFeature(String.valueOf(entry.getKey()));
                if (!(feature instanceof EAttribute attr) || !attr.isChangeable() || attr.isDerived() || attr.isMany()
                        || Set.of("id", "name", "documentation", "file", "imagePath", "conceptType").contains(attr.getName())
                        || (object instanceof IFolder && attr.getName().equals("type"))) {
                    throw new AxiomaException("INVALID_ATTRIBUTE", "Atributo no modificable: " + entry.getKey());
                }
                try {
                    Object value = entry.getValue() == null ? null : EcoreUtil.createFromString(attr.getEAttributeType(), String.valueOf(entry.getValue()));
                    object.eSet(attr, value);
                } catch (IllegalArgumentException error) {
                    throw new AxiomaException("INVALID_ATTRIBUTE_VALUE", "Valor inválido para " + attr.getName(), error);
                }
            }
        }
        if (args.get("features") instanceof Map<?, ?> features) {
            IFeatures owner = require(object, IFeatures.class, "objeto");
            for (var entry : features.entrySet()) {
                if (entry.getValue() == null) owner.getFeatures().remove(String.valueOf(entry.getKey()));
                else owner.getFeatures().putString(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        } else if (args.containsKey("features")) throw new AxiomaException("INVALID_ARGUMENT", "features debe ser un objeto.");
        return describe(object);
    }

    private Object delete(IArchimateModel model, Map<String, Object> args) {
        EObject object = find(model, required(args, "id"));
        if (object == model || (object instanceof IFolder folder && folder.getType() != FolderType.USER)) {
            throw new AxiomaException("SYSTEM_OBJECT", "No se elimina el modelo ni sus carpetas de sistema mediante esta operación.");
        }
        Set<EObject> deleted = new LinkedHashSet<>();
        deleted.add(object);
        object.eAllContents().forEachRemaining(deleted::add);
        List<EObject> all = contents(model);
        // Cierre transitivo: una relación también puede ser extremo de otra relación.
        boolean changed;
        do {
            changed = false;
            for (EObject candidate : all) {
                if (candidate.eContainer() != null && deleted.contains(candidate.eContainer())) changed |= deleted.add(candidate);
                if (candidate instanceof IDiagramModelReference reference && deleted.contains(reference.getReferencedModel())) changed |= deleted.add(candidate);
                if (candidate instanceof IArchimateRelationship rel && (deleted.contains(rel.getSource()) || deleted.contains(rel.getTarget()))) {
                    changed |= deleted.add(candidate);
                }
                if (candidate instanceof IDiagramModelArchimateComponent visual && deleted.contains(visual.getArchimateConcept())) {
                    changed |= deleted.add(candidate);
                }
                if (candidate instanceof IDiagramModelConnection visual && (deleted.contains(visual.getSource()) || deleted.contains(visual.getTarget()))) {
                    changed |= deleted.add(candidate);
                }
            }
        } while (changed);
        Map<String, Object> description = describe(object);
        for (EObject candidate : deleted) if (candidate instanceof IDiagramModelConnection connection) connection.disconnect();
        for (EObject candidate : deleted) if (candidate instanceof IArchimateRelationship relation) relation.disconnect();
        for (EObject candidate : deleted) EcoreUtil.delete(candidate, true);
        return Map.of("deleted", description, "cascadeObjectCount", deleted.size());
    }

    private List<Map<String, Object>> list(IArchimateModel model, Class<?> type, Map<String, Object> args) {
        String query = text(args, "query", "").toLowerCase(java.util.Locale.ROOT);
        return contents(model).stream().filter(type::isInstance)
                .filter(o -> query.isEmpty() || (o instanceof INameable n && n.getName().toLowerCase(java.util.Locale.ROOT).contains(query)))
                .map(this::describe).toList();
    }

    public Map<String, Object> describe(EObject object) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", object.eClass().getName());
        if (object instanceof IIdentifier id) result.put("id", id.getId());
        if (object.eContainer() instanceof IIdentifier parent) result.put("parentId", parent.getId());
        if (object instanceof INameable name) result.put("name", name.getName());
        if (object instanceof IDocumentable doc) result.put("documentation", doc.getDocumentation());
        if (object instanceof ITextContent text) result.put("content", text.getContent());
        if (object instanceof IProperties owner) {
            Map<String, String> properties = new LinkedHashMap<>();
            for (IProperty property : owner.getProperties()) properties.put(property.getKey(), property.getValue());
            result.put("properties", properties);
        }
        if (object instanceof IArchimateRelationship rel) {
            result.put("sourceId", rel.getSource().getId());
            result.put("targetId", rel.getTarget().getId());
        }
        if (object instanceof IDiagramModelArchimateComponent visual) result.put("conceptId", visual.getArchimateConcept().getId());
        if (object instanceof IDiagramModelObject visual && visual.getBounds() != null) result.put("bounds", Map.of("x", visual.getBounds().getX(),
                "y", visual.getBounds().getY(), "width", visual.getBounds().getWidth(), "height", visual.getBounds().getHeight()));
        if (object instanceof IDiagramModelConnection visual) {
            result.put("sourceVisualId", visual.getSource().getId());
            result.put("targetVisualId", visual.getTarget().getId());
            result.put("bendpoints", visual.getBendpoints().stream().map(b -> Map.of("startX", b.getStartX(), "startY", b.getStartY(), "endX", b.getEndX(), "endY", b.getEndY())).toList());
        }
        if (object instanceof IDiagramModelReference reference) result.put("referencedViewId", reference.getReferencedModel().getId());
        if (object instanceof IArchimateConcept concept) result.put("profiles", concept.getProfiles().stream().map(IProfile::getId).toList());
        if (object instanceof IFeatures owner) {
            Map<String, String> features = new LinkedHashMap<>();
            for (var f : owner.getFeatures()) features.put(f.getName(), f.getValue());
            result.put("features", features);
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (EAttribute attr : object.eClass().getEAllAttributes()) {
            if (!attr.isMany() && !attr.isDerived()) {
                Object value = object.eGet(attr);
                if (value != null) attributes.put(attr.getName(), value instanceof String || value instanceof Number || value instanceof Boolean ? value : value.toString());
            }
        }
        result.put("attributes", attributes);
        return result;
    }

    private Object viewContents(EObject object) {
        IDiagramModel view = require(object, IDiagramModel.class, "id");
        List<Map<String, Object>> children = new ArrayList<>();
        view.eAllContents().forEachRemaining(o -> { if (o instanceof IDiagramModelComponent) children.add(describe(o)); });
        return Map.of("view", describe(view), "objects", children);
    }
}
