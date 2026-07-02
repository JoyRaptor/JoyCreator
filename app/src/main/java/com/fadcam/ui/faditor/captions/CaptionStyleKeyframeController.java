package com.fadcam.ui.faditor.captions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.transcript.CaptionStyle;

import java.util.List;

/**
 * Stateless helper logic for the caption-style-keyframe UX (stopwatch arm + prev/next nav +
 * on-keyframe replace/remove). Owns the pure computations so {@code FaditorEditorActivity}
 * only has to wire view listeners to these results — it does not hold any Android view
 * references or undo-manager state itself (that stays in the activity, matching how the
 * sibling opacity/volume keyframe drawers are wired).
 *
 * <p>The activity remains the source of truth for {@code Clip} mutation + undo recording
 * (via {@code EditActions.CaptionStyleKeyframesAction} / the existing
 * snapshot-before → mutate → record-if-changed convention); this class only answers
 * "what should the UI show" and "where should nav jump to" questions so that logic isn't
 * duplicated inline at each call site.</p>
 */
public final class CaptionStyleKeyframeController {

    private CaptionStyleKeyframeController() {}

    /** Tolerance (ms) for snapping a playhead position onto an existing keyframe. */
    public static final long TOLERANCE_MS = Clip.CAPTION_STYLE_KEYFRAME_TOLERANCE_MS;

    /** The accent color for a caption style id — thin, documented alias for the single
     *  centralized style→color source ({@link CaptionStyle#byId(String)}) so callers in this
     *  feature don't need to know the underlying model class. */
    public static int colorForStyle(@NonNull String styleId) {
        return CaptionStyle.byId(styleId).activeColor;
    }

    /**
     * True when {@code clipMs} sits within {@link #TOLERANCE_MS} of any keyframe on
     * {@code clip}. Single source of truth for the "on a keyframe" check used by the
     * on-keyframe dot, the delete/"−" affordance, and tap-to-replace-vs-drop branching.
     */
    public static boolean isOnKeyframe(@Nullable Clip clip, long clipMs) {
        return indexOfKeyframeNear(clip, clipMs) >= 0;
    }

    /** Index of the keyframe within {@code TOLERANCE_MS} of {@code clipMs}, or -1. */
    public static int indexOfKeyframeNear(@Nullable Clip clip, long clipMs) {
        if (clip == null) return -1;
        List<Clip.CaptionStyleKeyframe> kfs = clip.getCaptionStyleKeyframes();
        for (int i = 0; i < kfs.size(); i++) {
            if (Math.abs(kfs.get(i).timeMs - clipMs) <= TOLERANCE_MS) return i;
        }
        return -1;
    }

    /** Result of a nav-state query: which directions are available and where they'd land. */
    public static final class NavState {
        public final boolean hasPrev;
        public final boolean hasNext;
        public final long prevTimeMs;
        public final long nextTimeMs;

        NavState(boolean hasPrev, long prevTimeMs, boolean hasNext, long nextTimeMs) {
            this.hasPrev = hasPrev;
            this.prevTimeMs = prevTimeMs;
            this.hasNext = hasNext;
            this.nextTimeMs = nextTimeMs;
        }
    }

    /**
     * Computes prev/next keyframe availability relative to {@code clipMs}, so the nav
     * buttons can dim/disable at the ends instead of only appearing/disappearing as a pair.
     * Keyframes within {@link #TOLERANCE_MS} of {@code clipMs} are treated as "current" and
     * skipped in both directions (matches the existing jump semantics: nav always moves to a
     * genuinely different keyframe).
     */
    @NonNull
    public static NavState computeNavState(@Nullable Clip clip, long clipMs) {
        if (clip == null || !clip.hasCaptionStyleKeyframes()) {
            return new NavState(false, -1, false, -1);
        }
        List<Clip.CaptionStyleKeyframe> kfs = clip.getCaptionStyleKeyframes(); // ascending
        long prevTime = -1;
        long nextTime = -1;
        for (Clip.CaptionStyleKeyframe kf : kfs) {
            if (kf.timeMs < clipMs - TOLERANCE_MS) {
                prevTime = kf.timeMs; // keep advancing — last one before clipMs wins
            } else if (kf.timeMs > clipMs + TOLERANCE_MS && nextTime < 0) {
                nextTime = kf.timeMs; // first one after clipMs
            }
        }
        return new NavState(prevTime >= 0, prevTime, nextTime >= 0, nextTime);
    }

    /**
     * What a style-chip tap should do while keyframe mode is armed, given the current
     * playhead position. The activity uses this to branch between "replace the keyframe at
     * the playhead" and "drop a new keyframe at the playhead" — both ultimately call the
     * same {@link Clip#addOrUpdateCaptionStyleKeyframe(long, String)} (which already
     * upserts), so this is mainly for UI feedback (e.g. toast wording) and for tests.
     */
    public enum TapAction { REPLACE, DROP }

    @NonNull
    public static TapAction tapActionFor(@Nullable Clip clip, long clipMs) {
        return isOnKeyframe(clip, clipMs) ? TapAction.REPLACE : TapAction.DROP;
    }
}
