package com.musicclip.visualizer.source;

import android.graphics.Bitmap;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Wikimedia Commons search API - free-licensed images, no API key.
 * https://commons.wikimedia.org/w/api.php
 */
public class WikimediaSource extends HttpSource {
    private static final String[] QUERIES = {
            "night sky", "city neon", "ocean wave", "mountain fog", "forest light",
            "abstract color", "sunset horizon", "flower macro", "architecture modern",
            "galaxy nebula", "lake reflection", "desert dune", "rain street", "aurora",
    };
    private final Random rnd = new Random();

    @Override
    public String siteName() {
        return "Wikimedia Commons";
    }

    @Override
    protected String resolveUrl(int maxW, int maxH) throws Exception {
        String q = QUERIES[rnd.nextInt(QUERIES.length)];
        String api = "https://commons.wikimedia.org/w/api.php?action=query&format=json"
                + "&formatversion=2&generator=search"
                + "&gsrsearch=" + URLEncoder.encode("filetype:bitmap " + q, "UTF-8")
                + "&gsrnamespace=6&gsrlimit=24"
                + "&prop=imageinfo&iiprop=url|extmetadata&iiurlwidth=" + Math.min(maxW, 1600);
        JSONObject json = Remote.getJson(api);
        JSONObject query = json.optJSONObject("query");
        if (query == null) return null;
        org.json.JSONArray pages = query.optJSONArray("pages");
        if (pages == null || pages.length() == 0) return null;

        List<String> urls = new ArrayList<>();
        List<String> credits = new ArrayList<>();
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.optJSONObject(i);
            if (page == null) continue;
            org.json.JSONArray info = page.optJSONArray("imageinfo");
            if (info == null || info.length() == 0) continue;
            JSONObject ii = info.optJSONObject(0);
            if (ii == null) continue;
            String thumb = ii.optString("thumburl", "");
            if (thumb.length() == 0) thumb = ii.optString("url", "");
            if (thumb.length() == 0) continue;
            String license = "free";
            try {
                JSONObject meta = ii.optJSONObject("extmetadata");
                if (meta != null) {
                    license = meta.optJSONObject("LicenseShortName").optString("value", "free");
                }
            } catch (Exception ignored) {
            }
            urls.add(thumb);
            credits.add("Wikimedia Commons · " + license);
        }
        if (urls.isEmpty()) return null;
        int pick = rnd.nextInt(urls.size());
        lastCredit = credits.get(pick);
        return urls.get(pick);
    }

    private volatile String lastCredit = "Wikimedia Commons";

    @Override
    protected String creditFor(String url) {
        return lastCredit;
    }
}
