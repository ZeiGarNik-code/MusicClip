package com.musicclip.visualizer.source;

/** Maps spinner positions (R.array.source_names) to source instances. */
public final class SourceManager {
    private SourceManager() {}

    public static VisualSource create(int index, String[] labels) {
        VisualSource src;
        switch (index) {
            case 1:
                src = new WikimediaSource();
                break;
            case 2:
                src = new OpenverseSource();
                break;
            case 3:
                src = new NasaSource();
                break;
            case 4:
                src = new GallerySource();
                break;
            case 5:
                src = new CustomUrlSource();
                break;
            case 0:
            default:
                src = new PicsumSource();
                index = 0;
                break;
        }
        if (labels != null && index >= 0 && index < labels.length) {
            src.label = labels[index];
        } else {
            src.label = src.siteName();
        }
        return src;
    }
}
