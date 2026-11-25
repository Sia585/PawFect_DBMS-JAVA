package com.pawfect;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Owns the SQLite connection settings and the baseline (version 0) schema. */
final class Database {
    /** Relative path: the database lives in the directory the server is started from. */
    static final Path DEFAULT_FILE = Path.of("pawfect.db");

    private final Path file;

    Database(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    /** Directory that holds the database file; backups (and later photos) live beside it. */
    Path directory() {
        return file.toAbsolutePath().getParent();
    }

    Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }

    /** Creates the three core tables, indexes and the single owner row if they are missing. */
    void initialize() throws SQLException {
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
}
