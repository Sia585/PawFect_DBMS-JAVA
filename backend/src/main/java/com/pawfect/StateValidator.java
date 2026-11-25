package com.pawfect;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/** Validates a /api/state PUT body and turns it into typed values. Touches no database. */
final class StateValidator {
    record Pet(String id, String name, String species, String breed, String sex, String birthdate,
               String weight, String vet, String face, String notes) {}

    record CareRecord(String id, String petId, String type, String title, String date, String dueDate, String notes) {}

    record StateInput(long baseRevision, List<Pet> pets, List<CareRecord> records) {}

    static final int MAX_PETS = 1_000, MAX_RECORDS = 50_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private StateValidator() {}

    static StateInput parse(Map<String, Object> body) {
        long baseRevision = baseRevision(body.get("baseRevision"));
        List<Map<String, Object>> rawPets = objectList(body.get("pets"), "pets");
        List<Map<String, Object>> rawRecords = objectList(body.get("records"), "records");
        if (rawPets.size() > MAX_PETS) throw new ValidationException("Too many pets (max " + MAX_PETS + ").");
        if (rawRecords.size() > MAX_RECORDS) throw new ValidationException("Too many care records (max " + MAX_RECORDS + ").");

        Set<String> petIds = new HashSet<>(), recordIds = new HashSet<>();
        List<Pet> pets = new ArrayList<>();
        for (Map<String, Object> p : rawPets) {
            required(p, "id"); required(p, "name"); required(p, "species");
            if (!petIds.add(value(p, "id"))) throw new ValidationException("Pet IDs must be unique.");
            pets.add(new Pet(id(p, "id"), text(p, "name", 100), text(p, "species", 100), text(p, "breed", 100), text(p, "sex", 30),
                date(p, "birthdate"), text(p, "weight", 30), text(p, "vet", 200), text(p, "face", 32), text(p, "notes", 2000)));
        }
        List<CareRecord> records = new ArrayList<>();
        for (Map<String, Object> r : rawRecords) {
            required(r, "id"); required(r, "petId"); required(r, "title"); required(r, "type");
            if (!petIds.contains(value(r, "petId"))) throw new ValidationException("Every care record must belong to a saved pet.");
            if (!recordIds.add(value(r, "id"))) throw new ValidationException("Care record IDs must be unique.");
            records.add(new CareRecord(id(r, "id"), id(r, "petId"), text(r, "type", 40), text(r, "title", 200),
                date(r, "date"), date(r, "dueDate"), text(r, "notes", 2000)));
        }
        return new StateInput(baseRevision, pets, records);
    }

    private static long baseRevision(Object v) {
        if (v == null) {
            throw new ValidationException("REVISION_REQUIRED", "baseRevision is required. Reload Pawfect to get the latest data and try again.");
        }
        if (v instanceof Integer || v instanceof Long) {
            long n = ((Number) v).longValue();
            if (n >= 0) return n;
        }
        throw new ValidationException("REVISION_REQUIRED", "baseRevision must be a non-negative whole number.");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectList(Object v, String name) {
        if (!(v instanceof List<?> list)) throw new ValidationException("Expected a '" + name + "' array.");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new ValidationException("Invalid item in '" + name + "'.");
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    private static String value(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Map || v instanceof List) throw new ValidationException("Field '" + k + "' must be text, a number or empty.");
        return v == null ? null : String.valueOf(v);
    }

    private static void required(Map<String, Object> m, String k) {
        if (value(m, k) == null || value(m, k).isBlank()) throw new ValidationException("Missing required field: " + k);
    }

    private static String text(Map<String, Object> m, String k, int max) {
        String s = value(m, k);
        if (s != null && s.length() > max) throw new ValidationException("Field '" + k + "' is too long (max " + max + " characters).");
        return s;
    }

    private static String id(Map<String, Object> m, String k) {
        String s = value(m, k);
        if (!ID.matcher(s).matches()) {
            throw new ValidationException("Field '" + k + "' may only contain letters, digits, '.', '_', ':' and '-' (max 64 characters).");
        }
        return s;
    }

    /** Blank or missing dates are allowed; anything else must be a real YYYY-MM-DD date. */
    private static String date(Map<String, Object> m, String k) {
        String s = value(m, k);
        if (s == null || s.isBlank()) return s;
        try {
            if (s.length() != 10) throw new DateTimeParseException("length", s, 0);
            LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new ValidationException("Field '" + k + "' must be a date in YYYY-MM-DD format.");
        }
        return s;
    }
}
