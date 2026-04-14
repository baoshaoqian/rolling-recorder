package com.example.rollingrecorder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextView statusText;
    private MaterialButton toggleButton;
    private MaterialButton batteryButton;
    private RecordingListAdapter adapter;

    // ── lifecycle ───────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        statusText = findViewById(R.id.statusText);
        toggleButton = findViewById(R.id.toggleButton);
        batteryButton = findViewById(R.id.batteryButton);

        // Recording list
        RecyclerView recyclerView = findViewById(R.id.recordingsRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecordingListAdapter(this, new ArrayList<>(), item -> {
            boolean deleted = StorageHelper.deleteRecording(this, item);
            if (deleted) {
                Toast.makeText(this, R.string.recording_deleted, Toast.LENGTH_SHORT).show();
                refreshRecordingList();
            } else {
                Toast.makeText(this, R.string.recording_delete_failed, Toast.LENGTH_LONG).show();
            }
        });
        recyclerView.setAdapter(adapter);

        // Toggle recording
        toggleButton.setOnClickListener(v -> {
            if (RecordingService.isRunning) {
                stopRecordingService();
            } else {
                checkAndRequestPermissions();
            }
        });

        // Battery optimisation
        batteryButton.setOnClickListener(v -> requestBatteryOptimizationExemption());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
        refreshRecordingList();
    }

    // ── permissions ─────────────────────────────────────────────────────

    private void checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (needed.isEmpty()) {
            startRecordingService();
        } else {
            ActivityCompat.requestPermissions(
                    this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) return;

        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            startRecordingService();
        } else {
            Toast.makeText(this,
                    "Permissions are required for recording", Toast.LENGTH_LONG).show();
        }
    }

    // ── service control ─────────────────────────────────────────────────

    private void startRecordingService() {
        Intent intent = new Intent(this, RecordingService.class);
        ContextCompat.startForegroundService(this, intent);
        RecordingService.setRecordingEnabled(this, true);
        // Give the service a moment to start, then update UI
        toggleButton.postDelayed(this::updateUI, 500);
    }

    private void stopRecordingService() {
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(RecordingService.ACTION_STOP);
        startService(intent);
        RecordingService.setRecordingEnabled(this, false);
        toggleButton.postDelayed(this::updateUI, 500);
    }

    // ── battery optimisation ────────────────────────────────────────────

    private void requestBatteryOptimizationExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } else {
            Toast.makeText(this,
                    "Battery optimization is already disabled",
                    Toast.LENGTH_SHORT).show();
            updateUI();
        }
    }

    // ── UI helpers ──────────────────────────────────────────────────────

    private void updateUI() {
        boolean running = RecordingService.isRunning;
        statusText.setText(running ? "Status: Recording" : "Status: Idle");
        toggleButton.setText(running ? "Stop Recording" : "Start Recording");

        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            batteryButton.setVisibility(View.GONE);
        } else {
            batteryButton.setVisibility(View.VISIBLE);
        }
    }

    private void refreshRecordingList() {
        List<RecordingItem> recordings = StorageHelper.getRecordings(this);
        adapter.updateRecordings(recordings);
    }
}
