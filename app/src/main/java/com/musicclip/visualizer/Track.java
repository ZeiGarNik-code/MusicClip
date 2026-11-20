package com.musicclip.visualizer;

import android.net.Uri;

/** Model of one playable track: built-in asset or a device (MediaStore) song. */
public class Track {
    public final String id;
    public final String title;
    public final String artist;
    public final long durationMs;
    public final boolean builtin;
    /** Path inside assets/ (e.g. "music/neon_skyline.wav") when {@link #builtin}. */
    public final String assetPath;
    /** content:// URI when not builtin. */
    public final Uri uri;

    private Track(String id, String title, String artist, long durationMs,
                  boolean builtin, String assetPath, Uri uri) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.durationMs = durationMs;
        this.builtin = builtin;
        this.assetPath = assetPath;
        this.uri = uri;
    }

    public static Track builtin(String id, String title, String artist, String assetPath) {
        return new Track(id, title, artist, 0, true, assetPath, null);
    }

    public static Track device(String id, String title, String artist, long durationMs, Uri uri) {
        return new Track(id, title, artist, durationMs, false, null, uri);
    }

    public String subLabel() {
        StringBuilder sb = new StringBuilder();
        if (artist != null && artist.length() > 0 && !artist.equals("<unknown>")) {
            sb.append(artist);
        }
        if (durationMs > 0) {
            if (sb.length() > 0) sb.append(" · ");
            int s = (int) (durationMs / 1000);
            sb.append(String.format("%d:%02d", s / 60, s % 60));
        }
        if (sb.length() == 0) sb.append(builtin ? "Nhạc tích hợp" : "Thiết bị");
        return sb.toString();
    }
}
