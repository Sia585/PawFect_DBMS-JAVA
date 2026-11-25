package com.pawfect;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.pawfect.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** The one-time demo seeder, always against disposable databases. */
class DemoDataSeederTest {
    @TempDir Path tmp;
    private HttpServer server;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

    @AfterEach
    void shutDown() { stop(server); }

    private static long revisionOf(Database d) throws SQLException {
        try (Connection c = d.connect()) { return StateRepository.revision(c); }
    }

    private static List<String> strings(Database d, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = d.connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) out.add(r.getString(1));
        }
        return out;
    }

    private static String category(String due, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, LocalDate.parse(due));      // same rules as the app: past = overdue, within 7 days = soon
        return days < 0 ? "overdue" : days <= 7 ? "soon" : "upcoming";
    }

    @Test
    void seedsAFreshDatabaseFileThatDoesNotExistYet() throws Exception {
        Database d = db(tmp);
        assertFalse(Files.exists(d.file()));
        DemoDataSeeder.Result r = DemoDataSeeder.seed(d, TODAY, null);
        assertEquals(3, r.pets());
        assertEquals(1, r.revision());
        assertEquals(3, count(d, "pet"));
        assertEquals(r.records(), count(d, "care_record"));
        assertEquals(r.weights(), count(d, "weight_measurement"));
        assertEquals(1L, revisionOf(d));
        assertEquals(3, userVersion(d));                                       // schema untouched: the normal latest version
        assertEquals("ok", pragmaFirst(d, "integrity_check"));
        assertTrue(foreignKeyCheckClean(d));
        assertEquals(List.of("Milo", "Luna", "Mochi"), strings(d, "SELECT name FROM pet ORDER BY rowid"));
        assertEquals(List.of("Dog|Golden Retriever|Male", "Cat|Domestic Shorthair|Female", "Rabbit|Netherland Dwarf|Male"),
            strings(d, "SELECT species||'|'||breed||'|'||sex FROM pet ORDER BY rowid"));
        assertEquals(List.of("1"), strings(d, "SELECT DISTINCT owner_id FROM pet"));
        assertEquals(List.of(), strings(d, "SELECT id FROM pet WHERE photo_file IS NOT NULL"));
    }

    @Test
    void everyPetHasTheRequiredVarietyOfRecordsReminderGroupsAndWeights() throws Exception {
        Database d = db(tmp);
        DemoDataSeeder.seed(d, TODAY, null);
        for (String pet : List.of("demo-milo", "demo-luna", "demo-mochi")) {
            List<String> types = strings(d, "SELECT DISTINCT type FROM care_record WHERE pet_id='" + pet + "'");
            assertTrue(types.size() >= 5, pet + " types " + types);
            assertTrue(types.containsAll(List.of("Vaccination", "Vet visit", "Grooming", "Feeding")), pet + " " + types);
            Set<String> groups = new HashSet<>();
            for (String due : strings(d, "SELECT due_date FROM care_record WHERE pet_id='" + pet + "' AND due_date <> ''")) groups.add(category(due, TODAY));
            assertTrue(groups.containsAll(Set.of("overdue", "soon", "upcoming")), pet + " reminder groups " + groups);
            assertTrue(strings(d, "SELECT record_date FROM care_record WHERE pet_id='" + pet + "' AND record_date <> '' AND record_date < '" + TODAY + "'").size() >= 5, pet + " history");
            List<String> dates = strings(d, "SELECT DISTINCT measured_on FROM weight_measurement WHERE pet_id='" + pet + "'");
            assertTrue(dates.size() >= 3, pet + " weight dates " + dates);
            assertEquals(count(d, "weight_measurement") > 0, true);
            assertTrue(strings(d, "SELECT id FROM care_record WHERE pet_id='" + pet + "' AND type='Weight'").size() >= 3, pet + " weight notes");
        }
        assertTrue(strings(d, "SELECT record_date FROM care_record WHERE record_date > '" + TODAY + "'").isEmpty(), "no record dated in the future");
        assertTrue(strings(d, "SELECT measured_on FROM weight_measurement WHERE measured_on > '" + TODAY + "'").isEmpty());
        assertTrue(strings(d, "SELECT birthdate FROM pet WHERE birthdate >= '" + TODAY + "'").isEmpty());
    }

    @Test
    void datesAreRelativeToTheDayItIsRun() throws Exception {
        Database a = db(tmp), b = new Database(tmp.resolve("other.db"));
        DemoDataSeeder.seed(a, TODAY, null);
        DemoDataSeeder.seed(b, TODAY.plusDays(400), null);
        assertEquals(strings(a, "SELECT id FROM care_record ORDER BY rowid"), strings(b, "SELECT id FROM care_record ORDER BY rowid"));
        for (String due : strings(b, "SELECT due_date FROM care_record WHERE due_date <> ''")) category(due, TODAY.plusDays(400));
        assertNotEquals(strings(a, "SELECT due_date FROM care_record ORDER BY rowid"), strings(b, "SELECT due_date FROM care_record ORDER BY rowid"));
        for (String pet : List.of("demo-milo", "demo-luna", "demo-mochi")) {
            Set<String> groups = new HashSet<>();
            for (String due : strings(b, "SELECT due_date FROM care_record WHERE pet_id='" + pet + "' AND due_date <> ''")) groups.add(category(due, TODAY.plusDays(400)));
            assertEquals(Set.of("overdue", "soon", "upcoming"), groups);
        }
    }

    @Test
    void weightNotesIncludeClearAndAmbiguousExamplesForTheImportReview() throws Exception {
        Database d = db(tmp);
        DemoDataSeeder.seed(d, TODAY, null);
        List<String> text = strings(d, "SELECT title || ' — ' || notes FROM care_record WHERE type='Weight' AND pet_id='demo-milo'");
        assertTrue(text.stream().anyMatch(t -> t.contains("27.6 kg")));          // clear, kg
        assertTrue(text.stream().anyMatch(t -> t.contains("61 lb")));            // clear, lb
        assertTrue(text.stream().anyMatch(t -> t.contains("28,6 kg")));          // decimal comma: needs review
        assertTrue(text.stream().anyMatch(t -> t.contains("28.4 kg") && t.contains("28.9 kg")));   // two values: needs review
        assertTrue(text.stream().anyMatch(t -> t.contains("Weighed 28 ")));      // no unit: needs review
        assertEquals(List.of(), strings(d, "SELECT id FROM weight_measurement WHERE source_record_id IS NOT NULL"));
    }

    @Test
    void theSeededDatabaseWorksWithTheRealServerAndSurvivesAnOrdinarySave() throws Exception {
        Database d = db(tmp);
        DemoDataSeeder.seed(d, TODAY, null);
        server = serve(d);
        int port = port(server);
        Response state = get(port, "/api/state");
        assertEquals(200, state.status());
        Map<String, Object> json = state.json();
        assertEquals(1, json.get("revision"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> pets = (List<Map<String, Object>>) json.get("pets");
        @SuppressWarnings("unchecked") List<Map<String, Object>> recs = (List<Map<String, Object>>) json.get("records");
        @SuppressWarnings("unchecked") List<Map<String, Object>> weights = (List<Map<String, Object>>) json.get("weights");
        assertEquals(3, pets.size());
        assertTrue(weights.size() >= 9);
        // the page would send exactly this back on any save: every seeded value must pass the server's validation
        Response saved = put(port, body(1, pets, recs));
        assertEquals(200, saved.status(), saved.body());
        assertEquals(count(d, "weight_measurement"), weights.size());
        assertEquals(3, count(d, "pet"));
    }

    @Test
    void anExistingDatabaseWithAnyDataIsLeftCompletelyAlone() throws Exception {
        for (String sql : List.of(
                "INSERT INTO pet(id,name,species) VALUES ('x','X','Dog')",
                "INSERT INTO pet(id,name,species) VALUES ('x','X','Dog')|INSERT INTO care_record(id,pet_id,type,title) VALUES ('c','x','Other','t')",
                "INSERT INTO pet(id,name,species) VALUES ('x','X','Dog')|INSERT INTO weight_measurement(id,pet_id,measured_on,grams) VALUES ('w','x','2026-01-01',1000)")) {
            Path dir = Files.createTempDirectory(tmp, "case");
            Database d = new Database(dir.resolve("pawfect.db"));
            d.initialize();
            new Migrator(d, Migrations.all(), new BackupService()).migrate();
            for (String s : sql.split("\\|")) exec(d, s);
            Map<String, List<List<String>>> before = dump(d);
            List<List<String>> weights = weightRows(d);
            long rev = revisionOf(d);
            assertThrows(DemoDataSeeder.NotEmptyException.class, () -> DemoDataSeeder.seed(d, TODAY, null));
            assertEquals(before, dump(d));
            assertEquals(weights, weightRows(d));
            assertEquals(rev, revisionOf(d));
        }
    }

    @Test
    void anOlderDatabaseWithDataIsNotEvenMigrated() throws Exception {
        Database d = db(tmp);
        d.initialize();                                                        // baseline v0 schema only
        populate0(d);
        Map<String, List<List<String>>> before = dump(d);
        assertThrows(DemoDataSeeder.NotEmptyException.class, () -> DemoDataSeeder.seed(d, TODAY, null));
        assertEquals(0, userVersion(d));
        assertEquals(before, dump(d));
        try (var files = Files.list(tmp)) { assertEquals(0L, files.filter(p -> p.getFileName().toString().contains("backup")).count()); }
    }

    private static void populate0(Database d) throws SQLException {
        exec(d, "INSERT INTO pet(id,name,species) VALUES ('old','Old','Cat')");
    }

    @Test
    void runningTwiceTheSecondRunAbortsAndChangesNothing() throws Exception {
        Database d = db(tmp);
        DemoDataSeeder.seed(d, TODAY, null);
        Map<String, List<List<String>>> before = dump(d);
        List<List<String>> weights = weightRows(d);
        assertThrows(DemoDataSeeder.NotEmptyException.class, () -> DemoDataSeeder.seed(d, TODAY.plusDays(3), null));
        assertEquals(before, dump(d));
        assertEquals(weights, weightRows(d));
        assertEquals(1L, revisionOf(d));
    }

    @Test
    void anEmptyButAlreadyPreparedDatabaseIsSeededAndTheRevisionMovesByOne() throws Exception {
        Database d = migrated(tmp);
        assertEquals(0L, revisionOf(d));
        DemoDataSeeder.seed(d, TODAY, null);
        assertEquals(1L, revisionOf(d));
        assertEquals(3, userVersion(d));
    }

    @Test
    void aFailureAtTheEndRollsBackEveryInsertAndTheRevision() throws Exception {
        Database d = db(tmp);
        assertThrows(SQLException.class, () -> DemoDataSeeder.seed(d, TODAY, c -> { throw new SQLException("injected failure"); }));
        assertEquals(0, count(d, "pet"));
        assertEquals(0, count(d, "care_record"));
        assertEquals(0, count(d, "weight_measurement"));
        assertEquals(0L, revisionOf(d));
        // and the database is still usable: a normal run afterwards succeeds
        assertEquals(3, DemoDataSeeder.seed(d, TODAY, null).pets());
    }

    @Test
    void aMidwayConstraintFailureAlsoRollsBack() throws Exception {
        Database d = db(tmp);
        d.initialize();
        new Migrator(d, Migrations.all(), new BackupService()).migrate();
        exec(d, "CREATE TRIGGER stop_weights BEFORE INSERT ON weight_measurement BEGIN SELECT RAISE(ABORT, 'blocked'); END");
        assertThrows(SQLException.class, () -> DemoDataSeeder.seed(d, TODAY, null));
        assertEquals(0, count(d, "pet"));
        assertEquals(0, count(d, "care_record"));
        assertEquals(0L, revisionOf(d));
    }

    @Test
    void theSeederIsNotWiredIntoTheServerStartup() throws Exception {
        Database d = db(tmp);
        server = serve(d);                                                     // normal start on a brand new database
        assertEquals(0, count(d, "pet"));
        assertEquals(0, count(d, "care_record"));
        assertEquals(0, count(d, "weight_measurement"));
    }
}
