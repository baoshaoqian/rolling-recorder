package com.example.rollingrecorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Re-starts the recording service after the device reboots,
 * but only if the user had recording enabled before the reboot.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (RecordingService.isRecordingEnabled(context)) {
                Log.i(TAG, "Boot completed – restarting recording service");
                Intent serviceIntent = new Intent(context, RecordingService.class);
                ContextCompat.startForegroundService(context, serviceIntent);
            }
        }
    }
}

