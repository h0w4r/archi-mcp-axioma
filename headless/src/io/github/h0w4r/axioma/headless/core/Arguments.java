package io.github.h0w4r.axioma.headless.core;

import java.util.Map;

/** Lectura estricta de parámetros compartida por todos los dominios de herramientas. */
public final class Arguments {
    private Arguments() {}

    public static String required(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new AxiomaException("INVALID_ARGUMENT", "El parámetro '" + key + "' debe ser un texto no vacío.");
        }
        return text;
    }

    public static String text(Map<String, Object> args, String key, String fallback) {
        if (!args.containsKey(key)) return fallback;
        if (!(args.get(key) instanceof String value)) {
            throw new AxiomaException("INVALID_ARGUMENT", "El parámetro '" + key + "' debe ser texto.");
        }
        return value;
    }

    public static boolean bool(Map<String, Object> args, String key, boolean fallback) {
        if (!args.containsKey(key)) return fallback;
        if (!(args.get(key) instanceof Boolean value)) {
            throw new AxiomaException("INVALID_ARGUMENT", "El parámetro '" + key + "' debe ser booleano.");
        }
        return value;
    }

    public static int integer(Map<String, Object> args, String key, int fallback) {
        if (!args.containsKey(key)) return fallback;
        if (!(args.get(key) instanceof Number value) || value.doubleValue() != value.intValue()) {
            throw new AxiomaException("INVALID_ARGUMENT", "El parámetro '" + key + "' debe ser un entero.");
        }
        return value.intValue();
    }

    public static void expectedRevision(Map<String, Object> args, long actual) {
        if (args.containsKey("expectedRevision")) {
            Object value = args.get("expectedRevision");
            if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) {
                throw new AxiomaException("INVALID_ARGUMENT", "expectedRevision debe ser un entero.");
            }
            if (number.longValue() != actual) {
                throw new AxiomaException("REVISION_CONFLICT", "El modelo cambió: revisión actual " + actual + ". Vuelva a consultarlo.");
            }
        }
    }
}
