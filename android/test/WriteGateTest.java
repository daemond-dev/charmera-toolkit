package io.github.daemonddev.charmera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Plain-JVM tests for the write gate (WriteGate). MainActivity's races cannot run here, but each one leaves the gate
 * a state: a check made against one backup while another is on record, a backup still being read, a check still
 * running. Each test below is one of those states, named after the race that leads to it, and the gate must refuse
 * every one of them. In the app's package, because WriteGate and the Result constructor are package-private.
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

        // No backup of the camera, no flash.
        expect("no backup on record: refused", refused(checkedY, data, null, false, false, "no backup"));
        expect("a check made without a backup: refused, also once a backup is on record",
                refused(unchecked, data, null, false, false, "no backup")
                        && refused(unchecked, data, backupY, false, false, "not checked against the backup"));

        // app-1: the file was picked, or the restore file made, while backup Y was being read; the check made against
        // X lands after Y is on record.
        expect("a check made against an earlier backup is refused once another is on record",
                refused(checkedX, data, backupY, false, false, "not checked against the backup on record now"));
        expect("the same check is refused when the backup that replaced X was refused and none is on record",
                refused(checkedX, data, null, false, false, "no backup"));

        // app-2 (a): Save, or Write, while "Reading the backup…" shows.
        expect("nothing goes to the card while a backup is being read, even with a check against the current one",
                refused(checkedX, data, backupX, true, false, "still being read"));

        // app-2 (b): Y is in, the card listing comes back before the check against Y: Write must stay off.
        expect("nothing goes to the card while the file is being checked again",
                refused(checkedX, data, backupY, false, true, "being checked again")
                        && refused(checkedY, data, backupY, false, true, "being checked again"));

        // The open confirmation, and the worker just before it writes, ask again with the backup on record then.
        expect("Write in a confirmation opened before the backup changed is refused",
                refused(checkedX, data, backupY, false, false, "not checked against the backup on record now"));
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
                refused(tableless, data, backupY, false, false, "not checked against the backup"));

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
