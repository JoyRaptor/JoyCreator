package com.fadcam.ui.faditor.layers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * Owns ALL gesture logic for floating items on the M6 multi-row Track UI: move
 * (drag along the row → absolute time), trim (edge handles on the selected item),
 * and delete (long-press, mirroring the existing overlay/audio delete affordance)
 * (PLAN Part 6 §6.3, Part 7 row M7 — extract-on-touch companion to
 * {@link LayerRowRenderer}).
 *
 * <p><b>The one architectural rule (PLAN, binding):</b> {@link Track}/{@link TimedItem}
 * are EPHEMERAL views rebuilt from the flat lists on every access (see {@code Timeline}'s
 * class doc). This controller NEVER stores gesture state on a {@code Track}/{@code TimedItem}
 * — it mutates the PERSISTENT PAYLOAD objects directly:
 * {@link TextOverlayItem#setTimeRange(long, long)} (move preserves duration; trim moves
 * one edge independently) and {@link AudioClip#setOffsetMs(long)} /
 * {@link AudioClip#setInPointMs(long)} / {@link AudioClip#setOutPointMs(long)} (mirrors
 * the EXISTING audio-lane trim semantics in {@code EditorTimelineView#doAudioTrimDrag} —
 * trim moves {@code inPointMs}/{@code outPointMs} within the source, it does NOT touch
 * {@code offsetMs}). The next {@code Track}/{@code TimedItem} rebuild reflects the
 * mutation automatically — this class never needs to write one.</p>
 *
 * <p><b>Master items are structurally unreachable here.</b> {@code Timeline#getLayers()}
 * / {@code getAudioTracks()} (M5) only ever produce {@code TEXT} and {@code AUDIO}
 * tracks; the {@code MASTER} track is a separate accessor
 * ({@code EditorTimelineView#getM6RowsTopPx} + {@code LayerRowRenderer#layout} only ever
 * receive {@code layerTracks}/{@code audioLayerTracks}, never the master). There is no
 * code path from a row-body touch here into a master {@code Clip}.</p>
 *
 * <p><b>Undo:</b> exactly one undo step per completed gesture. This class snapshots the
 * payload's before-state at gesture start (mirroring
 * {@code FaditorEditorActivity#onOverlayDragStart}/{@code EditActions.AudioTrimAction}),
 * applies live mutations during the drag for immediate visual feedback (mirroring
 * {@code doAudioTrimDrag}/{@code doLayerDrag}), and reports the finished gesture via
 * {@link Callback#onGestureFinished} with the before-snapshot so the activity can record
 * a single {@code EditActions.OverlayTransformAction} / {@code EditActions.AudioTrimAction}
 * / {@code EditActions.LambdaAction} — the SAME undo classes M6-era code already uses, not
 * new parallel ones. Delete reports via {@link Callback#onItemDeleteRequested} so the
 * activity can reuse its existing confirmation-dialog pattern
 * ({@code onOverlayLayerLongPressed}/{@code deleteSelectedAudioClip}).</p>
 */
public final class LayerGestureController {

    /**
     * Inverse geometry bridge: content-space x → absolute timeline ms. (The forward
     * direction reuses {@link LayerRowRenderer.TimeToX} directly rather than
     * duplicating an identical functional interface.)
     */
    public interface XToTime { long map(float x); }

    /** Reports gesture lifecycle events back to the owning activity for undo + refresh. */
    public interface Callback {
        /**
         * A move/trim gesture on {@code item} just finished with a real change (no-op
         * guarded — never called if nothing moved). {@code payload} is the same object
         * as {@code item}'s wrapped {@code TextOverlayItem}/{@code AudioClip}; passed
         * separately only for a stable type at the call site.
         */
        void onGestureFinished(@NonNull TimedItem item, @NonNull GestureKind kind);

        /** Live mutation happened (not yet finished) — refresh preview/timeline now. */
        void onGestureLive(@NonNull TimedItem item);

        /** Long-press on an item's body → the activity should offer to delete it. */
        void onItemDeleteRequested(@NonNull Track track, @NonNull TimedItem item);

        /**
         * A MOVE gesture on {@code item} ended with the finger over a DIFFERENT row than
         * where the drag started (PLAN Part 7 row M10 scope 1, drag-between-layers).
         * {@code fromTrack}/{@code toTrack} are the source/destination Track VIEWS at
         * gesture-start/end (both EPHEMERAL — read their {@code getId()} before this
         * call returns, don't hold the objects). The activity is responsible for the
         * persistent mutation (setting the item's {@code layerId} to {@code toTrack.getId()})
         * and recording exactly one undo step alongside whatever
         * {@link #onGestureFinished} already recorded for the time-position change —
         * see the M10 build report for why this is a SEPARATE callback rather than
         * folded into {@code onGestureFinished} (independent no-op guards: a drag can
         * change track without changing time, or vice versa).
         */
        void onItemMovedToTrack(@NonNull TimedItem item, @NonNull Track fromTrack, @NonNull Track toTrack);

        /**
         * A MOVE gesture on {@code item} ended with the finger over the "new layer"
         * drop zone below the last row (PLAN Part 7 row M10 scope 2, drop-to-new-layer).
         * The activity creates a new persistent track (matching {@code fromTrack}'s
         * band/kind — see {@code LayerRowRenderer#isFloatingBandRow}) and reassigns the
         * item's {@code layerId} to it, as one undo step.
         */
        void onItemDroppedOnNewLayer(@NonNull TimedItem item, @NonNull Track fromTrack);
    }

    public enum GestureKind { MOVE, TRIM_LEFT, TRIM_RIGHT }

    private static final long MIN_TEXT_DURATION_MS = 250;
    private static final long AUDIO_MIN_TRIM_GAP_MS = 500;
    private static final long LONG_PRESS_MS = 500;

    private final LayerRowRenderer rowRenderer;
    private final Callback callback;

    // ── Active gesture state (never stored on Track/TimedItem — PLAN's one rule) ──
    private boolean active = false;
    private GestureKind activeKind = null;
    private Track activeTrack;
    private TimedItem activeItem;
    private float dragStartX;
    private long dragStartTimelineMs;   // item's timelineStartMs at gesture start
    private long dragStartDurationMs;   // item's duration at gesture start (for MOVE)
    private long dragStartTrimInMs, dragStartTrimOutMs; // audio-only, for TRIM
    private long dragStartTextStartMs, dragStartTextEndMs; // text-only, for TRIM (preserve open-ended end)
    private boolean movedDuringGesture = false;

    /** Snapshot captured at gesture-start, handed back on finish so the caller can undo. */
    @Nullable private TextOverlayItem.TransformSnapshot textBeforeSnapshot;
    private long audioBeforeOffsetMs, audioBeforeInMs, audioBeforeOutMs;

    /** Item id selected for trim-handle exposure (mirrors the audio/overlay "selected → handles show" convention). */
    @Nullable private String selectedItemId;

    // ── M10: cross-row drag-target tracking (MOVE gestures only) ───────────────
    /** Row the active MOVE gesture is currently hovering, or null (own row / no valid target). */
    @Nullable private Track hoverTargetTrack;
    /** True once the finger has moved over the "new layer" drop zone during this MOVE. */
    private boolean hoverNewLayerZone;
    /** topPx/y of the last onRowBodyMove call, needed by onRowBodyUp's zone re-check. */
    private float lastMoveY, lastMoveTopPx;

    /** True once a long-press has fired for the current touch-down (suppresses move-drag). */
    private boolean longPressFired = false;
    private final android.os.Handler longPressHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable longPressRunnable;

    public LayerGestureController(@NonNull LayerRowRenderer rowRenderer, @NonNull Callback callback) {
        this.rowRenderer = rowRenderer;
        this.callback = callback;
    }

    /** Currently-selected item id (drives trim-handle visibility in the renderer/hit-test). */
    @Nullable
    public String getSelectedItemId() { return selectedItemId; }

    public void clearSelection() { selectedItemId = null; }

    /**
     * DOWN on a row body (already confirmed within the row region and not a header hit
     * by the caller). Returns true if a gesture was armed (caller should consume the
     * touch and suppress its own segment/audio hit-testing for this gesture).
     */
    public boolean onRowBodyDown(float x, float y, float topPx, long totalMs,
                                  @NonNull LayerRowRenderer.TimeToX timeToX) {
        cancelLongPress();
        LayerRowRenderer.ItemHit hit = rowRenderer.hitTestItem(x, y, topPx, totalMs, timeToX, selectedItemId);
        if (hit == null) {
            // Tap on empty row space (not on any item) — clear selection, consume the
            // touch (matches the M6 "consume, don't fall through" contract), no gesture.
            selectedItemId = null;
            active = false;
            return false;
        }
        activeTrack = hit.track;
        activeItem = hit.item;
        dragStartX = x;
        movedDuringGesture = false;
        longPressFired = false;
        hoverTargetTrack = null;
        hoverNewLayerZone = false;
        rowRenderer.setDragTargetTrackId(null);

        if (hit.zone == LayerRowRenderer.ItemZone.LEFT_HANDLE) {
            armTrim(hit.item, true);
        } else if (hit.zone == LayerRowRenderer.ItemZone.RIGHT_HANDLE) {
            armTrim(hit.item, false);
        } else {
            // Body tap: select it (exposes trim handles next touch) and arm a
            // potential MOVE drag; a long-press instead offers delete (mirrors the
            // existing overlay/audio long-press-to-delete affordance).
            selectedItemId = hit.item.getId();
            armMove(hit.item);
            longPressRunnable = () -> {
                if (!active || movedDuringGesture) return;
                longPressFired = true;
                callback.onItemDeleteRequested(activeTrack, activeItem);
            };
            longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_MS);
        }
        active = true;
        activeKind = (hit.zone == LayerRowRenderer.ItemZone.LEFT_HANDLE) ? GestureKind.TRIM_LEFT
                : (hit.zone == LayerRowRenderer.ItemZone.RIGHT_HANDLE) ? GestureKind.TRIM_RIGHT
                : GestureKind.MOVE;
        return true;
    }

    private void armMove(@NonNull TimedItem item) {
        dragStartTimelineMs = item.getTimelineStartMs();
        dragStartDurationMs = 0; // recomputed lazily by dragStartDurationMsForMove
        moveGrabOffsetMs = -1;   // set on the first onRowBodyMove call (needs xToTime)
        if (item.getTextOverlay() != null) {
            textBeforeSnapshot = item.getTextOverlay().snapshotTransform();
        } else if (item.getAudioClip() != null) {
            AudioClip ac = item.getAudioClip();
            audioBeforeOffsetMs = ac.getOffsetMs();
            audioBeforeInMs = ac.getInPointMs();
            audioBeforeOutMs = ac.getOutPointMs();
        }
    }

    private void armTrim(@NonNull TimedItem item, boolean left) {
        if (item.getTextOverlay() != null) {
            TextOverlayItem o = item.getTextOverlay();
            textBeforeSnapshot = o.snapshotTransform();
            dragStartTextStartMs = Math.max(0, o.getStartMs());
            dragStartTextEndMs = o.getEndMs();
        } else if (item.getAudioClip() != null) {
            AudioClip ac = item.getAudioClip();
            audioBeforeOffsetMs = ac.getOffsetMs();
            audioBeforeInMs = ac.getInPointMs();
            audioBeforeOutMs = ac.getOutPointMs();
            dragStartTrimInMs = ac.getInPointMs();
            dragStartTrimOutMs = ac.getOutPointMs();
        }
    }

    /**
     * MOVE (drag while ACTION_MOVE). Suppresses the pending long-press once the finger
     * has moved (mirrors {@code EditorTimelineView#onMove}'s touch-slop cancellation).
     *
     * @param topPx the row region's current top (screen) y, needed by MOVE gestures to
     *              resolve which row/new-layer-zone the finger is currently over (M10;
     *              PLAN Part 7 row M10 scope 1). Ignored for TRIM (no cross-row concept).
     */
    public void onRowBodyMove(float x, float y, float topPx, long totalMs, @NonNull XToTime xToTime) {
        if (!active || activeItem == null) return;
        if (Math.abs(x - dragStartX) > 4f) {
            cancelLongPress();
            movedDuringGesture = true;
        }
        if (!movedDuringGesture) return;

        long t = xToTime.map(x);
        if (activeKind == GestureKind.MOVE && moveGrabOffsetMs < 0) {
            // First move of a MOVE gesture: capture how far into the item the finger
            // grabbed it, so the item tracks the finger instead of snapping its start
            // to the touch point.
            moveGrabOffsetMs = t - dragStartTimelineMs;
        }
        switch (activeKind) {
            case MOVE:
                applyMove(t, totalMs);
                updateDragTarget(y, topPx);
                break;
            case TRIM_LEFT:
                applyTrim(t, true);
                break;
            case TRIM_RIGHT:
                applyTrim(t, false);
                break;
        }
        callback.onGestureLive(activeItem);
    }

    /**
     * M10: re-resolve the cross-row drag target for the current finger position and
     * update {@link LayerRowRenderer}'s highlight accordingly. A locked/hidden row, or
     * a row in a different band (floating vs audio) than the drag's source, is never a
     * valid target (PLAN Part 7 row M10 scope 5 "no drags in or out" of locked/hidden
     * rows; cross-band moves are out of scope for M10) — those cases clear the
     * highlight instead of arming a bogus move.
     */
    private void updateDragTarget(float y, float topPx) {
        lastMoveY = y;
        lastMoveTopPx = topPx;
        boolean sourceIsFloatingBand = rowRenderer.isFloatingBandRow(activeTrack);

        if (rowRenderer.isWithinNewLayerZone(y, topPx)) {
            hoverNewLayerZone = true;
            hoverTargetTrack = null;
            rowRenderer.setDragTargetTrackId(null);
            return;
        }
        hoverNewLayerZone = false;

        Track candidate = rowRenderer.rowTrackAt(y, topPx);
        if (candidate == null || candidate.getId().equals(activeTrack.getId())
                || candidate.isLocked() || candidate.isHidden()
                || rowRenderer.isFloatingBandRow(candidate) != sourceIsFloatingBand) {
            hoverTargetTrack = null;
            rowRenderer.setDragTargetTrackId(null);
            return;
        }
        hoverTargetTrack = candidate;
        rowRenderer.setDragTargetTrackId(candidate.getId());
    }

    private void applyMove(long targetTimeMs, long totalMs) {
        TimedItem item = activeItem;
        if (item.getTextOverlay() != null) {
            TextOverlayItem o = item.getTextOverlay();
            long duration = (dragStartDurationMsForMove(o));
            // targetTimeMs is where the finger's x maps to; anchor MOVE so the item's
            // start tracks the finger delta from the drag-start x, not an absolute jump.
            long newStart = Math.max(0, targetTimeMs - moveGrabOffsetMs);
            long newEnd = (o.getEndMs() == Long.MAX_VALUE) ? Long.MAX_VALUE : newStart + duration;
            o.setTimeRange(newStart, newEnd);
        } else if (item.getAudioClip() != null) {
            AudioClip ac = item.getAudioClip();
            long newOffset = Math.max(0, targetTimeMs - moveGrabOffsetMs);
            ac.setOffsetMs(newOffset);
        }
    }

    /** ms from the item's start to the finger's grab point, captured on first move. */
    private long moveGrabOffsetMs = -1;

    private long dragStartDurationMsForMove(@NonNull TextOverlayItem o) {
        if (dragStartDurationMs <= 0) {
            long end = o.getEndMs();
            dragStartDurationMs = (end == Long.MAX_VALUE)
                    ? Long.MAX_VALUE : Math.max(MIN_TEXT_DURATION_MS, end - Math.max(0, o.getStartMs()));
        }
        return dragStartDurationMs == Long.MAX_VALUE ? 0 : dragStartDurationMs;
    }

    private void applyTrim(long targetTimeMs, boolean left) {
        TimedItem item = activeItem;
        if (item.getTextOverlay() != null) {
            TextOverlayItem o = item.getTextOverlay();
            if (left) {
                long maxStart = (dragStartTextEndMs == Long.MAX_VALUE)
                        ? Long.MAX_VALUE : dragStartTextEndMs - MIN_TEXT_DURATION_MS;
                long newStart = Math.max(0, Math.min(targetTimeMs, maxStart));
                o.setTimeRange(newStart, dragStartTextEndMs);
            } else {
                long newEnd = Math.max(dragStartTextStartMs + MIN_TEXT_DURATION_MS, targetTimeMs);
                o.setTimeRange(dragStartTextStartMs, newEnd);
            }
        } else if (item.getAudioClip() != null) {
            AudioClip ac = item.getAudioClip();
            long srcDur = ac.getSourceDurationMs();
            if (srcDur <= 0) return;
            // Mirrors EditorTimelineView#doAudioTrimDrag exactly: trim moves inPointMs/
            // outPointMs within the source; offsetMs (absolute timeline position) is
            // untouched by a trim gesture — only by MOVE. targetTimeMs is an absolute
            // timeline ms; the clip's offset is fixed during a trim, so subtracting it
            // converts to a source-relative in/out point.
            if (left) {
                long newIn = Math.max(0, Math.min(dragStartTrimOutMs - AUDIO_MIN_TRIM_GAP_MS,
                        targetTimeMs - ac.getOffsetMs()));
                ac.setInPointMs(newIn);
            } else {
                long newOut = Math.max(dragStartTrimInMs + AUDIO_MIN_TRIM_GAP_MS,
                        Math.min(srcDur, targetTimeMs - ac.getOffsetMs()));
                ac.setOutPointMs(newOut);
            }
        }
    }

    /**
     * UP/CANCEL. Finalizes the gesture: records nothing itself (that's the caller's
     * job via {@link Callback#onGestureFinished}, which compares before/after and is
     * naturally no-op-guarded the same way {@code recordOverlayTimelineDrag} /
     * {@code AudioTrimAction} recording already is upstream). Returns true if a
     * gesture was active (caller should consume the UP).
     */
    public boolean onRowBodyUp() {
        cancelLongPress();
        if (!active) return false;
        boolean wasMoved = movedDuringGesture && !longPressFired;
        TimedItem item = activeItem;
        Track fromTrack = activeTrack;
        Track toTrack = hoverTargetTrack;
        boolean droppedOnNewLayerZone = hoverNewLayerZone;
        active = false;
        activeItem = null;
        activeTrack = null;
        moveGrabOffsetMs = -1;
        dragStartDurationMs = 0;
        hoverTargetTrack = null;
        hoverNewLayerZone = false;
        rowRenderer.setDragTargetTrackId(null);
        if (wasMoved && item != null) {
            callback.onGestureFinished(item, activeKind);
            // M10: cross-row / drop-to-new-layer are reported as SEPARATE events from
            // the time-position change above (PLAN Part 7 row M10 scope 1/2) — a drag
            // can end on a different row with or without also having moved in time.
            if (droppedOnNewLayerZone && fromTrack != null) {
                callback.onItemDroppedOnNewLayer(item, fromTrack);
            } else if (toTrack != null && fromTrack != null) {
                callback.onItemMovedToTrack(item, fromTrack, toTrack);
            }
        }
        return true;
    }

    private void cancelLongPress() {
        if (longPressRunnable != null) longPressHandler.removeCallbacks(longPressRunnable);
    }

    // ── Snapshot accessors for the caller's undo-recording (before-state) ──────

    @Nullable
    public TextOverlayItem.TransformSnapshot getTextBeforeSnapshot() { return textBeforeSnapshot; }

    public long getAudioBeforeOffsetMs() { return audioBeforeOffsetMs; }
    public long getAudioBeforeInMs() { return audioBeforeInMs; }
    public long getAudioBeforeOutMs() { return audioBeforeOutMs; }

    // ── M10: drag-state queries for the caller's LayerRowRenderer#layout call ──

    /**
     * True while a MOVE gesture (not TRIM) is in progress — the only gesture kind that
     * has a cross-row concept — so the caller knows whether to pass {@code dragActive}
     * into {@link LayerRowRenderer#layout} (which draws the new-layer drop zone).
     */
    public boolean isMoveDragActive() {
        return active && movedDuringGesture && activeKind == GestureKind.MOVE;
    }

    /** True if the active MOVE gesture is currently hovering the new-layer drop zone. */
    public boolean isHoveringNewLayerZone() { return hoverNewLayerZone; }
}
