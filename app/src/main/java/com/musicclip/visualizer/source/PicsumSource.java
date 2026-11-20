package com.musicclip.visualizer.source;

import java.util.Random;

/** https://picsum.photos - free Lorem Picsum / Unsplash photos, no API key. */
public class PicsumSource extends HttpSource {
    private final Random rnd = new Random();

    @Override
    public String siteName() {
        return "Lorem Picsum / Unsplash";
    }

    @Override
    protected String resolveUrl(int maxW, int maxH) {
        int w = Math.min(Math.max(maxW, 640), 1920);
        int h = Math.min(Math.max(maxH, 640), 2560);
        return "https://picsum.photos/" + w + "/" + h + "?random=" + (rnd.nextInt(100000) + 1);
    }
}
