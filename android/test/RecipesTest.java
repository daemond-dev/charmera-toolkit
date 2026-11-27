package io.github.daemonddev.charmera;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Recipes is generated data (tools/gen_recipes.py from charmera_toolkit/recipes.json); this holds its shape, and that
 * the reviewed files it lists are the ones Candidates pins for the versions the app makes today.
 */
public class RecipesTest {
    static int passes;
    static int failures;

    static void expect(String what, boolean ok) {
        if (ok) {
            passes++;
        } else {
            failures++;
            System.out.println("FAILED: " + what);
        }
    }

    static boolean hexOk(String s) {
        return s.length() % 2 == 0 && s.matches("[0-9a-f]*");
    }

    public static void main(String[] args) {
        int n = Recipes.MOD_IDS.length;
        expect("every per-mod table has one entry per mod", Recipes.MOD_BASES.length == n && Recipes.MOD_NAMES.length == n
                && Recipes.MOD_TITLES.length == n && Recipes.MOD_STATUS.length == n
                && Recipes.MOD_DESCRIPTIONS.length == n && Recipes.MOD_CONFLICTS.length == n
                && Recipes.MOD_REQUIRES.length == n && Recipes.MOD_COPIES.length == n && Recipes.MOD_COPY_OLD.length == n
                && Recipes.MOD_PATCH_OFFSETS.length == n && Recipes.MOD_PATCH_BYTES.length == n
                && Recipes.MOD_PATCH_OLD.length == n);
        boolean shape = true;
        for (int i = 0; i < n; i++) {
            shape &= Recipes.MOD_PATCH_OFFSETS[i].length == Recipes.MOD_PATCH_BYTES[i].length
                    && Recipes.MOD_PATCH_OFFSETS[i].length == Recipes.MOD_PATCH_OLD[i].length
                    && Recipes.MOD_COPIES[i].length == Recipes.MOD_COPY_OLD[i].length
                    && Recipes.MOD_IDS[i].equals(Recipes.MOD_BASES[i] + "/" + Recipes.MOD_NAMES[i]);
            for (String h : Recipes.MOD_PATCH_BYTES[i]) {
                shape &= hexOk(h) && !h.isEmpty();
            }
            for (String h : Recipes.MOD_PATCH_OLD[i]) {
                shape &= h.length() == 64 && hexOk(h);
            }
            for (int[] c : Recipes.MOD_COPIES[i]) {
                shape &= c.length == 3 && c[2] > 0;
            }
        }
        expect("each mod's patches and copies line up, and every byte string is hex", shape);

        Set<String> ids = new HashSet<>(Arrays.asList(Recipes.MOD_IDS));
        Set<String> dumps = new HashSet<>();
        for (String[] d : Recipes.DUMPS) {
            dumps.add(d[0]);
        }
        boolean sets = true;
        Set<String> keys = new HashSet<>();
        for (String[] s : Recipes.SETS) {
            for (String m : s[1].isEmpty() ? new String[0] : s[1].split(",")) {
                sets &= ids.contains(m) && m.startsWith(s[0] + "/");
            }
            sets &= s[2].matches("\\d{12}") && (s[3].equals("tested") || s[3].equals("experimental"));
            keys.add(s[0] + "|" + s[1]);
        }
        expect("every set names mods of its own base, a 12-digit stamp and a status", sets);
        boolean builds = true;
        Set<String> made = new HashSet<>();
        for (String[] b : Recipes.BUILDS) {
            builds &= keys.contains(b[0] + "|" + b[1]) && dumps.contains(b[2]) && b[4].length() == 64
                    && b[3].matches("JH_5408\\d{12}_[0-9A-F]{8}\\.bin");
            made.add(b[0] + "|" + b[1] + "|" + b[2]);
        }
        expect("every build row is a set made from a known dump, with a camera file name and a SHA-256", builds);
        boolean complete = true;
        for (String[] s : Recipes.SETS) {
            for (String[] d : Recipes.DUMPS) {
                if (d[1].equals(s[0])) {
                    complete &= made.contains(s[0] + "|" + s[1] + "|" + d[0]);
                }
            }
        }
        expect("every set has its reviewed file from every dump of its base", complete);

        // The files the app pins today are rows of the table, under the same names.
        Set<String> rows = new HashSet<>();
        for (String[] b : Recipes.BUILDS) {
            rows.add(b[3] + " " + b[4]);
        }
        boolean pinned = true;
        for (Candidates.Kind k : new Candidates.Kind[]{Candidates.Kind.RESTORE, Candidates.Kind.PROBE,
                Candidates.Kind.BRINGUP, Candidates.Kind.V025, Candidates.Kind.V026, Candidates.Kind.V027}) {
            boolean both = rows.contains(k.fileName + " " + k.sha256) && rows.contains(k.cleanFileName + " " + k.cleanSha256);
            if (!both) {
                System.out.println("  not in Recipes.BUILDS: " + k);
            }
            pinned &= both;
        }
        expect("every version the app pins is a reviewed row of the table, from both copies of its dump", pinned);

        System.out.printf(Locale.ROOT, "RecipesTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }
}
