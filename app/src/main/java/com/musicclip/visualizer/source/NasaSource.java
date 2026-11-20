package com.musicclip.visualizer.source;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Random;

/** NASA image library (images-api.nasa.gov) - public domain, no key (DEMO_KEY not even needed). */
public class NasaSource extends HttpSource {
    private static final String[] QUERIES = {
            "nebula", "earth", "galaxy", "moon", "solar system",
            "astronaut", "mars", "deep space", "aurora", "iss",
    };
    private final Random rnd = new Random();

    @Override
    public String siteName() {
        return "NASA Image Library";
    }

    @Override
    protected String resolveUrl(int maxW, int maxH) throws Exception {
        String q = QUERIES[rnd.nextInt(QUERIES.length)];
        String api = "https://images-api.nasa.gov/search?q="
                + URLEncoder.encode(q, "UTF-8") + "&media_type=image";
        JSONObject json = Remote.getJson(api);
        JSONObject collection = json.optJSONObject("collection");
        if (collection == null) return null;
        org.json.JSONArray items = collection.optJSONArray("items");
        if (items == null || items.length() == 0) return null;

        // try a few random items until one has a direct image link
        for (int attempt = 0; attempt < 5; attempt++) {
            JSONObject item = items.optJSONObject(rnd.nextInt(items.length()));
            if (item == null) continue;
            org.json.JSONArray links = item.optJSONArray("links");
            if (links == null || links.length() == 0) continue;
            JSONObject link = links.optJSONObject(0);
            if (link == null) continue;
            String href = link.optString("href", "");
            if (href.length() == 0) continue;
            lastCredit = "NASA";
            return href;
        }
        return null;
    }

    private volatile String lastCredit = "NASA";

    @Override
    protected String creditFor(String url) {
        return lastCredit;
    }
}
