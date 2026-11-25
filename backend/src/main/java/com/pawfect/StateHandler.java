package com.pawfect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/** HTTP handler for GET and PUT /api/state. */
final class StateHandler implements HttpHandler {
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

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
                Http.requireContentType(exchange, "application/json");
                byte[] raw = Http.readLimited(exchange.getRequestBody(), MAX_BODY_BYTES);
                Map<String, Object> body;
                try {
                    body = Http.JSON.readValue(raw, MAP);
                } catch (JsonProcessingException e) {
                    throw new ValidationException("INVALID_JSON", "Request body must be a valid JSON object.");
                }
                if (body == null) throw new ValidationException("INVALID_JSON", "Request body must be a valid JSON object.");
                StateRepository.SaveResult r = repository.save(StateValidator.parse(body));
                Http.sendJson(exchange, 200, Map.of("saved", true, "revision", r.revision(),
                    "inserted", r.inserted(), "updated", r.updated(), "deleted", r.deleted()));
            } else {
                exchange.getResponseHeaders().set("Allow", "GET, PUT");
                Http.sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            }
        } catch (ConflictException e) {
            Http.sendJson(exchange, 409, Map.of("error", e.getMessage(), "code", "STALE_REVISION", "currentRevision", e.currentRevision()));
        } catch (ValidationException e) {
            Http.sendJson(exchange, e.status(), Map.of("error", e.getMessage(), "code", e.code()));
        } catch (IllegalArgumentException e) {
            Http.sendJson(exchange, 400, Map.of("error", e.getMessage(), "code", "INVALID_REQUEST"));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            Http.sendJson(exchange, 500, Map.of("error", "The Pawfect database could not complete that request."));
        }
    }
}
