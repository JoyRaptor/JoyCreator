package com.fadcam.ui.faditor.layers;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.keyframe.Keyframe;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns ALL rendering, measurement and header-touch hit-testing for the M6 multi-row
 * timeline UI driven by the schema-v8 Track model (PLAN Part 6 + Part 7, row M6).
 *
 * <p><b>Extract-on-touch (PLAN §1.2(E)):</b> {@code EditorTimelineView} (4,881+ lines) must
 * NOT grow with row-rendering feature logic. It delegates here via a handful of thin hook
 * calls (measure/draw/touch) and this class owns everything else: paints, row geometry,
 * collapse/hide/lock/mute icon hit-zones, and the small vertical scroll used to keep the
 * pinned rows above always visible while extra layer/audio rows scroll beneath them.</p>
 *
 * <p><b>What this does NOT do (out of scope for M6):</b> moving/trimming/deleting items on
 * a row (M7); drag-between-layers or creating new tracks (M10); any preview/export effect
 * — hidden/locked/muted only affect how this class renders/hit-tests THIS row (PLAN Part 7
 * M6 scope item 4); wiring those into playback is M-COMP-1 / M-EXPORT-1 (see the TODOs
 * below at each toggle site).</p>
 *
 * <p><b>Plain single-track projects are unaffected:</b> {@link #layout} is a no-op (draws
 * nothing, adds zero height, hit-tests nothing) whenever both {@code layers} and
 * {@code audioTracks} are empty — exactly the case for a project with no extra Track beyond
 * the auto-migrated master (PLAN Part 7 M6 scope item 6; DESIGN §5 "never bury the
 * timeline"). The existing read-only VIZ/CC/overlay rows drawn by
 * {@code EditorTimelineView#drawLayers} are untouched by this class (PLAN §1.2(E) allows
 * leaving them if re-routing is invasive — it is, given their tight coupling to that view's
 * own selection/drag state; see the M6 build report for the full rationale).</p>
 */
public final class LayerRowRenderer {

    // ── Row geometry (dp) ───────────────────────────────────────────
    private static final float HEADER_WIDTH_DP = 92f;
    private static final float ROW_HEIGHT_EXPANDED_DP = 34f;
    private static final float ROW_HEIGHT_COLLAPSED_DP = 14f;
    /** AV3: expanded AUDIO rows are taller so the two-lane quad-band tape (and the volume
     *  keyframe rubber-band drawn over it) have room. Floating layer rows keep 34dp. */
    private static final float ROW_HEIGHT_AUDIO_EXPANDED_DP = 76f;
    private static final float ROW_GAP_DP = 3f;
    private static final float TOP_GAP_DP = 6f;
    /** Small breathing gap above the first AUDIO-band row (below master). */
    private static final float AUDIO_BAND_TOP_GAP_DP = 3f;
    private static final float ICON_SIZE_DP = 12f;
    private static final float ICON_GAP_DP = 4f;
    private static final float CARET_SIZE_DP = 6f;
    /** Default cap on the visible height of the scrollable layer/audio-row region (rest scrolls). */
    private static final float DEFAULT_MAX_VISIBLE_ROWS_DP = 140f;
    /** G6 resizable timeline: floor/ceiling for the user-controlled band cap (dp). Floor ≈ one
     * expanded row. The ceiling is intentionally larger than any phone screen since G6.3: the
     * REAL growth bound is dynamic — the grab bar caps the band at the space actually available
     * (PreviewPipController.maxBandDpFor), and past the preview's minimum the preview promotes
     * to a floating PiP so the band can absorb its slot (near-fullscreen timeline). */
    private static final float MIN_VISIBLE_ROWS_DP = 40f;
    private static final float MAX_VISIBLE_ROWS_CAP_DP = 1000f;
    /** G6: the current layer-band viewport cap (dp), user-resizable via the preview/timeline grab bar. */
    private float maxVisibleRowsDp = DEFAULT_MAX_VISIBLE_ROWS_DP;

    // ── Colors (frosted dark glass — DESIGN §5) ─────────────────────
    private static final int COLOR_HEADER_BG      = 0x99141420; // semi-transparent dark
    private static final int COLOR_HEADER_BG_LOCK = 0x99201414;
    private static final int COLOR_ROW_BODY_BG    = 0x661A1A24;
    private static final int COLOR_ROW_NAME       = 0xFFEDEDED;
    private static final int COLOR_ICON_ON        = 0xFFFFFFFF;
    private static final int COLOR_ICON_OFF       = 0x66FFFFFF;
    private static final int COLOR_ITEM_TEXT      = 0xDD8C3DFA;   // purple (TEXT/STICKER)
    private static final int COLOR_ITEM_VIDEO     = 0xDD4397FD;   // blue (VIDEO/IMAGE)
    private static final int COLOR_ITEM_AUDIO     = 0xDD35F6BF;   // aqua (AUDIO)
    private static final int COLOR_ITEM_SPRITE    = 0xDDFFB74D;   // amber (SPRITE, S5)
    private static final int COLOR_ITEM_CAPTION   = 0xDDFFC107;   // amber-gold (CAPTION/CC, Slice B)
    private static final int COLOR_ITEM_VIZ       = 0xDD4DD0E1;   // cyan (VISUALIZER, Slice B)
    private static final int COLOR_ITEM_HIDDEN    = 0x552A2A2A;   // dimmed/ghosted
    private static final int COLOR_STRIP          = 0x99CC27FF;   // collapsed summary strip
    /** Selection stroke width, item-hit-test PLAN §6: "accent-colored stroke... per the item's color family." */
    private static final float SELECTION_STROKE_DP = 2f;

    /** Reusable path for sprite frame-swap diamonds. */
    private final Path spriteDiamondPath = new Path();
    /** Paint for sprite frame-swap diamonds. */
    private final Paint spriteDiamondPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** C4 §1: consolidated property-keyframe diamonds (drawer accent green). */
    private final Paint kfDiamondPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** C4 §3: opacity-only rubber-band envelope + its dark scrim. */
    private final Paint kfEnvLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kfEnvDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kfScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean kfEnvPaintsInit;

    /** Bucket tolerance for consolidating property key times into ONE row diamond — matches
     *  the drawer's on-key tolerance so an X and a Y key from the same gesture read as one. */
    static final long KF_CONSOLIDATE_TOLERANCE_MS = 66L;

    /** Which header icon zone a touch landed on. */
    public enum HitZone { CARET, HIDE, LOCK, MUTE, NONE }

    /** Result of a header hit-test: which track + which zone. */
    public static final class HeaderHit {
        @NonNull public final Track track;
        @NonNull public final HitZone zone;
        HeaderHit(@NonNull Track track, @NonNull HitZone zone) {
            this.track = track; this.zone = zone;
        }
    }

    /** Which part of an item block a touch landed on (M7). DELETE = the trash roundel
     *  drawn on the SELECTED item (redesign: delete moved off long-press, which is now
     *  pick-up-for-move — see PLAN_LAYER_GESTURE_CONTRACT "DELETE relocates"). */
    public enum ItemZone { BODY, LEFT_HANDLE, RIGHT_HANDLE, DELETE }

    /** Result of a row-BODY item hit-test (M7): which track + item + zone. */
    public static final class ItemHit {
        @NonNull public final Track track;
        @NonNull public final TimedItem item;
        @NonNull public final ItemZone zone;
        ItemHit(@NonNull Track track, @NonNull TimedItem item, @NonNull ItemZone zone) {
            this.track = track; this.item = item; this.zone = zone;
        }
    }

    private final float density;
    private final Paint headerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowBodyBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint itemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint itemLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Selection stroke around a selected item's body (Stage 2; PLAN §6). */
    private final Paint itemSelectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path caretPath = new Path();
    private final Path mutePath = new Path();

    /** Rows laid out on the last {@link #layout} call, top-to-bottom, for draw/hit-test. */
    private final List<RowLayout> rows = new ArrayList<>();

    /** Vertical scroll offset (px) within the capped-height row region. */
    private float scrollOffsetPx = 0f;
    private float contentHeightPx = 0f;
    private float viewportHeightPx = 0f;
    /** Horizontal scroll + view width captured at the last {@link #layout} call — the
     *  visible viewport in content-x, needed to pin the delete badge on-screen for
     *  items whose right end runs past the screen edge (user feedback 2026-07-03). */
    private float lastHScrollOffsetPx = 0f;
    private float lastWidthPx = 0f;
    /** Screen-y of the FLOATING band's top edge at the last {@link #layout} (band-1 anchor). */
    private float lastTopPx = 0f;
    /** Screen-y of the AUDIO band's top edge at the last {@link #layout} (band-2 anchor). */
    private float lastAudioTopPx = 0f;
    /** Height (px) of the AUDIO band laid out by the last {@link #layout} (0 = no audio). */
    private float audioBandHeightPx = 0f;

    /** Viewport-left in content-x at the last layout (for panel-relative gestures). */
    public float getLastHScrollOffsetPx() { return lastHScrollOffsetPx; }
    /** Timeline-panel width at the last layout (for panel-relative gestures). */
    public float getLastWidthPx() { return lastWidthPx; }

    private static final class RowLayout {
        final Track track;
        /** True if this row came from the {@code layers} (floating) list, false if {@code audioTracks} (M10). */
        final boolean floatingBand;
        final RectF headerRect = new RectF();
        final RectF bodyRect = new RectF();
        final RectF caretRect = new RectF();
        final RectF hideRect = new RectF();
        final RectF lockRect = new RectF();
        final RectF muteRect = new RectF();
        RowLayout(Track t, boolean floatingBand) { track = t; this.floatingBand = floatingBand; }
    }

    /**
     * The ONE cross-row / new-layer affordance color (dragux_v3 slice-3 #2, user
     * hand-test 2026-07-04: the old white ring "should be the established PURPLE
     * cross-row affordance"). Drives the cross-row drag-target highlight ring, the
     * gap insertion line, and the cross-band insertion line — every "landing on
     * another row / linkage context" cue reads as one purple family. Same-row
     * moves read neutral WHITE ({@link #COLOR_SAME_ROW_OUTLINE}); snap-home stays
     * gray (home ghost).
     */
    private static final int COLOR_DROP_TARGET_RING = 0xFF8C3DFA;
    private final Paint dropTargetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Same-row (time-only) move outline (dragux_v3 slice-3 #2 audit, C8): NEUTRAL WHITE,
     * item-color-INDEPENDENT. The old item-color-blended-toward-white read purple for
     * TEXT/STICKER items (base 0xFF8C3DFA blended 22% toward white ≈ 0xFFA567FB) — nearly
     * identical to {@link #COLOR_DROP_TARGET_RING} cross-row purple, so same-row and
     * cross-row shared a color for those types (the ambiguity the audit bans). White is
     * the one unambiguous same-row cue for EVERY item kind; purple = cross-row family only.
     */
    private static final int COLOR_SAME_ROW_OUTLINE = 0xFFFFFFFF;

    // ── Slice 2 (dragux_v3, BINDING 2026-07-04, built 2026-07-17): the GAP is the
    // new-layer target. While an item is picked up, every gap between adjacent
    // floating rows PLUS above the topmost PLUS below the bottommost is an
    // insertion target; hovering one draws ONE accent line in that gap and
    // releasing creates the track AT that index. This replaces the three bolted-on
    // affordances (pinned bottom zone, cross-band arm text, nothing-between-rows).
    /** Armed gap index: 0..floatingRowCount (i = above row i; count = below last). -1 = none. */
    private int hoverGapIndex = -1;
    /** Entry half-height (px basis dp) of a gap hit-zone; the CURRENTLY-armed gap uses
     *  2x on the way out (sticky hover, spec-correction C5 — no flicker at boundaries). */
    private static final float GAP_HIT_HALF_DP = 8f;
    /** Floating-row count at the last {@link #layout} — gap indices are only valid against it. */
    private int floatingRowCountAtLayout = 0;

    public void setHoverGapIndex(int gapIndex) { this.hoverGapIndex = gapIndex; }

    /** G9c: ids of every link-group member — drives the chain badge on item blocks. */
    private final java.util.Set<String> linkedItemIds = new java.util.HashSet<>();
    private final Paint linkBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    { linkBadgePaint.setStyle(Paint.Style.STROKE); }

    public void setLinkedItemIds(@NonNull java.util.Set<String> ids) {
        linkedItemIds.clear();
        linkedItemIds.addAll(ids);
        linkBadgePaint.setStrokeWidth(1.4f * density);
    }

    /**
     * Gap hit-test in screen-y (floating band only). {@code currentGap} is the
     * already-armed index (or -1) — it gets a 2x exit zone so the armed line is
     * sticky. Returns the armed gap index, or -1.
     */
    public int gapIndexAt(float y, float topPx, int currentGap) {
        int n = floatingRowCountAtLayout;
        if (n <= 0) return -1;
        float localY = y - topPx + scrollOffsetPx;
        // Outside the band viewport entirely → no gap (master/audio areas keep
        // their own affordances; C5 scope: floating band first).
        if (localY < -GAP_HIT_HALF_DP * density
                || localY > contentHeightPx + GAP_HIT_HALF_DP * density) {
            return -1;
        }
        float enter = GAP_HIT_HALF_DP * density;
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i <= n; i++) {
            float d = Math.abs(localY - gapBoundaryY(i));
            float half = (i == currentGap) ? enter * 2f : enter;
            if (d <= half && d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Content-space y of gap boundary {@code gapIndex} (see {@link #hoverGapIndex} doc). */
    private float gapBoundaryY(int gapIndex) {
        float halfGap = (ROW_GAP_DP * density) / 2f;
        if (gapIndex <= 0) return rows.get(0).bodyRect.top - halfGap;
        if (gapIndex >= floatingRowCountAtLayout) {
            return rows.get(floatingRowCountAtLayout - 1).bodyRect.bottom + halfGap;
        }
        return (rows.get(gapIndex - 1).bodyRect.bottom + rows.get(gapIndex).bodyRect.top) / 2f;
    }

    /** The Slice 2 insertion line: one accent line in the armed gap, no text. */
    private void drawGapInsertionLine(@NonNull Canvas canvas, float hScrollOffsetPx, float widthPx) {
        int n = floatingRowCountAtLayout;
        if (n <= 0 || hoverGapIndex < 0 || hoverGapIndex > n) return;
        float lineY = gapBoundaryY(hoverGapIndex);
        float left = hScrollOffsetPx + HEADER_WIDTH_DP * density;
        float right = hScrollOffsetPx + widthPx;
        stripPaint.setColor(COLOR_DROP_TARGET_RING);
        float half = 1.25f * density;
        canvas.drawRoundRect(left, lineY - half, right - 4f * density, lineY + half,
                half, half, stripPaint);
    }

    public LayerRowRenderer(float density) {
        this.density = density;
        headerBgPaint.setStyle(Paint.Style.FILL);
        rowBodyBgPaint.setStyle(Paint.Style.FILL);
        namePaint.setColor(COLOR_ROW_NAME);
        namePaint.setTextSize(10f * density);
        namePaint.setTypeface(Typeface.DEFAULT_BOLD);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(1.6f * density);
        itemPaint.setStyle(Paint.Style.FILL);
        itemLabelPaint.setTextSize(9f * density);
        itemLabelPaint.setColor(0xFFFFFFFF);
        stripPaint.setStyle(Paint.Style.FILL);
        itemSelectionPaint.setStyle(Paint.Style.STROKE);
        itemSelectionPaint.setStrokeWidth(SELECTION_STROKE_DP * density);
        dropTargetPaint.setStyle(Paint.Style.STROKE);
        dropTargetPaint.setStrokeWidth(2f * density);
        dropTargetPaint.setColor(COLOR_DROP_TARGET_RING);
    }

    /** True when there is nothing to draw (plain single-track project — PLAN scope item 6). */
    public boolean isEmpty(@NonNull List<Track> layers, @NonNull List<Track> audioTracks) {
        return layers.isEmpty() && audioTracks.isEmpty();
    }

    /**
     * Extra height (px) of the FLOATING band (visual rows ABOVE master). Audio-track rows
     * moved to their own band BELOW master (audio consolidation, 2026-07-07) and are measured
     * by {@link #measureAudioBandHeightPx} — they no longer contribute here. Zero for a plain
     * project. Called from {@code EditorTimelineView#onMeasure}.
     */
    public float measureExtraHeightPx(@NonNull List<Track> layers) {
        if (layers.isEmpty()) return 0f;
        float rowGap = ROW_GAP_DP * density;
        float total = 0f;
        for (Track t : layers) total += rowHeightPx(t) + rowGap;
        float capped = Math.min(total, effectiveViewportCapPx());
        return TOP_GAP_DP * density + capped;
    }

    // ── Audio-band clipping fix (2026-07-08) ──────────────────────────────────────
    // When the view's measured height is clamped below its desired height (the parent
    // can't grant everything: many floating rows + master + the audio band), the ONLY
    // flexible, internally-scrolling band — the FLOATING band — absorbs the deficit,
    // so the fixed-height master + audio bands below it stay fully on-screen instead
    // of the bottom band silently clipping (found in the audio-consolidation smoke).
    /** Extra squeeze (px) on the floating band's viewport cap. Set by onMeasure. */
    private float viewportSqueezePx = 0f;

    /** Set by {@code EditorTimelineView#onMeasure}: the height deficit the floating band
     *  must absorb this pass (0 = the view got everything it asked for). */
    public void setViewportSqueezePx(float px) {
        this.viewportSqueezePx = Math.max(0f, px);
    }

    /** The floating band's viewport cap after absorbing any measure deficit — never
     *  below one collapsed band (40dp, the video-dominant G6.2 detent) so the band
     *  stays visible and scrollable. */
    private float effectiveViewportCapPx() {
        return Math.max(40f * density, maxVisibleRowsDp * density - viewportSqueezePx);
    }

    /**
     * Height (px) of the AUDIO band (headered audio rows BELOW master). Unlike the floating
     * band it is NOT viewport-capped and never scrolls — audio projects have a handful of
     * lanes and the old legacy audio band grew the view the same way. Zero when no audio.
     */
    public float measureAudioBandHeightPx(@NonNull List<Track> audioTracks) {
        if (audioTracks.isEmpty()) return 0f;
        float rowGap = ROW_GAP_DP * density;
        float total = AUDIO_BAND_TOP_GAP_DP * density;
        for (Track t : audioTracks) total += rowHeightPx(t) + rowGap;
        return total;
    }

    private float rowHeightPx(@NonNull Track t) {
        if (t.isCollapsed()) return ROW_HEIGHT_COLLAPSED_DP * density;
        if (t.getKind() == TrackKind.AUDIO) return ROW_HEIGHT_AUDIO_EXPANDED_DP * density;
        return ROW_HEIGHT_EXPANDED_DP * density;
    }

    /**
     * Lay out + draw every layer/audio row starting at {@code topPx}, within a viewport
     * capped at {@link #maxVisibleRowsDp} (extra rows scroll — this is the "master row
     * stays pinned; layer/audio rows scroll beneath it" behavior from PLAN §6.1: the master
     * row and all existing timeline content live entirely above {@code topPx} and are never
     * part of this scrolling region).
     *
     * <p>Called from inside the god view's {@code canvas.translate(-hScrollOffsetPx, 0)}
     * content-space block, so item bodies (which must line up with {@code timeToX}) are
     * laid out directly in that space, but the row HEADER column (name/caret/hide/lock/
     * mute — {@code hScrollOffsetPx} wide) is counter-translated back to screen-space so it
     * stays pinned to the left edge like the rest of the timeline's left gutter, instead of
     * scrolling out of view with the timeline content.</p>
     *
     * @param widthPx        viewport (screen) width — the header column is pinned within it.
     * @param hScrollOffsetPx the god view's current horizontal content scroll offset.
     * @param timeToX        maps absolute timeline ms to on-canvas (content-space) x (reuses
     *                       the god view's exact segment-aware mapping).
     * @param totalMs        timeline total duration, for items with an unbounded end.
     */
    public void layout(@NonNull Canvas canvas, @NonNull List<Track> layers,
                        @NonNull List<Track> audioTracks, float topPx, float audioTopPx,
                        float widthPx, float hScrollOffsetPx, long totalMs,
                        @NonNull TimeToX timeToX) {
        layout(canvas, layers, audioTracks, topPx, audioTopPx, widthPx, hScrollOffsetPx,
                totalMs, timeToX, false, null);
    }

    /**
     * Drag overload: {@code dragActive} enables the Slice 2 gap-insertion line (the
     * armed gap set via {@link #setHoverGapIndex}) — the pinned "+ Drop here for new
     * layer" zone is RETIRED (dragux_v3 Slice 2, BINDING).
     *
     * @param selectedItemId Stage 2 (PLAN §6): id of the currently-selected row item, or
     *                       {@code null} for no selection. Draws a selection stroke on
     *                       that item's body in {@link #drawExpandedItems} — the same id
     *                       {@link LayerGestureController#getSelectedItemId()} already
     *                       tracks for trim-handle exposure, now also driving the visual.
     */
    public void layout(@NonNull Canvas canvas, @NonNull List<Track> layers,
                        @NonNull List<Track> audioTracks, float topPx, float audioTopPx,
                        float widthPx, float hScrollOffsetPx, long totalMs,
                        @NonNull TimeToX timeToX,
                        boolean dragActive,
                        @Nullable String selectedItemId) {
        rows.clear();
        lastHScrollOffsetPx = hScrollOffsetPx;
        lastWidthPx = widthPx;
        lastTopPx = topPx;
        lastAudioTopPx = audioTopPx;
        if (isEmpty(layers, audioTracks)) {
            contentHeightPx = 0f; audioBandHeightPx = 0f; floatingRowCountAtLayout = 0; return;
        }

        float rowGap = ROW_GAP_DP * density;
        // ── Band 1: FLOATING rows (visual — text/sticker/sprite/PiP/CC/viz) ABOVE master,
        // in their own content space (0 = band top), scrollable within the capped viewport.
        float y = TOP_GAP_DP * density;
        for (Track t : layers) y = addRow(t, y, hScrollOffsetPx, widthPx, rowGap, true);
        int floatingRowCount = rows.size();
        floatingRowCountAtLayout = floatingRowCount;
        // Rows-only content height; the viewport caps at maxVisibleRowsDp and the extra
        // rows SCROLL beneath the pinned master (PLAN §6.1). Slice 2: no zone height is
        // reserved anymore — the gaps themselves are the new-layer targets.
        contentHeightPx = y;
        viewportHeightPx = Math.min(contentHeightPx, effectiveViewportCapPx());
        scrollOffsetPx = clampScroll(scrollOffsetPx);
        canvas.save();
        canvas.clipRect(hScrollOffsetPx, topPx, hScrollOffsetPx + widthPx, topPx + viewportHeightPx);
        canvas.translate(0f, topPx - scrollOffsetPx);
        for (int i = 0; i < floatingRowCount; i++) {
            drawRow(canvas, rows.get(i), totalMs, timeToX, selectedItemId);
        }
        if (dragActive && hoverGapIndex >= 0) {
            drawGapInsertionLine(canvas, hScrollOffsetPx, widthPx);
        }
        if (dragActive && crossBandInsertionArmed && crossBandDraggedIsFloating) {
            drawCrossBandInsertionLine(canvas, hScrollOffsetPx, widthPx);
        }
        if (dragActive && timeLockGuidesArmed && floatingRowCount > 0) {
            drawTimeLockGuides(canvas, timeToX, 0, floatingRowCount);
        }
        canvas.restore();

        // ── Band 2: AUDIO rows BELOW master (audio consolidation 2026-07-07 — replaces the
        // legacy EditorTimelineView audio band). Own content space (0 = band top), NEVER
        // scrolls (uncapped; the measured view height reserves the full band, exactly like
        // the legacy audio lanes did). Same rows list, so id-based lookups, cross-band drag
        // proxy/highlight and the gesture controller see one unified row world.
        float ay = AUDIO_BAND_TOP_GAP_DP * density;
        for (Track t : audioTracks) ay = addRow(t, ay, hScrollOffsetPx, widthPx, rowGap, false);
        audioBandHeightPx = audioTracks.isEmpty() ? 0f : ay;
        if (audioBandHeightPx > 0f) {
            canvas.save();
            canvas.clipRect(hScrollOffsetPx, audioTopPx,
                    hScrollOffsetPx + widthPx, audioTopPx + audioBandHeightPx);
            canvas.translate(0f, audioTopPx);
            for (int i = floatingRowCount; i < rows.size(); i++) {
                drawRow(canvas, rows.get(i), totalMs, timeToX, selectedItemId);
            }
            if (dragActive && crossBandInsertionArmed && !crossBandDraggedIsFloating) {
                drawCrossBandInsertionLine(canvas, hScrollOffsetPx, widthPx);
            }
            if (dragActive && timeLockGuidesArmed && rows.size() > floatingRowCount) {
                drawTimeLockGuides(canvas, timeToX, floatingRowCount, rows.size());
            }
            canvas.restore();
        }
    }

    // ── A9-lite time-lock guides (dragux_v3 A9, user spec): while a cross-row move is
    // time-locked (near-vertical drag keeping the original timing), 1px dotted vertical
    // lines at the item's original start/end bounds confirm "same time, different layer".
    // They vanish the moment the drag goes diagonal (controller disarms them). Dim by
    // design — "they don't have to be very bright" (user).
    private boolean timeLockGuidesArmed;
    private long timeLockStartMs, timeLockDurMs;

    public void setTimeLockGuides(boolean armed, long startMs, long durMs) {
        this.timeLockGuidesArmed = armed;
        this.timeLockStartMs = startMs;
        this.timeLockDurMs = durMs;
    }

    /** Draws the guides spanning rows [fromRow, toRow) — one band's rows, since the two
     *  bands live in different content spaces (drawn inside each band's translate). */
    private void drawTimeLockGuides(@NonNull Canvas canvas, @NonNull TimeToX timeToX,
                                     int fromRow, int toRow) {
        if (rows.isEmpty() || fromRow >= toRow) return;
        float top = rows.get(fromRow).bodyRect.top;
        float bottom = rows.get(toRow - 1).bodyRect.bottom;
        float x0 = timeToX.map(timeLockStartMs);
        float x1 = timeToX.map(timeLockStartMs + Math.max(0, timeLockDurMs));
        int prevColor = itemSelectionPaint.getColor();
        Paint.Style prevStyle = itemSelectionPaint.getStyle();
        float prevW = itemSelectionPaint.getStrokeWidth();
        android.graphics.PathEffect prevEffect = itemSelectionPaint.getPathEffect();
        itemSelectionPaint.setStyle(Paint.Style.STROKE);
        itemSelectionPaint.setStrokeWidth(1f); // single-pixel per spec
        itemSelectionPaint.setColor(0x9ACFCFD6);
        itemSelectionPaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{2f * density, 2.5f * density}, 0f));
        canvas.drawLine(x0, top, x0, bottom, itemSelectionPaint);
        if (x1 > x0 + 1f) canvas.drawLine(x1, top, x1, bottom, itemSelectionPaint);
        itemSelectionPaint.setPathEffect(prevEffect);
        itemSelectionPaint.setStrokeWidth(prevW);
        itemSelectionPaint.setColor(prevColor);
        itemSelectionPaint.setStyle(prevStyle);
    }

    /** True while a picked-up drag hovers a row of the OTHER band — draw the insertion
     *  line at the position the new lane will REALLY appear (see setter doc). */
    private boolean crossBandInsertionArmed;
    /** Band of the item being dragged when {@link #crossBandInsertionArmed} (true=floating/visual). */
    private boolean crossBandDraggedIsFloating;

    /**
     * Arm/clear the cross-band insertion indicator (user feedback 2026-07-03): dragging a
     * VISUAL item over the audio band used to preview it "below the audio" while the drop
     * actually creates the new visual lane ABOVE the audio band (and vice versa for audio
     * items) — the preview lied. While armed, a bright insertion line is drawn at the
     * TRUE future position of the new lane: for a visual item, the visual/audio band
     * boundary; for an audio item, below the last audio row (new audio lanes append to
     * the bottom of the audio band).
     */
    public void setCrossBandInsertionArmed(boolean armed, boolean draggedIsFloating) {
        this.crossBandInsertionArmed = armed;
        this.crossBandDraggedIsFloating = draggedIsFloating;
    }

    private void drawCrossBandInsertionLine(@NonNull Canvas canvas, float hScrollOffsetPx, float widthPx) {
        if (rows.isEmpty()) return;
        // Split-band geometry (audio consolidation 2026-07-07): the two bands live in
        // different content spaces, and this is called inside the DRAGGED item's own band
        // pass — so the line lands below that band's LAST row: a new visual lane appends
        // at the bottom of the floating band (above master), a new audio lane below the
        // last audio row. Find the last row of the relevant band.
        RowLayout last = null;
        for (RowLayout row : rows) {
            if (row.floatingBand == crossBandDraggedIsFloating) last = row;
        }
        if (last == null) return;
        float lineY = last.bodyRect.bottom + (ROW_GAP_DP * density) / 2f;
        float left = hScrollOffsetPx + HEADER_WIDTH_DP * density;
        float right = hScrollOffsetPx + widthPx;
        stripPaint.setColor(COLOR_DROP_TARGET_RING);
        float half = 1.25f * density;
        canvas.drawRoundRect(left, lineY - half, right - 4f * density, lineY + half,
                half, half, stripPaint);
        // Slice 2: text label KILLED — one insertion-line vocabulary, no words.
    }

    /**
     * @param hScrollOffsetPx the god view's horizontal content scroll offset, used so the
     *                        header rect is stored in the SAME content-space coordinates
     *                        the caller hit-tests against (see {@link #hitTestHeader}) even
     *                        though it is drawn pinned to the screen edge.
     */
    private float addRow(@NonNull Track t, float y, float hScrollOffsetPx, float widthPx,
                          float rowGap, boolean floatingBand) {
        RowLayout row = new RowLayout(t, floatingBand);
        float h = rowHeightPx(t);
        row.headerRect.set(hScrollOffsetPx, y, hScrollOffsetPx + HEADER_WIDTH_DP * density, y + h);
        row.bodyRect.set(hScrollOffsetPx + HEADER_WIDTH_DP * density, y, hScrollOffsetPx + widthPx, y + h);
        layoutHeaderIcons(row, h);
        rows.add(row);
        return y + h + rowGap;
    }

    private void layoutHeaderIcons(@NonNull RowLayout row, float rowH) {
        float iconSize = ICON_SIZE_DP * density;
        float gap = ICON_GAP_DP * density;
        float cy = row.headerRect.top + rowH / 2f;
        float caretCx = row.headerRect.left + iconSize * 0.7f;
        row.caretRect.set(caretCx - iconSize / 2f, cy - iconSize / 2f,
                caretCx + iconSize / 2f, cy + iconSize / 2f);
        // §4.5: eye/lock are PER-OBJECT now (object drawer) — the gutter keeps only
        // mute (audio-ish rows), right-aligned. hideRect/lockRect stay EMPTY so their
        // hit-tests can never fire; per-layer SOLO is the one remaining future control.
        float right = row.headerRect.right - gap;
        row.muteRect.set(right - iconSize, cy - iconSize / 2f, right, cy + iconSize / 2f);
        row.lockRect.setEmpty();
        row.hideRect.setEmpty();
    }

    private void drawRow(@NonNull Canvas canvas, @NonNull RowLayout row,
                          long totalMs, @NonNull TimeToX timeToX, @Nullable String selectedItemId) {
        Track t = row.track;
        boolean collapsed = t.isCollapsed();

        headerBgPaint.setColor(t.isLocked() ? COLOR_HEADER_BG_LOCK : COLOR_HEADER_BG);
        canvas.drawRect(row.headerRect, headerBgPaint);
        rowBodyBgPaint.setColor(COLOR_ROW_BODY_BG);
        canvas.drawRect(row.bodyRect, rowBodyBgPaint);

        // Caret (collapse toggle) — right-pointing when collapsed, down when expanded.
        drawCaret(canvas, row.caretRect, collapsed);

        // JoyRaptor 2026-07-19: layers are NEUTRAL SUBSTRATE — the kind badge moved off
        // the row gutter onto each OBJECT (drawItemBody). The header keeps only the
        // caret + mute; no name, no kind identity.

        // §4.5: per-layer eye/lock glyphs RETIRED (drawEyeIcon/drawLockIcon calls gone) —
        // hidden/locked live on objects, toggled in the drawer, ghosted on item bodies.
        drawMuteIcon(canvas, row.muteRect, !t.isMuted(), rowCarriesAudio(t));

        if (collapsed) {
            drawCollapsedStrip(canvas, row, totalMs, timeToX);
            // SPLIT-ELEMENT FIX: even when the hovered target row is COLLAPSED, the ONE
            // proxy body must render on it (never vanish) — draw it over the thin strip
            // so the coherent object is always visible under the finger.
            if (proxyItem != null && proxyRowTrackId != null && proxyRowTrackId.equals(t.getId())
                    && liftedItemId != null && liftedItemId.equals(proxyItem.getId())
                    && !isItemOnRow(proxyItem, t)) {
                float top = row.bodyRect.top + 3f * density;
                float bottom = row.bodyRect.bottom - 3f * density;
                drawItemBody(canvas, proxyItem, t.getKind(), baseColorFor(t.getKind()),
                        t.isHidden(), true, top, bottom, row.bodyRect.centerY(),
                        totalMs, timeToX, selectedItemId);
            }
        } else {
            drawExpandedItems(canvas, row, t, totalMs, timeToX, selectedItemId);
        }

        // M10: highlight the row a cross-row item drag is hovering. This used to be a full-width
        // purple STROKE around the whole row body — but for a small clip that bordering outline read
        // as an oversized "false size" of the item itself (JoyRaptor 2026-07-07: "a purple outline bigger
        // than it... why show me this false size?"). The single coherent proxy body (drawn at the
        // item's TRUE size + resolved drop X) is the size cue; the row target now reads as a thin
        // purple accent bar down the row's left edge + a faint wash — clearly "this ROW", not an item.
        if (dragTargetTrackId != null && dragTargetTrackId.equals(t.getId())) {
            int prevColor = barPaint.getColor();
            Paint.Style prevStyle = barPaint.getStyle();
            barPaint.setStyle(Paint.Style.FILL);
            barPaint.setColor(0x1F8C3DFA); // ~12% purple wash over the target row
            canvas.drawRoundRect(row.bodyRect, 3f * density, 3f * density, barPaint);
            barPaint.setColor(COLOR_DROP_TARGET_RING); // solid accent bar at the left edge
            canvas.drawRect(row.bodyRect.left, row.bodyRect.top,
                    row.bodyRect.left + 3f * density, row.bodyRect.bottom, barPaint);
            barPaint.setColor(prevColor);
            barPaint.setStyle(prevStyle);
        }
    }

    /** Id of the track row currently highlighted as a cross-row drag target, or null (M10). */
    @Nullable private String dragTargetTrackId;

    /** Set/clear which row (by track id) should render the drag-target highlight ring (M10). */
    public void setDragTargetTrackId(@Nullable String trackId) { this.dragTargetTrackId = trackId; }

    /** Id of the item currently "lifted" (picked up for a move) — rendered raised/brighter. */
    @Nullable private String liftedItemId;

    /** Set/clear which item (by id) is picked up for a move, so it draws with a lift affordance. */
    public void setLiftedItemId(@Nullable String itemId) { this.liftedItemId = itemId; }

    // ── SPLIT-ELEMENT FIX (2026-07-05, user root-cause on real phone): the drag used to
    // render as TWO disconnected half-objects — E1 (the brightened lifted body, drawn on
    // its SOURCE row in drawExpandedItems, tracking finger-X only because the model row
    // membership doesn't change until DROP) and E2 (the full-row purple ring in drawRow,
    // tracking which row-Y is hovered but never the finger-X / the resolver). The fix:
    // ONE proxy. The lifted body is now drawn on EXACTLY the hovered target row
    // (proxyRowTrackId), at its already-resolved model X — so what you see under the
    // finger is one coherent object that follows both axes and forecasts the drop
    // (WYSIWYG). On its HOME row it leaves only the passive home-ghost gap marker; it is
    // NOT redrawn there (no origin duplicate). It draws on no other row (no wrong-row
    // leak). The controller keeps proxyRowTrackId == the hovered cross-row target, or ==
    // the home row when the finger is over its own row / new-layer zone. ──
    /** Row (track id) the single drag proxy body is drawn on this frame, or null (= home row). */
    @Nullable private String proxyRowTrackId;
    /** The lifted item itself (its model X is resolved live), so the proxy can be drawn on
     *  a row it is not (yet) a member of — model membership only changes at DROP. */
    @Nullable private TimedItem proxyItem;

    /** Set the lifted item whose proxy body renders on {@link #proxyRowTrackId}. */
    public void setProxyItem(@Nullable TimedItem item) { this.proxyItem = item; }

    /**
     * Set the row the lifted item's coherent proxy body renders on (the hovered cross-row
     * target). {@code null} = the item's own/home row (the finger is over its own row, the
     * new-layer zone, or no valid other target). The proxy is drawn on this row ONLY and
     * nowhere else, eliminating the old source-row duplicate + wrong-row leak.
     */
    public void setProxyRowTrackId(@Nullable String trackId) { this.proxyRowTrackId = trackId; }

    // ── S3 drag-state outline (dragux_v3 slice-3 #2, user hand-tests 2026-07-04/05):
    // one unambiguous outline color per drag state on the LIFTED item itself. The user
    // saw WHITE where purple was expected — that was the generic selection stroke
    // (base color blended 55% toward white) still drawing on the dragged item; while
    // lifted, THIS outline replaces it entirely. ──
    /** No pickup in progress — no drag outline. */
    public static final int DRAG_OUTLINE_NONE = 0;
    /** Landing on the item's OWN row (time-only move): outline = neutral WHITE
     *  ({@link #COLOR_SAME_ROW_OUTLINE}), item-color-independent so it never collides
     *  with the cross-row purple for TEXT/STICKER items. */
    public static final int DRAG_OUTLINE_SAME_ROW = 1;
    /** Landing on ANOTHER row: the established PURPLE cross-row/linkage affordance. */
    public static final int DRAG_OUTLINE_CROSS_ROW = 2;
    /** Landing creates a NEW layer (pinned zone or cross-band arm): lighter purple,
     *  DASHED — same family as cross-row but visibly "not an existing row". */
    public static final int DRAG_OUTLINE_NEW_LAYER = 3;
    private int dragOutlineState = DRAG_OUTLINE_NONE;

    /** Set by {@link LayerGestureController} as the hover target changes mid-drag. */
    public void setDragOutlineState(int state) { this.dragOutlineState = state; }

    /** Outline around the lifted item encoding the CURRENT landing state (see consts).
     *  Snap-home keeps its own cue (the brightened home ghost) — unchanged. */
    private void drawDragStateOutline(@NonNull Canvas canvas, float x0, float top,
                                       float x1, float bottom, int baseColor) {
        int color;
        boolean dashed = false;
        switch (dragOutlineState) {
            case DRAG_OUTLINE_CROSS_ROW: color = COLOR_DROP_TARGET_RING; break;
            case DRAG_OUTLINE_NEW_LAYER: color = brighten(COLOR_DROP_TARGET_RING); dashed = true; break;
            case DRAG_OUTLINE_SAME_ROW: color = COLOR_SAME_ROW_OUTLINE; break;
            default: return;
        }
        int prevColor = itemSelectionPaint.getColor();
        android.graphics.PathEffect prevEffect = itemSelectionPaint.getPathEffect();
        itemSelectionPaint.setColor(color);
        if (dashed) {
            itemSelectionPaint.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{5f * density, 3f * density}, 0f));
        }
        float ins = (SELECTION_STROKE_DP * density) / 2f;
        canvas.drawRoundRect(x0 + ins, top + ins, x1 - ins, bottom - ins,
                3f * density, 3f * density, itemSelectionPaint);
        itemSelectionPaint.setPathEffect(prevEffect);
        itemSelectionPaint.setColor(prevColor);
    }

    // ── Home ghost (feedback 2026-07-03am): the item's ORIGINAL extent during a
    // move/trim gesture — a grey outline at where-it-was, brightening when the gesture
    // is within snap range of putting it back exactly (release = original, no undo). ──
    @Nullable private String homeGhostTrackId;
    private long homeGhostStartMs, homeGhostDurMs;
    private boolean homeGhostArmed;

    /** Set (trackId non-null) or clear (null) the home ghost for the active gesture. */
    public void setHomeGhost(@Nullable String trackId, long startMs, long durMs) {
        this.homeGhostTrackId = trackId;
        this.homeGhostStartMs = startMs;
        this.homeGhostDurMs = durMs;
    }

    /** Brighten the ghost: the current gesture would land EXACTLY back on it. */
    public void setHomeGhostArmed(boolean armed) { this.homeGhostArmed = armed; }

    private void drawHomeGhost(@NonNull Canvas canvas, float top, float bottom,
                                @NonNull TimeToX timeToX) {
        float gx0 = timeToX.map(homeGhostStartMs);
        float gx1 = Math.max(gx0 + 2f * density, timeToX.map(homeGhostStartMs + homeGhostDurMs));
        int prevColor = itemSelectionPaint.getColor();
        Paint.Style prevStyle = itemSelectionPaint.getStyle();
        float prevW = itemSelectionPaint.getStrokeWidth();
        android.graphics.PathEffect prevEffect = itemSelectionPaint.getPathEffect();
        // RESTYLE (user 2026-07-04: "the outline should be left behind as a GRAY BOX of
        // where it was" — the old near-white bright stroke read as the ACTIVE preview
        // and made the drag visuals feel backwards). The ghost is now unmistakably a
        // passive memory: dim translucent gray FILL + dim DASHED gray stroke. The solid
        // full-color item under the finger is the loudest thing on the row; the ghost
        // whispers. Armed (home snap) = the ghost warms slightly + stroke solidifies —
        // still gray family, never brighter than the live item.
        itemSelectionPaint.setStyle(Paint.Style.FILL);
        itemSelectionPaint.setColor(homeGhostArmed ? 0x4AB8B8C0 : 0x2A88888E);
        canvas.drawRoundRect(gx0, top, gx1, bottom, 3f * density, 3f * density, itemSelectionPaint);
        itemSelectionPaint.setStyle(Paint.Style.STROKE);
        itemSelectionPaint.setStrokeWidth(1.5f * density);
        itemSelectionPaint.setColor(homeGhostArmed ? 0xC8C4C4CC : 0x6E8A8A92);
        if (!homeGhostArmed) {
            itemSelectionPaint.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{4f * density, 3f * density}, 0f));
        }
        canvas.drawRoundRect(gx0, top, gx1, bottom, 3f * density, 3f * density, itemSelectionPaint);
        itemSelectionPaint.setPathEffect(prevEffect);
        itemSelectionPaint.setStrokeWidth(prevW);
        itemSelectionPaint.setColor(prevColor);
        itemSelectionPaint.setStyle(prevStyle);
    }

    /** Id of the item currently being edge-TRIMMED — rendered with timeline-locked stripes. */
    @Nullable private String trimmingItemId;
    /** uptimeMillis when the current trim armed, for the stripe fade-in. */
    private long trimStripeFadeStartMs;

    /** Set/clear which item (by id) is being trimmed, so it draws the stripe feedback. */
    public void setTrimmingItemId(@Nullable String itemId) {
        if (itemId != null && !itemId.equals(trimmingItemId)) {
            trimStripeFadeStartMs = android.os.SystemClock.uptimeMillis();
        }
        this.trimmingItemId = itemId;
    }

    /** Paint for the trim-feedback stripes (configured per-draw; soft translucent white). */
    private final Paint trimStripePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Trim-feedback stripes (user feedback 2026-07-03): a soft diagonal pattern whose
     * stripe positions are a FIXED CONTENT-SPACE GRID (multiples of the spacing in
     * absolute timeline-x), NOT item-relative — so while dragging an edge the pattern
     * visibly stays put and the moving edge "eats"/reveals stripes. That's the cue that
     * distinguishes a RESIZE (bar still, edge consuming stripes) from a MOVE (whole bar
     * + its stripes sliding) even when the item's far end is off-screen. Fades in over
     * ~180ms from trim-arm so it reads as feedback, not a permanent texture.
     */
    private void drawTrimStripes(@NonNull Canvas canvas, float x0, float top, float x1, float bottom) {
        float t = Math.min(1f, (android.os.SystemClock.uptimeMillis() - trimStripeFadeStartMs) / 180f);
        int alpha = (int) (0x3C * t);
        if (alpha <= 0) return;
        trimStripePaint.setStyle(Paint.Style.STROKE);
        trimStripePaint.setStrokeWidth(2f * density);
        trimStripePaint.setColor((alpha << 24) | 0x00FFFFFF);
        canvas.save();
        canvas.clipRect(x0, top, x1, bottom);
        float spacing = 12f * density;
        float h = bottom - top;
        // 45° diagonals anchored to the absolute content-x grid (timeline-locked).
        float sx = (float) (Math.floor((x0 - h) / spacing) * spacing);
        for (; sx <= x1 + h; sx += spacing) {
            canvas.drawLine(sx, bottom, sx + h, top, trimStripePaint);
        }
        canvas.restore();
    }

    private void drawCaret(@NonNull Canvas canvas, @NonNull RectF r, boolean collapsed) {
        caretPath.reset();
        float cx = r.centerX(), cy = r.centerY();
        float s = CARET_SIZE_DP * density / 2f;
        if (collapsed) {
            caretPath.moveTo(cx - s * 0.6f, cy - s);
            caretPath.lineTo(cx + s * 0.8f, cy);
            caretPath.lineTo(cx - s * 0.6f, cy + s);
        } else {
            caretPath.moveTo(cx - s, cy - s * 0.6f);
            caretPath.lineTo(cx, cy + s * 0.8f);
            caretPath.lineTo(cx + s, cy - s * 0.6f);
        }
        caretPath.close();
        iconPaint.setStyle(Paint.Style.FILL);
        iconPaint.setColor(COLOR_ICON_ON);
        canvas.drawPath(caretPath, iconPaint);
        iconPaint.setStyle(Paint.Style.STROKE);
    }

    private void drawEyeIcon(@NonNull Canvas canvas, @NonNull RectF r, boolean visible) {
        iconPaint.setColor(visible ? COLOR_ICON_ON : COLOR_ICON_OFF);
        canvas.drawOval(r, iconPaint);
        canvas.drawCircle(r.centerX(), r.centerY(), r.width() * 0.16f, iconPaint);
        if (!visible) {
            canvas.drawLine(r.left, r.bottom, r.right, r.top, iconPaint);
        }
    }

    private void drawLockIcon(@NonNull Canvas canvas, @NonNull RectF r, boolean locked) {
        iconPaint.setColor(locked ? COLOR_ICON_ON : COLOR_ICON_OFF);
        float bodyTop = r.top + r.height() * 0.42f;
        canvas.drawRect(r.left, bodyTop, r.right, r.bottom, iconPaint);
        RectF shackle = new RectF(r.left + r.width() * 0.2f, r.top,
                r.right - r.width() * 0.2f, bodyTop + r.height() * 0.1f);
        canvas.drawArc(shackle, 180f, 180f, false, iconPaint);
    }

    /**
     * Whether the row's mute toggle means anything — i.e. does this row carry audio?
     *
     * <p>NEUTRAL SUBSTRATE: decided by CONTENT, not by the row's {@link TrackKind}. A lane
     * is no longer branded by type, so "kind == VIDEO" stopped being the same question as
     * "holds a video": a neutral lane holding a PiP used to draw the greyed not-applicable
     * icon, while an emptied VIDEO-kind lane still drew the live one. The spine
     * ({@link TrackKind#MASTER}) and the audio band always carry audio by construction;
     * every other row carries audio exactly when it holds an audio clip or an overlay
     * (PiP) clip.</p>
     */
    private static boolean rowCarriesAudio(@NonNull Track t) {
        if (t.getKind() == TrackKind.AUDIO || t.getKind() == TrackKind.MASTER) return true;
        for (TimedItem item : t.getItems()) {
            if (item.getAudioClip() != null) return true;
            com.fadcam.ui.faditor.model.Clip clip = item.getClip();
            if (clip != null && clip.isOverlayClip()) return true;
        }
        return false;
    }

    private void drawMuteIcon(@NonNull Canvas canvas, @NonNull RectF r, boolean unmuted,
                               boolean applicable) {
        iconPaint.setColor(!applicable ? COLOR_ICON_OFF : (unmuted ? COLOR_ICON_ON : COLOR_ICON_OFF));
        float midY = r.centerY();
        canvas.drawRect(r.left, midY - r.height() * 0.18f, r.left + r.width() * 0.4f,
                midY + r.height() * 0.18f, iconPaint);
        mutePath.reset();
        mutePath.moveTo(r.left + r.width() * 0.4f, r.top);
        mutePath.lineTo(r.right, r.top);
        mutePath.lineTo(r.right, r.bottom);
        mutePath.lineTo(r.left + r.width() * 0.4f, r.bottom);
        mutePath.close();
        Paint.Style prev = iconPaint.getStyle();
        iconPaint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(mutePath, iconPaint);
        iconPaint.setStyle(prev);
        if (!unmuted && applicable) {
            canvas.drawLine(r.left, r.bottom, r.right, r.top, iconPaint);
        }
    }

    private void drawCollapsedStrip(@NonNull Canvas canvas, @NonNull RowLayout row,
                                     long totalMs, @NonNull TimeToX timeToX) {
        Track t = row.track;
        stripPaint.setColor(t.isHidden() ? COLOR_ITEM_HIDDEN : COLOR_STRIP);
        float midY = row.bodyRect.centerY();
        float h = 3f * density;
        for (TimedItem item : t.getItems()) {
            float x0 = timeToX.map(item.getTimelineStartMs());
            long dur = item.getDisplayDurationMs(totalMs);
            float x1 = Math.max(x0 + 2f * density, timeToX.map(item.getTimelineStartMs() + dur));
            canvas.drawRect(x0, midY - h / 2f, x1, midY + h / 2f, stripPaint);
        }
    }

    /** §4.5: per-OBJECT eye — a hidden object's row item ghosts like a hidden track's. */
    private static boolean isObjectHidden(@NonNull TimedItem item) {
        if (item.getTextOverlay() != null) return item.getTextOverlay().isHidden();
        if (item.getSprite() != null) return item.getSprite().isHidden();
        if (item.getWaveform() != null) return item.getWaveform().isHidden();
        if (item.getClip() != null && item.getClip().isOverlayClip()) {
            return item.getClip().isHiddenObject();
        }
        return false;
    }

    private void drawExpandedItems(@NonNull Canvas canvas, @NonNull RowLayout row,
                                    @NonNull Track t, long totalMs, @NonNull TimeToX timeToX,
                                    @Nullable String selectedItemId) {
        int baseColor = baseColorFor(t.getKind());
        boolean ghosted = t.isHidden();
        float top = row.bodyRect.top + 3f * density;
        float bottom = row.bodyRect.bottom - 3f * density;
        // Home ghost first — it sits UNDER the live items (the moving/trimming item
        // slides over its own origin outline).
        if (homeGhostTrackId != null && homeGhostTrackId.equals(t.getId())) {
            drawHomeGhost(canvas, top, bottom, timeToX);
        }
        for (TimedItem item : t.getItems()) {
            boolean lifted = liftedItemId != null && liftedItemId.equals(item.getId());
            // SPLIT-ELEMENT FIX: the lifted item is a single PROXY drawn on the hovered
            // target row (proxyRowTrackId), NOT here on its source row, whenever the proxy
            // has moved to another row. On its home row (proxyRowTrackId == null or ==
            // this row) it still draws here so a same-row move tracks under the finger.
            if (lifted && proxyRowTrackId != null && !proxyRowTrackId.equals(t.getId())) {
                // The moving object left this row; leave only the passive home-ghost gap
                // marker (drawn above) — do NOT draw a second copy of the item here.
                continue;
            }
            drawItemBody(canvas, item, t.getKind(), baseColor,
                    ghosted || isObjectHidden(item), lifted,
                    top, bottom, row.bodyRect.centerY(), totalMs, timeToX, selectedItemId);
        }
        // SPLIT-ELEMENT FIX (single proxy): if THIS row is the hovered cross-row target
        // and the lifted proxy item's home row is a DIFFERENT track, draw the ONE proxy
        // body here — at its already-resolved model X (WYSIWYG, fed by the same drop
        // resolver every move) — so the coherent moving object lives under the finger on
        // exactly this row and nowhere else.
        if (proxyItem != null && proxyRowTrackId != null && proxyRowTrackId.equals(t.getId())
                && liftedItemId != null && liftedItemId.equals(proxyItem.getId())
                && !isItemOnRow(proxyItem, t)) {
            drawItemBody(canvas, proxyItem, t.getKind(), baseColorFor(t.getKind()), ghosted,
                    true, top, bottom, row.bodyRect.centerY(), totalMs, timeToX, selectedItemId);
        }
    }

    /** True if {@code item} is a member of {@code t}'s (ephemeral) item list this frame. */
    private static boolean isItemOnRow(@NonNull TimedItem item, @NonNull Track t) {
        for (TimedItem sib : t.getItems()) {
            if (sib.getId().equals(item.getId())) return true;
        }
        return false;
    }

    /**
     * Draw a single item block (body + label + sprite diamonds + selection/drag outline
     * + trim stripes). Extracted so the SPLIT-ELEMENT single-proxy fix can render the
     * lifted item on its hovered target row (a row it is not yet a member of) with the
     * exact same visuals as an in-place item, guaranteeing one coherent object.
     */
    private void drawItemBody(@NonNull Canvas canvas, @NonNull TimedItem item,
                               @NonNull TrackKind rowKind, int baseColor, boolean ghosted,
                               boolean lifted, float top, float bottom, float centerY,
                               long totalMs, @NonNull TimeToX timeToX,
                               @Nullable String selectedItemId) {
        float x0 = timeToX.map(item.getTimelineStartMs());
        long dur = item.getDisplayDurationMs(totalMs);
        // Draw floor 2dp (was 6dp): short clips must not RENDER wider than their
        // true length — a floored bar overlapped its bookend neighbor and read as
        // "the preview is longer than the clip" (feedback 2026-07-03am). Hit-testing
        // keeps a wider grab floor; forgiving hit > honest hit, but drawing must be
        // honest.
        float x1 = Math.max(x0 + 2f * density, timeToX.map(item.getTimelineStartMs() + dur));
        if (lifted) {
            // Picked-up-for-move "lift": a soft drop shadow just below/right + a
            // brightened, slightly inflated body so it visibly rises off the row
            // (PLAN TARGET CONTRACT: "haptic + a visible lift"). Drawn before the
            // body so the shadow sits under it.
            itemPaint.setColor(0x66000000);
            float sh = 2f * density;
            canvas.drawRoundRect(x0 + sh, top + sh, x1 + sh, bottom + sh,
                    3f * density, 3f * density, itemPaint);
        }
        itemPaint.setColor(ghosted ? COLOR_ITEM_HIDDEN : (lifted ? brighten(baseColor) : baseColor));
        int[] waveform = (item.getAudioClip() != null) ? item.getAudioClip().getWaveform() : null;
        // AV2 quad-band tape: the richest audio representation, drawn in a dark contained body.
        // Falls through to the W2/legacy bars while its lazy extraction is still in flight.
        com.fadcam.ui.faditor.waveform.BandedTimelineWaveformCache.Shaped tape = null;
        if (item.getAudioClip() != null && tapeProvider != null && tapeStyle != null
                && tapeRenderer != null && !ghosted) {
            tape = tapeProvider.get(item.getAudioClip());
        }
        // W2 HD zoom tier: when zoomed in far enough and the span-limited high-density
        // extraction has landed, draw from it instead of the legacy fixed-800 samples —
        // same aqua bars, but word/onset-level detail resolves. Falls back to the legacy
        // int[] (instant, persisted) until the extraction is ready.
        com.fadcam.ui.faditor.model.WaveformData hdWave = null;
        if (tape == null && item.getAudioClip() != null && hdWaveformProvider != null) {
            long clipDurMs = Math.max(1, item.getAudioClip().getTrimmedDurationMs());
            float pxPerSec = (x1 - x0) / (clipDurMs / 1000f);
            hdWave = hdWaveformProvider.get(item.getAudioClip(), pxPerSec);
        }
        if (tape != null) {
            if (lifted) {
                float grow = 1.5f * density;
                canvas.drawRoundRect(x0 - grow, top - grow, x1 + grow, bottom + grow,
                        3f * density, 3f * density, itemPaint);
            }
            // Dark contained body (prototype background), then the tape bands on top.
            int prevBody = itemPaint.getColor();
            itemPaint.setColor(0xFF0A0D11);
            canvas.drawRoundRect(x0, top, x1, bottom, 3f * density, 3f * density, itemPaint);
            itemPaint.setColor(prevBody);
            tapeRect.set(x0, top, x1, bottom);
            long inMs = item.getAudioClip().getInPointMs();
            long durMs = Math.max(1, item.getAudioClip().getTrimmedDurationMs());
            canvas.save();
            canvas.clipRect(x0, top, x1, bottom);
            // A3: blit cached bitmap tiles (bakes on demand) instead of full vector draw every
            // frame; falls back to direct vector draw if the tile cache is absent.
            // F2d (PERF_SPEC_LONGFILE_20260718): pass the visible content window so only
            // on-screen tiles bake/blit — item bodies are laid out in the god view's
            // scroll-translated content space, so the viewport is [hScroll, hScroll + width].
            if (tapeTileCache != null) {
                tapeTileCache.draw(canvas, tapeRect, tape.raw, tape.tape, tape.serial, tapeStyle,
                        inMs, durMs, item.getAudioClip().getId(),
                        lastHScrollOffsetPx, lastHScrollOffsetPx + lastWidthPx);
            } else {
                tapeRenderer.draw(canvas, tapeRect, tape.raw, tape.tape, tapeStyle, inMs, durMs);
            }
            canvas.restore();
            // AV3: captions live INSIDE the audio track, along the bottom — only on an
            // expanded (tall) row, for a caption-enabled audio clip.
            if (item.getAudioClip().isCaptionsEnabled() && (bottom - top) > 40f * density) {
                drawAudioCaptionRibbon(canvas, x0, top, x1, bottom, item.getAudioClip());
            }
        } else if (hdWave != null || waveform != null) {
            if (lifted) {
                float grow = 1.5f * density;
                canvas.drawRoundRect(x0 - grow, top - grow, x1 + grow, bottom + grow,
                        3f * density, 3f * density, itemPaint);
            }
            if (hdWave != null) {
                drawHdAudioWaveform(canvas, hdWave, item.getAudioClip(), x0, top, x1, bottom,
                        ghosted);
            } else {
                drawAudioWaveform(canvas, waveform, x0, top, x1, bottom, baseColor, ghosted);
            }
        } else {
            if (lifted) {
                float grow = 1.5f * density;
                canvas.drawRoundRect(x0 - grow, top - grow, x1 + grow, bottom + grow,
                        3f * density, 3f * density, itemPaint);
            } else {
                canvas.drawRoundRect(x0, top, x1, bottom, 3f * density, 3f * density, itemPaint);
            }
        }
        // §2 item preview images (video filmstrip / image thumb / sprite cells) over the
        // plain body — cache-served, viewport-culled, no decode/alloc on this draw path.
        drawItemPreviews(canvas, item, x0, x1, top, bottom, timeToX);
        // AUDIO volume-automation envelope (audio consolidation 2026-07-07): keep the blue
        // rubber-band + keyframe dots on the unified audio rows (legacy drawAudioTrack port).
        if (item.getAudioClip() != null && !ghosted && item.getAudioClip().hasVolumeKeyframes()) {
            drawVolumeEnvelope(canvas, item.getAudioClip(), x0, top, x1, bottom);
        }
        // C4 §3: opacity-only rubber-band for overlay/sprite item blocks — over a subtle
        // scrim so it stays legible on top of the §2 preview thumbnails/filmstrips.
        drawItemOpacityEnvelope(canvas, item, x0, top, x1, bottom, timeToX, ghosted);
        // CAPTION per-keyframe style segments (layers-UX Slice B): overdraw the amber base bar
        // with each keyframe region's active-style colour, mirroring the old drawLayers caption
        // branch so keyframed captions keep their colour segments in the consolidated renderer.
        if (item.getCaptionSpan() != null && !ghosted) {
            com.fadcam.ui.faditor.model.Clip clip = item.getCaptionSpan().getClip();
            if (clip.hasCaptionStyleKeyframes()) {
                long startMs = item.getTimelineStartMs();
                long endMs = startMs + dur;
                canvas.save();
                canvas.clipRect(x0, top, x1, bottom);
                long segLeftMs = startMs;
                String styleId = clip.captionStyleAtClipMs(0);
                for (com.fadcam.ui.faditor.model.Clip.CaptionStyleKeyframe kf
                        : clip.getCaptionStyleKeyframes()) {
                    long segRightMs = Math.min(startMs + kf.timeMs, endMs);
                    float sx0 = timeToX.map(segLeftMs);
                    float sx1 = timeToX.map(segRightMs);
                    if (sx1 > sx0) {
                        int c = com.fadcam.ui.faditor.transcript.CaptionStyle.byId(styleId).activeColor;
                        itemPaint.setColor(0xDD000000 | (c & 0x00FFFFFF));
                        canvas.drawRect(sx0, top, sx1, bottom, itemPaint);
                    }
                    segLeftMs = segRightMs;
                    styleId = kf.styleId;
                }
                if (segLeftMs < endMs) {
                    float sx0 = timeToX.map(segLeftMs);
                    int c = com.fadcam.ui.faditor.transcript.CaptionStyle.byId(styleId).activeColor;
                    itemPaint.setColor(0xDD000000 | (c & 0x00FFFFFF));
                    canvas.drawRect(sx0, top, x1, bottom, itemPaint);
                }
                canvas.restore();
            } else {
                // UNKEYFRAMED captions (JoyRaptor 2026-07-14): the tape must still reflect the
                // clip's single active style (Bounce→green, Zoom→blue, …), not stay on the
                // amber base — before this, only keyframed segments got their style colour.
                int c = com.fadcam.ui.faditor.transcript.CaptionStyle
                        .byId(clip.getCaptionStyleId()).activeColor;
                itemPaint.setColor(0xDD000000 | (c & 0x00FFFFFF));
                canvas.drawRoundRect(x0, top, x1, bottom, 3f * density, 3f * density, itemPaint);
            }
        }
        String label = labelFor(item);
        if (label != null && !label.isEmpty()) {
            canvas.save();
            canvas.clipRect(x0, top, x1, bottom);
            itemLabelPaint.setColor(ghosted ? 0x88FFFFFF : 0xFFFFFFFF);
            // §3 pinned-scroll for labels (JoyRaptor 2026-07-18): when the item start scrolls
            // off-screen left, the label RIDES the left viewport edge exactly like the
            // image thumb (drawPinnedThumb) — so a long text item stays identifiable
            // while its body spans the screen; it parks at the right end as it leaves.
            float pad = 5f * density;
            float labelW = itemLabelPaint.measureText(label);
            float viewLeft = lastHScrollOffsetPx + HEADER_WIDTH_DP * density;
            float labelX = Math.max(x0 + pad, Math.min(viewLeft + pad, x1 - labelW - pad));
            canvas.drawText(label, labelX,
                    centerY + itemLabelPaint.getTextSize() / 3f, itemLabelPaint);
            canvas.restore();
        }
        // JoyRaptor 2026-07-19 (neutral substrate): the KIND badge belongs to the OBJECT,
        // not the lane — small glyph at the item's left inside edge, payload-derived
        // so it stays correct when any-object-on-any-lane lands. Skipped on slivers.
        if (x1 - x0 > 40f * density) {
            drawKindBadge(canvas, x0 + 3f * density, (top + bottom) / 2f,
                    payloadKindOf(item, rowKind));
        }

        // G9c: chain badge on linked items (visual only — unlink lives in the object
        // drawer / batch menu). Two interlocked stroke rings at the top-right corner,
        // the purple link-family color; skipped on slivers.
        if (linkedItemIds.contains(item.getId()) && x1 - x0 > 26f * density) {
            float r = 3.2f * density;
            float cy = top + 5.5f * density;
            float cx = x1 - 9f * density;
            linkBadgePaint.setColor(ghosted ? 0x668C3DFA : 0xFF8C3DFA);
            canvas.drawCircle(cx - r * 0.7f, cy, r, linkBadgePaint);
            canvas.drawCircle(cx + r * 0.7f, cy, r, linkBadgePaint);
        }

        // Frame-swap diamonds for sprite items (S5)
        if (item.getSprite() != null) {
            float cy = centerY;
            float r = 4f * density;
            int diamondColor = ghosted ? 0x66FFFFFF : 0xE6FFFFFF;
            spriteDiamondPaint.setColor(diamondColor);
            for (FrameTrack.Key k : item.getSprite().getFrameTrack().keys()) {
                float dx = timeToX.map(item.getTimelineStartMs() + k.timeMs);
                if (dx < x0 + 3f || dx > x1 - 3f) continue;
                spriteDiamondPath.rewind();
                spriteDiamondPath.moveTo(dx, cy - r);
                spriteDiamondPath.lineTo(dx + r, cy);
                spriteDiamondPath.lineTo(dx, cy + r);
                spriteDiamondPath.lineTo(dx - r, cy);
                spriteDiamondPath.close();
                canvas.drawPath(spriteDiamondPath, spriteDiamondPaint);
            }
        }
        // C4 §1: consolidated property-keyframe diamonds (green, below the row midline) —
        // drawn AFTER the white frame-swap diamonds so both stay distinct on a sprite row.
        drawItemKeyframeDiamonds(canvas, item, x0, x1, top, bottom, centerY, timeToX, ghosted);
        // Caption style keyframe diamonds (layers-UX Slice B) — mirrors the sprite block above so
        // caption style keys stay visible in the consolidated renderer.
        if (item.getCaptionSpan() != null) {
            com.fadcam.ui.faditor.model.Clip clip = item.getCaptionSpan().getClip();
            if (clip.hasCaptionStyleKeyframes()) {
                float cy = centerY;
                float r = 4f * density;
                spriteDiamondPaint.setColor(ghosted ? 0x66FFFFFF : 0xE6FFFFFF);
                for (com.fadcam.ui.faditor.model.Clip.CaptionStyleKeyframe kf
                        : clip.getCaptionStyleKeyframes()) {
                    float dx = timeToX.map(item.getTimelineStartMs() + kf.timeMs);
                    if (dx < x0 + 3f || dx > x1 - 3f) continue;
                    spriteDiamondPath.rewind();
                    spriteDiamondPath.moveTo(dx, cy - r);
                    spriteDiamondPath.lineTo(dx + r, cy);
                    spriteDiamondPath.lineTo(dx, cy + r);
                    spriteDiamondPath.lineTo(dx - r, cy);
                    spriteDiamondPath.close();
                    canvas.drawPath(spriteDiamondPath, spriteDiamondPaint);
                }
            }
        }
        // Stage 2 (PLAN §6): tap-select a row item → draw a clear selection state —
        // a brightened stroke in the item's OWN color family (not a generic white
        // ring), so the family reads at a glance (purple selection on a purple
        // TEXT item, aqua on an AUDIO item, etc.), plus small trim-handle end caps
        // mirroring the exact zones hitTestItem already hit-tests for a selected
        // item (ITEM_HANDLE_HALF_WIDTH_DP) — those zones were already live/
        // draggable; this just makes them visible instead of an invisible hot zone.
        if (lifted) {
            // S3: while lifted, the drag-state outline is the ONLY outline — the
            // generic near-white selection stroke is suppressed (it was the WHITE
            // the user reported where purple was expected).
            drawDragStateOutline(canvas, x0, top, x1, bottom, baseColor);
        } else if (selectedItemId != null && selectedItemId.equals(item.getId())) {
            // Captions are clip-owned (no delete semantics) — no trash badge.
            drawItemSelection(canvas, x0, top, x1, bottom, baseColor,
                    item.getCaptionSpan() == null);
        }
        if (trimmingItemId != null && trimmingItemId.equals(item.getId())) {
            drawTrimStripes(canvas, x0, top, x1, bottom);
        }
    }

    /** Paints for the audio volume-automation envelope (ported legacy drawAudioTrack visual). */
    private final Paint volEnvLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint volEnvDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean volEnvPaintsInit;

    /**
     * Blue volume-automation envelope (rubber-band line, keyframe-to-keyframe) on an AUDIO
     * item body — ported 1:1 from the legacy {@code EditorTimelineView#drawAudioTrack} so the
     * unified audio rows keep the volume-keyframe visual (audio consolidation 2026-07-07).
     * x = clip-local time across the item block; y maps gain 0..2 (bottom..top), 100% at mid.
     */
    private void drawVolumeEnvelope(@NonNull Canvas canvas,
                                     @NonNull com.fadcam.ui.faditor.model.AudioClip ac,
                                     float x0, float top, float x1, float bottom) {
        if (!volEnvPaintsInit) {
            volEnvPaintsInit = true;
            volEnvLinePaint.setColor(0xFF40C4FF); // light blue
            volEnvLinePaint.setStyle(Paint.Style.STROKE);
            volEnvLinePaint.setStrokeWidth(1.6f * density);
            volEnvLinePaint.setStrokeJoin(Paint.Join.ROUND);
            volEnvDotPaint.setColor(0xFF40C4FF);
            volEnvDotPaint.setStyle(Paint.Style.FILL);
        }
        java.util.List<com.fadcam.ui.faditor.model.AudioClip.VolumeKeyframe> kfs =
                ac.getVolumeKeyframes();
        if (kfs.isEmpty()) return;
        long dur = Math.max(1, ac.getTrimmedDurationMs());
        float w = x1 - x0;
        float h = bottom - top;
        canvas.save();
        canvas.clipRect(x0, top, x1, bottom);
        float prevX = 0f, prevY = 0f;
        for (int k = 0; k < kfs.size(); k++) {
            com.fadcam.ui.faditor.model.AudioClip.VolumeKeyframe kf = kfs.get(k);
            float fx = Math.max(0f, Math.min(1f, kf.timeMs / (float) dur));
            float x = x0 + fx * w;
            float gFrac = Math.max(0f, Math.min(1f, kf.volume / 2.0f));
            float y = bottom - gFrac * h;
            if (k == 0) {
                canvas.drawLine(x0, y, x, y, volEnvLinePaint); // flat hold from clip start
            } else {
                canvas.drawLine(prevX, prevY, x, y, volEnvLinePaint);
            }
            if (k == kfs.size() - 1) {
                canvas.drawLine(x, y, x1, y, volEnvLinePaint); // flat hold to clip end
            }
            canvas.drawCircle(x, y, 2.6f * density, volEnvDotPaint);
            prevX = x;
            prevY = y;
        }
        canvas.restore();
    }

    // ── C4: general keyframe visuals for overlay/sprite item blocks ──────────────

    /**
     * The general-property {@link KeyframeSet} of an overlay ({@link
     * com.fadcam.ui.faditor.model.TextOverlayItem}, covers image too) or a sprite item;
     * {@code null} for every other payload (they have no X/Y/SCALE/ROTATION/OPACITY track).
     */
    @Nullable
    static KeyframeSet keyframeSetOf(@NonNull TimedItem item) {
        if (item.getTextOverlay() != null) return item.getTextOverlay().getKeyframes();
        if (item.getSprite() != null) return item.getSprite().getKeyframes();
        return null;
    }

    /**
     * Item-LOCAL union of key times across ALL property tracks, merged into
     * {@link #KF_CONSOLIDATE_TOLERANCE_MS} buckets — each bucket is ONE consolidated
     * diamond (a solid diamond = ANY property keyed at that time). Bucket time = the
     * earliest key in the bucket. Empty when the item carries no general keyframes.
     */
    @NonNull
    static List<Long> consolidatedKeyTimesLocal(@NonNull TimedItem item) {
        KeyframeSet set = keyframeSetOf(item);
        if (set == null) return Collections.emptyList();
        List<Long> times = new ArrayList<>();
        for (KeyframeTrack t : set.tracks()) {
            for (Keyframe k : t.keyframes) times.add(k.timeMs);
        }
        if (times.isEmpty()) return Collections.emptyList();
        Collections.sort(times);
        List<Long> buckets = new ArrayList<>();
        long cur = times.get(0);
        buckets.add(cur);
        for (int i = 1; i < times.size(); i++) {
            if (times.get(i) - cur > KF_CONSOLIDATE_TOLERANCE_MS) {
                cur = times.get(i);
                buckets.add(cur);
            }
        }
        return buckets;
    }

    /**
     * C4 §2 hit-test: item-LOCAL bucket time of the consolidated diamond within a generous
     * ~12dp zone of content-x {@code x}, or {@code null}. Drives the selected-item keyframe
     * time-shift drag in {@link LayerGestureController}.
     */
    @Nullable
    Long hitTestKeyframeDiamond(@NonNull TimedItem item, float x, @NonNull TimeToX timeToX) {
        List<Long> buckets = consolidatedKeyTimesLocal(item);
        if (buckets.isEmpty()) return null;
        long start = item.getTimelineStartMs();
        float best = 12f * density;
        Long hit = null;
        for (long b : buckets) {
            float dx = Math.abs(timeToX.map(start + b) - x);
            if (dx <= best) { best = dx; hit = b; }
        }
        return hit;
    }

    /**
     * C4 §1: draw the consolidated property-keyframe diamonds — drawer accent GREEN (dimmed
     * when ghosted) so they read differently from the WHITE frame-swap diamonds, and offset
     * just BELOW the row midline so a sprite showing both stays legible at 34dp.
     */
    private void drawItemKeyframeDiamonds(@NonNull Canvas canvas, @NonNull TimedItem item,
                                          float x0, float x1, float top, float bottom,
                                          float centerY, @NonNull TimeToX timeToX,
                                          boolean ghosted) {
        List<Long> buckets = consolidatedKeyTimesLocal(item);
        if (buckets.isEmpty()) return;
        float cy = Math.min(bottom - 4f * density, centerY + 5f * density);
        float r = 3.5f * density;
        kfDiamondPaint.setColor(ghosted ? 0x664CAF50 : 0xE64CAF50);
        long start = item.getTimelineStartMs();
        for (long b : buckets) {
            float dx = timeToX.map(start + b);
            if (dx < x0 + 3f || dx > x1 - 3f) continue;
            spriteDiamondPath.rewind();
            spriteDiamondPath.moveTo(dx, cy - r);
            spriteDiamondPath.lineTo(dx + r, cy);
            spriteDiamondPath.lineTo(dx, cy + r);
            spriteDiamondPath.lineTo(dx - r, cy);
            spriteDiamondPath.close();
            canvas.drawPath(spriteDiamondPath, kfDiamondPaint);
        }
    }

    /**
     * C4 §3: opacity-only rubber-band for an overlay/sprite item block (KeyframeSet.OPACITY;
     * value 0..1 maps bottom→top), only when that track has ≥2 keys. Drawn OVER a subtle
     * dark scrim so the white-ish line stays legible over the §2 preview thumbnails — a
     * distinct visual from the audio band's blue volume envelope.
     */
    private void drawItemOpacityEnvelope(@NonNull Canvas canvas, @NonNull TimedItem item,
                                         float x0, float top, float x1, float bottom,
                                         @NonNull TimeToX timeToX, boolean ghosted) {
        if (ghosted) return;
        KeyframeSet set = keyframeSetOf(item);
        if (set == null) return;
        KeyframeTrack op = set.get(KeyframeSet.OPACITY);
        if (op == null || op.keyframes.size() < 2) return;
        if (!kfEnvPaintsInit) {
            kfEnvPaintsInit = true;
            kfEnvLinePaint.setColor(0xCCFFFFFF);
            kfEnvLinePaint.setStyle(Paint.Style.STROKE);
            kfEnvLinePaint.setStrokeWidth(1.6f * density);
            kfEnvLinePaint.setStrokeJoin(Paint.Join.ROUND);
            kfEnvDotPaint.setColor(0xCCFFFFFF);
            kfEnvDotPaint.setStyle(Paint.Style.FILL);
            kfScrimPaint.setStyle(Paint.Style.FILL);
        }
        long start = item.getTimelineStartMs();
        float h = bottom - top;
        canvas.save();
        canvas.clipRect(x0, top, x1, bottom);
        kfScrimPaint.setColor(0x59000000);
        canvas.drawRoundRect(x0, top, x1, bottom, 3f * density, 3f * density, kfScrimPaint);
        List<Keyframe> ks = op.keyframes;
        float prevX = 0f, prevY = 0f;
        for (int k = 0; k < ks.size(); k++) {
            Keyframe kf = ks.get(k);
            float x = timeToX.map(start + kf.timeMs);
            float v = Math.max(0f, Math.min(1f, kf.value));
            float y = bottom - v * h;
            if (k == 0) {
                canvas.drawLine(x0, y, x, y, kfEnvLinePaint); // flat hold from block start
            } else {
                canvas.drawLine(prevX, prevY, x, y, kfEnvLinePaint);
            }
            if (k == ks.size() - 1) {
                canvas.drawLine(x, y, x1, y, kfEnvLinePaint); // flat hold to block end
            }
            canvas.drawCircle(x, y, 2.4f * density, kfEnvDotPaint);
            prevX = x;
            prevY = y;
        }
        canvas.restore();
    }

    /**
     * W2: source of zoom-tiered waveform data for audio items. Implemented by
     * {@code TimelineWaveformCache} — returns {@code null} when the legacy bars should
     * draw (zoomed out / not extracted yet), kicking the extraction as a side effect.
     */
    public interface HdWaveformProvider {
        @Nullable
        com.fadcam.ui.faditor.model.WaveformData get(
                @NonNull com.fadcam.ui.faditor.model.AudioClip clip, float pxPerSec);
    }

    @Nullable
    private HdWaveformProvider hdWaveformProvider;

    public void setHdWaveformProvider(@Nullable HdWaveformProvider provider) {
        this.hdWaveformProvider = provider;
    }

    // ── Quad-band tape waveform (AV2) — takes precedence over the W2/legacy bars ──

    /** Source of shaped quad-band tape data; {@code null} while lazily extracting. */
    public interface TapeProvider {
        @Nullable
        com.fadcam.ui.faditor.waveform.BandedTimelineWaveformCache.Shaped get(
                @NonNull com.fadcam.ui.faditor.model.AudioClip clip);
    }

    @Nullable
    private TapeProvider tapeProvider;
    @Nullable
    private com.fadcam.ui.faditor.waveform.TapeWaveformStyle tapeStyle;
    @Nullable
    private com.fadcam.ui.faditor.waveform.TapeWaveformRenderer tapeRenderer;
    /** A3: bakes/blit-caches the tape so pan/play frames are one drawBitmap per item. */
    @Nullable
    private com.fadcam.ui.faditor.waveform.TapeTileCache tapeTileCache;
    private final RectF tapeRect = new RectF();

    public void setTapeSource(@Nullable TapeProvider provider,
                              @Nullable com.fadcam.ui.faditor.waveform.TapeWaveformStyle style) {
        this.tapeProvider = provider;
        this.tapeStyle = style;
        if (provider != null && tapeRenderer == null) {
            tapeRenderer = new com.fadcam.ui.faditor.waveform.TapeWaveformRenderer(density);
            tapeTileCache = new com.fadcam.ui.faditor.waveform.TapeTileCache(density);
        }
    }

    /** JoyRaptor's live-customization hook: {@code true} while a settings slider is dragged so the
     *  visible tapes reshape in real time via direct vector draw; {@code false} on release. */
    public void setTapeDirectVectorMode(boolean on) {
        if (tapeTileCache != null) tapeTileCache.setDirectVectorMode(on);
    }

    // ── §2 item preview images (LANE_BADGES_AND_PREVIEWS_SPEC §2) ──────────────────
    // Videos/images/sprites already "read" via their content; these providers surface it
    // ON the row item. Same contract as the tape/HD-waveform providers above: this class
    // stays pure-draw (paints + geometry), while EditorTimelineView OWNS every decode/
    // extraction, the async load, the LRU cache and the invalidate. A provider returns the
    // cached bitmap(s) or {@code null} while it loads — so the draw path never decodes,
    // never allocates a bitmap, and never blocks a scrub frame (the 4-5fps regression rule).

    /** §2 image items: one decoded, size-bounded thumbnail for the overlay's image uri,
     *  or {@code null} while it decodes (the call kicks the async decode + invalidate). */
    public interface ImagePreviewProvider {
        @Nullable android.graphics.Bitmap get(@NonNull String imageUri, int targetHpx);
    }

    /** §2 sprite items: a decode-once loaded sheet renderer + per-key cell resolution. The
     *  renderer draws the cell (it owns the canvas/geometry); the provider owns sheet
     *  lookup + the STEP/HOLD frame resolve so sheet data stays in EditorTimelineView. */
    public interface SpriteCellProvider {
        /** Loaded (decode-once) renderer for {@code sheetId}, or {@code null} while loading. */
        @Nullable com.fadcam.ui.faditor.sprite.SpriteSheetRenderer renderer(@NonNull String sheetId);
        /** Resolved cell index for a frame-track key (direct cell OR preset), or -1. */
        int cellForKey(@NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item,
                       @NonNull FrameTrack.Key key);
    }

    /** §2 video items (overlay/PiP clips): the master T1 filmstrip thumbnails for the clip's
     *  source, reused from EditorTimelineView's own extraction + LRU cache (NOT a new
     *  extractor). {@code null}/empty while extracting. */
    public interface VideoFilmstripProvider {
        @Nullable java.util.List<android.graphics.Bitmap> get(
                @NonNull com.fadcam.ui.faditor.model.Clip clip, int targetHpx);
    }

    @Nullable private ImagePreviewProvider imagePreviewProvider;
    @Nullable private SpriteCellProvider spriteCellProvider;
    @Nullable private VideoFilmstripProvider videoFilmstripProvider;

    public void setImagePreviewProvider(@Nullable ImagePreviewProvider p) { this.imagePreviewProvider = p; }
    public void setSpriteCellProvider(@Nullable SpriteCellProvider p) { this.spriteCellProvider = p; }
    public void setVideoFilmstripProvider(@Nullable VideoFilmstripProvider p) { this.videoFilmstripProvider = p; }

    /** FILTER_BITMAP so scaled thumbs/cells stay smooth; no per-frame allocation. */
    private final Paint previewPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    /** Reused dest rect for every preview blit — the draw path allocates nothing. */
    private final RectF previewDst = new RectF();

    /**
     * §2: draw the item's preview image(s) over its (already-drawn) plain body — a video
     * filmstrip across the body, one image thumbnail at the start (§3-pinned), or a sprite
     * sheet cell at each frame-track keyframe. All bitmaps are cache-served; a cache MISS
     * simply draws nothing this frame (the body colour shows through) and the provider's
     * async load will invalidate. Everything is culled to the visible viewport so a
     * 45-min project pays for on-screen pixels only.
     */
    private void drawItemPreviews(@NonNull Canvas canvas, @NonNull TimedItem item,
                                   float x0, float x1, float top, float bottom,
                                   @NonNull TimeToX timeToX) {
        float h = bottom - top;
        if (h < 6f * density || x1 - x0 < 3f * density) return; // too thin to be legible
        com.fadcam.ui.faditor.model.Clip clip = item.getClip();
        // VIDEO items: filmstrip across the whole body (JoyRaptor: "see where the video is at
        // any given time"). Image-backed clips fall to the single-thumb path below.
        if (clip != null && !clip.isImageClip() && videoFilmstripProvider != null) {
            java.util.List<android.graphics.Bitmap> strip = videoFilmstripProvider.get(clip, (int) h);
            if (strip != null && !strip.isEmpty()) {
                drawFilmstrip(canvas, strip, x0, x1, top, bottom);
            }
            return;
        }
        // IMAGE items: one thumbnail at the start, pinned to the left edge on scroll (§3).
        if (item.getTextOverlay() != null && item.getTextOverlay().isImage()
                && item.getTextOverlay().getImageUri() != null && imagePreviewProvider != null) {
            android.graphics.Bitmap bmp =
                    imagePreviewProvider.get(item.getTextOverlay().getImageUri(), (int) h);
            if (bmp != null) drawPinnedThumb(canvas, bmp, x0, x1, top, bottom);
            return;
        }
        // SPRITE items: the pose/cell at each keyframe, drawn at that keyframe's x.
        if (item.getSprite() != null && spriteCellProvider != null) {
            drawSpriteKeyframeCells(canvas, item, x0, x1, top, bottom, timeToX);
        }
    }

    /**
     * §2 video: tile cached thumbnails across the body, each tile mapped to the thumb at
     * ITS time-fraction (thumbs are sampled evenly across the clip). Culled to the on-
     * screen slice of the body so a long clip costs what's visible, not its full length
     * (the same VIEWPORT-CULL rule the master filmstrip + drawSegmentTranscript use).
     */
    private void drawFilmstrip(@NonNull Canvas canvas,
                               @NonNull java.util.List<android.graphics.Bitmap> thumbs,
                               float x0, float x1, float top, float bottom) {
        android.graphics.Bitmap first = thumbs.get(0);
        if (first == null || first.isRecycled()) return;
        float h = bottom - top;
        float tileW = Math.max(8f * density, h * (first.getWidth() / (float) first.getHeight()));
        float span = x1 - x0;
        if (span <= 0f) return;
        // On-screen slice only.
        float vx0 = Math.max(x0, lastHScrollOffsetPx);
        float vx1 = Math.min(x1, lastHScrollOffsetPx + lastWidthPx);
        if (vx1 <= vx0) return;
        canvas.save();
        canvas.clipRect(x0, top, x1, bottom);
        float startTx = x0 + (float) Math.floor((vx0 - x0) / tileW) * tileW;
        for (float tx = startTx; tx < vx1; tx += tileW) {
            float frac = Math.max(0f, Math.min(0.99999f, (tx - x0 + tileW / 2f) / span));
            int idx = Math.min(thumbs.size() - 1, (int) (frac * thumbs.size()));
            android.graphics.Bitmap b = thumbs.get(idx);
            if (b == null || b.isRecycled()) continue;
            previewDst.set(tx, top, Math.min(tx + tileW, x1), bottom);
            canvas.drawBitmap(b, null, previewDst, previewPaint);
        }
        canvas.restore();
    }

    /**
     * §2 image + §3 pinned-scroll: draw one thumbnail (kept at its own aspect) at the item
     * start; when the start scrolls off-screen left while the item still spans the viewport
     * the thumb RIDES the left viewport edge (mirror of the trash-can's right-edge pin —
     * see {@link #deleteBadgeCx}). The pin sits just right of the pinned badge gutter so it
     * never covers the badges. Badges stay put; only the thumb slides.
     */
    private void drawPinnedThumb(@NonNull Canvas canvas, @NonNull android.graphics.Bitmap bmp,
                                 float x0, float x1, float top, float bottom) {
        if (bmp.isRecycled()) return;
        float h = bottom - top;
        float w = Math.max(2f * density, h * (bmp.getWidth() / (float) bmp.getHeight()));
        w = Math.min(w, x1 - x0);
        // viewport-left in content-x = scroll offset; keep the thumb clear of the pinned
        // header/badge column so it reads as "riding the left edge just past the badges".
        float viewLeft = lastHScrollOffsetPx + HEADER_WIDTH_DP * density;
        // clamp(viewLeft, x0, x1-w): natural at x0 when not scrolled; pinned+sliding while
        // x0 < viewLeft < x1-w; parks at the item's right end as it finally scrolls away.
        float thumbX = Math.max(x0, Math.min(viewLeft, x1 - w));
        canvas.save();
        canvas.clipRect(x0, top, x1, bottom);
        previewDst.set(thumbX, top, thumbX + w, bottom);
        canvas.drawBitmap(bmp, null, previewDst, previewPaint);
        canvas.restore();
    }

    /**
     * §2 sprite: draw the sheet cell at each frame-track keyframe, at that keyframe's
     * item-local x — an "updated preview wherever there is a keyframe". The frame-swap
     * diamonds (drawn later in {@link #drawItemBody}) mark the exact key; the cell shows
     * the pose it swaps to. Culled to the item/viewport; the sheet bitmap is decoded once
     * by the provider, never here.
     */
    private void drawSpriteKeyframeCells(@NonNull Canvas canvas, @NonNull TimedItem item,
                                         float x0, float x1, float top, float bottom,
                                         @NonNull TimeToX timeToX) {
        com.fadcam.ui.faditor.sprite.SpriteOverlayItem sprite = item.getSprite();
        com.fadcam.ui.faditor.sprite.SpriteSheetRenderer sr =
                spriteCellProvider.renderer(sprite.getSheetId());
        if (sr == null) return;
        float h = bottom - top;
        float w = Math.max(2f * density, h * Math.max(0.05f, sr.cellAspect()));
        float viewLeft = lastHScrollOffsetPx, viewRight = lastHScrollOffsetPx + lastWidthPx;
        canvas.save();
        canvas.clipRect(x0, top, x1, bottom);
        for (FrameTrack.Key k : sprite.getFrameTrack().keys()) {
            float dx = timeToX.map(item.getTimelineStartMs() + k.timeMs);
            if (dx + w < Math.max(x0, viewLeft) || dx > Math.min(x1, viewRight)) continue; // cull
            int cell = spriteCellProvider.cellForKey(sprite, k);
            if (cell < 0) continue;
            previewDst.set(dx, top, Math.min(dx + w, x1), bottom);
            sr.drawCell(canvas, cell, previewDst, previewPaint);
        }
        canvas.restore();
    }

    /** Scratch rect for visible-span clipping in {@link #drawHdAudioWaveform}. */
    private final android.graphics.Rect hdClipBounds = new android.graphics.Rect();

    /**
     * HD variant of {@link #drawAudioWaveform}: draws peak-preserving bars from
     * {@link com.fadcam.ui.faditor.model.WaveformData} (200–400 buckets/sec, indexed by
     * absolute SOURCE time via {@code bucketAt}), iterating only the VISIBLE pixel span so
     * a long zoomed-in clip costs what's on screen, not its full length. Each bar takes the
     * MAX of the buckets it covers (max-of-range, not point-sample — same W1 rule that
     * keeps transients visible when zoomed out).
     */
    private void drawHdAudioWaveform(@NonNull Canvas canvas,
                                      @NonNull com.fadcam.ui.faditor.model.WaveformData hd,
                                      @NonNull com.fadcam.ui.faditor.model.AudioClip ac,
                                      float x0, float top, float x1, float bottom,
                                      boolean ghosted) {
        float w = x1 - x0;
        if (w <= 0) return;
        float centerY = (top + bottom) / 2f;
        float halfH = Math.max(1f, (bottom - top) / 2f - 2f * density);
        barPaint.setColor(ghosted ? 0x404CAF50 : 0xCC35F6BF);
        canvas.getClipBounds(hdClipBounds);
        float vx0 = Math.max(x0, hdClipBounds.left);
        float vx1 = Math.min(x1, hdClipBounds.right);
        if (vx1 <= vx0) return;
        // 1dp bars with a hairline gap: enough columns for letter-level onsets at high
        // zoom without degenerating into a solid fill.
        float barW = Math.max(1f, 1f * density);
        float stride = barW + Math.max(0.5f, 0.5f * density);
        long inMs = ac.getInPointMs();
        long durMs = Math.max(1, ac.getTrimmedDurationMs());
        for (float bx = vx0; bx < vx1; bx += stride) {
            long t0 = inMs + (long) ((bx - x0) / w * durMs);
            long t1 = inMs + (long) ((bx + stride - x0) / w * durMs);
            int i0 = hd.bucketAt(t0);
            int i1 = Math.max(i0, hd.bucketAt(Math.max(t0, t1 - 1)));
            float amp = 0f;
            for (int i = i0; i <= i1; i++) {
                if (hd.amplitudes[i] > amp) amp = hd.amplitudes[i];
            }
            amp = (float) Math.pow(amp, 0.7);
            float barH = Math.max(1f, amp * halfH);
            canvas.drawRect(bx, centerY - barH, bx + barW, centerY + barH, barPaint);
        }
    }

    /**
     * AV3: draw a caption ribbon along the INSIDE bottom of an expanded audio track — a thin
     * bar tinted by the clip's caption style with a "CC" tag, so a caption-enabled audio clip
     * shows its captions live inside its own track (the user's "captions on the inside along
     * the bottom"). Full per-word caption text is a later enrichment; this is the affordance.
     */
    private void drawAudioCaptionRibbon(@NonNull Canvas canvas, float x0, float top, float x1,
                                         float bottom,
                                         @NonNull com.fadcam.ui.faditor.model.AudioClip ac) {
        float h = 13f * density;
        float ribbonTop = bottom - h - 2f * density;
        if (ribbonTop < top) return;
        int styleColor;
        try {
            styleColor = com.fadcam.ui.faditor.transcript.CaptionStyle
                    .byId(ac.getCaptionStyleId()).activeColor;
        } catch (Exception e) {
            styleColor = 0xFFFFC107;
        }
        itemPaint.setColor(0xCC000000 | (styleColor & 0x00FFFFFF));
        canvas.drawRoundRect(x0 + 2f * density, ribbonTop, x1 - 2f * density, bottom - 2f * density,
                2f * density, 2f * density, itemPaint);
        canvas.save();
        canvas.clipRect(x0 + 2f * density, ribbonTop, x1 - 2f * density, bottom - 2f * density);
        itemLabelPaint.setColor(0xFFFFFFFF);
        canvas.drawText("CC", x0 + 6f * density, bottom - 5f * density, itemLabelPaint);
        canvas.restore();
    }

    private void drawAudioWaveform(@NonNull Canvas canvas, @NonNull int[] waveform,
                                    float x0, float top, float x1, float bottom,
                                    int baseColor, boolean ghosted) {
        float w = x1 - x0;
        if (w <= 0) return;
        float centerY = (top + bottom) / 2f;
        float halfH = Math.max(1f, (bottom - top) / 2f - 2f * density);
        // Mute baseColor to a dim alpha for the bars
        int barColor = ghosted ? 0x404CAF50 : (0xCC35F6BF);
        barPaint.setColor(barColor);
        float barW = Math.max(1f, 2f * density);
        float step = w / waveform.length;
        for (int i = 0; i < waveform.length; i++) {
            float bx = x0 + i * step;
            if (bx > x1) break;
            float amp = (waveform[i] & 0xFF) / 255f;
            amp = (float) Math.pow(amp, 0.7);
            float barH = Math.max(1f, amp * halfH);
            canvas.drawRect(bx, centerY - barH, bx + barW, centerY + barH, barPaint);
        }
    }

    /**
     * Selection stroke + end-cap trim handles for the currently-selected item
     * (Stage 2; PLAN §6 "row item's accent color... echoed... via brighten/outline").
     * The stroke color is the item's own family color brightened toward white
     * (blended, not replaced) so a purple TEXT item gets a lighter purple ring, an
     * aqua AUDIO item a lighter aqua ring, etc. — reads as "this exact item," not a
     * generic selected-anything indicator.
     */
    private void drawItemSelection(@NonNull Canvas canvas, float x0, float top, float x1, float bottom,
                                     int baseColor, boolean deletable) {
        itemSelectionPaint.setColor(brighten(baseColor));
        float ins = (SELECTION_STROKE_DP * density) / 2f;
        canvas.drawRoundRect(x0 + ins, top + ins, x1 - ins, bottom - ins,
                3f * density, 3f * density, itemSelectionPaint);
        // Small end-cap handles at the trim zones (ITEM_HANDLE_HALF_WIDTH_DP-wide hit
        // zones already existed for a selected item in hitTestItem; this draws them).
        float handleHalf = Math.min(ITEM_HANDLE_HALF_WIDTH_DP * density * 0.4f, (x1 - x0) * 0.15f);
        float handleH = (bottom - top) * 0.7f;
        float capTop = top + (bottom - top - handleH) / 2f;
        Paint.Style prevStyle = itemSelectionPaint.getStyle();
        itemSelectionPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(x0 - handleHalf / 2f, capTop, x0 + handleHalf / 2f, capTop + handleH,
                handleHalf / 2f, handleHalf / 2f, itemSelectionPaint);
        canvas.drawRoundRect(x1 - handleHalf / 2f, capTop, x1 + handleHalf / 2f, capTop + handleH,
                handleHalf / 2f, handleHalf / 2f, itemSelectionPaint);
        itemSelectionPaint.setStyle(prevStyle);
        // Delete badge (redesign): a small trash roundel at the selected item's right
        // end, just inside the right trim cap. Tapping it routes the SAME
        // Callback#onItemDeleteRequested → confirmation dialog the old long-press used
        // (long-press is now pick-up-for-move). Skipped on items too narrow to host it
        // without swallowing the trim caps — geometry shared with hitTestItem via
        // deleteBadgeCx so glyph and hot zone can never drift apart.
        float cx = deletable ? deleteBadgeCx(x0, x1) : Float.NaN;
        if (!Float.isNaN(cx)) {
            float cy = (top + bottom) / 2f;
            float r = DELETE_BADGE_RADIUS_DP * density;
            int prevColor = itemSelectionPaint.getColor();
            float prevW = itemSelectionPaint.getStrokeWidth();
            itemSelectionPaint.setStyle(Paint.Style.FILL);
            itemSelectionPaint.setColor(0xDD1C1C22);
            canvas.drawCircle(cx, cy, r, itemSelectionPaint);
            itemSelectionPaint.setStyle(Paint.Style.STROKE);
            itemSelectionPaint.setStrokeWidth(1.2f * density);
            itemSelectionPaint.setColor(0xFFFFFFFF);
            // Minimal trash glyph: lid line over a body outline.
            canvas.drawLine(cx - 0.55f * r, cy - 0.45f * r, cx + 0.55f * r, cy - 0.45f * r,
                    itemSelectionPaint);
            canvas.drawRoundRect(cx - 0.38f * r, cy - 0.2f * r, cx + 0.38f * r, cy + 0.55f * r,
                    1f * density, 1f * density, itemSelectionPaint);
            itemSelectionPaint.setStrokeWidth(prevW);
            itemSelectionPaint.setColor(prevColor);
            itemSelectionPaint.setStyle(prevStyle);
        }
    }

    /** Radius of the selected item's delete badge (the trash roundel).
     *  9dp (was 7) — user feedback 2026-07-03: "a little hard to hit" + log-proven
     *  (a whole hand-test session produced ZERO DELETE-zone hits, all BODY). */
    private static final float DELETE_BADGE_RADIUS_DP = 9f;

    /**
     * Center-x (content space) of the selected item's delete badge, or {@link Float#NaN}
     * when the item is too narrow to host one without colliding with the left trim
     * handle's zone. Single source of truth for {@link #drawItemSelection} AND
     * {@link #hitTestItem}.
     *
     * <p>Natural spot: just inside the right trim cap. If the item's right end runs past
     * the right edge of the visible viewport, the badge PINS to the rightmost on-screen
     * position instead, riding the screen edge as the view scrolls until the item's real
     * end comes into view — so on a long item you never have to travel to its end to
     * delete it (user feedback 2026-07-03).</p>
     */
    private float deleteBadgeCx(float x0, float x1) {
        float r = DELETE_BADGE_RADIUS_DP * density;
        float handle = ITEM_HANDLE_HALF_WIDTH_DP * density;
        float cx = x1 - handle - r;
        if (lastWidthPx > 0f) {
            float pinnedCx = lastHScrollOffsetPx + lastWidthPx - r - 4f * density;
            cx = Math.min(cx, pinnedCx);
        }
        return (cx - r < x0 + handle) ? Float.NaN : cx;
    }

    /** Blend {@code color} 55% toward white, preserving its alpha (a "brightened" accent). */
    private static int brighten(int color) {
        return blendToWhite(color, 0.55f);
    }

    /** Blend {@code color} {@code t} of the way toward white, preserving its alpha. */
    private static int blendToWhite(int color, float t) {
        int a = (color >>> 24) & 0xFF;
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;
        r = (int) (r + (255 - r) * t);
        g = (int) (g + (255 - g) * t);
        b = (int) (b + (255 - b) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private int baseColorFor(@NonNull TrackKind kind) {
        switch (kind) {
            case TEXT:
            case STICKER:
                return COLOR_ITEM_TEXT;
            case AUDIO:
                return COLOR_ITEM_AUDIO;
            case SPRITE:
                return COLOR_ITEM_SPRITE;
            case CAPTION:
                return COLOR_ITEM_CAPTION;
            case VISUALIZER:
                return COLOR_ITEM_VIZ;
            default:
                return COLOR_ITEM_VIDEO;
        }
    }

    /**
     * Draw the row's kind badge (LANE_BADGES spec §1) at {@code (leftX, cy)} —
     * small canvas-drawn glyphs matching the gutter icon style: filmstrip with
     * sprocket holes (video), T-in-a-box (text), mountain-in-a-frame (image),
     * stickman-in-a-ring (sprite), CC box (captions), bars (visualizer/audio).
     */
    /** The OBJECT's own kind (JoyRaptor 2026-07-19: badges ride objects, lanes are neutral).
     *  Falls back to the row kind for payloads without a distinct identity. */
    @NonNull
    private static TrackKind payloadKindOf(@NonNull TimedItem item, @NonNull TrackKind rowKind) {
        if (item.getTextOverlay() != null) {
            return item.getTextOverlay().isImage() ? TrackKind.IMAGE : TrackKind.TEXT;
        }
        if (item.getSprite() != null) return TrackKind.SPRITE;
        if (item.getAudioClip() != null) return TrackKind.AUDIO;
        if (item.getWaveform() != null) return TrackKind.VISUALIZER;
        if (item.getCaptionSpan() != null) return TrackKind.CAPTION;
        if (item.getClip() != null && item.getClip().isOverlayClip()) return TrackKind.VIDEO;
        return rowKind;
    }

    private void drawKindBadge(@NonNull Canvas canvas, float leftX, float cy,
                               @NonNull TrackKind kind) {
        float s = 12f * density;          // badge box size
        float l = leftX, t = cy - s / 2f, r = leftX + s * 1.25f, b = cy + s / 2f;
        int color = 0xFFB9BdC4;
        Paint.Style prevStyle = iconPaint.getStyle();
        float prevStroke = iconPaint.getStrokeWidth();
        int prevColor = iconPaint.getColor();
        iconPaint.setColor(color);
        iconPaint.setStrokeWidth(1.3f * density);

        switch (kind) {
            case TEXT:
            case CAPTION: {
                iconPaint.setStyle(Paint.Style.STROKE);
                canvas.drawRoundRect(l, t, r, b, 2f * density, 2f * density, iconPaint);
                iconPaint.setStyle(Paint.Style.FILL);
                float ts = namePaint.getTextSize();
                namePaint.setTextSize(s * (kind == TrackKind.CAPTION ? 0.62f : 0.8f));
                int pc = namePaint.getColor();
                namePaint.setColor(color);
                String glyph = kind == TrackKind.CAPTION ? "CC" : "T";
                float tw = namePaint.measureText(glyph);
                canvas.drawText(glyph, (l + r) / 2f - tw / 2f,
                        cy + namePaint.getTextSize() * 0.36f, namePaint);
                namePaint.setTextSize(ts);
                namePaint.setColor(pc);
                break;
            }
            case STICKER: { // image: mountain in a frame + sun
                iconPaint.setStyle(Paint.Style.STROKE);
                canvas.drawRoundRect(l, t, r, b, 1.5f * density, 1.5f * density, iconPaint);
                android.graphics.Path mtn = new android.graphics.Path();
                mtn.moveTo(l + s * 0.12f, b - s * 0.15f);
                mtn.lineTo(l + s * 0.45f, t + s * 0.35f);
                mtn.lineTo(l + s * 0.72f, b - s * 0.15f);
                canvas.drawPath(mtn, iconPaint);
                iconPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(r - s * 0.3f, t + s * 0.3f, s * 0.1f, iconPaint);
                break;
            }
            case SPRITE: { // stickman in a ring
                iconPaint.setStyle(Paint.Style.STROKE);
                float rad = s * 0.55f;
                float cx = l + rad;
                canvas.drawCircle(cx, cy, rad, iconPaint);
                canvas.drawCircle(cx, cy - rad * 0.45f, rad * 0.18f, iconPaint); // head
                canvas.drawLine(cx, cy - rad * 0.25f, cx, cy + rad * 0.25f, iconPaint); // body
                canvas.drawLine(cx - rad * 0.35f, cy - rad * 0.05f,
                        cx + rad * 0.35f, cy - rad * 0.05f, iconPaint);          // arms
                canvas.drawLine(cx, cy + rad * 0.25f, cx - rad * 0.3f, cy + rad * 0.6f, iconPaint);
                canvas.drawLine(cx, cy + rad * 0.25f, cx + rad * 0.3f, cy + rad * 0.6f, iconPaint);
                break;
            }
            case VISUALIZER: { // rising bars
                iconPaint.setStyle(Paint.Style.FILL);
                float bw = s * 0.18f;
                float[] hs = {0.35f, 0.7f, 0.5f, 0.95f};
                for (int i = 0; i < hs.length; i++) {
                    float bx = l + i * (bw + 1.5f * density);
                    canvas.drawRect(bx, b - s * hs[i], bx + bw, b, iconPaint);
                }
                break;
            }
            case AUDIO: { // mirrored waveform around a center line
                iconPaint.setStyle(Paint.Style.FILL);
                float bw = s * 0.16f;
                float[] hs = {0.25f, 0.5f, 0.35f, 0.45f, 0.2f};
                for (int i = 0; i < hs.length; i++) {
                    float bx = l + i * (bw + 1.2f * density);
                    canvas.drawRect(bx, cy - s * hs[i] / 1.4f, bx + bw,
                            cy + s * hs[i] / 1.4f, iconPaint);
                }
                break;
            }
            default: { // VIDEO / MASTER / PiP: filmstrip with sprocket holes
                iconPaint.setStyle(Paint.Style.STROKE);
                canvas.drawRoundRect(l, t, r, b, 1.5f * density, 1.5f * density, iconPaint);
                iconPaint.setStyle(Paint.Style.FILL);
                float hole = s * 0.14f;
                for (int i = 0; i < 3; i++) {
                    float hx = l + s * 0.18f + i * s * 0.4f;
                    canvas.drawRect(hx, t + hole * 0.6f, hx + hole, t + hole * 1.6f, iconPaint);
                    canvas.drawRect(hx, b - hole * 1.6f, hx + hole, b - hole * 0.6f, iconPaint);
                }
                break;
            }
        }
        iconPaint.setStyle(prevStyle);
        iconPaint.setStrokeWidth(prevStroke);
        iconPaint.setColor(prevColor);
    }

    @Nullable
    private String labelFor(@NonNull TimedItem item) {
        if (item.getTextOverlay() != null) {
            return item.getTextOverlay().isImage() ? "IMG" : item.getTextOverlay().getText();
        }
        if (item.getAudioClip() != null) return item.getAudioClip().getLabel();
        if (item.getSprite() != null) {
            int keys = item.getSprite().getFrameTrack().size();
            return keys > 1 ? "✦ " + keys : "✦";
        }
        if (item.getWaveform() != null) return "VIZ";
        if (item.getCaptionSpan() != null) return "CC";
        if (item.getClip() != null) return null; // master items already show thumbnails elsewhere
        return null;
    }

    // ── Touch (header toggles + collapse caret + row-region vertical scroll) ──

    /** Functional geometry bridge into the god view's segment-aware time→x mapping. */
    public interface TimeToX { float map(long timeMs); }

    /**
     * Hit-test a DOWN/tap at content coordinates (already scroll-adjusted for the
     * god view's own horizontal scroll) against the last-laid-out header icon zones.
     * Returns {@code null} if the tap is outside the row region or on a locked
     * track's body (locked tracks ignore taps entirely — PLAN Part 7 M6 scope item 4).
     */
    @Nullable
    public HeaderHit hitTestHeader(float x, float y, float topPx) {
        if (!inAnyBand(y, topPx)) return null;
        for (RowLayout row : rows) {
            float localY = bandLocalY(row, y, topPx);
            // Positive-form check: bandLocalY returns NaN for out-of-band rows, and NaN
            // fails EVERY comparison — the negated form would fall through as a hit.
            if (!(localY >= row.headerRect.top && localY <= row.headerRect.bottom)) continue;
            // X-bounds guard: the headerRect spans the row's full height but only the
            // left HEADER_WIDTH_DP column is the header — the rest of the row (to its
            // right) is the item BODY. Without this check, a touch anywhere in the row
            // body fell through the four icon .contains() tests below and hit the
            // HitZone.NONE fallthrough, so hitTestHeader "consumed" every body touch —
            // starving the M7 item hit-test (select/long-press/drag) and the horizontal
            // scrub pass-through of the DOWN entirely. A body-column touch is not a
            // header hit: skip this row so the caller falls through to onRowBodyDown.
            if (x < row.headerRect.left || x > row.headerRect.right) continue;
            if (row.caretRect.contains(x, localY)) return new HeaderHit(row.track, HitZone.CARET);
            if (row.hideRect.contains(x, localY)) return new HeaderHit(row.track, HitZone.HIDE);
            if (row.lockRect.contains(x, localY)) return new HeaderHit(row.track, HitZone.LOCK);
            if (row.muteRect.contains(x, localY)) return new HeaderHit(row.track, HitZone.MUTE);
            return new HeaderHit(row.track, HitZone.NONE); // header hit, no icon — still consume
        }
        return null;
    }

    /**
     * True if {@code (x,y)} falls within the row region at all (used by the god view to
     * decide whether to route the touch here vs. its own segment/audio hit-testing).
     * Covers BOTH bands: the floating band at {@code topPx} and the audio band at its
     * layout-time anchor (audio consolidation 2026-07-07).
     */
    public boolean isWithinRowRegion(float y, float topPx) {
        return !rows.isEmpty() && inAnyBand(y, topPx);
    }

    /** Whether {@code y} (screen) falls inside the floating band's viewport OR the audio band. */
    private boolean inAnyBand(float y, float topPx) {
        boolean inFloating = viewportHeightPx > 0f && y >= topPx && y <= topPx + viewportHeightPx;
        boolean inAudio = audioBandHeightPx > 0f && y >= lastAudioTopPx
                && y <= lastAudioTopPx + audioBandHeightPx;
        return inFloating || inAudio;
    }

    /**
     * Screen-y → the given row's band-local content-y. The two bands live in different
     * content spaces: floating rows scroll within the capped viewport at {@code topPx};
     * audio rows sit unscrolled at the audio band anchor stored by the last layout().
     * Returns {@code Float.NaN} when {@code y} is OUTSIDE the row's own band region —
     * without that guard a touch in one band could numerically alias onto a row of the
     * OTHER band (e.g. an audio-band touch mapping into a floating row scrolled past the
     * band-1 viewport). NaN fails every subsequent rect comparison, so callers need no
     * special-casing.
     */
    private float bandLocalY(@NonNull RowLayout row, float y, float topPx) {
        if (row.floatingBand) {
            if (y < topPx || y > topPx + viewportHeightPx) return Float.NaN;
            return y - topPx + scrollOffsetPx;
        }
        if (y < lastAudioTopPx || y > lastAudioTopPx + audioBandHeightPx) return Float.NaN;
        return y - lastAudioTopPx;
    }

    /** True if the row body under {@code y} belongs to a LOCKED track (taps must be ignored). */
    public boolean isRowLocked(float y, float topPx) {
        for (RowLayout row : rows) {
            float localY = bandLocalY(row, y, topPx);
            if (localY >= row.headerRect.top && localY <= row.headerRect.bottom) {
                return row.track.isLocked();
            }
        }
        return false;
    }

    // ── M10: drag-between-layers + drop-to-new-layer geometry queries ─────────

    /**
     * The track whose ROW (header or body, expanded or collapsed) contains content-space
     * {@code y}, or {@code null} if {@code y} is outside every row (e.g. in the gap
     * between rows — gap targets are {@link #gapIndexAt}'s job, Slice 2). Used by
     * {@link LayerGestureController} to find the cross-row drag target
     * as the finger moves, independent of lock/hidden state (the caller decides whether
     * a locked/hidden row is a valid drop target — PLAN Part 7 M10 scope 5: "no drags
     * in or out" of locked/hidden rows).
     */
    @Nullable
    public Track rowTrackAt(float y, float topPx) {
        for (RowLayout row : rows) {
            float localY = bandLocalY(row, y, topPx);
            if (localY >= row.headerRect.top && localY <= row.bodyRect.bottom) {
                return row.track;
            }
        }
        return null;
    }

    /**
     * True if this track's row came from the {@code layers} (floating TEXT/STICKER/
     * IMAGE/VIDEO) band rather than {@code audioTracks} — used to decide whether a
     * cross-row drag is even eligible (an item can only move within its own band;
     * PLAN Part 7 row M10 does not scope cross-band moves) and which {@link TrackKind}
     * a same-band "new layer" drop should create.
     */
    public boolean isFloatingBandRow(@NonNull Track track) {
        for (RowLayout row : rows) {
            if (row.track.getId().equals(track.getId())) return row.floatingBand;
        }
        return true;
    }

    /** Half-width (px) of an item's edge trim-handle hit-zone, shared with the item-hit-test. */
    private static final float ITEM_HANDLE_HALF_WIDTH_DP = 10f;

    /**
     * Hit-test a DOWN/tap at content coordinates against the item blocks drawn by
     * {@link #drawExpandedItems} in the last {@link #layout} call (M7; PLAN §6.3).
     * Only EXPANDED, unlocked, non-hidden rows are eligible — a collapsed row's thin
     * summary strip is not individually editable, a locked track ignores all row-body
     * gestures (PLAN Part 7 M7 scope item 3), and a hidden track's items are not
     * interactive (same scope item). Returns {@code null} when the touch is outside the
     * row region, on a track header, or misses every item's body/handle zone (a miss
     * inside the row body is still "within the row" — callers use
     * {@link #isWithinRowRegion} first to decide whether to fall through at all).
     *
     * @param selectedItemId when non-null, trim-handle zones are only hit-tested for
     *                       the item with this id (mirrors the existing audio/overlay
     *                       convention: handles only appear on the selected item).
     */
    @Nullable
    public ItemHit hitTestItem(float x, float y, float topPx, long totalMs,
                                @NonNull TimeToX timeToX, @Nullable String selectedItemId) {
        if (!inAnyBand(y, topPx)) return null;
        float handleHalf = ITEM_HANDLE_HALF_WIDTH_DP * density;
        for (RowLayout row : rows) {
            float localY = bandLocalY(row, y, topPx);
            // Positive-form checks: NaN (out-of-band row) must fail, not fall through.
            if (!(localY >= row.bodyRect.top && localY <= row.bodyRect.bottom)) continue;
            Track t = row.track;
            if (t.isCollapsed() || t.isLocked() || t.isHidden()) return null;
            float top = row.bodyRect.top + 3f * density;
            float bottom = row.bodyRect.bottom - 3f * density;
            if (!(localY >= top && localY <= bottom)) return null;
            for (TimedItem item : t.getItems()) {
                float x0 = timeToX.map(item.getTimelineStartMs());
                long dur = item.getDisplayDurationMs(totalMs);
                float x1 = Math.max(x0 + 6f * density, timeToX.map(item.getTimelineStartMs() + dur));
                if (x < x0 - handleHalf || x > x1 + handleHalf) continue;
                boolean selected = selectedItemId != null && selectedItemId.equals(item.getId());
                if (selected && x <= x0 + handleHalf) {
                    return new ItemHit(t, item, ItemZone.LEFT_HANDLE);
                }
                if (selected && x >= x1 - handleHalf) {
                    return new ItemHit(t, item, ItemZone.RIGHT_HANDLE);
                }
                if (selected) {
                    // Delete badge — checked AFTER the trim handles (review fix
                    // 2026-07-03: the badge's slop circle used to swallow the inner half
                    // of the RIGHT_HANDLE zone, so a near-edge trim grab opened the
                    // delete dialog). Trim owns [x1-handleHalf, x1+handleHalf]; the badge
                    // gets the slop circle left of it (and the full circle when pinned
                    // mid-item on long clips, where no handle overlaps).
                    // Captions never host the badge (clip-owned, no delete semantics)
                    // — keep the hot zone in lockstep with drawItemSelection.
                    float cx = item.getCaptionSpan() == null
                            ? deleteBadgeCx(x0, x1) : Float.NaN;
                    if (!Float.isNaN(cx)) {
                        float cy = (top + bottom) / 2f;
                        float slopR = DELETE_BADGE_RADIUS_DP * density * 2.0f;
                        float ddx = x - cx, ddy = localY - cy;
                        if (ddx * ddx + ddy * ddy <= slopR * slopR) {
                            return new ItemHit(t, item, ItemZone.DELETE);
                        }
                    }
                }
                if (x >= x0 && x <= x1) {
                    return new ItemHit(t, item, ItemZone.BODY);
                }
            }
            return null; // inside this row's body but not on any item
        }
        return null;
    }

    // ── G8 marquee multi-select ─────────────────────────────────────

    /**
     * G8: collect every item whose block intersects (inclusive/crossing mode) or is
     * fully enclosed by (exclusive/window mode) the marquee. The rect arrives in the
     * renderer's CONTENT space: x = scroll-adjusted content-x (the same axis
     * {@code timeToX} maps into), y = band-local content-y (0 = top of the first row
     * region, the same axis {@code rows} rects live on). Locked/hidden/collapsed rows
     * never contribute — same exclusions as {@link #hitTestItem}.
     */
    @NonNull
    public List<ItemHit> collectItemsInRect(@NonNull RectF contentRect, long totalMs,
                                            @NonNull TimeToX timeToX,
                                            boolean requireFullContainment) {
        List<ItemHit> out = new ArrayList<>();
        // Audio-band rows live in a DIFFERENT content space (band 2, anchored at
        // lastAudioTopPx, unscrolled) than the marquee rect (band-1 coords). The
        // follow-up is closed: convert the rect into band-2 local space per row
        // instead of skipping — band1-local + (lastTopPx - scrollOffsetPx) = screen,
        // screen - lastAudioTopPx = band2-local.
        float bandShift = (lastTopPx - scrollOffsetPx) - lastAudioTopPx;
        for (RowLayout row : rows) {
            Track t = row.track;
            if (t.isCollapsed() || t.isLocked() || t.isHidden()) continue;
            float rTop = contentRect.top, rBot = contentRect.bottom;
            if (!row.floatingBand) { rTop += bandShift; rBot += bandShift; }
            float top = row.bodyRect.top + 3f * density;
            float bottom = row.bodyRect.bottom - 3f * density;
            boolean yIntersects = bottom >= rTop && top <= rBot;
            boolean yContained = top >= rTop && bottom <= rBot;
            if (requireFullContainment ? !yContained : !yIntersects) continue;
            for (TimedItem item : t.getItems()) {
                float x0 = timeToX.map(item.getTimelineStartMs());
                long dur = item.getDisplayDurationMs(totalMs);
                float x1 = Math.max(x0 + 6f * density,
                        timeToX.map(item.getTimelineStartMs() + dur));
                boolean xIntersects = x1 >= contentRect.left && x0 <= contentRect.right;
                boolean xContained = x0 >= contentRect.left && x1 <= contentRect.right;
                if (requireFullContainment ? xContained : xIntersects) {
                    out.add(new ItemHit(t, item, ItemZone.BODY));
                }
            }
        }
        return out;
    }

    /**
     * G8: highlight every marquee-selected item — a translucent white wash + white
     * stroke over each item block. Must be called inside the god view's
     * {@code canvas.translate(-hScrollOffsetPx, 0)} block, right after {@link #layout}
     * (same convention as layout itself): x is content-space, the clip window is the
     * band viewport expressed in content-x. Geometry mirrors {@link #hitTestItem}/
     * {@link #collectItemsInRect} exactly.
     */
    public void drawMultiSelection(@NonNull Canvas canvas, @NonNull java.util.Set<String> ids,
                                   float topPx, long totalMs, @NonNull TimeToX timeToX) {
        if (ids.isEmpty() || rows.isEmpty()) return;
        canvas.save();
        canvas.clipRect(lastHScrollOffsetPx, topPx,
                lastHScrollOffsetPx + lastWidthPx, topPx + viewportHeightPx);
        for (RowLayout row : rows) {
            if (!row.floatingBand) continue; // audio rows drawn in their own pass below
            Track t = row.track;
            float top = topPx + row.bodyRect.top + 3f * density - scrollOffsetPx;
            float bottom = topPx + row.bodyRect.bottom - 3f * density - scrollOffsetPx;
            drawMultiSelectionRow(canvas, t, ids, top, bottom, totalMs, timeToX);
        }
        canvas.restore();
        // Audio band pass (band-2 anchors, unscrolled) — marquee coverage of the
        // audio band, the collectItemsInRect follow-up now closed.
        if (audioBandHeightPx > 0f) {
            canvas.save();
            canvas.clipRect(lastHScrollOffsetPx, lastAudioTopPx,
                    lastHScrollOffsetPx + lastWidthPx, lastAudioTopPx + audioBandHeightPx);
            for (RowLayout row : rows) {
                if (row.floatingBand) continue;
                float top = lastAudioTopPx + row.bodyRect.top + 3f * density;
                float bottom = lastAudioTopPx + row.bodyRect.bottom - 3f * density;
                drawMultiSelectionRow(canvas, row.track, ids, top, bottom, totalMs, timeToX);
            }
            canvas.restore();
        }
    }

    /** One row's marquee-highlight blocks (shared by the floating + audio passes). */
    private void drawMultiSelectionRow(@NonNull Canvas canvas, @NonNull Track t,
                                       @NonNull java.util.Set<String> ids,
                                       float top, float bottom, long totalMs,
                                       @NonNull TimeToX timeToX) {
        for (TimedItem item : t.getItems()) {
            if (!ids.contains(item.getId())) continue;
            float x0 = timeToX.map(item.getTimelineStartMs());
            long dur = item.getDisplayDurationMs(totalMs);
            float x1 = Math.max(x0 + 6f * density,
                    timeToX.map(item.getTimelineStartMs() + dur));
            itemPaint.setColor(0x33FFFFFF);
            canvas.drawRoundRect(x0, top, x1, bottom, 4f * density, 4f * density, itemPaint);
            itemSelectionPaint.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(x0, top, x1, bottom, 4f * density, 4f * density,
                    itemSelectionPaint);
        }
    }

    /** G8: current vertical scroll (px) of the band viewport, for marquee coordinate math. */
    public float getScrollOffsetPx() { return scrollOffsetPx; }

    /** G8: resolve a set of selected item ids back to live (track, item) pairs. */
    @NonNull
    public List<ItemHit> collectItemsByIds(@NonNull java.util.Set<String> ids) {
        List<ItemHit> out = new ArrayList<>();
        if (ids.isEmpty()) return out;
        for (RowLayout row : rows) {
            for (TimedItem item : row.track.getItems()) {
                if (ids.contains(item.getId())) {
                    out.add(new ItemHit(row.track, item, ItemZone.BODY));
                }
            }
        }
        return out;
    }

    /**
     * Scroll the FLOATING band so the row hosting {@code itemId} is fully visible
     * (preview-tap → reveal-the-layer, JoyRaptor 2026-07-17). Audio-band rows are fixed
     * below master and always visible — no-op for those. Uses the row geometry of
     * the last {@link #layout} pass (content-space). Returns true if the scroll moved.
     */
    public boolean revealRowForItem(@NonNull String itemId) {
        for (RowLayout row : rows) {
            if (!row.floatingBand) continue;
            boolean hosts = false;
            for (TimedItem item : row.track.getItems()) {
                if (itemId.equals(item.getId())) { hosts = true; break; }
            }
            if (!hosts) continue;
            float pad = ROW_GAP_DP * density;
            float before = scrollOffsetPx;
            if (row.bodyRect.top < scrollOffsetPx) {
                scrollOffsetPx = clampScroll(row.bodyRect.top - pad);
            } else if (row.bodyRect.bottom > scrollOffsetPx + viewportHeightPx) {
                scrollOffsetPx = clampScroll(row.bodyRect.bottom - viewportHeightPx + pad);
            }
            return scrollOffsetPx != before;
        }
        return false;
    }

    /**
     * SCREEN-space {top, bottom} of the row band hosting {@code itemId} from the
     * last {@link #layout} pass, or null when the item isn't laid out or its row is
     * scrolled fully out of the floating viewport. KineMaster-playhead lane
     * (deferred seam): lets the god view draw its context guides along a
     * floating/audio row without duplicating band math. Floating rows are clamped
     * to the band viewport so guides never bleed over master.
     */
    @Nullable
    public float[] screenBandForItem(@NonNull String itemId) {
        for (RowLayout row : rows) {
            boolean hosts = false;
            for (TimedItem item : row.track.getItems()) {
                if (itemId.equals(item.getId())) { hosts = true; break; }
            }
            if (!hosts) continue;
            if (row.floatingBand) {
                float top = lastTopPx + row.bodyRect.top - scrollOffsetPx;
                float bottom = lastTopPx + row.bodyRect.bottom - scrollOffsetPx;
                float vTop = lastTopPx;
                float vBot = lastTopPx + viewportHeightPx;
                if (bottom < vTop || top > vBot) return null; // scrolled out of view
                return new float[]{Math.max(top, vTop), Math.min(bottom, vBot)};
            }
            return new float[]{lastAudioTopPx + row.bodyRect.top,
                               lastAudioTopPx + row.bodyRect.bottom};
        }
        return null;
    }

    /** Scroll the row region by {@code dy} px (clamped); returns true if it consumed the scroll. */
    public boolean scrollBy(float dy) {
        float before = scrollOffsetPx;
        scrollOffsetPx = clampScroll(scrollOffsetPx + dy);
        return scrollOffsetPx != before;
    }

    private float clampScroll(float v) {
        float max = Math.max(0f, contentHeightPx - viewportHeightPx);
        return Math.max(0f, Math.min(v, max));
    }

    public float getContentHeightPx() { return contentHeightPx; }
    public float getViewportHeightPx() { return viewportHeightPx; }
    /** Height (px) of the audio band from the last layout (0 = no audio rows). */
    public float getAudioBandHeightPx() { return audioBandHeightPx; }

    // ── G6 resizable timeline ───────────────────────────────────────
    /** Current layer-band viewport cap (dp), user-controlled via the preview/timeline grab bar. */
    public float getMaxVisibleRowsDp() { return maxVisibleRowsDp; }

    /** Default cap (dp) — used to reset / seed persistence. */
    public float getDefaultMaxVisibleRowsDp() { return DEFAULT_MAX_VISIBLE_ROWS_DP; }

    /**
     * G6: set the layer-band viewport cap (dp), clamped to [{@link #MIN_VISIBLE_ROWS_DP},
     * {@link #MAX_VISIBLE_ROWS_CAP_DP}]. Returns the clamped value actually applied so the caller
     * can persist / feed a slider without re-clamping. Pure state — the caller triggers relayout.
     */
    public float setMaxVisibleRowsDp(float dp) {
        maxVisibleRowsDp = Math.max(MIN_VISIBLE_ROWS_DP, Math.min(MAX_VISIBLE_ROWS_CAP_DP, dp));
        return maxVisibleRowsDp;
    }
}

