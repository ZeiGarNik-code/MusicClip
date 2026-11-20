package com.musicclip.visualizer.render;

/**
 * Snapshot of audio analysis consumed by {@link RenderEngine}.
 * Implemented by {@link com.musicclip.visualizer.audio.AudioEngine} (live),
 * by the exporter (computed from PCM) and by {@link IdleAudio} (no permission).
 */
public interface AudioFrames {
    int BANDS = 32;

    /** Copies the 32 smoothed band levels (0..1) into {@code out}. */
    void copyBands(float[] out);

    /** Copies recent waveform bytes (unsigned, 128 = silence) into {@code out}. */
    void copyWave(byte[] out);

    /** Low-frequency energy 0..1. */
    float getBass();

    /** Overall loudness 0..1. */
    float getLevel();

    /** Returns true exactly once per detected beat. */
    boolean consumeBeat();
}
