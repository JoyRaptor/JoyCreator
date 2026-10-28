package com.fadcam.ui.faditor.sprite;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Joy Brush "Send to Studio" into an EXISTING project: where the sent board lands on that project's timeline, and which id
 * its sheet keeps. Pure rules (no Android), so the harness can check them.
 *
 * A board sent into a new project is the whole project. Sent into one that already has a video or a song it is one more
 * layer on top: it runs over the whole project and loops, at half the canvas and centred, so it is seen at once and one
 * drag away from where the person wants it. An empty project (nothing on the timeline yet) behaves like a new one:
 * the animation plays once and holds.
 */
public final class BoardAttachPlan {
    private BoardAttachPlan() {}

    /** Share of the canvas the sent animation covers when it lands on top of other things. */
    public static final float OVER_SIZE = 0.5f;

    public static final class Placement {
        public final long startMs;
        public final long endMs;
        /** {@code "loop"} or {@code "hold"}, as {@link SpriteOverlayItem#setEndBehavior} takes it. */
        public final String endBehavior;
        public final float sizeFraction;

        Placement(long startMs, long endMs, String endBehavior, float sizeFraction) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.endBehavior = endBehavior;
            this.sizeFraction = sizeFraction;
        }
    }

    /**
     * @param projectMs how long the project already runs (its timeline's total), 0 or less when it is empty
     * @param animMs    how long one pass of the sent animation takes (at least 1)
     */
    public static Placement onExisting(long projectMs, long animMs) {
        long cycle = Math.max(1L, animMs);
        if (projectMs <= 0L) return new Placement(0L, cycle, "hold", 1f);
        // A project shorter than one pass still shows the whole pass: the overlay is never cut below the animation.
        return new Placement(0L, Math.max(projectMs, cycle), "loop", OVER_SIZE);
    }

    /**
     * The id the sheet keeps in the project. A board sent twice into the same project must not share a sheet id (the
     * second would replace the first's pictures), so a taken id is swapped for a fresh one.
     */
    public static String sheetIdFor(String wanted, Set<String> taken, Supplier<String> fresh) {
        if (wanted == null || wanted.isEmpty() || taken.contains(wanted)) {
            String id = fresh.get();
            while (taken.contains(id)) id = fresh.get();
            return id;
        }
        return wanted;
    }
}
