package io.github.daemonddev.charmera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/**
 * Plain-JVM tests for the photo and video date repair (DateFix), mirroring tests/test_exif.py: the camera's own
 * segment order, the all-colons dates, a corrupt MakerNote in a big-endian block, a photo from the original firmware
 * with no EXIF at all, the videos' 2010-06-29 in ICRD (ISO) and IDIT (asctime), and JPEGs from other cameras, which
 * must come back untouched. Pillow is not here, so the JPEGs are assembled from hand-made segments; DateFix never
 * decodes the image data, and the tests check that every byte of it is carried over. Set CHARMERA_STOCK_IMAGE to the
 * public stock dump to also run the two photos stored inside it. In the app's package, because DateFix is
 * package-private.
 */
public final class DateFixTest {

    private static int failures = 0;
    private static int passes = 0;

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long FALLBACK = utc(2026, 3, 3, 12, 16, 29);
    private static final long RECORDED = utc(2026, 8, 7, 18, 45, 12);
    private static final byte[] CAMERA_DATE = bytes("2026:03:03:12:16:29\u0000"); // all colons, 20 bytes with the NUL
    private static final int[] STOCK_PHOTO_OFFSETS = {0x180000, 0x1A5800};
    private static final String BOGUS_IDIT = "Tue Jun 29 12:00:00 2010\n\u0000";
    private static final String BOGUS_ICRD = "2010-06-29\u0000";

    public static void main(String[] args) throws IOException {
        dates();
        cameraSegmentOrder();
        malformedDateAndChipMake();
        corruptMakerNoteBigEndian();
        originalFirmwarePhotoWithoutExif();
        zeroDateFallsBackToFileTime();
        orientation();
        ycbcrPositioning();
        secondExifDropped();
        idempotent();
        otherCamerasUntouched();
        badJpegInput();
        avi();
        badAviInput();
        captureNames();
        entryNames();
        stockImage();

        System.out.printf(Locale.ROOT, "DateFixTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ---- dates -------------------------------------------------------------------------------------------------

    private static void dates() {
        String[][] normal = {
                {"2026:03:03:12:16:29", "2026:03:03 12:16:29"}, // the camera's extra colon
                {"2026:03:03 12:16:29", "2026:03:03 12:16:29"}, // already valid
                {"2026:03:03 12:16:29\u0000   ", "2026:03:03 12:16:29"}, // padding
                {"0000:00:00 00:00:00", null},
                {"", null},
                {"garbage", null},
                {"2026:03:03:12:16", null},
        };
        for (String[] c : normal) {
            String got = DateFix.normalizeExifDate(c[0]);
            expect("normalizeExifDate(" + c[0].replace("\u0000", "\\0") + ")",
                    c[1] == null ? got == null : c[1].equals(got), String.valueOf(got));
        }

        Object[][] forms = {
                {"Tue Jun 29 12:00:00 2010\n", new int[]{2010, 6, 29}},
                {"Wed Jun  9 12:00:00 2010", new int[]{2010, 6, 9}},
                {"Mon Jun 29 12:00:00 2010", new int[]{2010, 6, 29}}, // wrong weekday: still read
                {"2010:06:29 12:00:00", new int[]{2010, 6, 29}},
                {"2010-06-29\u0000", new int[]{2010, 6, 29}},
                {"2010-06-29T12:00:00", new int[]{2010, 6, 29}},
                {"2010/06/29", new int[]{2010, 6, 29}},
                {"Jun 29 12:00:00 2010", new int[]{2010, 6, 29}},
        };
        for (Object[] c : forms) {
            int[] got = DateFix.parseAviDate((String) c[0]);
            int[] want = (int[]) c[1];
            expect("parseAviDate reads " + ((String) c[0]).trim(),
                    got != null && got[0] == want[0] && got[1] == want[1] && got[2] == want[2], Arrays.toString(got));
        }
        String[] nonsense = {"", "Tue Xyz 29 12:00:00 2010", "not a date", "2010-02-30", "2010-13-01",
                "2010:06:29 25:00:00", "\u0000\u0000\u0000"};
        for (String s : nonsense) {
            expect("parseAviDate rejects " + s.replace("\u0000", "\\0"), DateFix.parseAviDate(s) == null);
        }

        int[] when = DateFix.fields(RECORDED, UTC);
        expect("fields: 2026-08-07 18:45:12 is a Friday (Mon=0 gives 4)",
                Arrays.equals(when, new int[]{2026, 8, 7, 18, 45, 12, 4}), Arrays.toString(when));
        String[] originals = {"Tue Jun 29 12:00:00 2010\n", "Tue Jun 29 12:00:00 2010", "2010:06:29 12:00:00",
                "2010-06-29", "2010-06-29T12:00:00", "2010-06-29 12:00:00"};
        for (String original : originals) {
            String formatted = DateFix.formatAviDateLike(original, when);
            int[] back = DateFix.parseAviDate(formatted);
            boolean dateOnly = original.trim().length() <= 10;
            int[] want = dateOnly ? new int[]{2026, 8, 7, 0, 0, 0} : new int[]{2026, 8, 7, 18, 45, 12};
            expect("formatAviDateLike keeps the style and length of " + original.trim(),
                    formatted.length() == original.length() && Arrays.equals(back, want), formatted);
        }
        expect("asctime pads a single-digit day with a space",
                DateFix.formatAviDateLike("Tue Jun 29 12:00:00 2010\n", when).equals("Fri Aug  7 18:45:12 2026\n"));
        expect("the file time is formatted in the given zone",
                DateFix.formatMtime(FALLBACK, UTC).equals("2026:03:03 12:16:29")
                        && DateFix.formatMtime(FALLBACK, TimeZone.getTimeZone("GMT+02:00"))
                        .equals("2026:03:03 14:16:29"));
    }

    // ---- JPEG --------------------------------------------------------------------------------------------------

    private static void cameraSegmentOrder() {
        byte[] camera = charmeraJpeg(64, 48, charmeraTiff(CAMERA_DATE, 1));
        List<int[]> segs = segments(camera);
        expect("the fixture is in the camera's order: SOF0, COM, DQT, DQT, DRI, 4 DHT, APP1, SOS; no APP0",
                markers(segs).equals(Arrays.asList(0xC0, 0xFE, 0xDB, 0xDB, 0xDD, 0xC4, 0xC4, 0xC4, 0xC4, 0xE1, 0xDA)),
                markers(segs).toString());
        DateFix.Info before = exifOf(camera);
        expect("the camera block is read: Generalplus/CBB3, all-colons date, YCbCr 2",
                before.present && before.make.equals("Generalplus") && before.model.equals("CBB3")
                        && before.dateTimeOriginal.equals("2026:03:03:12:16:29") && before.ycbcrPositioning == 2,
                before.make + "/" + before.model + " " + before.dateTimeOriginal);
        expect("the camera's photo is recognised", DateFix.isCharmeraJpeg(camera));

        DateFix.Result r = DateFix.fixJpeg(camera, FALLBACK, UTC);
        checkCameraFix("camera order", camera, r);
        expect("camera order: the date is the repaired EXIF date, not the file time",
                "2026:03:03 12:16:29".equals(r.dateTime) && "exif".equals(r.dateSource)
                        && r.note.contains("extra colon repaired"), r.note);
        expect("camera order: the segment order is unchanged", markers(segments(r.data)).equals(markers(segs)));
        DateFix.Info after = exifOf(r.data);
        expect("camera order: YCbCr positioning 2 carried over, PixelX/Y from the frame (64x48, not 640x480)",
                after.ycbcrPositioning == 2 && after.pixelX == 64 && after.pixelY == 48,
                after.ycbcrPositioning + " " + after.pixelX + "x" + after.pixelY);
        expect("camera order: the input array is not modified",
                Arrays.equals(camera, charmeraJpeg(64, 48, charmeraTiff(CAMERA_DATE, 1))));
    }

    /** The checks test_exif.py's check_camera_jpeg makes, minus the pixel decode (no Pillow here). */
    private static void checkCameraFix(String what, byte[] jpeg, DateFix.Result r) {
        DateFix.Layout before = DateFix.scan(jpeg);
        expect(what + ": changed", r.changed && r.data != jpeg, r.note);
        DateFix.Layout after = DateFix.scan(r.data);
        expect(what + ": the new APP1 sits where the old one was, one APP1 in the output",
                after.exifFound && after.exifStart == before.exifStart && after.exifRanges.size() == 1);
        expect(what + ": every byte before and after the EXIF block is unchanged",
                Arrays.equals(Arrays.copyOfRange(r.data, 0, after.exifStart),
                        Arrays.copyOfRange(jpeg, 0, before.exifStart))
                        && Arrays.equals(Arrays.copyOfRange(r.data, after.exifEnd, r.data.length),
                        Arrays.copyOfRange(jpeg, before.exifEnd, jpeg.length)));
        expect(what + ": dimensions from the frame", after.width == before.width && after.height == before.height);
        DateFix.Info info = exifOf(r.data);
        expect(what + ": Kodak/Charmera, all three dates in the valid form",
                info.make.equals("Kodak") && info.model.equals("Charmera") && isValidDate(info.dateTime)
                        && info.dateTime.equals(info.dateTimeOriginal) && info.dateTime.equals(info.dateTimeDigitized),
                info.make + "/" + info.model + " " + info.dateTime);
        expect(what + ": the note names the old make/model", r.note.contains("(was Generalplus/CBB3)"), r.note);
        expect(what + ": the output is rebuilt little-endian",
                r.data[after.exifStart + 10] == 'I' && r.data[after.exifStart + 11] == 'I');
        expect(what + ": a second pass still recognises the photo (the COM survives)", DateFix.isCharmeraJpeg(r.data));
    }

    private static void malformedDateAndChipMake() {
        byte[] tiff = buildTiff(true,
                list(e(DateFix.TAG_MAKE, DateFix.TYPE_ASCII, 12, bytes("Generalplus\u0000")),
                        e(DateFix.TAG_MODEL, DateFix.TYPE_ASCII, 5, bytes("CBB3\u0000")),
                        e(DateFix.TAG_DATETIME, DateFix.TYPE_ASCII, 20, CAMERA_DATE)),
                list(e(DateFix.TAG_DATETIME_ORIGINAL, DateFix.TYPE_ASCII, 20, CAMERA_DATE)));
        byte[] bad = withExif(plainJpeg(64, 48), cat(bytes("Exif\u0000\u0000"), tiff));
        expect("the all-colons date is really in the file", exifOf(bad).dateTimeOriginal.equals("2026:03:03:12:16:29"));
        DateFix.Result r = DateFix.fixJpeg(bad, utc(2000, 1, 1, 0, 0, 0), UTC);
        DateFix.Info out = exifOf(r.data);
        expect("malformed date repaired from EXIF, chip make replaced",
                r.changed && "exif".equals(r.dateSource) && "2026:03:03 12:16:29".equals(r.dateTime)
                        && out.dateTimeOriginal.equals("2026:03:03 12:16:29") && out.make.equals("Kodak")
                        && r.note.contains("extra colon repaired") && r.note.contains("(was Generalplus/CBB3)"),
                r.note);
        expect("malformed date: the JFIF APP0 stays first", segments(r.data).get(0)[0] == 0xE0);
    }

    private static void corruptMakerNoteBigEndian() {
        byte[] date = CAMERA_DATE;
        byte[] tiff = buildTiff(false,
                list(e(DateFix.TAG_MAKE, DateFix.TYPE_ASCII, 12, bytes("Generalplus\u0000")),
                        e(DateFix.TAG_MODEL, DateFix.TYPE_ASCII, 4, bytes("CBB3")),
                        e(DateFix.TAG_ORIENTATION, DateFix.TYPE_SHORT, 1, new byte[]{0, 8}),
                        e(DateFix.TAG_DATETIME, DateFix.TYPE_ASCII, 20, date)),
                list(e(DateFix.TAG_DATETIME_ORIGINAL, DateFix.TYPE_ASCII, 20, date),
                        e(0x927C, DateFix.TYPE_UNDEFINED, 100, be32(200)))); // MakerNote, offset past the end
        byte[] jpeg = withExif(plainJpeg(64, 48), cat(bytes("Exif\u0000\u0000"), tiff));
        DateFix.Result r = DateFix.fixJpeg(jpeg, utc(2000, 1, 1, 0, 0, 0), UTC);
        DateFix.Layout after = DateFix.scan(r.data);
        expect("big-endian block with a MakerNote past the end: read, orientation 8 kept, date repaired",
                r.changed && exifOf(r.data).orientation == 8 && "2026:03:03 12:16:29".equals(r.dateTime)
                        && "exif".equals(r.dateSource) && r.note.contains("(was Generalplus/CBB3)"), r.note);
        expect("big-endian block: rebuilt little-endian, no MakerNote bytes carried",
                r.data[after.exifStart + 10] == 'I' && r.data[after.exifStart + 11] == 'I'
                        && indexOf(r.data, new byte[]{(byte) 0x92, 0x7C}) < 0
                        && indexOf(r.data, new byte[]{0x7C, (byte) 0x92}) < 0);

        // The camera's own block: little-endian, MakerNote exactly one past the end, InteropIFD past the end.
        byte[] camera = withExif(plainJpeg(64, 48), cat(bytes("Exif\u0000\u0000"), charmeraTiff(CAMERA_DATE, 1)));
        DateFix.Result c = DateFix.fixJpeg(camera, FALLBACK, UTC);
        expect("the camera's corrupt MakerNote and InteropIFD are dropped", c.changed
                && indexOf(c.data, new byte[]{0x7C, (byte) 0x92}) < 0
                && indexOf(c.data, new byte[]{0x05, (byte) 0xA0}) < 0);
    }

    private static void originalFirmwarePhotoWithoutExif() {
        // docs/FIRST-FLASH-INCIDENT.md: SOF0 first, 1440x1080, COM of length 11 holding exactly "GPEncoder", two DQT,
        // DRI, four DHT, the scan. No APP0, no APP1.
        byte[] bare = cat(new byte[]{(byte) 0xFF, (byte) 0xD8}, sof0(1440, 1080), seg(0xFE, bytes("GPEncoder")),
                seg(0xDB, dqt(0)), seg(0xDB, dqt(1)), seg(0xDD, new byte[]{0, 0}), seg(0xC4, dht(0x00)),
                seg(0xC4, dht(0x10)), seg(0xC4, dht(0x01)), seg(0xC4, dht(0x11)), sosAndScan());
        expect("the original firmware's bare header is recognised by its comment", DateFix.isCharmeraJpeg(bare));
        DateFix.Result r = DateFix.fixJpeg(bare, FALLBACK, UTC);
        DateFix.Layout after = DateFix.scan(r.data);
        DateFix.Info info = exifOf(r.data);
        expect("no EXIF: a fresh block dated from the file time, 1440x1080, Kodak/Charmera, orientation 1",
                r.changed && "mtime".equals(r.dateSource) && "2026:03:03 12:16:29".equals(r.dateTime)
                        && info.dateTimeOriginal.equals("2026:03:03 12:16:29") && info.make.equals("Kodak")
                        && info.orientation == 1 && info.pixelX == 1440 && info.pixelY == 1080
                        && r.note.contains("no EXIF in source") && r.note.contains("from file time"), r.note);
        expect("no EXIF and no APP0: the block goes right after SOI, every other byte follows unchanged",
                after.exifStart == 2 && Arrays.equals(Arrays.copyOfRange(r.data, after.exifEnd, r.data.length),
                        Arrays.copyOfRange(bare, 2, bare.length)));
        DateFix.Result zoned = DateFix.fixJpeg(bare, FALLBACK, TimeZone.getTimeZone("GMT-05:00"));
        expect("the file time is read in the zone given", "2026:03:03 07:16:29".equals(zoned.dateTime), zoned.dateTime);

        // With a JFIF APP0 (a camera photo rotated by some tool, say), the block goes after it.
        byte[] jfif = cat(new byte[]{(byte) 0xFF, (byte) 0xD8}, app0(), seg(0xFE, bytes("GPEncoder")),
                seg(0xDB, dqt(0)), sof0(64, 48), seg(0xC4, dht(0x00)), sosAndScan());
        DateFix.Result j = DateFix.fixJpeg(jfif, FALLBACK, UTC);
        DateFix.Layout jl = DateFix.scan(j.data);
        expect("no EXIF after a JFIF APP0: inserted right after it",
                j.changed && jl.exifStart == 2 + app0().length && segments(j.data).get(1)[0] == 0xE1);
    }

    private static void zeroDateFallsBackToFileTime() {
        byte[] tiff = buildTiff(true, list(e(DateFix.TAG_MAKE, DateFix.TYPE_ASCII, 12, bytes("Generalplus\u0000")),
                        e(DateFix.TAG_DATETIME, DateFix.TYPE_ASCII, 20, bytes("0000:00:00 00:00:00\u0000"))),
                list(e(DateFix.TAG_DATETIME_ORIGINAL, DateFix.TYPE_ASCII, 20, bytes("0000:00:00 00:00:00\u0000"))));
        DateFix.Result r = DateFix.fixJpeg(withExif(plainJpeg(64, 48), cat(bytes("Exif\u0000\u0000"), tiff)), FALLBACK,
                UTC);
        expect("a zeroed EXIF date falls back to the file time", r.changed && "mtime".equals(r.dateSource)
                && "2026:03:03 12:16:29".equals(r.dateTime) && !r.note.contains("no EXIF in source"), r.note);
    }

    private static void orientation() {
        byte[] nine = withExif(plainJpeg(64, 48), cat(bytes("Exif\u0000\u0000"), charmeraTiff(CAMERA_DATE, 9)));
        expect("orientation 9 is really in the source", exifOf(nine).orientation == 9);
        DateFix.Result r = DateFix.fixJpeg(nine, FALLBACK, UTC);
        expect("an out-of-range orientation is written as 1 and reported",
                exifOf(r.data).orientation == 1 && r.note.contains("orientation 1 (source had 9)"), r.note);
        byte[] eight = withExif(plainJpeg(64, 48), cat(bytes("Exif\u0000\u0000"), charmeraTiff(CAMERA_DATE, 8)));
        DateFix.Result f = DateFix.fixJpeg(eight, FALLBACK, UTC);
        expect("a valid orientation is kept, not reported", exifOf(f.data).orientation == 8
                && f.note.contains("orientation 8") && !f.note.contains("source had"), f.note);
    }

    private static void ycbcrPositioning() {
        byte[] odd = withExif(plainJpeg(64, 48), cat(bytes("Exif\u0000\u0000"), buildTiff(true,
                list(e(DateFix.TAG_MAKE, DateFix.TYPE_ASCII, 12, bytes("Generalplus\u0000")),
                        e(DateFix.TAG_YCBCR_POSITIONING, DateFix.TYPE_SHORT, 1, new byte[]{7, 0})),
                new ArrayList<Object[]>())));
        expect("YCbCrPositioning 7 is really in the source", exifOf(odd).ycbcrPositioning == 7);
        expect("an invalid YCbCrPositioning goes back to 1",
                exifOf(DateFix.fixJpeg(odd, FALLBACK, UTC).data).ycbcrPositioning == 1);
    }

    private static void secondExifDropped() {
        byte[] first = DateFix.encodeExif(64, 48, "2026:03:03:12:16:29", 1, "Generalplus", "CBB3", 2);
        byte[] second = DateFix.encodeExif(64, 48, "2001:01:01 01:01:01", 1, "Other", "Other", 1);
        byte[] one = withExif(plainJpeg(64, 48), first);
        DateFix.Layout l1 = DateFix.scan(one);
        byte[] two = cat(Arrays.copyOfRange(one, 0, l1.exifEnd), seg(0xE1, second),
                Arrays.copyOfRange(one, l1.exifEnd, one.length));
        DateFix.Layout l2 = DateFix.scan(two);
        expect("the fixture carries two Exif APP1", l2.exifRanges.size() == 2);
        DateFix.Result r = DateFix.fixJpeg(two, FALLBACK, UTC);
        DateFix.Layout after = DateFix.scan(r.data);
        expect("a second Exif APP1 is dropped; the first one's date wins",
                after.exifRanges.size() == 1 && after.exifStart == l2.exifStart
                        && "2026:03:03 12:16:29".equals(r.dateTime) && indexOf(r.data, bytes("Other")) < 0
                        && Arrays.equals(Arrays.copyOfRange(r.data, after.exifEnd, r.data.length),
                        Arrays.copyOfRange(two, l2.exifRanges.get(1)[1], two.length)), r.note);
    }

    private static void idempotent() {
        byte[] camera = charmeraJpeg(64, 48, charmeraTiff(CAMERA_DATE, 1));
        DateFix.Result once = DateFix.fixJpeg(camera, FALLBACK, UTC);
        DateFix.Result twice = DateFix.fixJpeg(once.data, utc(2000, 1, 1, 0, 0, 0), UTC);
        expect("a second pass gives the same bytes and says so",
                !twice.changed && twice.data == once.data && "exif".equals(twice.dateSource)
                        && twice.note.startsWith("already repaired"), twice.note);
    }

    private static void otherCamerasUntouched() {
        byte[] plain = plainJpeg(64, 48);
        expect("a plain encoder frame is not the camera's", !DateFix.isCharmeraJpeg(plain));
        DateFix.Result p = DateFix.fixJpeg(plain, FALLBACK, UTC);
        expect("a plain JPEG comes back untouched", !p.changed && p.data == plain
                && Arrays.equals(plain, plainJpeg(64, 48)) && p.note.contains("not a Charmera photo"), p.note);

        byte[] phone = withExif(plainJpeg(64, 48),
                DateFix.encodeExif(64, 48, "2026:03:03 12:16:29", 6, "Apple", "iPhone", 1));
        expect("a phone photo is not the camera's", !DateFix.isCharmeraJpeg(phone));
        DateFix.Result ph = DateFix.fixJpeg(phone, FALLBACK, UTC);
        expect("a phone photo keeps its own EXIF", !ph.changed && ph.data == phone, ph.note);

        expect("isCharmeraJpeg: the GPEncoder comment",
                DateFix.isCharmeraJpeg(cat(new byte[]{(byte) 0xFF, (byte) 0xD8}, seg(0xFE, bytes("GPEncoder 1.0")))));
        expect("isCharmeraJpeg: Make Generalplus", DateFix.isCharmeraJpeg(withExif(plainJpeg(64, 48),
                DateFix.encodeExif(64, 48, "2026:03:03 12:16:29", 1, " generalplus ", "", 1))));
        expect("isCharmeraJpeg: the all-colons date alone", DateFix.isCharmeraJpeg(withExif(plainJpeg(64, 48),
                DateFix.encodeExif(64, 48, "2026:03:03:12:16:29", 1, "", "", 1))));
        expect("isCharmeraJpeg: not a JPEG, empty, null", !DateFix.isCharmeraJpeg(bytes("not a jpeg"))
                && !DateFix.isCharmeraJpeg(new byte[0]) && !DateFix.isCharmeraJpeg(null));
    }

    private static void badJpegInput() {
        byte[] text = bytes("not a jpeg");
        DateFix.Result r = DateFix.fixJpeg(text, FALLBACK, UTC);
        expect("not a JPEG: unchanged, with the reason", !r.changed && r.data == text && r.note.contains("not a JPEG"),
                r.note);
        expect("null and empty input: unchanged, no exception", !DateFix.fixJpeg(null, FALLBACK, UTC).changed
                && !DateFix.fixJpeg(new byte[0], FALLBACK, null).changed);
        byte[] noFrame = cat(new byte[]{(byte) 0xFF, (byte) 0xD8}, seg(0xFE, bytes("GPEncoder")),
                new byte[]{(byte) 0xFF, (byte) 0xD9});
        DateFix.Result nf = DateFix.fixJpeg(noFrame, FALLBACK, UTC);
        expect("a camera JPEG with no frame header: unchanged, with the reason",
                !nf.changed && nf.data == noFrame && nf.note.contains("image size"), nf.note);

        byte[] camera = charmeraJpeg(64, 48, charmeraTiff(CAMERA_DATE, 1));
        boolean ok = true;
        String why = null;
        for (int n = 0; n <= camera.length && ok; n++) {
            byte[] cut = Arrays.copyOf(camera, n);
            try {
                DateFix.Result c = DateFix.fixJpeg(cut, FALLBACK, UTC);
                ok = c.data != null && c.note != null && (c.changed || c.data == cut);
            } catch (RuntimeException e) {
                ok = false;
                why = "length " + n + ": " + e;
            }
        }
        expect("every truncation of a camera JPEG: no exception", ok, why);
        expect("random corruption of a camera JPEG: no exception", fuzz(camera, true), null);
    }

    // ---- AVI ---------------------------------------------------------------------------------------------------

    private static void captureNames() {
        byte[] photo = charmeraJpeg(640, 480, charmeraTiff(CAMERA_DATE, 1));
        expect("a photo is named from its own EXIF date, extra colon and all",
                "20260303_121629".equals(DateFix.captureName(photo, false, RECORDED, UTC)),
                DateFix.captureName(photo, false, RECORDED, UTC));
        byte[] bare = plainJpeg(640, 480);
        expect("a photo with no EXIF date is named from its file time",
                "20260807_184512".equals(DateFix.captureName(bare, false, RECORDED, UTC)),
                DateFix.captureName(bare, false, RECORDED, UTC));
        byte[] fake = minimalAvi(BOGUS_IDIT, BOGUS_ICRD);
        expect("a video with the camera's fake 2010 date is named from its file time",
                "20260807_184512".equals(DateFix.captureName(fake, true, RECORDED, UTC)),
                DateFix.captureName(fake, true, RECORDED, UTC));
        byte[] real = minimalAvi("Tue Mar  3 12:16:29 2026\n\u0000", "2026-03-03\u0000");
        expect("a video with a real date chunk is named from it",
                "20260303_121629".equals(DateFix.captureName(real, true, RECORDED, UTC)),
                DateFix.captureName(real, true, RECORDED, UTC));
        expect("no date and no file time: no name", DateFix.captureName(bare, false, 0, UTC) == null);
        expect("garbage never throws and falls back to the file time",
                "20260807_184512".equals(DateFix.captureName(new byte[]{1, 2, 3}, false, RECORDED, UTC))
                        && "20260807_184512".equals(DateFix.captureName(new byte[0], true, RECORDED, UTC)));
    }

    private static void avi() {
        byte[] original = minimalAvi(BOGUS_IDIT, BOGUS_ICRD);
        List<DateFix.AviDateField> fields = DateFix.readAviDates(original);
        expect("both date fields are found, IDIT in hdrl then ICRD in INFO",
                fields != null && fields.size() == 2 && fields.get(0).chunkId.equals("IDIT")
                        && fields.get(1).chunkId.equals("ICRD"));
        byte[] copy = original.clone();
        DateFix.Result r = DateFix.fixAvi(copy, RECORDED, UTC);
        expect("the input array is not modified", Arrays.equals(copy, original));
        List<DateFix.AviDateField> after = DateFix.readAviDates(r.data);
        expect("IDIT asctime and ICRD 2010-06-29 rewritten from the file time, same length",
                r.changed && r.data.length == original.length && after.size() == 2
                        && after.get(0).value.equals("Fri Aug  7 18:45:12 2026\n\u0000")
                        && after.get(1).value.equals("2026-08-07\u0000")
                        && r.note.equals(
                        "date 2026:08:07 18:45:12 from the file time replaces 2010-06-29 in IDIT, ICRD"),
                r.note);
        boolean onlyDates = true;
        boolean any = false;
        for (int i = 0; i < original.length; i++) {
            if (original[i] != r.data[i]) {
                any = true;
                boolean inField = false;
                for (DateFix.AviDateField f : fields) {
                    inField |= i >= f.offset && i < f.offset + f.size;
                }
                onlyDates &= inField;
            }
        }
        expect("only the date bytes changed, no RIFF size moved", any && onlyDates);
        DateFix.Result again = DateFix.fixAvi(r.data, RECORDED, UTC);
        expect("the repaired copy is left alone on a second pass", !again.changed && again.data == r.data
                && again.note.startsWith("left as is, not the camera's fake date"), again.note);

        DateFix.Result zoned = DateFix.fixAvi(original, RECORDED, TimeZone.getTimeZone("GMT+02:00"));
        expect("the file time is read in the zone given",
                DateFix.readAviDates(zoned.data).get(0).value.startsWith("Fri Aug  7 20:45:12 2026\n"));

        byte[] publicBuild = minimalAvi(null, BOGUS_ICRD); // what the public build writes: INFO/ICRD, no IDIT
        DateFix.Result pb = DateFix.fixAvi(publicBuild, RECORDED, UTC);
        expect("the public build's ICRD 2010-06-29 alone is rewritten, date only",
                pb.changed && DateFix.readAviDates(pb.data).get(0).value.equals("2026-08-07\u0000")
                        && pb.note.endsWith("in ICRD"), pb.note);

        // A header-only prefix (the zip writer streams the frames): the same patch, the same length as the prefix.
        int prefix = indexOf(publicBuild, bytes("movi")) + 4;
        DateFix.Result head = DateFix.fixAvi(Arrays.copyOf(publicBuild, prefix), RECORDED, UTC);
        expect("a header-only prefix is patched the same way and keeps its length", head.changed
                && head.data.length == prefix
                && Arrays.equals(head.data, Arrays.copyOf(pb.data, prefix)));

        byte[] genuine = minimalAvi("Sat Aug  1 10:00:00 2026\n\u0000", null);
        DateFix.Result g = DateFix.fixAvi(genuine, RECORDED, UTC);
        expect("a genuine date is left alone and reported", !g.changed && g.data == genuine
                && g.note.equals("left as is, not the camera's fake date: IDIT 'Sat Aug  1 10:00:00 2026'"), g.note);

        byte[] small = minimalAvi("Jun 29 12:00:00 2010", null); // no weekday: 20 bytes of room
        DateFix.Result s = DateFix.fixAvi(small, RECORDED, UTC);
        expect("a field too small for the new text is reported, not truncated", !s.changed && s.data == small
                && s.note.contains("would not fit: IDIT"), s.note);

        byte[] none = minimalAvi(null, null);
        DateFix.Result n = DateFix.fixAvi(none, RECORDED, UTC);
        expect("no date chunk: copied unchanged", !n.changed && n.data == none
                && n.note.equals("no IDIT/ICRD date chunk, copied unchanged"), n.note);

        // Only a video with the camera's fixed 2010-06-29 counts as the camera's own (Back up photos deletes only those).
        expect("the camera's video (IDIT and ICRD 2010-06-29) is the camera's", DateFix.isCharmeraAvi(original));
        expect("the public build's video (ICRD 2010-06-29 only) is the camera's", DateFix.isCharmeraAvi(publicBuild));
        expect("IDIT 2010-06-29 alone is the camera's", DateFix.isCharmeraAvi(minimalAvi(BOGUS_IDIT, null)));
        expect("its header-only prefix is the camera's too", DateFix.isCharmeraAvi(Arrays.copyOf(publicBuild, prefix)));
        expect("an AVI with a real date is not the camera's", !DateFix.isCharmeraAvi(genuine)
                && !DateFix.isCharmeraAvi(minimalAvi("Tue Mar  3 12:16:29 2026\n\u0000", "2026-03-03\u0000")));
        expect("an AVI with no date field is not the camera's", DateFix.isAvi(none) && !DateFix.isCharmeraAvi(none));
        expect("another day in 2010 is not the camera's", !DateFix.isCharmeraAvi(minimalAvi(null, "2010-06-28\u0000")));
        expect("the repaired copy is no longer the camera's", !DateFix.isCharmeraAvi(r.data));
        expect("a date field past the bytes read is not seen", !DateFix.isCharmeraAvi(Arrays.copyOf(publicBuild,
                indexOf(publicBuild, bytes("ICRD")) + 6)));
        expect("not an AVI, empty or null: not the camera's", !DateFix.isCharmeraAvi(bytes("not a video"))
                && !DateFix.isCharmeraAvi(new byte[0]) && !DateFix.isCharmeraAvi(null)
                && !DateFix.isCharmeraAvi(charmeraJpeg(640, 480, charmeraTiff(CAMERA_DATE, 1))));
    }

    /** The zip entry names Back up photos gives: card names, capture-time names, collisions, and originals/. */
    private static void entryNames() {
        java.util.Set<String> lower = new java.util.HashSet<>();
        expect("no stem: the card path as it is", "DCIM/100MEDIA/PICT0001.JPG".equals(
                DateFix.entryName("DCIM/100MEDIA/PICT0001.JPG", null, "", lower)));
        expect("the same card path again (another case): '-2' before the extension, case kept",
                "DCIM/100MEDIA/pict0001-2.jpg".equals(DateFix.entryName("DCIM/100MEDIA/pict0001.jpg", null, "",
                        lower)));
        expect("a stem: same folder, the stem, the extension in lower case", "DCIM/100MEDIA/20261111_114931.jpg"
                .equals(DateFix.entryName("DCIM/100MEDIA/PICT0002.JPG", "20261111_114931", "", lower)));
        expect("a second photo in the same second: '_2'", "DCIM/100MEDIA/20261111_114931_2.jpg"
                .equals(DateFix.entryName("DCIM/100MEDIA/PICT0003.JPG", "20261111_114931", "", lower)));
        expect("and a third: '_3'", "DCIM/100MEDIA/20261111_114931_3.jpg"
                .equals(DateFix.entryName("DCIM/100MEDIA/PICT0004.jpg", "20261111_114931", "", lower)));
        expect("a video with the same stem keeps its own extension, so no collision",
                "DCIM/100MEDIA/20261111_114931.avi".equals(DateFix.entryName("DCIM/100MEDIA/MOVI0001.AVI",
                        "20261111_114931", "", lower)));
        expect("the same stem in another folder: no collision", "SPIDCIM/20261111_114931.jpg"
                .equals(DateFix.entryName("SPIDCIM/PICT0001.JPG", "20261111_114931", "", lower)));
        expect("no extension, no stem", "DCIM/README".equals(DateFix.entryName("DCIM/README", null, "", lower)));
        expect("no extension, a stem", "DCIM/20260101_000000".equals(DateFix.entryName("DCIM/README2",
                "20260101_000000", "", lower)));
        expect("a dot only in a folder name is no extension", "DCIM/a.b/PICT".equals(DateFix.entryName("DCIM/a.b/PICT",
                null, "", lower)));
        expect("originals/ keeps the card path name, not the capture-time name",
                "originals/DCIM/100MEDIA/PICT0002.JPG".equals(DateFix.entryName("DCIM/100MEDIA/PICT0002.JPG", null,
                        "originals/", lower)));
        expect("originals/ names are made unique too", "originals/DCIM/100MEDIA/pict0002-2.jpg".equals(
                DateFix.entryName("DCIM/100MEDIA/pict0002.jpg", null, "originals/", lower)));
        expect("every name given is in the set, in lower case", lower.size() == 12
                && lower.contains("originals/dcim/100media/pict0002.jpg")
                && lower.contains("dcim/100media/20261111_114931_3.jpg"));
    }

    private static void badAviInput() {
        byte[] text = bytes("not a video");
        DateFix.Result r = DateFix.fixAvi(text, RECORDED, UTC);
        expect("not an AVI: unchanged, with the reason", !r.changed && r.data == text
                && r.note.contains("not an AVI"), r.note);
        expect("null and empty input: unchanged, no exception", !DateFix.fixAvi(null, RECORDED, UTC).changed
                && !DateFix.fixAvi(new byte[0], RECORDED, null).changed);
        byte[] avi = minimalAvi(BOGUS_IDIT, BOGUS_ICRD);
        boolean ok = true;
        String why = null;
        for (int len = 0; len <= avi.length && ok; len++) {
            byte[] cut = Arrays.copyOf(avi, len);
            try {
                DateFix.Result c = DateFix.fixAvi(cut, RECORDED, UTC);
                ok = c.data != null && c.data.length == len && c.note != null;
            } catch (RuntimeException e) {
                ok = false;
                why = "length " + len + ": " + e;
            }
        }
        expect("every truncation of a video: no exception, same length", ok, why);
        expect("random corruption of a video: no exception, same length", fuzz(avi, false), null);
        byte[] huge = avi.clone();
        huge[4] = huge[5] = huge[6] = huge[7] = (byte) 0xFF; // RIFF size 4 GiB
        expect("a RIFF size past the end is clipped to the data",
                DateFix.fixAvi(huge, RECORDED, UTC).changed);
    }

    private static boolean fuzz(byte[] base, boolean jpeg) {
        Random random = new Random(20261111L);
        for (int round = 0; round < 3000; round++) {
            byte[] b = base.clone();
            int flips = 1 + random.nextInt(8);
            for (int k = 0; k < flips; k++) {
                b[random.nextInt(b.length)] = (byte) random.nextInt(256);
            }
            try {
                DateFix.Result r = jpeg ? DateFix.fixJpeg(b, FALLBACK, UTC) : DateFix.fixAvi(b, RECORDED, UTC);
                if (r.data == null || r.note == null || (!jpeg && r.data.length != b.length)) {
                    System.out.println("fuzz round " + round + ": " + r.note);
                    return false;
                }
                if (r.note.startsWith("copied unchanged: unexpected error")) {
                    System.out.println("fuzz round " + round + " hit the catch-all: " + r.note);
                    return false;
                }
            } catch (RuntimeException e) {
                System.out.println("fuzz round " + round + ": " + e);
                return false;
            }
        }
        return true;
    }

    // ---- the stock dump's stored photos ------------------------------------------------------------------------

    private static void stockImage() throws IOException {
        String path = System.getenv("CHARMERA_STOCK_IMAGE");
        if (path == null || path.isEmpty() || !new File(path).isFile()) {
            System.out.println("DateFixTest: CHARMERA_STOCK_IMAGE not set, the stored photos are not checked");
            return;
        }
        byte[] image = readAll(new File(path));
        if (image.length > 0x1A5802 && (image[0x180000] & 0xFF) == 0xFF && (image[0x180001] & 0xFF) == 0xFF
                && (image[0x1A5800] & 0xFF) == 0xFF && (image[0x1A5801] & 0xFF) == 0xFF) {
            System.out.println("DateFixTest: the stock image is a cleaned copy (its photo slots are erased), the stored"
                    + " photos are not checked");
            return;
        }
        for (int offset : STOCK_PHOTO_OFFSETS) {
            String what = String.format(Locale.ROOT, "stock photo at %#x", offset);
            if (!(image.length > offset + 2 && (image[offset] & 0xFF) == 0xFF && (image[offset + 1] & 0xFF) == 0xD8)) {
                expect(what + ": a JPEG starts there", false);
                continue;
            }
            int end = indexOf(image, new byte[]{(byte) 0xFF, (byte) 0xD9}, offset) + 2;
            byte[] jpeg = Arrays.copyOfRange(image, offset, end);
            DateFix.Info info = exifOf(jpeg);
            expect(what + ": Generalplus/CBB3", info.present && info.make.equals("Generalplus")
                    && info.model.equals("CBB3"), info.make + "/" + info.model);
            expect(what + ": recognised", DateFix.isCharmeraJpeg(jpeg));
            DateFix.Result r = DateFix.fixJpeg(jpeg, FALLBACK, UTC);
            checkCameraFix(what, jpeg, r);
            expect(what + ": dated from its EXIF", "exif".equals(r.dateSource) && isValidDate(r.dateTime), r.note);
        }
    }

    private static byte[] readAll(File file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
        InputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private static byte[] seg(int marker, byte[] payload) {
        int len = payload.length + 2;
        return cat(new byte[]{(byte) 0xFF, (byte) marker, (byte) (len >> 8), (byte) len}, payload);
    }

    private static byte[] sof0(int width, int height) {
        return seg(0xC0, new byte[]{8, (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, 3,
                1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1});
    }

    private static byte[] dqt(int id) {
        byte[] b = new byte[65];
        b[0] = (byte) id;
        for (int i = 1; i < b.length; i++) {
            b[i] = (byte) (i + id);
        }
        return b;
    }

    private static byte[] dht(int classAndId) {
        byte[] b = new byte[18];
        b[0] = (byte) classAndId;
        b[1] = 1; // one code of length 1
        b[17] = 0; // its symbol
        return b;
    }

    private static byte[] app0() {
        return seg(0xE0, new byte[]{'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0});
    }

    /** SOS, scan data holding stuffed FF 00 and a restart marker, then EOI. */
    private static byte[] sosAndScan() {
        return cat(seg(0xDA, new byte[]{3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 0x3F, 0}),
                new byte[]{0x12, 0x34, (byte) 0xFF, 0x00, 0x56, (byte) 0xFF, (byte) 0xD0, 0x78, (byte) 0xE1, 0x00,
                        (byte) 0xFF, (byte) 0xD9});
    }

    /** A JPEG with a JFIF APP0 and no EXIF, like a freshly encoded frame. */
    private static byte[] plainJpeg(int width, int height) {
        return cat(new byte[]{(byte) 0xFF, (byte) 0xD8}, app0(), seg(0xDB, dqt(0)), sof0(width, height),
                seg(0xC4, dht(0x00)), sosAndScan());
    }

    /** The camera's segment order: SOI, SOF0, COM "GPEncoder" (length 0x11), DQT, DQT, DRI, 4 DHT, APP1, SOS. */
    private static byte[] charmeraJpeg(int width, int height, byte[] tiff) {
        byte[] com = Arrays.copyOf(bytes("GPEncoder"), 0x11 - 2);
        return cat(new byte[]{(byte) 0xFF, (byte) 0xD8}, sof0(width, height), seg(0xFE, com), seg(0xDB, dqt(0)),
                seg(0xDB, dqt(1)), seg(0xDD, new byte[]{0, 0}), seg(0xC4, dht(0x00)), seg(0xC4, dht(0x10)),
                seg(0xC4, dht(0x01)), seg(0xC4, dht(0x11)), seg(0xE1, cat(bytes("Exif\u0000\u0000"), tiff)),
                sosAndScan());
    }

    /**
     * The camera's EXIF block: little-endian, Generalplus/CBB3, 640x480, YCbCrPositioning 2, the given dates, a
     * MakerNote whose offset is exactly the TIFF length (one past the end) and an InteropIFD pointer past the end.
     */
    private static byte[] charmeraTiff(byte[] date, int orientation) {
        byte[] tiff = charmeraTiff(date, orientation, 0);
        return charmeraTiff(date, orientation, tiff.length); // the inline offset does not change the length
    }

    private static byte[] charmeraTiff(byte[] date, int orientation, int tiffLen) {
        return buildTiff(true,
                list(e(DateFix.TAG_MAKE, DateFix.TYPE_ASCII, 12, bytes("Generalplus\u0000")),
                        e(DateFix.TAG_MODEL, DateFix.TYPE_ASCII, 5, bytes("CBB3\u0000")),
                        e(DateFix.TAG_ORIENTATION, DateFix.TYPE_SHORT, 1, new byte[]{(byte) orientation, 0}),
                        e(DateFix.TAG_DATETIME, DateFix.TYPE_ASCII, 20, date),
                        e(DateFix.TAG_YCBCR_POSITIONING, DateFix.TYPE_SHORT, 1, new byte[]{2, 0})),
                list(e(DateFix.TAG_DATETIME_ORIGINAL, DateFix.TYPE_ASCII, 20, date),
                        e(DateFix.TAG_DATETIME_DIGITIZED, DateFix.TYPE_ASCII, 20, date),
                        e(0x927C, DateFix.TYPE_UNDEFINED, 64, le32(tiffLen)), // MakerNote at the end: nothing there
                        e(DateFix.TAG_PIXEL_X, DateFix.TYPE_LONG, 1, le32(640)),
                        e(DateFix.TAG_PIXEL_Y, DateFix.TYPE_LONG, 1, le32(480)),
                        e(0xA005, DateFix.TYPE_LONG, 1, le32(tiffLen + 1000)))); // InteropIFD past the end
    }

    private static Object[] e(int tag, int typ, int count, byte[] value) {
        return new Object[]{tag, typ, count, value};
    }

    private static List<Object[]> list(Object[]... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    /**
     * A TIFF (test_exif.py's build_tiff): entries sorted by tag, values over 4 bytes out of line, 4-byte values inline
     * as given (so a raw offset can be planted), an ExifIFD pointer added to IFD0.
     */
    private static byte[] buildTiff(boolean little, List<Object[]> ifd0, List<Object[]> exifIfd) {
        int exifOff = 8 + ifdSize(ifd0.size() + 1);
        List<Object[]> first = new ArrayList<>(ifd0);
        first.add(e(DateFix.TAG_EXIF_IFD, DateFix.TYPE_LONG, 1, little ? le32(exifOff) : be32(exifOff)));
        int extBase = exifOff + ifdSize(exifIfd.size());
        ByteArrayOutputStream ext = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(little ? 'I' : 'M');
        out.write(little ? 'I' : 'M');
        write(out, u16(0x2A, little));
        write(out, little ? le32(8) : be32(8));
        List<List<Object[]>> dirs = new ArrayList<>();
        dirs.add(first);
        dirs.add(new ArrayList<>(exifIfd));
        for (List<Object[]> entries : dirs) {
            Collections.sort(entries, new Comparator<Object[]>() {
                @Override
                public int compare(Object[] a, Object[] b) {
                    return Integer.compare((Integer) a[0], (Integer) b[0]);
                }
            });
            write(out, u16(entries.size(), little));
            for (Object[] en : entries) {
                write(out, u16((Integer) en[0], little));
                write(out, u16((Integer) en[1], little));
                write(out, little ? le32((Integer) en[2]) : be32((Integer) en[2]));
                byte[] value = (byte[]) en[3];
                if (value.length <= 4) {
                    write(out, Arrays.copyOf(value, 4));
                } else {
                    int at = extBase + ext.size();
                    write(out, little ? le32(at) : be32(at));
                    write(ext, value);
                    if (value.length % 2 == 1) {
                        ext.write(0);
                    }
                }
            }
            write(out, new byte[4]);
        }
        write(out, ext.toByteArray());
        return out.toByteArray();
    }

    private static int ifdSize(int n) {
        return 2 + 12 * n + 4;
    }

    private static byte[] riffChunk(String id, byte[] data) {
        byte[] pad = data.length % 2 == 1 ? new byte[1] : new byte[0];
        return cat(bytes(id), le32(data.length), data, pad);
    }

    private static byte[] riffList(String kind, byte[]... children) {
        byte[] body = cat(bytes(kind), cat(children));
        return cat(bytes("LIST"), le32(body.length), body);
    }

    /**
     * A video as the C# importer's sample builder lays one out (test_exif.py's minimal_avi): MJPG 1440x1080 + PCM, IDIT
     * in hdrl (asctime) and ICRD in INFO (ISO); null leaves a field out.
     */
    private static byte[] minimalAvi(String idit, String icrd) {
        byte[] avih = new byte[56];
        System.arraycopy(le32(33333), 0, avih, 0, 4);
        System.arraycopy(le32(90), 0, avih, 16, 4);
        System.arraycopy(le32(2), 0, avih, 24, 4);
        System.arraycopy(le32(1440), 0, avih, 32, 4);
        System.arraycopy(le32(1080), 0, avih, 36, 4);
        byte[] vstrh = new byte[56];
        System.arraycopy(bytes("vidsMJPG"), 0, vstrh, 0, 8);
        byte[] vstrf = cat(le32(40), le32(1440), le32(1080), new byte[28]);
        byte[] astrh = new byte[56];
        System.arraycopy(bytes("auds"), 0, astrh, 0, 4);
        byte[] astrf = cat(new byte[]{1, 0, 1, 0}, le32(22050), le32(44100), new byte[]{2, 0, 16, 0});
        List<byte[]> hdrl = new ArrayList<>();
        hdrl.add(riffChunk("avih", avih));
        hdrl.add(riffList("strl", riffChunk("strh", vstrh), riffChunk("strf", vstrf)));
        hdrl.add(riffList("strl", riffChunk("strh", astrh), riffChunk("strf", astrf)));
        if (idit != null) {
            hdrl.add(riffChunk("IDIT", bytes(idit)));
        }
        byte[] frame = new byte[1028];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xD8;
        for (int i = 2; i < 1026; i++) {
            frame[i] = (byte) i;
        }
        frame[1026] = (byte) 0xFF;
        frame[1027] = (byte) 0xD9;
        List<byte[]> top = new ArrayList<>();
        top.add(riffList("hdrl", hdrl.toArray(new byte[0][])));
        if (icrd != null) {
            top.add(riffList("INFO", riffChunk("ICRD", bytes(icrd))));
        }
        top.add(riffList("movi", riffChunk("01wb", new byte[64]), riffChunk("00dc", frame), riffChunk("00dc", frame)));
        top.add(riffChunk("idx1", new byte[16]));
        byte[] body = cat(top.toArray(new byte[0][]));
        return cat(bytes("RIFF"), le32(4 + body.length), bytes("AVI "), body);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static byte[] withExif(byte[] jpeg, byte[] payload) {
        return DateFix.replaceExif(jpeg, DateFix.scan(jpeg), payload);
    }

    private static DateFix.Info exifOf(byte[] jpeg) {
        DateFix.Layout layout = DateFix.scan(jpeg);
        if (layout == null || !layout.exifFound) {
            return new DateFix.Info();
        }
        return DateFix.readExif(jpeg, layout.exifStart + 10, layout.exifEnd);
    }

    /** {marker, start, end} for every segment up to and including SOS, whose end is the end of the file. */
    private static List<int[]> segments(byte[] jpeg) {
        List<int[]> out = new ArrayList<>();
        int pos = 2;
        while (pos + 1 < jpeg.length) {
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA) {
                out.add(new int[]{marker, pos, jpeg.length});
                break;
            }
            int end = pos + 2 + ((jpeg[pos + 2] & 0xFF) << 8 | (jpeg[pos + 3] & 0xFF));
            out.add(new int[]{marker, pos, end});
            pos = end;
        }
        return out;
    }

    private static List<Integer> markers(List<int[]> segs) {
        List<Integer> out = new ArrayList<>();
        for (int[] s : segs) {
            out.add(s[0]);
        }
        return out;
    }

    private static boolean isValidDate(String s) {
        return s != null && s.matches("^\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}$");
    }

    private static long utc(int y, int mo, int d, int h, int mi, int s) {
        GregorianCalendar c = new GregorianCalendar(UTC, Locale.US);
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        return c.getTimeInMillis();
    }

    private static byte[] bytes(String s) {
        return DateFix.ascii(s);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            write(out, p);
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    private static byte[] u16(int v, boolean little) {
        return little ? new byte[]{(byte) v, (byte) (v >> 8)} : new byte[]{(byte) (v >> 8), (byte) v};
    }

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] be32(int v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static int indexOf(byte[] b, byte[] needle) {
        return indexOf(b, needle, 0);
    }

    private static int indexOf(byte[] b, byte[] needle, int from) {
        outer:
        for (int i = from; i + needle.length <= b.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (b[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
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
