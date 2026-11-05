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

/**
 * The checks `charmera verify` makes, for the one file size the toolkit produces (0x1E0000 bytes), plus a few that
 * the app makes stricter. No Android imports, so it runs on a plain JVM for the tests in android/test.
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
    static final int[] PHOTO_SLOTS = {0x180000, 0x1A5800};

    /** What identifies a firmware build. The real one is ORIGINAL_2025; tests make their own. */
    public static final class Build {
        final String label;
        final String stamp;
        final int stampOffset;
        final byte[] buildString;
        final int buildOffset;
        final byte[] compiled;
        final int compiledOffset;
        final String softwareSha256;
        final String codeSha256;
        final int sensorStart;
        final int sensorEnd;
        final String sensorSha256;

        public Build(String label, String stamp, int stampOffset, String buildString, int buildOffset, String compiled,
                     int compiledOffset, String softwareSha256, String codeSha256, int sensorStart, int sensorEnd,
                     String sensorSha256) {
            this.label = label;
            this.stamp = stamp;
            this.stampOffset = stampOffset;
            this.buildString = buildString.getBytes(StandardCharsets.US_ASCII);
            this.buildOffset = buildOffset;
            this.compiled = compiled.getBytes(StandardCharsets.US_ASCII);
            this.compiledOffset = compiledOffset;
            this.softwareSha256 = softwareSha256;
            this.codeSha256 = codeSha256;
            this.sensorStart = sensorStart;
            this.sensorEnd = sensorEnd;
            this.sensorSha256 = sensorSha256;
        }
    }

    /** Values from charmera_toolkit/image.py; keep the two in step. */
    public static final Build ORIGINAL_2025 = new Build(
            "original 2025 series (matte body, yellow Kodak boot screen), build JH5408 20250520",
            "202505201029", 0x218C4, "JH5408  20250520", 0x18964, "Oct 14 2025", 0xF3978,
            "fe88e8de57e7b1ff1a0856127ee09a21fb3ce2dde2684662c533719b80adc5c8",
            "297506be352e2efb0e9231936197c0798f2633dd27a005ae64a0b3ad547d33f6",
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
            } else if (n >= 0x100000 && sha256Hex(data, 0x200, 0x100000).equals(build.codeSha256)) {
                out.add(new Line(Level.WARN, "software", "a mod: the code is stock, the resources (sounds, frames, logo)"
                        + " differ. Only flash files you built yourself or trust."));
            } else {
                out.add(new Line(Level.WARN, "software", "a mod that changes the code (a tweak or another patch). Only"
                        + " flash it if you made that change on purpose, and only with a way back (see Recovery)."));
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
            int entries = resourceCount(data);
            Level level = entries == 43 ? Level.PASS : (entries == 0 ? Level.FAIL : Level.WARN);
            out.add(new Line(level, "resources", entries + " resource entries" + (entries == 43 ? "" : " (stock has 43)")));
        }
        return new Result(out, cardName, sha256Hex(data, 0, n), n);
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

    private static int resourceCount(byte[] data) {
        if (!regionEquals(data, RESOURCE_BASE, new byte[]{'G', 'P', '+', 0})) {
            return 0;
        }
        int count = 0;
        for (int off = RESOURCE_BASE + 0x18; off + 24 <= data.length; off += 24) {
            int first = data[off] & 0xFF;
            if (first == 0 || first == 0xFF) {
                break;
            }
            count++;
        }
        return count;
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
