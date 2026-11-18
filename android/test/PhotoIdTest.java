package io.github.daemonddev.charmera;

import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Plain-JVM tests for the photo header check. Synthetic headers carry the segments of the two templates photo_id.py
 * describes and of firmware v0.26's EXIF header; with CHARMERA_STOCK_IMAGE set to the public stock dump, the dump's own
 * bare template (file 0xFFB78) must read as the bare header, and with CHARMERA_FACTORY_DUMP set, v0.25's and v0.26's
 * still templates as theirs.
 */
public final class PhotoIdTest {

    private static int passes = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        byte[] stillCom = "GPEncoder      ".getBytes(StandardCharsets.US_ASCII);
        byte[] bareCom = "GPEncoder".getBytes(StandardCharsets.US_ASCII);
        expect("the COM length fields are the templates' 0x11 and 0x0B",
                seg(0xFE, stillCom)[3] == 0x11 && seg(0xFE, bareCom)[3] == 0x0B);

        byte[] still = jpeg(seg(0xFE, stillCom), exif(true, "Generalplus", "CBB3"));
        expect("the still header with Generalplus/CBB3 is the public family", kind(still) == PhotoId.Kind.PUBLIC_FAMILY);
        expect("big-endian EXIF reads too", kind(jpeg(seg(0xFE, stillCom), exif(false, "Generalplus", "CBB3")))
                == PhotoId.Kind.PUBLIC_FAMILY);
        expect("a NUL- and space-padded Make still matches", kind(jpeg(seg(0xFE, stillCom), exif(true, "Generalplus  ", "CBB3")))
                == PhotoId.Kind.PUBLIC_FAMILY);
        expect("image data after the scan header is never needed", kind(concat(still, new byte[70000])) == PhotoId.Kind.PUBLIC_FAMILY);
        PhotoId.Result r = PhotoId.identify(still);
        expect("make and model are reported", "Generalplus".equals(r.make) && "CBB3".equals(r.model) && r.reasons.isEmpty());
        expect("and the Software", "20150615".equals(r.software));

        // The camera always writes Software 20150615 on a still photo (photo_id.py's 'still'): the same Generalplus/CBB3
        // header without it is a copy the toolkit's exif-fix --keep-make made, not camera-written (photo_id.py's
        // 'exif-fix'), and another Software is no known build's (photo_id.py's 'unknown').
        byte[] keepMake = jpeg(seg(0xFE, stillCom), exif(true, "Generalplus", "CBB3", null));
        expect("Generalplus/CBB3 without Software is an exif-fix copy, not the public family",
                reasonIs(keepMake, "an exif-fix copy") && "".equals(PhotoId.identify(keepMake).software));
        expect("Generalplus/CBB3 with another Software is other", reasonIs(jpeg(seg(0xFE, stillCom),
                exif(true, "Generalplus", "CBB3", "20990101")), "EXIF Software '20990101', not 20150615"));
        expect("Kodak/Charmera without Software on the still template is an exif-fix copy",
                reasonIs(jpeg(seg(0xFE, stillCom), exif(true, "Kodak", "Charmera", null)), "an exif-fix copy"));

        byte[] bare = jpeg(seg(0xFE, bareCom));
        expect("the bare comment without APP1 is the original bare header", kind(bare) == PhotoId.Kind.ORIGINAL_BARE);

        // what keeps a header out of either kind
        expect("the bare comment with an EXIF block is other", reasonIs(jpeg(seg(0xFE, bareCom), exif(true, "Generalplus", "CBB3")),
                "the bare comment with an APP1 block"));
        expect("the still comment without EXIF is other", reasonIs(jpeg(seg(0xFE, stillCom)), "no EXIF block"));
        expect("another Make is other", reasonIs(jpeg(seg(0xFE, stillCom), exif(true, "Canon", "CBB3")), "EXIF Make 'Canon'"));
        expect("another Model is other", reasonIs(jpeg(seg(0xFE, stillCom), exif(true, "Generalplus", "X")), "EXIF Model 'X'"));
        expect("no comment is other", reasonIs(jpeg(exif(true, "Generalplus", "CBB3")), "no GPEncoder comment"));
        expect("a different comment is other", reasonIs(jpeg(seg(0xFE, "GPEncoder ".getBytes(StandardCharsets.US_ASCII))),
                "a comment other than the GPEncoder one"));
        expect("two comments are other", reasonIs(jpeg(seg(0xFE, bareCom), seg(0xFE, bareCom)), "more than one comment"));
        expect("an EXIF block with a broken TIFF header is no EXIF", reasonIs(jpeg(seg(0xFE, stillCom),
                seg(0xE1, concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII), "XX*\0".getBytes(StandardCharsets.US_ASCII)))),
                "no EXIF block"));

        // Firmware v0.26: the bare comment, one Kodak/Charmera APP1 right after the SOI, a 0x340-byte header.
        byte[] v026 = factoryHeader(PhotoId.FACTORY_EXIF_HEADER, "Kodak", "Charmera");
        expect("v0.26's header is the factory EXIF one", v026.length == 0x340 && kind(v026) == PhotoId.Kind.FACTORY_EXIF);
        expect("its make and model are reported", "Kodak".equals(PhotoId.identify(v026).make)
                && "Charmera".equals(PhotoId.identify(v026).model));
        expect("image data after it is never needed", kind(concat(v026, new byte[70000])) == PhotoId.Kind.FACTORY_EXIF);
        expect("its Software is reported", "v0.26".equals(PhotoId.identify(v026).software));
        // exif-fix writes Kodak/Charmera with no Software on a copy of a bare photo: not the EXIF mod (photo_id.py)
        byte[] fixedBare = factoryHeader(PhotoId.FACTORY_EXIF_HEADER, exif(true, "Kodak", "Charmera", null));
        expect("the EXIF mod's layout without Software is an exif-fix copy", fixedBare.length == 0x340
                && reasonIs(fixedBare, "an exif-fix copy"));
        expect("a Kodak/Charmera header of another length is other", reasonIs(factoryHeader(0x350, "Kodak", "Charmera"),
                "of 0x350 bytes, not 0x340"));
        expect("another Make after the SOI is other", reasonIs(factoryHeader(0x340, "Generalplus", "Charmera"),
                "the bare comment with an APP1 block"));
        expect("the Kodak APP1 anywhere but right after the SOI is other", reasonIs(jpeg(seg(0xFE, bareCom),
                exif(true, "Kodak", "Charmera")), "the bare comment with an APP1 block"));
        expect("two APP1 blocks are other", kind(concat(new byte[] {(byte) 0xFF, (byte) 0xD8}, exif(true, "Kodak",
                "Charmera"), Arrays.copyOfRange(factoryHeader(0x340, "Kodak", "Charmera"), 2, 0x340)))
                == PhotoId.Kind.OTHER);
        expect("the still comment with Kodak EXIF is not v0.26's", kind(concat(new byte[] {(byte) 0xFF, (byte) 0xD8},
                exif(true, "Kodak", "Charmera"), Arrays.copyOfRange(jpeg(seg(0xFE, stillCom)), 2, jpeg(seg(0xFE,
                stillCom)).length))) == PhotoId.Kind.OTHER);

        // What the flash check expects after each write: v0.26's EXIF header, the bare one for v0.25, else the public
        // family's (the stock restore file, the probe, v0.24, or no known firmware).
        expect("v0.26 expects the factory EXIF header", PhotoId.expectedFor(Candidates.Kind.V026)
                == PhotoId.Kind.FACTORY_EXIF);
        expect("v0.25 expects the bare factory header", PhotoId.expectedFor(Candidates.Kind.V025)
                == PhotoId.Kind.ORIGINAL_BARE);
        expect("v0.24, the probe, the restore file and an unknown firmware expect the public family",
                PhotoId.expectedFor(Candidates.Kind.BRINGUP) == PhotoId.Kind.PUBLIC_FAMILY
                        && PhotoId.expectedFor(Candidates.Kind.PROBE) == PhotoId.Kind.PUBLIC_FAMILY
                        && PhotoId.expectedFor(Candidates.Kind.RESTORE) == PhotoId.Kind.PUBLIC_FAMILY
                        && PhotoId.expectedFor(null) == PhotoId.Kind.PUBLIC_FAMILY);
        boolean every = true;
        for (Candidates.Kind k : Candidates.repairVersions()) {
            every &= PhotoId.expectedFor(k) != PhotoId.Kind.OTHER;
        }
        expect("every Repair version expects a header the check knows", every);

        // damaged input never throws
        expect("null is other", kind(null) == PhotoId.Kind.OTHER);
        expect("not a JPEG", reasonIs(new byte[] {1, 2, 3}, "no JPEG start marker"));
        expect("empty", kind(new byte[0]) == PhotoId.Kind.OTHER);
        expect("cut short before the scan", reasonIs(Arrays.copyOf(still, still.length - 8), "cut off")
                || reasonIs(Arrays.copyOf(still, still.length - 8), "ends before the start of scan"));
        expect("cut right after a segment", reasonIs(concat(new byte[] {(byte) 0xFF, (byte) 0xD8}, seg(0xFE, bareCom)), "ends before the start of scan"));
        expect("an end marker before the scan", reasonIs(concat(new byte[] {(byte) 0xFF, (byte) 0xD8},
                seg(0xFE, bareCom), new byte[] {(byte) 0xFF, (byte) 0xD9}), "an end marker before the start of scan"));
        expect("garbage where a marker should be", reasonIs(concat(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x12, 0x34}), "no marker at 0x2"));
        expect("fill bytes before a marker are skipped", kind(concat(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                Arrays.copyOfRange(bare, 2, bare.length))) == PhotoId.Kind.ORIGINAL_BARE);
        byte[] far = concat(new byte[] {(byte) 0xFF, (byte) 0xD8}, seg(0xE2, new byte[0xFFF0]), seg(0xE2, new byte[0xFFF0]),
                seg(0xFE, bareCom), sos());
        expect("nothing past the first 64 KiB is read", reasonIs(far, "is cut off"));
        byte[] evil = jpeg(seg(0xFE, stillCom), seg(0xE1, concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII),
                new byte[] {'I', 'I', 0x2A, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F})));
        expect("an IFD offset past the block does not throw", kind(evil) == PhotoId.Kind.OTHER);
        byte[] evil2 = jpeg(seg(0xFE, stillCom), seg(0xE1, concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII),
                new byte[] {'I', 'I', 0x2A, 0, 8, 0, 0, 0, 1, 0, 0x0F, 1, 2, 0, 50, 0, 0, 0, (byte) 0xF0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF})));
        expect("a string offset past the block does not throw", kind(evil2) == PhotoId.Kind.OTHER);

        String real = System.getenv("CHARMERA_STOCK_IMAGE");
        if (real == null || real.isEmpty()) {
            System.out.println("PhotoIdTest: real-dump check SKIPPED (set CHARMERA_STOCK_IMAGE to the public stock dump)");
        } else {
            byte[] template = new byte[0x270];
            try (RandomAccessFile f = new RandomAccessFile(real, "r")) {
                f.seek(0xFFB78);
                f.readFully(template);
            }
            expect("the dump's bare template reads as the bare header", kind(template) == PhotoId.Kind.ORIGINAL_BARE);
        }

        String factoryPath = System.getenv("CHARMERA_FACTORY_DUMP");
        byte[] factory = factoryPath == null || factoryPath.isEmpty() ? null
                : java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(factoryPath));
        if (factory == null || !Candidates.isFactoryDump(factory)) {
            System.out.println("PhotoIdTest: factory-dump checks SKIPPED (set CHARMERA_FACTORY_DUMP to the factory dump)");
        } else {
            // The templates the firmware copies into every still photo (file offset = VA + 0x200).
            byte[] v025 = Candidates.build(factory, Candidates.Kind.V025);
            byte[] v026b = Candidates.build(factory, Candidates.Kind.V026);
            expect("v0.25's still template reads as the bare header",
                    kind(Arrays.copyOfRange(v025, 0x107538, 0x107538 + 0x270)) == PhotoId.Kind.ORIGINAL_BARE);
            expect("v0.26's still template reads as the factory EXIF header", kind(Arrays.copyOfRange(v026b, 0x89640,
                    0x89640 + PhotoId.FACTORY_EXIF_HEADER)) == PhotoId.Kind.FACTORY_EXIF);
        }

        System.out.printf(Locale.ROOT, "PhotoIdTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    static PhotoId.Kind kind(byte[] data) {
        return PhotoId.identify(data).kind;
    }

    static boolean reasonIs(byte[] data, String part) {
        PhotoId.Result r = PhotoId.identify(data);
        if (r.kind != PhotoId.Kind.OTHER) {
            return false;
        }
        for (String s : r.reasons) {
            if (s.contains(part)) {
                return true;
            }
        }
        System.out.println("  reasons were " + r.reasons);
        return false;
    }

    /** SOI, a SOF0, the given segments, a DQT, DRI 0 and the templates' scan header. */
    static byte[] jpeg(byte[]... middle) {
        byte[] sof = seg(0xC0, new byte[] {8, 0x02, (byte) 0xD0, 0x05, 0x00, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1});
        byte[] all = concat(new byte[] {(byte) 0xFF, (byte) 0xD8}, sof);
        for (byte[] m : middle) {
            all = concat(all, m);
        }
        return concat(all, seg(0xDB, new byte[65]), seg(0xDD, new byte[2]), sos());
    }

    /**
     * v0.26's header shape: SOI, an APP1 Exif block with this Make and Model, SOF0, the bare comment, a DQT sized so the
     * header is total bytes long, DRI and the scan header.
     */
    static byte[] factoryHeader(int total, String make, String model) {
        return factoryHeader(total, exif(true, make, model));
    }

    /** v0.26's header shape around this APP1 block. */
    static byte[] factoryHeader(int total, byte[] app1) {
        byte[] head = concat(new byte[] {(byte) 0xFF, (byte) 0xD8}, app1,
                seg(0xC0, new byte[] {8, 0x02, (byte) 0xD0, 0x05, 0x00, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1}),
                seg(0xFE, "GPEncoder".getBytes(StandardCharsets.US_ASCII)));
        byte[] tail = concat(seg(0xDD, new byte[2]), sos());
        return concat(head, seg(0xDB, new byte[total - head.length - tail.length - 4]), tail);
    }

    static byte[] sos() {
        return seg(0xDA, new byte[] {3, 1, 0, 2, 0x11, 3, 0x11, 0, 0x3F, 0});
    }

    static byte[] seg(int marker, byte[] payload) {
        int len = payload.length + 2;
        return concat(new byte[] {(byte) 0xFF, (byte) marker, (byte) (len >> 8), (byte) len}, payload);
    }

    /**
     * An APP1 Exif block whose IFD0 holds Make, Model and the Software the camera writes with that Make: 20150615 for
     * Generalplus (the v006 family's still writer), v0.26 for Kodak (our EXIF mod), none for any other.
     */
    static byte[] exif(boolean little, String make, String model) {
        String trimmed = make.trim();
        return exif(little, make, model, trimmed.equals("Generalplus") ? "20150615"
                : trimmed.equals("Kodak") ? "v0.26" : null);
    }

    /** An APP1 Exif block whose IFD0 holds Make, Model and, unless null, Software (each stored by offset). */
    static byte[] exif(boolean little, String make, String model, String software) {
        byte[] mk = (make + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] md = (model + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] sw = software == null ? null : (software + "\0").getBytes(StandardCharsets.US_ASCII);
        int entries = sw == null ? 2 : 3;
        int ifd = 8;
        int data = ifd + 2 + entries * 12 + 4;
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write(little ? 'I' : 'M');
        t.write(little ? 'I' : 'M');
        put(t, 0x2A, 2, little);
        put(t, ifd, 4, little);
        put(t, entries, 2, little);
        entry(t, 0x010F, mk.length, data, mk, little);
        entry(t, 0x0110, md.length, data + mk.length, md, little);
        if (sw != null) {
            entry(t, 0x0131, sw.length, data + mk.length + md.length, sw, little);
        }
        put(t, 0, 4, little);
        for (byte[] text : new byte[][] {mk, md, sw}) {
            if (text != null && text.length > 4) {
                t.write(text, 0, text.length);
            }
        }
        return seg(0xE1, concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII), t.toByteArray()));
    }

    static void entry(ByteArrayOutputStream t, int tag, int count, int offset, byte[] inline, boolean little) {
        put(t, tag, 2, little);
        put(t, 2, 2, little);
        put(t, count, 4, little);
        if (count <= 4) {
            byte[] v = Arrays.copyOf(inline, 4);
            t.write(v, 0, 4);
        } else {
            put(t, offset, 4, little);
        }
    }

    static void put(ByteArrayOutputStream t, long v, int n, boolean little) {
        for (int i = 0; i < n; i++) {
            int shift = little ? 8 * i : 8 * (n - 1 - i);
            t.write((int) (v >> shift) & 0xFF);
        }
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
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
