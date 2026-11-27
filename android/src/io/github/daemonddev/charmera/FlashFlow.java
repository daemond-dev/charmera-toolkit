package io.github.daemonddev.charmera;

/**
 * The progress of one flash run on a flashing route (Write custom firmware, Identify the image sensor, Restore stock
 * firmware) for one camera drive, and what the route's steps show for it. No Android imports, so it runs on a plain
 * JVM in android/test/FlashFlowTest.java; MainActivity keeps one run per drive and route in its preferences, so a run
 * survives Android ending the app's process while the camera is away from the phone.
 *
 * The run moves on only through explicit events: a write that worked (or the same file found on the card), the
 * owner's answer to "Did the camera show Upgrade 100%?", a removal that worked, a listing of the card, a check after
 * the flash. Whether the drive is plugged in now never moves it: the camera is unplugged on purpose to flash it, so a
 * drive seen gone or back changes only the drive step's line (and, in the screen, which buttons work). The eject is
 * advice, not a gate: the write was synced before it reported success, so the answer counts whether or not an eject
 * was seen.
 *
 * Stages, in order: NONE (nothing written this run), WRITTEN (the file is on the card, written or found there),
 * ANSWERED (the owner answered the Upgrade question), REMOVING (a removal worked, and no listing since has shown the
 * card without an update file), REMOVED (a listing after it found none), CHECKED (the flash check or the sensor read).
 *
 * view() says for each step of the route whether it is done, open or locked, and which step is current. A locked
 * step's note always names the first open step before it, so "Do step N first" never sends the owner to a step that
 * is locked itself. The sentences that say what to do next (after an eject the app did not see, on the drive step
 * while the drive is away, under the flash question) name only steps that are open in that state, and never tell the
 * owner to switch the camera on and connect it while an update file may be on the card, except in the safe order.
 *
 * The run is one String: a header line, then its fields separated by '|', each escaped as FlashLog escapes a field, so
 * any text round-trips. parse() never throws: anything it cannot read is a run with nothing done.
 */
final class FlashFlow {

    enum Route { REPAIR, PROBE, RESTORE }

    enum Stage { NONE, WRITTEN, ANSWERED, REMOVING, REMOVED, CHECKED }

    /** The steps a flashing route can have; steps() gives each route's, in the order the screen numbers them. */
    enum Step { INTRO, BACKUP, FILE, DRIVE, WRITE, FLASH, REMOVE, CHECK, READ }

    static final String HEADER = "charmera-flash-flow 1";
    private static final int FIELDS = 18;

    /** How to connect a camera whose card may hold an update file: it must not start up with that card in it. */
    static final String SAFE_ORDER = "Switch the camera off and take the card out. Switch it on with the power button,"
            + " wait 10 seconds, put the card back in, then connect it. The app reads it as soon as it appears.";
    /** How to connect a camera whose card the last listing found without an update file. */
    static final String PLAIN_ORDER = "Switch the camera on with its power button, wait 10 seconds, then connect it. The"
            + " app reads it as soon as it appears.";
    /** The drive line for a drive ejected with the cable still in, outside the flash: nothing else to do with it now. */
    static final String EJECTED_IN = "Ejected ✓ Unplug the cable.";
    static final String CONNECT_LATER = "Connect it again when you need it.";
    static final String FLASH_NOW_PREFIX = "Let the camera write the file now and answer the question in step ";
    /** Shown only while the update file is on the card, so it carries the safe order itself. */
    static final String RECONNECT_TO_REMOVE = "Reconnect the camera to remove the update file, in this order: "
            + SAFE_ORDER;

    private static final Step[] REPAIR_STEPS = {Step.INTRO, Step.DRIVE, Step.WRITE, Step.FLASH, Step.REMOVE, Step.CHECK};
    private static final Step[] PROBE_STEPS = {Step.DRIVE, Step.WRITE, Step.FLASH, Step.REMOVE, Step.READ};
    private static final Step[] RESTORE_STEPS = {Step.BACKUP, Step.FILE, Step.DRIVE, Step.WRITE, Step.FLASH,
            Step.REMOVE};
    private static final Step[] NOTHING = new Step[0];

    /**
     * What the screen knows now that is not the run's progress: the state of the steps before the write, which count
     * until the run is past them, and whether a file other than the one written is loaded. view() reads it; no event
     * comes from it.
     */
    static final class Live {
        /** Write custom firmware: "Continue" was tapped under "Before you start". */
        boolean started;
        /** Restore: a backup of this camera is on record. */
        boolean backupOnRecord;
        /** Restore: the loaded file passed its checks against the backup on record and is this route's kind. */
        boolean fileReady;
        /** A card the app can write to is listed on the saved drive, and the drive is mounted now. */
        boolean driveReady;
        /** A loaded file this route writes, ready to write, is not the file this run wrote (or found). */
        boolean loadedOther;
    }

    /** The steps of a route as they stand: done, open (current or not) or locked, with each lock's note. */
    static final class View {
        private final Step[] order;
        private final boolean[] done;
        private final boolean[] locked;
        private final String[] note;
        private final int current;

        private View(Step[] order, boolean[] done, boolean[] locked, String[] note, int current) {
            this.order = order;
            this.done = done;
            this.locked = locked;
            this.note = note;
            this.current = current;
        }

        boolean done(Step s) {
            int i = indexOf(order, s);
            return i >= 0 && done[i];
        }

        boolean locked(Step s) {
            int i = indexOf(order, s);
            return i >= 0 && locked[i];
        }

        /** Neither done nor locked: the owner can act on it now. */
        boolean open(Step s) {
            int i = indexOf(order, s);
            return i >= 0 && !done[i] && !locked[i];
        }

        /** A locked step's note ("Do step N first.", N the first open step before it); null for any other. */
        String lockReason(Step s) {
            int i = indexOf(order, s);
            return i >= 0 && locked[i] ? note[i] : null;
        }

        /** The index of the first open step, -1 when every step is done. */
        int current() {
            return current;
        }

        /** The first open step, or null when every step is done. */
        Step currentStep() {
            return current < 0 ? null : order[current];
        }
    }

    final Route route;
    private Stage stage = Stage.NONE;
    /** The file written (or found on the card): its SHA-256, its name on the card, the firmware it carries. */
    private String sha = "";
    private String cardName = "";
    private String firmware = "";
    /** The app wrote it (false: the same file was found on the card, so it may have been flashed before). */
    private boolean wroteIt;
    private long writtenAt;
    /** The SHA-256 of the backup the file's check was made against; "" for a reviewed patch, checked without one. */
    private String backupSha = "";
    /** The answer to the Upgrade question: null before one. */
    private Boolean seen;
    /** A removal worked on this route while nothing was written (its result shows in the Remove step). */
    private boolean removedFirst;
    /** The card was listed, and what the last listing found: any update file, and one not named as the written one. */
    private boolean listed;
    private boolean listedFile;
    private boolean listedOther;
    /** A comparison found a file under the written name that is not the written file. */
    private boolean differs;
    /** The flash check found the firmware family's header, and its result as shown. */
    private boolean checkOk;
    private String checkText = "";
    /** The SENSOR.TXT the card held just after the write: null when not read, "" for none, else its identity. */
    private String sensor;
    /** An eject was seen since the write. */
    private boolean ejected;
    /** The write worked on the mount seen now (not kept in serialize(): after a restart the app cannot tell). */
    private boolean synced;

    FlashFlow(Route route) {
        if (route == null) {
            throw new IllegalArgumentException("a flash run needs a route");
        }
        this.route = route;
    }

    // --- events ----------------------------------------------------------------------------------------------------

    /**
     * A write that worked put this file on the card (wroteIt), or the same file was found there (not wroteIt): a new
     * run for it, whatever the run before had reached. time is when; backupSha the backup its check was made against.
     */
    void written(String sha, String cardName, boolean wroteIt, long time, String backupSha, String firmware) {
        clearRun();
        stage = Stage.WRITTEN;
        this.sha = nz(sha);
        this.cardName = nz(cardName);
        this.firmware = nz(firmware);
        this.wroteIt = wroteIt;
        this.writtenAt = time;
        this.backupSha = nz(backupSha);
        // The card holds this file now; whether it holds another is for the next listing to say.
        listed = true;
        listedFile = true;
        listedOther = false;
        // Only a write the app made on this mount was synced by it; a file found there says nothing about that.
        synced = wroteIt;
    }

    /** The same file was found on the card: nothing written, but the flash part starts over for it. */
    void foundOnCard(String sha, String cardName, long time, String backupSha, String firmware) {
        written(sha, cardName, false, time, backupSha, firmware);
    }

    /**
     * Android reported the drive ejected. Advice only: nothing waits for it. The synced claim stands: an eject Android
     * then reports as a pull is taken back, and a drive ejected for real is next seen gone or mounted anew.
     */
    void ejectSeen() {
        ejected = true;
    }

    /** The drive went (pulled out, or no longer there): the mount the write was synced on is over. */
    void driveLeft() {
        synced = false;
    }

    /** The drive was seen mounted anew: nothing was written on this mount. */
    void mountChanged() {
        synced = false;
    }

    /** The owner's answer to "Did the camera show Upgrade 100%?"; a later answer replaces it. */
    void answered(boolean seen) {
        if (stage == Stage.NONE) {
            return;     // no file this run: there was nothing to flash
        }
        this.seen = seen;
        if (stage == Stage.WRITTEN) {
            stage = Stage.ANSWERED;
        }
    }

    /**
     * A removal of the update files worked, started on the route on (null: elsewhere, as on Advanced). It counts as
     * done only once a listing shows the card without an update file.
     */
    void removalWorked(Route on) {
        synced = false;     // what the card holds changed, and the app sees no sync of a deletion
        differs = false;
        listedFile = false; // a removal that worked deletes every update file; a later listing says if one is back
        switch (stage) {
            case NONE:
                if (on == route) {
                    removedFirst = true;
                }
                break;
            case WRITTEN:
            case ANSWERED:
                stage = Stage.REMOVING;
                break;
            default:
                break;      // REMOVING waits for a listing; REMOVED and CHECKED: the last listing found none already
        }
    }

    /** A listing of the card's top folder, with the names of the update files in it (none: no update file). */
    void listing(String... updateFiles) {
        String[] names = updateFiles == null ? new String[0] : updateFiles;
        listed = true;
        listedFile = false;
        listedOther = false;
        for (String n : names) {
            if (n == null) {
                continue;
            }
            listedFile = true;
            if (cardName.isEmpty() || !n.equalsIgnoreCase(cardName)) {
                listedOther = true;
            }
        }
        switch (stage) {
            case ANSWERED:
            case REMOVING:
                if (!listedFile) {
                    stage = Stage.REMOVED;      // the removal reached the card (or the file went another way)
                }
                break;
            case REMOVED:
            case CHECKED:
                if (listedFile) {
                    stage = Stage.REMOVING;     // an update file is on the card again: it must go before a start-up
                }
                break;
            default:
                break;      // NONE: nothing written; WRITTEN without the file is writeAgain()'s case (Write reopens)
        }
    }

    /** A comparison found a file under the written name whose content is not the written file's. */
    void differentFileOnCard() {
        if (stage != Stage.NONE) {
            differs = true;
        }
    }

    /** The flash check (ok: the firmware family's header) or the sensor read, after the removal; text as shown. */
    void checked(boolean ok, String text) {
        if (stage != Stage.REMOVED && stage != Stage.CHECKED) {
            return;
        }
        stage = Stage.CHECKED;
        checkOk = ok;
        checkText = nz(text);
    }

    /** The SENSOR.TXT read just after the write made at writtenAt: "" for none, null when it could not be read. */
    void sensorRead(long writtenAt, String identity) {
        if (stage != Stage.NONE && writtenAt == this.writtenAt) {
            sensor = identity;
        }
    }

    /**
     * Restore: the backup on record is now nowSha (null: none). Before the answer, or after "not seen" while the flash
     * step is still open, a write checked against another backup no longer counts: the run starts over, and the card's
     * update file is what Remove is for.
     */
    void backupChanged(String nowSha) {
        if (route == Route.RESTORE && (stage == Stage.WRITTEN || stage == Stage.ANSWERED && !Boolean.TRUE.equals(seen))
                && !nz(nowSha).equals(backupSha)) {
            reset();
        }
    }

    /**
     * Nothing written this run: a write of another route's file, or a backup change. Either way the card may hold an
     * update file (the other route's, or this run's still), so until the next listing it counts as holding one.
     */
    void reset() {
        clearRun();
        listed = true;
        listedFile = true;
    }

    private void clearRun() {
        stage = Stage.NONE;
        sha = "";
        cardName = "";
        firmware = "";
        wroteIt = false;
        writtenAt = 0;
        backupSha = "";
        seen = null;
        removedFirst = false;
        listedOther = false;
        differs = false;
        checkOk = false;
        checkText = "";
        sensor = null;
        ejected = false;
        synced = false;
    }

    // --- one String ------------------------------------------------------------------------------------------------

    String serialize() {
        String[] f = {route.name(), stage.name(), sha, cardName, firmware, bit(wroteIt), Long.toString(writtenAt),
                backupSha, seen == null ? "-" : seen ? "y" : "n", bit(removedFirst), bit(listed), bit(listedFile),
                bit(listedOther), bit(differs), bit(checkOk), checkText,
                sensor == null ? "u" : sensor.isEmpty() ? "n" : "i" + sensor, bit(ejected)};
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < f.length; i++) {
            sb.append(i == 0 ? "" : "|").append(FlashLog.escape(f[i]));
        }
        return sb.append('\n').toString();
    }

    /** The run for route in text; a run with nothing done for null, for text that is not one, or another route's. */
    static FlashFlow parse(String text, Route route) {
        FlashFlow fresh = new FlashFlow(route);
        if (text == null) {
            return fresh;
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[0].trim().equals(HEADER)) {
            return fresh;
        }
        String[] raw = lines[1].split("\\|", -1);     // escaped fields hold no raw '|'
        if (raw.length != FIELDS) {
            return fresh;
        }
        String[] v = new String[FIELDS];
        for (int i = 0; i < FIELDS; i++) {
            v[i] = FlashLog.unescape(raw[i]);
            if (v[i] == null) {
                return fresh;
            }
        }
        try {
            if (!route.name().equals(v[0])) {
                return fresh;
            }
            FlashFlow r = new FlashFlow(route);
            r.stage = Stage.valueOf(v[1]);
            r.sha = v[2];
            r.cardName = v[3];
            r.firmware = v[4];
            r.wroteIt = bit(v[5]);
            r.writtenAt = Long.parseLong(v[6]);
            r.backupSha = v[7];
            if ("y".equals(v[8])) {
                r.seen = Boolean.TRUE;
            } else if ("n".equals(v[8])) {
                r.seen = Boolean.FALSE;
            } else if (!"-".equals(v[8])) {
                return fresh;
            }
            r.removedFirst = bit(v[9]);
            r.listed = bit(v[10]);
            r.listedFile = bit(v[11]);
            r.listedOther = bit(v[12]);
            r.differs = bit(v[13]);
            r.checkOk = bit(v[14]);
            r.checkText = v[15];
            if ("n".equals(v[16])) {
                r.sensor = "";
            } else if (v[16].startsWith("i")) {
                r.sensor = v[16].substring(1);
            } else if (!"u".equals(v[16])) {
                return fresh;
            }
            r.ejected = bit(v[17]);
            // Only a state the events can reach: a run past the write names its file; WRITTEN has no answer yet and
            // ANSWERED has one; REMOVED and CHECKED come from a listing that found no update file.
            boolean removed = r.stage == Stage.REMOVED || r.stage == Stage.CHECKED;
            if (r.stage != Stage.NONE && r.sha.isEmpty() || r.stage == Stage.WRITTEN && r.seen != null
                    || r.stage == Stage.ANSWERED && r.seen == null || removed && (!r.listed || r.listedFile)) {
                return fresh;
            }
            return r;
        } catch (IllegalArgumentException e) {     // NumberFormatException too
            return fresh;
        }
    }

    private static String bit(boolean b) {
        return b ? "1" : "0";
    }

    private static boolean bit(String s) {
        if ("1".equals(s)) {
            return true;
        }
        if ("0".equals(s)) {
            return false;
        }
        throw new IllegalArgumentException("not a flag: " + s);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // --- what the run says -----------------------------------------------------------------------------------------

    Stage stage() {
        return stage;
    }

    String sha() {
        return sha;
    }

    String cardName() {
        return cardName;
    }

    String firmware() {
        return firmware;
    }

    boolean wroteIt() {
        return wroteIt;
    }

    long writtenAt() {
        return writtenAt;
    }

    String backupSha() {
        return backupSha;
    }

    Boolean seen() {
        return seen;
    }

    boolean ejected() {
        return ejected;
    }

    String checkText() {
        return checkText;
    }

    /** The SENSOR.TXT identity read just after the write: null when unknown, "" when the card held none. */
    String sensorAtWrite() {
        return sensor;
    }

    /** Nothing of the run is left on the card: its update file was removed and a listing since found none. */
    static boolean finished(FlashFlow f) {
        return f != null && (f.stage == Stage.REMOVED || f.stage == Stage.CHECKED);
    }

    /**
     * A finished run of a file other than wantSha, the one its route writes now (an older firmware version); null
     * wantSha (Restore, whose file is this camera's own): never stale. A run still under way is never stale: its file
     * may be on the card and must be removed first.
     */
    static boolean staleRun(FlashFlow f, String wantSha) {
        return f != null && wantSha != null && !f.sha.isEmpty() && !wantSha.equals(f.sha) && finished(f);
    }

    /** A file was written (or found) this run: the steps before the write are behind the owner and stay done. */
    boolean past() {
        return stage != Stage.NONE;
    }

    /**
     * "Synced" may be said: the app wrote the file (not found it) on the mount seen now, and nothing was removed since.
     */
    boolean syncedClaim() {
        return (stage == Stage.WRITTEN || stage == Stage.ANSWERED) && wroteIt && synced;
    }

    /** The Remove step holds a removal's result: after one this run, or one made here before any write. */
    boolean removalShown() {
        return stage == Stage.REMOVING || stage == Stage.REMOVED || stage == Stage.CHECKED
                || stage == Stage.NONE && removedFirst;
    }

    /** The card may hold an update file: this run put one there and no listing since found none, or one was listed. */
    boolean mayHoldUpdateFile() {
        switch (stage) {
            case WRITTEN:
            case ANSWERED:
            case REMOVING:
                return true;
            default:
                return !listed || listedFile;
        }
    }

    /**
     * Remove may be used now (its step opens though the flash step is not done): once the question is answered; right
     * after the write when it found the file there (it may have been flashed before), when the card holds an update
     * file other than the written one, or when a loaded file this route writes is not the written one; before any
     * write whenever the card holds an update file, and to keep a removal made here in sight.
     */
    boolean removeAvailable(Live live) {
        Live l = live == null ? new Live() : live;
        switch (stage) {
            case NONE:
                return listedFile || removedFirst;
            case WRITTEN:
                return !wroteIt || listedOther || differs || l.loadedOther;
            default:
                return true;
        }
    }

    private boolean isDone(Step s, Live l) {
        switch (s) {
            case INTRO:
                return l.started || past();
            case BACKUP:
                return l.backupOnRecord || past();
            case FILE:
                return l.fileReady || past();
            case DRIVE:
                // Once the file is on the card the drive step stays done: an unplugged drive only changes its line.
                return l.driveReady || past();
            case WRITE:
                // Another loaded file is written next; the file is written again once it is removed after "not seen"
                // (no Upgrade screen, "Program error"), or after a flash check that found another header.
                return past() && !l.loadedOther && !writeAgain();
            case FLASH:
                // Done once the owner saw "Upgrade 100%", eject or not; after "not seen" it stays open with its More
                // info until the update file is removed.
                return stage == Stage.ANSWERED && Boolean.TRUE.equals(seen) || stage.compareTo(Stage.REMOVING) >= 0;
            case REMOVE:
                return stage.compareTo(Stage.REMOVED) >= 0;
            case CHECK:
                return stage == Stage.CHECKED && checkOk;
            default:
                return false;       // READ stays open, with the sensor result in sight
        }
    }

    /**
     * The flash did not take, so the file is written again: the owner did not see "Upgrade 100%" and the file is
     * removed (unless a check passed since), or the flash check found another firmware's header.
     */
    private boolean writeAgain() {
        if (stage == Stage.CHECKED) {
            return !checkOk;
        }
        if (stage == Stage.WRITTEN) {
            return writtenGone();
        }
        return stage == Stage.REMOVED && Boolean.FALSE.equals(seen);
    }

    /** Before the answer, a listing found no update file on the card: the written file is gone, so it is written again. */
    private boolean writtenGone() {
        return stage == Stage.WRITTEN && !listedFile;
    }

    /** The write step's line while the written file is gone from the card before the answer; "" otherwise. */
    String goneLine() {
        return writtenGone() ? "The card no longer lists " + cardName + ". Write it again in step " + number(Step.WRITE)
                + "." : "";
    }

    /** The route's steps as they stand with live. */
    View view(Live live) {
        Live l = live == null ? new Live() : live;
        Step[] order = steps(route);
        int n = order.length;
        boolean[] done = new boolean[n];
        boolean[] locked = new boolean[n];
        String[] note = new String[n];
        for (int i = 0; i < n; i++) {
            done[i] = isDone(order[i], l);
        }
        boolean remove = removeAvailable(l);
        int current = -1;
        for (int i = 0; i < n; i++) {
            if (done[i]) {
                continue;
            }
            boolean ready = order[i] == Step.REMOVE && remove;
            if (!ready) {
                ready = true;
                for (Step need : needs(route, order[i])) {
                    int j = indexOf(order, need);
                    if (j >= 0 && !done[j]) {
                        ready = false;
                        break;
                    }
                }
            }
            if (ready) {
                if (current < 0) {
                    current = i;
                }
                continue;
            }
            locked[i] = true;
            // The first open step before it (one always exists: the first step not done has all its needs done).
            for (int j = 0; j < i && note[i] == null; j++) {
                if (!done[j] && !locked[j]) {
                    note[i] = "Do step " + (j + 1) + " first.";
                }
            }
            for (int j = 0; j < i && note[i] == null; j++) {
                if (!done[j]) {
                    note[i] = "Do step " + (j + 1) + " first.";
                }
            }
        }
        return new View(order, done, locked, note, current);
    }

    /** The drive step's line while the saved drive is unplugged and no other drive is connected. */
    String unpluggedHint(Live live) {
        return unpluggedHint(live, false);
    }

    /**
     * The drive step's line while the saved drive is not mounted: unplugged, or (ejectedIn) ejected with the cable
     * still in. Before the answer, while the flash step is open, it sends the owner to that step; otherwise it says how
     * to connect the camera, in the safe order while an update file may be on the card.
     */
    String unpluggedHint(Live live, boolean ejectedIn) {
        if (stage == Stage.WRITTEN && view(live).open(Step.FLASH)) {
            return ejectedIn ? "Ejected: unplug the cable, then let the camera write the file as step " + number(Step.FLASH)
                    + " says and answer the question."
                    : "Unplugged: let the camera write the file as step " + number(Step.FLASH) + " says, then answer the question.";
        }
        return unpluggedHint(mayHoldUpdateFile(), ejectedIn);
    }

    /** The drive line for a screen without a run: the safe order unless the card is known to hold no update file. */
    static String unpluggedHint(boolean mayHoldUpdateFile) {
        return unpluggedHint(mayHoldUpdateFile, false);
    }

    /** As unpluggedHint(boolean), for a drive ejected with the cable still in (ejectedIn): unplug it first. */
    static String unpluggedHint(boolean mayHoldUpdateFile, boolean ejectedIn) {
        if (ejectedIn) {
            return EJECTED_IN;
        }
        return mayHoldUpdateFile ? SAFE_ORDER : PLAIN_ORDER;
    }

    /** What to do next once the drive went without the app seeing it eject (or was pulled out), by the run's stage. */
    String afterUnseenEject(Live live) {
        View v = view(live);
        switch (stage) {
            case WRITTEN:
                if (v.open(Step.FLASH)) {
                    return FLASH_NOW_PREFIX + number(Step.FLASH) + ". More info there gives the safe order to"
                            + " reconnect it.";
                }
                if (writtenGone() && v.open(Step.WRITE)) {
                    return goneLine();
                }
                return v.open(Step.REMOVE) ? RECONNECT_TO_REMOVE : connectLater();
            case ANSWERED:
                if (!Boolean.TRUE.equals(seen) && v.open(Step.FLASH)) {
                    // The safe order first: the update file is still on the card.
                    return "Open More info in step " + number(Step.FLASH) + " for the safe order to reconnect the"
                            + " camera.";
                }
                return v.open(Step.REMOVE) ? RECONNECT_TO_REMOVE : connectLater();
            case REMOVING:
                if (listedFile) {
                    return v.open(Step.REMOVE) ? RECONNECT_TO_REMOVE : connectLater();    // listed again since
                }
                return v.open(Step.REMOVE) ? "Reconnect the camera, so the app can see that the update file is gone."
                        : connectLater();
            case REMOVED:
                if (route == Route.RESTORE) {
                    // The last step: nothing follows it on this route. Only what is known: the file is gone.
                    return Boolean.TRUE.equals(seen) ? "The update file is removed. Switch the camera on to see whether"
                            + " it starts." : "The update file is removed. " + CONNECT_LATER;
                }
                Step next = route == Route.REPAIR ? Step.CHECK : Step.READ;
                return v.open(next) ? "Go on with step " + number(next) + ", and reconnect the camera when it says so."
                        : connectLater();
            default:
                return connectLater();
        }
    }

    /** "Connect it again when you need it", with the safe order while an update file may be on the card. */
    private String connectLater() {
        return mayHoldUpdateFile() ? "When you connect it again: " + SAFE_ORDER : CONNECT_LATER;
    }

    /**
     * The line under the flash question: the answer, and what follows from it while that step is open (Remove after
     * "seen"; the flash step's own More info, below the line, after "not seen"). Once the update file is removed it
     * says only what was answered.
     */
    String answerLine(Live live) {
        if (seen == null || stage == Stage.NONE) {
            return "";
        }
        String fw = firmware.isEmpty() ? "" : ", " + firmware;
        if (stage == Stage.ANSWERED) {
            View v = view(live);
            if (seen) {
                return "Logged: Upgrade 100% seen" + fw + "." + (v.open(Step.REMOVE) ? " Now remove the update file." : "");
            }
            return "Logged: Upgrade 100% not seen." + (v.open(Step.FLASH) ? " Open More info below for what to do." : "");
        }
        return seen ? "Logged: Upgrade 100% seen" + fw + "." : "Logged: Upgrade 100% not seen.";
    }

    /** The flash step's folded line once it is done. */
    String flashSummary(Live live) {
        return seen == null ? "Upgrade 100% question not answered. The update file is removed." : answerLine(live);
    }

    /** The write step's folded line when its result is no longer on screen (another route, or a restart). */
    String writeLine() {
        if (!past()) {
            return "";
        }
        return (wroteIt ? "Written: " : "Found on the card already: ") + cardName + ".";
    }

    /** The Remove step's folded line when its result is no longer on screen. */
    String removeLine() {
        return "The card lists no update file.";
    }

    // --- the steps -------------------------------------------------------------------------------------------------

    /** The route's steps in the order the screen numbers them (step N is index N - 1). */
    static Step[] steps(Route r) {
        switch (r) {
            case REPAIR:
                return REPAIR_STEPS.clone();
            case PROBE:
                return PROBE_STEPS.clone();
            default:
                return RESTORE_STEPS.clone();
        }
    }

    /** The step's number on the route's screen; 0 when the route has no such step. */
    static int number(Route r, Step s) {
        return indexOf(steps(r), s) + 1;
    }

    int number(Step s) {
        return number(route, s);
    }

    /** The steps s needs done before it opens (a Remove the run makes available opens without them). */
    private static Step[] needs(Route r, Step s) {
        switch (s) {
            case FILE:
                return new Step[]{Step.BACKUP};
            case DRIVE:
                return r == Route.REPAIR ? new Step[]{Step.INTRO} : r == Route.RESTORE ? new Step[]{Step.FILE}
                        : NOTHING;
            case WRITE:
                return r == Route.REPAIR ? new Step[]{Step.INTRO, Step.DRIVE}
                        : r == Route.RESTORE ? new Step[]{Step.FILE, Step.DRIVE} : new Step[]{Step.DRIVE};
            case FLASH:
                return new Step[]{Step.WRITE};
            case REMOVE:
                return new Step[]{Step.FLASH};
            case CHECK:
            case READ:
                return new Step[]{Step.FLASH, Step.REMOVE};
            default:
                return NOTHING;
        }
    }

    private static int indexOf(Step[] order, Step s) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] == s) {
                return i;
            }
        }
        return -1;
    }
}
