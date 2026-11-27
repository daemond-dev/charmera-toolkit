import io.github.daemonddev.charmera.Candidates;
import io.github.daemonddev.charmera.UpdateCheck;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Plain-JVM tests for making the flash candidates in the app. Synthetic images test each step of the recipe and the
 * refusals; with CHARMERA_STOCK_IMAGE set to the public stock dump, the restore file and the three withdrawn
 * candidates are made and must hash to the published and reviewed files (Candidates.build refuses anything else), and
 * the checker must find the dump's build marks where the research put them.
 */
public final class CandidatesTest {

    private static int passes = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // the stock dump is recognised by its hash only
        byte[] junk = new byte[0x200000];
        expect("a 2 MiB file that is not the dump is refused", refused(() -> Candidates.stockDump(junk), "not the v006 chip backup the app uses"));
        expect("a short file is refused as cut off", refused(() -> Candidates.stockDump(new byte[0x166000]), "cut-off download"));
        expect("isStockDump is false for anything else", !Candidates.isStockDump(junk) && !Candidates.isStockDump(new byte[10]));

        // each step of the recipe, on a synthetic image
        byte[] img = synthetic();
        byte[] c = invokeTransform(img, Candidates.Kind.C);
        byte[] a = invokeTransform(img, Candidates.Kind.A);
        byte[] b = invokeTransform(img, Candidates.Kind.B);
        expect("output is an update file's size", c.length == UpdateCheck.UPDATE_SIZE && a.length == c.length && b.length == c.length);
        expect("C keeps the first valid record at sector 0", Arrays.equals(Arrays.copyOfRange(c, 0x170000, 0x170100),
                Arrays.copyOfRange(img, 0x170200, 0x170300)));
        expect("the rest of the bank is erased", allEqual(c, 0x170200, 0x17F000, 0xFF) && allEqual(c, 0x170100, 0x170200, 0));
        expect("A sets UI sound 0 with a valid CRC", (a[0x170019] & 0xFF) == 0 && crcOk(a, 0x170000));
        expect("C keeps UI sound as it was", (c[0x170019] & 0xFF) == 5);
        expect("photo directory zeroed, photo slots erased", allEqual(c, 0x17F000, 0x17F200, 0)
                && allEqual(c, 0x17F200, UpdateCheck.UPDATE_SIZE, 0xFF));
        expect("B silences 8-bit PCM to 0x80 and 16-bit to 0", allEqual(b, WAV8_DATA, WAV8_DATA + 64, 0x80)
                && allEqual(b, WAV16_DATA, WAV16_DATA + 64, 0) && b[WAV8_DATA - 8] == 'd');
        expect("A leaves the sound data alone", a[WAV8_DATA] == img[WAV8_DATA]);
        expect("nothing below the settings bank changes", Arrays.equals(Arrays.copyOf(b, 0x120000), Arrays.copyOf(img, 0x120000)));
        expect("a synthetic dump never passes build()", refused(() -> Candidates.build(img, Candidates.Kind.C),
                "is not exactly candidate C, so the app will not use it"));
        expect("a synthetic restore never passes build()", refused(() -> Candidates.build(img, Candidates.Kind.RESTORE),
                "is not exactly the stock restore file"));
        expect("a refusal says the cause first and keeps the SHA-256 for its last line", refused(
                () -> Candidates.stockDump(junk), "so the app will not use it") && refused(() -> Candidates.stockDump(junk),
                "\nDetail: its SHA-256 is "));
        // Whether a 2 MiB file could be a backup is for UpdateCheck.backupProblem to say: step 0 may refuse it too.
        expect("a refused dump is not pointed to step 0", !refused(() -> Candidates.stockDump(junk), "step 0")
                && !refused(() -> Candidates.stockDump(junk), "Choose this camera's backup"));
        expect("the stock restore, the sensor-ID probe, the OV9732 bring-up and firmware v0.25, v0.26 and v0.27 are"
                + " offered, in that order", java.util.Arrays.equals(Candidates.Kind.offeredKinds(), new Candidates.Kind[]{
                        Candidates.Kind.RESTORE, Candidates.Kind.PROBE, Candidates.Kind.BRINGUP, Candidates.Kind.V025,
                        Candidates.Kind.V026, Candidates.Kind.V027}));
        byte[] restore0 = invokeTransform(img, Candidates.Kind.RESTORE);
        expect("the restore is the dump's first 0x1E0000 bytes, untouched",
                java.util.Arrays.equals(restore0, java.util.Arrays.copyOf(img, UpdateCheck.UPDATE_SIZE)));

        String real = System.getenv("CHARMERA_STOCK_IMAGE");
        byte[] given = real == null || real.isEmpty() ? null : Files.readAllBytes(Paths.get(real));
        if (given == null) {
            System.out.println("CandidatesTest: real-dump checks SKIPPED (set CHARMERA_STOCK_IMAGE to the public stock dump)");
        } else if (!Candidates.isStockDump(given)) {
            System.out.println("CandidatesTest: real-dump checks SKIPPED (CHARMERA_STOCK_IMAGE is not the public stock dump)");
        } else {
            byte[] dump = Candidates.stockDump(given);
            for (Candidates.Kind k : Candidates.Kind.values()) {
                if (k.base != Candidates.Base.STOCK) {
                    expect("the stock dump never makes " + k, refused(() -> Candidates.build(dump, k),
                            "not the factory chip backup"));
                    continue;
                }
                byte[] out = Candidates.build(dump, k);   // throws unless the SHA-256 is the published or reviewed one
                String want = k.shaFrom(dump);            // the cleaned copy makes the clean variant of each kind
                UpdateCheck.Result r = UpdateCheck.check(k.fileNameFor(want), out);
                expect("real " + k + " is the reviewed file and passes the checker", r.ok
                        && k.fileNameFor(want).equals(r.cardName) && want.equals(r.sha256) && k.matches(r.sha256));
            }
            byte[] restore = Arrays.copyOf(dump, UpdateCheck.UPDATE_SIZE);
            expect("the published restore file is accepted as the dump", Arrays.equals(Candidates.stockDump(restore), dump));
            // Finding 7, executed: Candidates.check is the one rule every file-entry path shares. The sensor-ID probe
            // is checked with no reference whatever backup is passed, so a backup it was never meant to match cannot
            // fail its sensor-table line and refuse the one file that needs no backup. Every other file is checked
            // against the reference. Run here because this logic is safety-critical and must not rest on a source test.
            byte[] probe7 = Candidates.build(dump, Candidates.Kind.PROBE);
            UpdateCheck.Result probeWithBackup = Candidates.check(Candidates.Kind.PROBE.fileNameFor(UpdateCheck.sha256Hex(probe7, 0, probe7.length)), probe7, dump);
            UpdateCheck.Result probeNoBackup = Candidates.check(Candidates.Kind.PROBE.fileNameFor(UpdateCheck.sha256Hex(probe7, 0, probe7.length)), probe7, null);
            expect("the probe is checked with no reference even when a backup is passed, and its check names the"
                    + " backup on record", probeWithBackup.ok && !probeWithBackup.compared
                    && UpdateCheck.sha256Hex(dump, 0, dump.length).equals(probeWithBackup.referenceSha256));
            expect("the probe comes out identical with or without a backup passed", probeNoBackup.ok
                    && !probeNoBackup.compared && probeNoBackup.referenceSha256 == null
                    && probeWithBackup.sha256.equals(probeNoBackup.sha256));
            UpdateCheck.Result restoreWithBackup = Candidates.check(Candidates.Kind.RESTORE.fileNameFor(UpdateCheck.sha256Hex(restore, 0, restore.length)), restore, dump);
            expect("a non-probe file is still checked against the backup passed (the rule is probe-only)",
                    restoreWithBackup.compared && restoreWithBackup.referenceSha256 != null);
            expect("Candidates.isProbe keys on the exact probe bytes", Candidates.isProbe(probe7)
                    && !Candidates.isProbe(restore));
            // The OV9732 bring-up is the second reviewed patch: like the probe it is checked with no reference, so a
            // backup it was never meant to match cannot fail its sensor-table line and refuse it (finding 7).
            byte[] bringup7 = Candidates.build(dump, Candidates.Kind.BRINGUP);
            UpdateCheck.Result bringupWithBackup = Candidates.check(Candidates.Kind.BRINGUP.fileNameFor(UpdateCheck.sha256Hex(bringup7, 0, bringup7.length)), bringup7, dump);
            expect("the bring-up is checked with no reference even when a backup is passed, and its check names the"
                    + " backup on record", bringupWithBackup.ok && !bringupWithBackup.compared
                    && UpdateCheck.sha256Hex(dump, 0, dump.length).equals(bringupWithBackup.referenceSha256));
            expect("isReviewedPatch covers the probe, the bring-up and the restore file; only the first two are checked alone",
                    Candidates.isReviewedPatch(probe7) && Candidates.isReviewedPatch(bringup7)
                    && Candidates.isReviewedPatch(restore) && !Candidates.isProbe(bringup7)
                    && !Candidates.checkedAlone(UpdateCheck.sha256Hex(restore, 0, restore.length)));
            UpdateCheck.Marks mk = UpdateCheck.findMarks(dump);
            expect("the real dump's marks are found by content where the research put them", mk.complete()
                    && mk.stampOffset == 0x218C4 && "202505201029".equals(mk.stamp) && mk.buildOffset == 0x18964
                    && mk.resetStart == 0xF5BFC && mk.sensorStart == 0xF5C6C && mk.sensorEnd == 0xF5CE8 && mk.sensorPairs == 61
                    && mk.referencedFrom == 0x7B91C && mk.busAddress == 0x78
                    && "aea3976bdf7ba2e747d8b56141f7ef0b4e3262481540dc2869457bc814fcb755".equals(mk.sensorSha256));
            expect("its source folder is v006, named in all 27 of its source paths, the first name at 0x3a4d2 as image.py"
                    + " reports it", "v006".equals(mk.sourceTree) && mk.sourceTrees.size() == 1
                    && mk.sourceTrees.get("v006") == 27 && mk.sourceTreeOffset == 0x3A4D2
                    && "v006".equals(new String(dump, mk.sourceTreeOffset, 4, StandardCharsets.US_ASCII))
                    && mk.stampConflict.isEmpty() && "Oct 14 2025".equals(mk.compiled) && mk.compiledOffset == 0xF3978);
            UpdateCheck.Result rr = UpdateCheck.check(Candidates.Kind.RESTORE.fileNameFor(UpdateCheck.sha256Hex(restore, 0, restore.length)), restore, UpdateCheck.PUBLIC_BUILD, dump);
            expect("the restore file against the dump it came from passes, and the sensor line says what a public"
                    + " backup proves", rr.ok && rr.compared && level(rr, "software") == UpdateCheck.Level.PASS
                    && level(rr, "vector-table") == UpdateCheck.Level.PASS
                    && level(rr, "sensor-table") == UpdateCheck.Level.WARN
                    && rr.verdict().startsWith("OK to put on the card. Read "
                    + (Candidates.isClean(dump) ? "this warning" : "these 2 warnings")   // the clean copy stores no photos
                    + " first. Image sensor set-up:"
                    + " the same as in the firmware in your backup. That firmware is the public v006 build, unchanged")
                    && (Candidates.isClean(dump) || rr.verdict().contains("Stored photos:")));   // the clean copy has none
            expect("the public dump is refused as a backup of your camera",
                    UpdateCheck.backupProblem(dump, Candidates.PUBLIC_DUMP_SHA256S) != null
                    && UpdateCheck.backupProblem(dump, Candidates.PUBLIC_DUMP_SHA256S).startsWith(
                    "it is byte for byte the public v006 chip backup"));   // the cleaned copy too: it is what the app downloads
            // A read of a camera flashed with the restore file, once one setting was saved: taken, with the note.
            byte[] read = dump.clone();
            read[0x170100] ^= 1;
            expect("a near copy of the public dump is taken, and noted as the public build",
                    UpdateCheck.backupProblem(read, Candidates.PUBLIC_DUMP_SHA256S) == null
                            && UpdateCheck.backupNotes(read, UpdateCheck.PUBLIC_BUILD).size() == 1
                            && UpdateCheck.backupNotes(read, UpdateCheck.PUBLIC_BUILD).get(0).contains("the public v006"
                            + " build, unchanged"));
            UpdateCheck.Result plain = UpdateCheck.check(Candidates.Kind.RESTORE.fileNameFor(UpdateCheck.sha256Hex(restore, 0, restore.length)), restore);
            expect("without a backup the restore file's own checks pass, its vector table is the public build's, and"
                    + " it is not OK yet", plain.ok && !plain.compared && level(plain, "vector-table") == UpdateCheck.Level.PASS
                    && plain.verdict().startsWith("Not compared with a backup of this camera's own chip"));
            expect("its build marks name the source folder, and what a folder shows", text(plain, "series").contains(
                    "compiled Oct 14 2025, source folder v006, stamp 202505201029") && text(plain, "series").contains(
                    "the same one does not show the same build, and neither says whether the file fits your camera"));
            // The same changes to the real file as in tests/test_android.py, where image.py makes the same calls.
            byte[] restamped = restore.clone();
            put(restamped, 0x218C4, "202601010000");
            UpdateCheck.Result sr = UpdateCheck.check(named(restamped), restamped);
            expect("the restore file with another stamp inside is a build laid out like the public one, not that build"
                    + " with changes", level(sr, "build") == UpdateCheck.Level.WARN && text(sr, "build").contains(
                    "laid out like the public v006 build: its stamp is not that build's")
                    && !text(sr, "build").contains("with changes"));
            // The compile date and the source folder are build marks too (S15): another one is another build.
            byte[] redated = restore.clone();
            put(redated, 0xF3978, "Oct 15 2025");  // the dump's only compile date, a day on
            UpdateCheck.Result dr = UpdateCheck.check(named(redated), redated);
            expect("the restore file with another compile date is a build laid out like the public one, not that build"
                    + " with changes", "Oct 15 2025".equals(UpdateCheck.findMarks(redated).compiled)
                    && level(dr, "build") == UpdateCheck.Level.WARN && text(dr, "build").contains("a build the app does"
                    + " not know, laid out like the public v006 build: its compile date is not that build's")
                    && !text(dr, "build").contains("with changes") && dr.ok);
            byte[] v007 = replaced(restore, "\\2247\\5408\\v006\\", "\\2247\\5408\\v007\\");
            UpdateCheck.Result fr7 = UpdateCheck.check(named(v007), v007);
            expect("and so is the restore file with v007 for v006 in all 27 source paths",
                    UpdateCheck.findMarks(v007).sourceTrees.equals(java.util.Collections.singletonMap("v007", 27))
                    && level(fr7, "build") == UpdateCheck.Level.WARN && text(fr7, "build").contains("a build the app"
                    + " does not know, laid out like the public v006 build: its source folder is not that build's")
                    && !text(fr7, "build").contains("with changes") && fr7.ok);
            byte[] changed = restore.clone();
            changed[0x110000] ^= 1;                 // the zero padding below the resource pack
            UpdateCheck.Result cr = UpdateCheck.check(named(changed), changed);
            expect("while a change that keeps every build mark is the public dump's build with changes",
                    level(cr, "build") == UpdateCheck.Level.INFO && text(cr, "build").contains("the public v006 build"
                    + " (the one both public v006 chip backups hold, and the one the stock restore file is), with changes"));
            byte[] twice = dump.clone();
            put(twice, 0xFFF8, "*.bin\0xx202601010101\0");   // a second stamp after a second *.bin
            UpdateCheck.Marks tm = UpdateCheck.findMarks(twice);
            expect("two different stamps after *.bin: no stamp", tm.stamp == null
                    && tm.stampConflict.equals(Arrays.asList("202601010101", "202505201029")));
            byte[] broken = restore.clone();
            for (int i = 0x200; i < 0x240; i++) {
                broken[i] ^= (byte) 0xFF;
            }
            UpdateCheck.Result vr = UpdateCheck.check(String.format(Locale.ROOT, "JH_5408202611061300_%08X.bin",
                    UpdateCheck.byteSum(broken)), broken);
            expect("the restore file with its vector table destroyed is refused", !vr.ok
                    && level(vr, "vector-table") == UpdateCheck.Level.FAIL);
        }
        // The owner's factory dump (private, never in the repository): v0.25 is made from it and from nothing else.
        String factoryPath = System.getenv("CHARMERA_FACTORY_DUMP");
        byte[] factory = factoryPath == null || factoryPath.isEmpty() ? null : Files.readAllBytes(Paths.get(factoryPath));
        if (factory == null) {
            System.out.println("CandidatesTest: factory-dump checks SKIPPED (set CHARMERA_FACTORY_DUMP to the factory dump)");
        } else if (!Candidates.isFactoryDump(factory)) {
            System.out.println("CandidatesTest: factory-dump checks SKIPPED (CHARMERA_FACTORY_DUMP is not the factory dump)");
        } else {
            String asBackup = UpdateCheck.backupProblem(factory, Candidates.PUBLIC_DUMP_SHA256S);
            expect("the cleaned v012 copy is refused as a backup and named; the owner's own read is not refused for being"
                    + " public", Candidates.isClean(factory) ? asBackup != null && asBackup.contains("factory build v012")
                    : asBackup == null || !asBackup.contains("public"));
            // an owner's own read of a v012 chip (other settings, another photo): v012's OV9732 table is its sensor
            // table, so it is taken as a backup
            byte[] own = factory.clone();
            own[0x170300] ^= 0x5A;
            own[0x1A0000] ^= 0x5A;
            UpdateCheck.Marks ownMarks = UpdateCheck.findMarks(own);
            expect("an own read of a v012 chip has v012's 167-write OV9732 table and is taken as a backup",
                    ownMarks.complete() && ownMarks.sensorStart == 0xF5D3C && ownMarks.sensorPairs == 167
                    && "718cf3a161fc9961fc79a1c8b5a1ba7016973818c6d634c9a0eef309265361d6".equals(ownMarks.sensorSha256)
                    && UpdateCheck.backupProblem(own, Candidates.PUBLIC_DUMP_SHA256S) == null);
            byte[] v025 = Candidates.build(factory, Candidates.Kind.V025);   // throws unless it is the reference file
            expect("the factory dump makes the v0.25 reference file", Candidates.Kind.V025.shaFrom(factory).equals(
                    UpdateCheck.sha256Hex(v025, 0, v025.length)) && v025.length == UpdateCheck.UPDATE_SIZE
                    && "a5495ef4a7ab73e95e7f32351c7dca5dcfaa6d3c51f29860c017f547bbe1d3d0".equals(Candidates.Kind.V025.sha256)
                    && "bb0c826e4c5e27d5183b6a4e67d45434cc0d6196f8d0f914b396fb42ae0bd36a".equals(Candidates.Kind.V025.cleanSha256));
            int differ = 0;
            for (int i = 0; i < v025.length; i++) {
                boolean photos = i >= Candidates.FACTORY_PHOTOS_START && i < Candidates.FACTORY_PHOTOS_END;
                if (!photos && v025[i] != factory[i]) {
                    differ++;
                }
            }
            expect("outside the blanked photo slots, v0.25 differs from the dump in exactly its 172 authored bytes",
                    differ == 172 && allEqual(v025, Candidates.FACTORY_PHOTOS_START, Candidates.FACTORY_PHOTOS_END, 0xFF));
            UpdateCheck.Result withBackup = Candidates.check(Candidates.Kind.V025.fileNameFor(UpdateCheck.sha256Hex(v025, 0, v025.length)), v025, factory);
            UpdateCheck.Result plain = Candidates.check(Candidates.Kind.V025.fileNameFor(UpdateCheck.sha256Hex(v025, 0, v025.length)), v025, null);
            expect("v0.25 passes with v012's build marks found (its OV9732 table included), and no backup comparison",
                    withBackup.ok && plain.ok && !withBackup.compared
                    && UpdateCheck.sha256Hex(factory, 0, factory.length).equals(withBackup.referenceSha256)
                    && level(plain, "series") == UpdateCheck.Level.PASS && level(plain, "sensor-table") == UpdateCheck.Level.WARN
                    && Candidates.Kind.V025.fileNameFor(plain.sha256).equals(plain.cardName));
            expect("every other line UpdateCheck gives v0.25 is unchanged", level(plain, "checksum") == UpdateCheck.Level.PASS
                    && level(plain, "magic") == UpdateCheck.Level.PASS && level(plain, "name") == UpdateCheck.Level.PASS
                    && level(plain, "size") == UpdateCheck.Level.PASS);
            expect("UpdateCheck alone takes v0.25 too: v012 is a build it knows, so nothing is refused for the build marks",
                    UpdateCheck.check(Candidates.Kind.V025.fileNameFor(UpdateCheck.sha256Hex(v025, 0, v025.length)), v025).ok);
            expect("v0.25 is a reviewed patch and the Repair version it says", Candidates.isReviewedPatch(v025)
                    && Candidates.repairVersion(plain.sha256) == Candidates.Kind.V025);
            byte[] other = v025.clone();
            other[0x62390] ^= 1;
            UpdateCheck.Result changed = Candidates.check(named(other), other, null);
            expect("a v0.25 with one byte changed is not v0.25, but an unknown v012 file: warned about, not refused",
                    changed.ok && !Candidates.isReviewedPatch(other) && level(changed, "series") == UpdateCheck.Level.PASS
                    && level(changed, "build") == UpdateCheck.Level.WARN && level(changed, "software") == UpdateCheck.Level.WARN
                    && level(changed, "vector-table") == UpdateCheck.Level.PASS
                    && level(changed, "sensor-table") == UpdateCheck.Level.WARN
                    && changed.report().contains("v012's program for the OmniVision OV9732 sensor"));
            expect("the factory dump never makes a stock kind", refused(() -> Candidates.build(factory,
                    Candidates.Kind.BRINGUP), "is not exactly v006 with the OV9732 port and Volume and LED rows"));
            // v0.26: v0.25 plus the EXIF header, its vendor template part copied from the dump at run time.
            byte[] v026 = Candidates.build(factory, Candidates.Kind.V026);   // throws unless it is the reference file
            expect("the factory dump makes the v0.26 reference file", v026.length == UpdateCheck.UPDATE_SIZE
                    && (Candidates.isClean(factory) ? "be3cb3ec0ed062c0772aaf2ecb9c1c80000b64e6ac8da11cd649ebde80719738"
                    : "617770c3f3c9d880f93f06043090d3d07909c845633ca08e9c183deb44fa06d2").equals(
                    UpdateCheck.sha256Hex(v026, 0, v026.length))
                    && Candidates.Kind.V026.shaFrom(factory).equals(UpdateCheck.sha256Hex(v026, 0, v026.length)));
            int[] cp = {0x10753A, 0x89712, 0x26E};   // Candidates.V026_COPIES (package-private)
            expect("the copied template is the dump's own, identical in v0.25", java.util.Arrays.equals(
                    java.util.Arrays.copyOfRange(factory, cp[0], cp[0] + cp[2]),
                    java.util.Arrays.copyOfRange(v025, cp[0], cp[0] + cp[2])));
            int differ26 = 0;
            int copyDiffers = 0;
            for (int i = 0; i < v026.length; i++) {
                if (v026[i] != v025[i]) {
                    differ26++;
                    if (i >= cp[1] && i < cp[1] + cp[2]) {
                        copyDiffers++;
                    }
                }
            }
            expect("v0.26 differs from v0.25 in its 332 authored bytes and the copied range only",
                    differ26 == 332 + copyDiffers);
            UpdateCheck.Result plain26 = Candidates.check(Candidates.Kind.V026.fileNameFor(UpdateCheck.sha256Hex(v026, 0, v026.length)), v026, factory);
            expect("v0.26 passes its checks, with no backup comparison, under its own name", plain26.ok
                    && !plain26.compared && Candidates.Kind.V026.fileNameFor(plain26.sha256).equals(plain26.cardName)
                    && level(plain26, "checksum") == UpdateCheck.Level.PASS);
            expect("v0.26 is a reviewed patch and the Repair version it says", Candidates.isReviewedPatch(v026)
                    && Candidates.repairVersion(plain26.sha256) == Candidates.Kind.V026);
            byte[] v027 = Candidates.build(factory, Candidates.Kind.V027);   // throws unless it is the reference file
            UpdateCheck.Result plain27 = Candidates.check(Candidates.Kind.V027.fileNameFor(
                    UpdateCheck.sha256Hex(v027, 0, v027.length)), v027, factory);
            expect("the factory dump makes the v0.27 reference file, which passes its checks under its own name",
                    (Candidates.isClean(factory) ? "89d8b61e8e01e7e89807c766e01e6106eb589b4f927f69b9e50b3286430c0639"
                    : "108af2997b108a05f8edc72a339705e77170061c042eb3b30857ff641b266968").equals(
                    UpdateCheck.sha256Hex(v027, 0, v027.length)) && plain27.ok && !plain27.compared
                    && Candidates.Kind.V027.fileNameFor(plain27.sha256).equals(plain27.cardName)
                    && Candidates.isReviewedPatch(v027) && Candidates.repairVersion(plain27.sha256) == Candidates.Kind.V027);
        }
        // The cleaned copies (the-dumping-grounds): each kind has a second reviewed file, the same code with only the
        // factory settings record and no stored photos; the withdrawn candidates already are that file.
        for (Candidates.Kind k : Candidates.Kind.values()) {
            expect(k + " matches both its files, and names each by its own name", k.matches(k.sha256)
                    && k.matches(k.cleanSha256) && k.fileName.equals(k.fileNameFor(k.sha256))
                    && k.cleanFileName.equals(k.fileNameFor(k.cleanSha256)) && !k.matches("0".repeat(64)));
            expect(k + "'s clean file is renamed only by its byte sum", k.cleanFileName.substring(0, 20)
                    .equals(k.fileName.substring(0, 20)));
        }
        expect("the restore file from the cleaned copy is candidate C's bytes",
                Candidates.Kind.RESTORE.cleanSha256.equals(Candidates.Kind.C.sha256));
        expect("the cleaned copies are public dumps, never a backup", Arrays.asList(Candidates.PUBLIC_DUMP_SHA256S)
                .containsAll(Arrays.asList(Candidates.CLEAN_STOCK_SHA256, Candidates.CLEAN_FACTORY_SHA256)));
        expect("the app downloads the cleaned copy from its repository at a fixed commit",
                Candidates.STOCK_DUMP_DOWNLOAD.startsWith("https://raw.githubusercontent.com/the-dumping-grounds/"
                        + "charmera-firmware-v006/") && Candidates.STOCK_DUMP_DOWNLOAD.endsWith(
                        "/charmera-v006-2025-10-14-clean.bin") && Candidates.STOCK_DUMP_DOWNLOAD.matches(".*/[0-9a-f]{40}/.*"));
        String film = System.getenv("CHARMERA_FILM_MODS_RESTORE");
        byte[] f = film == null || film.isEmpty() || !Files.isRegularFile(Paths.get(film)) ? null
                : Files.readAllBytes(Paths.get(film));
        if (f != null) {
            UpdateCheck.Result fr = UpdateCheck.check(named(f), f);
            expect("the film-mods build's vector table is compared with its own build's, by its layout",
                    level(fr, "vector-table") == UpdateCheck.Level.PASS && fr.ok);
            byte[] redated = f.clone();
            put(redated, 0xF3604, "Nov 20 2025");  // that build's only compile date, a day on
            UpdateCheck.Result dr = UpdateCheck.check(named(redated), redated);
            expect("the film-mods file with another compile date is a build laid out like that one, not that build with"
                    + " changes", level(dr, "build") == UpdateCheck.Level.WARN && text(dr, "build").contains("a build the"
                    + " app does not know, laid out like the charmera-film-mods author's build: its compile date is not"
                    + " that build's") && !text(dr, "build").contains("with changes"));
            byte[] changed = f.clone();
            changed[0x110000] ^= 1;
            UpdateCheck.Result cr = UpdateCheck.check(named(changed), changed);
            expect("while a change that keeps every build mark is that build with changes",
                    level(cr, "build") == UpdateCheck.Level.WARN && text(cr, "build").startsWith("the charmera-film-mods"
                    + " author's build (same stamp, different bytes, compiled Nov 19 2025), with changes"));
        }
        if (f != null && given != null && Candidates.isStockDump(given)) {
            // A backup laid out like the other known build: a broken vector table is still caught, by the known
            // build whose layout the file has, as it is without a backup.
            byte[] dump = Candidates.stockDump(given);
            byte[] filmChip = Arrays.copyOf(f, 0x200000);
            Arrays.fill(filmChip, f.length, filmChip.length, (byte) 0xFF);
            byte[] read = dump.clone();
            read[0x170100] ^= 1;                    // a read of a camera flashed with the restore file, one setting saved
            expect("the film-mods restore file padded to 2 MiB, and a near copy of the public dump, are taken as backups",
                    UpdateCheck.backupProblem(filmChip, Candidates.PUBLIC_DUMP_SHA256S) == null
                            && UpdateCheck.backupProblem(read, Candidates.PUBLIC_DUMP_SHA256S) == null);
            byte[] broken = Arrays.copyOf(dump, UpdateCheck.UPDATE_SIZE);
            byte[] brokenFilm = f.clone();
            for (int i = 0x200; i < 0x240; i++) {
                broken[i] ^= (byte) 0xFF;
                brokenFilm[i] ^= (byte) 0xFF;
            }
            UpdateCheck.Result vr = UpdateCheck.check(named(broken), broken, UpdateCheck.PUBLIC_BUILD, filmChip);
            expect("the restore file with its vector table destroyed fails against a film-mods backup",
                    !vr.ok && level(vr, "vector-table") == UpdateCheck.Level.FAIL
                            && vr.verdict().contains("Program start: the table the processor starts the program from"
                            + " differs from the one in the public v006 build"));
            vr = UpdateCheck.check(named(brokenFilm), brokenFilm, UpdateCheck.PUBLIC_BUILD, read);
            expect("and the film-mods file with its vector table destroyed fails against a public-build backup",
                    !vr.ok && level(vr, "vector-table") == UpdateCheck.Level.FAIL
                            && vr.verdict().contains("differs from the one in the charmera-film-mods author's build"));
            vr = UpdateCheck.check(named(f), f, UpdateCheck.PUBLIC_BUILD, read);
            expect("the film-mods file against a public-build backup: the same table in another program is a warning"
                    + " that says both", vr.ok && vr.compared && level(vr, "sensor-table") == UpdateCheck.Level.WARN
                    && level(vr, "vector-table") == UpdateCheck.Level.PASS
                    && vr.verdict().contains("the program around it differs from that firmware")
                    && vr.verdict().contains("That firmware is the public v006 build, unchanged"));
        }
        System.out.printf(Locale.ROOT, "CandidatesTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    static final int WAV8_DATA = 0x121E00 + 44;
    static final int WAV16_DATA = 0x123200 + 44;

    /** A 2 MiB image with two settings records (an erased sector first), a GP+ pack with two WAVs, and photos. */
    static byte[] synthetic() {
        byte[] d = new byte[0x200000];
        for (int i = 0; i < 0x120000; i++) {
            d[i] = (byte) (i * 31 + 7);
        }
        Arrays.fill(d, 0x120000, 0x200000, (byte) 0xFF);
        Arrays.fill(d, 0x120000, 0x120018, (byte) 0);
        put(d, 0x120000, "GP+");
        entry(d, 0, "IMGWAV", (0x121E00 - 0x120000) / 0x200);
        entry(d, 1, "BEEPWAV", (0x123200 - 0x120000) / 0x200);
        Arrays.fill(d, 0x120018 + 48, 0x120018 + 72, (byte) 0);
        wav(d, 0x121E00, 8, 1000);
        wav(d, 0x123200, 16, 1000);
        record(d, 0x170200, 5);      // sector 0 left erased: the first valid record is the second sector
        record(d, 0x170400, 3);
        Arrays.fill(d, 0x17F000, 0x17F200, (byte) 0);
        Arrays.fill(d, 0x180000, 0x181000, (byte) 0x55);
        return d;
    }

    static void entry(byte[] d, int i, String name, int sector) {
        int off = 0x120018 + 24 * i;
        Arrays.fill(d, off, off + 24, (byte) 0);
        put(d, off, name);
        d[off + 20] = (byte) sector;
        d[off + 21] = (byte) (sector >> 8);
    }

    static void wav(byte[] d, int off, int bits, int dataLen) {
        Arrays.fill(d, off, off + 44, (byte) 0);    // a real header: every field not set below is zero
        put(d, off, "RIFF");
        le32(d, off + 4, 36 + dataLen);
        put(d, off + 8, "WAVEfmt ");
        le32(d, off + 16, 16);
        d[off + 20] = 1;
        d[off + 22] = 1;
        le32(d, off + 24, 8000);
        d[off + 34] = (byte) bits;
        put(d, off + 36, "data");
        le32(d, off + 40, dataLen);
        for (int i = 0; i < dataLen; i++) {
            d[off + 44 + i] = (byte) (i * 13 + 1);
        }
    }

    static void record(byte[] d, int off, int uiSound) {
        Arrays.fill(d, off, off + 0x200, (byte) 0);
        d[off] = 1;
        d[off + 0x19] = (byte) uiSound;
        CRC32 crc = new CRC32();
        crc.update(d, off, 252);
        le32(d, off + 252, (int) crc.getValue());
    }

    static byte[] invokeTransform(byte[] img, Candidates.Kind kind) throws Exception {
        java.lang.reflect.Method m = Candidates.class.getDeclaredMethod("transform", byte[].class, Candidates.Kind.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, img, kind);
    }

    static boolean crcOk(byte[] d, int off) {
        CRC32 crc = new CRC32();
        crc.update(d, off, 252);
        long v = (d[off + 252] & 0xFFL) | (d[off + 253] & 0xFFL) << 8 | (d[off + 254] & 0xFFL) << 16 | (d[off + 255] & 0xFFL) << 24;
        return crc.getValue() == v;
    }

    static boolean allEqual(byte[] d, int from, int to, int value) {
        for (int i = from; i < to; i++) {
            if ((d[i] & 0xFF) != value) {
                return false;
            }
        }
        return true;
    }

    static void put(byte[] d, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            d[off + i] = (byte) s.charAt(i);
        }
    }

    /** A copy of d with every from replaced by to, which has the same length. */
    static byte[] replaced(byte[] d, String from, String to) {
        byte[] f = from.getBytes(StandardCharsets.US_ASCII);
        byte[] out = d.clone();
        for (int i = 0; i + f.length <= out.length; i++) {
            if (out[i] == f[0] && Arrays.equals(Arrays.copyOfRange(out, i, i + f.length), f)) {
                put(out, i, to);
            }
        }
        return out;
    }

    static void le32(byte[] d, int off, int v) {
        for (int i = 0; i < 4; i++) {
            d[off + i] = (byte) (v >>> (8 * i));
        }
    }

    interface Call {
        void run() throws Exception;
    }

    /** The level of the first line with that check name, or null. */
    static UpdateCheck.Level level(UpdateCheck.Result r, String name) {
        for (UpdateCheck.Line l : r.lines) {
            if (l.name.equals(name)) {
                return l.level;
            }
        }
        return null;
    }

    /** The plain text of the first line with that check name, or "". */
    static String text(UpdateCheck.Result r, String name) {
        for (UpdateCheck.Line l : r.lines) {
            if (l.name.equals(name)) {
                return l.text;
            }
        }
        return "";
    }

    /** An update file's name for d: a stamp no known build carries, and d's own sum. */
    static String named(byte[] d) {
        return String.format(Locale.ROOT, "JH_5408202611061300_%08X.bin", UpdateCheck.byteSum(d));
    }

    static boolean refused(Call c, String message) {
        try {
            c.run();
            return false;
        } catch (Exception e) {
            Throwable t = e.getCause() != null && e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
            return String.valueOf(t.getMessage()).contains(message);
        }
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
