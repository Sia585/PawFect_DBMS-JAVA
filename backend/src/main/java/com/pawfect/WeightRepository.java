package com.pawfect;

import java.sql.*;
import java.util.*;

/**
 * Weight measurements. They are changed only by these explicit operations (add/edit, delete, import),
 * never by the pet and care-record save, so unrelated edits cannot delete or alter them. Every operation
 * uses the same revision check as the main save.
 */
final class WeightRepository {
    static final int MAX_PER_PET = 5_000;

    /** changed=false means the request matched what was already stored, so the revision did not move. */
    record Result(long revision, boolean changed) {}

    private interface Work { boolean run(Connection c) throws SQLException; }

    private final Database db;

    WeightRepository(Database db) {
        this.db = db;
    }

    /** All measurements, oldest first, in the shape the browser uses. */
    static List<Map<String, Object>> readAll(Connection c) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT id,pet_id,measured_on,grams,note,source_record_id FROM weight_measurement ORDER BY measured_on, rowid")) {
            while (r.next()) {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("id", r.getString(1)); w.put("petId", r.getString(2)); w.put("date", r.getString(3));
                w.put("grams", r.getLong(4)); w.put("note", r.getString(5)); w.put("sourceRecordId", r.getString(6));
                out.add(w);
            }
        }
        return out;
    }

    /** Adds a measurement or edits the one with the same id (its pet cannot change). */
    Result save(WeightValidator.Change in) throws SQLException {
        return inTransaction(in.baseRevision(), c -> upsert(c, in.weight()));
    }

    Result delete(long baseRevision, String id) throws SQLException {
        return inTransaction(baseRevision, c -> {
            try (PreparedStatement q = c.prepareStatement("DELETE FROM weight_measurement WHERE id = ?")) {
                q.setString(1, id);
                if (q.executeUpdate() == 0) throw new ValidationException(404, "NOT_FOUND", "That weight measurement no longer exists.");
                return true;
            }
        });
    }

    /** All-or-nothing: either every item is added or none is. */
    Result importAll(WeightValidator.Import in) throws SQLException {
        return inTransaction(in.baseRevision(), c -> {
            boolean changed = false;
            for (WeightValidator.Weight w : in.items()) {
                if (exists(c, w.id())) throw new ValidationException(409, "DUPLICATE_WEIGHT", "That weight was already added.");
                changed |= upsert(c, w);
            }
            return changed;
        });
    }

    private static boolean upsert(Connection c, WeightValidator.Weight w) throws SQLException {
        String pet = query(c, "SELECT 1 FROM pet WHERE id = ?", w.petId());
        if (pet == null) throw new ValidationException(404, "PET_NOT_FOUND", "That pet no longer exists.");
        String owner = query(c, "SELECT pet_id FROM weight_measurement WHERE id = ?", w.id());
        if (owner != null && !owner.equals(w.petId())) throw new ValidationException("A measurement cannot be moved to another pet.");
        if (owner == null && Long.parseLong(query(c, "SELECT COUNT(*) FROM weight_measurement WHERE pet_id = ?", w.petId())) >= MAX_PER_PET) {
            throw new ValidationException("Too many weight measurements for one pet (max " + MAX_PER_PET + ").");
        }
        try (PreparedStatement q = c.prepareStatement("""
            INSERT INTO weight_measurement(id,pet_id,measured_on,grams,note,source_record_id) VALUES(?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET measured_on=excluded.measured_on, grams=excluded.grams, note=excluded.note,
              source_record_id=excluded.source_record_id
            WHERE weight_measurement.measured_on IS NOT excluded.measured_on OR weight_measurement.grams IS NOT excluded.grams
              OR weight_measurement.note IS NOT excluded.note OR weight_measurement.source_record_id IS NOT excluded.source_record_id
            """)) {
            q.setString(1, w.id()); q.setString(2, w.petId()); q.setString(3, w.date()); q.setLong(4, w.grams());
            q.setString(5, w.note()); q.setString(6, w.sourceRecordId());
            return q.executeUpdate() > 0;
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("UNIQUE")) {
                throw new ValidationException(409, "DUPLICATE_WEIGHT", "That care record was already imported as a weight.");
            }
            throw e;
        }
    }

    private static boolean exists(Connection c, String id) throws SQLException {
        return query(c, "SELECT 1 FROM weight_measurement WHERE id = ?", id) != null;
    }

    private static String query(Connection c, String sql, String arg) throws SQLException {
        try (PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, arg);
            try (ResultSet r = q.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    private Result inTransaction(long baseRevision, Work work) throws SQLException {
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
