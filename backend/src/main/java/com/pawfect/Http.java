package com.pawfect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
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

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    /** Requires a JSON content type and returns the body as a JSON object (400 INVALID_JSON otherwise). */
    static Map<String, Object> readJsonObject(HttpExchange x, int maxBytes) throws IOException {
        requireContentType(x, "application/json");
        byte[] raw = readLimited(x.getRequestBody(), maxBytes);
        Map<String, Object> body;
        try {
            body = JSON.readValue(raw, MAP);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ValidationException("INVALID_JSON", "Request body must be a valid JSON object.");
        }
        if (body == null) throw new ValidationException("INVALID_JSON", "Request body must be a valid JSON object.");
        return body;
    }

    /** Maps an exception from a handler to the JSON error response (409 stale revision, 4xx validation, 500 otherwise). */
    static void sendFailure(HttpExchange x, Exception e) throws IOException {
        if (e instanceof ConflictException c) {
            sendJson(x, 409, Map.of("error", c.getMessage(), "code", "STALE_REVISION", "currentRevision", c.currentRevision()));
        } else if (e instanceof ValidationException v) {
            sendJson(x, v.status(), Map.of("error", v.getMessage(), "code", v.code()));
        } else if (e instanceof IllegalArgumentException) {
            sendJson(x, 400, Map.of("error", e.getMessage(), "code", "INVALID_REQUEST"));
        } else {
            e.printStackTrace(System.err);
            sendJson(x, 500, Map.of("error", "The Pawfect database could not complete that request."));
        }
    }

    /** baseRevision from the query string, for requests without a JSON body (DELETE, raw uploads). */
    static long queryRevision(HttpExchange x) {
        String q = x.getRequestURI().getRawQuery();
        if (q != null) {
            for (String part : q.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && kv[0].equals("baseRevision") && kv[1].matches("\\d{1,15}")) return Long.parseLong(kv[1]);
            }
        }
        throw new ValidationException("REVISION_REQUIRED", "baseRevision is required. Reload Pawfect to get the latest data and try again.");
    }
}
