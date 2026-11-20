package com.musicclip.visualizer.source;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

/** Shared helpers for HTTP-backed sources: cache + download + decode + main-thread callback. */
abstract class HttpSource extends VisualSource {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Returns the concrete image URL to download (may do API lookups first). */
    protected abstract String resolveUrl(int maxW, int maxH) throws Exception;

    protected String creditFor(String url) {
        return siteName();
    }

    @Override
    public void fetch(final Context context, final int maxW, final int maxH, final Callback callback) {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = resolveUrl(maxW, maxH);
                    if (url == null) throw new Exception("empty url");
                    byte[] data = ImageCache.read(context, url);
                    if (data == null) {
                        data = Remote.download(url);
                        ImageCache.write(context, url, data);
                    }
                    final Bitmap bmp = BitmapLoader.decode(data, maxW, maxH);
                    if (bmp == null) throw new Exception("decode failed");
                    final String credit = creditFor(url);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onImage(bmp, credit);
                        }
                    });
                } catch (final Exception e) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(String.valueOf(e.getMessage()));
                        }
                    });
                }
            }
        });
    }
}
