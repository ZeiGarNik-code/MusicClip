package com.musicclip.visualizer.source;

/**
 * "Cảnh ma mị" — eerie landscape photography (fog, night forests, abandoned
 * places, storms) pulled from Wikimedia Commons free-licensed images.
 */
public class EerieSource extends WikimediaSource {
    private static final String[] EERIE_QUERIES = {
            "fog forest", "misty mountain night", "night forest moon", "abandoned house",
            "storm clouds sea", "foggy lake", "dark clouds landscape", "full moon night",
            "foggy road", "old castle fog", "winter fog trees", "foggy graveyard",
            "thunderstorm landscape", "dead tree fog", "moonlit clouds",
    };

    public EerieSource() {
        super(EERIE_QUERIES);
    }

    @Override
    public String siteName() {
        return "Cảnh ma mị · Wikimedia";
    }
}
