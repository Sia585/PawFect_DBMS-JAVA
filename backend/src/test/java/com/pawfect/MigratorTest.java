package com.pawfect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.pawfect.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class MigratorTest {
    @TempDir Path tmp;

    private static Migrator migrator(Database d, BackupService b, Migration... extra) {
        java.util.List<Migration> all = new java.util.ArrayList<>(Migrations.all());
        all.addAll(List.of(extra));
        return new Migrator(d, all, b);
    }

    private static long backupFiles(Path dir) throws Exception {
        Path backups = dir.resolve("backups");
        if (!Files.exists(backups)) return 0;
        try (Stream<Path> s = Files.list(backups)) { return s.count(); }
    }

    /** A migration that changes the schema and then fails, to prove nothing is left behind. */
    private static final class Failing implements Migration {
        private final int version;
        Failing(int version) { this.version = version; }
        @Override public int version() { return version; }
        @Override public String description() { return "injected failure"; }
        @Override public void apply(Connection c) throws SQLException {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE half_done (x TEXT)");
                s.execute("INSERT INTO half_done VALUES ('partial')");
            }
            throw new SQLException("injected failure");
        }
    }

    @Test
    void freshDatabaseMigratesToV1WithoutBackup() throws Exception {
        Database d = db(tmp);
        d.initialize();
        assertEquals(0, userVersion(d));
        Migrator.Result r = migrator(d, new BackupService()).migrate();
        assertEquals(0, r.fromVersion());
        assertEquals(1, r.toVersion());
        assertNull(r.backup());
        assertEquals(1, userVersion(d));
        assertEquals(List.of(List.of("1", "1", "0")), dump(d).get("app_state"));
        assertEquals(1L, count(d, "owner"));
        assertEquals("ok", pragmaFirst(d, "integrity_check"));
        assertTrue(foreignKeyCheckClean(d));
        assertEquals(0L, backupFiles(tmp));
    }

    @Test
    void migrationAddsOnlyTheAppStateTable() throws Exception {
        Database d = migrated(tmp);
        assertTrue(tableExists(d, "owner") && tableExists(d, "pet") && tableExists(d, "care_record") && tableExists(d, "app_state"));
        assertFalse(tableExists(d, "weight_measurement"));
    }

    @Test
    void existingDataSurvivesMigrationAndIsBackedUpAndVerified() throws Exception {
        Database d = db(tmp);
        d.initialize();
        populate(d);
        Map<String, List<List<String>>> before = dump(d);
        Migrator.Result r = migrator(d, new BackupService()).migrate();

        Map<String, List<List<String>>> after = dump(d);
        for (String t : List.of("owner", "pet", "care_record")) assertEquals(before.get(t), after.get(t));
        assertEquals(4L, count(d, "pet"));
        assertEquals(15L, count(d, "care_record"));

        assertNotNull(r.backup());
        assertTrue(Files.exists(r.backup()));
        Database backup = new Database(r.backup());
        assertEquals("ok", pragmaFirst(backup, "integrity_check"));
        assertEquals(0, userVersion(backup));
        assertEquals(4L, count(backup, "pet"));
        assertEquals(15L, count(backup, "care_record"));
        assertFalse(tableExists(backup, "app_state"));
        assertTrue(foreignKeyCheckClean(d));
        assertEquals("ok", pragmaFirst(d, "integrity_check"));
    }

    @Test
    void repeatedMigrationIsANoOp() throws Exception {
        Database d = db(tmp);
        d.initialize();
        populate(d);
        migrator(d, new BackupService()).migrate();
        Map<String, List<List<String>>> afterFirst = dump(d);
        Migrator.Result second = migrator(d, new BackupService()).migrate();
        assertEquals(1, second.fromVersion());
        assertEquals(1, second.toVersion());
        assertNull(second.backup());
        assertEquals(afterFirst, dump(d));
        assertEquals(1L, backupFiles(tmp));
        assertEquals(1, userVersion(d));
    }

    @Test
    void walDatabaseWithUncheckpointedDataIsBackedUpCompletely() throws Exception {
        Database d = db(tmp);
        d.initialize();
        assertEquals("wal", pragmaFirst(d, "journal_mode"));
        populate(d);
        try (Connection keeper = d.connect()) {          // an open connection keeps the WAL from being checkpointed away
            try (Statement s = keeper.createStatement()) {
                s.execute("INSERT INTO pet(id,name,species) VALUES ('wal-extra','Walter','Dog')");
            }
            Path wal = tmp.resolve("test.db-wal");
            assertTrue(Files.exists(wal) && Files.size(wal) > 0);
            Migrator.Result r = migrator(d, new BackupService()).migrate();
            assertNotNull(r.backup());
            Database backup = new Database(r.backup());
            assertEquals(5L, count(backup, "pet"));
            assertEquals("ok", pragmaFirst(backup, "integrity_check"));
        }
        assertEquals(5L, count(d, "pet"));
        assertEquals(1, userVersion(d));
        assertTrue(foreignKeyCheckClean(d));
    }

    @Test
    void injectedFailureRollsBackThatMigrationOnly() throws Exception {
        Database d = db(tmp);
        d.initialize();
        populate(d);
        Map<String, List<List<String>>> before = dump(d);
        MigrationException e = assertThrows(MigrationException.class, () -> migrator(d, new BackupService(), new Failing(2)).migrate());
        assertTrue(e.getMessage().contains("v2"));
        assertEquals(1, userVersion(d));                 // v1 committed, v2 rolled back
        assertFalse(tableExists(d, "half_done"));
        assertTrue(tableExists(d, "app_state"));
        for (String t : List.of("owner", "pet", "care_record")) assertEquals(before.get(t), dump(d).get(t));
        assertEquals(1L, backupFiles(tmp));              // the pre-migration backup was kept
        assertEquals("ok", pragmaFirst(d, "integrity_check"));
    }

    @Test
    void failureInTheFirstMigrationLeavesTheDatabaseAtVersionZero() throws Exception {
        Database d = db(tmp);
        d.initialize();
        Migration failingV1 = new Failing(1);
        assertThrows(MigrationException.class, () -> new Migrator(d, List.of(failingV1), new BackupService()).migrate());
        assertEquals(0, userVersion(d));
        assertFalse(tableExists(d, "half_done"));
        assertFalse(tableExists(d, "app_state"));
    }

    @Test
    void backupFailureAbortsBeforeAnythingChanges() throws Exception {
        Database d = db(tmp);
        d.initialize();
        populate(d);
        Map<String, List<List<String>>> before = dump(d);
        BackupService failing = new BackupService() {
            @Override protected void createCopy(Connection source, Path target) throws SQLException { throw new SQLException("disk full"); }
        };
        assertThrows(MigrationException.class, () -> migrator(d, failing).migrate());
        assertEquals(0, userVersion(d));
        assertFalse(tableExists(d, "app_state"));
        assertEquals(before, dump(d));
        assertEquals(0L, backupFiles(tmp));
    }

    @Test
    void corruptBackupFailsVerificationAndAbortsMigration() throws Exception {
        Database d = db(tmp);
        d.initialize();
        populate(d);
        BackupService corrupt = new BackupService() {
            @Override protected void createCopy(Connection source, Path target) throws SQLException {
                try { Files.writeString(target, "this is not a database"); } catch (java.io.IOException e) { throw new SQLException(e); }
            }
        };
        assertThrows(MigrationException.class, () -> migrator(d, corrupt).migrate());
        assertEquals(0, userVersion(d));
        assertFalse(tableExists(d, "app_state"));
        assertEquals(0L, backupFiles(tmp));              // the bad backup was removed
    }

    @Test
    void incompleteBackupIsDetectedByRowCounts() throws Exception {
        Database d = db(tmp);
        d.initialize();
        populate(d);
        BackupService truncated = new BackupService() {
            @Override protected void createCopy(Connection source, Path target) throws SQLException {
                super.createCopy(source, target);
                try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + target); Statement s = c.createStatement()) {
                    s.execute("DELETE FROM care_record WHERE id = 'rec-0'");
                }
            }
        };
        MigrationException e = assertThrows(MigrationException.class, () -> migrator(d, truncated).migrate());
        assertTrue(e.getMessage().contains("row counts"));
        assertEquals(0, userVersion(d));
        assertEquals(15L, count(d, "care_record"));
    }

    @Test
    void aDatabaseFromANewerVersionIsRefused() throws Exception {
        Database d = db(tmp);
        d.initialize();
        populate(d);
        exec(d, "PRAGMA user_version = 99");
        Map<String, List<List<String>>> before = dump(d);
        MigrationException e = assertThrows(MigrationException.class, () -> migrator(d, new BackupService()).migrate());
        assertTrue(e.getMessage().contains("99"));
        assertEquals(before, dump(d));
        assertEquals(99, userVersion(d));
    }

    @Test
    void migrationVersionsMustIncrease() {
        Database d = db(tmp);
        assertThrows(IllegalArgumentException.class, () -> new Migrator(d, List.of(new Failing(2), new Failing(2)), new BackupService()));
    }

    @Test
    void backupWorksWhenTheFolderNameContainsAQuote() throws Exception {
        Path odd = Files.createDirectories(tmp.resolve("O'Brien's pets"));
        Database d = db(odd);
        d.initialize();
        populate(d);
        Migrator.Result r = migrator(d, new BackupService()).migrate();
        assertNotNull(r.backup());
        assertEquals(15L, count(new Database(r.backup()), "care_record"));
    }

    /** Runs only when -Dpawfect.fixture=<path to the supplied synthetic pawfect.db> is given. The original is never touched. */
    @Test
    @EnabledIfSystemProperty(named = "pawfect.fixture", matches = ".+")
    void suppliedFixtureCopyMigratesAndKeepsAllRows() throws Exception {
        Path copy = tmp.resolve("test.db");
        Files.copy(Path.of(System.getProperty("pawfect.fixture")), copy);
        Database d = new Database(copy);
        d.initialize();
        long pets = count(d, "pet"), records = count(d, "care_record");
        Map<String, List<List<String>>> before = dump(d);
        Migrator.Result r = migrator(d, new BackupService()).migrate();
        assertEquals(pets, count(d, "pet"));
        assertEquals(records, count(d, "care_record"));
        for (String t : List.of("owner", "pet", "care_record")) assertEquals(before.get(t), dump(d).get(t));
        assertEquals(1, userVersion(d));
        assertEquals("ok", pragmaFirst(d, "integrity_check"));
        assertTrue(foreignKeyCheckClean(d));
        if (pets + records > 0) {
            assertNotNull(r.backup());
            assertEquals(pets, count(new Database(r.backup()), "pet"));
            assertEquals(records, count(new Database(r.backup()), "care_record"));
        }
    }
}
