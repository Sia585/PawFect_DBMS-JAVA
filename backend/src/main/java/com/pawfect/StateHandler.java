package com.pawfect;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/** HTTP handler for GET and PUT /api/state (pets and care records; weights have their own endpoints). */
final class StateHandler implements HttpHandler {
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final StateRepository repository;

    StateHandler(StateRepository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                Http.sendJson(exchange, 200, repository.readState());
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                StateRepository.SaveResult r = repository.save(StateValidator.parse(Http.readJsonObject(exchange, MAX_BODY_BYTES)));
                Http.sendJson(exchange, 200, Map.of("saved", true, "revision", r.revision(),
                    "inserted", r.inserted(), "updated", r.updated(), "deleted", r.deleted()));
            } else {
                exchange.getResponseHeaders().set("Allow", "GET, PUT");
                Http.sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            }
        } catch (Exception e) {
            Http.sendFailure(exchange, e);
        }
    }
}
