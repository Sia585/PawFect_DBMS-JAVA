package com.pawfect;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Weight endpoints (all require baseRevision, like the state save):
 * PUT /api/weights/{id}, DELETE /api/weights/{id}?baseRevision=N, POST /api/weights/import.
 */
final class WeightHandler implements HttpHandler {
    static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final String PREFIX = "/api/weights/";

    private final WeightRepository repository;

    WeightHandler(WeightRepository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath();
            if (!path.startsWith(PREFIX) || path.length() == PREFIX.length()) {
                Http.sendJson(x, 404, Map.of("error", "Not found"));
                return;
            }
            String tail = path.substring(PREFIX.length());
            String method = x.getRequestMethod();
            if (tail.equals("import")) {
                if (!method.equals("POST")) { notAllowed(x, "POST"); return; }
                done(x, repository.importAll(WeightValidator.parseImport(Http.readJsonObject(x, MAX_BODY_BYTES))));
            } else if (method.equals("PUT")) {
                String id = WeightValidator.pathId(tail);
                done(x, repository.save(WeightValidator.parseOne(id, Http.readJsonObject(x, MAX_BODY_BYTES))));
            } else if (method.equals("DELETE")) {
                done(x, repository.delete(queryRevision(x), WeightValidator.pathId(tail)));
            } else {
                notAllowed(x, "PUT, DELETE");
            }
        } catch (Exception e) {
            Http.sendFailure(x, e);
        }
    }

    private static void done(HttpExchange x, WeightRepository.Result r) throws IOException {
        Http.sendJson(x, 200, Map.of("saved", true, "revision", r.revision(), "changed", r.changed()));
    }

    private static void notAllowed(HttpExchange x, String allow) throws IOException {
        x.getResponseHeaders().set("Allow", allow);
        Http.sendJson(x, 405, Map.of("error", "Method not allowed"));
    }

    private static long queryRevision(HttpExchange x) {
        String q = x.getRequestURI().getRawQuery();
        if (q != null) {
            for (String part : q.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && kv[0].equals("baseRevision")) {
                    String v = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    if (v.matches("\\d{1,15}")) return Long.parseLong(v);
                }
            }
        }
        throw new ValidationException("REVISION_REQUIRED", "baseRevision is required. Reload Pawfect to get the latest data and try again.");
    }
}
