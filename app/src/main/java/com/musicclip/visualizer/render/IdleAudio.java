package com.musicclip.visualizer.render;

import android.os.SystemClock;

/**
 * Fallback audio frames used when the RECORD_AUDIO permission is denied:
 * keeps the visuals alive with smooth simulated bands and beats.
 */
public class IdleAudio implements AudioFrames {
    private final float[] bands = new float[BANDS];
    private final byte[] wave = new byte[512];
    private final long t0 = System.nanoTime();
    private long lastBeatMs;
    private boolean pendingBeat;

    @Override
    public void copyBands(float[] out) {
        float t = (System.nanoTime() - t0) / 1e9f;
        for (int i = 0; i < out.length && i < BANDS; i++) {
            float v = 0.30f
                    + 0.16f * (float) Math.sin(t * 1.6 + i * 0.55)
                    + 0.10f * (float) Math.sin(t * 0.7 + i * 0.21)
                    + 0.06f * (float) Math.sin(t * 3.1 + i * 1.3);
            out[i] = v < 0.02f ? 0.02f : (v > 1f ? 1f : v);
        }
    }

    @Override
    public void copyWave(byte[] out) {
        float t = (System.nanoTime() - t0) / 1e9f;
        for (int i = 0; i < out.length; i++) {
            double v = Math.sin(i * 0.09 + t * 6.0) * Math.sin(i * 0.013 + t * 1.7);
            out[i] = (byte) (128 + (int) (v * 90));
        }
    }

    @Override
    public float getBass() {
        float t = (System.nanoTime() - t0) / 1e9f;
        float v = 0.35f + 0.2f * (float) Math.sin(t * 1.6);
        return v < 0 ? 0 : v;
    }

    @Override
    public float getLevel() {
        float t = (System.nanoTime() - t0) / 1e9f;
        return 0.4f + 0.15f * (float) Math.sin(t * 0.9 + 1.0);
    }

    @Override
    public boolean consumeBeat() {
        long now = SystemClock.uptimeMillis();
        if (now - lastBeatMs > 680) {
            lastBeatMs = now;
            pendingBeat = true;
        }
        if (pendingBeat) {
            pendingBeat = false;
            return true;
        }
        return false;
    }
}
