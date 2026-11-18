package io.github.daemonddev.charmera;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which STOCK firmware this camera runs, from the photos on its card, for the "Identify this camera's firmware" route.
 * Its job is to pick the base to build on, assuming an unmodded stock camera; a camera that was flashed or modded is
 * the exception, which it detects and says, without weakening the normal stock answer. No Android imports, so it runs
 * on a plain JVM in android/test/IdentifyTest.java. Read-only: it only reads the start of each photo
 * (PhotoId.HEADER_LIMIT + 16 bytes) through the Header each entry brings. It gives the same answers as
 * charmera_toolkit/identify.py.
 *
 * Families: the "v006 family" is the 0x590 Generalplus header (stock v006 or v008_test, SP140A cameras); the "v012
 * family" is the bare 0x270 header (stock v012, OV9732 cameras). The 0x340 EXIF-mod header is ours (v0.26 and later,
 * built on v012): the camera is not stock, and it is v012 family. Bare and EXIF-mod photos are one family.
 *
 * Photos with no file time (mtime 0) are left out of the dating: they are counted and named, never read. Among the
 * others (videos carry no such header and are only counted), in time order, it finds the oldest camera-written photo
 * (reading at most cap headers from the oldest end) and the newest (at most cap from the newest end), then reads the
 * family of cap photos evenly spaced by date, the oldest and newest found always among them (all of them when there
 * are no more than cap), and looks there for a change of family. Two families mean a flash in between (or photos from
 * two cameras): the base then comes from the oldest family, as photos from before a flash show the stock firmware.
 */
final class Identify {

    /** The firmware base to build on: stock v012's, the v006 family's (v006 or v008_test), or none. */
    enum Base { V012, V006_FAMILY, UNKNOWN }

    enum Confidence { HIGH, MEDIUM, LOW }

    /** The cap: at most this many photo headers are read from each end, and this many for the families. */
    static final int SCAN_CAP = 500;
    /** How many photos that are not recognised, could not be read or have no file time are named one by one. */
    static final int NAMED_OTHERS = 10;

    static final String CAVEAT_V008 = "A photo cannot tell v006 from v008_test: both are stock builds for SP140A"
            + " cameras and write the same header; either base works the same for our purposes.";
    static final String CAVEAT_AFTER_FLASH = "Photos show the firmware that took them: on a camera that was flashed,"
            + " or that freezes in Photo mode, only photos from before the flash say what fits.";
    static final String CAVEAT_CLOCK = "Dates and order come from the file times, which come from the camera's clock.";
    static final String OUR_V021_V024 = "If this camera was ever flashed with our v0.21-v0.24, its photos look like"
            + " this too.";
    static final String NOT_STOCK = "This camera does not look stock: ";

    /** The start of one file (PhotoId.HEADER_LIMIT + 16 bytes, fewer at its end), read only when it is needed. */
    interface Header {
        byte[] read() throws IOException;
    }

    /** One photo or video on the card. */
    static final class Entry {
        /** Its path from the card's top folder, e.g. "DCIM/PICT0001.jpg". */
        final String path;
        /** Its file time in ms, 0 when unknown. */
        final long mtime;
        final boolean video;
        final Header header;

        Entry(String path, long mtime, boolean video, Header header) {
            this.path = path;
            this.mtime = mtime;
            this.video = video;
            this.header = header;
        }
    }

    /** A change of family between two camera-written photos next to each other among those read, in time order. */
    static final class Change {
        final Entry before;
        final PhotoId.Kind from;
        final Entry after;
        final PhotoId.Kind to;

        Change(Entry before, PhotoId.Kind from, Entry after, PhotoId.Kind to) {
            this.before = before;
            this.from = from;
            this.after = after;
            this.to = to;
        }
    }

    /** A run, in time order, of camera-written photos of one family among those read for the families. */
    static final class Run {
        final Base family;
        final Entry first;
        Entry last;
        int count = 1;

        Run(Base family, Entry first) {
            this.family = family;
            this.first = first;
            this.last = first;
        }
    }

    static final class Result {
        /** One line per photo that matters (the oldest and newest recognised, each change, the others), and counts. */
        final List<String> evidence = new ArrayList<>();
        /** The answer, one statement per line: the conclusion, any not-stock lines, the base, then notes. */
        String conclusion;
        /** The family the base comes from (V012 or V006_FAMILY), or UNKNOWN. */
        Base family = Base.UNKNOWN;
        Base base = Base.UNKNOWN;
        Confidence confidence = Confidence.LOW;
        /** Why the confidence is what it is (identify.py's confidence_why); empty without a base. */
        String confidenceWhy = "";
        /** The "This camera does not look stock: ..." lines, also in the conclusion; empty for a stock camera. */
        final List<String> notStock = new ArrayList<>();
        /** The notes after the base line (identify.py's notes), also in the conclusion. */
        final List<String> notes = new ArrayList<>();
        final List<String> caveats = new ArrayList<>();
        int photos;
        int videos;
        /** Photos with no file time, left out of the dating and never read. */
        int undated;
        /** Photos read for the families (identify.py's scanned): the evenly spaced sample. */
        int scanned;
        /** Every photo header read: the sample and the ones read at either end to find the oldest and newest. */
        int headersRead;
        /** Camera-written photos among those read for the families. */
        int recognised;
        /** Photos read whose header is not one a known build writes as the camera saves it (PhotoId OTHER). */
        int other;
        /** Photos whose start could not be read. */
        int unreadable;
        /** Fewer photos were read for the families than the card holds with a file time. */
        boolean capped;
        /** At least one photo read for the families has our EXIF mod's header. */
        boolean exifMod;
        /** The oldest camera-written photo, found within cap photos of the oldest end; null when none was. */
        Entry oldest;
        PhotoId.Kind oldestKind;
        /** The newest camera-written photo, found within cap photos of the newest end; null when none was. */
        Entry newest;
        PhotoId.Kind newestKind;
        /** Photos newer than the newest camera-written one, read and skipped (identify.py's skipped_newest). */
        int skippedNewest;
        /** Changes of family (v006 family to v012 family or back); bare to EXIF mod is not one. */
        final List<Change> changes = new ArrayList<>();
        /** The runs of one family among the photos read for the families, oldest first. */
        final List<Run> runs = new ArrayList<>();
    }

    private Identify() {
    }

    /** What a recognised header says, in the words the route shows. */
    static String label(PhotoId.Kind k) {
        switch (k) {
            case PUBLIC_FAMILY:
                return "v006 family (stock v006 or v008_test, SP140A cameras)";
            case ORIGINAL_BARE:
                return "v012 family (stock v012, OV9732 cameras)";
            case FACTORY_EXIF:
                return "our EXIF mod (v0.26 or later), v012 family";
            default:
                return "not a header a known Charmera build writes";
        }
    }

    /** The family a recognised header belongs to: v012 for stock v012 and our EXIF mod of it, else the v006 family. */
    static Base baseOf(PhotoId.Kind k) {
        switch (k) {
            case PUBLIC_FAMILY:
                return Base.V006_FAMILY;
            case ORIGINAL_BARE:
            case FACTORY_EXIF:
                return Base.V012;
            default:
                return Base.UNKNOWN;
        }
    }

    /** The base to build on for a family (identify.py's BASE): v012, or v006 for the v006 family. */
    static String baseName(Base b) {
        switch (b) {
            case V012:
                return "v012";
            case V006_FAMILY:
                return "v006";
            default:
                return "unknown";
        }
    }

    /** The stock firmware a camera of that family runs (identify.py's STOCK). */
    static String stockName(Base b) {
        switch (b) {
            case V012:
                return "v012";
            case V006_FAMILY:
                return "v006 or v008_test";
            default:
                return "unknown";
        }
    }

    static String familyName(Base b) {
        switch (b) {
            case V012:
                return "the v012 family";
            case V006_FAMILY:
                return "the v006 family";
            default:
                return "unknown";
        }
    }

    static Result identify(List<Entry> media, TimeZone tz) {
        return identify(media, tz, SCAN_CAP);
    }

    /** Never throws for a file that cannot be read: it is counted, and named in the evidence. */
    static Result identify(List<Entry> media, TimeZone tz, int cap) {
        cap = Math.max(1, cap);
        Result r = new Result();
        List<Entry> photos = new ArrayList<>();
        List<Entry> undated = new ArrayList<>();
        for (Entry e : media) {
            if (e.video) {
                r.videos++;
            } else if (e.mtime <= 0) {
                undated.add(e);
            } else {
                photos.add(e);
            }
        }
        // oldest first; the same time goes by path (as identify.py sorts), as the card names photos by a counter
        Collections.sort(photos, (a, b) -> a.mtime != b.mtime ? Long.compare(a.mtime, b.mtime)
                : a.path.compareTo(b.path));
        Collections.sort(undated, (a, b) -> a.path.compareTo(b.path));
        r.undated = undated.size();
        r.photos = photos.size() + undated.size();
        int n = photos.size();
        TreeMap<Integer, PhotoId.Result> read = new TreeMap<>();     // by index in time order; null: not readable
        // identify.py's first_camera, from each end: at most cap headers each
        int lo = -1;
        for (int i = 0; i < n && i < cap; i++) {
            if (camera(scan(photos, i, read))) {
                lo = i;
                break;
            }
        }
        int hi = -1;
        for (int j = 0; j < n && j < cap; j++) {
            if (camera(scan(photos, n - 1 - j, read))) {
                hi = n - 1 - j;
                break;
            }
            r.skippedNewest++;
        }
        if (hi < 0) {
            r.skippedNewest = 0;     // identify.py concludes nothing then, and gives no notes
        }
        // identify.py's sample: cap photos evenly spaced by date (all when there are no more), keeping both ends found
        List<Integer> sample = sample(n, cap, lo, hi);
        for (int i : sample) {
            scan(photos, i, read);
        }
        r.scanned = sample.size();
        r.headersRead = read.size();
        r.capped = r.scanned < n;
        if (lo >= 0) {
            r.oldest = photos.get(lo);
            r.oldestKind = read.get(lo).kind;
        }
        if (hi >= 0) {
            r.newest = photos.get(hi);
            r.newestKind = read.get(hi).kind;
        }

        // the families, from the photos read for them only (identify.py's runs over the sample)
        PhotoId.Kind prevKind = null;
        Entry prev = null;
        Entry firstExif = null;
        String exifSoftware = null;
        Base oldestFamily = Base.UNKNOWN;
        boolean v006Seen = false;
        List<Entry> bares = new ArrayList<>();
        for (int i : sample) {
            PhotoId.Result k = read.get(i);
            if (!camera(k)) {
                continue;
            }
            Entry e = photos.get(i);
            Base family = baseOf(k.kind);
            r.recognised++;
            if (oldestFamily == Base.UNKNOWN) {
                oldestFamily = family;
            }
            if (k.kind == PhotoId.Kind.PUBLIC_FAMILY) {
                v006Seen = true;
            } else if (k.kind == PhotoId.Kind.ORIGINAL_BARE) {
                bares.add(e);
            } else if (k.kind == PhotoId.Kind.FACTORY_EXIF && firstExif == null) {
                firstExif = e;
                exifSoftware = k.software;
            }
            // bare to EXIF mod (and back) is one family: not a change
            if (prevKind != null && baseOf(prevKind) != family) {
                r.changes.add(new Change(prev, prevKind, e, k.kind));
            }
            Run last = r.runs.isEmpty() ? null : r.runs.get(r.runs.size() - 1);
            if (last != null && last.family == family) {
                last.last = e;
                last.count++;
            } else {
                r.runs.add(new Run(family, e));
            }
            prev = e;
            prevKind = k.kind;
        }
        r.exifMod = firstExif != null;
        // the install falls after the last bare photo no newer than the first EXIF-mod one (identify.py's `before`)
        Entry bareBeforeExif = null;
        if (firstExif != null) {
            for (Entry b : bares) {
                if (b.mtime <= firstExif.mtime) {
                    bareBeforeExif = b;
                }
            }
        }

        // the evidence: counts, then the photos that matter
        List<String> otherLines = new ArrayList<>();
        List<String> unreadableLines = new ArrayList<>();
        for (java.util.Map.Entry<Integer, PhotoId.Result> m : read.entrySet()) {
            Entry e = photos.get(m.getKey());
            PhotoId.Result k = m.getValue();
            if (k == null) {
                r.unreadable++;
                if (unreadableLines.size() < NAMED_OTHERS) {
                    unreadableLines.add(e.path + ": could not be read.");
                }
            } else if (!camera(k)) {
                r.other++;
                if (otherLines.size() < NAMED_OTHERS) {
                    otherLines.add(which(e, tz) + ": not recognised" + (k.reasons.isEmpty() ? ""
                            : " (" + joined(k.reasons) + ")") + ".");
                }
            }
        }
        r.evidence.add(String.format(Locale.ROOT, "Photos on the card: %d; read: %d%s; camera-written: %d; not"
                + " recognised: %d; could not be read: %d.", r.photos, r.headersRead, r.headersRead == r.scanned ? ""
                : String.format(Locale.ROOT, " (%d of them for the families)", r.scanned), r.recognised, r.other,
                r.unreadable));
        if (r.videos > 0) {
            r.evidence.add(String.format(Locale.ROOT, "Videos: %d, not read (only photos carry this header).",
                    r.videos));
        }
        if (r.undated > 0) {
            r.evidence.add(undatedLine(r.undated) + ":");
            for (int i = 0; i < undated.size() && i < NAMED_OTHERS; i++) {
                r.evidence.add(undated.get(i).path + ": no file time.");
            }
            if (undated.size() > NAMED_OTHERS) {
                r.evidence.add(String.format(Locale.ROOT, "...and %d more with no file time.",
                        undated.size() - NAMED_OTHERS));
            }
        }
        if (r.capped) {
            r.evidence.add(String.format(Locale.ROOT, "Only %d of %d dated photos were read for the families: the"
                    + " oldest and the newest camera-written ones, and photos evenly spaced by date between them.",
                    r.scanned, n));
        }
        if (r.oldest != null) {
            r.evidence.add("Oldest camera-written: " + which(r.oldest, tz) + ": " + label(r.oldestKind) + ".");
        } else if (r.newest != null) {
            r.evidence.add(String.format(Locale.ROOT, "None of the oldest %d photos read is camera-written.",
                    Math.min(cap, n)));
        }
        if (r.newest != null && r.newest != r.oldest) {
            r.evidence.add("Newest camera-written: " + which(r.newest, tz) + ": " + label(r.newestKind) + ".");
        }
        if (firstExif != null) {
            r.evidence.add("First photo with our EXIF mod's header: " + which(firstExif, tz) + ".");
        }
        for (Change c : r.changes) {
            r.evidence.add("Family changes between " + which(c.before, tz) + " (" + label(c.from) + ") and "
                    + which(c.after, tz) + " (" + label(c.to) + ").");
        }
        r.evidence.addAll(otherLines);
        if (r.other > otherLines.size()) {
            r.evidence.add(String.format(Locale.ROOT, "...and %d more not recognised.", r.other - otherLines.size()));
        }
        r.evidence.addAll(unreadableLines);
        if (r.unreadable > unreadableLines.size()) {
            r.evidence.add(String.format(Locale.ROOT, "...and %d more could not be read.",
                    r.unreadable - unreadableLines.size()));
        }

        conclude(r, tz, n, cap, oldestFamily, bareBeforeExif, firstExif, exifSoftware, v006Seen);
        r.caveats.add(CAVEAT_V008);
        r.caveats.add(CAVEAT_AFTER_FLASH);
        r.caveats.add(CAVEAT_CLOCK);
        if (r.capped) {
            r.caveats.add("Not every photo was read, so a short run of photos from another firmware between the ones"
                    + " read can be missed.");
        }
        return r;
    }

    /**
     * identify.py's sample: every index when n is no more than cap; else cap indexes evenly spaced from 0 to n - 1
     * (Python's round, half to even; n - 1 alone for a cap of 1), with lo and hi (the oldest and newest camera-written
     * photos found, -1 for none) always among them. Sorted.
     */
    static List<Integer> sample(int n, int cap, int lo, int hi) {
        List<Integer> out = new ArrayList<>();
        if (n <= cap) {
            for (int i = 0; i < n; i++) {
                out.add(i);
            }
            return out;
        }
        TreeSet<Integer> picks = new TreeSet<>();
        if (cap <= 1) {
            picks.add(n - 1);
        } else {
            for (int i = 0; i < cap; i++) {
                picks.add((int) Math.rint((double) ((long) i * (n - 1)) / (cap - 1)));
            }
        }
        if (lo >= 0) {
            picks.add(lo);
        }
        if (hi >= 0) {
            picks.add(hi);
        }
        out.addAll(picks);
        return out;
    }

    private static String undatedLine(int undated) {
        return undated == 1 ? "1 photo has no file time and was left out of the dating"
                : String.format(Locale.ROOT, "%d photos have no file time and were left out of the dating", undated);
    }

    /** identify.py's _families_text, in the app's words: two runs by their dates, more by each run's span. */
    private static String familiesText(Result r, TimeZone tz) {
        if (r.runs.size() == 2) {
            Run a = r.runs.get(0);
            Run b = r.runs.get(1);
            return "photos from two firmware families: " + familyName(a.family) + " up to " + date(a.last, tz) + ", "
                    + familyName(b.family) + " from " + date(b.first, tz) + ": the camera was flashed in between, or"
                    + " the card holds photos from two cameras.";
        }
        StringBuilder sb = new StringBuilder();
        for (Run x : r.runs) {
            sb.append(sb.length() == 0 ? "" : "; ").append(familyName(x.family)).append(' ').append(date(x.first, tz))
                    .append(" to ").append(date(x.last, tz));
        }
        return String.format(Locale.ROOT, "photos from two firmware families, which change %d times by date (%s):"
                + " the camera was flashed more than once, or the card holds photos from more than one camera.",
                r.runs.size() - 1, sb);
    }

    /**
     * The answer. Stock assumption: one family is "This camera runs stock ...", HIGH when the scan was not capped and
     * at least 2 camera-written photos agree, else MEDIUM. Our EXIF mod: not stock, base v012, HIGH. Two families:
     * not stock, base from the OLDEST family, LOW. No newest camera-written photo found: no answer.
     */
    private static void conclude(Result r, TimeZone tz, int dated, int cap, Base oldestFamily, Entry bareBeforeExif,
                                 Entry firstExif, String exifSoftware, boolean v006Seen) {
        List<String> lines = new ArrayList<>();
        if (r.photos == 0) {
            r.conclusion = "The card holds no photos, so the photos cannot say which firmware this camera runs."
                    + (r.videos > 0 ? " Videos do not carry the header the app reads." : "");
            return;
        }
        if (r.newest == null) {
            if (dated == 0) {
                lines.add("No photo on the card has a file time, so the app cannot date them or tell which"
                        + " firmware took them.");
            } else if (r.unreadable == r.headersRead) {
                lines.add("The app could not read the photos on the card, so it cannot tell which firmware took"
                        + " them.");
            } else if (r.oldest != null) {
                // identify.py's first_camera from the newest end found none within the cap: it concludes nothing
                lines.add(String.format(Locale.ROOT, "None of the newest %d photos the app read has a header a known"
                        + " Charmera build writes as the camera saves it, so the app cannot tell which firmware"
                        + " took the newest photos and gives no answer.", Math.min(cap, dated)));
            } else {
                lines.add("None of the photos the app read has a header a known Charmera build writes. They may come"
                        + " from another camera or app, so the photos cannot say which firmware this camera runs.");
            }
            if (r.undated > 0 && dated > 0) {
                lines.add(undatedLine(r.undated) + ".");
            }
            r.conclusion = lines(lines);
            return;
        }
        r.family = oldestFamily;
        r.base = oldestFamily;
        if (r.exifMod) {
            r.notStock.add(NOT_STOCK + "our EXIF mod (v0.26 or later" + (exifSoftware == null || exifSoftware.isEmpty()
                    ? "" : ", Software '" + exifSoftware + "'") + ") is installed" + (bareBeforeExif == null ? ""
                    : ", installed between " + date(bareBeforeExif, tz) + " and " + date(firstExif, tz)) + ".");
        }
        if (!r.changes.isEmpty()) {
            // two families: the base goes by the oldest, never the newest
            r.notStock.add(0, NOT_STOCK + familiesText(r, tz));
            r.confidence = Confidence.LOW;
            r.confidenceWhy = "the card holds photos from two firmware families; the base follows the oldest, from"
                    + " before the change";
            if (r.capped) {
                r.confidenceWhy += String.format(Locale.ROOT, "; read %d of %d dated photo headers, so the"
                        + " change-over dates are approximate", r.scanned, dated);
            }
            lines.add("The oldest photos on the card show stock " + stockName(oldestFamily) + ", the newer ones "
                    + familyName(baseOf(r.changes.get(0).to)) + ".");
        } else if (r.exifMod) {
            r.confidence = Confidence.HIGH;
            r.confidenceWhy = "the EXIF mod's header is ours alone, and the mod is built on v012";
            lines.add("This camera runs our EXIF mod, which is built on stock v012.");
        } else {
            List<String> whys = new ArrayList<>();
            if (r.recognised < 2) {
                whys.add("only one camera-written photo was found");
            }
            if (r.capped) {
                whys.add(String.format(Locale.ROOT, "read %d of %d dated photo headers, evenly spaced by date",
                        r.scanned, dated));
            }
            r.confidence = whys.isEmpty() ? Confidence.HIGH : Confidence.MEDIUM;
            r.confidenceWhy = whys.isEmpty() ? String.format(Locale.ROOT, "all %d camera-written photos read show the"
                    + " same family, oldest to newest", r.recognised) : joined(whys);
            lines.add("This camera runs stock " + stockName(oldestFamily) + ".");
        }
        lines.addAll(r.notStock);
        lines.add("Base to build on: " + baseName(r.base) + (r.changes.isEmpty() ? "."
                : " (from the oldest photos: photos from before a flash show the stock firmware)."));
        if (r.skippedNewest > 0) {
            r.notes.add(r.skippedNewest == 1 ? "The newest photo file was not the camera's own and was skipped."
                    : String.format(Locale.ROOT, "The newest %d photo files were not the camera's own and were"
                    + " skipped.", r.skippedNewest));
        }
        if (v006Seen) {
            r.notes.add(OUR_V021_V024);
        }
        lines.addAll(r.notes);
        if (r.undated > 0) {
            lines.add(undatedLine(r.undated) + ".");
        }
        r.conclusion = lines(lines);
    }

    /** A photo's file time as a date the owner can read, with the year. */
    static String date(Entry e, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
        f.setTimeZone(tz);
        return f.format(new Date(e.mtime));
    }

    /** Reads photo i's header once (identify.py's read_photo); null when it cannot be read. */
    private static PhotoId.Result scan(List<Entry> photos, int i, TreeMap<Integer, PhotoId.Result> read) {
        if (read.containsKey(i)) {
            return read.get(i);
        }
        Entry e = photos.get(i);
        PhotoId.Result k;
        try {
            byte[] head = e.header == null ? null : e.header.read();
            k = head == null ? null : PhotoId.identify(head);
        } catch (IOException | RuntimeException ex) {
            k = null;
        }
        read.put(i, k);
        return k;
    }

    /** Written by the camera as it saved it (identify.py's CAMERA_KINDS): any kind but OTHER. */
    private static boolean camera(PhotoId.Result k) {
        return k != null && k.kind != PhotoId.Kind.OTHER;
    }

    /** A photo's path and, when its file time is known, when it was taken. */
    private static String which(Entry e, TimeZone tz) {
        return e.path + (e.mtime > 0 ? ", taken " + date(e, tz) : ", time unknown");
    }

    /** The lines one per line (String.join needs Android 8; the app runs from Android 7). */
    private static String lines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(sb.length() == 0 ? "" : "\n").append(l);
        }
        return sb.toString();
    }

    private static String joined(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(sb.length() == 0 ? "" : "; ").append(p);
        }
        return sb.toString();
    }
}
