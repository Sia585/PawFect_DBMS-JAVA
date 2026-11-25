package com.pawfect;

import java.sql.*;
import java.util.*;

/**
 * Reads the pet and care-record snapshot and saves it by reconciliation: rows are inserted,
 * updated in place or deleted by stable ID, never wholesale deleted and re-created. That keeps
 * anything that references a pet (now or in later milestones) from being cascade-deleted by an
 * unrelated save. A revision counter rejects saves made from out-of-date data.
 */
final class StateRepository {
    /** Counts of what a save actually changed, plus the revision after the save. */
    record SaveResult(long revision, int inserted, int updated, int deleted) {}

    private static final String UPSERT_PET = """
        INSERT INTO pet(id,name,species,breed,sex,birthdate,weight,vet,face,notes) VALUES(?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET name=excluded.name, species=excluded.species, breed=excluded.breed,
          sex=excluded.sex, birthdate=excluded.birthdate, weight=excluded.weight, vet=excluded.vet,
          face=excluded.face, notes=excluded.notes
        WHERE pet.name IS NOT excluded.name OR pet.species IS NOT excluded.species OR pet.breed IS NOT excluded.breed
          OR pet.sex IS NOT excluded.sex OR pet.birthdate IS NOT excluded.birthdate OR pet.weight IS NOT excluded.weight
          OR pet.vet IS NOT excluded.vet OR pet.face IS NOT excluded.face OR pet.notes IS NOT excluded.notes
        """;
    private static final String UPSERT_RECORD = """
        INSERT INTO care_record(id,pet_id,type,title,record_date,due_date,notes) VALUES(?,?,?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET pet_id=excluded.pet_id, type=excluded.type, title=excluded.title,
          record_date=excluded.record_date, due_date=excluded.due_date, notes=excluded.notes
        WHERE care_record.pet_id IS NOT excluded.pet_id OR care_record.type IS NOT excluded.type
          OR care_record.title IS NOT excluded.title OR care_record.record_date IS NOT excluded.record_date
          OR care_record.due_date IS NOT excluded.due_date OR care_record.notes IS NOT excluded.notes
        """;

    private final Database db;

    StateRepository(Database db) {
        this.db = db;
    }

    /** Reads revision, pets and records from one consistent snapshot. */
    Map<String, Object> readState() throws SQLException {
        List<Map<String, Object>> pets = new ArrayList<>(), records = new ArrayList<>();
        long revision;
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try {
                revision = revision(c);
                try (Statement s = c.createStatement();
                     ResultSet r = s.executeQuery("SELECT id,name,species,breed,sex,birthdate,weight,vet,face,notes FROM pet ORDER BY rowid")) {
                    while (r.next()) {
                        Map<String, Object> p = new LinkedHashMap<>();
                        for (String col : List.of("id","name","species","breed","sex","birthdate","weight","vet","face","notes")) p.put(col, r.getString(col));
                        pets.add(p);
                    }
                }
                try (Statement s = c.createStatement();
                     ResultSet r = s.executeQuery("SELECT id,pet_id,type,title,record_date,due_date,notes FROM care_record ORDER BY rowid")) {
                    while (r.next()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", r.getString("id")); item.put("petId", r.getString("pet_id"));
                        item.put("type", r.getString("type")); item.put("title", r.getString("title"));
                        item.put("date", r.getString("record_date")); item.put("dueDate", r.getString("due_date"));
                        item.put("notes", r.getString("notes")); records.add(item);
                    }
                }
            } finally {
                c.rollback();
            }
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("revision", revision);
        state.put("pets", pets);
        state.put("records", records);
        return state;
    }

    /**
     * Reconciles the database with the supplied snapshot in one IMMEDIATE transaction. Throws
     * ConflictException (and changes nothing) when baseRevision is stale. Any failure rolls
     * everything back.
     */
    SaveResult save(StateValidator.StateInput in) throws SQLException {
        try (Connection c = db.connect(); Statement tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                long current = revision(c);
                if (current != in.baseRevision()) throw new ConflictException(current);

                Set<String> existingPets = ids(c, "pet"), existingRecords = ids(c, "care_record");
                int inserted = 0, updated = 0, deleted = 0;

                try (PreparedStatement q = c.prepareStatement(UPSERT_PET)) {
                    for (StateValidator.Pet p : in.pets()) {
                        int i = 1;
                        q.setString(i++, p.id()); q.setString(i++, p.name()); q.setString(i++, p.species()); q.setString(i++, p.breed());
                        q.setString(i++, p.sex()); q.setString(i++, p.birthdate()); q.setString(i++, p.weight()); q.setString(i++, p.vet());
                        q.setString(i++, p.face()); q.setString(i, p.notes());
                        if (q.executeUpdate() > 0) { if (existingPets.contains(p.id())) updated++; else inserted++; }
                    }
                }
                try (PreparedStatement q = c.prepareStatement(UPSERT_RECORD)) {
                    for (StateValidator.CareRecord r : in.records()) {
                        q.setString(1, r.id()); q.setString(2, r.petId()); q.setString(3, r.type()); q.setString(4, r.title());
                        q.setString(5, r.date()); q.setString(6, r.dueDate()); q.setString(7, r.notes());
                        if (q.executeUpdate() > 0) { if (existingRecords.contains(r.id())) updated++; else inserted++; }
                    }
                }

                // Explicit removals only: rows that exist but are absent from the snapshot. Records go first.
                Set<String> keepRecords = new HashSet<>(), keepPets = new HashSet<>();
                in.records().forEach(r -> keepRecords.add(r.id()));
                in.pets().forEach(p -> keepPets.add(p.id()));
                deleted += deleteMissing(c, "care_record", existingRecords, keepRecords);
                deleted += deleteMissing(c, "pet", existingPets, keepPets);

                long revision = current;
                if (inserted + updated + deleted > 0) {
                    try (Statement s = c.createStatement()) { s.executeUpdate("UPDATE app_state SET revision = revision + 1 WHERE id = 1"); }
                    revision = revision(c);
                }
                tx.execute("COMMIT");
                return new SaveResult(revision, inserted, updated, deleted);
            } catch (Throwable t) {
                try { tx.execute("ROLLBACK"); } catch (SQLException ignored) { }
                throw t;
            }
        }
    }

    private static int deleteMissing(Connection c, String table, Set<String> existing, Set<String> keep) throws SQLException {
        int n = 0;
        try (PreparedStatement q = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            for (String id : existing) {
                if (keep.contains(id)) continue;
                q.setString(1, id);
                n += q.executeUpdate();
            }
        }
        return n;
    }

    private static Set<String> ids(Connection c, String table) throws SQLException {
        Set<String> ids = new HashSet<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT id FROM " + table)) {
            while (r.next()) ids.add(r.getString(1));
        }
        return ids;
    }

    private static long revision(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT revision FROM app_state WHERE id = 1")) {
            return r.next() ? r.getLong(1) : 0L;
        }
    }
}
