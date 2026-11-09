package io.github.daemonddev.charmera;

import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Makes the published stock restore file from the public stock dump, the same way the toolkit does on a computer, so
 * no computer is needed. No firmware is shipped in the app: the owner downloads the public dump, and this class
 * accepts only that exact file (by SHA-256). The three withdrawn candidates C, A and B (docs/03-FLASH-CANDIDATES.md)
 * are still described here so that a copy picked from the phone is recognised and refused.
 *
 * The result is accepted only when its SHA-256 equals the published or reviewed file's, so this path can produce
 * nothing else. No Android imports: it runs on a plain JVM for the tests in android/test.
 */
public final class Candidates {

    /** The public stock dump: benbadio/keychain-camera-plus, dumps/charmera_stock_latest.bin (2 MiB). */
    public static final String STOCK_DUMP_SHA256 = "cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f";
    /** The other public dump of the same build: Khimira/Project-Sirius, Original_Firmware.bin. */
    public static final String SIRIUS_DUMP_SHA256 = "720ed0b65c91b149b0916498732b0ecf40227736ef5e3c126c487a7b5253436c";
    /** Every public dump: a copy of someone else's camera, never a backup of the owner's. */
    public static final String[] PUBLIC_DUMP_SHA256S = {STOCK_DUMP_SHA256, SIRIUS_DUMP_SHA256};
    /** Where it is downloaded from: the upstream repository at a fixed commit, so the address never changes. */
    public static final String STOCK_DUMP_DOWNLOAD = "https://raw.githubusercontent.com/benbadio/keychain-camera-plus/"
            + "209c25a47ce91c805b663edb6e18861b367a5a1b/dumps/charmera_stock_latest.bin";
    /** The same file's page, for downloading it in a browser. */
    public static final String STOCK_DUMP_PAGE = "https://github.com/benbadio/keychain-camera-plus/blob/"
            + "209c25a47ce91c805b663edb6e18861b367a5a1b/dumps/charmera_stock_latest.bin";
    static final int DUMP_SIZE = 0x200000;

    static final int SETTINGS_BASE = 0x170000;
    static final int SETTINGS_END = 0x17F000;
    static final int PHOTO_DIRECTORY = 0x17F000;
    static final int SECTOR = 0x200;
    static final int RECORD = 0x100;
    static final int CRC_LEN = 252;
    static final int OFF_UI_SOUND = 0x19;
    static final List<String> SOUND_SLOTS =
            Arrays.asList("BEEPWAV", "CLICKWAV", "IMGWAV", "POWEROFF_AUDIOWAV", "POWERON_AUDIOWAV");

    /**
     * The sensor-ID probe (Kind.PROBE): the stock restore file with a reviewed 330-byte patch in two windows, over the
     * restore file's own bytes and nothing else, both inside the 0x1E0000 image. PROBE_HOOK, at PROBE_HOOK_OFFSET,
     * redirects the one call that writes the image sensor's set-up table so the patch runs first. PROBE_STUB, at
     * PROBE_STUB_OFFSET, is that patch, written over a routine the firmware never calls: it walks the sensor bus across
     * every device address (0x08..0x77) and, for each that answers, reads its 16-bit chip-ID windows (0x3000..0x301F
     * and 0x3100..0x310F), writes them to C:\SENSOR.TXT on the card, then lets the set-up table be written and the camera
     * stop as before. It reuses the firmware's own bus read routine (device address and register width taken from the
     * driver context, the bytes it rewrites per read and restores before resuming). build() checks the result against Kind.PROBE's
     * SHA-256, so a wrong offset or byte here cannot produce a file the app will use.
     */
    static final int PROBE_HOOK_OFFSET = 0x7B898;
    static final int PROBE_STUB_OFFSET = 0x62120;
    static final byte[] PROBE_HOOK = hex("209affeb");
    static final byte[] PROBE_STUB = hex("f04f2de9e0d04de20040a0e11c519fe504808de2a700a0e30100c8e40100a0e30100c8e40090a0e308a0a0e1018088e20860" +
            "a0e38600a0e10900c5e50500a0e10010a0e30d20a0e1922effeb000050e31f0000ba040059e31d0000aa0160c8e41000a0e3" +
            "0700c5e50070a0e3ff00a0e30000cde50500a0e1031a87e30d20a0e1842effeb0000dde50100c8e4017087e2200057e3f4ff" +
            "ffba0070a0e3ff00a0e30000cde50500a0e1311c87e30d20a0e1782effeb0000dde50100c8e4017087e2100057e3f4ffffba" +
            "0800a0e30700c5e5019089e2016086e2780056e3d4ffffba0090cae57800a0e30900c5e50db048e004b04be23c009fe53410" +
            "9fe5660402eb000050e3050000ba0060a0e104108de20b20a0e11a0502eb0600a0e1040302ebe0d08de20400a0e1f04fbde8" +
            "8e6400eaa4831000020600005c200600433a5c53454e534f522e54585400");

    /**
     * The OV9732 bring-up (Kind.BRINGUP): the same patch shape as the sensor-ID probe — PROBE_HOOK at
     * PROBE_HOOK_OFFSET redirects the one call that writes the image sensor's set-up table, over a routine the
     * firmware never calls — but this stub drives the OmniVision OV9732 that is actually fitted instead of probing. It
     * points a 16-bit control context at bus 0x6C and walks a 1-lane MIPI RAW10 1280x720 start-up table (from the
     * Ingenic T31 ov9732.c driver) through the firmware's own sensor-write routine (VA 0x2d828), then returns so the
     * capture pipeline runs. Boot code, update code and the SP140A set-up table are the stock restore file's byte for
     * byte; only the one call and the dead routine change, so the stock restore file reverses it. build() checks the
     * result against Kind.BRINGUP's SHA-256, so a wrong offset or byte here cannot produce a file the app will use.
     */
    static final byte[] BRINGUP_STUB = hex("f0412de934509fe534809fe50010d8e5ff0051e30600000a0100d8e5011480e10220d8e50500a0e1362effeb038088e2" +
            "f5ffffea0000a0e3f041bde81eff2fe1681f0600721f06001100120000000010086c01030101000001000030071f3008" +
            "ff30142230813c3084003600f636017236100c3611f0361235365822365922365a0237001f37011037020c3703073704" +
            "3c37058137100c378258380f03380fce350131350a00350b103d82383d83a43d861f3d87034001e04006014007404800" +
            "0048050048028448193048253248260848271d48215048235048373750000f57080657810057830f01000137030b3705" +
            "51ff");

    /**
     * What the app can make. RESTORE is the published stock restore file: the stock dump's first 0x1E0000 bytes, under
     * its published name. PROBE is the sensor-ID probe: the stock restore file with a reviewed patch that reads the
     * image sensor's identifier and writes it to the card, so the owner can tell which sensor is fitted; it does not
     * fix the camera, and RESTORE puts it back. C, A and B are withdrawn and not offered: they are the same software
     * with other settings,
     * and when C was flashed on a camera that then froze in photo and video mode, the restore file froze it the same
     * way. The cause is most likely a firmware that does not fit that camera's image sensor, which no file made from
     * the public dump can fix; a copy of C, A or B picked from the phone is recognised and refused.
     */
    public enum Kind {
        RESTORE(true, "The stock restore file: the first part of the public dump, under its published name. It is a"
                + " copy of one working camera's memory, with that camera's settings; its two stored photos come along"
                + " unlisted. Several owners report working cameras after flashing it; what their cameras ran before is"
                + " not known. After it was flashed, one original-series camera could no longer take pictures; the"
                + " likely cause is a sensor it does not drive.",
                "JH_5408202610261427_0F065D55.bin", "b10a2f1c6302afbad8c205bd2f1e23c0ed244f6f3c553b513f2bd41f4b446906"),
        PROBE(true, "The sensor-ID probe: the stock restore file with a small reviewed patch. When the camera enters"
                + " photo mode it walks the sensor bus, and for each address that answers it reads the 16-bit chip-ID"
                + " registers (0x3000..0x301F and 0x3100..0x310F) and writes them to SENSOR.TXT at the root of the"
                + " card, then stops as the camera does now. It"
                + " does not fix the camera: it reports which image sensor is on the bus and at which address, so a"
                + " wrong-sensor mismatch can be told apart and the exact part named. Its boot"
                + " code, update code and image-sensor table are the stock restore file's byte for byte; only a dead"
                + " debug routine and the one call that writes the sensor set-up are changed. The stock restore file"
                + " (above) puts the camera back.",
                "JH_5408202611082300_0F065C4D.bin", "6a7aa6d66914805e741d7da3ae743a5250f0f1ef90622b3acc9da4bb75d2547b"),
        BRINGUP(true, "The OV9732 bring-up (experimental): the stock restore file with a reviewed patch, the same shape"
                + " as the sensor-ID probe, that drives the OmniVision OV9732 actually fitted instead of the SuperPix"
                + " SP140A the firmware was built for. It points the control bus at the OV9732's 16-bit address (bus"
                + " 0x6C) and writes a 1-lane MIPI start-up table for it through the firmware's own write routine, then"
                + " lets the camera run. It changes no boot or update code and leaves the SP140A table in place; only a"
                + " dead routine and the one sensor-setup call change, so the stock restore file (above) puts it back. It"
                + " may not take a clean picture on the first try — the sensor's MIPI timing has to match what the"
                + " camera's receiver expects — but it cannot damage the camera; the worst case is no picture, as"
                + " now.",
                "JH_5408202611090011_0F062D96.bin", "bf22fb31157661387233fdfa20c8467e2a0cc9274af7b47d048886d9119b1fcd"),
        C(false, "Candidate C (withdrawn: the same software as the stock restore file with factory settings; a camera"
                + " it was flashed on froze in photo and video mode, and so did the stock restore file).",
                "JH_5408202611050003_0FB74C24.bin", "9b8841d7b0674ca047a67b06594b92e67d17c387b6fab11f4b5191abc644c3ec"),
        A(false, "Candidate A (withdrawn: the same software as C with camera sounds off).",
                "JH_5408202611050001_0FB74D14.bin", "c4822cd7eed63169d80c5105811ab6492444793fafcc7a96e809869f2e5ccda7"),
        B(false, "Candidate B (withdrawn: the same software as A with silenced sound data).",
                "JH_5408202611050002_0FB0BD3C.bin", "32a7f54e5090050d099681c33378e3d23822a736279ea78f9f3442a45edc3979");

        /** Whether the app offers it. */
        public final boolean offered;
        /** What the file is, in plain words, for the owner. */
        public final String label;
        public final String fileName;
        public final String sha256;

        Kind(boolean offered, String label, String fileName, String sha256) {
            this.offered = offered;
            this.label = label;
            this.fileName = fileName;
            this.sha256 = sha256;
        }

        /** The kinds the app offers, in order. */
        public static Kind[] offeredKinds() {
            int n = 0;
            for (Kind k : values()) {
                if (k.offered) {
                    n++;
                }
            }
            Kind[] out = new Kind[n];
            int i = 0;
            for (Kind k : values()) {
                if (k.offered) {
                    out[i++] = k;
                }
            }
            return out;
        }
    }

    private Candidates() {
    }

    /**
     * The 2 MiB stock dump from what the owner picked: the public dump itself, or the published stock restore file
     * (its first 0x1E0000 bytes, which this pads with the blank 0xFF tail). Throws otherwise, with a message that
     * says the cause and the next step in plain words: it starts in lower case so the screen can show it after a
     * colon, and any SHA-256 is on a last line that starts with "Detail:". Whether another 2 MiB file could be this
     * camera's backup is UpdateCheck.backupProblem's to say, not this message's.
     */
    public static byte[] stockDump(byte[] picked) {
        byte[] dump;
        if (picked.length == DUMP_SIZE) {
            dump = picked;
        } else if (picked.length == UpdateCheck.UPDATE_SIZE) {
            dump = Arrays.copyOf(picked, DUMP_SIZE);
            Arrays.fill(dump, UpdateCheck.UPDATE_SIZE, DUMP_SIZE, (byte) 0xFF);
        } else {
            throw new IllegalArgumentException("the file is " + UpdateCheck.bytes(picked.length) + ", but the stock dump"
                    + " is " + UpdateCheck.bytes(DUMP_SIZE) + ". A shorter file is usually a cut-off download."
                    + " Download it again.");
        }
        String sha = UpdateCheck.sha256Hex(dump, 0, dump.length);
        if (!sha.equals(STOCK_DUMP_SHA256)) {
            throw new IllegalArgumentException("the file is not the public stock dump, so the app will not use it. The"
                    + " app makes the stock restore file only from that exact file, charmera_stock_latest.bin,"
                    + " unchanged.\nDetail: its SHA-256 is " + sha + "; the public stock dump's is " + STOCK_DUMP_SHA256
                    + ".");
        }
        return dump;
    }

    /** True when the bytes are the public stock dump, or the published restore file made from it. */
    public static boolean isStockDump(byte[] picked) {
        try {
            stockDump(picked);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The candidate's bytes, accepted only when they hash to the reviewed file. Throws otherwise, with a message in
     * the same form as stockDump's.
     */
    public static byte[] build(byte[] stockDump, Kind kind) {
        byte[] out = transform(stockDump, kind);
        String sha = UpdateCheck.sha256Hex(out, 0, out.length);
        if (!sha.equals(kind.sha256)) {
            String what = kind == Kind.RESTORE ? "the published stock restore file" : "candidate " + kind.name();
            throw new IllegalStateException("the file made from the stock dump is not exactly " + what + ", so the app"
                    + " will not use it. This should not happen. Please report it.\nDetail: its SHA-256 is " + sha
                    + "; " + what + " has " + kind.sha256 + ".");
        }
        return out;
    }

    /** Whether these exact bytes are the reviewed sensor-ID probe, by SHA-256: a file written without a backup. */
    public static boolean isProbe(byte[] data) {
        return Kind.PROBE.sha256.equals(UpdateCheck.sha256Hex(data, 0, data.length));
    }

    /**
     * Whether these exact bytes are one of the reviewed patches the app writes without a backup — the sensor-ID probe
     * or the OV9732 bring-up — by SHA-256. Both are built from the public build and reversed by the stock restore
     * file: boot code, update code and the SP140A set-up table are the restore file's byte for byte, and only a dead
     * routine and the one sensor-setup call are patched. So, like the probe, the bring-up is checked with no backup.
     */
    public static boolean isReviewedPatch(byte[] data) {
        return isReviewedPatch(UpdateCheck.sha256Hex(data, 0, data.length));
    }

    /** As isReviewedPatch(byte[]), by the SHA-256 of the bytes that passed the check (for WriteGate). */
    public static boolean isReviewedPatch(String sha256) {
        return Kind.PROBE.sha256.equals(sha256) || Kind.BRINGUP.sha256.equals(sha256);
    }

    /**
     * UpdateCheck.check against the public build, with the one rule every file-entry path in the app shares: the
     * reviewed patches (the sensor-ID probe and the OV9732 bring-up) are checked with no reference, whatever backup is
     * passed. They are backup-independent (WriteGate's exemption), so no path may compare one with a backup it was
     * never meant to match — that would fail its sensor-table line and refuse a file that needs no backup (finding 7).
     * Every other file is checked against the reference given. The extra hash runs only when a reference is present
     * (the common no-backup case is free).
     */
    public static UpdateCheck.Result check(String name, byte[] data, byte[] reference) {
        byte[] ref = reference != null && isReviewedPatch(data) ? null : reference;
        return UpdateCheck.check(name, data, UpdateCheck.PUBLIC_BUILD, ref);
    }

    /** The recipe itself, without the final hash check (the tests run it on synthetic images). */
    static byte[] transform(byte[] dump, Kind kind) {
        if (dump.length != DUMP_SIZE) {
            throw new IllegalArgumentException("the stock dump must be " + UpdateCheck.bytes(DUMP_SIZE) + ".");
        }
        if (kind == Kind.RESTORE) {
            return Arrays.copyOf(dump, UpdateCheck.UPDATE_SIZE);   // the published restore file is exactly this
        }
        if (kind == Kind.PROBE) {
            byte[] payload = Arrays.copyOf(dump, UpdateCheck.UPDATE_SIZE);   // the restore file, with the probe patch
            System.arraycopy(PROBE_HOOK, 0, payload, PROBE_HOOK_OFFSET, PROBE_HOOK.length);
            System.arraycopy(PROBE_STUB, 0, payload, PROBE_STUB_OFFSET, PROBE_STUB.length);
            return payload;
        }
        if (kind == Kind.BRINGUP) {
            byte[] payload = Arrays.copyOf(dump, UpdateCheck.UPDATE_SIZE);   // the restore file, with the bring-up patch
            System.arraycopy(PROBE_HOOK, 0, payload, PROBE_HOOK_OFFSET, PROBE_HOOK.length);   // same hook as the probe
            System.arraycopy(BRINGUP_STUB, 0, payload, PROBE_STUB_OFFSET, BRINGUP_STUB.length);
            return payload;
        }
        byte[] image = dump.clone();
        int first = firstValidRecord(image);
        if (first < 0) {
            throw new IllegalArgumentException("the stock dump has no undamaged settings record.");
        }
        byte[] record = Arrays.copyOfRange(image, first, first + RECORD);
        if (kind == Kind.A || kind == Kind.B) {
            record = withByte(record, OFF_UI_SOUND, 0);       // sounds mute --method settings --base-record first
        }
        rewriteStore(image, record);                          // also what pack --factory-settings does for C
        if (kind == Kind.B) {
            mutePcm(image);                                   // sounds mute --method pcm
        }
        byte[] payload = Arrays.copyOf(image, UpdateCheck.UPDATE_SIZE);   // pack trims the blank tail
        blankPhotos(payload);                                 // pack --blank-photos
        return payload;
    }

    /** The first non-erased sector of the bank whose 252-byte body matches its CRC, or -1. */
    static int firstValidRecord(byte[] image) {
        for (int off = SETTINGS_BASE; off < SETTINGS_END; off += SECTOR) {
            if (!erased(image, off) && crcOk(image, off)) {
                return off;
            }
        }
        return -1;
    }

    /** The bank holds only this record, at sector 0 with a zero second page; an erased directory is zeroed. */
    static void rewriteStore(byte[] image, byte[] record) {
        Arrays.fill(image, SETTINGS_BASE, SETTINGS_END, (byte) 0xFF);
        System.arraycopy(record, 0, image, SETTINGS_BASE, RECORD);
        Arrays.fill(image, SETTINGS_BASE + RECORD, SETTINGS_BASE + SECTOR, (byte) 0);
        if (erased(image, PHOTO_DIRECTORY)) {
            Arrays.fill(image, PHOTO_DIRECTORY, PHOTO_DIRECTORY + SECTOR, (byte) 0);
        }
    }

    /** A zeroed photo directory (what the camera's own "delete all" leaves) and erased photo slots. */
    static void blankPhotos(byte[] payload) {
        Arrays.fill(payload, PHOTO_DIRECTORY, PHOTO_DIRECTORY + SECTOR, (byte) 0);
        Arrays.fill(payload, PHOTO_DIRECTORY + SECTOR, UpdateCheck.UPDATE_SIZE, (byte) 0xFF);
    }

    /** Silence the PCM data of the five sound slots (0x80 for 8-bit, 0x00 for 16-bit); headers untouched. */
    static void mutePcm(byte[] image) {
        for (UpdateCheck.Entry e : UpdateCheck.resourceDirectory(image)) {
            if (!SOUND_SLOTS.contains(e.name)) {
                continue;
            }
            int off = (int) e.offset;
            if (!UpdateCheck.regionEquals(image, off, new byte[]{'R', 'I', 'F', 'F'})) {
                throw new IllegalArgumentException("the sound " + e.displayName() + " in the stock dump is not a WAV"
                        + " file.");
            }
            long end = off + 8L + u32(image, off + 4);
            int bits = -1;
            int p = off + 12;
            while (p + 8 <= end && p + 8 <= image.length) {
                long size = u32(image, p + 4);
                if (image[p] == 'f' && image[p + 1] == 'm' && image[p + 2] == 't' && image[p + 3] == ' ') {
                    bits = (image[p + 8 + 14] & 0xFF) | (image[p + 8 + 15] & 0xFF) << 8;
                } else if (image[p] == 'd' && image[p + 1] == 'a' && image[p + 2] == 't' && image[p + 3] == 'a') {
                    if (bits < 0 || p + 8 + size > image.length) {
                        throw new IllegalArgumentException("the sound " + e.displayName() + " in the stock dump has"
                                + " no format part before its sound data.");
                    }
                    Arrays.fill(image, p + 8, (int) (p + 8 + size), (byte) (bits == 8 ? 0x80 : 0x00));
                    break;
                }
                p += 8 + (int) size + (int) (size & 1);
            }
        }
    }

    static byte[] withByte(byte[] record, int offset, int value) {
        byte[] r = record.clone();
        r[offset] = (byte) value;
        CRC32 crc = new CRC32();
        crc.update(r, 0, CRC_LEN);
        long v = crc.getValue();
        for (int i = 0; i < 4; i++) {
            r[CRC_LEN + i] = (byte) (v >>> (8 * i));
        }
        return r;
    }

    static boolean erased(byte[] image, int off) {
        for (int i = 0; i < 8; i++) {
            if (off + i >= image.length) {
                return true;
            }
            if ((image[off + i] & 0xFF) != 0xFF) {
                return false;
            }
        }
        return true;
    }

    static boolean crcOk(byte[] image, int off) {
        CRC32 crc = new CRC32();
        crc.update(image, off, CRC_LEN);
        return crc.getValue() == u32(image, off + CRC_LEN);
    }

    static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16 | (b[off + 3] & 0xFFL) << 24;
    }

    /** Bytes from a string of two hex digits each, for the reviewed probe patch above. */
    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
