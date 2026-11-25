package com.pawfect;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies pending migrations in order, each in its own transaction, tracking progress with
 * PRAGMA user_version. A verified backup is taken first when the database already holds data.
 */
final class Migrator {
    /** Outcome of a migration run; backup is null when none was needed. */
    record Result(int fromVersion, int toVersion, Path backup) {}

    private final Database db;
    private final List<Migration> migrations;
    private final BackupService backups;

    Migrator(Database db, List<Migration> migrations, BackupService backups) {
        int previous = 0;
        for (Migration m : migrations) {
            if (m.version() <= previous) throw new IllegalArgumentException("Migrations must have strictly increasing versions.");
            previous = m.version();
        }
        this.db = db;
        this.migrations = List.copyOf(migrations);
        this.backups = backups;
    }

    Result migrate() throws SQLException {
        try (Connection c = db.connect()) {
            int current = userVersion(c);
            int latest = migrations.isEmpty() ? 0 : migrations.get(migrations.size() - 1).version();
            if (current > latest) {
                throw new MigrationException("This database is at schema version " + current + " but this version of Pawfect only understands up to " + latest + ". Update Pawfect instead of using an older copy.");
            }
            List<Migration> pending = new ArrayList<>();
            for (Migration m : migrations) if (m.version() > current) pending.add(m);
            if (pending.isEmpty()) return new Result(current, current, null);

            Path backup = hasUserData(c) ? backups.backup(db, c, current, latest) : null;
            for (Migration m : pending) apply(c, m);
            return new Result(current, latest, backup);
        }
    }

    private void apply(Connection c, Migration m) {
        try (Statement s = c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            try {
                m.apply(c);
                s.execute("PRAGMA user_version = " + m.version());
                try (ResultSet r = s.executeQuery("PRAGMA foreign_key_check")) {
                    if (r.next()) throw new MigrationException("Foreign-key check failed after migration v" + m.version() + ".");
                }
                s.execute("COMMIT");
            } catch (Throwable t) {
                try { s.execute("ROLLBACK"); } catch (SQLException ignored) { }
                if (t instanceof MigrationException me) throw me;
                throw new MigrationException("Migration v" + m.version() + " (" + m.description() + ") failed and was rolled back: " + t.getMessage(), t);
            }
        } catch (SQLException e) {
            throw new MigrationException("Migration v" + m.version() + " could not be started: " + e.getMessage(), e);
        }
    }

    static int userVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) {
            r.next();
            return r.getInt(1);
        }
    }

    /** True when pets or care records exist, i.e. when a backup is worth making. */
    private static boolean hasUserData(Connection c) throws SQLException {
        long total = 0;
        for (var e : BackupService.countRows(c).entrySet()) {
            if (e.getKey().equals("pet") || e.getKey().equals("care_record")) total += e.getValue();
        }
        return total > 0;
    }
}
