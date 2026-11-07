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
                && text(r, "sensor-table").contains("Choose this camera's backup")
                && text(r, "sensor-table").contains("the likely cause is a sensor it does not drive"), r);
        // A backup whose firmware is a known build may be a read taken after a flash with it: a match is a warning.
        r = UpdateCheck.check(name(base, "202611050001"), base, build, chip(base));
        expect("against a backup of a known build the same table is a warning, and the software passes", r.ok
                && r.compared && line(r, "sensor-table", UpdateCheck.Level.WARN)
                && text(r, "sensor-table").contains("only if the backup was read before the camera was ever flashed")
                && text(r, "sensor-table").contains("That firmware is synthetic test build, unchanged")
                && line(r, "software", UpdateCheck.Level.PASS) && line(r, "vector-table", UpdateCheck.Level.PASS)
                && r.verdict().startsWith("OK to put on the card. Read this warning first. Image sensor set-up: the same"
                + " as in the firmware in your backup."), r);
        expect("a check names the backup it was made against by SHA-256", chip(base).length == 0x200000
                && UpdateCheck.sha256Hex(chip(base), 0, 0x200000).equals(r.referenceSha256)
                && check(base, build).referenceSha256 == null, r);
        // The camera's own build, which no public image holds: the same table and program pass.
        byte[] own = base.clone();
        own[0x62B8] ^= 0x08;
        r = UpdateCheck.check(name(own, "202611050001"), own, build, chip(own));
        expect("against a backup of a build the app does not know, the same table and software pass", r.ok && r.compared
                && line(r, "sensor-table", UpdateCheck.Level.PASS) && line(r, "software", UpdateCheck.Level.PASS)
                && text(r, "sensor-table").contains("the same as in the firmware in your backup")
                && !text(r, "sensor-table").contains("your camera's own firmware")
                && r.verdict().equals("OK to put on the card. Every check passed."), r);
        r = UpdateCheck.check(name(base, "202611050001"), base, build, chip(own));
        expect("the same table with another program around it is a warning that says so", r.ok && r.compared
                && line(r, "sensor-table", UpdateCheck.Level.WARN)
                && text(r, "sensor-table").contains("the program around it differs from that firmware")
                && text(r, "sensor-table").contains("a different program can drive the same sensor differently")
                && !text(r, "sensor-table").contains("drives the image sensor the way that firmware does")
                && line(r, "software", UpdateCheck.Level.WARN)
                && text(r, "software").contains("differs from the firmware in your backup"), r);
        // Against a backup of a known build, another program around the same table: both are said.
        r = UpdateCheck.check(name(own, "202611050001"), own, build, chip(base));
        expect("against a backup of a known build, another program around the same table is a warning that says both",
                r.ok && r.compared && line(r, "sensor-table", UpdateCheck.Level.WARN)
                        && text(r, "sensor-table").contains("the program around it differs from that firmware")
                        && text(r, "sensor-table").contains("That firmware is synthetic test build, unchanged")
                        && text(r, "sensor-table").contains("only if the backup was read before the camera was ever"
                        + " flashed"), r);
        m = base.clone();
        m[0xF5C70] ^= 1;
        r = check(m, build);
        expect("a changed sensor table warns without a backup", r.ok && line(r, "sensor-table", UpdateCheck.Level.WARN)
                && text(r, "sensor-table").contains("not the public build's program"), r);
        expect("a file laid out like a known build with another sensor table is another build, not a changed copy",
                line(r, "build", UpdateCheck.Level.WARN) && text(r, "build").contains("a build the app does not know, laid"
                + " out like synthetic test build") && !text(r, "build").contains("with changes"), r);
        r = UpdateCheck.check(name(m, "202611050001"), m, build, chip(base));
        expect("a changed sensor table fails against the camera's backup", !r.ok && r.compared
                && line(r, "sensor-table", UpdateCheck.Level.FAIL)
                && r.verdict().contains("different from the firmware in your backup")
                && text(r, "sensor-table").contains("section D"), r);
        byte[] otherBackup = chip(m);
        r = UpdateCheck.check(name(base, "202611050001"), base, build, otherBackup);
        expect("the public file fails against a backup whose sensor table differs", !r.ok
                && line(r, "sensor-table", UpdateCheck.Level.FAIL), r);
        byte[] noTable = chip(own);
        noTable[0xF5BFC] = 0;   // no reset table: the backup has no sensor table the app can find
        r = UpdateCheck.check(name(own, "202611050001"), own, build, noTable);
        expect("a backup without a sensor table fails the comparison", !r.ok && !r.compared
                && line(r, "sensor-table", UpdateCheck.Level.FAIL)
                && text(r, "sensor-table").contains("has no image sensor set-up table"), r);

        // --- which table is the sensor program: the one the code points at by an aligned literal -------------

        m = base.clone();
        putU32(m, 0x7B91C, 0);                 // the literal gone: no code refers to the table
        expect("without its literal the table is found but not referenced", UpdateCheck.findMarks(m).sensorStart == 0xF5C6C
                && UpdateCheck.findMarks(m).referencedFrom == -1, null);
        r = check(m, build);
        expect("an unreferenced table warns without a backup and says so", r.ok
                && line(r, "sensor-table", UpdateCheck.Level.WARN)
                && text(r, "sensor-table").contains("No code in the file refers to this table"), r);
        r = UpdateCheck.check(name(m, "202611050001"), m, build, chip(own));
        expect("an unreferenced table fails against a backup whose table is referenced", !r.ok
                && line(r, "sensor-table", UpdateCheck.Level.FAIL)
                && text(r, "sensor-table").contains("no code in the file refers to its image-sensor table"), r);
        putU32(m, 0x7B91D, 0xF5C6C - 0x200);   // the same literal one byte off: code keeps its literals aligned
        expect("a literal at an unaligned offset is no code reference", UpdateCheck.findMarks(m).referencedFrom == -1, null);
        m = base.clone();
        sensorPair(m, 0xE0000, SENSOR_TABLE, -1);
        UpdateCheck.Marks decoy = UpdateCheck.findMarks(m);
        expect("an unreferenced table found first loses to the referenced one", decoy.sensorStart == 0xF5C6C
                && decoy.referencedFrom == 0x7B91C && decoy.resetStart == 0xF5BFC && !decoy.severalPrograms(), null);
        m = base.clone();
        sensorPair(m, 0xE0000, SENSOR_TABLE, 0x7C000);
        expect("two referenced copies of the same table are one program", !UpdateCheck.findMarks(m).severalPrograms()
                && UpdateCheck.findMarks(m).referencedTables.size() == 2, null);
        byte[] secondTable = SENSOR_TABLE.clone();
        secondTable[3] ^= 1;
        m = base.clone();
        sensorPair(m, 0xE0000, secondTable, 0x7C000);
        r = check(m, build);
        expect("two referenced tables that differ are several sensor programs, a FAIL", !r.ok
                && UpdateCheck.findMarks(m).severalPrograms() && line(r, "sensor-table", UpdateCheck.Level.FAIL)
                && text(r, "sensor-table").contains("more than one image-sensor program")
                && detail(r, "sensor-table").contains("several sensor programs"), r);
        r = UpdateCheck.check(name(own, "202611050001"), own, build, chip(m));
        expect("a backup with several sensor programs cannot be compared with", !r.ok && !r.compared
                && line(r, "sensor-table", UpdateCheck.Level.FAIL)
                && text(r, "sensor-table").contains("the backup holds more than one"), r);
        r = UpdateCheck.check(name(m, "202611050001"), m, build, chip(own));
        expect("nor is a file with several sensor programs compared with a backup", !r.ok && !r.compared
                && line(r, "sensor-table", UpdateCheck.Level.FAIL)
                && text(r, "sensor-table").contains("the file holds more than one"), r);
        m = base.clone();
        m[0x218D0] = 'X';                      // no stamp: the build marks are not all there
        r = UpdateCheck.check(name(m, "202611050001"), m, build, chip(own));
        expect("nor is a file without all the build marks, although a backup is given", !r.ok && !r.compared
                && line(r, "series", UpdateCheck.Level.FAIL) && text(r, "sensor-table").isEmpty(), r);

        // --- the stamp: a *.bin before it is preferred, several runs without one are refused, and so are several
        // different stamps after *.bin, as in image.py find_marks ------------------------------------------------

        m = base.clone();
        m[0xFFFF] = 'x';
        put(m, 0x10000, "202601010101");
        m[0x1000C] = 0;                        // a second 12-digit run, before the real stamp
        m[0x30000] = 'x';
        put(m, 0x30001, "202601010202");
        m[0x3000D] = 0;                        // and a third, after it
        expect("three stamp runs and no *.bin before any: no stamp, so not Charmera firmware",
                UpdateCheck.findMarks(m).stamp == null && UpdateCheck.findMarks(m).stampConflict.isEmpty()
                        && !check(m, build).ok && line(check(m, build), "series", UpdateCheck.Level.FAIL), check(m, build));
        put(m, 0x218C4 - 8, "*.bin");
        m[0x218C4 - 3] = 0;
        UpdateCheck.Marks withBin = UpdateCheck.findMarks(m);
        expect("the run after *.bin is the stamp, with another run before it and one after it",
                "202505201029".equals(withBin.stamp) && withBin.stampOffset == 0x218C4 && withBin.stampConflict.isEmpty()
                        && check(m, build).ok, check(m, build));
        byte[] two = m.clone();
        put(two, 0x10000 - 8, "*.bin");
        two[0x10000 - 3] = 0;                  // a *.bin before the first run too: two different stamps follow one
        UpdateCheck.Marks conflicting = UpdateCheck.findMarks(two);
        r = check(two, build);
        expect("two different stamps after *.bin: no stamp, since which one the camera compares cannot be told",
                conflicting.stamp == null && conflicting.stampOffset == -1
                        && conflicting.stampConflict.equals(Arrays.asList("202601010101", "202505201029"))
                        && !r.ok && line(r, "series", UpdateCheck.Level.FAIL)
                        && text(r, "series").contains("the version stamp. 2 different stamps follow the \"*.bin\" the"
                        + " updater looks for (202601010101, 202505201029), so which one the camera compares cannot be"
                        + " told.") && detail(r, "series").contains("stamps after *.bin: 202601010101, 202505201029"), r);
        String twoStamps = UpdateCheck.backupProblem(chip(two), new String[0]);
        expect("nor is a dump with them a backup", twoStamps != null && twoStamps.endsWith("not a complete dump of the"
                + " chip. 2 different stamps follow the \"*.bin\" the updater looks for (202601010101, 202505201029), so"
                + " which one the camera compares cannot be told"), null);
        put(two, 0x10000, "202505201029");     // the same stamp after both
        UpdateCheck.Marks agreeing = UpdateCheck.findMarks(two);
        expect("the same stamp after *.bin twice is the stamp, the first one", "202505201029".equals(agreeing.stamp)
                && agreeing.stampOffset == 0x10000 && agreeing.stampConflict.isEmpty(), null);

        // --- the stamp of the firmware in the backup -----------------------------------------------------------

        byte[] stamped = own.clone();
        put(stamped, 0x218C4, "202509300000");   // a camera on another stamp, from an update
        r = UpdateCheck.check(name(own, "202509300000"), own, build, chip(stamped));
        expect("a name with the stamp of the firmware in the backup fails: that camera would skip the file", !r.ok
                && line(r, "stamp-camera", UpdateCheck.Level.FAIL) && line(r, "stamp", UpdateCheck.Level.PASS), r);
        r = UpdateCheck.check(name(own, "202611050001"), own, build, chip(stamped));
        expect("a name with another stamp passes against it", line(r, "stamp-camera", UpdateCheck.Level.PASS), r);
        r = UpdateCheck.check(name(own, "202611050001"), own, build, chip(own));
        expect("a backup with the known builds' stamp adds no line: the stamp line covers it",
                detail(r, "stamp-camera").isEmpty() && text(r, "stamp-camera").isEmpty(), r);

        // --- the program's start: the ARM vector table ------------------------------------------------------

        m = base.clone();
        for (int i = 0x200; i < 0x240; i++) {
            m[i] ^= (byte) 0xFF;
        }
        r = check(m, build);
        expect("a destroyed vector table fails without a backup, against the build whose layout the file has", !r.ok
                && line(r, "vector-table", UpdateCheck.Level.FAIL) && text(r, "vector-table").contains("synthetic test"
                + " build") && text(r, "vector-table").contains("only a chip programmer"), r);
        r = UpdateCheck.check(name(m, "202611050001"), m, build, chip(own));
        expect("and against the backup", !r.ok && line(r, "vector-table", UpdateCheck.Level.FAIL), r);
        byte[] elsewhere = base.clone();
        elsewhere[0x18964] = 'X';
        put(elsewhere, 0x18A00, "JH5408  20250520");
        elsewhere[0x18A10] = 0;                // the build string elsewhere: another build's layout
        // A backup laid out like another build does not let a broken table through: the file is then compared with
        // the known build whose layout it has, as it is without a backup.
        r = UpdateCheck.check(name(m, "202611050001"), m, build, chip(elsewhere));
        expect("a destroyed vector table fails against a backup laid out like another build, by the known build whose"
                + " layout the file has", !r.ok && line(r, "vector-table", UpdateCheck.Level.FAIL)
                && text(r, "vector-table").contains("differs from the one in synthetic test build")
                && text(r, "vector-table").contains("and from the one in your backup")
                && r.verdict().startsWith("Not OK to put on the card. The app will not write this file. Program start:"), r);
        byte[] otherStart = elsewhere.clone();
        otherStart[0x210] ^= 1;                // and another vector table
        r = UpdateCheck.check(name(base, "202611050001"), base, build, chip(otherStart));
        expect("an intact vector table passes against it, by the known build whose layout the file has", r.ok
                && line(r, "vector-table", UpdateCheck.Level.PASS)
                && text(r, "vector-table").contains("the same as in synthetic test build, whose layout the file has")
                && text(r, "vector-table").contains("not laid out like the firmware in your backup"), r);
        r = check(elsewhere, build);
        expect("a file laid out like no known build: the vector table is not compared, a warning", r.ok
                && line(r, "vector-table", UpdateCheck.Level.WARN) && line(r, "build", UpdateCheck.Level.WARN)
                && text(r, "build").contains("a build the app does not know: not the public dump's"), r);
        r = UpdateCheck.check(name(elsewhere, "202611050001"), elsewhere, build, chip(own));
        expect("against a backup laid out otherwise whose table it has, it passes: the same as the backup's", r.ok
                && line(r, "vector-table", UpdateCheck.Level.PASS)
                && text(r, "vector-table").contains("the same as in your backup"), r);
        r = UpdateCheck.check(name(otherStart, "202611050001"), otherStart, build, chip(own));
        expect("with another table, laid out like neither the backup nor a known build, it is a warning", r.ok
                && line(r, "vector-table", UpdateCheck.Level.WARN) && text(r, "vector-table").contains("not laid out"
                + " like the firmware in your backup, nor like a build the app knows"), r);
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
        expect("but a file laid out like a known build with another stamp is not that build with changes, as in"
                + " image.py", line(r, "build", UpdateCheck.Level.WARN) && text(r, "build").contains("a build the app"
                + " does not know, laid out like synthetic test build: its stamp is not that build's.")
                && !text(r, "build").contains("with changes")
                && detail(r, "build").contains("the stamp is 202601010000, that build's 202505201029"), r);
        UpdateCheck.Marks marks = UpdateCheck.findMarks(base);
        r = check(base, build);
        expect("the compile date is found by content and shown with the build marks", "Oct 14 2025".equals(marks.compiled)
                && marks.compiledOffset == 0xF3978 && text(r, "series").contains("compiled Oct 14 2025"), r);
        m = base.clone();
        m[0xF3983] = 'X';   // no NUL after the date: not a compile date any more
        expect("a date without its NUL is not a compile date", UpdateCheck.findMarks(m).compiled == null, null);

        // --- the source folder: a build mark that shows another build, never the same one or a fit -------------

        expect("the source folder is found by content and shown with the build marks, with what it shows",
                "v006".equals(marks.sourceTree) && marks.sourceTrees.size() == 1 && marks.sourceTrees.get("v006") == 3
                        && text(r, "series").contains(", source folder v006, stamp")
                        && text(r, "series").contains("A different source folder shows a different build, the same one"
                        + " does not show the same build, and neither says whether the file fits your camera.")
                        && detail(r, "series").contains("source folder at 0x3a4d3, named in 3 source paths"), r);
        expect("it is reported where its name begins, as image.py reports it, not where \\2247\\5408\\ does",
                marks.sourceTreeOffset == 0x3A4D3 && "v006".equals(new String(base, marks.sourceTreeOffset, 4,
                        StandardCharsets.US_ASCII)) && "\\2247\\5408\\".equals(new String(base,
                        marks.sourceTreeOffset - 11, 11, StandardCharsets.US_ASCII)), null);
        m = base.clone();
        sourcePath(m, 0x50000, "v007", "host\\init.c");
        m[0x50200] = 0;
        put(m, 0x50201, "G:\\code\\2247\\5408\\v009");
        m[0x50201 + 22] = 0;                   // no backslash after the folder's name: not a source path
        UpdateCheck.Marks minority = UpdateCheck.findMarks(m);
        r = check(m, build);
        expect("a folder fewer paths name is not the source folder, and a name without a backslash after it is none",
                "v006".equals(minority.sourceTree) && minority.sourceTrees.size() == 2
                        && minority.sourceTrees.get("v007") == 1 && !minority.sourceTrees.containsKey("v009")
                        && detail(r, "series").contains(", v007 in 1 path"), r);
        sourcePath(m, 0x51000, "v007", "host\\os_wrapper\\MicroC\\rtos.c");
        sourcePath(m, 0x52000, "v007", "host\\netstack_wrapper\\icomm_lwIP\\netstack.c");
        expect("on a tie the folder found first is the source folder", "v006".equals(UpdateCheck.findMarks(m).sourceTree)
                && UpdateCheck.findMarks(m).sourceTrees.get("v007") == 3, null);
        sourcePath(m, 0x53000, "v007", "host\\tcpip\\lwip-1.4.0\\src\\core\\tcp.c");
        r = check(m, build);
        expect("a file laid out like a known build from another source folder is another build, not a changed copy",
                "v007".equals(UpdateCheck.findMarks(m).sourceTree) && r.ok && line(r, "build", UpdateCheck.Level.WARN)
                        && text(r, "build").contains("laid out like synthetic test build: its source folder is not that"
                        + " build's.") && !text(r, "build").contains("with changes")
                        && text(r, "series").contains("source folder v007")
                        && detail(r, "build").contains("the source folder is v007, that build's v006"), r);
        // A folder's name is 1 to 64 bytes from 0x20 to 0x7E other than the backslash, as in image.py; the name
        // begins 19 bytes after the offset sourcePath is given.
        m = base.clone();
        for (int at : new int[]{0x50000, 0x51000, 0x52000, 0x53000}) {
            sourcePath(m, at, "v 0~6", "host\\init.c");
        }
        expect("a space and a tilde may stand in a folder's name", "v 0~6".equals(UpdateCheck.findMarks(m).sourceTree)
                && UpdateCheck.findMarks(m).sourceTrees.get("v 0~6") == 4
                && UpdateCheck.findMarks(m).sourceTreeOffset == 0x50000 + 19, null);
        m = base.clone();
        sourcePath(m, 0x50000, "v0\u007f6", "host\\init.c");
        sourcePath(m, 0x51000, "v0\u001f6", "host\\init.c");
        sourcePath(m, 0x52000, "v0#6", "host\\init.c");
        m[0x52000 + 19 + 2] = (byte) 0xE9;     // the # as a byte above 0x7E
        expect("a byte below 0x20 or above 0x7E makes a name no folder", UpdateCheck.findMarks(m).sourceTrees.keySet()
                .equals(java.util.Collections.singleton("v006")), null);
        // Each path is looked for after the backslash that ends the one before, as image.py's regular expression is:
        // here the second \2247\5408\ starts at the backslash that ends v006's path.
        m = base.clone();
        String nested = "G:\\code\\2247\\5408\\v006\\2247\\5408\\v007\\init.c";
        m[0x50000] = 0;
        put(m, 0x50001, nested);
        m[0x50001 + nested.length()] = 0;
        expect("a path that starts inside the one before it is not counted", UpdateCheck.findMarks(m).sourceTrees.get(
                "v006") == 4 && !UpdateCheck.findMarks(m).sourceTrees.containsKey("v007"), null);
        m = base.clone();
        for (int at : new int[]{0x3A4C8, 0x3C448, 0x46608}) {
            m[at + 1] = '3';                   // \3247\ in place of \2247\: no source path left
        }
        r = check(m, build);
        expect("a file whose paths name no source folder shows none, and is not a changed copy of a build that has one",
                UpdateCheck.findMarks(m).sourceTree == null && UpdateCheck.findMarks(m).sourceTrees.isEmpty()
                        && UpdateCheck.findMarks(m).sourceTreeOffset == -1 && !text(r, "series").contains("source folder v")
                        && !text(r, "series").contains("A different source folder")
                        && detail(r, "series").contains("no source folder found") && line(r, "build", UpdateCheck.Level.WARN)
                        && detail(r, "build").contains("the source folder is not found, that build's v006"), r);
        m = base.clone();
        m[0x62B8] ^= 0x08;
        r = check(m, build);
        expect("a code change keeps the build's layout, stamp, table, folder and compile date: that build with changes",
                r.ok && line(r, "build", UpdateCheck.Level.INFO)
                        && text(r, "build").contains("synthetic test build, with changes")
                        && detail(r, "build").contains("the stamp, the sensor table, the source folder and the compile"
                        + " date are that build's"), r);

        // --- the compile date: a build mark like the source folder, where the build records one ----------------

        m = base.clone();
        put(m, 0xF3978, "Oct 15 2025");
        r = check(m, build);
        expect("a file laid out like a known build with another compile date is another build, not a changed copy",
                r.ok && line(r, "build", UpdateCheck.Level.WARN) && text(r, "build").contains("a build the app does not"
                        + " know, laid out like synthetic test build: its compile date is not that build's.")
                        && !text(r, "build").contains("with changes")
                        && text(r, "series").contains("compiled Oct 15 2025")
                        && detail(r, "build").contains("the compile date is Oct 15 2025, that build's Oct 14 2025"), r);
        r = check(m, buildFor(base, null));
        expect("against a build that records no compile date, the date asks for nothing", r.ok
                && line(r, "build", UpdateCheck.Level.INFO) && text(r, "build").contains("synthetic test build, with changes")
                && detail(r, "build").contains("and the stamp, the sensor table and the source folder are that"
                + " build's"), r);
        m[0xF3983] = 'X';                      // no NUL after the date: no compile date at all
        put(m, 0x218C4, "202601010000");
        r = check(m, build);
        expect("a file with no compile date and another stamp is not that build with changes either", r.ok
                && line(r, "build", UpdateCheck.Level.WARN) && text(r, "build").contains("laid out like synthetic test"
                + " build: its stamp and its compile date are not that build's.")
                && detail(r, "build").contains("the compile date is not found, that build's Oct 14 2025"), r);

        // --- the official update size ------------------------------------------------------------------------

        byte[] official = Arrays.copyOf(base, UpdateCheck.OFFICIAL_SIZE);
        r = UpdateCheck.check(name(official, "202611050001"), official, build);
        expect("an official-size file passes: size INFO, final erase WARN, own area INFO, build unchanged", r.ok
                && line(r, "size", UpdateCheck.Level.INFO) && line(r, "final-erase", UpdateCheck.Level.WARN)
                && text(r, "final-erase").contains("factory settings") && line(r, "own-area", UpdateCheck.Level.INFO)
                && line(r, "resource-slots", UpdateCheck.Level.PASS) && line(r, "build", UpdateCheck.Level.INFO)
                && text(r, "build").contains("unchanged") && line(r, "software", UpdateCheck.Level.PASS), r);
        r = UpdateCheck.check(name(official, "202611050001"), official, build, chip(base));
        expect("an official-size file against a backup of its known build: software passes, the sensor line warns", r.ok
                && line(r, "software", UpdateCheck.Level.PASS) && line(r, "sensor-table", UpdateCheck.Level.WARN), r);
        byte[] ownOfficial = Arrays.copyOf(own, UpdateCheck.OFFICIAL_SIZE);
        r = UpdateCheck.check(name(ownOfficial, "202611050001"), ownOfficial, build, chip(own));
        expect("an official-size file of the camera's own build against its backup passes the software and sensor"
                + " lines", r.ok && line(r, "software", UpdateCheck.Level.PASS)
                && line(r, "sensor-table", UpdateCheck.Level.PASS) && line(r, "vector-table", UpdateCheck.Level.PASS), r);
        byte[] shorter = Arrays.copyOf(base, 0x160000);
        r = UpdateCheck.check(name(shorter, "202611050001"), shorter, build);
        expect("a file shorter than the official size fails", !r.ok && line(r, "size", UpdateCheck.Level.FAIL), r);
        byte[] between = Arrays.copyOf(base, 0x170000);
        r = UpdateCheck.check(name(between, "202611050001"), between, build);
        expect("a software-only image fails as one, with the way to an update file", !r.ok
                && line(r, "size", UpdateCheck.Level.FAIL) && text(r, "size").contains("software-only image")
                && text(r, "size").contains("charmera pack"), r);

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
        expect("a dump without a sensor table the app knows is not a backup, and may be another build's",
                UpdateCheck.backupProblem(noMarks, noPublic).contains("set-up table")
                        && UpdateCheck.backupProblem(noMarks, noPublic).contains("charmera inspect"), null);
        byte[] noString = dump.clone();
        noString[0x18964] = 'X';
        expect("a dump without the build string is not a complete dump", UpdateCheck.backupProblem(noString, noPublic)
                .contains("not a complete dump of the chip"), null);
        expect("nothing is not a backup", UpdateCheck.backupProblem(null, noPublic) != null, null);
        String publicProblem = UpdateCheck.backupProblem(dump, new String[]{UpdateCheck.sha256Hex(dump, 0, dump.length)});
        expect("a read identical to the public dump is refused, and the reason allows that it is this camera's", publicProblem
                .contains("flashed with the public restore file") && publicProblem.contains("not firmware known to fit"), null);
        String otherPublic = UpdateCheck.backupProblem(dump, new String[]{repeat('0', 64),
                UpdateCheck.sha256Hex(dump, 0, dump.length)});
        expect("another public dump is not called the stock dump, nor a read after the stock restore file",
                otherPublic.startsWith("it is byte for byte one of the public dumps, not the stock one:")
                        && otherPublic.contains("flashed with a file made from that dump")
                        && !otherPublic.contains("the public restore file") && otherPublic.contains("not firmware known to"
                        + " fit"), null);
        byte[] cutOff = dump.clone();
        Arrays.fill(cutOff, 0x100000, cutOff.length, (byte) 0xFF);   // a clip that lost contact half way
        expect("a read that is blank from half way is not a backup: it could not put the camera back",
                UpdateCheck.backupProblem(cutOff, noPublic).contains("resource pack")
                        && UpdateCheck.backupProblem(cutOff, noPublic).contains("could not put the camera back"), null);
        byte[] damaged = dump.clone();
        Arrays.fill(damaged, slot("LOGOJPG")[0], slot("LOGOJPG")[0] + slot("LOGOJPG")[1], (byte) 0xFF);
        expect("a read with a damaged resource is not a backup", UpdateCheck.backupProblem(damaged, noPublic)
                .startsWith("1 of its 43 sounds, pictures and menu files are damaged"), null);
        // The toolkit's backup and restore keep a read no second read agreed with as
        // <label>_<time>.unverified-read<n>.bin: not a backup, whatever its bytes hold, since a poor clip contact can
        // leave wrong bytes that look whole.
        String unverified = UpdateCheck.backupProblem("chip-now_20261106-120000Z.unverified-read1.bin", dump, noPublic);
        expect("a dump named like a read no second read agreed with is not a backup", unverified != null
                && unverified.startsWith("its name is one the toolkit's backup and restore give to reads that did not"
                + " agree and to a single read, and such a read is not a backup until two reads agree.")
                && unverified.contains("read the chip again with the toolkit's backup command until two reads"
                + " agree"), null);
        Locale language = Locale.getDefault();
        String upper;
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));   // where a capital I is not a lower-case i
            upper = UpdateCheck.backupProblem("CHIP-NOW_20261106-120000Z.UNVERIFIED-READ2.BIN", dump, noPublic);
        } finally {
            Locale.setDefault(language);
        }
        expect("in any case, whatever the phone's language", unverified != null && unverified.equals(upper), null);
        expect("and for its name first, when its bytes are a cut-off read too", unverified != null && unverified.equals(
                UpdateCheck.backupProblem("chip-now_20261106-120000Z.unverified-read1.bin", cutOff, noPublic)), null);
        expect("the read the backup command keeps once two reads agree is taken, and so is a dump whose name is not"
                + " known", UpdateCheck.backupProblem("chip-now_20261106-120000Z.bin", dump, noPublic) == null
                && UpdateCheck.backupProblem(null, dump, noPublic) == null, null);
        // A near copy of a known build's dump, as a read after a flash with it is once a setting was saved: it is
        // taken, and what it can and cannot show is said with it and on the sensor line.
        byte[] near = dump.clone();
        putRecord(near, 0x170000, 1);
        Arrays.fill(near, 0x17F000, 0x17F200, (byte) 0);
        expect("a near copy of the public dump is taken as a backup",
                UpdateCheck.backupProblem(near, new String[]{UpdateCheck.sha256Hex(dump, 0, dump.length)}) == null, null);
        List<String> notes = UpdateCheck.backupNotes(near, build);
        expect("and noted as a known, public build that shows a fit only if read before any flash", notes.size() == 1
                && notes.get(0).contains("synthetic test build, unchanged")
                && notes.get(0).contains("only if it was read before the camera was ever flashed"), null);
        r = UpdateCheck.check(name(base, "202611050001"), base, build, near);
        expect("against it, the same table is a warning", r.ok && line(r, "sensor-table", UpdateCheck.Level.WARN), r);
        expect("a backup of a build no public image holds gets no note", UpdateCheck.backupNotes(chip(own), build)
                .isEmpty() && UpdateCheck.backupNotes(dump, UpdateCheck.PUBLIC_BUILD).isEmpty(), null);
        byte[] zeroTail = dump.clone();
        Arrays.fill(zeroTail, UpdateCheck.UPDATE_SIZE, zeroTail.length, (byte) 0);   // padded with 0x00, not 0xFF
        List<String> tail = UpdateCheck.backupNotes(zeroTail, UpdateCheck.PUBLIC_BUILD);
        expect("a read whose last 128 KiB are not blank is taken, with a warning, not refused",
                UpdateCheck.backupProblem(zeroTail, noPublic) == null && tail.size() == 1
                        && tail.get(0).contains("not blank") && tail.get(0).contains("original firmware left"), null);

        // --- what kind of mod: bytes 0..0x120000 (header, code, data) against the resources -------------------

        m = base.clone();
        m[slot("SOUND0WAV")[0] + 60] ^= 0x55;   // a PCM byte
        r = check(m, build);
        expect("resource mod warns, passes, says the code area is the public dump's", r.ok
                && line(r, "software", UpdateCheck.Level.WARN)
                && text(r, "software").contains("The program itself is the public dump's")
                && detail(r, "software").contains("(boot header, code and data) match the public dump"), r);
        r = UpdateCheck.check(name(m, "202611050001"), m, build, chip(base));
        expect("against the backup a resource mod says the program is the one in the backup", r.ok
                && line(r, "software", UpdateCheck.Level.WARN)
                && text(r, "software").contains("The program itself is the one in your backup"), r);

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
        expect("without a backup the verdict is not OK yet, and points at the warnings",
                r.verdict().startsWith("Not OK to put on the card yet: it has not been compared with a backup")
                && r.verdict().contains("The file's own checks passed, with these 2 warnings:")
                && r.verdict().contains("Stored photos: the file carries 1 photo from the camera it was made from, but its"
                + " photo list is empty, so your camera will not show it.")
                && r.verdict().contains("copy the SPIDCIM folder off the card"), r);
        r = UpdateCheck.check(name(m, "202611050001"), m, build, chip(own));
        expect("compared with a backup, the verdict passes it and points at the warnings", r.ok
                && r.verdict().startsWith("OK to put on the card. Read these ")
                && r.verdict().contains("Stored photos: the file carries 1 photo"), r);
        m[0x17F002] = 1;        // directory entry 0: used
        putU32(m, 0x17F004, 38478);
        r = check(m, build);
        expect("a listed stored photo is said to be shown", r.ok && text(r, "photos").contains("lists it, so your camera will show it"), r);

        // --- the verdict at the top of the report -------------------------------------------------------------

        r = check(base, build);
        expect("a clean file's verdict without a backup is not OK yet: it was compared with no backup", r.ok
                && !r.compared && r.verdict().startsWith("Not OK to put on the card yet: it has not been compared with a"
                + " backup of this camera's own chip, and without one no file goes on the card. The file's own checks"
                + " passed, with this warning: Image sensor set-up: the same program as the public build's"), r);
        r = UpdateCheck.check(name(own, "202611050001"), own, build, chip(own));
        expect("the same file compared with a backup is OK", r.ok && r.compared
                && r.verdict().startsWith("OK to put on the card."), r);
        r = UpdateCheck.check("JH_5408202611050001_00000000.bin", base, build);
        expect("a failed file's verdict is Not OK with the first reason", r.verdict().startsWith("Not OK to put on the"
                + " card. The app will not write this file. Sum: the file's bytes do not add up"), r);
        m = base.clone();
        m[0] = 'X';
        r = UpdateCheck.check("JH_5408202505201029_00000000.bin", m, build);
        expect("the verdict counts the other problems", r.verdict().startsWith("Not OK to put on the card. The app will"
                + " not write this file. Sum: ") && r.verdict().endsWith(" 2 more problems are listed below."), r);
        r = UpdateCheck.check("firmware.bin", base, build);
        expect("a nameless file's verdict says what to do, and that the stock restore file is not everyone's",
                r.verdict().contains("Name: this is not an update file's name") && r.verdict().contains("Do not make up a"
                + " name") && r.verdict().contains("The stock restore file is only for a camera whose backup, read before"
                + " the camera was ever flashed, shows the public build. For any other camera, make the file from its"
                + " own backup with charmera pack on a computer.") && !r.verdict().contains("To restore the camera, tap"), r);

        // --- a refused stock restore file: only a name problem is reported as one -----------------------------

        r = UpdateCheck.check("JH_5408202611050001_00000000.bin", base, build);
        expect("a wrong sum is a name problem", UpdateCheck.nameIsTheOnlyProblem(r), r);
        r = UpdateCheck.check("firmware.bin", base, build);
        expect("no update file's name is a name problem", UpdateCheck.nameIsTheOnlyProblem(r), r);
        r = UpdateCheck.check(name(base, "202505201029"), base, build);
        expect("the known builds' stamp in the name is a name problem", UpdateCheck.nameIsTheOnlyProblem(r), r);
        r = UpdateCheck.check(name(base, "202611050001"), base, build, otherBackup);
        expect("a sensor table that differs from the backup's is not a name problem", !r.ok
                && !UpdateCheck.nameIsTheOnlyProblem(r), r);
        r = UpdateCheck.check("JH_5408202611050001_00000000.bin", base, build, otherBackup);
        expect("nor is it one with a wrong sum besides", !r.ok && !UpdateCheck.nameIsTheOnlyProblem(r), r);
        expect("a file that passed has no name problem", !UpdateCheck.nameIsTheOnlyProblem(check(base, build)), null);

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
            UpdateCheck.Marks fm = UpdateCheck.findMarks(d);
            expect("its source folder is v008_test, named in all 27 of its source paths, the first name at 0x464ba as"
                    + " image.py reports it", "v008_test".equals(fm.sourceTree) && fm.sourceTrees.size() == 1
                    && fm.sourceTrees.get("v008_test") == 27 && fm.sourceTreeOffset == 0x464BA
                    && text(r, "series").contains("source folder v008_test")
                    && detail(r, "series").contains("source folder at 0x464ba, named in 27 source paths"), r);
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
        // three source paths of the kind the firmware's checks name their files by: the source folder is v006
        sourcePath(d, 0x3A4C0, "v006", "host\\tcpip\\lwip-1.4.0\\src\\netif\\etharp.c");
        sourcePath(d, 0x3C440, "v006", "host\\tcpip\\lwip-1.4.0\\src\\api\\api_lib.c");
        sourcePath(d, 0x46600, "v006", "host\\init.c");
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
        return buildFor(d, "Oct 14 2025");
    }

    /** The synthetic build with the compile date it records, or null for none. */
    static UpdateCheck.Build buildFor(byte[] d, String compiled) {
        return new UpdateCheck.Build("synthetic test build", "202505201029", UpdateCheck.sha256Hex(d, 0, 0x170000),
                UpdateCheck.sha256Hex(d, 0, 0x120000), UpdateCheck.sha256Hex(d, 0, UpdateCheck.OFFICIAL_SIZE),
                UpdateCheck.sha256Hex(d, 0xF5C6C, 0xF5CE8), UpdateCheck.sha256Hex(d, 0x200, 0x240), compiled, "v006",
                0x218C4, 0x18964, 0xF5C6C);
    }

    /** A 2 MiB read of a chip that holds the image d: d, then the blank 0xFF the rest of the chip is. */
    static byte[] chip(byte[] d) {
        byte[] c = new byte[0x200000];
        System.arraycopy(d, 0, c, 0, d.length);
        Arrays.fill(c, d.length, c.length, (byte) 0xFF);
        return c;
    }

    /** A NUL, then the source path G:\code\2247\5408\<folder>\<file> and its NUL, at off; the prefix is at off + 8. */
    static void sourcePath(byte[] d, int off, String folder, String file) {
        String path = "G:\\code\\2247\\5408\\" + folder + "\\" + file;
        d[off] = 0;
        put(d, off + 1, path);
        d[off + 1 + path.length()] = 0;
    }

    /** A second reset table and init table (the given one) at off, with a code literal for it at literal, or none. */
    static void sensorPair(byte[] d, int off, byte[] table, int literal) {
        System.arraycopy(UpdateCheck.SENSOR_RESET_TABLE, 0, d, off, 6);
        System.arraycopy(table, 0, d, off + 6, table.length);
        if (literal >= 0) {
            putU32(d, literal, off + 6 - 0x200);
        }
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

    static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
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
