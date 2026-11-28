package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The PREVIEW half of the one time authority (STU-5). {@link TimeMap} answers "where does editor
 * time t land in the exported file"; this answers the questions the live preview asks, with the
 * same clip starts, the same transition and the same transition length the export uses:
 * <ul>
 *   <li>where on the timeline a player position in a master clip is ({@link #editorMs});</li>
 *   <li>the inverse, a timeline time back to a position in the clip ({@link #clipMediaMs});</li>
 *   <li>which transition a seam has, and how long its blend is in the outgoing clip's own media
 *       time ({@link #transitionAt}, {@link #blendMediaMs}).</li>
 * </ul>
 *
 * <p><b>Units.</b> A player position is MEDIA ms from the clip's in-point: it advances at the
 * clip's speed. A timeline (editor) time is that divided by the speed. Mixing the two is what made
 * the preview disagree with the export on a clip that is not at normal speed (code review 11-11).</p>
 *
 * <p>Pure Java: {@code tools/jvm-harness/PreviewClockTest.java} pins it against frozen copies of the
 * code it replaced, and against {@link TimeMap}.</p>
 */
public final class PreviewClock {

    private PreviewClock() {}

    /**
     * The editor-clock time of {@code mediaPosMs} (player position, media ms from the in-point) in
     * master clip {@code idx}: the clip's start ON THE TIMELINE (the same sum the timeline draws,
     * loop extensions included) plus the position divided by the clip's speed.
     *
     * <p>Replaces the preview's own sum of TRIMMED lengths, which left out an earlier clip's loop
     * extension (so captions, overlays and the time display ran early after a looped clip), and
     * the blend's playhead, which divided earlier clips by their speed twice and took the
     * transitions off (it showed the file's clock, then jumped at the end of the blend).</p>
     */
    public static long editorMs(@NonNull Timeline tl, int idx, long mediaPosMs) {
        if (idx < 0 || idx >= tl.getClipCount()) return Math.max(0L, mediaPosMs);
        Clip c = tl.getClip(idx);
        long start = tl.getClipStartMs(idx);
        if (c == null) return start + mediaPosMs;
        float speed = c.getSpeedMultiplier();
        return start + (speed > 0f ? (long) (mediaPosMs / speed) : mediaPosMs);
    }

    /** The inverse of {@link #editorMs}: a timeline time to a media position in clip {@code idx}. */
    public static long clipMediaMs(@NonNull Timeline tl, int idx, long editorMs) {
        if (idx < 0 || idx >= tl.getClipCount()) return Math.max(0L, editorMs);
        Clip c = tl.getClip(idx);
        long local = Math.max(0L, editorMs - tl.getClipStartMs(idx));
        if (c == null) return local;
        float speed = c.getSpeedMultiplier();
        return speed > 0f ? (long) (local * speed) : local;
    }

    /** The transition on the seam after clip {@code seam}, as the export reads it (the FIRST listed). */
    @Nullable
    public static Transition transitionAt(@NonNull Timeline tl, int seam) {
        return TimeMap.transitionAtSeam(tl, seam);
    }

    /**
     * How long the blend at {@code seam} lasts in the OUTGOING clip's media time: the export's own
     * clamped transition length ({@link TimeMap#effectiveTransitionMs}: never more than either clip
     * can give, shared when a clip has a transition on both sides) times that clip's speed, at
     * least 1 and at most the clip's own media length. 0 when the seam has no transition.
     */
    public static long blendMediaMs(@NonNull Timeline tl, int seam) {
        if (seam < 0 || seam >= tl.getClipCount()) return 0L;
        Transition t = transitionAt(tl, seam);
        if (t == null) return 0L;
        Clip a = tl.getClip(seam);
        long mediaLen = Math.max(1L, a.getOutPointMs() - a.getInPointMs());
        long eff = TimeMap.effectiveTransitionMs(tl, t, seam);
        long media = Math.round(eff * (double) Math.max(0.1f, a.getSpeedMultiplier()));
        return Math.max(1L, Math.min(mediaLen, media));
    }

    /** Where in the outgoing clip's media time the blend at {@code seam} opens. */
    public static long blendStartMediaMs(@NonNull Timeline tl, int seam) {
        if (seam < 0 || seam >= tl.getClipCount()) return 0L;
        Clip a = tl.getClip(seam);
        long mediaLen = Math.max(1L, a.getOutPointMs() - a.getInPointMs());
        return mediaLen - blendMediaMs(tl, seam);
    }

    /**
     * Where, in the media time of the pass through the clip now playing, the blend at {@code seam} opens; the pass started
     * [passStartVisualMs] into the clip's visual length (its loop-before, plus the passes already played). Today's
     * rule: on every pass, at the clip's end.
     */
    public static long blendOpensAtInPass(@NonNull Timeline tl, int seam, long passStartVisualMs) {
        return blendStartMediaMs(tl, seam);
    }
}
