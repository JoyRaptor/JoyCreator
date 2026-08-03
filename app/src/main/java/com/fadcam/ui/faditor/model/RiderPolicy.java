package com.fadcam.ui.faditor.model;

/**
 * What happens to an attached rider when its host master clip moves or resizes
 * (PLAN_TIMELINE_MANIPULATION_V1 §2.0).
 *
 * <p>Three relationships in this project are the SAME relationship — visualizer→host clip,
 * caption→transcribed source clip, layer item→anchor clip — and were on course to be built as
 * three mechanisms. They share {@link AnchorMath} and differ only here.</p>
 *
 * <p>The policy is a property of the RIDER KIND, not a user setting. Do not add a UI for it; the
 * user-facing control is attach/detach (the chain icon, and the caption detach dialog in
 * {@code PLAN_GESTURE_CONTRACT_FINAL_20260706.md} §4.1).</p>
 */
public enum RiderPolicy {

    /**
     * Shift with the host; never change duration. <b>Layer items.</b>
     *
     * <p>The user chose that duration and a ripple is not an edit to it. An item whose host grows
     * therefore does not grow with it, and one whose host shrinks may extend past it — both are
     * correct, because trim is an explicit action.</p>
     */
    SHIFT_ONLY,

    /**
     * Shift with the host and clamp into its span. <b>Visualizers.</b>
     *
     * <p>This is the SHIPPED behaviour of {@code Timeline.resyncAttachedVisualizers}, preserved
     * deliberately rather than by accident: an attached visualizer renders its host clip's audio,
     * so a window extending past the host would be drawing a waveform for a clip that is not
     * there. Recorded here so a later pass does not "fix" it into agreement with
     * {@link #SHIFT_ONLY} and silently change what ships.</p>
     */
    SHIFT_TRUNCATE,

    /**
     * Shift with the host and re-read the host's content. <b>Captions.</b>
     *
     * <p>A caption row is a view over its source clip's transcript, so moving the host moves the
     * captions and re-sourcing keeps them pointed at the right transcript. Detaching is a PROMPTED
     * choice, not a silent fallback, because a detached caption over two overlapping transcribed
     * clips has no unambiguous source — see the gesture contract §4.1.</p>
     *
     * <p><b>Not yet implemented.</b> The caption-attach slice is specced and has no code; this
     * constant exists so the model is shaped for it rather than retrofitted around it. Treat a
     * caption rider as {@link #SHIFT_ONLY} until that slice lands, and do not invent a second
     * caption mechanism in the meantime.</p>
     */
    SHIFT_RESOURCE
}
