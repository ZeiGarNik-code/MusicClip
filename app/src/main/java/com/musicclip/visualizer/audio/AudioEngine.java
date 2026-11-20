package com.musicclip.visualizer.audio;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;
import android.util.Log;

import com.musicclip.visualizer.Track;
import com.musicclip.visualizer.render.AudioFrames;

/**
 * Playback engine: MediaPlayer + {@link Visualizer} FFT analysis.
 * Implements {@link AudioFrames} so the render thread can read band levels.
 */
public class AudioEngine implements AudioFrames {
    private static final String TAG = "AudioEngine";

    public interface Listener {
        void onPrepared();

        void onError(String message);
    }

    private final Object lock = new Object();
    private final float[] smooth = new float[BANDS];
    private final float[] tmpBands = new float[BANDS];
    private final byte[] wave = new byte[512];

    private MediaPlayer player;
    private Visualizer visualizer;
    private Listener listener;
    private boolean reactive;
    private boolean released = true;

    private float bass;
    private float level;
    private float avgBass = 0.3f;
    private float autoGain = 1f;
    private int beatVersion;
    private int consumedBeat = -1;
    private long lastBeatMs;

    public void prepare(Context context, Track track, Listener l) {
        release();
        released = false;
        listener = l;
        player = new MediaPlayer();
        try {
            if (track.builtin) {
                AssetFileDescriptor afd = context.getAssets().openFd(track.assetPath);
                player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            } else {
                player.setDataSource(context, track.uri);
            }
            player.setLooping(true);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    attachVisualizer(mp.getAudioSessionId());
                    if (listener != null) listener.onPrepared();
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    if (listener != null) listener.onError("MediaPlayer error " + what + "/" + extra);
                    return true;
                }
            });
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "prepare failed", e);
            if (listener != null) listener.onError(String.valueOf(e.getMessage()));
        }
    }

    private void attachVisualizer(int sessionId) {
        try {
            visualizer = new Visualizer(sessionId);
            int[] range = Visualizer.getCaptureSizeRange();
            visualizer.setCaptureSize(range[1]);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
                    if (waveform == null) return;
                    synchronized (lock) {
                        int n = Math.min(waveform.length, wave.length);
                        System.arraycopy(waveform, 0, wave, 0, n);
                    }
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                    if (fft != null) processFft(fft);
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, false);
            visualizer.setEnabled(true);
            reactive = true;
            Log.i(TAG, "Visualizer attached, session=" + sessionId);
        } catch (Throwable t) {
            // Missing RECORD_AUDIO or no audio session support -> run unreactive.
            Log.w(TAG, "Visualizer unavailable, falling back to simulated beats", t);
            reactive = false;
            if (visualizer != null) {
                try {
                    visualizer.release();
                } catch (Throwable ignored) {
                }
                visualizer = null;
            }
        }
    }

    private void processFft(byte[] fft) {
        int n = fft.length;
        int bins = n / 2;
        if (bins < 8) return;
        synchronized (lock) {
            float bandMax = 0f;
            for (int b = 0; b < BANDS; b++) {
                int i0 = (int) Math.pow(bins, (double) b / BANDS);
                int i1 = (int) Math.pow(bins, (double) (b + 1) / BANDS);
                if (i1 <= i0) i1 = i0 + 1;
                if (i0 < 1) i0 = 1;
                if (i1 > bins) i1 = bins;
                float sum = 0f;
                int count = 0;
                for (int i = i0; i < i1; i++) {
                    float re = fft[2 * i];
                    float im = fft[2 * i + 1];
                    sum += (float) Math.sqrt(re * re + im * im);
                    count++;
                }
                float raw = count > 0 ? sum / count : 0f;
                // rolling auto gain
                if (raw > autoGain) autoGain = raw;
                autoGain = Math.max(autoGain * 0.9965f, 1e-3f);
                float v = raw / autoGain;
                if (v > 1f) v = 1f;
                smooth[b] += (v - smooth[b]) * 0.35f;
                if (smooth[b] > bandMax) bandMax = smooth[b];
            }
            float bSum = 0f;
            for (int i = 0; i < 4; i++) bSum += smooth[i];
            bass = bSum / 4f;
            float lSum = 0f;
            for (int i = 0; i < BANDS; i++) lSum += smooth[i];
            level = lSum / BANDS;
            avgBass += (bass - avgBass) * 0.06f;
            long now = SystemClock.uptimeMillis();
            if (bass > 0.28f && bass > avgBass * 1.35f + 0.05f && now - lastBeatMs > 200) {
                lastBeatMs = now;
                beatVersion++;
            }
        }
    }

    public boolean isReactive() {
        return reactive;
    }

    public void start() {
        if (player != null && !released) {
            try {
                player.start();
            } catch (IllegalStateException e) {
                Log.w(TAG, "start failed", e);
            }
        }
    }

    public void pause() {
        if (player != null && player.isPlaying()) {
            try {
                player.pause();
            } catch (IllegalStateException e) {
                Log.w(TAG, "pause failed", e);
            }
        }
    }

    public boolean isPlaying() {
        try {
            return player != null && !released && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public int getPositionMs() {
        try {
            return player != null ? player.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public void release() {
        released = true;
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
            } catch (Throwable ignored) {
            }
            try {
                visualizer.release();
            } catch (Throwable ignored) {
            }
            visualizer = null;
        }
        if (player != null) {
            try {
                player.reset();
            } catch (Throwable ignored) {
            }
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        reactive = false;
    }

    // ----------------------------------------------------------- AudioFrames

    @Override
    public void copyBands(float[] out) {
        synchronized (lock) {
            int n = Math.min(out.length, smooth.length);
            System.arraycopy(smooth, 0, out, 0, n);
        }
    }

    @Override
    public void copyWave(byte[] out) {
        synchronized (lock) {
            int n = Math.min(out.length, wave.length);
            System.arraycopy(wave, 0, out, 0, n);
        }
    }

    @Override
    public float getBass() {
        synchronized (lock) {
            return bass;
        }
    }

    @Override
    public float getLevel() {
        synchronized (lock) {
            return level;
        }
    }

    @Override
    public boolean consumeBeat() {
        synchronized (lock) {
            if (beatVersion != consumedBeat) {
                consumedBeat = beatVersion;
                return true;
            }
            return false;
        }
    }
}
