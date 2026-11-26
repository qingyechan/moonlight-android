package com.limelight.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Serves downloaded APK files from app cache/updates via content:// URI for system installer.
 */
public class ApkUpdateProvider extends ContentProvider {

    private static final String UPDATES_SUBDIR = "updates";

    public static File getUpdatesDir(Context context) {
        File dir = new File(context.getCacheDir(), UPDATES_SUBDIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static Uri getApkUri(Context context, String fileName) {
        return new Uri.Builder()
                .scheme("content")
                .authority("update." + context.getPackageName())
                .appendPath(fileName)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Read-only provider");
        }
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("Missing context");
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")) {
            throw new FileNotFoundException("Invalid path");
        }

        try {
            File baseDir = getUpdatesDir(context).getCanonicalFile();
            File target = new File(baseDir, name).getCanonicalFile();
            if (!target.getPath().startsWith(baseDir.getPath() + File.separator) || !target.exists()) {
                throw new FileNotFoundException("APK not found");
            }
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
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
