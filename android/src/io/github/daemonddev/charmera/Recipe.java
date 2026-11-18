package io.github.daemonddev.charmera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The firmware recipes (Recipes, generated from charmera_toolkit/recipes.json) as the app uses them: a base (the stock
 * firmware of one build: v006, v008_test or v012) plus a choice of the mods written for it, made from one of that
 * base's dumps exactly as charmera_toolkit/recipes.py makes it, and accepted only when the result is the reviewed file
 * the builds table lists for that base, those mods and that dump (Recipes.BUILDS). So, as with Candidates.build, this
 * path can produce nothing that was not generated, reviewed and published by hash.
 *
 * The preparation per dump (Recipes.DUMPS): v006 is cut to the 0x1E0000 update file; v012 is cut the same way with its
 * internal photo slots (0x180000..0x1CB000) blanked to 0xFF; v008_test is used as it is. Then each mod in the table's
 * order: its copies (from the dump itself), then its patches, each only over the bytes it was written for (their
 * SHA-256 is recorded). No Android imports: it runs on a plain JVM for android/test/RecipeTest.java.
 */
final class Recipe {

    static final String V006 = "v006";
    static final String V008 = "v008_test";
    static final String V012 = "v012";
    /** The bases, in the order the Repair route's base menu lists them. */
    static final String[] BASES = {V012, V006, V008};
    /** The mod whose photos carry the EXIF block (PhotoId.Kind.FACTORY_EXIF). */
    static final String EXIF_MOD = "v012/exif";
    static final int UPDATE_SIZE = 0x1E0000;
    static final int PHOTOS_START = 0x180000;
    static final int PHOTOS_END = 0x1CB000;

    private Recipe() {
    }

    /** A base and the mods chosen for it, kept in the table's order whatever order they were given in. */
    static final class Selection {
        final String base;
        final List<String> mods;

        Selection(String base, String... mods) {
            this(base, Arrays.asList(mods));
        }

        Selection(String base, List<String> mods) {
            this.base = base;
            List<String> ordered = new ArrayList<>();
            for (String id : Recipes.MOD_IDS) {           // the table's order; a duplicate counts once
                if (mods.contains(id)) {
                    ordered.add(id);
                }
            }
            for (String id : mods) {                     // an unknown id is kept at the end, so problem() names it
                if (modIndex(id) < 0 && !ordered.contains(id)) {
                    ordered.add(id);
                }
            }
            this.mods = Collections.unmodifiableList(ordered);
        }

        /** The mods joined by ',', as the tables write them. */
        String csv() {
            StringBuilder b = new StringBuilder();
            for (String m : mods) {
                b.append(b.length() == 0 ? "" : ",").append(m);
            }
            return b.toString();
        }

        boolean has(String mod) {
            return mods.contains(mod);
        }

        /** This selection with mod ticked (on) or not. */
        Selection with(String mod, boolean on) {
            List<String> m = new ArrayList<>(mods);
            m.remove(mod);
            if (on) {
                m.add(mod);
            }
            return new Selection(base, m);
        }

        /** No mods: the base's stock firmware, unchanged. */
        boolean stock() {
            return mods.isEmpty();
        }

        /** Its Recipes.SETS row, or null when it is not one of the reviewed combinations. */
        String[] set() {
            for (String[] s : Recipes.SETS) {
                if (s[0].equals(base) && s[1].equals(csv())) {
                    return s;
                }
            }
            return null;
        }

        /** "tested" or "experimental" (from its set), or null when it is not a set. */
        String status() {
            String[] s = set();
            return s == null ? null : s[3];
        }

        /** Its set's label, or the base and mod names when it is not a set. */
        String label() {
            String[] s = set();
            if (s != null) {
                return s[4];
            }
            StringBuilder b = new StringBuilder(base);
            for (String m : mods) {
                int i = modIndex(m);
                b.append(" + ").append(i < 0 ? m : Recipes.MOD_TITLES[i]);
            }
            return b.toString();
        }

        /** Whether a file with this SHA-256 is this selection made from any of its base's dumps. */
        boolean matches(String sha) {
            String[] row = reviewed(sha);
            return row != null && row[0].equals(base) && row[1].equals(csv());
        }

        /** The reviewed SHA-256 of this selection made from its base's first dump (the original read), or null. */
        String sha256() {
            for (String[] b : Recipes.BUILDS) {
                if (b[0].equals(base) && b[1].equals(csv())) {
                    return b[4];
                }
            }
            return null;
        }

        /** The app's fixed kind that makes the same files (RESTORE, PROBE, BRINGUP, V025..V027), or null. */
        Candidates.Kind kind() {
            for (Candidates.Kind k : LEGACY) {
                if (matches(k.sha256) && matches(k.cleanSha256)) {
                    return k;
                }
            }
            return null;
        }

        /** The firmware version of a preset ("v0.26"), or null for any other selection. */
        String version() {
            Candidates.Kind k = kind();
            return k == null ? null : k.version;
        }

        /** How the screen names it: a preset by its version, any other by its set's label. */
        String name() {
            String v = version();
            return v != null ? v : label();
        }

        /** "base|mod,mod", for the preferences; parse() reads it back. */
        String serialize() {
            return base + "|" + csv();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Selection && ((Selection) o).base.equals(base) && ((Selection) o).mods.equals(mods);
        }

        @Override
        public int hashCode() {
            return base.hashCode() * 31 + mods.hashCode();
        }

        @Override
        public String toString() {
            return serialize();
        }
    }

    /** The fixed kinds that are sets of the table, so a preset builds the same file it always did. */
    static final Candidates.Kind[] LEGACY = {Candidates.Kind.RESTORE, Candidates.Kind.PROBE, Candidates.Kind.BRINGUP,
            Candidates.Kind.V025, Candidates.Kind.V026, Candidates.Kind.V027};

    /** A selection written by Selection.serialize(), or null when the text is not one (or names no base). */
    static Selection parse(String text) {
        if (text == null) {
            return null;
        }
        int bar = text.indexOf('|');
        if (bar < 0) {
            return null;
        }
        String base = text.substring(0, bar);
        if (!Arrays.asList(BASES).contains(base)) {
            return null;
        }
        String csv = text.substring(bar + 1);
        return new Selection(base, csv.isEmpty() ? new String[0] : csv.split(","));
    }

    /**
     * The Repair pick to show: the one remembered (Selection.serialize()) when it is still a reviewed combination;
     * else the version an older app remembered by its Kind name (V027, V026, V025, BRINGUP), as its set; else the
     * newest preset.
     */
    static Selection remembered(String set, String legacyKind) {
        Selection s = parse(set);
        if (s != null && problem(s) == null) {
            return s;
        }
        for (Candidates.Kind k : Candidates.repairVersions()) {
            if (k.name().equals(legacyKind)) {
                return of(k);
            }
        }
        return presets()[0];
    }

    /** The set of a fixed kind (by its reviewed file). */
    static Selection of(Candidates.Kind kind) {
        return selectionOf(kind.sha256);
    }

    /** The Repair route's presets, newest first: one per Candidates.repairVersions() entry. */
    static Selection[] presets() {
        Candidates.Kind[] kinds = Candidates.repairVersions();
        Selection[] out = new Selection[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            out[i] = of(kinds[i]);
        }
        return out;
    }

    static int modIndex(String id) {
        for (int i = 0; i < Recipes.MOD_IDS.length; i++) {
            if (Recipes.MOD_IDS[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** The mods of a base, in the table's order. */
    static String[] mods(String base) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Recipes.MOD_IDS.length; i++) {
            if (Recipes.MOD_BASES[i].equals(base)) {
                out.add(Recipes.MOD_IDS[i]);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * Whether sel's set is "experimental" in Recipes.SETS while none of its ticked mods is: the combination has not been
     * tried on a camera, although each mod alone has.
     */
    static boolean untriedCombination(Selection sel) {
        if (!"experimental".equals(sel.status())) {
            return false;
        }
        for (String m : sel.mods) {
            if (experimental(m)) {
                return false;
            }
        }
        return true;
    }

    static boolean experimental(String mod) {
        int i = modIndex(mod);
        return i >= 0 && "experimental".equals(Recipes.MOD_STATUS[i]);
    }

    /** A ticked mod of sel that cannot go with mod (either one names the other as a conflict), or null. */
    static String conflict(Selection sel, String mod) {
        int i = modIndex(mod);
        for (String other : sel.mods) {
            if (other.equals(mod)) {
                continue;
            }
            int j = modIndex(other);
            if (i >= 0 && Arrays.asList(Recipes.MOD_CONFLICTS[i]).contains(other)
                    || j >= 0 && Arrays.asList(Recipes.MOD_CONFLICTS[j]).contains(mod)) {
                return other;
            }
        }
        return null;
    }

    /** A mod that mod needs and sel lacks, or null. */
    static String missing(Selection sel, String mod) {
        int i = modIndex(mod);
        if (i < 0) {
            return null;
        }
        for (String need : Recipes.MOD_REQUIRES[i]) {
            if (!sel.has(need)) {
                return need;
            }
        }
        return null;
    }

    /** A mod's short title ("EXIF dates"), or its id when it is not one. */
    static String title(String mod) {
        int i = modIndex(mod);
        return i < 0 ? mod : Recipes.MOD_TITLES[i];
    }

    static String description(String mod) {
        int i = modIndex(mod);
        return i < 0 ? "" : Recipes.MOD_DESCRIPTIONS[i];
    }

    /**
     * Why the app will not make sel, as a plain clause in lower case (the same form as Candidates' messages), or null
     * when it is a reviewed combination: an unknown base or mod, a mod of another base, two mods that conflict, a mod
     * without one it needs, or a combination not in the table.
     */
    static String problem(Selection sel) {
        if (!Arrays.asList(BASES).contains(sel.base)) {
            return "there is no base " + sel.base + " (the bases are v012, v006 and v008_test)";
        }
        for (String m : sel.mods) {
            int i = modIndex(m);
            if (i < 0 || !Recipes.MOD_BASES[i].equals(sel.base)) {
                return "there is no mod " + m + " for base " + sel.base;
            }
            String clash = conflict(sel, m);
            if (clash != null) {
                return title(m) + " cannot go with " + title(clash);
            }
            String need = missing(sel, m);
            if (need != null) {
                return title(m) + " needs " + title(need);
            }
        }
        if (sel.set() == null) {
            return sel.label() + " is not one of the reviewed combinations";
        }
        return null;
    }

    /** The ids of a base's dumps (Recipes.DUMPS), the original read first. */
    static String[] dumpIds(String base) {
        List<String> out = new ArrayList<>();
        for (String[] d : Recipes.DUMPS) {
            if (d[1].equals(base)) {
                out.add(d[0]);
            }
        }
        return out.toArray(new String[0]);
    }

    /** The Recipes.DUMPS row with this id, or null. */
    static String[] dump(String id) {
        for (String[] d : Recipes.DUMPS) {
            if (d[0].equals(id)) {
                return d;
            }
        }
        return null;
    }

    /** The Recipes.DUMPS row these bytes are, by SHA-256, or null. */
    static String[] dumpFor(byte[] data) {
        String sha = UpdateCheck.sha256Hex(data, 0, data.length);
        for (String[] d : Recipes.DUMPS) {
            if (d[4].equals(sha)) {
                return d;
            }
        }
        return null;
    }

    /** The public dump of a base that the app downloads (the cleaned copy where there is one), or null. */
    static String[] download(String base) {
        String[] pick = null;
        for (String[] d : Recipes.DUMPS) {
            if (d[1].equals(base) && d[5] != null && (pick == null || "clean".equals(d[2]))) {
                pick = d;
            }
        }
        return pick;
    }

    /** The dump cut to the base's update file, as the reviewed files were made from it (recipes.prepare). */
    static byte[] prepare(byte[] dump, String[] entry) {
        String how = entry[3];
        if (how.equals("as-is")) {
            return dump.clone();
        }
        byte[] out = Arrays.copyOf(dump, Math.min(dump.length, UPDATE_SIZE));
        if (how.equals("truncate-blank-photos")) {
            Arrays.fill(out, PHOTOS_START, PHOTOS_END, (byte) 0xFF);
        } else if (!how.equals("truncate")) {
            throw new IllegalStateException("the recipes name a preparation the app does not know (" + how + ").");
        }
        return out;
    }

    /** One mod: its copies, then its patches, each only over the bytes it was written for (recipes.apply). */
    static void apply(byte[] out, byte[] dump, int mod) {
        String id = Recipes.MOD_IDS[mod];
        int[][] copies = Recipes.MOD_COPIES[mod];
        for (int c = 0; c < copies.length; c++) {
            int from = copies[c][0];
            int to = copies[c][1];
            int n = copies[c][2];
            if (from < 0 || to < 0 || n <= 0 || from + n > dump.length || to + n > out.length) {
                throw new IllegalStateException(id + ": a copy lies outside the dump or the file.");
            }
            if (!UpdateCheck.sha256Hex(out, to, to + n).equals(Recipes.MOD_COPY_OLD[mod][c])) {
                throw new IllegalStateException(id + ": the bytes at file 0x" + Integer.toHexString(to).toUpperCase(
                        java.util.Locale.ROOT) + " are not the ones this mod replaces.");
            }
            System.arraycopy(dump, from, out, to, n);
        }
        int[] offsets = Recipes.MOD_PATCH_OFFSETS[mod];
        for (int p = 0; p < offsets.length; p++) {
            byte[] add = Candidates.hex(Recipes.MOD_PATCH_BYTES[mod][p]);
            int off = offsets[p];
            if (off < 0 || off + add.length > out.length) {
                throw new IllegalStateException(id + ": a patch lies outside the file.");
            }
            if (!UpdateCheck.sha256Hex(out, off, off + add.length).equals(Recipes.MOD_PATCH_OLD[mod][p])) {
                throw new IllegalStateException(id + ": the bytes at file 0x" + Integer.toHexString(off).toUpperCase(
                        java.util.Locale.ROOT) + " are not the ones this mod replaces.");
            }
            System.arraycopy(add, 0, out, off, add.length);
        }
    }

    /** The bytes of a base plus mods from a dump, unchecked against the builds table (the tests use this). */
    static byte[] compose(byte[] dump, String[] entry, Selection sel) {
        byte[] out = prepare(dump, entry);
        for (String m : sel.mods) {
            int i = modIndex(m);
            if (i < 0 || !Recipes.MOD_BASES[i].equals(entry[1])) {
                throw new IllegalArgumentException(m + " is not a mod of base " + entry[1] + ".");
            }
            apply(out, dump, i);
        }
        return out;
    }

    /**
     * The reviewed file of sel made from this dump, accepted only when it hashes to the Recipes.BUILDS row for that
     * base, those mods and that dump. Throws otherwise, with a message in the same form as Candidates.build's: it
     * starts in lower case, and any SHA-256 is on a last line that starts with "Detail:".
     */
    static byte[] build(byte[] dump, Selection sel) {
        String[] entry = dumpFor(dump);
        if (entry == null) {
            throw new IllegalArgumentException("the file is not a dump the app makes firmware from, so the app will not"
                    + " use it.\nDetail: its SHA-256 is " + UpdateCheck.sha256Hex(dump, 0, dump.length) + ".");
        }
        if (!entry[1].equals(sel.base)) {
            throw new IllegalArgumentException("the dump is build " + entry[1] + ", but " + sel.label() + " is made"
                    + " from build " + sel.base + ", so the app will not use it.");
        }
        String why = problem(sel);
        if (why != null) {
            throw new IllegalArgumentException(why + ", so the app will not make it.");
        }
        String[] row = null;
        for (String[] b : Recipes.BUILDS) {
            if (b[0].equals(sel.base) && b[1].equals(sel.csv()) && b[2].equals(entry[0])) {
                row = b;
            }
        }
        if (row == null) {
            throw new IllegalStateException("no reviewed file is listed for " + sel.label() + " from " + entry[0]
                    + ", so the app will not make it. This should not happen. Please report it.");
        }
        byte[] out = compose(dump, entry, sel);
        String sha = UpdateCheck.sha256Hex(out, 0, out.length);
        if (!sha.equals(row[4])) {
            throw new IllegalStateException("the file made from the " + sel.base + " dump is not exactly "
                    + sel.label() + ", so the app will not use it. This should not happen. Please report it.\nDetail:"
                    + " its SHA-256 is " + sha + "; " + sel.label() + " has " + row[4] + ".");
        }
        return out;
    }

    /** The Recipes.BUILDS row of a reviewed file, by its SHA-256, or null. */
    static String[] reviewed(String sha) {
        if (sha == null) {
            return null;
        }
        for (String[] b : Recipes.BUILDS) {
            if (b[4].equals(sha)) {
                return b;
            }
        }
        return null;
    }

    /** The camera file name of the reviewed file with this SHA-256, or null. */
    static String fileNameFor(String sha) {
        String[] b = reviewed(sha);
        return b == null ? null : b[3];
    }

    /** The selection a reviewed file was made from, by its SHA-256, or null. */
    static Selection selectionOf(String sha) {
        String[] b = reviewed(sha);
        return b == null ? null : new Selection(b[0], b[1].isEmpty() ? new String[0] : b[1].split(","));
    }

    /** The v006 mod that makes the public build drive the OV9732 (v0.23; with the Volume and LED rows, v0.24). */
    static final String OV9732_PORT = "v006/ov9732-port";
    /** The v006 mod that is the sensor-ID probe (the Probe route's file). */
    static final String PROBE_MOD = "v006/sensor-probe";

    /**
     * Whether sel's firmware drives the OV9732: every v012 build (the factory firmware of OV9732 cameras, its stock
     * included) and every v006 build with the OV9732 port. Every other build (v006 without the port, the stock restore
     * file among them, and every v008_test build) is SP140A firmware.
     */
    static boolean drivesOv9732(Selection sel) {
        return sel != null && (V012.equals(sel.base) || V006.equals(sel.base) && sel.has(OV9732_PORT));
    }

    /** The sensor a pick's firmware drives, in words: "OV9732" or "SP140A". */
    static String sensorOf(Selection sel) {
        return drivesOv9732(sel) ? "OV9732" : "SP140A";
    }

    /**
     * The warning for writing sel when nothing has confirmed what this camera runs, or null when something has. Every
     * reviewed build may be written without a backup (WriteGate): the owner decides. A backup on record confirms it
     * (its comparison lines, Candidates.check, say when the backup's sensor is the other one), and so does the Identify
     * route's answer when its family drives the same sensor as sel. identified is that answer's family: "v012",
     * "v006-family", or null when Identify has not given one. The sensor-ID probe needs no warning: it works on either.
     */
    static String unconfirmed(Selection sel, boolean backupOnRecord, String identified) {
        if (sel == null || backupOnRecord || sel.has(PROBE_MOD) && sel.mods.size() == 1) {
            return null;
        }
        String mine = sensorOf(sel);
        String other = drivesOv9732(sel) ? "SP140A" : "OV9732";
        if (identified != null) {
            String theirs = "v012".equals(identified) ? "OV9732" : "SP140A";
            if (theirs.equals(mine)) {
                return null;
            }
            return "Wrong sensor? Identify says this camera's photos come from " + ("v012".equals(identified)
                    ? "build v012" : "the v006 family") + ", firmware for the " + theirs + ", but this file drives the "
                    + mine + ". On an " + theirs + " camera it boots but can't take pictures until you flash firmware"
                    + " that fits.";
        }
        return "Not confirmed: no backup is chosen and Identify hasn't been run, so the app doesn't know which"
                + " firmware this camera runs. This file drives the " + mine + ". On an " + other + " camera it boots but"
                + " can't take pictures until you flash firmware that fits. To check without opening the camera, run"
                + " \"Identify this camera's firmware\" on its photos.";
    }

    /** The header the photos of this firmware should have. */
    static PhotoId.Kind expectedPhoto(Selection sel) {
        if (sel.has(EXIF_MOD)) {
            return PhotoId.Kind.FACTORY_EXIF;
        }
        if (V012.equals(sel.base)) {
            return PhotoId.Kind.ORIGINAL_BARE;
        }
        return PhotoId.Kind.PUBLIC_FAMILY;
    }

    /** The plain words for a selection the app has no fixed kind for: its label and each mod's description. */
    static String describe(Selection sel) {
        StringBuilder b = new StringBuilder(sel.label()).append(".");
        for (String m : sel.mods) {
            b.append(" ").append(title(m)).append(experimental(m) ? " (experimental, not yet tried on a camera)" : "")
                    .append(": ").append(description(m));
        }
        if (sel.stock() && V012.equals(sel.base)) {
            b.append(" The factory firmware of OV9732 cameras, unchanged.");
        } else if (!drivesOv9732(sel)) {
            b.append(sel.stock() ? " The base's stock firmware, unchanged." : " SP140A firmware. It doesn't drive the"
                    + " OV9732.");
        }
        if (untriedCombination(sel)) {
            b.append(" Experimental: this combination hasn't been tried on a camera yet, though each of its mods"
                    + " has.");
        }
        return b.toString();
    }
}
