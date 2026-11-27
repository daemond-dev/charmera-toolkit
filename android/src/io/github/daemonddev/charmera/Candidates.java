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
    /**
     * The public dump's cleaned copy (the-dumping-grounds/charmera-firmware-v006, charmera-v006-2025-10-14-clean.bin):
     * the same bytes as the public dump up to 0x170000, with only its factory settings record kept and its stored
     * photos erased. The app downloads this one. Every stock kind made from it is the same code as the one made from
     * the public dump; only the settings bank and photo area differ (Kind.cleanSha256).
     */
    public static final String CLEAN_STOCK_SHA256 = "75b911e9fc62cb3e4b461501916d1f1c336a28587296f03eba1f9d90cbc01082";
    /** Every public dump: a copy of someone else's camera, never a backup of the owner's. */
    public static final String[] PUBLIC_DUMP_SHA256S = {STOCK_DUMP_SHA256, SIRIUS_DUMP_SHA256, CLEAN_STOCK_SHA256,
            "63e396846f949600c2c1881920860821f2fcd70a70ac79ca54eebaee4a0ccb1c"};   // the last: CLEAN_FACTORY_SHA256
    /** The repository the cleaned copies are published in, at the commit each was published with. */
    static final String V006_COMMIT = "123b76a8ead1322678bbb750ac1c85c8d8bd2099";
    static final String V012_COMMIT = "39150fbc584e4cb7c5bf091f56b9888f07379578";
    /** Where it is downloaded from: its repository at a fixed commit, so the address never changes. */
    public static final String STOCK_DUMP_DOWNLOAD = "https://raw.githubusercontent.com/the-dumping-grounds/"
            + "charmera-firmware-v006/" + V006_COMMIT + "/charmera-v006-2025-10-14-clean.bin";
    /** The same file's page, for downloading it in a browser. */
    public static final String STOCK_DUMP_PAGE = "https://github.com/the-dumping-grounds/charmera-firmware-v006/blob/"
            + V006_COMMIT + "/charmera-v006-2025-10-14-clean.bin";
    static final int DUMP_SIZE = 0x200000;
    /**
     * The owner's own camera, read off its flash chip on 2026-11-13: a factory build (v012, Jan 12 2026) that drives
     * the OV9732 natively. The owner picks it on the phone, and the app keeps a copy (MainActivity, factory-dump.bin).
     * The factory kinds (Base.FACTORY) are made from it or from its cleaned copy (CLEAN_FACTORY_SHA256), by SHA-256.
     */
    public static final String FACTORY_DUMP_SHA256 = "08a7d993996b93cf2468fe08d70a0e77d5e1a9d6ff16bf647f5c7a0edf5d85ce";
    /**
     * The factory dump's cleaned, public copy (the-dumping-grounds/charmera-firmware-v012,
     * charmera-v012-2026-01-12-clean.bin): the same bytes up to 0x170000, only the factory settings record kept and the
     * stored photos erased. Any owner of an OV9732 camera can pick it.
     */
    public static final String CLEAN_FACTORY_SHA256 = "63e396846f949600c2c1881920860821f2fcd70a70ac79ca54eebaee4a0ccb1c";
    /** Its file name as the owner saved it, for the one message that asks for it. */
    public static final String FACTORY_DUMP_NAME = "charmera_flash_20261113T193627Z.bin";
    /** The cleaned copy's file name and page, for the message that asks for a factory dump. */
    public static final String CLEAN_FACTORY_NAME = "charmera-v012-2026-01-12-clean.bin";
    public static final String CLEAN_FACTORY_PAGE = "https://github.com/the-dumping-grounds/charmera-firmware-v012/blob/"
            + V012_COMMIT + "/charmera-v012-2026-01-12-clean.bin";
    /** Where the app downloads it from when the phone has no factory dump: its repository at a fixed commit. */
    public static final String CLEAN_FACTORY_DOWNLOAD = "https://raw.githubusercontent.com/the-dumping-grounds/"
            + "charmera-firmware-v012/" + V012_COMMIT + "/charmera-v012-2026-01-12-clean.bin";
    /** The factory dump's internal photo slots, blanked to 0xFF so no camera receives the two photos stored in it. */
    public static final int FACTORY_PHOTOS_START = 0x180000;
    public static final int FACTORY_PHOTOS_END = 0x1CB000;

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
     * byte; this stub changes only the one call and the dead routine (AE_HOOK, TUNE_OFFSETS and ROWS_OFFSETS list the
     * bring-up's other changes), and the stock restore file's bytes undo all of them (that file does not drive the
     * OV9732, so on this camera it is no way back to working photos; see Kind.BRINGUP's label). The table sets the
     * OV9732 black-level target to 64 (0x4002/0x4003, v0.22) to match the 64 the GP ISP subtracts (profile 0xF7118 ->
     * [0xD0800018] = 0x8040); the sensor's default of 16 crushed the bottom 48 codes of every frame. build() checks the
     * result against Kind.BRINGUP's SHA-256, so a wrong offset or byte here cannot produce a file the app will use.
     */
    static final byte[] BRINGUP_STUB = hex("f0412de934509fe534809fe50010d8e5ff0051e30600000a0100d8e5011480e10220d8e50500a0e1362effeb038088e2" +
            "f5ffffea0000a0e3f041bde81eff2fe1681f0600721f06001100120000000010086c01030101000001000030071f3008" +
            "ff30142230813c3084003600f636017236100c3611f0361235365822365922365a0237001f37011037020c3703073704" +
            "3c37058137100c378258380f03380fce350131350a00350b203d82383d83a43d861f3d87034001e04002004003404006014007404800" +
            "0048050048028448193048253248260848271d48215048235048373750000f57080657810057830f38201c5180015181" +
            "0051820151830051840151855001000137030b370551ff");

    /**
     * The OV9732 adaptive-exposure redirect, applied on top of the bring-up stub (Kind.BRINGUP only). The stock
     * firmware runs a per-frame auto-exposure loop that computes integration and gain each frame and writes them to
     * the SP140A registers that are dead on the OV9732; AE_HOOK, at AE_HOOK_OFFSET (the updater's write tail, VA
     * 0x7b378), diverts that write to AE_STUB, at AE_STUB_OFFSET (a routine the firmware never calls, VA 0x62088).
     * AE_STUB reads the firmware's own computed integration/gain and re-emits them as OV9732 manual exposure
     * (0x3501/0x3502) and gain (0x350a/0x350b) through the firmware's own sensor-write routine (VA 0x2d828), then
     * returns to the updater's epilogue, so exposure tracks the scene instead of staying fixed. Reviewed against the
     * disassembly; build() checks the result's SHA-256, so a wrong offset or byte cannot produce a file the app uses.
     * Integration is clamped to [1, 0x3c4] lines (v0.22): the stock program line (VA 0xf5ae8) tops out at 962 lines and
     * the OV9732 allows VTS-4 = 970 (VTS 0x3ce from the init). v0.21's 0x310 (784, the old static init exposure)
     * truncated the table's top 57 rows and made exposure fall from row 156 to 157.
     */
    static final int AE_HOOK_OFFSET = 0x7B578;
    static final int AE_STUB_OFFSET = 0x62288;
    static final byte[] AE_HOOK = hex("429bffea");
    static final byte[] AE_STUB = hex("080094e5010050e30100a0b3f11fa0e3010050e10100a0c10c3094e54332a0e1100053e31030a0b3f010a0e3010053e10130a0c1" +
            "0040a0e10350a0e15c609fe50600a0e1353ca0e3011083e32422a0e1ff2002e2d12dffeb0600a0e1353ca0e3021083e30422a0e1ff20" +
            "02e2cb2dffeb0600a0e1353ca0e30a1083e32524a0e1032002e2c52dffeb0600a0e1353ca0e30b1083e3ff2005e2c02dffebc16400ea" +
            "2c2106001100120000000010086c");

    /**
     * v0.23 image tuning, fitted to colour-card shots of this camera against the stock camera (same scene, daylight and
     * warm indoor light; docs/15 section 9). Each entry is {file offset, replacement bytes}; build() checks the result's
     * SHA-256, so a wrong offset or byte cannot produce a file the app will use.
     * - Colour matrix (profile +0x28, VA 0xF7582): 9 x s16, 64 = 1.0, rows kept summing to 64 so greys stay grey in
     *   every light. Daylight colour error vs stock 13.5 -> 6.5 dE; indoor (not fitted) 10.1 -> 7.7.
     * - Per-band AE target table (VA 0xF7110, copied to RAM by 0x66BF8): enabled, -18 in the brightest band only
     *   (target 120 -> 102 = -0.40 EV through the 0xF7138 metering curve); dimmer bands 0, so dim scenes are unchanged.
     * - The set-target (msg 0x3000001D, VA 0x69760) and EV (msg 0x30000020, VA 0x6981C) handlers call the band-aware
     *   setter 0x66C28 instead of 0x93498, so the offset survives power-on and EV messages. The table and these two
     *   handler changes only work together: without the enabled table, 0x66C28 returns early and no target is set.
     */
    static final int[] TUNE_OFFSETS = {0xF7782, 0xF7310, 0x69960, 0x69A1C};
    static final byte[][] TUNE_BYTES = {
            hex("9000caffe6ffd5ff9200d9fff8ffc3ff8500"),
            hex("0112000000000000"),
            hex("b010c8e1540e90e5020080e22df5ffeb"),
            hex("540e90e5020080e2fff4ffeb")};

    /**
     * v0.24 settings rows, on top of v0.23 (docs/15 section 10). Two rows after FORMAT in the setup menu: "Volume"
     * (the SDK's own Volume item, record +0x19, list 0..8) and "LED" (Off / On; record +0x60, where 0 = On = the stock
     * automatic low-light flash). The page drawer, cursor arrow and item handler are patched so page 2 draws FORMAT,
     * Volume and LED; page 1 draws as before. The two shutter calls that light the flash in dim light go through a
     * check of +0x60; the five calls that switch it off are untouched. New code sits in the dead ssv_wifi block at
     * VA 0x62184 (nothing outside that block branches or points into 0x62184..0x62220). Each entry is
     * {file offset, replacement bytes}; build() checks the result's SHA-256. Built and checked by
     * build_v024.py (expected-original-bytes checks, keystone/capstone, Unicorn runs of the new code) and reviewed.
     */
    static final int[] ROWS_OFFSETS = {0xFFDF9, 0x17010, 0x17034, 0x176C0, 0x176D8, 0x17774, 0x17834, 0x17958, 0x179FC, 0x17B64, 0x195C0, 0x19888, 0x1E0F8, 0x1E154, 0x62384};
    static final byte[][] ROWS_BYTES = {
            hex("3a3f"),   // setup list: append Volume and LED
            hex("0910a0e3"),   // setup list count (home path)
            hex("0910a0e3"),   // setup list count (photo path)
            hex("000057e3b400c411"),   // page 2+: first row at x 0x12
            hex("000057e30200001a"),   // scroll bar follows the page
            hex("02a047e2"),   // date-format red only on absolute rows 2..4
            hex("010057e33800008a"),   // camera/video icons only on absolute rows 0..1
            hex("8d2a013b"),   // second row x: 0x28 on page 1, 0x12 after
            hex("060057e31500008a"),   // footer follows the page (SET DATE / OK)
            hex("062a01eb"),   // cursor arrow row wraps per page
            hex("7d23010a"),   // LED row handler
            hex("0900a003"),   // Volume list: 0..8
            hex("aa10011b"),   // flash LED on (shot path 1) gated by LED setting
            hex("9310011b"),   // flash LED on (shot path 2) gated by LED setting
            hex("0020d4e5060052e3062042220ef0a0e1060057e32800a0331200a023010056e30ef0a0e16cc09fe560c0dce500005ce3" +
                    "0e3bff0a0ef0a0e10b00c9e554109fe56060d1e5000056e30160a0030060a013000054e3430086020cdefe0a010054e3" +
                    "0200a0030300c7054300a0030ddefe0a020054e32edffe1a0000d5e5010020e26020d1e5000052e16000c1150100a013" +
                    "fa00c11531dffeeadc071100"),   // new code: arrow wrap, row x, LED gate, LED handler case
    };

    /**
     * v0.25 = the factory dump's first 0x1E0000 bytes, photo slots blanked, plus v0.24's Volume and LED rows relocated
     * to the v012 build (build_v025.py: setup-menu code +0x18, LED-on routine 0x30E00, dead ssv_wifi block at VA
     * 0x62190, setup list VA 0xFFCE2, settings record RAM 0x11059C). Each entry is {file offset, bytes}: exactly the
     * bytes that differ from the factory dump, every one of them authored (new ARM code and data), so no vendor byte is
     * in this source. 172 bytes in 36 runs; build() checks the result's SHA-256 against Kind.V025.
     */
    static final int[] V025_OFFSETS = {0x17028, 0x1704C, 0x176D8, 0x176DA, 0x176DF, 0x176F0, 0x176F2, 0x176F7, 0x1778E, 0x1784E, 0x17853, 0x17970, 0x17A14, 0x17A16, 0x17A1B, 0x17B7C, 0x195D8, 0x198A0, 0x1E10C, 0x1E168, 0x62390, 0x623A7, 0x623AB, 0x623B0, 0x623BE, 0x623CA, 0x623D6, 0x623DB, 0x623E2, 0x623E4, 0x623E6, 0x623EE, 0x623FB, 0x6241A, 0x6241F, 0xFFEE9};
    static final byte[][] V025_BYTES = {
            hex("09"),                      // item count 9 (home path)
            hex("09"),                      // item count 9 (photo path)
            hex("00"),                      // page 2+: first row at x 0x12
            hex("57"),                      // page 2+: first row at x 0x12
            hex("11"),                      // page 2+: first row at x 0x12
            hex("00"),                      // scroll bar follows the page
            hex("57"),                      // scroll bar follows the page
            hex("1a"),                      // scroll bar follows the page
            hex("47"),                      // date-format red on rows 2..4 only
            hex("57"),                      // camera/video icons on rows 0..1 only
            hex("8a"),                      // camera/video icons on rows 0..1 only
            hex("8a2a013b"),                // second row x per page
            hex("06"),                      // footer follows the page
            hex("57"),                      // footer follows the page
            hex("8a"),                      // footer follows the page
            hex("032a01eb"),                // cursor arrow wraps per page
            hex("7a2301"),                  // LED row handler
            hex("09"),                      // Volume list 0..8
            hex("a81001"),                  // shutter LED-on call 1 gated
            hex("911001"),                  // shutter LED-on call 2 gated
            hex("0020d4e5060052e3062042220ef0a0e1060057e328"), // new code in the dead ssv_wifi block
            hex("331200"),                  // new code in the dead ssv_wifi block
            hex("23010056"),                // new code in the dead ssv_wifi block
            hex("0ef0a0e16cc09fe560c0dce5"), // new code in the dead ssv_wifi block
            hex("5ce30e3bff0a0ef0a0e10b"),  // new code in the dead ssv_wifi block
            hex("c9e554109fe56060d1e500"),  // new code in the dead ssv_wifi block
            hex("56e30160"),                // new code in the dead ssv_wifi block
            hex("030060a013"),              // new code in the dead ssv_wifi block
            hex("54"),                      // new code in the dead ssv_wifi block
            hex("43"),                      // new code in the dead ssv_wifi block
            hex("86020fdefe0a01"),          // new code in the dead ssv_wifi block
            hex("54e30200a0030300c70543"),  // new code in the dead ssv_wifi block
            hex("0310defe0a020054e331dffe1a0000d5e5010020e26020d1e5000052e160"), // new code in the dead ssv_wifi block
            hex("c1150100"),                // new code in the dead ssv_wifi block
            hex("13fa00c11534dffeea9c051100"), // new code in the dead ssv_wifi block
            hex("3a3f"),                    // setup list: Volume and LED
    };

    /**
     * v0.26 = v0.25 plus an EXIF block in every still photo, on the SD card and in internal memory (build_v026.py). The
     * still header grows from the bare 0x270 bytes to 0x340: SOI, an authored APP1 'Exif' block of 0xD0 bytes (Make
     * Kodak, Model Charmera, Software v0.26, DateTime, DateTimeOriginal, DateTimeDigitized), then the v012 template's own
     * segments from SOF0 on. A new date-fill routine, called once per shot before the card check, writes the camera
     * clock's date into the three dates (an unset clock gives 2025:01:01). The new template and routine go over a dead
     * MMIO driver at file 0x89640..0x899F4; the header builder, the DQT setter, the DCIM write and the internal-flash
     * slot writer are pointed at it.
     *
     * V026_OFFSETS/V026_BYTES are exactly the bytes v0.26 changes in v0.25 outside the copied range, every one of them
     * authored (instruction encodings, the template pointer, the APP1 block, the new code): 332 bytes in 45 runs.
     * V026_COPIES is the vendor part of the new template: the v012 still template from SOF0 on (0x26E bytes, file
     * 0x10753A, identical in the factory dump and in v0.25), copied from the factory dump at run time, so no vendor
     * byte is in this source. build() checks the result's SHA-256 against Kind.V026.
     */
    static final int[][] V026_COPIES = {{0x10753A, 0x89712, 0x26E}};
    static final int[] V026_OFFSETS = {0x9068, 0x90A0, 0x90A4, 0x9164, 0x91BC, 0x6D028, 0x6D030, 0x6D6C8, 0x6E29C, 0x6E2A4, 0x6E2B0, 0x6E2BC, 0x6E2D0, 0x6E2D4, 0x6E2D8, 0x6E388, 0x6E3AC, 0x6F3CC, 0x89640, 0x89644, 0x89661, 0x8966D, 0x89672, 0x8967A, 0x89682, 0x8968E, 0x896A5, 0x896B6, 0x896C2, 0x896C6, 0x896DA, 0x896E2, 0x896EA, 0x896FE, 0x89980, 0x8998A, 0x89992, 0x8999E, 0x899AC, 0x899BE, 0x899C0, 0x899C4, 0x899CD, 0x899E2, 0x899E4};
    static final byte[][] V026_BYTES = {
            hex("0d0d"),                    // slot directory length = JPEG + header
            hex("03"),                      // slot: full header pages
            hex("40"),                      // slot: header bytes in the shared page
            hex("010b"),                    // slot: JPEG rest offset (full pages)
            hex("010b"),                    // slot: JPEG rest offset (last page)
            hex("f7"),                      // DQT 0 values
            hex("4f3f"),                    // DQT 1 values
            hex("409408"),                  // still template literal (DQT setter, header builder)
            hex("d7"),                      // SOF height hi
            hex("d8"),                      // SOF height lo
            hex("d9"),                      // SOF width hi
            hex("da"),                      // SOF width lo
            hex("dd"),                      // SOF Y sampling
            hex("8081"),                    // DRI interval hi
            hex("8181"),                    // DRI interval lo
            hex("7c6d00"),                  // card check -> date fill, then card check
            hex("0d2d"),                    // DCIM header write size
            hex("409408"),                  // still template literal (internal-flash save)
            hex("ffd8ff"),                  // SOI + APP1 Exif block (authored)
            hex("00ce45786966000049492a000800000005000f010200060000004a00"), // SOI + APP1 Exif block (authored)
            hex("0010010200090000005000"),  // SOI + APP1 Exif block (authored)
            hex("00310102"),                // SOI + APP1 Exif block (authored)
            hex("060000005a0000"),          // SOI + APP1 Exif block (authored)
            hex("32010200140000"),          // SOI + APP1 Exif block (authored)
            hex("6000000069870400010000"),  // SOI + APP1 Exif block (authored)
            hex("74000000000000004b6f64616b00436861726d657261"), // SOI + APP1 Exif block (authored)
            hex("0076302e323600202020203a20203a20"), // SOI + APP1 Exif block (authored)
            hex("2020203a20203a20200003"),  // SOI + APP1 Exif block (authored)
            hex("009007"),                  // SOI + APP1 Exif block (authored)
            hex("040000003032333203900200140000009e0000"), // SOI + APP1 Exif block (authored)
            hex("04900200140000"),          // SOI + APP1 Exif block (authored)
            hex("b2000000000000"),          // SOI + APP1 Exif block (authored)
            hex("202020203a20203a20202020203a20203a2020"), // SOI + APP1 Exif block (authored)
            hex("202020203a20203a20202020203a20203a202000"), // SOI + APP1 Exif block (authored)
            hex("10402de930d04de214"),      // new code: date fill
            hex("8de210dcfdeb24"),          // new code: date fill
            hex("9de528109de52c209de507"),  // new code: date fill
            hex("8de86c00a0e30c008de510608d"), // new code: date fill
            hex("14008de20f0090e82b14feeb6c0086e220"), // new code: date fill
            hex("a0"),                      // new code: date fill
            hex("0a10c0"),                  // new code: date fill
            hex("aa1086e2be2086e2"),        // new code: date fill
            hex("30a0e303c0d0e703c0c1e703c0c2e7013083e213"), // new code: date fill
            hex("53"),                      // new code: date fill
            hex("f9ffff3a30d08de21040bde8aa61feea"), // new code: date fill
    };

    /**
     * v0.27 = v0.26 plus two mods (build-v027/build_v027.py, each checked in Unicorn and reviewed independently):
     * - Boot to the last camera mode: the one place the first UI state is chosen after startup (VA 0x6384, after the SD
     *   update check) goes to Picture, or to Video when settings byte +0x61 is 1; the Picture and Video entries
     *   (VA 0x1D59C, 0x1C4C4) store the mode there. Home stays a short power press away. New code at VA 0x62230.
     * - Capture flash: while a photo is taken the preview is drawn black under the HUD for 120 ms of the stock 200 ms
     *   shutter delay, so a shot shows at Volume 0 too (VA 0x161A8, 0x1DF70, 0x1DF80; new code at VA 0x89800).
     * Each entry is {file offset, bytes}: exactly the bytes that differ from v0.26, every one authored (new ARM code,
     * literals and branch words). 202 bytes in 24 runs.
     */
    static final int[] V027_OFFSETS = {0x6584, 0x163A8, 0x1C6C4, 0x1D79C, 0x1E170, 0x1E180, 0x62430, 0x62438, 0x62444, 0x62448, 0x6245E, 0x62462, 0x62487, 0x89A00, 0x89A03, 0x89A09, 0x89A19, 0x89A1C, 0x89A36, 0x89A38, 0x89A3C, 0x89A55, 0x89A58, 0x89A5E};
    static final byte[][] V027_BYTES = {
            hex("a96f01eb"),
            hex("95cd01"),
            hex("621701"),
            hex("2c1301"),
            hex("2eae01"),
            hex("2eae"),
            hex("01402de94dc7fe"),
            hex("0140bde838109fe56110d1"),
            hex("010051"),
            hex("34109f052c109f150ef0a0e120109fe5012c40e201"),
            hex("52e300"),
            hex("a0136130d1e5020053e16120c1150130a013fa30c11586aafeea9c051100020100000101"),
            hex("00"),
            hex("00"),
            hex("0020c09fe5"),
            hex("c0dce500005ce3fd2ffe0a0f402de9"),
            hex("10"),
            hex("192ba0e321dbfdeb0f40bde8f72ffeea0098080038c09fe501"),
            hex("a0"),
            hex("0010cc"),
            hex("0354feea10402de90040a0e10c00a0e3511b00eb18c09f"),
            hex("00"),
            hex("0000cce50c"),
            hex("54e20000a0434b1b00eb1080bde80000a0e100980800")};

    /**
     * One step of a factory kind's recipe, applied on top of the factory dump (and of every earlier layer): first the
     * copies, then the patches. copies are {from, to, length}: bytes the app must not ship (the vendor's own code or
     * data) taken at run time from the factory dump at from and written to the output at to. patches are
     * {file offset, bytes}, every byte authored by us. A later firmware (v0.26) is v0.25's layer plus a layer of its own.
     */
    static final class Layer {
        final int[][] copies;
        final int[] offsets;
        final byte[][] bytes;

        Layer(int[][] copies, int[] offsets, byte[][] bytes) {
            if (offsets.length != bytes.length) {
                throw new IllegalArgumentException("a patch table needs one offset per entry");
            }
            this.copies = copies;
            this.offsets = offsets;
            this.bytes = bytes;
        }
    }

    /** v0.25: the Volume and LED rows; nothing copied. */
    static final Layer V025_LAYER = new Layer(new int[0][], V025_OFFSETS, V025_BYTES);

    /** v0.26: the EXIF still header and its date fill, on top of v0.25's layer; the template's vendor part copied. */
    static final Layer V026_LAYER = new Layer(V026_COPIES, V026_OFFSETS, V026_BYTES);

    /** v0.27: boot to the last camera mode and the capture flash, on top of v0.26's layers; nothing copied. */
    static final Layer V027_LAYER = new Layer(new int[0][], V027_OFFSETS, V027_BYTES);

    /** Where each base comes from: the public stock dump, or the owner's own factory dump. */
    public enum Base { STOCK, FACTORY }

    /**
     * What the app can make. RESTORE is the published stock restore file: the stock dump's first 0x1E0000 bytes, under
     * its published name. PROBE is the sensor-ID probe: the stock restore file with a reviewed patch that reads the
     * image sensor's identifier and writes it to the card, so the owner can tell which sensor is fitted; it does not
     * fix the camera, and RESTORE puts it back. C, A and B are withdrawn and not offered: they are the same software
     * with other settings,
     * and when C was flashed on a camera that then froze in photo and video mode, the restore file froze it the same
     * way. The cause is most likely a firmware that does not fit that camera's image sensor, which no file made from
     * the public dump can fix; a copy of C, A or B picked from the phone is recognised and refused.
     *
     * The Repair route's fixed kinds (repairVersions(), newest first) are V027, V026 and V025, made from the factory
     * build v012, and BRINGUP (v006 with the OV9732 port and Volume and LED rows, from the public stock dump). Their
     * Java names keep the numbers older versions of the app showed (v0.24 to v0.27). The screen names each by its base
     * and mods (firmwareName()), and the recipes keep the old number (Recipe.Selection.oldName()). These four stay
     * to recognise their files and to move a pick an older app remembered by Kind name. A new build is not a kind:
     * it is a set in charmera_toolkit/recipes.json, and the screen describes every Repair pick from the recipes
     * (Recipe.describe), these four included.
     */
    public enum Kind {
        RESTORE(true, Base.STOCK, false, "The stock restore file is the first part of the public chip backup of build v006, under its published"
                + " name. Made from the cleaned copy the app downloads, it has the factory settings and no stored photos."
                + " Made from the original chip backup, it's a copy of one working camera's memory, with that camera's settings"
                + " and its two stored photos. Several owners report working cameras after writing it, but what their"
                + " cameras ran before isn't known. After it was written, one original-series camera could no longer take"
                + " pictures. The likely cause is a sensor it doesn't drive.",
                "JH_5408202610261427_0F065D55.bin", "b10a2f1c6302afbad8c205bd2f1e23c0ed244f6f3c553b513f2bd41f4b446906",
                // from the cleaned copy: the same bytes as candidate C (stock software, factory settings, no photos)
                "JH_5408202610261427_0FB74C24.bin", "9b8841d7b0674ca047a67b06594b92e67d17c387b6fab11f4b5191abc644c3ec"),
        PROBE(true, Base.STOCK, false, "The sensor-ID probe is the stock restore file with a small patch. It's a known file. When the camera enters"
                + " photo mode, it walks the sensor bus. For each address that answers, it reads the 16-bit chip-ID"
                + " registers (0x3000..0x301F and 0x3100..0x310F) and writes them to SENSOR.TXT at the root of the"
                + " card, then stops there. It doesn't fix the camera, and it replaces the camera's firmware until you"
                + " write another. It reports which image sensor"
                + " is on the bus and at which address, so you can tell a wrong sensor from a dead one and name the"
                + " exact part. Its boot code, update code and image-sensor table are the stock restore file's byte for"
                + " byte. Only a dead debug routine and the one call that writes the sensor set-up are changed. The"
                + " stock restore file puts an SP140A camera back. On an OV9732 camera, write a v012 build.",
                "JH_5408202611082300_0F065C4D.bin", "6a7aa6d66914805e741d7da3ae743a5250f0f1ef90622b3acc9da4bb75d2547b",
                "JH_5408202611082300_0FB74B1C.bin", "02e2729cffa4dd835f2b138b4ff4df9be89baecfb96f473665dc4b3411e7d2d5"),
        BRINGUP(true, Base.STOCK, true, "The OV9732 port makes the public build, made for the SP140A, drive an OmniVision"
                + " OV9732 sensor. It flips the picture 180°, lifts blue in the white balance, and"
                + " redirects the stock firmware's own per-frame auto-exposure to the OV9732's exposure and gain"
                + " registers, so the picture follows the light instead of staying fixed and dark. Black level is"
                + " matched to the image processor so shadows aren't crushed, and colour and bright-light exposure are"
                + " tuned against a stock camera on a colour card. It adds two setup-menu rows after FORMAT: Volume"
                + " (system sounds, 0 to 8, which also sets playback volume) and LED (the automatic flash light, On as"
                + " before or Off). Boot and update code are the stock restore file's, byte for byte. The stock restore"
                + " file doesn't drive the OV9732, so it stops photo and video working on an OV9732 camera.",
                "JH_5408202611120024_0F0613AC.bin", "0919c5d857f855b11424407211a132ac61f4c91852e01af583bd6b76c85b1c69",
                "JH_5408202611120024_0FB7027B.bin", "8a46998f48410b1f03f4e6f9231b6896757f106b4d4d6854b8cce4fbd6e2dc5b"),
        V025(true, Base.FACTORY, true, "v012 with Volume and LED rows is the factory firmware of OV9732 cameras"
                + " (build v012) with two setup-menu rows after FORMAT: Volume (system sounds, 0 to 8) and LED (the"
                + " automatic flash light, On or Off). The camera starts with Volume 5 and LED On. Photos carry no EXIF"
                + " date.",
                "JH_5408202611130025_0FA3BF37.bin", "a5495ef4a7ab73e95e7f32351c7dca5dcfaa6d3c51f29860c017f547bbe1d3d0",
                "JH_5408202611130025_0FB76FE9.bin", "bb0c826e4c5e27d5183b6a4e67d45434cc0d6196f8d0f914b396fb42ae0bd36a"),
        V026(true, Base.FACTORY, true, "v012 with Volume and LED rows and EXIF dates is the factory firmware of OV9732"
                + " cameras (build v012) with the Volume and LED rows after FORMAT, plus EXIF in every photo:"
                + " Make Kodak, Model Charmera, and the date and time from the camera's clock. Set the date in the"
                + " camera's setup menu first, because an unset clock gives 2025:01:01. The camera starts with Volume 5"
                + " and LED On.",
                "JH_5408202611130026_0FA38C7C.bin", "617770c3f3c9d880f93f06043090d3d07909c845633ca08e9c183deb44fa06d2",
                "JH_5408202611130026_0FB73D2E.bin", "be3cb3ec0ed062c0772aaf2ecb9c1c80000b64e6ac8da11cd649ebde80719738"),
        V027(true, Base.FACTORY, true, "v012 with Volume and LED rows, EXIF dates, boot to last mode and capture"
                + " flash is the factory firmware of OV9732 cameras (build v012) with the Volume and LED rows and EXIF"
                + " dates, plus two changes."
                + " Power-on goes straight into the mode you last used, photo or video (photo the first time), instead"
                + " of the SELECT MODE screen. A short press of the power button still opens it. And when you take a photo, the"
                + " screen flashes black under the icons for a moment, so you can see the shot even at Volume 0.",
                "JH_5408202611170027_0FA38996.bin", "108af2997b108a05f8edc72a339705e77170061c042eb3b30857ff641b266968",
                "JH_5408202611170027_0FB73A48.bin", "89d8b61e8e01e7e89807c766e01e6106eb589b4f927f69b9e50b3286430c0639"),
        C(false, Base.STOCK, false, "Candidate C (withdrawn: the same software as the stock restore file with factory settings. A camera"
                + " it was written to froze in photo and video mode, and so did the stock restore file).",
                "JH_5408202611050003_0FB74C24.bin", "9b8841d7b0674ca047a67b06594b92e67d17c387b6fab11f4b5191abc644c3ec"),
        A(false, Base.STOCK, false, "Candidate A (withdrawn: the same software as C with camera sounds off).",
                "JH_5408202611050001_0FB74D14.bin", "c4822cd7eed63169d80c5105811ab6492444793fafcc7a96e809869f2e5ccda7"),
        B(false, Base.STOCK, false, "Candidate B (withdrawn: the same software as A with silenced sound data).",
                "JH_5408202611050002_0FB0BD3C.bin", "32a7f54e5090050d099681c33378e3d23822a736279ea78f9f3442a45edc3979");

        /** Whether the app offers it. */
        public final boolean offered;
        /** The dump it is made from. */
        public final Base base;
        /** Whether it is one of the Repair route's fixed kinds (repairVersions()). */
        private final boolean repair;
        /**
         * What the file is, in plain words. The screen shows it for RESTORE, PROBE and a withdrawn candidate. A Repair
         * pick is described from the recipes (Recipe.describe) with its way back, so the Repair kinds' labels say only
         * what the build is.
         */
        public final String label;
        /** The reviewed file made from the original dump (the public dump, or the owner's factory dump). */
        public final String fileName;
        public final String sha256;
        /**
         * The reviewed file made from the dump's cleaned copy (CLEAN_STOCK_SHA256, CLEAN_FACTORY_SHA256): the same
         * code, with only the factory settings record and no stored photos. The same as fileName/sha256 for the
         * withdrawn candidates, which already make the store and the photo area that way.
         */
        public final String cleanFileName;
        public final String cleanSha256;

        Kind(boolean offered, Base base, boolean repair, String label, String fileName, String sha256) {
            this(offered, base, repair, label, fileName, sha256, fileName, sha256);
        }

        Kind(boolean offered, Base base, boolean repair, String label, String fileName, String sha256,
             String cleanFileName, String cleanSha256) {
            this.offered = offered;
            this.base = base;
            this.repair = repair;
            this.label = label;
            this.fileName = fileName;
            this.sha256 = sha256;
            this.cleanFileName = cleanFileName;
            this.cleanSha256 = cleanSha256;
        }

        /** Whether a file with this SHA-256 is this kind: made from the original dump or from its cleaned copy. */
        public boolean matches(String sha) {
            return sha256.equals(sha) || cleanSha256.equals(sha);
        }

        /** The kind's file name for the file with this SHA-256 (the cleaned copy's when it is that one). */
        public String fileNameFor(String sha) {
            return cleanSha256.equals(sha) && !sha256.equals(sha) ? cleanFileName : fileName;
        }

        /** The reviewed SHA-256 of this kind made from this dump: the cleaned copy's file when the dump is a clean one. */
        public String shaFrom(byte[] dump) {
            return isClean(dump) ? cleanSha256 : sha256;
        }

        /** Whether it is one of the Repair route's versions. */
        public boolean repair() {
            return repair;
        }

        /**
         * Its name, by its base and mods (Recipe.firmwareName): "v012 with Volume and LED rows". Read from the recipes
         * when asked, never while the enum is made, since Recipe refers back to these kinds.
         */
        public String firmwareName() {
            Recipe.Selection s = Recipe.selectionOf(sha256);
            return s != null ? s.label() : name();
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
     * The Repair route's fixed kinds, newest first: the builds older versions of the app numbered. None is the default
     * pick: that's stock v012 or stock v006, with no mods ticked (Recipe.defaultPick).
     */
    public static Kind[] repairVersions() {
        return new Kind[]{Kind.V027, Kind.V026, Kind.V025, Kind.BRINGUP};
    }

    /** The fixed Repair kind with this SHA-256 (a checked file's), or null. */
    public static Kind repairVersion(String sha256) {
        for (Kind k : repairVersions()) {
            if (k.matches(sha256)) {
                return k;
            }
        }
        return null;
    }

    /**
     * The owner's factory dump from what they picked: exactly the 2 MiB file with FACTORY_DUMP_SHA256. Throws
     * otherwise, with a message in the same form as stockDump's.
     */
    public static byte[] factoryDump(byte[] picked) {
        String sha = UpdateCheck.sha256Hex(picked, 0, picked.length);
        if (picked.length != DUMP_SIZE || !(sha.equals(FACTORY_DUMP_SHA256) || sha.equals(CLEAN_FACTORY_SHA256))) {
            throw new IllegalArgumentException("the file is not the factory chip backup, so the app will not use it. The"
                    + " factory firmware is made only from that exact file, " + FACTORY_DUMP_NAME + ", or its public"
                    + " cleaned copy, " + CLEAN_FACTORY_NAME + " (the-dumping-grounds/charmera-firmware-v012), unchanged."
                    + "\nDetail: its SHA-256 is " + sha + "; the factory chip backup's is " + FACTORY_DUMP_SHA256
                    + " and its cleaned copy's " + CLEAN_FACTORY_SHA256 + ".");
        }
        return picked;
    }

    /** True when the bytes are the factory dump or its cleaned copy. */
    public static boolean isFactoryDump(byte[] picked) {
        if (picked.length != DUMP_SIZE) {
            return false;
        }
        String sha = UpdateCheck.sha256Hex(picked, 0, picked.length);
        return FACTORY_DUMP_SHA256.equals(sha) || CLEAN_FACTORY_SHA256.equals(sha);
    }

    /** True when the 2 MiB dump is one of the cleaned copies (CLEAN_STOCK_SHA256, CLEAN_FACTORY_SHA256). */
    public static boolean isClean(byte[] dump) {
        if (dump.length != DUMP_SIZE) {
            return false;
        }
        String sha = UpdateCheck.sha256Hex(dump, 0, dump.length);
        return CLEAN_STOCK_SHA256.equals(sha) || CLEAN_FACTORY_SHA256.equals(sha);
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
            throw new IllegalArgumentException("the file is " + UpdateCheck.bytes(picked.length) + ", but the public v006 chip"
                    + " backup is " + UpdateCheck.bytes(DUMP_SIZE) + ". A shorter file is usually a cut-off download."
                    + " Download it again.");
        }
        String sha = UpdateCheck.sha256Hex(dump, 0, dump.length);
        if (!sha.equals(STOCK_DUMP_SHA256) && !sha.equals(CLEAN_STOCK_SHA256)) {
            throw new IllegalArgumentException("the file is not the v006 chip backup the app uses, so the app will not use it. The"
                    + " app makes the stock restore file only from that exact file, charmera-v006-2025-10-14-clean.bin"
                    + " (the-dumping-grounds/charmera-firmware-v006) or the original charmera_stock_latest.bin,"
                    + " unchanged.\nDetail: its SHA-256 is " + sha + "; the public v006 chip backup's is " + CLEAN_STOCK_SHA256
                    + " (the original's " + STOCK_DUMP_SHA256 + ").");
        }
        return dump;
    }

    /** True when the bytes are the public stock dump or its cleaned copy, or a restore file made from either. */
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
    public static byte[] build(byte[] dump, Kind kind) {
        if (kind.base == Base.FACTORY) {
            factoryDump(dump);     // a factory kind is made from the owner's factory dump and nothing else
        }
        byte[] out = transform(dump, kind);
        String sha = UpdateCheck.sha256Hex(out, 0, out.length);
        String want = kind.shaFrom(dump);
        if (!sha.equals(want)) {
            String what = kind == Kind.RESTORE ? "the stock restore file"
                    : kind.repair() ? kind.firmwareName() : "candidate " + kind.name();
            String from = kind.base == Base.FACTORY ? "the factory chip backup" : "the public v006 chip backup";
            throw new IllegalStateException("the file made from " + from + " is not exactly " + what + ", so the app"
                    + " will not use it. That's a bug in the app. Report it at https://github.com/daemond-dev/charmera-toolkit/issues.\nDetail: its SHA-256 is " + sha
                    + "; " + what + " has " + want + ".");
        }
        return out;
    }

    /** Whether these exact bytes are the reviewed sensor-ID probe, by SHA-256: a file written without a backup. */
    public static boolean isProbe(byte[] data) {
        return Kind.PROBE.matches(UpdateCheck.sha256Hex(data, 0, data.length));
    }

    /**
     * Whether these exact bytes are one of the reviewed files the app writes without a backup, by SHA-256: the
     * sensor-ID probe, a fixed Repair kind (repairVersions()) or any reviewed build of the recipe table.
     * - The probe and BRINGUP (Base.STOCK) are built from the public build v006: boot code, update code and the SP140A
     *   set-up table are the restore file's byte for byte. The probe patches only a dead routine and the one
     *   sensor-setup call; BRINGUP also patches the AE write tail (AE_HOOK), two image-tuning data tables and two
     *   AE-target message handlers (TUNE_OFFSETS), and the setup-menu and LED code (ROWS_OFFSETS); its black level is
     *   in BRINGUP_STUB's start-up table.
     * - V025, V026 and V027 (Base.FACTORY) are the factory build v012, from the owner's own dump or its public cleaned
     *   copy, with authored patches only (Layer). Stock v012 or another v012 build is their way back.
     * Each is a fixed, reviewed file, so none needs a backup to be checked against.
     */
    public static boolean isReviewedPatch(byte[] data) {
        return isReviewedPatch(UpdateCheck.sha256Hex(data, 0, data.length));
    }

    /**
     * As isReviewedPatch(byte[]), by the SHA-256 of the bytes that passed the check (for WriteGate): the fixed kinds and
     * every reviewed build of the recipe table (Recipe.reviewed), whatever sensor it drives, the stock restore file
     * among them. None needs a backup on record: the owner decides. When nothing confirms what the camera runs, the app
     * says so (Recipe.unconfirmed), and a backup on record whose sensor is the other one makes a loud warning
     * (wrongSensor), never a refusal.
     */
    public static boolean isReviewedPatch(String sha256) {
        return Kind.PROBE.matches(sha256) || repairVersion(sha256) != null || Recipe.reviewed(sha256) != null;
    }

    /**
     * Whether a reviewed file is checked with no backup at all (check drops the reference): the sensor-ID probe and the
     * files that drive the OV9732 (every v012 build, every v006 build with the port), which an SP140A-style comparison
     * with a backup cannot describe. Every other file, reviewed SP140A builds included, is compared with the backup
     * when one is on record, and checked again when it changes.
     */
    public static boolean checkedAlone(String sha256) {
        return Kind.PROBE.matches(sha256) || repairVersion(sha256) != null
                || Recipe.drivesOv9732(Recipe.selectionOf(sha256));
    }

    /**
     * The withdrawn candidate (C, A or B) these bytes are, by SHA-256, or null. Never the stock restore file: the one
     * made from the cleaned copy has candidate C's bytes, and it is the restore file. WriteGate refuses these.
     */
    public static Kind withdrawn(String sha256) {
        if (sha256 == null || Kind.RESTORE.matches(sha256)) {
            return null;
        }
        for (Kind k : Kind.values()) {
            if (!k.offered && k.matches(sha256)) {
                return k;
            }
        }
        return null;
    }

    /**
     * Whether a file is one the Repair route writes, by SHA-256: a Repair version or any reviewed build of the recipe
     * table, whatever sensor it drives. Not the sensor-ID probe (the Probe route's) and not the stock restore file
     * (stock v006, the Restore route's).
     */
    public static boolean isRepairFile(String sha256) {
        if (repairVersion(sha256) != null) {
            return true;
        }
        Recipe.Selection s = Recipe.selectionOf(sha256);
        return s != null && !s.has(Recipe.PROBE_MOD) && !(s.stock() && Recipe.V006.equals(s.base));
    }

    /**
     * UpdateCheck.check against the public build, with the one rule every file-entry path in the app shares: the
     * reviewed patches (the sensor-ID probe and every Repair version) are checked with no reference, whatever backup is
     * passed. They are backup-independent (WriteGate's exemption), so no path may compare one with a backup it was
     * never meant to match — that would fail its sensor-table line and refuse a file that needs no backup (finding 7).
     * Every other file is checked against the reference given. The extra hash runs only when a reference is present
     * (the common no-backup case is free). A file checked alone while a backup is on record still gets a sensor line
     * that says which sensor the backup's firmware drives (backupSensor), and its result names that backup
     * (referenceSha256), so a new backup gets it checked again; it's still not compared with it (compared is false).
     */
    public static UpdateCheck.Result check(String name, byte[] data, byte[] reference) {
        boolean dropped = reference != null && checkedAlone(UpdateCheck.sha256Hex(data, 0, data.length));
        UpdateCheck.Result r = checkWith(name, data, dropped ? null : reference);
        return dropped ? backupSensor(r, data, reference) : r;
    }

    /**
     * A file checked alone (checkedAlone) while a backup is on record: its sensor line, which says nothing in the file
     * can tell which sensor the camera has, becomes one that says which sensor the backup's firmware drives. When that's
     * the other sensor it's the loud "Wrong sensor?" warning, never a refusal: the camera still boots and takes the
     * card update, so the flash is undone from the card. The sensor-ID probe works on either sensor and keeps its line.
     */
    static UpdateCheck.Result backupSensor(UpdateCheck.Result r, byte[] data, byte[] reference) {
        String backupSha = UpdateCheck.sha256Hex(reference, 0, reference.length);
        List<UpdateCheck.Line> lines = new java.util.ArrayList<>();
        String mine = UpdateCheck.sensorOf(data);
        String theirs = UpdateCheck.sensorOf(reference);
        boolean probe = Kind.PROBE.matches(r.sha256);
        boolean done = probe || mine == null;
        for (UpdateCheck.Line l : r.lines) {
            if (probe && "sensor-table".equals(l.name) && l.text.contains(UpdateCheck.BACKUP_STEP)) {
                // A backup is already chosen, so the line's "choose that dump" step no longer applies.
                lines.add(new UpdateCheck.Line(l.level, l.name, l.title,
                        l.text.replace(" " + UpdateCheck.BACKUP_STEP, ""), l.detail));
            } else if (!done && "sensor-table".equals(l.name) && l.level != UpdateCheck.Level.FAIL) {
                done = true;
                lines.add(backupSensorLine(fileLabel(r.sha256), mine, theirs, l.detail));
            } else {
                lines.add(l);
            }
        }
        if (!done && theirs != null && !theirs.equals(mine)) {
            lines.add(backupSensorLine(fileLabel(r.sha256), mine, theirs, ""));
        }
        return new UpdateCheck.Result(lines, r.cardName, r.sha256, r.size, false, backupSha);
    }

    /** The sensor line for a file checked alone against the sensor the backup's firmware drives (theirs, or null). */
    static UpdateCheck.Line backupSensorLine(String file, String mine, String theirs, String detail) {
        if (theirs != null && !theirs.equals(mine)) {
            return new UpdateCheck.Line(UpdateCheck.Level.WARN, "sensor-table", UpdateCheck.WRONG_SENSOR, "the firmware"
                    + " in your chip backup drives the " + theirs + ", and " + file + " drives the " + mine + ". If the"
                    + " backup is this camera's, the camera boots with this file but can't take photos until you write "
                    + Recipe.fixFor(theirs) + ". It's a known file, so the app writes it if you go ahead.", detail);
        }
        if (theirs != null) {
            return new UpdateCheck.Line(UpdateCheck.Level.WARN, "sensor-table", "Image sensor set-up", "the firmware"
                    + " in your backup drives the " + theirs + " too, as " + file + " does. That's your camera's sensor"
                    + " only if the backup was read before the camera was ever written to.", detail);
        }
        return new UpdateCheck.Line(UpdateCheck.Level.WARN, "sensor-table", "Image sensor set-up", "the app can't"
                + " tell which sensor the firmware in your backup drives, so it can't say whether " + file + " fits"
                + " your camera. This file drives the " + mine + ".", detail);
    }

    /** What a reviewed file is called in a sensor line: its name (base and mods), or "this file". */
    static String fileLabel(String sha256) {
        Recipe.Selection s = Recipe.selectionOf(sha256);
        if (s != null) {
            return s.label();
        }
        Kind k = repairVersion(sha256);
        return k != null ? k.firmwareName() : "this file";
    }

    /** check without the backup rule: UpdateCheck.check, with the reviewed files' known failures turned into warnings. */
    private static UpdateCheck.Result checkWith(String name, byte[] data, byte[] ref) {
        UpdateCheck.Result r = UpdateCheck.check(name, data, UpdateCheck.PUBLIC_BUILD, ref);
        Kind k = repairVersion(r.sha256);
        if (k != null && k.base == Base.FACTORY) {
            return asFactory(r, k);
        }
        Recipe.Selection s = Recipe.selectionOf(r.sha256);
        if (s == null) {
            return r;
        }
        // The lines the file fails on its own, with no backup (the build is unknown to the app), say "unknown build"
        // (asOtherBuild). A sensor line that compares the file with the backup on record says the backup's firmware
        // drives the sensor another way: for a reviewed build that is a loud warning (wrongSensor), not a refusal.
        UpdateCheck.Result alone = ref == null ? r : UpdateCheck.check(name, data, UpdateCheck.PUBLIC_BUILD, null);
        return wrongSensor(Recipe.V006.equals(s.base) ? r : asOtherBuild(r, s, alone), s, alone);
    }

    /**
     * A reviewed build's check with each failed sensor-table line that compares it with the backup (one it does not
     * fail alone) turned into a loud warning: the file is known byte for byte, so the only question left is whether
     * it fits this camera, and that is the owner's call. The line says what happens if the backup is right.
     */
    static UpdateCheck.Result wrongSensor(UpdateCheck.Result r, Recipe.Selection s, UpdateCheck.Result alone) {
        List<UpdateCheck.Line> lines = new java.util.ArrayList<>();
        boolean changed = false;
        for (UpdateCheck.Line l : r.lines) {
            if (l.level == UpdateCheck.Level.FAIL && "sensor-table".equals(l.name) && !failsAlone(alone, l)) {
                changed = true;
                lines.add(new UpdateCheck.Line(UpdateCheck.Level.WARN, l.name, UpdateCheck.WRONG_SENSOR, "the firmware"
                        + " in your chip backup drives the image sensor differently from " + s.label() + ", which drives"
                        + " the " + Recipe.sensorOf(s) + ". If the backup is this camera's, the camera boots with this"
                        + " file but can't take photos until you write firmware for its sensor (base v012 for the"
                        + " OV9732, stock v006 for the SP140A). It's a known file, so the app writes it if you go"
                        + " ahead.",
                        l.text + (l.detail.isEmpty() ? "" : " " + l.detail)));
            } else {
                lines.add(l);
            }
        }
        return changed ? new UpdateCheck.Result(lines, r.cardName, r.sha256, r.size, r.compared, r.referenceSha256) : r;
    }

    /** The checks a factory kind's exact bytes turn from a failure into a warning: those that only say "unknown build". */
    static final List<String> FACTORY_WARN_ONLY = Arrays.asList("series", "sensor-table");

    /**
     * A factory kind's check, its exact bytes identified by SHA-256: the factory build (v012) has no SP140A-style
     * sensor table and build marks the app does not know, so those lines are warnings, never failures. Every other line
     * (name, sum, size, start of the file, final erase, built-in parts...) stands as UpdateCheck gave it.
     */
    static UpdateCheck.Result asFactory(UpdateCheck.Result r, Kind k) {
        return softened(r, r, "the app can't check this part. The file is " + k.firmwareName() + ", made from the"
                + " camera's own factory firmware. That's a build the app doesn't know, and it drives the OV9732 without"
                + " an SP140A-style sensor table. It's the known file, identified by its SHA-256.");
    }

    /**
     * As asFactory, for a reviewed build (Recipe) of a base other than v006: the factory build v012 (the same words as
     * asFactory's) or v008_test, a later SP140A build whose marks the app does not know either.
     */
    static UpdateCheck.Result asOtherBuild(UpdateCheck.Result r, Recipe.Selection s, UpdateCheck.Result alone) {
        String what = Recipe.V012.equals(s.base) ? "made from the camera's own factory firmware. That's a build the app"
                + " doesn't know, and it drives the OV9732 without an SP140A-style sensor table"
                : "made from build " + s.base + ", a later SP140A build the app doesn't know";
        return softened(r, alone, "the app can't check this part. The file is " + s.label() + ", " + what + ". It's the"
                + " known file, identified by its SHA-256.");
    }

    /**
     * The check with its "unknown build" failures (FACTORY_WARN_ONLY) turned into warnings that say why. A failure is
     * one of those only when the same file fails it in the same words with no backup given (alone: the check without
     * a reference, or r itself when r had none): it says the app does not know the build. A failure that compares the
     * file with the backup on record (a sensor table that differs from the backup's, a backup with none) is never
     * softened.
     */
    static UpdateCheck.Result softened(UpdateCheck.Result r, UpdateCheck.Result alone, String why) {
        List<UpdateCheck.Line> lines = new java.util.ArrayList<>();
        for (UpdateCheck.Line l : r.lines) {
            if (l.level == UpdateCheck.Level.FAIL && FACTORY_WARN_ONLY.contains(l.name) && failsAlone(alone, l)) {
                lines.add(new UpdateCheck.Line(UpdateCheck.Level.WARN, l.name, l.title, why, l.text
                        + (l.detail.isEmpty() ? "" : " " + l.detail)));
            } else {
                lines.add(l);
            }
        }
        return new UpdateCheck.Result(lines, r.cardName, r.sha256, r.size, r.compared, r.referenceSha256);
    }

    /** Whether the check with no backup (alone) fails the line l fails, in the same words. */
    private static boolean failsAlone(UpdateCheck.Result alone, UpdateCheck.Line l) {
        for (UpdateCheck.Line a : alone.lines) {
            if (a.level == UpdateCheck.Level.FAIL && a.name.equals(l.name) && a.text.equals(l.text)) {
                return true;
            }
        }
        return false;
    }

    /** The recipe itself, without the final hash check (the tests run it on synthetic images). */
    static byte[] transform(byte[] dump, Kind kind) {
        if (dump.length != DUMP_SIZE) {
            throw new IllegalArgumentException("the chip backup must be " + UpdateCheck.bytes(DUMP_SIZE) + ".");
        }
        if (kind.base == Base.FACTORY) {
            return factoryImage(dump, layersOf(kind));
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
            System.arraycopy(AE_HOOK, 0, payload, AE_HOOK_OFFSET, AE_HOOK.length);            // + the AE-exposure redirect
            System.arraycopy(AE_STUB, 0, payload, AE_STUB_OFFSET, AE_STUB.length);
            for (int i = 0; i < TUNE_OFFSETS.length; i++) {                                    // + v0.23 colour/exposure tuning
                System.arraycopy(TUNE_BYTES[i], 0, payload, TUNE_OFFSETS[i], TUNE_BYTES[i].length);
            }
            for (int i = 0; i < ROWS_OFFSETS.length; i++) {                                    // + v0.24 Volume and LED rows
                System.arraycopy(ROWS_BYTES[i], 0, payload, ROWS_OFFSETS[i], ROWS_BYTES[i].length);
            }
            return payload;
        }
        byte[] image = dump.clone();
        int first = firstValidRecord(image);
        if (first < 0) {
            throw new IllegalArgumentException("the public v006 chip backup has no undamaged settings record.");
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

    /** A factory kind's layers, in order: each later version is the one before plus a layer of its own. */
    static Layer[] layersOf(Kind kind) {
        switch (kind) {
            case V025:
                return new Layer[]{V025_LAYER};
            case V026:
                return new Layer[]{V025_LAYER, V026_LAYER};
            case V027:
                return new Layer[]{V025_LAYER, V026_LAYER, V027_LAYER};
            default:
                throw new IllegalArgumentException(kind + " is not made from the factory chip backup.");
        }
    }

    /**
     * The factory recipe: the dump's first 0x1E0000 bytes with the internal photo slots blanked, then each layer in
     * order, its copies (from the factory dump itself, never from an earlier layer's output) before its patches.
     */
    static byte[] factoryImage(byte[] dump, Layer... layers) {
        byte[] out = Arrays.copyOf(dump, UpdateCheck.UPDATE_SIZE);
        Arrays.fill(out, FACTORY_PHOTOS_START, FACTORY_PHOTOS_END, (byte) 0xFF);
        for (Layer l : layers) {
            for (int[] c : l.copies) {
                if (c.length != 3 || c[2] <= 0 || c[0] < 0 || c[1] < 0 || c[0] + c[2] > dump.length
                        || c[1] + c[2] > out.length) {
                    throw new IllegalStateException("a copy range lies outside the chip backup or the update file.");
                }
                System.arraycopy(dump, c[0], out, c[1], c[2]);
            }
            for (int i = 0; i < l.offsets.length; i++) {
                if (l.offsets[i] < 0 || l.offsets[i] + l.bytes[i].length > out.length) {
                    throw new IllegalStateException("a patch lies outside the update file.");
                }
                System.arraycopy(l.bytes[i], 0, out, l.offsets[i], l.bytes[i].length);
            }
        }
        return out;
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
                throw new IllegalArgumentException("the sound " + e.displayName() + " in the public v006 chip backup is not a WAV"
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
                        throw new IllegalArgumentException("the sound " + e.displayName() + " in the public v006 chip backup has"
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
