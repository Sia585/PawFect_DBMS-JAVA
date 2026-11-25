package com.pawfect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static com.pawfect.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class WeightRepositoryTest {
    @TempDir Path tmp;

    private static final String MILO = "pet-milo-001", LUNA = "pet-luna-002";

    private Database db;
    private StateRepository state;
    private WeightRepository weights;

    private void setUp() throws Exception {
        db = migrated(tmp);
        populate(db);
        state = new StateRepository(db);
        weights = new WeightRepository(db);
    }

    private long revision() throws Exception {
        return ((Number) state.readState().get("revision")).longValue();
    }

    private WeightValidator.Change change(long rev, String id, String petId, String date, long grams) {
        Map<String, Object> m = weight(petId, date, grams);
        m.put("baseRevision", rev);
        return WeightValidator.parseOne(id, m);
    }

    private static ValidationException invalid(Map<String, Object> body) {
        return assertThrows(ValidationException.class, () -> WeightValidator.parseOne("w1", body));
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = weight("pet-milo-001", "2026-01-05", 29500L);
        m.put("baseRevision", 0L);
        return m;
    }

    // ---- validator ------------------------------------------------------------------------------

    @Test
    void validatorAcceptsAGoodMeasurement() {
        Map<String, Object> m = ok();
        m.put("note", "after breakfast");
        WeightValidator.Change c = WeightValidator.parseOne("w1", m);
        assertEquals(new WeightValidator.Weight("w1", MILO, "2026-01-05", 29500, "after breakfast", null), c.weight());
        m.put("note", "   ");
        assertNull(WeightValidator.parseOne("w1", m).weight().note());
    }

    @Test
    void validatorRejectsBadValues() {
        for (Object grams : new Object[] {0L, -5L, 2_000_001L, 1.5, 29500.0, "29500", null, List.of(1)}) {
            Map<String, Object> m = ok(); m.put("grams", grams);
            invalid(m);
        }
        Map<String, Object> m = ok(); m.put("date", "2026-02-31"); invalid(m);
        m = ok(); m.put("date", ""); invalid(m);
        m = ok(); m.put("date", "1899-12-31"); invalid(m);
        m = ok(); m.put("date", LocalDate.now().plusDays(3).toString()); invalid(m);
        m = ok(); m.remove("petId"); invalid(m);
        m = ok(); m.put("petId", "../x"); invalid(m);
        m = ok(); m.put("note", "x".repeat(501)); invalid(m);
        m = ok(); m.put("sourceRecordId", "bad id!"); invalid(m);
        m = ok(); m.remove("baseRevision");
        assertEquals("REVISION_REQUIRED", assertThrows(ValidationException.class, () -> WeightValidator.parseOne("w1", ok().entrySet().stream()
            .filter(e -> !e.getKey().equals("baseRevision")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))).code());
        assertTrue(WeightValidator.parseOne("w1", withToday()).weight().date().equals(LocalDate.now().toString()));
        assertThrows(ValidationException.class, () -> WeightValidator.pathId("import"));
        assertThrows(ValidationException.class, () -> WeightValidator.pathId("a/b"));
    }

    private static Map<String, Object> withToday() {
        Map<String, Object> m = ok(); m.put("date", LocalDate.now().toString()); return m;
    }

    @Test
    void importValidationRejectsEmptyHugeAndDuplicateItems() throws Exception {
        assertThrows(ValidationException.class, () -> WeightValidator.parseImport(Map.of("baseRevision", 0L, "items", List.of())));
        List<Object> many = new ArrayList<>();
        for (int i = 0; i <= WeightValidator.MAX_IMPORT; i++) many.add(importItem("i" + i, MILO, "2026-01-01", 100, null));
        assertThrows(ValidationException.class, () -> WeightValidator.parseImport(Map.of("baseRevision", 0L, "items", many)));
        assertThrows(ValidationException.class, () -> WeightValidator.parseImport(Map.of("baseRevision", 0L, "items",
            List.of(importItem("a", MILO, "2026-01-01", 100, null), importItem("a", MILO, "2026-01-02", 100, null)))));
        assertThrows(ValidationException.class, () -> WeightValidator.parseImport(Map.of("baseRevision", 0L, "items",
            List.of(importItem("a", MILO, "2026-01-01", 100, "rec-1"), importItem("b", MILO, "2026-01-02", 100, "rec-1")))));
    }

    // ---- repository -----------------------------------------------------------------------------

    @Test
    void addEditAndDeleteAdvanceTheRevision() throws Exception {
        setUp();
        assertEquals(new WeightRepository.Result(1, true), weights.save(change(0, "w1", MILO, "2026-01-05", 29500)));
        assertEquals(new WeightRepository.Result(2, true), weights.save(change(1, "w2", MILO, "2026-02-05", 30100)));
        assertEquals(new WeightRepository.Result(3, true), weights.save(change(2, "w1", MILO, "2026-01-06", 29400)));      // edit
        assertEquals(List.of(Arrays.asList("w1", MILO, "2026-01-06", "29400", null, null), Arrays.asList("w2", MILO, "2026-02-05", "30100", null, null)), weightRows(db));
        assertEquals(new WeightRepository.Result(4, true), weights.delete(3, "w1"));
        assertEquals(1L, count(db, "weight_measurement"));
        assertEquals(4L, revision());
    }

    @Test
    void savingTheSameMeasurementAgainChangesNothing() throws Exception {
        setUp();
        weights.save(change(0, "w1", MILO, "2026-01-05", 29500));
        assertEquals(new WeightRepository.Result(1, false), weights.save(change(1, "w1", MILO, "2026-01-05", 29500)));
        assertEquals(1L, revision());
    }

    @Test
    void measurementsAreReturnedWithTheStateOldestFirst() throws Exception {
        setUp();
        weights.save(change(0, "w2", MILO, "2026-03-01", 31000));
        weights.save(change(1, "w1", MILO, "2026-01-01", 29000));
        weights.save(change(2, "w3", LUNA, "2026-02-01", 4200));
        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) state.readState().get("weights");
        assertEquals(List.of("w1", "w3", "w2"), list.stream().map(w -> (String) w.get("id")).toList());
        assertEquals(Map.of("id", "w3", "petId", LUNA, "date", "2026-02-01", "grams", 4200L).entrySet().stream().allMatch(e -> e.getValue().equals(list.get(1).get(e.getKey()))), true);
    }

    @Test
    void aStaleRevisionIsRejectedAndNothingChanges() throws Exception {
        setUp();
        weights.save(change(0, "w1", MILO, "2026-01-05", 29500));
        Map<String, List<List<String>>> before = dump(db);
        List<List<String>> rows = weightRows(db);
        ConflictException e = assertThrows(ConflictException.class, () -> weights.save(change(0, "w2", MILO, "2026-01-06", 100)));
        assertEquals(1, e.currentRevision());
        assertThrows(ConflictException.class, () -> weights.delete(0, "w1"));
        assertThrows(ConflictException.class, () -> weights.importAll(WeightValidator.parseImport(Map.of("baseRevision", 5L, "items", List.of(importItem("i", MILO, "2026-01-01", 5, null))))));
        assertEquals(before, dump(db));
        assertEquals(rows, weightRows(db));
    }

    @Test
    void anOrdinaryStateSaveThatIsStaleAfterAWeightEditIsRejected() throws Exception {
        setUp();
        Map<String, Object> snapshot = state.readState();
        weights.save(change(0, "w1", MILO, "2026-01-05", 29500));          // another tab adds a weight
        assertThrows(ConflictException.class, () -> state.save(input(0, snapshot)));
        assertEquals(1L, count(db, "weight_measurement"));
    }

    @Test
    void unknownPetMovingPetAndMissingMeasurementAreRefused() throws Exception {
        setUp();
        assertEquals("PET_NOT_FOUND", assertThrows(ValidationException.class, () -> weights.save(change(0, "w1", "ghost", "2026-01-05", 100))).code());
        weights.save(change(0, "w1", MILO, "2026-01-05", 29500));
        assertThrows(ValidationException.class, () -> weights.save(change(1, "w1", LUNA, "2026-01-05", 29500)));
        assertEquals("NOT_FOUND", assertThrows(ValidationException.class, () -> weights.delete(1, "nope")).code());
        assertEquals(1L, revision());
        assertEquals(1L, count(db, "weight_measurement"));
    }

    @Test
    void importAddsEverythingOrNothing() throws Exception {
        setUp();
        WeightValidator.Import good = WeightValidator.parseImport(Map.of("baseRevision", 0L, "items", List.of(
            importItem("i1", MILO, "2026-01-01", 29000, "rec-12"), importItem("i2", LUNA, "2026-01-02", 4100, "rec-13"))));
        assertEquals(new WeightRepository.Result(1, true), weights.importAll(good));
        assertEquals(2L, count(db, "weight_measurement"));

        List<List<String>> before = weightRows(db);
        WeightValidator.Import badPet = WeightValidator.parseImport(Map.of("baseRevision", 1L, "items", List.of(
            importItem("i3", MILO, "2026-01-03", 100, "rec-1"), importItem("i4", "ghost", "2026-01-04", 100, "rec-2"))));
        assertEquals("PET_NOT_FOUND", assertThrows(ValidationException.class, () -> weights.importAll(badPet)).code());
        WeightValidator.Import again = WeightValidator.parseImport(Map.of("baseRevision", 1L, "items", List.of(
            importItem("i5", MILO, "2026-01-05", 100, "rec-4"), importItem("i6", MILO, "2026-01-06", 100, "rec-12"))));   // rec-12 already imported
        assertEquals("DUPLICATE_WEIGHT", assertThrows(ValidationException.class, () -> weights.importAll(again)).code());
        WeightValidator.Import sameId = WeightValidator.parseImport(Map.of("baseRevision", 1L, "items", List.of(importItem("i1", MILO, "2026-01-07", 100, null))));
        assertEquals("DUPLICATE_WEIGHT", assertThrows(ValidationException.class, () -> weights.importAll(sameId)).code());
        assertEquals(before, weightRows(db));
        assertEquals(1L, revision());
    }

    @Test
    void importLeavesTheOriginalCareRecordsAndProfileWeightsAlone() throws Exception {
        setUp();
        Map<String, List<List<String>>> before = dump(db);
        weights.importAll(WeightValidator.parseImport(Map.of("baseRevision", 0L, "items", List.of(importItem("i1", MILO, "2026-01-01", 29000, "rec-12")))));
        Map<String, List<List<String>>> after = dump(db);
        assertEquals(before.get("pet"), after.get("pet"));
        assertEquals(before.get("care_record"), after.get("care_record"));
    }

    @Test
    void aFailureInsideTheTransactionLeavesNoPartialChanges() throws Exception {
        setUp();
        exec(db, "CREATE TRIGGER boom BEFORE INSERT ON weight_measurement WHEN NEW.grams = 777 BEGIN SELECT RAISE(ABORT, 'injected'); END");
        WeightValidator.Import imp = WeightValidator.parseImport(Map.of("baseRevision", 0L, "items", List.of(
            importItem("i1", MILO, "2026-01-01", 100, null), importItem("i2", MILO, "2026-01-02", 777, null))));
        assertThrows(java.sql.SQLException.class, () -> weights.importAll(imp));
        assertEquals(0L, count(db, "weight_measurement"));
        assertEquals(0L, revision());
    }

    @Test
    void aPetCannotHaveMoreThanTheLimit() throws Exception {
        setUp();
        try (java.sql.Connection c = db.connect(); java.sql.Statement s = c.createStatement()) {
            s.execute("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < " + WeightRepository.MAX_PER_PET + ") "
                + "INSERT INTO weight_measurement(id,pet_id,measured_on,grams) SELECT 'bulk'||i, '" + MILO + "', '2026-01-01', 100 FROM n");
        }
        assertThrows(ValidationException.class, () -> weights.save(change(0, "one-too-many", MILO, "2026-01-02", 100)));
        weights.save(change(0, "other-pet", LUNA, "2026-01-02", 100));
    }

    // ---- weights are protected from unrelated saves ------------------------------------------------

    @Test
    void ordinaryPetAndRecordEditsNeverTouchMeasurements() throws Exception {
        setUp();
        weights.save(change(0, "w1", MILO, "2026-01-05", 29500));
        weights.save(change(1, "w2", LUNA, "2026-01-06", 4200));
        List<List<String>> rows = weightRows(db);

        @SuppressWarnings("unchecked") Map<String, Object> snap = state.readState();
        @SuppressWarnings("unchecked") List<Map<String, Object>> pets = (List<Map<String, Object>>) snap.get("pets");
        @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) snap.get("records");
        pets.get(0).put("name", "Milo II"); pets.get(0).put("weight", "31.0");                    // profile edit incl. the original weight field
        records.get(0).put("title", "edited"); records.remove(1);                                  // record edit and delete
        String gone = (String) pets.remove(3).get("id");                                          // a pet without measurements is removed
        records.removeIf(rec -> rec.get("petId").equals(gone));
        StateRepository.SaveResult r = state.save(input(2, snap));
        assertTrue(r.updated() > 0 && r.deleted() > 0);
        assertEquals(rows, weightRows(db));
        assertEquals("31.0", pets.get(0).get("weight"));
    }

    @Test
    void aSnapshotThatSimplyOmitsWeightsDoesNotDeleteThem() throws Exception {
        setUp();
        weights.save(change(0, "w1", MILO, "2026-01-05", 29500));
        Map<String, Object> snap = new LinkedHashMap<>(state.readState());
        snap.remove("weights");
        state.save(input(1, snap));                                                                // nothing changed
        assertEquals(1L, count(db, "weight_measurement"));
        assertEquals(1L, revision());
    }

    @Test
    void removingAPetThatHasMeasurementsIsRefusedUnlessConfirmed() throws Exception {
        setUp();
        weights.save(change(0, "w1", MILO, "2026-01-05", 29500));
        weights.save(change(1, "w2", LUNA, "2026-01-05", 4200));
        Map<String, List<List<String>>> before = dump(db);
        List<List<String>> rows = weightRows(db);

        @SuppressWarnings("unchecked") Map<String, Object> snap = state.readState();
        @SuppressWarnings("unchecked") List<Map<String, Object>> pets = (List<Map<String, Object>>) snap.get("pets");
        @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) snap.get("records");
        pets.removeIf(p -> p.get("id").equals(MILO));
        records.removeIf(rec -> rec.get("petId").equals(MILO));
        ValidationException e = assertThrows(ValidationException.class, () -> state.save(input(2, snap)));
        assertEquals(409, e.status());
        assertEquals("PET_HAS_WEIGHTS", e.code());
        assertEquals(before, dump(db));                                                           // pet, records and revision untouched
        assertEquals(rows, weightRows(db));

        Map<String, Object> confirmed = new LinkedHashMap<>(snap);
        confirmed.put("baseRevision", 2L); confirmed.remove("revision"); confirmed.put("removeWeightsOfDeletedPets", true);
        StateRepository.SaveResult r = state.save(StateValidator.parse(confirmed));
        assertEquals(3L, r.revision());
        assertEquals(List.of(rows.get(1)), weightRows(db));                                       // only Milo's measurement went; Luna's stays
        assertEquals(3L, count(db, "pet"));
        assertTrue(foreignKeyCheckClean(db));
    }

    @Test
    void theConfirmationFlagDoesNotDeleteAnythingByItself() throws Exception {
        setUp();
        weights.save(change(0, "w1", MILO, "2026-01-05", 29500));
        Map<String, Object> snap = new LinkedHashMap<>(state.readState());
        snap.put("baseRevision", 1L); snap.remove("revision"); snap.put("removeWeightsOfDeletedPets", true);
        StateRepository.SaveResult r = state.save(StateValidator.parse(snap));
        assertEquals(0, r.deleted());
        assertEquals(1L, count(db, "weight_measurement"));
        assertEquals(1L, r.revision());
        assertThrows(ValidationException.class, () -> StateValidator.parse(Map.of("baseRevision", 0L, "pets", List.of(), "records", List.of(), "removeWeightsOfDeletedPets", "yes")));
    }

    @Test
    void databaseRestrictIsTheLastLineOfDefence() throws Exception {
        setUp();
        weights.save(change(0, "w1", MILO, "2026-01-05", 29500));
        assertThrows(java.sql.SQLException.class, () -> exec(db, "DELETE FROM pet WHERE id = '" + MILO + "'"));
        assertEquals(1L, count(db, "weight_measurement"));
    }
}
