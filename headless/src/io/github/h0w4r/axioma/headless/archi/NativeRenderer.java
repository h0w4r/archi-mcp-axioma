package io.github.h0w4r.axioma.headless.archi;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.export.svg.SVGExportProvider;
import io.github.h0w4r.axioma.headless.core.AxiomaException;

/** Render nativo fuera del núcleo y sin workbench ni ventanas abiertas. */
public final class NativeRenderer implements io.github.h0w4r.axioma.headless.core.RenderPort, AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "axioma-render"));
    private Display display;

    public byte[] render(IDiagramModel view, String format) {
        if (!format.equals("png") && !format.equals("svg")) throw new AxiomaException("INVALID_ARGUMENT", "Formato de exportación: png o svg.");
        if (Platform.getBundle("com.archimatetool.export.svg") == null) throw new AxiomaException("RENDER_DEPENDENCY_MISSING", "Falta el exportador SVG oficial de Archi.");
        var future = executor.submit(() -> {
            if (display == null) display = new Display();
            SVGExportProvider provider = new SVGExportProvider();
            provider.setDrawTextAsShapes(true);
            provider.setEmbedFonts(false);
            String svg = provider.getSVGString(view, true);
            if (format.equals("svg")) return svg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // Rasterizar el SVG conserva las figuras blancas y el alpha, sin chroma-key.
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_EXECUTE_ONLOAD, false);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_ALLOWED_SCRIPT_TYPES, "");
            transcoder.addTranscodingHint(PNGTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transcoder.transcode(new TranscoderInput(new StringReader(svg)), new TranscoderOutput(output));
            return output.toByteArray();
        });
        boolean interrupted = false;
        try {
            // Mantener el bloqueo del modelo hasta que el worker termine, incluso al cancelar el cliente.
            // Así no se libera ni modifica el archivo de imágenes mientras SWT lo está leyendo.
            for (;;) {
                try { return future.get(); }
                catch (InterruptedException error) { interrupted = true; }
            }
        }
        catch (ExecutionException error) {
            Throwable cause = error.getCause();
            System.err.println("AXIOMA render falló: " + cause);
            throw new AxiomaException("RENDER_FAILED", "Falló el render nativo: " + cause.getMessage(), cause);
        }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    public Map<String, Object> capabilities() {
        return Map.of("formats", java.util.List.of("svg", "png"), "transparentBackground", true,
                "backend", "Archi SVG + Batik", "graphicsDependencies", "SWT y fuentes; Linux requiere servidor gráfico o Xvfb; no requiere workbench.");
    }

    @Override public void close() {
        try { executor.submit(() -> { if (display != null && !display.isDisposed()) display.dispose(); }).get(); }
        catch (Exception error) { System.err.println("AXIOMA: cierre de render: " + error); }
        finally { executor.shutdown(); }
    }
}
