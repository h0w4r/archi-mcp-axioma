package io.github.h0w4r.axioma.headless.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.eclipse.emf.ecore.util.EcoreUtil;
import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.model.ModelChecker;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import io.github.h0w4r.axioma.headless.core.AxiomaException;

/** Adaptador del formato nativo de Archi, con guardado atómico y sin diálogo gráfico. */
public final class NativeModelStore {
    private final WorkspaceFiles files;
    public NativeModelStore(WorkspaceFiles files) { this.files = files; }

    public IArchimateModel create(String name, Path path) {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        model.setName(name);
        model.setFile(path.toFile());
        model.setAdapter(IArchiveManager.class, IArchiveManager.FACTORY.createArchiveManager(model));
        return model;
    }

    public record Loaded(IArchimateModel model, String fingerprint) {}

    public IArchimateModel load(Path path) { return loadWithFingerprint(path).model(); }

    public Loaded loadWithFingerprint(Path path) {
        return loadVerified(path, false);
    }

    private Loaded loadVerified(Path path, boolean internal) {
        if (internal) files.internal(path.toString()); else files.resolve(path.toString());
        for (int attempt = 0; attempt < 2; attempt++) {
            String before = WorkspaceFiles.fingerprint(path);
            IArchimateModel model = null;
            try {
                model = IEditorModelManager.INSTANCE.load(path.toFile());
                if (model == null) throw new IOException("El cargador de Archi no devolvió un modelo.");
                ((IArchiveManager) model.getAdapter(IArchiveManager.class)).loadImages();
                String after = WorkspaceFiles.fingerprint(path);
                if (before.equals(after)) return new Loaded(model, after);
                dispose(model);
            } catch (IOException error) {
                if (model != null) dispose(model);
                throw new AxiomaException("MODEL_LOAD_FAILED", "No se pudo cargar el modelo nativo: " + path.getFileName(), error);
            }
        }
        throw new AxiomaException("EXTERNAL_FILE_CONFLICT", "El archivo cambió durante las dos lecturas verificadas.");
    }

    public IArchimateModel copy(IArchimateModel source) {
        IArchimateModel copy = EcoreUtil.copy(source);
        copy.setFile(source.getFile());
        // El almacén de imágenes no es un atributo EMF: clonarlo explícitamente.
        IArchiveManager archive = (IArchiveManager) source.getAdapter(IArchiveManager.class);
        copy.setAdapter(IArchiveManager.class, archive.clone(copy));
        return copy;
    }

    public java.util.List<String> validate(IArchimateModel model) {
        ModelChecker checker = new ModelChecker(model);
        checker.checkAll();
        var errors = new java.util.ArrayList<>(checker.getErrorMessages());
        java.util.Set<String> identifiers = new java.util.HashSet<>();
        java.util.List<org.eclipse.emf.ecore.EObject> objects = new java.util.ArrayList<>();
        objects.add(model); model.eAllContents().forEachRemaining(objects::add);
        for (var object : objects) {
            if (object instanceof com.archimatetool.model.IIdentifier identified && !identifiers.add(identified.getId())) errors.add("Identificador duplicado: " + identified.getId());
            if (object instanceof com.archimatetool.model.IArchimateRelationship rel && rel.getSource() != null && rel.getTarget() != null
                    && !com.archimatetool.model.util.ArchimateModelUtils.isValidRelationship(rel.getSource(), rel.getTarget(), rel.eClass())) {
                errors.add("Relación no válida según el metamodelo de Archi: " + rel.getId());
            }
            if (object instanceof com.archimatetool.model.IDiagramModelObject visual
                    && (visual.getBounds().getWidth() <= 0 || visual.getBounds().getHeight() <= 0)) errors.add("Dimensiones no positivas: " + visual.getId());
        }
        return java.util.List.copyOf(errors);
    }

    public void dispose(IArchimateModel model) {
        IArchiveManager archive = (IArchiveManager) model.getAdapter(IArchiveManager.class);
        if (archive != null) archive.dispose();
    }

    public String save(IArchimateModel model, Path path, String expectedFingerprint) {
        Path temporary = null;
        try {
            files.resolve(path.toString());
            Files.createDirectories(path.getParent());
            Path lockPath = path.resolveSibling(path.getFileName() + ".axioma.lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException e) { lock = null; }
                if (lock == null) throw new AxiomaException("MODEL_BUSY", "Otro proceso Axioma está guardando este modelo.");
                try (FileLock held = lock) {
                    if (!WorkspaceFiles.fingerprint(path).equals(expectedFingerprint)) {
                        throw new AxiomaException("EXTERNAL_FILE_CONFLICT", "El archivo cambió fuera del servicio. Recárguelo antes de guardar.");
                    }
                    var errors = validate(model);
                    if (!errors.isEmpty()) throw new AxiomaException("INVALID_MODEL", String.join("; ", errors));
                    temporary = Files.createTempFile(path.getParent(), ".axioma-", ".archimate");
                    model.setFile(temporary.toFile());
                    try {
                        ((IArchiveManager) model.getAdapter(IArchiveManager.class)).saveModel();
                        try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) { output.force(true); }
                        // Segunda comprobación tras serializar, antes de reemplazar el archivo real.
                        if (!WorkspaceFiles.fingerprint(path).equals(expectedFingerprint)) {
                            throw new AxiomaException("EXTERNAL_FILE_CONFLICT", "El archivo cambió mientras se preparaba el guardado.");
                        }
                        if (!expectedFingerprint.equals("absent")) snapshot(path, expectedFingerprint);
                        files.resolve(path.toString());
                        if (!WorkspaceFiles.fingerprint(path).equals(expectedFingerprint)) throw new AxiomaException("EXTERNAL_FILE_CONFLICT", "El archivo cambió antes del reemplazo final.");
                        // No degradar silenciosamente a una escritura no atómica.
                        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        temporary = null;
                    } finally { model.setFile(path.toFile()); }
                    return WorkspaceFiles.fingerprint(path);
                }
            }
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            throw new AxiomaException("ATOMIC_SAVE_UNAVAILABLE", "El sistema de archivos no admite el guardado atómico requerido.", e);
        } catch (IOException e) {
            throw new AxiomaException("MODEL_SAVE_FAILED", "Falló el guardado del modelo; no se acredita persistencia.", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { System.err.println("AXIOMA: temporal pendiente de limpieza: " + temporary); }
            }
        }
    }

    /** Historial direccionado por contenido; conserva exactamente el archivo anterior. */
    private Path historyDirectory(Path path) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            String key = java.util.HexFormat.of().formatHex(digest.digest(files.root().relativize(path).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return files.internal(".axioma/history/" + key);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void snapshot(Path path, String fingerprint) throws IOException {
        Path folder = historyDirectory(path);
        Files.createDirectories(folder);
        Path destination = folder.resolve(fingerprint + ".archimate");
        if (Files.exists(destination)) {
            if (!WorkspaceFiles.fingerprint(destination).equals(fingerprint)) throw new AxiomaException("HISTORY_CORRUPTED", "Una copia histórica no coincide con su huella.");
            return;
        }
        Path temporary = Files.createTempFile(folder, ".snapshot-", ".tmp");
        try {
            Files.copy(path, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (!WorkspaceFiles.fingerprint(temporary).equals(fingerprint)) throw new AxiomaException("EXTERNAL_FILE_CONFLICT", "El archivo cambió mientras se conservaba la versión anterior.");
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) { output.force(true); }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    public java.util.List<java.util.Map<String, Object>> history(Path path) {
        Path folder = historyDirectory(path);
        if (!Files.isDirectory(folder)) return java.util.List.of();
        try (var entries = Files.list(folder)) {
            return entries.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.archimate")).sorted().map(p -> {
                try {
                    return java.util.Map.<String, Object>of("fingerprint", p.getFileName().toString().substring(0, 64), "bytes", Files.size(p), "savedAt", Files.getLastModifiedTime(p).toString());
                } catch (IOException error) { throw new AxiomaException("HISTORY_READ_FAILED", "No se pudo leer el historial.", error); }
            }).toList();
        } catch (IOException error) { throw new AxiomaException("HISTORY_READ_FAILED", "No se pudo listar el historial.", error); }
    }

    public IArchimateModel loadSnapshot(Path path, String fingerprint) {
        if (!fingerprint.matches("[0-9a-f]{64}")) throw new AxiomaException("INVALID_ARGUMENT", "fingerprint debe ser un SHA-256 hexadecimal.");
        Path snapshot = historyDirectory(path).resolve(fingerprint + ".archimate");
        if (!WorkspaceFiles.fingerprint(snapshot).equals(fingerprint)) throw new AxiomaException("HISTORY_NOT_FOUND", "La copia histórica no existe o no conserva su huella.");
        return loadVerified(snapshot, true).model();
    }
}
