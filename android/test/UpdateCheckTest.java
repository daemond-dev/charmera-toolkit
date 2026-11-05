import io.github.daemonddev.charmera.UpdateCheck;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * Plain-JVM tests for the app's update-file checks. The images are synthetic (no Kodak or Reto firmware in the
 * repository); the test builds its own Build descriptor from the synthetic regions. Set CHARMERA_UPDATE_FILES to a
 * list of real update files (separated by the platform's path separator) to also check them against the real build.
 */
public final class UpdateCheckTest {

    private static int failures = 0;
    private static int passes = 0;

    public static void main(String[] args) throws Exception {
        byte[] base = synthetic();
        UpdateCheck.Build build = buildFor(base);

        UpdateCheck.Result r = UpdateCheck.check(name(base, "202611050001"), base, build);
        expect("good file passes", r.ok, r);
        expect("card name is the canonical name", name(base, "202611050001").equals(r.cardName), r);
        expect("software reported identical", line(r, "software", UpdateCheck.Level.PASS), r);
        expect("blank settings reported", line(r, "settings", UpdateCheck.Level.INFO), r);
        expect("43 resources", line(r, "resources", UpdateCheck.Level.PASS), r);

        String copy = name(base, "202611050001").replace(".bin", " (1).bin");
        r = UpdateCheck.check(copy, base, build);
        expect("a renamed copy still passes", r.ok, r);
        expect("and is written under the canonical name", name(base, "202611050001").equals(r.cardName)
                && line(r, "card-name", UpdateCheck.Level.INFO), r);

        r = UpdateCheck.check("JH_5408202611050001_00000000.bin", base, build);
        expect("wrong sum fails", !r.ok && line(r, "checksum", UpdateCheck.Level.FAIL), r);

        r = UpdateCheck.check(name(base, "202505201029"), base, build);
        expect("stock stamp fails", !r.ok && line(r, "stamp", UpdateCheck.Level.FAIL), r);

        r = UpdateCheck.check(name(base, "202699999999"), base, build);
        expect("impossible date only warns", r.ok && line(r, "stamp-date", UpdateCheck.Level.WARN), r);

        r = UpdateCheck.check(name(base, "202611050001").replace("JH_5408", "JH_5409"), base, build);
        expect("other product fails", !r.ok && line(r, "product", UpdateCheck.Level.FAIL), r);

        r = UpdateCheck.check("firmware.bin", base, build);
        expect("no update name fails", !r.ok && r.cardName == null && line(r, "name", UpdateCheck.Level.FAIL), r);

        byte[] big = new byte[0x200000];
        System.arraycopy(base, 0, big, 0, base.length);
        java.util.Arrays.fill(big, base.length, big.length, (byte) 0xFF);
        r = UpdateCheck.check(name(big, "202611050001"), big, build);
        expect("2 MiB file fails", !r.ok && line(r, "size", UpdateCheck.Level.FAIL), r);

        byte[] m = base.clone();
        m[0] = 'X';
        r = UpdateCheck.check(name(m, "202611050001"), m, build);
        expect("bad magic fails", !r.ok && line(r, "magic", UpdateCheck.Level.FAIL), r);

        m = base.clone();
        m[0x18964] = 'X';
        r = UpdateCheck.check(name(m, "202611050001"), m, build);
        expect("other build string fails", !r.ok && line(r, "series", UpdateCheck.Level.FAIL), r);

        m = base.clone();
        m[0xF5C70] ^= 1;
        r = UpdateCheck.check(name(m, "202611050001"), m, build);
        expect("changed sensor table fails", !r.ok && line(r, "sensor-table", UpdateCheck.Level.FAIL), r);

        m = base.clone();
        m[0x121E80] ^= 0x55;
        r = UpdateCheck.check(name(m, "202611050001"), m, build);
        expect("resource mod warns, passes", r.ok && line(r, "software", UpdateCheck.Level.WARN)
                && detail(r, "software").contains("the code is stock"), r);

        m = base.clone();
        m[0x62B8] ^= 0x08;
        r = UpdateCheck.check(name(m, "202611050001"), m, build);
        expect("code mod warns, passes", r.ok && detail(r, "software").contains("changes the code"), r);

        m = base.clone();
        putRecord(m, 0x170000, 0);
        r = UpdateCheck.check(name(m, "202611050001"), m, build);
        expect("record with erased photo directory fails", !r.ok && line(r, "photo-directory", UpdateCheck.Level.FAIL), r);
        java.util.Arrays.fill(m, 0x17F000, 0x17F200, (byte) 0);
        r = UpdateCheck.check(name(m, "202611050001"), m, build);
        expect("muted record reads as UI sounds OFF", r.ok && detail(r, "settings").contains("UI sounds OFF"), r);
        m[0x170000 + 0x10] ^= 1;   // break the CRC
        r = UpdateCheck.check(name(m, "202611050001"), m, build);
        expect("bad CRC warns", r.ok && line(r, "settings", UpdateCheck.Level.WARN), r);

        m = base.clone();
        m[0x180000] = (byte) 0xFF;
        m[0x180001] = (byte) 0xD8;
        r = UpdateCheck.check(name(m, "202611050001"), m, build);
        expect("stored photo warns", r.ok && line(r, "photos", UpdateCheck.Level.WARN), r);

        String real = System.getenv("CHARMERA_UPDATE_FILES");
        if (real != null && !real.isEmpty()) {
            for (String p : real.split(File.pathSeparator)) {
                File f = new File(p);
                byte[] d = Files.readAllBytes(f.toPath());
                r = UpdateCheck.check(f.getName(), d);
                System.out.println("--- " + f.getName() + "\n" + r.report());
                expect("real file " + f.getName() + " passes against the real build", r.ok, r);
            }
        }

        System.out.printf(Locale.ROOT, "%d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // --- synthetic image ---------------------------------------------------------------------------------------

    static byte[] synthetic() {
        byte[] d = new byte[UpdateCheck.UPDATE_SIZE];
        new Random(5408).nextBytes(d);
        put(d, 0, "SPII");
        put(d, 0x218C4, "202505201029");
        put(d, 0x18964, "JH5408  20250520");
        put(d, 0xF3978, "Oct 14 2025");
        // a GP+ pack with 43 directory entries
        java.util.Arrays.fill(d, 0x120000, 0x120018, (byte) 0);
        put(d, 0x120000, "GP+");
        for (int i = 0; i < 43; i++) {
            int off = 0x120018 + 24 * i;
            java.util.Arrays.fill(d, off, off + 24, (byte) 0);
            put(d, off, String.format(Locale.ROOT, "RES%02dGPZP", i));
            d[off + 20] = (byte) (3 + i);
        }
        java.util.Arrays.fill(d, 0x120018 + 24 * 43, 0x120018 + 24 * 44, (byte) 0);
        // settings bank, photo directory and photo slots blank, as pack --blank-settings --blank-photos leaves them
        java.util.Arrays.fill(d, 0x170000, d.length, (byte) 0xFF);
        return d;
    }

    static UpdateCheck.Build buildFor(byte[] d) {
        return new UpdateCheck.Build("synthetic test build", "202505201029", 0x218C4, "JH5408  20250520", 0x18964,
                "Oct 14 2025", 0xF3978, UpdateCheck.sha256Hex(d, 0, 0x170000), UpdateCheck.sha256Hex(d, 0x200, 0x100000),
                0xF5C6C, 0xF5CE8, UpdateCheck.sha256Hex(d, 0xF5C6C, 0xF5CE8));
    }

    static void putRecord(byte[] d, int off, int uiSound) {
        java.util.Arrays.fill(d, off, off + 0x200, (byte) 0);
        d[off] = 1;
        d[off + 0x05] = 5;
        d[off + 0x19] = (byte) uiSound;
        CRC32 crc = new CRC32();
        crc.update(d, off, 252);
        long v = crc.getValue();
        for (int i = 0; i < 4; i++) {
            d[off + 252 + i] = (byte) (v >>> (8 * i));
        }
    }

    static void put(byte[] d, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, d, off, b.length);
    }

    static String name(byte[] d, String stamp) {
        return String.format(Locale.ROOT, "JH_5408%s_%08X.bin", stamp, UpdateCheck.byteSum(d));
    }

    // --- assertions --------------------------------------------------------------------------------------------

    static boolean line(UpdateCheck.Result r, String name, UpdateCheck.Level level) {
        for (UpdateCheck.Line l : r.lines) {
            if (l.name.equals(name) && l.level == level) {
                return true;
            }
        }
        return false;
    }

    static String detail(UpdateCheck.Result r, String name) {
        for (UpdateCheck.Line l : r.lines) {
            if (l.name.equals(name)) {
                return l.detail;
            }
        }
        return "";
    }

    static void expect(String what, boolean ok, UpdateCheck.Result r) {
        if (ok) {
            passes++;
        } else {
            failures++;
            System.out.println("FAILED: " + what + "\n" + r.report());
        }
    }
}
