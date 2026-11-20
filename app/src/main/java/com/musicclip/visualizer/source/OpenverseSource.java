package com.musicclip.visualizer.source;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Random;

/** Openverse (openverse.org) - aggregated CC / public-domain images, no key needed. */
public class OpenverseSource extends HttpSource {
    private static final String[] QUERIES = {
            "nature", "space", "city", "abstract", "ocean", "portrait light",
            "mountains", "street photography", "flowers", "architecture",
    };
    private final Random rnd = new Random();

    @Override
    public String siteName() {
        return "Openverse";
    }

    @Override
    protected String resolveUrl(int maxW, int maxH) throws Exception {
        String q = QUERIES[rnd.nextInt(QUERIES.length)];
        String api = "https://api.openverse.org/v1/images/?q="
                + URLEncoder.encode(q, "UTF-8") + "&page_size=20";
        JSONObject json = Remote.getJson(api);
        org.json.JSONArray results = json.optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        int pick = rnd.nextInt(results.length());
        JSONObject item = results.optJSONObject(pick);
        if (item == null) return null;
        String url = item.optString("url", "");
        if (url.length() == 0) return null;
        String license = item.optString("license", "");
        lastCredit = license.length() > 0 ? "Openverse · " + license.toUpperCase() : "Openverse";
        return url;
    }

    private volatile String lastCredit = "Openverse";

    @Override
    protected String creditFor(String url) {
        return lastCredit;
    }
}
