package com.pawfect;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/** GET /photos/{name}: serves a stored photograph. Only names the server generated are accepted. */
final class PhotoFileHandler implements HttpHandler {
    private static final String PREFIX = "/photos/";

    private final PhotoStore store;

    PhotoFileHandler(PhotoStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange x) throws IOException {
        try {
            if (!x.getRequestMethod().equals("GET")) {
                x.getResponseHeaders().set("Allow", "GET");
                Http.sendJson(x, 405, Map.of("error", "Method not allowed"));
                return;
            }
            String path = x.getRequestURI().getPath();
            byte[] bytes = path.startsWith(PREFIX) ? store.read(path.substring(PREFIX.length())) : null;
            if (bytes == null) { Http.sendJson(x, 404, Map.of("error", "Not found")); return; }
            x.getResponseHeaders().set("Content-Type", "image/jpeg");
            x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            x.getResponseHeaders().set("Cache-Control", "private, max-age=31536000, immutable");   // every upload gets a new name
            x.sendResponseHeaders(200, bytes.length);
            x.getResponseBody().write(bytes);
            x.close();
        } catch (Exception e) {
            Http.sendFailure(x, e);
        }
    }
}
