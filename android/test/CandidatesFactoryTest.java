package io.github.daemonddev.charmera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Plain-JVM tests for the Repair versions made from the owner's factory dump (Candidates.Kind.V025, V026), on
 * synthetic images only: the dump itself is private and is used only when CHARMERA_FACTORY_DUMP points to it
 * (CandidatesTest, WriteGateTest). Covers the SHA-256 gate on the input, the factory recipe and its copy ranges (the
 * mechanism a later version uses for vendor bytes the app must not ship), the menu's versions and its latest-available
 * choice, the reviewed-patch rule and the check that turns "unknown build" failures into warnings for those exact bytes.
 * In the app's package, because Layer, factoryImage and the Result constructor are package-private.
 */
public final class CandidatesFactoryTest {

    private static int passes = 0;
    private static int failures = 0;

    public static void main(String[] args) {
        byte[] img = new byte[Candidates.DUMP_SIZE];
        for (int i = 0; i < img.length; i++) {
            img[i] = (byte) (i * 37 + 11);
        }

        // The SHA-256 gate on the input: only the factory dump makes a factory kind.
        expect("a synthetic 2 MiB image is not the factory dump", !Candidates.isFactoryDump(img)
                && refused(() -> Candidates.factoryDump(img), "not the factory dump"));
        expect("build() refuses a factory kind from any other dump, before making anything",
                refused(() -> Candidates.build(img, Candidates.Kind.V025), "not the factory dump"));
        expect("a short file is not the factory dump", !Candidates.isFactoryDump(new byte[10])
                && refused(() -> Candidates.factoryDump(new byte[10]), Candidates.FACTORY_DUMP_NAME));
        expect("the refusal names the dump's SHA-256 on its Detail line",
                refused(() -> Candidates.factoryDump(img), "\nDetail: its SHA-256 is "));

        // The v0.25 recipe on a synthetic image: the first 0x1E0000 bytes, photo slots blanked, the authored bytes.
        byte[] v025 = Candidates.transform(img, Candidates.Kind.V025);
        expect("v0.25 is an update file's size", v025.length == UpdateCheck.UPDATE_SIZE);
        expect("its photo slots are blanked", all(v025, Candidates.FACTORY_PHOTOS_START, Candidates.FACTORY_PHOTOS_END,
                0xFF));
        boolean patched = true;
        int authored = 0;
        boolean[] touched = new boolean[v025.length];
        for (int i = 0; i < Candidates.V025_OFFSETS.length; i++) {
            int off = Candidates.V025_OFFSETS[i];
            byte[] b = Candidates.V025_BYTES[i];
            patched &= Arrays.equals(Arrays.copyOfRange(v025, off, off + b.length), b);
            for (int j = 0; j < b.length; j++) {
                touched[off + j] = true;
            }
            authored += b.length;
        }
        expect("every v0.25 patch is in place: 172 authored bytes in 36 runs", patched && authored == 172
                && Candidates.V025_OFFSETS.length == 36);
        boolean rest = true;
        for (int i = 0; i < v025.length; i++) {
            boolean photos = i >= Candidates.FACTORY_PHOTOS_START && i < Candidates.FACTORY_PHOTOS_END;
            if (!photos && !touched[i] && v025[i] != img[i]) {
                rest = false;
            }
        }
        expect("every other byte is the dump's", rest);
        expect("no v0.25 patch lies in the photo slots or past the update file", outside(Candidates.V025_OFFSETS,
                Candidates.V025_BYTES));
        expect("a synthetic v0.25 never passes build()'s output check either", Candidates.Kind.V025.sha256.length() == 64
                && !Candidates.Kind.V025.sha256.equals(UpdateCheck.sha256Hex(v025, 0, v025.length)));

        // Copy ranges: bytes taken at run time from the factory dump, then the layer's own patches over them.
        Candidates.Layer copy = new Candidates.Layer(new int[][]{{0x1000, 0x50000, 16}},
                new int[]{0x50004}, new byte[][]{{(byte) 0xAA, (byte) 0xBB}});
        byte[] c = Candidates.factoryImage(img, copy);
        byte[] want = Arrays.copyOfRange(img, 0x1000, 0x1010);
        want[4] = (byte) 0xAA;
        want[5] = (byte) 0xBB;
        expect("a copy range lands at its target, with the layer's patches over it",
                Arrays.equals(Arrays.copyOfRange(c, 0x50000, 0x50010), want));
        expect("a copy leaves its source alone and the bytes around the target", c[0x1000] == img[0x1000]
                && c[0x4FFFF] == img[0x4FFFF] && c[0x50010] == img[0x50010]);
        // A later layer copies from the factory dump itself, never from what an earlier layer wrote: v0.26 on v0.25.
        Candidates.Layer earlier = new Candidates.Layer(new int[0][], new int[]{0x2000}, new byte[][]{{1, 2, 3, 4}});
        Candidates.Layer later = new Candidates.Layer(new int[][]{{0x2000, 0x60000, 4}}, new int[0], new byte[0][]);
        byte[] l2 = Candidates.factoryImage(img, earlier, later);
        expect("layers apply in order, and a later copy reads the dump, not an earlier layer's patch",
                l2[0x2000] == 1 && l2[0x2003] == 4 && Arrays.equals(Arrays.copyOfRange(l2, 0x60000, 0x60004),
                        Arrays.copyOfRange(img, 0x2000, 0x2004)));
        expect("v0.25's own layer copies nothing", Candidates.V025_LAYER.copies.length == 0
                && Arrays.equals(Candidates.factoryImage(img, Candidates.V025_LAYER), v025));
        expect("a copy range past the dump is refused", refused(() -> Candidates.factoryImage(img,
                new Candidates.Layer(new int[][]{{Candidates.DUMP_SIZE - 4, 0x1000, 8}}, new int[0], new byte[0][])),
                "outside"));
        expect("a copy range past the update file is refused", refused(() -> Candidates.factoryImage(img,
                new Candidates.Layer(new int[][]{{0x1000, UpdateCheck.UPDATE_SIZE - 4, 8}}, new int[0], new byte[0][])),
                "outside"));
        expect("a copy range of no bytes is refused", refused(() -> Candidates.factoryImage(img,
                new Candidates.Layer(new int[][]{{0x1000, 0x2000, 0}}, new int[0], new byte[0][])), "outside"));
        expect("a patch past the update file is refused", refused(() -> Candidates.factoryImage(img,
                new Candidates.Layer(new int[0][], new int[]{UpdateCheck.UPDATE_SIZE - 1}, new byte[][]{{1, 2}})),
                "outside"));
        expect("a patch table needs one offset per entry", refused(() -> new Candidates.Layer(new int[0][],
                new int[]{1, 2}, new byte[][]{{1}}), "one offset per entry"));
        expect("only factory kinds have layers", refused(() -> Candidates.layersOf(Candidates.Kind.BRINGUP),
                "not made from the factory dump"));

        // v0.26 = v0.25's layer, then its own: one copy of the vendor template (from the dump), then 332 authored bytes.
        expect("v0.26 is v0.25's layer plus its own", Arrays.equals(Candidates.layersOf(Candidates.Kind.V026),
                new Candidates.Layer[]{Candidates.V025_LAYER, Candidates.V026_LAYER}));
        expect("v0.27 is v0.26's layers plus its own", Arrays.equals(Candidates.layersOf(Candidates.Kind.V027),
                new Candidates.Layer[]{Candidates.V025_LAYER, Candidates.V026_LAYER, Candidates.V027_LAYER}));
        int v027bytes = 0;
        for (byte[] b : Candidates.V027_BYTES) {
            v027bytes += b.length;
        }
        expect("v0.27's own layer is 202 authored bytes in 24 runs, nothing copied",
                v027bytes == 202 && Candidates.V027_OFFSETS.length == 24 && Candidates.V027_LAYER.copies.length == 0);
        byte[] v026i = Candidates.transform(img, Candidates.Kind.V026);
        byte[] v027i = Candidates.transform(img, Candidates.Kind.V027);
        int d27 = 0;
        for (int i = 0; i < v027i.length; i++) {
            if (v027i[i] != v026i[i]) {
                d27++;
            }
        }
        expect("v0.27 differs from v0.26 in at most its 202 bytes", d27 <= 202 && d27 > 150);
        expect("v0.26 copies exactly one range: the v012 still template from SOF0 on, 0x26E bytes",
                Candidates.V026_LAYER.copies.length == 1 && Arrays.equals(Candidates.V026_COPIES[0],
                        new int[]{0x10753A, 0x89712, 0x26E}));
        byte[] v026 = Candidates.transform(img, Candidates.Kind.V026);
        int[] cp = Candidates.V026_COPIES[0];
        expect("the copied range comes from the dump", Arrays.equals(Arrays.copyOfRange(v026, cp[1], cp[1] + cp[2]),
                Arrays.copyOfRange(img, cp[0], cp[0] + cp[2])));
        boolean patched26 = true;
        int authored26 = 0;
        boolean[] touched26 = touched.clone();
        for (int i = 0; i < Candidates.V026_OFFSETS.length; i++) {
            int off = Candidates.V026_OFFSETS[i];
            byte[] b = Candidates.V026_BYTES[i];
            patched26 &= Arrays.equals(Arrays.copyOfRange(v026, off, off + b.length), b);
            for (int j = 0; j < b.length; j++) {
                patched26 &= off + j < cp[1] || off + j >= cp[1] + cp[2];   // no authored byte over the copy
                touched26[off + j] = true;
            }
            authored26 += b.length;
        }
        for (int j = cp[1]; j < cp[1] + cp[2]; j++) {
            touched26[j] = true;
        }
        expect("every v0.26 patch is in place, none over the copy: 332 authored bytes in 45 runs", patched26
                && authored26 == 332 && Candidates.V026_OFFSETS.length == 45);
        boolean v025Kept = true;
        for (int i = 0; i < Candidates.V025_OFFSETS.length; i++) {
            int off = Candidates.V025_OFFSETS[i];
            byte[] b = Candidates.V025_BYTES[i];
            v025Kept &= Arrays.equals(Arrays.copyOfRange(v026, off, off + b.length), b);
        }
        expect("v0.25's patches stay in v0.26", v025Kept);
        boolean rest26 = true;
        for (int i = 0; i < v026.length; i++) {
            boolean photos = i >= Candidates.FACTORY_PHOTOS_START && i < Candidates.FACTORY_PHOTOS_END;
            if (!photos && !touched26[i] && v026[i] != img[i]) {
                rest26 = false;
            }
        }
        expect("every other v0.26 byte is the dump's", rest26 && all(v026, Candidates.FACTORY_PHOTOS_START,
                Candidates.FACTORY_PHOTOS_END, 0xFF));
        expect("no v0.26 patch lies in the photo slots or past the update file", outside(Candidates.V026_OFFSETS,
                Candidates.V026_BYTES));
        expect("build() refuses v0.26 from any other dump", refused(() -> Candidates.build(img, Candidates.Kind.V026),
                "not the factory dump"));

        // The fixed Repair kinds (the builds older apps numbered v0.27 to v0.24), newest first.
        Candidates.Kind[] versions = Candidates.repairVersions();
        expect("the fixed Repair kinds are V027, V026, V025 then BRINGUP", Arrays.equals(versions, new Candidates.Kind[]{
                Candidates.Kind.V027, Candidates.Kind.V026, Candidates.Kind.V025, Candidates.Kind.BRINGUP}));
        boolean newestFirst = true;
        for (int i = 1; i < versions.length; i++) {
            newestFirst &= versions[i - 1].fileName.compareTo(versions[i].fileName) > 0;
        }
        expect("newest first, by the stamp in their file names", newestFirst);
        expect("each fixed kind is named by its base and mods", "v012 with Volume and LED rows and EXIF dates".equals(
                Candidates.Kind.V026.firmwareName()) && "v012 with Volume and LED rows".equals(
                Candidates.Kind.V025.firmwareName()) && "v006 with the OV9732 port and Volume and LED rows".equals(
                Candidates.Kind.BRINGUP.firmwareName()) && Candidates.Kind.BRINGUP.repair()
                && !Candidates.Kind.RESTORE.repair() && !Candidates.Kind.PROBE.repair());
        boolean noNumber = true;
        for (Candidates.Kind k : Candidates.Kind.values()) {
            noNumber &= !k.label.contains("v0.") && !k.firmwareName().contains("v0.");
        }
        expect("no kind's words name a build by a number", noNumber);
        expect("v0.26 and v0.25 are made from the factory dump, v0.24 from the stock dump", Candidates.Kind.V026.base
                == Candidates.Base.FACTORY && Candidates.Kind.V025.base == Candidates.Base.FACTORY
                && Candidates.Kind.BRINGUP.base == Candidates.Base.STOCK);
        boolean plainLabels = true;
        for (Candidates.Kind k : versions) {
            // A Repair pick is described from the recipes with its way back, so its kind's label only says what the
            // build is: nothing about whose dump it was made from, and no way back that could go stale.
            plainLabels &= !k.label.contains("your factory dump") && !k.label.contains("read off its chip")
                    && !k.label.contains("this camera's own") && !k.label.contains("way back");
        }
        expect("the Repair kinds' labels say what the build is, and nothing about one camera's dump", plainLabels);
        expect("the factory dump downloads from its repository at a fixed commit",
                Candidates.CLEAN_FACTORY_DOWNLOAD.startsWith("https://raw.githubusercontent.com/the-dumping-grounds/"
                        + "charmera-firmware-v012/") && Candidates.CLEAN_FACTORY_DOWNLOAD.endsWith("/"
                        + Candidates.CLEAN_FACTORY_NAME) && Candidates.CLEAN_FACTORY_DOWNLOAD.matches(".*/[0-9a-f]{40}/.*"));

        // Every fixed Repair kind belongs to the Repair route and is a reviewed patch (WriteGate's waiver), by SHA-256.
        for (Candidates.Kind k : versions) {
            expect(k + " is found by its SHA-256 and is a reviewed patch", Candidates.repairVersion(k.sha256) == k
                    && Candidates.isReviewedPatch(k.sha256));
        }
        expect("nothing else is a Repair version", Candidates.repairVersion(Candidates.Kind.RESTORE.sha256) == null
                && Candidates.repairVersion(Candidates.Kind.PROBE.sha256) == null && Candidates.repairVersion(null) == null
                && Candidates.isReviewedPatch(Candidates.Kind.PROBE.sha256)
                && Candidates.isReviewedPatch(Candidates.Kind.RESTORE.sha256) && !Candidates.isRepairFile(Candidates.Kind.RESTORE.sha256)
                && Candidates.isReviewedPatch(Candidates.Kind.C.sha256) == (Recipe.reviewed(Candidates.Kind.C.sha256) != null)
                && !Candidates.isReviewedPatch(Candidates.Kind.A.sha256) && !Candidates.isReviewedPatch(Candidates.Kind.B.sha256));

        // A factory kind's exact bytes: "unknown build" failures become warnings; every other failure stands.
        List<UpdateCheck.Line> lines = new ArrayList<>();
        lines.add(new UpdateCheck.Line(UpdateCheck.Level.PASS, "name", "Name", "fine.", ""));
        lines.add(new UpdateCheck.Line(UpdateCheck.Level.FAIL, "series", "Build marks", "no sensor table.", "x"));
        UpdateCheck.Result marks = new UpdateCheck.Result(lines, Candidates.Kind.V025.fileName,
                Candidates.Kind.V025.sha256, UpdateCheck.UPDATE_SIZE, false, null);
        UpdateCheck.Result soft = Candidates.asFactory(marks, Candidates.Kind.V025);
        expect("unknown build marks are a warning for v0.25, which then passes", !marks.ok && soft.ok
                && soft.lines.get(1).level == UpdateCheck.Level.WARN && soft.lines.get(1).text.contains("v012 with Volume and LED rows")
                && soft.lines.get(1).detail.contains("no sensor table.") && soft.lines.get(0).level == UpdateCheck.Level.PASS
                && soft.sha256.equals(marks.sha256) && !soft.compared && soft.referenceSha256 == null);
        List<UpdateCheck.Line> sum = new ArrayList<>(lines);
        sum.add(new UpdateCheck.Line(UpdateCheck.Level.FAIL, "checksum", "Sum", "wrong.", ""));
        UpdateCheck.Result bad = Candidates.asFactory(new UpdateCheck.Result(sum, Candidates.Kind.V025.fileName,
                Candidates.Kind.V025.sha256, UpdateCheck.UPDATE_SIZE, false, null), Candidates.Kind.V025);
        expect("any other failure still refuses it", !bad.ok && bad.lines.get(2).level == UpdateCheck.Level.FAIL);
        UpdateCheck.Result synthetic = Candidates.check(Candidates.Kind.V025.fileName, v025, null);
        expect("Candidates.check softens nothing for bytes that are not exactly a factory kind's",
                sameLevels(synthetic, UpdateCheck.check(Candidates.Kind.V025.fileName, v025)));
        expect("and a synthetic v0.25 is refused", !synthetic.ok);

        System.out.printf(Locale.ROOT, "CandidatesFactoryTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    static boolean sameLevels(UpdateCheck.Result a, UpdateCheck.Result b) {
        if (a.lines.size() != b.lines.size()) {
            return false;
        }
        for (int i = 0; i < a.lines.size(); i++) {
            if (a.lines.get(i).level != b.lines.get(i).level) {
                return false;
            }
        }
        return true;
    }

    static boolean outside(int[] offsets, byte[][] bytes) {
        for (int i = 0; i < offsets.length; i++) {
            int end = offsets[i] + bytes[i].length;
            if (end > UpdateCheck.UPDATE_SIZE || end > Candidates.FACTORY_PHOTOS_START
                    && offsets[i] < Candidates.FACTORY_PHOTOS_END) {
                return false;
            }
        }
        return true;
    }

    static boolean all(byte[] d, int from, int to, int value) {
        for (int i = from; i < to; i++) {
            if ((d[i] & 0xFF) != value) {
                return false;
            }
        }
        return true;
    }

    interface Call {
        void run() throws Exception;
    }

    static boolean refused(Call c, String message) {
        try {
            c.run();
            return false;
        } catch (Exception e) {
            return String.valueOf(e.getMessage()).contains(message);
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
