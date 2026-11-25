package com.teawhale.chatdemo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves copies of picked files from the app cache.
 *
 * The file chooser hands back arbitrary content:// URIs whose MIME type and
 * display name often do not match the page's accept list (Android photos are
 * frequently HEIC, gallery entries may have no extension at all). The WebView
 * silently drops results that fail that filter. Returning our own URIs lets us
 * control both the extension and the MIME type so the page always receives the
 * file and can show its own validation message.
 */
public class PickedFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.teawhale.chatdemo.picked";

    static File cacheDir(android.content.Context context) {
        File dir = new File(context.getCacheDir(), "picked");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = new File(uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment())
                .getName();
        File file = new File(cacheDir(getContext()), name);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        return name == null ? "application/octet-stream" : MainActivity.mimeForName(name);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
