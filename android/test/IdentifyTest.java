package io.github.daemonddev.charmera;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Plain-JVM tests for the "Identify this camera's firmware" route's logic. Synthetic headers (PhotoIdTest's) stand in
 * for the photos: the v006 family's still header, stock v012's bare one and our v0.26 EXIF mod's one. The rules: the
 * answer assumes a stock camera; our EXIF mod and two families on one card are the exceptions, said in a "does not
 * look stock" line; the base goes by the oldest family; photos with no file time are left out of the dating.
 */
public final class IdentifyTest {

    private static int passes = 0;
    private static int failures = 0;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long DAY = 24L * 3600 * 1000;
    private static final long T0 = 1760000000000L;      // Oct 2025
    /** How many headers the entries made by photo() have handed out, to check the cap. */
    private static int reads = 0;

    public static void main(String[] args) {
        byte[] pub = PhotoIdTest.jpeg(PhotoIdTest.seg(0xFE, "GPEncoder      ".getBytes(StandardCharsets.US_ASCII)),
                PhotoIdTest.exif(true, "Generalplus", "CBB3"));
        byte[] bare = PhotoIdTest.jpeg(PhotoIdTest.seg(0xFE, "GPEncoder".getBytes(StandardCharsets.US_ASCII)));
        byte[] exif = PhotoIdTest.factoryHeader(PhotoId.FACTORY_EXIF_HEADER, "Kodak", "Charmera");
        byte[] junk = {1, 2, 3, 4};
        expect("the synthetic headers are the three kinds", PhotoId.identify(pub).kind == PhotoId.Kind.PUBLIC_FAMILY
                && PhotoId.identify(bare).kind == PhotoId.Kind.ORIGINAL_BARE
                && PhotoId.identify(exif).kind == PhotoId.Kind.FACTORY_EXIF);

        // an empty card: no verdict, the caveats all the same
        Identify.Result r = Identify.identify(new ArrayList<>(), UTC);
        expect("an empty card has no base", r.base == Identify.Base.UNKNOWN && r.confidence == Identify.Confidence.LOW
                && r.photos == 0 && r.oldest == null && r.conclusion.contains("no photos")
                && r.conclusion.contains("Take two or three with the camera first"));
        expect("the two caveats are always there", r.caveats.contains(Identify.CAVEAT_V008)
                && r.caveats.contains(Identify.CAVEAT_AFTER_FLASH));
        expect("the v008 caveat says both are stock SP140A builds and either base works",
                Identify.CAVEAT_V008.contains("stock builds for SP140A")
                        && Identify.CAVEAT_V008.contains("suggests base v006 for either"));
        expect("the flash caveat says photos show the firmware that took them",
                Identify.CAVEAT_AFTER_FLASH.startsWith("Photos show the firmware that took them")
                        && Identify.CAVEAT_AFTER_FLASH.contains("freezes in Photo mode")
                        && Identify.CAVEAT_AFTER_FLASH.contains("only photos from before the write say what fits"));

        // stock v012: bare everywhere, read in time order, not list order
        List<Identify.Entry> card = new ArrayList<>();
        card.add(photo("DCIM/PICT0002.jpg", T0 + DAY, bare));
        card.add(photo("DCIM/PICT0001.jpg", T0, bare));
        card.add(photo("DCIM/PICT0003.jpg", T0 + 2 * DAY, bare));
        r = Identify.identify(card, UTC);
        expect("the oldest and newest go by time, not by list order", "DCIM/PICT0001.jpg".equals(r.oldest.path)
                && "DCIM/PICT0003.jpg".equals(r.newest.path));
        expect("bare everywhere is stock v012, high", r.base == Identify.Base.V012
                && r.family == Identify.Base.V012 && r.confidence == Identify.Confidence.HIGH && r.changes.isEmpty()
                && r.conclusion.startsWith("These photos were taken by stock v012 (an OV9732 camera). If firmware was written to the camera after them, it runs something else now.") && r.notStock.isEmpty()
                && !r.conclusion.contains("does not look stock") && !r.conclusion.contains("OV9732 port on v006"));
        expect("the evidence names both ends", any(r.evidence, "Oldest camera-written: DCIM/PICT0001.jpg")
                && any(r.evidence, "Newest camera-written: DCIM/PICT0003.jpg"));
        expect("dates carry the year", any(r.evidence, "taken " + Identify.date(card.get(1), UTC))
                && Identify.date(card.get(1), UTC).startsWith("2025-10-"));
        consistent("stock v012", r);

        // stock v006 family: two agree, not capped: high, and the OV9732-port line instead of a lower confidence;
        // OTHER and unreadable photos at the ends are skipped and counted
        card = new ArrayList<>();
        card.add(photo("DCIM/PICT0001.jpg", T0, junk));
        card.add(photo("DCIM/PICT0002.jpg", T0 + DAY, pub));
        card.add(photo("DCIM/PICT0003.jpg", T0 + 2 * DAY, pub));
        card.add(photo("DCIM/PICT0004.jpg", T0 + 3 * DAY, junk));
        card.add(broken("DCIM/PICT0005.jpg", T0 + 4 * DAY));
        r = Identify.identify(card, UTC);
        expect("OTHER at either end is skipped", "DCIM/PICT0002.jpg".equals(r.oldest.path)
                && "DCIM/PICT0003.jpg".equals(r.newest.path));
        expect("OTHER and unreadable ones are counted", r.other == 2 && r.unreadable == 1 && r.scanned == 5
                && r.recognised == 2);
        expect("and named", any(r.evidence, "DCIM/PICT0001.jpg, taken") && any(r.evidence, "not recognised")
                && any(r.evidence, "DCIM/PICT0005.jpg: could not be read."));
        expect("the v006 family is stock v006 or v008_test, high", r.base == Identify.Base.V006_FAMILY
                && r.confidence == Identify.Confidence.HIGH && r.notStock.isEmpty()
                && r.conclusion.startsWith("These photos were taken by stock v006 or v008_test (an SP140A camera)."));
        expect("the v006 family's base is v006, as identify.py's, and the line says where each v006 file is written",
                r.conclusion.contains("\nYour base is v006. For mods, pick base v006 on \"Write custom firmware\". Stock"
                        + " v006 is written on \"Restore stock firmware\" or Advanced.")
                && Identify.nextStep(Identify.Base.V012).equals("Pick base v012 on \"Write custom firmware\".")
                && Identify.baseName(Identify.Base.V006_FAMILY).equals("v006")
                && Identify.stockName(Identify.Base.V006_FAMILY).equals("v006 or v008_test"));
        expect("all read agree: high, and why", r.confidenceWhy.equals("all 2 camera-written photos read show the same"
                + " family, oldest to newest"));
        expect("the v006 family adds the one OV9732-port line", r.conclusion.contains(Identify.OUR_OV9732_PORT)
                && r.conclusion.indexOf("OV9732 port on v006") == r.conclusion.lastIndexOf("OV9732 port on v006"));
        consistent("stock v006", r);

        // a single camera-written photo: medium
        card = new ArrayList<>();
        card.add(photo("DCIM/PICT0001.jpg", T0, bare));
        r = Identify.identify(card, UTC);
        expect("one photo is both the oldest and the newest", r.oldest == r.newest && r.evidence.size() >= 2
                && !any(r.evidence, "Newest camera-written"));
        expect("a single photo is medium", r.base == Identify.Base.V012 && r.confidence == Identify.Confidence.MEDIUM
                && r.conclusion.startsWith("These photos were taken by stock v012 (an OV9732 camera). If firmware was written to the camera after them, it runs something else now."));

        // only OTHER photos: no verdict
        card = new ArrayList<>();
        card.add(photo("DCIM/A.jpg", T0, junk));
        card.add(photo("DCIM/B.jpg", T0 + DAY, junk));
        r = Identify.identify(card, UTC);
        expect("no recognised photo, no base", r.base == Identify.Base.UNKNOWN && r.oldest == null && r.other == 2
                && r.conclusion.contains("None of the photos") && !r.conclusion.contains("Your base is"));

        // our EXIF mod after stock v012's bare photos: one family, not a change; not stock; base v012, high
        card = new ArrayList<>();
        card.add(photo("DCIM/PICT0001.jpg", T0, bare));
        card.add(photo("DCIM/PICT0002.jpg", T0 + DAY, bare));
        card.add(photo("DCIM/PICT0003.jpg", T0 + 2 * DAY, exif));
        card.add(photo("DCIM/PICT0004.jpg", T0 + 3 * DAY, exif));
        r = Identify.identify(card, UTC);
        expect("bare then EXIF mod is one family", r.changes.isEmpty() && r.exifMod && r.family == Identify.Base.V012);
        expect("the EXIF mod is not stock, base v012, high", r.base == Identify.Base.V012
                && r.confidence == Identify.Confidence.HIGH && r.notStock.size() == 1
                && r.notStock.get(0).startsWith("This camera does not look stock: our EXIF mod (Software 'v0.26')"
                + " is installed") && !r.conclusion.contains("taken by stock"));
        expect("the EXIF mod's install falls between the last bare and the first EXIF photo",
                r.notStock.get(0).contains("installed between " + Identify.date(card.get(1), UTC) + " and "
                        + Identify.date(card.get(2), UTC)));
        consistent("bare then EXIF mod", r);

        // our EXIF mod everywhere: no date range, still base v012, high
        card = new ArrayList<>();
        card.add(photo("DCIM/PICT0001.jpg", T0, exif));
        r = Identify.identify(card, UTC);
        expect("the EXIF mod alone is base v012, high, without a range", r.base == Identify.Base.V012
                && r.confidence == Identify.Confidence.HIGH && r.notStock.size() == 1
                && !r.notStock.get(0).contains("installed between"));
        consistent("EXIF mod only", r);

        // two families: the v006 family until a flash, our EXIF mod after it; the base goes by the oldest, low
        card = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            card.add(photo(String.format(Locale.ROOT, "DCIM/PICT%04d.jpg", i + 1), T0 + i * DAY, pub));
        }
        for (int i = 4; i < 7; i++) {
            card.add(photo(String.format(Locale.ROOT, "DCIM/PICT%04d.jpg", i + 1), T0 + i * DAY, exif));
        }
        r = Identify.identify(card, UTC);
        expect("two families: one change", r.changes.size() == 1
                && r.changes.get(0).from == PhotoId.Kind.PUBLIC_FAMILY && r.changes.get(0).to == PhotoId.Kind.FACTORY_EXIF);
        expect("the change sits between the last old and the first new photo",
                "DCIM/PICT0004.jpg".equals(r.changes.get(0).before.path)
                        && "DCIM/PICT0005.jpg".equals(r.changes.get(0).after.path));
        expect("the change's dates are in the evidence", any(r.evidence, "Family changes between DCIM/PICT0004.jpg,"
                + " taken " + Identify.date(card.get(3), UTC)) && any(r.evidence, "DCIM/PICT0005.jpg, taken "
                + Identify.date(card.get(4), UTC)));
        expect("two families: base from the oldest family, low", r.base == Identify.Base.V006_FAMILY
                && r.family == Identify.Base.V006_FAMILY && r.confidence == Identify.Confidence.LOW
                && r.oldestKind == PhotoId.Kind.PUBLIC_FAMILY && r.newestKind == PhotoId.Kind.FACTORY_EXIF);
        expect("two families say so, with both dates", r.notStock.size() == 2 && r.notStock.get(0).equals(
                "This camera does not look stock: photos from two firmware families, the v006 family up to "
                        + Identify.date(card.get(3), UTC) + " and the v012 family from " + Identify.date(card.get(4), UTC)
                        + ". Either firmware was written to the camera in between, or the card holds photos from two cameras."));
        expect("and the EXIF mod as well, without weakening the base", r.notStock.get(1).contains("our EXIF mod")
                && r.conclusion.contains("Your base is v006. That's from the oldest photos, since")
                && !r.conclusion.contains("Your base is v012"));
        consistent("v006 then EXIF mod", r);

        // two families the other way round: never the newest family's base
        card = new ArrayList<>();
        card.add(photo("DCIM/PICT0001.jpg", T0, bare));
        card.add(photo("DCIM/PICT0002.jpg", T0 + DAY, bare));
        card.add(photo("DCIM/PICT0003.jpg", T0 + 2 * DAY, pub));
        r = Identify.identify(card, UTC);
        expect("v012 then the v006 family is base v012, low", r.base == Identify.Base.V012
                && r.confidence == Identify.Confidence.LOW && r.changes.size() == 1 && r.notStock.size() == 1
                && r.notStock.get(0).contains("the v012 family up to") && r.conclusion.contains(Identify.OUR_OV9732_PORT));
        consistent("v012 then v006", r);

        // there and back: two changes, both found
        card = new ArrayList<>();
        card.add(photo("DCIM/PICT0001.jpg", T0, bare));
        card.add(photo("DCIM/PICT0002.jpg", T0 + DAY, pub));
        card.add(photo("DCIM/PICT0003.jpg", T0 + 2 * DAY, bare));
        r = Identify.identify(card, UTC);
        expect("two changes are both found", r.changes.size() == 2 && r.base == Identify.Base.V012
                && r.confidence == Identify.Confidence.LOW && r.runs.size() == 3 && r.notStock.get(0).equals(
                "This camera does not look stock: photos from two firmware families, which change 2 times by date (the"
                        + " v012 family " + Identify.date(card.get(0), UTC) + " to " + Identify.date(card.get(0), UTC)
                        + "; the v006 family " + Identify.date(card.get(1), UTC) + " to " + Identify.date(card.get(1), UTC)
                        + "; the v012 family " + Identify.date(card.get(2), UTC) + " to " + Identify.date(card.get(2), UTC)
                        + "). Either firmware was written to the camera more than once, or the card holds photos from more than one"
                        + " camera."));

        // photos with no file time are left out of the dating: never read, counted and named
        card = new ArrayList<>();
        card.add(new Identify.Entry("DCIM/PICT0009.jpg", 0, false, () -> {
            throw new AssertionError("a photo with no file time was read");
        }));
        card.add(photo("DCIM/PICT0001.jpg", T0, bare));
        card.add(photo("DCIM/PICT0002.jpg", T0 + DAY, bare));
        r = Identify.identify(card, UTC);
        expect("a photo with no file time is not the oldest", "DCIM/PICT0001.jpg".equals(r.oldest.path)
                && r.undated == 1 && r.photos == 3 && r.scanned == 2 && !r.capped);
        expect("and makes no change or lower confidence", r.changes.isEmpty() && r.base == Identify.Base.V012
                && r.confidence == Identify.Confidence.HIGH);
        expect("it is counted and named", r.conclusion.contains("1 photo has no file time and was left out of the"
                + " dating.") && any(r.evidence, "DCIM/PICT0009.jpg: no file time."));
        List<Identify.Entry> undated = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            undated.add(photo("DCIM/U" + i + ".jpg", 0, pub));
        }
        reads = 0;
        r = Identify.identify(undated, UTC);
        expect("a card of undated photos has no base and reads nothing", reads == 0 && r.undated == 3
                && r.base == Identify.Base.UNKNOWN && r.conclusion.contains("No photo on the card has a file time")
                && any(r.evidence, "3 photos have no file time and were left out of the dating"));

        // videos are ignored for the verdict and never read
        card = new ArrayList<>();
        card.add(new Identify.Entry("DCIM/MOVI0001.avi", T0, true, () -> {
            throw new AssertionError("a video was read");
        }));
        r = Identify.identify(card, UTC);
        expect("a card of videos has no base", r.videos == 1 && r.photos == 0 && r.base == Identify.Base.UNKNOWN
                && r.conclusion.contains("Videos don't carry"));
        card.add(photo("DCIM/PICT0001.jpg", T0 - DAY, bare));
        card.add(new Identify.Entry("DCIM/MOVI0002.avi", T0 + DAY, true, () -> pub));
        r = Identify.identify(card, UTC);
        expect("videos do not count as photos", r.videos == 2 && r.photos == 1 && r.scanned == 1
                && r.base == Identify.Base.V012 && "DCIM/PICT0001.jpg".equals(r.newest.path));

        // the cap: no more headers are read than it allows, and the ends still come first
        card = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            card.add(photo(String.format(Locale.ROOT, "DCIM/PICT%04d.jpg", i + 1), T0 + i * 60000L,
                    i < 600 ? bare : pub));
        }
        reads = 0;
        r = Identify.identify(card, UTC);
        expect("at most SCAN_CAP headers are read", reads == Identify.SCAN_CAP && r.scanned == Identify.SCAN_CAP
                && r.capped && r.photos == 1200);
        expect("the ends are exact under the cap", "DCIM/PICT0001.jpg".equals(r.oldest.path)
                && "DCIM/PICT1200.jpg".equals(r.newest.path));
        expect("the change is still found under the cap", r.changes.size() == 1
                && r.changes.get(0).from == PhotoId.Kind.ORIGINAL_BARE && r.base == Identify.Base.V012
                && r.confidence == Identify.Confidence.LOW);
        expect("the cap is said", r.caveats.size() == 4 && any(r.evidence, "Only 500 of 1200 dated photos were read"));
        reads = 0;
        r = Identify.identify(card.subList(0, 600), UTC, 50);
        expect("one family under a small cap is medium", reads == 50 && r.capped
                && r.confidence == Identify.Confidence.MEDIUM && r.base == Identify.Base.V012);
        reads = 0;
        r = Identify.identify(card.subList(0, 40), UTC, 50);
        expect("under the cap every photo is read once", reads == 40 && !r.capped
                && r.confidence == Identify.Confidence.HIGH && r.caveats.size() == 3);
        // junk at both ends, under a cap: the oldest and newest camera-written photos are still among those read
        card = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            card.add(photo(String.format(Locale.ROOT, "DCIM/PICT%04d.jpg", i + 1), T0 + i * 60000L,
                    i < 3 || i >= 97 ? junk : bare));
        }
        r = Identify.identify(card, UTC, 20);
        expect("the sample always holds the oldest and newest camera-written photos",
                "DCIM/PICT0004.jpg".equals(r.oldest.path) && "DCIM/PICT0097.jpg".equals(r.newest.path)
                        && r.scanned == Identify.sample(100, 20, 3, 96).size() && r.scanned == 22
                        && r.skippedNewest == 3);
        // only junk, more than the cap: reading stops at the cap
        List<Identify.Entry> junkCard = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            junkCard.add(photo("DCIM/J" + i + ".jpg", T0 + i, junk));
        }
        reads = 0;
        r = Identify.identify(junkCard, UTC, 10);
        // identify.py: up to 10 from each end (0-9, 29-20), then the 10 evenly spaced (adds 10, 13, 16, 19)
        expect("a card of junk stops at the cap at each end and the sample", reads == 24 && r.other == 24
                && r.headersRead == 24 && r.scanned == 10 && r.base == Identify.Base.UNKNOWN
                && r.conclusion.contains("None of the photos") && any(r.evidence, "...and 14 more not recognised"));

        // Each end search has a cap of its own, then the evenly spaced sample (identify.py's first_camera and
        // sample(keep=...)): 8 junk photos at the oldest end no longer use up the budget for the photos between.
        card = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            card.add(photo(String.format(Locale.ROOT, "DCIM/PICT%04d.jpg", i + 1), T0 + i * DAY,
                    i < 8 ? junk : i < 30 ? bare : pub));
        }
        reads = 0;
        r = Identify.identify(card, UTC, 10);
        expect("each end has its own cap", "DCIM/PICT0009.jpg".equals(r.oldest.path)
                && "DCIM/PICT0040.jpg".equals(r.newest.path));
        expect("the sample is identify.py's: 10 evenly spaced and the oldest found",
                Identify.sample(40, 10, 8, 39).toString().equals("[0, 4, 8, 9, 13, 17, 22, 26, 30, 35, 39]")
                        && r.scanned == 11 && r.capped && r.recognised == 9);
        expect("the headers read are the end searches' and the sample's", reads == 17 && r.headersRead == 17
                && r.other == 8 && any(r.evidence, "read 17 (11 of them for the families)"));
        expect("the change-over falls between the same photos as identify.py's", r.changes.size() == 1
                && "DCIM/PICT0027.jpg".equals(r.changes.get(0).before.path)
                && "DCIM/PICT0031.jpg".equals(r.changes.get(0).after.path)
                && r.notStock.get(0).contains("the v012 family up to " + Identify.date(card.get(26), UTC)
                + " and the v006 family from " + Identify.date(card.get(30), UTC)));
        expect("a capped change says its dates are approximate", r.confidence == Identify.Confidence.LOW
                && r.confidenceWhy.endsWith("; read 11 of 40 dated photo headers, so the change-over dates are"
                + " approximate"));
        expect("Python's round: half to even", Identify.sample(6, 5, -1, -1).toString().equals("[0, 1, 2, 4, 5]")
                && Identify.sample(5, 5, -1, -1).size() == 5
                && Identify.sample(7, 1, -1, -1).toString().equals("[6]"));

        // the newest end: files that are not the camera's own are skipped, and a note says so (identify.py's notes)
        card = new ArrayList<>();
        card.add(photo("DCIM/PICT0001.jpg", T0, bare));
        card.add(photo("DCIM/PICT0002.jpg", T0 + DAY, bare));
        card.add(photo("DCIM/PICT0003.jpg", T0 + 2 * DAY, junk));
        card.add(photo("DCIM/PICT0004.jpg", T0 + 3 * DAY, junk));
        r = Identify.identify(card, UTC);
        expect("the newest 2 skipped get a note", r.skippedNewest == 2 && "DCIM/PICT0002.jpg".equals(r.newest.path)
                && r.notes.contains("The newest 2 photo files were not the camera's own and were skipped.")
                && r.conclusion.contains("The newest 2 photo files were not the camera's own"));
        card.remove(3);
        r = Identify.identify(card, UTC);
        expect("one skipped: singular", r.skippedNewest == 1
                && r.conclusion.contains("The newest photo file was not the camera's own and was skipped."));
        card.remove(2);
        r = Identify.identify(card, UTC);
        expect("none skipped: no note", r.skippedNewest == 0 && r.notes.isEmpty());

        // an exif-fix --keep-make copy (Generalplus/CBB3, no Software) is not camera-written: skipped, not v006 family
        byte[] keepMake = PhotoIdTest.jpeg(PhotoIdTest.seg(0xFE, "GPEncoder      ".getBytes(StandardCharsets.US_ASCII)),
                PhotoIdTest.exif(true, "Generalplus", "CBB3", null));
        card = new ArrayList<>();
        card.add(photo("DCIM/PICT0001.jpg", T0, pub));
        card.add(photo("DCIM/PICT0002.jpg", T0 + DAY, pub));
        card.add(photo("DCIM/PICT0003.jpg", T0 + 2 * DAY, keepMake));
        r = Identify.identify(card, UTC);
        expect("a --keep-make copy is skipped", "DCIM/PICT0002.jpg".equals(r.newest.path) && r.recognised == 2
                && r.other == 1 && r.skippedNewest == 1 && any(r.evidence, "an exif-fix copy"));
        card = new ArrayList<>();
        card.add(photo("DCIM/PICT0001.jpg", T0, bare));
        card.add(photo("DCIM/PICT0002.jpg", T0 + DAY, keepMake));
        card.add(photo("DCIM/PICT0003.jpg", T0 + 2 * DAY, bare));
        r = Identify.identify(card, UTC);
        expect("and makes no second family", r.changes.isEmpty() && r.base == Identify.Base.V012
                && r.notStock.isEmpty() && !r.conclusion.contains(Identify.OUR_OV9732_PORT));
        // an exif-fix copy of a bare photo (Kodak/Charmera, no Software) is not our EXIF mod
        card = new ArrayList<>();
        card.add(photo("DCIM/PICT0001.jpg", T0, bare));
        card.add(photo("DCIM/PICT0002.jpg", T0 + DAY, PhotoIdTest.factoryHeader(PhotoId.FACTORY_EXIF_HEADER,
                PhotoIdTest.exif(true, "Kodak", "Charmera", null))));
        r = Identify.identify(card, UTC);
        expect("an exif-fix copy of a bare photo is no EXIF mod", !r.exifMod && r.notStock.isEmpty()
                && r.base == Identify.Base.V012 && r.skippedNewest == 1);

        // no camera-written photo within the cap at the newest end: identify.py concludes nothing
        card = new ArrayList<>();
        card.add(photo("DCIM/PICT0001.jpg", T0, bare));
        for (int i = 1; i < 6; i++) {
            card.add(photo(String.format(Locale.ROOT, "DCIM/PICT%04d.jpg", i + 1), T0 + i * DAY, junk));
        }
        r = Identify.identify(card, UTC, 3);
        expect("no newest within the cap: no answer", r.base == Identify.Base.UNKNOWN && r.newest == null
                && r.oldest != null && r.conclusion.contains("None of the newest 3 photos")
                && !r.conclusion.contains("Your base is") && r.notes.isEmpty());
        // none at the oldest end within the cap, but one at the newest: the answer goes by those read
        card = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            card.add(photo(String.format(Locale.ROOT, "DCIM/PICT%04d.jpg", i + 1), T0 + i * DAY, junk));
        }
        card.add(photo("DCIM/PICT0006.jpg", T0 + 5 * DAY, bare));
        card.add(photo("DCIM/PICT0007.jpg", T0 + 6 * DAY, bare));
        r = Identify.identify(card, UTC, 3);
        expect("no oldest within the cap: the newest still answers", r.oldest == null
                && "DCIM/PICT0007.jpg".equals(r.newest.path) && r.base == Identify.Base.V012
                && r.confidence == Identify.Confidence.MEDIUM && r.recognised == 1
                && any(r.evidence, "None of the oldest 3 photos read is camera-written."));

        expect("labels name the family", Identify.label(PhotoId.Kind.PUBLIC_FAMILY).equals(
                "v006 family (stock v006 or v008_test, SP140A cameras)")
                && Identify.label(PhotoId.Kind.ORIGINAL_BARE).equals("v012 family (stock v012, OV9732 cameras)")
                && Identify.label(PhotoId.Kind.FACTORY_EXIF).equals("our EXIF mod, v012 family"));

        System.out.printf(Locale.ROOT, "IdentifyTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** The conclusion, the base line and the base field agree; a "taken by stock" line names that same base. */
    static void consistent(String what, Identify.Result r) {
        String name = Identify.baseName(r.base);
        boolean ok = r.base != Identify.Base.UNKNOWN && r.family == r.base
                && r.conclusion.contains("Your base is " + name + ".");
        for (Identify.Base b : Identify.Base.values()) {
            if (b != r.base && b != Identify.Base.UNKNOWN) {
                ok &= !r.conclusion.contains("taken by stock " + Identify.stockName(b))
                        && !r.conclusion.contains("show stock " + Identify.stockName(b))
                        && !r.conclusion.contains("Your base is " + Identify.baseName(b) + ".");
            }
        }
        for (String line : r.notStock) {
            ok &= r.conclusion.contains(line);
        }
        expect(what + ": the answer does not contradict itself", ok);
    }

    static Identify.Entry photo(String path, long mtime, byte[] head) {
        return new Identify.Entry(path, mtime, false, () -> {
            reads++;
            return head;
        });
    }

    /** A photo whose start the drive does not hand over. */
    static Identify.Entry broken(String path, long mtime) {
        return new Identify.Entry(path, mtime, false, () -> {
            reads++;
            throw new IOException("the drive did not answer");
        });
    }

    static boolean any(List<String> lines, String part) {
        for (String s : lines) {
            if (s.contains(part)) {
                return true;
            }
        }
        return false;
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
