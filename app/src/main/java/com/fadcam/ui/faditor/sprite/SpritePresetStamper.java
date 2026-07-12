package com.fadcam.ui.faditor.sprite;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Fast-follow A (PLAN_SPRITE_ANIMATION §Fast-follow A): PURE key-pattern
 * generators for the dope-sheet's preset chips ("Cycle all cells @ sheet fps",
 * "Ping-pong cells", "Hold current"). No Android deps — JVM-harness testable.
 *
 * <p>Each generator returns a brand-new, fully-sorted key list anchored at
 * {@code startLocalMs} (item-local ms, the playhead at stamp time); the caller
 * (FaditorEditorActivity) swaps the WHOLE {@code FrameTrack} for the result —
 * "each stamp = whole-track before/after swap, ONE undo step" per the plan.
 * This class never touches a live {@link SpriteOverlayItem}; it only reads
 * {@link SpriteSheet} geometry (cell count, enabled flags, fps).</p>
 */
public final class SpritePresetStamper {

    private SpritePresetStamper() {}

    public enum Kind { CYCLE_ALL, PINGPONG, HOLD_CURRENT }

    /**
     * Generate a replacement key list for {@code kind}, anchored at
     * {@code startLocalMs}. {@code currentCell} is only used by
     * {@link Kind#HOLD_CURRENT} (pass the resolver's current cell, or -1 to
     * fall back to the first enabled cell).
     */
    @NonNull
    public static List<FrameTrack.Key> generate(@NonNull Kind kind, @NonNull SpriteSheet sheet,
                                                  long startLocalMs, int currentCell) {
        long start = Math.max(0, startLocalMs);
        switch (kind) {
            case HOLD_CURRENT:
                return holdCurrent(sheet, start, currentCell);
            case PINGPONG:
                return pingpong(sheet, start);
            case CYCLE_ALL:
            default:
                return cycleAll(sheet, start);
        }
    }

    @NonNull
    private static List<FrameTrack.Key> holdCurrent(@NonNull SpriteSheet sheet, long start,
                                                      int currentCell) {
        List<FrameTrack.Key> out = new ArrayList<>();
        int cell = currentCell >= 0 && currentCell < sheet.cellCount()
                ? currentCell : firstEnabledCell(sheet);
        out.add(FrameTrack.Key.ofCell(start, cell));
        return out;
    }

    @NonNull
    private static List<FrameTrack.Key> cycleAll(@NonNull SpriteSheet sheet, long start) {
        List<FrameTrack.Key> out = new ArrayList<>();
        List<Integer> cells = enabledCells(sheet);
        if (cells.isEmpty()) return out;
        long stepMs = stepMsFor(sheet);
        long t = start;
        for (int c : cells) {
            out.add(FrameTrack.Key.ofCell(t, c));
            t += stepMs;
        }
        return out;
    }

    @NonNull
    private static List<FrameTrack.Key> pingpong(@NonNull SpriteSheet sheet, long start) {
        List<FrameTrack.Key> out = new ArrayList<>();
        List<Integer> cells = enabledCells(sheet);
        if (cells.isEmpty()) return out;
        long stepMs = stepMsFor(sheet);
        long t = start;
        for (int c : cells) {
            out.add(FrameTrack.Key.ofCell(t, c));
            t += stepMs;
        }
        // Return leg, excluding both ends (they'd duplicate the turn frames).
        for (int i = cells.size() - 2; i >= 1; i--) {
            out.add(FrameTrack.Key.ofCell(t, cells.get(i)));
            t += stepMs;
        }
        return out;
    }

    private static long stepMsFor(@NonNull SpriteSheet sheet) {
        float fps = sheet.getFps() > 0f ? sheet.getFps() : 8f;
        return Math.max(1L, Math.round(1000.0 / fps));
    }

    @NonNull
    private static List<Integer> enabledCells(@NonNull SpriteSheet sheet) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < sheet.cellCount(); i++) {
            SpriteSheet.Cell meta = sheet.cellAt(i);
            if (meta == null || meta.enabled) out.add(i);
        }
        return out;
    }

    private static int firstEnabledCell(@NonNull SpriteSheet sheet) {
        List<Integer> cells = enabledCells(sheet);
        return cells.isEmpty() ? 0 : cells.get(0);
    }
}
