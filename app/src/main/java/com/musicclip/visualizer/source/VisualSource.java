package com.musicclip.visualizer.source;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A source of free background images. */
public abstract class VisualSource {

    public interface Callback {
        void onImage(Bitmap bitmap, String credit);

        void onError(String message);
    }

    /** Shared background executor for all sources. */
    protected static final ExecutorService EXEC = Executors.newFixedThreadPool(2);

    /** Display label (set by SourceManager from string-array). */
    public String label = "";

    /** Human readable name of the site (for credits). */
    public abstract String siteName();

    /** True when the user must pick a local image (gallery). */
    public boolean needsPick() {
        return false;
    }

    /** True when the user must supply a URL first. */
    public boolean needsInput() {
        return false;
    }

    /** Starts an asynchronous fetch; callbacks arrive on the main thread. */
    public abstract void fetch(Context context, int maxW, int maxH, Callback callback);
}
