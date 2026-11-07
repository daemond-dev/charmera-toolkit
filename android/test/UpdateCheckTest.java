import io.github.daemonddev.charmera.UpdateCheck;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Plain-JVM tests for the app's update-file checks. The images are synthetic (no Kodak or Reto firmware in the
 * repository): random code, and a resource pack of 43 small entries of every kind the checker knows. The test builds
 * its own Build descriptor from the synthetic regions. Set CHARMERA_UPDATE_FILES to a list of real update files
 * (separated by the platform's path separator) to also check them against the real build.
 */
public final class UpdateCheckTest {

    private static int failures = 0;
    private static int passes = 0;

    /** name -> {slot offset, slot length, content length}, in directory order. */
    static final Map<String, int[]> SLOTS = new LinkedHashMap<>();
    static final int DIRECTORY = 0x120018;

    public static void main(String[] args) throws Exception {
        byte[] base = synthetic();
        UpdateCheck.Build build = buildFor(base);

        UpdateCheck.Result r = UpdateCheck.check(name(base, "202611050001"), base, build);
        expect("good file passes", r.ok, r);
        expect("card name is the canonical name", name(base, "202611050001").equals(r.cardName), r);
        expect("software reported identical", line(r, "software", UpdateCheck.Level.PASS), r);
        expect("blank settings reported", line(r, "settings", UpdateCheck.Level.INFO), r);
        expect("43 resources", line(r, "resources", UpdateCheck.Level.PASS), r);
        expect("every resource slot passes", line(r, "resource-slots", UpdateCheck.Level.PASS), r);

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
        Arrays.fill(big, base.length, big.length, (byte) 0xFF);
        r = UpdateCheck.check(name(big, "202611050001"), big, build);
        expect("2 MiB file fails", !r.ok && line(r, "size", UpdateCheck.Level.FAIL), r);

        byte[] m = base.clone();
        m[0] = 'X';
        r = check(m, build);
        expect("bad magic fails", !r.ok && line(r, "magic", UpdateCheck.Level.FAIL), r);

        m = base.clone();
        m[0x18964] = 'X';
        r = check(m, build);
        expect("other build string fails", !r.ok && line(r, "series", UpdateCheck.Level.FAIL), r);

        // --- the image sensor: a warning without the camera's backup, a comparison with it ----------------------

        r = check(base, build);
        expect("without a backup the sensor line is a warning that names step 0", r.ok
                && line(r, "sensor-table", UpdateCheck.Level.WARN)
                && text(r, "sensor-table").contains("Choose this camera's backup"), r);
        r = UpdateCheck.check(name(base, "202611050001"), base, build, base);
        expect("against the camera's own backup the same table passes and so does the software", r.ok && r.compared
                && line(r, "sensor-table", UpdateCheck.Level.PASS) && line(r, "software", UpdateCheck.Level.PASS)
                && r.verdict().equals("OK to put on the card. Every check passed."), r);
        m = base.clone();
        m[0xF5C70] ^= 1;
        r = check(m, build);
        expect("a changed sensor table warns without a backup", r.ok && line(r, "sensor-table", UpdateCheck.Level.WARN)
                && text(r, "sensor-table").contains("not the public build's program"), r);
        r = UpdateCheck.check(name(m, "202611050001"), m, build, base);
        expect("a changed sensor table fails against the camera's backup", !r.ok && r.compared
                && line(r, "sensor-table", UpdateCheck.Level.FAIL)
                && r.verdict().contains("different from your camera's backup"), r);
        byte[] otherBackup = new byte[0x200000];
        System.arraycopy(m, 0, otherBackup, 0, m.length);
        Arrays.fill(otherBackup, m.length, otherBackup.length, (byte) 0xFF);
        r = UpdateCheck.check(name(base, "202611050001"), base, build, otherBackup);
        expect("the public file fails against a backup whose sensor table differs", !r.ok
                && line(r, "sensor-table", UpdateCheck.Level.FAIL), r);
        m = base.clone();
        m[0xF5C6C] = 0;   // the init table no longer starts with a page select: no table found
        r = check(m, build);
        expect("no sensor table means not Charmera firmware", !r.ok && line(r, "series", UpdateCheck.Level.FAIL)
                && text(r, "series").contains("set-up table"), r);
        m = base.clone();
        m[0x218D0] = 'X';   // the stamp has no NUL after it: not a stamp string any more
        r = check(m, build);
        expect("no stamp string means not Charmera firmware", !r.ok && line(r, "series", UpdateCheck.Level.FAIL)
                && text(r, "series").contains("version stamp"), r);
        m = base.clone();
        put(m, 0x218C4, "202601010000");
        r = check(m, build);
        expect("another stamp is still a Charmera build mark, since the stamp is no identity", r.ok
                && line(r, "series", UpdateCheck.Level.PASS) && text(r, "series").contains("stamp 202601010000")
                && line(r, "stamp-in-file", UpdateCheck.Level.INFO), r);
        UpdateCheck.Marks marks = UpdateCheck.findMarks(base);
        r = check(base, build);
        expect("the compile date is found by content and shown with the build marks", "Oct 14 2025".equals(marks.compiled)
                && marks.compiledOffset == 0xF3978 && text(r, "series").contains("compiled Oct 14 2025"), r);
        m = base.clone();
        m[0xF3983] = 'X';   // no NUL after the date: not a compile date any more
        expect("a date without its NUL is not a compile date", UpdateCheck.findMarks(m).compiled == null, null);

        // --- the official update size ------------------------------------------------------------------------

        byte[] official = Arrays.copyOf(base, UpdateCheck.OFFICIAL_SIZE);
        r = UpdateCheck.check(name(official, "202611050001"), official, build);
        expect("an official-size file passes: size INFO, final erase WARN, own area INFO, build unchanged", r.ok
                && line(r, "size", UpdateCheck.Level.INFO) && line(r, "final-erase", UpdateCheck.Level.WARN)
                && text(r, "final-erase").contains("factory settings") && line(r, "own-area", UpdateCheck.Level.INFO)
                && line(r, "resource-slots", UpdateCheck.Level.PASS) && line(r, "build", UpdateCheck.Level.INFO)
                && text(r, "build").contains("unchanged") && line(r, "software", UpdateCheck.Level.PASS), r);
        r = UpdateCheck.check(name(official, "202611050001"), official, build, base);
        expect("an official-size file against the backup passes the software and sensor lines", r.ok
                && line(r, "software", UpdateCheck.Level.PASS) && line(r, "sensor-table", UpdateCheck.Level.PASS), r);
        byte[] shorter = Arrays.copyOf(base, 0x160000);
        r = UpdateCheck.check(name(shorter, "202611050001"), shorter, build);
        expect("a file shorter than the official size fails", !r.ok && line(r, "size", UpdateCheck.Level.FAIL), r);
        byte[] between = Arrays.copyOf(base, 0x170000);
        r = UpdateCheck.check(name(between, "202611050001"), between, build);
        expect("a file between the two sizes fails", !r.ok && line(r, "size", UpdateCheck.Level.FAIL), r);

        // --- what the app takes as a backup of the camera ----------------------------------------------------

        String[] noPublic = new String[0];
        expect("an update file is not a backup", UpdateCheck.backupProblem(base, noPublic) != null
                && UpdateCheck.backupProblem(base, noPublic).contains("update file"), null);
        byte[] dump = new byte[0x200000];
        System.arraycopy(base, 0, dump, 0, base.length);
        Arrays.fill(dump, base.length, dump.length, (byte) 0xFF);
        expect("a 2 MiB dump with the build marks is a backup", UpdateCheck.backupProblem(dump, noPublic) == null, null);
        expect("the public dump is not a backup of your camera", UpdateCheck.backupProblem(dump,
                new String[]{UpdateCheck.sha256Hex(dump, 0, dump.length)}) != null, null);
        byte[] blank = new byte[0x200000];
        Arrays.fill(blank, (byte) 0xFF);
        expect("a blank read is not a backup", UpdateCheck.backupProblem(blank, noPublic).contains("does not begin"), null);
        byte[] noMarks = dump.clone();
        noMarks[0xF5C6C] = 0;
        expect("a dump without the marks is not a backup", UpdateCheck.backupProblem(noMarks, noPublic).contains("set-up table"), null);
        expect("nothing is not a backup", UpdateCheck.backupProblem(null, noPublic) != null, null);
        String publicProblem = UpdateCheck.backupProblem(dump, new String[]{UpdateCheck.sha256Hex(dump, 0, dump.length)});
        expect("a read identical to the public dump is refused, and the reason allows that it is this camera's", publicProblem
                .contains("flashed with the public restore file") && publicProblem.contains("not firmware known to fit"), null);

        // --- what kind of mod: bytes 0..0x120000 (header, code, data) against the resources -------------------

        m = base.clone();
        m[slot("SOUND0WAV")[0] + 60] ^= 0x55;   // a PCM byte
        r = check(m, build);
        expect("resource mod warns, passes, says the code area is the public dump's", r.ok
                && line(r, "software", UpdateCheck.Level.WARN)
                && text(r, "software").contains("The program itself is the public dump's")
                && detail(r, "software").contains("(boot header, code and data) match the public dump"), r);
        r = UpdateCheck.check(name(m, "202611050001"), m, build, base);
        expect("against the backup a resource mod says the program is the camera's own", r.ok
                && line(r, "software", UpdateCheck.Level.WARN)
                && text(r, "software").contains("The program itself is your camera's own"), r);

        m = base.clone();
        m[0x62B8] ^= 0x08;
        r = check(m, build);
        expect("code mod warns, passes", r.ok && codeChange(r), r);

        m = base.clone();
        m[0x28] ^= 0x01;   // inside the boot header's register list
        r = check(m, build);
        expect("a boot-header change is reported as a code change", r.ok && codeChange(r), r);

        m = base.clone();
        m[0x100100] ^= 0x01;   // inside .data
        r = check(m, build);
        expect("a .data change is reported as a code change", r.ok && codeChange(r), r);

        m = base.clone();
        m[0x115000] ^= 0x01;   // the zero padding below the pack
        r = check(m, build);
        expect("a change in the padding below the pack is reported as a code change", r.ok && codeChange(r), r);

        // --- resource slots ------------------------------------------------------------------------------------

        m = base.clone();
        m[slot("SOUND1WAV")[0]] = 'X';
        expectSlotFail("WAV whose RIFF magic is broken", m, build, "SOUND1.WAV", "is not a RIFF WAVE");

        m = base.clone();
        putU32(m, slot("SOUND1WAV")[0] + 4, slot("SOUND1WAV")[1]);   // 8 + this > the slot
        expectSlotFail("WAV whose RIFF size is larger than its slot", m, build, "SOUND1.WAV", "RIFF size says");

        m = base.clone();
        put(m, slot("SOUND2WAV")[0] + 36, "dat_");
        expectSlotFail("WAV without a data chunk", m, build, "SOUND2.WAV", "has no data chunk");

        m = base.clone();
        put(m, slot("SOUND2WAV")[0] + 12, "junk");
        expectSlotFail("WAV without a fmt chunk", m, build, "SOUND2.WAV", "has no fmt chunk");

        m = base.clone();
        putU32(m, slot("SOUND3WAV")[0] + 40, 0x7FFFFFF0);
        expectSlotFail("WAV whose data chunk runs past the file", m, build, "SOUND3.WAV", "past the end of the file");

        m = base.clone();
        int[] logo = slot("LOGOJPG");
        Arrays.fill(m, logo[0] + logo[2] / 2, logo[0] + logo[1], (byte) 0xFF);
        expectSlotFail("JPEG cut off half way", m, build, "LOGO.JPG", "no FF D9 end marker");

        m = base.clone();
        m[logo[0] + 1] = 0;
        expectSlotFail("JPEG without FF D8", m, build, "LOGO.JPG", "is not a JPEG");

        m = base.clone();
        Arrays.fill(m, logo[0], logo[0] + logo[1], (byte) 0xFF);
        expectSlotFail("erased boot logo", m, build, "LOGO.JPG", "erased");

        m = base.clone();
        int[] pic = slot("PIC03GPZP");
        m[pic[0] + pic[2] - 8] ^= 1;   // the trailer's CRC
        expectSlotFail("GPZP whose trailer CRC is wrong", m, build, "PIC03.GPZP", "trailer CRC-32");

        m = base.clone();
        m[pic[0] + pic[2] - 4] ^= 1;   // the trailer's length
        expectSlotFail("GPZP whose trailer length is wrong", m, build, "PIC03.GPZP", "trailer length");

        m = base.clone();
        m[pic[0] + 4] = 0x07;   // BFINAL 1, block type 3 (reserved)
        expectSlotFail("GPZP whose stream is corrupt", m, build, "PIC03.GPZP", "corrupt");

        m = base.clone();
        Arrays.fill(m, pic[0] + 4 + (pic[2] - 12) / 2, pic[0] + pic[1], (byte) 0xFF);
        expectSlotFail("GPZP cut off half way", m, build, "PIC03.GPZP", "ends before its final block");

        m = base.clone();
        m[pic[0]] = 'X';
        expectSlotFail("GPZP without its magic", m, build, "PIC03.GPZP", "is not GPZP");

        // A stream that fills its slot so that only half of the trailer fits (1526 literals make 1528 stream bytes).
        m = base.clone();
        byte[] longPayload = new byte[1526];
        for (int i = 0; i < longPayload.length; i++) {
            longPayload[i] = (byte) (i % 140);
        }
        byte[] halfTrailer = Arrays.copyOf(gpzp(fixedDeflate(longPayload), longPayload), slot("PIC07GPZP")[1]);
        expect("fixture: the stream leaves 4 bytes of its slot", halfTrailer.length - 4 - fixedDeflate(longPayload).length == 4, null);
        putSlot(m, "PIC07GPZP", halfTrailer);
        expectSlotFail("GPZP without its trailer", m, build, "PIC07.GPZP", "trailer is missing");

        byte[] payload = picturePayload(8);

        m = base.clone();
        putSlot(m, "PIC08GPZP", gpzp(storedDeflate(payload), payload));
        expectSlotFail("GPZP with a stored block", m, build, "PIC08.GPZP", "uses stored DEFLATE blocks");

        m = base.clone();
        byte[] text = englishText();
        byte[] dyn = rawDeflate(text);
        expect("fixture: the zlib stream starts with a dynamic block", ((dyn[0] >> 1) & 3) == 2, null);
        putSlot(m, "PIC09GPZP", gpzp(dyn, text));
        expectSlotFail("GPZP with a dynamic-Huffman block", m, build, "PIC09.GPZP", "uses dynamic DEFLATE blocks");

        m = base.clone();
        putSlot(m, "PIC10GPZP", jpeg(300));
        expectSlotFail("a JPEG in a GPZP-named slot", m, build, "PIC10.GPZP", "is not GPZP");

        m = base.clone();
        m[slot("TEXTPAK")[0]] = 'X';
        expectSlotFail("PAK without GPRS", m, build, "TEXT.PAK", "is not a GPRS pack");

        m = base.clone();
        put(m, entryOffset("PIC11GPZP"), "PIC11TXT\0\0\0\0\0");
        expectSlotFail("an entry whose name has no known kind", m, build, "PIC11TXT", "no known kind");

        m = base.clone();
        int e12 = entryOffset("PIC12GPZP");
        putU32(m, e12 + 20, (int) ((m[entryOffset("PIC13GPZP") + 20] & 0xFF)
                | (m[entryOffset("PIC13GPZP") + 21] & 0xFF) << 8));   // same sector as the next entry
        expectSlotFail("an entry out of order (empty slot)", m, build, "PIC12.GPZP", "empty slot");

        // The last entry moved past the factory self-test sector: the entry before it now reaches past the pack.
        m = base.clone();
        putU32(m, entryOffset("ICON33GPZ") + 20, (0x16E000 - 0x120000) / 512 + 1);
        r = check(m, build);
        expect("a slot reaching past the resource area fails", !r.ok && text(r, "resource-slots").startsWith("2 of 43")
                && detail(r, "resource-slots").contains("PIC33.GPZP at 0x") && detail(r, "resource-slots").contains(
                "..0x16e200 lies outside the resource area") && detail(r, "resource-slots").contains("ICON33.GPZ at 0x16e200: empty slot"), r);

        m = base.clone();
        putU32(m, entryOffset("SOUND0WAV") + 20, 1);   // offset 0x120200, inside the directory
        expectSlotFail("a slot inside the directory", m, build, "SOUND0.WAV", "outside the resource area");

        // A cut-off input padded with 0xFF, as pack did with a truncated download: every entry from the cut is erased.
        m = base.clone();
        int cut = slot("PIC20GPZP")[0] + 100;
        Arrays.fill(m, cut, 0x170000, (byte) 0xFF);
        r = check(m, build);
        expect("a cut-off pack fails, naming the erased entries", !r.ok && line(r, "resource-slots", UpdateCheck.Level.FAIL)
                && detail(r, "resource-slots").contains("PIC21.GPZP at 0x") && detail(r, "resource-slots").contains("erased")
                && detail(r, "resource-slots").contains("; and 7 more") && text(r, "resource-slots").startsWith("15 of 43"), r);

        // --- settings and photos --------------------------------------------------------------------------------

        m = base.clone();
        putRecord(m, 0x170000, 0);
        r = check(m, build);
        expect("record with erased photo directory fails", !r.ok && line(r, "photo-directory", UpdateCheck.Level.FAIL), r);
        Arrays.fill(m, 0x17F000, 0x17F200, (byte) 0);
        r = check(m, build);
        expect("muted record reads as camera sounds off", r.ok && text(r, "settings").contains("camera sounds off"), r);
        m[0x170000 + 0x10] ^= 1;   // break the CRC
        r = check(m, build);
        expect("bad CRC warns", r.ok && line(r, "settings", UpdateCheck.Level.WARN), r);

        m = base.clone();
        m[0x180000] = (byte) 0xFF;
        m[0x180001] = (byte) 0xD8;
        r = check(m, build);
        expect("stored photo warns", r.ok && line(r, "photos", UpdateCheck.Level.WARN)
                && text(r, "photos").contains("1 photo from the camera it was made from"), r);
        expect("the verdict passes it and points at the warnings", r.verdict().startsWith("OK to put on the card.")
                && r.verdict().contains("Read these 2 warnings first.")
                && r.verdict().contains("Stored photos: the file carries 1 photo from the camera it was made from, but its"
                + " photo list is empty, so your camera will not show it.")
                && r.verdict().contains("copy the SPIDCIM folder off the card"), r);
        m[0x17F002] = 1;        // directory entry 0: used
        putU32(m, 0x17F004, 38478);
        r = check(m, build);
        expect("a listed stored photo is said to be shown", r.ok && text(r, "photos").contains("lists it, so your camera will show it"), r);

        // --- the verdict at the top of the report -------------------------------------------------------------

        r = check(base, build);
        expect("a clean file's verdict without a backup is OK with the sensor warning", r.verdict().startsWith("OK to put"
                + " on the card. Read this warning first. Image sensor set-up: the same program as the public build's"), r);
        r = UpdateCheck.check("JH_5408202611050001_00000000.bin", base, build);
        expect("a failed file's verdict is Not OK with the first reason", r.verdict().startsWith("Not OK to put on the"
                + " card. The app will not write this file. Sum: the file's bytes do not add up"), r);
        m = base.clone();
        m[0] = 'X';
        r = UpdateCheck.check("JH_5408202505201029_00000000.bin", m, build);
        expect("the verdict counts the other problems", r.verdict().startsWith("Not OK to put on the card. The app will"
                + " not write this file. Sum: ") && r.verdict().endsWith(" 2 more problems are listed below."), r);
        r = UpdateCheck.check("firmware.bin", base, build);
        expect("a nameless file's verdict says what to do", r.verdict().contains("Name: this is not an update file's"
                + " name") && r.verdict().contains("Do not make up a name")
                && r.verdict().contains("tap \"Download the stock restore file\" instead."), r);

        // --- the write gate: Write uses exactly the checked bytes ---------------------------------------------

        UpdateCheck.Result good = check(base, build);
        expect("confirms the bytes it checked", UpdateCheck.confirms(good, base) == null, good);
        m = base.clone();
        m[0x150000] ^= 1;
        expect("refuses other bytes of the same size", UpdateCheck.confirms(good, m) != null, good);
        expect("refuses bytes of another size", UpdateCheck.confirms(good, Arrays.copyOf(base, base.length - 1)) != null, good);
        UpdateCheck.Result bad = UpdateCheck.check("JH_5408202611050001_00000000.bin", base, build);
        expect("refuses a file that failed its checks", UpdateCheck.confirms(bad, base) != null, bad);
        UpdateCheck.Result nameless = UpdateCheck.check("firmware.bin", base, build);
        expect("refuses a file without a card name", UpdateCheck.confirms(nameless, base) != null, nameless);
        expect("refuses no file", UpdateCheck.confirms(null, base) != null && UpdateCheck.confirms(good, null) != null, good);

        String film = System.getenv("CHARMERA_FILM_MODS_RESTORE");
        if (film != null && !film.isEmpty()) {
            File f = new File(film);
            byte[] d = Files.readAllBytes(f.toPath());
            r = UpdateCheck.check(f.getName(), d);
            expect("the film-mods restore file is named by its fingerprint and refused for its stamp", !r.ok
                    && line(r, "build", UpdateCheck.Level.WARN) && text(r, "build").contains("charmera-film-mods")
                    && line(r, "stamp", UpdateCheck.Level.FAIL) && text(r, "series").contains("compiled Nov 19 2025"), r);
        }

        String real = System.getenv("CHARMERA_UPDATE_FILES");
        if (real != null && !real.isEmpty()) {
            for (String p : real.split(File.pathSeparator)) {
                File f = new File(p);
                byte[] d = Files.readAllBytes(f.toPath());
                r = UpdateCheck.check(f.getName(), d);
                System.out.println("--- " + f.getName() + "\n" + r.verdict() + "\n\n" + r.report());
                expect("real file " + f.getName() + " passes against the real build", r.ok, r);
            }
        }

        System.out.printf(Locale.ROOT, "UpdateCheckTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // --- synthetic image ---------------------------------------------------------------------------------------

    static byte[] synthetic() {
        byte[] d = new byte[UpdateCheck.UPDATE_SIZE];
        new Random(5408).nextBytes(d);
        put(d, 0, "SPII");
        // the build marks, shaped as the content finder expects them: NUL-terminated strings, and a sensor program
        // (reset table, a 61-write init table with a page select first and one more page switch, the code literal)
        d[0x218C3] = 0;
        put(d, 0x218C4, "202505201029");
        d[0x218D0] = 0;
        put(d, 0x18964, "JH5408  20250520");
        d[0x18974] = 0;
        put(d, 0xF3978, "Oct 14 2025");
        d[0xF3983] = 0;
        System.arraycopy(UpdateCheck.SENSOR_RESET_TABLE, 0, d, 0xF5BFC, 6);
        System.arraycopy(SENSOR_TABLE, 0, d, 0xF5C6C, SENSOR_TABLE.length);
        putU32(d, 0x7B91C, 0xF5C6C - 0x200);
        // The resource pack: GP+, a directory of 43 entries, then each entry's content padded with 0xFF to its slot.
        Arrays.fill(d, 0x120000, 0x170000, (byte) 0xFF);
        Arrays.fill(d, 0x120000, 0x120018, (byte) 0);
        put(d, 0x120000, "GP+");
        List<String> names = new ArrayList<>();
        List<byte[]> contents = new ArrayList<>();
        List<Integer> slotSizes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            names.add("SOUND" + i + "WAV");
            contents.add(wav(800 + 100 * i));
            slotSizes.add(1536);
        }
        names.add("LOGOJPG");
        contents.add(jpeg(900));
        slotSizes.add(1024);
        names.add("BGJPG");
        contents.add(jpeg(400));
        slotSizes.add(512);
        names.add("TEXTPAK");
        byte[] pak = new byte[1500];
        new Random(1).nextBytes(pak);
        put(pak, 0, "GPRS");
        contents.add(pak);
        slotSizes.add(2048);
        for (int i = 0; i < 34; i++) {
            names.add(String.format(Locale.ROOT, "PIC%02dGPZP", i));
            byte[] p = picturePayload(i);
            contents.add(gpzp(fixedDeflate(p), p));
            slotSizes.add(1536);
        }
        names.add("ICON33GPZ");   // the last entry, named like U_DISK.GPZ
        byte[] icon = picturePayload(99);
        contents.add(gpzp(fixedDeflate(icon), icon));
        slotSizes.add(0);   // runs to 0x16E000

        SLOTS.clear();
        int at = 0x120600;
        for (int i = 0; i < names.size(); i++) {
            int dir = DIRECTORY + 24 * i;
            Arrays.fill(d, dir, dir + 24, (byte) 0);
            put(d, dir, names.get(i));
            putU32(d, dir + 20, (at - 0x120000) / 512);
            byte[] c = contents.get(i);
            int size = slotSizes.get(i) == 0 ? 0x16E000 - at : slotSizes.get(i);
            if (c.length > size) {
                throw new IllegalStateException(names.get(i) + " does not fit its slot");
            }
            System.arraycopy(c, 0, d, at, c.length);
            SLOTS.put(names.get(i), new int[]{at, size, c.length});
            at += size;
        }
        Arrays.fill(d, DIRECTORY + 24 * names.size(), DIRECTORY + 24 * (names.size() + 1), (byte) 0);
        // settings bank, photo directory and photo slots blank, as pack --blank-settings --blank-photos leaves them
        Arrays.fill(d, 0x170000, d.length, (byte) 0xFF);
        return d;
    }

    /** A 61-write init table: page select 0 first, one more page switch, the ff ff marker; 124 bytes like the real one. */
    static final byte[] SENSOR_TABLE = sensorTable();

    static byte[] sensorTable() {
        byte[] t = new byte[124];
        t[0] = (byte) 0xFD;
        t[1] = 0;
        for (int i = 0; i < 58; i++) {
            t[2 + 2 * i] = (byte) (0x20 + i);
            t[3 + 2 * i] = (byte) ((0x20 + i) * 3);
        }
        t[118] = (byte) 0xFD;
        t[119] = 1;
        t[120] = 0x11;
        t[121] = (byte) 0x80;
        t[122] = (byte) 0xFF;
        t[123] = (byte) 0xFF;
        return t;
    }

    static UpdateCheck.Build buildFor(byte[] d) {
        return new UpdateCheck.Build("synthetic test build", "202505201029", UpdateCheck.sha256Hex(d, 0, 0x170000),
                UpdateCheck.sha256Hex(d, 0, 0x120000), UpdateCheck.sha256Hex(d, 0, UpdateCheck.OFFICIAL_SIZE),
                UpdateCheck.sha256Hex(d, 0xF5C6C, 0xF5CE8), 0x218C4, 0x18964, 0xF5C6C);
    }

    static int[] slot(String name) {
        return SLOTS.get(name);
    }

    static int entryOffset(String name) {
        int i = new ArrayList<>(SLOTS.keySet()).indexOf(name);
        return DIRECTORY + 24 * i;
    }

    /** Content into an entry's slot, 0xFF after it, as frames and resources replace do. */
    static void putSlot(byte[] d, String name, byte[] content) {
        int[] s = slot(name);
        if (content.length > s[1]) {
            throw new IllegalStateException(name + ": " + content.length + " bytes do not fit the slot of " + s[1]);
        }
        Arrays.fill(d, s[0], s[0] + s[1], (byte) 0xFF);
        System.arraycopy(content, 0, d, s[0], content.length);
    }

    /** A mono 8-bit 8000 Hz WAV: RIFF, fmt, data, as the stock sounds are laid out. */
    static byte[] wav(int samples) {
        byte[] w = new byte[44 + samples];
        put(w, 0, "RIFF");
        putU32(w, 4, 36 + samples);
        put(w, 8, "WAVEfmt ");
        putU32(w, 16, 16);
        w[20] = 1;   // PCM
        w[22] = 1;   // mono
        putU32(w, 24, 8000);
        putU32(w, 28, 8000);
        w[32] = 1;
        w[34] = 8;
        put(w, 36, "data");
        putU32(w, 40, samples);
        for (int i = 0; i < samples; i++) {
            w[44 + i] = (byte) (0x80 + (i % 32) - 16);
        }
        return w;
    }

    static byte[] jpeg(int len) {
        byte[] j = new byte[len];
        for (int i = 0; i < len; i++) {
            j[i] = (byte) (0x11 + i % 0x60);
        }
        j[0] = (byte) 0xFF;
        j[1] = (byte) 0xD8;
        j[len - 2] = (byte) 0xFF;
        j[len - 1] = (byte) 0xD9;
        return j;
    }

    static byte[] picturePayload(int seed) {
        byte[] p = new byte[600];
        for (int i = 0; i < p.length; i++) {
            p[i] = (byte) ((i * 7 + seed * 13) % 140);   // literals below 144: 8-bit fixed codes
        }
        return p;
    }

    static byte[] gpzp(byte[] stream, byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload, 0, payload.length);
        byte[] trailer = new byte[8];
        putU32(trailer, 0, (int) crc.getValue());
        putU32(trailer, 4, payload.length);
        return concat(concat(ascii("GPZP"), stream), trailer);
    }

    /** One final fixed-Huffman block of literals only, then end of block: what the stock encoder's blocks are. */
    static byte[] fixedDeflate(byte[] payload) {
        BitWriter w = new BitWriter();
        w.lsb(1, 1);   // BFINAL
        w.lsb(1, 2);   // BTYPE 01, fixed Huffman
        for (byte b : payload) {
            int v = b & 0xFF;
            if (v < 144) {
                w.msb(0x30 + v, 8);
            } else {
                w.msb(0x190 + v - 144, 9);
            }
        }
        w.msb(0, 7);   // 256, end of block
        return w.bytes();
    }

    /** One final stored block. */
    static byte[] storedDeflate(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);   // BFINAL 1, BTYPE 00, then padding to the byte
        out.write(payload.length & 0xFF);
        out.write(payload.length >> 8);
        out.write(~payload.length & 0xFF);
        out.write((~payload.length >> 8) & 0xFF);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    static byte[] rawDeflate(byte[] payload) {
        Deflater def = new Deflater(9, true);
        def.setInput(payload);
        def.finish();
        byte[] buf = new byte[payload.length + 64];
        int n = def.deflate(buf);
        def.end();
        return Arrays.copyOf(buf, n);
    }

    static byte[] englishText() {
        StringBuilder sb = new StringBuilder();
        String[] words = {"camera", "update", "flash", "card", "boot", "screen", "photo", "frame", "sound", "menu",
                "battery", "power", "yellow", "series", "settings", "record", "shutter", "volume", "picture", "check"};
        Random rnd = new Random(42);
        while (sb.length() < 1800) {
            sb.append(words[rnd.nextInt(words.length)]).append(rnd.nextInt(5) == 0 ? ".\n" : " ");
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    static final class BitWriter {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int cur;
        int n;

        void bit(int b) {
            cur |= (b & 1) << n;
            if (++n == 8) {
                out.write(cur);
                cur = 0;
                n = 0;
            }
        }

        void lsb(int v, int bits) {
            for (int i = 0; i < bits; i++) {
                bit(v >> i);
            }
        }

        void msb(int v, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                bit(v >> i);
            }
        }

        byte[] bytes() {
            if (n > 0) {
                out.write(cur);
            }
            return out.toByteArray();
        }
    }

    static void putRecord(byte[] d, int off, int uiSound) {
        Arrays.fill(d, off, off + 0x200, (byte) 0);
        d[off] = 1;
        d[off + 0x05] = 5;
        d[off + 0x19] = (byte) uiSound;
        CRC32 crc = new CRC32();
        crc.update(d, off, 252);
        putU32(d, off + 252, (int) crc.getValue());
    }

    static void put(byte[] d, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, d, off, b.length);
    }

    static void putU32(byte[] d, int off, int v) {
        for (int i = 0; i < 4; i++) {
            d[off + i] = (byte) (v >>> (8 * i));
        }
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    static String name(byte[] d, String stamp) {
        return String.format(Locale.ROOT, "JH_5408%s_%08X.bin", stamp, UpdateCheck.byteSum(d));
    }

    static UpdateCheck.Result check(byte[] d, UpdateCheck.Build build) {
        return UpdateCheck.check(name(d, "202611050001"), d, build);
    }

    // --- assertions --------------------------------------------------------------------------------------------

    static void expectSlotFail(String what, byte[] m, UpdateCheck.Build build, String entry, String text) {
        UpdateCheck.Result r = check(m, build);
        String d = detail(r, "resource-slots");
        expect(what + " fails resource-slots", !r.ok && line(r, "resource-slots", UpdateCheck.Level.FAIL)
                && text(r, "resource-slots").startsWith("1 of 43 are damaged.")
                && d.startsWith(entry + " at ") && d.contains(text), r);
    }

    static boolean codeChange(UpdateCheck.Result r) {
        return line(r, "software", UpdateCheck.Level.WARN) && text(r, "software").contains("program differs from the public dump's")
                && detail(r, "software").contains("(boot header, code and data) differ from the public dump");
    }

    static boolean line(UpdateCheck.Result r, String name, UpdateCheck.Level level) {
        for (UpdateCheck.Line l : r.lines) {
            if (l.name.equals(name) && l.level == level) {
                return true;
            }
        }
        return false;
    }

    /** The technical detail of the first line with that check name. */
    static String detail(UpdateCheck.Result r, String name) {
        for (UpdateCheck.Line l : r.lines) {
            if (l.name.equals(name)) {
                return l.detail;
            }
        }
        return "";
    }

    /** The plain text of the first line with that check name. */
    static String text(UpdateCheck.Result r, String name) {
        for (UpdateCheck.Line l : r.lines) {
            if (l.name.equals(name)) {
                return l.text;
            }
        }
        return "";
    }

    /**
     * What the owner reads first stays plain: no offsets and no check names in a title, a text or the verdict; those
     * belong in the detail. Every line has a title and a text.
     */
    static String unplain(UpdateCheck.Result r) {
        List<String> names = new ArrayList<>();
        for (UpdateCheck.Line l : r.lines) {
            names.add(l.name);
        }
        List<String> shown = new ArrayList<>();
        for (UpdateCheck.Line l : r.lines) {
            if (l.title.isEmpty() || l.text.isEmpty()) {
                return "an empty title or text: " + l;
            }
            shown.add(l.title + ": " + l.text);
        }
        shown.add(r.verdict());
        for (String s : shown) {
            if (s.contains("0x")) {
                return "an offset outside the detail: " + s;
            }
            for (String n : names) {
                if (n.contains("-") && s.contains(n)) {
                    return "the check name " + n + " outside the detail: " + s;
                }
            }
        }
        return null;
    }

    static void expect(String what, boolean ok, UpdateCheck.Result r) {
        String rough = r == null ? null : unplain(r);
        if (ok && rough == null) {
            passes++;
        } else {
            failures++;
            System.out.println("FAILED: " + what + (rough == null ? "" : " (" + rough + ")") + "\n"
                    + (r == null ? "" : r.verdict() + "\n\n" + r.report()));
        }
    }
}
