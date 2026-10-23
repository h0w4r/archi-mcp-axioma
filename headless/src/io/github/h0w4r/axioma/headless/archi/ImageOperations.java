package io.github.h0w4r.axioma.headless.archi;

import java.util.Map;
import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelImageProvider;
import io.github.h0w4r.axioma.headless.core.AxiomaException;
import io.github.h0w4r.axioma.headless.persistence.WorkspaceFiles;
import static io.github.h0w4r.axioma.headless.core.Arguments.*;

/** Operaciones del archivo de imágenes, conservadas por el guardado nativo. */
public final class ImageOperations {
    private ImageOperations() {}
    public static Object execute(IArchimateModel model, WorkspaceFiles files, NativeOperations operations, Map<String, Object> args) {
        IArchiveManager archive = (IArchiveManager) model.getAdapter(IArchiveManager.class);
        return switch (required(args, "action")) {
            case "list" -> archive.getImagePaths().stream().sorted().toList();
            case "add" -> {
                var path = files.resolve(required(args, "path"));
                try {
                    String image = archive.addImageFromFile(path.toFile());
                    if (args.containsKey("id")) {
                        var target = operations.find(model, required(args, "id"));
                        if (!(target instanceof IDiagramModelImageProvider provider)) throw new AxiomaException("WRONG_OBJECT_TYPE", "El destino no admite imágenes.");
                        provider.setImagePath(image);
                    }
                    yield Map.of("id", image, "imagePath", image);
                } catch (java.io.IOException error) { throw new AxiomaException("IMAGE_IMPORT_FAILED", "No se pudo incorporar la imagen.", error); }
            }
            case "assign" -> {
                var object = operations.find(model, required(args, "id"));
                if (!(object instanceof IDiagramModelImageProvider provider)) throw new AxiomaException("WRONG_OBJECT_TYPE", "El objeto no admite imágenes.");
                String image = text(args, "imagePath", "");
                if (!image.isEmpty() && !archive.getLoadedImagePaths().contains(image) && !archive.getImagePaths().contains(image)) throw new AxiomaException("IMAGE_NOT_FOUND", "La imagen no existe en el archivo nativo.");
                provider.setImagePath(image.isEmpty() ? null : image);
                yield operations.describe(object);
            }
            default -> throw new AxiomaException("UNSUPPORTED_OPERATION", "Acción de imagen no soportada.");
        };
    }
}
