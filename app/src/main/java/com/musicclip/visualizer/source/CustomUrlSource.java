package com.musicclip.visualizer.source;

import android.content.Context;

/** Background image from a user-provided direct URL. */
public class CustomUrlSource extends HttpSource {
    private volatile String url = "";

    public void setUrl(String u) {
        this.url = u != null ? u.trim() : "";
    }

    public String getUrl() {
        return url;
    }

    @Override
    public String siteName() {
        return "URL tùy chọn";
    }

    @Override
    public boolean needsInput() {
        return true;
    }

    @Override
    protected String resolveUrl(int maxW, int maxH) throws Exception {
        String u = url;
        if (u.length() == 0) throw new Exception("chưa có URL");
        return u;
    }

    @Override
    protected String creditFor(String url) {
        return "URL: " + url;
    }
}
