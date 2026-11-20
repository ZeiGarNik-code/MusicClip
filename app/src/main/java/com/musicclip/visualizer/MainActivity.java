package com.musicclip.visualizer;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Launcher screen: pick a music track + a free visual source, then start. */
public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_MIC = 101;

    private Spinner sourceSpinner;
    private ListView trackList;
    private Button btnStart;
    private Button btnGrant;
    private TextView permissionHint;

    private final List<Track> tracks = new ArrayList<>();
    private TrackAdapter adapter;
    private int selectedIndex = 0;
    private boolean micRequested;
    private String[] sourceLabels;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sourceLabels = getResources().getStringArray(R.array.source_names);
        sourceSpinner = (Spinner) findViewById(R.id.sourceSpinner);
        trackList = (ListView) findViewById(R.id.trackList);
        btnStart = (Button) findViewById(R.id.btnStart);
        btnGrant = (Button) findViewById(R.id.btnGrant);
        permissionHint = (TextView) findViewById(R.id.permissionHint);

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, sourceLabels);
        sourceSpinner.setAdapter(spinnerAdapter);

        adapter = new TrackAdapter();
        trackList.setAdapter(adapter);
        trackList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                selectedIndex = position;
                trackList.setItemChecked(position, true);
            }
        });

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVisualizer();
            }
        });
        btnGrant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestNeededPermissions();
            }
        });

        reloadTracks();
        updatePermissionUi();
        requestNeededPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadTracks();
        updatePermissionUi();
    }

    private boolean hasPermission(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canReadMusic() {
        if (Build.VERSION.SDK_INT >= 33) {
            return hasPermission(Manifest.permission.READ_MEDIA_AUDIO);
        }
        return hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (!canReadMusic()) {
            if (Build.VERSION.SDK_INT >= 33) {
                need.add(Manifest.permission.READ_MEDIA_AUDIO);
            } else {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS || requestCode == REQ_MIC) {
            reloadTracks();
            updatePermissionUi();
            if (requestCode == REQ_MIC && pendingLaunch) {
                pendingLaunch = false;
                launch();
            }
        }
    }

    private void updatePermissionUi() {
        boolean ok = canReadMusic();
        permissionHint.setVisibility(ok ? View.GONE : View.VISIBLE);
        btnGrant.setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    private void reloadTracks() {
        int prevSize = tracks.size();
        tracks.clear();
        tracks.addAll(BuiltInTracks.all());
        if (canReadMusic()) {
            try {
                Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String[] proj = new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.DURATION,
                };
                Cursor cur = getContentResolver().query(base, proj,
                        MediaStore.Audio.Media.IS_MUSIC + "!=0", null,
                        MediaStore.Audio.Media.TITLE + " ASC");
                if (cur != null) {
                    while (cur.moveToNext()) {
                        long id = cur.getLong(0);
                        String title = cur.getString(1);
                        String artist = cur.getString(2);
                        long dur = cur.getLong(3);
                        if (title == null || title.length() == 0) continue;
                        Uri uri = ContentUris.withAppendedId(base, id);
                        tracks.add(Track.device("ms_" + id, title, artist, dur, uri));
                    }
                    cur.close();
                }
            } catch (SecurityException e) {
                // permission revoked meanwhile
            }
        }
        if (selectedIndex >= tracks.size()) selectedIndex = 0;
        if (tracks.size() != prevSize || prevSize == 0) {
            adapter.notifyDataSetChanged();
            if (!tracks.isEmpty()) {
                trackList.setItemChecked(selectedIndex, true);
            }
        }
    }

    private boolean pendingLaunch;

    private void startVisualizer() {
        if (tracks.isEmpty()) {
            Toast.makeText(this, R.string.empty_device_music, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO) && !micRequested) {
            micRequested = true;
            pendingLaunch = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        launch();
    }

    private void launch() {
        if (tracks.isEmpty()) return;
        Track t = tracks.get(Math.min(selectedIndex, tracks.size() - 1));
        Intent i = new Intent(this, VisualizerActivity.class);
        i.putExtra(VisualizerActivity.EXTRA_BUILTIN, t.builtin);
        i.putExtra(VisualizerActivity.EXTRA_ASSET_PATH, t.assetPath);
        i.putExtra(VisualizerActivity.EXTRA_URI, t.uri != null ? t.uri.toString() : null);
        i.putExtra(VisualizerActivity.EXTRA_TITLE, t.title);
        i.putExtra(VisualizerActivity.EXTRA_ARTIST, t.artist);
        i.putExtra(VisualizerActivity.EXTRA_SOURCE_INDEX, sourceSpinner.getSelectedItemPosition());
        startActivity(i);
    }

    // -------------------------------------------------------------- adapter

    private class TrackAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return tracks.size();
        }

        @Override
        public Object getItem(int position) {
            return tracks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_track, parent, false);
            }
            Track t = tracks.get(position);
            TextView title = (TextView) v.findViewById(R.id.itemTitle);
            TextView sub = (TextView) v.findViewById(R.id.itemSub);
            TextView badge = (TextView) v.findViewById(R.id.itemBadge);
            title.setText(t.title);
            sub.setText(t.subLabel());
            if (t.builtin) {
                badge.setText(R.string.builtin_badge);
                badge.setBackgroundResource(R.drawable.badge_builtin);
                badge.setTextColor(Color.rgb(8, 60, 30));
            } else {
                badge.setText(R.string.device_badge);
                badge.setBackgroundResource(R.drawable.badge_device);
                badge.setTextColor(Color.WHITE);
            }
            return v;
        }
    }
}
