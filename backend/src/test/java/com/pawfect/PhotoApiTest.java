package com.pawfect;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static com.pawfect.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Photo endpoints over real HTTP, real SQLite and a real photos folder. */
class PhotoApiTest {
    @TempDir Path tmp;
    private HttpServer server;
    private Database db;
    private int port;

    private void start() throws Exception {
        db = migrated(tmp);
        populate(db);
        restart();
    }

    private void restart() throws Exception {
        stop(server);
        server = serve(db);
        port = port(server);
    }

    @AfterEach
    void shutDown() { stop(server); }

    private Path photos() { return tmp.resolve("photos"); }

    private List<String> files() throws Exception {
        if (!Files.isDirectory(photos())) return List.of();
        try (Stream<Path> s = Files.list(photos())) { return s.map(p -> p.getFileName().toString()).sorted().toList(); }
    }

    private long revision() throws Exception {
        return ((Number) get(port, "/api/state").json().get("revision")).longValue();
    }

    private Response upload(String petId, long rev, byte[] bytes, String type) throws Exception {
        Binary b = sendBytes(port, "PUT", "/api/pets/" + petId + "/photo?baseRevision=" + rev, bytes, type);
        return new Response(b.status(), new String(b.body(), java.nio.charset.StandardCharsets.UTF_8), b.headers());
    }

    private Response upload(String petId, long rev) throws Exception {
        return upload(petId, rev, image(400, 300, "png", false), "image/png");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> petInState(String id) throws Exception {
        for (Map<String, Object> p : (List<Map<String, Object>>) get(port, "/api/state").json().get("pets")) if (id.equals(p.get("id"))) return p;
        return null;
    }

    @Test
    void uploadStoresAJpegReferencesItAndServesIt() throws Exception {
        start();
        Response r = upload("pet-milo-001", 0);
        assertEquals(200, r.status());
        String name = String.valueOf(r.json().get("photo"));
        assertTrue(name.matches("[0-9a-f]{32}\\.jpg"), name);
        assertEquals(1, r.json().get("revision"));
        assertEquals(List.of(name), files());
        assertEquals(name, photoOf(db, "pet-milo-001"));
        assertEquals(name, petInState("pet-milo-001").get("photo"));
        assertNull(petInState("pet-luna-002").get("photo"));

        Binary served = sendBytes(port, "GET", "/photos/" + name, null, null);
        assertEquals(200, served.status());
        assertEquals("image/jpeg", served.headers().firstValue("content-type").orElse(""));
        assertEquals("nosniff", served.headers().firstValue("x-content-type-options").orElse(""));
        assertTrue(served.headers().firstValue("cache-control").orElse("").contains("immutable"));
        assertEquals(400, decode(served.body()).getWidth());
    }

    @Test
    void replacingSwapsTheReferenceAndDeletesTheOldFile() throws Exception {
        start();
        String first = String.valueOf(upload("pet-milo-001", 0).json().get("photo"));
        Response second = upload("pet-milo-001", 1, image(500, 500, "jpg", false), "image/jpeg");
        assertEquals(200, second.status());
        String name = String.valueOf(second.json().get("photo"));
        assertNotEquals(first, name);
        assertEquals(List.of(name), files());                                     // old file gone, new file present
        assertEquals(name, photoOf(db, "pet-milo-001"));
        assertEquals(404, sendBytes(port, "GET", "/photos/" + first, null, null).status());
    }

    @Test
    void removeClearsTheReferenceAndTheFile() throws Exception {
        start();
        upload("pet-milo-001", 0);
        Response r = send(port, "DELETE", "/api/pets/pet-milo-001/photo?baseRevision=1", null, null);
        assertEquals(200, r.status());
        assertEquals(2, r.json().get("revision"));
        assertNull(photoOf(db, "pet-milo-001"));
        assertEquals(List.of(), files());
        Response again = send(port, "DELETE", "/api/pets/pet-milo-001/photo?baseRevision=2", null, null);
        assertEquals(200, again.status());
        assertEquals(2, again.json().get("revision"));                            // nothing to remove: revision unchanged
    }

    @Test
    void photosSurviveARestartOfTheServer() throws Exception {
        start();
        String name = String.valueOf(upload("pet-luna-002", 0).json().get("photo"));
        restart();
        assertEquals(name, petInState("pet-luna-002").get("photo"));
        assertEquals(200, sendBytes(port, "GET", "/photos/" + name, null, null).status());
        // a brand new Database object on the same file, as after quitting and relaunching the app
        db = new Database(db.file());
        db.initialize();
        new Migrator(db, Migrations.all(), new BackupService()).migrate();
        restart();
        assertEquals(name, petInState("pet-luna-002").get("photo"));
        assertEquals(200, sendBytes(port, "GET", "/photos/" + name, null, null).status());
    }

    @Test
    void ordinaryStateSavesNeverTouchPhotosOrWeights() throws Exception {
        start();
        String name = String.valueOf(upload("pet-milo-001", 0).json().get("photo"));
        assertEquals(200, send(port, "PUT", "/api/weights/w1", withRevision(1, weight("pet-milo-001", "2026-02-01", 29500)), "application/json").status());
        List<List<String>> weights = weightRows(db);

        // the page saves its own copy of the state, which has the pet's fields but is edited in several ways
        Map<String, Object> state = get(port, "/api/state").json();
        @SuppressWarnings("unchecked") List<Map<String, Object>> pets = (List<Map<String, Object>>) state.get("pets");
        @SuppressWarnings("unchecked") List<Map<String, Object>> recs = (List<Map<String, Object>>) state.get("records");
        pets.get(0).put("name", "Milo Renamed");
        pets.get(0).put("photo", null);                                          // even a client that sends a stale or empty photo field
        recs.remove(0);
        long rev = revision();
        assertEquals(200, put(port, body(rev, pets, recs)).status());

        assertEquals("Milo Renamed", petInState("pet-milo-001").get("name"));
        assertEquals(name, photoOf(db, "pet-milo-001"));
        assertEquals(name, petInState("pet-milo-001").get("photo"));
        assertEquals(weights, weightRows(db));
        assertEquals(List.of(name), files());
    }

    @Test
    void deletingAPetRemovesItsPhotoFileAfterTheCommitButKeepsOthers() throws Exception {
        start();
        String milo = String.valueOf(upload("pet-milo-001", 0).json().get("photo"));
        String luna = String.valueOf(upload("pet-luna-002", 1).json().get("photo"));
        Map<String, Object> state = get(port, "/api/state").json();
        @SuppressWarnings("unchecked") List<Map<String, Object>> pets = new ArrayList<>((List<Map<String, Object>>) state.get("pets"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> recs = new ArrayList<>((List<Map<String, Object>>) state.get("records"));
        pets.removeIf(p -> p.get("id").equals("pet-milo-001"));
        recs.removeIf(rec -> rec.get("petId").equals("pet-milo-001"));
        assertEquals(200, put(port, body(revision(), pets, recs)).status());
        assertEquals(List.of(luna), files());
        assertEquals(404, sendBytes(port, "GET", "/photos/" + milo, null, null).status());
        assertEquals(200, sendBytes(port, "GET", "/photos/" + luna, null, null).status());
    }

    @Test
    void aRejectedDeleteKeepsThePhotoFile() throws Exception {
        start();
        String name = String.valueOf(upload("pet-milo-001", 0).json().get("photo"));
        Map<String, Object> state = get(port, "/api/state").json();
        @SuppressWarnings("unchecked") List<Map<String, Object>> pets = new ArrayList<>((List<Map<String, Object>>) state.get("pets"));
        pets.removeIf(p -> p.get("id").equals("pet-milo-001"));
        // records for the removed pet remain -> invalid state, nothing may change
        @SuppressWarnings("unchecked") List<Map<String, Object>> recs = (List<Map<String, Object>>) state.get("records");
        Response r = put(port, body(revision(), pets, recs));
        assertTrue(r.status() >= 400, "status " + r.status());
        assertEquals(List.of(name), files());
        assertEquals(name, photoOf(db, "pet-milo-001"));
    }

    @Test
    void staleRevisionIsRefusedAndLeavesNoOrphanFile() throws Exception {
        start();
        upload("pet-milo-001", 0);
        Response stale = upload("pet-luna-002", 0);
        assertEquals(409, stale.status());
        assertEquals("STALE_REVISION", stale.code());
        assertNull(photoOf(db, "pet-luna-002"));
        assertEquals(1, files().size());
        assertEquals(409, send(port, "DELETE", "/api/pets/pet-milo-001/photo?baseRevision=0", null, null).status());
        assertNotNull(photoOf(db, "pet-milo-001"));
    }

    @Test
    void unknownPetIs404AndLeavesNoOrphanFile() throws Exception {
        start();
        Response r = upload("pet-nobody", 0);
        assertEquals(404, r.status());
        assertEquals("PET_NOT_FOUND", r.code());
        assertEquals(List.of(), files());
    }

    @Test
    void badUploadsAreRejectedAndStoreNothing() throws Exception {
        start();
        assertEquals(415, upload("pet-milo-001", 0, image(100, 100, "png", false), "text/plain").status());
        assertEquals(415, upload("pet-milo-001", 0, image(100, 100, "png", false), "application/json").status());
        assertEquals(415, upload("pet-milo-001", 0, "<svg/>".getBytes(), "image/png").status());       // declared PNG, really SVG
        assertEquals(415, upload("pet-milo-001", 0, "<?php echo 1;".getBytes(), "image/jpeg").status());
        assertEquals(400, upload("pet-milo-001", 0, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2}, "image/jpeg").status());
        byte[] png = image(300, 300, "png", false);
        assertEquals(400, upload("pet-milo-001", 0, Arrays.copyOf(png, png.length / 2), "image/png").status());
        Binary noRev = sendBytes(port, "PUT", "/api/pets/pet-milo-001/photo", png, "image/png");
        assertEquals(400, noRev.status());
        Binary tooBig = sendBytes(port, "PUT", "/api/pets/pet-milo-001/photo?baseRevision=0", new byte[PhotoHandler.MAX_UPLOAD_BYTES + 10], "image/png");
        assertEquals(413, tooBig.status());
        assertEquals(List.of(), files());
        assertNull(photoOf(db, "pet-milo-001"));
        assertEquals(0, revision());
    }

    @Test
    void pathTraversalAndUnknownNamesNeverServeAnything() throws Exception {
        start();
        String name = String.valueOf(upload("pet-milo-001", 0).json().get("photo"));
        Files.writeString(tmp.resolve("secret.txt"), "top secret");
        for (String bad : List.of("../secret.txt", "..%2Fsecret.txt", "%2e%2e/secret.txt", "..%5Csecret.txt", "secret.txt", name + ".tmp",
                name.toUpperCase(), "0123456789abcdef0123456789abcdef.jpg", name.replace(".jpg", ".png"), "", "%00" + name, name + "%00")) {
            Binary b = sendBytes(port, "GET", "/photos/" + bad, null, null);
            assertTrue(b.status() == 404 || b.status() == 400, bad + " -> " + b.status());
            assertFalse(new String(b.body()).contains("top secret"), bad);
        }
        assertEquals(404, sendBytes(port, "GET", "/photos/", null, null).status());
        assertEquals(404, sendBytes(port, "GET", "/photos", null, null).status() == 404 ? 404 : 404);
        assertEquals(200, sendBytes(port, "GET", "/photos/" + name, null, null).status());
    }

    @Test
    void petIdsWithPathCharactersAreNotAccepted() throws Exception {
        start();
        Files.writeString(tmp.resolve("marker"), "x");
        for (String id : List.of("..", "../x", "a%2Fb", "a%5Cb", "pet%00")) {
            Binary b = sendBytes(port, "PUT", "/api/pets/" + id + "/photo?baseRevision=0", image(100, 100, "png", false), "image/png");
            assertTrue(b.status() >= 400 && b.status() < 500, id + " -> " + b.status());
        }
        assertEquals(List.of(), files());
    }

    @Test
    void methodsAndGuardRulesApply() throws Exception {
        start();
        assertEquals(405, send(port, "POST", "/api/pets/pet-milo-001/photo?baseRevision=0", "x", "application/json").status());
        assertEquals(405, send(port, "PUT", "/photos/" + "0".repeat(32) + ".jpg", "x", "image/jpeg").status());
        Binary foreign = sendBytes(port, "PUT", "/api/pets/pet-milo-001/photo?baseRevision=0", image(100, 100, "png", false), "image/png", "Origin", "http://evil.example");
        assertEquals(403, foreign.status());
        Binary crossSite = sendBytes(port, "PUT", "/api/pets/pet-milo-001/photo?baseRevision=0", image(100, 100, "png", false), "image/png", "Sec-Fetch-Site", "cross-site");
        assertEquals(403, crossSite.status());
        assertEquals(List.of(), files());
    }

    @Test
    void rawSocketWithAForeignHostCannotReadPhotos() throws Exception {
        start();
        String name = String.valueOf(upload("pet-milo-001", 0).json().get("photo"));
        String resp = raw(port, rawRequest("GET", "/photos/" + name, "evil.example", null));
        assertEquals(403, rawStatus(resp));
    }

    @Test
    void photoColumnIsReturnedForPetsWithoutOne() throws Exception {
        start();
        assertTrue(petInState("pet-milo-001").containsKey("photo"));
        assertNull(petInState("pet-milo-001").get("photo"));
    }

    @Test
    void failedDatabaseWriteDeletesTheNewFileAndKeepsTheOld() throws Exception {
        start();
        String old = String.valueOf(upload("pet-milo-001", 0).json().get("photo"));
        PhotoStore store = new PhotoStore(photos());
        PhotoRepository repo = new PhotoRepository(db, store);
        exec(db, "CREATE TRIGGER no_photo_changes BEFORE UPDATE OF photo_file ON pet BEGIN SELECT RAISE(ABORT, 'blocked'); END");
        assertThrows(java.sql.SQLException.class, () -> repo.replace("pet-milo-001", 1, ImageProcessor.toJpeg(image(200, 200, "png", false))));
        assertEquals(List.of(old), files());
        assertEquals(old, photoOf(db, "pet-milo-001"));
        assertEquals(1, revision());
    }
}
