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
        return audioMuted || (overlay && !audioIncluded);
    }

    /**
     * Whether a PiP is muted after its audio is included or left out ("Include audio", the drawer's sound toggle).
     * A PiP is added muted as well as with its audio left out, so including its audio unmutes it, or it stays silent
     * (STU-F6 on the Note 9, 11-28). Leaving it out keeps the mute as it was.
     */
    public static boolean mutedAfterInclude(boolean include, boolean mutedBefore) {
        return !include && mutedBefore;
    }

    /** Whether the clip's audio is included after the Volume controls set it muted or not. */
    public static boolean includedAfter(boolean overlay, boolean nowMuted, boolean includedBefore) {
        // Unmuting a PiP from the Volume controls means "let me hear it": its audio is included in the same step.
        return overlay ? (includedBefore || !nowMuted) : includedBefore;
    }
}
