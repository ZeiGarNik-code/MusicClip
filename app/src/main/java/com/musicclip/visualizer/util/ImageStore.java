package com.musicclip.visualizer.util;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Saves screenshots to Pictures/MusicClip (MediaStore on API 29+). */
public final class ImageStore {
    public interface Callback {
        void onSaved(String location);

        void onError(String message);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageStore() {}

    public static void savePng(final Context context, final Bitmap bmp, final Callback cb) {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String name = "MusicClip_" + new SimpleDateFormat(
                            "yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                        v.put(MediaStore.Images.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/MusicClip");
                        v.put(MediaStore.Images.Media.IS_PENDING, 1);
                        ContentResolverInsert(context, v, bmp, cb, name);
                        return;
                    }
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES), "MusicClip");
                    if (!dir.exists() && !dir.mkdirs()) {
                        File fallback = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                        if (fallback == null) throw new Exception("không tạo được thư mục");
                        dir = fallback;
                    }
                    File f = new File(dir, name);
                    OutputStream os = new FileOutputStream(f);
                    try {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    } finally {
                        os.close();
                    }
                    MediaScannerConnection.scanFile(context,
                            new String[]{f.getAbsolutePath()}, null, null);
                    postSaved(cb, f.getAbsolutePath());
                } catch (Exception e) {
                    postError(cb, String.valueOf(e.getMessage()));
                }
            }
        });
    }

    private static void ContentResolverInsert(Context context, ContentValues v, Bitmap bmp,
                                              Callback cb, String name) throws Exception {
        android.content.ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("MediaStore insert lỗi");
        OutputStream os = resolver.openOutputStream(uri);
        if (os == null) throw new Exception("không mở được stream");
        try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        } finally {
            os.close();
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, v, null, null);
        postSaved(cb, Environment.DIRECTORY_PICTURES + "/MusicClip/" + name);
    }

    private static void postSaved(final Callback cb, final String location) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onSaved(location);
            }
        });
    }

    private static void postError(final Callback cb, final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(msg);
            }
        });
    }
}
