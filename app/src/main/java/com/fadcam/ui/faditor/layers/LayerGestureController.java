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
    public enum DownResult { MISS, PENDING, ARMED_TRIM }

    private static final long MIN_TEXT_DURATION_MS = 250;
    private static final long AUDIO_MIN_TRIM_GAP_MS = 500;

    /** Movement slop (px) that promotes a touch to a drag — matches the horizontal value historically used here. */
    private static final float MOVE_SLOP_PX = 4f;

    /**
     * Snap radius in RAW px — the ONE tunable for every drag snap in this controller
     * (home snap, trim-home snap, butting suggestion). The view supplies a dp-scaled
     * value at construction (FEEDBACK_20260703_dragux_v3 A4: user measured the old
     * hardcoded 48px as "~4mm, too aggressive"; target is 1–2mm ≈ 8dp). Default stays
     * conservative for any caller that never wires the setter.
     */
    private float snapRadiusPx = 24f;

    /** See {@link #snapRadiusPx} — the view calls this once with {@code SNAP_RADIUS_DP * density}. */
    public void setSnapRadiusPx(float px) { if (px > 0f) snapRadiusPx = px; }

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
     * True while the picked-up drag hovers a row of the OTHER band (visual item over the
     * audio band or vice versa). Redesigned semantics (user feedback 2026-07-03): instead
     * of a silent dead zone, this ARMS the same new-lane drop the pinned zone offers —
     * dropping a visual item "past its band" creates the new visual lane at its true
     * position (above the audio band), which is what the user meant by the drag. The
     * renderer draws an insertion line at that true position while armed, so the preview
     * stops lying about where the item will land.
     */
    private boolean hoverCrossBandNewLane = false;
    /** Last row id whose hover rejection was logged (throttle: one RG line per row, not per MOVE). */
    @Nullable private String lastRejectedRowId;

    /**
     * True once a long-press PICK-UP has committed for the current body touch — only then
     * does a drag actually MOVE the item (PLAN TARGET CONTRACT: swipe=scrub, hold=grab).
     * Before pickup, the caller owns the touch (deciding tap vs scrub vs row-scroll); this
     * controller does nothing to the item. Drives the "lifted" visual in the renderer.
     */
    private boolean pickupArmed = false;
    /** True from a body DOWN until UP/pickup — the caller is still disambiguating this touch. */
    private boolean pendingBodyDown = false;
    /** True when the current PENDING body touch landed on the selected item's delete
     *  badge — a tap-resolution (UP within slop, committed) fires the delete
     *  confirmation; any other resolution (scrub/pickup/scroll/cancel) ignores it. */
    private boolean pendingDeleteBadge = false;

    // ── FOLLOW-UP 1 (user spec 2026-07-03): occupied-row bookend snap ─────────────
    /**
     * Joint (content ms) the armed bookend shows — the target row's FIRST item's start
     * (BEFORE side) or LAST item's end (AFTER side) — or {@link Long#MIN_VALUE} when no
     * bookend is armed. The view polls this after each onRowBodyMove and runs the
     * animated "excursion" that brings the joint on-screen (playhead stays
     * content-locked as the temporary-maneuver cue).
     */
    private long bookendJointMs = Long.MIN_VALUE;
    /** Snapped start (ms) for the dragged item while a bookend is armed. */
    private long bookendSnapStartMs;
    /** false = BEFORE the row's first item, true = AFTER the row's last item. */
    private boolean bookendAfter;
    /**
     * The dragged item's DISPLAYED duration captured at first movement (pre-mutation).
     * Used for all bookend/ghost math instead of live getDisplayDurationMs: for an
     * open-ended text item the live value changes as the item moves (it renders "to
     * project end"), which made the BEFORE-snap overlap and the AFTER-snap preview
     * longer than the clip (user feedback 2026-07-03 morning).
     */
    private long dragStartDisplayDurMs = 0;
    /** True while the picked-up drag hovers the item's OWN row (home-snap eligibility). */
    private boolean hoveringHomeRow = false;
    /** Log-throttle + state for the home/original snap being engaged. */
    private boolean homeSnapArmed = false;
    /**
     * While true (the view is animating its return from an excursion), onRowBodyMove
     * skips the finger→time mapping — the view's scrollOffset is mid-animation, so
     * mapping finger x through it would teleport the item. The item stays put until the
     * view settles; the next MOVE after that resumes normal finger tracking.
     */
    private boolean suppressMoveMapping = false;

    /** See {@link #bookendJointMs}. */
    public long getBookendJointMs() { return bookendJointMs; }

    /** See {@link #suppressMoveMapping} — set by the view around its return animation. */
    public void setSuppressMoveMapping(boolean s) { suppressMoveMapping = s; }

    private void clearBookend() {
        bookendJointMs = Long.MIN_VALUE;
    }

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
            selectedItemId = null;
            active = false;
            pendingBodyDown = false;
            pendingDeleteBadge = false;
            pickupArmed = false;
            return DownResult.MISS;
        }
        activeTrack = hit.track;
        activeItem = hit.item;
        dragStartX = x;
        dragStartY = y;
        movedDuringGesture = false;
        pickupArmed = false;
        pendingBodyDown = false;
        // Delete badge (review fix 2026-07-03): DEFERRED to a tap-on-UP instead of firing
        // on DOWN. A DOWN on the badge routes exactly like a body hit (PENDING) with this
        // flag set: a quick lift within slop = the delete tap (confirmation fires in
        // onRowBodyUp); a horizontal swipe from the badge = SCRUB; a long-press = pickup.
        // Firing on DOWN hijacked swipes that happened to start on the (viewport-pinned)
        // badge with a blocking dialog — the contract says swipe must always scrub.
        pendingDeleteBadge = hit.zone == LayerRowRenderer.ItemZone.DELETE;
        hoverTargetTrack = null;
        hoverNewLayerZone = false;
        rowRenderer.setDragTargetTrackId(null);

        if (hit.zone == LayerRowRenderer.ItemZone.LEFT_HANDLE
                || hit.zone == LayerRowRenderer.ItemZone.RIGHT_HANDLE) {
            boolean left = hit.zone == LayerRowRenderer.ItemZone.LEFT_HANDLE;
            armTrim(hit.item, left);
            rowRenderer.setTrimmingItemId(hit.item.getId()); // timeline-locked stripe feedback
            active = true;
            activeKind = left ? GestureKind.TRIM_LEFT : GestureKind.TRIM_RIGHT;
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
        rowRenderer.setDragOutlineState(LayerRowRenderer.DRAG_OUTLINE_SAME_ROW);
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
            // Capture the pre-mutation displayed extent ONCE: it drives the bookend
            // math AND the "home ghost" — the grey outline left at the item's original
            // position/length so the user always sees where it came from and can snap
            // back exactly (no undo needed). Same mechanism for MOVE and TRIM.
            dragStartDisplayDurMs = activeItem.getDisplayDurationMs(totalMs);
            rowRenderer.setHomeGhost(activeTrack.getId(),
                    activeItem.getTimelineStartMs(), dragStartDisplayDurMs);
        }
        if (!movedDuringGesture) return;

        long t = xToTime.map(x);
        // Snap radius in ms at the CURRENT zoom — from the single dp-scaled tunable
        // (A4: gentle, ~1–2mm; was a hardcoded 48px ≈ 4mm the user called too grabby).
        long snapThrMs = Math.abs(xToTime.map(x + snapRadiusPx) - t);
        if (activeKind == GestureKind.MOVE && moveGrabOffsetMs < 0) {
            // First move of a MOVE gesture: capture how far into the item the finger
            // grabbed it, so the item tracks the finger instead of snapping its start
            // to the touch point.
            moveGrabOffsetMs = t - dragStartTimelineMs;
        }
        switch (activeKind) {
            case MOVE:
                // Target/hover first so the placement below reflects THIS event's row
                // (updateDragTarget is y/finger-driven, independent of the item's
                // current position, so the reorder is safe).
                updateDragTarget(x, y, topPx, totalMs);
                // S5 off-screen butt placement (dragux_v3 slice-3 #5, user-designed,
                // panel-relative like the original bookend spec): which HALF of the
                // TIMELINE PANEL the finger is in decides which side of a covering
                // sibling the resolver butts to when the desired spot is occupied —
                // LEFT half = butt BEFORE the occupant (view then reveals the earlier
                // joint), RIGHT half = butt AFTER. Free placement is unaffected; this
                // only picks the ESCAPE side when the finger is inside a block.
                float panelW = rowRenderer.getLastWidthPx();
                fingerSidePref = panelW > 0f
                        ? ((x - rowRenderer.getLastHScrollOffsetPx()) < panelW / 2f ? -1 : 1) : 0;
                if (!suppressMoveMapping) {
                    long prospective = Math.max(0, t - moveGrabOffsetMs);
                    long draggedDur = dragStartDisplayDurMs > 0 ? dragStartDisplayDurMs
                            : activeItem.getDisplayDurationMs(totalMs);
                    if (hoveringHomeRow
                            && Math.abs(prospective - dragStartTimelineMs) <= snapThrMs) {
                        // HOME SNAP (feedback 2026-07-03am): the user is putting the item
                        // back where it started — snap it EXACTLY there and light the
                        // ghost, so "release = exactly where you started", no undo needed.
                        clearBookend();
                        applyMoveTo(dragStartTimelineMs, false);
                        setHomeSnapArmed(true);
                        rowRenderer.setTimeLockGuides(false, 0, 0);
                        break;
                    }
                    // A9 TIME-LOCK GUARDRAIL (dragux_v3, user spec): hovering a DIFFERENT
                    // row while the would-be start stays within a gentle dead-zone of the
                    // original time = a pure LAYER change, not a time change. Lock time to
                    // the original and draw dotted vertical guides at the item's bounds
                    // ("same time, different layer"). A larger horizontal move unlocks +
                    // hides them (diagonal = move both). PHASE-R R3/R4: the lock zone IS
                    // the one snap constant (re-entering re-locks + re-shows the guides).
                    if (hoverTargetTrack != null
                            && Math.abs(prospective - dragStartTimelineMs) <= snapThrMs) {
                        // A9 SNAP-PRIORITY + WYSIWYG (dragux_v3, user hand-tests 2026-07-04
                        // and 2026-07-05): keep the ORIGINAL time when the target-row slot
                        // at that time is FREE — the drag rails straight up/down with no
                        // horizontal pull (the "diagonal tug-of-war" the user reported was
                        // the OLD oscillating resolver bouncing the item between two
                        // positions; the robust resolver returns dragStartTimelineMs
                        // unchanged when free and never oscillates). When that slot is
                        // OCCUPIED we must NOT preview an overlap (user 2026-07-05: "it
                        // doesn't butt the preview but overlaps"): the robust resolver butts
                        // the item to the nearest legal edge and we draw THAT (WYSIWYG),
                        // publishing the joint so the view reveals the butt.
                        long locked = resolveNoOverlapStart(dragStartTimelineMs, totalMs);
                        long lockJoint = lastButtJointMs;
                        if (locked == dragStartTimelineMs) {
                            // A9 re-engage rule (b) (dragux_v3 SNAP-PRIORITY, BINDING):
                            // while time-locked the butt-magnets stay FULLY suppressed —
                            // EXCEPT when the item's edge AT ITS LOCKED TIME is already
                            // within the snap radius of a neighbor's edge ("only when it
                            // comes close to touching it"). Then, and only then, the
                            // magnet clicks it the last millimetre into the butt.
                            long touch = nearestButtWithin(dragStartTimelineMs, draggedDur,
                                    totalMs, snapThrMs);
                            if (touch != Long.MIN_VALUE) {
                                locked = touch;
                                lockJoint = bookendJointMs; // published by nearestButtWithin
                            }
                        }
                        applyMoveTo(locked, true);
                        rowRenderer.setTimeLockGuides(true, locked, draggedDur);
                        if (locked != dragStartTimelineMs && lockJoint != Long.MIN_VALUE) {
                            bookendJointMs = lockJoint;
                            bookendSnapStartMs = locked;
                            bookendAfter = locked >= lockJoint;
                        } else if (lockJoint == Long.MIN_VALUE) {
                            clearBookend();
                        }
                        setHomeSnapArmed(false);
                        break;
                    }
                    rowRenderer.setTimeLockGuides(false, 0, 0);
                    // FREE PLACEMENT FIRST (dragux_v3 A3): anywhere legal on the landing
                    // row is allowed; butting is a SUGGESTION within the gentle radius,
                    // never a forced destination. Overlap still resolves to the nearest
                    // butting edge (A8 no-overlap rule) — and when it does, the joint is
                    // published via the bookend fields so the view's excursion can bring
                    // an off-screen joint into view (A6/A7 joint visibility preserved).
                    long resolved = resolveNoOverlapStart(prospective, totalMs);
                    if (lastButtJointMs != Long.MIN_VALUE) {
                        // Overlap push → we ARE butting: preview exactly end-to-end.
                        bookendJointMs = lastButtJointMs;
                        bookendSnapStartMs = resolved;
                        bookendAfter = resolved >= lastButtJointMs;
                        applyMoveTo(resolved, true);
                    } else {
                        long suggested = nearestButtWithin(prospective, draggedDur, totalMs, snapThrMs);
                        if (suggested != Long.MIN_VALUE) {
                            // Gentle butt-snap (A4): near a sibling edge → click into it.
                            applyMoveTo(suggested, true);
                        } else {
                            // JOINT HYSTERESIS (user repro 2026-07-04: view never panned
                            // to show the butt): keep the last joint published until the
                            // finger has CLEARLY departed (2× radius) — per-event
                            // disarming flickered the joint and endlessly reset the
                            // view's 220ms excursion dwell timer, so the pan never fired.
                            if (bookendJointMs != Long.MIN_VALUE
                                    && Math.abs(prospective - bookendSnapStartMs) > snapThrMs * 2) {
                                clearBookend();
                            }
                            // HONEST PREVIEW (user repro 2026-07-04: open-ended items
                            // previewed at to-project-end length, painting their outline
                            // OVER siblings the overlap math had already cleared): during
                            // a MOVE drag, ALWAYS preview at the captured closed length.
                            // Open-endedness is restored at DROP when legal (onRowBodyUp).
                            applyMoveTo(resolved, true);
                        }
                    }
                    setHomeSnapArmed(false);
                }
                break;
            case TRIM_LEFT:
                applyTrim(t, true);
                maybeSnapTrimHome(true, snapThrMs);
                break;
            case TRIM_RIGHT:
                applyTrim(t, false);
                maybeSnapTrimHome(false, snapThrMs);
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
    private void updateDragTarget(float x, float y, float topPx, long totalMs) {
        lastMoveY = y;
        lastMoveTopPx = topPx;
        boolean sourceIsFloatingBand = rowRenderer.isFloatingBandRow(activeTrack);

        if (rowRenderer.isWithinNewLayerZone(y, topPx)) {
            hoverNewLayerZone = true;
            hoverCrossBandNewLane = false;
            hoveringHomeRow = false;
            lastRejectedRowId = null;
            hoverTargetTrack = null;
            clearBookend();
            rowRenderer.setDragTargetTrackId(null);
            rowRenderer.setCrossBandInsertionArmed(false, sourceIsFloatingBand);
            rowRenderer.setDragOutlineState(LayerRowRenderer.DRAG_OUTLINE_NEW_LAYER);
            return;
        }
        hoverNewLayerZone = false;

        Track candidate = rowRenderer.rowTrackAt(y, topPx);
        // Home-snap eligibility: only while hovering the item's OWN row (putting it
        // back where it started must not fight the bookend/cross-band logic of others).
        hoveringHomeRow = candidate != null && candidate.getId().equals(activeTrack.getId());
        if (candidate == null || candidate.getId().equals(activeTrack.getId())
                || candidate.isLocked() || candidate.isHidden()
                || rowRenderer.isFloatingBandRow(candidate) != sourceIsFloatingBand) {
            boolean crossBand = candidate != null
                    && rowRenderer.isFloatingBandRow(candidate) != sourceIsFloatingBand;
            // Cross-band hover ARMS the new-lane drop at its TRUE position instead of
            // being a silent dead zone (user feedback 2026-07-03) — see the field doc.
            // Locked/hidden same-band rows stay plain rejections.
            hoverCrossBandNewLane = crossBand;
            rowRenderer.setCrossBandInsertionArmed(crossBand, sourceIsFloatingBand);
            if (candidate != null && !candidate.getId().equals(activeTrack.getId())
                    && !candidate.getId().equals(lastRejectedRowId)) {
                lastRejectedRowId = candidate.getId();
            }
            hoverTargetTrack = null;
            clearBookend();
            rowRenderer.setDragTargetTrackId(null);
            // S3 state colors: cross-band hover arms a NEW-LAYER drop → purple family
            // (dashed); own row / plain rejection = a same-row move → item's own color.
            rowRenderer.setDragOutlineState(crossBand
                    ? LayerRowRenderer.DRAG_OUTLINE_NEW_LAYER
                    : LayerRowRenderer.DRAG_OUTLINE_SAME_ROW);
            return;
        }
        hoverCrossBandNewLane = false;
        lastRejectedRowId = null;
        rowRenderer.setCrossBandInsertionArmed(false, sourceIsFloatingBand);
        // S3 (user 2026-07-04: outline "shows WHITE; expects the established PURPLE
        // cross-row affordance"): hovering ANOTHER row = cross-row move → the dragged
        // item's outline goes purple, same family as the target-row ring.
        rowRenderer.setDragOutlineState(LayerRowRenderer.DRAG_OUTLINE_CROSS_ROW);

        // REDESIGNED (dragux_v3 A3, supersedes FOLLOW-UP 1's forced bookend): hovering
        // an OCCUPIED same-band row no longer teleports the item to a screen-half
        // bookend — the user must be able to place ANYWHERE legal on the row. The MOVE
        // branch in onRowBodyMove now owns all placement: free position via the
        // no-overlap resolver, gentle radius-gated butt-snap, and it publishes the
        // joint through the bookend fields (for the view's excursion) only when an
        // actual butting is in effect. Here we just track the hover target.
        hoverTargetTrack = candidate;
        rowRenderer.setDragTargetTrackId(candidate.getId());
    }

    /**
     * No-overlap rule (P0 fix, FEEDBACK_20260703_dragux_v3 A8 + the user's binding
     * "no multiple items occupying the same time on a layer" decision): during a
     * finger-driven same-band move, if the desired position would overlap a SIBLING
     * on the row the item will land on (the hover-target row when one is armed,
     * else its own row), snap to the nearer legal butting edge instead. Cross-row
     * drops onto OCCUPIED rows are already handled by the bookend snap (which
     * bypasses this path via applyMoveTo); this closes the same-row hole that let
     * audio items stack. Iterates a few times so being pushed out of one sibling
     * into another resolves to a stable legal spot (chains of ≥4 give up and keep
     * the last computed position — the drop can still be aborted via the home ghost).
     */
    private long resolveNoOverlapStart(long desiredStart, long totalMs) {
        lastButtJointMs = Long.MIN_VALUE;
        Track row = hoverTargetTrack != null ? hoverTargetTrack : activeTrack;
        if (row == null || activeItem == null) return Math.max(0, desiredStart);
        long dur = dragStartDisplayDurMs > 0 ? dragStartDisplayDurMs
                : activeItem.getDisplayDurationMs(totalMs);
        if (dur <= 0) return Math.max(0, desiredStart);
        long start = nearestFreeStart(row, activeItem.getId(), desiredStart, dur, totalMs,
                fingerSidePref);
        // lastButtJointMs is set by nearestFreeStart (MIN_VALUE when touching nothing).
        return start;
    }

    /** -1 = finger in LEFT half of the timeline panel, +1 = RIGHT half, 0 = unknown (S5). */
    private int fingerSidePref = 0;

    // ── Resolver scratch buffers (S2 perf: the resolver runs on EVERY move event, so
    // it must not allocate — these primitive arrays are reused across calls and only
    // regrow when a row gains more items than ever seen before). ──
    private long[] blockStartBuf = new long[8];
    private long[] blockEndBuf = new long[8];

    /**
     * Robust no-overlap placement (dragux_v3, user repro 2026-07-05: "I was able to
     * place two clips overlapping by dropping between two butted clips"). Replaces the
     * old 4-pass push-loop, which OSCILLATED between "butt-before" and "butt-after" when
     * there was no room and gave up STILL OVERLAPPING (also the source of the "diagonal
     * tug-of-war" — the item bounced between two positions each frame). Merges the row's
     * siblings into occupied blocks (touching/overlapping intervals coalesced), then
     * returns the start CLOSEST to {@code desiredStart} at which {@code [start, start+dur]}
     * fits ENTIRELY in a free region: before the first block, inside a gap wide enough,
     * or after the last block. The tail after the last block is always free, so a legal
     * start ALWAYS exists — overlap can NEVER be returned. Proven over 13 cases in a
     * standalone harness before porting.
     *
     * <p><b>Perf (S2):</b> this runs on EVERY move event (the WYSIWYG live-resolved
     * preview), so it is allocation-free: siblings are gathered into the reusable
     * {@link #blockStartBuf}/{@link #blockEndBuf} arrays, insertion-sorted (rows hold a
     * handful of items) and merged in place.</p>
     *
     * <p><b>S5 side preference:</b> when {@code sidePref != 0} AND the desired interval
     * actually intersects an occupied block (the finger is "inside" a sibling), the
     * escape side is chosen by the finger's timeline-panel half instead of raw
     * nearest-distance: {@code -1} = butt BEFORE that block, {@code +1} = butt AFTER —
     * falling back to the other side, then to nearest, when the preferred side has no
     * room. Free placements ignore the preference entirely.</p>
     *
     * <p>Sets {@link #lastButtJointMs} to the sibling-block edge the placement abuts
     * (fed to the view's excursion so an off-screen joint is revealed), or
     * {@link Long#MIN_VALUE} when it lands in open space; returns the start.</p>
     */
    private long nearestFreeStart(@NonNull Track row, @NonNull String selfId,
                                  long desiredStart, long dur, long totalMs, int sidePref) {
        lastButtJointMs = Long.MIN_VALUE;
        int n = 0;
        for (TimedItem sib : row.getItems()) {
            if (sib.getId().equals(selfId)) continue;
            long ss = sib.getTimelineStartMs();
            long se = ss + Math.max(0, sib.getDisplayDurationMs(totalMs));
            if (se <= ss) continue;
            if (n == blockStartBuf.length) {
                blockStartBuf = java.util.Arrays.copyOf(blockStartBuf, n * 2);
                blockEndBuf = java.util.Arrays.copyOf(blockEndBuf, n * 2);
            }
            // Insertion sort by start (rows hold a handful of items).
            int j = n;
            while (j > 0 && blockStartBuf[j - 1] > ss) {
                blockStartBuf[j] = blockStartBuf[j - 1];
                blockEndBuf[j] = blockEndBuf[j - 1];
                j--;
            }
            blockStartBuf[j] = ss;
            blockEndBuf[j] = se;
            n++;
        }
        if (n == 0) return Math.max(0, desiredStart);
        // Merge touching/overlapping siblings into occupied blocks, in place.
        int m = 0;
        for (int i = 1; i < n; i++) {
            if (blockStartBuf[i] <= blockEndBuf[m]) {
                blockEndBuf[m] = Math.max(blockEndBuf[m], blockEndBuf[i]);
            } else {
                m++;
                blockStartBuf[m] = blockStartBuf[i];
                blockEndBuf[m] = blockEndBuf[i];
            }
        }
        int blockCount = m + 1;

        // S5: finger inside a block + a side preference → escape to the chosen side.
        if (sidePref != 0) {
            for (int i = 0; i < blockCount; i++) {
                if (desiredStart < blockEndBuf[i] && desiredStart + dur > blockStartBuf[i]) {
                    long beforeStart = blockStartBuf[i] - dur;
                    boolean beforeOk = beforeStart >= 0
                            && (i == 0 || beforeStart >= blockEndBuf[i - 1]);
                    long afterStart = blockEndBuf[i];
                    boolean afterOk = i + 1 >= blockCount
                            || blockStartBuf[i + 1] - afterStart >= dur;
                    if (sidePref < 0 ? beforeOk : afterOk) {
                        boolean before = sidePref < 0;
                        lastButtJointMs = before ? blockStartBuf[i] : blockEndBuf[i];
                        return before ? beforeStart : afterStart;
                    }
                    if (sidePref < 0 ? afterOk : beforeOk) {
                        boolean before = sidePref >= 0; // preferred side had no room → flip
                        lastButtJointMs = before ? blockStartBuf[i] : blockEndBuf[i];
                        return before ? beforeStart : afterStart;
                    }
                    break; // neither adjacent side fits → nearest-logic below decides
                }
            }
        }

        long bestStart = Long.MIN_VALUE, bestJoint = Long.MIN_VALUE, bestDist = Long.MAX_VALUE;
        // (a) Before the first block.
        long firstStart = blockStartBuf[0];
        if (firstStart - dur >= 0) {
            long hi = firstStart - dur;
            long cand = Math.max(0, Math.min(desiredStart, hi));
            long dist = Math.abs(cand - desiredStart);
            long joint = (cand + dur == firstStart) ? firstStart : Long.MIN_VALUE;
            if (dist < bestDist) { bestDist = dist; bestStart = cand; bestJoint = joint; }
        }
        // (b) Between consecutive blocks (only gaps wide enough for dur).
        for (int i = 0; i + 1 < blockCount; i++) {
            long gapLo = blockEndBuf[i];
            long gapHi = blockStartBuf[i + 1] - dur;
            if (gapHi >= gapLo) {
                long cand = Math.max(gapLo, Math.min(desiredStart, gapHi));
                long dist = Math.abs(cand - desiredStart);
                long joint = Long.MIN_VALUE;
                if (cand == gapLo) joint = blockEndBuf[i];
                else if (cand + dur == blockStartBuf[i + 1]) joint = blockStartBuf[i + 1];
                if (dist < bestDist) { bestDist = dist; bestStart = cand; bestJoint = joint; }
            }
        }
        // (c) After the last block — always feasible, so a legal spot ALWAYS exists.
        long tailLo = blockEndBuf[blockCount - 1];
        long cand = Math.max(tailLo, desiredStart);
        long dist = Math.abs(cand - desiredStart);
        if (dist < bestDist) {
            bestStart = cand;
            bestJoint = (cand == tailLo) ? tailLo : Long.MIN_VALUE;
        }
        lastButtJointMs = bestJoint;
        return Math.max(0, bestStart);
    }

    /** Joint (ms) the last {@link #resolveNoOverlapStart} butted against, else MIN_VALUE. */
    private long lastButtJointMs = Long.MIN_VALUE;

    /**
     * Gentle butting SUGGESTION (dragux_v3 A3+A4): if {@code prospective} sits within
     * the snap radius of a legal butting position against any sibling on the landing
     * row (before its start or after its end), return that snapped start and publish
     * the joint via the bookend fields; else return {@link Long#MIN_VALUE} (no snap —
     * free placement). Unlike the overlap resolver this NEVER moves a far position;
     * it only "clicks in" when the user is already almost there.
     */
    private long nearestButtWithin(long prospective, long draggedDur, long totalMs, long thrMs) {
        Track row = hoverTargetTrack != null ? hoverTargetTrack : activeTrack;
        if (row == null || activeItem == null || draggedDur <= 0) return Long.MIN_VALUE;
        long bestStart = Long.MIN_VALUE, bestJoint = 0, bestDist = thrMs + 1;
        for (TimedItem sib : row.getItems()) {
            if (sib.getId().equals(activeItem.getId())) continue;
            long ss = sib.getTimelineStartMs();
            long se = ss + sib.getDisplayDurationMs(totalMs);
            long before = ss - draggedDur;   // our end butts the sibling's start
            long after = se;                  // our start butts the sibling's end
            if (before >= 0 && Math.abs(prospective - before) < bestDist) {
                bestDist = Math.abs(prospective - before);
                bestStart = before;
                bestJoint = ss;
            }
            if (Math.abs(prospective - after) < bestDist) {
                bestDist = Math.abs(prospective - after);
                bestStart = after;
                bestJoint = se;
            }
        }
        if (bestStart == Long.MIN_VALUE || bestDist > thrMs) return Long.MIN_VALUE;
        bookendJointMs = bestJoint;
        bookendSnapStartMs = bestStart;
        bookendAfter = bestStart >= bestJoint;
        return bestStart;
    }

    /** ms from the item's start to the finger's grab point, captured on first move. */
    private long moveGrabOffsetMs = -1;

    /**
     * Place the dragged item at an EXACT start (bookend/home snap) — no grab-offset math.
     *
     * @param closeOpenEnd bookend snaps pass true: an open-ended text item takes its
     *                     captured displayed length ({@link #dragStartDisplayDurMs}) so
     *                     the preview (and the drop) butts exactly end-to-end. The home
     *                     snap passes false — "exactly where you started" must preserve
     *                     the original open end.
     */
    private void applyMoveTo(long newStartMs, boolean closeOpenEnd) {
        TimedItem item = activeItem;
        if (item == null) return;
        if (item.getTextOverlay() != null) {
            TextOverlayItem o = item.getTextOverlay();
            long duration = dragStartDurationMsForMove(o);
            boolean openEnded = dragStartDurationMs == Long.MAX_VALUE;
            long end;
            if (openEnded) {
                end = closeOpenEnd && dragStartDisplayDurMs > 0
                        ? newStartMs + dragStartDisplayDurMs : Long.MAX_VALUE;
            } else {
                end = newStartMs + duration;
            }
            o.setTimeRange(newStartMs, end);
        } else if (item.getAudioClip() != null) {
            item.getAudioClip().setOffsetMs(newStartMs);
        }
    }

    /** Home/original-position snap state → renderer ghost highlight + throttled log. */
    private void setHomeSnapArmed(boolean armed) {
        if (armed != homeSnapArmed) {
            homeSnapArmed = armed;
        }
        rowRenderer.setHomeGhostArmed(armed);
    }

    /**
     * TRIM home snap (feedback 2026-07-03am): if the dragged edge is back within the
     * snap radius of its ORIGINAL position, restore the original extent exactly and
     * light the ghost — the user can see "release = original length", no undo needed.
     */
    private void maybeSnapTrimHome(boolean left, long thrMs) {
        TimedItem item = activeItem;
        if (item == null) return;
        boolean snapped = false;
        if (item.getTextOverlay() != null) {
            TextOverlayItem o = item.getTextOverlay();
            if (left) {
                if (Math.abs(o.getStartMs() - dragStartTextStartMs) <= thrMs) {
                    o.setTimeRange(dragStartTextStartMs, dragStartTextEndMs);
                    snapped = true;
                }
            } else if (dragStartTextEndMs == Long.MAX_VALUE) {
                // Original end was open-ended: compare against the DISPLAYED ghost end
                // and restore the open end on snap.
                if (dragStartDisplayDurMs > 0 && Math.abs(o.getEndMs()
                        - (dragStartTextStartMs + dragStartDisplayDurMs)) <= thrMs) {
                    o.setTimeRange(dragStartTextStartMs, Long.MAX_VALUE);
                    snapped = true;
                }
            } else if (Math.abs(o.getEndMs() - dragStartTextEndMs) <= thrMs) {
                o.setTimeRange(dragStartTextStartMs, dragStartTextEndMs);
                snapped = true;
            }
        } else if (item.getAudioClip() != null) {
            AudioClip ac = item.getAudioClip();
            if (left) {
                if (Math.abs(ac.getInPointMs() - audioBeforeInMs) <= thrMs) {
                    ac.setInPointMs(audioBeforeInMs);
                    snapped = true;
                }
            } else if (Math.abs(ac.getOutPointMs() - audioBeforeOutMs) <= thrMs) {
                ac.setOutPointMs(audioBeforeOutMs);
                snapped = true;
            }
        }
        setHomeSnapArmed(snapped);
    }

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
                // No-overlap law on trims (dragux_v3 A8 hardening 2026-07-04): the
                // left edge may not cross into a same-row sibling.
                long ourEnd = dragStartTextEndMs == Long.MAX_VALUE
                        ? Long.MAX_VALUE / 4 : dragStartTextEndMs;
                newStart = Math.max(newStart, trimSiblingFloor(newStart, ourEnd));
                newStart = Math.min(newStart, maxStart == Long.MAX_VALUE ? newStart : maxStart);
                o.setTimeRange(newStart, dragStartTextEndMs);
            } else {
                long newEnd = Math.max(dragStartTextStartMs + MIN_TEXT_DURATION_MS, targetTimeMs);
                newEnd = Math.min(newEnd, trimSiblingCeil(dragStartTextStartMs, newEnd));
                newEnd = Math.max(newEnd, dragStartTextStartMs + MIN_TEXT_DURATION_MS);
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
            // No-overlap law on AUDIO trims (PHASE-R R2, closing the f646bb9 gap: only
            // the TEXT branch got sibling clamps). An audio item's timeline extent is
            // [offsetMs, offsetMs + (out-in)] and offsetMs is FIXED during a trim, so
            // ANY duration growth (right handle raising out, OR left handle lowering in)
            // extends the item's END rightward and can cross a later same-row sibling.
            // Clamp growth at the sibling ceiling; never force below the gesture-start
            // extent (pre-existing overlaps must not un-trim, mirroring f646bb9), and
            // the 500ms minimum always wins (mirrors the text MIN_TEXT_DURATION rule).
            long offset = ac.getOffsetMs();
            long startExtentEnd = offset + (dragStartTrimOutMs - dragStartTrimInMs);
            if (left) {
                long newIn = Math.max(0, Math.min(dragStartTrimOutMs - AUDIO_MIN_TRIM_GAP_MS,
                        targetTimeMs - offset));
                long proposedEnd = offset + (dragStartTrimOutMs - newIn);
                long ceil = Math.max(trimSiblingCeil(offset, proposedEnd), startExtentEnd);
                newIn = Math.max(newIn, offset + dragStartTrimOutMs - ceil);
                newIn = Math.min(newIn, dragStartTrimOutMs - AUDIO_MIN_TRIM_GAP_MS);
                ac.setInPointMs(Math.max(0, newIn));
            } else {
                long newOut = Math.max(dragStartTrimInMs + AUDIO_MIN_TRIM_GAP_MS,
                        Math.min(srcDur, targetTimeMs - offset));
                long proposedEnd = offset + (newOut - dragStartTrimInMs);
                long ceil = Math.max(trimSiblingCeil(offset, proposedEnd), startExtentEnd);
                newOut = Math.min(newOut, ceil - offset + dragStartTrimInMs);
                newOut = Math.max(newOut, dragStartTrimInMs + AUDIO_MIN_TRIM_GAP_MS);
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
    public boolean onRowBodyUp(boolean committed) {
        if (!active) return false;
        // A real committed change only happened if we actually moved (trim, or a
        // picked-up move). A body touch that lifted before pickup (pendingBodyDown still
        // set) is a TAP — selection already happened on DOWN, nothing to record.
        boolean wasMoved = movedDuringGesture && (activeKind != GestureKind.MOVE || pickupArmed);
        // Review fix 2026-07-03 (CRITICAL): an INTERRUPTED gesture (ACTION_CANCEL,
        // pinch second finger, parent intercept — anything but a deliberate finger
        // lift) must ABORT, not commit. Without this, whatever hover state the last
        // MOVE latched (incl. the cross-band/new-layer arm) was committed by the
        // interruption — silently creating a new lane the user never released into.
        // A cancel now restores the item to its exact gesture-start state and records
        // nothing.
        if (!committed && wasMoved) {
            revertActiveItemToGestureStart();
            wasMoved = false;
        }
        TimedItem item = activeItem;
        Track fromTrack = activeTrack;
        Track toTrack = hoverTargetTrack;
        // A release while hovering the other band commits the SAME new-lane drop as the
        // pinned zone (user feedback 2026-07-03) — the lane is created band-correctly by
        // the activity (fromTrack's kind), so a visual item dropped "on the audio" lands
        // on a new visual lane above the audio band, exactly what the insertion line
        // promised.
        boolean droppedOnNewLayerZone = hoverNewLayerZone || hoverCrossBandNewLane;
        boolean wasTap = pendingBodyDown && !movedDuringGesture;
        boolean wasDeleteTap = wasTap && pendingDeleteBadge;
        // Captured BEFORE the reset block below wipes them (commit-time overlap guard
        // + open-endedness restoration).
        long commitDur = dragStartDisplayDurMs;
        boolean commitWasMoveKind = activeKind == GestureKind.MOVE;
        boolean commitWasOpenEnded = dragStartDurationMs == Long.MAX_VALUE;
        boolean commitHomeSnapped = homeSnapArmed;
        pendingDeleteBadge = false;
        active = false;
        activeItem = null;
        activeTrack = null;
        moveGrabOffsetMs = -1;
        dragStartDurationMs = 0;
        pendingBodyDown = false;
        pickupArmed = false;
        hoverTargetTrack = null;
        hoverNewLayerZone = false;
        hoverCrossBandNewLane = false;
        lastRejectedRowId = null;
        bookendJointMs = Long.MIN_VALUE;
        suppressMoveMapping = false;
        hoveringHomeRow = false;
        homeSnapArmed = false;
        dragStartDisplayDurMs = 0;
        rowRenderer.setDragTargetTrackId(null);
        rowRenderer.setLiftedItemId(null);
        rowRenderer.setTrimmingItemId(null);
        rowRenderer.setHomeGhost(null, 0, 0);
        rowRenderer.setHomeGhostArmed(false);
        rowRenderer.setCrossBandInsertionArmed(false, true);
        rowRenderer.setTimeLockGuides(false, 0, 0);
        rowRenderer.setDragOutlineState(LayerRowRenderer.DRAG_OUTLINE_NONE);
        if (wasMoved && item != null) {
            // COMMIT-TIME NO-OVERLAP GUARANTEE (dragux_v3 A8 hardening, user repro
            // 2026-07-04: dropping onto a row with two butted items could land
            // overlapping despite the live-preview resolver — preview state can race
            // the final hover). Whatever the preview showed, the DROP re-resolves the
            // item's start against the actual destination row's siblings; overlap can
            // never persist past a release. New-layer drops skip it (empty row).
            if (!droppedOnNewLayerZone && commitWasMoveKind && commitDur > 0) {
                Track dest = toTrack != null ? toTrack : fromTrack;
                if (dest != null) {
                    long cur = item.getTimelineStartMs();
                    long fixed = resolveOverlapOnRow(dest, item, cur, commitDur);
                    if (fixed != cur) {
                        applyCommittedStart(item, fixed);
                    }
                }
            }
            // OPEN-ENDEDNESS RESTORATION (pairs with the honest closed-length drag
            // preview): a MOVE of an originally open-ended text item re-opens its end
            // at DROP when that is legal — home snap always restores the original open
            // end; elsewhere only if NO sibling on the landing row starts at/after the
            // item's new start (an open end would run over it otherwise — the law wins).
            if (commitWasMoveKind && commitWasOpenEnded && item.getTextOverlay() != null) {
                TextOverlayItem o = item.getTextOverlay();
                boolean reopen;
                if (commitHomeSnapped) {
                    reopen = true;
                } else {
                    Track dest = droppedOnNewLayerZone ? null : (toTrack != null ? toTrack : fromTrack);
                    reopen = true;
                    if (dest != null) {
                        long myStart = item.getTimelineStartMs();
                        for (TimedItem sib : dest.getItems()) {
                            if (sib.getId().equals(item.getId())) continue;
                            if (sib.getTimelineStartMs() >= myStart) { reopen = false; break; }
                        }
                    }
                }
                if (reopen && o.getEndMs() != Long.MAX_VALUE) {
                    o.setTimeRange(Math.max(0, o.getStartMs()), Long.MAX_VALUE);
                }
            }
            // M10: report the track-change FIRST (see method doc) so the activity can
            // fold it into the ONE undo action onGestureFinished below builds.
            if (droppedOnNewLayerZone && fromTrack != null) {
                callback.onItemDroppedOnNewLayer(item, fromTrack);
            } else if (toTrack != null && fromTrack != null) {
                callback.onItemMovedToTrack(item, fromTrack, toTrack);
            }
            callback.onGestureFinished(item, activeKind);
        } else if (wasDeleteTap && committed && item != null && fromTrack != null) {
            // Deferred delete-badge tap (review fix 2026-07-03): the badge no longer
            // fires on DOWN — a clean tap on it resolves HERE, on the committed UP,
            // after scrub/pickup/scroll have all been ruled out.
            callback.onItemDeleteRequested(fromTrack, item);
        }
        return true;
    }

    /** Lowest legal start for a row-system LEFT trim (may not cross a same-row sibling). */
    private long trimSiblingFloor(long proposedStart, long ourEnd) {
        if (activeTrack == null || activeItem == null) return proposedStart;
        long floor = proposedStart;
        for (TimedItem sib : activeTrack.getItems()) {
            if (sib.getId().equals(activeItem.getId())) continue;
            long ss = sib.getTimelineStartMs();
            long se = ss + Math.max(0, sib.getDisplayDurationMs(Long.MAX_VALUE / 4));
            if (ss < ourEnd && se > proposedStart) floor = Math.max(floor, se);
        }
        return floor;
    }

    /** Highest legal end for a row-system RIGHT trim (may not cross a same-row sibling). */
    private long trimSiblingCeil(long ourStart, long proposedEnd) {
        if (activeTrack == null || activeItem == null) return proposedEnd;
        long ceil = proposedEnd;
        for (TimedItem sib : activeTrack.getItems()) {
            if (sib.getId().equals(activeItem.getId())) continue;
            long ss = sib.getTimelineStartMs();
            long se = ss + Math.max(0, sib.getDisplayDurationMs(Long.MAX_VALUE / 4));
            if (se > ourStart && ss < proposedEnd) ceil = Math.min(ceil, ss);
        }
        return ceil;
    }

    /**
     * Stateless overlap resolver for the COMMIT-TIME guard in {@link #onRowBodyUp} — the
     * LAST line of defence that guarantees the PERSISTED state never overlaps, whatever
     * the live preview showed. Routes through the same overlap-PROOF
     * {@link #nearestFreeStart} the live drag uses (the old duplicate 4-pass loop had the
     * same oscillate-and-give-up-overlapping bug — user repro 2026-07-05). Duration comes
     * from the pre-reset captured display duration.
     */
    private long resolveOverlapOnRow(@NonNull Track row, @NonNull TimedItem moved,
                                     long desiredStart, long dur) {
        // Same side preference as the live preview (S5) so the commit can never land on
        // a different side than the WYSIWYG outline forecast (the guard normally no-ops:
        // the live position is already legal, so the resolver returns it unchanged).
        return nearestFreeStart(row, moved.getId(), desiredStart, dur, Long.MAX_VALUE / 4,
                fingerSidePref);
    }

    /** Apply a commit-time corrected start to {@code item}, preserving duration + open end. */
    private static void applyCommittedStart(@NonNull TimedItem item, long newStartMs) {
        if (item.getTextOverlay() != null) {
            TextOverlayItem o = item.getTextOverlay();
            long oldStart = Math.max(0, o.getStartMs());
            long oldEnd = o.getEndMs();
            long newEnd = (oldEnd == Long.MAX_VALUE) ? Long.MAX_VALUE
                    : newStartMs + Math.max(0, oldEnd - oldStart);
            o.setTimeRange(newStartMs, newEnd);
        } else if (item.getAudioClip() != null) {
            item.getAudioClip().setOffsetMs(newStartMs);
        }
    }

    /**
     * Restore the active item to its exact gesture-start state — the abort path for an
     * INTERRUPTED touch stream (see the CANCEL block in {@link #onRowBodyUp}). Uses the
     * same snapshots armMove/armTrim captured for undo, so the restore is exact.
     */
    private void revertActiveItemToGestureStart() {
        TimedItem item = activeItem;
        if (item == null) return;
        if (activeKind == GestureKind.MOVE) {
            if (item.getTextOverlay() != null) {
                TextOverlayItem o = item.getTextOverlay();
                long duration = dragStartDurationMsForMove(o);
                long end = (o.getEndMs() == Long.MAX_VALUE) ? Long.MAX_VALUE
                        : dragStartTimelineMs + duration;
                o.setTimeRange(dragStartTimelineMs, end);
            } else if (item.getAudioClip() != null) {
                item.getAudioClip().setOffsetMs(audioBeforeOffsetMs);
            }
        } else {
            if (item.getTextOverlay() != null) {
                item.getTextOverlay().setTimeRange(dragStartTextStartMs, dragStartTextEndMs);
            } else if (item.getAudioClip() != null) {
                AudioClip ac = item.getAudioClip();
                ac.setInPointMs(audioBeforeInMs);
                ac.setOutPointMs(audioBeforeOutMs);
            }
        }
        callback.onGestureLive(item); // refresh preview/timeline with the restored state
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
