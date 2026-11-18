package io.github.daemonddev.charmera;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs the dates the Kodak Charmera writes, on in-memory copies only: the port of charmera_toolkit/exif.py (the
 * exif-fix command) that the photo backup uses for the copies it puts in the zip. No Android imports, so it runs on a
 * plain JVM in android/test/DateFixTest.java.
 *
 * JPEG: the camera writes its dates as "YYYY:MM:DD:HH:MM:SS" (a colon where the space belongs), 640x480 as the pixel
 * size of a bigger frame, a MakerNote offset past the end of the block and Make/Model "Generalplus"/"CBB3". The old
 * block is never edited in place: a bounds-checked reader salvages orientation, YCbCr positioning, make, model and the
 * dates (the MakerNote is never read), the true size comes from the frame header, and a fresh little-endian EXIF APP1
 * goes where the old one was (or after the JFIF APP0, or after SOI, when there was none). Every other byte, the image
 * data included, is copied unchanged. Only a JPEG that shows the camera's fingerprints is rebuilt (isCharmeraJpeg); any
 * other JPEG comes back unchanged.
 *
 * AVI: the public build writes ICRD "2010-06-29" into every video (and the community tools also expect an IDIT in
 * asctime form). A field holding exactly 2010-06-29 is rewritten with the file's modification time, in the same text
 * form, NUL-padded to the same length, so no other byte moves; any other date is left alone.
 *
 * Neither fix throws on bad input: it returns the input unchanged and says why in Result.note.
 */
final class DateFix {

    static final String CAMERA_MAKE = "Kodak";
    static final String CAMERA_MODEL = "Charmera";
    static final String LENS_MAKE = "Kodak";
    static final String LENS_MODEL = "Charmera fixed lens (35mm-equiv f/2.4)";
    static final int FNUMBER_NUM = 24;
    static final int FNUMBER_DEN = 10;
    static final int FOCAL_LENGTH_35MM = 35;
    static final String CHIP_MAKE = "Generalplus";
    static final byte[] GPENCODER_SIGNATURE = ascii("GPEncoder");
    static final int SIGNATURE_SAMPLE_SIZE = 4096;
    static final int MAX_AVI_DATE_FIELD = 64;
    static final String EXIF_DATE_FORMAT = "yyyy:MM:dd HH:mm:ss";

    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};
    private static final Pattern VALID_DATE = Pattern.compile("^\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}$");
    private static final Pattern MALFORMED_DATE =
            Pattern.compile("^(\\d{4}):(\\d{2}):(\\d{2}):(\\d{2}):(\\d{2}):(\\d{2})$");

    static final int TYPE_ASCII = 2;
    static final int TYPE_SHORT = 3;
    static final int TYPE_LONG = 4;
    static final int TYPE_RATIONAL = 5;
    static final int TYPE_UNDEFINED = 7;

    static final int TAG_MAKE = 0x010F;
    static final int TAG_MODEL = 0x0110;
    static final int TAG_ORIENTATION = 0x0112;
    static final int TAG_SOFTWARE = 0x0131;
    static final int TAG_DATETIME = 0x0132;
    static final int TAG_YCBCR_POSITIONING = 0x0213;
    static final int TAG_EXIF_IFD = 0x8769;
    static final int TAG_FNUMBER = 0x829D;
    static final int TAG_EXIF_VERSION = 0x9000;
    static final int TAG_DATETIME_ORIGINAL = 0x9003;
    static final int TAG_DATETIME_DIGITIZED = 0x9004;
    static final int TAG_COMPONENTS_CONFIG = 0x9101;
    static final int TAG_FLASHPIX_VERSION = 0xA000;
    static final int TAG_COLOR_SPACE = 0xA001;
    static final int TAG_PIXEL_X = 0xA002;
    static final int TAG_PIXEL_Y = 0xA003;
    static final int TAG_FOCAL_LENGTH_35MM = 0xA405;
    static final int TAG_LENS_MAKE = 0xA433;
    static final int TAG_LENS_MODEL = 0xA434;

    private DateFix() {
    }

    /** What a fix gave back: the bytes to store (the input itself when nothing changed), and a one-line note. */
    static final class Result {
        final byte[] data;
        final boolean changed;
        final String note;
        /** The capture time written ("yyyy:MM:dd HH:mm:ss"), or null when nothing was rebuilt or patched. */
        final String dateTime;
        /** "exif" when the date came from the photo's own EXIF, "mtime" from the file time, or null. */
        final String dateSource;

        Result(byte[] data, boolean changed, String note, String dateTime, String dateSource) {
            this.data = data;
            this.changed = changed;
            this.note = note;
            this.dateTime = dateTime;
            this.dateSource = dateSource;
        }

        static Result unchanged(byte[] data, String note) {
            return new Result(data, false, note, null, null);
        }
    }

    // ---- JPEG segment scan -------------------------------------------------------------------------------------

    /** Where the Exif APP1 is (or should go) and the frame's true size. exifStart == exifEnd: none, insert there. */
    static final class Layout {
        int width;
        int height;
        int exifStart = 2;
        int exifEnd = 2;
        boolean exifFound;
        final List<int[]> exifRanges = new ArrayList<>();
    }

    private static boolean isSof(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    /** The marker segments up to the start of scan data, or null when data is not a JPEG (no SOI). */
    static Layout scan(byte[] data) {
        if (data == null || data.length < 2 || u8(data, 0) != 0xFF || u8(data, 1) != 0xD8) {
            return null;
        }
        Layout layout = new Layout();
        int n = data.length;
        int pos = 2;
        while (pos + 1 < n) {
            if (u8(data, pos) != 0xFF) {
                break;
            }
            while (pos + 1 < n && u8(data, pos + 1) == 0xFF) {
                pos++; // fill bytes before a marker
            }
            if (pos + 1 >= n) {
                break;
            }
            int marker = u8(data, pos + 1);
            if (marker == 0xD9 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2; // standalone markers, no length field
                continue;
            }
            if (marker == 0xDA) {
                break; // start of scan
            }
            if (pos + 4 > n) {
                break;
            }
            int segLen = be16(data, pos + 2);
            if (segLen < 2) {
                break;
            }
            int segStart = pos;
            int segEnd = pos + 2 + segLen;
            if (segEnd > n) {
                break;
            }
            int payload = pos + 4;
            int payloadLen = segEnd - payload;
            if (marker == 0xE1 && startsWith(data, payload, payloadLen, EXIF_HEADER)) {
                layout.exifRanges.add(new int[]{segStart, segEnd});
                if (!layout.exifFound) {
                    layout.exifStart = segStart;
                    layout.exifEnd = segEnd;
                    layout.exifFound = true;
                }
            } else if (marker == 0xE0 && !layout.exifFound) {
                layout.exifStart = segEnd;
                layout.exifEnd = segEnd;
            } else if (isSof(marker) && payloadLen >= 5) {
                layout.height = be16(data, payload + 1);
                layout.width = be16(data, payload + 3);
            }
            pos = segEnd;
        }
        return layout;
    }

    /**
     * A copy of data with its first Exif APP1 replaced by one wrapping payload (every further Exif APP1 dropped), or
     * with it inserted at the insertion point when there was none. Null when the segment would be too large.
     */
    static byte[] replaceExif(byte[] data, Layout layout, byte[] payload) {
        int segLen = payload.length + 2;
        if (segLen > 0xFFFF) {
            return null;
        }
        List<int[]> ranges = layout.exifRanges;
        if (ranges.isEmpty()) {
            ranges = new ArrayList<>();
            ranges.add(new int[]{layout.exifStart, layout.exifEnd});
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + segLen + 2);
        int pos = 0;
        for (int i = 0; i < ranges.size(); i++) {
            int[] r = ranges.get(i);
            out.write(data, pos, r[0] - pos);
            if (i == 0) {
                out.write(0xFF);
                out.write(0xE1);
                out.write((segLen >> 8) & 0xFF);
                out.write(segLen & 0xFF);
                out.write(payload, 0, payload.length);
            }
            pos = r[1];
        }
        out.write(data, pos, data.length - pos);
        return out.toByteArray();
    }

    // ---- Tolerant TIFF/EXIF reader -----------------------------------------------------------------------------

    /** What the reader salvages; anything out of range stays empty. The MakerNote is never looked at. */
    static final class Info {
        boolean present;
        int orientation;
        int ycbcrPositioning;
        String make = "";
        String model = "";
        String software = "";
        String dateTime = "";
        String dateTimeOriginal = "";
        String dateTimeDigitized = "";
        /** Read for the tests only (the rebuilt block's PixelX/YDimension); the fix takes the size from the frame. */
        long pixelX;
        long pixelY;
    }

    /** The TIFF block that follows "Exif\0\0", little or big endian. */
    static Info readExif(byte[] tiff, int start, int end) {
        Info info = new Info();
        if (tiff == null || start < 0 || end > tiff.length || end - start < 8) {
            return info;
        }
        boolean little;
        if (tiff[start] == 'I' && tiff[start + 1] == 'I') {
            little = true;
        } else if (tiff[start] == 'M' && tiff[start + 1] == 'M') {
            little = false;
        } else {
            return info;
        }
        Tiff t = new Tiff(tiff, start, end - start, little);
        if (t.u16(2) != 0x2A) {
            return info;
        }
        info.present = true;
        readDir(t, t.u32(4), info, false);
        return info;
    }

    /** The salvaged EXIF of a JPEG's first Exif APP1; an empty Info without one. */
    static Info sourceExif(byte[] data, Layout layout) {
        if (layout != null && layout.exifFound) {
            int payload = layout.exifStart + 4;
            if (layout.exifEnd - payload >= 6) {
                return readExif(data, payload + 6, layout.exifEnd);
            }
        }
        return new Info();
    }

    private static final class Tiff {
        final byte[] b;
        final int base;
        final int len;
        final boolean little;

        Tiff(byte[] b, int base, int len, boolean little) {
            this.b = b;
            this.base = base;
            this.len = len;
            this.little = little;
        }

        int u16(long off) {
            int i = base + (int) off;
            return little ? (u8(b, i) | u8(b, i + 1) << 8) : (u8(b, i) << 8 | u8(b, i + 1));
        }

        long u32(long off) {
            int i = base + (int) off;
            long v = little ? (u8(b, i) | u8(b, i + 1) << 8 | u8(b, i + 2) << 16 | (long) u8(b, i + 3) << 24)
                    : ((long) u8(b, i) << 24 | u8(b, i + 1) << 16 | u8(b, i + 2) << 8 | u8(b, i + 3));
            return v & 0xFFFFFFFFL;
        }
    }

    private static void readDir(Tiff t, long off, Info info, boolean inExif) {
        if (off < 0 || off + 2 > t.len) {
            return;
        }
        int count = t.u16(off);
        long pos = off + 2;
        for (int k = 0; k < count; k++) {
            if (pos + 12 > t.len) {
                return;
            }
            int tag = t.u16(pos);
            int typ = t.u16(pos + 2);
            long cnt = t.u32(pos + 4);
            long value = pos + 8;
            pos += 12;
            if (!inExif) {
                if (tag == TAG_ORIENTATION && typ == TYPE_SHORT) {
                    info.orientation = t.u16(value);
                } else if (tag == TAG_YCBCR_POSITIONING && typ == TYPE_SHORT) {
                    info.ycbcrPositioning = t.u16(value);
                } else if (tag == TAG_MAKE) {
                    info.make = readAscii(t, value, typ, cnt);
                } else if (tag == TAG_MODEL) {
                    info.model = readAscii(t, value, typ, cnt);
                } else if (tag == TAG_SOFTWARE) {
                    info.software = readAscii(t, value, typ, cnt);
                } else if (tag == TAG_DATETIME) {
                    info.dateTime = readAscii(t, value, typ, cnt);
                } else if (tag == TAG_EXIF_IFD && typ == TYPE_LONG) {
                    readDir(t, t.u32(value), info, true);
                }
            } else {
                if (tag == TAG_DATETIME_ORIGINAL) {
                    info.dateTimeOriginal = readAscii(t, value, typ, cnt);
                } else if (tag == TAG_DATETIME_DIGITIZED) {
                    info.dateTimeDigitized = readAscii(t, value, typ, cnt);
                } else if (tag == TAG_PIXEL_X && (typ == TYPE_LONG || typ == TYPE_SHORT)) {
                    info.pixelX = typ == TYPE_LONG ? t.u32(value) : t.u16(value);
                } else if (tag == TAG_PIXEL_Y && (typ == TYPE_LONG || typ == TYPE_SHORT)) {
                    info.pixelY = typ == TYPE_LONG ? t.u32(value) : t.u16(value);
                }
            }
        }
    }

    private static String readAscii(Tiff t, long value, int typ, long cnt) {
        if (typ != TYPE_ASCII || cnt <= 0) {
            return "";
        }
        long off;
        if (cnt <= 4) {
            off = value;
        } else {
            off = t.u32(value);
            if (off + cnt > t.len) {
                return "";
            }
        }
        return stripNulSpace(decodeAscii(t.b, t.base + (int) off, (int) cnt));
    }

    // ---- Minimal little-endian TIFF writer ---------------------------------------------------------------------

    private static final class Entry {
        final int tag;
        final int typ;
        final int count;
        byte[] inline = new byte[4];
        byte[] external;

        Entry(int tag, int typ, int count) {
            this.tag = tag;
            this.typ = typ;
            this.count = count;
        }
    }

    private static Entry asciiEntry(int tag, String text) {
        byte[] raw = Arrays.copyOf(ascii(text), text.length() + 1);
        if (raw.length <= 4) {
            Entry e = new Entry(tag, TYPE_ASCII, raw.length);
            System.arraycopy(raw, 0, e.inline, 0, raw.length);
            return e;
        }
        Entry e = new Entry(tag, TYPE_ASCII, raw.length);
        e.external = raw.length % 2 == 1 ? Arrays.copyOf(raw, raw.length + 1) : raw; // word-align external values
        return e;
    }

    private static Entry shortEntry(int tag, int value) {
        Entry e = new Entry(tag, TYPE_SHORT, 1);
        e.inline = new byte[]{(byte) value, (byte) (value >> 8), 0, 0};
        return e;
    }

    private static Entry longEntry(int tag, long value) {
        Entry e = new Entry(tag, TYPE_LONG, 1);
        e.inline = le32(value);
        return e;
    }

    private static Entry undefinedEntry(int tag, byte[] raw) {
        Entry e = new Entry(tag, TYPE_UNDEFINED, raw.length);
        System.arraycopy(raw, 0, e.inline, 0, raw.length);
        return e;
    }

    private static Entry rationalEntry(int tag, long num, long den) {
        Entry e = new Entry(tag, TYPE_RATIONAL, 1);
        e.external = new byte[8];
        System.arraycopy(le32(num), 0, e.external, 0, 4);
        System.arraycopy(le32(den), 0, e.external, 4, 4);
        return e;
    }

    private static int ifdSize(int n) {
        return 2 + 12 * n + 4;
    }

    /**
     * A complete APP1 payload: "Exif\0\0" and a fresh little-endian TIFF holding only the corrected tags, in the order
     * exif.py's encode_exif writes them. Building from scratch is what drops the camera's corrupt MakerNote.
     */
    static byte[] encodeExif(int width, int height, String dateTime, int orientation, String make, String model,
                             int ycbcrPositioning) {
        List<Entry> ifd0 = new ArrayList<>();
        if (make != null && !make.isEmpty()) {
            ifd0.add(asciiEntry(TAG_MAKE, make));
        }
        if (model != null && !model.isEmpty()) {
            ifd0.add(asciiEntry(TAG_MODEL, model));
        }
        int o = orientation >= 1 && orientation <= 8 ? orientation : 1;
        int p = ycbcrPositioning == 1 || ycbcrPositioning == 2 ? ycbcrPositioning : 1;
        ifd0.add(shortEntry(TAG_ORIENTATION, o));
        ifd0.add(asciiEntry(TAG_DATETIME, dateTime));
        ifd0.add(shortEntry(TAG_YCBCR_POSITIONING, p));
        Entry exifPointer = longEntry(TAG_EXIF_IFD, 0); // patched below
        ifd0.add(exifPointer);

        List<Entry> exifIfd = new ArrayList<>();
        exifIfd.add(rationalEntry(TAG_FNUMBER, FNUMBER_NUM, FNUMBER_DEN));
        exifIfd.add(undefinedEntry(TAG_EXIF_VERSION, ascii("0230")));
        exifIfd.add(asciiEntry(TAG_DATETIME_ORIGINAL, dateTime));
        exifIfd.add(asciiEntry(TAG_DATETIME_DIGITIZED, dateTime));
        exifIfd.add(undefinedEntry(TAG_COMPONENTS_CONFIG, new byte[]{1, 2, 3, 0})); // Y, Cb, Cr, -
        exifIfd.add(undefinedEntry(TAG_FLASHPIX_VERSION, ascii("0100")));
        exifIfd.add(shortEntry(TAG_COLOR_SPACE, 1)); // sRGB
        exifIfd.add(longEntry(TAG_PIXEL_X, width));
        exifIfd.add(longEntry(TAG_PIXEL_Y, height));
        exifIfd.add(shortEntry(TAG_FOCAL_LENGTH_35MM, FOCAL_LENGTH_35MM));
        exifIfd.add(asciiEntry(TAG_LENS_MAKE, LENS_MAKE));
        exifIfd.add(asciiEntry(TAG_LENS_MODEL, LENS_MODEL));

        int headerSize = 8;
        int exifIfdOffset = headerSize + ifdSize(ifd0.size());
        int extBase = exifIfdOffset + ifdSize(exifIfd.size());
        exifPointer.inline = le32(exifIfdOffset);

        List<Entry> all = new ArrayList<>(ifd0);
        all.addAll(exifIfd);
        ByteArrayOutputStream ext = new ByteArrayOutputStream();
        for (Entry e : all) {
            if (e.external == null) {
                continue;
            }
            e.inline = le32(extBase + ext.size());
            ext.write(e.external, 0, e.external.length);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(EXIF_HEADER, 0, EXIF_HEADER.length);
        out.write('I');
        out.write('I');
        writeLe16(out, 0x2A);
        writeLe32(out, headerSize);
        List<List<Entry>> dirs = new ArrayList<>();
        dirs.add(ifd0);
        dirs.add(exifIfd);
        for (List<Entry> entries : dirs) {
            writeLe16(out, entries.size());
            for (Entry e : entries) {
                writeLe16(out, e.tag);
                writeLe16(out, e.typ);
                writeLe32(out, e.count);
                out.write(e.inline, 0, 4);
            }
            writeLe32(out, 0); // no next IFD
        }
        byte[] extBytes = ext.toByteArray();
        out.write(extBytes, 0, extBytes.length);
        return out.toByteArray();
    }

    // ---- Dates -------------------------------------------------------------------------------------------------

    /** A standard "YYYY:MM:DD HH:MM:SS", repairing the all-colons form; null for empty, zeroed or unknown values. */
    static String normalizeExifDate(String raw) {
        String s = stripNulSpace(raw == null ? "" : raw);
        if (s.isEmpty() || s.startsWith("0000")) {
            return null;
        }
        if (VALID_DATE.matcher(s).matches()) {
            return s;
        }
        Matcher m = MALFORMED_DATE.matcher(s);
        if (m.matches()) {
            return m.group(1) + ":" + m.group(2) + ":" + m.group(3) + " " + m.group(4) + ":" + m.group(5) + ":"
                    + m.group(6);
        }
        return null;
    }

    /**
     * Whether this JPEG came out of the Charmera: the "GPEncoder" comment in the first 4 KiB, a salvaged Make of
     * "Generalplus", or a date in the camera's all-colons form. A phone picture dropped under DCIM is not rebuilt.
     */
    static boolean isCharmeraJpeg(byte[] data) {
        if (data == null) {
            return false;
        }
        if (indexOf(data, Math.min(data.length, SIGNATURE_SAMPLE_SIZE), GPENCODER_SIGNATURE) >= 0) {
            return true;
        }
        Layout layout = scan(data);
        if (layout == null) {
            return false;
        }
        return chipFingerprint(sourceExif(data, layout));
    }

    private static boolean chipFingerprint(Info info) {
        if (info.make.trim().equalsIgnoreCase(CHIP_MAKE)) {
            return true;
        }
        String[] dates = {info.dateTimeOriginal, info.dateTimeDigitized, info.dateTime};
        for (String raw : dates) {
            if (MALFORMED_DATE.matcher(stripNulSpace(raw)).matches()) {
                return true;
            }
        }
        return false;
    }

    // ---- The JPEG fix ------------------------------------------------------------------------------------------

    /**
     * The repaired copy of a Charmera JPEG, Make/Model written as Kodak/Charmera. The capture time is the photo's own
     * EXIF date (DateTimeOriginal, then DateTimeDigitized, then DateTime), repaired; without one, mtimeMs in tz.
     * A JPEG without the camera's fingerprints, or one that cannot be read, comes back unchanged with the reason.
     *
     * @param tz the zone the card's file times are read in; null for the default zone
     */
    static Result fixJpeg(byte[] data, long mtimeMs, TimeZone tz) {
        try {
            return fixJpegOrThrow(data, mtimeMs, tz);
        } catch (RuntimeException e) {
            // The reader is bounds-checked throughout; this is a last line so that one odd file never stops a backup.
            return Result.unchanged(data, "copied unchanged: unexpected error reading the photo ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private static Result fixJpegOrThrow(byte[] data, long mtimeMs, TimeZone tz) {
        Layout layout = scan(data);
        if (layout == null) {
            return Result.unchanged(data, "copied unchanged: not a JPEG (no start-of-image marker)");
        }
        if (!isCharmeraJpeg(data)) {
            return Result.unchanged(data, "copied unchanged: not a Charmera photo");
        }
        if (layout.width == 0 || layout.height == 0) {
            return Result.unchanged(data, "copied unchanged: could not read the image size from the frame header");
        }
        Info info = sourceExif(data, layout);

        String dateText = null;
        String source = "mtime";
        boolean repaired = false;
        String[] dates = {info.dateTimeOriginal, info.dateTimeDigitized, info.dateTime};
        for (String raw : dates) {
            String fixed = normalizeExifDate(raw);
            if (fixed != null) {
                dateText = fixed;
                source = "exif";
                repaired = !fixed.equals(stripNulSpace(raw));
                break;
            }
        }
        if (dateText == null) {
            dateText = formatMtime(mtimeMs, tz);
        }
        int orientation = info.orientation >= 1 && info.orientation <= 8 ? info.orientation : 1;

        byte[] payload = encodeExif(layout.width, layout.height, dateText, orientation, CAMERA_MAKE, CAMERA_MODEL,
                info.ycbcrPositioning);
        byte[] out = replaceExif(data, layout, payload);
        if (out == null) {
            return Result.unchanged(data, "copied unchanged: the rebuilt EXIF block would be too large");
        }

        StringBuilder note = new StringBuilder();
        note.append(layout.width).append('x').append(layout.height);
        note.append(", orientation ").append(orientation);
        if (info.orientation != 0 && (info.orientation < 1 || info.orientation > 8)) {
            note.append(" (source had ").append(info.orientation).append(')');
        }
        note.append(", date ").append(dateText).append(" from ").append(source.equals("exif") ? "EXIF" : "file time");
        if (repaired) {
            note.append(", extra colon repaired");
        }
        if (!info.present) {
            note.append(", no EXIF in source");
        }
        if (!info.make.isEmpty() || !info.model.isEmpty()) {
            note.append(", make/model ").append(CAMERA_MAKE).append('/').append(CAMERA_MODEL).append(" (was ")
                    .append(info.make).append('/').append(info.model).append(')');
        } else {
            note.append(", make/model ").append(CAMERA_MAKE).append('/').append(CAMERA_MODEL);
        }
        if (Arrays.equals(out, data)) {
            return new Result(data, false, "already repaired: " + note, dateText, source);
        }
        return new Result(out, true, note.toString(), dateText, source);
    }

    /**
     * The capture time as a file name stem, "yyyyMMdd_HHmmss": a photo's own EXIF date, else a video's date chunk
     * when it is not the camera's fake 2010-06-29, else the file time. Null when there is none. Never throws.
     */
    static String captureName(byte[] head, boolean video, long mtimeMs, TimeZone tz) {
        try {
            if (video) {
                List<AviDateField> found = readAviDates(head);
                if (found != null) {
                    for (AviDateField f : found) {
                        int[] d = parseAviDate(f.value);
                        if (d != null && !isBogus(d)) {
                            return String.format(Locale.US, "%04d%02d%02d_%02d%02d%02d", d[0], d[1], d[2], d[3], d[4],
                                    d[5]);
                        }
                    }
                }
            } else {
                Layout layout = scan(head);
                if (layout != null) {
                    Info info = sourceExif(head, layout);
                    for (String raw : new String[]{info.dateTimeOriginal, info.dateTimeDigitized, info.dateTime}) {
                        String fixed = normalizeExifDate(raw);
                        if (fixed != null) {
                            return fixed.replace(":", "").replace(' ', '_');
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // an odd file falls back to its file time
        }
        if (mtimeMs <= 0) {
            return null;
        }
        return formatMtime(mtimeMs, tz).replace(":", "").replace(' ', '_');
    }

    static String formatMtime(long mtimeMs, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat(EXIF_DATE_FORMAT, Locale.US);
        f.setTimeZone(tz != null ? tz : TimeZone.getDefault());
        return f.format(new Date(mtimeMs));
    }

    // ---- AVI: the hard-coded 2010-06-29 ------------------------------------------------------------------------

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
            "Nov", "Dec"};
    private static final String[] DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final Pattern ASCTIME =
            Pattern.compile("^([A-Za-z]{3}) (\\d{1,2}) (\\d{2}):(\\d{2}):(\\d{2}) (\\d{4})$");
    private static final Pattern LEADING_WEEKDAY = Pattern.compile("^[A-Za-z]{3} (?=[A-Za-z]{3} )");
    // The strptime forms exif.py tries, in its order: %Y:%m:%d %H:%M:%S, %Y-%m-%d %H:%M:%S, %Y-%m-%dT%H:%M:%S,
    // %Y-%m-%d, %Y/%m/%d. strptime takes one or two digits for every field but the year.
    private static final Pattern[] DATE_FORMS = {
            Pattern.compile("^(\\d{4}):(\\d{1,2}):(\\d{1,2}) (\\d{1,2}):(\\d{1,2}):(\\d{1,2})$"),
            Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2}) (\\d{1,2}):(\\d{1,2}):(\\d{1,2})$"),
            Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})T(\\d{1,2}):(\\d{1,2}):(\\d{1,2})$"),
            Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$"),
            Pattern.compile("^(\\d{4})/(\\d{1,2})/(\\d{1,2})$"),
    };

    /** A date stored as text inside the AVI: where it is and how much room it has. */
    static final class AviDateField {
        final String chunkId;
        final int offset;
        final int size;
        final String value;

        AviDateField(String chunkId, int offset, int size, String value) {
            this.chunkId = chunkId;
            this.offset = offset;
            this.size = size;
            this.value = value;
        }
    }

    /**
     * {year, month, day, hour, minute, second} from the forms AVI writers use for IDIT/ICRD (asctime, EXIF-style,
     * ISO), or null. The weekday is ignored; month names are English whatever the locale; invalid dates give null.
     */
    static int[] parseAviDate(String raw) {
        if (raw == null) {
            return null;
        }
        String text = strip(raw, "\u0000 \r\n").replaceAll("\\s+", " ");
        text = LEADING_WEEKDAY.matcher(text).replaceFirst("");
        Matcher m = ASCTIME.matcher(text);
        if (m.matches()) {
            int month = -1;
            for (int i = 0; i < MONTHS.length; i++) {
                if (MONTHS[i].equalsIgnoreCase(m.group(1))) {
                    month = i + 1;
                }
            }
            if (month < 0) {
                return null;
            }
            return valid(Integer.parseInt(m.group(6)), month, Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
        }
        for (Pattern form : DATE_FORMS) {
            Matcher d = form.matcher(text);
            if (d.matches()) {
                boolean time = d.groupCount() == 6;
                return valid(Integer.parseInt(d.group(1)), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(3)),
                        time ? Integer.parseInt(d.group(4)) : 0, time ? Integer.parseInt(d.group(5)) : 0,
                        time ? Integer.parseInt(d.group(6)) : 0);
            }
        }
        return null;
    }

    private static int[] valid(int y, int mo, int d, int h, int mi, int s) {
        if (y < 1 || mo < 1 || mo > 12 || d < 1 || h > 23 || mi > 59 || s > 61) {
            return null;
        }
        GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US);
        c.clear();
        c.set(y, mo - 1, 1);
        if (d > c.getActualMaximum(Calendar.DAY_OF_MONTH)) {
            return null;
        }
        return new int[]{y, mo, d, h, mi, s};
    }

    private static boolean isBogus(int[] date) {
        return date != null && date[0] == 2010 && date[1] == 6 && date[2] == 29;
    }

    /** when ({y, mo, d, h, mi, s, weekday Mon=0}) in the same style as original; a trailing newline is kept. */
    static String formatAviDateLike(String original, int[] when) {
        String text = original;
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\u0000') {
            end--;
        }
        text = text.substring(0, end);
        String newline = text.endsWith("\n") ? "\n" : "";
        String core = strip(text, " \t\n\r\u000B\f"); // Python's strip(): whitespace only, not NUL as trim() would
        String formatted;
        if (core.matches("^[A-Za-z]{3} [\\s\\S]*")) {
            // asctime pads a single-digit day with a space ("Jun  9").
            formatted = String.format(Locale.US, "%s %s %2d %02d:%02d:%02d %04d", DAYS[when[6]], MONTHS[when[1] - 1],
                    when[2], when[3], when[4], when[5], when[0]);
        } else if (core.matches("^\\d{4}:\\d{2}:\\d{2}[\\s\\S]*")) {
            formatted = String.format(Locale.US, "%04d:%02d:%02d %02d:%02d:%02d", when[0], when[1], when[2], when[3],
                    when[4], when[5]);
        } else if (core.length() <= 10) {
            formatted = String.format(Locale.US, "%04d-%02d-%02d", when[0], when[1], when[2]);
        } else {
            formatted = String.format(Locale.US, "%04d-%02d-%02d" + (core.indexOf('T') >= 0 ? "T" : " ")
                    + "%02d:%02d:%02d", when[0], when[1], when[2], when[3], when[4], when[5]);
        }
        return formatted + newline;
    }

    /** {y, mo, d, h, mi, s, weekday Mon=0} of mtimeMs in tz (the default zone when null). */
    static int[] fields(long mtimeMs, TimeZone tz) {
        GregorianCalendar c = new GregorianCalendar(tz != null ? tz : TimeZone.getDefault(), Locale.US);
        c.setTimeInMillis(mtimeMs);
        int weekday = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Calendar: Sunday 1 .. Saturday 7; here Monday 0
        return new int[]{c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), weekday};
    }

    static boolean isAvi(byte[] data) {
        return data != null && data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'A' && data[9] == 'V' && data[10] == 'I' && data[11] == ' ';
    }

    /**
     * The camera's own video: an AVI whose IDIT or ICRD date field (in data, the file or its start) holds the date the
     * camera writes into every video, 2010-06-29. Any other AVI, one with no date field or only real dates, is not
     * counted as the camera's. Never throws.
     */
    static boolean isCharmeraAvi(byte[] data) {
        try {
            List<AviDateField> found = readAviDates(data);
            if (found != null) {
                for (AviDateField f : found) {
                    if (isBogus(parseAviDate(f.value))) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            // an odd file is not counted as the camera's
        }
        return false;
    }

    /**
     * The zip entry name for the card file at path ("DCIM/100MEDIA/PICT0001.JPG"): prefix (as "originals/", or "")
     * then, with a stem (a capture-time name, captureName), the file's folder, the stem and its extension in lower
     * case; without one, the card path as it is. Made unique against lower (the lower-case names taken so far, which
     * it adds to): "_2", "_3"... after a stem, "-2", "-3"... after a card name, before the extension.
     */
    static String entryName(String path, String stem, String prefix, Set<String> lower) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        String dir = path.substring(0, slash + 1);
        String ext = dot > slash ? path.substring(dot) : "";
        String base = prefix + (stem != null ? dir + stem : path.substring(0, path.length() - ext.length()));
        String tail = stem != null ? ext.toLowerCase(Locale.ROOT) : ext;
        String entry = base + tail;
        for (int k = 2; !lower.add(entry.toLowerCase(Locale.ROOT)); k++) {
            entry = base + (stem != null ? "_" : "-") + k + tail;
        }
        return entry;
    }

    /**
     * The IDIT/ICRD date fields of an AVI, from its headers (the frames in movi are skipped), or null when data is not
     * an AVI. data may be the whole file or only its start: a field past the end of data is not seen.
     */
    static List<AviDateField> readAviDates(byte[] data) {
        if (!isAvi(data)) {
            return null;
        }
        List<AviDateField> out = new ArrayList<>();
        long riffEnd = Math.min(data.length, 8 + le32(data, 4));
        walkRiff(data, 12, riffEnd, out, 0);
        return out;
    }

    private static void walkRiff(byte[] data, long start, long end, List<AviDateField> out, int depth) {
        long pos = start;
        while (pos + 8 <= end) {
            int p = (int) pos;
            String chunkId = new String(decodeAscii(data, p, 4));
            long size = le32(data, p + 4);
            long dataStart = pos + 8;
            long dataEnd = Math.min(end, dataStart + size);
            if ((chunkId.equals("LIST") || chunkId.equals("RIFF")) && size >= 4 && depth < 4) {
                boolean movi = dataStart + 4 <= data.length
                        && new String(decodeAscii(data, (int) dataStart, 4)).equals("movi");
                if (!movi) { // frames: nothing to read there
                    walkRiff(data, dataStart + 4, dataEnd, out, depth + 1);
                }
            } else if ((chunkId.equals("IDIT") || chunkId.equals("ICRD")) && size > 0 && size <= MAX_AVI_DATE_FIELD
                    && dataStart + size <= data.length) {
                out.add(new AviDateField(chunkId, (int) dataStart, (int) size,
                        decodeAscii(data, (int) dataStart, (int) size)));
            }
            pos = dataStart + size + (size & 1); // chunks are word-aligned
        }
    }

    /**
     * A copy of an AVI (or of its start, with every header chunk in it) whose fields holding the camera's fake date,
     * 2010-06-29, carry mtimeMs in tz instead: same text form, NUL-padded to the same length, so the copy is exactly as
     * long as data and no other byte moves. Any other date is left alone. A caller streaming the rest of the file
     * writes result.data in place of the first data.length bytes.
     *
     * @param tz the zone the card's file times are read in; null for the default zone
     */
    static Result fixAvi(byte[] data, long mtimeMs, TimeZone tz) {
        try {
            return fixAviOrThrow(data, mtimeMs, tz);
        } catch (RuntimeException e) {
            return Result.unchanged(data, "copied unchanged: unexpected error reading the video ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private static Result fixAviOrThrow(byte[] data, long mtimeMs, TimeZone tz) {
        List<AviDateField> found = readAviDates(data);
        if (found == null) {
            return Result.unchanged(data, "copied unchanged: not an AVI file");
        }
        if (found.isEmpty()) {
            return Result.unchanged(data, "no IDIT/ICRD date chunk, copied unchanged");
        }
        int[] when = fields(mtimeMs, tz);
        String whenText = formatMtime(mtimeMs, tz);
        byte[] out = null;
        List<String> patched = new ArrayList<>();
        List<String> tooSmall = new ArrayList<>();
        StringBuilder genuine = new StringBuilder();
        for (AviDateField f : found) {
            if (!isBogus(parseAviDate(f.value))) {
                if (genuine.length() > 0) {
                    genuine.append(", ");
                }
                genuine.append(f.chunkId).append(" '").append(strip(f.value, "\u0000").trim()).append('\'');
                continue;
            }
            byte[] replacement = ascii(formatAviDateLike(f.value, when));
            if (replacement.length > f.size) {
                tooSmall.add(f.chunkId);
                continue;
            }
            if (out == null) {
                out = data.clone();
            }
            Arrays.fill(out, f.offset, f.offset + f.size, (byte) 0);
            System.arraycopy(replacement, 0, out, f.offset, replacement.length);
            patched.add(f.chunkId);
        }
        if (patched.isEmpty() && tooSmall.isEmpty()) {
            return Result.unchanged(data, "left as is, not the camera's fake date: " + genuine);
        }
        StringBuilder note = new StringBuilder();
        if (!patched.isEmpty()) {
            note.append("date ").append(whenText).append(" from the file time replaces 2010-06-29 in ")
                    .append(joined(patched));
        }
        if (!tooSmall.isEmpty()) {
            note.append(note.length() > 0 ? "; " : "copied unchanged: ")
                    .append("left unchanged because the new text would not fit: ").append(joined(tooSmall));
        }
        if (out == null || Arrays.equals(out, data)) {
            return new Result(data, false, note.toString(), null, null);
        }
        return new Result(out, true, note.toString(), whenText, "mtime");
    }

    // ---- Bytes and text ----------------------------------------------------------------------------------------

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int be16(byte[] b, int i) {
        return u8(b, i) << 8 | u8(b, i + 1);
    }

    private static long le32(byte[] b, int i) {
        return (u8(b, i) | u8(b, i + 1) << 8 | u8(b, i + 2) << 16 | (long) u8(b, i + 3) << 24) & 0xFFFFFFFFL;
    }

    private static byte[] le32(long v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static void writeLe16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream out, long v) {
        byte[] b = le32(v);
        out.write(b, 0, 4);
    }

    private static boolean startsWith(byte[] b, int off, int len, byte[] prefix) {
        if (len < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (b[off + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] b, int limit, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (b[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** ASCII bytes of text; anything outside ASCII becomes '?', as Python's encode("ascii", "replace"). */
    static byte[] ascii(String text) {
        byte[] out = new byte[text.length()];
        for (int i = 0; i < out.length; i++) {
            char c = text.charAt(i);
            out[i] = c < 0x80 ? (byte) c : (byte) '?';
        }
        return out;
    }

    /** Bytes as ASCII; anything outside ASCII becomes U+FFFD, as Python's decode("ascii", "replace"). */
    private static String decodeAscii(byte[] b, int off, int len) {
        char[] out = new char[len];
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xFF;
            out[i] = v < 0x80 ? (char) v : '\uFFFD';
        }
        return new String(out);
    }

    private static String stripNulSpace(String s) {
        return strip(s, "\u0000 ");
    }

    /** s without any of chars at either end, as Python's str.strip(chars). */
    private static String strip(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    private static String joined(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) {
                b.append(", ");
            }
            b.append(p);
        }
        return b.toString();
    }
}
