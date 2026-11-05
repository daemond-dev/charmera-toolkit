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
 * The checks `charmera verify` makes, for the one file size the toolkit produces (0x1E0000 bytes). No Android
 * imports, so it runs on a plain JVM for the tests in android/test.
 *
 * A file passes only when every FAIL-level check passes. WARN lines are shown and do not block.
 */
public final class UpdateCheck {

    public static final int UPDATE_SIZE = 0x1E0000;
    public static final String PRODUCT = "5408";

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

    /** sha256 of bytes 0..0x120000 (CODE_AREA_END) of the original 2025 series stock dump; tests/test_android.py checks it. */
    static final String ORIGINAL_2025_CODE_AREA_SHA256 = "a96b29c21a6c20d66df4636797cb13224e06cdb223c3cf6a7ef06dde6a9c0dc7";

    /** What identifies a firmware build. The real one is ORIGINAL_2025; tests make their own. */
    public static final class Build {
        final String label;
        final String stamp;
        final int stampOffset;
        final byte[] buildString;
        final int buildOffset;
        final byte[] compiled;
        final int compiledOffset;
        /** sha256 of bytes 0..0x170000: header, code, data and resources. */
        final String softwareSha256;
        /** sha256 of bytes 0..0x120000: header, code and data, everything below the resource pack. */
        final String codeAreaSha256;
        final int sensorStart;
        final int sensorEnd;
        final String sensorSha256;

        public Build(String label, String stamp, int stampOffset, String buildString, int buildOffset, String compiled,
                     int compiledOffset, String softwareSha256, String codeAreaSha256, int sensorStart, int sensorEnd,
                     String sensorSha256) {
            this.label = label;
            this.stamp = stamp;
            this.stampOffset = stampOffset;
            this.buildString = buildString.getBytes(StandardCharsets.US_ASCII);
            this.buildOffset = buildOffset;
            this.compiled = compiled.getBytes(StandardCharsets.US_ASCII);
            this.compiledOffset = compiledOffset;
            this.softwareSha256 = softwareSha256;
            this.codeAreaSha256 = codeAreaSha256;
            this.sensorStart = sensorStart;
            this.sensorEnd = sensorEnd;
            this.sensorSha256 = sensorSha256;
        }
    }

    /** Values from charmera_toolkit/image.py (the code-area hash from the stock dump itself); keep them in step. */
    public static final Build ORIGINAL_2025 = new Build(
            "original 2025 series (matte body, yellow Kodak boot screen), build JH5408 20250520",
            "202505201029", 0x218C4, "JH5408  20250520", 0x18964, "Oct 14 2025", 0xF3978,
            "fe88e8de57e7b1ff1a0856127ee09a21fb3ce2dde2684662c533719b80adc5c8",
            ORIGINAL_2025_CODE_AREA_SHA256,
            0xF5C6C, 0xF5CE8, "aea3976bdf7ba2e747d8b56141f7ef0b4e3262481540dc2869457bc814fcb755");

    /** JH_ + 4-digit product + 12-digit stamp + _ + 8 hex digits, anywhere in the name (copies get " (1)" added). */
    static final Pattern NAME = Pattern.compile("JH_(\\d{4})(\\d{12})_([0-9A-Fa-f]{8})");

    public enum Level { PASS, INFO, WARN, FAIL }

    public static final class Line {
        public final Level level;
        public final String name;
        public final String detail;

        Line(Level level, String name, String detail) {
            this.level = level;
            this.name = name;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%-4s %-15s %s", level, name, detail);
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

        Result(List<Line> lines, String cardName, String sha256, int size) {
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
        }

        public String report() {
            StringBuilder sb = new StringBuilder();
            for (Line l : lines) {
                sb.append(l).append('\n');
            }
            return sb.toString();
        }
    }

    private UpdateCheck() {
    }

    public static Result check(String displayName, byte[] data) {
        return check(displayName, data, ORIGINAL_2025);
    }

    public static Result check(String displayName, byte[] data, Build build) {
        List<Line> out = new ArrayList<>();
        String cardName = null;
        long sum = byteSum(data);

        // 1. The name: the camera only looks for JH_5408<stamp>_<sum>.bin, and the sum is its only integrity check.
        Matcher m = NAME.matcher(displayName == null ? "" : displayName);
        if (!m.find()) {
            out.add(new Line(Level.FAIL, "name", "\"" + displayName + "\" does not contain JH_5408<12 digits>_<8 hex digits>."
                    + " Use the file exactly as pack wrote it: the sum in its name is how the camera, and this app, know"
                    + " the file arrived intact. This file adds up to " + hex8(sum) + "."));
        } else {
            String product = m.group(1);
            String stamp = m.group(2);
            long named = Long.parseLong(m.group(3), 16);
            cardName = "JH_" + product + stamp + "_" + hex8(named) + ".bin";
            out.add(new Line(Level.PASS, "name", "JH_" + product + " stamp " + stamp + " checksum " + hex8(named)));
            if (!cardName.equals(displayName)) {
                out.add(new Line(Level.INFO, "card-name", "will be written to the card as " + cardName));
            }
            out.add(new Line(product.equals(PRODUCT) ? Level.PASS : Level.FAIL, "product",
                    product.equals(PRODUCT) ? "product code " + product
                            : "product code " + product + ", but the Charmera's updater looks for JH_" + PRODUCT));
            out.add(new Line(named == sum ? Level.PASS : Level.FAIL, "checksum", named == sum
                    ? "the bytes add up to " + hex8(sum) + ", as the name says"
                    : "the name says " + hex8(named) + " but the bytes add up to " + hex8(sum)
                    + ": the file is damaged or was changed after it was named. The camera would show"
                    + " \"Program error\" and change nothing, but do not use it."));
            boolean differs = !stamp.equals(build.stamp);
            out.add(new Line(differs ? Level.PASS : Level.FAIL, "stamp", differs
                    ? "differs from the camera's own " + build.stamp
                    : "equals the camera's own stamp " + build.stamp + ", so the camera would ignore the file"));
            boolean realDate = isRealStamp(stamp);
            out.add(new Line(realDate ? Level.PASS : Level.WARN, "stamp-date",
                    realDate ? "stamp is a real date" : "stamp is not a valid YYYYMMDDhhmm (the camera does not care)"));
        }

        // 2. Size and structure.
        int n = data.length;
        if (n == UPDATE_SIZE) {
            out.add(new Line(Level.PASS, "size", n + " bytes = 30 blocks of 64 KiB (the size every published update has)"));
            out.add(new Line(Level.INFO, "final-erase", "after programming, the updater also erases block 0x1F0000, which is blank anyway"));
        } else {
            out.add(new Line(Level.FAIL, "size", n + " bytes; this app only puts 0x1E0000-byte (1,966,080) files on a card."
                    + (n == 0x200000 ? " A full 2 MiB dump must never go on a card: pack trims it." : "")
                    + " Use charmera pack and verify on a computer for anything else."));
        }
        boolean magic = n >= 4 && data[0] == 'S' && data[1] == 'P' && data[2] == 'I' && data[3] == 'I';
        out.add(new Line(magic ? Level.PASS : Level.FAIL, "magic",
                magic ? "starts with 'SPII'" : "does not start with 'SPII', so the camera would not boot from it"));

        // 3. Which camera it is for. A file for another edition can stop the camera taking pictures.
        boolean stampOk = regionEquals(data, build.stampOffset, build.stamp.getBytes(StandardCharsets.US_ASCII));
        boolean buildOk = regionEquals(data, build.buildOffset, build.buildString);
        if (!(stampOk && buildOk)) {
            out.add(new Line(Level.FAIL, "series", "the build stamps of the " + build.label + " are not in this file;"
                    + " it is for another camera or edition, or not a Charmera firmware at all"));
        } else {
            out.add(new Line(Level.PASS, "series", build.label));
            boolean sensor = n >= build.sensorEnd
                    && sha256Hex(data, build.sensorStart, build.sensorEnd).equals(build.sensorSha256);
            out.add(new Line(sensor ? Level.PASS : Level.FAIL, "sensor-table", sensor
                    ? "the image sensor set-up table is the stock one"
                    : "the image sensor set-up table differs from stock; no toolkit mod changes it, and the wrong table"
                    + " is what stops other editions from taking pictures"));
            if (n >= SOFTWARE_END && sha256Hex(data, 0, SOFTWARE_END).equals(build.softwareSha256)) {
                out.add(new Line(Level.PASS, "software", "bytes 0..0x170000 are identical to the stock firmware"));
            } else if (n >= CODE_AREA_END && sha256Hex(data, 0, CODE_AREA_END).equals(build.codeAreaSha256)) {
                out.add(new Line(Level.WARN, "software", "a mod: bytes 0..0x120000 (boot header, code and data) are"
                        + " stock, the resources (sounds, frames, logo, menus) differ. Only flash files you built yourself"
                        + " or trust."));
            } else {
                out.add(new Line(Level.WARN, "software", "a mod that changes the boot header, the code or its data"
                        + " (bytes 0..0x120000: a tweak or another patch). Only flash it if you made that change on"
                        + " purpose, and only with a way back (see Recovery)."));
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
            out.add(new Line(photos == 0 ? Level.PASS : Level.WARN, "photos", photos == 0
                    ? "no photos stored in the file"
                    : photos + " photo(s) stored in the file, from the camera it was made from"
                    + " (pack --blank-photos removes them)"));
        }
        if (n >= SOFTWARE_END) {
            List<Entry> entries = resourceDirectory(data);
            int count = entries.size();
            Level level = count == 43 ? Level.PASS : (count == 0 ? Level.FAIL : Level.WARN);
            out.add(new Line(level, "resources", count + " resource entries" + (count == 43 ? "" : " (stock has 43)")));
            if (count > 0) {
                List<String> problems = resourceProblems(data, entries);
                if (problems.isEmpty()) {
                    out.add(new Line(Level.PASS, "resource-slots", "all " + count + " entries lie inside the pack, none"
                            + " is erased, and each holds the kind its name says (WAV with fmt and data within its slot,"
                            + " JPEG with an end marker, GPZP that inflates with a matching trailer and only fixed-Huffman"
                            + " blocks, GPRS)"));
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append(problems.size()).append(" of ").append(count).append(" entries are damaged: ");
                    int listed = Math.min(problems.size(), MAX_LISTED_PROBLEMS);
                    for (int i = 0; i < listed; i++) {
                        sb.append(i == 0 ? "" : "; ").append(problems.get(i));
                    }
                    if (problems.size() > listed) {
                        sb.append("; and ").append(problems.size() - listed).append(" more");
                    }
                    sb.append(". The camera checks none of this; a damaged boot logo or menu resource can stop it"
                            + " booting far enough to update again.");
                    out.add(new Line(Level.FAIL, "resource-slots", sb.toString()));
                }
            }
        }
        return new Result(out, cardName, sha256Hex(data, 0, n), n);
    }

    /**
     * Null when data is exactly the file that check passed (the same size and SHA-256, and check is a pass with a card
     * name), otherwise why it must not be written. The app calls this again just before it writes.
     */
    public static String confirms(Result check, byte[] data) {
        if (check == null || data == null) {
            return "no checked file";
        }
        if (!check.ok) {
            return "the file did not pass its checks";
        }
        if (check.cardName == null) {
            return "the file's name could not be read";
        }
        if (data.length != check.size || !sha256Hex(data, 0, data.length).equals(check.sha256)) {
            return "the bytes to be written are not the file that was checked";
        }
        return null;
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
            out.add(new Line(Level.INFO, "settings", "settings bank blank: the camera starts with its defaults"));
        } else if (current >= PHOTO_DIRECTORY) {
            out.add(new Line(Level.WARN, "settings", "120 or more records: the camera would read the photo directory as"
                    + " settings and fall back to defaults (pack --factory-settings fixes this)"));
        } else if (!crcOk(data, current)) {
            out.add(new Line(Level.WARN, "settings", "the record the camera would load (at 0x" + Integer.toHexString(current)
                    + ") fails its CRC, so the camera would use its defaults"));
        } else {
            int ui = data[current + OFF_UI_SOUND] & 0xFF;
            out.add(new Line(Level.PASS, "settings", records + " record(s); the camera will load the one at 0x"
                    + Integer.toHexString(current) + ": UI sounds " + (ui == 0 ? "OFF" : "on (level " + ui + ")")));
        }
        boolean consistent = blank || !erased(data, PHOTO_DIRECTORY);
        out.add(new Line(consistent ? Level.PASS : Level.FAIL, "photo-directory", consistent
                ? "photo directory consistent with the settings"
                : "settings exist but the photo directory at 0x17F000 is erased; the camera would read two bogus photo"
                + " entries (pack zeroes it)"));
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
    static List<String> resourceProblems(byte[] data, List<Entry> entries) {
        List<String> problems = new ArrayList<>();
        long dirEnd = DIRECTORY_START + (long) DIRECTORY_ENTRY * entries.size();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            long start = e.offset;
            long end = i + 1 < entries.size() ? entries.get(i + 1).offset : RESOURCE_LIMIT;
            String where = e.displayName() + " at " + hex(start);
            if (start < dirEnd || end > RESOURCE_LIMIT) {
                problems.add(where + ": its slot " + hex(start) + ".." + hex(end) + " lies outside the resource area "
                        + hex(dirEnd) + ".." + hex(RESOURCE_LIMIT));
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
