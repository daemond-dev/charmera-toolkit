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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Puts a verified Charmera update file in the root of an SD card the phone can see, through Android's own folder
 * picker (the Storage Access Framework), so no storage permission and no root are needed. The camera then
 * flashes itself from the card. Also removes update files from the card afterwards.
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
    private static final String DOWNLOAD = "Download the stock restore file";
    private static final String USE_FILE = "Use a file on the phone";
    private static final String SAVE = "Save a copy to the phone";
    private static final String CHOOSE = CardState.CHOOSE_BUTTON;
    private static final String WRITE = "Write the update file to the card";
    private static final String EJECT = "Eject the drive (opens Android's storage settings)";
    private static final String REMOVE = CardState.REMOVE_BUTTON;
    private static final String OPEN_SETTINGS = "Open storage settings";
    /** fetch's failure when GitHub answered, but not with the file; the download's message tells it apart. */
    private static final String GITHUB_REFUSED = "GitHub did not send the file";
    /** How a write that worked begins; write() offers the eject screen only after it. */
    private static final String WRITTEN = "Done: the update file is on the card.";

    /**
     * One worker for the whole process, never shut down: a write or removal finishes even when the screen that
     * started it is gone (Back, or a dark-mode, font or language change recreates it), and whatever a new screen
     * asks for (looking at the card again) waits until it has.
     */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    /** Downloads run apart from card work, so a slow connection never holds up a card listing or a write. */
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final int SAVE_COPY = 4;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView fileStatus;
    /** The technical part under fileStatus: the checks, sums and Android's error words. Hidden when empty. */
    private TextView fileDetail;
    private TextView saveStatus;
    private TextView cardStatus;
    private TextView writeStatus;
    private Button writeButton;
    private Button cleanButton;
    private Button downloadButton;
    private TextView cleanStatus;
    private Button saveButton;
    /** The public stock dump once downloaded or picked and checked; kept so choosing again needs no new download. */
    private byte[] stockDump;
    private boolean downloading;

    private byte[] fileData;
    private UpdateCheck.Result fileCheck;
    /** Bumped by every loadFile, so a slow read of an earlier file cannot replace a later one. */
    private int fileRequest;
    private Uri tree;
    private Card card;
    /** Bumped by every inspectCard, so only the latest look at the card is shown. */
    private int cardRequest;
    private boolean inspecting;
    /** A write, a removal or a comparison with the card is running. */
    private boolean busy;
    /** The confirmation dialog on screen, or null. */
    private AlertDialog dialog;

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

        Plain(String message) {
            this(message, null);
        }

        Plain(String message, String detail) {
            super(message);
            this.detail = detail;
        }
    }

    // --- layout -------------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.app_name);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);

        col.addView(heading("Before you start"));
        col.addView(warning("Only for the original 2025 series: matte body, yellow Kodak boot screen. Never flash a"
                + " Millennium Edition (blue boot screen) or a CLOT edition.\n"
                + "Charge the camera's battery fully first."));
        col.addView(body("Flashing replaces the two photos the camera keeps in its own memory. With the stock restore"
                + " file, they become the two photos of the camera it was copied from. If your photos matter, copy the"
                + " SPIDCIM folder off the card before you flash, with the Files app or on a computer. That is where the"
                + " camera copies them."));

        col.addView(heading("1. Get the update file"));
        col.addView(body("The update file is the file the camera flashes itself from. Tap " + q(DOWNLOAD) + ". The app"
                + " downloads the stock dump from GitHub (2 MB): a public copy of a working camera's memory. It checks"
                + " the stock dump and makes the stock restore file from it. That is the update file other owners have"
                + " flashed. It puts the camera back to stock, settings included. The app uses it only if it matches"
                + " the published file exactly."));
        downloadButton = button(DOWNLOAD, v -> downloadDump());
        col.addView(downloadButton);
        TextView offline = body("No connection, or the file is already on the phone? Tap " + q(USE_FILE) + " and"
                + " choose the stock dump or an update file. To get the stock dump in a browser, open its page: "
                + Candidates.STOCK_DUMP_PAGE);
        android.text.util.Linkify.addLinks(offline, android.text.util.Linkify.WEB_URLS);
        col.addView(offline);
        col.addView(button(USE_FILE, v -> pickFile()));
        fileStatus = body("");
        col.addView(fileStatus);
        fileDetail = mono();
        col.addView(fileDetail);
        col.addView(body("Only for a card in the phone's own SD slot on Android 11 or later (see step 2):"));
        saveButton = button(SAVE, v -> saveCopy());
        col.addView(saveButton);
        saveStatus = body("");
        col.addView(saveStatus);

        col.addView(heading("2. Connect the camera"));
        col.addView(warning("Plugging the cable into a switched-off camera switches it on. If its card holds an update"
                + " file, the camera flashes that file again. So always connect the camera this way:"));
        col.addView(body("• If the camera is on, switch it off.\n"
                + "• Take the card out of the camera.\n"
                + "• Switch the camera on with its power key.\n"
                + "• Wait 10 seconds. The camera looks for an update file only in the first five seconds after"
                + " power-on.\n"
                + "• Put the card back in.\n"
                + "• Connect the camera to the phone with a USB-C data cable. The camera then shows its card to the"
                + " phone as a drive: the camera's drive.\n"
                + "Tap " + q(CHOOSE) + ". In the picker's menu, open the camera's drive. Tap \"Use this folder\" while"
                + " you are in its top folder, not inside DCIM.\n"
                + "No drive in the picker? See \"If the drive does not show up\" at the bottom.\n"
                + "A USB card reader works the same way: put the card in the reader and connect the reader to the"
                + " phone. The phone's own SD slot works the same way only on Android 7 to 10. From Android 11 on,"
                + " Android does not let an app choose the top folder of a card in the phone's slot.\n"
                + "On Android 11 and later, for the slot: tap " + q(SAVE) + " in step 1. Copy that file to the card's"
                + " top folder with the Files app. Then tap " + q(USE_FILE) + " and choose the copy on the card to have"
                + " it checked. Then skip to step 4."));
        col.addView(button(CHOOSE, v -> pickCard()));
        cardStatus = body("");
        col.addView(cardStatus);

        col.addView(heading("3. Write to the card"));
        col.addView(body("Tap " + q(WRITE) + ". It works once step 1 says \"OK to put on the card\" and step 2 says the"
                + " card is ready. The app asks before it writes. It copies the update file to the card's top folder"
                + " and reads it back to check it."));
        writeButton = button(WRITE, v -> confirmWrite());
        col.addView(writeButton);
        writeStatus = body("");
        col.addView(writeStatus);
        col.addView(button(EJECT, v -> openStorageSettings()));

        col.addView(heading("4. Flash"));
        col.addView(body("• Eject the drive: tap " + q(EJECT) + " above, tap the USB drive there, then \"Eject\". Or"
                + " tap \"Eject\" in the notification Android shows for the USB drive. Then unplug the cable. With a"
                + " card reader or the phone's slot: eject, then put the card in the camera.\n"
                + "• Check once more: original 2025 series camera, battery fully charged.\n"
                + "• If the camera is on, switch it off. Then switch it on with its power key, with the card in it. The"
                + " camera looks for the update file only in the first five seconds after power-on. It then shows"
                + " \"Upgrade\" with a percentage, about 15 to 20 seconds in all. Do not touch the camera or the card"
                + " until it reaches 100%.\n"
                + "• No \"Upgrade\" screen, and the camera starts as usual? Then it did not find the update file. Switch"
                + " the camera off. Connect it as in step 2, and check that step 2 lists the update file. If it does,"
                + " eject the drive and try step 4 again. If it does not, write the update file again in step 3."));
        col.addView(warning("At 100% the camera stays on its last screen. It does not switch itself off and does not"
                + " delete the update file. Take the card out first, then hold the power key about a second to switch"
                + " the camera off."));
        col.addView(body("\"Program error\" on the camera means it found the update file damaged and did not use it."
                + " Nothing was erased, and the camera switches itself off. Take the card out. Then remove the update"
                + " file as in step 5, and write it again in step 3."));

        col.addView(heading("5. Clean up"));
        col.addView(warning("The update file is still on the card. The camera would flash it again at every power-on,"
                + " so remove it now."));
        col.addView(body("• Keep the card out of the camera.\n"
                + "• Switch the camera on with its power key, not by plugging in the cable.\n"
                + "• Wait 10 seconds. The camera looks for the update file only in the first five seconds after"
                + " power-on, so it cannot flash again now.\n"
                + "• Put the card in.\n"
                + "• Connect the camera to the phone.\n"
                + "• Tap " + q(REMOVE) + ". If it is greyed out, first tap " + q(CHOOSE) + " and pick the camera's"
                + " drive again.\n"
                + "Card in the phone's own slot on Android 11 or later? Put the card in the phone. Delete the update"
                + " file from its top folder with the Files app. Eject the card before you take it out."));
        cleanButton = button(REMOVE, v -> confirmClean());
        col.addView(cleanButton);
        cleanStatus = body("");
        col.addView(cleanStatus);
        col.addView(body("When the update file is gone: eject the drive and unplug the cable. Then switch the camera off"
                + " and on again. Check the boot screen, the live view and a test photo."));

        col.addView(heading("If the drive does not show up"));
        col.addView(body("Check the cable first: some USB-C cables only charge.\n"
                + "Android may refuse a card the camera has used, because the camera leaves a flag set on it. The phone"
                + " then offers to format the card. Formatting works, but it erases everything on the card: copy your"
                + " photos off it first, for example on a computer. On a computer, the toolkit can also clear the flag"
                + " without erasing anything. Details: docs/05-CARDS.md and docs/06-ANDROID.md in the toolkit."));

        ScrollView scroll = new ScrollView(this);
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
        updateButtons();
        // A file the app was opened with is handled once: not again by a recreated screen (state != null), and not
        // when Recents starts the app again with the intent it was first opened with.
        Intent first = getIntent();
        if (state == null && first != null && (first.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            handleIncoming(first);
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
        // The card may have been inserted, removed or changed while the app was in the background.
        if (tree != null && !busy && !inspecting && dialog == null) {
            inspectCard();
        }
    }

    @Override
    protected void onDestroy() {
        dismissDialog();
        super.onDestroy();
    }

    /** True once this screen is closing; work that finishes later must not touch its views or open dialogs. */
    private boolean gone() {
        return isFinishing() || isDestroyed();
    }

    private TextView warning(String s) {
        TextView t = body(s);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
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

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void updateButtons() {
        boolean idle = !busy && dialog == null;
        boolean fileOk = fileData != null && fileCheck != null && fileCheck.ok;
        boolean cardOk = card != null && card.state.writable();
        writeButton.setEnabled(idle && fileOk && cardOk);
        cleanButton.setEnabled(idle && card != null && card.state.listed && !card.state.updateFiles.isEmpty());
        downloadButton.setEnabled(idle && !downloading);
        saveButton.setEnabled(idle && fileOk);
    }

    private void dismissDialog() {
        if (dialog != null) {
            AlertDialog d = dialog;
            dialog = null;
            d.dismiss();
        }
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
        boolean none = technical == null || technical.trim().isEmpty();
        fileDetail.setText(none ? "" : technical.trim());
        fileDetail.setVisibility(none ? View.GONE : View.VISIBLE);
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
        if (busy || dialog != null || UpdateCheck.confirms(fileCheck, fileData) != null) {
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
        if (data == null || UpdateCheck.confirms(check, data) != null) {
            return;
        }
        setStatus(saveStatus, "Saving the copy…");
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
            });
        });
    }

    /** Download the public stock dump from its pinned upstream address, then ask which candidate to make. */
    private void downloadDump() {
        dismissDialog();
        final int request = ++fileRequest;
        fileData = null;
        fileCheck = null;
        saveStatus.setText("");
        if (!busy) {
            writeStatus.setText("");
        }
        if (stockDump != null) {               // already downloaded and checked: choose again without a new download
            chooseCandidate(stockDump, request, "Used the stock dump the app already has.");
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
                chooseCandidate(d, request, "Downloaded the stock dump from GitHub and checked it.");
            });
        });
    }

    private static byte[] fetch(String address) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new Plain(GITHUB_REFUSED, "GitHub answered " + code + ".");
            }
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** from says where the stock dump came from, as a sentence for the top of step 1's readout. */
    private void chooseCandidate(byte[] dump, int request, String from) {
        stockDump = dump;
        if (dialog != null) {
            showFile(from + " Tap " + q(DOWNLOAD) + " to make the update file from it. No new download is needed.",
                    null);
            updateButtons();
            return;
        }
        final Candidates.Kind[] kinds = Candidates.Kind.offeredKinds();
        if (kinds.length == 1) {
            buildCandidate(dump, kinds[0], request, from);
            return;
        }
        final boolean[] picked = {false};
        String[] labels = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            labels[i] = kinds[i].label;
        }
        showFile(from + " Choose which update file to make.",
                detailBlock("SHA-256 of the stock dump " + Candidates.STOCK_DUMP_SHA256 + "."));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Which update file?")
                .setItems(labels, (di, which) -> {
                    picked[0] = true;
                    buildCandidate(dump, kinds[which], request, from);
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnDismissListener(di -> {
            if (dialog == di) {
                dialog = null;
            }
            if (!gone()) {
                if (!picked[0] && request == fileRequest) {
                    showFile("No update file made yet. Tap " + q(DOWNLOAD) + " to choose again. No new download is"
                            + " needed.", null);
                }
                updateButtons();
            }
        });
        dialog = d;
        d.show();
        updateButtons();
    }

    private void buildCandidate(byte[] dump, Candidates.Kind kind, int request, String from) {
        if (request != fileRequest) {
            return;
        }
        showFile("Making the update file…", null);
        WORKER.execute(() -> {
            byte[] data = null;
            UpdateCheck.Result result = null;
            String summary;
            String technical;
            try {
                data = Candidates.build(dump, kind);
                result = UpdateCheck.check(kind.fileName, data);
                summary = result.verdict() + "\n"
                        + from + " Made " + kind.fileName + " from it: it matches the published file exactly. "
                        + kind.label + "\n"
                        + (result.ok ? nextAfterFile(false) : "");
                technical = "Detail: SHA-256 " + result.sha256 + ", the published file's.\n\nThe app's checks:\n\n"
                        + result.report();
            } catch (RuntimeException e) {
                data = null;
                result = null;
                String[] parts = split(why(e));
                summary = "Could not make the update file: " + sentence(parts[0].isEmpty() ? "it failed" : parts[0]);
                technical = detailBlock(parts[1]);
            }
            final byte[] dd = data;
            final UpdateCheck.Result r = result;
            final String tt = summary;
            final String xx = technical;
            ui.post(() -> {
                if (gone() || request != fileRequest) {
                    return;
                }
                fileData = dd;
                fileCheck = r;
                showFile(tt, xx);
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
        saveStatus.setText("");
        if (!busy) {
            writeStatus.setText("");
        }
        updateButtons();
        WORKER.execute(() -> {
            String name = displayName(uri);
            String summary;
            String technical = null;
            byte[] data = null;
            UpdateCheck.Result result = null;
            byte[] stock = null;
            try {
                data = readAll(getContentResolver().openInputStream(uri));
                stock = data.length == Candidates.DUMP_SIZE && Candidates.isStockDump(data) ? Candidates.stockDump(data) : null;
                if (stock != null) {
                    summary = "";
                } else if (data.length == Candidates.DUMP_SIZE) {
                    String reason = "";
                    try {
                        Candidates.stockDump(data);
                    } catch (IllegalArgumentException e) {
                        reason = e.getMessage();
                    }
                    String[] parts = split(reason);
                    summary = "\"" + name + "\" is a 2 MB dump, but "
                            + sentence(parts[0].isEmpty() ? "it is not the public stock dump" : parts[0])
                            + "\nA dump never goes on a card as it is. To restore the camera, tap " + q(DOWNLOAD)
                            + " instead.";
                    technical = detailBlock(parts[1]);
                } else {
                    result = UpdateCheck.check(name, data);
                    boolean renamed = result.cardName != null && !result.cardName.equals(name);
                    boolean restore = Candidates.Kind.RESTORE.sha256.equals(result.sha256);
                    Candidates.Kind withdrawn = withdrawnKind(result.sha256);
                    if (withdrawn != null) {
                        // Its checks pass, but it is a candidate the app no longer offers.
                        summary = "Do not put this file on the card. It is "
                                + withdrawn.label.substring(0, 1).toLowerCase(java.util.Locale.ROOT)
                                + withdrawn.label.substring(1) + " The app offers only the stock restore file. Tap "
                                + q(DOWNLOAD) + " instead.\n"
                                + "File: " + name + ".\n"
                                + "If this file is on the card, remove it before the card goes back into the camera:"
                                + " see step 5.";
                    } else if (restore && !result.ok) {
                        // The bytes are the stock restore file, so only its name can have failed.
                        summary = "Not OK to put on the card: the camera would not use it under this name. It is exactly"
                                + " the stock restore file, under another name. Tap " + q(DOWNLOAD) + " to make it"
                                + " with its right name.\n"
                                + "File: " + name + ".";
                    } else {
                        summary = result.verdict() + "\n"
                                + "File: " + name + "."
                                + (restore ? " It is exactly the stock restore file." : "")
                                + (renamed ? "\nIts name here is not exactly the one the camera looks for. The app"
                                        + " writes it to the card as " + result.cardName + ". A copy you put on the card"
                                        + " yourself must be renamed to exactly that, or the camera will not use it."
                                        : "")
                                + "\n" + (result.ok ? nextAfterFile(!renamed) : "");
                    }
                    technical = "Detail: " + UpdateCheck.bytes(data.length) + ", SHA-256 " + result.sha256 + ".\n\nThe"
                            + " app's checks:\n\n" + result.report();
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
            ui.post(() -> {
                if (gone() || request != fileRequest) {
                    return;
                }
                if (s != null) {
                    chooseCandidate(s, request, from);
                    return;
                }
                fileData = d;
                fileCheck = r;
                showFile(t, x);
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
        } else if (request == SAVE_COPY) {
            writeCopy(uri);
        } else if (request == PICK_CARD) {
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
        // Everything the dialog shows is captured here, and Write uses exactly that, whatever is loaded later.
        final byte[] data = fileData;
        final UpdateCheck.Result check = fileCheck;
        final Card c = card;
        if (busy || dialog != null || UpdateCheck.confirms(check, data) != null || c == null || !c.state.writable()) {
            return;
        }
        final String name = check.cardName;
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
                .setTitle("Write the update file to the card?")
                .setMessage("The app copies the update file to the card's top folder. Nothing changes on the camera"
                        + " yet: it flashes the file the next time it switches on with this card in it.\n\n"
                        + "Update file: " + name + "\n"
                        + "Card: " + (c.state.label == null ? "the chosen card" : c.state.label) + "\n\n"
                        + "Detail: " + UpdateCheck.bytes(check.size) + ", SHA-256 " + check.sha256 + ".")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Write", (di, w) -> write(c, data, check))
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

    private void compareWithCard(Card c, CardState.Item same, UpdateCheck.Result check) {
        busy = true;
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
                busy = false;
                setStatus(writeStatus, equal
                        ? "Nothing to write: the card already holds this exact update file, " + check.cardName + ".\n"
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
        busy = true;
        setStatus(writeStatus, "Writing the update file to the card…");
        updateButtons();
        WORKER.execute(() -> {
            String outcome = writeAndReport(c.tree, data, check);
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                busy = false;
                setStatus(writeStatus, outcome);
                inspectCard();
                if (outcome.startsWith(WRITTEN)) {
                    done("The update file is on the card", "After ejecting, unplug the cable, or take the card out of"
                            + " the reader, and go on with step 4.");
                }
            });
        });
    }

    /** Runs on the worker. Checks again, lists the card again, writes, reads back; says what is on the card after a failure. */
    private String writeAndReport(Uri t, byte[] data, UpdateCheck.Result check) {
        String refusal = UpdateCheck.confirms(check, data);
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

    // --- cleaning up --------------------------------------------------------------------------------------------

    private void confirmClean() {
        final Card c = card;
        if (busy || dialog != null || c == null || !c.state.listed || c.state.updateFiles.isEmpty()) {
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
        busy = true;
        setStatus(cleanStatus, "Removing from the card…");
        updateButtons();
        WORKER.execute(() -> {
            StringBuilder sb = new StringBuilder();
            StringBuilder reasons = new StringBuilder();
            boolean all = true;
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
            final boolean allRemoved = all;
            String headline = !allRemoved ? "Not everything was removed."
                    : files.size() == 1 ? "Done: the update file is removed." : "Done: the update files are removed.";
            final String s = headline + "\n" + sb + "\n" + readCard(t).state.afterwards(true) + detail(reasons.toString());
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                busy = false;
                setStatus(cleanStatus, s);
                inspectCard();
                if (allRemoved) {
                    done(files.size() == 1 ? "Update file removed" : "Update files removed", "If you have just"
                            + " flashed: after ejecting, unplug the cable. Then switch the camera off and on again, and"
                            + " check the boot screen, the live view and a test photo.\n"
                            + "If you removed an old update file before writing: tap \"Later\" and go on with step 3.");
                }
            });
        });
    }
}
