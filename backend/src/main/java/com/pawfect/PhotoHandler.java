package com.pawfect;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PUT /api/pets/{id}/photo?baseRevision=N (raw JPEG or PNG body) and DELETE /api/pets/{id}/photo?baseRevision=N.
 */
final class PhotoHandler implements HttpHandler {
    static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    private static final Pattern PATH = Pattern.compile("/api/pets/([A-Za-z0-9._:-]{1,64})/photo");

    private final PhotoRepository repository;

    PhotoHandler(PhotoRepository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(HttpExchange x) throws IOException {
        try {
            Matcher m = PATH.matcher(x.getRequestURI().getPath());
            if (!m.matches()) { Http.sendJson(x, 404, Map.of("error", "Not found")); return; }
            String petId = m.group(1), method = x.getRequestMethod();
            if (method.equals("PUT")) {
                String type = String.valueOf(x.getRequestHeaders().getFirst("Content-Type")).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!type.equals("image/jpeg") && !type.equals("image/png")) {
                    throw new ValidationException(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be image/jpeg or image/png.");
                }
                long base = Http.queryRevision(x);
                byte[] jpeg = ImageProcessor.toJpeg(Http.readLimited(x.getRequestBody(), MAX_UPLOAD_BYTES));
                done(x, repository.replace(petId, base, jpeg));
            } else if (method.equals("DELETE")) {
                done(x, repository.remove(petId, Http.queryRevision(x)));
            } else {
                x.getResponseHeaders().set("Allow", "PUT, DELETE");
                Http.sendJson(x, 405, Map.of("error", "Method not allowed"));
            }
        } catch (Exception e) {
            Http.sendFailure(x, e);
        }
    }

    private static void done(HttpExchange x, PhotoRepository.Result r) throws IOException {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("saved", true); body.put("revision", r.revision()); body.put("photo", r.photo());
        Http.sendJson(x, 200, body);
    }
}
