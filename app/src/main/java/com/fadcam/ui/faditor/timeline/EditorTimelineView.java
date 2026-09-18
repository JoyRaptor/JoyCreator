package com.fadcam.ui.faditor.timeline;

import com.fadcam.ui.faditor.Studio;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Transition;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.keyframe.Keyframe;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;
import com.fadcam.ui.faditor.layers.ObjectPalette;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Advanced NLE timeline: fixed center playhead, pinch-zoom, frame thumbnails.
 * Timeline scrolls horizontally while playhead remains centered.
 */
public class EditorTimelineView extends View {

    private static final String TAG = "EditorTimelineView";

    // ── Scale & layout constants (dp) ────────────────────────────────
    private static final float BASE_DP_PER_SECOND = 50f;
    /** 0.5→0.07 (JoyRaptor 2026-07-16): lets a phone see ~2-minute stretches. Draw cost is
     *  viewport-bounded (tiles/mips/culled words), so deep zoom-out stays cheap. */
    private static final float MIN_ZOOM = 0.07f;
    private static final float MAX_ZOOM = 8f;
    private static final float MIN_SEGMENT_DP = 80f;
    private static final float EDGE_PADDING_DP = 20f;

    private static final float RULER_HEIGHT_DP = 22f;
    /**
     * SPEC_N §3 — height of the COLLAPSED spine strip. Deliberately the same 14dp as
     * {@code LayerRowRenderer.ROW_HEIGHT_COLLAPSED_DP}: JoyRaptor asked for "a little tiny
     * collapsible caret as well", i.e. the spine folding away into the SAME visual language
     * a collapsed audio row already uses, not a second collapse idea with its own look.
     */
    private static final float SPINE_COLLAPSED_HEIGHT_DP = 14f;
    /**
     * The master tape's slot at the bottom of the minimap.
     *
     * <p>JoyRaptor: "the spine preview that sits under the timeline minimap — that spine
     * part was before all the other minimap was made, so currently its too thick, you can
     * shorten the spike part 50% or 60%."
     *
     * <p>He is describing an artefact of the order things were built in. This band was the
     * WHOLE minimap once, so 16dp was the right size for it; then the per-layer lines were
     * added above it and the tape kept a height it had earned as the only thing there.
     *
     * <h3>Why 7dp was wrong, and it was not the height that was wrong</h3>
     * The first cut took the SLOT from 16dp to 7dp and left the insets at 3dp top and
     * bottom — so the drawn block went from 10dp to ONE. JoyRaptor: "theres one pixel for
     * the top and another for the bottom, to even have ONE pixel available to show gap you
     * need a minimum of 3 pixels ... this one looks just like object layers."
     *
     * <p>He is right, and the reason is that this band is not decoration. It carries the
     * thumbnail loading meters, the transcription progress bars, the audio-analysis bar and
     * the silence gaps — four things stacked inside its height. A band with a 1dp interior
     * cannot show any of them, so the cut did not shrink a graphic, it deleted a readout.
     *
     * <p>11dp with 1.5dp insets gives an 8dp interior: a 31% cut off the slot and only 20%
     * off the part that has to be legible. Shorter than the original, and still a band
     * rather than a line.
     */
    private static final float MINIMAP_HEIGHT_DP = 11f;
    /**
     * Inset above and below the master tape inside its slot.
     *
     * <p>Scaled WITH the slot rather than left at a literal 3dp. That is the mistake the
     * first cut made: a fixed inset takes a fixed bite, so halving the slot did not halve
     * the block, it very nearly removed it.
     */
    private static final float MINIMAP_TAPE_INSET_DP = 1.5f;
    // F-MINIMAP: thin per-layer lines stacked ABOVE the master tape, so a glance at the strip
    // shows WHERE the objects are across the whole project, not just where the clips are.
    /** Thickness of one layer line. */
    private static final float MINIMAP_LAYER_LINE_DP = 1.2f;
    /** Line-to-line pitch (thickness + gap). */
    private static final float MINIMAP_LAYER_PITCH_DP = 2f;
    /** Hard cap. Past this the lines stop being readable and start eating the timeline. */
    private static final int MINIMAP_MAX_LAYER_LINES = 12;
    private static final float TRACK_HEIGHT_DP = 56f;
    /** Sprocket-rail thickness reserved OUTSIDE the film content (top + bottom of the master band),
     *  so the perforations FRAME the thumbnails instead of covering them (JoyRaptor 2026-07-07). */
    private static final float FILM_RAIL_DP = 7f;
    private static final float SEGMENT_GAP_DP = 4f;
    private static final float SEGMENT_CORNER_DP = 6f;

    private static final float HANDLE_WIDTH_DP = 11f; // a touch narrower (JoyRaptor 2026-07-06); hit-zone stays generous (+touchSlop)
    private static final float HANDLE_OVERHANG_DP = 4f;
    private static final float HANDLE_NOTCH_WIDTH_DP = 3f;
    private static final float HANDLE_NOTCH_HEIGHT_DP = 18f;

    /**
     * The playhead line.
     *
     * <p>2dp, down from 2.5dp. JoyRaptor asked how wide it was and pointed at KineMaster's,
     * which is thinner — thinness is most of what makes a cursor read as PRECISE rather
     * than as a marker laid over the work.
     *
     * <p>It does not go thinner than 2dp, and the reason is the slice dashes. Those are 1
     * physical pixel and they sit ON the playhead; at 1.5dp on a 3x screen the line is 4.5
     * physical pixels, so a 1px dash would leave under 2px of pink either side of it and
     * the two would smear into one muddy stripe. At 2dp there are 6 physical pixels with
     * the dash centred, which keeps the pink readable at the dash edges AND in the gaps —
     * and that layering is the whole point: the playhead wearing a warning, not a second
     * cursor drawn beside it.
     */
    /**
     * Studio Final §02 draws {@code .ph} at 1.5px. This was 2.
     *
     * <p>It was left open in the checklist — "KineMaster's is thinner, open question whether
     * to reduce" — and the drawing had already answered it. A playhead is a hairline that
     * says exactly where you are; half a point of extra width is half a point of ambiguity
     * about which frame it is over.
     */
    private static final float PLAYHEAD_WIDTH_DP = 1.5f;
    private static final float PLAYHEAD_CIRCLE_DP = 6f;
    private static final float BORDER_WIDTH_DP = 2f;

    private static final float LABEL_SIZE_DP = 10f;
    private static final float RULER_TEXT_SIZE_DP = 9f;
    private static final float RULER_TICK_HEIGHT_DP = 5f;
    private static final float TOUCH_SLOP_DP = 22f;

    // ── Audio track layout constants (dp) ─────────────────────────────
    private static final float AUDIO_TRACK_HEIGHT_DP = 40f;
    private static final float AUDIO_TRACK_GAP_DP    = 6f;
    /** Vertical gap between stacked audio lanes (overlapping clips get their own row). */
    private static final float AUDIO_LANE_GAP_DP     = 3f;

    // Overlay/caption "layer" rows drawn below the tracks (visualisation).
    private static final float LAYER_ROW_HEIGHT_DP = 16f;
    private static final float LAYER_ROW_GAP_DP    = 4f;
    private static final float LAYER_TOP_GAP_DP    = 6f;
    private static final float BASE_TIMELINE_DP    = 140f;  // preserve existing height
    private static final float AUDIO_CORNER_DP       = 6f;
    private static final float AUDIO_WAVEFORM_BAR_GAP_DP = 0.5f;

    // ── Colors ───────────────────────────────────────────────────────
    private static final int COLOR_RULER_BG      = Studio.PANEL;
    private static final int COLOR_RULER_TEXT     = Studio.INK_FAINT;
    private static final int COLOR_RULER_TICK     = Studio.OFF;
    private static final int COLOR_TRACK_BG       = Studio.SURFACE; // deep "film black" — the main track reads as the anchor (Slice E delineation)
    /** Near-black film-border rails framing the master track's top & bottom edges. */
    // ── THE FILM, NOW THAT THE GROUND IS BLACK ──────────────────────────────────
    // JoyRaptor: "Now that background is closer to black we will still need the film to
    // read as film. Make sure the sprocket holes read like holes to whatever the
    // background is and that the film part shape is readable."
    //
    // The old values were built when the timeline sat on #16161B. Against the new
    // near-black ground the rail at #000000 measured 1.07:1 -- the film's outer edge was
    // GONE, and the holes at #2C2C35 were LIGHTER than the film they were punched in, so
    // they read as studs rather than perforations. The relationship was inverted.
    //
    // Three values, each with one job:
    //   RAIL     the film base. One step up from the ground so the band exists at all.
    //   SPROCKET the hole. DARKER than the rail, because a hole shows the dark behind it.
    //   EDGE     a hairline on the film's OUTER cut edge. This is the element that
    //            actually draws the shape: at 1.98:1 over the ground it is the highest
    //            contrast in the strip, and an edge is detectable at ratios where a
    //            whole FIELD of the same colour would not be.
    //
    // All three are near-black on purpose. Contrast ratio is a poor instrument down here
    // -- the +0.05 flare term dominates and everything scores between 1.0 and 1.3 -- so
    // the shape is carried by the hairline and by the RHYTHM of the perforations, not by
    // making the film lighter until the numbers look respectable. A grey filmstrip would
    // pass a contrast check and look nothing like film.
    private static final int COLOR_FILM_RAIL      = Studio.RAISED;
    private static final int COLOR_FILM_EDGE      = Studio.OFF;
    /** Highlight on a perforation's lower lip -- the film's thickness catching light. */
    private static final int COLOR_FILM_HOLE_LIP  = 0x3DFFFFFF;
    /** Sprocket perforations punched along the film rails. */
    private static final int COLOR_FILM_SPROCKET  = Studio.GROUND;
    private static final int COLOR_SEGMENT        = Studio.LINE;
    private static final int COLOR_SEGMENT_SEL    = Studio.LINE; // SELECTED fill — cyan family (was green Studio.OFF)
    /** SELECTED = cyan, everywhere in the app. A state is a RING; an object colour is a FILL. */
    private static final int COLOR_BORDER_SEL     = Studio.ARMED;
    /** Selected-transition outline — blue, so a selected TRANSITION is still distinct from a
     *  selected CLIP now that clip selection is cyan. Revisit if the two ever read alike. */
    private static final int COLOR_TRANSITION_SEL = Studio.VIDEO;
    private static final int COLOR_HANDLE         = Studio.ARMED; // trim handles follow the selection colour
    private static final int COLOR_HANDLE_NOTCH   = 0xBB33333C;
    /**
     * NEUTRAL playhead = LIVE pink, matching Sprite Lab's "this is what is showing now".
     * This is only the fallback: {@link #resolvePlayheadContextColor()} still tints the head
     * by the SELECTED OBJECT's kind, which is more informative and predates this change.
     * Do not flatten that away.
     */
    /**
     * The playhead's own colour: the Capture magenta.
     *
     * <p>JoyRaptor: "shouldnt we have it be hot pink capture color?" — and the body draws
     * the full Capture gradient #FA3D5D -> #FF008C vertically; this is the end stop, used
     * for the time chip and anywhere a single value is needed.
     *
     * <p>DO NOT let a colour sweep touch this. It was #F43F8E, and a pass that folded rare
     * near-duplicates onto tokens measured it a short hop from the new DANGER red and
     * snapped it — which made "playing" and "about to destroy something" the same colour
     * for about ten minutes. LIVE and DANGER must never converge: one is a normal state you
     * want to see, the other means something is being lost.
     */
    private static final int COLOR_PLAYHEAD       = Studio.LIVE;
    private static final int COLOR_LABEL          = 0xBBFFFFFF;
    private static final int COLOR_DRAG_GHOST     = 0x6622D3EE;
    private static final int COLOR_AUDIO_BG       = Studio.LINE;
    private static final int COLOR_AUDIO_BG_SEL   = Studio.LINE; // selected audio fill — cyan family
    private static final int COLOR_AUDIO_WAVE     = Studio.AUDIO;
    private static final int COLOR_AUDIO_WAVE_MUTED = Studio.INK_OFF;
    private static final int COLOR_AUDIO_WAVE_DIM = Studio.alpha(Studio.AUDIO, 0x40); // mirror half
    private static final int COLOR_AUDIO_CENTERLINE = Studio.alpha(Studio.AUDIO, 0x33);
    private static final int COLOR_AUDIO_LABEL    = 0xBBFFFFFF;
    /**
     * The audio band's ground.
     *
     * <p>Was #16161B — a full step lighter than the lane stripes above it, which JoyRaptor
     * spotted immediately: "audio tracks are a lighter grey then the dark alternating lines
     * in the other lanes, looks odd". It read as a different SURFACE rather than as more of
     * the same timeline, so the band looked pasted on.
     *
     * <p>It now sits on the same two-value alternation as every other lane.
     */
    private static final int COLOR_AUDIO_TRACK_BG = Studio.LANE_B;

    // ── KineMaster-class playhead lane (JoyRaptor 2026-07-19) ─────────────────
    // The per-kind playhead tints used to be re-declared here as a hand-kept mirror of
    // LayerRowRenderer's private per-item constants ("keep in lockstep"). They had already
    // fallen out of lockstep — neither copy had an IMAGE case. Both now read the one table in
    // ObjectPalette (F-COLOR), so there is nothing left to keep in step.
    /** Distinct tint while a trim drag is active (amber, unused by any row family). */
    private static final int COLOR_PLAYHEAD_TRIM = Studio.CAREFUL;
    /** Bookmark diamond glyph on the ruler. */
    private static final int COLOR_BOOKMARK   = Studio.CAREFUL;

    private static final float CHIP_TEXT_DP        = 10f;
    private static final float CHIP_TEXT_SCRUB_DP  = 12f;
    private static final float CHIP_PAD_H_DP        = 9f;
    private static final float CHIP_PAD_V_DP        = 3f;
    private static final float CHIP_CORNER_DP       = 4f;
    private static final float GUIDE_DASH_DP        = 4f;
    private static final float BOOKMARK_SIZE_DP     = 6f;
    /** Tap/long-press hit tolerance for a bookmark, in dp (converted to ms per zoom). */
    private static final float BOOKMARK_HIT_DP      = 12f;

    // ── Paints ───────────────────────────────────────────────────────
    private final Paint rulerBgPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerTextPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerTickPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackBgPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Master-track "filmstrip" frame (rails + sprocket holes) — Slice E delineation. */
    private final Paint filmPaint          = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segmentPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** FADE_KNOBS §2.5 spine knob + veil paints. */
    private final Paint spineKnobFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spineKnobStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spineKnobVeilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path spineFadeVeilPath = new android.graphics.Path();
    private final Paint spineStemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spineDiagPaintImpl = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spineDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleNotchPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint         = new Paint(Paint.ANTI_ALIAS_FLAG);
    // KineMaster playhead lane paints (JoyRaptor 2026-07-19)
    private final Paint chipBgPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path chipPath = new android.graphics.Path();
    private final Paint chipBorderPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipTextPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Dashed guide line paint (top/bottom row-band + vertical trim edge). */
    private final Paint guidePaint          = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bookmarkPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  bookmarkPath         = new Path();
    private final RectF chipRect             = new RectF();
    private final Paint dragGhostPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trimOverlayPaint    = new Paint();
    private final Paint trimRecoverPaint    = new Paint();
    // Slide freeze-zone markers + frozen-zone tint (JoyRaptor 2026-07-16)
    private final Paint freezeMarkerPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint freezeZonePaint     = new Paint();
    // Caption text-animation in/out zone carets + zone tint (SPEC_TEXT_ANIMATION). Amber rather
    // than the freeze markers' cyan: the two never appear on the same clip, but they sit in the
    // same place on the tape, so a glance has to say WHICH kind of zone this is.
    private final Paint textAnimMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textAnimZonePaint   = new Paint();
    // Text-box MOTION-RANGE window carrots + tint (SPEC_TEXT_DRAWER follow-up, 2026-08-08).
    // Purple, the app's keyframe accent — this is where the entrance/exit tape RUNS, distinct
    // from the amber in/out zone carets, which sit inside this window.
    private final Paint motionRangeMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * Shared white outline for the two animation markers (JoyRaptor, 2026-08-18: "rounded corners
     * and ... a white stroke around them, maybe one pixel wide").
     *
     * <p>ONE paint for both so the amber entrance caret and the purple motion marker cannot drift
     * apart in weight — they sit on the same row, often within a few pixels of each other, and a
     * mismatched outline reads as a rendering bug rather than as two different controls.
     *
     * <p>The stroke earns its place beyond taste: these markers are drawn over an arbitrary
     * filmstrip, and amber-on-bright-frame is exactly the case where a fill alone stops having an
     * edge. The outline is what guarantees a silhouette on any footage.</p>
     */
    private final Paint animMarkerOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint motionRangeZonePaint   = new Paint();

    // Audio track paints
    private final Paint audioTrackBgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioClipPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioWavePaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioWaveMirrorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioCenterlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioLabelPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioBorderPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Blue volume-automation envelope (the "rubber-band" line drawn keyframe-to-keyframe)
    private final Paint volEnvLinePaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint volEnvDotPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    // White clip-opacity envelope
    private final Paint opacityEnvLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint opacityEnvDotPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Light-blue volume envelope (for video clips carrying audio).
    private final Paint volumeEnvLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint volumeEnvDotPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    // MISSING state overlay
    private final Paint missingBgPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint missingTextPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Pixel dimensions ─────────────────────────────────────────────
    private float density;
    private float dpPerSecondPx, minSegmentPx, edgePaddingPx;
    private float rulerHeightPx, trackHeightPx, segmentGapPx, segmentCornerPx;
    private float handleWidthPx, handleOverhangPx, handleNotchWidthPx, handleNotchHeightPx;
    private float playheadWidthPx, playheadCirclePx, borderWidthPx;
    private float touchSlopPx, rulerTickHeightPx;
    // KineMaster playhead lane pixel dims (JoyRaptor 2026-07-19)
    private float chipTextPx, chipTextScrubPx, chipPadHPx, chipPadVPx, chipCornerPx;
    private float guideDashPx, bookmarkSizePx, bookmarkHitPx;

    // ── KineMaster playhead lane state (JoyRaptor 2026-07-19) ─────────────────
    /** True while the user is actively scrubbing the playhead (chip grows/bolds). */
    private boolean playheadScrubbing = false;
    /** Animated (current) playhead+chip-border colour, and its target. */
    @Nullable private android.graphics.LinearGradient playheadGrad;
    private float playheadGradTop = Float.NaN, playheadGradBot = Float.NaN;
    private final Paint slicePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int playheadColorCurrent = COLOR_PLAYHEAD;
    private int playheadColorTarget  = COLOR_PLAYHEAD;
    @Nullable private android.animation.ValueAnimator playheadColorAnim;
    private final android.animation.ArgbEvaluator argbEval = new android.animation.ArgbEvaluator();
    /** Cache so a static frame does no String.format (perf culture). */
    private long chipCachedMs = Long.MIN_VALUE;
    private String chipCachedText = "";
    // Bookmarks (view working copy; survives setTimeline). Sorted ascending.
    private final List<Long> bookmarksMs = new ArrayList<>();
    @Nullable private BookmarkListener bookmarkListener;
    // Ruler-area-only long-press/tap detector for bookmarks (kept out of the main
    // scrub/drag/pickup arbitration — a scroll cancels it via onScroll).
    private boolean rulerTouchActive = false;
    private boolean rulerLongPressFired = false;
    private float rulerDownContentX = 0f;
    private final Runnable rulerLongPressRunnable = new Runnable() {
        @Override public void run() {
            if (!rulerTouchActive) return;
            rulerLongPressFired = true;
            long t = Math.max(0, Math.min(xToTime(rulerDownContentX), getTimelineEndMs()));
            toggleBookmarkNear(t);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
    };
    private float audioTrackHeightPx, audioTrackGapPx, audioCornerPx, audioWaveBarGapPx;
    private float audioLaneGapPx;
    /** Number of stacked audio lanes (≥1) and the lane each audio clip sits in. */
    private int audioLaneCount = 1;
    private int[] audioClipLanes = new int[0];
    private float minimapHeightPx;
    /**
     * Height of the F-MINIMAP per-layer line band sitting above the master tape. ADAPTIVE:
     * zero when the project has no floating/audio layers, so a plain single-track project
     * measures and draws exactly as it did before this feature existed.
     */
    private float minimapLayerBandPx = 0f;
    private boolean minimapDragging = false;
    private final Paint minimapBlockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minimapViewportPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minimapViewportBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Per-source audio waveforms drawn along the bottom of video segments
    private final Map<Integer, int[]> sourceWaveforms = new HashMap<>();
    private final Set<Integer> waveformsLoading = new HashSet<>();
    private com.fadcam.ui.faditor.util.AudioExtractor waveformExtractor;
    private final Paint segmentWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Transcript text drawn below each segment (pinned to word timestamps)
    private final Paint transcriptTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint transcriptHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, com.fadcam.ui.faditor.transcript.Transcript> segmentTranscripts = new HashMap<>();
    private final Map<Integer, com.fadcam.ui.faditor.transcript.Transcript> audioTranscripts = new HashMap<>();
    private long currentPlayheadSourceMs = -1;
    private int transcriptClipIndex = -1;
    private int audioTranscriptClipIndex = -1;
    private long audioCurrentPlayheadMs = -1;

    // Transitions rendered as colored bands between/over clip edges
    private final List<com.fadcam.ui.faditor.model.Transition> transitions = new ArrayList<>();
    private final Paint transitionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint transitionLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint transitionHelperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int selectedTransitionIndex = -1;
    private int transitionDragIndex = -1;
    private long transitionDragStartDurationMs = 0;
    private float transitionDragStartX = 0f;
    private float transitionDragX = 0f;
    // Set by transitionDurationFromDragX() on every call: whether the requested duration
    // exceeded the neighbor-clip seam limit (maxSpan) and had to be clamped down, and what
    // that limit was. The activity reads these right after onTransitionDurationFinished's
    // duration is computed to decide whether to surface a "limited by clip length" toast.
    private boolean lastTransitionDragWasSpanClamped = false;
    private long lastTransitionDragMaxSpanMs = 0;

    // Overlay/caption "layer" rows shown below the tracks (read-only view of
    // each overlay's time-range + keyframes so the user can SEE them).
    private final List<TextOverlayItem> overlays = new ArrayList<>();
    // Visualizer (waveform) overlays shown as their own read-only layer rows below the text rows.
    private final List<com.fadcam.ui.faditor.model.WaveformOverlayInstance> waveformLayers = new ArrayList<>();
    // Caption spans (timeline {startMs,endMs} per captioned clip) shown as their own layer rows.
    private final List<long[]> captionSpans = new ArrayList<>();

    // ── M6 multi-row Track UI (extract-on-touch: all logic in LayerRowRenderer) ──
    private com.fadcam.ui.faditor.layers.LayerRowRenderer layerRowRenderer;

    /**
     * The row renderer, for the host's undo-recording. It owns the ONE generic fade
     * accessor/mutator pair that every fade host routes through, and the activity had no
     * way to reach it except reflection.
     */
    @Nullable
    public com.fadcam.ui.faditor.layers.LayerRowRenderer getLayerRowRenderer() { return layerRowRenderer; }
    /** W2: zoom-tiered HD waveform data source for the audio rows (see TimelineWaveformCache). */
    private com.fadcam.ui.faditor.waveform.TimelineWaveformCache timelineWaveformCache;
    /** AV2: quad-band tape waveform — shared style + shaped-data cache for the audio rows. */
    private com.fadcam.ui.faditor.waveform.TapeWaveformStyle tapeStyle;
    private com.fadcam.ui.faditor.waveform.BandedTimelineWaveformCache tapeWaveformCache;

    // ── Clip-audio drawer (v2 live-follow, JoyRaptor 2026-07-11) ────────────────────
    // Double-tap a MASTER clip → its embedded audio slides down as a quad-band-tape
    // "shelf" below the strip; double-tap again slides it back under. The drawer is
    // PINNED to its clip: geometry derives per-frame from the clip's current segRect,
    // so scroll / trim / reorder / speed changes are followed live with no extra
    // bookkeeping. Cutting splits video+audio together by construction (the drawer
    // just displays the clip's own audio). State is session-level UI state.
    /** Drawer height — matches the original (legacy) audio track height in the app. */
    /** 40→45dp (JoyRaptor 2026-07-14): ~12% taller so the tape keeps its old height while a black
     *  word band (below) is carved out — words sit under the waveform, not on its centerline. */
    private static final float CLIP_AUDIO_DRAWER_HEIGHT_DP = 45f;
    /** Bottom slice of the drawer left as dark padding for the transcript words. */
    private static final float CLIP_DRAWER_WORD_BAND_DP = 12f;
    private static final long CLIP_DRAWER_ANIM_MS = 220;
    /** Max UP-to-UP ms for two master-segment taps to read as a double-tap (mirrors
     *  LayerGestureController.DOUBLE_TAP_WINDOW_MS). */
    private static final long MASTER_DOUBLE_TAP_WINDOW_MS = 320;

    /**
     * When the touch we are handling actually HAPPENED, from {@link MotionEvent#getEventTime()}.
     *
     * <p>JoyRaptor, 2026-09-13: <i>"double tapping on main spine video that has keyframed volume
     * changes wasn't triggering the drawer, I had to double tap an adjacent video."</i>
     *
     * <p>The double-tap window was measured with {@code SystemClock.uptimeMillis()} — i.e. when we
     * got ROUND to handling the lift, not when the finger left the glass. The first tap of the
     * pair seeks, and a seek on a 48-minute project rebuilds the spine and decodes a frame; every
     * millisecond that work blocks the UI thread is taken out of a 320ms budget the user never
     * knew they were spending. So the same two taps register on a cheap clip and miss on an
     * expensive one, which is exactly the "it works on the clip next to it" shape of the report —
     * a clip carrying a keyframed volume envelope draws strictly more than its neighbours.
     *
     * <p>{@code getEventTime()} is already in the {@code uptimeMillis} timebase, so this is a
     * drop-in that makes the window mean what it says.
     */
    private long lastTouchEventTimeMs;
    /** clipId → animated open fraction 0..1 (present only while open or animating). */
    private final java.util.Map<String, Float> clipAudioDrawerFraction = new java.util.HashMap<>();
    /** clipId → its running slide animator (cancelled + replaced on re-toggle). */
    private final java.util.Map<String, android.animation.ValueAnimator> clipAudioDrawerAnims =
            new java.util.HashMap<>();
    /** clipIds whose drawer TARGET state is open. */
    private final java.util.Set<String> clipAudioDrawerOpen = new java.util.HashSet<>();
    /**
     * SPEC_V §4 — "the audio drawer was out when the spine was collapsed, so put it back on
     * expand". PURELY VIEW STATE, and deliberately a plain field on this View: it is not in
     * {@code Timeline}, so it can reach neither {@code project.json} (ProjectStorage only ever
     * serializes the Timeline) nor the undo stack (only {@code undoManager.recordAction} calls
     * do, and nothing here records one). It dies with the editor screen, which is right — a
     * remembered shelf is a convenience within one sitting, not a property of the project.
     */
    private boolean spineCollapseReopenDrawers = false;
    /** A3 tile cache for drawer bodies (the row renderer owns its own instance). */
    private com.fadcam.ui.faditor.waveform.TapeTileCache clipDrawerTapeCache;
    private long lastMasterTapUpMs;
    private String lastMasterTapClipId;
    /** Screen-space X of the last master-segment tap (JoyRaptor 2026-07-14): the first tap's seek
     *  auto-centers the strip, sliding a SHORT clip out from under a stationary finger — so the
     *  double-tap must be detected by "same screen spot, quick succession", not by the second
     *  tap resolving to the same (now-shifted) segment. */
    private float lastMasterTapScreenX;
    private final List<com.fadcam.ui.faditor.layers.Track> layerTracks = new ArrayList<>();
    private final List<com.fadcam.ui.faditor.layers.Track> audioLayerTracks = new ArrayList<>();
    private OnTrackHeaderActionListener trackHeaderActionListener;
    /** True while a drag that started inside the M6 row region is in progress (vertical scroll). */
    private boolean m6RowDragActive = false;
    private float m6RowLastY = 0f;
    /**
     * True from DOWN when a touch lands inside the row region but misses every item/
     * header (empty row space, or ANY touch — including a would-be item hit — on a
     * locked/hidden/collapsed row body, since {@link com.fadcam.ui.faditor.layers.LayerRowRenderer#hitTestItem}
     * returns {@code null} for those rows). Axis is not yet decided: the row band must
     * support BOTH vertical row-scroll (M6) and horizontal timeline scrub pass-through
     * (surface-overlap fix) from the same empty-space touch, so the decision is deferred
     * to the first {@code onMove} past touch-slop, exactly like the segment/audio
     * long-press-vs-drag disambiguation elsewhere in this class. Cleared once the axis
     * is resolved (flips to {@link #m6RowDragActive} or {@link #m6RowScrubPassthroughActive}).
     */
    private boolean m6RowPendingAxisDecision = false;
    /** AV3: a collapsed (thin) AUDIO row whose body was tapped — a pure tap expands it. */
    private com.fadcam.ui.faditor.layers.Track pendingCollapsedAudioTrack;
    private float m6RowPendingDownX = 0f, m6RowPendingLastX = 0f, m6RowPendingDownY = 0f;
    /**
     * True once a row-band touch has been resolved as a horizontal scrub (dominant-axis
     * winner over vertical row-scroll). While true, {@code onMove} drives the SAME
     * {@link #updatePlayheadFromX} primitive {@link GestureListener#onScroll} uses
     * elsewhere, so scrubbing over a row feels identical to scrubbing anywhere else on
     * the timeline (the user's reported bug: "I can't scrub through the timeline" on
     * Layer 1 / the extracted-audio row).
     */
    private boolean m6RowScrubPassthroughActive = false;

    // ── M7 floating-item gestures (extract-on-touch: all logic in LayerGestureController) ──
    private com.fadcam.ui.faditor.layers.LayerGestureController layerGestureController;
    /**
     * True while an ARMED move/trim gesture on a row ITEM is in progress: a trim (armed on
     * DOWN over an edge handle) or a PICKED-UP move (after the long-press). Set only once
     * the item is actually being manipulated — a plain body touch starts in
     * {@link #m7ItemPendingDown} instead, so a swipe scrubs rather than nudging the item.
     */
    private boolean m7ItemGestureActive = false;
    /**
     * A2 minimap drag-nav (dragux_v3, user 2026-07-04): true while a picked-up item
     * drag has slid INTO the minimap band — the view navigates, the item's mapping is
     * suppressed (it stays put), and the drag resumes when the finger leaves the band.
     */
    private boolean itemDragMinimapNav = false;
    /**
     * True from a body DOWN on a row item until the touch resolves (PLAN TARGET CONTRACT).
     * The item is SELECTED, but the gesture is undecided: a horizontal-dominant move →
     * SCRUB (pass through, item not moved); the pickup timer firing with low movement →
     * PICK-UP (flips to {@link #m7ItemGestureActive}, item follows finger); a
     * vertical-dominant move before pickup → row-scroll; an UP within slop → TAP (select
     * only). Mirrors the reorder/audio long-press-vs-drag disambiguation already in this
     * class, but with scrub as the horizontal escape hatch.
     */
    private boolean m7ItemPendingDown = false;
    private float m7PendingDownX = 0f, m7PendingDownY = 0f;
    /** Long-press window that promotes a PENDING body touch to a pick-up-for-move. */
    private static final long ITEM_PICKUP_MS = 450;
    private com.fadcam.ui.faditor.layers.LayerGestureController.Callback layerGestureCallback;
    private final Runnable itemPickupRunnable = new Runnable() {
        @Override
        public void run() {
            if (!m7ItemPendingDown || layerGestureController == null || isScaling) return;
            if (layerGestureController.beginPickup()) {
                m7ItemPendingDown = false;
                m7ItemGestureActive = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
            }
        }
    };

    public void setLayerGestureCallback(
            @Nullable com.fadcam.ui.faditor.layers.LayerGestureController.Callback cb) {
        this.layerGestureCallback = cb;
        if (layerRowRenderer != null) {
            layerGestureController = new com.fadcam.ui.faditor.layers.LayerGestureController(
                    layerRowRenderer, cb != null ? cb : NOOP_GESTURE_CALLBACK);
            // dragux_v3 A4: the ONE snap tunable — gentle ~8dp (≈1–2mm), was 48raw px.
            layerGestureController.setSnapRadiusPx(
                    8f * getResources().getDisplayMetrics().density);
            // Layer-polish #7: the post-pickup move slop was a hardcoded 4 RAW px (~1.5dp
            // here, tighter on higher-DPI phones) — smaller than hold-jitter, so the
            // hold→release-in-place object menu opened only sometimes. dp-scale it to the
            // conventional 8dp touch slop so a hold that doesn't really move opens the menu.
            layerGestureController.setMoveSlopPx(
                    8f * getResources().getDisplayMetrics().density);
            // Re-apply: this method REPLACES the controller, so a provider set earlier would be
            // silently dropped and §2a resizing would fall back to RELATIVE-with-no-fps-update
            // — a resize that looks like it worked and plays at the wrong speed.
            layerGestureController.setSpriteFpsProvider(spriteFpsProvider);
        }
    }

    @Nullable private com.fadcam.ui.faditor.layers.LayerGestureController.SpriteFpsProvider
            spriteFpsProvider;

    /** SPEC_IMAGE_SEQUENCE §2a: model lookups the resize gesture needs. */
    public void setSpriteFpsProvider(
            @Nullable com.fadcam.ui.faditor.layers.LayerGestureController.SpriteFpsProvider p) {
        this.spriteFpsProvider = p;
        if (layerGestureController != null) layerGestureController.setSpriteFpsProvider(p);
    }

    /**
     * Exposes the M7 gesture controller so the activity's {@code Callback} can read the
     * before-gesture snapshot captured at drag-start (PLAN Part 7 row M7 undo contract).
     */
    @NonNull
    public com.fadcam.ui.faditor.layers.LayerGestureController getLayerGestureController() {
        return layerGestureController;
    }

    /**
     * True if {@code track} came from the floating {@code layerTracks} list (TEXT/
     * STICKER/IMAGE/VIDEO) rather than {@code audioLayerTracks} (M10; PLAN Part 7 row
     * M10 scope 2 — decides which {@code TrackKind} a "drop to new layer" gesture
     * should create). Delegates to {@link com.fadcam.ui.faditor.layers.LayerRowRenderer}
     * (the extract-on-touch owner of row geometry), falling back to a direct list
     * lookup so it's correct even outside an active drag (the renderer's row list is
     * only populated during {@code layout}, but that runs every frame this view is
     * visible, so in practice it is always fresh by the time a gesture finishes).
     */
    public boolean isLayerTrackFloatingBand(@NonNull com.fadcam.ui.faditor.layers.Track track) {
        for (com.fadcam.ui.faditor.layers.Track t : layerTracks) {
            if (t.getId().equals(track.getId())) return true;
        }
        for (com.fadcam.ui.faditor.layers.Track t : audioLayerTracks) {
            if (t.getId().equals(track.getId())) return false;
        }
        return layerRowRenderer.isFloatingBandRow(track);
    }

    private static final com.fadcam.ui.faditor.layers.LayerGestureController.Callback
            NOOP_GESTURE_CALLBACK = new com.fadcam.ui.faditor.layers.LayerGestureController.Callback() {
        @Override public void onGestureFinished(
                @NonNull com.fadcam.ui.faditor.layers.TimedItem item,
                @NonNull com.fadcam.ui.faditor.layers.LayerGestureController.GestureKind kind) {}
        @Override public void onGestureLive(@NonNull com.fadcam.ui.faditor.layers.TimedItem item) {}
        @Override public void onItemDeleteRequested(
                @NonNull com.fadcam.ui.faditor.layers.Track track,
                @NonNull com.fadcam.ui.faditor.layers.TimedItem item) {}
        @Override public void onItemMovedToTrack(
                @NonNull com.fadcam.ui.faditor.layers.TimedItem item,
                @NonNull com.fadcam.ui.faditor.layers.Track fromTrack,
                @NonNull com.fadcam.ui.faditor.layers.Track toTrack) {}
        @Override public void onItemDroppedOnNewLayer(
                @NonNull com.fadcam.ui.faditor.layers.TimedItem item,
                @NonNull com.fadcam.ui.faditor.layers.Track fromTrack, int insertionIndex) {}
    };

    /** Callback for a tap on a track row-header icon (M6; glue lives in FaditorEditorActivity). */
    public interface OnTrackHeaderActionListener {
        void onTrackHeaderAction(@NonNull com.fadcam.ui.faditor.layers.Track track,
                                  @NonNull com.fadcam.ui.faditor.layers.LayerRowRenderer.HitZone zone);
    }

    public void setOnTrackHeaderActionListener(OnTrackHeaderActionListener l) {
        this.trackHeaderActionListener = l;
    }

    /**
     * PHASE-P P1: long-press on a layer/audio row HEADER (the pinned left icon column)
     * opens the track-management menu (move up / move down / solo / delete). The
     * MASTER row is not one of the renderer's rows, so it reaches this listener through
     * its OWN hit-test below (G22) rather than {@code hitTestHeader}.
     * {@code viewX}/{@code viewY} are raw view-space coords for menu anchoring.
     */
    public interface OnTrackHeaderLongPressListener {
        void onTrackHeaderLongPress(@NonNull com.fadcam.ui.faditor.layers.Track track,
                                     float viewX, float viewY);
    }

    private OnTrackHeaderLongPressListener trackHeaderLongPressListener;

    public void setOnTrackHeaderLongPressListener(OnTrackHeaderLongPressListener l) {
        this.trackHeaderLongPressListener = l;
    }

    // ── PHASE-P P1: pending header touch (tap deferred to UP so a long-press can win) ──
    private com.fadcam.ui.faditor.layers.LayerRowRenderer.HeaderHit pendingHeaderHit;
    private float headerDownRawX, headerDownRawY;
    private boolean headerLongPressFired;
    private final Runnable headerLongPressRunnable = new Runnable() {
        @Override public void run() {
            if (pendingHeaderHit == null) return;
            headerLongPressFired = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (trackHeaderLongPressListener != null) {
                trackHeaderLongPressListener.onTrackHeaderLongPress(
                        pendingHeaderHit.track, headerDownRawX, headerDownRawY);
            }
        }
    };

    /** Cancel any pending header tap/long-press (shared by move-past-slop, UP, and resets). */
    private void cancelPendingHeaderTouch() {
        longPressHandler.removeCallbacks(headerLongPressRunnable);
        pendingHeaderHit = null;
        headerLongPressFired = false;
    }

    // ── G22: the MASTER row's door into the track-header menu ──────────────────────
    // The master band is drawn by this view, not LayerRowRenderer, so hitTestHeader can
    // never see it and no UI path could route a long-press to the master row — the solo
    // logic in the host was ready and permanently unreachable for it ("hear only the
    // video" could not be asked for). A stationary hold on the master band that is NOT on
    // a clip (segment hits keep their own pickup long-press) fires the same listener the
    // lane headers use, with the live Timeline's master Track.
    private final Runnable masterHeaderLongPressRunnable = new Runnable() {
        @Override public void run() {
            masterHeaderLongPressArmed = false;
            if (liveTimeline == null) return;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (trackHeaderLongPressListener != null) {
                trackHeaderLongPressListener.onTrackHeaderLongPress(
                        liveTimeline.getMasterTrack(), downX, downY);
            }
        }
    };
    private boolean masterHeaderLongPressArmed;

    /** Cancel a pending master-row long-press (move-past-slop, second finger, pinch, UP). */
    private void cancelMasterHeaderLongPress() {
        longPressHandler.removeCallbacks(masterHeaderLongPressRunnable);
        masterHeaderLongPressArmed = false;
    }

    /**
     * Push the current Track model in for the M6 multi-row UI (PLAN Part 7, row M6).
     * Snapshots the lists (same convention as {@link #setOverlays}/{@link #setAudioClips})
     * rather than holding a live {@code Timeline} reference. A plain single-track project
     * passes two empty lists, which {@link com.fadcam.ui.faditor.layers.LayerRowRenderer}
     * renders as zero rows — no visual change (PLAN Part 7 M6 scope item 6).
     */
    /** Id of the currently-selected layer-row item, or null. Used by M12's move controls. */
    @Nullable
    public String getSelectedLayerItemId() {
        return layerGestureController != null ? layerGestureController.getSelectedItemId() : null;
    }

    public void setLayerTracks(@NonNull List<com.fadcam.ui.faditor.layers.Track> layers,
                               @NonNull List<com.fadcam.ui.faditor.layers.Track> audioTracks) {
        layerTracks.clear();
        layerTracks.addAll(layers);
        audioLayerTracks.clear();
        audioLayerTracks.addAll(audioTracks);
        // F-MINIMAP: the layer-line band is sized from these lists, so it must be recomputed
        // before the requestLayout() below — adding a text layer grows the strip.
        recomputeMinimapHeight();
        // Stale-selection guard: ops that replace item identity (split → two new ids,
        // delete, undo/redo swapping objects) re-feed through here. If the controller's
        // selected id no longer exists in EITHER band, clear it — otherwise every
        // selection-derived toolbar op (delete/split/volume…) silently falls through to
        // its legacy master-clip target while the user believes an item is selected.
        if (layerGestureController != null
                && layerGestureController.getSelectedItemId() != null
                && !layerItemIdExists(layerGestureController.getSelectedItemId())) {
            layerGestureController.clearSelection();
        }
        requestLayout();
        invalidate();
    }

    /**
     * Object time-scrubber (SPEC_OBJECT_TIME_SCRUBBER §8a) LIGHT per-frame update: nudge one
     * already-laid-out layer/audio item's drawn start WITHOUT rebuilding the Track views. The
     * fed Track views hold the live payloads, so their {@code TimedItem} snapshot of
     * {@code timelineStartMs} is updated in place and the view is invalidated — O(items), no
     * {@code getLayers()}/{@code setLayerTracks} rebuild (which is why it can run every frame
     * without the ANR risk that a full sync would carry on a long project). Returns false if
     * the item isn't currently in either band (caller should fall back to a full sync).
     */
    public boolean updateLayerItemStartLight(@NonNull String itemId, long newStartMs) {
        boolean found = nudgeItemStartIn(layerTracks, itemId, newStartMs)
                | nudgeItemStartIn(audioLayerTracks, itemId, newStartMs);
        if (found) invalidate();
        return found;
    }

    private static boolean nudgeItemStartIn(
            @NonNull List<com.fadcam.ui.faditor.layers.Track> tracks,
            @NonNull String itemId, long newStartMs) {
        boolean any = false;
        for (com.fadcam.ui.faditor.layers.Track t : tracks) {
            for (com.fadcam.ui.faditor.layers.TimedItem it : t.getItems()) {
                if (it.getId().equals(itemId)) { it.setTimelineStartMs(newStartMs); any = true; }
            }
        }
        return any;
    }

    /** Fraction of the viewport kept as a lookahead dead-zone on each side while following a
     *  time-scrub. Tracking kicks in when the scrubbed point crosses this margin — BEFORE the
     *  edge — so the user can see what the object is sliding toward. Tunable feel constant. */
    private static final float SCRUB_FOLLOW_MARGIN_FRAC = 0.25f;

    /**
     * Object time-scrubber (user feedback 2026-07-27): while the shuttle moves the object, keep
     * that point on-screen by panning the timeline so it stays inside a centered dead-zone —
     * so the object doesn't run off the edge and you can see it approach neighbours. Called each
     * frame from the scrub's onPreview; a direct set (not animated) because the shuttle already
     * advances smoothly per frame. Clamped to the same bounds as every other pan.
     */
    public void followScrubTimeMs(long ms) {
        float w = getWidth();
        if (w <= 0f) return;
        float contentX = timeToX(ms);
        float screenX = contentX - scrollOffsetPx;
        float margin = w * SCRUB_FOLLOW_MARGIN_FRAC;
        float target = scrollOffsetPx;
        if (screenX > w - margin) {
            target = contentX - (w - margin);   // moving right: hold it at the right dead-zone
        } else if (screenX < margin) {
            target = contentX - margin;          // moving left: hold it at the left dead-zone
        }
        float centerX = w / 2f;
        float minScroll = edgePaddingPx - centerX;
        float maxScroll = timeToX(getTimelineEndMs()) - centerX;
        target = Math.max(minScroll, Math.min(target, maxScroll));
        if (target != scrollOffsetPx) {
            scrollOffsetPx = target;
            invalidate();
        }
    }

    /** True if a fed row item with this id exists in either band (floating layers + audio). */
    private boolean layerItemIdExists(@NonNull String id) {
        for (List<com.fadcam.ui.faditor.layers.Track> band
                : java.util.Arrays.asList(layerTracks, audioLayerTracks)) {
            for (com.fadcam.ui.faditor.layers.Track t : band) {
                for (com.fadcam.ui.faditor.layers.TimedItem item : t.getItems()) {
                    if (id.equals(item.getId())) return true;
                }
            }
        }
        return false;
    }

    /**
     * Programmatically select a row item by id (both bands searched). Used after ops
     * that replace the selected item's identity (audio split keeps the left half
     * selected, mirroring the master-split behavior). No-op if the id isn't fed yet —
     * callers must sync the overlay rows first.
     */
    public void selectLayerItemById(@Nullable String id) {
        if (layerGestureController == null) return;
        if (id == null) {
            layerGestureController.clearSelection();
            return;
        }
        for (List<com.fadcam.ui.faditor.layers.Track> band
                : java.util.Arrays.asList(layerTracks, audioLayerTracks)) {
            for (com.fadcam.ui.faditor.layers.Track t : band) {
                for (com.fadcam.ui.faditor.layers.TimedItem item : t.getItems()) {
                    if (id.equals(item.getId())) {
                        layerGestureController.setSelectedItem(t, item);
                        invalidate();
                        return;
                    }
                }
            }
        }
    }

    /**
     * Scroll the floating layer band so the row hosting {@code itemId} is visible
     * (preview-tap reveals the object's layer, JoyRaptor 2026-07-17). Safe no-op when the
     * renderer hasn't laid out yet or the item lives in an always-visible band.
     */
    public void revealLayerRowForItem(@Nullable String itemId) {
        if (itemId == null || layerRowRenderer == null) return;
        if (!layerRowRenderer.hasRowForItem(itemId)) {
            // SPEC_U §3: an object that was just ADDED is selected before the band has
            // re-laid-out, so there is no row to reveal yet. Remember it and retry on the
            // far side of the next draw (see the tail of onDraw) instead of dropping it —
            // that silent drop is exactly why a new layer's lane never came into view.
            pendingRevealItemId = itemId;
            requestLayout();
            invalidate();
            return;
        }
        float target = layerRowRenderer.revealScrollTargetFor(itemId);
        if (Float.isNaN(target)) return;   // already on screen — minimum movement means none
        animateBandScrollTo(target);
    }

    /**
     * SPEC_U §2 — "animate briefly rather than jumping". Short decelerating slide of the
     * floating band's own scroll. Any new reveal, and any touch on the timeline, cancels the
     * one in flight so the animation can never fight the user's finger.
     */
    private void animateBandScrollTo(float targetPx) {
        cancelBandRevealAnim();
        float from = layerRowRenderer.getScrollOffsetPx();
        if (Math.abs(targetPx - from) < 0.5f) return;
        bandRevealAnim = android.animation.ValueAnimator.ofFloat(from, targetPx);
        bandRevealAnim.setDuration(BAND_REVEAL_ANIM_MS);
        bandRevealAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        bandRevealAnim.addUpdateListener(a -> {
            layerRowRenderer.setScrollOffsetPx((Float) a.getAnimatedValue());
            invalidate();
        });
        bandRevealAnim.start();
    }

    /** SPEC_U §2 — stop a reveal slide (a touch, or a newer reveal, wins). */
    private void cancelBandRevealAnim() {
        if (bandRevealAnim != null) { bandRevealAnim.cancel(); bandRevealAnim = null; }
    }

    /** G9c: linked-member ids for the renderer's chain badge (fed by syncTimelineOverlays). */
    public void setLinkedItemIds(@NonNull java.util.Set<String> ids) {
        if (layerRowRenderer != null) layerRowRenderer.setLinkedItemIds(ids);
    }

    // ── SPEC_U §2/§3 — reveal-the-lane state ─────────────────────────
    /** Short enough to read as "it moved there", long enough not to be a jump. */
    private static final long BAND_REVEAL_ANIM_MS = 180L;
    @Nullable private android.animation.ValueAnimator bandRevealAnim;
    /** An item asked to be revealed before its row existed; retried after the next draw. */
    @Nullable private String pendingRevealItemId;

    // ── State ────────────────────────────────────────────────────────
    private final List<SegmentData> segments = new ArrayList<>();
    private final List<RectF> segRects = new ArrayList<>();
    private int selectedIndex = -1;  // UI selection (-1 = none selected)
    /**
     * SPEC_N §5 — true only while {@link #selectedIndex} was set by a DELIBERATE TAP on that
     * spine segment. JoyRaptor: "Those fade sliders should not even be visible unless I have
     * clicked on that spine piece directly. If I haven't clicked on it and it's only selected
     * because the playhead is there, they shouldn't show up." The fade veils + knobs are gated
     * on this, NOT on {@code selectedIndex >= 0}; the green trim handles keep their old
     * selection-only behaviour. Cleared by: {@link #setSegments} (project/playhead-driven
     * re-selection), {@link #setSelectedIndex} (every programmatic selection, which is the
     * playhead path), the same-segment deselect toggle, collapsing the spine, and any DOWN
     * that lands outside the master band (tapping a lane, the audio band, the ruler, the
     * minimap — "the user taps away"). A DOWN inside the master band deliberately does NOT
     * clear it, so a knob can still be grabbed and dragged.
     *
     * <p>Stored as the tapped CLIP ID — not a boolean, and not an index. Both simpler forms
     * were tried on the device and both failed, for the same underlying reason: one tap on the
     * spine produces a whole CASCADE of selection traffic, not one selection. Logged on the
     * Note 9, a single tap on a clip runs
     *   UP → seekToTimelineMs(...) → activity → setSelectedIndex(tapped)
     *      → setSegments(..., tapped) → [the view's own tap branch now sees
     *        downSegIndex == selectedIndex, so it takes the DESELECT arm]
     *      → setSegments(..., -1) → activity re-selects → setSelectedIndex(tapped)
     * so a boolean was wiped by the refeed and an index was wiped by the transient -1, and in
     * both cases the knobs never appeared at all. A clip id survives the cascade: a selection
     * of -1 is ignored as transient, and the arming only drops once the selection SETTLES on a
     * different clip — which is exactly what a playhead-driven selection change looks like.</p>
     */
    @androidx.annotation.Nullable private String spineTapArmedClipId = null;
    /** SPEC_N §3 — screen-space rect of the spine's collapse caret (see drawSpineCollapseCaret). */
    private final RectF spineCaretRect = new RectF();
    /** SPEC_V §2 — hoisted scratch rect for the collapsed strip's per-clip body (per-frame,
     *  per-clip: allocating here showed up as GC churn on a long project). */
    private final RectF collapsedStripRect = new RectF();
    private int lastPlaybackIndex = 0;  // Playback tracking (persists when deselected)
    private long playheadPositionMs = 0; // Absolute playhead position in timeline
    private long totalEffectiveMs = 0;
    private float contentWidthPx = 0;
    
    // Timeline zoom and scroll state
    private float zoomLevel = 1f;
    private float scrollOffsetPx = 0f;
    
    // Thumbnails cache (key: content-based cacheKey, value: list of thumbnails)
    private final Map<String, List<Bitmap>> thumbnailsCache = new HashMap<>();
    private final Set<String> thumbnailsLoading = new HashSet<>();
    // Keys whose extraction hard-failed (no decodable frames). Tracked so the
    // lazy per-frame loader in onDraw doesn't re-enqueue them every redraw.
    private final Set<String> thumbnailsFailed = new HashSet<>();
    /** 30→60 (2026-07-16): on a 45-min clip 30 thumbs = one frame per 90s. 60 halves that
     *  while staying ~5-10MB per segment; the proportional tile mapping in
     *  drawThumbnailsForSegment covers any remaining gap by repeating thumbs at deep zoom. */
    private static final int MAX_THUMBNAILS_PER_SEGMENT = 60;
    /** SPEC_V §2: frames sampled per segment while the spine is COLLAPSED. A 14dp strip
     *  cannot show 60 distinct frames, so it does not pay for them — a fifth of the seeks,
     *  and each frame a sixteenth of the pixels (14dp vs 56dp, squared). */
    private static final int COLLAPSED_THUMBNAILS_PER_SEGMENT = 12;
    // Disk cache so a re-opened project (or a re-scrubbed-past segment whose in-memory bitmaps
    // were evicted) doesn't re-decode frames it already extracted once. Mirrors
    // WaveformExtractor's disk-cache pattern (per-key file(s) under getCacheDir(), version-gated).
    // Partial scope of road_map §BACKLOG T1 (FEEDBACK_20260703_timeline_fidelity.md): this session
    // lands the disk LRU cache layer; the full "one background sequential MediaCodec sweep"
    // accurate-extraction architecture (replacing per-thumb OPTION_CLOSEST_SYNC seeks) is a
    // separate, larger follow-up — see the note above extractVideoThumbnails.
    // v2: filmstrip frames now come from an accurate forward MediaCodec sweep instead of
    // OPTION_CLOSEST_SYNC keyframe snapping — bump so old keyframe-snapped strips invalidate.
    private static final int FILMSTRIP_CACHE_VERSION = 2;
    private static final long FILMSTRIP_CACHE_MAX_BYTES = 24L * 1024 * 1024; // ~24MB LRU cap
    // Named + background-priority (2026-08-15, the ~200% idle CPU report). Two reasons:
    //
    // 1. IDENTITY. With the default factory these threads are "pool-N-thread-M", and N is a
    //    process-wide counter — so the one line in `top -H` that names the biggest CPU consumer
    //    in the app named nothing at all. Two separate sessions burned time guessing which of
    //    the codebase's ~20 executors "pool-37" / "pool-17" was. `comm` truncates at 15 chars,
    //    which also hid that this is a TWO-thread pool showing up as one repeated name.
    // 2. PRIORITY. These threads run a full-source MediaCodec decode sweep (see
    //    extractVideoThumbnails / FilmstripSweepExtractor) that lasts MINUTES on a long source,
    //    and at default priority they compete with the main thread and RenderThread for the
    //    same cores — measured on the Note 20 with a 46-minute source: 2 x ~100% here while
    //    main sat at 21% and RenderThread at 17%, on a PAUSED editor. Filmstrip tiles are the
    //    definition of nice-to-have work; the UI must win every time.
    //
    // This does NOT reduce the total work — that is the sweep's O(whole file) cost, which is
    // the actual blocker and a separate decision. It stops that work from starving the UI.
    // Process.setThreadPriority (not Thread.setPriority) — the Android mechanism, applied from
    // INSIDE the thread because it acts on the calling tid. It sets the real nice value and the
    // background scheduling group; Thread.setPriority only nudges the JVM-side mapping.
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2, r ->
            new Thread(() -> {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "filmstrip-sweep"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Path clipPath = new Path();
    /** Reusable dst rect for filmstrip tile blits (F2c: was a new RectF per tile per frame). */
    private final RectF thumbTileDst = new RectF();
    /**
     * Gate for per-tick/per-motion-event debug logs (F4, PERF_SPEC_LONGFILE_20260718): FLog
     * runs four redaction regexes per call and these paths fire at 20-120Hz during playback
     * and scrubbing — string-building + regex there was a measurable slice of the main-thread
     * budget on long projects. Constant-false so javac strips the calls; flip locally to debug.
     */
    private static final boolean VLOG = true;

    // ── §2 item preview images (LANE_BADGES spec) — decode/extract state owned HERE so the
    //    renderer stays pure-draw. All served from these caches; misses kick an async load
    //    and invalidate. ──────────────────────────────────────────────────────────────────
    /** Project sprite sheets, fed via {@link #setSpriteSheets} (for the sprite-cell preview). */
    private final List<com.fadcam.ui.faditor.sprite.SpriteSheet> previewSpriteSheets =
            new ArrayList<>();
    /** Decode-once loaded sheet renderers, keyed by sheet id (sprite-cell previews). */
    private final Map<String, com.fadcam.ui.faditor.sprite.SpriteSheetRenderer> spriteRendererCache =
            new HashMap<>();
    private final Set<String> spriteRendererLoading = new HashSet<>();
    private final Set<String> spriteRendererFailed = new HashSet<>();
    /**
     * Decoded, size-bounded image-item thumbnails, keyed by image uri. A TRUE LRU:
     * {@code accessOrder=true} means get() promotes, so the eldest entry really is the least
     * recently used one.
     *
     * <p>THE BUG THIS REPLACES (device-proven, Note 20, 2026-08-15 — this was the ~200% idle CPU):
     * the cache was a plain {@link HashMap} capped at 48 entries, evicted by taking the FIRST
     * entry off {@code entrySet().iterator()}. HashMap iteration order is arbitrary, so that
     * evicted an unrelated bitmap rather than the coldest one — it was never an LRU despite the
     * comment. JoyRaptor's project has 51 distinct image overlays against that cap of 48.
     *
     * <p>Three items short is all it takes, because this cache is read from the DRAW path and a
     * miss is not passive: {@code imagePreviewFor} queues a decode, and the decode's completion
     * calls {@code invalidate()}. Draw → miss → decode → invalidate → draw. With a working set
     * larger than the cap the loop has no fixed point, so it ran forever: both threads of
     * {@code thumbnailExecutor} pegged, the main thread and RenderThread burning on the
     * never-ending invalidate storm, and 34-52 lines/second of
     * "SurfaceView: updateSurface: has no frame" — the symptom the previous session read as the
     * view tree being re-laid-out every frame. It was, and this is why.
     *
     * <p>Bounding by BYTES rather than by a count of entries is the actual fix. A count cap is a
     * guess about a working set the user controls; add one more image to a project and a
     * correctly-written LRU still thrashes. Each preview is only row-height tall (~196px square,
     * ~150KB), so this budget holds many hundreds of them — a project would need thousands of
     * distinct images before the working set could exceed it again.
     */
    private static final long IMAGE_PREVIEW_CACHE_MAX_BYTES = 32L * 1024 * 1024; // ~32MB
    private final LinkedHashMap<String, Bitmap> imagePreviewCache =
            new LinkedHashMap<>(64, 0.75f, true /* accessOrder — real LRU */);
    private final Set<String> imagePreviewLoading = new HashSet<>();
    private final Set<String> imagePreviewFailed = new HashSet<>();

    // ── Frame-accurate trim-edge preview ──────────────────────────────
    // While dragging a trim handle, show the EXACT in/out frame (OPTION_CLOSEST,
    // not keyframe-snapped) in a floating bubble at the handle, so the user can
    // see precisely which frame the cut lands on — e.g. which side of a baked-in
    // jump cut. Extraction is debounced + serialized off the main thread.
    private final ExecutorService trimPreviewExecutor = Executors.newSingleThreadExecutor();
    private boolean trimEdgePreviewActive = false;
    private boolean trimEdgePreviewIsLeft = false;
    @Nullable private Bitmap trimEdgePreviewBitmap;
    private long trimEdgePreviewBitmapMs = -1;   // source ms the current bitmap shows
    private long trimEdgePreviewPendingMs = -1;  // latest requested source ms
    private long trimEdgePreviewShownMs = -1;    // source ms for the timecode label
    @Nullable private MediaMetadataRetriever trimPreviewRetriever;
    private int trimPreviewRetrieverUriHash = 0;
    private static final long TRIM_PREVIEW_DEBOUNCE_MS = 60;

    // ── Audio track state ─────────────────────────────────────────────
    private final List<AudioClip> audioClips = new ArrayList<>();
    private final List<RectF> audioClipRects = new ArrayList<>();

    // ── Touch ────────────────────────────────────────────────────────
    private boolean isScaling = false;
    /**
     * True between ACTION_DOWN and ACTION_UP/ACTION_CANCEL on this view — see the top of
     * {@link #onTouchEvent} for why it is maintained there rather than in the individual
     * gesture branches.
     */
    private boolean gestureActive = false;
    /** DIAGNOSTIC: branch-selecting flags captured at the last ACTION_UP/CANCEL. See onTouchEvent. */
    private String lastUpState = "none";
    /** FOLLOW-UP 2: true from onScaleEnd (a finger survived the pinch) until that
     *  finger lifts — its MOVEs drive the scrub directly, re-anchored (see onScaleEnd). */
    private boolean postPinchPanActive = false;
    /** Previous raw x of the surviving pointer; NaN = not yet re-anchored. */
    private float postPinchLastX = Float.NaN;
    
    private enum Drag {
        NONE,
        LEFT_HANDLE,
        RIGHT_HANDLE,
        FADE_IN_HANDLE,
        FADE_OUT_HANDLE,
        TRANSITION_LEFT_HANDLE,
        TRANSITION_RIGHT_HANDLE,
        FREEZE_LEFT_HANDLE,
        FREEZE_RIGHT_HANDLE,
        TEXT_ANIM_IN_HANDLE,
        TEXT_ANIM_OUT_HANDLE,
        MOTION_RANGE_START_HANDLE,
        MOTION_RANGE_END_HANDLE
    }
    private Drag activeDrag = Drag.NONE;
    /** Finger x (scrolled space) while dragging a slide freeze-zone handle. */
    private float freezeDragX;
    /** Finger x (content space) while dragging a text-box timing caret. */
    private float textAnimDragX;
    /**
     * The zones the grabbed item held when the caret was GRABBED, so the release can hand the
     * listener an undo target that the gesture's own live preview has not already overwritten.
     */
    private float textAnimBeforeIn, textAnimBeforeOut;
    /** Finger x (content space) while dragging a text-box motion-range boundary carrot. */
    /** The motion window the grabbed item held when the carrot was GRABBED (undo target). */
    private long motionRangeBeforeStartMs, motionRangeBeforeEndMs;
    private float downX, downY;
    private long downTime;
    private int downSegIndex = -1;
    private boolean longPressTriggered;

    // ── §3A.5b dislodge arming ──────────────────────────────────────────────────────────────
    /** True between the long-press firing and the touch ending: a vertical move now dislodges. */
    /** Playhead at the moment reorder mode opened, restored if the user backs out. */
    private long reorderEntryPlayheadMs = -1L;

    // ── CARRY: the dislodge as ONE continuous motion (PLAN_CARRY_V1) ─────────────────
    // The clip is NOT removed from the spine when you pull up. It stays put, drawn dimmed as an
    // "origin ghost", while a floating card follows the finger. The model changes once, on
    // RELEASE. Before this, the demote ran mid-gesture and the clip visibly teleported to a layer
    // while the finger was still down — JoyRaptor: "it moves off and then suddenly appears at another
    // layer. Now I'm not exactly sure where it is."
    private boolean carryActive;
    /** Index on the spine the carried clip came FROM. Its ghost stays drawn there. */
    private int carrySegIndex = -1;
    private float carryFingerX, carryFingerY;
    /** Finger offset inside the clip rect at pick-up, so the card does not jump under the thumb. */
    private float carryGrabDx, carryGrabDy;
    private float carryCardW, carryCardH;
    /**
     * Height the card is EASING TOWARDS. The master track is taller than a lane row, so without
     * this the clip changed shape at the instant of release — JoyRaptor: "there isn't that part that
     * happens from one aspect ratio to another in the tape". Animating it during the hover means
     * the drop changes nothing visually; the card is already the size of the thing it becomes.
     */
    private float carryTargetH;
    // How a release onto a LANE would resolve. Keyed on the free hole under the finger, so the
    // rule is monotonic: the more room there is, the less drastic the outcome. Pointing at open
    // space places; pointing at a partly-blocked span trims; pointing INSIDE an existing item
    // opens a lane. That ordering is what makes it predictable without being explained.
    private static final int CARRY_FIT = 0, CARRY_TRIM = 1, CARRY_NEWLANE = 2;
    /** A hole shorter than this is not a placement, it is a sliver — treat it as no room at all. */
    private static final long CARRY_MIN_HOLE_MS = 200L;
    /** Mirrors FaditorEditorActivity.M12_DEFAULT_LAYER_ID — the lane a demote lands on. */
    private static final String CARRY_FALLBACK_LANE_ID = "video";
    private int carryDropState = CARRY_FIT;
    private long carryHoleMs = -1;
    @Nullable private String carryTargetLaneId;
    /** Animated height of the lane-opening preview, and which row index it opens before. */
    private float laneOpenPx = 0f;
    private int laneOpenIndex = -1;
    private final Paint carryWarnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint carryNewLanePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Set when the carried object came from a LANE rather than the spine (the reverse direction). */
    private boolean carryFromLayer;
    private String carryLayerItemId;
    private String carryThumbKey;
    /** Resolved destination, recomputed every move. */
    private int carryDropSeam = -1;
    private boolean carryOverLayerBand;
    private final Paint carryCardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint carryGhostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint carryLayerBandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── §3A.4 SPINE DROP INDICATOR ───────────────────────────────────────────────────
    // A picked-up layer item hovering over the master track: show WHERE it will land before the
    // finger lifts. JoyRaptor's ask, verbatim: "highlighted area where they're going to be inserted so
    // that everything is cleanly projected [to] the user so that they don't have to guess."
    /** Seam index the hovering item would insert at, or -1 when it is not over the spine. */
    private int spineDropIndex = -1;
    private final Paint spineDropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spineDropGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean dislodgeArmed;
    /** Which segment armed, so a dislodge cannot act on a different clip than the one held. */
    private int dislodgeArmedSegIndex = -1;
    /** Vertical travel (px) that turns an armed hold into a dislodge. One finger-width-ish. */
    private float dislodgeThresholdPx = 0f;
    private long dragStartInMs, dragStartOutMs;
    private float dragStartSegLeft, dragStartSegRight;
    private float trimDragX;                  // Timeline-space x of handle during trim drag
    private float trimDragStartFrac;          // Start fraction computed during trim drag
    private float trimDragEndFrac;            // End fraction computed during trim drag
    private float lastTrimFingerScreenX;      // Last finger screen X during trim drag (for edge scroll)
    // A1 (slice 3): last finger SCREEN position during a picked-up item MOVE, consumed
    // by edgeScrollRunnable's item branch to keep re-mapping the item as the view pans.
    private float lastItemDragScreenX, lastItemDragScreenY;
    private long trimDragStartLoopBefore;     // loopBeforeMs at drag start (for loop extension)
    private long trimDragStartLoopAfter;      // loopAfterMs at drag start (for loop extension)
    private long trimDragLoopBefore;          // current loopBeforeMs during drag
    private long trimDragLoopAfter;           // current loopAfterMs during drag
    private boolean loopChangedDuringDrag;    // true if loop values were modified during drag
    /** L3: true while the loop-extension readout bubble should be drawn (i.e. a
     *  LEFT_HANDLE/RIGHT_HANDLE drag has crossed into loop-extension territory
     *  this gesture). Cleared alongside the trim-edge preview on drag end. */
    private boolean loopReadoutActive;
    private static final long LONG_PRESS_MS = 400;

    // ── Edge auto-scroll during trim drag ─────────────────────────────
    private static final float EDGE_SCROLL_ZONE_DP = 60f;
    private static final long EDGE_SCROLL_INTERVAL_MS = 16;  // ~60fps
    private static final float EDGE_SCROLL_MAX_SPEED_DP = 12f; // max dp per tick
    private float edgeScrollZonePx;
    private float edgeScrollMaxSpeedPx;
    private final Handler edgeScrollHandler = new Handler(Looper.getMainLooper());
    private boolean isEdgeScrolling = false;

    // ── Reorder mode ────────────────────────────────────────────────
    private boolean isReorderMode = false;
    private final List<Integer> reorderOrder = new ArrayList<>();
    private int reorderDragIdx = -1;           // index within reorderOrder being dragged
    private float reorderDragCenterX;          // drag center X in view coords
    private float reorderDragCenterY;          // drag center Y in view coords
    private float reorderBlockSize;            // computed block side length
    private float reorderRowStartX;            // X of first block
    private float reorderRowCenterY;           // Y center of block row
    private float reorderGapPx;               // gap between blocks
    // Horizontal scroll for the reorder block row when it overflows the screen
    // (many clips). Drag a block near an edge to auto-scroll, or drag it over the
    // reorder minimap to jump straight to that part of the row.
    private float reorderScrollPx = 0f;
    private float reorderMaxScrollPx = 0f;
    private final RectF reorderMinimapRect = new RectF();
    private boolean reorderMinimapDragging = false;
    // Scrubbing the minimap WITHOUT holding a clip — pans the block row so the user
    // can see what's there (and where they'll drop) before grabbing anything.
    private boolean reorderMinimapPanning = false;
    private static final float REORDER_BAR_HEIGHT_DP = 36f;
    private static final float REORDER_BTN_PADDING_DP = 14f;
    private static final float REORDER_BLOCK_CORNER_DP = 8f;
    private float reorderBarHeightPx;
    private float reorderBtnPaddingPx;
    private float reorderBlockCornerPx;
    private final RectF reorderCancelRect = new RectF();
    private final RectF reorderDoneRect = new RectF();
    private final RectF reorderLinkRect = new RectF();
    private int reorderSegmentIndex = -1;
    private final Paint reorderBgPaint2 = new Paint();
    private final Paint reorderBlockPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reorderBlockDragPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reorderBlockSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reorderNumPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reorderBtnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reorderBarPaint = new Paint();
    private final Paint reorderDropIndicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint loopPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── External asset drag ─────────────────────────────────────────
    private boolean assetDragActive = false;
    private boolean assetDragOverTimeline = false;
    private float assetDragScreenX;
    private float assetDragScreenY;
    private int assetDragInsertIndex = -1;

    // ── Long press detection via Handler ─────────────────────────────
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private int clipAudioShelfLongPressSegIndex = -1;
    private final Runnable clipAudioShelfLongPressRunnable = this::onClipAudioShelfLongPress;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeDrag == Drag.NONE && downSegIndex >= 0 && segments.size() > 1
                    && !isScaling) {
                // §3A.5b — HOLD ARMS, VERTICAL COMMITS. Holding no longer drops you straight
                // into reorder: it arms, shows that it armed, and waits. A vertical move then
                // dislodges the clip; releasing without one opens the reorder dialog as before.
                //
                // The old behaviour fired on a plain timer with no stillness or intent test, so
                // pausing to think — or holding steady for a precision horizontal move — put you
                // in reorder mode uninvited. That is the exact complaint this closes.
                longPressTriggered = true;
                dislodgeArmed = true;
                dislodgeArmedSegIndex = downSegIndex;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                invalidate();   // draw the armed lift so "now move up" is discoverable
            }
        }
    };
    private void onClipAudioShelfLongPress() {
        if (listener != null && clipAudioShelfLongPressSegIndex >= 0) {
            int seg = clipAudioShelfLongPressSegIndex;
            clipAudioShelfLongPressSegIndex = -2;
            listener.onClipAudioShelfLongPressed(seg);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else {
            clipAudioShelfLongPressSegIndex = -1;
        }
    }

    private int hitClipAudioShelf(float x, float y) {
        if (clipAudioDrawerFraction.isEmpty() || segRects.isEmpty() || segments.isEmpty()) return -1;
        float bandTop = masterBotPx() + transcriptReservePx();
        float bandH = clipAudioDrawerBandPx();
        if (bandH <= 0 || y < bandTop || y > bandTop + bandH) return -1;
        float scrolledX = x;
        for (int i = 0; i < segRects.size() && i < segments.size(); i++) {
            RectF r = segRects.get(i);
            SegmentData sd = segments.get(i);
            if (sd.isImageClip) continue;
            if (!clipAudioDrawerOpen.contains(sd.clipId)) continue;
            if (scrolledX >= r.left && scrolledX <= r.right) return i;
        }
        return -1;
    }

    private final Runnable edgeScrollRunnable = new Runnable() {
        @Override
        public void run() {
        // Reorder mode: auto-scroll the block ROW (not the main timeline) so the
        // dragged clip can reach off-screen positions.
        if (isReorderMode) {
            if (reorderDragIdx < 0 || reorderMaxScrollPx <= 0f) {
                isEdgeScrolling = false;
                return;
            }
            float fingerX = reorderDragCenterX;
            int vw = getWidth();
            float delta = 0f;
            if (fingerX < edgeScrollZonePx) {
                delta = -edgeScrollMaxSpeedPx * (1f - fingerX / edgeScrollZonePx);
            } else if (fingerX > vw - edgeScrollZonePx) {
                delta = edgeScrollMaxSpeedPx * (1f - (vw - fingerX) / edgeScrollZonePx);
            } else {
                isEdgeScrolling = false;
                return;
            }
            reorderScrollPx = Math.max(0f, Math.min(reorderMaxScrollPx, reorderScrollPx + delta));
            invalidate();
            edgeScrollHandler.postDelayed(this, EDGE_SCROLL_INTERVAL_MS);
            return;
        }
        // A1 (slice 3): picked-up row-item MOVE near a screen edge — pan the timeline
        // continuously and re-map the item through the new offset each tick, so the
        // item travels with the view and the WYSIWYG resolver keeps the preview legal
        // the whole way. Suppressed while the minimap nav owns the finger or an
        // excursion animation owns scrollOffsetPx (precedence handled in onMove).
        if (m7ItemGestureActive && layerGestureController != null
                && layerGestureController.isMoveDragActive() && !itemDragMinimapNav) {
            if (excursionActive) {
                isEdgeScrolling = false;
                return;
            }
            // A1 arbitration (C8): a gap-insertion hover suppresses horizontal edge-pan
            // even when the finger is held still in the edge zone (intent is vertical —
            // open a new lane). Vertical M6 row-reveal below stays live.
            boolean gapHover = layerGestureController.isHoverGapActive();
            // SPEC W §4 — no auto-pan for full-span objects, either side of the
            // hover: the dragged item spanning the timeline, or the occupant it is
            // hovering over spanning it. Panning toward "the end" of something with
            // no end is the disorientation being fixed; the shared full-span
            // definition is the same one the resolvers use. Vertical row-reveal
            // stays live — reaching an empty lane requires it, and it never chases
            // an end.
            boolean fullSpanPan = layerGestureController.isActiveDragFullSpan(totalEffectiveMs)
                    || layerGestureController.isFullSpanBlocked();
            float fx = lastItemDragScreenX;
            float fy = lastItemDragScreenY;
            int vw = getWidth();
            float hDelta = 0f;
            if (!gapHover && !fullSpanPan && fx < edgeScrollZonePx) {
                hDelta = -edgeScrollMaxSpeedPx * (1f - fx / edgeScrollZonePx);
            } else if (!gapHover && !fullSpanPan && fx > vw - edgeScrollZonePx) {
                hDelta = edgeScrollMaxSpeedPx * (1f - (vw - fx) / edgeScrollZonePx);
            }
            // VERTICAL M6 auto-scroll (JoyRaptor 2026-07-07 hand-test): a held item near the top/bottom of
            // the capped row band scrolls the rows so hidden lanes — and the "+ new layer" zone pinned
            // at the band bottom — become reachable during the drag (was horizontal-only before).
            float vDelta = m6MoveDragVerticalScrollDelta(fy);
            if (hDelta == 0f && vDelta == 0f) {
                isEdgeScrolling = false;
                return;
            }
            if (hDelta != 0f) {
                scrollOffsetPx += hDelta;
                clampScroll();
            }
            if (vDelta != 0f) {
                layerRowRenderer.scrollBy(vDelta);
            }
            layerGestureController.onRowBodyMove(fx + scrollOffsetPx, fy,
                    getM6RowsTopPx(), totalEffectiveMs, EditorTimelineView.this::xToTime);
            invalidate();
            edgeScrollHandler.postDelayed(this, EDGE_SCROLL_INTERVAL_MS);
            return;
        }
        if (!(activeDrag == Drag.LEFT_HANDLE || activeDrag == Drag.RIGHT_HANDLE)
                && !assetDragActive) {
            isEdgeScrolling = false;
            return;
        }
        float screenX = assetDragActive ? assetDragScreenX : lastTrimFingerScreenX;
        int viewWidth = getWidth();
        float scrollDelta = 0;
        if (screenX < edgeScrollZonePx) {
            float depth = 1f - (screenX / edgeScrollZonePx);
            scrollDelta = -edgeScrollMaxSpeedPx * depth;
        } else if (screenX > viewWidth - edgeScrollZonePx) {
            float depth = 1f - ((viewWidth - screenX) / edgeScrollZonePx);
            scrollDelta = edgeScrollMaxSpeedPx * depth;
        } else {
            isEdgeScrolling = false;
            return;
        }
        scrollOffsetPx += scrollDelta;
        clampScroll();
        if (assetDragActive) {
            updateAssetDragFromScreenX(screenX);
        } else {
            float scrolledX = screenX + scrollOffsetPx;
            doTrimDrag(scrolledX);
        }
            edgeScrollHandler.postDelayed(this, EDGE_SCROLL_INTERVAL_MS);
        }
    };
    
    // Gesture detectors for zoom and scroll
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private OverScroller flingScroller;
    private boolean flingJustFinished = false;  // Tracks when fling ends so we can reset userDragging
    // Throttle guard for computeScroll's fling step: OverScroller.computeScrollOffset() can
    // report the SAME (or sub-pixel-different but same-rounded-px) curX for consecutive frames
    // near the end of a fling — re-running updatePlayheadFromX + postInvalidateOnAnimation in
    // that case is pure waste (same seek, same draw). Math.MIN_VALUE sentinel forces the first
    // fling frame of any run to always process (avoids a stale value from a previous fling
    // suppressing frame 1 of a new one).
    private float lastFlingScrollOffsetPx = Float.MIN_VALUE;
    /**
     * Velocity for the CUSTOM-path row-band scrub (user feedback 2026-07-03: main-timeline
     * swipes glide with inertia via GestureDetector's onFling; row-band scrubs stopped
     * dead on lift because they bypass the detector). Fed every custom-path touch; read
     * only when a scrub-passthrough gesture lifts, then handed to the SAME fling the
     * detector uses ({@link #startPlayheadFling}).
     */
    private android.view.VelocityTracker rowScrubVelocityTracker;
    private int minFlingVelocityPx, maxFlingVelocityPx;

    // ── FOLLOW-UP 1: bookend "view excursion" (user spec 2026-07-03) ──────────────
    /**
     * True while the view is temporarily scrolled away from its anchor to show a
     * bookend snap preview during a pickup-drag (and during the animated return).
     * While true the playhead draws CONTENT-LOCKED (scrolls off-screen with the
     * timeline) instead of re-centered — the user's deliberate cue that this scroll is
     * a temporary maneuver and how far from "home" the view currently is.
     */
    private boolean excursionActive = false;
    /** scrollOffsetPx to return to when the excursion ends (the pre-excursion anchor). */
    private float excursionReturnOffsetPx;
    /** Joint (content ms) the current excursion is showing; Long.MIN_VALUE = none. */
    private long excursionShownJointMs = Long.MIN_VALUE;
    private android.animation.ValueAnimator excursionAnimator;
    /**
     * SPEC W §5 — dwell before the FIRST pan of a drag. Was ~220ms (feedback
     * 2026-07-03am): slow pass-throughs armed the excursion mid-travel and yanked
     * the view somewhere the finger was only visiting. Now 700ms — the middle of
     * the 600–800ms window — so only PARKING (finger substantially still over one
     * target) arms it, and travelling through lanes never does, however slowly.
     * Tune the feel in this ONE line; the reset distance below is the other half.
     */
    private static final long EXCURSION_DWELL_MS = 700;
    /**
     * SPEC W §5 — the other half of the dwell: finger travel (dp) that RESTARTS
     * the dwell clock. Any deliberate move past this distance re-arms from zero,
     * so a slow drag through several lanes keeps postponing the pan instead of
     * firing mid-journey. One line, same tuning story as the duration above.
     */
    private static final float EXCURSION_DWELL_RESET_DP = 12f;
    private long pendingExcursionJointMs = Long.MIN_VALUE;
    /** Finger position where the current dwell was armed — movement past the
     * reset distance restarts the clock (SPEC W §5). */
    private float dwellArmX = 0f, dwellArmY = 0f;
    private float excursionDwellResetPx = 0f;
    private final Runnable excursionEnterRunnable = new Runnable() {
        @Override
        public void run() {
            long j = pendingExcursionJointMs;
            pendingExcursionJointMs = Long.MIN_VALUE;
            if (j != Long.MIN_VALUE && m7ItemGestureActive && layerGestureController != null
                    && layerGestureController.getBookendJointMs() == j) {
                startOrRetargetExcursion(j);
            }
        }
    };

    private void cancelPendingExcursionEnter() {
        longPressHandler.removeCallbacks(excursionEnterRunnable);
        pendingExcursionJointMs = Long.MIN_VALUE;
    }

    /** Animate scrollOffsetPx to a target; excursion animations never teleport (spec). */
    private void animateExcursionScrollTo(float targetOffset, @Nullable Runnable onEnd) {
        if (excursionAnimator != null) {
            excursionAnimator.removeAllListeners();
            excursionAnimator.removeAllUpdateListeners();
            excursionAnimator.cancel();
        }
        excursionAnimator = android.animation.ValueAnimator.ofFloat(scrollOffsetPx, targetOffset);
        // 420ms ease-in-out (was 260ms decelerate) — "the pans to the sides could go a
        // little bit slower, make it smooth" (feedback 2026-07-03am).
        excursionAnimator.setDuration(420);
        excursionAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        excursionAnimator.addUpdateListener(a -> {
            scrollOffsetPx = (float) a.getAnimatedValue();
            invalidate();
        });
        if (onEnd != null) {
            excursionAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) { onEnd.run(); }
            });
        }
        excursionAnimator.start();
    }

    /**
     * Margin (dp) kept between a revealed bookend joint and the screen edge — the joint
     * is brought JUST inside the viewport by this much, never centered (slice-3 #4).
     */
    private static final float EXCURSION_REVEAL_MARGIN_DP = 56f;

    /**
     * Enter (or retarget/flip) the excursion so the given joint is revealed. MINIMUM-PAN
     * (dragux_v3 slice-3 #4, user hand-test 2026-07-04: "the camera moves too far over —
     * disorienting"): pan only enough to bring {@code jointMs} on-screen with a small
     * margin — never centering it — and if it is already comfortably visible, hold the
     * current offset (no pan at all). The target is clamped to the same bounds
     * {@link #clampScroll()} enforces so it never over-pans past the timeline ends.
     */
    private void startOrRetargetExcursion(long jointMs) {
        if (!excursionActive) {
            excursionActive = true;
            excursionReturnOffsetPx = scrollOffsetPx;
            if (!flingScroller.isFinished()) flingScroller.abortAnimation();
        }
        excursionShownJointMs = jointMs;
        if (layerGestureController != null) layerGestureController.setSuppressMoveMapping(false);
        float jointContentX = timeToX(jointMs);
        float margin = EXCURSION_REVEAL_MARGIN_DP * density;
        float screenX = jointContentX - scrollOffsetPx;
        float target;
        if (screenX < margin) {
            target = jointContentX - margin;                 // just off the left → nudge right
        } else if (screenX > getWidth() - margin) {
            target = jointContentX - (getWidth() - margin);  // just off the right → nudge left
        } else {
            target = scrollOffsetPx;                          // already visible → hold, no pan
        }
        float centerX = getWidth() / 2f;
        float minScroll = edgePaddingPx - centerX;
        float maxScroll = timeToX(getTimelineEndMs()) - centerX;
        target = Math.max(minScroll, Math.min(target, maxScroll));
        animateExcursionScrollTo(target, null);
    }

    /**
     * Abandon an excursion IN PLACE (no glide home): the user deliberately started
     * A1 edge auto-pan mid-excursion, so continuous travel takes over from wherever
     * the view currently is — the pre-excursion anchor is forgotten (precedence rule:
     * edge-pan = deliberate travel, beats the targeted butt reveal).
     */
    private void abandonExcursionInPlace() {
        if (!excursionActive) return;
        if (excursionAnimator != null) {
            excursionAnimator.removeAllListeners();
            excursionAnimator.removeAllUpdateListeners();
            excursionAnimator.cancel();
        }
        excursionActive = false;
        excursionShownJointMs = Long.MIN_VALUE;
        if (layerGestureController != null) layerGestureController.setSuppressMoveMapping(false);
    }

    /** Animate back to the pre-excursion anchor; playhead stays content-locked until home. */
    private void endExcursion(String cause) {
        if (!excursionActive) return;
        excursionShownJointMs = Long.MIN_VALUE;
        // Freeze the item's finger->time mapping while the view glides home — mapping
        // finger x through a mid-animation scrollOffset would teleport the item.
        if (layerGestureController != null) layerGestureController.setSuppressMoveMapping(true);
        animateExcursionScrollTo(excursionReturnOffsetPx, () -> {
            excursionActive = false;
            if (layerGestureController != null) layerGestureController.setSuppressMoveMapping(false);
            invalidate();
        });
    }

    @Nullable private OnSegmentActionListener listener;

    /** When true, detected-silence candidates are drawn yellow and are tappable. */
    private boolean showSilence = false;

    public void setShowSilence(boolean show) {
        this.showSilence = show;
        invalidate();
    }

    public boolean isShowSilence() {
        return showSilence;
    }

    /** Indices of segments whose source media is missing/unresolvable. */
    @Nullable private Set<Integer> missingSegments;

    // ── Segment data holder ──────────────────────────────────────────
    private static class SegmentData {
        final int index;
        final long sourceDurationMs;
        final Uri sourceUri;
        final boolean isImageClip;
        final String cacheKey;
        /** Source-only key for the filmstrip thumbnail caches (trim-independent). */
        final String thumbKey;
        long inPointMs;
        long outPointMs;
        long trimmedMs;
        float speed;
        long effectiveMs;
        /** Reference to the clip's live removed-span list (drawn as dark regions). */
        final java.util.List<long[]> removedSpans;
        /** Reference to the clip's detected-silence candidates (drawn yellow). */
        final java.util.List<long[]> silenceCandidates;
        /** The clip's id (for looking up transcripts). */
        final String clipId;
        /** Reference to the Clip object for opacity keyframes and other live data. */
        final Clip clip;

        SegmentData(int i, @NonNull Clip clip) {
            this.index = i;
            this.clip = clip;
            this.sourceDurationMs = clip.getSourceDurationMs();
            this.sourceUri = clip.getSourceUri();
            this.isImageClip = clip.isImageClip();
            this.inPointMs = clip.getInPointMs();
            this.outPointMs = clip.getOutPointMs();
            this.trimmedMs = outPointMs - inPointMs;
            this.speed = clip.getSpeedMultiplier();
            // Use visual (looped) duration when loop/ping-pong is active
            if (clip.hasLoopExtension()) {
                this.effectiveMs = Math.max(1, clip.getVisualDurationMs());
            } else {
                this.effectiveMs = Math.max(1, (long)(trimmedMs / speed));
            }
            this.removedSpans = clip.getRemovedSpans();
            this.silenceCandidates = clip.getSilenceCandidates();
            this.clipId = clip.getId();
            // Content-based key: same media + same trim = same thumbnails
            this.cacheKey = sourceUri.hashCode() + "_" + inPointMs + "_" + outPointMs;
            // TRIM-INDEPENDENT thumbnail key (2026-07-16): thumbs extract across the FULL
            // source once and any trim window maps onto them at draw time — so a cut/trim
            // keeps its frames instantly instead of blanking the tape and re-decoding the
            // whole QHD source (which also tanked playback fps mid-edit).
            this.thumbKey = sourceUri.hashCode() + "_src";
        }
    }

    // ── Listener interface ───────────────────────────────────────────
    public interface OnSegmentActionListener {
        void onSegmentSelected(int index);
        /** A yellow silence candidate was tapped — convert it to a cut. */
        void onSilenceCandidateTapped(int segmentIndex, long startMs, long endMs);
        void onTrimChanged(int segmentIndex, float startFraction, float endFraction, boolean isLeft);
        void onTrimFinished(int segmentIndex, float startFraction, float endFraction);
        /**
         * Slide freeze-zone handle drag finished (JoyRaptor 2026-07-16): the inner
         * handles set how long the slide holds its first/last frame inside the
         * trim window. Values are clip-window-relative ms, already clamped.
         */
        default void onSlideFreezeChanged(int segmentIndex,
                long freezeStartMs, long freezeEndMs) {}
        /**
         * A text box's timing caret was RELEASED (SPEC_TEXT_ANIMATION, LEDGER §3h): how much of
         * the box's span it takes to animate in, and how much to animate out.
         *
         * <p><b>Values are a FRACTION OF THE BOX'S SPAN</b> (0…0.5), already clamped — the same
         * base {@code TextOverlayItem.setTextAnimZonePct} stores and both text-box renderers
         * evaluate against. They are not a duration: a zone measured in timeline ms would cover
         * the wrong span on any speed-adjusted content, the exact class of mistake LEDGER §3g was,
         * and a fraction removes the units rather than pinning them down.</p>
         *
         * <p>Fires ONCE per gesture, on release, and carries the values from BEFORE the gesture as
         * well as after. The before-values travel with the callback rather than being remembered
         * by the implementer, because by release the model already holds this gesture's own live
         * preview — an implementer reading "the current value" as its undo target would record a
         * step that undoes to the last pixel of the drag instead of to where the drag started. The
         * view captures them on the DOWN that grabbed the caret, which is the only moment they are
         * unambiguously available. See {@link #onTextAnimZonesPreviewed}.</p>
         */
        default void onTextAnimZonesChanged(@NonNull String itemId,
                float beforeIn, float beforeOut, float inPct, float outPct) {}
        /**
         * The same values, live, on every frame of a caret drag — so the preview canvas follows
         * the finger instead of jumping on release.
         *
         * <p>Records NOTHING: no undo step and no autosave. One undo entry per pixel would bury
         * the user's real history under a hundred of the gesture's own, which is why the caption
         * sliders split preview from commit the same way.</p>
         */
        default void onTextAnimZonesPreviewed(@NonNull String itemId,
                float inPct, float outPct) {}
        /**
         * A text box's motion-RANGE window was dragged (SPEC_TEXT_DRAWER follow-up, 2026-08-08):
         * the [startMs, endMs) window the entrance/exit zones evaluate against, in ABSOLUTE
         * timeline ms. Fires ONCE per gesture, on release, carrying the before-window as well as
         * the after — same capture-on-DOWN rule as {@link #onTextAnimZonesChanged}.
         */
        default void onMotionRangeChanged(@NonNull String itemId,
                long beforeStartMs, long beforeEndMs, long afterStartMs, long afterEndMs) {}
        /**
         * The same window, live on every frame of the carrot drag — preview only, no undo.
         */
        default void onMotionRangePreviewed(@NonNull String itemId,
                long startMs, long endMs) {}
        /** Double-tap on a generated-slide clip → its code editor sheet. */
        default void onSlideDoubleTapped(int segmentIndex) {}
        /** Called when playhead is seeked. isDragging=true means user is actively dragging,
         *  so don't load new clips yet; isDragging=false means this is a discrete seek or drag end. */
        void onPlayheadSeeked(int segmentIndex, float fractionInSegment, boolean isDragging);
        void onPlayheadDragFinished();
        void onSegmentReordered(int fromIndex, int toIndex);
        void onReorderModeChanged(boolean entering);

        /**
         * §3A.5b — an ARMED master clip was pulled vertically: dislodge it from the spine.
         * The activity performs the demote (and its undo); the view only reports intent.
         */
        /**
         * A master clip was pulled off the spine. Returns the id of the resulting LAYER item so
         * the still-live touch can be handed to the layer drag engine, or null if the demote was
         * refused. §3A.5b.
         */
        default String onClipDislodgeRequested(int segmentIndex) { return null; }
        /**
         * The dislodged clip was successfully adopted by the layer drag engine, so the demote and
         * the coming drop are ONE gesture and must cost ONE undo press. §3A.5b.
         */
        default void onDislodgeAdopted() {}
        /**
         * A picked-up layer item's drag ended — commit OR cancel. Paired with
         * {@link #onDislodgeAdopted} so the undo merge can never outlive its gesture.
         */
        default void onItemDragEnded() {}
        /**
         * A held layer item was released over the master track: promote it into the spine at
         * {@code insertIndex}, the seam the §3A.4 indicator was pointing at.
         */
        default void onItemDroppedOnMasterTrack(int insertIndex) {}
        /** A carried spine clip was released over the layer band: demote it, landing at {@code atMs}. */
        /**
         * @param laneId   lane the card was over, or null
         * @param trimToMs if &gt;= 0, shorten the clip to this length so it fits the hole
         * @param newLane  true when there was no room — mint a fresh lane rather than overlap
         */
        default void onClipCarriedToLayer(int segmentIndex, long atMs, @Nullable String laneId,
                                          long trimToMs, boolean newLane) {}
        /** A carried spine clip was released over a different seam: reorder it there. */
        default void onClipCarriedToSeam(int fromIndex, int toIndex) {}
        /** The user tapped the "Link" button in the reorder bar — open relink for the given clip. */
        default void onReorderLinkRequested(int segmentIndex) {}
        /**
         * A drag on an overlay's timeline handle (range edge or keyframe diamond)
         * is about to begin — capture the overlay's before-state for undo here,
         * before any mutation. Default no-op for backward compatibility.
         */
        default void onOverlayDragStart(int overlayIndex) {}
        void onOverlayRangeChanged(int overlayIndex, long startMs, long endMs, boolean isLeft);
        void onOverlayRangeFinished(int overlayIndex, long startMs, long endMs);
        void onOverlayKeyframeMoved(int overlayIndex, long oldLocalMs, long newLocalMs);
        void onOverlayKeyframeMoveFinished(int overlayIndex, long oldLocalMs, long newLocalMs);
        void onTransitionSelected(int index);
        void onTransitionDurationChanged(int index, long durationMs);
        void onTransitionDurationFinished(int index, long durationMs);
        void onTransitionDeleted(int index);
        /** Loop extension trim finished — called when drag extends past source bounds. */
        default void onLoopTrimFinished(int segmentIndex, long oldBefore, long oldAfter, long newBefore, long newAfter) {}
        /** FADE_KNOBS §2.5: spine fade knobs committed — one undo step for the whole gesture. */
        default void onMasterFadeFinished(int segmentIndex, long oldInMs, long oldOutMs,
                                          long newInMs, long newOutMs) {}
        /**
         * ADVERSARIAL FIX 2: whether the host is in a mode that forbids timeline edits
         * (Word Sync §3.1 lockout). The master-fade knob used to gate only the COMMIT, so a
         * drag under lockout still wrote {@code masterFadeInMs/OutMs} live on every MOVE and
         * the change stuck with no undo entry and no save. The arm is now gated instead —
         * the drag never starts.
         */
        default boolean isTimelineEditLockedOut() { return false; }
        /** B7: long-press the tape inside the clip-audio shelf → same audio drawer. */
        default void onClipAudioShelfLongPressed(int segmentIndex) {}

        /** B2.U3 — cross-fade pill drag finished: record one undo step for whole gesture. */
        default void onCrossfadeChanged(@NonNull com.fadcam.ui.faditor.model.AudioCrossfade before,
                                        @NonNull com.fadcam.ui.faditor.model.AudioCrossfade after) {}
        /** B2.U3 — cross-fade pill created via fluent fade-drag: one undo step to remove it. */
        default void onCrossfadeCreated(@NonNull com.fadcam.ui.faditor.model.AudioCrossfade crossfade) {}
        /** B2.U3 — cross-fade pill selected (tap) → show peek inspector. */
        default void onCrossfadeSelected(@Nullable com.fadcam.ui.faditor.model.AudioCrossfade crossfade) {}
    }

    // ── Constructors ─────────────────────────────────────────────────
    public EditorTimelineView(Context c) { super(c); init(); }
    public EditorTimelineView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }
    public EditorTimelineView(Context c, @Nullable AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        updateDpPerSecond();
        minSegmentPx = MIN_SEGMENT_DP * density;
        edgePaddingPx = EDGE_PADDING_DP * density;
        recomputeMinimapHeight();
        trackHeightPx = TRACK_HEIGHT_DP * density;
        segmentGapPx = SEGMENT_GAP_DP * density;
        segmentCornerPx = SEGMENT_CORNER_DP * density;
        handleWidthPx = HANDLE_WIDTH_DP * density;
        handleOverhangPx = HANDLE_OVERHANG_DP * density;
        handleNotchWidthPx = HANDLE_NOTCH_WIDTH_DP * density;
        handleNotchHeightPx = HANDLE_NOTCH_HEIGHT_DP * density;
        playheadWidthPx = PLAYHEAD_WIDTH_DP * density;
        playheadCirclePx = PLAYHEAD_CIRCLE_DP * density;
        borderWidthPx = BORDER_WIDTH_DP * density;
        touchSlopPx = TOUCH_SLOP_DP * density;
        rulerTickHeightPx = RULER_TICK_HEIGHT_DP * density;
        chipTextPx = CHIP_TEXT_DP * density;
        chipTextScrubPx = CHIP_TEXT_SCRUB_DP * density;
        chipPadHPx = CHIP_PAD_H_DP * density;
        chipPadVPx = CHIP_PAD_V_DP * density;
        chipCornerPx = CHIP_CORNER_DP * density;
        guideDashPx = GUIDE_DASH_DP * density;
        bookmarkSizePx = BOOKMARK_SIZE_DP * density;
        bookmarkHitPx = BOOKMARK_HIT_DP * density;
        audioTrackHeightPx = AUDIO_TRACK_HEIGHT_DP * density;
        audioTrackGapPx = AUDIO_TRACK_GAP_DP * density;
        audioLaneGapPx = AUDIO_LANE_GAP_DP * density;
        audioCornerPx = AUDIO_CORNER_DP * density;
        audioWaveBarGapPx = AUDIO_WAVEFORM_BAR_GAP_DP * density;
        // §3b: hand the renderer the app's Material Symbols font so the lane mute control can
        // be a REAL speaker glyph. Null-tolerant — the renderer keeps its hand-drawn fallback.
        android.graphics.Typeface iconFont = null;
        try {
            iconFont = androidx.core.content.res.ResourcesCompat.getFont(
                    getContext(), com.fadcam.R.font.materialicons);
        } catch (Exception e) {
            FLog.w(TAG, "Icon font unavailable for lane rows; using drawn glyphs", e);
        }
        layerRowRenderer = new com.fadcam.ui.faditor.layers.LayerRowRenderer(density, iconFont);
        // W2 HD zoom tier: zoom-tiered span-limited waveform data for the audio rows,
        // extracted via the shared WaveformExtractor pipeline. The renderer pulls per
        // item; a landed extraction just invalidates this view to swap the bars in.
        timelineWaveformCache = new com.fadcam.ui.faditor.waveform.TimelineWaveformCache(
                getContext(), this::postInvalidateOnAnimation);
        layerRowRenderer.setHdWaveformProvider(timelineWaveformCache::get);
        // AV2: quad-band tape waveform. Style holds the (currently default) look; the cache
        // lazily extracts + shapes per audio clip and invalidates when a tape is ready.
        tapeStyle = new com.fadcam.ui.faditor.waveform.TapeWaveformStyle();
        // AV4: reflect the user's persisted "Waveform visualizer" settings (crossovers, colors,
        // lanes, FX, and the eager/lazy analysis choice) so the tape matches Settings on open.
        tapeStyle.loadFrom(
                android.preference.PreferenceManager.getDefaultSharedPreferences(getContext()));
        tapeWaveformCache = new com.fadcam.ui.faditor.waveform.BandedTimelineWaveformCache(
                getContext(), tapeStyle, this::postInvalidateOnAnimation);
        layerRowRenderer.setTapeSource(tapeWaveformCache::get, tapeStyle);
        // §2 item preview images (LANE_BADGES spec): the renderer stays pure-draw; THIS view
        // owns every decode/extraction + async load + LRU cache + invalidate (see the
        // image/sprite/video preview helpers below). Video previews REUSE the master T1
        // filmstrip pipeline (extractVideoThumbnails + thumbnailsCache), not a new extractor.
        layerRowRenderer.setImagePreviewProvider(this::imagePreviewFor);
        layerRowRenderer.setSpriteCellProvider(spriteCellProvider);
        layerRowRenderer.setVideoFilmstripProvider(this::filmstripForOverlayClip);
        // SPEC_PIP_AUDIO slice D: a PiP's tape comes from the SAME banded cache the master
        // clip-audio drawer uses, via its URI-keyed API — one extraction per unique file
        // serves every trim window (superset reuse), so opening a drawer never re-analyses.
        layerRowRenderer.setLaneTapeProvider(this::laneTapeFor);
        layerGestureController = new com.fadcam.ui.faditor.layers.LayerGestureController(
                layerRowRenderer, NOOP_GESTURE_CALLBACK);

        rulerBgPaint.setColor(COLOR_RULER_BG);
        rulerBgPaint.setStyle(Paint.Style.FILL);
        rulerTextPaint.setColor(COLOR_RULER_TEXT);
        rulerTextPaint.setTextSize(RULER_TEXT_SIZE_DP * density);
        rulerTextPaint.setTextAlign(Paint.Align.CENTER);
        rulerTextPaint.setTypeface(Typeface.MONOSPACE);
        rulerTickPaint.setColor(COLOR_RULER_TICK);
        rulerTickPaint.setStrokeWidth(1f * density);
        trackBgPaint.setColor(COLOR_TRACK_BG);
        trackBgPaint.setStyle(Paint.Style.FILL);
        segmentPaint.setStyle(Paint.Style.FILL);
        borderPaint.setColor(COLOR_BORDER_SEL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(borderWidthPx);
        handlePaint.setColor(COLOR_HANDLE);
        handlePaint.setStyle(Paint.Style.FILL);
        // FADE_KNOBS §2.5 spine knob paints (same look as the lane-row knobs)
        spineKnobFillPaint.setColor(Studio.RAISED);
        spineKnobFillPaint.setStyle(Paint.Style.FILL);
        spineKnobStrokePaint.setColor(COLOR_HANDLE);
        spineKnobStrokePaint.setStyle(Paint.Style.STROKE);
        spineKnobStrokePaint.setStrokeWidth(2f * density);
        spineKnobVeilPaint.setColor(0xAA050508);
        spineKnobVeilPaint.setStyle(Paint.Style.FILL);
        spineStemPaint.setColor(COLOR_HANDLE);
        spineStemPaint.setStyle(Paint.Style.STROKE);
        spineStemPaint.setStrokeWidth(1.2f * density);
        spineStemPaint.setAlpha(150);
        spineDiagPaintImpl.setColor(COLOR_HANDLE);
        spineDiagPaintImpl.setStyle(Paint.Style.STROKE);
        spineDiagPaintImpl.setAlpha(220);
        spineDotPaint.setColor(COLOR_HANDLE);
        spineDotPaint.setStyle(Paint.Style.FILL);
        handleNotchPaint.setColor(COLOR_HANDLE_NOTCH);
        handleNotchPaint.setStyle(Paint.Style.FILL);
        playheadPaint.setColor(COLOR_PLAYHEAD);
        playheadPaint.setStyle(Paint.Style.FILL);
        playheadCirclePaint.setColor(COLOR_PLAYHEAD);
        playheadCirclePaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(COLOR_LABEL);
        labelPaint.setTextSize(LABEL_SIZE_DP * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        // KineMaster playhead lane paints (JoyRaptor 2026-07-19)
        // ── THE TIME CHIP ───────────────────────────────────────────────────────
        // JoyRaptor: "the playhead currently black with stroke around the time and square
        // needs to be solid and rounded."
        //
        // What was here was an outlined dark box: a 90%-opaque #16161B fill with a 1.25dp
        // ring of the playhead colour. That is three visual layers -- ring, fill, text --
        // to say one thing, and at 10sp the ring was thicker than the strokes of the
        // digits inside it, so the chip read as a frame with numbers trapped in it.
        //
        // Now it is ONE layer: the playhead's own colour, filled solid, with the time
        // punched out of it. The chip and the line below it become a single object that
        // changes colour together, which is the whole point of the context tinting.
        chipBgPaint.setStyle(Paint.Style.FILL);
        chipBgPaint.setPathEffect(new android.graphics.CornerPathEffect(3f * density));
        chipBorderPaint.setStyle(Paint.Style.FILL);
        chipTextPaint.setTextAlign(Paint.Align.CENTER);
        chipTextPaint.setTextSize(chipTextPx);
        // Plex Mono, the app's own mono. A running timecode redraws on every frame, and in
        // a proportional face the digits change width as they tick, so the chip visibly
        // breathes -- which reads as the app being unstable rather than as time passing.
        chipTextPaint.setTypeface(com.fadcam.ui.type.Type.mono(getContext(),
                com.fadcam.ui.type.Type.SEMIBOLD));
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(1f * density);
        guidePaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{guideDashPx, guideDashPx}, 0f));
        bookmarkPaint.setColor(COLOR_BOOKMARK);
        bookmarkPaint.setStyle(Paint.Style.FILL);
        dragGhostPaint.setColor(COLOR_DRAG_GHOST);
        dragGhostPaint.setStyle(Paint.Style.FILL);
        trimOverlayPaint.setColor(0x80000000);
        freezeMarkerPaint.setColor(Studio.INK);
        freezeZonePaint.setColor(0x3322D3EE);
        textAnimMarkerPaint.setColor(Studio.CAREFUL);
        textAnimZonePaint.setColor(0x40FBBF24);
        motionRangeMarkerPaint.setColor(Studio.GUIDE);
        // Rounded corners via a CornerPathEffect rather than by hand-building arcs: the markers
        // are built as 3-point Paths in two places, and an effect on the paint rounds both
        // without either path routine having to know about it. Radius is in px and deliberately
        // small — enough to take the needle off the point, not enough to turn a caret into a
        // blob at the size these draw (a few dp tall).
        android.graphics.CornerPathEffect markerCorners =
                new android.graphics.CornerPathEffect(Math.max(1f, 1.5f * density));
        textAnimMarkerPaint.setPathEffect(markerCorners);
        motionRangeMarkerPaint.setPathEffect(markerCorners);
        animMarkerOutlinePaint.setColor(Studio.INK);
        animMarkerOutlinePaint.setStyle(Paint.Style.STROKE);
        animMarkerOutlinePaint.setStrokeWidth(Math.max(1f, density));   // ~1dp
        animMarkerOutlinePaint.setStrokeJoin(Paint.Join.ROUND);
        // The SAME effect instance as the fills: a stroke rounded differently from the shape it
        // outlines shows as a halo that misses the corners.
        animMarkerOutlinePaint.setPathEffect(markerCorners);
        motionRangeZonePaint.setColor(0x33A78BFA);
        trimOverlayPaint.setStyle(Paint.Style.FILL);
        trimRecoverPaint.setColor(0x4035F6BF);
        trimRecoverPaint.setStyle(Paint.Style.FILL);
        transitionHelperPaint.setColor(0xCC35F6BF);
        transitionHelperPaint.setStyle(Paint.Style.STROKE);
        transitionHelperPaint.setStrokeWidth(1.5f * density);
        transitionHelperPaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{4f * density, 4f * density}, 0f));
        edgeScrollZonePx = EDGE_SCROLL_ZONE_DP * density;
        edgeScrollMaxSpeedPx = EDGE_SCROLL_MAX_SPEED_DP * density;
        excursionDwellResetPx = EXCURSION_DWELL_RESET_DP * density;
        excursionTellDashes = new android.graphics.DashPathEffect(
                new float[]{6f * density, 5f * density}, 0f);

        // Reorder mode paints
        reorderBarHeightPx = REORDER_BAR_HEIGHT_DP * density;
        reorderBtnPaddingPx = REORDER_BTN_PADDING_DP * density;
        reorderBlockCornerPx = REORDER_BLOCK_CORNER_DP * density;

        missingBgPaint.setColor(0xCCFF4438);
        missingBgPaint.setStyle(Paint.Style.FILL);
        missingTextPaint.setColor(Studio.INK);
        missingTextPaint.setTextSize(11f * density);
        missingTextPaint.setTextAlign(Paint.Align.CENTER);
        missingTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        reorderBgPaint2.setColor(0xE00D0D10);
        reorderBgPaint2.setStyle(Paint.Style.FILL);
        reorderBarPaint.setColor(Studio.PANEL);
        reorderBarPaint.setStyle(Paint.Style.FILL);
        reorderBlockPaint2.setColor(Studio.LINE);
        reorderBlockPaint2.setStyle(Paint.Style.FILL);
        reorderBlockDragPaint.setColor(Studio.OFF);
        reorderBlockDragPaint.setStyle(Paint.Style.FILL);
        reorderBlockSelectedPaint.setColor(COLOR_BORDER_SEL);
        reorderBlockSelectedPaint.setStyle(Paint.Style.STROKE);
        reorderBlockSelectedPaint.setStrokeWidth(2.5f * density);
        reorderNumPaint.setColor(0xDDFFFFFF);
        reorderNumPaint.setTextSize(14f * density);
        reorderNumPaint.setTextAlign(Paint.Align.CENTER);
        reorderNumPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        reorderBtnTextPaint.setColor(COLOR_HANDLE);
        reorderBtnTextPaint.setTextSize(14f * density);
        reorderBtnTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        carryCardPaint.setColor(Studio.RAISED);
        carryCardPaint.setShadowLayer(8f * density, 0f, 4f * density, 0xAA000000);
        // The ghost is a WASH over the clip's own pixels rather than a hole: the user must still
        // recognise WHICH clip left, and an empty slot looks like the clip was deleted.
        carryGhostPaint.setColor(0xB00D0D10);
        carryLayerBandPaint.setColor(0x338C3DFA);
        // RED = the only destructive state in the drag language (§3A.4). Trimming to fit loses
        // content, which is the same promise as the cut, so it reuses the same colour rather than
        // teaching a second warning vocabulary. The scissors glyph is REQUIRED, not decoration:
        // colour alone fails for colourblind users.
        carryWarnPaint.setColor(Studio.DANGER);
        carryWarnPaint.setStyle(Paint.Style.STROKE);
        carryWarnPaint.setStrokeWidth(2.5f * density);
        // Light purple, dashed = "creates a new layer" — already shipped, already learned.
        carryNewLanePaint.setColor(Studio.INK_DIM);
        carryNewLanePaint.setStyle(Paint.Style.STROKE);
        carryNewLanePaint.setStrokeWidth(2.5f * density);
        carryNewLanePaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{8f * density, 6f * density}, 0f));

        // §3A.4: solid purple = "insert at this seam". Purple ALREADY means "this is where it
        // lands" everywhere else in the drag language, so the seam insert inherits it rather than
        // teaching the user a second colour. Green/amber were rejected outright -- they are
        // ripple/gap mode and are on screen simultaneously.
        spineDropPaint.setColor(Studio.ROOM_AVATAR_DEEP);
        spineDropPaint.setStrokeWidth(3.5f * density);
        spineDropPaint.setStrokeCap(Paint.Cap.ROUND);
        spineDropPaint.setStyle(Paint.Style.FILL);
        spineDropGlowPaint.setColor(0x338C3DFA);
        spineDropGlowPaint.setStyle(Paint.Style.FILL);

        reorderDropIndicatorPaint.setColor(COLOR_HANDLE);
        reorderDropIndicatorPaint.setStrokeWidth(3f * density);
        reorderDropIndicatorPaint.setStrokeCap(Paint.Cap.ROUND);

        // Audio track paints
        audioTrackBgPaint.setColor(COLOR_AUDIO_TRACK_BG);
        audioTrackBgPaint.setStyle(Paint.Style.FILL);
        audioClipPaint.setColor(COLOR_AUDIO_BG);
        audioClipPaint.setStyle(Paint.Style.FILL);
        audioWavePaint.setColor(COLOR_AUDIO_WAVE);
        audioWavePaint.setStyle(Paint.Style.FILL);
        audioWavePaint.setStrokeCap(Paint.Cap.ROUND);
        audioWaveMirrorPaint.setColor(COLOR_AUDIO_WAVE_DIM);
        audioWaveMirrorPaint.setStyle(Paint.Style.FILL);
        audioWaveMirrorPaint.setStrokeCap(Paint.Cap.ROUND);
        audioCenterlinePaint.setColor(COLOR_AUDIO_CENTERLINE);
        audioCenterlinePaint.setStyle(Paint.Style.STROKE);
        audioCenterlinePaint.setStrokeWidth(1f * density);
        audioLabelPaint.setColor(COLOR_AUDIO_LABEL);
        audioLabelPaint.setTextSize(9f * density);
        audioLabelPaint.setTextAlign(Paint.Align.LEFT);
        audioBorderPaint.setColor(COLOR_BORDER_SEL);
        audioBorderPaint.setStyle(Paint.Style.STROKE);
        audioBorderPaint.setStrokeWidth(borderWidthPx);
        volEnvLinePaint.setColor(Studio.ARMED); // light blue
        volEnvLinePaint.setStyle(Paint.Style.STROKE);
        volEnvLinePaint.setStrokeWidth(1.6f * density);
        volEnvLinePaint.setStrokeJoin(Paint.Join.ROUND);
        volEnvDotPaint.setColor(Studio.ARMED);
        volEnvDotPaint.setStyle(Paint.Style.FILL);
        opacityEnvLinePaint.setColor(0xCCFFFFFF); // semi-transparent white
        opacityEnvLinePaint.setStyle(Paint.Style.STROKE);
        opacityEnvLinePaint.setStrokeWidth(1.6f * density);
        opacityEnvLinePaint.setStrokeJoin(Paint.Join.ROUND);
        opacityEnvDotPaint.setColor(0xCCFFFFFF);
        opacityEnvDotPaint.setStyle(Paint.Style.FILL);
        volumeEnvLinePaint.setColor(Studio.ARMED);
        volumeEnvLinePaint.setStyle(Paint.Style.STROKE);
        volumeEnvLinePaint.setStrokeWidth(1.6f * density);
        volumeEnvLinePaint.setStrokeJoin(Paint.Join.ROUND);
        volumeEnvDotPaint.setColor(Studio.ARMED);
        volumeEnvDotPaint.setStyle(Paint.Style.FILL);

        // Initialize gesture detectors
        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleListener());
        gestureDetector = new GestureDetector(getContext(), new GestureListener());
        flingScroller = new OverScroller(getContext());
        android.view.ViewConfiguration vc = android.view.ViewConfiguration.get(getContext());
        minFlingVelocityPx = vc.getScaledMinimumFlingVelocity();
        maxFlingVelocityPx = vc.getScaledMaximumFlingVelocity();
    }
    
    private void updateDpPerSecond() {
        dpPerSecondPx = BASE_DP_PER_SECOND * zoomLevel * density;
    }

    /** SPEC_20260829_WORD_SYNC §3.3 — ms per pixel for snap tolerance (consume, not reflect). */
    public double getMsPerPixel() {
        return 1000.0 / Math.max(1f, dpPerSecondPx);
    }

    // ══════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════

    public void setOnSegmentActionListener(@Nullable OnSegmentActionListener l) {
        this.listener = l;
    }

    /**
     * B2: the live Timeline, kept so the cross-fade pills can be read at DRAW time.
     * Reading them per-frame rather than caching a copy means a fade created, moved or
     * deleted shows up without a second "now tell the view about it" call that somebody
     * eventually forgets to make — the bug pattern this file has paid for repeatedly.
     */
    @Nullable private Timeline liveTimeline;

    public void setTimeline(@NonNull Timeline timeline, int selected) {
        this.liveTimeline = timeline;
        segments.clear();
        totalEffectiveMs = 0;
        Set<String> activeKeys = new HashSet<>();
        for (int i = 0; i < timeline.getClipCount(); i++) {
            SegmentData sd = new SegmentData(i, timeline.getClip(i));
            segments.add(sd);
            totalEffectiveMs += sd.effectiveMs;
            activeKeys.add(sd.thumbKey);
        }
        // Evict cache entries no longer referenced. SPEC_V §3: entries are keyed
        // source@sizeXcount now (see thumbVariantKey), so "still referenced" is decided on
        // the SOURCE prefix — otherwise every re-feed would evict every size variant.
        Set<String> toEvict = new HashSet<>();
        for (String k : thumbnailsCache.keySet()) {
            if (!activeKeys.contains(thumbKeyPrefixOf(k))) toEvict.add(k);
        }
        for (String key : toEvict) {
            List<Bitmap> old = thumbnailsCache.remove(key);
            if (old != null) {
                for (Bitmap bmp : old) {
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                }
            }
            thumbnailsLoading.remove(key);
            thumbnailsFailed.remove(key);
        }
        selectedIndex = selected;  // Allow -1 for no selection
        // SPEC_N §5: a refeed that lands on a DIFFERENT clip than the one the user tapped
        // disarms the fade knobs; a refeed of the same selection leaves the arming alone.
        noteSpineSelectionChanged(selected);
        selectedTransitionIndex = -1;
        if (selected >= 0) {
            lastPlaybackIndex = selected;  // Track for playback continuation
        }
        reconcileClipAudioDrawers();
        computeRects();
        
        // Center timeline on current playhead position
        if (getWidth() > 0) {
            centerPlayhead();
        }

        requestLayout();
        invalidate();
        // Kick the clip-audio tape analysis for every master clip in the background NOW —
        // by the time anyone double-taps a drawer open the tape is (being) built, instead of
        // starting a minutes-long analysis at first open (JoyRaptor 2026-07-16, 45-min lecture).
        primeBackgroundTapeAnalysis();
    }

    /**
     * Set which segment indices have missing/unresolvable source media.
     * These segments will be drawn with a red MISSING overlay.
     */
    public void setMissingSegments(@Nullable Set<Integer> indices) {
        this.missingSegments = indices;
        invalidate();
    }

    /**
     * Set the text/image overlays to visualise as layer rows below the tracks.
     * Each becomes a coloured bar over its time-range, with keyframe diamonds.
     * The view grows taller to fit them (and shrinks back to the base height
     * when there are none, so non-overlay projects are unchanged).
     */
    public void setOverlays(@NonNull List<TextOverlayItem> o) {
        overlays.clear();
        overlays.addAll(o);
        requestLayout();
        invalidate();
    }

    /** Read-only visualizer (waveform) overlays shown as their own layer rows. */
    public void setWaveformLayers(
            @NonNull List<com.fadcam.ui.faditor.model.WaveformOverlayInstance> w) {
        waveformLayers.clear();
        waveformLayers.addAll(w);
        requestLayout();
        invalidate();
    }

    /** Read-only caption spans (timeline {startMs,endMs} per captioned clip) shown as layer rows. */
    public void setCaptionSpans(@NonNull List<long[]> spans) {
        captionSpans.clear();
        captionSpans.addAll(spans);
        requestLayout();
        invalidate();
    }

    /**
     * Set the transcript to display below a specific segment, pinned to
     * word timestamps. The words scroll with the timeline like the waveform.
     */
    public void setSegmentTranscript(int segmentIndex,
                                     @Nullable com.fadcam.ui.faditor.transcript.Transcript transcript) {
        if (segmentIndex < 0 || segmentIndex >= segments.size()) return;
        SegmentData sd = segments.get(segmentIndex);
        if (transcript != null) {
            segmentTranscripts.put(sd.clipId, transcript);
        } else {
            segmentTranscripts.remove(sd.clipId);
        }
        requestLayout();
        invalidate();
    }

    /** Set transitions to render on the timeline. */
    public void setTransitions(@NonNull List<com.fadcam.ui.faditor.model.Transition> t) {
        transitions.clear();
        transitions.addAll(t);
        int max = Math.max(-1, transitions.size() - 1);
        if (selectedTransitionIndex > max) selectedTransitionIndex = -1;
        invalidate();
    }

    public int getSelectedTransitionIndex() {
        return selectedTransitionIndex;
    }

    public void setSelectedTransitionIndex(int index) {
        selectedTransitionIndex = index;
        invalidate();
    }

    /** Update the playhead source position for transcript word highlighting. */
    public void setTranscriptHighlight(int clipIndex, long sourceMs) {
        this.transcriptClipIndex = clipIndex;
        this.currentPlayheadSourceMs = sourceMs;
        invalidate();
    }

    /** Set the transcript to display inside an audio clip. */
    public void setAudioClipTranscript(int audioIndex,
                                       @Nullable com.fadcam.ui.faditor.transcript.Transcript transcript) {
        if (transcript != null) {
            audioTranscripts.put(audioIndex, transcript);
        } else {
            audioTranscripts.remove(audioIndex);
        }
        invalidate();
    }

    /** Update the playhead position for audio transcript word highlighting. */
    public void setAudioTranscriptHighlight(int audioIndex, long sourceMs) {
        this.audioTranscriptClipIndex = audioIndex;
        this.audioCurrentPlayheadMs = sourceMs;
        invalidate();
    }

    /**
     * Set the audio clips to display on the audio track below the video segments.
     * Call this whenever the timeline's audio clips change.
     *
     * @param clips unmodifiable list from Timeline.getAudioClips()
     */
    public void setAudioClips(@NonNull List<AudioClip> clips) {
        audioClips.clear();
        audioClips.addAll(clips);
        computeRects();
        requestLayout();
        invalidate();
        // AV4 eager analysis: when the user chose "analyze at import", prime the quad-band tape
        // cache for every audio clip now instead of waiting for it to first scroll into view.
        primeEagerTapeAnalysis();
    }

    /**
     * AV4: if the user chose eager analysis, kick the quad-band tape extraction for every current
     * audio clip up front (idempotent: {@code get} is in-flight / ready / failed guarded, so
     * re-calling is safe). No-op in lazy mode — the renderer's analyze-on-first-draw is unchanged.
     */
    public void primeEagerTapeAnalysis() {
        if (tapeStyle == null || !tapeStyle.analyzeEager || tapeWaveformCache == null) return;
        for (AudioClip ac : audioClips) {
            if (ac != null) tapeWaveformCache.get(ac);
        }
    }

    /**
     * Background tape analysis for the MASTER clips' audio (the clip-audio drawer tapes),
     * kicked at {@link #setTimeline} — always, regardless of the AV4 eager/lazy pref (JoyRaptor
     * decided 2026-07-16: a first drawer-open must not start a minutes-long analysis; on a
     * 45-min clip that read as a blank, broken drawer). Idempotent: the cache's get() is
     * in-flight/ready/failed guarded, and extraction runs on the cache's single worker thread.
     */
    /**
     * SPEC_PIP_AUDIO slice D: shaped tape for an opted-in PiP, or null while extracting.
     * Uses the FULL-source span (not the trim window) for the same reason the master drawer
     * does: with the cache's superset reuse, one extraction per file serves every trim this
     * clip will ever have, so trimming or splitting never restarts a long analysis.
     */
    @Nullable
    private com.fadcam.ui.faditor.waveform.BandedTimelineWaveformCache.Shaped laneTapeFor(
            @NonNull com.fadcam.ui.faditor.model.Clip clip) {
        if (tapeWaveformCache == null) return null;
        android.net.Uri uri = clip.getSourceUri();
        long srcDur = clip.getSourceDurationMs();
        if (uri == null || srcDur <= 0 || clip.isImageClip()) return null;
        return tapeWaveformCache.get(uri, 0, srcDur, srcDur);
    }

    /**
     * SPEC_PIP_AUDIO slice D: open/close a lane's audio drawer. Kept here (not in the
     * renderer) so it survives the renderer's per-frame row rebuild, mirroring how the master
     * clip-audio drawer's open set lives on this view.
     */
    public void setLaneAudioDrawerOpen(@NonNull String trackId, boolean open) {
        if (open) laneAudioDrawerOpen.add(trackId);
        else laneAudioDrawerOpen.remove(trackId);
        layerRowRenderer.setAudioDrawerOpenTrackIds(laneAudioDrawerOpen);
        requestLayout();
        invalidate();
    }

    /** True if {@code trackId}'s audio drawer is currently open. */
    public boolean isLaneAudioDrawerOpen(@NonNull String trackId) {
        return laneAudioDrawerOpen.contains(trackId);
    }

    /** Lane ids whose audio drawer is open (session UI state, like the master drawer's). */
    private final java.util.Set<String> laneAudioDrawerOpen = new java.util.HashSet<>();

    private void primeBackgroundTapeAnalysis() {
        if (tapeWaveformCache == null) return;
        for (SegmentData sd : segments) {
            // Generated slides are silent by construction and their trim ceiling
            // (30s) exceeds the baked MP4's audio — an extraction span that can
            // never complete. Skip them entirely (the drawer shows a flat tape).
            if (sd != null && sd.clip != null && sd.clip.isGeneratedSlide()) continue;
            if (sd != null && sd.sourceUri != null && !sd.isImageClip
                    && sd.sourceDurationMs > 0) {
                // FULL-source span (not the trim window): with the cache's superset reuse,
                // ONE extraction per unique file serves every trim window this clip will
                // ever have — trims and splits never re-run a minutes-long analysis.
                tapeWaveformCache.get(sd.sourceUri, 0, sd.sourceDurationMs,
                        sd.sourceDurationMs);
            }
        }
    }

    /**
     * AV4: the live global {@link com.fadcam.ui.faditor.waveform.TapeWaveformStyle} shared by
     * every audio row — handed to the "Waveform visualizer" settings sheet so its controls edit
     * (and persist) the same instance the renderer draws from.
     */
    @NonNull
    public com.fadcam.ui.faditor.waveform.TapeWaveformStyle getTapeStyle() {
        return tapeStyle;
    }

    /**
     * AV4: react after the "Waveform visualizer" settings sheet mutated {@link #getTapeStyle()}.
     * A crossover/presence change alters the extracted band data → drop the cache so it re-extracts
     * (and re-prime immediately when eager); every other knob (contrast/smooth/normalize/colors/
     * lanes/FX) only re-shapes/redraws from the same cached raw data.
     */
    public void onTapeStyleChanged(boolean crossoversChanged) {
        if (tapeWaveformCache == null) return;
        if (crossoversChanged) {
            tapeWaveformCache.clear();
            primeEagerTapeAnalysis();
            invalidate();
        } else {
            tapeWaveformCache.reshapeAll();
        }
    }

    /**
     * Returns the currently selected audio clip index, or -1 if none.
     *
     * <p>Audio consolidation (2026-07-07): when audio rides the unified renderer rows,
     * the selection authority is {@link com.fadcam.ui.faditor.layers.LayerGestureController}
     * — derive the index by mapping its selected item id over {@code audioClips}
     * ({@code TimedItem.ofAudioClip} reuses the AudioClip's id), so every activity op
     * anchored on this method (volume sheet, mute, split, captions, delete,
     * trim-to-selection, …) keeps working unchanged on the new rows.</p>
     */
    public int getSelectedAudioIndex() {
        String sel = layerGestureController != null
                ? layerGestureController.getSelectedItemId() : null;
        if (sel == null) return -1;
        for (int i = 0; i < audioClips.size(); i++) {
            if (sel.equals(audioClips.get(i).getId())) return i;
        }
        return -1;
    }

    public void setSelectedIndex(int index) {
        if (index != selectedIndex && index >= 0 && index < segments.size()) {
            selectedIndex = index;
            // SPEC_N §5: every caller of this is programmatic. It only fires when the index
            // actually changes, so a playhead that walks onto a different clip lands here and
            // disarms the knobs — the case JoyRaptor does not want them for.
            noteSpineSelectionChanged(index);
            selectedTransitionIndex = -1;
            lastPlaybackIndex = index;  // Update playback tracking
            invalidate();
        }
    }

    /**
     * Moves the playhead to the start of the given segment and centers the
     * view on it. Used after inserting a clip so the new clip is always
     * visible instead of silently landing off-screen.
     */
    public void scrollToSegment(int index) {
        if (index < 0 || index >= segments.size()) return;
        long cumulMs = 0;
        for (int i = 0; i < index; i++) {
            cumulMs += segments.get(i).effectiveMs;
        }
        // Land slightly inside the segment so it is unambiguously selected
        playheadPositionMs = cumulMs + Math.min(100, segments.get(index).effectiveMs / 2);
        centerPlayhead();
        invalidate();
    }

    /**
     * Returns the current playhead position in absolute timeline milliseconds.
     */
    public long getPlayheadPositionMs() {
        return playheadPositionMs;
    }

    /**
     * True while a finger is down on the timeline, or a playhead fling is still gliding.
     *
     * <p>Exists so the editor can tell a LIVE drag from a STRANDED "drag active" latch. The
     * editor sets that latch from {@code onPlayheadSeeked} and clears it from
     * {@code onPlayheadDragFinished}, but a dozen touch branches can return before the shared
     * ACTION_UP block that fires the latter — so the latch could survive the gesture and freeze
     * the playhead permanently (device-proven on the Note 20, 2026-07-27). This query lets the
     * editor self-heal instead of depending on every exit path remembering to notify.</p>
     *
     * <p>The fling term matters: a flung release genuinely keeps driving the playhead after the
     * finger is gone, and its own completion path notifies. Healing during it would let playback
     * fight the glide.</p>
     */
    public boolean isGestureActive() {
        return gestureActive || (flingScroller != null && !flingScroller.isFinished());
    }

    /**
     * DIAGNOSTIC: the branch-selecting flags as they stood at the last ACTION_UP/CANCEL, so a
     * stranded-latch heal can name which touch branch swallowed the release. Temporary, paired
     * with PHDIAG. See the ACTION_UP case in {@link #onTouchEvent}.
     */
    @NonNull
    public String getLastUpState() {
        return lastUpState;
    }

    /**
     * Sets playhead position in absolute timeline ms and redraws.
     * Used by audio-tail mode when playhead advances past video segments.
     */
    public void setPlayheadPositionMs(long positionMs) {
        playheadPositionMs = positionMs;
        centerPlayhead();
        invalidate();
    }

    /**
     * Programmatically jump the playhead to an absolute timeline position and
     * drive the listener so the player actually seeks/loads the right clip.
     * Used by tap-the-timestamp-to-type-a-time.
     */
    public void seekToTimelineMs(long timelineMs) {
        if (segments.isEmpty()) return;
        long clamped = Math.max(0, Math.min(timelineMs, totalEffectiveMs));
        playheadPositionMs = clamped;
        centerPlayhead();
        invalidate();

        // Resolve which segment + source fraction this position maps to.
        long cumul = 0;
        int seg = segments.size() - 1;
        long local = 0;
        for (int i = 0; i < segments.size(); i++) {
            SegmentData sd = segments.get(i);
            if (clamped <= cumul + sd.effectiveMs) {
                seg = i;
                local = clamped - cumul;
                break;
            }
            cumul += sd.effectiveMs;
        }
        SegmentData sd = segments.get(seg);
        long sourceMs = sd.inPointMs + (long) (local * sd.speed);
        float frac = sd.sourceDurationMs > 0
                ? Math.max(0f, Math.min(1f, sourceMs / (float) sd.sourceDurationMs)) : 0f;
        if (listener != null) {
            listener.onPlayheadSeeked(seg, frac, false);
            listener.onPlayheadDragFinished();
        }
    }

    /**
     * Returns the segment index that contains the current playhead position.
     * Scans through segments and returns the index of the segment containing playheadPositionMs.
     * Returns -1 if no segment contains the playhead (e.g., playhead is past all segments).
     */
    public int getSegmentAtPlayhead() {
        long cumulMs = 0;
        for (int i = 0; i < segments.size(); i++) {
            SegmentData sd = segments.get(i);
            long endMs = cumulMs + sd.effectiveMs;
            if (playheadPositionMs >= cumulMs && playheadPositionMs < endMs) {
                return i;
            }
            if (i == segments.size() - 1 && playheadPositionMs == endMs) {
                return i;
            }
            cumulMs = endMs;
        }
        return segments.isEmpty() ? -1 : segments.size() - 1;
    }

    /**
     * Returns the start time (in timeline ms) for the given segment index.
     */
    public long getSegmentStartTimeMs(int index) {
        return getSegmentStartTime(index);
    }

    /**
     * Accept a source-absolute fraction (0..1 of source duration).
     * Converts to absolute ms position in timeline.
     * Works regardless of UI selection state - uses last known playing segment.
     */
    /**
     * How far past a clip's out-point a playback report may run and still be treated as
     * "the seam is passing" rather than a bad report. Observed overshoot is 9-48ms; half a
     * second is comfortably above that and far below a real mis-attribution.
     */
    private static final long MAX_BOUNDARY_GLIDE_SOURCE_MS = 500L;

    public void setPlayheadFraction(float sourceFraction) {
        // Use last valid index for playback if currently deselected
        int playbackIndex = selectedIndex >= 0 ? selectedIndex : lastPlaybackIndex;
        
        if (VLOG) FLog.d(TAG, "setPlayheadFraction: fraction=" + sourceFraction + " selectedIndex=" + selectedIndex + " playbackIndex=" + playbackIndex);
        
        if (playbackIndex >= 0 && playbackIndex < segments.size()) {
            SegmentData sd = segments.get(playbackIndex);
            long selectedSegmentStartMs = getSegmentStartTime(playbackIndex);
            long rawSourceMs = (long)(sourceFraction * sd.sourceDurationMs);
            // BOUNDARY GLIDE: near a cut the player keeps reporting positions a little PAST
            // this clip's out-point (measured on the Note 20: 9-48ms over, for 2-6 updates
            // in a row). Pinning every one of them to the out-point froze the playhead at
            // each cut for 110-330ms before the next clip's updates took over — a visible
            // hitch on every edit. Carry the overshoot FORWARD instead, so the playhead
            // keeps moving through the seam at the rate time is actually passing.
            // Only a SMALL overshoot glides: a wildly out-of-range fraction is a stale or
            // wrong-segment report, and flinging the playhead across the timeline on one of
            // those would be far worse than the freeze this replaces.
            long overshootMs = 0;
            if (rawSourceMs > sd.outPointMs) {
                long overSourceMs = rawSourceMs - sd.outPointMs;
                if (overSourceMs <= MAX_BOUNDARY_GLIDE_SOURCE_MS) {
                    overshootMs = (long) (overSourceMs / sd.speed);
                }
            }
            // Convert from source position to trimmed position
            long localMs = Math.max(sd.inPointMs, Math.min(rawSourceMs, sd.outPointMs))
                    - sd.inPointMs;
            // Adjust for speed
            localMs = (long)(localMs / sd.speed);
            playheadPositionMs = Math.min(totalEffectiveMs,
                    selectedSegmentStartMs + localMs + overshootMs);

            // Remember this index for playback continuation
            lastPlaybackIndex = playbackIndex;
            
            if (VLOG) FLog.d(TAG, "setPlayheadFraction: playheadPositionMs=" + playheadPositionMs);
            
            // Auto-scroll to keep playhead centered (always, not just when not dragging)
            centerPlayhead();
        }
        invalidate();
    }
    
    private long getSegmentStartTime(int index) {
        long time = 0;
        for (int i = 0; i < Math.min(index, segments.size()); i++) {
            time += segments.get(i).effectiveMs;
        }
        return time;
    }
    
    /**
     * Ms to subtract from {@link #playheadPositionMs} for DISPLAY ONLY — the device's audio
     * output latency, so the waveform sitting under the fixed centre line is the moment the
     * speaker is actually producing (SPEC_20260829_AUDIO_SYNC_TRUTH §3.4).
     *
     * <p><b>This is a draw-time shift and must never reach the model.</b> It was originally
     * implemented by writing the compensated value straight into {@code playheadPositionMs},
     * which silently corrupted every consumer of {@link #getPlayheadPositionMs()} —
     * segment-under-playhead lookup, snapping, and, worst, the audio drift lock, which then
     * measured a permanent error equal to the latency and spent playback fighting it. On a
     * device whose latency exceeded the 120ms desync threshold that became an endless
     * park/restart loop; below it, an endless micro-speed trim. JoyRaptor heard both, as
     * stuttering and as volume pumping (Note 20, 2026-08-29).
     *
     * <p>The line itself is painted at screen centre and the CONTENT scrolls beneath it, so
     * the whole correction is one question: which moment gets centred.</p>
     */
    private int playheadDrawOffsetMs = 0;

    /** Set the display-only latency shift. 0 when paused — nothing is being heard. */
    public void setPlayheadDrawOffsetMs(int ms) {
        int v = Math.max(0, ms);
        if (v == playheadDrawOffsetMs) return;
        playheadDrawOffsetMs = v;
        centerPlayhead();
        invalidate();
    }

    /** The playhead as the user should SEE and READ it. Never use this for model decisions. */
    private long drawnPlayheadMs() {
        return Math.max(0L, playheadPositionMs - playheadDrawOffsetMs);
    }

    private void centerPlayhead() {
        float centerX = getWidth() / 2f;
        float playheadX = timeToX(drawnPlayheadMs());
        scrollOffsetPx = playheadX - centerX;
        if (VLOG) FLog.d(TAG, "centerPlayhead: centerX=" + centerX + " playheadX=" + playheadX + " scrollOffset=" + scrollOffsetPx);
        clampScroll();
    }

    /**
     * Returns the effective end of the timeline in ms, accounting for both
     * video segments and audio clips that may extend beyond video.
     */
    public long getTimelineEndMs() {
        long end = totalEffectiveMs;
        for (AudioClip ac : audioClips) {
            end = Math.max(end, ac.getEndOnTimelineMs());
        }
        return end;
    }
    
    private void clampScroll() {
        if (contentWidthPx <= 0 || getWidth() <= 0) return;
        
        float centerX = getWidth() / 2f;
        long timelineEnd = getTimelineEndMs();
        
        // Strict bounds: prevent scrolling past timeline start (0ms) or end
        // minScroll: scroll offset when 0ms is at center
        // maxScroll: scroll offset when timelineEnd is at center
        float minScroll = edgePaddingPx - centerX;  // Allows 0ms to be centered
        float maxScroll = timeToX(timelineEnd) - centerX;  // Allows end to be centered
        
        scrollOffsetPx = Math.max(minScroll, Math.min(scrollOffsetPx, maxScroll));
    }

    public void setTrimFromClip(@NonNull Clip clip) {
        if (selectedIndex >= 0 && selectedIndex < segments.size()) {
            segments.set(selectedIndex, new SegmentData(selectedIndex, clip));
            totalEffectiveMs = 0;
            for (SegmentData sd : segments) totalEffectiveMs += sd.effectiveMs;
            computeRects();
            clampScroll();
            requestLayout();
            invalidate();
        }
    }

    public float getTrimStartFraction() {
        if (selectedIndex < 0 || selectedIndex >= segments.size()) return 0f;
        SegmentData sd = segments.get(selectedIndex);
        return sd.sourceDurationMs > 0 ? (float) sd.inPointMs / sd.sourceDurationMs : 0f;
    }

    public float getTrimEndFraction() {
        if (selectedIndex < 0 || selectedIndex >= segments.size()) return 1f;
        SegmentData sd = segments.get(selectedIndex);
        return sd.sourceDurationMs > 0 ? (float) sd.outPointMs / sd.sourceDurationMs : 1f;
    }

    // ══════════════════════════════════════════════════════════════════
    //  LAYOUT
    // ══════════════════════════════════════════════════════════════════

    /**
     * Assign each audio clip to a stacked lane so that clips overlapping in time get
     * their OWN row (the user's "music + narration on separate tracks" ask). Greedy
     * first-fit by start time; {@code audioClipLanes} is parallel to {@code audioClips}
     * and {@code audioLaneCount} is the number of rows needed (≥1).
     */
    private void recomputeAudioLanes() {
        int n = audioClips.size();
        audioClipLanes = new int[n];
        audioLaneCount = 1;
        if (n == 0) return;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) ->
                Long.compare(audioClips.get(a).getOffsetMs(), audioClips.get(b).getOffsetMs()));
        long[] laneEnd = new long[n]; // timeline end-ms of the last clip placed in each lane
        int lanes = 0;
        for (Integer idx : order) {
            AudioClip ac = audioClips.get(idx);
            long start = ac.getOffsetMs();
            long end = ac.getEndOnTimelineMs();
            int placed = -1;
            for (int L = 0; L < lanes; L++) {
                if (start >= laneEnd[L]) { placed = L; break; }
            }
            if (placed < 0) { placed = lanes; lanes++; }
            laneEnd[placed] = end;
            audioClipLanes[idx] = placed;
        }
        audioLaneCount = Math.max(1, lanes);
    }

    /**
     * SPEC_V §1/§3 — THE SPINE GEOMETRY {@link #segRects} WAS LAST BUILT FROM.
     *
     * <p>Every spine rect, the selection chrome, the context guides and the filmstrip tiling
     * derive from {@code segRects}, and {@code segRects} was only ever rebuilt from
     * {@code onSizeChanged} — i.e. it relied on a collapse CHANGING THIS VIEW'S HEIGHT.
     * SPEC_U §4 is exactly the change that stopped that being true: the height the spine
     * gives up is handed to the layer band, so the view measures the SAME total and
     * {@code onSizeChanged} never fires. The rects then kept the old top/height until some
     * unrelated re-feed rebuilt them — which is why the tape stayed squished "until you
     * scroll past a clip border" (a scroll that changes the selected clip re-feeds
     * {@code setTimeline}, and that calls {@code computeRects}).</p>
     */
    private float segRectsSpineTopPx = Float.NaN;
    private float segRectsSpineHeightPx = Float.NaN;

    /**
     * SPEC_V §3 — rebuild {@link #segRects} iff the spine's band geometry has moved under
     * them. Two float compares per frame, and a recompute ONLY on a real change: deliberately
     * not a blanket per-draw invalidate, which would re-lay-out (and re-extract) continuously
     * on a long project. Catches every height-changing path, not just the collapse caret —
     * the layer band growing, the audio drawer sliding, a grab-bar drag.
     */
    private void ensureSegRectsFresh() {
        if (segments.isEmpty()) return;
        if (segRects.size() != segments.size()
                || Float.isNaN(segRectsSpineTopPx)
                || Math.abs(masterContentTopPx() - segRectsSpineTopPx) > 0.5f
                || Math.abs(spineTrackHeightPx() - segRectsSpineHeightPx) > 0.5f) {
            computeRects();
        }
    }

    /** Total vertical span of the (possibly multi-lane) audio track in px. */
    private float audioTrackTotalHeightPx() {
        return audioLaneCount * audioTrackHeightPx
                + (audioLaneCount - 1) * audioLaneGapPx;
    }

    private void computeRects() {
        segRects.clear();
        if (segments.isEmpty()) {
            contentWidthPx = 0;
            segRectsSpineTopPx = Float.NaN;
            segRectsSpineHeightPx = Float.NaN;
            return;
        }
        float tTop = masterContentTopPx();
        // SPEC_V §1/§3 — stamp the spine geometry these rects were built from, so
        // ensureSegRectsFresh() can tell a stale set from a current one in O(1).
        segRectsSpineTopPx = tTop;
        segRectsSpineHeightPx = spineTrackHeightPx();
        float x = edgePaddingPx;

        for (int i = 0; i < segments.size(); i++) {
            SegmentData sd = segments.get(i);
            // UNIFORM TIME AXIS (user 2026-07-27): width is strictly proportional to duration and
            // clips abut with NO positioning gap, so 1s = the same pixels everywhere and timeToX/
            // xToTime are linear. This removes the old min-width clamp + inter-clip gap that made
            // the scale non-uniform (which warped moving overlays and threw off Start/End-here).
            // Short clips are now genuinely narrow — you zoom in to work with them. Clips are kept
            // visually separated by a DISPLAY-ONLY inset in drawSegment (never in the time mapping).
            float segW = (sd.effectiveMs / 1000f) * dpPerSecondPx;
            segRects.add(new RectF(x, tTop, x + segW, tTop + spineTrackHeightPx()));
            x += segW;
        }
        contentWidthPx = x + edgePaddingPx;

        // Thumbnails are loaded lazily for ON-SCREEN segments only (see onDraw).
        // Eagerly extracting frames for every clip here caused heavy background
        // MMR work + unbounded bitmap retention (memory pressure → GC stalls →
        // ANR) once a project had many clips.

        // Compute audio clip rectangles (stacked into lanes so overlapping clips
        // — e.g. music under narration — get their own row instead of piling up).
        audioClipRects.clear();
        recomputeAudioLanes();
        if (!audioClips.isEmpty() && totalEffectiveMs > 0) {
            float audioTop = audioBandTopPx();
            for (int i = 0; i < audioClips.size(); i++) {
                AudioClip ac = audioClips.get(i);
                float clipStartX = edgePaddingPx
                        + (ac.getOffsetMs() / 1000f) * dpPerSecondPx;
                // Uniform axis: audio width is proportional too (no min-width clamp).
                float clipW = (ac.getTrimmedDurationMs() / 1000f) * dpPerSecondPx;
                float top = audioTop
                        + audioClipLanes[i] * (audioTrackHeightPx + audioLaneGapPx);
                RectF audioRect = new RectF(
                        clipStartX, top,
                        clipStartX + clipW, top + audioTrackHeightPx);
                audioClipRects.add(audioRect);
                // Extend contentWidthPx if audio extends beyond video
                contentWidthPx = Math.max(contentWidthPx, audioRect.right + edgePaddingPx);
            }
        }
    }

    /**
     * SPEC_U 4 - every px of this view whose height is NOT negotiable on a given pass: the
     * minimap + ruler strip, the spine's own band (at its collapsed-or-not height, rails
     * included), the divider gap above the spine, the transcript tail, the open clip-audio
     * drawer, the audio band's top gap and the legacy in-strip overlay rows.
     *
     * <p>This exists so ONE expression feeds both the measure pass and the band budget. It is
     * arranged to mirror the band-geometry accessors term for term - {@code masterTopPx} adds
     * the divider gap only when the floating band actually has a footprint, so this adds it on
     * exactly the same condition. It used to add that gap whenever there were audio tracks
     * TOO, which on an audio-only project reserved 6dp that nothing ever drew: the same
     * measure-vs-layout mismatch SPEC_N 2 found above the spine, in a second place.</p>
     */
    private float chromeHeightPx() {
        float px = minimapHeightPx + RULER_HEIGHT_DP * density
                + filmRailPx() + spineTrackHeightPx() + filmRailPx();
        if (!layerTracks.isEmpty()) px += LAYER_TOP_GAP_DP * density;   // masterTopPx's gap
        if (!audioLayerTracks.isEmpty() || !audioClips.isEmpty()) px += audioTrackGapPx;
        if (audioLayerTracks.isEmpty() && !audioClips.isEmpty()) {
            // Legacy in-strip audio lanes: fixed geometry, so they are chrome, not a band.
            px += (audioLaneCount * AUDIO_TRACK_HEIGHT_DP
                    + (audioLaneCount - 1) * AUDIO_LANE_GAP_DP) * density;
        }
        if (!segmentTranscripts.isEmpty()) px += 17f * density;
        px += clipAudioDrawerBandPx();
        // Captions share ONE track row (sequential clips don't overlap), like a real caption track.
        int legacyRows = overlays.size() + waveformLayers.size() + (captionSpans.isEmpty() ? 0 : 1);
        if (legacyRows > 0) {
            px += (LAYER_TOP_GAP_DP + legacyRows * (LAYER_ROW_HEIGHT_DP + LAYER_ROW_GAP_DP) + 6f)
                    * density;
        }
        return px;
    }

    /**
     * SPEC_U 4 - ADAPTIVE VERTICAL SPACE. JoyRaptor shoots vertical video, so screen height is
     * the scarcest thing in this app, and a collapsed region that leaves a grey hole instead of
     * handing its height to a neighbour is a straight waste of it.
     *
     * <p><b>The rule, in one paragraph.</b> Chrome and the spine take what they need first -
     * the spine keeps its full band unless the user explicitly collapsed it, because it is the
     * primary track and a stack of layers must not be able to squeeze it away. Whatever height
     * is left over is the pool the two flexible bands share. Each asks for what its rows
     * actually need (rows x row height, collapsed rows asking only for their strip). If both
     * fit, both get exactly their want and NOTHING is reserved for a region that does not want
     * it - which is what makes collapsing the audio band, or the spine, hand real height to the
     * layer lanes instead of leaving dead grey. If they do not both fit, the shortfall lands on
     * the FLOATING band, because it is the one that scrolls: a row pushed out of it can still
     * be reached by dragging, whereas an audio row pushed out of the audio band (which does not
     * scroll) would simply be gone. Neither band is ever taken below one usable row while it
     * has content.</p>
     *
     * <p>The budget is set BEFORE anything is reserved, and both the reservation and the
     * drawing read it back through the renderer, so the measure pass and the layout pass
     * cannot drift apart - the SPEC_N 2 trap, which is precisely what manufactures "negative
     * space". Growing a band cannot teleport a mid-scroll position either: the band's scroll
     * offset is never reset here, only re-clamped, and a band that grows can only ever REDUCE
     * how far it is scrolled.</p>
     */
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // The pre-SPEC_U "squeeze" (the whole deficit dumped on the floating band) is
        // superseded by the budget below, which knows about both bands and their floors.
        // Zeroed so a stale value from an earlier pass can never leak into the fallback path.
        layerRowRenderer.setViewportSqueezePx(0f);
        layerRowRenderer.setBandGrantsPx(0f, 0f);   // ask for the UNGRANTED wants

        // -- 1. What the regions want -------------------------------------------------
        final float chromePx = chromeHeightPx();
        final float layerWantPx = layerRowRenderer.measureLayerContentPx(layerTracks);
        final float audioWantPx = layerRowRenderer.measureAudioContentPx(audioLayerTracks);

        // -- 2. How tall this view would therefore like to be -------------------------
        //
        // THE GRAB BAR RESIZES THE TIMELINE, NOT JUST THE LAYER BAND. Everything above sizes
        // this view from its CONTENT; the cap the user dragged is also a FLOOR on the whole
        // view, which is what makes the control do what it says ("drag up -> taller timeline,
        // smaller preview") on a sparse project where the band alone had nothing to grow into,
        // and what lets the preview collapse far enough to promote to a pop-out. Only a USER
        // drag can push that cap past the content, so nothing else can ratchet it open.
        final float grabFloorPx = layerRowRenderer.getMaxVisibleRowsDp() * density;
        final float floorPx = Math.max(grabFloorPx, BASE_TIMELINE_DP * density);
        //
        // THE COLLAPSE DIVIDEND — the heart of SPEC_U §4. The grab-bar cap bounds the LAYER
        // band; the audio band and the spine are outside it. So when the user collapsed the
        // audio band, the height it stopped needing came off the view's total and went back to
        // the PREVIEW — which, on a vertical video, cannot use it: it becomes exactly the
        // "negative space below the spine" JoyRaptor reported. Handing that height to the
        // layer band instead is what he actually asked for ("those things come down to make
        // room for more lanes"), and the amount is the slack the collapse created, asked of
        // the rows themselves rather than remembered from a previous layout.
        final float freedPx = layerRowRenderer.audioCollapseSlackPx(audioLayerTracks)
                + spineCollapseSlackPx();
        final float layerCapPx = grabFloorPx + freedPx;
        int defH = (int) Math.ceil(Math.max(
                chromePx + Math.min(layerWantPx, layerCapPx) + audioWantPx, floorPx));
        final int h = resolveSize(defH, hSpec);

        // -- 3. Hand the space out ----------------------------------------------------
        final float flexPx = Math.max(0f, h - chromePx);
        final float layerMinPx = layerTracks.isEmpty()
                ? 0f : layerRowRenderer.minBandHeightPx(true);
        final float audioMinPx = audioLayerTracks.isEmpty()
                ? 0f : layerRowRenderer.minBandHeightPx(false);
        // Audio first, because it cannot scroll - but never at the cost of the layer band's
        // last row, and never more than it wants (a collapsed audio band wants only its strip,
        // and the remainder therefore flows to the lanes above).
        final float audioGrantPx = audioLayerTracks.isEmpty() ? 0f
                : Math.min(audioWantPx, Math.max(audioMinPx, flexPx - layerMinPx));
        final float layerGrantPx = layerTracks.isEmpty() ? 0f
                : Math.max(layerMinPx, Math.min(layerWantPx, flexPx - audioGrantPx));
        layerRowRenderer.setBandGrantsPx(layerGrantPx, audioGrantPx);

        // -- 4. Measure what the bands actually took ----------------------------------
        // Read back THROUGH the renderer (not from the local grants) so this number is the
        // same one layout() will draw with, by construction.
        float usedPx = chromePx
                + layerRowRenderer.measureExtraHeightPx(layerTracks)
                + layerRowRenderer.measureAudioBandHeightPx(audioLayerTracks);
        // Never shrink below what the user's own grab-bar drag asked for, never claim height
        // the parent did not offer, and never squeeze the floors away: when even the two
        // minimums do not fit (a phone-height edge case) usedPx wins and the parent clips,
        // which is what already happened before, only now with the floors intact.
        int finalH = (int) Math.ceil(Math.max(usedPx, Math.min(floorPx, h)));
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, finalH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeRects();

        // Center timeline on current playhead position when view size changes
        if (!segments.isEmpty()) {
            centerPlayhead();
        }
    }

    // ── G6 resizable timeline (contract §5) ─────────────────────────────
    /**
     * Set the layer-band viewport cap (dp) from the preview/timeline grab bar and relayout. The band
     * grows/shrinks inside this cap, changing this view's measured height, which — because the preview
     * (player_container) is {@code layout_weight=1} — reflows the split: taller band ⇒ more rows visible
     * + smaller preview; shorter band ⇒ bigger preview. Returns the clamped value actually applied.
     */
    public float setLayerBandMaxHeightDp(float dp) {
        return setLayerBandMaxHeightDp(dp, /* clampToContent = */ true);
    }

    /**
     * As {@link #setLayerBandMaxHeightDp(float)}, but pass {@code false} for a cap the user
     * asked for by dragging the grab bar — see
     * {@link com.fadcam.ui.faditor.layers.LayerRowRenderer#setMaxVisibleRowsDp(float, boolean)}
     * for why a drag must not be clamped to the rows' own height.
     */
    public float setLayerBandMaxHeightDp(float dp, boolean clampToContent) {
        float applied = layerRowRenderer.setMaxVisibleRowsDp(dp, clampToContent);
        requestLayout();
        invalidate();
        return applied;
    }

    /** Current layer-band viewport cap (dp) — for the grab-bar drag baseline + persistence. */
    public float getLayerBandMaxHeightDp() { return layerRowRenderer.getMaxVisibleRowsDp(); }

    /** Default band cap (dp) — seed value when nothing is persisted yet. */
    public float getLayerBandDefaultMaxHeightDp() { return layerRowRenderer.getDefaultMaxVisibleRowsDp(); }

    // ══════════════════════════════════════════════════════════════════
    //  DRAWING
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        // Reorder mode draws its own UI over everything
        if (isReorderMode) {
            drawReorderMode(canvas);
            return;
        }

        // KineMaster lane (JoyRaptor 2026-07-19): retarget the playhead/chip colour animation
        // to the current editing context. Cheap no-op when the context is unchanged.
        updatePlayheadContextColor();

        int w = getWidth();
        // SPEC_N §3: one read per frame — every band-geometry accessor below already routes
        // through isSpineCollapsed(), this local only gates the DRAWING of spine chrome.
        final boolean spineCollapsed = isSpineCollapsed();
        // SPEC_V §3: the rects must describe the band we are about to draw into. Cheap
        // no-op unless the spine's top/height actually moved since they were built.
        ensureSegRectsFresh();
        float tTop = masterTopPx();
        float tBot = masterBotPx();
        // Reserve the transcript-row space below the segments (matches onMeasure) so the audio track /
        // waveform doesn't ride up over the inline transcript words.
        float audioTop = audioBandTopPx();
        float audioBot = audioBandBotPx();

        // Backgrounds
        canvas.drawRect(0, 0, w, rulerHeightPx, rulerBgPaint);
        canvas.drawRect(0, tTop, w, tBot, trackBgPaint);
        if (!audioClips.isEmpty()) {
            canvas.drawRect(0, audioTop, w, audioBot, audioTrackBgPaint);
        }

        if (segments.isEmpty() || segRects.isEmpty()) return;

        // Apply scroll offset
        canvas.save();
        canvas.translate(-scrollOffsetPx, 0);
        
        drawRuler(canvas, w);

        // §3A.5b — THE ARMED LIFT. Hold alone changes nothing functionally, so without a visible
        // cue the user has no way to learn "now pull up" and the gesture reads as broken. Drawn
        // UNDER the segments so it reads as the clip lifting off the track rather than as an
        // overlay on top of it.
        if (!spineCollapsed) drawDislodgeArmedLift(canvas);

        // Ghost trim: drawn first so neighbouring segments cover it
        if (!spineCollapsed && selectedIndex >= 0 && selectedIndex < segRects.size()) {
            drawTrimGhosts(canvas, selectedIndex);
        }

        // Clip-audio drawers: open shelves under their master clips (content space — they
        // scroll/trim/reorder WITH their clip since geometry derives from segRects). Drawn
        // BEFORE the segment loop so a drawer-open clip's transcript (which slides down to
        // the drawer's inside bottom, painted by drawSegment) lands ON TOP of the shelf.
        if (!spineCollapsed) drawClipAudioDrawers(canvas);

        // Cull segments outside the visible viewport. onDraw runs on every frame
        // of a continuous trim/scroll drag; drawing (and lazily loading thumbnails
        // for) every clip — including off-screen ones — starved input dispatch and
        // produced "not responding" ANRs once a project had many clips. Visible
        // window in content coordinates is [scrollOffsetPx, scrollOffsetPx + w].
        final float visLeft = scrollOffsetPx;
        final float visRight = scrollOffsetPx + w;
        if (spineCollapsed) {
            // SPEC_N §3: collapsed = a thin strip that still shows WHERE the cuts are.
            // SPEC_V §2 supersedes the "no thumbnails while collapsed" half of that: the
            // tape stays visible, extracted at the COLLAPSED height (see spineThumbCount).
            drawCollapsedSpine(canvas, visLeft, visRight);
        } else {
            for (int i = 0; i < segRects.size(); i++) {
                RectF segR = segRects.get(i);
                if (segR.right < visLeft || segR.left > visRight) continue;
                // Lazily extract thumbnails for segments as they scroll into view.
                loadThumbnailsForSegment(i);
                drawSegment(canvas, i);
            }

            // Master "filmstrip" frame (JoyRaptor 2026-07-06): drawn in content space (travels with the scroll)
            // and BEFORE the trim handles so the green handles paint on top of the film, not behind it.
            drawMasterFilmstrip(canvas, tTop, tBot, w);
        }

        // FADE_KNOBS §2.5: the dark fade veil goes UNDER the green trim bars (JoyRaptor: "the dark
        // shadowy triangle needs to be under the green trim handle") — same layer as the film.
        // SPEC_N §5: veils + knobs now require a DELIBERATE TAP on the segment (see
        // spineTapArmedClipId); a playhead-driven selection no longer shows them.
        if (spineFadeControlsVisible()) {
            drawMasterFadeVeils(canvas, segRects.get(selectedIndex));
        }

        // SPEC_N §3: trim handles, fade knobs and transitions are hidden while collapsed —
        // a control you cannot usefully hit is worse than no control.
        if (!spineCollapsed && selectedIndex >= 0 && selectedIndex < segRects.size()) {
            drawTrimHandles(canvas, segRects.get(selectedIndex));
            drawSlideFreezeHandles(canvas, segRects.get(selectedIndex));
        }

        // Audio clips ride the unified renderer rows (audio consolidation: audioLayerTracks
        // fed via setLayerTracks) — the renderer's audio band below master IS the audio UI.
        // The legacy in-strip audio bar (drawAudioTrack + its gesture path) was deleted
        // 2026-08-22 (SPEC_AUDIO_UX_V1 row A1): provably unreachable since getAudioTracks()
        // synthesizes a track for every AudioClip, and it carried a second, conflicting
        // long-press duration for the same hold gesture.

        // Transitions (fade/wipe/push bands between clips)
        if (!spineCollapsed) drawTransitions(canvas);

        // M6 hook: multi-row Track UI (pinned master above; extra layer/audio rows
        // below, with their own capped-height vertical scroll). No-op for a plain
        // single-track project (LayerRowRenderer#isEmpty). scrollOffsetPx is passed
        // through so the row HEADERS (name/caret/hide/lock/mute) stay pinned to the
        // left edge of the viewport like the rest of the timeline's left gutter,
        // while item bodies stay in content-space so they line up with timeToX.
        // M10: while a cross-row MOVE drag is active, layout() also lays out + draws the
        // "drop here to create a new layer" zone below the last row.
        // Stage 2 (PLAN §6): pass the gesture controller's selectedItemId through so
        // layout() can draw the selection stroke on the tapped/dragged item's body.
        tickLaneOpen();
        layerRowRenderer.setAudioCrossfades(
                liveTimeline != null ? liveTimeline.getAudioCrossfades() : null);
        layerRowRenderer.layout(canvas, layerTracks, audioLayerTracks, getM6RowsTopPx(),
                audioBandTopPx(), w,
                scrollOffsetPx, totalEffectiveMs, this::timeToX,
                layerGestureController != null && layerGestureController.isMoveDragActive(),
                layerGestureController != null ? layerGestureController.getSelectedItemId() : null);

        // Text-box timing carets — AFTER layout(), because layout() is what establishes the row
        // geometry itemBodyRect reads, and because they must paint over the item body rather than
        // under it. Still inside the translate: the carets are content-x / screen-y, which is
        // exactly this space (see the block comment on drawTextAnimHandles).
        // Audio tapes carry their words too — see drawAudioTranscripts. Drawn here, in the same
        // content-x / screen-y space as the handles below, and AFTER layout() because that is
        // what establishes the row geometry itemBodyRect reads.
        drawAudioTranscripts(canvas, totalEffectiveMs);
        drawTextAnimHandles(canvas);
        // Motion-range window carrots — after the amber carets so the purple window reads as
        // wrapping them (it does: the window is where the whole entrance+exit tape runs).
        drawMotionRangeHandles(canvas);

        // SPEC_N §4 — FADE KNOBS ON TOP OF EVERY LAYER ROW. JoyRaptor: "I add an adjustment layer,
        // and I notice that the adjustment layer is covering up the fade slider on the main
        // spine ... They should be on top of all the layers." The knobs used to draw just after
        // drawTransitions() and therefore BEFORE layerRowRenderer.layout(), so any lane
        // overlapping the 22dp the knobs float into painted straight over them.
        //
        // Both pre-existing ordering constraints still hold, and both are still JoyRaptor's:
        //   • the VEIL stays UNDER the green trim bars — it is untouched, still drawn back at
        //     the filmstrip, and it is clipped to the segment rect so no lane can cover it;
        //   • the KNOBS stay ABOVE the transition bands — drawTransitions() runs earlier still.
        // This is the same content-space translate the knobs were drawn in before, so their
        // geometry is unchanged; only the paint order moved.
        if (spineFadeControlsVisible()) {
            drawMasterFadeKnobs(canvas, segRects.get(selectedIndex));
        }

        // G8: marquee multi-selection highlights + the live selection box — content-x
        // space, so they ride the same translate as the rows themselves.
        if (!marqueeSelectedIds.isEmpty()) {
            layerRowRenderer.drawMultiSelection(canvas, marqueeSelectedIds, getM6RowsTopPx(),
                    totalEffectiveMs, this::timeToX);
        }
        drawMarqueeBox(canvas);

        canvas.restore();

        // ⚠ BOTH of these draw in SCREEN space and must be OUTSIDE the scroll translate, and
        // AFTER it, for two independent reasons found by screenshotting a live drag:
        //   1. Inside the translate the canvas is already shifted by -scrollOffsetPx, and both
        //      helpers convert content→screen themselves — so everything was offset TWICE and
        //      landed correctly only at scroll 0.
        //   2. They were drawn BEFORE the segments, so the filmstrip painted straight over them.
        //      The §3A.4 seam line was therefore invisible in every real drag; the drop LOGIC was
        //      right, the probe agreed with it, and nobody was looking at the pixels. A feature
        //      can be verified end-to-end by its data and still not exist on screen.
        drawSpineDropIndicator(canvas);
        drawCarry(canvas);
        // SPEC W §5 — the dwell's visible tell: while the excursion is ARMED (dwell
        // pending, pan not yet fired), a dashed accent line marks the joint the view
        // is ABOUT to reveal, so the pan is never a surprise. Screen space, same
        // reason as the seam/carry indicators above.
        drawPendingExcursionTell(canvas);

        // SPEC_N §3: the spine's collapse caret. SCREEN space and pinned to the left gutter at
        // exactly the x every lane row's caret uses, so the spine's control reads as one more
        // row header rather than a new invention.
        drawSpineCollapseCaret(canvas, tTop, masterBotPx());

        // Draw fixed center playhead (NOT affected by scroll)
        float playheadBot = !audioClips.isEmpty() ? audioBot : tBot;
        drawCenterPlayhead(canvas, tTop, playheadBot);

        // Minimap strip on top (screen coords, not scrolled)
        // The selected object's span is recorded by the row renderer as it draws, and read
        // back when the playhead is drawn later in this same pass. Clearing it here means a
        // frame in which nothing is selected cannot inherit the last frame's answer.
        if (layerRowRenderer != null) layerRowRenderer.clearSelectionSpan();
        drawMinimap(canvas, w);
        // Keep the minimap loading meters animating while anything is mid-load —
        // throttled repaint that stops itself once every meter reads complete.
        if (minimapMetersAnimating) postInvalidateDelayed(120);

        if (assetDragActive) {
            drawAssetDragPreview(canvas, w);
            drawTransitionDragPreview(canvas, w);
        }

        // L3: live numeric readout while dragging a loop-extension edge (screen space,
        // on top of all — same reasoning as the trim-edge preview bubble below).
        if (loopReadoutActive && (activeDrag == Drag.LEFT_HANDLE || activeDrag == Drag.RIGHT_HANDLE)) {
            drawLoopExtensionReadout(canvas, w, tTop, tBot);
        }

        // SPEC_IMAGE_SEQUENCE §9c: live "24 frames · 4.0s · 6.0 fps" while an image sequence's
        // edge is being dragged. Mirrors the loop-resize readout above, deliberately: §9b
        // decided AGAINST per-frame absolute pinning partly because eyeballing against the
        // tape already serves music timing — this is what makes that eye exact.
        drawSequenceResizeReadout(canvas, w, tTop, tBot);

        // Frame-accurate trim-edge preview bubble (screen space, on top of all).
        // (Trim-edge preview now happens in the main video, not as a finger-blocking
        // bubble here — drawTrimEdgePreview is retained but no longer activated.)

        // SPEC_U §3: a reveal that arrived before its row existed. The band has now laid out
        // (the M6 hook above ran layout()), so the row geometry is real — retry it once, off
        // this draw. Cleared unconditionally first: if the row STILL is not there the request
        // is dropped rather than re-posting forever.
        if (pendingRevealItemId != null) {
            String id = pendingRevealItemId;
            pendingRevealItemId = null;
            if (layerRowRenderer.hasRowForItem(id)) post(() -> revealLayerRowForItem(id));
        }
    }

    private final Paint seqReadoutBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seqReadoutTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** §9c — the live frame-count / duration / fps readout during a sequence edge drag. */
    private void drawSequenceResizeReadout(@NonNull Canvas canvas, int viewW,
                                           float trackTop, float trackBot) {
        if (layerGestureController == null) return;
        String text = layerGestureController.sequenceResizeReadout();
        if (text == null || text.isEmpty()) return;
        seqReadoutTextPaint.setColor(Studio.INK);
        seqReadoutTextPaint.setTextSize(13f * density);
        seqReadoutTextPaint.setTypeface(Typeface.MONOSPACE);
        seqReadoutBgPaint.setColor(0xF017171C);
        float padX = 10f * density, padY = 6f * density;
        float tw = seqReadoutTextPaint.measureText(text);
        float bw = tw + padX * 2, bh = 15f * density + padY * 2;
        float left = Math.max(2f * density, Math.min((viewW - bw) / 2f, viewW - bw - 2f * density));
        float top = Math.max(2f * density, trackTop - bh - 10f * density);
        RectF box = new RectF(left, top, left + bw, top + bh);
        canvas.drawRoundRect(box, 6f * density, 6f * density, seqReadoutBgPaint);
        canvas.drawText(text, left + padX, top + padY + 12f * density, seqReadoutTextPaint);
    }

    private final Paint trimPreviewBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trimPreviewBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trimPreviewLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Floating bubble showing the exact in/out frame of the clip being trimmed,
     * anchored over the active handle. Drawn in screen space so it is never
     * clipped by the scrolled content and always tracks the finger.
     */
    private void drawTrimEdgePreview(@NonNull Canvas canvas, int viewW, float trackTop, float trackBot) {
        Bitmap bmp = trimEdgePreviewBitmap;
        // Handle position in screen space (trimDragX is content-space).
        float handleScreenX = trimDragX - scrollOffsetPx;

        float pad = 4f * density;
        float bw, bh;
        if (bmp != null && !bmp.isRecycled()) {
            bw = bmp.getWidth();
            bh = bmp.getHeight();
        } else {
            // Placeholder box while the first frame decodes.
            bw = bh = trackHeightPx * 1.4f;
        }
        float boxW = bw + pad * 2;
        float boxH = bh + pad * 2;

        // Horizontally centre on the handle, clamped to stay fully on screen.
        float cx = handleScreenX;
        float left = cx - boxW / 2f;
        left = Math.max(2f * density, Math.min(left, viewW - boxW - 2f * density));
        // Sit just above the track; if it would run off the top, drop below instead.
        float top = trackTop - boxH - 8f * density;
        boolean below = top < 2f * density;
        if (below) top = trackBot + 8f * density;
        RectF box = new RectF(left, top, left + boxW, top + boxH);

        trimPreviewBgPaint.setColor(0xF0000000);
        trimPreviewBorderPaint.setStyle(Paint.Style.STROKE);
        trimPreviewBorderPaint.setStrokeWidth(1.5f * density);
        trimPreviewBorderPaint.setColor(Studio.GO);
        float corner = 6f * density;
        canvas.drawRoundRect(box, corner, corner, trimPreviewBgPaint);

        if (bmp != null && !bmp.isRecycled()) {
            canvas.drawBitmap(bmp, left + pad, top + pad, null);
        }
        canvas.drawRoundRect(box, corner, corner, trimPreviewBorderPaint);

        // Label: which edge + exact timecode (frame-accurate position in source).
        trimPreviewLabelPaint.setColor(Studio.INK);
        trimPreviewLabelPaint.setTextSize(10f * density);
        trimPreviewLabelPaint.setTextAlign(Paint.Align.CENTER);
        String tag = (trimEdgePreviewIsLeft ? "IN " : "OUT ") + formatEdgeTimecode(trimEdgePreviewShownMs);
        float ty = below ? box.bottom + 12f * density : box.top - 4f * density;
        // Keep label readable with a subtle shadow.
        trimPreviewLabelPaint.setShadowLayer(2f * density, 0, 0, Studio.GROUND);
        canvas.drawText(tag, box.centerX(), ty, trimPreviewLabelPaint);
        trimPreviewLabelPaint.clearShadowLayer();

        // A little pointer line from the bubble to the handle.
        trimPreviewBorderPaint.setStrokeWidth(1.2f * density);
        float anchorY = below ? box.top : box.bottom;
        canvas.drawLine(Math.max(box.left, Math.min(handleScreenX, box.right)), anchorY,
                handleScreenX, below ? trackBot : trackTop, trimPreviewBorderPaint);
    }

    /**
     * L3: floating bubble showing the extension length (and, for looping modes, the
     * resulting rep count) while dragging a loop-extension edge past source bounds —
     * e.g. "+2.4s ~ 3 loops" or "+2.4s freeze" for STILL. Text-only sibling of
     * {@link #drawTrimEdgePreview}: SAME box/border/label paints and anchor-over-handle
     * + pointer-line layout, just sized to the label instead of a thumbnail bitmap.
     */
    private void drawLoopExtensionReadout(@NonNull Canvas canvas, int viewW, float trackTop, float trackBot) {
        if (selectedIndex < 0 || selectedIndex >= segments.size()) return;
        SegmentData sd = segments.get(selectedIndex);
        Clip clip = sd.clip;
        boolean isLeft = (activeDrag == Drag.LEFT_HANDLE);
        long extensionMs = isLeft ? trimDragLoopBefore : trimDragLoopAfter;
        if (extensionMs <= 0) return;

        String label = formatLoopReadoutLabel(clip, extensionMs);

        float handleScreenX = trimDragX - scrollOffsetPx;

        trimPreviewLabelPaint.setTextSize(12f * density);
        trimPreviewLabelPaint.setTextAlign(Paint.Align.CENTER);
        float textW = trimPreviewLabelPaint.measureText(label);

        float padH = 10f * density, padV = 7f * density;
        float boxW = textW + padH * 2;
        float boxH = 14f * density + padV * 2; // ~one text line at 12sp + vertical pad

        float cx = handleScreenX;
        float left = cx - boxW / 2f;
        left = Math.max(2f * density, Math.min(left, viewW - boxW - 2f * density));
        float top = trackTop - boxH - 8f * density;
        boolean below = top < 2f * density;
        if (below) top = trackBot + 8f * density;
        RectF box = new RectF(left, top, left + boxW, top + boxH);

        trimPreviewBgPaint.setColor(0xF0000000);
        trimPreviewBorderPaint.setStyle(Paint.Style.STROKE);
        trimPreviewBorderPaint.setStrokeWidth(1.5f * density);
        trimPreviewBorderPaint.setColor(Studio.GO);
        float corner = 6f * density;
        canvas.drawRoundRect(box, corner, corner, trimPreviewBgPaint);
        canvas.drawRoundRect(box, corner, corner, trimPreviewBorderPaint);

        trimPreviewLabelPaint.setColor(Studio.INK);
        trimPreviewLabelPaint.setShadowLayer(2f * density, 0, 0, Studio.GROUND);
        canvas.drawText(label, box.centerX(), box.centerY() + 4.5f * density, trimPreviewLabelPaint);
        trimPreviewLabelPaint.clearShadowLayer();

        trimPreviewBorderPaint.setStrokeWidth(1.2f * density);
        float anchorY = below ? box.top : box.bottom;
        canvas.drawLine(Math.max(box.left, Math.min(handleScreenX, box.right)), anchorY,
                handleScreenX, below ? trackBot : trackTop, trimPreviewBorderPaint);
    }

    /**
     * "+2.4s ≈ 3 loops" (NORMAL/PING_PONG — rep count mirrors ExportManager's exact
     * clamp formula, {@code ceil(extensionMs / trimmedPlayMs)}, so the readout never
     * disagrees with what export actually renders) or "+2.4s freeze" (STILL, which
     * has no rep concept — the whole extension is one held frame). "≈" matches the
     * existing effective-duration readout's own use of the glyph (activity ~6596).
     */
    @NonNull
    private String formatLoopReadoutLabel(@NonNull Clip clip, long extensionMs) {
        String secs = String.format(java.util.Locale.US, "+%.1fs", extensionMs / 1000f);
        if (clip.getLoopMode() == Clip.LOOP_MODE_STILL) {
            return secs + " freeze";
        }
        long trimmedPlayMs = clip.getTrimmedDurationMs();
        if (trimmedPlayMs <= 0) return secs;
        int reps = (int) Math.ceil(extensionMs / (double) trimmedPlayMs);
        return secs + " ≈ " + reps + (reps == 1 ? " loop" : " loops");
    }

    /** mm:ss.fff style timecode for the trim-edge label (source position). */
    @NonNull
    private String formatEdgeTimecode(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long mm = totalSec / 60;
        long ss = totalSec % 60;
        long mmm = ms % 1000;
        return String.format(java.util.Locale.US, "%d:%02d.%03d", mm, ss, mmm);
    }

    /**
     * Draw transitions as colored diagonal bands at clip boundaries.
     * Fades = blue, wipes = orange, pushes = purple. Labeled with type.
     */
    private void drawTransitions(@NonNull Canvas canvas) {
        if (transitions.isEmpty() || segments.isEmpty()) return;

        float pxPerMs = dpPerSecondPx / 1000f;
        float bandW = 6f * density;
        transitionLabelPaint.setTextSize(8f * density);
        transitionLabelPaint.setColor(Studio.INK);
        transitionLabelPaint.setTypeface(Typeface.DEFAULT_BOLD);

        for (int i = 0; i < transitions.size(); i++) {
            com.fadcam.ui.faditor.model.Transition t = transitions.get(i);
            if (t.clipIndex < 0 || t.clipIndex >= segments.size()) continue;
            RectF rect = getTransitionRect(i, bandW);
            if (rect == null) continue;
            float x = rect.left;
            float w = rect.width();
            float y0 = rect.top;
            float y1 = rect.bottom;

            int color;
            String label;
            if (t.isFade()) {
                color = 0x9922D3EE;
                label = t.type.name().contains("BLACK") ? "fade⬛" : "fade⬜";
            } else if (t.isWipe()) {
                color = 0x99FBBF24;
                label = "wipe" + t.getDirection().charAt(0);
            } else if (t.isPush()) {
                color = 0x99CC27FF;
                label = "push" + t.getDirection().charAt(0);
            } else if (t.isGlShader()) {
                color = 0x9922D3EE;
                label = "gl";
            } else {
                color = 0x9935F6BF;
                label = "dissolve";
            }
            // Chip-label honesty: the chip's drawable width is capped to the neighbor-clip seam
            // (maxSpan below, same clamp as getTransitionRect), but the MODEL can still hold a
            // longer durationMs (a shorter clip elsewhere un-clamps it later). Showing only the
            // clamped span silently hid the real stored value from JoyRaptor ("handles snap to whatever
            // they prefer"). When clamped, append "(of Xs)" so the stored value stays visible.
            // TODO(strings): externalize " (of %ss)" once the strings pass lands.
            int seamForLabel = Math.max(0, Math.min(t.clipIndex, segments.size() - 2));
            long leftDurForLabel = segments.get(seamForLabel).effectiveMs;
            long rightDurForLabel = segments.get(seamForLabel + 1).effectiveMs;
            long maxSpanForLabel = Math.max(1, Math.min(leftDurForLabel, rightDurForLabel));
            if (t.durationMs > maxSpanForLabel) {
                label = label + " " + formatTransitionDurationShort(maxSpanForLabel)
                        + " (of " + formatTransitionDurationShort(t.durationMs) + ")";
            }

            // Transition zone stripes: blue at 50% to match the blue selection
            // outline (type is still conveyed by the label).
            transitionPaint.setColor(COLOR_TRANSITION_SEL);
            transitionPaint.setAlpha(128);
            canvas.save();
            clipPath.reset();
            clipPath.addRect(x, y0, x + w, y1, Path.Direction.CW);
            canvas.clipPath(clipPath);

            float stripeH = 4f * density;
            for (float yy = y0 - w; yy < y1 + w; yy += stripeH * 2) {
                Path stripe = new Path();
                stripe.moveTo(x, yy);
                stripe.lineTo(x + w, yy + w);
                stripe.lineTo(x + w, yy + w + stripeH);
                stripe.lineTo(x, yy + stripeH);
                stripe.close();
                canvas.drawPath(stripe, transitionPaint);
            }

            if (t.fuzziness > 0) {
                transitionPaint.setAlpha((int)(t.fuzziness * 80));
                canvas.drawRect(x, y0, x + w, y1, transitionPaint);
                transitionPaint.setAlpha(255);
            }
            canvas.restore();

            if (i == selectedTransitionIndex) {
                int prevBorderColor = borderPaint.getColor();
                borderPaint.setColor(COLOR_TRANSITION_SEL);
                borderPaint.setStrokeWidth(2.5f * density);
                canvas.drawRoundRect(x, y0, x + w, y1, 4f * density, 4f * density, borderPaint);
                borderPaint.setStrokeWidth(borderWidthPx);
                borderPaint.setColor(prevBorderColor);
                drawTransitionTrimHandles(canvas, new RectF(x, y0, x + w, y1));
                drawTransitionHelper(canvas, t, rect);
            }

            if (w > transitionLabelPaint.measureText(label) + 4f * density) {
                canvas.save();
                canvas.clipRect(x, y0, x + w, y1);
                canvas.drawText(label, x + 2f * density,
                        y0 + transitionLabelPaint.getTextSize() + 2f * density,
                        transitionLabelPaint);
                canvas.restore();
            }
        }
    }

    @Nullable
    private RectF getTransitionRect(int transitionIndex, float fallbackBandW) {
        if (transitionIndex < 0 || transitionIndex >= transitions.size()) return null;
        com.fadcam.ui.faditor.model.Transition t = transitions.get(transitionIndex);
        int seam = Math.max(0, Math.min(t.clipIndex, segments.size() - 2));
        float seamX = timeToX(getSeamTimeMs(seam + 1));
        long leftDur = segments.get(seam).effectiveMs;
        long rightDur = segments.get(seam + 1).effectiveMs;
        long maxSpan = Math.max(1, Math.min(leftDur, rightDur));
        long spanMs = Math.max(1, Math.min(t.durationMs, maxSpan));
        float w = Math.max(fallbackBandW, spanMs * dpPerSecondPx / 1000f);
        float x = Math.max(edgePaddingPx, Math.min(seamX - w / 2f,
                segRects.get(segRects.size() - 1).right - w));
        return new RectF(x, segRects.get(Math.min(seam, segRects.size() - 1)).top,
                x + w, segRects.get(Math.min(seam, segRects.size() - 1)).bottom);
    }

    /** Short "1.6s" style formatting for the chip-label clamp indicator. */
    private String formatTransitionDurationShort(long ms) {
        return String.format(java.util.Locale.US, "%.1fs", ms / 1000f);
    }

    private long getSeamTimeMs(int seam) {
        if (seam <= 0) return 0;
        long cumul = 0;
        for (int i = 0; i < Math.min(seam, segments.size()); i++) {
            cumul += segments.get(i).effectiveMs;
        }
        return cumul;
    }

    // ══════════════════════════════════════════════════════════════════
    //  BAND GEOMETRY (Slice E) — single source of truth for every band's Y.
    //
    //  Every render + hit-test site that used to recompute the master/audio
    //  band bounds inline now routes through these accessors, so the vertical
    //  re-layout (Slice E step 2: overlays/layers ABOVE master, MASTER centered,
    //  AUDIO below) is a change to THESE FIVE METHODS ONLY — no scattered edits,
    //  no site left on the old geometry. Step 1 keeps the CURRENT order (master
    //  pinned at the ruler bottom) so the centralization is provably pixel-
    //  identical before the flip.
    // ══════════════════════════════════════════════════════════════════

    /**
     * Height (px) the M6 layer band occupies just below the ruler (Slice E: the
     * overlay/caption/visualizer/sprite rows now sit ABOVE the master track). Mirrors
     * {@code onMeasure}'s reservation so master lands exactly at the band's bottom edge.
     * Zero for a plain single-track project (no rows → no band → master stays at top).
     */
    private float m6BandFootprintPx() {
        // Audio consolidation: only the FLOATING band sits above master; audio-track rows
        // moved to their own band below master (see audioBandTopPx/audioBandHeightPx).
        return layerRowRenderer.measureExtraHeightPx(layerTracks);
    }

    /**
     * Top Y (px) of the MASTER video track band. Slice E (FEEDBACK #3): master is no
     * longer pinned at the ruler bottom — the nameless layer substrate (overlays / CC /
     * visualizers / sprites) sits ABOVE it, so master is pushed down below that band with
     * a divider gap, leaving it centered with AUDIO below. Master keeps its size
     * (56dp) which is already larger than a 34dp layer row, so it reads as the dominant
     * band (JoyRaptor 2026-07-06: "larger relative to the other layers", no resize needed).
     * Plain single-track projects (no layer band) keep master at the ruler bottom exactly
     * as before.
     */
    private float masterTopPx() {
        float band = m6BandFootprintPx();
        return band > 0f ? rulerHeightPx + band + LAYER_TOP_GAP_DP * density : rulerHeightPx;
    }

    /**
     * SPEC_N §3 — is the SPINE collapsed?
     *
     * <p>State lives in the project's existing per-track flags side-table under the master
     * track's own id ("master"), which {@code ProjectStorage} already serializes and already
     * OMITS when every flag is default. So this is sparse exactly as the spec requires:
     * absent = expanded, and a project that never collapses its spine re-saves byte-identically.
     * Nothing new was added to the storage schema.</p>
     */
    private boolean isSpineCollapsed() {
        if (liveTimeline == null) return false;
        com.fadcam.ui.faditor.layers.TrackFlags f = liveTimeline.getTrackFlags("master");
        return f != null && f.collapsed;
    }

    /**
     * SPEC_N §3 — height (px) of the master film content. Collapsing shrinks THIS one number
     * (and the rails below), which every rect, hit-test and measure already derives from, so
     * the collapse cannot leave a stale band behind. Playback, export and the spine's own
     * selection read none of this — collapsing is purely how tall the lane draws.
     */
    private float spineTrackHeightPx() {
        return isSpineCollapsed() ? SPINE_COLLAPSED_HEIGHT_DP * density : trackHeightPx;
    }

    /**
     * SPEC_U §4 — the height (px) the SPINE has given up by being collapsed: its full band
     * (rails + track) minus the thin strip it draws now, or 0 while it is expanded. The
     * audio-band twin of this is {@code LayerRowRenderer#audioCollapseSlackPx}; together they
     * are the "collapse dividend" the layer band is allowed to grow into (see onMeasure).
     * Note the spine can only give this up on the user's explicit say-so — nothing else
     * collapses it — which is how it keeps its floor as the primary track.
     */
    private float spineCollapseSlackPx() {
        if (!isSpineCollapsed()) return 0f;
        return (trackHeightPx + 2f * FILM_RAIL_DP * density) - SPINE_COLLAPSED_HEIGHT_DP * density;
    }

    /** Sprocket-rail thickness (px) reserved OUTSIDE the film content at the top &amp; bottom of the
     *  master band, so the perforations frame the thumbnails instead of covering them (JoyRaptor 2026-07-07). */
    private float filmRailPx() {
        // SPEC_N §3: a collapsed spine is a thin strip — no sprocket rails to frame a
        // filmstrip that is not drawn.
        return isSpineCollapsed() ? 0f : FILM_RAIL_DP * density;
    }

    /** Top Y (px) of the master FILM CONTENT (thumbnails) — below the top sprocket rail. */
    private float masterContentTopPx() {
        return masterTopPx() + filmRailPx();
    }

    /** Bottom Y (px) of the MASTER video track band, including the sprocket rail above &amp; below the film. */
    private float masterBotPx() {
        return masterTopPx() + filmRailPx() + spineTrackHeightPx() + filmRailPx();
    }

    /** Reserved vertical space (px) for the master transcript row below the tape.
     *  ZERO since 2026-07-14 (JoyRaptor): transcript words now live ONLY inside the open
     *  clip-audio drawer (bottom-aligned white); when the drawer is collapsed the words
     *  are already on the video preview as captions — so the old under-strip words row
     *  ("the gray middle bar") is reclaimed. Method kept so the band geometry reads
     *  the same at every call site. */
    private float transcriptReservePx() {
        return 0f;
    }

    /**
     * Current height (px) of the clip-audio drawer BAND — the shared slot below the
     * transcript reserve that open drawers slide into. All open drawers share one band
     * (their clips are disjoint in x), so the band height is the MAX open fraction × the
     * drawer height. 0 when every drawer is closed → the whole feature costs nothing.
     */
    private float clipAudioDrawerBandPx() {
        if (clipAudioDrawerFraction.isEmpty()) return 0f;
        float max = 0f;
        for (Float f : clipAudioDrawerFraction.values()) {
            if (f != null && f > max) max = f;
        }
        return max * CLIP_AUDIO_DRAWER_HEIGHT_DP * density;
    }

    /** Top Y (px) of the AUDIO band (below master + transcript reserve + any open clip-audio drawer). */
    private float audioBandTopPx() {
        return masterBotPx() + transcriptReservePx() + clipAudioDrawerBandPx() + audioTrackGapPx;
    }

    /** Bottom Y (px) of the AUDIO band. Renderer-derived when audio rides the unified
     *  renderer rows (audio consolidation); legacy lane stack otherwise. */
    private float audioBandBotPx() {
        return audioBandTopPx() + (audioLayerTracks.isEmpty()
                ? audioTrackTotalHeightPx()
                : layerRowRenderer.measureAudioBandHeightPx(audioLayerTracks));
    }

    // ── Clip-audio drawer: toggle + draw ─────────────────────────────────────

    /**
     * Open/close the clip's audio drawer with the slide animation. The animator drives the
     * clip's fraction 0↔1; every frame re-measures (the band below reflows) + redraws. On a
     * fully-closed end the entry is dropped so {@link #clipAudioDrawerBandPx} returns to 0.
     */
    private void toggleClipAudioDrawer(@NonNull String clipId) {
        boolean opening = !clipAudioDrawerOpen.contains(clipId);
        if (opening) clipAudioDrawerOpen.add(clipId); else clipAudioDrawerOpen.remove(clipId);

        android.animation.ValueAnimator old = clipAudioDrawerAnims.remove(clipId);
        if (old != null) old.cancel();

        Float cur = clipAudioDrawerFraction.get(clipId);
        float from = cur != null ? cur : (opening ? 0f : 1f);
        float to = opening ? 1f : 0f;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(from, to);
        va.setDuration((long) (CLIP_DRAWER_ANIM_MS * Math.abs(to - from)));
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(a -> {
            clipAudioDrawerFraction.put(clipId, (Float) a.getAnimatedValue());
            // Rects (legacy audio lane, content bounds) derive band tops at compute time —
            // refresh them per frame so everything below reflows with the slide even when
            // the parent grants a fixed height (onSizeChanged won't fire then).
            computeRects();
            requestLayout();
            invalidate();
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                clipAudioDrawerAnims.remove(clipId);
                if (!clipAudioDrawerOpen.contains(clipId)) {
                    clipAudioDrawerFraction.remove(clipId);
                }
                requestLayout();
                invalidate();
            }
        });
        clipAudioDrawerAnims.put(clipId, va);
        va.start();
        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
    }

    /**
     * Layer-wide audio-drawer toggle (JoyRaptor 2026-07-14): a double-tap on any master clip
     * expands/collapses the audio shelf for EVERY (non-image) clip on the strip, driven by the
     * tapped clip's target state — a per-clip shelf wasted the row for the other clips' audio.
     * Idempotent per clip: only clips not already at the target state animate.
     */
    private void toggleLayerAudioDrawers(@NonNull String tappedClipId) {
        boolean opening = !clipAudioDrawerOpen.contains(tappedClipId);
        for (SegmentData sd : segments) {
            if (sd.clipId == null || sd.isImageClip) continue;
            if (clipAudioDrawerOpen.contains(sd.clipId) != opening) {
                toggleClipAudioDrawer(sd.clipId);
            }
        }
    }

    /**
     * Carry the OPEN audio shelf across a timeline re-feed. The open state is keyed by CLIP ID,
     * and structural edits mint fresh ids — a split replaces the open parent with two closed
     * children, silently collapsing the shelf mid-edit (JoyRaptor 2026-07-18: "the split should just
     * cut through the film and the waveform"). The shelf is a LAYER-wide toggle in practice
     * ({@link #toggleLayerAudioDrawers}), so: if it was open at all, keep it open for every
     * (non-image) clip in the new timeline — instantly, fraction 1, no animation — and drop
     * state for ids that no longer exist.
     */
    private void reconcileClipAudioDrawers() {
        if (clipAudioDrawerOpen.isEmpty()) return; // shelf closed — nothing to carry
        java.util.Set<String> live = new HashSet<>();
        for (SegmentData sd : segments) {
            if (sd.clipId != null) live.add(sd.clipId);
        }
        // Prune dead ids (and kill their in-flight animators).
        for (java.util.Iterator<String> it = clipAudioDrawerOpen.iterator(); it.hasNext(); ) {
            String id = it.next();
            if (!live.contains(id)) {
                it.remove();
                clipAudioDrawerFraction.remove(id);
                android.animation.ValueAnimator anim = clipAudioDrawerAnims.remove(id);
                if (anim != null) anim.cancel();
            }
        }
        // Shelf was open (set was non-empty on entry) — hold it open for the whole layer.
        for (SegmentData sd : segments) {
            if (sd.clipId == null || sd.isImageClip) continue;
            if (clipAudioDrawerOpen.add(sd.clipId)) {
                clipAudioDrawerFraction.put(sd.clipId, 1f);
            }
        }
    }

    /** The segment index currently holding this clipId, or -1 (clip deleted/reordered away). */
    private int segmentIndexForClipId(@NonNull String clipId) {
        for (int i = 0; i < segments.size(); i++) {
            if (clipId.equals(segments.get(i).clipId)) return i;
        }
        return -1;
    }

    /** Paints for the drawer body + the master clip's volume rubber-band inside it. */
    private final Paint drawerBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint drawerEnvLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint drawerEnvDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean drawerPaintsInit;

    /**
     * Draw every open (or animating) clip-audio drawer. Content-space (inside the scroll
     * translate). LIVE-FOLLOW: each drawer's x-range is re-derived from its clip's CURRENT
     * segRect this frame, so scroll/trim/reorder/speed changes are followed with zero extra
     * state; a deleted clip's entry is pruned here. The slide is a clip-rect reveal: the
     * body translates up by the un-opened remainder, so it visually emerges from (and
     * retracts back under) the strip above like a shelf.
     */
    private void drawClipAudioDrawers(@NonNull Canvas canvas) {
        drawerAnalysisAnimating = false;
        if (clipAudioDrawerFraction.isEmpty()) return;
        if (!drawerPaintsInit) {
            drawerPaintsInit = true;
            drawerBodyPaint.setStyle(Paint.Style.FILL);
            drawerBodyPaint.setColor(Studio.SURFACE); // prototype tape background
            drawerEnvLinePaint.setColor(Studio.ARMED);
            drawerEnvLinePaint.setStyle(Paint.Style.STROKE);
            drawerEnvLinePaint.setStrokeWidth(1.6f * density);
            drawerEnvDotPaint.setColor(Studio.ARMED);
            drawerEnvDotPaint.setStyle(Paint.Style.FILL);
        }
        if (clipDrawerTapeCache == null) {
            clipDrawerTapeCache =
                    new com.fadcam.ui.faditor.waveform.TapeTileCache(density);
        }
        float bandTop = masterBotPx() + transcriptReservePx();
        float H = CLIP_AUDIO_DRAWER_HEIGHT_DP * density;

        java.util.Iterator<java.util.Map.Entry<String, Float>> it =
                clipAudioDrawerFraction.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Float> e = it.next();
            int segIdx = segmentIndexForClipId(e.getKey());
            if (segIdx < 0 || segIdx >= segRects.size()) {
                // Clip no longer exists (deleted / replaced id on split-right-half) — prune.
                android.animation.ValueAnimator anim = clipAudioDrawerAnims.remove(e.getKey());
                if (anim != null) anim.cancel();
                clipAudioDrawerOpen.remove(e.getKey());
                it.remove();
                continue;
            }
            float f = e.getValue() != null ? e.getValue() : 0f;
            float visibleH = f * H;
            if (visibleH < 1f) continue;
            RectF seg = segRects.get(segIdx);
            SegmentData sd = segments.get(segIdx);

            canvas.save();
            canvas.clipRect(seg.left, bandTop, seg.right, bandTop + visibleH);
            canvas.translate(0f, visibleH - H); // slide: emerge from under the strip
            float r = 3f * density;
            canvas.drawRoundRect(seg.left, bandTop, seg.right, bandTop + H, r, r,
                    drawerBodyPaint);

            com.fadcam.ui.faditor.waveform.BandedTimelineWaveformCache.Shaped tape =
                    (tapeWaveformCache != null && sd.sourceUri != null && !sd.isImageClip
                            && (sd.clip == null || !sd.clip.isGeneratedSlide()))
                            ? tapeWaveformCache.get(sd.sourceUri, sd.inPointMs, sd.outPointMs,
                                    sd.sourceDurationMs)
                            : null;
            if (tape != null) {
                // Tape stops CLIP_DRAWER_WORD_BAND_DP short of the drawer bottom — the dark
                // body shows through as a black word band so the transcript words sit BELOW
                // the waveform instead of on its centerline (JoyRaptor 2026-07-14).
                RectF body = new RectF(seg.left, bandTop, seg.right,
                        bandTop + H - CLIP_DRAWER_WORD_BAND_DP * density);
                String drawerKey = sd.sourceUri + "|" + sd.inPointMs + "-" + sd.outPointMs;
                // F2d: pass the visible content window so only on-screen tiles bake/blit
                // (canvas is scroll-translated ⇒ viewport = [scrollOffsetPx, +width]).
                clipDrawerTapeCache.draw(canvas, body, tape.raw, tape.tape, tape.serial, tapeStyle,
                        sd.inPointMs, Math.max(1, sd.trimmedMs), drawerKey,
                        scrollOffsetPx, scrollOffsetPx + getWidth());
            } else {
                // Extraction in flight (or failed) — "analyzing audio…" label + a slow sheen
                // sweep so the user can SEE work happening (JoyRaptor 2026-07-16: on a 45-min clip
                // the blank drawer read as broken). The label PINS to the viewport's left edge
                // while the tape's start is scrolled off-screen (trash-can-style), so it's
                // visible wherever the user is over the clip.
                // Canvas is translated by -scrollOffsetPx, so the viewport's left edge in
                // this (content) space is scrollOffsetPx — NOT 0 (round-2 pin fix).
                float labelX = Math.max(seg.left, scrollOffsetPx) + 6f * density;
                labelX = Math.min(labelX, Math.max(seg.left, seg.right - 90f * density));
                transcriptTextPaint.setTextSize(9f * density);
                int pulseA = (int) (0x66 + 0x2E
                        * Math.sin(android.os.SystemClock.uptimeMillis() / 320.0));
                transcriptTextPaint.setColor((pulseA << 24) | 0x00FFFFFF);
                canvas.drawText("analyzing audio…", labelX,
                        bandTop + H / 2f + 3f * density, transcriptTextPaint);
                transcriptTextPaint.setColor(0x99FFFFFF);
                drawAnalyzingSheen(canvas, seg.left, bandTop, seg.right, bandTop + H);
                drawerAnalysisAnimating = true;
            }

            // The clip's volume rubber-band (keyframes) rides ON TOP of the tape — same
            // visual as the audio rows' envelope, mapped clip-local over the trimmed span.
            if (sd.clip != null && sd.clip.hasVolumeKeyframes()) {
                @SuppressWarnings("unchecked")
                java.util.List<Clip.VolumeKeyframe> kfs = (java.util.List<Clip.VolumeKeyframe>) sd.clip.getVolumeKeyframes();
                long dur = Math.max(1, sd.trimmedMs);
                float w = seg.width();
                // Envelope maps over the TAPE region only (above the black word band).
                float tapeH = H - CLIP_DRAWER_WORD_BAND_DP * density;
                canvas.save();
                canvas.clipRect(seg.left, bandTop, seg.right, bandTop + tapeH);
                float prevX = 0f, prevY = 0f;
                for (int k = 0; k < kfs.size(); k++) {
                    Clip.VolumeKeyframe kf = kfs.get(k);
                    float fx = Math.max(0f, Math.min(1f, kf.timeMs / (float) dur));
                    float x = seg.left + fx * w;
                    float gFrac = Math.max(0f, Math.min(1f, kf.volume / 2.0f));
                    float y = (bandTop + tapeH) - gFrac * tapeH;
                    if (k == 0) {
                        canvas.drawLine(seg.left, y, x, y, drawerEnvLinePaint);
                    } else {
                        canvas.drawLine(prevX, prevY, x, y, drawerEnvLinePaint);
                    }
                    if (k == kfs.size() - 1) {
                        canvas.drawLine(x, y, seg.right, y, drawerEnvLinePaint);
                    }
                    canvas.drawCircle(x, y, 2.6f * density, drawerEnvDotPaint);
                    prevX = x;
                    prevY = y;
                }
                canvas.restore();
            }
            canvas.restore();
        }
        // Keep the sheen/pulse moving while any visible drawer is still analyzing —
        // throttled repaint, stops itself the frame analysis completes.
        if (drawerAnalysisAnimating) {
            postInvalidateDelayed(48);
        }
    }

    /** True during a draw pass iff some open drawer showed the analyzing placeholder. */
    private boolean drawerAnalysisAnimating = false;
    private final Paint drawerSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix drawerSheenMatrix = new android.graphics.Matrix();

    /**
     * Subtle bright band sweeping left→right across the analyzing tape body (~1.4s period) —
     * the "something is actually happening" signal JoyRaptor asked for (2026-07-16).
     */
    private void drawAnalyzingSheen(Canvas canvas, float left, float top,
                                    float right, float bottom) {
        // Round-2 fix: sweep the VISIBLE window with a FIXED-width band. Sizing the band
        // to the segment (18% of a 45-min strip) made a wall of light cross the viewport
        // in a blink — read as a flash, not a sheen. Content space is scrolled by
        // scrollOffsetPx, so the viewport is [scrollOffsetPx, scrollOffsetPx + width].
        float visL = Math.max(left, scrollOffsetPx);
        float visR = Math.min(right, scrollOffsetPx + getWidth());
        if (visR - visL < 1f) return;
        if (drawerSheenPaint.getShader() == null) {
            drawerSheenPaint.setShader(new android.graphics.LinearGradient(
                    0f, 0f, 1f, 0f,
                    new int[]{0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF},
                    null, android.graphics.Shader.TileMode.CLAMP));
        }
        float period = 2400f; // slow, calm pass
        float phase = (android.os.SystemClock.uptimeMillis() % (long) period) / period;
        float bandW = Math.min(120f * density, (visR - visL) * 0.35f);
        float x = visL - bandW + (visR - visL + 2f * bandW) * phase;
        drawerSheenMatrix.reset();
        drawerSheenMatrix.setScale(bandW, 1f);
        drawerSheenMatrix.postTranslate(x, 0f);
        drawerSheenPaint.getShader().setLocalMatrix(drawerSheenMatrix);
        canvas.drawRect(visL, top, visR, bottom, drawerSheenPaint);
    }

    /**
     * Top Y (px) where the M6 Track-driven rows (the nameless layer substrate) begin.
     * Slice E (FEEDBACK #3): this band moved to the TOP — directly under the ruler /
     * minimap — so the overlays / captions / visualizers / sprites read as sitting ABOVE
     * the master track (JoyRaptor's industry-standard order), instead of below it where they
     * looked inverted. The renderer adds its own top gap internally, so the first row
     * starts a hair below {@code rulerHeightPx}. master + audio are pushed below via
     * {@link #masterTopPx}. Both the renderer draw and the M6 hit-testing consume this
     * one value, so the band and its touch zone always move together.
     */
    private float getM6RowsTopPx() {
        return rulerHeightPx;
    }

    /**
     * Whole-project overview strip: every clip as a proportional block, with a
     * highlighted viewport showing which part of the project is on screen.
     * Tap or drag anywhere on it to jump/scrub.
     */
    /** True during a draw pass iff some minimap meter is mid-load (drives the pulse). */
    private boolean minimapMetersAnimating = false;

    /**
     * Per-segment transcription progress, 0..1, or a NEGATIVE value for indeterminate
     * (the engine reports -1 while it has no fraction to give). Absent = not transcribing.
     */
    private final java.util.Map<Integer, Float> transcribeProgress = new java.util.HashMap<>();
    /** Per-segment bar colour, keyed the same way — identifies WHICH model is running. */
    private final android.util.SparseIntArray transcribeColor = new android.util.SparseIntArray();

    /**
     * Show (or update) a transcription meter on a segment's mini-map block.
     * Pass {@code fraction < 0} for indeterminate. Call {@link #clearSegmentTranscribing} when
     * the run finishes, fails, or is cancelled — a meter left behind would claim work is still
     * happening after it stopped, which is the failure this feature exists to prevent.
     */
    public void setSegmentTranscribing(int segmentIndex, float fraction, int color) {
        if (segmentIndex < 0) return;
        transcribeProgress.put(segmentIndex, fraction);
        transcribeColor.put(segmentIndex, color);
        invalidate();
    }

    /** Remove a segment's transcription meter. Safe to call when none is showing. */
    public void clearSegmentTranscribing(int segmentIndex) {
        if (transcribeProgress.remove(segmentIndex) != null) {
            transcribeColor.delete(segmentIndex);
            invalidate();
        }
    }

    /** Remove every transcription meter (e.g. the editor is tearing down). */
    public void clearAllTranscribing() {
        if (transcribeProgress.isEmpty()) return;
        transcribeProgress.clear();
        transcribeColor.clear();
        invalidate();
    }

    /**
     * F-MINIMAP: size the minimap band for however many layer lines the project currently has,
     * and shift the ruler down to match. Called from {@link #init()} and whenever the Track
     * model is re-fed, since adding a text layer must grow the strip.
     *
     * <p>Deliberately adaptive rather than a fixed 12-line reservation: a project with no
     * layers keeps the original 16dp strip, so nothing about the existing layout moves.</p>
     */
    private void recomputeMinimapHeight() {
        int lines = minimapLayerLineCount();
        minimapLayerBandPx = lines == 0
                ? 0f
                // +1 pitch of breathing room between the lowest line and the master tape.
                : (lines * MINIMAP_LAYER_PITCH_DP + 1f) * density;
        minimapHeightPx = MINIMAP_HEIGHT_DP * density + minimapLayerBandPx;
        // Ruler band sits below the minimap strip; everything keyed off
        // rulerHeightPx shifts down together.
        rulerHeightPx = RULER_HEIGHT_DP * density + minimapHeightPx;
        // SPEC_N §1 belt-and-braces: the band between minimapHeightPx and rulerHeightPx is the
        // "0s 2s 4s" strip, and the spec is explicit that if it can EVER compute to zero that
        // is the bug. It cannot from the arithmetic above, but density is read from a
        // DisplayMetrics that has been observed to arrive as 0 on a detached view, which would
        // collapse the whole band. Floor it so the strip can never have zero height.
        if (rulerHeightPx - minimapHeightPx < 1f) {
            rulerHeightPx = minimapHeightPx + RULER_HEIGHT_DP * Math.max(1f, density);
        }
    }

    /** Number of layer lines the strip will draw (floating band then audio band, capped). */
    private int minimapLayerLineCount() {
        return Math.min(MINIMAP_MAX_LAYER_LINES, layerTracks.size() + audioLayerTracks.size());
    }

    /**
     * F-MINIMAP: one thin line per layer above the master tape, each item drawn as a segment
     * at its position across the WHOLE project — so a glance shows where the objects live even
     * when the viewport is zoomed into a few seconds of a long timeline.
     *
     * <p>Segments are coloured by the OBJECT's type via {@link ObjectPalette} (the same table
     * the row bodies, badges and playhead use), never by the lane — a text object on a neutral
     * lane still reads purple. The selected object blinks white so it can be found instantly in
     * a long project. Everything is clipped to the master length, so an item dragged past the
     * end cannot draw outside the strip.</p>
     *
     * <p>Stack order matches the timeline: floating layers first (top-down), then audio.</p>
     */
    private void drawMinimapLayerLines(Canvas canvas, float margin, float stripW) {
        int lines = minimapLayerLineCount();
        if (lines == 0 || totalEffectiveMs <= 0) return;

        String selectedId = layerGestureController != null
                ? layerGestureController.getSelectedItemId() : null;
        // Medium blink for the selected object (spec: PULSES white, not an outline). Reuses the
        // loading-meter animation flag, which already drives a repost while anything animates.
        float pulse = 0.5f + 0.5f * (float) Math.sin(
                android.os.SystemClock.uptimeMillis() / 260.0);

        float thickness = MINIMAP_LAYER_LINE_DP * density;
        float pitch = MINIMAP_LAYER_PITCH_DP * density;
        float y = 3f * density;

        List<com.fadcam.ui.faditor.layers.Track> ordered =
                new ArrayList<>(layerTracks.size() + audioLayerTracks.size());
        ordered.addAll(layerTracks);
        ordered.addAll(audioLayerTracks);

        for (int i = 0; i < lines; i++) {
            com.fadcam.ui.faditor.layers.Track t = ordered.get(i);
            // Faint rail so an EMPTY layer still reads as a layer that exists.
            minimapBlockPaint.setColor(0x1AFFFFFF);
            canvas.drawRect(margin, y, margin + stripW, y + thickness, minimapBlockPaint);

            for (com.fadcam.ui.faditor.layers.TimedItem item : t.getItems()) {
                long start = item.getTimelineStartMs();
                long dur = item.getDisplayDurationMs(totalEffectiveMs);
                // Clip to the master length — an object may legitimately be parked past the
                // end mid-drag, and it must not paint outside the strip.
                long s = Math.max(0, Math.min(totalEffectiveMs, start));
                long e = Math.max(0, Math.min(totalEffectiveMs, start + Math.max(0, dur)));
                if (e <= s) continue;
                float x0 = margin + (s / (float) totalEffectiveMs) * stripW;
                float x1 = margin + (e / (float) totalEffectiveMs) * stripW;
                int color = ObjectPalette.forItem(item, t.getKind());
                if (selectedId != null && selectedId.equals(item.getId())) {
                    color = blendColors(color, Studio.INK, pulse);
                    minimapMetersAnimating = true; // keep the blink repainting
                }
                minimapBlockPaint.setColor(color);
                // Floor the width so a very short object stays visible as a dot.
                canvas.drawRect(x0, y, Math.max(x0 + 1f, x1), y + thickness, minimapBlockPaint);
            }
            y += pitch;
        }
    }

    /** Linear blend a→b by t (0..1), per ARGB channel. */
    private static int blendColors(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24), ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24), br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private void drawMinimap(Canvas canvas, int viewW) {
        minimapMetersAnimating = false;
        if (totalEffectiveMs <= 0 || segments.isEmpty()) return;
        float margin = 8f * density;
        // The master tape keeps its original 16dp slot at the BOTTOM of the strip; the
        // per-layer lines (F-MINIMAP) occupy the adaptive band above it, so adding layers
        // pushes the lines upward and never shrinks the tape.
        float top = MINIMAP_TAPE_INSET_DP * density + minimapLayerBandPx;
        float bot = minimapHeightPx - MINIMAP_TAPE_INSET_DP * density;
        float stripW = viewW - margin * 2;
        if (stripW <= 0) return;
        drawMinimapLayerLines(canvas, margin, stripW);

        // Clip blocks
        long cumul = 0;
        float gap = Math.min(1.5f * density, stripW / (segments.size() * 8f));
        for (int i = 0; i < segments.size(); i++) {
            SegmentData sd = segments.get(i);
            float x0 = margin + (cumul / (float) totalEffectiveMs) * stripW;
            cumul += sd.effectiveMs;
            float x1 = margin + (cumul / (float) totalEffectiveMs) * stripW;
            // LOADING METERS (JoyRaptor spec, 2026-07-16): a block whose thumbnails aren't in
            // yet draws in a DARKER shade of its own color (dark green selected / dark
            // gray unselected) with a soft pulse while extraction runs, snapping to full
            // color when loaded — "running slow because it's doing stuff, not broken."
            // SPEC_V §3: read the CURRENT size variant, so the meter reports on the frames
            // this strip height is actually going to draw.
            String meterKey = thumbVariantKey(sd);
            List<Bitmap> meterThumbs = thumbnailsCache.get(meterKey);
            boolean thumbsReady = sd.isImageClip
                    ? meterThumbs != null
                    : (meterThumbs != null && !meterThumbs.isEmpty());
            boolean thumbsLoading = thumbnailsLoading.contains(meterKey);
            int full = i == selectedIndex ? Studio.GO : Studio.INK_OFF;
            // Selection is cyan across the whole editor now; this dark green was the
            // last place a selected thing still read as "go".
            int darkC = i == selectedIndex ? Studio.LINE : Studio.RAISED;
            int color = thumbsReady ? full : darkC;
            if (!thumbsReady && thumbsLoading) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(
                        android.os.SystemClock.uptimeMillis() / 260.0);
                color = blendColors(darkC, full, pulse * 0.45f);
                minimapMetersAnimating = true;
            }
            minimapBlockPaint.setColor(color);
            canvas.drawRoundRect(x0, top, Math.max(x0 + 1, x1 - gap), bot,
                    2f * density, 2f * density, minimapBlockPaint);
            // Thin blue AUDIO-analysis progress bar along the block bottom; gone at 100%.
            if (tapeWaveformCache != null && !sd.isImageClip && sd.sourceUri != null
                    && sd.sourceDurationMs > 0) {
                float ap = tapeWaveformCache.progressFor(sd.sourceUri, 0,
                        sd.sourceDurationMs, sd.sourceDurationMs);
                if (ap < 1f) {
                    minimapBlockPaint.setColor(Studio.ARMED);
                    float bw = Math.max(0f, (Math.max(x0 + 1, x1 - gap) - x0) * ap);
                    canvas.drawRect(x0, bot - 2f * density, x0 + bw, bot, minimapBlockPaint);
                    minimapMetersAnimating = true;
                }
            }

            // TRANSCRIBING meter, sitting just above the audio-analysis bar. Same idea as that
            // bar and for a sharper reason: transcription runs in SERIES and a long clip on the
            // Accurate model takes tens of minutes, with nothing on screen to say so. A user
            // closed the editor on a 20-minute run because it looked idle and lost the lot
            // (2026-07-28). Coloured per model so several queued runs are distinguishable.
            Float tp = transcribeProgress.get(i);
            if (tp != null) {
                float blockW = Math.max(x0 + 1, x1 - gap) - x0;
                float barTop = bot - 4.5f * density, barBot = bot - 2.5f * density;
                // Dim full-width track so a QUEUED clip (progress 0, or indeterminate) still
                // reads as "this one is waiting its turn", not as "nothing is happening".
                minimapBlockPaint.setColor(0x33FFFFFF);
                canvas.drawRect(x0, barTop, x0 + blockW, barBot, minimapBlockPaint);
                int tcol = transcribeColor.get(i, Studio.CAREFUL);
                if (tp < 0f) {
                    // Indeterminate: a short shuttle sweeping the block.
                    float ph = (android.os.SystemClock.uptimeMillis() % 1400L) / 1400f;
                    float segW = blockW * 0.28f;
                    float sx = x0 + (blockW + segW) * ph - segW;
                    minimapBlockPaint.setColor(tcol);
                    canvas.drawRect(Math.max(x0, sx), barTop,
                            Math.min(x0 + blockW, sx + segW), barBot, minimapBlockPaint);
                } else {
                    minimapBlockPaint.setColor(tcol);
                    canvas.drawRect(x0, barTop, x0 + blockW * Math.min(1f, tp), barBot,
                            minimapBlockPaint);
                }
                minimapMetersAnimating = true;
            }

            // Show silence candidates as yellow dots and cut spans as dark
            // lines inside the minimap block, so the user can see where
            // dead air / cuts are at a glance.
            if (sd.silenceCandidates != null || sd.removedSpans != null) {
                long segStart = cumul - sd.effectiveMs;
                float segW = x1 - x0;
                float pxPerMs = sd.effectiveMs > 0 ? segW / sd.effectiveMs : 0;

                // Cut spans (dark)
                if (sd.removedSpans != null) {
                    for (long[] span : sd.removedSpans) {
                        long relStart = (span[0] - sd.inPointMs);
                        long relEnd = (span[1] - sd.inPointMs);
                        float cx0 = x0 + Math.max(0, relStart / (float) sd.effectiveMs) * segW;
                        float cx1 = x0 + Math.min(1, relEnd / (float) sd.effectiveMs) * segW;
                        minimapBlockPaint.setColor(Studio.SURFACE);
                        canvas.drawRect(cx0, top + 2f * density,
                                Math.max(cx0 + 1, cx1), bot - 2f * density, minimapBlockPaint);
                    }
                }

                // Silence candidates (yellow)
                if (showSilence && sd.silenceCandidates != null) {
                    for (long[] cand : sd.silenceCandidates) {
                        long relStart = (cand[0] - sd.inPointMs);
                        long relEnd = (cand[1] - sd.inPointMs);
                        float cx0 = x0 + Math.max(0, relStart / (float) sd.effectiveMs) * segW;
                        float cx1 = x0 + Math.min(1, relEnd / (float) sd.effectiveMs) * segW;
                        minimapBlockPaint.setColor(0xCCFBBF24);
                        canvas.drawRect(cx0, top + 2f * density,
                                Math.max(cx0 + 1, cx1), bot - 2f * density, minimapBlockPaint);
                    }
                }
            }
        }

        // Viewport window (visible content range)
        long tLeft = Math.max(0, xToTime(scrollOffsetPx));
        long tRight = Math.min(totalEffectiveMs, xToTime(scrollOffsetPx + viewW));
        if (tRight > tLeft) {
            float vx0 = margin + (tLeft / (float) totalEffectiveMs) * stripW;
            float vx1 = margin + (tRight / (float) totalEffectiveMs) * stripW;
            // ── WHAT YOU ARE LOOKING AT ──────────────────────────────────────
            // JoyRaptor: "currently the selection preview range is white and only over the
            // spine in the minimap. it should extend the hight of the full minimap (which is
            // variable with how many layers are shown) and be a thin 1px cyan frame instead
            // of white to go with our system."
            //
            // Two fixes in one. It spans the WHOLE strip now, layer lines included, because
            // the window is a statement about TIME and every row in the strip shares that
            // axis — framing only the tape said the viewport applied to the tape alone.
            //
            // And it is a frame, not a filled box. The old 0x30 white wash sat on top of the
            // per-layer lines, which are 1.2dp and already faint; anything laid over them
            // takes away the one thing the band exists to show. A stroke says the same thing
            // and costs nothing underneath it.
            float vTop = 1.5f * density;
            float vBot = minimapHeightPx - 1.5f * density;
            minimapViewportBorderPaint.setColor(Studio.ARMED);
            minimapViewportBorderPaint.setStyle(Paint.Style.STROKE);
            minimapViewportBorderPaint.setStrokeWidth(Math.max(1f, 1f * density));
            canvas.drawRoundRect(vx0, vTop, vx1, vBot,
                    2f * density, 2f * density, minimapViewportBorderPaint);
        }
    }

    /** Handles taps/drags on the minimap strip: scrubs the playhead. */
    private boolean handleMinimapTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (e.getY() > minimapHeightPx || segments.isEmpty() || totalEffectiveMs <= 0) {
                    return false;
                }
                minimapDragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                seekFromMinimap(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!minimapDragging) return false;
                seekFromMinimap(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!minimapDragging) return false;
                minimapDragging = false;
                if (listener != null) listener.onPlayheadDragFinished();
                return true;
        }
        return minimapDragging;
    }

    private void seekFromMinimap(float screenX) {
        float margin = 8f * density;
        float stripW = getWidth() - margin * 2;
        float frac = Math.max(0f, Math.min(1f, (screenX - margin) / stripW));
        long time = (long) (frac * totalEffectiveMs);
        updatePlayheadFromX(timeToX(time));
    }

    /**
     * Halo under an ARMED master clip: the affordance that makes "hold, then pull up" learnable.
     *
     * <p>Purple, because that is already this timeline's "this is where it lands" colour (§3A.4)
     * and the armed clip is about to become a layer item. Steady, not pulsing — §3A.2's rule is
     * that animation means "still deciding" and steady means "committed"; the arm IS committed,
     * it is only waiting for a direction.</p>
     */
    private void drawDislodgeArmedLift(@NonNull Canvas canvas) {
        if (!dislodgeArmed) return;
        int i = dislodgeArmedSegIndex;
        if (i < 0 || i >= segRects.size()) return;
        RectF r = segRects.get(i);
        float d = getResources().getDisplayMetrics().density;
        RectF halo = new RectF(r.left - 6f * d, r.top - 6f * d, r.right + 6f * d, r.bottom + 6f * d);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x558C3DFA);
        canvas.drawRoundRect(halo, 6f * d, 6f * d, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f * d);
        p.setColor(Studio.ROOM_AVATAR_DEEP);
        canvas.drawRoundRect(halo, 6f * d, 6f * d, p);

        // An upward chevron above the clip: says WHICH WAY, which a halo alone does not.
        float cx = (r.left + r.right) / 2f;
        float cyTop = halo.top - 4f * d;
        float armW = 9f * d;
        Path chev = new Path();
        chev.moveTo(cx - armW, cyTop);
        chev.lineTo(cx, cyTop - armW);
        chev.lineTo(cx + armW, cyTop);
        p.setStrokeWidth(3f * d);
        canvas.drawPath(chev, p);
    }

    private void drawRuler(Canvas canvas, int viewW) {
        if (totalEffectiveMs <= 0) return;
        
        // Dynamic label interval based on zoom (like professional editors)
        // Calculate minimum pixels between labels to avoid overlap (~60dp)
        float minLabelGapPx = 60f * density;
        long labelInterval = calculateDynamicLabelInterval(minLabelGapPx);
        
        // Calculate tick hierarchy intervals to avoid conflicts
        long minorInterval;
        long mediumInterval;
        
        if (labelInterval >= 60000) {
            // Very wide zoom (1m+ labels): coarse tiers so a 45-min project doesn't
            // draw thousands of second-ticks per frame.
            minorInterval = 10000;     // 10s detail ticks
            mediumInterval = 30000;    // 30s medium ticks
        } else if (labelInterval >= 30000) {
            minorInterval = 5000;      // 5s detail ticks
            mediumInterval = 10000;    // 10s medium ticks
        } else if (labelInterval >= 5000) {
            // Three-tier system for large intervals (5s+)
            minorInterval = 200;       // 0.2s detail ticks
            mediumInterval = 1000;     // 1s medium ticks
        } else if (labelInterval >= 1000) {
            // Two-tier system for medium intervals (1-5s)
            minorInterval = 200;       // 0.2s detail ticks
            mediumInterval = labelInterval; // No medium tier, same as label
        } else {
            // Single-tier for sub-second intervals (<1s)
            minorInterval = labelInterval; // No minor ticks, same as label
            mediumInterval = labelInterval; // No medium ticks, same as label
        }
        
        // VIEWPORT CULL (F2a, PERF_SPEC_LONGFILE_20260718): the tick loops used to span the
        // whole timeline — on a 45-min project that was 5k-18k canvas ops (plus a label String
        // each) per frame regardless of scroll/zoom. Only the visible window's ticks draw now;
        // ±1 interval of margin so a label whose center sits just off-screen still paints its
        // on-screen half.
        final long rulerVisStartMs = Math.max(0L, xToTime(scrollOffsetPx));
        final long rulerVisEndMs = Math.min(totalEffectiveMs, xToTime(scrollOffsetPx + viewW));

        // Draw minor ticks (finest detail) - skip if too dense
        if (dpPerSecondPx > 25f * density) {  // Only show when zoomed in enough
            rulerTickPaint.setStrokeWidth(1f * density);
            long tStart = Math.max(0L, (rulerVisStartMs / minorInterval - 1) * minorInterval);
            long tEnd = Math.min(totalEffectiveMs, rulerVisEndMs + minorInterval);
            for (long t = tStart; t <= tEnd; t += minorInterval) {
                if (t % mediumInterval == 0) continue;  // Skip medium/major ticks
                float x = timeToX(t);
                canvas.drawLine(x, rulerHeightPx - rulerTickHeightPx * 0.3f, x, rulerHeightPx, rulerTickPaint);
            }
        }

        // Draw medium ticks (1s or sub-intervals)
        if (labelInterval > mediumInterval) {
            rulerTickPaint.setStrokeWidth(1.5f * density);
            long tStart = Math.max(0L, (rulerVisStartMs / mediumInterval - 1) * mediumInterval);
            long tEnd = Math.min(totalEffectiveMs, rulerVisEndMs + mediumInterval);
            for (long t = tStart; t <= tEnd; t += mediumInterval) {
                if (t % labelInterval == 0) continue;  // Skip labeled ticks
                float x = timeToX(t);
                canvas.drawLine(x, rulerHeightPx - rulerTickHeightPx * 0.6f, x, rulerHeightPx, rulerTickPaint);
            }
        }

        // Draw major ticks with labels (dynamic interval)
        rulerTickPaint.setStrokeWidth(2f * density);
        {
            long tStart = Math.max(0L, (rulerVisStartMs / labelInterval - 1) * labelInterval);
            long tEnd = Math.min(totalEffectiveMs, rulerVisEndMs + labelInterval);
            for (long t = tStart; t <= tEnd; t += labelInterval) {
                float x = timeToX(t);
                String text = fmtTime(t);
                float halfText = rulerTextPaint.measureText(text) / 2f;

                // Tall tick for labeled intervals
                canvas.drawLine(x, rulerHeightPx - rulerTickHeightPx, x, rulerHeightPx, rulerTickPaint);

                // Label
                canvas.drawText(text, x - halfText, rulerHeightPx - rulerTickHeightPx - 2f * density, rulerTextPaint);
            }
        }

        // KineMaster lane (JoyRaptor 2026-07-19): bookmark diamonds on the ruler, culled to
        // the same visible window as the ticks.
        drawBookmarkGlyphs(canvas, rulerVisStartMs, rulerVisEndMs);
    }

    /** Calculate label interval dynamically based on zoom level to prevent overlap */
    private long calculateDynamicLabelInterval(float minLabelGapPx) {
        // Available intervals in ascending order
        long[] intervals = {100, 200, 500, 1000, 2000, 5000, 10000, 30000, 60000,
                120000, 300000};
        
        // Find smallest interval that gives enough pixel spacing
        for (long interval : intervals) {
            float pixelGap = (interval / 1000f) * dpPerSecondPx;
            if (pixelGap >= minLabelGapPx) {
                return interval;
            }
        }
        
        return 60000;  // Fallback to 60s for very zoomed out views
    }

    /** Maps absolute timeline time (ms) to x coordinate in timeline space (NOT screen space). */
    private float timeToX(long timeMs) {
        long cumulative = 0;
        for (int i = 0; i < segments.size(); i++) {
            SegmentData sd = segments.get(i);
            if (timeMs <= cumulative + sd.effectiveMs) {
                RectF rect = segRects.get(i);
                long localMs = timeMs - cumulative;
                float frac = sd.effectiveMs > 0 ? (float) localMs / sd.effectiveMs : 0f;
                return rect.left + frac * rect.width();
            }
            cumulative += sd.effectiveMs;
        }
        // Time is past all video segments — extend linearly from last segment edge
        if (!segRects.isEmpty()) {
            float lastRight = segRects.get(segRects.size() - 1).right;
            long msPastVideo = timeMs - totalEffectiveMs;
            return lastRight + (msPastVideo / 1000f) * dpPerSecondPx;
        }
        return 0;
    }

    /**
     * Inverse of {@link #timeToX(long)}: convert an X coordinate to timeline milliseconds.
     * Handles times within video segments, gaps between clips, and audio-only regions.
     * 
     * @param x the X coordinate on the timeline
     * @return the corresponding timeline position in milliseconds
     */
    private long xToTime(float x) {
        if (segments.isEmpty() || segRects.isEmpty()) return 0;
        
        // Try to find which segment rect contains this X
        long cumulative = 0;
        for (int i = 0; i < segments.size(); i++) {
            RectF rect = segRects.get(i);
            SegmentData sd = segments.get(i);
            
            // If x is within this segment's rect
            if (x >= rect.left && x <= rect.right) {
                float frac = rect.width() > 0 ? (x - rect.left) / rect.width() : 0f;
                long localMs = (long)(frac * sd.effectiveMs);
                return cumulative + localMs;
            }
            
            // If x is between this segment and the next, interpolate
            if (i < segments.size() - 1) {
                RectF nextRect = segRects.get(i + 1);
                if (x > rect.right && x < nextRect.left) {
                    // x is in a gap between segments
                    // Assign it to the next segment at position 0 (start of next segment)
                    return cumulative + sd.effectiveMs;
                }
            }
            
            cumulative += sd.effectiveMs;
        }
        
        // x is past the last segment's rect
        RectF lastRect = segRects.get(segRects.size() - 1);
        if (x > lastRect.right) {
            float distPastVideo = x - lastRect.right;
            long msPastVideo = (long)((distPastVideo / dpPerSecondPx) * 1000f);
            return totalEffectiveMs + msPastVideo;
        }
        
        // x is before first segment — return 0
        return 0;
    }

    private long getMajorTickInterval(long totalMs) {
        // Return major tick intervals (5s, 10s, 30s, 60s) based on total duration
        if (totalMs < 30000) return 5000;   // < 30s: show 5s ticks
        if (totalMs < 60000) return 10000;  // < 1min: show 10s ticks
        if (totalMs < 300000) return 30000; // < 5min: show 30s ticks
        return 60000;  // >= 5min: show 1min ticks
    }

    private String fmtTime(long ms) {
        // Special case: show "0s" instead of "0.0s"
        if (ms == 0) {
            return "0s";
        }
        
        // Show decimal seconds for sub-second precision (0.1s, 0.2s, etc.)
        if (ms < 1000) {
            return String.format(Locale.US, "0.%ds", ms / 100);
        }
        
        long sec = ms / 1000;
        long m = sec / 60;
        long s = sec % 60;
        
        // Show sub-second precision if not on whole second boundary
        if (ms % 1000 > 0) {
            long decimal = (ms % 1000) / 100;
            if (m > 0) {
                return String.format(Locale.US, "%d:%02d.%d", m, s, decimal);
            } else {
                return String.format(Locale.US, "%d.%ds", s, decimal);
            }
        }
        
        return m > 0 ? String.format(Locale.US, "%d:%02d", m, s)
                      : String.format(Locale.US, "%ds", s);
    }

    /**
     * Format a duration in ms as a compact label for timeline segments.
     * e.g. "3s", "1:25", "0.8s"
     */
    private String formatDurationCompact(long ms) {
        if (ms <= 0) return "0s";
        if (ms < 1000) {
            return String.format(Locale.US, "0.%ds", ms / 100);
        }
        long sec = ms / 1000;
        long m = sec / 60;
        long s = sec % 60;
        if (m > 0) {
            return String.format(Locale.US, "%d:%02d", m, s);
        }
        if (ms % 1000 >= 100) {
            return String.format(Locale.US, "%d.%ds", s, (ms % 1000) / 100);
        }
        return String.format(Locale.US, "%ds", s);
    }

    /**
     * Draws faded "ghost" extensions on the selected segment showing footage
     * that exists in the source but is currently trimmed off. Makes it obvious
     * that trimming is non-destructive: drag the handle back to recover it.
     * Drawn beneath neighbouring segments so it never obscures them.
     */
    private void drawTrimGhosts(Canvas canvas, int index) {
        SegmentData sd = segments.get(index);
        if (sd.isImageClip || sd.trimmedMs <= 0) return;
        RectF r = segRects.get(index);
        float pxPerSourceMs = r.width() / (float) sd.trimmedMs;

        long leftHiddenMs = sd.inPointMs;
        long rightHiddenMs = sd.sourceDurationMs - sd.outPointMs;

        segmentPaint.setColor(0x33FFFFFF); // faint fill
        if (leftHiddenMs > 0) {
            float ghostW = leftHiddenMs * pxPerSourceMs;
            RectF ghost = new RectF(r.left - ghostW, r.top + r.height() * 0.15f,
                    r.left, r.bottom - r.height() * 0.15f);
            canvas.drawRoundRect(ghost, segmentCornerPx, segmentCornerPx, segmentPaint);
        }
        if (rightHiddenMs > 0) {
            float ghostW = rightHiddenMs * pxPerSourceMs;
            RectF ghost = new RectF(r.right, r.top + r.height() * 0.15f,
                    r.right + ghostW, r.bottom - r.height() * 0.15f);
            canvas.drawRoundRect(ghost, segmentCornerPx, segmentCornerPx, segmentPaint);
        }
    }

    private void drawSegment(Canvas canvas, int i) {
        RectF full = segRects.get(i);
        // Uniform axis: segRects now abut with no time gap, so separate clips with a DISPLAY-ONLY
        // inset (never in the time mapping). Narrow clips inset proportionally so they never vanish.
        float visInset = Math.min(segmentGapPx * 0.5f, full.width() * 0.2f);
        RectF r = new RectF(full.left + visInset, full.top, full.right - visInset, full.bottom);
        boolean sel = (i == selectedIndex);
        SegmentData sd = segments.get(i);

        // Draw thumbnails if available, otherwise draw solid color
        List<Bitmap> thumbs = thumbnailsCache.get(thumbVariantKey(sd));
        if (thumbs != null && !thumbs.isEmpty()) {
            drawThumbnailsForSegment(canvas, r, thumbs, sel, sd);
        } else {
            segmentPaint.setColor(sel ? COLOR_SEGMENT_SEL : COLOR_SEGMENT);
            canvas.drawRoundRect(r, segmentCornerPx, segmentCornerPx, segmentPaint);
        }

        // Loop extension overlay: semi-transparent regions + indicator
        if (sd.clip.hasLoopExtension()) {
            long visDur = sd.effectiveMs;
            long loopBefore = sd.clip.getLoopBeforeMs();
            long loopAfter = sd.clip.getLoopAfterMs();
            long baseDur = sd.clip.getTrimmedDurationMs();
            if (visDur > 0 && (loopBefore > 0 || loopAfter > 0)) {
                float pxPerVisMs = r.width() / (float) visDur;
                boolean isStill = sd.clip.getLoopMode() == Clip.LOOP_MODE_STILL;
                // Loop-before region (left side)
                if (loopBefore > 0) {
                    float x0 = r.left;
                    float x1 = r.left + loopBefore * pxPerVisMs;
                    canvas.save();
                    canvas.clipRect(x0, r.top, x1, r.bottom);
                    canvas.drawColor(0x88000000);
                    if (!isStill) {
                        // Repeating loop arrows
                        loopPaint.setAlpha(220);
                        float arrowSize = Math.min(20 * density, (x1 - x0) * 0.3f);
                        for (float ax = x0 + arrowSize; ax < x1 - arrowSize; ax += arrowSize * 2.5f) {
                            drawLoopArrow(canvas, ax, r.centerY(), arrowSize, true);
                        }
                    }
                    canvas.restore();
                }
                // Loop-after region (right side)
                if (loopAfter > 0) {
                    float x0 = r.left + (loopBefore + baseDur) * pxPerVisMs;
                    float x1 = r.right;
                    canvas.save();
                    canvas.clipRect(x0, r.top, x1, r.bottom);
                    canvas.drawColor(0x88000000);
                    if (!isStill) {
                        loopPaint.setAlpha(220);
                        float arrowSize = Math.min(20 * density, (x1 - x0) * 0.3f);
                        for (float ax = x0 + arrowSize; ax < x1 - arrowSize; ax += arrowSize * 2.5f) {
                            drawLoopArrow(canvas, ax, r.centerY(), arrowSize, false);
                        }
                    }
                    canvas.restore();
                }
                // Loop mode badge at top-right of base clip
                String badge;
                if (sd.clip.getLoopMode() == Clip.LOOP_MODE_STILL) {
                    badge = "\u25A0"; // filled square (freeze frame)
                } else if (sd.clip.getLoopMode() == Clip.LOOP_MODE_PING_PONG) {
                    badge = "\u21C4";
                } else {
                    badge = "\u21BB";
                }
                loopPaint.setAlpha(200);
                loopPaint.setTextSize(14 * density);
                float bx = r.left + (loopBefore + baseDur) * pxPerVisMs - loopPaint.measureText(badge) - 4 * density;
                float by = r.top + 16 * density;
                canvas.drawText(badge, bx, by, loopPaint);
            }
        }

        // Selection border
        if (sel) {
            float ins = borderWidthPx / 2f;
            RectF br = new RectF(r.left + ins, r.top + ins, r.right - ins, r.bottom - ins);
            canvas.drawRoundRect(br, segmentCornerPx, segmentCornerPx, borderPaint);
        }

        // MISSING state overlay: red background + "MISSING" label
        if (missingSegments != null && missingSegments.contains(i)) {
            canvas.drawRoundRect(r, segmentCornerPx, segmentCornerPx, missingBgPaint);
            String missingLabel = "MISSING";
            float mx = r.centerX();
            float my = r.centerY() + missingTextPaint.getTextSize() / 3f;
            canvas.drawText(missingLabel, mx, my, missingTextPaint);
        }

        // Audio waveform along the bottom of the segment (video clips only)
        drawSegmentWaveform(canvas, r, sd);

        // Yellow detected-silence candidates (tap to convert to a cut)
        if (showSilence) {
            drawSilenceCandidates(canvas, r, sd);
        }
        // Dark regions for transcript-removed spans (non-destructive skip list)
        drawRemovedRegions(canvas, r, sd);

        // White clip-opacity envelope (rubber-band line, keyframe-to-keyframe).
        // x = clip-local time across the rect; y maps opacity 0..1 (bottom..top).
        if (sd.clip.hasOpacityKeyframes()) {
            java.util.List<Clip.OpacityKeyframe> kfs = sd.clip.getOpacityKeyframes();
            long dur = Math.max(1, sd.effectiveMs);
            float h = r.height();
            canvas.save();
            canvas.clipRect(r);
            float prevX = 0f, prevY = 0f;
            for (int k = 0; k < kfs.size(); k++) {
                Clip.OpacityKeyframe kf = kfs.get(k);
                float fx = Math.max(0f, Math.min(1f, kf.timeMs / (float) dur));
                float x = r.left + fx * r.width();
                float oFrac = Math.max(0f, Math.min(1f, kf.opacity));
                float y = r.bottom - oFrac * h;
                if (k == 0) {
                    canvas.drawLine(r.left, y, x, y, opacityEnvLinePaint);
                } else {
                    canvas.drawLine(prevX, prevY, x, y, opacityEnvLinePaint);
                }
                if (k == kfs.size() - 1) {
                    canvas.drawLine(x, y, r.right, y, opacityEnvLinePaint);
                }
                canvas.drawCircle(x, y, 2.6f * density, opacityEnvDotPaint);
                prevX = x;
                prevY = y;
            }
            canvas.restore();
        }

        // Light-blue volume envelope for video clips that carry audio with keyframes.
        // x = clip-local time across the rect; y maps volume 0..2 (bottom..top).
if (sd.clip.hasVolumeKeyframes()) {
                @SuppressWarnings("unchecked")
                java.util.List<Clip.VolumeKeyframe> kfs = (java.util.List<Clip.VolumeKeyframe>) sd.clip.getVolumeKeyframes();
                long dur = Math.max(1, sd.effectiveMs);
            float h = r.height();
            canvas.save();
            canvas.clipRect(r);
            float prevX = 0f, prevY = 0f;
            for (int k = 0; k < kfs.size(); k++) {
                Clip.VolumeKeyframe kf = kfs.get(k);
                float fx = Math.max(0f, Math.min(1f, kf.timeMs / (float) dur));
                float x = r.left + fx * r.width();
                float vFrac = Math.max(0f, Math.min(1f, kf.volume / 2.0f));
                float y = r.bottom - vFrac * h;
                if (k == 0) {
                    canvas.drawLine(r.left, y, x, y, volumeEnvLinePaint);
                } else {
                    canvas.drawLine(prevX, prevY, x, y, volumeEnvLinePaint);
                }
                if (k == kfs.size() - 1) {
                    canvas.drawLine(x, y, r.right, y, volumeEnvLinePaint);
                }
                canvas.drawCircle(x, y, 2.6f * density, volumeEnvDotPaint);
                prevX = x;
                prevY = y;
            }
            canvas.restore();
        }

        if (r.width() > labelPaint.getTextSize() * 3f) {
            // Show segment duration at the bottom-right — sticky to visible portion
            String label = formatDurationCompact(sd.effectiveMs);
            float ty = r.bottom - 4f * density;

            // Compute visible portion of this segment for sticky positioning
            float viewLeft = scrollOffsetPx;
            float viewRight = scrollOffsetPx + getWidth();
            float visRight = Math.min(r.right, viewRight);

            float labelWidth = labelPaint.measureText(label);
            float padding = 6f * density;
            // Position at right edge of visible portion
            float cx = visRight - padding - labelWidth / 2f;
            // Clamp within segment bounds
            cx = Math.max(r.left + labelWidth / 2f + padding, cx);
            cx = Math.min(r.right - labelWidth / 2f - padding, cx);

            // Shadow for better visibility
            labelPaint.setShadowLayer(2f * density, 0, 0, Studio.GROUND);
            canvas.drawText(label, cx, ty, labelPaint);
            labelPaint.clearShadowLayer();
        }

        // Transcript text pinned below the segment, aligned to word timestamps
        drawSegmentTranscript(canvas, r, sd, i);
    }

    /** Gap between segment bottom and transcript text. */
    private static final float TRANSCRIPT_BELOW_GAP_DP = 3f;

    /**
     * Draw transcript words below the segment rect, positioned by their
     * source timestamps. Words scroll with the timeline. The word at the
     * playhead is highlighted in green.
     */
    // ── Transcript search highlight (JoyRaptor 2026-07-16: hits show on the words tape) ──
    @Nullable private String searchClipId;
    @Nullable private java.util.Set<Integer> searchWordIndices;

    /** Word indices matching the transcript panel's search, for one clip. Null/empty clears. */
    public void setTranscriptSearchMatches(@Nullable String clipId,
                                           @Nullable java.util.Set<Integer> wordIndices) {
        this.searchClipId = clipId;
        this.searchWordIndices =
                (wordIndices == null || wordIndices.isEmpty()) ? null : wordIndices;
        invalidate();
    }


    /**
     * Transcript words along the bottom of each AUDIO clip's tape, exactly as
     * {@link #drawSegmentTranscript} puts them along a video segment's.
     *
     * <p><b>This did not exist.</b> {@code setAudioClipTranscript} filled {@code audioTranscripts}
     * and the activity dutifully pushed every audio clip's transcript into it on each sync — and
     * nothing ever read the map. The words were parsed, stored, and drawn nowhere, so a music or
     * voice track showed a bare waveform while the video beside it showed its words. JoyRaptor needs
     * the audio tape for a project built on a song: "transcription text still not showing on
     * audio file tape. thats a top issue for me."
     *
     * <p>Geometry comes from the renderer ({@code itemBodyRect}) rather than being re-derived
     * here, so the words cannot drift from the tape they belong to when the audio band scrolls,
     * collapses or is re-ordered. Word times are SOURCE ms, mapped through the clip's trim the
     * same way the video path maps through its in/out points.
     */
    private void drawAudioTranscripts(@NonNull Canvas canvas, long totalMs) {
        if (audioTranscripts.isEmpty() || audioClips.isEmpty()) return;

        float fontSize = 9f * density;
        transcriptTextPaint.setTextSize(fontSize);
        transcriptTextPaint.setTypeface(Typeface.DEFAULT);
        transcriptTextPaint.setShadowLayer(1.5f * density, 0, 0, Studio.GROUND);
        transcriptHighlightPaint.setTextSize(fontSize);
        transcriptHighlightPaint.setTypeface(Typeface.DEFAULT_BOLD);
        transcriptHighlightPaint.setColor(Studio.GO);
        transcriptHighlightPaint.setShadowLayer(2f * density, 0, 0, Studio.GROUND);
        float descent = transcriptTextPaint.getFontMetrics().descent;

        // Same word/mark crossfade the video tape uses, so both bands thin out together
        // instead of one going to marks while the other still draws text.
        float dps = dpPerSecondPx / density;
        float wordsAlpha = Math.max(0f, Math.min(1f, (dps - 22f) / 13f));
        int markAlpha = (int) ((1f - wordsAlpha) * 0xB4);
        float markW = 1.5f * density, markH = 4.5f * density;
        float cullL = scrollOffsetPx - 80f * density;
        float cullR = scrollOffsetPx + getWidth() + 20f * density;
        long playheadMs = getPlayheadPositionMs();

        for (int i = 0; i < audioClips.size(); i++) {
            com.fadcam.ui.faditor.transcript.Transcript tr = audioTranscripts.get(i);
            if (tr == null || tr.words.isEmpty()) continue;
            AudioClip ac = audioClips.get(i);
            if (ac == null) continue;
            RectF body = layerRowRenderer.itemBodyRect(
                    ac.getId(), audioBandTopPx(), totalMs, this::timeToX);
            if (body == null || body.width() <= 0f) continue;

            long inMs = ac.getInPointMs();
            long outMs = ac.getOutPointMs();
            long span = outMs - inMs;
            if (span <= 0) continue;
            float pxPerMs = body.width() / (float) span;
            float textY = body.bottom - 1f * density - descent;

            // The playhead in this clip's SOURCE time, for the active-word highlight.
            long sourceMs = (playheadMs - ac.getOffsetMs()) + inMs;

            canvas.save();
            canvas.clipRect(body.left, textY - fontSize, body.right, textY + descent + 1f * density);
            for (int w = 0; w < tr.words.size(); w++) {
                com.fadcam.ui.faditor.transcript.TranscriptWord word = tr.words.get(w);
                if (word.startMs < inMs || word.endMs > outMs) continue;
                float wordX = body.left + (word.startMs - inMs) * pxPerMs;
                if (wordX > cullR) break;   // time-ordered: nothing further is visible
                if (wordX < cullL) continue;
                boolean isActive = sourceMs >= word.startMs && sourceMs <= word.endMs;

                if (markAlpha > 0) {
                    transcriptTextPaint.setColor((word.struck ? markAlpha / 3 : markAlpha) << 24
                            | 0x00FFFFFF);
                    canvas.drawRect(wordX, textY - markH, wordX + markW, textY,
                            transcriptTextPaint);
                    transcriptTextPaint.setColor(Studio.INK);
                }
                if (wordsAlpha <= 0f) continue;
                int wA = (int) (wordsAlpha * 0xFF);
                if (isActive) {
                    transcriptHighlightPaint.setAlpha(wA);
                    canvas.drawText(word.text, wordX, textY, transcriptHighlightPaint);
                    transcriptHighlightPaint.setAlpha(0xFF);
                } else if (word.struck) {
                    transcriptTextPaint.setColor(((0x44 * wA / 0xFF) << 24) | 0x00FFFFFF);
                    canvas.drawText(word.text, wordX, textY, transcriptTextPaint);
                    transcriptTextPaint.setColor(Studio.INK);
                } else {
                    transcriptTextPaint.setColor(Studio.INK);
                    transcriptTextPaint.setAlpha(wA);
                    canvas.drawText(word.text, wordX, textY, transcriptTextPaint);
                    transcriptTextPaint.setAlpha(0xFF);
                }
            }
            canvas.restore();
        }
    }

    private void drawSegmentTranscript(Canvas canvas, RectF rect, SegmentData sd, int segIndex) {
        if (sd.clipId == null) return;
        com.fadcam.ui.faditor.transcript.Transcript tr = segmentTranscripts.get(sd.clipId);
        if (tr == null || tr.words.isEmpty()) return;

        // Transcript words are ALWAYS visible in the timeline (JoyRaptor 2026-07-14), with two
        // bottom-aligned homes and a slide between them driven by the drawer fraction:
        //  - collapsed: along the INSIDE BOTTOM of the video's preview tape (the segment
        //    filmstrip itself — the old under-strip gray row is gone);
        //  - open: along the INSIDE BOTTOM of the audio drawer, in its black word band,
        //    below the tape waveform.
        // Full WHITE for readability; descenders sit ~1px above the bottom edge.
        Float drawerFracObj = clipAudioDrawerFraction.get(sd.clipId);
        float drawerFrac = drawerFracObj != null ? Math.max(0f, Math.min(1f, drawerFracObj)) : 0f;

        float fontSize = 9f * density;
        transcriptTextPaint.setTextSize(fontSize);
        transcriptTextPaint.setTypeface(Typeface.DEFAULT);
        transcriptTextPaint.setColor(Studio.INK);
        transcriptTextPaint.setShadowLayer(1.5f * density, 0, 0, Studio.GROUND);

        transcriptHighlightPaint.setTextSize(fontSize);
        transcriptHighlightPaint.setTypeface(Typeface.DEFAULT_BOLD);
        transcriptHighlightPaint.setColor(Studio.GO);
        transcriptHighlightPaint.setShadowLayer(2f * density, 0, 0, Studio.GROUND);

        float pxPerMs = rect.width() / (float) sd.trimmedMs;
        // Baseline so that descenders (y, g, p) end ~1px above the bottom edge of the home.
        float descent = transcriptTextPaint.getFontMetrics().descent;
        float segBaseY = rect.bottom - 1f * density - descent;
        float drawerBot = masterBotPx()
                + drawerFrac * CLIP_AUDIO_DRAWER_HEIGHT_DP * density;
        float drawerBaseY = drawerBot - 1f * density - descent;
        // Slide between the two homes with the drawer animation.
        float textY = segBaseY + (drawerBaseY - segBaseY) * drawerFrac;

        // Clip to the segment's horizontal extent so words don't overflow
        canvas.save();
        float transcriptTop = textY - fontSize;
        float transcriptBot = textY + descent + 1f * density;
        clipPath.reset();
        clipPath.addRect(rect.left, transcriptTop, rect.right, transcriptBot,
                Path.Direction.CW);
        canvas.clipRect(rect.left, transcriptTop, rect.right, transcriptBot);

        boolean isCurrentClip = (segIndex == transcriptClipIndex);

        // Word→mark crossfade (JoyRaptor 2026-07-16): below reading density the words fade
        // into thin white marks — one per word — so GAPS in dialogue stay visually
        // scannable at wide zooms (an empty stretch = no marks). Full words ≥35dp/s,
        // pure marks ≤22dp/s, blend between.
        float dps = dpPerSecondPx / density;
        float wordsAlpha = Math.max(0f, Math.min(1f, (dps - 22f) / 13f));
        int markAlpha = (int) ((1f - wordsAlpha) * 0xB4);
        float markW = 1.5f * density, markH = 4.5f * density;

        // VIEWPORT CULL (2026-07-16 perf regression fix): words are always-visible now
        // (no drawer gate), and a transcribed 45-min lecture holds THOUSANDS of words —
        // issuing drawText for all of them every frame dropped playback to ~5fps. Only
        // words inside the visible window (canvas is scroll-translated ⇒ viewport =
        // [scrollOffsetPx, scrollOffsetPx + width]) actually draw.
        float cullL = scrollOffsetPx - 80f * density;
        float cullR = scrollOffsetPx + getWidth() + 20f * density;

        for (int w = 0; w < tr.words.size(); w++) {
            com.fadcam.ui.faditor.transcript.TranscriptWord word = tr.words.get(w);
            // Only draw words within the clip's trim range
            if (word.startMs < sd.inPointMs || word.endMs > sd.outPointMs) continue;

            float wordX = rect.left + (word.startMs - sd.inPointMs) * pxPerMs;
            if (wordX > cullR) break;      // words are time-ordered — nothing further is visible
            if (wordX < cullL) continue;
            String text = word.text;

            // Struck words are dimmed
            boolean isActive = isCurrentClip
                    && currentPlayheadSourceMs >= word.startMs
                    && currentPlayheadSourceMs <= word.endMs;
            boolean isSearchHit = searchWordIndices != null
                    && sd.clipId.equals(searchClipId)
                    && searchWordIndices.contains(w);

            if (markAlpha > 0) {
                if (isSearchHit) {
                    // Search hits stay findable even in pure-marks zoom: amber,
                    // taller, full-strength.
                    transcriptTextPaint.setColor(Studio.CAREFUL);
                    canvas.drawRect(wordX, textY - markH * 1.6f, wordX + markW * 1.6f, textY,
                            transcriptTextPaint);
                } else {
                    // Thin per-word mark (dimmer for struck words) — the gap-scanning view.
                    transcriptTextPaint.setColor((word.struck ? markAlpha / 3 : markAlpha) << 24
                            | 0x00FFFFFF);
                    canvas.drawRect(wordX, textY - markH, wordX + markW, textY,
                            transcriptTextPaint);
                }
                transcriptTextPaint.setColor(Studio.INK);
            }
            if (wordsAlpha <= 0f) continue;
            int wA = (int) (wordsAlpha * 0xFF);
            if (isActive) {
                transcriptHighlightPaint.setAlpha(wA);
                canvas.drawText(text, wordX, textY, transcriptHighlightPaint);
                transcriptHighlightPaint.setAlpha(0xFF);
            } else if (isSearchHit) {
                // Amber bold — matches the panel's search highlight semantics.
                transcriptHighlightPaint.setColor(Studio.CAREFUL);
                transcriptHighlightPaint.setAlpha(wA);
                canvas.drawText(text, wordX, textY, transcriptHighlightPaint);
                transcriptHighlightPaint.setColor(Studio.GO);
                transcriptHighlightPaint.setAlpha(0xFF);
            } else if (word.struck) {
                transcriptTextPaint.setColor(((0x44 * wA / 0xFF) << 24) | 0x00FFFFFF);
                canvas.drawText(text, wordX, textY, transcriptTextPaint);
                transcriptTextPaint.setColor(Studio.INK);
            } else {
                transcriptTextPaint.setAlpha(wA);
                canvas.drawText(text, wordX, textY, transcriptTextPaint);
                transcriptTextPaint.setAlpha(0xFF);
            }
        }
        canvas.restore();
    }
    
    /**
     * Draws the clip's own audio as a slim waveform band across the bottom
     * ~30% of the segment, mapped to the trimmed window, so cuts can be lined
     * up with speech. Waveforms are extracted once per source and cached.
     */
    /**
     * Draws dark overlays where transcript edits have removed audio/video (the
     * non-destructive skip list). Shows live as the user strikes words.
     */
    private void drawRemovedRegions(Canvas canvas, RectF rect, SegmentData sd) {
        if (sd.removedSpans == null || sd.removedSpans.isEmpty() || sd.trimmedMs <= 0) return;
        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(rect, segmentCornerPx, segmentCornerPx, Path.Direction.CW);
        canvas.clipPath(clipPath);
        float pxPerMs = rect.width() / (float) sd.trimmedMs;
        segmentPaint.setColor(0xCC0D0D10); // near-opaque dark
        for (long[] span : sd.removedSpans) {
            float x0 = rect.left + (Math.max(sd.inPointMs, span[0]) - sd.inPointMs) * pxPerMs;
            float x1 = rect.left + (Math.min(sd.outPointMs, span[1]) - sd.inPointMs) * pxPerMs;
            if (x1 > x0) {
                canvas.drawRect(x0, rect.top, x1, rect.bottom, segmentPaint);
            }
        }
        canvas.restore();
    }

    /** Draws detected-silence candidates as translucent yellow bands. */
    private void drawSilenceCandidates(Canvas canvas, RectF rect, SegmentData sd) {
        if (sd.silenceCandidates == null || sd.silenceCandidates.isEmpty()
                || sd.trimmedMs <= 0) return;
        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(rect, segmentCornerPx, segmentCornerPx, Path.Direction.CW);
        canvas.clipPath(clipPath);
        float pxPerMs = rect.width() / (float) sd.trimmedMs;
        segmentPaint.setColor(0x66FBBF24); // translucent yellow
        for (long[] span : sd.silenceCandidates) {
            float x0 = rect.left + (Math.max(sd.inPointMs, span[0]) - sd.inPointMs) * pxPerMs;
            float x1 = rect.left + (Math.min(sd.outPointMs, span[1]) - sd.inPointMs) * pxPerMs;
            if (x1 > x0) {
                canvas.drawRect(x0, rect.top, x1, rect.bottom, segmentPaint);
            }
        }
        canvas.restore();
    }

    /**
     * Peak amplitude 0..1 of {@code uri}'s audio at {@code sourceMs}, or -1 if that source has not
     * been analysed yet.
     *
     * <p>For the master level meter, which must report what the EXPORT will write and therefore
     * needs the spine's own signal — not just its gain. Reads the cache the tape waveform is
     * already drawn from, so it costs a lookup and never triggers extraction: a source that has
     * not been analysed answers -1 and the meter falls back to gain for that clip.
     */
    public float sourceAmplitudeAt(@Nullable android.net.Uri uri, long sourceMs, long sourceDurMs) {
        if (uri == null || sourceDurMs <= 0) return -1f;
        int[] wave = sourceWaveforms.get(uri.hashCode());
        if (wave == null) return -1f;       // not analysed — do NOT kick off extraction here
        if (wave.length == 0) return 0f;    // analysed, and it has no audio track
        if (sourceMs < 0) return 0f;
        if (sourceMs >= sourceDurMs) return 0f;
        int i = (int) (sourceMs * (long) wave.length / sourceDurMs);
        if (i < 0 || i >= wave.length) return 0f;
        return Math.max(0f, Math.min(1f, wave[i] / 255f));
    }

    private void drawSegmentWaveform(Canvas canvas, RectF rect, SegmentData sd) {
        if (sd.isImageClip || sd.sourceDurationMs <= 0) return;
        int key = sd.sourceUri.hashCode();
        int[] wave = sourceWaveforms.get(key);
        if (wave == null) {
            loadSourceWaveform(key, sd.sourceUri);
            return;
        }
        if (wave.length == 0) return; // no audio track

        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(rect, segmentCornerPx, segmentCornerPx, Path.Direction.CW);
        canvas.clipPath(clipPath);

        float bandH = rect.height() * 0.30f;
        float baseY = rect.bottom;
        float barW = 2f * density;
        float step = barW + audioWaveBarGapPx;
        int barCount = Math.max(1, (int) (rect.width() / step));

        // VIEWPORT CULL (F2b, PERF_SPEC_LONGFILE_20260718): bars were drawn across the WHOLE
        // segment (~43k drawRect/frame on an 8-min clip at editing zoom — the single biggest
        // slice of the >1s draw passes on the 45-min project). Only visible bars draw now.
        final float wVisL = scrollOffsetPx;
        final float wVisR = scrollOffsetPx + getWidth();
        int jStart = Math.max(0, (int) ((wVisL - rect.left) / step) - 1);
        int jEnd = Math.min(barCount, (int) ((wVisR - rect.left) / step) + 2);

        segmentWavePaint.setColor(0xB322D3EE); // soft cyan, distinct from green accents
        for (int j = jStart; j < jEnd; j++) {
            float fracInTrim = j / (float) barCount;
            long sourceMs = sd.inPointMs + (long) (fracInTrim * sd.trimmedMs);
            // Map by the waveform's fixed time-per-bin (each value = WAVEFORM_WINDOW_MS
            // of audio from t=0). This stays aligned even when the file's reported
            // duration is wrong (fragmented MP4), which previously skewed the wave.
            int si = (int) (sourceMs / (float)
                    com.fadcam.ui.faditor.util.AudioExtractor.WAVEFORM_WINDOW_MS);
            si = Math.max(0, Math.min(si, wave.length - 1));
            float amp = wave[si] / 255f;
            float barH = Math.max(1f * density, amp * bandH);
            float x = rect.left + j * step;
            canvas.drawRect(x, baseY - barH, x + barW, baseY, segmentWavePaint);
        }
        canvas.restore();
    }

    private void loadSourceWaveform(int key, Uri sourceUri) {
        if (waveformsLoading.contains(key)) return;
        waveformsLoading.add(key);
        if (waveformExtractor == null) {
            waveformExtractor = new com.fadcam.ui.faditor.util.AudioExtractor(getContext());
        }
        waveformExtractor.generateWaveform(sourceUri,
                new com.fadcam.ui.faditor.util.AudioExtractor.WaveformCallback() {
                    @Override
                    public void onWaveformReady(@NonNull int[] waveform) {
                        post(() -> {
                            sourceWaveforms.put(key, waveform);
                            waveformsLoading.remove(key);
                            invalidate();
                        });
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        post(() -> {
                            // Cache empty so we don't retry every frame (e.g. muted video)
                            sourceWaveforms.put(key, new int[0]);
                            waveformsLoading.remove(key);
                        });
                    }
                });
    }

    private void drawThumbnailsForSegment(Canvas canvas, RectF rect,
                                          List<Bitmap> thumbs, boolean selected,
                                          SegmentData sd) {
        if (thumbs.isEmpty()) return;

        // Clip canvas to rounded rect so thumbnails don't bleed outside corners
        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(rect, segmentCornerPx, segmentCornerPx, Path.Direction.CW);
        canvas.clipPath(clipPath);

        // Tile thumbnails across the segment, mapping each tile to the thumb at ITS
        // time-fraction of the clip (extraction samples thumbs evenly across in→out, so
        // thumb[i] ≈ fraction i/N of the duration). The old sequential mapping ran out of
        // thumbs after MAX_THUMBNAILS_PER_SEGMENT tiles and stretched the LAST frame over
        // the whole remaining strip (JoyRaptor counted exactly 30 frames then a smear on the
        // 45-min lecture, 2026-07-16). Proportional mapping keeps every tile representative
        // of its position at any zoom — thumbs repeat at deep zoom instead of vanishing.
        float tileWidth = rect.height();  // Square tiles matching track height

        // VIEWPORT CULL (F2c, PERF_SPEC_LONGFILE_20260718): tiles were laid across the WHOLE
        // segment (~2.3k drawBitmap + a new RectF each per frame at editing zoom on the 45-min
        // project). Snap the start to the tile grid so the same tile boundaries land at the
        // same content-x regardless of scroll, then stop at the visible right edge.
        final float tVisL = scrollOffsetPx;
        final float tVisR = scrollOffsetPx + getWidth();
        float x = rect.left;
        if (tVisL > rect.left) {
            x = rect.left + (float) Math.floor((tVisL - rect.left) / tileWidth) * tileWidth;
        }
        final float xStop = Math.min(rect.right, tVisR + tileWidth);

        // Thumbs cover the FULL source; map this tile's position through the clip's
        // trim window into source time, then into the full-source thumb list — so any
        // trim/split reuses the same extraction with position-correct frames.
        float srcDur = Math.max(1f, sd.sourceDurationMs);
        while (x < xStop) {
            float centerFrac = (x + tileWidth * 0.5f - rect.left) / rect.width();
            float srcFrac = sd.isImageClip ? centerFrac
                    : (sd.inPointMs + centerFrac * sd.trimmedMs) / srcDur;
            int idx = Math.min(thumbs.size() - 1,
                    Math.max(0, (int) (srcFrac * thumbs.size())));
            Bitmap thumb = thumbs.get(idx);
            if (thumb != null && !thumb.isRecycled()) {
                float drawRight = Math.min(x + tileWidth, rect.right);
                thumbTileDst.set(x, rect.top, drawRight, rect.bottom);
                canvas.drawBitmap(thumb, null, thumbTileDst, null);
            }
            x += tileWidth;
        }

        // Darken overlay for selected segment (green tint)
        if (selected) {
            segmentPaint.setColor(Studio.alpha(Studio.ARMED, 0x33));
            canvas.drawRect(rect, segmentPaint);
        } else {
            // Slight darken for unselected to make labels readable
            segmentPaint.setColor(0x30000000);
            canvas.drawRect(rect, segmentPaint);
        }

        canvas.restore();
    }
    
    /**
     * Loads thumbnails for a segment on a background thread.
     * Uses MediaMetadataRetriever for video clips and BitmapFactory for image clips.
     */
    /**
     * Recycle all cached filmstrip thumbnails to free memory ahead of a
     * memory-heavy operation (e.g. export). They reload lazily on the next draw
     * (see onDraw), so this is safe to call any time.
     */
    /**
     * F3c (PERF_SPEC_LONGFILE_20260718): gate NEW waveform/band extraction kicks while the
     * player is playing — a minutes-long audio decode racing live playback for the codec and
     * disk starved both (dropped frames + partial never-cached extractions that re-kicked
     * forever). Ready data still draws; in-flight jobs finish; the first draw after pause
     * kicks anything still missing. Called from the activity's play/pause transitions.
     */
    public void setAnalysisSuspended(boolean suspended) {
        if (tapeWaveformCache != null) tapeWaveformCache.setSuspended(suspended);
        if (timelineWaveformCache != null) timelineWaveformCache.setSuspended(suspended);
    }

    public void releaseThumbnailMemory() {
        for (List<Bitmap> thumbs : thumbnailsCache.values()) {
            if (thumbs != null) {
                for (Bitmap bmp : thumbs) {
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                }
            }
        }
        thumbnailsCache.clear();
        thumbnailsFailed.clear();
        // Leave thumbnailsLoading as-is: in-flight extractions will re-cache on
        // completion, which is fine. Repaint to drop references immediately.
        invalidate();
    }

    /**
     * SPEC_V §2/§3 — extraction height (px) for the spine's filmstrip AT THE CURRENT SPINE
     * HEIGHT. Was hard-wired to the expanded {@code trackHeightPx}; a collapsed strip is
     * 14dp, so extracting at 56dp for it would be ~16× the pixels for frames drawn a
     * quarter the size.
     */
    private int spineThumbSizePx() {
        return Math.max(1, (int) spineTrackHeightPx());
    }

    /**
     * SPEC_V §2 — how many frames to sample for a segment. SPEC_N §3 originally loaded NONE
     * while collapsed to save extraction work on a long project; JoyRaptor has since asked
     * for the tape to stay visible when collapsed, so the saving moves here instead: a
     * collapsed strip gets a fifth of the frames at a sixteenth of the pixels each.
     */
    private int spineThumbCount(@NonNull SegmentData sd) {
        if (sd.isImageClip) return 1;
        return isSpineCollapsed() ? COLLAPSED_THUMBNAILS_PER_SEGMENT : MAX_THUMBNAILS_PER_SEGMENT;
    }

    /**
     * SPEC_V §3 — THE CACHE KEY THAT WAS MISSING THE SIZE. {@code sd.thumbKey} identifies the
     * SOURCE only, so one set of bitmaps served every strip height. That was harmless while
     * the spine had exactly one height; the moment collapsing extracts a smaller set, an
     * expand would redraw those small frames stretched over the full-height tape. Keying by
     * the size+count the frames were actually extracted at makes the two sets distinct
     * entries, so a height change picks up the right one (or extracts it) rather than
     * silently reusing the wrong one. The two sets coexist cheaply — the collapsed one is
     * ~1/80th the bytes of the expanded one.
     */
    private String thumbVariantKey(@NonNull SegmentData sd) {
        return sd.thumbKey + "@" + spineThumbSizePx() + "x" + spineThumbCount(sd);
    }

    /** The source-only prefix of a variant key (see {@link #thumbVariantKey}). */
    private static String thumbKeyPrefixOf(@NonNull String variantKey) {
        int at = variantKey.indexOf('@');
        return at < 0 ? variantKey : variantKey.substring(0, at);
    }

    private void loadThumbnailsForSegment(int index) {
        if (index < 0 || index >= segments.size()) return;
        if (index >= segRects.size()) return;

        SegmentData sd = segments.get(index);
        String key = thumbVariantKey(sd);

        // Already loaded, currently loading, or known-failed
        if (thumbnailsCache.containsKey(key) && !thumbnailsCache.get(key).isEmpty()) return;
        if (thumbnailsLoading.contains(key)) return;
        if (thumbnailsFailed.contains(key)) return;

        thumbnailsLoading.add(key);

        // Trim-independent extraction (2026-07-16): videos always sample the FULL source
        // with the max budget — one extraction per source file, ever; every trim/split
        // window maps onto it at draw time. Images keep their single-frame path.
        int count = spineThumbCount(sd);

        int thumbSize = spineThumbSizePx();
        Uri uri = sd.sourceUri;
        boolean isImage = sd.isImageClip;
        long inMs = 0;
        long outMs = Math.max(1, sd.sourceDurationMs);
        int finalCount = count;
        // thumbSize/count affect what's actually extracted (a re-zoom changes tile density), so
        // the disk key must include them. Built from the SOURCE prefix + the same suffix it
        // always used (SPEC_V put the size into the in-memory key too, and spelling it twice
        // here would invalidate every filmstrip already on disk for no gain).
        String diskKey = thumbKeyPrefixOf(key) + "_" + thumbSize + "x" + finalCount;
        File diskDir = filmstripCacheDir(diskKey);

        thumbnailExecutor.execute(() -> {
            List<Bitmap> thumbs = readFilmstripDiskCache(diskDir, finalCount);
            boolean fromDisk = thumbs != null;
            if (thumbs == null) {
                thumbs = new ArrayList<>();
                try {
                    if (isImage) {
                        extractImageThumbnails(uri, thumbs, thumbSize);
                    } else {
                        extractVideoThumbnails(uri, inMs, outMs, thumbs, thumbSize, finalCount);
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Failed to extract thumbnails for " + key, e);
                }
                if (!thumbs.isEmpty()) {
                    writeFilmstripDiskCache(diskDir, thumbs);
                }
            }
            List<Bitmap> finalThumbs = thumbs;
            boolean finalFromDisk = fromDisk;
            mainHandler.post(() -> {
                thumbnailsLoading.remove(key);
                if (!finalThumbs.isEmpty()) {
                    thumbnailsCache.put(key, finalThumbs);
                    invalidate();
                    if (finalFromDisk) {
                        FLog.d(TAG, "Filmstrip disk cache hit for " + diskKey);
                    }
                } else {
                    // Hard failure (no decodable frames) — remember so the lazy
                    // loader doesn't re-enqueue this key on every redraw.
                    thumbnailsFailed.add(key);
                }
            });
        });
    }

    // ── §2 item preview images: providers backing LayerRowRenderer (spec §2). All three
    //    keep decode/extraction OFF the draw path: a cache hit returns instantly, a miss
    //    kicks an async load and invalidates when it lands. ──────────────────────────────

    /**
     * Feed the project's sprite sheets so the sprite-cell preview (§2) can resolve + decode
     * them. Called by the editor alongside {@link #setLayerTracks}. Evicts any loaded
     * renderer whose sheet is no longer present so a deleted/replaced sheet's bitmap frees.
     */
    public void setSpriteSheets(@NonNull List<com.fadcam.ui.faditor.sprite.SpriteSheet> sheets) {
        previewSpriteSheets.clear();
        previewSpriteSheets.addAll(sheets);
        Set<String> live = new HashSet<>();
        for (com.fadcam.ui.faditor.sprite.SpriteSheet s : sheets) live.add(s.getId());
        java.util.Iterator<Map.Entry<String, com.fadcam.ui.faditor.sprite.SpriteSheetRenderer>> it =
                spriteRendererCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, com.fadcam.ui.faditor.sprite.SpriteSheetRenderer> e = it.next();
            if (!live.contains(e.getKey())) { e.getValue().recycle(); it.remove(); }
        }
        spriteRendererFailed.retainAll(live);
    }

    @Nullable
    private com.fadcam.ui.faditor.sprite.SpriteSheet previewSheetById(@NonNull String sheetId) {
        for (com.fadcam.ui.faditor.sprite.SpriteSheet s : previewSpriteSheets) {
            if (s.getId().equals(sheetId)) return s;
        }
        return null;
    }

    /**
     * Drop this sheet's cached decoder so the next draw re-reads it. Called when a sheet's
     * FRAMES change under a stable id (a sequence reorder), where the cache would otherwise
     * keep serving the old order — a stale tape is indistinguishable from a reorder that
     * silently did nothing.
     */
    public void invalidateSpriteRenderer(@NonNull String sheetId) {
        com.fadcam.ui.faditor.sprite.SpriteSheetRenderer r = spriteRendererCache.remove(sheetId);
        if (r != null) r.recycle();
        spriteRendererFailed.remove(sheetId);
        invalidate();
    }

    /**
     * Forget which frames were unreadable, WITHOUT dropping the decoded bitmaps.
     *
     * <p>A sequence frame that failed to open is remembered so it is not retried on every draw
     * (see {@code SequenceFrameCache#failed}) — correct for a hot path, wrong forever after the
     * user fixes the file, because the tape kept drawing the dashed MISSING slash on a cell that
     * had come back. The editor calls this at the coarse moments where files may have changed
     * under us; the lazy per-frame probe then repopulates whatever is still genuinely gone.</p>
     *
     * <p>Deliberately NOT {@link #invalidateSpriteRenderer}: that recycles the renderer, which
     * throws away every decoded frame to fix a boolean.</p>
     */
    public void clearSpriteMissingCache() {
        for (com.fadcam.ui.faditor.sprite.SpriteSheetRenderer r : spriteRendererCache.values()) {
            if (r != null) r.clearMissingCache();
        }
        // A sheet that failed to LOAD outright is also retried — restoring the file is exactly
        // the case this set would otherwise pin as broken for the life of the view.
        spriteRendererFailed.clear();
        invalidate();
    }

    /** §2 sprite-cell provider: decode-once sheet renderer + STEP/HOLD cell resolution. */
    private final com.fadcam.ui.faditor.layers.LayerRowRenderer.SpriteCellProvider spriteCellProvider =
            new com.fadcam.ui.faditor.layers.LayerRowRenderer.SpriteCellProvider() {
        @Override
        @Nullable
        public com.fadcam.ui.faditor.sprite.SpriteSheetRenderer renderer(@NonNull String sheetId) {
            com.fadcam.ui.faditor.sprite.SpriteSheetRenderer r = spriteRendererCache.get(sheetId);
            if (r != null) return r;
            if (spriteRendererLoading.contains(sheetId) || spriteRendererFailed.contains(sheetId)) {
                return null;
            }
            final com.fadcam.ui.faditor.sprite.SpriteSheet sheet = previewSheetById(sheetId);
            if (sheet == null) { spriteRendererFailed.add(sheetId); return null; }
            spriteRendererLoading.add(sheetId);
            thumbnailExecutor.execute(() -> {
                com.fadcam.ui.faditor.sprite.SpriteSheetRenderer loaded = null;
                try {
                    loaded = com.fadcam.ui.faditor.sprite.SpriteSheetRenderer.load(getContext(), sheet);
                } catch (Exception e) {
                    FLog.w(TAG, "Sprite sheet preview load failed for " + sheetId, e);
                }
                final com.fadcam.ui.faditor.sprite.SpriteSheetRenderer f = loaded;
                mainHandler.post(() -> {
                    spriteRendererLoading.remove(sheetId);
                    if (f != null) { spriteRendererCache.put(sheetId, f); invalidate(); }
                    else spriteRendererFailed.add(sheetId);
                });
            });
            return null;
        }

        @Override
        @Nullable
        public com.fadcam.ui.faditor.sprite.SpriteSheet sheet(@NonNull String sheetId) {
            return previewSheetById(sheetId);
        }

        @Override
        public int cellForKey(@NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item,
                              @NonNull com.fadcam.ui.faditor.sprite.FrameTrack.Key key) {
            // Direct-cell keys resolve trivially; preset keys resolve through the ONE
            // canonical cell resolver at the key's item-local time (phase 0 of the preset).
            if (key.cellIndex >= 0) return key.cellIndex;
            com.fadcam.ui.faditor.sprite.SpriteSheet sheet = previewSheetById(item.getSheetId());
            if (sheet == null) return -1;
            return com.fadcam.ui.faditor.sprite.SpriteFrameResolver.resolveCellAt(
                    sheet, item, item.getStartMs() + key.timeMs);
        }
    };

    /**
     * §2 image-item thumbnail provider: returns a decoded, row-height-bounded bitmap for the
     * image uri, or {@code null} while it decodes off-thread. Simple whole-lifetime LRU: an
     * editor project has tens of image overlays, not thousands.
     */
    @Nullable
    private Bitmap imagePreviewFor(@NonNull String imageUri, int targetHpx) {
        Bitmap cached = imagePreviewCache.get(imageUri);
        final Bitmap live = (cached != null && !cached.isRecycled()) ? cached : null;
        // SPEC_V §3 (same class again): keyed by uri ALONE, so a preview decoded for a short
        // row — a collapsed lane, or the band squeezed by the collapse dividend — stayed in
        // place when the row grew back and drew soft. Fixed by UPGRADING IN PLACE rather than
        // putting the size in the key: this cache is byte-budgeted and JoyRaptor's project has
        // ~51 image overlays, so a second variant per image would cost him real memory to hold
        // a version nothing wants. Only the largest height ever asked for matters. The small
        // bitmap keeps drawing while the bigger decode runs, so nothing blanks.
        boolean tooSmall = live != null && live.getHeight() < targetHpx * 0.75f;
        if (live != null && !tooSmall) return live;
        if (imagePreviewLoading.contains(imageUri) || imagePreviewFailed.contains(imageUri)) {
            return live;
        }
        imagePreviewLoading.add(imageUri);
        final int h = Math.max(1, targetHpx);
        thumbnailExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                bmp = decodeBoundedImage(Uri.parse(imageUri), h);
            } catch (Exception e) {
                FLog.w(TAG, "Image-item preview decode failed", e);
            }
            final Bitmap f = bmp;
            mainHandler.post(() -> {
                imagePreviewLoading.remove(imageUri);
                if (f != null) {
                    imagePreviewCache.put(imageUri, f);
                    trimImagePreviewCache();
                    invalidate();
                } else {
                    imagePreviewFailed.add(imageUri);
                }
            });
        });
        // Keep drawing the too-small one until the upgrade lands (null only on a true miss).
        return live;
    }

    /**
     * Evicts least-recently-used image previews until the cache is within
     * {@link #IMAGE_PREVIEW_CACHE_MAX_BYTES}. Insert first, then trim — so a freshly decoded
     * bitmap is never the one thrown away, which would re-queue the decode that just finished.
     *
     * <p>Never evicts the last remaining entry: if a single preview somehow exceeds the whole
     * budget, keeping it is strictly better than a decode loop that can never satisfy itself.
     */
    private void trimImagePreviewCache() {
        long bytes = 0;
        for (Bitmap b : imagePreviewCache.values()) {
            if (b != null && !b.isRecycled()) bytes += (long) b.getByteCount();
        }
        java.util.Iterator<Map.Entry<String, Bitmap>> it =
                imagePreviewCache.entrySet().iterator();
        while (bytes > IMAGE_PREVIEW_CACHE_MAX_BYTES && imagePreviewCache.size() > 1
                && it.hasNext()) {
            // accessOrder=true, so the iterator hands back the LEAST recently used entry first.
            Bitmap old = it.next().getValue();
            it.remove();
            if (old != null && !old.isRecycled()) {
                bytes -= (long) old.getByteCount();
                old.recycle();
            }
        }
    }

    /** Decode {@code uri} down-sampled so its height is ~{@code targetHpx} (memory-frugal). */
    @Nullable
    private Bitmap decodeBoundedImage(@NonNull Uri uri, int targetHpx) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outHeight <= 0 || bounds.outWidth <= 0) return null;
        int sample = 1;
        while (bounds.outHeight / (sample * 2) >= targetHpx && sample < 32) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (java.io.InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    /**
     * §2 video-item filmstrip provider: cached thumbnails for an overlay/PiP clip's source,
     * REUSING the master T1 pipeline — same {@link #thumbnailsCache}, same source key
     * ({@code hash+"_src"}, so an overlay sharing a source with a master segment reuses those
     * thumbs for free), same {@link #extractVideoThumbnails} extractor + disk cache. Returns
     * {@code null} while extracting.
     */
    @Nullable
    private List<Bitmap> filmstripForOverlayClip(@NonNull com.fadcam.ui.faditor.model.Clip clip,
                                                 int targetHpx) {
        if (clip.isImageClip()) return null; // image clips take the single-thumb path
        // SPEC_V §3 (the same class of bug, found while fixing the spine): this shares
        // thumbnailsCache with the master filmstrip and was keyed by the SOURCE only — so
        // (a) an overlay clip and a master clip of the same file fought over one entry at
        // whichever height loaded first, and (b) a lane row that changes height (collapse,
        // or the band being squeezed) kept redrawing the frames extracted for the old one.
        // Same size-in-the-key fix, same shape as thumbVariantKey.
        String key = clip.getSourceUri().hashCode() + "_src@"
                + Math.max(1, targetHpx) + "x" + MAX_THUMBNAILS_PER_SEGMENT;
        List<Bitmap> cached = thumbnailsCache.get(key);
        if (cached != null && !cached.isEmpty()) return cached;
        if (thumbnailsLoading.contains(key) || thumbnailsFailed.contains(key)) return null;
        thumbnailsLoading.add(key);
        final Uri uri = clip.getSourceUri();
        final long outMs = Math.max(1, clip.getSourceDurationMs());
        final int thumbSize = Math.max(1, targetHpx);
        final int count = MAX_THUMBNAILS_PER_SEGMENT;
        // Prefix only, for the same reason loadThumbnailsForSegment uses one: keeps the disk
        // key byte-identical to what is already cached on disk.
        final String diskKey = thumbKeyPrefixOf(key) + "_" + thumbSize + "x" + count;
        final File diskDir = filmstripCacheDir(diskKey);
        thumbnailExecutor.execute(() -> {
            List<Bitmap> thumbs = readFilmstripDiskCache(diskDir, count);
            if (thumbs == null) {
                thumbs = new ArrayList<>();
                try {
                    extractVideoThumbnails(uri, 0, outMs, thumbs, thumbSize, count);
                } catch (Exception e) {
                    FLog.w(TAG, "Overlay filmstrip extract failed for " + key, e);
                }
                if (!thumbs.isEmpty()) writeFilmstripDiskCache(diskDir, thumbs);
            }
            final List<Bitmap> finalThumbs = thumbs;
            mainHandler.post(() -> {
                thumbnailsLoading.remove(key);
                if (!finalThumbs.isEmpty()) { thumbnailsCache.put(key, finalThumbs); invalidate(); }
                else thumbnailsFailed.add(key);
            });
        });
        return null;
    }

    // ── Filmstrip disk cache (LRU by total size, mirrors WaveformExtractor's file-per-key
    //    pattern) ─────────────────────────────────────────────────────

    @NonNull
    private File filmstripCacheRoot() {
        File dir = new File(getContext().getCacheDir(), "filmstrip");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @NonNull
    private File filmstripCacheDir(@NonNull String diskKey) {
        String hashed = Integer.toHexString(diskKey.hashCode());
        return new File(filmstripCacheRoot(), hashed);
    }

    /**
     * Reads a previously-cached set of thumbnails for this exact key (source+trim+size+count).
     * Returns null on any miss/version-mismatch/corruption (caller re-extracts).
     */
    @Nullable
    private List<Bitmap> readFilmstripDiskCache(@NonNull File dir, int expectedCount) {
        File versionFile = new File(dir, ".v");
        if (!versionFile.exists()) return null;
        try {
            String v = new String(java.nio.file.Files.readAllBytes(versionFile.toPath()),
                    java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (!String.valueOf(FILMSTRIP_CACHE_VERSION).equals(v)) return null;
        } catch (Exception e) {
            return null;
        }
        List<Bitmap> out = new ArrayList<>();
        for (int i = 0; i < expectedCount; i++) {
            File f = new File(dir, i + ".webp");
            if (!f.exists()) {
                // Partial/corrupt cache entry — bail and let the caller re-extract everything.
                for (Bitmap b : out) if (!b.isRecycled()) b.recycle();
                return null;
            }
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp == null) {
                for (Bitmap b : out) if (!b.isRecycled()) b.recycle();
                return null;
            }
            out.add(bmp);
        }
        // Touch the dir's mtime so the LRU sweep treats a cache HIT as recently used.
        //noinspection ResultOfMethodCallIgnored
        dir.setLastModified(System.currentTimeMillis());
        return out.isEmpty() ? null : out;
    }

    private void writeFilmstripDiskCache(@NonNull File dir, @NonNull List<Bitmap> thumbs) {
        try {
            if (!dir.exists()) dir.mkdirs();
            for (int i = 0; i < thumbs.size(); i++) {
                Bitmap b = thumbs.get(i);
                if (b == null || b.isRecycled()) continue;
                File f = new File(dir, i + ".webp");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                    Bitmap.CompressFormat fmt = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                            ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
                    b.compress(fmt, 80, fos);
                }
            }
            File versionFile = new File(dir, ".v");
            java.nio.file.Files.write(versionFile.toPath(),
                    String.valueOf(FILMSTRIP_CACHE_VERSION).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            evictFilmstripDiskCacheIfOverBudget();
        } catch (Exception e) {
            FLog.w(TAG, "Filmstrip disk cache write failed", e);
        }
    }

    /**
     * Simple whole-project-lifetime LRU: when the cache directory exceeds the byte budget,
     * delete the least-recently-touched per-clip subdirectories until back under budget. Cheap
     * enough to run inline after each write since it only walks one level of directories (a
     * project realistically has tens, not thousands, of distinct filmstrip keys).
     */
    private void evictFilmstripDiskCacheIfOverBudget() {
        File root = filmstripCacheRoot();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return;

        long total = 0;
        List<File> sorted = new ArrayList<>();
        for (File d : dirs) {
            total += dirSizeBytes(d);
            sorted.add(d);
        }
        if (total <= FILMSTRIP_CACHE_MAX_BYTES) return;

        sorted.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified())); // oldest first
        for (File d : sorted) {
            if (total <= FILMSTRIP_CACHE_MAX_BYTES) break;
            long freed = dirSizeBytes(d);
            deleteRecursive(d);
            total -= freed;
        }
    }

    private static long dirSizeBytes(@NonNull File dir) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long sum = 0;
        for (File f : files) sum += f.length();
        return sum;
    }

    private static void deleteRecursive(@NonNull File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * Extracts a single thumbnail from an image URI, scaled to thumbSize.
     */
    private void extractImageThumbnails(@NonNull Uri uri, @NonNull List<Bitmap> out, int thumbSize) {
        try {
            // First pass: get dimensions only
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = getContext().getContentResolver().openInputStream(uri)) {
                if (is == null) return;
                BitmapFactory.decodeStream(is, null, opts);
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return;

            // Calculate sample size for efficient decoding
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            opts.inSampleSize = Math.max(1, maxDim / (thumbSize * 2));
            opts.inJustDecodeBounds = false;

            // Second pass: decode scaled bitmap
            Bitmap raw;
            try (InputStream is = getContext().getContentResolver().openInputStream(uri)) {
                if (is == null) return;
                raw = BitmapFactory.decodeStream(is, null, opts);
            }
            if (raw == null) return;

            // Center-crop to square
            Bitmap cropped = centerCropSquare(raw, thumbSize);
            if (cropped != raw) raw.recycle();
            out.add(cropped);
        } catch (Exception e) {
            FLog.w(TAG, "Image thumbnail extraction failed", e);
        }
    }

    /**
     * Extracts evenly-spaced video frames for the filmstrip.
     *
     * <p>Road_map T1: the accurate path is ONE sequential {@link FilmstripSweepExtractor} decode
     * sweep per source — it decodes forward and grabs the first frame at/after each target
     * timestamp, so filmstrip tiles stop keyframe-snapping (no more wrong/duplicated frames on
     * long-GOP screen recordings). On any codec failure it falls back to the old
     * {@code MediaMetadataRetriever} keyframe-snap path so a segment is never left blank that the
     * old code would have filled.
     */
    private void extractVideoThumbnails(@NonNull Uri uri, long inMs, long outMs,
                                        @NonNull List<Bitmap> out, int thumbSize, int count) {
        long rangeMs = Math.max(1, outMs - inMs);
        long[] targetsUs = new long[count];
        for (int i = 0; i < count; i++) {
            long timeMs = inMs + (rangeMs * i) / count;
            targetsUs[i] = timeMs * 1000L;
        }

        // Accurate sequential sweep first.
        try {
            List<Bitmap> swept = FilmstripSweepExtractor.sweep(getContext(), uri, targetsUs, thumbSize);
            if (swept != null && swept.size() == count) {
                out.addAll(swept);
                return;
            }
            if (swept != null) {
                for (Bitmap b : swept) if (b != null && !b.isRecycled()) b.recycle();
            }
        } catch (Throwable t) {
            // Defensive: sweep is written to return null rather than throw, but never let a
            // decoder quirk crash the extraction thread — fall through to the MMR path.
            FLog.w(TAG, "Filmstrip sweep threw; falling back to MMR", t);
        }

        // Fallback: original per-thumbnail keyframe-snapped extraction (unchanged behaviour).
        extractVideoThumbnailsMmr(uri, targetsUs, out, thumbSize);
    }

    /**
     * Legacy per-thumbnail extraction via {@link MediaMetadataRetriever} with
     * {@code OPTION_CLOSEST_SYNC} (keyframe-snapped). Used only as a fallback when the accurate
     * sweep fails for a source.
     */
    private void extractVideoThumbnailsMmr(@NonNull Uri uri, @NonNull long[] targetsUs,
                                           @NonNull List<Bitmap> out, int thumbSize) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(getContext(), uri);
            for (long timeUs : targetsUs) {
                Bitmap frame = retriever.getFrameAtTime(timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    Bitmap cropped = centerCropSquare(frame, thumbSize);
                    if (cropped != frame) frame.recycle();
                    out.add(cropped);
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Video thumbnail extraction failed", e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Center-crops a bitmap to a square and scales to targetSize.
     */
    @NonNull
    static Bitmap centerCropSquare(@NonNull Bitmap src, int targetSize) {
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int x = (w - side) / 2;
        int y = (h - side) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, side, side);
        if (cropped.getWidth() != targetSize) {
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true);
            if (scaled != cropped) cropped.recycle();
            return scaled;
        }
        return cropped;
    }
    
    /**
     * Slice E delineation (JoyRaptor 2026-07-06): frame the MASTER track as a strip of FILM so it reads as
     * the anchor band the overlays sit above and the audio sits below. Near-black sprocket rails punched
     * along the top &amp; bottom edges + the deep film-black body (COLOR_TRACK_BG). Drawn in SCREEN space
     * over the thumbnails — a fixed film gate the strip scrolls through. Cheap: two rails + evenly
     * spaced perforations across the viewport width.
     */
    private void drawMasterFilmstrip(@NonNull Canvas canvas, float tTop, float tBot, int w) {
        // Drawn in CONTENT space (inside the scroll translate) BEFORE the trim handles, so (a) the
        // sprocket holes are content-locked and TRAVEL with the strip as it scrolls, and (b) the green
        // trim handles paint on TOP of the frame, not behind it (JoyRaptor 2026-07-06). Rails + holes are
        // culled to the visible content span so the cost is constant regardless of timeline length.
        // railH == filmRailPx() (the reserved OUTSIDE margin) so the rails frame the film, not cover it.
        float railH = filmRailPx();
        float left = scrollOffsetPx;
        float right = scrollOffsetPx + w;
        filmPaint.setStyle(Paint.Style.FILL);

        // The film base.
        filmPaint.setColor(COLOR_FILM_RAIL);
        canvas.drawRect(left, tTop, right, tTop + railH, filmPaint);
        canvas.drawRect(left, tBot - railH, right, tBot, filmPaint);

        // The CUT EDGE. One hairline along the outside of each rail, which is where the
        // film meets the ground and therefore the only line that can tell you where the
        // strip stops. Without it the band just fades out and the master track stops
        // looking like a physical object.
        float hair = Math.max(1f, 1.1f * density);
        filmPaint.setColor(COLOR_FILM_EDGE);
        canvas.drawRect(left, tTop, right, tTop + hair, filmPaint);
        canvas.drawRect(left, tBot - hair, right, tBot, filmPaint);

        // Perforations at fixed content-X intervals → they scroll with the film like real
        // sprocket holes. The hole is drawn DARKER than the rail it sits in, then given a
        // lit lower lip. That pair is the whole illusion: a dark void plus a bright edge
        // below it is how the eye is told something is punched THROUGH rather than
        // printed ON. Reversing the two -- which is what was here -- makes a rivet.
        float holeW = 5f * density, holeH = 3.5f * density, corner = 1f * density;
        float pitch = 14f * density;
        float topCy = tTop + railH / 2f, botCy = tBot - railH / 2f;
        float firstCx = (float) (Math.floor((left - pitch / 2f) / pitch) * pitch) + pitch / 2f;

        // Two passes rather than one, so the paint colour is set twice per FRAME instead
        // of twice per hole. At 14dp pitch a wide timeline draws ~60 perforations, and
        // Paint.setColor busts the native paint cache on every call.
        filmPaint.setColor(COLOR_FILM_SPROCKET);
        for (float cx = firstCx; cx < right + pitch; cx += pitch) {
            canvas.drawRoundRect(cx - holeW / 2f, topCy - holeH / 2f,
                    cx + holeW / 2f, topCy + holeH / 2f, corner, corner, filmPaint);
            canvas.drawRoundRect(cx - holeW / 2f, botCy - holeH / 2f,
                    cx + holeW / 2f, botCy + holeH / 2f, corner, corner, filmPaint);
        }

        // The lip. Inset horizontally so it reads as a curve catching light rather than as
        // a second rectangle stacked under the first.
        float lipInset = corner;
        float lipH = Math.max(1f, 0.9f * density);
        filmPaint.setColor(COLOR_FILM_HOLE_LIP);
        for (float cx = firstCx; cx < right + pitch; cx += pitch) {
            canvas.drawRect(cx - holeW / 2f + lipInset, topCy + holeH / 2f - lipH,
                    cx + holeW / 2f - lipInset, topCy + holeH / 2f, filmPaint);
            canvas.drawRect(cx - holeW / 2f + lipInset, botCy + holeH / 2f - lipH,
                    cx + holeW / 2f - lipInset, botCy + holeH / 2f, filmPaint);
        }
    }

    private void drawCenterPlayhead(Canvas canvas, float tTop, float tBot) {
        float centerX = getWidth() / 2f;
        // FOLLOW-UP 1 (user spec 2026-07-03): during a bookend excursion the playhead is
        // CONTENT-LOCKED — it scrolls away with the timeline instead of re-centering.
        // That's the deliberate cue that the scroll is a temporary maneuver ("I can see
        // the playhead way back there, so I know how far I've traveled").
        float px = excursionActive ? (timeToX(playheadPositionMs) - scrollOffsetPx) : centerX;
        if (px < -playheadWidthPx || px > getWidth() + playheadWidthPx) return; // off-screen mid-excursion

        // Start BELOW the minimap strip so the playhead lives only on the ruler + timeline (drawing it
        // over the minimap makes it look like a stray cursor on the overview).
        float lineTop = minimapHeightPx;
        float lineBot = tBot;

        // KineMaster lane (JoyRaptor 2026-07-19): dotted context guides drawn UNDER the
        // playhead line so the solid line + chip stay crisp on top.
        drawContextGuides(canvas, lineTop, lineBot);

        // ── THE PLAYHEAD BODY ───────────────────────────────────────────────
        // JoyRaptor: "the blue playhead seems just blue. shouldnt we have it be hot pink
        // capture color?" and "the full capture gradient should be used vertically on the
        // playhead body and long vertical pin."
        //
        // It runs VERTICALLY, top to bottom, which is the only orientation that means
        // anything on a line 2.5dp wide: across it there is nothing to see, but down its
        // length the gradient gives the eye something to track when the timeline is dense.
        //
        // The context tint still wins when there IS a context — trimming turns it amber,
        // and a selected object lends it that object's colour, which is more informative
        // than any fixed colour could be. The capture gradient is what it wears when it is
        // just being the playhead.
        boolean neutral = playheadColorCurrent == COLOR_PLAYHEAD;
        float halfW = playheadWidthPx / 2f;
        if (neutral) {
            if (playheadGrad == null || playheadGradTop != lineTop || playheadGradBot != lineBot) {
                playheadGrad = new android.graphics.LinearGradient(
                        0f, lineTop, 0f, lineBot,
                        Studio.ROOM_CAPTURE, Studio.LIVE, android.graphics.Shader.TileMode.CLAMP);
                playheadGradTop = lineTop;
                playheadGradBot = lineBot;
            }
            playheadPaint.setShader(playheadGrad);
        } else {
            playheadPaint.setShader(null);
            playheadPaint.setColor(playheadColorCurrent);
        }
        canvas.drawRect(px - halfW, lineTop, px + halfW, lineBot, playheadPaint);
        playheadPaint.setShader(null);

        // ── WHAT SLICE WILL CUT ─────────────────────────────────────────────
        // JoyRaptor, describing KineMaster: "a 1px wide dashed line that appears on the
        // playhead in caution amber over the playhead just the vertical range of the
        // selected object or clip or audio. the idea is that if you click 'slice' the
        // dashed line lets you know what exactly is being sliced. that way your not like
        // 'will it slice my clip or my selected object?'"
        //
        // That is the whole value of it: Slice is ambiguous the moment more than one thing
        // is selectable, and the answer is currently only discoverable by doing it and
        // undoing it. The dashes sit ON the playhead and span ONLY the selected object's
        // rows, so the question answers itself before the button is pressed.
        //
        // Amber because this is the "careful" colour — a cut is destructive — and it is
        // drawn OVER the pink, which stays visible at the dash gaps, so the line still
        // reads as the playhead wearing a warning rather than as a second cursor.
        drawSliceSpan(canvas, px, tTop, tBot);

        // Floating time-chip at the playhead top.
        drawPlayheadChip(canvas, px);
    }

    /**
     * The dashed amber marker showing exactly what a Slice would cut.
     *
     * <p>Spans the selected object's rows and nothing else. Three cases, in the order the
     * editor resolves selection:
     * <ol>
     *   <li>a SPINE segment is selected -> the master track's own band;</li>
     *   <li>a LAYER object is selected -> the span the row renderer recorded while
     *       drawing it this frame;</li>
     *   <li>nothing selected -> nothing drawn, because there is no ambiguity to resolve.</li>
     * </ol>
     *
     * <p>1px, not 1dp. JoyRaptor called it "1px wide" and on a 3x screen a 1dp dash is 3
     * physical pixels, which stops looking like a hairline and starts looking like a
     * second playhead competing with the first.
     */
    private void drawSliceSpan(@NonNull Canvas canvas, float px, float tTop, float tBot) {
        float top, bot;
        if (selectedIndex >= 0) {
            top = tTop; bot = tBot;
        } else if (layerRowRenderer != null && layerRowRenderer.hasSelectionSpan()) {
            top = layerRowRenderer.selectionSpanTop();
            bot = layerRowRenderer.selectionSpanBottom();
        } else {
            return;
        }
        if (bot - top < 2f) return;

        if (slicePaint.getPathEffect() == null) {
            slicePaint.setStyle(Paint.Style.STROKE);
            slicePaint.setStrokeWidth(1f);              // one PHYSICAL pixel
            slicePaint.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{3f * density, 3f * density}, 0f));
        }
        slicePaint.setColor(COLOR_PLAYHEAD_TRIM);
        canvas.drawLine(px, top, px, bot, slicePaint);
    }

    // ═══════════ KineMaster-class playhead lane (JoyRaptor 2026-07-19) ═══════════

    /** Retarget the ~150ms colour animation if the editing context changed. */
    private void updatePlayheadContextColor() {
        int target = resolvePlayheadContextColor();
        if (target == playheadColorTarget) return;
        playheadColorTarget = target;
        if (playheadColorAnim != null) playheadColorAnim.cancel();
        android.animation.ValueAnimator a =
                android.animation.ValueAnimator.ofObject(argbEval, playheadColorCurrent, target);
        a.setDuration(150);
        a.addUpdateListener(anim -> {
            playheadColorCurrent = (int) anim.getAnimatedValue();
            invalidate();
        });
        playheadColorAnim = a;
        a.start();
    }

    /**
     * Priority: trim tint &gt; the playhead's own colour.
     *
     * <p>This used to borrow the SELECTED OBJECT's hue — master blue, text purple, and so
     * on. It was a reasonable idea and it is now the wrong one, for two reasons that only
     * became true together.
     *
     * <p>First, JoyRaptor: "the blue playhead seems just blue. shouldnt we have it be hot
     * pink capture color?" He was looking at the playhead wearing an object's identity and
     * reading it as the playhead's own colour, which is exactly what it looks like — there
     * is nothing on screen to say the tint was borrowed.
     *
     * <p>Second, and the reason it can simply go: the dashed slice span now answers the
     * question the tint was trying to answer, and answers it far better. A tint says "the
     * selected thing is blue-ish"; the dashes say "THIS is what Slice will cut", drawn over
     * exactly those rows. Keeping both would be two systems reporting one fact, and the
     * weaker one was costing the playhead its identity.
     *
     * <p>Trimming keeps its amber, because that is a MODE rather than a selection: while a
     * handle is being dragged the playhead is doing something different, and it should not
     * look like it is idling.
     */
    private int resolvePlayheadContextColor() {
        if (activeDrag == Drag.LEFT_HANDLE || activeDrag == Drag.RIGHT_HANDLE) {
            return COLOR_PLAYHEAD_TRIM;
        }
        return COLOR_PLAYHEAD;
    }

    @Nullable
    private com.fadcam.ui.faditor.layers.TrackKind kindForSelectedLayerItem(@NonNull String id) {
        for (List<com.fadcam.ui.faditor.layers.Track> band
                : java.util.Arrays.asList(layerTracks, audioLayerTracks)) {
            for (com.fadcam.ui.faditor.layers.Track t : band) {
                for (com.fadcam.ui.faditor.layers.TimedItem it : t.getItems()) {
                    // NEUTRAL SUBSTRATE: colour by the OBJECT's kind, not its lane's. A lane
                    // no longer describes what it holds, so returning t.getKind() tinted the
                    // playhead by whatever the lane happened to be called — a sprite on the
                    // "Text" lane read as text, and anything on a neutral lane hit an
                    // unhandled LAYER case. Same authority the row badges use.
                    if (id.equals(it.getId())) {
                        return com.fadcam.ui.faditor.layers.LayerRowRenderer
                                .payloadKindOf(it, t.getKind());
                    }
                }
            }
        }
        return null;
    }

    /**
     * F-COLOR: was a second hand-maintained copy of the item table (the COLOR_PH_* block),
     * which had already drifted — it had no IMAGE case, so images took the VIDEO blue through
     * {@code default:}. Both tables now read {@link com.fadcam.ui.faditor.layers.ObjectPalette},
     * so a hue is changed in one place and they cannot disagree again.
     */
    private int colorForKind(@NonNull com.fadcam.ui.faditor.layers.TrackKind k) {
        return com.fadcam.ui.faditor.layers.ObjectPalette.forKind(k);
    }

    /**
     * Dotted guides in SCREEN space: top+bottom of the selected item's row band across the
     * visible width, plus a vertical line at a live trim edge.
     *
     * <p>COVERS EVERY SELECTION AND EVERY TRIM NOW. {@code PLAYHEAD_KINEMASTER_20260719.md}
     * scoped this to master + legacy-audio because both of the things it needed —
     * a floating item's band rect and a layer trim's live edge — lived in files that were held
     * at the time. Neither is held any more: the bands come from
     * {@code LayerRowRenderer.screenBandForItem} and the edge from
     * {@code LayerGestureController.liveTrimEdgeMs}, so a text box, a sprite, a PiP or an audio
     * clip now gets the same guides a master clip always did.</p>
     */
    private void drawContextGuides(@NonNull Canvas canvas, float lineTop, float lineBot) {
        int w = getWidth();
        int guideColor = (playheadColorCurrent & 0x00FFFFFF) | 0x55000000; // low-alpha context tint
        guidePaint.setColor(guideColor);

        // Horizontal row-band guides (segRects .top/.bottom are absolute
        // Y — only X is scrolled — so they are valid screen coordinates as-is).
        RectF band = null;
        // SPEC_V §1 — THE TWO BLUE DOTTED LINES. They are these: the selected clip's row-band
        // guides, blue because a selected master clip tints the playhead ObjectPalette.MASTER.
        // They earn their keep on the EXPANDED spine (they are what you line a layer up
        // against while trimming), but on a COLLAPSED spine the band is a 14dp sliver, so two
        // rules 14dp apart read as unexplained chrome rather than as an alignment aid — and
        // they are the same "hidden while collapsed" case as the trim handles and fade knobs
        // (SPEC_N §3: a control you cannot usefully hit is worse than no control). The other
        // half of JoyRaptor's report — that they sat at FULL height and did not move — was
        // the stale-segRects fault fixed in ensureSegRectsFresh(); they now derive from the
        // current geometry either way.
        if (selectedIndex >= 0 && selectedIndex < segRects.size() && !isSpineCollapsed()) {
            band = segRects.get(selectedIndex);
        }
        if (band != null) {
            canvas.drawLine(0, band.top, w, band.top, guidePaint);
            canvas.drawLine(0, band.bottom, w, band.bottom, guidePaint);
        } else if (layerGestureController != null && layerRowRenderer != null
                && layerGestureController.getSelectedItemId() != null) {
            // Deferred-seam TODO closed: guides along the SELECTED layer item's row
            // (text/sticker/sprite/viz/PiP in the floating band, or a renderer audio
            // row), via the renderer's screen-space band accessor.
            float[] tb = layerRowRenderer.screenBandForItem(
                    layerGestureController.getSelectedItemId());
            if (tb != null) {
                canvas.drawLine(0, tb[0], w, tb[0], guidePaint);
                canvas.drawLine(0, tb[1], w, tb[1], guidePaint);
            }
        }

        // Vertical trim-edge guide. Master trim first (trimDragX is content-space), then a
        // LAYER-ITEM trim — a text box, sprite, PiP or audio clip — whose edge the controller
        // reports as resolved ms. Both draw the same line for the same reason: while an edge is
        // moving, the one thing you cannot see is what it is lining up with on the other rows.
        float edgeX = Float.NaN;
        if (activeDrag == Drag.LEFT_HANDLE || activeDrag == Drag.RIGHT_HANDLE) {
            edgeX = trimDragX - scrollOffsetPx;
        } else if (layerGestureController != null) {
            long edgeMs = layerGestureController.liveTrimEdgeMs(totalEffectiveMs);
            if (edgeMs != Long.MIN_VALUE) edgeX = timeToX(edgeMs) - scrollOffsetPx;
        }
        if (!Float.isNaN(edgeX) && edgeX >= 0 && edgeX <= w) {
            canvas.drawLine(edgeX, lineTop, edgeX, lineBot, guidePaint);
        }
    }

    /** Floating dark pill showing mm:ss.mmm at the playhead top; bigger/bolder while scrubbing. */
    private void drawPlayheadChip(@NonNull Canvas canvas, float px) {
        // The readout matches what is being HEARD, for the same reason the content is
        // scrolled to the heard moment — the two must agree or the chip contradicts the line.
        long shown = drawnPlayheadMs();
        if (chipCachedMs != shown) {
            chipCachedMs = shown;
            chipCachedText = fmtChipTime(shown);
        }
        String text = chipCachedText;
        chipTextPaint.setTextSize(playheadScrubbing ? chipTextScrubPx : chipTextPx);
        Paint.FontMetrics fm = chipTextPaint.getFontMetrics();
        float textH = fm.descent - fm.ascent;
        float textW = chipTextPaint.measureText(text);
        float boxW = textW + chipPadHPx * 2f;
        float boxH = textH + chipPadVPx * 2f;

        // Sit in the ruler band (right at the playhead's top), clamped on-screen.
        float top = minimapHeightPx + 1f * density;
        float bottom = top + boxH;
        float cx = px;
        float left = cx - boxW / 2f;
        float margin = 2f * density;
        left = Math.max(margin, Math.min(left, getWidth() - boxW - margin));
        chipRect.set(left, top, left + boxW, bottom);

        // SOLID, in the playhead's current context colour, at full opacity. The old fill
        // was 0xE6 -- 90% -- which let the ruler's tick marks ghost through the numbers.
        chipBgPaint.setColor(Studio.GROUND | (playheadColorCurrent & 0x00FFFFFF));

        // ── A TRAPEZOID, NOT A PILL ─────────────────────────────────────────
        // JoyRaptor: "seeing the pill on the playhead its much better then box, i am
        // wondering if it would be even better as a trapazoid? eg: \\00:01.127/"
        //
        // His sketch has both edges leaning inward toward the bottom, so the chip narrows
        // as it approaches the line it belongs to. That is what a pill cannot do: a pill
        // is the same shape at both ends and floats above the playhead, while this one
        // FUNNELS into it, and the eye follows the taper down to the exact column the time
        // refers to.
        //
        // It is also the same shear already used by the lobby's New chips and by the
        // recents cards, so the angle is becoming a thing the product says rather than a
        // one-off flourish.
        //
        // The corners are softened with a CornerPathEffect rather than a rounded-rect
        // path: a trapezoid's corners are not 90 degrees, and the effect solves the
        // tangent arc at each vertex, so the acute pair rounds tighter than the obtuse
        // pair -- which is what the eye expects and what hand-rolled quadratics get wrong.
        float taper = Math.min(boxH * 0.42f, boxW * 0.16f);
        chipPath.reset();
        chipPath.moveTo(chipRect.left, chipRect.top);
        chipPath.lineTo(chipRect.right, chipRect.top);
        chipPath.lineTo(chipRect.right - taper, chipRect.bottom);
        chipPath.lineTo(chipRect.left + taper, chipRect.bottom);
        chipPath.close();
        canvas.drawPath(chipPath, chipBgPaint);

        // The time is punched OUT of the chip, in whichever of black or white actually
        // reads on this colour. Hardcoding white was safe on the old near-black fill and
        // is wrong now: the playhead turns amber while trimming and lime on a master
        // selection, and white on those measures under 2:1.
        chipTextPaint.setColor(inkOn(playheadColorCurrent));
        float baseline = top + chipPadVPx - fm.ascent;
        canvas.drawText(text, chipRect.centerX(), baseline, chipTextPaint);
    }

    /**
     * Black or white, whichever is legible on {@code bg}.
     *
     * <p>Uses the WCAG relative-luminance curve rather than a naive average of the
     * channels, because the eye is roughly ten times more sensitive to green than to blue.
     * The naive version gets the studio palette exactly backwards on two colours: pure
     * magenta and pure blue both average to a mid value and would be handed white text,
     * when magenta needs black and blue needs white.
     *
     * <p>The 0.42 threshold rather than 0.5 is deliberate. White-on-colour and
     * black-on-colour are not symmetric -- light text on a mid tone blooms and loses its
     * counters, so the crossover belongs slightly below the midpoint.
     */
    private static int inkOn(int bg) {
        float[] c = new float[3];
        c[0] = ((bg >> 16) & 0xFF) / 255f;
        c[1] = ((bg >> 8) & 0xFF) / 255f;
        c[2] = (bg & 0xFF) / 255f;
        for (int i = 0; i < 3; i++) {
            c[i] = c[i] <= 0.04045f
                    ? c[i] / 12.92f
                    : (float) Math.pow((c[i] + 0.055f) / 1.055f, 2.4);
        }
        float l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
        return l > 0.42f ? Studio.SURFACE : Studio.INK;
    }

    /** mm:ss.mmm, with an h: prefix only past one hour. */
    private String fmtChipTime(long ms) {
        if (ms < 0) ms = 0;
        long h = ms / 3600000L;
        long m = (ms / 60000L) % 60L;
        long s = (ms / 1000L) % 60L;
        long milli = ms % 1000L;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, milli);
        }
        return String.format(Locale.US, "%02d:%02d.%03d", m, s, milli);
    }

    // ── Bookmarks ────────────────────────────────────────────────────────

    /** Host callback for bookmark set changes (for sidecar persistence — see BookmarkStore). */
    public interface BookmarkListener {
        void onBookmarksChanged(@NonNull List<Long> bookmarksMs);
    }

    public void setBookmarkListener(@Nullable BookmarkListener l) { this.bookmarkListener = l; }

    /** Replace the view's bookmark set (copied + sorted). */
    // ── D6: beat markers ──────────────────────────────────────────────────────────────
    /**
     * Detected beats, ascending, in timeline ms. Deliberately a SEPARATE list from bookmarks:
     * a bookmark is something you placed and a beat is something the machine guessed, and
     * mixing them would make "clear the beats" also throw away the user's own marks.
     */
    @NonNull private long[] beatsMs = new long[0];
    private boolean beatSnapEnabled = true;
    private final Paint beatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Run beat detection over an audio clip and populate the grid, using the waveform this view
     * already caches for drawing — no new extraction, no new I/O.
     *
     * @return false when the waveform is not cached yet (the cache fetches asynchronously and
     *         will invalidate the view when it lands, so the caller may simply try again).
     */
    /**
     * An amplitude envelope extracted for ANALYSIS, carrying the frame rate it was sampled at.
     *
     * <p>The rate travels WITH the samples on purpose. The waveform cache picks a resolution
     * tier per clip, so two clips do not necessarily share one — and every analysis in this
     * codebase (beats, alignment) converts frames to milliseconds, so borrowing one clip's rate
     * for another's samples silently scales the answer. That is not a hypothetical: the first
     * D7 door read {@code bucketMs} from the FIRST clip and applied it to BOTH.</p>
     */
    public static final class AnalysisEnvelope {
        /** Amplitudes normalised to 0..1000. */
        @NonNull public final int[] samples;
        public final double framesPerSecond;
        /** Where these samples start inside the SOURCE, in ms. */
        public final long sourceStartMs;

        AnalysisEnvelope(@NonNull int[] samples, double framesPerSecond, long sourceStartMs) {
            this.samples = samples;
            this.framesPerSecond = framesPerSecond;
            this.sourceStartMs = sourceStartMs;
        }
    }

    /**
     * Extract an analysis envelope for a clip from the waveform this view already caches.
     *
     * <p>Public so callers do not have to reach into the private cache by reflection — which the
     * first D7 door did, losing compile-time checking and inviting an R8 release-only failure.
     * It is also the ONE place amplitudes get normalised, so a second caller cannot normalise
     * differently and quietly get different numbers.</p>
     *
     * @return null when the cache has not fetched this clip yet. That is not an error: the cache
     *         is asynchronous and invalidates the view when it lands, so callers retry.
     */
    @Nullable
    public AnalysisEnvelope analysisEnvelopeFor(
            @NonNull com.fadcam.ui.faditor.model.AudioClip ac) {
        if (timelineWaveformCache == null) return null;
        com.fadcam.ui.faditor.model.WaveformData wd =
                timelineWaveformCache.get(ac, Math.max(1f, dpPerSecondPx));
        if (wd == null || wd.amplitudes.length < 8 || wd.bucketMs <= 0) return null;
        float max = 0f;
        for (float a : wd.amplitudes) if (a > max) max = a;
        if (max <= 0f) return null;
        int[] env = new int[wd.amplitudes.length];
        for (int i = 0; i < env.length; i++) env[i] = Math.round(wd.amplitudes[i] / max * 1000f);
        return new AnalysisEnvelope(env, 1000.0 / wd.bucketMs, wd.startOffsetMs);
    }

    /**
     * D8: ONE BAND's RMS envelope for an audio clip, from the quad-band cache this view
     * already draws with — no new extraction, no new I/O. Samples normalised to 0..1000,
     * same single-normalisation discipline as {@link #analysisEnvelopeFor}, and the rate
     * travels WITH the samples for the same reason.
     *
     * @return null while the cache has not finished this clip, or when the requested band
     *         was never computed (presence off). The cache invalidates the view when the
     *         extraction lands, so callers may simply retry.
     */
    @Nullable
    public AnalysisEnvelope bandedEnvelopeFor(
            @NonNull com.fadcam.ui.faditor.model.AudioClip ac, int band) {
        if (tapeWaveformCache == null) return null;
        com.fadcam.ui.faditor.waveform.BandedTimelineWaveformCache.Shaped shaped =
                tapeWaveformCache.get(ac);
        if (shaped == null || shaped.raw == null) return null;
        float[] b = shaped.raw.band(band);
        if (b == null || b.length < 8 || shaped.raw.envRate <= 0f) return null;
        float max = 0f;
        for (float v : b) if (v > max) max = v;
        if (max <= 0f) return null;
        int[] env = new int[b.length];
        for (int i = 0; i < env.length; i++) env[i] = Math.round(b[i] / max * 1000f);
        return new AnalysisEnvelope(env, shaped.raw.envRate, shaped.raw.startOffsetMs);
    }

    public boolean detectBeatsForAudioClip(
            @NonNull com.fadcam.ui.faditor.model.AudioClip ac, float sensitivity) {
        if (timelineWaveformCache == null) return false;
        float pxPerSec = Math.max(1f, dpPerSecondPx);
        com.fadcam.ui.faditor.model.WaveformData wd = timelineWaveformCache.get(ac, pxPerSec);
        if (wd == null || wd.amplitudes.length < 8 || wd.bucketMs <= 0) return false;

        // Normalise to a fixed integer range rather than assuming the float scale. The detector
        // thresholds adaptively so absolute scale does not matter — but a straight cast of
        // 0..1 floats would truncate the whole envelope to zeros, which is a silent no-result.
        float max = 0f;
        for (float a : wd.amplitudes) if (a > max) max = a;
        if (max <= 0f) { setBeatMarkers(null); return true; }
        int[] env = new int[wd.amplitudes.length];
        for (int i = 0; i < env.length; i++) {
            env[i] = Math.round(wd.amplitudes[i] / max * 1000f);
        }

        com.fadcam.ui.faditor.waveform.BeatDetector.Result r =
                com.fadcam.ui.faditor.waveform.BeatDetector.detect(
                        env, 1000.0 / wd.bucketMs, sensitivity);

        // Detected times are relative to the ENVELOPE start; the envelope starts at
        // startOffsetMs within the SOURCE; the clip shows source[inPoint..outPoint] at
        // offsetMs on the timeline. Beats outside the trimmed window are dropped rather than
        // clamped — a beat clamped to a clip edge is a beat in the wrong place.
        long in = ac.getInPointMs();
        long spanEnd = ac.getOffsetMs() + ac.getTrimmedDurationMs();
        long[] tmp = new long[r.beatsMs.length];
        int n = 0;
        for (long b : r.beatsMs) {
            long timelineMs = ac.getOffsetMs() + (wd.startOffsetMs + b - in);
            if (timelineMs >= ac.getOffsetMs() && timelineMs <= spanEnd) tmp[n++] = timelineMs;
        }
        long[] out = new long[n];
        System.arraycopy(tmp, 0, out, 0, n);
        setBeatMarkers(out);
        lastDetectedBpm = r.bpm;
        return true;
    }

    /** Tempo from the last detection, or 0. Display only — it never moves a beat. */
    public float getLastDetectedBpm() { return lastDetectedBpm; }
    private float lastDetectedBpm = 0f;

    /** Replace the detected beat grid. Pass empty/null to clear it. */
    public void setBeatMarkers(@Nullable long[] beats) {
        beatsMs = (beats == null) ? new long[0] : beats.clone();
        java.util.Arrays.sort(beatsMs);
        invalidate();
    }

    @NonNull public long[] getBeatMarkers() { return beatsMs; }

    public void setBeatSnapEnabled(boolean on) { beatSnapEnabled = on; }
    public boolean isBeatSnapEnabled() { return beatSnapEnabled; }

    /**
     * Snap a timeline position to the nearest beat, if snapping is on and one is close enough.
     * Tolerance is in PIXELS converted to ms, so it stays a constant thumb-distance at every
     * zoom rather than becoming unusable when you zoom in to place something precisely.
     */
    /**
     * The x→time mapper ITEM gestures use, so a dragged or trimmed clip lands on the beat.
     *
     * <p>Deliberately NOT the mapper scrubbing uses. A playhead that snaps is a playhead you
     * cannot park between beats to hear exactly what is there — and inspecting is most of what
     * scrubbing is for.</p>
     */
    private long xToTimeSnapped(float x) { return snapToBeat(xToTime(x)); }

    public long snapToBeat(long timeMs) {
        if (!beatSnapEnabled || beatsMs.length == 0) return timeMs;
        // dpPerSecondPx already folds in zoom AND density (see its assignment at setTimeline).
        float pxPerMs = dpPerSecondPx / 1000f;
        long tolMs = Math.max(1, (long) (10f * density / Math.max(1e-6f, pxPerMs)));
        return com.fadcam.ui.faditor.waveform.BeatDetector.snap(beatsMs, timeMs, tolMs);
    }

    /**
     * Beat ticks on the ruler — thin vertical lines, NOT the bookmark diamond. The two must
     * not look alike: one is yours and one is a guess, and telling them apart at a glance is
     * the whole reason this grid is editable rather than authoritative.
     */
    private void drawBeatMarkers(@NonNull Canvas canvas, long visStartMs, long visEndMs) {
        if (beatsMs.length == 0) return;
        beatPaint.setColor(0x99FBBF24);
        beatPaint.setStrokeWidth(Math.max(1f, 1.2f * density));
        float top = minimapHeightPx;
        float bottom = top + bookmarkSizePx * 1.6f;
        for (long b : beatsMs) {
            if (b < visStartMs || b > visEndMs) continue;
            float x = timeToX(b);
            canvas.drawLine(x, top, x, bottom, beatPaint);
        }
    }

    public void setBookmarks(@Nullable List<Long> marks) {
        bookmarksMs.clear();
        if (marks != null) bookmarksMs.addAll(marks);
        java.util.Collections.sort(bookmarksMs);
        invalidate();
    }

    @NonNull
    public List<Long> getBookmarks() { return new ArrayList<>(bookmarksMs); }

    /** True when {@code y} is inside the ruler band (below the minimap, above the tracks). */
    private boolean isRulerBandY(float y) {
        return y >= minimapHeightPx && y < rulerHeightPx;
    }

    /** Bookmark-hit tolerance in ms at the current zoom. */
    private long bookmarkTolMs() {
        float pxPerMs = dpPerSecondPx / 1000f;
        if (pxPerMs <= 0f) return 200L;
        return (long) (bookmarkHitPx / pxPerMs);
    }

    /** Index of the nearest bookmark within tolerance of {@code timeMs}, or -1. */
    private int bookmarkIndexNear(long timeMs) {
        long tol = bookmarkTolMs();
        int best = -1;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < bookmarksMs.size(); i++) {
            long d = Math.abs(bookmarksMs.get(i) - timeMs);
            if (d <= tol && d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** Long-press semantics: remove the bookmark under the finger, else drop a new one. */
    private void toggleBookmarkNear(long timeMs) {
        int hit = bookmarkIndexNear(timeMs);
        if (hit >= 0) {
            bookmarksMs.remove(hit);
        } else {
            bookmarksMs.add(timeMs);
            java.util.Collections.sort(bookmarksMs);
        }
        if (bookmarkListener != null) bookmarkListener.onBookmarksChanged(getBookmarks());
    }

    /**
     * Diamond markers on the ruler. Called from {@link #drawRuler} (content space,
     * inside the scroll translate) with the already-computed visible window so the
     * cost stays viewport-bounded like the ruler ticks.
     */
    private void drawBookmarkGlyphs(@NonNull Canvas canvas, long visStartMs, long visEndMs) {
        // Beats first, so a user's own bookmark always paints OVER the machine's guess.
        drawBeatMarkers(canvas, visStartMs, visEndMs);
        if (bookmarksMs.isEmpty()) return;
        long tol = bookmarkTolMs();
        float half = bookmarkSizePx;
        float cy = minimapHeightPx + half + 1f * density;
        for (int i = 0; i < bookmarksMs.size(); i++) {
            long bm = bookmarksMs.get(i);
            if (bm < visStartMs - tol || bm > visEndMs + tol) continue;
            float x = timeToX(bm);
            bookmarkPath.rewind();
            bookmarkPath.moveTo(x, cy - half);
            bookmarkPath.lineTo(x + half, cy);
            bookmarkPath.lineTo(x, cy + half);
            bookmarkPath.lineTo(x - half, cy);
            bookmarkPath.close();
            canvas.drawPath(bookmarkPath, bookmarkPaint);
        }
    }

    private void drawLoopArrow(Canvas canvas, float cx, float cy, float size, boolean backward) {
        float half = size / 2f;
        float shaftEnd = backward ? cx + half : cx - half;
        float headBase = backward ? cx + half * 0.3f : cx - half * 0.3f;
        float headTip = backward ? cx + half : cx - half;
        float headSpan = half * 0.6f;
        // Shaft
        canvas.drawLine(backward ? headBase : cx, cy, shaftEnd, cy, loopPaint);
        // Arrowhead
        float dir = backward ? 1f : -1f;
        canvas.drawLine(headTip, cy, headBase, cy - headSpan, loopPaint);
        canvas.drawLine(headTip, cy, headBase, cy + headSpan, loopPaint);
    }

    /**
     * Trim handles at left and right edges of the selected segment.
     * Fully rounded handles for seamless blend with segment curves.
     */
    private void drawTrimHandles(Canvas canvas, RectF seg) {
        float hTop = seg.top - handleOverhangPx;
        float hBot = seg.bottom + handleOverhangPx;
        float cornerRadius = segmentCornerPx;

        if (activeDrag == Drag.LEFT_HANDLE) {
            if (trimDragX > seg.left) {
                // Trimming: dim overlay from seg.left to handle
                canvas.drawRect(seg.left, seg.top, trimDragX, seg.bottom, trimOverlayPaint);
            } else if (trimDragX < seg.left) {
                // Recovering: green overlay from handle to seg.left
                canvas.drawRect(trimDragX, seg.top, seg.left, seg.bottom, trimRecoverPaint);
            }
            // Left handle at drag position
            RectF leftH = new RectF(trimDragX - handleWidthPx / 2f, hTop,
                    trimDragX + handleWidthPx / 2f, hBot);
            canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, leftH);
            // Right handle at segment edge (unchanged)
            RectF rightH = new RectF(seg.right - handleWidthPx, hTop, seg.right, hBot);
            canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, rightH);
        } else if (activeDrag == Drag.RIGHT_HANDLE) {
            // Left handle at segment edge (unchanged)
            RectF leftH = new RectF(seg.left, hTop, seg.left + handleWidthPx, hBot);
            canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, leftH);
            if (trimDragX < seg.right) {
                // Trimming: dim overlay from handle to seg.right
                canvas.drawRect(trimDragX, seg.top, seg.right, seg.bottom, trimOverlayPaint);
            } else if (trimDragX > seg.right) {
                // Recovering: green overlay from seg.right to handle
                canvas.drawRect(seg.right, seg.top, trimDragX, seg.bottom, trimRecoverPaint);
            }
            // Right handle at drag position
            RectF rightH = new RectF(trimDragX - handleWidthPx / 2f, hTop,
                    trimDragX + handleWidthPx / 2f, hBot);
            canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, rightH);
        } else {
            // Normal: handles at segment edges
            RectF leftH = new RectF(seg.left, hTop, seg.left + handleWidthPx, hBot);
            canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, leftH);
            RectF rightH = new RectF(seg.right - handleWidthPx, hTop, seg.right, hBot);
            canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, rightH);
        }
    }

    private void drawNotch(Canvas canvas, RectF hr) {
        float cx = hr.centerX(), cy = hr.centerY();
        float half = handleNotchHeightPx / 2f;
        RectF n = new RectF(cx - handleNotchWidthPx / 2f, cy - half,
                            cx + handleNotchWidthPx / 2f, cy + half);
        canvas.drawRoundRect(n, handleNotchWidthPx / 2f, handleNotchWidthPx / 2f, handleNotchPaint);
    }

    private void drawTransitionTrimHandles(Canvas canvas, RectF rect) {
        float hTop = rect.top - handleOverhangPx;
        float hBot = rect.bottom + handleOverhangPx;
        float cornerRadius = 3f * density;
        float leftX = (activeDrag == Drag.TRANSITION_LEFT_HANDLE) ? transitionDragX : rect.left;
        float rightX = (activeDrag == Drag.TRANSITION_RIGHT_HANDLE) ? transitionDragX : rect.right;
        RectF leftH = new RectF(leftX - handleWidthPx / 2f, hTop,
                leftX + handleWidthPx / 2f, hBot);
        RectF rightH = new RectF(rightX - handleWidthPx / 2f, hTop,
                rightX + handleWidthPx / 2f, hBot);
        // BLUE handles for a transition's resize grips (vs GREEN for clean clip-trim edges) so it's
        // obvious what you're grabbing.
        handlePaint.setColor(COLOR_TRANSITION_SEL);
        handleNotchPaint.setColor(0xBB44444F);
        canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
        canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
        drawNotch(canvas, leftH);
        drawNotch(canvas, rightH);
        handlePaint.setColor(COLOR_HANDLE);
        handleNotchPaint.setColor(COLOR_HANDLE_NOTCH);
    }

    /**
     * SPEC_N §5 — may the spine's fade veils + knobs paint at all?
     *
     * <p>Three things must be true: something is selected, the selection came from a
     * DELIBERATE TAP on that segment (not from the playhead landing on it), and the spine is
     * not collapsed (§3: a control you cannot usefully hit is worse than no control).</p>
     */
    private boolean spineFadeControlsVisible() {
        if (spineTapArmedClipId == null || isSpineCollapsed()) return false;
        if (selectedIndex < 0 || selectedIndex >= segRects.size()
                || selectedIndex >= segments.size()) return false;
        return spineTapArmedClipId.equals(segments.get(selectedIndex).clipId);
    }

    /**
     * SPEC_N §5 — every selection change routes through here. A settled selection on a
     * DIFFERENT clip than the tapped one disarms the fade knobs (the playhead-driven case
     * JoyRaptor does not want them for); a transient {@code -1} in the middle of the tap cascade
     * described on {@link #spineTapArmedClipId} does not.
     */
    private void noteSpineSelectionChanged(int newSelected) {
        if (spineTapArmedClipId == null) return;
        if (newSelected < 0 || newSelected >= segments.size()) return;
        if (!spineTapArmedClipId.equals(segments.get(newSelected).clipId)) {
            spineTapArmedClipId = null;
        }
    }

    /**
     * SPEC_N §3 — the COLLAPSED spine: one thin bar per clip so the cuts are still readable,
     * drawn in the same content space the full tape uses (so it scrolls and lines up with
     * timeToX identically). The selected clip keeps its green tint, which is why collapsing
     * cannot change the spine's selection — it only changes how the selection LOOKS.
     */
    private void drawCollapsedSpine(Canvas canvas, float visLeft, float visRight) {
        float corner = 2f * density;
        for (int i = 0; i < segRects.size() && i < segments.size(); i++) {
            RectF r = segRects.get(i);
            if (r.right < visLeft || r.left > visRight) continue;
            SegmentData sd = segments.get(i);
            boolean sel = (i == selectedIndex);
            // 1px display-only inset, the same trick drawSegment uses, so abutting clips
            // still read as separate blocks without perturbing the time mapping.
            collapsedStripRect.set(r.left + density, r.top,
                    Math.max(r.left + density, r.right - density), r.bottom);

            // SPEC_V §2 — THE TAPE STAYS VISIBLE WHILE COLLAPSED. JoyRaptor: "when it's
            // collapsed, I can't see the frames because the black bars go on top ... the
            // thumbnails tape [should] have higher [z] level." So the frames are drawn HERE,
            // over the track background and the segment block, through the SAME helper the
            // expanded tape uses — one tiling path, so the two cannot drift apart. This
            // supersedes SPEC_N §3's "no thumbnails are loaded while collapsed"; the
            // extraction saving it was protecting moves into spineThumbCount/spineThumbSizePx,
            // which sample a collapsed strip at a fifth of the frames and a sixteenth of the
            // pixels rather than not at all.
            loadThumbnailsForSegment(i);
            List<Bitmap> thumbs = thumbnailsCache.get(thumbVariantKey(sd));
            if (thumbs != null && !thumbs.isEmpty()) {
                drawThumbnailsForSegment(canvas, collapsedStripRect, thumbs, sel, sd);
            } else {
                segmentPaint.setColor(sel ? COLOR_SEGMENT_SEL : COLOR_SEGMENT);
                canvas.drawRoundRect(collapsedStripRect, corner, corner, segmentPaint);
            }
            if (sel) {
                borderPaint.setColor(COLOR_BORDER_SEL);
                canvas.drawRoundRect(collapsedStripRect, corner, corner, borderPaint);
            }
        }

        // SPEC_V §2 — MAKE IT READ AS THE SPINE. JoyRaptor: "when I collapse the main spine,
        // I can't see where it is." A collapsed lane row is a flat grey bar and so was this,
        // which is exactly why it disappeared into the stack. Two cues, both already the
        // spine's own vocabulary and neither costing a row of height:
        //   • the filmstrip itself — no other row draws full-bleed video frames;
        //   • a hairline of the film rail's own near-black along the top and bottom edge, so
        //     the strip reads as a squashed piece of film rather than as one more lane bar.
        // (The third cue, the caret disc in the left gutter, is already drawn in screen space
        // by drawSpineCollapseCaret and lands on top of this.)
        if (!segRects.isEmpty()) {
            float hair = Math.max(1f, 1.2f * density);
            float top = segRects.get(0).top;
            float bot = segRects.get(0).bottom;
            filmPaint.setStyle(Paint.Style.FILL);
            // COLOR_FILM_EDGE, not COLOR_FILM_RAIL. When the spine is collapsed there is no
            // room for rails and perforations, so this hairline IS the film; drawing it in
            // the base colour made it 1.17:1 against the ground and it disappeared for the
            // same reason the expanded strip did.
            filmPaint.setColor(COLOR_FILM_EDGE);
            canvas.drawRect(visLeft, top, visRight, top + hair, filmPaint);
            canvas.drawRect(visLeft, bot - hair, visRight, bot, filmPaint);
        }
    }

    /**
     * SPEC_N §3 — the spine's collapse caret, in SCREEN space at the same gutter x as every
     * lane row's caret. A dark disc sits behind it because, unlike a lane header, the spine's
     * left edge can have thumbnails scrolled under it.
     */
    private void drawSpineCollapseCaret(Canvas canvas, float tTop, float tBot) {
        if (segments.isEmpty()) { spineCaretRect.setEmpty(); return; }
        float size = layerRowRenderer.caretSizePx();
        float cx = layerRowRenderer.caretCenterXPx();
        float cy = (tTop + tBot) / 2f;
        spineCaretRect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        // SPEC_U §1: the disc is NEW chrome introduced with the collapsible spine, so it takes
        // the gutter's neutral grey (COLOR_RULER_BG) at 80% rather than the master track's
        // slightly blue film-black — the caret sits in the same left gutter as every lane
        // caret, and those now read pure neutral too.
        segmentPaint.setColor(0xCC000000 | (COLOR_RULER_BG & 0x00FFFFFF));
        canvas.drawCircle(cx, cy, size * 0.85f, segmentPaint);
        layerRowRenderer.drawCollapseCaret(canvas, spineCaretRect, isSpineCollapsed());
    }

    /**
     * SPEC_N §3 — toggle the spine's collapse. Routed through the SAME
     * {@code onTrackHeaderAction(track, CARET)} path a lane row's caret uses, with the master
     * track: that one method already flips the flag in {@code Timeline}'s persistent
     * side-table, records it as ONE undo step, prunes defaults so an expanded spine writes
     * nothing, and schedules the autosave. Following the existing pattern rather than
     * inventing a second collapse mechanism is the spec's explicit instruction — and it is
     * why this feature needed no storage-schema change at all.
     */
    private void toggleSpineCollapsed() {
        if (liveTimeline == null || trackHeaderActionListener == null) return;
        // §5: a collapse hides the knobs, so the deliberate-tap arming must not survive it.
        spineTapArmedClipId = null;
        final boolean collapsing = !isSpineCollapsed();
        trackHeaderActionListener.onTrackHeaderAction(liveTimeline.getMasterTrack(),
                com.fadcam.ui.faditor.layers.LayerRowRenderer.HitZone.CARET);

        // SPEC_V §4 — the audio drawer travels with the spine, and remembers. Collapsing
        // with the shelf out retracts it (and reclaims its band, which the collapsed spine
        // was still reserving); expanding puts it back ONLY if it was out. A collapse over
        // a closed drawer remembers nothing, so expanding cannot surprise the user with a
        // shelf they did not open. See spineCollapseReopenDrawers for why this is view state.
        if (collapsing) {
            spineCollapseReopenDrawers = !clipAudioDrawerOpen.isEmpty();
            if (spineCollapseReopenDrawers) setAllClipAudioDrawers(false);
        } else if (spineCollapseReopenDrawers) {
            spineCollapseReopenDrawers = false;
            setAllClipAudioDrawers(true);
        }

        // SPEC_V §3 — REBUILD THE RECTS. The spine's height just changed, and since
        // SPEC_U §4 that no longer changes THIS VIEW'S height (the freed px go to the layer
        // band), so onSizeChanged — the only thing that used to call computeRects here —
        // may never fire. See segRectsSpineTopPx.
        computeRects();
        requestLayout();
        invalidate();
    }

    /**
     * SPEC_V §4 — drive every (non-image) clip's audio drawer to one state. Same per-clip
     * animation the double-tap shelf toggle uses ({@link #toggleLayerAudioDrawers}); this
     * variant just does not need a clip to have been tapped. Idempotent per clip.
     */
    private void setAllClipAudioDrawers(boolean open) {
        for (SegmentData sd : segments) {
            if (sd.clipId == null || sd.isImageClip) continue;
            if (clipAudioDrawerOpen.contains(sd.clipId) != open) {
                toggleClipAudioDrawer(sd.clipId);
            }
        }
    }

    /**
     * FADE_KNOBS §2.5 — the spine's dark fade veil, drawn UNDER the trim bars. Same grammar
     * as the lane-row veil: near-black wedge + a razor-thin high-contrast line on the sloped
     * edge (JoyRaptor: "razor thin green line", same as the other elements' diagonals). On the
     * spine the wedge reads literally — the fade IS to black.
     */
    private void drawMasterFadeVeils(Canvas canvas, RectF seg) {
        Clip clip = selectedIndex < segments.size() ? segments.get(selectedIndex).clip : null;
        if (clip == null) return;
        long inMs = clip.getMasterFadeInMs();
        long outMs = clip.getMasterFadeOutMs();
        if (inMs <= 0 && outMs <= 0) return;
        canvas.save();
        canvas.clipRect(seg.left, seg.top, seg.right, seg.bottom);
        float edgeW = 1.4f * density;
        if (inMs > 0) {
            float fx = spineKnobX(true, seg, clip);
            // Hoisted + rewind() — this was a `new Path()` per frame.
            spineFadeVeilPath.rewind();
            spineFadeVeilPath.moveTo(seg.left, seg.top);
            spineFadeVeilPath.lineTo(fx, seg.top);
            spineFadeVeilPath.lineTo(seg.left, seg.bottom);
            spineFadeVeilPath.close();
            canvas.drawPath(spineFadeVeilPath, spineKnobVeilPaint);
            // razor-thin high-contrast diagonal on the wedge's sloped edge
            canvas.drawLine(fx, seg.top, seg.left, seg.bottom, spineDiagPaint(edgeW));
        }
        if (outMs > 0) {
            float fx = spineKnobX(false, seg, clip);
            spineFadeVeilPath.rewind();
            spineFadeVeilPath.moveTo(fx, seg.top);
            spineFadeVeilPath.lineTo(seg.right, seg.top);
            spineFadeVeilPath.lineTo(seg.right, seg.bottom);
            spineFadeVeilPath.close();
            canvas.drawPath(spineFadeVeilPath, spineKnobVeilPaint);
            canvas.drawLine(fx, seg.top, seg.right, seg.bottom, spineDiagPaint(edgeW));
        }
        canvas.restore();
    }

    /** The veil's diagonal line paint — handle-green, hairline, drawn under the trim bars. */
    private Paint spineDiagPaint(float widthPx) {
        spineDiagPaintImpl.setStrokeWidth(widthPx);
        return spineDiagPaintImpl;
    }

    /**
     * FADE_KNOBS §2.5 — the spine's fade knobs, drawn ABOVE the selected segment, AFTER the
     * transition bands (never painted under one) and styled like the lane-row knobs: dark
     * disc, 2dp handle-green stroke, inner dot in the same colour, hairline stem. Position IS
     * the readout: fade=0 sits on the trim edge, inwards by the fade length.
     */
    private void drawMasterFadeKnobs(Canvas canvas, RectF seg) {
        Clip clip = selectedIndex < segments.size() ? segments.get(selectedIndex).clip : null;
        if (clip == null) return;
        float knobR = SPINE_KNOB_R_DP * density;
        float offset = SPINE_KNOB_TOP_OFFSET_DP * density;
        float cy = seg.top - offset;
        // Knobs + stems (always, even at 0 — the readout that fade=0)
        float inX = spineKnobX(true, seg, clip);
        float outX = spineKnobX(false, seg, clip);
        // Was `new float[]{inX, outX}` per frame just to run a two-element loop.
        for (int i = 0; i < 2; i++) {
            float cx = i == 0 ? inX : outX;
            canvas.drawLine(cx, seg.top, cx, cy + knobR - density, spineStemPaint);
            canvas.drawCircle(cx, cy, knobR, spineKnobFillPaint);
            canvas.drawCircle(cx, cy, knobR, spineKnobStrokePaint);
            canvas.drawCircle(cx, cy, 3.4f * density, spineDotPaint);
        }
    }

    private void drawTransitionHelper(Canvas canvas, Transition t, RectF rect) {
        if (t == null || rect == null) return;
        if (t.isWipe() || t.type == Transition.Type.LINEAR_MIRROR_WIPE) {
            float cx = rect.centerX();
            float cy = rect.centerY();
            float len = Math.max(rect.width(), rect.height()) * 0.42f;
            transitionHelperPaint.setColor(0xCC35F6BF);
            canvas.drawLine(cx - len, cy, cx + len, cy, transitionHelperPaint);
            transitionHelperPaint.setColor(0xAA22D3EE);
            canvas.drawLine(cx, cy - len, cx, cy + len, transitionHelperPaint);
        } else if (t.type == Transition.Type.RADIAL) {
            float cx = rect.centerX();
            float cy = rect.centerY();
            float r = Math.min(rect.width(), rect.height()) * 0.34f;
            transitionHelperPaint.setColor(0xCC35F6BF);
            canvas.drawCircle(cx, cy, r, transitionHelperPaint);
            transitionHelperPaint.setColor(0xAA22D3EE);
            canvas.drawCircle(cx, cy, r * 0.68f, transitionHelperPaint);
        } else if (t.isPush()) {
            transitionHelperPaint.setColor(0x668A8A94);
            float cx = rect.centerX();
            float cy = rect.centerY();
            float s = Math.min(rect.width(), rect.height()) * 0.22f;
            Path tri = new Path();
            tri.moveTo(cx - s, cy - s);
            tri.lineTo(cx + s, cy - s);
            tri.lineTo(cx, cy);
            tri.close();
            canvas.drawPath(tri, transitionHelperPaint);
            tri.moveTo(cx + s, cy - s);
            tri.lineTo(cx + s, cy + s);
            tri.lineTo(cx, cy);
            tri.close();
            canvas.drawPath(tri, transitionHelperPaint);
            tri.moveTo(cx + s, cy + s);
            tri.lineTo(cx - s, cy + s);
            tri.lineTo(cx, cy);
            tri.close();
            canvas.drawPath(tri, transitionHelperPaint);
            tri.moveTo(cx - s, cy + s);
            tri.lineTo(cx - s, cy - s);
            tri.lineTo(cx, cy);
            tri.close();
            canvas.drawPath(tri, transitionHelperPaint);
            transitionHelperPaint.setColor(0xCC35F6BF);
            canvas.drawPath(tri, transitionHelperPaint);
        } else if (t.isGlitch()) {
            transitionHelperPaint.setColor(0xCC22D3EE);
            canvas.drawLine(rect.left + 4f * density, rect.top + 8f * density,
                    rect.right - 4f * density, rect.top + 8f * density, transitionHelperPaint);
            transitionHelperPaint.setColor(0xCCFF008C);
            canvas.drawLine(rect.left + 10f * density, rect.centerY(),
                    rect.right - 10f * density, rect.centerY(), transitionHelperPaint);
            transitionHelperPaint.setColor(0xCC22D3EE);
            canvas.drawLine(rect.left + 4f * density, rect.bottom - 8f * density,
                    rect.right - 4f * density, rect.bottom - 8f * density, transitionHelperPaint);
        } else if (t.isTvChannel()) {
            transitionHelperPaint.setColor(0xCCFFFFFF);
            canvas.drawLine(rect.left + 4f * density, rect.top + 8f * density,
                    rect.right - 4f * density, rect.top + 8f * density, transitionHelperPaint);
            transitionHelperPaint.setColor(0x66000000);
            canvas.drawLine(rect.left + 4f * density, rect.centerY(),
                    rect.right - 4f * density, rect.centerY(), transitionHelperPaint);
            transitionHelperPaint.setColor(0xCCFFFFFF);
            canvas.drawLine(rect.left + 4f * density, rect.bottom - 8f * density,
                    rect.right - 4f * density, rect.bottom - 8f * density, transitionHelperPaint);
        }
    }

// ══════════════════════════════════════════════════════════════════
    //  REORDER MODE DRAWING
    // ══════════════════════════════════════════════════════════════════

    private void drawReorderMode(@NonNull Canvas canvas) {
        int w = getWidth();
        if (w <= 0) w = getParent() instanceof View ? ((View) getParent()).getWidth() : 500;
        int h = getHeight();
        int n = reorderOrder.size();
        if (n == 0) return;

        // Dark overlay background
        canvas.drawRect(0, 0, w, h, reorderBgPaint2);

        // ── Top bar ──
        canvas.drawRect(0, 0, w, reorderBarHeightPx, reorderBarPaint);

        // Cancel "✕" on left
        reorderBtnTextPaint.setTextAlign(Paint.Align.LEFT);
        float btnY = reorderBarHeightPx / 2f + reorderBtnTextPaint.getTextSize() / 3f;
        canvas.drawText("✕  Cancel", reorderBtnPaddingPx, btnY, reorderBtnTextPaint);
        float cancelTextW = reorderBtnTextPaint.measureText("✕  Cancel");
        reorderCancelRect.set(0, 0, reorderBtnPaddingPx + cancelTextW + reorderBtnPaddingPx, reorderBarHeightPx);

        // "Link" button (between Cancel and the title) for broken clips
        boolean showLink = false;
        if (reorderSegmentIndex >= 0 && reorderSegmentIndex < segments.size()) {
            android.net.Uri uri = segments.get(reorderSegmentIndex).clip.getSourceUri();
            if (uri != null) {
                String scheme = uri.getScheme();
                if ("content".equals(scheme) || "file".equals(scheme)) {
                    java.io.File f = new java.io.File(uri.getPath() != null ? uri.getPath() : "");
                    showLink = !f.exists();
                }
            }
        }
        if (showLink) {
            reorderBtnTextPaint.setTextAlign(Paint.Align.LEFT);
            float linkX = reorderCancelRect.right + reorderBtnPaddingPx;
            reorderBtnTextPaint.setColor(Studio.DANGER);
            canvas.drawText("Link", linkX, btnY, reorderBtnTextPaint);
            reorderBtnTextPaint.setColor(COLOR_HANDLE);
            float linkTextW = reorderBtnTextPaint.measureText("Link");
            reorderLinkRect.set(linkX - reorderBtnPaddingPx / 2f, 0,
                    linkX + linkTextW + reorderBtnPaddingPx / 2f, reorderBarHeightPx);
        } else {
            reorderLinkRect.setEmpty();
        }

        // "Done" on right
        reorderBtnTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("Done", w - reorderBtnPaddingPx, btnY, reorderBtnTextPaint);
        float doneTextW = reorderBtnTextPaint.measureText("Done");
        reorderDoneRect.set(w - reorderBtnPaddingPx - doneTextW - reorderBtnPaddingPx, 0, w, reorderBarHeightPx);

        // Title "Reorder" in center (shifted slightly right if Link is shown)
        float cx = showLink
                ? (reorderLinkRect.right + reorderDoneRect.left) / 2f
                : w / 2f;
        reorderBtnTextPaint.setTextAlign(Paint.Align.CENTER);
        reorderBtnTextPaint.setColor(0xAAFFFFFF);
        canvas.drawText("Reorder", cx, btnY, reorderBtnTextPaint);
        reorderBtnTextPaint.setColor(COLOR_HANDLE);  // restore

        // ── Compute block layout ──
        float availW = w - reorderBtnPaddingPx * 2;
        float availH = h - reorderBarHeightPx - 16f * density;  // vertical space for blocks
        reorderGapPx = segmentGapPx * 2;
        reorderBlockSize = Math.min(availH * 0.8f,
                (availW - (n - 1) * reorderGapPx) / n);
        reorderBlockSize = Math.max(reorderBlockSize, 36f * density);  // minimum size
        float totalRowW = n * reorderBlockSize + (n - 1) * reorderGapPx;
        // When the row fits, center it (no scroll). When it overflows (many clips),
        // left-align and allow horizontal scrolling so every clip is reachable.
        if (totalRowW <= availW) {
            reorderRowStartX = (w - totalRowW) / 2f;
            reorderMaxScrollPx = 0f;
            reorderScrollPx = 0f;
        } else {
            reorderRowStartX = reorderBtnPaddingPx;
            reorderMaxScrollPx = totalRowW - availW;
            reorderScrollPx = Math.max(0f, Math.min(reorderMaxScrollPx, reorderScrollPx));
        }
        reorderRowCenterY = reorderBarHeightPx + (h - reorderBarHeightPx) / 2f;

        // ── Compute drop position if dragging ──
        int dropTarget = -1;
        if (reorderDragIdx >= 0) {
            dropTarget = computeReorderDropTarget(reorderDragCenterX);
        }

        // ── Draw blocks ──
        float drawIdx = 0;
        for (int i = 0; i < n; i++) {
            if (i == reorderDragIdx) continue; // Skip the dragged block (drawn later)

            // If a block is being dragged, shift blocks to show drop gap
            float visualPos = drawIdx;
            if (reorderDragIdx >= 0 && dropTarget >= 0) {
                int adjustedDrop = dropTarget;
                if (reorderDragIdx < dropTarget) adjustedDrop--;
                if (drawIdx >= adjustedDrop) visualPos = drawIdx + 1;
            }

            float bx = reorderRowStartX + visualPos * (reorderBlockSize + reorderGapPx) - reorderScrollPx;
            float by = reorderRowCenterY - reorderBlockSize / 2f;
            // Cull blocks scrolled fully off-screen (cheap, and keeps many-clip rows fast).
            if (bx + reorderBlockSize < 0 || bx > w) { drawIdx++; continue; }
            RectF blockRect = new RectF(bx, by, bx + reorderBlockSize, by + reorderBlockSize);

            canvas.drawRoundRect(blockRect, reorderBlockCornerPx, reorderBlockCornerPx, reorderBlockPaint2);

            // First-frame preview + segment number
            int segNum = reorderOrder.get(i) + 1;
            drawReorderBlockThumbnail(canvas, blockRect, reorderOrder.get(i));
            canvas.drawText(String.valueOf(segNum), blockRect.centerX(),
                    blockRect.centerY() + reorderNumPaint.getTextSize() / 3f, reorderNumPaint);

            drawIdx++;
        }

        // ── Draw drop indicator line ──
        if (reorderDragIdx >= 0 && dropTarget >= 0) {
            int adjustedDrop = dropTarget;
            if (reorderDragIdx < dropTarget) adjustedDrop--;
            float indicatorX = reorderRowStartX + adjustedDrop * (reorderBlockSize + reorderGapPx) - reorderGapPx / 2f - reorderScrollPx;
            float indicatorTop = reorderRowCenterY - reorderBlockSize / 2f - 4f * density;
            float indicatorBot = reorderRowCenterY + reorderBlockSize / 2f + 4f * density;
            canvas.drawLine(indicatorX, indicatorTop, indicatorX, indicatorBot, reorderDropIndicatorPaint);
        }

        // ── Draw dragged block last (on top) ──
        if (reorderDragIdx >= 0 && reorderDragIdx < n) {
            float bx = reorderDragCenterX - reorderBlockSize / 2f;
            float by = reorderDragCenterY - reorderBlockSize / 2f;
            RectF dragRect = new RectF(bx, by, bx + reorderBlockSize, by + reorderBlockSize);

            canvas.drawRoundRect(dragRect, reorderBlockCornerPx, reorderBlockCornerPx, reorderBlockDragPaint);
            drawReorderBlockThumbnail(canvas, dragRect, reorderOrder.get(reorderDragIdx));
            canvas.drawRoundRect(dragRect, reorderBlockCornerPx, reorderBlockCornerPx, reorderBlockSelectedPaint);

            int segNum = reorderOrder.get(reorderDragIdx) + 1;
            canvas.drawText(String.valueOf(segNum), dragRect.centerX(),
                    dragRect.centerY() + reorderNumPaint.getTextSize() / 3f, reorderNumPaint);
        }

        // ── Minimap strip (same thin-bar style as the main timeline) so the user
        //    can see WHERE the dragged clip sits in the whole project. ──
        drawReorderMinimap(canvas, w);
    }

    /**
     * Thin overview strip shown during reorder: every clip as a duration-
     * proportional bar in the current order, with the clip being dragged
     * highlighted green — so it's obvious which clip a block represents.
     */
    private void drawReorderMinimap(@NonNull Canvas canvas, int viewW) {
        if (totalEffectiveMs <= 0 || reorderOrder.isEmpty()) return;
        float margin = 8f * density;
        float bot = getHeight() - 6f * density;
        float top = bot - Math.max(6f * density, minimapHeightPx - 6f * density);
        float stripW = viewW - margin * 2;
        if (stripW <= 0) return;

        // Remember the strip bounds so the touch handler can map a finger position
        // over the minimap to a scroll position (drag-to-jump).
        reorderMinimapRect.set(margin, top, margin + stripW, bot);

        long cumul = 0;
        float gap = Math.min(1.5f * density, stripW / (reorderOrder.size() * 8f));
        for (int i = 0; i < reorderOrder.size(); i++) {
            int segIdx = reorderOrder.get(i);
            if (segIdx < 0 || segIdx >= segments.size()) continue;
            SegmentData sd = segments.get(segIdx);
            float x0 = margin + (cumul / (float) totalEffectiveMs) * stripW;
            cumul += sd.effectiveMs;
            float x1 = margin + (cumul / (float) totalEffectiveMs) * stripW;
            boolean dragged = (i == reorderDragIdx);
            minimapBlockPaint.setColor(dragged ? Studio.GO : Studio.INK_OFF);
            float barTop = dragged ? top - 2f * density : top;
            canvas.drawRoundRect(x0, barTop, Math.max(x0 + 1, x1 - gap), bot,
                    2f * density, 2f * density, minimapBlockPaint);
        }

        // Viewport box: shows which slice of the (overflowing) block row is on
        // screen, and highlights while the user is dragging over the minimap.
        if (reorderMaxScrollPx > 0f) {
            float totalRowW = reorderMaxScrollPx + (viewW - reorderBtnPaddingPx * 2);
            float fL = reorderScrollPx / totalRowW;
            float fR = (reorderScrollPx + (viewW - reorderBtnPaddingPx * 2)) / totalRowW;
            float vL = margin + Math.max(0f, fL) * stripW;
            float vR = margin + Math.min(1f, fR) * stripW;
            // Configure explicitly — the normal-mode minimap (which usually sets
            // these) doesn't run while reordering.
            boolean active = reorderMinimapDragging || reorderMinimapPanning;
            // The same window while it is being DRAGGED. It gets a faint fill only in that
            // moment — you are holding it, so it is worth making solid enough to track — and
            // goes back to a bare frame the instant you let go.
            minimapViewportPaint.setStyle(Paint.Style.FILL);
            minimapViewportPaint.setColor(active ? Studio.alpha(Studio.ARMED, 0x33) : 0x00000000);
            minimapViewportBorderPaint.setColor(Studio.ARMED);
            minimapViewportBorderPaint.setStyle(Paint.Style.STROKE);
            minimapViewportBorderPaint.setStrokeWidth(Math.max(1f, 1f * density));
            canvas.drawRect(vL, 1.5f * density, vR, minimapHeightPx - 1.5f * density,
                    minimapViewportPaint);
            canvas.drawRect(vL, top - 2f * density, vR, bot + 2f * density,
                    minimapViewportBorderPaint);
        }
    }

    /**
     * Draws the segment's first cached thumbnail (center-cropped) inside a
     * reorder block so users see WHAT they're rearranging, with a slight
     * darken so the number badge stays readable.
     */
    private void drawReorderBlockThumbnail(@NonNull Canvas canvas, RectF rect, int segIdx) {
        if (segIdx < 0 || segIdx >= segments.size()) return;
        List<Bitmap> thumbs = thumbnailsCache.get(segments.get(segIdx).thumbKey);
        if (thumbs == null || thumbs.isEmpty()) {
            // Thumbnails load lazily (only for clips visible in the main timeline).
            // Reorder blocks can reference clips that were never on-screen, so
            // request extraction here too; the block draws without an image until
            // it arrives, then the load callback invalidates and redraws.
            loadThumbnailsForSegment(segIdx);
            return;
        }
        Bitmap thumb = thumbs.get(0);
        if (thumb == null || thumb.isRecycled()) return;

        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(rect, reorderBlockCornerPx, reorderBlockCornerPx, Path.Direction.CW);
        canvas.clipPath(clipPath);

        // Center-crop the bitmap into the square block
        float scale = Math.max(rect.width() / thumb.getWidth(), rect.height() / thumb.getHeight());
        float dw = thumb.getWidth() * scale;
        float dh = thumb.getHeight() * scale;
        RectF dest = new RectF(
                rect.centerX() - dw / 2f, rect.centerY() - dh / 2f,
                rect.centerX() + dw / 2f, rect.centerY() + dh / 2f);
        canvas.drawBitmap(thumb, null, dest, null);

        // Darken for number readability
        segmentPaint.setColor(0x55000000);
        canvas.drawRect(rect, segmentPaint);
        canvas.restore();
    }

    private int computeReorderDropTarget(float fingerX) {
        int n = reorderOrder.size();
        for (int i = 0; i < n; i++) {
            float blockCenterX = reorderRowStartX + i * (reorderBlockSize + reorderGapPx)
                    + reorderBlockSize / 2f - reorderScrollPx;
            if (fingerX < blockCenterX) return i;
        }
        return n; // drop at end
    }

    public void enterReorderMode() {
        isReorderMode = true;
        reorderOrder.clear();
        for (int i = 0; i < segments.size(); i++) reorderOrder.add(i);
        reorderDragIdx = -1;
        reorderScrollPx = 0f;
        reorderMaxScrollPx = 0f;
        reorderMinimapDragging = false;
        reorderSegmentIndex = downSegIndex;
        // §3A.5b — remember where the playhead was, so BACKING OUT can put it back. Captured on
        // entry rather than at the tap, because that is the last moment it is still the user's
        // chosen position regardless of how reorder was reached (hold-release today, double-tap
        // later, where the first tap will have seeked).
        reorderEntryPlayheadMs = playheadPositionMs;
        if (listener != null) listener.onReorderModeChanged(true);
        invalidate();
    }

    private void exitReorderMode(boolean commit) {
        // ⚠ ORDER MATTERS (user, 2026-08-04): put the playhead back BEFORE isReorderMode flips
        // and before anything repaints, so the move happens while the reorder UI is still
        // covering it. Restoring after the mode exits produces a visible jog the instant the
        // normal view returns — "that would ruin the illusion of smoothness".
        // Only on BACK-OUT: a committed reorder has rearranged the clips, and forcing the
        // playhead back to a time that now shows different footage would be worse than leaving it.
        if (!commit && reorderEntryPlayheadMs >= 0) {
            // setPlayheadPositionMs only assigns + invalidates — it does NOT seek the player, so
            // on its own this left the marker at A and the picture at B, and the play button then
            // resumed from B. Seek as well, so the restore is real.
            setPlayheadPositionMs(reorderEntryPlayheadMs);
            if (listener != null) {
                int seg = getSegmentAtPlayhead();
                if (seg >= 0 && seg < segments.size()) {
                    SegmentData sd = segments.get(seg);
                    long within = Math.max(0, reorderEntryPlayheadMs - getSegmentStartTimeMs(seg));
                    // ⚠ onPlayheadSeeked's fraction is of the FULL SOURCE duration, not of the
                    // effective (trimmed, speed-scaled) segment. The first version of this used
                    // within/effectiveMs, which on any trimmed clip resolved to a source time past
                    // the out-point and clamped the player to the clip's END — and the consumer's
                    // setPlayheadFraction then wrote that back over the marker too, clobbering the
                    // restore this block exists to perform. Worse than the bug it fixed, and
                    // invisible on an untrimmed 1x clip, i.e. invisible in a smoke test.
                    // Same expression as updatePlayheadFromX.
                    long sourceMs = sd.inPointMs + (long) (within * sd.speed);
                    float frac = sd.sourceDurationMs > 0
                            ? Math.max(0f, Math.min(1f, (float) sourceMs / sd.sourceDurationMs))
                            : 0f;
                    listener.onPlayheadSeeked(seg, frac, false);
                }
            }
        }
        reorderEntryPlayheadMs = -1L;
        isReorderMode = false;
        if (commit && listener != null) {
            // Apply the permutation using a sequence of moveClip operations
            // Build the desired final order
            List<Integer> desired = new ArrayList<>(reorderOrder);
            List<Integer> current = new ArrayList<>();
            for (int i = 0; i < desired.size(); i++) current.add(i);

            for (int targetPos = 0; targetPos < desired.size(); targetPos++) {
                int wantIdx = desired.get(targetPos);
                int currentPos = current.indexOf(wantIdx);
                if (currentPos != targetPos) {
                    listener.onSegmentReordered(currentPos, targetPos);
                    // Reflect the move in our tracking list
                    int moved = current.remove(currentPos);
                    current.add(targetPos, moved);
                }
            }
        }
        reorderOrder.clear();
        reorderDragIdx = -1;
        if (listener != null) listener.onReorderModeChanged(false);
        invalidate();
    }

    private boolean handleReorderTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int n = reorderOrder.size();

        // Prevent parent (HorizontalScrollView) from intercepting
        getParent().requestDisallowInterceptTouchEvent(true);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Check cancel button
                if (reorderCancelRect.contains(x, y)) {
                    exitReorderMode(false);
                    return true;
                }
                // Check link button
                if (!reorderLinkRect.isEmpty() && reorderLinkRect.contains(x, y)) {
                    if (listener != null) listener.onReorderLinkRequested(reorderSegmentIndex);
                    exitReorderMode(false);
                    return true;
                }
                // Check done button
                if (reorderDoneRect.contains(x, y)) {
                    exitReorderMode(true);
                    return true;
                }
                // Check if touching a block
                for (int i = 0; i < n; i++) {
                    float bx = reorderRowStartX + i * (reorderBlockSize + reorderGapPx) - reorderScrollPx;
                    float by = reorderRowCenterY - reorderBlockSize / 2f;
                    if (x >= bx && x <= bx + reorderBlockSize
                            && y >= by && y <= by + reorderBlockSize) {
                        reorderDragIdx = i;
                        reorderDragCenterX = bx + reorderBlockSize / 2f;
                        reorderDragCenterY = by + reorderBlockSize / 2f;
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        invalidate();
                        return true;
                    }
                }
                // Not on a block → if the row overflows and the touch is on the
                // minimap, start scrub-panning: drag along the minimap to bring any
                // part of the row into view BEFORE grabbing a clip, so you can see
                // what you're about to grab and where it'll land.
                if (reorderMaxScrollPx > 0f && !reorderMinimapRect.isEmpty()
                        && y >= reorderMinimapRect.top - 12f * density) {
                    reorderMinimapPanning = true;
                    float frac = (x - reorderMinimapRect.left) / reorderMinimapRect.width();
                    reorderScrollPx = Math.max(0f, Math.min(1f, frac)) * reorderMaxScrollPx;
                    invalidate();
                    return true;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (reorderMinimapPanning) {
                    float frac = (x - reorderMinimapRect.left) / reorderMinimapRect.width();
                    reorderScrollPx = Math.max(0f, Math.min(1f, frac)) * reorderMaxScrollPx;
                    invalidate();
                    return true;
                }
                if (reorderDragIdx >= 0) {
                    reorderDragCenterX = x;
                    reorderDragCenterY = y;
                    // Dragging the block onto the overview minimap jumps the row to
                    // that part of the project — fast travel without hovering at an
                    // edge waiting for auto-scroll.
                    if (reorderMaxScrollPx > 0f && !reorderMinimapRect.isEmpty()
                            && y >= reorderMinimapRect.top - 8f * density) {
                        reorderMinimapDragging = true;
                        stopEdgeScroll();
                        float frac = (x - reorderMinimapRect.left) / reorderMinimapRect.width();
                        frac = Math.max(0f, Math.min(1f, frac));
                        reorderScrollPx = frac * reorderMaxScrollPx;
                    } else {
                        reorderMinimapDragging = false;
                        // Near a screen edge → auto-scroll the row (only meaningful
                        // when it overflows).
                        if (reorderMaxScrollPx > 0f) {
                            startOrStopEdgeScroll(x);
                        }
                    }
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopEdgeScroll();
                reorderMinimapDragging = false;
                reorderMinimapPanning = false;
                if (reorderDragIdx >= 0) {
                    int dropTarget = computeReorderDropTarget(reorderDragCenterX);
                    // Clamp
                    dropTarget = Math.max(0, Math.min(dropTarget, n));
                    // Perform the visual reorder
                    int movedItem = reorderOrder.remove(reorderDragIdx);
                    int insertAt = dropTarget;
                    if (reorderDragIdx < dropTarget) insertAt--;
                    insertAt = Math.max(0, Math.min(insertAt, reorderOrder.size()));
                    reorderOrder.add(insertAt, movedItem);
                    reorderDragIdx = -1;
                    invalidate();
                }
                return true;
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════
    //  TOUCH HANDLING
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void computeScroll() {
        super.computeScroll();
        
        // Handle fling animation
        if (flingScroller.computeScrollOffset()) {
            float newScrollOffset = flingScroller.getCurrX();

            // Throttle: skip the seek+redraw when the scroller reports the same rounded pixel
            // offset as last frame (happens repeatedly near the tail of a fling as velocity
            // decays toward zero). Still keep the animation alive via postInvalidateOnAnimation
            // so the scroller itself keeps ticking toward isFinished().
            boolean offsetChanged = Math.round(newScrollOffset) != Math.round(lastFlingScrollOffsetPx);
            if (offsetChanged) {
                lastFlingScrollOffsetPx = newScrollOffset;

                // Calculate playhead position from scroll offset
                float centerX = getWidth() / 2f;
                float playheadX = centerX + newScrollOffset;

                // Update playhead position (will trigger seek and centerPlayhead)
                updatePlayheadFromX(playheadX);
            }

            // Continue animation (scroller needs repeated computeScrollOffset() calls to
            // finish regardless of whether this particular frame moved the playhead)
            postInvalidateOnAnimation();
        } else if (flingJustFinished) {
            // Fling completed — notify listener so userDragging gets reset
            flingJustFinished = false;
            if (listener != null) {
                FLog.d(TAG, "computeScroll: fling finished, calling onPlayheadDragFinished");
                listener.onPlayheadDragFinished();
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // GESTURE LIVENESS — tracked FIRST, before every branch below, because almost all of
        // them can return early (reorder, minimap, active pinch, marquee, post-pinch pan, the
        // audio-band taps, the slide double-tap). Any of those returns skips the shared
        // ACTION_UP block that ends a playhead drag, so a "drag is active" latch held by the
        // editor could outlive the finger. This flag cannot be skipped, so it is a truthful
        // answer to "is a finger still down on the timeline?" no matter which branch consumed
        // the event. See isGestureActive().
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureActive = true;
                // SPEC_U §2: the finger always wins over a reveal slide in flight.
                cancelBandRevealAnim();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gestureActive = false;
                // DIAGNOSTIC (2026-07-27, additive only — no control flow depends on this).
                // The device confirmation of the stranded-latch fix showed the SELF-HEAL firing
                // once, which means a stranding path OTHER than the post-pinch pan (fixed
                // directly) still exists. Rather than tag ten early-return sites, snapshot the
                // flags that DECIDE which branch is about to consume this release: read back in
                // the heal warning, it names the culprit the next time it happens. Remove with
                // PHDIAG once that path is closed.
                lastUpState = "action=" + (e.getActionMasked() == MotionEvent.ACTION_UP ? "UP" : "CANCEL")
                        + " reorder=" + isReorderMode
                        + " minimapDrag=" + minimapDragging
                        + " scaling=" + isScaling
                        + " marquee=" + marqueeMode
                        + " postPinchPan=" + postPinchPanActive
                        + " activeDrag=" + activeDrag
                        + " pointers=" + e.getPointerCount();
                break;
            default:
                break;
        }

        // Reorder mode intercepts all touch events
        if (isReorderMode) {
            return handleReorderTouch(e);
        }

        // Minimap strip scrubbing
        if (handleMinimapTouch(e)) {
            return true;
        }

        if (VLOG) FLog.d(TAG, "onTouchEvent: action=" + e.getActionMasked() + " x=" + e.getX() + " isScaling=" + isScaling + " activeDrag=" + activeDrag);
        
        // Let scale detector process ALL events (it needs to track for pinch detection)
        scaleDetector.onTouchEvent(e);

        // A second finger means a pinch, never a reorder/drag long-press — cancel
        // any pending long-press the instant multi-touch begins.
        if (e.getPointerCount() > 1) {
            longPressHandler.removeCallbacks(longPressRunnable);
            cancelMasterHeaderLongPress();
            cancelMarqueeTouch();
        }

        // If actually pinch-zooming, block other handlers
        if (isScaling) {
            if (VLOG) FLog.d(TAG, "onTouchEvent: consumed by active pinch zoom");
            return true;
        }

        // G8 marquee multi-select: while the select-mode toggle is armed, single-finger
        // touches are OWNED by the marquee (drag paints a selection box; tap toggles an
        // item; scrub is intentionally unavailable — that's what the mode toggle means,
        // contract §5.5). Placed AFTER the pinch handling so two fingers still zoom.
        if (marqueeMode != MarqueeMode.OFF && handleMarqueeTouch(e)) {
            return true;
        }

        // FOLLOW-UP 2: post-pinch handback — the finger that survived the pinch pans
        // the timeline immediately (no lift-and-retouch), re-anchored at its own
        // position so there's zero jump. Placed BEFORE the gesture-detector guard: the
        // detector missed the whole pinch (isScaling early-return) and its stale state
        // must not see this stream. A new POINTER_DOWN (re-pinch) or DOWN hands off.
        if (postPinchPanActive) {
            int ppAction = e.getActionMasked();
            if (ppAction == MotionEvent.ACTION_MOVE && e.getPointerCount() == 1) {
                float px = e.getX();
                if (Float.isNaN(postPinchLastX)) {
                    postPinchLastX = px;
                    // Restart velocity tracking from the re-anchor: this branch returns
                    // before the shared feed below, and pinch-era samples are garbage
                    // for the single surviving pointer anyway.
                    if (rowScrubVelocityTracker == null) rowScrubVelocityTracker = android.view.VelocityTracker.obtain();
                    else rowScrubVelocityTracker.clear();
                    rowScrubVelocityTracker.addMovement(e);
                } else {
                    if (rowScrubVelocityTracker != null) rowScrubVelocityTracker.addMovement(e);
                    float distanceX = postPinchLastX - px;
                    postPinchLastX = px;
                    float centerX = getWidth() / 2f;
                    updatePlayheadFromX(centerX + scrollOffsetPx + distanceX);
                }
                invalidate();
                return true;
            }
            if (ppAction == MotionEvent.ACTION_UP || ppAction == MotionEvent.ACTION_CANCEL) {
                postPinchPanActive = false;
                postPinchLastX = Float.NaN;
                // Same glide parity as the row-band scrub: a flick that ends the
                // pinch→pan motion flings like any other timeline swipe.
                boolean flung = false;
                if (ppAction == MotionEvent.ACTION_UP && rowScrubVelocityTracker != null) {
                    rowScrubVelocityTracker.computeCurrentVelocity(1000, maxFlingVelocityPx);
                    float vx = rowScrubVelocityTracker.getXVelocity();
                    if (Math.abs(vx) > minFlingVelocityPx) {
                        startPlayheadFling(vx);
                        flung = true;
                    }
                }
                // THE STRANDED-LATCH FIX (device-proven 2026-07-27, Note 20). This branch
                // returns before the shared ACTION_UP block, so it must end the drag itself.
                // Its ACTION_MOVE above calls updatePlayheadFromX -> onPlayheadSeeked(
                // isDragging=true), which latches the editor's userDragging; without this
                // notify the latch outlived the gesture and updatePlayheadPosition() then
                // early-returned forever, freezing the playhead/timeline/overlay visibility
                // while video and audio kept playing. It only bit after a ZOOM because the
                // pinch is what hands off to this pan, and only on a GENTLE release because
                // a flick starts a fling whose completion path already notifies — which is
                // exactly why the user saw it as intermittent and zoom-correlated.
                if (!flung && listener != null) listener.onPlayheadDragFinished();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            // POINTER_DOWN (a re-pinch starting) or an unexpected DOWN: hand off cleanly.
            postPinchPanActive = false;
            postPinchLastX = Float.NaN;
        }

        // When actively dragging a handle, audio clip, or reordering, bypass gesture detector
        // to prevent GestureDetector.onTouchEvent() returning true and blocking onMove/onUp.
        // M10 fix (pre-existing gap since M7): the M6/M7 row-gesture flags
        // (m7ItemGestureActive — an item move/trim on a Track row; m6RowDragActive —
        // vertical scroll within the row region) were NOT included in this guard, so
        // once onDown() armed a row-item MOVE (setting m7ItemGestureActive=true and
        // returning true from handleM6RowTouch), the VERY NEXT MotionEvent still fell
        // through to gestureDetector.onTouchEvent() here, whose onScroll() has no idea
        // a row gesture is active — it would seek the timeline playhead AND return
        // true, permanently starving onMove()/onRowBodyMove() of every subsequent
        // event for that gesture. This made row-item drags (and therefore M10's
        // cross-row / drop-to-new-layer detection, which lives entirely in
        // onRowBodyMove) unreliable on fast/continuous drags. Bypassing the gesture
        // detector while either row flag is set mirrors the existing bypass for
        // activeDrag exactly. Surface-overlap fix: also bypass while a
        // row-band touch's axis is undecided (m6RowPendingAxisDecision) or has already
        // resolved to horizontal scrub-passthrough (m6RowScrubPassthroughActive) — the
        // custom onMove below owns axis math for both, using its own downX-relative
        // deltas; letting the gesture detector's onScroll race it here would double-
        // drive (or steal) the scrub exactly like the M10 comment above describes for
        // item drags.
        if (activeDrag == Drag.NONE && !m7ItemGestureActive && !m7ItemPendingDown
                && !m6RowDragActive && !m6RowPendingAxisDecision && !m6RowScrubPassthroughActive) {
            // Let gesture detector process events only when no active drag. m7ItemPendingDown
            // is included so a body touch's follow-up MOVEs reach the custom onMove (which
            // owns the scrub-vs-pickup-vs-scroll disambiguation) instead of the detector's
            // onScroll racing it — exactly like m6RowPendingAxisDecision for empty space.
            boolean gestureEvent = gestureDetector.onTouchEvent(e);
            if (gestureEvent) {
                if (VLOG) FLog.d(TAG, "onTouchEvent: consumed by gesture detector");
                return true;
            }
        }
        
        // Handle custom touch logic for trim/reorder
        float x = e.getX(), y = e.getY();
        // Row-band scrub fling: capture velocity for every custom-path touch. Consumed
        // only when a scrub-passthrough lifts (see onUp) — harmless otherwise.
        int maskedAction = e.getActionMasked();
        // The EVENT's own clock, not ours. See lastTouchEventTimeMs.
        lastTouchEventTimeMs = e.getEventTime();
        if (maskedAction == MotionEvent.ACTION_DOWN) {
            if (rowScrubVelocityTracker == null) rowScrubVelocityTracker = android.view.VelocityTracker.obtain();
            else rowScrubVelocityTracker.clear();
            rowScrubVelocityTracker.addMovement(e);
        } else if (maskedAction == MotionEvent.ACTION_MOVE && rowScrubVelocityTracker != null) {
            rowScrubVelocityTracker.addMovement(e);
        }
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN: 
                if (VLOG) FLog.d(TAG, "onTouchEvent: ACTION_DOWN - calling onDown");
                return onDown(x, y);
            case MotionEvent.ACTION_MOVE: 
                return onMove(x, y);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: 
                if (VLOG) FLog.d(TAG, "onTouchEvent: ACTION_UP/CANCEL - calling onUp");
                return onUp(x, y, e.getAction() == MotionEvent.ACTION_UP);
        }
        return super.onTouchEvent(e);
    }

    // ═══════════ G8 marquee multi-select (gesture contract §5.5) ═══════════

    /** Marquee selection mode: OFF = normal gestures; INCLUSIVE = crossing (touch any
     *  part selects the whole object); EXCLUSIVE = window (only fully-enclosed objects). */
    public enum MarqueeMode { OFF, INCLUSIVE, EXCLUSIVE }

    /** Host callbacks for the marquee mode. */
    public interface MarqueeListener {
        /** Long-press on a selected item while a multi-selection is active → batch menu. */
        void onBatchActionRequested(
                @NonNull java.util.List<com.fadcam.ui.faditor.layers.LayerRowRenderer.ItemHit> items);
        /** The multi-selection changed (count 0 = cleared). */
        void onMarqueeSelectionChanged(int count);
    }

    private MarqueeMode marqueeMode = MarqueeMode.OFF;
    @Nullable private MarqueeListener marqueeListener;
    private final java.util.LinkedHashSet<String> marqueeSelectedIds =
            new java.util.LinkedHashSet<>();
    private boolean marqueeTouchActive = false;
    private boolean marqueeDragActive = false;
    private boolean marqueeBatchFired = false;
    /** JoyRaptor 2026-07-19: in select mode a drag STARTING ON AN OBJECT manipulates it
     *  (instant move, no hold needed); only empty space ropes a box. */
    @Nullable private com.fadcam.ui.faditor.layers.LayerRowRenderer.ItemHit marqueeDownItemHit;
    private boolean marqueeItemDragActive = false;
    private float marqueeDownViewX, marqueeDownViewY;
    /** Marquee corners are CONTENT-anchored (x = scrolled content-x, y = band-local
     *  content-y) so edge-scrolling extends the box instead of dragging it along. */
    private float marqueeAnchorContentX, marqueeAnchorLocalY;
    private float marqueeCurContentX, marqueeCurLocalY;
    private float marqueeLastViewX, marqueeLastViewY;
    private boolean marqueeEdgeScrollActive = false;
    private final android.graphics.RectF marqueeContentRect = new android.graphics.RectF();
    @Nullable private Paint marqueeFillPaint;
    @Nullable private Paint marqueeStrokePaint;

    public void setMarqueeListener(@Nullable MarqueeListener l) { this.marqueeListener = l; }

    @NonNull
    public MarqueeMode getMarqueeMode() { return marqueeMode; }

    /** Arm/cycle the marquee mode. Turning it OFF clears the multi-selection. */
    public void setMarqueeMode(@NonNull MarqueeMode mode) {
        if (marqueeMode == mode) return;
        marqueeMode = mode;
        cancelMarqueeTouch();
        if (mode == MarqueeMode.OFF && !marqueeSelectedIds.isEmpty()) {
            marqueeSelectedIds.clear();
            notifyMarqueeSelectionChanged();
        }
        invalidate();
    }

    /** Snapshot of the current multi-selection resolved to live track items. */
    @NonNull
    public java.util.List<com.fadcam.ui.faditor.layers.LayerRowRenderer.ItemHit>
            getMarqueeSelectedItems() {
        return layerRowRenderer.collectItemsByIds(marqueeSelectedIds);
    }

    public void clearMarqueeSelection() {
        if (marqueeSelectedIds.isEmpty()) return;
        marqueeSelectedIds.clear();
        notifyMarqueeSelectionChanged();
        invalidate();
    }

    private void notifyMarqueeSelectionChanged() {
        if (marqueeListener != null) {
            marqueeListener.onMarqueeSelectionChanged(marqueeSelectedIds.size());
        }
    }

    private final Runnable marqueeBatchLongPressRunnable = () -> {
        if (!marqueeTouchActive || marqueeDragActive || marqueeSelectedIds.isEmpty()) return;
        marqueeBatchFired = true;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        if (marqueeListener != null) {
            marqueeListener.onBatchActionRequested(getMarqueeSelectedItems());
        }
    };

    /** Owns every single-finger touch while select mode is armed. Always consumes. */
    private boolean handleMarqueeTouch(@NonNull MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                marqueeTouchActive = true;
                marqueeDragActive = false;
                marqueeBatchFired = false;
                // The marquee consumes the UP a post-pinch pan would normally reset on —
                // clear it here so a pinch during select mode can't leak a stale pan.
                postPinchPanActive = false;
                postPinchLastX = Float.NaN;
                marqueeDownViewX = x;
                marqueeDownViewY = y;
                marqueeLastViewX = x;
                marqueeLastViewY = y;
                marqueeAnchorContentX = x + scrollOffsetPx;
                marqueeAnchorLocalY = y - getM6RowsTopPx() + layerRowRenderer.getScrollOffsetPx();
                // Object-vs-empty routing (JoyRaptor 2026-07-19): remember what the touch
                // started on — a later drag manipulates the OBJECT; empty space ropes.
                marqueeDownItemHit = layerRowRenderer.hitTestItem(x + scrollOffsetPx, y,
                        getM6RowsTopPx(), totalEffectiveMs, this::timeToX, null);
                marqueeItemDragActive = false;
                // Batch long-press arms only when the touch starts ON a selected item.
                if (marqueeDownItemHit != null && !marqueeSelectedIds.isEmpty()
                        && marqueeSelectedIds.contains(marqueeDownItemHit.item.getId())) {
                    longPressHandler.postDelayed(marqueeBatchLongPressRunnable, ITEM_PICKUP_MS);
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!marqueeTouchActive || marqueeBatchFired) return true;
                marqueeLastViewX = x;
                marqueeLastViewY = y;
                float slop = 8f * density;
                if (!marqueeDragActive && !marqueeItemDragActive
                        && (Math.abs(x - marqueeDownViewX) > slop
                            || Math.abs(y - marqueeDownViewY) > slop)) {
                    longPressHandler.removeCallbacks(marqueeBatchLongPressRunnable);
                    if (marqueeDownItemHit != null && layerGestureController != null) {
                        // Drag started ON an object → instant pickup-move (no hold).
                        layerGestureController.onRowBodyDown(
                                marqueeDownViewX + scrollOffsetPx, marqueeDownViewY,
                                getM6RowsTopPx(), totalEffectiveMs, this::timeToX);
                        if (layerGestureController.beginPickup()) {
                            marqueeItemDragActive = true;
                        } else {
                            // Locked object / nothing to lift — consume, no rope.
                            marqueeDownItemHit = null;
                        }
                    } else {
                        marqueeDragActive = true;
                        startMarqueeEdgeScroll();
                    }
                }
                if (marqueeItemDragActive && layerGestureController != null) {
                    layerGestureController.onRowBodyMove(x + scrollOffsetPx, y,
                            getM6RowsTopPx(), totalEffectiveMs, this::xToTime);
                } else if (marqueeDragActive) {
                    updateMarqueeTo(x, y);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                longPressHandler.removeCallbacks(marqueeBatchLongPressRunnable);
                boolean wasDrag = marqueeDragActive;
                boolean wasItemDrag = marqueeItemDragActive;
                boolean batchFired = marqueeBatchFired;
                marqueeDragActive = false;
                marqueeItemDragActive = false;
                marqueeTouchActive = false;
                marqueeBatchFired = false;
                marqueeDownItemHit = null;
                if (wasItemDrag && layerGestureController != null) {
                    layerGestureController.onRowBodyUp(
                            e.getActionMasked() == MotionEvent.ACTION_UP);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                        && !wasDrag && !batchFired) {
                    onMarqueeTap(x, y);
                }
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
            }
        }
        return true;
    }

    /** Abort any in-flight marquee touch (pinch started, mode flipped, …). */
    private void cancelMarqueeTouch() {
        longPressHandler.removeCallbacks(marqueeBatchLongPressRunnable);
        if (marqueeItemDragActive && layerGestureController != null) {
            layerGestureController.onRowBodyUp(false); // abort = revert, never commit
        }
        marqueeItemDragActive = false;
        marqueeDownItemHit = null;
        marqueeTouchActive = false;
        marqueeDragActive = false;
        marqueeBatchFired = false;
    }

    /** Recompute the marquee rect + LIVE selection from the current finger position. */
    private void updateMarqueeTo(float viewX, float viewY) {
        marqueeCurContentX = viewX + scrollOffsetPx;
        marqueeCurLocalY = viewY - getM6RowsTopPx() + layerRowRenderer.getScrollOffsetPx();
        marqueeContentRect.set(
                Math.min(marqueeAnchorContentX, marqueeCurContentX),
                Math.min(marqueeAnchorLocalY, marqueeCurLocalY),
                Math.max(marqueeAnchorContentX, marqueeCurContentX),
                Math.max(marqueeAnchorLocalY, marqueeCurLocalY));
        java.util.List<com.fadcam.ui.faditor.layers.LayerRowRenderer.ItemHit> hits =
                layerRowRenderer.collectItemsInRect(marqueeContentRect, totalEffectiveMs,
                        this::timeToX, marqueeMode == MarqueeMode.EXCLUSIVE);
        marqueeSelectedIds.clear();
        for (com.fadcam.ui.faditor.layers.LayerRowRenderer.ItemHit h : hits) {
            marqueeSelectedIds.add(h.item.getId());
        }
        notifyMarqueeSelectionChanged();
        invalidate();
    }

    /** Tap in select mode: toggle the item under the finger; empty space clears all. */
    private void onMarqueeTap(float viewX, float viewY) {
        com.fadcam.ui.faditor.layers.LayerRowRenderer.ItemHit hit =
                layerRowRenderer.hitTestItem(viewX + scrollOffsetPx, viewY, getM6RowsTopPx(),
                        totalEffectiveMs, this::timeToX, null);
        if (hit != null) {
            String id = hit.item.getId();
            if (!marqueeSelectedIds.remove(id)) marqueeSelectedIds.add(id);
        } else {
            marqueeSelectedIds.clear();
        }
        notifyMarqueeSelectionChanged();
    }

    /** Both-axis edge auto-scroll while roping a marquee (contract §5.5). The corners are
     *  content-anchored, so scrolling extends the box over content the viewport didn't show. */
    private final Runnable marqueeEdgeScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!marqueeDragActive) {
                marqueeEdgeScrollActive = false;
                return;
            }
            float edge = 40f * density;
            float step = 12f * density;
            boolean scrolled = false;
            float w = getWidth();
            if (marqueeLastViewX < edge) {
                updatePlayheadFromX(w / 2f + scrollOffsetPx - step);
                scrolled = true;
            } else if (marqueeLastViewX > w - edge) {
                updatePlayheadFromX(w / 2f + scrollOffsetPx + step);
                scrolled = true;
            }
            float bandTop = getM6RowsTopPx();
            float bandBot = bandTop + layerRowRenderer.getViewportHeightPx();
            if (marqueeLastViewY < bandTop + 20f * density) {
                scrolled |= layerRowRenderer.scrollBy(-step * 0.6f);
            } else if (marqueeLastViewY > bandBot - 20f * density) {
                scrolled |= layerRowRenderer.scrollBy(step * 0.6f);
            }
            if (scrolled) {
                updateMarqueeTo(marqueeLastViewX, marqueeLastViewY);
            }
            postOnAnimation(this);
        }
    };

    private void startMarqueeEdgeScroll() {
        if (marqueeEdgeScrollActive) return;
        marqueeEdgeScrollActive = true;
        postOnAnimation(marqueeEdgeScrollRunnable);
    }

    /** Draw the active marquee box. Called INSIDE the scroll-translated canvas block
     *  (content-x space), after the rows have laid out. */
    private void drawMarqueeBox(@NonNull android.graphics.Canvas canvas) {
        if (!marqueeDragActive) return;
        if (marqueeFillPaint == null) {
            marqueeFillPaint = new Paint();
            marqueeFillPaint.setStyle(Paint.Style.FILL);
            marqueeFillPaint.setColor(0x268C3DFA);
            marqueeStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            marqueeStrokePaint.setStyle(Paint.Style.STROKE);
            marqueeStrokePaint.setStrokeWidth(1.5f * density);
            marqueeStrokePaint.setColor(Studio.ROOM_AVATAR_DEEP);
            marqueeStrokePaint.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{6f * density, 4f * density}, 0f));
        }
        float topPx = getM6RowsTopPx();
        float bandScroll = layerRowRenderer.getScrollOffsetPx();
        float l = marqueeContentRect.left;
        float r = marqueeContentRect.right;
        float t = topPx + marqueeContentRect.top - bandScroll;
        float b = topPx + marqueeContentRect.bottom - bandScroll;
        canvas.save();
        canvas.clipRect(scrollOffsetPx, topPx, scrollOffsetPx + getWidth(),
                topPx + layerRowRenderer.getViewportHeightPx());
        canvas.drawRect(l, t, r, b, marqueeFillPaint);
        canvas.drawRect(l, t, r, b, marqueeStrokePaint);
        canvas.restore();
    }

    /**
     * M6/M7 touch hook (extract-on-touch: header hit-testing lives in
     * {@link com.fadcam.ui.faditor.layers.LayerRowRenderer}; row-BODY item gestures
     * — move/trim/delete — live in {@link com.fadcam.ui.faditor.layers.LayerGestureController}
     * (PLAN Part 7 row M7)). Returns true if the touch was consumed by the multi-row
     * Track UI.
     */
    private boolean handleM6RowTouch(float scrolledX, float y) {
        float topPx = getM6RowsTopPx();
        if (!layerRowRenderer.isWithinRowRegion(y, topPx)) return false;
        com.fadcam.ui.faditor.layers.LayerRowRenderer.HeaderHit hit =
                layerRowRenderer.hitTestHeader(scrolledX, y, topPx);
        if (hit != null) {
            // PHASE-P P1: don't fire the icon action on DOWN anymore — defer to UP so a
            // 450ms hold can open the track-management menu instead (tap semantics are
            // unchanged for a quick press: DOWN→UP within slop fires the same action).
            cancelPendingHeaderTouch();
            pendingHeaderHit = hit;
            headerDownRawX = scrolledX - scrollOffsetPx;
            headerDownRawY = y;
            longPressHandler.postDelayed(headerLongPressRunnable, ITEM_PICKUP_MS);
            getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
            return true;
        }
        // Not a header hit — the tap landed in a row's body or empty row space.
        // M7 (redesigned, PLAN TARGET CONTRACT): hand it to LayerGestureController, which
        // returns WHAT to do with the follow-ups:
        //  • ARMED_TRIM — edge handle of the selected item: arm the item gesture NOW
        //    (drag = trim), same as before.
        //  • PENDING — a body hit: the item is SELECTED, but DO NOT arm a move. Enter the
        //    pending-body state and start the pick-up timer. A horizontal swipe from here
        //    scrubs (item not moved); a long-press picks it up for a move; a vertical drag
        //    row-scrolls; a quick lift is a tap. This is the #1 fix for "purple feels
        //    stuck" (a swipe used to nudge the item a few ms instead of scrubbing).
        //  • MISS — empty/locked/hidden/collapsed row: fall through to the pending-axis
        //    scrub-vs-row-scroll decision below (unchanged).
        com.fadcam.ui.faditor.layers.LayerGestureController.DownResult down =
                layerGestureController.onRowBodyDown(scrolledX, y, topPx, totalEffectiveMs, this::timeToX);
        if (down == com.fadcam.ui.faditor.layers.LayerGestureController.DownResult.ARMED_TRIM) {
            m7ItemGestureActive = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
            return true;
        }
        if (down == com.fadcam.ui.faditor.layers.LayerGestureController.DownResult.ARMED_XFADE) {
            m7ItemGestureActive = true;
            // Pill selected — notify host for peek inspector
            if (listener != null) {
                com.fadcam.ui.faditor.model.AudioCrossfade sel = layerGestureController.getActiveCrossfade();
                listener.onCrossfadeSelected(sel);
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
            return true;
        }
        if (down == com.fadcam.ui.faditor.layers.LayerGestureController.DownResult.PENDING) {
            m7ItemPendingDown = true;
            m7PendingDownX = scrolledX;
            m7PendingDownY = y;
            longPressHandler.removeCallbacks(itemPickupRunnable);
            longPressHandler.postDelayed(itemPickupRunnable, ITEM_PICKUP_MS);
            getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
            return true;
        }
        // Deselect crossfade pill if tapping empty space (MISS)
        if (layerRowRenderer.getSelectedCrossfadeId() != null) {
            layerRowRenderer.setSelectedCrossfadeId(null);
            if (listener != null) listener.onCrossfadeSelected(null);
            invalidate();
        }
        // Surface-overlap fix: a miss inside the row band is NOT immediately claimed as
        // an M6 vertical-scroll drag anymore (that used to swallow every horizontal drag
        // over a row too, blocking timeline scrub entirely over Layer 1 / the extracted
        // -audio row and any locked row). Axis is undecided until onMove sees enough
        // movement to tell a vertical row-scroll from a horizontal scrub; still consume
        // the DOWN itself (matches every other surface's contract — a DOWN with no armed
        // gesture yet always returns true).
        // AV3 discovery: if this MISS landed on a COLLAPSED (thin) AUDIO row, remember it —
        // a pure tap (slop never exceeded) expands it into the tall quad-band tape below.
        // A drag still scrubs/row-scrolls (this is only consumed in the tap-resolution branch).
        {
            com.fadcam.ui.faditor.layers.Track rt = layerRowRenderer.rowTrackAt(y, topPx);
            pendingCollapsedAudioTrack = (rt != null && rt.isCollapsed() && !rt.isLocked()
                    && rt.getKind() == com.fadcam.ui.faditor.layers.TrackKind.AUDIO) ? rt : null;
        }
        m6RowPendingAxisDecision = true;
        m6RowPendingDownX = scrolledX;
        m6RowPendingLastX = scrolledX;
        m6RowPendingDownY = y;
        m6RowLastY = y;
        // Claim the gesture from the parent scroll container NOW, before the axis is
        // decided: every other armed-DOWN branch in onDown() ends with this call, but a
        // pending-axis DOWN returns true straight out of handleM6RowTouch and skips
        // onDown()'s trailing requestDisallowInterceptTouchEvent — so without this the
        // parent could intercept the follow-up MOVEs and the axis decision (scrub vs
        // row-scroll) in onMove would never run. Both axes re-assert it on resolution.
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    /**
     * Tear down ALL M6/M7 row-gesture state in one place. Used when a competing gesture
     * (a pinch — see {@code onScaleBegin}) hijacks the stream so the normal
     * {@code onUp()} reset path is skipped, which would otherwise leak a flag until the
     * next gesture and cause the "row scrub sticks sometimes" symptom. Idempotent.
     */
    private void resetRowGestureFlags(String cause) {
        cancelPendingHeaderTouch();
        longPressHandler.removeCallbacks(itemPickupRunnable);
        if (itemDragMinimapNav) {
            itemDragMinimapNav = false;
            if (layerGestureController != null) layerGestureController.setSuppressMoveMapping(false);
        }
        if (m7ItemGestureActive || m7ItemPendingDown) {
            m7ItemGestureActive = false;
            m7ItemPendingDown = false;
            // Flag-reset paths (pinch begin, fresh DOWN with a leaked flag) are always
            // interruptions, never deliberate drops — abort, don't commit (review fix).
            if (layerGestureController != null) layerGestureController.onRowBodyUp(false);
        }
        cancelPendingExcursionEnter();
        if (excursionActive) endExcursion("reset:" + cause);
        m6RowDragActive = false;
        m6RowScrubPassthroughActive = false;
        m6RowPendingAxisDecision = false;
    }

    private boolean onDown(float x, float y) {
        if (VLOG) FLog.d(TAG, "onDown: x=" + x + " y=" + y);
        // Bug A safety net: a fresh DOWN starts a new gesture stream — no row gesture from
        // a PRIOR stream can still be legitimately active. If any M6/M7 flag survived
        // (e.g. an UP/CANCEL that was swallowed by a competing handler), clear it now so
        // it can't misroute this gesture's MOVEs (the "sticks sometimes" symptom). The
        // reset logs its cause, so if this ever fires in the pull it names the leak path.
        resetRowGestureFlags("fresh-DOWN");
        downX = x;
        downY = y;
        downTime = System.currentTimeMillis();
        longPressTriggered = false;
        dislodgeArmed = false;
        dislodgeArmedSegIndex = -1;
        if (dislodgeThresholdPx <= 0f) {
            dislodgeThresholdPx = 24f * getResources().getDisplayMetrics().density;
        }

        // SPEC_N §3: the spine's collapse caret is SCREEN space (pinned gutter), so it is
        // tested on the raw x/y and before anything that consumes a master-band touch.
        // The DRAWN glyph is 12dp; the TOUCH box is padded to a real finger target, exactly
        // as LayerRowRenderer does for the row carets.
        if (!spineCaretRect.isEmpty()
                && x >= spineCaretRect.left - 8f * density && x <= spineCaretRect.right + 8f * density
                && y >= spineCaretRect.top - 8f * density && y <= spineCaretRect.bottom + 8f * density) {
            toggleSpineCollapsed();
            return true;
        }

        // SPEC_N §5: "the user taps away". Any DOWN that is not on the master band disarms the
        // fade knobs — a lane, the audio band, the ruler, the minimap, a scrub. A DOWN INSIDE
        // the band deliberately does not, otherwise grabbing a knob would make it vanish under
        // the finger. The band is extended upward by the knob's reach so the knobs themselves
        // count as "on the spine".
        {
            float knobReach = (SPINE_KNOB_TOP_OFFSET_DP + SPINE_KNOB_R_DP) * density;
            if (y < masterTopPx() - knobReach || y > masterBotPx()) {
                spineTapArmedClipId = null;
            }
        }

        // Adjust x for scroll offset
        float scrolledX = x + scrollOffsetPx;
        if (VLOG) FLog.d(TAG, "onDown: scrolledX=" + scrolledX + " scrollOffset=" + scrollOffsetPx);

        // B7: long-press the tape inside the clip-audio shelf → same audio drawer (clip's own audio)
        {
            int shelfSeg = hitClipAudioShelf(scrolledX, y);
            if (shelfSeg >= 0) {
                clipAudioShelfLongPressSegIndex = shelfSeg;
                longPressHandler.removeCallbacks(clipAudioShelfLongPressRunnable);
                longPressHandler.postDelayed(clipAudioShelfLongPressRunnable, android.view.ViewConfiguration.getLongPressTimeout());
            } else {
                longPressHandler.removeCallbacks(clipAudioShelfLongPressRunnable);
                clipAudioShelfLongPressSegIndex = -1;
            }
        }

        // Text-box timing carets. These MUST be tested before handleM6RowTouch, because that is
        // where the item's own trim handles are claimed and the carets live ~a finger's width
        // inboard of them — after it, a caret grab would always be swallowed by the row. Same rule
        // as the freeze carets against the master trim bar: the caret's zone is the TIGHTER of the
        // two (0.9 of the inset against the trim's full inset), so a true edge grab still lands on
        // the trim cap while a caret resting at zone 0 is grabbable at all.
        {
            Drag ch = hitTestTextAnimHandle(scrolledX, y);
            if (ch != Drag.NONE) {
                FLog.d(TAG, "onDown: hit text anim caret " + ch);
                activeDrag = ch;
                textAnimDragX = scrolledX;
                // Read BEFORE the first preview mutates them — this is the undo target.
                com.fadcam.ui.faditor.model.TextOverlayItem grabbed = selectedTextAnimItem();
                textAnimBeforeIn = grabbed != null ? grabbed.getTextAnimInPct() : 0f;
                textAnimBeforeOut = grabbed != null ? grabbed.getTextAnimOutPct() : 0f;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }

        // Motion-range window carrots — same tight-then-trim rule as the amber carets above.
        {
            Drag mr = hitTestMotionRangeHandle(scrolledX, y);
            if (mr != Drag.NONE) {
                FLog.d(TAG, "onDown: hit motion-range carrot " + mr);
                activeDrag = mr;
                com.fadcam.ui.faditor.model.TextOverlayItem grabbed = selectedMotionRangeItem();
                motionRangeBeforeStartMs = grabbed != null ? grabbed.getMotionStartMs() : 0L;
                motionRangeBeforeEndMs = grabbed != null ? grabbed.getMotionEndMs() : 0L;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }

        // M6 hook: touch dispatch into the multi-row Track UI. Header icon taps
        // (caret/hide/lock/mute) are handled entirely here; a tap elsewhere in a
        // LOCKED track's row is swallowed (locked = taps/gestures ignored at the
        // timeline level per PLAN Part 7 M6 scope item 4); a tap in an unlocked
        // row's body falls through (item editing is M7 — out of scope here).
        if (handleM6RowTouch(scrolledX, y)) {
            return true;
        }

        // FADE_KNOBS §2.5: spine fade knobs arm BEFORE the transition seam test — at fade=0
        // the out-knob sits exactly ON the seam and must not lose to it.
        if (selectedIndex >= 0 && selectedIndex < segRects.size()
                && !(listener != null && listener.isTimelineEditLockedOut())) {
            Drag fk = hitTestFadeKnobOnly(scrolledX, y);
            if (fk != Drag.NONE) {
                FLog.d(TAG, "onDown: hit spine fade knob " + fk);
                activeDrag = fk;
                Clip fc = segments.get(selectedIndex).clip;
                if (fc != null) {
                    fadeStartInMs = fc.getMasterFadeInMs();
                    fadeStartOutMs = fc.getMasterFadeOutMs();
                    fadeLastInMs = fadeStartInMs;
                    fadeLastOutMs = fadeStartOutMs;
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }

        int transitionHit = hitTestTransition(scrolledX, y);
        if (transitionHit >= 0) {
            selectedTransitionIndex = transitionHit;
            Drag transitionHandle = hitTestTransitionHandle(transitionHit, scrolledX, y);
            if (transitionHandle != Drag.NONE) {
                activeDrag = transitionHandle;
                transitionDragIndex = transitionHit;
                transitionDragStartX = scrolledX;
                transitionDragX = scrolledX;
                Transition t = transitions.get(transitionHit);
                transitionDragStartDurationMs = t.durationMs;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            invalidate();
            if (listener != null) listener.onTransitionSelected(transitionHit);
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }

        // Slide freeze-zone markers: checked BEFORE the outer trim handles with
        // a deliberately tight zone — at freeze 0 the marker sits just inside
        // the green bar, and the tight zone lets it be grabbed at all while the
        // bar's generous slop still owns true edge grabs.
        if (selectedIndex >= 0 && selectedIndex < segRects.size()) {
            Drag fh = hitTestFreezeHandle(scrolledX, y);
            if (fh != Drag.NONE) {
                FLog.d(TAG, "onDown: hit freeze handle " + fh);
                activeDrag = fh;
                freezeDragX = scrolledX;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }


        // Check trim handles first
        if (selectedIndex >= 0 && selectedIndex < segRects.size()) {
            Drag h = hitTestHandle(scrolledX, y);
            if (h != Drag.NONE) {
                FLog.d(TAG, "onDown: hit handle " + h);
                activeDrag = h;
                SegmentData sd = segments.get(selectedIndex);
                dragStartInMs = sd.inPointMs;
                dragStartOutMs = sd.outPointMs;
                RectF seg = segRects.get(selectedIndex);
                dragStartSegLeft = seg.left;
                dragStartSegRight = seg.right;
                // Init trim drag visual state
                trimDragStartFrac = sd.sourceDurationMs > 0 ? (float) sd.inPointMs / sd.sourceDurationMs : 0f;
                trimDragEndFrac = sd.sourceDurationMs > 0 ? (float) sd.outPointMs / sd.sourceDurationMs : 1f;
                trimDragX = scrolledX;
                // Init loop extension state
                trimDragStartLoopBefore = sd.clip.getLoopBeforeMs();
                trimDragStartLoopAfter = sd.clip.getLoopAfterMs();
                trimDragLoopBefore = trimDragStartLoopBefore;
                trimDragLoopAfter = trimDragStartLoopAfter;
                loopChangedDuringDrag = false;
                loopReadoutActive = false; // L3: fresh drag starts with the readout hidden
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }

        downSegIndex = hitTestSegment(scrolledX, y);
        if (VLOG) FLog.d(TAG, "onDown: hit segment " + downSegIndex);



        // (The legacy in-strip audio select/drag arm block was deleted 2026-08-22 —
        // SPEC_AUDIO_UX_V1 row A1: unreachable since audio consolidation.)

        if (downSegIndex >= 0) {
            // Schedule long press detection via Handler
            longPressHandler.removeCallbacks(longPressRunnable);
            if (segments.size() > 1) {
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_MS);
            }
            // A clip owns the master-band hold; the G22 menu must not fight its pickup.
            cancelMasterHeaderLongPress();
        } else if (liveTimeline != null && y >= masterTopPx() && y <= masterBotPx()) {
            // G22: stationary hold on the master band's own tape (empty spine, rails) —
            // same timeout and haptic contract as the B7 clip-audio shelf long-press.
            cancelMasterHeaderLongPress();
            masterHeaderLongPressArmed = true;
            longPressHandler.postDelayed(masterHeaderLongPressRunnable,
                    android.view.ViewConfiguration.getLongPressTimeout());
        }
        // Always accept touch for scrolling (anywhere on timeline, not just on segments)
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    /**
     * Resolve the just-demoted clip to its layer row and hand the in-flight touch to
     * {@link com.fadcam.ui.faditor.layers.LayerGestureController#adoptDrag}.
     *
     * <p>Safe to call before the next layout: adoption reads the item's MODEL time only, so it
     * does not depend on row rectangles that {@code layout()} has not computed yet. The activity's
     * {@code afterSpineLayerMove} has already rebuilt {@link #layerTracks} synchronously by the
     * time the listener returns, so the new item is present here.</p>
     */
    /**
     * §3A.4 — while a layer item is held over the MASTER track, work out which seam it would
     * insert at and remember it for {@link #drawSpineDropIndicator}. Returns true if the spine is
     * currently a legal target.
     *
     * <p><b>Seam-only by design.</b> The spec also describes a red + scissors state for dropping
     * mid-clip, which CUTS the clip underneath. That is deliberately not built yet: the model has
     * no split-and-insert-in-one-gesture op, so the honest choice was to snap every hover to a
     * seam and show exactly that, rather than draw a scissors the drop would not honour. An
     * indicator that promises something the release does not do is worse than no indicator — it
     * teaches the user to distrust all of them.</p>
     */
    private boolean updateSpineDropTarget(float screenX, float screenY) {
        int prev = spineDropIndex;
        spineDropIndex = -1;
        if (layerGestureController == null || segRects.isEmpty()) {
            if (prev != -1) invalidate();
            return false;
        }
        // §3A.5 PAYLOAD LEGALITY: only video/image objects may live on the spine. Text, stickers
        // and sprites are refused, so they must never be OFFERED a landing spot either.
        com.fadcam.ui.faditor.layers.TimedItem it = layerGestureController.getActiveItem();
        boolean legal = it != null && it.getClip() != null && it.getClip().isOverlayClip();
        if (!legal) {
            if (prev != -1) invalidate();
            return false;
        }
        RectF band = segRects.get(0);
        // Vertical hit band, generous by a touch slop on each side: the finger is dragging a whole
        // clip body, so requiring the fingertip itself to be inside the strip reads as finicky.
        if (screenY < band.top - touchSlopPx || screenY > band.bottom + touchSlopPx) {
            if (prev != -1) invalidate();
            return false;
        }
        spineDropIndex = getInsertIndexAtX(screenX);
        if (spineDropIndex != prev) {
            // SPINEDROP probe: the hand test cannot screenshot a moving finger, so leave a trace
            // that says what the indicator CLAIMED. Pair it with the DROP line below and a
            // mismatch between promised and actual seam is visible in logcat instead of being an
            // argument about what someone thought they saw.
            android.util.Log.d("SPINEDROP", "hover seam=" + spineDropIndex + " of " + segRects.size());
            invalidate();
        }
        return true;
    }

    /** §3A.4 — solid purple line at the seam the held item will insert at. */
    private void drawSpineDropIndicator(@NonNull Canvas canvas) {
        if (spineDropIndex < 0 || segRects.isEmpty()) return;
        // Belt and braces: the indicator is meaningless without a live drag, and a single
        // drag-end path that forgot to clear the index would otherwise paint this line onto the
        // timeline permanently. Ask the authority (is an item actually being moved?) rather than
        // trusting every exit to have tidied up.
        if (layerGestureController == null || !layerGestureController.isMoveDragActive()) return;
        RectF band = segRects.get(0);
        // The seam's x: the LEFT edge of the clip we would insert before, or the right edge of the
        // last clip when appending past the end.
        float contentX = spineDropIndex < segRects.size()
                ? segRects.get(spineDropIndex).left
                : segRects.get(segRects.size() - 1).right;
        float x = contentX - scrollOffsetPx;
        float top = band.top;
        float bot = band.bottom;
        // A soft halo behind the line so it reads against both light thumbnails and dark ones —
        // a 3.5dp line alone disappears on a busy frame, which is exactly when it matters.
        canvas.drawRect(x - 5f * density, top, x + 5f * density, bot, spineDropGlowPaint);
        canvas.drawRect(x - 1.75f * density, top, x + 1.75f * density, bot, spineDropPaint);
        // Caps top and bottom: they turn a line that could be mistaken for a clip border into a
        // deliberate marker.
        float capR = 4f * density;
        canvas.drawCircle(x, top, capR, spineDropPaint);
        canvas.drawCircle(x, bot, capR, spineDropPaint);
    }

    /**
     * Pick a spine clip UP without changing the model (PLAN_CARRY_V1). The grab offset is taken
     * from the ORIGINAL touch-down point, not from the current finger position: by the time the
     * vertical threshold is crossed the finger has already travelled, and using the current point
     * would snap the card so its grab point jumped to wherever the finger got to.
     */
    private void beginCarry(int segIndex, float x, float y) {
        if (segIndex < 0 || segIndex >= segRects.size()) return;
        RectF r = segRects.get(segIndex);
        carryActive = true;
        carrySegIndex = segIndex;
        carryFingerX = x;
        carryFingerY = y;
        carryGrabDx = Math.max(0f, Math.min(r.width(), (downX + scrollOffsetPx) - r.left));
        carryGrabDy = Math.max(0f, Math.min(r.height(), downY - r.top));
        // A very long clip would produce a card wider than the screen, which reads as a smear
        // rather than an object. Cap it and keep the grab point inside the capped width.
        carryCardW = Math.min(r.width(), getWidth() * 0.55f);
        carryCardH = r.height();
        if (carryGrabDx > carryCardW) carryGrabDx = carryCardW * 0.5f;
        carryTargetH = carryCardH;
        carryFromLayer = false;
        carryLayerItemId = null;
        carryThumbKey = segIndex < segments.size() ? segments.get(segIndex).thumbKey : null;
        carryDropSeam = -1;
        carryOverLayerBand = false;
        getParent().requestDisallowInterceptTouchEvent(true);
        android.util.Log.d("CARRY", "begin seg=" + segIndex + " grab=" + carryGrabDx + "," + carryGrabDy
                + " bandTop=" + masterContentTopPx() + " spineH=" + trackHeightPx
                + " laneH=" + (layerRowRenderer != null ? layerRowRenderer.expandedRowHeightPx() : -1f));
    }

    /** Track the finger and re-resolve where a release would put the clip. */
    private void updateCarry(float x, float y) {
        carryFingerX = x;
        carryFingerY = y;
        float bandTop = masterContentTopPx();
        float bandBot = bandTop + trackHeightPx;
        if (y >= bandTop - touchSlopPx && y <= bandBot + touchSlopPx) {
            carryOverLayerBand = false;
            carryDropSeam = getInsertIndexAtX(x);
            carryTargetH = trackHeightPx;            // becoming a master clip: full height
        } else if (y < bandTop - touchSlopPx && y > rulerHeightPx) {
            resolveLaneDrop(x, y);
            float want = layerRowRenderer != null
                    ? layerRowRenderer.expandedRowHeightPx() : trackHeightPx;
            if (want != carryTargetH) {
                android.util.Log.d("CARRY", "targetH " + carryTargetH + " -> " + want
                        + " (cardH now " + carryCardH + ")");
            }
            carryTargetH = want;
            // Above the spine and below the ruler = the layer band: this becomes a PiP.
            carryOverLayerBand = true;
            carryDropSeam = -1;
        } else {
            carryOverLayerBand = false;
            carryDropSeam = -1;
        }
        invalidate();
    }

    /**
     * Release. Exactly ONE model mutation, so exactly one undo press — which is what makes the
     * whole gesture read as a single action rather than a sequence of things that happened to you.
     */
    private void endCarry(boolean commit) {
        int seg = carrySegIndex;
        int seam = carryDropSeam;
        boolean toLayer = carryOverLayerBand;
        float cardLeft = carryFingerX - carryGrabDx;
        carryActive = false;
        carrySegIndex = -1;
        carryDropSeam = -1;
        carryOverLayerBand = false;
        getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();
        android.util.Log.d("CARRY", "end commit=" + commit + " seg=" + seg + " seam=" + seam
                + " toLayer=" + toLayer);
        int state = carryDropState;
        long hole = carryHoleMs;
        String laneId = carryTargetLaneId;
        carryDropState = CARRY_FIT;
        carryHoleMs = -1;
        carryTargetLaneId = null;
        if (!commit || seg < 0 || listener == null) return;
        if (toLayer) {
            // Land it at the time the CARD's left edge is over, not the finger's — the card is
            // what the user was aiming, and its left edge is the clip's start.
            // ⚠ xToTime compares against segRects, which are CONTENT coordinates — so it takes a
            // CONTENT x, not a screen one. getInsertIndexAtX (used just below) does its own
            // conversion and takes a SCREEN x. Two neighbouring helpers, opposite conventions.
            // Passing the screen x here is correct only at scroll 0, i.e. correct in exactly the
            // state a fresh test starts in and wrong the moment the user scrolls.
            //
            // Honour EXACTLY what the card showed. An outcome here that differs from the one
            // drawn is the worst failure an indicator can have — worse than no indicator, because
            // by then the user has been taught to trust it.
            long trimTo = state == CARRY_TRIM ? Math.max(CARRY_MIN_HOLE_MS, hole) : -1L;
            android.util.Log.d("CARRY", "drop lane state=" + state + " hole=" + hole
                    + " lane=" + laneId + " trimTo=" + trimTo);
            listener.onClipCarriedToLayer(seg, Math.max(0L, xToTime(cardLeft + scrollOffsetPx)),
                    laneId, trimTo, state == CARRY_NEWLANE);
        } else if (seam >= 0) {
            // Dropping a clip back where it already is is a no-op, not a reorder. moveClip's
            // target index is measured in the list WITHOUT the clip, so a seam to the right of the
            // origin shifts down by one; seam == seg and seam == seg + 1 are both "same place".
            if (seam != seg && seam != seg + 1) {
                listener.onClipCarriedToSeam(seg, seam > seg ? seam - 1 : seam);
            }
        }
    }

    /** The carried clip: a dimmed ghost where it came from, and a card under the finger. */
    private void drawCarry(@NonNull Canvas canvas) {
        if (!carryActive) return;
        if (carrySegIndex >= 0 && carrySegIndex < segRects.size()) {
            RectF g = segRects.get(carrySegIndex);
            float l = g.left - scrollOffsetPx, r = g.right - scrollOffsetPx;
            canvas.drawRect(l, g.top, r, g.bottom, carryGhostPaint);
        }
        // Destination projection.
        if (carryOverLayerBand) {
            canvas.drawRect(0, rulerHeightPx, getWidth(), masterContentTopPx() - touchSlopPx,
                    carryLayerBandPaint);
        } else if (carryDropSeam >= 0) {
            drawCarrySeamLine(canvas, carryDropSeam);
        }
        // Ease toward the destination's height. The grab offset is scaled with it so the finger
        // keeps holding the SAME POINT of the clip as it shrinks — without this the card slides
        // out from under the thumb while it resizes, which reads as the app losing your grip.
        if (carryTargetH > 0 && Math.abs(carryCardH - carryTargetH) > 0.5f) {
            float prevH = carryCardH;
            carryCardH += (carryTargetH - carryCardH) * 0.25f;
            if (prevH > 0) carryGrabDy *= carryCardH / prevH;
            postInvalidateOnAnimation();
        }
        float left = carryFingerX - carryGrabDx;
        float top = carryFingerY - carryGrabDy;
        // TRIM: the card narrows to the hole it will actually occupy, so the shape under the
        // finger IS the result. Telling the user "this will be trimmed" in words, or in colour
        // alone, still leaves them guessing HOW MUCH.
        float drawW = carryCardW;
        if (carryOverLayerBand && carryDropState == CARRY_TRIM && carryHoleMs > 0) {
            float holeW = (carryHoleMs / 1000f) * dpPerSecondPx;
            drawW = Math.max(6f * density, Math.min(carryCardW, holeW));
        }
        RectF card = new RectF(left, top, left + drawW, top + carryCardH);
        canvas.drawRoundRect(card, reorderBlockCornerPx, reorderBlockCornerPx, carryCardPaint);
        drawCarryThumb(canvas, card);
        Paint outline;
        if (!carryOverLayerBand) outline = spineDropPaint2();
        else if (carryDropState == CARRY_TRIM) outline = carryWarnPaint;
        else if (carryDropState == CARRY_NEWLANE) outline = carryNewLanePaint;
        else outline = spineDropPaint2();
        // Same hardware-acceleration caveat as the lane slot: the NEW-LANE outline is dashed, and
        // a dashed rect primitive does not dash on an HW canvas. Route every outline through a
        // path so the three states are actually distinguishable.
        clipPath.reset();
        clipPath.addRoundRect(card, reorderBlockCornerPx, reorderBlockCornerPx, Path.Direction.CW);
        canvas.drawPath(clipPath, outline);
        if (carryOverLayerBand && carryDropState == CARRY_TRIM) {
            drawScissorsGlyph(canvas, card.right, card.centerY());
        }
    }

    /**
     * The carried clip's picture. Keyed by thumbKey rather than by spine index, because the same
     * cache serves lane items: thumbKey is {@code sourceUri.hashCode() + "_src"}, so a PiP of a
     * clip that is (or was) on the spine already has its filmstrip decoded. Draws nothing when the
     * source was never on screen — the card still reads as a card, just an empty one.
     */
    private void drawCarryThumb(@NonNull Canvas canvas, @NonNull RectF rect) {
        if (carryThumbKey == null) return;
        List<Bitmap> thumbs = thumbnailsCache.get(carryThumbKey);
        if (thumbs == null || thumbs.isEmpty()) return;
        Bitmap thumb = thumbs.get(0);
        if (thumb == null || thumb.isRecycled()) return;
        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(rect, reorderBlockCornerPx, reorderBlockCornerPx, Path.Direction.CW);
        canvas.clipPath(clipPath);
        float scale = Math.max(rect.width() / thumb.getWidth(), rect.height() / thumb.getHeight());
        float dw = thumb.getWidth() * scale, dh = thumb.getHeight() * scale;
        canvas.drawBitmap(thumb, null,
                new RectF(rect.centerX() - dw / 2f, rect.centerY() - dh / 2f,
                        rect.centerX() + dw / 2f, rect.centerY() + dh / 2f), null);
        canvas.restore();
    }

    /**
     * THE REVERSE DIRECTION. A lane item being dragged used to stay drawn in its own row, sliding
     * side to side — JoyRaptor: "the user might think that if he leaves his finger up that it's going
     * to just stay in its original lane." So it now lifts out of the lane onto the same floating
     * card the spine uses, leaving the home ghost behind, and grows to master height as it nears
     * the spine.
     */
    private void beginLayerCarry(@NonNull com.fadcam.ui.faditor.layers.TimedItem item, float x, float y) {
        Clip c = item.getClip();
        if (c == null) return;                    // pictures only; text/stickers keep their row
        carryActive = true;
        carryFromLayer = true;
        carrySegIndex = -1;
        carryLayerItemId = item.getId();
        carryThumbKey = c.getSourceUri() != null ? (c.getSourceUri().hashCode() + "_src") : null;
        float durMs = Math.max(1f, item.getDisplayDurationMs(totalEffectiveMs));
        carryCardW = Math.min((durMs / 1000f) * dpPerSecondPx, getWidth() * 0.55f);
        carryCardH = layerRowRenderer != null
                ? layerRowRenderer.expandedRowHeightPx() : trackHeightPx;
        carryTargetH = carryCardH;
        // Held at the point the finger is already on horizontally, centred vertically: the row is
        // short enough that any other vertical anchor looks arbitrary.
        float itemLeft = timeToX(item.getTimelineStartMs()) - scrollOffsetPx;
        carryGrabDx = Math.max(0f, Math.min(carryCardW, x - itemLeft));
        carryGrabDy = carryCardH / 2f;
        carryFingerX = x;
        carryFingerY = y;
        // One representation only. The renderer keeps drawing the HOME GHOST (set on pickup), so
        // the origin lane still shows where it came from — but the body itself is now the card.
        if (layerRowRenderer != null) layerRowRenderer.setCarriedItemId(carryLayerItemId);
        android.util.Log.d("CARRY", "beginLayer item=" + carryLayerItemId + " w=" + carryCardW);
    }

    /**
     * Free room on {@code lane} starting at {@code startMs}: the distance from startMs to the next
     * item's start, or {@link Long#MAX_VALUE} when nothing follows. Returns 0 when startMs is
     * INSIDE an existing item — there is no hole there at all, which is what selects the new-lane
     * outcome.
     *
     * <p>Excludes the carried item itself. Without that, dragging a clip a few hundred ms along
     * its OWN lane measures the hole against the item being moved and reports zero room, so
     * nudging a clip sideways would spawn a lane every time.</p>
     */
    private long freeHoleMs(@Nullable com.fadcam.ui.faditor.layers.Track lane, long startMs,
                            @Nullable String ignoreItemId) {
        if (lane == null) return Long.MAX_VALUE;
        long next = Long.MAX_VALUE;
        for (com.fadcam.ui.faditor.layers.TimedItem it : lane.getItems()) {
            if (ignoreItemId != null && ignoreItemId.equals(it.getId())) continue;
            long s = it.getTimelineStartMs();
            long e = s + Math.max(0L, it.getDisplayDurationMs(totalEffectiveMs));
            if (startMs >= s && startMs < e) return 0L;      // pointing inside an item
            if (s > startMs && s < next) next = s;
        }
        return next == Long.MAX_VALUE ? Long.MAX_VALUE : next - startMs;
    }

    /**
     * SPEC W §3 — true when {@code [startMs, startMs + durMs)} intersects a lane
     * occupant that spans (effectively) the whole timeline. The shared definition
     * ({@link Timeline#isFullSpanRange}); the carried item itself is excluded the
     * same way {@link #freeHoleMs} excludes it.
     */
    private boolean laneHasFullSpanOverlap(
            @NonNull com.fadcam.ui.faditor.layers.Track lane, long startMs, long durMs,
            @Nullable String ignoreItemId) {
        if (totalEffectiveMs <= 0 || durMs <= 0) return false;
        long endMs = startMs + durMs;
        for (com.fadcam.ui.faditor.layers.TimedItem it : lane.getItems()) {
            if (ignoreItemId != null && ignoreItemId.equals(it.getId())) continue;
            long s = it.getTimelineStartMs();
            long e = s + Math.max(0L, it.getDisplayDurationMs(totalEffectiveMs));
            if (e <= s) continue;
            if (Timeline.rangesOverlap(startMs, endMs, s, e)
                    && Timeline.isFullSpanRange(s, e - s, totalEffectiveMs)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decide what a release onto the lane band would do, and shape the card to match. Called every
     * move while the card is over the lanes.
     */
    private void resolveLaneDrop(float x, float y) {
        long durMs = carriedDurationMs();
        long startMs = Math.max(0L, xToTime(carryFingerX - carryGrabDx + scrollOffsetPx));
        com.fadcam.ui.faditor.layers.Track lane =
                layerRowRenderer != null ? layerRowRenderer.rowTrackAt(y, getM6RowsTopPx()) : null;
        if (lane == null) {
            // The finger is between rows, or the layout shifted under it mid-drag. Falling through
            // with a null lane made freeHoleMs answer Long.MAX_VALUE — "infinite room" — so an
            // UNKNOWN target was treated as an EMPTY one and the preview promised a clean drop
            // onto an occupied lane. Resolve against the lane the drop will actually use instead.
            for (com.fadcam.ui.faditor.layers.Track t : layerTracks) {
                if (CARRY_FALLBACK_LANE_ID.equals(t.getId())) { lane = t; break; }
            }
        }
        carryTargetLaneId = lane != null ? lane.getId() : null;
        long hole = freeHoleMs(lane, startMs, carryFromLayer ? carryLayerItemId : null);
        carryHoleMs = hole;
        // SPEC W §3 — full-span exemption for the carry card, same geometry as the
        // move-drag path: the before/after shortcut (TRIM — "place this just before
        // the end") is meaningless when the carried object spans the whole timeline
        // or the occupant under the finger does. Offer an empty lane (FIT, only when
        // the lane is genuinely empty) or a NEW lane — never a trim, never overlap.
        // Uses the ONE overlap / full-span definitions (Timeline#rangesOverlap,
        // Timeline#isFullSpanRange) the add paths and move resolver share.
        boolean carriedFullSpan = Timeline.isFullSpanRange(startMs, durMs, totalEffectiveMs)
                || durMs >= (long) (totalEffectiveMs * Timeline.FULL_SPAN_FRACTION);
        boolean occupantFullSpan = lane != null
                && laneHasFullSpanOverlap(lane, startMs, durMs,
                        carryFromLayer ? carryLayerItemId : null);
        if (carriedFullSpan || occupantFullSpan) {
            carryDropState = (hole == Long.MAX_VALUE) ? CARRY_FIT : CARRY_NEWLANE;
        } else if (hole == Long.MAX_VALUE || hole >= durMs) {
            carryDropState = CARRY_FIT;
        } else if (hole >= CARRY_MIN_HOLE_MS) {
            carryDropState = CARRY_TRIM;
        } else {
            carryDropState = CARRY_NEWLANE;
        }
        // Which row the lane opens BEFORE. Opening it right at the lane the finger is over (rather
        // than appending at the bottom) is the whole point: the lane arrives where the user is
        // pointing, not somewhere they then have to go and find.
        laneOpenIndex = 0;
        if (lane != null) {
            for (int i = 0; i < layerTracks.size(); i++) {
                if (layerTracks.get(i).getId().equals(lane.getId())) { laneOpenIndex = i; break; }
            }
        } else {
            laneOpenIndex = layerTracks.size();
        }
    }

    /**
     * Ease the lane-opening preview toward open (a carried clip has nowhere to go) or shut.
     * Driven from the draw pass so it animates with the rows it displaces rather than on its own
     * timer, which would let the slot and the rows disagree mid-flight.
     */
    private void tickLaneOpen() {
        float target = (carryActive && carryOverLayerBand && carryDropState == CARRY_NEWLANE
                && layerRowRenderer != null) ? layerRowRenderer.expandedRowHeightPx() : 0f;
        if (Math.abs(laneOpenPx - target) > 0.5f) {
            laneOpenPx += (target - laneOpenPx) * 0.22f;
            postInvalidateOnAnimation();
        } else {
            laneOpenPx = target;
        }
        if (layerRowRenderer != null) {
            layerRowRenderer.setPendingLaneGap(laneOpenPx > 0.5f ? laneOpenIndex : -1, laneOpenPx);
        }
    }

    /** Duration of whatever is riding the card, in timeline ms. */
    private long carriedDurationMs() {
        if (carryFromLayer) {
            return Math.max(1L, (long) ((carryCardW / Math.max(1f, dpPerSecondPx)) * 1000f));
        }
        return carrySegIndex >= 0 && carrySegIndex < segments.size()
                ? Math.max(1L, segments.get(carrySegIndex).effectiveMs) : 1L;
    }

    /**
     * The scissors at the cut edge. REQUIRED by §3A.4 rather than decorative: red alone does not
     * survive colourblindness, and "this will be shortened" is exactly the message that must not
     * depend on hue. Drawn as two crossed blades and two rings — cheap, and unmistakable at a
     * glance even at 12dp.
     */
    private void drawScissorsGlyph(@NonNull Canvas canvas, float x, float cy) {
        float r = 3f * density, arm = 7f * density;
        carryWarnPaint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(x - arm, cy - arm, x + arm * 0.4f, cy + arm * 0.4f, carryWarnPaint);
        canvas.drawLine(x - arm, cy + arm, x + arm * 0.4f, cy - arm * 0.4f, carryWarnPaint);
        canvas.drawCircle(x + arm * 0.7f, cy - arm * 0.7f, r, carryWarnPaint);
        canvas.drawCircle(x + arm * 0.7f, cy + arm * 0.7f, r, carryWarnPaint);
    }

    /** Outline stroke for the carried card — purple, matching "this is where it lands". */
    private Paint spineDropPaint2() {
        spineDropPaint.setStyle(Paint.Style.STROKE);
        spineDropPaint.setStrokeWidth(2f * density);
        return spineDropPaint;
    }

    /**
     * SPEC W §5 — the dwell's visible tell (see the onDraw call site). A dashed
     * accent line at the pending joint, spanning the lane band, with a small ring
     * where it meets the top: "parked long enough, the view is about to show you
     * THIS". Same purple family as the gap insertion line, so it reads as the same
     * promise (a placement preview) rather than a new warning vocabulary. Drawn
     * only while the dwell is armed — passing through never shows it, because
     * passing through never arms.
     */
    private final Paint excursionTellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Cached dash — allocating a PathEffect per draw is per-frame churn (see lessons). */
    private android.graphics.DashPathEffect excursionTellDashes;

    private void drawPendingExcursionTell(@NonNull Canvas canvas) {
        if (excursionActive || pendingExcursionJointMs == Long.MIN_VALUE) return;
        if (!m7ItemGestureActive || layerGestureController == null
                || !layerGestureController.isMoveDragActive()) return;
        float contentX = timeToX(pendingExcursionJointMs);
        float x = contentX - scrollOffsetPx;
        if (x < -20f || x > getWidth() + 20f) return;
        float top = getM6RowsTopPx();
        float bottom = masterContentTopPx();
        if (bottom <= top) return;
        excursionTellPaint.setStyle(Paint.Style.STROKE);
        excursionTellPaint.setStrokeWidth(1.5f * density);
        excursionTellPaint.setColor(Studio.INK_DIM);
        excursionTellPaint.setPathEffect(excursionTellDashes);
        canvas.drawLine(x, top, x, bottom, excursionTellPaint);
        excursionTellPaint.setPathEffect(null);
        excursionTellPaint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(x, top + 6f * density, 4f * density, excursionTellPaint);
    }

    private void drawCarrySeamLine(@NonNull Canvas canvas, int seam) {
        if (segRects.isEmpty()) return;
        float contentX = seam < segRects.size()
                ? segRects.get(seam).left : segRects.get(segRects.size() - 1).right;
        float x = contentX - scrollOffsetPx;
        float top = masterContentTopPx(), bot = top + trackHeightPx;
        spineDropPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(x - 5f * density, top, x + 5f * density, bot, spineDropGlowPaint);
        canvas.drawRect(x - 1.75f * density, top, x + 1.75f * density, bot, spineDropPaint);
        canvas.drawCircle(x, top, 4f * density, spineDropPaint);
        canvas.drawCircle(x, bot, 4f * density, spineDropPaint);
    }

    private boolean adoptDislodgedDrag(@Nullable String itemId) {
        if (itemId == null || layerGestureController == null) return false;
        for (com.fadcam.ui.faditor.layers.Track t : layerTracks) {
            for (com.fadcam.ui.faditor.layers.TimedItem it : t.getItems()) {
                if (itemId.equals(it.getId())) {
                    return layerGestureController.adoptDrag(t, it);
                }
            }
        }
        return false;
    }

    private boolean onMove(float x, float y) {
        // B7: cancel shelf long-press if finger moved beyond slop
        if (clipAudioShelfLongPressSegIndex >= 0) {
            if (Math.abs(x - downX) > touchSlopPx || Math.abs(y - downY) > touchSlopPx) {
                longPressHandler.removeCallbacks(clipAudioShelfLongPressRunnable);
                clipAudioShelfLongPressSegIndex = -1;
            }
        }
        // CARRY owns the touch outright once it starts — checked before everything else so no
        // scrub, pan or item branch can steal a finger that is holding a clip.
        // Only the SPINE carry owns the touch. A lane carry is a visual skin over the layer drag
        // engine, which must keep receiving moves or the model never follows the finger.
        if (carryActive && !carryFromLayer) {
            updateCarry(x, y);
            return true;
        }
        // §3A.5b — HOLD ARMS, VERTICAL COMMITS. Checked before every other branch so a dislodge
        // cannot be swallowed by scrub/pan, and gated on VERTICAL DOMINANCE so a precision
        // horizontal move (the thing the user explicitly asked not to interrupt) never triggers it.
        if (dislodgeArmed && !isReorderMode && activeDrag == Drag.NONE) {
            float dy = y - downY, dx = x - downX;
            // DISARM on a deliberate horizontal move. Without this, holding and then sliding
            // sideways — a precision horizontal move, the exact thing the user said must never be
            // interrupted — left the arm live, so RELEASING opened the reorder dialog uninvited.
            // Same complaint the whole scheme exists to fix, reintroduced one level down.
            if (Math.abs(dx) > dislodgeThresholdPx && Math.abs(dx) > Math.abs(dy)) {
                dislodgeArmed = false;
                dislodgeArmedSegIndex = -1;
                longPressTriggered = false;
                invalidate();
            } else if (Math.abs(dy) > dislodgeThresholdPx && Math.abs(dy) > Math.abs(dx)) {
                int seg = dislodgeArmedSegIndex;
                dislodgeArmed = false;
                dislodgeArmedSegIndex = -1;
                longPressTriggered = false;   // do NOT also open the reorder dialog on UP
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                // CARRY: pick the clip UP. No model mutation here — see PLAN_CARRY_V1. The clip
                // stays on the spine as a ghost and a card follows the finger until release.
                beginCarry(seg, x, y);
                // ⚠ The spine is now one clip SHORTER, so downSegIndex points at whatever slid
                // into that slot. Leaving it set made the following ACTION_UP select and SEEK the
                // wrong clip — a visible jump straight after every successful dislodge. Retire
                // the whole gesture: this touch has done its job.
                // The spine is UNCHANGED (that is the point), but this touch is no longer a
                // segment interaction — retire it so the following ACTION_UP cannot also select
                // or seek the clip under it.
                downSegIndex = -1;
                activeDrag = Drag.NONE;
                invalidate();
                return true;
            }
        }
        if (pendingHeaderHit != null) {
            // PHASE-P P1: a header press that wanders past slop is neither a tap nor a
            // long-press — cancel both. Headers have no drag behavior; keep consuming.
            if (Math.abs(x - headerDownRawX) > touchSlopPx
                    || Math.abs(y - headerDownRawY) > touchSlopPx) {
                cancelPendingHeaderTouch();
            }
            return true;
        }
        if (m7ItemPendingDown) {
            // A body touch is selected but not yet committed. Decide from the first move
            // past slop (PLAN TARGET CONTRACT). The pickup timer runs in parallel: if it
            // fires first (finger still ~still) it flips us to m7ItemGestureActive before
            // we get here again.
            float scrolledXNow = x + scrollOffsetPx;
            float rdx = Math.abs(scrolledXNow - m7PendingDownX);
            float rdy = Math.abs(y - m7PendingDownY);
            if (rdx > touchSlopPx || rdy > touchSlopPx) {
                // Moved past slop before the long-press → NOT a pickup. Cancel the timer.
                longPressHandler.removeCallbacks(itemPickupRunnable);
                if (rdx >= rdy) {
                    // Horizontal-dominant: SCRUB the timeline, item NOT moved (the #1 fix).
                    // Release into the exact same scrub pass-through empty row space uses.
                    m7ItemPendingDown = false;
                    m6RowScrubPassthroughActive = true;
                    m6RowPendingLastX = x; // raw x seed, matches the scrub branch's convention
                    if (layerGestureController != null) layerGestureController.onRowBodyUp(false); // abort pending (no move)
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                } else {
                    // Vertical-dominant before pickup: hand off to M6 row-scroll (band
                    // scroll), item NOT moved. (Items usually fill the row, so this is
                    // rarely exercised, but keeps parity with the empty-space contract.)
                    m7ItemPendingDown = false;
                    m6RowDragActive = true;
                    m6RowLastY = y;
                    if (layerGestureController != null) layerGestureController.onRowBodyUp(false); // abort pending (no move)
                    invalidate();
                    return true;
                }
            }
            // Still within slop — consume, keep waiting for pickup or a decisive move.
            return true;
        }
        if (m7ItemGestureActive) {
            // A2 MINIMAP DRAG-NAV (user 2026-07-04: "drag onto the minimap, it slides
            // to that region, pull back down and the clip is still attached"): with a
            // picked-up MOVE, the finger entering the minimap band navigates the view
            // instead of moving the item — mapping suppressed so the item stays put;
            // leaving the band resumes normal finger tracking through the NEW offset.
            if (y <= minimapHeightPx && layerGestureController.isMoveDragActive()) {
                if (!itemDragMinimapNav) {
                    itemDragMinimapNav = true;
                    layerGestureController.setSuppressMoveMapping(true);
                    cancelPendingExcursionEnter();
                }
                seekFromMinimap(x);
                invalidate();
                return true;
            } else if (itemDragMinimapNav) {
                itemDragMinimapNav = false;
                layerGestureController.setSuppressMoveMapping(false);
            }
            // ARMED: a trim, or a picked-up move — drive the item gesture. onRowBodyMove
            // no-ops a MOVE that hasn't been picked up, so this only moves after pickup.
            float scrolledX = x + scrollOffsetPx;
            // §3A.4: decide the spine hover BEFORE onRowBodyMove, not after. onRowBodyMove is what
            // runs updateDragTarget, so suppressing afterwards would apply to the NEXT move event
            // — leaving one frame in which both indicators are drawn every time the finger crosses
            // onto the spine.
            boolean overSpine = updateSpineDropTarget(x, y);
            layerGestureController.setSpineHoverSuppressed(overSpine);
            layerGestureController.onRowBodyMove(scrolledX, y, getM6RowsTopPx(), totalEffectiveMs,
                    this::xToTimeSnapped);
            // Lift the lane item onto the floating card the first time it actually moves, then
            // track the finger and grow toward master height as it nears the spine.
            if (!carryActive && layerGestureController.isMoveDragActive()) {
                com.fadcam.ui.faditor.layers.TimedItem li = layerGestureController.getActiveItem();
                if (li != null) beginLayerCarry(li, x, y);
            }
            if (carryActive && carryFromLayer) {
                carryFingerX = x;
                carryFingerY = y;
                if (!overSpine) resolveLaneDrop(x, y); else carryDropState = CARRY_FIT;
                carryTargetH = overSpine ? trackHeightPx
                        : (layerRowRenderer != null ? layerRowRenderer.expandedRowHeightPx()
                                                    : trackHeightPx);
                invalidate();
            }
            // A1 EDGE AUTO-PAN for a held item (dragux_v3, slice 3): sustained hold near
            // the screen's left/right edge pans the timeline continuously to open more
            // room. PRECEDENCE vs the off-screen butt reveal (S5): edge-pan = deliberate
            // continuous travel and WINS while the finger is inside the edge zone — the
            // excursion's dwell timer is cancelled and an in-flight excursion is
            // abandoned in place (no glide home under a travelling finger). Outside the
            // edge zone the targeted excursion reveal runs exactly as before.
            boolean inEdgeZone = false;
            if (layerGestureController.isMoveDragActive()) {
                lastItemDragScreenX = x;
                lastItemDragScreenY = y;
                // A1 arbitration (C8): a gap-insertion hover (finger between rows, vertical
                // intent) suppresses horizontal edge-pan — the gap entry already disarmed
                // the excursion, so no scroll competes with the new-lane placement.
                inEdgeZone = !layerGestureController.isHoverGapActive()
                        && (x < edgeScrollZonePx || x > getWidth() - edgeScrollZonePx);
                if (inEdgeZone) {
                    cancelPendingExcursionEnter();
                    abandonExcursionInPlace();
                }
                // Kick the edge-scroll loop when near a horizontal edge OR a vertical M6 band edge —
                // the vertical case reveals hidden rows + the new-layer zone during a held-item move
                // (JoyRaptor 2026-07-07: "can't reach hidden rows"). The runnable self-stops when neither
                // zone is active (both deltas 0).
                boolean inVZone = m6MoveDragVerticalScrollDelta(y) != 0f;
                if ((inEdgeZone || inVZone) && !isEdgeScrolling) {
                    isEdgeScrolling = true;
                    edgeScrollHandler.post(edgeScrollRunnable);
                } else if (!inEdgeZone && !inVZone && isEdgeScrolling) {
                    stopEdgeScroll();
                }
            }
            // FOLLOW-UP 1: drive the bookend excursion from the controller's poll state —
            // an armed bookend (occupied target row) animates the view to the joint;
            // disarming (finger left that row) animates back to the anchor.
            long joint = layerGestureController.getBookendJointMs();
            if (inEdgeZone) {
                // Edge-pan owns the view; the excursion stays out of the fight.
            } else if (joint != Long.MIN_VALUE && joint != excursionShownJointMs) {
                if (excursionActive) {
                    // Already out on the excursion: a flip/retarget is deliberate —
                    // immediate (no dwell).
                    cancelPendingExcursionEnter();
                    startOrRetargetExcursion(joint);
                } else if (pendingExcursionJointMs != joint) {
                    // First pan of this hover: dwell so a slow pass-through doesn't
                    // yank the view (SPEC W §5: 700ms, parking — not passing — arms).
                    longPressHandler.removeCallbacks(excursionEnterRunnable);
                    pendingExcursionJointMs = joint;
                    dwellArmX = x;
                    dwellArmY = y;
                    longPressHandler.postDelayed(excursionEnterRunnable, EXCURSION_DWELL_MS);
                    invalidate();
                } else {
                    // SPEC W §5 — MOVEMENT RESETS THE TIMER. Same joint, but the
                    // finger travelled: this is pass-through, not parking. Restart
                    // the dwell from zero so a slow drag through lanes never arms
                    // mid-journey, however long the journey takes.
                    float dx = x - dwellArmX, dy = y - dwellArmY;
                    if (dx * dx + dy * dy > excursionDwellResetPx * excursionDwellResetPx) {
                        longPressHandler.removeCallbacks(excursionEnterRunnable);
                        dwellArmX = x;
                        dwellArmY = y;
                        longPressHandler.postDelayed(excursionEnterRunnable, EXCURSION_DWELL_MS);
                        invalidate();
                    }
                }
            } else if (joint == Long.MIN_VALUE) {
                cancelPendingExcursionEnter();
                if (excursionActive && excursionShownJointMs != Long.MIN_VALUE) {
                    endExcursion("bookend disarmed");
                }
            }
            invalidate();
            return true;
        }
        if (m6RowDragActive) {
            layerRowRenderer.scrollBy(m6RowLastY - y);
            m6RowLastY = y;
            invalidate();
            return true;
        }
        if (m6RowScrubPassthroughActive) {
            // Axis already resolved horizontal: drive the SAME primitive
            // GestureListener#onScroll uses, so this feels identical to scrubbing
            // anywhere else on the timeline. The delta MUST be computed in RAW
            // view-space x (previous raw x - current raw x), exactly like
            // GestureDetector's distanceX that onScroll consumes — NOT in content-space
            // (x + scrollOffsetPx). updatePlayheadFromX re-centers the timeline every
            // call, so scrollOffsetPx shifts by ~the same amount x moved; a content-space
            // delta (x + scrollOffsetPx) therefore nets to ~0 after the first event and
            // the playhead freezes (the bug: scrub axis resolved but the playhead never
            // moved). m6RowPendingLastX holds the previous RAW x (seeded to raw x at
            // axis-resolution below).
            float distanceX = m6RowPendingLastX - x; // raw prev - raw cur (GestureDetector convention)
            m6RowPendingLastX = x;
            float centerX = getWidth() / 2f;
            float newPlayheadX = centerX + scrollOffsetPx + distanceX;
            updatePlayheadFromX(newPlayheadX);
            getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
            return true;
        }
        if (m6RowPendingAxisDecision) {
            float scrolledXNow = x + scrollOffsetPx;
            float rdx = Math.abs(scrolledXNow - m6RowPendingDownX);
            float rdy = Math.abs(y - m6RowPendingDownY);
            if (rdx > touchSlopPx || rdy > touchSlopPx) {
                // Cancel any long-press armed for this touch (matches the generic
                // slop-exceeded cancellation below — a row-band drag is not a tap).
                longPressHandler.removeCallbacks(longPressRunnable);
                if (rdx >= rdy) {
                    // Horizontal-dominant: release into scrub pass-through. Seed
                    // m6RowPendingLastX with the RAW view-space x (the scrub branch above
                    // computes its delta in raw x to mirror GestureDetector) so the very
                    // first scrub delta is relative to THIS move, not the original down.
                    m6RowPendingAxisDecision = false;
                    m6RowScrubPassthroughActive = true;
                    m6RowPendingLastX = x;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                } else {
                    // Vertical-dominant: hand off to M6's existing row-scroll.
                    m6RowPendingAxisDecision = false;
                    m6RowDragActive = true;
                    m6RowLastY = y;
                    invalidate();
                    return true;
                }
            }
            // Still within slop — consume without committing to an axis yet.
            return true;
        }
        float dx = Math.abs(x - downX);
        float scrolledX = x + scrollOffsetPx;

        // Cancel long press if finger moved beyond slop
        if (dx > touchSlopPx) {
            longPressHandler.removeCallbacks(longPressRunnable);
            cancelMasterHeaderLongPress();
        }

        if (assetDragActive) {
            updateAssetDrag(x, y);
            startOrStopEdgeScroll(x);
            return true;
        }

        if (activeDrag == Drag.LEFT_HANDLE || activeDrag == Drag.RIGHT_HANDLE) {
            lastTrimFingerScreenX = x;  // store screen-space X for edge scroll
            doTrimDrag(scrolledX);
            // Start/stop edge auto-scroll based on finger proximity to screen edges
            startOrStopEdgeScroll(x);
            return true;
        }

        // FADE_KNOBS §2.5: spine fade knobs — map the finger to clip-local ms, write the model live.
        if (activeDrag == Drag.FADE_IN_HANDLE || activeDrag == Drag.FADE_OUT_HANDLE) {
            doMasterFadeDrag(scrolledX);
            return true;
        }

        if (activeDrag == Drag.FREEZE_LEFT_HANDLE || activeDrag == Drag.FREEZE_RIGHT_HANDLE) {
            doFreezeDrag(scrolledX);
            return true;
        }

        if (activeDrag == Drag.TEXT_ANIM_IN_HANDLE
                || activeDrag == Drag.TEXT_ANIM_OUT_HANDLE) {
            doTextAnimDrag(scrolledX);
            return true;
        }

        if (activeDrag == Drag.MOTION_RANGE_START_HANDLE
                || activeDrag == Drag.MOTION_RANGE_END_HANDLE) {
            doMotionRangeDrag(scrolledX);
            return true;
        }

        if (activeDrag == Drag.TRANSITION_LEFT_HANDLE || activeDrag == Drag.TRANSITION_RIGHT_HANDLE) {
            doTransitionTrimDrag(scrolledX);
            return true;
        }

        return true;
    }

    private boolean onUp(float x, float y, boolean isUp) {
        // B7: shelf long-press cleanup — remove pending and, if the long-press already fired, consume this UP (drawer already opened)
        longPressHandler.removeCallbacks(clipAudioShelfLongPressRunnable);
        boolean wasShelfLongPress = clipAudioShelfLongPressSegIndex == -2;
        if (clipAudioShelfLongPressSegIndex >= 0) clipAudioShelfLongPressSegIndex = -1;
        if (wasShelfLongPress) return true;
        if (carryActive && !carryFromLayer) {
            // ACTION_CANCEL must NOT commit: the system took the gesture away, the user did not
            // choose a destination. The card disappears and the clip stays where it was, which is
            // the only outcome that cannot surprise anyone.
            endCarry(isUp);
            return true;
        }
        // §3A.5b — released while ARMED without ever moving vertically: that is the reorder
        // request. Kept on hold-release (as well as double-tap) because long-press is the more
        // discoverable of the two, and the reorder window has virtues the user has said he does
        // not want to lose. Reversible: delete this block if hold-release should do nothing.
        // §3A.5b — DOUBLE-TAP to reorder was BUILT AND REMOVED on 2026-08-04. Master clips
        // ALREADY have a double-tap (the clip-audio drawer, gesture contract §1), and mine ran
        // first, so on long clips it made the audio drawer unreachable — while on short clips the
        // first tap's auto-centering shifts the second tap onto a different segment index, so the
        // drawer opened instead. The same gesture did two different things depending on clip
        // length, which is the exact bug the existing detector's clipId-based resolution was
        // written to fix. Reintroducing it one branch above was a straight regression.
        //
        // Hold-release already opens reorder, so nothing was lost. If a fast path is still wanted,
        // it has to go THROUGH the existing detector (share its window and its clipId matching),
        // not race it.
        if (dislodgeArmed) {
            dislodgeArmed = false;
            dislodgeArmedSegIndex = -1;
            if (isUp && !isReorderMode && activeDrag == Drag.NONE
                    && downSegIndex >= 0 && segments.size() > 1) {
                enterReorderMode();
                // Deliberately NOT notifying onPlayheadDragFinished here. It was added as
                // stranded-latch insurance, but `userDragging` is provably never set on this
                // branch (activeDrag == NONE, no scrub) — so it cleared nothing and instead ran
                // the handler's clip-swap block, forcing a loadClipForPlayback + seekInClip at the
                // instant the reorder UI opens.
                invalidate();
                return true;
            }
            invalidate();
        }
        if (pendingHeaderHit != null) {
            // PHASE-P P1: header press resolved. A quick tap (long-press didn't fire,
            // real UP) fires the same icon action the old on-DOWN path did; after a
            // long-press (menu already open) or a CANCEL, just clean up.
            com.fadcam.ui.faditor.layers.LayerRowRenderer.HeaderHit hit = pendingHeaderHit;
            boolean fired = headerLongPressFired;
            cancelPendingHeaderTouch();
            if (isUp && !fired
                    && hit.zone != com.fadcam.ui.faditor.layers.LayerRowRenderer.HitZone.NONE
                    && trackHeaderActionListener != null) {
                trackHeaderActionListener.onTrackHeaderAction(hit.track, hit.zone);
            }
            getParent().requestDisallowInterceptTouchEvent(false);
            invalidate();
            return true;
        }
        if (m7ItemPendingDown) {
            // Body touch resolved as a TAP: within slop, lifted before the pickup timer.
            // Selection already happened on DOWN; kill the timer (else it would fire
            // ~450ms AFTER the finger left and lift the item with nothing touching) and
            // close the controller's pending gesture (records nothing — no move).
            longPressHandler.removeCallbacks(itemPickupRunnable);
            m7ItemPendingDown = false;
            layerGestureController.onRowBodyUp(isUp);
            // §5.4 fluent creation: a fade-in dragged past its own clip's start asked for a
            // cross-fade. The controller detects it (it knows the fade geometry) but cannot
            // create it (no Timeline there); this view holds one, so it does the creating.
            if (liveTimeline != null) {
                com.fadcam.ui.faditor.model.AudioCrossfade req =
                        layerGestureController.consumePendingCrossfadeRequest();
                if (req != null) {
                    liveTimeline.addAudioCrossfade(req);
                    layerRowRenderer.setSelectedCrossfadeId(req.getId());
                    if (listener != null) {
                        listener.onCrossfadeCreated(req);
                        listener.onCrossfadeSelected(req);
                    }
                    invalidate();
                }
            }
            getParent().requestDisallowInterceptTouchEvent(false);
            invalidate();
            return true;
        }
        if (m7ItemGestureActive) {
            // B2.U3 — cross-fade pill drag finished: one undo step for whole gesture
            if (layerGestureController.isCrossfadeDragActive()) {
                m7ItemGestureActive = false;
                stopEdgeScroll();
                com.fadcam.ui.faditor.model.AudioCrossfade before = layerGestureController.finishCrossfadeDrag();
                if (before != null && listener != null && liveTimeline != null) {
                    com.fadcam.ui.faditor.model.AudioCrossfade after = liveTimeline.findAudioCrossfade(before.getId());
                    if (after != null) {
                        com.fadcam.ui.faditor.model.AudioCrossfade afterCopy = new com.fadcam.ui.faditor.model.AudioCrossfade(after);
                        listener.onCrossfadeChanged(before, afterCopy);
                    }
                }
                invalidate();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            m7ItemGestureActive = false;
            stopEdgeScroll(); // A1: the held-item edge pan ends with the finger
            if (itemDragMinimapNav) {
                // Finger lifted while parked on the minimap band: end the nav state;
                // the commit below drops the item where it already legally sits.
                itemDragMinimapNav = false;
                layerGestureController.setSuppressMoveMapping(false);
            }
            // §3A.4 SPINE DROP. Taken BEFORE onRowBodyUp, because that call clears the gesture.
            int spineDrop = isUp ? spineDropIndex : -1;
            spineDropIndex = -1;
            // isUp==false is an ACTION_CANCEL — the controller ABORTS (reverts the item,
            // fires no drop callbacks) instead of committing (review fix 2026-07-03).
            // A spine drop deliberately takes the ABORT path too: the item is about to leave the
            // layer entirely, so committing a layer-position change first would record an edit to
            // a lane the object no longer lives on, and undo would walk back through it.
            layerGestureController.onRowBodyUp(spineDrop >= 0 ? false : isUp);
            // §5.4 fluent creation: a fade-in dragged past its own clip's start asked for a
            // cross-fade. The controller detects it (it knows the fade geometry) but cannot
            // create it (no Timeline there); this view holds one, so it does the creating.
            if (liveTimeline != null) {
                com.fadcam.ui.faditor.model.AudioCrossfade req =
                        layerGestureController.consumePendingCrossfadeRequest();
                if (req != null) {
                    liveTimeline.addAudioCrossfade(req);
                    layerRowRenderer.setSelectedCrossfadeId(req.getId());
                    if (listener != null) {
                        listener.onCrossfadeCreated(req);
                        listener.onCrossfadeSelected(req);
                    }
                    invalidate();
                }
            }
            if (spineDrop >= 0 && listener != null) {
                android.util.Log.d("SPINEDROP", "DROP seam=" + spineDrop);
                listener.onItemDroppedOnMasterTrack(spineDrop);
            }
            // Disarm the undo merge unconditionally. onRowBodyUp above has already run
            // onGestureFinished on the commit path (which consumes it), but a CANCEL fires no
            // drop callback at all -- and a commit that happens to record NOTHING consumes
            // nothing either. Either way an armed merge must not survive into the next edit,
            // where it would silently swallow an unrelated action into this gesture's entry.
            if (carryFromLayer) {
                carryActive = false;
                carryFromLayer = false;
                carryLayerItemId = null;
                carryThumbKey = null;
                if (layerRowRenderer != null) layerRowRenderer.setCarriedItemId(null);
            }
            if (listener != null) listener.onItemDragEnded();
            // A drop (or cancel) during an excursion: glide home so the playhead is
            // re-centered again (the normal invariant) — the user sees the result land
            // at the bookend, then the view returns to where they were.
            cancelPendingExcursionEnter();
            if (excursionActive) endExcursion(isUp ? "drop" : "cancel");
            invalidate();
            return true;
        }
        if (m6RowDragActive) {
            m6RowDragActive = false;
            return true;
        }
        if (m6RowScrubPassthroughActive) {
            m6RowScrubPassthroughActive = false;
            // Fling-inertia parity (user feedback 2026-07-03: "the other bars lack this
            // gliding feeling"): hand the release velocity to the SAME fling the gesture
            // detector's onFling gives the main timeline, so a row-band scrub glides and
            // decelerates identically. Only on a real UP — a CANCEL just stops.
            if (isUp && rowScrubVelocityTracker != null) {
                rowScrubVelocityTracker.computeCurrentVelocity(1000, maxFlingVelocityPx);
                float vx = rowScrubVelocityTracker.getXVelocity();
                if (Math.abs(vx) > minFlingVelocityPx) {
                    startPlayheadFling(vx);
                }
            }
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        if (m6RowPendingAxisDecision) {
            // Slop never exceeded — a tap on empty row space (or a locked/hidden row's
            // body). Nothing to select, nothing to scrub; just consume like the M6
            // header-hit "NONE zone" case does.
            m6RowPendingAxisDecision = false;
            // AV3: a pure tap on a collapsed (thin) AUDIO bar expands it — the discovery
            // affordance for the quad-band tape. Routes through the SAME caret toggle path
            // (undo step + flag persistence) so it behaves exactly like tapping the caret.
            if (pendingCollapsedAudioTrack != null && trackHeaderActionListener != null) {
                trackHeaderActionListener.onTrackHeaderAction(pendingCollapsedAudioTrack,
                        com.fadcam.ui.faditor.layers.LayerRowRenderer.HitZone.CARET);
                pendingCollapsedAudioTrack = null;
            }
            // Release the parent-intercept claim taken on the pending DOWN (the armed
            // branches above already do this; the tap-only path forgot to).
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        Drag last = activeDrag;
        float scrolledX = x + scrollOffsetPx;

        // Stop edge auto-scroll and cancel long presses
        stopEdgeScroll();
        longPressHandler.removeCallbacks(longPressRunnable);
        cancelMasterHeaderLongPress();

        // Tear down the frame-accurate trim-edge preview when a trim drag ends.
        if (last == Drag.LEFT_HANDLE || last == Drag.RIGHT_HANDLE) {
            clearTrimEdgePreview();
            loopReadoutActive = false; // L3: hide the loop-extension readout on release
        }

        // FADE_KNOBS §2.5: one undo step for the whole spine fade gesture.
        if ((last == Drag.FADE_IN_HANDLE || last == Drag.FADE_OUT_HANDLE)
                && listener != null
                && (fadeStartInMs != fadeLastInMs || fadeStartOutMs != fadeLastOutMs)) {
            listener.onMasterFadeFinished(selectedIndex,
                    fadeStartInMs, fadeStartOutMs, fadeLastInMs, fadeLastOutMs);
        }

        // (The legacy in-strip audio drag-resolve and audio tap-select/double-tap blocks
        // were deleted 2026-08-22 — SPEC_AUDIO_UX_V1 row A1. Audio selection now lives
        // entirely in LayerGestureController; getSelectedAudioIndex() derives from it.)

        if (last == Drag.LEFT_HANDLE || last == Drag.RIGHT_HANDLE) {
            if (listener != null) {
                if (loopChangedDuringDrag) {
                    // LOOP-EXTENSION drag: the in/out points were PINNED to the source bounds while
                    // the handle crossed past them (doTrimDrag clamps newOut→sourceDuration /
                    // newIn→0 and moves the overshoot into loopBefore/loopAfter). So trimDragEndFrac
                    // is 1.0 (or startFrac 0.0) — NOT the clip's real trim. Firing onTrimFinished
                    // here would commit that clamped fraction as a genuine trim, EXPANDING an
                    // already-trimmed clip out to its full source (the "resize reverts / changes
                    // size" regression). Fire ONLY the loop callback; the trim is unchanged.
                    listener.onLoopTrimFinished(selectedIndex,
                            trimDragStartLoopBefore, trimDragStartLoopAfter,
                            trimDragLoopBefore, trimDragLoopAfter);
                } else {
                    // Plain trim (handle stayed within source bounds): commit the trim fractions.
                    listener.onTrimFinished(selectedIndex, trimDragStartFrac, trimDragEndFrac);
                }
            }
            loopChangedDuringDrag = false;
        } else if (last == Drag.FREEZE_LEFT_HANDLE || last == Drag.FREEZE_RIGHT_HANDLE) {
            finishFreezeDrag();
        } else if (last == Drag.TEXT_ANIM_IN_HANDLE
                || last == Drag.TEXT_ANIM_OUT_HANDLE) {
            finishTextAnimDrag();
        } else if (last == Drag.MOTION_RANGE_START_HANDLE
                || last == Drag.MOTION_RANGE_END_HANDLE) {
            finishMotionRangeDrag();
        } else if (last == Drag.TRANSITION_LEFT_HANDLE || last == Drag.TRANSITION_RIGHT_HANDLE) {
            if (listener != null) {
                long newDur = transitionDurationFromDragX(transitionDragX);
                listener.onTransitionDurationFinished(transitionDragIndex, newDur);
            }
        } else if (isUp && downSegIndex >= 0) {
            if (Math.abs(x - downX) < touchSlopPx) {
                // SPEC_N §5: THE deliberate tap. Armed HERE — at the top of the tap branch and
                // BEFORE seekToTimelineMs() fires the selection cascade — because every
                // selection callback that follows would otherwise arrive while the knobs were
                // still unarmed and be indistinguishable from a playhead-driven selection.
                spineTapArmedClipId = downSegIndex < segments.size()
                        ? segments.get(downSegIndex).clipId : null;
                // Tapping a yellow silence candidate converts it to a cut.
                if (showSilence && tryTapSilenceCandidate(downSegIndex, downX)) {
                    // consumed — don't change selection (and never pairs into a double-tap)
                    lastMasterTapClipId = null;
                } else {
                    // Clip-audio drawer (v2): two quick taps on the SAME master segment =
                    // toggle its audio drawer (gesture contract §1's double-tap slot for
                    // master clips). Detected BEFORE the selection toggle so the second
                    // tap doesn't deselect what the first tap selected.
                    SegmentData tappedSd = downSegIndex < segments.size()
                            ? segments.get(downSegIndex) : null;
                    long tapNow = lastTouchEventTimeMs != 0L
                            ? lastTouchEventTimeMs : android.os.SystemClock.uptimeMillis();
                    // Double-tap = same SCREEN spot in quick succession, resolved against the
                    // FIRST tap's clip (JoyRaptor 2026-07-14). The first tap's seek auto-centers the
                    // strip, so on short clips the second tap resolves to a DIFFERENT (shifted)
                    // segment even though the finger never moved — the old same-segment check
                    // made those clips un-double-tappable.
                    boolean isDoubleTap = lastMasterTapClipId != null
                            && tapNow - lastMasterTapUpMs <= MASTER_DOUBLE_TAP_WINDOW_MS
                            && Math.abs(downX - lastMasterTapScreenX) <= touchSlopPx * 2f;
                    String doubleTapClipId = isDoubleTap ? lastMasterTapClipId : null;
                    int doubleTapSegIndex = -1;
                    if (isDoubleTap) {
                        for (int i = 0; i < segments.size(); i++) {
                            SegmentData s = segments.get(i);
                            if (doubleTapClipId.equals(s.clipId) && !s.isImageClip) {
                                doubleTapSegIndex = i;
                                break;
                            }
                        }
                        if (doubleTapSegIndex < 0) isDoubleTap = false; // clip gone / image clip
                    }
                    lastMasterTapClipId = tappedSd != null ? tappedSd.clipId : null;
                    lastMasterTapUpMs = tapNow;
                    lastMasterTapScreenX = downX;

                    if (!isDoubleTap) {
                        // Move the playhead to the tap so play resumes EXACTLY here
                        // (previously a tap only selected, leaving the playhead — and
                        // thus playback — at the old position). Skipped on the second tap
                        // of a double-tap: the first tap already sought, and re-seeking at
                        // the post-center screen X would jump onto a neighbouring clip.
                        seekToTimelineMs(xToTime(downX + scrollOffsetPx));
                    }

                    if (isDoubleTap) {
                        lastMasterTapClipId = null; // consume the pair (no triple-chains)
                        SegmentData dtSd = segments.get(doubleTapSegIndex);
                        if (dtSd.clip != null && dtSd.clip.isGeneratedSlide()) {
                            // Slides are silent by construction — the audio shelf is
                            // useless there. Double-tap opens the slide's code editor
                            // instead (JoyRaptor 2026-07-16).
                            if (selectedIndex != doubleTapSegIndex) {
                                selectedIndex = doubleTapSegIndex;
                                if (listener != null) listener.onSegmentSelected(doubleTapSegIndex);
                            }
                            if (listener != null) listener.onSlideDoubleTapped(doubleTapSegIndex);
                            invalidate();
                            getParent().requestDisallowInterceptTouchEvent(false);
                            activeDrag = Drag.NONE;
                            transitionDragIndex = -1;
                            downSegIndex = -1;
                            return true;
                        }
                        toggleLayerAudioDrawers(doubleTapClipId);
                        // Keep the first tap's selection: ensure the clip stays selected
                        // instead of the same-segment tap-toggle deselecting it.
                        if (selectedIndex != doubleTapSegIndex) {
                            selectedIndex = doubleTapSegIndex;
                            if (listener != null) listener.onSegmentSelected(doubleTapSegIndex);
                        }
                        invalidate();
                    } else if (downSegIndex == selectedIndex) {
                        // Toggle selection: deselect if same segment, select if different
                        selectedIndex = -1;
                        invalidate();
                        if (listener != null) listener.onSegmentSelected(-1);
                    } else {
                        selectedIndex = downSegIndex;
                        invalidate();
                        if (listener != null) listener.onSegmentSelected(downSegIndex);
                    }
                }
            }
        }

        // Reset drag state and notify listener that playhead drag finished (if any)
        if (listener != null && last == Drag.NONE) {
            listener.onPlayheadDragFinished();
        }
        
        activeDrag = Drag.NONE;
        transitionDragIndex = -1;
        downSegIndex = -1;
        getParent().requestDisallowInterceptTouchEvent(false);
        return true;
    }

    // ── Touch helpers ────────────────────────────────────────────────

    /** FADE_KNOBS §2.1 spine knobs: same geometry as the lane-row knobs (20dp drawn, 48dp hit).
     *  Offset 22dp — JoyRaptor: the spine knobs sat a touch low, lift them a little further up. */
    private static final float SPINE_KNOB_R_DP = 10f;
    private static final float SPINE_KNOB_HIT_R_DP = 24f;
    private static final float SPINE_KNOB_TOP_OFFSET_DP = 22f;
    /** Fade values snapshot for one-undo on the spine fade drag. */
    private long fadeStartInMs, fadeStartOutMs;
    private long fadeLastInMs, fadeLastOutMs;

    private Drag hitTestHandle(float x, float y) {
        if (selectedIndex < 0 || selectedIndex >= segRects.size()) return Drag.NONE;
        RectF seg = segRects.get(selectedIndex);
        Drag fk = hitTestFadeKnobOnly(x, y);
        if (fk != Drag.NONE) return fk;
        float hTop = seg.top - handleOverhangPx;
        float hBot = seg.bottom + handleOverhangPx;

        if (y < hTop - touchSlopPx / 2 || y > hBot + touchSlopPx / 2) return Drag.NONE;

        // Left handle zone
        if (x >= seg.left - touchSlopPx / 2 && x <= seg.left + handleWidthPx + touchSlopPx / 2) {
            return Drag.LEFT_HANDLE;
        }
        // Right handle zone
        if (x >= seg.right - handleWidthPx - touchSlopPx / 2 && x <= seg.right + touchSlopPx / 2) {
            return Drag.RIGHT_HANDLE;
        }
        return Drag.NONE;
    }

    /** The knob half of the spine hit-test, shared by hitTestHandle and the pre-transition check. */
    private Drag hitTestFadeKnobOnly(float x, float y) {
        if (selectedIndex < 0 || selectedIndex >= segRects.size()) return Drag.NONE;
        RectF seg = segRects.get(selectedIndex);
        Clip fadeClip = selectedIndex < segments.size() ? segments.get(selectedIndex).clip : null;
        if (fadeClip == null) return Drag.NONE;
        float knobHitR = SPINE_KNOB_HIT_R_DP * density;
        float knobOffset = SPINE_KNOB_TOP_OFFSET_DP * density;
        float cy = seg.top - knobOffset;
        if (y < cy - knobHitR || y > seg.bottom) return Drag.NONE;
        long trimmed = Math.max(1, fadeClip.getTrimmedDurationMs());
        float inX = spineKnobX(true, seg, fadeClip);
        float outX = spineKnobX(false, seg, fadeClip);
        float dIn = (float) Math.sqrt((x - inX) * (x - inX) + (y - cy) * (y - cy));
        float dOut = (float) Math.sqrt((x - outX) * (x - outX) + (y - cy) * (y - cy));
        if (dIn <= knobHitR && dIn <= dOut) return Drag.FADE_IN_HANDLE;
        if (dOut <= knobHitR) return Drag.FADE_OUT_HANDLE;
        return Drag.NONE;
    }

    /** Content-x of a spine fade knob for the selected segment (draw + drag share this). */
    private float spineKnobX(boolean fadeIn, @NonNull RectF seg, @NonNull Clip clip) {
        long trimmed = Math.max(1, clip.getTrimmedDurationMs());
        float w = Math.max(1f, seg.width());
        long f = Math.max(0, Math.min(fadeIn ? clip.getMasterFadeInMs() : clip.getMasterFadeOutMs(), trimmed));
        return fadeIn ? seg.left + w * f / (float) trimmed
                      : seg.right - w * f / (float) trimmed;
    }

    /** FADE_KNOBS §2.5: live write for a spine knob drag — position IS the readout, like the lane rows. */
    private void doMasterFadeDrag(float scrolledX) {
        if (selectedIndex < 0 || selectedIndex >= segRects.size()) return;
        Clip clip = segments.get(selectedIndex).clip;
        if (clip == null) return;
        RectF seg = segRects.get(selectedIndex);
        long trimmed = Math.max(1, clip.getTrimmedDurationMs());
        float frac = (scrolledX - seg.left) / Math.max(1f, seg.width());
        long t = Math.round(trimmed * Math.max(0f, Math.min(1f, frac)));
        boolean fadeIn = activeDrag == Drag.FADE_IN_HANDLE;
        long half = trimmed / 2;
        long v = fadeIn ? Math.max(0, Math.min(half, t)) : Math.max(0, Math.min(half, trimmed - t));
        if (v <= 40) v = 0; // same dead-zone as the lane-row knobs
        long other = fadeIn ? clip.getMasterFadeOutMs() : clip.getMasterFadeInMs();
        if (v + other > trimmed) v = Math.max(0, trimmed - other);
        if (fadeIn) clip.setMasterFadeInMs(v); else clip.setMasterFadeOutMs(v);
        fadeLastInMs = clip.getMasterFadeInMs();
        fadeLastOutMs = clip.getMasterFadeOutMs();
        invalidate();
    }

    // ── Slide freeze-zone handles (JoyRaptor 2026-07-16) ─────────────────

    /** The selected segment's clip when it is a generated slide, else null. */
    @Nullable
    private Clip selectedSlideClip() {
        if (selectedIndex < 0 || selectedIndex >= segments.size()) return null;
        Clip c = segments.get(selectedIndex).clip;
        return c != null && c.isGeneratedSlide() ? c : null;
    }

    /** Visual x of the freeze-start marker: inset just inside the left trim bar. */
    private float freezeLeftX(@NonNull RectF seg, @NonNull Clip clip) {
        long trimmed = Math.max(1, clip.getTrimmedDurationMs());
        long fs = clip.getGeneratedSource() != null
                ? Math.max(0, clip.getGeneratedSource().freezeStartMs) : 0;
        return seg.left + handleWidthPx + (seg.width() * fs / trimmed);
    }

    /** Visual x of the freeze-end marker: inset just inside the right trim bar. */
    private float freezeRightX(@NonNull RectF seg, @NonNull Clip clip) {
        long trimmed = Math.max(1, clip.getTrimmedDurationMs());
        long fe = clip.getGeneratedSource() != null
                ? Math.max(0, clip.getGeneratedSource().freezeEndMs) : 0;
        return seg.right - handleWidthPx - (seg.width() * fe / trimmed);
    }

    /**
     * Hit-test the slide freeze-zone markers. Deliberately TIGHT (no touch
     * slop) so the generous outer trim-handle zones keep winning at the edges;
     * the markers move inward and out of conflict as soon as a zone is set.
     */
    private Drag hitTestFreezeHandle(float x, float y) {
        Clip slide = selectedSlideClip();
        if (slide == null || selectedIndex >= segRects.size()) return Drag.NONE;
        RectF seg = segRects.get(selectedIndex);
        if (y < seg.top || y > seg.bottom) return Drag.NONE;
        float zone = handleWidthPx * 0.9f;
        if (Math.abs(x - freezeLeftX(seg, slide)) <= zone) return Drag.FREEZE_LEFT_HANDLE;
        if (Math.abs(x - freezeRightX(seg, slide)) <= zone) return Drag.FREEZE_RIGHT_HANDLE;
        return Drag.NONE;
    }

    /** Live freeze-zone drag: clamp the marker inside the clip window. */
    private void doFreezeDrag(float x) {
        Clip slide = selectedSlideClip();
        if (slide == null || selectedIndex >= segRects.size()) return;
        RectF seg = segRects.get(selectedIndex);
        float min, max;
        if (activeDrag == Drag.FREEZE_LEFT_HANDLE) {
            min = seg.left + handleWidthPx;
            max = (activeDragOtherFreezeX(seg, slide)) - handleWidthPx;
        } else {
            min = (activeDragOtherFreezeX(seg, slide)) + handleWidthPx;
            max = seg.right - handleWidthPx;
        }
        freezeDragX = Math.max(min, Math.min(x, Math.max(min, max)));
        invalidate();
    }

    private float activeDragOtherFreezeX(@NonNull RectF seg, @NonNull Clip slide) {
        return activeDrag == Drag.FREEZE_LEFT_HANDLE
                ? freezeRightX(seg, slide) : freezeLeftX(seg, slide);
    }

    /** Commit the freeze drag: px → clip-window ms, then notify the listener. */
    private void finishFreezeDrag() {
        Clip slide = selectedSlideClip();
        if (slide == null || selectedIndex >= segRects.size() || listener == null) return;
        RectF seg = segRects.get(selectedIndex);
        long trimmed = Math.max(1, slide.getTrimmedDurationMs());
        com.fadcam.ui.faditor.model.GeneratedSource gs = slide.getGeneratedSource();
        long fs = gs != null ? Math.max(0, gs.freezeStartMs) : 0;
        long fe = gs != null ? Math.max(0, gs.freezeEndMs) : 0;
        if (activeDrag == Drag.FREEZE_LEFT_HANDLE) {
            fs = Math.round((freezeDragX - seg.left - handleWidthPx) * trimmed / seg.width());
        } else {
            fe = Math.round((seg.right - handleWidthPx - freezeDragX) * trimmed / seg.width());
        }
        fs = Math.max(0, Math.min(fs, trimmed));
        fe = Math.max(0, Math.min(fe, trimmed - fs));
        listener.onSlideFreezeChanged(selectedIndex, fs, fe);
        invalidate();
    }

    /**
     * Freeze markers + zone tint for a selected slide clip: a ▶ marker where
     * the animation starts, a ◀ where it ends, and a subtle tint over the
     * frozen zones so the three-zone structure reads at a glance.
     */
    private void drawSlideFreezeHandles(Canvas canvas, RectF seg) {
        Clip slide = selectedSlideClip();
        if (slide == null) return;
        float lx = activeDrag == Drag.FREEZE_LEFT_HANDLE
                ? freezeDragX : freezeLeftX(seg, slide);
        float rx = activeDrag == Drag.FREEZE_RIGHT_HANDLE
                ? freezeDragX : freezeRightX(seg, slide);
        // Frozen-zone tint (between the trim bar and the marker).
        if (lx > seg.left + handleWidthPx + 1f) {
            canvas.drawRect(seg.left + handleWidthPx, seg.top, lx, seg.bottom,
                    freezeZonePaint);
        }
        if (rx < seg.right - handleWidthPx - 1f) {
            canvas.drawRect(rx, seg.top, seg.right - handleWidthPx, seg.bottom,
                    freezeZonePaint);
        }
        drawFreezeMarker(canvas, lx, seg, true);
        drawFreezeMarker(canvas, rx, seg, false);
    }

    private void drawFreezeMarker(Canvas canvas, float x, RectF seg, boolean pointsRight) {
        float cy = seg.centerY();
        float h = handleNotchHeightPx * 1.2f;
        float w = handleWidthPx * 0.8f;
        android.graphics.Path p = new android.graphics.Path();
        if (pointsRight) {
            p.moveTo(x - w / 2f, cy - h / 2f);
            p.lineTo(x - w / 2f, cy + h / 2f);
            p.lineTo(x + w / 2f, cy);
        } else {
            p.moveTo(x + w / 2f, cy - h / 2f);
            p.lineTo(x + w / 2f, cy + h / 2f);
            p.lineTo(x - w / 2f, cy);
        }
        p.close();
        canvas.drawPath(p, freezeMarkerPaint);
    }

    // ── In/out timing carets (SPEC_TEXT_ANIMATION, LEDGER §3h) ──────────────────────────────
    //
    // These shipped for CAPTIONS first, the user drove them, and his verdict was "carets worked
    // well". He then rejected them FOR CAPTIONS anyway, for a reason no amount of polish would
    // have fixed: "to get a fifty percent fade in, fifty percent fade out, I'm gonna be having to
    // do a lot of dragging over perhaps a thirty minute clip. And that just won't do." Captions
    // arrive line after line down a long video and the timing wanted is ONE value for all of
    // them, so captions moved to a range control in the caption style drawer, which is still
    // where they are authored.
    //
    // A TEXT BOX is the case the carets were designed for and the one the user reserved them for,
    // in his words: "those carets were expected to be put on text in the first place. And how they
    // were operating in the closed captions worked perfectly well for text." A single object, one
    // visible span on the tape, a gesture done once. So the machinery below was parked rather than
    // deleted, and this is it revived — aimed at a text box, with the INTERACTION unchanged: drag
    // the caret inward, the zone it covers tints, release commits one undo step.
    //
    // The maths moved to CaptionAnimator (caretTravelPx / caretInX / caretOutX / zoneFromCaretX)
    // where the harness can reach it. What stays here is what is genuinely about this view: which
    // rect the carets ride, and which finger owns which grab.
    //
    // ⚠ TWO COORDINATE SYSTEMS, and they are not the obvious ones. An item's body is CONTENT-x
    // (it scrolls with the timeline) but SCREEN-y (the layer band has its OWN vertical scroll, in
    // a field that happens to share this view's field NAME — LayerRowRenderer.scrollOffsetPx is a
    // different variable on a different axis). Rather than reconstruct that here, the rect comes
    // from LayerRowRenderer.itemBodyRect, which is written to mirror hitTestItem line for line.
    // One derivation. Two would put the caret where the finger cannot reach it.

    /** Whether the carets are offered at all. Text boxes drive this; captions no longer do. */
    private boolean textAnimHandlesVisible = true;

    /**
     * Show or hide the in/out timing carets.
     *
     * <p>Default ON: the carets ARE the timing control for a text box, and they appear only on a
     * SELECTED animated item anyway, so there is nothing for an off state to protect against.
     * Kept as a switch so a future mode that needs the tape for something else can take it.</p>
     */
    public void setTextAnimHandlesVisible(boolean visible) {
        if (textAnimHandlesVisible == visible) return;
        textAnimHandlesVisible = visible;
        invalidate();
    }

    /**
     * The selected layer item's text overlay when the timing carets apply to it, else null.
     *
     * <p>Gated on the item having a PRESET, not on it having a zone. Zero zones are the off state
     * of the timing, not of the control — an item whose zones the user has just dragged to zero
     * must keep its carets, or the gesture would delete its own instrument and there would be no
     * way back. Conversely an item with no preset has nothing to time, and carets on it would be a
     * control that visibly does nothing.</p>
     */
    @Nullable
    private com.fadcam.ui.faditor.model.TextOverlayItem selectedTextAnimItem() {
        if (!textAnimHandlesVisible || layerGestureController == null) return null;
        String id = layerGestureController.getSelectedItemId();
        if (id == null) return null;
        for (com.fadcam.ui.faditor.layers.Track t : layerTracks) {
            for (com.fadcam.ui.faditor.layers.TimedItem item : t.getItems()) {
                if (!id.equals(item.getId())) continue;
                com.fadcam.ui.faditor.model.TextOverlayItem o = item.getTextOverlay();
                if (o == null) return null;
                return com.fadcam.ui.faditor.transcript.CaptionAnimator
                        .parsePreset(o.getTextAnimPreset())
                        == com.fadcam.ui.faditor.transcript.CaptionAnimator.Preset.NONE
                        ? null : o;
            }
        }
        return null;
    }

    /**
     * The selected item's tape, in the space this view draws layer content in (content-x,
     * screen-y). Null whenever the carets cannot be placed — no selection, no preset, the row
     * collapsed/locked/hidden, or the row scrolled out of the layer band.
     */
    @Nullable
    private RectF textAnimRect() {
        if (layerRowRenderer == null || layerGestureController == null) return null;
        String id = layerGestureController.getSelectedItemId();
        if (id == null) return null;
        return layerRowRenderer.itemBodyRect(id, getM6RowsTopPx(), totalEffectiveMs, this::timeToX);
    }

    /**
     * How far in from each end of the tape the carets' travel starts: the item's own trim-handle
     * half-width, taken FROM the renderer rather than guessed, so a caret at zone 0 rests just
     * inboard of the trim grab instead of on top of it.
     */
    private float textAnimInsetPx() {
        return layerRowRenderer.itemHandleHalfWidthPx();
    }

    /** Whether this tape is wide enough for the carets to be drawn and dragged at all. */
    private boolean textAnimUsable(@NonNull RectF r) {
        return com.fadcam.ui.faditor.transcript.CaptionAnimator
                .caretTravelPx(r.left, r.right, textAnimInsetPx()) > 0f;
    }

    /**
     * Hit-test the timing carets. Deliberately TIGHT and checked BEFORE the item's own trim
     * handles, exactly as the slide freeze carets are against the master trim bar: at zone 0 a
     * caret sits just inboard of the trim cap, and the tight zone is what lets it be grabbed at
     * all while the cap's fuller slop still owns a true edge grab.
     *
     * <p>The zone is 0.9 of the inset against the trim's full inset, so the caret is the narrower
     * of the two claims on that neighbourhood — the same relationship the caption carets had with
     * the master trim bar, which is the version the user drove and approved.</p>
     */
    private Drag hitTestTextAnimHandle(float x, float y) {
        com.fadcam.ui.faditor.model.TextOverlayItem o = selectedTextAnimItem();
        if (o == null) return Drag.NONE;
        RectF r = textAnimRect();
        if (r == null || !textAnimUsable(r)) return Drag.NONE;
        if (y < r.top || y > r.bottom) return Drag.NONE;
        float inset = textAnimInsetPx();
        float zone = inset * 0.9f;
        if (Math.abs(x - com.fadcam.ui.faditor.transcript.CaptionAnimator.caretInX(
                r.left, r.right, inset, o.getTextAnimInPct())) <= zone) {
            return Drag.TEXT_ANIM_IN_HANDLE;
        }
        if (Math.abs(x - com.fadcam.ui.faditor.transcript.CaptionAnimator.caretOutX(
                r.left, r.right, inset, o.getTextAnimOutPct())) <= zone) {
            return Drag.TEXT_ANIM_OUT_HANDLE;
        }
        return Drag.NONE;
    }

    /**
     * Live caret drag. Each caret is clamped to its own half of the tape: at full travel both sit
     * ON the centre, which is the intended "animates in, and starts animating out the instant it
     * is in" state rather than a collision to be prevented.
     *
     * <p>Previews through the listener on every move but records NOTHING — one undo entry per
     * pixel would bury the user's history under the gesture's own. The single entry is written on
     * release by {@link #finishTextAnimDrag}.</p>
     */
    private void doTextAnimDrag(float x) {
        com.fadcam.ui.faditor.model.TextOverlayItem o = selectedTextAnimItem();
        if (o == null) return;
        RectF r = textAnimRect();
        if (r == null || !textAnimUsable(r)) return;
        float inset = textAnimInsetPx();
        float centre = r.left + inset
                + com.fadcam.ui.faditor.transcript.CaptionAnimator
                        .caretTravelPx(r.left, r.right, inset);
        float min, max;
        if (activeDrag == Drag.TEXT_ANIM_IN_HANDLE) {
            min = r.left + inset;
            max = centre;
        } else {
            min = centre;
            max = r.right - inset;
        }
        textAnimDragX = Math.max(min, Math.min(x, max));
        if (listener != null) {
            listener.onTextAnimZonesPreviewed(o.getId(),
                    textAnimZoneIn(o, r), textAnimZoneOut(o, r));
        }
        invalidate();
    }

    /** The in-zone this gesture currently implies: the dragged value, or the stored one. */
    private float textAnimZoneIn(@NonNull com.fadcam.ui.faditor.model.TextOverlayItem o,
                                 @NonNull RectF r) {
        if (activeDrag != Drag.TEXT_ANIM_IN_HANDLE) return o.getTextAnimInPct();
        return com.fadcam.ui.faditor.transcript.CaptionAnimator.zoneFromCaretInX(
                r.left, r.right, textAnimInsetPx(), textAnimDragX);
    }

    /** The out-zone this gesture currently implies: the dragged value, or the stored one. */
    private float textAnimZoneOut(@NonNull com.fadcam.ui.faditor.model.TextOverlayItem o,
                                  @NonNull RectF r) {
        if (activeDrag != Drag.TEXT_ANIM_OUT_HANDLE) return o.getTextAnimOutPct();
        return com.fadcam.ui.faditor.transcript.CaptionAnimator.zoneFromCaretOutX(
                r.left, r.right, textAnimInsetPx(), textAnimDragX);
    }

    /**
     * Commit the caret drag: px → a FRACTION OF THE BOX'S SPAN, then notify the listener ONCE.
     *
     * <p>There is no unit conversion left to get wrong. This method used to map travel onto a
     * clip's usable zone range in source ms and carried a note about why it must NOT divide by the
     * speed multiplier the way the freeze carets do. The stored value is a fraction, which has no
     * units at all — full travel is {@code CaptionAnimator.MAX_ZONE_PCT} on every box at every
     * length — so the hazard is gone rather than merely handled.</p>
     */
    private void finishTextAnimDrag() {
        com.fadcam.ui.faditor.model.TextOverlayItem o = selectedTextAnimItem();
        if (o == null || listener == null) return;
        RectF r = textAnimRect();
        if (r == null || !textAnimUsable(r)) return;
        listener.onTextAnimZonesChanged(o.getId(), textAnimBeforeIn, textAnimBeforeOut,
                textAnimZoneIn(o, r), textAnimZoneOut(o, r));
        invalidate();
    }

    // ── Motion-range window carrots (SPEC_TEXT_DRAWER follow-up, 2026-08-08) ────────────────
    //
    // The "Start motion here"/"End motion here" buttons place a [startMs, endMs) WINDOW at the
    // playhead; these two carrots fine-tune it on the tape. They ride the SAME selected animated
    // text box as the amber in/out zone carets — the purple window is OUTSIDE them (it is where
    // the whole entrance+exit tape runs), so both can coexist on one block without claiming the
    // same pixels: the amber carets travel inside the item's span, the purple window bounds it.

    /** The selected text overlay that owns a motion-range window, else null. */
    @Nullable
    private com.fadcam.ui.faditor.model.TextOverlayItem selectedMotionRangeItem() {
        if (layerGestureController == null) return null;
        String id = layerGestureController.getSelectedItemId();
        if (id == null) return null;
        for (com.fadcam.ui.faditor.layers.Track t : layerTracks) {
            for (com.fadcam.ui.faditor.layers.TimedItem item : t.getItems()) {
                if (!id.equals(item.getId())) continue;
                com.fadcam.ui.faditor.model.TextOverlayItem o = item.getTextOverlay();
                if (o == null) return null;
                if (!o.hasMotionRange()) return null;
                return com.fadcam.ui.faditor.transcript.CaptionAnimator
                        .parsePreset(o.getTextAnimPreset())
                        == com.fadcam.ui.faditor.transcript.CaptionAnimator.Preset.NONE
                        ? null : o;
            }
        }
        return null;
    }

    /** The selected item's tape in content-x / screen-y (same space as the amber carets). */
    @Nullable
    private RectF motionRangeRect() {
        if (layerRowRenderer == null || layerGestureController == null) return null;
        String id = layerGestureController.getSelectedItemId();
        if (id == null) return null;
        return layerRowRenderer.itemBodyRect(id, getM6RowsTopPx(), totalEffectiveMs, this::timeToX);
    }

    /** Content-x for a timeline ms, clamped to the item's tape so a carrot never leaves it. */
    private float motionRangeX(@NonNull com.fadcam.ui.faditor.model.TextOverlayItem o,
                               @NonNull RectF r, long ms) {
        return Math.max(r.left, Math.min(r.right, timeToX(ms)));
    }

    /**
     * Hit-test the two window carrots — tight, and checked BEFORE the item's own trim handles,
     * exactly like the amber carets (a carrot at the tape's very edge must still be grabbable).
     */
    private Drag hitTestMotionRangeHandle(float x, float y) {
        com.fadcam.ui.faditor.model.TextOverlayItem o = selectedMotionRangeItem();
        if (o == null) return Drag.NONE;
        RectF r = motionRangeRect();
        if (r == null || r.right - r.left < textAnimInsetPx() * 2f) return Drag.NONE;
        if (y < r.top || y > r.bottom) return Drag.NONE;
        float zone = textAnimInsetPx() * 0.9f;
        float sx = motionRangeX(o, r, o.getMotionStartMs());
        float ex = motionRangeX(o, r, o.getMotionEndMs());
        if (Math.abs(x - sx) <= zone) return Drag.MOTION_RANGE_START_HANDLE;
        if (Math.abs(x - ex) <= zone) return Drag.MOTION_RANGE_END_HANDLE;
        return Drag.NONE;
    }

    /** Live carrot drag — sets the window, previews, records nothing (one undo on release). */
    private void doMotionRangeDrag(float x) {
        com.fadcam.ui.faditor.model.TextOverlayItem o = selectedMotionRangeItem();
        if (o == null || listener == null) return;
        RectF r = motionRangeRect();
        if (r == null) return;
        long minMs = Math.max(0L, xToTime(r.left));
        long maxMs = Math.max(minMs + 1L, xToTime(r.right));
        long minGap = Math.max(16L, Math.round((maxMs - minMs) * 0.01f));
        if (activeDrag == Drag.MOTION_RANGE_START_HANDLE) {
            long start = Math.max(minMs, Math.min(xToTime(x), o.getMotionEndMs() - minGap));
            o.setMotionRange(start, o.getMotionEndMs());
        } else {
            long end = Math.min(maxMs, Math.max(xToTime(x), o.getMotionStartMs() + minGap));
            o.setMotionRange(o.getMotionStartMs(), end);
        }
        listener.onMotionRangePreviewed(o.getId(), o.getMotionStartMs(), o.getMotionEndMs());
        invalidate();
    }

    /** Commit the carrot drag — ONE undo step spanning the whole gesture. */
    private void finishMotionRangeDrag() {
        com.fadcam.ui.faditor.model.TextOverlayItem o = selectedMotionRangeItem();
        if (o == null || listener == null) return;
        listener.onMotionRangeChanged(o.getId(), motionRangeBeforeStartMs,
                motionRangeBeforeEndMs, o.getMotionStartMs(), o.getMotionEndMs());
        invalidate();
    }

    /**
     * The window tint + two purple carrots at the motion range's start/end. Drawn only when a
     * range is SET (the buttons are the only way to create one), so an untouched box shows
     * nothing new here.
     */
    private void drawMotionRangeHandles(Canvas canvas) {
        com.fadcam.ui.faditor.model.TextOverlayItem o = selectedMotionRangeItem();
        if (o == null) return;
        RectF r = motionRangeRect();
        if (r == null) return;
        float[] span = layerRowRenderer.rowContentXRange();
        if (span == null) return;
        canvas.save();
        canvas.clipRect(span[0], r.top, span[1], r.bottom);
        try {
            float sx = motionRangeX(o, r, o.getMotionStartMs());
            float ex = motionRangeX(o, r, o.getMotionEndMs());
            if (ex - sx > 1f) {
                canvas.drawRect(sx, r.top, ex, r.bottom, motionRangeZonePaint);
            }
            drawMotionRangeMarker(canvas, sx, r, true);
            drawMotionRangeMarker(canvas, ex, r, false);
        } finally {
            canvas.restore();
        }
    }

    private void drawMotionRangeMarker(Canvas canvas, float x, RectF r, boolean leftEdge) {
        float cy = r.centerY();
        float h = Math.min(r.height() * 0.7f, handleNotchHeightPx * 1.2f);
        float w = textAnimInsetPx() * 0.8f;
        android.graphics.Path p = new android.graphics.Path();
        if (leftEdge) {
            p.moveTo(x + w / 2f, cy - h / 2f);
            p.lineTo(x + w / 2f, cy + h / 2f);
            p.lineTo(x - w / 2f, cy);
        } else {
            p.moveTo(x - w / 2f, cy - h / 2f);
            p.lineTo(x - w / 2f, cy + h / 2f);
            p.lineTo(x + w / 2f, cy);
        }
        p.close();
        canvas.drawPath(p, motionRangeMarkerPaint);
        canvas.drawPath(p, animMarkerOutlinePaint);
    }

    /**
     * The in/out carets plus a tint over the two zones — the user's "dragging them inward darkens
     * those regions to show the in/out zones". A ▶ where the entrance runs, a ◀ where the exit
     * does; carets resting at the ends mean zero-length zones, which IS the off state and is why
     * there is no separate enable switch.
     */
    private void drawTextAnimHandles(Canvas canvas) {
        com.fadcam.ui.faditor.model.TextOverlayItem o = selectedTextAnimItem();
        if (o == null) return;
        RectF r = textAnimRect();
        // Not merely a guard: drawing on a tape too narrow to aim at would advertise a control the
        // hit-test now (correctly) refuses to give, which is worse than showing none.
        if (r == null || !textAnimUsable(r)) return;
        // An item's tape can start left of the viewport or run past its right edge — itemBodyRect
        // reports the TRUE tape, because the caret maths needs both real ends. The rows clip their
        // item bodies to the row's content span; without the same clip here the zone tint and a
        // caret would paint over the PINNED row headers, which is where the lane caret/mute icons
        // live. Same span, taken from the renderer rather than reconstructed.
        float[] span = layerRowRenderer.rowContentXRange();
        if (span == null) return;
        canvas.save();
        canvas.clipRect(span[0], r.top, span[1], r.bottom);
        try {
            drawTextAnimHandlesClipped(canvas, r, o);
        } finally {
            canvas.restore();
        }
    }

    private void drawTextAnimHandlesClipped(Canvas canvas, @NonNull RectF r,
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem o) {
        float inset = textAnimInsetPx();
        float lx = activeDrag == Drag.TEXT_ANIM_IN_HANDLE ? textAnimDragX
                : com.fadcam.ui.faditor.transcript.CaptionAnimator.caretInX(
                        r.left, r.right, inset, o.getTextAnimInPct());
        float rx = activeDrag == Drag.TEXT_ANIM_OUT_HANDLE ? textAnimDragX
                : com.fadcam.ui.faditor.transcript.CaptionAnimator.caretOutX(
                        r.left, r.right, inset, o.getTextAnimOutPct());
        if (lx > r.left + inset + 1f) {
            canvas.drawRect(r.left + inset, r.top, lx, r.bottom, textAnimZonePaint);
        }
        if (rx < r.right - inset - 1f) {
            canvas.drawRect(rx, r.top, r.right - inset, r.bottom, textAnimZonePaint);
        }
        drawTextAnimMarker(canvas, lx, r, true);
        drawTextAnimMarker(canvas, rx, r, false);
    }

    private void drawTextAnimMarker(Canvas canvas, float x, RectF r, boolean pointsRight) {
        float cy = r.centerY();
        // Sized against the ITEM's body, not the master clip's notch: a layer row is a fraction of
        // the master track's height, and a marker scaled for the big tape would overflow it.
        float h = Math.min(r.height() * 0.7f, handleNotchHeightPx * 1.2f);
        float w = textAnimInsetPx() * 0.8f;
        android.graphics.Path p = new android.graphics.Path();
        if (pointsRight) {
            p.moveTo(x - w / 2f, cy - h / 2f);
            p.lineTo(x - w / 2f, cy + h / 2f);
            p.lineTo(x + w / 2f, cy);
        } else {
            p.moveTo(x + w / 2f, cy - h / 2f);
            p.lineTo(x + w / 2f, cy + h / 2f);
            p.lineTo(x - w / 2f, cy);
        }
        p.close();
        canvas.drawPath(p, textAnimMarkerPaint);
        canvas.drawPath(p, animMarkerOutlinePaint);
    }

    /**
     * Compute trim fractions from the pixel drag without updating segment data.
     * Segment rect stays unchanged during drag — visual feedback is via a dim overlay
     * and the trim handle drawn at the finger position (see drawTrimHandles).
     * Segment data is applied only on ACTION_UP via onTrimFinished.
     */
    private void doTrimDrag(float x) {
        if (selectedIndex < 0 || selectedIndex >= segments.size()) return;
        SegmentData sd = segments.get(selectedIndex);
        if (sd.sourceDurationMs <= 0) return;

        boolean isLeft = (activeDrag == Drag.LEFT_HANDLE);
        boolean hasLoop = sd.clip.hasLoopExtension();

        // px per effective ms at current scale
        float pxPerEffMs = dpPerSecondPx / 1000f;
        // Convert to source ms: source ms = effective ms * speed
        float pxPerSrcMs = pxPerEffMs / sd.speed;

        float minGapFrac = 500f / sd.sourceDurationMs;

        if (isLeft) {
            float endFrac = (float) dragStartOutMs / sd.sourceDurationMs;
            float deltaX = x - dragStartSegLeft;
            float deltaSrcMs = deltaX / pxPerSrcMs;
            long newInMs = dragStartInMs + (long) deltaSrcMs;
            // In loop mode allow extending past source start
            if (hasLoop) {
                // Clamp so we don't go past outPoint-500, but allow negative (loop before)
                newInMs = Math.min(dragStartOutMs - 500, newInMs);
                long loopBeforeDelta = -newInMs; // positive when newInMs < 0
                if (loopBeforeDelta > 0) {
                    trimDragLoopBefore = loopBeforeDelta;
                    newInMs = 0;
                    loopChangedDuringDrag = true;
                    loopReadoutActive = true; // L3: live readout bubble
                } else {
                    trimDragLoopBefore = 0;
                }
            } else {
                newInMs = Math.max(0, Math.min(dragStartOutMs - 500, newInMs));
            }
            trimDragStartFrac = (float) newInMs / sd.sourceDurationMs;
            trimDragStartFrac = Math.max(0f, Math.min(trimDragStartFrac, endFrac - minGapFrac));
            trimDragEndFrac = endFrac;
        } else {
            float startFrac = (float) dragStartInMs / sd.sourceDurationMs;
            float deltaX = x - dragStartSegRight;
            float deltaSrcMs = deltaX / pxPerSrcMs;
            long newOutMs = dragStartOutMs + (long) deltaSrcMs;
            // In loop mode allow extending past source end
            if (hasLoop) {
                newOutMs = Math.max(dragStartInMs + 500, newOutMs);
                if (newOutMs > sd.sourceDurationMs) {
                    trimDragLoopAfter = newOutMs - sd.sourceDurationMs;
                    newOutMs = sd.sourceDurationMs;
                    loopChangedDuringDrag = true;
                    loopReadoutActive = true; // L3: live readout bubble
                } else {
                    trimDragLoopAfter = 0;
                }
            } else {
                newOutMs = Math.max(dragStartInMs + 500,
                        Math.min(sd.sourceDurationMs, newOutMs));
            }
            trimDragEndFrac = (float) newOutMs / sd.sourceDurationMs;
            trimDragEndFrac = Math.max(startFrac + minGapFrac, Math.min(1f, trimDragEndFrac));
            trimDragStartFrac = startFrac;
        }

        // Allow handle to extend beyond segment edges for trim recovery.
        float pxPerSrcMs2 = (dpPerSecondPx / 1000f) / sd.speed;
        float minTrimX = dragStartSegLeft - (dragStartInMs * pxPerSrcMs2);
        float maxTrimX = dragStartSegRight + ((sd.sourceDurationMs - dragStartOutMs) * pxPerSrcMs2);
        // In loop mode, allow extending further past boundaries
        if (hasLoop) {
            minTrimX -= 5000 * pxPerSrcMs2; // allow ~5s of loop extension
            maxTrimX += 5000 * pxPerSrcMs2;
        }
        trimDragX = Math.max(minTrimX, Math.min(x, maxTrimX));

        // Apply loop changes directly to clip for visual feedback (like audio trim)
        if (loopChangedDuringDrag) {
            sd.clip.setLoopBeforeMs(trimDragLoopBefore);
            sd.clip.setLoopAfterMs(trimDragLoopAfter);
            // Rebuild segment data to reflect new visual duration
            segments.set(selectedIndex, new SegmentData(selectedIndex, sd.clip));
            computeRects();
        }

        // Segment data and rects are NOT updated — visual feedback comes from
        // the trim overlay drawn in drawTrimHandles. Data is committed in onUp.
        invalidate();

        // The trim edge is now previewed frame-accurately in the MAIN video
        // (the activity seeks the player from onTrimChanged), so the old floating
        // thumbnail bubble — which the finger covered — is no longer shown here.

        // Notify listener for time label updates
        if (listener != null) {
            listener.onTrimChanged(selectedIndex, trimDragStartFrac, trimDragEndFrac, isLeft);
        }
    }

    /**
     * Request (debounced) extraction of the EXACT frame at {@code sourceMs} of the
     * dragged clip, to be shown in the trim-edge preview bubble. Safe to call on
     * every drag move — actual decoding is debounced and serialized off the main
     * thread, so it never blocks input.
     */
    private void requestTrimEdgePreview(@Nullable Uri sourceUri, long sourceMs, boolean isLeft) {
        if (sourceUri == null) return;
        trimEdgePreviewActive = true;
        trimEdgePreviewIsLeft = isLeft;
        trimEdgePreviewPendingMs = sourceMs;
        trimEdgePreviewShownMs = sourceMs;
        mainHandler.removeCallbacks(trimPreviewExtractRunnable);
        mainHandler.postDelayed(trimPreviewExtractRunnable, TRIM_PREVIEW_DEBOUNCE_MS);
        invalidate();
    }

    private final Runnable trimPreviewExtractRunnable = new Runnable() {
        @Override
        public void run() {
            if (!trimEdgePreviewActive) return;
            if (selectedIndex < 0 || selectedIndex >= segments.size()) return;
            final long ms = trimEdgePreviewPendingMs;
            if (ms < 0 || ms == trimEdgePreviewBitmapMs) return; // already shown
            final Uri uri = segments.get(selectedIndex).sourceUri;
            if (uri == null) return;
            final int sizePx = Math.round(trackHeightPx * 2.2f);
            trimPreviewExecutor.execute(() -> {
                Bitmap bmp = extractExactFrame(uri, ms, sizePx);
                if (bmp == null) return;
                mainHandler.post(() -> {
                    if (!trimEdgePreviewActive) { bmp.recycle(); return; }
                    if (trimEdgePreviewBitmap != null && !trimEdgePreviewBitmap.isRecycled()) {
                        trimEdgePreviewBitmap.recycle();
                    }
                    trimEdgePreviewBitmap = bmp;
                    trimEdgePreviewBitmapMs = ms;
                    invalidate();
                });
            });
        }
    };

    /**
     * Decode the exact frame at {@code sourceMs} (frame-accurate, not keyframe-
     * snapped), scaled to fit within {@code maxPx} preserving aspect ratio. Runs
     * on {@link #trimPreviewExecutor} only, so the cached retriever is single-
     * threaded. Returns null on failure.
     */
    @Nullable
    private Bitmap extractExactFrame(@NonNull Uri uri, long sourceMs, int maxPx) {
        try {
            if (trimPreviewRetriever == null || trimPreviewRetrieverUriHash != uri.hashCode()) {
                if (trimPreviewRetriever != null) {
                    try { trimPreviewRetriever.release(); } catch (Exception ignored) {}
                }
                trimPreviewRetriever = new MediaMetadataRetriever();
                trimPreviewRetriever.setDataSource(getContext(), uri);
                trimPreviewRetrieverUriHash = uri.hashCode();
            }
            Bitmap frame = trimPreviewRetriever.getFrameAtTime(
                    sourceMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null) {
                frame = trimPreviewRetriever.getFrameAtTime(
                        sourceMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) return null;
            int w = frame.getWidth(), h = frame.getHeight();
            if (w <= 0 || h <= 0) { frame.recycle(); return null; }
            float scale = Math.min(maxPx / (float) w, maxPx / (float) h);
            int dw = Math.max(1, Math.round(w * scale));
            int dh = Math.max(1, Math.round(h * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(frame, dw, dh, true);
            if (scaled != frame) frame.recycle();
            return scaled;
        } catch (Exception e) {
            FLog.w(TAG, "Trim edge frame extraction failed", e);
            return null;
        }
    }

    /** Stop the trim-edge preview and free its bitmap. Called on trim release. */
    private void clearTrimEdgePreview() {
        trimEdgePreviewActive = false;
        trimEdgePreviewBitmapMs = -1;
        trimEdgePreviewPendingMs = -1;
        mainHandler.removeCallbacks(trimPreviewExtractRunnable);
        if (trimEdgePreviewBitmap != null && !trimEdgePreviewBitmap.isRecycled()) {
            trimEdgePreviewBitmap.recycle();
        }
        trimEdgePreviewBitmap = null;
        invalidate();
    }

    /** Same-lane test for AUDIO clips (null layerId groups as "audio", mirroring Timeline#getAudioTracks). */
    private boolean sameAudioLane(AudioClip a, AudioClip b) {
        String ka = a.getLayerId() == null ? "audio" : a.getLayerId();
        String kb = b.getLayerId() == null ? "audio" : b.getLayerId();
        return ka.equals(kb);
    }

    /**
     * PHASE-R R2: highest legal timeline END for {@code ac} (extent grows rightward from
     * its fixed {@code offsetMs} during a trim) — may not cross into a same-lane sibling.
     * Never forces below {@code currentEnd} (pre-existing overlaps must not un-trim,
     * mirroring the f646bb9 text-lane pattern).
     */
    private long audioSiblingCeil(AudioClip ac, long proposedEnd, long currentEnd) {
        long ceil = proposedEnd;
        long ourStart = ac.getOffsetMs();
        for (AudioClip sib : audioClips) {
            if (sib == ac || !sameAudioLane(ac, sib)) continue;
            long ss = sib.getOffsetMs();
            long se = ss + Math.max(0, sib.getTrimmedDurationMs());
            if (se > ourStart && ss < ceil) ceil = Math.min(ceil, ss);
        }
        return Math.max(ceil, currentEnd);
    }

    private void doTransitionTrimDrag(float x) {
        if (transitionDragIndex < 0 || transitionDragIndex >= transitions.size()) return;
        Transition t = transitions.get(transitionDragIndex);
        long newDuration = transitionDurationFromDragX(x);
        if (listener != null) {
            listener.onTransitionDurationChanged(transitionDragIndex, newDuration);
        }
        invalidate();
    }

    private long transitionDurationFromDragX(float x) {
        if (transitionDragIndex < 0 || transitionDragIndex >= transitions.size()) {
            return transitionDragStartDurationMs;
        }
        Transition t = transitions.get(transitionDragIndex);
        float pxPerMs = dpPerSecondPx / 1000f;
        // Directional: dragging the handle OUTWARD (away from the seam) grows the transition, INWARD
        // shrinks it. The old abs() meant any drag grew it and you could never shrink — felt like a
        // snap-back once it hit the 2s cap.
        float signedDelta = (activeDrag == Drag.TRANSITION_LEFT_HANDLE)
                ? (transitionDragStartX - x)
                : (x - transitionDragStartX);
        long halfDuration = (long) (signedDelta / pxPerMs);
        long newDuration = transitionDragStartDurationMs + halfDuration * 2L;
        int seam = Math.max(1, Math.min(t.clipIndex, segments.size() - 1));
        long leftDur = seam > 0 ? segments.get(seam - 1).effectiveMs : 0;
        long rightDur = seam < segments.size() ? segments.get(seam).effectiveMs : segments.get(seam - 1).effectiveMs;
        long maxSpan = Math.max(50, Math.min(leftDur, rightDur));
        long modelClamped = Math.max(50, Math.min(10_000, newDuration));
        lastTransitionDragMaxSpanMs = maxSpan;
        lastTransitionDragWasSpanClamped = modelClamped > maxSpan;
        return Math.min(maxSpan, modelClamped);
    }

    /** Whether the most recent {@link #transitionDurationFromDragX} call was limited by the
     *  neighbor-clip seam (maxSpan), not the 50ms-10s model clamp. Read this right after
     *  onTransitionDurationFinished fires to decide whether to toast the user. */
    public boolean wasLastTransitionDragSpanClamped() {
        return lastTransitionDragWasSpanClamped;
    }

    /** The neighbor-clip seam limit (ms) that applied to the most recent transition drag. */
    public long getLastTransitionDragMaxSpanMs() {
        return lastTransitionDragMaxSpanMs;
    }

    private Drag hitTestTransitionHandle(int transitionIndex, float x, float y) {
        if (transitionIndex < 0 || transitionIndex >= transitions.size()) return Drag.NONE;
        RectF rect = getTransitionRect(transitionIndex, 18f * density);
        if (rect == null) return Drag.NONE;
        if (y < rect.top - touchSlopPx || y > rect.bottom + touchSlopPx) return Drag.NONE;
        if (x >= rect.left - touchSlopPx && x <= rect.left + handleWidthPx + touchSlopPx) {
            return Drag.TRANSITION_LEFT_HANDLE;
        }
        if (x >= rect.right - handleWidthPx - touchSlopPx && x <= rect.right + touchSlopPx) {
            return Drag.TRANSITION_RIGHT_HANDLE;
        }
        return Drag.NONE;
    }
    
    /**
     * Vertical auto-scroll delta (px) for a held-item MOVE whose finger dwells near the top or bottom
     * of the capped M6 row band, so hidden lanes (and the new-layer zone pinned at the band bottom)
     * become reachable mid-drag. Returns 0 when the finger is in the neutral middle, or when nothing is
     * hidden (content fits the viewport). Scrolls the {@code layerRowRenderer}, not the timeline.
     * (JoyRaptor 2026-07-07 hand-test: "I can't hold it up top or below to have it scroll automatically so
     * I can reach hidden rows.")
     */
    private float m6MoveDragVerticalScrollDelta(float fy) {
        if (layerRowRenderer == null) return 0f;
        float viewportH = layerRowRenderer.getViewportHeightPx();
        if (viewportH <= 0f) return 0f;
        if (layerRowRenderer.getContentHeightPx() <= viewportH + 1f) return 0f; // nothing hidden
        float bandTop = getM6RowsTopPx();
        float bandBot = bandTop + viewportH;
        float vZone = Math.min(edgeScrollZonePx, viewportH * 0.35f);
        float speed = edgeScrollMaxSpeedPx * 0.6f; // rows are short — a gentler pace reads better
        if (fy < bandTop + vZone) {
            float depth = Math.max(0f, Math.min(1f, (bandTop + vZone - fy) / vZone));
            return -speed * depth;
        }
        if (fy > bandBot - vZone) {
            float depth = Math.max(0f, Math.min(1f, (fy - (bandBot - vZone)) / vZone));
            return speed * depth;
        }
        return 0f;
    }

    /**
     * Start or stop edge auto-scrolling based on finger position.
     * Called on every MOVE event during a trim drag.
     */
    private void startOrStopEdgeScroll(float screenX) {
        int viewWidth = getWidth();
        boolean nearEdge = screenX < edgeScrollZonePx || screenX > viewWidth - edgeScrollZonePx;
        if (nearEdge && !isEdgeScrolling) {
            isEdgeScrolling = true;
            edgeScrollHandler.post(edgeScrollRunnable);
        } else if (!nearEdge && isEdgeScrolling) {
            stopEdgeScroll();
        }
    }

    private void stopEdgeScroll() {
        isEdgeScrolling = false;
        edgeScrollHandler.removeCallbacks(edgeScrollRunnable);
    }

    /**
     * If the tap (screen X) lands on a silence candidate OR an already-cut span,
     * fire the listener to toggle it and return true.
     */
    private boolean tryTapSilenceCandidate(int segIndex, float screenDownX) {
        if (segIndex < 0 || segIndex >= segments.size() || segIndex >= segRects.size()) {
            return false;
        }
        SegmentData sd = segments.get(segIndex);
        RectF rr = segRects.get(segIndex);
        if (rr.width() <= 0) return false;
        float contentX = screenDownX + scrollOffsetPx;
        float frac = (contentX - rr.left) / rr.width();
        long sourceMs = sd.inPointMs + (long) (frac * sd.trimmedMs);

        // Check yellow silence candidates first
        if (sd.silenceCandidates != null) {
            for (long[] cand : sd.silenceCandidates) {
                if (sourceMs >= cand[0] && sourceMs <= cand[1]) {
                    if (listener != null) {
                        listener.onSilenceCandidateTapped(segIndex, cand[0], cand[1]);
                    }
                    return true;
                }
            }
        }

        // Also check already-cut spans (black regions) so the user can
        // tap to undo a cut.
        if (sd.removedSpans != null) {
            for (long[] span : sd.removedSpans) {
                if (sourceMs >= span[0] && sourceMs <= span[1]) {
                    if (listener != null) {
                        listener.onSilenceCandidateTapped(segIndex, span[0], span[1]);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private int hitTestTransition(float x, float y) {
        if (transitions.isEmpty() || segments.isEmpty()) return -1;
        if (y < masterTopPx() - touchSlopPx || y > masterBotPx() + touchSlopPx) return -1;
        float bandW = 6f * density;
        for (int i = transitions.size() - 1; i >= 0; i--) {
            RectF rect = getTransitionRect(i, bandW);
            if (rect != null && x >= rect.left - touchSlopPx && x <= rect.right + touchSlopPx) {
                return i;
            }
        }
        return -1;
    }

    private int hitTestSegment(float x, float y) {
        if (y < masterTopPx() - touchSlopPx / 2 || y > masterBotPx() + touchSlopPx / 2) {
            return -1;
        }
        for (int i = 0; i < segRects.size(); i++) {
            RectF r = segRects.get(i);
            if (x >= r.left - segmentGapPx && x <= r.right + segmentGapPx) return i;
        }
        return -1;
    }

    /** Maps tap/drag x to playhead position. x should be in timeline coordinates. */
    /**
     * Update playhead position from an X coordinate.
     * Allows free movement across the entire timeline, including gaps between clips.
     * 
     * CRITICAL: Only changes segments when X visually crosses into a different clip's rectangle.
     * This prevents the "snapping" behavior users experience when dragging near split points.
     */
    private void updatePlayheadFromX(float x) {
        float playheadX = x;
        if (VLOG) FLog.d(TAG, "updatePlayheadFromX: x=" + x);
        
        long timelineEndMs = getTimelineEndMs();
        long newPlayheadMs = xToTime(playheadX);
        newPlayheadMs = Math.max(0, Math.min(newPlayheadMs, timelineEndMs));
        playheadPositionMs = newPlayheadMs;
        
        if (VLOG) FLog.d(TAG, "updatePlayheadFromX: playheadPositionMs=" + playheadPositionMs + "ms");
        
        if (listener != null && !segments.isEmpty()) {
            // Find which segment's RECTANGLE this X falls into (visually, not time-based)
            // This is the key: we use rect-based detection, not time-based
            int targetSegment = -1;
            
            for (int i = 0; i < segRects.size(); i++) {
                RectF rect = segRects.get(i);
                if (playheadX >= rect.left && playheadX <= rect.right) {
                    targetSegment = i;
                    break;
                }
            }
            
            // If X is in a gap between rectangles, snap to the nearest rectangle edge
            // but DON'T trigger a segment change yet
            if (targetSegment < 0 && !segRects.isEmpty()) {
                float minDist = Float.MAX_VALUE;
                for (int i = 0; i < segRects.size(); i++) {
                    RectF rect = segRects.get(i);
                    float dist;
                    if (playheadX < rect.left) {
                        dist = rect.left - playheadX;
                    } else {
                        dist = playheadX - rect.right;
                    }
                    if (dist < minDist) {
                        minDist = dist;
                        targetSegment = i;
                    }
                }
            }
            
            // Bounds check
            if (targetSegment < 0) targetSegment = 0;
            if (targetSegment >= segments.size()) targetSegment = segments.size() - 1;
            
            // NOW compute position within the target segment using ABSOLUTE time
            SegmentData sd = segments.get(targetSegment);
            long cumulMs = 0;
            for (int i = 0; i < targetSegment; i++) {
                cumulMs += segments.get(i).effectiveMs;
            }
            
            long posInSegmentMs = newPlayheadMs - cumulMs;
            posInSegmentMs = Math.max(0, Math.min(posInSegmentMs, sd.effectiveMs));
            
            long sourceMs = sd.inPointMs + (long)(posInSegmentMs * sd.speed);
            float sourceFrac = sd.sourceDurationMs > 0 ? (float)sourceMs / sd.sourceDurationMs : 0f;
            sourceFrac = Math.max(0f, Math.min(sourceFrac, 1f));
            
            if (VLOG) FLog.d(TAG, "updatePlayheadFromX: targetSegment=" + targetSegment
                    + " posInSegmentMs=" + posInSegmentMs + " sourceFrac=" + sourceFrac);
            
            // Pass isDragging=true to prevent loading new clips during active drag
            // The FaditorEditorActivity will only seek within current clip, then load new one on drag end
            listener.onPlayheadSeeked(targetSegment, sourceFrac, true);
        }

        // SCROLL DIRECTLY TO THE X THE FINGER CHOSE — do NOT re-derive it via
        // centerPlayhead()'s timeToX(xToTime(x)) round trip. The two maps are not exact
        // inverses around segment boundaries/extrapolation, and the ~10px per-event
        // residue was INTEGRATED into a constant one-directional crawl (the "smooth
        // ratchet" rewind bias: drift continued while the finger slowed, always the
        // same way, regardless of stroke direction). With the direct assignment the
        // next event starts from exactly the scroll offset this event produced, so
        // finger deltas accumulate with zero error. (Diagnosed from a VLOG trace
        // 2026-08-30: playheadX fell ~10px/event while the finger was stationary.)
        float scrubCenterX = getWidth() / 2f;
        scrollOffsetPx = playheadX - scrubCenterX;
        clampScroll();
        invalidate();
    }

    private int findDrop(float x) {
        for (int i = 0; i < segRects.size(); i++) {
            if (x <= segRects.get(i).centerX()) return i;
        }
        return segRects.size() - 1;
    }

    public void startAssetDrag() {
        assetDragActive = true;
        assetDragOverTimeline = false;
        assetDragInsertIndex = -1;
        invalidate();
    }

    public int updateAssetDrag(float screenX, float screenY) {
        assetDragScreenX = screenX;
        assetDragScreenY = screenY;
        if (screenY <= minimapHeightPx) {
            float delta = screenX - getWidth() / 2f;
            scrollOffsetPx += delta * 0.05f;
            clampScroll();
            assetDragOverTimeline = false;
            assetDragInsertIndex = -1;
            invalidate();
            return -1;
        }
        assetDragOverTimeline = isAssetDropTarget(screenX, screenY);
        if (assetDragOverTimeline) {
            assetDragInsertIndex = getInsertIndexAtX(screenX);
        } else {
            assetDragInsertIndex = -1;
        }
        invalidate();
        return assetDragInsertIndex;
    }

    public void endAssetDrag(boolean commit) {
        stopEdgeScroll();
        assetDragActive = false;
        assetDragOverTimeline = false;
        assetDragInsertIndex = -1;
        invalidate();
    }

    public int getInsertIndexAtX(float screenX) {
        if (segments.isEmpty()) return 0;
        float x = screenX + scrollOffsetPx;
        for (int i = 0; i < segRects.size(); i++) {
            if (x <= segRects.get(i).centerX()) return i;
        }
        return segRects.size();
    }

    // ── Transition drag: snap to the nearest SEAM (a transition lives BETWEEN two clips) ──────────

    private int transitionDragSeam = -1;
    private boolean transitionDragActive = false;

    /** Seam (left-clip index, 0..clipCount-2) whose boundary is nearest the screen X. -1 if &lt;2 clips. */
    public int getNearestSeamAtX(float screenX) {
        if (segRects.size() < 2) return -1;
        float x = screenX + scrollOffsetPx;
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < segRects.size() - 1; i++) {
            float d = Math.abs(x - segRects.get(i).right);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    /** Highlight a seam as the live drop target during a transition drag (-1 clears it). */
    public void setTransitionDragSeam(int seam) {
        boolean active = seam >= 0;
        if (transitionDragSeam == seam && transitionDragActive == active) return;
        transitionDragSeam = seam;
        transitionDragActive = active;
        invalidate();
    }

    public void clearTransitionDrag() {
        if (!transitionDragActive && transitionDragSeam < 0) return;
        transitionDragActive = false;
        transitionDragSeam = -1;
        invalidate();
    }

    /** Scroll so the seam is centred/visible (used after a drop snaps to an off-screen seam). */
    public void scrollToSeam(int seam) {
        if (seam < 0 || seam >= segRects.size() - 1 || getWidth() <= 0) return;
        scrollOffsetPx = segRects.get(seam).right - getWidth() / 2f;
        clampScroll();
        invalidate();
    }

    private void drawTransitionDragPreview(@NonNull Canvas canvas, int viewW) {
        if (!transitionDragActive || transitionDragSeam < 0
                || transitionDragSeam >= segRects.size() - 1) return;
        float sx = segRects.get(transitionDragSeam).right - scrollOffsetPx;
        float tTop = masterTopPx();
        float tBot = masterBotPx();
        int prevColor = dragGhostPaint.getColor();
        Paint.Style prevStyle = dragGhostPaint.getStyle();
        // Buttress highlight: outline the two clips the transition will join.
        dragGhostPaint.setColor(0x5535F6BF);
        dragGhostPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(Math.max(0, segRects.get(transitionDragSeam).left - scrollOffsetPx), tTop,
                sx, tBot, dragGhostPaint);
        canvas.drawRect(sx, tTop,
                Math.min(viewW, segRects.get(transitionDragSeam + 1).right - scrollOffsetPx), tBot,
                dragGhostPaint);
        // Bright seam bar where it will land.
        float halfW = 5f * density;
        dragGhostPaint.setColor(Studio.GO);
        canvas.drawRoundRect(new RectF(sx - halfW, tTop - 8f * density, sx + halfW, tBot + 8f * density),
                halfW, halfW, dragGhostPaint);
        dragGhostPaint.setColor(prevColor);
        dragGhostPaint.setStyle(prevStyle);
    }

    private boolean isAssetDropTarget(float screenX, float screenY) {
        if (screenY < rulerHeightPx - touchSlopPx || screenY > getHeight() - minimapHeightPx) {
            return false;
        }
        return true;
    }

    private void updateAssetDragFromScreenX(float screenX) {
        assetDragScreenX = screenX;
        assetDragOverTimeline = isAssetDropTarget(assetDragScreenX, assetDragScreenY);
        if (assetDragOverTimeline) {
            assetDragInsertIndex = getInsertIndexAtX(assetDragScreenX);
        } else {
            assetDragInsertIndex = -1;
        }
        invalidate();
    }

    private void drawAssetDragPreview(@NonNull Canvas canvas, int viewW) {
        if (!assetDragActive || !assetDragOverTimeline) return;
        float x = Math.max(0, Math.min(assetDragScreenX, viewW));
        float tTop = masterTopPx();
        float audioTop = audioBandTopPx();
        float tBot = audioClips.isEmpty() ? masterBotPx() : audioTop + audioTrackHeightPx;
        dragGhostPaint.setStrokeWidth(4f * density);
        dragGhostPaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(x, tTop - 10f * density, x, tBot + 10f * density, dragGhostPaint);

        float ghostW = Math.min(180f * density, viewW * 0.22f);
        float ghostH = Math.min(56f * density, trackHeightPx);
        float gx = Math.max(0, Math.min(x - ghostW / 2f, viewW - ghostW));
        float gy = tTop + (trackHeightPx - ghostH) / 2f;
        RectF ghost = new RectF(gx, gy, gx + ghostW, gy + ghostH);
        dragGhostPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(ghost, segmentCornerPx, segmentCornerPx, dragGhostPaint);
        dragGhostPaint.setStyle(Paint.Style.STROKE);
        dragGhostPaint.setStrokeWidth(2f * density);
        canvas.drawRoundRect(ghost, segmentCornerPx, segmentCornerPx, dragGhostPaint);
        dragGhostPaint.setStyle(Paint.Style.FILL);
        dragGhostPaint.setStrokeWidth(4f * density);
    }
    
    // ── Word Sync V2 — tape word hit-test helpers (public for Activity) ───────
    public float getScrollOffsetPx() { return scrollOffsetPx; }
    public long getTimelineMsForX(float viewX) { return xToTime(viewX + scrollOffsetPx); }
    public float getViewXForTimelineMs(long ms) { return timeToX(ms) - scrollOffsetPx; }
    public float getMsPerPixelPublic() { return 1000f / Math.max(1f, dpPerSecondPx); }
    /** Expose xToTime/timeToX for Word Sync hit-testing (was private). */
    public long publicXToTime(float x) { return xToTime(x); }
    public float publicTimeToX(long ms) { return timeToX(ms); }

    /**
     * Word Sync V2 §6/§6.1 — hit-test a transcript word ON THE TAPE.
     *
     * <p>Mirrors {@link #drawSegmentTranscript} / {@link #drawAudioTranscripts} geometry
     * EXACTLY — same rects, same pxPerMs, same visInset — so a tap lands on the word the
     * user SEES. A previous attempt re-derived positions from segment start times and was
     * off by ~a dozen words whenever the tapped segment was not the transcript panel's own
     * clip; deriving from the same rect the draw pass uses makes that class of drift
     * impossible.</p>
     *
     * @return {@code {ownerIndex, wordIndex}} where {@code ownerIndex >= 0} is the master
     *         segment index and {@code ownerIndex < 0} encodes {@code -(audioIndex+1)};
     *         null when the touch is not on any word.
     */
    @Nullable
    public int[] hitTestTapeWord(float viewX, float viewY) {
        if (segments.isEmpty() || segRects.isEmpty()) return null;
        float contentX = viewX + scrollOffsetPx;
        // Master tape: words drawn along the bottom of each segment (or in its open drawer band).
        for (int i = 0; i < segments.size() && i < segRects.size(); i++) {
            SegmentData sd = segments.get(i);
            com.fadcam.ui.faditor.transcript.Transcript tr = segmentTranscripts.get(sd.clipId);
            if (tr == null || tr.words.isEmpty() || sd.trimmedMs <= 0) continue;
            RectF full = segRects.get(i);
            if (contentX < full.left || contentX > full.right) continue;
            // Mirror drawSegment's DISPLAY-ONLY inset (same formula, same inputs).
            float visInset = Math.min(segmentGapPx * 0.5f, full.width() * 0.2f);
            RectF rect = new RectF(full.left + visInset, full.top,
                    full.right - visInset, full.bottom);
            // §6 (V2 refinement): the word gesture owns ONLY the word band — the bottom
            // strip where words are drawn — NOT the whole tape. Touches anywhere else on
            // the segment keep their timeline gestures (scrub / pinch / pan).
            Float drawerFracObj = clipAudioDrawerFraction.get(sd.clipId);
            float drawerFrac = drawerFracObj != null
                    ? Math.max(0f, Math.min(1f, drawerFracObj)) : 0f;
            transcriptTextPaint.setTextSize(9f * density);
            float descent = transcriptTextPaint.getFontMetrics().descent;
            float segBaseY = rect.bottom - 1f * density - descent;
            float drawerBot = masterBotPx() + drawerFrac * CLIP_AUDIO_DRAWER_HEIGHT_DP * density;
            float drawerBaseY = drawerBot - 1f * density - descent;
            float textY = segBaseY + (drawerBaseY - segBaseY) * drawerFrac;
            if (viewY < textY - 16f * density || viewY > textY + 8f * density) continue;
            int w = hitWordInTranscript(tr, sd.inPointMs, sd.outPointMs, sd.trimmedMs,
                    rect, contentX);
            if (w >= 0) return new int[]{i, w};
        }
        // Audio band: words drawn along each audio clip's row body.
        if (layerRowRenderer != null) {
            for (int i = 0; i < audioClips.size(); i++) {
                AudioClip ac = audioClips.get(i);
                com.fadcam.ui.faditor.transcript.Transcript tr = audioTranscripts.get(i);
                if (ac == null || tr == null || tr.words.isEmpty()) continue;
                RectF body = layerRowRenderer.itemBodyRect(
                        ac.getId(), audioBandTopPx(), totalEffectiveMs, this::timeToX);
                if (body == null || body.width() <= 0f) continue;
                if (contentX < body.left || contentX > body.right) continue;
                // Same word-band restriction for the audio tape.
                transcriptTextPaint.setTextSize(9f * density);
                float aDescent = transcriptTextPaint.getFontMetrics().descent;
                float aTextY = body.bottom - 1f * density - aDescent;
                if (viewY < aTextY - 14f * density || viewY > aTextY + 8f * density) continue;
                long inMs = ac.getInPointMs();
                long outMs = ac.getOutPointMs();
                long span = outMs - inMs;
                if (span <= 0) continue;
                int w = hitWordInTranscript(tr, inMs, outMs, (int) span, body, contentX);
                if (w >= 0) return new int[]{-(i + 1), w};
            }
        }
        return null;
    }

    /** Shared word-loop for {@link #hitTestTapeWord}: X-span hit with a small slop, nearest wins. */
    private int hitWordInTranscript(@NonNull com.fadcam.ui.faditor.transcript.Transcript tr,
                                    long inMs, long outMs, long trimmedMs,
                                    @NonNull RectF rect, float contentX) {
        if (trimmedMs <= 0) return -1;
        float pxPerMs = rect.width() / (float) trimmedMs;
        float slop = 8f * density;
        float bestDist = Float.MAX_VALUE;
        int best = -1;
        for (int w = 0; w < tr.words.size(); w++) {
            com.fadcam.ui.faditor.transcript.TranscriptWord word = tr.words.get(w);
            if (word.startMs < inMs || word.endMs > outMs) continue;
            float x0 = rect.left + (word.startMs - inMs) * pxPerMs;
            float x1 = rect.left + (word.endMs - inMs) * pxPerMs;
            if (x1 <= x0) x1 = x0 + 1f;
            if (contentX >= x0 - slop && contentX <= x1 + slop) {
                float d = contentX < x0 ? x0 - contentX : (contentX > x1 ? contentX - x1 : 0f);
                if (d < bestDist) { bestDist = d; best = w; }
            }
        }
        return best;
    }

    // ── Gesture listeners ────────────────────────────────────────────
    
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float initialZoom;
        private float scaleAccumulator = 1f;
        
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            // A pinch swallows the terminal UP (isScaling early-returns, then postPinchPan does
            // too), so an armed clip kept its halo and chevron painted through the whole pinch —
            // a lie about gesture state. Cosmetic, but the affordance must never claim to be
            // armed when it is not.
            dislodgeArmed = false;
            dislodgeArmedSegIndex = -1;
            FLog.d(TAG, "ScaleListener.onScaleBegin: zoom=" + zoomLevel);
            isScaling = true;  // Set flag to block other touches
            // Cancel any pending reorder long-press — this is a pinch.
            longPressHandler.removeCallbacks(longPressRunnable);
            cancelMasterHeaderLongPress();
            // Bug A ("row scrub sticks sometimes") — while isScaling is true, onTouchEvent
            // early-returns for EVERY subsequent event including the terminal UP/CANCEL, so
            // onUp() never runs and whichever M6/M7 row-gesture flag was set (a pinch that
            // begins mid row-scrub / mid item-drag) LEAKS until it happens to be re-entered
            // by a later gesture — driving a stale scrub with a stale m6RowPendingLastX (the
            // "stick + jump"). Tear the row gesture down cleanly here, the same way we just
            // cancelled the long-presses above.
            resetRowGestureFlags("pinch(onScaleBegin)");
            initialZoom = zoomLevel;
            scaleAccumulator = 1f;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            scaleAccumulator *= detector.getScaleFactor();
            zoomLevel = initialZoom * scaleAccumulator;
            zoomLevel = Math.max(MIN_ZOOM, Math.min(zoomLevel, MAX_ZOOM));
            
            if (VLOG) FLog.d(TAG, "ScaleListener.onScale: scaleFactor=" + detector.getScaleFactor() + " zoomLevel=" + zoomLevel);
            
            updateDpPerSecond();
            computeRects();
            
            // Keep playhead centered during zoom
            centerPlayhead();
            
            invalidate();
            return true;
        }
        
        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            FLog.d(TAG, "ScaleListener.onScaleEnd");
            isScaling = false;  // Clear flag to allow other touches
            // FOLLOW-UP 2 (post-pinch dead zone): the pinch swallowed every event from
            // the gesture detector (the isScaling early-return above it), so its
            // internal state is stale — the surviving finger's MOVEs would be ignored
            // (flight-recorder: action=2 stream, activeDrag=NONE, nothing consumes)
            // until a re-touch. Hand the surviving pointer back to the pan/scrub path
            // directly, RE-ANCHORED at its own next position (NaN seed → the first MOVE
            // sets the anchor with zero delta, killing the 1151→541 active-pointer
            // jump). Pinch→pan becomes one fluid motion.
            postPinchPanActive = true;
            postPinchLastX = Float.NaN;
            // Keep the parent-intercept claim — the same finger is still mid-gesture.
        }
    }
    
    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            if (VLOG) FLog.d(TAG, "GestureListener.onDown");
            // Cancel any ongoing fling
            if (!flingScroller.isFinished()) {
                flingScroller.abortAnimation();
            }
            return false; // Let custom onDown handle it
        }
        
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (VLOG) FLog.d(TAG, "GestureListener.onScroll: distanceX=" + distanceX + " activeDrag=" + activeDrag);
            // Cancel long press — user is scrolling, not holding
            longPressHandler.removeCallbacks(longPressRunnable);
            cancelMasterHeaderLongPress();
            // Only handle scroll if not dragging handles
            if (activeDrag != Drag.NONE) {
                if (VLOG) FLog.d(TAG, "GestureListener.onScroll: ignoring, activeDrag=" + activeDrag);
                return false;
            }
            
            // Horizontal drag on timeline = seek playhead
            float centerX = getWidth() / 2f;
            float newPlayheadX = centerX + scrollOffsetPx + distanceX;
            
            if (VLOG) FLog.d(TAG, "GestureListener.onScroll: newPlayheadX=" + newPlayheadX);
            
            // Find which segment and position this corresponds to
            updatePlayheadFromX(newPlayheadX);
            
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            FLog.d(TAG, "GestureListener.onFling: velocityX=" + velocityX);
            // Only fling if not dragging handles or reordering
            if (activeDrag != Drag.NONE) {
                return false;
            }
            startPlayheadFling(velocityX);
            return true;
        }
    }

    /**
     * Start the playhead-scrub fling animation (extracted from {@link GestureListener#onFling}
     * so the row-band scrub-passthrough UP can hand off the SAME glide — user feedback
     * 2026-07-03: row scrubs stopped dead while main-timeline swipes glided).
     */
    private void startPlayheadFling(float velocityX) {
        // Mark that a fling is starting so we can signal drag finished when it ends
        flingJustFinished = true;
        // Reset the computeScroll() throttle sentinel so frame 1 of THIS fling always processes,
        // even if it happens to round to the same px as the last frame of a PREVIOUS fling.
        lastFlingScrollOffsetPx = Float.MIN_VALUE;

        // Calculate proper scroll bounds to keep playhead within timeline range
        float centerX = getWidth() / 2f;
        float minScroll = edgePaddingPx - centerX;  // When 0ms at center
        long timelineEnd = getTimelineEndMs();
        float maxScroll = timeToX(timelineEnd) - centerX;  // When timeline end at center

        // Start fling animation (negative velocity because scrolling moves playhead position)
        int startX = (int) scrollOffsetPx;
        flingScroller.fling(
            startX, 0,               // startX, startY
            (int) -velocityX, 0,     // velocityX (invert), velocityY
            (int) minScroll,         // minX (video start bound)
            (int) maxScroll,         // maxX (video end bound)
            0, 0                     // minY, maxY (no vertical scroll)
        );

        postInvalidateOnAnimation();
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Return the pooled VelocityTracker (review fix: it was obtained but never
        // recycled — one leaked pool instance per editor open/close).
        if (rowScrubVelocityTracker != null) {
            rowScrubVelocityTracker.recycle();
            rowScrubVelocityTracker = null;
        }
        // Stop a mid-flight excursion animation (its update listener invalidates this view).
        if (excursionAnimator != null) {
            excursionAnimator.removeAllListeners();
            excursionAnimator.removeAllUpdateListeners();
            excursionAnimator.cancel();
        }
        // Shut down thumbnail loader
        thumbnailExecutor.shutdownNow();
        thumbnailsLoading.clear();
        thumbnailsFailed.clear();
        // W2: stop any in-flight timeline waveform extraction with the view.
        if (timelineWaveformCache != null) {
            timelineWaveformCache.shutdown();
        }
        // AV2: stop any in-flight band-tape extraction with the view.
        if (tapeWaveformCache != null) {
            tapeWaveformCache.shutdown();
        }
        // Trim-edge preview teardown
        trimPreviewExecutor.shutdownNow();
        mainHandler.removeCallbacks(trimPreviewExtractRunnable);
        if (trimEdgePreviewBitmap != null && !trimEdgePreviewBitmap.isRecycled()) {
            trimEdgePreviewBitmap.recycle();
        }
        trimEdgePreviewBitmap = null;
        if (trimPreviewRetriever != null) {
            try { trimPreviewRetriever.release(); } catch (Exception ignored) {}
            trimPreviewRetriever = null;
        }
        // Clean up thumbnails
        for (List<Bitmap> thumbs : thumbnailsCache.values()) {
            if (thumbs != null) {
                for (Bitmap bmp : thumbs) {
                    if (bmp != null && !bmp.isRecycled()) {
                        bmp.recycle();
                    }
                }
            }
        }
        thumbnailsCache.clear();
    }
}
