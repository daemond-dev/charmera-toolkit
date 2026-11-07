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
     * What the app can make. RESTORE is the published stock restore file: the stock dump's first 0x1E0000 bytes, under
     * its published name. C, A and B are withdrawn and not offered: they are the same software with other settings,
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
     * colon, and any SHA-256 is on a last line that starts with "Detail:".
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
                    + " unchanged. A backup of your own camera is chosen with \"Choose this camera's backup\" in step 0"
                    + " instead, and charmera pack on a computer makes an update file from it.\nDetail: its SHA-256"
                    + " is " + sha + "; the public stock dump's is " + STOCK_DUMP_SHA256 + ".");
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

    /** The recipe itself, without the final hash check (the tests run it on synthetic images). */
    static byte[] transform(byte[] dump, Kind kind) {
        if (dump.length != DUMP_SIZE) {
            throw new IllegalArgumentException("the stock dump must be " + UpdateCheck.bytes(DUMP_SIZE) + ".");
        }
        if (kind == Kind.RESTORE) {
            return Arrays.copyOf(dump, UpdateCheck.UPDATE_SIZE);   // the published restore file is exactly this
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
}
