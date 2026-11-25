package com.pawfect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.pawfect.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class StateRepositoryTest {
    @TempDir Path tmp;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> state, String key) {
        return (List<Map<String, Object>>) state.get(key);
    }

    private static long revisionOf(Map<String, Object> state) {
        return ((Number) state.get("revision")).longValue();
    }

    @Test
    void freshStateIsEmptyAtRevisionZero() throws Exception {
        StateRepository repo = new StateRepository(migrated(tmp));
        Map<String, Object> s = repo.readState();
        assertEquals(0L, revisionOf(s));
        assertEquals(0, list(s, "pets").size());
        assertEquals(0, list(s, "records").size());
    }

    @Test
    void createEditAndDeletePetsAndRecords() throws Exception {
        Database d = migrated(tmp);
        StateRepository repo = new StateRepository(d);

        Map<String, Object> state = repo.readState();
        list(state, "pets").add(pet("p1", "Milo", "Dog"));
        list(state, "records").add(record("r1", "p1", "Rabies shot"));
        StateRepository.SaveResult created = repo.save(input(0, state));
        assertEquals(1L, created.revision());
        assertEquals(2, created.inserted());

        state = repo.readState();
        list(state, "pets").get(0).put("name", "Milo II");
        StateRepository.SaveResult edited = repo.save(input(1, state));
        assertEquals(2L, edited.revision());
        assertEquals(1, edited.updated());
        assertEquals("Milo II", list(repo.readState(), "pets").get(0).get("name"));

        state = repo.readState();
        list(state, "records").remove(0);
        StateRepository.SaveResult recordGone = repo.save(input(2, state));
        assertEquals(1, recordGone.deleted());
        assertEquals(1, list(repo.readState(), "pets").size());

        state = repo.readState();
        list(state, "pets").clear();
        StateRepository.SaveResult petGone = repo.save(input(3, state));
        assertEquals(1, petGone.deleted());
        assertEquals(4L, petGone.revision());
        assertEquals(0, list(repo.readState(), "pets").size());
    }

    @Test
    void savingIdenticalDataChangesNothingAndKeepsTheRevision() throws Exception {
        Database d = migrated(tmp);
        populate(d);
        StateRepository repo = new StateRepository(d);
        Map<String, List<List<String>>> before = dump(d);
        StateRepository.SaveResult r = repo.save(input(0, repo.readState()));
        assertEquals(0L, r.revision());
        assertEquals(0, r.inserted() + r.updated() + r.deleted());
        assertEquals(before, dump(d));
    }

    @Test
    void anUnrelatedEditKeepsEveryIdRowAndTheOwner() throws Exception {
        Database d = migrated(tmp);
        populate(d);
        exec(d, "UPDATE owner SET display_name = 'Sunny' WHERE id = 1");
        StateRepository repo = new StateRepository(d);
        Map<String, List<List<String>>> before = dump(d);

        Map<String, Object> state = repo.readState();
        list(state, "pets").get(1).put("notes", "now allergic to nothing");
        StateRepository.SaveResult r = repo.save(input(0, state));
        assertEquals(1, r.updated());
        assertEquals(1L, r.revision());

        Map<String, List<List<String>>> after = dump(d);
        assertEquals(before.get("owner"), after.get("owner"));                 // owner row untouched
        assertEquals(before.get("care_record"), after.get("care_record"));     // same IDs and rowids, nothing re-created
        assertEquals(4, after.get("pet").size());
        for (int i = 0; i < 4; i++) {
            if (i == 1) continue;
            assertEquals(before.get("pet").get(i), after.get("pet").get(i));
        }
        assertEquals(before.get("pet").get(1).get(0), after.get("pet").get(1).get(0));   // same rowid
        assertEquals(before.get("pet").get(1).get(1), after.get("pet").get(1).get(1));   // same id
        assertEquals("1", after.get("pet").get(1).get(2));                                // owner_id still 1
        assertEquals("now allergic to nothing", after.get("pet").get(1).get(11));
    }

    @Test
    void dependentRowsSurviveUnrelatedEditsAndGoOnlyWithTheirOwnPet() throws Exception {
        Database d = migrated(tmp);
        populate(d);
        exec(d, "CREATE TABLE dep (id TEXT PRIMARY KEY, pet_id TEXT NOT NULL REFERENCES pet(id) ON DELETE CASCADE)");
        for (String p : List.of("pet-milo-001", "pet-luna-002", "pet-kiwi-003")) exec(d, "INSERT INTO dep VALUES ('dep-" + p + "', '" + p + "')");
        StateRepository repo = new StateRepository(d);

        Map<String, Object> state = repo.readState();
        list(state, "pets").get(3).put("name", "Biscuit Jr.");
        repo.save(input(0, state));
        assertEquals(3L, count(d, "dep"));                                      // the old delete-and-reinsert would have emptied this

        state = repo.readState();
        list(state, "pets").remove(1);                                          // intentionally remove Luna
        list(state, "records").removeIf(r -> "pet-luna-002".equals(r.get("petId")));
        repo.save(input(1, state));
        assertEquals(2L, count(d, "dep"));
        assertEquals(3L, count(d, "pet"));
        assertTrue(foreignKeyCheckClean(d));
    }

    @Test
    void aRestrictedDependentRowBlocksAccidentalDeletionAndRollsEverythingBack() throws Exception {
        Database d = migrated(tmp);
        populate(d);
        exec(d, "CREATE TABLE dep (id TEXT PRIMARY KEY, pet_id TEXT NOT NULL REFERENCES pet(id) ON DELETE RESTRICT)");
        exec(d, "INSERT INTO dep VALUES ('d1', 'pet-milo-001')");
        StateRepository repo = new StateRepository(d);
        Map<String, List<List<String>>> before = dump(d);

        Map<String, Object> state = repo.readState();
        list(state, "pets").get(1).put("name", "Changed in the same save");
        list(state, "records").removeIf(r -> "pet-milo-001".equals(r.get("petId")));
        list(state, "pets").remove(0);                                           // tries to delete Milo
        assertThrows(SQLException.class, () -> repo.save(input(0, state)));
        assertEquals(before, dump(d));                                           // nothing, including Luna's rename, was applied
        assertEquals(1L, count(d, "dep"));
    }

    @Test
    void aStaleRevisionIsRejectedAndTheDatabaseIsUnchanged() throws Exception {
        Database d = migrated(tmp);
        StateRepository repo = new StateRepository(d);
        Map<String, Object> state = repo.readState();
        list(state, "pets").add(pet("p1", "Milo", "Dog"));
        repo.save(input(0, state));
        Map<String, List<List<String>>> before = dump(d);

        Map<String, Object> stale = Map.of("pets", List.of(pet("p2", "Other tab", "Cat")), "records", List.of());
        ConflictException e = assertThrows(ConflictException.class, () -> repo.save(input(0, stale)));
        assertEquals(1L, e.currentRevision());
        assertEquals(before, dump(d));
    }

    @Test
    void aStaleTabCannotDeleteWhatAnotherTabCreated() throws Exception {
        Database d = migrated(tmp);
        StateRepository repo = new StateRepository(d);
        Map<String, Object> tabB = repo.readState();                              // tab B loads the empty database

        Map<String, Object> tabA = repo.readState();                              // tab A adds a pet and a record
        list(tabA, "pets").add(pet("pa", "Created in A", "Dog"));
        list(tabA, "records").add(record("ra", "pa", "Vet visit in A"));
        repo.save(input(0, tabA));

        list(tabB, "pets").add(pet("pb", "Created in B", "Cat"));                  // tab B saves with its old revision
        assertThrows(ConflictException.class, () -> repo.save(input(0, tabB)));

        Map<String, Object> now = repo.readState();
        assertEquals(1, list(now, "pets").size());
        assertEquals("pa", list(now, "pets").get(0).get("id"));
        assertEquals("ra", list(now, "records").get(0).get("id"));
    }

    @Test
    void aFailureHalfwayThroughRollsBackEverything() throws Exception {
        Database d = migrated(tmp);
        populate(d);
        exec(d, "CREATE TRIGGER boom BEFORE INSERT ON care_record WHEN NEW.title = 'BOOM' BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
        StateRepository repo = new StateRepository(d);
        Map<String, List<List<String>>> before = dump(d);

        Map<String, Object> state = repo.readState();
        list(state, "pets").get(0).put("name", "renamed before the failure");
        list(state, "pets").add(pet("new-pet", "Newcomer", "Fish"));
        list(state, "records").add(record("bad", "new-pet", "BOOM"));
        assertThrows(SQLException.class, () -> repo.save(input(0, state)));

        assertEquals(before, dump(d));                                           // pets, records AND revision all unchanged
        assertEquals(0L, revisionOf(repo.readState()));
    }

    @Test
    void aRecordCanMoveToAnotherPetAndBlankFieldsRoundTrip() throws Exception {
        Database d = migrated(tmp);
        populate(d);
        StateRepository repo = new StateRepository(d);
        Map<String, Object> state = repo.readState();
        list(state, "records").get(0).put("petId", "pet-luna-002");
        list(state, "pets").get(2).put("breed", "");
        repo.save(input(0, state));
        Map<String, Object> after = repo.readState();
        assertEquals("pet-luna-002", list(after, "records").get(0).get("petId"));
        assertEquals("rec-0", list(after, "records").get(0).get("id"));
        assertEquals("", list(after, "pets").get(2).get("breed"));
        assertEquals(15, list(after, "records").size());
    }

    @Test
    void readStateKeepsThePetOrderAcrossEdits() throws Exception {
        Database d = migrated(tmp);
        populate(d);
        StateRepository repo = new StateRepository(d);
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> p : list(repo.readState(), "pets")) ids.add(p.get("id"));
        Map<String, Object> state = repo.readState();
        list(state, "pets").get(0).put("vet", "New clinic");
        repo.save(input(0, state));
        List<Object> after = new ArrayList<>();
        for (Map<String, Object> p : list(repo.readState(), "pets")) after.add(p.get("id"));
        assertEquals(ids, after);
    }
}
