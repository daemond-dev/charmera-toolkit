package io.github.daemonddev.charmera;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What the header of a photo says about the firmware family that wrote it, ported from the marker logic of
 * charmera_toolkit/photo_id.py. No Android imports, so it runs on a plain JVM in android/test/PhotoIdTest.java.
 * Read-only: only the segments from the SOI up to and including the SOS header are read, never the image data after
 * it and never past the first 64 KiB.
 *
 * - PUBLIC_FAMILY: the comment of the still template both known builds write ("GPEncoder" and six spaces, COM length
 *   field 0x11) and an EXIF APP1 block with Make Generalplus, Model CBB3 and Software 20150615 (the camera always
 *   writes that Software; the same names without it are a copy the toolkit's exif-fix --keep-make made: OTHER);
 * - ORIGINAL_BARE: the bare template's comment ("GPEncoder", COM length field 0x0B) and no APP1 segment at all, which
 *   neither known build writes for a still photo (the factory build v012, and firmware v0.25 made from it, write it);
 * - FACTORY_EXIF: firmware v0.26's header, 0x340 bytes up to the end of the SOS header: the bare comment and one APP1
 *   block, right after the SOI, with EXIF Make Kodak, Model Charmera and a Software tag (exif-fix writes the same
 *   names on its copies, but no Software: such a copy is OTHER);
 * - OTHER: anything else, with what differs in reasons.
 *
 * This is the family check only (comment and EXIF names), not the whole-template comparison photo_id.py makes, and a
 * photo that matches does not prove which build the camera runs: two builds can share a template.
 */
final class PhotoId {

    enum Kind { PUBLIC_FAMILY, ORIGINAL_BARE, FACTORY_EXIF, OTHER }

    static final int HEADER_LIMIT = 0x10000;
    static final byte[] STILL_COMMENT = "GPEncoder      ".getBytes(StandardCharsets.US_ASCII);   // COM length field 0x11
    static final byte[] BARE_COMMENT = "GPEncoder".getBytes(StandardCharsets.US_ASCII);         // COM length field 0x0B
    static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};
    static final String CAMERA_MAKE = "Generalplus";
    static final String CAMERA_MODEL = "CBB3";
    /** The Software tag the v006 family's still writer always fills in (photo_id.CAMERA_SOFTWARE). */
    static final String CAMERA_SOFTWARE = "20150615";
    /** Firmware v0.26's EXIF names and header length (SOI up to the end of the SOS header). */
    static final String FACTORY_MAKE = "Kodak";
    static final String FACTORY_MODEL = "Charmera";
    static final int FACTORY_EXIF_HEADER = 0x340;

    /**
     * The header the photos of a written firmware should have: the factory build's bare one for v0.25, the EXIF mod's
     * one for v0.26 and v0.27, and the public build's family for everything else (the stock restore file, the probe, v0.24, or a firmware
     * the app does not know, null).
     */
    static Kind expectedFor(Candidates.Kind written) {
        if (written == Candidates.Kind.V026 || written == Candidates.Kind.V027) {   // both carry the EXIF mod
            return Kind.FACTORY_EXIF;
        }
        if (written != null && written.base == Candidates.Base.FACTORY) {
            return Kind.ORIGINAL_BARE;
        }
        return Kind.PUBLIC_FAMILY;
    }

    static final class Result {
        final Kind kind;
        /** Why the header is OTHER (what is missing or differs); empty otherwise. */
        final List<String> reasons;
        /** The EXIF Make, Model and Software ("" for a missing tag), or null without an EXIF block. */
        final String make;
        final String model;
        final String software;

        Result(Kind kind, List<String> reasons, String make, String model, String software) {
            this.kind = kind;
            this.reasons = reasons;
            this.make = make;
            this.model = model;
            this.software = software;
        }
    }

    static final class Segment {
        final int marker;
        final int offset;       // of the 0xFF that starts it
        final byte[] payload;   // after the length field

        Segment(int marker, int offset, byte[] payload) {
            this.marker = marker;
            this.offset = offset;
            this.payload = payload;
        }
    }

    private PhotoId() {
    }

    /**
     * The segments from the SOI at 0 up to and including the SOS header, read from data (the start of the file; more
     * than HEADER_LIMIT bytes are not looked at). problem[0] gets why the header could not be read whole, or null.
     */
    static List<Segment> segments(byte[] data, String[] problem) {
        List<Segment> out = new ArrayList<>();
        int limit = Math.min(data.length, HEADER_LIMIT);
        problem[0] = null;
        if (limit < 2 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            problem[0] = "no JPEG start marker (FF D8)";
            return out;
        }
        out.add(new Segment(0xD8, 0, new byte[0]));
        int pos = 2;
        while (true) {
            if (pos + 2 > limit) {
                problem[0] = "the header ends before the start of scan";
                return out;
            }
            if ((data[pos] & 0xFF) != 0xFF) {
                problem[0] = String.format(Locale.ROOT, "no marker at 0x%x", pos);
                return out;
            }
            int marker = data[pos + 1] & 0xFF;
            if (marker == 0xFF) {                                  // a fill byte before the marker
                pos += 1;
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2;
                continue;
            }
            if (marker == 0xD9) {
                problem[0] = "an end marker before the start of scan";
                return out;
            }
            if (pos + 4 > limit) {
                problem[0] = "the header ends before the start of scan";
                return out;
            }
            int length = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            if (length < 2 || pos + 2 + length > limit) {
                problem[0] = String.format(Locale.ROOT, "the segment FF%02X at 0x%x is cut off", marker, pos);
                return out;
            }
            out.add(new Segment(marker, pos, Arrays.copyOfRange(data, pos + 4, pos + 2 + length)));
            pos += 2 + length;
            if (marker == 0xDA) {
                return out;
            }
        }
    }

    /** Classifies the header at the start of data (a whole photo, or at least its first bytes). Never throws. */
    static Result identify(byte[] data) {
        List<String> reasons = new ArrayList<>();
        if (data == null) {
            reasons.add("no data");
            return new Result(Kind.OTHER, reasons, null, null, null);
        }
        String[] problem = new String[1];
        List<Segment> segs = segments(data, problem);
        List<byte[]> comments = new ArrayList<>();
        int app1s = 0;
        boolean app1First = segs.size() > 1 && segs.get(1).marker == 0xE1;
        boolean app1 = false;
        String make = null;
        String model = null;
        String software = null;
        for (Segment s : segs) {
            if (s.marker == 0xFE) {
                comments.add(s.payload);
            } else if (s.marker == 0xE1) {
                app1 = true;
                app1s++;
                if (make == null && startsWith(s.payload, EXIF_HEADER)) {
                    String[] mm = makeModel(Arrays.copyOfRange(s.payload, EXIF_HEADER.length, s.payload.length));
                    make = mm[0];
                    model = mm[1];
                    software = mm[2];
                }
            }
        }
        if (problem[0] != null) {
            reasons.add(problem[0]);
            return new Result(Kind.OTHER, reasons, make, model, software);
        }
        boolean still = comments.size() == 1 && Arrays.equals(comments.get(0), STILL_COMMENT);
        boolean bare = comments.size() == 1 && Arrays.equals(comments.get(0), BARE_COMMENT);
        boolean camera = make != null && CAMERA_MAKE.equals(make) && CAMERA_MODEL.equals(model);
        boolean kodak = make != null && FACTORY_MAKE.equals(make) && FACTORY_MODEL.equals(model);
        if (still && camera && CAMERA_SOFTWARE.equals(software)) {
            return new Result(Kind.PUBLIC_FAMILY, reasons, make, model, software);
        }
        if (bare && !app1) {
            return new Result(Kind.ORIGINAL_BARE, reasons, make, model, software);
        }
        Segment sos = segs.get(segs.size() - 1);
        int headerEnd = sos.offset + 4 + sos.payload.length;
        // The toolkit's exif-fix writes no Software tag on its copies: Kodak/Charmera, or with --keep-make the
        // camera's Generalplus/CBB3, on the template the photo had (photo_id.py's 'exif-fix', not camera-written).
        if ((still && (camera || kodak) || bare && app1s == 1 && app1First && kodak) && software.isEmpty()) {
            reasons.add("an exif-fix copy (EXIF Make " + make + ", Model " + model + ", no Software), not a photo as"
                    + " the camera saved it");
            return new Result(Kind.OTHER, reasons, make, model, software);
        }
        if (bare && app1s == 1 && app1First && kodak) {
            if (headerEnd == FACTORY_EXIF_HEADER) {
                return new Result(Kind.FACTORY_EXIF, reasons, make, model, software);
            }
            reasons.add(String.format(Locale.ROOT, "a Kodak/Charmera EXIF header of 0x%x bytes, not 0x%x", headerEnd,
                    FACTORY_EXIF_HEADER));
        }
        if (comments.isEmpty()) {
            reasons.add("no GPEncoder comment");
        } else if (comments.size() > 1) {
            reasons.add("more than one comment");
        } else if (!still && !bare) {
            reasons.add("a comment other than the GPEncoder one");
        }
        if (bare && app1 && reasons.isEmpty()) {
            reasons.add("the bare comment with an APP1 block");
        }
        if (still) {
            if (make == null) {
                reasons.add("no EXIF block");
            } else {
                if (!CAMERA_MAKE.equals(make)) {
                    reasons.add("EXIF Make '" + make + "', not " + CAMERA_MAKE);
                }
                if (!CAMERA_MODEL.equals(model)) {
                    reasons.add("EXIF Model '" + model + "', not " + CAMERA_MODEL);
                }
                if (!CAMERA_SOFTWARE.equals(software)) {
                    reasons.add("EXIF Software '" + software + "', not " + CAMERA_SOFTWARE);
                }
            }
        }
        return new Result(Kind.OTHER, reasons, make, model, software);
    }

    /**
     * {Make, Model, Software} from IFD0 of the TIFF block after "Exif\0\0" ("" for a missing tag), or all null when
     * unreadable.
     */
    static String[] makeModel(byte[] tiff) {
        String[] none = {null, null, null};
        if (tiff.length < 8) {
            return none;
        }
        boolean little;
        if (tiff[0] == 'I' && tiff[1] == 'I') {
            little = true;
        } else if (tiff[0] == 'M' && tiff[1] == 'M') {
            little = false;
        } else {
            return none;
        }
        if (u16(tiff, 2, little) != 0x2A) {
            return none;
        }
        String[] out = {"", "", ""};
        long ifd0 = u32(tiff, 4, little);
        if (ifd0 + 2 > tiff.length) {
            return out;
        }
        int off = (int) ifd0;
        int count = u16(tiff, off, little);
        int pos = off + 2;
        for (int i = 0; i < count && pos + 12 <= tiff.length; i++, pos += 12) {
            int tag = u16(tiff, pos, little);
            if (tag == 0x010F) {
                out[0] = ascii(tiff, pos, little);
            } else if (tag == 0x0110) {
                out[1] = ascii(tiff, pos, little);
            } else if (tag == 0x0131) {
                out[2] = ascii(tiff, pos, little);
            }
        }
        return out;
    }

    /** An ASCII entry's text, NULs and spaces trimmed from both ends as exif.py does; "" when it is not ASCII or cut off. */
    private static String ascii(byte[] tiff, int entry, boolean little) {
        int type = u16(tiff, entry + 2, little);
        long cnt = u32(tiff, entry + 4, little);
        if (type != 2 || cnt <= 0) {
            return "";
        }
        int start;
        if (cnt <= 4) {
            start = entry + 8;
        } else {
            long o = u32(tiff, entry + 8, little);
            if (o + cnt > tiff.length) {
                return "";
            }
            start = (int) o;
        }
        int a = start;
        int b = start + (int) cnt;
        while (a < b && (tiff[a] == 0 || tiff[a] == ' ')) {
            a++;
        }
        while (b > a && (tiff[b - 1] == 0 || tiff[b - 1] == ' ')) {
            b--;
        }
        StringBuilder sb = new StringBuilder(b - a);
        for (int i = a; i < b; i++) {
            int c = tiff[i] & 0xFF;
            sb.append(c < 0x80 ? (char) c : '�');
        }
        return sb.toString();
    }

    private static int u16(byte[] d, int o, boolean little) {
        int a = d[o] & 0xFF;
        int b = d[o + 1] & 0xFF;
        return little ? a | (b << 8) : (a << 8) | b;
    }

    private static long u32(byte[] d, int o, boolean little) {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            int byteAt = little ? o + 3 - i : o + i;
            v = (v << 8) | (d[byteAt] & 0xFF);
        }
        return v;
    }

    private static boolean startsWith(byte[] a, byte[] prefix) {
        if (a.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (a[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
