package com.example.rollingrecorder;

import android.net.Uri;

/**
 * Model representing a single recording file for display in the UI.
 */
public class RecordingItem {
    private final String name;
    private final long size;
    private final long dateModified;
    private final Uri uri;

    public RecordingItem(String name, long size, long dateModified, Uri uri) {
        this.name = name;
        this.size = size;
        this.dateModified = dateModified;
        this.uri = uri;
    }

    public String getName() { return name; }
    public long getSize() { return size; }
    public long getDateModified() { return dateModified; }
    public Uri getUri() { return uri; }
}

