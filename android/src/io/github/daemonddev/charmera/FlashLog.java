package io.github.daemonddev.charmera;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * What the app did to this camera and what the owner saw: written, flash seen or not, update file removed, photo
 * check, backup saved. No Android imports, so it runs on a plain JVM in android/test/FlashLogTest.java; MainActivity
 * keeps the serialized log in its preferences.
 *
 * The list is newest first and holds at most MAX entries; the oldest fall off. The log is one String: a header line,
 * then one line per entry with its fields separated by '|', and '\', '|', CR and LF inside a field escaped, so any text
 * round-trips. parse() never throws: a line it cannot read is skipped, and anything that is not a log reads as empty.
 *
 * The log records what the owner told the app and what the app did. It is not proof of what the camera runs:
 * summary() says the latest state the log knows, or "unknown".
 */
final class FlashLog {

    static final int MAX = 200;
    static final String HEADER = "charmera-flash-log 1";
    static final String UNKNOWN = "unknown";

    enum Event {
        WRITTEN("written to the card"),
        FLASH_SEEN("flash seen"),
        FLASH_NOT_SEEN("flash not seen"),
        FILE_REMOVED("update file removed"),
        CHECK_OK("photo check OK"),
        CHECK_FAILED("photo check failed"),
        BACKUP_SAVED("backup saved");

        /** The short wording the log page and summary() show. */
        final String label;

        Event(String label) {
            this.label = label;
        }
    }

    static final class Entry {
        final long timeMs;
        final Event event;
        /** The firmware it concerns, as the app names it ("v0.23", "stock restore", "probe"); "" when none. */
        final String firmware;
        /** The start of the file's SHA-256 in lowercase hex; "" when none. */
        final String sha256Prefix;
        /** Free text; "" when none. */
        final String note;

        Entry(long timeMs, Event event, String firmware, String sha256Prefix, String note) {
            if (event == null) {
                throw new IllegalArgumentException("a log entry needs an event");
            }
            this.timeMs = timeMs;
            this.event = event;
            this.firmware = firmware == null ? "" : firmware;
            this.sha256Prefix = sha256Prefix == null ? "" : sha256Prefix;
            this.note = note == null ? "" : note;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** Newest first, read-only. */
    List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    int size() {
        return entries.size();
    }

    void clear() {
        entries.clear();
    }

    /** Adds e in time order (newest first; among equal times, the one added last first) and drops the oldest past MAX. */
    void add(Entry e) {
        int i = 0;
        while (i < entries.size() && entries.get(i).timeMs > e.timeMs) {
            i++;
        }
        entries.add(i, e);
        while (entries.size() > MAX) {
            entries.remove(entries.size() - 1);
        }
    }

    void add(long timeMs, Event event, String firmware, String sha256Hex, String note) {
        add(new Entry(timeMs, event, firmware, shaPrefix(sha256Hex), note));
    }

    /** The first 12 hex digits of a SHA-256, lowercase; "" for null. */
    static String shaPrefix(String sha256Hex) {
        if (sha256Hex == null) {
            return "";
        }
        String s = sha256Hex.trim().toLowerCase(Locale.ROOT);
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    // --- one String --------------------------------------------------------------------------------------------

    String serialize() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Entry e : entries) {
            sb.append(e.timeMs).append('|').append(e.event.name()).append('|').append(escape(e.firmware)).append('|')
                    .append(escape(e.sha256Prefix)).append('|').append(escape(e.note)).append('\n');
        }
        return sb.toString();
    }

    /** The log in text; an empty log for null, for text that is not a log, and without the lines it cannot read. */
    static FlashLog parse(String text) {
        FlashLog log = new FlashLog();
        if (text == null) {
            return log;
        }
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals(HEADER)) {
            return log;
        }
        for (int n = 1; n < lines.length; n++) {
            Entry e = parseLine(lines[n]);
            if (e != null) {
                log.add(e);
            }
        }
        return log;
    }

    private static Entry parseLine(String line) {
        if (line.isEmpty()) {
            return null;
        }
        String[] f = line.split("\\|", -1);   // escaped fields hold no raw '|'
        if (f.length != 5) {
            return null;
        }
        try {
            long time = Long.parseLong(f[0]);
            Event event = Event.valueOf(f[1]);
            String firmware = unescape(f[2]);
            String sha = unescape(f[3]);
            String note = unescape(f[4]);
            if (firmware == null || sha == null || note == null) {
                return null;
            }
            return new Entry(time, event, firmware, sha, note);
        } catch (IllegalArgumentException e) {   // NumberFormatException too
            return null;
        }
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '|': sb.append("\\p"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The text escape() made, or null for an escape it never writes. */
    static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= s.length()) {
                return null;
            }
            switch (s.charAt(i)) {
                case '\\': sb.append('\\'); break;
                case 'p': sb.append('|'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                default: return null;
            }
        }
        return sb.toString();
    }

    // --- what the log says -------------------------------------------------------------------------------------

    /** A time as the log shows it, e.g. "11 Nov 13:02". */
    static String when(long timeMs, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH);
        f.setTimeZone(tz);
        return f.format(new Date(timeMs));
    }

    /**
     * The latest camera firmware state the log knows, in one short line, e.g. "v0.23 — flash seen 11 Nov 13:02",
     * or "unknown". Removing the update file and saving a backup say nothing about the firmware and are passed over.
     */
    String summary(TimeZone tz) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String at = when(e.timeMs, tz);
            switch (e.event) {
                case FLASH_SEEN:
                    return named(e.firmware, i) + " — flash seen " + at;
                case FLASH_NOT_SEEN:
                    return UNKNOWN + " — " + named(e.firmware, i) + " flash not seen " + at;
                case WRITTEN:
                    return named(e.firmware, i) + " written " + at + ", flash not confirmed";
                case CHECK_OK:
                    // the photo's header shows only the public-build family, not which of its files was written
                    return named(e.firmware, i) + " written; new photo has the public-build family's header " + at;
                case CHECK_FAILED:
                    return UNKNOWN + " — photo check failed " + at;
                default:
                    break;
            }
        }
        return UNKNOWN;
    }

    /**
     * The firmware the owner saw the camera take, for a file name that carries it (the test-shot zip's): the latest
     * firmware state must be a flash seen of a named file, or a photo check that passed whose nearest older entry
     * naming a firmware is a flash seen of that same file. The check alone cannot say which file it was (the stock
     * restore file, the probe and the bring-up write the same header), so it names one only after a flash seen. Null
     * when the latest state is a write not yet confirmed, a flash not seen, a failed check or nothing, and for a
     * check that follows a write or a flash not seen. Removing the update file and saving a backup are passed over.
     */
    String confirmedFirmware() {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            switch (e.event) {
                case FLASH_SEEN:
                    return e.firmware.isEmpty() ? null : e.firmware;
                case CHECK_OK:
                    Entry before = olderNaming(i);
                    return before != null && before.event == Event.FLASH_SEEN
                            && (e.firmware.isEmpty() || e.firmware.equals(before.firmware)) ? before.firmware : null;
                case FLASH_NOT_SEEN:
                case WRITTEN:
                case CHECK_FAILED:
                    return null;
                default:
                    break;
            }
        }
        return null;
    }

    /**
     * The nearest entry older than the one at index from that names a firmware and is a firmware state (removing the
     * update file and saving a backup are not); null when there is none.
     */
    private Entry olderNaming(int from) {
        for (int i = from + 1; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.firmware.isEmpty() && e.event != Event.FILE_REMOVED && e.event != Event.BACKUP_SAVED) {
                return e;
            }
        }
        return null;
    }

    /** e's firmware, or when it names none, that of the nearest older entry that does; "firmware" when none does. */
    private String named(String firmware, int from) {
        String n = namedOrNull(firmware, from);
        return n == null ? "firmware" : n;
    }

    private String namedOrNull(String firmware, int from) {
        if (!firmware.isEmpty()) {
            return firmware;
        }
        for (int i = from + 1; i < entries.size(); i++) {
            if (!entries.get(i).firmware.isEmpty()) {
                return entries.get(i).firmware;
            }
        }
        return null;
    }
}
