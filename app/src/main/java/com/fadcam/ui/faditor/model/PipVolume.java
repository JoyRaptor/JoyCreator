package com.fadcam.ui.faditor.model;

/**
 * What the Volume controls say about a clip, so they say what the export does (STU-F6, 11-28). A picture-in-picture is
 * heard only when its audio is included ({@link Clip#isOverlayAudioEnabled}, off when it is added) AND it is not muted;
 * a main-track clip only has to be not muted.
 */
public final class PipVolume {
    private PipVolume() {}

    /** Whether the Volume controls show the clip as muted. */
    public static boolean shownMuted(boolean overlay, boolean audioMuted, boolean audioIncluded) {
        return audioMuted;
    }

    /** Whether the clip's audio is included after the Volume controls set it muted or not. */
    public static boolean includedAfter(boolean overlay, boolean nowMuted, boolean includedBefore) {
        return includedBefore;
    }
}
