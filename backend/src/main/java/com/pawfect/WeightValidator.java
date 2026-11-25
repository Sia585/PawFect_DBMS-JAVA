package com.pawfect;

import java.time.LocalDate;
import java.util.*;

/** Validates weight request bodies. Touches no database. */
final class WeightValidator {
    /** One measurement. grams is the stored unit; the browser converts from kg or lb. */
    record Weight(String id, String petId, String date, long grams, String note, String sourceRecordId) {}

    record Change(long baseRevision, Weight weight) {}

    record Import(long baseRevision, List<Weight> items) {}

    static final long MAX_GRAMS = 2_000_000L;       // 2,000 kg
    static final int MAX_IMPORT = 500, MAX_NOTE = 500;
    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);

    private WeightValidator() {}

    /** Body of PUT /api/weights/{id}. */
    static Change parseOne(String id, Map<String, Object> body) {
        long base = StateValidator.baseRevision(body.get("baseRevision"));
        return new Change(base, weight(id, body));
    }

    /** Body of POST /api/weights/import. */
    static Import parseImport(Map<String, Object> body) {
        long base = StateValidator.baseRevision(body.get("baseRevision"));
        List<Map<String, Object>> raw = StateValidator.objectList(body.get("items"), "items");
        if (raw.isEmpty()) throw new ValidationException("Nothing to import.");
        if (raw.size() > MAX_IMPORT) throw new ValidationException("Too many items to import at once (max " + MAX_IMPORT + ").");
        List<Weight> items = new ArrayList<>();
        Set<String> ids = new HashSet<>(), sources = new HashSet<>();
        for (Map<String, Object> m : raw) {
            StateValidator.required(m, "id");
            Weight w = weight(StateValidator.id(m, "id"), m);
            if (!ids.add(w.id())) throw new ValidationException("Imported weight IDs must be unique.");
            if (w.sourceRecordId() != null && !sources.add(w.sourceRecordId())) throw new ValidationException("A care record can only be imported once.");
            items.add(w);
        }
        return new Import(base, items);
    }

    /** Validates a path id such as the one in /api/weights/{id}. */
    static String pathId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._:-]{1,64}") || id.equals("import")) {
            throw new ValidationException("Invalid weight ID.");
        }
        return id;
    }

    private static Weight weight(String id, Map<String, Object> m) {
        StateValidator.required(m, "petId"); StateValidator.required(m, "date");
        String petId = StateValidator.id(m, "petId");
        String date = StateValidator.date(m, "date");
        LocalDate d = LocalDate.parse(date);
        if (d.isBefore(EARLIEST) || d.isAfter(LocalDate.now().plusDays(1))) {
            throw new ValidationException("A weight date must be between 1900 and today.");
        }
        Object g = m.get("grams");
        if (!(g instanceof Integer || g instanceof Long) || ((Number) g).longValue() < 1 || ((Number) g).longValue() > MAX_GRAMS) {
            throw new ValidationException("Weight must be a whole number of grams between 1 and " + MAX_GRAMS + ".");
        }
        String note = StateValidator.text(m, "note", MAX_NOTE);
        String source = m.get("sourceRecordId") == null || String.valueOf(m.get("sourceRecordId")).isBlank() ? null : StateValidator.id(m, "sourceRecordId");
        return new Weight(id, petId, date, ((Number) g).longValue(), note == null || note.isBlank() ? null : note, source);
    }
}
