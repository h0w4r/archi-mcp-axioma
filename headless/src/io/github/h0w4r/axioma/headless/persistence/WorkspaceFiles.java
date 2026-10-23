package io.github.h0w4r.axioma.headless.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import io.github.h0w4r.axioma.headless.core.AxiomaException;

/** Control transversal de rutas y huellas para impedir accesos fuera del workspace. */
public final class WorkspaceFiles {
    private final Path root;

    public WorkspaceFiles(Path root) throws IOException {
        Files.createDirectories(root);
        this.root = root.toRealPath();
    }

    public Path root() { return root; }

    public Path resolve(String input) {
        return resolve(input, false);
    }

    /** Reservado a persistencia: sigue verificando que la ruta permanezca dentro de la raíz. */
    public Path internal(String input) { return resolve(input, true); }

    private Path resolve(String input, boolean internal) {
        try {
            Path requested = Path.of(input);
            Path path = (requested.isAbsolute() ? requested : root.resolve(requested)).normalize().toAbsolutePath();
            if (!path.startsWith(root)) throw denied();
            if (!internal) checkVisible(path);
            // Verificar también el ancestro existente: normalize() no detecta enlaces simbólicos.
            Path ancestor = path;
            while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
            if (ancestor == null || !ancestor.toRealPath().startsWith(root)) throw denied();
            if (!internal) checkVisible(ancestor.toRealPath());
            if (Files.exists(path) && !path.toRealPath().startsWith(root)) throw denied();
            return path;
        } catch (IOException | java.nio.file.InvalidPathException e) {
            throw new AxiomaException("INVALID_PATH", "No se pudo verificar la ruta solicitada.", e);
        }
    }

    private void checkVisible(Path path) {
        Path relative = root.relativize(path);
        if (relative.getNameCount() > 0 && relative.getName(0).toString().equalsIgnoreCase(".axioma")) {
            throw new AxiomaException("RESERVED_PATH", "El directorio .axioma está reservado al historial y al runtime.");
        }
    }

    private AxiomaException denied() {
        return new AxiomaException("PATH_OUTSIDE_WORKSPACE", "La ruta debe permanecer dentro del workspace configurado.");
    }

    public static String fingerprint(Path path) {
        if (!Files.exists(path)) return "absent";
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new AxiomaException("FILE_READ_FAILED", "No se pudo calcular la huella del modelo.", e);
        }
    }
}
