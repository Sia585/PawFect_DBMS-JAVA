package com.pawfect;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/** Local Pawfect web server and SQLite-backed JSON API. */
public final class PawfectServer {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        int port = Integer.parseInt(System.getenv().getOrDefault("PAWFECT_PORT", "8080"));
        HttpServer server = start(new Database(Database.DEFAULT_FILE), port);
        System.out.println("Pawfect is running at http://localhost:" + server.getAddress().getPort());
        System.out.println("SQLite database: " + new java.io.File("pawfect.db").getAbsolutePath());
    }

    /** Prepares the database (baseline schema, then migrations) and starts the server on 127.0.0.1. Port 0 picks a free port. */
    static HttpServer start(Database db, int port) throws Exception {
        db.initialize();
        Migrator.Result migration = new Migrator(db, Migrations.all(), new BackupService()).migrate();
        if (migration.backup() != null) System.out.println("Database upgraded from v" + migration.fromVersion() + " to v" + migration.toVersion() + ". Backup: " + migration.backup());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        RequestGuard guard = new RequestGuard(server.getAddress().getPort());
        HttpContext state = server.createContext("/api/state", new StateHandler(new StateRepository(db)));
        HttpContext weights = server.createContext("/api/weights", new WeightHandler(new WeightRepository(db)));
        HttpContext statics = server.createContext("/", new StaticHandler());
        state.getFilters().add(guard);
        weights.getFilters().add(guard);
        statics.getFilters().add(guard);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }
}
