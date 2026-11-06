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
 */
public class MainActivity extends Activity {

    private static final int PICK_FILE = 1;
    private static final int PICK_CARD = 2;
    private static final String PREFS = "card";
    private static final String KEY_TREE = "tree";
    private static final String EXTERNAL_STORAGE = "com.android.externalstorage.documents";
    private static final String TEMP_NAME = "charmera-stage.part";
    private static final int MAX_FILE = 4 * 1024 * 1024;

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
        col.addView(body("Only for the original 2025 series: matte body, yellow Kodak boot screen. Never flash a"
                + " Millennium or CLOT edition with these files.\n"
                + "Charge the camera's battery fully first.\n"
                + "The flash candidates (docs/03-FLASH-CANDIDATES.md) erase the two photos the camera keeps in its own"
                + " memory."));

        col.addView(heading("1. Get the update file"));
        col.addView(body("The app downloads the public stock firmware dump from GitHub (2 MiB), checks it, and makes the"
                + " stock restore file from it: the published file other owners have flashed, accepted only if its"
                + " SHA-256 matches. It puts a camera back to stock."));
        downloadButton = button("Download the stock restore file", v -> downloadDump());
        col.addView(downloadButton);
        TextView offline = body("Offline, or already have the stock dump or an update file on the phone? Choose it"
                + " instead. The stock dump's page: " + Candidates.STOCK_DUMP_PAGE);
        android.text.util.Linkify.addLinks(offline, android.text.util.Linkify.WEB_URLS);
        col.addView(offline);
        col.addView(button("Use a file on the phone", v -> pickFile()));
        fileStatus = mono();
        col.addView(fileStatus);
        saveButton = button("Save a copy to the phone", v -> saveCopy());
        col.addView(saveButton);

        col.addView(heading("2. Connect the camera"));
        col.addView(warning("Plugging the cable into a switched-off camera switches it on. If its card holds an update"
                + " file, it flashes that file again. Only connect a switched-off camera when the card has no update file."));
        col.addView(body("Card in the camera. Connect the camera to the phone with a USB-C data cable; the camera shows its"
                + " card as a drive. If no drive appears, do not switch the camera on with the card in, in case the card"
                + " still holds an update file: take the card out, switch the camera on, wait 10 seconds, put the card"
                + " back in, then connect. Tap the button, open that drive in the picker and tap \"Use this folder\" at"
                + " its top level, not inside DCIM.\n"
                + "A USB card reader works the same way. The phone's own SD slot works only on Android 7 to 10: from"
                + " Android 11 on, Android does not let an app choose the top level of a card in the slot. For the slot,"
                + " use \"Save a copy to the phone\" in step 1, copy that file to the card's top level with the Files"
                + " app, and skip steps 2 and 3."));
        col.addView(button("Choose the camera's drive", v -> pickCard()));
        cardStatus = mono();
        col.addView(cardStatus);

        col.addView(heading("3. Write"));
        writeButton = button("Write the file to the card", v -> confirmWrite());
        col.addView(writeButton);
        writeStatus = body("");
        col.addView(writeStatus);
        col.addView(button("Eject the drive (opens Android's storage settings)", v -> openStorageSettings()));

        col.addView(heading("4. Flash"));
        col.addView(body("1. Eject the drive in Android (Settings, Storage, the USB drive, Eject), then unplug the cable."
                + " With a card reader or the phone's slot: eject, then put the card into the camera.\n"
                + "2. Switch the camera off, then on again, with the card still in it. The camera looks for the file in"
                + " its first five seconds and shows the update progress. Do not touch anything until it reaches 100%.\n"
                + "3. The camera stays on its last screen and does not delete the file. Take the card out first, then"
                + " hold the power key about a second to switch it off.\n"
                + "\"Program error\" means the file's sum did not match: nothing was erased and the camera switches"
                + " itself off."));

        col.addView(heading("5. Clean up"));
        col.addView(body("The update file is still on the card, and the camera would flash it again at every power-on."
                + " With the card still out: switch the camera on with its own power key (not by plugging in the cable),"
                + " wait 10 seconds, put the card in, connect the camera to the phone again, then tap the button. The camera only looks for the file in the first five"
                + " seconds after power-on, so it cannot flash again this way."));
        cleanButton = button("Remove update files from the card", v -> confirmClean());
        col.addView(cleanButton);
        cleanStatus = body("");
        col.addView(cleanStatus);
        col.addView(body("Then switch the camera off and on normally and check the boot screen, the live view and a test"
                + " photo."));

        col.addView(heading("If the drive does not show up"));
        col.addView(body("A card the camera has used has a flag set that Android may refuse, so the phone offers to"
                + " format it instead. Formatting it in the phone works but erases the card; copy your photos off first."
                + " Details: docs/05-CARDS.md and docs/06-ANDROID.md in the toolkit."));

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        setContentView(scroll);

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null);
        if (saved != null && hasPermission(Uri.parse(saved))) {
            tree = Uri.parse(saved);
            cardStatus.setText("Looking at the card…");
        } else {
            cardStatus.setText("No card chosen yet.");
        }
        fileStatus.setText("No file chosen yet.");
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
        android.widget.Toast.makeText(this, "Open Settings, Storage, and eject the drive there.",
                android.widget.Toast.LENGTH_LONG).show();
    }

    /** After a write or a removal: say it where the owner is looking, then offer the eject screen. */
    private void done(String message, String next) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        if (dialog != null) {
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(message)
                .setMessage("Eject the drive in Android before you unplug the cable or take the card out, so everything"
                        + " written reaches the card. Android does not let apps eject a drive, so this opens the storage"
                        + " settings: tap the USB drive or card there, then Eject.\n\n" + next)
                .setPositiveButton("Open storage settings", (di, w) -> openStorageSettings())
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

    private static String why(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    // --- the update file ----------------------------------------------------------------------------------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, PICK_FILE);
        } catch (ActivityNotFoundException | SecurityException e) {
            fileStatus.setText("This phone has no app that can pick a file (" + why(e) + "). Open the file from a file"
                    + " manager with \"Open with\" or \"Share\" instead.");
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
            fileStatus.setText("This phone has no app to save a file with (" + why(e) + ").");
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
        writeStatus.setText("Saving a copy…");
        WORKER.execute(() -> {
            String text;
            try {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    if (out == null) {
                        throw new IOException("the other app gave nothing to write to");
                    }
                    out.write(data);
                }
                String sha = sha256Of(uri);
                String name = displayName(uri);
                if (!check.sha256.equals(sha)) {
                    text = "The saved copy reads back differently (SHA-256 " + sha + "); do not use it.";
                } else if (!check.cardName.equals(name)) {
                    text = "Saved and read back, but as \"" + name + "\". Rename it to " + check.cardName
                            + " exactly before copying it to the card, or the camera will not find it.";
                } else {
                    text = "Saved " + name + " and read it back: it matches. Copy it to the card's top level with the"
                            + " Files app, then choose the copy in step 1 to have it checked there.";
                }
            } catch (IOException | RuntimeException e) {
                text = "Could not save the copy: " + why(e);
            }
            final String tt = text;
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                writeStatus.setText(tt);
            });
        });
    }

    /** Download the public stock dump from its pinned upstream address, then ask which candidate to make. */
    private void downloadDump() {
        dismissDialog();
        final int request = ++fileRequest;
        fileData = null;
        fileCheck = null;
        if (!busy) {
            writeStatus.setText("");
        }
        if (stockDump != null) {               // already downloaded and checked: choose again without a new download
            chooseCandidate(stockDump, request);
            return;
        }
        fileStatus.setText("Downloading the stock dump from GitHub (2 MiB)…");
        downloading = true;
        updateButtons();
        NET.execute(() -> {
            byte[] dump = null;
            String problem = null;
            try {
                dump = Candidates.stockDump(fetch(Candidates.STOCK_DUMP_DOWNLOAD));
            } catch (IOException | RuntimeException e) {
                problem = why(e);
            }
            final byte[] d = dump;
            final String p = problem;
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
                    fileStatus.setText("Could not get the stock dump: " + p + "\nCheck the connection and try again, or"
                            + " download it in the browser from the page linked above and choose it with \"Use a file on"
                            + " the phone\".");
                    updateButtons();
                    return;
                }
                chooseCandidate(d, request);
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
                throw new IOException("GitHub answered " + code);
            }
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private void chooseCandidate(byte[] dump, int request) {
        stockDump = dump;
        if (dialog != null) {
            fileStatus.setText("The stock dump is downloaded and checked. Tap \"Download the stock restore file\" to"
                    + " make the file (no new download is needed).");
            updateButtons();
            return;
        }
        final Candidates.Kind[] kinds = Candidates.Kind.offeredKinds();
        if (kinds.length == 1) {
            buildCandidate(dump, kinds[0], request);
            return;
        }
        final boolean[] picked = {false};
        String[] labels = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            labels[i] = kinds[i].label;
        }
        fileStatus.setText("The stock dump checks out (SHA-256 " + Candidates.STOCK_DUMP_SHA256 + "). Choose which"
                + " file to make.");
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Which file?")
                .setItems(labels, (di, which) -> {
                    picked[0] = true;
                    buildCandidate(dump, kinds[which], request);
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnDismissListener(di -> {
            if (dialog == di) {
                dialog = null;
            }
            if (!gone()) {
                if (!picked[0] && request == fileRequest) {
                    fileStatus.setText("No file made yet. Tap \"Download the stock restore file\" to choose (no new"
                            + " download is needed).");
                }
                updateButtons();
            }
        });
        dialog = d;
        d.show();
        updateButtons();
    }

    private void buildCandidate(byte[] dump, Candidates.Kind kind, int request) {
        if (request != fileRequest) {
            return;
        }
        fileStatus.setText("Making " + kind.fileName + "…");
        WORKER.execute(() -> {
            byte[] data = null;
            UpdateCheck.Result result = null;
            String text;
            try {
                data = Candidates.build(dump, kind);
                result = UpdateCheck.check(kind.fileName, data);
                text = "Made " + kind.fileName + " from the stock dump. Its SHA-256 " + result.sha256 + " matches the"
                        + " published file.\n" + kind.label + "\n\n" + result.report() + "\n"
                        + (result.ok ? "OK to put on the card." : "Not OK: this file will not be written.");
            } catch (RuntimeException e) {
                data = null;
                result = null;
                text = "Could not make the file: " + why(e);
            }
            final byte[] dd = data;
            final UpdateCheck.Result r = result;
            final String tt = text;
            ui.post(() -> {
                if (gone() || request != fileRequest) {
                    return;
                }
                fileData = dd;
                fileCheck = r;
                fileStatus.setText(tt);
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
            fileStatus.setText("Could not read what the other app sent (" + why(e) + "). Choose the file here instead.");
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
        fileStatus.setText("Reading and checking…");
        fileData = null;
        fileCheck = null;
        if (!busy) {
            writeStatus.setText("");
        }
        updateButtons();
        WORKER.execute(() -> {
            String name = displayName(uri);
            String text;
            byte[] data = null;
            UpdateCheck.Result result = null;
            byte[] stock = null;
            try {
                data = readAll(getContentResolver().openInputStream(uri));
                stock = data.length == Candidates.DUMP_SIZE && Candidates.isStockDump(data) ? Candidates.stockDump(data) : null;
                if (stock != null) {
                    text = "";
                } else if (data.length == Candidates.DUMP_SIZE) {
                    String why = "";
                    try {
                        Candidates.stockDump(data);
                    } catch (IllegalArgumentException e) {
                        why = e.getMessage();
                    }
                    text = name + "\nThis is a 2 MiB dump, but " + why + "\nA dump never goes on a card as it is.";
                } else {
                    result = UpdateCheck.check(name, data);
                    text = name + "\n" + data.length + " bytes, SHA-256 " + result.sha256 + "\n\n" + result.report()
                            + "\n" + (result.ok ? "OK to put on the card." : "Not OK: this file will not be written. Fix the FAIL lines first.");
                }
            } catch (IOException | RuntimeException e) {
                data = null;
                result = null;
                stock = null;
                text = "Could not read the file: " + why(e);
            }
            final byte[] d = data;
            final UpdateCheck.Result r = result;
            final String t = text;
            final byte[] s = stock;
            ui.post(() -> {
                if (gone() || request != fileRequest) {
                    return;
                }
                if (s != null) {
                    chooseCandidate(s, request);
                    return;
                }
                fileData = d;
                fileCheck = r;
                fileStatus.setText(t);
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
            throw new IOException("the provider returned no data");
        }
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(UpdateCheck.UPDATE_SIZE);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_FILE) {
                    throw new IOException("larger than 4 MiB; not an update file");
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
            cardStatus.setText("This phone has no folder picker (" + why(e) + "), so the app cannot reach the card."
                    + " Save the update file with \"Save a copy to the phone\", copy it to the card's top level with the"
                    + " Files app, then choose that copy in step 1 to have it checked.");
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
                cardStatus.setText("The picker returned a single document, not a folder (" + uri + "). Choose the card"
                        + " again in Android's own picker and tap \"Use this folder\" at its top level.");
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
                note = "Android did not grant lasting access to that folder (" + why(e) + "), so the app will ask for"
                        + " the card again next time.";
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
        cardStatus.setText("Looking at the card…");
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
                cardStatus.setText(c.state.describe() + (note == null ? "" : "\n\n" + note));
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
            c.state = CardState.unreadable("The card cannot be read (" + why(e) + "). Is it still in the phone or"
                    + " connected? Choose it again if this stays.");
        }
        return c;
    }

    private void fillCard(Card c) {
        Uri t = c.tree;
        CardState s = c.state;
        if (!EXTERNAL_STORAGE.equals(t.getAuthority())) {
            s.problem = "That folder is not on a storage device (it came from " + t.getAuthority() + "). Choose the SD"
                    + " card or USB drive itself.";
            return;
        }
        String docId = DocumentsContract.getTreeDocumentId(t);
        if (docId.startsWith("primary:")) {
            s.problem = "That is the phone's internal storage, not the SD card. Choose the card or USB drive in the"
                    + " picker's menu.";
            return;
        }
        if (!docId.endsWith(":")) {
            s.problem = "That is the folder \"" + docId.substring(docId.indexOf(':') + 1) + "\" on the card. The camera"
                    + " only looks in the top level: choose the card itself. On Android 11 and later the top level of a"
                    + " card in the phone's own slot cannot be chosen: use a USB card reader or the camera's USB-C cable.";
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
            writeStatus.setText(refusal);
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Write to the card?")
                .setMessage("File: " + name + "\nSize: " + check.size + " bytes\nSHA-256: " + check.sha256
                        + "\nCard: " + (c.state.label == null ? "the chosen card" : c.state.label) + ", top folder\n\n"
                        + "This only copies a file. The camera flashes itself the next time it powers on with this card in it.")
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
        writeStatus.setText("Checking the copy already on the card…");
        updateButtons();
        WORKER.execute(() -> {
            String sha;
            try {
                sha = sha256Of(DocumentsContract.buildDocumentUriUsingTree(c.tree, same.documentId));
            } catch (RuntimeException e) {
                sha = "unreadable: " + why(e);
            }
            final boolean equal = check.sha256.equals(sha);
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                busy = false;
                writeStatus.setText(equal
                        ? check.cardName + " is already on the card, byte for byte. Nothing to do."
                        : "A different file named " + same.name + " is on the card. Remove update files first, then"
                        + " write again.");
                updateButtons();
            });
        });
    }

    private void write(Card c, byte[] data, UpdateCheck.Result check) {
        busy = true;
        writeStatus.setText("Writing…");
        updateButtons();
        WORKER.execute(() -> {
            String outcome = writeAndReport(c.tree, data, check);
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                busy = false;
                writeStatus.setText(outcome);
                inspectCard();
                if (outcome.startsWith("Written and read back")) {
                    done("Written to the card", "Then unplug the cable and follow step 4.");
                }
            });
        });
    }

    /** Runs on the worker. Checks again, lists the card again, writes, reads back; says what is on the card after a failure. */
    private String writeAndReport(Uri t, byte[] data, UpdateCheck.Result check) {
        String refusal = UpdateCheck.confirms(check, data);
        if (refusal != null) {
            return "Not written: " + refusal + ". Nothing was written to the card.";
        }
        Card now = readCard(t);
        refusal = now.state.writeRefusal();
        if (refusal != null || now.root == null) {
            return "Not written. Looking at the card again just before writing: "
                    + (refusal == null ? "the card cannot be read." : refusal) + " Nothing was written to the card.";
        }
        try {
            return writeAndVerify(now, data, check);
        } catch (IOException | RuntimeException e) {
            return "Writing failed: " + why(e) + "\n\n" + readCard(t).state.afterwards();
        }
    }

    private String writeAndVerify(Card c, byte[] data, UpdateCheck.Result check) throws IOException {
        ContentResolver r = getContentResolver();
        String name = check.cardName;
        Uri temp = DocumentsContract.createDocument(r, c.root, "application/octet-stream", TEMP_NAME);
        if (temp == null) {
            throw new IOException("the card refused to create a file");
        }
        writeFully(temp, data);
        Uri doc;
        try {
            doc = DocumentsContract.renameDocument(r, temp, name);
            if (doc == null) {
                // Android 7 reports a failed rename as null instead of an exception.
                throw new IOException("the card did not rename " + TEMP_NAME);
            }
        } catch (IOException | RuntimeException e) {
            // Rename is optional for providers: delete the temporary file and write under the final name directly.
            String failed = delete(temp);
            if (failed != null) {
                throw new IOException("renaming " + TEMP_NAME + " failed (" + why(e) + "), and so did deleting it ("
                        + failed + ")");
            }
            doc = DocumentsContract.createDocument(r, c.root, "application/octet-stream", name);
            if (doc == null) {
                throw new IOException("the card refused to create " + name);
            }
            writeFully(doc, data);
        }
        String actual = documentName(doc);
        if (!name.equals(actual)) {
            String failed = delete(doc);
            throw new IOException("the card stored the file as \"" + actual + "\" instead of " + name + ", which the"
                    + " camera would not find; " + (failed == null ? "it was deleted" : "deleting it failed (" + failed + ")"));
        }
        String sha = sha256Of(doc);
        if (!check.sha256.equals(sha)) {
            String failed = delete(doc);
            throw new IOException("reading the copy back gave a different SHA-256 (" + sha + "); "
                    + (failed == null ? "the copy was deleted" : "deleting it failed (" + failed + ")")
                    + ". The card may be failing.");
        }
        return "Written and read back: " + name + " matches, SHA-256 " + sha + ".\n\nEject the card in Android before"
                + " you take it out or unplug the cable, then follow the camera steps below.";
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
                throw new IOException("the card did not open the file for writing");
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

    private String sha256Of(Uri doc) {
        try (InputStream in = getContentResolver().openInputStream(doc)) {
            if (in == null) {
                return "";
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return UpdateCheck.toHex(md.digest());
        } catch (Exception e) {
            return "unreadable: " + why(e);
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
                .setTitle("Remove these from the card?")
                .setMessage(names.toString() + "\nOnly these files in the card's top folder are deleted. Photos are not touched.")
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
        cleanStatus.setText("Removing…");
        updateButtons();
        WORKER.execute(() -> {
            StringBuilder sb = new StringBuilder();
            for (CardState.Item f : files) {
                String failed;
                try {
                    failed = delete(DocumentsContract.buildDocumentUriUsingTree(t, f.documentId));
                } catch (RuntimeException e) {
                    failed = why(e);
                }
                sb.append(failed == null ? "Removed " + f.name : "Could not remove " + f.name + ": " + failed).append('\n');
            }
            final boolean allRemoved = sb.indexOf("Could not remove") < 0;
            sb.append('\n').append(readCard(t).state.afterwards());
            final String s = sb.toString().trim();
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                busy = false;
                cleanStatus.setText(s);
                inspectCard();
                if (allRemoved) {
                    done("Update file removed", "Then unplug the cable. The camera is ready to use normally.");
                }
            });
        });
    }
}
