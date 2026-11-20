package com.musicclip.visualizer.source;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;

/** Local gallery pick (photo picker / ACTION_GET_CONTENT) - no runtime permission needed. */
public class GallerySource extends VisualSource {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public String siteName() {
        return "Thiết bị";
    }

    @Override
    public boolean needsPick() {
        return true;
    }

    @Override
    public void fetch(Context context, int maxW, int maxH, Callback callback) {
        callback.onError("pick-required");
    }

    /** Decodes a content:// URI on a background thread. */
    public static void fetchUri(final Context context, final android.net.Uri uri,
                                final int maxW, final int maxH, final Callback callback) {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                InputStream in = null;
                try {
                    in = context.getContentResolver().openInputStream(uri);
                    if (in == null) throw new Exception("cannot open " + uri);
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    final Bitmap bmp = BitmapLoader.decode(out.toByteArray(), maxW, maxH);
                    if (bmp == null) throw new Exception("decode failed");
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onImage(bmp, "Ảnh của tôi");
                        }
                    });
                } catch (final Exception e) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(String.valueOf(e.getMessage()));
                        }
                    });
                } finally {
                    try {
                        if (in != null) in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }
}
