package com.example.rollingrecorder;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages recording storage: directory access, free-space checks,
 * oldest-file deletion, and listing recordings for the UI.
 */
public class StorageHelper {

    private static final String TAG = "StorageHelper";
    private static final long MIN_FREE_SPACE_BYTES = 2L * 1024 * 1024 * 1024; // 2 GB
    public static final String RECORDING_DIR_NAME = "RollingRecorder";

    // ── directory helpers ────────────────────────────────────────────────

    /**
     * Returns the physical directory path (used for StatFs & direct file
     * access on API < 29). On API 29+ the directory may only exist
     * virtually through MediaStore until a file is actually written.
     */
    public static File getRecordingDir() {
        File musicDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC);
        File recordingDir = new File(musicDir, RECORDING_DIR_NAME);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !recordingDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            recordingDir.mkdirs();
        }
        return recordingDir;
    }

    // ── storage-space guard ─────────────────────────────────────────────

    /**
     * Ensures at least {@link #MIN_FREE_SPACE_BYTES} of free space is
     * available on the external storage volume. Deletes oldest recordings
     * one-by-one until the threshold is met or no more files remain.
     *
     * @return true if enough space is available after cleanup.
     */
    public static boolean ensureStorageAvailable(Context context) {
        File storageDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC);
        StatFs statFs = new StatFs(storageDir.getAbsolutePath());
        long freeBytes = statFs.getAvailableBytes();

        while (freeBytes < MIN_FREE_SPACE_BYTES) {
            boolean deleted = deleteOldestRecording(context);
            if (!deleted) {
                Log.w(TAG, "No more recordings to delete but storage is still low");
                return false;
            }
            statFs = new StatFs(storageDir.getAbsolutePath());
            freeBytes = statFs.getAvailableBytes();
        }
        return true;
    }

    // ── deletion ────────────────────────────────────────────────────────

    public static boolean deleteRecording(Context context, RecordingItem item) {
        if (item == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return deleteRecordingViaMediaStore(context, item.getUri(), item.getName());
        } else {
            return deleteRecordingViaFile(item.getName());
        }
    }

    private static boolean deleteOldestRecording(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return deleteOldestViaMediaStore(context);
        } else {
            return deleteOldestViaFile();
        }
    }

    private static boolean deleteOldestViaFile() {
        File dir = getRecordingDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".m4a"));
        if (files == null || files.length == 0) return false;

        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        File oldest = files[0];
        return deleteRecordingViaFile(oldest.getName());
    }

    private static boolean deleteRecordingViaFile(String fileName) {
        File recordingFile = new File(getRecordingDir(), fileName);
        if (!recordingFile.exists()) {
            return false;
        }

        Log.i(TAG, "Deleting recording (file): " + recordingFile.getName());
        return recordingFile.delete();
    }

    private static boolean deleteOldestViaMediaStore(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME
        };
        String selection = MediaStore.Audio.Media.RELATIVE_PATH + "=?";
        String[] selectionArgs = {"Music/" + RECORDING_DIR_NAME + "/"};
        String sortOrder = MediaStore.Audio.Media.DATE_MODIFIED + " ASC";

        try (Cursor cursor = resolver.query(
                collection, projection, selection, selectionArgs, sortOrder)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                String name = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME));
                Uri deleteUri = Uri.withAppendedPath(collection, String.valueOf(id));
                return deleteRecordingViaMediaStore(context, deleteUri, name);
            }
        }
        return false;
    }

    private static boolean deleteRecordingViaMediaStore(Context context, Uri deleteUri, String name) {
        ContentResolver resolver = context.getContentResolver();
        int deleted = resolver.delete(deleteUri, null, null);
        if (deleted > 0) {
            Log.i(TAG, "Deleted recording (MediaStore): " + name);
            return true;
        }
        return false;
    }

    // ── listing ─────────────────────────────────────────────────────────

    /**
     * Returns all recordings for display, newest first.
     */
    public static List<RecordingItem> getRecordings(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return getRecordingsFromMediaStore(context);
        } else {
            return getRecordingsFromFileSystem(context);
        }
    }

    private static List<RecordingItem> getRecordingsFromMediaStore(Context context) {
        List<RecordingItem> items = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED
        };
        String selection = MediaStore.Audio.Media.RELATIVE_PATH + "=?";
        String[] selectionArgs = {"Music/" + RECORDING_DIR_NAME + "/"};
        String sortOrder = MediaStore.Audio.Media.DATE_MODIFIED + " DESC";

        try (Cursor cursor = resolver.query(
                collection, projection, selection, selectionArgs, sortOrder)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow(
                            MediaStore.Audio.Media._ID));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(
                            MediaStore.Audio.Media.DISPLAY_NAME));
                    long size = cursor.getLong(cursor.getColumnIndexOrThrow(
                            MediaStore.Audio.Media.SIZE));
                    long date = cursor.getLong(cursor.getColumnIndexOrThrow(
                            MediaStore.Audio.Media.DATE_MODIFIED));
                    Uri contentUri = Uri.withAppendedPath(collection, String.valueOf(id));
                    items.add(new RecordingItem(name, size, date, contentUri));
                }
            }
        }
        return items;
    }

    private static List<RecordingItem> getRecordingsFromFileSystem(Context context) {
        List<RecordingItem> items = new ArrayList<>();
        File dir = getRecordingDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".m4a"));
        if (files == null) return items;

        // newest first
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        for (File file : files) {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
            items.add(new RecordingItem(
                    file.getName(), file.length(), file.lastModified() / 1000, uri));
        }
        return items;
    }
}


