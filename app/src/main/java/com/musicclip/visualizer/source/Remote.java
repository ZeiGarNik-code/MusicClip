package com.musicclip.visualizer.source;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny HTTP + JSON helper (no third-party libraries). */
public final class Remote {
    public static final String UA = "MusicClipVisualizer/1.0 (open-source music visualizer)";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Remote() {}

    public static void run(final Runnable task, final Runnable onMain) {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                task.run();
                if (onMain != null) MAIN.post(onMain);
            }
        });
    }

    public static byte[] download(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", UA);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            // follow cross-host redirects manually (picsum -> fastly etc.)
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                if (loc != null) {
                    conn.disconnect();
                    return download(loc);
                }
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " for " + urlStr);
            }
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 40 * 1024 * 1024) throw new IOException("response too large");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    public static JSONObject getJson(String urlStr) throws IOException {
        byte[] data = download(urlStr);
        try {
            return new JSONObject(new String(data, "UTF-8"));
        } catch (Exception e) {
            throw new IOException("bad JSON: " + e.getMessage());
        }
    }
}
