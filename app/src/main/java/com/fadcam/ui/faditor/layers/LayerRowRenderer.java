package com.fadcam.ui.faditor.layers;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.Keyframe;
import com.fadcam.ui.faditor.keyframe.KeyframeGlyph;
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
    /**
     * Width of the left gutter on every lane row. Sized to exactly what it holds — caret,
     * gap, mute, gap — after §3b moved mute flush against the caret. It was 92dp back when
     * the gutter also carried a track name, a kind badge and eye/lock glyphs; those all
     * retired to the object drawer and the width never followed them down, so ~58dp of every
     * lane was empty space.
     */
    private static final float HEADER_WIDTH_DP =
            /* caret left inset */ 2.4f + /* caret */ 12f + /* gap */ 4f + /* mute */ 12f
            + /* trailing gap */ 4f;
    private static final float ROW_HEIGHT_EXPANDED_DP = 34f;
    private static final float ROW_HEIGHT_COLLAPSED_DP = 14f;
    /** AV3: expanded AUDIO rows are taller so the two-lane quad-band tape (and the volume
     *  keyframe rubber-band drawn over it) have room. Floating layer rows keep 34dp. */
    private static final float ROW_HEIGHT_AUDIO_EXPANDED_DP = 76f;
    private static final float ROW_GAP_DP = 3f;
    /** FADE_KNOBS §2.1 top-pad: outboard knobs float ~20dp above the clip's top edge. The study
     *  flagged "the top row has nothing above it to float into" — fix is a reserved pad on the
     *  timeline rather than flipping the knob below for row one (chosen because it keeps the
     *  control identical on every row and keeps 95% of lane above clickable; flipping would put
     *  the knob inside the clip on row one, reintroducing the trim/trash contest there).
     *  28dp = 16dp stem + 10dp knob radius + 2dp breathing, so the first row's knob is fully
     *  reachable without clipping. */
    private static final float TOP_GAP_DP = 28f;
    /** Small breathing gap above the first AUDIO-band row (below master). */
    private static final float AUDIO_BAND_TOP_GAP_DP = 3f;
    // ── Fade knobs (SPEC_20260829_FADE_KNOBS §2.1-§2.4) ───────────────────────
    /** Drawn radius ~10dp (20dp disc) — the study's knob. */
    private static final float FADE_KNOB_R_DP = 10f;
    /** Hit radius ≥22dp (44dp target) — decoupled from row height, identical on 34dp and 76dp. */
    private static final float FADE_KNOB_HIT_R_DP = 22f;
    /** How far the knob center sits above the clip's top edge. */
    private static final float FADE_KNOB_TOP_OFFSET_DP = 16f;
    /** Hairline stem width. */
    private static final float FADE_STEM_W_DP = 1.2f;
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
    /** Slack over the measured content so the cap never clips the last row by a pixel. */
    private static final float BAND_CAP_SLACK_DP = 8f;
    /** G6: the current layer-band viewport cap (dp), user-resizable via the preview/timeline grab bar. */
    private float maxVisibleRowsDp = DEFAULT_MAX_VISIBLE_ROWS_DP;

    // ── Colors (frosted dark glass — DESIGN §5) ─────────────────────
    private static final int COLOR_HEADER_BG      = 0x99141420; // semi-transparent dark
    private static final int COLOR_HEADER_BG_LOCK = 0x99201414;
    private static final int COLOR_ROW_BODY_BG    = 0x661A1A24;
    private static final int COLOR_ROW_NAME       = 0xFFEDEDED;
    private static final int COLOR_ICON_ON        = 0xFFFFFFFF;
    private static final int COLOR_ICON_OFF       = 0x66FFFFFF;
    /** Muted lane: dimmed but still clearly PRESENT — it is a state, not a disabled control.
     *  (Nothing draws a disabled mute any more; a lane with no audio has no icon at all.) */
    private static final int COLOR_ICON_MUTED     = 0xB3FF6B6B;
    // Per-item hues moved to ObjectPalette (F-COLOR) — one table for the whole editor.
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
    /** §4 white 1px stroke around every glyph so amber/green read on their tapes. */
    private final Paint kfDiamondStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** C4 §3: opacity-only rubber-band envelope + its dark scrim. */
    private final Paint kfEnvLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kfEnvDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kfScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean kfEnvPaintsInit;

    // FADE_KNOBS §2.1-2.4: knob + veil + dotted edge + duration label — one general control
    private final Paint fadeKnobFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadeKnobStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadeStemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadeVeilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadeEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadeDurationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path fadeVeilPath = new Path();
    /** Dragging fade for duration readout (§2.3). Null = not dragging. */
    @Nullable private String draggingFadeItemId;
    private boolean draggingFadeIsIn;
    private long draggingFadeMs;
    // Retired: B1.V wedge corner effect — replaced by knob+veil. Kept for reference until stable.

    // ── B3/B4 header chrome ─────────────────────────────────────────
    /** Solo ring around the mute glyph — amber, the colour solo reads as in every DAW. */
    private static final int COLOR_SOLO_RING = 0xFFFFC107;
    private static final int COLOR_METER_TRACK = 0x26FFFFFF;
    private static final int COLOR_METER_FILL = 0xFF4CAF50;
    private static final int COLOR_METER_CLIP = 0xFFFF5252;
    /** Width of the per-track level gutter bar (B4). */
    private static final float METER_BAR_W_DP = 3f;
    /** Full-scale for the bar's linear display range; past 1.0 the cap turns clip-red. */
    private static final float METER_FULL_SCALE = 1.0f;
    private final Paint meterTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meterFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint soloRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Bucket tolerance for consolidating property key times into ONE row diamond — matches
     *  the drawer's on-key tolerance so an X and a Y key from the same gesture read as one. */
    static final long KF_CONSOLIDATE_TOLERANCE_MS = 66L;

    // ── B3 solo + B4 meters: session state the HOST drives, the renderer only READS ────
    /**
     * Solo is keyed by TRACK ID, never by {@code Track} reference: rows are rebuilt-from-flat
     * views on every sync (the same reason {@code onTrackHeaderAction} persists through
     * TrackFlags), so a reference-keyed set would silently forget on the next rebuild and the
     * ring would blink off — the G18 trap in miniature. Session-scoped by design: solos do not
     * survive a project reload (standard DAW behaviour).
     */
    private static final java.util.Set<String> SOLOED_TRACK_IDS = new java.util.HashSet<>();
    /**
     * Each carrier lane's muted state as it was when the FIRST solo of the current solo
     * session engaged, so clearing the last solo restores what the user actually had instead
     * of blanket-unmuting. Written by the track-header menu ({@code toggleTrackSolo}), read
     * back when the set empties.
     */
    private static final java.util.Map<String, Boolean> PRE_SOLO_MUTED = new java.util.HashMap<>();

    /**
     * Master-spine clip audio-mute states from before the current solo session.
     *
     * <p>Twin of {@link #PRE_SOLO_MUTED}, kept separate because the master spine's audio is a
     * per-CLIP flag rather than a track mute. Solo silences it too: JoyRaptor soloed an audio lane,
     * still heard the video, and ruled that solo means hear ONLY this.</p>
     */
    private static final java.util.Map<String, Boolean> PRE_SOLO_CLIP_MUTED = new java.util.HashMap<>();

    /** Live map — see {@link #PRE_SOLO_CLIP_MUTED}. Cleared when the last solo clears. */
    public static java.util.Map<String, Boolean> preSoloClipMuted() {
        return PRE_SOLO_CLIP_MUTED;
    }

    /** True while this row's lane is soloed — draws the ring around the mute glyph. */
    public static boolean isSoloed(@NonNull Track t) {
        return SOLOED_TRACK_IDS.contains(t.getId());
    }

    /** Snapshot of the current solo selection (ids). The menu owns all mutation. */
    @NonNull
    public static java.util.Set<String> soloedIdsSnapshot() {
        return new java.util.HashSet<>(SOLOED_TRACK_IDS);
    }

    /** The pre-solo muted map (id → was muted). Menu-owned mutation, renderer-held memory. */
    @NonNull
    public static java.util.Map<String, Boolean> preSoloMuted() {
        return PRE_SOLO_MUTED;
    }

    /** Replace the whole solo selection (menu + its undo steps are the only callers). */
    public static void setSoloedIds(@NonNull java.util.Collection<String> ids) {
        SOLOED_TRACK_IDS.clear();
        SOLOED_TRACK_IDS.addAll(ids);
    }

    /**
     * Whether this lane can be heard RIGHT NOW: not muted, AND not excluded by someone
     * else's solo. Preview and export both already consult {@code Track.isMuted()} (via
     * {@code LayerPreviewController.isAudioClipTrackMuted}), so solo is implemented as
     * DERIVED muting over that existing machinery — no engine change, real silence.
     */
    public static boolean isTrackAudible(@NonNull Track t) {
        if (t.isMuted()) return false;
        return SOLOED_TRACK_IDS.isEmpty() || SOLOED_TRACK_IDS.contains(t.getId());
    }

    /**
     * B4: the host pushes the absolute playhead here once per tick
     * ({@code FaditorEditorActivity.updateCurrentTimeDisplay}) because the playhead lives in
     * the host and {@code EditorTimelineView} owns this renderer privately — a static channel
     * is the only way to feed it without widening that view's surface. Read by the gutter
     * level bars during every draw pass; {@link Long#MIN_VALUE} means "no tick yet", which
     * keeps every bar at its resting baseline.
     */
    private static volatile long hostPlayheadMs = Long.MIN_VALUE;

    public static void reportHostPlayheadMs(long absoluteMs) {
        hostPlayheadMs = absoluteMs;
    }

    /**
     * Program level of ONE lane at the given playhead: the sum of every contributing clip's
     * FINAL audible gain (B1.Q — envelope × volumeLevel) at the moment, gated by clip mute
     * and {@link #isTrackAudible}. This is the intended loudness the mix maths produce, not a
     * post-DSP measurement — no engine tap exists yet (Visualizer-based true metering is the
     * noted upgrade path). Unscaled linear units, 0..~2.
     */
    public static float trackLevelAt(@NonNull Track t, long playheadAbsMs) {
        if (playheadAbsMs == Long.MIN_VALUE || !isTrackAudible(t)) return 0f;
        float sum = 0f;
        for (TimedItem item : t.getItems()) {
            com.fadcam.ui.faditor.model.AudioClip ac = item.getAudioClip();
            if (ac != null) {
                sum += clipContribution(ac, item.getTimelineStartMs(), playheadAbsMs);
                continue;
            }
            com.fadcam.ui.faditor.model.Clip c = item.getClip();
            if (c != null && c.isOverlayClip() && c.isOverlayAudioEnabled()) {
                long start = item.getTimelineStartMs();
                sum += clipContribution(c, start, playheadAbsMs);
            }
        }
        return sum;
    }

    private static float clipContribution(@NonNull com.fadcam.ui.faditor.model.AudioParams p,
                                          long startMs, long playheadAbsMs) {
        if (p.isMuted()) return 0f;
        long local = playheadAbsMs - startMs;
        if (local < 0 || local > p.getTrimmedDurationMs()) return 0f;
        float g = p.gainAtClipMs(local);
        return g > 0f ? g : 0f;
    }

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
     *  pick-up-for-move — see PLAN_LAYER_GESTURE_CONTRACT "DELETE relocates").
     *  FADE_IN/FADE_OUT are SPEC_AUDIO_UX_V1 §4 top-corner triangles for audio fades;
     *  they are inset 16dp from the trim edge so they never overlap, and trim wins. */
    public enum ItemZone { BODY, LEFT_HANDLE, RIGHT_HANDLE, DELETE, FADE_IN, FADE_OUT }

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
    /** Material Symbols ligature paint for the lane mute glyph; null → hand-drawn fallback. */
    @Nullable
    private Paint iconFontPaint;

    /** Rows laid out on the last {@link #layout} call, top-to-bottom, for draw/hit-test. */
    private final List<RowLayout> rows = new ArrayList<>();

    // ── B2: JoyRaptor's cross-fade pill (SPEC_AUDIO_UX_V1 §5) ──────────────────────────────
    /** DRAWN height. The hit box is inflated separately — see §5.2 and hitTestCrossfade. */
    private static final float XFADE_PILL_H_DP = 14f;
    /** How far the shading reaches into a lane's tape on each side of the seam. */
    private static final float XFADE_SHADE_ALPHA = 0.55f;
    private final List<com.fadcam.ui.faditor.model.AudioCrossfade> audioCrossfades =
            new ArrayList<>();
    @Nullable private String selectedCrossfadeId;
    private final Paint xfadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xfadeShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path xfadeArrowPath = new Path();

    /** Replace the cross-fades this renderer draws. The list is copied, not aliased. */
    public void setAudioCrossfades(
            @Nullable List<com.fadcam.ui.faditor.model.AudioCrossfade> xs) {
        audioCrossfades.clear();
        if (xs != null) audioCrossfades.addAll(xs);
    }

    public void setSelectedCrossfadeId(@Nullable String id) { this.selectedCrossfadeId = id; }
    @Nullable public String getSelectedCrossfadeId() { return selectedCrossfadeId; }


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

    /** SPEC_PIP_AUDIO slice D: height of an OPEN lane audio drawer. */
    private static final float LANE_AUDIO_DRAWER_DP = 34f;

    /** Lane ids whose audio drawer is open. Empty = the feature is entirely inert. */
    @NonNull
    private final java.util.Set<String> audioDrawerOpenTrackIds = new java.util.HashSet<>();

    /** Replace the set of lanes showing an audio drawer (see {@link #laneAudioDrawerPx}). */
    public void setAudioDrawerOpenTrackIds(@Nullable java.util.Set<String> ids) {
        audioDrawerOpenTrackIds.clear();
        if (ids != null) audioDrawerOpenTrackIds.addAll(ids);
    }

    /** Source of a PiP clip's shaped quad-band tape, or null while extracting. */
    public interface LaneTapeProvider {
        @Nullable
        com.fadcam.ui.faditor.waveform.BandedTimelineWaveformCache.Shaped get(
                @NonNull com.fadcam.ui.faditor.model.Clip clip);
    }

    @Nullable
    private LaneTapeProvider laneTapeProvider;

    public void setLaneTapeProvider(@Nullable LaneTapeProvider provider) {
        this.laneTapeProvider = provider;
    }

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
        /** SPEC_PIP_AUDIO slice D: the open audio shelf at the row's bottom; EMPTY when closed. */
        final RectF drawerRect = new RectF();

        /**
         * Bottom of the ITEM area — the row's bottom, minus an open audio drawer. Every item
         * extent and hit-test goes through this, so a closed drawer is a no-op by construction.
         */
        float itemsBottom() {
            return drawerRect.isEmpty() ? bodyRect.bottom : drawerRect.top;
        }
        final RectF caretRect = new RectF();
        final RectF muteRect = new RectF();
        /** Touch box for {@link #muteRect} — bigger than the glyph, see layoutHeaderIcons. */
        final RectF muteHitRect = new RectF();
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
     *  2x on the way out (sticky hover, spec-correction C5 — no flicker at boundaries).
     *  Layer-polish #5 (user: "shrink the gap target"): was 8dp, which — since the gap is
     *  hit-tested BEFORE the row and rows are only 34dp — made ~half of each row a
     *  create-a-new-lane target, so aiming at a neighbouring lane often made a lane instead.
     *  5dp leaves the centre ~24dp of a row as a solid row-target while the 2x sticky exit
     *  still makes a gap easy to hold once entered. Retunable feel constant. */
    private static final float GAP_HIT_HALF_DP = 5f;
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

    /**
     * @param iconFont the app's Material Symbols ligature font, or null to fall back to the
     *                 hand-drawn glyph primitives. Passed in rather than loaded here so this
     *                 renderer keeps needing no Context (§3b).
     */
    public LayerRowRenderer(float density, @Nullable Typeface iconFont) {
        this.density = density;
        if (iconFont != null) {
            iconFontPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            iconFontPaint.setTypeface(iconFont);
            iconFontPaint.setTextAlign(Paint.Align.CENTER);
        }
        // The opening slot: light purple dashed = "creates a new layer", the meaning already
        // shipped for the gap-drop insertion line. Same colour, same promise, no new vocabulary.
        pendingLaneSlotPaint.setStyle(Paint.Style.STROKE);
        pendingLaneSlotPaint.setColor(0xFFC9A6FF);
        pendingLaneSlotPaint.setStrokeWidth(2f * density);
        pendingLaneSlotPaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{8f * density, 6f * density}, 0f));
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
        // Fade knob + veil paints (SPEC_20260829_FADE_KNOBS §2.1-2.4) — one routine, shared audio+image+caption
        fadeKnobFillPaint.setStyle(Paint.Style.FILL);
        fadeKnobFillPaint.setColor(0xFF1C1C26);
        fadeKnobStrokePaint.setStyle(Paint.Style.STROKE);
        fadeKnobStrokePaint.setStrokeWidth(2f * density);
        fadeStemPaint.setStyle(Paint.Style.STROKE);
        fadeStemPaint.setStrokeWidth(FADE_STEM_W_DP * density);
        fadeVeilPaint.setStyle(Paint.Style.FILL);
        fadeEdgePaint.setStyle(Paint.Style.STROKE);
        fadeEdgePaint.setStrokeWidth(1.2f * density);
        fadeEdgePaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{3f * density, 3f * density}, 0f));
        fadeDurationPaint.setTextSize(9f * density);
        fadeDurationPaint.setTypeface(Typeface.DEFAULT_BOLD);
        fadeDurationPaint.setColor(0xFFFFFFFF);
        fadeDurationPaint.setTextAlign(Paint.Align.CENTER);
        fadeDurationPaint.setShadowLayer(3f * density, 0f, 1f * density, 0xCC000000);
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
        // The opening gap is real height: leaving it out clips the bottom row while the lane
        // animates in, so the rows appear to slide UNDER the master track instead of apart.
        if (pendingLaneGapPx > 0.5f) total += pendingLaneGapPx + rowGap;
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

    /**
     * Height of a normal expanded lane row. The CARRY animation needs it so a clip lifted off the
     * (taller) master track can shrink to lane height WHILE being dragged, instead of snapping to
     * a new shape the instant the finger lifts.
     */
    public float expandedRowHeightPx() { return ROW_HEIGHT_EXPANDED_DP * density; }

    private float rowHeightPx(@NonNull Track t) {
        if (t.isCollapsed()) return ROW_HEIGHT_COLLAPSED_DP * density;
        if (t.getKind() == TrackKind.AUDIO) return ROW_HEIGHT_AUDIO_EXPANDED_DP * density;
        return ROW_HEIGHT_EXPANDED_DP * density + laneAudioDrawerPx(t);
    }

    /**
     * SPEC_PIP_AUDIO slice D: extra height for this lane's open audio drawer — the shelf that
     * shows an opted-in PiP's waveform under its own body, so the picture tape and the audio
     * can be read together (the lane-row sibling of the master clip-audio drawer in
     * {@code EditorTimelineView}).
     *
     * <p>0 unless the lane's drawer is explicitly OPEN, which makes every existing row
     * pixel-identical: the layout, the item extents and the hit-test all key off this one
     * number, so a closed drawer cannot perturb anything.</p>
     */
    private float laneAudioDrawerPx(@NonNull Track t) {
        if (audioDrawerOpenTrackIds.isEmpty()) return 0f;
        if (t.isCollapsed() || t.getKind() == TrackKind.AUDIO) return 0f;
        return audioDrawerOpenTrackIds.contains(t.getId()) ? LANE_AUDIO_DRAWER_DP * density : 0f;
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
        // CARRY new-lane preview: open a real gap in the layout so the rows below SLIDE DOWN and
        // the lane is seen arriving, rather than materialising after the finger lifts. Inserting
        // space into the same loop that positions the rows is what makes the shift free — a
        // separately-animated overlay would drift out of step with the rows it sits between.
        int rowIdx = 0;
        float gapTopY = -1f;
        for (Track t : layers) {
            if (pendingLaneGapIndex == rowIdx && pendingLaneGapPx > 0.5f) {
                gapTopY = y;
                y += pendingLaneGapPx + rowGap;
            }
            y = addRow(t, y, hScrollOffsetPx, widthPx, rowGap, true);
            rowIdx++;
        }
        if (pendingLaneGapIndex >= layers.size() && pendingLaneGapPx > 0.5f) {
            gapTopY = y;
            y += pendingLaneGapPx + rowGap;
        }
        if (gapTopY >= 0f) {
            float l = hScrollOffsetPx + HEADER_WIDTH_DP * density;
            pendingLaneGapRect.set(l, gapTopY, hScrollOffsetPx + widthPx, gapTopY + pendingLaneGapPx);
            // Via a PATH, not drawRoundRect: DashPathEffect is not honoured for rect primitives
            // on a hardware-accelerated canvas, so the dashes silently render solid or not at all.
            // drawPath is the supported route for path effects.
            pendingLaneSlotPath.reset();
            pendingLaneSlotPath.addRoundRect(pendingLaneGapRect, 6f * density, 6f * density,
                    android.graphics.Path.Direction.CW);
            canvas.drawPath(pendingLaneSlotPath, pendingLaneSlotPaint);
            android.util.Log.d("CARRY", "laneGap idx=" + pendingLaneGapIndex
                    + " px=" + pendingLaneGapPx + " y=" + gapTopY);
        } else {
            pendingLaneGapRect.setEmpty();
        }
        int floatingRowCount = rows.size();
        floatingRowCountAtLayout = floatingRowCount;
        // Rows-only content height; the viewport caps at maxVisibleRowsDp and the extra
        // rows SCROLL beneath the pinned master (PLAN §6.1). Slice 2: no zone height is
        // reserved anymore — the gaps themselves are the new-layer targets.
        contentHeightPx = y;
        viewportHeightPx = Math.min(contentHeightPx, effectiveViewportCapPx());
        scrollOffsetPx = clampScroll(scrollOffsetPx);
        canvas.save();
        float knobOverhang = (FADE_KNOB_TOP_OFFSET_DP + FADE_KNOB_R_DP + 4f) * density;
        canvas.clipRect(hScrollOffsetPx, topPx - knobOverhang, hScrollOffsetPx + widthPx, topPx + viewportHeightPx);
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
            float audioKnobOverhang = (FADE_KNOB_TOP_OFFSET_DP + FADE_KNOB_R_DP + 4f) * density;
            canvas.clipRect(hScrollOffsetPx, audioTopPx - audioKnobOverhang,
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
            drawAudioCrossfades(canvas, floatingRowCount, timeToX);
            canvas.restore();
        }
    }

    // ── Drag guides (dragux_v3 A9, widened 2026-08-14): 1px dotted vertical lines at the
    // dragged item's previewed start/end, spanning every row, so you can see what the item
    // lines up with on the OTHER lanes. Dim by design — "they don't have to be very bright"
    // (user).
    //
    // They began life as A9's time-lock confirmation: shown only while a near-vertical
    // cross-row drag held its original timing, and disarmed "the moment the drag goes
    // diagonal", which is what this comment used to say with some pride. That turned out to be
    // exactly backwards — the diagonal drag is when you can least tell where the item will
    // land. JoyRaptor, 2026-08-13: "if I start going down and then going over it doesn't show ...
    // I'm pretty much guessing where the exact start mark is." The controller now arms them
    // from applyMoveTo, so they are on for the whole move and always mark the previewed start.
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
        // SPEC_PIP_AUDIO slice D: carve the drawer off the BOTTOM of the row. Stays EMPTY
        // for every closed row, and row.itemsBottom() then returns bodyRect.bottom exactly
        // as before — so items keep their height instead of stretching into the shelf.
        float drawerPx = laneAudioDrawerPx(t);
        if (drawerPx > 0f) {
            row.drawerRect.set(row.bodyRect.left, row.bodyRect.bottom - drawerPx,
                    row.bodyRect.right, row.bodyRect.bottom);
        } else {
            row.drawerRect.setEmpty();
        }
        layoutHeaderIcons(row, h - drawerPx);
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
        // §3b (2026-07-28): mute sits FLUSH AGAINST THE CARET, not right-aligned in a wide
        // gutter. The header used to be 92dp because it once held a name, a kind badge and
        // eye/lock; all of those retired, leaving mute stranded ~62dp from the caret with
        // nothing in between — a centimetre of every lane spent on empty space. The header is
        // now sized to exactly caret + gap + mute + gap, and the ~58dp that frees up goes back
        // to the lane body where the objects actually live.
        row.muteRect.set(row.caretRect.right + gap, cy - iconSize / 2f,
                row.caretRect.right + gap + iconSize, cy + iconSize / 2f);
        // A 12dp glyph is a 12dp target, which is far below anything a finger can hit
        // reliably. The DRAWN box stays 12dp; the TOUCH box is the full row height and runs
        // from just left of the glyph to the header's edge. The caret is hit-tested first, so
        // the small overlap on the left cannot steal a caret tap.
        //
        // B4: the level meter's 3dp strip lives at the header's RIGHT edge — INSIDE what this
        // touch box used to cover, so a tap aimed at the meter muted the lane. The instrument
        // is read-only; its strip is reserved out of the mute target.
        float meterReserve = rowCarriesAudio(row.track) ? (METER_BAR_W_DP + 2f) * density : 0f;
        row.muteHitRect.set(row.muteRect.left - gap / 2f, row.headerRect.top,
                row.headerRect.right - meterReserve, row.headerRect.top + rowH);
    }

    private void drawRow(@NonNull Canvas canvas, @NonNull RowLayout row,
                          long totalMs, @NonNull TimeToX timeToX, @Nullable String selectedItemId) {
        Track t = row.track;
        boolean collapsed = t.isCollapsed();

        headerBgPaint.setColor(t.isLocked() ? COLOR_HEADER_BG_LOCK : COLOR_HEADER_BG);
        canvas.drawRect(row.headerRect, headerBgPaint);
        rowBodyBgPaint.setColor(COLOR_ROW_BODY_BG);
        canvas.drawRect(row.bodyRect, rowBodyBgPaint);

        // B4: the 3dp level gutter bar. It sits on the header's RIGHT edge, flush against
        // the lane body — the left edge is the caret's home and a bar there would read as
        // caret chrome. ALWAYS drawn: a dim baseline track at zero is the resting state
        // (the G18 lesson — a meter that renders nothing until audio plays reads as broken,
        // not as idle), so the lane's loudness control is visible before any clip exists.
        if (rowCarriesAudio(t)) {
            float barW = METER_BAR_W_DP * density;
            float trackTop = row.headerRect.top + 2f * density;
            float trackBottom = row.headerRect.bottom - 2f * density;
            meterTrackPaint.setColor(COLOR_METER_TRACK);
            canvas.drawRect(row.headerRect.right - barW, trackTop,
                    row.headerRect.right, trackBottom, meterTrackPaint);
            float level = trackLevelAt(t, hostPlayheadMs);
            float frac = Math.max(0f, Math.min(1.2f, level)) / METER_FULL_SCALE;
            if (frac > 0f) {
                float fillTop = Math.max(trackTop, trackBottom - (trackBottom - trackTop) * frac);
                boolean clipping = level > METER_FULL_SCALE;
                meterFillPaint.setColor(clipping ? COLOR_METER_CLIP : COLOR_METER_FILL);
                canvas.drawRect(row.headerRect.right - barW, fillTop,
                        row.headerRect.right, trackBottom, meterFillPaint);
            }
        }

        // Caret (collapse toggle) — right-pointing when collapsed, down when expanded.
        drawCaret(canvas, row.caretRect, collapsed);

        // JoyRaptor 2026-07-19: layers are NEUTRAL SUBSTRATE — the kind badge moved off
        // the row gutter onto each OBJECT (drawItemBody). The header keeps only the
        // caret + mute; no name, no kind identity.

        // §4.5: per-layer eye/lock glyphs RETIRED (drawEyeIcon/drawLockIcon calls gone) —
        // hidden/locked live on objects, toggled in the drawer, ghosted on item bodies.
        // §3b: ABSENT, not greyed out. A lane with no audio has nothing to mute, so the
        // control simply is not there — "that way things aren't cluttered when you don't
        // actually have need for it" (user, 2026-07-28). The greyed-out version drew an
        // affordance on every text/sticker/image lane in the project that could never do
        // anything. hitTestHeader already refuses the tap on these rows; now the icon agrees.
        if (rowCarriesAudio(t)) {
            drawMuteIcon(canvas, row.muteRect, !t.isMuted());
            // B3: the solo ring — drawn around the mute glyph so one glance answers "why is
            // everything else silent?" Amber, distinct from the muted-red glyph and the
            // white unmuted one; a state ring, not a second control.
            if (isSoloed(t)) {
                float inf = 2f * density;
                soloRingPaint.setStyle(Paint.Style.STROKE);
                soloRingPaint.setStrokeWidth(1.5f * density);
                soloRingPaint.setColor(COLOR_SOLO_RING);
                canvas.drawRoundRect(row.muteRect.left - inf, row.muteRect.top - inf,
                        row.muteRect.right + inf, row.muteRect.bottom + inf,
                        (row.muteRect.height() + 2f * inf) / 2f,
                        (row.muteRect.height() + 2f * inf) / 2f, soloRingPaint);
            }
        }

        if (collapsed) {
            drawCollapsedStrip(canvas, row, totalMs, timeToX);
            // SPLIT-ELEMENT FIX: even when the hovered target row is COLLAPSED, the ONE
            // proxy body must render on it (never vanish) — draw it over the thin strip
            // so the coherent object is always visible under the finger.
            if (proxyItem != null && proxyRowTrackId != null && proxyRowTrackId.equals(t.getId())
                    && liftedItemId != null && liftedItemId.equals(proxyItem.getId())
                    && !isItemOnRow(proxyItem, t)) {
                float top = row.bodyRect.top + 3f * density;
                float bottom = row.itemsBottom() - 3f * density;
                // F-COLOR: the dragged proxy keeps the OBJECT's colour as it crosses lanes —
                // otherwise it changed hue mid-drag to match whatever row it was hovering.
                drawItemBody(canvas, proxyItem, t.getKind(), baseColorForItem(proxyItem, t.getKind()),
                        t.isHidden(), true, top, bottom, (top + bottom) / 2f,
                        totalMs, timeToX, selectedItemId);
            }
        } else {
            drawExpandedItems(canvas, row, t, totalMs, timeToX, selectedItemId);
            drawLaneAudioDrawer(canvas, row, t, totalMs, timeToX);
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
    /** FADE_KNOBS §2.3: dragging state for duration readout. */
    public void setDraggingFade(@Nullable String itemId, boolean isIn, long ms) {
        this.draggingFadeItemId = itemId;
        this.draggingFadeIsIn = isIn;
        this.draggingFadeMs = ms;
    }
    public void clearDraggingFade() { this.draggingFadeItemId = null; }

    /** Row (track id) the single drag proxy body is drawn on this frame, or null (= home row). */
    @Nullable private String proxyRowTrackId;
    /** The lifted item itself (its model X is resolved live), so the proxy can be drawn on
     *  a row it is not (yet) a member of — model membership only changes at DROP. */
    @Nullable private TimedItem proxyItem;

    /**
     * The item currently riding the CARRY card. While set, no row draws its body — the card is
     * the single representation. Null clears it.
     */
    public void setCarriedItemId(@Nullable String id) { this.carriedItemId = id; }

    /**
     * Open an animated gap before row {@code index} (or at the end when index >= row count) to
     * preview the lane a carried clip will land in. {@code px} is the current animated height;
     * 0 closes it.
     */
    public void setPendingLaneGap(int index, float px) {
        this.pendingLaneGapIndex = index;
        this.pendingLaneGapPx = Math.max(0f, px);
    }

    /** True while a lane-opening preview is on screen. */
    public boolean hasPendingLaneGap() { return pendingLaneGapPx > 0.5f; }

    private int pendingLaneGapIndex = -1;
    private float pendingLaneGapPx = 0f;
    private final RectF pendingLaneGapRect = new RectF();
    private final Paint pendingLaneSlotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path pendingLaneSlotPath = new android.graphics.Path();

    @Nullable private String carriedItemId;

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
     * (PiP) clip that has OPTED IN to contributing audio (SPEC_PIP_AUDIO — a PiP is silent
     * by default, so muting a lane that holds only silent PiPs would do nothing, and the
     * icon must not promise otherwise).</p>
     */
    public static boolean rowCarriesAudio(@NonNull Track t) {
        if (t.getKind() == TrackKind.AUDIO || t.getKind() == TrackKind.MASTER) return true;
        for (TimedItem item : t.getItems()) {
            if (item.getAudioClip() != null) return true;
            com.fadcam.ui.faditor.model.Clip clip = item.getClip();
            if (clip != null && clip.isOverlayClip() && clip.isOverlayAudioEnabled()) return true;
        }
        return false;
    }

    /**
     * §3b: a REAL speaker — the Material Symbols {@code volume_up} glyph when the lane is
     * audible, {@code volume_off} (speaker with a cross through it) when it is muted. The app
     * already speaks this vocabulary elsewhere ({@code VolumeControlBottomSheet}), so the lane
     * row now matches it instead of approximating a speaker out of a rectangle and a trapezoid,
     * which read as a flag at 12dp and gave no hint that it was a toggle.
     *
     * <p>The hand-drawn primitives remain as the fallback for a missing/unloadable icon font:
     * a glyph that silently fails to render would leave a lane with audio looking exactly like
     * a lane without it, which is the one state this control must never be confused with.</p>
     */
    private void drawMuteIcon(@NonNull Canvas canvas, @NonNull RectF r, boolean unmuted) {
        if (iconFontPaint != null) {
            iconFontPaint.setColor(unmuted ? COLOR_ICON_ON : COLOR_ICON_MUTED);
            iconFontPaint.setTextSize(r.height() * 1.15f);
            Paint.FontMetrics fm = iconFontPaint.getFontMetrics();
            float baseline = r.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(unmuted ? "volume_up" : "volume_off", r.centerX(), baseline,
                    iconFontPaint);
            return;
        }
        iconPaint.setColor(unmuted ? COLOR_ICON_ON : COLOR_ICON_MUTED);
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
        if (!unmuted) {
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
        boolean ghosted = t.isHidden();
        float top = row.bodyRect.top + 3f * density;
        float bottom = row.itemsBottom() - 3f * density;
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
            // CARRY: the object is riding a floating card drawn by EditorTimelineView, free of
            // the row grid. Drawing it here too put TWO bodies on screen — the card under the
            // finger AND the item still sitting in its lane — which is precisely the "will it
            // stay in its original lane?" ambiguity the lift exists to remove. The passive home
            // ghost (drawn above) still marks where it came from.
            if (carriedItemId != null && carriedItemId.equals(item.getId())) continue;
            if (lifted && proxyRowTrackId != null && !proxyRowTrackId.equals(t.getId())) {
                // The moving object left this row; leave only the passive home-ghost gap
                // marker (drawn above) — do NOT draw a second copy of the item here.
                continue;
            }
            // F-COLOR: per-ITEM colour. This used to hoist baseColorFor(t.getKind()) out of the
            // loop, so every object on a row wore its LANE's colour — the neutral substrate made
            // that wrong (a text object on a VIDEO/LAYER lane read blue). The badge and the
            // playhead tint already resolved by payload; the body now agrees with them.
            drawItemBody(canvas, item, t.getKind(), baseColorForItem(item, t.getKind()),
                    ghosted || isObjectHidden(item), lifted,
                    top, bottom, (top + bottom) / 2f, totalMs, timeToX, selectedItemId);
        }
        // SPLIT-ELEMENT FIX (single proxy): if THIS row is the hovered cross-row target
        // and the lifted proxy item's home row is a DIFFERENT track, draw the ONE proxy
        // body here — at its already-resolved model X (WYSIWYG, fed by the same drop
        // resolver every move) — so the coherent moving object lives under the finger on
        // exactly this row and nowhere else.
        if (proxyItem != null && proxyRowTrackId != null && proxyRowTrackId.equals(t.getId())
                && liftedItemId != null && liftedItemId.equals(proxyItem.getId())
                && !isItemOnRow(proxyItem, t)) {
            drawItemBody(canvas, proxyItem, t.getKind(), baseColorForItem(proxyItem, t.getKind()),
                    ghosted, true, top, bottom, (top + bottom) / 2f, totalMs, timeToX,
                    selectedItemId);
        }
    }

    /**
     * SPEC_PIP_AUDIO slice D: draw the lane's open audio shelf — each opted-in PiP on this row
     * gets its waveform under its own body, aligned to the SAME x span, so picture and audio
     * read together. No-op unless the drawer is open (empty {@code drawerRect}), so a closed
     * row is untouched. A PiP whose tape is still extracting simply draws the empty shelf —
     * the master drawer behaves the same way.
     */
    private void drawLaneAudioDrawer(@NonNull Canvas canvas, @NonNull RowLayout row,
                                      @NonNull Track t, long totalMs, @NonNull TimeToX timeToX) {
        if (row.drawerRect.isEmpty()) return;
        float top = row.drawerRect.top + 2f * density;
        float bottom = row.drawerRect.bottom - 2f * density;
        if (bottom <= top) return;
        int prevBody = itemPaint.getColor();
        itemPaint.setColor(0xFF0A0D11);
        canvas.drawRoundRect(row.drawerRect, 3f * density, 3f * density, itemPaint);
        itemPaint.setColor(prevBody);
        if (laneTapeProvider == null || tapeStyle == null || tapeRenderer == null) return;
        for (TimedItem item : t.getItems()) {
            com.fadcam.ui.faditor.model.Clip clip = item.getClip();
            if (clip == null || !clip.isOverlayClip() || !clip.isOverlayAudioEnabled()) continue;
            com.fadcam.ui.faditor.waveform.BandedTimelineWaveformCache.Shaped tape =
                    laneTapeProvider.get(clip);
            if (tape == null) continue; // still extracting
            float x0 = timeToX.map(item.getTimelineStartMs());
            float x1 = Math.max(x0 + 2f * density,
                    timeToX.map(item.getTimelineStartMs() + item.getDisplayDurationMs(totalMs)));
            tapeRect.set(x0, top, x1, bottom);
            canvas.save();
            canvas.clipRect(x0, top, x1, bottom);
            tapeRenderer.draw(canvas, tapeRect, tape.raw, tape.tape, tapeStyle,
                    clip.getInPointMs(), Math.max(1, clip.getTrimmedDurationMs()));
            canvas.restore();
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
        // Drawn for EVERY audio clip, not only keyframed ones. Gating this on
        // hasVolumeKeyframes() meant moving the volume slider produced no visible change
        // whatsoever — the clip just got quieter with nothing on screen to say so, and there
        // was no rubber-band to grab in order to MAKE the first keyframe. The flat line at the
        // clip's level is both the readout and the affordance.
        if (item.getAudioClip() != null && !ghosted) {
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
            com.fadcam.ui.faditor.model.Clip.CaptionBinding binding = item.getCaptionSpan().getBinding();
            boolean isFirstBinding = binding == null || item.getCaptionSpan().getBindingIndex() == 0;
            String bindingStyleId = binding != null ? binding.styleId : clip.getCaptionStyleId();
            if (isFirstBinding && clip.hasCaptionStyleKeyframes()) {
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
                // UNKEYFRAMED or non-first binding: solid colour from the binding's style.
                int c = com.fadcam.ui.faditor.transcript.CaptionStyle.byId(bindingStyleId).activeColor;
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
            // JoyRaptor 2026-08-05: the label used to start at x0 + pad, which sat directly ON
            // TOP of the kind badge drawn just below (T-in-a-box under "Text", the bars
            // under "VIZ", …). When the badge is drawn, the label's LEFT bound clears it
            // instead; the pinned-scroll bound is untouched, because once the item start
            // scrolls off-screen the badge has gone with it and only the label is left.
            float leftBound = x0 + (kindBadgeVisible(x0, x1) ? KIND_BADGE_LABEL_INSET_DP * density : pad);
            float labelW = itemLabelPaint.measureText(label);
            float viewLeft = lastHScrollOffsetPx + HEADER_WIDTH_DP * density;
            float labelX = Math.max(leftBound, Math.min(viewLeft + pad, x1 - labelW - pad));
            canvas.drawText(label, labelX,
                    centerY + itemLabelPaint.getTextSize() / 3f, itemLabelPaint);
            canvas.restore();
        }
        // JoyRaptor 2026-07-19 (neutral substrate): the KIND badge belongs to the OBJECT,
        // not the lane — small glyph at the item's left inside edge, payload-derived
        // so it stays correct when any-object-on-any-lane lands. Skipped on slivers.
        if (kindBadgeVisible(x0, x1)) {
            drawKindBadge(canvas, x0 + KIND_BADGE_INSET_DP * density, (top + bottom) / 2f,
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
        // FADE_KNOBS §2.2: dark veil always when fade>0 (readout at glance), knob only when selected (§2.1)
        drawFadeVeils(canvas, item, x0, top, x1, bottom, baseColor, totalMs, timeToX);
        if (lifted) {
            // S3: while lifted, the drag-state outline is the ONLY outline — the
            // generic near-white selection stroke is suppressed (it was the WHITE
            // the user reported where purple was expected).
            drawDragStateOutline(canvas, x0, top, x1, bottom, baseColor);
        } else if (selectedItemId != null && selectedItemId.equals(item.getId())) {
            // Captions are clip-owned (no delete semantics) — no trash badge.
            drawItemSelection(canvas, x0, top, x1, bottom, baseColor,
                    item.getCaptionSpan() == null);
            drawSequenceResizeModeHandles(canvas, item, x0, top, x1, bottom);
            if (hasFadeHost(item)) drawFadeKnobs(canvas, item, x0, top, x1, bottom, baseColor, totalMs, timeToX);
        }
        if (item.getAudioClip() != null && item.getAudioClip().hasRemovedSpans()) {
            drawAudioStruckTape(canvas, item, x0, top, x1, bottom, timeToX);
        }
        if (trimmingItemId != null && trimmingItemId.equals(item.getId())) {
            drawTrimStripes(canvas, x0, top, x1, bottom);
        }
        // Right-to-left from the trash roundel: pass-through first, then FX. Counted rather
        // than positioned by literal, so the FX mark does not sit on top of the finger badge
        // on an object that has both.
        int slot = 0;
        if (drawPassThroughBadge(canvas, item, x0, top, x1, bottom, slot)) slot++;
        drawFxBadge(canvas, item, x0, top, x1, bottom, slot);
    }

    /**
     * A small <b>fx</b> roundel on any object carrying its own live effect stack.
     *
     * <p>Effects were completely invisible from the timeline: the only way to know an object had
     * any was to select it and open the drawer. On a project with a dozen objects that turns
     * "which one is doing that?" into a search. Drawn whenever the stack RENDERS something, so a
     * fully bypassed stack does not claim credit for a frame it is not changing.</p>
     */
    private void drawFxBadge(@NonNull Canvas canvas, @NonNull TimedItem item,
                             float x0, float top, float x1, float bottom, int slot) {
        boolean has;
        if (item.getClip() != null) has = item.getClip().hasActiveFx();
        else if (item.getTextOverlay() != null) has = item.getTextOverlay().hasActiveFx();
        else if (item.getAdjustment() != null) has = item.getAdjustment().rendersAnything();
        else has = false;
        if (!has) return;

        float r = DELETE_BADGE_RADIUS_DP * density;
        float cx = badgeSlotCx(x0, x1, slot, r);
        if (Float.isNaN(cx) || cx - r < x0) return;
        float cy = (top + bottom) / 2f;

        int prevColor = itemSelectionPaint.getColor();
        Paint.Style prevStyle = itemSelectionPaint.getStyle();
        itemSelectionPaint.setStyle(Paint.Style.FILL);
        itemSelectionPaint.setColor(0xDD1C1C22);
        canvas.drawCircle(cx, cy, r, itemSelectionPaint);
        if (fxBadgePaint == null) {
            fxBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            fxBadgePaint.setColor(0xFFB388FF);   // the app's purple accent, as the FX tool uses
            fxBadgePaint.setTextAlign(Paint.Align.CENTER);
            fxBadgePaint.setTypeface(android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        }
        fxBadgePaint.setTextSize(r * 1.15f);
        // Baseline nudge rather than centre-y: text sits on its baseline, so drawing at cy
        // would hang the glyphs below the middle of the roundel.
        canvas.drawText("fx", cx, cy + r * 0.42f, fxBadgePaint);
        itemSelectionPaint.setColor(prevColor);
        itemSelectionPaint.setStyle(prevStyle);
    }

    @Nullable private Paint fxBadgePaint;

    /** Centre-x of the {@code slot}-th badge counting left from the trash roundel. */
    private float badgeSlotCx(float x0, float x1, int slot, float r) {
        float trashCx = deleteBadgeCx(x0, x1);
        float first = Float.isNaN(trashCx) ? x1 - r - 2f * density : trashCx - (2.4f * r);
        return first - slot * (2.4f * r);
    }

    /**
     * The finger-with-a-slash badge on an object whose taps pass through in the preview.
     *
     * <p>Drawn WHENEVER the flag is on, not only when the item is selected. The point of the
     * badge is to answer "why can't I grab that thing on the canvas" at a glance, and a state
     * you can only see after selecting the object is no use for a question you ask about an
     * object you cannot select.</p>
     *
     * <p>Sits just left of where the trash roundel goes, so the two never overlap on a selected
     * row, and pins to the viewport edge the same way the trash does.</p>
     */
    private boolean drawPassThroughBadge(@NonNull Canvas canvas, @NonNull TimedItem item,
                                         float x0, float top, float x1, float bottom, int slot) {
        com.fadcam.ui.faditor.model.Clip c = item.getClip();
        if (c == null || !c.isPassThrough()) return false;
        float r = DELETE_BADGE_RADIUS_DP * density;
        float cx = badgeSlotCx(x0, x1, slot, r);
        if (cx - r < x0) return false;                 // no room without covering the body
        float cy = (top + bottom) / 2f;

        int prevColor = itemSelectionPaint.getColor();
        Paint.Style prevStyle = itemSelectionPaint.getStyle();
        float prevW = itemSelectionPaint.getStrokeWidth();
        itemSelectionPaint.setStyle(Paint.Style.FILL);
        itemSelectionPaint.setColor(0xDD1C1C22);
        canvas.drawCircle(cx, cy, r, itemSelectionPaint);
        itemSelectionPaint.setStyle(Paint.Style.STROKE);
        itemSelectionPaint.setStrokeWidth(1.2f * density);
        itemSelectionPaint.setColor(0xFFFFFFFF);
        // A finger pressing a surface: a stem, and the line it stops against.
        canvas.drawLine(cx, cy - 0.55f * r, cx, cy + 0.15f * r, itemSelectionPaint);
        canvas.drawLine(cx - 0.5f * r, cy + 0.45f * r, cx + 0.5f * r, cy + 0.45f * r,
                itemSelectionPaint);
        // ...and the slash that says it is not catching anything.
        canvas.drawLine(cx - 0.6f * r, cy - 0.6f * r, cx + 0.6f * r, cy + 0.6f * r,
                itemSelectionPaint);
        itemSelectionPaint.setStrokeWidth(prevW);
        itemSelectionPaint.setColor(prevColor);
        itemSelectionPaint.setStyle(prevStyle);
        return true;
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
        // NOTE: an empty keyframe list is NOT an early return any more — see the flat-line
        // branch below, which is the whole point of drawing this for unautomated clips.
        long dur = Math.max(1, ac.getTrimmedDurationMs());
        float w = x1 - x0;
        float h = bottom - top;
        canvas.save();
        canvas.clipRect(x0, top, x1, bottom);
        if (kfs.isEmpty()) {
            // No automation yet: one flat line at the clip's own level. This is what makes a
            // volume change VISIBLE, and it is the line the user grabs to place a first
            // keyframe. Same 0..2 vertical scale as the keyframed path below, so the line does
            // not jump the moment a keyframe is added.
            float gFrac = Math.max(0f, Math.min(1f, ac.getVolumeLevel() / 2.0f));
            float y = bottom - gFrac * h;
            canvas.drawLine(x0, y, x1, y, volEnvLinePaint);
            canvas.restore();
            return;
        }
        float prevX = 0f, prevY = 0f;
        for (int k = 0; k < kfs.size(); k++) {
            com.fadcam.ui.faditor.model.AudioClip.VolumeKeyframe kf = kfs.get(k);
            float fx = Math.max(0f, Math.min(1f, kf.timeMs / (float) dur));
            float x = x0 + fx * w;
            // B1.Q: stored values are MULTIPLIERS over volumeLevel — draw the FINAL gain
            // so a boosted clip's envelope still rides high on the tape.
            float finalGain = kf.volume * ac.getVolumeLevel();
            float gFrac = Math.max(0f, Math.min(1f, finalGain / 2.0f));
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
        // PiP (overlay clip). Read from the CLIP, not from TimedItem#getTransform(): the item is
        // a snapshot taken when the rows were last synced, and the first keyframe a user drops
        // CREATES the KeyframeSet — so a snapshot taken before that still holds null and the
        // key would not appear until something unrelated rebuilt the rows.
        if (item.getClip() != null && item.getClip().isOverlayClip()) {
            return item.getClip().getOverlayTransform();
        }
        return null;
    }

    /**
     * Timeline ms of a raw key time from {@link #keyframeSetOf}.
     *
     * <p><b>Two time bases meet here.</b> Text and sprite keys are item-LOCAL, while a PiP's
     * {@code overlayTransform} keys are ABSOLUTE timeline ms — that is the base every reader of
     * it uses ({@code OverlayVideoPreviewView}, {@code PipFrameOverlay}, the menu adapters), so
     * this converts rather than trying to change it. Getting it wrong does not crash: it draws
     * the diamonds at double the offset, which looks like a plausible-but-wrong keyframe time
     * and is the kind of error that survives a screenshot.</p>
     */
    static long keyTimeToTimelineMs(@NonNull TimedItem item, long rawKeyMs) {
        return absoluteKeyTimeBase(item) ? rawKeyMs : item.getTimelineStartMs() + rawKeyMs;
    }

    /** True when this item's key times are ABSOLUTE timeline ms (PiP) rather than item-local. */
    private static boolean absoluteKeyTimeBase(@NonNull TimedItem item) {
        return item.getClip() != null && item.getClip().isOverlayClip();
    }

    /**
     * The earliest key time this item may legally hold, IN ITS OWN BASE — 0 for an item-local
     * track, the item's timeline start for a PiP's absolute one. Used as the floor when the
     * first key of a track is dragged, so a PiP key cannot be shoved back before the clip it
     * belongs to exists.
     */
    public static long earliestLegalKeyTimeMs(@NonNull TimedItem item) {
        return absoluteKeyTimeBase(item) ? item.getTimelineStartMs() : 0L;
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

    /** Consolidated bucket with representative easing (earliest key in bucket) + preset-owned flag. */
    static final class ConsolidatedKey {
        final long timeMs;
        final Easing easing;
        final boolean presetOwned;
        ConsolidatedKey(long t, Easing e, boolean presetOwned) { timeMs = t; easing = e; this.presetOwned = presetOwned; }
        ConsolidatedKey(long t, Easing e) { this(t, e, false); }
    }

    /** Buckets with easing — single-source family for timeline diamonds (§3 contract). Preset-owned bucket if ANY key in bucket is owned. */
    @NonNull
    static List<ConsolidatedKey> consolidatedKeysWithEasing(@NonNull TimedItem item) {
        KeyframeSet set = keyframeSetOf(item);
        if (set == null) return Collections.emptyList();
        // Collect (time, easing, presetOwned) triples — preset true if any track has owned at that time
        List<ConsolidatedKey> all = new ArrayList<>();
        for (KeyframeTrack t : set.tracks()) {
            for (Keyframe k : t.keyframes) all.add(new ConsolidatedKey(k.timeMs, k.easing, k.presetOwned));
        }
        if (all.isEmpty()) return Collections.emptyList();
        all.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        // Merge buckets: presetOwned ORs within tolerance (so amber survives consolidation)
        List<ConsolidatedKey> buckets = new ArrayList<>();
        ConsolidatedKey cur = all.get(0);
        buckets.add(cur);
        for (int i = 1; i < all.size(); i++) {
            ConsolidatedKey ck = all.get(i);
            if (ck.timeMs - cur.timeMs > KF_CONSOLIDATE_TOLERANCE_MS) {
                cur = ck;
                buckets.add(cur);
            } else if (ck.presetOwned && !cur.presetOwned) {
                // Upgrade bucket to amber if any member is preset-owned
                int last = buckets.size() - 1;
                buckets.set(last, new ConsolidatedKey(cur.timeMs, cur.easing, true));
                cur = buckets.get(last);
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
        float best = 12f * density;
        Long hit = null;
        for (long b : buckets) {
            float dx = Math.abs(timeToX.map(keyTimeToTimelineMs(item, b)) - x);
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
        List<ConsolidatedKey> buckets = consolidatedKeysWithEasing(item);
        if (buckets.isEmpty()) return;
        float cy = Math.min(bottom - 4f * density, centerY + 5f * density);
        float r = 3.5f * density;
        // §4 — every glyph gets a 1px white stroke so amber/green read on coloured tapes, and interior curve is hidden on timeline (DETAIL_MIN 20dp) to avoid reading as glitch.
        kfDiamondPaint.setStyle(Paint.Style.FILL);
        kfDiamondStrokePaint.setStyle(Paint.Style.STROKE);
        kfDiamondStrokePaint.setColor(0xFFFFFFFF);
        kfDiamondStrokePaint.setStrokeWidth(1f * density);
        kfDiamondStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        for (ConsolidatedKey b : buckets) {
            float dx = timeToX.map(keyTimeToTimelineMs(item, b.timeMs));
            if (dx < x0 + 3f || dx > x1 - 3f) continue;
            if (b.presetOwned && !ghosted) kfDiamondPaint.setColor(0xFFFFC107);
            else kfDiamondPaint.setColor(ghosted ? 0x664CAF50 : 0xE64CAF50);
            spriteDiamondPath.rewind();
            KeyframeGlyph.silhouetteFor(b.easing, dx, cy, r, spriteDiamondPath);
            // §4 white 1px stroke behind fill — independent of fill colour so amber on amber and green on green both read
            canvas.drawPath(spriteDiamondPath, kfDiamondStrokePaint);
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
        // Amber if any opacity key is preset-owned (spec §3.8 colour-only, shape unchanged)
        boolean anyPreset = false;
        for (Keyframe kk : op.keyframes) if (kk.presetOwned) { anyPreset = true; break; }
        if (anyPreset) {
            kfEnvLinePaint.setColor(0xFFFFC107);
            kfEnvDotPaint.setColor(0xFFFFC107);
        } else {
            kfEnvLinePaint.setColor(0xCCFFFFFF);
            kfEnvDotPaint.setColor(0xCCFFFFFF);
        }
        float h = bottom - top;
        canvas.save();
        canvas.clipRect(x0, top, x1, bottom);
        kfScrimPaint.setColor(0x59000000);
        canvas.drawRoundRect(x0, top, x1, bottom, 3f * density, 3f * density, kfScrimPaint);
        List<Keyframe> ks = op.keyframes;
        float prevX = 0f, prevY = 0f;
        for (int k = 0; k < ks.size(); k++) {
            Keyframe kf = ks.get(k);
            float x = timeToX.map(keyTimeToTimelineMs(item, kf.timeMs));
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
            // Dots amber if that specific key is preset-owned
            if (kf.presetOwned) kfEnvDotPaint.setColor(0xFFFFC107);
            else kfEnvDotPaint.setColor(anyPreset ? 0xFFFFC107 : 0xCCFFFFFF);
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
        /**
         * The sheet DEFINITION for {@code sheetId}, or null.
         *
         * <p>Needed because an image sequence's tape (SPEC_IMAGE_SEQUENCE §4) draws a mark at
         * every frame CHANGE, and those times are derived from the sheet's weights and cadence —
         * not from the frame-track keys, of which a sequence has exactly one.</p>
         */
        @Nullable com.fadcam.ui.faditor.sprite.SpriteSheet sheet(@NonNull String sheetId);
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

        com.fadcam.ui.faditor.sprite.SpriteSheet sheet =
                spriteCellProvider.sheet(sprite.getSheetId());
        if (sheet != null && sheet.isSequence()) {
            drawSequenceTape(canvas, sprite, sheet, sr, x0, x1, top, bottom, w,
                    viewLeft, viewRight, timeToX);
            canvas.restore();
            return;
        }

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

    /** Repeat-pass thumbnails are dimmed rather than recoloured — §4's "reads as a repeat". */
    private final android.graphics.Paint repeatPaint = new android.graphics.Paint(
            android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);

    /** Hairline at each frame change, so a change is visible even where a thumb is too narrow. */
    private final android.graphics.Paint seqTickPaint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);

    /**
     * SPEC_IMAGE_SEQUENCE §4 — the image-sequence tape.
     *
     * <p><b>A thumbnail is drawn at each frame CHANGE, and the thumbnails are a UNIFORM size.</b>
     * Their POSITION carries the timing, not their width — the user's own correction of an
     * earlier draft. A long hold is simply a long gap before the next thumbnail, with the lane's
     * bar colour showing through, so uneven timing is legible AND honest at once. Scaling
     * thumbnails to duration would have made short frames unreadable to buy information that
     * position already carries for free.</p>
     *
     * <p>Change times come from {@link com.fadcam.ui.faditor.sprite.SpriteFrameResolver}, the same
     * function that decides what the video shows, so the tape cannot lie about the timing.</p>
     */
    private void drawSequenceTape(@NonNull Canvas canvas,
                                  @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem sprite,
                                  @NonNull com.fadcam.ui.faditor.sprite.SpriteSheet sheet,
                                  @NonNull com.fadcam.ui.faditor.sprite.SpriteSheetRenderer sr,
                                  float x0, float x1, float top, float bottom, float w,
                                  float viewLeft, float viewRight, @NonNull TimeToX timeToX) {
        long start = sprite.getStartMs();
        // Ask only for the VISIBLE window. A looping sequence has unboundedly many changes; the
        // tape needs the ones on screen, and the cap below is the backstop for a pathological
        // zoom where even that is thousands.
        long fromLocal = Math.max(0, msAtX(Math.max(x0, viewLeft), timeToX, start) - 1);
        long toLocal = msAtX(Math.min(x1, viewRight), timeToX, start) + 1;
        java.util.List<com.fadcam.ui.faditor.sprite.SpriteFrameResolver.FrameChange> changes =
                com.fadcam.ui.faditor.sprite.SpriteFrameResolver.frameChangesIn(
                        sheet, sprite, fromLocal, toLocal, MAX_SEQUENCE_MARKS);
        if (changes.isEmpty()) return;

        // §6: the "continues →" affordance, and the visible admission when a neighbour cut it
        // short. The spec's requirement is precise — keep the marker, resolve the length, and
        // "show visibly when a neighbour clipped it. Never silently."
        if (sprite.isContinuesUntilBlocked()) {
            seqTickPaint.setColor(sprite.isClippedByNeighbour() ? 0xFFFF7043 : 0xFFFFD54F);
            seqTickPaint.setTextSize(10f * density);
            String marker = sprite.isClippedByNeighbour() ? "⊣ clipped" : "continues →";
            float tw = seqTickPaint.measureText(marker);
            if (x1 - x0 > tw + 10 * density) {
                canvas.drawText(marker, x1 - tw - 4 * density, bottom - 4 * density, seqTickPaint);
            }
        }

        repeatPaint.setAlpha(110);
        seqTickPaint.setColor(0xB3FFFFFF);
        float tickW = Math.max(1f, density);
        // Thumbs are skipped, not shrunk, when they would collide: overlapping them would read
        // as a faster cadence than the sequence actually has. The hairline still marks every
        // change, so a dense region shows as a comb rather than as a smear of half-thumbnails.
        float minGap = w * 0.9f;
        float lastDrawnX = Float.NEGATIVE_INFINITY;
        for (com.fadcam.ui.faditor.sprite.SpriteFrameResolver.FrameChange fc : changes) {
            float dx = timeToX.map(start + fc.localMs);
            if (dx < x0 || dx > x1) continue;
            canvas.drawRect(dx, top, dx + tickW, bottom, seqTickPaint);
            if (dx - lastDrawnX < minGap) continue;
            previewDst.set(dx, top, Math.min(dx + w, x1), bottom);
            sr.drawCell(canvas, fc.cellIndex, previewDst, fc.repeat ? repeatPaint : previewPaint);
            lastDrawnX = dx;
        }
    }

    /** Cap on tape marks per row per frame — see {@link #drawSequenceTape}. */
    private static final int MAX_SEQUENCE_MARKS = 400;

    private final android.graphics.Paint seqHandlePaint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);

    /**
     * SPEC_IMAGE_SEQUENCE §2a — <b>the resize mode must be visible ON THE TAPE, not only in the
     * drawer.</b>
     *
     * <p>The spec is blunt about why: <i>"One handle with two different destructive behaviours
     * based on invisible state is the exact trap that bit the caret-vs-trim grab (LEDGER §1f)."</i>
     * So the two modes get different handle shapes and colours on the selected item:</p>
     *
     * <ul>
     *   <li><b>RELATIVE</b> — an amber double-headed arrow bar. It stretches; nothing is lost.</li>
     *   <li><b>ABSOLUTE</b> — a red notched comb. It cuts frames off, and it should look like
     *       something that cuts.</li>
     * </ul>
     */
    private void drawSequenceResizeModeHandles(@NonNull Canvas canvas, @NonNull TimedItem item,
                                               float x0, float top, float x1, float bottom) {
        if (item.getSprite() == null || spriteCellProvider == null) return;
        com.fadcam.ui.faditor.sprite.SpriteSheet sheet =
                spriteCellProvider.sheet(item.getSprite().getSheetId());
        if (sheet == null || !sheet.isSequence()) return;
        boolean absolute = sheet.getResizeMode()
                == com.fadcam.ui.faditor.sprite.SequenceTiming.ResizeMode.ABSOLUTE;
        seqHandlePaint.setColor(absolute ? 0xFFFF5252 : 0xFFFFB300);
        seqHandlePaint.setStyle(android.graphics.Paint.Style.FILL);
        float w = 3.5f * density;
        float h = (bottom - top) * 0.55f;
        float cy = (top + bottom) / 2f;
        for (float ex : new float[]{x0, x1}) {
            if (absolute) {
                // Notched comb: three short teeth, reading as "this removes frames".
                float tooth = h / 5f;
                for (int i = -1; i <= 1; i++) {
                    float ty = cy + i * (tooth * 1.6f) - tooth / 2f;
                    canvas.drawRect(ex - w / 2f, ty, ex + w / 2f, ty + tooth, seqHandlePaint);
                }
            } else {
                // Solid bar: reads as "this stretches".
                canvas.drawRoundRect(ex - w / 2f, cy - h / 2f, ex + w / 2f, cy + h / 2f,
                        w / 2f, w / 2f, seqHandlePaint);
            }
        }
    }


    /**
     * Inverse of {@link TimeToX#map} by local search — the renderer is handed a forward mapping
     * only, and the tape needs "what time is at this pixel" to ask for just the visible window.
     * Two probes a second apart give the (linear) scale; the mapping is linear in time by
     * construction, so this is exact rather than approximate.
     */
    private long msAtX(float x, @NonNull TimeToX timeToX, long itemStartMs) {
        float xAtStart = timeToX.map(itemStartMs);
        float xAtPlusSec = timeToX.map(itemStartMs + 1000);
        float pxPerSec = xAtPlusSec - xAtStart;
        if (Math.abs(pxPerSec) < 0.0001f) return 0;
        return Math.round((x - xAtStart) / pxPerSec * 1000.0);
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

    // ── FADE_KNOBS §2.1-2.4: outboard knob + dark curtain — ONE drawing routine, shared
    // across every host that has a 0..1 intensity (audio volume, image opacity, caption binding).
    // Position IS the readout (§2.1a): knob sits above trim when fade=0, inwards by fade length.

    /** Whether this item has a fade host at all (even at 0). Used to suppress veil/knob on wrong kinds. */
    private boolean hasFadeHost(@NonNull TimedItem item) {
        if (item.getAudioClip() != null) return true;
        if (item.getTextOverlay() != null) return true; // text/image/sprite all use opacity; veil still meaningful
        if (item.getSprite() != null) return true;
        if (item.getWaveform() != null) return true;
        if (item.getClip() != null && item.getClip().isOverlayClip()) return true;
        if (item.getCaptionSpan() != null) return true;
        return false;
    }

    private long getFadeInMsForItem(@NonNull TimedItem item, long totalMs) {
        if (item.getAudioClip() != null) return item.getAudioClip().getFadeInMs();
        if (item.getTextOverlay() != null) return item.getTextOverlay().getImageFadeInMs();
        if (item.getSprite() != null) return 0; // TODO sprite opacity fade model
        if (item.getWaveform() != null) return 0;
        if (item.getClip() != null && item.getClip().isOverlayClip()) {
            // Clip overlay opacity via keyframes not yet — treat as 0 for now, veil still works if added
            return 0;
        }
        if (item.getCaptionSpan() != null) {
            com.fadcam.ui.faditor.model.Clip.CaptionBinding b = item.getCaptionSpan().getBinding();
            if (b != null) return b.fadeInMs;
            // Legacy single-binding fallback via Clip's own? No per-clip fade; use 0
            return 0;
        }
        return 0;
    }

    private long getFadeOutMsForItem(@NonNull TimedItem item, long totalMs) {
        if (item.getAudioClip() != null) return item.getAudioClip().getFadeOutMs();
        if (item.getTextOverlay() != null) return item.getTextOverlay().getImageFadeOutMs();
        if (item.getSprite() != null) return 0;
        if (item.getWaveform() != null) return 0;
        if (item.getClip() != null && item.getClip().isOverlayClip()) return 0;
        if (item.getCaptionSpan() != null) {
            com.fadcam.ui.faditor.model.Clip.CaptionBinding b = item.getCaptionSpan().getBinding();
            if (b != null) return b.fadeOutMs;
            return 0;
        }
        return 0;
    }

    private void setFadeInMsForItem(@NonNull TimedItem item, long ms, long totalMs) {
        ms = Math.max(0, ms);
        if (item.getAudioClip() != null) { item.getAudioClip().setFadeInMs(ms); return; }
        if (item.getTextOverlay() != null) { item.getTextOverlay().setImageFadeInMs(ms, totalMs); return; }
        if (item.getCaptionSpan() != null) {
            com.fadcam.ui.faditor.model.Clip.CaptionBinding b = item.getCaptionSpan().getBinding();
            if (b != null) { b.fadeInMs = Math.max(0, Math.min(ms, item.getDisplayDurationMs(totalMs)/2)); }
            return;
        }
    }

    private void setFadeOutMsForItem(@NonNull TimedItem item, long ms, long totalMs) {
        ms = Math.max(0, ms);
        if (item.getAudioClip() != null) { item.getAudioClip().setFadeOutMs(ms); return; }
        if (item.getTextOverlay() != null) { item.getTextOverlay().setImageFadeOutMs(ms, totalMs); return; }
        if (item.getCaptionSpan() != null) {
            com.fadcam.ui.faditor.model.Clip.CaptionBinding b = item.getCaptionSpan().getBinding();
            if (b != null) { b.fadeOutMs = Math.max(0, Math.min(ms, item.getDisplayDurationMs(totalMs)/2)); }
            return;
        }
    }

    /** Exposed for gesture controller: same clamping as draw. */
    public long[] clampedFadeMs(@NonNull TimedItem item, long totalMs) {
        long dur = Math.max(0, item.getDisplayDurationMs(totalMs));
        long in = getFadeInMsForItem(item, totalMs);
        long out = getFadeOutMsForItem(item, totalMs);
        in = Math.max(0, Math.min(in, dur/2));
        out = Math.max(0, Math.min(out, dur/2));
        if (in + out > dur && dur > 0) { in = dur/2; out = dur - in; }
        return new long[]{in, out};
    }

    /** Dark veil inside the clip for each fade (§2.2) — display only, always when fade>0. */
    private void drawFadeVeils(@NonNull Canvas canvas, @NonNull TimedItem item,
                               float x0, float top, float x1, float bottom,
                               int baseColor, long totalMs, @NonNull TimeToX timeToX) {
        if (!hasFadeHost(item)) return;
        long[] clamped = clampedFadeMs(item, totalMs);
        long fadeIn = clamped[0]; long fadeOut = clamped[1];
        if (fadeIn <= 0 && fadeOut <= 0) return;
        float r = 3f * density;
        // Clip veil to item rounded rect so it doesn't spill beyond corners
        canvas.save();
        Path clipPath = new Path();
        clipPath.addRoundRect(x0, top, x1, bottom, r, r, Path.Direction.CW);
        canvas.clipPath(clipPath);
        fadeVeilPaint.setColor(0xAA05050A);
        if (fadeIn > 0) {
            float fx = timeToX.map(item.getTimelineStartMs() + fadeIn);
            fx = Math.max(x0, Math.min(fx, x1));
            if (fx > x0 + 1f) {
                fadeVeilPath.rewind();
                fadeVeilPath.moveTo(x0, top);
                fadeVeilPath.lineTo(fx, top);
                fadeVeilPath.lineTo(x0, bottom);
                fadeVeilPath.close();
                canvas.drawPath(fadeVeilPath, fadeVeilPaint);
                // sloped edge highlight
                Paint edge = itemSelectionPaint;
                int pc = edge.getColor(); float pw = edge.getStrokeWidth(); Paint.Style ps = edge.getStyle();
                edge.setColor(brighten(baseColor)); edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(1.4f*density); edge.setAlpha(140);
                canvas.drawLine(fx, top, x0, bottom, edge);
                edge.setColor(pc); edge.setStyle(ps); edge.setStrokeWidth(pw); edge.setAlpha(255);
                // dotted inner boundary line in object's colour (§2.4)
                fadeEdgePaint.setColor(baseColor);
                canvas.drawLine(fx, top, fx, bottom, fadeEdgePaint);
            }
        }
        if (fadeOut > 0) {
            float fx = timeToX.map(item.getTimelineStartMs() + item.getDisplayDurationMs(totalMs) - fadeOut);
            fx = Math.max(x0, Math.min(fx, x1));
            if (fx < x1 - 1f) {
                fadeVeilPath.rewind();
                fadeVeilPath.moveTo(fx, top);
                fadeVeilPath.lineTo(x1, top);
                fadeVeilPath.lineTo(x1, bottom);
                fadeVeilPath.close();
                canvas.drawPath(fadeVeilPath, fadeVeilPaint);
                Paint edge = itemSelectionPaint;
                int pc = edge.getColor(); float pw = edge.getStrokeWidth(); Paint.Style ps = edge.getStyle();
                edge.setColor(brighten(baseColor)); edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(1.4f*density); edge.setAlpha(140);
                canvas.drawLine(fx, top, x1, bottom, edge);
                edge.setColor(pc); edge.setStyle(ps); edge.setStrokeWidth(pw); edge.setAlpha(255);
                fadeEdgePaint.setColor(baseColor);
                canvas.drawLine(fx, top, fx, bottom, fadeEdgePaint);
            }
        }
        canvas.restore();
        // Duration label while dragging (§2.3) — riding inside edge of veil, below knob, not over darkest area
        if (draggingFadeItemId != null && draggingFadeItemId.equals(item.getId())) {
            String label = String.format(java.util.Locale.US, "%.1f s", draggingFadeMs / 1000f);
            // Place near the active knob's diagonal mid, offset inside veil for readability
            float fx = draggingFadeIsIn
                    ? timeToX.map(item.getTimelineStartMs() + clamped[0])
                    : timeToX.map(item.getTimelineStartMs() + item.getDisplayDurationMs(totalMs) - clamped[1]);
            float lx = draggingFadeIsIn ? (x0 + fx)/2f : (fx + x1)/2f;
            float ly = top + (bottom - top) * 0.32f;
            // keep inside item
            lx = Math.max(x0 + 14f*density, Math.min(lx, x1 - 14f*density));
            // backdrop for readability
            float tw = fadeDurationPaint.measureText(label);
            float pad = 3f*density;
            Paint bg = itemSelectionPaint;
            int pbc = bg.getColor(); Paint.Style pbs = bg.getStyle();
            bg.setColor(0xCC000000); bg.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(lx - tw/2f - pad, ly - 9f*density, lx + tw/2f + pad, ly + 4f*density, 3f*density, 3f*density, bg);
            bg.setColor(pbc); bg.setStyle(pbs);
            canvas.drawText(label, lx, ly, fadeDurationPaint);
        }
    }

    /** Outboard knob + stem (§2.1) — selected only, one per fade, positioned from model every pass. */
    private void drawFadeKnobs(@NonNull Canvas canvas, @NonNull TimedItem item,
                               float x0, float top, float x1, float bottom,
                               int baseColor, long totalMs, @NonNull TimeToX timeToX) {
        if (!hasFadeHost(item)) return;
        long[] clamped = clampedFadeMs(item, totalMs);
        // Always draw knobs even at 0 — they sit above trim area as the readout that fade=0
        float knobR = FADE_KNOB_R_DP * density;
        float stemW = FADE_STEM_W_DP * density;
        float offset = FADE_KNOB_TOP_OFFSET_DP * density;
        fadeKnobStrokePaint.setColor(baseColor);
        fadeStemPaint.setColor(baseColor);
        fadeStemPaint.setAlpha(140);
        // Fade-in knob
        {
            long fadeIn = clamped[0];
            float fx = timeToX.map(item.getTimelineStartMs() + fadeIn);
            fx = Math.max(x0, Math.min(fx, x1));
            float cx = fx;
            float cy = top - offset;
            // stem
            canvas.drawLine(cx, top, cx, cy + knobR - 1f*density, fadeStemPaint);
            // knob disc
            fadeKnobFillPaint.setColor(0xFF1C1C26);
            canvas.drawCircle(cx, cy, knobR, fadeKnobFillPaint);
            canvas.drawCircle(cx, cy, knobR, fadeKnobStrokePaint);
            canvas.drawCircle(cx, cy, 3.4f * density, itemSelectionPaint); // inner dot in base colour
            // inner dot colour
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(baseColor);
            canvas.drawCircle(cx, cy, 3.4f * density, dot);
        }
        // Fade-out knob
        {
            long fadeOut = clamped[1];
            long dur = item.getDisplayDurationMs(totalMs);
            float fx = timeToX.map(item.getTimelineStartMs() + dur - fadeOut);
            fx = Math.max(x0, Math.min(fx, x1));
            float cx = fx;
            float cy = top - offset;
            canvas.drawLine(cx, top, cx, cy + knobR - 1f*density, fadeStemPaint);
            fadeKnobFillPaint.setColor(0xFF1C1C26);
            canvas.drawCircle(cx, cy, knobR, fadeKnobFillPaint);
            canvas.drawCircle(cx, cy, knobR, fadeKnobStrokePaint);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(baseColor);
            dot.setStyle(Paint.Style.FILL);
            dot.setAntiAlias(true);
            canvas.drawCircle(cx, cy, 3.4f * density, dot);
        }
    }

    /** Legacy name kept for any stray call — delegates to new veil+knob split (no-op if not selected). */
    private void drawFadeHandles(@NonNull Canvas canvas, float x0, float top, float x1, float bottom, int baseColor) {
        // Retired wedge path — fade handles are now outboard knobs+veils drawn via the two methods above.
    }

    /**
     * JoyRaptor's cross-fade pill (§5) — drawn at the SEAM between two adjacent audio lanes.
     *
     * <p><b>The shading tells the truth about what you will hear (§5.1).</b> His original
     * sketch darkened "everything on the preview tape after it". That would be false on the
     * winning lane, which is at full AFTER the crossover. So the LOSER darkens after the fade
     * and the WINNER darkens before it: at a glance the bright tape is the audible tape, with
     * no legend to read.</p>
     *
     * <p><b>Drawn at 14dp, hit at 24dp.</b> {@code AUDIO_LANE_GAP_DP} is 3dp — far too thin to
     * hold a finger — so the pill overhangs into both lanes visually and its touch box is
     * inflated further still. Precedence is settled in §5.2: the pill beats an item BODY (it is
     * smaller, on top and more specific) and loses to trim and fade handles.</p>
     */
    private void drawAudioCrossfades(@NonNull Canvas canvas, int floatingRowCount,
                                     @NonNull TimeToX timeToX) {
        if (audioCrossfades.isEmpty()) return;
        float halfH = XFADE_PILL_H_DP * density / 2f;
        for (com.fadcam.ui.faditor.model.AudioCrossfade x : audioCrossfades) {
            int idx = -1;
            for (int i = floatingRowCount; i < rows.size(); i++) {
                if (rows.get(i).track.getId().equals(x.getLowerLaneId())) { idx = i; break; }
            }
            // A pill needs a lane on BOTH sides. The topmost audio lane has nothing above it,
            // so a fade naming it is stale data (its upper lane was deleted) — skip rather
            // than draw a bridge to nowhere.
            if (idx <= floatingRowCount) continue;
            RowLayout lower = rows.get(idx);
            RowLayout upper = rows.get(idx - 1);
            float seamY = (upper.bodyRect.bottom + lower.bodyRect.top) / 2f;
            float x0 = timeToX.map(x.getStartMs());
            float x1 = timeToX.map(x.getEndMs());
            if (x1 < lower.bodyRect.left - 40 || x0 > lower.bodyRect.right + 40) continue;

            int rgb = x.getColorRgb();
            boolean up = x.isToLaneAbove();
            RowLayout winner = up ? upper : lower;
            RowLayout loser  = up ? lower : upper;

            // Winner is dim BEFORE the crossover; loser is dim AFTER it.
            xfadeShadePaint.setStyle(Paint.Style.FILL);
            xfadeShadePaint.setColor(0x00000000 | (Math.round(XFADE_SHADE_ALPHA * 255f) << 24));
            canvas.drawRect(winner.bodyRect.left, winner.bodyRect.top,
                    Math.max(winner.bodyRect.left, x0), winner.itemsBottom(), xfadeShadePaint);
            canvas.drawRect(Math.min(loser.bodyRect.right, x1), loser.bodyRect.top,
                    loser.bodyRect.right, loser.itemsBottom(), xfadeShadePaint);

            // The pill.
            boolean sel = x.getId().equals(selectedCrossfadeId);
            xfadePaint.setStyle(Paint.Style.FILL);
            xfadePaint.setColor(0xFF000000 | rgb);
            xfadePaint.setAlpha(sel ? 255 : 220);
            float r = halfH;
            canvas.drawRoundRect(x0, seamY - halfH, x1, seamY + halfH, r, r, xfadePaint);
            if (sel) {
                xfadePaint.setStyle(Paint.Style.STROKE);
                xfadePaint.setStrokeWidth(1.5f * density);
                xfadePaint.setColor(0xFFFFFFFF);
                canvas.drawRoundRect(x0, seamY - halfH, x1, seamY + halfH, r, r, xfadePaint);
            }

            // Diagonal arrows: which way the sound is travelling. No text — §5 asks for a
            // visible DIRECTION, and a letter pair would need explaining.
            xfadePaint.setStyle(Paint.Style.STROKE);
            xfadePaint.setStrokeWidth(1.6f * density);
            xfadePaint.setColor(0xFF101010);
            float step = 18f * density;
            float a = 4f * density;
            for (float cx = x0 + step * 0.5f; cx < x1 - a; cx += step) {
                float dy = up ? -a : a;
                xfadeArrowPath.reset();
                xfadeArrowPath.moveTo(cx - a, seamY - dy);
                xfadeArrowPath.lineTo(cx, seamY + dy);
                xfadeArrowPath.lineTo(cx + a, seamY - dy);
                canvas.drawPath(xfadeArrowPath, xfadePaint);
            }
        }
    }

    /** SPEC_AUDIO_UX_V1 D10: struck-word spans as darkened tape — same mechanism as Clip, routed for AudioClip. */
    private void drawAudioStruckTape(@NonNull Canvas canvas, @NonNull TimedItem item, float x0, float top, float x1, float bottom, @NonNull TimeToX timeToX) {
        com.fadcam.ui.faditor.model.AudioClip ac = item.getAudioClip();
        if (ac == null || ac.getRemovedSpans().isEmpty()) return;
        long clipStart = item.getTimelineStartMs();
        long inPoint = ac.getInPointMs();
        Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
        dark.setColor(0xAA000000);
        dark.setStyle(Paint.Style.FILL);
        for (long[] span : ac.getRemovedSpans()) {
            long s = span[0], e = span[1];
            float sx = timeToX.map(clipStart + (s - inPoint));
            float ex = timeToX.map(clipStart + (e - inPoint));
            if (ex < x0 || sx > x1) continue;
            sx = Math.max(sx, x0);
            ex = Math.min(ex, x1);
            canvas.drawRect(sx, top, ex, bottom, dark);
            // Thin diagonal hatch to make it read as "cut" even on monochrome.
            dark.setColor(0x22FFFFFF);
            dark.setStrokeWidth(1f * density);
            for (float hx = sx; hx < ex; hx += 6f * density) {
                canvas.drawLine(hx, top, hx + 4f * density, bottom, dark);
            }
            dark.setColor(0xAA000000);
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

    /**
     * F-COLOR: the hue now lives in {@link ObjectPalette} (one table for the whole editor) —
     * this only applies the item-body alpha. Prefer {@link #baseColorForItem} at any call site
     * that HAS the item: colouring from a row's kind is what turned a purple text object blue
     * after it was moved onto another lane.
     */
    private int baseColorFor(@NonNull TrackKind kind) {
        return ObjectPalette.body(ObjectPalette.forKind(kind));
    }

    /** F-COLOR: an item's body colour, resolved from the OBJECT's kind, not its lane's. */
    private int baseColorForItem(@NonNull TimedItem item, @NonNull TrackKind rowKind) {
        return ObjectPalette.body(ObjectPalette.forItem(item, rowKind));
    }

    /**
     * Draw the row's kind badge (LANE_BADGES spec §1) at {@code (leftX, cy)} —
     * small canvas-drawn glyphs matching the gutter icon style: filmstrip with
     * sprocket holes (video), T-in-a-box (text), mountain-in-a-frame (image),
     * stickman-in-a-ring (sprite), CC box (captions), bars (visualizer/audio).
     */
    /** The OBJECT's own kind (JoyRaptor 2026-07-19: badges ride objects, lanes are neutral).
     *  Falls back to the row kind for payloads without a distinct identity.
     *
     *  <p>PUBLIC because it is the single answer to "what kind is this object", which under
     *  the neutral substrate is no longer the same question as "what kind is its lane". Every
     *  consumer that used to read the lane's {@link TrackKind} for an ITEM must come here
     *  instead — a third private copy of this switch is how the badge, the mute icon and the
     *  playhead tint drifted apart in the first place.</p> */
    @NonNull
    public static TrackKind payloadKindOf(@NonNull TimedItem item, @NonNull TrackKind rowKind) {
        // Delegates so the badge, the body colour, the playhead tint and the mini-map all read
        // ONE definition of what an object is (F-COLOR). Kept as a public entry point because
        // several call sites already refer to it by this name.
        return ObjectPalette.payloadKindOf(item, rowKind);
    }

    /** Left inset of the kind badge from the item block's left edge. */
    private static final float KIND_BADGE_INSET_DP = 3f;
    /** Badge box size — {@code s} in {@link #drawKindBadge}. */
    private static final float KIND_BADGE_SIZE_DP = 12f;
    /** Widest badge is the boxed glyph at {@code s * 1.25}; the bars/ring variants are narrower. */
    private static final float KIND_BADGE_WIDTH_DP = KIND_BADGE_SIZE_DP * 1.25f;
    /**
     * Where an item's text label may start when the kind badge is drawn: past the badge's
     * right edge plus a breathing gap. Keep this derived from the badge geometry — the
     * overlap this fixes came from the label and the badge each carrying their own literal.
     */
    private static final float KIND_BADGE_LABEL_INSET_DP =
            KIND_BADGE_INSET_DP + KIND_BADGE_WIDTH_DP + 4f;
    /** Minimum item width that earns a kind badge — below this the block is a sliver. */
    private static final float KIND_BADGE_MIN_ITEM_DP = 40f;

    /** Whether {@link #drawKindBadge} will paint for an item block spanning {@code [x0, x1]}. */
    private boolean kindBadgeVisible(float x0, float x1) {
        return x1 - x0 > KIND_BADGE_MIN_ITEM_DP * density;
    }

    private void drawKindBadge(@NonNull Canvas canvas, float leftX, float cy,
                               @NonNull TrackKind kind) {
        float s = KIND_BADGE_SIZE_DP * density;   // badge box size
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
        if (item.getAdjustment() != null) {
            // The layer's own name, so several are told apart at a glance. The effect count is
            // appended once there is one: an adjustment layer with an empty stack changes
            // nothing, and saying so on the chip is cheaper than wondering why nothing happened.
            int fx = item.getAdjustment().getFx().active().size();
            String name = item.getAdjustment().getName();
            return fx > 0 ? name + " · " + fx : name;
        }
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
            // (Retired: the HIDE/LOCK header zones were always-empty rects that could never
            // fire — hide/lock are per-object now, in the object drawer. Dead branches removed.)
            if (row.muteHitRect.contains(x, localY)) {
                // §3b: the icon is now ABSENT on a row that carries no audio, so there is
                // nothing there to tap — but the touch box still has to agree with the
                // picture, or the empty space would flip TrackFlags.muted, push a "Mute
                // track" undo step and schedule an autosave with zero audible effect.
                // Consume as NONE (same as an empty-header tap) rather than falling through
                // to the body.
                return new HeaderHit(row.track,
                        rowCarriesAudio(row.track) ? HitZone.MUTE : HitZone.NONE);
            }
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
     * Every track currently laid out, both bands, in row order.
     *
     * <p>For the drag's cross-lane ALIGNMENT snap: the edges worth snapping to are the ones the
     * user can SEE, and that is exactly the set this renderer just laid out. Asking the model
     * instead would include rows scrolled out of the world and rows the layout skipped, so the
     * item would click onto an edge with nothing on screen to explain why.</p>
     */
    @NonNull
    public List<Track> laidOutTracks() {
        List<Track> out = new java.util.ArrayList<>(rows.size());
        for (RowLayout row : rows) out.add(row.track);
        return out;
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
    /** SPEC_AUDIO_UX_V1 §4: trim = outer 16dp full height, selection-only, trim wins. */
    private static final float TRIM_WIDTH_DP = 16f;
    // FADE_KNOBS §4: old in-row 20×12dp fade zone RETIRED — outboard knob is the only grip.
    // E2_DEBUG retired with it (was true in production, logged every top-corner touch).

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
    /**
     * The AUDIO lane immediately above or below {@code laneId}, or null at the band edge.
     * Used by §5.4's fluent cross-fade creation, which needs to know what a fade is fading
     * TO. Floating (non-audio) rows are skipped: a cross-fade is an audio-lane relationship.
     */
    @Nullable
    public String adjacentAudioLaneId(@NonNull String laneId, boolean above) {
        int idx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).floatingBand && rows.get(i).track.getId().equals(laneId)) {
                idx = i; break;
            }
        }
        if (idx < 0) return null;
        int n = idx + (above ? -1 : 1);
        if (n < 0 || n >= rows.size() || rows.get(n).floatingBand) return null;
        return rows.get(n).track.getId();
    }

    /** Which part of a cross-fade pill a touch landed on. */
    public enum XfadeZone { BODY, LEFT_EDGE, RIGHT_EDGE }

    /** Result of {@link #hitTestCrossfade}. */
    public static final class XfadeHit {
        @NonNull public final com.fadcam.ui.faditor.model.AudioCrossfade xfade;
        @NonNull public final XfadeZone zone;
        XfadeHit(@NonNull com.fadcam.ui.faditor.model.AudioCrossfade x, @NonNull XfadeZone z) {
            this.xfade = x; this.zone = z;
        }
    }

    /**
     * Hit-test the cross-fade pills (§5.2).
     *
     * <p><b>Drawn at 14dp, hit at 24dp.</b> {@code AUDIO_LANE_GAP_DP} is 3dp, so the seam alone
     * could never hold a finger; the pill overhangs both lanes visually and its touch box is
     * inflated further still. That inflation is why PRECEDENCE has to be explicit, and the
     * caller enforces it: this is only consulted after {@link #hitTestItem} has declined or
     * returned {@code BODY}, so trim handles, fade handles and the delete badge all win and the
     * pill only beats bare item body.</p>
     *
     * <p>Edge zones are the outer 14dp of the pill, and never more than a third of it — on a
     * short fade an edge that ate half the pill would leave no middle to grab and sliding
     * would become impossible.</p>
     */
    @Nullable
    public XfadeHit hitTestCrossfade(float x, float y, float topPx, @NonNull TimeToX timeToX) {
        if (audioCrossfades.isEmpty()) return null;
        float halfHit = 12f * density;              // 24dp tall box around the seam
        float edgeW = 14f * density;
        for (com.fadcam.ui.faditor.model.AudioCrossfade xf : audioCrossfades) {
            int idx = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).track.getId().equals(xf.getLowerLaneId())) { idx = i; break; }
            }
            if (idx <= 0) continue;
            RowLayout lower = rows.get(idx);
            RowLayout upper = rows.get(idx - 1);
            if (lower.floatingBand || upper.floatingBand) continue;
            float localY = bandLocalY(lower, y, topPx);
            if (Float.isNaN(localY)) continue;
            float seamY = (upper.bodyRect.bottom + lower.bodyRect.top) / 2f;
            if (Math.abs(localY - seamY) > halfHit) continue;
            float x0 = timeToX.map(xf.getStartMs());
            float x1 = timeToX.map(xf.getEndMs());
            if (x < x0 - edgeW * 0.5f || x > x1 + edgeW * 0.5f) continue;
            float e = Math.min(edgeW, (x1 - x0) / 3f);
            if (x <= x0 + e) return new XfadeHit(xf, XfadeZone.LEFT_EDGE);
            if (x >= x1 - e) return new XfadeHit(xf, XfadeZone.RIGHT_EDGE);
            return new XfadeHit(xf, XfadeZone.BODY);
        }
        return null;
    }

    @Nullable
    public ItemHit hitTestItem(float x, float y, float topPx, long totalMs,
                                 @NonNull TimeToX timeToX, @Nullable String selectedItemId) {
        if (!inAnyBand(y, topPx)) return null;
        float specTrimW = TRIM_WIDTH_DP * density;
        float knobHitR = FADE_KNOB_HIT_R_DP * density;
        float knobOffset = FADE_KNOB_TOP_OFFSET_DP * density;
        for (RowLayout row : rows) {
            float localY = bandLocalY(row, y, topPx);
            // For knob hits we allow y above the row (outboard), so check broad band: expand upward by knobOverhang
            boolean inRowExpanded = !Float.isNaN(localY)
                    && localY >= row.bodyRect.top - knobOffset - knobHitR
                    && localY <= row.bodyRect.bottom;
            if (!inRowExpanded) continue;
            Track t = row.track;
            if (t.isCollapsed() || t.isLocked() || t.isHidden()) return null;
            float top = row.bodyRect.top + 3f * density;
            float bottom = row.itemsBottom() - 3f * density;
            // Check knob hits first for selected items (outboard, decoupled from row height) — §4 collision fix
            for (TimedItem item : t.getItems()) {
                float x0 = timeToX.map(item.getTimelineStartMs());
                long dur = item.getDisplayDurationMs(totalMs);
                float x1 = Math.max(x0 + 6f * density, timeToX.map(item.getTimelineStartMs() + dur));
                boolean selected = selectedItemId != null && selectedItemId.equals(item.getId());
                if (selected && hasFadeHost(item)) {
                    long[] clamped = clampedFadeMs(item, totalMs);
                    float fxIn = timeToX.map(item.getTimelineStartMs() + clamped[0]);
                    fxIn = Math.max(x0, Math.min(fxIn, x1));
                    float cyIn = top - knobOffset;
                    float dx = x - fxIn, dy = localY - cyIn;
                    if (dx*dx + dy*dy <= knobHitR*knobHitR) return new ItemHit(t, item, ItemZone.FADE_IN);
                    long durForOut = dur;
                    float fxOut = timeToX.map(item.getTimelineStartMs() + durForOut - clamped[1]);
                    fxOut = Math.max(x0, Math.min(fxOut, x1));
                    float cyOut = top - knobOffset;
                    dx = x - fxOut; dy = localY - cyOut;
                    if (dx*dx + dy*dy <= knobHitR*knobHitR) return new ItemHit(t, item, ItemZone.FADE_OUT);
                }
            }
            // Now check inside-body zones: need y inside clip
            if (!(localY >= top && localY <= bottom)) continue;
            for (TimedItem item : t.getItems()) {
                float x0 = timeToX.map(item.getTimelineStartMs());
                long dur = item.getDisplayDurationMs(totalMs);
                float x1 = Math.max(x0 + 6f * density, timeToX.map(item.getTimelineStartMs() + dur));
                if (x < x0 - specTrimW || x > x1 + specTrimW) continue;
                boolean selected = selectedItemId != null && selectedItemId.equals(item.getId());
                float trimW = specTrimW;
                if (selected && x <= x0 + trimW) {
                    return new ItemHit(t, item, ItemZone.LEFT_HANDLE);
                }
                if (selected && x >= x1 - trimW) {
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

    /**
     * Half-width (px) of an item's trim-handle hit zone, for a caller that must place a control
     * INSIDE those handles rather than on top of them (the text-box timing carets).
     *
     * <p>Exposed rather than re-derived at the call site on purpose: the carets sit roughly a
     * finger's width from the trim caps, so a caller working from its own idea of the handle width
     * would put them where the trim grab already is — and "the drawn thing and the grabbable thing
     * came from two derivations" is the bug class this file has paid for more than once.</p>
     */
    public float itemHandleHalfWidthPx() {
        return ITEM_HANDLE_HALF_WIDTH_DP * density;
    }

    /**
     * The content-x span row bodies occupy ({@code {left, right}}), or null when nothing is laid
     * out. Every row shares it — it is the pinned header's right edge to the viewport's right edge.
     *
     * <p>For a caller that draws its own decoration on an item and must not spill outside the row:
     * {@link #drawRow} clips items to this span, so a decoration drawn from {@link #itemBodyRect}
     * — which reports the item's TRUE tape, extending past the viewport when it must — has to clip
     * to the same span or it will paint over the pinned row headers.</p>
     */
    @Nullable
    public float[] rowContentXRange() {
        if (rows.isEmpty()) return null;
        RectF b = rows.get(0).bodyRect;
        return new float[]{b.left, b.right};
    }

    /**
     * The on-screen body of one item from the last {@link #layout} pass, or null when it is not
     * addressable: no such item, a collapsed/locked/hidden row, or a row scrolled out of the
     * floating band's viewport.
     *
     * <p><b>Coordinate space, which is the whole reason this method exists:</b> left/right are
     * CONTENT-x (the axis {@code timeToX} maps into, the same one {@link #hitTestItem} takes) and
     * top/bottom are SCREEN-y. That mixture is not a convenience — it is what an item's geometry
     * genuinely IS here, because the horizontal axis scrolls with the timeline while the vertical
     * one scrolls with the layer band's OWN independent scroll ({@code scrollOffsetPx} on this
     * class is a different field from the timeline view's identically-named one). A caller drawing
     * inside the view's {@code translate(-scrollOffsetPx, 0)} is in exactly this space.</p>
     *
     * <p>Every number below is taken from {@link #hitTestItem} rather than recomputed: the same
     * {@code x0}/{@code x1} including its 6dp minimum width, the same 3dp vertical inset, the same
     * {@code itemsBottom()} so an open audio drawer shortens the body identically, and the same
     * band mapping inverted ({@code bandLocalY}'s {@code y - topPx + scrollOffsetPx}). A caret
     * drawn from a second derivation would land where the finger cannot grab it.</p>
     *
     * <p>Floating rows are clamped to the band viewport, matching {@code bandLocalY}'s NaN guard:
     * a touch above or below the viewport misses every row, so a body drawn outside it would be
     * visible and dead. Clamping (rather than returning the unclamped rect) keeps draw and
     * hit-test reading the same rect.</p>
     */
    @Nullable
    public RectF itemBodyRect(@NonNull String itemId, float topPx, long totalMs,
                              @NonNull TimeToX timeToX) {
        for (RowLayout row : rows) {
            Track t = row.track;
            if (t.isCollapsed() || t.isLocked() || t.isHidden()) continue;
            for (TimedItem item : t.getItems()) {
                if (!itemId.equals(item.getId())) continue;
                float x0 = timeToX.map(item.getTimelineStartMs());
                long dur = item.getDisplayDurationMs(totalMs);
                float x1 = Math.max(x0 + 6f * density,
                        timeToX.map(item.getTimelineStartMs() + dur));
                float localTop = row.bodyRect.top + 3f * density;
                float localBottom = row.itemsBottom() - 3f * density;
                if (row.floatingBand) {
                    // Inverse of bandLocalY: local = screen - topPx + scrollOffsetPx. topPx is the
                    // PARAMETER, not the stored lastTopPx, because that is what bandLocalY uses —
                    // mirroring it exactly is the point, even though today's single caller passes
                    // the same value to both.
                    float top = localTop + topPx - scrollOffsetPx;
                    float bottom = localBottom + topPx - scrollOffsetPx;
                    float vTop = topPx;
                    float vBot = topPx + viewportHeightPx;
                    if (bottom <= vTop || top >= vBot) return null; // scrolled out of the band
                    return new RectF(x0, Math.max(top, vTop), x1, Math.min(bottom, vBot));
                }
                return new RectF(x0, localTop + lastAudioTopPx, x1, localBottom + lastAudioTopPx);
            }
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
            float bottom = row.itemsBottom() - 3f * density;
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
            float bottom = topPx + row.itemsBottom() - 3f * density - scrollOffsetPx;
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
                float bottom = lastAudioTopPx + row.itemsBottom() - 3f * density;
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
        return setMaxVisibleRowsDp(dp, /* clampToContent = */ true);
    }

    /**
     * As {@link #setMaxVisibleRowsDp(float)}, but {@code clampToContent=false} for a cap the
     * USER asked for by dragging the grab bar.
     *
     * <p>The content clamp below exists to stop the PiP's additive band fill ratcheting the cap
     * past anything renderable. That is the right guard for the AUTOMATIC path and the wrong one
     * for a deliberate drag: on a sparse project - one video lane and one music lane, which is
     * exactly how a music video starts - the content is only a couple of rows tall, so the cap
     * is already at the content ceiling and every drag returns the value it was given. The bar
     * does not move at all, and the preview can never be collapsed far enough to pop out.
     *
     * <p>A user drag has its own, correct ceiling already: the grab bar clamps through
     * {@code PreviewPipController.maxBandDpFor}, which is the space the column actually has.
     * Empty band below the rows is a legitimate thing to ask for - it is what "give the timeline
     * more of the screen" means when there are only two lanes to show.
     */
    public float setMaxVisibleRowsDp(float dp, boolean clampToContent) {
        float capped = Math.max(MIN_VISIBLE_ROWS_DP, Math.min(MAX_VISIBLE_ROWS_CAP_DP, dp));
        // A VIEWPORT CAP TALLER THAN THE CONTENT IS DEAD TRAVEL. This is a cap on how much of
        // the rows to show, so once it passes the rows' own height, raising it further renders
        // identically -- but it is still stored, and the grab bar still persists it. The PiP's
        // additive band fill runs on every promote, so promote/demote cycles ratcheted it up
        // with nothing to push back: JoyRaptor's Note 9 reached 820dp over content worth ~420dp.
        //
        // From there the grab bar is dead in BOTH directions -- dragging up moves a number
        // nothing can render, and dragging down does nothing for the ~400dp it takes to get
        // back to the content height ("i couldent grab the bar either"). Clamping to the
        // content makes the stored value always meaningful, so the very next drag rescues an
        // install that already ran away. Only clamp once the content has actually been
        // measured; contentHeightPx is 0 before the first layout and pinning to that would
        // freeze the band at its minimum.
        if (clampToContent && contentHeightPx > 0f) {
            float contentDp = contentHeightPx / density + BAND_CAP_SLACK_DP;
            capped = Math.max(MIN_VISIBLE_ROWS_DP, Math.min(capped, contentDp));
        }
        maxVisibleRowsDp = capped;
        return maxVisibleRowsDp;
    }

    /**
     * B4: the master level meter for the preview corner. A small vertical bar that sums the
     * per-track program levels ({@link #trackLevelAt}) across BOTH lane bands at the playhead
     * the host pushes via {@link #reportHostPlayheadMs}.
     *
     * <p>It is a dumb view on purpose: it holds no clock and runs no timer — the host's
     * existing playhead tick calls {@link #invalidate()}, and each draw reads the pushed
     * playhead. Resting state is the dim baseline track at zero, ALWAYS drawn (the G18
     * lesson: a meter that appears only when audio plays reads as broken).</p>
     *
     * <p>Like the gutter bars this shows the mix maths' intended loudness — envelope × gain,
     * mute/solo-gated — not a post-DSP measurement; no engine tap exists yet.</p>
     */
    public static final class MasterMeterView extends android.view.View {

        private static final int TRACK_COLOR = 0x33000000;
        private static final int FILL_COLOR = 0xFF4CAF50;
        private static final int CLIP_COLOR = 0xFFFF5252;
        private static final float FULL_SCALE = 1.0f;

        @Nullable private java.util.List<Track> floatingBand;
        @Nullable private java.util.List<Track> audioBand;
        private final android.graphics.Paint trackPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fillPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        /** Smoothed display level so the bar falls like a real PPM instead of flickering. */
        private float displayLevel = 0f;

        public MasterMeterView(@NonNull android.content.Context ctx) {
            super(ctx);
            // Deliberately NO elevation: at 6f it drew ABOVE the ObjectDrawer, an idle
            // indicator floating over the workbench the user was actively using.
        }

        public void setTracks(@Nullable java.util.List<Track> floating,
                              @Nullable java.util.List<Track> audio) {
            floatingBand = floating;
            audioBand = audio;
            invalidate();
        }

        /** One tick from the host: advance smoothing + repaint. */
        public void tick() {
            long ph = hostPlayheadMs;
            float target = 0f;
            if (ph != Long.MIN_VALUE) {
                if (floatingBand != null) {
                    for (Track t : floatingBand) target += trackLevelAt(t, ph);
                }
                if (audioBand != null) {
                    for (Track t : audioBand) target += trackLevelAt(t, ph);
                }
                target = Math.max(0f, Math.min(1.2f, target));
            }
            // Attack instant, release ~300ms at 60fps ticks — reads as a meter, not a strobe.
            displayLevel = target > displayLevel ? target : displayLevel * 0.94f + target * 0.06f;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull android.graphics.Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w < 2f || h < 2f) return;
            float r = w / 2f;
            trackPaint.setColor(TRACK_COLOR);
            canvas.drawRoundRect(0f, 0f, w, h, r, r, trackPaint);
            float frac = Math.max(0f, Math.min(1f, displayLevel / FULL_SCALE));
            if (frac > 0.01f) {
                fillPaint.setColor(displayLevel > FULL_SCALE ? CLIP_COLOR : FILL_COLOR);
                canvas.drawRoundRect(0f, h - h * frac, w, h, r, r, fillPaint);
            }
        }
    }
}

