package io.github.h0w4r.axioma.headless.core;

/** Error operativo tipado que el protocolo puede devolver sin inventar resultados. */
public final class AxiomaException extends RuntimeException {
    private String correlationId;
    public AxiomaException correlated(String id) { this.correlationId = id; return this; }
    public String correlationId() { return correlationId; }
    private static final long serialVersionUID = 1L;
    private final String code;

    public AxiomaException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AxiomaException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
}
