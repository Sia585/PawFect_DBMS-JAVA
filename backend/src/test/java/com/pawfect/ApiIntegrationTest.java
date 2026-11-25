package com.pawfect;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.pawfect.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Black-box tests against the real server, real HTTP and a real SQLite file. */
class ApiIntegrationTest {
    @TempDir Path tmp;
    private HttpServer server;
    private Database db;
    private int port;

    private void start() throws Exception {
        db = migrated(tmp);
        server = serve(db);
        port = port(server);
    }

    @AfterEach
    void shutDown() {
        stop(server);
    }

    private long revision() throws Exception {
        return ((Number) get(port, "/api/state").json().get("revision")).longValue();
    }

    // ---- state API ---------------------------------------------------------------------------

    @Test
    void getReturnsRevisionPetsAndRecords() throws Exception {
        start();
        Response r = get(port, "/api/state");
        assertEquals(200, r.status());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(Map.of("revision", 0, "pets", List.of(), "records", List.of(), "weights", List.of()), r.json());
    }

    @Test
    void normalCreateEditDeleteFlowThroughHttp() throws Exception {
        start();
        Map<String, Object> milo = pet("p1", "Milo", "Dog");
        milo.put("notes", "ünï 🐕");
        Response created = put(port, body(0, list(milo), list(record("r1", "p1", "Rabies"))));
        assertEquals(200, created.status());
        assertEquals(1, created.json().get("revision"));
        assertEquals(Boolean.TRUE, created.json().get("saved"));

        Map<String, Object> state = get(port, "/api/state").json();
        assertEquals(1, state.get("revision"));
        assertEquals("ünï 🐕", ((Map<?, ?>) ((List<?>) state.get("pets")).get(0)).get("notes"));

        Map<String, Object> edited = pet("p1", "Milo", "Dog");
        edited.put("breed", "Golden");
        assertEquals(200, put(port, body(1, list(edited), list(record("r1", "p1", "Rabies booster")))).status());
        assertEquals(2L, revision());

        assertEquals(200, put(port, body(2, list(edited), list())).status());          // record removed
        assertEquals(0, ((List<?>) get(port, "/api/state").json().get("records")).size());
        assertEquals(200, put(port, body(3, list(), list())).status());                // pet removed
        assertEquals(0, ((List<?>) get(port, "/api/state").json().get("pets")).size());
        assertEquals(4L, revision());
    }

    @Test
    void anOldClientWithoutBaseRevisionIsRefusedAndNothingChanges() throws Exception {
        start();
        Map<String, List<List<String>>> before = dump(db);
        Response r = put(port, "{\"pets\":[{\"id\":\"p1\",\"name\":\"Milo\",\"species\":\"Dog\"}],\"records\":[]}");
        assertEquals(400, r.status());
        assertEquals("REVISION_REQUIRED", r.code());
        assertTrue(r.error().contains("baseRevision"));
        assertEquals(before, dump(db));
    }

    @Test
    void aStaleRevisionGets409WithTheCurrentRevisionAndNoChange() throws Exception {
        start();
        assertEquals(200, put(port, body(0, list(pet("p1", "Milo", "Dog")), list())).status());
        Map<String, List<List<String>>> before = dump(db);
        Response stale = put(port, body(0, list(pet("p2", "Stale", "Cat")), list()));
        assertEquals(409, stale.status());
        assertEquals("STALE_REVISION", stale.code());
        assertEquals(1, stale.json().get("currentRevision"));
        assertEquals(before, dump(db));
        Response ahead = put(port, body(99, list(), list()));                          // a revision from the future is also stale
        assertEquals(409, ahead.status());
        assertEquals(before, dump(db));
    }

    @Test
    void aStaleClientCannotDeleteWhatAnotherClientCreated() throws Exception {
        start();
        assertEquals(200, put(port, body(0, list(pet("pa", "From A", "Dog")), list(record("ra", "pa", "A's record")))).status());
        Response staleDelete = put(port, body(0, list(), list()));                      // B still believes the database is empty
        assertEquals(409, staleDelete.status());
        Map<String, Object> now = get(port, "/api/state").json();
        assertEquals(1, ((List<?>) now.get("pets")).size());
        assertEquals(1, ((List<?>) now.get("records")).size());
    }

    @Test
    void invalidJsonIsA400AndChangesNothing() throws Exception {
        start();
        Map<String, List<List<String>>> before = dump(db);
        for (String bad : List.of("{oops", "[]", "null", "", "\"text\"", "{\"pets\":")) {
            Response r = put(port, bad);
            assertEquals(400, r.status());
            assertEquals("INVALID_JSON", r.code());
        }
        assertEquals(before, dump(db));
    }

    @Test
    void invalidPayloadsAreRejectedWithoutPartialChanges() throws Exception {
        start();
        assertEquals(200, put(port, body(0, list(pet("keep", "Keeper", "Dog")), list())).status());
        Map<String, List<List<String>>> before = dump(db);
        List<String> bodies = List.of(
            body(1, list(pet("a", "  ", "Dog")), list()),
            body(1, list(pet("a", "x", "Dog"), pet("a", "y", "Dog")), list()),
            body(1, list(pet("a", "x", "Dog")), list(record("r", "missing", "t"))),
            body(1, list(pet("keep", "Renamed but bad", "Dog"), pet("../etc", "x", "Dog")), list()),
            "{\"baseRevision\":1,\"pets\":{},\"records\":[]}",
            "{\"baseRevision\":1,\"pets\":[],\"records\":[1]}",
            "{\"baseRevision\":\"1\",\"pets\":[],\"records\":[]}",
            "{\"baseRevision\":1,\"pets\":[{\"id\":\"a\",\"name\":\"x\",\"species\":\"Dog\",\"birthdate\":\"2026-02-31\"}],\"records\":[]}");
        for (String b : bodies) assertEquals(400, put(port, b).status());
        assertEquals(before, dump(db));
    }

    @Test
    void wrongContentTypeIs415AndAJsonCharsetIsAccepted() throws Exception {
        start();
        String ok = body(0, list(pet("p1", "Milo", "Dog")), list());
        assertEquals(415, send(port, "PUT", "/api/state", ok, "text/plain").status());
        assertEquals(415, send(port, "PUT", "/api/state", ok, "application/x-www-form-urlencoded").status());
        assertEquals(415, send(port, "PUT", "/api/state", ok, null).status());
        assertEquals(0, count(db, "pet"));
        assertEquals(200, send(port, "PUT", "/api/state", ok, "application/json; charset=UTF-8").status());
        assertEquals(1, count(db, "pet"));
    }

    @Test
    void theBodyLimitHoldsWhileStreamingEvenWithoutAContentLength() throws Exception {
        start();
        byte[] big = new byte[StateHandler.MAX_BODY_BYTES + 1024];
        java.util.Arrays.fill(big, (byte) 'x');
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/state"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big)))   // unknown length: chunked
            .build();
        HttpResponse<String> r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(413, r.statusCode());
        assertEquals(0, count(db, "pet"));
    }

    @Test
    void otherMethodsAreNotAllowed() throws Exception {
        start();
        for (String m : List.of("POST", "DELETE", "PATCH")) {
            Response r = send(port, m, "/api/state", "{}", "application/json");
            assertEquals(405, r.status());
            assertEquals("GET, PUT", r.headers().firstValue("Allow").orElse(""));
        }
    }

    // ---- static files (behaviour carried over from the original server) -------------------------

    @Test
    void staticFilesBehaveAsBefore() throws Exception {
        start();
        Response index = get(port, "/");
        assertEquals(200, index.status());
        assertTrue(index.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(index.body().contains("Pawfect"));
        assertEquals("no-store", index.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(index.body(), get(port, "/index.html").body());
        assertTrue(get(port, "/app.js").headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
        assertTrue(get(port, "/styles.css").headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
        assertEquals(404, get(port, "/nope.png").status());
        assertEquals(404, get(port, "/nope").status());
        assertEquals(400, get(port, "/%2e%2e/secret").status());
        assertEquals(400, get(port, "/a%5Cb").status());
        assertEquals(200, send(port, "HEAD", "/", null, null).status());
        assertEquals(405, send(port, "POST", "/", "{}", "application/json").status());
        assertEquals(405, send(port, "PUT", "/app.js", "{}", "application/json").status());
    }

    // ---- request guard -----------------------------------------------------------------------

    @Test
    void theHostHeaderMustBeLocalhostOr127001OnThisPort() throws Exception {
        start();
        for (String host : List.of("localhost:" + port, "127.0.0.1:" + port, "LOCALHOST:" + port))
            assertEquals(200, rawStatus(raw(port, rawRequest("GET", "/api/state", host, null))));
        for (String host : List.of("evil.example", "evil.example:" + port, "localhost:" + (port + 1), "localhost", "127.0.0.1.evil.example:" + port))
            assertEquals(403, rawStatus(raw(port, rawRequest("GET", "/api/state", host, null))));
        assertEquals(403, rawStatus(raw(port, rawRequest("GET", "/", "evil.example:" + port, null))));      // static files too (DNS rebinding)
        assertEquals(403, rawStatus(raw(port, "GET /api/state HTTP/1.0\r\n\r\n")));                          // no Host at all
    }

    @Test
    void theOriginMustBeLocalOrAbsent() throws Exception {
        start();
        String hostLocal = "localhost:" + port;
        for (String origin : List.of("http://localhost:" + port, "http://127.0.0.1:" + port))
            assertEquals(200, rawStatus(raw(port, rawRequest("GET", "/api/state", hostLocal, null, "Origin", origin))));
        for (String origin : List.of("http://evil.example", "https://localhost:" + port, "http://localhost:" + (port + 1), "null", "http://localhost.evil.example:" + port))
            assertEquals(403, rawStatus(raw(port, rawRequest("GET", "/api/state", hostLocal, null, "Origin", origin))));
        // A command-line client sends no Origin and is allowed, for reads and writes.
        assertEquals(200, rawStatus(raw(port, rawRequest("GET", "/api/state", "127.0.0.1:" + port, null))));
        assertEquals(200, rawStatus(raw(port, rawRequest("PUT", "/api/state", "127.0.0.1:" + port, body(0, list(pet("cli", "From curl", "Cat")), list())))));
        assertEquals(1, count(db, "pet"));
    }

    @Test
    void foreignOriginsAndCrossSiteRequestsCannotWrite() throws Exception {
        start();
        String b = body(0, list(pet("x", "Attacker", "Dog")), list());
        assertEquals(403, rawStatus(raw(port, rawRequest("PUT", "/api/state", "localhost:" + port, b, "Origin", "http://evil.example"))));
        assertEquals(403, rawStatus(raw(port, rawRequest("PUT", "/api/state", "localhost:" + port, b, "Origin", "null"))));
        assertEquals(403, rawStatus(raw(port, rawRequest("PUT", "/api/state", "localhost:" + port, b, "Sec-Fetch-Site", "cross-site"))));
        assertEquals(403, rawStatus(raw(port, rawRequest("PUT", "/api/state", "localhost:" + port, b, "Sec-Fetch-Site", "same-site"))));
        assertEquals(403, rawStatus(raw(port, rawRequest("PUT", "/api/state", "evil.example", b))));
        assertEquals(0, count(db, "pet"));
        assertEquals(200, rawStatus(raw(port, rawRequest("PUT", "/api/state", "localhost:" + port, b, "Origin", "http://localhost:" + port, "Sec-Fetch-Site", "same-origin"))));
        assertEquals(1, count(db, "pet"));
    }

    // ---- concurrency -------------------------------------------------------------------------

    @Test
    void twoClientsSavingWithTheSameRevisionGetExactlyOneWinner() throws Exception {
        start();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                long rev = revision();
                List<Map<String, Object>> current = new ArrayList<>();
                for (Object o : (List<?>) get(port, "/api/state").json().get("pets")) { @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o; current.add(m); }
                CyclicBarrier gate = new CyclicBarrier(2);
                List<Future<Integer>> results = new ArrayList<>();
                for (String who : List.of("A", "B")) {
                    List<Map<String, Object>> pets = new ArrayList<>(current);
                    pets.add(pet(who + "-" + round, "Pet from " + who, "Dog"));
                    String json = body(rev, pets, list());
                    results.add(pool.submit(() -> { gate.await(); return put(port, json).status(); }));
                }
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : results) statuses.add(f.get(20, TimeUnit.SECONDS));
                java.util.Collections.sort(statuses);
                assertEquals(List.of(200, 409), statuses);
                assertEquals(rev + 1, revision());
                assertEquals(round + 1, count(db, "pet"));                              // exactly one pet added per round
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyConcurrentWritersLoseNoUpdates() throws Exception {
        start();
        int writers = 6, petsEach = 5;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                final int writer = w;
                done.add(pool.submit(() -> {
                    for (int i = 0; i < petsEach; i++) {
                        while (true) {
                            Map<String, Object> state = get(port, "/api/state").json();
                            List<Map<String, Object>> pets = new ArrayList<>();
                            for (Object o : (List<?>) state.get("pets")) { @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o; pets.add(m); }
                            pets.add(pet("w" + writer + "-" + i + "-" + UUID.randomUUID(), "Pet " + writer + "." + i, "Dog"));
                            Response r = put(port, body(((Number) state.get("revision")).longValue(), pets, list()));
                            if (r.status() == 200) break;
                            assertEquals(409, r.status());
                            conflicts.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : done) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(writers * petsEach, count(db, "pet"));                             // nothing lost
        assertEquals((long) writers * petsEach, revision());                            // one revision per successful save
        assertEquals("ok", pragmaFirst(db, "integrity_check"));
    }

    @Test
    void rapidSequentialSavesAdvanceTheRevisionOneByOne() throws Exception {
        start();
        List<Map<String, Object>> pets = new ArrayList<>();
        long rev = 0;
        for (int i = 1; i <= 30; i++) {
            pets.add(pet("p" + i, "Pet " + i, "Dog"));
            Response r = put(port, body(rev, pets, list()));
            assertEquals(200, r.status());
            rev = ((Number) r.json().get("revision")).longValue();
            assertEquals(i, rev);
        }
        Response repeat = put(port, body(rev, pets, list()));                           // the same snapshot again changes nothing
        assertEquals(200, repeat.status());
        assertEquals(30, repeat.json().get("revision"));
        assertEquals(30L, count(db, "pet"));
    }

    // ---- failure handling and startup ------------------------------------------------------------

    @Test
    void aDatabaseErrorMidSaveIsA500AndLeavesNothingBehind() throws Exception {
        start();
        populate(db);
        exec(db, "CREATE TRIGGER boom BEFORE INSERT ON care_record WHEN NEW.title = 'BOOM' BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
        Map<String, List<List<String>>> before = dump(db);
        Map<String, Object> state = get(port, "/api/state").json();
        @SuppressWarnings("unchecked") List<Map<String, Object>> pets = (List<Map<String, Object>>) state.get("pets");
        @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) state.get("records");
        pets.get(0).put("name", "renamed first");
        pets.add(pet("np", "New", "Fish"));
        records.add(record("bad", "np", "BOOM"));
        Response r = put(port, body(0, pets, records));
        assertEquals(500, r.status());
        assertEquals(before, dump(db));
        assertEquals(0L, revision());
    }

    @Test
    void startingOnALegacyDatabaseMigratesItBacksItUpAndServesItsData() throws Exception {
        Database legacy = db(tmp);
        legacy.initialize();
        populate(legacy);
        server = serve(legacy);
        port = port(server);
        db = legacy;
        Map<String, Object> state = get(port, "/api/state").json();
        assertEquals(0, state.get("revision"));
        assertEquals(4, ((List<?>) state.get("pets")).size());
        assertEquals(15, ((List<?>) state.get("records")).size());
        assertEquals(3, userVersion(db));
        try (var files = Files.list(tmp.resolve("backups"))) { assertEquals(1L, files.count()); }
        assertTrue(tableExists(db, "weight_measurement"));
        assertFalse(tableExists(db, "photo"));
    }

    @Test
    void startupRefusesADatabaseNewerThanTheCode() throws Exception {
        Database d = db(tmp);
        d.initialize();
        exec(d, "PRAGMA user_version = 42");
        assertThrows(MigrationException.class, () -> serve(d));
    }

    @Test
    void theServerOnlyListensOnTheLoopbackAddress() throws Exception {
        start();
        assertEquals("127.0.0.1", server.getAddress().getAddress().getHostAddress());
        assertTrue(Files.exists(tmp.resolve("test.db")));
    }
}
