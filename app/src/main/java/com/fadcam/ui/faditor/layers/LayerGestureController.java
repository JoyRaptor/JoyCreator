package com.fadcam.ui.faditor.layers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
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
 * confirmation-dialog pattern ({@code deleteTextOverlayWithConfirmation}/{@code deleteSelectedAudioClip}).</p>
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
         * A MOVE gesture on {@code item} ended armed on a NEW-LAYER target: a gap
         * between floating rows (Slice 2 gap-insertion — {@code insertionIndex} =
         * gap index, 0 = above the top row, rowCount = below the bottom row) or the
         * cross-band arm ({@code insertionIndex} = {@link Integer#MAX_VALUE} =
         * append at the band's bottom, the pre-Slice-2 semantics). Fires BEFORE
         * {@link #onGestureFinished} for the same reason as
         * {@link #onItemMovedToTrack} above. The activity creates a new persistent
         * track (matching {@code fromTrack}'s band/kind) AT that visual position
         * (z renumber), reassigns the item's {@code layerId} to it immediately, and
         * stages the undo/redo halves for {@code onGestureFinished} to fold into
         * one action.
         */
        void onItemDroppedOnNewLayer(@NonNull TimedItem item, @NonNull Track fromTrack,
                                     int insertionIndex);

        /**
         * The user DOUBLE-TAPPED {@code item} — two quick taps on the SAME item within the
         * double-tap window (gesture contract §1: double-tap = the express lane to that
         * object's POWER-TOOLS DRAWER / type editor). Fires from the SECOND tap's UP
         * resolution in {@link LayerGestureController#onRowBodyUp}; the item is already
         * SELECTED (selection happens on the first tap's DOWN, unchanged). The activity
         * routes to the per-type editor (text/image → text editor, sprite → sprite palette,
         * …). A single tap NEVER fires this — it only selects.
         */
        default void onItemDoubleTapped(@NonNull TimedItem item) {}

        /**
         * The user HELD {@code item} (pickup armed — it lifted, with haptic) and then
         * RELEASED it in place WITHOUT crossing the move slop (gesture contract §1:
         * hold → release-in-place = open the GENERAL ADVANCED MENU / object options sheet,
         * §2). Distinct from a MOVE (finger crossed the slop → commit) and from a CANCEL
         * (interrupted stream → abort, no menu). Fires from {@link
         * LayerGestureController#onRowBodyUp} on a committed release. The activity opens
         * its per-type options menu (text/image → the layer-item actions dialog, …).
         */
        default void onItemMenuRequested(@NonNull Track track, @NonNull TimedItem item) {}

        /**
         * The row-item SELECTION changed (gesture contract §1: tap = select — outline the
         * row bar AND spawn manipulation handles in the PREVIEW, G4). Fires whenever
         * {@code selectedItemId} actually transitions: a body-down on a different item
         * ({@code item} non-null, with its {@code track}), a body-down on empty row space,
         * or {@link LayerGestureController#clearSelection} ({@code item}/{@code track}
         * null). NOT re-fired on a body-down on the already-selected item. The activity
         * shows/hides the preview manipulation-handles overlay from here; badge/outline
         * rendering is unaffected (the renderer keeps reading {@code selectedItemId}
         * directly).
         */
        default void onItemSelectionChanged(@Nullable Track track, @Nullable TimedItem item) {}

        /**
         * C4 §2: a keyframe TIME-SHIFT drag on {@code item}'s consolidated row diamond is
         * about to begin (the item is the current selection; the finger landed on a
         * diamond). The activity snapshots the item's transform/keyframe state here for the
         * ONE undo step committed on finger-up — mirrors the drawer sliders'
         * {@code onSliderStart}. Never fires for non-overlay/non-sprite payloads.
         */
        default void onItemKeyframeShiftBegin(@NonNull TimedItem item) {}

        /**
         * C4 §2: the keyframe time-shift drag on {@code item} ended on finger-up — the
         * activity records ONE undo step against the snapshot taken in
         * {@link #onItemKeyframeShiftBegin} (a no-op when nothing actually moved, e.g. a tap
         * or a fully-clamped drag). Always paired with a prior begin.
         */
        default void onItemKeyframeShiftCommitted(@NonNull TimedItem item) {}
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

    /**
     * Movement slop (RAW px) that promotes a picked-up touch to a real MOVE — i.e. how far
     * the finger may drift after a pickup/trim-grab before it counts as a drag rather than a
     * "released in place" (which opens the object menu — gesture contract §1). This was a
     * hardcoded {@code 4f} RAW px, which is ~1.5dp on the Note 9 and even TIGHTER (~1.1dp) on
     * higher-DPI phones — smaller than normal finger jitter during a hold, so a hold→release
     * frequently registered a micro-move and the object menu "worked only sometimes." Now
     * dp-scaled by the view (see {@link #setMoveSlopPx}); default stays at the old raw value
     * for any caller that never wires the setter.
     */
    private float moveSlopPx = 4f;

    /** See {@link #moveSlopPx} — the view calls this once with {@code MOVE_SLOP_DP * density}. */
    public void setMoveSlopPx(float px) { if (px > 0f) moveSlopPx = px; }

    /**
     * Trim-grab start slop (RAW px), kept at the historical value. The enlarged, dp-scaled
     * {@link #moveSlopPx} exists to make "released in place → open the object menu" reliable,
     * which is a MOVE/pickup-path concern. TRIM has no such menu and maps the edge to the
     * finger's ABSOLUTE time, so enlarging ITS start-slop would add a dead-zone-then-catch-up
     * lurch at trim start (found by adversarial self-review of the #7 fix). Keeping TRIM here
     * leaves trim feel byte-for-byte as it was before that fix.
     */
    private static final float TRIM_START_SLOP_PX = 4f;

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
    private long clipBeforeStartMs, clipBeforeInMs, clipBeforeOutMs;

    /** Item id selected for trim-handle exposure (mirrors the audio/overlay "selected → handles show" convention). */
    @Nullable private String selectedItemId;

    // ── G1: double-tap detection (gesture contract §1 — double-tap = open type editor) ──
    /** Item id of the last resolved single TAP awaiting a possible second tap, or null. */
    @Nullable private String lastTapItemId;
    /** {@code SystemClock.uptimeMillis()} of the last resolved TAP's UP (pairs with {@link #lastTapItemId}). */
    private long lastTapUpMs;
    /**
     * Max ms between two taps' UPs to count as a double-tap. Measured UP-to-UP (a real
     * double-tap's second UP lands ~200ms after the first); kept tight so two deliberate
     * single taps on the same item don't accidentally pair.
     */
    private static final long DOUBLE_TAP_WINDOW_MS = 320;

    // ── M10: cross-row drag-target tracking (MOVE gestures only) ───────────────
    /** Row the active MOVE gesture is currently hovering, or null (own row / no valid target). */
    @Nullable private Track hoverTargetTrack;
    /** Slice 2: armed gap index (0..floatingRowCount) while hovering a between-rows
     *  new-layer target; -1 = none. Replaces the retired pinned-zone boolean. */
    private int hoverGapIndex = -1;
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

    // ── C4 §2: keyframe time-shift drag (selected item's consolidated row diamond) ──
    /** True while a consolidated-diamond drag is moving keys in time. Routed like a TRIM
     *  (ARMED on DOWN, drag → onRowBodyMove, commit → onRowBodyUp) but handled by its own
     *  branches; never sets pickupArmed so it can't fight pickup/scrub/excursion. */
    private boolean kfShiftActive = false;
    private TimedItem kfShiftItem;
    /** Content-x at DOWN; the finger→local-time reference is resolved lazily on first move. */
    private float kfShiftDownX;
    private long kfShiftStartFingerLocalMs = Long.MIN_VALUE;
    private boolean kfShiftMoved = false;
    private final java.util.List<KfMovingKey> kfShiftKeys = new java.util.ArrayList<>();

    /** One property key caught in the dragged bucket, with its per-track legal delta window. */
    private static final class KfMovingKey {
        final com.fadcam.ui.faditor.keyframe.KeyframeTrack track;
        final long origTime;
        final float value;
        final com.fadcam.ui.faditor.keyframe.Easing easing;
        final long deltaMin, deltaMax; // item-local, from the REMAINING (non-moving) neighbors
        long curTime;
        KfMovingKey(@NonNull com.fadcam.ui.faditor.keyframe.KeyframeTrack track, long origTime,
                    float value, @NonNull com.fadcam.ui.faditor.keyframe.Easing easing,
                    long deltaMin, long deltaMax) {
            this.track = track; this.origTime = origTime; this.value = value; this.easing = easing;
            this.deltaMin = deltaMin; this.deltaMax = deltaMax; this.curTime = origTime;
        }
    }

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

    public void clearSelection() {
        boolean had = selectedItemId != null;
        selectedItemId = null;
        if (had) callback.onItemSelectionChanged(null, null);
    }

    /**
     * Programmatic selection, for ops that REPLACE the selected item's identity outside
     * a touch gesture (split creates two new ids; undo/redo swaps objects). Without this,
     * {@code selectedItemId} keeps pointing at the removed id and every selection-derived
     * op (delete/split/volume…) silently falls back to its legacy master-clip target.
     * Fires {@link Callback#onItemSelectionChanged} exactly like a tap-select would.
     */
    public void setSelectedItem(@Nullable Track track, @Nullable TimedItem item) {
        String newId = item == null ? null : item.getId();
        boolean changed = !java.util.Objects.equals(newId, selectedItemId);
        selectedItemId = newId;
        if (changed) callback.onItemSelectionChanged(track, item);
    }

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
        lastTotalMs = totalMs;
        LayerRowRenderer.ItemHit hit = rowRenderer.hitTestItem(x, y, topPx, totalMs, timeToX, selectedItemId);
        if (hit == null) {
            boolean hadSelection = selectedItemId != null;
            selectedItemId = null;
            active = false;
            pendingBodyDown = false;
            pendingDeleteBadge = false;
            pickupArmed = false;
            lastTapItemId = null; // an empty-space touch breaks any pending double-tap pairing
            if (hadSelection) callback.onItemSelectionChanged(null, null);
            return DownResult.MISS;
        }
        activeTrack = hit.track;
        activeItem = hit.item;
        dragStartX = x;
        dragStartY = y;
        movedDuringGesture = false;
        pickupArmed = false;
        pendingBodyDown = false;
        lockedHoldRefused = false;
        // Delete badge (review fix 2026-07-03): DEFERRED to a tap-on-UP instead of firing
        // on DOWN. A DOWN on the badge routes exactly like a body hit (PENDING) with this
        // flag set: a quick lift within slop = the delete tap (confirmation fires in
        // onRowBodyUp); a horizontal swipe from the badge = SCRUB; a long-press = pickup.
        // Firing on DOWN hijacked swipes that happened to start on the (viewport-pinned)
        // badge with a blocking dialog — the contract says swipe must always scrub.
        // §4.5 per-OBJECT lock: a locked object stays SELECTABLE (otherwise it could
        // never be unlocked via its drawer) but every mutating zone is refused — the
        // delete badge, trim handles, and keyframe shift below all no-op, and
        // beginPickup() refuses so it can never move.
        boolean objectLocked = isObjectLocked(hit.item);
        pendingDeleteBadge = !objectLocked && hit.zone == LayerRowRenderer.ItemZone.DELETE;
        hoverTargetTrack = null;
        hoverGapIndex = -1;
        rowRenderer.setHoverGapIndex(-1);
        lastLoggedHoverRow = null;
        rowRenderer.setDragTargetTrackId(null);
        rowRenderer.setProxyRowTrackId(null);
        rowRenderer.setProxyItem(null);

        if (!objectLocked && (hit.zone == LayerRowRenderer.ItemZone.LEFT_HANDLE
                || hit.zone == LayerRowRenderer.ItemZone.RIGHT_HANDLE)) {
            boolean left = hit.zone == LayerRowRenderer.ItemZone.LEFT_HANDLE;
            armTrim(hit.item, left);
            rowRenderer.setTrimmingItemId(hit.item.getId()); // timeline-locked stripe feedback
            active = true;
            activeKind = left ? GestureKind.TRIM_LEFT : GestureKind.TRIM_RIGHT;
            return DownResult.ARMED_TRIM;
        }

        // C4 §2: a horizontal drag starting on a consolidated keyframe diamond of the
        // ALREADY-SELECTED item MOVES that key in time — the ONE edit the drawer can't do
        // well. Only for the CURRENT selection (checked before selectedItemId is reassigned
        // below), so a first touch on an unselected item keeps plain select/pickup/scrub.
        // Routes like a trim (ARMED_TRIM → view drives onRowBodyMove/onRowBodyUp here); the
        // diamond-only hit means it never competes with body pickup/scrub.
        if (!objectLocked && hit.item.getId().equals(selectedItemId)
                && tryArmKeyframeShift(hit, x, timeToX)) {
            return DownResult.ARMED_TRIM;
        }

        // Body hit: SELECT immediately (tap-select feel; also exposes trim handles), but
        // DO NOT arm a move and DO NOT start a delete long-press. Whether this becomes a
        // tap, a scrub, a row-scroll, or a pick-up-for-move is the caller's decision from
        // the follow-up events. active=true so isMoveDragActive()/onRowBodyUp() have a
        // consistent lifecycle, but activeKind stays MOVE only as the *potential* kind;
        // movedDuringGesture stays false until beginPickup().
        boolean selectionChanged = !hit.item.getId().equals(selectedItemId);
        selectedItemId = hit.item.getId();
        active = true;
        pendingBodyDown = true;
        activeKind = GestureKind.MOVE;
        if (selectionChanged) callback.onItemSelectionChanged(hit.track, hit.item);
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
    /** §4.5 per-OBJECT lock query (audio's lock lives on the AudioClip itself). */
    private static boolean isObjectLocked(@NonNull TimedItem item) {
        if (item.getTextOverlay() != null) return item.getTextOverlay().isLocked();
        if (item.getSprite() != null) return item.getSprite().isLocked();
        if (item.getWaveform() != null) return item.getWaveform().isLocked();
        if (item.getAudioClip() != null) return item.getAudioClip().isLocked();
        if (item.getClip() != null && item.getClip().isOverlayClip()) {
            return item.getClip().isLockedObject();
        }
        return false;
    }

    /** §4.5: the hold fired on a LOCKED object — no lift, but the release-in-place
     *  must still open the drawer (it holds the Unlock action). */
    private boolean lockedHoldRefused;

    public boolean beginPickup() {
        if (!active || !pendingBodyDown || activeItem == null) return false;
        // §4.5: a locked object never lifts — remember the refusal so onRowBodyUp's
        // hold-release-in-place branch still opens the general drawer.
        if (isObjectLocked(activeItem)) {
            lockedHoldRefused = true;
            // Clear pendingBodyDown too, exactly as the non-locked path below does. Without
            // this, onRowBodyUp still computes wasTap = pendingBodyDown && !moved = TRUE, and
            // its wasTap branch is tested BEFORE the holdReleaseInPlace branch — so the menu
            // this refusal exists to open was unreachable, and since Unlock lives only in
            // that menu, locking an object from the row made it permanently locked.
            pendingBodyDown = false;
            return false;
        }
        pendingBodyDown = false;
        pickupArmed = true;
        activeKind = GestureKind.MOVE;
        armMove(activeItem);
        rowRenderer.setLiftedItemId(activeItem.getId());
        // SPLIT-ELEMENT FIX: register the ONE proxy. proxyRowTrackId == null means "draw
        // on the item's own/home row" (it starts on its home row); the renderer draws the
        // single coherent body there, and moves it to the hovered target row as
        // updateDragTarget flips proxyRowTrackId below.
        rowRenderer.setProxyItem(activeItem);
        rowRenderer.setProxyRowTrackId(null);
        rowRenderer.setDragOutlineState(LayerRowRenderer.DRAG_OUTLINE_SAME_ROW);
        rowGestureLog("pickup item=" + activeItem.getId() + " row=" + activeTrack.getId()
                + " startMs=" + dragStartTimelineMs);
        return true;
    }

    /**
     * §3A.5b — ADOPT an in-flight touch that began on the MASTER TRACK, after a dislodge has
     * already demoted that clip into {@code track} as {@code item}.
     *
     * <p><b>Why adoption rather than a second drag engine.</b> The dislodge starts life as a spine
     * gesture, so it never went through {@link #onRowBodyDown} and has no pending body touch for
     * {@link #beginPickup} to promote — but the finger is still down and the user is still moving.
     * Everything that should happen from here (magnet suppression, WYSIWYG drop, edge auto-pan,
     * minimap nav, the overlap resolver, the undo merge) already exists on this path and only on
     * this path. Duplicating it for dislodged clips would mean two implementations of the same
     * interaction, diverging on the first bug fixed in one of them.</p>
     *
     * <p>Deliberately does NOT go through {@code beginPickup}'s lock check: a clip that just left
     * the spine cannot be a locked overlay object — it was not an overlay object a moment ago.</p>
     *
     * <p>{@code moveGrabOffsetMs} is left at its armed −1 so the FIRST move after adoption
     * establishes the grab point from where the finger actually is. Seeding it here from the
     * dislodge's touch-down x would grab at the point the press STARTED, and the finger has since
     * travelled the vertical commit distance — the clip would visibly jump sideways on adoption.</p>
     *
     * @return true if the drag was adopted and the caller should now route MOVEs to
     *         {@link #onRowBodyMove}; false if the arguments do not describe a live item.
     */
    public boolean adoptDrag(@Nullable Track track, @Nullable TimedItem item) {
        if (track == null || item == null) return false;
        active = true;
        pendingBodyDown = false;
        pickupArmed = true;
        lockedHoldRefused = false;
        activeKind = GestureKind.MOVE;
        activeTrack = track;
        activeItem = item;
        selectedItemId = item.getId();
        // The gesture is ALREADY a move — the vertical pull that dislodged it was the movement.
        // Leaving this false makes onRowBodyUp treat the release as a TAP and open the drawer
        // instead of committing the drop.
        movedDuringGesture = true;
        armMove(item);
        rowRenderer.setLiftedItemId(item.getId());
        rowRenderer.setProxyItem(item);
        rowRenderer.setProxyRowTrackId(null);
        rowRenderer.setDragOutlineState(LayerRowRenderer.DRAG_OUTLINE_SAME_ROW);
        rowGestureLog("adopt item=" + item.getId() + " row=" + track.getId()
                + " startMs=" + dragStartTimelineMs);
        return true;
    }

    /**
     * The item currently being dragged, or null. §3A.4 needs it to answer "is this payload even
     * legal on the spine?" before promising a landing spot — a text overlay must never be offered
     * an insertion the drop would then refuse.
     */
    @Nullable
    public TimedItem getActiveItem() { return activeItem; }

    /**
     * §3A.4 — while true, this controller claims NO drop target: the master track owns the
     * gesture and is drawing the seam indicator itself. Set per move event by the view.
     */
    public void setSpineHoverSuppressed(boolean s) { spineHoverSuppressed = s; }

    private boolean spineHoverSuppressed;

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
        } else if (item.getClip() != null && item.getClip().isOverlayClip()) {
            clipBeforeStartMs = item.getClip().getOverlayStartMs();
            clipBeforeInMs = item.getClip().getInPointMs();
            clipBeforeOutMs = item.getClip().getOutPointMs();
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
        } else if (item.getClip() != null && item.getClip().isOverlayClip()) {
            clipBeforeStartMs = item.getClip().getOverlayStartMs();
            clipBeforeInMs = item.getClip().getInPointMs();
            clipBeforeOutMs = item.getClip().getOutPointMs();
            dragStartTrimInMs = item.getClip().getInPointMs();
            dragStartTrimOutMs = item.getClip().getOutPointMs();
        }
    }

    /**
     * C4 §2: try to arm a keyframe time-shift on a BODY down that landed near a consolidated
     * diamond. Collects every property key inside the ~66ms bucket (the consolidated diamond
     * IS the union) and precomputes each key's legal delta window from its REMAINING
     * neighbors (strictly between them; never below 0 item-local). Returns false (leaving the
     * normal select/pickup path) unless a real bucket was grabbed.
     */
    private boolean tryArmKeyframeShift(@NonNull LayerRowRenderer.ItemHit hit, float x,
                                        @NonNull LayerRowRenderer.TimeToX timeToX) {
        if (hit.zone != LayerRowRenderer.ItemZone.BODY) return false;
        com.fadcam.ui.faditor.keyframe.KeyframeSet set = LayerRowRenderer.keyframeSetOf(hit.item);
        if (set == null) return false;
        Long bucket = rowRenderer.hitTestKeyframeDiamond(hit.item, x, timeToX);
        if (bucket == null) return false;
        kfShiftKeys.clear();
        for (com.fadcam.ui.faditor.keyframe.KeyframeTrack t : set.tracks()) {
            com.fadcam.ui.faditor.keyframe.Keyframe moving = null;
            for (com.fadcam.ui.faditor.keyframe.Keyframe k : t.keyframes) {
                if (Math.abs(k.timeMs - bucket) <= LayerRowRenderer.KF_CONSOLIDATE_TOLERANCE_MS) {
                    moving = k; break;
                }
            }
            if (moving == null) continue;
            long prev = Long.MIN_VALUE, next = Long.MAX_VALUE;
            for (com.fadcam.ui.faditor.keyframe.Keyframe k : t.keyframes) {
                if (k == moving) continue;
                if (k.timeMs < moving.timeMs && k.timeMs > prev) prev = k.timeMs;
                if (k.timeMs > moving.timeMs && k.timeMs < next) next = k.timeMs;
            }
            // Floor for the FIRST key of a track. Not a bare 0: a PiP's transform keys are in
            // ABSOLUTE timeline ms, so "the earliest legal time" for one is its own start, not
            // the start of the project. Dragging with a 0 floor would let a PiP's first key slide
            // back before the clip exists — the same off-span state the diamond now refuses.
            long floor = LayerRowRenderer.earliestLegalKeyTimeMs(hit.item);
            long lo = (prev == Long.MIN_VALUE) ? floor : prev + 1;            // ≥floor, strictly > prev
            long dMin = lo - moving.timeMs;
            long dMax = (next == Long.MAX_VALUE) ? Long.MAX_VALUE : (next - 1) - moving.timeMs; // strictly < next
            kfShiftKeys.add(new KfMovingKey(t, moving.timeMs, moving.value, moving.easing, dMin, dMax));
        }
        if (kfShiftKeys.isEmpty()) return false;
        kfShiftActive = true;
        kfShiftItem = hit.item;
        kfShiftDownX = x;
        kfShiftStartFingerLocalMs = Long.MIN_VALUE;
        kfShiftMoved = false;
        // Consistent lifecycle so onRowBodyUp is reached; the kfShift branches own it.
        active = true;
        activeItem = hit.item;
        activeTrack = hit.track;
        callback.onItemKeyframeShiftBegin(hit.item);
        return true;
    }

    /** C4 §2 drag: shift the whole bucket by ONE clamped delta (keeps the union coherent). */
    private void doKeyframeShiftMove(float x, @NonNull XToTime xToTime) {
        if (kfShiftItem == null) return;
        long itemStart = kfShiftItem.getTimelineStartMs();
        if (kfShiftStartFingerLocalMs == Long.MIN_VALUE) {
            kfShiftStartFingerLocalMs = xToTime.map(kfShiftDownX) - itemStart;
        }
        long reqDelta = (xToTime.map(x) - itemStart) - kfShiftStartFingerLocalMs;
        // Tightest window across all moving keys — no key crosses its neighbors or goes < 0.
        long dMin = Long.MIN_VALUE, dMax = Long.MAX_VALUE;
        for (KfMovingKey mk : kfShiftKeys) {
            if (mk.deltaMin > dMin) dMin = mk.deltaMin;
            if (mk.deltaMax < dMax) dMax = mk.deltaMax;
        }
        // Disjoint per-track windows inside one bucket (pathological ~66ms overlap of
        // differently-neighbored tracks) → no legal shared delta; hold still.
        long delta = dMin > dMax ? 0L : Math.max(dMin, Math.min(dMax, reqDelta));
        boolean any = false;
        for (KfMovingKey mk : kfShiftKeys) {
            long nt = mk.origTime + delta;
            if (nt != mk.curTime) {
                mk.track.removeAt(mk.curTime);
                mk.track.put(nt, mk.value, mk.easing);
                mk.curTime = nt;
                any = true;
            }
        }
        if (any) {
            kfShiftMoved = true;
            callback.onGestureLive(kfShiftItem);
        }
    }

    /** C4 §2 finish: on a real UP commit ONE undo step; on CANCEL restore the original times. */
    private boolean finishKeyframeShift(boolean committed) {
        TimedItem item = kfShiftItem;
        if (!committed && kfShiftMoved) {
            for (KfMovingKey mk : kfShiftKeys) {
                if (mk.curTime != mk.origTime) {
                    mk.track.removeAt(mk.curTime);
                    mk.track.put(mk.origTime, mk.value, mk.easing);
                    mk.curTime = mk.origTime;
                }
            }
        }
        kfShiftActive = false;
        kfShiftItem = null;
        kfShiftKeys.clear();
        kfShiftStartFingerLocalMs = Long.MIN_VALUE;
        kfShiftMoved = false;
        active = false;
        activeItem = null;
        activeTrack = null;
        // Always report so the activity records (no-op when unchanged) and clears its snapshot.
        if (item != null) callback.onItemKeyframeShiftCommitted(item);
        return true;
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
        lastTotalMs = totalMs;
        if (kfShiftActive) { doKeyframeShiftMove(x, xToTime); return; }
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
        // #7 correction: the enlarged dp-scaled slop applies to the MOVE/pickup path ONLY
        // (its purpose is the hold-release-in-place menu). TRIM keeps the historical raw slop
        // so the #7 fix doesn't add a start-lurch to trims (they map the edge absolutely).
        float startSlop = (activeKind == GestureKind.MOVE) ? moveSlopPx : TRIM_START_SLOP_PX;
        if (!movedDuringGesture
                && (Math.abs(x - dragStartX) > startSlop || Math.abs(y - dragStartY) > startSlop)) {
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
                    // #loosen (user 2026-07-27): the lane-change time-lock was too sticky — you
                    // could snap to a lane but not flow diagonally into it. Release the lock with a
                    // SMALLER sideways move (half the snap radius) so "snap to the lane, then a
                    // little side-to-side starts sliding in that lane" just works. Tunable.
                    long timeLockThrMs = Math.max(1, snapThrMs / 2);
                    if (hoverTargetTrack != null
                            && Math.abs(prospective - dragStartTimelineMs) <= timeLockThrMs) {
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
        // ROWGESTURE per-move (TEMP): finger x/y, resolved model start, the row the proxy
        // is drawn on, and butt/lock state — one line per move so the hand-test + logcat
        // reads exactly what the single proxy did each frame (resolved vs finger).
        if (ROWGESTURE_DEBUG && activeKind == GestureKind.MOVE && pickupArmed) {
            String drawnRow = hoverTargetTrack != null ? hoverTargetTrack.getId()
                    : (activeTrack != null ? activeTrack.getId() + "(home)" : "?");
            rowGestureLog("move fx=" + (int) x + " fy=" + (int) y
                    + " fingerMs=" + t
                    + " resolvedMs=" + activeItem.getTimelineStartMs()
                    + " drawnRow=" + drawnRow
                    + " timeLock=" + (hoverTargetTrack != null
                        && Math.abs(Math.max(0, t - moveGrabOffsetMs) - dragStartTimelineMs) <= snapThrMs)
                    + " buttJoint=" + (bookendJointMs == Long.MIN_VALUE ? "-" : bookendJointMs)
                    + " homeSnap=" + homeSnapArmed);
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

        // §3A.4: the finger is over the MASTER TRACK, where the view is already drawing a seam
        // indicator. Without this the gap below the last layer row also matches down there, so the
        // user got a new-layer insertion line AND a spine seam line simultaneously — two different
        // promises about where one clip is going, which is the exact guessing the indicator exists
        // to remove. Clear every hover state rather than merely hiding the line, because an armed
        // gap would also COMMIT a new-lane drop on release.
        if (spineHoverSuppressed) {
            hoverGapIndex = -1;
            hoverCrossBandNewLane = false;
            hoveringHomeRow = false;
            lastRejectedRowId = null;
            hoverTargetTrack = null;
            clearBookend();
            rowRenderer.setDragTargetTrackId(null);
            rowRenderer.setProxyRowTrackId(null);
            rowRenderer.setCrossBandInsertionArmed(false, sourceIsFloatingBand);
            rowRenderer.setHoverGapIndex(-1);
            rowRenderer.setDragOutlineState(LayerRowRenderer.DRAG_OUTLINE_SAME_ROW);
            logHoverTarget("spine-suppressed");
            return;
        }

        // Slice 2 (dragux_v3, BINDING): the GAP is the new-layer target. Checked FIRST
        // so line-in-gap and row-highlight are mutually exclusive by construction.
        // Floating-band items only (C5 scope); the currently-armed gap gets a 2x exit
        // zone inside gapIndexAt (sticky hover — no flicker at the boundary).
        int gap = sourceIsFloatingBand ? rowRenderer.gapIndexAt(y, topPx, hoverGapIndex) : -1;
        if (gap >= 0) {
            hoverGapIndex = gap;
            hoverCrossBandNewLane = false;
            hoveringHomeRow = false;
            lastRejectedRowId = null;
            hoverTargetTrack = null;
            // C5 rider: entering a gap DISARMS the bookend excursion — the view must
            // never run two competing animated scrolls.
            clearBookend();
            rowRenderer.setDragTargetTrackId(null);
            // SPLIT-ELEMENT FIX: over a gap the proxy stays on its HOME row (the
            // insertion line shows where the new lane appears; the moving object
            // itself remains the ONE coherent body on its origin row).
            rowRenderer.setProxyRowTrackId(null);
            rowRenderer.setCrossBandInsertionArmed(false, sourceIsFloatingBand);
            rowRenderer.setHoverGapIndex(gap);
            rowRenderer.setDragOutlineState(LayerRowRenderer.DRAG_OUTLINE_NEW_LAYER);
            logHoverTarget("gap:" + gap);
            return;
        }
        hoverGapIndex = -1;
        rowRenderer.setHoverGapIndex(-1);

        Track candidate = rowRenderer.rowTrackAt(y, topPx);
        // Home-snap eligibility: only while hovering the item's OWN row (putting it
        // back where it started must not fight the bookend/cross-band logic of others).
        hoveringHomeRow = candidate != null && candidate.getId().equals(activeTrack.getId());
        if (candidate == null || candidate.getId().equals(activeTrack.getId())
                || candidate.isLocked() || candidate.isHidden()
                || rowRenderer.isFloatingBandRow(candidate) != sourceIsFloatingBand
                || !payloadCompatible(activeItem, candidate)) {
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
            // SPLIT-ELEMENT FIX: own row / locked-hidden reject / cross-band arm all keep
            // the proxy on its HOME row (proxyRowTrackId == null) — the single body tracks
            // the finger's X in place; it never leaks onto a rejected/other-band row.
            rowRenderer.setProxyRowTrackId(null);
            // S3 state colors: cross-band hover arms a NEW-LAYER drop → purple family
            // (dashed); own row / plain rejection = a same-row move → item's own color.
            rowRenderer.setDragOutlineState(crossBand
                    ? LayerRowRenderer.DRAG_OUTLINE_NEW_LAYER
                    : LayerRowRenderer.DRAG_OUTLINE_SAME_ROW);
            logHoverTarget(crossBand ? "cross-band-newlane" : "reject/own-row");
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
        // SPLIT-ELEMENT FIX: this is a VALID cross-row target — the ONE proxy body now
        // draws on THIS row (candidate), at its resolved model X, and on no other row.
        // The old E1 (source-row ghost) is suppressed and the old full-row E2 ring
        // becomes just the target-row highlight behind the coherent proxy.
        rowRenderer.setProxyRowTrackId(candidate.getId());
        logHoverTarget("cross-row:" + candidate.getId());
    }

    // ── ROWGESTURE instrumentation (TEMP, split-element rewrite 2026-07-05) ──────────
    // Greppable log tag for the device-in-the-loop hand-test: `adb logcat -d -s ROWGESTURE:D`.
    // Gated behind ROWGESTURE_DEBUG so it is cheap when off. LEFT IN (marked TEMP) for the
    // follow-up strip once the user confirms the single-proxy drag on a GREEN build.
    /**
     * TEMP: flip true to re-enable ROWGESTURE logs for a device-in-the-loop hand-test.
     *
     * <p>Turned OFF 2026-07-26 (`PLAN_LAYER_GESTURE_CONTRACT.md:136-138` asked for this
     * instrumentation to be stripped). Deliberately turned off rather than DELETED, because
     * the condition the note above attaches to the strip — "once the user confirms the
     * single-proxy drag on a GREEN build" — has not happened: that confirmation IS
     * `SPEC_NEUTRAL_SUBSTRATE`'s validation-queue items 5-7, which are still open, and that
     * queue's own method note recommends exactly this kind of log probe for them. Deleting
     * the instrumentation now would remove the tool the remaining work needs.
     *
     * <p>Off matters because {@code FLog.d} is NOT gated on {@code BuildConfig.DEBUG} — every
     * call writes two log lines and runs a redaction pass. The only per-frame call site (the
     * MOVE branch) is already behind this flag, so flipping it is the whole cost saving; the
     * other four sites are one-shot (pickup / drop-commit / hold-release) and their string
     * concatenation is negligible.
     */
    public static final boolean ROWGESTURE_DEBUG = false;
    private static final String RG_TAG = "ROWGESTURE";
    /** Last hover row id logged, to throttle per-move spam to one line per row change. */
    @Nullable private String lastLoggedHoverRow;

    static void rowGestureLog(@NonNull String msg) {
        if (ROWGESTURE_DEBUG) com.fadcam.FLog.d(RG_TAG, msg);
    }

    /** Throttled hover/proxy-row log: one line whenever the hovered/target row changes. */
    private void logHoverTarget(@NonNull String tag) {
        if (!ROWGESTURE_DEBUG) return;
        String key = tag;
        if (!key.equals(lastLoggedHoverRow)) {
            lastLoggedHoverRow = key;
            String proxyRow = hoverTargetTrack != null ? hoverTargetTrack.getId()
                    : (activeTrack != null ? activeTrack.getId() + "(home)" : "?");
            rowGestureLog("hover " + tag + " drawnRow=" + proxyRow
                    + " gapIndex=" + hoverGapIndex);
        }
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
        } else if (item.getClip() != null && item.getClip().isOverlayClip()) {
            item.getClip().setOverlayStartMs(newStartMs);
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
        } else if (item.getClip() != null && item.getClip().isOverlayClip()) {
            Clip c = item.getClip();
            if (left) {
                long rightEdgeMs = clipBeforeStartMs + (clipBeforeOutMs - clipBeforeInMs);
                if (Math.abs(c.getOverlayStartMs() - clipBeforeStartMs) <= thrMs) {
                    c.setOverlayStartMs(clipBeforeStartMs);
                    c.setOutPointMs(clipBeforeOutMs);
                    snapped = true;
                }
            } else if (Math.abs(c.getOutPointMs() - clipBeforeOutMs) <= thrMs) {
                c.setOutPointMs(clipBeforeOutMs);
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
                // MAX/4 is CORRECT here and is not the stranding bug: `ourEnd` is only ever a
                // comparison bound ("does this sibling start before our end"), and for an
                // open-ended item the honest answer is "before anything". No value derived from
                // it is stored. The stranding came from feeding this constant to
                // getDisplayDurationMs as a fallback LENGTH — see trimSiblingFloor.
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
        } else if (item.getClip() != null && item.getClip().isOverlayClip()) {
            Clip c = item.getClip();
            long srcDur = c.getSourceDurationMs();
            if (srcDur <= 0) return;
            long offset = c.getOverlayStartMs();
            long startExtentEnd = offset + (dragStartTrimOutMs - dragStartTrimInMs);
            if (left) {
                long rightEdgeMs = clipBeforeStartMs + (dragStartTrimOutMs - dragStartTrimInMs);
                long minStart = 0;
                long maxStart = rightEdgeMs - AUDIO_MIN_TRIM_GAP_MS;
                long newStartMs = Math.max(minStart, Math.min(targetTimeMs, maxStart));
                newStartMs = Math.max(newStartMs, trimSiblingFloor(newStartMs, rightEdgeMs));
                newStartMs = Math.min(newStartMs, maxStart);
                c.setOverlayStartMs(newStartMs);
                long newDuration = rightEdgeMs - newStartMs;
                long newOutMs = dragStartTrimInMs + newDuration;
                newOutMs = Math.min(srcDur, newOutMs);
                newOutMs = Math.max(dragStartTrimInMs + AUDIO_MIN_TRIM_GAP_MS, newOutMs);
                c.setOutPointMs(newOutMs);
            } else {
                long newOut = Math.max(dragStartTrimInMs + AUDIO_MIN_TRIM_GAP_MS,
                        Math.min(srcDur, targetTimeMs - offset));
                long proposedEnd = offset + (newOut - dragStartTrimInMs);
                long ceil = Math.max(trimSiblingCeil(offset, proposedEnd), startExtentEnd);
                newOut = Math.min(newOut, ceil - offset + dragStartTrimInMs);
                newOut = Math.max(newOut, dragStartTrimInMs + AUDIO_MIN_TRIM_GAP_MS);
                c.setOutPointMs(newOut);
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
        if (kfShiftActive) return finishKeyframeShift(committed);
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
        boolean droppedOnNewLayerZone = hoverGapIndex >= 0 || hoverCrossBandNewLane;
        // Slice 2: gap drops carry their insertion index; the cross-band arm keeps its
        // pre-Slice-2 append-at-bottom semantics (MAX_VALUE = append).
        int commitInsertionIndex = hoverGapIndex >= 0 ? hoverGapIndex : Integer.MAX_VALUE;
        boolean wasTap = pendingBodyDown && !movedDuringGesture;
        boolean wasDeleteTap = wasTap && pendingDeleteBadge;
        // G1 (gesture contract §1): a HOLD that lifted the item (pickup armed) then released
        // WITHOUT crossing the move slop = "hold → release in place" → open the general
        // advanced menu. Captured before the reset block below clears pickupArmed.
        boolean holdReleaseInPlace = (pickupArmed || lockedHoldRefused) && !movedDuringGesture;
        lockedHoldRefused = false;
        // Captured BEFORE the reset block below wipes them (commit-time overlap guard
        // + open-endedness restoration).
        long commitDur = dragStartDisplayDurMs;
        boolean commitWasMoveKind = activeKind == GestureKind.MOVE;
        boolean commitWasOpenEnded = dragStartDurationMs == Long.MAX_VALUE;
        boolean commitHomeSnapped = homeSnapArmed;
        pendingDeleteBadge = false;
        spineHoverSuppressed = false;   // §3A.4: never let it leak into the next gesture
        active = false;
        activeItem = null;
        activeTrack = null;
        moveGrabOffsetMs = -1;
        dragStartDurationMs = 0;
        pendingBodyDown = false;
        pickupArmed = false;
        hoverTargetTrack = null;
        hoverGapIndex = -1;
        hoverCrossBandNewLane = false;
        lastRejectedRowId = null;
        bookendJointMs = Long.MIN_VALUE;
        suppressMoveMapping = false;
        hoveringHomeRow = false;
        homeSnapArmed = false;
        dragStartDisplayDurMs = 0;
        lastLoggedHoverRow = null;
        rowRenderer.setDragTargetTrackId(null);
        rowRenderer.setProxyRowTrackId(null);
        rowRenderer.setProxyItem(null);
        rowRenderer.setLiftedItemId(null);
        rowRenderer.setTrimmingItemId(null);
        rowRenderer.setHomeGhost(null, 0, 0);
        rowRenderer.setHomeGhostArmed(false);
        rowRenderer.setCrossBandInsertionArmed(false, true);
        rowRenderer.setHoverGapIndex(-1);
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
            rowGestureLog("drop-commit item=" + item.getId()
                    + " committedStartMs=" + item.getTimelineStartMs()
                    + " fromRow=" + (fromTrack != null ? fromTrack.getId() : "?")
                    + " toRow=" + (toTrack != null ? toTrack.getId() : (fromTrack != null ? fromTrack.getId() + "(same)" : "?"))
                    + " newLayerZone=" + droppedOnNewLayerZone);
            // M10: report the track-change FIRST (see method doc) so the activity can
            // fold it into the ONE undo action onGestureFinished below builds.
            if (droppedOnNewLayerZone && fromTrack != null) {
                callback.onItemDroppedOnNewLayer(item, fromTrack, commitInsertionIndex);
            } else if (toTrack != null && fromTrack != null) {
                callback.onItemMovedToTrack(item, fromTrack, toTrack);
            }
            callback.onGestureFinished(item, activeKind);
        } else if (wasDeleteTap && committed && item != null && fromTrack != null) {
            // Deferred delete-badge tap (review fix 2026-07-03): the badge no longer
            // fires on DOWN — a clean tap on it resolves HERE, on the committed UP,
            // after scrub/pickup/scroll have all been ruled out.
            callback.onItemDeleteRequested(fromTrack, item);
            lastTapItemId = null; // a delete tap never pairs into a double-tap
        } else if (wasTap && committed && item != null) {
            // G1 (gesture contract §1): a clean body TAP, resolved AFTER scrub/pickup/
            // scroll were ruled out. The first tap only SELECTS (done on DOWN). A SECOND
            // tap on the SAME item within the window is the express lane to that object's
            // type editor — reported via onItemDoubleTapped; the single tap fires nothing.
            long now = android.os.SystemClock.uptimeMillis();
            if (item.getId().equals(lastTapItemId) && now - lastTapUpMs <= DOUBLE_TAP_WINDOW_MS) {
                lastTapItemId = null;
                callback.onItemDoubleTapped(item);
            } else {
                lastTapItemId = item.getId();
                lastTapUpMs = now;
            }
        } else if (holdReleaseInPlace && committed && item != null && fromTrack != null) {
            // G1 (gesture contract §1): hold → release in place. The pickup already lifted
            // the item (haptic); releasing without a move asks for its general advanced
            // menu rather than committing a move or silently aborting.
            rowGestureLog("hold-release-menu item=" + item.getId() + " row=" + fromTrack.getId());
            callback.onItemMenuRequested(fromTrack, item);
            lastTapItemId = null;
        }
        return true;
    }

    /**
     * Lowest legal start for a row-system LEFT trim (may not cross a same-row sibling).
     *
     * <p><b>The sibling length resolves against {@link #effectiveTotalMs()}, not
     * Long.MAX_VALUE / 4.</b> This is the SECOND path that stranded items at
     * 2305843009213693951ms — see {@code resolveOverlapOnRow} for the mechanism and the ledger
     * for the three projects carrying the damage. Here the escape was subtler: with an
     * open-ended sibling the floor became MAX/4, and the clamp on the next line
     * ({@code maxStart == Long.MAX_VALUE ? newStart : maxStart}) is a NO-OP for exactly the
     * items at risk, because an open-ended item's {@code maxStart} IS Long.MAX_VALUE. So the
     * one guard that looked like it would catch this could not.</p>
     */
    private long trimSiblingFloor(long proposedStart, long ourEnd) {
        if (activeTrack == null || activeItem == null) return proposedStart;
        long floor = proposedStart;
        for (TimedItem sib : activeTrack.getItems()) {
            if (sib.getId().equals(activeItem.getId())) continue;
            long ss = sib.getTimelineStartMs();
            long se = ss + Math.max(0, sib.getDisplayDurationMs(effectiveTotalMs()));
            if (ss < ourEnd && se > proposedStart) floor = Math.max(floor, se);
        }
        return floor;
    }

    /**
     * Highest legal end for a row-system RIGHT trim (may not cross a same-row sibling).
     *
     * <p>Uses {@link #effectiveTotalMs()} for consistency with {@link #trimSiblingFloor}. This
     * direction was never able to strand an item — it narrows to a sibling's START (`ss`), never
     * to the computed end (`se`) — but leaving one call site on the old sentinel would leave the
     * trap armed for the next edit.</p>
     */
    private long trimSiblingCeil(long ourStart, long proposedEnd) {
        if (activeTrack == null || activeItem == null) return proposedEnd;
        long ceil = proposedEnd;
        for (TimedItem sib : activeTrack.getItems()) {
            if (sib.getId().equals(activeItem.getId())) continue;
            long ss = sib.getTimelineStartMs();
            long se = ss + Math.max(0, sib.getDisplayDurationMs(effectiveTotalMs()));
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
        //
        // `lastTotalMs`, NOT Long.MAX_VALUE / 4 — and that constant was a real bug, not a
        // harmless over-estimate. `totalMs` is what an OPEN-ENDED item's length resolves
        // against (TimedItem#getDisplayDurationMs returns `fallbackMs - start` when endMs is
        // Long.MAX_VALUE), so passing MAX/4 made any open-ended sibling occupy the row out to
        // 2305843009213693951ms. nearestFreeStart's "after the last block" branch then returned
        // exactly that, and applyCommittedStart wrote it to the item as its startMs — putting it
        // ~73 million years down the timeline, where no playhead can reach it and no UI can
        // select it. Three sandbox projects carry a text overlay stranded at precisely
        // Long.MAX_VALUE / 4 (see the ledger); this is where they came from.
        //
        // The irony is the point: this guard exists to stop an overlap reaching DISK, and it was
        // the only path corrupting what reached disk. Using the same total the LIVE resolver was
        // given also makes the commit agree with the preview the user actually saw, which is what
        // this method was for in the first place.
        return nearestFreeStart(row, moved.getId(), desiredStart, dur, effectiveTotalMs(),
                fingerSidePref);
    }

    /**
     * The last timeline total this controller was handed, for the commit path — which, unlike
     * every live path, is not given one ({@link #onRowBodyUp} takes only a boolean).
     *
     * <p>Set on DOWN and on every MOVE, so a drop is always preceded by at least one write. The
     * 0 default only survives if a commit somehow runs without either, and
     * {@link #effectiveTotalMs()} is what decides what that means.</p>
     */
    private long lastTotalMs = 0;

    /**
     * A sane timeline total for the commit-time resolver.
     *
     * <p>Falls back to 0 rather than to a large sentinel, deliberately. With 0, an open-ended
     * sibling's {@code getDisplayDurationMs} clamps to 0 via its own {@code Math.max(0, …)}, the
     * sibling contributes no block, and the resolver returns the desired start unchanged — the
     * item stays where the user dropped it. A large fallback does the opposite: it invents an
     * enormous occupied region and flings the item to the end of it. <b>When this value is
     * unknown the right failure is to leave the item alone, not to move it somewhere no one can
     * reach.</b></p>
     */
    private long effectiveTotalMs() {
        return Math.max(0, lastTotalMs);
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
        } else if (item.getClip() != null && item.getClip().isOverlayClip()) {
            item.getClip().setOverlayStartMs(newStartMs);
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
            } else if (item.getClip() != null && item.getClip().isOverlayClip()) {
                item.getClip().setOverlayStartMs(clipBeforeStartMs);
            }
        } else {
            if (item.getTextOverlay() != null) {
                item.getTextOverlay().setTimeRange(dragStartTextStartMs, dragStartTextEndMs);
            } else if (item.getAudioClip() != null) {
                AudioClip ac = item.getAudioClip();
                ac.setInPointMs(audioBeforeInMs);
                ac.setOutPointMs(audioBeforeOutMs);
            } else if (item.getClip() != null && item.getClip().isOverlayClip()) {
                item.getClip().setOverlayStartMs(clipBeforeStartMs);
                item.getClip().setInPointMs(clipBeforeInMs);
                item.getClip().setOutPointMs(clipBeforeOutMs);
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
    public long getClipBeforeStartMs() { return clipBeforeStartMs; }
    public long getClipBeforeInMs() { return clipBeforeInMs; }
    public long getClipBeforeOutMs() { return clipBeforeOutMs; }

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

    /**
     * True while the move-drag is hovering a between-rows GAP (Slice 2 gap-insertion
     * target). A1 arbitration (dragux_v3 C8): a gap hover means the finger is between
     * rows and the intent is VERTICAL (open a new lane here) — so horizontal edge
     * auto-pan must NOT run (it already disarms the bookend excursion via clearBookend).
     */
    public boolean isHoverGapActive() {
        return hoverGapIndex >= 0;
    }


    /**
     * Guard: which payloads a row accepts. NEUTRAL SUBSTRATE (see
     * {@code tasks/SPEC_NEUTRAL_SUBSTRATE.md}) — EVERY floating row is a lane that takes
     * ANY visual payload (text/sticker/sprite/image/video), so the user stacks whatever
     * they like wherever they like. The single real split is AUDIO: audio clips are never
     * visually composited, so an audio lane takes only audio and a floating lane never
     * does. (The same-band guard in {@code updateDragTarget} already blocks cross-band
     * drags; this keeps the model correct independently of it.)
     */
    private static boolean payloadCompatible(@NonNull TimedItem item, @NonNull Track candidate) {
        if (candidate.getKind() == TrackKind.AUDIO) {
            return item.getAudioClip() != null;
        }
        // NOT every floating row is a lane: the band also carries the read-only CAPTION and
        // VISUALIZER views (FaditorEditorActivity appends them to the layer band). Dropping
        // onto one would write a layerId that no routing consumes — see TrackKind#isLane.
        if (!candidate.getKind().isLane()) return false;
        if (item.getAudioClip() != null) return false;
        return item.getClip() != null
                || item.getTextOverlay() != null
                || item.getSprite() != null;
    }
}
