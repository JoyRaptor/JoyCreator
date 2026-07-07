package com.fadcam.ui.faditor.timeline;

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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 8f;
    private static final float MIN_SEGMENT_DP = 80f;
    private static final float EDGE_PADDING_DP = 20f;

    private static final float RULER_HEIGHT_DP = 22f;
    private static final float MINIMAP_HEIGHT_DP = 16f;
    private static final float TRACK_HEIGHT_DP = 56f;
    private static final float SEGMENT_GAP_DP = 4f;
    private static final float SEGMENT_CORNER_DP = 6f;

    private static final float HANDLE_WIDTH_DP = 14f;
    private static final float HANDLE_OVERHANG_DP = 4f;
    private static final float HANDLE_NOTCH_WIDTH_DP = 3f;
    private static final float HANDLE_NOTCH_HEIGHT_DP = 18f;

    private static final float PLAYHEAD_WIDTH_DP = 2.5f;
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
    private static final int COLOR_RULER_BG      = 0xFF141414;
    private static final int COLOR_RULER_TEXT     = 0xFF777777;
    private static final int COLOR_RULER_TICK     = 0xFF444444;
    private static final int COLOR_TRACK_BG       = 0xFF101014; // deep "film black" — the main track reads as the anchor (Slice E delineation)
    /** Near-black film-border rails framing the master track's top & bottom edges. */
    private static final int COLOR_FILM_RAIL      = 0xFF050507;
    /** Sprocket perforations punched along the film rails. */
    private static final int COLOR_FILM_SPROCKET  = 0xFF2A2A32;
    private static final int COLOR_SEGMENT        = 0xFF2D2D2D;
    private static final int COLOR_SEGMENT_SEL    = 0xFF1B3A20;
    private static final int COLOR_BORDER_SEL     = 0xFF4CAF50;
    /** Selected-transition outline — blue, to distinguish from green clip selection. */
    private static final int COLOR_TRANSITION_SEL = 0xFF2196F3;
    private static final int COLOR_HANDLE         = 0xFF4CAF50;
    private static final int COLOR_HANDLE_NOTCH   = 0xBB1B5E20;
    private static final int COLOR_PLAYHEAD       = 0xFFFFFFFF;
    private static final int COLOR_LABEL          = 0xBBFFFFFF;
    private static final int COLOR_DRAG_GHOST     = 0x664CAF50;
    private static final int COLOR_AUDIO_BG       = 0xFF1E2A1E;
    private static final int COLOR_AUDIO_BG_SEL   = 0xFF1B3A20;
    private static final int COLOR_AUDIO_WAVE     = 0xFF4CAF50;
    private static final int COLOR_AUDIO_WAVE_MUTED = 0xFF555555;
    private static final int COLOR_AUDIO_WAVE_DIM = 0x404CAF50; // Faded version for mirror half
    private static final int COLOR_AUDIO_CENTERLINE = 0x334CAF50;
    private static final int COLOR_AUDIO_LABEL    = 0xBBFFFFFF;
    private static final int COLOR_AUDIO_TRACK_BG = 0xFF151515;

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
    private final Paint handleNotchPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint         = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dragGhostPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trimOverlayPaint    = new Paint();
    private final Paint trimRecoverPaint    = new Paint();

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
    private float audioTrackHeightPx, audioTrackGapPx, audioCornerPx, audioWaveBarGapPx;
    private float audioLaneGapPx;
    /** Number of stacked audio lanes (≥1) and the lane each audio clip sits in. */
    private int audioLaneCount = 1;
    private int[] audioClipLanes = new int[0];
    private float minimapHeightPx;
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

    // Overlay/caption "layer" rows shown below the tracks (read-only view of
    // each overlay's time-range + keyframes so the user can SEE them).
    private final List<TextOverlayItem> overlays = new ArrayList<>();
    // Visualizer (waveform) overlays shown as their own read-only layer rows below the text rows.
    private final List<com.fadcam.ui.faditor.model.WaveformOverlayInstance> waveformLayers = new ArrayList<>();
    // Caption spans (timeline {startMs,endMs} per captioned clip) shown as their own layer rows.
    private final List<long[]> captionSpans = new ArrayList<>();
    private final Paint layerBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint layerSelPaint = new Paint(Paint.ANTI_ALIAS_FLAG); // selected-layer highlight ring
    private final Paint layerLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint layerKeyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int activeLayerIndex = -1;
    private long layerDragStartMs;
    private long layerDragEndMs;
    // A pending tap on a layer row ({kind,value} from hitTestLayerTap), dispatched on UP if not dragged.
    private int[] pendingLayerTap;
    // Selected layer row for the highlight ring (kind 0=overlay/1=viz/2=caption; value=index/clipIndex; -1=none).
    private int selectedLayerKind = -1;
    private int selectedLayerValue = -1;
    private long layerDragInitialKeyLocalMs = -1;
    private long layerDragOriginalKeyLocalMs = -1;

    // ── M6 multi-row Track UI (extract-on-touch: all logic in LayerRowRenderer) ──
    private com.fadcam.ui.faditor.layers.LayerRowRenderer layerRowRenderer;
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
        }
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
                @NonNull com.fadcam.ui.faditor.layers.Track fromTrack) {}
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
     * opens the track-management menu (rename / move up / move down / delete). The
     * MASTER row never appears in these rows, so it is structurally excluded.
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

    /**
     * Push the current Track model in for the M6 multi-row UI (PLAN Part 7, row M6).
     * Snapshots the lists (same convention as {@link #setOverlays}/{@link #setAudioClips})
     * rather than holding a live {@code Timeline} reference. A plain single-track project
     * passes two empty lists, which {@link com.fadcam.ui.faditor.layers.LayerRowRenderer}
     * renders as zero rows — no visual change (PLAN Part 7 M6 scope item 6).
     */
    public void setLayerTracks(@NonNull List<com.fadcam.ui.faditor.layers.Track> layers,
                               @NonNull List<com.fadcam.ui.faditor.layers.Track> audioTracks) {
        layerTracks.clear();
        layerTracks.addAll(layers);
        audioLayerTracks.clear();
        audioLayerTracks.addAll(audioTracks);
        requestLayout();
        invalidate();
    }

    // ── State ────────────────────────────────────────────────────────
    private final List<SegmentData> segments = new ArrayList<>();
    private final List<RectF> segRects = new ArrayList<>();
    private int selectedIndex = -1;  // UI selection (-1 = none selected)
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
    private static final int MAX_THUMBNAILS_PER_SEGMENT = 30;
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Path clipPath = new Path();

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
    private int selectedAudioIndex = -1;
    private boolean isDraggingAudio = false;
    private int dragAudioIndex = -1;
    private float dragAudioStartX;
    private long dragAudioStartOffsetMs;
    private boolean audioLongPressTriggered = false;
    private int pendingAudioIndex = -1;       // Index of audio clip awaiting long-press
    private static final long AUDIO_LONG_PRESS_MS = 1000;  // Long hold to avoid accidental drags
    // Audio trim drag state
    private float audioTrimDragX;
    private long audioTrimDragInMs;
    private long audioTrimDragOutMs;

    // ── Touch ────────────────────────────────────────────────────────
    private boolean isScaling = false;
    /** FOLLOW-UP 2: true from onScaleEnd (a finger survived the pinch) until that
     *  finger lifts — its MOVEs drive the scrub directly, re-anchored (see onScaleEnd). */
    private boolean postPinchPanActive = false;
    /** Previous raw x of the surviving pointer; NaN = not yet re-anchored. */
    private float postPinchLastX = Float.NaN;
    
    private enum Drag {
        NONE,
        LEFT_HANDLE,
        RIGHT_HANDLE,
        AUDIO_LEFT_HANDLE,
        AUDIO_RIGHT_HANDLE,
        LAYER_LEFT_HANDLE,
        LAYER_RIGHT_HANDLE,
        LAYER_KEYFRAME,
        TRANSITION_LEFT_HANDLE,
        TRANSITION_RIGHT_HANDLE
    }
    private Drag activeDrag = Drag.NONE;
    private float downX, downY;
    private long downTime;
    private int downSegIndex = -1;
    private boolean longPressTriggered;
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
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeDrag == Drag.NONE && downSegIndex >= 0 && segments.size() > 1
                    && !isScaling) {
                longPressTriggered = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                enterReorderMode();
            }
        }
    };
    private final Runnable audioLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingAudioIndex >= 0 && pendingAudioIndex < audioClips.size()
                    && !isDraggingAudio && activeDrag == Drag.NONE) {
                audioLongPressTriggered = true;
                isDraggingAudio = true;
                dragAudioIndex = pendingAudioIndex;
                float scrolledX = downX + scrollOffsetPx;
                dragAudioStartX = scrolledX;
                dragAudioStartOffsetMs = audioClips.get(dragAudioIndex).getOffsetMs();
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
            }
        }
    };
    // Long-press a layer row → delete/remove that layer object (tap=open, long-hold=delete).
    private boolean layerLongPressFired = false;
    private final Runnable layerLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingLayerTap == null || activeDrag != Drag.NONE || listener == null) return;
            layerLongPressFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            int[] lt = pendingLayerTap;
            if (lt[0] == 0) listener.onOverlayLayerLongPressed(lt[1]);
            else if (lt[0] == 1) listener.onVisualizerLayerLongPressed(lt[1]);
            else listener.onCaptionLayerLongPressed(lt[1]);
        }
    };
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
            float fx = lastItemDragScreenX;
            int vw = getWidth();
            float delta;
            if (fx < edgeScrollZonePx) {
                delta = -edgeScrollMaxSpeedPx * (1f - fx / edgeScrollZonePx);
            } else if (fx > vw - edgeScrollZonePx) {
                delta = edgeScrollMaxSpeedPx * (1f - (vw - fx) / edgeScrollZonePx);
            } else {
                isEdgeScrolling = false;
                return;
            }
            scrollOffsetPx += delta;
            clampScroll();
            if (com.fadcam.ui.faditor.layers.LayerGestureController.ROWGESTURE_DEBUG) {
                FLog.d("ROWGESTURE", "edge-pan tick delta=" + (int) delta
                        + " fx=" + (int) fx + " scrollOffsetPx=" + (int) scrollOffsetPx);
            }
            layerGestureController.onRowBodyMove(fx + scrollOffsetPx, lastItemDragScreenY,
                    getM6RowsTopPx(), totalEffectiveMs, EditorTimelineView.this::xToTime);
            invalidate();
            edgeScrollHandler.postDelayed(this, EDGE_SCROLL_INTERVAL_MS);
            return;
        }
        if (!(activeDrag == Drag.LEFT_HANDLE || activeDrag == Drag.RIGHT_HANDLE
                || activeDrag == Drag.AUDIO_LEFT_HANDLE || activeDrag == Drag.AUDIO_RIGHT_HANDLE)
                && !assetDragActive && !isDraggingAudio) {
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
        } else if (isDraggingAudio && dragAudioIndex >= 0) {
            // Audio clip drag with edge scroll: update offset based on movement
            float scrolledX = screenX + scrollOffsetPx;
            float deltaX = scrolledX - dragAudioStartX;
            float deltaSec = deltaX / dpPerSecondPx;
            long newOffset = dragAudioStartOffsetMs + (long) (deltaSec * 1000f);
            newOffset = Math.max(0, newOffset);
            AudioClip ac = audioClips.get(dragAudioIndex);
            ac.setOffsetMs(newOffset);
            computeRects();
            invalidate();
        } else if (activeDrag == Drag.AUDIO_LEFT_HANDLE || activeDrag == Drag.AUDIO_RIGHT_HANDLE) {
            float scrolledX = screenX + scrollOffsetPx;
            doAudioTrimDrag(scrolledX);
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
     * Dwell before the FIRST pan of a drag (feedback 2026-07-03am): someone moving the
     * item slowly through the rows shouldn't have the view yanked sideways the instant
     * a bookend arms — the excursion starts only after the bookend has been held
     * ~220ms. Flips while ALREADY out on an excursion stay immediate (deliberate).
     */
    private static final long EXCURSION_DWELL_MS = 220;
    private long pendingExcursionJointMs = Long.MIN_VALUE;
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
        }
    }

    // ── Listener interface ───────────────────────────────────────────
    public interface OnSegmentActionListener {
        void onSegmentSelected(int index);
        /** A yellow silence candidate was tapped — convert it to a cut. */
        void onSilenceCandidateTapped(int segmentIndex, long startMs, long endMs);
        void onTrimChanged(int segmentIndex, float startFraction, float endFraction, boolean isLeft);
        void onTrimFinished(int segmentIndex, float startFraction, float endFraction);
        /** Called when playhead is seeked. isDragging=true means user is actively dragging,
         *  so don't load new clips yet; isDragging=false means this is a discrete seek or drag end. */
        void onPlayheadSeeked(int segmentIndex, float fractionInSegment, boolean isDragging);
        void onPlayheadDragFinished();
        void onSegmentReordered(int fromIndex, int toIndex);
        void onReorderModeChanged(boolean entering);
        /** The user tapped the "Link" button in the reorder bar — open relink for the given clip. */
        default void onReorderLinkRequested(int segmentIndex) {}
        void onAudioClipSelected(int audioIndex);
        void onAudioTrimChanged(int audioIndex, long inPointMs, long outPointMs, boolean isLeft);
        void onAudioTrimFinished(int audioIndex, long inPointMs, long outPointMs);
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
        // Tapping a read-only layer row (turns the layer view into navigation/editing).
        default void onOverlayLayerTapped(int overlayIndex) {}
        default void onVisualizerLayerTapped(int waveformIndex) {}
        default void onCaptionLayerTapped(int clipIndex) {}
        // Long-pressing a layer row → delete/remove that layer object.
        default void onOverlayLayerLongPressed(int overlayIndex) {}
        default void onVisualizerLayerLongPressed(int waveformIndex) {}
        default void onCaptionLayerLongPressed(int clipIndex) {}
        /** Loop extension trim finished — called when drag extends past source bounds. */
        default void onLoopTrimFinished(int segmentIndex, long oldBefore, long oldAfter, long newBefore, long newAfter) {}
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
        minimapHeightPx = MINIMAP_HEIGHT_DP * density;
        // Ruler band sits below the minimap strip; everything keyed off
        // rulerHeightPx shifts down together.
        rulerHeightPx = RULER_HEIGHT_DP * density + minimapHeightPx;
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
        audioTrackHeightPx = AUDIO_TRACK_HEIGHT_DP * density;
        audioTrackGapPx = AUDIO_TRACK_GAP_DP * density;
        audioLaneGapPx = AUDIO_LANE_GAP_DP * density;
        audioCornerPx = AUDIO_CORNER_DP * density;
        audioWaveBarGapPx = AUDIO_WAVEFORM_BAR_GAP_DP * density;
        layerRowRenderer = new com.fadcam.ui.faditor.layers.LayerRowRenderer(density);
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
        handleNotchPaint.setColor(COLOR_HANDLE_NOTCH);
        handleNotchPaint.setStyle(Paint.Style.FILL);
        playheadPaint.setColor(COLOR_PLAYHEAD);
        playheadPaint.setStyle(Paint.Style.FILL);
        playheadCirclePaint.setColor(COLOR_PLAYHEAD);
        playheadCirclePaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(COLOR_LABEL);
        labelPaint.setTextSize(LABEL_SIZE_DP * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        dragGhostPaint.setColor(COLOR_DRAG_GHOST);
        dragGhostPaint.setStyle(Paint.Style.FILL);
        trimOverlayPaint.setColor(0x80000000);
        trimOverlayPaint.setStyle(Paint.Style.FILL);
        trimRecoverPaint.setColor(0x404CAF50);
        trimRecoverPaint.setStyle(Paint.Style.FILL);
        transitionHelperPaint.setColor(0xCC4CAF50);
        transitionHelperPaint.setStyle(Paint.Style.STROKE);
        transitionHelperPaint.setStrokeWidth(1.5f * density);
        transitionHelperPaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{4f * density, 4f * density}, 0f));
        edgeScrollZonePx = EDGE_SCROLL_ZONE_DP * density;
        edgeScrollMaxSpeedPx = EDGE_SCROLL_MAX_SPEED_DP * density;

        // Reorder mode paints
        reorderBarHeightPx = REORDER_BAR_HEIGHT_DP * density;
        reorderBtnPaddingPx = REORDER_BTN_PADDING_DP * density;
        reorderBlockCornerPx = REORDER_BLOCK_CORNER_DP * density;

        missingBgPaint.setColor(0xCCFF4444);
        missingBgPaint.setStyle(Paint.Style.FILL);
        missingTextPaint.setColor(0xFFFFFFFF);
        missingTextPaint.setTextSize(11f * density);
        missingTextPaint.setTextAlign(Paint.Align.CENTER);
        missingTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        reorderBgPaint2.setColor(0xE0111111);
        reorderBgPaint2.setStyle(Paint.Style.FILL);
        reorderBarPaint.setColor(0xFF1A1A1A);
        reorderBarPaint.setStyle(Paint.Style.FILL);
        reorderBlockPaint2.setColor(0xFF2D2D2D);
        reorderBlockPaint2.setStyle(Paint.Style.FILL);
        reorderBlockDragPaint.setColor(0xFF3A3A3A);
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
        volEnvLinePaint.setColor(0xFF40C4FF); // light blue
        volEnvLinePaint.setStyle(Paint.Style.STROKE);
        volEnvLinePaint.setStrokeWidth(1.6f * density);
        volEnvLinePaint.setStrokeJoin(Paint.Join.ROUND);
        volEnvDotPaint.setColor(0xFF40C4FF);
        volEnvDotPaint.setStyle(Paint.Style.FILL);
        opacityEnvLinePaint.setColor(0xCCFFFFFF); // semi-transparent white
        opacityEnvLinePaint.setStyle(Paint.Style.STROKE);
        opacityEnvLinePaint.setStrokeWidth(1.6f * density);
        opacityEnvLinePaint.setStrokeJoin(Paint.Join.ROUND);
        opacityEnvDotPaint.setColor(0xCCFFFFFF);
        opacityEnvDotPaint.setStyle(Paint.Style.FILL);
        volumeEnvLinePaint.setColor(0xFF40C4FF);
        volumeEnvLinePaint.setStyle(Paint.Style.STROKE);
        volumeEnvLinePaint.setStrokeWidth(1.6f * density);
        volumeEnvLinePaint.setStrokeJoin(Paint.Join.ROUND);
        volumeEnvDotPaint.setColor(0xFF40C4FF);
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

    // ══════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════

    public void setOnSegmentActionListener(@Nullable OnSegmentActionListener l) {
        this.listener = l;
    }

    public void setTimeline(@NonNull Timeline timeline, int selected) {
        segments.clear();
        totalEffectiveMs = 0;
        Set<String> activeKeys = new HashSet<>();
        for (int i = 0; i < timeline.getClipCount(); i++) {
            SegmentData sd = new SegmentData(i, timeline.getClip(i));
            segments.add(sd);
            totalEffectiveMs += sd.effectiveMs;
            activeKeys.add(sd.cacheKey);
        }
        // Evict cache entries no longer referenced
        Set<String> toEvict = new HashSet<>(thumbnailsCache.keySet());
        toEvict.removeAll(activeKeys);
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
        selectedTransitionIndex = -1;
        if (selected >= 0) {
            lastPlaybackIndex = selected;  // Track for playback continuation
        }
        computeRects();
        
        // Center timeline on current playhead position
        if (getWidth() > 0) {
            centerPlayhead();
        }
        
        requestLayout();
        invalidate();
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
        selectedAudioIndex = -1;
        computeRects();
        requestLayout();
        invalidate();
    }

    /**
     * Returns the currently selected audio clip index, or -1 if none.
     */
    public int getSelectedAudioIndex() {
        return selectedAudioIndex;
    }

    public void setSelectedIndex(int index) {
        if (index != selectedIndex && index >= 0 && index < segments.size()) {
            selectedIndex = index;
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
    public void setPlayheadFraction(float sourceFraction) {
        // Use last valid index for playback if currently deselected
        int playbackIndex = selectedIndex >= 0 ? selectedIndex : lastPlaybackIndex;
        
        FLog.d(TAG, "setPlayheadFraction: fraction=" + sourceFraction + " selectedIndex=" + selectedIndex + " playbackIndex=" + playbackIndex);
        
        if (playbackIndex >= 0 && playbackIndex < segments.size()) {
            SegmentData sd = segments.get(playbackIndex);
            long selectedSegmentStartMs = getSegmentStartTime(playbackIndex);
            long localMs = (long)(sourceFraction * sd.sourceDurationMs);
            // Convert from source position to trimmed position
            localMs = Math.max(sd.inPointMs, Math.min(localMs, sd.outPointMs)) - sd.inPointMs;
            // Adjust for speed
            localMs = (long)(localMs / sd.speed);
            playheadPositionMs = selectedSegmentStartMs + localMs;
            
            // Remember this index for playback continuation
            lastPlaybackIndex = playbackIndex;
            
            FLog.d(TAG, "setPlayheadFraction: playheadPositionMs=" + playheadPositionMs);
            
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
    
    private void centerPlayhead() {
        float centerX = getWidth() / 2f;
        float playheadX = timeToX(playheadPositionMs);
        scrollOffsetPx = playheadX - centerX;
        FLog.d(TAG, "centerPlayhead: centerX=" + centerX + " playheadX=" + playheadX + " scrollOffset=" + scrollOffsetPx);
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

    /** Total vertical span of the (possibly multi-lane) audio track in px. */
    private float audioTrackTotalHeightPx() {
        return audioLaneCount * audioTrackHeightPx
                + (audioLaneCount - 1) * audioLaneGapPx;
    }

    private void computeRects() {
        segRects.clear();
        if (segments.isEmpty()) {
            contentWidthPx = 0;
            return;
        }
        float tTop = masterTopPx();
        float x = edgePaddingPx;

        for (int i = 0; i < segments.size(); i++) {
            SegmentData sd = segments.get(i);
            float segW = Math.max(minSegmentPx, (sd.effectiveMs / 1000f) * dpPerSecondPx);
            segRects.add(new RectF(x, tTop, x + segW, tTop + trackHeightPx));
            x += segW + segmentGapPx;
        }
        contentWidthPx = x - segmentGapPx + edgePaddingPx;

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
                float clipW = Math.max(minSegmentPx,
                        (ac.getTrimmedDurationMs() / 1000f) * dpPerSecondPx);
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

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float contentDp = MINIMAP_HEIGHT_DP + RULER_HEIGHT_DP + TRACK_HEIGHT_DP;
        if (!audioClips.isEmpty()) {
            contentDp += AUDIO_TRACK_GAP_DP
                    + audioLaneCount * AUDIO_TRACK_HEIGHT_DP
                    + (audioLaneCount - 1) * AUDIO_LANE_GAP_DP;
        }
        // Extra space for transcript text below segments
        if (!segmentTranscripts.isEmpty()) {
            contentDp += 17f;
        }
        // Captions share ONE track row (sequential clips don't overlap), like a real caption track.
        int layerRows = overlays.size() + waveformLayers.size() + (captionSpans.isEmpty() ? 0 : 1);
        if (layerRows > 0) {
            contentDp += LAYER_TOP_GAP_DP
                    + layerRows * (LAYER_ROW_HEIGHT_DP + LAYER_ROW_GAP_DP) + 6f;
        }
        // Keep the established base height when there are no extra layer rows.
        float totalDp = Math.max(BASE_TIMELINE_DP, contentDp);
        int defH = (int) (totalDp * density);
        // M6 hook: extra height for the Track-driven multi-row UI (zero for a plain
        // single-track project — see LayerRowRenderer#isEmpty).
        defH += (int) layerRowRenderer.measureExtraHeightPx(layerTracks, audioLayerTracks);
        // Slice E: the M6 layer band moved ABOVE the master track, with a divider gap
        // between the band and master (see masterTopPx). Reserve that gap here so the
        // AUDIO band at the very bottom is never clipped by the measured height.
        if (!layerTracks.isEmpty() || !audioLayerTracks.isEmpty()) {
            defH += (int) (LAYER_TOP_GAP_DP * density);
        }
        int h = resolveSize(defH, hSpec);
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, h);
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

        int w = getWidth();
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

        // Ghost trim: drawn first so neighbouring segments cover it
        if (selectedIndex >= 0 && selectedIndex < segRects.size()) {
            drawTrimGhosts(canvas, selectedIndex);
        }

        // Cull segments outside the visible viewport. onDraw runs on every frame
        // of a continuous trim/scroll drag; drawing (and lazily loading thumbnails
        // for) every clip — including off-screen ones — starved input dispatch and
        // produced "not responding" ANRs once a project had many clips. Visible
        // window in content coordinates is [scrollOffsetPx, scrollOffsetPx + w].
        final float visLeft = scrollOffsetPx;
        final float visRight = scrollOffsetPx + w;
        for (int i = 0; i < segRects.size(); i++) {
            RectF segR = segRects.get(i);
            if (segR.right < visLeft || segR.left > visRight) continue;
            // Lazily extract thumbnails for segments as they scroll into view.
            loadThumbnailsForSegment(i);
            drawSegment(canvas, i);
        }

        if (selectedIndex >= 0 && selectedIndex < segRects.size()) {
            drawTrimHandles(canvas, segRects.get(selectedIndex));
        }

        // Draw audio clips
        if (!audioClips.isEmpty()) {
            drawAudioTrack(canvas);
        }

        // Overlay/caption layer rows (time-ranges + keyframe diamonds)
        drawLayers(canvas);

        // Transitions (fade/wipe/push bands between clips)
        drawTransitions(canvas);

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
        layerRowRenderer.layout(canvas, layerTracks, audioLayerTracks, getM6RowsTopPx(), w,
                scrollOffsetPx, totalEffectiveMs, this::timeToX,
                layerGestureController != null && layerGestureController.isMoveDragActive(),
                layerGestureController != null && layerGestureController.isHoveringNewLayerZone(),
                layerGestureController != null ? layerGestureController.getSelectedItemId() : null);

        canvas.restore();

        // Master-track "filmstrip" delineation (JoyRaptor 2026-07-06): frame the main track as a strip of
        // film so it reads as the anchor band — overlays sit above it, audio below. Screen space, over
        // the thumbnails, so the frame stays put as the strip scrolls. (Master always exists here —
        // onDraw returned early above if segments were empty.)
        drawMasterFilmstrip(canvas, tTop, tBot, w);

        // Draw fixed center playhead (NOT affected by scroll)
        float playheadBot = !audioClips.isEmpty() ? audioBot : tBot;
        drawCenterPlayhead(canvas, tTop, playheadBot);

        // Minimap strip on top (screen coords, not scrolled)
        drawMinimap(canvas, w);

        if (assetDragActive) {
            drawAssetDragPreview(canvas, w);
            drawTransitionDragPreview(canvas, w);
        }

        // L3: live numeric readout while dragging a loop-extension edge (screen space,
        // on top of all — same reasoning as the trim-edge preview bubble below).
        if (loopReadoutActive && (activeDrag == Drag.LEFT_HANDLE || activeDrag == Drag.RIGHT_HANDLE)) {
            drawLoopExtensionReadout(canvas, w, tTop, tBot);
        }

        // Frame-accurate trim-edge preview bubble (screen space, on top of all).
        // (Trim-edge preview now happens in the main video, not as a finger-blocking
        // bubble here — drawTrimEdgePreview is retained but no longer activated.)
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
        trimPreviewBorderPaint.setColor(0xFF4CAF50);
        float corner = 6f * density;
        canvas.drawRoundRect(box, corner, corner, trimPreviewBgPaint);

        if (bmp != null && !bmp.isRecycled()) {
            canvas.drawBitmap(bmp, left + pad, top + pad, null);
        }
        canvas.drawRoundRect(box, corner, corner, trimPreviewBorderPaint);

        // Label: which edge + exact timecode (frame-accurate position in source).
        trimPreviewLabelPaint.setColor(0xFFFFFFFF);
        trimPreviewLabelPaint.setTextSize(10f * density);
        trimPreviewLabelPaint.setTextAlign(Paint.Align.CENTER);
        String tag = (trimEdgePreviewIsLeft ? "IN " : "OUT ") + formatEdgeTimecode(trimEdgePreviewShownMs);
        float ty = below ? box.bottom + 12f * density : box.top - 4f * density;
        // Keep label readable with a subtle shadow.
        trimPreviewLabelPaint.setShadowLayer(2f * density, 0, 0, 0xFF000000);
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
        trimPreviewBorderPaint.setColor(0xFF4CAF50);
        float corner = 6f * density;
        canvas.drawRoundRect(box, corner, corner, trimPreviewBgPaint);
        canvas.drawRoundRect(box, corner, corner, trimPreviewBorderPaint);

        trimPreviewLabelPaint.setColor(0xFFFFFFFF);
        trimPreviewLabelPaint.setShadowLayer(2f * density, 0, 0, 0xFF000000);
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

    private final Path layerKeyPath = new Path();

    /**
     * Draw each overlay/caption as a coloured "layer" bar over its time-range,
     * with white diamonds at its keyframes — so the user can SEE the animation
     * instead of trusting it's there. Read-only for now (no dragging yet).
     */
    private void drawLayers(@NonNull Canvas canvas) {
        if ((overlays.isEmpty() && waveformLayers.isEmpty() && captionSpans.isEmpty())
                || totalEffectiveMs <= 0) return;
        float rowH = LAYER_ROW_HEIGHT_DP * density;
        float rowGap = LAYER_ROW_GAP_DP * density;
        float top = getLayerTopPx();
        float pxPerMs = dpPerSecondPx / 1000f;

        layerLabelPaint.setTextSize(10f * density);
        layerLabelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        layerLabelPaint.setColor(0xFFFFFFFF);
        layerSelPaint.setStyle(Paint.Style.STROKE);
        layerSelPaint.setStrokeWidth(2.5f * density);
        layerSelPaint.setColor(0xFFFFFFFF); // selected-layer highlight ring

        for (int i = 0; i < overlays.size(); i++) {
            TextOverlayItem o = overlays.get(i);
            float y0 = top + i * (rowH + rowGap);
            float y1 = y0 + rowH;

            long startMs = Math.max(0, o.getStartMs());
            long endMs = (o.getEndMs() == Long.MAX_VALUE)
                    ? totalEffectiveMs : Math.min(o.getEndMs(), totalEffectiveMs);
            if (endMs <= startMs) endMs = totalEffectiveMs;

            float x0 = timeToX(startMs);
            float x1 = Math.max(x0 + 6f * density, timeToX(endMs));

            // Image overlays purple, text overlays teal.
            layerBarPaint.setColor(o.isImage() ? 0xDD7E57C2 : 0xDD26A69A);
            canvas.drawRoundRect(x0, y0, x1, y1, 3f * density, 3f * density, layerBarPaint);
            if (selectedLayerKind == 0 && selectedLayerValue == i) {
                canvas.drawRoundRect(x0, y0, x1, y1, 3f * density, 3f * density, layerSelPaint);
            }

            // Label (clipped to the bar).
            String label = o.isImage() ? "IMG" : o.getText();
            if (label != null && !label.isEmpty()) {
                canvas.save();
                canvas.clipRect(x0, y0, x1, y1);
                float ty = (y0 + y1) / 2f + layerLabelPaint.getTextSize() / 3f;
                canvas.drawText(label, x0 + 5f * density, ty, layerLabelPaint);
                canvas.restore();
            }

            // Keyframe diamonds (use the X track as the canonical set of times).
            if (o.isArmed()) {
                KeyframeTrack xt = o.getKeyframes().get(KeyframeSet.X);
                if (xt != null && !xt.isEmpty()) {
                    layerKeyPaint.setColor(0xFFFFFFFF);
                    float cy = (y0 + y1) / 2f;
                    float r = rowH * 0.30f;
                    for (Keyframe k : xt.keyframes) {
                        float kx = timeToX(startMs + k.timeMs);
                        drawDiamond(canvas, kx, cy, r);
                    }
                }
            }
        }

        // Visualizer (waveform) overlays continue the rows below the text/image overlays.
        for (int i = 0; i < waveformLayers.size(); i++) {
            com.fadcam.ui.faditor.model.WaveformOverlayInstance wv = waveformLayers.get(i);
            float y0 = top + (overlays.size() + i) * (rowH + rowGap);
            float y1 = y0 + rowH;

            long startMs = Math.max(0, wv.getStartMs());
            long endMs = (wv.getEndMs() <= 0 || wv.getEndMs() == Long.MAX_VALUE)
                    ? totalEffectiveMs : Math.min(wv.getEndMs(), totalEffectiveMs);
            if (endMs <= startMs) endMs = totalEffectiveMs;

            float x0 = timeToX(startMs);
            float x1 = Math.max(x0 + 6f * density, timeToX(endMs));

            layerBarPaint.setColor(0xDF4DD0E1); // cyan = visualizer layer
            canvas.drawRoundRect(x0, y0, x1, y1, 3f * density, 3f * density, layerBarPaint);
            if (selectedLayerKind == 1 && selectedLayerValue == i) {
                canvas.drawRoundRect(x0, y0, x1, y1, 3f * density, 3f * density, layerSelPaint);
            }

            canvas.save();
            canvas.clipRect(x0, y0, x1, y1);
            float ty = (y0 + y1) / 2f + layerLabelPaint.getTextSize() / 3f;
            layerLabelPaint.setColor(0xFF06303A);
            canvas.drawText("VIZ", x0 + 5f * density, ty, layerLabelPaint);
            canvas.restore();
            layerLabelPaint.setColor(0xFFFFFFFF);
        }

        // Captions all share ONE caption-track row below the overlay + visualizer rows.
        int captionRowBase = overlays.size() + waveformLayers.size();
        float capY0 = top + captionRowBase * (rowH + rowGap);
        for (int i = 0; i < captionSpans.size(); i++) {
            long[] span = captionSpans.get(i);
            float y0 = capY0;
            float y1 = y0 + rowH;

            long startMs = Math.max(0, span[0]);
            long endMs = (span[1] <= 0 || span[1] == Long.MAX_VALUE)
                    ? totalEffectiveMs : Math.min(span[1], totalEffectiveMs);
            if (endMs <= startMs) endMs = totalEffectiveMs;

            float x0 = timeToX(startMs);
            float x1 = Math.max(x0 + 6f * density, timeToX(endMs));

            // Look up clip to check for caption style keyframes
            int clipIdx = (int) span[2];
            Clip clip = (clipIdx >= 0 && clipIdx < segments.size()) ? segments.get(clipIdx).clip : null;
            boolean hasKfs = clip != null && clip.hasCaptionStyleKeyframes();

            if (hasKfs) {
                // Draw colored segments from each keyframe region
                long segLeftMs = startMs;
                String styleId = clip.captionStyleAtClipMs(0);
                for (Clip.CaptionStyleKeyframe kf : clip.getCaptionStyleKeyframes()) {
                    long segRightMs = Math.min(startMs + kf.timeMs, endMs);
                    float sx0 = timeToX(segLeftMs);
                    float sx1 = timeToX(segRightMs);
                    if (sx1 > sx0) {
                        int c = com.fadcam.ui.faditor.transcript.CaptionStyle.byId(styleId).activeColor;
                        layerBarPaint.setColor(0xDF000000 | (c & 0x00FFFFFF));
                        canvas.drawRect(sx0, y0, sx1, y1, layerBarPaint);
                    }
                    segLeftMs = segRightMs;
                    styleId = kf.styleId;
                }
                // Tail after the final keyframe
                if (segLeftMs < endMs) {
                    float sx0 = timeToX(segLeftMs);
                    int c = com.fadcam.ui.faditor.transcript.CaptionStyle.byId(styleId).activeColor;
                    layerBarPaint.setColor(0xDF000000 | (c & 0x00FFFFFF));
                    canvas.drawRect(sx0, y0, x1, y1, layerBarPaint);
                }
            } else {
                layerBarPaint.setColor(0xDFFFC107); // amber = caption layer
                canvas.drawRoundRect(x0, y0, x1, y1, 3f * density, 3f * density, layerBarPaint);
            }

            if (selectedLayerKind == 2 && selectedLayerValue == (int) span[2]) {
                canvas.drawRoundRect(x0, y0, x1, y1, 3f * density, 3f * density, layerSelPaint);
            }

            canvas.save();
            canvas.clipRect(x0, y0, x1, y1);
            float ty = (y0 + y1) / 2f + layerLabelPaint.getTextSize() / 3f;
            layerLabelPaint.setColor(0xFF3E2C00);
            canvas.drawText("CC", x0 + 5f * density, ty, layerLabelPaint);
            canvas.restore();
            layerLabelPaint.setColor(0xFFFFFFFF);

            // Keyframe diamonds
            if (hasKfs) {
                layerKeyPaint.setColor(0xFFFFFFFF);
                float cy = (y0 + y1) / 2f;
                float r = rowH * 0.30f;
                for (Clip.CaptionStyleKeyframe kf : clip.getCaptionStyleKeyframes()) {
                    float kx = timeToX(startMs + kf.timeMs);
                    if (kx >= x0 && kx <= x1) {
                        drawDiamond(canvas, kx, cy, r);
                    }
                }
            }
        }
    }

    private void drawDiamond(@NonNull Canvas canvas, float cx, float cy, float r) {
        layerKeyPath.reset();
        layerKeyPath.moveTo(cx, cy - r);
        layerKeyPath.lineTo(cx + r, cy);
        layerKeyPath.lineTo(cx, cy + r);
        layerKeyPath.lineTo(cx - r, cy);
        layerKeyPath.close();
        canvas.drawPath(layerKeyPath, layerKeyPaint);
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
        transitionLabelPaint.setColor(0xFFFFFFFF);
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
                color = 0x9900BCD4;
                label = t.type.name().contains("BLACK") ? "fade⬛" : "fade⬜";
            } else if (t.isWipe()) {
                color = 0x99FF9800;
                label = "wipe" + t.getDirection().charAt(0);
            } else if (t.isPush()) {
                color = 0x999C27B0;
                label = "push" + t.getDirection().charAt(0);
            } else if (t.isGlShader()) {
                color = 0x9900E5FF;
                label = "gl";
            } else {
                color = 0x994CAF50;
                label = "dissolve";
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
        return layerRowRenderer.measureExtraHeightPx(layerTracks, audioLayerTracks);
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

    /** Bottom Y (px) of the MASTER video track band. */
    private float masterBotPx() {
        return masterTopPx() + trackHeightPx;
    }

    /** Reserved vertical space (px) for the master transcript row below the tape. */
    private float transcriptReservePx() {
        return segmentTranscripts.isEmpty() ? 0f : 17f * density;
    }

    /** Top Y (px) of the AUDIO band (directly below master + its transcript reserve). */
    private float audioBandTopPx() {
        return masterBotPx() + transcriptReservePx() + audioTrackGapPx;
    }

    /** Bottom Y (px) of the AUDIO band. */
    private float audioBandBotPx() {
        return audioBandTopPx() + audioTrackTotalHeightPx();
    }

    private float getLayerTopPx() {
        return masterBotPx()
                + (!audioClips.isEmpty() ? audioTrackGapPx + audioTrackTotalHeightPx() : 0f)
                + transcriptReservePx()
                + LAYER_TOP_GAP_DP * density;
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

    private long displayEndMs(@NonNull TextOverlayItem overlay) {
        long endMs = overlay.getEndMs() == Long.MAX_VALUE
                ? totalEffectiveMs : Math.min(overlay.getEndMs(), totalEffectiveMs);
        if (endMs <= overlay.getStartMs()) endMs = totalEffectiveMs;
        return Math.max(0, endMs);
    }

    private long clampLayerTime(long timeMs) {
        return Math.max(0, Math.min(timeMs, Math.max(0, totalEffectiveMs)));
    }

    private int hitTestLayerRow(float y) {
        if (overlays.isEmpty() || totalEffectiveMs <= 0) return -1;
        float rowH = LAYER_ROW_HEIGHT_DP * density;
        float rowGap = LAYER_ROW_GAP_DP * density;
        float top = getLayerTopPx();
        for (int i = 0; i < overlays.size(); i++) {
            float y0 = top + i * (rowH + rowGap);
            float y1 = y0 + rowH;
            if (y >= y0 - touchSlopPx / 3f && y <= y1 + touchSlopPx / 3f) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Hit-test a TAP on any layer row (overlay / visualizer / caption). {@code x} is the
     * scrolled (content) X. Returns {kind, value}: kind 0=overlay (value=overlayIndex),
     * 1=visualizer (value=waveformIndex), 2=caption (value=clipIndex), or null if none.
     */
    private int[] hitTestLayerTap(float x, float y) {
        if (totalEffectiveMs <= 0) return null;
        float rowH = LAYER_ROW_HEIGHT_DP * density;
        float rowGap = LAYER_ROW_GAP_DP * density;
        float top = getLayerTopPx();
        float slop = touchSlopPx / 3f;
        // Text/image overlay rows.
        for (int i = 0; i < overlays.size(); i++) {
            float y0 = top + i * (rowH + rowGap);
            if (y < y0 - slop || y > y0 + rowH + slop) continue;
            TextOverlayItem o = overlays.get(i);
            float x0 = timeToX(Math.max(0, o.getStartMs()));
            float x1 = Math.max(x0 + 6f * density, timeToX(displayEndMs(o)));
            if (x >= x0 - slop && x <= x1 + slop) return new int[]{0, i};
        }
        // Visualizer rows.
        int base = overlays.size();
        for (int i = 0; i < waveformLayers.size(); i++) {
            float y0 = top + (base + i) * (rowH + rowGap);
            if (y < y0 - slop || y > y0 + rowH + slop) continue;
            com.fadcam.ui.faditor.model.WaveformOverlayInstance wv = waveformLayers.get(i);
            long s = Math.max(0, wv.getStartMs());
            long e = (wv.getEndMs() <= 0 || wv.getEndMs() == Long.MAX_VALUE)
                    ? totalEffectiveMs : Math.min(wv.getEndMs(), totalEffectiveMs);
            if (e <= s) e = totalEffectiveMs;
            float x0 = timeToX(s), x1 = Math.max(x0 + 6f * density, timeToX(e));
            if (x >= x0 - slop && x <= x1 + slop) return new int[]{1, i};
        }
        // Caption track row (one row; segments carry their clip index in span[2]).
        if (!captionSpans.isEmpty()) {
            int capRow = base + waveformLayers.size();
            float y0 = top + capRow * (rowH + rowGap);
            if (y >= y0 - slop && y <= y0 + rowH + slop) {
                for (long[] sp : captionSpans) {
                    long s = Math.max(0, sp[0]);
                    long e = (sp[1] <= 0 || sp[1] == Long.MAX_VALUE)
                            ? totalEffectiveMs : Math.min(sp[1], totalEffectiveMs);
                    if (e <= s) e = totalEffectiveMs;
                    float x0 = timeToX(s), x1 = Math.max(x0 + 6f * density, timeToX(e));
                    if (x >= x0 && x <= x1) return new int[]{2, (int) sp[2]};
                }
            }
        }
        return null;
    }

    private Drag hitTestLayer(float x, float y) {
        int row = hitTestLayerRow(y);
        if (row < 0) return Drag.NONE;

        TextOverlayItem overlay = overlays.get(row);
        long startMs = Math.max(0, overlay.getStartMs());
        long endMs = displayEndMs(overlay);
        float x0 = timeToX(startMs);
        float x1 = Math.max(x0 + 6f * density, timeToX(endMs));
        float edgeSlop = Math.max(handleWidthPx, touchSlopPx * 0.65f);

        if (Math.abs(x - x0) <= edgeSlop) {
            activeLayerIndex = row;
            return Drag.LAYER_LEFT_HANDLE;
        }
        if (Math.abs(x - x1) <= edgeSlop) {
            activeLayerIndex = row;
            return Drag.LAYER_RIGHT_HANDLE;
        }

        KeyframeTrack xt = overlay.getKeyframes().get(KeyframeSet.X);
        if (xt != null && !xt.isEmpty()) {
            float keySlop = Math.max(10f * density, touchSlopPx * 0.55f);
            for (Keyframe keyframe : xt.keyframes) {
                long timelineMs = startMs + keyframe.timeMs;
                if (timelineMs < startMs || timelineMs > endMs) continue;
                if (Math.abs(x - timeToX(timelineMs)) <= keySlop) {
                    activeLayerIndex = row;
                    layerDragInitialKeyLocalMs = keyframe.timeMs;
                    layerDragOriginalKeyLocalMs = keyframe.timeMs;
                    return Drag.LAYER_KEYFRAME;
                }
            }
        }

        if (x >= x0 && x <= x1) {
            activeLayerIndex = row;
        }
        return Drag.NONE;
    }

    /**
     * Whole-project overview strip: every clip as a proportional block, with a
     * highlighted viewport showing which part of the project is on screen.
     * Tap or drag anywhere on it to jump/scrub.
     */
    private void drawMinimap(Canvas canvas, int viewW) {
        if (totalEffectiveMs <= 0 || segments.isEmpty()) return;
        float margin = 8f * density;
        float top = 3f * density;
        float bot = minimapHeightPx - 3f * density;
        float stripW = viewW - margin * 2;
        if (stripW <= 0) return;

        // Clip blocks
        long cumul = 0;
        float gap = Math.min(1.5f * density, stripW / (segments.size() * 8f));
        for (int i = 0; i < segments.size(); i++) {
            SegmentData sd = segments.get(i);
            float x0 = margin + (cumul / (float) totalEffectiveMs) * stripW;
            cumul += sd.effectiveMs;
            float x1 = margin + (cumul / (float) totalEffectiveMs) * stripW;
            minimapBlockPaint.setColor(i == selectedIndex ? 0xFF4CAF50 : 0xFF5A5A5A);
            canvas.drawRoundRect(x0, top, Math.max(x0 + 1, x1 - gap), bot,
                    2f * density, 2f * density, minimapBlockPaint);

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
                        minimapBlockPaint.setColor(0xFF111111);
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
                        minimapBlockPaint.setColor(0xCCFFEB3B);
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
            minimapViewportPaint.setColor(0x30FFFFFF);
            canvas.drawRoundRect(vx0, top - 1.5f * density, vx1, bot + 1.5f * density,
                    3f * density, 3f * density, minimapViewportPaint);
            minimapViewportBorderPaint.setColor(0xCCFFFFFF);
            minimapViewportBorderPaint.setStyle(Paint.Style.STROKE);
            minimapViewportBorderPaint.setStrokeWidth(1.2f * density);
            canvas.drawRoundRect(vx0, top - 1.5f * density, vx1, bot + 1.5f * density,
                    3f * density, 3f * density, minimapViewportBorderPaint);
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

    private void drawRuler(Canvas canvas, int viewW) {
        if (totalEffectiveMs <= 0) return;
        
        // Dynamic label interval based on zoom (like professional editors)
        // Calculate minimum pixels between labels to avoid overlap (~60dp)
        float minLabelGapPx = 60f * density;
        long labelInterval = calculateDynamicLabelInterval(minLabelGapPx);
        
        // Calculate tick hierarchy intervals to avoid conflicts
        long minorInterval;
        long mediumInterval;
        
        if (labelInterval >= 5000) {
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
        
        // Draw minor ticks (finest detail) - skip if too dense
        if (dpPerSecondPx > 25f * density) {  // Only show when zoomed in enough
            rulerTickPaint.setStrokeWidth(1f * density);
            for (long t = 0; t <= totalEffectiveMs; t += minorInterval) {
                if (t % mediumInterval == 0) continue;  // Skip medium/major ticks
                float x = timeToX(t);
                canvas.drawLine(x, rulerHeightPx - rulerTickHeightPx * 0.3f, x, rulerHeightPx, rulerTickPaint);
            }
        }
        
        // Draw medium ticks (1s or sub-intervals)
        if (labelInterval > mediumInterval) {
            rulerTickPaint.setStrokeWidth(1.5f * density);
            for (long t = 0; t <= totalEffectiveMs; t += mediumInterval) {
                if (t % labelInterval == 0) continue;  // Skip labeled ticks
                float x = timeToX(t);
                canvas.drawLine(x, rulerHeightPx - rulerTickHeightPx * 0.6f, x, rulerHeightPx, rulerTickPaint);
            }
        }
        
        // Draw major ticks with labels (dynamic interval)
        rulerTickPaint.setStrokeWidth(2f * density);
        for (long t = 0; t <= totalEffectiveMs; t += labelInterval) {
            float x = timeToX(t);
            String text = fmtTime(t);
            float halfText = rulerTextPaint.measureText(text) / 2f;
            
            // Tall tick for labeled intervals
            canvas.drawLine(x, rulerHeightPx - rulerTickHeightPx, x, rulerHeightPx, rulerTickPaint);
            
            // Label
            canvas.drawText(text, x - halfText, rulerHeightPx - rulerTickHeightPx - 2f * density, rulerTextPaint);
        }
    }
    
    /** Calculate label interval dynamically based on zoom level to prevent overlap */
    private long calculateDynamicLabelInterval(float minLabelGapPx) {
        // Available intervals in ascending order
        long[] intervals = {100, 200, 500, 1000, 2000, 5000, 10000, 30000, 60000};
        
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
        RectF r = segRects.get(i);
        boolean sel = (i == selectedIndex);
        SegmentData sd = segments.get(i);

        // Draw thumbnails if available, otherwise draw solid color
        List<Bitmap> thumbs = thumbnailsCache.get(sd.cacheKey);
        if (thumbs != null && !thumbs.isEmpty()) {
            drawThumbnailsForSegment(canvas, r, thumbs, sel);
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
            java.util.List<Clip.VolumeKeyframe> kfs = sd.clip.getVolumeKeyframes();
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
            labelPaint.setShadowLayer(2f * density, 0, 0, 0xFF000000);
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
    private void drawSegmentTranscript(Canvas canvas, RectF rect, SegmentData sd, int segIndex) {
        if (sd.clipId == null) return;
        com.fadcam.ui.faditor.transcript.Transcript tr = segmentTranscripts.get(sd.clipId);
        if (tr == null || tr.words.isEmpty()) return;

        float fontSize = 9f * density;
        transcriptTextPaint.setTextSize(fontSize);
        transcriptTextPaint.setTypeface(Typeface.DEFAULT);
        transcriptTextPaint.setColor(0x99FFFFFF);
        transcriptTextPaint.setShadowLayer(1.5f * density, 0, 0, 0xFF000000);

        transcriptHighlightPaint.setTextSize(fontSize);
        transcriptHighlightPaint.setTypeface(Typeface.DEFAULT_BOLD);
        transcriptHighlightPaint.setColor(0xFF4CAF50);
        transcriptHighlightPaint.setShadowLayer(2f * density, 0, 0, 0xFF000000);

        float pxPerMs = rect.width() / (float) sd.trimmedMs;
        float textY = rect.bottom + (TRANSCRIPT_BELOW_GAP_DP + 9f) * density;

        // Clip to the segment's horizontal extent so words don't overflow
        canvas.save();
        float transcriptTop = rect.bottom + TRANSCRIPT_BELOW_GAP_DP * density;
        float transcriptBot = transcriptTop + 14f * density;
        clipPath.reset();
        clipPath.addRect(rect.left, transcriptTop, rect.right, transcriptBot,
                Path.Direction.CW);
        canvas.clipRect(rect.left, transcriptTop, rect.right, transcriptBot);

        boolean isCurrentClip = (segIndex == transcriptClipIndex);

        for (int w = 0; w < tr.words.size(); w++) {
            com.fadcam.ui.faditor.transcript.TranscriptWord word = tr.words.get(w);
            // Only draw words within the clip's trim range
            if (word.startMs < sd.inPointMs || word.endMs > sd.outPointMs) continue;

            float wordX = rect.left + (word.startMs - sd.inPointMs) * pxPerMs;
            String text = word.text;

            // Struck words are dimmed
            boolean isActive = isCurrentClip
                    && currentPlayheadSourceMs >= word.startMs
                    && currentPlayheadSourceMs <= word.endMs;

            if (isActive) {
                canvas.drawText(text, wordX, textY, transcriptHighlightPaint);
            } else if (word.struck) {
                transcriptTextPaint.setColor(0x44FFFFFF);
                canvas.drawText(text, wordX, textY, transcriptTextPaint);
                transcriptTextPaint.setColor(0x99FFFFFF);
            } else {
                canvas.drawText(text, wordX, textY, transcriptTextPaint);
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
        segmentPaint.setColor(0xCC101010); // near-opaque dark
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
        segmentPaint.setColor(0x66FFEB3B); // translucent yellow
        for (long[] span : sd.silenceCandidates) {
            float x0 = rect.left + (Math.max(sd.inPointMs, span[0]) - sd.inPointMs) * pxPerMs;
            float x1 = rect.left + (Math.min(sd.outPointMs, span[1]) - sd.inPointMs) * pxPerMs;
            if (x1 > x0) {
                canvas.drawRect(x0, rect.top, x1, rect.bottom, segmentPaint);
            }
        }
        canvas.restore();
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

        segmentWavePaint.setColor(0xB34DD0E1); // soft cyan, distinct from green accents
        for (int j = 0; j < barCount; j++) {
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
                                          List<Bitmap> thumbs, boolean selected) {
        if (thumbs.isEmpty()) return;

        // Clip canvas to rounded rect so thumbnails don't bleed outside corners
        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(rect, segmentCornerPx, segmentCornerPx, Path.Direction.CW);
        canvas.clipPath(clipPath);

        // Tile thumbnails across the segment
        float tileWidth = rect.height();  // Square tiles matching track height
        float x = rect.left;
        int thumbIdx = 0;

        while (x < rect.right) {
            Bitmap thumb = thumbs.get(Math.min(thumbIdx, thumbs.size() - 1));
            if (thumb != null && !thumb.isRecycled()) {
                float drawRight = Math.min(x + tileWidth, rect.right);
                RectF dest = new RectF(x, rect.top, drawRight, rect.bottom);
                canvas.drawBitmap(thumb, null, dest, null);
            }
            x += tileWidth;
            thumbIdx++;
            if (thumbIdx >= thumbs.size() && x < rect.right) {
                Bitmap last = thumbs.get(thumbs.size() - 1);
                if (last != null && !last.isRecycled()) {
                    canvas.drawBitmap(last, null, new RectF(x, rect.top, rect.right, rect.bottom), null);
                }
                break;
            }
        }

        // Darken overlay for selected segment (green tint)
        if (selected) {
            segmentPaint.setColor(0x40004400);
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

    private void loadThumbnailsForSegment(int index) {
        if (index < 0 || index >= segments.size()) return;
        if (index >= segRects.size()) return;

        SegmentData sd = segments.get(index);
        String key = sd.cacheKey;

        // Already loaded, currently loading, or known-failed
        if (thumbnailsCache.containsKey(key) && !thumbnailsCache.get(key).isEmpty()) return;
        if (thumbnailsLoading.contains(key)) return;
        if (thumbnailsFailed.contains(key)) return;

        thumbnailsLoading.add(key);

        // Calculate how many thumbnails we need based on segment width
        RectF rect = segRects.get(index);
        float tileWidth = trackHeightPx;  // Square tiles
        int count = Math.max(1, (int) Math.ceil(rect.width() / tileWidth));
        count = Math.min(count, MAX_THUMBNAILS_PER_SEGMENT);

        int thumbSize = Math.max(1, (int) trackHeightPx);
        Uri uri = sd.sourceUri;
        boolean isImage = sd.isImageClip;
        long inMs = sd.inPointMs;
        long outMs = sd.outPointMs;
        int finalCount = count;

        thumbnailExecutor.execute(() -> {
            List<Bitmap> thumbs = new ArrayList<>();
            try {
                if (isImage) {
                    extractImageThumbnails(uri, thumbs, thumbSize);
                } else {
                    extractVideoThumbnails(uri, inMs, outMs, thumbs, thumbSize, finalCount);
                }
            } catch (Exception e) {
                FLog.w(TAG, "Failed to extract thumbnails for " + key, e);
            }
            mainHandler.post(() -> {
                thumbnailsLoading.remove(key);
                if (!thumbs.isEmpty()) {
                    thumbnailsCache.put(key, thumbs);
                    invalidate();
                } else {
                    // Hard failure (no decodable frames) — remember so the lazy
                    // loader doesn't re-enqueue this key on every redraw.
                    thumbnailsFailed.add(key);
                }
            });
        });
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
     * Extracts evenly-spaced video frames using MediaMetadataRetriever.
     */
    private void extractVideoThumbnails(@NonNull Uri uri, long inMs, long outMs,
                                        @NonNull List<Bitmap> out, int thumbSize, int count) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(getContext(), uri);
            long rangeMs = Math.max(1, outMs - inMs);

            for (int i = 0; i < count; i++) {
                long timeMs = inMs + (rangeMs * i) / count;
                long timeUs = timeMs * 1000L;
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
    private static Bitmap centerCropSquare(@NonNull Bitmap src, int targetSize) {
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
        float railH = 7f * density;
        filmPaint.setStyle(Paint.Style.FILL);
        filmPaint.setColor(COLOR_FILM_RAIL);
        canvas.drawRect(0, tTop, w, tTop + railH, filmPaint);
        canvas.drawRect(0, tBot - railH, w, tBot, filmPaint);
        // Sprocket perforations punched along each rail (evenly spaced across the viewport).
        filmPaint.setColor(COLOR_FILM_SPROCKET);
        float holeW = 5f * density, holeH = 3.5f * density, corner = 1f * density;
        float pitch = 14f * density;
        float topCy = tTop + railH / 2f, botCy = tBot - railH / 2f;
        for (float cx = pitch / 2f; cx < w; cx += pitch) {
            canvas.drawRoundRect(cx - holeW / 2f, topCy - holeH / 2f,
                    cx + holeW / 2f, topCy + holeH / 2f, corner, corner, filmPaint);
            canvas.drawRoundRect(cx - holeW / 2f, botCy - holeH / 2f,
                    cx + holeW / 2f, botCy + holeH / 2f, corner, corner, filmPaint);
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
        canvas.drawRect(px - playheadWidthPx / 2f, lineTop,
                px + playheadWidthPx / 2f, lineBot, playheadPaint);
    }

    /**
     * Draws each audio clip as a rounded rectangle with waveform bars inside.
     * Called within the scrolled canvas context.
     */
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

    private void drawAudioTrack(Canvas canvas) {
        for (int i = 0; i < audioClips.size() && i < audioClipRects.size(); i++) {
            AudioClip ac = audioClips.get(i);
            RectF rect = audioClipRects.get(i);

            // Clip background (always rounded, handles drawn on top)
            boolean selected = (i == selectedAudioIndex);
            audioClipPaint.setColor(selected ? COLOR_AUDIO_BG_SEL : COLOR_AUDIO_BG);
            canvas.drawRoundRect(rect, audioCornerPx, audioCornerPx, audioClipPaint);

            // Selection border (rounded to match)
            if (selected) {
                audioBorderPaint.setStyle(Paint.Style.STROKE);
                canvas.drawRoundRect(rect, audioCornerPx, audioCornerPx, audioBorderPaint);
            }

            // Waveform fills the full width (handles overlap edge bars, like video thumbnails)
            int[] waveform = ac.getWaveform();
            if (waveform != null && waveform.length > 0) {
                boolean muted = ac.isMuted();
                int waveColor = muted ? COLOR_AUDIO_WAVE_MUTED : COLOR_AUDIO_WAVE;
                int mirrorColor = muted ? 0x40555555 : COLOR_AUDIO_WAVE_DIM;
                audioWavePaint.setColor(waveColor);
                audioWaveMirrorPaint.setColor(mirrorColor);

                float clipW = rect.width();
                float centerY = rect.centerY();
                float topHalf = (centerY - rect.top) - 1f * density;
                float botHalf = (rect.bottom - centerY) - 1f * density;

                // Draw centerline
                canvas.drawLine(rect.left, centerY,
                        rect.right, centerY, audioCenterlinePaint);

                // 3dp bars with 0.5dp gaps
                float barW = 3f * density;
                float gap = 0.5f * density;
                int barCount = Math.max(1, (int) (clipW / (barW + gap)));
                float step = clipW / barCount;

                canvas.save();
                canvas.clipRect(rect);
                RectF barRect = new RectF();
                for (int j = 0; j < barCount; j++) {
                    // W1 peak-preserving envelope (JoyRaptor 2026-07-06): each bar shows the LOUDEST sample
                    // bin in the span it covers, not one point-sampled value — so onsets & transients
                    // pop and quiet passages still read (the DAW look you align words by), instead of
                    // the old averaged smear that skipped every peak between sampled points.
                    float p0 = (j / (float) barCount) * waveform.length;
                    float p1 = ((j + 1) / (float) barCount) * waveform.length;
                    int s0 = Math.max(0, Math.min((int) p0, waveform.length - 1));
                    int s1 = Math.max(s0 + 1, Math.min((int) Math.ceil(p1), waveform.length));
                    int peak = 0;
                    for (int s = s0; s < s1; s++) if (waveform[s] > peak) peak = waveform[s];
                    float amplitude = peak / 255f;

                    // Perceptual scaling (sqrt-ish) lifts quiet detail above the floor.
                    amplitude = (float) Math.pow(amplitude, 0.6);

                    float minBar = 1f * density;
                    float topH = Math.max(minBar, amplitude * topHalf);
                    float botH = Math.max(minBar, amplitude * botHalf * 0.85f);

                    float barX = rect.left + j * step;
                    float barRadius = barW * 0.4f;

                    barRect.set(barX, centerY - topH, barX + barW, centerY);
                    canvas.drawRoundRect(barRect, barRadius, barRadius, audioWavePaint);

                    barRect.set(barX, centerY, barX + barW, centerY + botH);
                    canvas.drawRoundRect(barRect, barRadius, barRadius, audioWaveMirrorPaint);
                }
                canvas.restore();
            }

            // Blue volume-automation envelope (rubber-band line, keyframe-to-keyframe).
            // x = clip-local time across the rect; y maps gain 0..2 (bottom..top), 100% at mid.
            if (ac.hasVolumeKeyframes()) {
                java.util.List<AudioClip.VolumeKeyframe> kfs = ac.getVolumeKeyframes();
                long dur = Math.max(1, ac.getTrimmedDurationMs());
                float h = rect.height();
                canvas.save();
                canvas.clipRect(rect);
                float prevX = 0f, prevY = 0f;
                for (int k = 0; k < kfs.size(); k++) {
                    AudioClip.VolumeKeyframe kf = kfs.get(k);
                    float fx = Math.max(0f, Math.min(1f, kf.timeMs / (float) dur));
                    float x = rect.left + fx * rect.width();
                    float gFrac = Math.max(0f, Math.min(1f, kf.volume / 2.0f));
                    float y = rect.bottom - gFrac * h;
                    if (k == 0) {
                        // Flat hold from clip start to first keyframe
                        canvas.drawLine(rect.left, y, x, y, volEnvLinePaint);
                    } else {
                        canvas.drawLine(prevX, prevY, x, y, volEnvLinePaint);
                    }
                    if (k == kfs.size() - 1) {
                        // Flat hold from last keyframe to clip end
                        canvas.drawLine(x, y, rect.right, y, volEnvLinePaint);
                    }
                    canvas.drawCircle(x, y, 2.6f * density, volEnvDotPaint);
                    prevX = x;
                    prevY = y;
                }
                canvas.restore();
            }

            // Label (always offset to clear handle area)
            String label = ac.getLabel();
            if (label != null && !label.isEmpty()) {
                float textX = rect.left + handleWidthPx + 4f * density;
                float textY = rect.top + audioLabelPaint.getTextSize() + 2f * density;
                canvas.save();
                canvas.clipRect(rect);
                canvas.drawText(label, textX, textY, audioLabelPaint);
                canvas.restore();
            }

            // Duration label at bottom-right, sticky to visible portion
            long audioDurMs = ac.getOutPointMs() - ac.getInPointMs();
            if (rect.width() > labelPaint.getTextSize() * 3f) {
                String durLabel = formatDurationCompact(audioDurMs);
                float durY = rect.bottom - 3f * density;

                float viewLeft = scrollOffsetPx;
                float viewRight = scrollOffsetPx + getWidth();
                float visRight = Math.min(rect.right, viewRight);

                float durWidth = labelPaint.measureText(durLabel);
                float padding = 6f * density;
                float cx = visRight - padding - durWidth / 2f;
                cx = Math.max(rect.left + durWidth / 2f + padding, cx);
                cx = Math.min(rect.right - durWidth / 2f - padding, cx);

                labelPaint.setShadowLayer(2f * density, 0, 0, 0xFF000000);
                canvas.drawText(durLabel, cx, durY, labelPaint);
            }

            // Audio transcript words along the bottom of the audio clip
            com.fadcam.ui.faditor.transcript.Transcript audioTr = audioTranscripts.get(i);
            if (audioTr != null && !audioTr.words.isEmpty()) {
                float fontSize = 8f * density;
                transcriptTextPaint.setTextSize(fontSize);
                transcriptTextPaint.setTypeface(Typeface.DEFAULT);
                transcriptTextPaint.setColor(0x99FFFFFF);
                transcriptTextPaint.setShadowLayer(1.5f * density, 0, 0, 0xFF000000);

                transcriptHighlightPaint.setTextSize(fontSize);
                transcriptHighlightPaint.setTypeface(Typeface.DEFAULT_BOLD);
                transcriptHighlightPaint.setColor(0xFF4CAF50);
                transcriptHighlightPaint.setShadowLayer(2f * density, 0, 0, 0xFF000000);

                float pxPerMs = rect.width() / (float) audioDurMs;
                float textY = rect.bottom - 3f * density;

                canvas.save();
                canvas.clipRect(rect);

                boolean isCurrentAudio = (i == audioTranscriptClipIndex);

                for (int w = 0; w < audioTr.words.size(); w++) {
                    com.fadcam.ui.faditor.transcript.TranscriptWord word = audioTr.words.get(w);
                    if (word.startMs < ac.getInPointMs() || word.endMs > ac.getOutPointMs()) continue;
                    float wordX = rect.left + (word.startMs - ac.getInPointMs()) * pxPerMs;
                    boolean isActive = isCurrentAudio
                            && audioCurrentPlayheadMs >= word.startMs
                            && audioCurrentPlayheadMs <= word.endMs;
                    if (isActive) {
                        canvas.drawText(word.text, wordX, textY, transcriptHighlightPaint);
                    } else if (word.struck) {
                        transcriptTextPaint.setColor(0x44FFFFFF);
                        canvas.drawText(word.text, wordX, textY, transcriptTextPaint);
                        transcriptTextPaint.setColor(0x99FFFFFF);
                    } else {
                        canvas.drawText(word.text, wordX, textY, transcriptTextPaint);
                    }
                }
                canvas.restore();
            }

            // Trim handles on selected audio clip
            if (selected) {
                drawAudioTrimHandles(canvas, rect);
            }
        }
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
        handleNotchPaint.setColor(0xBB0D47A1);
        canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
        canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
        drawNotch(canvas, leftH);
        drawNotch(canvas, rightH);
        handlePaint.setColor(COLOR_HANDLE);
        handleNotchPaint.setColor(COLOR_HANDLE_NOTCH);
    }

    private void drawTransitionHelper(Canvas canvas, Transition t, RectF rect) {
        if (t == null || rect == null) return;
        if (t.isWipe() || t.type == Transition.Type.LINEAR_MIRROR_WIPE) {
            float cx = rect.centerX();
            float cy = rect.centerY();
            float len = Math.max(rect.width(), rect.height()) * 0.42f;
            transitionHelperPaint.setColor(0xCC4CAF50);
            canvas.drawLine(cx - len, cy, cx + len, cy, transitionHelperPaint);
            transitionHelperPaint.setColor(0xAA00BCD4);
            canvas.drawLine(cx, cy - len, cx, cy + len, transitionHelperPaint);
        } else if (t.type == Transition.Type.RADIAL) {
            float cx = rect.centerX();
            float cy = rect.centerY();
            float r = Math.min(rect.width(), rect.height()) * 0.34f;
            transitionHelperPaint.setColor(0xCC4CAF50);
            canvas.drawCircle(cx, cy, r, transitionHelperPaint);
            transitionHelperPaint.setColor(0xAA00BCD4);
            canvas.drawCircle(cx, cy, r * 0.68f, transitionHelperPaint);
        } else if (t.isPush()) {
            transitionHelperPaint.setColor(0x669E9E9E);
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
            transitionHelperPaint.setColor(0xCC4CAF50);
            canvas.drawPath(tri, transitionHelperPaint);
        } else if (t.isGlitch()) {
            transitionHelperPaint.setColor(0xCC00E5FF);
            canvas.drawLine(rect.left + 4f * density, rect.top + 8f * density,
                    rect.right - 4f * density, rect.top + 8f * density, transitionHelperPaint);
            transitionHelperPaint.setColor(0xCCFF006E);
            canvas.drawLine(rect.left + 10f * density, rect.centerY(),
                    rect.right - 10f * density, rect.centerY(), transitionHelperPaint);
            transitionHelperPaint.setColor(0xCC00E5FF);
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

    /**
     * Draws trim handles on the selected audio clip, matching video handle style.
     * Includes trim overlay feedback and notch indicators.
     */
    private void drawAudioTrimHandles(Canvas canvas, RectF rect) {
        float hTop = rect.top - handleOverhangPx;
        float hBot = rect.bottom + handleOverhangPx;
        float cornerRadius = audioCornerPx;

        if (activeDrag == Drag.AUDIO_LEFT_HANDLE) {
            if (audioTrimDragX > rect.left) {
                canvas.drawRect(rect.left, rect.top, audioTrimDragX, rect.bottom, trimOverlayPaint);
            } else if (audioTrimDragX < rect.left) {
                canvas.drawRect(audioTrimDragX, rect.top, rect.left, rect.bottom, trimRecoverPaint);
            }
            RectF leftH = new RectF(audioTrimDragX - handleWidthPx / 2f, hTop,
                    audioTrimDragX + handleWidthPx / 2f, hBot);
            canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, leftH);
            RectF rightH = new RectF(rect.right - handleWidthPx, hTop, rect.right, hBot);
            canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, rightH);
        } else if (activeDrag == Drag.AUDIO_RIGHT_HANDLE) {
            RectF leftH = new RectF(rect.left, hTop, rect.left + handleWidthPx, hBot);
            canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, leftH);
            if (audioTrimDragX < rect.right) {
                canvas.drawRect(audioTrimDragX, rect.top, rect.right, rect.bottom, trimOverlayPaint);
            } else if (audioTrimDragX > rect.right) {
                canvas.drawRect(rect.right, rect.top, audioTrimDragX, rect.bottom, trimRecoverPaint);
            }
            RectF rightH = new RectF(audioTrimDragX - handleWidthPx / 2f, hTop,
                    audioTrimDragX + handleWidthPx / 2f, hBot);
            canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, rightH);
        } else {
            // Normal: handles at edges
            RectF leftH = new RectF(rect.left, hTop, rect.left + handleWidthPx, hBot);
            canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, leftH);
            RectF rightH = new RectF(rect.right - handleWidthPx, hTop, rect.right, hBot);
            canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, rightH);
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
            reorderBtnTextPaint.setColor(0xFFFF5252);
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
            minimapBlockPaint.setColor(dragged ? 0xFF4CAF50 : 0xFF5A5A5A);
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
            minimapViewportPaint.setStyle(Paint.Style.FILL);
            minimapViewportPaint.setColor(active ? 0x404CAF50 : 0x30FFFFFF);
            minimapViewportBorderPaint.setColor(active ? 0xCC4CAF50 : 0xCCFFFFFF);
            minimapViewportBorderPaint.setStyle(Paint.Style.STROKE);
            minimapViewportBorderPaint.setStrokeWidth(1.2f * density);
            canvas.drawRect(vL, top - 2f * density, vR, bot + 2f * density, minimapViewportPaint);
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
        List<Bitmap> thumbs = thumbnailsCache.get(segments.get(segIdx).cacheKey);
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
        if (listener != null) listener.onReorderModeChanged(true);
        invalidate();
    }

    private void exitReorderMode(boolean commit) {
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
            
            // Calculate playhead position from scroll offset
            float centerX = getWidth() / 2f;
            float playheadX = centerX + newScrollOffset;
            
            // Update playhead position (will trigger seek and centerPlayhead)
            updatePlayheadFromX(playheadX);
            
            // Continue animation
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
        // Reorder mode intercepts all touch events
        if (isReorderMode) {
            return handleReorderTouch(e);
        }

        // Minimap strip scrubbing
        if (handleMinimapTouch(e)) {
            return true;
        }

        FLog.d(TAG, "onTouchEvent: action=" + e.getActionMasked() + " x=" + e.getX() + " isScaling=" + isScaling + " activeDrag=" + activeDrag);
        
        // Let scale detector process ALL events (it needs to track for pinch detection)
        scaleDetector.onTouchEvent(e);

        // A second finger means a pinch, never a reorder/drag long-press — cancel
        // any pending long-press the instant multi-touch begins.
        if (e.getPointerCount() > 1) {
            longPressHandler.removeCallbacks(longPressRunnable);
            longPressHandler.removeCallbacks(audioLongPressRunnable);
            pendingAudioIndex = -1;
        }

        // If actually pinch-zooming, block other handlers
        if (isScaling) {
            FLog.d(TAG, "onTouchEvent: consumed by active pinch zoom");
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
                if (ppAction == MotionEvent.ACTION_UP && rowScrubVelocityTracker != null) {
                    rowScrubVelocityTracker.computeCurrentVelocity(1000, maxFlingVelocityPx);
                    float vx = rowScrubVelocityTracker.getXVelocity();
                    if (Math.abs(vx) > minFlingVelocityPx) {
                        startPlayheadFling(vx);
                    }
                }
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
        // activeDrag/isDraggingAudio exactly. Surface-overlap fix: also bypass while a
        // row-band touch's axis is undecided (m6RowPendingAxisDecision) or has already
        // resolved to horizontal scrub-passthrough (m6RowScrubPassthroughActive) — the
        // custom onMove below owns axis math for both, using its own downX-relative
        // deltas; letting the gesture detector's onScroll race it here would double-
        // drive (or steal) the scrub exactly like the M10 comment above describes for
        // item drags.
        if (activeDrag == Drag.NONE && !isDraggingAudio && !m7ItemGestureActive && !m7ItemPendingDown
                && !m6RowDragActive && !m6RowPendingAxisDecision && !m6RowScrubPassthroughActive) {
            // Let gesture detector process events only when no active drag. m7ItemPendingDown
            // is included so a body touch's follow-up MOVEs reach the custom onMove (which
            // owns the scrub-vs-pickup-vs-scroll disambiguation) instead of the detector's
            // onScroll racing it — exactly like m6RowPendingAxisDecision for empty space.
            boolean gestureEvent = gestureDetector.onTouchEvent(e);
            if (gestureEvent) {
                FLog.d(TAG, "onTouchEvent: consumed by gesture detector");
                return true;
            }
        }
        
        // Handle custom touch logic for trim/reorder
        float x = e.getX(), y = e.getY();
        // Row-band scrub fling: capture velocity for every custom-path touch. Consumed
        // only when a scrub-passthrough lifts (see onUp) — harmless otherwise.
        int maskedAction = e.getActionMasked();
        if (maskedAction == MotionEvent.ACTION_DOWN) {
            if (rowScrubVelocityTracker == null) rowScrubVelocityTracker = android.view.VelocityTracker.obtain();
            else rowScrubVelocityTracker.clear();
            rowScrubVelocityTracker.addMovement(e);
        } else if (maskedAction == MotionEvent.ACTION_MOVE && rowScrubVelocityTracker != null) {
            rowScrubVelocityTracker.addMovement(e);
        }
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN: 
                FLog.d(TAG, "onTouchEvent: ACTION_DOWN - calling onDown");
                return onDown(x, y);
            case MotionEvent.ACTION_MOVE: 
                return onMove(x, y);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: 
                FLog.d(TAG, "onTouchEvent: ACTION_UP/CANCEL - calling onUp");
                return onUp(x, y, e.getAction() == MotionEvent.ACTION_UP);
        }
        return super.onTouchEvent(e);
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
        // Surface-overlap fix: a miss inside the row band is NOT immediately claimed as
        // an M6 vertical-scroll drag anymore (that used to swallow every horizontal drag
        // over a row too, blocking timeline scrub entirely over Layer 1 / the extracted
        // -audio row and any locked row). Axis is undecided until onMove sees enough
        // movement to tell a vertical row-scroll from a horizontal scrub; still consume
        // the DOWN itself (matches every other surface's contract — a DOWN with no armed
        // gesture yet always returns true).
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
        FLog.d(TAG, "onDown: x=" + x + " y=" + y);
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

        // Adjust x for scroll offset
        float scrolledX = x + scrollOffsetPx;
        FLog.d(TAG, "onDown: scrolledX=" + scrolledX + " scrollOffset=" + scrollOffsetPx);

        // M6 hook: touch dispatch into the multi-row Track UI. Header icon taps
        // (caret/hide/lock/mute) are handled entirely here; a tap elsewhere in a
        // LOCKED track's row is swallowed (locked = taps/gestures ignored at the
        // timeline level per PLAN Part 7 M6 scope item 4); a tap in an unlocked
        // row's body falls through (item editing is M7 — out of scope here).
        if (handleM6RowTouch(scrolledX, y)) {
            return true;
        }

        pendingLayerTap = null;
        Drag layerHit = hitTestLayer(scrolledX, y);
        if (layerHit != Drag.NONE && activeLayerIndex >= 0
                && activeLayerIndex < overlays.size()) {
            TextOverlayItem overlay = overlays.get(activeLayerIndex);
            activeDrag = layerHit;
            layerDragStartMs = Math.max(0, overlay.getStartMs());
            layerDragEndMs = displayEndMs(overlay);
            if (listener != null) listener.onOverlayDragStart(activeLayerIndex);
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        // A tap on any layer-row body (overlay / visualizer / caption) → dispatch on UP;
        // a long-hold there → delete/remove that layer object.
        int[] layerTap = hitTestLayerTap(scrolledX, y);
        if (layerTap != null) {
            pendingLayerTap = layerTap;
            layerLongPressFired = false;
            longPressHandler.removeCallbacks(layerLongPressRunnable);
            longPressHandler.postDelayed(layerLongPressRunnable, AUDIO_LONG_PRESS_MS);
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        // Touch landed off any layer row → clear the layer-selection highlight.
        if (selectedLayerKind != -1) {
            selectedLayerKind = -1;
            selectedLayerValue = -1;
            invalidate();
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
        FLog.d(TAG, "onDown: hit segment " + downSegIndex);

        // Check audio trim handles (before audio body hit test)
        if (selectedAudioIndex >= 0 && selectedAudioIndex < audioClipRects.size()) {
            Drag ah = hitTestAudioHandle(scrolledX, y);
            if (ah != Drag.NONE) {
                FLog.d(TAG, "onDown: hit audio handle " + ah);
                activeDrag = ah;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }

        // Check if touch is on an audio clip (for select/drag)
        if (downSegIndex < 0) {
            int audioHit = hitTestAudioClip(scrolledX, y);
            if (audioHit >= 0) {
                FLog.d(TAG, "onDown: hit audio clip " + audioHit);
                pendingAudioIndex = audioHit;
                audioLongPressTriggered = false;
                // Schedule audio long-press for drag
                longPressHandler.removeCallbacks(audioLongPressRunnable);
                longPressHandler.postDelayed(audioLongPressRunnable, AUDIO_LONG_PRESS_MS);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }

        if (downSegIndex >= 0) {
            // Schedule long press detection via Handler
            longPressHandler.removeCallbacks(longPressRunnable);
            if (segments.size() > 1) {
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_MS);
            }
        }
        // Always accept touch for scrolling (anywhere on timeline, not just on segments)
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private boolean onMove(float x, float y) {
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
            layerGestureController.onRowBodyMove(scrolledX, y, getM6RowsTopPx(), totalEffectiveMs, this::xToTime);
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
                inEdgeZone = x < edgeScrollZonePx || x > getWidth() - edgeScrollZonePx;
                if (inEdgeZone) {
                    cancelPendingExcursionEnter();
                    abandonExcursionInPlace();
                }
                startOrStopEdgeScroll(x);
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
                    // yank the view (feedback 2026-07-03am).
                    longPressHandler.removeCallbacks(excursionEnterRunnable);
                    pendingExcursionJointMs = joint;
                    longPressHandler.postDelayed(excursionEnterRunnable, EXCURSION_DWELL_MS);
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
            // Cancel audio long press too (if not already triggered)
            if (!audioLongPressTriggered) {
                longPressHandler.removeCallbacks(audioLongPressRunnable);
                pendingAudioIndex = -1;
            }
            // Cancel layer-row long-press (don't delete on a scroll).
            if (!layerLongPressFired) {
                longPressHandler.removeCallbacks(layerLongPressRunnable);
                pendingLayerTap = null;
            }
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

        // Audio trim handle drag
        if (activeDrag == Drag.AUDIO_LEFT_HANDLE || activeDrag == Drag.AUDIO_RIGHT_HANDLE) {
            lastTrimFingerScreenX = x;
            doAudioTrimDrag(scrolledX);
            startOrStopEdgeScroll(x);
            return true;
        }

        if (activeDrag == Drag.LAYER_LEFT_HANDLE
                || activeDrag == Drag.LAYER_RIGHT_HANDLE
                || activeDrag == Drag.LAYER_KEYFRAME) {
            doLayerDrag(scrolledX);
            return true;
        }

        if (activeDrag == Drag.TRANSITION_LEFT_HANDLE || activeDrag == Drag.TRANSITION_RIGHT_HANDLE) {
            doTransitionTrimDrag(scrolledX);
            return true;
        }

        // Audio clip drag: update offset based on finger movement
        if (isDraggingAudio && dragAudioIndex >= 0 && dragAudioIndex < audioClips.size()) {
            lastTrimFingerScreenX = x;
            float deltaX = scrolledX - dragAudioStartX;
            float deltaSec = deltaX / dpPerSecondPx;
            long newOffset = dragAudioStartOffsetMs + (long) (deltaSec * 1000f);
            newOffset = Math.max(0, newOffset);
            AudioClip ac = audioClips.get(dragAudioIndex);
            ac.setOffsetMs(newOffset);
            computeRects();
            invalidate();
            startOrStopEdgeScroll(x);
            return true;
        }

        return true;
    }

    private boolean onUp(float x, float y, boolean isUp) {
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
            getParent().requestDisallowInterceptTouchEvent(false);
            invalidate();
            return true;
        }
        if (m7ItemGestureActive) {
            m7ItemGestureActive = false;
            stopEdgeScroll(); // A1: the held-item edge pan ends with the finger
            if (itemDragMinimapNav) {
                // Finger lifted while parked on the minimap band: end the nav state;
                // the commit below drops the item where it already legally sits.
                itemDragMinimapNav = false;
                layerGestureController.setSuppressMoveMapping(false);
            }
            // isUp==false is an ACTION_CANCEL — the controller ABORTS (reverts the item,
            // fires no drop callbacks) instead of committing (review fix 2026-07-03).
            layerGestureController.onRowBodyUp(isUp);
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
        longPressHandler.removeCallbacks(audioLongPressRunnable);

        // Tear down the frame-accurate trim-edge preview when a trim drag ends.
        if (last == Drag.LEFT_HANDLE || last == Drag.RIGHT_HANDLE) {
            clearTrimEdgePreview();
            loopReadoutActive = false; // L3: hide the loop-extension readout on release
        }

        // Finish audio drag
        if (isDraggingAudio) {
            // PHASE-R R2 (P0 A8, the user's repro path): the legacy waveform-lane drag
            // wrote setOffsetMs raw all the way to release, so same-lane audio items
            // could be dropped STACKED. Commit-time resolve to the nearest butting
            // position (the established no-overlap rule); if no legal spot exists,
            // snap back to the drag origin (established cancel behavior). No listener
            // fires from this legacy path (pre-existing), so no undo semantics change.
            if (dragAudioIndex >= 0 && dragAudioIndex < audioClips.size()) {
                AudioClip movedAc = audioClips.get(dragAudioIndex);
                if (!isUp) {
                    // ACTION_CANCEL = interrupted gesture → abort, restore origin
                    // (same rule the row-system controller applies on CANCEL).
                    movedAc.setOffsetMs(dragAudioStartOffsetMs);
                } else {
                    long resolved = resolveAudioDropOffset(movedAc, movedAc.getOffsetMs());
                    movedAc.setOffsetMs(resolved == Long.MIN_VALUE
                            ? dragAudioStartOffsetMs : resolved);
                }
            }
            isDraggingAudio = false;
            dragAudioIndex = -1;
            pendingAudioIndex = -1;
            computeRects();
            invalidate();
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }

        // Audio tap: select/deselect (only if long press didn't trigger drag)
        if (isUp && pendingAudioIndex >= 0 && !audioLongPressTriggered) {
            float tapDist = Math.abs(x - downX);
            if (tapDist < touchSlopPx) {
                if (pendingAudioIndex == selectedAudioIndex) {
                    selectedAudioIndex = -1; // Deselect
                } else {
                    selectedAudioIndex = pendingAudioIndex;
                    // Deselect video segment visually when audio is selected,
                    // but DON'T propagate onSegmentSelected(-1) to the activity.
                    // Doing so would call selectSegment(-1) which sets
                    // selectedClipIndex = -1, then getSelectedClip() silently
                    // resets it to 0, corrupting playback tracking and causing
                    // the playhead to jump to the wrong segment.
                    if (selectedIndex >= 0) {
                        selectedIndex = -1;
                    }
                }
                invalidate();
                if (listener != null) listener.onAudioClipSelected(selectedAudioIndex);
            }
            pendingAudioIndex = -1;
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        pendingAudioIndex = -1;

        // Layer-row tap (no drag): dispatch the appropriate callback. A long-press already
        // fired its delete via the runnable, so just consume the UP here.
        if (pendingLayerTap != null) {
            longPressHandler.removeCallbacks(layerLongPressRunnable);
            int[] lt = pendingLayerTap;
            pendingLayerTap = null;
            if (layerLongPressFired) {
                layerLongPressFired = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            if (isUp && Math.abs(x - downX) < touchSlopPx && listener != null) {
                // Highlight the tapped layer row, and clear any video/audio selection.
                selectedLayerKind = lt[0];
                selectedLayerValue = lt[1];
                selectedIndex = -1;
                selectedAudioIndex = -1;
                invalidate();
                if (lt[0] == 0) listener.onOverlayLayerTapped(lt[1]);
                else if (lt[0] == 1) listener.onVisualizerLayerTapped(lt[1]);
                else listener.onCaptionLayerTapped(lt[1]);
            }
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }

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
        } else if (last == Drag.AUDIO_LEFT_HANDLE || last == Drag.AUDIO_RIGHT_HANDLE) {
            // Audio trim finished — data was already applied during drag
            if (listener != null) {
                listener.onAudioTrimFinished(selectedAudioIndex, audioTrimDragInMs, audioTrimDragOutMs);
            }
        } else if ((last == Drag.LAYER_LEFT_HANDLE || last == Drag.LAYER_RIGHT_HANDLE)
                && activeLayerIndex >= 0 && activeLayerIndex < overlays.size()) {
            TextOverlayItem overlay = overlays.get(activeLayerIndex);
            if (listener != null) {
                listener.onOverlayRangeFinished(activeLayerIndex,
                        overlay.getStartMs(), overlay.getEndMs());
            }
        } else if (last == Drag.TRANSITION_LEFT_HANDLE || last == Drag.TRANSITION_RIGHT_HANDLE) {
            if (listener != null) {
                long newDur = transitionDurationFromDragX(transitionDragX);
                listener.onTransitionDurationFinished(transitionDragIndex, newDur);
            }
        } else if (last == Drag.LAYER_KEYFRAME
                && activeLayerIndex >= 0 && activeLayerIndex < overlays.size()) {
            if (listener != null) {
                listener.onOverlayKeyframeMoveFinished(activeLayerIndex,
                        layerDragInitialKeyLocalMs, layerDragOriginalKeyLocalMs);
            }
        } else if (isUp && downSegIndex >= 0) {
            if (Math.abs(x - downX) < touchSlopPx) {
                // Tapping a yellow silence candidate converts it to a cut.
                if (showSilence && tryTapSilenceCandidate(downSegIndex, downX)) {
                    // consumed — don't change selection
                } else {
                    // Move the playhead to the tap so play resumes EXACTLY here
                    // (previously a tap only selected, leaving the playhead — and
                    // thus playback — at the old position).
                    seekToTimelineMs(xToTime(downX + scrollOffsetPx));
                    // Toggle selection: deselect if same segment, select if different
                    if (downSegIndex == selectedIndex) {
                        selectedIndex = -1;
                        invalidate();
                        if (listener != null) listener.onSegmentSelected(-1);
                    } else {
                        selectedIndex = downSegIndex;
                        if (selectedAudioIndex >= 0) {
                            selectedAudioIndex = -1;
                            if (listener != null) listener.onAudioClipSelected(-1);
                        }
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
        activeLayerIndex = -1;
        layerDragInitialKeyLocalMs = -1;
        layerDragOriginalKeyLocalMs = -1;
        getParent().requestDisallowInterceptTouchEvent(false);
        return true;
    }

    // ── Touch helpers ────────────────────────────────────────────────

    private Drag hitTestHandle(float x, float y) {
        if (selectedIndex < 0 || selectedIndex >= segRects.size()) return Drag.NONE;
        RectF seg = segRects.get(selectedIndex);
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

    /**
     * Hit-test trim handles on the selected audio clip.
     */
    private Drag hitTestAudioHandle(float x, float y) {
        if (selectedAudioIndex < 0 || selectedAudioIndex >= audioClipRects.size()) return Drag.NONE;
        RectF r = audioClipRects.get(selectedAudioIndex);
        float hTop = r.top - handleOverhangPx;
        float hBot = r.bottom + handleOverhangPx;

        if (y < hTop - touchSlopPx / 2 || y > hBot + touchSlopPx / 2) return Drag.NONE;

        if (x >= r.left - touchSlopPx / 2 && x <= r.left + handleWidthPx + touchSlopPx / 2) {
            return Drag.AUDIO_LEFT_HANDLE;
        }
        if (x >= r.right - handleWidthPx - touchSlopPx / 2 && x <= r.right + touchSlopPx / 2) {
            return Drag.AUDIO_RIGHT_HANDLE;
        }
        return Drag.NONE;
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

    /**
     * Handles audio clip trim handle drag, updating in/out points visually.
     * Data is committed on ACTION_UP via onAudioTrimChanged callback.
     */
    private void doAudioTrimDrag(float x) {
        if (selectedAudioIndex < 0 || selectedAudioIndex >= audioClips.size()) return;
        AudioClip ac = audioClips.get(selectedAudioIndex);
        long srcDur = ac.getSourceDurationMs();
        if (srcDur <= 0) return;

        RectF rect = audioClipRects.get(selectedAudioIndex);
        float pxPerMs = dpPerSecondPx / 1000f;
        long minGap = 500; // minimum 500ms

        boolean isLeft = (activeDrag == Drag.AUDIO_LEFT_HANDLE);
        // PHASE-R R2: no-overlap law on legacy-lane audio trims. Extent grows rightward
        // from the fixed offsetMs whenever (out - in) grows, on EITHER handle — clamp
        // the growth at the same-lane sibling ceiling (never forcing an un-trim of a
        // pre-existing overlap); the 500ms minimum always wins.
        long curEnd = ac.getOffsetMs() + Math.max(0, ac.getTrimmedDurationMs());
        if (isLeft) {
            float deltaX = x - rect.left;
            long deltaMs = (long) (deltaX / pxPerMs);
            long newIn = Math.max(0, Math.min(ac.getOutPointMs() - minGap, ac.getInPointMs() + deltaMs));
            long ceil = audioSiblingCeil(ac, ac.getOffsetMs() + (ac.getOutPointMs() - newIn), curEnd);
            newIn = Math.max(newIn, ac.getOffsetMs() + ac.getOutPointMs() - ceil);
            newIn = Math.max(0, Math.min(newIn, ac.getOutPointMs() - minGap));
            audioTrimDragInMs = newIn;
            audioTrimDragOutMs = ac.getOutPointMs();
        } else {
            float deltaX = x - rect.right;
            long deltaMs = (long) (deltaX / pxPerMs);
            long newOut = Math.max(ac.getInPointMs() + minGap,
                    Math.min(srcDur, ac.getOutPointMs() + deltaMs));
            long ceil = audioSiblingCeil(ac, ac.getOffsetMs() + (newOut - ac.getInPointMs()), curEnd);
            newOut = Math.min(newOut, ceil - ac.getOffsetMs() + ac.getInPointMs());
            newOut = Math.max(newOut, ac.getInPointMs() + minGap);
            audioTrimDragInMs = ac.getInPointMs();
            audioTrimDragOutMs = newOut;
        }
        audioTrimDragX = x;

        // Apply trim changes immediately for visual feedback
        ac.setInPointMs(audioTrimDragInMs);
        ac.setOutPointMs(audioTrimDragOutMs);
        computeRects();
        invalidate();

        if (listener != null) {
            listener.onAudioTrimChanged(selectedAudioIndex, audioTrimDragInMs, audioTrimDragOutMs, isLeft);
        }
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

    /**
     * PHASE-R R2 (the user's audio-stacking repro lived on THIS legacy lane — the
     * row-system got its resolver in c7442ae/caa628e, but the legacy waveform-lane
     * long-press drag wrote {@code setOffsetMs} raw): resolve a dropped audio offset
     * against same-lane siblings with the SAME butting rule as
     * {@code LayerGestureController#resolveOverlapOnRow} — push to the nearer legal
     * butt edge, multi-pass for chained pushes. Returns {@link Long#MIN_VALUE} when no
     * non-overlapping position was found (caller snaps back to the drag origin,
     * matching the established cancel behavior).
     */
    private long resolveAudioDropOffset(AudioClip moved, long desiredOffset) {
        long dur = Math.max(0, moved.getTrimmedDurationMs());
        if (dur <= 0) return desiredOffset;
        long start = desiredOffset;
        for (int pass = 0; pass < 4; pass++) {
            boolean pushed = false;
            for (AudioClip sib : audioClips) {
                if (sib == moved || !sameAudioLane(moved, sib)) continue;
                long ss = sib.getOffsetMs();
                long se = ss + Math.max(0, sib.getTrimmedDurationMs());
                if (start < se && start + dur > ss) {
                    long before = ss - dur;
                    long after = se;
                    start = (before >= 0
                            && Math.abs(desiredOffset - before) <= Math.abs(desiredOffset - after))
                            ? before : after;
                    pushed = true;
                }
            }
            if (!pushed) {
                return Math.max(0, start);
            }
        }
        return Long.MIN_VALUE; // still colliding after 4 passes → snap-back
    }

    /** Same-lane test for the legacy overlay lane's no-overlap clamps (null layerId groups as "text"). */
    private boolean sameLayerLane(TextOverlayItem a, TextOverlayItem b) {
        String ka = a.getLayerId() == null ? "text" : a.getLayerId();
        String kb = b.getLayerId() == null ? "text" : b.getLayerId();
        return ka.equals(kb);
    }

    /** Lowest legal start for a LEFT-handle trim: may not cross into a same-lane sibling. */
    private long layerSiblingFloor(TextOverlayItem ov, long proposedStart, long endMs) {
        long floor = proposedStart;
        for (TextOverlayItem sib : overlays) {
            if (sib == ov || !sameLayerLane(ov, sib)) continue;
            long ss = Math.max(0, sib.getStartMs());
            long se = sib.getEndMs() == Long.MAX_VALUE ? totalEffectiveMs : sib.getEndMs();
            if (ss < endMs && se > proposedStart) floor = Math.max(floor, se);
        }
        // Pre-existing overlaps (created before this law) must not force an un-trim.
        return Math.min(floor, Math.max(0, endMs - 250));
    }

    /** Highest legal end for a RIGHT-handle trim: may not cross into a same-lane sibling. */
    private long layerSiblingCeil(TextOverlayItem ov, long startMs, long proposedEnd) {
        long ceil = proposedEnd;
        for (TextOverlayItem sib : overlays) {
            if (sib == ov || !sameLayerLane(ov, sib)) continue;
            long ss = Math.max(0, sib.getStartMs());
            long se = sib.getEndMs() == Long.MAX_VALUE ? totalEffectiveMs : sib.getEndMs();
            if (se > startMs && ss < proposedEnd) ceil = Math.min(ceil, ss);
        }
        return Math.max(ceil, startMs + 250);
    }

    private void doLayerDrag(float x) {
        if (activeLayerIndex < 0 || activeLayerIndex >= overlays.size()
                || totalEffectiveMs <= 0) {
            return;
        }

        TextOverlayItem overlay = overlays.get(activeLayerIndex);
        long t = clampLayerTime(xToTime(x));
        long minGapMs = 250;

        if (activeDrag == Drag.LAYER_LEFT_HANDLE) {
            long endMs = layerDragEndMs;
            long newStart = Math.min(t, Math.max(0, endMs - minGapMs));
            // No-overlap law (dragux_v3 A8, 2026-07-04 user repro): the LEGACY overlay
            // lane bypassed the row-system guards entirely — clamp the moving edge
            // against same-layer siblings so a trim can never extend into one.
            newStart = Math.max(newStart, layerSiblingFloor(overlay, newStart, endMs));
            overlay.setTimeRange(newStart,
                    overlay.getEndMs() == Long.MAX_VALUE ? Long.MAX_VALUE : endMs);
            if (listener != null) {
                listener.onOverlayRangeChanged(activeLayerIndex,
                        overlay.getStartMs(), overlay.getEndMs(), true);
            }
        } else if (activeDrag == Drag.LAYER_RIGHT_HANDLE) {
            long startMs = layerDragStartMs;
            long newEnd = Math.max(t, startMs + minGapMs);
            newEnd = Math.min(newEnd, Math.max(startMs + minGapMs, totalEffectiveMs));
            newEnd = Math.min(newEnd, layerSiblingCeil(overlay, startMs, newEnd));
            overlay.setTimeRange(startMs, newEnd >= totalEffectiveMs ? Long.MAX_VALUE : newEnd);
            if (listener != null) {
                listener.onOverlayRangeChanged(activeLayerIndex,
                        overlay.getStartMs(), overlay.getEndMs(), false);
            }
        } else if (activeDrag == Drag.LAYER_KEYFRAME) {
            long startMs = Math.max(0, overlay.getStartMs());
            long endMs = displayEndMs(overlay);
            long newLocal = Math.max(0, Math.min(t - startMs, Math.max(0, endMs - startMs)));
            if (overlay.moveKeyframeLocalTime(layerDragOriginalKeyLocalMs, newLocal)) {
                if (listener != null) {
                    listener.onOverlayKeyframeMoved(activeLayerIndex,
                            layerDragOriginalKeyLocalMs, newLocal);
                }
                layerDragOriginalKeyLocalMs = newLocal;
            }
        }

        invalidate();
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
        long maxSpan = Math.max(100, Math.min(leftDur, rightDur));
        return Math.max(100, Math.min(2000, Math.min(maxSpan, newDuration)));
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

    /**
     * Hit-tests audio clip rects. Returns the index of the audio clip under
     * the given coordinates, or -1 if none.
     */
    private int hitTestAudioClip(float x, float y) {
        if (audioClipRects.isEmpty()) return -1;
        float audioTop = audioBandTopPx();
        float audioBot = audioBandBotPx();
        if (y < audioTop - touchSlopPx / 2 || y > audioBot + touchSlopPx / 2) return -1;
        // With stacked lanes, match the actual per-clip rect (x AND y), not just x.
        for (int i = 0; i < audioClipRects.size(); i++) {
            RectF r = audioClipRects.get(i);
            if (x >= r.left && x <= r.right
                    && y >= r.top - touchSlopPx / 2 && y <= r.bottom + touchSlopPx / 2) {
                return i;
            }
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
        FLog.d(TAG, "updatePlayheadFromX: x=" + x);
        
        long timelineEndMs = getTimelineEndMs();
        long newPlayheadMs = xToTime(playheadX);
        newPlayheadMs = Math.max(0, Math.min(newPlayheadMs, timelineEndMs));
        playheadPositionMs = newPlayheadMs;
        
        FLog.d(TAG, "updatePlayheadFromX: playheadPositionMs=" + playheadPositionMs + "ms");
        
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
            
            FLog.d(TAG, "updatePlayheadFromX: targetSegment=" + targetSegment 
                    + " posInSegmentMs=" + posInSegmentMs + " sourceFrac=" + sourceFrac);
            
            // Pass isDragging=true to prevent loading new clips during active drag
            // The FaditorEditorActivity will only seek within current clip, then load new one on drag end
            listener.onPlayheadSeeked(targetSegment, sourceFrac, true);
        }
        
        centerPlayhead();
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
        dragGhostPaint.setColor(0x554CAF50);
        dragGhostPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(Math.max(0, segRects.get(transitionDragSeam).left - scrollOffsetPx), tTop,
                sx, tBot, dragGhostPaint);
        canvas.drawRect(sx, tTop,
                Math.min(viewW, segRects.get(transitionDragSeam + 1).right - scrollOffsetPx), tBot,
                dragGhostPaint);
        // Bright seam bar where it will land.
        float halfW = 5f * density;
        dragGhostPaint.setColor(0xFF4CAF50);
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
    
    // ── Gesture listeners ────────────────────────────────────────────
    
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float initialZoom;
        private float scaleAccumulator = 1f;
        
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            FLog.d(TAG, "ScaleListener.onScaleBegin: zoom=" + zoomLevel);
            isScaling = true;  // Set flag to block other touches
            // Cancel any pending reorder/audio-drag long-press — this is a pinch.
            longPressHandler.removeCallbacks(longPressRunnable);
            longPressHandler.removeCallbacks(audioLongPressRunnable);
            pendingAudioIndex = -1;
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
            
            FLog.d(TAG, "ScaleListener.onScale: scaleFactor=" + detector.getScaleFactor() + " zoomLevel=" + zoomLevel);
            
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
            FLog.d(TAG, "GestureListener.onDown");
            // Cancel any ongoing fling
            if (!flingScroller.isFinished()) {
                flingScroller.abortAnimation();
            }
            return false; // Let custom onDown handle it
        }
        
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            FLog.d(TAG, "GestureListener.onScroll: distanceX=" + distanceX + " activeDrag=" + activeDrag);
            // Cancel long press — user is scrolling, not holding
            longPressHandler.removeCallbacks(longPressRunnable);
            // Cancel audio long-press too — prevents false-positive audio drag
            // when the user is scrolling through the timeline and their finger
            // happens to pass over an audio clip (pendingAudioIndex was set in
            // onDown but the onMove dispatch is bypassed by the gesture detector).
            longPressHandler.removeCallbacks(audioLongPressRunnable);
            if (!audioLongPressTriggered) pendingAudioIndex = -1;
            // Only handle scroll if not dragging handles
            if (activeDrag != Drag.NONE) {
                FLog.d(TAG, "GestureListener.onScroll: ignoring, activeDrag=" + activeDrag);
                return false;
            }
            
            // Horizontal drag on timeline = seek playhead
            float centerX = getWidth() / 2f;
            float newPlayheadX = centerX + scrollOffsetPx + distanceX;
            
            FLog.d(TAG, "GestureListener.onScroll: newPlayheadX=" + newPlayheadX);
            
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
