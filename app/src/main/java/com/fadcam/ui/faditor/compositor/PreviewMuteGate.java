package com.fadcam.ui.faditor.compositor;

/**
 * The preview's mute switch for one sound clip (Codex round 2, R2-AUD-02).
 *
 * <p>The editor works out each clip's effective level (the clip muted, or its lane muted, gives 0)
 * and hands it to {@link AudioClipPreviewPlayer#setVolume}. That method used to drop the numbers
 * and only re-read the clip into its processors, which know nothing of mute, so a muted clip or
 * lane went on playing in the editor while the export left it out.</p>
 *
 * <p>The LEVEL itself (volume, curve, pan) stays with the processors, which read the model, so it
 * is not applied twice. This is only the gate: shut when the clip is muted or the editor says its
 * effective level is nothing, open otherwise. A level of exactly 0 that is not a mute (the slider
 * at zero, or the curve sitting on a zero key) is silence either way, so the gate cannot change
 * what a listener hears there.</p>
 */
public final class PreviewMuteGate {
    private PreviewMuteGate() {}

    /** The player gain: 0 (shut) or 1 (open; the processors set the level). */
    public static float playerGain(boolean clipMuted, float left, float right) {
        if (clipMuted) return 0f;
        if (!(left > 0f) && !(right > 0f)) return 0f;   // NaN counts as shut
        return 1f;
    }
}
