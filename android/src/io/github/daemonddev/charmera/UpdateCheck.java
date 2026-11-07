package io.github.daemonddev.charmera;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The checks `charmera verify` makes, for the two update-file sizes the camera is known to take: 0x1E0000 bytes (the
 * community files) and 0x169000 bytes (official updates). No Android imports, so it runs on a plain JVM for the tests in
 * android/test.
 *
 * A file passes only when every FAIL-level check passes. WARN lines are shown and do not block.
 *
 * Every check looks at the file. None looks at the camera. The one thing that does say something about a particular
 * camera is a dump of that camera's own flash chip: given as the reference, the file's image-sensor program and its
 * software are compared with it. Without one, the sensor line is a warning that cannot be cleared: two different
 * builds carry the same version stamp, and the public build does not fit every original-series camera.
 *
 * Every line is written for the owner reading it on a phone: a plain title and text, and any numbers, sums or
 * offsets below it as a labelled detail. Result.verdict() is the one-line answer for the top of the report.
 */
public final class UpdateCheck {

    public static final int UPDATE_SIZE = 0x1E0000;
    /** Where official updates and the film-mods files end: code, data and the resource pack, nothing of the camera's own area. */
    public static final int OFFICIAL_SIZE = 0x169000;
    public static final String PRODUCT = "5408";
    /** The stamp both known builds carry (the public dump and the film-mods author's build): it identifies neither. */
    public static final String SHARED_STAMP = "202505201029";

    static final int SOFTWARE_END = 0x170000;
    static final int SETTINGS_BASE = 0x170000;
    static final int PHOTO_DIRECTORY = 0x17F000;
    static final int SECTOR = 0x200;
    static final int CRC_LEN = 252;
    static final int OFF_UI_SOUND = 0x19;
    static final int RESOURCE_BASE = 0x120000;
    /** The boot header, the code, its read-only and initialised data and the padding all lie below this offset. */
    static final int CODE_AREA_END = 0x120000;
    /** The factory self-test sector starts here; the last resource entry's slot ends at it (as in verify). */
    static final int RESOURCE_LIMIT = 0x16E000;
    static final int DIRECTORY_START = RESOURCE_BASE + 0x18;
    static final int DIRECTORY_ENTRY = 24;
    static final int MAX_LISTED_PROBLEMS = 8;
    static final int[] PHOTO_SLOTS = {0x180000, 0x1A5800};
    /** File offset = RAM address + this. */
    static final int VA_OFFSET = 0x200;

    /** The sensor's reset table: page select 0, register 0x20 = 0, end marker. The init table follows it. */
    public static final byte[] SENSOR_RESET_TABLE = {(byte) 0xFD, 0, 0x20, 0, (byte) 0xFF, (byte) 0xFF};
    static final int SENSOR_PAGE_REGISTER = 0xFD;

    /** sha256 of bytes 0..0x120000 (CODE_AREA_END) of the public dump; tests/test_android.py checks it. */
    static final String PUBLIC_CODE_AREA_SHA256 = "a96b29c21a6c20d66df4636797cb13224e06cdb223c3cf6a7ef06dde6a9c0dc7";

    /**
     * What identifies a firmware build: fingerprints over its bytes, never the version stamp. The real one is
     * PUBLIC_BUILD; tests make their own. The mark offsets say where that build keeps its marks, so a changed copy of
     * it can be told from another build (which keeps them elsewhere: the film-mods build has its stamp at 0x218D0).
     */
    public static final class Build {
        /** The build in the owner's words, as the report shows it. */
        final String label;
        /** The stamp compiled into it: a file name with this stamp is ignored by a camera running the build. */
        final String stamp;
        /** sha256 of bytes 0..0x170000: header, code, data and resources. */
        final String softwareSha256;
        /** sha256 of bytes 0..0x120000: header, code and data, everything below the resource pack. */
        final String codeAreaSha256;
        /** sha256 of bytes 0..0x169000: what an official update writes. */
        final String officialSha256;
        /** sha256 of the sensor init table, end marker included. */
        final String sensorSha256;
        final int stampOffset;
        final int buildOffset;
        final int sensorOffset;

        public Build(String label, String stamp, String softwareSha256, String codeAreaSha256, String officialSha256,
                     String sensorSha256, int stampOffset, int buildOffset, int sensorOffset) {
            this.label = label;
            this.stamp = stamp;
            this.softwareSha256 = softwareSha256;
            this.codeAreaSha256 = codeAreaSha256;
            this.officialSha256 = officialSha256;
            this.sensorSha256 = sensorSha256;
            this.stampOffset = stampOffset;
            this.buildOffset = buildOffset;
            this.sensorOffset = sensorOffset;
        }
    }

    /** Values from charmera_toolkit/image.py (PUBLIC_BUILD, hashed from the stock dump itself); keep them in step. */
    public static final Build PUBLIC_BUILD = new Build(
            "the public dump's build (the one several owners' cameras run, and the one the stock restore file is)",
            SHARED_STAMP,
            "fe88e8de57e7b1ff1a0856127ee09a21fb3ce2dde2684662c533719b80adc5c8",
            PUBLIC_CODE_AREA_SHA256,
            "bffddd53d01a42725d63aa08dff5585433604a680a85520b23f3e176ae80f745",
            "aea3976bdf7ba2e747d8b56141f7ef0b4e3262481540dc2869457bc814fcb755",
            0x218C4, 0x18964, 0xF5C6C);

    /**
     * The charmera-film-mods author's build, hashed from that project's public restore file (bytes 0..0x169000 of the
     * author's factory dump; charmera_toolkit/image.py FILM_MODS_BUILD). Same stamp, different bytes, the same sensor
     * table. Only 0x169000 bytes are public, so there is no hash over 0..0x170000.
     */
    public static final Build FILM_MODS_BUILD = new Build(
            "the charmera-film-mods author's build (same stamp, different bytes, compiled Nov 19 2025)",
            SHARED_STAMP,
            null,
            "b672ee59802a51f98b9d079e1cd2dfac77708afdf25a69bdf04c2e66b5fcb507",
            "5b352e46f1f52175cb4da2278be8db32ecc36be4943424d58cfd8e2cc9c4386f",
            "aea3976bdf7ba2e747d8b56141f7ef0b4e3262481540dc2869457bc814fcb755",
            0x218D0, 0x1897C, 0xF5D34);

    /** Known builds other than the one a check is run against, named when a file is one of them unchanged. */
    static final Build[] OTHER_KNOWN_BUILDS = {FILM_MODS_BUILD};

    /** JH_ + 4-digit product + 12-digit stamp + _ + 8 hex digits, anywhere in the name (copies get " (1)" added). */
    static final Pattern NAME = Pattern.compile("JH_(\\d{4})(\\d{12})_([0-9A-Fa-f]{8})");

    public enum Level { PASS, INFO, WARN, FAIL }

    /** What to do instead after a FAIL: the file the app makes itself. The button is named as MainActivity labels it. */
    static final String RESTORE_INSTEAD = "To restore the camera, tap \"Download the stock restore file\" instead.";
    /** The same, plus the way to a working mod. */
    static final String INSTEAD = RESTORE_INSTEAD + " For a mod, make the file again with charmera pack on a computer.";
    /** What a build match does and does not prove. Every build line carries it. */
    static final String NOT_THE_CAMERA = "A build match says which public image the file was made from, not whether it fits"
            + " your camera: only a dump of your camera's own chip does.";
    /** How the backup gets into the app; the button is named as MainActivity labels it. */
    static final String BACKUP_STEP = "Choose that dump with \"Choose this camera's backup\" in step 0 and the app compares them.";

    public static final class Line {
        public final Level level;
        /** The check's short name, the one `charmera verify` uses. For code and tests; the report does not show it. */
        public final String name;
        /** What the check is about, in plain words: "Name", "Stored photos". */
        public final String title;
        /** What the check found and, when it matters, what to do next. Plain words, no offsets. */
        public final String text;
        /** Numbers, sums and offsets for anyone who wants them, or "" for none. Shown below the text as "Detail:". */
        public final String detail;

        Line(Level level, String name, String title, String text, String detail) {
            this.level = level;
            this.name = name;
            this.title = title;
            this.text = text;
            this.detail = detail == null ? "" : detail;
        }

        /** "PASS  Name: in the form the camera looks for." and, when there is one, an indented "Detail:" line. */
        @Override
        public String toString() {
            String head = level + "  " + title + ": " + text;
            return detail.isEmpty() ? head : head + "\n      Detail: " + detail;
        }
    }

    /** The build marks found in a file by their content, never at a fixed offset. -1 and null mean not found. */
    public static final class Marks {
        public final String stamp;
        public final int stampOffset;
        public final String buildString;
        public final int buildOffset;
        public final int resetStart;
        public final int sensorStart;
        public final int sensorEnd;
        public final int sensorPairs;
        public final String sensorSha256;
        /** The code literal holding the init table's RAM address, or -1 when no code refers to it. */
        public final int referencedFrom;
        /** The sensor's bus write address from the config struct after the reset table, or -1. */
        public final int busAddress;
        /** The compile date the build carries ("Oct 14 2025"), or null. It differs between the two known builds. */
        public final String compiled;
        public final int compiledOffset;

        Marks(String stamp, int stampOffset, String buildString, int buildOffset, int resetStart, int sensorStart,
              int sensorEnd, int sensorPairs, String sensorSha256, int referencedFrom, int busAddress, String compiled,
              int compiledOffset) {
            this.stamp = stamp;
            this.stampOffset = stampOffset;
            this.buildString = buildString;
            this.buildOffset = buildOffset;
            this.resetStart = resetStart;
            this.sensorStart = sensorStart;
            this.sensorEnd = sensorEnd;
            this.sensorPairs = sensorPairs;
            this.sensorSha256 = sensorSha256;
            this.referencedFrom = referencedFrom;
            this.busAddress = busAddress;
            this.compiled = compiled;
            this.compiledOffset = compiledOffset;
        }

        /** The three marks every Charmera build carries. */
        public boolean complete() {
            return stamp != null && buildString != null && sensorStart >= 0;
        }

        /** Which of the three are missing, in plain words, or "". */
        String missing() {
            List<String> out = new ArrayList<>();
            if (buildString == null) {
                out.add("the build string");
            }
            if (stamp == null) {
                out.add("the version stamp");
            }
            if (sensorStart < 0) {
                out.add("the image sensor's set-up table");
            }
            return String.join(", ", out);
        }
    }

    public static final class Result {
        public final List<Line> lines;
        /** True when no FAIL line was produced. */
        public final boolean ok;
        /** The name the file must have on the card, or null when the name could not be read. */
        public final String cardName;
        public final String sha256;
        public final int size;
        /** True when the file was compared with a dump of the camera (the owner's backup). */
        public final boolean compared;

        Result(List<Line> lines, String cardName, String sha256, int size) {
            this(lines, cardName, sha256, size, false);
        }

        Result(List<Line> lines, String cardName, String sha256, int size, boolean compared) {
            boolean failed = false;
            for (Line l : lines) {
                if (l.level == Level.FAIL) {
                    failed = true;
                    break;
                }
            }
            this.lines = Collections.unmodifiableList(lines);
            this.ok = !failed;
            this.cardName = cardName;
            this.sha256 = sha256;
            this.size = size;
            this.compared = compared;
        }

        /**
         * The plain answer for the top of the report. When the file passed: "OK to put on the card." and any warnings
         * in full, to read first. When it did not: "Not OK to put on the card.", the first problem with its cause and
         * what to do next, and how many more there are.
         */
        public String verdict() {
            Line first = null;
            int fails = 0;
            List<Line> warned = new ArrayList<>();
            for (Line l : lines) {
                if (l.level == Level.FAIL) {
                    if (first == null) {
                        first = l;
                    }
                    fails++;
                } else if (l.level == Level.WARN) {
                    warned.add(l);
                }
            }
            if (first != null) {
                String more = fails == 1 ? ""
                        : fails == 2 ? " 1 more problem is listed below."
                        : " " + (fails - 1) + " more problems are listed below.";
                return "Not OK to put on the card. The app will not write this file. " + first.title + ": " + first.text
                        + more;
            }
            StringBuilder sb = new StringBuilder("OK to put on the card.");
            if (warned.isEmpty()) {
                sb.append(" Every check passed.");
            } else {
                sb.append(warned.size() == 1 ? " Read this warning first." : " Read these " + warned.size()
                        + " warnings first.");
                for (Line w : warned) {
                    sb.append(' ').append(w.title).append(": ").append(w.text);
                }
            }
            return sb.toString();
        }

        /** Every check, in order, with a blank line between them. */
        public String report() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(lines.get(i)).append('\n');
            }
            return sb.toString();
        }
    }

    private UpdateCheck() {
    }

    // --- the build marks, by content ----------------------------------------------------------------------------

    private static boolean digit(byte b) {
        return b >= '0' && b <= '9';
    }

    static int indexOf(byte[] data, byte[] needle, int from, int to) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= Math.min(to, data.length); i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** End (exclusive, marker included) of the (register, value) table starting at start, or -1. */
    private static int registerTableEnd(byte[] data, int start, int limit) {
        for (int p = start; p + 2 <= Math.min(data.length, start + limit); p += 2) {
            if ((data[p] & 0xFF) == 0xFF && (data[p + 1] & 0xFF) == 0xFF) {
                return p + 2;
            }
        }
        return -1;
    }

    /**
     * The build marks, each found by what it looks like in the code area: the 12-digit stamp (ASCII digits then a NUL,
     * after a non-digit, by preference the one that follows the "*.bin" the updater matches with), the build string
     * "JH5408  <date>", and the sensor program: the reset table by its exact bytes, then the first (register, value)
     * table after it that starts with a page select, switches page at least once more, holds 16 writes or more and
     * ends with the ff ff marker. Where several spots fit, the init table the code points at by address wins.
     */
    public static Marks findMarks(byte[] data) {
        int end = Math.min(data.length, CODE_AREA_END);
        String stamp = null;
        int stampOffset = -1;
        int runs = 0;
        int firstRun = -1;
        byte[] updatePattern = "*.bin\0".getBytes(StandardCharsets.US_ASCII);
        for (int i = VA_OFFSET; i + 13 <= end; i++) {
            if (!digit(data[i]) || digit(data[i - 1])) {
                continue;
            }
            int j = i;
            while (j < end && digit(data[j])) {
                j++;
            }
            if (j - i == 12 && j < end && data[j] == 0) {
                runs++;
                if (firstRun < 0) {
                    firstRun = i;
                }
                if (indexOf(data, updatePattern, i - 64, i) >= 0) {
                    stamp = new String(data, i, 12, StandardCharsets.US_ASCII);
                    stampOffset = i;
                    break;
                }
            }
            i = j;
        }
        if (stamp == null && runs == 1) {
            stamp = new String(data, firstRun, 12, StandardCharsets.US_ASCII);
            stampOffset = firstRun;
        }
        String buildString = null;
        int buildOffset = -1;
        byte[] jh = "JH5408".getBytes(StandardCharsets.US_ASCII);
        for (int at = indexOf(data, jh, VA_OFFSET, end); at >= 0; at = indexOf(data, jh, at + 1, end)) {
            int p = at + jh.length;
            int spaces = 0;
            while (p < end && data[p] == ' ') {
                p++;
                spaces++;
            }
            int q = p;
            while (q < end && q - p < 8 && digit(data[q])) {
                q++;
            }
            if (spaces >= 1 && q - p == 8) {
                buildString = new String(data, at, q - at, StandardCharsets.US_ASCII);
                buildOffset = at;
                break;
            }
        }
        int resetStart = -1;
        int sensorStart = -1;
        int sensorEnd = -1;
        int pairs = 0;
        int referencedFrom = -1;
        int bus = -1;
        boolean pickedReferenced = false;
        for (int at = indexOf(data, SENSOR_RESET_TABLE, VA_OFFSET, end); at >= 0 && !pickedReferenced;
             at = indexOf(data, SENSOR_RESET_TABLE, at + 1, end)) {
            int resetEnd = at + SENSOR_RESET_TABLE.length;
            for (int p = resetEnd; p + 2 <= Math.min(end, resetEnd + 0x400); p += 2) {
                if ((data[p] & 0xFF) != SENSOR_PAGE_REGISTER || data[p + 1] != 0) {
                    continue;
                }
                int e = registerTableEnd(data, p, 2048);
                if (e < 0 || e > end) {
                    continue;
                }
                int n = (e - p) / 2 - 1;
                int pages = 0;
                for (int k = p + 2; k < e - 2; k += 2) {
                    if ((data[k] & 0xFF) == SENSOR_PAGE_REGISTER) {
                        pages++;
                    }
                }
                if (n < 16 || pages < 1) {
                    continue;
                }
                int ram = p - VA_OFFSET;
                byte[] literal = {(byte) ram, (byte) (ram >> 8), (byte) (ram >> 16), (byte) (ram >> 24)};
                int ref = indexOf(data, literal, VA_OFFSET, end);
                if (sensorStart < 0 || ref >= 0) {
                    resetStart = at;
                    sensorStart = p;
                    sensorEnd = e;
                    pairs = n;
                    referencedFrom = ref;
                    int busAt = (resetEnd + 3) & ~3;
                    long b = busAt + 4 <= data.length ? u32(data, busAt) : -1;
                    bus = b > 0 && b < 0x100 ? (int) b : -1;
                    pickedReferenced = ref >= 0;
                }
                break;
            }
        }
        String compiled = null;
        int compiledOffset = -1;
        for (int i = VA_OFFSET; i + 12 <= end && compiled == null; i++) {
            if (isCompileDate(data, i)) {
                compiled = new String(data, i, 11, StandardCharsets.US_ASCII);
                compiledOffset = i;
            }
        }
        return new Marks(stamp, stampOffset, buildString, buildOffset, resetStart, sensorStart, sensorEnd, pairs,
                sensorStart < 0 ? null : sha256Hex(data, sensorStart, sensorEnd), referencedFrom, bus, compiled,
                compiledOffset);
    }

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /** "Mmm dd 20yy" and a NUL at i, the form a compile date takes (as image.py's _COMPILED_RE). */
    private static boolean isCompileDate(byte[] d, int i) {
        if (!(d[i + 3] == ' ' && (d[i + 4] == ' ' || digit(d[i + 4])) && digit(d[i + 5]) && d[i + 6] == ' '
                && d[i + 7] == '2' && d[i + 8] == '0' && digit(d[i + 9]) && digit(d[i + 10]) && d[i + 11] == 0)) {
            return false;
        }
        String month = new String(d, i, 3, StandardCharsets.US_ASCII);
        for (String m : MONTHS) {
            if (m.equals(month)) {
                return true;
            }
        }
        return false;
    }

    // --- the checks ------------------------------------------------------------------------------------------------

    public static Result check(String displayName, byte[] data) {
        return check(displayName, data, PUBLIC_BUILD, null);
    }

    public static Result check(String displayName, byte[] data, Build build) {
        return check(displayName, data, build, null);
    }

    /**
     * reference is a dump of the camera the file goes into (the owner's backup), or null. With it, the file's sensor
     * program and software are compared with that camera's own firmware; without it, they are only compared with the
     * known build and the sensor line stays a warning.
     */
    public static Result check(String displayName, byte[] data, Build build, byte[] reference) {
        List<Line> out = new ArrayList<>();
        String cardName = null;
        long sum = byteSum(data);
        Marks marks = findMarks(data);

        // 1. The name: the camera only looks for JH_5408<stamp>_<sum>.bin, and the sum is its only integrity check.
        Matcher m = NAME.matcher(displayName == null ? "" : displayName);
        if (!m.find()) {
            out.add(new Line(Level.FAIL, "name", "Name", "this is not an update file's name, so the camera would not use"
                    + " the file. The camera only takes a file named like JH_5408202610261427_0F065D55.bin, and the sum"
                    + " at the end must match the file. Do not make up a name: choose the file under the name it was"
                    + " made with. " + RESTORE_INSTEAD,
                    (displayName == null ? "the file has no name" : "the name is \"" + displayName + "\"")
                            + "; the file's bytes add up to " + hex8(sum) + "."));
        } else {
            String product = m.group(1);
            String stamp = m.group(2);
            long named = Long.parseLong(m.group(3), 16);
            cardName = "JH_" + product + stamp + "_" + hex8(named) + ".bin";
            out.add(new Line(Level.PASS, "name", "Name", "in the form the camera looks for.",
                    "model " + product + ", stamp " + stamp + ", sum " + hex8(named) + "."));
            if (!cardName.equals(displayName)) {
                out.add(new Line(Level.INFO, "card-name", "Name on the card", "the name on the phone is not exactly"
                        + " the one the camera looks for, for example because \" (1)\" was added. The app writes the file"
                        + " to the card as " + cardName + ".", ""));
            }
            boolean charmera = product.equals(PRODUCT);
            out.add(new Line(charmera ? Level.PASS : Level.FAIL, "product", "Model", charmera
                    ? "the name is for the Charmera."
                    : "the name is for another camera model, so the Charmera would ignore the file. Do not use it. "
                    + INSTEAD,
                    "model code " + product + (charmera ? "." : "; the Charmera looks for JH_" + PRODUCT + ".")));
            out.add(new Line(named == sum ? Level.PASS : Level.FAIL, "checksum", "Sum", named == sum
                    ? "the file's bytes add up to the sum in its name, so the file arrived whole."
                    : "the file's bytes do not add up to the sum in its name. The file is damaged, or it was changed"
                    + " after it was named. The camera would show \"Program error\", erase nothing and switch itself"
                    + " off. Do not use this file. Copy or download it again from where it came from.",
                    named == sum ? "sum " + hex8(sum) + "."
                            : "the name says " + hex8(named) + "; the bytes add up to " + hex8(sum) + "."));
            boolean differs = !stamp.equals(build.stamp);
            out.add(new Line(differs ? Level.PASS : Level.FAIL, "stamp", "Stamp", differs
                    ? "the 12 digits in the name differ from the stamp the known builds carry, so a camera running one"
                    + " of them will not skip the file."
                    : "the 12 digits in the name are the stamp the known builds carry, so a camera running one of them"
                    + " would skip the file. Make the file again with charmera pack on a computer, which gives it"
                    + " another stamp. " + RESTORE_INSTEAD,
                    differs ? "in the name " + stamp + "; the known builds' own " + build.stamp + "."
                            : "stamp " + stamp + "."));
            boolean realDate = isRealStamp(stamp);
            out.add(new Line(realDate ? Level.PASS : Level.WARN, "stamp-date", "Stamp date", realDate
                    ? "the stamp is a real date and time."
                    : "the stamp is not a real date and time. The camera does not mind, so this does not stop the"
                    + " update.",
                    realDate ? "" : "stamp " + stamp + ", read as year, month, day, hour and minute."));
            if (marks.stamp != null && !marks.stamp.equals(stamp)) {
                out.add(new Line(Level.INFO, "stamp-in-file", "Stamp in the file", "the stamp inside the file is not"
                        + " the one in its name, so a camera that runs this file flashes it again at every power-on"
                        + " while the file is on its card. Take the card out before the camera is switched on again,"
                        + " and remove the file (step 5).",
                        "inside the file " + marks.stamp + "; in the name " + stamp + "."));
            }
        }

        // 2. Size and structure. Two sizes are known to work; what the camera erases after writing depends on it.
        int n = data.length;
        boolean official = n == OFFICIAL_SIZE;
        if (n == UPDATE_SIZE) {
            out.add(new Line(Level.PASS, "size", "Size", "the right size: the one the community update files have. It"
                    + " writes the camera's settings and stored photos too (see below).", bytes(n) + ", 30 blocks of 64 KiB."));
            out.add(new Line(Level.INFO, "final-erase", "After the update", "the camera also erases the last part of"
                    + " its memory, which is empty anyway. Nothing to do.", "block 0x1F0000, 64 KiB."));
        } else if (official) {
            out.add(new Line(Level.INFO, "size", "Size", "the right size: the one official updates have. It holds the"
                    + " program, the pictures, the sounds and the menus only, nothing of another camera's settings or"
                    + " photos.", bytes(n) + ", 22 blocks of 64 KiB and part of the 23rd."));
            out.add(new Line(Level.WARN, "final-erase", "After the update", "the camera also erases its settings"
                    + " block. At the next start it uses factory settings and an empty photo list: the photos in its"
                    + " own memory stay there, but are no longer shown. Nothing above that block is touched.",
                    "block 0x170000, 64 KiB: the settings bank and the photo directory."));
        } else {
            String why;
            if (n == 0x200000) {
                why = " It is the size of a full 2 MB dump. A dump must never go on the card as it is: the camera's"
                        + " final erase then wraps around and bricks it. charmera pack on a computer makes the update"
                        + " file from it.";
            } else if (n < UPDATE_SIZE) {
                why = " A shorter file is usually a cut-off download or copy. Copy or download it again.";
            } else {
                why = " Make the update file with charmera pack on a computer, and check it there with charmera verify.";
            }
            out.add(new Line(Level.FAIL, "size", "Size", "the file is the wrong size for an update file, so the app"
                    + " will not put it on the card." + why, bytes(n) + "; an update file has " + bytes(UPDATE_SIZE)
                    + " or " + bytes(OFFICIAL_SIZE) + "."));
        }
        boolean magic = n >= 4 && data[0] == 'S' && data[1] == 'P' && data[2] == 'I' && data[3] == 'I';
        out.add(new Line(magic ? Level.PASS : Level.FAIL, "magic", "Start of the file", magic
                ? "it begins as Charmera firmware does."
                : "it does not begin as Charmera firmware does, so the camera would not start from it. Do not use"
                + " this file. " + INSTEAD,
                magic ? "begins with SPII." : "the first 4 bytes are not SPII."));

        // 3. Which build it is, read from its content. The stamp is not an identity: two builds share it.
        boolean compared = false;
        if (!marks.complete()) {
            out.add(new Line(Level.FAIL, "series", "Build marks", "not all the marks every Charmera firmware carries"
                    + " were found: " + marks.missing() + ". This is not Charmera firmware, or a damaged copy of it."
                    + " Do not use it. " + RESTORE_INSTEAD,
                    "found: " + (marks.buildString == null ? "" : "build string at " + hex(marks.buildOffset) + "; ")
                            + (marks.stamp == null ? "" : "stamp " + marks.stamp + " at " + hex(marks.stampOffset) + "; ")
                            + (marks.sensorStart < 0 ? "" : "sensor table at " + hex(marks.sensorStart) + ".")));
        } else {
            out.add(new Line(Level.PASS, "series", "Build marks", "Charmera firmware: build " + marks.buildString.trim()
                    + (marks.compiled == null ? "" : ", compiled " + marks.compiled) + ", stamp " + marks.stamp
                    + ". The stamp is carried by two different builds and tells them apart from nothing; their"
                    + " compile dates differ.",
                    "build string at " + hex(marks.buildOffset) + ", stamp at " + hex(marks.stampOffset)
                            + (marks.compiled == null ? ", no compile date found" : ", compile date at "
                            + hex(marks.compiledOffset)) + ", sensor table"
                            + " at " + hex(marks.sensorStart) + ".."+ hex(marks.sensorEnd) + " (" + marks.sensorPairs
                            + " writes" + (marks.busAddress < 0 ? "" : ", bus address " + hex(marks.busAddress))
                            + (marks.referencedFrom < 0 ? ", no code reference found" : ", referenced from code at "
                            + hex(marks.referencedFrom)) + ")."));
            String officialSha = n >= OFFICIAL_SIZE ? sha256Hex(data, 0, OFFICIAL_SIZE) : null;
            boolean exact = officialSha != null && officialSha.equals(build.officialSha256);
            boolean codeStock = n >= CODE_AREA_END && sha256Hex(data, 0, CODE_AREA_END).equals(build.codeAreaSha256);
            boolean madeFrom = marks.stampOffset == build.stampOffset && marks.buildOffset == build.buildOffset
                    && marks.sensorStart == build.sensorOffset;
            Build other = null;
            for (Build b : OTHER_KNOWN_BUILDS) {
                if (b != build && officialSha != null && officialSha.equals(b.officialSha256)) {
                    other = b;
                }
            }
            if (exact) {
                out.add(new Line(Level.INFO, "build", "Build", build.label + ", unchanged. " + NOT_THE_CAMERA,
                        "bytes 0 to 0x169000 hash to the known build's."));
            } else if (codeStock || madeFrom) {
                out.add(new Line(Level.INFO, "build", "Build", build.label + ", with changes (see Software below). "
                        + NOT_THE_CAMERA, "the build marks lie where that build keeps them."));
            } else if (other != null) {
                out.add(new Line(Level.WARN, "build", "Build", other.label + ", unchanged: not the public dump's. It"
                        + " belongs only in a camera that ran it. " + NOT_THE_CAMERA,
                        "bytes 0 to 0x169000 hash to that build's, SHA-256 " + other.officialSha256 + "."));
            } else {
                out.add(new Line(Level.WARN, "build", "Build", "a build the app does not know: not the public dump's."
                        + " It belongs only in the camera it was dumped from. " + NOT_THE_CAMERA,
                        "the build marks lie at other offsets than in the public dump."));
            }

            // The image sensor: the file's program for it against the camera's own, when a backup is given.
            boolean knownTable = marks.sensorSha256.equals(build.sensorSha256);
            Marks ref = reference == null ? null : findMarks(reference);
            if (ref != null && ref.sensorStart >= 0) {
                compared = true;
                boolean same = ref.sensorSha256.equals(marks.sensorSha256);
                out.add(new Line(same ? Level.PASS : Level.FAIL, "sensor-table", "Image sensor set-up", same
                        ? "the same as in your camera's backup, so the file drives the image sensor the way the"
                        + " firmware in that backup does. That is your camera's own way only if the backup was read"
                        + " before any flash: a read taken after one shows what the camera runs now, not what fits it."
                        : "different from your camera's backup. The file drives the image sensor differently from the"
                        + " firmware in your backup, and a camera whose sensor is not the one a firmware drives freezes"
                        + " in photo and video mode after the flash. Do not use this file on this camera from the app."
                        + " If the camera's own firmware is already gone and the file is a working camera's, that"
                        + " donor route goes through the toolkit on a computer (docs/02-RECOVERY.md, section D).",
                        "the file's table: " + marks.sensorPairs + " writes, SHA-256 " + marks.sensorSha256 + "; the"
                                + " backup's: " + ref.sensorPairs + " writes, SHA-256 " + ref.sensorSha256 + "."));
            } else if (ref != null) {
                out.add(new Line(Level.FAIL, "sensor-table", "Image sensor set-up", "the backup given has no image"
                        + " sensor set-up table, so the file cannot be compared with your camera's own firmware. Choose a"
                        + " complete dump of your camera's chip in step 0.", "the file's table: " + marks.sensorPairs
                        + " writes, SHA-256 " + marks.sensorSha256 + "."));
            } else if (knownTable) {
                out.add(new Line(Level.WARN, "sensor-table", "Image sensor set-up", "the same program as the public"
                        + " build's: it drives one image sensor and never checks which one is fitted. Nothing in the"
                        + " file can tell whether your camera has that sensor, and one original-series camera was left"
                        + " unable to take pictures by this very program. " + BACKUP_STEP,
                        marks.sensorPairs + " writes, SHA-256 " + marks.sensorSha256 + "."));
            } else {
                out.add(new Line(Level.WARN, "sensor-table", "Image sensor set-up", "not the public build's program:"
                        + " the file was made from another build's dump and drives the sensor differently. Nothing in"
                        + " the file can tell whether that fits your camera. " + BACKUP_STEP,
                        marks.sensorPairs + " writes, SHA-256 " + marks.sensorSha256 + "; the public build's is "
                                + build.sensorSha256 + "."));
            }

            // The software: against the backup when there is one, else against the known build.
            if (reference != null && reference.length >= SOFTWARE_END && n >= OFFICIAL_SIZE) {
                int span = Math.min(n, SOFTWARE_END);
                boolean same = regionEquals(data, 0, reference, 0, span) && (span >= SOFTWARE_END
                        || allFF(reference, span, SOFTWARE_END - span));
                if (same) {
                    out.add(new Line(Level.PASS, "software", "Software", "the program and its sounds, pictures and"
                            + " menus are the same as in your camera's backup, byte for byte.", ""));
                } else if (regionEquals(data, 0, reference, 0, CODE_AREA_END)) {
                    out.add(new Line(Level.WARN, "software", "Software", "a modified file. The program itself is your"
                            + " camera's own, but its sounds, frames, boot logo or menus differ from your backup's. Only"
                            + " put it on the card if you made it yourself or trust who made it.",
                            "bytes 0 to 0x120000 (boot header, code and data) match the backup; the resources after them"
                                    + " differ."));
                } else {
                    out.add(new Line(Level.WARN, "software", "Software", "a file whose program differs from your"
                            + " camera's own firmware: a mod that changes the program, or another build. Only put it on"
                            + " the card if you made that change on purpose, or know the file is for this camera. Your"
                            + " backup is the way back (docs/02-RECOVERY.md).",
                            "bytes 0 to 0x120000 (boot header, code and data) differ from the backup."));
                }
            } else if (n >= SOFTWARE_END && sha256Hex(data, 0, SOFTWARE_END).equals(build.softwareSha256)
                    || n == OFFICIAL_SIZE && exact) {
                out.add(new Line(Level.PASS, "software", "Software", "the program and its sounds, pictures and menus"
                        + " are the same as the public dump's, byte for byte.", ""));
            } else if (codeStock) {
                out.add(new Line(Level.WARN, "software", "Software", "a modified file. The program itself is the public"
                        + " dump's, but its sounds, frames, boot logo or menus differ. Only put it on the card if you"
                        + " made it yourself or trust who made it.",
                        "bytes 0 to 0x120000 (boot header, code and data) match the public dump; the resources after them differ."));
            } else {
                out.add(new Line(Level.WARN, "software", "Software", "a file whose program differs from the public"
                        + " dump's: a mod that changes the program, or another build. Only put it on the card if you"
                        + " made that change on purpose, or know the file is for this camera. Keep a way back ready: a"
                        + " backup of your camera (docs/02-RECOVERY.md).",
                        "bytes 0 to 0x120000 (boot header, code and data) differ from the public dump."));
            }
        }

        // 4. What the file writes into the camera's own area.
        if (n >= PHOTO_DIRECTORY + SECTOR) {
            settingsLines(data, out);
            int photos = 0;
            for (int slot : PHOTO_SLOTS) {
                if (n > slot + 2 && (data[slot] & 0xFF) == 0xFF && (data[slot + 1] & 0xFF) == 0xD8) {
                    photos++;
                }
            }
            int listed = photoDirectoryEntries(data);
            String copyOff = " If your photos matter, copy the SPIDCIM folder off the card before you flash. That is"
                    + " where the camera copies them.";
            if (photos == 0) {
                out.add(new Line(Level.PASS, "photos", "Stored photos", "the file carries none. The flash erases any"
                        + " photos now in the camera's own memory." + copyOff, ""));
            } else if (listed == 0) {
                out.add(new Line(Level.WARN, "photos", "Stored photos", "the file carries " + count(photos, "photo")
                        + " from the camera it was made from, but its photo list is empty, so your camera will not show"
                        + (photos == 1 ? " it" : " them") + ". " + (photos == 1 ? "It" : "They") + " still replace"
                        + (photos == 1 ? "s" : "") + " any photos in your camera's own memory." + copyOff,
                        "the photo directory lists no photo."));
            } else {
                out.add(new Line(Level.WARN, "photos", "Stored photos", "the file carries " + count(photos, "photo")
                        + " from the camera it was made from and lists " + (listed == photos ? (photos == 1 ? "it" : "them")
                        : count(listed, "of them")) + ", so your camera will show " + (listed == 1 ? "it" : "them")
                        + " in place of any photos in its own memory." + copyOff,
                        "the photo directory lists " + listed + "."));
            }
        } else if (official) {
            out.add(new Line(Level.INFO, "own-area", "Settings and stored photos", "the file stops before them, so"
                    + " none of another camera's settings or photos are written. See \"After the update\" for what"
                    + " the camera does with its own.", ""));
        }
        if (n >= OFFICIAL_SIZE) {
            List<Entry> entries = resourceDirectory(data);
            int count = entries.size();
            Level level = count == 43 ? Level.PASS : (count == 0 ? Level.FAIL : Level.WARN);
            String found;
            if (count == 43) {
                found = "all 43 sounds, pictures and menu files are there, as in the known builds.";
            } else if (count == 0) {
                found = "none found. The camera needs them to start. The file is damaged, or it is not Charmera"
                        + " firmware. Do not use it. " + INSTEAD;
            } else {
                found = "the file has " + count + " sounds, pictures and menu files, where the known builds have 43."
                        + " It is a modified file. Only put it on the card if you made it yourself or trust who made it.";
            }
            out.add(new Line(level, "resources", "Built-in parts", found, ""));
            if (count > 0) {
                List<String> problems = resourceProblems(data, entries);
                if (problems.isEmpty()) {
                    out.add(new Line(Level.PASS, "resource-slots", "Built-in parts, one by one", "each one is whole"
                            + " and holds what its name says.", "all " + count + " entries lie inside the resource"
                            + " area, none is erased, and each holds the kind its name says: WAV with fmt and data"
                            + " within its slot, JPEG with an end marker, GPZP that inflates with a matching trailer"
                            + " and only fixed-Huffman blocks, GPRS."));
                } else {
                    StringBuilder sb = new StringBuilder();
                    int shown = Math.min(problems.size(), MAX_LISTED_PROBLEMS);
                    for (int i = 0; i < shown; i++) {
                        sb.append(i == 0 ? "" : "; ").append(problems.get(i));
                    }
                    if (problems.size() > shown) {
                        sb.append("; and ").append(problems.size() - shown).append(" more");
                    }
                    sb.append('.');
                    out.add(new Line(Level.FAIL, "resource-slots", "Built-in parts, one by one", problems.size()
                            + " of " + count + " are damaged. The camera does not check them before it flashes. A"
                            + " damaged boot logo or menu can stop the camera starting far enough to update again. Do"
                            + " not use this file. It was most likely made from a cut-off download or a damaged dump."
                            + " Making it again from the same source does not help. Start again from a complete dump. "
                            + RESTORE_INSTEAD, sb.toString()));
                }
            }
        }
        return new Result(out, cardName, sha256Hex(data, 0, n), n, compared);
    }

    /**
     * Null when data is exactly the file that check passed (the same size and SHA-256, and check is a pass with a card
     * name), otherwise why it must not be written. The app calls this again just before it writes.
     *
     * The reason is a plain clause that starts in lower case and has no final full stop, so the screen can show it
     * after "Not written: ". It names the cause and says to go back to step 1.
     */
    public static String confirms(Result check, byte[] data) {
        if (check == null || data == null) {
            return "no file has been checked yet; get the update file in step 1 first";
        }
        if (!check.ok) {
            return "the file did not pass its checks; see the lines marked FAIL in step 1";
        }
        if (check.cardName == null) {
            return "the file's name is not an update file's name; see step 1";
        }
        if (data.length != check.size || !sha256Hex(data, 0, data.length).equals(check.sha256)) {
            return "the file changed after it was checked; get it again in step 1";
        }
        return null;
    }

    /**
     * What a backup of the camera must be for the app to take it as that camera's own dump: a full 2 MiB read of the
     * flash chip that carries the Charmera build marks and is not one of the public images. Null when it is, otherwise
     * why not, as a plain clause in lower case without a final full stop.
     */
    public static String backupProblem(byte[] data, String[] publicSha256) {
        if (data == null) {
            return "no file was read";
        }
        if (data.length != 0x200000) {
            return "it is " + bytes(data.length) + ", but a backup of the flash chip is exactly " + bytes(0x200000)
                    + (data.length == UPDATE_SIZE || data.length == OFFICIAL_SIZE ? ". This is an update file, not a dump of a chip" : "");
        }
        String sha = sha256Hex(data, 0, data.length);
        for (String s : publicSha256) {
            if (s.equalsIgnoreCase(sha)) {
                return "it is byte for byte the public stock dump: a copy of someone else's camera, or a read of a camera"
                        + " that was flashed with the public restore file and has changed no setting since. Either way it"
                        + " is not firmware known to fit your camera, so the app has nothing to compare a file with";
            }
        }
        if (!(data[0] == 'S' && data[1] == 'P' && data[2] == 'I' && data[3] == 'I')) {
            return "it does not begin as Charmera firmware does, so it is not a readable dump of the chip";
        }
        Marks marks = findMarks(data);
        if (!marks.complete()) {
            return "the marks every Charmera firmware carries were not all found (" + marks.missing() + "), so it is not a"
                    + " complete dump of the chip";
        }
        return null;
    }

    /** How many of the two directory entries at 0x17F000 mark a photo as present. An erased directory counts as none. */
    static int photoDirectoryEntries(byte[] data) {
        if (data.length < PHOTO_DIRECTORY + 16 || erased(data, PHOTO_DIRECTORY)) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < 2; i++) {
            int off = PHOTO_DIRECTORY + 8 * i;
            int used = (data[off + 2] & 0xFF) | (data[off + 3] & 0xFF) << 8;
            long length = u32(data, off + 4);
            if (used != 0 && used != 0xFFFF && length != 0 && length != 0xFFFFFFFFL) {
                n++;
            }
        }
        return n;
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    // --- settings bank ---------------------------------------------------------------------------------------

    private static void settingsLines(byte[] data, List<Line> out) {
        int records = 0;
        int current = -1;
        for (int off = SETTINGS_BASE; off < PHOTO_DIRECTORY + SECTOR; off += SECTOR) {
            if (erased(data, off)) {
                break;
            }
            records++;
            current = off;
        }
        boolean blank = records == 0;
        if (blank) {
            out.add(new Line(Level.INFO, "settings", "Settings", "the file carries none. The camera will start with its"
                    + " default settings.", "the settings area is blank."));
        } else if (current >= PHOTO_DIRECTORY) {
            out.add(new Line(Level.WARN, "settings", "Settings", "the file holds too many saved settings. The camera"
                    + " would read part of its photo list as settings and fall back to its default settings. This does"
                    + " not stop the update.", "120 or more settings records."));
        } else if (!crcOk(data, current)) {
            out.add(new Line(Level.WARN, "settings", "Settings", "the saved settings the camera would load are damaged,"
                    + " so the camera would use its default settings. This does not stop the update.",
                    "the record at 0x" + Integer.toHexString(current) + " fails its CRC."));
        } else {
            int ui = data[current + OFF_UI_SOUND] & 0xFF;
            out.add(new Line(Level.PASS, "settings", "Settings", "the file carries the saved settings of the camera it"
                    + " was made from. Your camera will start with them, with camera sounds "
                    + (ui == 0 ? "off." : "on (level " + ui + ")."),
                    records + (records == 1 ? " settings record" : " settings records") + "; the camera loads the one"
                            + " at 0x" + Integer.toHexString(current) + "."));
        }
        boolean consistent = blank || !erased(data, PHOTO_DIRECTORY);
        out.add(new Line(consistent ? Level.PASS : Level.FAIL, "photo-directory", "Photo list", consistent
                ? "the list of photos in the camera's own memory is in order."
                : "the file has settings but no photo list. The camera would read two broken photo entries from it."
                + " Do not use this file. Make it again with charmera pack on a computer, which writes an empty photo"
                + " list. " + RESTORE_INSTEAD,
                consistent ? "" : "the photo directory at 0x17F000 is erased."));
    }

    private static boolean erased(byte[] data, int off) {
        for (int i = 0; i < 8; i++) {
            if (off + i >= data.length || (data[off + i] & 0xFF) != 0xFF) {
                return off + i >= data.length;
            }
        }
        return true;
    }

    private static boolean crcOk(byte[] data, int off) {
        CRC32 crc = new CRC32();
        crc.update(data, off, CRC_LEN);
        long stored = (data[off + CRC_LEN] & 0xFFL) | (data[off + CRC_LEN + 1] & 0xFFL) << 8
                | (data[off + CRC_LEN + 2] & 0xFFL) << 16 | (data[off + CRC_LEN + 3] & 0xFFL) << 24;
        return crc.getValue() == stored;
    }

    // --- resource pack ------------------------------------------------------------------------------------------
    //
    // The camera checks nothing inside the resource pack, so a cut-off or damaged pack is flashed as it is, and the
    // boot logo is opened at every boot before the card is mounted. Each directory entry is checked against the kind
    // its name says it is, inside the slot it owns. The same rules as charmera_toolkit/verify.py resource_problems.

    /** One directory entry: a 20-byte name and the offset of its slot (sector x 512 from the pack's start). */
    static final class Entry {
        final String name;
        final long offset;

        Entry(String name, long offset) {
            this.name = name;
            this.offset = offset;
        }

        /** The kind its name says: GPZP, GPZ, WAV, JPG or PAK, or null for none of those. */
        String kind() {
            for (String ext : new String[]{"GPZP", "GPZ", "WAV", "JPG", "PAK"}) {
                if (name.endsWith(ext) && name.length() > ext.length()) {
                    return ext;
                }
            }
            return null;
        }

        String displayName() {
            String k = kind();
            return k == null ? name : name.substring(0, name.length() - k.length()) + "." + k;
        }
    }

    static List<Entry> resourceDirectory(byte[] data) {
        List<Entry> out = new ArrayList<>();
        if (!regionEquals(data, RESOURCE_BASE, new byte[]{'G', 'P', '+', 0})) {
            return out;
        }
        for (int off = DIRECTORY_START; off + DIRECTORY_ENTRY <= data.length; off += DIRECTORY_ENTRY) {
            int first = data[off] & 0xFF;
            if (first == 0 || first == 0xFF) {
                break;
            }
            int len = 20;
            while (len > 0 && data[off + len - 1] == 0) {
                len--;
            }
            StringBuilder name = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                int c = data[off + i] & 0xFF;
                name.append(c < 0x80 ? (char) c : '�');
            }
            out.add(new Entry(name.toString(), RESOURCE_BASE + u32(data, off + 20) * SECTOR));
        }
        return out;
    }

    /**
     * One line per entry whose slot lies outside the pack or the file, is erased, or does not hold the kind its name
     * says. An entry owns the bytes up to the next entry's offset; the last one up to RESOURCE_LIMIT.
     */
    /**
     * Where the last entry's slot ends: the factory self-test sector, or the end of an official-size file, which lies
     * before that sector (the camera leaves the rest of the block erased).
     */
    static int resourceLimit(int fileLength) {
        return fileLength >= OFFICIAL_SIZE && fileLength < RESOURCE_LIMIT ? fileLength : RESOURCE_LIMIT;
    }

    static List<String> resourceProblems(byte[] data, List<Entry> entries) {
        List<String> problems = new ArrayList<>();
        long dirEnd = DIRECTORY_START + (long) DIRECTORY_ENTRY * entries.size();
        int limit = resourceLimit(data.length);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            long start = e.offset;
            long end = i + 1 < entries.size() ? entries.get(i + 1).offset : limit;
            String where = e.displayName() + " at " + hex(start);
            if (start < dirEnd || end > limit) {
                problems.add(where + ": its slot " + hex(start) + ".." + hex(end) + " lies outside the resource area "
                        + hex(dirEnd) + ".." + hex(limit));
                continue;
            }
            if (end <= start) {
                problems.add(where + ": empty slot (the next entry starts at " + hex(end) + ")");
                continue;
            }
            if (end > data.length) {
                problems.add(where + ": its slot " + hex(start) + ".." + hex(end) + " runs past the end of the file ("
                        + hex(data.length) + " bytes)");
                continue;
            }
            int s = (int) start;
            int len = (int) (end - start);
            if (allFF(data, s, len)) {
                problems.add(where + ": erased (all " + len + " bytes of its slot are 0xFF)");
                continue;
            }
            String kind = e.kind();
            String why;
            if (kind == null) {
                why = "the name has no known kind (.WAV, .JPG, .GPZP, .GPZ, .PAK)";
            } else if (kind.equals("WAV")) {
                why = wavProblem(data, s, len);
            } else if (kind.equals("JPG")) {
                why = jpegProblem(data, s, len);
            } else if (kind.equals("PAK")) {
                why = regionEquals(data, s, new byte[]{'G', 'P', 'R', 'S'}) ? null
                        : "is not a GPRS pack (starts " + startBytes(data, s, len) + ")";
            } else {
                why = gpzpProblem(data, s, len);
            }
            if (why != null) {
                problems.add(where + ": " + why);
            }
        }
        return problems;
    }

    static String wavProblem(byte[] data, int s, int len) {
        if (len < 12 || !regionEquals(data, s, new byte[]{'R', 'I', 'F', 'F'})
                || !regionEquals(data, s + 8, new byte[]{'W', 'A', 'V', 'E'})) {
            return "is not a RIFF WAVE (starts " + startBytes(data, s, len) + ")";
        }
        long total = 8 + u32(data, s + 4);
        if (total > len) {
            return "RIFF size says " + total + " B but the slot holds " + len + " B";
        }
        long p = 12;
        boolean fmt = false;
        long dataEnd = -1;
        while (p + 8 <= total) {
            int at = s + (int) p;
            long size = u32(data, at + 4);
            if (regionEquals(data, at, new byte[]{'f', 'm', 't', ' '}) && size >= 16) {
                fmt = true;
            } else if (regionEquals(data, at, new byte[]{'d', 'a', 't', 'a'})) {
                dataEnd = at + 8 + size;
                break;
            }
            p += 8 + size + (size & 1);
        }
        if (!fmt || dataEnd < 0) {
            return "has no " + (!fmt && dataEnd < 0 ? "fmt chunk and no data chunk" : !fmt ? "fmt chunk" : "data chunk");
        }
        if (dataEnd > data.length) {
            return "its sample data runs past the end of the file";
        }
        return null;
    }

    static String jpegProblem(byte[] data, int s, int len) {
        if (len < 2 || (data[s] & 0xFF) != 0xFF || (data[s + 1] & 0xFF) != 0xD8) {
            return "is not a JPEG (starts " + startBytes(data, s, len) + ", not FF D8)";
        }
        for (int i = s + 2; i + 1 < s + len; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD9) {
                return null;
            }
        }
        return "has no FF D9 end marker inside its slot (cut off or partly erased)";
    }

    /** 'GPZP', a raw DEFLATE stream, then {u32 CRC-32, u32 length} of the inflated data; padding may follow. */
    static String gpzpProblem(byte[] data, int s, int len) {
        if (!regionEquals(data, s, new byte[]{'G', 'P', 'Z', 'P'}) || len < 4) {
            return "is not GPZP (starts " + startBytes(data, s, len) + ")";
        }
        Inflater inflater = new Inflater(true);
        int streamLen;
        long inflated = 0;
        CRC32 crc = new CRC32();
        try {
            inflater.setInput(data, s + 4, len - 4);
            byte[] buf = new byte[64 * 1024];
            while (!inflater.finished()) {
                int k = inflater.inflate(buf);
                if (k == 0 && !inflater.finished()) {
                    return "GPZP: the DEFLATE stream ends before its final block";
                }
                crc.update(buf, 0, k);
                inflated += k;
            }
            int rest = inflater.getRemaining();
            streamLen = len - 4 - rest;
            if (rest < 8) {
                return "GPZP: the 8-byte CRC/length trailer is missing";
            }
        } catch (DataFormatException e) {
            return "GPZP: the DEFLATE stream is corrupt (" + e.getMessage() + ")";
        } finally {
            inflater.end();
        }
        int trailer = s + 4 + streamLen;
        long storedCrc = u32(data, trailer);
        long storedLen = u32(data, trailer + 4);
        if (storedLen != inflated) {
            return "GPZP: trailer length " + storedLen + " but the stream inflates to " + inflated + " bytes";
        }
        if (storedCrc != crc.getValue()) {
            return String.format(Locale.ROOT, "GPZP: trailer CRC-32 %08x does not match the payload (%08x)",
                    storedCrc, crc.getValue());
        }
        return nonFixedBlocks(data, s + 4, streamLen);
    }

    // A small DEFLATE block walker, the one in charmera_toolkit/frames.py deflate_blocks: enough to prove a stream
    // has fixed-Huffman blocks only.

    private static final int[] LEN_EXTRA = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4,
            5, 5, 5, 5, 0};
    private static final int[] DIST_EXTRA = {0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10,
            11, 11, 12, 12, 13, 13};

    private static final class Bits {
        final byte[] data;
        int pos;
        final int end;
        int buf;
        int cnt;

        Bits(byte[] data, int from, int len) {
            this.data = data;
            this.pos = from;
            this.end = from + len;
        }

        int bit() {
            if (cnt == 0) {
                if (pos >= end) {
                    throw new IllegalArgumentException("DEFLATE stream ends inside a block");
                }
                buf = data[pos++] & 0xFF;
                cnt = 8;
            }
            int b = buf & 1;
            buf >>= 1;
            cnt--;
            return b;
        }

        int lsb(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v |= bit() << i;
            }
            return v;
        }

        int msb(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | bit();
            }
            return v;
        }

        void align() {
            buf = 0;
            cnt = 0;
        }
    }

    private static int fixedLitLen(Bits br) {
        int code = br.msb(7);
        if (code <= 0x17) {
            return 256 + code;
        }
        code = (code << 1) | br.bit();
        if (code >= 0x30 && code <= 0xBF) {
            return code - 0x30;
        }
        if (code >= 0xC0 && code <= 0xC7) {
            return 280 + code - 0xC0;
        }
        code = (code << 1) | br.bit();
        if (code >= 0x190 && code <= 0x1FF) {
            return 144 + code - 0x190;
        }
        throw new IllegalArgumentException("invalid fixed-Huffman code");
    }

    /**
     * Null when every block of the raw DEFLATE stream at data[from..from+len) is a fixed-Huffman block; otherwise
     * which other blocks it holds, or why it cannot be walked. Walking stops at the first dynamic block, as in
     * frames.py.
     */
    static String nonFixedBlocks(byte[] data, int from, int len) {
        Bits br = new Bits(data, from, len);
        boolean stored = false;
        boolean dynamic = false;
        try {
            while (true) {
                int last = br.bit();
                int type = br.lsb(2);
                if (type == 0) {
                    br.align();
                    if (br.pos + 4 > br.end) {
                        throw new IllegalArgumentException("stored block header is truncated");
                    }
                    int n = (data[br.pos] & 0xFF) | (data[br.pos + 1] & 0xFF) << 8;
                    int nn = (data[br.pos + 2] & 0xFF) | (data[br.pos + 3] & 0xFF) << 8;
                    if (n != (~nn & 0xFFFF)) {
                        throw new IllegalArgumentException("stored block length check fails");
                    }
                    br.pos += 4 + n;
                    stored = true;
                } else if (type == 1) {
                    while (true) {
                        int sym = fixedLitLen(br);
                        if (sym == 256) {
                            break;
                        }
                        if (sym > 256) {
                            if (sym > 285) {
                                throw new IllegalArgumentException("invalid length code");
                            }
                            br.lsb(LEN_EXTRA[sym - 257]);
                            int dist = br.msb(5);
                            if (dist > 29) {
                                throw new IllegalArgumentException("invalid distance code");
                            }
                            br.lsb(DIST_EXTRA[dist]);
                        }
                    }
                } else if (type == 2) {
                    dynamic = true;
                    break;
                } else {
                    throw new IllegalArgumentException("reserved block type 3");
                }
                if (last == 1) {
                    break;
                }
            }
        } catch (IllegalArgumentException e) {
            return "GPZP: the DEFLATE blocks cannot be walked (" + e.getMessage() + ")";
        }
        if (!stored && !dynamic) {
            return null;
        }
        String other = dynamic && stored ? "dynamic and stored" : dynamic ? "dynamic" : "stored";
        return "uses " + other + " DEFLATE blocks; every stock stream is fixed-Huffman only";
    }

    private static boolean allFF(byte[] data, int from, int len) {
        for (int i = from; i < from + len; i++) {
            if ((data[i] & 0xFF) != 0xFF) {
                return false;
            }
        }
        return true;
    }

    private static String startBytes(byte[] data, int from, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < from + Math.min(4, len) && i < data.length; i++) {
            sb.append(sb.length() == 0 ? "" : " ").append(String.format(Locale.ROOT, "%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static long u32(byte[] data, int off) {
        return (data[off] & 0xFFL) | (data[off + 1] & 0xFFL) << 8 | (data[off + 2] & 0xFFL) << 16
                | (data[off + 3] & 0xFFL) << 24;
    }

    private static String hex(long v) {
        return "0x" + Long.toHexString(v);
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    /** The camera's checksum: the unsigned 32-bit sum of every byte. */
    public static long byteSum(byte[] data) {
        long sum = 0;
        for (byte b : data) {
            sum += b & 0xFF;
        }
        return sum & 0xFFFFFFFFL;
    }

    static String hex8(long v) {
        return String.format(Locale.ROOT, "%08X", v & 0xFFFFFFFFL);
    }

    /** "1,966,080 bytes". */
    static String bytes(long n) {
        return String.format(Locale.ROOT, "%,d bytes", n);
    }

    static boolean isRealStamp(String stamp) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmm", Locale.ROOT);
        f.setLenient(false);
        try {
            f.parse(stamp);
            return true;
        } catch (ParseException e) {
            return false;
        }
    }

    /** True when data[off..off+len) equals other[otherOff..otherOff+len), both in range. */
    static boolean regionEquals(byte[] data, int off, byte[] other, int otherOff, int len) {
        if (off < 0 || otherOff < 0 || off + len > data.length || otherOff + len > other.length) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (data[off + i] != other[otherOff + i]) {
                return false;
            }
        }
        return true;
    }

    static boolean regionEquals(byte[] data, int off, byte[] expected) {
        if (off < 0 || off + expected.length > data.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[off + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    public static String sha256Hex(byte[] data, int from, int to) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(data, from, Math.max(0, Math.min(to, data.length) - from));
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
