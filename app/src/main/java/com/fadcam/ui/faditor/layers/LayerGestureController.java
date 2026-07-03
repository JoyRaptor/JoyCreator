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
 * <p><b>Undo:</b> exactly one undo step per completed gesture — including a diagonal M10
 * drag that changes BOTH time-position and track membership (PLAN M10 acceptance (d)).
 * This class snapshots the payload's before-state at gesture start (mirroring
 * {@code FaditorEditorActivity#onOverlayDragStart}/{@code EditActions.AudioTrimAction}),
 * applies live mutations during the drag for immediate visual feedback (mirroring
 * {@code doAudioTrimDrag}/{@code doLayerDrag}), and on {@link #onRowBodyUp} reports the
 * finished gesture via, IN ORDER: {@link Callback#onItemMovedToTrack}/
 * {@link Callback#onItemDroppedOnNewLayer} (if the row/track also changed) THEN
 * {@link Callback#onGestureFinished} with the before-snapshot — so the activity can
 * stage the track mutation's undo/redo halves in the first call and fold them into the
 * SAME single {@code EditActions.OverlayTransformAction} / {@code EditActions.AudioTrimAction}
 * / {@code EditActions.LambdaAction} the second call records — the SAME undo classes
 * M6-era code already uses, not new parallel ones. Delete reports via
 * {@link Callback#onItemDeleteRequested} so the activity can reuse its existing
 * confirmation-dialog pattern ({@code onOverlayLayerLongPressed}/{@code deleteSelectedAudioClip}).</p>
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

        /**
         * The user asked to delete {@code item} — the activity should offer to delete it
         * (reusing its existing confirmation-dialog delete path). NOTE (redesign): this is
         * NO LONGER wired to a raw long-press on the item body (that gesture is now
         * PICK-UP-for-move — see {@link #beginPickup}). It is invoked from the SELECTED
         * state instead: the caller routes its existing selection-delete affordance (the
         * timeline toolbar trash) to the currently-selected row item. Kept as a callback so
         * the activity owns the confirmation UX + undo, identical to how it deletes an
         * overlay/audio clip selected on any other surface.
         */
        void onItemDeleteRequested(@NonNull Track track, @NonNull TimedItem item);

        /**
         * A MOVE gesture on {@code item} ended with the finger over a DIFFERENT row than
         * where the drag started (PLAN Part 7 row M10 scope 1, drag-between-layers).
         * {@code fromTrack}/{@code toTrack} are the source/destination Track VIEWS at
         * gesture-start/end (both EPHEMERAL — read their {@code getId()} before this
         * call returns, don't hold the objects).
         *
         * <p><b>Fires BEFORE {@link #onGestureFinished} for the same gesture</b> (see
         * {@link LayerGestureController#onRowBodyUp} doc). Implementations must apply
         * the persistent mutation (setting the item's {@code layerId} to
         * {@code toTrack.getId()}) immediately so the model is consistent for the
         * upcoming {@code onGestureFinished} call, but should stage — not
         * {@code undoManager.recordAction} — the undo/redo halves of that mutation, and
         * hand them to the {@code onGestureFinished} handler to fold into the SAME
         * single undo action as the position change (PLAN M10 acceptance (d): one undo
         * step per completed drag, even when both position and track changed).</p>
         */
        void onItemMovedToTrack(@NonNull TimedItem item, @NonNull Track fromTrack, @NonNull Track toTrack);

        /**
         * A MOVE gesture on {@code item} ended with the finger over the "new layer"
         * drop zone below the last row (PLAN Part 7 row M10 scope 2, drop-to-new-layer).
         * Fires BEFORE {@link #onGestureFinished} for the same reason as
         * {@link #onItemMovedToTrack} above. The activity creates a new persistent
         * track (matching {@code fromTrack}'s band/kind — see
         * {@code LayerRowRenderer#isFloatingBandRow}), reassigns the item's
         * {@code layerId} to it immediately, and stages the undo/redo halves for
         * {@code onGestureFinished} to fold into one action.
         */
        void onItemDroppedOnNewLayer(@NonNull TimedItem item, @NonNull Track fromTrack);
    }

    public enum GestureKind { MOVE, TRIM_LEFT, TRIM_RIGHT }

    /**
     * Outcome of {@link #onRowBodyDown}: what the caller (EditorTimelineView) should do
     * with the follow-up MotionEvents for this DOWN. The redesigned contract (PLAN
     * TARGET CONTRACT) never arms a MOVE straight off a body touch — a plain horizontal
     * swipe over an item must SCRUB the timeline, not nudge the item. So a body hit
     * returns {@link #PENDING} (defer: tap vs scrub vs long-press-pickup vs row-scroll is
     * decided by the caller from the first moves / a long-press timer); only an edge
     * trim-handle on the already-selected item returns {@link #ARMED_TRIM} (drag = trim,
     * immediate, unchanged from before). {@link #MISS} = empty row space / locked / hidden
     * / no item — caller falls through to its own axis decision (scrub vs row-scroll).
     */
    public enum DownResult { MISS, PENDING, ARMED_TRIM,
        /** The DOWN was fully handled here (the selected item's delete badge fired
         *  {@link Callback#onItemDeleteRequested}) — caller consumes the touch, arms
         *  NOTHING, and expects no follow-up routing for this gesture. Mirrors how a
         *  header icon tap consumes on DOWN. */
        CONSUMED }

    private static final long MIN_TEXT_DURATION_MS = 250;
    private static final long AUDIO_MIN_TRIM_GAP_MS = 500;

    // ── TEMP diagnostics (tag "ROWGESTURE") — strip after user confirms Bug A/B fixed.
    // Gated so no string is built when disabled (FLog has no isLoggable guard). Flip
    // ROWGESTURE_LOG=false (or delete every RG(...) call + this block) to remove. ──
    private static final boolean ROWGESTURE_LOG = true;
    /** Movement slop (px) that promotes a touch to a drag — matches the horizontal value historically used here. */
    private static final float MOVE_SLOP_PX = 4f;
    private static void RG(String msg) { if (ROWGESTURE_LOG) com.fadcam.FLog.d("ROWGESTURE", msg); }

    private final LayerRowRenderer rowRenderer;
    private final Callback callback;

    // ── Active gesture state (never stored on Track/TimedItem — PLAN's one rule) ──
    private boolean active = false;
    private GestureKind activeKind = null;
    private Track activeTrack;
    private TimedItem activeItem;
    private float dragStartX;
    private float dragStartY;            // finger y at gesture start (for axis-agnostic move detection — cross-row drags are VERTICAL)
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

    /**
     * True once a long-press PICK-UP has committed for the current body touch — only then
     * does a drag actually MOVE the item (PLAN TARGET CONTRACT: swipe=scrub, hold=grab).
     * Before pickup, the caller owns the touch (deciding tap vs scrub vs row-scroll); this
     * controller does nothing to the item. Drives the "lifted" visual in the renderer.
     */
    private boolean pickupArmed = false;
    /** True from a body DOWN until UP/pickup — the caller is still disambiguating this touch. */
    private boolean pendingBodyDown = false;

    public LayerGestureController(@NonNull LayerRowRenderer rowRenderer, @NonNull Callback callback) {
        this.rowRenderer = rowRenderer;
        this.callback = callback;
    }

    /** Currently-selected item id (drives trim-handle visibility in the renderer/hit-test). */
    @Nullable
    public String getSelectedItemId() { return selectedItemId; }

    public void clearSelection() { selectedItemId = null; }

    /**
     * DOWN on a row body (already confirmed within the row region and not a header hit by
     * the caller). See {@link DownResult}:
     * <ul>
     *   <li>{@link DownResult#ARMED_TRIM} — hit an edge trim-handle of the SELECTED item;
     *       trim is armed, the caller should route follow-up MOVEs into
     *       {@link #onRowBodyMove} (drag = trim) and consume the touch.</li>
     *   <li>{@link DownResult#PENDING} — hit an item BODY. The item is SELECTED now
     *       (tap-select feel on down), but NOTHING is armed to move yet. The caller must
     *       disambiguate the follow-up MotionEvents itself (PLAN TARGET CONTRACT):
     *       horizontal-dominant move = SCRUB (pass through, item not moved); a long-press
     *       with low movement = PICK-UP (caller then calls {@link #beginPickup}); vertical
     *       move before pickup = row-scroll. Only after {@link #beginPickup} does
     *       {@link #onRowBodyMove} move the item.</li>
     *   <li>{@link DownResult#MISS} — empty row space / locked / hidden / collapsed row /
     *       no item under the touch. Selection cleared; caller falls through to its own
     *       scrub-vs-row-scroll axis decision.</li>
     * </ul>
     */
    @NonNull
    public DownResult onRowBodyDown(float x, float y, float topPx, long totalMs,
                                    @NonNull LayerRowRenderer.TimeToX timeToX) {
        LayerRowRenderer.ItemHit hit = rowRenderer.hitTestItem(x, y, topPx, totalMs, timeToX, selectedItemId);
        if (hit == null) {
            RG("DOWN miss (no item under touch) x=" + x + " y=" + y + " topPx=" + topPx + " -> MISS (caller axis-decides scrub/scroll)");
            selectedItemId = null;
            active = false;
            pendingBodyDown = false;
            pickupArmed = false;
            return DownResult.MISS;
        }
        if (hit.zone == LayerRowRenderer.ItemZone.DELETE) {
            // Trash badge on the SELECTED item (redesign: delete lives here now, not on
            // long-press). Fire the same confirmation-dialog callback the long-press
            // used and consume — nothing armed, selection untouched (the dialog's
            // cancel path leaves the item selected exactly as before the tap).
            RG("DOWN hit DELETE badge item=" + hit.item.getId() + " track=" + hit.track.getId()
                    + " -> onItemDeleteRequested (CONSUMED, nothing armed)");
            callback.onItemDeleteRequested(hit.track, hit.item);
            return DownResult.CONSUMED;
        }
        activeTrack = hit.track;
        activeItem = hit.item;
        dragStartX = x;
        dragStartY = y;
        movedDuringGesture = false;
        pickupArmed = false;
        pendingBodyDown = false;
        hoverTargetTrack = null;
        hoverNewLayerZone = false;
        rowRenderer.setDragTargetTrackId(null);

        if (hit.zone == LayerRowRenderer.ItemZone.LEFT_HANDLE
                || hit.zone == LayerRowRenderer.ItemZone.RIGHT_HANDLE) {
            boolean left = hit.zone == LayerRowRenderer.ItemZone.LEFT_HANDLE;
            armTrim(hit.item, left);
            active = true;
            activeKind = left ? GestureKind.TRIM_LEFT : GestureKind.TRIM_RIGHT;
            RG("DOWN hit item=" + hit.item.getId() + " zone=" + hit.zone + " track=" + hit.track.getId()
                    + " -> ARMED_TRIM (immediate)");
            return DownResult.ARMED_TRIM;
        }

        // Body hit: SELECT immediately (tap-select feel; also exposes trim handles), but
        // DO NOT arm a move and DO NOT start a delete long-press. Whether this becomes a
        // tap, a scrub, a row-scroll, or a pick-up-for-move is the caller's decision from
        // the follow-up events. active=true so isMoveDragActive()/onRowBodyUp() have a
        // consistent lifecycle, but activeKind stays MOVE only as the *potential* kind;
        // movedDuringGesture stays false until beginPickup().
        selectedItemId = hit.item.getId();
        active = true;
        pendingBodyDown = true;
        activeKind = GestureKind.MOVE;
        RG("DOWN hit item=" + hit.item.getId() + " zone=BODY track=" + hit.track.getId()
                + " locked=" + hit.track.isLocked() + " floatingBand=" + rowRenderer.isFloatingBandRow(hit.track)
                + " x=" + x + " y=" + y + " -> PENDING (selected; awaiting tap/scrub/pickup/scroll)");
        return DownResult.PENDING;
    }

    /**
     * Promote a PENDING body touch (see {@link #onRowBodyDown}) into a PICK-UP for move:
     * the long-press fired with low movement, so from here a drag MOVES the item
     * (horizontal = reposition in time, vertical = change layer / new-layer zone). Arms
     * the move snapshot and flags the "lifted" visual. No-op unless a body touch is
     * currently pending (e.g. the caller already resolved it to scrub/scroll).
     *
     * @return true if pickup was armed (caller should now set its item-drag-active flag
     *         and route MOVEs to {@link #onRowBodyMove}); false if there was nothing to
     *         pick up.
     */
    public boolean beginPickup() {
        if (!active || !pendingBodyDown || activeItem == null) return false;
        pendingBodyDown = false;
        pickupArmed = true;
        activeKind = GestureKind.MOVE;
        armMove(activeItem);
        rowRenderer.setLiftedItemId(activeItem.getId());
        RG("PICKUP armed item=" + activeItem.getId() + " track=" + activeTrack.getId()
                + " (long-press + low movement -> item now follows finger; lift visible)");
        return true;
    }

    /** True while a PENDING body touch is still awaiting the caller's tap/scrub/scroll/pickup decision. */
    public boolean isPendingBodyDown() { return active && pendingBodyDown; }

    /** True once {@link #beginPickup} has committed — a drag now moves the item + it renders lifted. */
    public boolean isPickupArmed() { return pickupArmed; }

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
        // Redesign gate (PLAN TARGET CONTRACT): a MOVE only happens AFTER a pick-up
        // (long-press). Before pickup the caller keeps a body touch in its own pending
        // state and never routes MOVEs here, so if this is a MOVE-kind gesture that has
        // NOT been picked up, do nothing (defensive — a stray event must not nudge the
        // item, which was the whole "purple feels stuck" bug: a plain swipe moved it a
        // few ms instead of scrubbing). TRIM is unaffected — it arms immediately on DOWN.
        if (activeKind == GestureKind.MOVE && !pickupArmed) return;

        // First real move after pickup (MOVE) or first move of a trim: mark moved so the
        // drop/commit path in onRowBodyUp records the change. For MOVE this also starts
        // the cross-row hover/new-layer-zone tracking. Either axis counts (a cross-row
        // drag is inherently VERTICAL — the finger travels down while x barely changes).
        if (!movedDuringGesture
                && (Math.abs(x - dragStartX) > MOVE_SLOP_PX || Math.abs(y - dragStartY) > MOVE_SLOP_PX)) {
            movedDuringGesture = true;
            RG("MOVE/TRIM first movement kind=" + activeKind
                    + " dx=" + (x - dragStartX) + " dy=" + (y - dragStartY)
                    + (activeKind == GestureKind.MOVE ? " (picked-up item now tracking finger)" : ""));
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
            if (!hoverNewLayerZone) RG("HOVER -> NEW-LAYER-ZONE (armed) y=" + y + " topPx=" + topPx);
            hoverNewLayerZone = true;
            hoverTargetTrack = null;
            rowRenderer.setDragTargetTrackId(null);
            return;
        }
        if (hoverNewLayerZone) RG("HOVER left NEW-LAYER-ZONE y=" + y + " topPx=" + topPx);
        hoverNewLayerZone = false;

        Track candidate = rowRenderer.rowTrackAt(y, topPx);
        if (candidate == null || candidate.getId().equals(activeTrack.getId())
                || candidate.isLocked() || candidate.isHidden()
                || rowRenderer.isFloatingBandRow(candidate) != sourceIsFloatingBand) {
            // TEMP: log WHY a row under the finger is not a valid drop target (helps prove
            // the "can't move between levels" chicken-and-egg: with only 1 row per band the
            // only legal target is the new-layer zone above).
            if (candidate != null && !candidate.getId().equals(activeTrack.getId())) {
                RG("HOVER row=" + candidate.getId() + " REJECTED reason="
                        + (candidate.isLocked() ? "locked" : candidate.isHidden() ? "hidden"
                            : rowRenderer.isFloatingBandRow(candidate) != sourceIsFloatingBand ? "cross-band" : "?"));
            }
            if (hoverTargetTrack != null) RG("HOVER cleared target (was " + hoverTargetTrack.getId() + ")");
            hoverTargetTrack = null;
            rowRenderer.setDragTargetTrackId(null);
            return;
        }
        if (hoverTargetTrack == null || !hoverTargetTrack.getId().equals(candidate.getId())) {
            RG("HOVER -> valid target row=" + candidate.getId());
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
     *
     * <p><b>Ordering (deliberate, ONE-undo-step requirement):</b> the track-change
     * callback ({@link Callback#onItemMovedToTrack}/{@link Callback#onItemDroppedOnNewLayer})
     * fires BEFORE {@link Callback#onGestureFinished}, not after. A diagonal drag (the
     * common case — a finger rarely moves in a perfectly straight vertical line) changes
     * BOTH the item's time-position AND its track in the SAME gesture; firing the
     * track-change callback first lets the activity stash the pending track mutation,
     * then fold it into the SAME undo action {@code onGestureFinished} builds for the
     * position change, rather than pushing two separate {@code undoStack} entries for
     * one physical drag (PLAN M10 acceptance (d): "each completed drag = ONE undo step").</p>
     */
    public boolean onRowBodyUp() {
        if (!active) return false;
        // A real committed change only happened if we actually moved (trim, or a
        // picked-up move). A body touch that lifted before pickup (pendingBodyDown still
        // set) is a TAP — selection already happened on DOWN, nothing to record.
        boolean wasMoved = movedDuringGesture && (activeKind != GestureKind.MOVE || pickupArmed);
        TimedItem item = activeItem;
        Track fromTrack = activeTrack;
        Track toTrack = hoverTargetTrack;
        boolean droppedOnNewLayerZone = hoverNewLayerZone;
        boolean wasTap = pendingBodyDown && !movedDuringGesture;
        boolean wasPickup = pickupArmed;
        active = false;
        activeItem = null;
        activeTrack = null;
        moveGrabOffsetMs = -1;
        dragStartDurationMs = 0;
        pendingBodyDown = false;
        pickupArmed = false;
        hoverTargetTrack = null;
        hoverNewLayerZone = false;
        rowRenderer.setDragTargetTrackId(null);
        rowRenderer.setLiftedItemId(null);
        RG("UP active=true wasMoved=" + wasMoved + " outcome=" + (wasTap ? "TAP(select-only)"
                        : wasPickup ? "PICKUP-MOVE" : activeKind == GestureKind.TRIM_LEFT || activeKind == GestureKind.TRIM_RIGHT ? "TRIM" : "no-op")
                + " kind=" + activeKind + " droppedOnNewLayer=" + droppedOnNewLayerZone
                + " toTrack=" + (toTrack == null ? "null" : toTrack.getId()));
        if (wasMoved && item != null) {
            // M10: report the track-change FIRST (see method doc) so the activity can
            // fold it into the ONE undo action onGestureFinished below builds.
            if (droppedOnNewLayerZone && fromTrack != null) {
                RG("DROP COMMIT -> onItemDroppedOnNewLayer (create new layer + move item " + item.getId() + ")");
                callback.onItemDroppedOnNewLayer(item, fromTrack);
            } else if (toTrack != null && fromTrack != null) {
                RG("DROP COMMIT -> onItemMovedToTrack " + fromTrack.getId() + "->" + toTrack.getId());
                callback.onItemMovedToTrack(item, fromTrack, toTrack);
            } else {
                RG("DROP no track change (same-row move/trim only)");
            }
            callback.onGestureFinished(item, activeKind);
        } else {
            RG("UP no-op (tap = select-only, or never picked up — no move recorded)");
        }
        return true;
    }

    // ── Snapshot accessors for the caller's undo-recording (before-state) ──────

    @Nullable
    public TextOverlayItem.TransformSnapshot getTextBeforeSnapshot() { return textBeforeSnapshot; }

    public long getAudioBeforeOffsetMs() { return audioBeforeOffsetMs; }
    public long getAudioBeforeInMs() { return audioBeforeInMs; }
    public long getAudioBeforeOutMs() { return audioBeforeOutMs; }

    // ── M10: drag-state queries for the caller's LayerRowRenderer#layout call ──

    /**
     * True while a PICKED-UP MOVE (not TRIM, not a pre-pickup body touch) is in progress —
     * the only state that has a cross-row concept — so the caller knows whether to pass
     * {@code dragActive} into {@link LayerRowRenderer#layout} (which draws + pins the
     * new-layer drop zone). Gated on {@link #pickupArmed} so a plain swipe/scrub over an
     * item never flashes the drop zone (PLAN TARGET CONTRACT: the zone only appears once
     * the item is actually lifted for a move).
     */
    public boolean isMoveDragActive() {
        return active && pickupArmed && activeKind == GestureKind.MOVE;
    }

    /** True if the active MOVE gesture is currently hovering the new-layer drop zone. */
    public boolean isHoveringNewLayerZone() { return hoverNewLayerZone; }
}
