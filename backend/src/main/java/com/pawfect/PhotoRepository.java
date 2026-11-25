package com.pawfect;

import java.io.IOException;
import java.sql.*;

/**
 * Sets or clears a pet's photograph. The new file is written first, then the database row is switched in
 * one revision-checked transaction; the old file is deleted only after that commit, and the new file is
 * deleted if the transaction fails, so the database never points at a missing file.
 */
final class PhotoRepository {
    record Result(long revision, String photo) {}

    private final Database db;
    private final PhotoStore store;

    PhotoRepository(Database db, PhotoStore store) {
        this.db = db;
        this.store = store;
    }

    Result replace(String petId, long baseRevision, byte[] jpeg) throws SQLException, IOException {
        String name = store.store(jpeg);
        String[] old = new String[1];
        try {
            Revisioned.Result r = Revisioned.run(db, baseRevision, c -> {
                old[0] = currentPhoto(c, petId);
                try (PreparedStatement q = c.prepareStatement("UPDATE pet SET photo_file = ? WHERE id = ?")) {
                    q.setString(1, name); q.setString(2, petId);
                    return q.executeUpdate() > 0;
                }
            });
            store.delete(old[0]);
            return new Result(r.revision(), name);
        } catch (Throwable t) {
            store.delete(name);
            throw t;
        }
    }

    /** Removing a photo from a pet that has none is a no-op (the revision does not move). */
    Result remove(String petId, long baseRevision) throws SQLException {
        String[] old = new String[1];
        Revisioned.Result r = Revisioned.run(db, baseRevision, c -> {
            old[0] = currentPhoto(c, petId);
            if (old[0] == null) return false;
            try (PreparedStatement q = c.prepareStatement("UPDATE pet SET photo_file = NULL WHERE id = ?")) {
                q.setString(1, petId);
                return q.executeUpdate() > 0;
            }
        });
        store.delete(old[0]);
        return new Result(r.revision(), null);
    }

    /** The pet's current file name (may be null); 404 if the pet does not exist. */
    private static String currentPhoto(Connection c, String petId) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("SELECT photo_file FROM pet WHERE id = ?")) {
            q.setString(1, petId);
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) throw new ValidationException(404, "PET_NOT_FOUND", "That pet no longer exists.");
                return r.getString(1);
            }
        }
    }
}
