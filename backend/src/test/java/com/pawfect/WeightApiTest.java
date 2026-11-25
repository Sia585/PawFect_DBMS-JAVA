package com.pawfect;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static com.pawfect.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Weight endpoints over real HTTP against a real SQLite file. */
class WeightApiTest {
    @TempDir Path tmp;
    private HttpServer server;
    private Database db;
    private int port;

    private void start() throws Exception {
        db = migrated(tmp);
        populate(db);
        server = serve(db);
        port = port(server);
    }

    @AfterEach
    void shutDown() { stop(server); }

    private long revision() throws Exception {
        return ((Number) get(port, "/api/state").json().get("revision")).longValue();
    }

    private Response putWeight(String id, long rev, String petId, String date, Object grams) throws Exception {
        return send(port, "PUT", "/api/weights/" + id, withRevision(rev, weight(petId, date, grams)), "application/json");
    }

    @Test
    void addEditDeleteAndReadBackThroughTheStateEndpoint() throws Exception {
        start();
        Response a = putWeight("w1", 0, "pet-milo-001", "2026-01-05", 29500);
        assertEquals(200, a.status());
        assertEquals(1, a.json().get("revision"));
        assertEquals(Boolean.TRUE, a.json().get("changed"));
        assertEquals(200, putWeight("w1", 1, "pet-milo-001", "2026-01-05", 29800).status());
        Response same = putWeight("w1", 2, "pet-milo-001", "2026-01-05", 29800);
        assertEquals(200, same.status());
        assertEquals(Boolean.FALSE, same.json().get("changed"));
        assertEquals(2, same.json().get("revision"));

        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) get(port, "/api/state").json().get("weights");
        assertEquals(1, list.size());
        assertEquals(29800, list.get(0).get("grams"));
        assertEquals("pet-milo-001", list.get(0).get("petId"));

        Response del = send(port, "DELETE", "/api/weights/w1?baseRevision=2", null, null);
        assertEquals(200, del.status());
        assertEquals(3, del.json().get("revision"));
        assertEquals(0L, count(db, "weight_measurement"));
    }

    @Test
    void ordinaryStateSavesFromTheBrowserNeverRemoveMeasurements() throws Exception {
        start();
        assertEquals(200, putWeight("w1", 0, "pet-milo-001", "2026-01-05", 29500).status());
        Map<String, Object> state = get(port, "/api/state").json();
        @SuppressWarnings("unchecked") List<Map<String, Object>> pets = (List<Map<String, Object>>) state.get("pets");
        @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) state.get("records");
        pets.get(0).put("name", "Renamed");
        records.remove(0);
        Response r = put(port, body(1, pets, records));                         // no "weights" key at all, like an older or simpler client
        assertEquals(200, r.status());
        assertEquals(1L, count(db, "weight_measurement"));
        assertEquals(List.of(Arrays.asList("w1", "pet-milo-001", "2026-01-05", "29500", null, null)), weightRows(db));
    }

    @Test
    void deletingAPetWithMeasurementsNeedsExplicitConfirmation() throws Exception {
        start();
        assertEquals(200, putWeight("w1", 0, "pet-kiwi-003", "2026-01-05", 40).status());
        Map<String, Object> state = get(port, "/api/state").json();
        @SuppressWarnings("unchecked") List<Map<String, Object>> pets = (List<Map<String, Object>>) state.get("pets");
        @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) state.get("records");
        pets.removeIf(p -> p.get("id").equals("pet-kiwi-003"));
        records.removeIf(r -> r.get("petId").equals("pet-kiwi-003"));
        Map<String, List<List<String>>> before = dump(db);
        Response refused = put(port, body(1, pets, records));
        assertEquals(409, refused.status());
        assertEquals("PET_HAS_WEIGHTS", refused.code());
        assertEquals(before, dump(db));

        Map<String, Object> confirmed = Http.JSON.readValue(body(1, pets, records), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        confirmed.put("removeWeightsOfDeletedPets", true);
        assertEquals(200, put(port, Http.JSON.writeValueAsString(confirmed)).status());
        assertEquals(0L, count(db, "weight_measurement"));
        assertEquals(3L, count(db, "pet"));
    }

    @Test
    void importIsAtomicAndRepeatedImportsAreRefused() throws Exception {
        start();
        String ok = withRevision(0, Map.of("items", List.of(importItem("i1", "pet-milo-001", "2026-01-01", 29000, "rec-0"), importItem("i2", "pet-luna-002", "2026-01-02", 4100, "rec-1"))));
        Response r = send(port, "POST", "/api/weights/import", ok, "application/json");
        assertEquals(200, r.status());
        assertEquals(1, r.json().get("revision"));
        assertEquals(2L, count(db, "weight_measurement"));

        List<List<String>> before = weightRows(db);
        String again = withRevision(1, Map.of("items", List.of(importItem("i9", "pet-milo-001", "2026-02-01", 100, null), importItem("i10", "pet-milo-001", "2026-02-02", 100, "rec-0"))));
        Response dup = send(port, "POST", "/api/weights/import", again, "application/json");
        assertEquals(409, dup.status());
        assertEquals("DUPLICATE_WEIGHT", dup.code());
        assertEquals(before, weightRows(db));
        assertEquals(1L, revision());
        assertEquals(405, send(port, "GET", "/api/weights/import", null, null).status());
    }

    @Test
    void staleRevisionsGet409OnEveryWeightOperationAndChangeNothing() throws Exception {
        start();
        assertEquals(200, putWeight("w1", 0, "pet-milo-001", "2026-01-05", 29500).status());
        List<List<String>> rows = weightRows(db);
        Response a = putWeight("w2", 0, "pet-milo-001", "2026-01-06", 100);
        assertEquals(409, a.status());
        assertEquals("STALE_REVISION", a.code());
        assertEquals(1, a.json().get("currentRevision"));
        assertEquals(409, send(port, "DELETE", "/api/weights/w1?baseRevision=0", null, null).status());
        assertEquals(409, send(port, "POST", "/api/weights/import", withRevision(0, Map.of("items", List.of(importItem("i", "pet-milo-001", "2026-01-01", 5, null)))), "application/json").status());
        assertEquals(rows, weightRows(db));
        assertEquals(1L, revision());
    }

    @Test
    void invalidRequestsAreRejectedWithoutChanges() throws Exception {
        start();
        List<List<String>> none = weightRows(db);
        assertEquals("REVISION_REQUIRED", send(port, "PUT", "/api/weights/w1", Http.JSON.writeValueAsString(weight("pet-milo-001", "2026-01-05", 100)), "application/json").code());
        assertEquals("REVISION_REQUIRED", send(port, "DELETE", "/api/weights/w1", null, null).code());
        assertEquals("REVISION_REQUIRED", send(port, "DELETE", "/api/weights/w1?baseRevision=abc", null, null).code());
        assertEquals(400, putWeight("w1", 0, "pet-milo-001", "2026-01-05", 1.5).status());
        assertEquals(400, putWeight("w1", 0, "pet-milo-001", "2026-01-05", 0).status());
        assertEquals(400, putWeight("w1", 0, "pet-milo-001", "nonsense", 100).status());
        assertEquals(404, putWeight("w1", 0, "no-such-pet", "2026-01-05", 100).status());
        assertEquals(400, putWeight("bad%20id%21", 0, "pet-milo-001", "2026-01-05", 100).status());
        assertEquals(405, putWeight("import", 0, "pet-milo-001", "2026-01-05", 100).status());
        assertEquals(400, send(port, "PUT", "/api/weights/w1", "{oops", "application/json").status());
        assertEquals(415, send(port, "PUT", "/api/weights/w1", withRevision(0, weight("pet-milo-001", "2026-01-05", 100)), "text/plain").status());
        assertEquals(404, send(port, "DELETE", "/api/weights/never?baseRevision=0", null, null).status());
        assertEquals(404, get(port, "/api/weights/").status());
        assertEquals(404, get(port, "/api/weightsfoo").status());
        assertEquals(405, get(port, "/api/weights/w1").status());
        assertEquals(405, send(port, "POST", "/api/weights/w1", "{}", "application/json").status());
        assertEquals(none, weightRows(db));
        assertEquals(0L, revision());
    }

    @Test
    void theRequestGuardAlsoProtectsWeightEndpoints() throws Exception {
        start();
        String host = "localhost:" + port;
        String b = withRevision(0, weight("pet-milo-001", "2026-01-05", 100));
        assertEquals(403, rawStatus(raw(port, rawRequest("PUT", "/api/weights/w1", host, b, "Origin", "http://evil.example"))));
        assertEquals(403, rawStatus(raw(port, rawRequest("PUT", "/api/weights/w1", host, b, "Sec-Fetch-Site", "cross-site"))));
        assertEquals(403, rawStatus(raw(port, rawRequest("PUT", "/api/weights/w1", "evil.example", b))));
        assertEquals(403, rawStatus(raw(port, rawRequest("DELETE", "/api/weights/w1?baseRevision=0", host, null, "Origin", "http://evil.example"))));
        assertEquals(0L, count(db, "weight_measurement"));
        assertEquals(200, rawStatus(raw(port, rawRequest("PUT", "/api/weights/w1", host, b, "Origin", "http://localhost:" + port))));
        assertEquals(1L, count(db, "weight_measurement"));
    }

    @Test
    void twoClientsAddingWeightsAtTheSameRevisionGetOneWinner() throws Exception {
        start();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                long rev = revision();
                CyclicBarrier gate = new CyclicBarrier(2);
                List<Future<Integer>> f = new ArrayList<>();
                for (String who : List.of("a", "b")) {
                    final int r = round;
                    f.add(pool.submit(() -> { gate.await(); return putWeight(who + r, rev, "pet-milo-001", "2026-01-0" + (1 + r), 1000 + r).status(); }));
                }
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> x : f) statuses.add(x.get(20, TimeUnit.SECONDS));
                Collections.sort(statuses);
                assertEquals(List.of(200, 409), statuses);
                assertEquals(round + 1, count(db, "weight_measurement"));
            }
        } finally { pool.shutdownNow(); }
        assertEquals("ok", pragmaFirst(db, "integrity_check"));
    }

    @Test
    void unicodeNotesRoundTrip() throws Exception {
        start();
        Map<String, Object> w = weight("pet-luna-002", "2026-01-05", 4200);
        w.put("note", "après dîner 🐈 “ok”");
        assertEquals(200, send(port, "PUT", "/api/weights/u1", withRevision(0, w), "application/json").status());
        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) get(port, "/api/state").json().get("weights");
        assertEquals("après dîner 🐈 “ok”", list.get(0).get("note"));
    }
}
