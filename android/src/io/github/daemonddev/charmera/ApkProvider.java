package io.github.daemonddev.charmera;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands the downloaded update APK (cacheDir/{@value #NAME}) to the system package installer as a content:// URI, so
 * the app can offer an update without a file:// URI (which Android 7+ blocks for this) and without a support library.
 * It serves exactly that one file, read-only, and only to whoever the install intent grants read permission to. No
 * Android imports that the checker's JVM tests need, but it is an Android component, so it is not among them.
 */
public final class ApkProvider extends ContentProvider {

    /** The file name in the cache dir, and the last path segment of the content URI. */
    static final String NAME = "update.apk";
    static final String MIME = "application/vnd.android.package-archive";
    private static final String AUTHORITY = "io.github.daemonddev.charmera.updates";

    /** The content URI for the update APK in this app's cache. */
    static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/" + NAME);
    }

    private File file() {
        return new File(getContext().getCacheDir(), NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        // The installer reads the display name and size from these columns before it opens the file.
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{"charmera-card.apk", file().length()});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return MIME;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
}
