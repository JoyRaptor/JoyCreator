package com.fadcam.ui.faditor.sprite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * SPEC_IMAGE_SEQUENCE §6 — turn "this continues" into a CONCRETE length, every time anything
 * changes, and say so when a neighbour cut it short.
 *
 * <p><b>Why this exists rather than a genuinely open-ended duration.</b> The user asked for an
 * object that runs <i>"until it hits some other layer in that lane or the end of the project"</i>.
 * The spec's own analysis of that is the design here: a duration that depends on neighbours means
 * adding an unrelated object silently shortens this one, deleting one silently extends it, and
 * undo must restore a length nobody authored. This codebase has a ledger full of "the app moved
 * my clip by itself" incidents and they were expensive to find.</p>
 *
 * <p>So: <b>keep the affordance, resolve the length.</b> {@code endMs} is always a real number.
 * When the number came from a neighbour rather than from the sequence's own run, the item is
 * flagged {@link SpriteOverlayItem#isClippedByNeighbour()} and the tape draws that — the "never
 * silently" half of the requirement, which is the half that makes the feature safe.</p>
 *
 * <p>Pure and Android-free: it takes the lane's items and the project length, and returns whether
 * anything moved.</p>
 */
public final class OpenEndResolver {

    private OpenEndResolver() {}

    /**
     * Resolve every {@code continuesUntilBlocked} item in {@code laneItems}.
     *
     * @param laneItems  every sprite item sharing one lane (order irrelevant)
     * @param sheetOf    sheet lookup, for the natural run length
     * @param projectEndMs hard stop when no neighbour blocks
     * @return true when any item's end actually changed — the caller's cue to re-sync and save.
     *         Returning false on a no-op is what keeps this safe to call on every edit.
     */
    public static boolean resolve(@NonNull List<SpriteOverlayItem> laneItems,
                                  @NonNull SheetLookup sheetOf,
                                  long projectEndMs) {
        boolean changed = false;
        for (SpriteOverlayItem item : laneItems) {
            if (item == null || !item.isContinuesUntilBlocked()) continue;

            long start = item.getStartMs();
            // The nearest later item in this lane is the blocker. Ties and overlaps resolve to
            // "the earliest thing that starts after us", which is the only reading that cannot
            // produce a negative length.
            long blocker = Long.MAX_VALUE;
            for (SpriteOverlayItem other : laneItems) {
                if (other == null || other == item) continue;
                long os = other.getStartMs();
                if (os > start && os < blocker) blocker = os;
            }
            long hardStop = Math.min(blocker == Long.MAX_VALUE ? projectEndMs : blocker,
                    projectEndMs);
            if (hardStop <= start) hardStop = start + MIN_SPAN_MS;

            long natural = naturalRunMs(item, sheetOf);
            long resolved;
            boolean clipped;
            if (natural <= 0) {
                resolved = hardStop;
                clipped = blocker != Long.MAX_VALUE;
            } else if (start + natural <= hardStop) {
                // The run fits. "Continues" means keep going, so fill the room by repeating —
                // but the LENGTH is still concrete, which is the whole point.
                resolved = hardStop;
                clipped = false;
            } else {
                resolved = hardStop;
                // Only a NEIGHBOUR counts as clipping. Running out of project is not something
                // a neighbour did, and saying so names an object that does not exist — the
                // §6 affordance whose whole job is honesty would be lying about the reason.
                clipped = blocker != Long.MAX_VALUE;
            }

            if (item.getEndMs() != resolved) {
                item.setTimeRange(start, resolved);
                changed = true;
            }
            if (item.isClippedByNeighbour() != clipped) {
                item.setClippedByNeighbour(clipped);
                changed = true;
            }
        }
        return changed;
    }

    /** A continuing object still needs SOME length, even squeezed against its neighbour. */
    private static final long MIN_SPAN_MS = 200;

    /** One forward pass of the item's sequence at its cadence, or 0 when unknowable. */
    private static long naturalRunMs(@NonNull SpriteOverlayItem item,
                                     @NonNull SheetLookup sheetOf) {
        SpriteSheet sheet = sheetOf.get(item.getSheetId());
        if (sheet == null || !sheet.isSequence()) return 0;
        SpriteSheet.Preset p = sheet.sequencePreset();
        if (p == null || p.frames.isEmpty()) return 0;
        float fps = SequenceTiming.clampFps(p.fps > 0f ? p.fps : sheet.getFps());
        return SequenceTiming.totalMsForFps(p.weights, p.frames.size(), fps);
    }

    /** Sheet lookup, so this stays free of project/storage imports. */
    public interface SheetLookup {
        @Nullable SpriteSheet get(@NonNull String sheetId);
    }
}
