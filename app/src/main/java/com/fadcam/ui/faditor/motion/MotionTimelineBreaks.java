package com.fadcam.ui.faditor.motion;

import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CutJumps;
import com.fadcam.ui.faditor.model.SpineTransform;
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

    /** Fences a spine transform to its source piece and forbids sampling across held steps. */
    public static MotionBreaks spine(Clip clip, long clipStartUs, long pieceStartUs, long pieceEndUs) {
        var breaks = new MotionBreaks.Builder().add(pieceStartUs).add(pieceEndUs);
        var transform = clip.getSpineTransform();
        for (String property : SpineTransform.tracks()) {
            var track = transform.get(property);
            if (track != null) breaks.addSteps(track, clipStartUs);
        }
        return breaks.build();
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

    public static int supportedCell(SpriteSheet sheet, SpriteOverlayItem item, long timeMs) {
        int cell = SpriteFrameResolver.resolveCellAt(sheet, item, timeMs);
        if (cell == SpriteFrameResolver.NO_CELL) return cell;
        var alignment = sheet.cellTransform(cell);
        // Aligned art can extend beyond the cell texture.
        return alignment == null || alignment.isIdentity() ? cell : SpriteFrameResolver.NO_CELL;
    }

    public record CellWindow(int cell, long startUs, long endUs) {}

    /** Exact holding interval for authored cells. Preset sequences use their regular route. */
    @Nullable
    public static CellWindow directCellWindow(SpriteSheet sheet, SpriteOverlayItem item, long timeMs) {
        int cell = supportedCell(sheet, item, timeMs);
        if (cell == SpriteFrameResolver.NO_CELL) return null;
        long itemStart = item.getStartMs();
        long start = itemStart;
        long end = item.getEndMs();
        int previous = SpriteFrameResolver.resolveCellAt(sheet, item, start);
        for (var key : item.getFrameTrack().keys()) {
            if (key.presetId != null) return null;
            long edge = itemStart + key.timeMs;
            if (edge >= end) break;
            int next = SpriteFrameResolver.resolveCellAt(sheet, item, edge);
            if (next == previous) continue;
            if (edge > timeMs) {
                end = edge;
                break;
            }
            start = edge;
            previous = next;
        }
        return new CellWindow(cell, start * 1000L,
                end == Long.MAX_VALUE ? end : end * 1000L);
    }
}
