import io.github.daemonddev.charmera.Candidates;
import io.github.daemonddev.charmera.UpdateCheck;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Plain-JVM tests for making the flash candidates in the app. Synthetic images test each step of the recipe and the
 * refusals; with CHARMERA_STOCK_IMAGE set to the public stock dump, the restore file and the three withdrawn
 * candidates are made and must hash to the published and reviewed files (Candidates.build refuses anything else), and
 * the checker must find the dump's build marks where the research put them.
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
        expect("a synthetic dump never passes build()", refused(() -> Candidates.build(img, Candidates.Kind.C),
                "is not exactly candidate C, so the app will not use it"));
        expect("a synthetic restore never passes build()", refused(() -> Candidates.build(img, Candidates.Kind.RESTORE),
                "is not exactly the published stock restore file"));
        expect("a refusal says the cause first and keeps the SHA-256 for its last line", refused(
                () -> Candidates.stockDump(junk), "so the app will not use it") && refused(() -> Candidates.stockDump(junk),
                "\nDetail: its SHA-256 is "));
        // Whether a 2 MiB file could be a backup is for UpdateCheck.backupProblem to say: step 0 may refuse it too.
        expect("a refused dump is not pointed to step 0", !refused(() -> Candidates.stockDump(junk), "step 0")
                && !refused(() -> Candidates.stockDump(junk), "Choose this camera's backup"));
        expect("only the stock restore is offered", java.util.Arrays.equals(Candidates.Kind.offeredKinds(),
                new Candidates.Kind[]{Candidates.Kind.RESTORE}));
        byte[] restore0 = invokeTransform(img, Candidates.Kind.RESTORE);
        expect("the restore is the dump's first 0x1E0000 bytes, untouched",
                java.util.Arrays.equals(restore0, java.util.Arrays.copyOf(img, UpdateCheck.UPDATE_SIZE)));

        String real = System.getenv("CHARMERA_STOCK_IMAGE");
        byte[] given = real == null || real.isEmpty() ? null : Files.readAllBytes(Paths.get(real));
        if (given == null) {
            System.out.println("CandidatesTest: real-dump checks SKIPPED (set CHARMERA_STOCK_IMAGE to the public stock dump)");
        } else if (!Candidates.isStockDump(given)) {
            System.out.println("CandidatesTest: real-dump checks SKIPPED (CHARMERA_STOCK_IMAGE is not the public stock dump)");
        } else {
            byte[] dump = Candidates.stockDump(given);
            for (Candidates.Kind k : Candidates.Kind.values()) {
                byte[] out = Candidates.build(dump, k);   // throws unless the SHA-256 is the published or reviewed one
                UpdateCheck.Result r = UpdateCheck.check(k.fileName, out);
                expect("real " + k + " is the reviewed file and passes the checker", r.ok && k.fileName.equals(r.cardName)
                        && k.sha256.equals(r.sha256));
            }
            byte[] restore = Arrays.copyOf(dump, UpdateCheck.UPDATE_SIZE);
            expect("the published restore file is accepted as the dump", Arrays.equals(Candidates.stockDump(restore), dump));
            UpdateCheck.Marks mk = UpdateCheck.findMarks(dump);
            expect("the real dump's marks are found by content where the research put them", mk.complete()
                    && mk.stampOffset == 0x218C4 && "202505201029".equals(mk.stamp) && mk.buildOffset == 0x18964
                    && mk.resetStart == 0xF5BFC && mk.sensorStart == 0xF5C6C && mk.sensorEnd == 0xF5CE8 && mk.sensorPairs == 61
                    && mk.referencedFrom == 0x7B91C && mk.busAddress == 0x78
                    && "aea3976bdf7ba2e747d8b56141f7ef0b4e3262481540dc2869457bc814fcb755".equals(mk.sensorSha256));
            expect("its source folder is v006, named in all 27 of its source paths", "v006".equals(mk.sourceTree)
                    && mk.sourceTrees.size() == 1 && mk.sourceTrees.get("v006") == 27 && mk.sourceTreeOffset == 0x3A4C7
                    && mk.stampConflict.isEmpty());
            UpdateCheck.Result rr = UpdateCheck.check(Candidates.Kind.RESTORE.fileName, restore, UpdateCheck.PUBLIC_BUILD, dump);
            expect("the restore file against the dump it came from passes, and the sensor line says what a public"
                    + " backup proves", rr.ok && rr.compared && level(rr, "software") == UpdateCheck.Level.PASS
                    && level(rr, "vector-table") == UpdateCheck.Level.PASS
                    && level(rr, "sensor-table") == UpdateCheck.Level.WARN
                    && rr.verdict().startsWith("OK to put on the card. Read these 2 warnings first. Image sensor set-up:"
                    + " the same as in the firmware in your backup. That firmware is the public dump's build, unchanged")
                    && rr.verdict().contains("Stored photos:"));
            expect("the public dump is refused as a backup of your camera",
                    UpdateCheck.backupProblem(dump, new String[]{Candidates.STOCK_DUMP_SHA256}) != null);
            // A read of a camera flashed with the restore file, once one setting was saved: taken, with the note.
            byte[] read = dump.clone();
            read[0x170100] ^= 1;
            expect("a near copy of the public dump is taken, and noted as the public build",
                    UpdateCheck.backupProblem(read, Candidates.PUBLIC_DUMP_SHA256S) == null
                            && UpdateCheck.backupNotes(read, UpdateCheck.PUBLIC_BUILD).size() == 1
                            && UpdateCheck.backupNotes(read, UpdateCheck.PUBLIC_BUILD).get(0).contains("the public dump's"
                            + " build, unchanged"));
            UpdateCheck.Result plain = UpdateCheck.check(Candidates.Kind.RESTORE.fileName, restore);
            expect("without a backup the restore file's own checks pass, its vector table is the public build's, and"
                    + " it is not OK yet", plain.ok && !plain.compared && level(plain, "vector-table") == UpdateCheck.Level.PASS
                    && plain.verdict().startsWith("Not OK to put on the card yet:"));
            expect("its build marks name the source folder, and what a folder shows", text(plain, "series").contains(
                    "compiled Oct 14 2025, source folder v006, stamp 202505201029") && text(plain, "series").contains(
                    "the same one does not show the same build, and neither says whether the file fits your camera"));
            // The same changes to the real file as in tests/test_android.py, where image.py makes the same calls.
            byte[] restamped = restore.clone();
            put(restamped, 0x218C4, "202601010000");
            UpdateCheck.Result sr = UpdateCheck.check(named(restamped), restamped);
            expect("the restore file with another stamp inside is a build laid out like the public one, not that build"
                    + " with changes", level(sr, "build") == UpdateCheck.Level.WARN && text(sr, "build").contains(
                    "laid out like the public dump's build: its stamp is not that build's")
                    && !text(sr, "build").contains("with changes"));
            byte[] twice = dump.clone();
            put(twice, 0xFFF8, "*.bin\0xx202601010101\0");   // a second stamp after a second *.bin
            UpdateCheck.Marks tm = UpdateCheck.findMarks(twice);
            expect("two different stamps after *.bin: no stamp", tm.stamp == null
                    && tm.stampConflict.equals(Arrays.asList("202601010101", "202505201029")));
            byte[] broken = restore.clone();
            for (int i = 0x200; i < 0x240; i++) {
                broken[i] ^= (byte) 0xFF;
            }
            UpdateCheck.Result vr = UpdateCheck.check(String.format(Locale.ROOT, "JH_5408202611061300_%08X.bin",
                    UpdateCheck.byteSum(broken)), broken);
            expect("the restore file with its vector table destroyed is refused", !vr.ok
                    && level(vr, "vector-table") == UpdateCheck.Level.FAIL);
        }
        String film = System.getenv("CHARMERA_FILM_MODS_RESTORE");
        byte[] f = film == null || film.isEmpty() || !Files.isRegularFile(Paths.get(film)) ? null
                : Files.readAllBytes(Paths.get(film));
        if (f != null) {
            UpdateCheck.Result fr = UpdateCheck.check(named(f), f);
            expect("the film-mods build's vector table is compared with its own build's, by its layout",
                    level(fr, "vector-table") == UpdateCheck.Level.PASS && fr.ok);
        }
        if (f != null && given != null && Candidates.isStockDump(given)) {
            // A backup laid out like the other known build: a broken vector table is still caught, by the known
            // build whose layout the file has, as it is without a backup.
            byte[] dump = Candidates.stockDump(given);
            byte[] filmChip = Arrays.copyOf(f, 0x200000);
            Arrays.fill(filmChip, f.length, filmChip.length, (byte) 0xFF);
            byte[] read = dump.clone();
            read[0x170100] ^= 1;                    // a read of a camera flashed with the restore file, one setting saved
            expect("the film-mods restore file padded to 2 MiB, and a near copy of the public dump, are taken as backups",
                    UpdateCheck.backupProblem(filmChip, Candidates.PUBLIC_DUMP_SHA256S) == null
                            && UpdateCheck.backupProblem(read, Candidates.PUBLIC_DUMP_SHA256S) == null);
            byte[] broken = Arrays.copyOf(dump, UpdateCheck.UPDATE_SIZE);
            byte[] brokenFilm = f.clone();
            for (int i = 0x200; i < 0x240; i++) {
                broken[i] ^= (byte) 0xFF;
                brokenFilm[i] ^= (byte) 0xFF;
            }
            UpdateCheck.Result vr = UpdateCheck.check(named(broken), broken, UpdateCheck.PUBLIC_BUILD, filmChip);
            expect("the restore file with its vector table destroyed fails against a film-mods backup",
                    !vr.ok && level(vr, "vector-table") == UpdateCheck.Level.FAIL
                            && vr.verdict().contains("Program start: the table the processor starts the program from"
                            + " differs from the one in the public dump's build"));
            vr = UpdateCheck.check(named(brokenFilm), brokenFilm, UpdateCheck.PUBLIC_BUILD, read);
            expect("and the film-mods file with its vector table destroyed fails against a public-build backup",
                    !vr.ok && level(vr, "vector-table") == UpdateCheck.Level.FAIL
                            && vr.verdict().contains("differs from the one in the charmera-film-mods author's build"));
            vr = UpdateCheck.check(named(f), f, UpdateCheck.PUBLIC_BUILD, read);
            expect("the film-mods file against a public-build backup: the same table in another program is a warning"
                    + " that says both", vr.ok && vr.compared && level(vr, "sensor-table") == UpdateCheck.Level.WARN
                    && level(vr, "vector-table") == UpdateCheck.Level.PASS
                    && vr.verdict().contains("the program around it differs from that firmware")
                    && vr.verdict().contains("That firmware is the public dump's build, unchanged"));
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

    /** The level of the first line with that check name, or null. */
    static UpdateCheck.Level level(UpdateCheck.Result r, String name) {
        for (UpdateCheck.Line l : r.lines) {
            if (l.name.equals(name)) {
                return l.level;
            }
        }
        return null;
    }

    /** The plain text of the first line with that check name, or "". */
    static String text(UpdateCheck.Result r, String name) {
        for (UpdateCheck.Line l : r.lines) {
            if (l.name.equals(name)) {
                return l.text;
            }
        }
        return "";
    }

    /** An update file's name for d: a stamp no known build carries, and d's own sum. */
    static String named(byte[] d) {
        return String.format(Locale.ROOT, "JH_5408202611061300_%08X.bin", UpdateCheck.byteSum(d));
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
