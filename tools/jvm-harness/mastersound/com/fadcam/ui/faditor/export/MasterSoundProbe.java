package com.fadcam.ui.faditor.export;

/** Test door: MasterSound is package-private to the export package. */
public final class MasterSoundProbe {
    public static boolean laysSilence(boolean imageClip, boolean muted, long audioDurationMs) {
        return MasterSound.laysSilence(imageClip, muted, audioDurationMs);
    }
}
