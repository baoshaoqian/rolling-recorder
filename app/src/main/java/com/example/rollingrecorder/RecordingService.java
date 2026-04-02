package com.example.rollingrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service that continuously records audio as M4A/AAC,
 * splitting files at the 08:00 / 14:00 / 22:00 boundaries.
 */
public class RecordingService extends Service {

    private static final String TAG = "RecordingService";
    public static final String ACTION_STOP = "com.example.rollingrecorder.STOP";
    private static final String CHANNEL_ID = "recording_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "recording_prefs";
    private static final String KEY_ENABLED = "recording_enabled";

    /** Live in-process flag – accurate as long as the process is alive. */
    public static boolean isRunning = false;

    private MediaRecorder recorder;
    private Handler handler;
    private Uri currentMediaStoreUri;
    private ParcelFileDescriptor currentPfd;

    // ── lifecycle ───────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            setRecordingEnabled(false);
            stopRecording();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!isRunning) {
            Notification notification = createNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            setRecordingEnabled(true);
            startRecording();
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }

    // ── recording control ───────────────────────────────────────────────

    private void startRecording() {
        StorageHelper.ensureStorageAvailable(this);

        String fileName = generateFileName();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            recorder = new MediaRecorder(this);
        } else {
            recorder = new MediaRecorder();
        }

        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(44100);
        recorder.setAudioEncodingBitRate(128000);

        recorder.setOnErrorListener((mr, what, extra) -> {
            Log.e(TAG, "MediaRecorder error: what=" + what + " extra=" + extra);
            handler.post(this::rotateRecording);
        });

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setupMediaStoreOutput(fileName);
            } else {
                setupDirectFileOutput(fileName);
            }
            recorder.prepare();
            recorder.start();
            isRunning = true;
            Log.i(TAG, "Recording started: " + fileName);
            scheduleNextRotation();
        } catch (IOException e) {
            Log.e(TAG, "Failed to start recording", e);
            isRunning = false;
        }
    }

    private void stopRecording() {
        handler.removeCallbacksAndMessages(null);

        if (recorder != null) {
            try {
                if (isRunning) {
                    recorder.stop();
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Error stopping recorder", e);
            }
            recorder.release();
            recorder = null;
        }

        finalizeMediaStoreEntry();
        isRunning = false;
    }

    private void rotateRecording() {
        Log.i(TAG, "Rotating recording at time-window boundary");
        stopRecording();
        startRecording();
        updateNotification();
    }

    // ── output file setup ───────────────────────────────────────────────

    private void setupMediaStoreOutput(String fileName) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH,
                "Music/" + StorageHelper.RECORDING_DIR_NAME);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        }

        Uri collection = MediaStore.Audio.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        currentMediaStoreUri = getContentResolver().insert(collection, values);
        if (currentMediaStoreUri == null) {
            throw new IOException("Failed to create MediaStore entry");
        }

        currentPfd = getContentResolver()
                .openFileDescriptor(currentMediaStoreUri, "w");
        if (currentPfd == null) {
            throw new IOException("Failed to open file descriptor for recording");
        }
        recorder.setOutputFile(currentPfd.getFileDescriptor());
    }

    private void setupDirectFileOutput(String fileName) {
        File dir = StorageHelper.getRecordingDir();
        File file = new File(dir, fileName);
        recorder.setOutputFile(file.getAbsolutePath());
    }

    private void finalizeMediaStoreEntry() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && currentMediaStoreUri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.IS_PENDING, 0);
            getContentResolver().update(currentMediaStoreUri, values, null, null);
            currentMediaStoreUri = null;
        }
        if (currentPfd != null) {
            try { currentPfd.close(); } catch (IOException ignored) { }
            currentPfd = null;
        }
    }

    // ── scheduling ──────────────────────────────────────────────────────

    private void scheduleNextRotation() {
        long delayMs = getMillisUntilNextBoundary();
        Log.i(TAG, "Next rotation in " + (delayMs / 1000 / 60) + " minutes");
        handler.postDelayed(this::rotateRecording, delayMs);
    }

    /**
     * Calculates milliseconds from now until the next 08:00 / 14:00 / 22:00
     * boundary in the device's local time zone. Returns at least 1 000 ms
     * to avoid tight loops right at a boundary.
     */
    static long getMillisUntilNextBoundary() {
        Calendar now = Calendar.getInstance();
        Calendar next = (Calendar) now.clone();

        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (hour < 8) {
            next.set(Calendar.HOUR_OF_DAY, 8);
        } else if (hour < 14) {
            next.set(Calendar.HOUR_OF_DAY, 14);
        } else if (hour < 22) {
            next.set(Calendar.HOUR_OF_DAY, 22);
        } else {
            next.add(Calendar.DAY_OF_MONTH, 1);
            next.set(Calendar.HOUR_OF_DAY, 8);
        }
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);

        long delay = next.getTimeInMillis() - now.getTimeInMillis();
        return Math.max(delay, 1000);
    }

    // ── file naming ─────────────────────────────────────────────────────

    private String generateFileName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        return "RR_" + sdf.format(new Date()) + ".m4a";
    }

    // ── notification ────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Recording Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows while Rolling Recorder is capturing audio");
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent mainPi = PendingIntent.getActivity(
                this, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, RecordingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Rolling Recorder")
                .setContentText("Recording audio…")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(mainPi)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
                .setOngoing(true)
                .build();
    }

    @SuppressLint("MissingPermission")
    private void updateNotification() {
        NotificationManager mgr = getSystemService(NotificationManager.class);
        if (mgr != null) mgr.notify(NOTIFICATION_ID, createNotification());
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    // ── persistent "enabled" flag (survives reboots) ────────────────────

    public static void setRecordingEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private void setRecordingEnabled(boolean enabled) {
        setRecordingEnabled(this, enabled);
    }

    public static boolean isRecordingEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }
}





