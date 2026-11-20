package com.musicclip.visualizer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.musicclip.visualizer.audio.AudioEngine;
import com.musicclip.visualizer.export.VideoExporter;
import com.musicclip.visualizer.render.IdleAudio;
import com.musicclip.visualizer.render.VisualizerView;
import com.musicclip.visualizer.source.CustomUrlSource;
import com.musicclip.visualizer.source.GallerySource;
import com.musicclip.visualizer.source.PicsumSource;
import com.musicclip.visualizer.source.SourceManager;
import com.musicclip.visualizer.source.VisualSource;
import com.musicclip.visualizer.util.ImageStore;

/** Fullscreen live visualizer with background sources, screenshot and video export. */
public class VisualizerActivity extends Activity {
    public static final String EXTRA_BUILTIN = "builtin";
    public static final String EXTRA_ASSET_PATH = "assetPath";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_SOURCE_INDEX = "sourceIndex";

    private static final int REQ_PICK_IMAGE = 201;
    private static final int REQ_STORAGE = 202;
    private static final int FETCH_W = 1280;
    private static final int FETCH_H = 1920;
    private static final long AUTO_ROTATE_MS = 20000;

    private VisualizerView view;
    private AudioEngine engine;
    private Track track;
    private String[] sourceLabels;
    private int sourceIndex;
    private VisualSource source;

    private TextView trackTitle;
    private TextView sourceName;
    private Button btnPlay;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Bitmap currentBg;
    private boolean resumeAudioOnReturn;
    private boolean pendingScreenshot;
    private boolean pendingExport;

    private boolean exporting;
    private AlertDialog exportDialog;
    private TextView progressStage;
    private TextView progressPercent;
    private ProgressBar progressBar;
    private VideoExporter activeExporter;

    private final Runnable autoRotate = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing()) {
                changeBackground(true);
                main.postDelayed(this, AUTO_ROTATE_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersive();
        setContentView(R.layout.activity_visualizer);

        Intent in = getIntent();
        boolean builtin = in.getBooleanExtra(EXTRA_BUILTIN, true);
        String assetPath = in.getStringExtra(EXTRA_ASSET_PATH);
        String uriStr = in.getStringExtra(EXTRA_URI);
        String title = in.getStringExtra(EXTRA_TITLE);
        String artist = in.getStringExtra(EXTRA_ARTIST);
        sourceIndex = in.getIntExtra(EXTRA_SOURCE_INDEX, 0);
        if (builtin) {
            track = Track.builtin("asset", title != null ? title : "Nhạc có sẵn", artist, assetPath);
        } else {
            track = Track.device("dev", title != null ? title : "Thiết bị", artist, 0,
                    uriStr != null ? Uri.parse(uriStr) : null);
        }

        sourceLabels = getResources().getStringArray(R.array.source_names);
        source = SourceManager.create(sourceIndex, sourceLabels);

        view = (VisualizerView) findViewById(R.id.surface);
        view.setAudio(new IdleAudio());
        trackTitle = (TextView) findViewById(R.id.trackTitle);
        sourceName = (TextView) findViewById(R.id.sourceName);
        btnPlay = (Button) findViewById(R.id.btnPlay);
        trackTitle.setText(track.title);

        engine = new AudioEngine();
        engine.prepare(this, track, new AudioEngine.Listener() {
            @Override
            public void onPrepared() {
                if (isFinishing()) return;
                view.setAudio(engine);
                engine.start();
                updatePlayButton();
                if (!engine.isReactive()) {
                    Toast.makeText(VisualizerActivity.this,
                            R.string.need_mic_permission, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(VisualizerActivity.this,
                        "Lỗi phát nhạc: " + message, Toast.LENGTH_LONG).show();
            }
        });

        changeBackground(false);
        main.postDelayed(autoRotate, AUTO_ROTATE_MS);

        ((Button) findViewById(R.id.btnBack)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine == null) return;
                if (engine.isPlaying()) {
                    engine.pause();
                } else {
                    engine.start();
                }
                updatePlayButton();
            }
        });
        ((Button) findViewById(R.id.btnEffect)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int e = view.cycleEffect();
                String[] names = getResources().getStringArray(R.array.effect_names);
                Toast.makeText(VisualizerActivity.this,
                        getString(R.string.effect_toast, names[e]), Toast.LENGTH_SHORT).show();
            }
        });
        ((Button) findViewById(R.id.btnBackground)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeBackground(false);
            }
        });
        ((Button) findViewById(R.id.btnScreenshot)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureScreenshot();
            }
        });
        ((Button) findViewById(R.id.btnExport)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExport();
            }
        });
    }

    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void updatePlayButton() {
        if (engine != null && engine.isPlaying()) {
            btnPlay.setText(R.string.btn_pause);
        } else {
            btnPlay.setText(R.string.btn_play);
        }
    }

    // ------------------------------------------------------------ backgrounds

    private void changeBackground(boolean auto) {
        if (source.needsPick()) {
            if (auto) return; // keep the picked image during auto-rotate
            pickImage();
            return;
        }
        if (source.needsInput()) {
            if (auto) return;
            if (((CustomUrlSource) source).getUrl().length() == 0) {
                askUrl();
                return;
            }
        }
        sourceName.setText(source.label + " · đang tải…");
        source.fetch(this, FETCH_W, FETCH_H, new VisualSource.Callback() {
            @Override
            public void onImage(final Bitmap bitmap, final String credit) {
                if (isFinishing()) {
                    bitmap.recycle();
                    return;
                }
                applyBackground(bitmap, credit);
            }

            @Override
            public void onError(String message) {
                if (isFinishing()) return;
                if (!(source instanceof PicsumSource)) {
                    Toast.makeText(VisualizerActivity.this,
                            getString(R.string.source_error, source.label),
                            Toast.LENGTH_SHORT).show();
                    sourceIndex = 0;
                    source = SourceManager.create(0, sourceLabels);
                    changeBackground(false);
                } else {
                    sourceName.setText(source.label + " · lỗi: " + message);
                }
            }
        });
    }

    private void applyBackground(Bitmap bmp, String credit) {
        currentBg = bmp;
        view.setBackgroundBitmap(bmp);
        sourceName.setText(source.label + " · " + credit);
    }

    private void pickImage() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, REQ_PICK_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void askUrl() {
        final EditText input = new EditText(this);
        input.setHint(R.string.url_hint);
        input.setSingleLine(true);
        input.setText(((CustomUrlSource) source).getUrl());
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.url_title)
                .setView(input)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ((CustomUrlSource) source).setUrl(input.getText().toString());
                        changeBackground(false);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                sourceName.setText("Ảnh của tôi · đang tải…");
                GallerySource.fetchUri(this, uri, FETCH_W, FETCH_H, new VisualSource.Callback() {
                    @Override
                    public void onImage(Bitmap bitmap, String credit) {
                        if (isFinishing()) {
                            bitmap.recycle();
                            return;
                        }
                        applyBackground(bitmap, credit);
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(VisualizerActivity.this,
                                "Không mở được ảnh: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------ screenshot

    private void captureScreenshot() {
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingScreenshot = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        doCapture();
    }

    private void doCapture() {
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) return;
        final Bitmap shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        view.renderSnapshot(shot);
        ImageStore.savePng(this, shot, new ImageStore.Callback() {
            @Override
            public void onSaved(String location) {
                shot.recycle();
                Toast.makeText(VisualizerActivity.this,
                        getString(R.string.saved_toast, location), Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String message) {
                shot.recycle();
                Toast.makeText(VisualizerActivity.this,
                        getString(R.string.save_error, message), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---------------------------------------------------------------- export

    private void startExport() {
        if (exporting) return;
        if (currentBg == null) {
            Toast.makeText(this, "Đang chờ hình nền…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingExport = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        doExport();
    }

    private void doExport() {
        exporting = true;
        showExportDialog();
        int w = 720;
        int h = 1280;
        activeExporter = new VideoExporter(getApplicationContext(), track, currentBg,
                view.getEffectIndex(), w, h, new VideoExporter.Listener() {
                    @Override
                    public void onProgress(float fraction, final String stage) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (exportDialog != null && exportDialog.isShowing()) {
                                    progressBar.setProgress((int) (fraction * 100));
                                    progressPercent.setText((int) (fraction * 100) + "%");
                                    progressStage.setText(stage);
                                }
                            }
                        });
                    }

                    @Override
                    public void onDone(final String location) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                exporting = false;
                                dismissExportDialog();
                                Toast.makeText(VisualizerActivity.this,
                                        getString(R.string.export_done, location),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }

                    @Override
                    public void onError(final String message) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                exporting = false;
                                dismissExportDialog();
                                Toast.makeText(VisualizerActivity.this,
                                        getString(R.string.export_error, message),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
        activeExporter.start();
    }

    private void showExportDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_progress, null);
        progressStage = (TextView) content.findViewById(R.id.progressStage);
        progressPercent = (TextView) content.findViewById(R.id.progressPercent);
        progressBar = (ProgressBar) content.findViewById(R.id.progressBar);
        exportDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.export_title)
                .setView(content)
                .setCancelable(false)
                .setNegativeButton(R.string.export_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (activeExporter != null) activeExporter.cancel();
                        exporting = false;
                    }
                })
                .create();
        exportDialog.show();
    }

    private void dismissExportDialog() {
        if (exportDialog != null) {
            try {
                exportDialog.dismiss();
            } catch (Exception ignored) {
            }
            exportDialog = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingScreenshot) {
                pendingScreenshot = false;
                if (granted) doCapture();
                else Toast.makeText(this, R.string.need_storage_permission, Toast.LENGTH_SHORT).show();
            }
            if (pendingExport) {
                pendingExport = false;
                if (granted) doExport();
                else Toast.makeText(this, R.string.need_storage_permission, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        if (view != null) view.startRender();
        if (resumeAudioOnReturn && engine != null) {
            engine.start();
            updatePlayButton();
        }
        resumeAudioOnReturn = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumeAudioOnReturn = engine != null && engine.isPlaying();
        if (engine != null) engine.pause();
        if (view != null) view.stopRender();
        updatePlayButton();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(autoRotate);
        if (activeExporter != null) activeExporter.cancel();
        dismissExportDialog();
        if (engine != null) engine.release();
        if (view != null) view.stopRender();
    }
}
