package io.github.daemonddev.charmera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

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
    private static final Pattern UPDATE_FILE = Pattern.compile("(?i)^JH_.*\\.bin$|^charmera-stage.*\\.part$");
    private static final int MAX_FILE = 4 * 1024 * 1024;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView fileStatus;
    private TextView cardStatus;
    private TextView writeStatus;
    private Button writeButton;
    private Button cleanButton;

    private byte[] fileData;
    private UpdateCheck.Result fileCheck;
    private Uri tree;
    private Card card;

    /** What was found on the card the last time it was looked at. */
    private static final class Card {
        Uri root;
        String label;
        long available = -1;
        boolean hasDcim;
        final List<String[]> updateFiles = new ArrayList<>();   // {documentId, displayName, size}
        String problem;
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

        col.addView(heading("1. The update file"));
        col.addView(body("Pick the JH_5408…bin file that charmera pack wrote. It is checked the way charmera verify"
                + " checks it before anything goes near the card."));
        col.addView(button("Choose update file", v -> pickFile()));
        fileStatus = mono();
        col.addView(fileStatus);

        col.addView(heading("2. The SD card"));
        col.addView(body("Put the camera's card in the phone (slot or USB card reader). In the picker, open the card"
                + " itself and tap \"Use this folder\" at its top level, not inside DCIM."));
        col.addView(button("Choose the SD card", v -> pickCard()));
        cardStatus = mono();
        col.addView(cardStatus);

        col.addView(heading("3. Write"));
        writeButton = button("Write the file to the card", v -> confirmWrite());
        col.addView(writeButton);
        writeStatus = body("");
        col.addView(writeStatus);
        cleanButton = button("Remove update files from the card", v -> confirmClean());
        col.addView(cleanButton);

        col.addView(heading("Then, on the camera"));
        col.addView(body("1. Eject the card in Android (Settings, Storage, SD card, Eject) before taking it out.\n"
                + "2. Camera off, card in, hold the power key. The camera finds the file in its first five seconds and"
                + " shows the update progress. Do not touch anything until it finishes.\n"
                + "3. Power off, take the card out, put it back in the phone and tap \"Remove update files\". The camera"
                + " does not delete the file and would flash it again at every power-on.\n"
                + "\"Program error\" on the camera means the file's sum did not match; nothing was erased."));

        col.addView(heading("If the card does not show up"));
        TextView help = body("A card the camera has used has a flag set that Android refuses, so the phone offers to"
                + " format it instead. Either format it in the phone (this erases it) or run charmera card-fix on a"
                + " computer once. Details: docs/05-CARDS.md and docs/06-ANDROID.md in the toolkit.");
        col.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        setContentView(scroll);

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null);
        if (saved != null && hasPermission(Uri.parse(saved))) {
            tree = Uri.parse(saved);
            inspectCard();
        } else {
            cardStatus.setText("No card chosen yet.");
        }
        fileStatus.setText("No file chosen yet.");
        updateButtons();
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
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
        boolean fileOk = fileCheck != null && fileCheck.ok;
        boolean cardOk = card != null && card.problem == null;
        writeButton.setEnabled(fileOk && cardOk);
        cleanButton.setEnabled(card != null && card.root != null && !card.updateFiles.isEmpty());
    }

    // --- the update file ----------------------------------------------------------------------------------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_FILE);
    }

    /** "Open with" or "Share" from a file manager. */
    private void handleIncoming(Intent intent) {
        if (intent == null) {
            return;
        }
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) {
            loadFile(uri);
        }
    }

    private void loadFile(Uri uri) {
        fileStatus.setText("Reading and checking…");
        fileData = null;
        fileCheck = null;
        updateButtons();
        worker.execute(() -> {
            String name = displayName(uri);
            String text;
            byte[] data = null;
            UpdateCheck.Result result = null;
            try {
                data = readAll(getContentResolver().openInputStream(uri));
                result = UpdateCheck.check(name, data);
                text = name + "\n" + data.length + " bytes, SHA-256 " + result.sha256 + "\n\n" + result.report()
                        + "\n" + (result.ok ? "OK to put on the card." : "Not OK: this file will not be written. Fix the FAIL lines first.");
            } catch (IOException | SecurityException e) {
                text = "Could not read the file: " + e.getMessage();
            }
            final byte[] d = data;
            final UpdateCheck.Result r = result;
            final String t = text;
            ui.post(() -> {
                fileData = d;
                fileCheck = r;
                fileStatus.setText(t);
                writeStatus.setText("");
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
        startActivityForResult(i, PICK_CARD);
    }

    private boolean hasPermission(Uri uri) {
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(uri) && p.isReadPermission() && p.isWritePermission()) {
                return true;
            }
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
        } else if (request == PICK_CARD) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException e) {
                cardStatus.setText("Android did not grant lasting access to that folder: " + e.getMessage());
            }
            tree = uri;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply();
            inspectCard();
        }
    }

    private void inspectCard() {
        cardStatus.setText("Looking at the card…");
        card = null;
        updateButtons();
        final Uri t = tree;
        worker.execute(() -> {
            Card c = readCard(t);
            ui.post(() -> {
                card = c;
                cardStatus.setText(describe(c));
                updateButtons();
            });
        });
    }

    private Card readCard(Uri t) {
        Card c = new Card();
        String docId = DocumentsContract.getTreeDocumentId(t);
        if (!EXTERNAL_STORAGE.equals(t.getAuthority())) {
            c.problem = "That folder is not on a storage device (it came from " + t.getAuthority() + "). Choose the SD card"
                    + " itself.";
            return c;
        }
        if (docId.startsWith("primary:")) {
            c.problem = "That is the phone's internal storage, not the SD card. Choose the card in the picker's menu.";
            return c;
        }
        if (!docId.endsWith(":")) {
            c.problem = "That is the folder \"" + docId.substring(docId.indexOf(':') + 1) + "\" on the card. The camera"
                    + " only looks in the top level: choose the card itself.";
            return c;
        }
        c.root = DocumentsContract.buildDocumentUriUsingTree(t, docId);
        ContentResolver r = getContentResolver();
        try (Cursor q = r.query(c.root, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                c.label = q.getString(0);
            }
        } catch (RuntimeException e) {
            c.problem = "The card cannot be read (" + e.getMessage() + "). Is it still in the phone? Choose it again.";
            return c;
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(t, docId);
        try (Cursor q = r.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (q != null && q.moveToNext()) {
                String name = q.getString(1);
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(q.getString(3));
                if (dir && "DCIM".equalsIgnoreCase(name)) {
                    c.hasDcim = true;
                } else if (!dir && name != null && UPDATE_FILE.matcher(name).matches()) {
                    c.updateFiles.add(new String[]{q.getString(0), name, q.isNull(2) ? "?" : String.valueOf(q.getLong(2))});
                }
            }
        } catch (RuntimeException e) {
            c.problem = "The card's top folder cannot be listed (" + e.getMessage() + "). Choose the card again.";
            return c;
        }
        c.available = availableBytes(docId);
        if (c.available >= 0 && c.available < UpdateCheck.UPDATE_SIZE + 64 * 1024) {
            c.problem = "Not enough free space on the card (" + c.available + " bytes free).";
        }
        return c;
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

    private String describe(Card c) {
        StringBuilder sb = new StringBuilder();
        if (c.label != null) {
            sb.append("Card: ").append(c.label).append('\n');
        }
        if (c.available >= 0) {
            sb.append(String.format(Locale.ROOT, "Free: %.1f MB\n", c.available / 1e6));
        }
        if (c.root != null) {
            sb.append(c.hasDcim ? "Has a DCIM folder: looks like the camera's card.\n"
                    : "No DCIM folder: make sure this is the card you mean.\n");
            if (c.updateFiles.isEmpty()) {
                sb.append("No update files in the top folder.\n");
            } else {
                sb.append("Update files already in the top folder:\n");
                for (String[] f : c.updateFiles) {
                    sb.append("  ").append(f[1]).append(" (").append(f[2]).append(" bytes)\n");
                }
            }
        }
        if (c.problem != null) {
            sb.append('\n').append(c.problem);
        }
        return sb.toString().trim();
    }

    // --- writing ------------------------------------------------------------------------------------------------

    private void confirmWrite() {
        if (fileCheck == null || !fileCheck.ok || card == null || card.problem != null) {
            return;
        }
        String name = fileCheck.cardName;
        for (String[] f : card.updateFiles) {
            if (f[1].equalsIgnoreCase(name) && String.valueOf(fileCheck.size).equals(f[2])) {
                writeStatus.setText("Checking the copy already on the card…");
                final String id = f[0];
                worker.execute(() -> {
                    String sha = sha256Of(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                    boolean same = fileCheck.sha256.equals(sha);
                    ui.post(() -> {
                        if (same) {
                            writeStatus.setText(name + " is already on the card, byte for byte. Nothing to do.");
                        } else {
                            writeStatus.setText("A different file named " + name + " is on the card. Remove update files"
                                    + " first, then write again.");
                        }
                    });
                });
                return;
            }
        }
        if (!card.updateFiles.isEmpty()) {
            writeStatus.setText("The card already holds another update file (listed above). Two on one card is not a"
                    + " situation the camera is known to handle. Tap \"Remove update files\" first.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Write to the card?")
                .setMessage("File: " + name + "\nSize: " + fileCheck.size + " bytes\nSHA-256: " + fileCheck.sha256
                        + "\nCard: " + (card.label == null ? "the chosen card" : card.label) + ", top folder\n\n"
                        + "This only copies a file. The camera flashes itself the next time it powers on with this card in it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Write", (d, w) -> write())
                .show();
    }

    private void write() {
        writeStatus.setText("Writing…");
        writeButton.setEnabled(false);
        final byte[] data = fileData;
        final UpdateCheck.Result check = fileCheck;
        final Card c = card;
        worker.execute(() -> {
            String outcome;
            try {
                outcome = writeAndVerify(c, data, check);
            } catch (IOException | RuntimeException e) {
                outcome = "Writing failed: " + e.getMessage() + "\nNothing usable was left on the card under the update"
                        + " name; check with \"Remove update files\" before you try again.";
            }
            final String o = outcome;
            ui.post(() -> {
                writeStatus.setText(o);
                inspectCard();
            });
        });
    }

    private String writeAndVerify(Card c, byte[] data, UpdateCheck.Result check) throws IOException {
        ContentResolver r = getContentResolver();
        String name = check.cardName;
        Uri doc;
        Uri temp = DocumentsContract.createDocument(r, c.root, "application/octet-stream", TEMP_NAME);
        if (temp == null) {
            throw new IOException("the card refused to create a file");
        }
        writeFully(temp, data);
        try {
            doc = DocumentsContract.renameDocument(r, temp, name);
            if (doc == null) {
                doc = temp;
            }
        } catch (IOException | RuntimeException e) {
            // Rename is optional for providers; fall back to writing under the final name directly.
            DocumentsContract.deleteDocument(r, temp);
            doc = DocumentsContract.createDocument(r, c.root, "application/octet-stream", name);
            if (doc == null) {
                throw new IOException("the card refused to create " + name);
            }
            writeFully(doc, data);
        }
        String actual = documentName(doc);
        if (!name.equals(actual)) {
            DocumentsContract.deleteDocument(r, doc);
            throw new IOException("the card stored the file as \"" + actual + "\" instead of " + name
                    + ", which the camera would not find; it was deleted");
        }
        String sha = sha256Of(doc);
        if (!check.sha256.equals(sha)) {
            DocumentsContract.deleteDocument(r, doc);
            throw new IOException("reading the copy back gave a different SHA-256 (" + sha + "); the copy was deleted."
                    + " The card may be failing.");
        }
        return "Written and read back: " + name + " matches, SHA-256 " + sha + ".\n\nEject the card in Android before"
                + " you take it out, then follow the camera steps below.";
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
            return "unreadable: " + e.getMessage();
        }
    }

    // --- cleaning up --------------------------------------------------------------------------------------------

    private void confirmClean() {
        if (card == null || card.updateFiles.isEmpty()) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (String[] f : card.updateFiles) {
            names.append(f[1]).append('\n');
        }
        new AlertDialog.Builder(this)
                .setTitle("Remove these from the card?")
                .setMessage(names.toString() + "\nOnly these files in the card's top folder are deleted. Photos are not touched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> clean())
                .show();
    }

    private void clean() {
        final Card c = card;
        writeStatus.setText("Removing…");
        worker.execute(() -> {
            StringBuilder sb = new StringBuilder();
            for (String[] f : c.updateFiles) {
                try {
                    boolean ok = DocumentsContract.deleteDocument(getContentResolver(),
                            DocumentsContract.buildDocumentUriUsingTree(tree, f[0]));
                    sb.append(ok ? "Removed " : "Could not remove ").append(f[1]).append('\n');
                } catch (IOException | RuntimeException e) {
                    sb.append("Could not remove ").append(f[1]).append(": ").append(e.getMessage()).append('\n');
                }
            }
            final String s = sb.toString().trim();
            ui.post(() -> {
                writeStatus.setText(s);
                inspectCard();
            });
        });
    }
}
