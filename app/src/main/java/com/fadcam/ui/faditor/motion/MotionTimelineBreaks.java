package com.fadcam.ui.faditor.motion;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CutJumps;
import com.fadcam.ui.faditor.model.TimeMap;
import com.fadcam.ui.faditor.sprite.SpriteFrameResolver;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

/** Adapts the current Studio time and cell authorities to the shutter's continuity fences. */
public final class MotionTimelineBreaks {
    private MotionTimelineBreaks() {}

    /** Editor clock. Transition sides are evaluated separately, never as frame donors. */
    public static void addTimeline(MotionBreaks.Builder into, TimeMap timeMap) {
        int clips = timeMap.clipCount();
        for (int index = 0; index <= clips; index++) {
            long seam = timeMap.clipEditorStartMs(index);
            into.add(seam * 1000L);
            long blend = timeMap.seamMs(index - 1);
            if (blend > 0) {
                into.add((seam - blend) * 1000L);
                into.add((seam + blend) * 1000L);
            }
        }
        CutJumps cuts = timeMap.cuts();
        for (int index = 0; index < cuts.count(); index++) {
            into.exclude(cuts.startMs(index) * 1000L, cuts.endMs(index) * 1000L);
        }
    }

    /** Adds visible clip boundaries and any repeated source discontinuity within the window. */
    public static void addClip(MotionBreaks.Builder into, Clip clip, long clipStartMs,
                               long fromUs, long toUs) {
        long clipEnd = clipStartMs + clip.getVisualDurationMs();
        into.add(clipStartMs * 1000L).add(clipEnd * 1000L);
        long period = clip.getTrimmedDurationMs();
        if (!clip.hasLoopExtension() || !clip.isLoopModeLooping() || period <= 0) return;

        long base = clipStartMs + clip.getLoopBeforeMs();
        long start = Math.max(clipStartMs + 1, Math.floorDiv(fromUs, 1000L));
        long end = Math.min(clipEnd - 1, Math.floorDiv(toUs, 1000L));
        long firstBoundary = start + Math.floorMod(base - start, period);
        double adjacentStep = Math.ceil(clip.getSpeedMultiplier()) + 1.0;
        for (long boundary = firstBoundary; boundary <= end; boundary += period) {
            long previous = clip.mapToSourceMs(boundary - clipStartMs - 1);
            long current = clip.mapToSourceMs(boundary - clipStartMs);
            // A ping-pong turn may share an endpoint. Let that path reverse continuously.
            if (previous < 0 || current < 0) continue;
            boolean jumps = clip.getLoopMode() == Clip.LOOP_MODE_NORMAL
                    ? current != previous : Math.abs((double) current - previous) > adjacentStep;
            if (jumps) {
                into.add(boundary * 1000L);
            }
        }
    }

    /**
     * Query from the earlier of shutter-open and centre to the later of shutter-close and centre.
     * The current renderer switches cells at whole milliseconds.
     * Resolve each threshold with the playback resolver, including transitions to/from no cell.
     */
    public static void addSprite(MotionBreaks.Builder into, SpriteSheet sheet, SpriteOverlayItem item,
                                 long fromUs, long toUs) {
        long start = Math.max(item.getStartMs(), Math.floorDiv(fromUs, 1000L));
        long end = Math.min(item.getEndMs(), Math.floorDiv(toUs, 1000L));
        into.add(item.getStartMs() * 1000L);
        if (item.getEndMs() != Long.MAX_VALUE) into.add(item.getEndMs() * 1000L);
        if (end < start) return;
        int previous = SpriteFrameResolver.resolveCellAt(sheet, item, start);
        for (long ms = start + 1; ms <= end; ms++) {
            int cell = SpriteFrameResolver.resolveCellAt(sheet, item, ms);
            if (cell != previous) into.add(ms * 1000L);
            previous = cell;
        }
    }
}
