package com.pawfect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Shared helpers: temporary databases, a real in-process server, HTTP and raw-socket clients. */
final class TestSupport {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private TestSupport() {}

    static Database db(Path dir) {
        return new Database(dir.resolve("test.db"));
    }

    /** Baseline schema plus all migrations, like a started server. */
    static Database migrated(Path dir) throws Exception {
        Database d = db(dir);
        d.initialize();
        new Migrator(d, Migrations.all(), new BackupService()).migrate();
        return d;
    }

    /** Adds 4 pets and 15 care records shaped like the supplied synthetic fixture (not the fixture itself). */
    static void populate(Database d) throws SQLException {
        String[][] pets = {
            {"pet-milo-001", "Milo", "Dog", "Golden Retriever", "Male", "2022-03-14", "29.5", "Clinic", "🐕", "n"},
            {"pet-luna-002", "Luna", "Cat", "", "Female", "2021-06-01", "4.2", "Clinic", "🐈", ""},
            {"pet-kiwi-003", "Kiwi", "Bird", "Budgerigar", "", "2024-01-10", "", "", "🦜", ""},
            {"pet-biscuit-004", "Biscuit", "Rabbit", "Holland Lop", "", "2023-09-09", "1.8", "", "🐇", ""}};
        String[] types = {"Vaccination", "Vaccination", "Vaccination", "Vaccination", "Vet visit", "Vet visit", "Vet visit",
            "Medication", "Medication", "Grooming", "Grooming", "Grooming", "Weight", "Weight", "Feeding"};
        try (Connection c = d.connect()) {
            try (PreparedStatement q = c.prepareStatement("INSERT INTO pet(id,name,species,breed,sex,birthdate,weight,vet,face,notes) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                for (String[] p : pets) { for (int i = 0; i < p.length; i++) q.setString(i + 1, p[i]); q.executeUpdate(); }
            }
            try (PreparedStatement q = c.prepareStatement("INSERT INTO care_record(id,pet_id,type,title,record_date,due_date,notes) VALUES(?,?,?,?,?,?,?)")) {
                for (int i = 0; i < types.length; i++) {
                    q.setString(1, "rec-" + i); q.setString(2, pets[i % 4][0]); q.setString(3, types[i]); q.setString(4, types[i] + " " + i);
                    q.setString(5, "2026-0" + (1 + i % 9) + "-1" + (i % 9)); q.setString(6, i % 3 == 0 ? "2027-01-01" : ""); q.setString(7, "note " + i);
                    q.executeUpdate();
                }
            }
        }
    }

    /** All rows (including rowid) of the core tables and app_state when present, as plain strings. */
    static Map<String, List<List<String>>> dump(Database d) throws SQLException {
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        try (Connection c = d.connect()) {
            for (String t : List.of("owner", "pet", "care_record", "app_state")) {
                try (Statement s = c.createStatement(); ResultSet tables = s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + t + "'")) {
                    if (!tables.next()) continue;
                }
                List<List<String>> rows = new ArrayList<>();
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT rowid, * FROM " + t + " ORDER BY rowid")) {
                    int n = r.getMetaData().getColumnCount();
                    while (r.next()) { List<String> row = new ArrayList<>(); for (int i = 1; i <= n; i++) row.add(r.getString(i)); rows.add(row); }
                }
                out.put(t, rows);
            }
        }
        return out;
    }

    static long count(Database d, String table) throws SQLException {
        try (Connection c = d.connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    static boolean tableExists(Database d, String name) throws SQLException {
        try (Connection c = d.connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + name + "'")) {
            return r.next();
        }
    }

    static int userVersion(Database d) throws SQLException {
        try (Connection c = d.connect()) { return Migrator.userVersion(c); }
    }

    static void exec(Database d, String sql) throws SQLException {
        try (Connection c = d.connect(); Statement s = c.createStatement()) { s.execute(sql); }
    }

    static String pragmaFirst(Database d, String pragma) throws SQLException {
        try (Connection c = d.connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA " + pragma)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    static boolean foreignKeyCheckClean(Database d) throws SQLException {
        try (Connection c = d.connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA foreign_key_check")) {
            return !r.next();
        }
    }

    // ---- real server -------------------------------------------------------------------------

    static HttpServer serve(Database d) throws Exception {
        return PawfectServer.start(d, 0);
    }

    static int port(HttpServer s) {
        return s.getAddress().getPort();
    }

    static void stop(HttpServer s) {
        if (s == null) return;
        s.stop(0);
        if (s.getExecutor() instanceof ExecutorService ex) ex.shutdownNow();
    }

    record Response(int status, String body, java.net.http.HttpHeaders headers) {
        Map<String, Object> json() throws Exception {
            return Http.JSON.readValue(body, new TypeReference<Map<String, Object>>() {});
        }
        String code() throws Exception { return String.valueOf(json().get("code")); }
        String error() throws Exception { return String.valueOf(json().get("error")); }
    }

    /** Sends a real HTTP request. extraHeaders is name, value, name, value ... */
    static Response send(int port, String method, String path, String body, String contentType, String... extraHeaders) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (contentType != null) b.header("Content-Type", contentType);
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) b.header(extraHeaders[i], extraHeaders[i + 1]);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), r.body(), r.headers());
    }

    static Response get(int port, String path) throws Exception {
        return send(port, "GET", path, null, null);
    }

    static Response put(int port, String json, String... extraHeaders) throws Exception {
        return send(port, "PUT", "/api/state", json, "application/json", extraHeaders);
    }

    /** Sends a request over a plain socket so any Host header can be used. Returns the full raw response. */
    static String raw(int port, String request) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            try (InputStream in = s.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        }
    }

    static int rawStatus(String response) {
        return Integer.parseInt(response.split(" ", 3)[1]);
    }

    static String rawRequest(String method, String path, String host, String body, String... headers) {
        StringBuilder sb = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
        if (host != null) sb.append("Host: ").append(host).append("\r\n");
        for (int i = 0; i + 1 < headers.length; i += 2) sb.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (body != null) sb.append("Content-Type: application/json\r\n");
        sb.append("Content-Length: ").append(b.length).append("\r\nConnection: close\r\n\r\n");
        if (body != null) sb.append(body);
        return sb.toString();
    }

    // ---- payload builders --------------------------------------------------------------------

    static Map<String, Object> pet(String id, String name, String species) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id); p.put("name", name); p.put("species", species);
        return p;
    }

    static Map<String, Object> record(String id, String petId, String title) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id); r.put("petId", petId); r.put("type", "Other"); r.put("title", title);
        return r;
    }

    static String body(long baseRevision, List<Map<String, Object>> pets, List<Map<String, Object>> records) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseRevision", baseRevision); m.put("pets", pets); m.put("records", records);
        return Http.JSON.writeValueAsString(m);
    }

    @SafeVarargs
    static <T> List<T> list(T... items) {
        List<T> out = new ArrayList<>();
        for (T item : items) out.add(item);
        return out;
    }

    static StateValidator.StateInput input(long baseRevision, Map<String, Object> state) {
        Map<String, Object> m = new LinkedHashMap<>(state);
        m.put("baseRevision", baseRevision);
        m.remove("revision");
        return StateValidator.parse(m);
    }
}
