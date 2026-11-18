package io.github.daemonddev.charmera;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Recipe: the app's own copy of charmera_toolkit/recipes.py. Every reviewed file of the builds table is made again
 * from the dumps named in the environment (CHARMERA_STOCK_IMAGE: v006, CHARMERA_FACTORY_DUMP: v012, and for v008_test
 * CHARMERA_V008_DUMP or, when it is unset, CHARMERA_FILM_MODS_RESTORE, which CI sets; each skipped when absent), the
 * conflicts and the refusals hold, and each fixed Candidates.Kind the app made before is the same file Recipe.build
 * makes of its set.
 */
public class RecipeTest {
    static int passes;
    static int failures;
    static int skipped;

    static void expect(String what, boolean ok) {
        if (ok) {
            passes++;
        } else {
            failures++;
            System.out.println("FAILED: " + what);
        }
    }

    static boolean throwsOn(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    static String messageOf(Runnable r) {
        try {
            r.run();
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    static byte[] read(String env) {
        String path = System.getenv(env);
        if (path == null || path.isEmpty() || !Files.isRegularFile(Paths.get(path))) {
            return null;
        }
        try {
            return Files.readAllBytes(Paths.get(path));
        } catch (IOException e) {
            return null;
        }
    }

    static String sha(byte[] b) {
        return UpdateCheck.sha256Hex(b, 0, b.length);
    }

    public static void main(String[] args) {
        // the selection value: table order, labels and statuses from SETS, serialized and read back
        Recipe.Selection v026 = new Recipe.Selection("v012", "v012/exif", "v012/volume-led");
        expect("mods are kept in the table's order", v026.csv().equals("v012/volume-led,v012/exif"));
        expect("a set's label and status come from SETS", v026.label().startsWith("v0.26:")
                && "tested".equals(v026.status()));
        expect("an experimental set says so", "experimental".equals(
                new Recipe.Selection("v012", "v012/capture-flash").status()));
        expect("serialize and parse are inverse", v026.equals(Recipe.parse(v026.serialize()))
                && Recipe.parse("v012|").equals(new Recipe.Selection("v012")) && Recipe.parse("nonsense") == null
                && Recipe.parse("v999|") == null);
        expect("with() ticks and unticks", v026.with("v012/exif", false).equals(new Recipe.Selection("v012",
                "v012/volume-led")) && v026.with("v012/capture-flash", true).has("v012/capture-flash"));
        expect("mods(base) lists each base's mods in order", Arrays.equals(Recipe.mods("v006"), new String[]{
                "v006/sensor-probe", "v006/ov9732-port", "v006/volume-led"}) && Recipe.mods("v012").length == 4
                && Recipe.mods("v008_test").length == 0);
        expect("dump ids per base, original first", Arrays.equals(Recipe.dumpIds("v012"),
                new String[]{"v012-original", "v012-clean"}) && Arrays.equals(Recipe.dumpIds("v008_test"),
                new String[]{"v008_test"}));
        expect("the download of each base is its public copy", Recipe.download("v006")[0].equals("v006-clean")
                && Recipe.download("v012")[0].equals("v012-clean") && Recipe.download("v008_test")[0].equals("v008_test"));

        // conflicts and requires
        Recipe.Selection probe = new Recipe.Selection("v006", "v006/sensor-probe");
        expect("the probe conflicts with the port and the rows, both ways",
                "v006/sensor-probe".equals(Recipe.conflict(probe, "v006/ov9732-port"))
                        && "v006/sensor-probe".equals(Recipe.conflict(probe, "v006/volume-led"))
                        && "v006/ov9732-port".equals(Recipe.conflict(new Recipe.Selection("v006", "v006/ov9732-port"),
                        "v006/sensor-probe")));
        expect("v012's mods do not conflict", Recipe.conflict(v026, "v012/capture-flash") == null);
        String clash = Recipe.problem(new Recipe.Selection("v006", "v006/sensor-probe", "v006/ov9732-port"));
        expect("a conflicting pick is refused by name", clash != null && clash.contains("cannot go with"));
        expect("a mod of another base is refused", Recipe.problem(new Recipe.Selection("v006", "v012/exif")) != null);
        expect("an unknown mod is refused", Recipe.problem(new Recipe.Selection("v012", "v012/nonsense")) != null);
        expect("every SETS row is a pick with no problem", allSetsFine());
        expect("nothing requires anything today, so missing() is null", Recipe.missing(v026, "v012/exif") == null);

        // trust: every reviewed build is written without a backup; the ones that drive the OV9732 (and the probe) are
        // checked with none, the SP140A ones are compared with a backup when one is on record
        boolean trust = true;
        for (String[] b : Recipes.BUILDS) {
            boolean ov = b[0].equals("v012") || b[0].equals("v006")
                    && Arrays.asList(b[1].split(",")).contains("v006/ov9732-port");
            boolean probeRow = b[0].equals("v006") && b[1].equals("v006/sensor-probe");
            boolean restoreRow = b[0].equals("v006") && b[1].isEmpty();
            Recipe.Selection sel = Recipe.selectionOf(b[4]);
            boolean row = Candidates.isReviewedPatch(b[4]) && Candidates.checkedAlone(b[4]) == (ov || probeRow)
                    && Recipe.drivesOv9732(sel) == ov
                    && b[3].equals(Recipe.fileNameFor(b[4]))
                    && sel.serialize().equals(b[0] + "|" + b[1])
                    && Candidates.isRepairFile(b[4]) == !(probeRow || restoreRow);
            if (!row) {
                System.out.println("  backup waiver wrong for " + b[0] + "|" + b[1] + " from " + b[2]);
            }
            trust &= row;
        }
        expect("every reviewed build needs no backup; only the SP140A ones are compared with one", trust);
        expect("unconfirmed(): a warning with no backup and no Identify answer, none once either confirms it",
                Recipe.unconfirmed(v026, false, null) != null && Recipe.unconfirmed(v026, false, null).contains("OV9732")
                        && Recipe.unconfirmed(v026, true, null) == null
                        && Recipe.unconfirmed(v026, false, "v012") == null
                        && Recipe.unconfirmed(v026, false, "v006-family").startsWith("Wrong sensor?")
                        && Recipe.unconfirmed(new Recipe.Selection("v006"), false, "v012").startsWith("Wrong sensor?")
                        && Recipe.unconfirmed(probe, false, null) == null);
        // a set marked experimental whose mods are each tested alone is still an untried combination
        Recipe.Selection rowsOnly = new Recipe.Selection("v006", "v006/volume-led");
        expect("an experimental set of tested mods is an untried combination, and describe() says so",
                !Recipe.experimental("v006/volume-led") && "experimental".equals(rowsOnly.status())
                        && Recipe.untriedCombination(rowsOnly) && Recipe.describe(rowsOnly).contains("Experimental")
                        && !Recipe.untriedCombination(v026)
                        && !Recipe.untriedCombination(new Recipe.Selection("v012", "v012/capture-flash")));
        expect("describe() names SP140A firmware and the factory firmware, and asks for no backup",
                Recipe.describe(rowsOnly).contains("SP140A firmware")
                        && !Recipe.describe(rowsOnly).contains("backup")
                        && Recipe.describe(new Recipe.Selection("v012")).contains("factory firmware of OV9732 cameras"));
        expect("the restore file is a reviewed file, compared with a backup when one is on record",
                Candidates.isReviewedPatch(Candidates.Kind.RESTORE.sha256)
                        && Candidates.isReviewedPatch(Candidates.Kind.RESTORE.cleanSha256)
                        && !Candidates.checkedAlone(Candidates.Kind.RESTORE.sha256)
                        && !Candidates.isRepairFile(Candidates.Kind.RESTORE.sha256));
        expect("withdrawn A and B are not reviewed builds", Recipe.reviewed(Candidates.Kind.A.sha256) == null
                && Recipe.reviewed(Candidates.Kind.B.sha256) == null && !Candidates.isReviewedPatch(Candidates.Kind.A.sha256));
        expect("an unknown SHA-256 is no build", Recipe.reviewed("00") == null && Recipe.selectionOf(null) == null
                && Recipe.fileNameFor("ab") == null);

        // the photo header each build should give
        expect("EXIF mod -> factory EXIF header", Recipe.expectedPhoto(new Recipe.Selection("v012", "v012/exif",
                "v012/capture-flash")) == PhotoId.Kind.FACTORY_EXIF);
        expect("v012 without EXIF -> bare factory header", Recipe.expectedPhoto(new Recipe.Selection("v012",
                "v012/volume-led")) == PhotoId.Kind.ORIGINAL_BARE && Recipe.expectedPhoto(new Recipe.Selection("v012"))
                == PhotoId.Kind.ORIGINAL_BARE);
        expect("v006 and v008_test -> the public family", Recipe.expectedPhoto(probe) == PhotoId.Kind.PUBLIC_FAMILY
                && Recipe.expectedPhoto(new Recipe.Selection("v008_test")) == PhotoId.Kind.PUBLIC_FAMILY);
        boolean same = true;
        for (Candidates.Kind k : Candidates.repairVersions()) {
            same &= Recipe.expectedPhoto(Recipe.of(k)) == PhotoId.expectedFor(k);
        }
        expect("for every preset, the same header PhotoId.expectedFor names", same);

        // presets and the remembered pick, with the migration from the old Kind names
        Recipe.Selection[] presets = Recipe.presets();
        boolean presetsOk = presets.length == Candidates.repairVersions().length;
        for (int i = 0; i < presets.length && presetsOk; i++) {
            Candidates.Kind k = Candidates.repairVersions()[i];
            presetsOk = presets[i].kind() == k && k.version.equals(presets[i].version()) && presets[i].matches(k.sha256)
                    && presets[i].matches(k.cleanSha256);
        }
        expect("each preset is the set of its version, both copies", presetsOk);
        expect("the legacy kinds are sets", Recipe.of(Candidates.Kind.RESTORE).serialize().equals("v006|")
                && Recipe.of(Candidates.Kind.PROBE).serialize().equals("v006|v006/sensor-probe")
                && Recipe.of(Candidates.Kind.BRINGUP).serialize().equals("v006|v006/ov9732-port,v006/volume-led"));
        expect("a custom set has no version and is named by its label",
                new Recipe.Selection("v012", "v012/exif").version() == null
                        && new Recipe.Selection("v012", "v012/exif").name().equals("v012 + EXIF dates"));
        expect("a remembered set is used", Recipe.remembered("v012|v012/exif", "V025")
                .equals(new Recipe.Selection("v012", "v012/exif")));
        expect("old Kind names migrate to their sets", Recipe.remembered(null, "V026").equals(Recipe.of(Candidates.Kind.V026))
                && Recipe.remembered(null, "V025").equals(Recipe.of(Candidates.Kind.V025))
                && Recipe.remembered(null, "V027").equals(Recipe.of(Candidates.Kind.V027))
                && Recipe.remembered(null, "BRINGUP").equals(Recipe.of(Candidates.Kind.BRINGUP)));
        expect("nothing, or nothing valid, remembered -> the newest preset", Recipe.remembered(null, null)
                .equals(presets[0]) && Recipe.remembered("v006|v006/sensor-probe,v006/ov9732-port", "PROBE")
                .equals(presets[0]) && presets[0].kind() == Candidates.Kind.V027);

        // refusals of build() that need no dump
        byte[] junk = new byte[0x200000];
        String m = messageOf(() -> Recipe.build(junk, v026));
        expect("a file that is no dump is refused, with a Detail line", m != null && m.startsWith("the file is not a dump")
                && m.contains("\nDetail: its SHA-256 is "));

        // every build from the dumps on hand
        Map<String, byte[]> dumps = new HashMap<>();
        put(dumps, read("CHARMERA_STOCK_IMAGE"));
        put(dumps, read("CHARMERA_FACTORY_DUMP"));
        byte[] v008 = read("CHARMERA_V008_DUMP");
        put(dumps, v008 != null ? v008 : read("CHARMERA_FILM_MODS_RESTORE"));    // CI sets the second
        int rebuilt = 0;
        for (String[] b : Recipes.BUILDS) {
            byte[] dump = dumps.get(b[2]);
            if (dump == null) {
                skipped++;
                continue;
            }
            Recipe.Selection s = Recipe.selectionOf(b[4]);
            byte[] out;
            try {
                out = Recipe.build(dump, s);
            } catch (RuntimeException e) {
                expect("build " + b[0] + " " + b[1] + " from " + b[2] + ": " + e.getMessage(), false);
                continue;
            }
            expect("rebuilt " + b[3] + " from " + b[2], sha(out).equals(b[4]) && b[3].equals(Recipe.fileNameFor(sha(out))));
            rebuilt++;
        }
        System.out.println("  rebuilt " + rebuilt + " of " + Recipes.BUILDS.length + " builds (" + skipped
                + " skipped: their dump is not in the environment)");

        for (byte[] dump : dumps.values()) {
            String[] entry = Recipe.dumpFor(dump);
            // an unlisted result: a pick that is not a set, and a dump of another base
            Recipe.Selection other = entry[1].equals("v012") ? new Recipe.Selection("v006", "v006/ov9732-port")
                    : new Recipe.Selection("v012", "v012/exif");
            expect("a dump of another base is refused (" + entry[0] + ")", throwsOn(() -> Recipe.build(dump, other)));
            if (entry[1].equals("v006")) {
                String why = messageOf(() -> Recipe.build(dump, new Recipe.Selection("v006", "v006/sensor-probe",
                        "v006/volume-led")));
                expect("a pick that is not a reviewed set is refused (conflict)", why != null && why.contains("cannot go with"));
            }
            // a result that is not the listed file: the dump with one byte outside every mod changed
            if (!entry[3].equals("as-is")) {
                byte[] bent = dump.clone();
                bent[0x1000] ^= 1;
                expect("bytes that are not a listed dump are refused (" + entry[0] + ")",
                        throwsOn(() -> Recipe.build(bent, Recipe.selectionOf(entry[1].equals("v012")
                                ? Candidates.Kind.V026.sha256 : Candidates.Kind.BRINGUP.sha256))));
                byte[] unlisted = Recipe.compose(dump, entry, new Recipe.Selection(entry[1]));
                unlisted[0x2000] ^= 1;
                expect("compose of a changed image is not a listed build", Recipe.reviewed(sha(unlisted)) == null);
            }
            // each legacy Kind of this base: the same file Candidates.build made, and Recipe.build makes it
            for (Candidates.Kind k : Recipe.LEGACY) {
                boolean factory = k.base == Candidates.Base.FACTORY;
                if (factory != entry[1].equals("v012") || entry[1].equals("v008_test")) {
                    continue;
                }
                byte[] old = Candidates.build(dump, k);
                byte[] now = Recipe.build(dump, Recipe.of(k));
                expect(k + " from " + entry[0] + " is the same file both ways", Arrays.equals(old, now)
                        && k.fileNameFor(sha(old)).equals(Recipe.fileNameFor(sha(now))));
            }
        }

        // the refusal style, with a mod table that no longer fits: a copy whose old bytes differ
        if (dumps.containsKey("v012-clean") || dumps.containsKey("v012-original")) {
            byte[] dump = dumps.containsKey("v012-clean") ? dumps.get("v012-clean") : dumps.get("v012-original");
            byte[] out = Recipe.prepare(dump, Recipe.dumpFor(dump));
            int exif = Recipe.modIndex("v012/exif");
            Recipe.apply(out, dump, exif);
            String twice = messageOf(() -> Recipe.apply(out, dump, exif));
            expect("a mod is applied only over the bytes it was written for", twice != null
                    && twice.contains("are not the ones this mod replaces"));
        }

        System.out.printf(Locale.ROOT, "RecipeTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    static void put(Map<String, byte[]> dumps, byte[] data) {
        if (data == null) {
            return;
        }
        String[] e = Recipe.dumpFor(data);
        if (e == null) {
            System.out.println("  not a known dump, skipped: SHA-256 " + sha(data));
            return;
        }
        dumps.put(e[0], data);
    }

    static boolean allSetsFine() {
        for (String[] s : Recipes.SETS) {
            if (Recipe.problem(new Recipe.Selection(s[0], s[1].isEmpty() ? new String[0] : s[1].split(","))) != null) {
                return false;
            }
        }
        return true;
    }
}
