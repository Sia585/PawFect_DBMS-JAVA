package com.pawfect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Runs one change in a BEGIN IMMEDIATE transaction guarded by the revision counter: a stale baseRevision
 * throws ConflictException and changes nothing; the revision moves up by one only if the work changed
 * something; any failure rolls everything back.
 */
final class Revisioned {
    interface Work { boolean run(Connection c) throws SQLException; }

    /** changed=false means the request matched what was already stored, so the revision did not move. */
    record Result(long revision, boolean changed) {}

    private Revisioned() {}

    static Result run(Database db, long baseRevision, Work work) throws SQLException {
        try (Connection c = db.connect(); Statement tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                long current = StateRepository.revision(c);
                if (current != baseRevision) throw new ConflictException(current);
                boolean changed = work.run(c);
                long revision = current;
                if (changed) {
                    tx.executeUpdate("UPDATE app_state SET revision = revision + 1 WHERE id = 1");
                    revision = StateRepository.revision(c);
                }
                tx.execute("COMMIT");
                return new Result(revision, changed);
            } catch (Throwable t) {
                try { tx.execute("ROLLBACK"); } catch (SQLException ignored) { }
                throw t;
            }
        }
    }
}
