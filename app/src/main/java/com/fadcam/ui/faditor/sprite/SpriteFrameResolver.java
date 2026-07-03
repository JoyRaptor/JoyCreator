package com.fadcam.ui.faditor.sprite;

import androidx.annotation.NonNull;

/**
 * THE single source of truth for "which cell shows at time T"
 * (PLAN_SPRITE_ANIMATION §Data model). Preview, export, the timeline lane, and AI
 * validation all call {@link #resolveCellAt} — no other code may compute a cell
 * index, which makes preview/export divergence impossible by construction.
 *
 * <p>Pure static function of its inputs: no state, no side effects, no clocks.</p>
 */
public final class SpriteFrameResolver {

    private SpriteFrameResolver() {}

    /** Returned when nothing can be shown (empty frame track / no valid cell). */
    public static final int NO_CELL = -1;

    /**
     * Resolve the cell index visible at absolute timeline time {@code timelineMs}.
     *
     * <p>Rules, in order:</p>
     * <ol>
     *   <li>Outside the item's time range → {@link #NO_CELL}.</li>
     *   <li>Empty frame track → {@link #NO_CELL} (callers may show cell 0 as an
     *       affordance in EDIT UI, but playback/export show nothing that wasn't
     *       authored).</li>
     *   <li>Before the first entry → the FIRST entry's content at its own start
     *       phase ("what you placed is what you see" — no blank lead-in).</li>
     *   <li>A direct-cell entry HOLDS its cell until the next entry.</li>
     *   <li>A preset entry starts at the entry's time and advances at the preset's
     *       fps (falling back to the sheet's fps), wrapping per the preset type
     *       (loop / pingpong / once-then-hold-last), until the next entry.</li>
     *   <li>After the LAST entry, {@code item.endBehavior} applies to that entry:
     *       "hold" freezes its final state; "loop"/"pingpong" keep a preset entry
     *       running (overriding a "once" preset's own stop), and for a direct-cell
     *       last entry simply keep showing the cell (a single cell loops to
     *       itself).</li>
     * </ol>
     *
     * <p>Disabled or out-of-range cell indices resolve to {@link #NO_CELL} rather
     * than clamping — a broken reference must be visible in QA, not silently
     * remapped.</p>
     */
    public static int resolveCellAt(@NonNull SpriteSheet sheet,
                                    @NonNull SpriteOverlayItem item,
                                    long timelineMs) {
        if (!item.isVisibleAt(timelineMs)) return NO_CELL;
        FrameTrack track = item.getFrameTrack();
        if (track.isEmpty()) return NO_CELL;

        long localMs = Math.max(0, item.toLocalMs(timelineMs));
        FrameTrack.Key active = track.atOrBefore(localMs);
        if (active == null) {
            // Before the first entry: show the first entry's content at phase 0.
            active = track.first();
            if (active == null) return NO_CELL;
            localMs = active.timeMs; // phase 0 of that entry
        }

        boolean isLast = track.after(active.timeMs) == null;

        if (active.presetId == null) {
            // Direct cell: holds until the next entry; endBehavior for a direct
            // cell has nothing to animate, so it keeps holding regardless.
            return validCellOrNone(sheet, active.cellIndex);
        }

        SpriteSheet.Preset preset = sheet.presetById(active.presetId);
        if (preset == null || preset.frames.isEmpty()) return NO_CELL;

        float fps = preset.fps > 0f ? preset.fps : sheet.getFps();
        if (fps <= 0f) fps = 1f;
        long elapsed = localMs - active.timeMs;
        long frameOrdinal = (long) Math.floor(elapsed * fps / 1000.0);

        String type = preset.type;
        if (isLast) {
            // endBehavior overrides the preset's own wrap after the LAST entry.
            if ("loop".equals(item.getEndBehavior())) type = "loop";
            else if ("pingpong".equals(item.getEndBehavior())) type = "pingpong";
            // "hold": keep the preset's own type; a "once" preset holds its final
            // frame via the once-branch below, a loop/pingpong preset keeps running
            // (holding a mid-animation freeze frame would look broken).
        }

        int n = preset.frames.size();
        int idx;
        switch (type) {
            case "once":
                idx = (int) Math.min(frameOrdinal, n - 1);
                break;
            case "pingpong": {
                if (n == 1) { idx = 0; break; }
                int period = 2 * (n - 1);
                int phase = (int) (frameOrdinal % period);
                idx = phase < n ? phase : period - phase;
                break;
            }
            case "loop":
            default:
                idx = (int) (frameOrdinal % n);
                break;
        }
        return validCellOrNone(sheet, preset.frames.get(idx));
    }

    private static int validCellOrNone(@NonNull SpriteSheet sheet, int cellIndex) {
        if (cellIndex < 0 || cellIndex >= sheet.cellCount()) return NO_CELL;
        SpriteSheet.Cell meta = sheet.cellAt(cellIndex);
        if (meta != null && !meta.enabled) return NO_CELL;
        return cellIndex;
    }
}
