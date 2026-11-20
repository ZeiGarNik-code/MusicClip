package com.musicclip.visualizer.source;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;

/** Simple disk cache for downloaded images (keyed by URL hash). */
public final class ImageCache {
    private ImageCache() {}

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "visuals");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File fileFor(Context c, String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                sb.append(Integer.toHexString((digest[i] >> 4) & 0xf));
                sb.append(Integer.toHexString(digest[i] & 0xf));
            }
            return new File(dir(c), sb.toString() + ".img");
        } catch (Exception e) {
            return new File(dir(c), String.valueOf(Math.abs(url.hashCode())) + ".img");
        }
    }

    public static byte[] read(Context c, String url) {
        File f = fileFor(c, url);
        if (!f.exists() || f.length() == 0) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return off == data.length ? data : null;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static void write(Context c, String url, byte[] data) {
        if (data == null) return;
        File f = fileFor(c, url);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(data);
        } catch (Exception ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }
}
