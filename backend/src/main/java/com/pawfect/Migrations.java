package com.pawfect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** The ordered list of schema migrations. Only additive changes are allowed. */
final class Migrations {
    private Migrations() {}

    static List<Migration> all() {
        return List.of(new AppStateMigration());
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
}
