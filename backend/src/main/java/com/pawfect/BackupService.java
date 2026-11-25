package com.pawfect;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates and verifies a consistent copy of the database before a migration.
 * VACUUM INTO is used because it also captures data that is still in the WAL file.
 */
class BackupService {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Copies the database to backups/ next to it, verifies the copy, and returns its path. */
    Path backup(Database db, Connection source, int fromVersion, int toVersion) {
        Path target = null;
        try {
            Path dir = db.directory().resolve("backups");
            Files.createDirectories(dir);
            target = uniqueTarget(dir, fromVersion, toVersion);
            Map<String, Long> expected = countRows(source);
            int userVersion = Migrator.userVersion(source);
            createCopy(source, target);
            verify(target, expected, userVersion);
            return target;
        } catch (IOException | SQLException | RuntimeException e) {
            if (target != null) {
                try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            }
            if (e instanceof MigrationException me) throw me;
            throw new MigrationException("Could not create a verified backup, so the migration was not started: " + e.getMessage(), e);
        }
    }

    /** Overridable so tests can simulate a failing or corrupt backup. */
    protected void createCopy(Connection source, Path target) throws SQLException {
        try (Statement s = source.createStatement()) {
            s.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
        }
    }

    /** Opens the copy read-only and checks integrity, user_version and every table's row count. */
    void verify(Path backup, Map<String, Long> expectedCounts, int expectedUserVersion) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection c = config.createConnection("jdbc:sqlite:" + backup);
             Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("PRAGMA integrity_check")) {
                if (!r.next() || !"ok".equals(r.getString(1))) throw new MigrationException("Backup failed the integrity check.");
            }
            if (Migrator.userVersion(c) != expectedUserVersion) throw new MigrationException("Backup has the wrong schema version.");
            Map<String, Long> actual = countRows(c);
            if (!actual.equals(expectedCounts)) throw new MigrationException("Backup row counts do not match the database: expected " + expectedCounts + " but found " + actual);
        }
    }

    static Map<String, Long> countRows(Connection c) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
             ResultSet tables = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            java.util.List<String> names = new java.util.ArrayList<>();
            while (tables.next()) names.add(tables.getString(1));
            for (String name : names) {
                try (Statement q = c.createStatement(); ResultSet r = q.executeQuery("SELECT COUNT(*) FROM \"" + name.replace("\"", "\"\"") + "\"")) {
                    r.next();
                    counts.put(name, r.getLong(1));
                }
            }
        }
        return counts;
    }

    private static Path uniqueTarget(Path dir, int from, int to) {
        String base = "pawfect-pre-v" + from + "-to-v" + to + "-" + LocalDateTime.now().format(STAMP);
        Path p = dir.resolve(base + ".db");
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(base + "-" + i + ".db");
        return p;
    }
}
