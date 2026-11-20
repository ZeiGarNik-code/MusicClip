package com.musicclip.visualizer.export;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import com.musicclip.visualizer.Track;
import com.musicclip.visualizer.render.AudioFrames;
import com.musicclip.visualizer.render.RenderEngine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Offline MP4 exporter — full song duration (safety cap 10 minutes).
 *
 * audio:  asset WAV (memory) or MediaCodec decode -> temp PCM file (streamed)
 *          -> AAC encoder (fed chunk by chunk, never buffers the whole song)
 * video:  RenderEngine(Canvas) -> GL quad -> H.264 encoder (input surface)
 * mux:    MediaMuxer started as soon as BOTH encoder formats are known
 *          (small hold-over buffers only), then everything streams straight in.
 */
public class VideoExporter {
    private static final String TAG = "VideoExporter";
    private static final int FPS = 30;
    private static final long FRAME_US = 1_000_000L / FPS;
    /** Hard safety cap: export at most the first 10 minutes of a track. */
    private static final long MAX_AUDIO_US = 10L * 60 * 1_000_000L;
    private static final int WIN = 1024;

    public interface Listener {
        void onProgress(float fraction, String stage);

        void onDone(String location);

        void onError(String message);
    }

    private final Context appCtx;
    private final Track track;
    private final Bitmap background;
    private final int width;
    private final int height;
    private final int effect;
    private final Listener listener;
    private volatile boolean cancelled;

    public VideoExporter(Context context, Track track, Bitmap background, int effect,
                         int width, int height, Listener listener) {
        this.appCtx = context.getApplicationContext();
        this.track = track;
        this.background = background;
        this.effect = effect;
        this.width = width;
        this.height = height;
        this.listener = listener;
    }

    public void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runInternal();
            }
        }, "mc-export");
        t.start();
    }

    public void cancel() {
        cancelled = true;
    }

    private static class CancelledException extends Exception {}

    private void checkCancel() throws CancelledException {
        if (cancelled) throw new CancelledException();
    }

    private void publish(float fraction, String stage) {
        if (!cancelled) listener.onProgress(fraction, stage);
    }

    // ------------------------------------------------------------------ main

    private void runInternal() {
        File out = new File(appCtx.getCacheDir(), "mc_export_" + System.currentTimeMillis() + ".mp4");
        PcmSource pcm = null;
        try {
            publish(0.01f, "Đọc dữ liệu âm thanh…");
            pcm = loadPcm();
            checkCancel();
            long totalUs = pcm.totalFrames() * 1_000_000L / pcm.sampleRate();
            if (totalUs <= 0) throw new Exception("bài hát rỗng");
            exportTo(out, pcm, totalUs);
            checkCancel();
            publish(0.97f, "Lưu vào thư viện…");
            String location = publishToGallery(out);
            publish(1f, "Xong");
            listener.onDone(location);
        } catch (CancelledException ce) {
            out.delete();
        } catch (Throwable t) {
            Log.e(TAG, "export failed", t);
            out.delete();
            if (!cancelled) {
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                listener.onError(msg);
            }
        } finally {
            if (pcm != null) {
                try {
                    pcm.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------ PCM source

    /** Random-access PCM16 little-endian audio. */
    interface PcmSource {
        int sampleRate();

        int channels();

        long totalFrames();

        /** Reads up to maxFrames sample-groups starting at startFrame; returns count (0 = EOF). */
        int read(short[] dst, long startFrame, int maxFrames);

        void close();
    }

    static final class MemoryPcm implements PcmSource {
        private final short[] data;
        private final int sr;
        private final int ch;

        MemoryPcm(short[] data, int sr, int ch) {
            this.data = data;
            this.sr = sr;
            this.ch = ch;
        }

        @Override
        public int sampleRate() {
            return sr;
        }

        @Override
        public int channels() {
            return ch;
        }

        @Override
        public long totalFrames() {
            return data.length / ch;
        }

        @Override
        public int read(short[] dst, long startFrame, int maxFrames) {
            long startSample = startFrame * ch;
            if (startSample >= data.length) return 0;
            int count = (int) Math.min(maxFrames * (long) ch, data.length - startSample);
            count -= count % ch;
            System.arraycopy(data, (int) startSample, dst, 0, count);
            return count / ch;
        }

        @Override
        public void close() {}
    }

    static final class FilePcm implements PcmSource {
        private final File file;
        private final long byteLen;
        private final int sr;
        private final int ch;
        private final boolean deleteOnClose;
        private RandomAccessFile raf;

        FilePcm(File file, long byteLen, int sr, int ch, boolean deleteOnClose) throws Exception {
            this.file = file;
            this.byteLen = byteLen;
            this.sr = sr;
            this.ch = ch;
            this.deleteOnClose = deleteOnClose;
            this.raf = new RandomAccessFile(file, "r");
        }

        @Override
        public int sampleRate() {
            return sr;
        }

        @Override
        public int channels() {
            return ch;
        }

        @Override
        public long totalFrames() {
            return byteLen / (2L * ch);
        }

        @Override
        public synchronized int read(short[] dst, long startFrame, int maxFrames) {
            try {
                long byteOff = startFrame * ch * 2L;
                if (byteOff >= byteLen) return 0;
                int want = (int) Math.min(maxFrames, (byteLen - byteOff) / (2L * ch));
                if (want <= 0) return 0;
                byte[] tmp = new byte[want * ch * 2];
                raf.seek(byteOff);
                raf.readFully(tmp);
                for (int i = 0; i < want * ch; i++) {
                    dst[i] = (short) ((tmp[2 * i] & 0xff) | (tmp[2 * i + 1] << 8));
                }
                return want;
            } catch (Exception e) {
                Log.w(TAG, "pcm read failed", e);
                return 0;
            }
        }

        @Override
        public synchronized void close() {
            try {
                if (raf != null) raf.close();
            } catch (Throwable ignored) {
            }
            raf = null;
            if (deleteOnClose) file.delete();
        }
    }

    // ---------------------------------------------------------------- loading

    private PcmSource loadPcm() throws Exception {
        if (track.builtin) {
            byte[] raw = readAsset(track.assetPath);
            return parseWav(raw);
        }
        return decodeDeviceToTempFile();
    }

    private byte[] readAsset(String path) throws Exception {
        InputStream in = appCtx.getAssets().open(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static PcmSource parseWav(byte[] raw) throws Exception {
        if (raw.length < 44 || raw[0] != 'R' || raw[1] != 'I' || raw[2] != 'F' || raw[3] != 'F') {
            throw new Exception("không phải file WAV");
        }
        int pos = 12;
        int audioFormat = 0, channels = 0, sampleRate = 0, bits = 0;
        byte[] pcmBytes = null;
        while (pos + 8 <= raw.length) {
            String id = new String(raw, pos, 4, "US-ASCII");
            int size = le32(raw, pos + 4);
            int body = pos + 8;
            if (body + size > raw.length) size = raw.length - body;
            if ("fmt ".equals(id)) {
                audioFormat = le16(raw, body);
                channels = le16(raw, body + 2);
                sampleRate = le32(raw, body + 4);
                bits = le16(raw, body + 14);
            } else if ("data".equals(id)) {
                pcmBytes = new byte[size];
                System.arraycopy(raw, body, pcmBytes, 0, size);
            }
            pos = body + size + (size & 1);
        }
        if (pcmBytes == null) throw new Exception("WAV thiếu data");
        if (audioFormat != 1 || bits != 16) {
            throw new Exception("chỉ hỗ trợ WAV PCM 16-bit");
        }
        if (channels < 1 || channels > 2 || sampleRate < 8000) {
            throw new Exception("WAV không hợp lệ");
        }
        short[] shorts = new short[pcmBytes.length / 2];
        for (int i = 0; i < shorts.length; i++) {
            shorts[i] = (short) le16(pcmBytes, i * 2);
        }
        return new MemoryPcm(shorts, sampleRate, channels);
    }

    /** Streams the decoded device track into a temp PCM file (RAM-friendly, full length). */
    private PcmSource decodeDeviceToTempFile() throws Exception {
        MediaExtractor ex = new MediaExtractor();
        FileOutputStream fos = null;
        File tmp = new File(appCtx.getCacheDir(), "mc_pcm_" + System.currentTimeMillis() + ".pcm");
        try {
            ex.setDataSource(appCtx, track.uri, null);
            int sel = -1;
            String mime = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    sel = i;
                    mime = m;
                    break;
                }
            }
            if (sel < 0) throw new Exception("file không có track âm thanh");
            ex.selectTrack(sel);

            MediaCodec dec = MediaCodec.createDecoderByType(mime);
            dec.configure(ex.getTrackFormat(sel), null, null, 0);
            dec.start();

            int sampleRate = 44100;
            int channels = 2;
            boolean formatKnown = false;
            long maxBytes = -1;
            long written = 0;
            fos = new FileOutputStream(tmp);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean done = false;
            int spin = 0;

            while (!done) {
                checkCancel();
                if (spin++ > 400000) throw new Exception("decoder timeout");

                int inIdx = dec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer ib = dec.getInputBuffer(inIdx);
                    int sampleSize = ex.readSampleData(ib, 0);
                    long pts = ex.getSampleTime();
                    if (sampleSize < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        dec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                        ex.advance();
                    }
                }

                int outIdx = dec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = dec.getOutputFormat();
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    formatKnown = true;
                    long maxFrames = MAX_AUDIO_US * sampleRate / 1_000_000L;
                    maxBytes = maxFrames * channels * 2L;
                } else if (outIdx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = dec.getOutputBuffer(outIdx);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        if (!formatKnown) {
                            // format normally arrives before the first buffer
                            formatKnown = true;
                            long maxFrames = MAX_AUDIO_US * sampleRate / 1_000_000L;
                            maxBytes = maxFrames * channels * 2L;
                        }
                        int toWrite = info.size;
                        if (maxBytes >= 0 && written + toWrite > maxBytes) {
                            toWrite = (int) (maxBytes - written);
                            toWrite -= toWrite % (channels * 2);
                        }
                        if (toWrite > 0) {
                            byte[] chunk = new byte[toWrite];
                            ob.get(chunk);
                            fos.write(chunk);
                            written += toWrite;
                        }
                        dec.releaseOutputBuffer(outIdx, false);
                        if (maxBytes >= 0 && written >= maxBytes) done = true;
                    } else {
                        dec.releaseOutputBuffer(outIdx, false);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                }
            }
            try {
                dec.stop();
            } catch (Throwable ignored) {
            }
            dec.release();
            fos.flush();
            fos.close();
            fos = null;

            if (written < channels * 2L) throw new Exception("không giải mã được âm thanh");
            return new FilePcm(tmp, written, sampleRate, channels, true);
        } catch (Throwable t) {
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignored) {
            }
            tmp.delete();
            if (t instanceof Exception) throw (Exception) t;
            throw new Exception(String.valueOf(t));
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
    }

    // --------------------------------------------------------------- export

    private void exportTo(File outFile, final PcmSource pcm, long totalUs) throws Exception {
        final int frames = (int) Math.max(1, (totalUs + FRAME_US - 1) / FRAME_US);

        MediaFormat vFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        vFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vFmt.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000);
        vFmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        vFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        final MediaCodec videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        videoCodec.configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        final Surface codecSurface = videoCodec.createInputSurface();
        videoCodec.start();

        MediaFormat aFmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate(), pcm.channels());
        aFmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        aFmt.setInteger(MediaFormat.KEY_BIT_RATE, pcm.channels() >= 2 ? 160000 : 112000);
        aFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
        final MediaCodec audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioCodec.configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioCodec.start();

        EglRenderer egl = null;
        Bitmap frameBmp = null;
        final MediaMuxer[] muxHolder = new MediaMuxer[1];
        final boolean[] muxStartedHolder = new boolean[1];
        try {
            egl = new EglRenderer(codecSurface, width, height);
            frameBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas frameCanvas = new Canvas(frameBmp);
            final RenderEngine engine = new RenderEngine();
            engine.setEffect(effect);
            engine.setBackground(background);
            final PcmFrames pcmFrames = new PcmFrames(pcm);

            final MediaFormat[] videoFormat = new MediaFormat[1];
            final MediaFormat[] audioFormat = new MediaFormat[1];
            final DeferredSink vSink = new DeferredSink();
            final DeferredSink aSink = new DeferredSink();

            // phase 1: render frames until the video encoder announces its format
            int f = 0;
            while (videoFormat[0] == null && f < 8 && !cancelled) {
                renderFrame(engine, frameCanvas, pcmFrames, f);
                egl.draw(frameBmp, f * FRAME_US * 1000L);
                drain(videoCodec, vSink, videoFormat, false);
                f++;
            }
            if (videoFormat[0] == null) throw new Exception("video encoder không xuất format");

            // phase 2: encode the whole track; as soon as BOTH formats exist the
            // muxer starts and every further buffer streams straight through.
            publish(0.05f, "Mã hóa âm thanh…");
            encodeAllAudio(audioCodec, pcm, totalUs, aSink, audioFormat, new Runnable() {
                @Override
                public void run() {
                    try {
                        MediaMuxer m = new MediaMuxer(outFile.getPath(),
                                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                        int vt = m.addTrack(videoFormat[0]);
                        int at = m.addTrack(audioFormat[0]);
                        m.start();
                        muxHolder[0] = m;
                        muxStartedHolder[0] = true;
                        vSink.arm(m, vt);
                        aSink.arm(m, at);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            if (!muxStartedHolder[0]) throw new Exception("audio encoder không xuất format");

            // phase 3: remaining video frames, streamed directly to the muxer
            publish(0.30f, "Mã hóa hình ảnh…");
            for (; f < frames; f++) {
                checkCancel();
                renderFrame(engine, frameCanvas, pcmFrames, f);
                egl.draw(frameBmp, f * FRAME_US * 1000L);
                drain(videoCodec, vSink, videoFormat, false);
                if ((f & 7) == 0) {
                    publish(0.30f + 0.65f * f / frames,
                            "Mã hóa hình ảnh " + f + "/" + frames);
                }
            }

            // phase 4: video EOS
            checkCancel();
            videoCodec.signalEndOfInputStream();
            drain(videoCodec, vSink, videoFormat, true);
            publish(0.96f, "Hoàn tất khung hình…");

            if (muxStartedHolder[0]) {
                try {
                    muxHolder[0].stop();
                } catch (Throwable t) {
                    Log.w(TAG, "muxer.stop failed", t);
                }
                muxStartedHolder[0] = false;
            }
        } finally {
            if (muxHolder[0] != null) {
                try {
                    if (muxStartedHolder[0]) muxHolder[0].stop();
                } catch (Throwable ignored) {
                }
                try {
                    muxHolder[0].release();
                } catch (Throwable ignored) {
                }
            }
            try {
                videoCodec.stop();
            } catch (Throwable ignored) {
            }
            videoCodec.release();
            try {
                audioCodec.stop();
            } catch (Throwable ignored) {
            }
            audioCodec.release();
            if (egl != null) egl.release();
            try {
                codecSurface.release();
            } catch (Throwable ignored) {
            }
            if (frameBmp != null) frameBmp.recycle();
        }
    }

    private void renderFrame(RenderEngine engine, Canvas canvas, PcmFrames frames, int f) {
        frames.seek(f);
        engine.render(canvas, width, height, frames, (long) f * FRAME_US * 1000L);
    }

    // ------------------------------------------------------------ codec glue

    private static final class Pending {
        final byte[] bytes;
        final long ptsUs;
        final int flags;

        Pending(byte[] bytes, long ptsUs, int flags) {
            this.bytes = bytes;
            this.ptsUs = ptsUs;
            this.flags = flags;
        }
    }

    private interface Sink {
        void write(Pending p) throws Exception;
    }

    /** Holds encoded buffers until the muxer is armed, then streams them through. */
    private static final class DeferredSink implements Sink {
        private final List<Pending> held = new ArrayList<>();
        private MediaMuxer muxer;
        private int track;
        private boolean armed;

        void arm(MediaMuxer m, int trackIndex) throws Exception {
            muxer = m;
            track = trackIndex;
            armed = true;
            for (int i = 0; i < held.size(); i++) {
                writeDirect(held.get(i));
            }
            held.clear();
        }

        @Override
        public void write(Pending p) {
            if (!armed) {
                held.add(p);
            } else {
                writeDirect(p);
            }
        }

        private void writeDirect(Pending p) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.set(0, p.bytes.length, p.ptsUs,
                    p.flags & ~(MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                            | MediaCodec.BUFFER_FLAG_END_OF_STREAM));
            muxer.writeSampleData(track, ByteBuffer.wrap(p.bytes), info);
        }
    }

    private void drain(MediaCodec codec, Sink sink, MediaFormat[] fmtOut, boolean untilEos)
            throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idleSpins = 0;
        while (true) {
            checkCancel();
            int idx = codec.dequeueOutputBuffer(info, untilEos ? 10000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) return;
                if (++idleSpins > 500) throw new Exception("encoder không xuất EOS");
                continue;
            }
            idleSpins = 0;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                fmtOut[0] = codec.getOutputFormat();
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue;
            if (idx >= 0) {
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer ob = codec.getOutputBuffer(idx);
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    byte[] bytes = new byte[info.size];
                    ob.get(bytes);
                    sink.write(new Pending(bytes, info.presentationTimeUs, info.flags));
                }
                codec.releaseOutputBuffer(idx, false);
                if (eos) return;
            }
        }
    }

    /** Feeds the whole PCM stream into the AAC encoder (chunked, constant memory). */
    private void encodeAllAudio(MediaCodec codec, PcmSource pcm, long totalUs,
                                Sink sink, MediaFormat[] fmtOut, Runnable onFormatReady)
            throws Exception {
        final int ch = pcm.channels();
        final int sr = pcm.sampleRate();
        final long maxFrames = Math.max(1, totalUs * sr / 1_000_000L);
        final short[] chunk = new short[1024 * 2 * ch];
        long fed = 0;
        boolean inputDone = false;
        boolean outputDone = false;
        boolean readyFired = false;
        int idleSpins = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (!outputDone) {
            checkCancel();
            if (idleSpins++ > 200000) throw new Exception("audio encoder timeout");

            if (!inputDone) {
                int idx = codec.dequeueInputBuffer(10000);
                if (idx >= 0) {
                    ByteBuffer ib = codec.getInputBuffer(idx);
                    ib.clear();
                    int capGroups = (ib.capacity() / 2) / ch;
                    int want = Math.min(1024, Math.max(1, capGroups));
                    int got = pcm.read(chunk, fed, want);
                    ib.order(ByteOrder.LITTLE_ENDIAN);
                    if (got <= 0) {
                        codec.queueInputBuffer(idx, 0, 0,
                                fed * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        ShortBuffer sb = ib.asShortBuffer();
                        sb.put(chunk, 0, got * ch);
                        long ptsUs = fed * 1_000_000L / sr;
                        fed += got;
                        boolean eos = fed >= maxFrames || got < want;
                        codec.queueInputBuffer(idx, 0, got * ch * 2, ptsUs,
                                eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        if (eos) inputDone = true;
                    }
                    idleSpins = 0;
                }
            }

            int idx = codec.dequeueOutputBuffer(info, inputDone ? 10000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
            idleSpins = 0;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                fmtOut[0] = codec.getOutputFormat();
                if (!readyFired && fmtOut[0] != null) {
                    readyFired = true;
                    onFormatReady.run();
                }
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue;
            if (idx >= 0) {
                if (info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer ob = codec.getOutputBuffer(idx);
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    byte[] bytes = new byte[info.size];
                    ob.get(bytes);
                    sink.write(new Pending(bytes, info.presentationTimeUs, info.flags));
                }
                codec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }
        if (!readyFired) throw new Exception("audio encoder không xuất format");
    }

    // ------------------------------------------------------------ PCM frames

    static final class PcmFrames implements AudioFrames {
        private final PcmSource pcm;
        private final float[] smooth = new float[BANDS];
        private final float[] raw = new float[BANDS];
        private final byte[] wave = new byte[512];
        private final short[] win = new short[WIN * 2];
        private final float[] mono = new float[WIN];
        private final float[] hann = new float[WIN];
        private final double[] freqs = new double[BANDS];
        private float autoGain = 1f;
        private float avgBass = 0.3f;
        private float bass;
        private float level;
        private int beatVersion;
        private int consumedBeat = -1;
        private long lastBeatMs = -1000;

        PcmFrames(PcmSource pcm) {
            this.pcm = pcm;
            for (int i = 0; i < WIN; i++) {
                hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (WIN - 1)));
            }
            double f = 40.0;
            double ratio = Math.pow(12000.0 / 40.0, 1.0 / BANDS);
            for (int i = 0; i < BANDS; i++) {
                freqs[i] = f;
                f *= ratio;
            }
        }

        void seek(int frameIndex) {
            int ch = pcm.channels();
            long startL = (long) frameIndex * FRAME_US * pcm.sampleRate() / 1_000_000L;
            int got = pcm.read(win, startL, WIN);
            java.util.Arrays.fill(mono, 0f);
            for (int i = 0; i < got; i++) {
                float v;
                if (ch == 1) {
                    v = win[i] / 32768f;
                } else {
                    v = (win[2 * i] + win[2 * i + 1]) / 2f / 32768f;
                }
                mono[i] = v * hann[i];
            }
            java.util.Arrays.fill(win, (short) 0, Math.max(got * ch, 0), (short) 0);

            float maxRaw = 0f;
            for (int b = 0; b < BANDS; b++) {
                raw[b] = goertzel(freqs[b]);
                if (raw[b] > maxRaw) maxRaw = raw[b];
            }
            if (maxRaw > autoGain) autoGain = maxRaw;
            autoGain = Math.max(autoGain * 0.996f, 1e-3f);
            float sum = 0f;
            for (int b = 0; b < BANDS; b++) {
                float v = raw[b] / autoGain;
                if (v > 1f) v = 1f;
                smooth[b] += (v - smooth[b]) * 0.35f;
                sum += smooth[b];
            }
            level = sum / BANDS;
            bass = (smooth[0] + smooth[1] + smooth[2] + smooth[3]) / 4f;
            avgBass += (bass - avgBass) * 0.06f;
            long vm = frameIndex * FRAME_US / 1000L;
            if (bass > 0.28f && bass > avgBass * 1.35f + 0.05f && vm - lastBeatMs > 200) {
                lastBeatMs = vm;
                beatVersion++;
            }

            // waveform window for the oscilloscope effect
            int waveGot = pcm.read(win, Math.max(0, startL - 256), 512);
            for (int i = 0; i < wave.length; i++) {
                int si = i;
                float v = 0f;
                if (si < waveGot) {
                    v = ch == 1 ? win[si] / 32768f
                            : (win[2 * si] + win[2 * si + 1]) / 2f / 32768f;
                }
                int iv = (int) (v * 127f);
                if (iv > 127) iv = 127;
                if (iv < -128) iv = -128;
                wave[i] = (byte) (128 + iv);
            }
            java.util.Arrays.fill(win, (short) 0);
        }

        private float goertzel(double freq) {
            double sr = pcm.sampleRate();
            double k = 2.0 * Math.cos(2.0 * Math.PI * freq / sr);
            double s1 = 0, s2 = 0;
            for (int i = 0; i < WIN; i++) {
                double s0 = mono[i] + k * s1 - s2;
                s2 = s1;
                s1 = s0;
            }
            double power = s1 * s1 + s2 * s2 - k * s1 * s2;
            return (float) Math.sqrt(Math.max(0, power)) / WIN;
        }

        @Override
        public void copyBands(float[] out) {
            System.arraycopy(smooth, 0, out, 0, Math.min(out.length, BANDS));
        }

        @Override
        public void copyWave(byte[] out) {
            System.arraycopy(wave, 0, out, 0, Math.min(out.length, wave.length));
        }

        @Override
        public float getBass() {
            return bass;
        }

        @Override
        public float getLevel() {
            return level;
        }

        @Override
        public boolean consumeBeat() {
            if (beatVersion != consumedBeat) {
                consumedBeat = beatVersion;
                return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ GL

    private static final String VERT =
            "attribute vec2 aPos;\n"
                    + "attribute vec2 aUv;\n"
                    + "varying vec2 vUv;\n"
                    + "void main(){vUv=aUv;gl_Position=vec4(aPos,0.0,1.0);}\n";
    private static final String FRAG =
            "precision mediump float;\n"
                    + "varying vec2 vUv;\n"
                    + "uniform sampler2D uTex;\n"
                    + "void main(){gl_FragColor=texture2D(uTex,vUv);}\n";

    static final class EglRenderer {
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface surface = EGL14.EGL_NO_SURFACE;
        private int program;
        private int texture;
        private int posLoc, uvLoc, texLoc;
        private final int width, height;
        private final FloatBuffer posBuf;
        private final FloatBuffer uvBuf;
        private final ByteBuffer pixelBuf;
        private boolean texUploaded;

        EglRenderer(Surface nativeSurface, int width, int height) {
            this.width = width;
            this.height = height;

            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay failed");
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
                throw new RuntimeException("eglInitialize failed");
            }
            int[] cfgAttr = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 0,
                    0x3142, 1, // EGL_RECORDABLE_ANDROID
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(display, cfgAttr, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                throw new RuntimeException("eglChooseConfig failed");
            }
            int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext failed");
            int[] surfAttr = {EGL14.EGL_NONE};
            surface = EGL14.eglCreateWindowSurface(display, configs[0], nativeSurface, surfAttr, 0);
            if (surface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreateWindowSurface failed");
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new RuntimeException("eglMakeCurrent failed");
            }

            program = linkProgram(VERT, FRAG);
            GLES20.glUseProgram(program);
            texLoc = GLES20.glGetUniformLocation(program, "uTex");
            GLES20.glUniform1i(texLoc, 0);
            posLoc = GLES20.glGetAttribLocation(program, "aPos");
            uvLoc = GLES20.glGetAttribLocation(program, "aUv");

            posBuf = directFloats(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
            uvBuf = directFloats(new float[]{0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f});
            pixelBuf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());

            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            texture = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        private static FloatBuffer directFloats(float[] values) {
            FloatBuffer fb = ByteBuffer.allocateDirect(values.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(values);
            fb.position(0);
            return fb;
        }

        private static int linkProgram(String vertSrc, String fragSrc) {
            int vs = compile(GLES20.GL_VERTEX_SHADER, vertSrc);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragSrc);
            int prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, vs);
            GLES20.glAttachShader(prog, fs);
            GLES20.glLinkProgram(prog);
            int[] status = new int[1];
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(prog);
                throw new RuntimeException("link failed: " + log);
            }
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            return prog;
        }

        private static int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] status = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(s);
                throw new RuntimeException("compile failed: " + log);
            }
            return s;
        }

        void draw(Bitmap frame, long ptsNs) {
            pixelBuf.rewind();
            frame.copyPixelsToBuffer(pixelBuf);
            pixelBuf.position(0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            if (!texUploaded) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                        width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuf);
                texUploaded = true;
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                        width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuf);
            }

            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);
            GLES20.glEnableVertexAttribArray(posLoc);
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf);
            GLES20.glEnableVertexAttribArray(uvLoc);
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, uvBuf);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(posLoc);
            GLES20.glDisableVertexAttribArray(uvLoc);

            EGLExt.eglPresentationTimeANDROID(display, surface, ptsNs);
            if (!EGL14.eglSwapBuffers(display, surface)) {
                throw new RuntimeException("eglSwapBuffers failed");
            }
        }

        void release() {
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
            } catch (Throwable ignored) {
            }
            try {
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            } catch (Throwable ignored) {
            }
            try {
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            } catch (Throwable ignored) {
            }
            try {
                if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display);
            } catch (Throwable ignored) {
            }
        }
    }

    // -------------------------------------------------------------- publish

    private String publishToGallery(File cache) throws Exception {
        String base = "MusicClip_" + sanitize(track.title) + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, base + ".mp4");
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/MusicClip");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            ContentResolver resolver = appCtx.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("MediaStore insert lỗi");
            OutputStream os = resolver.openOutputStream(uri);
            if (os == null) throw new Exception("không mở được stream ghi");
            copyStream(cache, os);
            v.clear();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, v, null, null);
            cache.delete();
            return Environment.DIRECTORY_MOVIES + "/MusicClip/" + base + ".mp4";
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "MusicClip");
        if (!dir.exists() && !dir.mkdirs()) {
            File fallback = appCtx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (fallback == null) throw new Exception("không tạo được thư mục");
            dir = fallback;
        }
        File dst = new File(dir, base + ".mp4");
        if (!cache.renameTo(dst)) {
            copyStream(cache, new FileOutputStream(dst));
            cache.delete();
        }
        MediaScannerConnection.scanFile(appCtx,
                new String[]{dst.getAbsolutePath()}, null, null);
        return dst.getAbsolutePath();
    }

    private static void copyStream(File src, OutputStream os) throws Exception {
        InputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                os.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String sanitize(String s) {
        if (s == null || s.length() == 0) return "track";
        return s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }
}
