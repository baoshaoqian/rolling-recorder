package com.example.rollingrecorder;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DecimalFormat;
import java.util.List;

/**
 * Adapter that displays recording files in a RecyclerView.
 * Tapping the play button opens the file in an external audio player.
 */
public class RecordingListAdapter
        extends RecyclerView.Adapter<RecordingListAdapter.ViewHolder> {

    private final Context context;
    private List<RecordingItem> recordings;

    public RecordingListAdapter(Context context, List<RecordingItem> recordings) {
        this.context = context;
        this.recordings = recordings;
    }

    public void updateRecordings(List<RecordingItem> recordings) {
        this.recordings = recordings;
        notifyDataSetChanged();
    }

    // ── ViewHolder ──────────────────────────────────────────────────────

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recording, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RecordingItem item = recordings.get(position);
        holder.nameText.setText(item.getName());
        holder.sizeText.setText(formatFileSize(item.getSize()));
        holder.openButton.setOnClickListener(v -> openRecording(item));
    }

    @Override
    public int getItemCount() {
        return recordings.size();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void openRecording(RecordingItem item) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(item.getUri(), "audio/mp4");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(intent);
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        DecimalFormat df = new DecimalFormat("#.##");
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return df.format(bytes / 1024.0) + " KB";
        if (bytes < 1024L * 1024 * 1024) return df.format(bytes / (1024.0 * 1024)) + " MB";
        return df.format(bytes / (1024.0 * 1024 * 1024)) + " GB";
    }

    // ── view holder ─────────────────────────────────────────────────────

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView nameText;
        final TextView sizeText;
        final ImageButton openButton;

        ViewHolder(View itemView) {
            super(itemView);
            nameText = itemView.findViewById(R.id.recordingName);
            sizeText = itemView.findViewById(R.id.recordingSize);
            openButton = itemView.findViewById(R.id.openButton);
        }
    }
}


