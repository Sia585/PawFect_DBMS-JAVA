package com.pawfect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

/**
 * One-time demonstration data for a fresh Pawfect database: three fictional pets (Milo, Luna, Mochi) with care
 * records, overdue / soon / upcoming reminders and structured weight history.
 *
 * This is a separate command-line tool. The server never calls it, and it refuses to touch a database that
 * already holds any pet, care record or weight measurement. Everything is inserted in one transaction.
 *
 * Run from the backend directory:  mvn compile exec:java@demo-data
 */
public final class DemoDataSeeder {
    /** Rows written by a successful run. */
    record Result(int pets, int records, int weights, long revision) {}

    /** The database already holds data; nothing was changed. */
    static final class NotEmptyException extends Exception {
        private static final long serialVersionUID = 1L;
        NotEmptyException(String message) { super(message); }
    }

    /** Test hook: runs after all inserts, before the commit. Throwing here must roll everything back. */
    interface BeforeCommit { void run(Connection c) throws SQLException; }

    private static final String[] TABLES = {"pet", "care_record", "weight_measurement"};

    private DemoDataSeeder() {}

    public static void main(String[] args) {
        Path file = args.length > 0 ? Path.of(args[0]) : Database.DEFAULT_FILE;
        try {
            Class.forName("org.sqlite.JDBC");
            Result r = seed(new Database(file), LocalDate.now(), null);
            System.out.println();
            System.out.println("Demo data added to " + file.toAbsolutePath());
            System.out.println("  " + r.pets() + " pets, " + r.records() + " care records, " + r.weights() + " weight measurements (revision " + r.revision() + ")");
            System.out.println("Start Pawfect as usual (mvn compile exec:java) and open http://localhost:8080");
            System.out.println("If Pawfect is already open in a browser, reload the page.");
        } catch (NotEmptyException e) {
            System.err.println();
            System.err.println("Nothing was changed. " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println();
            System.err.println("The demo data could not be added and nothing was changed: " + e.getMessage());
            System.exit(1);
        }
    }

    static Result seed(Database db, LocalDate today, BeforeCommit hook) throws Exception {
        // 1. Look before touching anything: an existing database with data (even an older schema) is left alone.
        if (Files.exists(db.file())) {
            String found = existingData(db);
            if (found != null) throw new NotEmptyException(found);
        }
        // 2. Same preparation the server does: baseline tables, then migrations (an empty database needs no backup).
        db.initialize();
        new Migrator(db, Migrations.all(), new BackupService()).migrate();

        try (Connection c = db.connect(); Statement tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                String found = existingData(c);                        // re-check inside the write lock
                if (found != null) throw new NotEmptyException(found);
                DemoData data = DemoData.build(today);
                insert(c, data);
                int invalid = foreignKeyViolations(c);
                if (invalid > 0) throw new SQLException("Foreign key check failed (" + invalid + " problems).");
                tx.executeUpdate("UPDATE app_state SET revision = revision + 1 WHERE id = 1");
                if (hook != null) hook.run(c);
                long revision = StateRepository.revision(c);
                tx.execute("COMMIT");
                return new Result(data.pets().size(), data.records().size(), data.weights().size(), revision);
            } catch (Throwable t) {
                try { tx.execute("ROLLBACK"); } catch (SQLException ignored) { }
                throw t;
            }
        }
    }

    /** A sentence describing what exists, or null when pet, care_record and weight_measurement are all empty or absent. */
    private static String existingData(Database db) throws SQLException {
        try (Connection c = db.connect()) { return existingData(c); }
    }

    private static String existingData(Connection c) throws SQLException {
        StringBuilder found = new StringBuilder();
        for (String t : TABLES) {
            boolean exists;
            try (PreparedStatement q = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
                q.setString(1, t);
                try (ResultSet r = q.executeQuery()) { exists = r.next(); }
            }
            if (!exists) continue;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + t)) {
                r.next();
                if (r.getLong(1) > 0) found.append(found.length() == 0 ? "" : ", ").append(r.getLong(1)).append(' ').append(t.replace('_', ' ')).append(" rows");
            }
        }
        return found.length() == 0 ? null : "The database already contains data (" + found + "). The demo seeder only runs on an empty database.";
    }

    private static void insert(Connection c, DemoData d) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("INSERT INTO pet(id,owner_id,name,species,breed,sex,birthdate,weight,vet,face,notes) VALUES(?,1,?,?,?,?,?,?,?,?,?)")) {
            for (DemoData.Pet p : d.pets()) {
                q.setString(1, p.id()); q.setString(2, p.name()); q.setString(3, p.species()); q.setString(4, p.breed()); q.setString(5, p.sex());
                q.setString(6, p.birthdate()); q.setString(7, p.weight()); q.setString(8, p.vet()); q.setString(9, p.face()); q.setString(10, p.notes());
                q.executeUpdate();
            }
        }
        try (PreparedStatement q = c.prepareStatement("INSERT INTO care_record(id,pet_id,type,title,record_date,due_date,notes) VALUES(?,?,?,?,?,?,?)")) {
            for (DemoData.Record r : d.records()) {
                q.setString(1, r.id()); q.setString(2, r.petId()); q.setString(3, r.type()); q.setString(4, r.title());
                q.setString(5, r.date()); q.setString(6, r.dueDate()); q.setString(7, r.notes());
                q.executeUpdate();
            }
        }
        try (PreparedStatement q = c.prepareStatement("INSERT INTO weight_measurement(id,pet_id,measured_on,grams,note,source_record_id) VALUES(?,?,?,?,?,NULL)")) {
            for (DemoData.Weight w : d.weights()) {
                q.setString(1, w.id()); q.setString(2, w.petId()); q.setString(3, w.date()); q.setInt(4, w.grams()); q.setString(5, w.note());
                q.executeUpdate();
            }
        }
    }

    private static int foreignKeyViolations(Connection c) throws SQLException {
        int n = 0;
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA foreign_key_check")) { while (r.next()) n++; }
        return n;
    }

    /** The fictional dataset, with every date computed from "today" so reminders always fall in the intended group. */
    record DemoData(List<Pet> pets, List<Record> records, List<Weight> weights) {
        record Pet(String id, String name, String species, String breed, String sex, String birthdate, String weight, String vet, String face, String notes) {}
        record Record(String id, String petId, String type, String title, String date, String dueDate, String notes) {}
        record Weight(String id, String petId, String date, int grams, String note) {}

        static DemoData build(LocalDate today) {
            java.util.ArrayList<Pet> pets = new java.util.ArrayList<>();
            java.util.ArrayList<Record> rec = new java.util.ArrayList<>();
            java.util.ArrayList<Weight> w = new java.util.ArrayList<>();
            java.util.function.IntFunction<String> ago = n -> today.minusDays(n).toString();
            java.util.function.IntFunction<String> ahead = n -> today.plusDays(n).toString();

            // ---- Milo: adult Golden Retriever, steady weight, active
            pets.add(new Pet("demo-milo", "Milo", "Dog", "Golden Retriever", "Male", today.minusYears(4).minusMonths(2).minusDays(9).toString(), "29.4",
                "Greenfield Animal Clinic, Dr. Anita Rao", "🐕",
                "Loves fetch and the beach. Sensitive stomach: no chicken-based treats. Microchipped. Nervous about the vet scale, so treats help."));
            String m = "demo-milo-";
            rec.add(new Record(m + "rec-01", "demo-milo", "Vaccination", "Rabies booster", ago.apply(360), ahead.apply(5), "Annual booster. Certificate kept in the vet folder."));
            rec.add(new Record(m + "rec-02", "demo-milo", "Vaccination", "DHPP annual vaccine", ago.apply(180), ahead.apply(185), "Combined distemper, hepatitis, parvovirus vaccine. No reaction."));
            rec.add(new Record(m + "rec-03", "demo-milo", "Medication", "Flea and tick tablet", ago.apply(42), ago.apply(12), "Monthly chewable. The latest dose is overdue, so buy a new pack."));
            rec.add(new Record(m + "rec-04", "demo-milo", "Vet visit", "Annual health check", ago.apply(150), ahead.apply(45), "Heart and joints fine. Dr. Rao suggested keeping weight under 30 kg."));
            rec.add(new Record(m + "rec-05", "demo-milo", "Grooming", "Bath and coat trim", ago.apply(21), ahead.apply(9), "Deshedding brush session included."));
            rec.add(new Record(m + "rec-06", "demo-milo", "Feeding", "Switched to salmon kibble", ago.apply(75), "", "Changed gradually over 10 days. Two meals a day, about 340 g in total."));
            rec.add(new Record(m + "rec-07", "demo-milo", "Medication", "Deworming tablet", ago.apply(95), ahead.apply(60), "Every 3 months."));
            rec.add(new Record(m + "rec-08", "demo-milo", "Other", "Microchip details checked", ago.apply(300), "", "Phone number on the chip registry confirmed."));
            // old Weight notes: clear and ambiguous, for the import review
            rec.add(new Record(m + "rec-09", "demo-milo", "Weight", "Weigh-in 27.6 kg", ago.apply(330), "", "Clinic scale after the morning walk."));
            rec.add(new Record(m + "rec-10", "demo-milo", "Weight", "Weight check", ago.apply(270), "", "Home scale: 61 lb"));
            rec.add(new Record(m + "rec-11", "demo-milo", "Weight", "Weighed 28", ago.apply(240), "", "Forgot to note the unit."));
            rec.add(new Record(m + "rec-12", "demo-milo", "Weight", "Weight check", ago.apply(210), "", "Scale said 28.4 kg first, then 28.9 kg when he moved"));
            rec.add(new Record(m + "rec-13", "demo-milo", "Weight", "Weight", ago.apply(200), "", "28,6 kg"));
            int[] mg = {28300, 28750, 29100, 29350, 29600, 29400};
            int[] md = {170, 130, 90, 55, 25, 3};
            for (int i = 0; i < mg.length; i++) w.add(new Weight(m + "w-0" + (i + 1), "demo-milo", ago.apply(md[i]), mg[i], i == 3 ? "After the switch to salmon kibble" : i == 5 ? "Morning, before breakfast" : ""));

            // ---- Luna: indoor Domestic Shorthair, slender
            pets.add(new Pet("demo-luna", "Luna", "Cat", "Domestic Shorthair", "Female", today.minusYears(2).minusMonths(8).minusDays(17).toString(), "4.1",
                "Lakeside Cat Clinic, Dr. Imran Qureshi", "🐈",
                "Indoor cat who loves window perches and the laser pointer. Spayed. Prefers wet food in the evening and hides when visitors come."));
            String l = "demo-luna-";
            rec.add(new Record(l + "rec-01", "demo-luna", "Vaccination", "FVRCP booster", ago.apply(340), ahead.apply(25), "Core vaccine for feline herpesvirus, calicivirus and panleukopenia."));
            rec.add(new Record(l + "rec-02", "demo-luna", "Vaccination", "Rabies vaccine", ago.apply(200), ahead.apply(165), "Three-year vaccine."));
            rec.add(new Record(l + "rec-03", "demo-luna", "Vet visit", "Dental check", ago.apply(380), ago.apply(20), "Mild tartar on the back teeth. Recheck was due, so book a follow-up."));
            rec.add(new Record(l + "rec-04", "demo-luna", "Grooming", "Nail trim", ago.apply(18), ahead.apply(3), "Trim every three weeks. She tolerates one paw at a time."));
            rec.add(new Record(l + "rec-05", "demo-luna", "Medication", "Spot-on flea treatment", ago.apply(28), ahead.apply(2), "Monthly. Apply between the shoulder blades."));
            rec.add(new Record(l + "rec-06", "demo-luna", "Feeding", "Started a urinary-care diet", ago.apply(60), "", "Vet-recommended wet food. Water fountain added to encourage drinking."));
            rec.add(new Record(l + "rec-07", "demo-luna", "Vet visit", "Annual wellness exam", ago.apply(120), ahead.apply(70), "Healthy. Weight steady."));
            rec.add(new Record(l + "rec-08", "demo-luna", "Weight", "Weight 4.1 kg", ago.apply(300), "", "Vet scale."));
            rec.add(new Record(l + "rec-09", "demo-luna", "Weight", "Weighed Luna", ago.apply(250), "", "About 4.3, I think"));
            rec.add(new Record(l + "rec-10", "demo-luna", "Weight", "Weight check", ago.apply(225), "", "9.2 lb on the kitchen scale"));
            int[] lg = {4050, 4150, 4200, 4100};
            int[] ld = {150, 100, 50, 7};
            for (int i = 0; i < lg.length; i++) w.add(new Weight(l + "w-0" + (i + 1), "demo-luna", ago.apply(ld[i]), lg[i], i == 3 ? "Steady after the diet change" : ""));

            // ---- Mochi: young Netherland Dwarf rabbit, grams matter
            pets.add(new Pet("demo-mochi", "Mochi", "Rabbit", "Netherland Dwarf", "Male", today.minusYears(1).minusMonths(5).minusDays(3).toString(), "1.1",
                "Sunny Paws Exotic Pet Clinic, Dr. Meera Nair", "🐇",
                "Neutered. Lives in an indoor pen with daily floor time. Eats timothy hay, a little fresh greens and a few pellets. Loves bananas but only as a rare treat."));
            String o = "demo-mochi-";
            rec.add(new Record(o + "rec-01", "demo-mochi", "Vaccination", "Myxomatosis and RHDV2 vaccine", ago.apply(327), ahead.apply(38), "Yearly vaccine. Book ahead because exotic clinic slots fill quickly."));
            rec.add(new Record(o + "rec-02", "demo-mochi", "Grooming", "Nail trim", ago.apply(36), ago.apply(6), "Nails are growing long again, so trim them soon."));
            rec.add(new Record(o + "rec-03", "demo-mochi", "Vet visit", "Neutering check-up", ago.apply(200), "", "Healed well. Dr. Nair advised weighing every two weeks."));
            rec.add(new Record(o + "rec-04", "demo-mochi", "Vet visit", "Dental and teeth alignment check", ago.apply(70), ahead.apply(110), "Teeth wearing evenly."));
            rec.add(new Record(o + "rec-05", "demo-mochi", "Feeding", "Hay and pellet plan", ago.apply(100), "", "Unlimited timothy hay, a small bowl of pellets and a handful of leafy greens each day."));
            rec.add(new Record(o + "rec-06", "demo-mochi", "Medication", "Parasite spot-on (ear mites)", ago.apply(55), "", "One treatment, no mites found at the recheck."));
            rec.add(new Record(o + "rec-07", "demo-mochi", "Other", "Fortnightly weigh-in reminder", ago.apply(14), ahead.apply(2), "Weigh on the kitchen scale before the morning feed."));
            rec.add(new Record(o + "rec-08", "demo-mochi", "Weight", "Weight 1050 g", ago.apply(190), "", "At the clinic."));
            rec.add(new Record(o + "rec-09", "demo-mochi", "Weight", "Weigh-in", ago.apply(160), "", "1.1"));
            rec.add(new Record(o + "rec-10", "demo-mochi", "Weight", "Weight check", ago.apply(145), "", "1,12 kg"));
            int[] og = {1060, 1085, 1100, 1120, 1105};
            int[] od = {120, 90, 60, 30, 4};
            for (int i = 0; i < og.length; i++) w.add(new Weight(o + "w-0" + (i + 1), "demo-mochi", ago.apply(od[i]), og[i], i == 4 ? "Before the morning feed" : ""));

            return new DemoData(List.copyOf(pets), List.copyOf(rec), List.copyOf(w));
        }
    }
}
