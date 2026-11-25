package com.pawfect;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/** Serves the bundled single-page app from the classpath folder /static. */
final class StaticHandler implements HttpHandler {
    private static final Map<String, String> MIME = Map.of(
        ".html", "text/html; charset=utf-8", ".css", "text/css; charset=utf-8",
        ".js", "text/javascript; charset=utf-8", ".json", "application/json; charset=utf-8",
        ".svg", "image/svg+xml", ".png", "image/png", ".ico", "image/x-icon"
    );

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) { Http.sendJson(exchange,405,Map.of("error","Method not allowed")); return; }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) path="/index.html";
        if (path.contains("..") || path.indexOf('\\')>=0) { Http.sendJson(exchange,400,Map.of("error","Invalid path")); return; }
        try (var in = StaticHandler.class.getResourceAsStream("/static" + path)) {
            if (in==null) { Http.sendJson(exchange,404,Map.of("error","Not found")); return; }
            byte[] body=in.readAllBytes(); String ext=path.substring(path.lastIndexOf('.'));
            exchange.getResponseHeaders().set("Content-Type",MIME.getOrDefault(ext,"application/octet-stream"));
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.sendResponseHeaders(200,"HEAD".equals(exchange.getRequestMethod())?-1:body.length);
            if (!"HEAD".equals(exchange.getRequestMethod())) exchange.getResponseBody().write(body);
            exchange.close();
        }
    }
}
