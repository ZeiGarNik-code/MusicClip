package com.musicclip.visualizer.source;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;

/** Decoding helpers: down-sample to fit + EXIF rotation. */
public final class BitmapLoader {
    private BitmapLoader() {}

    public static Bitmap decode(byte[] data, int maxW, int maxH) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (bmp == null) return null;

        int rotation = readExifRotation(data);
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) {
                bmp.recycle();
                bmp = rotated;
            }
        }
        return bmp;
    }

    private static int readExifRotation(byte[] data) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(data));
            int orient = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orient) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }
}
