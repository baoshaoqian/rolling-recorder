package com.example.rollingrecorder;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
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

    public interface OnRecordingActionListener {
        void onDeleteRequested(RecordingItem item);
    }

    private final Context context;
    private final OnRecordingActionListener actionListener;
    private List<RecordingItem> recordings;

    public RecordingListAdapter(Context context,
                                List<RecordingItem> recordings,
                                OnRecordingActionListener actionListener) {
        this.context = context;
        this.recordings = recordings;
        this.actionListener = actionListener;
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
        holder.itemView.setOnClickListener(v -> openRecording(item));
        holder.itemView.setOnLongClickListener(v -> {
            showContextMenu(v, item);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return recordings.size();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void showContextMenu(View anchor, RecordingItem item) {
        PopupMenu popupMenu = new PopupMenu(context, anchor);
        popupMenu.inflate(R.menu.recording_item_menu);
        popupMenu.setOnMenuItemClickListener(menuItem -> handleMenuAction(menuItem, item));
        popupMenu.show();
    }

    private boolean handleMenuAction(MenuItem menuItem, RecordingItem item) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.action_play_recording) {
            openRecording(item);
            return true;
        }
        if (itemId == R.id.action_delete_recording) {
            if (actionListener != null) {
                actionListener.onDeleteRequested(item);
            }
            return true;
        }
        return false;
    }

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

        ViewHolder(View itemView) {
            super(itemView);
            nameText = itemView.findViewById(R.id.recordingName);
            sizeText = itemView.findViewById(R.id.recordingSize);
        }
    }
}


