package com.fadcam.ui.faditor.export;

/**
 * What a main-track clip lays in the audio-only export (.m4a, and the chunked export's sound pass): its own sound, or
 * silence of its length (STU-C3, 11-27).
 */
final class MasterSound {
    private MasterSound() {}

    /**
     * True when the clip lays silence. [audioDurationMs] is {@code ExportManager.audioDurationMsOf} of its source: 0 when
     * the source has no sound track, Long.MAX_VALUE when it could not be read.
     */
    static boolean laysSilence(boolean imageClip, boolean muted, long audioDurationMs) {
        return imageClip || muted;
    }
}
