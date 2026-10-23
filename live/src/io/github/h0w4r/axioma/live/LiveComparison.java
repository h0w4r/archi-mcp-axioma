package io.github.h0w4r.axioma.live;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import org.eclipse.emf.common.util.BasicMonitor;
import org.eclipse.emf.compare.*;
import org.eclipse.emf.compare.match.*;
import org.eclipse.emf.compare.match.eobject.IdentifierEObjectMatcher;
import org.eclipse.emf.compare.match.impl.MatchEngineFactoryImpl;
import org.eclipse.emf.compare.match.impl.MatchEngineFactoryRegistryImpl;
import org.eclipse.emf.compare.merge.BatchMerger;
import org.eclipse.emf.compare.merge.IMerger;
import org.eclipse.emf.compare.scope.DefaultComparisonScope;
import org.eclipse.emf.compare.utils.UseIdentifiers;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.change.util.ChangeRecorder;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.gef.commands.CommandStack;
import com.archimatetool.editor.model.*;
import com.archimatetool.model.*;
import com.archimatetool.model.util.ArchimateModelUtils;

/** Diferencias calculadas en background y aplicadas transaccionalmente sobre los objetos vivos. */
public final class LiveComparison implements AutoCloseable {
    /** Un escritor sigue avanzando: esperar una versión coherente, no pausar como si fuera corrupción. */
    public static final class UnstableRead extends Exception {
        public UnstableRead(String message, Throwable cause) { super(message, cause); }
    }
    private record Incoming(IArchimateModel model, String fingerprint) {}
    public record Capture(IArchimateModel live, IArchimateModel snapshot, Map<EObject, EObject> originals) {}
    private final Capture capture;
    private final IArchimateModel incoming;
    private final Comparison comparison;
    private final String fingerprint;

    private LiveComparison(Capture capture, IArchimateModel incoming, Comparison comparison, String fingerprint) {
        this.capture = capture; this.incoming = incoming; this.comparison = comparison; this.fingerprint = fingerprint;
    }

    /** Ejecutar en el hilo UI: la copia contiene el estado exacto que el operador está viendo. */
    public static Capture capture(IArchimateModel model) {
        EcoreUtil.Copier copier = new EcoreUtil.Copier();
        IArchimateModel snapshot = (IArchimateModel) copier.copy(model);
        copier.copyReferences();
        Map<EObject, EObject> originals = new IdentityHashMap<>();
        copier.forEach((original, copy) -> originals.put(copy, original));
        return new Capture(model, snapshot, originals);
    }

    /** Sólo I/O y objetos aislados: no lee widgets ni recorre el modelo vivo. */
    public static LiveComparison prepare(Capture capture, Path path) throws Exception {
        IArchimateModel loaded = null;
        try {
            var stable = loadStable(path);
            loaded = stable.model();
            String after = stable.fingerprint();
            if (!loaded.getId().equals(capture.snapshot().getId())) throw new IllegalStateException("El archivo fue sustituido por un modelo con otro ID.");
            validate(loaded);
            var fallback = DefaultMatchEngine.createDefaultEObjectMatcher(UseIdentifiers.NEVER);
            var matcher = new IdentifierEObjectMatcher(fallback, object -> object instanceof IIdentifier id ? id.getId() : null);
            var registry = new MatchEngineFactoryRegistryImpl();
            registry.add(new MatchEngineFactoryImpl(matcher, new DefaultComparisonFactory(new DefaultEqualityHelperFactory())));
            Comparison diff = EMFCompare.builder().setMatchEngineFactoryRegistry(registry).build()
                    .compare(new DefaultComparisonScope(loaded, capture.snapshot(), null));
            if (diff.getDiagnostic() != null && diff.getDiagnostic().getSeverity() >= org.eclipse.emf.common.util.Diagnostic.ERROR) {
                throw new IllegalStateException("EMF Compare detectó un error: " + diff.getDiagnostic().getMessage());
            }
            return new LiveComparison(capture, loaded, diff, after);
        } catch (Exception error) {
            if (loaded != null) archive(loaded).dispose();
            throw error;
        }
    }

    private static Incoming loadStable(Path path) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            IArchimateModel loaded = null;
            try {
                String before = fingerprint(path);
                try {
                    loaded = IEditorModelManager.INSTANCE.load(path.toFile());
                } catch (java.io.IOException error) {
                    if (!before.equals(fingerprint(path))) continue;
                    throw error; // La misma huella contiene un archivo inválido, no una lectura desfasada.
                }
                if (loaded == null) throw new java.io.IOException("El archivo no contiene un modelo nativo.");
                String after = fingerprint(path);
                if (before.equals(after)) return new Incoming(loaded, after);
                archive(loaded).dispose(); loaded = null;
            } catch (java.nio.file.FileSystemException error) {
                if (loaded != null) archive(loaded).dispose();
                if (attempt == 2) throw new UnstableRead("El archivo está temporalmente ocupado o no está disponible.", error);
            }
        }
        throw new UnstableRead("El archivo continuó cambiando durante tres lecturas verificadas.", null);
    }

    public String fingerprint() { return fingerprint; }
    public int differences() { return comparison.getDifferences().size(); }
    public java.util.Set<String> viewIds() { return incoming.getDiagramModels().stream().map(IDiagramModel::getId).collect(java.util.stream.Collectors.toSet()); }

    /** Aplicar en UI tras comprobar de nuevo época, registro del modelo y ausencia de cambios humanos. */
    public int apply(Runnable refresh, Runnable suspend) {
        IArchimateModel live = capture.live();
        // Una modificación ajena al CommandStack tampoco puede pasar inadvertida entre captura y fusión.
        if (!EcoreUtil.equals(live, capture.snapshot())) throw new IllegalStateException("El modelo cambió después de capturar sus diferencias.");
        int count = differences();
        boolean imagesChanged = !sameImages(archive(live), archive(incoming));
        if (count == 0 && !imagesChanged) { refresh.run(); return 0; }
        // Rebasar sólo la derecha de la comparación: las instancias existentes y los editores se conservan.
        comparison.eAllContents().forEachRemaining(object -> {
            if (object instanceof Match match && match.getRight() != null) {
                EObject original = capture.originals().get(match.getRight());
                if (original == null) throw new IllegalStateException("Objeto derecho fuera de la captura.");
                match.setRight(original);
            }
            if (object instanceof ReferenceChange ref && capture.originals().containsKey(ref.getValue())) {
                ref.setValue(capture.originals().get(ref.getValue()));
            }
        });
        var oldArchive = archive(live);
        var newArchive = archive(incoming).clone(live);
        var recorder = new ChangeRecorder(live);
        live.setAdapter(IArchiveManager.class, newArchive);
        IEditorModelManager manager = IEditorModelManager.INSTANCE;
        manager.firePropertyChange(this, IEditorModelManager.PROPERTY_ECORE_EVENTS_START, null, live);
        GuiNotificationFence fence = new GuiNotificationFence(live);
        try {
            new BatchMerger(IMerger.RegistryImpl.createStandaloneInstance())
                    .copyAllLeftToRight(new ArrayList<>(comparison.getDifferences()), new BasicMonitor());
            validate(live);
            if (!EcoreUtil.equals(live, incoming)) throw new IllegalStateException("La fusión no reprodujo exactamente el modelo entrante.");
            refresh.run();
            recorder.endRecording();
            ((CommandStack) live.getAdapter(CommandStack.class)).flush();
            oldArchive.dispose();
            return count;
        } catch (RuntimeException error) {
            suspend.run();
            live.setAdapter(IArchiveManager.class, oldArchive);
            recorder.endRecording().applyAndReverse();
            newArchive.dispose();
            try { refresh.run(); } catch (RuntimeException uiError) { error.addSuppressed(uiError); }
            throw error;
        } finally {
            recorder.dispose();
            fence.close();
            manager.firePropertyChange(this, IEditorModelManager.PROPERTY_ECORE_EVENTS_END, null, live);
        }
    }

    private static boolean sameImages(IArchiveManager left, IArchiveManager right) {
        if (!left.getImagePaths().equals(right.getImagePaths())) return false;
        return left.getImagePaths().stream().allMatch(path -> Arrays.equals(left.getBytesFromEntry(path), right.getBytesFromEntry(path)));
    }

    private static void validate(IArchimateModel model) {
        var checker = new ModelChecker(model);
        checker.checkAll();
        if (!checker.getErrorMessages().isEmpty()) throw new IllegalStateException(String.join("; ", checker.getErrorMessages()));
        var ids = new java.util.HashSet<String>();
        ids.add(model.getId());
        model.eAllContents().forEachRemaining(object -> {
            if (object instanceof IIdentifier id && !ids.add(id.getId())) throw new IllegalStateException("ID duplicado: " + id.getId());
            if (object instanceof IArchimateRelationship relation && !ArchimateModelUtils.isValidRelationship(relation.getSource(), relation.getTarget(), relation.eClass())) {
                throw new IllegalStateException("Relación entrante inválida: " + relation.getId());
            }
        });
    }

    public static String fingerprint(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = new byte[65536]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static IArchiveManager archive(IArchimateModel model) { return (IArchiveManager) model.getAdapter(IArchiveManager.class); }
    @Override public void close() { archive(incoming).dispose(); }
}
