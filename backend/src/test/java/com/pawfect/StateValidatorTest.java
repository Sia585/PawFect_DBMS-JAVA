package com.pawfect;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.pawfect.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class StateValidatorTest {
    private static Map<String, Object> state(Object baseRevision, Object pets, Object records) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (baseRevision != null) m.put("baseRevision", baseRevision);
        if (pets != null) m.put("pets", pets);
        if (records != null) m.put("records", records);
        return m;
    }

    private static ValidationException rejects(Map<String, Object> body) {
        return assertThrows(ValidationException.class, () -> StateValidator.parse(body));
    }

    @Test
    void acceptsAValidStateAndIgnoresUnknownKeys() {
        Map<String, Object> p = pet("pet-milo-001", "Milo", "Dog");
        p.put("weight", 29.5);
        p.put("extra", "ignored");
        p.put("birthdate", "2022-03-14");
        StateValidator.StateInput in = StateValidator.parse(state(3, List.of(p), List.of(record("rec-1", "pet-milo-001", "Shot"))));
        assertEquals(3L, in.baseRevision());
        assertEquals("29.5", in.pets().get(0).weight());
        assertEquals(1, in.records().size());
    }

    @Test
    void baseRevisionIsRequiredAndMustBeAWholeNonNegativeNumber() {
        assertEquals("REVISION_REQUIRED", rejects(state(null, List.of(), List.of())).code());
        assertEquals("REVISION_REQUIRED", rejects(state(-1, List.of(), List.of())).code());
        assertEquals("REVISION_REQUIRED", rejects(state("3", List.of(), List.of())).code());
        assertEquals("REVISION_REQUIRED", rejects(state(1.5, List.of(), List.of())).code());
        assertEquals("REVISION_REQUIRED", rejects(state(true, List.of(), List.of())).code());
    }

    @Test
    void keepsTheOriginalErrorMessages() {
        assertEquals("Expected a 'pets' array.", rejects(state(0, "nope", List.of())).getMessage());
        assertEquals("Expected a 'records' array.", rejects(state(0, List.of(), null)).getMessage());
        assertEquals("Invalid item in 'pets'.", rejects(state(0, List.of(1), List.of())).getMessage());
        assertEquals("Missing required field: name", rejects(state(0, List.of(pet("a", "  ", "Dog")), List.of())).getMessage());
        assertEquals("Missing required field: species", rejects(state(0, List.of(Map.of("id", "a", "name", "n")), List.of())).getMessage());
        assertEquals("Missing required field: id", rejects(state(0, List.of(Map.of("name", "n", "species", "s")), List.of())).getMessage());
        assertEquals("Pet IDs must be unique.", rejects(state(0, List.of(pet("a", "x", "Dog"), pet("a", "y", "Dog")), List.of())).getMessage());
        assertEquals("Every care record must belong to a saved pet.",
            rejects(state(0, List.of(pet("a", "x", "Dog")), List.of(record("r", "zz", "t")))).getMessage());
        assertEquals("Care record IDs must be unique.",
            rejects(state(0, List.of(pet("a", "x", "Dog")), List.of(record("r", "a", "t"), record("r", "a", "u")))).getMessage());
        assertEquals("Missing required field: title", rejects(state(0, List.of(pet("a", "x", "Dog")), List.of(Map.of("id", "r", "petId", "a", "type", "t")))).getMessage());
        assertEquals("Missing required field: type", rejects(state(0, List.of(pet("a", "x", "Dog")), List.of(Map.of("id", "r", "petId", "a", "title", "t")))).getMessage());
    }

    @Test
    void rejectsBadIdsLongTextNestedValuesAndBadDates() {
        for (String bad : List.of("../x", "has space", "a/b", "x".repeat(65), "tab\t"))
            assertTrue(rejects(state(0, List.of(pet(bad, "n", "Dog")), List.of())).getMessage().contains("'id'"));
        assertTrue(rejects(state(0, List.of(pet("a", "n".repeat(101), "Dog")), List.of())).getMessage().contains("too long"));
        Map<String, Object> nested = pet("a", "n", "Dog");
        nested.put("notes", Map.of("x", 1));
        assertTrue(rejects(state(0, List.of(nested), List.of())).getMessage().contains("text, a number or empty"));
        for (String badDate : List.of("2026-02-30", "26-1-1", "2026-1-1", "yesterday", "2026-13-01")) {
            Map<String, Object> p = pet("a", "n", "Dog");
            p.put("birthdate", badDate);
            assertTrue(rejects(state(0, List.of(p), List.of())).getMessage().contains("birthdate"));
        }
        Map<String, Object> r = record("r", "a", "t");
        r.put("dueDate", "2026-02-31");
        assertTrue(rejects(state(0, List.of(pet("a", "n", "Dog")), List.of(r))).getMessage().contains("dueDate"));
    }

    @Test
    void blankAndMissingDatesAreAllowed() {
        Map<String, Object> p = pet("a", "n", "Dog");
        p.put("birthdate", "");
        Map<String, Object> r = record("r", "a", "t");
        r.put("date", "");
        r.put("dueDate", null);
        StateValidator.StateInput in = StateValidator.parse(state(0, List.of(p), List.of(r)));
        assertEquals("", in.pets().get(0).birthdate());
        assertNull(in.records().get(0).dueDate());
    }

    @Test
    void acceptsTheIdStylesUsedByTheAppAndTheSampleData() {
        for (String id : List.of("pet-milo-001", "6b3eab45-ecb3-4fef-ac6b-c3e99ea361ef", "rec_1", "a.b:c")) {
            assertEquals(id, StateValidator.parse(state(0, List.of(pet(id, "n", "Dog")), List.of())).pets().get(0).id());
        }
    }

    @Test
    void limitsTheNumberOfPets() {
        List<Object> many = new java.util.ArrayList<>();
        for (int i = 0; i <= StateValidator.MAX_PETS; i++) many.add(pet("p" + i, "n", "Dog"));
        assertTrue(rejects(state(0, many, List.of())).getMessage().contains("Too many pets"));
    }
}
