package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;

import java.util.Map;

/**
 * The owner's ripple check (STU-5, ruling 11-27, "transitions overlap (choice A), with a ripple check"): after an edit
 * that makes the project shorter or longer at a point, say what will happen ("this makes the project 1 s shorter; 6
 * layers start after this point") and let the person move those layers with their clips or leave them, as ONE undo
 * step, with a "don't ask again" answer.
 *
 * <p>The moving itself is NOT done here: the timeline already does it, as one structural step
 * ({@link Timeline#beginStructural} / {@link Timeline#endStructural}: in "ripple" mode everything after the point moves,
 * floating layers included; in "gap" mode nothing does). This adds only what the check needs on top: the numbers it
 * shows ({@link #summarize}) and a per-edit answer ({@link #finish}) that leaves the project's own setting alone.</p>
 *
 * <p>HIDDEN until the owner picks the screen on his options page: {@link #ENABLED} is off and nothing asks. Until a
 * screen exists, ASK behaves as the project's own setting, which is today's behaviour.</p>
 */
public final class RippleCheck {
    private RippleCheck() {}

    /** The hidden switch: off until the owner's screen is built. */
    public static volatile boolean ENABLED = false;

    /** The person's standing answer: ask (the screen), move the layers with their clips, or leave them. */
    public enum Choice {
        ASK, MOVE, LEAVE;

        /** The stored answer, from its name; ASK for anything else (a new or unreadable setting). */
        @NonNull
        public static Choice of(String stored) {
            if (stored != null) for (Choice c : values()) if (c.name().equals(stored)) return c;
            return ASK;
        }
    }

    /** What the check says: the project [changeMs] longer (negative: shorter), and [layers] starting at or after [atMs]. */
    public static final class Summary {
        public final long atMs;
        public final long changeMs;
        public final int layers;

        Summary(long atMs, long changeMs, int layers) {
            this.atMs = atMs;
            this.changeMs = changeMs;
            this.layers = layers;
        }

        /** Nothing to ask: the length did not change, or nothing is placed after the point. */
        public boolean nothingToAsk() { return changeMs == 0 || layers == 0; }
    }

    /**
     * What an edit at [atMs] did to the length of the clips' own track (as it is now against [trackBeforeMs], from
     * {@link Timeline#getVideoTrackDurationMs}: the whole project's length also counts layers that run past the clips,
     * which have not moved yet when the check is asked) and how many layers
     * (pictures and text, sprites, sound, picture-in-pictures, adjustment layers, visualisers) start at or after it.
     */
    @NonNull
    public static Summary summarize(@NonNull Timeline timeline, long trackBeforeMs, long atMs) {
        int n = 0;
        for (TextOverlayItem t : timeline.getTextOverlays()) if (t.getStartMs() >= atMs) n++;
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : timeline.getSpriteOverlays()) if (s.getStartMs() >= atMs) n++;
        for (AudioClip a : timeline.getAudioClips()) if (a.getOffsetMs() >= atMs) n++;
        for (Clip c : timeline.getOverlayClips()) if (c.getOverlayStartMs() >= atMs) n++;
        for (AdjustmentLayer l : timeline.getAdjustmentLayers()) if (l.getStartMs() >= atMs) n++;
        for (WaveformOverlayInstance w : timeline.getWaveformOverlays()) if (w.getStartMs() >= atMs) n++;
        return new Summary(atMs, timeline.getVideoTrackDurationMs() - trackBeforeMs, n);
    }

    /**
     * Whether the check follows the edit that closed a bracket, by the editor's name for it ("trim", "speed", ...):
     * the person's own edits that can change the length. Never Undo or Redo (they put a state back, answer included),
     * a correction the app makes itself, or a declined or reverted edit. A transition joins this list when choice A
     * makes it overlap the clips (today it does not change the editor's length).
     */
    public static boolean asksAfter(String where) {
        if (where == null) return false;
        switch (where) {
            case "trim": case "speed": case "loopExtend": case "loopResize": case "loopMode":
            case "delete": case "deleteLinkedPair": case "silenceCuts":
                return true;
            default:
                return false;
        }
    }

    /**
     * Where an edit changed the clips' track, from their starts before and after it ({@link Timeline#captureClipStarts}):
     * the earliest old start of a clip that moved or went. When none did (only the last clip changed), the old end of
     * the clips: what changes is what lies past them.
     */
    public static long pointOf(@NonNull Map<String, Long> before, @NonNull Map<String, Long> after, long trackBeforeMs) {
        long point = Long.MAX_VALUE;
        for (Map.Entry<String, Long> e : before.entrySet()) {
            Long now = after.get(e.getKey());
            if (now == null || now.longValue() != e.getValue().longValue()) point = Math.min(point, e.getValue());
        }
        return point == Long.MAX_VALUE ? trackBeforeMs : point;
    }

    /**
     * Closes the edit's structural bracket with this edit's answer: MOVE ripples everything after the point, LEAVE
     * leaves it (the timeline's "gap" behaviour), ASK keeps the project's own setting. The project's setting is put
     * back afterwards whatever happens: the answer is for this edit.
     */
    @NonNull
    public static Timeline.AnchorShiftResult finish(@NonNull Timeline timeline, @NonNull Map<String, Long> beforeStarts,
                                                    @NonNull Choice choice) {
        String mode = timeline.getRippleMode();
        try {
            if (choice == Choice.MOVE) timeline.setRippleMode("ripple");
            else if (choice == Choice.LEAVE) timeline.setRippleMode("gap");
            return timeline.endStructural(beforeStarts);
        } finally {
            timeline.setRippleMode(mode);
        }
    }
}
