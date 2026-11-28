package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * WHERE A FILMED CLIP'S OWN MOTION BLUR MAY RUN (STU-7, owner plan 11-28 step 1).
 *
 * <p>One answer for the live preview ({@code FxLivePreviewController}) and the export
 * ({@code ExportManager} / {@code HighFootageExportEffect}), so the two blur the same frames.
 * A {@link Span} is one continuous run of the clip's source: the frames in it are played in
 * order, each one frame after the last, so the flow between two neighbours is real motion and
 * the shutter may reach from one to the next. Everything outside a span is drawn sharp.</p>
 *
 * <p><b>Transitions.</b> The export plays a transition as its own piece, A's tail and B's head
 * blended ({@code ExportManager.buildTransitionItem}); the preview draws the blend over A's tail
 * and starts B past its head. So the span of a clip is its main pass less the outgoing tail and
 * the incoming head, cut with the export's own arithmetic (the seam-clamped
 * {@link TimeMap#effectiveTransitionMs}, rounded to source ms exactly as the export trims the
 * main item). A frame under a blend is never in a span, and the span's ends are fences, so no
 * shutter reaches across a seam. A project with transitions still blurs every clip body.</p>
 *
 * <p><b>Clock.</b> A span maps a source time to the editor clock the shutter keys, fences and
 * every other item read: {@code editorOrigin + (source - sourceOrigin) / speed}. That is the
 * preview's decoder anchor ({@code PreviewDecoderClock.Anchor}) and the timeline's own placement
 * of the clip ({@link PreviewClock#editorMs}), so a frame gets the same editor time whichever
 * route draws it.</p>
 *
 * <p>Pure: no Android, no media. {@code tools/jvm-harness/FootageSpansTest.java} pins it.</p>
 */
public final class FootageSpans {

    private FootageSpans() {}

    /** One continuous run of source frames that may blur, and its place on the editor clock. */
    public record Span(int clipIndex, long sourceStartUs, long sourceEndUs, long sourceOriginUs,
                       long editorOriginUs, double speed) {

        /** The editor (timeline) time of source time {@code sourceUs}. */
        public long editorUs(long sourceUs) {
            return editorOriginUs + Math.round((sourceUs - sourceOriginUs) / speed);
        }

        public long editorStartUs() { return editorUs(sourceStartUs); }

        public long editorEndUs() { return editorUs(sourceEndUs); }

        /** Does the source window {@code [fromUs, toUs]} lie wholly inside this span? */
        public boolean covers(long fromUs, long toUs) {
            return fromUs >= sourceStartUs && toUs <= sourceEndUs && fromUs < toUs;
        }
    }

    /**
     * Can this clip's footage blur at all? The clip-level rules both routes share. The project
     * switch, the launch gate and the decoder are the callers' business.
     */
    public static boolean eligible(@Nullable Clip clip) {
        if (clip == null || clip.isImageClip() || !clip.isRigidMotionBlurEnabled()
                || clip.hasLoopExtension() || clip.hasMesh() || clip.hasCornerPin()
                || clip.effectiveCropFractions() != null
                || (clip.isSpineTransformArmed() && clip.hasSpineTransform())) {
            return false;
        }
        float speed = clip.getSpeedMultiplier();
        if (!Float.isFinite(speed) || speed <= 0f) return false;
        var shutter = clip.getRigidMotionShutter();
        var keys = clip.getRigidMotionShutterKeys();
        return keys.everBlurs(shutter) && keys.maxAngle(shutter) <= 360;
    }

    /**
     * The main pass of master clip {@code index} outside both of its transitions, or null when
     * the clip cannot blur or nothing of it is left once the blends take their share.
     */
    @Nullable
    public static Span body(@NonNull Timeline timeline, int index) {
        if (index < 0 || index >= timeline.getClipCount()) return null;
        Clip clip = timeline.getClip(index);
        if (!eligible(clip)) return null;
        TimeMap map = TimeMap.of(timeline);
        float speed = clip.getSpeedMultiplier();
        long inMs = clip.getInPointMs();
        long outMs = clip.getOutPointMs();
        // The export's main-item trim (ExportManager.buildComposition), to the millisecond.
        long startMs = inMs;
        long head = map.headMs(index);
        if (head > 0) startMs = Math.min(outMs, inMs + Math.round(head * speed));
        long endMs = outMs;
        long tail = map.seamMs(index);
        if (tail > 0) endMs = Math.max(startMs, outMs - Math.round(tail * speed));
        // buildClipItem never reads past the end of the file.
        long sourceMs = clip.getSourceDurationMs();
        if (sourceMs > 0) endMs = Math.min(endMs, sourceMs);
        if (endMs <= startMs) return null;
        long editorOriginMs = map.clipEditorStartMs(index) + mainPassOffsetMs(clip);
        return new Span(index, startMs * 1000L, endMs * 1000L, inMs * 1000L,
                editorOriginMs * 1000L, speed);
    }

    /** Where the clip's own pass starts inside its visual span: after its loop-before. */
    static long mainPassOffsetMs(@NonNull Clip clip) {
        return clip.hasLoopExtension() && !clip.isImageClip() ? clip.getLoopBeforeMs() : 0L;
    }

    /** Index of {@code clip} on the master track by identity, or -1 (a filler, a PiP). */
    public static int indexOf(@NonNull Timeline timeline, @NonNull Clip clip) {
        for (int i = 0; i < timeline.getClipCount(); i++) {
            if (timeline.getClip(i) == clip) return i;
        }
        return -1;
    }
}
