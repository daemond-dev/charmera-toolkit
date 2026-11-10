package io.github.daemonddev.charmera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Puts a verified Charmera update file in the root of an SD card the phone can see, through Android's own folder
 * picker (the Storage Access Framework), so no storage permission and no root are needed. The camera then
 * flashes itself from the card. Also removes update files from the card afterwards.
 *
 * No backup of the camera, no flash: the app writes to the card only while a dump of that camera's own flash chip is
 * on record (step 0), and only an update file that it has compared with that dump; WriteGate decides, from the check
 * and the backup on record now. A file identical to the public dump proves nothing about the camera it goes into; the
 * comparison with the firmware in a dump read before the camera was ever flashed is the one check that does.
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
    private static final String DOWNLOAD = "Download the stock restore file";
    private static final String MAKE_PROBE = "Make the sensor-ID probe";
    private static final String MAKE_BRINGUP = "Make the OV9732 bring-up";
    private static final String READ_RESULT = "Read the sensor result from the card";
    private static final String USE_FILE = "Use a file on the phone";
    private static final String SAVE = "Save a copy to the phone";
    private static final String CHOOSE = CardState.CHOOSE_BUTTON;
    private static final String WRITE = "Write the update file to the card";
    private static final String EJECT = "Eject the drive (opens Android's storage settings)";
    private static final String REMOVE = CardState.REMOVE_BUTTON;
    /** Where new versions of the app are published; the footer links to it. */
    private static final String RELEASES_PAGE = "https://github.com/daemond-dev/charmera-toolkit/releases";
    private static final String UPDATE_APK_URL = "https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk";
    private static final String OPEN_SETTINGS = "Open storage settings";
    /** fetch's failure when GitHub answered, but not with the file; the download's message tells it apart. */
    private static final String GITHUB_REFUSED = "GitHub did not send the file";
    /** How a write that worked begins; write() offers the eject screen only after it. */
    private static final String WRITTEN = "Done: the update file is on the card.";
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
        /** A screen has shown how it ended and offered the eject screen; a later screen does not show it again. */
        boolean shown;

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
    private Button backupButton;
    private TextView fileStatus;
    /** The technical part under fileStatus: the checks, sums and Android's error words. Behind a "Details" toggle. */
    private TextView fileDetail;
    /** The "Details" toggle that shows or hides fileDetail; hidden when there is no detail. */
    private TextView fileDetailToggle;
    private TextView saveStatus;
    private TextView cardStatus;
    private TextView writeStatus;
    private Button writeButton;
    private Button cleanButton;
    private Button downloadButton;
    private Button ejectButton;
    private Button chooseButton;
    private TextView cleanStatus;
    private Button saveButton;
    private Button probeButton;
    private Button bringupButton;
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
    /** The public stock dump once downloaded or picked and checked; kept so choosing again needs no new download. */
    private byte[] stockDump;
    private boolean downloading;

    private byte[] fileData;
    private UpdateCheck.Result fileCheck;
    /** How the file in step 1 is described above its checks, and whether the owner chose it, for checking it again. */
    private String fileName;
    private String fileOrigin;
    private boolean fileChosen;
    /** Bumped by every loadFile, so a slow read of an earlier file cannot replace a later one. */
    private int fileRequest;
    /** The file in step 1 is being checked again, against the backup on record now; nothing is written meanwhile. */
    private volatile boolean checking;
    /** Bumped by every check of the file again, so only the latest one is shown. */
    private int checkRequest;
    private Uri tree;
    private Card card;
    /** Bumped by every inspectCard, so only the latest look at the card is shown. */
    private int cardRequest;
    private boolean inspecting;
    /** A comparison of the update file with the copy on the card is running (a write or removal is an Operation). */
    private boolean comparing;
    /** The write or removal whose progress this screen shows, until it has shown how it ended. */
    private Operation watching;
    /**
     * The backup (SHA-256 and name) the file step 3 says is on the card was checked against, and the one the copy
     * step 1 says was saved was checked against: once another backup is on record, those messages no longer hold.
     */
    private String writtenWith;
    private String writtenName;
    private String savedWith;
    private String savedName;
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

    // --- layout -------------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.app_name);
        resolveTheme();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(windowBg);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);
        this.col = col;

        banner();

        LinearLayout s0 = stepCard("0", "Back up the camera", "Read this camera's flash to a 2 MB file (CH341A clip on"
                + " a computer) and choose it here. Required before any write.");
        backupButton = primary(BACKUP, v -> pickBackup());
        s0.addView(backupButton);
        backupStatus = body("");
        s0.addView(backupStatus);
        expander(s0, "Why a backup is required", "Firmware that does not fit cannot be reversed without a chip dump"
                + " taken before the first flash. Cameras of the same series can use different image sensors, and"
                + " nothing on screen shows which. The app accepts only a full dump that is not a public copy or an"
                + " \"unverified-read\", and cannot tell which camera a dump came from — so make sure it is this"
                + " one's. A dump read after a flash holds what was flashed, not what fits; when that is a public"
                + " build, the app says so.");

        LinearLayout s1 = stepCard("1", "Get the update file", "Download the public 2 MB dump; the stock restore file"
                + " is built from it.");
        downloadButton = primary(DOWNLOAD, v -> downloadDump(Candidates.Kind.RESTORE));
        s1.addView(downloadButton);
        s1.addView(linkText("Open the download page \u2197", Candidates.STOCK_DUMP_PAGE));
        s1.addView(button(USE_FILE, v -> pickFile()));
        saveButton = button(SAVE, v -> saveCopy());
        s1.addView(saveButton);
        saveStatus = body("");
        s1.addView(saveStatus);
        fileStatus = body("");
        s1.addView(fileStatus);
        fileDetail = mono();
        fileDetail.setVisibility(View.GONE);
        fileDetailToggle = detailToggle(fileDetail, "Details");
        s1.addView(fileDetailToggle);
        s1.addView(fileDetail);
        expander(s1, "Already have the file, or using the phone's SD slot?", "Have the dump or an update file already?"
                + " Tap " + q(USE_FILE) + " and choose it. Android 11+ with the card in the phone's own slot: tap "
                + q(SAVE) + ", copy the file onto the card with the Files app (step 2), then " + q(USE_FILE) + " to check"
                + " it. The stock restore file is a public copy of a working camera's memory under its published name;"
                + " matching it says nothing about your camera, so the app still checks it against your step 0 backup"
                + " before writing.");
        divider(s1);
        s1.addView(subTitle("Identify the image sensor"));
        s1.addView(body("Diagnostic only: writes the sensor's ID to SENSOR.TXT. It does not repair. No backup needed."));
        probeButton = primary(MAKE_PROBE, v -> confirmMakeProbe());
        s1.addView(probeButton);
        expander(s1, "Details", "When the camera next enters photo mode it scans the sensor bus, writes the result to"
                + " SENSOR.TXT, and stops as it does now. Read it with " + q(READ_RESULT) + " in step 4. It changes no"
                + " boot or update code, only a dead debug routine and one sensor-setup call, so the stock restore file"
                + " reverses it.");
        divider(s1);
        s1.addView(subTitle("Try the OV9732 bring-up (experimental)"));
        s1.addView(body("Drives the OV9732 so the camera can take photos. The stock restore file reverts it. No backup"
                + " needed."));
        bringupButton = primary(MAKE_BRINGUP, v -> confirmMakeBringup());
        s1.addView(bringupButton);
        expander(s1, "Details", "Once the probe names the sensor as an OmniVision OV9732, this drives it in place of the"
                + " SP140A the firmware expects, then lets the camera run so you can try a photo. It may not be right on"
                + " the first try, but it cannot damage the camera; like the probe it changes no boot or update code, so"
                + " the stock restore file puts it back.");

        LinearLayout s2 = stepCard("2", "Connect the camera", null);
        s2.addView(caution("Plugging the cable into a switched-off camera turns it on, and it will flash any update file"
                + " on the card. Connect it in this order:"));
        s2.addView(body("1. Switch the camera off and take the card out.\n"
                + "2. Switch it on with the power key; wait 10 seconds (it only looks for an update file in the first"
                + " five).\n"
                + "3. Put the card back in, then connect the camera to the phone with a USB-C data cable.\n"
                + "4. Tap " + q(CHOOSE) + ", open the camera's drive, and tap \"Use this folder\" in its top folder"
                + " — not inside DCIM."));
        chooseButton = primary(CHOOSE, v -> pickCard());
        s2.addView(chooseButton);
        cardStatus = body("");
        s2.addView(cardStatus);
        expander(s2, "Card reader or the phone's SD slot", "A USB card reader works the same way. The phone's own slot"
                + " works only on Android 7–10; from Android 11 the system will not let an app pick a card's top"
                + " folder in the slot. For the slot on Android 11+: tap " + q(SAVE) + " in step 1, copy that file onto"
                + " the card with the Files app, then tap " + q(USE_FILE) + " and choose the copy to have it checked."
                + " No drive in the picker? See the last card.");

        LinearLayout s3 = stepCard("3", "Write to the card", "Tap " + q(WRITE) + " once step 0 has a backup, step 1 says"
                + " \"OK to put on the card\", and step 2 shows the card is ready. The file is copied to the card's top"
                + " folder and read back to verify. Nothing changes on the camera yet.");
        writeButton = primary(WRITE, v -> confirmWrite());
        s3.addView(writeButton);
        writeStatus = body("");
        s3.addView(writeStatus);
        ejectButton = button(EJECT, v -> openStorageSettings());
        s3.addView(ejectButton);

        LinearLayout s4 = stepCard("4", "Flash", "Eject the drive before unplugging: tap " + q(EJECT) + " in step 3, tap"
                + " the USB drive, then \"Eject\" (or use Android's USB notification). With a reader or the slot, eject"
                + " and put the card in the camera.");
        s4.addView(body("1. Confirm the backup is safe, it is an original 2025-series camera, and the battery is full.\n"
                + "2. Switch the camera off, then on with the power key, card in it. It shows \"Upgrade\" with a"
                + " percentage for about 15–20 seconds — do not touch anything until 100%."));
        s4.addView(caution("At 100% the camera stays on its last screen; it does not switch off or delete the update"
                + " file. Take the card out first, then hold the power key about a second to switch off."));
        expander(s4, "No \"Upgrade\" screen, or \"Program error\"", "No \"Upgrade\" and it starts normally? It did not"
                + " find the file — reconnect as in step 2, confirm step 2 lists the update file, then eject and"
                + " retry; if it is not listed, write it again in step 3. \"Program error\" means the camera found the"
                + " file damaged and ignored it; nothing was erased. Remove the file (step 5) and write it again.");
        divider(s4);
        s4.addView(subTitle("Read the sensor result"));
        s4.addView(body("Flashed the sensor-ID probe? Take the update file off the card (step 5) so the camera boots the"
                + " probe instead of re-flashing, open photo mode so it writes SENSOR.TXT, then tap " + q(READ_RESULT)
                + "."));
        expander(s4, "Details", "While an update file is on the card the camera re-flashes it every boot instead of"
                + " running it. With it gone, the probe boots; photo mode stops as before but scans the sensor bus and"
                + " writes SENSOR.TXT first. Bring the card back and tap " + q(CHOOSE) + " in step 2 if needed.");
        readResultButton = primary(READ_RESULT, v -> readSensorResult());
        s4.addView(readResultButton);
        sensorStatus = body("");
        s4.addView(sensorStatus);

        LinearLayout s5 = stepCard("5", "Clean up", null);
        s5.addView(caution("The update file is still on the card, and the camera would flash it again at every"
                + " power-on. Remove it now."));
        s5.addView(body("1. Keep the card out of the camera. Switch the camera on with the power key (not by plugging"
                + " in the cable) and wait 10 seconds.\n"
                + "2. Put the card in, connect the camera, and tap " + q(REMOVE) + ". Greyed out? Tap " + q(CHOOSE)
                + " and pick the drive again."));
        cleanButton = primary(REMOVE, v -> confirmClean());
        s5.addView(cleanButton);
        cleanStatus = body("");
        s5.addView(cleanStatus);
        TextView after = body("Once it is gone: eject, unplug, switch the camera off and on, and check the boot screen,"
                + " live view, and a test photo.");
        after.setPadding(0, dp(10), 0, dp(2));
        s5.addView(after);
        expander(s5, "Phone's own SD slot (Android 11+)", "Put the card in the phone, delete the update file from its"
                + " top folder with the Files app, and eject the card before removing it.");

        LinearLayout tools = card();
        tools.addView(subTitle("Compare test shots"));
        tools.addView(body("A fixed reference card to photograph after each flash, so colour and orientation are always"
                + " compared against the same image."));
        tools.addView(primary("Open calibration target", v ->
                startActivity(new Intent(this, CalibrationActivity.class))));
        expander(tools, "How to use it", "In a dark room, turn off auto-brightness and night-light, fill the frame with"
                + " the card, and keep the phone flat. It is a consistent target, not a lab-calibrated one.");

        LinearLayout help = card();
        expander(help, "The drive does not show up", "Check the cable first — some USB-C cables only charge."
                + " Android may refuse a card the camera has used (it leaves a flag set on the card) and offer to"
                + " format it; formatting erases everything, so copy your photos off first. On a computer, the toolkit"
                + " can clear the flag without erasing. See docs/05-CARDS.md and docs/06-ANDROID.md.");

        TextView footer = body("Charmera Card " + appVersion());
        footer.setTextColor(disabledText);
        footer.setPadding(dp(4), dp(10), dp(4), 0);
        col.addView(footer);
        TextView check = body("Check for updates");
        check.setTextColor(accent);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setTextIsSelectable(false);
        check.setPadding(dp(4), dp(4), dp(4), dp(2));
        check.setOnClickListener(v -> checkForUpdate());
        col.addView(check);
        updateStatus = body("");
        updateStatus.setPadding(dp(4), 0, dp(4), dp(2));
        col.addView(updateStatus);
        col.addView(linkText("Open the releases page \u2197", RELEASES_PAGE));

        scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        setContentView(scroll);

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null);
        if (saved != null && hasPermission(Uri.parse(saved))) {
            tree = Uri.parse(saved);
            setStatus(cardStatus, "Looking at the card…");
        } else {
            setStatus(cardStatus, "No drive chosen yet.");
        }
        showFile("No update file yet.", null);
        setStatus(backupStatus, "No backup on record. The app will not write to the card without one.");
        String savedBackup = state == null ? null : state.getString(STATE_BACKUP_URI);
        if (savedBackup != null) {
            loadBackup(Uri.parse(savedBackup));     // the screen was recreated (dark mode, font size): read it again
        }
        // A write or removal another screen started may still run, or have ended with no screen open to show it.
        if (last != null && !last.shown) {
            watching = last;
        }
        updateButtons();
        uiReady = true;     // from here a new file result scrolls itself into view (not the initial empty state)
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
        if (backupUri != null && backup != null) {
            out.putString(STATE_BACKUP_URI, backupUri.toString());   // 2 MB do not fit a Bundle; the file is read again
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        screen = this;
        catchUp();
        // The card may have been inserted, removed or changed while the app was in the background.
        if (tree != null && !busy() && !inspecting && dialog == null) {
            inspectCard();
        }
    }

    @Override
    protected void onDestroy() {
        dismissDialog();
        if (screen == this) {
            screen = null;
        }
        super.onDestroy();
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
     * off; once it has ended, how, under its button, then the card is looked at again and the eject screen offered.
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
        setStatus(status, op.outcome);
        if (op.write && op.succeeded) {
            writtenWith = op.backupSha256;
            writtenName = op.backupName;
        }
        inspectCard();
        if (op.succeeded && first) {
            if (op.write) {
                done("The update file is on the card", "After ejecting, unplug the cable, or take the card out of"
                        + " the reader, and go on with step 4.");
            } else {
                done(op.files == 1 ? "Update file removed" : "Update files removed", "If you have just"
                        + " flashed: after ejecting, unplug the cable. Then switch the camera off and on again, and"
                        + " check the boot screen, the live view and a test photo.\n"
                        + "If you removed an old update file before writing: tap \"Later\" and go on with step 3.");
            }
        }
        updateButtons();
    }

    /**
     * Android does not let an app eject a drive, so this opens the storage settings, where the USB drive or card has
     * an Eject entry.
     */
    private void openStorageSettings() {
        String[] actions = {android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
                android.provider.Settings.ACTION_MEMORY_CARD_SETTINGS, android.provider.Settings.ACTION_SETTINGS};
        for (String action : actions) {
            try {
                startActivity(new Intent(action));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // try the next, more general settings screen
            }
        }
        android.widget.Toast.makeText(this, "Android's storage settings did not open. Open Settings yourself, then"
                + " Storage, and eject the drive there.", android.widget.Toast.LENGTH_LONG).show();
    }

    /** After a write or a removal: say it where the owner is looking, then offer the eject screen. */
    private void done(String message, String next) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        if (dialog != null) {
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(message)
                .setMessage("Now eject the drive, before you unplug the cable or take the card out. Ejecting makes sure"
                        + " everything written reaches the card.\n"
                        + "Android does not let apps eject a drive, so " + q(OPEN_SETTINGS) + " opens Android's storage"
                        + " settings. Tap the USB drive or card there, then \"Eject\". Or tap \"Eject\" in the"
                        + " notification Android shows for the USB drive.\n\n" + next)
                .setPositiveButton(OPEN_SETTINGS, (di, w) -> openStorageSettings())
                .setNegativeButton("Later", null)
                .create();
        d.setOnDismissListener(di -> {
            if (dialog == di) {
                dialog = null;
            }
            if (!gone()) {
                updateButtons();
            }
        });
        dialog = d;
        d.show();
        updateButtons();
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
            warnAccent = 0xFFB06A00;
            windowBg = 0xFFF3F3F4;
            cardFill = 0xFFFFFFFF;
            cardStroke = 0xFFE3E3E6;
            disabledFill = 0xFFDCDCE0;
            disabledText = 0xFF8C8C8C;
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

    /** A card headed by a round step number and a bold title, then an optional line of instruction. */
    private LinearLayout stepCard(String number, String title, String instruction) {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.addView(badge(number));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(textPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t);
        c.addView(head);
        if (instruction != null) {
            TextView ins = body(instruction);
            ins.setPadding(0, dp(10), 0, dp(2));
            c.addView(ins);
        }
        return c;
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

    /** A faint rule inside a card, setting a secondary block apart from the main action. */
    private void divider(LinearLayout parent) {
        View line = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(4);
        line.setLayoutParams(lp);
        line.setBackgroundColor(cardStroke);
        parent.addView(line);
    }

    /** A bold sub-heading inside a card. */
    private TextView subTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(textPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(2));
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

    /** The one hard rule, in a card with an amber title, at the top of the screen. */
    private void banner() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(cautionFill);
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(1)), cautionStroke);
        c.setBackground(bg);
        int bp = dp(16);
        c.setPadding(bp, bp, bp, bp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(12);
        c.setLayoutParams(blp);
        col.addView(c);
        TextView t = new TextView(this);
        t.setText("Before you flash");
        t.setTextColor(warnAccent);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        c.addView(t);
        TextView rule = body("No backup of this camera, no flash. Original 2025 series only — matte body, yellow"
                + " Kodak boot screen; never a Millennium Edition (blue boot screen) or a CLOT edition. Charge the"
                + " battery fully first.");
        rule.setPadding(0, dp(6), 0, dp(2));
        c.addView(rule);
        expander(c, "What a flash does to your photos", "A longer file (1,966,080 bytes, like the stock restore file)"
                + " replaces the two photos the camera keeps in its own memory. A shorter one (1,478,656 bytes) keeps"
                + " them but clears the list, so the camera stops showing them either way. To keep them, copy the"
                + " SPIDCIM folder off the card first, with the Files app or a computer.");
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

    private TextView mono() {
        TextView t = body("");
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return t;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Scroll the main view so a freshly-shown result is on screen, not left below the fold. Bubbles up to the
     *  enclosing ScrollView; a no-op if the view has no size yet, so it is posted after layout. */
    private void reveal(final View v) {
        if (v == null) {
            return;
        }
        v.post(() -> v.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()), false));
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
                    setStatus(updateStatus, "No app update has been published yet, so there is nothing to install. This"
                            + " button will find one once a release is published.");
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
                .setMessage("Version " + remoteName + " is available; you have " + appVersion() + ". The update is"
                        + " downloaded. Tap Update to install it \u2014 Android will ask you to confirm.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Update", (di, w) -> installUpdate())
                .create();
        d.setOnDismissListener(di -> {
            if (dialog == di) {
                dialog = null;
            }
            if (!gone()) {
                updateButtons();
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
                    .setMessage("To install updates, allow this app to install apps. Android will open its settings;"
                            + " turn it on, come back, and tap Check for updates again.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (di, w) -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            setStatus(updateStatus, "Could not open the setting. Turn on \"install unknown apps\" for"
                                    + " this app in Android settings, then tap Check for updates again.");
                        }
                    })
                    .create();
            d.setOnDismissListener(di -> {
                if (dialog == di) {
                    dialog = null;
                }
                if (!gone()) {
                    updateButtons();
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
        boolean cardOk = card != null && card.state.writable();
        writeButton.setEnabled(idle && fileOk && cardOk);
        cleanButton.setEnabled(idle && card != null && card.state.listed && !card.state.updateFiles.isEmpty());
        downloadButton.setEnabled(idle && !downloading);
        probeButton.setEnabled(idle && !downloading);
        bringupButton.setEnabled(idle && !downloading);
        readResultButton.setEnabled(idle && !readingSensor && card != null && card.state.listed);
        backupButton.setEnabled(idle && !loadingBackup);
        saveButton.setEnabled(idle && fileOk);
        // Another drive picked during a write would show the write's result under the wrong drive.
        chooseButton.setEnabled(!busy());
        // Ejecting while a write or a removal runs would cut it short.
        ejectButton.setEnabled(!busy());
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
        setStatus(fileStatus, summary == null ? "" : summary.trim());
        showDetail(technical);
        // The probe/bring-up/download buttons sit far below this readout; scroll it into view so the result is seen
        // without hunting back up the page.
        if (uiReady && scroll != null) {
            fileStatus.post(() -> fileStatus.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, fileStatus.getWidth(), fileStatus.getHeight()), false));
        }
    }

    /** Fills step 1's detail block (hidden behind the Details toggle, collapsed on every new readout). */
    private void showDetail(String technical) {
        boolean none = technical == null || technical.trim().isEmpty();
        fileDetail.setText(none ? "" : technical.trim());
        fileDetail.setVisibility(View.GONE);            // new content starts collapsed, behind the Details toggle
        fileDetailToggle.setText("\u25b8 Details");
        fileDetailToggle.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    /** The "Detail:" block for step 1's readout, or null when there is no detail. */
    private static String detailBlock(String d) {
        return d == null || d.trim().isEmpty() ? null : "Detail: " + d.trim();
    }

    /** What to do after step 1 has a file that passed. A file the owner chose may already be a copy on the card. */
    private static String nextAfterFile(boolean chosen) {
        return chosen ? "Next: go on with step 2. If this copy is already on the card, eject the card and go on with"
                + " step 4." : "Next: go on with step 2.";
    }

    /** The withdrawn candidate (C, A or B) these bytes are, by SHA-256, or null. */
    private static Candidates.Kind withdrawnKind(String sha) {
        for (Candidates.Kind k : Candidates.Kind.values()) {
            if (!k.offered && k.sha256.equals(sha)) {
                return k;
            }
        }
        return null;
    }

    /** Whether a checked file is the sensor-ID probe, by its reviewed SHA-256: a file written without a backup. */
    private static boolean isProbe(UpdateCheck.Result result) {
        return result != null && Candidates.Kind.PROBE.sha256.equals(result.sha256);
    }

    /** Whether a checked file is the OV9732 bring-up, by its reviewed SHA-256. */
    private static boolean isBringup(UpdateCheck.Result result) {
        return result != null && Candidates.Kind.BRINGUP.sha256.equals(result.sha256);
    }

    /** Whether a checked file is one of the reviewed patches (probe or bring-up): the files written without a backup. */
    private static boolean isReviewedPatch(UpdateCheck.Result result) {
        return result != null && Candidates.isReviewedPatch(result.sha256);
    }

    /** How many WARN lines a checked file produced, for the one-line status in step 1. */
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
            setStatus(backupStatus, "Android could not open its file picker, so the backup cannot be chosen. Without it"
                    + " the app does not write to the card." + detail(why(e)));
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
                            ? " If it is a read of this camera, keep it: it is the way back to what the camera runs now."
                            + " It cannot show what fits the camera, so the app has nothing to compare an update file"
                            + " with, and does not write to the card. A camera that can no longer take pictures since a"
                            + " flash needs the donor route, on a computer (docs/02-RECOVERY.md, section D)."
                            : " The app needs the 2 MB file the toolkit's backup command read from this camera's flash"
                            + " chip. Tap " + q(BACKUP) + " to choose it.") + "\n"
                            + "File: " + name + ".";
                } else {
                    StringBuilder notes = new StringBuilder();
                    for (String note : UpdateCheck.backupNotes(data, UpdateCheck.PUBLIC_BUILD)) {
                        notes.append('\n').append(note);
                    }
                    text = "Backup on record: " + name + ", " + UpdateCheck.bytes(data.length) + ". The app compares the"
                            + " update file with it in step 1, and writes to the card only while this backup is here."
                            + " Keep the file itself somewhere safe: it is the only way back to what the camera runs now."
                            + notes + detail("SHA-256 " + sha + ".");
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
                backup = d;
                backupSha256 = h;
                backupName = d == null ? null : n;
                backupUri = d == null ? null : uri;
                setStatus(backupStatus, t);
                if (!Objects.equals(before, h)) {
                    dismissWriteDialog();
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
            setStatus(writeStatus, "The update file on the card was checked against the backup chosen before, "
                    + writtenName + ", and step 0 has changed since. Unless step 1 says \"OK to put on the card\" again,"
                    + " remove the file from the card (step 5) before the card goes back into the camera.");
            writtenWith = null;
        }
        String saved = saveStatus.getText().toString();
        if (savedWith != null && !savedWith.equals(now) && saved.startsWith(SAVED)) {
            setStatus(saveStatus, "The copy saved to the phone was checked against the backup chosen before, "
                    + savedName + ", and step 0 has changed since. Do not put it on the card unless step 1 says \"OK to"
                    + " put on the card\" again.");
            savedWith = null;
        }
    }

    /**
     * Checks the file in step 1 again, against the backup on record now, whenever its check was made against another
     * one (or none). Until the new check is in, nothing is written or saved (WriteGate), and step 1 says so.
     */
    private void recheckFile() {
        final byte[] data = fileData;
        final String name = fileName;
        if (data == null || name == null) {
            checking = false;
            return;
        }
        // The reviewed patches (probe, bring-up) are backup-independent; their check is never re-made against a backup.
        // This keys on the applied check (fileCheck), set and cleared together with fileData on the UI thread, so a
        // patch in fileData always has isReviewedPatch(fileCheck) true here (finding 7). Keep that lock-step if you
        // change how file state is set.
        if (isReviewedPatch(fileCheck)) {
            checking = false;
            return;
        }
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
        showFile("Checking the update file again, against the backup now on record…", null);
        updateButtons();
        WORKER.execute(() -> {
            final UpdateCheck.Result result = UpdateCheck.check(name, data, UpdateCheck.PUBLIC_BUILD, ref);
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
                showChecked(result, origin, chosen);
                updateButtons();
            });
        });
    }

    /**
     * Applies a check made on the worker to step 1, unless it was made against another backup than the one on record
     * now: then the file is kept and checked again, and the old check is never shown or used.
     */
    private void applyCheck(byte[] data, UpdateCheck.Result result, String name, String origin, boolean chosen) {
        fileData = data;
        fileName = name;
        fileOrigin = origin;
        fileChosen = chosen;
        // The reviewed patches (probe, bring-up) are backup-independent (WriteGate's exemption): each is checked with
        // no reference (buildRestore), so its check never needs re-checking against the backup on record, and must not
        // be — comparing it with a backup it was never meant to match would fail its sensor-table line and refuse a
        // file that needs no backup. Apply the patch's check as it stands.
        if (!isReviewedPatch(result) && !Objects.equals(result.referenceSha256, backupSha256)) {
            fileCheck = null;
            recheckFile();
            return;
        }
        fileCheck = result;
        showChecked(result, origin, chosen);
    }

    /** Step 1's readout for a file that was checked: the verdict, where the file came from, and what to do next. */
    private void showChecked(UpdateCheck.Result result, String origin, boolean chosen) {
        String state;
        int color;
        String next;
        if (!result.ok) {
            state = "\u2717 Not OK to put on the card.";
            color = badColor;
            next = "Tap Details to see what failed.";
        } else if (isProbe(result)) {
            state = "\u2713 Sensor-ID probe ready \u2014 no backup needed.";
            color = okColor;
            next = "Connect the camera (step 2) and tap " + q(WRITE) + ". After it flashes, open photo mode, then read"
                    + " it with " + q(READ_RESULT) + " in step 4.";
        } else if (isBringup(result)) {
            state = "\u2713 OV9732 bring-up ready \u2014 no backup needed.";
            color = okColor;
            next = "Connect the camera (step 2) and tap " + q(WRITE) + ". After it flashes, power the camera on and try"
                    + " a photo, then take the card out and look at the JPEGs. The stock restore file puts it back.";
        } else if (!result.compared) {
            state = "\u26a0 Needs this camera's backup first.";
            color = warnAccent;
            next = "Add it in step 0; the app compares the file with it before it will write.";
        } else {
            int warns = warnCount(result);
            state = "\u2713 OK to put on the card." + (warns == 0 ? ""
                    : " " + warns + (warns == 1 ? " warning" : " warnings") + " \u2014 see Details.");
            color = okColor;
            next = nextAfterFile(chosen);
        }
        SpannableString line = new SpannableString(state + "\n" + next);
        line.setSpan(new StyleSpan(Typeface.BOLD), 0, state.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new android.text.style.ForegroundColorSpan(color), 0, state.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        fileStatus.setText(line);
        reveal(fileStatus);
        showDetail(result.verdict() + "\n\n" + origin + "\n\nDetail: " + UpdateCheck.bytes(result.size) + ", SHA-256 "
                + result.sha256 + ".\n\nThe app's checks"
                + (result.compared ? ", compared with " + backupName : " (not compared with a backup)") + ":\n\n"
                + result.report());
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
                    + " camera's USB-C cable or a USB card reader instead (step 2)." + detail(why(e)));
        }
    }

    private void writeCopy(Uri uri) {
        final byte[] data = saveData;
        final UpdateCheck.Result check = saveCheck;
        saveData = null;
        saveCheck = null;
        // Checked again against the backup on record now: it may have changed while the save screen was open. A check
        // of another file in step 1 does not concern these bytes; a changed backup shows in its SHA-256.
        final String refusal = data == null ? "the app was restarted while the save screen was open (a dark-mode, font"
                + " or language change can do that), so it no longer had the file to save"
                : WriteGate.refusal(check, data, backupSha256, loadingBackup, false);
        if (refusal != null) {
            // The save screen has already made an empty file under the update file's name: it must not stay behind.
            setStatus(saveStatus, "Not saving the copy; removing the empty file the save screen made…");
            WORKER.execute(() -> {
                String name = displayName(uri);
                String failed = delete(uri);
                final String tt = "No copy was saved: " + sentence(refusal) + (failed == null
                        ? " The empty file the save screen made was deleted."
                        : " The save screen made an empty file, " + name + ", and the app could not delete it: delete"
                        + " it yourself, so it never goes on a card.") + " Then tap " + q(SAVE) + " again."
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
                    text = "Saved, but under another name: Android named the copy \"" + name + "\". The camera only"
                            + " uses the update file under its exact name. Rename the copy to " + check.cardName
                            + " before you copy it to the card.";
                } else {
                    text = "Saved " + name + " on the phone, and read it back: it is the same as the update file.\n"
                            + "Next: copy it to the card's top folder with the Files app, not into DCIM. Then tap "
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
                    outdated(backupSha256);   // the backup may have changed while the copy was written
                }
            });
        });
    }

    /** Download the public stock dump from its pinned upstream address, then make the chosen kind of file from it. */
    private void downloadDump(Candidates.Kind kind) {
        dismissDialog();
        final int request = ++fileRequest;
        fileData = null;
        fileCheck = null;
        fileName = null;
        checking = false;          // a check of the earlier file still running is dropped (fileRequest)
        saveStatus.setText("");
        if (!busy()) {
            writeStatus.setText("");
        }
        if (stockDump != null) {               // already downloaded and checked: make the file again without a new download
            buildRestore(stockDump, request, "Used the stock dump the app already has.", kind);
            return;
        }
        showFile("Downloading the stock dump from GitHub (2 MB)…", null);
        downloading = true;
        updateButtons();
        NET.execute(() -> {
            byte[] dump = null;
            String problem = null;
            String technical = null;
            final String browser = " Or open the stock dump's page above in the browser, download it there, and tap "
                    + q(USE_FILE) + ".";
            final String wrong = "The download arrived, but it is not the stock dump, so the app will not use it.\n"
                    + "A Wi-Fi network that asks you to sign in can cause this, or a download that stopped part way."
                    + " Open a web page in the browser to check the connection. Then tap " + q(DOWNLOAD) + " again.";
            try {
                dump = Candidates.stockDump(fetch(Candidates.STOCK_DUMP_DOWNLOAD));
            } catch (IOException e) {
                if (GITHUB_REFUSED.equals(e.getMessage())) {
                    problem = "The stock dump did not download: GitHub did not send the file. It may be busy.\n"
                            + "Wait a few minutes, then tap " + q(DOWNLOAD) + " again." + browser;
                    technical = detailOf(e);
                } else if (e instanceof Plain) {        // something arrived, but it cannot be the stock dump
                    problem = wrong;
                    technical = e.getMessage();
                } else {
                    problem = "The stock dump did not download: the phone could not reach GitHub, or the connection"
                            + " stopped.\nCheck the connection, then tap " + q(DOWNLOAD) + " again." + browser;
                    technical = detailOf(e);
                }
            } catch (IllegalArgumentException e) {
                String[] parts = split(e.getMessage());   // what arrived is not the stock dump; Candidates says why
                problem = wrong;
                technical = parts[1].isEmpty() ? firstSentence(parts[0]) : parts[1];
            } catch (RuntimeException e) {
                problem = "The stock dump did not download: the download stopped with an error.\nTap " + q(DOWNLOAD)
                        + " again." + browser;
                technical = why(e);
            }
            final byte[] d = dump;
            final String p = problem;
            final String x = technical;
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
                buildRestore(d, request, "Downloaded the stock dump from GitHub and checked it.", kind);
            });
        });
    }

    /** Explain the sensor-ID probe (a diagnostic, written without a backup), then build it on the owner's go-ahead. */
    private void confirmMakeProbe() {
        if (busy() || dialog != null || downloading) {
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Make the sensor-ID probe?")
                .setMessage("This is a diagnostic, not a repair. It will not make the camera take pictures.\n\n"
                        + "It is the one file the app puts on the card without a backup in step 0, because it is built"
                        + " from the public build and the stock restore file (step 1) puts it back: its boot and update"
                        + " code are that file's, byte for byte.\n\n"
                        + "After you flash it (steps 2 to 4) and open photo mode on the camera, it scans the sensor"
                        + " bus and writes a result to SENSOR.TXT on the card and the camera stops as it does now. Read it"
                        + " with " + q(READ_RESULT) + " in step 4.\n\n"
                        + "The app cannot prove the file fits your camera without a dump of its chip, which you do not"
                        + " have. The worst case is an empty result, not a damaged camera.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Make it", (di, w) -> downloadDump(Candidates.Kind.PROBE))
                .create();
        d.setOnDismissListener(di -> {
            if (dialog == di) {
                dialog = null;
            }
            if (!gone()) {
                updateButtons();
            }
        });
        dialog = d;
        d.show();
        updateButtons();
    }

    /** Explain the OV9732 bring-up (a reviewed patch, written without a backup), then build it on the owner's go-ahead. */
    private void confirmMakeBringup() {
        if (busy() || dialog != null || downloading) {
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Make the OV9732 bring-up?")
                .setMessage("This is experimental. It drives the OmniVision OV9732 that is fitted, in place of the"
                        + " SuperPix SP140A the firmware was built for, so the camera can try to take a picture.\n\n"
                        + "Like the sensor-ID probe, it goes on the card without a backup in step 0, because it is built"
                        + " from the public build and the stock restore file (step 1) puts it back: its boot and update"
                        + " code are that file's, byte for byte, and the SP140A table is left in place.\n\n"
                        + "After you flash it (steps 2 to 4), power the camera on and try a photo, then take the card out"
                        + " and look at the JPEGs.\n\n"
                        + "It may take no clean picture on the first try: the sensor's 1-lane MIPI timing has to match"
                        + " what the camera's receiver expects, which can only be confirmed on the bench. It cannot"
                        + " damage the camera; the worst case is no picture, as now.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Make it", (di, w) -> downloadDump(Candidates.Kind.BRINGUP))
                .create();
        d.setOnDismissListener(di -> {
            if (dialog == di) {
                dialog = null;
            }
            if (!gone()) {
                updateButtons();
            }
        });
        dialog = d;
        d.show();
        updateButtons();
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

    /** Make the chosen kind of update file from the dump. from says where the dump came from, for step 1's readout. */
    private void buildRestore(byte[] dump, int request, String from, Candidates.Kind kind) {
        stockDump = dump;
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
            try {
                data = Candidates.build(dump, kind);
                result = Candidates.check(kind.fileName, data, ref);
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
                    applyCheck(dd, r, kind.fileName, from + " Made " + kind.fileName + " from it: it matches the"
                            + " published file exactly. " + kind.label, false);
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
            showFile("The app could not read the file the other app sent. Tap " + q(USE_FILE) + " and choose it there"
                    + " instead.", detailBlock(why(e)));
            return;
        }
        if (uri != null) {
            loadFile(uri);
        }
    }

    private void loadFile(Uri uri) {
        // A confirmation for the previous file must not stay on screen once another file is loaded.
        dismissDialog();
        final int request = ++fileRequest;
        showFile("Reading the file and checking it…", null);
        fileData = null;
        fileCheck = null;
        fileName = null;
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
            String origin = null;
            boolean chosen = false;
            try {
                data = readAll(getContentResolver().openInputStream(uri));
                stock = data.length == Candidates.DUMP_SIZE && Candidates.isStockDump(data) ? Candidates.stockDump(data) : null;
                if (stock != null) {
                    summary = "";
                } else if (data.length == Candidates.DUMP_SIZE) {
                    String problem = UpdateCheck.backupProblem(name, data, Candidates.PUBLIC_DUMP_SHA256S);
                    if (problem == null) {
                        // Most likely the owner's own backup, picked in the wrong step.
                        summary = "\"" + name + "\" looks like a dump of a camera's flash chip, not an update file, and a"
                                + " dump never goes on a card as it is. If it is this camera's backup, choose it with "
                                + q(BACKUP) + " in step 0: the app then compares the update file with it. An update file"
                                + " is made from a dump with charmera pack on a computer.";
                    } else {
                        // Step 0 would refuse it too, so it is not pointed to: what is wrong with it is said instead.
                        summary = "\"" + name + "\" is a 2 MB dump, but not the public stock dump, the one dump the app"
                                + " makes an update file from. Nor is it one that step 0 takes as a backup: "
                                + sentence(problem) + "\nA dump never goes on a card as it is.";
                        technical = detailBlock("its SHA-256 is " + UpdateCheck.sha256Hex(data, 0, data.length)
                                + "; the public stock dump's is " + Candidates.STOCK_DUMP_SHA256 + ".");
                    }
                } else {
                    result = Candidates.check(name, data, ref);
                    boolean renamed = result.cardName != null && !result.cardName.equals(name);
                    boolean restore = Candidates.Kind.RESTORE.sha256.equals(result.sha256);
                    Candidates.Kind withdrawn = withdrawnKind(result.sha256);
                    if (withdrawn != null) {
                        // A candidate the app no longer offers, whatever its checks say.
                        summary = "The app refuses this file: do not put it on the card. It is "
                                + withdrawn.label.substring(0, 1).toLowerCase(java.util.Locale.ROOT)
                                + withdrawn.label.substring(1) + " The only file the app makes itself is the stock"
                                + " restore file, which is only for a camera whose backup, read before the camera was"
                                + " ever flashed, shows the public build.\n"
                                + "File: " + name + ".\n"
                                + "If this file is on the card, remove it before the card goes back into the camera:"
                                + " see step 5.";
                    } else if (restore && UpdateCheck.nameIsTheOnlyProblem(result)) {
                        // The bytes are the stock restore file and only its name failed; anything else, a sensor
                        // table that differs from the backup's above all, is shown in full by showChecked below.
                        summary = "Not OK to put on the card: the camera would not use it under this name. It is exactly"
                                + " the stock restore file, under another name. Tap " + q(DOWNLOAD) + " to make it"
                                + " with its right name.\n"
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
            final String from = "\"" + name + "\" is the public stock dump.";
            final String o = origin;
            final boolean c = chosen;
            final String n = name;
            ui.post(() -> {
                if (gone() || request != fileRequest) {
                    return;
                }
                if (s != null) {
                    buildRestore(s, request, from, Candidates.Kind.RESTORE);
                    return;
                }
                if (r != null && o != null) {
                    // Checked against the backup on record when the file was picked; applyCheck checks it again if
                    // another backup has come in since.
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
                    throw new Plain("it is larger than 4 MB, so it is neither an update file nor the stock dump");
                }
            }
            return out.toByteArray();
        }
    }

    // --- the card -----------------------------------------------------------------------------------------------

    private void pickCard() {
        if (busy()) {
            return;     // a write or removal reports on the drive it runs on; another one picked now would take its place
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, PICK_CARD);
        } catch (ActivityNotFoundException | SecurityException e) {
            setStatus(cardStatus, "Android could not open its folder picker, so the app cannot reach the card. Instead:"
                    + " tap " + q(SAVE) + " in step 1. Copy that file to the card's top folder with the Files app."
                    + " Then tap " + q(USE_FILE) + " and choose the copy on the card, so the app checks it there."
                    + detail(why(e)));
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
            loadFile(uri);
        } else if (request == PICK_BACKUP) {
            loadBackup(uri);
        } else if (request == SAVE_COPY) {
            writeCopy(uri);
        } else if (request == PICK_CARD) {
            // Results about the previous drive, or an earlier look at this one, no longer apply.
            if (!busy()) {
                writeStatus.setText("");
                cleanStatus.setText("");
                sensorStatus.setText("");    // a sensor result read from another drive no longer applies
            }
            if (!DocumentsContract.isTreeUri(uri)) {
                tree = null;
                card = null;
                cardRequest++;
                inspecting = false;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_TREE).apply();
                setStatus(cardStatus, "That was a single file, not a drive. Tap " + q(CHOOSE) + " again, open the"
                        + " camera's drive and tap \"Use this folder\" in its top folder.");
                updateButtons();
                return;
            }
            String note = null;
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
        final int request = ++cardRequest;
        final Uri t = tree;
        inspecting = true;
        setStatus(cardStatus, "Looking at the card…");
        card = null;
        updateButtons();
        WORKER.execute(() -> {
            Card c = readCard(t);
            ui.post(() -> {
                if (gone() || request != cardRequest) {
                    return;
                }
                inspecting = false;
                card = c;
                setStatus(cardStatus, c.state.describe() + (note == null ? "" : "\n\n" + note));
                updateButtons();
            });
        });
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
            s.problem = "That folder is not on a card or USB drive: it belongs to another app. Tap " + q(CHOOSE)
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
                    + " Android 11 and later, the top folder of a card in the phone's own slot cannot be chosen: use"
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
        if (busy() || dialog != null || gate(check, data) != null || c == null || !c.state.writable()) {
            return;
        }
        final String name = check.cardName;
        final boolean probe = isProbe(check);
        final boolean bringup = isBringup(check);
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
                        : bringup ? "Write the OV9732 bring-up to the card?"
                        : "Write the update file to the card?")
                .setMessage("The app copies the update file to the card's top folder. Nothing changes on the camera"
                        + " yet: it flashes the file the next time it switches on with this card in it.\n\n"
                        + "Update file: " + name + "\n"
                        + (probe
                            ? "This is the sensor-ID probe, written without a backup (one of the reviewed patches the"
                            + " app allows that). It scans the sensor bus and writes a result to SENSOR.TXT; it does not"
                            + " fix the camera.\n"
                            : bringup
                            ? "This is the OV9732 bring-up, written without a backup (one of the reviewed patches the app"
                            + " allows that). It drives the fitted OV9732 in place of the SP140A set-up; it changes no"
                            + " boot or update code, and the stock restore file reverses it.\n"
                            : "Compared with this camera's backup: " + backupLine + "\n")
                        + "Card: " + (c.state.label == null ? "the chosen card" : c.state.label) + "\n\n"
                        + (probe
                            ? "After it flashes, open photo mode on the camera, then read the result with "
                            + q(READ_RESULT) + " in step 4.\n\n"
                            : bringup
                            ? "After it flashes, power the camera on and try a photo, then take the card out and look at"
                            + " the JPEGs. It may take no clean picture on the first try; the stock restore file puts it"
                            + " back.\n\n"
                            : "The app cannot tell which camera a backup was read from: write only if that backup is a"
                            + " read of the camera this card goes into.\n\n")
                        + "Detail: " + UpdateCheck.bytes(check.size) + ", SHA-256 " + check.sha256 + ".")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Write", (di, w) -> write(c, data, check))
                .create();
        d.setOnDismissListener(di -> {
            if (dialog == di) {
                dialog = null;
            }
            if (writeDialog == di) {
                writeDialog = null;
            }
            if (!gone()) {
                updateButtons();
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
                if (equal) {
                    writtenWith = check.referenceSha256;
                    writtenName = backupName;
                }
                setStatus(writeStatus, equal
                        ? NOTHING_TO_WRITE + " the card already holds this exact update file, " + check.cardName + ".\n"
                        + "Eject the drive and go on with step 4. If you have already flashed with it, go on with step"
                        + " 5 instead."
                        : read
                        ? "Nothing was written to the card. It holds a different file with the same name, " + same.name
                        + ". Tap " + q(REMOVE) + " (under step 5) now, then write again."
                        : "Nothing was written to the card. It holds a file named " + same.name + ", but the app could"
                        + " not read it to compare. Tap " + q(REMOVE) + " (under step 5) now, then write again."
                        + detail(got));
                updateButtons();
            });
        });
    }

    private void write(Card c, byte[] data, UpdateCheck.Result check) {
        // Tapped in the confirmation: the gate is asked again, with the backup on record at this moment.
        String refusal = busy() ? "a write or a removal is already running" : gate(check, data);
        if (refusal != null) {
            setStatus(writeStatus, "Nothing was written to the card: " + sentence(refusal));
            updateButtons();
            return;
        }
        final Operation op = start(true, 1);
        setStatus(writeStatus, WRITING);
        cleanStatus.setText("");          // a result about an earlier removal no longer applies
        sensorStatus.setText("");         // a sensor result from a previous flash no longer applies
        updateButtons();
        WORKER.execute(() -> {
            String outcome = null;
            try {
                outcome = writeAndReport(c.tree, data, check);
            } finally {
                String o = outcome != null ? outcome : "Writing stopped with an unexpected error. Step 2 shows what the"
                        + " card holds now: do not switch the camera on with this card in it while it lists an update"
                        + " file.";
                finish(op, o, o.startsWith(WRITTEN));
            }
        });
    }

    /** Runs on the worker. Checks again, lists the card again, writes, reads back; says what is on the card after a failure. */
    private String writeAndReport(Uri t, byte[] data, UpdateCheck.Result check) {
        // Against the backup on record now, which may have changed since Write was tapped. A check of another file in
        // step 1 does not concern these bytes; a changed backup shows in its SHA-256.
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
                    + readCard(t).state.afterwards() + "\n\nWhen step 2 lists no update file, tap " + q(WRITE)
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
        return WRITTEN + "\nThe app wrote " + name + " to the card's top folder and read it back: it matches.\n"
                + "Next: eject the drive before you unplug the cable or take the card out. Tap " + q(EJECT) + ". Then"
                + " follow step 4." + detail("SHA-256 " + sha + ".");
    }

    /** Null when the document was deleted, otherwise why not (Android 7 answers false, later versions throw). */
    private String delete(Uri doc) {
        try {
            return DocumentsContract.deleteDocument(getContentResolver(), doc) ? null : "the card refused";
        } catch (IOException | RuntimeException e) {
            return why(e);
        }
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
        readingSensor = true;
        setStatus(sensorStatus, "Reading SENSOR.TXT from the card…");
        updateButtons();
        WORKER.execute(() -> {
            String text;
            try {
                text = decodeSensor(readCardFile(t, "SENSOR.TXT"));
            } catch (RuntimeException e) {
                text = "Could not read the card: " + sentence(cause(e, "the drive may have come loose")) + " Tap "
                        + q(CHOOSE) + " in step 2 and pick the drive again." + detail(why(e));
            }
            final String tt = text;
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                readingSensor = false;
                setStatus(sensorStatus, tt);
                reveal(sensorStatus);
                updateButtons();
            });
        });
    }

    /**
     * The bytes of the file with this name in the card's top folder (any case), or null when the card was read and
     * there is genuinely no such file. A card that cannot be read now returns a null children cursor (as fillCard
     * treats it too): that throws, so a dropped or loose drive is never reported as a missing file.
     */
    private byte[] readCardFile(Uri t, String name) {
        String docId = DocumentsContract.getTreeDocumentId(t);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(t, docId);
        String foundId = null;
        try (Cursor q = getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (q == null) {
                throw new RuntimeException("the drive did not answer");
            }
            while (q.moveToNext()) {
                if (name.equalsIgnoreCase(q.getString(1))
                        && !DocumentsContract.Document.MIME_TYPE_DIR.equals(q.getString(2))) {
                    foundId = q.getString(0);
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
            return "No SENSOR.TXT on the card yet.\n\nFlash the sensor-ID probe (steps 2 to 4), then open photo mode on"
                    + " the camera: it stops there as before, but writes the file first. Then bring the card back and"
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
                    + " writes. The probe may not have finished. Flash it again, open photo mode, and read it again.";
        }
        int hi = b[0] & 0xFF, lo = b[1] & 0xFF;
        String id = String.format(java.util.Locale.ROOT, "0x%02X 0x%02X", hi, lo);
        if ((hi == 0xFF && lo == 0xFF) || (hi == 0x00 && lo == 0x00)) {
            return "Sensor ID " + id + ": nothing answered at the one address this older probe tried. Flash the current"
                    + " probe, which scans every address and tells a wrong address apart from a sensor that is truly"
                    + " silent.";
        }
        if ((hi == 0x14 && lo == 0x0A) || (hi == 0x0A && lo == 0x14)) {
            return "Sensor ID " + id + ": the SP140A, the sensor the firmware already drives.";
        }
        return "Sensor ID " + id + ": a different sensor from the one the firmware drives. Note this pair down.";
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
                    + " that is not responding at all \u2014 disconnected, unpowered, or dead \u2014 not a wrong-sensor"
                    + " mismatch. Open the camera, reseat the sensor's ribbon, and scan again. If it still answers"
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
                  .append(match ? " \u2014 the SP140A it expects." : ", not the SP140A's 14 0A.");
            } else {
                sb.append(".");
            }
            sb.append("\n\n").append(match
                    ? "So the sensor is present and is the exact part the firmware expects. The freeze is not a sensor"
                      + " mismatch \u2014 the cause is elsewhere (power, clock, or a fault past the ID read)."
                    : "A sensor is present at the expected address, so this is not a missing or wrong-address sensor."
                      + " If the ID is not 14 0A, note it down.");
        } else {
            int a = (b[18] & 0xFF) != 0xFF ? (b[18] & 0xFF) : hits.get(0);
            sb.append("A sensor answered at ")
              .append(String.format(java.util.Locale.ROOT, "0x%02X (bus 0x%02X)", a, a << 1))
              .append(", not the 0x3C (bus 0x78) the firmware drives.\n\n")
              .append("The camera has a different sensor than the restored firmware targets \u2014 it is alive, just at"
                      + " another address. That is the fixable case: a firmware built for this sensor could work. The"
                      + " first responder's registers 0x02/0x03 read ").append(idStr).append(". Note the address and"
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
                    + " that is not responding at all \u2014 disconnected, unpowered, or dead \u2014 not a wrong-sensor"
                    + " mismatch. Reseat the sensor's ribbon and scan again; if it still answers nowhere, the sensor or"
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
              .append(match ? " \u2014 the SP140A it expects.\n\n" : ", not the SP140A's 14 0A.\n\n")
              .append(match
                    ? "So the sensor is present and is the exact part the firmware expects; a freeze is not a sensor"
                      + " mismatch."
                    : "Something answers at the SP140A address but reports a different ID.");
        } else {
            int a = addrs.get(0);
            sb.append("A sensor answered at ")
              .append(String.format(java.util.Locale.ROOT, "0x%02X (bus 0x%02X)", a, a << 1))
              .append(", not the 0x3C (bus 0x78) the firmware drives.\n\n")
              .append("The camera has a different sensor than the restored firmware targets \u2014 it is alive, just at"
                      + " another address. That is the fixable case: a firmware built for this sensor could work.");
        }
        sb.append("\n\nRegister fingerprint (send SENSOR.TXT so the exact part can be named):");
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
                    + " that is not responding at all — disconnected, unpowered, or dead. Reseat the sensor's ribbon"
                    + " and try again; if it still answers nowhere, it is a hardware fault no firmware can fix.";
        }
        final int SP140A = 0x3C;   // 7-bit; bus write address 0x78
        StringBuilder sb = new StringBuilder();
        if (addrs.indexOf(SP140A) >= 0) {
            sb.append("A sensor answered at 0x3C (bus 0x78), the address the firmware drives.\n\n");
        } else {
            sb.append("A sensor answered at ").append(hexAddr(addrs.get(0)))
              .append(", not the 0x3C (bus 0x78) the firmware drives — alive, just at another address.\n\n");
        }
        for (int i = 0; i < count; i++) {
            int off = 3 + i * REC;
            sb.append(hexAddr(b[off] & 0xFF)).append(": ").append(identify16(b, off)).append('\n');
        }
        sb.append("\nSend SENSOR.TXT so the fingerprint can be confirmed against a datasheet.");
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
            return "no 16-bit register answered (all FF) — the part likely uses 8-bit register addresses; the probe"
                    + " can be switched to an 8-bit ID read of 0x00..0x1F and 0xF0..0xFF.";
        }
        if (all00) {
            return "answered, but the 16-bit ID registers all read 00 — the sensor's core may be held unclocked, or"
                    + " its ID is at a register other than 0x300A/0x3107.";
        }
        if (allSame) {
            return String.format(java.util.Locale.ROOT, "all registers read %02X — likely the wrong sub-address"
                    + " width for this part.", first);
        }
        return "ID not recognized at 0x300A/0x300B (OmniVision) or 0x3107/0x3108 (SmartSens) — send the file.";
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
        if (busy() || dialog != null || c == null || !c.state.listed || c.state.updateFiles.isEmpty()) {
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
                updateButtons();
            }
        });
        dialog = d;
        d.show();
        updateButtons();
    }

    private void clean(Uri t, List<CardState.Item> files) {
        if (busy()) {
            return;
        }
        final Operation op = start(false, files.size());
        setStatus(cleanStatus, REMOVING);
        writeStatus.setText("");          // a result about the write before the flash no longer applies
        sensorStatus.setText("");         // a sensor result from a previous flash no longer applies
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
                        : files.size() == 1 ? "Done: the update file is removed." : "Done: the update files are removed.";
                outcome = headline + "\n" + sb + "\n" + readCard(t).state.afterwards(true) + detail(reasons.toString());
            } finally {
                finish(op, outcome != null ? outcome : "Removing stopped with an unexpected error. Step 2 shows what"
                        + " the card holds now: do not switch the camera on with this card in it while it lists an"
                        + " update file.", outcome != null && all);
            }
        });
    }
}
