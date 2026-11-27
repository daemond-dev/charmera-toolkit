package io.github.daemonddev.charmera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Puts a verified Charmera update file in the root of an SD card the phone can see, through Android's own folder
 * picker (the Storage Access Framework), so no storage permission and no root are needed. The camera then
 * flashes itself from the card. Also removes update files from the card afterwards.
 *
 * Warn, don't block: WriteGate decides, from the check and the backup on record now. A reviewed file, or any other
 * file that passed its checks, may be written with no backup on record, with a warning when nothing confirms what the
 * camera runs (an unknown file gets a loud one: UNKNOWN_FILE). With a backup on record, an unknown file is written only
 * once it was compared with that backup: a file identical to the public dump proves nothing about the camera it goes
 * into; the comparison with the firmware in a dump read before the camera was ever flashed is the one check that does.
 *
 * Every text on screen is for the owner reading it on a phone: what happened first (shown in bold), then what to do
 * next, with buttons named exactly as they are labelled; sums and Android's own error words come last, as "Detail:".
 */
public class MainActivity extends Activity {

    private static final int PICK_FILE = 1;
    private static final int PICK_CARD = 2;
    private static final String PREFS = "card";
    private static final String KEY_TREE = "tree";
    private static final String EXTERNAL_STORAGE = "com.android.externalstorage.documents";
    private static final String TEMP_NAME = "charmera-stage.part";
    private static final int MAX_FILE = 4 * 1024 * 1024;

    // The buttons' labels. Instructions and messages quote them with q(), so they always match the screen.
    private static final String BACKUP = "Choose this camera's backup";

    /** The loud warning for an unknown file written with no backup on record (WriteGate allows it). */
    static final String UNKNOWN_FILE = "It isn't one of this project's known builds, and with no chip backup chosen"
            + " the app can't compare it with this camera's firmware. Only write it if you know it fits.";
    private static final String DOWNLOAD = "Download the stock restore file";
    private static final String MAKE_PROBE = "Make the sensor-ID probe";
    private static final String READ_RESULT = "Read the sensor result from the card";
    private static final String USE_FILE = "Use a file on the phone";
    private static final String SAVE = "Save a copy to the phone";
    private static final String CHOOSE = CardState.CHOOSE_BUTTON;
    private static final String WRITE = "Write the update file to the card";
    private static final String EJECT = "Eject the drive";
    private static final String REMOVE = CardState.REMOVE_BUTTON;
    /** Where new versions of the app are published; the footer links to it. */
    private static final String RELEASES_PAGE = "https://github.com/daemond-dev/charmera-toolkit/releases";
    /** The project's docs folder on GitHub. A link opens a guide there, since the phone has no copy of the repo. */
    private static final String DOCS_PAGE = "https://github.com/daemond-dev/charmera-toolkit/blob/main/docs/";
    private static final String UPDATE_APK_URL = "https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk";
    /** fetch's failure when GitHub answered, but not with the file; the download's message tells it apart. */
    private static final String GITHUB_REFUSED = "GitHub did not send the file";
    /** How a write that worked begins; write() offers the eject screen only after it. */
    private static final String WRITTEN = "The update file is on the card ✓";
    /** How a saved copy that worked begins, under its own name or another. */
    private static final String SAVED = "Saved";
    /** How a write begins when the card already holds the same file; it sends the owner on to the flash, as WRITTEN. */
    private static final String NOTHING_TO_WRITE = "Nothing to write:";
    private static final String WRITING = "Writing the update file to the card…";
    private static final String REMOVING = "Removing from the card…";

    /**
     * One worker for the whole process, never shut down: a write or removal finishes even when the screen that
     * started it is gone (Back, or a dark-mode, font or language change recreates it), and whatever a new screen
     * asks for (looking at the card again) waits until it has.
     */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    /** Downloads run apart from card work, so a slow connection never holds up a card listing or a write. */
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    /** Where the worker hands back how a write or removal ended, whether or not the screen that started it is open. */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * A write or a removal. It runs on the worker and belongs to the process, not to the screen that started it: a
     * screen made while it runs (a dark-mode, font or language change recreates the screen; Back and opening the app
     * again, or "Open with", makes a new one) keeps Eject, Write and the card buttons off, and the screen open when it
     * ends shows how it ended. Read and written on the main thread only.
     */
    private static final class Operation {
        /** A write, or else a removal. */
        final boolean write;
        /** For a removal: how many files it removes, for the eject prompt's title. */
        final int files;
        /** For a write: the backup the file was checked against, its SHA-256 and name. */
        final String backupSha256;
        final String backupName;
        /** How it ended, for the status under its button; null while it runs. */
        String outcome;
        /** A write that put the file on the card, or a removal that removed every file it named. */
        boolean succeeded;
        /** A screen has shown how it ended; a later screen does not show it again. */
        boolean shown;
        /** For a write: the SHA-256 of the file it writes, so the route knows which firmware went on the card. */
        String sha256;
        /** For a write: the name it has on the card, to tell it from another update file there. */
        String cardName;
        /** The route it was started on, and the drive it runs on (its volume UUID, or null). */
        Route route;
        String uuid;

        Operation(boolean write, int files, String backupSha256, String backupName) {
            this.write = write;
            this.files = files;
            this.backupSha256 = backupSha256;
            this.backupName = backupName;
        }
    }

    /** The latest write or removal, running or ended; null before the first one. Main thread only. */
    private static Operation last;
    /** The screen last resumed, the one an operation that ends reports to; null once it is destroyed. Main thread only. */
    private static MainActivity screen;
    private static final int SAVE_COPY = 4;
    private static final int PICK_BACKUP = 5;
    private static final String STATE_BACKUP_URI = "backupUri";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView backupStatus;
    /** The backup's SHA-256, behind its Details toggle. */
    private TextView backupDetail;
    private TextView backupDetailToggle;
    private Button backupButton;
    private TextView fileStatus;
    /** The technical part under fileStatus: the checks, sums and Android's error words. Behind a "Details" toggle. */
    private TextView fileDetail;
    /** The "Details" toggle that shows or hides fileDetail; hidden when there is no detail. */
    private TextView fileDetailToggle;
    private TextView saveStatus;
    private TextView cardStatus;
    /** The rest of the card's readout (name, free space, DCIM), behind its Details toggle. */
    private TextView cardDetail;
    private TextView cardDetailToggle;
    /** A note about the drive pick, shown with the card's readout. */
    private String cardNote;
    private TextView writeStatus;
    private Button writeButton;
    private Button cleanButton;
    private Button downloadButton;
    private Button ejectButton;
    private Button chooseButton;
    private TextView cleanStatus;
    private Button saveButton;
    private Button probeButton;
    private Button repairButton;
    /**
     * The Repair route's pick: a base and the mods ticked on it (the owner's own pick, or the default one at first).
     */
    private Recipe.Selection repairSel = Recipe.defaultPick();
    /** The base radios, the mod boxes and the warning line for an experimental or stock pick. */
    private android.widget.RadioGroup baseChoice;
    private LinearLayout customPanel;
    private LinearLayout modBoxes;
    private TextView customWarning;
    /** The Repair pick's full name ("v012 with Volume and LED rows"), the first line of the write step. */
    private TextView repairName;
    /** Shown in the make button's place while stock v006 is picked: buttons to the screens that write it. */
    private LinearLayout stockV006Panel;
    /** Shown in the Repair write step while the pick is a v012 build and no factory firmware is kept on the phone. */
    private TextView factoryHint;
    private Button factoryFileButton;
    private Button readResultButton;
    private TextView sensorStatus;
    /** A read of SENSOR.TXT is running; it blocks a second read until it ends. */
    private volatile boolean readingSensor;
    /** The footer's update readout, and whether a check for a newer version is running. */
    private TextView updateStatus;
    private volatile boolean checkingUpdate;
    /** The owner's dump of this camera's flash chip, once chosen and accepted; the update file is compared with it. */
    private byte[] backup;
    /**
     * Its SHA-256, by which every check names the backup it was made against (null while none is on record). The
     * worker reads it just before it writes, so it is volatile, as are the two flags WriteGate also reads.
     */
    private volatile String backupSha256;
    private String backupName;
    private Uri backupUri;
    private volatile boolean loadingBackup;
    /** The screen was recreated and is reading the backup it had on record again (read on the UI thread only). */
    private boolean rereadingBackup;
    /** The public stock dump once downloaded or picked and checked; kept so choosing again needs no new download. */
    private byte[] stockDump;
    /** The owner's factory dump once picked or read back from the phone and checked (Candidates.factoryDump). */
    private byte[] factoryDump;
    /** Build v008_test's public file once downloaded and checked (Recipes.DUMPS), for a v008_test pick. */
    private byte[] v008Dump;
    private boolean downloading;

    private byte[] fileData;
    private UpdateCheck.Result fileCheck;
    /** How the file step describes the file above its checks, and whether the owner chose it, for checking again. */
    private String fileName;
    private String fileOrigin;
    private boolean fileChosen;
    /** The file step shows fileCheck's verdict (showChecked), so its next line is worded again for another route. */
    private boolean checkShown;
    /** Bumped by every loadFile, so a slow read of an earlier file cannot replace a later one. */
    private int fileRequest;
    /** The file is being checked again, against the backup on record now; nothing is written meanwhile. */
    private volatile boolean checking;
    /** Bumped by every check of the file again, so only the latest one is shown. */
    private int checkRequest;
    private Uri tree;
    private Card card;
    /** Bumped by every inspectCard, so only the latest look at the card is shown. */
    private int cardRequest;
    private boolean inspecting;
    /**
     * The listing held before the look at the card running now: on the routes that only read the card, the drive step
     * stays done on it while the same drive, still mounted, is listed again (after a zip), so no step flickers locked.
     * Buttons still go by card, which is null until the new listing is back.
     */
    private Card relistedFrom;
    /** A zip's result to scroll into view once the look at the card running now is back (archiveShown). */
    private TextView revealAfterLook;
    /** A comparison of the update file with the copy on the card is running (a write or removal is an Operation). */
    private boolean comparing;
    /** The write or removal whose progress this screen shows, until it has shown how it ended. */
    private Operation watching;
    /**
     * The backup (SHA-256 and name) the file the write step says is on the card was checked against, and the one
     * the copy the file step says was saved was checked against: once another backup is on record, those messages
     * no longer hold. With each, the file's own SHA-256: a file checked alone (Candidates.checkedAlone) was never
     * compared with the backup, so what is said about it then is different.
     */
    private String writtenWith;
    private String writtenName;
    private String writtenSha;
    private String savedWith;
    private String savedName;
    private String savedSha;
    /** The confirmation dialog on screen, or null. */
    private AlertDialog dialog;
    /** The write confirmation while it is on screen, or null: it goes when the backup or the check changes. */
    private AlertDialog writeDialog;

    // Colours for the card layout, chosen in resolveTheme() from the phone's light/dark mode and the theme accent.
    private LinearLayout col;
    private ScrollView scroll;
    private boolean uiReady;
    private int accent, onAccent, windowBg, cardFill, cardStroke, disabledFill, disabledText, ripple, warnAccent, cautionFill, cautionStroke, okColor, badColor, textPrimary, textSecondary;

    /** The card's tree as chosen, and what was found the last time it was looked at. */
    private static final class Card {
        final Uri tree;
        Uri root;
        CardState state = new CardState();

        Card(Uri tree) {
            this.tree = tree;
        }
    }

    /** A failure already worded for the owner: a plain clause in lower case, without a final full stop. */
    private static final class Plain extends IOException {
        /** The technical part, shown under the plain words as "Detail:", or null. */
        final String detail;
        /** The HTTP status code when the failure was an HTTP response, else 0. */
        final int code;

        Plain(String message) {
            this(message, null, 0);
        }

        Plain(String message, String detail) {
            this(message, detail, 0);
        }

        Plain(String message, String detail, int code) {
            super(message);
            this.detail = detail;
            this.code = code;
        }
    }

    // --- screens ------------------------------------------------------------------------------------------------

    /**
     * The screens. One Activity shows one route at a time in its scroll column, so the steps of different jobs are
     * never on one screen; Back and "‹ Home" go Home, and Back on Home leaves the app.
     */
    private enum Route { HOME, BACKUP_PHOTOS, REPAIR, PROBE, RESTORE, TEST_SHOTS, FLASH_LOG, ADVANCED, IDENTIFY }

    private static final String STATE_ROUTE = "route";
    /** The saved drive's name as Android last described it, for the drive bar while it is not connected. */
    private static final String KEY_DRIVE_NAME = "driveName";
    private static final String KEY_FLASH_LOG = "flashLog";
    /** DocumentsContract.ACTION_DOCUMENT_ROOT_SETTINGS (a system API, so spelled out): the drive's own storage page. */
    private static final String DOCUMENT_ROOT_SETTINGS = "android.provider.action.DOCUMENT_ROOT_SETTINGS";
    private static final String SYNCED = "Synced to the camera ✓";
    private static final String EJECTED = "Ejected ✓ Unplug the cable.";
    private static final String NO_FILE = "No update file yet.";

    /*
     * What a flashing route has done is a FlashFlow run, one per drive and route, kept in the preferences on every
     * event (KEY_FLASH_RUN) and read back after Android ends the process. Only explicit events move a run on: a write
     * that worked or the same file found on the card, the answer to the flash question, a removal that worked, a
     * listing of the card, the flash check or sensor read. Whether the drive is plugged in now moves no step. The
     * flags below are about the drive and its eject line, not about a route's progress. Main thread only.
     */
    /** The flash runs as last loaded or changed, by preference key; each change is kept in the preferences at once. */
    private static final Map<String, FlashFlow> RUNS = new HashMap<>();
    private static final String KEY_FLASH_RUN = "flashRun.";
    /** The saved drive was seen ejected since the last write (the Eject steps of Back up photos and Advanced). */
    private static boolean progressEjected;
    /**
     * The saved drive was seen unmounted and has not been mounted since, nor a write, removal or zip started: the only
     * state in which the app says "Ejected". Cleared whenever the drive is seen mounted again.
     */
    private static boolean ejectedThisMount;
    /** "Continue" was tapped under the repair route's "Before you start". */
    private static boolean repairStarted;
    /**
     * The document IDs of the card's DCIM photos just after the last write (listed on the worker), or null, and the
     * run and write they belong to (runId), for the flash check.
     */
    private static volatile Set<String> dcimAtWrite;
    private static volatile String dcimAtWriteFor;
    /** The SENSOR.TXT the worker read just after a write, waiting to go into that write's run (applySnapshot). */
    private static final java.util.concurrent.atomic.AtomicReference<Snapshot> SENSOR_SNAPSHOT =
            new java.util.concurrent.atomic.AtomicReference<>();
    /**
     * The flash check's last result as shown and the run and write it was made for (runId), for a new screen too; and
     * its verdict: TRUE for the public-build family's header, FALSE for another header, null for none (no new photo
     * yet, or the card could not be read).
     */
    private static volatile String photoCheck;
    private static volatile String photoCheckFor;
    private static volatile Boolean photoCheckOk;
    private static volatile boolean checkingPhoto;
    /**
     * The Identify route (routeTitle(Route.IDENTIFY)): a reading of the card's photos is running; its last result as shown
     * (the conclusion with its caveats, and the evidence) and the drive it was read from (treeUuid).
     */
    private static volatile boolean identifying;
    private static volatile String identifyText;
    private static volatile String identifyEvidence;
    private static volatile String identifyFor;
    /**
     * The firmware base the last identification found, or null when it found none or failed. It is an answer about
     * the drive in identifyFor only, so read it through identified(): the Repair route's Identify line and the file
     * step's "not confirmed" warning use it.
     */
    private static volatile Identify.Base identifiedBase;

    /** The base Identify found on the drive connected now, or null: another drive's answer says nothing here. */
    private Identify.Base identified() {
        String drive = identifyFor;
        return drive != null && drive.equals(treeUuid()) ? identifiedBase : null;
    }
    /** A verified backup zip was saved this run, from the drive with this UUID. */
    private static volatile boolean backupSaved;
    private static volatile String backupSavedFrom;
    /**
     * When the test-shot set began (step 1 tapped), 0 before; and the DCIM photos on the card then, or null. Both are
     * kept in the preferences too, so the set's shots are still found after Android ends the app's process.
     */
    private static volatile long shotsSince;
    private static volatile Set<String> shotsBefore;
    /** A light was picked for the set begun at shotsSince. */
    private static boolean lightChosen;
    /** When the app last said "Ejected" (elapsedRealtime), and progressEjected as it was just before it. */
    private static long ejectedAt;
    private static boolean progressEjectedBefore;
    private static final String KEY_SHOTS_SINCE = "shotsSince";
    /** The KEY_SHOTS_SINCE value of the set a light was picked for (lightChosen), kept beside it. */
    private static final String KEY_LIGHT_FOR = "lightChosenFor";
    private static final String KEY_SHOTS_BEFORE = "shotsBefore";
    private static final String KEY_SHOTS_BEFORE_FOR = "shotsBeforeFor";
    private static final String KEY_STANDING_VOLUMES = "standingVolumes";
    /** How long after Android reports the drive unmounted the app looks again before it calls it ejected. */
    private static final int UNMOUNT_SETTLE_MS = 1500;
    /** How many more one-second looks the settle check takes while Android still says "ejecting". */
    private static final int EJECTING_LOOKS = 15;
    /** Android said the saved drive is being ejected (broadcast or state), since it was last mounted. */
    private boolean ejectingSeen;
    /** An eject the app reported this recently is taken back when Android then reports the drive pulled out. */
    private static final long UNDO_EJECT_MS = 2000;
    /** The folder in a backup zip that holds the untouched original of each copy whose dates were fixed. */
    private static final String ORIGINALS = "originals/";

    /**
     * A zip of the camera's photos: a backup, or a set of test shots. Like Operation it belongs to the process, so a
     * screen made while it runs shows its progress and how it ended. It runs on the worker, so no write, removal or
     * look at the card runs alongside it.
     */
    private static final class Archive {
        final boolean shots;
        volatile String progress = "Getting ready…";
        /** How it ended; null while it runs. */
        volatile String outcome;
        /** A screen has taken in how it ended (revealed it, and looked at the card again). Main thread only. */
        boolean shown;
        /**
         * The owner has had how it ended on screen with the drive listed. Until then its step stays open, so a drive
         * pulled mid-zip never hides the result behind the drive step. Main thread only.
         */
        boolean seen;
        /** The card's listing it started from: a screen made while it runs shows that, not a new look behind the zip. */
        final Card card;

        Archive(boolean shots, Card card) {
            this.shots = shots;
            this.card = card;
        }
    }

    /** The last backup zip and the last set of test shots: each keeps its own result, so one never erases the other. */
    private static volatile Archive backupArchive;
    private static volatile Archive shotsArchive;

    private static Archive archiveOf(boolean shots) {
        return shots ? shotsArchive : backupArchive;
    }

    /** The zip running now, or null. Only one runs at a time (startArchive refuses a second). */
    private static Archive runningArchive() {
        Archive b = backupArchive;
        if (b != null && b.outcome == null) {
            return b;
        }
        Archive s = shotsArchive;
        return s != null && s.outcome == null ? s : null;
    }

    private static boolean archiving() {
        return runningArchive() != null;
    }

    /** Its step holds a zip running now, or one whose end the owner has not yet had on screen with the drive listed. */
    private static boolean unseen(Archive a) {
        return a != null && !a.seen;
    }

    /** What the card's SENSOR.TXT was just after a write, read on the worker, for that write's run. */
    private static final class Snapshot {
        final String uuid;
        final FlashFlow.Route route;
        final long writtenAt;
        /** Its identity (sensorIdentity), "" for no such file, null when the card could not be read. */
        final String sensor;

        Snapshot(String uuid, FlashFlow.Route route, long writtenAt, String sensor) {
            this.uuid = uuid;
            this.route = route;
            this.writtenAt = writtenAt;
            this.sensor = sensor;
        }
    }

    private static final int SAVE_ZIP = 6;
    private static final int SAVE_SHOTS = 7;
    private static final String KEY_FIX_DATES = "fixDates";
    private static final String KEY_DATE_NAMES = "dateNames";
    /** The Repair pick (Recipe.Selection.serialize(): base and mods). Apps 0.28 to 0.31 also kept KEY_REPAIR_CUSTOM. */
    private static final String KEY_REPAIR_SET = "repairSet";
    private static final String KEY_REPAIR_CUSTOM = "repairCustom";
    /** The Repair build an app before 0.28 remembered, by Kind name: read once, moved to KEY_REPAIR_SET. */
    private static final String KEY_REPAIR_OLD = "repairVersion";
    private static final String STATE_WIPE = "wipe";
    private static final String STATE_SHOTS_WIPE = "shotsWipe";
    private static final String STATE_LIGHTING = "lighting";
    private static final String STATE_OTHER_LIGHT = "otherLight";
    private static final String STATE_SHOT_COUNT = "shotCount";
    private static final String STATE_PENDING_ZIP = "pendingZip";
    private static final String STATE_PENDING_ZIP_SHOTS = "pendingZipShots";
    private static final String STATE_EJECT_ASKED = "ejectAsked";
    /**
     * The save place of the zip running now, kept in the preferences until it has been read back (before any delete
     * from the camera): still there at start-up, with no zip running, it was left unfinished when Android ended the
     * app's process. KEY_ZIP_RUNNING_SHOTS says which kind it was.
     */
    private static final String KEY_ZIP_RUNNING = "zipRunning";
    private static final String KEY_ZIP_RUNNING_SHOTS = "zipRunningShots";
    private static final String ZIP_BUTTON = "Choose where to save the zip";
    private static final String SHOTS_BUTTON = "Save the shots";
    private static final String CHECK_PHOTO = "Check the newest photo";
    private static final String IDENTIFY_BUTTON = "Identify from the photos on the card";
    /** A Charmera photo is a few hundred KB; a larger file is copied as it is, without its dates fixed. */
    private static final int MAX_PHOTO = 32 * 1024 * 1024;
    /** How much of a video's start is read for its date chunks, which sit in its header before the frames. */
    private static final int AVI_HEAD = 256 * 1024;

    private Button zipButton;
    private TextView zipStatus;
    private android.widget.CheckBox fixDatesBox;
    /** Name the zip's copies by capture time (20261111_114931.jpg) instead of the camera's counter. */
    private android.widget.CheckBox dateNamesBox;
    private android.widget.CheckBox wipeBox;
    private Button shotsButton;
    private TextView shotsStatus;
    private TextView shotsStartStatus;
    private android.widget.CheckBox shotsWipeBox;
    private android.widget.RadioGroup lightingGroup;
    private android.widget.RadioButton[] lightingChoices;
    private android.widget.EditText otherLighting;
    private android.widget.EditText shotCount;
    private TextView shotCountToggle;
    private TextView lightingStatus;
    private Button checkPhotoButton;
    private TextView checkStatus;
    private Button identifyButton;
    private Button identifyNextButton;
    private TextView identifyStatus;
    private TextView identifyDetail;
    private TextView identifyDetailToggle;

    private Route route = Route.HOME;
    /** The work route whose shared result lines are on screen; another one clears them (go()). */
    private Route lastWorkRoute;
    /** "Back up photos first" was tapped on this route: Back up photos offers the way back to it. */
    private Route returnTo;
    private static final String STATE_RETURN = "returnTo";
    /** The route the file in fileData was asked for; another flashing route drops it, so it is never written there. */
    private Route fileFor;
    private final List<Step> steps = new ArrayList<>();
    private FlashLog flashLog;

    private LinearLayout driveBar;
    private TextView driveText;
    private TextView driveChange;
    /** The drive step's one line: what to do about the saved drive now. */
    private TextView driveHint;
    private TextView ejectStatus;
    /** Eject under "Remove the update file", shown once the removal worked. */
    private Button removeEjectButton;
    /** How FlashFlow.goneLine() begins, so updateButtons can take that line back once it no longer holds. */
    private static final String GONE_LINE = "The card no longer lists ";
    /** "Flash again" under a flashing route's header, shown only while its run is finished (updateButtons). */
    private TextView flashAgainLink;
    private Button useFileButton;
    private TextView downloadPageLink;
    /** The eject page was opened for the saved drive; onResume and the volume broadcasts look whether it went. */
    private boolean ejectAsked;
    private boolean volumesWatched;
    /** Bumped by every volume broadcast for the saved drive, so only the latest unmount is looked at again. */
    private int unmountCheck;
    /**
     * An unmount of the saved drive, seen mounted before it, waits for its second look (the unmountCheck it posted is
     * pendingUnmount); a pull, a mount or that look clears it.
     */
    private boolean unmountPending;
    private int pendingUnmount;
    /** The saved drive was seen mounted since it was last seen gone (refreshDrive). */
    private boolean driveSeenMounted;
    /** The saved drive was mounted while a dialog was open: the card is looked at once the dialog goes. */
    private boolean inspectAfterDialog;
    /** A zip's save place chosen while the card was not listed yet (the screen was recreated): it starts once it is. */
    private Uri pendingZip;
    private boolean pendingZipShots;
    /** The route "Use a file on the phone" was tapped on, for the file the picker returns. */
    private Route pendingFileFor;
    /** The answer line under "Did the camera show Upgrade 100%?" on screen now, or null. */
    private TextView flashAnswerView;
    /** The flashing route's "Eject, then flash" and "Remove the update file" steps on screen now, or null. */
    private Step ejectStepNow;
    private Step removeStepNow;

    /** Android's word that a drive was mounted, ejected, unmounted or pulled out; registered while the screen shows. */
    private final BroadcastReceiver volumes = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            android.os.storage.StorageVolume v = null;
            try {
                v = intent.getParcelableExtra(android.os.storage.StorageVolume.EXTRA_STORAGE_VOLUME);
            } catch (RuntimeException ignored) {
                // no volume named: the drive bar is looked at again all the same
            }
            final String uuid = v == null ? null : v.getUuid();
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                volumeEvent(action, uuid);
            });
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.app_name);
        if (getActionBar() != null) {
            getActionBar().hide();        // every screen has its own title; the bar only repeated "Charmera Card"
        }
        resolveTheme();
        // The status bar takes the page colour instead of the theme's blue; dark icons on the light page.
        getWindow().setStatusBarColor(windowBg);
        if (android.os.Build.VERSION.SDK_INT >= 23 && (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                != android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(windowBg);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);
        scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(windowBg);
        scroll.addView(col);
        setContentView(scroll);
        makeViews();
        if (state != null) {
            wipeBox.setChecked(state.getBoolean(STATE_WIPE, false));
            shotsWipeBox.setChecked(state.getBoolean(STATE_SHOTS_WIPE, false));
            int light = state.getInt(STATE_LIGHTING, 0);
            lightingChoices[light >= 0 && light < lightingChoices.length ? light : 0].setChecked(true);
            otherLighting.setText(state.getString(STATE_OTHER_LIGHT, ""));
            shotCount.setText(state.getString(STATE_SHOT_COUNT, ""));
            // The eject page was open when the screen was made again: onResume still looks whether the drive went.
            ejectAsked = state.getBoolean(STATE_EJECT_ASKED, false);
        }
        flashLog = FlashLog.parse(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FLASH_LOG, null));
        if (shotsSince == 0 && state != null) {
            // Android ended the process while the set ran and brought this screen back: its start is in the
            // preferences. A fresh start of the app (no saved state) begins with no set, so photos taken since an old
            // set are never swept into a new save.
            restoreShots();
        }

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null);
        if (saved != null && hasPermission(Uri.parse(saved))) {
            tree = Uri.parse(saved);
            setStatus(cardStatus, "Looking at the card…");
            Archive running = runningArchive();
            if (running != null && running.outcome == null && running.card != null
                    && tree.equals(running.card.tree)) {
                // A zip holds the card: this screen shows the listing the zip started from, so its progress shows, and
                // looks at the card again once the zip ends (archiveShown).
                card = running.card;
            }
        } else {
            setStatus(cardStatus, "No drive chosen yet.");
        }
        String pending = state == null ? null : state.getString(STATE_PENDING_ZIP);
        if (pending != null) {
            // A save place chosen for a zip that waited for the card's listing when the screen was made again.
            pendingZip = Uri.parse(pending);
            pendingZipShots = state.getBoolean(STATE_PENDING_ZIP_SHOTS, false);
        }
        showFile(NO_FILE, null);
        setStatus(backupStatus, "No backup chosen.");
        String savedBackup = state == null ? null : state.getString(STATE_BACKUP_URI);
        if (savedBackup != null) {
            rereadingBackup = true;
            loadBackup(Uri.parse(savedBackup));     // the screen was recreated (dark mode, font size): read it again
        }
        // A write or removal another screen started may still run, or have ended with no screen open to show it.
        if (last != null && !last.shown) {
            watching = last;
        }
        followIdentify();      // an Identify answer from before the screen was made again, for this drive
        route = routeNamed(state == null ? null : state.getString(STATE_ROUTE));
        lastWorkRoute = route;
        String back = state == null ? null : state.getString(STATE_RETURN);
        returnTo = back == null || route != Route.BACKUP_PHOTOS ? null : routeNamed(back);
        freshRunIfStale(flowRoute(route));     // the first render of this route: entering it, as go() does
        render();
        updateButtons();
        unfinishedZip();
        if (pendingZip != null) {
            if (tree == null || busy() || archiving()) {
                startPendingZip();      // it cannot wait for a listing: refused now, its empty file deleted
            } else {
                clearOutcome(pendingZipShots);
                setStatus(pendingZipShots ? shotsStatus : zipStatus, "Looking at the card before saving the zip…");
            }
        }
        uiReady = true;     // from here a new result scrolls itself into view (not the initial empty state)
        // A file the app was opened with is handled once: not again by a recreated screen (state != null), and not
        // when Recents starts the app again with the intent it was first opened with.
        Intent first = getIntent();
        if (state == null && first != null && (first.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            handleIncoming(first);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_ROUTE, route.name());
        if (returnTo != null) {
            out.putString(STATE_RETURN, returnTo.name());
        }
        out.putBoolean(STATE_WIPE, wipeBox.isChecked());
        out.putBoolean(STATE_SHOTS_WIPE, shotsWipeBox.isChecked());
        out.putInt(STATE_LIGHTING, lightingIndex());
        out.putString(STATE_OTHER_LIGHT, otherLighting.getText().toString());
        out.putString(STATE_SHOT_COUNT, shotCount.getText().toString());
        if (backupUri != null && backup != null) {
            out.putString(STATE_BACKUP_URI, backupUri.toString());   // 2 MB do not fit a Bundle; the file is read again
        }
        if (pendingZip != null) {
            // Still waiting for the card's listing: the next screen starts it, or refuses it and deletes its file.
            out.putString(STATE_PENDING_ZIP, pendingZip.toString());
            out.putBoolean(STATE_PENDING_ZIP_SHOTS, pendingZipShots);
        }
        out.putBoolean(STATE_EJECT_ASKED, ejectAsked);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // The broadcasts carry the drive's path as a file: URI; without the scheme none of them arrives.
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addDataScheme("file");
        try {
            registerReceiver(volumes, f);
            volumesWatched = true;
        } catch (RuntimeException e) {
            volumesWatched = false;     // onResume still looks at the drive each time the screen comes back
        }
    }

    @Override
    protected void onStop() {
        if (volumesWatched) {
            volumesWatched = false;
            try {
                unregisterReceiver(volumes);
            } catch (RuntimeException ignored) {
                // not registered after all
            }
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        screen = this;
        applySnapshot();  // a SENSOR.TXT read after a write while no screen was open goes into its run
        catchUp();
        // The card may have been inserted, removed or changed while the app was in the background. Not while a zip
        // runs: it holds the card, and archiveShown() looks at the card again once it ends.
        if (tree != null && !busy() && !inspecting && dialog == null && !archiving()) {
            inspectCard();
        }
        refreshDrive();
        checkEject();     // back from the drive's storage page: was it ejected?
        archiveShown();   // a zip that ended while no screen was open
        photoShown();
    }

    @Override
    protected void onDestroy() {
        dismissDialog();
        if (pendingZip != null && isFinishing()) {
            // Closed for good before the card was listed: no zip starts, so the save screen's empty file goes.
            final Uri dest = pendingZip;
            pendingZip = null;
            WORKER.execute(() -> delete(dest));
        }
        if (screen == this) {
            screen = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (route != Route.HOME) {
            go(returnTo != null ? returnTo : Route.HOME);
        } else {
            super.onBackPressed();
        }
    }

    /** True once this screen is closing; work that finishes later must not touch its views or open dialogs. */
    private boolean gone() {
        return isFinishing() || isDestroyed();
    }

    /** A write or a removal is running, started by this screen or by an earlier one. */
    private static boolean working() {
        return last != null && last.outcome == null;
    }

    /** A write, a removal or a comparison with the card is running. */
    private boolean busy() {
        return comparing || working();
    }

    /** Starts a write or removal as the process's operation; this screen shows its progress. */
    private Operation start(boolean write, int files) {
        Operation op = new Operation(write, files, write ? backupSha256 : null, write ? backupName : null);
        op.route = route;
        op.uuid = treeUuid();
        last = op;
        watching = op;
        return op;
    }

    /**
     * Runs on the worker when a write or removal ends, whatever happened to the screen that started it: the outcome
     * goes to the screen open now, or waits for the next one.
     */
    private static void finish(Operation op, String outcome, boolean succeeded) {
        MAIN.post(() -> {
            op.outcome = outcome;
            op.succeeded = succeeded;
            MainActivity s = screen;
            if (s != null && !s.gone()) {
                s.watching = op;
                s.catchUp();
            }
        });
    }

    /**
     * Shows the write or removal this screen is watching: while it runs, that it runs, with Eject and the card buttons
     * off; once it has ended, how, under its button, then the card is looked at again and the next step shown.
     */
    private void catchUp() {
        Operation op = watching;
        if (op == null) {
            return;
        }
        TextView status = op.write ? writeStatus : cleanStatus;
        if (op.outcome == null) {
            setStatus(status, op.write ? WRITING : REMOVING);
            updateButtons();
            return;
        }
        watching = null;
        boolean first = !op.shown;
        op.shown = true;
        // writeFully syncs the file to the card before a write can succeed, so a write that worked is synced.
        setStatus(status, op.write && op.succeeded && op.outcome.startsWith(WRITTEN)
                ? WRITTEN + " " + SYNCED + op.outcome.substring(WRITTEN.length()) : op.outcome);
        reveal(status);
        if (op.write && op.succeeded) {
            writtenWith = op.backupSha256;
            writtenName = op.backupName;
            writtenSha = op.sha256;
        }
        if (op.succeeded && first) {
            if (op.write) {
                // writeFully synced it: the run's synced claim starts here and nowhere else (a removal claims no sync)
                newRun(op.uuid, op.sha256, op.cardName, true, op.backupSha256);
            } else {
                ejectedThisMount = false;
                noteRemoval(op.uuid, op.route);
                logFlash(FlashLog.Event.FILE_REMOVED, null);
                placeEjectStatus();     // the next eject's result belongs under the Remove step's Eject now
            }
        }
        inspectCard();
        if (op.succeeded && first) {
            done(op.write ? "The update file is on the card" : op.files == 1 ? "Update file removed"
                    : "Update files removed");
        }
        updateButtons();
    }

    /**
     * A write that worked (wrote), or the same file found on the card already: a new run for the flashing route that
     * writes this file, whatever that run had reached, with checkedWith the backup the file's check was made against.
     * The other flashing routes' runs on this drive start over too, and count the card as holding an update file until
     * the next listing (FlashFlow.reset), so their drive line gives the safe order: the card holds this file now. What
     * an earlier flash left (its eject line, its answer, its photo check) no longer applies.
     */
    private void newRun(String uuid, String sha, String cardName, boolean wrote, String checkedWith) {
        FlashFlow.Route r = routeOfFile(sha);
        long now = System.currentTimeMillis();
        for (FlashFlow.Route x : FlashFlow.Route.values()) {
            FlashFlow f = runOf(uuid, x);
            if (x == r && wrote) {
                f.written(sha, cardName, true, now, checkedWith, firmwareLabel(sha));
            } else if (x == r) {
                f.foundOnCard(sha, cardName, now, checkedWith, firmwareLabel(sha));
            } else {
                f.reset();
            }
            keep(uuid, f);
        }
        progressEjected = false;
        ejectedThisMount = false;
        ejectAsked = false;
        photoCheck = null;
        photoCheckFor = null;
        photoCheckOk = null;
        if (!checkingPhoto) {
            checkStatus.setText("");
        }
        ejectStatus.setText("");
        // The eject result belongs under this flash's Eject again, not in a Remove step an earlier flash left it in.
        placeUnder(ejectButton, ejectStatus);
        // Which photos predate the flash, for the flash check, and the SENSOR.TXT there now, for the sensor read.
        snapshotAtWrite(uuid, r, now);
        if (wrote) {
            logFlash(FlashLog.Event.WRITTEN, sha);
        } else {
            // Nothing was written: the log says the file was on the card, and when the app found it there.
            flashLog = appendLog(this, FlashLog.Event.WRITTEN, firmwareLabel(sha), sha,
                    "found on the card already; the app wrote nothing");
        }
    }

    /** After a write or a removal: say it where the owner is looking; the route's next step is Eject. */
    private void done(String message) {
        android.widget.Toast.makeText(this, message + ". Eject the drive before you unplug it.",
                android.widget.Toast.LENGTH_LONG).show();
    }

    /** Android's storage settings, the last place to eject from; false when none of them opened. */
    private boolean openStorageSettings() {
        String[] actions = {android.provider.Settings.ACTION_MEMORY_CARD_SETTINGS,
                android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS, android.provider.Settings.ACTION_SETTINGS};
        for (String action : actions) {
            if (open(new Intent(action))) {
                return true;
            }
        }
        return false;
    }

    /** Starts another app's screen; false when there is none, or it refused. */
    private boolean open(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // --- the camera drive: identity, connected state, eject -------------------------------------------------------

    /** The saved drive's volume UUID: the tree's document ID up to ':' ("1234-ABCD"), or null. */
    private String treeUuid() {
        return uuidOf(tree);
    }

    /** A drive tree's volume UUID, as treeUuid() reads it, or null. */
    private static String uuidOf(Uri t) {
        if (t == null || !EXTERNAL_STORAGE.equals(t.getAuthority())) {
            return null;
        }
        try {
            String id = DocumentsContract.getTreeDocumentId(t);
            int colon = id.indexOf(':');
            return colon <= 0 || id.startsWith("primary:") ? null : id.substring(0, colon);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The phone's volume with this UUID (any case), or null when no such drive is attached. */
    private android.os.storage.StorageVolume volume(String uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            android.os.storage.StorageManager sm =
                    (android.os.storage.StorageManager) getSystemService(STORAGE_SERVICE);
            if (sm != null) {
                for (android.os.storage.StorageVolume v : sm.getStorageVolumes()) {
                    if (uuid.equalsIgnoreCase(v.getUuid())) {
                        return v;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // read as not attached
        }
        return null;
    }

    private static boolean mounted(android.os.storage.StorageVolume v) {
        return v != null && android.os.Environment.MEDIA_MOUNTED.equals(v.getState());
    }

    /**
     * Android reports the drive unmounted: ejected, and still attached. Checking (just plugged in), ejecting (not
     * finished) and unmountable are not an eject.
     */
    private static boolean unmounted(android.os.storage.StorageVolume v) {
        return v != null && android.os.Environment.MEDIA_UNMOUNTED.equals(v.getState());
    }

    private static boolean ejecting(android.os.storage.StorageVolume v) {
        return v != null && android.os.Environment.MEDIA_EJECTING.equals(v.getState());
    }

    /** For a drive that is attached but neither mounted nor unmounted: what Android reports, and what to do. */
    private static String notReady(android.os.storage.StorageVolume v) {
        return ejecting(v) ? "Ejecting…" : "The drive is not ready (Android reports it " + v.getState() + "). Wait a"
                + " few seconds, then tap " + q(EJECT) + " again.";
    }

    /** The drive bar and the drive step's line: the saved drive's name, and whether it is connected now. */
    private void refreshDrive() {
        String uuid = treeUuid();
        android.os.storage.StorageVolume v = volume(uuid);
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (mounted(v)) {
            if (!driveSeenMounted && !ejectAsked && !busy()) {
                ejectStatus.setText("");  // back after being gone: a line about that eject or unplug is stale
            }
            driveSeenMounted = true;
            noteStandingVolumes(uuid);
        } else if (v == null) {
            driveSeenMounted = false;     // gone: an unmount seen next is one on the way in
            noteDriveGone(uuid);          // the mount a write was synced on is over
        }
        String name = null;
        if (v != null) {
            name = v.getDescription(this);
            if (name != null && !name.isEmpty()) {
                prefs.edit().putString(KEY_DRIVE_NAME, name).apply();
            }
        }
        if (name == null || name.isEmpty()) {
            name = prefs.getString(KEY_DRIVE_NAME, null);
        }
        if (name == null || name.isEmpty()) {
            name = uuid != null ? "USB drive " + uuid : "the chosen folder";
        }
        String text;
        if (tree == null) {
            text = "Camera drive: none chosen";
        } else if (uuid == null) {
            text = "Camera drive: " + name + " · not a USB drive or card";
        } else if (mounted(v)) {
            if (ejectedThisMount) {
                ejectStatus.setText("");  // the line about that eject no longer holds either
            }
            ejectedThisMount = false;     // mounted again: an eject seen before no longer holds
            text = "Camera drive: " + name + " · Connected ✓";
        } else {
            text = "Camera drive: " + name + " · Not connected";
        }
        // A saved drive that is only unplugged: plugging it back in re-reads it by itself (volumeEvent), so the hint
        // is all that is needed. No pick button and no stale "cannot find the card" under it; "Change" stays.
        boolean away = driveAway();
        chooseButton.setVisibility(away ? View.GONE : View.VISIBLE);
        cardStatus.setVisibility(away || cardStatus.length() == 0 ? View.GONE : View.VISIBLE);
        if (away) {
            cardDetailToggle.setVisibility(View.GONE);
            cardDetail.setVisibility(View.GONE);
        }
        driveText.setText(text);
        driveChange.setText(tree == null ? "Choose" : "Change");
        showDriveLine();
    }

    /**
     * The drive step's one line: what to do about the saved drive now. Empty while it is connected (the bar says so,
     * and the step shows the card). While it is away: on a flashing route what its run says (flash as the flash step
     * says, or how to connect the camera), and elsewhere how to connect it; in the safe order whenever an update file
     * may be on the card (FlashFlow, by the run and the last listing), never "switch on and connect" with it in.
     */
    private String driveLine() {
        String uuid = treeUuid();
        if (tree == null) {
            return "Tap " + q(CHOOSE) + ", open the camera's drive and tap \"Use this folder\" in its top folder.";
        }
        if (uuid == null) {
            return "Tap " + q(CHOOSE) + " and pick the camera's drive.";
        }
        android.os.storage.StorageVolume v = volume(uuid);
        if (mounted(v)) {
            return "";
        }
        // Ejected (or ejecting) with the cable still in: unplugging comes first, whatever another drive does.
        boolean ejectedIn = unmounted(v) || ejecting(v);
        android.os.storage.StorageVolume other = ejectedIn ? null : otherDrive();
        if (other != null) {
            String otherName = other.getDescription(this);
            return "A different drive is connected" + (otherName == null || otherName.isEmpty() ? ""
                    : " (" + otherName + ")") + ", not the saved one. If it is the camera's drive, tap " + q(CHOOSE)
                    + " (or Change in the drive bar) and pick it.";
        }
        FlashFlow f = flow();
        return f != null ? f.unpluggedHint(live(), ejectedIn)
                : FlashFlow.unpluggedHint(mayHoldUpdateFile(uuid), ejectedIn);
    }

    /** Puts the drive line in the drive step; an event that moves the run on changes it too (updateButtons). */
    private void showDriveLine() {
        String hint = driveLine();
        if (!hint.contentEquals(driveHint.getText())) {
            driveHint.setText(hint);
        }
        driveHint.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // --- the flash runs (FlashFlow): one per drive and flashing route ------------------------------------------------

    /** The preference key of a drive's run for a flashing route. */
    private static String runKey(String uuid, FlashFlow.Route r) {
        return KEY_FLASH_RUN + uuid.toUpperCase(Locale.ROOT) + "." + r.name();
    }

    /** A run and the write it is at, so a result made for one write is never taken for another's. */
    private static String runId(String uuid, FlashFlow.Route r, long writtenAt) {
        return uuid == null ? null : runKey(uuid, r) + "@" + writtenAt;
    }

    /** The id of the write the run is at, or null when it has none (runId). */
    private static String runId(String uuid, FlashFlow f) {
        return f.past() ? runId(uuid, f.route, f.writtenAt()) : null;
    }

    /**
     * The flash check's result for the Repair run on the saved drive: the last check made for that run's write, or
     * the result the run keeps (after a restart); "" when there is none for this write.
     */
    private String checkShown() {
        String uuid = treeUuid();
        FlashFlow f = runOf(uuid, FlashFlow.Route.REPAIR);
        String id = runId(uuid, f);
        String p = photoCheck;
        return p != null && id != null && id.equals(photoCheckFor) ? p : f.checkText();
    }

    /**
     * The drive's run for route r: as last changed, or as kept in the preferences (after Android ended the process).
     * With no drive (uuid null) a run with nothing done, kept nowhere: nothing can be written without a drive.
     */
    private FlashFlow runOf(String uuid, FlashFlow.Route r) {
        if (uuid == null) {
            return new FlashFlow(r);
        }
        String key = runKey(uuid, r);
        FlashFlow f = RUNS.get(key);
        if (f == null) {
            f = FlashFlow.parse(getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, null), r);
            RUNS.put(key, f);
        }
        // Never replaced here, while a screen may be showing it (its removal's result, its Eject): a stale run starts
        // over only when its route is entered (freshRunIfStale) or on "Flash again" (startNewRun).
        return f;
    }

    /**
     * The file a flashing route writes now: Repair the build picked (base and mods), Probe the probe; null for Restore
     * (this camera's).
     */
    private String wantedSha(FlashFlow.Route r) {
        return r == FlashFlow.Route.REPAIR ? repairSel.sha256()
                : r == FlashFlow.Route.PROBE ? Candidates.Kind.PROBE.sha256 : null;
    }

    /**
     * Entering a flashing route: a finished run of a file the route no longer writes (an older firmware version) says
     * nothing about the file offered now, so the route starts a new run. A run still under way is kept (FlashFlow).
     */
    private void freshRunIfStale(FlashFlow.Route r) {
        String uuid = treeUuid();
        FlashFlow run = r == null || uuid == null ? null : runOf(uuid, r);
        String want = wantedSha(r);
        // the same version made from the other copy of the dump (original or cleaned) is the same firmware: not stale
        Recipe.Selection wantKind = r == FlashFlow.Route.REPAIR ? repairSel
                : r == FlashFlow.Route.PROBE ? Recipe.of(Candidates.Kind.PROBE) : null;
        if (run != null && wantKind != null && wantKind.matches(run.sha())) {
            want = run.sha();
        }
        if (r == null || uuid == null || !FlashFlow.staleRun(run, want)) {
            return;
        }
        String key = runKey(uuid, r);
        RUNS.put(key, new FlashFlow(r));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(key).apply();
    }

    /** "Flash again": a finished run is put aside and the route starts over; a run still under way never is. */
    private void startNewRun() {
        FlashFlow.Route r = flowRoute(route);
        String uuid = treeUuid();
        if (r == null || uuid == null || busy() || !FlashFlow.finished(runOf(uuid, r))) {
            return;
        }
        String key = runKey(uuid, r);
        RUNS.put(key, new FlashFlow(r));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(key).apply();
        render();
    }

    /** Keeps a run in the preferences after an event, so it survives the process; only when it changed. */
    private void keep(String uuid, FlashFlow f) {
        if (uuid == null) {
            return;
        }
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String key = runKey(uuid, f.route);
        String text = f.serialize();
        if (!text.equals(prefs.getString(key, null))) {
            prefs.edit().putString(key, text).apply();
        }
    }

    /** The flashing route of a screen, or null for any other. */
    private static FlashFlow.Route flowRoute(Route r) {
        switch (r) {
            case REPAIR:
                return FlashFlow.Route.REPAIR;
            case PROBE:
                return FlashFlow.Route.PROBE;
            case RESTORE:
                return FlashFlow.Route.RESTORE;
            default:
                return null;
        }
    }

    /** The flashing route a file belongs to: every Repair build and the probe their own; any other is Restore's. */
    private static FlashFlow.Route routeOfFile(String sha) {
        return Candidates.isRepairFile(sha) ? FlashFlow.Route.REPAIR
                : Candidates.Kind.PROBE.matches(sha) ? FlashFlow.Route.PROBE : FlashFlow.Route.RESTORE;
    }

    /** The run of the flashing route on screen, for the saved drive; null on any other screen. */
    private FlashFlow flow() {
        FlashFlow.Route r = flowRoute(route);
        return r == null ? null : runOf(treeUuid(), r);
    }

    /**
     * What the screen knows now that is not the run's progress (FlashFlow.Live): the steps before the write, and
     * whether a file this route writes, ready to write, is loaded that is not the one written. The gate (a SHA-256 of
     * the file) is asked only when that answer matters.
     */
    private FlashFlow.Live live() {
        FlashFlow.Live l = new FlashFlow.Live();
        l.started = repairStarted;
        l.backupOnRecord = backupSha256 != null;
        l.driveReady = card != null && card.state.writable() && mounted(volume(treeUuid()));
        FlashFlow f = flow();
        boolean fits = fileCheck != null && fileCheck.ok && fitsRoute(fileCheck);
        boolean other = fits && f != null && f.past() && !fileCheck.sha256.equals(f.sha());
        boolean passes = (route == Route.RESTORE || other) && fits && gateShown(fileCheck, fileData) == null;
        l.fileReady = route == Route.RESTORE && passes;     // Restore's file step; the others make theirs in the write step
        l.loadedOther = other && passes;
        return l;
    }

    /**
     * gate() for the steps' display, kept while nothing it reads changes, since it hashes the whole file and the steps
     * ask often. Write, Save and every write path ask gate() itself (updateButtons, confirmWrite, write, the worker).
     */
    private String gateShown(UpdateCheck.Result check, byte[] data) {
        GateMemo m = gateMemo;
        if (m == null || m.check != check || m.data != data || !Objects.equals(m.backup, backupSha256)
                || m.loading != loadingBackup || m.checking != checking) {
            m = new GateMemo(check, data, backupSha256, loadingBackup, checking, gate(check, data));
            gateMemo = m;
        }
        return m.answer;
    }

    /** gateShown()'s last answer, and what it was worked out from. */
    private static final class GateMemo {
        final UpdateCheck.Result check;
        final byte[] data;
        final String backup;
        final boolean loading;
        final boolean checking;
        final String answer;

        GateMemo(UpdateCheck.Result check, byte[] data, String backup, boolean loading, boolean checking,
                String answer) {
            this.check = check;
            this.data = data;
            this.backup = backup;
            this.loading = loading;
            this.checking = checking;
            this.answer = answer;
        }
    }

    private GateMemo gateMemo;

    /** Whether the saved drive's card may hold an update file, as its runs and the last listing know it. */
    private boolean mayHoldUpdateFile(String uuid) {
        for (FlashFlow.Route r : FlashFlow.Route.values()) {
            if (runOf(uuid, r).mayHoldUpdateFile()) {
                return true;
            }
        }
        return false;
    }

    /** A listing of the saved drive's card: each of its runs takes in which update files it holds. */
    private void noteListing(Card c) {
        if (!readable(c)) {
            return;     // a card that could not be listed says nothing about what it holds
        }
        String uuid = uuidOf(c.tree);
        String[] names = new String[c.state.updateFiles.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = c.state.updateFiles.get(i).name;
        }
        for (FlashFlow.Route r : FlashFlow.Route.values()) {
            FlashFlow f = runOf(uuid, r);
            f.listing(names);
            keep(uuid, f);
        }
    }

    /** A removal worked on the drive, started on route on: every run with a file on that card takes it in. */
    private void noteRemoval(String uuid, Route on) {
        for (FlashFlow.Route r : FlashFlow.Route.values()) {
            FlashFlow f = runOf(uuid, r);
            f.removalWorked(flowRoute(on));
            keep(uuid, f);
        }
    }

    /** The saved drive went: the mount a write was synced on is over (nothing kept: the synced claim never is). */
    private void noteDriveGone(String uuid) {
        for (FlashFlow.Route r : FlashFlow.Route.values()) {
            runOf(uuid, r).driveLeft();
        }
    }

    /** The saved drive was mounted anew: nothing was written on this mount yet. */
    private void noteMount(String uuid) {
        for (FlashFlow.Route r : FlashFlow.Route.values()) {
            runOf(uuid, r).mountChanged();
        }
    }

    /** A SENSOR.TXT the worker read just after a write goes into that write's run (if it is still that write). */
    private void applySnapshot() {
        Snapshot s = SENSOR_SNAPSHOT.getAndSet(null);
        if (s == null) {
            return;
        }
        FlashFlow f = runOf(s.uuid, s.route);
        f.sensorRead(s.writtenAt, s.sensor);
        keep(s.uuid, f);
    }

    /**
     * A drive is saved but not mounted now, and no other drive is: plugging it back in is all that is needed. With
     * another drive mounted (a different card in the camera, say) the owner may need to choose it, so Choose shows.
     */
    private boolean driveAway() {
        String uuid = treeUuid();
        return tree != null && uuid != null && !mounted(volume(uuid)) && otherDrive() == null;
    }

    /**
     * A removable drive mounted now that is not the saved one, or null. A drive that was mounted while the saved one
     * was mounted too (the phone's own SD card, say) is not counted: it stays put, so it is not the camera's.
     */
    private android.os.storage.StorageVolume otherDrive() {
        String mine = treeUuid();
        Set<String> standing = standingVolumes();
        for (android.os.storage.StorageVolume v : otherMounted(mine)) {
            if (!standing.contains(v.getUuid().toUpperCase(Locale.ROOT))) {
                return v;
            }
        }
        return null;
    }

    /** The removable drives mounted now, other than the one with this UUID, each with a UUID; empty when unknown. */
    private List<android.os.storage.StorageVolume> otherMounted(String mine) {
        List<android.os.storage.StorageVolume> out = new ArrayList<>();
        try {
            android.os.storage.StorageManager sm =
                    (android.os.storage.StorageManager) getSystemService(STORAGE_SERVICE);
            if (sm != null) {
                for (android.os.storage.StorageVolume v : sm.getStorageVolumes()) {
                    String id = v.getUuid();
                    if (!v.isPrimary() && v.isRemovable() && mounted(v) && id != null && !id.equalsIgnoreCase(mine)) {
                        out.add(v);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // read as none
        }
        return out;
    }

    /** The UUIDs (upper case) of drives seen mounted alongside the saved drive, kept in the preferences. */
    private Set<String> standingVolumes() {
        Set<String> s = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_STANDING_VOLUMES, null);
        return s == null ? new HashSet<String>() : new HashSet<>(s);
    }

    /** The saved drive is mounted now: every other drive mounted with it is one that stays (see otherDrive). */
    private void noteStandingVolumes(String mine) {
        Set<String> known = standingVolumes();
        boolean added = false;
        for (android.os.storage.StorageVolume v : otherMounted(mine)) {
            added |= known.add(v.getUuid().toUpperCase(Locale.ROOT));
        }
        if (added) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_STANDING_VOLUMES, known).apply();
        }
    }

    /**
     * A volume broadcast: the saved drive mounted, ejected or pulled out. Android reports a drive "unmounted" on its
     * way out when it is pulled out too (then "bad removal"), and on its way in: that alone is never an eject.
     */
    private void volumeEvent(String action, String uuid) {
        final String mine = treeUuid();
        if (mine != null && mine.equalsIgnoreCase(uuid)) {
            boolean zipping = archiving();
            if (Intent.ACTION_MEDIA_UNMOUNTED.equals(action)) {
                final int check = ++unmountCheck;
                // An unmount on the way out of a drive seen mounted; on the way in (just plugged in) it is not one.
                unmountPending = false;
                if (!zipping) {
                    dropCard();
                }
                if (!busy() && !zipping) {
                    // Only a drive seen mounted before this unmount can have been ejected (never one coming in).
                    final boolean seen = driveSeenMounted;
                    unmountPending = driveSeenMounted;
                    pendingUnmount = check;
                    // Ejected only when the drive is still attached, and still unmounted, a moment later.
                    settleUnmount(check, seen, mine, 0);
                }
            } else if (Intent.ACTION_MEDIA_EJECT.equals(action)) {
                ejectingSeen = true;
                if (!ejectedThisMount) {      // late or out of order: never over an eject already confirmed
                    setStatus(ejectStatus, "Ejecting…");
                }
            } else if (Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action)) {
                unmountCheck++;     // the unmount just before was the pull, not an eject
                unmountPending = false;
                ejectAsked = false;
                // "Synced first" only on a flashing route whose run wrote its file on this mount (FlashFlow), with
                // nothing running now; read before the run takes in the pull, which ends that mount.
                FlashFlow f = flow();
                boolean synced = f != null && f.syncedClaim() && !busy() && !zipping;
                if (ejectedThisMount && android.os.SystemClock.elapsedRealtime() - ejectedAt < UNDO_EJECT_MS) {
                    undoEject();
                }
                if (!zipping) {
                    dropCard();
                }
                noteDriveGone(mine);
                // The steps take in the pull first, so what to do next (and where it shows) follows the steps as
                // they are now, never a step this pull folds.
                updateButtons();
                // On a flashing route the eject is advice, not a gate: say what to do next by the run's stage.
                // Off the flashing routes: how to connect it again, in the safe order while the card may hold an update
                // file (the drive's runs and the last listing), as the drive line says it.
                String next = (f != null ? f.afterUnseenEject(live())
                        : FlashFlow.unpluggedHint(mayHoldUpdateFile(mine), false)) + " Eject before you unplug next time.";
                setStatus(ejectStatus, synced
                        ? "The drive was unplugged before it was ejected. What was written was synced first. " + next
                        : "The drive was unplugged before it was ejected. " + next);
                placeEjectStatus();
            } else if (Intent.ACTION_MEDIA_REMOVED.equals(action)) {
                // Android reports "removed", not "bad removal", only for a drive that was no longer mounted. An unmount
                // of a drive seen mounted, still waiting for its second look with no pull or mount since, was an eject:
                // the cable came out within that moment.
                boolean ejectedFirst = unmountPending && pendingUnmount == unmountCheck
                        || ejectingSeen && !ejectedThisMount;     // Android was ejecting it when the cable came out
                unmountPending = false;
                unmountCheck++;     // gone: there is nothing left to look at
                if (!zipping) {
                    dropCard();
                }
                noteDriveGone(mine);
                if (ejectedFirst && !busy() && !archiving() && mine.equalsIgnoreCase(treeUuid())) {
                    markEjected();
                }
                if (ejectedThisMount) {
                    setStatus(ejectStatus, "The drive was ejected, then unplugged.");
                }
                ejectingSeen = false;
            } else if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
                ejectAsked = false;
                ejectingSeen = false;
                ejectedThisMount = false;
                unmountPending = false;
                unmountCheck++;
                noteMount(mine);              // mounted anew: nothing was written on this mount yet
                ejectStatus.setText("");
                // A look at the card still running is superseded by this one (cardRequest).
                if (!busy() && !zipping) {
                    if (dialog == null) {
                        inspectCard();
                    } else {
                        inspectAfterDialog = true;
                    }
                }
            }
        }
        refreshDrive();
        updateButtons();
    }

    /**
     * The saved drive went (unmounted, pulled out or removed): its listing no longer holds, so Write, Remove and the
     * card's other buttons go off until it is looked at again. A zip waiting for the listing is refused now.
     */
    private void dropCard() {
        cardRequest++;
        inspecting = false;
        relistedFrom = null;
        card = null;
        TextView shown = revealAfterLook;     // the look it waited for will not come back: reveal it as things are
        revealAfterLook = null;
        reveal(shown);
        cardStatus.setText("");
        cardDetailToggle.setVisibility(View.GONE);
        cardDetail.setVisibility(View.GONE);
        startPendingZip();
    }

    /** A dialog went: the card is looked at now if the drive was mounted meanwhile. */
    private void afterDialog() {
        if (inspectAfterDialog && dialog == null && tree != null && !busy() && !archiving()) {
            inspectCard();
        }
        updateButtons();
    }

    /**
     * Eject: Android lets only the system unmount a drive. The app asks anyway (that usually does nothing), then opens
     * the drive's own storage page, which has Eject; failing that the Files app, then the storage settings. Whether
     * it was ejected is seen from the drive's state afterwards, never assumed.
     */
    private void eject() {
        if (busy() || archiving()) {
            return;
        }
        String uuid = treeUuid();
        android.os.storage.StorageVolume v = volume(uuid);
        if (uuid == null) {
            setStatus(ejectStatus, "No camera drive is saved, so there is nothing to eject.");
            return;
        }
        if (v == null) {
            setStatus(ejectStatus, ejectedThisMount ? "The drive was ejected, then unplugged."
                    : "The drive is not connected, so there is nothing to eject.");
            return;
        }
        if (unmounted(v)) {
            markEjected();
            return;
        }
        if (!mounted(v)) {
            setStatus(ejectStatus, notReady(v));
            return;
        }
        Uri root = DocumentsContract.buildRootUri(EXTERNAL_STORAGE, uuid);
        try {
            DocumentsContract.ejectRoot(getContentResolver(), root);
        } catch (RuntimeException ignored) {
            // expected: the provider lets only the system eject; the pages below can
        }
        android.os.storage.StorageVolume after = volume(uuid);
        if (unmounted(after)) {
            markEjected();
            return;
        }
        ejectAsked = true;
        if (ejecting(after)) {
            ejectingSeen = true;
            setStatus(ejectStatus, "Ejecting…");      // the broadcast, the settle check or onResume confirms it
            settleUnmount(++unmountCheck, true, uuid, 0);
            return;
        }
        Intent page = new Intent(DOCUMENT_ROOT_SETTINGS);
        page.setDataAndType(root, DocumentsContract.Root.MIME_TYPE_ITEM);
        Intent files = new Intent(Intent.ACTION_VIEW);
        files.setDataAndType(root, DocumentsContract.Root.MIME_TYPE_ITEM);
        if (open(page)) {
            setStatus(ejectStatus, "Tap Eject (or Unmount) on that page, then come back here.");
        } else if (open(files)) {
            setStatus(ejectStatus, "Tap the eject icon next to the drive in the Files app, then come back here.");
        } else if (openStorageSettings()) {
            setStatus(ejectStatus, "Tap the USB drive or card in the storage settings, then Eject, then come back"
                    + " here.");
        } else {
            ejectAsked = false;
            setStatus(ejectStatus, "Android opened no page to eject the drive from. Use Eject in the notification"
                    + " Android shows for the USB drive, then come back here.");
        }
    }

    /** Back from the eject page: say whether the drive went, from its state now. */
    private void checkEject() {
        if (!ejectAsked) {
            return;
        }
        String uuid = treeUuid();
        android.os.storage.StorageVolume v = volume(uuid);
        if (v == null) {
            ejectAsked = false;
            noteDriveGone(uuid);
            updateButtons();      // the steps take in the drive gone first, so the advice follows them as they are now
            // The eject is advice, not a gate: what to do next follows the run's stage (FlashFlow), never a step that
            // is locked; off the flashing routes there is no next step to name, only how to connect it again (in the
            // safe order while the card may hold an update file).
            FlashFlow f = flow();
            setStatus(ejectStatus, ejectedThisMount ? "The drive was ejected, then unplugged."
                    : "The drive is no longer connected, and the app didn't see it eject first. "
                    + (f != null ? f.afterUnseenEject(live()) : FlashFlow.unpluggedHint(mayHoldUpdateFile(uuid), false)));
            updateButtons();      // placed by the steps as they are, and their folded lines take in the status
        } else if (unmounted(v)) {
            markEjected();
        } else if (!mounted(v)) {
            setStatus(ejectStatus, notReady(v));
        } else {
            // Connected now: not ejected yet, or ejected and connected again while the app was away (it cannot tell
            // which), so it claims neither. "Synced" only for a write this route's run made on this mount.
            FlashFlow f = flow();
            setStatus(ejectStatus, f != null && f.syncedClaim()
                    ? "Written and synced. Eject on the drive's page before you unplug. Tap " + q(EJECT) + " to open it"
                    + " again."
                    : "The drive is connected. If you haven't ejected it yet, tap " + q(EJECT) + " and use Eject (or"
                    + " Unmount) on the drive's page.");
        }
    }

    /**
     * The look after an unmount (or an eject Android is still doing): ejected once the drive is still attached and
     * unmounted. While Android still reports it ejecting, it looks again each second for a while, so the result never
     * waits for the screen to be reopened. A newer volume broadcast (unmountCheck) ends it.
     */
    private void settleUnmount(final int check, final boolean seen, final String mine, final int tries) {
        ui.postDelayed(() -> {
            if (gone() || check != unmountCheck) {
                return;
            }
            android.os.storage.StorageVolume v = volume(mine);
            if (ejecting(v) && tries < EJECTING_LOOKS) {
                settleUnmount(check, seen, mine, tries + 1);
                return;
            }
            unmountPending = false;
            if (seen && !busy() && !archiving() && mine.equalsIgnoreCase(treeUuid()) && unmounted(v)) {
                markEjected();
            } else {
                refreshDrive();
                updateButtons();
            }
        }, tries == 0 ? UNMOUNT_SETTLE_MS : 1000);
    }

    private void markEjected() {
        if (!ejectedThisMount) {
            progressEjectedBefore = progressEjected;     // for undoEject(), should Android report a pull right after
        }
        ejectedAt = android.os.SystemClock.elapsedRealtime();
        ejectAsked = false;
        progressEjected = true;
        ejectedThisMount = true;
        noteEject(treeUuid());   // advice only: the runs record it, and no step waits for it
        setStatus(ejectStatus, EJECTED);
        refreshDrive();
        // The steps take in the eject before the status is placed (updateButtons places it last), so it never lands
        // in a step this eject folds.
        updateButtons();
        reveal(ejectStatus);
    }

    /** The saved drive was seen ejected: each of its runs records it (advice only; no step waits for an eject). */
    private void noteEject(String uuid) {
        for (FlashFlow.Route r : FlashFlow.Route.values()) {
            FlashFlow f = runOf(uuid, r);
            f.ejectSeen();
            keep(uuid, f);
        }
    }

    /**
     * Where an eject's or a pull's result goes: under the Remove step's Eject once the run's Remove step holds a
     * removal (FlashFlow.removalShown), or while the Remove step is the open one and the eject step is folded;
     * otherwise under the eject step's Eject. So it never lands in a step that is folded or locked; a Remove step
     * folded once it is done shows it in its folded line. It reads the steps as the last refreshSteps() left them, so
     * updateButtons() places it again last.
     */
    private void placeEjectStatus() {
        Step remove = removeStepNow;
        Step eject = ejectStepNow;
        FlashFlow f = flow();
        boolean removeOpen = remove != null && remove.body.getVisibility() == View.VISIBLE;
        boolean ejectFolded = eject != null && eject.body.getVisibility() != View.VISIBLE;
        if (remove != null && f != null && (f.removalShown() || removeOpen && ejectFolded)) {
            placeUnder(removeEjectButton, ejectStatus);
        } else {
            placeUnder(ejectButton, ejectStatus);
        }
    }

    /** Android reported the drive pulled out just after the app said "Ejected": it was not, so that is taken back. */
    private void undoEject() {
        ejectedThisMount = false;
        progressEjected = progressEjectedBefore;
    }

    // --- the flash log --------------------------------------------------------------------------------------------

    /**
     * How the log names the firmware a file carries, by its SHA-256: the probe and the stock restore file by their
     * routes, any other reviewed build by its name ("v012 with Volume and LED rows"). Entries an older app logged keep
     * the words it used ("v0.27"): the log is never rewritten.
     */
    private static String firmwareLabel(String sha) {
        if (Candidates.Kind.PROBE.matches(sha)) {
            return "probe";
        }
        if (Candidates.Kind.RESTORE.matches(sha)) {
            return "stock restore";
        }
        Recipe.Selection made = Recipe.selectionOf(sha);
        return made != null ? made.label() : "update file";
    }

    private void logFlash(FlashLog.Event event, String sha) {
        flashLog = appendLog(this, event, sha == null ? null : firmwareLabel(sha), sha, null);
    }

    /**
     * Adds an entry to the log kept in the preferences and returns the log. Main thread or worker: the zip and the
     * flash check log from the worker, so a result is kept even when its screen has gone.
     */
    private static synchronized FlashLog appendLog(Context ctx, FlashLog.Event event, String firmware, String sha,
            String note) {
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        FlashLog log = FlashLog.parse(prefs.getString(KEY_FLASH_LOG, null));    // another screen may have added to it
        log.add(System.currentTimeMillis(), event, firmware, sha, note);
        prefs.edit().putString(KEY_FLASH_LOG, log.serialize()).apply();
        return log;
    }

    private static synchronized FlashLog clearLog(Context ctx) {
        FlashLog log = new FlashLog();
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FLASH_LOG, log.serialize()).apply();
        return log;
    }

    private FlashLog readLog() {
        return FlashLog.parse(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FLASH_LOG, null));
    }

    // --- routes -----------------------------------------------------------------------------------------------------

    private static Route routeNamed(String name) {
        for (Route r : Route.values()) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return Route.HOME;
    }

    /**
     * Opens a route. A flashing route drops a file another route asked for, so it is never written from here, and the
     * shared result lines another route left (its write, eject, removal, sensor result) are cleared: view text only.
     */
    private void go(Route to) {
        if (to != Route.BACKUP_PHOTOS) {
            returnTo = null;
        }
        boolean work = to != Route.HOME && to != Route.FLASH_LOG;
        freshRunIfStale(flowRoute(to));     // entering a flashing route: a finished run of an older file starts over
        if (work && to != lastWorkRoute && !busy() && !archiving()) {
            FlashFlow.Route flashing = flowRoute(to);
            if (flashing != null && !runOf(treeUuid(), flashing).past()) {
                writeStatus.setText("");
                ejectStatus.setText("");
                cleanStatus.setText("");
            }
            if (to != Route.PROBE && to != Route.ADVANCED) {
                sensorStatus.setText("");
            }
        }
        if (work) {
            lastWorkRoute = to;
        }
        route = to;
        if ((to == Route.REPAIR || to == Route.PROBE || to == Route.RESTORE) && fileFor != null && fileFor != to
                && !busy()) {
            dropFile();
        }
        if (checkShown && fileCheck != null) {
            fileStatus.setText(checkLine(fileCheck, fileChosen));     // its next line is for the route now on screen
        }
        render();
        scroll.scrollTo(0, 0);
    }

    /** Forgets the file in fileData (and any read or check of it still running), as a new file would. */
    private void dropFile() {
        dropFile(true);
    }

    /**
     * dropFile, with or without scrolling to the file step's line. A tick of a mod box drops the file too, and the
     * screen must stay where the boxes are.
     */
    private void dropFile(boolean reveal) {
        dismissWriteDialog();
        fileRequest++;
        fileData = null;
        fileCheck = null;
        fileName = null;
        fileFor = null;
        checking = false;
        if (reveal) {
            showFile(NO_FILE, null);
        } else {
            checkShown = false;
            setStatus(fileStatus, NO_FILE);
            showDetail(null);
        }
    }

    /** Whether the route on screen writes this file: each flashing route only its own kind. */
    private boolean fitsRoute(UpdateCheck.Result check) {
        switch (route) {
            case REPAIR:
                return isRepair(check);
            case PROBE:
                return isProbe(check);
            case RESTORE:
                return check != null && !isProbe(check) && !isRepair(check);
            case ADVANCED:
                return true;
            default:
                return false;
        }
    }

    /**
     * Builds the patch for the probe or repair route, as the old "Make" buttons did: from the dump of the pick's base
     * (v012 the factory dump, v006 the public stock dump, v008_test its public file).
     */
    private void makePatch(Recipe.Selection sel) {
        if (busy() || dialog != null || downloading) {
            return;
        }
        if (Recipe.V012.equals(sel.base)) {
            makeFactory(sel, route);
        } else if (Recipe.V008.equals(sel.base)) {
            downloadV008(sel, route);
        } else {
            downloadDump(sel, route);
        }
    }

    /** The Repair make button's label, the same for every pick: the write step shows the pick's name above it. */
    private static final String MAKE_FIRMWARE = "Make this firmware";

    /**
     * A firmware name at the start of a sentence: "Stock v012 (...)" for a stock pick. A name that starts with its
     * base ("v012 with EXIF dates") stays as it is, since v012 is the build's own name.
     */
    private static String cap(String name) {
        return name.startsWith("stock ") ? "S" + name.substring(1) : name;
    }

    /** The dump a base's files are made from, in the write step's words. */
    private static String dumpWords(String base) {
        return Recipe.V012.equals(base) ? "a public copy of the factory firmware" : Recipe.V008.equals(base)
                ? "build v008_test's public file" : "the public v006 chip backup";
    }

    /** Whether the owner's factory dump is in memory or kept on the phone (checked again when it is used). */
    private boolean haveFactoryDump() {
        return factoryDump != null || new File(getFilesDir(), KEPT_FACTORY).isFile();
    }

    /** The default pick on base v012: stock v012, with no mods ticked (Recipe.defaultPick()). */
    private static Recipe.Selection factoryDefault() {
        return Recipe.defaultPick();
    }

    /**
     * Before the owner has picked (nothing in KEY_REPAIR_SET: every pick the owner makes is remembered at once), the
     * Repair pick follows Identify's answer for the drive connected now (Recipe.defaultPick(Identify.Base)): stock v006
     * for the v006 family, else stock v012, no mod ticked. It isn't remembered, so it stays a default. Called once when
     * an answer arrives, and when the screen is made again. Returns whether the pick moved.
     */
    private boolean followIdentify() {
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_REPAIR_SET, null) != null) {
            return false;
        }
        // Nor while a make, a check or a dialog is live, or over a Repair file loaded of the pick on screen.
        if (busy() || downloading || checking || dialog != null
                || (fileFor == Route.REPAIR && fileCheck != null && repairSel.matches(fileCheck.sha256))) {
            return false;
        }
        Recipe.Selection d = Recipe.defaultPick(identified());
        if (d.equals(repairSel)) {
            return false;
        }
        repairSel = d;
        freshRunIfStale(FlashFlow.Route.REPAIR);
        return true;
    }

    /** Keeps the Repair pick (base and mods) in the preferences. */
    private void rememberRepair() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_REPAIR_SET, repairSel.serialize()).apply();
    }

    /**
     * Whether a factory dump picked for this route is made into firmware: only where a v012 build is written,
     * the Repair route and Advanced (which writes any checked file). On every other route it is only kept.
     */
    private static boolean buildsFactory(Route forRoute) {
        return forRoute == Route.REPAIR || forRoute == Route.ADVANCED;
    }

    /** The file step's line for a factory dump picked on a route that does not use it: kept, and where it is used. */
    private static String factoryKeptOnly(String fromFactory) {
        return fromFactory + " This screen doesn't use it. " + q(routeTitle(Route.REPAIR)) + " makes firmware"
                + " from it when base v012 is picked.";
    }

    /** What the write step says when the pick needs a dump the phone does not have: v012's, or v008_test's. */
    private static String needsFactory(Recipe.Selection sel) {
        if (Recipe.V008.equals(sel.base)) {
            return cap(sel.label()) + " is made from build v008_test's public file. " + q(MAKE_FIRMWARE)
                    + " downloads it from GitHub.";
        }
        return q(MAKE_FIRMWARE) + " downloads a public copy of the factory firmware of OV9732 cameras (2 MB, "
                + Candidates.CLEAN_FACTORY_NAME + ") from GitHub. Have your own chip backup of an OV9732 camera ("
                + Candidates.FACTORY_DUMP_NAME + ")? Tap " + q(USE_FILE) + " to build from that.";
    }

    /**
     * The Repair route's base choice: one radio button per base, stacked, v012 (OV9732), v006 and v008_test (SP140A).
     * Each radio's tag is its base. Checking the radio of the base already picked changes nothing, so
     * refreshRepairChoice can check it without a loop.
     */
    private android.widget.RadioGroup baseRadios() {
        android.widget.RadioGroup g = new android.widget.RadioGroup(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setContentDescription("Base firmware");
        for (String base : Recipe.BASES) {
            android.widget.RadioButton r = new android.widget.RadioButton(this);
            r.setId(View.generateViewId());
            r.setTag(base);
            r.setText(baseText(base));
            r.setTextColor(textPrimary);
            r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            r.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
            r.setMinHeight(dp(48));      // a 48dp touch target
            r.setPadding(r.getPaddingLeft(), dp(6), 0, dp(6));
            g.addView(r, new android.widget.RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        checkBase(g, repairSel.base);
        g.setOnCheckedChangeListener((group, id) -> {
            View r = group.findViewById(id);
            if (r == null || !(r.getTag() instanceof String)) {
                return;
            }
            String base = (String) r.getTag();
            if (!base.equals(repairSel.base)) {
                chooseRepair(new Recipe.Selection(base));     // another base: none of its mods ticked yet
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        g.setLayoutParams(lp);
        return g;
    }

    /** Checks the radio of this base, when it isn't checked already. */
    private static void checkBase(android.widget.RadioGroup g, String base) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View r = g.getChildAt(i);
            if (base.equals(r.getTag()) && g.getCheckedRadioButtonId() != r.getId()) {
                g.check(r.getId());
            }
        }
    }

    private static String baseText(String base) {
        return Recipe.V012.equals(base) ? "v012 (factory build, OV9732 sensor)"
                : Recipe.V006.equals(base) ? "v006 (public build, SP140A sensor)"
                : "v008_test (later build, SP140A sensor)";
    }

    /**
     * The owner picked a base with the Repair route's base radios, or ticked or unticked a mod: it is remembered,
     * a loaded file of another pick goes (with a build of one still running), and a finished run of another pick
     * starts over. A pick while anything runs (the radios and boxes are off then) is put back.
     */
    private void chooseRepair(Recipe.Selection sel) {
        if (sel.equals(repairSel)) {
            return;
        }
        if (busy() || downloading || checking || dialog != null) {
            refreshRepairChoice();          // the radios and boxes show the pick there is
            return;
        }
        repairSel = sel;
        rememberRepair();
        if (route == Route.REPAIR && (fileCheck == null || !sel.matches(fileCheck.sha256))) {
            dropFile(false);        // no scroll: the owner is still ticking boxes
        }
        freshRunIfStale(FlashFlow.Route.REPAIR);
        render();
        updateButtons();
    }

    /** The base radios, the mod boxes, the warning line and the factory-firmware hint, for the pick now. */
    private void refreshRepairChoice() {
        repairName.setText(cap(repairSel.label()));
        // Stock v006 is the stock restore file, which this screen doesn't write: its two screens are offered instead.
        boolean elsewhere = writtenOnRestore(repairSel);
        repairButton.setVisibility(elsewhere ? View.GONE : View.VISIBLE);
        stockV006Panel.setVisibility(elsewhere ? View.VISIBLE : View.GONE);
        checkBase(baseChoice, repairSel.base);
        modBoxes.removeAllViews();
        boolean idle = !busy() && !downloading && !checking;
        boolean experimental = false;
        for (final String mod : Recipe.mods(repairSel.base)) {
            String clash = Recipe.conflict(repairSel, mod);
            boolean tried = !Recipe.experimental(mod);
            experimental |= !tried && repairSel.has(mod);
            android.widget.CheckBox box = toggle(Recipe.title(mod) + (tried ? "" : " (experimental, not yet tried on a"
                    + " camera)"), repairSel.has(mod));
            box.setTag(clash);
            box.setEnabled(idle && clash == null);
            box.setOnCheckedChangeListener((v, on) -> chooseRepair(repairSel.with(mod, on)));
            modBoxes.addView(box);
            TextView about = body(clash == null ? Recipe.description(mod)
                    : "Can't go with " + Recipe.title(clash) + ". Untick it first. " + Recipe.description(mod));
            about.setTextColor(textSecondary);
            about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            about.setPadding(dp(32), 0, 0, dp(6));
            modBoxes.addView(about);
        }
        if (Recipe.mods(repairSel.base).length == 0) {
            TextView none = body("Build " + repairSel.base + " has no mods yet.");
            none.setTextColor(textSecondary);
            modBoxes.addView(none);
        }
        // The set's own status counts too: a combination marked experimental in the recipes warns even when each of
        // its mods alone is tested (the boxes keep their own labels).
        boolean untried = !experimental && Recipe.untriedCombination(repairSel);
        String problem = Recipe.problem(repairSel);
        String warn = problem != null ? "The app won't make this, because " + problem + "."
                : repairSel.has(Recipe.PROBE_MOD) ? "The sensor-ID probe is a diagnostic. The app makes it here, and "
                + q(routeTitle(Route.PROBE)) + " writes it."
                : backupLine(repairSel) + (experimental ? " At least one ticked mod is experimental. It hasn't been"
                + " tried on a camera yet." + reviewedAndWayBack(repairSel)
                : untried ? " " + Recipe.untriedNote(repairSel) + reviewedAndWayBack(repairSel) : "");
        Identify.Base found = identified();
        String foundBase = found == Identify.Base.V012 ? Recipe.V012 : found == Identify.Base.V006_FAMILY ? Recipe.V006
                : null;
        // A photo can't tell v006 from v008_test, so that pair is no mismatch. Any other difference is said here.
        if (foundBase != null && !foundBase.equals(repairSel.base)
                && !(Recipe.V006.equals(foundBase) && Recipe.V008.equals(repairSel.base))) {
            warn = "Identify found base " + foundBase + " for this camera, and " + repairSel.base + " is picked. "
                    + warn;
        }
        warn = warn.trim();
        customWarning.setText(warn);
        customWarning.setVisibility(!warn.isEmpty() ? View.VISIBLE : View.GONE);
        boolean ask = Recipe.V012.equals(repairSel.base) && !haveFactoryDump()
                && (fileCheck == null || !repairSel.matches(fileCheck.sha256));
        factoryHint.setText(ask ? needsFactory(repairSel) : "");
        factoryHint.setVisibility(ask ? View.VISIBLE : View.GONE);
        factoryFileButton.setVisibility(ask ? View.VISIBLE : View.GONE);
    }

    /** Whether the pick is stock v006, the stock restore file: Restore and Advanced write it, this screen doesn't. */
    private static boolean writtenOnRestore(Recipe.Selection sel) {
        return sel.stock() && Recipe.V006.equals(sel.base);
    }

    /**
     * The end of the experimental line: the file is still checked, and how to go back from this pick. A flash from the
     * card only helps while the camera still starts, so it says that.
     */
    private static String reviewedAndWayBack(Recipe.Selection sel) {
        return " The file is still the known one, checked byte for byte. If the camera boots but misbehaves, you can"
                + " go back from the card. " + wayBack(sel);
    }

    /**
     * The line for a pick that is stock or SP140A firmware: stock v012, stock v006 (the restore file, written on its
     * own screens) or SP140A firmware. "" for a build with mods that drives the OV9732. No pick needs a chip backup;
     * the file step warns when nothing confirms what the camera runs.
     */
    private static String backupLine(Recipe.Selection sel) {
        if (sel.stock() && Recipe.V012.equals(sel.base)) {
            return "No mods ticked. This is build v012, the factory firmware of OV9732 cameras, unchanged.";
        }
        if (writtenOnRestore(sel)) {
            return "No mods ticked. This is the stock restore file (build v006, unchanged). This screen doesn't write it."
                    + " " + q(routeTitle(Route.RESTORE)) + " writes it and compares it with your chip backup. Without a"
                    + " chip backup, use Advanced (" + q(DOWNLOAD) + ").";
        }
        if (Recipe.drivesOv9732(sel)) {
            return "";
        }
        return sel.stock() ? "No mods ticked. This is build " + sel.base + "'s stock SP140A firmware, unchanged."
                : "This is SP140A firmware. Without the OV9732 port it doesn't drive the OV9732.";
    }

    /** The views that move between routes are made once; render() puts them into the route's steps. */
    private void makeViews() {
        flashAgainLink = link("Start a new run", v -> startNewRun());
        flashAgainLink.setPadding(0, dp(6), 0, dp(6));
        flashAgainLink.setVisibility(View.GONE);
        backupButton = primary(BACKUP, v -> pickBackup());
        backupStatus = body("");
        backupDetail = mono();
        backupDetail.setVisibility(View.GONE);
        backupDetailToggle = detailToggle(backupDetail, "Details");
        backupDetailToggle.setVisibility(View.GONE);
        downloadButton = primary(DOWNLOAD, v -> downloadDump(Recipe.of(Candidates.Kind.RESTORE), route));
        // The file the picker returns is for this route; a picker cancelled changes nothing about the file loaded now.
        useFileButton = button(USE_FILE, v -> {
            pendingFileFor = route;
            pickFile();
        });
        downloadPageLink = linkText("Open the v006 chip backup's download page ↗", Candidates.STOCK_DUMP_PAGE);
        saveButton = button(SAVE, v -> saveCopy());
        saveStatus = body("");
        fileStatus = body("");
        fileDetail = mono();
        fileDetail.setVisibility(View.GONE);
        fileDetailToggle = detailToggle(fileDetail, "Details");
        probeButton = primary(MAKE_PROBE, v -> makePatch(Recipe.of(Candidates.Kind.PROBE)));
        // The pick remembered as base and mods; an app before 0.28 remembered a version by its Kind name (V027, V026,
        // V025, BRINGUP), which is moved to its set here, once.
        android.content.SharedPreferences picked = getSharedPreferences(PREFS, MODE_PRIVATE);
        repairSel = Recipe.remembered(picked.getString(KEY_REPAIR_SET, null), picked.getString(KEY_REPAIR_OLD, null));
        if (picked.contains(KEY_REPAIR_OLD) || picked.contains(KEY_REPAIR_CUSTOM)) {
            picked.edit().remove(KEY_REPAIR_OLD).remove(KEY_REPAIR_CUSTOM)
                    .putString(KEY_REPAIR_SET, repairSel.serialize()).apply();
        }
        repairButton = primary(MAKE_FIRMWARE, v -> makePatch(repairSel));
        // In the make button's place while stock v006 is picked: the two screens that write the stock restore file.
        stockV006Panel = new LinearLayout(this);
        stockV006Panel.setOrientation(LinearLayout.VERTICAL);
        stockV006Panel.addView(button("Open " + routeTitle(Route.RESTORE), v -> go(Route.RESTORE)));
        stockV006Panel.addView(button("Open Advanced", v -> go(Route.ADVANCED)));
        stockV006Panel.setVisibility(View.GONE);
        repairName = body("");          // the pick's full name, under the write step's short title
        repairName.setTypeface(Typeface.DEFAULT_BOLD);
        customPanel = new LinearLayout(this);
        customPanel.setOrientation(LinearLayout.VERTICAL);
        baseChoice = baseRadios();
        customPanel.addView(baseChoice);
        modBoxes = new LinearLayout(this);
        modBoxes.setOrientation(LinearLayout.VERTICAL);
        modBoxes.setPadding(0, dp(6), 0, 0);
        customPanel.addView(modBoxes);
        customWarning = body("");
        customWarning.setTextColor(warnAccent);
        customWarning.setTypeface(Typeface.DEFAULT_BOLD);
        customPanel.addView(customWarning);
        factoryHint = body("");
        factoryHint.setTextColor(textSecondary);
        factoryFileButton = button(USE_FILE, v -> {
            pendingFileFor = Route.REPAIR;
            pickFile();
        });
        driveHint = body("");
        chooseButton = primary(CHOOSE, v -> pickCard());
        cardStatus = body("");
        cardDetail = body("");
        cardDetail.setTextColor(textSecondary);
        cardDetail.setVisibility(View.GONE);
        cardDetailToggle = detailToggle(cardDetail, "Details");
        cardDetailToggle.setVisibility(View.GONE);
        writeButton = primary(WRITE, v -> confirmWrite());
        writeStatus = body("");
        // Each Eject shows its result right under itself, whichever step it is in.
        ejectButton = primary(EJECT, v -> {
            placeUnder(v, ejectStatus);
            eject();
        });
        ejectStatus = body("");
        cleanButton = primary(REMOVE, v -> confirmClean());
        cleanStatus = body("");
        removeEjectButton = button(EJECT, v -> {
            placeUnder(v, ejectStatus);
            eject();
        });
        readResultButton = primary(READ_RESULT, v -> readSensorResult());
        sensorStatus = body("");
        updateStatus = body("");
        final android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        fixDatesBox = toggle("Fix photo and video dates in the copies", prefs.getBoolean(KEY_FIX_DATES, true));
        fixDatesBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(KEY_FIX_DATES, on).apply());
        dateNamesBox = toggle("Name the copies by date and time (20261111_114931.jpg)",
                prefs.getBoolean(KEY_DATE_NAMES, true));
        dateNamesBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(KEY_DATE_NAMES, on).apply());
        wipeBox = toggle("Delete them from the camera after a verified backup", false);
        zipButton = primary(ZIP_BUTTON, v -> confirmArchive(false));
        zipStatus = body("");
        shotsStartStatus = body("");
        android.widget.RadioGroup lights = new android.widget.RadioGroup(this);
        lightingChoices = new android.widget.RadioButton[3];
        String[] lightNames = {"Daylight", "Indoor", "Other"};
        for (int i = 0; i < lightNames.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(lightNames[i]);
            rb.setTextColor(textPrimary);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            rb.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
            // A tap, even on the light already picked, is the owner's choice; "Other" counts once its name is typed.
            if (i < 2) {
                rb.setOnClickListener(v -> chooseLight());
            }
            lights.addView(rb);
            lightingChoices[i] = rb;
        }
        lightingGroup = lights;
        otherLighting = field("Other light, e.g. sunset or LED lamp", android.text.InputType.TYPE_CLASS_TEXT);
        lightingStatus = body("");
        lightingStatus.setTextColor(textSecondary);
        lightingStatus.setTextIsSelectable(false);
        // The text field only while "Other" is picked; the line under it names the choice for the folded step.
        lights.setOnCheckedChangeListener((g, id) -> showLighting());
        otherLighting.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                showLighting();
            }
        });
        // Typing does not fold the step away mid-word: the light counts as chosen once the typing is done.
        otherLighting.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        otherLighting.setOnEditorActionListener((tv, actionId, event) -> {
            if (!otherLighting.getText().toString().trim().isEmpty()) {
                chooseLight();
            }
            return false;
        });
        otherLighting.setOnFocusChangeListener((fv, has) -> {
            if (!has && !otherLighting.getText().toString().trim().isEmpty()) {
                chooseLight();
            }
        });
        lightingChoices[0].setChecked(true);
        showLighting();
        shotCount = field("Save the newest N photos instead", android.text.InputType.TYPE_CLASS_NUMBER);
        shotCount.setVisibility(View.GONE);
        shotCountToggle = detailToggle(shotCount, "Choose how many");
        shotsWipeBox = toggle("Delete them from the camera after a verified save", false);
        shotsButton = primary(SHOTS_BUTTON, v -> confirmArchive(true));
        shotsStatus = body("");
        checkPhotoButton = primary(CHECK_PHOTO, v -> checkPhoto());
        checkStatus = body("");
        identifyButton = primary(IDENTIFY_BUTTON, v -> identifyFirmware());
        identifyStatus = body("");
        identifyDetail = mono();
        identifyDetail.setVisibility(View.GONE);
        identifyDetailToggle = detailToggle(identifyDetail, "Evidence");
        identifyDetailToggle.setVisibility(View.GONE);
        // Shown once Identify found a base on this drive: the answer names a base, and this is where it's picked.
        identifyNextButton = button("Open " + routeTitle(Route.REPAIR), v -> go(Route.REPAIR));
        identifyNextButton.setVisibility(View.GONE);

        driveBar = new LinearLayout(this);
        driveBar.setOrientation(LinearLayout.HORIZONTAL);
        driveBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(cardFill);
        bg.setCornerRadius(dp(10));
        bg.setStroke(Math.max(1, dp(1)), cardStroke);
        driveBar.setBackground(bg);
        driveBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6);
        blp.bottomMargin = dp(12);
        driveBar.setLayoutParams(blp);
        driveText = body("Camera drive: none chosen");
        driveText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        driveText.setTextIsSelectable(false);
        driveBar.addView(driveText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        driveChange = link("Change", v -> pickCard());
        driveChange.setPadding(dp(12), dp(8), 0, dp(8));
        driveBar.addView(driveChange);
    }

    /** Puts a view made once into this route's layout, taking it out of the one it was in. */
    private static void attach(ViewGroup parent, View v) {
        if (v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
        parent.addView(v);
    }

    /** Moves a status view right under the button in the same layout, so the button's result shows beside it. */
    private static void placeUnder(View button, View status) {
        if (!(button.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) button.getParent();
        if (status.getParent() == parent && parent.indexOfChild(status) == parent.indexOfChild(button) + 1) {
            return;
        }
        if (status.getParent() instanceof ViewGroup) {
            ((ViewGroup) status.getParent()).removeView(status);
        }
        parent.addView(status, parent.indexOfChild(button) + 1);
    }

    /** Lays out the route on screen: its title, the drive bar and its steps, with the shared views in them. */
    private void render() {
        otherLighting.clearFocus();       // a focus loss inside removeAllViews would re-enter updateButtons()
        col.removeAllViews();
        steps.clear();
        flashAnswerView = null;   // a flashing route's eject step makes it again
        ejectStepNow = null;      // and its eject and remove steps
        removeStepNow = null;
        flashLog = readLog();     // the worker may have added to it
        showArchive();
        if (!checkingPhoto) {
            setStatus(checkStatus, checkShown());     // this write's check, or "" when the last one was another's
        }
        setStatus(shotsStartStatus, shotsSince == 0 ? "" : "Set started " + FlashLog.when(shotsSince,
                TimeZone.getDefault()) + ". Photos taken from then on are saved in the last step.");
        switch (route) {
            case BACKUP_PHOTOS:
                renderBackupPhotos();
                break;
            case REPAIR:
                renderRepair();
                break;
            case PROBE:
                renderProbe();
                break;
            case RESTORE:
                renderRestore();
                break;
            case TEST_SHOTS:
                renderTestShots();
                break;
            case FLASH_LOG:
                renderFlashLog();
                break;
            case ADVANCED:
                renderAdvanced();
                break;
            case IDENTIFY:
                renderIdentify();
                break;
            default:
                renderHome();
                break;
        }
        refreshDrive();
        showCard();
        updateButtons();
    }

    /** The top of every screen: "‹ Home" (not on Home), the title, one short line, and the drive bar. */
    private void header(String title, String line) {
        if (route != Route.HOME) {
            final Route back = returnTo != null ? returnTo : Route.HOME;
            TextView home = link("‹ " + (back == Route.HOME ? "Home" : "Back to " + routeTitle(back)), v -> go(back));
            home.setPadding(0, 0, dp(12), dp(6));
            col.addView(home);
        }
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(textPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);
        if (line != null) {
            TextView l = body(line);
            l.setTextColor(textSecondary);
            l.setTextIsSelectable(false);
            col.addView(l);
        }
        attach(col, driveBar);
        if (flowRoute(route) != null) {
            // Shown, and worded, by updateButtons from the run as it stands, so a new write hides it at once.
            attach(col, flashAgainLink);
        }
    }

    /** "Flash again" only while the run is finished; "Done." only once every step of the route is done. */
    private void showFlashAgain(FlashFlow f) {
        boolean again = f != null && FlashFlow.finished(f);
        flashAgainLink.setVisibility(again ? View.VISIBLE : View.GONE);
        if (again) {
            String text = (f.view(live()).current() < 0 ? "Done. " : "") + "Start a new run";
            if (!text.contentEquals(flashAgainLink.getText())) {
                flashAgainLink.setText(text);
            }
        }
    }

    /** A route's name as its header and Home tile show it, for "‹ Back to …" and for text naming the route. */
    private static String routeTitle(Route r) {
        switch (r) {
            case REPAIR:
                return "Write custom firmware";
            case PROBE:
                return "Identify the image sensor";
            case RESTORE:
                return "Restore stock firmware";
            case IDENTIFY:
                return "Identify this camera's firmware";
            default:
                return "Home";
        }
    }

    /** "Back up photos first": the backup route, with a way back to this one. */
    private void backUpFirst() {
        returnTo = route;
        go(Route.BACKUP_PHOTOS);
    }

    /** The label of the flash log's summary line: it is the app's own record, not a reading of the camera. */
    private static final String LAST_FLASH = "Last write from this app: ";

    private void renderHome() {
        header("Charmera Card", "Back up, write firmware and look after a Kodak Charmera from the phone.");
        TextView fw = body(LAST_FLASH + flashLog.summary(java.util.TimeZone.getDefault()));
        fw.setTextColor(textSecondary);
        fw.setPadding(0, 0, 0, dp(10));
        col.addView(fw);
        tile("Back up photos", "Save the camera's photos and videos as a zip.", Route.BACKUP_PHOTOS);
        tile(routeTitle(Route.IDENTIFY), "From the photos on the card. Writes nothing.", Route.IDENTIFY);
        tile(routeTitle(Route.REPAIR), "Stock firmware for your sensor, plus the changes you tick. Now: "
                + cap(repairSel.label()) + ".", Route.REPAIR);
        tile(routeTitle(Route.PROBE), "Only for a camera that can't take photos. It replaces the camera's firmware"
                + " with a test build. Try " + q(routeTitle(Route.IDENTIFY)) + " first.", Route.PROBE);
        tile(routeTitle(Route.RESTORE), "Stock v006, for SP140A cameras. Needs a chip backup. Without one, use Advanced."
                + " OV9732 camera? Write base v012 with no mods.", Route.RESTORE);
        tile("Test shots", "Shoot the colour card and save the shots.", Route.TEST_SHOTS);
        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        links.setGravity(android.view.Gravity.CENTER_VERTICAL);
        links.addView(link("Write log", v -> go(Route.FLASH_LOG)));
        links.addView(dot());
        links.addView(link("Advanced", v -> go(Route.ADVANCED)));
        links.addView(dot());
        links.addView(link("Check for app updates", v -> checkForUpdate()));
        col.addView(links);
        attach(col, updateStatus);
        TextView footer = body("Charmera Card " + appVersion() + " · For the original 2025 Charmera (yellow Kodak boot"
                + " screen), never a Millennium or CLOT edition. Charge the battery fully before any write.");
        footer.setTextColor(textSecondary);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        col.addView(footer);
    }

    /** A Home button for a route: a card with its name in bold and one short line under it. */
    private void tile(String title, String line, final Route to) {
        LinearLayout c = card();
        android.graphics.drawable.Drawable bg = c.getBackground();
        c.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ripple), bg, null));
        c.setClickable(true);
        c.setOnClickListener(v -> go(to));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(accent);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        TextView l = body(line);
        l.setTextColor(textSecondary);
        l.setTextIsSelectable(false);
        l.setPadding(0, dp(2), 0, 0);
        c.addView(l);
    }

    private TextView dot() {
        TextView d = body(" · ");
        d.setTextColor(textSecondary);
        d.setTextIsSelectable(false);
        return d;
    }

    private void renderBackupPhotos() {
        header("Back up photos", "Save the camera's photos and videos as a zip.");
        Step drive = readDriveStep("1");
        Step options = step("2", "Options", "The zip holds the photos and videos in DCIM and SPIDCIM. It never includes"
                + " the update file, SENSOR.TXT or other folders.");
        options.add(fixDatesBox);
        options.add(dateNamesBox);
        options.add(wipeBox);
        options.more("The camera writes dates other programs can't read, so the copies get readable ones. The files on"
                        + " the card are never changed.",
                "Videos get the card's file time, read in the phone's time zone. On some phones that's off by the"
                        + " time-zone difference.",
                "Delete removes only the camera's own JPEG photos and AVI videos whose copy in the zip read back the"
                        + " same. Folders and other files stay.",
                "With delete on, the zip also keeps each date-fixed file as the camera wrote it, under its name on the"
                        + " card, in originals/.",
                "SPIDCIM holds the photos the camera copied from its own memory, which a write replaces.");
        options.needs = new Step[]{drive};
        final Step save = step("3", "Back up", "Choose a place for the zip. The app copies the files, then reads the"
                + " zip back to check every one.");
        save.add(zipButton);
        save.result(zipStatus);
        save.more("A big card takes minutes, and the app keeps the screen on while it works. Keep the cable in until"
                        + " the result shows.",
                "Choose a place on the phone, not the camera's card.",
                "Android adds (1) to the name when a file of that name exists.");
        save.needs = new Step[]{drive};
        // A zip running, or one whose end the owner has not seen with the drive listed, keeps the step open even with
        // the drive away (pulled mid-zip): its result, and what to do about it, never hides behind step 1. The button
        // stays off until the drive is listed again (updateButtons).
        save.ready = () -> unseen(backupArchive);
        save.done = () -> backupSaved && !archiving() && Objects.equals(backupSavedFrom, treeUuid());
        // Folded, it still says which copies are not whole, and what was deleted from the camera, or that nothing was.
        save.summaryText = () -> {
            String outcome = zipStatus.getText().toString();
            StringBuilder folded = new StringBuilder(save.resultLine());
            for (String warning : copyWarnings(outcome)) {
                folded.append("\n").append(warning);
            }
            String deleted = deleteLine(outcome);
            return deleted == null ? folded.toString() : folded + "\n" + deleted;
        };
        Step eject = ejectStep("4");
        eject.needs = new Step[]{drive};
        // Once ejected (or while ejecting) the drive step reads "not connected"; keep this step open.
        eject.ready = () -> progressEjected || leavingNow();
    }

    private void renderRepair() {
        header(routeTitle(Route.REPAIR), "The Charmera was made with two image sensors, and each needs its own firmware."
                + " The base is the stock firmware for your sensor: v012 for the OV9732, v006 for the SP140A. Mods are"
                + " optional changes on top. Don't know your sensor? Run " + q(routeTitle(Route.IDENTIFY)) + " first.");
        // Each step is numbered and shown as the route's flash run says (FlashFlow; refreshSteps).
        Step intro = flowStep(FlashFlow.Step.INTRO, "Before you start", "Writing firmware replaces the photos kept in the"
                + " camera's own memory. Photos on the SD card stay. Charge the battery fully first. If the write is cut"
                + " off, only a chip programmer can bring the camera back. For the original 2025 series only (matte"
                + " body, yellow Kodak boot screen), never a Millennium (blue boot screen) or CLOT edition.");
        intro.add(button("Back up photos first", v -> backUpFirst()));
        intro.add(primary("Start the steps", v -> {
            repairStarted = true;
            updateButtons();
        }));
        intro.more("Base v012 is for a camera with an OmniVision OV9732 sensor. Use " + q(routeTitle(Route.PROBE))
                        + " only when the card has no photos from before a write.",
                writtenOnRestore(repairSel)
                        ? "With no mods ticked, base v006 is the stock restore file. " + q(routeTitle(Route.RESTORE))
                        + " writes it and compares it with your chip backup. Without a chip backup, use Advanced ("
                        + q(DOWNLOAD) + ")."
                        : "You don't need a chip backup (a read of the camera's flash chip, made with a programmer). The"
                        + " app writes only a known file, one whose SHA-256 is on the list published with this"
                        + " project. If nothing confirms which firmware the camera runs (a chip backup, or Identify),"
                        + " it says so before it writes.",
                wayBack(repairSel));
        flashDriveStep();
        boolean factory = Recipe.V012.equals(repairSel.base);
        Step write = flowStep(FlashFlow.Step.WRITE, "Write firmware", "Pick the base and tick the mods, then tap "
                + q(MAKE_FIRMWARE) + ". The app builds the update file from " + dumpWords(repairSel.base)
                + " and checks it. Then tap " + q(WRITE) + ".");
        write.add(repairName);
        write.add(customPanel);
        write.add(repairButton);
        write.add(stockV006Panel);
        write.add(factoryHint);
        write.add(factoryFileButton);
        refreshRepairChoice();
        write.result(fileStatus);
        write.add(fileDetailToggle);
        write.add(fileDetail);
        write.add(writeButton);
        write.result(writeStatus);
        write.more(factory ? "The app downloads the public copy of factory build v012 (2 MB) from GitHub once, at a"
                        + " fixed commit, and keeps it on this phone. If you chose your own chip backup instead, it"
                        + " stays on the phone too."
                        : Recipe.V008.equals(repairSel.base)
                        ? "Making it downloads build v008_test's public file (1.4 MB) from GitHub once, at a fixed"
                        + " commit."
                        : "Making it downloads the public v006 chip backup (2 MB) from GitHub once, at a fixed commit.",
                "The app reads the file back from the card after writing and compares it.",
                "Nothing changes on the camera until it is switched on with the card in it.");
        foldWrite(write);
        flashStep();
        removeStep();
        Step check = flowStep(FlashFlow.Step.CHECK, "Photo check", "Take one photo with the camera, switch it off,"
                + " reconnect, then tap " + q(CHECK_PHOTO) + ".");
        check.add(checkPhotoButton);
        check.result(checkStatus);
        check.more("The app reads the start of the newest photo taken since the write. Each firmware writes that part a little"
                        + " differently.",
                Recipe.expectedPhoto(repairSel) == PhotoId.Kind.FACTORY_EXIF ? cap(repairSel.label())
                        + " writes the factory header with its own EXIF block (Make Kodak, Model Charmera), so the"
                        + " check looks for that one."
                        : factory && repairSel.stock() ? "Stock v012 is the factory firmware. Its photos have the factory"
                        + " header (no EXIF block), so the check looks for that one. Every v012 build without EXIF dates"
                        + " writes the same header, so a match doesn't show which one the camera runs."
                        : factory ? cap(repairSel.label()) + " is built on the factory build v012. Its photos have the"
                        + " factory header (no EXIF block), not the public build's, so the check looks for that one."
                        : Recipe.V006.equals(repairSel.base) && repairSel.stock() ? "The stock restore file and the"
                        + " probe write the same header. A match shows the camera runs one of them, but not which one."
                        : "The stock restore file, the probe and " + repairSel.label() + " all write the"
                        + " same header. A match shows the camera runs one of them, but not which one.",
                "It can't see the picture, so a black or frozen photo passes too. Open the photo and look at it.",
                "If the app didn't list the card at the write, or was restarted since, it picks the photo by its"
                        + " time, which comes from the camera's clock.");
    }

    /** The Repair intro's way back from a pick, in the words that fit its base and mods. */
    private static String wayBack(Recipe.Selection sel) {
        String step = "step " + FlashFlow.number(FlashFlow.Route.REPAIR, FlashFlow.Step.WRITE);
        if (sel.mods.isEmpty()) {
            // a stock pick is itself what the camera's mods are undone with
            return Recipe.V012.equals(sel.base) ? "This is the factory firmware of OV9732 cameras, unchanged. It's the"
                    + " way back from every v012 build."
                    : Recipe.V006.equals(sel.base) ? "This is the stock restore file itself."
                    : "This is build v008_test unchanged. To go back from it, write the firmware this camera ran"
                    + " before, using this camera's chip backup.";
        }
        if (Recipe.V012.equals(sel.base)) {
            return "To go back, write the firmware the camera ran before in " + step + ". That's another v012 build,"
                    + " or stock v012, the factory firmware (base v012 with no mods ticked).";
        }
        if (Recipe.V008.equals(sel.base)) {
            return "To go back from " + sel.label() + ", write the firmware this camera ran before, using this"
                    + " camera's chip backup. That's the stock restore file (" + q(routeTitle(Route.RESTORE)) + ") if"
                    + " the backup, read before the camera was ever written to, shows the public build.";
        }
        if (sel.has(Recipe.OV9732_PORT) && sel.mods.size() > 1) {
            return "To go back from " + sel.label() + ", write v006 with the OV9732 port. The app makes it here too"
                    + " (base v006 with only the OV9732 port ticked).";
        }
        if (sel.has(Recipe.OV9732_PORT)) {
            return "To go back from " + sel.label() + ", pick another OV9732 build in " + step + " and write it.";
        }
        return "To go back from " + sel.label() + ", write the stock restore file. Use " + q(routeTitle(Route.RESTORE))
                + " with this camera's chip backup, or Advanced > " + q(DOWNLOAD) + " without one.";
    }

    private void renderProbe() {
        header(routeTitle(Route.PROBE), "For a camera that can't take photos. This writes a test build that reads the"
                + " sensor's ID. It doesn't fix the camera, and it replaces the camera's firmware until you write"
                + " another.");
        photosNote();
        flashDriveStep();
        Step write = flowStep(FlashFlow.Step.WRITE, "Write the sensor-ID probe", "The app makes the probe from the"
                + " public v006 chip backup, checks it, and copies it to the card.");
        write.add(probeButton);
        write.result(fileStatus);
        write.add(fileDetailToggle);
        write.add(fileDetail);
        write.add(writeButton);
        write.result(writeStatus);
        write.more("When the camera next opens photo mode, the probe reads which sensor answers, writes SENSOR.TXT to"
                        + " the card, and stops there.",
                "You don't need a chip backup. The app writes only the known file, checked byte for byte.",
                "It changes no boot or update code. On an SP140A camera the stock restore file reverses it. On an OV9732"
                        + " camera, undo it with a v012 build, stock or with mods (" + q(routeTitle(Route.REPAIR))
                        + "), not the stock restore file.");
        foldWrite(write);
        flashStep();
        removeStep();
        Step read = flowStep(FlashFlow.Step.READ, "Read the result", "Power the camera on, open photo mode once,"
                + " switch it off, then reconnect.");
        read.add(readResultButton);
        read.result(sensorStatus);
        read.more("While the update file is on the card the camera writes it again instead of running it, so remove it"
                + " first.");
    }

    private void renderRestore() {
        header(routeTitle(Route.RESTORE), null);
        col.addView(caution("This screen writes stock v006, the firmware of SP140A cameras, and compares it with your"
                + " chip backup first. On an OV9732 camera, go to " + q(routeTitle(Route.REPAIR)) + ", pick base v012"
                + " and tick no mods. Original 2025 series only, never a Millennium (blue boot screen) or CLOT"
                + " edition."));
        photosNote();
        // Each step is numbered and shown as the route's flash run says (FlashFlow; refreshSteps). Before the write the
        // backup, the file and the drive steps follow what is on record now; once the file is on the card they are
        // behind the owner (a backup changed before the flash starts the run over: FlashFlow.backupChanged).
        Step backupStep = flowStep(FlashFlow.Step.BACKUP, BACKUP, "A 2 MB copy of this camera's flash chip, read"
                + " with a clip and a 3.3 V programmer before the camera was ever written to. It isn't the photo zip. You"
                + " only have one if the camera was opened and its chip read.");
        backupStep.add(backupButton);
        backupStep.result(backupStatus);
        backupStep.add(backupDetailToggle);
        backupStep.add(backupDetail);
        backupStep.more("The stock restore file is only for a camera whose backup, read before the camera was ever"
                        + " written to, shows the public build.",
                "Several owners report working cameras after writing it, but what their cameras ran before isn't known.",
                "The app refuses the public chip backups and any read named unverified-read.",
                "A camera that stopped taking pictures after a write has firmware for the other sensor. Write one that"
                        + " fits from the card: on an OV9732 camera, a v012 build (stock or with mods) in "
                        + q(routeTitle(Route.REPAIR)) + ". Only a camera with a sensor no known build drives needs"
                        + " a copy of a working camera's firmware, written with the toolkit on a computer (see the"
                        + " Recovery guide below, section D).",
                "The app can't tell which camera a backup came from, and a backup read after a write only shows"
                        + " what was written. Use one read before this camera was first written to.",
                "Keep the backup file somewhere safe. It's the only way back to what the camera runs now.");
        backupStep.add(linkText("Recovery guide ↗", DOCS_PAGE + "02-RECOVERY.md"));
        // Folded once the file is on the card, these two say what the run knows when the screen no longer has the
        // backup or the file it wrote (a restart, or another file loaded since).
        backupStep.summaryText = () -> {
            FlashFlow f = flow();
            return backupSha256 == null && f != null && f.past() ? "No backup is chosen now. The update file was"
                    + " checked against the one chosen at the time." : backupStep.resultLine();
        };
        Step file = flowStep(FlashFlow.Step.FILE, "Get the stock restore file", "The app downloads the public 2 MB"
                + " v006 chip backup, makes the restore file from it, and checks it against your backup.");
        file.add(downloadButton);
        file.add(useFileButton);
        file.result(fileStatus);
        file.add(fileDetailToggle);
        file.add(fileDetail);
        file.add(downloadPageLink);
        file.more("Have the file already? Tap " + q(USE_FILE) + ".",
                "Card in the phone's own slot on Android 11 or later? Use " + q(SAVE) + " on Advanced.");
        file.summaryText = () -> {
            FlashFlow f = flow();
            if (f == null || !f.past() || fileCheck != null && fileCheck.sha256.equals(f.sha())) {
                return file.resultLine();
            }
            return fileCheck == null ? f.writeLine() : "On the card: " + f.cardName() + ". The file loaded now is"
                    + " another one.";
        };
        flashDriveStep();          // one current step at a time: backup, file, then the drive
        Step write = flowStep(FlashFlow.Step.WRITE, "Write to the card", "The app copies the file to the card's top"
                + " folder and reads it back.");
        write.add(writeButton);
        write.result(writeStatus);
        // With another file loaded that passes, it is that one's write next (FlashFlow: Live.loadedOther).
        foldWrite(write);
        flashStep();
        removeStep();
    }

    /**
     * The Identify route: what the photos on the card say about the firmware that took them, and what
     * the owner can see on the camera. No flash run, no backup and no file: it reads the photos' headers and writes
     * nothing, to the card or to any run.
     */
    private void renderIdentify() {
        header(routeTitle(Route.IDENTIFY), "From the photos on the card and what the camera shows. Writes nothing.");
        Step drive = readDriveStep("1");
        Step look = step("2", "Read the photos", "Each firmware writes the start of its photo files a little"
                + " differently. The app reads that part of each photo to tell which firmware took it.");
        look.add(identifyButton);
        look.result(identifyStatus);
        look.add(identifyDetailToggle);
        look.add(identifyDetail);
        look.add(identifyNextButton);
        look.more("Nothing is written to the card or the write log.",
                "Only photos in DCIM are read, at most " + Identify.SCAN_CAP + ", and only up to the start of the"
                        + " picture. Videos are counted, not read.",
                "Photos copied from another camera or edited on a computer may not be recognised. They're counted and"
                        + " left out.",
                "Open a photo and look at it too. A black or frozen picture has the same header.");
        look.needs = new Step[]{drive};
        Step ask = step("3", "Look at the camera", "No card or app needed.");
        ask.body.addView(body("1. On the camera, open Setup and go to page 2. If there are Volume and LED rows after"
                + " FORMAT, the camera runs one of this project's builds with that mod. If not, it's stock, or a"
                + " build without that mod."));
        ask.body.addView(body("2. If photo or video mode freezes after a write, the firmware you wrote is for the"
                + " other sensor. Write the other base."));
        ask.more("Setup > Version shows JH5408  20250520 on every known build, ours too, so it tells you nothing.",
                "A freeze points at the firmware written last, not at the camera. Photos from before that write show"
                        + " what fits.");
        showIdentify();
    }

    private void renderTestShots() {
        header("Test shots", "Shoot the same colour card after each write, so the shots compare.");
        Step card = step("1", "Show the colour card", "Hold the phone flat in front of the camera, or use a printed"
                + " card.");
        card.add(primary("Show the colour card", v -> {
            startShots();
            if (!open(new Intent(this, CalibrationActivity.class))) {
                android.widget.Toast.makeText(this, "The colour card did not open.",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }));
        card.add(button("Use a printed card", v -> startShots()));
        card.result(shotsStartStatus);
        card.more("Take 3 shots per light.", "Wait 5 seconds between shots.", "Cover the camera's flash.",
                "Turn off auto-brightness and night light on the phone.",
                "Tapping either button starts a new set. Only photos taken after that are saved.");
        card.done = () -> shotsSince != 0;
        Step light = step("2", "Lighting", "The light the shots were taken in. It goes in the zip's name.");
        light.add(lightingGroup);
        light.add(otherLighting);
        light.result(lightingStatus);
        light.needs = new Step[]{card};
        light.done = () -> shotsSince != 0 && lightChosen;
        Step drive = readDriveStep("3");
        drive.needs = new Step[]{card};
        Step save = step("4", SHOTS_BUTTON, "The app zips the photos taken since step 1, with a manifest.txt that lists"
                + " them.");
        save.add(shotCountToggle);
        save.add(shotCount);
        if (!shotCount.getText().toString().trim().isEmpty()) {
            shotCount.setVisibility(View.VISIBLE);       // a count typed before stays in sight
            shotCountToggle.setText("\u25be Choose how many");
        }
        save.add(shotsWipeBox);
        save.add(shotsButton);
        save.result(shotsStatus);
        save.more("The zip is named Charmera-<firmware>-<lighting>-<date>.zip. The firmware comes from the write log.",
                "No new photos found? Tap \"Choose how many\" and type how many of the newest photos to save.",
                "The photos are saved exactly as the camera wrote them. Their dates aren't changed.",
                "Delete removes only photos whose copy in the zip read back the same.");
        save.needs = new Step[]{card, drive};
        save.ready = () -> unseen(shotsArchive);      // as on Back up photos: a pull mid-zip never hides the result
    }

    private void renderFlashLog() {
        header("Write log", "What the app wrote, and what you told it. It is not proof of what the camera runs.");
        TextView now = body(LAST_FLASH + flashLog.summary(TimeZone.getDefault()));
        now.setPadding(0, 0, 0, dp(10));
        col.addView(now);
        List<FlashLog.Entry> entries = flashLog.entries();
        if (entries.isEmpty()) {
            TextView none = body("Nothing logged yet.");
            none.setTextColor(textSecondary);
            col.addView(none);
            return;
        }
        LinearLayout list = card();
        for (FlashLog.Entry e : entries) {
            StringBuilder sb = new StringBuilder(FlashLog.when(e.timeMs, TimeZone.getDefault())).append(" · ")
                    .append(e.event.label);
            if (!e.firmware.isEmpty()) {
                sb.append(" · ").append(e.firmware);
            }
            TextView row = body(sb.toString());
            row.setTypeface(Typeface.DEFAULT_BOLD);
            list.addView(row);
            String sub = (e.sha256Prefix.isEmpty() ? "" : "SHA-256 " + e.sha256Prefix + "…")
                    + (!e.sha256Prefix.isEmpty() && !e.note.isEmpty() ? " · " : "") + e.note;
            if (!sub.isEmpty()) {
                TextView s = body(sub);
                s.setTextColor(textSecondary);
                s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                s.setPadding(0, 0, 0, dp(6));
                list.addView(s);
            }
        }
        col.addView(button("Clear log", v -> confirmClearLog()));
    }

    private void confirmClearLog() {
        if (dialog != null) {
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Clear the write log?")
                .setMessage("The app forgets every entry, both what it wrote and what you told it. This can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear log", (di, w) -> {
                    flashLog = clearLog(this);
                    render();
                })
                .create();
        d.setOnDismissListener(di -> {
            if (dialog == di) {
                dialog = null;
            }
            if (!gone()) {
                afterDialog();
            }
        });
        dialog = d;
        d.show();
        updateButtons();
    }

    private void renderAdvanced() {
        header("Advanced", "Every tool on one page. Use it to write stock v006 without a chip backup, or an update"
                + " file you already have.");
        Step backupStep = step("1", "Chip backup (optional)", "A 2 MB read of this camera's flash chip, if you have"
                + " one. It isn't the photo zip. With one, the app compares an SP140A update file with it before"
                + " writing, and warns when any file is for the other sensor.");
        backupStep.add(backupButton);
        backupStep.result(backupStatus);
        backupStep.add(backupDetailToggle);
        backupStep.add(backupDetail);
        Step file = step("2", "Update file", q(DOWNLOAD) + " gets stock v006, for SP140A cameras. " + q(USE_FILE)
                + " takes any update file (JH_….bin).");
        file.add(downloadButton);
        file.add(useFileButton);
        file.result(fileStatus);
        file.add(fileDetailToggle);
        file.add(fileDetail);
        file.add(saveButton);
        file.result(saveStatus);
        file.add(downloadPageLink);
        file.more("On Android 11 or later with the card in the phone's own slot, save a copy, copy it to the card's"
                + " top folder with the Files app, then tap " + q(USE_FILE) + " and choose the copy so the app checks"
                + " it.");
        Step advDrive = driveStep("3");
        Step write = step("4", "Write to the card", "The app copies the file to the card's top folder and reads it"
                + " back.");
        write.add(writeButton);
        write.result(writeStatus);
        Step advEject = ejectStep("5");
        advEject.needs = new Step[]{advDrive};
        advEject.ready = () -> progressEjected || leavingNow();
        Step remove = step("6", "Remove update files", "After the camera writes it, or before you write a different file. The"
                + " camera writes it again on every power-on while the file is on the card.");
        remove.add(cleanButton);
        remove.result(cleanStatus);
        Step read = step("7", "Read the sensor result", "Only after writing the sensor-ID probe.");
        read.add(readResultButton);
        read.result(sensorStatus);
        Step app = step("8", "App update", "Charmera Card " + appVersion() + ".");
        app.add(button("Check for app updates", v -> checkForUpdate()));
        app.result(updateStatus);
        app.add(linkText("Open the releases page ↗", RELEASES_PAGE));
    }

    /** Before a flashing route without its own "Before you start": the photos in the camera's memory go. */
    private void photosNote() {
        LinearLayout c = card();
        TextView t = body("Writing firmware replaces the photos kept in the camera's own memory. Photos on the SD card stay.");
        t.setTextIsSelectable(false);
        c.addView(t);
        c.addView(button("Back up photos first", v -> backUpFirst()));
    }

    /** The camera drive step: the warning about plugging in, the saved drive's state, and Choose. */
    private Step driveStep(String number) {
        Step s = step(number, "Camera drive", null);
        s.body.addView(caution("Never plug the cable into a switched-off camera. It turns on and writes any update"
                + " file on the card."));
        s.add(driveHint);
        s.add(chooseButton);
        s.result(cardStatus);
        s.add(cardDetailToggle);
        s.add(cardDetail);
        s.more("Connect in this order: camera off and card out, switch on with the power button and wait 10 seconds,"
                        + " put the card in, then plug in the USB-C data cable.",
                "Choose the drive's top folder (\"Use this folder\"), not DCIM.",
                "A USB card reader works the same way. The phone's own SD slot works only on Android 7 to 10.",
                "Drive not showing? Some USB-C cables only charge. Android may offer to format a card the camera"
                        + " has used. That erases it, so copy the photos off first (see Card help below).");
        s.add(linkText("Card help ↗", DOCS_PAGE + "05-CARDS.md"));
        s.done = () -> card != null && card.state.writable() && mounted(volume(treeUuid()));
        return s;
    }

    /** The camera drive step of a route that only reads the card (and deletes photos): listed is enough. */
    private Step readDriveStep(String number) {
        Step s = driveStep(number);
        s.done = () -> (readable(card) || relisting()) && mounted(volume(treeUuid()));
        return s;
    }

    /** The drive, listed before, is being listed again: same drive, nothing pulled or unmounted since (dropCard). */
    private boolean relisting() {
        Card before = relistedFrom;
        return inspecting && card == null && readable(before) && tree != null && tree.equals(before.tree);
    }

    /** The saved drive is attached but not mounted: ejecting, or ejected and not yet seen as such. */
    private boolean leavingNow() {
        android.os.storage.StorageVolume v = volume(treeUuid());
        return v != null && !mounted(v);
    }

    /** The card was listed, so its photos can be read. A low-space or stale-update-file note does not matter here. */
    private static boolean readable(Card c) {
        return c != null && c.state.listed && c.root != null;
    }

    /** The Eject step of a route that writes no update file (Back up photos, Advanced). */
    private Step ejectStep(String number) {
        Step s = step(number, "Eject", "Eject before you unplug, so everything written reaches the card.");
        s.add(ejectButton);
        s.result(ejectStatus);
        return s;
    }

    /** A flashing route's step, numbered as FlashFlow numbers it for the route and shown as its run says. */
    private Step flowStep(FlashFlow.Step kind, String title, String line) {
        Step s = step(String.valueOf(FlashFlow.number(flowRoute(route), kind)), title, line);
        s.kind = kind;
        return s;
    }

    /**
     * A flashing route's drive step. Once the file is on the card it stays done (FlashFlow): an unplugged drive only
     * turns the card's buttons off, and the folded line then says what the drive line says (flash as the flash step
     * says, or how to connect the camera, in the safe order while an update file may be on the card).
     */
    private Step flashDriveStep() {
        Step s = driveStep(String.valueOf(FlashFlow.number(flowRoute(route), FlashFlow.Step.DRIVE)));
        s.kind = FlashFlow.Step.DRIVE;
        s.summaryText = () -> driveHint.length() > 0 ? driveHint.getText().toString() : s.resultLine();
        return s;
    }

    /** A flashing route's write step folded: its result, or once that is off screen what the run wrote or found. */
    private void foldWrite(final Step write) {
        write.summaryText = () -> {
            FlashFlow f = flow();
            return writeStatus.length() == 0 && f != null && f.past() ? f.writeLine() : write.resultLine();
        };
    }

    /**
     * Eject, then what to do on the camera, and the flash question. The eject is advice, not a gate: the write was
     * synced (writeFully), so the answer counts whether or not the app saw an eject. Done once "Upgrade 100%" was seen
     * or the update file is removed; after "not seen" it stays open, its More info in sight (FlashFlow).
     */
    private Step flashStep() {
        final Step s = flowStep(FlashFlow.Step.FLASH, "Eject, then let the camera write", "Eject before you unplug, so everything"
                + " written reaches the card.");
        s.add(ejectButton);
        s.result(ejectStatus);
        TextView how = body("1. Unplug the cable.\n"
                + "2. Switch the camera on with the card in it.\n"
                + "3. Wait for \"Upgrade 100%\" (15 to 20 seconds). Touch nothing until then.\n"
                + "4. Hold the power button 1 second to switch it off.");
        s.body.addView(how);
        flashQuestion(s);
        s.more("No \"Upgrade\" screen? The camera didn't find the file. With the camera off and the card out, switch"
                        + " it on and wait 10 seconds, put the card in, then connect and check the card holds the file.",
                "\"Program error\" means the camera found the file damaged and ignored it. Nothing was erased."
                        + " Remove the file and write it again.",
                "At 100% the camera stays on that screen. It does not delete the update file.");
        s.summaryText = () -> {
            FlashFlow f = flow();
            return f == null ? s.resultLine() : f.flashSummary(live());
        };
        ejectStepNow = s;
        return s;
    }

    /** "Did the camera show Upgrade 100%?" under the flash instructions; the answer goes to the flash log. */
    private void flashQuestion(Step s) {
        TextView ask = body("Did the camera show \"Upgrade 100%\"?");
        ask.setTypeface(Typeface.DEFAULT_BOLD);
        ask.setTextIsSelectable(false);
        s.body.addView(ask);
        final TextView answer = body("");
        flashAnswerView = answer;     // its line is the run's (updateButtons), so a new write clears it too
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button yes = button("Yes", v -> answerFlash(true));
        Button no = button("No / something else", v -> answerFlash(false));
        row.addView(yes, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(no, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        s.body.addView(row);
        s.body.addView(answer);
        s.results.add(answer);
    }

    /** The owner's answer: logged, and the run moves on to it whether or not an eject was seen (FlashFlow.answered). */
    private void answerFlash(boolean seen) {
        String uuid = treeUuid();
        FlashFlow f = flow();
        if (f == null || !f.past()) {
            return;     // nothing written this run: there was nothing to flash
        }
        flashLog = appendLog(this, seen ? FlashLog.Event.FLASH_SEEN : FlashLog.Event.FLASH_NOT_SEEN,
                f.firmware().isEmpty() ? null : f.firmware(), f.sha(), null);
        f.answered(seen);
        keep(uuid, f);
        ejectStatus.setText("");          // what it said ("flash now, answer below") is done with; never carry it on
        updateButtons();
        reveal(flashAnswerView);
    }

    /**
     * Remove the update file after the flash. It opens as the run says (FlashFlow.removeAvailable): once the flash
     * question is answered; at once when the write found the file there already, when the card holds another update
     * file, or another file that this route writes is loaded; before any write whenever the card holds one. It is done
     * once a listing after the removal finds no update file, and stays done until a listing finds one again.
     */
    private Step removeStep() {
        final Step s = flowStep(FlashFlow.Step.REMOVE, "Remove the update file", "The camera writes it again on"
                + " every power-on while the file is on the card.");
        s.body.addView(body("1. Take the card out, switch the camera on with the power button and wait 10 seconds.\n"
                + "2. Put the card in, then connect.\n"
                + "3. Tap " + q(REMOVE) + ", then eject the drive."));
        s.add(cleanButton);
        s.result(cleanStatus);
        // Eject again from here: the eject step above is done and folded away by now.
        s.add(removeEjectButton);
        s.more("You can also do it without the phone. With the camera off, take the card out, switch the camera on,"
                        + " put the card in, then format the card in the camera. That erases everything on the card, the update file too, so"
                        + " back up the photos first.",
                "The next time the app reads the card and finds no update file, this step counts as done.");
        FlashFlow f = flow();
        if (f != null && f.removalShown()) {
            attach(s.body, ejectStatus);
        }
        // Folded once done: the removal's result (or, once that is off screen, what the card lists), and the eject's
        // result placed under its Eject.
        s.summaryText = () -> {
            FlashFlow now = flow();
            String line = cleanStatus.length() == 0 && now != null ? now.removeLine() : s.resultLine();
            String ejected = ejectStatus.getParent() == s.body ? ejectStatus.getText().toString().trim() : "";
            return ejected.isEmpty() ? line : line + "\n" + ejected;
        };
        removeStepNow = s;
        return s;
    }

    // --- photos on the card: listing, zip backup, test shots, flash check -------------------------------------------

    /** A photo or video in the card's DCIM or SPIDCIM folder. */
    private static final class Media {
        final String docId;
        /** Its path from the card's top folder, e.g. "DCIM/PICT0001.jpg"; the zip entry's name. */
        final String path;
        final long size;
        final long mtime;
        final boolean video;
        final Uri uri;

        Media(String docId, String path, long size, long mtime, boolean video, Uri uri) {
            this.docId = docId;
            this.path = path;
            this.size = size;
            this.mtime = mtime;
            this.video = video;
            this.uri = uri;
        }

        boolean inDcim() {
            return path.regionMatches(true, 0, "DCIM/", 0, 5);
        }
    }

    /** One row of a folder listing. */
    private static final class Row {
        final String id;
        final String name;
        final String mime;
        final long size;
        final long mtime;

        Row(String id, String name, String mime, long size, long mtime) {
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.mtime = mtime;
        }
    }

    /** What one file put into the zip: the CRC and length of the bytes written, and how much of the card file read. */
    private static final class Copied {
        long crc;
        long length;
        long read;
        boolean fixed;
        /** The name it has in the zip. */
        String entry;
        /** The file is the camera's own (a Charmera JPEG or an AVI), so a verified copy may be deleted. */
        boolean camera;
    }

    /**
     * The photos and videos in DCIM (and SPIDCIM when asked), sorted by path. Worker only. Only files ending .jpg,
     * .jpeg or .avi (any case) inside those folders: never a folder, an update file, SENSOR.TXT or anything in the top
     * folder. A drive that does not answer throws, so it is never read as one without photos.
     */
    private List<Media> listMedia(Uri t, boolean spidcim) throws IOException {
        String rootId = DocumentsContract.getTreeDocumentId(t);
        List<Media> out = new ArrayList<>();
        for (Row r : children(t, rootId)) {
            if (r.id == null || r.name == null || !DocumentsContract.Document.MIME_TYPE_DIR.equals(r.mime)) {
                continue;
            }
            if (r.name.equalsIgnoreCase("DCIM") || spidcim && r.name.equalsIgnoreCase("SPIDCIM")) {
                collect(t, r.id, r.name + "/", out, 0);
            }
        }
        Collections.sort(out, (a, b) -> a.path.compareToIgnoreCase(b.path));
        return out;
    }

    private void collect(Uri t, String dirId, String prefix, List<Media> out, int depth) throws IOException {
        for (Row r : children(t, dirId)) {
            if (r.id == null || r.name == null) {
                continue;
            }
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(r.mime)) {
                if (depth < 3) {
                    collect(t, r.id, prefix + r.name + "/", out, depth + 1);
                }
                continue;
            }
            String lower = r.name.toLowerCase(Locale.ROOT);
            boolean video = lower.endsWith(".avi");
            if (lower.startsWith("._") || !(video || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
                continue;
            }
            out.add(new Media(r.id, prefix + r.name, r.size, r.mtime, video,
                    DocumentsContract.buildDocumentUriUsingTree(t, r.id)));
        }
    }

    private List<Row> children(Uri t, String docId) throws IOException {
        Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(t, docId);
        List<Row> rows = new ArrayList<>();
        try (Cursor q = getContentResolver().query(u, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                null, null, null)) {
            if (q == null) {
                throw new Plain("the drive didn't answer. The cable or card may have come loose");
            }
            while (q.moveToNext()) {
                rows.add(new Row(q.getString(0), q.getString(1), q.getString(2), q.isNull(3) ? -1 : q.getLong(3),
                        q.isNull(4) ? 0 : q.getLong(4)));
            }
        }
        return rows;
    }

    /** Up to max bytes from in (fewer at its end). */
    private static byte[] readUpTo(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 512 * 1024));
        byte[] buf = new byte[64 * 1024];
        while (out.size() < max) {
            int n = in.read(buf, 0, Math.min(buf.length, max - out.size()));
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Just after a write (or the same file found on the card), on the worker: the SENSOR.TXT on the card now, which
     * goes into that write's run (applySnapshot) and so survives a restart, since a result read later that is still
     * this file is not news; and the IDs of the card's DCIM photos, what predates the flash, for the flash check. Each
     * is left unknown when the card cannot be read now.
     */
    private void snapshotAtWrite(final String uuid, final FlashFlow.Route r, final long writtenAt) {
        final Uri t = tree;
        dcimAtWrite = null;
        dcimAtWriteFor = null;
        if (t == null || uuid == null) {
            return;
        }
        final String id = runId(uuid, r, writtenAt);
        WORKER.execute(() -> {
            String sensor;
            try {
                long[] modified = {0};
                byte[] b = readCardFile(t, "SENSOR.TXT", modified);
                sensor = sensorIdentity(b, modified[0]);
            } catch (RuntimeException e) {
                sensor = null;   // not read: no file is called unchanged, and a read later says the app cannot tell
            }
            SENSOR_SNAPSHOT.set(new Snapshot(uuid, r, writtenAt, sensor));
            ui.post(() -> {
                if (gone()) {
                    MainActivity s = screen;      // this screen has gone: the one open now takes it in
                    if (s != null && s != this && !s.gone()) {
                        s.applySnapshot();
                    }
                    return;
                }
                applySnapshot();
            });
            Set<String> ids = new HashSet<>();
            try {
                for (Media m : listMedia(t, false)) {
                    ids.add(m.docId);
                }
            } catch (IOException | RuntimeException e) {
                return;     // not connected now: the photos are picked by their time instead
            }
            dcimAtWrite = ids;
            dcimAtWriteFor = id;
        });
    }

    /** Lists the card's DCIM photos on the worker when a test-shot set begins; left null when it cannot be listed. */
    private void snapshotShots() {
        final Uri t = tree;
        final long since = shotsSince;
        final Context app = getApplicationContext();
        shotsBefore = null;
        if (t == null) {
            return;
        }
        WORKER.execute(() -> {
            Set<String> ids = new HashSet<>();
            try {
                for (Media m : listMedia(t, false)) {
                    ids.add(m.docId);
                }
            } catch (IOException | RuntimeException e) {
                return;     // not connected now: the photos are picked by their time instead
            }
            if (since == shotsSince) {      // not a set begun again meanwhile
                shotsBefore = ids;
                app.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_SHOTS_BEFORE, ids)
                        .putLong(KEY_SHOTS_BEFORE_FOR, since).apply();
            }
        });
    }

    /** Step 1 of the test shots: a new set begins now. Its start is kept in the preferences too. */
    private void startShots() {
        shotsSince = System.currentTimeMillis();
        lightChosen = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_SHOTS_SINCE, shotsSince)
                .remove(KEY_SHOTS_BEFORE).remove(KEY_SHOTS_BEFORE_FOR).remove(KEY_LIGHT_FOR).apply();
        if (readable(card) && !busy() && !archiving()) {
            snapshotShots();
        } else {
            shotsBefore = null;
        }
        if (!archiving()) {
            // The previous set's "Saved … / Deleted …" is not about this set; a backup's result stays.
            shotsArchive = null;
            shotsStatus.setText("");
        }
        setStatus(shotsStartStatus, "Set started " + FlashLog.when(shotsSince, TimeZone.getDefault()) + ". Photos"
                + " taken from then on are saved in the last step.");
        updateButtons();
    }

    /** The test-shot set's start (and the photos on the card then) as kept in the preferences, after a restart. */
    private void restoreShots() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long since = prefs.getLong(KEY_SHOTS_SINCE, 0);
        if (since <= 0) {
            return;
        }
        Set<String> before = prefs.getLong(KEY_SHOTS_BEFORE_FOR, 0) == since
                ? prefs.getStringSet(KEY_SHOTS_BEFORE, null) : null;
        shotsBefore = before == null ? null : new HashSet<>(before);
        shotsSince = since;
        lightChosen = prefs.getLong(KEY_LIGHT_FOR, 0) == since;    // a light picked for this same set
    }

    /** The owner picked the light for this set: the lighting step is done. It is kept beside the set's start. */
    private void chooseLight() {
        if (shotsSince != 0 && !lightChosen) {
            lightChosen = true;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_LIGHT_FOR, shotsSince).apply();
            updateButtons();
        }
    }

    private void showLighting() {
        otherLighting.setVisibility(lightingIndex() == 2 ? View.VISIBLE : View.GONE);
        lightingStatus.setText("Lighting: " + lighting());
    }

    private int lightingIndex() {
        for (int i = 0; i < lightingChoices.length; i++) {
            if (lightingChoices[i].isChecked()) {
                return i;
            }
        }
        return 0;
    }

    private String lighting() {
        int i = lightingIndex();
        if (i == 2) {
            String other = otherLighting.getText().toString().trim();
            return other.isEmpty() ? "Other" : other;
        }
        return lightingChoices[i].getText().toString();
    }

    /** Letters, digits and dots only, for a file name; "unknown" when nothing is left. */
    private static String tag(String s) {
        String t = s == null ? "" : s.replaceAll("[^A-Za-z0-9.]+", "");
        return t.isEmpty() ? "unknown" : t;
    }

    /**
     * The firmware for the test-shot zip's name: the one the owner saw the camera take (FlashLog.confirmed: a
     * flash seen, or a passed photo check after a flash seen of that file), otherwise "unknown". A reviewed file is
     * named by its SHA-256 as Recipe.fileTag gives it ("v012-volume-led-exif"), since its full name is too long for a
     * file name; any other entry by the words it was logged with ("v0.27" from an older app).
     */
    private String firmwareTag() {
        FlashLog.Entry e = readLog().confirmed();
        if (e == null) {
            return "unknown";
        }
        Recipe.Selection s = Recipe.selectionOfPrefix(e.sha256Prefix);
        return s != null ? Recipe.fileTag(s) : tag(e.firmware);
    }

    private static String stamp(String pattern, long ms) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ROOT);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(ms));
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1e6);
    }

    /** "Choose where to save the zip" / "Save the shots": a wipe is confirmed first, then the save screen opens. */
    private void confirmArchive(final boolean shots) {
        if (busy() || archiving() || dialog != null || !readable(card)) {
            return;
        }
        final String name = shots
                ? "Charmera-" + firmwareTag() + "-" + tag(lighting()) + "-" + stamp("yyyyMMdd-HHmm",
                System.currentTimeMillis()) + ".zip"
                : "Charmera-" + stamp("yyyyMMdd-HHmm", System.currentTimeMillis()) + ".zip";
        boolean wipe = shots ? shotsWipeBox.isChecked() : wipeBox.isChecked();
        if (!wipe) {
            pickZip(shots ? SAVE_SHOTS : SAVE_ZIP, name);
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Delete from the camera afterwards?")
                .setMessage("Once the zip is saved and read back, the app deletes from the camera each "
                        + (shots ? "photo" : "photo and video") + " whose copy in the zip matched. Files this camera"
                        + " did not make, folders, the update file and SENSOR.TXT stay.\n\nThe camera keeps no other"
                        + " copy, so the zip is then the only one.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save and delete", (di, w) -> pickZip(shots ? SAVE_SHOTS : SAVE_ZIP, name))
                .create();
        d.setOnDismissListener(di -> {
            if (dialog == di) {
                dialog = null;
            }
            if (!gone()) {
                afterDialog();
            }
        });
        dialog = d;
        d.show();
        updateButtons();
    }

    /** Android's save screen for the zip; its result starts the zip. */
    private void pickZip(int request, String title) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, title);
        try {
            startActivityForResult(i, request);
        } catch (ActivityNotFoundException | SecurityException e) {
            setStatus(request == SAVE_SHOTS ? shotsStatus : zipStatus, "Nothing was saved: Android could not open its"
                    + " screen for saving a file." + detail(why(e)));
        }
    }

    /** The save screen made the (empty) zip at dest: fill it from the card on the worker. */
    private void startArchive(final Uri dest, final boolean shots) {
        final Card c = card;
        final TextView status = shots ? shotsStatus : zipStatus;
        String refusal = busy() || archiving() ? "a write, a removal or another zip is running"
                : !readable(c) ? "the app hasn't listed the camera's drive. Connect it and try again"
                : onCameraCard(dest) ? "that place is on the camera's own card. Choose a place on the phone, not the"
                + " camera's card" : null;
        if (refusal != null) {
            clearOutcome(shots);        // an older zip's outcome must not come back in place of this refusal
            setStatus(status, "Nothing was saved: " + sentence(refusal));
            WORKER.execute(() -> delete(dest));     // the save screen's empty file must not stay behind
            return;
        }
        // Until the zip is read back, a process ended by Android leaves it unfinished: the next start says so.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ZIP_RUNNING, dest.toString())
                .putBoolean(KEY_ZIP_RUNNING_SHOTS, shots).commit();
        final boolean fix = !shots && fixDatesBox.isChecked();
        final boolean dateNames = dateNamesBox.isChecked();
        final boolean wipe = shots ? shotsWipeBox.isChecked() : wipeBox.isChecked();
        int n = 0;
        // A count folded away under "Choose how many" is not in sight, so it chooses nothing.
        if (shots && shotCount.getVisibility() == View.VISIBLE) {
            try {
                n = Integer.parseInt(shotCount.getText().toString().trim());
            } catch (NumberFormatException e) {
                n = 0;
            }
        }
        final int count = Math.max(0, Math.min(n, 9999));
        final long since = shotsSince;
        final Set<String> before = shotsBefore;
        final String header = shots ? "Charmera test shots\n"
                + "Firmware (write log): " + flashLog.summary(TimeZone.getDefault()) + "\n"
                + "Lighting: " + lighting() + "\n"
                + "App: Charmera Card " + appVersion() + "\n"
                + "Saved: " + stamp("yyyy-MM-dd HH:mm", System.currentTimeMillis()) + " (phone time)\n" : null;
        final Archive a = new Archive(shots, c);
        if (shots) {
            shotsArchive = a;
        } else {
            backupArchive = a;
        }
        ejectedThisMount = false;       // the zip reads (and may delete from) the mounted drive
        ejectAsked = false;
        ejectStatus.setText("");        // an eject before the zip no longer applies
        if (!shots) {
            backupSaved = false;
            backupSavedFrom = treeUuid();
        }
        showArchive();
        updateButtons();
        final Context app = getApplicationContext();
        WORKER.execute(() -> {
            String outcome = null;
            try {
                outcome = runArchive(a, app, c.tree, dest, fix, dateNames, wipe, count, since, before, header);
            } finally {
                forgetRunningZip(app);      // ended, one way or another: nothing is left unfinished for the next start
                a.outcome = outcome != null ? outcome : "The zip stopped with an unexpected error. Don't rely on it."
                        + " Nothing was deleted from the camera unless this says so.";
            }
            postArchive();
        });
    }

    /** The zip running now is read back or has ended: its save place is no longer kept (KEY_ZIP_RUNNING). */
    private static void forgetRunningZip(Context app) {
        app.getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_ZIP_RUNNING).remove(KEY_ZIP_RUNNING_SHOTS)
                .commit();
    }

    /**
     * dest is a document on the saved camera drive's own card: the save screen offers that card like any other place,
     * and a zip there is no copy off the camera (with delete on, the only copy would be on the card it came from).
     */
    private boolean onCameraCard(Uri dest) {
        String mine = treeUuid();
        if (mine == null || dest == null || !EXTERNAL_STORAGE.equals(dest.getAuthority())) {
            return false;
        }
        try {
            String id = DocumentsContract.getDocumentId(dest);
            int colon = id.indexOf(':');
            return colon > 0 && mine.equalsIgnoreCase(id.substring(0, colon));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * A zip refused, or waiting for the card's listing: the slot's last outcome goes (and, for a backup, its "saved"),
     * so an older zip's result never shows in place of this one's. A zip still running is kept.
     */
    private static void clearOutcome(boolean shots) {
        Archive a = archiveOf(shots);
        if (a == null || a.outcome == null) {
            return;
        }
        if (shots) {
            shotsArchive = null;
        } else {
            backupArchive = null;
            backupSaved = false;
        }
    }

    /**
     * Start-up: a zip whose save place is still kept (KEY_ZIP_RUNNING) with no zip running was left unfinished when
     * Android ended the app's process, before it was read back, so nothing was deleted from the camera for it. If that
     * file is still there, the zip's step says so; the key goes either way.
     */
    private void unfinishedZip() {
        final android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String left = prefs.getString(KEY_ZIP_RUNNING, null);
        if (left == null || archiving()) {
            return;
        }
        final boolean shots = prefs.getBoolean(KEY_ZIP_RUNNING_SHOTS, false);
        prefs.edit().remove(KEY_ZIP_RUNNING).remove(KEY_ZIP_RUNNING_SHOTS).apply();
        final Uri dest = Uri.parse(left);
        WORKER.execute(() -> {
            String name = null;
            try (Cursor q = getContentResolver().query(dest, new String[]{OpenableColumns.DISPLAY_NAME}, null, null,
                    null)) {
                if (q != null && q.moveToFirst() && !q.isNull(0)) {
                    name = q.getString(0);
                }
            } catch (RuntimeException e) {
                name = null;        // gone, or no longer reachable: nothing to say about it
            }
            final String found = name;
            ui.post(() -> {
                if (gone() || found == null || archiving()) {
                    return;
                }
                setStatus(shots ? shotsStatus : zipStatus, "A zip was not finished: the app was stopped while it saved "
                        + found + ", before it was read back. Don't rely on it. Delete it and save the zip again."
                        + " Nothing was deleted from the camera for it.");
            });
        });
    }

    /** Has the screen open now show the zip's progress or outcome. Worker or main thread. */
    private void postArchive() {
        ui.post(() -> {
            if (gone()) {
                MainActivity s = screen;      // this screen has gone: the one open now shows it
                if (s != null && s != this && !s.gone()) {
                    s.archiveShown();
                }
                return;
            }
            archiveShown();
        });
    }

    /** Fills each zip's status from its own slot: the backup's under Back up, the shots' under Save the shots. */
    private void showArchive() {
        showArchive(backupArchive, zipStatus);
        showArchive(shotsArchive, shotsStatus);
    }

    private static void showArchive(Archive a, TextView status) {
        if (a == null) {
            return;
        }
        String o = a.outcome;
        setStatus(status, o != null ? o : a.progress);
    }

    /**
     * Shows the zips' state; once one has ended, looks at the card again (photos may have been deleted) and reveals
     * the result after that listing is back, so the steps it moves never hide it or scroll it away.
     */
    private void archiveShown() {
        showArchive();
        for (boolean shots : new boolean[]{false, true}) {
            Archive a = archiveOf(shots);
            if (a == null || a.outcome == null || a.shown) {
                continue;
            }
            a.shown = true;
            flashLog = readLog();
            TextView status = shots ? shotsStatus : zipStatus;
            if (inspecting) {
                revealAfterLook = status;       // the look in flight shows it once it is back
            } else if (tree != null && !busy() && dialog == null) {
                revealAfterLook = status;
                inspectCard();
            } else {
                reveal(status);
            }
        }
        updateButtons();
    }

    /** A zip's end has been on screen with the drive listed: from now on its step opens and locks as usual. */
    private void markSeen(Archive a, TextView status, boolean listed) {
        if (a != null && a.outcome != null && !a.seen && listed && status.isShown()) {
            a.seen = true;
        }
    }

    /**
     * Runs on the worker: lists the photos, writes the zip, syncs it, reads it back and checks every entry, then, only
     * when asked, deletes from the card exactly the files whose entries matched (through delete(), one file each).
     * Returns the outcome for the step's result.
     */
    private String runArchive(Archive a, Context app, Uri t, Uri dest, boolean fix, boolean dateNames, boolean wipe,
                              int count,
            long since, Set<String> before, String manifestHead) {
        boolean shots = a.shots;
        List<Media> chosen;
        try {
            chosen = listMedia(t, !shots);
        } catch (IOException | RuntimeException e) {
            return "Nothing was saved. The app couldn't list the camera's photos: " + sentence(cause(e, "the drive"
                    + " did not answer")) + emptyZip(dest) + detail(detailOf(e));
        }
        if (shots) {
            chosen = newestShots(chosen, count, since, before);
        }
        if (chosen.isEmpty()) {
            return "Nothing was saved: " + (shots ? count > 0 ? "the card's DCIM folder holds no photos."
                    : before != null ? "no photo has appeared in the card's DCIM folder since step 1. To save photos"
                    + " that were there already, type how many of the newest to save, then try again."
                    : "no photo taken since step 1 is on the card. If you took some, the camera's clock may differ"
                    + " from the phone's. Type how many of the newest photos to save, then try again."
                    : "the card's DCIM and SPIDCIM folders hold no photos or videos.") + emptyZip(dest);
        }
        TimeZone tz = TimeZone.getDefault();
        Map<String, long[]> written = new LinkedHashMap<>();    // entry name -> {CRC-32, length}
        Map<String, Media> sourceOf = new LinkedHashMap<>();
        // entry name of a date-fixed copy -> the entry that holds the card file as it is (delete on only)
        Map<String, String> originalOf = new LinkedHashMap<>();
        Set<String> removable = new HashSet<>();
        Set<String> lower = new HashSet<>();
        int photos = 0;
        int videos = 0;
        int fixed = 0;
        int originals = 0;
        int shortOriginals = 0;
        int shortRead = 0;
        int unsized = 0;
        long bytes = 0;
        int got = chosen.size();
        StringBuilder manifest = manifestHead == null ? null : new StringBuilder(manifestHead).append("Selected: ")
                .append(count > 0 ? "the newest " + got + (got == 1 ? " photo" : " photos")
                        + (got < count ? " (" + count + " asked; the card held no more)" : "")
                        : "photos taken since " + stamp("yyyy-MM-dd HH:mm", since)).append("\nFiles:\n");
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(dest, "w")) {
            if (pfd == null) {
                throw new Plain("Android gave the app nowhere to write the zip");
            }
            try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fos, 64 * 1024))) {
                zip.setMethod(ZipOutputStream.DEFLATED);
                zip.setLevel(Deflater.BEST_SPEED);      // JPEG and AVI are compressed already
                for (int i = 0; i < chosen.size(); i++) {
                    Media m = chosen.get(i);
                    a.progress = "Copying " + (i + 1) + " of " + chosen.size() + "…";
                    postArchive();
                    // The name is chosen inside, once the file's start (and its capture time) has been read.
                    Copied cp = copyInto(zip, m, "", dateNames, fix, tz, lower);
                    String entry = cp.entry;
                    written.put(entry, new long[]{cp.crc, cp.length});
                    sourceOf.put(entry, m);
                    boolean whole = m.size >= 0 && cp.read == m.size;
                    if (!whole) {
                        shortRead++;
                    } else if (cp.camera) {
                        removable.add(entry);
                    }
                    if (m.size < 0) {
                        unsized++;      // never whole: it stays, and the outcome says the card gave no size
                    }
                    // Only a whole first copy's file can be deleted, so only it needs its original kept beside it.
                    if (wipe && cp.fixed && whole) {
                        // The card file is about to be deleted: the zip keeps it as the camera wrote it too, so a
                        // copy with rebuilt dates is never the only one left. It is read back like every entry, and
                        // keeps the file's name on the card (made unique in lower), whatever the copy is named.
                        Copied orig = copyInto(zip, m, ORIGINALS, false, false, tz, lower);
                        String original = orig.entry;
                        written.put(original, new long[]{orig.crc, orig.length});
                        originalOf.put(entry, original);
                        originals++;
                        bytes += orig.length;
                        if (orig.read != m.size) {
                            // The second read came out short: the card file stays, and the zip's original of it is
                            // not called the file as the camera wrote it.
                            removable.remove(entry);
                            shortOriginals++;
                        }
                    }
                    if (cp.fixed) {
                        fixed++;
                    }
                    if (m.video) {
                        videos++;
                    } else {
                        photos++;
                    }
                    bytes += cp.length;
                    if (manifest != null) {
                        manifest.append(entry).append("  ").append(cp.read).append(" bytes  ")
                                .append(m.mtime > 0 ? stamp("yyyy-MM-dd HH:mm:ss", m.mtime) : "no file time")
                                .append('\n');
                    }
                }
                if (manifest != null) {
                    byte[] text = manifest.toString().getBytes(StandardCharsets.UTF_8);
                    zip.putNextEntry(new ZipEntry("manifest.txt"));
                    zip.write(text);
                    zip.closeEntry();
                    CRC32 crc = new CRC32();
                    crc.update(text, 0, text.length);
                    written.put("manifest.txt", new long[]{crc.getValue(), text.length});
                }
                a.progress = "Saving the zip…";
                postArchive();
                zip.finish();
                zip.flush();
                fos.getFD().sync();     // the zip is on the phone's storage before it is read back
            }
        } catch (IOException | RuntimeException e) {
            String failed = delete(dest);
            return "The zip was not finished: " + sentence(cause(e, "a file could not be read from the card, or the"
                    + " zip could not be written. The cable may have come loose, or the phone may be full"))
                    + (failed == null ? " The unfinished zip was deleted." : " Delete the unfinished zip yourself.")
                    + " Nothing was deleted from the camera." + detail(detailOf(e));
        }
        a.progress = "Reading the zip back to check it…";
        postArchive();
        Set<String> verified = new HashSet<>();
        String verifyProblem = null;
        try {
            verifyZip(dest, written, verified);
            if (verified.size() != written.size()) {
                verifyProblem = (written.size() - verified.size()) + " of " + written.size() + " files read back"
                        + " differently or not at all";
            }
        } catch (IOException | RuntimeException e) {
            verifyProblem = "the zip could not be read back" + (why(e).isEmpty() ? "" : ": " + why(e));
        }
        String name = displayName(dest);
        String what = shots ? photos + (photos == 1 ? " test shot" : " test shots") + " and manifest.txt"
                : photos + (photos == 1 ? " photo, " : " photos, ") + videos + (videos == 1 ? " video" : " videos");
        if (verifyProblem != null) {
            return "Don't rely on the zip " + name + ": " + sentence(verifyProblem) + " Nothing was deleted from the"
                    + " camera. Delete the zip and try again. Saving it somewhere else may help.";
        }
        // Read back and matched: nothing is left for a restart to call unfinished (the wipe below comes after this).
        forgetRunningZip(app);
        // "Every file matched" only when every copy is also as long as the card lists it; otherwise the lines below
        // say which are not, and the backup does not count as done (backupSaved).
        boolean allWhole = shortRead == 0 && shortOriginals == 0;
        StringBuilder out = new StringBuilder("Saved " + what + " (" + megabytes(bytes) + ") as " + name
                + (allWhole ? ". Verified: the zip was read back and every file matched."
                : ". The zip was read back and matches what the app copied, but not every copy is whole."));
        if (fixed > 0) {
            out.append("\nDates fixed in ").append(fixed).append(fixed == 1 ? " copy." : " copies.");
        }
        int wholeOriginals = originals - shortOriginals;
        if (wholeOriginals > 0 && wholeOriginals == fixed) {
            out.append(" The zip also holds ").append(wholeOriginals == 1 ? "that file" : "those files")
                    .append(" as the camera wrote ").append(wholeOriginals == 1 ? "it" : "them").append(", in ")
                    .append(ORIGINALS).append(".");
        } else if (wholeOriginals > 0) {
            out.append(" The zip also holds ").append(wholeOriginals).append(" of them as the camera wrote them, in ")
                    .append(ORIGINALS).append(".");
        }
        if (shortOriginals > 0) {
            out.append("\n").append(shortOriginals).append(shortOriginals == 1 ? " copy" : " copies").append(" in ")
                    .append(ORIGINALS).append(DIFFERENT_SIZE).append(" the file, so ")
                    .append(shortOriginals == 1 ? "it is" : "they are").append(" not the file as the camera wrote it.")
                    .append(" The card ").append(shortOriginals == 1 ? "file stays" : "files stay")
                    .append(" on the camera.");
        }
        int shortSized = shortRead - unsized;
        if (shortSized > 0) {
            out.append("\n").append(shortSized).append(shortSized == 1 ? " file" : " files")
                    .append(DIFFERENT_SIZE).append(shortSized == 1 ? " it, so it stays" : " them, so they stay")
                    .append(" on the camera.");
        }
        if (unsized > 0) {
            out.append("\n").append(unsized).append(unsized == 1 ? " file has" : " files have")
                    .append(NO_SIZE).append(", so the app can't check that the copy is whole, and ")
                    .append(unsized == 1 ? "it stays" : "they stay").append(" on the camera.");
        }
        // What may go: a whole copy of the camera's own file that read back the same, and its original too when the
        // zip holds one (tested on the JVM: ZipCheckTest).
        Set<String> deletable = ZipCheck.deletable(removable, verified, originalOf);
        if (wipe) {
            out.append("\n").append(wipeVerified(sourceOf, deletable, verified));
        } else {
            out.append("\nThe photos are still on the camera.");
        }
        appendLog(app, FlashLog.Event.BACKUP_SAVED, null, null, (shots ? "test shots, " + lightingNote(manifestHead)
                + ", " : "") + what + " in " + name);
        if (!shots) {
            backupSaved = allWhole;     // a copy that is not whole keeps the step open, its warnings in sight
        }
        return out.toString();
    }

    /** How the outcome says a copy is not whole: read a different size than listed, or listed with no size at all. */
    private static final String DIFFERENT_SIZE = " read a different size than the card lists";
    private static final String NO_SIZE = " no size in the card's listing";

    /** The lines of a zip's outcome that say a copy is not whole, for its folded step. */
    private static List<String> copyWarnings(String outcome) {
        List<String> out = new ArrayList<>();
        for (String line : outcome.split("\n")) {
            if (line.contains(DIFFERENT_SIZE) || line.contains(NO_SIZE)) {
                out.add(line);
            }
        }
        return out;
    }

    private static String lightingNote(String manifestHead) {
        int i = manifestHead == null ? -1 : manifestHead.indexOf("Lighting: ");
        if (i < 0) {
            return "lighting not named";
        }
        int end = manifestHead.indexOf('\n', i);
        return manifestHead.substring(i + "Lighting: ".length(), end < 0 ? manifestHead.length() : end);
    }

    /**
     * Copies one card file into its own zip entry, its dates fixed when asked; never changes the card file. The entry
     * is named by DateFix.entryName: prefix (ORIGINALS for an original beside its copy, else ""), then the card path,
     * or with dateName the capture time in the same folder, made unique in lower ("-2" / "_2"...).
     */
    private Copied copyInto(ZipOutputStream zip, Media m, String prefix, boolean dateName, boolean fix, TimeZone tz,
                            Set<String> lower) throws IOException {
        CRC32 crc = new CRC32();
        Copied c = new Copied();
        try (InputStream in = getContentResolver().openInputStream(m.uri)) {
            if (in == null) {
                throw new Plain("Android gave no data for " + m.path + " on the card");
            }
            byte[] head = readUpTo(in, m.video ? AVI_HEAD : MAX_PHOTO + 1);
            String stem = dateName ? DateFix.captureName(head, m.video, m.mtime, tz) : null;
            String entry = DateFix.entryName(m.path, stem, prefix, lower);
            c.entry = entry;
            ZipEntry ze = new ZipEntry(entry);
            if (m.mtime > 0) {
                ze.setTime(m.mtime);
            }
            zip.putNextEntry(ze);
            c.read = head.length;
            byte[] first = head;
            if (m.video) {
                // The camera's own video only: an AVI with the camera's fixed 2010-06-29 in its IDIT or ICRD.
                c.camera = DateFix.isCharmeraAvi(head);
                if (fix) {
                    DateFix.Result r = DateFix.fixAvi(head, m.mtime, tz);
                    if (r.changed && r.data.length == head.length) {
                        first = r.data;
                        c.fixed = true;
                    }
                }
            } else if (head.length <= MAX_PHOTO) {
                c.camera = DateFix.isCharmeraJpeg(head);
                if (fix) {
                    DateFix.Result r = DateFix.fixJpeg(head, m.mtime, tz);
                    if (r.changed) {
                        first = r.data;
                        c.fixed = true;
                    }
                }
            }
            zip.write(first, 0, first.length);
            crc.update(first, 0, first.length);
            c.length = first.length;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                zip.write(buf, 0, n);
                crc.update(buf, 0, n);
                c.read += n;
                c.length += n;
            }
        }
        zip.closeEntry();
        c.crc = crc.getValue();
        return c;
    }

    /** Reads the saved zip back; each entry whose CRC-32 and length are what was written goes into verified. */
    private void verifyZip(Uri dest, Map<String, long[]> written, Set<String> verified) throws IOException {
        InputStream raw = getContentResolver().openInputStream(dest);
        if (raw == null) {
            throw new Plain("Android gave no data for the zip");
        }
        verified.addAll(ZipCheck.verify(raw, written));     // tested on the JVM: ZipCheckTest
    }

    /**
     * Deletes from the card exactly the files whose zip entry read back the same and that the camera made, one file
     * at a time through delete(); folders and every other file stay. Worker only. Returns what it did, in words.
     */
    private String wipeVerified(Map<String, Media> sourceOf, Set<String> removable, Set<String> verified) {
        int deleted = 0;
        int kept = 0;
        StringBuilder reasons = new StringBuilder();
        // The card answering no and a delete that failed with an error (a drive pulled mid-wipe, say) are told apart.
        int refused = 0;
        int failed = 0;
        for (Map.Entry<String, Media> e : sourceOf.entrySet()) {
            if (!removable.contains(e.getKey()) || !verified.contains(e.getKey())) {
                kept++;
                continue;
            }
            String why = delete(e.getValue().uri);
            if (why == null) {
                deleted++;
            } else {
                if (CARD_REFUSED.equals(why)) {
                    refused++;
                } else {
                    failed++;
                }
                reasons.append(reasons.length() == 0 ? "" : "; ").append(e.getValue().path).append(": ").append(why);
            }
        }
        StringBuilder sb = new StringBuilder("Deleted " + deleted + " from the camera.");
        if (kept > 0) {
            sb.append(" Kept ").append(kept).append(kept == 1 ? " file" : " files").append(" this camera did not"
                    + " make, or that did not read back whole.");
        }
        if (refused > 0) {
            sb.append(" Could not delete ").append(refused).append(": the card refused. ").append(refused == 1
                    ? "It is" : "They are").append(" in the zip and still on the camera.");
        }
        if (failed > 0) {
            // The delete threw: the app cannot tell what the card holds now, so it says only what it knows.
            sb.append(" Could not delete ").append(failed).append(refused > 0 ? " more" : "").append(": the delete"
                    + " failed with an error (the drive may have been unplugged). ").append(failed == 1 ? "It is"
                    : "They are").append(" in the zip, and may still be on the camera.");
        }
        if (refused + failed > 0) {
            sb.append(detail(reasons.toString()));
        }
        return sb.toString();
    }

    /** The line of a backup's outcome that says what was deleted from the camera (or that nothing was), or null. */
    private static String deleteLine(String outcome) {
        for (String line : outcome.split("\n")) {
            if (line.startsWith("Deleted ") || line.startsWith("The photos are still on the camera.")) {
                return line;
            }
        }
        return null;
    }

    /** The save screen's empty zip is deleted; says so, or that the owner should. */
    private String emptyZip(Uri dest) {
        return delete(dest) == null ? " The empty zip the save screen made was deleted."
                : " Delete the empty zip the save screen made.";
    }

    /** The DCIM photos of this test-shot set, oldest first: the newest count, or those taken since the set began. */
    private static List<Media> newestShots(List<Media> all, int count, long since, Set<String> before) {
        List<Media> photos = new ArrayList<>();
        for (Media m : all) {
            if (!m.video && m.inDcim()) {
                photos.add(m);
            }
        }
        Collections.sort(photos, (a, b) -> a.mtime != b.mtime ? Long.compare(b.mtime, a.mtime)
                : b.path.compareToIgnoreCase(a.path));
        List<Media> out = new ArrayList<>();
        for (Media m : photos) {
            if (count > 0 ? out.size() < count : before != null ? !before.contains(m.docId) : m.mtime >= since) {
                out.add(m);
            }
        }
        Collections.reverse(out);
        return out;
    }

    /**
     * Flash check: the newest DCIM photo taken since the write, and whether its header is the one the public build's
     * family writes. It cannot see the picture, nor tell the stock restore file, the probe and the bring-up apart.
     */
    private void checkPhoto() {
        final Card c = card;
        if (busy() || archiving() || dialog != null || checkingPhoto || !readable(c)) {
            return;
        }
        // The Repair run's write: when it was, and the DCIM photos listed just after it (only that write's listing).
        final String uuid = treeUuid();
        final FlashFlow run = runOf(uuid, FlashFlow.Route.REPAIR);
        final String id = runId(uuid, run);
        final long since = run.writtenAt();
        final Set<String> before = id != null && id.equals(dcimAtWriteFor) ? dcimAtWrite : null;
        final String sha = run.past() ? run.sha() : null;
        checkingPhoto = true;
        photoCheck = null;
        setStatus(checkStatus, "Looking for the newest photo…");
        updateButtons();
        final Uri t = c.tree;
        final boolean fileOnCard = !c.state.updateFiles.isEmpty();
        final Context app = getApplicationContext();
        WORKER.execute(() -> {
            String text;
            Boolean verdict = null;     // none: no new photo yet, or the card could not be read
            try {
                List<Media> all = listMedia(t, false);
                Media newest = null;
                for (Media m : all) {
                    if (m.video || !m.inDcim() || (before != null ? before.contains(m.docId)
                            : since > 0 && m.mtime < since)) {
                        continue;
                    }
                    if (newest == null || m.mtime > newest.mtime
                            || m.mtime == newest.mtime && m.path.compareToIgnoreCase(newest.path) > 0) {
                        newest = m;
                    }
                }
                if (newest == null) {
                    text = fileOnCard ? "No new photo yet. The update file is still on the card, so the camera writes"
                            + " it again instead of starting. Tap " + q(REMOVE) + " first."
                            : "No photo taken since the write is on the card. Take one photo, switch the camera off,"
                            + " reconnect, then tap " + q(CHECK_PHOTO) + " again.";
                } else {
                    byte[] head;
                    try (InputStream in = getContentResolver().openInputStream(newest.uri)) {
                        if (in == null) {
                            throw new Plain("Android gave no data for " + newest.path);
                        }
                        head = readUpTo(in, PhotoId.HEADER_LIMIT + 16);
                    }
                    PhotoId.Result r = PhotoId.identify(head);
                    String which = newest.path + (newest.mtime > 0 ? ", taken " + FlashLog.when(newest.mtime,
                            TimeZone.getDefault()) : "");
                    String how = before != null ? "" : since > 0 ? "\nThe app picked it by its time, which comes from"
                            + " the camera's clock." : "\nThe app does not know when the file was written (it was"
                            + " restarted since), so this may be a photo from before the write.";
                    String fw = sha == null ? null : firmwareLabel(sha);
                    Candidates.Kind written = Candidates.repairVersion(sha);
                    // any reviewed build by its base and mods; else by the fixed kind
                    Recipe.Selection made = Recipe.selectionOf(sha);
                    boolean factory = made != null ? Recipe.V012.equals(made.base)
                            : written != null && written.base == Candidates.Base.FACTORY;
                    PhotoId.Kind expected = made != null ? Recipe.expectedPhoto(made) : PhotoId.expectedFor(written);
                    verdict = r.kind == expected;
                    if (expected == PhotoId.Kind.FACTORY_EXIF && r.kind == expected) {
                        // The EXIF dates mod: the factory header with its own EXIF block (Make Kodak, Model Charmera).
                        boolean boot = made != null && made.has("v012/boot-last-mode");
                        boolean flash = made != null && made.has("v012/capture-flash");
                        text = "New photo found, with the EXIF dates header ✓\n" + which
                                + ". Open it and check the picture."
                                + (boot || flash ? " Every build with EXIF dates writes this header, so check the other"
                                + " mods on the camera itself." : "")
                                + (boot ? " Power-on goes straight into the mode you last used (photo the first"
                                + " time)." : "")
                                + (flash ? " The screen flashes black when you take a photo." : "") + how;
                        appendLog(app, FlashLog.Event.CHECK_OK, fw, sha, "factory EXIF header: " + newest.path);
                    } else if (expected == PhotoId.Kind.FACTORY_EXIF) {
                        String got = r.kind == PhotoId.Kind.ORIGINAL_BARE ? "the factory header without EXIF"
                                : r.kind == PhotoId.Kind.PUBLIC_FAMILY ? "the public build's header"
                                : r.reasons.isEmpty() ? "a header the app does not know"
                                : android.text.TextUtils.join("; ", r.reasons);
                        text = "This photo has " + got + ", not the EXIF dates header"
                                + " ✗\n" + which + ". The write didn't take. Write the file again and wait for"
                                + " \"Upgrade 100%\"." + how;
                        appendLog(app, FlashLog.Event.CHECK_FAILED, fw, sha, newest.path + ": " + got);
                    } else if (factory && r.kind == PhotoId.Kind.ORIGINAL_BARE) {
                        // A v012 build without EXIF dates writes the factory build's bare header.
                        text = "New photo found, with the factory firmware's header \u2713\n" + which + ". Open it and"
                                + " check the picture." + how;
                        appendLog(app, FlashLog.Event.CHECK_OK, fw, sha, "factory header: " + newest.path);
                    } else if (factory && r.kind == PhotoId.Kind.PUBLIC_FAMILY) {
                        text = "This photo has the public build's header, not the factory firmware's \u2717\n" + which
                                + ". The write didn't take. Write the file again and wait for \"Upgrade 100%\"." + how;
                        appendLog(app, FlashLog.Event.CHECK_FAILED, fw, sha, newest.path + ": public-build header");
                    } else if (r.kind == PhotoId.Kind.FACTORY_EXIF) {
                        // The EXIF mod's header after a write of a firmware without it: it still runs a build with it.
                        text = "This photo has the EXIF header of a build with EXIF dates, not the written"
                                + " firmware's ✗\n" + which + ". The write didn't take. Write the file"
                                + " again and wait for \"Upgrade 100%\"." + how;
                        appendLog(app, FlashLog.Event.CHECK_FAILED, fw, sha, newest.path + ": factory EXIF header");
                    } else if (r.kind == PhotoId.Kind.PUBLIC_FAMILY) {
                        text = "New photo found, written by this firmware family ✓\n" + which + ". Open it and check"
                                + " the picture." + how;
                        // The header is the same for the stock restore file, the probe and the bring-up: the log
                        // names no firmware for it, only the family.
                        appendLog(app, FlashLog.Event.CHECK_OK, null, sha, "public-build family header: "
                                + newest.path);
                    } else if (r.kind == PhotoId.Kind.ORIGINAL_BARE) {
                        text = "This photo has the original firmware's header ✗\n" + which + ". The write didn't"
                                + " take. Write the file again and wait for \"Upgrade 100%\"." + how;
                        appendLog(app, FlashLog.Event.CHECK_FAILED, fw, sha, newest.path + ": original header");
                    } else {
                        String why = r.reasons.isEmpty() ? "its header is not one the app knows"
                                : android.text.TextUtils.join("; ", r.reasons);
                        text = "This photo's header is not this firmware family's ✗\n" + which + ": " + sentence(why)
                                + " It may not be a photo this camera took." + how;
                        appendLog(app, FlashLog.Event.CHECK_FAILED, fw, sha, newest.path + ": " + why);
                    }
                }
            } catch (IOException | RuntimeException e) {
                text = "Could not read the card: " + sentence(cause(e, "the drive may have come loose")) + " Connect it"
                        + " again, then tap " + q(CHECK_PHOTO) + "." + detail(detailOf(e));
            }
            photoCheck = text;
            photoCheckFor = id;
            photoCheckOk = verdict;
            checkingPhoto = false;
            ui.post(() -> {
                if (gone()) {
                    MainActivity s = screen;      // this screen has gone: the one open now shows it
                    if (s != null && s != this && !s.gone()) {
                        s.photoShown();
                    }
                    return;
                }
                photoShown();
            });
        });
    }

    /**
     * Identify: lists the card's DCIM photos and videos, reads the start of at most Identify.SCAN_CAP photos (header
     * only), and shows what they say (Identify). Reads only: it writes nothing to the card and touches no flash run.
     */
    private void identifyFirmware() {
        final Card c = card;
        if (busy() || archiving() || dialog != null || identifying || !readable(c)) {
            return;
        }
        identifying = true;
        identifyText = null;
        identifyEvidence = null;
        setStatus(identifyStatus, "Reading the photos on the card…");
        identifyDetail.setText("");
        identifyDetailToggle.setVisibility(View.GONE);
        identifyNextButton.setVisibility(View.GONE);
        updateButtons();
        final Uri t = c.tree;
        final String uuid = treeUuid();
        WORKER.execute(() -> {
            String text;
            String evidence = "";
            Identify.Base base = null;      // stays null when the read fails or finds no base: an old answer goes
            try {
                List<Identify.Entry> entries = new ArrayList<>();
                for (Media m : listMedia(t, false)) {
                    final Uri u = m.uri;
                    final String path = m.path;
                    entries.add(new Identify.Entry(m.path, m.mtime, m.video, () -> {
                        try (InputStream in = getContentResolver().openInputStream(u)) {
                            if (in == null) {
                                throw new Plain("Android gave no data for " + path);
                            }
                            return readUpTo(in, PhotoId.HEADER_LIMIT + 16);
                        }
                    }));
                }
                Identify.Result r = Identify.identify(entries, TimeZone.getDefault());
                StringBuilder sb = new StringBuilder(r.conclusion);
                if (r.base != Identify.Base.UNKNOWN) {
                    sb.append("\nConfidence: ").append(r.confidence.name().toLowerCase(Locale.ROOT)).append(".");
                }
                for (String caveat : r.caveats) {
                    sb.append("\n\u2022 ").append(caveat);
                }
                text = sb.toString();
                StringBuilder ev = new StringBuilder();
                for (String line : r.evidence) {
                    ev.append(ev.length() == 0 ? "" : "\n").append(line);
                }
                evidence = ev.toString();
                if (r.base != Identify.Base.UNKNOWN) {
                    base = r.base;
                }
            } catch (IOException | RuntimeException e) {
                text = "Could not read the card: " + sentence(cause(e, "the drive may have come loose")) + " Connect it"
                        + " again, then tap " + q(IDENTIFY_BUTTON) + "." + detail(detailOf(e));
            }
            identifyText = text;
            identifyEvidence = evidence;
            identifiedBase = base;
            identifyFor = uuid;
            identifying = false;
            ui.post(() -> {
                if (gone()) {
                    MainActivity s = screen;      // this screen has gone: the one open now shows it
                    if (s != null && s != this && !s.gone()) {
                        if (s.followIdentify() && s.route != Route.IDENTIFY) {
                            s.render();
                        }
                        s.showIdentify();
                        s.updateButtons();
                    }
                    return;
                }
                if (followIdentify() && route != Route.IDENTIFY) {
                    render();
                }
                showIdentify();
                reveal(identifyStatus);
                updateButtons();
            });
        });
    }

    /** Puts the last identification on screen when it was read from the drive saved now; otherwise clears it. */
    private void showIdentify() {
        if (identifying) {
            return;     // "Reading..." stays until the result comes
        }
        String text = identifyText;
        boolean mine = text != null && Objects.equals(identifyFor, treeUuid());
        setStatus(identifyStatus, mine ? text : "");
        String ev = mine ? identifyEvidence : null;
        identifyDetail.setText(ev == null ? "" : ev);
        identifyDetailToggle.setVisibility(ev == null || ev.isEmpty() ? View.GONE : View.VISIBLE);
        identifyNextButton.setVisibility(identified() != null ? View.VISIBLE : View.GONE);
    }

    /**
     * Shows the flash check's last result, and the log it added to, when it was made for the Repair run's write now; a
     * verdict (the family's header or another one) goes into that run once (FlashFlow.checked).
     */
    private void photoShown() {
        String p = photoCheck;
        if (p == null || checkingPhoto) {
            return;
        }
        String uuid = treeUuid();
        FlashFlow f = runOf(uuid, FlashFlow.Route.REPAIR);
        String id = runId(uuid, f);
        if (id == null || !id.equals(photoCheckFor)) {
            return;     // a check of another write, or of another drive's card
        }
        Boolean verdict = photoCheckOk;
        if (verdict != null) {
            photoCheckOk = null;
            f.checked(verdict, p);
            keep(uuid, f);
        }
        boolean fresh = !p.equals(checkStatus.getText().toString());
        setStatus(checkStatus, p);
        flashLog = readLog();
        if (fresh) {
            reveal(checkStatus);
        }
        updateButtons();
    }

    // --- the step component -----------------------------------------------------------------------------------------

    private interface Check {
        boolean holds();
    }

    private interface Text {
        String get();
    }

    /**
     * One numbered step of a route: badge, title, one short line, its action, the action's result right under it, and
     * "More info" bullets. Done: a ✓ and the title with a one-line result, tap to open again. Locked: the title dimmed
     * with "Do step N first", N the first open step before it. Otherwise it is open.
     */
    private final class Step {
        final String number;
        final LinearLayout box;
        final LinearLayout head;
        final TextView badge;
        final android.graphics.drawable.GradientDrawable badgeFill;
        final TextView title;
        final TextView summary;
        final TextView lockNote;
        final LinearLayout body;
        final List<TextView> results = new ArrayList<>();
        /**
         * A flashing route's step: done, open or locked as the route's flash run says (FlashFlow.view), and done, ready
         * and needs are not used. Null on the other routes.
         */
        FlashFlow.Step kind;
        Check done;
        /** When it holds, the step is open even though a step it needs is not done. */
        Check ready;
        Step[] needs = new Step[0];
        /** The folded done state's line, when the first line of the latest result would leave out what matters. */
        Text summaryText;
        boolean isDone;
        /** A done step the owner opened again. */
        boolean open;
        /** Its state has been applied once, so a later change to done is news (and scrolls). */
        boolean shown;

        Step(String number, String titleText, String line) {
            this.number = number;
            box = card();
            head = new LinearLayout(MainActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            badge = badge(number);
            badgeFill = (android.graphics.drawable.GradientDrawable) badge.getBackground();
            head.addView(badge);
            title = new TextView(MainActivity.this);
            title.setText(titleText);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.setOnClickListener(v -> {
                if (isDone) {
                    open = !open;
                    apply(true, null);
                }
            });
            box.addView(head);
            summary = body("");
            summary.setTextColor(textSecondary);
            summary.setTextIsSelectable(false);
            summary.setVisibility(View.GONE);
            box.addView(summary);
            lockNote = body("");
            lockNote.setTextColor(disabledText);
            lockNote.setTextIsSelectable(false);
            lockNote.setVisibility(View.GONE);
            box.addView(lockNote);
            body = new LinearLayout(MainActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            box.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (line != null) {
                TextView l = body(line);
                l.setPadding(0, dp(8), 0, dp(2));
                body.addView(l);
            }
        }

        void add(View v) {
            attach(body, v);
        }

        /** A status view: an action's result, placed right under it. */
        void result(final TextView v) {
            attach(body, v);
            results.add(v);
            if (v.getTag(R.id.collapse_empty) == null) {
                // An empty result takes no room: no blank gap under a button before anything has run.
                v.setTag(R.id.collapse_empty, Boolean.TRUE);
                v.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence t, int a, int b, int c) {
                    }

                    @Override
                    public void onTextChanged(CharSequence t, int a, int b, int c) {
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable e) {
                        v.setVisibility(e.length() == 0 || (v == cardStatus && driveAway()) ? View.GONE
                                : View.VISIBLE);
                    }
                });
            }
            v.setVisibility(v.length() == 0 || (v == cardStatus && driveAway()) ? View.GONE : View.VISIBLE);
        }

        /** The "More info" toggle with up to five short bullets. */
        void more(String... bullets) {
            StringBuilder sb = new StringBuilder();
            for (String b : bullets) {
                sb.append(sb.length() == 0 ? "" : "\n").append("• ").append(b);
            }
            expander(body, "More info", sb.toString());
        }

        /** The first line of its latest result (the last one with text), for the collapsed done state. */
        String resultLine() {
            for (int i = results.size() - 1; i >= 0; i--) {
                String t = results.get(i).getText().toString().trim();
                if (!t.isEmpty()) {
                    int nl = t.indexOf('\n');
                    return nl < 0 ? t : t.substring(0, nl);
                }
            }
            return "Done.";
        }

        /** Done; or locked, with lock its note ("Do step N first."); or open when lock is null. */
        void apply(boolean nowDone, String lock) {
            isDone = nowDone;
            if (nowDone) {
                badge.setText("✓");
                badgeFill.setColor(okColor);
                badge.setTextColor(onAccent);
                title.setTextColor(textPrimary);
                lockNote.setVisibility(View.GONE);
                summary.setText(summaryText != null ? summaryText.get() : resultLine());
                summary.setVisibility(open ? View.GONE : View.VISIBLE);
                body.setVisibility(open ? View.VISIBLE : View.GONE);
            } else if (lock != null) {
                open = false;
                badge.setText(number);
                badgeFill.setColor(disabledFill);
                badge.setTextColor(disabledText);
                title.setTextColor(disabledText);
                summary.setVisibility(View.GONE);
                lockNote.setText(lock);
                lockNote.setVisibility(View.VISIBLE);
                body.setVisibility(View.GONE);
            } else {
                open = false;
                badge.setText(number);
                badgeFill.setColor(accent);
                badge.setTextColor(onAccent);
                title.setTextColor(textPrimary);
                summary.setVisibility(View.GONE);
                lockNote.setVisibility(View.GONE);
                body.setVisibility(View.VISIBLE);
            }
        }
    }

    private Step step(String number, String title, String line) {
        Step s = new Step(number, title, line);
        steps.add(s);
        return s;
    }

    /**
     * Applies each step's state; a step that has just become done scrolls itself and the next one into view. A flashing
     * route's steps are as its flash run says (FlashFlow.view: done, open or locked, the note naming the first open
     * step before it), never by whether the drive is plugged in now. The other routes' steps go by their done, ready
     * and needs, and their notes name the first open step before them too.
     */
    private void refreshSteps() {
        FlashFlow f = flow();
        FlashFlow.View v = f == null ? null : f.view(live());
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            boolean d;
            String lock = null;
            if (s.kind != null && v != null) {
                d = v.done(s.kind);
                lock = v.lockReason(s.kind);
            } else {
                d = s.done != null && s.done.holds();
                if (!d && (s.ready == null || !s.ready.holds())) {
                    for (Step n : s.needs) {
                        if (!n.isDone) {
                            lock = "Do step " + firstOpenBefore(i, n).number + " first.";
                            break;
                        }
                    }
                }
            }
            boolean news = s.shown && d && !s.isDone;
            s.apply(d, lock);
            s.shown = true;
            if (news && uiReady) {
                reveal(s.summary);
            }
        }
    }

    /** The first step before index i that is open as this refresh applied it; fallback when none is. */
    private Step firstOpenBefore(int i, Step fallback) {
        for (int j = 0; j < i; j++) {
            Step e = steps.get(j);
            if (!e.isDone && e.lockNote.getVisibility() != View.VISIBLE) {
                return e;
            }
        }
        return fallback;
    }

    /** The step whose card holds this view, or null. */
    private Step stepOf(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            for (Step s : steps) {
                if (s.box == p) {
                    return s;
                }
            }
        }
        return null;
    }

    /** The view's top within the scroll column, or -1 when it is not in it. */
    private int offsetInColumn(View v) {
        int y = 0;
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == col) {
                return y;
            }
            y += p.getTop();
        }
        return -1;
    }

    /**
     * Scrolls so a fresh result is on screen together with the head of the next step, so the owner sees what happened
     * and what comes next without hunting. Posted, since the result has no size until it is laid out.
     */
    private void reveal(final View v) {
        if (v == null) {
            return;
        }
        v.post(() -> {
            if (gone() || scroll == null || !v.isShown()) {
                return;
            }
            int top = offsetInColumn(v);
            if (top < 0) {
                return;
            }
            int bottom = top + v.getHeight();
            Step s = stepOf(v);
            int i = s == null ? -1 : steps.indexOf(s);
            if (i >= 0 && i + 1 < steps.size()) {
                Step next = steps.get(i + 1);
                bottom = Math.max(bottom, offsetInColumn(next.box) + next.head.getHeight() + dp(24));
            }
            int h = scroll.getHeight();
            int y = scroll.getScrollY();
            int pad = dp(12);
            int target;
            if (bottom - top + 2 * pad > h) {
                target = top - pad;
            } else if (bottom + pad > y + h) {
                target = bottom + pad - h;
            } else if (top - pad < y) {
                target = top - pad;
            } else {
                return;
            }
            scroll.smoothScrollTo(0, Math.max(0, target));
        });
    }

    /** Pick the card-layout colours from the phone's light/dark mode and the theme's accent. */
    private void resolveTheme() {
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        // Fixed, accessible colours rather than the device theme's (which can be low-contrast on some skins). The
        // accent/onAccent pair is chosen so button labels and link text both clear 4.5:1 in each mode: a darker accent
        // with white text in light mode, a lighter accent with near-black text in dark mode. Every text colour below
        // is set explicitly so nothing falls back to the theme default (the old dark-mode readability bug).
        if (night) {
            accent = 0xFF5AA0F5;
            onAccent = 0xFF0A0A0A;
            warnAccent = 0xFFF0B429;
            windowBg = 0xFF121212;
            cardFill = 0xFF1E1E1E;
            cardStroke = 0xFF2E2E2E;
            disabledFill = 0xFF333333;
            disabledText = 0xFF8A8A8A;
            ripple = 0x40FFFFFF;
            cautionFill = 0xFF2A2109;
            cautionStroke = 0xFF4A3A12;
            okColor = 0xFF6FD08C;
            badColor = 0xFFE57373;
            textPrimary = 0xFFEDEDED;
            textSecondary = 0xFFAEAEAE;
        } else {
            accent = 0xFF1D6FD6;
            onAccent = 0xFFFFFFFF;
            warnAccent = 0xFF8A5300;
            windowBg = 0xFFF3F3F4;
            cardFill = 0xFFFFFFFF;
            cardStroke = 0xFFE3E3E6;
            disabledFill = 0xFFDCDCE0;
            disabledText = 0xFF767676;
            ripple = 0x22000000;
            cautionFill = 0xFFFFF7E6;
            cautionStroke = 0xFFF0DFA8;
            okColor = 0xFF2E7D32;
            badColor = 0xFFC62828;
            textPrimary = 0xFF1A1A1A;
            textSecondary = 0xFF5B5B5B;
        }
    }

    /** A rounded surface card, added to the main column, that step content goes into. */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(cardFill);
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(1)), cardStroke);
        c.setBackground(bg);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        col.addView(c);
        return c;
    }

    /** The round step number at the head of a step card. */
    private TextView badge(String n) {
        TextView b = new TextView(this);
        b.setText(n);
        b.setTextColor(onAccent);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(accent);
        b.setBackground(bg);
        int s = dp(28);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    /** A filled accent button for a step's main action; it dims when disabled, which a bare drawable would not. */
    private Button primary(String label, View.OnClickListener onClick) {
        Button b = button(label, onClick);
        int[][] states = {{-android.R.attr.state_enabled}, {}};
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setColor(new android.content.res.ColorStateList(states, new int[]{disabledFill, accent}));
        fill.setCornerRadius(dp(10));
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ripple), fill, null));
        b.setTextColor(new android.content.res.ColorStateList(states, new int[]{disabledText, onAccent}));
        b.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        int py = dp(10);
        b.setPadding(0, py, 0, py);
        return b;
    }

    /** An amber alert box for a destructive-step warning: a tinted, rounded surface with bold text. */
    private View caution(String text) {
        TextView t = body(text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(cautionFill);
        bg.setCornerRadius(dp(10));
        bg.setStroke(Math.max(1, dp(1)), cautionStroke);
        t.setBackground(bg);
        int px = dp(12);
        t.setPadding(px, px, px, px);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    /** A tappable label that shows or hides a block of detail, collapsed to begin with. */
    private void expander(LinearLayout parent, String label, String detail) {
        final TextView detailView = body(detail);
        detailView.setVisibility(View.GONE);
        final TextView toggle = new TextView(this);
        toggle.setText("▸ " + label);
        toggle.setTextColor(accent);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        toggle.setPadding(0, dp(10), 0, dp(4));
        toggle.setOnClickListener(v -> {
            boolean show = detailView.getVisibility() == View.GONE;
            detailView.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText((show ? "▾ " : "▸ ") + label);
        });
        parent.addView(toggle);
        parent.addView(detailView);
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(textPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setTextIsSelectable(true);
        return t;
    }

    /** A check box for a step's option, in the screen's own colours. */
    private android.widget.CheckBox toggle(String label, boolean on) {
        android.widget.CheckBox b = new android.widget.CheckBox(this);
        b.setText(label);
        b.setChecked(on);
        b.setTextColor(textPrimary);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        b.setPadding(b.getPaddingLeft(), dp(6), 0, dp(6));
        return b;
    }

    /** A one-line text field with a hint, in the screen's own colours. */
    private android.widget.EditText field(String hint, int inputType) {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextColor(textPrimary);
        e.setHintTextColor(textSecondary);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private TextView mono() {
        TextView t = body("");
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return t;
    }

    /**
     * A secondary button: outlined in the accent, accent text, in the screen's own colours (never the device theme's,
     * which is light on Android 7 to 9 even in dark mode). primary() fills it.
     */
    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        int[][] states = {{-android.R.attr.state_enabled}, {}};
        android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable();
        outline.setColor(android.graphics.Color.TRANSPARENT);
        outline.setCornerRadius(dp(10));
        outline.setStroke(Math.max(1, dp(1)), new android.content.res.ColorStateList(states,
                new int[]{disabledFill, accent}));
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(10));
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ripple), outline, mask));
        b.setStateListAnimator(null);       // no shadow under a see-through button
        b.setTextColor(new android.content.res.ColorStateList(states, new int[]{disabledText, accent}));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        int py = dp(10);
        b.setPadding(dp(8), py, dp(8), py);
        return b;
    }

    /** A "label" toggle (collapsed to begin with) that shows or hides an existing, dynamically updated view. */
    private TextView detailToggle(final View target, final String label) {
        final TextView toggle = new TextView(this);
        toggle.setTextColor(accent);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        toggle.setPadding(0, dp(8), 0, dp(4));
        toggle.setText("\u25b8 " + label);
        toggle.setOnClickListener(v -> {
            boolean show = target.getVisibility() == View.GONE;
            target.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText((show ? "\u25be " : "\u25b8 ") + label);
        });
        return toggle;
    }

    /** A tappable accent label that opens a URL in the browser, so the raw address need not clutter the screen. */
    private TextView linkText(String label, final String url) {
        TextView t = body(label);
        t.setTextColor(accent);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextIsSelectable(false);
        t.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException e) {
                // no browser installed; nothing to open
            }
        });
        return t;
    }

    /** A tappable accent label that runs an action in the app. */
    private TextView link(String label, View.OnClickListener onClick) {
        TextView t = body(label);
        t.setTextColor(accent);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextIsSelectable(false);
        t.setOnClickListener(onClick);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** The app's version name from the manifest, for the footer; empty if it cannot be read. */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private int installedVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Download the published APK, compare its version with this one, and offer to install it when it is newer. */
    private void checkForUpdate() {
        if (busy() || dialog != null || checkingUpdate) {
            return;
        }
        checkingUpdate = true;
        setStatus(updateStatus, "Checking for a newer version\u2026");
        NET.execute(() -> {
            String problem = null;
            int remoteCode = -1;
            String remoteName = null;
            boolean noRelease = false;
            try {
                byte[] bytes = fetch(UPDATE_APK_URL);
                File f = new File(getCacheDir(), ApkProvider.NAME);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
                android.content.pm.PackageInfo info = getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
                if (info == null) {
                    f.delete();
                    problem = "the download is not a valid app file";
                } else {
                    remoteCode = info.versionCode;
                    remoteName = info.versionName;
                }
            } catch (Plain e) {
                if (e.code == 404) {
                    noRelease = true;   // the release, or its APK, is not there: nothing has been published yet
                } else {
                    problem = cause(e, "the phone could not reach GitHub, or the download stopped");
                }
            } catch (IOException e) {
                problem = cause(e, "the phone could not reach GitHub, or the download stopped");
            } catch (RuntimeException e) {
                problem = cause(e, "the download failed");
            }
            final String p = problem;
            final int rc = remoteCode;
            final String rn = remoteName;
            final boolean nr = noRelease;
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                checkingUpdate = false;
                if (nr) {
                    setStatus(updateStatus, "No app update has been published yet, so there's nothing to install. Once"
                            + " a release is published, this button finds it.");
                    return;
                }
                if (p != null) {
                    setStatus(updateStatus, "Could not check for updates: " + sentence(p) + " Open the releases page to"
                            + " check by hand.");
                    return;
                }
                if (rc > installedVersionCode()) {
                    confirmInstall(rn);
                } else {
                    setStatus(updateStatus, "You're on the latest version (" + appVersion() + "). The published release"
                            + " is " + rn + ".");
                    new File(getCacheDir(), ApkProvider.NAME).delete();
                }
            });
        });
    }

    /** Offer to install the update that checkForUpdate downloaded. */
    private void confirmInstall(String remoteName) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("Version " + remoteName + " is available (you have " + appVersion() + ") and already"
                        + " downloaded. Tap Install update. Android will ask you to confirm.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Install update", (di, w) -> installUpdate())
                .create();
        d.setOnDismissListener(di -> {
            if (dialog == di) {
                dialog = null;
            }
            if (!gone()) {
                afterDialog();
            }
        });
        dialog = d;
        d.show();
        updateButtons();
    }

    /** Hand the downloaded APK to Android's installer, first asking to allow app installs when that is not granted. */
    private void installUpdate() {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("Allow updates")
                    .setMessage("To install updates, allow this app to install apps. Android opens its settings."
                            + " Turn it on, come back, and tap Check for app updates again.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (di, w) -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            setStatus(updateStatus, "Could not open the setting. Turn on \"install unknown apps\" for"
                                    + " this app in Android settings, then tap Check for app updates again.");
                        }
                    })
                    .create();
            d.setOnDismissListener(di -> {
                if (dialog == di) {
                    dialog = null;
                }
                if (!gone()) {
                    afterDialog();
                }
            });
            dialog = d;
            d.show();
            updateButtons();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(ApkProvider.uri(), ApkProvider.MIME);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            setStatus(updateStatus, "No installer is available. Open the releases page to install by hand.");
        }
    }

    private void updateButtons() {
        boolean idle = !busy() && dialog == null;
        // The gate (WriteGate): a file that passed, checked against the backup on record now, with no backup being read
        // and no check still running. A flash from a copy saved to the phone is a flash too, so the copy is gated the
        // same way.
        boolean fileOk = gate(fileCheck, fileData) == null;
        // A listing of a drive that is not mounted now (ejected, or pulled out) no longer says what it holds.
        boolean attached = mounted(volume(treeUuid()));
        boolean cardOk = card != null && card.state.writable() && attached;
        writeButton.setEnabled(idle && fileOk && cardOk);
        cleanButton.setEnabled(idle && attached && card != null && card.state.listed
                && !card.state.updateFiles.isEmpty());
        downloadButton.setEnabled(idle && !downloading);
        probeButton.setEnabled(idle && !downloading);
        repairButton.setEnabled(idle && !downloading);
        // The base radios and mod boxes are off while a file is being made, checked or written, so a pick never meets
        // work in flight.
        boolean pickable = idle && !downloading && !checking;
        baseChoice.setEnabled(pickable);
        for (int i = 0; i < baseChoice.getChildCount(); i++) {
            baseChoice.getChildAt(i).setEnabled(pickable);     // a RadioGroup doesn't pass enabled on to its radios
        }
        baseChoice.setAlpha(pickable ? 1f : 0.45f);   // the radios' fixed tint doesn't look off when they are
        for (int i = 0; i < modBoxes.getChildCount(); i++) {
            View box = modBoxes.getChildAt(i);
            if (box instanceof android.widget.CheckBox) {
                box.setEnabled(pickable && box.getTag() == null);   // tag: the mod it conflicts with
            }
        }
        factoryFileButton.setEnabled(idle);
        readResultButton.setEnabled(idle && !readingSensor && attached && card != null && card.state.listed);
        backupButton.setEnabled(idle && !loadingBackup);
        saveButton.setEnabled(idle && fileOk);
        // Another drive picked during a write would show the write's result under the wrong drive.
        chooseButton.setEnabled(!busy());
        // Ejecting while a write or a removal runs would cut it short.
        ejectButton.setEnabled(!busy());
        // Each flashing route writes only its own kind of file (go() drops another route's file).
        if (!fitsRoute(fileCheck)) {
            writeButton.setEnabled(false);
        }
        driveChange.setEnabled(!busy());
        useFileButton.setEnabled(idle);
        // A zip reads the card and may delete photos from it: nothing else touches the card or the drive meanwhile.
        boolean zipping = archiving();
        if (zipping) {
            writeButton.setEnabled(false);
            cleanButton.setEnabled(false);
            readResultButton.setEnabled(false);
            chooseButton.setEnabled(false);
            ejectButton.setEnabled(false);
            driveChange.setEnabled(false);
        }
        removeEjectButton.setEnabled(ejectButton.isEnabled());
        // The Remove step's own Eject once it holds a removal's result; the line under the flash question is the run's.
        FlashFlow f = flow();
        removeEjectButton.setVisibility(f != null && f.removalShown() ? View.VISIBLE : View.GONE);
        if (flashAnswerView != null) {
            setStatus(flashAnswerView, f == null ? "" : f.answerLine(live()));
        }
        showFlashAgain(f);
        // Before the answer a listing found the written file gone: the write step, open again, says so.
        String gone = f == null || busy() ? "" : f.goneLine();
        if (!gone.isEmpty() && !gone.contentEquals(writeStatus.getText())) {
            setStatus(writeStatus, gone);
        } else if (gone.isEmpty() && !busy() && writeStatus.getText().toString().startsWith(GONE_LINE)) {
            writeStatus.setText("");      // listed with the file again (or written again): that line no longer holds
        }
        // A zip may take minutes: the screen stays on while one runs.
        if (zipping) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        showDriveLine();      // the answer, a removal or a listing changes it while the drive is away
        boolean listed = readable(card) && attached;
        zipButton.setEnabled(idle && !zipping && listed);
        shotsButton.setEnabled(idle && !zipping && listed && shotsSince != 0);
        checkPhotoButton.setEnabled(idle && !zipping && !checkingPhoto && listed);
        identifyButton.setEnabled(idle && !zipping && !identifying && listed);
        // A zip's end on screen as the last refresh left it, with the drive listed now, has been seen.
        markSeen(backupArchive, zipStatus, listed);
        markSeen(shotsArchive, shotsStatus, listed);
        refreshSteps();
        // After the steps: the eject's result follows the states just applied.
        placeEjectStatus();
    }

    /** WriteGate's answer for this file and check, with the backup on record now: null when it may go to the card. */
    private String gate(UpdateCheck.Result check, byte[] data) {
        return WriteGate.refusal(check, data, backupSha256, loadingBackup, checking);
    }

    private void dismissDialog() {
        if (dialog != null) {
            AlertDialog d = dialog;
            dialog = null;
            d.dismiss();
        }
    }

    /** A write confirmation names the backup and the check it was opened with; once either changes, it goes. */
    private void dismissWriteDialog() {
        if (writeDialog != null && dialog == writeDialog) {
            dismissDialog();
        }
        writeDialog = null;
    }

    // --- wording ------------------------------------------------------------------------------------------------

    /** A button's label in quotes, as instructions name it. */
    private static String q(String label) {
        return "\"" + label + "\"";
    }

    /** Android's own words for a failure, for a "Detail:" line; empty when it gave none. Never a class name. */
    private static String why(Throwable e) {
        String m = e.getMessage();
        return m == null ? "" : m.trim();
    }

    /** The plain cause of a failure: the app's own words for a Plain one, otherwise the fallback given. */
    private static String cause(Throwable e, String fallback) {
        return e instanceof Plain ? e.getMessage() : fallback;
    }

    /** The technical part of a failure, for its "Detail:" line. */
    private static String detailOf(Throwable e) {
        return e instanceof Plain ? ((Plain) e).detail : why(e);
    }

    /** A labelled "Detail:" paragraph under a plain message, or nothing when there is no detail. */
    private static String detail(String d) {
        return d == null || d.trim().isEmpty() ? "" : "\n\nDetail: " + d.trim();
    }

    /** A clause made into a sentence: a full stop is added unless it already ends with one. */
    private static String sentence(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() || t.endsWith(".") || t.endsWith("?") || t.endsWith("!") ? t : t + ".";
    }

    /** A message from Candidates split into its plain words and the text of its last "Detail:" line ("" if none). */
    private static String[] split(String m) {
        String s = m == null ? "" : m;
        int i = s.indexOf("\nDetail:");
        return i < 0 ? new String[]{s.trim(), ""}
                : new String[]{s.substring(0, i).trim(), s.substring(i + "\nDetail:".length()).trim()};
    }

    /** A status with its first sentence (what happened) in bold, so it stands out above what to do next. */
    private static void setStatus(TextView v, String text) {
        if (text == null || text.isEmpty()) {
            v.setText("");
            return;
        }
        int end = text.indexOf('\n');
        int stop = text.indexOf(". ");
        if (stop >= 0 && (end < 0 || stop + 1 < end)) {
            end = stop + 1;
        }
        if (end < 0) {
            end = text.length();
        }
        SpannableString s = new SpannableString(text);
        s.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        v.setText(s);
    }

    /** Step 1's readout: the plain summary, and under it the technical part (hidden when there is none). */
    private void showFile(String summary, String technical) {
        checkShown = false;
        setStatus(fileStatus, summary == null ? "" : summary.trim());
        showDetail(technical);
        if (uiReady) {
            reveal(fileStatus);
        }
    }

    /** Fills the file step's detail block (hidden behind the Details toggle, collapsed on every new readout). */
    private void showDetail(String technical) {
        boolean none = technical == null || technical.trim().isEmpty();
        fileDetail.setText(none ? "" : technical.trim());
        fileDetail.setVisibility(View.GONE);            // new content starts collapsed, behind the Details toggle
        fileDetailToggle.setText("\u25b8 Details");
        fileDetailToggle.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    /** The "Detail:" block for the file step's readout, or null when there is no detail. */
    private static String detailBlock(String d) {
        return d == null || d.trim().isEmpty() ? null : "Detail: " + d.trim();
    }

    /** What to do once the file step has a file that passed. A file the owner chose may already be on the card. */
    private static String nextAfterFile(boolean chosen) {
        return chosen ? "Now connect the camera's drive and write it. If this copy is already on the card, tap "
                + q(WRITE) + " anyway. The app finds it there, writes nothing, and moves on to Eject."
                : "Now connect the camera's drive and write it.";
    }

    /**
     * For a file that passed but that the flashing route on screen does not write (each writes only its own kind):
     * where it is written instead; null when this route writes it, or the screen is not a flashing route.
     */
    private String writtenElsewhere(UpdateCheck.Result result) {
        if ((route != Route.REPAIR && route != Route.PROBE && route != Route.RESTORE) || fitsRoute(result)) {
            return null;
        }
        // Another flashing route drops this file (go()), so only Advanced writes this very copy.
        String again = isProbe(result) ? "or make the probe again on " + routeTitle(Route.PROBE)
                : isRepair(result) ? "or make it again on " + routeTitle(Route.REPAIR)
                : "or open " + routeTitle(Route.RESTORE) + " and choose it again with " + q(USE_FILE);
        return routeTitle(route) + " doesn't write this file. To write this copy, open Advanced, " + again + ".";
    }

    /** The withdrawn candidate (C, A or B) these bytes are, by SHA-256, or null. */
    private static Candidates.Kind withdrawnKind(String sha) {
        return Candidates.withdrawn(sha);
    }

    /** Whether a checked file is the sensor-ID probe, by its reviewed SHA-256: a file written without a backup. */
    private static boolean isProbe(UpdateCheck.Result result) {
        return result != null && Candidates.Kind.PROBE.matches(result.sha256);
    }

    /**
     * The base and mods a checked file the Repair route writes was made from (any pick of a base and mods),
     * by its reviewed SHA-256, or null.
     */
    private static Recipe.Selection repairOf(UpdateCheck.Result result) {
        return result == null || !Candidates.isRepairFile(result.sha256) ? null : Recipe.selectionOf(result.sha256);
    }

    /**
     * Whether a checked file is one the Repair route writes: any pick of a base and mods (Candidates.isRepairFile:
     * every reviewed build but the probe and the stock restore file).
     */
    private static boolean isRepair(UpdateCheck.Result result) {
        return repairOf(result) != null;
    }

    /**
     * Whether a checked file is a reviewed file (the sensor-ID probe, any Repair file, the stock restore file: every
     * build of the recipe table): the files written without a backup.
     */
    private static boolean isReviewedPatch(UpdateCheck.Result result) {
        return result != null && Candidates.isReviewedPatch(result.sha256);
    }

    /**
     * The warning for writing a reviewed file when nothing confirms what this camera runs (Recipe.unconfirmed): no
     * backup on record and no Identify answer for the same sensor. Null when something does, or for another file.
     */
    private String unconfirmed(UpdateCheck.Result result) {
        if (!isReviewedPatch(result)) {
            return null;
        }
        Identify.Base f = identified();
        return Recipe.unconfirmed(Recipe.selectionOf(result.sha256), backupSha256 != null,
                f == Identify.Base.V012 ? "v012" : f == Identify.Base.V006_FAMILY ? "v006-family" : null);
    }

    /**
     * The one sensor warning the file step and the write dialog show for a reviewed file, or null: the check's own
     * "Wrong sensor?" line when the chip backup on record is firmware for the other sensor (UpdateCheck.wrongSensor),
     * else unconfirmed(). Without this the backup's warning, the stronger evidence, would sit only under Details.
     */
    private String sensorWarning(UpdateCheck.Result result) {
        if (!isReviewedPatch(result)) {
            return null;
        }
        String wrong = UpdateCheck.wrongSensor(result);
        return wrong != null ? wrong : unconfirmed(result);
    }

    /**
     * Whether a checked file is an unknown file (not reviewed) that was checked with no backup and goes to the card
     * with none on record: the file step and the confirmation then say so loudly (UNKNOWN_FILE).
     */
    private boolean unknownNoBackup(UpdateCheck.Result result) {
        return result != null && !isReviewedPatch(result) && !result.compared && result.referenceSha256 == null
                && backupSha256 == null;
    }

    /** How many WARN lines a checked file produced, for the file step's one-line status. */
    private static int warnCount(UpdateCheck.Result result) {
        int n = 0;
        for (UpdateCheck.Line l : result.lines) {
            if (l.level == UpdateCheck.Level.WARN) {
                n++;
            }
        }
        return n;
    }

    /** The first sentence of a message: up to and including the first full stop that ends a sentence. */
    private static String firstSentence(String s) {
        String t = s == null ? "" : s.trim();
        int i = t.indexOf(". ");
        return i < 0 ? t : t.substring(0, i + 1);
    }

    /** True for a SHA-256 in hex; sha256Of answers with the reason instead when the file could not be read. */
    private static boolean isSha(String s) {
        return s != null && s.matches("[0-9a-f]{64}");
    }

    // --- the backup of this camera ------------------------------------------------------------------------------

    private void pickBackup() {
        if (busy() || dialog != null || loadingBackup) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, PICK_BACKUP);
        } catch (ActivityNotFoundException | SecurityException e) {
            setStatus(backupStatus, "Android couldn't open its file picker, so no backup can be chosen. Without one, the"
                    + " app can't compare the update file with this camera's firmware." + detail(why(e)));
        }
    }

    /**
     * Read the chosen dump and take it as this camera's backup only if UpdateCheck.backupProblem finds nothing wrong
     * with it or with its name: a read the toolkit kept as unverified is not a backup.
     */
    private void loadBackup(Uri uri) {
        loadingBackup = true;
        setStatus(backupStatus, "Reading the backup…");
        updateButtons();
        WORKER.execute(() -> {
            String name = displayName(uri);
            byte[] data = null;
            String sha = null;
            String text;
            try {
                data = readAll(getContentResolver().openInputStream(uri));
                sha = UpdateCheck.sha256Hex(data, 0, data.length);
                String problem = UpdateCheck.backupProblem(name, data, Candidates.PUBLIC_DUMP_SHA256S);
                if (problem != null) {
                    boolean isPublic = false;
                    for (String s : Candidates.PUBLIC_DUMP_SHA256S) {
                        isPublic |= s.equals(sha);
                    }
                    data = null;
                    sha = null;
                    text = "Not taken as a backup: " + sentence(problem) + (isPublic
                            ? " If it's a read of this camera, keep it. It's the way back to what the camera runs now."
                            : " The app needs the 2 MB file the toolkit's backup command read from this camera's flash"
                            + " chip. Tap " + q(BACKUP) + " to choose it.") + "\n"
                            + "File: " + name + ".";
                } else {
                    StringBuilder notes = new StringBuilder();
                    for (String note : UpdateCheck.backupNotes(data, UpdateCheck.PUBLIC_BUILD)) {
                        notes.append('\n').append(note);
                    }
                    text = "Backup chosen: " + name + ", " + UpdateCheck.bytes(data.length) + "." + notes;
                }
            } catch (IOException | RuntimeException e) {
                data = null;
                sha = null;
                text = "The backup could not be read: " + sentence(cause(e, "the file could not be opened. It may have"
                        + " been moved, or the app that holds it did not hand it over")) + " Tap " + q(BACKUP)
                        + " to choose it again." + detail(detailOf(e));
            }
            final byte[] d = data;
            final String h = sha;
            final String t = text;
            final String n = name;
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                String before = backupSha256;
                loadingBackup = false;
                boolean reread = rereadingBackup;
                rereadingBackup = false;
                if (d == null && before != null) {
                    // A failed choice never drops the backup already on record: without it, an update file that
                    // failed against this camera's own firmware would be checked again with nothing to compare.
                    setStatus(backupStatus, t + "\nThe app still uses " + backupName + ".");
                    updateButtons();
                    return;
                }
                backup = d;
                backupSha256 = h;
                backupName = d == null ? null : n;
                backupUri = d == null ? null : uri;
                setStatus(backupStatus, d == null && reread ? "⚠ The backup you chose before couldn't be read"
                        + " again, so no backup is chosen now and the update file isn't compared with one. Tap "
                        + q(BACKUP) + " to choose it again.\n" + t : t);
                backupDetail.setText(h == null ? "" : "SHA-256 " + h);
                backupDetail.setVisibility(View.GONE);
                backupDetailToggle.setText("\u25b8 Details");
                backupDetailToggle.setVisibility(h == null ? View.GONE : View.VISIBLE);
                if (!Objects.equals(before, h)) {
                    dismissWriteDialog();
                    // Restore: a write checked against another backup, not yet flashed, no longer counts.
                    FlashFlow restore = runOf(treeUuid(), FlashFlow.Route.RESTORE);
                    restore.backupChanged(h);
                    keep(treeUuid(), restore);
                }
                outdated(h);
                recheckFile();
                updateButtons();
            });
        });
    }

    /**
     * Once the backup on record is no longer the one the file on the card, or the copy saved to the phone, was checked
     * against, their "Done" and "Saved" no longer hold: they are replaced with what to do about that file. A screen
     * recreated meanwhile reads the same backup again, and nothing changes.
     */
    private void outdated(String now) {
        String written = writeStatus.getText().toString();
        if (writtenWith != null && !writtenWith.equals(now)
                && (written.startsWith(WRITTEN) || written.startsWith(NOTHING_TO_WRITE))) {
            // A file checked alone was never compared with the backup: only its sensor line read it. It may now be
            // "Wrong sensor?", so the owner is sent to look, not told to remove a file that is still what it was.
            setStatus(writeStatus, Candidates.checkedAlone(writtenSha) ? "The chip backup has changed since the update"
                    + " file was written to the card. The file isn't compared with a backup, but its sensor warning"
                    + " goes by the backup chosen. Look at the file step before the card goes back into the camera."
                    : "The update file on the card was checked against the backup chosen before, "
                    + writtenName + ", and the backup has changed since. Unless the file step shows the file ready"
                    + " again, tap " + q(REMOVE) + " before the card goes back into the camera.");
            writtenWith = null;
        }
        String saved = saveStatus.getText().toString();
        if (savedWith != null && !savedWith.equals(now) && saved.startsWith(SAVED)) {
            setStatus(saveStatus, Candidates.checkedAlone(savedSha) ? "The chip backup has changed since this copy was"
                    + " saved. The file isn't compared with a backup, but its sensor warning goes by the backup"
                    + " chosen. Look at the file step before you put the copy on a card."
                    : "The copy saved to the phone was checked against the backup chosen before, "
                    + savedName + ", and the backup has changed since. Don't put it on the card unless the file"
                    + " step shows the file ready again.");
            savedWith = null;
        }
    }

    /**
     * Checks the file in the file step again, against the backup on record now, whenever its check was made against another
     * one (or none). Until the new check is in, nothing is written or saved (WriteGate), and the file step says so.
     */
    private void recheckFile() {
        final byte[] data = fileData;
        final String name = fileName;
        if (data == null || name == null) {
            checking = false;
            return;
        }
        // A file checked alone (the probe, the OV9732 builds: Candidates.checkedAlone) is never compared with a backup
        // (finding 7), but its sensor line says which sensor the backup on record drives, and its check names that
        // backup (referenceSha256): so it's checked again through Candidates.check, like any file, when the backup
        // changes, and still never compared with it.
        if (!checking && fileCheck != null && Objects.equals(fileCheck.referenceSha256, backupSha256)) {
            return;   // already checked against this backup
        }
        dismissWriteDialog();
        final int request = fileRequest;
        final int check = ++checkRequest;
        final byte[] ref = backup;
        final String origin = fileOrigin;
        final boolean chosen = fileChosen;
        checking = true;
        fileCheck = null;
        showFile("Checking the update file again with the backup now chosen…", null);
        updateButtons();
        WORKER.execute(() -> {
            final UpdateCheck.Result result = Candidates.check(name, data, ref);
            ui.post(() -> {
                if (gone() || request != fileRequest || check != checkRequest || fileData != data) {
                    return;
                }
                checking = false;
                if (!Objects.equals(result.referenceSha256, backupSha256)) {
                    recheckFile();   // the backup changed again meanwhile
                    return;
                }
                dismissWriteDialog();
                fileCheck = result;
                followLoadedRepair(result);     // as applyCheck does: a load that came this way is the pick too
                showChecked(result, origin, chosen);
                updateButtons();
            });
        });
    }

    /**
     * Applies a check made on the worker to the file step, unless it was made against another backup than the one on record
     * now: then the file is kept and checked again, and the old check is never shown or used.
     */
    private void applyCheck(byte[] data, UpdateCheck.Result result, String name, String origin, boolean chosen) {
        fileData = data;
        fileName = name;
        fileOrigin = origin;
        fileChosen = chosen;
        // A check made with another backup than the one on record now is never applied. That holds for the files checked
        // alone too (Candidates.checkedAlone): they're never compared with a backup (finding 7), but their sensor line
        // says which sensor the backup drives, so a check made with another one is checked again.
        if (!Objects.equals(result.referenceSha256, backupSha256)) {
            fileCheck = null;
            recheckFile();
            return;
        }
        fileCheck = result;
        followLoadedRepair(result);
        showChecked(result, origin, chosen);
    }

    /**
     * The Repair route writes any of its builds, so a Repair file loaded for it that is not the build picked becomes
     * the pick (and is remembered): the name in bold, the base radios, the mod boxes, the check line and the file written
     * then always name the same build.
     */
    private void followLoadedRepair(UpdateCheck.Result result) {
        Recipe.Selection k = repairOf(result);
        if (fileFor == Route.REPAIR && k != null && k.equals(repairSel)) {
            rememberRepair();       // a made or loaded build of the default pick is the owner's pick too
        }
        if (fileFor != Route.REPAIR || k == null || k.equals(repairSel)) {
            return;
        }
        repairSel = k;
        rememberRepair();
        freshRunIfStale(FlashFlow.Route.REPAIR);
        if (route == Route.REPAIR) {
            render();
        }
    }

    /** Step 1's readout for a file that was checked: the verdict, where the file came from, and what to do next. */
    private void showChecked(UpdateCheck.Result result, String origin, boolean chosen) {
        fileStatus.setText(checkLine(result, chosen));
        checkShown = true;
        reveal(fileStatus);
        String older = Recipe.olderNameNote(result.sha256);
        showDetail(result.verdict() + "\n\n" + origin + (older == null ? "" : "\n\n" + older) + "\n\nDetail: "
                + UpdateCheck.bytes(result.size) + ", SHA-256 " + result.sha256 + ".\n\nThe app's checks"
                + (result.compared ? ", compared with " + backupName : " (not compared with a backup)") + ":\n\n"
                + result.report());
    }

    /** The file step's verdict and next line for a checked file, for the route on screen. */
    private SpannableString checkLine(UpdateCheck.Result result, boolean chosen) {
        String state;
        int color;
        String next;
        if (!result.ok) {
            state = "\u2717 Not OK to put on the card.";
            color = badColor;
            next = "Tap Details to see what failed.";
        } else if (isProbe(result)) {
            state = "\u2713 Sensor-ID probe ready. No backup needed.";
            color = okColor;
            next = "Now connect the camera's drive and tap " + q(WRITE) + ".";
        } else if (isReviewedPatch(result)) {
            String unsure = sensorWarning(result);
            int warns = warnCount(result);
            Recipe.Selection sel = Recipe.selectionOf(result.sha256);
            state = "\u2713 " + (sel != null ? cap(sel.label()) + " is ready." : "Known file ready.")
                    + (warns == 0 ? "" : " " + warns + (warns == 1 ? " warning" : " warnings") + " (see Details).");
            color = unsure != null || warns > 0 ? warnAccent : okColor;
            next = (unsure == null ? "" : "\u26a0 " + unsure + "\n") + "Now connect the camera's drive and tap "
                    + q(WRITE) + ".";
        } else if (unknownNoBackup(result)) {
            int warns = warnCount(result);
            state = "\u26a0 Unknown file." + (warns == 0 ? ""
                    : " " + warns + (warns == 1 ? " warning" : " warnings") + " (see Details).");
            color = warnAccent;
            next = UNKNOWN_FILE + "\n" + nextAfterFile(chosen);
        } else if (!result.compared) {
            state = "\u26a0 Not compared with the backup chosen now.";
            color = warnAccent;
            next = "The app compares it again before it will write it. Tap Details to see why.";
        } else {
            int warns = warnCount(result);
            state = "\u2713 OK to put on the card." + (warns == 0 ? ""
                    : " " + warns + (warns == 1 ? " warning" : " warnings") + " (see Details).");
            color = okColor;
            next = nextAfterFile(chosen);
        }
        String elsewhere = result.ok ? writtenElsewhere(result) : null;
        if (elsewhere != null) {
            next = elsewhere;     // Write stays off here, so "tap Write" would lead nowhere
        }
        SpannableString line = new SpannableString(state + "\n" + next);
        line.setSpan(new StyleSpan(Typeface.BOLD), 0, state.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new android.text.style.ForegroundColorSpan(color), 0, state.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return line;
    }

    // --- the update file ----------------------------------------------------------------------------------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, PICK_FILE);
        } catch (ActivityNotFoundException | SecurityException e) {
            showFile("Android could not open its file picker. Open the file in a file manager app instead, tap \"Open"
                    + " with\" or \"Share\", and choose " + getString(R.string.app_name) + ".", detailBlock(why(e)));
        }
    }

    /** The file and check the save was started for; the copy written is exactly these bytes. */
    private byte[] saveData;
    private UpdateCheck.Result saveCheck;

    /** Save the checked update file where the owner chooses, for copying to a card the app cannot reach. */
    private void saveCopy() {
        if (busy() || dialog != null || gate(fileCheck, fileData) != null) {
            return;
        }
        saveData = fileData;
        saveCheck = fileCheck;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, fileCheck.cardName);
        try {
            startActivityForResult(i, SAVE_COPY);
        } catch (ActivityNotFoundException | SecurityException e) {
            setStatus(saveStatus, "No copy was saved: Android could not open its screen for saving a file. Use the"
                    + " camera's USB-C cable or a USB card reader instead." + detail(why(e)));
        }
    }

    private void writeCopy(Uri uri) {
        final byte[] data = saveData;
        final UpdateCheck.Result check = saveCheck;
        saveData = null;
        saveCheck = null;
        // Checked again against the backup on record now: it may have changed while the save screen was open. A check
        // of another file in the file step does not concern these bytes; a changed backup shows in its SHA-256.
        final String refusal = data == null ? "the app was restarted while the save screen was open (a dark-mode, font"
                + " or language change can do that), so it no longer had the file to save"
                : WriteGate.refusal(check, data, backupSha256, loadingBackup, false);
        if (refusal != null) {
            // The save screen has already made an empty file under the update file's name: it must not stay behind.
            setStatus(saveStatus, "Not saving the copy. Removing the empty file the save screen made…");
            WORKER.execute(() -> {
                String name = displayName(uri);
                String failed = delete(uri);
                final String tt = "No copy was saved: " + sentence(refusal) + (failed == null
                        ? " The empty file the save screen made was deleted."
                        : " The save screen made an empty file, " + name + ", and the app couldn't delete it. Delete"
                        + " it yourself so it never goes on a card.") + " Then tap " + q(SAVE) + " again."
                        + detail(failed);
                ui.post(() -> {
                    if (gone()) {
                        return;
                    }
                    setStatus(saveStatus, tt);
                });
            });
            return;
        }
        setStatus(saveStatus, "Saving the copy…");
        final String with = check.referenceSha256;
        final String withName = backupName;
        WORKER.execute(() -> {
            String text;
            try {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    if (out == null) {
                        throw new Plain("Android gave the app nowhere to write the copy");
                    }
                    out.write(data);
                }
                String sha = sha256Of(uri);
                String name = displayName(uri);
                if (!isSha(sha)) {
                    text = "Do not use the saved copy: the app could not read it back to check it. Delete it, then tap "
                            + q(SAVE) + " again." + detail(sha);
                } else if (!check.sha256.equals(sha)) {
                    text = "Do not use the saved copy: it reads back differently from the update file. Delete it, then"
                            + " tap " + q(SAVE) + " again." + detail("SHA-256 of the copy " + sha + "; of the update"
                            + " file " + check.sha256 + ".");
                } else if (!check.cardName.equals(name)) {
                    text = "Saved, but under another name. Android named the copy \"" + name + "\". The camera only"
                            + " uses the update file under its exact name. Rename the copy to " + check.cardName
                            + " before you copy it to the card.";
                } else {
                    text = "Saved " + name + " on the phone and read it back. It matches the update file.\n"
                            + "Now copy it to the card's top folder with the Files app, not into DCIM. Then tap "
                            + q(USE_FILE) + " and choose the copy on the card, so the app checks it there.";
                }
            } catch (IOException | RuntimeException e) {
                text = "No copy was saved: " + sentence(cause(e, "Android could not write the file there")) + " If a"
                        + " file was left where you saved it, delete it. Then tap " + q(SAVE) + " to try again."
                        + detail(detailOf(e));
            }
            final String tt = text;
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                setStatus(saveStatus, tt);
                if (tt.startsWith(SAVED)) {
                    savedWith = with;
                    savedName = withName;
                    savedSha = check.sha256;
                    outdated(backupSha256);   // the backup may have changed while the copy was written
                }
            });
        });
    }

    /**
     * Download the public stock dump from its pinned upstream address, then make the chosen kind of file from it, for
     * the route forRoute. The file loaded before goes now, so the one being made is tagged for that route from here.
     */
    private void downloadDump(Recipe.Selection sel, Route forRoute) {
        dismissDialog();
        final int request = ++fileRequest;
        fileData = null;
        fileCheck = null;
        fileName = null;
        fileFor = forRoute;
        checking = false;          // a check of the earlier file still running is dropped (fileRequest)
        saveStatus.setText("");
        if (!busy()) {
            writeStatus.setText("");
        }
        if (stockDump != null) {               // already downloaded and checked: make the file again without a new download
            buildRestore(stockDump, request, "Used the v006 chip backup the app already has.", sel, forRoute);
            return;
        }
        showFile("Getting the v006 chip backup (2 MB, downloaded from GitHub the first time)…", null);
        downloading = true;
        updateButtons();
        NET.execute(() -> {
            byte[] dump = null;
            String problem = null;
            String technical = null;
            final String browser = " Or open the v006 chip backup's page above in the browser, download it there, and tap "
                    + q(USE_FILE) + ".";
            final String wrong = "The download arrived, but it is not the v006 chip backup, so the app will not use it.\n"
                    + "A Wi-Fi network that asks you to sign in can cause this, or a download that stopped part way."
                    + " Open a web page in the browser to check the connection. Then tap " + q(DOWNLOAD) + " again.";
            boolean kept = false;
            boolean keeping = false;
            try {
                dump = keptDump();             // the phone's own copy, checked again: no download needed
                kept = dump != null;
                if (dump == null) {
                    dump = Candidates.stockDump(fetch(Candidates.STOCK_DUMP_DOWNLOAD));
                    keeping = keepDump(dump);  // "keeps a copy" is said only when it did
                }
            } catch (IOException e) {
                if (GITHUB_REFUSED.equals(e.getMessage())) {
                    problem = "The v006 chip backup did not download: GitHub did not send the file. It may be busy.\n"
                            + "Wait a few minutes, then tap " + q(DOWNLOAD) + " again." + browser;
                    technical = detailOf(e);
                } else if (e instanceof Plain) {        // something arrived, but it cannot be the stock dump
                    problem = wrong;
                    technical = e.getMessage();
                } else {
                    problem = "The v006 chip backup did not download: the phone could not reach GitHub, or the connection"
                            + " stopped.\nCheck the connection, then tap " + q(DOWNLOAD) + " again." + browser;
                    technical = detailOf(e);
                }
            } catch (IllegalArgumentException e) {
                String[] parts = split(e.getMessage());   // what arrived is not the stock dump; Candidates says why
                problem = wrong;
                technical = parts[1].isEmpty() ? firstSentence(parts[0]) : parts[1];
            } catch (RuntimeException e) {
                problem = "The v006 chip backup did not download: the download stopped with an error.\nTap " + q(DOWNLOAD)
                        + " again." + browser;
                technical = why(e);
            }
            final byte[] d = dump;
            final String p = problem;
            final String x = technical;
            final boolean fromKept = kept;
            final boolean keptNow = keeping;
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                downloading = false;
                if (d != null) {
                    stockDump = d;
                }
                if (request != fileRequest) {
                    updateButtons();
                    return;
                }
                if (d == null) {
                    showFile(p, detailBlock(x));
                    updateButtons();
                    return;
                }
                buildRestore(d, request, fromKept ? "Used the v006 chip backup kept on this phone, checked again."
                        : "Downloaded the v006 chip backup from GitHub and checked it."
                        + (keptNow ? " The app keeps a copy on this phone." : ""), sel, forRoute);
            });
        });
    }

    /** Where the app keeps its own checked copy of the stock dump, so it never needs GitHub's copy again. */
    private static final String KEPT_DUMP = "stock-dump.bin";
    /** Where the app keeps the owner's factory dump once picked, so it is picked only once. Never leaves the phone. */
    private static final String KEPT_FACTORY = "factory-dump.bin";

    /** The kept factory dump if it is there and still exactly that dump; a damaged copy is thrown away. Worker thread. */
    private byte[] keptFactoryDump() {
        File f = new File(getFilesDir(), KEPT_FACTORY);
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = new java.io.FileInputStream(f)) {
            return Candidates.factoryDump(readAll(in));
        } catch (IOException | IllegalArgumentException e) {
            f.delete();                       // not the factory dump any more: the owner picks it again
            return null;
        }
    }

    /**
     * Make a v012 build from the factory dump: the copy in memory, or the one kept on the phone, read and checked
     * again on the worker. With none, the public cleaned copy is downloaded (downloadFactory). The file loaded before
     * goes now, so the one being made is tagged for forRoute from here, as downloadDump does.
     */
    private void makeFactory(Recipe.Selection sel, Route forRoute) {
        dismissDialog();
        final int request = ++fileRequest;
        fileData = null;
        fileCheck = null;
        fileName = null;
        fileFor = forRoute;
        checking = false;          // a check of the earlier file still running is dropped (fileRequest)
        saveStatus.setText("");
        if (!busy()) {
            writeStatus.setText("");
        }
        if (factoryDump != null) {
            buildRestore(factoryDump, request, "Used the factory firmware the app already has.", sel, forRoute);
            return;
        }
        showFile("Reading the copy of the factory firmware kept on this phone…", null);
        updateButtons();
        WORKER.execute(() -> {
            final byte[] d = keptFactoryDump();
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                if (d != null) {
                    factoryDump = d;
                }
                if (request != fileRequest) {
                    updateButtons();
                    return;
                }
                if (d == null) {
                    downloadFactory(sel, forRoute, request);   // none on the phone: the public cleaned copy
                    return;
                }
                buildRestore(d, request, "Used the copy of the factory firmware kept on this phone, checked again.", sel,
                        forRoute);
            });
        });
    }

    /**
     * With no factory dump on the phone: download the factory build v012's public cleaned copy, check it by SHA-256
     * (Candidates.factoryDump), keep it as the factory dump, and make the pick from it. The owner's own read, picked
     * with Use a file, is kept in its place and used first from then on.
     */
    private void downloadFactory(Recipe.Selection sel, Route forRoute, int request) {
        showFile("Downloading the public copy of factory build v012 (2 MB, once)…", null);
        downloading = true;
        updateButtons();
        NET.execute(() -> {
            byte[] dump = null;
            String problem = null;
            String technical = null;
            boolean keeping = false;
            final String again = " Then tap " + q(MAKE_FIRMWARE) + " again.";
            final String instead = " Or open " + Candidates.CLEAN_FACTORY_PAGE + " in the browser, download "
                    + Candidates.CLEAN_FACTORY_NAME + " there, and tap " + q(USE_FILE) + ". You can also choose your own"
                    + " chip backup of an OV9732 camera, " + Candidates.FACTORY_DUMP_NAME + ", the same way.";
            final String wrong = "The download arrived, but it isn't the factory firmware, so the app won't use it.\nA"
                    + " Wi-Fi network that asks you to sign in can cause this, or a download that stopped part way."
                    + again + instead;
            try {
                dump = Candidates.factoryDump(fetch(Candidates.CLEAN_FACTORY_DOWNLOAD));
                keeping = keepFactoryDump(dump);
            } catch (IOException e) {
                if (GITHUB_REFUSED.equals(e.getMessage())) {
                    problem = "The factory firmware didn't download: GitHub didn't send the file. It may be busy.\nWait a"
                            + " few minutes." + again + instead;
                } else if (e instanceof Plain) {
                    problem = wrong;
                } else {
                    problem = "The factory firmware didn't download: the phone couldn't reach GitHub, or the connection"
                            + " stopped.\nCheck the connection." + again + instead;
                }
                technical = detailOf(e);
            } catch (IllegalArgumentException e) {
                String[] parts = split(e.getMessage());
                problem = wrong;
                technical = parts[1].isEmpty() ? firstSentence(parts[0]) : parts[1];
            } catch (RuntimeException e) {
                problem = "The factory firmware didn't download: the download stopped with an error." + again + instead;
                technical = why(e);
            }
            final byte[] d = dump;
            final String p = problem;
            final String x = technical;
            final boolean keptNow = keeping;
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                downloading = false;
                if (d != null) {
                    factoryDump = d;
                }
                refreshRepairChoice();
                if (request != fileRequest) {
                    updateButtons();
                    return;
                }
                if (d == null) {
                    showFile(p, detailBlock(x));
                    updateButtons();
                    return;
                }
                buildRestore(d, request, "Downloaded the public copy of factory build v012 ("
                        + Candidates.CLEAN_FACTORY_NAME + ") from GitHub and checked it."
                        + (keptNow ? " The app keeps a copy on this phone." : ""), sel, forRoute);
            });
        });
    }

    /** Where the app keeps build v008_test's public file once downloaded and checked, so it downloads only once. */
    private static final String KEPT_V008 = "v008_test-dump.bin";

    /** The v008_test file as the recipes start from it, checked by its SHA-256 (Recipes.DUMPS); throws otherwise. */
    private static byte[] v008File(byte[] data) {
        String[] want = Recipe.download(Recipe.V008);
        String sha = UpdateCheck.sha256Hex(data, 0, data.length);
        if (want == null || !want[4].equals(sha)) {
            throw new IllegalArgumentException("the file is not build v008_test's public file, so the app will not use"
                    + " it.\nDetail: its SHA-256 is " + sha + "; the public file's is "
                    + (want == null ? "not listed" : want[4]) + ".");
        }
        return data;
    }

    /** The kept v008_test file if it is there and still exactly that file; a damaged copy is thrown away. */
    private byte[] keptV008() {
        File f = new File(getFilesDir(), KEPT_V008);
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = new java.io.FileInputStream(f)) {
            return v008File(readAll(in));
        } catch (IOException | IllegalArgumentException e) {
            f.delete();
            return null;
        }
    }

    /**
     * Make a v008_test pick: from the file already checked, the copy kept on the phone, or build v008_test's public
     * file, downloaded at a fixed commit (Recipes.DUMPS) and checked by SHA-256, as downloadDump does for v006. The
     * file loaded before goes now, so the one being made is tagged for forRoute from here.
     */
    private void downloadV008(Recipe.Selection sel, Route forRoute) {
        dismissDialog();
        final int request = ++fileRequest;
        fileData = null;
        fileCheck = null;
        fileName = null;
        fileFor = forRoute;
        checking = false;          // a check of the earlier file still running is dropped (fileRequest)
        saveStatus.setText("");
        if (!busy()) {
            writeStatus.setText("");
        }
        if (v008Dump != null) {
            buildRestore(v008Dump, request, "Used build v008_test's file the app already has.", sel, forRoute);
            return;
        }
        showFile("Getting build v008_test's public file (1.4 MB, downloaded from GitHub the first time)…", null);
        downloading = true;
        updateButtons();
        final String[] entry = Recipe.download(Recipe.V008);
        NET.execute(() -> {
            byte[] dump = null;
            String problem = null;
            String technical = null;
            boolean kept = false;
            boolean keeping = false;
            final String again = " Then tap " + q(MAKE_FIRMWARE) + " again.";
            try {
                dump = keptV008();
                kept = dump != null;
                if (dump == null) {
                    dump = v008File(fetch(entry[5]));
                    keeping = keepCopy(KEPT_V008, dump);
                }
            } catch (IOException e) {
                problem = GITHUB_REFUSED.equals(e.getMessage())
                        ? "Build v008_test's file did not download: GitHub did not send the file. It may be busy.\nWait"
                        + " a few minutes." + again
                        : e instanceof Plain ? "The download arrived, but it is not build v008_test's file, so the app"
                        + " will not use it.\nA Wi-Fi network that asks you to sign in can cause this." + again
                        : "Build v008_test's file did not download: the phone could not reach GitHub, or the connection"
                        + " stopped.\nCheck the connection." + again;
                technical = detailOf(e);
            } catch (IllegalArgumentException e) {
                String[] parts = split(e.getMessage());
                problem = "The download arrived, but it is not build v008_test's file, so the app will not use it.\nA"
                        + " Wi-Fi network that asks you to sign in can cause this, or a download that stopped part way."
                        + again;
                technical = parts[1].isEmpty() ? firstSentence(parts[0]) : parts[1];
            } catch (RuntimeException e) {
                problem = "Build v008_test's file did not download: the download stopped with an error." + again;
                technical = why(e);
            }
            final byte[] d = dump;
            final String p = problem;
            final String x = technical;
            final boolean fromKept = kept;
            final boolean keptNow = keeping;
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                downloading = false;
                if (d != null) {
                    v008Dump = d;
                }
                if (request != fileRequest) {
                    updateButtons();
                    return;
                }
                if (d == null) {
                    showFile(p, detailBlock(x));
                    updateButtons();
                    return;
                }
                buildRestore(d, request, fromKept ? "Used build v008_test's file kept on this phone, checked again."
                        : "Downloaded build v008_test's file from GitHub and checked it."
                        + (keptNow ? " A copy stays on this phone." : ""), sel, forRoute);
            });
        });
    }

    /** The kept copy if it is there and still the exact stock dump; a damaged copy is thrown away. Worker thread. */
    private byte[] keptDump() {
        File f = new File(getFilesDir(), KEPT_DUMP);
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = new java.io.FileInputStream(f)) {
            return Candidates.stockDump(readAll(in));
        } catch (IOException | IllegalArgumentException e) {
            f.delete();                       // not the stock dump any more: download it again
            return null;
        }
    }

    /**
     * Keeps a checked stock dump in the app's own storage: written to a temporary file of its own beside it (so two
     * threads keeping it at once never share one), synced, then renamed over it. True only once the rename worked.
     * Worker or network thread.
     */
    private boolean keepDump(byte[] dump) {
        return keepCopy(KEPT_DUMP, dump);
    }

    /** keepDump for the owner's factory dump (KEPT_FACTORY). */
    private boolean keepFactoryDump(byte[] dump) {
        return keepCopy(KEPT_FACTORY, dump);
    }

    /** Writes data to name in the app's own storage, as keepDump says. True only once the rename worked. */
    private boolean keepCopy(String name, byte[] data) {
        File f = new File(getFilesDir(), name);
        File part;
        try {
            part = File.createTempFile(name, ".part", getFilesDir());
        } catch (IOException | RuntimeException e) {
            return false;                     // not kept; the next run gets it again
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
            out.write(data);
            out.getFD().sync();
        } catch (IOException e) {
            part.delete();
            return false;
        }
        if (!part.renameTo(f)) {
            part.delete();
            return false;
        }
        return true;
    }

    private static byte[] fetch(String address) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new Plain(GITHUB_REFUSED, "GitHub answered " + code + ".", code);
            }
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** Make the chosen kind of update file from the dump. from says where the dump came from, for the file step's readout. */
    private void buildRestore(byte[] dump, int request, String from, Recipe.Selection sel, Route forRoute) {
        if (Recipe.V012.equals(sel.base)) {
            factoryDump = dump;
        } else if (Recipe.V008.equals(sel.base)) {
            v008Dump = dump;
        } else {
            stockDump = dump;
        }
        if (request != fileRequest) {
            return;
        }
        final byte[] ref = backup;
        showFile("Making the update file…", null);
        WORKER.execute(() -> {
            byte[] data = null;
            UpdateCheck.Result result = null;
            String problem = null;
            String technical = null;
            // A pick that is one of the fixed kinds (Recipe.LEGACY) is made by Candidates.build, any other by
            // Recipe.build. RecipeTest holds the two to the same bytes. Both refuse a result that is not the reviewed
            // file; its name is its builds row's.
            Candidates.Kind legacy = sel.kind();
            String made = legacy != null ? legacy.fileName : sel.label();
            // The restore file and the probe have their own screens and their own words. Every Repair pick is
            // described the same way, from the recipes, with its way back.
            final String about = legacy == Candidates.Kind.RESTORE || legacy == Candidates.Kind.PROBE ? legacy.label
                    : Recipe.describe(sel) + (sel.stock() ? "" : " " + wayBack(sel));
            try {
                data = legacy != null ? Candidates.build(dump, legacy) : Recipe.build(dump, sel);
                String sha = UpdateCheck.sha256Hex(data, 0, data.length);
                String listed = Recipe.fileNameFor(sha);
                made = listed != null ? listed : legacy != null ? legacy.fileNameFor(sha) : made;
                result = Candidates.check(made, data, ref);
            } catch (RuntimeException e) {
                data = null;
                result = null;
                String[] parts = split(why(e));
                problem = "Could not make the update file: " + sentence(parts[0].isEmpty() ? "it failed" : parts[0]);
                technical = detailBlock(parts[1]);
            }
            final byte[] dd = data;
            final UpdateCheck.Result r = result;
            final String pp = problem;
            final String xx = technical;
            final String mm = made;
            ui.post(() -> {
                if (gone() || request != fileRequest) {
                    return;
                }
                if (r == null) {
                    fileData = null;
                    fileCheck = null;
                    fileName = null;
                    showFile(pp, xx);
                } else {
                    // Checked against the backup on record when the download started; applyCheck checks it again if
                    // another backup has come in since.
                    fileFor = forRoute;
                    applyCheck(dd, r, mm, from + " Made " + mm + " from it. It matches the known file exactly. "
                            + about, false);
                }
                updateButtons();
            });
        });
    }

    /** "Open with" or "Share" from a file manager. */
    private void handleIncoming(Intent intent) {
        if (intent == null) {
            return;
        }
        Uri uri = null;
        try {
            if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                uri = intent.getData();
            } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
        } catch (RuntimeException e) {
            if (route != Route.ADVANCED) {
                go(Route.ADVANCED);
            }
            showFile("The app could not read the file the other app sent. Tap " + q(USE_FILE) + " and choose it there"
                    + " instead.", detailBlock(why(e)));
            return;
        }
        if (uri != null) {
            if (route != Route.ADVANCED) {
                go(Route.ADVANCED);
            }
            loadFile(uri, Route.ADVANCED);      // a file from another app is checked and written on the Advanced page
        }
    }

    /** Reads and checks a file the owner picked (or another app sent), for the route forRoute. */
    private void loadFile(Uri uri, Route forRoute) {
        // A confirmation for the previous file must not stay on screen once another file is loaded.
        dismissDialog();
        final int request = ++fileRequest;
        showFile("Reading the file and checking it…", null);
        fileData = null;
        fileCheck = null;
        fileName = null;
        fileFor = forRoute;        // the file before is gone; the one being read is for this route
        checking = false;          // a check of the earlier file still running is dropped (fileRequest)
        saveStatus.setText("");
        if (!busy()) {
            writeStatus.setText("");
        }
        updateButtons();
        final byte[] ref = backup;
        WORKER.execute(() -> {
            String name = displayName(uri);
            String summary;
            String technical = null;
            byte[] data = null;
            UpdateCheck.Result result = null;
            byte[] stock = null;
            byte[] factory = null;
            boolean factoryKept = false;
            String origin = null;
            boolean chosen = false;
            try {
                data = readAll(getContentResolver().openInputStream(uri));
                // The owner's own factory dump first, by its SHA-256: kept, and the factory firmware made from it.
                factory = Candidates.isFactoryDump(data) ? data : null;
                stock = factory == null && data.length == Candidates.DUMP_SIZE && Candidates.isStockDump(data)
                        ? Candidates.stockDump(data) : null;
                if (factory != null) {
                    factoryKept = keepFactoryDump(factory);
                    summary = "";
                } else if (stock != null) {
                    keepDump(stock);           // a stock dump picked by hand is kept too
                    summary = "";
                } else if (data.length == Candidates.DUMP_SIZE) {
                    String problem = UpdateCheck.backupProblem(name, data, Candidates.PUBLIC_DUMP_SHA256S);
                    if (problem == null) {
                        // Most likely the owner's own backup, picked in the wrong step.
                        summary = "\"" + name + "\" looks like a chip backup, a copy of a camera's flash chip. It isn't"
                                + " an update file. A chip backup never goes on a card as it is. If it's this camera's backup,"
                                + " choose it with " + q(BACKUP) + " and the app compares the update file with it. An"
                                + " update file is made from a chip backup with charmera pack on a computer.";
                    } else {
                        // Step 0 would refuse it too, so it is not pointed to: what is wrong with it is said instead.
                        summary = "\"" + name + "\" is a 2 MB chip backup, but the app doesn't make update files from"
                                + " it. It makes them only from charmera-v006-2025-10-14-clean.bin or the original"
                                + " charmera_stock_latest.bin, and from a factory chip backup. The app won't take"
                                + " it as this camera's backup either, because "
                                + sentence(problem) + "\nA chip backup never goes on a card as it is.";
                        technical = detailBlock("its SHA-256 is " + UpdateCheck.sha256Hex(data, 0, data.length)
                                + "; the public v006 chip backup's is " + Candidates.CLEAN_STOCK_SHA256 + " (the original's "
                                + Candidates.STOCK_DUMP_SHA256 + ").");
                    }
                } else {
                    result = Candidates.check(name, data, ref);
                    boolean renamed = result.cardName != null && !result.cardName.equals(name);
                    boolean restore = Candidates.Kind.RESTORE.matches(result.sha256);
                    Candidates.Kind withdrawn = withdrawnKind(result.sha256);
                    if (withdrawn != null) {
                        // A candidate the app no longer offers, whatever its checks say.
                        summary = "The app refuses this file. Don't put it on the card. It is "
                                + withdrawn.label.substring(0, 1).toLowerCase(java.util.Locale.ROOT)
                                + withdrawn.label.substring(1) + " For stock firmware, use the stock restore file,"
                                + " which is only for a camera whose backup, read before the camera was ever written to,"
                                + " shows the public build.\n"
                                + "File: " + name + ".\n"
                                + "If this file is on the card, tap " + q(REMOVE) + " before the card goes back into"
                                + " the camera.";
                    } else if (restore && UpdateCheck.nameIsTheOnlyProblem(result)) {
                        // The bytes are the stock restore file and only its name failed; anything else, a sensor
                        // table that differs from the backup's above all, is shown in full by showChecked below.
                        summary = "Not OK to put on the card. It's exactly the stock restore file, but under a name the"
                                + " camera won't use. Tap " + q(DOWNLOAD) + " to make it with the right name.\n"
                                + "File: " + name + ".";
                    } else {
                        origin = "File: " + name + "."
                                + (restore ? " It is exactly the stock restore file." : "")
                                + (renamed ? "\nIts name here is not exactly the one the camera looks for. The app"
                                        + " writes it to the card as " + result.cardName + ". A copy you put on the card"
                                        + " yourself must be renamed to exactly that, or the camera will not use it."
                                        : "");
                        chosen = !renamed;
                        summary = null;   // shown by showChecked, which also knows about the backup
                    }
                    if (summary != null) {
                        technical = "Detail: " + UpdateCheck.bytes(data.length) + ", SHA-256 " + result.sha256 + ".\n\nThe"
                                + " app's checks:\n\n" + result.report();
                        result = null;    // a refused file is shown, never kept for writing
                        data = null;
                    }
                }
            } catch (IOException | RuntimeException e) {
                data = null;
                result = null;
                stock = null;
                factory = null;
                summary = "The app could not use this file: " + sentence(cause(e, "it could not be read. It may have"
                        + " been moved, or the app that holds it did not hand it over")) + " Tap " + q(USE_FILE)
                        + " to choose a file again.";
                technical = detailBlock(detailOf(e));
            }
            final byte[] d = data;
            final UpdateCheck.Result r = result;
            final String t = summary;
            final String x = technical;
            final byte[] s = stock;
            final byte[] fd = factory;
            final String fromFactory = "\"" + name + "\" is your factory chip backup." + (factoryKept
                    ? " The app keeps it on this phone, so you don't need to pick it again." : "");
            final String from = "\"" + name + "\" is the public v006 chip backup.";
            final String o = origin;
            final boolean c = chosen;
            final String n = name;
            ui.post(() -> {
                if (gone() || request != fileRequest) {
                    return;
                }
                if (fd != null) {
                    // Kept on every route, but made into firmware only where that firmware is written: on the Repair
                    // route, the v012 build picked there (or the default one, which becomes the pick); on Advanced,
                    // which writes any checked file, the same build, the Repair pick unchanged. Elsewhere (Restore,
                    // Probe) nothing is made: those routes never write a v012 build.
                    Recipe.Selection k = Recipe.V012.equals(repairSel.base) ? repairSel : factoryDefault();
                    factoryDump = fd;
                    if (!buildsFactory(forRoute)) {
                        // The Repair pick moves to stock v012 unless the owner picked one already. No mod is ticked.
                        if (!Recipe.V012.equals(repairSel.base)
                                && getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_REPAIR_SET, null) == null) {
                            repairSel = factoryDefault();
                            freshRunIfStale(FlashFlow.Route.REPAIR);
                        }
                        showFile(factoryKeptOnly(fromFactory), null);
                        updateButtons();
                        return;
                    }
                    if (forRoute == Route.REPAIR && !k.equals(repairSel)) {
                        repairSel = k;
                        rememberRepair();
                        freshRunIfStale(FlashFlow.Route.REPAIR);
                        render();
                    } else if (route == Route.REPAIR) {
                        refreshRepairChoice();
                    }
                    buildRestore(fd, request, fromFactory, k, forRoute);
                    updateButtons();
                    return;
                }
                if (s != null) {
                    // On the Repair route a stock dump makes the v006 pick there; elsewhere the restore file.
                    Recipe.Selection k = forRoute != Route.REPAIR ? Recipe.of(Candidates.Kind.RESTORE)
                            : Recipe.V006.equals(repairSel.base) ? repairSel : null;
                    if (k == null) {
                        stockDump = s;
                        showFile(from + " " + needsFactory(repairSel), null);
                        updateButtons();
                        return;
                    }
                    buildRestore(s, request, from, k, forRoute);
                    return;
                }
                if (r != null && o != null) {
                    // Checked against the backup on record when the file was picked; applyCheck checks it again if
                    // another backup has come in since.
                    fileFor = forRoute;
                    applyCheck(d, r, n, o, c);
                } else {
                    fileData = null;
                    fileCheck = null;
                    showFile(t, x);
                }
                updateButtons();
            });
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // fall through to the last path segment
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last.substring(last.lastIndexOf('/') + 1);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) {
            throw new Plain("Android gave no data for it");
        }
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(UpdateCheck.UPDATE_SIZE);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_FILE) {
                    throw new Plain("it is larger than " + UpdateCheck.bytes(MAX_FILE) + ", so it is neither an update file nor the"
                            + " v006 chip backup");
                }
            }
            return out.toByteArray();
        }
    }

    // --- the card -----------------------------------------------------------------------------------------------

    private void pickCard() {
        if (busy() || archiving()) {
            return;     // a write or removal reports on the drive it runs on; another one picked now would take its place
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, PICK_CARD);
        } catch (ActivityNotFoundException | SecurityException e) {
            setStatus(cardStatus, "Android couldn't open its folder picker, so the app can't reach the card. Instead,"
                    + " tap " + q(SAVE) + " on the Advanced page and copy that file to the card's top folder with the"
                    + " Files app. Then tap " + q(USE_FILE) + " and choose the copy on the card, so the app checks it"
                    + " there."
                    + detail(why(e)));
            if (driveAway()) {
                // The card's readout is hidden while the saved drive is away: say it where it is seen.
                android.widget.Toast.makeText(this, "Android couldn't open its folder picker, so the app can't"
                        + " reach the card.", android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean hasPermission(Uri uri) {
        try {
            for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(uri) && p.isReadPermission() && p.isWritePermission()) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // treated as no permission: the card is chosen again
        }
        return false;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (request == PICK_FILE) {
            loadFile(uri, pendingFileFor != null ? pendingFileFor : route);
            pendingFileFor = null;
        } else if (request == PICK_BACKUP) {
            loadBackup(uri);
        } else if (request == SAVE_COPY) {
            writeCopy(uri);
        } else if (request == SAVE_ZIP || request == SAVE_SHOTS) {
            if (card == null && tree != null && !busy() && !archiving()) {
                // A screen made while the save screen was open has not listed the card yet: the zip starts once it
                // has (inspectCard), and is refused, its empty file deleted, only if the card cannot be read.
                pendingZip = uri;
                pendingZipShots = request == SAVE_SHOTS;
                clearOutcome(pendingZipShots);      // onResume or a render must not bring an older zip's result back
                setStatus(pendingZipShots ? shotsStatus : zipStatus, "Looking at the card before saving the zip…");
            } else {
                startArchive(uri, request == SAVE_SHOTS);
            }
        } else if (request == PICK_CARD) {
            // Results about the previous drive, or an earlier look at this one, no longer apply.
            if (!busy()) {
                writeStatus.setText("");
                cleanStatus.setText("");
                sensorStatus.setText("");    // a sensor result read from another drive no longer applies
                ejectStatus.setText("");     // nor an eject of it
                ejectAsked = false;
            }
            // The same drive chosen again keeps its backup's result; a backup of another drive is not one of this one.
            String chosenUuid = DocumentsContract.isTreeUri(uri) ? uuidOf(uri) : null;
            boolean sameDrive = chosenUuid != null && chosenUuid.equalsIgnoreCase(backupSavedFrom);
            if (!archiving() && !sameDrive) {
                backupSaved = false;         // a backup of the drive chosen before is not one of this drive
                zipStatus.setText("");
                backupArchive = null;
            }
            if (!DocumentsContract.isTreeUri(uri)) {
                tree = null;
                card = null;
                if (followIdentify() && route == Route.REPAIR) {     // no drive, so no Identify answer
                    render();
                }
                relistedFrom = null;
                cardRequest++;
                inspecting = false;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_TREE).remove(KEY_DRIVE_NAME).apply();
                refreshDrive();
                setStatus(cardStatus, "That was a single file, not a drive. Tap " + q(CHOOSE) + " again, open the"
                        + " camera's drive and tap \"Use this folder\" in its top folder.");
                updateButtons();
                return;
            }
            String note = null;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_DRIVE_NAME).apply();   // another drive's name
            progressEjected = false;
            ejectedThisMount = false;
            // Each drive has its own flash runs (runOf), so another drive's progress never shows for this one.
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply();
            } catch (SecurityException e) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_TREE).apply();
                note = "Android allowed access to this drive for now, but not for later. Next time you open the app,"
                        + " tap " + q(CHOOSE) + " again." + detail(why(e));
            }
            tree = uri;
            if (followIdentify() && route == Route.REPAIR) {     // another drive has its own Identify answer, or none
                render();
            }
            refreshDrive();
            inspectCard(note);
        }
    }

    private void inspectCard() {
        inspectCard(null);
    }

    private void inspectCard(String note) {
        if (tree == null) {
            return;
        }
        cardDetailToggle.setVisibility(View.GONE);
        cardDetail.setVisibility(View.GONE);
        inspectAfterDialog = false;
        final int request = ++cardRequest;
        final Uri t = tree;
        inspecting = true;
        setStatus(cardStatus, "Looking at the card…");
        if (card != null) {
            relistedFrom = card;      // a look that supersedes another keeps the listing from before both
        }
        card = null;
        updateButtons();
        WORKER.execute(() -> {
            Card c = readCard(t);
            ui.post(() -> {
                if (gone() || request != cardRequest) {
                    return;
                }
                inspecting = false;
                relistedFrom = null;
                card = c;
                noteListing(c);     // the drive's flash runs take in what the card holds (a removal counts on this)
                refreshDrive();
                cardNote = note;
                showCard();
                updateButtons();
                TextView shown = revealAfterLook;     // a zip's result, now that the steps are as the listing says
                revealAfterLook = null;
                reveal(shown);
                startPendingZip();
            });
        });
    }

    /** A zip whose save place came while the card was not listed: starts it now (startArchive refuses an unread card). */
    private void startPendingZip() {
        Uri dest = pendingZip;
        if (dest == null) {
            return;
        }
        pendingZip = null;
        startArchive(dest, pendingZipShots);
    }

    /**
     * The drive step's readout of the card: its one-line verdict and any update file, with what to do about it on this
     * route; the card's name, free space and DCIM folder go behind Details.
     */
    private void showCard() {
        Card c = card;
        if (c == null || inspecting) {
            return;
        }
        String all = c.state.describe();
        int nl = all.indexOf('\n');
        String head = nl < 0 ? all : all.substring(0, nl);
        StringBuilder rest = new StringBuilder();
        if (nl >= 0) {
            for (String line : all.substring(nl + 1).split("\n")) {
                if (!line.startsWith("Remove it") && !line.startsWith("Remove them")) {
                    rest.append(rest.length() == 0 ? "" : "\n").append(line);
                }
            }
        }
        boolean writes = route == Route.REPAIR || route == Route.PROBE || route == Route.RESTORE
                || route == Route.ADVANCED;
        if (!writes && c.state.listed && c.state.problem == null && c.state.updateFiles.isEmpty()) {
            head = "Ready: the app can read this card.";
        }
        StringBuilder sb = new StringBuilder(head);
        if (c.state.listed && !c.state.updateFiles.isEmpty()) {
            boolean one = c.state.updateFiles.size() == 1;
            sb.append('\n').append(c.state.names()).append(". ").append(writes
                    ? "Remove " + (one ? "it" : "them") + " with " + q(REMOVE) + " after the camera writes it, or before you"
                    + " write a different file."
                    : "Remove " + (one ? "it" : "them") + " on the " + routeTitle(Route.REPAIR) + ", "
                    + routeTitle(Route.PROBE) + " or " + routeTitle(Route.RESTORE) + " screen before you switch"
                    + " the camera on with this card.");
        }
        if (cardNote != null) {
            sb.append("\n\n").append(cardNote);
        }
        setStatus(cardStatus, sb.toString());
        cardDetail.setText(rest.toString());
        cardDetail.setVisibility(View.GONE);
        cardDetailToggle.setText("\u25b8 Details");
        cardDetailToggle.setVisibility(rest.length() == 0 || cardStatus.getVisibility() != View.VISIBLE ? View.GONE
                : View.VISIBLE);
    }

    /** Lists the card's top folder. Never throws: a card that cannot be read comes back with a problem, not empty. */
    private Card readCard(Uri t) {
        Card c = new Card(t);
        try {
            fillCard(c);
        } catch (RuntimeException e) {
            c.root = null;
            c.state = CardState.unreadable("The app cannot read the card. Check that the camera or card reader is still"
                    + " connected, or that the card is still in the phone. Then tap " + q(CHOOSE) + " and pick the drive"
                    + " again, so the app looks at it again." + detail(why(e)));
        }
        return c;
    }

    private void fillCard(Card c) {
        Uri t = c.tree;
        CardState s = c.state;
        if (!EXTERNAL_STORAGE.equals(t.getAuthority())) {
            s.problem = "That folder isn't on a card or USB drive. It belongs to another app. Tap " + q(CHOOSE)
                    + " again and pick the camera's drive in the picker's menu.";
            return;
        }
        String docId = DocumentsContract.getTreeDocumentId(t);
        if (docId.startsWith("primary:")) {
            s.problem = "That is the phone's own storage, not the card. Tap " + q(CHOOSE) + " again and pick the"
                    + " camera's drive in the picker's menu.";
            return;
        }
        if (!docId.endsWith(":")) {
            s.problem = "That is the folder \"" + docId.substring(docId.indexOf(':') + 1) + "\" on the card, not its top"
                    + " folder. The camera looks for the update file only in the top folder. Tap " + q(CHOOSE)
                    + " again, open the camera's drive and tap \"Use this folder\" before you open any folder in it. On"
                    + " Android 11 and later, the top folder of a card in the phone's own slot can't be chosen, so use"
                    + " the camera's USB-C cable or a USB card reader.";
            return;
        }
        c.root = DocumentsContract.buildDocumentUriUsingTree(t, docId);
        ContentResolver r = getContentResolver();
        // A card that is not there makes the provider answer with no cursor at all, not with an empty folder.
        try (Cursor q = r.query(c.root, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (q == null || !q.moveToFirst()) {
                s.problem = CardState.NOT_FOUND;
                return;
            }
            s.label = q.getString(0);
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(t, docId);
        try (Cursor q = r.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (q == null) {
                s.problem = CardState.NOT_FOUND;
                return;
            }
            while (q.moveToNext()) {
                s.addChild(q.getString(0), q.getString(1), q.getString(3), q.isNull(2) ? -1 : q.getLong(2));
            }
        }
        s.listingDone(availableBytes(docId));
    }

    private long availableBytes(String docId) {
        Uri roots = DocumentsContract.buildRootsUri(EXTERNAL_STORAGE);
        try (Cursor q = getContentResolver().query(roots, new String[]{DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES}, null, null, null)) {
            while (q != null && q.moveToNext()) {
                if (docId.equals(q.getString(0)) && !q.isNull(1)) {
                    return q.getLong(1);
                }
            }
        } catch (RuntimeException ignored) {
            // the free space is only a courtesy check; the write reports a full card anyway
        }
        return -1;
    }

    // --- writing ------------------------------------------------------------------------------------------------

    private void confirmWrite() {
        // Everything the dialog shows is captured here, and Write uses exactly that, whatever is loaded later. The
        // backup it names is the one the check was made against: the gate allows no other.
        final byte[] data = fileData;
        final UpdateCheck.Result check = fileCheck;
        final Card c = card;
        if (busy() || archiving() || dialog != null || gate(check, data) != null || c == null
                || !c.state.writable() || !fitsRoute(check)) {
            return;
        }
        final String name = check.cardName;
        final boolean probe = isProbe(check);
        final Recipe.Selection repair = repairOf(check);
        final boolean bringup = repair != null;
        final boolean factory = bringup && Recipe.V012.equals(repair.base);
        final boolean port = bringup && Recipe.V006.equals(repair.base) && repair.has(Recipe.OV9732_PORT);
        // Every reviewed file is written without a backup: the dialog says when nothing confirms what the camera runs.
        // Any reviewed build whose set is experimental says so, even when each of its mods alone is tested.
        // The sensor warning is the backup's "Wrong sensor?" line when the check has one, else the unconfirmed one.
        final String unsure = sensorWarning(check);
        final boolean unknown = unknownNoBackup(check);
        final Recipe.Selection made = Recipe.selectionOf(check.sha256);
        final String untried = made != null && "experimental".equals(made.status()) ? cap(made.label())
                + " is experimental. It hasn't been tried on a camera as it is. If the camera boots but misbehaves, you can"
                + " go back from the card." + (Recipe.V012.equals(made.base) ? "" : " " + wayBack(made)) + "\n" : "";
        final String backupLine = backupSha256 == null ? null
                : backupName + ", SHA-256 " + backupSha256.substring(0, 16) + "…";
        CardState.Item same = c.state.sameFile(check);
        if (same != null) {
            compareWithCard(c, same, check);
            return;
        }
        String refusal = c.state.writeRefusal();
        if (refusal != null) {
            setStatus(writeStatus, "Nothing was written to the card. " + refusal);
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(probe ? "Write the sensor-ID probe to the card?"
                        : bringup ? "Write " + repair.label() + " to the card?"
                        : "Write the update file to the card?")
                .setMessage("The app copies the update file to the card's top folder. Nothing changes on the camera"
                        + " yet. The camera writes the file the next time it switches on with this card in it.\n\n"
                        + "Update file: " + name + "\n"
                        + (probe
                            ? "This is the sensor-ID probe, a known file. It scans the sensor bus and writes a result"
                            + " to SENSOR.TXT. It doesn't fix the camera.\n"
                            : factory && repair.stock()
                            ? "This is " + repair.label() + ", a known file. It's the factory firmware of OV9732"
                            + " cameras, so it's also the way back to stock from every v012 build.\n"
                            : factory
                            ? "This is " + repair.label() + ", a known file. The way back is another v012 build or"
                            + " stock v012. Make it on " + q(routeTitle(Route.REPAIR)) + ".\n"
                            : port
                            ? "This is " + repair.label() + ", a known file. It drives the fitted OV9732 in place"
                            + " of the SP140A set-up and changes no boot or update code. The stock restore file"
                            + " doesn't drive the OV9732.\n"
                            : bringup
                            ? "This is " + repair.label() + ", a known SP140A firmware file.\n"
                            : backupLine != null && check.compared
                            ? "Compared with this camera's chip backup: " + backupLine + "\n"
                            : isReviewedPatch(check)
                            ? "A known file, not compared with a backup.\n"
                            : "")
                        + (unsure == null ? "" : "\n\u26a0 " + unsure + "\n\n")
                        + (unknown ? "\n\u26a0 Unknown file. " + UNKNOWN_FILE + "\n\n" : "")
                        + untried
                        + "Card: " + (c.state.label == null ? "the chosen card" : c.state.label) + "\n\n"
                        + (probe
                            ? "After the camera writes it, open photo mode on the camera, then read the result with "
                            + q(READ_RESULT) + ".\n\n"
                            : bringup
                            ? "After the camera writes it, power the camera on and try a photo, then take the card out and look at"
                            + " the JPEGs.\n\n"
                            : check.compared
                            ? "The app can't tell which camera a backup was read from. Write only if that backup is a"
                            + " read of the camera this card goes into.\n\n"
                            : "")
                        + "Detail: " + UpdateCheck.bytes(check.size) + ", SHA-256 " + check.sha256 + ".")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(probe ? "Write probe" : bringup ? "Write firmware" : "Write file",
                        (di, w) -> write(c, data, check))
                .create();
        d.setOnDismissListener(di -> {
            if (dialog == di) {
                dialog = null;
            }
            if (writeDialog == di) {
                writeDialog = null;
            }
            if (!gone()) {
                afterDialog();
            }
        });
        dialog = d;
        writeDialog = d;
        d.show();
        updateButtons();
    }

    private void compareWithCard(Card c, CardState.Item same, UpdateCheck.Result check) {
        comparing = true;
        setStatus(writeStatus, "Checking the update file already on the card…");
        updateButtons();
        WORKER.execute(() -> {
            String sha;
            try {
                sha = sha256Of(DocumentsContract.buildDocumentUriUsingTree(c.tree, same.documentId));
            } catch (RuntimeException e) {
                sha = "could not read it: " + why(e);
            }
            final boolean equal = check.sha256.equals(sha);
            final boolean read = isSha(sha);
            final String got = sha;
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                comparing = false;
                String uuid = uuidOf(c.tree);
                if (equal) {
                    writtenWith = check.referenceSha256;
                    writtenName = backupName;
                    writtenSha = check.sha256;
                    // A flash with this file starts over, as after a write, but nothing was written: no synced claim,
                    // and Remove is open at once, since it may have been flashed before (FlashFlow.foundOnCard).
                    newRun(uuid, check.sha256, check.cardName, false, check.referenceSha256);
                    noteListing(c);
                } else {
                    // Not the file the run wrote, under its name: Remove opens for it ("Tap Remove now").
                    FlashFlow f = runOf(uuid, routeOfFile(check.sha256));
                    f.differentFileOnCard();
                    keep(uuid, f);
                }
                setStatus(writeStatus, equal
                        ? NOTHING_TO_WRITE + " the card already holds this exact update file, " + check.cardName + ".\n"
                        + "Eject the drive and let the camera write it. If the camera has already written it, tap " + q(REMOVE)
                        + " instead."
                        : read
                        ? "Nothing was written to the card. It holds a different file with the same name, " + same.name
                        + ". Tap " + q(REMOVE) + " now, then write again."
                        : "Nothing was written to the card. It holds a file named " + same.name + ", but the app could"
                        + " not read it to compare. Tap " + q(REMOVE) + " now, then write again."
                        + detail(got));
                updateButtons();
            });
        });
    }

    private void write(Card c, byte[] data, UpdateCheck.Result check) {
        // Tapped in the confirmation: the gate is asked again, with the backup on record at this moment.
        String refusal = busy() || archiving() ? "a write, a removal or a zip is already running"
                : gate(check, data);
        if (refusal != null) {
            setStatus(writeStatus, "Nothing was written to the card: " + sentence(refusal));
            updateButtons();
            return;
        }
        final Operation op = start(true, 1);
        op.sha256 = check.sha256;
        op.cardName = check.cardName;
        setStatus(writeStatus, WRITING);
        cleanStatus.setText("");          // a result about an earlier removal no longer applies
        ejectStatus.setText("");          // an eject before this write no longer applies
        sensorStatus.setText("");         // a sensor result from a previous flash no longer applies
        updateButtons();
        WORKER.execute(() -> {
            String outcome = null;
            try {
                outcome = writeAndReport(c.tree, data, check);
            } finally {
                String o = outcome != null ? outcome : "Writing stopped with an unexpected error. The Camera drive"
                        + " step shows what the card holds now. Don't switch the camera on with this card in it"
                        + " while it lists an update file.";
                finish(op, o, o.startsWith(WRITTEN));
            }
        });
    }

    /** Runs on the worker. Checks again, lists the card again, writes, reads back; says what is on the card after a failure. */
    private String writeAndReport(Uri t, byte[] data, UpdateCheck.Result check) {
        // Against the backup on record now, which may have changed since Write was tapped. A check of another file in
        // the file step does not concern these bytes; a changed backup shows in its SHA-256.
        String refusal = WriteGate.refusal(check, data, backupSha256, loadingBackup, false);
        if (refusal != null) {
            return "Nothing was written to the card: " + sentence(refusal);
        }
        Card now = readCard(t);
        refusal = now.state.writeRefusal();
        if (refusal != null || now.root == null) {
            return "Nothing was written to the card. Just before writing, the app looked at the card again and found"
                    + " this:\n" + (refusal == null ? "The app cannot read the card. Tap " + q(CHOOSE) + " and pick the"
                    + " drive again." : refusal);
        }
        try {
            return writeAndVerify(now, data, check);
        } catch (IOException | RuntimeException e) {
            return "Writing failed: " + sentence(cause(e, "the card or Android stopped the write. The card may be full,"
                    + " or the cable or card came loose")) + "\n\n"
                    + readCard(t).state.afterwards() + "\n\nWhen the camera drive step lists no update file, tap " + q(WRITE)
                    + " to try again." + detail(detailOf(e));
        }
    }

    private String writeAndVerify(Card c, byte[] data, UpdateCheck.Result check) throws IOException {
        ContentResolver r = getContentResolver();
        String name = check.cardName;
        Uri temp = DocumentsContract.createDocument(r, c.root, "application/octet-stream", TEMP_NAME);
        if (temp == null) {
            throw new Plain("the card did not let the app create a file on it");
        }
        writeFully(temp, data);
        Uri doc;
        try {
            doc = DocumentsContract.renameDocument(r, temp, name);
            if (doc == null) {
                // Android 7 reports a failed rename as null instead of an exception.
                throw new Plain("the card did not rename the app's temporary file " + TEMP_NAME);
            }
        } catch (IOException | RuntimeException e) {
            // Rename is optional for providers: delete the temporary file and write under the final name directly.
            String failed = delete(temp);
            if (failed != null) {
                throw new Plain("the app could not rename its temporary file " + TEMP_NAME + " on the card, and could"
                        + " not delete it either", "rename: " + why(e) + "; delete: " + failed);
            }
            doc = DocumentsContract.createDocument(r, c.root, "application/octet-stream", name);
            if (doc == null) {
                throw new Plain("the card did not let the app create " + name);
            }
            writeFully(doc, data);
        }
        String actual = documentName(doc);
        if (!name.equals(actual)) {
            String failed = delete(doc);
            throw new Plain("the card saved the file " + (actual == null ? "under an unknown name" : "as \"" + actual
                    + "\"") + " instead of " + name + ", and the camera would not find it under that name. "
                    + (failed == null ? "The app deleted it" : "The app could not delete it"), failed);
        }
        String sha = sha256Of(doc);
        if (!check.sha256.equals(sha)) {
            String failed = delete(doc);
            throw new Plain((isSha(sha) ? "the copy on the card reads back differently from the update file, so the"
                    + " card may be failing. " : "the app could not read the copy back from the card to check it. ")
                    + (failed == null ? "The app deleted the copy" : "The app could not delete the copy"),
                    (isSha(sha) ? "SHA-256 read back " + sha + "; the update file's is " + check.sha256 : sha)
                    + (failed == null ? "" : "; delete: " + failed));
        }
        return WRITTEN + "\nThe app wrote " + name + " to the card's top folder and read it back, and it matches.\n"
                + "Now eject the drive before you unplug the cable or take the card out. Tap " + q(EJECT) + "."
                + detail("SHA-256 " + sha + ".");
    }

    /** delete()'s answer when the card said no (deleteDocument returned false), as opposed to an error. */
    private static final String CARD_REFUSED = "the card refused";

    /**
     * delete()'s answer for false on Android 7: it answers false for an error too (a drive pulled mid-wipe, say), so
     * the app cannot tell a refusal from a failure, and never calls it a refusal.
     */
    private static final String FALSE_ON_7 = "the delete failed (Android 7 gives no reason)";

    /** Null when the document was deleted, otherwise why not (Android 7 answers false, later versions throw). */
    private String delete(Uri doc) {
        try {
            return DocumentsContract.deleteDocument(getContentResolver(), doc) ? null : falseAnswer();
        } catch (IOException | RuntimeException e) {
            return why(e);
        }
    }

    /** What deleteDocument's false means: the card said no from Android 8 on; on Android 7 it may be an error too. */
    private static String falseAnswer() {
        return android.os.Build.VERSION.SDK_INT >= 26 ? CARD_REFUSED : FALSE_ON_7;
    }

    private void writeFully(Uri doc, byte[] data) throws IOException {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(doc, "w")) {
            if (pfd == null) {
                throw new Plain("the card did not open the file for writing");
            }
            try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                out.write(data);
                out.flush();
                out.getFD().sync();
            }
        }
    }

    private String documentName(Uri doc) {
        try (Cursor q = getContentResolver().query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            return q != null && q.moveToFirst() ? q.getString(0) : null;
        }
    }

    /** The document's SHA-256 in hex, or, when it cannot be read, why not (which isSha tells apart). */
    private String sha256Of(Uri doc) {
        try (InputStream in = getContentResolver().openInputStream(doc)) {
            if (in == null) {
                return "could not read it back: Android gave no data";
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return UpdateCheck.toHex(md.digest());
        } catch (Exception e) {
            return "could not read it back" + (why(e).isEmpty() ? "" : ": " + why(e));
        }
    }

    // --- reading the sensor-ID probe's result -------------------------------------------------------------------

    /** Read SENSOR.TXT the sensor-ID probe wrote to the card, and say in plain words what its two bytes mean. */
    private void readSensorResult() {
        final Card c = card;
        if (busy() || dialog != null || readingSensor || c == null || !c.state.listed) {
            return;
        }
        final Uri t = c.tree;
        // The probe run on this card's drive: the SENSOR.TXT the card held just after the probe was written (or found
        // there), kept with the run so it survives a restart. Only that same file read again is called unchanged; the
        // camera's and the phone's clocks are never compared, since the camera's may be off by hours. When the app could
        // not read it then, or has no probe write on record for this card, the result says it cannot tell.
        final String uuid = uuidOf(t);
        final FlashFlow run = runOf(uuid, FlashFlow.Route.PROBE);
        final boolean onRecord = run.past();
        final String before = onRecord ? run.sensorAtWrite() : null;
        final boolean wrote = run.wroteIt();
        final String id = runId(uuid, run);
        // Flashed and removed already: a missing file then means photo mode has not run yet, not "flash it first".
        final boolean removed = run.stage() == FlashFlow.Stage.REMOVED || run.stage() == FlashFlow.Stage.CHECKED;
        readingSensor = true;
        setStatus(sensorStatus, "Reading SENSOR.TXT from the card…");
        updateButtons();
        WORKER.execute(() -> {
            String text;
            boolean fresh = false;
            try {
                long[] modified = {0};
                byte[] b = readCardFile(t, "SENSOR.TXT", modified);
                text = b == null && removed ? "No SENSOR.TXT on the card yet.\n\nPower the camera on and open photo"
                        + " mode once. It stops there as before, but writes the file first. Then switch it off,"
                        + " reconnect, and tap " + q(READ_RESULT) + " again." : decodeSensor(b);
                if (b != null && before != null && !before.isEmpty()
                        && before.equals(sensorIdentity(b, modified[0]))) {
                    // The very file the card held when the probe went on it: same date, size and contents.
                    text = "This SENSOR.TXT has the same date, size and contents as when the probe was "
                            + (wrote ? "written to" : "found on") + " the card. So either the probe hasn't run yet, or"
                            + " it ran and left the file exactly as it was. With the update file removed, open photo mode once, switch"
                            + " the camera off, reconnect, then tap " + q(READ_RESULT) + " again.\n\nWhat the file"
                            + " says:\n" + text;
                } else if (b != null && onRecord && before == null) {
                    text = "The app can't tell whether the probe wrote this file since the last write, because it"
                            + " couldn't read the card's SENSOR.TXT when the probe went on it. If you haven't opened photo mode"
                            + " since removing the update file, do that first, switch the camera off, reconnect, then"
                            + " tap " + q(READ_RESULT) + " again.\n\nWhat the file says:\n" + text;
                } else if (b != null && !onRecord) {
                    text = "The app has no record of writing the probe to this card, so it can't tell when this file"
                            + " was written. It may be from an earlier probe.\n\nWhat the file says:\n" + text;
                } else {
                    fresh = b != null;      // a file the card did not hold when the probe went on it
                }
            } catch (RuntimeException e) {
                text = "Could not read the card: " + sentence(cause(e, "the drive may have come loose")) + " Tap "
                        + q(CHOOSE) + " and pick the drive again." + detail(why(e));
            }
            final String tt = text;
            final boolean news = fresh;
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                readingSensor = false;
                FlashFlow now = runOf(uuid, FlashFlow.Route.PROBE);
                if (news && id != null && id.equals(runId(uuid, now))) {
                    now.checked(true, tt);      // this probe's own result: the run is done (its step stays open)
                    keep(uuid, now);
                }
                setStatus(sensorStatus, tt);
                reveal(sensorStatus);
                updateButtons();
            });
        });
    }

    /**
     * Who a SENSOR.TXT is, to tell whether a later read is the same file: its last-modified time, size and SHA-256;
     * "" for no file.
     */
    private static String sensorIdentity(byte[] b, long modified) {
        return b == null ? "" : modified + ":" + b.length + ":" + UpdateCheck.sha256Hex(b, 0, b.length);
    }

    /**
     * The bytes of the file with this name in the card's top folder (any case), or null when the card was read and
     * there is genuinely no such file. A card that cannot be read now returns a null children cursor (as fillCard
     * treats it too): that throws, so a dropped or loose drive is never reported as a missing file. modified[0] gets
     * the file's last-modified time (0 when the provider gives none).
     */
    private byte[] readCardFile(Uri t, String name, long[] modified) {
        String docId = DocumentsContract.getTreeDocumentId(t);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(t, docId);
        String foundId = null;
        try (Cursor q = getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                null, null, null)) {
            if (q == null) {
                throw new RuntimeException("the drive did not answer");
            }
            while (q.moveToNext()) {
                if (name.equalsIgnoreCase(q.getString(1))
                        && !DocumentsContract.Document.MIME_TYPE_DIR.equals(q.getString(2))) {
                    foundId = q.getString(0);
                    modified[0] = q.isNull(3) ? 0 : q.getLong(3);   // its last-modified time, 0 when not given
                    break;
                }
            }
        }
        if (foundId == null) {
            return null;
        }
        try (InputStream in = getContentResolver().openInputStream(
                DocumentsContract.buildDocumentUriUsingTree(t, foundId))) {
            return readAll(in);
        } catch (IOException e) {
            throw new RuntimeException(why(e).isEmpty() ? "the file could not be read" : why(e));
        }
    }

    /** What SENSOR.TXT means, for the owner: the bus-scan result the probe writes, or an older probe's two bytes. */
    private String decodeSensor(byte[] b) {
        if (b == null) {
            return "No SENSOR.TXT on the card yet.\n\nWrite the sensor-ID probe and remove the update file, then open photo mode on"
                    + " the camera. It stops there as before, but writes the file first. Then bring the card back and"
                    + " tap " + q(READ_RESULT) + " again.";
        }
        // Current probe (16-bit ID read): 0xA7 0x01, a responder count, then per responder its 7-bit address and two
        // 16-bit register windows: 0x3000..0x301F (32 bytes) then 0x3100..0x310F (16 bytes).
        if (b.length >= 3 && (b[0] & 0xFF) == 0xA7 && (b[1] & 0xFF) == 0x01) {
            return decodeFingerprint16(b);
        }
        // Earlier 8-bit fingerprint probe: 0xA6 0x01, a responder count, then per responder its 7-bit address and a
        // window of 8-bit registers (0x00..0x1F then 0xF0..0xFF, 48 bytes).
        if (b.length >= 3 && (b[0] & 0xFF) == 0xA6 && (b[1] & 0xFF) == 0x01) {
            return decodeFingerprint(b);
        }
        // Earlier scan probe: 0xA5 0x01, a 16-byte present-address bitmap (7-bit addresses), the first responder's
        // address (0xFF = none), then its registers 0x02 and 0x03.
        if (b.length >= 21 && (b[0] & 0xFF) == 0xA5 && (b[1] & 0xFF) == 0x01) {
            return decodeScan(b);
        }
        // An older single-address probe wrote just two bytes; keep reading it so an old card still makes sense.
        if (b.length < 2) {
            return "SENSOR.TXT holds " + b.length + (b.length == 1 ? " byte" : " bytes") + ", not a result the probe"
                    + " writes. The probe may not have finished. Write it again, open photo mode, and read it again.";
        }
        int hi = b[0] & 0xFF, lo = b[1] & 0xFF;
        String id = String.format(java.util.Locale.ROOT, "0x%02X 0x%02X", hi, lo);
        if ((hi == 0xFF && lo == 0xFF) || (hi == 0x00 && lo == 0x00)) {
            return "Sensor ID " + id + ": nothing answered at the one address this older probe tried. Write the current"
                    + " probe, which scans every address and tells a wrong address apart from a sensor that is truly"
                    + " silent.";
        }
        if ((hi == 0x14 && lo == 0x0A) || (hi == 0x0A && lo == 0x14)) {
            return "Sensor ID " + id + ": the SP140A, the sensor the firmware already drives.";
        }
        return "Sensor ID " + id + ": a different sensor from the one the firmware drives. Write down these two bytes, since they name"
                + " the part.";
    }

    /** Decode the bus-scan SENSOR.TXT (see decodeSensor for the layout): which 7-bit addresses answered, and the
     *  first responder's ID bytes, turned into plain words about dead-sensor versus wrong-sensor. */
    private String decodeScan(byte[] b) {
        java.util.List<Integer> hits = new java.util.ArrayList<>();
        for (int a = 0; a < 128; a++) {
            if ((b[2 + (a >> 3)] & (1 << (a & 7))) != 0) {
                hits.add(a);
            }
        }
        final int SP140A = 0x3C;   // 7-bit; bus write address 0x78
        if (hits.isEmpty()) {
            return "The probe scanned every address on the sensor bus and nothing answered.\n\nThat points to a sensor"
                    + " that isn't responding at all (disconnected, unpowered or dead), not one at the wrong"
                    + " address. Open the camera, reseat the sensor's ribbon, and scan again. If it still answers"
                    + " nowhere, the sensor or its wiring is a hardware fault no firmware can fix.";
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            int a = hits.get(i);
            list.append(i == 0 ? "" : ", ")
                .append(String.format(java.util.Locale.ROOT, "0x%02X (bus 0x%02X)", a, a << 1));
        }
        int r2 = b[19] & 0xFF, r3 = b[20] & 0xFF;
        String idStr = String.format(java.util.Locale.ROOT, "0x%02X 0x%02X", r2, r3);
        StringBuilder sb = new StringBuilder();
        if (hits.contains(SP140A)) {
            boolean idRead = (b[18] & 0xFF) == SP140A;
            boolean match = idRead && r2 == 0x14 && r3 == 0x0A;
            sb.append("A sensor answered at 0x3C (bus 0x78), the address the firmware drives");
            if (idRead) {
                sb.append(", and its ID reads ").append(idStr)
                  .append(match ? ", the SP140A it expects." : ", not the SP140A's 14 0A.");
            } else {
                sb.append(".");
            }
            sb.append("\n\n").append(match
                    ? "So the sensor is present and is the exact part the firmware expects. The freeze isn't a sensor"
                      + " mismatch. The cause is elsewhere: power, the clock, or a fault past the ID read."
                    : "A sensor is present at the expected address, so this is not a missing or wrong-address sensor."
                      + " If the ID is not 14 0A, note it down.");
        } else {
            int a = (b[18] & 0xFF) != 0xFF ? (b[18] & 0xFF) : hits.get(0);
            sb.append("A sensor answered at ")
              .append(String.format(java.util.Locale.ROOT, "0x%02X (bus 0x%02X)", a, a << 1))
              .append(", not the 0x3C (bus 0x78) the firmware drives.\n\n")
              .append("The camera has a different sensor from the one this firmware drives. It answers, just at"
                      + " another address, so firmware built for it can fix this. The first responder's registers"
                      + " 0x02/0x03 read ").append(idStr).append(". Note the address and"
                      + " these bytes down.");
        }
        sb.append("\n\nAnswered at: ").append(list).append(".");
        return sb.toString();
    }

    /** Decode the scan+fingerprint SENSOR.TXT (0xA6 0x01, a responder count, then for each responder its 7-bit
     *  address and a window of ID registers: 0x00..0x1F then 0xF0..0xFF). Gives the same dead/mismatch/SP140A verdict
     *  as the scan, then shows each responder's registers so the exact part can be named from a datasheet. */
    private String decodeFingerprint(byte[] b) {
        final int REC = 49;   // 1 address byte + 48 register bytes
        int count = b[2] & 0xFF;
        java.util.List<Integer> addrs = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            int off = 3 + i * REC;
            if (off + REC > b.length) { count = i; break; }
            addrs.add(b[off] & 0xFF);
        }
        final int SP140A = 0x3C;   // 7-bit; bus write address 0x78
        if (addrs.isEmpty()) {
            return "The probe scanned every address on the sensor bus and nothing answered.\n\nThat points to a sensor"
                    + " that isn't responding at all (disconnected, unpowered or dead), not one at the wrong"
                    + " address. Reseat the sensor's ribbon and scan again. If it still answers nowhere, the sensor or"
                    + " its wiring is a hardware fault no firmware can fix.";
        }
        StringBuilder sb = new StringBuilder();
        int sp = addrs.indexOf(SP140A);
        if (sp >= 0) {
            int off = 3 + sp * REC;
            int r2 = b[off + 1 + 0x02] & 0xFF, r3 = b[off + 1 + 0x03] & 0xFF;
            boolean match = r2 == 0x14 && r3 == 0x0A;
            sb.append("A sensor answered at 0x3C (bus 0x78), the address the firmware drives, and its registers"
                    + " 0x02/0x03 read ").append(String.format(java.util.Locale.ROOT, "0x%02X 0x%02X", r2, r3))
              .append(match ? ", the SP140A it expects.\n\n" : ", not the SP140A's 14 0A.\n\n")
              .append(match
                    ? "So the sensor is present and is the exact part the firmware expects, and a freeze isn't a"
                      + " sensor mismatch."
                    : "Something answers at the SP140A address but reports a different ID.");
        } else {
            int a = addrs.get(0);
            sb.append("A sensor answered at ")
              .append(String.format(java.util.Locale.ROOT, "0x%02X (bus 0x%02X)", a, a << 1))
              .append(", not the 0x3C (bus 0x78) the firmware drives.\n\n")
              .append("The camera has a different sensor from the one this firmware drives. It answers, just at"
                      + " another address, so firmware built for it can fix this.");
        }
        sb.append("\n\nRegister fingerprint (keep SENSOR.TXT: a datasheet can name the exact part from these bytes):");
        for (int i = 0; i < count; i++) {
            int off = 3 + i * REC;
            int a = b[off] & 0xFF;
            sb.append("\n\n").append(String.format(java.util.Locale.ROOT, "0x%02X (bus 0x%02X)", a, a << 1))
              .append("\n  00-1F: ").append(hexRange(b, off + 1, 32))
              .append("\n  F0-FF: ").append(hexRange(b, off + 1 + 32, 16));
        }
        return sb.toString();
    }

    /** Decode the 16-bit ID-read SENSOR.TXT (0xA7 0x01, a responder count, then per responder its 7-bit address and
     *  two 16-bit register windows: 0x3000..0x301F then 0x3100..0x310F). Reads the real chip-ID registers of a
     *  16-bit-addressed sensor (OmniVision at 0x300A/0x300B/0x300C, SmartSens at 0x3107/0x3108) and names the part.
     *  Absent registers read 0xFF here (the probe pre-fills 0xFF and the bus routine leaves it on a NACK), not 0x00. */
    private String decodeFingerprint16(byte[] b) {
        final int REC = 49;   // 1 address byte + 32 (0x3000..0x301F) + 16 (0x3100..0x310F)
        int count = b[2] & 0xFF;
        java.util.List<Integer> addrs = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            int off = 3 + i * REC;
            if (off + REC > b.length) { count = i; break; }
            addrs.add(b[off] & 0xFF);
        }
        if (addrs.isEmpty()) {
            return "The probe scanned every address on the sensor bus and nothing answered.\n\nThat points to a sensor"
                    + " that isn't responding at all (disconnected, unpowered or dead). Reseat the sensor's ribbon"
                    + " and try again. If it still answers nowhere, it's a hardware fault no firmware can fix.";
        }
        final int SP140A = 0x3C;   // 7-bit; bus write address 0x78
        StringBuilder sb = new StringBuilder();
        if (addrs.indexOf(SP140A) >= 0) {
            sb.append("A sensor answered at 0x3C (bus 0x78), the address the firmware drives.\n\n");
        } else {
            sb.append("A sensor answered at ").append(hexAddr(addrs.get(0)))
              .append(", not the 0x3C (bus 0x78) the firmware drives. It answers, just at another address.\n\n");
        }
        for (int i = 0; i < count; i++) {
            int off = 3 + i * REC;
            sb.append(hexAddr(b[off] & 0xFF)).append(": ").append(identify16(b, off)).append('\n');
        }
        boolean ov9732 = false;
        for (int i = 0; i < count; i++) {
            int off = 3 + i * REC;
            if ((b[off + 1 + 0x0A] & 0xFF) == 0x97 && (b[off + 1 + 0x0B] & 0xFF) == 0x32) ov9732 = true;
        }
        sb.append(ov9732
                ? "\nThat's the sensor the factory build v012 drives. Write a v012 build (stock or with mods) from"
                  + " the card (" + q(routeTitle(Route.REPAIR)) + "). The stock restore file can't take pictures on it."
                : "\nThe app can't tell from this which base fits. Keep SENSOR.TXT and open an issue on the project's"
                  + " GitHub page with it. The fingerprint below is what naming the part from a datasheet needs. The"
                  + " probe stays on the camera until you write another firmware, so write what the camera ran"
                  + " before.");
        sb.append("\n\nRegister fingerprint (16-bit reads; FF = no answer):");
        for (int i = 0; i < count; i++) {
            int off = 3 + i * REC;
            sb.append("\n\n").append(hexAddr(b[off] & 0xFF))
              .append("\n  3000-301F: ").append(hexRange(b, off + 1, 32))
              .append("\n  3100-310F: ").append(hexRange(b, off + 1 + 32, 16));
        }
        return sb.toString();
    }

    /** Name the part at one responder from its 16-bit chip-ID registers, or explain why it could not be named. */
    private static String identify16(byte[] b, int off) {
        int w1 = off + 1;          // 0x3000..0x301F
        int w2 = off + 1 + 32;     // 0x3100..0x310F
        int r300A = b[w1 + 0x0A] & 0xFF, r300B = b[w1 + 0x0B] & 0xFF, r300C = b[w1 + 0x0C] & 0xFF;
        int r3107 = b[w2 + 0x07] & 0xFF, r3108 = b[w2 + 0x08] & 0xFF;
        int ov = -1;
        if (r300A != 0x00 && r300A != 0xFF) {
            ov = (r300A << 8) | r300B;                  // e.g. 0x2680, 0x5647
        } else if (r300A == 0x00 && r300B != 0x00 && r300B != 0xFF) {
            ov = (r300B << 8) | r300C;                  // OV8865-style: 0x300A reads 0x00
        }
        if (ov > 0) {
            String known = ovName(ov);
            return "OmniVision " + (known != null ? known
                    : String.format(java.util.Locale.ROOT, "OV%04X (not in the app's list)", ov))
                    + String.format(java.util.Locale.ROOT, "  [0x300A/B/C = %02X %02X %02X]", r300A, r300B, r300C);
        }
        if (r3107 != 0x00 && r3107 != 0xFF) {
            return String.format(java.util.Locale.ROOT, "SmartSens SC%02X%02X  [0x3107/8 = %02X %02X]",
                    r3107, r3108, r3107, r3108);
        }
        boolean allFF = true, all00 = true, allSame = true;
        int first = b[w1] & 0xFF;
        for (int k = 0; k < 48; k++) {
            int v = b[w1 + k] & 0xFF;
            if (v != 0xFF) allFF = false;
            if (v != 0x00) all00 = false;
            if (v != first) allSame = false;
        }
        if (allFF) {
            return "no 16-bit register answered (all FF). The part likely uses 8-bit register addresses, and the"
                    + " probe can be switched to an 8-bit ID read of 0x00..0x1F and 0xF0..0xFF.";
        }
        if (all00) {
            return "answered, but the 16-bit ID registers all read 00. The sensor's core may be held unclocked, or"
                    + " its ID is at a register other than 0x300A/0x3107.";
        }
        if (allSame) {
            return String.format(java.util.Locale.ROOT, "all registers read %02X, likely the wrong sub-address"
                    + " width for this part.", first);
        }
        return "ID not recognized at 0x300A/0x300B (OmniVision) or 0x3107/0x3108 (SmartSens). Keep SENSOR.TXT: its"
                + " bytes are what a datasheet comparison needs.";
    }

    /** Known OmniVision chip IDs (0x300A/0x300B, or 0x300B/0x300C when 0x300A reads 0), small point-and-shoot parts. */
    private static String ovName(int id) {
        switch (id) {
            case 0x2680: return "OV2680";
            case 0x2685: return "OV2685";
            case 0x2640: return "OV2640";
            case 0x5640: return "OV5640";
            case 0x5647: return "OV5647";
            case 0x5648: return "OV5648";
            case 0x8865: return "OV8865";
            case 0x9732: return "OV9732";
            case 0x7670: return "OV7670";
            default: return null;
        }
    }

    /** "0x36 (bus 0x6C)" for a 7-bit sensor address. */
    private static String hexAddr(int a) {
        return String.format(java.util.Locale.ROOT, "0x%02X (bus 0x%02X)", a, (a << 1) & 0xFF);
    }

    /** n register bytes from data[start] as space-separated hex, double-spaced every 8. */
    private static String hexRange(byte[] data, int start, int n) {
        StringBuilder h = new StringBuilder();
        for (int i = 0; i < n; i++) {
            h.append(i == 0 ? "" : (i % 8 == 0 ? "  " : " "))
             .append(String.format(java.util.Locale.ROOT, "%02X", data[start + i] & 0xFF));
        }
        return h.toString();
    }

    // --- cleaning up --------------------------------------------------------------------------------------------

    private void confirmClean() {
        final Card c = card;
        if (busy() || archiving() || dialog != null || c == null || !c.state.listed
                || c.state.updateFiles.isEmpty()) {
            return;
        }
        // Exactly the files the dialog names are removed.
        final List<CardState.Item> files = new ArrayList<>(c.state.updateFiles);
        StringBuilder names = new StringBuilder();
        for (CardState.Item f : files) {
            names.append(f.name).append('\n');
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Remove from the card?")
                .setMessage("The app deletes " + (files.size() == 1 ? "this file" : "these files") + " from the card's"
                        + " top folder:\n" + names + "\nNothing else is deleted. Photos stay on the card.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (di, w) -> clean(c.tree, files))
                .create();
        d.setOnDismissListener(di -> {
            if (dialog == di) {
                dialog = null;
            }
            if (!gone()) {
                afterDialog();
            }
        });
        dialog = d;
        d.show();
        updateButtons();
    }

    private void clean(Uri t, List<CardState.Item> files) {
        if (busy() || archiving()) {
            return;
        }
        final Operation op = start(false, files.size());
        setStatus(cleanStatus, REMOVING);
        writeStatus.setText("");          // a result about the write before the flash no longer applies
        sensorStatus.setText("");         // a sensor result from a previous flash no longer applies
        ejectStatus.setText("");          // an eject before this removal no longer applies
        ejectAsked = false;
        updateButtons();
        WORKER.execute(() -> {
            String outcome = null;
            boolean all = true;
            try {
                StringBuilder sb = new StringBuilder();
                StringBuilder reasons = new StringBuilder();
                for (CardState.Item f : files) {
                    String failed;
                    try {
                        failed = delete(DocumentsContract.buildDocumentUriUsingTree(t, f.documentId));
                    } catch (RuntimeException e) {
                        failed = why(e);
                    }
                    if (failed == null) {
                        sb.append("Removed ").append(f.name).append(".\n");
                    } else {
                        all = false;
                        sb.append("Could not remove ").append(f.name).append(".\n");
                        reasons.append(reasons.length() == 0 ? "" : "; ").append(f.name).append(": ").append(failed);
                    }
                }
                String headline = !all ? "Not everything was removed."
                        : files.size() == 1 ? "The update file is removed ✓" : "The update files are removed ✓";
                outcome = headline + "\n" + sb + "\n" + readCard(t).state.afterwards(true) + detail(reasons.toString());
            } finally {
                finish(op, outcome != null ? outcome : "Removing stopped with an unexpected error. The Camera drive"
                        + " step shows what the card holds now. Don't switch the camera on with this card in it"
                        + " while it lists an update file.", outcome != null && all);
            }
        });
    }
}
