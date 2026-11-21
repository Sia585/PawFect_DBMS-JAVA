package com.pawfect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Local Pawfect web server and SQLite-backed JSON API. */
public final class PawfectServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String DB_URL = "jdbc:sqlite:pawfect.db";
    private static final Map<String, String> MIME = Map.of(
        ".html", "text/html; charset=utf-8", ".css", "text/css; charset=utf-8",
        ".js", "text/javascript; charset=utf-8", ".json", "application/json; charset=utf-8",
        ".svg", "image/svg+xml", ".png", "image/png", ".ico", "image/x-icon"
    );

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        initializeDatabase();
        int port = Integer.parseInt(System.getenv().getOrDefault("PAWFECT_PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/state", PawfectServer::stateApi);
        server.createContext("/", PawfectServer::staticFiles);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("Pawfect is running at http://localhost:" + port);
        System.out.println("SQLite database: " + new java.io.File("pawfect.db").getAbsolutePath());
    }

    private static Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(DB_URL);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }

    private static void initializeDatabase() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("CREATE TABLE IF NOT EXISTS owner (id INTEGER PRIMARY KEY CHECK (id = 1), display_name TEXT NOT NULL DEFAULT 'Pet parent')");
            s.execute("INSERT OR IGNORE INTO owner(id) VALUES (1)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS pet (
                  id TEXT PRIMARY KEY, owner_id INTEGER NOT NULL DEFAULT 1 REFERENCES owner(id),
                  name TEXT NOT NULL, species TEXT NOT NULL, breed TEXT, sex TEXT,
                  birthdate TEXT, weight TEXT, vet TEXT, face TEXT, notes TEXT
                )
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS care_record (
                  id TEXT PRIMARY KEY, pet_id TEXT NOT NULL REFERENCES pet(id) ON DELETE CASCADE,
                  type TEXT NOT NULL, title TEXT NOT NULL, record_date TEXT,
                  due_date TEXT, notes TEXT
                )
                """);
            s.execute("CREATE INDEX IF NOT EXISTS care_record_pet_date ON care_record(pet_id, record_date)");
            s.execute("CREATE INDEX IF NOT EXISTS care_record_due_date ON care_record(due_date)");
        }
    }

    private static void stateApi(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200, readState());
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                Map<String, Object> body = JSON.readValue(exchange.getRequestBody(), MAP);
                writeState(body);
                sendJson(exchange, 200, Map.of("saved", true));
            } else {
                exchange.getResponseHeaders().set("Allow", "GET, PUT");
                sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            }
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            sendJson(exchange, 500, Map.of("error", "The Pawfect database could not complete that request."));
        }
    }

    private static Map<String, Object> readState() throws SQLException {
        List<Map<String, Object>> pets = new ArrayList<>(), records = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT id,name,species,breed,sex,birthdate,weight,vet,face,notes FROM pet ORDER BY rowid")) {
            while (r.next()) {
                Map<String, Object> p = new LinkedHashMap<>();
                for (String col : List.of("id","name","species","breed","sex","birthdate","weight","vet","face","notes")) p.put(col, r.getString(col));
                pets.add(p);
            }
        }
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT id,pet_id,type,title,record_date,due_date,notes FROM care_record ORDER BY rowid")) {
            while (r.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", r.getString("id")); item.put("petId", r.getString("pet_id"));
                item.put("type", r.getString("type")); item.put("title", r.getString("title"));
                item.put("date", r.getString("record_date")); item.put("dueDate", r.getString("due_date"));
                item.put("notes", r.getString("notes")); records.add(item);
            }
        }
        Map<String, Object> state = new LinkedHashMap<>(); state.put("pets", pets); state.put("records", records); return state;
    }

    /** Replaces the supplied snapshot in one SQLite transaction. */
    private static void writeState(Map<String, Object> state) throws SQLException {
        List<Map<String, Object>> pets = objectList(state.get("pets"), "pets");
        List<Map<String, Object>> records = objectList(state.get("records"), "records");
        Set<String> petIds = new HashSet<>(), recordIds = new HashSet<>();
        for (Map<String, Object> p : pets) {
            required(p, "id"); required(p, "name"); required(p, "species");
            if (!petIds.add(value(p, "id"))) throw new IllegalArgumentException("Pet IDs must be unique.");
        }
        for (Map<String, Object> r : records) {
            required(r, "id"); required(r, "petId"); required(r, "title"); required(r, "type");
            if (!petIds.contains(value(r, "petId"))) throw new IllegalArgumentException("Every care record must belong to a saved pet.");
            if (!recordIds.add(value(r, "id"))) throw new IllegalArgumentException("Care record IDs must be unique.");
        }
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) { s.executeUpdate("DELETE FROM care_record"); s.executeUpdate("DELETE FROM pet"); }
                try (PreparedStatement q = c.prepareStatement("INSERT INTO pet(id,name,species,breed,sex,birthdate,weight,vet,face,notes) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    for (Map<String,Object> p : pets) {
                        int i=1; q.setString(i++,value(p,"id")); q.setString(i++,value(p,"name")); q.setString(i++,value(p,"species"));
                        q.setString(i++,value(p,"breed")); q.setString(i++,value(p,"sex")); q.setString(i++,value(p,"birthdate"));
                        q.setString(i++,value(p,"weight")); q.setString(i++,value(p,"vet")); q.setString(i++,value(p,"face")); q.setString(i,value(p,"notes")); q.addBatch();
                    } q.executeBatch();
                }
                try (PreparedStatement q = c.prepareStatement("INSERT INTO care_record(id,pet_id,type,title,record_date,due_date,notes) VALUES(?,?,?,?,?,?,?)")) {
                    for (Map<String,Object> r : records) {
                        q.setString(1,value(r,"id")); q.setString(2,value(r,"petId")); q.setString(3,value(r,"type"));
                        q.setString(4,value(r,"title")); q.setString(5,value(r,"date")); q.setString(6,value(r,"dueDate")); q.setString(7,value(r,"notes")); q.addBatch();
                    } q.executeBatch();
                }
                c.commit();
            } catch (Exception e) { c.rollback(); if (e instanceof SQLException sql) throw sql; if(e instanceof IllegalArgumentException bad) throw bad; throw new SQLException(e); }
            finally { c.setAutoCommit(true); }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> objectList(Object v, String name) {
        if (!(v instanceof List<?> list)) throw new IllegalArgumentException("Expected a '" + name + "' array.");
        List<Map<String,Object>> result = new ArrayList<>();
        for (Object item : list) { if (!(item instanceof Map<?,?> map)) throw new IllegalArgumentException("Invalid item in '" + name + "'."); result.add((Map<String,Object>)map); }
        return result;
    }
    private static String value(Map<String,Object> m, String k) { Object v=m.get(k); return v==null?null:String.valueOf(v); }
    private static void required(Map<String,Object> m, String k) { if (value(m,k)==null || value(m,k).isBlank()) throw new IllegalArgumentException("Missing required field: " + k); }

    private static void staticFiles(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) { sendJson(exchange,405,Map.of("error","Method not allowed")); return; }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) path="/index.html";
        if (path.contains("..") || path.indexOf('\\')>=0) { sendJson(exchange,400,Map.of("error","Invalid path")); return; }
        try (var in = PawfectServer.class.getResourceAsStream("/static" + path)) {
            if (in==null) { sendJson(exchange,404,Map.of("error","Not found")); return; }
            byte[] body=in.readAllBytes(); String ext=path.substring(path.lastIndexOf('.'));
            exchange.getResponseHeaders().set("Content-Type",MIME.getOrDefault(ext,"application/octet-stream"));
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.sendResponseHeaders(200,"HEAD".equals(exchange.getRequestMethod())?-1:body.length);
            if (!"HEAD".equals(exchange.getRequestMethod())) exchange.getResponseBody().write(body);
            exchange.close();
        }
    }
    private static void sendJson(HttpExchange x, int status, Object body) throws IOException {
        byte[] bytes=JSON.writeValueAsBytes(body); x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control","no-store"); x.sendResponseHeaders(status,bytes.length); x.getResponseBody().write(bytes); x.close();
    }
}
