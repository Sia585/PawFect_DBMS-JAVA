package com.pawfect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** The ordered list of schema migrations. Only additive changes are allowed. */
final class Migrations {
    private Migrations() {}

    static List<Migration> all() {
        return List.of(new AppStateMigration(), new WeightMigration());
    }

    /** v1: a single-row table holding the revision counter used to detect stale saves. */
    static final class AppStateMigration implements Migration {
        @Override public int version() { return 1; }

        @Override public String description() { return "app_state revision counter"; }

        @Override
        public void apply(Connection c) throws SQLException {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS app_state (id INTEGER PRIMARY KEY CHECK (id = 1), revision INTEGER NOT NULL DEFAULT 0)");
                s.execute("INSERT OR IGNORE INTO app_state(id, revision) VALUES (1, 0)");
            }
        }
    }

    /**
     * v2: structured weight measurements. Weight is stored as whole grams. ON DELETE RESTRICT means a pet
     * that still has measurements cannot be deleted by accident; the app removes them explicitly first.
     * source_record_id remembers which care record an imported measurement came from (no foreign key, so
     * deleting that care record is never blocked and never deletes the measurement).
     */
    static final class WeightMigration implements Migration {
        @Override public int version() { return 2; }

        @Override public String description() { return "weight_measurement table"; }

        @Override
        public void apply(Connection c) throws SQLException {
            try (Statement s = c.createStatement()) {
                s.execute("""
                    CREATE TABLE weight_measurement (
                      id TEXT PRIMARY KEY,
                      pet_id TEXT NOT NULL REFERENCES pet(id) ON DELETE RESTRICT,
                      measured_on TEXT NOT NULL,
                      grams INTEGER NOT NULL CHECK (grams > 0 AND grams <= 2000000),
                      note TEXT,
                      source_record_id TEXT
                    )""");
                s.execute("CREATE INDEX idx_weight_pet_date ON weight_measurement(pet_id, measured_on)");
                s.execute("CREATE UNIQUE INDEX idx_weight_source ON weight_measurement(source_record_id) WHERE source_record_id IS NOT NULL");
            }
        }
    }
}
