import io.github.daemonddev.charmera.Candidates;
import io.github.daemonddev.charmera.UpdateCheck;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Plain-JVM tests for making the flash candidates in the app. Synthetic images test each step of the recipe and the
 * refusals; with CHARMERA_STOCK_IMAGE set to the public stock dump, all three candidates are made and must hash to
 * the reviewed files (Candidates.build refuses anything else).
 */
public final class CandidatesTest {

    private static int passes = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // the stock dump is recognised by its hash only
        byte[] junk = new byte[0x200000];
        expect("a 2 MiB file that is not the dump is refused", refused(() -> Candidates.stockDump(junk), "not the public stock dump"));
        expect("a short file is refused as cut off", refused(() -> Candidates.stockDump(new byte[0x166000]), "cut-off download"));
        expect("isStockDump is false for anything else", !Candidates.isStockDump(junk) && !Candidates.isStockDump(new byte[10]));

        // each step of the recipe, on a synthetic image
        byte[] img = synthetic();
        byte[] c = invokeTransform(img, Candidates.Kind.C);
        byte[] a = invokeTransform(img, Candidates.Kind.A);
        byte[] b = invokeTransform(img, Candidates.Kind.B);
        expect("output is an update file's size", c.length == UpdateCheck.UPDATE_SIZE && a.length == c.length && b.length == c.length);
        expect("C keeps the first valid record at sector 0", Arrays.equals(Arrays.copyOfRange(c, 0x170000, 0x170100),
                Arrays.copyOfRange(img, 0x170200, 0x170300)));
        expect("the rest of the bank is erased", allEqual(c, 0x170200, 0x17F000, 0xFF) && allEqual(c, 0x170100, 0x170200, 0));
        expect("A sets UI sound 0 with a valid CRC", (a[0x170019] & 0xFF) == 0 && crcOk(a, 0x170000));
        expect("C keeps UI sound as it was", (c[0x170019] & 0xFF) == 5);
        expect("photo directory zeroed, photo slots erased", allEqual(c, 0x17F000, 0x17F200, 0)
                && allEqual(c, 0x17F200, UpdateCheck.UPDATE_SIZE, 0xFF));
        expect("B silences 8-bit PCM to 0x80 and 16-bit to 0", allEqual(b, WAV8_DATA, WAV8_DATA + 64, 0x80)
                && allEqual(b, WAV16_DATA, WAV16_DATA + 64, 0) && b[WAV8_DATA - 8] == 'd');
        expect("A leaves the sound data alone", a[WAV8_DATA] == img[WAV8_DATA]);
        expect("nothing below the settings bank changes", Arrays.equals(Arrays.copyOf(b, 0x120000), Arrays.copyOf(img, 0x120000)));
        expect("a synthetic dump never passes build()", refused(() -> Candidates.build(img, Candidates.Kind.C), "not the reviewed"));

        String real = System.getenv("CHARMERA_STOCK_IMAGE");
        byte[] given = real == null || real.isEmpty() ? null : Files.readAllBytes(Paths.get(real));
        if (given == null) {
            System.out.println("CandidatesTest: real-dump checks SKIPPED (set CHARMERA_STOCK_IMAGE to the public stock dump)");
        } else if (!Candidates.isStockDump(given)) {
            System.out.println("CandidatesTest: real-dump checks SKIPPED (CHARMERA_STOCK_IMAGE is not the public stock dump)");
        } else {
            byte[] dump = Candidates.stockDump(given);
            for (Candidates.Kind k : Candidates.Kind.values()) {
                byte[] out = Candidates.build(dump, k);   // throws unless the SHA-256 is the reviewed one
                UpdateCheck.Result r = UpdateCheck.check(k.fileName, out);
                expect("real " + k + " is the reviewed file and passes the checker", r.ok && k.fileName.equals(r.cardName)
                        && k.sha256.equals(r.sha256));
            }
            byte[] restore = Arrays.copyOf(dump, UpdateCheck.UPDATE_SIZE);
            expect("the published restore file is accepted as the dump", Arrays.equals(Candidates.stockDump(restore), dump));
        }
        System.out.printf(Locale.ROOT, "CandidatesTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    static final int WAV8_DATA = 0x121E00 + 44;
    static final int WAV16_DATA = 0x123200 + 44;

    /** A 2 MiB image with two settings records (an erased sector first), a GP+ pack with two WAVs, and photos. */
    static byte[] synthetic() {
        byte[] d = new byte[0x200000];
        for (int i = 0; i < 0x120000; i++) {
            d[i] = (byte) (i * 31 + 7);
        }
        Arrays.fill(d, 0x120000, 0x200000, (byte) 0xFF);
        Arrays.fill(d, 0x120000, 0x120018, (byte) 0);
        put(d, 0x120000, "GP+");
        entry(d, 0, "IMGWAV", (0x121E00 - 0x120000) / 0x200);
        entry(d, 1, "BEEPWAV", (0x123200 - 0x120000) / 0x200);
        Arrays.fill(d, 0x120018 + 48, 0x120018 + 72, (byte) 0);
        wav(d, 0x121E00, 8, 1000);
        wav(d, 0x123200, 16, 1000);
        record(d, 0x170200, 5);      // sector 0 left erased: the first valid record is the second sector
        record(d, 0x170400, 3);
        Arrays.fill(d, 0x17F000, 0x17F200, (byte) 0);
        Arrays.fill(d, 0x180000, 0x181000, (byte) 0x55);
        return d;
    }

    static void entry(byte[] d, int i, String name, int sector) {
        int off = 0x120018 + 24 * i;
        Arrays.fill(d, off, off + 24, (byte) 0);
        put(d, off, name);
        d[off + 20] = (byte) sector;
        d[off + 21] = (byte) (sector >> 8);
    }

    static void wav(byte[] d, int off, int bits, int dataLen) {
        Arrays.fill(d, off, off + 44, (byte) 0);    // a real header: every field not set below is zero
        put(d, off, "RIFF");
        le32(d, off + 4, 36 + dataLen);
        put(d, off + 8, "WAVEfmt ");
        le32(d, off + 16, 16);
        d[off + 20] = 1;
        d[off + 22] = 1;
        le32(d, off + 24, 8000);
        d[off + 34] = (byte) bits;
        put(d, off + 36, "data");
        le32(d, off + 40, dataLen);
        for (int i = 0; i < dataLen; i++) {
            d[off + 44 + i] = (byte) (i * 13 + 1);
        }
    }

    static void record(byte[] d, int off, int uiSound) {
        Arrays.fill(d, off, off + 0x200, (byte) 0);
        d[off] = 1;
        d[off + 0x19] = (byte) uiSound;
        CRC32 crc = new CRC32();
        crc.update(d, off, 252);
        le32(d, off + 252, (int) crc.getValue());
    }

    static byte[] invokeTransform(byte[] img, Candidates.Kind kind) throws Exception {
        java.lang.reflect.Method m = Candidates.class.getDeclaredMethod("transform", byte[].class, Candidates.Kind.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, img, kind);
    }

    static boolean crcOk(byte[] d, int off) {
        CRC32 crc = new CRC32();
        crc.update(d, off, 252);
        long v = (d[off + 252] & 0xFFL) | (d[off + 253] & 0xFFL) << 8 | (d[off + 254] & 0xFFL) << 16 | (d[off + 255] & 0xFFL) << 24;
        return crc.getValue() == v;
    }

    static boolean allEqual(byte[] d, int from, int to, int value) {
        for (int i = from; i < to; i++) {
            if ((d[i] & 0xFF) != value) {
                return false;
            }
        }
        return true;
    }

    static void put(byte[] d, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            d[off + i] = (byte) s.charAt(i);
        }
    }

    static void le32(byte[] d, int off, int v) {
        for (int i = 0; i < 4; i++) {
            d[off + i] = (byte) (v >>> (8 * i));
        }
    }

    interface Call {
        void run() throws Exception;
    }

    static boolean refused(Call c, String message) {
        try {
            c.run();
            return false;
        } catch (Exception e) {
            Throwable t = e.getCause() != null && e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
            return String.valueOf(t.getMessage()).contains(message);
        }
    }

    static void expect(String what, boolean ok) {
        if (ok) {
            passes++;
        } else {
            failures++;
            System.out.println("FAILED: " + what);
        }
    }
}
