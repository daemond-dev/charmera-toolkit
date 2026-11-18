package io.github.daemonddev.charmera;

import java.util.Locale;
import java.util.TimeZone;

/** Plain-JVM tests for the flash log: order, cap, the one-String form with awkward text, and the one-line summary. */
public final class FlashLogTest {

    private static int passes = 0;
    private static int failures = 0;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    /** 11 Nov 2026 13:02 UTC. */
    private static final long T = 1794402120000L;
    private static final long MIN = 60000L;

    public static void main(String[] args) {
        expect("the time format is the one the log shows", FlashLog.when(T, UTC).equals("11 Nov 13:02"));

        FlashLog empty = new FlashLog();
        expect("an empty log's summary is unknown", empty.summary(UTC).equals("unknown"));
        expect("null, junk and a headerless text parse as empty", FlashLog.parse(null).size() == 0
                && FlashLog.parse("nonsense\n1|WRITTEN|a|b|c\n").size() == 0 && FlashLog.parse("").size() == 0);

        // order: newest first, whatever order entries come in
        FlashLog log = new FlashLog();
        log.add(T, FlashLog.Event.WRITTEN, "v0.23", "ABCDEF0123456789abcdef", "");
        log.add(T + 2 * MIN, FlashLog.Event.FILE_REMOVED, "", null, null);
        log.add(T + MIN, FlashLog.Event.FLASH_SEEN, "v0.23", null, "Upgrade 100%");
        expect("entries are newest first", log.entries().get(0).event == FlashLog.Event.FILE_REMOVED
                && log.entries().get(1).event == FlashLog.Event.FLASH_SEEN && log.entries().get(2).event == FlashLog.Event.WRITTEN);
        expect("the SHA-256 prefix is 12 lowercase hex digits", log.entries().get(2).sha256Prefix.equals("abcdef012345"));
        expect("missing fields read as empty", log.entries().get(0).sha256Prefix.isEmpty() && log.entries().get(0).note.isEmpty());
        expect("a removed file is passed over: the flash seen is the state",
                log.summary(UTC).equals("v0.23, flash seen 11 Nov 13:03"));
        boolean readOnly;
        try {
            log.entries().clear();
            readOnly = false;
        } catch (UnsupportedOperationException e) {
            readOnly = true;
        }
        expect("entries() is read-only", readOnly && log.size() == 3);

        // summary for each kind of latest event
        expect("written but not seen", one(FlashLog.Event.WRITTEN, "v0.23").equals("v0.23 written 11 Nov 13:02, flash not confirmed"));
        expect("flash not seen", one(FlashLog.Event.FLASH_NOT_SEEN, "v0.23").equals("unknown (v0.23 flash not seen 11 Nov 13:02)"));
        expect("check failed", one(FlashLog.Event.CHECK_FAILED, "").equals("unknown (photo check failed 11 Nov 13:02)"));
        expect("only a backup is unknown", one(FlashLog.Event.BACKUP_SAVED, "").equals("unknown"));
        FlashLog checked = new FlashLog();
        checked.add(T - MIN, FlashLog.Event.FLASH_SEEN, "v0.23", null, null);
        checked.add(T, FlashLog.Event.CHECK_OK, "", null, null);
        expect("a check without a firmware takes the one flashed before it",
                checked.summary(UTC).equals("v0.23 written, a new photo has the public-build family's header 11 Nov 13:02"));

        // the firmware a file name may carry: only a flash seen or a photo check that passed names one
        expect("a passed check names the firmware flashed before it", "v0.23".equals(checked.confirmedFirmware()));
        expect("a flash seen names its firmware", "v0.23".equals(oneLog(FlashLog.Event.FLASH_SEEN, "v0.23").confirmedFirmware()));
        expect("a write alone names none", oneLog(FlashLog.Event.WRITTEN, "v0.23").confirmedFirmware() == null);
        expect("a flash not seen names none", oneLog(FlashLog.Event.FLASH_NOT_SEEN, "v0.23").confirmedFirmware() == null);
        expect("an empty log names none", new FlashLog().confirmedFirmware() == null);
        FlashLog notSeen = new FlashLog();
        notSeen.add(T - MIN, FlashLog.Event.FLASH_SEEN, "v0.23", null, null);
        notSeen.add(T, FlashLog.Event.CHECK_FAILED, "v0.24", null, null);
        notSeen.add(T + MIN, FlashLog.Event.BACKUP_SAVED, "", null, null);
        expect("a failed check after a flash seen names none, a backup after it is passed over",
                notSeen.confirmedFirmware() == null);
        FlashLog bare = new FlashLog();
        bare.add(T, FlashLog.Event.CHECK_OK, "", null, null);
        expect("a passed check with no firmware anywhere names none", bare.confirmedFirmware() == null);
        // a passed check names a firmware only after a flash seen of that file: its header fits the whole public family
        FlashLog refused = new FlashLog();
        refused.add(T - 3 * MIN, FlashLog.Event.WRITTEN, "v0.23", "2222222222222222", null);
        refused.add(T - 2 * MIN, FlashLog.Event.FLASH_NOT_SEEN, "v0.23", "2222222222222222", null);
        refused.add(T - MIN, FlashLog.Event.FILE_REMOVED, "", null, null);
        refused.add(T, FlashLog.Event.CHECK_OK, "", null, null);
        expect("a passed check after a flash not seen names none", refused.confirmedFirmware() == null);
        FlashLog unanswered = new FlashLog();
        unanswered.add(T - MIN, FlashLog.Event.WRITTEN, "v0.23", "2222222222222222", null);
        unanswered.add(T, FlashLog.Event.CHECK_OK, "", null, null);
        expect("a passed check after a write with no flash answer names none", unanswered.confirmedFirmware() == null);
        FlashLog removed = new FlashLog();
        removed.add(T - 3 * MIN, FlashLog.Event.WRITTEN, "v0.23", "2222222222222222", null);
        removed.add(T - 2 * MIN, FlashLog.Event.FLASH_SEEN, "v0.23", "2222222222222222", null);
        removed.add(T - MIN, FlashLog.Event.FILE_REMOVED, "", null, null);
        removed.add(T, FlashLog.Event.CHECK_OK, "", null, null);
        removed.add(T + MIN, FlashLog.Event.CHECK_OK, "", null, null);
        expect("passed checks after a flash seen and a removal name the flashed firmware",
                "v0.23".equals(removed.confirmedFirmware()));
        FlashLog otherFile = new FlashLog();
        otherFile.add(T - MIN, FlashLog.Event.FLASH_SEEN, "v0.21", null, null);
        otherFile.add(T, FlashLog.Event.CHECK_OK, "v0.23", null, null);
        expect("a passed check naming another file than the flash seen names none", otherFile.confirmedFirmware() == null);
        FlashLog sameFile = new FlashLog();
        sameFile.add(T - MIN, FlashLog.Event.FLASH_SEEN, "v0.23", null, null);
        sameFile.add(T, FlashLog.Event.CHECK_OK, "v0.23", null, null);
        expect("a passed check naming the file of the flash seen names it", "v0.23".equals(sameFile.confirmedFirmware()));
        FlashLog unnamed = new FlashLog();
        unnamed.add(T - MIN, FlashLog.Event.WRITTEN, "v0.23", null, null);
        unnamed.add(T, FlashLog.Event.FLASH_SEEN, "", null, null);
        expect("a flash seen of no named file names none, not an older entry's", unnamed.confirmedFirmware() == null);

        // the one-String form round-trips awkward text
        FlashLog odd = new FlashLog();
        String nasty = "a|b\\c\nd\re \\p \\n|| — é";
        odd.add(T, FlashLog.Event.CHECK_FAILED, "stock|restore", "00ff", nasty);
        odd.add(T + MIN, FlashLog.Event.BACKUP_SAVED, "", "", "\\");
        String text = odd.serialize();
        expect("serialized text starts with the header", text.startsWith(FlashLog.HEADER + "\n"));
        expect("one line per entry", text.split("\n", -1).length == 4);
        FlashLog back = FlashLog.parse(text);
        expect("round-trip keeps every entry", back.size() == 2);
        expect("round-trip keeps every field", same(back.entries().get(1), T, FlashLog.Event.CHECK_FAILED, "stock|restore", "00ff", nasty)
                && same(back.entries().get(0), T + MIN, FlashLog.Event.BACKUP_SAVED, "", "", "\\"));
        expect("serialize is stable", back.serialize().equals(text));

        // damaged lines are skipped, not thrown
        String damaged = FlashLog.HEADER + "\n" + "x|WRITTEN|a|b|c\n" + T + "|NO_SUCH_EVENT|a|b|c\n" + T + "|WRITTEN|a|b\n"
                + T + "|WRITTEN|a\\q|b|c\n" + T + "|WRITTEN|a\\|b|c\n" + T + "|FLASH_SEEN|v0.21||ok\n";
        FlashLog d = FlashLog.parse(damaged);
        expect("only the readable line survives", d.size() == 1 && d.entries().get(0).firmware.equals("v0.21"));

        // cap
        FlashLog big = new FlashLog();
        for (int i = 0; i < 250; i++) {
            big.add(T + i * MIN, FlashLog.Event.WRITTEN, "v0." + i, null, null);
        }
        expect("the log holds at most 200 entries", big.size() == FlashLog.MAX);
        expect("the oldest fall off", big.entries().get(0).firmware.equals("v0.249")
                && big.entries().get(FlashLog.MAX - 1).firmware.equals("v0.50"));
        expect("the cap holds through a round-trip", FlashLog.parse(big.serialize()).size() == FlashLog.MAX);
        big.clear();
        expect("clear empties the log", big.size() == 0 && big.summary(UTC).equals("unknown"));

        boolean nullEvent;
        try {
            new FlashLog.Entry(T, null, "", "", "");
            nullEvent = false;
        } catch (IllegalArgumentException e) {
            nullEvent = true;
        }
        expect("an entry needs an event", nullEvent);

        System.out.printf(Locale.ROOT, "FlashLogTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    static String one(FlashLog.Event event, String firmware) {
        return oneLog(event, firmware).summary(UTC);
    }

    static FlashLog oneLog(FlashLog.Event event, String firmware) {
        FlashLog log = new FlashLog();
        log.add(T, event, firmware, null, null);
        return log;
    }

    static boolean same(FlashLog.Entry e, long time, FlashLog.Event event, String firmware, String sha, String note) {
        return e.timeMs == time && e.event == event && e.firmware.equals(firmware) && e.sha256Prefix.equals(sha)
                && e.note.equals(note);
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
