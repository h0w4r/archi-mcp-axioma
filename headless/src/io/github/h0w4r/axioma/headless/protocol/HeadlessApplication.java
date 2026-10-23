package io.github.h0w4r.axioma.headless.protocol;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.github.h0w4r.axioma.headless.archi.NativeOperations;
import io.github.h0w4r.axioma.headless.archi.NativeRenderer;
import io.github.h0w4r.axioma.headless.core.*;
import io.github.h0w4r.axioma.headless.persistence.*;

/** Entrada Equinox propia: inicia MCP por stdio y nunca crea un workbench. */
public final class HeadlessApplication implements IApplication {
    private final CountDownLatch stopped = new CountDownLatch(1);
    private McpSyncServer server;
    private NativeRenderer renderer;

    @Override
    public Object start(IApplicationContext context) throws Exception {
        InputStream input = System.in;
        OutputStream output = System.out;
        System.setErr(new java.io.PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));
        // Cualquier mensaje de Archi o dependencias debe ir a stderr, no al protocolo.
        System.setOut(System.err);
        ObjectMapper mapper = new ObjectMapper();
        var json = new JacksonMcpJsonMapper(mapper);
        Path workspace = Path.of(System.getProperty("axioma.workspace", Path.of(System.getProperty("user.home"), "Archi-Axioma").toString()));
        renderer = new NativeRenderer();
        var files = new WorkspaceFiles(workspace);
        String buildVersion = org.osgi.framework.FrameworkUtil.getBundle(getClass()).getVersion().toString();
        String nativeVersion = org.eclipse.core.runtime.Platform.getBundle("com.archimatetool.model").getVersion().toString();
        var service = new AxiomaService(new ModelRegistry(new NativeModelStore(files), files), new NativeOperations(), renderer,
                org.eclipse.ui.PlatformUI::isWorkbenchRunning, buildVersion, nativeVersion);
        InputStream tracked = new FilterInputStream(input) {
            @Override public int read() throws java.io.IOException { int read = super.read(); if (read == -1) stopped.countDown(); return read; }
            @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                int read = super.read(b, off, len); if (read == -1) stopped.countDown(); return read;
            }
        };
        var transport = new StdioServerTransportProvider(json, tracked, output);
        var specification = McpServer.sync(transport).jsonMapper(json).serverInfo("archi-mcp-axioma", buildVersion)
                .instructions("Trabaja con modelId explícito. Cada mutación confirmada se guarda atómicamente. "
                        + "Usa dryRun para previsualizar y expectedRevision para detectar conflictos. No hay aprobación gráfica.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());
        Map<String, List<String>> domains = ToolCatalog.domains();
        for (var entry : domains.entrySet()) {
            String domain = entry.getKey();
            var schema = ToolCatalog.schema(domain, entry.getValue());
            var tool = McpSchema.Tool.builder().name("axioma_" + domain).title("Axioma: " + domain)
                    .description("Operaciones " + domain + ": " + String.join(", ", entry.getValue())
                            + ". Creación usa name/type/path; objetos usan id; vistas usan viewId; relaciones usan sourceId/targetId. "
                            + "batch.apply acepta operations con domain, action y alias opcional as.")
                    .inputSchema(json, mapper.writeValueAsString(schema)).build();
            specification.toolCall(tool, (exchange, request) -> {
                try {
                    Object result = service.execute(domain, request.arguments() == null ? Map.of() : request.arguments());
                    return McpSchema.CallToolResult.builder().addTextContent(mapper.writeValueAsString(result))
                            .structuredContent(result).isError(false).build();
                } catch (Exception error) {
                    String code = error instanceof AxiomaException known ? known.code() : "INTERNAL_ERROR";
                    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                    String correlation = error instanceof AxiomaException known && known.correlationId() != null ? known.correlationId() : "bootstrap";
                    var result = Map.of("error", Map.of("code", code, "message", message, "correlationId", correlation));
                    try {
                        return McpSchema.CallToolResult.builder().addTextContent(mapper.writeValueAsString(result))
                                .structuredContent(result).isError(true).build();
                    } catch (Exception encoding) { throw new IllegalStateException(encoding); }
                }
            });
        }
        server = specification.build();
        System.err.println("AXIOMA listo: stdio, sin workbench, workspace=" + workspace);
        stopped.await();
        server.closeGracefully();
        renderer.close();
        return IApplication.EXIT_OK;
    }

    @Override public void stop() { stopped.countDown(); }
}
