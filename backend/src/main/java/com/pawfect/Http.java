package com.pawfect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Shared JSON and request-body helpers for the HTTP handlers. */
final class Http {
    static final ObjectMapper JSON = new ObjectMapper();

    private Http() {}

    static void sendJson(HttpExchange x, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    /** Rejects the request unless its media type (ignoring parameters such as charset) is the expected one. */
    static void requireContentType(HttpExchange x, String expected) {
        String header = x.getRequestHeaders().getFirst("Content-Type");
        String type = header == null ? "" : header.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!type.equals(expected)) {
            throw new ValidationException(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be " + expected + ".");
        }
    }

    /** Reads at most maxBytes while streaming, so the limit holds even when Content-Length is missing or wrong. */
    static byte[] readLimited(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) throw new ValidationException(413, "PAYLOAD_TOO_LARGE", "Request body is too large (max " + maxBytes + " bytes).");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
