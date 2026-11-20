package com.musicclip.visualizer.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** SurfaceView that drives {@link RenderEngine} on a dedicated thread. */
public class VisualizerView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    private Thread thread;
    private volatile boolean running;
    private volatile boolean surfaceReady;

    private final RenderEngine engine = new RenderEngine();
    private volatile AudioFrames audio = new IdleAudio();
    private final Object drawLock = new Object();

    public VisualizerView(Context context) {
        this(context, null);
    }

    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
        setKeepScreenOn(true);
    }

    public void setAudio(AudioFrames a) {
        audio = a != null ? a : new IdleAudio();
    }

    public AudioFrames getAudio() {
        return audio;
    }

    public void setBackgroundBitmap(Bitmap bmp) {
        engine.setBackground(bmp);
    }

    public int getEffectIndex() {
        return engine.getEffect();
    }

    public int cycleEffect() {
        return engine.cycleEffect();
    }

    public void setEffect(int idx) {
        engine.setEffect(idx);
    }

    /** Renders the current frame into an arbitrary bitmap (screenshot / preview). */
    public void renderSnapshot(Bitmap target) {
        Canvas c = new Canvas(target);
        synchronized (drawLock) {
            engine.render(c, target.getWidth(), target.getHeight(), audio, System.nanoTime());
        }
    }

    public void startRender() {
        if (running) return;
        running = true;
        if (thread == null || !thread.isAlive()) {
            thread = new Thread(this, "mc-render");
            thread.start();
        }
    }

    public void stopRender() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    @Override
    public void run() {
        long lastFrameNs = 0;
        while (running) {
            if (!surfaceReady) {
                sleep(40);
                continue;
            }
            Canvas c = null;
            try {
                c = getHolder().lockCanvas();
                if (c != null) {
                    synchronized (drawLock) {
                        engine.render(c, c.getWidth(), c.getHeight(), audio, System.nanoTime());
                    }
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                surfaceReady = false;
            } finally {
                if (c != null) {
                    try {
                        getHolder().unlockCanvasAndPost(c);
                    } catch (Exception ignored) {
                    }
                }
            }
            // pace ~60 fps
            long frameNs = System.nanoTime();
            long elapsed = frameNs - lastFrameNs;
            lastFrameNs = frameNs;
            long targetNs = 16_000_000L;
            long sleepNs = targetNs - elapsed;
            if (sleepNs > 1_000_000L) {
                sleep(sleepNs / 1_000_000L);
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --------------------------------------------------------- SurfaceHolder

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceReady = true;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }
}
