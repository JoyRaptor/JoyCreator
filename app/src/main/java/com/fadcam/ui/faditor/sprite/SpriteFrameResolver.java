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
     *       (loop / pingpong / once-then-hold-last), until the next entry. When the
     *       preset carries per-frame WEIGHTS (SPEC_IMAGE_SEQUENCE §2) a frame holds
     *       for that many fps ticks instead of one; an unweighted preset is the
     *       identical computation, so image sequences and sprite sheets share this
     *       evaluator rather than each having their own.</li>
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

        // clampFps, not a bare > 0 check: frameChangesIn (the tape) clamps, and if the two
        // entry points of this class disagree about their input then the timeline draws marks
        // on a different cadence than the video plays — a breach of the one-evaluator rule from
        // inside the evaluator itself. Only reachable via hand-edited JSON with preset.fps above
        // MAX_FPS, which is exactly the sort of thing that gets found late.
        float fps = SequenceTiming.clampFps(preset.fps > 0f ? preset.fps : sheet.getFps());
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

        // frameOrdinal is a TICK, not a frame index. With no weights a tick IS a frame and every
        // branch below reduces to the arithmetic this method has always done; with weights, a
        // frame simply occupies several consecutive ticks. That is the whole of
        // SPEC_IMAGE_SEQUENCE's timing model, and it is why there is no second evaluator.
        int n = preset.frames.size();
        java.util.List<Integer> w = preset.weights;
        int total = SequenceTiming.totalWeight(w, n);
        int idx;
        switch (type) {
            case "once":
                idx = SequenceTiming.indexAtTick(w, n, Math.min(frameOrdinal, total - 1));
                break;
            case "pingpong": {
                if (n == 1) { idx = 0; break; }
                long period = SequenceTiming.pingPongPeriod(w, n);
                idx = SequenceTiming.pingPongIndexAtTick(w, n, frameOrdinal % period);
                break;
            }
            case "loop":
            default:
                idx = SequenceTiming.indexAtTick(w, n, frameOrdinal % total);
                break;
        }
        return validCellOrNone(sheet, preset.frames.get(idx));
    }

    /**
     * One moment where the picture CHANGES — what the timeline tape draws a thumbnail at
     * (SPEC_IMAGE_SEQUENCE §4) and what the dope sheet lays out.
     */
    public static final class FrameChange {
        /** Item-LOCAL ms at which this frame comes in. */
        public final long localMs;
        /** The cell that comes in, already validated. */
        public final int cellIndex;
        /**
         * True when this is a repeat — a second or later pass of a loop/ping-pong cycle. §4 draws
         * these slightly darker so a looped region reads as "this is a repeat" rather than as
         * more authored content.
         */
        public final boolean repeat;

        FrameChange(long localMs, int cellIndex, boolean repeat) {
            this.localMs = localMs;
            this.cellIndex = cellIndex;
            this.repeat = repeat;
        }
    }

    /**
     * Every frame change in {@code [fromLocalMs, toLocalMs]}, in time order.
     *
     * <p><b>Why this lives here.</b> The tape's whole value is that it shows the truth about
     * timing; deriving change times anywhere else would let the picture on the timeline drift
     * from the picture on the video, which is worse than no tape at all. So the same class that
     * answers "which cell at time t" also answers "when does the cell change" — and both walk the
     * same weights.</p>
     *
     * @param maxCount hard cap on returned entries. A 600-frame sequence looping over a long
     *                 timeline is unbounded in principle; the tape only needs what fits on
     *                 screen, and an uncapped walk here would be a frame-time hang.
     */
    @NonNull
    public static java.util.List<FrameChange> frameChangesIn(@NonNull SpriteSheet sheet,
                                                             @NonNull SpriteOverlayItem item,
                                                             long fromLocalMs, long toLocalMs,
                                                             int maxCount) {
        java.util.List<FrameChange> out = new java.util.ArrayList<>();
        FrameTrack track = item.getFrameTrack();
        if (track.isEmpty() || maxCount <= 0) return out;

        // The item's own end in local ms. An open-ended item is bounded by the query window, so
        // this never walks forever even when endMs is MAX_VALUE.
        long itemEndLocal = item.getEndMs() == Long.MAX_VALUE
                ? toLocalMs : item.getEndMs() - item.getStartMs();

        java.util.List<FrameTrack.Key> keys = track.keys();
        for (int ki = 0; ki < keys.size() && out.size() < maxCount; ki++) {
            FrameTrack.Key k = keys.get(ki);
            long spanEnd = ki + 1 < keys.size() ? keys.get(ki + 1).timeMs : itemEndLocal;
            if (spanEnd <= k.timeMs) continue;
            if (k.timeMs > toLocalMs) break;

            if (k.presetId == null) {
                int cell = validCellOrNone(sheet, k.cellIndex);
                if (cell != NO_CELL && k.timeMs >= fromLocalMs && k.timeMs <= toLocalMs) {
                    out.add(new FrameChange(k.timeMs, cell, false));
                }
                continue;
            }

            SpriteSheet.Preset preset = sheet.presetById(k.presetId);
            if (preset == null || preset.frames.isEmpty()) continue;
            float fps = preset.fps > 0f ? preset.fps : sheet.getFps();
            fps = SequenceTiming.clampFps(fps);

            String type = preset.type;
            boolean isLast = ki + 1 >= keys.size();
            if (isLast) {
                if ("loop".equals(item.getEndBehavior())) type = "loop";
                else if ("pingpong".equals(item.getEndBehavior())) type = "pingpong";
            }
            boolean once = "once".equals(type);

            int n = preset.frames.size();
            int[][] cycle = SequenceTiming.cycleOrder(preset.weights, n, type);
            int[] order = cycle[0], weights = cycle[1];
            long cycleTicks = 0;
            for (int w : weights) cycleTicks += w;
            if (cycleTicks <= 0) continue;

            long tick = 0;
            int pass = 0;
            walk:
            while (true) {
                for (int i = 0; i < order.length; i++) {
                    long tMs = k.timeMs + Math.round(tick * 1000.0 / fps);
                    if (tMs >= spanEnd || tMs > toLocalMs) break walk;
                    if (tMs >= fromLocalMs) {
                        int cell = validCellOrNone(sheet, preset.frames.get(order[i]));
                        if (cell != NO_CELL) {
                            out.add(new FrameChange(tMs, cell, pass > 0));
                            if (out.size() >= maxCount) break walk;
                        }
                    }
                    tick += weights[i];
                }
                pass++;
                // "once" shows its run and then HOLDS the last frame — no further changes, so
                // stopping here is what keeps a non-looping sequence from drawing phantom marks
                // across the rest of its span.
                if (once) break;
            }
        }
        return out;
    }

    private static int validCellOrNone(@NonNull SpriteSheet sheet, int cellIndex) {
        if (cellIndex < 0 || cellIndex >= sheet.cellCount()) return NO_CELL;
        SpriteSheet.Cell meta = sheet.cellAt(cellIndex);
        if (meta != null && !meta.enabled) return NO_CELL;
        return cellIndex;
    }
}
