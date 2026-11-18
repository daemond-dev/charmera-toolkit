package io.github.daemonddev.charmera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Plain-JVM tests for the write gate (WriteGate). MainActivity's races cannot run here, but each one leaves the gate
 * a state: a check made against one backup while another is on record, a backup still being read, a check still
 * running. Each test below is one of those states, named after the race that leads to it, and the gate must refuse
 * every one of them. Warn, don't block: a file that passed its checks with no backup on record (an unknown file
 * included) goes to the card; known bricks (a FAIL, a withdrawn candidate, a dump) never do. In the app's package,
 * because WriteGate and the Result constructor are package-private.
 */
public final class WriteGateTest {

    private static int failures = 0;
    private static int passes = 0;

    public static void main(String[] args) {
        byte[] data = new byte[UpdateCheck.UPDATE_SIZE];
        Arrays.fill(data, (byte) 0x5A);
        String name = "JH_5408202611050001_00000000.bin";
        String backupX = "11" + repeat('1', 62);
        String backupY = "22" + repeat('2', 62);
        UpdateCheck.Result checkedX = passed(name, data, backupX);
        UpdateCheck.Result checkedY = passed(name, data, backupY);
        UpdateCheck.Result unchecked = new UpdateCheck.Result(new ArrayList<>(), name, sha(data), data.length, false,
                null);

        expect("a file that passed against the backup on record may go to the card",
                WriteGate.refusal(checkedY, data, backupY, false, false) == null, null);

        // An unknown file (not reviewed) with no backup on record: written, with a loud warning on screen (not here).
        expect("an unknown file that passed, checked with no backup, goes to the card with no backup on record",
                WriteGate.refusal(unchecked, data, null, false, false) == null, null);
        expect("but once a backup is on record, the check made without one waits for the comparison with it",
                refused(unchecked, data, backupY, false, false, "checked against the backup"));
        expect("a check against a backup no longer on record waits for the check made with none",
                refused(checkedY, data, null, false, false, "checked against the backup"));
        List<UpdateCheck.Line> failAlone = new ArrayList<>();
        failAlone.add(new UpdateCheck.Line(UpdateCheck.Level.FAIL, "sum", "Sum", "wrong.", ""));
        UpdateCheck.Result failedAlone = new UpdateCheck.Result(failAlone, name, sha(data), data.length, false, null);
        expect("an unknown file that FAILs a check is refused with no backup on record",
                refused(failedAlone, data, null, false, false, "did not pass"));
        expect("an unknown file waits while a backup is being read",
                refused(unchecked, data, null, true, false, "still being read"));
        expect("an unknown file waits while it is being checked again",
                refused(unchecked, data, null, false, true, "being checked again"));
        byte[] changedAlone = data.clone();
        changedAlone[7] ^= 1;
        expect("an unknown file's bytes changed after its check are refused",
                refused(unchecked, changedAlone, null, false, false, "changed"));
        // A 2 MB dump is never an update file: its size line FAILs, so the gate refuses it with or without a backup.
        byte[] dumpLike = new byte[0x200000];
        UpdateCheck.Result dumpCheck = UpdateCheck.check(name, dumpLike, UpdateCheck.PUBLIC_BUILD, null);
        expect("a 2 MB dump is refused with no backup on record", !dumpCheck.ok
                && refused(dumpCheck, dumpLike, null, false, false, "did not pass"), dumpCheck.report());
        // The withdrawn candidates, by SHA-256, whatever their checks say and whatever backup is on record.
        for (Candidates.Kind k : new Candidates.Kind[]{Candidates.Kind.A, Candidates.Kind.B}) {
            UpdateCheck.Result w = new UpdateCheck.Result(new ArrayList<>(), k.fileName, k.sha256, data.length, false,
                    null);
            UpdateCheck.Result wc = new UpdateCheck.Result(new ArrayList<>(), k.fileName, k.cleanSha256, data.length,
                    true, backupY);
            expect("withdrawn candidate " + k + " is refused with no backup on record",
                    refused(w, data, null, false, false, "withdrawn"));
            expect("withdrawn candidate " + k + " (cleaned copy) is refused with a backup on record",
                    refused(wc, data, backupY, false, false, "withdrawn"));
        }
        expect("the stock restore file is never taken for a withdrawn candidate",
                Candidates.withdrawn(Candidates.Kind.RESTORE.sha256) == null
                        && Candidates.withdrawn(Candidates.Kind.RESTORE.cleanSha256) == null, null);

        // app-1: the file was picked, or the restore file made, while backup Y was being read; the check made against
        // X lands after Y is on record.
        expect("a check made against an earlier backup is refused once another is on record",
                refused(checkedX, data, backupY, false, false, "checked against the backup chosen now"));
        expect("the same check is refused when the backup that replaced X was refused and none is on record",
                refused(checkedX, data, null, false, false, "checked against the backup"));

        // app-2 (a): Save, or Write, while "Reading the backup…" shows.
        expect("nothing goes to the card while a backup is being read, even with a check against the current one",
                refused(checkedX, data, backupX, true, false, "still being read"));

        // app-2 (b): Y is in, the card listing comes back before the check against Y: Write must stay off.
        expect("nothing goes to the card while the file is being checked again",
                refused(checkedX, data, backupY, false, true, "being checked again")
                        && refused(checkedY, data, backupY, false, true, "being checked again"));

        // The open confirmation, and the worker just before it writes, ask again with the backup on record then.
        expect("Write in a confirmation opened before the backup changed is refused",
                refused(checkedX, data, backupY, false, false, "checked against the backup chosen now"));
        expect("the check against the backup on record now opens the gate again",
                WriteGate.refusal(checkedY, data, backupY, false, false) == null, null);

        // What UpdateCheck.confirms refuses, the gate refuses too.
        List<UpdateCheck.Line> fail = new ArrayList<>();
        fail.add(new UpdateCheck.Line(UpdateCheck.Level.FAIL, "sensor-table", "Image sensor set-up", "different.", ""));
        UpdateCheck.Result failed = new UpdateCheck.Result(fail, name, sha(data), data.length, true, backupY);
        expect("a file that failed its checks against this backup is refused",
                refused(failed, data, backupY, false, false, "did not pass"));
        byte[] other = data.clone();
        other[100] ^= 1;
        expect("bytes that changed after the check are refused", refused(checkedY, other, backupY, false, false, "changed"));
        UpdateCheck.Result nameless = new UpdateCheck.Result(new ArrayList<>(), null, sha(data), data.length, true,
                backupY);
        expect("a file without an update file's name is refused", refused(nameless, data, backupY, false, false, "name"));
        expect("no file is refused", refused(null, null, backupY, false, false, "no file"));
        UpdateCheck.Result tableless = new UpdateCheck.Result(new ArrayList<>(), name, sha(data), data.length, false,
                backupY);
        expect("a check against a backup it could not be compared with is refused",
                refused(tableless, data, backupY, false, false, "checked against the backup"));

        // The sensor-ID probe is the one reviewed file the gate lets through without a backup (WriteGate's class
        // note), recognised only by the reviewed SHA-256 and only for the bytes that hash to it. A check that merely
        // claims the probe's SHA-256 over other bytes is caught by confirms, never waved through as a flash with no
        // backup.
        UpdateCheck.Result probeClaim = new UpdateCheck.Result(new ArrayList<>(), Candidates.Kind.PROBE.fileName,
                Candidates.Kind.PROBE.sha256, data.length, false, null);
        // "changed" (from confirms): the probe branch was taken and the file-level check still ran.
        expect("a probe SHA-256 over other bytes is caught by its own check (confirms), not waved through",
                refused(probeClaim, data, null, false, false, "changed"));
        expect("the probe exemption still waits while the file is being checked",
                refused(probeClaim, data, null, false, true, "being checked again"));

        // With the real stock dump, the actual probe the app builds goes to the card with no backup on record; the
        // stock restore file does not; and the file-level checks still hold for the probe.
        String stockEnv = System.getenv("CHARMERA_STOCK_IMAGE");
        byte[] stock = stockEnv == null || stockEnv.isEmpty() ? null : readMaybe(stockEnv);
        if (stock == null || !Candidates.isStockDump(stock)) {
            System.out.println("WriteGateTest: probe-with-real-dump checks SKIPPED (set CHARMERA_STOCK_IMAGE to the"
                    + " public stock dump)");
        } else {
            byte[] dump = Candidates.stockDump(stock);
            byte[] probe = Candidates.build(dump, Candidates.Kind.PROBE);
            UpdateCheck.Result probeCheck = UpdateCheck.check(Candidates.Kind.PROBE.fileNameFor(UpdateCheck.sha256Hex(probe, 0, probe.length)), probe,
                    UpdateCheck.PUBLIC_BUILD, null);
            expect("the sensor-ID probe goes to the card with no backup on record",
                    WriteGate.refusal(probeCheck, probe, null, false, false) == null, null);
            expect("the probe still waits while it is being checked again",
                    refused(probeCheck, probe, null, false, true, "being checked again"));
            // Finding 7: the app checks the probe with no reference (buildRestore), so its check carries no backup
            // comparison that a backup on record could turn into a FAIL. A probe compared with a backup whose sensor
            // differs would fail its sensor-table line; the reference-free check the app makes cannot, and the gate
            // lets the probe through whatever backup is on record.
            expect("the probe's check makes no backup comparison, so no backup can turn it not-OK",
                    !probeCheck.compared && probeCheck.ok, null);
            expect("the sensor-ID probe goes to the card with a backup on record too",
                    WriteGate.refusal(probeCheck, probe, sha(dump), false, false) == null, null);
            byte[] tampered = probe.clone();
            tampered[200] ^= 1;
            expect("probe bytes changed after the check are refused",
                    refused(probeCheck, tampered, null, false, false, "changed"));
            // The OV9732 bring-up is the second reviewed patch the gate lets through without a backup (same waiver).
            byte[] bringup = Candidates.build(dump, Candidates.Kind.BRINGUP);
            UpdateCheck.Result bringupCheck = UpdateCheck.check(Candidates.Kind.BRINGUP.fileNameFor(UpdateCheck.sha256Hex(bringup, 0, bringup.length)), bringup,
                    UpdateCheck.PUBLIC_BUILD, null);
            expect("the OV9732 bring-up goes to the card with no backup on record",
                    WriteGate.refusal(bringupCheck, bringup, null, false, false) == null, null);
            expect("the bring-up goes to the card with a backup on record too",
                    WriteGate.refusal(bringupCheck, bringup, sha(dump), false, false) == null, null);
            byte[] bringupTampered = bringup.clone();
            bringupTampered[200] ^= 1;
            expect("bring-up bytes changed after the check are refused",
                    refused(bringupCheck, bringupTampered, null, false, false, "changed"));
            byte[] restore = Candidates.build(dump, Candidates.Kind.RESTORE);
            UpdateCheck.Result restoreCheck = UpdateCheck.check(Candidates.Kind.RESTORE.fileNameFor(UpdateCheck.sha256Hex(restore, 0, restore.length)), restore,
                    UpdateCheck.PUBLIC_BUILD, null);
            expect("the stock restore file is a reviewed file: with no backup on record it goes to the card",
                    WriteGate.refusal(restoreCheck, restore, null, false, false) == null, restoreCheck.report());
            expect("but a check of it made without a backup waits for the check against the backup once one is on record",
                    refused(restoreCheck, restore, sha(dump), false, false, "checked against the backup"));
        }

        // v0.25, made from the owner's factory dump, is a Repair version: written with no backup, by the same waiver.
        String factoryEnv = System.getenv("CHARMERA_FACTORY_DUMP");
        byte[] factory = factoryEnv == null || factoryEnv.isEmpty() ? null : readMaybe(factoryEnv);
        if (factory == null || !Candidates.isFactoryDump(factory)) {
            System.out.println("WriteGateTest: factory-dump checks SKIPPED (set CHARMERA_FACTORY_DUMP to the factory"
                    + " dump)");
        } else {
            byte[] v025 = Candidates.build(factory, Candidates.Kind.V025);
            UpdateCheck.Result v025Check = Candidates.check(Candidates.Kind.V025.fileNameFor(UpdateCheck.sha256Hex(v025, 0, v025.length)), v025, factory);
            expect("firmware v0.25 goes to the card with no backup on record",
                    WriteGate.refusal(v025Check, v025, null, false, false) == null, null);
            expect("and with a backup on record", WriteGate.refusal(v025Check, v025, sha(factory), false, false) == null,
                    null);
            byte[] v025Tampered = v025.clone();
            v025Tampered[200] ^= 1;
            expect("v0.25 bytes changed after the check are refused",
                    refused(v025Check, v025Tampered, null, false, false, "changed"));
        }

        // The backup waiver per set: every reviewed build goes to the card without a backup; the owner decides. The
        // SP140A builds (v006 without the port, v008_test) are compared with a backup when one is on record, and a
        // backup that drives the other sensor makes a loud warning, not a refusal.
        String[] stockEntry = stock == null ? null : Recipe.dumpFor(stock);
        if (stockEntry == null || !"v006".equals(stockEntry[1])) {
            System.out.println("WriteGateTest: per-set v006 checks SKIPPED (set CHARMERA_STOCK_IMAGE to a v006 dump)");
        } else {
            byte[] rows = Recipe.build(stock, new Recipe.Selection("v006", "v006/volume-led"));
            UpdateCheck.Result rowsAlone = Candidates.check(Recipe.fileNameFor(sha(rows)), rows, null);
            expect("v006 + Volume and LED rows with no backup on record goes to the card",
                    rowsAlone.ok && WriteGate.refusal(rowsAlone, rows, null, false, false) == null, rowsAlone.report());
            expect("and a check of it made without a backup is refused once a backup is on record",
                    refused(rowsAlone, rows, sha(stock), false, false, "checked against the backup"));
            UpdateCheck.Result rowsAgainst = Candidates.check(Recipe.fileNameFor(sha(rows)), rows, stock);
            expect("v006 + Volume and LED rows checked against the backup on record may go to the card",
                    rowsAgainst.compared && WriteGate.refusal(rowsAgainst, rows, sha(stock), false, false) == null,
                    rowsAgainst.report());
            byte[] v023 = Recipe.build(stock, new Recipe.Selection("v006", "v006/ov9732-port"));
            UpdateCheck.Result v023Check = Candidates.check(Recipe.fileNameFor(sha(v023)), v023, null);
            expect("v0.23 (v006 + OV9732 port) goes to the card with no backup on record",
                    WriteGate.refusal(v023Check, v023, null, false, false) == null, v023Check.report());
            byte[] restore = Recipe.build(stock, new Recipe.Selection("v006"));
            UpdateCheck.Result restoreAlone = Candidates.check(Recipe.fileNameFor(sha(restore)), restore, null);
            expect("stock v006 (the restore file) with no backup on record goes to the card",
                    WriteGate.refusal(restoreAlone, restore, null, false, false) == null, restoreAlone.report());
        }
        if (factory == null || Recipe.dumpFor(factory) == null) {
            System.out.println("WriteGateTest: per-set v012 checks SKIPPED (set CHARMERA_FACTORY_DUMP to a v012 dump)");
        } else {
            byte[] v012 = Recipe.build(factory, new Recipe.Selection("v012"));
            UpdateCheck.Result v012Check = Candidates.check(Recipe.fileNameFor(sha(v012)), v012, null);
            expect("stock v012 (the factory firmware, unchanged) goes to the card with no backup on record",
                    WriteGate.refusal(v012Check, v012, null, false, false) == null, v012Check.report());
            UpdateCheck.Result v012WithStock = stock == null ? v012Check
                    : Candidates.check(Recipe.fileNameFor(sha(v012)), v012, stock);
            expect("and whatever backup is on record (it is never compared with one)", !v012WithStock.compared
                    && WriteGate.refusal(v012WithStock, v012, stock == null ? null : sha(stock), false, false) == null,
                    v012WithStock.report());
        }
        String v008Env = System.getenv("CHARMERA_V008_DUMP");
        if (v008Env == null || v008Env.isEmpty()) {
            v008Env = System.getenv("CHARMERA_FILM_MODS_RESTORE");
        }
        byte[] v008Dump = v008Env == null || v008Env.isEmpty() ? null : readMaybe(v008Env);
        if (v008Dump == null || Recipe.dumpFor(v008Dump) == null) {
            System.out.println("WriteGateTest: per-set v008_test checks SKIPPED (set CHARMERA_V008_DUMP or"
                    + " CHARMERA_FILM_MODS_RESTORE to the v008_test file)");
        } else {
            byte[] v008 = Recipe.build(v008Dump, new Recipe.Selection("v008_test"));
            String v008Name = Recipe.fileNameFor(sha(v008));
            UpdateCheck.Result v008Alone = Candidates.check(v008Name, v008, null);
            expect("stock v008_test with no backup on record goes to the card",
                    WriteGate.refusal(v008Alone, v008, null, false, false) == null, v008Alone.report());
            UpdateCheck.Result v008Own = Candidates.check(v008Name, v008, v008Dump);
            expect("stock v008_test checked against its own build's backup may go to the card", v008Own.ok
                    && v008Own.compared && WriteGate.refusal(v008Own, v008, sha(v008Dump), false, false) == null,
                    v008Own.report());
            if (factory == null) {
                System.out.println("WriteGateTest: v008_test against an OV9732 backup SKIPPED (set"
                        + " CHARMERA_FACTORY_DUMP)");
            } else {
                // The backup's firmware drives the OV9732 without an SP140A table: for a reviewed SP140A build that is a
                // loud "Wrong sensor?" warning, not a refusal (the owner decides).
                UpdateCheck.Result v008OnOv = Candidates.check(v008Name, v008, factory);
                boolean tableWarns = false;
                boolean anyFail = false;
                for (UpdateCheck.Line l : v008OnOv.lines) {
                    tableWarns |= l.level == UpdateCheck.Level.WARN && l.name.equals("sensor-table")
                            && l.title.equals("Wrong sensor?");
                    anyFail |= l.level == UpdateCheck.Level.FAIL;
                }
                expect("stock v008_test against an OV9732 backup is OK with a Wrong sensor? warning",
                        v008OnOv.ok && tableWarns && !anyFail, v008OnOv.report());
                expect("and the gate lets it through", WriteGate.refusal(v008OnOv, v008, sha(factory), false, false)
                        == null, v008OnOv.report());
            }
        }

        for (String why : new String[]{WriteGate.refusal(checkedX, data, backupY, false, false),
                WriteGate.refusal(checkedX, data, backupX, true, false), WriteGate.refusal(checkedX, data, null, false, false),
                WriteGate.refusal(checkedX, data, backupY, false, true)}) {
            expect("a refusal is a plain clause: lower case first, no final full stop", why != null
                    && Character.isLowerCase(why.charAt(0)) && !why.endsWith("."), why);
        }

        System.out.printf(Locale.ROOT, "WriteGateTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    static UpdateCheck.Result passed(String name, byte[] data, String backupSha256) {
        return new UpdateCheck.Result(new ArrayList<>(), name, sha(data), data.length, true, backupSha256);
    }

    static String sha(byte[] data) {
        return UpdateCheck.sha256Hex(data, 0, data.length);
    }

    static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    /** The file's bytes, or null if it cannot be read (the real-dump checks then skip). */
    static byte[] readMaybe(String path) {
        try {
            return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
        } catch (java.io.IOException e) {
            return null;
        }
    }

    static boolean refused(UpdateCheck.Result check, byte[] data, String backupSha256, boolean loading, boolean checking,
                           String why) {
        String r = WriteGate.refusal(check, data, backupSha256, loading, checking);
        if (r == null || !r.contains(why)) {
            System.out.println("  refusal was: " + r);
            return false;
        }
        return true;
    }

    static void expect(String what, boolean ok) {
        expect(what, ok, null);
    }

    static void expect(String what, boolean ok, String shown) {
        if (ok) {
            passes++;
        } else {
            failures++;
            System.out.println("FAILED: " + what + (shown == null ? "" : "\n" + shown));
        }
    }
}
