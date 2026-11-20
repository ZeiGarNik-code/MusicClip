package com.musicclip.visualizer.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.Random;

/**
 * Pure-Canvas visualizer renderer.
 * Used live by {@link VisualizerView} and offline by the video exporter
 * (same code path, driven by PCM-derived {@link AudioFrames}).
 */
public class RenderEngine {
    public static final int EFFECT_COUNT = 6;

    private final float[] bands = new float[AudioFrames.BANDS];
    private final byte[] wave = new byte[512];
    private final float[] hsv = new float[3];
    private final Matrix covMatrix = new Matrix();
    private final RectF rf = new RectF();
    private final Random rnd = new Random();
    private final Path fogPath = new Path();
    private final ArrayList<Wisp> wisps = new ArrayList<Wisp>();
    private float lastFdt;
    private float wispTimer;
    private RadialGradient vignette;
    private int vigW = -1, vigH = -1;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path wavePath = new Path();
    private final Path waveMirror = new Path();

    private Bitmap background;
    private Bitmap covBg;
    private int covW, covH;
    private LinearGradient bottomGrad;
    private int bottomGradH = -1;
    private RadialGradient pulseGrad;
    private int pulseGradKey = -1;

    private int effect;
    private long lastNs;
    private float timeSec;
    private float beatEnv;
    private boolean firstFrame = true;

    private final ArrayList<Particle> particles = new ArrayList<Particle>();
    private final ArrayList<Shock> shocks = new ArrayList<Shock>();

    // ------------------------------------------------------------ state setters

    public synchronized void setBackground(Bitmap b) {
        background = b;
        covBg = null;
    }

    public synchronized Bitmap getBackground() {
        return background;
    }

    public int getEffect() {
        return effect;
    }

    public void setEffect(int idx) {
        effect = ((idx % EFFECT_COUNT) + EFFECT_COUNT) % EFFECT_COUNT;
    }

    public int cycleEffect() {
        effect = (effect + 1) % EFFECT_COUNT;
        return effect;
    }

    // ------------------------------------------------------------------ render

    public synchronized void render(Canvas c, int w, int h, AudioFrames audio, long nowNs) {
        if (c == null || w <= 0 || h <= 0) return;

        long dt;
        if (firstFrame) {
            lastNs = nowNs;
            firstFrame = false;
            dt = 16_000_000L;
        } else {
            dt = nowNs - lastNs;
            lastNs = nowNs;
        }
        if (dt < 0) dt = 0;
        if (dt > 100_000_000L) dt = 100_000_000L;
        float fdt = dt / 1e9f;
        timeSec += fdt;
        lastFdt = fdt;

        audio.copyBands(bands);
        audio.copyWave(wave);
        float bass = audio.getBass();
        float level = audio.getLevel();
        if (audio.consumeBeat()) {
            beatEnv = 1f;
            onBeat(w, h);
        } else {
            beatEnv -= fdt * 3.0f;
            if (beatEnv < 0f) beatEnv = 0f;
        }

        c.drawColor(Color.rgb(8, 8, 14));
        drawBackground(c, w, h, bass, level);
        updateParticles(fdt);
        updateShocks(fdt);

        switch (effect) {
            case 0:
                drawBars(c, w, h, bass, level);
                break;
            case 1:
                drawCircle(c, w, h, bass, level);
                break;
            case 2:
                drawWaveFx(c, w, h, bass, level);
                break;
            case 3:
                drawParticles(c, w, h, bass, level);
                break;
            case 4:
                drawPulse(c, w, h, bass, level);
                break;
            case 5:
                drawPhantom(c, w, h, bass, level);
                break;
            default:
                break;
        }
        drawShocks(c, w, h);
    }

    private int hue(float deg, float s, float v) {
        hsv[0] = ((deg % 360f) + 360f) % 360f;
        hsv[1] = s;
        hsv[2] = v;
        return Color.HSVToColor(hsv);
    }

    private int hueA(float deg, float s, float v, int alpha) {
        int c = hue(deg, s, v);
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c));
    }

    // -------------------------------------------------------------- background

    private void drawBackground(Canvas c, int w, int h, float bass, float level) {
        Bitmap bg = background;
        if (bg != null && !bg.isRecycled()) {
            ensureCover(bg, w, h);
            float beatScale = 1f + 0.05f * beatEnv + 0.010f * (float) Math.sin(timeSec * 0.6);
            c.save();
            c.scale(beatScale, beatScale, w / 2f, h / 2f);
            c.drawBitmap(bg, covMatrix, bitmapPaint);
            c.restore();

            fill.setColor(Color.argb(150, 6, 6, 14));
            c.drawRect(0, 0, w, h, fill);

            if (bottomGrad == null || bottomGradH != h) {
                bottomGrad = new LinearGradient(0, h * 0.55f, 0, h,
                        0x00000000, 0x99000000, Shader.TileMode.CLAMP);
                bottomGradH = h;
            }
            fill.setShader(bottomGrad);
            c.drawRect(0, h * 0.55f, w, h, fill);
            fill.setShader(null);
        } else {
            // No image yet: animated gradient backdrop so the screen is never black.
            fill.setColor(Color.rgb(14, 14, 26));
            c.drawRect(0, 0, w, h, fill);
            float cx = w * (0.5f + 0.3f * (float) Math.sin(timeSec * 0.5));
            float cy = h * (0.45f + 0.2f * (float) Math.cos(timeSec * 0.37));
            float r = Math.min(w, h) * (0.55f + 0.1f * beatEnv);
            int key = (int) (timeSec * 20f + bass * 40f) & 0xffff;
            if (pulseGrad == null || pulseGradKey != key) {
                int col = hue(timeSec * 26f, 0.7f, 1f);
                pulseGrad = new RadialGradient(cx, cy, r,
                        Color.argb(90, Color.red(col), Color.green(col), Color.blue(col)),
                        0x00000000, Shader.TileMode.CLAMP);
                pulseGradKey = key;
            }
            fill.setShader(pulseGrad);
            c.drawCircle(cx, cy, r, fill);
            fill.setShader(null);
        }
    }

    private void ensureCover(Bitmap bg, int w, int h) {
        if (covBg == bg && covW == w && covH == h) return;
        covBg = bg;
        covW = w;
        covH = h;
        float bw = bg.getWidth();
        float bh = bg.getHeight();
        if (bw <= 0 || bh <= 0) return;
        float scale = Math.max(w / bw, h / bh);
        float dx = (w - bw * scale) / 2f;
        float dy = (h - bh * scale) / 2f;
        covMatrix.reset();
        covMatrix.setScale(scale, scale);
        covMatrix.postTranslate(dx, dy);
    }

    // ---------------------------------------------------------------- effects

    private void drawBars(Canvas c, int w, int h, float bass, float level) {
        int n = AudioFrames.BANDS;
        float padX = w * 0.055f;
        float gap = w * 0.006f;
        float bw = (w - 2 * padX - (n - 1) * gap) / n;
        float baseY = h * 0.80f;
        float maxH = h * 0.42f;
        float pulse = 0.85f + 0.15f * beatEnv;
        float radius = Math.max(2f, bw * 0.5f);

        for (int i = 0; i < n; i++) {
            float bh = bands[i] * maxH * pulse;
            if (bh < 4f) bh = 4f;
            float l = padX + i * (bw + gap);
            int col = hue(timeSec * 30f + i * 7f + level * 40f, 0.85f, 1f);
            fill.setColor(col);
            rf.set(l, baseY - bh, l + bw, baseY);
            c.drawRoundRect(rf, radius, radius, fill);

            // reflection
            int alpha = (int) (40 + bands[i] * 90);
            fill.setColor(Color.argb(alpha, Color.red(col), Color.green(col), Color.blue(col)));
            rf.set(l, baseY + 3f, l + bw, baseY + 3f + bh * 0.38f);
            c.drawRoundRect(rf, radius, radius, fill);
        }
    }

    private void drawCircle(Canvas c, int w, int h, float bass, float level) {
        float cx = w / 2f;
        float cy = h * 0.47f;
        float minDim = Math.min(w, h);
        float r = minDim * 0.19f + bass * minDim * 0.05f + beatEnv * minDim * 0.02f;
        int spokes = AudioFrames.BANDS;
        stroke.setStrokeCap(Paint.Cap.ROUND);

        for (int i = 0; i < spokes; i++) {
            float deg = (i / (float) spokes) * 360f - 90f + timeSec * 10f;
            float rad = (float) Math.toRadians(deg);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            float len = 6f + bands[i] * minDim * 0.17f + beatEnv * 6f;
            int col = hue(timeSec * 26f + i * 11f + level * 50f, 0.85f, 1f);
            stroke.setColor(col);
            stroke.setStrokeWidth(Math.max(3f, minDim * 0.013f));
            c.drawLine(cx + cos * r, cy + sin * r, cx + cos * (r + len), cy + sin * (r + len), stroke);
            // mirrored spoke
            c.drawLine(cx - cos * r, cy - sin * r, cx - cos * (r + len), cy - sin * (r + len), stroke);
        }

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Color.argb(140, 255, 255, 255));
        stroke.setStrokeWidth(2f);
        c.drawCircle(cx, cy, r, stroke);
        stroke.setStyle(Paint.Style.FILL);
        // inner pulse disc
        fill.setColor(Color.argb((int) (30 + 70 * beatEnv), 255, 255, 255));
        c.drawCircle(cx, cy, r * (0.25f + 0.2f * beatEnv), fill);
    }

    private void drawWaveFx(Canvas c, int w, int h, float bass, float level) {
        float midY = h * 0.47f;
        float amp = h * 0.24f * (0.55f + 0.55f * level + 0.25f * beatEnv);
        int pts = wave.length;
        float dx = w / (pts - 1f);

        wavePath.reset();
        waveMirror.reset();
        for (int i = 0; i < pts; i++) {
            float y = midY + ((wave[i] & 0xFF) - 128) / 128f * amp;
            if (i == 0) {
                wavePath.moveTo(0, y);
                waveMirror.moveTo(0, 2 * midY - y);
            } else {
                wavePath.lineTo(i * dx, y);
                waveMirror.lineTo(i * dx, 2 * midY - y);
            }
        }

        int accent = hue(timeSec * 30f + bass * 80f, 0.85f, 1f);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);

        stroke.setColor(Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent)));
        stroke.setStrokeWidth(Math.max(10f, h * 0.018f));
        c.drawPath(wavePath, stroke);
        c.drawPath(waveMirror, stroke);

        stroke.setColor(Color.argb(130, Color.red(accent), Color.green(accent), Color.blue(accent)));
        stroke.setStrokeWidth(Math.max(5f, h * 0.008f));
        c.drawPath(wavePath, stroke);
        c.drawPath(waveMirror, stroke);

        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(Math.max(2f, h * 0.003f));
        c.drawPath(wavePath, stroke);

        stroke.setStyle(Paint.Style.FILL);
    }

    private void drawParticles(Canvas c, int w, int h, float bass, float level) {
        // star field
        for (int k = 0; k < 60; k++) {
            float sx = ((k * 97) % 101) / 101f * w;
            float sy = ((k * 57) % 89) / 89f * h;
            int a = (int) (50 + 70 * (0.5 + 0.5 * Math.sin(timeSec * 2.2 + k)));
            fill.setColor(Color.argb(a, 255, 255, 255));
            float sr = 1.2f + (k % 3) * 0.7f + beatEnv * 1.2f;
            c.drawCircle(sx, sy, sr, fill);
        }

        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            float frac = 1f - p.life / p.max;
            if (frac <= 0f) continue;
            int alpha = (int) (255 * frac * frac);
            fill.setColor(hueA(p.hue + timeSec * 40f, 0.8f, 1f, alpha));
            c.drawCircle(p.x, p.y, p.size * (0.35f + 0.65f * frac) + beatEnv * 3f, fill);
        }

        // soft bass glow behind particles
        fill.setColor(Color.argb((int) (26 + 46 * bass), 124, 77, 255));
        c.drawCircle(w / 2f, h * 0.5f, Math.min(w, h) * (0.30f + 0.1f * beatEnv), fill);
    }

    private void drawPulse(Canvas c, int w, int h, float bass, float level) {
        float cx = w / 2f;
        float cy = h * 0.47f;
        float minDim = Math.min(w, h);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStyle(Paint.Style.STROKE);

        int rings = 16;
        for (int i = 0; i < rings; i++) {
            float band = bands[(i * AudioFrames.BANDS) / rings];
            float r = minDim * (0.10f + i * 0.030f) + band * minDim * 0.06f + beatEnv * minDim * 0.02f;
            int alpha = (int) (40 + 150 * band + 60 * beatEnv);
            if (alpha > 235) alpha = 235;
            stroke.setColor(hueA(timeSec * 24f + i * 18f, 0.85f, 1f, alpha));
            stroke.setStrokeWidth(Math.max(2.5f, minDim * 0.007f * (0.6f + band)));
            c.drawCircle(cx, cy, r, stroke);
        }

        fill.setColor(Color.argb((int) (50 + 120 * beatEnv), 255, 255, 255));
        c.drawCircle(cx, cy, minDim * (0.045f + 0.05f * beatEnv), fill);

        // horizontal beat lines
        stroke.setColor(hueA(timeSec * 40f, 0.6f, 1f, (int) (70 + 100 * level)));
        stroke.setStrokeWidth(2f);
        float y = h * (0.5f + 0.32f * (float) Math.sin(timeSec * 1.3));
        c.drawLine(w * 0.06f, y, w * 0.94f, y, stroke);
        stroke.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------ beats

    private void onBeat(int w, int h) {
        if (effect == 3) {
            float cx = w / 2f;
            float cy = h * 0.5f;
            int count = 14 + rnd.nextInt(8);
            for (int i = 0; i < count; i++) {
                Particle p = new Particle();
                double ang = rnd.nextDouble() * Math.PI * 2;
                float speed = 200f + rnd.nextFloat() * 550f;
                p.x = cx;
                p.y = cy;
                p.vx = (float) (Math.cos(ang) * speed);
                p.vy = (float) (Math.sin(ang) * speed);
                p.max = 0.7f + rnd.nextFloat() * 1.0f;
                p.life = 0f;
                p.size = 4f + rnd.nextFloat() * 8f;
                p.hue = rnd.nextFloat() * 360f;
                particles.add(p);
            }
            while (particles.size() > 260) particles.remove(0);
        }
        if (effect == 4) {
            Shock s = new Shock();
            s.r = Math.min(w, h) * 0.08f;
            s.speed = Math.max(w, h) * 1.15f;
            s.alpha = 220;
            shocks.add(s);
            while (shocks.size() > 6) shocks.remove(0);
        }
        if (effect == 5) {
            spawnWisp(w, h);
            spawnWisp(w, h);
            spawnWisp(w, h);
        }
    }

    private void updateParticles(float fdt) {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life += fdt;
            if (p.life >= p.max) {
                particles.remove(i);
                continue;
            }
            p.x += p.vx * fdt;
            p.y += p.vy * fdt;
            p.vy += 90f * fdt;
            p.vx *= 0.994f;
            p.vy *= 0.994f;
        }
    }

    private void updateShocks(float fdt) {
        for (int i = shocks.size() - 1; i >= 0; i--) {
            Shock s = shocks.get(i);
            s.r += s.speed * fdt;
            s.alpha -= (int) (260 * fdt);
            if (s.alpha <= 0) shocks.remove(i);
        }
    }

    private void drawShocks(Canvas c, int w, int h) {
        if (shocks.isEmpty()) return;
        float cx = w / 2f;
        float cy = h * 0.47f;
        stroke.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < shocks.size(); i++) {
            Shock s = shocks.get(i);
            int a = s.alpha;
            if (a <= 0) continue;
            stroke.setColor(hueA(timeSec * 30f + i * 40f, 0.7f, 1f, a));
            stroke.setStrokeWidth(Math.max(2f, 8f * (s.alpha / 220f)));
            c.drawCircle(cx, cy, s.r, stroke);
        }
        stroke.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------- effect: Ma mị

    /**
     * "Ma mị" (eerie/haunting): dark veil, ghost moon with halo, drifting fog
     * bands, rising spirit wisps, fireflies and a heavy vignette — all beat-aware.
     */
    private void drawPhantom(Canvas c, int w, int h, float bass, float level) {
        float minDim = Math.min(w, h);

        // eerie purple veil over the background
        fill.setColor(Color.argb(72, 26, 4, 44));
        c.drawRect(0, 0, w, h, fill);

        // --- ghost moon (top-right) with pulsing halo
        float mx = w * 0.78f;
        float my = h * 0.17f;
        float mr = minDim * 0.075f * (1f + 0.07f * beatEnv);
        for (int i = 3; i >= 1; i--) {
            fill.setColor(Color.argb((int) (14 * (4 - i) * (0.6f + 0.5f * beatEnv)),
                    216, 226, 208));
            c.drawCircle(mx, my, mr * (1f + i * 0.6f), fill);
        }
        fill.setColor(Color.argb(228, 236, 240, 222));
        c.drawCircle(mx, my, mr, fill);
        fill.setColor(Color.argb(34, 110, 120, 110));
        c.drawCircle(mx - mr * 0.30f, my - mr * 0.15f, mr * 0.24f, fill);
        c.drawCircle(mx + mr * 0.26f, my + mr * 0.30f, mr * 0.17f, fill);
        c.drawCircle(mx + mr * 0.05f, my - mr * 0.40f, mr * 0.12f, fill);

        // --- drifting fog bands
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        int fogBoost = (int) (34 * beatEnv + 18 * level);
        for (int layer = 0; layer < 4; layer++) {
            float baseY = h * (0.30f + 0.16f * layer);
            float amp = h * (0.030f + 0.013f * layer);
            float speed = (0.5f + 0.22f * layer) * (layer % 2 == 0 ? 1f : -1f);
            int alpha = 24 + layer * 6 + fogBoost;
            if (alpha > 135) alpha = 135;
            stroke.setColor(Color.argb(alpha, 208, 214, 232));
            stroke.setStrokeWidth(h * (0.050f + 0.022f * layer));
            fogPath.reset();
            int steps = 22;
            for (int s = 0; s <= steps; s++) {
                float x = w * s / (float) steps;
                float y = baseY + (float) Math.sin(
                        (x / w) * 6.0f + timeSec * speed + layer * 1.7f) * amp;
                if (s == 0) fogPath.moveTo(x, y);
                else fogPath.lineTo(x, y);
            }
            c.drawPath(fogPath, stroke);
        }

        // --- spirit wisps rising
        updateWisps(lastFdt, w, h);
        for (int i = 0; i < wisps.size(); i++) {
            Wisp s = wisps.get(i);
            float t = s.life / s.max;
            if (t >= 1f) continue;
            int alpha = (int) (190 * Math.sin(Math.PI * Math.min(1f, t)));
            if (alpha <= 0) continue;
            fill.setColor(hueA(s.hue, 0.40f, 1f, alpha / 3));
            c.drawCircle(s.x, s.y, s.size * 3.4f, fill);
            fill.setColor(hueA(s.hue, 0.45f, 1f, alpha / 2));
            c.drawCircle(s.x, s.y - s.size * 1.5f, s.size * 0.75f, fill);
            fill.setColor(hueA(s.hue, 0.35f, 1f, alpha));
            c.drawCircle(s.x, s.y, s.size, fill);
        }

        // --- fireflies
        for (int k = 0; k < 26; k++) {
            float sx = ((k * 73) % 101) / 101f * w;
            float sy = ((k * 41) % 97) / 97f * h;
            double tw = 0.5 + 0.5 * Math.sin(timeSec * (1.3 + (k % 5) * 0.37) + k);
            int a = (int) (120 * tw * tw * (0.6f + 0.4f * beatEnv));
            fill.setColor(Color.argb(a, 172, 255, 205));
            c.drawCircle(sx, sy, 1.6f + (k % 3) * 0.8f + beatEnv * 1.4f, fill);
        }

        // --- vignette (dark corners)
        if (vigW != w || vigH != h || vignette == null) {
            vignette = new RadialGradient(w / 2f, h * 0.45f, Math.max(w, h) * 0.75f,
                    Color.TRANSPARENT, Color.argb(170, 0, 0, 0), Shader.TileMode.CLAMP);
            vigW = w;
            vigH = h;
        }
        fill.setShader(vignette);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        stroke.setStyle(Paint.Style.FILL);
    }

    private void updateWisps(float fdt, int w, int h) {
        for (int i = wisps.size() - 1; i >= 0; i--) {
            Wisp s = wisps.get(i);
            s.life += fdt;
            if (s.life >= s.max) {
                wisps.remove(i);
                continue;
            }
            s.y -= s.vy * fdt;
            s.x += (float) Math.sin(timeSec * 1.2 + s.phase) * 30f * fdt;
        }
        wispTimer -= fdt;
        if (wispTimer <= 0) {
            wispTimer = 0.5f + rnd.nextFloat() * 0.9f;
            spawnWisp(w, h);
        }
    }

    private void spawnWisp(int w, int h) {
        if (wisps.size() > 26) return;
        Wisp s = new Wisp();
        s.x = rnd.nextFloat() * w;
        s.y = h * (0.72f + rnd.nextFloat() * 0.34f);
        s.vy = 42f + rnd.nextFloat() * 75f;
        s.max = 3.2f + rnd.nextFloat() * 3.2f;
        s.life = 0f;
        s.size = 5.5f + rnd.nextFloat() * 9f;
        s.hue = 145f + rnd.nextFloat() * 75f; // pale green → cyan spirits
        s.phase = rnd.nextFloat() * 6.28f;
        wisps.add(s);
    }

    // ----------------------------------------------------------------- models

    private static final class Particle {
        float x, y, vx, vy, life, max, size, hue;
    }

    private static final class Shock {
        float r, speed;
        int alpha;
    }

    private static final class Wisp {
        float x, y, vy, phase, life, max, size, hue;
    }
}
