package com.fadcam.ui.faditor;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.slider.Slider;
import android.widget.Toast;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.Player;
import androidx.media3.transformer.ExportResult;
import androidx.media3.ui.PlayerView;

import com.fadcam.R;
import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.playback.FragmentedMp4Remuxer;
import com.fadcam.ui.InputActionBottomSheetFragment;
import com.fadcam.ui.faditor.assetbrowser.AssetItem;
import com.fadcam.ui.faditor.export.ExportManager;
import com.fadcam.ui.faditor.compositor.MasterPlaybackEngine;
import com.fadcam.ui.faditor.export.ExportService;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.gltransitions.GlTransitionPreviewView;
import com.fadcam.ui.faditor.player.FaditorPlayerManager;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.timeline.EditorTimelineView;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.Transition;
import com.fadcam.ui.faditor.undo.EditActions;
import com.fadcam.ui.faditor.undo.UndoManager;
import com.fadcam.ui.faditor.util.SilenceDetector;
import com.fadcam.ui.faditor.util.TimeFormatter;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaPlayer;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen video editor Activity for Faditor Mini.
 *
 * <p>Receives a video URI via intent data, creates an in-memory project,
 * and provides trim + export functionality using Media3.</p>
 */
public class FaditorEditorActivity extends AppCompatActivity {

    private static final String TAG = "FaditorEditor";

    /** Intent extra key for the video URI string. */
    public static final String EXTRA_VIDEO_URI = "faditor_video_uri";

    /** Intent extra key for multiple video URIs opened sequentially in Faditor. */
    public static final String EXTRA_VIDEO_URIS = "faditor_video_uris";

    /** Intent extra key for opening a saved project by ID. */
    public static final String EXTRA_PROJECT_ID = "faditor_project_id";

    /** Default duration for still image clips (milliseconds). */
    private static final long IMAGE_CLIP_DURATION_MS = 5000;

    @Nullable
    private List<Uri> initialVideoUris;

    // ── Core components ──────────────────────────────────────────────
    private FaditorProject project;
    private FaditorPlayerManager playerManager;
    private ExportManager exportManager;
    private SharedPreferencesManager prefsManager;
    private FragmentedMp4Remuxer remuxer;
    /** L2: cache of baked TRUE-reversed segments for PING_PONG loop legs (preview==export source). */
    private com.fadcam.ui.faditor.export.ReversedSegmentCache reversedCache;
    /** Off-main executor for baking reversed segments (drawer path). */
    @Nullable
    private java.util.concurrent.ExecutorService reverseBakeExecutor;
    /** Source-URI+in+out keys whose reverse bake is in flight, so we don't double-launch. */
    private final java.util.Set<String> reverseBakeInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** Whether the "reverse for long loops coming later" guard toast has been shown this session. */
    private boolean reverseLongGuardToastShown = false;
    /**
     * RANK-1 resilience: per-session set of baked-reversed URIs that FAILED to decode in the gapless
     * player. {@link #resolveReversedUri} returns null for any poisoned URI, so that PING_PONG clip
     * degrades to forward reps ONLY (scoped, not project-wide) on the next playlist rebuild, and the
     * decode-failure blackout can never spread. Cleared only when the app process dies (a clip re-trim
     * yields a NEW reversed URI/key, so a fresh bake is naturally re-tried).
     */
    private final java.util.Set<Uri> poisonedReversedUris =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /**
     * RANK-1c rebuild-race guard: monotonically increasing token bumped on EVERY user-initiated
     * gapless rebuild (trim/loop edit, mode change, poison recovery). {@link #kickReverseBakeIfNeeded}
     * captures this at kick time; the bake-complete auto-promote rebuild is DISCARDED if the token
     * advanced since (a later user edit already rebuilt), so a stale promote can't revert the timeline.
     */
    private volatile int rebuildGeneration = 0;

    // ── Export status (OOP) ──────────────────────────────────────────
    // ExportService runs in its own :export process — no binding (a cross-process
    // Binder cast would throw), no static bridge. Status arrives as package-scoped
    // global broadcasts; this local timestamp bridges the tap→foreground-notification
    // window so isExportRunning() can't double-start. Time-bounded (not a plain flag)
    // so a silent :export-process death can never wedge future exports.
    private static final long EXPORT_START_GRACE_MS = 30_000;
    private volatile long exportStartedLocallyAtMs = 0;
    private boolean exportEventsReceiverRegistered = false;
    private final android.content.BroadcastReceiver exportEventsReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context,
                        android.content.Intent intent) {
                    String action = intent.getAction();
                    if (action == null) return;
                    switch (action) {
                        case ExportService.ACTION_EXPORT_STARTED:
                            exportUiOnStarted();
                            break;
                        case ExportService.ACTION_EXPORT_PROGRESS:
                            exportUiOnProgress(intent.getFloatExtra(ExportService.EXTRA_PROGRESS, 0f));
                            break;
                        case ExportService.ACTION_EXPORT_COMPLETED:
                            exportStartedLocallyAtMs = 0;
                            exportUiOnCompleted(intent.getStringExtra(ExportService.EXTRA_OUTPUT_PATH));
                            break;
                        case ExportService.ACTION_EXPORT_ERROR:
                            exportStartedLocallyAtMs = 0;
                            exportUiOnError(intent.getStringExtra(ExportService.EXTRA_ERROR_MESSAGE));
                            break;
                        case ExportService.ACTION_EXPORT_CANCELLED:
                            exportStartedLocallyAtMs = 0;
                            break;
                    }
                }
            };

    // ── Asset pickers ────────────────────────────────────────────────
    private ActivityResultLauncher<Intent> imagePickerLauncher;
    private ActivityResultLauncher<Intent> videoPickerLauncher;
    private ActivityResultLauncher<Intent> overlayImagePickerLauncher;
    private ActivityResultLauncher<Intent> audioPickerLauncher;

    // ── Views ────────────────────────────────────────────────────────
    private PlayerView playerView;
    /** MISSING overlay in the preview area (shown when source is inaccessible). */
    @Nullable private View missingOverlayView;
    /** LRU cache for source resolvability checks (avoids repeated FS/ContentResolver calls). */
    @NonNull private final java.util.LinkedHashMap<android.net.Uri, Boolean> resolvableCache =
            new java.util.LinkedHashMap<android.net.Uri, Boolean>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<android.net.Uri, Boolean> eldest) {
                    return size() > 100;
                }
            };
    /** Live, scrubbable WebView preview for AI-generated slide clips. */
    private com.fadcam.ui.faditor.slides.GeneratedSlideView slidePreview;
    /** Clip id whose HTML is currently loaded into {@link #slidePreview}. */
    private String loadedSlideClipId;
    /** Backdrop drawing the canvas rect (black) + out-of-canvas hatch. */
    private com.fadcam.ui.faditor.player.CanvasFrameView canvasFrame;
    /** Preview-only 9:16 safe-zone guide (road_map "small never-built features"); never exported. */
    private com.fadcam.ui.faditor.player.SafeZoneOverlayView safeZoneOverlay;
    private FrameLayout playerContainer;
    private com.fadcam.ui.faditor.player.TransitionPreviewOverlayView transitionPreviewOverlay;
    private GlTransitionPreviewView glTransitionPreviewView;
    private com.fadcam.ui.faditor.overlay.TextOverlayLayer overlayLayer;
    /** IMAGE-track layer preview surface (M-COMP-1; PLAN §3.2 scope item 4). */
    private com.fadcam.ui.faditor.compositor.LayerImageOverlayView layerImageOverlay;
    /** Sprite preview surface (S4): resolver-driven, above video, below text/captions. */
    private com.fadcam.ui.faditor.sprite.SpriteOverlayView spriteOverlayView;
    /** Live overlay-video (PiP) preview surface (M-COMP-2; plan §3.3). */
    private com.fadcam.ui.faditor.compositor.OverlayVideoPreviewView overlayVideoLayer;
    /** The next videoPickerLauncher result creates a PiP overlay, not a master clip. */
    private boolean overlayVideoPickerPending;
    /** The next overlayImagePickerLauncher result creates a NEW layer track for the
     *  image (P1 reliable path), not an overlay on the default text track. */
    private boolean imageAsNewLayerPending;
    /** Decode-once sprite sheet renderers for the preview, keyed by sheetId. The
     *  paired SpriteSheet reference validates the cache across project reloads
     *  (new model objects → stale entry recycled + re-decoded). */
    private final java.util.Map<String, android.util.Pair<com.fadcam.ui.faditor.sprite.SpriteSheet,
            com.fadcam.ui.faditor.sprite.SpriteSheetRenderer>> spriteRendererCache =
            new java.util.HashMap<>();
    /** Text overlays whose ADD has already been recorded for undo (avoid double-record on re-edit). */
    private final java.util.Set<com.fadcam.ui.faditor.model.TextOverlayItem> textOverlayAddRecorded =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Before-snapshot captured at the start of an overlay timeline-handle drag (range edge or keyframe), for undo. */
    private com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot overlayTimelineDragBefore;
    private int overlayTimelineDragIndex = -1;
    private com.fadcam.ui.faditor.waveform.WaveformOverlayView waveformOverlayView;
    private com.fadcam.ui.faditor.waveform.WaveformExtractor waveformExtractor;
    private final java.util.Map<String, com.fadcam.ui.faditor.model.WaveformData> waveformDataBySource =
            new java.util.HashMap<>();
    private final java.util.Set<String> waveformExtractInFlight = new java.util.HashSet<>();

    // ── Transcript editing ───────────────────────────────────────────
    private com.fadcam.ui.faditor.transcript.TranscriptionEngine transcriptionEngine;
    private com.fadcam.ui.faditor.transcript.Transcript currentTranscript;
    private String transcriptClipId;
    private boolean transcriptIsForAudio = false;
    private int transcriptAudioIndex = -1;
    /** Target (trim-relative ms) of an in-flight live-skip seek, or -1. Prevents
     *  re-issuing the seek every tick (which caused a play/pause flicker). */
    private long pendingSkipSeekTarget = -1;
    private View transcriptPanel;
    private com.fadcam.ui.faditor.transcript.TranscriptPanelView transcriptView;
    private View transcriptReopenTab;
    private android.widget.TextView transcriptBreakBtn;
    private View transcriptProgress;
    private TextView transcriptProgressText;
    private com.google.android.material.progressindicator.LinearProgressIndicator transcriptProgressBar;
    private TextView transcriptStatusBadge;
    private com.google.android.material.progressindicator.LinearProgressIndicator transcriptGlobalProgressBar;
    private View toolTranscript;
    private final Map<com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType, Integer> activeTranscriptionModels = new LinkedHashMap<>();
    private ValueAnimator transcriptionPulseAnimator;
    private int transcriptionPulseIndex = 0;
    private View transcriptModelChoice;
    private android.widget.LinearLayout transcriptVersionBar;
    private View transcriptVersionScroll;
    /** Most recent absolute playhead time (ms) — used as the keyframe time base. */
    private long lastPlayheadAbsoluteMs = 0;
    private long lastPositionInSegmentMs = 0;
    private long lastSourcePositionInSegmentMs = 0;

    // Animated captions
    private com.fadcam.ui.faditor.transcript.CaptionOverlayView captionOverlay;
    private com.fadcam.ui.faditor.transcript.CaptionOverlayView audioCaptionOverlay;
    private View captionStyleBar;
    private boolean captionsActive;
    /** Clip the active caption overlay is bound to (for persisting its settings). */
    private String captionClipId;
    /** Audio clip the audio caption overlay is bound to. */
    private String audioCaptionClipId;
    /** Which overlay was most recently tapped (for style-bar routing). */
    private boolean activeCaptionIsAudio = false;
    /** Caption-style chips by style id, so we can highlight the active clip's style. */
    private final java.util.Map<String, TextView> captionStyleChips = new java.util.HashMap<>();
    /** Currently highlighted caption-style chip id (null = none). */
    private String highlightedCaptionStyleId;
    private EditorTimelineView editorTimeline;
    private TextView btnPlayPause;
    private TextView editorTitle;
    private TextView timeCurrent;
    private TextView timeTotal;
    private boolean transcriptPanelOpen = false;
    private boolean transitionPanelOpen = false;
    private View transitionPanel;
    /** The collapsible GL-transitions row + its "More effects" affordance label (pull-down to reveal). */
    @Nullable private View transitionGlRow;
    @Nullable private TextView transitionGlToggleLabel;
    private boolean transitionGlExpanded = false;
    private boolean visualizerDrawerOpen = false;
    private boolean captionDrawerOpen = false;
    private boolean captionDrawerChromeWired = false;
    private final HashMap<Transition.Type, View> transitionTypeViews = new HashMap<>();

    // Asset Browser
    /** Insert affordance bar shown above the panel when an asset is selected. */
    private View insertAffordanceBar;
    /** The asset browser panel (dropdown from top). Null when not created. */
    private com.fadcam.ui.faditor.assetbrowser.AssetBrowserPanel assetBrowserPanel;
    /** Launcher for picking a pinned directory (SAF tree URI). */
    private androidx.activity.result.ActivityResultLauncher<android.net.Uri> assetDirPickerLauncher;
    /** Currently selected asset for the insert-at-playhead button. */
    @Nullable
    private com.fadcam.ui.faditor.assetbrowser.AssetItem selectedAsset;
    @Nullable
    private AssetItem assetDragItem;
    @Nullable
    private View assetDragSourceView;
    @Nullable
    private TextView assetDragView;
    private boolean assetDragActive;
    private int assetDragInsertIndex = -1;
    private int assetDragLastInsertIndex = -1;
    private View exportProgressOverlay;
    private TextView exportProgressText;
    private TextView exportProgressPercent;
    private TextView exportEtaText;
    private TextView exportStatusIcon;
    private TextView exportBtnDone;
    private TextView exportTitle;
    private com.google.android.material.progressindicator.LinearProgressIndicator exportProgressBar;
    private TextView exportInfoText;
    private ExportProgressStripeView exportProgressStripe;
    private long exportStartTimeMs;
    private View remuxProgressOverlay;
    private TextView remuxProgressText;
    private com.fadcam.ui.faditor.crop.CropOverlayView cropOverlay;
    private android.widget.ImageView imagePreview;

    // ── Crop mode state ──────────────────────────────────────────────
    private boolean inCropMode = false;
    // Last known non-zero decoded video size. getPlayer().getVideoSize() returns 0
    // right after a seek/clip-switch, which made the crop-zoom preview flip between
    // "filled" and "not filled" as it recomputed against a bogus size. We fall back
    // to this cache so preview geometry stays stable. Updated in onVideoSizeChanged.
    private int lastDecodedVideoW = 0;
    private int lastDecodedVideoH = 0;
    private long lastBackPressTime = 0;
    private static final long BACK_PRESS_INTERVAL_MS = 2000;
    private String preCropPreset;           // saved on entering crop mode
    private float preCropLeft, preCropTop, preCropRight, preCropBottom; // saved custom bounds
    // Saved overlay visibilities while in crop mode (so we can't accidentally
    // nudge the visualizer / captions while dragging crop handles).
    private int preCropVisualizerVis = View.VISIBLE;
    private int preCropCaptionVis = View.GONE;
    private int preCropAudioCaptionVis = View.GONE;
    private View cropToolbar;
    private View controlsSection;
    private TextView cropSnapCenter;
    private TextView cropAutoCrop;
    private boolean cropSnapToCenter = false;

    // ── Tool buttons ─────────────────────────────────────────────────
    private View toolSpeed;
    private View toolMute;
    private TextView toolMuteIcon;
    private TextView toolMuteLabel;
    // Center HUD shown while dragging the Volume tool (green icon + % + finger-following fader).
    private View volumeDragHud;
    private com.fadcam.ui.faditor.VolumeDragFaderView volumeDragFader;
    private TextView volumeDragHudIcon;
    private TextView volumeDragHudValue;
    // Volume top-drawer (tap the Volume tool): scrub-tracking bar + keyframe stopwatch + carets.
    private View volumeDrawer;
    private com.fadcam.ui.faditor.VolumeBarView volumeBar;
    private TextView volumeDrawerIcon, volumeDrawerValue, volumeDrawerKeyframe, volumeDrawerMute;
    private boolean volumeDrawerOpen = false;
    private boolean volumeDrawerWired = false;
    // Opacity tool (mirrors the volume automation pattern for clip opacity keyframes).
    private View toolOpacity;
    private TextView toolOpacityIcon;
    private TextView toolOpacityLabel;
    // Captions tool-row cell (armed-state tint mirrors toolMuteIcon/toolOpacityIcon below).
    private TextView toolCaptionsIcon;
    private TextView toolCaptionsLabel;
    // Opacity top-drawer: scrub-tracking slider + keyframe stopwatch + carets.
    private View opacityDrawer;
    private SeekBar opacitySlider;
    private TextView opacityDrawerIcon, opacityDrawerValue, opacityDrawerKeyframe;
    private boolean opacityDrawerOpen = false;
    private boolean opacityDrawerWired = false;
    // Word scrub drawer (non-modal top drawer for scrub-to-retime)
    private View wordScrubDrawer;
    private com.fadcam.ui.faditor.WordScrubView wordScrubStrip;
    private android.widget.EditText wordScrubWordText;
    private TextView wordScrubTimestamp, wordScrubPrev, wordScrubNext, wordScrubCenter;
    private boolean wordScrubDrawerOpen = false;

    // Move drawer (position/layer drawer)
    private View moveDrawer;
    private TextView movePositionTimestamp, movePositionSeconds, movePositionFrames, movePositionLayer;
    private android.widget.EditText moveTargetInput;
    private TextView moveGo, moveLayerUp, moveLayerDown;
    private TextView moveClipStart, moveClipLeft, moveClipRight, moveClipEnd;
    private boolean moveDrawerOpen = false;
    private int wordScrubCurrentIndex = -1;
    /** How many sequential words are linked in the current scrub session (1 = single word). */
    private int wordScrubGroupLength = 1;
    private long wordScrubAnchorMs;
    private long[] wordScrubOriginalStarts;
    private TextView toolSpeedLabel;
    private View toolRotate;
    private TextView toolRotateIcon;
    private TextView toolRotateLabel;
    private View toolFlip;
    private TextView toolFlipIcon;
    private TextView toolFlipLabel;
    private View toolCrop;
    private TextView toolCropIcon;
    private TextView toolCropLabel;
    private View toolCanvas;
    private TextView toolCanvasIcon;
    private TextView toolCanvasLabel;
    private View toolAudio;
    private TextView toolAudioIcon;
    private TextView toolAudioLabel;
    private TextView toolSplitIcon;
    private TextView toolSplitLabel;
    private boolean splitHealMode = false;
    private TextView btnSoftSnap;
    private boolean overlaySoftSnapEnabled = true;
    /** PHASE-P P3 (M11): master ripple/gap edit-mode toggle button. */
    private TextView btnRippleMode;
    private View toolMove;
    private TextView toolMoveIcon, toolMoveLabel;
    // Data-driven bottom tools carousel (Stage 1).
    private com.fadcam.ui.faditor.tools.FaditorToolsAdapter toolsAdapter;
    // Swipe-up all-tools drawer (Stage 2).
    private com.fadcam.ui.faditor.tools.FaditorToolsDrawer toolsDrawer;
    // Tool order/pin/recency persistence (Stage 3).
    private com.fadcam.ui.faditor.tools.FaditorToolPrefs toolPrefs;
    // Loop tool + drawer
    private View toolLoop;
    private TextView toolLoopIcon, toolLoopLabel;
    private View loopDrawer;
    /** L3: scrolls the drawer's content (mode chips + extend rows) under the pinned
     *  grab-handle/header so the drawer never clips off the bottom of a short screen —
     *  see {@link #showLoopDrawer()} for the runtime height cap. */
    private androidx.core.widget.NestedScrollView loopDrawerScroll;
    private TextView loopDrawerIcon, loopDrawerModeLabel;
    private View loopModeOff, loopModeNormal, loopModeStill, loopModePingpong;
    private View loopExtendStart, loopExtendEnd, loopExtendPrev, loopExtendNext;
    /** Accumulated visual offset (ms) for loop/ping-pong playback cycles, used to
     *  advance the timeline playhead through the extension region while the player
     *  loops the source content naturally. Reset on segment advance or stop. */
    private long loopVisualOffsetMs = 0;
    /** Wall-clock timestamp (SystemClock.elapsedRealtime) when the Still-mode
     *  extension region was entered, or -1 if not in the extension. */
    private long loopStillExtensionStartMs = -1;
    /** Prevents re-entering the loop-restart path while the seek is still
     *  being applied by ExoPlayer (avoiding double-increment of offset). */
    private boolean loopRestartPending = false;
    private boolean loopDrawerOpen = false;
    private boolean loopDrawerWired = false;

    // ── Undo/Redo ────────────────────────────────────────────────────
    private UndoManager undoManager;
    private TextView btnUndo;
    private TextView btnRedo;
    private TextView btnRelinkMedia;

    // ── Relink catalog ───────────────────────────────────────────────
    private RelinkCatalogBottomSheet relinkCatalogSheet;
    private int relinkPendingIndex = -1;

    // Pre-edit state capture for undo recording
    private long preTrimInMs = -1;
    private long preTrimOutMs = -1;
    // Throttle for the live main-video trim-edge preview seek (see onTrimChanged).
    private long lastTrimPreviewSeekMs = 0;
    private static final long TRIM_PREVIEW_SEEK_THROTTLE_MS = 45;
    private long preAudioTrimInMs = -1;
    private long preAudioTrimOutMs = -1;

    // ── Multi-segment state ──────────────────────────────────────────
    /** Index of the currently selected clip/segment in the timeline. */
    private int selectedClipIndex = 0;
    private boolean transitionPlaybackActive = false;
    private long transitionPlaybackStartPositionMs = 0;
    private long transitionPlaybackDurationMs = 0;
    private int transitionPlaybackSeam = -1;
    private MediaMetadataRetriever transitionRetriever;
    private String transitionRetrieverUri;
    private Bitmap transitionLastBitmap;
    private long transitionLastSourceMs = -1;

    // ── Audio extraction ─────────────────────────────────────────────
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();

    // Off-main-thread file copy + duration probe for imported assets. These ops
    // (full URI copy, FFprobe/MediaMetadataRetriever) can block for seconds on a
    // SAF/content URI and were ANR-ing the main thread on every video insert.
    private final ExecutorService assetImportExecutor = Executors.newSingleThreadExecutor();

    /** MediaPlayers for audio clips — one per clip, synced with playhead. */
    private final List<MediaPlayer> audioPlayers = new ArrayList<>();
    /** Whether each audioPlayer is prepared and ready. */
    private final List<Boolean> audioPlayersReady = new ArrayList<>();

    /**
     * When ON (armed), changing the volume of the selected audio clip writes a volume
     * KEYFRAME at the current playhead (the blue envelope) instead of setting the
     * whole-clip volume. Toggled by long-pressing the Volume tool with an audio clip
     * selected (grey → green "stopwatch" essence).
     */
    private boolean audioVolumeKeyframeMode = false;
    private boolean clipOpacityKeyframeMode = false;
    private boolean captionStyleKeyframeMode = false;

    // Debounce for the audio reconcile: when did real playback stop (0 = playing). Used to pause
    // runaway audio after playback truly stops, without dipping the music at short clip boundaries.
    private long audioStoppedSinceMs = 0L;
    // ── Audio-tail mode: playhead continues past video for audio ─────
    private boolean audioTailActive = false;
    private long audioTailStartWall = 0;   // SystemClock.elapsedRealtime() when tail started
    private long audioTailStartMs = 0;     // playheadPositionMs when tail started

    // ── Persistence ──────────────────────────────────────────────────
    private ProjectStorage projectStorage;
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private static final long AUTO_SAVE_DELAY_MS = 3000;
    // Undo history persistence re-serializes EVERY snapshot (each a full project JSON),
    // which is heavy for long projects. It ran on every edit (each cut), causing the
    // slow-cut lag. Throttle it: the project itself still saves immediately on every
    // edit; the undo-history backup is written at most this often, and force-flushed
    // on pause so nothing is lost when leaving the editor.
    private static final long UNDO_HISTORY_SAVE_THROTTLE_MS = 15000;
    private long lastUndoHistorySaveMs = 0;
    private final Runnable autoSaveRunnable = () -> {
        if (project != null && projectStorage != null) {
            projectStorage.saveAsync(project);   // off the UI thread (debounced autosave)
            FLog.d(TAG, "Project auto-saved");
        }
    };

    /** True until we receive ExoPlayer's real duration and correct the project. */
    private boolean durationCorrectionPending = true;

    /**
     * True while the user is actively touching/dragging the timeline (playhead or trim).
     * Prevents {@link #updatePlayheadPosition()} from overwriting the drag position.
     */
    private boolean userDragging = false;

    /**
     * Remembers if the player was playing when a drag started,
     * so we can resume after the drag finishes.
     */
    private boolean wasPlayingBeforeDrag = false;

    /** Tracks the last playhead fraction set by the user (drag or trim). */
    private float lastUserPlayheadFraction = 0f;

    // ── Image clip playback ──────────────────────────────────────────
    /** True while an image clip is being "played" via internal timer. */
    private boolean imagePlaybackActive = false;
    /** System time (ms) when image playback started, for computing elapsed. */
    private long imagePlaybackStartSystemMs = 0;
    /** Position within the image clip when playback started (ms). */
    private long imagePlaybackStartOffsetMs = 0;

    // ── Playhead sync ────────────────────────────────────────────────
    private final Handler playheadHandler = new Handler(Looper.getMainLooper());
    private static final long PLAYHEAD_UPDATE_INTERVAL_MS = 50;

    private final Runnable playheadUpdater = new Runnable() {
        @Override
        public void run() {
            try {
                updatePlayheadPosition();
                syncAudioPlayerWithPlayhead();
                applyAudioKeyframeGains();
            } catch (Exception e) {
                FLog.e(TAG, "Error in updatePlayheadPosition", e);
            }
            // Only keep ticking if actively playing — avoids wasting CPU
            // redrawing the playhead position when nothing is moving.
            if ((playerManager != null && playerManager.isPlaying()) || audioTailActive) {
                playheadHandler.postDelayed(this, PLAYHEAD_UPDATE_INTERVAL_MS);
            }
        }
    };

    // ── Segment helpers ──────────────────────────────────────────────

    /**
     * Returns the currently selected clip.
     * Falls back to clip 0 if the index is out of range.
     */
    @NonNull
    private Clip getSelectedClip() {
        int count = project.getTimeline().getClipCount();
        if (selectedClipIndex < 0 || selectedClipIndex >= count) {
            // Don't mutate selectedClipIndex — callers must handle -1 explicitly.
            // Falling back to clip 0 for convenience but NOT changing the field,
            // otherwise updatePlayheadPosition would silently advance to the wrong
            // segment when no clip is actually selected (e.g. after audio tap).
            return project.getTimeline().getClip(0);
        }
        return project.getTimeline().getClip(selectedClipIndex);
    }

    /** Find a clip by its id, or null if not present. */
    @Nullable
    private Clip findClipById(@Nullable String id) {
        if (id == null) return null;
        Timeline tl = project.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (tl.getClip(i).getId().equals(id)) return tl.getClip(i);
        }
        return null;
    }

    @Nullable
    private AudioClip findAudioClipById(@NonNull String id) {
        Timeline tl = project.getTimeline();
        for (int i = 0; i < tl.getAudioClips().size(); i++) {
            if (tl.getAudioClips().get(i).getId().equals(id)) return tl.getAudioClips().get(i);
        }
        return null;
    }

    /** Find the audio clip active at a given timeline position (ms), or null. */
    @Nullable
    private AudioClip findAudioClipAtTimelineMs(long timelineMs) {
        Timeline tl = project.getTimeline();
        for (AudioClip ac : tl.getAudioClips()) {
            if (timelineMs >= ac.getOffsetMs() && timelineMs < ac.getEndOnTimelineMs()) {
                return ac;
            }
        }
        return null;
    }

    /**
     * Returns the total effective VISUAL video duration — the sum of each clip's on-timeline
     * length INCLUDING its loop/ping-pong/still extensions (a looped clip contributes its full
     * {@link Clip#getVisualDurationMs()}, not just its trimmed pass). This is the "video track
     * ends here" boundary the audio-tail feature gates on (see {@code btnPlayPause} / the playback
     * tick): if this undercounted the real timeline (as it did when it summed only
     * {@code getTrimmedDurationMs()}), pressing Play while paused INSIDE a loop extension — whose
     * back half sits past the sum-of-trimmed value — wrongly entered audio-tail mode (video frozen,
     * playhead driven purely by wall-clock) instead of resuming real ExoPlayer playback. Mirrors
     * the {@code hasLoopExtension() ? getVisualDurationMs() : getTrimmedDurationMs()} convention
     * already used elsewhere in this file (e.g. the STILL-extension cumulative math ~6978).
     */
    private long totalEffectiveMs() {
        long total = 0;
        Timeline tl = project.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            total += c.hasLoopExtension() ? c.getVisualDurationMs() : c.getTrimmedDurationMs();
        }
        return total;
    }

    /**
     * Select a segment by index, update the timeline and toolbar.
     *
     * @param index the segment index to select (-1 to deselect)
     */
    private void selectSegment(int index) {
        int count = project.getTimeline().getClipCount();
        loopVisualOffsetMs = 0;
        loopStillExtensionStartMs = -1;
        loopRestartPending = false;
        
        // Handle deselection
        if (index < 0) {
            selectedClipIndex = -1;
            editorTimeline.setTimeline(project.getTimeline(), -1);
            FLog.d(TAG, "Deselected all segments");
            return;
        }
        
        // Validate index
        if (index >= count) return;
        
        // Track if this is a reselection of the same segment
        boolean isReselection = (index == selectedClipIndex);
        
        selectedClipIndex = index;
        Clip clip = getSelectedClip();

        // Sync timeline view to the selected segment's trim
        editorTimeline.setTrimFromClip(clip);

        // Sync toolbar tools to the selected segment's settings
        updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
        updateOpacityUI();
        updateSpeedUI(clip.getSpeedMultiplier());
        updateRotateUI(clip.getRotationDegrees());
        updateFlipUI(clip.isFlipHorizontal(), clip.isFlipVertical());
        updateCropUI(clip.getCropPreset());
        updateFilterUI(clip);
        applyPreviewColorGrade(clip);
        updateSplitHealButton();

        // Deactivate crop overlay when switching segments (or reselecting)
        if (inCropMode) {
            exitCropMode(false);
        } else if (cropOverlay != null && cropOverlay.isActive()) {
            cropOverlay.deactivate();
        }

        // Only reload and reset playhead if selecting a different segment
        if (!isReselection) {
            // Stop any active image playback timer
            imagePlaybackActive = false;

            // Preserve current playhead position within the new segment
            long currentPlayheadMs = editorTimeline.getPlayheadPositionMs();
            long segStartMs = editorTimeline.getSegmentStartTimeMs(index);
            long effectiveMs = clip.getOutPointMs() - clip.getInPointMs();
            if (clip.getSpeedMultiplier() > 0) {
                effectiveMs = (long)(effectiveMs / clip.getSpeedMultiplier());
            }
            long localMs = Math.max(0, Math.min(currentPlayheadMs - segStartMs, effectiveMs));
            // Nudge 1ms before trim end to prevent boundary ambiguity
            // with getSegmentAtPlayhead() after split/delete operations.
            if (localMs == effectiveMs && effectiveMs > 1) {
                localMs = effectiveMs - 1;
            }
            // Convert local (effective) ms to source position
            long sourcePositionMs = clip.getInPointMs() + (long)(localMs * clip.getSpeedMultiplier());
            sourcePositionMs = Math.max(clip.getInPointMs(), Math.min(sourcePositionMs, clip.getOutPointMs()));
            float sourceFrac = clip.getSourceDurationMs() > 0
                    ? (float) sourcePositionMs / clip.getSourceDurationMs() : 0f;

            // MISSING source: show MISSING overlay instead of loading preview
            if (!clip.isGeneratedSlide() && !isSourceResolvable(clip.getSourceUri())) {
                hideImagePreview();
                hideSlidePreview();
                showMissingOverlay(clip);
                return;
            }

            if (clip.isImageClip()) {
                // Image clip: show image preview, hide video player
                hideMissingOverlay();
                hideSlidePreview();
                showImagePreview(clip.getSourceUri());
                if (playerManager != null && playerManager.isGapless()) {
                    // Gapless: point the engine at this image window so transport
                    // (play from here / window-local seeks) lines up with the selection.
                    playerManager.loadClip(clip);
                }
            } else if (clip.isGeneratedSlide()) {
                // AI slide: live WebView preview, no ExoPlayer load
                hideMissingOverlay();
                hideImagePreview();
                showSlidePreview(clip, localMs);
            } else {
                // Video clip: hide image preview, show video player
                hideMissingOverlay();
                hideImagePreview();
                hideSlidePreview();

                // Load the selected segment in the player
                loadClipForPlayback(clip);
                // Reset duration correction flag so ExoPlayer's real duration
                // is used to clamp trim points for this clip (critical for
                // relinked clips whose stored duration may be stale).
                durationCorrectionPending = true;
                playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
                playerManager.setPlaybackSpeed(clip.getSpeedMultiplier(), clip.isPitchCompensationEnabled());
                updatePreviewTransforms();
            }

            // Set playhead to preserved position (not segment start)
            editorTimeline.setPlayheadFraction(sourceFrac);
            
            long seekPositionMs = sourcePositionMs - clip.getInPointMs();
            if (!clip.isImageClip()
                    || (playerManager != null && playerManager.isGapless())) {
                // Seek ExoPlayer for video clips, and for image clips too when the gapless
                // engine serves them as playlist windows (window-local seek).
                playerManager.seekTo(seekPositionMs);
            }
            updateCurrentTimeDisplay(seekPositionMs);
        }

        refreshTotalTimeDisplay();

        FLog.d(TAG, "Selected segment " + index + "/" + count
                + " id=" + clip.getId()
                + " in=" + clip.getInPointMs() + " out=" + clip.getOutPointMs()
                + " isReselection=" + isReselection);
    
        // Update segment overview
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);
        refreshCaptionKeyframeDrawer();

        // If the transcript panel is already open, refresh it for the new clip.
        if (transcriptPanel != null && transcriptPanel.getVisibility() == View.VISIBLE) {
            loadTranscriptPanelContent();
        }
    }

    // ── Data-driven bottom tools carousel (Stage 1) ──────────────────
    /**
     * Builds the bottom tools carousel from {@link
     * com.fadcam.ui.faditor.tools.FaditorToolRegistry}. Each generated cell
     * reuses the historical {@code R.id.tool_*} ids, so the tool-button
     * capture + click-wiring code further down {@code onCreate()} resolves and
     * behaves exactly as before. This method only creates the cells; it does
     * NOT install any handlers itself (that stays in the existing code paths
     * to preserve behaviour 1:1, including the mute/opacity touch listeners).
     */
    private void buildToolsCarousel() {
        LinearLayout row = findViewById(R.id.faditor_tools_row);
        if (row == null) return;
        java.util.List<com.fadcam.ui.faditor.tools.FaditorTool> canonical =
                com.fadcam.ui.faditor.tools.FaditorToolRegistry.defaultTools(this);
        toolPrefs = new com.fadcam.ui.faditor.tools.FaditorToolPrefs(this);
        // Stage 3: resolve the persisted order (pins + manual/recent) up front.
        java.util.List<com.fadcam.ui.faditor.tools.FaditorTool> ordered =
                toolPrefs.resolveOrder(canonical);

        toolsAdapter = new com.fadcam.ui.faditor.tools.FaditorToolsAdapter(
                row, (tool, cell, icon, label) -> {
                    // No-op binder: the existing onCreate code captures fields
                    // and installs listeners via findViewById(R.id.tool_*).
                });
        toolsAdapter.setPrefs(toolPrefs);
        // v2: give the adapter the scroll view + overlay frame so edit mode can
        // auto-scroll and float the green drop line + the persistent Done
        // control. Must be set BEFORE setTools so overlays build once.
        View scroll = findViewById(R.id.faditor_tools_scroll);
        View overlayFrame = findViewById(R.id.faditor_tools_overlay);
        if (scroll instanceof android.widget.HorizontalScrollView
                && overlayFrame instanceof android.widget.FrameLayout) {
            toolsAdapter.setScrollAndOverlay(
                    (android.widget.HorizontalScrollView) scroll,
                    (android.widget.FrameLayout) overlayFrame);
        }
        toolsAdapter.setTools(ordered);
        toolsAdapter.updateEditChip();

        // Stage 2: swipe UP on the carousel opens the all-tools drawer.
        toolsDrawer = new com.fadcam.ui.faditor.tools.FaditorToolsDrawer(
                findViewById(R.id.editor_root) != null
                        ? (android.view.ViewGroup) ((View) findViewById(R.id.editor_root)).getParent()
                        : (android.view.ViewGroup) row.getRootView(),
                toolsAdapter,
                toolId -> {
                    // Record recency when a tool is used from the drawer too.
                    if (toolPrefs != null) toolPrefs.recordUse(toolId);
                });
        attachCarouselSwipeUp(row);

        // v2 edit mode: long-press-to-drag delegate + edit chip enters, the
        // floating Done control (and system back) exits.
        if (scroll instanceof com.fadcam.ui.faditor.tools.SwipeUpHorizontalScrollView) {
            ((com.fadcam.ui.faditor.tools.SwipeUpHorizontalScrollView) scroll)
                    .setEditDragDelegate(toolsAdapter.dragDelegate());
        }
        toolsAdapter.setOnEditToggle(() -> {
            if (toolsAdapter.isEditMode()) {
                toolsAdapter.exitEditMode();
            } else {
                toolsAdapter.enterEditMode();
            }
        });
    }

    /**
     * Wires the carousel's custom {@link
     * com.fadcam.ui.faditor.tools.SwipeUpHorizontalScrollView} so an upward
     * swipe opens the Stage 2 all-tools drawer. The custom view intercepts the
     * gesture itself (so clickable tool cells don't swallow it) while leaving
     * normal horizontal scrolling and taps intact.
     */
    private void attachCarouselSwipeUp(@NonNull View row) {
        View scroll = findViewById(R.id.faditor_tools_scroll);
        if (scroll instanceof com.fadcam.ui.faditor.tools.SwipeUpHorizontalScrollView) {
            ((com.fadcam.ui.faditor.tools.SwipeUpHorizontalScrollView) scroll)
                    .setOnSwipeUpListener(this::openToolsDrawer);
        }
    }

    /** Opens the Stage 2 all-tools drawer (no-op if already showing). */
    private void openToolsDrawer() {
        if (toolsDrawer != null && !toolsDrawer.isShowing()) {
            toolsDrawer.show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent ev) {
        if (assetDragActive) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    updateAssetDrag(ev.getRawX(), ev.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    endAssetDrag(ev.getActionMasked() == MotionEvent.ACTION_UP);
                    return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register asset picker launchers (must be before onStart)
        registerAssetPickers();

        // Register directory picker for asset browser (SAF tree URI)
        assetDirPickerLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException ignored) { }
                        String treeUriStr = uri.toString();
                        if (project != null) {
                            project.setPinnedAssetDir(treeUriStr);
                            saveProjectNow();
                        }
                        // Re-show the browser now that we have a pinned directory
                        showAssetBrowser();
                    } else {
                        // User cancelled — clear relink mode
                        relinkPendingIndex = -1;
                    }
                });

        // ── True fullscreen: hide status bar and nav bar ────────────
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_faditor_editor);

        // fitsSystemWindows handles safe area automatically on topBar

        // Initialize preferences and remuxer
        prefsManager = SharedPreferencesManager.getInstance(this);
        remuxer = new FragmentedMp4Remuxer(this);
        projectStorage = new ProjectStorage(this);
        undoManager = new UndoManager();
        undoManager.setSnapshotRestorer(new UndoManager.SnapshotRestorer() {
            @Nullable
            @Override
            public String captureSnapshot() {
                if (project == null || projectStorage == null) return null;
                return projectStorage.toJson(project);
            }

            @Override
            public void restoreFromSnapshot(@NonNull String projectJson) {
                restoreProjectFromSnapshot(projectJson);
            }
        });

        // Check if opening a saved project by ID
        String projectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        if (projectId != null) {
            FaditorProject loaded = projectStorage.load(projectId);
            if (loaded != null && !loaded.getTimeline().isEmpty()) {
                initViews();
                project = loaded;

                // T8: split any legacy sprites that share one lane (pre-T8 placements all
                // left layerId=null → one overlapping "sprite" track). Idempotent + purely
                // model-level, so it is safe here before the timeline view is wired up; the
                // deterministic lane ids persist on the next autosave.
                int movedSprites = project.getTimeline().migrateSpriteLayers();
                if (movedSprites > 0) {
                    FLog.i(TAG, "T8 sprite-layer migration: moved " + movedSprites
                            + " overlapping sprite(s) to their own lanes");
                }
                // Slice F: enforce the no-overlap invariant on text overlay lanes (two text overlays
                // could share a lane and overlap in time). Idempotent + model-level, same as the sprite
                // migration above; deterministic lane ids persist on the next autosave.
                int movedText = project.getTimeline().enforceNoOverlapTextLanes();
                if (movedText > 0) {
                    FLog.i(TAG, "Slice F: separated " + movedText
                            + " overlapping text overlay(s) onto their own lanes");
                }
                // Slice F: same no-overlap invariant for PiP / video-overlay lanes (two PiPs could share
                // a lane and overlap in time) — completes text/sprite/audio/PiP coverage. Model-level +
                // idempotent; deterministic "video-<id>" lane ids persist on the next autosave.
                int movedVideo = project.getTimeline().enforceNoOverlapVideoLanes();
                if (movedVideo > 0) {
                    FLog.i(TAG, "Slice F: separated " + movedVideo
                            + " overlapping PiP/video overlay(s) onto their own lanes");
                }
                // DURABILITY (road_map Tier-1): rescue any extracted-audio clips still pointing at the
                // OS-cleanable cache dir by copying them into the durable files/ dir + rewriting the URI.
                migrateAudioClipsToDurableStorage();

                // Check if any clips have stale cache/remux paths and try to
                // recover the original source before loading.
                recoverStaleCachePaths();

                Uri playUri = project.getTimeline().getClip(0).getSourceUri();
                // Resolve to file:// and check remux
                File sourceFile = resolveToFile(playUri);
                if (sourceFile != null) {
                    if (remuxer.needsRemux(sourceFile) && remuxer.hasRemuxedVersion(sourceFile)) {
                        playUri = Uri.fromFile(remuxer.getRemuxedFile(sourceFile));
                    } else {
                        // Always prefer file:// URI for reliable seeking
                        playUri = Uri.fromFile(sourceFile);
                    }
                }
                continueLoadFromSavedProject(playUri);
                FLog.d(TAG, "Editor opened saved project: " + projectId);
                return;
            }
            FLog.w(TAG, "Could not load saved project: " + projectId + ", falling back");
        }

        // Parse video URI from intent (new project)
        initialVideoUris = parseVideoUris();
        Uri videoUri = parseVideoUri();
        if (videoUri == null) {
            FLog.e(TAG, "No video URI provided");
            Toast.makeText(this, R.string.faditor_error_no_video, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Initialize components
        initViews();

        // Check if video needs remuxing for seekable playback
        attemptRemuxAndLoad(videoUri);

        FLog.d(TAG, "Editor opened with: " + videoUri);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Reapply immersive fullscreen (in case it was cleared by edge swipes)
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // If the AI assistant modified the project on disk, reload it
        // so the editor reflects the AI's changes instead of the stale
        // in-memory copy (which would overwrite the AI's work on next save).
        if (com.fadcam.ui.faditor.ai.AIChatState.projectModifiedByAI
                && project != null
                && project.getId().equals(
                    com.fadcam.ui.faditor.ai.AIChatState.modifiedProjectId)) {
            FLog.i(TAG, "AI modified project on disk — reloading from storage");
            com.fadcam.ui.faditor.ai.AIChatState.clearModified();

            try {
                com.fadcam.ui.faditor.model.FaditorProject reloaded =
                        projectStorage.load(project.getId());
                if (reloaded != null && !reloaded.getTimeline().isEmpty()) {
                    project = reloaded;
                    selectedClipIndex = Math.min(selectedClipIndex,
                            project.getTimeline().getClipCount() - 1);
                    if (selectedClipIndex < 0) selectedClipIndex = 0;
                    editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);
                    editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
                    syncTimelineOverlays();
                    if (overlayLayer != null) {
                        overlayLayer.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleTextOverlays(project.getTimeline()),
                                overlayLayerCallback());
                    }
                    selectSegment(selectedClipIndex);
                    updateEditorTitle();
                    refreshTotalTimeDisplay();
                    syncTimelineTranscript();
                    Toast.makeText(this, "AI edits applied", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                FLog.e(TAG, "Failed to reload project after AI edits", e);
                Toast.makeText(this, "AI edits saved — reopen project to see them",
                        Toast.LENGTH_LONG).show();
            }
        }

        playheadHandler.post(playheadUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        playheadHandler.removeCallbacks(playheadUpdater);
        hideTransitionPreview();
        // M-COMP-2: park the overlay decoder while backgrounded.
        if (overlayVideoLayer != null) overlayVideoLayer.pausePlayback();
        // Save project on pause (e.g. user switches away) — force-flush undo history.
        saveProjectNow(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        playheadHandler.removeCallbacks(playheadUpdater);
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        if (silenceDetector != null) silenceDetector.shutdown();
        if (transcriptionEngine != null) transcriptionEngine.shutdown();
        if (waveformExtractor != null) waveformExtractor.shutdown();
        releaseAudioPlayer();
        releaseTransitionRetriever();
        // M-COMP-2: free the overlay-video decoder.
        if (overlayVideoLayer != null) overlayVideoLayer.releasePlayer();
        audioExecutor.shutdownNow();
        assetImportExecutor.shutdownNow();
        // S4: release the shared sprite-sheet bitmaps.
        for (android.util.Pair<com.fadcam.ui.faditor.sprite.SpriteSheet,
                com.fadcam.ui.faditor.sprite.SpriteSheetRenderer> p : spriteRendererCache.values()) {
            if (p.second != null) p.second.recycle();
        }
        spriteRendererCache.clear();
        saveProjectNow(true);
        // Stop listening for export status but do NOT cancel — the :export process
        // continues on its own and reports via the system notification.
        if (exportEventsReceiverRegistered) {
            try {
                unregisterReceiver(exportEventsReceiver);
            } catch (Exception e) {
                FLog.w(TAG, "export receiver unregister failed", e);
            }
            exportEventsReceiverRegistered = false;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Reapply immersive fullscreen when window regains focus
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    // ── Initialization ───────────────────────────────────────────────

    @Nullable
    private Uri parseVideoUri() {
        List<Uri> uris = parseVideoUris();
        return uris != null && !uris.isEmpty() ? uris.get(0) : null;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private List<Uri> parseVideoUris() {
        List<Uri> uris = getIntent().getParcelableArrayListExtra(EXTRA_VIDEO_URIS);
        if (uris == null || uris.isEmpty()) {
            Uri single = parseSingleVideoUri();
            return single == null ? null : new ArrayList<>(java.util.Collections.singletonList(single));
        }
        List<Uri> filtered = new ArrayList<>();
        for (Uri uri : uris) {
            if (uri != null) filtered.add(uri);
        }
        return filtered.isEmpty() ? null : filtered;
    }

    @Nullable
    private Uri parseSingleVideoUri() {
        // Try intent data first, then extra
        Uri uri = getIntent().getData();
        if (uri == null) {
            String uriStr = getIntent().getStringExtra(EXTRA_VIDEO_URI);
            if (uriStr != null) {
                uri = Uri.parse(uriStr);
            }
        }
        return uri;
    }

    private void initViews() {
        playerView = findViewById(R.id.player_view);
        playerContainer = findViewById(R.id.player_container);
        transitionPreviewOverlay = findViewById(R.id.transition_preview_overlay);
        glTransitionPreviewView = findViewById(R.id.gl_transition_preview_view);
        overlayLayer = findViewById(R.id.overlay_layer);
        layerImageOverlay = findViewById(R.id.layer_image_overlay);
        if (layerImageOverlay != null) {
            layerImageOverlay.setRectProvider(this::computeCanvasRect);
        }
        spriteOverlayView = findViewById(R.id.sprite_overlay_layer);
        overlayVideoLayer = findViewById(R.id.overlay_video_layer);
        waveformOverlayView = findViewById(R.id.waveform_overlay);
        waveformExtractor = new com.fadcam.ui.faditor.waveform.WaveformExtractor(this);
        cropOverlay = findViewById(R.id.crop_overlay);
        imagePreview = findViewById(R.id.image_preview);
        slidePreview = findViewById(R.id.slide_preview);
        canvasFrame = findViewById(R.id.canvas_frame);
        safeZoneOverlay = findViewById(R.id.safe_zone_overlay);
        controlsSection = findViewById(R.id.controls_section);
        cropToolbar = findViewById(R.id.crop_toolbar);
        editorTimeline = findViewById(R.id.editor_timeline_view);
        editorTitle = findViewById(R.id.editor_title);
        editorTitle.setOnClickListener(v -> showRenameProjectDialog());
        editorTimeline.setOnTrackHeaderActionListener(this::onTrackHeaderAction);
        editorTimeline.setOnTrackHeaderLongPressListener(this::onTrackHeaderLongPress);
        editorTimeline.setLayerGestureCallback(layerGestureCallback());
        setupTimelineResizeGrabBar();
        editorTimeline.setOnSegmentActionListener(new EditorTimelineView.OnSegmentActionListener() {
            @Override
            public void onSegmentSelected(int index) {
                selectSegment(index);
            }

            @Override
            public void onSilenceCandidateTapped(int segmentIndex, long startMs, long endMs) {
                convertSilenceCandidate(segmentIndex, startMs, endMs);
            }

            @Override
            public void onTrimChanged(int segmentIndex, float startFraction, float endFraction, boolean isLeft) {
                if (!userDragging) {
                    // First drag callback — capture pre-trim values for undo
                    Clip clip = getSelectedClip();
                    if (clip != null) {
                        preTrimInMs = clip.getInPointMs();
                        preTrimOutMs = clip.getOutPointMs();
                    }
                }
                userDragging = true;
                Clip clip = getSelectedClip();
                if (clip == null) return;
                long duration = clip.getSourceDurationMs();
                clip.setInPointMs((long)(startFraction * duration));
                clip.setOutPointMs((long)(endFraction * duration));

                // Preview the trim edge frame-accurately in the MAIN video (so the
                // finger never covers it). Seek the player to the exact in/out frame
                // of the handle being dragged. Throttled so rapid drag events don't
                // flood the decoder with exact seeks.
                if (playerManager != null && !clip.isImageClip()) {
                    long nowMs = android.os.SystemClock.elapsedRealtime();
                    if (nowMs - lastTrimPreviewSeekMs >= TRIM_PREVIEW_SEEK_THROTTLE_MS) {
                        lastTrimPreviewSeekMs = nowMs;
                        long edgeSrcMs = isLeft
                                ? (long) (startFraction * duration)
                                : Math.max(0, (long) (endFraction * duration) - 1);
                        playerManager.setExactSeek(true);
                        playerManager.seekToAbsolute(edgeSrcMs);
                    }
                }

                // View handles its own visual update during drag.
                // setTrimFromClip is called in onTrimFinished for the final commit.
                updateCurrentTimeDisplay(0);
                refreshTotalTimeDisplay();
            }

            @Override
            public void onTrimFinished(int segmentIndex, float startFraction, float endFraction) {
                userDragging = false;
                Clip clip = getSelectedClip();
                if (clip == null) return;
                long duration = clip.getSourceDurationMs();
                long newIn = (long)(startFraction * duration);
                long newOut = (long)(endFraction * duration);

                // Record undo action before applying final values
                if (preTrimInMs >= 0 && (preTrimInMs != newIn || preTrimOutMs != newOut)) {
                    undoManager.recordAction(new EditActions.TrimAction(
                            clip, preTrimInMs, preTrimOutMs, newIn, newOut));
                }
                preTrimInMs = -1;
                preTrimOutMs = -1;

                clip.setInPointMs(newIn);
                clip.setOutPointMs(newOut);
                editorTimeline.setTrimFromClip(clip);
                if (!clip.isImageClip()) {
                    // RANK-1c: trim-edge drag rebuilds the playlist — advance the generation so a
                    // bake kicked before this drag discards its stale auto-promote.
                    rebuildGeneration++;
                    playerManager.updateTrimBounds(clip);
                    // Restore fast keyframe seeking for normal scrubbing (we forced
                    // EXACT during the trim-edge preview) and land on the new in-frame.
                    playerManager.setExactSeek(false);
                }
                // L2: a trim changes in/out → the reverse-bake key changes → the OLD baked file no
                // longer matches (cache miss), so a PING_PONG clip drops to forward-tail until a
                // fresh bake for the new range lands and rebuilds the playlist. Kick that re-bake.
                kickReverseBakeIfNeeded(clip);
                updateCurrentTimeDisplay(0);
                refreshTotalTimeDisplay();
                saveProjectNow();
            }

            @Override
            public void onLoopTrimFinished(int segmentIndex, long oldBefore, long oldAfter, long newBefore, long newAfter) {
                // NOTE: as of the resize-revert fix, a loop-extension edge drag fires THIS callback
                // ONLY (never onTrimFinished — see EditorTimelineView ACTION_UP), because the clip's
                // in/out points did not change (they were pinned to source bounds while the handle
                // moved the overshoot into loopBefore/loopAfter). So this handler owns all the
                // end-of-drag bookkeeping onTrimFinished used to do for this case.
                userDragging = false;
                Clip clip = project.getTimeline().getClip(segmentIndex);
                if (clip == null) return;
                // Loop values were already set on clip during drag; record undo
                undoManager.recordAction(new EditActions.LoopAction(clip,
                        clip.getLoopMode(), oldBefore, oldAfter,
                        clip.getLoopMode(), newBefore, newAfter));
                editorTimeline.setTrimFromClip(clip);
                // Rebuild the gapless playlist so the new rep count/boundaries take effect (for a
                // NORMAL loop) or so a stored PING_PONG clip stays correctly on the legacy
                // forward-tail path (parked). This applies + persists the resized extension.
                if (!clip.isImageClip()) {
                    // RANK-1c: loop-edge resize rebuilds the playlist — advance the generation so a
                    // bake kicked before this resize discards its stale auto-promote (the old
                    // resize-revert race the parking commit called out).
                    rebuildGeneration++;
                    playerManager.updateTrimBounds(clip);
                    playerManager.setExactSeek(false);
                }
                // PARKED: kickReverseBakeIfNeeded is a no-op while Clip.PING_PONG_PARKED (no bake is
                // ever triggered from a resize). Kept as the single un-park seam.
                kickReverseBakeIfNeeded(clip);
                refreshTotalTimeDisplay();
                saveProjectNow();
            }

            @Override
            public void onPlayheadSeeked(int segmentIndex, float fractionInSegment, boolean isDragging) {
                loopVisualOffsetMs = 0;
                loopStillExtensionStartMs = -1;
                userDragging = true;
                audioTailActive = false;  // Cancel audio-tail on seek
                // Get clip directly — DON'T call selectSegment() during scrubbing.
                // selectSegment triggers setPlayheadFraction → centerPlayhead which
                // modifies scrollOffsetPx, causing a feedback loop that makes the
                // timeline bounce between segments.
                Timeline tl = project.getTimeline();
                if (segmentIndex < 0 || segmentIndex >= tl.getClipCount()) return;
                Clip clip = tl.getClip(segmentIndex);
                if (clip == null) return;

                // If crossing to a different segment and NOT actively dragging,
                // load the new clip in the player (needed for correct seek bounds).
                // During drag, we skip loading to prevent snapping at split points.
                // When the drag ends, onPlayheadDragFinished() will load the new clip.
                if (segmentIndex != selectedClipIndex && !isDragging) {
                    selectedClipIndex = segmentIndex;
                    // Keep the green selection honest: it must match the clip the
                    // playhead is on, since that's what Delete/Split/etc. act on.
                    editorTimeline.setSelectedIndex(segmentIndex);
                    // MISSING source: show MISSING overlay instead of loading preview
                    if (!clip.isGeneratedSlide() && !isSourceResolvable(clip.getSourceUri())) {
                        hideImagePreview();
                        hideSlidePreview();
                        showMissingOverlay(clip);
                    } else if (clip.isImageClip()) {
                        // Image clip: show image preview instead of loading into ExoPlayer
                        hideMissingOverlay();
                        hideSlidePreview();
                        showImagePreview(clip.getSourceUri());
                        stopImagePlayback();
                        if (playerManager != null && playerManager.isGapless()) {
                            // Match the video scrub path's pause-on-seek: without this the
                            // gapless engine would keep playing under the image overlay.
                            playerManager.pause();
                        }
                    } else if (clip.isGeneratedSlide()) {
                        // AI slide: WebView preview is shown/seeked in the seek block below
                        hideMissingOverlay();
                        hideImagePreview();
                    } else {
                        // Video clip: load into ExoPlayer as usual
                        hideMissingOverlay();
                        hideImagePreview();
                        hideSlidePreview();
                        loadClipForPlayback(clip);
                        playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
                        playerManager.setPlaybackSpeed(clip.getSpeedMultiplier(), clip.isPitchCompensationEnabled());
                        updatePreviewTransforms();
                    }
                }

                // fractionInSegment is fraction of FULL source duration,
                // convert back to source time then compute relative-to-trim-start position
                long sourceMs = (long)(fractionInSegment * clip.getSourceDurationMs());
                long trimDuration = clip.getOutPointMs() - clip.getInPointMs();
                long seekPosition = Math.max(0, Math.min(sourceMs - clip.getInPointMs(), trimDuration));
                // MISSING video clip: skip ExoPlayer seek (no media loaded)
                boolean isMissingVideo = !clip.isGeneratedSlide() && !clip.isImageClip()
                        && !isSourceResolvable(clip.getSourceUri());
                if (clip.isGeneratedSlide()) {
                    // AI slide: drive the live WebView timeline instead of ExoPlayer.
                    showSlidePreview(clip, seekPosition);
                    updateScrubTransitionPreview(clip, segmentIndex, seekPosition);
                } else if (!clip.isImageClip() && !isMissingVideo) {
                    // Only seek ExoPlayer for video clips.
                    // Discrete taps (e.g. tap-a-word) seek EXACTly; drag-scrubbing
                    // stays on fast keyframe seeking.
                    if (playerManager == null) {
                        // Timeline can be scrubbed before the player is fully initialized
                        // (e.g. minimap drag during project creation). Keep the playhead
                        // and time display updated, but skip ExoPlayer operations.
                        updateCurrentTimeDisplay(seekPosition);
                        return;
                    }
                    playerManager.setExactSeek(!isDragging);
                    // Pause FIRST so ExoPlayer renders the decoded frame to TextureView
                    // (frame only becomes visible when playWhenReady=false and seek completes)
                    playerManager.pause();
                    playerManager.seekTo(seekPosition);
                    updatePreviewTransforms();
                    updateScrubTransitionPreview(clip, segmentIndex, seekPosition);
                } else {
                    // For image clips or missing clips: store the seek position for playback resumption
                    imagePlaybackStartOffsetMs = seekPosition;
                }
                // Update both timeline playhead position AND time display
                // Only update playhead fraction when NOT dragging to avoid calculated-position jumping
                // During drag, updatePlayheadFromX already set correct playheadPositionMs
                if (!isDragging) {
                    editorTimeline.setPlayheadFraction(fractionInSegment);
                }
                updateCurrentTimeDisplay(seekPosition);
                updateSplitHealButton();
                seekAudioPlayersToPlayhead();
            }

            @Override
            public void onPlayheadDragFinished() {
                userDragging = false;
                
                // Now that drag is finished, check if we crossed into a different segment
                // If so, load that clip now (we skipped it during the drag to avoid snapping)
                Timeline tl = project.getTimeline();
                int segmentAtPlayhead = editorTimeline.getSegmentAtPlayhead();
                if (segmentAtPlayhead >= 0 && segmentAtPlayhead != selectedClipIndex) {
                    selectedClipIndex = segmentAtPlayhead;
                    editorTimeline.setSelectedIndex(segmentAtPlayhead);
                    Clip clip = tl.getClip(segmentAtPlayhead);
                    if (clip != null) {
                        if (clip.isImageClip()) {
                            hideSlidePreview();
                            showImagePreview(clip.getSourceUri());
                            stopImagePlayback();
                            if (playerManager != null && playerManager.isGapless()) {
                                // Gapless: land the engine inside this image window at the
                                // scrubbed position so pressing play resumes from here.
                                long playheadMs = editorTimeline.getPlayheadPositionMs();
                                long segStartMs = editorTimeline
                                        .getSegmentStartTimeMs(segmentAtPlayhead);
                                playerManager.loadClip(clip);
                                playerManager.seekTo(Math.max(0, playheadMs - segStartMs));
                            }
                        } else if (clip.isGeneratedSlide()) {
                            hideImagePreview();
                            long playheadMs = editorTimeline.getPlayheadPositionMs();
                            long segStartMs = editorTimeline.getSegmentStartTimeMs(segmentAtPlayhead);
                            showSlidePreview(clip, Math.max(0, playheadMs - segStartMs));
                        } else {
                                hideImagePreview();
                                hideSlidePreview();
                                if (playerManager != null) {
                                    loadClipForPlayback(clip);
                                    playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
                                playerManager.setPlaybackSpeed(clip.getSpeedMultiplier(), clip.isPitchCompensationEnabled());
                                updatePreviewTransforms();

                                // Seek to the playhead position within the newly loaded clip
                                // so pressing play resumes from where the user scrubbed to,
                                // not from the beginning of the clip.
                                long playheadMs = editorTimeline.getPlayheadPositionMs();
                                long segStartMs = editorTimeline.getSegmentStartTimeMs(segmentAtPlayhead);
                                long localMs = Math.max(0, playheadMs - segStartMs);
                                long sourceMs = clip.getInPointMs()
                                        + (long)(localMs * clip.getSpeedMultiplier());
                                long seekPos = Math.max(0, sourceMs - clip.getInPointMs());
                                playerManager.seekTo(seekPos);
                                updatePreviewTransforms();
                            }
                        }
                    }
                }
                
                // Update toolbar UI to match the current segment
                if (selectedClipIndex >= 0 && selectedClipIndex < tl.getClipCount()) {
                    Clip clip = getSelectedClip();
                    if (clip != null) {
                        updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
                        updateOpacityUI();
                        updateSpeedUI(clip.getSpeedMultiplier());
                    }
                }

                // Frame-accurate settle: drag-scrubbing uses fast KEYFRAME seeking, so
                // when the finger lifts the preview is sitting on the nearest keyframe,
                // not the exact frame — which made it impossible to line a cut/transition
                // up to a music beat by eye. Re-seek EXACTly to the final playhead frame.
                if (playerManager != null
                        && selectedClipIndex >= 0 && selectedClipIndex < tl.getClipCount()) {
                    Clip fc = tl.getClip(selectedClipIndex);
                    if (fc != null && !fc.isImageClip() && !fc.isGeneratedSlide()
                            && isSourceResolvable(fc.getSourceUri())) {
                        long playheadMs = editorTimeline.getPlayheadPositionMs();
                        long segStartMs = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
                        long localMs = Math.max(0, playheadMs - segStartMs);
                        long sourceMs = fc.getInPointMs() + (long) (localMs * fc.getSpeedMultiplier());
                        long seekPos = Math.max(0, sourceMs - fc.getInPointMs());
                        playerManager.setExactSeek(true);
                        playerManager.pause();
                        playerManager.seekTo(seekPos);
                    }
                }
                seekAudioPlayersToPlayhead();
            }

            @Override
            public void onSegmentReordered(int fromIndex, int toIndex) {
                Timeline tl = project.getTimeline();
                undoManager.recordAction(new EditActions.ReorderClipAction(
                        tl, fromIndex, toIndex));
                tl.moveClip(fromIndex, toIndex);
                selectSegment(toIndex);
                syncTimelineOverlays();
                editorTimeline.invalidate();
                saveProjectNow();
            }

            @Override
            public void onReorderLinkRequested(int segmentIndex) {
                if (segmentIndex >= 0 && segmentIndex < project.getTimeline().getClipCount()) {
                    selectSegment(segmentIndex);
                    relinkPendingIndex = segmentIndex;
                    Clip clip = getSelectedClip();
                    if (clip != null && clip.isImageClip()) {
                        imagePickerLauncher.launch(openDocumentIntent("*/*"));
                    } else {
                        videoPickerLauncher.launch(openDocumentIntent("*/*"));
                    }
                }
            }

            @Override
            public void onReorderModeChanged(boolean entering) {
                // Hide/show bottom toolbar during reorder mode
                // Skip timeline_scroll (first HorizontalScrollView) — target the bottom toolbar
                HorizontalScrollView toolbar = null;
                LinearLayout controlsSection = findViewById(R.id.controls_section);
                if (controlsSection != null) {
                    for (int i = 0; i < controlsSection.getChildCount(); i++) {
                        View child = controlsSection.getChildAt(i);
                        if (child instanceof HorizontalScrollView
                                && child.getId() != R.id.timeline_scroll) {
                            toolbar = (HorizontalScrollView) child;
                            break;
                        }
                    }
                }
                if (toolbar != null) {
                    toolbar.setVisibility(entering ? View.GONE : View.VISIBLE);
                }
            }

            @Override
            public void onAudioClipSelected(int audioIndex) {
                FLog.d(TAG, "Audio clip selected: " + audioIndex);
                // Deselect video segment when audio clip is selected
                if (audioIndex >= 0 && selectedClipIndex >= 0) {
                    // Keep video segment for player but visual deselection
                    // is handled in EditorTimelineView
                }
                // If the transcript panel is open, switch it to the audio clip's transcript.
                if (transcriptPanel != null && transcriptPanel.getVisibility() == View.VISIBLE) {
                    loadTranscriptPanelContent();
                }
            }

            @Override
            public void onAudioTrimChanged(int audioIndex, long inPointMs, long outPointMs, boolean isLeft) {
                // Capture pre-trim values on first drag
                if (preAudioTrimInMs < 0 && project != null) {
                    AudioClip ac = project.getTimeline().getAudioClip(audioIndex);
                    if (ac != null) {
                        preAudioTrimInMs = ac.getInPointMs();
                        preAudioTrimOutMs = ac.getOutPointMs();
                    }
                }
                // Real-time visual feedback during audio trim drag
                FLog.d(TAG, "Audio trim changed: index=" + audioIndex
                        + " in=" + inPointMs + " out=" + outPointMs);
            }

            @Override
            public void onAudioTrimFinished(int audioIndex, long inPointMs, long outPointMs) {
                FLog.d(TAG, "Audio trim finished: index=" + audioIndex
                        + " in=" + inPointMs + " out=" + outPointMs);
                if (project == null) return;
                AudioClip ac = project.getTimeline().getAudioClip(audioIndex);
                if (ac != null) {
                    // Record undo action
                    if (preAudioTrimInMs >= 0
                            && (preAudioTrimInMs != inPointMs || preAudioTrimOutMs != outPointMs)) {
                        undoManager.recordAction(new EditActions.AudioTrimAction(
                                ac, preAudioTrimInMs, preAudioTrimOutMs,
                                inPointMs, outPointMs));
                    }
                    preAudioTrimInMs = -1;
                    preAudioTrimOutMs = -1;

                    ac.setInPointMs(inPointMs);
                    ac.setOutPointMs(outPointMs);
                    editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
                    // Re-prepare audio player with new trim
                    prepareAudioPlayer();
                    scheduleAutoSave();
                }
            }

            @Override
            public void onOverlayDragStart(int overlayIndex) {
                // Capture the overlay's pre-drag state once, before any mutation,
                // so range-edge / keyframe drags can record a single undo step.
                overlayTimelineDragBefore = null;
                overlayTimelineDragIndex = -1;
                if (project == null) return;
                java.util.List<com.fadcam.ui.faditor.model.TextOverlayItem> overlays =
                        project.getTimeline().getTextOverlays();
                if (overlayIndex < 0 || overlayIndex >= overlays.size()) return;
                overlayTimelineDragBefore = overlays.get(overlayIndex).snapshotTransform();
                overlayTimelineDragIndex = overlayIndex;
            }

            @Override
            public void onOverlayRangeChanged(int overlayIndex, long startMs, long endMs, boolean isLeft) {
                if (project == null) return;
                java.util.List<com.fadcam.ui.faditor.model.TextOverlayItem> overlays =
                        project.getTimeline().getTextOverlays();
                if (overlayIndex < 0 || overlayIndex >= overlays.size()) return;
                if (overlayLayer != null) overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
                syncTimelineOverlays();
            }

            @Override
            public void onOverlayRangeFinished(int overlayIndex, long startMs, long endMs) {
                if (project == null) return;
                java.util.List<com.fadcam.ui.faditor.model.TextOverlayItem> overlays =
                        project.getTimeline().getTextOverlays();
                if (overlayIndex < 0 || overlayIndex >= overlays.size()) return;
                recordOverlayTimelineDrag(overlays.get(overlayIndex), overlayIndex, "Overlay time range");
                if (overlayLayer != null) {
                    overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
                    overlayLayer.rebuild();
                }
                syncTimelineOverlays();
                scheduleAutoSave();
            }

            @Override
            public void onOverlayKeyframeMoved(int overlayIndex, long oldLocalMs, long newLocalMs) {
                if (overlayLayer != null) overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
                syncTimelineOverlays();
            }

            @Override
            public void onOverlayKeyframeMoveFinished(int overlayIndex, long oldLocalMs, long newLocalMs) {
                if (oldLocalMs == newLocalMs) {
                    overlayTimelineDragBefore = null;
                    overlayTimelineDragIndex = -1;
                    return;
                }
                if (project != null) {
                    java.util.List<com.fadcam.ui.faditor.model.TextOverlayItem> overlays =
                            project.getTimeline().getTextOverlays();
                    if (overlayIndex >= 0 && overlayIndex < overlays.size()) {
                        recordOverlayTimelineDrag(overlays.get(overlayIndex), overlayIndex, "Move overlay keyframe");
                    }
                }
                if (overlayLayer != null) overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
                syncTimelineOverlays();
                scheduleAutoSave();
            }

            @Override
            public void onTransitionSelected(int index) {
                updateTransitionSelection();
                showTransitionInspector(index);
            }

            @Override
            public void onTransitionDurationChanged(int index, long durationMs) {
                if (project == null || index < 0 || index >= project.getTimeline().getTransitions().size()) return;
                project.getTimeline().setTransitionDuration(index, durationMs);
                updateTransitionInspector(index);
            }

            @Override
            public void onTransitionDurationFinished(int index, long durationMs) {
                if (project == null || index < 0 || index >= project.getTimeline().getTransitions().size()) return;
                project.getTimeline().setTransitionDuration(index, durationMs);
                saveProjectNow();
                updateTransitionInspector(index);
            }

            @Override
            public void onTransitionDeleted(int index) {
                deleteTransition(index);
            }

            @Override
            public void onVisualizerLayerTapped(int waveformIndex) {
                // Tap the VIZ layer row → open that visualizer's Rolodex drawer.
                java.util.List<com.fadcam.ui.faditor.model.WaveformOverlayInstance> ws =
                        project.getTimeline().getWaveformOverlays();
                if (waveformIndex >= 0 && waveformIndex < ws.size()) {
                    showVisualizerStylePicker(ws.get(waveformIndex));
                }
            }

            @Override
            public void onOverlayLayerTapped(int overlayIndex) {
                // Tap a TEXT/IMAGE layer row → jump the playhead to that overlay's start so it's visible.
                java.util.List<com.fadcam.ui.faditor.model.TextOverlayItem> os =
                        project.getTimeline().getTextOverlays();
                if (overlayIndex >= 0 && overlayIndex < os.size()) {
                    editorTimeline.seekToTimelineMs(Math.max(0, os.get(overlayIndex).getStartMs()));
                }
            }

            @Override
            public void onCaptionLayerTapped(int clipIndex) {
                // Tap a CC caption segment → select that clip + jump to it so its captions/props show.
                if (clipIndex >= 0 && clipIndex < project.getTimeline().getClipCount()) {
                    editorTimeline.seekToTimelineMs(editorTimeline.getSegmentStartTimeMs(clipIndex));
                    selectSegment(clipIndex);
                }
            }

            @Override
            public void onVisualizerLayerLongPressed(int waveformIndex) {
                java.util.List<com.fadcam.ui.faditor.model.WaveformOverlayInstance> ws =
                        project.getTimeline().getWaveformOverlays();
                if (waveformIndex < 0 || waveformIndex >= ws.size()) return;
                com.fadcam.ui.faditor.model.WaveformOverlayInstance wv = ws.get(waveformIndex);
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        FaditorEditorActivity.this)
                        .setTitle("Remove visualizer?")
                        .setMessage("This removes the visualizer overlay from the timeline.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", (d, w) -> {
                            project.getTimeline().removeWaveformOverlay(wv);
                            undoManager.recordAction(new EditActions.LambdaAction("Remove visualizer",
                                    () -> project.getTimeline().removeWaveformOverlay(wv),
                                    () -> project.getTimeline().addWaveformOverlay(wv)));
                            if (waveformOverlayView != null) {
                                waveformOverlayView.setOverlays(project.getTimeline().getWaveformOverlays());
                                waveformOverlayView.invalidate();
                            }
                            syncTimelineOverlays();
                            editorTimeline.invalidate();
                            scheduleAutoSave();
                            Toast.makeText(FaditorEditorActivity.this, "Visualizer removed",
                                    Toast.LENGTH_SHORT).show();
                        })
                        .show();
            }

            @Override
            public void onOverlayLayerLongPressed(int overlayIndex) {
                java.util.List<com.fadcam.ui.faditor.model.TextOverlayItem> os =
                        project.getTimeline().getTextOverlays();
                if (overlayIndex < 0 || overlayIndex >= os.size()) return;
                final com.fadcam.ui.faditor.model.TextOverlayItem o = os.get(overlayIndex);
                // G2: canvas long-press = the same general advanced menu as the
                // timeline hold-release (one menu, contract §2).
                showObjectMenuSheetForTextOverlay(o);
            }

            @Override
            public void onCaptionLayerLongPressed(int clipIndex) {
                // Long-press a CC segment → select the clip + open caption keyframe drawer.
                if (clipIndex < 0 || clipIndex >= project.getTimeline().getClipCount()) return;
                editorTimeline.seekToTimelineMs(editorTimeline.getSegmentStartTimeMs(clipIndex));
                selectSegment(clipIndex);
                openCaptionKeyframeDrawer();
            }
        });
        btnPlayPause = findViewById(R.id.btn_play_pause);
        timeCurrent = findViewById(R.id.time_current);
        timeTotal = findViewById(R.id.time_total);
        exportProgressOverlay = findViewById(R.id.export_progress_overlay);
        exportProgressText = findViewById(R.id.export_progress_text);
        exportProgressPercent = findViewById(R.id.export_progress_percent);
        exportEtaText = findViewById(R.id.export_eta_text);
        exportStatusIcon = findViewById(R.id.export_status_icon);
        exportBtnDone = findViewById(R.id.export_btn_done);
        exportTitle = findViewById(R.id.export_title);
        exportProgressBar = findViewById(R.id.export_progress_bar);
        exportInfoText = findViewById(R.id.export_info_text);
        exportProgressStripe = findViewById(R.id.export_progress_stripe);
        remuxProgressOverlay = findViewById(R.id.remux_progress_overlay);
        remuxProgressText = findViewById(R.id.remux_progress_text);
        transcriptStatusBadge = findViewById(R.id.transcript_status_badge);
        transcriptGlobalProgressBar = findViewById(R.id.transcript_global_progress_bar);
        btnSoftSnap = findViewById(R.id.btn_soft_snap);

        // Data-driven bottom tools carousel. Builds all tool cells (with the
        // same R.id.tool_* ids the old XML used) into @id/faditor_tools_row so
        // that every findViewById(...) / cached field reference below keeps
        // resolving unchanged. Must run BEFORE the tool-button captures.
        buildToolsCarousel();

        // Tool buttons
        toolSpeed = findViewById(R.id.tool_speed);
        toolMute = findViewById(R.id.tool_mute);
        toolMuteIcon = findViewById(R.id.tool_mute_icon);
        toolMuteLabel = findViewById(R.id.tool_mute_label);
        volumeDragHud = findViewById(R.id.volume_drag_hud);
        volumeDragFader = findViewById(R.id.volume_drag_fader);
        volumeDragHudIcon = findViewById(R.id.volume_drag_hud_icon);
        volumeDragHudValue = findViewById(R.id.volume_drag_hud_value);
        volumeDrawer = findViewById(R.id.volume_drawer);
        volumeBar = findViewById(R.id.volume_bar);
        volumeDrawerIcon = findViewById(R.id.volume_drawer_icon);
        volumeDrawerValue = findViewById(R.id.volume_drawer_value);
        volumeDrawerKeyframe = findViewById(R.id.volume_drawer_keyframe);
        volumeDrawerMute = findViewById(R.id.volume_drawer_mute);
        toolOpacity = findViewById(R.id.tool_opacity);
        toolOpacityIcon = findViewById(R.id.tool_opacity_icon);
        toolOpacityLabel = findViewById(R.id.tool_opacity_label);
        opacityDrawer = findViewById(R.id.opacity_drawer);
        opacitySlider = findViewById(R.id.opacity_slider);
        opacityDrawerIcon = findViewById(R.id.opacity_drawer_icon);
        opacityDrawerValue = findViewById(R.id.opacity_drawer_value);
        opacityDrawerKeyframe = findViewById(R.id.opacity_drawer_keyframe);
        wordScrubDrawer = findViewById(R.id.word_scrub_drawer);
        wordScrubStrip = findViewById(R.id.word_scrub_strip);
        wordScrubWordText = findViewById(R.id.word_scrub_word_text);
        wordScrubTimestamp = findViewById(R.id.word_scrub_timestamp);
        wordScrubPrev = findViewById(R.id.word_scrub_prev);
        wordScrubNext = findViewById(R.id.word_scrub_next);
        wordScrubCenter = findViewById(R.id.word_scrub_center);
        toolSpeedLabel = findViewById(R.id.tool_speed_label);
        toolRotate = findViewById(R.id.tool_rotate);
        toolRotateIcon = findViewById(R.id.tool_rotate_icon);
        toolRotateLabel = findViewById(R.id.tool_rotate_label);
        toolFlip = findViewById(R.id.tool_flip);
        toolFlipIcon = findViewById(R.id.tool_flip_icon);
        toolFlipLabel = findViewById(R.id.tool_flip_label);
        toolCrop = findViewById(R.id.tool_crop);
        toolCropIcon = findViewById(R.id.tool_crop_icon);
        toolCropLabel = findViewById(R.id.tool_crop_label);
        toolCanvas = findViewById(R.id.tool_canvas);
        toolCanvasIcon = findViewById(R.id.tool_canvas_icon);
        toolCanvasLabel = findViewById(R.id.tool_canvas_label);
        toolAudio = findViewById(R.id.tool_audio);
        toolAudioIcon = findViewById(R.id.tool_audio_icon);
        toolAudioLabel = findViewById(R.id.tool_audio_label);
        toolMove = findViewById(R.id.tool_move);
        toolMoveIcon = findViewById(R.id.tool_move_icon);
        toolMoveLabel = findViewById(R.id.tool_move_label);
        toolLoop = findViewById(R.id.tool_loop);
        toolLoopIcon = findViewById(R.id.tool_loop_icon);
        toolLoopLabel = findViewById(R.id.tool_loop_label);
        loopDrawer = findViewById(R.id.loop_drawer);
        loopDrawerScroll = findViewById(R.id.loop_drawer_scroll);
        loopDrawerIcon = findViewById(R.id.loop_drawer_icon);
        loopDrawerModeLabel = findViewById(R.id.loop_drawer_mode_label);
        loopModeOff = findViewById(R.id.loop_mode_off);
        loopModeNormal = findViewById(R.id.loop_mode_normal);
        loopModeStill = findViewById(R.id.loop_mode_still);
        loopModePingpong = findViewById(R.id.loop_mode_pingpong);
        loopExtendStart = findViewById(R.id.loop_extend_start);
        loopExtendEnd = findViewById(R.id.loop_extend_end);
        loopExtendPrev = findViewById(R.id.loop_extend_prev);
        loopExtendNext = findViewById(R.id.loop_extend_next);
        toolTranscript = findViewById(R.id.tool_transcript);
        toolSplitIcon = findViewById(R.id.tool_split_icon);
        toolSplitLabel = findViewById(R.id.tool_split_label);
        cropSnapCenter = findViewById(R.id.crop_btn_snap_center);
        cropAutoCrop = findViewById(R.id.crop_btn_auto_crop);

        // Undo/Redo buttons with count badges
        btnUndo = findViewById(R.id.btn_undo);
        btnRedo = findViewById(R.id.btn_redo);
        btnRelinkMedia = findViewById(R.id.btn_relink_media);
        TextView undoCount = findViewById(R.id.undo_count);
        TextView redoCount = findViewById(R.id.redo_count);
        btnUndo.setOnClickListener(v -> performUndo());
        btnRedo.setOnClickListener(v -> performRedo());
        btnUndo.setOnLongClickListener(v -> { showUndoRedoHistoryPopup(v); return true; });
        btnRedo.setOnLongClickListener(v -> { showUndoRedoHistoryPopup(v); return true; });
        btnRelinkMedia.setOnClickListener(v -> showRelinkCatalog());
        if (btnSoftSnap != null) {
            btnSoftSnap.setOnClickListener(v -> toggleOverlaySoftSnap());
        }
        btnRippleMode = findViewById(R.id.btn_ripple_mode);
        if (btnRippleMode != null) {
            btnRippleMode.setOnClickListener(v -> toggleRippleMode());
        }
        undoManager.setOnStateChangedListener((canUndo, canRedo) -> {
            btnUndo.setAlpha(canUndo ? 1.0f : 0.3f);
            btnRedo.setAlpha(canRedo ? 1.0f : 0.3f);
            btnUndo.setEnabled(canUndo);
            btnRedo.setEnabled(canRedo);

            int uCount = undoManager.getUndoCount();
            int rCount = undoManager.getRedoCount();
            undoCount.setText(String.valueOf(uCount));
            undoCount.setVisibility(View.VISIBLE);
            undoCount.setAlpha(uCount > 0 ? 1.0f : 0.3f);
            redoCount.setText(String.valueOf(rCount));
            redoCount.setVisibility(View.VISIBLE);
            redoCount.setAlpha(rCount > 0 ? 1.0f : 0.3f);
        });

        // Segment tools
        findViewById(R.id.tool_split).setOnClickListener(v -> splitOrHealAtPlayhead());
        findViewById(R.id.tool_delete).setOnClickListener(v -> deleteSelectedSegment());
        findViewById(R.id.tool_duplicate).setOnClickListener(v -> duplicateSelectedSegment());
        findViewById(R.id.tool_add_asset).setOnClickListener(v -> showAddAssetPicker());
        findViewById(R.id.tool_text).setOnClickListener(v -> addTextOverlay());
        findViewById(R.id.tool_visualizer).setOnClickListener(v -> addWaveformVisualizer());
        findViewById(R.id.tool_filter).setOnClickListener(v -> openFilterSheet());
        findViewById(R.id.tool_sticker).setOnClickListener(v -> pickImageOverlay());
        findViewById(R.id.tool_silence).setOnClickListener(v -> toggleSilenceDetect());
        findViewById(R.id.tool_settings).setOnClickListener(v -> {
            com.fadcam.ui.faditor.FaditorSettingsBottomSheet sheet =
                    com.fadcam.ui.faditor.FaditorSettingsBottomSheet.newInstance();
            sheet.setCallback(this::setSafeZoneOverlayEnabled);
            sheet.show(getSupportFragmentManager(), "faditorSettings");
        });
        findViewById(R.id.tool_sprites).setOnClickListener(v -> openSpritePalette());
        View toolCompact = findViewById(R.id.tool_compact);
        if (toolCompact != null) toolCompact.setOnClickListener(v -> compactLayers());
        // G8 (contract §5.5): three-state marquee multi-select toggle.
        View toolSelect = findViewById(R.id.tool_select);
        if (toolSelect != null) toolSelect.setOnClickListener(v -> cycleMarqueeMode());
        wireMarqueeListener();
        toolMove.setOnClickListener(v -> toggleMoveDrawer());
        initMoveDrawer();
        // (Sprites tool wired above; manager implementation below the tool handlers.)
        findViewById(R.id.tool_transcript).setOnClickListener(v -> {
            if (transcriptPanel != null && transcriptPanel.getVisibility() == View.VISIBLE) {
                showTranscriptPanel(false);
            } else {
                openTranscriptPanel();
            }
        });
        findViewById(R.id.tool_transitions).setOnClickListener(v -> showTransitionPanel(!transitionPanelOpen));
        findViewById(R.id.tool_captions).setOnClickListener(v -> toggleCaptions());
        toolCaptionsIcon = findViewById(R.id.tool_captions_icon);
        toolCaptionsLabel = findViewById(R.id.tool_captions_label);
        toolLoop.setOnClickListener(v -> {
            if (loopDrawerOpen) {
                hideLoopDrawer();
            } else {
                showLoopDrawer();
            }
        });
        setupTranscriptPanel();
        setupCaptions();
        setupTransitionPanel();

        // Tap the current-time display to type a time and jump there.
        if (timeCurrent != null) {
            timeCurrent.setOnClickListener(v -> showSeekToTimeDialog());
        }

        // Close button — show confirmation bottom sheet
        findViewById(R.id.btn_close).setOnClickListener(v -> {
            if (inCropMode) {
                exitCropMode(false);
                return;
            }
            showCloseConfirmation();
        });

        // Asset Browser button — opens the pinned-folder asset panel
        findViewById(R.id.btn_asset_browser).setOnClickListener(v -> showAssetBrowser());

        // AI Assistant button — opens the chat assistant with project context
        findViewById(R.id.btn_ai_assistant).setOnClickListener(v -> {
            Intent intent = new Intent(this, com.fadcam.ui.faditor.ai.ChatAssistantActivity.class);
            intent.putExtra(com.fadcam.ui.faditor.ai.ChatAssistantActivity.EXTRA_PROJECT_ID,
                    project != null ? project.getId() : "");
            startActivity(intent);
        });
    }

    /**
     * Attempt to remux a fragmented MP4 for seekable preview, then proceed
     * with project initialisation. If the file doesn't need remuxing (or is
     * not a local file) we skip straight to loading.
     */
    private void attemptRemuxAndLoad(@NonNull Uri videoUri) {
        File sourceFile = resolveToFile(videoUri);

        // Build a file:// URI when the source resolves to a readable file.
        // This ensures ExoPlayer uses FileDataSource (supports random-access
        // seeking) instead of ContentDataSource (limited seeking).
        Uri fileUri = (sourceFile != null) ? Uri.fromFile(sourceFile) : videoUri;

        if (sourceFile != null && remuxer.needsRemux(sourceFile)) {
            // Already remuxed?
            if (remuxer.hasRemuxedVersion(sourceFile)) {
                File remuxed = remuxer.getRemuxedFile(sourceFile);
                FLog.d(TAG, "Using cached remuxed file: " + remuxed.getName());
                continueLoadWithUri(Uri.fromFile(remuxed), videoUri);
                return;
            }

            // Show progress and remux async
            showRemuxProgress();
            FLog.i(TAG, "Remuxing fragmented MP4 for seekable preview…");

            remuxer.remuxAsync(sourceFile, new FragmentedMp4Remuxer.RemuxCallback() {
                @Override
                public void onRemuxComplete(boolean success, String outputPath) {
                    runOnUiThread(() -> {
                        hideRemuxProgress();
                        if (success && outputPath != null) {
                            FLog.i(TAG, "Remux complete: " + outputPath);
                            continueLoadWithUri(
                                    Uri.fromFile(new File(outputPath)), videoUri);
                        } else {
                            FLog.w(TAG, "Remux failed, loading original (seeking may not work)");
                            continueLoadWithUri(fileUri, videoUri);
                        }
                    });
                }

                @Override
                public void onRemuxProgress(int percent) {
                    runOnUiThread(() -> {
                        if (remuxProgressText != null) {
                            remuxProgressText.setText(
                                    getString(R.string.faditor_remuxing_percent, percent));
                        }
                    });
                }
            });
        } else {
            // No remux needed — still use file:// URI when available for
            // reliable seeking and duration extraction.
            continueLoadWithUri(fileUri, videoUri);
        }
    }

    /**
     * Finish editor initialisation after (optional) remux is done.
     *
     * @param playUri   URI to use for preview playback (may be remuxed)
     * @param exportUri original URI kept for the project (NEVER a cache path)
     */
    private void continueLoadWithUri(@NonNull Uri playUri, @NonNull Uri exportUri) {
        // Store the ORIGINAL uri in the project, not the remuxed cache path.
        // The remuxed path is only used transiently for player seeking.
        initProject(exportUri);
        // Initialize the player before timeline/toolbar so scrub/playback
        // controls never see a null playerManager during new-project setup.
        initPlayer();
        // Override the player to use the remuxed path for this session
        if (playerManager != null && project != null
                && project.getTimeline().getClipCount() > 0) {
            Clip clip = project.getTimeline().getClip(0);
            // Create a temporary clip with the play URI for the player only
            Clip playClip = clip.relinked(playUri);
            playerManager.loadClip(playClip);
        }
        initTimeline();
        applyCanvasFrame();
        if (prefsManager != null) {
            setSafeZoneOverlayEnabled(prefsManager.isFaditorSafeZoneOverlayEnabled());
        }
        initToolbar();
        initExport();
        initBackHandler();
        setupOverlayLayer();
    }

    /**
     * Finish editor initialisation when loading a saved project.
     * Skips initProject() since the project is already loaded from storage.
     *
     * @param playUri URI to use for preview playback (may be remuxed)
     */
    private void continueLoadFromSavedProject(@NonNull Uri playUri) {
        // Project is already set from saved data, refresh time display
        refreshTotalTimeDisplay();
        updateEditorTitle();
        selectedClipIndex = Math.max(0, Math.min(selectedClipIndex,
                project.getTimeline().getClipCount() - 1));
        initPlayer();
        initTimeline();
        Clip initialClip = getSelectedClip();
        if (initialClip != null && initialClip.isGeneratedSlide()) {
            showSlidePreview(initialClip, 0);
        } else {
            loadClipForPlayback(initialClip);
        }
        applyCanvasFrame();
        if (prefsManager != null) {
            setSafeZoneOverlayEnabled(prefsManager.isFaditorSafeZoneOverlayEnabled());
        }
        initToolbar();
        initExport();
        initBackHandler();

        // Restore audio clips into timeline view if any exist
        if (project.getTimeline().hasAudioClips()) {
            editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
            updateAudioToolUI();
        }

        // Restore canvas preset
        if (project.getCanvasPreset() != null
                && !"original".equals(project.getCanvasPreset())) {
            applyCanvasPreview(project.getCanvasPreset());
        }

        // Restore text overlays
        setupOverlayLayer();

        // Bind waveform visualizers and extract their audio.
        refreshWaveformOverlays();

        // Restore animated captions if a clip had them enabled.
        editorTimeline.post(this::restoreCaptionsAfterLoad);

        // Restore undo history from disk
        List<String> descriptions = new ArrayList<>();
        List<String> snapshots = new ArrayList<>();
        if (projectStorage.loadUndoHistory(project.getId(), descriptions, snapshots)) {
            undoManager.loadHistory(descriptions, snapshots);
            FLog.d(TAG, "Restored " + descriptions.size() + " undo history entries");
        }

        // Push transcripts to the timeline for scrolling text display
        syncTimelineTranscript();

        FLog.d(TAG, "Editor loaded saved project: " + project.getId()
                + ", clips=" + project.getTimeline().getClipCount()
                + ", audioClips=" + project.getTimeline().getAudioClipCount());

        // Offer to relink any clips whose source can no longer be opened.
        editorTimeline.post(this::checkMissingMedia);
    }

    // ── Relink missing media (catalog-based, à la After Effects) ──────

    private boolean isSourceAccessible(@NonNull Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                String p = uri.getPath();
                return p != null && new File(p).exists();
            }
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                return in != null;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Like {@link #isSourceAccessible} but caches the result per URI. */
    private boolean isSourceResolvable(@NonNull Uri uri) {
        Boolean cached = resolvableCache.get(uri);
        if (cached != null) return cached;
        boolean accessible = isSourceAccessible(uri);
        resolvableCache.put(uri, accessible);
        return accessible;
    }

    /** Show a MISSING placeholder in the preview area for the given clip. */
    private void showMissingOverlay(@NonNull Clip clip) {
        if (missingOverlayView == null) {
            ensureMissingOverlay();
        }
        String displayName = clip.getDisplayName();
        if (displayName == null) displayName = originalFilename(clip.getSourceUri());
        if (displayName == null) displayName = "clip";
        android.widget.TextView nameText = missingOverlayView.findViewWithTag("name");
        if (nameText != null) nameText.setText(displayName);
        playerView.setVisibility(View.INVISIBLE);
        if (imagePreview != null) imagePreview.setVisibility(View.GONE);
        if (slidePreview != null) slidePreview.setVisibility(View.GONE);
        missingOverlayView.setVisibility(View.VISIBLE);
        missingOverlayView.bringToFront();
        missingOverlayView.setOnClickListener(v -> showRelinkCatalog());
    }

    /** Hide the MISSING placeholder and restore the video player. */
    private void hideMissingOverlay() {
        if (missingOverlayView != null && missingOverlayView.getVisibility() != View.GONE) {
            missingOverlayView.setVisibility(View.GONE);
            if (playerView != null && playerView.getVisibility() != View.VISIBLE) {
                playerView.setVisibility(View.VISIBLE);
            }
        }
    }

    /** Lazy-create the MISSING overlay view added to the player container. */
    private void ensureMissingOverlay() {
        if (missingOverlayView != null || playerContainer == null) return;
        android.content.res.Resources r = getResources();
        float density = r.getDisplayMetrics().density;
        int padPx = (int)(24 * density);

        android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
        overlay.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(0xFF1A1A2E);
        overlay.setVisibility(View.GONE);
        overlay.setTag("missing_overlay");

        // Warning icon (large emoji text)
        android.widget.TextView iconView = new android.widget.TextView(this);
        iconView.setText("\u26A0\uFE0F");
        iconView.setTextSize(48f);
        iconView.setTextColor(0xFFFF4444);
        iconView.setGravity(android.view.Gravity.CENTER);
        android.widget.FrameLayout.LayoutParams iconLp =
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        iconLp.gravity = android.view.Gravity.CENTER;
        iconLp.topMargin = -(int)(60 * density);
        overlay.addView(iconView, iconLp);

        // Clip name
        android.widget.TextView nameView = new android.widget.TextView(this);
        nameView.setTextColor(0xFFFFFFFF);
        nameView.setTextSize(16f);
        nameView.setGravity(android.view.Gravity.CENTER);
        nameView.setTag("name");
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        nameView.setMaxLines(1);
        android.widget.FrameLayout.LayoutParams nameLp =
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        nameLp.gravity = android.view.Gravity.CENTER;
        nameLp.topMargin = (int)(10 * density);
        overlay.addView(nameView, nameLp);

        // Tap to relink hint
        android.widget.TextView hintView = new android.widget.TextView(this);
        hintView.setText("Tap to relink");
        hintView.setTextColor(0xFF888888);
        hintView.setTextSize(13f);
        hintView.setGravity(android.view.Gravity.CENTER);
        android.widget.FrameLayout.LayoutParams hintLp =
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = android.view.Gravity.CENTER;
        hintLp.topMargin = (int)(40 * density);
        overlay.addView(hintView, hintLp);

        // Divider line above hint
        android.view.View divider = new android.view.View(this);
        divider.setBackgroundColor(0xFF444444);
        android.widget.FrameLayout.LayoutParams divLp =
                new android.widget.FrameLayout.LayoutParams(
                        (int)(120 * density), (int)(1 * density));
        divLp.gravity = android.view.Gravity.CENTER;
        divLp.topMargin = (int)(32 * density);
        overlay.addView(divider, divLp);

        playerContainer.addView(overlay);
        missingOverlayView = overlay;
    }

    /**
     * Detect clips whose sourceUri points to a stale cache/remux path and
     * try to recover the original source. This is the root cause of most
     * "missing media" problems: the remuxed cache file was saved into the
     * project JSON instead of the original URI.
     */
    /**
     * DURABILITY (road_map Tier-1): rescue AudioClips whose PERSISTED sourceUri still points at the OLD
     * cache dir ({@code getCacheDir()/faditor_audio}). Before the extract→getFilesDir fix, extracted audio
     * lived in the OS-cleanable cache; those clips' URIs are persisted in project.json. For each such clip
     * whose file STILL EXISTS, copy it into the durable {@code getFilesDir()/faditor_audio} and rewrite the
     * URI so a later cache-clear can't break the track. Idempotent (a files/-based URI has no
     * "cache/faditor_audio" segment → skipped), fully GUARDED (any per-clip failure leaves that clip's
     * original URI untouched — never breaks loading), saves once if anything moved.
     */
    private void migrateAudioClipsToDurableStorage() {
        if (project == null) return;
        java.io.File durableDir = new java.io.File(getFilesDir(), "faditor_audio");
        int moved = 0;
        for (AudioClip ac : project.getTimeline().getAudioClips()) {
            try {
                Uri uri = ac.getSourceUri();
                if (uri == null || !"file".equals(uri.getScheme())) continue;
                String path = uri.getPath();
                if (path == null || !path.contains("cache/faditor_audio")) continue;
                java.io.File src = new java.io.File(path);
                if (!src.exists()) continue; // cache already wiped — nothing left to rescue
                if (!durableDir.exists()) durableDir.mkdirs();
                java.io.File dst = new java.io.File(durableDir, src.getName());
                if (!dst.exists() || dst.length() != src.length()) {
                    try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
                if (dst.exists() && dst.length() > 0) {
                    ac.setSourceUri(Uri.fromFile(dst));
                    moved++;
                }
            } catch (Exception e) {
                FLog.w(TAG, "Audio durability migration skipped one clip: " + e.getMessage());
            }
        }
        if (moved > 0) {
            FLog.i(TAG, "Durability: migrated " + moved
                    + " extracted-audio clip(s) from cache to files/faditor_audio");
            saveProjectNow();
        }
    }

    private void recoverStaleCachePaths() {
        if (project == null) return;
        Timeline tl = project.getTimeline();
        boolean changed = false;

        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip clip = tl.getClip(i);
            Uri uri = clip.getSourceUri();
            String uriStr = uri.toString().toLowerCase();

            // Detect cache/remux paths
            if (uriStr.contains("cache") || uriStr.contains("remux")
                    || uriStr.contains("faditor_export_src")
                    || uriStr.contains("faditor_remux")
                    || uriStr.contains("/tmp/")) {
                // This is a cache path — the original is lost.
                // Mark the display name so the relink catalog can show it.
                String displayName = clip.getDisplayName();
                if (displayName == null) {
                    displayName = originalFilename(uri);
                    if (displayName != null) {
                        // Strip "remuxed_" prefix if present
                        displayName = displayName.replaceFirst("(?i)^remuxed_", "");
                        clip.setDisplayName(displayName);
                        changed = true;
                    }
                }
                FLog.w(TAG, "Clip " + i + " has stale cache path: " + uriStr
                        + " — will offer relink. Recovered name: " + displayName);
            }
        }

        if (changed) {
            saveProjectNow();
        }
    }

    /** Build a set of segment indices whose source media is currently inaccessible. */
    @NonNull
    private java.util.Set<Integer> computeMissingSegmentIndices() {
        java.util.Set<Integer> missing = new java.util.HashSet<>();
        if (project == null) return missing;
        Timeline tl = project.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (!isSourceResolvable(tl.getClip(i).getSourceUri())) {
                missing.add(i);
            }
        }
        return missing;
    }

    /** Count how many clips + audio clips have inaccessible sources. */
    private int countMissingMedia() {
        if (project == null) return 0;
        int count = 0;
        Timeline tl = project.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (!isSourceAccessible(tl.getClip(i).getSourceUri())) count++;
        }
        for (AudioClip ac : tl.getAudioClips()) {
            if (!isSourceAccessible(ac.getSourceUri())) count++;
        }
        return count;
    }

    /** Build the full media catalog entry list (all clips + audio, OK + MISSING). */
    @NonNull
    private java.util.List<RelinkCatalogBottomSheet.MediaEntry> buildMediaCatalog() {
        java.util.List<RelinkCatalogBottomSheet.MediaEntry> entries = new java.util.ArrayList<>();
        if (project == null) return entries;
        Timeline tl = project.getTimeline();

        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            String name = c.getDisplayName() != null
                    ? c.getDisplayName() : originalFilename(c.getSourceUri());
            if (name == null) name = "clip_" + i;
            boolean generated = isGeneratedMedia(c.getSourceUri());
            RelinkCatalogBottomSheet.MediaType type =
                    (c.isImageClip() || isImageMime(c.getSourceUri())
                            || isImageName(name))
                    ? RelinkCatalogBottomSheet.MediaType.IMAGE
                    : RelinkCatalogBottomSheet.MediaType.VIDEO;
            RelinkCatalogBottomSheet.Status status = isSourceAccessible(c.getSourceUri())
                    ? RelinkCatalogBottomSheet.Status.OK
                    : RelinkCatalogBottomSheet.Status.MISSING;
            entries.add(new RelinkCatalogBottomSheet.MediaEntry(
                    i, type, name, c.getSourceUri().toString(),
                    c.getSourceDurationMs(), generated, status));
        }

        // Audio clips: use negative indices to distinguish (encode as -1, -2, ...)
        int audioIdx = 0;
        for (AudioClip ac : tl.getAudioClips()) {
            String name = ac.getLabel() != null ? ac.getLabel()
                    : originalFilename(ac.getSourceUri());
            if (name == null) name = "audio_" + audioIdx;
            boolean generated = isGeneratedMedia(ac.getSourceUri());
            RelinkCatalogBottomSheet.Status status = isSourceAccessible(ac.getSourceUri())
                    ? RelinkCatalogBottomSheet.Status.OK
                    : RelinkCatalogBottomSheet.Status.MISSING;
            entries.add(new RelinkCatalogBottomSheet.MediaEntry(
                    -(audioIdx + 1), RelinkCatalogBottomSheet.MediaType.AUDIO,
                    name, ac.getSourceUri().toString(),
                    ac.getSourceDurationMs(), generated, status));
            audioIdx++;
        }
        return entries;
    }

    /** True if the URI points to an app-generated temp/cache file (remux, extract). */
    private boolean isGeneratedMedia(@NonNull Uri uri) {
        String s = uri.toString().toLowerCase();
        return s.contains("cache") || s.contains("remux")
                || s.contains("faditor_export_src")
                || s.contains("extracted_audio")
                || s.contains("/tmp/");
    }

    /** Show the relink catalog bottom sheet. */
    private void showRelinkCatalog() {
        if (project == null) return;
        relinkCatalogSheet = new RelinkCatalogBottomSheet();
        relinkCatalogSheet.setEntries(buildMediaCatalog());
        relinkCatalogSheet.setCallback(new RelinkCatalogBottomSheet.Callback() {
            @Override
            public void onRelinkRequested(@NonNull RelinkCatalogBottomSheet.MediaEntry entry) {
                startRelinkForEntry(entry);
            }

            @Override
            public void onRelinkAllRequested() {
                relinkAllMissing();
            }

            @Override
            public void onCatalogClosed() {
                saveProjectNow();
                // Reload the player if any clips changed
                if (countMissingMedia() == 0 && project != null
                        && project.getTimeline().getClipCount() > 0) {
                    selectedClipIndex = Math.min(selectedClipIndex,
                            project.getTimeline().getClipCount() - 1);
                    if (selectedClipIndex < 0) selectedClipIndex = 0;
                    selectSegment(-1);
                    selectSegment(selectedClipIndex);
                }
            }
        });
        relinkCatalogSheet.show(getSupportFragmentManager(), "relink_catalog");
    }

    /** Auto-show the catalog if media is missing on project load. */
    private void checkMissingMedia() {
        if (project == null) return;
        if (countMissingMedia() > 0) {
            showRelinkCatalog();
        }
        // Push missing segment indices to timeline view for visual indicators
        Set<Integer> missing = new HashSet<>();
        Timeline tl = project.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (!isSourceResolvable(tl.getClip(i).getSourceUri())) {
                missing.add(i);
            }
        }
        editorTimeline.setMissingSegments(missing);
    }

    /** Bridge: a file picker returned a file for the pending relink entry. */
    private void handleRelinkPick(int timelineIndex, @NonNull Uri pickedUri) {
        // Rebuild the entry for this index
        java.util.List<RelinkCatalogBottomSheet.MediaEntry> catalog = buildMediaCatalog();
        for (RelinkCatalogBottomSheet.MediaEntry e : catalog) {
            if (e.timelineIndex == timelineIndex) {
                confirmAndRelink(e, pickedUri);
                return;
            }
        }
        // Fallback: apply directly without confirmation
        String name = originalFilename(pickedUri);
        if (name == null) name = "file";
        applyRelink(timelineIndex, pickedUri, name, 0);
    }

    /** Start the file picker for a specific catalog entry. */
    private void startRelinkForEntry(@NonNull RelinkCatalogBottomSheet.MediaEntry entry) {
        relinkPendingIndex = entry.timelineIndex;
        if (entry.type == RelinkCatalogBottomSheet.MediaType.IMAGE) {
            imagePickerLauncher.launch(openDocumentIntent("*/*"));
        } else if (entry.type == RelinkCatalogBottomSheet.MediaType.AUDIO) {
            audioPickerLauncher.launch(openDocumentIntent("*/*"));
        } else {
            VideoSourceBottomSheet vs = new VideoSourceBottomSheet();
            vs.setLookingFor(entry.displayName);
            vs.setCallback(new VideoSourceBottomSheet.Callback() {
                @Override
                public void onRecordingSelected(@NonNull Uri videoUri) {
                    confirmAndRelink(entry, videoUri);
                }

                @Override
                public void onBrowseDevice() {
                    videoPickerLauncher.launch(openDocumentIntent("video/*"));
                }
            });
            vs.show(getSupportFragmentManager(), "relink_pick");
        }
    }

    /** Relink all missing entries one by one. */
    private void relinkAllMissing() {
        java.util.List<RelinkCatalogBottomSheet.MediaEntry> entries = buildMediaCatalog();
        for (RelinkCatalogBottomSheet.MediaEntry e : entries) {
            if (e.status == RelinkCatalogBottomSheet.Status.MISSING) {
                startRelinkForEntry(e);
                return; // The next one will be triggered after this one is resolved
            }
        }
    }

    /**
     * Confirm the replacement with the user, validate type/duration, and warn
     * about duplicates before applying.
     */
    private void confirmAndRelink(@NonNull RelinkCatalogBottomSheet.MediaEntry entry,
                                  @NonNull Uri newUri) {
        // Take persistable permission for content URIs (survives app restart)
        if ("content".equals(newUri.getScheme())) {
            try {
                getContentResolver().takePersistableUriPermission(
                        newUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
        }

        String newName = originalFilename(newUri);
        if (newName == null) newName = newUri.getLastPathSegment();
        if (newName == null) newName = "file";

        // Detect MIME type
        String mimeType = null;
        try {
            mimeType = getContentResolver().getType(newUri);
        } catch (Exception ignored) { }
        String typeDesc = mimeType != null ? mimeType : "unknown";

        // Check if this file is already used by another clip
        String newUriStr = newUri.toString();
        int dupIndex = -1;
        Timeline tl = project.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (i == entry.timelineIndex) continue;
            if (tl.getClip(i).getSourceUri().toString().equals(newUriStr)) {
                dupIndex = i;
                break;
            }
        }
        for (AudioClip ac : tl.getAudioClips()) {
            if (ac.getSourceUri().toString().equals(newUriStr)) {
                dupIndex = -2; // audio
                break;
            }
        }

        // Probe new file duration
        long newDuration = 0;
        if (entry.type == RelinkCatalogBottomSheet.MediaType.VIDEO) {
            newDuration = getVideoDuration(newUri);
        } else if (entry.type == RelinkCatalogBottomSheet.MediaType.AUDIO) {
            try {
                android.media.MediaMetadataRetriever r =
                        new android.media.MediaMetadataRetriever();
                r.setDataSource(this, newUri);
                String d = r.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                r.release();
                if (d != null) newDuration = Long.parseLong(d);
            } catch (Exception ignored) { }
        }

        // Build confirmation message
        StringBuilder msg = new StringBuilder();
        msg.append(getString(R.string.faditor_relink_confirm_msg,
                entry.timelineIndex >= 0 ? entry.timelineIndex + 1 : 0,
                entry.displayName,
                entry.type.name().toLowerCase(),
                newName,
                typeDesc));

        // Warn about duplicate usage
        if (dupIndex >= 0) {
            msg.append("\n\n").append(getString(R.string.faditor_relink_warn_same_file,
                    dupIndex + 1));
        } else if (dupIndex == -2) {
            msg.append("\n\n⚠ This file is already used by an audio clip.");
        }

        // Warn about duration mismatch
        if (entry.durationMs > 0 && newDuration > 0) {
            long diff = Math.abs(newDuration - entry.durationMs);
            if (diff > entry.durationMs * 0.2) { // >20% difference
                msg.append("\n\n").append(getString(R.string.faditor_relink_warn_duration,
                        formatMs(entry.durationMs), formatMs(newDuration)));
            }
        }

        // Make final copies for the lambda
        final Uri fnUri = newUri;
        final String fnName = newName;
        final long fnDur = newDuration;
        final int fnIdx = entry.timelineIndex;

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.faditor_relink_confirm_title)
                .setMessage(msg.toString())
                .setPositiveButton(R.string.faditor_relink_confirm_yes, (d, w) -> {
                    applyRelink(fnIdx, fnUri, fnName, fnDur);
                })
                .setNegativeButton(R.string.faditor_relink_skip, (d, w) -> {
                    relinkPendingIndex = -1;
                    // If relinking all, move to next missing
                    if (relinkCatalogSheet != null) {
                        relinkAllMissing();
                    }
                })
                .show();
    }

    private boolean isImageMime(@NonNull Uri uri) {
        String mime = null;
        try { mime = getContentResolver().getType(uri); } catch (Exception ignored) {}
        if (mime != null) return mime.startsWith("image/");
        String path = uri.getPath();
        if (path == null) return false;
        return isImageName(path);
    }

    private boolean isImageName(@NonNull String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif")
                || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".heic") || lower.endsWith(".heif")
                || lower.endsWith(".avif");
    }

    /** Apply the relink: replace the clip/audio source, update the catalog, save. */
    private void applyRelink(int timelineIndex, @NonNull Uri newUri,
                             @NonNull String newName, long newDuration) {
        // Invalidate resolvability cache — sources may have changed
        resolvableCache.clear();
        Timeline tl = project.getTimeline();

        if (timelineIndex >= 0) {
            // Video/image clip
            if (timelineIndex >= tl.getClipCount()) return;
            Clip oldClip = tl.getClip(timelineIndex);
            boolean isImage = isImageMime(newUri);
            newUri = copyUriToInternalStorage(newUri, isImage ? "images" : "videos");
            Clip newClip = oldClip.relinked(newUri);
            newClip.setImageClip(isImage);

            // Update duration + clamp trim points if the new file is shorter
            if (newDuration > 0) {
                newClip.setSourceDurationMs(newDuration);
                long in = Math.min(oldClip.getInPointMs(), newDuration - 1);
                long out = Math.min(Math.max(oldClip.getOutPointMs(), in + 1), newDuration);
                if (out <= in) { in = 0; out = newDuration; }
                newClip.setInPointMs(in);
                newClip.setOutPointMs(out);
            }
            newClip.setDisplayName(newName);

            undoManager.recordAction(new EditActions.ReplaceClipSourceAction(
                    tl, timelineIndex, oldClip, newClip));
            tl.removeClip(timelineIndex);
            tl.addClip(timelineIndex, newClip);

            // Auto-relink siblings from the same folder
            int autoFound = autoRelinkSiblings(newUri, timelineIndex);
            if (autoFound > 0) {
                Toast.makeText(this,
                        getString(R.string.faditor_relink_auto_found, autoFound),
                        Toast.LENGTH_LONG).show();
            }
        } else {
            // Audio clip (negative index: -1 = first, -2 = second, ...)
            int audioIdx = -(timelineIndex + 1);
            java.util.List<AudioClip> audioClips = tl.getAudioClips();
            if (audioIdx < 0 || audioIdx >= audioClips.size()) return;
            AudioClip ac = audioClips.get(audioIdx);
            ac.setSourceUri(newUri);
            if (newDuration > 0) {
                ac.setSourceDurationMs(newDuration);
                ac.setOutPointMs(newDuration);
            }
            ac.setLabel(newName);
        }

        saveProjectNow();

        // Update the catalog sheet
        if (relinkCatalogSheet != null) {
            relinkCatalogSheet.setEntries(buildMediaCatalog());
        }

        // Reload the player for the current clip
        if (timelineIndex >= 0 && timelineIndex == selectedClipIndex) {
            selectedClipIndex = -1;
            hideMissingOverlay();
            selectSegment(timelineIndex);
        }

        // Rebuild timeline segment data and evict stale thumbnail cache
        if (editorTimeline != null) {
            editorTimeline.setTimeline(tl, selectedClipIndex);
            editorTimeline.setMissingSegments(computeMissingSegmentIndices());
        }
        syncTimelineOverlays();

        // Continue relinking if more are missing
        if (countMissingMedia() > 0 && relinkCatalogSheet != null) {
            // Don't auto-advance; let the user pick the next one from the catalog
        }

        relinkPendingIndex = -1;
    }

    /**
     * After one manual relink, try to resolve the remaining missing clips from
     * the same folder by matching their original filenames. Returns how many
     * were auto-relinked. Skips the just-relinked clip.
     *
     * <p>Also handles the common case where multiple clips all came from the
     * same original file (e.g. it was chopped into segments). If the picked
     * file's name matches what a missing clip was looking for (after stripping
     * "remuxed_" prefix), all such clips are relinked to the same file.</p>
     */
    private int autoRelinkSiblings(@NonNull Uri justPicked, int skipIndex) {
        File picked = resolveToFile(justPicked);
        if (picked == null) return 0;
        File folder = picked.getParentFile();
        if (folder == null || !folder.isDirectory()) return 0;

        String pickedName = picked.getName();
        // Strip "remuxed_" prefix for matching
        String cleanPickedName = pickedName.replaceFirst("(?i)^remuxed_", "");

        Timeline tl = project.getTimeline();
        int found = 0;
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (i == skipIndex) continue;
            Clip c = tl.getClip(i);
            if (isSourceAccessible(c.getSourceUri())) continue;

            String name = originalFilename(c.getSourceUri());
            if (name == null) {
                // Try display name
                name = c.getDisplayName();
            }
            if (name == null) continue;

            // Strip "remuxed_" prefix from the missing clip's name too
            String cleanName = name.replaceFirst("(?i)^remuxed_", "");

            Uri matchedUri = null;

            // Match by exact filename in the same folder
            File candidate = new File(folder, name);
            if (candidate.exists() && candidate.canRead()) {
                matchedUri = Uri.fromFile(candidate);
            }

            // Match by cleaned name (handles remuxed_ prefix mismatch)
            if (matchedUri == null && cleanName.equals(cleanPickedName)) {
                matchedUri = justPicked;
            }

            // Try cleaned name in the same folder
            if (matchedUri == null) {
                candidate = new File(folder, cleanName);
                if (candidate.exists() && candidate.canRead()) {
                    matchedUri = Uri.fromFile(candidate);
                }
            }

            if (matchedUri != null) {
                Clip relinked = c.relinked(matchedUri);
                // Probe the new file's duration and CLAMP trim points.
                // Without this, relinked clips keep stale sourceDurationMs /
                // trim points from the remuxed file, causing seeks and
                // playback to break (play goes to start of clip).
                long newDur = getVideoDuration(matchedUri);
                if (newDur > 0) {
                    relinked.setSourceDurationMs(newDur);
                    long newIn = Math.min(c.getInPointMs(), newDur - 1);
                    long newOut = Math.min(c.getOutPointMs(), newDur);
                    if (newOut <= newIn) { newIn = 0; newOut = newDur; }
                    relinked.setInPointMs(newIn);
                    relinked.setOutPointMs(newOut);
                }
                tl.removeClip(i);
                tl.addClip(i, relinked);
                found++;
            }
        }
        return found;
    }

    /** Best-effort original filename from a (possibly dead) source URI. */
    @Nullable
    private String originalFilename(@NonNull Uri uri) {
        String s = uri.getLastPathSegment();
        if (s == null) s = uri.getPath();
        if (s == null) return null;
        try {
            s = java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception ignored) { }
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        return s.isEmpty() ? null : s;
    }

    @NonNull
    private String formatMs(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    @Nullable
    private File resolveToFile(@NonNull Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath());
        }
        String resolved = resolveSafPath(uri);
        if (resolved != null) {
            File f = new File(resolved);
            if (f.exists() && f.canRead()) return f;
        }
        return null;
    }

    private final java.util.HashMap<String, Uri> playbackUriCache = new java.util.HashMap<>();

    @NonNull
    private Uri resolvePlaybackUri(@NonNull Uri sourceUri) {
        // Cached: needsRemux() reads the file header from disk, and scrubbing over a
        // transition resolves the same URIs on every tick — without this the header
        // read + remux check ran on the UI thread each frame and snagged the scrub.
        String key = sourceUri.toString();
        Uri cached = playbackUriCache.get(key);
        if (cached != null) return cached;
        Uri resolved = sourceUri;
        File sourceFile = resolveToFile(sourceUri);
        if (sourceFile != null) {
            if (remuxer.needsRemux(sourceFile)) {
                File remuxed = remuxer.remuxSync(sourceFile);
                if (remuxed != null) {
                    resolved = Uri.fromFile(remuxed);
                }
            }
            if (resolved.equals(sourceUri)) {
                resolved = Uri.fromFile(sourceFile);
            }
        }
        playbackUriCache.put(key, resolved);
        return resolved;
    }

    @NonNull
    private Clip getPlaybackClip(@NonNull Clip clip) {
        Uri playbackUri = resolvePlaybackUri(clip.getSourceUri());
        if (playbackUri.equals(clip.getSourceUri())) {
            return clip;
        }
        return clip.relinked(playbackUri);
    }

    // ── L2: reversed-segment (ping-pong) cache plumbing ──────────────────

    private com.fadcam.ui.faditor.export.ReversedSegmentCache reversedCache() {
        if (reversedCache == null) {
            reversedCache = new com.fadcam.ui.faditor.export.ReversedSegmentCache(this);
        }
        return reversedCache;
    }

    /**
     * PURE LOOKUP for the gapless engine's {@code SourceResolver.resolveReversed}: returns the
     * CACHED baked-reversed file URI for a PING_PONG clip's current trimmed sub-range, or null if
     * the clip is not PING_PONG, its span is too long to bake, or the bake hasn't finished. Never
     * blocks / never bakes — the bake is warmed off-main by {@link #kickReverseBakeIfNeeded} (drawer)
     * or {@code ExportService} (pre-export). Determines the clip's gapless eligibility.
     */
    @Nullable
    private Uri resolveReversedUri(@NonNull Clip clip) {
        // PARKED: while ping-pong is dormant, NEVER hand the gapless engine a reversed file. With
        // this returning null, MasterPlaybackEngine.isEligible treats every PING_PONG clip as
        // ineligible, so the whole project falls to the legacy path — where the tick plays a
        // PING_PONG clip as a plain forward-tail wrap (a NORMAL loop). Graceful degrade, no black,
        // no baked item ever referenced. (Un-park by flipping Clip.PING_PONG_PARKED.)
        if (Clip.PING_PONG_PARKED) return null;
        if (clip.getLoopMode() != Clip.LOOP_MODE_PING_PONG || !clip.hasLoopExtension()
                || clip.isImageClip()) {
            return null;
        }
        Uri src = clip.getSourceUri();
        long in = clip.getInPointMs();
        long out = clip.getOutPointMs();
        if (!com.fadcam.ui.faditor.export.ReversedSegmentCache.canBake(in, out)) return null;
        if (reversedCache().isCached(src, in, out)) {
            Uri revUri = Uri.fromFile(reversedCache().fileFor(src, in, out));
            // RANK-1: a reversed URI that already failed to decode in the gapless player is poisoned
            // for this session → return null so this clip degrades to forward reps ONLY (scoped),
            // instead of re-feeding the player a file that black-outs the whole timeline.
            if (poisonedReversedUris.contains(revUri)) {
                FLog.w(TAG, "resolveReversedUri: URI is POISONED (prior decode failure) — "
                        + "degrading clip " + clip.getId() + " to forward reps");
                return null;
            }
            return revUri;
        }
        return null;
    }

    /**
     * RANK-1 recovery hook (registered on the player manager, fired on the main thread from the
     * gapless engine's onPlayerError). A baked reversed leg failed to decode. Poison its URI so the
     * resolver stops handing it out, rebuild the gapless playlist (that clip now degrades to forward
     * reps — same clamp math, scoped to this ONE clip), and reseek to the pre-error visual position
     * so playback resumes exactly where it black-outed instead of blacking the whole timeline.
     */
    private void onReverseWindowFailed(@Nullable String clipId, @Nullable Uri reversedUri,
                                       @Nullable String resumeClipId, long resumeVisualPosMs) {
        // Poison the reversed URI. Prefer the URI the engine reported; if absent, derive it from the
        // clip's current trim range so a re-resolve still returns null.
        Uri toPoison = reversedUri;
        if (toPoison == null && clipId != null) {
            Clip c = findClipById(clipId);
            if (c != null
                    && com.fadcam.ui.faditor.export.ReversedSegmentCache.canBake(
                            c.getInPointMs(), c.getOutPointMs())) {
                toPoison = Uri.fromFile(reversedCache().fileFor(
                        c.getSourceUri(), c.getInPointMs(), c.getOutPointMs()));
            }
        }
        if (toPoison != null) {
            poisonedReversedUris.add(toPoison);
            FLog.w(TAG, "RANK-1 recovery: POISONED reversed URI " + toPoison
                    + " (clip " + clipId + ") — degrading this clip to forward reps");
        } else {
            FLog.w(TAG, "RANK-1 recovery: reverse-leg failure with no poisonable URI (clip "
                    + clipId + ") — rebuilding anyway");
        }
        if (playerManager == null) return;
        // A recovery rebuild is a user-visible timeline change → bump the generation so any in-flight
        // stale bake auto-promote is discarded (rank-1c) and can't re-introduce the poisoned file.
        rebuildGeneration++;
        // rebuildGaplessTimeline() captures the current clip-id + visual position (which, at the
        // error, is the failing reverse leg's pre-error visual position) and restores it after the
        // rebuild — so the reseek to the pre-error position is handled there. Force playback to
        // resume afterward (the errored player's play-intent may not survive the teardown).
        playerManager.rebuildGaplessTimeline();
        if (playerManager.isGapless()) {
            playerManager.play();
        }
    }

    /**
     * The on-disk file ffmpeg should reverse for {@code clip} — a remuxed/faststart copy when the
     * raw source is a fragmented MP4 (so the reverse bake's fast-seek is accurate + linear-decodes
     * cleanly), otherwise the raw file. Returns null if no local file can be resolved.
     */
    @Nullable
    private File resolveReverseInputFile(@NonNull Clip clip) {
        Uri playbackUri = resolvePlaybackUri(clip.getSourceUri()); // remuxes fMP4 if needed (cached)
        File f = resolveToFile(playbackUri);
        if (f == null) f = resolveToFile(clip.getSourceUri());
        return f;
    }

    /**
     * Kick an OFF-MAIN reverse bake for a PING_PONG clip if one isn't cached / in flight, then on
     * completion rebuild the gapless playlist on the MAIN thread so the (now-eligible) project
     * promotes to the gapless engine with a TRUE reverse leg. Mirrors L1's lesson that a loop edit
     * must rebuild the engine — here the rebuild is deferred until the bake lands. Long spans
     * (> guard) skip the bake and show a one-time toast; the clip stays on the legacy forward-tail.
     */
    private void kickReverseBakeIfNeeded(@NonNull Clip clip) {
        // PARKED: no reverse bake is ever kicked while ping-pong is dormant — from ANY caller
        // (drawer applyLoopMode/extendLoop, trim-edge drag onTrimFinished, or loop-edge drag
        // onLoopTrimFinished). This is the "no new bakes, incl. from resize" guarantee.
        if (Clip.PING_PONG_PARKED) return;
        if (clip.getLoopMode() != Clip.LOOP_MODE_PING_PONG || !clip.hasLoopExtension()
                || clip.isImageClip()) {
            return;
        }
        final Uri src = clip.getSourceUri();
        final long in = clip.getInPointMs();
        final long out = clip.getOutPointMs();
        if (!com.fadcam.ui.faditor.export.ReversedSegmentCache.canBake(in, out)) {
            // Guard: span too long to bake — one-time toast, keep forward-tail fallback.
            if (!reverseLongGuardToastShown) {
                reverseLongGuardToastShown = true;
                Toast.makeText(this, R.string.faditor_reverse_long_loop_later,
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (reversedCache().isCached(src, in, out)) return; // already baked
        final String key = com.fadcam.ui.faditor.export.ReversedSegmentCache.keyFor(src, in, out);
        if (!reverseBakeInFlight.add(key)) return; // already baking this exact range
        final Clip bakeClip = clip;
        final String clipId = clip.getId();
        // RANK-1c: capture the rebuild generation at KICK time. If a later user edit rebuilds the
        // playlist before this bake lands, the generation advances and we DISCARD the stale
        // auto-promote below — so a bake finishing after the user re-trimmed/removed the loop can't
        // revert the timeline (the old resize-revert race).
        final int kickGeneration = rebuildGeneration;
        if (reverseBakeExecutor == null) {
            reverseBakeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        }
        reverseBakeExecutor.execute(() -> {
            // Resolve the input file OFF-MAIN — it may trigger a (blocking) fMP4 remux.
            File input = resolveReverseInputFile(bakeClip);
            File baked = reversedCache().bakeSync(src, input, in, out);
            reverseBakeInFlight.remove(key);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || playerManager == null) return;
                if (baked == null) {
                    FLog.w(TAG, "Reverse bake unavailable for clip " + clipId
                            + " — staying on forward-tail");
                    return;
                }
                // RANK-1c: discard a stale auto-promote — a user edit rebuilt since the kick.
                if (rebuildGeneration != kickGeneration) {
                    FLog.i(TAG, "Reverse bake for clip " + clipId + " landed but rebuild generation "
                            + "advanced (" + kickGeneration + "->" + rebuildGeneration
                            + ") — DISCARDING stale auto-promote");
                    return;
                }
                // The bake landed. If the clip is still PING_PONG with the same range, rebuild the
                // gapless playlist so the engine re-evaluates eligibility and uses the reversed file.
                Clip cur = findClipById(clipId);
                if (cur != null && cur.getLoopMode() == Clip.LOOP_MODE_PING_PONG
                        && cur.getInPointMs() == in && cur.getOutPointMs() == out
                        && !cur.isImageClip()) {
                    FLog.i(TAG, "Reverse bake ready for clip " + clipId
                            + " — rebuilding gapless playlist for TRUE ping-pong");
                    rebuildGeneration++;
                    playerManager.rebuildGaplessTimeline();
                }
            });
        });
    }

    private void loadClipForPlayback(@NonNull Clip clip) {
        if (playerManager == null) return;
        transitionPlaybackActive = false;
        hideTransitionPreview();
        playerManager.loadClip(getPlaybackClip(clip));
        durationCorrectionPending = true;
        if (playerView != null) playerView.setAlpha(1f);
    }

    @Nullable
    private String resolveSafPath(@NonNull Uri uri) {
        String uriPath = uri.getPath();
        if (uriPath == null || !uriPath.contains(":")) return null;
        try {
            int lastColon = uriPath.lastIndexOf(':');
            String encodedRel = uriPath.substring(lastColon + 1);
            String relativePath = java.net.URLDecoder.decode(encodedRel, "UTF-8");

            String beforeColon = uriPath.substring(0, lastColon);
            int lastSlash = beforeColon.lastIndexOf('/');
            String encodedId = lastSlash >= 0
                    ? beforeColon.substring(lastSlash + 1) : beforeColon;
            String storageId = java.net.URLDecoder.decode(encodedId, "UTF-8");

            String mountPoint = "primary".equalsIgnoreCase(storageId)
                    ? "/storage/emulated/0"
                    : "/storage/" + storageId;

            String fullPath = mountPoint + "/" + relativePath;
            FLog.d(TAG, "resolveSafPath: storageId='" + storageId + "' → " + fullPath);
            return fullPath;
        } catch (Exception e) {
            FLog.w(TAG, "resolveSafPath failed for " + uri, e);
            return null;
        }
    }

    private void showRemuxProgress() {
        if (remuxProgressOverlay != null) {
            remuxProgressOverlay.setVisibility(View.VISIBLE);
            remuxProgressOverlay.setAlpha(0f);
            remuxProgressOverlay.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void hideRemuxProgress() {
        if (remuxProgressOverlay != null) {
            remuxProgressOverlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                remuxProgressOverlay.setVisibility(View.GONE);
            }).start();
        }
    }

    private void initProject(@NonNull Uri videoUri) {
        initProject(new ArrayList<>(java.util.Collections.singletonList(videoUri)));
    }

    private void initProject(@NonNull List<Uri> videoUris) {
        if (videoUris == null || videoUris.isEmpty()) {
            FLog.e(TAG, "No video URIs provided to initProject");
            return;
        }
        long totalDuration = 0;
        for (Uri uri : videoUris) {
            long durationMs = getVideoDuration(uri);
            if (durationMs <= 0) {
                durationMs = IMAGE_CLIP_DURATION_MS;
            }
            totalDuration += durationMs;
        }

        project = new FaditorProject(videoUris.size() > 1 ? "Batch Project" : "Untitled");
        updateEditorTitle();
        for (Uri uri : videoUris) {
            long durationMs = getVideoDuration(uri);
            if (durationMs <= 0) durationMs = IMAGE_CLIP_DURATION_MS;
            Clip clip = new Clip(uri, durationMs);
            project.getTimeline().addClip(clip);
        }
        refreshTotalTimeDisplay();

        FLog.d(TAG, "Project created from " + videoUris.size() + " clips; duration=" + totalDuration + "ms");
    }

    private void updateEditorTitle() {
        if (editorTitle == null || project == null) return;
        String title = project.getName();
        if (title == null || title.trim().isEmpty()) {
            title = getString(R.string.faditor_editor_title);
        }
        editorTitle.setText(title.trim());
        editorTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        editorTitle.setMarqueeRepeatLimit(-1);
        editorTitle.setSelected(true);
    }

    private void showRenameProjectDialog() {
        if (project == null) return;
        String rawName = project.getName();
        final String currentName = rawName != null ? rawName : "";

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        EditText input = new EditText(this);
        input.setText(currentName);
        input.setSelection(0, currentName.length());
        input.setTextColor(0xFFFFFFFF);
        input.setHint("Project name");
        root.addView(input);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Rename project")
                .setView(root)
                .setPositiveButton("Rename", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty() && !newName.equals(currentName)) {
                        project.setName(newName);
                        scheduleAutoSave();
                        updateEditorTitle();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void initPlayer() {
        playerManager = new FaditorPlayerManager(this);
        getLifecycle().addObserver(playerManager);
        playerManager.setPlayerView(playerView);

        // ── M-COMP-0: hand the manager the master track + a seekable-URI resolver so it
        // can (behind FaditorPlayerManager.GAPLESS_ENGINE) play the whole track as a
        // pre-buffered ClippingConfiguration playlist and cross plain cuts with no cold
        // re-prepare. Route auto-seam UI sync through onGaplessSeam(). No-op when the flag
        // is off or the project isn't eligible (loops/transitions/images keep the legacy path).
        playerManager.setGaplessTimeline(project.getTimeline(),
                new MasterPlaybackEngine.SourceResolver() {
                    @NonNull
                    @Override
                    public Uri resolveSeekable(@NonNull Clip clip) {
                        return resolvePlaybackUri(clip.getSourceUri());
                    }

                    // L2: baked TRUE-reversed file for a PING_PONG clip (cached-only lookup).
                    @Nullable
                    @Override
                    public Uri resolveReversed(@NonNull Clip clip) {
                        return resolveReversedUri(clip);
                    }
                },
                this::onGaplessSeam);

        // ── RANK-1 resilience: when the gapless player errors on a baked REVERSED leg, poison that
        // reversed URI (so resolveReversedUri returns null → this clip degrades to forward reps
        // ONLY, scoped per-clip) and rebuild the playlist, reseeking to the pre-error visual
        // position. This contains what was the ffcdc86 whole-timeline blackout to zero clips.
        playerManager.setErrorRecoveryListener(this::onReverseWindowFailed);

        // Load the clip
        Clip clip = getSelectedClip();
        playerManager.loadClip(clip);

        // Listen for playback state changes
        playerManager.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                // Keep screen on only during active playback
                if (isPlaying) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    // REVIEW FIX (2026-07-05): playheadUpdater now self-terminates while
                    // paused (the CPU optimization above at ~line 571), so EVERY real
                    // playback start must re-arm it or the playhead/time display/caption
                    // sync/audio-player scheduling all freeze on first play. This listener
                    // fires for every play edge (user play, gapless resume, auto-advance),
                    // making it the single restart point; remove-then-post keeps the loop
                    // single-instance even if the audio-tail path posted it explicitly.
                    playheadHandler.removeCallbacks(playheadUpdater);
                    playheadHandler.post(playheadUpdater);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                // Don't override button state during audio-tail (video paused, audio playing)
                if (!audioTailActive) {
                    updatePlayPauseButton(isPlaying);
                }
                // M-COMP-2: the overlay decoder must see every master play/pause EDGE —
                // the playhead tick loop can stop (pause/ENDED) before delivering one,
                // which left the PiP free-running (device-caught 2026-07-05).
                if (overlayVideoLayer != null && !overlayVideoLayer.isEmpty()) {
                    overlayVideoLayer.setPlayheadMs(lastPlayheadAbsoluteMs, isPlaying);
                }
                // Sync audio player with ExoPlayer state
                if (isPlaying) {
                    syncAndPlayAudioPlayer();
                } else if (!audioTailActive) {
                    pauseAudioPlayer();
                }
            }

            @Override
            public void onVideoSizeChanged(@NonNull androidx.media3.common.VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    lastDecodedVideoW = videoSize.width;
                    lastDecodedVideoH = videoSize.height;
                }
                // The real decoded video size is only known now. The crop-zoom
                // preview and overlay geometry are both derived from it, so they
                // were being computed from a stale/zero size right after a seek or
                // a clip switch (different-resolution clips) — which is why the crop
                // looked inconsistent and captions resized when scrubbing. Recompute
                // them here so the preview converges to the correct, export-matching
                // result instead of whatever transient size was current earlier.
                if (!inCropMode) {
                    updatePreviewTransforms();
                }
                if (overlayLayer != null) {
                    overlayLayer.post(() -> overlayLayer.rebuild());
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED && !audioTailActive) {
                    updatePlayPauseButton(false);
                    pauseAudioPlayer(); // don't let the music run on past the video end
                }
                // Once ExoPlayer is READY, correct the duration if needed.
                // MediaMetadataRetriever is unreliable for fragmented MP4;
                // ExoPlayer parses actual content and reports the real duration.
                if (playbackState == Player.STATE_READY && durationCorrectionPending) {
                    durationCorrectionPending = false;
                    correctDurationFromPlayer();
                }
            }
        });

        // Sync initial volume and speed from clip state
        playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
        playerManager.setPlaybackSpeed(clip.getSpeedMultiplier(), clip.isPitchCompensationEnabled());
    }

    /**
     * M-COMP-0 seam callback: fired by {@link FaditorPlayerManager}'s gapless engine when the
     * player auto-advances across a plain cut to a new master window. Runs ONLY the non-player
     * half of {@link #advanceToSegment} (UI sync, per-clip speed/volume, timeline playhead) —
     * the player has already crossed the seam warm, so there is no re-prepare here. Runs on the
     * main thread (ExoPlayer callbacks are delivered on the app main thread).
     *
     * <p>L1: the engine now ALSO plays NORMAL-loop clips as extra playlist windows (before/after
     * loop reps). It fires this callback ONLY when the TIMELINE CLIP actually changes — reps of
     * the SAME looped clip are crossed silently inside the engine (see
     * {@code MasterPlaybackEngine.SeamListener}), so the caption/overlay rebind and playhead
     * re-homing below never re-run mid-loop, and {@code newIndex} is always a genuinely NEW clip.</p>
     *
     * @param newIndex    the master clip index the engine advanced to (a real clip change — never
     *                    fired for a same-clip loop-rep wrap)
     * @param autoAdvance true when playback PLAYED THROUGH a plain cut (the playhead is naturally
     *                    at the new clip's start); false when the window change was caused by a
     *                    user ruler tap/scrub seeking across a boundary. On a user seek the tapped
     *                    position was already set by {@link Listener#onPlayheadSeeked}, so this
     *                    handler must NOT re-home the playhead to the new clip's start — doing so
     *                    snapped it back (to 0 when the target was clip 0). See handoff 2026-07-02.
     */
    private void onGaplessSeam(int newIndex, boolean autoAdvance) {
        if (project == null || project.getTimeline() == null) return;
        Timeline timeline = project.getTimeline();
        if (newIndex < 0 || newIndex >= timeline.getClipCount()) return;
        FLog.d(TAG, "onGaplessSeam -> segment " + newIndex + " autoAdvance=" + autoAdvance);
        selectedClipIndex = newIndex;
        Clip nextClip = getSelectedClip();
        // Keep the player manager's tracked clip pointed at the new window (no player op — the
        // engine already crossed the cut) so currentClip-derived getters stay consistent.
        playerManager.syncGaplessCurrentClip(nextClip);
        // Image windows are DISPLAYED by the proven Glide overlay (showImagePreview hides the
        // PlayerView) while the engine's image window drives the clock underneath; crossing back
        // into a video window must restore the player surface or the image would cover it.
        if (nextClip.isImageClip()) {
            hideMissingOverlay();
            showImagePreview(nextClip.getSourceUri());
        } else {
            hideImagePreview();
        }
        editorTimeline.setTimeline(timeline, selectedClipIndex);
        editorTimeline.setTrimFromClip(nextClip);
        updateVolumeUI(nextClip.getVolumeLevel(), nextClip.isAudioMuted());
        updateOpacityUI();
        updateSpeedUI(nextClip.getSpeedMultiplier());
        updateRotateUI(nextClip.getRotationDegrees());
        updateFlipUI(nextClip.isFlipHorizontal(), nextClip.isFlipVertical());
        updateCropUI(nextClip.getCropPreset());
        updateFilterUI(nextClip);
        applyPreviewColorGrade(nextClip);
        // Per-clip volume + speed follow the new window (engine sets speed too, but keep the
        // UI + LoudnessEnhancer path here identical to advanceToSegment's non-player half).
        playerManager.setVolume(nextClip.isAudioMuted() ? 0f : nextClip.getVolumeLevel());
        playerManager.setPlaybackSpeed(nextClip.getSpeedMultiplier(), nextClip.isPitchCompensationEnabled());
        updatePreviewTransforms();
        // Re-home the playhead to the new clip's start ONLY when playback auto-advanced across the
        // cut. For a user-initiated cross-item SEEK (ruler tap/scrub), onPlayheadSeeked already set
        // the authoritative tapped position; re-homing here would clobber it (snap-to-0 on clip 0).
        if (autoAdvance) {
            float startFraction = (float) nextClip.getInPointMs() / nextClip.getSourceDurationMs();
            editorTimeline.setPlayheadFraction(startFraction);
        }
    }

    private void initTimeline() {
        Clip clip = getSelectedClip();
        editorTimeline.setTrimFromClip(clip);
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);

        // If audio clips exist (e.g. from a saved project), load them
        if (project.getTimeline().hasAudioClips()) {
            editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
            prepareAudioPlayer();
            updateAudioToolUI();
        }

        FLog.d(TAG, "Timeline initialized: sourceDuration=" + clip.getSourceDurationMs()
                + "ms, in=" + clip.getInPointMs() + ", out=" + clip.getOutPointMs());
    }

    private void initToolbar() {
        // Play/Pause button
        btnPlayPause.setOnClickListener(v -> {
            Clip c = getSelectedClip();
            // MISSING source: show overlay and don't attempt playback
            if (c != null && !c.isGeneratedSlide() && !isSourceResolvable(c.getSourceUri())) {
                showMissingOverlay(c);
                return;
            }
            // If we're in the audio-tail (the visual track has ended but music/audio is still
            // playing past it), a tap must PAUSE everything — regardless of whether the last
            // visual clip is video or image. (Bug: the image branch below restarted image
            // playback and never paused the audio players, so the music kept going.)
            if (audioTailActive) {
                audioTailActive = false;
                if (playerManager != null) playerManager.pause();
                pauseAudioPlayer();
                stopImagePlayback();
                updatePlayPauseButton(false);
                return;
            }
            if (c.isImageClip() && (playerManager == null || !playerManager.isGapless())) {
                // Image clip, LEGACY path only: toggle internal timer playback. In gapless mode
                // the engine plays the image as a native playlist window (P0 fix 2026-07-07), so
                // transport falls through to the ExoPlayer branch below like any other clip.
                if (imagePlaybackActive) {
                    // Pause: record current position
                    imagePlaybackStartOffsetMs = getImagePlaybackPositionMs();
                    stopImagePlayback();
                } else {
                    // Compute current position from playhead
                    long currentPos = imagePlaybackStartOffsetMs;
                    long clipDuration = c.getTrimmedDurationMs();
                    if (currentPos >= clipDuration) {
                        // At end: restart from beginning
                        currentPos = 0;
                    }
                    startImagePlayback(currentPos);
                }
            } else {
                // Video clip: use ExoPlayer
                if (playerManager == null) {
                    FLog.w(TAG, "Play pressed before player initialized");
                    return;
                }
                if (playerManager.isPlaying() || audioTailActive) {
                    audioTailActive = false;
                    playerManager.pause();
                    pauseAudioPlayer();
                    updatePlayPauseButton(false);
                } else {
                    long playheadMs = editorTimeline.getPlayheadPositionMs();
                    long videoEndMs = totalEffectiveMs();
                    long timelineEndMs = editorTimeline.getTimelineEndMs();

                    if (playheadMs >= videoEndMs && timelineEndMs > videoEndMs) {
                        // Playhead is in audio-only region past video
                        // Enter audio-tail mode directly
                        audioTailActive = true;
                        audioTailStartWall = android.os.SystemClock.elapsedRealtime();
                        audioTailStartMs = playheadMs;
                        btnPlayPause.setText("pause");
                        syncAndPlayAudioPlayer();
                        playheadHandler.post(playheadUpdater);
                        FLog.d(TAG, "Play from audio-tail region: playhead=" + playheadMs + " videoEnd=" + videoEndMs);
                    } else {
                        int playSegment = editorTimeline.getSegmentAtPlayhead();
                        if (playSegment >= 0 && playSegment != selectedClipIndex) {
                            selectSegment(playSegment);
                        }
                        // CRITICAL: Ensure player position matches timeline playhead before play.
                        // Convert timeline playhead position to source-relative position
                        // accounting for clip speed (effective time → source time).
                        Clip playClip = getSelectedClip();
                        // For an AI slide, playback uses the rendered MP4 (present once
                        // the slide has been rendered/exported). Hide the live WebView
                        // and load the clip so the normal ExoPlayer path plays it.
                        if (playClip != null && playClip.isGeneratedSlide()) {
                            hideSlidePreview();
                            loadClipForPlayback(playClip);
                        }
                        long segmentStartMs = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
                        long relativePlayheadMs = Math.max(0, playheadMs - segmentStartMs);
                        if (playClip != null && playClip.getSpeedMultiplier() > 0) {
                            // relativePlayheadMs is in EFFECTIVE time (post-speed).
                            // seekTo expects SOURCE time (pre-speed) relative to trimStart.
                            relativePlayheadMs = (long)(relativePlayheadMs * playClip.getSpeedMultiplier());
                        }
                        if (playClip != null && playClip.isImageClip() && playerManager.isGapless()) {
                            // Scrubs over an image never seek the engine (the Glide overlay is the
                            // scrub display), so the engine's current window may still be a prior
                            // clip — point it at this image window before the window-local seek.
                            playerManager.loadClip(playClip);
                        }
                        playerManager.seekTo(relativePlayheadMs);
                        
                        playerManager.play();
                        syncAndPlayAudioPlayer();
                    }
                }
            }
        });

        // Volume tool: three gestures, all handled in one touch listener —
        //  • TAP        → open the volume sheet (mute / ducking / fine control)
        //  • LONG-PRESS → toggle keyframe mode (green stopwatch)
        //  • DRAG ↕     → raise/lower the selected clip's volume live (drag up = louder).
        //                 With keyframes ON, the drag drops/updates a keyframe at the playhead
        //                 and raises/lowers it (the blue envelope tracks the drag).
        toolMute.setOnTouchListener(new View.OnTouchListener() {
            float lastY;
            float curVolume;
            boolean dragging;
            boolean longPressFired;
            // ~1 inch of drag = 1.0 volume → the full 0–200% range is ~2 inches of travel.
            final float unitPx = Math.max(1f, getResources().getDisplayMetrics().densityDpi);
            final int slop = android.view.ViewConfiguration.get(FaditorEditorActivity.this)
                    .getScaledTouchSlop();
            final Runnable longPress = () -> {
                if (dragging) return;
                longPressFired = true;
                toggleAudioVolumeKeyframeMode();
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastY = e.getRawY();
                        curVolume = volumeDragBaseline();
                        dragging = false;
                        longPressFired = false;
                        v.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (longPressFired) return true;
                        float dy = lastY - e.getRawY(); // up = positive = louder
                        if (!dragging && Math.abs(dy) > slop) {
                            dragging = true;
                            v.removeCallbacks(longPress); // it's a drag, not a hold/tap
                            showVolumeDragHud();
                        }
                        if (dragging) {
                            // INCREMENTAL + clamp: accumulate the delta and clamp to [0,2].
                            // Dragging past max/min "re-centres" (the excess is dropped because we
                            // re-anchor lastY each move), so a short physical drag spans full range.
                            curVolume = Math.max(0f, Math.min(2.0f, curVolume + dy / unitPx));
                            lastY = e.getRawY();
                            applyDraggedVolume(curVolume);
                            updateVolumeDragHud(curVolume, e.getRawY());
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        v.removeCallbacks(longPress);
                        if (dragging) {
                            hideVolumeDragHud();
                            scheduleAutoSave(); // persist the drag-adjusted volume/keyframe
                        } else if (!longPressFired) {
                            v.performClick();
                            showVolumeControl(); // tap → open the sheet (top drawer)
                        }
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.removeCallbacks(longPress);
                        hideVolumeDragHud();
                        dragging = false;
                        return true;
                }
                return false;
            }
        });

        // Opacity tool: three gestures (mirrors the volume tool pattern):
        //  • TAP        → open the opacity drawer
        //  • LONG-PRESS → toggle keyframe mode (green stopwatch)
        //  • DRAG ↕     → raise/lower the selected clip's opacity live.
        //                 With keyframes ON, the drag drops/updates a keyframe at the playhead.
        toolOpacity.setOnTouchListener(new View.OnTouchListener() {
            float lastY;
            float curOpacity;
            boolean dragging;
            boolean longPressFired;
            final float unitPx = Math.max(1f, getResources().getDisplayMetrics().densityDpi);
            final int slop = android.view.ViewConfiguration.get(FaditorEditorActivity.this)
                    .getScaledTouchSlop();
            final Runnable longPress = () -> {
                if (dragging) return;
                longPressFired = true;
                toggleClipOpacityKeyframeMode();
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastY = e.getRawY();
                        curOpacity = opacityDragBaseline();
                        dragging = false;
                        longPressFired = false;
                        v.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (longPressFired) return true;
                        float dy = lastY - e.getRawY();
                        if (!dragging && Math.abs(dy) > slop) {
                            dragging = true;
                            v.removeCallbacks(longPress);
                        }
                        if (dragging) {
                            curOpacity = Math.max(0f, Math.min(1f, curOpacity + dy / (unitPx * 2f)));
                            lastY = e.getRawY();
                            applyDraggedOpacity(curOpacity);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        v.removeCallbacks(longPress);
                        if (dragging) {
                            scheduleAutoSave();
                        } else if (!longPressFired) {
                            v.performClick();
                            showOpacityControl();
                        }
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.removeCallbacks(longPress);
                        dragging = false;
                        return true;
                }
                return false;
            }
        });

        // Speed slider
        toolSpeed.setOnClickListener(v -> showSpeedSlider());

        // Rotate (cycles 0 -> 90 -> 180 -> 270 -> 0)
        toolRotate.setOnClickListener(v -> rotateNext());

        // Flip picker
        toolFlip.setOnClickListener(v -> showFlipPicker());

        // Crop picker
        toolCrop.setOnClickListener(v -> enterCropMode());

        // Canvas picker (project-level aspect ratio)
        toolCanvas.setOnClickListener(v -> showCanvasPicker());

        // Audio tool
        toolAudio.setOnClickListener(v -> extractAudioFromCurrentClip());

        // Sync UI to existing clip state (e.g. reopened project)
        Clip clip = getSelectedClip();
        updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
        updateOpacityUI();
        updateSpeedUI(clip.getSpeedMultiplier());
        updateRotateUI(clip.getRotationDegrees());
        updateFlipUI(clip.isFlipHorizontal(), clip.isFlipVertical());
        updateCropUI(clip.getCropPreset());
        updateCanvasUI(project.getCanvasPreset());

        // Apply live preview transforms (delayed to ensure player view is laid out)
        playerView.post(() -> {
            updatePreviewTransforms();
            applyCanvasPreview(project.getCanvasPreset());
        });

        // Crop is stored in the model but NOT shown as overlay on restore.
        // User must tap the Crop tool to enter crop mode.
        // The crop will be applied on export regardless.
    }

    // ── Volume ────────────────────────────────────────────────────────

    /**
     * Toggle volume-keyframe ("stopwatch") mode for the selected audio clip. Only valid
     * when an audio clip is selected. Returns true if the long-press was consumed.
     */
    private boolean toggleAudioVolumeKeyframeMode() {
        int audioIdx = editorTimeline.getSelectedAudioIndex();
        Clip clip = getSelectedClip();
        if (audioIdx >= 0 && project.getTimeline().hasAudioClips()) {
            // Standalone audio clip selected → keyframe on AudioClip
            audioVolumeKeyframeMode = !audioVolumeKeyframeMode;
            updateVolumeKeyframeModeUI();
            Toast.makeText(this,
                    audioVolumeKeyframeMode
                            ? "Volume keyframes ON — adjust volume at each point to set fades"
                            : "Volume keyframes OFF",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        if (clip != null && project.getTimeline().getClipCount() > 0) {
            // Video clip selected → keyframe on Clip.VolumeKeyframe
            audioVolumeKeyframeMode = !audioVolumeKeyframeMode;
            updateVolumeKeyframeModeUI();
            Toast.makeText(this,
                    audioVolumeKeyframeMode
                            ? "Volume keyframes ON — adjust volume at each point to set fades"
                            : "Volume keyframes OFF",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        Toast.makeText(this, "Select a clip to use volume keyframes",
                Toast.LENGTH_SHORT).show();
        return true;
    }

    /** Refresh the Volume tool to reflect keyframe-mode (green stopwatch = armed). */
    private void updateVolumeKeyframeModeUI() {
        Clip clip = getSelectedClip();
        // updateVolumeUI shows the green stopwatch itself when keyframe mode is armed
        // on a selected audio clip, and the normal volume icon otherwise.
        updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
    }

    /** Tap on the Volume tool → open the volume TOP drawer (was a bottom sheet). */
    private void showVolumeControl() {
        if (toolPrefs != null) toolPrefs.recordUse("mute");
        if (volumeDrawerOpen) {
            hideVolumeDrawer();
        } else {
            openVolumeDrawer();
        }
    }

    // ── Clip-opacity keyframe helpers (mirrors volume automation) ────

    private boolean toggleClipOpacityKeyframeMode() {
        Clip clip = getSelectedClip();
        if (clip == null) {
            Toast.makeText(this, "Select a video clip to use opacity keyframes",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        clipOpacityKeyframeMode = !clipOpacityKeyframeMode;
        updateOpacityKeyframeModeUI();
        Toast.makeText(this,
                clipOpacityKeyframeMode
                        ? "Opacity keyframes ON — adjust opacity at each point to set fades"
                        : "Opacity keyframes OFF",
                Toast.LENGTH_SHORT).show();
        return true;
    }

    private void updateOpacityKeyframeModeUI() {
        updateOpacityUI();
    }

    // ── Caption style keyframes ───────────────────────────────────────

    private boolean toggleCaptionStyleKeyframeMode() {
        Clip clip = getSelectedClip();
        if (clip == null) {
            Toast.makeText(this, "Select a video clip to use caption style keyframes",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        captionStyleKeyframeMode = !captionStyleKeyframeMode;
        if (captionStyleKeyframeMode) {
            // First activation: create an initial keyframe at time 0 with current state
            if (!clip.hasCaptionStyleKeyframes()) {
                String initialStyle = clip.isCaptionsEnabled() ? clip.getCaptionStyleId() : "hidden";
                clip.addOrUpdateCaptionStyleKeyframe(0, initialStyle);
                clip.setCaptionsEnabled(true);
            }
            Toast.makeText(this, "Caption style keyframes ON — tap a style pill to drop a keyframe",
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Caption style keyframes OFF", Toast.LENGTH_SHORT).show();
        }
        scheduleAutoSave();
        refreshCaptionKeyframeDrawer();
        return true;
    }

    /** Tap on the Opacity tool → open the opacity top drawer. */
    private void showOpacityControl() {
        if (toolPrefs != null) toolPrefs.recordUse("opacity");
        if (opacityDrawerOpen) {
            hideOpacityDrawer();
        } else {
            openOpacityDrawer();
        }
    }

    private float opacityDragBaseline() {
        Clip clip = getSelectedClip();
        if (clip == null) return 1f;
        if (clipOpacityKeyframeMode && clip.hasOpacityKeyframes()) {
            return clip.opacityAtClipMs(lastPositionInSegmentMs);
        }
        return 1f;
    }

    private void applyDraggedOpacity(float opacity) {
        Clip clip = getSelectedClip();
        if (clip == null) return;
        opacity = Math.max(0f, Math.min(1f, opacity));
        if (clipOpacityKeyframeMode) {
            clip.addOrUpdateOpacityKeyframe(lastPositionInSegmentMs, opacity);
            editorTimeline.invalidate();
        }
        if (playerView != null) {
            playerView.setAlpha(opacity);
        }
        updateOpacityUI();
    }

    /** Legacy bottom-sheet volume control (kept for reference / ducking; not wired to the tap). */
    private void showVolumeControlSheet() {
        // Check if an audio clip is selected — control its volume instead
        int audioIdx = editorTimeline.getSelectedAudioIndex();
        if (audioIdx >= 0 && project.getTimeline().hasAudioClips()) {
            AudioClip ac = project.getTimeline().getAudioClip(audioIdx);
            if (ac != null) {
                float oldVol = ac.getVolumeLevel();
                boolean oldMuted = ac.isMuted();
                VolumeControlBottomSheet sheet = VolumeControlBottomSheet.newInstance(
                        ac.getVolumeLevel(), ac.isMuted(), 0f);
                sheet.setCallback(new VolumeControlBottomSheet.Callback() {
                    @Override
                    public void onVolumeChanged(float volume, boolean muted) {
                        if (audioVolumeKeyframeMode && !muted) {
                            // Write a keyframe at the current playhead (clip-local time).
                            long clipMs = editorTimeline.getPlayheadPositionMs() - ac.getOffsetMs();
                            ac.addOrUpdateVolumeKeyframe(clipMs, volume);
                            // Live: apply this gain immediately so the user hears it.
                            if (audioIdx < audioPlayers.size() && audioIdx < audioPlayersReady.size()
                                    && audioPlayersReady.get(audioIdx)) {
                                audioPlayers.get(audioIdx).setVolume(volume, volume);
                            }
                            editorTimeline.invalidate();
                            scheduleAutoSave();
                            return;
                        }
                        if (oldVol != volume || oldMuted != muted) {
                            undoManager.recordAction(new EditActions.AudioVolumeAction(
                                    ac, oldVol, volume));
                            if (oldMuted != muted) {
                                undoManager.recordAction(new EditActions.AudioMuteAction(
                                        ac, oldMuted, muted));
                            }
                        }
                        ac.setVolumeLevel(volume);
                        ac.setMuted(muted);
                        if (audioIdx < audioPlayers.size() && audioIdx < audioPlayersReady.size()
                                && audioPlayersReady.get(audioIdx)) {
                            float vol = muted ? 0f : volume;
                            audioPlayers.get(audioIdx).setVolume(vol, vol);
                        }
                        updateVolumeUI(volume, muted);
                        scheduleAutoSave();
                    }
                    @Override
                    public void onDuckChanged(float duckAmount) {
                        // Audio clips don't have ducking; ignore
                    }
                });
                sheet.show(getSupportFragmentManager(), "volumeControl");
                return;
            }
        }

        Clip clip = getSelectedClip();
        float oldVol = clip.getVolumeLevel();
        boolean oldMuted = clip.isAudioMuted();

        VolumeControlBottomSheet sheet = VolumeControlBottomSheet.newInstance(
                clip.getVolumeLevel(), clip.isAudioMuted(), clip.getDuckAmount());
        sheet.setCallback(new VolumeControlBottomSheet.Callback() {
            @Override
            public void onVolumeChanged(float volume, boolean muted) {
                if (oldVol != volume) {
                    undoManager.recordAction(new EditActions.VolumeAction(
                            clip, oldVol, volume));
                }
                if (oldMuted != muted) {
                    undoManager.recordAction(new EditActions.MuteAction(
                            clip, oldMuted, oldVol, muted, volume));
                }
                clip.setVolumeLevel(volume);
                clip.setAudioMuted(muted);
                updateVolumeUI(volume, muted);
                playerManager.setVolume(muted ? 0f : volume);
                scheduleAutoSave();
            }
            @Override
            public void onDuckChanged(float duckAmount) {
                clip.setDuckAmount(duckAmount);
                scheduleAutoSave();
            }
        });
        sheet.show(getSupportFragmentManager(), "volumeControl");
    }

    // ── Volume drag-adjust (vertical drag on the Volume tool) ─────────
    // Drag the Volume tool up/down to raise/lower the SELECTED audio clip's volume live.
    // When keyframe mode is armed, the drag drops/updates a keyframe at the playhead and
    // raises/lowers it (the blue envelope tracks the drag). On a video clip it adjusts the
    // clip's own volume. Tap still opens the sheet (mute/ducking/fine), long-press toggles
    // keyframe mode.

    /** Baseline volume to start a drag from (gain-at-playhead when keyframing, else clip volume). */
    private float volumeDragBaseline() {
        int audioIdx = editorTimeline.getSelectedAudioIndex();
        if (audioIdx >= 0 && project.getTimeline().hasAudioClips()) {
            AudioClip ac = project.getTimeline().getAudioClip(audioIdx);
            if (ac != null) {
                if (audioVolumeKeyframeMode) {
                    long clipMs = editorTimeline.getPlayheadPositionMs() - ac.getOffsetMs();
                    return ac.gainAtClipMs(clipMs);
                }
                return ac.getVolumeLevel();
            }
        }
        Clip clip = getSelectedClip();
        if (clip != null) {
            if (audioVolumeKeyframeMode) {
                long clipMs = editorTimeline.getPlayheadPositionMs();
                return clip.gainAtClipMs(clipMs);
            }
            return clip.getVolumeLevel();
        }
        return 1f;
    }

    /**
     * Apply a drag-adjusted volume to the current target. When keyframe mode is armed on the
     * selected audio clip, write/update a keyframe at the playhead (so the envelope tracks the
     * drag); otherwise set the whole-clip volume. Applies the gain live to the player.
     */
    private void applyDraggedVolume(float volume) {
        volume = Math.max(0f, Math.min(volume, 2.0f));
        int audioIdx = editorTimeline.getSelectedAudioIndex();
        if (audioIdx >= 0 && project.getTimeline().hasAudioClips()) {
            AudioClip ac = project.getTimeline().getAudioClip(audioIdx);
            if (ac != null) {
                if (audioVolumeKeyframeMode) {
                    long clipMs = editorTimeline.getPlayheadPositionMs() - ac.getOffsetMs();
                    ac.addOrUpdateVolumeKeyframe(clipMs, volume);
                    editorTimeline.invalidate();
                } else {
                    ac.setVolumeLevel(volume);
                    if (volume > 0f) ac.setMuted(false);
                }
                if (audioIdx < audioPlayers.size() && audioIdx < audioPlayersReady.size()
                        && audioPlayersReady.get(audioIdx)) {
                    audioPlayers.get(audioIdx).setVolume(volume, volume);
                }
                updateVolumeUI(ac.getVolumeLevel(), ac.isMuted());
                return;
            }
        }
        Clip clip = getSelectedClip();
        if (clip != null) {
            if (audioVolumeKeyframeMode) {
                long clipMs = lastPositionInSegmentMs;
                clip.addOrUpdateVolumeKeyframe(clipMs, volume);
                editorTimeline.invalidate();
            } else {
                clip.setVolumeLevel(volume);
                if (volume > 0f) clip.setAudioMuted(false);
            }
            playerManager.setVolume(clip.isAudioMuted() ? 0f : volume);
            updateVolumeUI(volume, clip.isAudioMuted());
        }
    }

    // ── Volume drag HUD (center overlay shown while dragging) ────────

    private void showVolumeDragHud() {
        if (volumeDragHud != null) volumeDragHud.setVisibility(View.VISIBLE);
        performHapticFeedback();
    }

    private void updateVolumeDragHud(float volume, float fingerRawY) {
        if (volumeDragHud == null) return;
        int pct = Math.round(volume * 100f);
        if (volumeDragHudValue != null) volumeDragHudValue.setText(pct + "%");
        if (volumeDragHudIcon != null) {
            String icon = volume < 0.01f ? "volume_off"
                    : volume <= 0.5f ? "volume_down" : "volume_up";
            volumeDragHudIcon.setText(icon);
            // Red tint above 100% (overdrive), green otherwise — matches the toolbar logic.
            int color = volume > 1.01f ? 0xFFF44336 : 0xFF4CAF50;
            volumeDragHudIcon.setTextColor(color);
            if (volumeDragHudValue != null) volumeDragHudValue.setTextColor(color);
            // Subtle "animated" scale with level.
            float scale = 0.85f + 0.35f * (volume / 2.0f);
            volumeDragHudIcon.setScaleX(scale);
            volumeDragHudIcon.setScaleY(scale);
        }
        if (volumeDragFader != null) {
            volumeDragFader.setVolume(volume);
            // The fader follows the finger so it reads as a handle you're holding (clamped on-screen).
            int[] loc = new int[2];
            volumeDragHud.getLocationOnScreen(loc);
            float centerY = loc[1] + volumeDragHud.getHeight() / 2f;
            float ty = fingerRawY - centerY;
            float maxTy = (volumeDragHud.getHeight() - volumeDragFader.getHeight()) / 2f - 20f;
            ty = Math.max(-maxTy, Math.min(maxTy, ty));
            volumeDragFader.setTranslationY(ty);
        }
    }

    private void hideVolumeDragHud() {
        if (volumeDragHud != null) volumeDragHud.setVisibility(View.GONE);
        if (volumeDragFader != null) volumeDragFader.setTranslationY(0f);
    }

    private void performHapticFeedback() {
        if (toolMute != null) {
            toolMute.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    // ── Volume top-drawer (tap the Volume tool) ──────────────────────

    /** One-time wiring of the volume drawer's bar / stopwatch / mute / carets / dismiss. */
    private void wireVolumeDrawer() {
        if (volumeDrawerWired || volumeDrawer == null) return;
        volumeDrawerWired = true;

        if (volumeBar != null) {
            volumeBar.setListener(new com.fadcam.ui.faditor.VolumeBarView.Listener() {
                @Override public void onVolumeSet(float volume) {
                    applyDraggedVolume(volume); // reuse: keyframe-at-playhead when armed, else whole-clip
                    refreshVolumeDrawerChrome();
                }
                @Override public void onVolumeCommitted() { scheduleAutoSave(); }
            });
        }
        // Keyframe arming stopwatch (gray off / green on).
        if (volumeDrawerKeyframe != null) {
            volumeDrawerKeyframe.setOnClickListener(v -> {
                toggleAudioVolumeKeyframeMode();
                refreshVolumeDrawer();
            });
        }
        // Mute toggle.
        if (volumeDrawerMute != null) {
            volumeDrawerMute.setOnClickListener(v -> {
                int audioIdx = editorTimeline.getSelectedAudioIndex();
                if (audioIdx >= 0 && project.getTimeline().hasAudioClips()) {
                    AudioClip ac = project.getTimeline().getAudioClip(audioIdx);
                    if (ac != null) {
                        ac.setMuted(!ac.isMuted());
                        if (audioIdx < audioPlayers.size() && audioIdx < audioPlayersReady.size()
                                && audioPlayersReady.get(audioIdx)) {
                            float g = com.fadcam.ui.faditor.compositor.LayerPreviewController
                                    .effectivePreviewVolume(project.getTimeline(), ac);
                            audioPlayers.get(audioIdx).setVolume(g, g);
                        }
                    }
                } else {
                    Clip clip = getSelectedClip();
                    clip.setAudioMuted(!clip.isAudioMuted());
                    playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
                }
                refreshVolumeDrawer();
                scheduleAutoSave();
            });
        }
        View close = findViewById(R.id.volume_drawer_close);
        if (close != null) close.setOnClickListener(v -> hideVolumeDrawer());
        View prev = findViewById(R.id.volume_kf_prev);
        if (prev != null) prev.setOnClickListener(v -> jumpVolumeKeyframe(-1));
        View next = findViewById(R.id.volume_kf_next);
        if (next != null) next.setOnClickListener(v -> jumpVolumeKeyframe(+1));

        // Swipe up on the grab handle / header dismisses.
        View.OnTouchListener swipeUp = new View.OnTouchListener() {
            float downY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downY = e.getRawY(); return true;
                    case MotionEvent.ACTION_UP:
                        if (downY - e.getRawY() > 40f * getResources().getDisplayMetrics().density) {
                            hideVolumeDrawer();
                        }
                        return true;
                }
                return false;
            }
        };
        View grab = findViewById(R.id.volume_drawer_grab);
        if (grab != null) grab.setOnTouchListener(swipeUp);
        View header = findViewById(R.id.volume_drawer_header);
        if (header != null) header.setOnTouchListener(swipeUp);
    }

    /**
     * Close all open top panels (drawers, transition panel).
     */
    private void closeAllTopPanels() {
        if (volumeDrawerOpen) hideVolumeDrawer();
        if (opacityDrawerOpen) hideOpacityDrawer();
        if (loopDrawerOpen) hideLoopDrawer();
        if (visualizerDrawerOpen) showVisualizerDrawer(false);
        if (captionDrawerOpen) showCaptionDrawer(false);
        if (moveDrawerOpen) hideMoveDrawer();
        if (transitionPanelOpen) showTransitionPanel(false);
        if (wordScrubDrawerOpen) {
            wordScrubDrawerOpen = false;
            View d = findViewById(R.id.word_scrub_drawer);
            if (d != null) d.setVisibility(View.GONE);
        }
    }

    private void openVolumeDrawer() {
        if (volumeDrawer == null) return;
        wireVolumeDrawer();
        closeAllTopPanels();
        volumeDrawerOpen = true;
        refreshVolumeDrawer();
        volumeDrawer.setVisibility(View.VISIBLE);
        volumeDrawer.post(() -> {
            volumeDrawer.setTranslationY(-volumeDrawer.getHeight());
            volumeDrawer.animate().translationY(0f).setDuration(180).start();
        });
    }

    private void hideVolumeDrawer() {
        if (volumeDrawer == null || !volumeDrawerOpen) return;
        volumeDrawerOpen = false;
        volumeDrawer.animate().translationY(-volumeDrawer.getHeight()).setDuration(160)
                .withEndAction(() -> volumeDrawer.setVisibility(View.GONE)).start();
    }

    /** Full refresh of the drawer (bar position + on-keyframe + chrome). */
    private void refreshVolumeDrawer() {
        if (!volumeDrawerOpen || volumeBar == null) return;
        float vol;
        boolean onKf = false;
        int audioIdx = editorTimeline.getSelectedAudioIndex();
        if (audioIdx >= 0 && project.getTimeline().hasAudioClips()) {
            AudioClip ac = project.getTimeline().getAudioClip(audioIdx);
            if (ac != null) {
                long clipMs = editorTimeline.getPlayheadPositionMs() - ac.getOffsetMs();
                vol = ac.hasVolumeKeyframes() ? ac.gainAtClipMs(clipMs) : ac.getVolumeLevel();
                for (AudioClip.VolumeKeyframe kf : ac.getVolumeKeyframes()) {
                    if (Math.abs(kf.timeMs - clipMs) <= 60) { onKf = true; break; }
                }
            } else {
                vol = 1f;
            }
        } else {
            Clip clip = getSelectedClip();
            if (clip != null) {
                long clipMs = lastPositionInSegmentMs;
                vol = clip.hasVolumeKeyframes() ? clip.gainAtClipMs(clipMs) : clip.getVolumeLevel();
                for (Clip.VolumeKeyframe kf : clip.getVolumeKeyframes()) {
                    if (Math.abs(kf.timeMs - clipMs) <= 60) { onKf = true; break; }
                }
            } else {
                vol = 1f;
            }
        }
        volumeBar.setVolume(vol);
        volumeBar.setOnKeyframe(onKf);
        refreshVolumeDrawerChrome();
    }

    /** Update just the icon/value/stopwatch/mute chrome from the bar's current volume. */
    private void refreshVolumeDrawerChrome() {
        if (!volumeDrawerOpen) return;
        float vol = volumeBar != null ? volumeBar.getVolume() : 1f;
        boolean muted = false;
        int audioIdx = editorTimeline.getSelectedAudioIndex();
        if (audioIdx >= 0 && project.getTimeline().hasAudioClips()) {
            AudioClip ac = project.getTimeline().getAudioClip(audioIdx);
            muted = ac != null && ac.isMuted();
        } else {
            muted = getSelectedClip().isAudioMuted();
        }
        int color = (muted || vol > 1.01f) ? 0xFFF44336 : 0xFF4CAF50;
        if (volumeDrawerValue != null) {
            volumeDrawerValue.setText(muted ? "Muted" : Math.round(vol * 100f) + "%");
            volumeDrawerValue.setTextColor(color);
        }
        if (volumeDrawerIcon != null) {
            volumeDrawerIcon.setText(muted || vol < 0.01f ? "volume_off"
                    : vol <= 0.5f ? "volume_down" : "volume_up");
            volumeDrawerIcon.setTextColor(color);
        }
        if (volumeDrawerKeyframe != null) {
            volumeDrawerKeyframe.setTextColor(audioVolumeKeyframeMode ? 0xFF4CAF50 : 0xFF9E9E9E);
        }
        if (volumeDrawerMute != null) {
            volumeDrawerMute.setTextColor(muted ? 0xFFF44336 : 0xFF9E9E9E);
        }
    }

    /** Jump the playhead to the prev/next volume keyframe on the selected audio clip. */
    private void jumpVolumeKeyframe(int dir) {
        int audioIdx = editorTimeline.getSelectedAudioIndex();
        if (audioIdx >= 0 && project.getTimeline().hasAudioClips()) {
            AudioClip ac = project.getTimeline().getAudioClip(audioIdx);
            if (ac == null || !ac.hasVolumeKeyframes()) return;
            long clipMs = editorTimeline.getPlayheadPositionMs() - ac.getOffsetMs();
            long best = -1;
            for (AudioClip.VolumeKeyframe kf : ac.getVolumeKeyframes()) { // sorted ascending
                if (dir > 0) {
                    if (kf.timeMs > clipMs + 30) { best = kf.timeMs; break; }
                } else {
                    if (kf.timeMs < clipMs - 30) best = kf.timeMs; // keep last one before
                }
            }
            if (best >= 0) {
                editorTimeline.seekToTimelineMs(ac.getOffsetMs() + best);
                refreshVolumeDrawer();
            }
        } else {
            Clip clip = getSelectedClip();
            if (clip == null || !clip.hasVolumeKeyframes()) return;
            long clipMs = lastPositionInSegmentMs;
            long best = -1;
            for (Clip.VolumeKeyframe kf : clip.getVolumeKeyframes()) { // sorted ascending
                if (dir > 0) {
                    if (kf.timeMs > clipMs + 30) { best = kf.timeMs; break; }
                } else {
                    if (kf.timeMs < clipMs - 30) best = kf.timeMs;
                }
            }
            if (best >= 0) {
                long segStart = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
                editorTimeline.seekToTimelineMs(segStart + best);
                refreshVolumeDrawer();
            }
        }
    }

    private void updateVolumeUI(float volume, boolean muted) {
        // Keyframe mode armed on an audio clip → keep the green stopwatch indicator
        // (otherwise selecting/scrubbing would reset it to the plain volume icon).
        if (audioVolumeKeyframeMode
                && editorTimeline != null && editorTimeline.getSelectedAudioIndex() >= 0) {
            if (toolMuteIcon != null) {
                toolMuteIcon.setText("timer");
                toolMuteIcon.setTextColor(0xFF4CAF50);
            }
            if (toolMuteLabel != null) {
                toolMuteLabel.setText("Keyframe");
                toolMuteLabel.setTextColor(0xFF4CAF50);
            }
            return;
        }
        if (toolMuteIcon != null) {
            String icon;
            if (muted) {
                icon = "volume_off";
            } else if (volume < 0.01f) {
                icon = "volume_mute";
            } else if (volume <= 0.5f) {
                icon = "volume_down";
            } else {
                icon = "volume_up";
            }
            toolMuteIcon.setText(icon);

            int color;
            if (muted) {
                color = 0xFFF44336; // red
            } else if (volume > 1.01f) {
                color = 0xFFF44336; // red for overdrive
            } else if (Math.abs(volume - 1f) < 0.01f) {
                color = 0xFF888888; // default gray
            } else {
                color = 0xFF4CAF50; // green for modified
            }
            toolMuteIcon.setTextColor(color);

            if (toolMuteLabel != null) {
                if (muted) {
                    toolMuteLabel.setText(R.string.faditor_tool_muted);
                } else {
                    int pct = Math.round(volume * 100f);
                    toolMuteLabel.setText(pct == 100
                            ? getString(R.string.faditor_tool_volume)
                            : pct + "%");
                }
                toolMuteLabel.setTextColor(color);
            }
        }
    }

    // ── Opacity drawer (mirrors the volume drawer pattern) ──────────

    private void wireOpacityDrawer() {
        if (opacityDrawerWired || opacityDrawer == null) return;
        opacityDrawerWired = true;

        if (opacitySlider != null) {
            opacitySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                float prevProgress = -1f;
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser) {
                        float op = progress / 100f;
                        applyDraggedOpacity(op);
                        refreshOpacityDrawerChrome();
                        prevProgress = progress;
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    if (sb.getProgress() != prevProgress) scheduleAutoSave();
                }
            });
        }
        if (opacityDrawerKeyframe != null) {
            opacityDrawerKeyframe.setOnClickListener(v -> {
                toggleClipOpacityKeyframeMode();
                refreshOpacityDrawer();
            });
        }
        View close = findViewById(R.id.opacity_drawer_close);
        if (close != null) close.setOnClickListener(v -> hideOpacityDrawer());
        View prev = findViewById(R.id.opacity_kf_prev);
        if (prev != null) prev.setOnClickListener(v -> jumpOpacityKeyframe(-1));
        View next = findViewById(R.id.opacity_kf_next);
        if (next != null) next.setOnClickListener(v -> jumpOpacityKeyframe(+1));

        View.OnTouchListener swipeUp = new View.OnTouchListener() {
            float downY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downY = e.getRawY(); return true;
                    case MotionEvent.ACTION_UP:
                        if (downY - e.getRawY() > 40f * getResources().getDisplayMetrics().density) {
                            hideOpacityDrawer();
                        }
                        return true;
                }
                return false;
            }
        };
        View grab = findViewById(R.id.opacity_drawer_grab);
        if (grab != null) grab.setOnTouchListener(swipeUp);
        View header = findViewById(R.id.opacity_drawer_header);
        if (header != null) header.setOnTouchListener(swipeUp);
    }

    // ── Caption style keyframe drawer wiring ──────────────────────

    private boolean captionKeyframeDrawerWired = false;

    private void wireCaptionKeyframeDrawer() {
        if (captionKeyframeDrawerWired) return;
        captionKeyframeDrawerWired = true;

        View arm = findViewById(R.id.caption_kf_arm);
        if (arm != null) arm.setOnClickListener(v -> {
            toggleCaptionStyleKeyframeMode();
            if (!captionStyleKeyframeMode) {
                View d = findViewById(R.id.caption_keyframe_drawer);
                if (d != null) d.setVisibility(View.GONE);
            }
        });

        View close = findViewById(R.id.caption_kf_close);
        if (close != null) close.setOnClickListener(v -> {
            View d = findViewById(R.id.caption_keyframe_drawer);
            if (d != null) d.setVisibility(View.GONE);
        });

        View prev = findViewById(R.id.caption_kf_prev);
        if (prev != null) prev.setOnClickListener(v -> jumpCaptionStyleKeyframe(-1));

        View next = findViewById(R.id.caption_kf_next);
        if (next != null) next.setOnClickListener(v -> jumpCaptionStyleKeyframe(+1));

        View del = findViewById(R.id.caption_kf_delete);
        if (del != null) del.setOnClickListener(v -> deleteCurrentCaptionStyleKeyframe());
    }

    private void openCaptionKeyframeDrawer() {
        wireCaptionKeyframeDrawer();
        View d = findViewById(R.id.caption_keyframe_drawer);
        if (d != null) {
            refreshCaptionKeyframeDrawer();
            d.setVisibility(View.VISIBLE);
        }
    }

    // ── Stopwatch shortcut (caption drawer's "advanced" area) ─────────
    // Always-reachable entry point into the SAME arm state + drawer that
    // wireCaptionKeyframeDrawer()/openCaptionKeyframeDrawer() own — this does not
    // duplicate the arming logic, it just gives the bottom caption-style bar a way to
    // reach it without requiring a long-press on the CC timeline lane.

    private boolean captionKfArmShortcutWired = false;

    private void wireCaptionKfArmShortcut() {
        if (captionKfArmShortcutWired) return;
        captionKfArmShortcutWired = true;
        View shortcut = findViewById(R.id.caption_kf_arm_shortcut);
        if (shortcut == null) return;
        shortcut.setOnClickListener(v -> {
            toggleCaptionStyleKeyframeMode();
            if (captionStyleKeyframeMode) {
                openCaptionKeyframeDrawer();
            } else {
                View d = findViewById(R.id.caption_keyframe_drawer);
                if (d != null) d.setVisibility(View.GONE);
            }
        });
    }

    /** Keep the bottom-bar stopwatch shortcut's tint in sync with the arm state. */
    private void updateCaptionKfArmShortcutUI() {
        View shortcut = findViewById(R.id.caption_kf_arm_shortcut);
        if (shortcut instanceof TextView) {
            ((TextView) shortcut).setTextColor(
                    captionStyleKeyframeMode ? 0xFF4CAF50 : 0xFF9E9E9E);
        }
    }

    // Snapshot of the selected clip + its opacity keyframes when the opacity drawer
    // opens, so the whole editing session collapses into ONE undo entry on close.
    @Nullable private Clip opacityUndoClip;
    @Nullable private java.util.List<Clip.OpacityKeyframe> opacityUndoBefore;

    private void openOpacityDrawer() {
        if (opacityDrawer == null) return;
        wireOpacityDrawer();
        closeAllTopPanels();
        // Capture pre-edit opacity keyframes for undo.
        opacityUndoClip = getSelectedClip();
        opacityUndoBefore = opacityUndoClip != null
                ? copyOpacityKfs(opacityUndoClip.getOpacityKeyframes()) : null;
        opacityDrawerOpen = true;
        refreshOpacityDrawer();
        opacityDrawer.setVisibility(View.VISIBLE);
        opacityDrawer.post(() -> {
            opacityDrawer.setTranslationY(-opacityDrawer.getHeight());
            opacityDrawer.animate().translationY(0f).setDuration(180).start();
        });
    }

    private void hideOpacityDrawer() {
        if (opacityDrawer == null || !opacityDrawerOpen) return;
        opacityDrawerOpen = false;
        opacityDrawer.animate().translationY(-opacityDrawer.getHeight()).setDuration(160)
                .withEndAction(() -> opacityDrawer.setVisibility(View.GONE)).start();
        // Commit one undo entry for the opacity-editing session if it changed.
        if (opacityUndoClip != null && opacityUndoBefore != null) {
            java.util.List<Clip.OpacityKeyframe> after = opacityUndoClip.getOpacityKeyframes();
            if (!opacityKfsEqual(opacityUndoBefore, after)) {
                undoManager.recordAction(new EditActions.OpacityKeyframesAction(
                        opacityUndoClip, opacityUndoBefore, after));
                saveProjectNow();
            }
        }
        opacityUndoClip = null;
        opacityUndoBefore = null;
    }

    private static java.util.List<Clip.OpacityKeyframe> copyOpacityKfs(
            @NonNull java.util.List<Clip.OpacityKeyframe> src) {
        java.util.List<Clip.OpacityKeyframe> out = new java.util.ArrayList<>();
        for (Clip.OpacityKeyframe kf : src) {
            out.add(new Clip.OpacityKeyframe(kf.timeMs, kf.opacity));
        }
        return out;
    }

    private static boolean opacityKfsEqual(@NonNull java.util.List<Clip.OpacityKeyframe> a,
                                           @NonNull java.util.List<Clip.OpacityKeyframe> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).timeMs != b.get(i).timeMs
                    || a.get(i).opacity != b.get(i).opacity) return false;
        }
        return true;
    }

    // ── Loop drawer ─────────────────────────────────────────────────

    private void wireLoopDrawer() {
        if (loopDrawerWired || loopDrawer == null) return;
        loopDrawerWired = true;

        // Mode selector
        loopModeOff.setOnClickListener(v -> applyLoopMode(Clip.LOOP_MODE_OFF));
        loopModeNormal.setOnClickListener(v -> applyLoopMode(Clip.LOOP_MODE_NORMAL));
        loopModeStill.setOnClickListener(v -> applyLoopMode(Clip.LOOP_MODE_STILL));
        if (Clip.PING_PONG_PARKED) {
            // PARKED: the ping-pong chip is a disabled "coming soon" affordance — tapping it shows a
            // toast and does NOT switch the clip into PING_PONG (so no new ping-pong project state,
            // no bake, no gapless-eligibility flip). The dim styling is applied in refreshLoopDrawer.
            // (loopModePingpong is a View field but the drawer chip is a <TextView> — safe cast.)
            if (loopModePingpong instanceof TextView) {
                ((TextView) loopModePingpong).setText(R.string.faditor_loop_pingpong_parked);
            }
            loopModePingpong.setOnClickListener(v ->
                    Toast.makeText(this, R.string.faditor_loop_pingpong_parked_toast,
                            Toast.LENGTH_LONG).show());
        } else {
            loopModePingpong.setOnClickListener(v -> applyLoopMode(Clip.LOOP_MODE_PING_PONG));
        }

        // Extend buttons: add 1s of loop time
        loopExtendStart.setOnClickListener(v -> extendLoop(-1000));
        loopExtendEnd.setOnClickListener(v -> extendLoop(1000));
        loopExtendPrev.setOnClickListener(v -> extendLoop(-5000));
        loopExtendNext.setOnClickListener(v -> extendLoop(5000));

        // Close button
        View close = findViewById(R.id.loop_drawer_close);
        if (close != null) close.setOnClickListener(v -> hideLoopDrawer());

        // Swipe-up gesture to dismiss
        View.OnTouchListener swipeUp = new View.OnTouchListener() {
            float downY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downY = e.getRawY(); return true;
                    case MotionEvent.ACTION_UP:
                        if (downY - e.getRawY() > 40f * getResources().getDisplayMetrics().density) {
                            hideLoopDrawer();
                        }
                        return true;
                }
                return false;
            }
        };
        View grab = findViewById(R.id.loop_drawer_header);
        if (grab != null) grab.setOnTouchListener(swipeUp);
    }

    private void showLoopDrawer() {
        if (loopDrawer == null) return;
        wireLoopDrawer();
        closeAllTopPanels();
        refreshLoopDrawer();
        loopDrawerOpen = true;
        loopDrawer.setVisibility(View.VISIBLE);
        // L3: cap the scrollable content (mode chips + extend rows) BEFORE the slide-in
        // below reads loopDrawer.getHeight() for its translation distance, so a screen too
        // short for the full drawer clamps first and the slide-in uses the corrected
        // (already-scrollable) height — same measure-then-clamp shape as the layer-history
        // popup's scroll cap, just applied to a fixed top drawer instead of a popup card.
        clampLoopDrawerScrollHeight();
        loopDrawer.post(() -> {
            loopDrawer.setTranslationY(-loopDrawer.getHeight());
            loopDrawer.animate().translationY(0f).setDuration(180).start();
        });
    }

    /**
     * Menu philosophy (DESIGN_JOY_CREATOR.md §5): "vertically shrink upper drawers where
     * possible" — a large drawer covering the timeline is as bad as covering the preview.
     * loop_drawer is a direct FrameLayout child anchored to the top, so its wrap_content
     * height is only bounded by the screen itself; on a short/dense screen (or with the
     * status bar + top app bar already eating space above it) its content could in
     * principle run past the bottom. Rather than restructuring the drawer, only
     * loop_drawer_scroll (everything below the pinned grab-handle/header) gets capped —
     * inner scroll only, per the plan.
     */
    private void clampLoopDrawerScrollHeight() {
        if (loopDrawerScroll == null) return;
        loopDrawerScroll.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        loopDrawerScroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        int screenH = getResources().getDisplayMetrics().heightPixels;
                        // Leave ~45% of the screen free below the drawer so the timeline
                        // stays reachable while it's open (same balance the transcript/
                        // caption-keyframe drawers strike with their own weighted scroll areas).
                        int maxHeightPx = (int) (screenH * 0.55f) - loopDrawerScroll.getTop();
                        if (maxHeightPx > 0 && loopDrawerScroll.getHeight() > maxHeightPx) {
                            android.view.ViewGroup.LayoutParams lp = loopDrawerScroll.getLayoutParams();
                            lp.height = maxHeightPx;
                            loopDrawerScroll.setLayoutParams(lp);
                        }
                    }
                });
    }

    private void hideLoopDrawer() {
        if (loopDrawer == null || !loopDrawerOpen) return;
        loopDrawerOpen = false;
        loopDrawer.animate().translationY(-loopDrawer.getHeight()).setDuration(160)
                .withEndAction(() -> loopDrawer.setVisibility(View.GONE)).start();
    }

    private void refreshLoopDrawer() {
        Clip clip = getSelectedClip();
        if (clip == null) return;
        int mode = clip.getLoopMode();
        // Update mode chip highlights
        int normalBg = 0xFF333333;
        int activeBg = 0xFF4CAF50;
        loopModeOff.setBackgroundColor(mode == Clip.LOOP_MODE_OFF ? activeBg : normalBg);
        loopModeNormal.setBackgroundColor(mode == Clip.LOOP_MODE_NORMAL ? activeBg : normalBg);
        loopModeStill.setBackgroundColor(mode == Clip.LOOP_MODE_STILL ? activeBg : normalBg);
        if (Clip.PING_PONG_PARKED) {
            // PARKED: never highlight the ping-pong chip green — even a legacy PING_PONG clip is now
            // playing as a plain forward loop, so a green "active" chip would misrepresent state.
            // Keep it dim/disabled-looking regardless of the clip's stored mode.
            loopModePingpong.setBackgroundColor(normalBg);
            loopModePingpong.setAlpha(0.4f);
        } else {
            loopModePingpong.setBackgroundColor(mode == Clip.LOOP_MODE_PING_PONG ? activeBg : normalBg);
        }
        // Update header label
        if (loopDrawerModeLabel != null) {
            int label;
            switch (mode) {
                case Clip.LOOP_MODE_NORMAL: label = R.string.faditor_loop_normal; break;
                case Clip.LOOP_MODE_STILL: label = R.string.faditor_loop_still; break;
                // PARKED: a stored PING_PONG clip degrades to a forward loop — reflect that in the
                // header label instead of advertising ping-pong the app no longer performs.
                case Clip.LOOP_MODE_PING_PONG:
                    label = Clip.PING_PONG_PARKED
                            ? R.string.faditor_loop_normal : R.string.faditor_loop_pingpong;
                    break;
                default: label = R.string.faditor_loop_off;
            }
            loopDrawerModeLabel.setText(label);
        }
    }

    private void applyLoopMode(int mode) {
        Clip clip = getSelectedClip();
        if (clip == null) return;
        int prevMode = clip.getLoopMode();
        long prevBefore = clip.getLoopBeforeMs();
        long prevAfter = clip.getLoopAfterMs();
        clip.setLoopMode(mode);
        if (mode == Clip.LOOP_MODE_OFF) {
            clip.setLoopBeforeMs(0);
            clip.setLoopAfterMs(0);
        } else if (clip.getLoopBeforeMs() == 0 && clip.getLoopAfterMs() == 0) {
            // Seed a small default extension so hasLoopExtension() returns true
            // immediately and the user can start dragging past source bounds.
            clip.setLoopAfterMs(1000);
        }
        // Reset visual offset when mode changes
        loopVisualOffsetMs = 0;
        loopStillExtensionStartMs = -1;
        // Record undo
        undoManager.recordAction(new EditActions.LoopAction(clip, prevMode, prevBefore, prevAfter,
                mode, clip.getLoopBeforeMs(), clip.getLoopAfterMs()));
        refreshLoopDrawer();
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);
        // L1: mode/extension changed the clip's loop-rep windows (or its gapless eligibility
        // entirely, e.g. switching to/from OFF/PING_PONG/STILL) — rebuild the gapless playlist
        // the same way the drag path (onLoopTrimFinished) and undo/redo
        // (refreshEditorAfterUndoRedo) already do, or the engine keeps playing the OLD extension
        // until some unrelated action happens to trigger a rebuild.
        if (!clip.isImageClip()) {
            // RANK-1c: a user loop edit rebuilds the playlist — advance the generation so any
            // in-flight bake kicked before this edit discards its stale auto-promote.
            rebuildGeneration++;
            playerManager.updateTrimBounds(clip);
        }
        // L2: if this made the clip PING_PONG, kick the off-main reverse bake now; when it lands it
        // rebuilds the gapless playlist for a TRUE reverse leg (until then, forward-tail fallback).
        kickReverseBakeIfNeeded(clip);
        saveProjectNow();
    }

    private void extendLoop(long deltaMs) {
        Clip clip = getSelectedClip();
        if (clip == null) return;
        int mode = clip.getLoopMode();
        if (mode == Clip.LOOP_MODE_OFF) {
            // Auto-enable normal loop when extending
            clip.setLoopMode(Clip.LOOP_MODE_NORMAL);
            mode = Clip.LOOP_MODE_NORMAL;
        }
        long prevBefore = clip.getLoopBeforeMs();
        long prevAfter = clip.getLoopAfterMs();
        if (deltaMs < 0) {
            clip.setLoopBeforeMs(Math.max(0, clip.getLoopBeforeMs() + (-deltaMs)));
        } else {
            clip.setLoopAfterMs(Math.max(0, clip.getLoopAfterMs() + deltaMs));
        }
        undoManager.recordAction(new EditActions.LoopAction(clip, mode, prevBefore, prevAfter,
                mode, clip.getLoopBeforeMs(), clip.getLoopAfterMs()));
        refreshLoopDrawer();
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);
        // L1: same reasoning as applyLoopMode above — the extension length changed, so the
        // gapless engine's playlist (rep count/boundaries) is stale until rebuilt.
        if (!clip.isImageClip()) {
            // RANK-1c: user loop edit → advance the rebuild generation (see applyLoopMode).
            rebuildGeneration++;
            playerManager.updateTrimBounds(clip);
        }
        // L2: extendLoop doesn't change the trim range (only before/after ms), so the reverse-bake
        // key is unchanged — but if this clip is PING_PONG and not yet baked, ensure a bake is in
        // flight (cheap no-op when already cached).
        kickReverseBakeIfNeeded(clip);
        saveProjectNow();
    }

    // ── Move drawer (position/layer) ────────────────────────────────

    private void initMoveDrawer() {
        if (moveDrawer != null) return;
        moveDrawer = findViewById(R.id.move_drawer);
        movePositionTimestamp = findViewById(R.id.move_position_timestamp);
        movePositionSeconds = findViewById(R.id.move_position_seconds);
        movePositionFrames = findViewById(R.id.move_position_frames);
        movePositionLayer = findViewById(R.id.move_position_layer);
        moveTargetInput = findViewById(R.id.move_target_input);
        moveGo = findViewById(R.id.move_go);
        moveLayerUp = findViewById(R.id.move_layer_up);
        moveLayerDown = findViewById(R.id.move_layer_down);
        moveClipStart = findViewById(R.id.move_clip_start);
        moveClipLeft = findViewById(R.id.move_clip_left);
        moveClipRight = findViewById(R.id.move_clip_right);
        moveClipEnd = findViewById(R.id.move_clip_end);

        moveClipStart.setOnClickListener(v -> moveSelectedClipTo(0));
        moveClipLeft.setOnClickListener(v -> moveSelectedClipBy(-1));
        moveClipRight.setOnClickListener(v -> moveSelectedClipBy(1));
        moveClipEnd.setOnClickListener(v -> {
            if (project != null) moveSelectedClipTo(project.getTimeline().getClipCount() - 1);
        });

        findViewById(R.id.move_drawer_close).setOnClickListener(v -> hideMoveDrawer());

        // Swipe-up gesture to dismiss
        View.OnTouchListener swipeUp = new View.OnTouchListener() {
            float downY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downY = e.getRawY(); return true;
                    case MotionEvent.ACTION_UP:
                        if (downY - e.getRawY() > 40f * getResources().getDisplayMetrics().density) {
                            hideMoveDrawer();
                        }
                        return true;
                }
                return false;
            }
        };
        View grab = findViewById(R.id.move_drawer_grab);
        if (grab != null) grab.setOnTouchListener(swipeUp);
        View header = findViewById(R.id.move_drawer_header);
        if (header != null) header.setOnTouchListener(swipeUp);

        // Layer up/down (future layer implementation)
        moveLayerUp.setOnClickListener(v -> {
            Toast.makeText(this, "Layer up (coming soon)", Toast.LENGTH_SHORT).show();
        });
        moveLayerDown.setOnClickListener(v -> {
            Toast.makeText(this, "Layer down (coming soon)", Toast.LENGTH_SHORT).show();
        });

        // Go button: parse input and seek to position
        moveGo.setOnClickListener(v -> performMoveToInput());
        moveTargetInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                performMoveToInput();
                return true;
            }
            return false;
        });
    }

    private void toggleMoveDrawer() {
        if (moveDrawerOpen) {
            hideMoveDrawer();
        } else {
            openMoveDrawer();
        }
    }

    private void openMoveDrawer() {
        initMoveDrawer();
        if (moveDrawer == null) return;
        closeAllTopPanels();
        moveDrawerOpen = true;
        refreshMoveDrawer();
        moveDrawer.setVisibility(View.VISIBLE);
        moveDrawer.post(() -> {
            moveDrawer.setTranslationY(-moveDrawer.getHeight());
            moveDrawer.animate().translationY(0f).setDuration(180).start();
        });
    }

    private void hideMoveDrawer() {
        if (moveDrawer == null || !moveDrawerOpen) return;
        moveDrawerOpen = false;
        moveDrawer.animate().translationY(-moveDrawer.getHeight()).setDuration(160)
                .withEndAction(() -> moveDrawer.setVisibility(View.GONE)).start();
    }

    private void refreshMoveDrawer() {
        if (!moveDrawerOpen) return;
        long playheadMs = editorTimeline != null ? editorTimeline.getPlayheadPositionMs() : 0;
        long totalMs = totalEffectiveMs();
        long seconds = playheadMs / 1000;
        long millis = playheadMs % 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds = seconds % 60;
        minutes = minutes % 60;

        if (movePositionTimestamp != null) {
            movePositionTimestamp.setText(String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis));
        }
        if (movePositionSeconds != null) {
            movePositionSeconds.setText(String.format("%.1fs", playheadMs / 1000.0));
        }
        if (movePositionFrames != null) {
            int frames = (int) ((playheadMs % 1000) * 30 / 1000);
            movePositionFrames.setText(frames + "f");
        }
        if (movePositionLayer != null) {
            movePositionLayer.setText("Layer 1");
        }
        refreshMoveClipButtons();
    }

    /** Dim/disable the clip-reorder buttons that can't apply to the current selection. */
    private void refreshMoveClipButtons() {
        if (moveClipStart == null) return;
        int count = project != null ? project.getTimeline().getClipCount() : 0;
        boolean hasSel = selectedClipIndex >= 0 && selectedClipIndex < count;
        boolean canLeft = hasSel && selectedClipIndex > 0;
        boolean canRight = hasSel && selectedClipIndex < count - 1;
        setMoveButtonEnabled(moveClipStart, canLeft);
        setMoveButtonEnabled(moveClipLeft, canLeft);
        setMoveButtonEnabled(moveClipRight, canRight);
        setMoveButtonEnabled(moveClipEnd, canRight);
    }

    private void setMoveButtonEnabled(@Nullable TextView btn, boolean enabled) {
        if (btn == null) return;
        btn.setEnabled(enabled);
        btn.setAlpha(enabled ? 1f : 0.3f);
    }

    private void performMoveToInput() {
        if (moveTargetInput == null) return;
        String input = moveTargetInput.getText().toString().trim();
        if (input.isEmpty()) return;

        long targetMs = -1;
        try {
            // Try parsing as HH:MM:SS.mmm
            String[] parts = input.split(":");
            if (parts.length == 3) {
                String[] secParts = parts[2].split("\\.");
                long h = Long.parseLong(parts[0]);
                long m = Long.parseLong(parts[1]);
                long s = Long.parseLong(secParts[0]);
                long ms = secParts.length > 1 ? Long.parseLong(secParts[1]) : 0;
                targetMs = h * 3600000 + m * 60000 + s * 1000 + ms;
            } else if (parts.length == 2) {
                long m = Long.parseLong(parts[0]);
                String[] secParts = parts[1].split("\\.");
                long s = Long.parseLong(secParts[0]);
                long ms = secParts.length > 1 ? Long.parseLong(secParts[1]) : 0;
                targetMs = m * 60000 + s * 1000 + ms;
            } else {
                // Try as seconds or frames
                if (input.endsWith("f") || input.endsWith("F")) {
                    int frames = Integer.parseInt(input.substring(0, input.length() - 1));
                    targetMs = (long) (frames * 1000.0 / 30.0);
                } else if (input.endsWith("s") || input.endsWith("S")) {
                    targetMs = (long) (Double.parseDouble(input.substring(0, input.length() - 1)) * 1000);
                } else if (input.contains(".")) {
                    targetMs = (long) (Double.parseDouble(input) * 1000);
                } else {
                    targetMs = Long.parseLong(input);
                }
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid format. Use HH:MM:SS.mmm or seconds", Toast.LENGTH_SHORT).show();
            return;
        }

        if (targetMs < 0) targetMs = 0;
        long total = totalEffectiveMs();
        if (targetMs > total) targetMs = total;

        if (editorTimeline != null) {
            editorTimeline.seekToTimelineMs(targetMs);
            refreshMoveDrawer();
        }
    }

    /** Move the selected clip by a relative number of positions (−1 = left, +1 = right). */
    private void moveSelectedClipBy(int delta) {
        if (project == null) return;
        if (selectedClipIndex < 0) {
            Toast.makeText(this, R.string.faditor_move_clip_none, Toast.LENGTH_SHORT).show();
            return;
        }
        moveSelectedClipTo(selectedClipIndex + delta);
    }

    /**
     * Reorder the selected clip to {@code toIndex} on the video track. Reuses the
     * same undo-safe path as drag-reorder ({@link EditActions.ReorderClipAction}),
     * keeps the moved clip selected, and refreshes the timeline + drawer state.
     */
    private void moveSelectedClipTo(int toIndex) {
        if (project == null) return;
        Timeline tl = project.getTimeline();
        int from = selectedClipIndex;
        int count = tl.getClipCount();
        if (from < 0 || from >= count) {
            Toast.makeText(this, R.string.faditor_move_clip_none, Toast.LENGTH_SHORT).show();
            return;
        }
        int to = Math.max(0, Math.min(count - 1, toIndex));
        if (to == from) {
            Toast.makeText(this,
                    from == 0 ? R.string.faditor_move_clip_at_start
                              : R.string.faditor_move_clip_at_end,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        undoManager.recordAction(new EditActions.ReorderClipAction(tl, from, to));
        tl.moveClip(from, to);
        selectSegment(to);
        editorTimeline.setTimeline(tl, to);
        editorTimeline.scrollToSegment(to);
        syncTimelineOverlays();
        editorTimeline.invalidate();
        refreshMoveDrawer();
        saveProjectNow();
    }

    private void refreshOpacityDrawer() {
        if (!opacityDrawerOpen || opacitySlider == null) return;
        Clip clip = getSelectedClip();
        if (clip == null) return;
        float op = clip.hasOpacityKeyframes()
                ? clip.opacityAtClipMs(lastPositionInSegmentMs) : 1f;
        int pct = Math.max(0, Math.min(100, Math.round(op * 100f)));
        opacitySlider.setProgress(pct);
        refreshOpacityDrawerChrome();
    }

    private void refreshOpacityDrawerChrome() {
        if (!opacityDrawerOpen) return;
        float op = opacitySlider != null ? opacitySlider.getProgress() / 100f : 1f;
        int pct = Math.round(op * 100f);
        if (opacityDrawerValue != null) {
            opacityDrawerValue.setText(pct + "%");
            opacityDrawerValue.setTextColor(pct < 100 ? 0xFF4CAF50 : 0xFF888888);
        }
        if (opacityDrawerKeyframe != null) {
            opacityDrawerKeyframe.setTextColor(clipOpacityKeyframeMode ? 0xFF4CAF50 : 0xFF9E9E9E);
        }
    }

    private void jumpOpacityKeyframe(int dir) {
        Clip clip = getSelectedClip();
        if (clip == null || !clip.hasOpacityKeyframes()) return;
        java.util.List<Clip.OpacityKeyframe> kfs = clip.getOpacityKeyframes();
        long best = -1;
        for (Clip.OpacityKeyframe kf : kfs) {
            if (dir > 0) {
                if (kf.timeMs > lastPositionInSegmentMs + 30) { best = kf.timeMs; break; }
            } else {
                if (kf.timeMs < lastPositionInSegmentMs - 30) best = kf.timeMs;
            }
        }
        if (best >= 0) {
            long segStart = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
            editorTimeline.seekToTimelineMs(segStart + best);
            refreshOpacityDrawer();
        }
    }

    // ── Caption style keyframe drawer ────────────────────────────────

    private void refreshCaptionKeyframeDrawer() {
        Clip cc = getSelectedClip();

        // Arm icon (accent-tinted green when armed, dim grey otherwise)
        View arm = findViewById(R.id.caption_kf_arm);
        if (arm != null) {
            arm.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    captionStyleKeyframeMode ? 0xFF4CAF50 : 0xFF666666));
        }
        // Mirror the same armed state onto the bottom-bar stopwatch shortcut (if built).
        updateCaptionKfArmShortcutUI();

        // Mirror the armed state onto the Captions tool-row cell itself, matching the
        // existing toolMuteIcon/toolOpacityIcon convention (green when armed, grey otherwise).
        if (toolCaptionsIcon != null) {
            toolCaptionsIcon.setTextColor(captionStyleKeyframeMode ? 0xFF4CAF50 : 0xFF888888);
        }
        if (toolCaptionsLabel != null) {
            toolCaptionsLabel.setTextColor(captionStyleKeyframeMode ? 0xFF4CAF50 : 0xFF888888);
        }

        if (cc == null) return;

        // Nav controls only show when armed + keyframes exist; each direction independently
        // dims/disables once there's nothing further to jump to (spec: dim/disable at ends).
        boolean showNav = captionStyleKeyframeMode && cc.hasCaptionStyleKeyframes();
        com.fadcam.ui.faditor.captions.CaptionStyleKeyframeController.NavState nav =
                com.fadcam.ui.faditor.captions.CaptionStyleKeyframeController.computeNavState(
                        cc, lastSourcePositionInSegmentMs);
        View prev = findViewById(R.id.caption_kf_prev);
        if (prev != null) {
            prev.setVisibility(showNav ? View.VISIBLE : View.GONE);
            prev.setEnabled(nav.hasPrev);
            prev.setAlpha(nav.hasPrev ? 1f : 0.35f);
        }
        View next = findViewById(R.id.caption_kf_next);
        if (next != null) {
            next.setVisibility(showNav ? View.VISIBLE : View.GONE);
            next.setEnabled(nav.hasNext);
            next.setAlpha(nav.hasNext ? 1f : 0.35f);
        }

        // On-keyframe indicator + delete ("−" affordance)
        boolean onKf = showNav && com.fadcam.ui.faditor.captions.CaptionStyleKeyframeController
                .isOnKeyframe(cc, lastSourcePositionInSegmentMs);
        View onDot = findViewById(R.id.caption_kf_onkeyframe);
        if (onDot != null) onDot.setVisibility(onKf ? View.VISIBLE : View.INVISIBLE);
        View del = findViewById(R.id.caption_kf_delete);
        if (del != null) del.setVisibility(onKf ? View.VISIBLE : View.GONE);
    }

    private void jumpCaptionStyleKeyframe(int dir) {
        Clip cc = getSelectedClip();
        if (cc == null || !cc.hasCaptionStyleKeyframes()) return;
        com.fadcam.ui.faditor.captions.CaptionStyleKeyframeController.NavState nav =
                com.fadcam.ui.faditor.captions.CaptionStyleKeyframeController.computeNavState(
                        cc, lastSourcePositionInSegmentMs);
        long best = (dir > 0) ? (nav.hasNext ? nav.nextTimeMs : -1)
                               : (nav.hasPrev ? nav.prevTimeMs : -1);
        if (best >= 0) {
            long segStart = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
            float speed = cc.getSpeedMultiplier();
            long bestTimelineMs = (speed > 0) ? (long) (best / speed) : best;
            editorTimeline.seekToTimelineMs(segStart + bestTimelineMs);
            refreshCaptionKeyframeDrawer();
        }
    }

    private void deleteCurrentCaptionStyleKeyframe() {
        Clip cc = getSelectedClip();
        if (cc == null) return;
        java.util.List<Clip.CaptionStyleKeyframe> beforeKfs = snapshotCaptionStyleKeyframes(cc);
        cc.removeCaptionStyleKeyframe(lastSourcePositionInSegmentMs);
        recordCaptionStyleKeyframeEdit(cc, beforeKfs);
        if (!cc.hasCaptionStyleKeyframes()) captionStyleKeyframeMode = false;
        scheduleAutoSave();
        if (captionsActive) bindCaptionData(cc);
        if (editorTimeline != null) editorTimeline.invalidate();
        refreshCaptionKeyframeDrawer();
    }

    private void updateOpacityUI() {
        if (clipOpacityKeyframeMode) {
            if (toolOpacityIcon != null) {
                toolOpacityIcon.setText("timer");
                toolOpacityIcon.setTextColor(0xFF4CAF50);
            }
            if (toolOpacityLabel != null) {
                toolOpacityLabel.setText("Keyframe");
                toolOpacityLabel.setTextColor(0xFF4CAF50);
            }
            return;
        }
        Clip clip = getSelectedClip();
        float opacity = clip != null && clip.hasOpacityKeyframes()
                ? clip.opacityAtClipMs(lastPositionInSegmentMs) : 1f;
        int pct = Math.round(opacity * 100f);
        if (toolOpacityIcon != null) {
            toolOpacityIcon.setText("opacity");
            toolOpacityIcon.setTextColor(pct < 100 ? 0xFF4CAF50 : 0xFF888888);
        }
        if (toolOpacityLabel != null) {
            toolOpacityLabel.setText(pct < 100 ? pct + "%" : getString(R.string.faditor_tool_opacity));
            toolOpacityLabel.setTextColor(pct < 100 ? 0xFF4CAF50 : 0xFF888888);
        }
    }

    // ── Speed ────────────────────────────────────────────────────────

    private void showSpeedSlider() {
        Clip clip = getSelectedClip();
        float oldSpeed = clip.getSpeedMultiplier();
        boolean oldPitch = clip.isPitchCompensationEnabled();
        SpeedSliderBottomSheet sheet = SpeedSliderBottomSheet.newInstance(
                clip.getSpeedMultiplier(), clip.isPitchCompensationEnabled());
        sheet.setCallback(new SpeedSliderBottomSheet.Callback() {
            @Override
            public void onSpeedChanged(float speed) {
                if (oldSpeed != speed) {
                    undoManager.recordAction(new EditActions.SpeedAction(
                            clip, oldSpeed, speed));
                }
                clip.setSpeedMultiplier(speed);
                playerManager.setPlaybackSpeed(speed, clip.isPitchCompensationEnabled());
                updateSpeedUI(speed);
                scheduleAutoSave();
            }

            @Override
            public void onPitchCompensationChanged(boolean enabled) {
                clip.setPitchCompensationEnabled(enabled);
                playerManager.setPlaybackSpeed(clip.getSpeedMultiplier(), enabled);
                scheduleAutoSave();
            }
        });
        sheet.show(getSupportFragmentManager(), "speed_slider");
    }

    /**
     * Real-time color grade for the LIVE preview, applied as a {@link android.graphics.RenderEffect}
     * on the TextureView-backed player (the same reason rotate/crop use View transforms — Media3
     * {@code setVideoEffects} does not render in this preview path). Covers the matrix-expressible
     * params (exposure/contrast/saturation/temperature/tint) via ColorMatrix on API 31+; the
     * shader-only params (highlights/shadows/fade/vignette/grain) are additionally previewed via
     * AGSL RuntimeShader on API 33+. On older devices the shader-only params are not shown in
     * preview (export still unaffected).
     */
    private void applyPreviewColorGrade(@Nullable Clip clip) {
        if (playerView == null
                || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return;
        }
        if (clip == null || clip.isImageClip() || !clip.getEffectStack().isActive()) {
            playerView.setRenderEffect(null);
            return;
        }
        com.fadcam.ui.faditor.effects.EffectStack fx = clip.getEffectStack();
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(fx.getSaturation());
        float c = 1f + fx.getContrast();
        float ct = (1f - c) * 0.5f * 255f;
        cm.postConcat(new android.graphics.ColorMatrix(new float[]{
                c, 0, 0, 0, ct,
                0, c, 0, 0, ct,
                0, 0, c, 0, ct,
                0, 0, 0, 1, 0}));
        float e = 1f + fx.getExposure();
        android.graphics.ColorMatrix scale = new android.graphics.ColorMatrix();
        scale.setScale(e * (1f + fx.getTemperature() * 0.18f),
                e * (1f + fx.getTint() * 0.08f),
                e * (1f - fx.getTemperature() * 0.18f), 1f);
        cm.postConcat(scale);

        boolean hasShaderParams = Math.abs(fx.getHighlights()) > 0.001f
                || Math.abs(fx.getShadows()) > 0.001f
                || Math.abs(fx.getFade()) > 0.001f
                || fx.getVignette() > 0.001f
                || fx.getGrain() > 0.001f;

        android.graphics.RenderEffect colorMatrixEffect =
                android.graphics.RenderEffect.createColorFilterEffect(
                        new android.graphics.ColorMatrixColorFilter(cm));

        if (hasShaderParams
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            String agsl =
                    "uniform shader inputShader;\n"
                    + "uniform float uHighlights;\n"
                    + "uniform float uShadows;\n"
                    + "uniform float uFade;\n"
                    + "uniform float uVignette;\n"
                    + "uniform float uGrain;\n"
                    + "half4 main(float2 co) {\n"
                    + "  half4 color = inputShader.eval(co);\n"
                    + "  float a = color.a;\n"
                    + "  float luma = dot(color.rgb, half3(0.299, 0.587, 0.114));\n"
                    + "  float highlightMask = step(0.5, luma) * clamp((luma - 0.5) * 2.0, 0.0, 1.0);\n"
                    + "  color.rgb = mix(color.rgb, half3(luma), uHighlights * highlightMask);\n"
                    + "  float shadowMask = step(luma, 0.5) * clamp((0.5 - luma) * 2.0, 0.0, 1.0);\n"
                    + "  color.rgb = mix(color.rgb, half3(luma), -uShadows * shadowMask);\n"
                    + "  float fadeUp = max(0.0, uFade);\n"
                    + "  float fadeDown = max(0.0, -uFade);\n"
                    + "  color.rgb = mix(color.rgb, half3(0.0), fadeUp);\n"
                    + "  color.rgb = mix(color.rgb, half3(1.0), fadeDown);\n"
                    + "  float d = distance(co, float2(0.5));\n"
                    + "  color *= 1.0 - (smoothstep(0.72, 0.28, d) * uVignette);\n"
                    + "  float noise = fract(sin(dot(co * 100.0, float2(12.9898, 78.233))) * 43758.5453) - 0.5;\n"
                    + "  color += noise * uGrain * 0.08;\n"
                    + "  color = clamp(color, 0.0, 1.0);\n"
                    + "  return half4(color.rgb, a);\n"
                    + "}";
            try {
                android.graphics.RuntimeShader shader =
                        new android.graphics.RuntimeShader(agsl);
                shader.setFloatUniform("uHighlights", fx.getHighlights());
                shader.setFloatUniform("uShadows", fx.getShadows());
                shader.setFloatUniform("uFade", fx.getFade());
                shader.setFloatUniform("uVignette", fx.getVignette());
                shader.setFloatUniform("uGrain", fx.getGrain());
                android.graphics.RenderEffect shaderEffect =
                        android.graphics.RenderEffect.createRuntimeShaderEffect(
                                shader, "inputShader");
                playerView.setRenderEffect(
                        android.graphics.RenderEffect.createChainEffect(
                                shaderEffect, colorMatrixEffect));
            } catch (Exception ex) {
                playerView.setRenderEffect(colorMatrixEffect);
            }
        } else {
            playerView.setRenderEffect(colorMatrixEffect);
        }
    }

    /** Tint the Filter tool green when the selected clip has any active color/grade effect. */
    private void updateFilterUI(@NonNull Clip clip) {
        int color = clip.getEffectStack().isActive() ? 0xFF4CAF50 : 0xFF888888;
        View icon = findViewById(R.id.tool_filter_icon);
        if (icon instanceof TextView) ((TextView) icon).setTextColor(color);
        View label = findViewById(R.id.tool_filter_label);
        if (label instanceof TextView) ((TextView) label).setTextColor(color);
    }

    private void updateSpeedUI(float speed) {
        if (toolSpeedLabel != null) {
            toolSpeedLabel.setText(formatSpeed(speed));
            int color = Math.abs(speed - 1f) < 0.001f ? 0xFF888888 : 0xFF4CAF50;
            toolSpeedLabel.setTextColor(color);
            TextView icon = findViewById(R.id.tool_speed_icon);
            if (icon != null) icon.setTextColor(color);
        }
    }

    private String formatSpeed(float speed) {
        if (speed == (int) speed) return (int) speed + "x";
        return String.format(java.util.Locale.US, "%.2gx", speed);
    }

    // ── Rotate ───────────────────────────────────────────────────────

    private void rotateNext() {
        Clip clip = getSelectedClip();
        int oldDeg = clip.getRotationDegrees();
        int newDeg = (oldDeg + 90) % 360;
        undoManager.recordAction(new EditActions.RotateAction(clip, oldDeg, newDeg));
        clip.setRotationDegrees(newDeg);
        updateRotateUI(newDeg);
        updatePreviewTransforms();
        scheduleAutoSave();
    }

    private void updateRotateUI(int degrees) {
        boolean active = degrees != 0;
        int color = active ? 0xFF4CAF50 : 0xFF888888;
        if (toolRotateIcon != null) {
            toolRotateIcon.setTextColor(color);
            // Visually rotate the icon to show current rotation
            toolRotateIcon.setRotation(degrees);
        }
        if (toolRotateLabel != null) {
            toolRotateLabel.setText(active ? (degrees + "\u00B0") : getString(R.string.faditor_tool_rotate));
            toolRotateLabel.setTextColor(color);
        }
    }

    // ── Flip ─────────────────────────────────────────────────────────

    private void showFlipPicker() {
        Clip clip = getSelectedClip();
        boolean oldH = clip.isFlipHorizontal();
        boolean oldV = clip.isFlipVertical();
        FlipPickerBottomSheet sheet = FlipPickerBottomSheet.newInstance(
                clip.isFlipHorizontal(), clip.isFlipVertical());
        sheet.setCallback((flipH, flipV) -> {
            if (oldH != flipH) {
                undoManager.recordAction(new EditActions.FlipHorizontalAction(
                        clip, oldH, flipH));
            }
            if (oldV != flipV) {
                undoManager.recordAction(new EditActions.FlipVerticalAction(
                        clip, oldV, flipV));
            }
            clip.setFlipHorizontal(flipH);
            clip.setFlipVertical(flipV);
            updateFlipUI(flipH, flipV);
            updatePreviewTransforms();
            scheduleAutoSave();
        });
        sheet.show(getSupportFragmentManager(), "flipPicker");
    }

    private void updateFlipUI(boolean flipH, boolean flipV) {
        boolean active = flipH || flipV;
        int color = active ? 0xFF4CAF50 : 0xFF888888;
        if (toolFlipIcon != null) {
            toolFlipIcon.setTextColor(color);
            // Mirror the icon when horizontally flipped
            toolFlipIcon.setScaleX(flipH ? -1f : 1f);
            toolFlipIcon.setScaleY(flipV ? -1f : 1f);
        }
        if (toolFlipLabel != null) {
            String label;
            if (flipH && flipV) label = "H+V";
            else if (flipH) label = "H";
            else if (flipV) label = "V";
            else label = getString(R.string.faditor_tool_flip);
            toolFlipLabel.setText(label);
            toolFlipLabel.setTextColor(color);
        }
    }

    // ── Crop ─────────────────────────────────────────────────────────

    /** The aspect ratio presets shown in crop mode. */
    private static final String[][] CROP_ASPECT_PRESETS = {
        {"free",  "Free"},
        {"1_1",   "1:1"},
        {"4_5",   "4:5"},
        {"4_3",   "4:3"},
        {"3_4",   "3:4"},
        {"16_9",  "16:9"},
        {"9_16",  "9:16"},
        {"custom_ratio", "Custom"},
    };

    /** Last-entered custom crop ratio W:H (persisted in-session so the Custom chip re-applies it). */
    private float customCropRatioW = 0f;
    private float customCropRatioH = 0f;

    /**
     * Enters crop mode: shows the crop overlay on the video preview and
     * replaces the bottom controls with a crop-specific toolbar containing
     * Cancel, aspect-ratio presets, and Done buttons.
     */
    private void enterCropMode() {
        if (inCropMode) return;
        inCropMode = true;

        // Hide ALL interactive overlays while cropping so they can't capture
        // touches / be nudged: text overlays, the audio visualizer, and captions.
        if (overlayLayer != null) overlayLayer.setVisibility(View.GONE);
        if (waveformOverlayView != null) {
            preCropVisualizerVis = waveformOverlayView.getVisibility();
            waveformOverlayView.setVisibility(View.GONE);
        }
        if (captionOverlay != null) {
            preCropCaptionVis = captionOverlay.getVisibility();
            captionOverlay.setVisibility(View.GONE);
        }
        if (audioCaptionOverlay != null) {
            preCropAudioCaptionVis = audioCaptionOverlay.getVisibility();
            audioCaptionOverlay.setVisibility(View.GONE);
        }

        Clip clip = getSelectedClip();

        // Save current state for cancel restoration
        preCropPreset = clip.getCropPreset();
        preCropLeft   = clip.getCropLeft();
        preCropTop    = clip.getCropTop();
        preCropRight  = clip.getCropRight();
        preCropBottom = clip.getCropBottom();

        // Pause playback while cropping
        if (playerManager != null && playerManager.isPlaying()) {
            playerManager.pause();
            pauseAudioPlayer();
            updatePlayPauseButton(false);
        }

        // Swap UI FIRST so the layout reflows before we compute the video rect.
        // The player_container grows taller when controls_section is hidden.
        if (controlsSection != null) controlsSection.setVisibility(View.GONE);
        if (cropToolbar != null) cropToolbar.setVisibility(View.VISIBLE);

        // Build aspect preset chips and wire buttons
        buildCropPresetChips();
        findViewById(R.id.crop_btn_done).setOnClickListener(v -> exitCropMode(true));
        findViewById(R.id.crop_btn_cancel).setOnClickListener(v -> exitCropMode(false));
        findViewById(R.id.crop_btn_reset).setOnClickListener(v -> {
            if (cropOverlay != null) {
                cropOverlay.setLockedAspectRatio(0f);
                cropOverlay.setSnapToCenter(cropSnapToCenter);
                // Re-compute videoRect after reset since container may have changed
                android.graphics.RectF videoRect = computeVideoContentRect();
                cropOverlay.setVideoContentRect(videoRect);
                cropOverlay.setSnapToCenter(cropSnapToCenter);
                cropOverlay.activate();
                Clip c = getSelectedClip();
                c.setCustomCropBounds(0f, 0f, 1f, 1f);
                highlightActivePresetChip("free");
                updateCropUI("custom");
            }
            Toast.makeText(this, R.string.faditor_crop_reset, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.crop_btn_auto_crop).setOnClickListener(v -> autoCropBlackBars());
        findViewById(R.id.crop_btn_snap_center).setOnClickListener(v -> toggleCropSnapCenter());

        // Set clip to "custom" and defer crop overlay activation until layout is complete.
        // Using OnGlobalLayoutListener ensures the player container has its final
        // dimensions after the controls section went GONE.
        clip.setCropPreset("custom");
        FrameLayout container = findViewById(R.id.player_container);
        container.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        container.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        activateFreeCrop();
                    }
                });

        FLog.d(TAG, "Entered crop mode (saved: preset=" + preCropPreset
                + ", bounds=[" + preCropLeft + "," + preCropTop
                + "," + preCropRight + "," + preCropBottom + "])");
    }

    /**
     * Exit crop mode.
     *
     * @param apply {@code true} to keep the crop, {@code false} to revert
     */
    private void exitCropMode(boolean apply) {
        if (!inCropMode) return;
        inCropMode = false;

        // Restore text overlays after cropping.
        if (overlayLayer != null) {
            overlayLayer.setVisibility(View.VISIBLE);
            overlayLayer.post(() -> overlayLayer.rebuild());
        }
        // Restore the visualizer + captions to their pre-crop visibility.
        if (waveformOverlayView != null) waveformOverlayView.setVisibility(preCropVisualizerVis);
        if (captionOverlay != null) captionOverlay.setVisibility(preCropCaptionVis);
        if (audioCaptionOverlay != null) audioCaptionOverlay.setVisibility(preCropAudioCaptionVis);

        Clip clip = getSelectedClip();

        if (apply) {
            // Keep current overlay bounds — they were already saved to the clip
            // via the OnCropChangeListener. Determine if user made a meaningful crop.
            boolean isFullFrame = Math.abs(clip.getCropLeft()) < 0.01f
                    && Math.abs(clip.getCropTop()) < 0.01f
                    && Math.abs(clip.getCropRight() - 1f) < 0.01f
                    && Math.abs(clip.getCropBottom() - 1f) < 0.01f;
            if (isFullFrame) {
                clip.setCropPreset("none");
                clip.setCustomCropBounds(0f, 0f, 1f, 1f);
            }
            // Record undo action for crop change
            String newPreset = clip.getCropPreset();
            float newL = clip.getCropLeft(), newT = clip.getCropTop();
            float newR = clip.getCropRight(), newB = clip.getCropBottom();
            if (!preCropPreset.equals(newPreset)
                    || preCropLeft != newL || preCropTop != newT
                    || preCropRight != newR || preCropBottom != newB) {
                undoManager.recordAction(new EditActions.CropAction(
                        clip,
                        preCropPreset, preCropLeft, preCropTop, preCropRight, preCropBottom,
                        newPreset, newL, newT, newR, newB));
            }
            // else keep "custom" preset with current bounds
            Toast.makeText(this, R.string.faditor_crop_applied, Toast.LENGTH_SHORT).show();
            FLog.d(TAG, "Crop applied: preset=" + clip.getCropPreset()
                    + ", bounds=[" + clip.getCropLeft() + "," + clip.getCropTop()
                    + "," + clip.getCropRight() + "," + clip.getCropBottom() + "]");
        } else {
            // Revert to saved state
            clip.setCropPreset(preCropPreset);
            clip.setCustomCropBounds(preCropLeft, preCropTop, preCropRight, preCropBottom);
            Toast.makeText(this, R.string.faditor_crop_cancelled, Toast.LENGTH_SHORT).show();
            FLog.d(TAG, "Crop cancelled, reverted to: " + preCropPreset);
        }

        // Deactivate the overlay
        if (cropOverlay != null && cropOverlay.isActive()) {
            cropOverlay.deactivate();
        }

        // Swap: show controls, hide crop toolbar
        if (cropToolbar != null) cropToolbar.setVisibility(View.GONE);
        if (controlsSection != null) controlsSection.setVisibility(View.VISIBLE);

        updateCropUI(clip.getCropPreset());

        // Defer preview transforms until layout has settled — the player container
        // changes size when the controls section becomes visible again.
        FrameLayout container = findViewById(R.id.player_container);
        container.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        container.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        updatePreviewTransforms();
                    }
                });

        scheduleAutoSave();
    }

    /**
     * Builds the aspect-ratio preset chips inside the crop toolbar.
     */
    private void buildCropPresetChips() {
        LinearLayout row = findViewById(R.id.crop_presets_row);
        if (row == null) return;
        row.removeAllViews();

        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(this, R.font.materialicons);

        for (String[] preset : CROP_ASPECT_PRESETS) {
            String key   = preset[0];
            String label = preset[1];

            TextView chip = new TextView(this);
            chip.setText(label);
            chip.setTextSize(13);
            chip.setTextColor(0xFFCCCCCC);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding((int)(14 * dp), (int)(8 * dp), (int)(14 * dp), (int)(8 * dp));
            chip.setBackgroundResource(R.drawable.settings_home_row_bg);
            chip.setTag(key);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int)(6 * dp));
            chip.setLayoutParams(lp);

            chip.setOnClickListener(v -> applyCropAspectPreset(key));
            row.addView(chip);
        }

        // Highlight the default ("free")
        highlightActivePresetChip("free");
    }

    /**
     * Applies an aspect ratio preset to the crop overlay.
     */
    private void applyCropAspectPreset(String key) {
        if (cropOverlay == null || !cropOverlay.isActive()) return;

        if ("custom_ratio".equals(key)) {
            showCustomCropRatioDialog();
            return;
        }

        float ratio;
        switch (key) {
            case "1_1":  ratio = 1f;            break;
            case "4_5":  ratio = 4f / 5f;       break;
            case "4_3":  ratio = 4f / 3f;       break;
            case "3_4":  ratio = 3f / 4f;       break;
            case "16_9": ratio = 16f / 9f;      break;
            case "9_16": ratio = 9f / 16f;      break;
            default:     ratio = 0f;             break; // free
        }

        cropOverlay.setLockedAspectRatio(ratio);
        highlightActivePresetChip(key);
    }

    /**
     * Prompt for a custom W:H crop aspect ratio (road_map "numeric ratio entry"). Two number fields;
     * on confirm, locks the crop overlay to W/H and highlights the Custom chip. Invalid/blank input is
     * ignored (keeps the current lock). The last-entered ratio is remembered for the session so tapping
     * Custom again pre-fills it.
     */
    private void showCustomCropRatioDialog() {
        if (cropOverlay == null || !cropOverlay.isActive()) return;
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout rowLayout = new LinearLayout(this);
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);
        rowLayout.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (20 * dp);
        rowLayout.setPadding(pad, (int) (8 * dp), pad, 0);

        android.widget.EditText wField = new android.widget.EditText(this);
        wField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        wField.setHint("W");
        wField.setGravity(Gravity.CENTER);
        if (customCropRatioW > 0f) wField.setText(trimNum(customCropRatioW));
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wField.setLayoutParams(wLp);
        rowLayout.addView(wField);

        TextView colon = new TextView(this);
        colon.setText(":");
        colon.setTextSize(20);
        colon.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
        rowLayout.addView(colon);

        android.widget.EditText hField = new android.widget.EditText(this);
        hField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        hField.setHint("H");
        hField.setGravity(Gravity.CENTER);
        if (customCropRatioH > 0f) hField.setText(trimNum(customCropRatioH));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hField.setLayoutParams(hLp);
        rowLayout.addView(hField);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.faditor_crop_custom_ratio_title)
                .setView(rowLayout)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    try {
                        float w = Float.parseFloat(wField.getText().toString().trim());
                        float h = Float.parseFloat(hField.getText().toString().trim());
                        if (w > 0f && h > 0f) {
                            customCropRatioW = w;
                            customCropRatioH = h;
                            cropOverlay.setLockedAspectRatio(w / h);
                            highlightActivePresetChip("custom_ratio");
                        }
                    } catch (NumberFormatException ignored) { /* keep current lock */ }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Format a positive float as a compact ratio component (drops a trailing ".0"). */
    private static String trimNum(float v) {
        if (v == Math.rint(v)) return String.valueOf((int) v);
        return String.valueOf(v);
    }

    /**
     * Highlights the active aspect ratio chip and dims the others.
     */
    private void highlightActivePresetChip(String activeKey) {
        LinearLayout row = findViewById(R.id.crop_presets_row);
        if (row == null) return;

        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof TextView) {
                boolean isActive = activeKey.equals(child.getTag());
                ((TextView) child).setTextColor(isActive ? 0xFF4CAF50 : 0xFFCCCCCC);
                ((TextView) child).setTypeface(null,
                        isActive ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
    }

    /**
     * Activate the free crop overlay on top of the video preview.
     */
    private void activateFreeCrop() {
        if (cropOverlay == null) return;

        // Compute the video content rect inside the player view
        // PlayerView with resize_mode="fit" centres the video
        playerView.post(() -> {
            android.graphics.RectF videoRect = computeVideoContentRect();
            FLog.d(TAG, "activateFreeCrop: videoRect=" + videoRect
                    + ", cropOverlay size=" + cropOverlay.getWidth() + "x" + cropOverlay.getHeight());
            cropOverlay.setVideoContentRect(videoRect);
            cropOverlay.setSnapToCenter(cropSnapToCenter);

            Clip clip = getSelectedClip();
            if ("custom".equals(clip.getCropPreset())
                    && (clip.getCropLeft() > 0 || clip.getCropTop() > 0
                    || clip.getCropRight() < 1 || clip.getCropBottom() < 1)) {
                // Restore previous custom crop bounds
                FLog.d(TAG, "activateFreeCrop: restoring bounds ["
                        + clip.getCropLeft() + "," + clip.getCropTop()
                        + "," + clip.getCropRight() + "," + clip.getCropBottom() + "]");
                cropOverlay.activate(
                        clip.getCropLeft(), clip.getCropTop(),
                        clip.getCropRight(), clip.getCropBottom());
            } else {
                FLog.d(TAG, "activateFreeCrop: full frame (no prior custom bounds)");
                cropOverlay.activate();
            }

            cropOverlay.setOnCropChangeListener((left, top, right, bottom) -> {
                clip.setCustomCropBounds(left, top, right, bottom);
                if (!inCropMode) updatePreviewTransforms();
                scheduleAutoSave();
            });
        });
    }

    /**
     * Compute where the video content is rendered inside the PlayerView.
     * Accounts for letterboxing (fit mode).
     */
    @NonNull
    /**
     * Stable rect representing the OUTPUT CANVAS in preview screen-space. When a
     * fixed canvas preset is active, {@link #applyCanvasFrame()} sizes the
     * PlayerView exactly to the canvas, so the PlayerView's bounds ARE the canvas.
     *
     * <p>Captions and text overlays are authored and exported in CANVAS
     * coordinates (font = fraction × canvas height; position = fraction of the
     * canvas). They must therefore size/position against THIS rect — not the
     * per-clip video content rect, which changes with each clip's decoded source
     * resolution and was making caption size jump around while scrubbing and not
     * match the export. Falls back to the video content rect for the "original"
     * preset (where the canvas == the source frame).</p>
     */
    private android.graphics.RectF computeCanvasRect() {
        if (playerView != null && project != null
                && project.getCanvasPreset() != null
                && !"original".equals(project.getCanvasPreset())) {
            int w = playerView.getWidth();
            int h = playerView.getHeight();
            if (w > 0 && h > 0) {
                float left = playerView.getLeft();
                float top = playerView.getTop();
                return new android.graphics.RectF(left, top, left + w, top + h);
            }
        }
        return computeVideoContentRect();
    }

    /**
     * Decoded video size with a stable fallback. {@code getVideoSize()} returns 0
     * for a moment after every seek / clip-switch; using that transient value made
     * the crop-zoom preview flip between filled and letterboxed. We cache the last
     * non-zero size and fall back to it. Returns {@code null} only when nothing is
     * known yet.
     */
    @Nullable
    private int[] effectiveVideoSize() {
        if (playerManager != null && playerManager.getPlayer() != null) {
            androidx.media3.common.VideoSize vs = playerManager.getPlayer().getVideoSize();
            if (vs.width > 0 && vs.height > 0) {
                lastDecodedVideoW = vs.width;
                lastDecodedVideoH = vs.height;
                return new int[]{vs.width, vs.height};
            }
        }
        if (lastDecodedVideoW > 0 && lastDecodedVideoH > 0) {
            return new int[]{lastDecodedVideoW, lastDecodedVideoH};
        }
        return null;
    }

    private android.graphics.RectF computeVideoContentRect() {
        int viewW = playerView.getWidth();
        int viewH = playerView.getHeight();

        // Offset for PlayerView position within its parent (e.g. when canvas preview
        // constrains PlayerView to a smaller centered area inside the container).
        // The crop overlay is match_parent on the container, so we need absolute offsets.
        float offsetX = playerView.getLeft();
        float offsetY = playerView.getTop();

        // Use the decoded video dimensions (with stable fallback)
        int[] vsz = effectiveVideoSize();
        if (vsz != null) {
            int videoW = vsz[0];
            int videoH = vsz[1];

            if (videoW > 0 && videoH > 0) {
                float videoAspect = (float) videoW / videoH;
                float viewAspect = (float) viewW / viewH;

                float renderW, renderH;
                if (videoAspect > viewAspect) {
                    // Video is wider — letterbox top/bottom
                    renderW = viewW;
                    renderH = viewW / videoAspect;
                } else {
                    // Video is taller — pillarbox left/right
                    renderH = viewH;
                    renderW = viewH * videoAspect;
                }

                float left = offsetX + (viewW - renderW) / 2f;
                float top = offsetY + (viewH - renderH) / 2f;
                android.graphics.RectF result = new android.graphics.RectF(
                        left, top, left + renderW, top + renderH);
                FLog.d(TAG, "computeVideoContentRect: result=" + result);
                return result;
            }
        }

        // Fallback: assume full view
        FLog.d(TAG, "computeVideoContentRect: FALLBACK to full view");
        return new android.graphics.RectF(offsetX, offsetY, offsetX + viewW, offsetY + viewH);
    }

    private void updateCropUI(@NonNull String preset) {
        boolean active = !"none".equals(preset);
        int color = active ? 0xFF4CAF50 : 0xFF888888;
        if (toolCropIcon != null) {
            toolCropIcon.setTextColor(color);
        }
        if (toolCropLabel != null) {
            String label;
            if ("custom".equals(preset)) {
                label = getString(R.string.faditor_tool_crop_free);
            } else if (active) {
                label = preset;
            } else {
                label = getString(R.string.faditor_tool_crop);
            }
            toolCropLabel.setText(label);
            toolCropLabel.setTextColor(color);
        }
        if (cropSnapCenter != null) {
            cropSnapCenter.setTextColor(cropSnapToCenter ? 0xFF4CAF50 : 0xFF888888);
            cropSnapCenter.setAlpha(cropSnapToCenter ? 1f : 0.55f);
        }
    }

    private void toggleCropSnapCenter() {
        cropSnapToCenter = !cropSnapToCenter;
        if (cropOverlay != null) {
            cropOverlay.setSnapToCenter(cropSnapToCenter);
        }
        updateCropUI(getSelectedClip() != null ? getSelectedClip().getCropPreset() : "custom");
        Toast.makeText(this,
                cropSnapToCenter ? R.string.faditor_crop_snap_on : R.string.faditor_crop_snap_off,
                Toast.LENGTH_SHORT).show();
    }

    private void autoCropBlackBars() {
        Clip clip = getSelectedClip();
        if (clip == null || clip.isImageClip()) {
            Toast.makeText(this, R.string.faditor_crop_error, Toast.LENGTH_SHORT).show();
            return;
        }
        float[] detected = detectBlackBarCropBounds(clip);
        if (detected == null) {
            detected = autoAspectCropBounds(clip);
        }
        if (detected == null) {
            Toast.makeText(this, R.string.faditor_crop_no_black_bars, Toast.LENGTH_SHORT).show();
            return;
        }
        clip.setCropPreset("custom");
        clip.setCustomCropBounds(detected[0], detected[1], detected[2], detected[3]);
        if (cropOverlay != null) {
            cropOverlay.activate(detected[0], detected[1], detected[2], detected[3]);
            cropOverlay.setSnapToCenter(cropSnapToCenter);
        }
        updateCropUI("custom");
        updatePreviewTransforms();
        Toast.makeText(this, R.string.faditor_crop_auto_applied, Toast.LENGTH_SHORT).show();
    }

    @Nullable
    private float[] detectBlackBarCropBounds(@NonNull Clip clip) {
        long sampleMs = Math.min(clip.getOutPointMs() - 100, Math.max(clip.getInPointMs() + 100,
                clip.getInPointMs() + (clip.getOutPointMs() - clip.getInPointMs()) / 2));
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, resolvePlaybackUri(clip.getSourceUri()));
            Bitmap frame = retriever.getFrameAtTime(sampleMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            int w = frame.getWidth();
            int h = frame.getHeight();
            float[] bars = detectBlackEdges(frame, w, h);
            if (frame != null) frame.recycle();
            float left = bars[0], top = bars[1], right = 1f - bars[2], bottom = 1f - bars[3];
            float cropped = 1f - ((right - left) * (bottom - top));
            if (cropped < 0.025f) return null;
            return new float[]{left, top, right, bottom};
        } catch (Exception e) {
            FLog.w(TAG, "Auto crop black bars failed", e);
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    @NonNull
    private float[] detectBlackEdges(@NonNull Bitmap frame, int w, int h) {
        float[] bars = new float[4];
        int stepX = Math.max(4, w / 96);
        int stepY = Math.max(4, h / 96);
        bars[0] = scanBlackEdge(frame, true, 0, w, h, stepX, stepY);
        bars[2] = scanBlackEdge(frame, false, 0, w, h, stepX, stepY);
        bars[1] = scanBlackEdge(frame, true, 1, w, h, stepX, stepY);
        bars[3] = scanBlackEdge(frame, false, 1, w, h, stepX, stepY);
        return bars;
    }

    private float scanBlackEdge(@NonNull Bitmap frame, boolean fromStart, int edge, int w, int h, int stepX, int stepY) {
        int maxScan = edge == 0 || edge == 2 ? Math.round(w * 0.45f) : Math.round(h * 0.45f);
        int limit = Math.min(maxScan, edge == 0 || edge == 2 ? w - 1 : h - 1);
        for (int d = 0; d <= limit; d += Math.max(1, edge == 0 || edge == 2 ? stepX : stepY)) {
            int x = fromStart ? d : (w - 1 - d);
            int y = edge == 1 ? d : (edge == 3 ? (h - 1 - d) : h / 2);
            if (edge == 0 || edge == 2) {
                int black = 0;
                int total = 0;
                for (int yy = 0; yy < h; yy += stepY) {
                    int pixel = frame.getPixel(x, yy);
                    if (isBlackPixel(pixel)) black++;
                    total++;
                }
                if (black / (float) total > 0.34f) continue;
            } else {
                int black = 0;
                int total = 0;
                for (int xx = 0; xx < w; xx += stepX) {
                    int pixel = frame.getPixel(xx, y);
                    if (isBlackPixel(pixel)) black++;
                    total++;
                }
                if (black / (float) total > 0.34f) continue;
            }
            int count = edge == 0 || edge == 2 ? d / stepX : d / stepY;
            return Math.max(0f, Math.min(0.45f, count * (edge == 0 || edge == 2 ? stepX / (float) w : stepY / (float) h)));
        }
        return 0f;
    }

    private boolean isBlackPixel(int pixel) {
        int r = Color.red(pixel);
        int g = Color.green(pixel);
        int b = Color.blue(pixel);
        return (r + g + b) / 3f < 34f;
    }

    @Nullable
    private float[] autoAspectCropBounds(@NonNull Clip clip) {
        int w = getVideoWidth(clip);
        int h = getVideoHeight(clip);
        if (w <= 0 || h <= 0) return null;
        float aspect = w / (float) h;
        if (aspect < 16f / 9f) {
            float cropH = w / (16f / 9f);
            float top = (h - cropH) / (2f * h);
            return new float[]{0f, top, 1f, top + cropH / h};
        }
        if (aspect > 16f / 9f) {
            float cropW = h * (9f / 16f);
            float left = (w - cropW) / (2f * w);
            return new float[]{left, 0f, left + cropW / w, 1f};
        }
        return null;
    }

    // ── Canvas ────────────────────────────────────────────────────────

    /**
     * Shows the canvas aspect ratio picker bottom sheet.
     */
    private void showCanvasPicker() {
        String oldPreset = project.getCanvasPreset();
        CanvasPickerBottomSheet sheet =
                CanvasPickerBottomSheet.newInstance(project.getCanvasPreset());
        sheet.setCallback(preset -> {
            if (!oldPreset.equals(preset)) {
                undoManager.recordAction(new EditActions.CanvasPresetAction(
                        project, oldPreset, preset));
            }
            project.setCanvasPreset(preset);
            updateCanvasUI(preset);
            applyCanvasPreview(preset);
            saveProjectNow();

            // Format label for toast
            String displayLabel = CanvasPickerBottomSheet.displayLabel(preset);
            Toast.makeText(this, getString(R.string.faditor_canvas_applied, displayLabel), Toast.LENGTH_SHORT).show();
        });
        sheet.show(getSupportFragmentManager(), "canvas_picker");
    }

    /**
     * Updates the canvas tool button UI to reflect the current preset.
     */
    private void updateCanvasUI(@NonNull String preset) {
        boolean active = !"original".equals(preset);
        int color = active ? 0xFF4CAF50 : 0xFF888888;
        if (toolCanvasIcon != null) {
            toolCanvasIcon.setTextColor(color);
        }
        if (toolCanvasLabel != null) {
            String label = active ? CanvasPickerBottomSheet.displayLabel(preset) :
                    getString(R.string.faditor_tool_canvas);
            toolCanvasLabel.setText(label);
            toolCanvasLabel.setTextColor(color);
        }
    }

    /**
     * Adjust player container to preview the canvas aspect ratio.
     * When a canvas preset is active, constrains PlayerView to the target aspect ratio
     * so black bars are visible around the video.
     */
    private void applyCanvasPreview(@NonNull String preset) {
        applyCanvasFrame();
    }

    /**
     * Lay out a single fixed canvas rect (from the project aspect) and size every
     * preview view to it, so video / image / slide all show at the SAME framing.
     * The {@link com.fadcam.ui.faditor.player.CanvasFrameView} behind them fills
     * the canvas with black and hatches everything outside it.
     */
    private void applyCanvasFrame() {
        FrameLayout container = findViewById(R.id.player_container);
        if (container == null) return;
        int containerW = container.getWidth();
        int containerH = container.getHeight();
        if (containerW <= 0 || containerH <= 0) {
            // Not measured yet — retry after layout.
            container.post(this::applyCanvasFrame);
            return;
        }

        float canvasAspect = resolveCanvasAspect();
        int targetW, targetH;
        if (canvasAspect <= 0) {
            // Unknown aspect: fill the whole preview (no hatch).
            targetW = containerW;
            targetH = containerH;
            if (canvasFrame != null) canvasFrame.setFill();
        } else {
            float containerAspect = (float) containerW / containerH;
            if (canvasAspect > containerAspect) {
                targetW = containerW;
                targetH = Math.round(containerW / canvasAspect);
            } else {
                targetH = containerH;
                targetW = Math.round(containerH * canvasAspect);
            }
            if (canvasFrame != null) canvasFrame.setCanvasRect(targetW, targetH);
        }

        sizeToCanvas(playerView, targetW, targetH);
        sizeToCanvas(imagePreview, targetW, targetH);
        sizeToCanvas(slidePreview, targetW, targetH);
        // Transition layers must occupy exactly the canvas rect too, or the incoming
        // frame / fade veil spills past the canvas (drawing over the hatch and
        // "snapping" back to canvas size when the transition ends).
        sizeToCanvas(transitionPreviewOverlay, targetW, targetH);
        sizeToCanvas(glTransitionPreviewView, targetW, targetH);
        sizeToCanvas(waveformOverlayView, targetW, targetH);
        // Safe-zone guide: sized to the same canvas rect so its margins read against
        // the actual output frame, not the whole (possibly hatched) preview area.
        sizeToCanvas(safeZoneOverlay, targetW, targetH);
        if (safeZoneOverlay != null) {
            safeZoneOverlay.setCanvasAspect(canvasAspect > 0 ? canvasAspect
                    : (targetH > 0 ? (float) targetW / targetH : -1f));
        }
    }

    private void sizeToCanvas(@Nullable View v, int w, int h) {
        if (v == null) return;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = android.view.Gravity.CENTER;
        v.setLayoutParams(lp);
    }

    /**
     * Enable/disable the preview-only 9:16 safe-zone guide (from the Settings sheet
     * toggle). Purely a preview visual — the overlay only actually draws when the
     * canvas aspect is ~9:16 (see {@link com.fadcam.ui.faditor.player.SafeZoneOverlayView}),
     * but visibility itself is gated here so it costs nothing when off.
     */
    private void setSafeZoneOverlayEnabled(boolean enabled) {
        if (safeZoneOverlay == null) return;
        safeZoneOverlay.setVisibility(enabled ? View.VISIBLE : View.GONE);
        safeZoneOverlay.setGuideEnabled(enabled);
    }

    /**
     * The project canvas aspect (width/height). Uses the canvas preset ratio, or
     * for "original" the first clip's intrinsic aspect (slide dimensions for an AI
     * slide). Returns -1 when unknown (caller fills the preview).
     */
    private float resolveCanvasAspect() {
        String rawPreset = project.getCanvasPreset();
        if (rawPreset != null && rawPreset.startsWith("custom_")) {
            // Literal W×H custom resolution: resolve via the same parser the
            // export path uses, source dims don't matter here (already fixed).
            int[] dims = CanvasPickerBottomSheet.resolveCanvasDimensions(rawPreset, 0, 0);
            if (dims != null && dims[0] > 0 && dims[1] > 0) {
                return (float) dims[0] / dims[1];
            }
        }
        String preset = project.getCanvasPreset().replace('_', ':').trim();
        switch (preset) {
            case "16:9": return 16f / 9f;
            case "9:16": return 9f / 16f;
            case "1:1":  return 1f;
            case "4:3":  return 4f / 3f;
            case "3:4":  return 3f / 4f;
            case "4:5":  return 4f / 5f;
            case "21:9": return 21f / 9f;
            default: break; // original
        }
        if (project.getTimeline().getClipCount() > 0) {
            Clip c = project.getTimeline().getClip(0);
            com.fadcam.ui.faditor.model.GeneratedSource gs = c.getGeneratedSource();
            if (gs != null && gs.width > 0 && gs.height > 0) {
                return (float) gs.width / gs.height;
            }
            int w = getVideoWidth(c);
            int h = getVideoHeight(c);
            if (w > 0 && h > 0) return (float) w / h;
        }
        return -1f;
    }

    /**
     * Gets video width from clip using MediaMetadataRetriever.
     */
    private int getVideoWidth(@NonNull Clip clip) {
        if (clip.isImageClip()) {
            try (InputStream is = getContentResolver().openInputStream(clip.getSourceUri())) {
                android.graphics.BitmapFactory.Options opts =
                        new android.graphics.BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeStream(is, null, opts);
                return opts.outWidth;
            } catch (Exception e) { return 0; }
        }
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(this, clip.getSourceUri());
            String w = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            r.release();
            return w != null ? Integer.parseInt(w) : 0;
        } catch (Exception e) { return 0; }
    }

    /**
     * Gets video height from clip using MediaMetadataRetriever.
     */
    private int getVideoHeight(@NonNull Clip clip) {
        if (clip.isImageClip()) {
            try (InputStream is = getContentResolver().openInputStream(clip.getSourceUri())) {
                android.graphics.BitmapFactory.Options opts =
                        new android.graphics.BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeStream(is, null, opts);
                return opts.outHeight;
            } catch (Exception e) { return 0; }
        }
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(this, clip.getSourceUri());
            String h = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            r.release();
            return h != null ? Integer.parseInt(h) : 0;
        } catch (Exception e) { return 0; }
    }

    // ── Audio ─────────────────────────────────────────────────────────

    /**
     * Extracts the audio track from the currently selected video clip,
     * saves it to an AAC file, generates a waveform, and adds an AudioClip
     * to the timeline.
     */
    private void extractAudioFromCurrentClip() {
        Clip clip = getSelectedClip();
        if (clip == null || clip.isImageClip()) {
            Toast.makeText(this, R.string.faditor_audio_no_track, Toast.LENGTH_SHORT).show();
            return;
        }

        Uri videoUri = clip.getSourceUri();
        Toast.makeText(this, R.string.faditor_audio_extracting, Toast.LENGTH_SHORT).show();

        audioExecutor.execute(() -> {
            try {
                // ── Step 1: Extract raw audio to AAC file ────────────────
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(this, videoUri, null);

                int audioTrackIndex = -1;
                MediaFormat audioFormat = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat fmt = extractor.getTrackFormat(i);
                    String mime = fmt.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrackIndex = i;
                        audioFormat = fmt;
                        break;
                    }
                }

                if (audioTrackIndex < 0 || audioFormat == null) {
                    runOnUiThread(() -> Toast.makeText(this,
                            R.string.faditor_audio_no_track, Toast.LENGTH_SHORT).show());
                    extractor.release();
                    return;
                }

                long durationUs = audioFormat.containsKey(MediaFormat.KEY_DURATION)
                        ? audioFormat.getLong(MediaFormat.KEY_DURATION) : 0;
                long durationMs = durationUs / 1000;
                if (durationMs <= 0) {
                    // Fallback: use video duration
                    durationMs = clip.getSourceDurationMs();
                }

                // Mux audio track into a proper M4A container (MediaPlayer needs headers).
                // DURABILITY (road_map Tier-1): this file's Uri becomes the AudioClip's PERSISTED
                // sourceUri, so it must survive the OS clearing the cache dir — use getFilesDir()
                // (app-internal, not OS-cleared) like the images dir, NOT getCacheDir(). A cache-dir
                // path would also be silently unrecoverable (recoverStaleCachePaths handles only video).
                extractor.selectTrack(audioTrackIndex);
                File audioDir = new File(getFilesDir(), "faditor_audio");
                if (!audioDir.exists()) audioDir.mkdirs();
                File audioFile = new File(audioDir,
                        "audio_" + System.currentTimeMillis() + ".m4a");

                MediaMuxer muxer = new MediaMuxer(audioFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int muxerTrackIndex = muxer.addTrack(audioFormat);
                muxer.start();

                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                while (true) {
                    buffer.clear();
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;
                    bufferInfo.offset = 0;
                    bufferInfo.size = sampleSize;
                    bufferInfo.presentationTimeUs = extractor.getSampleTime();
                    bufferInfo.flags = extractor.getSampleFlags();
                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo);
                    extractor.advance();
                }

                muxer.stop();
                muxer.release();
                extractor.release();

                Uri audioUri = Uri.fromFile(audioFile);
                final long finalDurationMs = durationMs;

                // ── Step 2: Generate waveform ────────────────────────────
                int[] waveform = generateWaveform(videoUri, audioTrackIndex, 800);

                // ── Step 3: Create AudioClip and add to timeline ─────────
                AudioClip audioClip = new AudioClip(audioUri, finalDurationMs);
                audioClip.setLabel(getString(R.string.faditor_audio_extract_current));
                audioClip.setWaveform(waveform);

                runOnUiThread(() -> {
                    Clip srcClip = getSelectedClip();
                    // Capture pre-extraction state for undo
                    boolean prevMuted = srcClip != null && srcClip.isAudioMuted();
                    float prevVolume = srcClip != null ? srcClip.getVolumeLevel() : 1.0f;

                    project.getTimeline().addAudioClip(audioClip);
                    editorTimeline.setAudioClips(project.getTimeline().getAudioClips());

                    // Record undo action for adding audio clip
                    undoManager.recordAction(new EditActions.AddAudioClipAction(
                            project.getTimeline(), audioClip,
                            srcClip, prevMuted, prevVolume));

                    // Mute the source video clip since audio is now on a separate track
                    if (srcClip != null) {
                        srcClip.setAudioMuted(true);
                        srcClip.setVolumeLevel(0f);
                        playerManager.setVolume(0f);
                        updateVolumeUI(0f, true);
                        FLog.i(TAG, "Auto-muted video clip after audio extraction: "
                                + "audioMuted=" + srcClip.isAudioMuted()
                                + ", volumeLevel=" + srcClip.getVolumeLevel());
                    } else {
                        FLog.w(TAG, "Could not auto-mute: getSelectedClip() returned null");
                    }

                    updateAudioToolUI();
                    prepareAudioPlayer();
                    scheduleAutoSave();
                    Toast.makeText(this, R.string.faditor_audio_extracted,
                            Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                FLog.e(TAG, "Audio extraction failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        R.string.faditor_audio_extract_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * Decodes audio from the given URI and produces a downsampled amplitude
     * array for waveform visualisation.
     *
     * @param uri              source media URI
     * @param audioTrackIndex  index of the audio track in the container
     * @param targetSamples    desired number of waveform bars
     * @return amplitude array (0–255), or null on failure
     */
    @Nullable
    private int[] generateWaveform(@NonNull Uri uri, int audioTrackIndex, int targetSamples) {
        try {
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(this, uri, null);
            extractor.selectTrack(audioTrackIndex);

            MediaFormat format = extractor.getTrackFormat(audioTrackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                extractor.release();
                return null;
            }

            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // Collect all decoded PCM samples (short values)
            List<Short> allSamples = new ArrayList<>();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = inputBuffers[inIdx];
                        int sampleSize = extractor.readSampleData(buf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                // Drain output
                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    ByteBuffer outBuf = outputBuffers[outIdx];
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    ShortBuffer shorts = outBuf.asShortBuffer();
                    while (shorts.hasRemaining()) {
                        allSamples.add(shorts.get());
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                } else if (outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                }
            }

            codec.stop();
            codec.release();
            extractor.release();

            if (allSamples.isEmpty()) return null;

            // Downsample to PEAK bins. W1 accuracy (JoyRaptor 2026-07-06): store the peak (max |sample|)
            // per bin, NOT the mean — averaging smeared out onsets/transients so words couldn't be
            // lined up by ear. Resolution scales with duration (~60 bins/sec, capped) so a long clip
            // isn't crushed into a coarse 800-bin smear; short clips keep the caller's fine target.
            int totalSamples = allSamples.size();
            int channels = 1;
            try { channels = Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)); } catch (Exception ignore) {}
            int sampleRate = 44100;
            try { sampleRate = Math.max(8000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE)); } catch (Exception ignore) {}
            float durationSec = (totalSamples / (float) channels) / sampleRate;
            int target = Math.max(targetSamples, Math.min(6000, Math.round(durationSec * 60f)));
            int samplesPerBin = Math.max(1, totalSamples / target);
            int[] waveform = new int[Math.min(target, totalSamples)];
            for (int i = 0; i < waveform.length; i++) {
                int start = i * samplesPerBin;
                int end = Math.min(start + samplesPerBin, totalSamples);
                int peak = 0;
                for (int j = start; j < end; j++) {
                    int a = Math.abs(allSamples.get(j));
                    if (a > peak) peak = a;
                }
                // Normalise to 0–255.
                waveform[i] = (int) Math.min(255, ((long) peak * 255) / 32768);
            }
            return waveform;

        } catch (Exception e) {
            FLog.e(TAG, "Waveform generation failed", e);
            return null;
        }
    }

    /**
     * Updates the audio tool button UI based on whether audio clips exist.
     */
    private void updateAudioToolUI() {
        boolean hasAudio = project != null && project.getTimeline().hasAudioClips();
        int color = hasAudio ? 0xFF4CAF50 : 0xFF888888;
        if (toolAudioIcon != null) toolAudioIcon.setTextColor(color);
        if (toolAudioLabel != null) toolAudioLabel.setTextColor(color);
    }

    // ── Audio player (playback sync) ─────────────────────────────────

    /**
     * Prepares MediaPlayers for ALL audio clips in the timeline.
     * One MediaPlayer per clip, each pre-prepared for instant playback.
     */
    private void prepareAudioPlayer() {
        releaseAudioPlayer();
        if (project == null || !project.getTimeline().hasAudioClips()) return;

        List<AudioClip> clips = project.getTimeline().getAudioClips();
        for (int i = 0; i < clips.size(); i++) {
            AudioClip ac = clips.get(i);
            if (ac == null) continue;
            final int idx = i;

            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(this, ac.getSourceUri());
                mp.setLooping(false);
                // M-COMP-1: track-mute multiplies over the clip's own mute/level (never
                // overwrites it) — see LayerPreviewController#effectivePreviewVolume.
                float vol = com.fadcam.ui.faditor.compositor.LayerPreviewController
                        .effectivePreviewVolume(project.getTimeline(), ac);
                mp.setVolume(vol, vol);

                audioPlayers.add(mp);
                audioPlayersReady.add(false);

                mp.setOnPreparedListener(p -> {
                    if (idx < audioPlayersReady.size()) {
                        audioPlayersReady.set(idx, true);
                    }
                    FLog.d(TAG, "AudioPlayer[" + idx + "] prepared, duration=" + p.getDuration() + "ms");
                });
                mp.setOnErrorListener((p, what, extra) -> {
                    FLog.e(TAG, "AudioPlayer[" + idx + "] error: what=" + what + " extra=" + extra);
                    if (idx < audioPlayersReady.size()) {
                        audioPlayersReady.set(idx, false);
                    }
                    return true;
                });
                mp.prepareAsync();
            } catch (Exception e) {
                FLog.e(TAG, "Failed to prepare audio player[" + i + "]", e);
            }
        }
    }

    /**
     * Syncs and plays the correct audio player(s) for the current playhead position.
     * Called once when user presses play.
     */
    private void syncAndPlayAudioPlayer() {
        if (project == null || !project.getTimeline().hasAudioClips()) return;

        long playheadMs = editorTimeline.getPlayheadPositionMs();
        List<AudioClip> clips = project.getTimeline().getAudioClips();

        for (int i = 0; i < clips.size() && i < audioPlayers.size(); i++) {
            if (i >= audioPlayersReady.size() || !audioPlayersReady.get(i)) continue;
            AudioClip ac = clips.get(i);
            MediaPlayer mp = audioPlayers.get(i);
            if (ac == null || mp == null) continue;

            long audioStartMs = ac.getOffsetMs();
            long audioEndMs = ac.getEndOnTimelineMs();

            try {
                if (playheadMs >= audioStartMs && playheadMs < audioEndMs) {
                    long seekPos = ac.getInPointMs() + (playheadMs - audioStartMs);
                    // Guard against seeking past the audio file's actual duration
                    int mediaDuration = mp.getDuration();
                    if (mediaDuration > 0 && seekPos >= mediaDuration) {
                        FLog.w(TAG, "AudioPlayer[" + i + "] seekPos=" + seekPos
                                + " exceeds mediaDuration=" + mediaDuration + ", clamping");
                        seekPos = Math.max(0, mediaDuration - 100); // seek near end
                    }
                    mp.seekTo((int) seekPos);
                    float vol = com.fadcam.ui.faditor.compositor.LayerPreviewController
                            .effectivePreviewVolume(project.getTimeline(), ac);
                    mp.setVolume(vol, vol);
                    mp.start();
                } else {
                    if (mp.isPlaying()) mp.pause();
                }
            } catch (Exception e) {
                FLog.e(TAG, "AudioPlayer[" + i + "] sync error", e);
            }
        }
    }

    /**
     * Periodically called from playheadUpdater to start/stop audio players
     * as the playhead enters/exits each audio clip range during playback.
     */
    private void syncAudioPlayerWithPlayhead() {
        if (project == null || !project.getTimeline().hasAudioClips()) return;

        // Gate on ACTUAL playback (isPlaying), NOT getPlayWhenReady — the latter stays true at
        // STATE_ENDED and when the player is stuck in a gap, which kept the music running after the
        // video stopped (bug). A short debounce avoids dipping the music during brief clip-boundary
        // buffering while still pausing runaway audio once playback has really stopped.
        boolean playing = (playerManager != null && playerManager.isPlaying())
                || imagePlaybackActive || audioTailActive;
        if (!playing) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (audioStoppedSinceMs == 0L) audioStoppedSinceMs = now;
            if (now - audioStoppedSinceMs > 400L) pauseAudioPlayer();
            return;
        }
        audioStoppedSinceMs = 0L;

        long playheadMs = editorTimeline.getPlayheadPositionMs();
        List<AudioClip> clips = project.getTimeline().getAudioClips();

        for (int i = 0; i < clips.size() && i < audioPlayers.size(); i++) {
            if (i >= audioPlayersReady.size() || !audioPlayersReady.get(i)) continue;
            AudioClip ac = clips.get(i);
            MediaPlayer mp = audioPlayers.get(i);
            if (ac == null || mp == null) continue;

            long audioStartMs = ac.getOffsetMs();
            long audioEndMs = ac.getEndOnTimelineMs();

            try {
                if (playheadMs >= audioStartMs && playheadMs < audioEndMs) {
                    if (!mp.isPlaying()) {
                        long seekPos = ac.getInPointMs() + (playheadMs - audioStartMs);
                        int mediaDuration = mp.getDuration();
                        if (mediaDuration > 0 && seekPos >= mediaDuration) {
                            FLog.w(TAG, "AudioSync[" + i + "] seekPos=" + seekPos
                                    + " exceeds mediaDuration=" + mediaDuration + ", clamping");
                            seekPos = Math.max(0, mediaDuration - 100);
                        }
                        mp.seekTo((int) seekPos);
                        float vol = com.fadcam.ui.faditor.compositor.LayerPreviewController
                                .effectivePreviewVolume(project.getTimeline(), ac);
                        mp.setVolume(vol, vol);
                        mp.start();
                    }
                } else {
                    if (mp.isPlaying()) {
                        mp.pause();
                    }
                }
            } catch (Exception e) {
                FLog.e(TAG, "Audio sync[" + i + "] error", e);
            }
        }
    }

    /**
     * Each tick, apply the interpolated volume-envelope gain to any playing audio clip
     * that has keyframes, producing the linear fades between keyframes during playback.
     * Clips without keyframes keep their static volume (set elsewhere) untouched.
     */
    private void applyAudioKeyframeGains() {
        if (project == null || !project.getTimeline().hasAudioClips()) return;
        long playheadMs = editorTimeline.getPlayheadPositionMs();
        List<AudioClip> clips = project.getTimeline().getAudioClips();
        for (int i = 0; i < clips.size() && i < audioPlayers.size(); i++) {
            if (i >= audioPlayersReady.size() || !audioPlayersReady.get(i)) continue;
            AudioClip ac = clips.get(i);
            MediaPlayer mp = audioPlayers.get(i);
            if (ac == null || mp == null || !ac.hasVolumeKeyframes()) continue;
            try {
                if (!mp.isPlaying()) continue;
                long clipMs = playheadMs - ac.getOffsetMs();
                boolean trackMuted = com.fadcam.ui.faditor.compositor.LayerPreviewController
                        .isAudioClipTrackMuted(project.getTimeline(), ac);
                float gain = (ac.isMuted() || trackMuted) ? 0f : ac.gainAtClipMs(clipMs);
                gain = Math.max(0f, Math.min(gain, 2.0f));
                mp.setVolume(gain, gain);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Pauses all audio players.
     */
    private void pauseAudioPlayer() {
        for (int i = 0; i < audioPlayers.size(); i++) {
            if (i >= audioPlayersReady.size() || !audioPlayersReady.get(i)) continue;
            try {
                MediaPlayer mp = audioPlayers.get(i);
                if (mp != null && mp.isPlaying()) mp.pause();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Seek all audio players to the current playhead position so audio is
     * frame-accurate after a scrub. Uses SEEK_CLOSEST on API 26+ for
     * frame-level precision, plain seekTo on older builds.
     */
    private void seekAudioPlayersToPlayhead() {
        if (project == null || !project.getTimeline().hasAudioClips()) return;
        long playheadMs = editorTimeline.getPlayheadPositionMs();
        java.util.List<AudioClip> clips = project.getTimeline().getAudioClips();
        for (int i = 0; i < clips.size() && i < audioPlayers.size(); i++) {
            if (i >= audioPlayersReady.size() || !audioPlayersReady.get(i)) continue;
            AudioClip ac = clips.get(i);
            MediaPlayer mp = audioPlayers.get(i);
            if (ac == null || mp == null) continue;
            long audioStartMs = ac.getOffsetMs();
            long audioEndMs = ac.getEndOnTimelineMs();
            try {
                if (playheadMs >= audioStartMs && playheadMs < audioEndMs) {
                    long seekPos = ac.getInPointMs() + (playheadMs - audioStartMs);
                    int mediaDuration = mp.getDuration();
                    if (mediaDuration > 0 && seekPos >= mediaDuration) {
                        seekPos = Math.max(0, mediaDuration - 100);
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        mp.seekTo((int) seekPos, MediaPlayer.SEEK_CLOSEST);
                    } else {
                        mp.seekTo((int) seekPos);
                    }
                    if (mp.isPlaying()) {
                        float vol = com.fadcam.ui.faditor.compositor.LayerPreviewController
                                .effectivePreviewVolume(project.getTimeline(), ac);
                        mp.setVolume(vol, vol);
                    }
                }
            } catch (Exception e) {
                FLog.e(TAG, "Audio seek[" + i + "] error", e);
            }
        }
    }

    /**
     * Releases all audio player resources.
     */
    private void releaseAudioPlayer() {
        for (MediaPlayer mp : audioPlayers) {
            if (mp != null) {
                try { mp.stop(); } catch (Exception ignored) {}
                try { mp.release(); } catch (Exception ignored) {}
            }
        }
        audioPlayers.clear();
        audioPlayersReady.clear();
    }

    // ── Live Preview Transforms ──────────────────────────────────────

    /**
     * Apply rotation, flip, and crop transforms to the PlayerView for live preview.
     */
    private void updatePreviewTransforms() {
        if (project == null || project.getTimeline().isEmpty()) return;
        Clip clip = getSelectedClip();

        int degrees = clip.getRotationDegrees();
        boolean flipH = clip.isFlipHorizontal();
        boolean flipV = clip.isFlipVertical();

        // Apply rotation
        playerView.setRotation(degrees);

        // Base scale: flip + 90/270 shrink
        float baseScaleX = flipH ? -1f : 1f;
        float baseScaleY = flipV ? -1f : 1f;

        if (degrees == 90 || degrees == 270) {
            int w = playerView.getWidth();
            int h = playerView.getHeight();
            if (w > 0 && h > 0) {
                float rotScale = Math.min((float) w / h, (float) h / w);
                baseScaleX *= rotScale;
                baseScaleY *= rotScale;
            }
        }

        // ── Crop preview ─────────────────────────────────────────────
        // When a crop is applied and we're NOT in crop-mode (overlay active),
        // clip the PlayerView to the crop region and scale it to fill the view.
        FrameLayout container = findViewById(R.id.player_container);
        String cropPreset = clip.getCropPreset();
        boolean applyCropZoom = false;

        if ("custom".equals(cropPreset) && !inCropMode) {
            float cropL = clip.getCropLeft();
            float cropT = clip.getCropTop();
            float cropR = clip.getCropRight();
            float cropB = clip.getCropBottom();
            float cropW = cropR - cropL;
            float cropH = cropB - cropT;

            if (cropW > 0.01f && cropH > 0.01f
                    && (cropW < 0.99f || cropH < 0.99f)) {

                int viewW = playerView.getWidth();
                int viewH = playerView.getHeight();
                if (viewW > 0 && viewH > 0) {
                    // Compute video render rect inside PlayerView (no parent offset)
                    float renderW = viewW, renderH = viewH;
                    float videoLeft = 0f, videoTop = 0f;

                    int[] vsz = effectiveVideoSize();
                    if (vsz != null) {
                        {
                            float vidAspect = (float) vsz[0] / vsz[1];
                            float viewAspect = (float) viewW / viewH;
                            if (vidAspect > viewAspect) {
                                renderW = viewW;
                                renderH = viewW / vidAspect;
                            } else {
                                renderH = viewH;
                                renderW = viewH * vidAspect;
                            }
                            videoLeft = (viewW - renderW) / 2f;
                            videoTop = (viewH - renderH) / 2f;
                        }
                    }

                    // Crop rect in PlayerView's own coordinate space
                    float cL = videoLeft + cropL * renderW;
                    float cT = videoTop + cropT * renderH;
                    float cR = videoLeft + cropR * renderW;
                    float cB = videoTop + cropB * renderH;

                    // Clip the PlayerView so only the crop region is drawn
                    playerView.setClipBounds(new android.graphics.Rect(
                            Math.round(cL), Math.round(cT),
                            Math.round(cR), Math.round(cB)));

                    // Scale up so the clipped crop region fills the container
                    float cropPixW = cR - cL;
                    float cropPixH = cB - cT;
                    float sX = (float) viewW / cropPixW;
                    float sY = (float) viewH / cropPixH;
                    float cropScale = Math.min(sX, sY);

                    baseScaleX *= cropScale;
                    baseScaleY *= cropScale;

                    // Crop center in PlayerView coordinates
                    float cropCX = (cL + cR) / 2f;
                    float cropCY = (cT + cB) / 2f;

                    // Pivot stays at view center. We need translation so that
                    // the crop center maps to the container center after scaling.
                    // With pivot = (vW/2, vH/2):
                    //   mapped_x = vW/2 + totalScaleX*(cx - vW/2) + tx
                    //   Want mapped_x = vW/2 → tx = -totalScaleX*(cx - vW/2)
                    // Use absolute cropScale for translation (flip sign handled by scaleX).
                    float tx = cropScale * (viewW / 2f - cropCX);
                    float ty = cropScale * (viewH / 2f - cropCY);

                    playerView.setPivotX(viewW / 2f);
                    playerView.setPivotY(viewH / 2f);
                    playerView.setTranslationX(tx);
                    playerView.setTranslationY(ty);

                    container.setClipChildren(true);
                    container.setClipToPadding(true);
                    applyCropZoom = true;
                }
            }
        }

        if (!applyCropZoom) {
            // Reset crop-related transforms
            playerView.setClipBounds(null);
            playerView.setPivotX(playerView.getWidth() / 2f);
            playerView.setPivotY(playerView.getHeight() / 2f);
            playerView.setTranslationX(0f);
            playerView.setTranslationY(0f);
            if (container != null) {
                container.setClipChildren(false);
                container.setClipToPadding(false);
            }
        }

        playerView.setScaleX(baseScaleX);
        playerView.setScaleY(baseScaleY);
    }

    private void initExport() {
        exportManager = new ExportManager(this, prefsManager);

        // Listen for export status from the :export process (package-scoped broadcasts).
        if (!exportEventsReceiverRegistered) {
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction(ExportService.ACTION_EXPORT_STARTED);
            filter.addAction(ExportService.ACTION_EXPORT_PROGRESS);
            filter.addAction(ExportService.ACTION_EXPORT_COMPLETED);
            filter.addAction(ExportService.ACTION_EXPORT_ERROR);
            filter.addAction(ExportService.ACTION_EXPORT_CANCELLED);
            androidx.core.content.ContextCompat.registerReceiver(this, exportEventsReceiver,
                    filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
            exportEventsReceiverRegistered = true;
        }

        // Export button → show confirmation bottom sheet
        findViewById(R.id.btn_export).setOnClickListener(v -> showExportConfirmation());

        // Cancel export button — a start-intent action reaches the :export process
        // (no binding exists across processes).
        findViewById(R.id.btn_cancel_export).setOnClickListener(v -> {
            android.content.Intent cancelIntent =
                    new android.content.Intent(this, ExportService.class);
            cancelIntent.setAction(ExportService.ACTION_CANCEL_EXPORT);
            startService(cancelIntent);
            exportStartedLocallyAtMs = 0;
            hideExportProgress();
            Toast.makeText(this, R.string.faditor_export_cancelled, Toast.LENGTH_SHORT).show();
        });

        // Back button on export screen: while an export is RUNNING it minimizes to
        // the background (the service exports an edit-immune snapshot, so returning
        // to a live editor is safe); otherwise it just dismisses the overlay.
        // Cancelling stays on the explicit Cancel button only.
        findViewById(R.id.export_btn_back).setOnClickListener(v -> {
            if (isExportRunning()) {
                minimizeExportToBackground();
            } else {
                hideExportProgress();
            }
        });

        // Tapping the thin progress stripe re-opens the minimized export overlay.
        if (exportProgressStripe != null) {
            exportProgressStripe.setOnClickListener(v -> reshowExportProgress());
        }

        // Done button → navigate to Faditor Mini tab
        if (exportBtnDone != null) {
            exportBtnDone.setOnClickListener(v -> {
                hideExportProgress();
                saveProjectNow();
                finish();
            });
        }
    }

    /**
     * Export progress/completion UI updates, driven by {@link #exportEventsReceiver}
     * (package-scoped broadcasts from the out-of-process ExportService). If the
     * Activity is destroyed, events are only reflected via the service's notification.
     */
    private void exportUiOnStarted() {
        exportStartTimeMs = System.currentTimeMillis();
        runOnUiThread(() -> showExportProgress());
    }

    private void exportUiOnProgress(float progress) {
                    runOnUiThread(() -> {
                        int percent = (int) (progress * 100);

                        if (exportProgressStripe != null) {
                            exportProgressStripe.setProgress(progress);
                        }

                        if (exportProgressPercent != null) {
                            exportProgressPercent.setText(percent + "%");
                        }
                        if (exportProgressBar != null) {
                            exportProgressBar.setIndeterminate(false);
                            exportProgressBar.setProgress(percent);
                        }
                        if (exportProgressText != null) {
                            exportProgressText.setText(
                                    getString(R.string.faditor_exporting_percent, percent));
                        }

                        // Compute ETA
                        if (progress > 0.05f && exportEtaText != null) {
                            long elapsed = System.currentTimeMillis() - exportStartTimeMs;
                            long totalEstimated = (long) (elapsed / progress);
                            long remainingMs = totalEstimated - elapsed;
                            exportEtaText.setText(getString(R.string.faditor_export_eta,
                                    formatEta(remainingMs)));
                            exportEtaText.setVisibility(View.VISIBLE);
                        }
                    });
    }

    private void exportUiOnCompleted(@Nullable String outputPath) {
                    runOnUiThread(() -> {
                        FLog.d(TAG, "Export saved to: " + outputPath);

                        if (exportProgressPercent != null) exportProgressPercent.setText("100%");
                        if (exportProgressBar != null) exportProgressBar.setProgress(100);
                        if (exportProgressText != null) {
                            exportProgressText.setText(R.string.faditor_export_complete_summary);
                            exportProgressText.setTextColor(0xFFFFFFFF);
                        }
                        if (exportStatusIcon != null) exportStatusIcon.setText("check_circle");
                        if (exportEtaText != null) exportEtaText.setVisibility(View.GONE);
                        if (exportInfoText != null) exportInfoText.setVisibility(View.GONE);
                        if (exportTitle != null) {
                            exportTitle.setText(R.string.faditor_export_complete_title);
                        }
                        if (exportProgressBar != null) {
                            exportProgressBar.setIndeterminate(false);
                            exportProgressBar.setProgress(100);
                        }

                        View cancelBtn = findViewById(R.id.btn_cancel_export);
                        if (cancelBtn != null) cancelBtn.setVisibility(View.GONE);
                        if (exportBtnDone != null) exportBtnDone.setVisibility(View.VISIBLE);

                        View backBtn = findViewById(R.id.export_btn_back);
                        if (backBtn != null) backBtn.setVisibility(View.INVISIBLE);

                        // Export is done — the running-indicator stripe no longer applies.
                        hideExportProgressStripe();

                        // Minimized (user is editing): don't yank them back to the
                        // overlay — a toast + the system notification announce it.
                        if (exportProgressOverlay != null
                                && exportProgressOverlay.getVisibility() != View.VISIBLE) {
                            reacquirePreviewIfReleased();
                            Toast.makeText(FaditorEditorActivity.this,
                                    R.string.faditor_export_complete_summary,
                                    Toast.LENGTH_LONG).show();
                        }

                        com.fadcam.ui.RecordsFragment.requestRefresh();
                    });
    }

    private void exportUiOnError(@Nullable String errorMessage) {
                    runOnUiThread(() -> {
                        hideExportProgress();
                        Toast.makeText(FaditorEditorActivity.this,
                                getString(R.string.faditor_export_error,
                                        errorMessage != null ? errorMessage : "Unknown error"),
                                Toast.LENGTH_LONG).show();
                        FLog.e(TAG, "Export failed: " + errorMessage);
                    });
    }

    /**
     * Format remaining time estimate into human-readable string.
     */
    @NonNull
    private String formatEta(long remainingMs) {
        long seconds = remainingMs / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) return minutes + "m " + seconds + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m";
    }

    private void initBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // The Stage 2 all-tools drawer closes on back before anything else.
                if (toolsDrawer != null && toolsDrawer.isShowing()) {
                    toolsDrawer.dismiss();
                    return;
                }

                // v2: system back commits + exits the tools edit mode.
                if (toolsAdapter != null && toolsAdapter.isEditMode()) {
                    toolsAdapter.exitEditMode();
                    return;
                }

                // If in crop mode, back = cancel crop
                if (inCropMode) {
                    exitCropMode(false);
                    return;
                }

                // An open top drawer closes on back before the editor does.
                if (volumeDrawerOpen) {
                    hideVolumeDrawer();
                    return;
                }
                if (opacityDrawerOpen) {
                    hideOpacityDrawer();
                    return;
                }
                if (moveDrawerOpen) {
                    hideMoveDrawer();
                    return;
                }
                if (visualizerDrawerOpen) {
                    showVisualizerDrawer(false);
                    return;
                }
                if (transitionPanelOpen) {
                    hideTransitionInspector();
                    showTransitionPanel(false);
                    return;
                }

                // Double-press to exit
                long now = System.currentTimeMillis();
                if (now - lastBackPressTime < BACK_PRESS_INTERVAL_MS) {
                    handleClose();
                } else {
                    lastBackPressTime = now;
                    Toast.makeText(FaditorEditorActivity.this,
                            R.string.faditor_back_press_exit, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    // ── Playback helpers ─────────────────────────────────────────────

    /**
     * Correct the project's source duration using ExoPlayer's actual reported duration.
     * Both MMR and FFprobeKit can disagree with ExoPlayer for edge-case files;
     * if ExoPlayer reports significantly different, prefer ExoPlayer's value.
     */
    private void correctDurationFromPlayer() {
        long playerDurationMs = playerManager.getSourceDuration();
        if (playerDurationMs <= 0) return;

        Clip clip = getSelectedClip();
        // Image clips have a fixed duration; no correction needed
        if (clip.isImageClip()) return;

        long storedDuration = clip.getSourceDurationMs();

        // Only correct if ExoPlayer reports SHORTER duration.
        // For fragmented MP4, ExoPlayer may report the container duration
        // (e.g. 60 000 ms) while FFprobe reports the actual content duration
        // (e.g. 4 096 ms). We trust FFprobe for "longer" values.
        if (playerDurationMs < storedDuration && (storedDuration - playerDurationMs) > 500) {
            FLog.w(TAG, "Duration correction (shorter): stored=" + storedDuration
                    + "ms → ExoPlayer=" + playerDurationMs + "ms");

            long oldIn = clip.getInPointMs();
            long oldOut = clip.getOutPointMs();

            clip.setSourceDurationMs(playerDurationMs);
            // CLAMP trim points to the real duration instead of resetting to 0.
            // Resetting to 0 destroyed the user's trim, causing "play goes to start"
            // on relinked clips whose stored duration came from a remuxed file.
            long newIn = Math.min(oldIn, playerDurationMs - 1);
            long newOut = Math.min(oldOut, playerDurationMs);
            if (newOut <= newIn) { newIn = 0; newOut = playerDurationMs; }
            clip.setInPointMs(newIn);
            clip.setOutPointMs(newOut);

            FLog.d(TAG, "Trim clamped: in=" + oldIn + "→" + newIn
                    + ", out=" + oldOut + "→" + newOut);

            // Refresh UI with corrected duration — but DO NOT reset playhead or player position.
            refreshTotalTimeDisplay();
            editorTimeline.setTrimFromClip(clip);
            // Update BOTH trim bounds silently so seeks use the corrected values.
            playerManager.updateTrimBoundsSilently(newIn, newOut);
        } else {
            FLog.d(TAG, "Duration OK: stored=" + storedDuration
                    + "ms, ExoPlayer=" + playerDurationMs + "ms (keeping stored)");
        }
    }

    private void updatePlayPauseButton(boolean isPlaying) {
        btnPlayPause.setText(isPlaying ? "pause" : "play_arrow");
    }

    // ── Time display helpers ─────────────────────────────────────────

    /**
     * Refresh the total time display to show full project duration.
     * Should be called whenever clips are added, removed, trimmed, or speed-changed.
     */
    private void refreshTotalTimeDisplay() {
        if (project == null || project.getTimeline().isEmpty()) return;
        // Show the EFFECTIVE (post-edit) length — what the export will be — using
        // "≈" when blacked-out spans shorten it from the raw timeline length.
        long effective = 0;
        boolean edited = false;
        for (Clip c : project.getTimeline().getClips()) {
            effective += c.getEffectiveDurationMs();
            if (c.hasRemovedSpans()) edited = true;
        }
        if (edited) {
            timeTotal.setText("≈" + TimeFormatter.formatAuto(effective));
        } else {
            timeTotal.setText(TimeFormatter.formatAuto(
                    project.getTimeline().getTotalDurationMs()));
        }
    }

    /**
     * Compute the absolute playhead position within the entire project timeline.
     * This sums up durations of all preceding segments plus the position within the current one.
     *
     * @param positionInCurrentSegmentMs 0-based position within the current clip's trimmed region
     * @return absolute position in the full project timeline
     */
    private long getAbsolutePlayheadMs(long positionInCurrentSegmentMs) {
        Timeline tl = project.getTimeline();
        long absoluteMs = 0;
        for (int i = 0; i < selectedClipIndex && i < tl.getClipCount(); i++) {
            absoluteMs += tl.getClip(i).getTrimmedDurationMs();
        }
        Clip clip = getSelectedClip();
        if (clip != null) {
            float speed = clip.getSpeedMultiplier();
            if (speed > 0) {
                absoluteMs += (long) (positionInCurrentSegmentMs / speed);
            } else {
                absoluteMs += positionInCurrentSegmentMs;
            }
        } else {
            absoluteMs += positionInCurrentSegmentMs;
        }
        return absoluteMs;
    }

    /**
     * Update the current time display with the absolute playhead position.
     *
     * @param positionInCurrentSegmentMs 0-based position within the current clip's trimmed region
     */
    private void updateCurrentTimeDisplay(long positionInCurrentSegmentMs) {
        long absoluteMs = getAbsolutePlayheadMs(positionInCurrentSegmentMs);
        lastPlayheadAbsoluteMs = absoluteMs;
        Clip currentClip = getSelectedClip();
        float currentSpeed = currentClip != null ? currentClip.getSpeedMultiplier() : 1f;
        long timelineLocalMs = (currentSpeed > 0) ? (long) (positionInCurrentSegmentMs / currentSpeed) : positionInCurrentSegmentMs;
        lastPositionInSegmentMs = timelineLocalMs;
        lastSourcePositionInSegmentMs = positionInCurrentSegmentMs;
        timeCurrent.setText(TimeFormatter.formatAuto(absoluteMs));

        // Volume/opacity/caption-style drawers track the scrub.
        if (volumeDrawerOpen) refreshVolumeDrawer();
        if (opacityDrawerOpen) refreshOpacityDrawer();
        updateOpacityUI();
        refreshCaptionKeyframeDrawer();
        // G2: the object-menu peek sheet tracks the scrub (values + diamonds).
        if (objectMenuSheet != null && objectMenuSheet.isShowing()) {
            objectMenuSheet.onPlayheadChanged(absoluteMs);
        }
        // G3: the keyframe ribbon's diamond tracks the scrub too.
        if (ribbonProp != null) refreshKeyframeRibbon();
        // G4: the manipulation-handles box follows keyframed transforms and
        // hides outside the selected object's time range.
        if (previewHandlesOverlay != null && previewHandlesOverlay.hasTarget()) {
            previewHandlesOverlay.setPlayheadMs(absoluteMs);
        }

        // Drive overlay time-ranges + keyframe animation from the playhead.
        if (overlayLayer != null && overlayLayer.getVisibility() == View.VISIBLE) {
            overlayLayer.setPlayheadMs(absoluteMs);
        }
        // M-COMP-1: same playhead tick drives the IMAGE-track preview surface (scrub +
        // live playback both flow through this one method — PLAN §3.2 scope item 5).
        if (layerImageOverlay != null) {
            layerImageOverlay.setPlayheadMs(absoluteMs);
        }
        // S4: same tick drives sprite frame resolution + keyframed transforms.
        if (spriteOverlayView != null && !spriteOverlayView.isEmpty()) {
            spriteOverlayView.setPlayheadMs(absoluteMs);
        }
        // M-COMP-2: same tick drives the live PiP layer — time-range visibility,
        // keyframed transform, and overlay-decoder sync against the master clock.
        if (overlayVideoLayer != null && !overlayVideoLayer.isEmpty()) {
            overlayVideoLayer.setPlayheadMs(absoluteMs,
                    playerManager != null && playerManager.isPlaying());
        }
        // S3: live cell indicator in the palette panel's transport row.
        if (spritePalettePanel != null) {
            spritePalettePanel.setPlayheadMs(absoluteMs);
        }
        // Drive waveform/spectrum visualizers from the TIMELINE playhead position,
        // not the source position — visualizers are placed at timeline positions
        // and their mapToSourceMs needs a timeline timestamp.
        if (waveformOverlayView != null) {
            waveformOverlayView.setPlayheadMs(editorTimeline.getPlayheadPositionMs());
        }

        // Captions are CLIP-SPECIFIC: show the captions of the clip under the playhead, switching at
        // each cut (fixes captions sticking on a previously-selected clip's transcript across a seam).
        if (captionsActive && captionOverlay != null) {
            Clip phClip = getSelectedClip();
            if (phClip != null && phClip.isCaptionsEnabled() && phClip.hasTranscript()) {
                if (!phClip.getId().equals(captionClipId)) {
                    bindCaptionData(phClip);
                }
                long capSrc = phClip.getInPointMs()
                        + (long) (positionInCurrentSegmentMs * phClip.getSpeedMultiplier());
                captionOverlay.setActiveSourceMs(capSrc);
                if (captionOverlay.getVisibility() != View.VISIBLE) {
                    captionOverlay.setVisibility(View.VISIBLE);
                }
            } else if (captionOverlay.getVisibility() == View.VISIBLE) {
                captionOverlay.setVisibility(View.GONE);
            }
        }
        // Audio caption overlay: find active audio clip at current playhead, show its captions
        if (captionsActive && audioCaptionOverlay != null) {
            AudioClip activeAudio = findAudioClipAtTimelineMs(absoluteMs);
            if (activeAudio != null && activeAudio.isCaptionsEnabled() && activeAudio.hasTranscript()) {
                if (!activeAudio.getId().equals(audioCaptionClipId)) {
                    bindAudioCaptionData(activeAudio);
                }
                long audioLocalMs = absoluteMs - activeAudio.getOffsetMs() + activeAudio.getInPointMs();
                audioCaptionOverlay.setActiveSourceMs(audioLocalMs);
                if (audioCaptionOverlay.getVisibility() != View.VISIBLE) {
                    audioCaptionOverlay.setVisibility(View.VISIBLE);
                }
            } else if (audioCaptionOverlay.getVisibility() == View.VISIBLE) {
                audioCaptionOverlay.setVisibility(View.GONE);
            }
        }
        // FEEDBACK #4 (layers-UX): the caption STYLE chooser (Pop/Zoom/Boxed/…) is only useful
        // while a captioned clip or audio clip is actually under the playhead — it used to stay
        // pinned to the bottom of the preview permanently. Mirror the video/audio caption-overlay
        // visibility just computed above (both were toggled from the model this same pass) so the
        // chooser auto-hides the moment no caption is in play, and reappears when one is.
        if (captionStyleBar != null) {
            boolean captionInPlay =
                    (captionOverlay != null && captionOverlay.getVisibility() == View.VISIBLE)
                    || (audioCaptionOverlay != null
                        && audioCaptionOverlay.getVisibility() == View.VISIBLE);
            int wantCaptionBarVis = captionInPlay ? View.VISIBLE : View.GONE;
            if (captionStyleBar.getVisibility() != wantCaptionBarVis) {
                captionStyleBar.setVisibility(wantCaptionBarVis);
            }
        }

        // Drive the transcript highlight and animated captions from playback.
        if (currentTranscript != null) {
            Clip clip = getSelectedClip();
            if (clip != null) {
                // Check if THIS clip has its own transcript — if so, use it.
                // This fixes the bug where the transcript highlight only worked
                // on the clip that was originally transcribed, not on other clips
                // that also have transcripts.
                com.fadcam.ui.faditor.transcript.NamedTranscript activeNt =
                        clip.getActiveNamedTranscript();
                if (activeNt != null && activeNt.transcript != currentTranscript
                        && !transcriptIsForAudio) {
                    // Switch to this clip's transcript.
                    // Guarded by !transcriptIsForAudio: if the panel is showing an
                    // audio clip's transcript we must NOT overwrite it with the
                    // video clip's transcript (which happens every 50ms via the
                    // playhead updater, causing a flicker).
                    currentTranscript = activeNt.transcript;
                    transcriptClipId = clip.getId();
                    if (transcriptView != null && transcriptPanel != null
                            && transcriptPanel.getVisibility() == View.VISIBLE) {
                        transcriptView.setTranscript(currentTranscript);
                    }
                    syncTimelineTranscript();
                }

                if (clip.getId().equals(transcriptClipId)) {
                    long sourceMs = clip.getInPointMs()
                            + (long) (positionInCurrentSegmentMs * clip.getSpeedMultiplier());
                    if (transcriptView != null && transcriptPanel != null
                            && transcriptPanel.getVisibility() == View.VISIBLE) {
                        transcriptView.setActiveSourceMs(sourceMs);
                    }
                    // (Captions are driven clip-specifically above, independent of the transcript panel.)
                    // Update the timeline transcript highlight
                    editorTimeline.setTranscriptHighlight(selectedClipIndex, sourceMs);
                }
            }
        }

        // Apply volume keyframe envelope to the video clip's audio in live preview.
        {
            Clip clip = getSelectedClip();
            if (clip != null && clip.hasVolumeKeyframes()) {
                float gain = Math.max(0f, Math.min(2f, clip.gainAtClipMs(timelineLocalMs)));
                if (playerManager != null) playerManager.setVolume(gain);
            }
        }

        // Apply clip opacity (keyframe envelope or default 1.0) to the video preview.
        {
            Clip clip = getSelectedClip();
            float opacity = 1f;
            if (clip != null && clip.hasOpacityKeyframes()) {
                opacity = Math.max(0f, Math.min(1f, clip.opacityAtClipMs(timelineLocalMs)));
            }
            if (playerView != null) playerView.setAlpha(opacity);
            if (imagePreview != null && imagePreview.getVisibility() == View.VISIBLE) {
                imagePreview.setAlpha(opacity);
            }
        }

        // Apply caption style keyframe to the caption overlay each tick.
        if (captionOverlay != null) {
            Clip cc = getSelectedClip();
            if (cc != null && cc.hasCaptionStyleKeyframes() && cc.isCaptionsEnabled()) {
                String sid = cc.captionStyleAtClipMs(positionInCurrentSegmentMs);
                if ("hidden".equals(sid)) {
                    if (captionOverlay.getVisibility() == View.VISIBLE) {
                        captionOverlay.setVisibility(View.GONE);
                    }
                } else {
                    com.fadcam.ui.faditor.transcript.CaptionStyle cs =
                            com.fadcam.ui.faditor.transcript.CaptionStyle.byId(sid);
                    captionOverlay.setStyle(cs);
                    // Only reveal if we're in a clip that supports captions
                    // (the higher-level captionsActive check handles this).
                    if (captionOverlay.getVisibility() != View.VISIBLE && captionsActive) {
                        captionOverlay.setVisibility(View.VISIBLE);
                    }
                }
            }
        }

    }

    // ── Image preview helpers ────────────────────────────────────────

    /**
     * Show the image preview overlay and hide the video player.
     *
     * @param imageUri URI of the image to display
     */
    private void showImagePreview(@NonNull Uri imageUri) {
        if (imagePreview == null) return;
        playerView.setVisibility(View.INVISIBLE);
        // Clear stale content before async Glide load
        imagePreview.setImageBitmap(null);
        imagePreview.setVisibility(View.VISIBLE);
        com.bumptech.glide.Glide.with(this)
                .load(imageUri)
                .into(imagePreview);
    }

    /**
     * Hide the image preview overlay and restore the video player.
     */
    private void hideImagePreview() {
        if (imagePreview == null) return;
        imagePreview.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
    }

    /**
     * Copy a content:// URI to app-internal storage so it survives a restart.
     * If the URI is already a file:// scheme, returns it unchanged.
     */
    @NonNull
    private Uri copyUriToInternalStorage(@NonNull Uri uri, @NonNull String subDir) {
        if (!"content".equals(uri.getScheme())) return uri;
        try {
            // Skip files > 10MB to avoid ANR on main thread (persistable URI permission handles survival)
            long size = -1;
            try (android.content.res.AssetFileDescriptor fd =
                         getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (fd != null) size = fd.getLength();
            } catch (Exception ignored) {}
            if (size > 10_000_000) {
                FLog.d(TAG, "Skipping copy for large file (" + (size / 1024 / 1024) + "MB): " + uri);
                return uri;
            }
            java.io.File dir = new java.io.File(getFilesDir(), subDir);
            if (!dir.exists()) dir.mkdirs();
            String name = "asset_" + System.currentTimeMillis() + "_" + uri.getLastPathSegment();
            java.io.File out = new java.io.File(dir, name);
            try (java.io.InputStream is = getContentResolver().openInputStream(uri);
                 java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                if (is == null) return uri;
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) >= 0) os.write(buf, 0, len);
            }
            Uri local = Uri.fromFile(out);
            FLog.d(TAG, "Copied " + uri + " -> " + local);
            return local;
        } catch (Exception e) {
            FLog.w(TAG, "Failed to copy URI to internal storage, using original", e);
            return uri;
        }
    }

    /**
     * Show the live, scrubbable WebView preview for an AI-generated slide clip and
     * seek its GSAP timeline to {@code localMs} (slide-local time). The slide is
     * authored at speed 1 with in-point 0, so the clip's local position maps
     * directly to the slide timeline. Works without any rendered MP4.
     */
    private void showSlidePreview(@NonNull Clip clip, long localMs) {
        if (slidePreview == null) return;
        com.fadcam.ui.faditor.model.GeneratedSource gs = clip.getGeneratedSource();
        if (gs == null) return;
        if (playerManager != null) playerManager.pause();
        playerView.setVisibility(View.INVISIBLE);
        if (imagePreview != null) imagePreview.setVisibility(View.GONE);
        if (!clip.getId().equals(loadedSlideClipId)) {
            java.io.File html = slideHtmlFile(gs.htmlUri);
            if (html != null && html.exists()) {
                slidePreview.loadSlide(html);
                loadedSlideClipId = clip.getId();
            }
        }
        if (slidePreview.getVisibility() != View.VISIBLE) {
            slidePreview.setVisibility(View.VISIBLE);
            slidePreview.bringToFront();
            // Keep editable overlays (and their touch targets) above the slide.
            if (overlayLayer != null) overlayLayer.bringToFront();
        }
        slidePreview.seekTo(localMs);
    }

    /** Hide the slide preview overlay and restore the video player. */
    private void hideSlidePreview() {
        if (slidePreview == null || slidePreview.getVisibility() == View.GONE) return;
        slidePreview.setVisibility(View.GONE);
        if (playerView != null && playerView.getVisibility() != View.VISIBLE) {
            playerView.setVisibility(View.VISIBLE);
        }
    }

    @Nullable
    private java.io.File slideHtmlFile(@Nullable String uri) {
        if (uri == null || uri.isEmpty()) return null;
        if (uri.startsWith("file://")) {
            String p = android.net.Uri.parse(uri).getPath();
            return p != null ? new java.io.File(p) : null;
        }
        return new java.io.File(uri);
    }

    /**
     * Start image clip playback (internal timer, no ExoPlayer).
     *
     * @param startOffsetMs position within the image clip to start from (0-based)
     */
    private void startImagePlayback(long startOffsetMs) {
        imagePlaybackActive = true;
        imagePlaybackStartOffsetMs = startOffsetMs;
        imagePlaybackStartSystemMs = System.currentTimeMillis();
        updatePlayPauseButton(true);
    }

    /**
     * Stop image clip playback timer.
     */
    private void stopImagePlayback() {
        imagePlaybackActive = false;
        updatePlayPauseButton(false);
    }

    /**
     * Get the current elapsed position (ms) within the image clip during playback.
     *
     * @return elapsed position in ms, or 0 if not playing
     */
    private long getImagePlaybackPositionMs() {
        if (!imagePlaybackActive) return imagePlaybackStartOffsetMs;
        long elapsed = System.currentTimeMillis() - imagePlaybackStartSystemMs;
        return imagePlaybackStartOffsetMs + elapsed;
    }

    private void updatePlayheadPosition() {
        if (playerManager == null || project == null || project.getTimeline().isEmpty()) return;

        // Guard: no clip selected — skip auto-advance.
        // This can happen transiently (e.g. during thumbnail preview or after
        // audio tap when onSegmentSelected(-1) is suppressed). Without this
        // guard the playhead could jump to the wrong segment.
        if (selectedClipIndex < 0) return;

        // ── Audio-tail mode: playhead continues past video end ───────
        if (audioTailActive) {
            long elapsed = android.os.SystemClock.elapsedRealtime() - audioTailStartWall;
            long playheadMs = audioTailStartMs + elapsed;
            long timelineEndMs = editorTimeline.getTimelineEndMs();

            if (playheadMs >= timelineEndMs) {
                // Audio tail finished — fully stop
                audioTailActive = false;
                pauseAudioPlayer();
                playheadMs = timelineEndMs;
                updatePlayPauseButton(false);
                FLog.d(TAG, "Audio-tail ended at " + playheadMs + "ms");
            }

            editorTimeline.setPlayheadPositionMs(playheadMs);
            timeCurrent.setText(TimeFormatter.formatAuto(playheadMs));
            return;
        }

        Clip clip = getSelectedClip();
        long sourceDuration = clip.getSourceDurationMs();
        if (sourceDuration <= 0) return;

        // Don't overwrite playhead while user is dragging
        if (userDragging) return;

        // ── Image clip playback (internal timer — LEGACY path only; in gapless mode the
        // engine plays the image as a native playlist window and the ExoPlayer path below
        // drives the playhead like any other clip) ─────────────────────
        if (clip.isImageClip() && imagePlaybackActive && !playerManager.isGapless()) {
            long positionMs = getImagePlaybackPositionMs();
            long clipDuration = clip.getTrimmedDurationMs();

            if (positionMs >= clipDuration) {
                // Image clip reached its end — auto-advance or pause
                stopImagePlayback();
                Timeline timeline = project.getTimeline();
                int nextIndex = selectedClipIndex + 1;
                if (nextIndex < timeline.getClipCount()) {
                    advanceToSegment(nextIndex, true);
                } else {
                    long timelineEndMs = editorTimeline.getTimelineEndMs();
                    if (timelineEndMs > totalEffectiveMs()) {
                        // Audio extends beyond image clip — enter audio-tail
                        stopImagePlayback();
                        audioTailActive = true;
                        audioTailStartMs = totalEffectiveMs();
                        audioTailStartWall = android.os.SystemClock.elapsedRealtime();
                        updatePlayPauseButton(true);
                        FLog.d(TAG, "Image: entering audio-tail");
                    } else {
                        // Last segment — set playhead to end
                        float endFraction = (float) clip.getOutPointMs() / sourceDuration;
                        lastUserPlayheadFraction = endFraction;
                        editorTimeline.setPlayheadFraction(endFraction);
                        long totalMs = timeline.getTotalDurationMs();
                        timeCurrent.setText(TimeFormatter.formatAuto(totalMs));
                        FLog.d(TAG, "Image playback stopped at last segment end");
                    }
                }
                return;
            }

            // Convert position to source fraction for timeline view
            float fraction = (float) (clip.getInPointMs() + positionMs) / sourceDuration;
            fraction = Math.max(0f, Math.min(fraction, 1f));
            lastUserPlayheadFraction = fraction;
            editorTimeline.setPlayheadFraction(fraction);
            updateCurrentTimeDisplay(positionMs);
            return;
        }

        // ── Video clip playback (ExoPlayer) ──────────────────────────
        // Skip ExoPlayer polling for image clips ONLY on the legacy path (no media loaded
        // there). In gapless mode the image IS a playlist window — poll it like a video.
        if (clip.isImageClip() && !playerManager.isGapless()) return;

        boolean isPlaying = playerManager.isPlaying();
        boolean isAtEnd = playerManager.isAtTrimEnd();

        // Check end-of-playback regardless of isPlaying state.
        // At STATE_ENDED, isPlaying() returns false, so the loop-restart
        // code must live OUTSIDE the isPlaying check or the clip freezes
        // on its last frame.
        if (isAtEnd && clip.hasLoopExtension()) {
            long currentPos = playerManager.getCurrentPosition();
            long trimmedDur = clip.getTrimmedDurationMs();
            long visualDuration = clip.getVisualDurationMs();

            if (clip.isLoopModeLooping() && clip.getLoopMode() == Clip.LOOP_MODE_PING_PONG) {
                // LEGACY (gapless flag OFF) PING_PONG preview = honest FORWARD-TAIL replay.
                // The true reversed leg is a baked ffmpeg segment played only by the gapless engine
                // (L2). When the flag is off (or a bake isn't ready) the preview must NOT fake
                // reverse: Media3 REQUIRES playbackSpeed > 0, so the old setPlaybackSpeed(-1f) hack
                // threw / was swallowed and never actually reversed. Instead we replay the trimmed
                // pass FORWARD each wrap — exactly what export used to do for ping-pong before L2 —
                // so the legacy path is simple, non-crashing, and matches its own (old) export.
                FLog.d(TAG, "PingPong(legacy fwd-tail): isAtEnd pending=" + loopRestartPending
                        + " vOff=" + loopVisualOffsetMs + " pos=" + currentPos + "/" + trimmedDur);
                if (loopRestartPending) {
                    FLog.d(TAG, "PingPong(legacy): still pending, skip");
                    return;
                }
                loopRestartPending = true;
                loopVisualOffsetMs += trimmedDur;
                long visualPos = clip.getLoopBeforeMs() + loopVisualOffsetMs;
                if (visualPos >= visualDuration) {
                    FLog.d(TAG, "PingPong(legacy): exhausted, advance");
                    loopVisualOffsetMs = 0;
                    Timeline timeline = project.getTimeline();
                    int nextIndex = selectedClipIndex + 1;
                    if (nextIndex < timeline.getClipCount()) {
                        advanceToSegment(nextIndex, true);
                    } else {
                        playerManager.pause();
                        pauseAudioPlayer();
                        updatePlayPauseButton(false);
                        timeCurrent.setText(TimeFormatter.formatAuto(timeline.getTotalDurationMs()));
                    }
                } else {
                    playerManager.seekTo(0);
                    if (!playerManager.isPlaying()) playerManager.play();
                }
                return;
            }

            if (clip.isLoopModeLooping() && clip.getLoopMode() == Clip.LOOP_MODE_NORMAL
                    && playerManager.isGapless()) {
                // L1: the gapless engine's playlist already contains this clip's before/after
                // loop reps as warm ExoPlayer windows — wraps are crossed natively (no polling,
                // no cold seekTo(0)) and internal rep-to-rep seams are suppressed by the engine
                // (MasterPlaybackEngine.SeamListener fires only on a TIMELINE CLIP change), so
                // isAtTrimEnd()/isAtEnd is false throughout the whole looped clip's playback —
                // this legacy branch only reaches "true" here once the ENTIRE PLAYLIST truly
                // ends. If this looped clip is also the LAST clip on the timeline, fall through
                // to the shared end-of-timeline handling below (advance/pause) instead of running
                // the poll-based wrap math, which would double-drive a playlist the engine
                // already finished. (PLAN_LOOP_PINGPONG.md L1.)
                FLog.d(TAG, "Loop: gapless NORMAL loop reached isAtEnd -> playlist truly ended");
                Timeline timeline = project.getTimeline();
                int nextIndex = selectedClipIndex + 1;
                if (nextIndex < timeline.getClipCount()) {
                    advanceToSegment(nextIndex, true);
                } else {
                    playerManager.pause();
                    pauseAudioPlayer();
                    updatePlayPauseButton(false);
                    long totalMs = timeline.getTotalDurationMs();
                    timeCurrent.setText(TimeFormatter.formatAuto(totalMs));
                }
                return;
            }

            if (clip.isLoopModeLooping()) {
                // NORMAL loop (legacy poll-based path — gapless-active NORMAL clips return
                // above; PING_PONG already returned earlier, so isLoopModeLooping() here can
                // only mean NORMAL).
                FLog.d(TAG, "Loop: isAtEnd pending=" + loopRestartPending
                        + " pos=" + currentPos + "/" + trimmedDur
                        + " vOff=" + loopVisualOffsetMs
                        + " isPlay=" + isPlaying);
                if (loopRestartPending) {
                    FLog.d(TAG, "Loop: still pending, skip");
                    return;
                }
                loopRestartPending = true;
                loopVisualOffsetMs += trimmedDur;
                long visualPos = clip.getLoopBeforeMs() + loopVisualOffsetMs;
                FLog.d(TAG, "Loop: exec vPos=" + visualPos + " vDur=" + visualDuration
                        + " b4=" + clip.getLoopBeforeMs() + " aft=" + clip.getLoopAfterMs());
                if (visualPos >= visualDuration) {
                    FLog.d(TAG, "Loop: exhausted, advance");
                    loopVisualOffsetMs = 0;
                    Timeline timeline = project.getTimeline();
                    int nextIndex = selectedClipIndex + 1;
                    if (nextIndex < timeline.getClipCount()) {
                        advanceToSegment(nextIndex, true);
                    } else {
                        playerManager.pause();
                        pauseAudioPlayer();
                        updatePlayPauseButton(false);
                        long totalMs = timeline.getTotalDurationMs();
                        timeCurrent.setText(TimeFormatter.formatAuto(totalMs));
                    }
                } else {
                    FLog.d(TAG, "Loop: seekTo(0)");
                    playerManager.seekTo(0);
                    if (!playerManager.isPlaying()) {
                        FLog.d(TAG, "Loop: was paused, play()");
                        playerManager.play();
                    }
                }
                return;
            }

            if (clip.getLoopMode() == Clip.LOOP_MODE_STILL) {
            // Still mode: player stays frozen on the last frame, but the
            // timeline playhead should advance through the after-extension
            // at real-time speed, then advance to the next clip.
            if (loopStillExtensionStartMs < 0) {
                loopStillExtensionStartMs = android.os.SystemClock.elapsedRealtime();
                playerManager.pause();
            }
            long elapsed = android.os.SystemClock.elapsedRealtime() - loopStillExtensionStartMs;
            long afterMs = clip.getLoopAfterMs();
            if (elapsed >= afterMs) {
                loopStillExtensionStartMs = -1;
                loopVisualOffsetMs = 0;
                Timeline timeline = project.getTimeline();
                int nextIndex = selectedClipIndex + 1;
                if (nextIndex < timeline.getClipCount()) {
                    advanceToSegment(nextIndex, true);
                } else {
                    pauseAudioPlayer();
                    updatePlayPauseButton(false);
                    long totalMs = timeline.getTotalDurationMs();
                    timeCurrent.setText(TimeFormatter.formatAuto(totalMs));
                }
            } else {
                loopVisualOffsetMs = elapsed;
                long visualPosMs = clip.getLoopBeforeMs() + clip.getTrimmedDurationMs()
                        + Math.min(elapsed, afterMs);
                Timeline tl = project.getTimeline();
                long cumul = 0;
                for (int i = 0; i < tl.getClipCount(); i++) {
                    if (i == selectedClipIndex) break;
                    Clip c = tl.getClip(i);
                    cumul += c.hasLoopExtension() ? c.getVisualDurationMs()
                            : c.getTrimmedDurationMs();
                }
                long totalTimelineMs = editorTimeline.getTimelineEndMs();
                float fraction = totalTimelineMs > 0
                        ? (float) (cumul + Math.min(visualPosMs, visualDuration)) / totalTimelineMs
                        : 0f;
                fraction = Math.max(0f, Math.min(fraction, 1f));
                lastUserPlayheadFraction = fraction;
                editorTimeline.setPlayheadFraction(fraction);
                updateCurrentTimeDisplay(clip.getTrimmedDurationMs()
                        + Math.min(elapsed, afterMs));
            }
            return;
        }
        } // end of if (isAtEnd && clip.hasLoopExtension())

        // (L2) The legacy ping-pong "backward pass" detection was removed with the
        // setPlaybackSpeed(-1f) reverse hack — legacy ping-pong now replays FORWARD (handled in
        // the isAtEnd block above, identically to a NORMAL loop). True reverse is the gapless
        // engine's baked-segment path only.

        if (isPlaying) {
            long currentPos = playerManager.getCurrentPosition();
            // Clear the loop-restart pending flag once the seek-to-0 has
            // landed (player position is near start). This guard lives inside
            // isPlaying because currentPos is ~0 here, NOT at the clip end.
            if (loopRestartPending && currentPos < 300) {
                loopRestartPending = false;
                FLog.d(TAG, "Loop: cleared pending during playback pos=" + currentPos);
            }
            if (transitionPlaybackActive) {
                long transitionSourceDuration = Math.max(1L, transitionPlaybackDurationMs);
                float progress = (currentPos - transitionPlaybackStartPositionMs) / (float) transitionSourceDuration;
                progress = Math.max(0f, Math.min(1f, progress));
                long outputMs = getOutputPositionForCurrentPlayback(currentPos);
                editorTimeline.setPlayheadPositionMs(outputMs);
                updateCurrentTimeDisplay(outputMs);
                renderTransitionPreview(progress, currentPos);
                if (progress >= 1f) {
                    hideTransitionPreview();
                    advanceToSegment(transitionPlaybackSeam + 1, true);
                }
                return;
            }

            long clipDuration = Math.max(1L, clip.getTrimmedDurationMs());
            Transition seamTransition = getTransitionAtSeam(selectedClipIndex);
            if (seamTransition != null) {
                long transitionSourceDuration = Math.max(1L,
                        Math.min(clipDuration, (long) (seamTransition.durationMs * clip.getSpeedMultiplier())));
                if (currentPos >= clipDuration - transitionSourceDuration) {
                    transitionPlaybackActive = true;
                    transitionPlaybackSeam = selectedClipIndex;
                    transitionPlaybackStartPositionMs = clipDuration - transitionSourceDuration;
                    transitionPlaybackDurationMs = transitionSourceDuration;
                    transitionPreviewOverlay.setVisibility(View.GONE);
                    if (glTransitionPreviewView != null) glTransitionPreviewView.clear();
                    if (playerView != null) playerView.setAlpha(1f);
                    return;
                }
            }

            // End of non-looped clip reached while playing — advance or stop
            // (runs after transition detection so transitions are not skipped)
            if (isAtEnd) {
                Timeline timeline = project.getTimeline();
                int nextIndex = selectedClipIndex + 1;
                if (nextIndex < timeline.getClipCount()) {
                    advanceToSegment(nextIndex, true);
                } else {
                    long timelineEndMs = editorTimeline.getTimelineEndMs();
                    if (timelineEndMs > totalEffectiveMs()) {
                        playerManager.pause();
                        audioTailActive = true;
                        audioTailStartMs = totalEffectiveMs();
                        audioTailStartWall = android.os.SystemClock.elapsedRealtime();
                        updatePlayPauseButton(true);
                        FLog.d(TAG, "Entering audio-tail: videoEnd=" + audioTailStartMs
                                + " timelineEnd=" + timelineEndMs);
                    } else {
                        playerManager.pause();
                        pauseAudioPlayer();
                        updatePlayPauseButton(false);
                        long totalMs = timeline.getTotalDurationMs();
                        timeCurrent.setText(TimeFormatter.formatAuto(totalMs));
                        FLog.d(TAG, "Playback stopped at last segment end");
                    }
                }
                return;
            }

            long position = playerManager.getCurrentPosition(); // 0-based within trim
            long absoluteMs = clip.getInPointMs() + position;

            // Live skip: if playback entered a transcript-removed span, jump past it
            if (clip.hasRemovedSpans()) {
                boolean inSpan = false;
                for (long[] span : clip.getRemovedSpans()) {
                    if (absoluteMs >= span[0] && absoluteMs < span[1] - 30) {
                        inSpan = true;
                        long target = span[1] - clip.getInPointMs();
                        long trimDur = clip.getOutPointMs() - clip.getInPointMs();
                        if (target >= trimDur - 30) {
                            playerManager.pause();
                            updatePlayPauseButton(false);
                            pendingSkipSeekTarget = -1;
                        } else if (pendingSkipSeekTarget != target) {
                            pendingSkipSeekTarget = target;
                            playerManager.setExactSeek(true);
                            playerManager.seekTo(target);
                        }
                        return;
                    }
                }
                if (!inSpan) {
                    pendingSkipSeekTarget = -1;
                }
            }

            // For clips with loop extension, directly set the absolute timeline
            // position instead of going through setPlayheadFraction (which clamps
            // to source trim bounds and can never reach the extension region).
            long displayTimeMs = position;
            long trimmedDurationMs = clip.getTrimmedDurationMs();
            if (clip.hasLoopExtension() && clip.getLoopMode() == Clip.LOOP_MODE_NORMAL
                    && playerManager.isGapless()) {
                // L1: the gapless engine's getCurrentPosition() ALREADY reports a position
                // that's continuous across the whole looped clip (loop-before start = 0, main
                // pass, loop-after end = getVisualDurationMs()) — see
                // MasterPlaybackEngine#getCurrentPositionInWindow(). So `position` here IS
                // visualPosMs already; the legacy reconstruction below
                // (loopBeforeMs + loopVisualOffsetMs + position) would DOUBLE-COUNT loopBeforeMs,
                // since loopVisualOffsetMs never advances in gapless mode (the poll-based wrap
                // block that increments it is bypassed — see the isAtEnd branch above).
                long visualPosMs = Math.min(position, clip.getVisualDurationMs());
                Timeline tl = project.getTimeline();
                long clipStartMs = 0;
                for (int i = 0; i < tl.getClipCount() && i < selectedClipIndex; i++) {
                    Clip c = tl.getClip(i);
                    clipStartMs += c.hasLoopExtension() ? c.getVisualDurationMs()
                            : c.getTrimmedDurationMs();
                }
                long timelineMs = clipStartMs + visualPosMs;
                FLog.d(TAG, "Loop playhead (gapless): pos=" + position + " vPos=" + visualPosMs
                        + " clipStart=" + clipStartMs + " tlMs=" + timelineMs);
                editorTimeline.setPlayheadPositionMs(timelineMs);
                displayTimeMs = visualPosMs;
            } else if (clip.hasLoopExtension()) {
                // Legacy (gapless flag OFF) looped-clip playhead: NORMAL and (now forward-tail)
                // PING_PONG both advance the playhead as loopBefore + accumulated-rep-offset +
                // the current forward position within the trimmed pass. (The old ping-pong
                // decoupled-wall-clock / backward-pass mapping was removed with the -1f reverse.)
                long visualPosMs = clip.getLoopBeforeMs() + loopVisualOffsetMs + position;
                long visDur = clip.getVisualDurationMs();
                if (visualPosMs > visDur) visualPosMs = visDur;
                // Find this clip's cumulative start on the timeline
                Timeline tl = project.getTimeline();
                long clipStartMs = 0;
                for (int i = 0; i < tl.getClipCount() && i < selectedClipIndex; i++) {
                    Clip c = tl.getClip(i);
                    clipStartMs += c.hasLoopExtension() ? c.getVisualDurationMs()
                            : c.getTrimmedDurationMs();
                }
                long timelineMs = clipStartMs + visualPosMs;
                FLog.d(TAG, "Loop playhead: pos=" + position + " vOff=" + loopVisualOffsetMs
                        + " vPos=" + visualPosMs + " clipStart=" + clipStartMs
                        + " tlMs=" + timelineMs);
                editorTimeline.setPlayheadPositionMs(timelineMs);
                displayTimeMs = visualPosMs;
            } else {
                float fraction = (float) absoluteMs / sourceDuration;
                fraction = Math.max(0f, Math.min(fraction, 1f));
                lastUserPlayheadFraction = fraction;
                editorTimeline.setPlayheadFraction(fraction);
                displayTimeMs = position;
            }
            updateCurrentTimeDisplay(displayTimeMs);
        }
    }

    @Nullable
    private Transition getTransitionAtSeam(int seam) {
        if (project == null || seam < 0) return null;
        Timeline timeline = project.getTimeline();
        if (seam >= timeline.getClipCount() - 1) return null;
        List<Transition> transitions = timeline.getTransitions();
        for (int i = transitions.size() - 1; i >= 0; i--) {
            Transition t = transitions.get(i);
            if (t.clipIndex == seam) return t;
        }
        return null;
    }

    private long getOutputPositionForCurrentPlayback(long relativePositionMs) {
        Timeline timeline = project.getTimeline();
        long output = 0;
        for (int i = 0; i < selectedClipIndex && i < timeline.getClipCount(); i++) {
            Clip c = timeline.getClip(i);
            output += (long) (c.getTrimmedDurationMs() / Math.max(0.01f, c.getSpeedMultiplier()));
            Transition t = getTransitionAtSeam(i);
            if (t != null) {
                output -= t.durationMs;
            }
        }
        Clip current = timeline.getClip(selectedClipIndex);
        if (current != null) {
            output += (long) (relativePositionMs / Math.max(0.01f, current.getSpeedMultiplier()));
        }
        return Math.max(0, output);
    }

    private void renderTransitionPreview(float progress, long currentPos) {
        if (transitionPreviewOverlay == null || project == null) return;
        Timeline timeline = project.getTimeline();
        if (transitionPlaybackSeam < 0 || transitionPlaybackSeam + 1 >= timeline.getClipCount()) {
            hideTransitionPreview();
            return;
        }
        Transition transition = getTransitionAtSeam(transitionPlaybackSeam);
        if (transition == null) {
            hideTransitionPreview();
            return;
        }
        Clip previous = timeline.getClip(transitionPlaybackSeam);
        Clip next = timeline.getClip(transitionPlaybackSeam + 1);
        if (previous == null || next == null) {
            hideTransitionPreview();
            return;
        }
        int color = transitionColor(transition);
        if (transition.isGlShader()) {
            transitionPreviewOverlay.setVisibility(View.GONE);
            if (playerView != null) playerView.setAlpha(1f);
            long sourceMs = previous.getInPointMs()
                    + Math.max(0L, Math.min(previous.getOutPointMs() - previous.getInPointMs(),
                    currentPos - transitionPlaybackStartPositionMs));
            Bitmap from = decodeTransitionFrame(previous, sourceMs, previewWidth(), previewHeight());
            Bitmap to = decodeTransitionFrame(next, transitionNextSourceMs(transition, next, progress),
                    previewWidth(), previewHeight());
            if (from != null && to != null && glTransitionPreviewView != null) {
                glTransitionPreviewView.render(from, to, transition, progress);
            } else {
                hideTransitionPreview();
            }
            return;
        }
        if (transition.type == Transition.Type.FADE_IN_FROM_BLACK || transition.type == Transition.Type.FADE_IN_FROM_WHITE) {
            transitionPreviewOverlay.renderColor(color, 1f - progress);
            // Veil drives the fade; keep the player opaque or it double-darkens.
            if (playerView != null) playerView.setAlpha(1f);
            return;
        }
        if (transition.type == Transition.Type.FADE_OUT_TO_BLACK || transition.type == Transition.Type.FADE_OUT_TO_WHITE) {
            transitionPreviewOverlay.renderColor(color, progress);
            if (playerView != null) playerView.setAlpha(1f);
            return;
        }
        if (transition.type == Transition.Type.CROSS_DISSOLVE) {
            transitionPreviewOverlay.renderColor(Color.TRANSPARENT, 0f);
            Bitmap frame = decodeTransitionFrame(next, transitionNextSourceMs(transition, next, progress),
                    previewWidth(), previewHeight());
            if (frame != null) {
                transitionPreviewOverlay.renderBitmap(frame, transition, progress);
            }
            if (playerView != null) playerView.setAlpha(1f - progress);
            return;
        }
        transitionPreviewOverlay.renderColor(Color.TRANSPARENT, 0f);
        Bitmap frame = decodeTransitionFrame(next, transitionNextSourceMs(transition, next, progress),
                previewWidth(), previewHeight());
        if (frame != null) {
            transitionPreviewOverlay.renderBitmap(frame, transition, progress);
        }
        if (playerView != null) playerView.setAlpha(1f);
    }

    /**
     * While scrubbing (paused) over the trailing transition window of a clip, blend
     * the outgoing clip toward the incoming clip in the preview window so the user
     * sees the transition resolve in real time — mirroring playback's transition
     * window detection but driven by the scrub seek position instead of the player.
     *
     * @param clip          the clip the playhead is currently on
     * @param segmentIndex  its index in the timeline
     * @param seekPosition  0-based position within the clip's trim (ms)
     */
    private void updateScrubTransitionPreview(@NonNull Clip clip, int segmentIndex, long seekPosition) {
        boolean slideOutgoing = clip.isGeneratedSlide();
        Transition seamTransition = getTransitionAtSeam(segmentIndex);
        if (seamTransition == null) {
            hideTransitionPreview();
            if (slideOutgoing) restoreSlideZOrder();
            return;
        }
        long clipDuration = Math.max(1L, clip.getTrimmedDurationMs());
        long transitionSourceDuration = Math.max(1L,
                Math.min(clipDuration, (long) (seamTransition.durationMs * clip.getSpeedMultiplier())));
        long startPosition = clipDuration - transitionSourceDuration;
        if (seekPosition < startPosition) {
            hideTransitionPreview();
            if (slideOutgoing) restoreSlideZOrder();
            return;
        }
        transitionPlaybackSeam = segmentIndex;
        transitionPlaybackStartPositionMs = startPosition;
        transitionPlaybackDurationMs = transitionSourceDuration;
        float progress = (seekPosition - startPosition) / (float) transitionSourceDuration;
        progress = Math.max(0f, Math.min(1f, progress));
        if (slideOutgoing) {
            // Outgoing is a slide rendered in the WebView (not playerView). Lift the
            // transition layers above the WebView so the incoming frame composites
            // over the held slide frame for a true crossfade instead of being hidden.
            if (transitionPreviewOverlay != null) transitionPreviewOverlay.bringToFront();
            if (glTransitionPreviewView != null) glTransitionPreviewView.bringToFront();
        }
        renderTransitionPreview(progress, seekPosition);
    }

    /** Restore the slide WebView (and editable overlays) above the transition layers. */
    private void restoreSlideZOrder() {
        if (slidePreview != null && slidePreview.getVisibility() == View.VISIBLE) {
            slidePreview.bringToFront();
            if (overlayLayer != null) overlayLayer.bringToFront();
        }
    }

    private void hideTransitionPreview() {
        transitionPlaybackActive = false;
        if (transitionPreviewOverlay != null) {
            transitionPreviewOverlay.setVisibility(View.GONE);
        }
        if (glTransitionPreviewView != null) glTransitionPreviewView.clear();
        if (playerView != null) playerView.setAlpha(1f);
        clearTransitionFrameCache();
    }

    private long transitionNextSourceMs(@NonNull Transition transition, @NonNull Clip next, float progress) {
        long transitionSourceDuration = Math.max(1L,
                (long) (transition.durationMs * Math.max(0.01f, next.getSpeedMultiplier())));
        long local = (long) (transitionSourceDuration * progress);
        return Math.max(next.getInPointMs(), Math.min(next.getOutPointMs(), next.getInPointMs() + local));
    }

    // Decoded transition frames keyed by uri@WxH. Scrubbing over a transition asks
    // for the same frame every tick; decoding once (the keyframe is identical across
    // the short window anyway) keeps the scrub smooth instead of snagging on each
    // getFrameAtTime/decodeStream call. Cleared in hideTransitionPreview().
    // Uses LruCache (was HashMap) for bounded memory: evicted bitmaps are recycled
    // automatically via entryRemoved. Capacity = 10 frames (enough for the visible
    // transition window + a few cached from adjacent clips).
    private final android.util.LruCache<String, Bitmap> transitionFrameCache =
            new android.util.LruCache<String, Bitmap>(10) {
                @Override
                protected void entryRemoved(boolean evicted, String key,
                        Bitmap oldValue, Bitmap newValue) {
                    if (oldValue != null && !oldValue.isRecycled()) oldValue.recycle();
                }
            };
    // Keys that failed to decode (e.g. broken SAF link) — don't retry the slow,
    // failing setDataSource/getFrameAtTime on every scrub tick.
    private final java.util.HashSet<String> transitionFrameFailed = new java.util.HashSet<>();

    @Nullable
    private Bitmap cachedTransitionFrame(@NonNull String key) {
        return transitionFrameCache.get(key);
    }

    private void clearTransitionFrameCache() {
        transitionFrameFailed.clear();
        transitionFrameCache.evictAll();
    }

    @Nullable
    private Bitmap decodeTransitionFrame(@NonNull Clip clip, long sourceMs, int outW, int outH) {
        if (clip.isImageClip()) {
            return decodeImageFrame(clip.getSourceUri(), outW, outH);
        }
        try {
            Uri playbackUri = resolvePlaybackUri(clip.getSourceUri());
            String uriString = playbackUri.toString();
            String key = uriString + "@" + outW + "x" + outH;
            Bitmap cached = cachedTransitionFrame(key);
            if (cached != null) return cached;
            if (transitionFrameFailed.contains(key)) return null;
            if (transitionRetriever == null || !uriString.equals(transitionRetrieverUri)) {
                releaseTransitionRetriever();
                transitionRetriever = new MediaMetadataRetriever();
                // file:// URIs must use the path form — setDataSource(Context, fileUri)
                // fails with status 0x80000000 (which killed the decode and, because
                // nothing cached, retried the slow failing call on every scrub tick).
                if ("file".equals(playbackUri.getScheme()) && playbackUri.getPath() != null) {
                    transitionRetriever.setDataSource(playbackUri.getPath());
                } else {
                    transitionRetriever.setDataSource(this, playbackUri);
                }
                transitionRetrieverUri = uriString;
            }
            Bitmap frame = transitionRetriever.getFrameAtTime(sourceMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = transitionRetriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) return null;
            Bitmap scaled = scalePreservingAspect(frame, outW, outH);
            if (scaled != frame) frame.recycle();
            transitionFrameCache.put(key, scaled);
            return scaled;
        } catch (Exception e) {
            transitionFrameFailed.add(resolvePlaybackUri(clip.getSourceUri()).toString() + "@" + outW + "x" + outH);
            FLog.w(TAG, "Failed to decode transition preview frame", e);
            return null;
        }
    }

    @Nullable
    private Bitmap decodeImageFrame(@NonNull Uri uri, int outW, int outH) {
        String key = uri.toString() + "@" + outW + "x" + outH;
        Bitmap cached = cachedTransitionFrame(key);
        if (cached != null) return cached;
        if (transitionFrameFailed.contains(key)) return null;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            Bitmap source = BitmapFactory.decodeStream(in);
            if (source == null) return null;
            Bitmap scaled = scalePreservingAspect(source, outW, outH);
            if (scaled != source) source.recycle();
            transitionFrameCache.put(key, scaled);
            return scaled;
        } catch (Exception e) {
            transitionFrameFailed.add(key);
            FLog.w(TAG, "Failed to decode image transition preview frame", e);
            return null;
        }
    }

    /**
     * Downscale a decoded frame to fit within {@code maxW}×{@code maxH} while preserving its
     * native aspect ratio, so the transition overlay letterboxes the frame the same way the
     * player fits the clip on the canvas — instead of stretching a wide clip to fill a tall one.
     */
    @NonNull
    private Bitmap scalePreservingAspect(@NonNull Bitmap src, int maxW, int maxH) {
        int sw = Math.max(1, src.getWidth());
        int sh = Math.max(1, src.getHeight());
        float scale = Math.min(maxW / (float) sw, maxH / (float) sh);
        int dw = Math.max(1, Math.round(sw * scale));
        int dh = Math.max(1, Math.round(sh * scale));
        if (dw == sw && dh == sh) return src;
        return Bitmap.createScaledBitmap(src, dw, dh, true);
    }

    private int transitionColor(@NonNull Transition transition) {
        if (transition.type == Transition.Type.FADE_IN_FROM_WHITE
                || transition.type == Transition.Type.FADE_OUT_TO_WHITE) {
            return Color.WHITE;
        }
        return Color.BLACK;
    }

    private int previewWidth() {
        return Math.max(1, playerContainer != null ? playerContainer.getWidth() : 1080);
    }

    private int previewHeight() {
        return Math.max(1, playerContainer != null ? playerContainer.getHeight() : 1920);
    }

    private void releaseTransitionRetriever() {
        if (transitionRetriever != null) {
            try {
                transitionRetriever.release();
            } catch (Exception ignored) {
            }
            transitionRetriever = null;
        }
        transitionRetrieverUri = null;
        if (transitionLastBitmap != null && !transitionLastBitmap.isRecycled()) {
            transitionLastBitmap.recycle();
        }
        transitionLastBitmap = null;
        transitionLastSourceMs = -1;
    }

    /**
     * Auto-advance to a new segment during playback, handling both image and video clips.
     *
     * @param nextIndex     the segment index to advance to
     * @param autoPlay      whether to start playback immediately
     */
    private void advanceToSegment(int nextIndex, boolean autoPlay) {
        loopVisualOffsetMs = 0;
        loopStillExtensionStartMs = -1;
        loopRestartPending = false;
        FLog.d(TAG, "Auto-advancing to segment " + nextIndex);

        // Deactivate crop overlay when switching segments
        if (inCropMode) {
            exitCropMode(false);
        } else if (cropOverlay != null && cropOverlay.isActive()) {
            cropOverlay.deactivate();
        }

        Timeline timeline = project.getTimeline();
        selectedClipIndex = nextIndex;
        Clip nextClip = getSelectedClip();
        editorTimeline.setTimeline(timeline, selectedClipIndex);
        editorTimeline.setTrimFromClip(nextClip);

        // Sync toolbar to new segment
        updateVolumeUI(nextClip.getVolumeLevel(), nextClip.isAudioMuted());
        updateOpacityUI();
        updateSpeedUI(nextClip.getSpeedMultiplier());
        updateRotateUI(nextClip.getRotationDegrees());
        updateFlipUI(nextClip.isFlipHorizontal(), nextClip.isFlipVertical());
        updateCropUI(nextClip.getCropPreset());
        updateFilterUI(nextClip);
        applyPreviewColorGrade(nextClip);

        if (nextClip.isImageClip()) {
            // Image clip: show image preview and start timer
            showImagePreview(nextClip.getSourceUri());
            if (autoPlay) {
                startImagePlayback(0);
            }
        } else {
            // Video clip: load and play
            hideImagePreview();
            loadClipForPlayback(nextClip);
            playerManager.setVolume(nextClip.isAudioMuted() ? 0f : nextClip.getVolumeLevel());
            playerManager.setPlaybackSpeed(nextClip.getSpeedMultiplier(), nextClip.isPitchCompensationEnabled());
            updatePreviewTransforms();
            if (autoPlay) {
                playerManager.play();
            }
        }

        float startFraction = (float) nextClip.getInPointMs() / nextClip.getSourceDurationMs();
        FLog.d(TAG, "advanceToSegment: loopMode=" + nextClip.getLoopMode()
                + " hasLoopExt=" + nextClip.hasLoopExtension()
                + " visualDur=" + (nextClip.hasLoopExtension() ? nextClip.getVisualDurationMs() : "n/a")
                + " inP=" + nextClip.getInPointMs() + " outP=" + nextClip.getOutPointMs()
                + " srcDur=" + nextClip.getSourceDurationMs()
                + " startFrac=" + String.format("%.4f", startFraction));
        editorTimeline.setPlayheadFraction(startFraction);
        updateCurrentTimeDisplay(0);
    }

    // ── Export helpers ────────────────────────────────────────────────

    /**
     * Show an export confirmation bottom sheet with project info.
     * On confirm, starts the export via the foreground service.
     */
    private void showExportConfirmation() {
        if (isExportRunning()) {
            Toast.makeText(this, R.string.faditor_export_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }

        Timeline tl = project.getTimeline();
        int clipCount = tl.getClipCount();
        long totalDurationMs = totalEffectiveMs();
        String durationStr = TimeFormatter.formatAuto(totalDurationMs);
        boolean hasAudio = tl.hasAudioClips();
        String audioInfo = hasAudio
                ? " • " + tl.getAudioClips().size() + " audio"
                : "";

        String helperText = getString(R.string.faditor_export_confirm_helper,
                durationStr, clipCount, audioInfo);

        try {
            int pad = (int) (20 * getResources().getDisplayMetrics().density);
            android.widget.LinearLayout root = new android.widget.LinearLayout(this);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            root.setPadding(pad, pad / 2, pad, 0);

            TextView helper = new TextView(this);
            helper.setText(helperText);
            helper.setTextColor(0xFFBBBBBB);
            helper.setTextSize(13);
            root.addView(helper);

            // ── Editable output file name (pre-filled with the default name) ──
            TextView fileNameLabel = new TextView(this);
            fileNameLabel.setText(R.string.faditor_export_filename_label);
            fileNameLabel.setTextColor(0xFF888888);
            fileNameLabel.setTextSize(11);
            android.widget.LinearLayout.LayoutParams fnLabelLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            fnLabelLp.topMargin = pad / 2;
            fileNameLabel.setLayoutParams(fnLabelLp);
            root.addView(fileNameLabel);

            final String defaultExportBaseName = "Faditor_"
                    + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                            .format(new java.util.Date());

            final android.widget.EditText fileNameInput = new android.widget.EditText(this);
            fileNameInput.setText(defaultExportBaseName);
            fileNameInput.setSelectAllOnFocus(true);
            fileNameInput.setSingleLine(true);
            fileNameInput.setTextColor(0xFFFFFFFF);
            fileNameInput.setTextSize(14);
            fileNameInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            android.widget.LinearLayout.LayoutParams fnInputLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            fnInputLp.topMargin = (int) (4 * getResources().getDisplayMetrics().density);
            fileNameInput.setLayoutParams(fnInputLp);
            root.addView(fileNameInput);

            final android.widget.CheckBox cleanAudio = new android.widget.CheckBox(this);
            cleanAudio.setText(R.string.faditor_clean_audio_label);
            cleanAudio.setTextColor(0xFFFFFFFF);
            cleanAudio.setChecked(project.getExportSettings().isCleanAudio());
            android.widget.LinearLayout.LayoutParams clp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.topMargin = pad / 2;
            cleanAudio.setLayoutParams(clp);
            root.addView(cleanAudio);

            TextView cleanDesc = new TextView(this);
            cleanDesc.setText(R.string.faditor_clean_audio_desc);
            cleanDesc.setTextColor(0xFF888888);
            cleanDesc.setTextSize(11);
            root.addView(cleanDesc);

            // ── Resolution + Quality pickers (persisted on the project's ExportSettings;
            //    defaults = Original/High = the legacy byte-identical export path) ──
            final com.fadcam.ui.faditor.model.ExportSettings.Resolution[] resValues = {
                    com.fadcam.ui.faditor.model.ExportSettings.Resolution.ORIGINAL,
                    com.fadcam.ui.faditor.model.ExportSettings.Resolution.FHD_1080P,
                    com.fadcam.ui.faditor.model.ExportSettings.Resolution.HD_720P,
                    com.fadcam.ui.faditor.model.ExportSettings.Resolution.SD_480P};
            final String[] resLabels = {"Original", "1080p", "720p", "480p"};
            final com.fadcam.ui.faditor.model.ExportSettings.Quality[] qualValues = {
                    com.fadcam.ui.faditor.model.ExportSettings.Quality.HIGH,
                    com.fadcam.ui.faditor.model.ExportSettings.Quality.MEDIUM,
                    com.fadcam.ui.faditor.model.ExportSettings.Quality.LOW};
            final String[] qualLabels = {"High", "Medium", "Low"};

            final android.widget.Spinner resSpinner =
                    buildExportSettingSpinner(root, "Resolution", resLabels,
                            java.util.Arrays.asList(resValues)
                                    .indexOf(project.getExportSettings().getResolution()), pad);
            final android.widget.Spinner qualSpinner =
                    buildExportSettingSpinner(root, "Quality", qualLabels,
                            java.util.Arrays.asList(qualValues)
                                    .indexOf(project.getExportSettings().getQuality()), pad);

            // ── Audio-only export (mux the composed audio mix to .m4a, no video).
            //    Not persisted on ExportSettings: an audio pull is a one-off act,
            //    defaulting back to video export next time is the safe behavior. ──
            final android.widget.CheckBox audioOnlyBox = new android.widget.CheckBox(this);
            audioOnlyBox.setText("Export audio only (.m4a)");
            audioOnlyBox.setTextColor(0xFFFFFFFF);
            android.widget.LinearLayout.LayoutParams aoLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            aoLp.topMargin = pad / 2;
            audioOnlyBox.setLayoutParams(aoLp);
            root.addView(audioOnlyBox);

            TextView audioOnlyDesc = new TextView(this);
            audioOnlyDesc.setText(
                    "Mixes clip audio + music into one audio file — no video track");
            audioOnlyDesc.setTextColor(0xFF888888);
            audioOnlyDesc.setTextSize(11);
            root.addView(audioOnlyDesc);

            // Resolution/Quality only shape the video encode — grey them out while
            // audio-only is checked so the dialog doesn't promise a video setting.
            audioOnlyBox.setOnCheckedChangeListener((b, checked) -> {
                resSpinner.setEnabled(!checked);
                qualSpinner.setEnabled(!checked);
                resSpinner.setAlpha(checked ? 0.4f : 1f);
                qualSpinner.setAlpha(checked ? 0.4f : 1f);
            });

            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.faditor_export_confirm_title)
                    .setView(root)
                    .setPositiveButton(R.string.faditor_export_confirm_action, (d, w) -> {
                        project.getExportSettings().setCleanAudio(cleanAudio.isChecked());
                        project.getExportSettings().setResolution(
                                resValues[Math.max(0, resSpinner.getSelectedItemPosition())]);
                        project.getExportSettings().setQuality(
                                qualValues[Math.max(0, qualSpinner.getSelectedItemPosition())]);
                        project.getExportSettings().setOutputFileName(
                                sanitizeExportFileName(
                                        fileNameInput.getText().toString(),
                                        defaultExportBaseName));
                        scheduleAutoSave();
                        startExportViaService(audioOnlyBox.isChecked());
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Exception e) {
            FLog.e(TAG, "Failed to show export confirmation, starting directly", e);
            startExportViaService();
        }
    }

    /**
     * Sanitize a user-entered export file base name (no extension) for use on
     * the file system. Strips characters invalid in Android/FAT/NTFS file
     * names ({@code / \ : * ? " < > |}), trims whitespace, and falls back to
     * {@code fallback} if the result is empty. The standard recording
     * extension is appended by {@code ExportManager}, not here.
     *
     * @param rawInput the raw text from the file name field
     * @param fallback the default base name to use if the input is blank
     *                 after sanitization (i.e. the user left it untouched or
     *                 cleared it)
     * @return a sanitized, non-empty base name
     */
    /**
     * Add a labeled dropdown row to the export-confirmation dialog and return its
     * Spinner. Kept programmatic to match the rest of the dialog's construction.
     */
    @NonNull
    private android.widget.Spinner buildExportSettingSpinner(
            @NonNull android.widget.LinearLayout root, @NonNull String label,
            @NonNull String[] options, int initialIndex, int pad) {
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(0xFF888888);
        labelView.setTextSize(11);
        android.widget.LinearLayout.LayoutParams labelLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = pad / 2;
        labelView.setLayoutParams(labelLp);
        root.addView(labelView);

        android.widget.Spinner spinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, options) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView,
                                @NonNull android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) ((TextView) v).setTextColor(0xFFFFFFFF);
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, initialIndex));
        root.addView(spinner);
        return spinner;
    }

    @NonNull
    private static String sanitizeExportFileName(@Nullable String rawInput, @NonNull String fallback) {
        if (rawInput == null) return fallback;
        String cleaned = rawInput.trim().replaceAll("[/\\\\:*?\"<>|]", "").trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    /**
     * Start the export via the foreground service.
     * The service handles the Transformer lifecycle and survives Activity destruction.
     */
    /**
     * Free as much editor-held memory as possible right before an in-process
     * export: drop the (potentially huge) in-memory undo snapshots, recycle
     * filmstrip thumbnails, and release the preview player's hardware decoder.
     * This directly attacks the OOM / codec-starvation that killed exports of
     * large projects. The preview is re-acquired in {@link #hideExportProgress()}.
     */
    private void prepareMemoryForExport() {
        if (undoManager != null) undoManager.releaseSnapshotMemory();
        if (editorTimeline != null) editorTimeline.releaseThumbnailMemory();
        if (playerManager != null) playerManager.releaseForExport();
        previewReleasedForExport = true;
        System.gc();
    }

    /**
     * True while the preview player has been released for an export and not yet
     * reacquired. Guards {@link FaditorPlayerManager#reacquireAfterExport()} against
     * double-rebuilds now that BOTH minimize-to-background and export completion can
     * try to restore the preview.
     */
    private boolean previewReleasedForExport = false;

    private void reacquirePreviewIfReleased() {
        if (previewReleasedForExport && playerManager != null) {
            playerManager.reacquireAfterExport();
            previewReleasedForExport = false;
        }
    }

    /**
     * Minimize a RUNNING export: hide the full-screen overlay and return to a live,
     * editable editor while the foreground service keeps exporting (the service owns
     * an edit-immune snapshot of the project). The thin progress stripe stays visible;
     * tapping it re-opens this overlay.
     */
    private void minimizeExportToBackground() {
        if (exportProgressOverlay != null) {
            exportProgressOverlay.animate().alpha(0f).setDuration(200).withEndAction(() ->
                    exportProgressOverlay.setVisibility(View.GONE)).start();
        }
        reacquirePreviewIfReleased();
        Toast.makeText(this,
                "Exporting in background — edits won't affect this export",
                Toast.LENGTH_LONG).show();
    }

    /** Re-open the export overlay from the minimized state, keeping live progress. */
    private void reshowExportProgress() {
        if (exportProgressOverlay == null
                || exportProgressOverlay.getVisibility() == View.VISIBLE) {
            return;
        }
        exportProgressOverlay.setVisibility(View.VISIBLE);
        exportProgressOverlay.setAlpha(0f);
        exportProgressOverlay.animate().alpha(1f).setDuration(200).start();
    }

    private void startExportViaService() {
        startExportViaService(false);
    }

    private void startExportViaService(boolean audioOnly) {
        if (isExportRunning()) {
            Toast.makeText(this, R.string.faditor_export_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }

        // Pre-flight check for missing media
        if (countMissingMedia() > 0) {
            Toast.makeText(this,
                    "Cannot export: " + countMissingMedia() + " media file(s) are missing. Please relink first.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        startOutOfProcessExport(audioOnly);
    }

    /** Actually start the actual export start (called after user confirms) */
    private void proceedWithExport() {
        startOutOfProcessExport(false);
    }

    /**
     * Serialize the current project to a per-job snapshot file for the :export process
     * (an edit-immune deep snapshot taken at export-tap time). Stale snapshots from
     * jobs that never got consumed are swept here. Returns null on failure.
     */
    @Nullable
    private String writeExportSnapshotFile() {
        if (project == null) return null;
        try {
            com.fadcam.ui.faditor.project.ProjectStorage storage =
                    new com.fadcam.ui.faditor.project.ProjectStorage(this);
            String json = storage.toJson(project);
            java.io.File dir = new java.io.File(getFilesDir(), "faditor");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            java.io.File[] stale = dir.listFiles(
                    (d, name) -> name.startsWith("export_snapshot_") && name.endsWith(".json"));
            if (stale != null) {
                for (java.io.File f : stale) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            java.io.File out = new java.io.File(dir,
                    "export_snapshot_" + System.currentTimeMillis() + ".json");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            FLog.e(TAG, "writeExportSnapshotFile failed", e);
            return null;
        }
    }

    /**
     * Hand the export to the out-of-process ExportService: free editor memory + the
     * preview codec first (the exporter has its own heap, but releasing the preview
     * codec still matters — hardware codec instances are a device-global resource),
     * snapshot the project to a file, and start the foreground service with its path.
     */
    private void startOutOfProcessExport(boolean audioOnly) {
        prepareMemoryForExport();

        String snapshotPath = writeExportSnapshotFile();
        if (snapshotPath == null) {
            Toast.makeText(this,
                    getString(R.string.faditor_export_error, "could not snapshot the project"),
                    Toast.LENGTH_LONG).show();
            return;
        }

        android.content.Intent serviceIntent =
                new android.content.Intent(this, ExportService.class);
        serviceIntent.setAction(ExportService.ACTION_START_EXPORT);
        serviceIntent.putExtra(ExportService.EXTRA_PROJECT_SNAPSHOT_PATH, snapshotPath);
        serviceIntent.putExtra(ExportService.EXTRA_AUDIO_ONLY, audioOnly);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        exportStartedLocallyAtMs = System.currentTimeMillis();

        // Show export info on the overlay (progress arrives via exportEventsReceiver).
        showExportInfoOnScreen();
    }

    /**
     * Check if an export is currently running. The service lives in the :export
     * process, so the truth is its ongoing foreground notification (plus a local
     * flag bridging the tap→first-broadcast window).
     */
    private boolean isExportRunning() {
        if (exportStartedLocallyAtMs > 0
                && System.currentTimeMillis() - exportStartedLocallyAtMs < EXPORT_START_GRACE_MS) {
            return true;
        }
        if (ExportService.isRunning(this)) {
            return true;
        }
        if (exportManager != null && exportManager.isExporting()) {
            return true;
        }
        return false;
    }

    /**
     * Show export info (duration, clips, codec) on the export progress screen.
     */
    private void showExportInfoOnScreen() {
        if (exportInfoText == null || project == null) return;

        Timeline tl = project.getTimeline();
        int clipCount = tl.getClipCount();
        long totalDurationMs = totalEffectiveMs();
        String durationStr = TimeFormatter.formatAuto(totalDurationMs);
        boolean hasAudio = tl.hasAudioClips();
        String audioInfo = hasAudio
                ? " • " + tl.getAudioClips().size() + " audio"
                : "";

        exportInfoText.setText(getString(R.string.faditor_export_info,
                durationStr, clipCount, audioInfo));
        exportInfoText.setVisibility(View.VISIBLE);
    }

    private void showExportProgress() {
        if (exportProgressOverlay == null) return;

        // Reset to initial exporting state (indeterminate until first progress poll)
        if (exportProgressPercent != null) exportProgressPercent.setText("–");
        if (exportProgressBar != null) {
            exportProgressBar.setIndeterminate(true);
        }
        if (exportProgressText != null) {
            exportProgressText.setText(R.string.faditor_exporting);
            exportProgressText.setTextColor(0xFFAAAAAA);
        }
        if (exportEtaText != null) exportEtaText.setVisibility(View.GONE);
        if (exportStatusIcon != null) exportStatusIcon.setText("movie");
        if (exportTitle != null) exportTitle.setText(R.string.faditor_exporting);
        if (exportBtnDone != null) exportBtnDone.setVisibility(View.INVISIBLE);

        // Reset Back button visibility
        View backBtn = findViewById(R.id.export_btn_back);
        if (backBtn != null) backBtn.setVisibility(View.VISIBLE);

        View cancelBtn = findViewById(R.id.btn_cancel_export);
        if (cancelBtn != null) cancelBtn.setVisibility(View.VISIBLE);

        exportProgressOverlay.setVisibility(View.VISIBLE);
        exportProgressOverlay.setAlpha(0f);
        exportProgressOverlay.animate().alpha(1f).setDuration(200).start();

        showExportProgressStripe();
    }

    private void hideExportProgress() {
        if (exportProgressOverlay != null) {
            exportProgressOverlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                exportProgressOverlay.setVisibility(View.GONE);
            }).start();
        }
        // Restore the preview player released for the export (see prepareMemoryForExport).
        reacquirePreviewIfReleased();

        hideExportProgressStripe();
    }

    /**
     * Shows the thin striped progress line at the top of the editor and starts its
     * scrolling animation. Reuses the same export lifecycle as the fullscreen
     * progress overlay (see {@link #exportEventsReceiver}) so the stripe stays in
     * sync with real export progress with no separate transport.
     */
    private void showExportProgressStripe() {
        if (exportProgressStripe == null) return;
        exportProgressStripe.setProgress(0f);
        exportProgressStripe.setVisibility(View.VISIBLE);
        exportProgressStripe.startAnimating();
    }

    /** Hides the stripe and stops its animator so it doesn't keep drawing in the background. */
    private void hideExportProgressStripe() {
        if (exportProgressStripe == null) return;
        exportProgressStripe.stopAnimating();
        exportProgressStripe.setVisibility(View.GONE);
    }

    // ── Navigation ───────────────────────────────────────────────────

    /**
     * Show a confirmation dialog before closing the editor.
     * Uses a centered dialog (not bottom sheet) because the close button
     * is in the top bar — one-handed users shouldn't have to reach the bottom.
     */
    private void showCloseConfirmation() {
        try {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.faditor_close_confirm_title)
                    .setMessage(R.string.faditor_close_confirm_helper)
                    .setPositiveButton(R.string.faditor_close_confirm_action, (d, w) -> handleClose())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Exception e) {
            FLog.e(TAG, "Failed to show close confirmation dialog", e);
            handleClose();
        }
    }

    private void handleClose() {
        saveProjectNow();
        finish();
    }

    // ── Persistence ──────────────────────────────────────────────────

    /**
     * Schedule a debounced auto-save (resets timer on each call).
     */
    // ── Undo / Redo ───────────────────────────────────────────────

    /**
     * Perform undo: reverts the last edit action and refreshes all relevant UI.
     */
    private void performUndo() {
        if (!undoManager.canUndo()) return;
        undoManager.undo();
        refreshEditorAfterUndoRedo();
        scheduleAutoSave();
    }

    /**
     * Perform redo: re-applies a previously undone edit action and refreshes UI.
     */
    private void performRedo() {
        if (!undoManager.canRedo()) return;
        undoManager.redo();
        refreshEditorAfterUndoRedo();
        scheduleAutoSave();
    }

    /**
     * Long-press popup on the undo/redo buttons: shows the full edit history as a
     * single vertical list with redo entries above a "current position" centerline
     * and undo entries below. Tapping an entry jumps straight to that point in
     * history (replaying the necessary number of undo()/redo() calls), then does
     * ONE UI refresh + save at the end (mirrors performUndo()/performRedo()).
     */
    private void showUndoRedoHistoryPopup(@NonNull View anchor) {
        java.util.List<com.fadcam.ui.faditor.undo.UndoManager.HistoryEntry> redoEntries =
                undoManager.getRedoHistory(); // index 0 = nearest redo (+1)
        java.util.List<com.fadcam.ui.faditor.undo.UndoManager.HistoryEntry> undoEntriesOldestFirst =
                undoManager.getUndoHistory(); // oldest-first; last = nearest undo (-1)

        if (redoEntries.isEmpty() && undoEntriesOldestFirst.isEmpty()) {
            return; // Nothing to show
        }

        float dp = getResources().getDisplayMetrics().density;
        int padH = (int) (16 * dp);
        int padV = (int) (10 * dp);

        android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        list.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));

        // Marker for the row that must be centered/visible on open.
        final View[] centerRowHolder = new View[1];
        // Popup reference is needed inside row click handlers to dismiss on tap;
        // created after the content view, so use a holder filled in once built.
        final android.widget.PopupWindow[] popupHolder = new android.widget.PopupWindow[1];
        // Guards against the spring-out animation being triggered twice (e.g. a row tap
        // that also races with an outside-touch auto-dismiss callback).
        final boolean[] dismissAnimStarted = new boolean[1];
        // Filled in below once `card` (the animated content root) exists; row taps call this
        // instead of popup.dismiss() directly so the "shrink back into the button" animation
        // gets to play. The actual jump (undo/redo) runs immediately/synchronously — only the
        // popup's own visual dismissal is deferred behind the short reverse animation.
        final Runnable[] animateOutAndDismiss = new Runnable[1];

        // ── Redo rows (top), numbered +N .. +1 top-to-bottom ──
        for (int i = redoEntries.size() - 1; i >= 0; i--) {
            com.fadcam.ui.faditor.undo.UndoManager.HistoryEntry entry = redoEntries.get(i);
            int stepsForward = i + 1; // how many redo() calls to reach this entry
            String label = "+" + stepsForward;
            list.addView(buildHistoryRow(label, entry.getDescription(), false, () -> {
                jumpUndoRedoBy(stepsForward, true);
                if (animateOutAndDismiss[0] != null) animateOutAndDismiss[0].run();
                else if (popupHolder[0] != null) popupHolder[0].dismiss();
            }));
        }

        // ── Centerline (current position) ──
        View centerRow = buildCenterlineRow();
        centerRowHolder[0] = centerRow;
        list.addView(centerRow);

        // ── Undo rows (bottom), numbered -1 .. -N top-to-bottom ──
        // undoEntriesOldestFirst is oldest-first; nearest undo (-1) is the LAST element.
        for (int i = undoEntriesOldestFirst.size() - 1; i >= 0; i--) {
            com.fadcam.ui.faditor.undo.UndoManager.HistoryEntry entry = undoEntriesOldestFirst.get(i);
            int stepsBack = undoEntriesOldestFirst.size() - i; // how many undo() calls to reach this entry
            String label = "-" + stepsBack;
            list.addView(buildHistoryRow(label, entry.getDescription(), true, () -> {
                jumpUndoRedoBy(stepsBack, false);
                if (animateOutAndDismiss[0] != null) animateOutAndDismiss[0].run();
                else if (popupHolder[0] != null) popupHolder[0].dismiss();
            }));
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(list, new android.widget.ScrollView.LayoutParams(
                android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                android.widget.ScrollView.LayoutParams.WRAP_CONTENT));

        // Card container with dark editor styling (rounded corners + subtle border).
        android.widget.FrameLayout card = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFF1A1A2E);
        cardBg.setCornerRadius(12 * dp);
        cardBg.setStroke((int) (1 * dp), 0xFF444444);
        card.setBackground(cardBg);
        card.setPadding(padH / 2, padV / 2, padH / 2, padV / 2);
        card.addView(scroll, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        int maxHeightPx = (int) (320 * dp);
        int widthPx = (int) (260 * dp);

        final android.widget.PopupWindow popup = new android.widget.PopupWindow(
                card, widthPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(8 * dp);
        popupHolder[0] = popup;

        // ── Spring-from-button pop-in / shrink-back-out animation ──
        // `card` is the whole visible content root; it's positioned at a FIXED top-left
        // screen offset by showAtLocation() below (loc[0] - widthPx/2 + anchor.width/2,
        // loc[1] - maxHeightPx - 16dp), regardless of its actual wrap-content height, so
        // the pivot can be computed from that fixed offset without waiting for layout.
        // Placing the pivot at the anchor button's on-screen center (translated into
        // card-local coordinates) makes the scale transform originate from the button,
        // so the popup visually springs out of it and shrinks back into it.
        final long POP_IN_MS = 200L;
        final long POP_OUT_MS = 150L;
        final float START_SCALE = 0.3f;
        int[] anchorLoc = new int[2];
        anchor.getLocationOnScreen(anchorLoc);
        float cardScreenLeft = anchorLoc[0] - (widthPx / 2f) + (anchor.getWidth() / 2f);
        float cardScreenTop = anchorLoc[1] - maxHeightPx - (16f * dp);
        float pivotX = (anchorLoc[0] + anchor.getWidth() / 2f) - cardScreenLeft;
        float pivotY = (anchorLoc[1] + anchor.getHeight() / 2f) - cardScreenTop;
        card.setPivotX(pivotX);
        card.setPivotY(pivotY);
        card.setScaleX(START_SCALE);
        card.setScaleY(START_SCALE);
        card.setAlpha(0f);

        // Guards double-triggering the reverse animation from two dismiss paths
        // (row tap + outside-touch/system dismiss racing each other).
        animateOutAndDismiss[0] = () -> {
            if (dismissAnimStarted[0]) return;
            dismissAnimStarted[0] = true;
            android.animation.ObjectAnimator outX = android.animation.ObjectAnimator.ofFloat(
                    card, View.SCALE_X, card.getScaleX(), START_SCALE);
            android.animation.ObjectAnimator outY = android.animation.ObjectAnimator.ofFloat(
                    card, View.SCALE_Y, card.getScaleY(), START_SCALE);
            android.animation.ObjectAnimator outA = android.animation.ObjectAnimator.ofFloat(
                    card, View.ALPHA, card.getAlpha(), 0f);
            android.animation.AnimatorSet outSet = new android.animation.AnimatorSet();
            outSet.playTogether(outX, outY, outA);
            outSet.setDuration(POP_OUT_MS);
            outSet.setInterpolator(new android.view.animation.AccelerateInterpolator());
            outSet.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (popupHolder[0] != null) popupHolder[0].dismiss();
                }
            });
            outSet.start();
        };
        // Outside touch would otherwise call PopupWindow's own dismiss() directly, which
        // removes the window instantly with no chance to animate it out. Intercept it via
        // ACTION_OUTSIDE (delivered here because setOutsideTouchable(true)): consuming it
        // (return true → suppress the default dismiss) and routing through the same
        // animate-out-then-dismiss path as a row tap keeps the shrink-back animation
        // consistent regardless of how the popup is closed.
        // (System back-press still dismisses instantly — PopupWindow handles that key event
        // internally in its decor view with no public pre-dismiss hook to intercept; a minor,
        // accepted gap for this polish-level animation.)
        popup.setTouchInterceptor((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_OUTSIDE) {
                animateOutAndDismiss[0].run();
                return true;
            }
            return false;
        });

        // Cap the scroll view's height so long histories (up to 50 entries) scroll
        // rather than overflowing off-screen.
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        if (scroll.getHeight() > maxHeightPx) {
                            android.view.ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                            lp.height = maxHeightPx;
                            scroll.setLayoutParams(lp);
                        }
                        // Auto-scroll so the centerline row is visible.
                        if (centerRowHolder[0] != null) {
                            scroll.post(() -> {
                                int targetY = centerRowHolder[0].getTop()
                                        - (scroll.getHeight() / 2) + (centerRowHolder[0].getHeight() / 2);
                                scroll.smoothScrollTo(0, Math.max(0, targetY));
                            });
                        }
                        // Pop-in now that the card has its real (possibly height-capped) size.
                        android.animation.ObjectAnimator inX = android.animation.ObjectAnimator.ofFloat(
                                card, View.SCALE_X, START_SCALE, 1f);
                        android.animation.ObjectAnimator inY = android.animation.ObjectAnimator.ofFloat(
                                card, View.SCALE_Y, START_SCALE, 1f);
                        android.animation.ObjectAnimator inA = android.animation.ObjectAnimator.ofFloat(
                                card, View.ALPHA, 0f, 1f);
                        android.animation.AnimatorSet inSet = new android.animation.AnimatorSet();
                        inSet.playTogether(inX, inY, inA);
                        inSet.setDuration(POP_IN_MS);
                        inSet.setInterpolator(new android.view.animation.OvershootInterpolator(1.6f));
                        inSet.start();
                    }
                });

        // Anchor above the button (long-pressed control sits at the bottom toolbar).
        try {
            int[] loc = new int[2];
            anchor.getLocationOnScreen(loc);
            popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY,
                    loc[0] - (widthPx / 2) + (anchor.getWidth() / 2),
                    loc[1] - maxHeightPx - (int) (16 * dp));
        } catch (Exception e) {
            popup.showAsDropDown(anchor);
        }
    }

    /** Build a single tappable history row (redo entry above centerline, undo entry below). */
    private View buildHistoryRow(@NonNull String label, @NonNull String description,
                                  boolean isUndoSide, @NonNull Runnable onTap) {
        float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int padH = (int) (14 * dp);
        int padV = (int) (10 * dp);
        row.setPadding(padH, padV, padH, padV);
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId != 0 ? outValue.resourceId : android.R.color.transparent);

        TextView numberView = new TextView(this);
        numberView.setText(label);
        numberView.setTextSize(13);
        numberView.setTypeface(null, android.graphics.Typeface.BOLD);
        numberView.setTextColor(isUndoSide ? 0xFFFF8A65 : 0xFF4DD0E1);
        android.widget.LinearLayout.LayoutParams numLp = new android.widget.LinearLayout.LayoutParams(
                (int) (32 * dp), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        row.addView(numberView, numLp);

        TextView descView = new TextView(this);
        descView.setText(description);
        descView.setTextSize(13);
        descView.setTextColor(0xFFDDDDDD);
        descView.setMaxLines(1);
        descView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.LinearLayout.LayoutParams descLp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        descLp.leftMargin = (int) (8 * dp);
        row.addView(descView, descLp);

        // onTap already wraps popup dismissal (see call sites in
        // showUndoRedoHistoryPopup), so just invoke it directly here.
        row.setOnClickListener(v -> onTap.run());
        return row;
    }

    /** Build the visually distinct "current position" centerline row. */
    private View buildCenterlineRow() {
        float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF33334D);
        bg.setCornerRadius(6 * dp);
        row.setBackground(bg);

        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(0xFF4CAF50);
        dot.setBackground(dotBg);
        android.widget.LinearLayout.LayoutParams dotLp = new android.widget.LinearLayout.LayoutParams(
                (int) (10 * dp), (int) (10 * dp));
        dotLp.rightMargin = (int) (10 * dp);
        row.addView(dot, dotLp);

        TextView label = new TextView(this);
        label.setText("Current position");
        label.setTextSize(13);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setTextColor(0xFFFFFFFF);
        row.addView(label);
        return row;
    }

    /**
     * Jump to a point in history by repeating undo()/redo() {@code steps} times
     * (data-only, no per-step UI refresh), then performing exactly ONE UI refresh
     * and save at the end — mirrors the post-processing in performUndo()/performRedo().
     *
     * @param steps  number of undo/redo calls to perform (>= 1)
     * @param isRedo true to call redo() repeatedly, false to call undo() repeatedly
     */
    private void jumpUndoRedoBy(int steps, boolean isRedo) {
        if (steps <= 0) return;
        boolean changed = false;
        for (int i = 0; i < steps; i++) {
            boolean ok = isRedo ? undoManager.redo() : undoManager.undo();
            if (!ok) break;
            changed = true;
        }
        if (!changed) return;
        refreshEditorAfterUndoRedo();
        scheduleAutoSave();
    }

    /**
     * Restore the entire project from a JSON snapshot.
     * Called by UndoManager's SnapshotRestorer during snapshot-based undo/redo.
     * Replaces the project object and all model references.
     *
     * @param projectJson the serialized project JSON to restore from
     */
    private void restoreProjectFromSnapshot(@NonNull String projectJson) {
        FaditorProject restored = projectStorage.fromJson(projectJson);
        if (restored == null) {
            FLog.e(TAG, "Failed to restore project from snapshot");
            return;
        }
        this.project = restored;
        FLog.d(TAG, "Project restored from snapshot, clips="
                + restored.getTimeline().getClipCount()
                + ", audioClips=" + restored.getTimeline().getAudioClipCount());
    }

    /**
     * Refresh all editor UI after an undo/redo operation.
     * Syncs timeline, toolbar, player state, and preview transforms with the current model.
     * Handles both action-based (same objects) and snapshot-based (new objects) restoration.
     */
    private void refreshEditorAfterUndoRedo() {
        // Clamp selected index in case clip count changed (e.g. after snapshot restore)
        int clipCount = project.getTimeline().getClipCount();
        if (clipCount == 0) return;
        if (selectedClipIndex < 0 || selectedClipIndex >= clipCount) {
            selectedClipIndex = Math.max(0, clipCount - 1);
        }

        Clip clip = getSelectedClip();
        if (clip == null) return;

        // Rebuild timeline view completely (handles both action and snapshot changes)
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);
        editorTimeline.setTrimFromClip(clip);
        editorTimeline.setAudioClips(project.getTimeline().getAudioClips());

        // Update player state
        if (!clip.isImageClip()) {
            playerManager.updateTrimBounds(clip);
            playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
            playerManager.setPlaybackSpeed(clip.getSpeedMultiplier(), clip.isPitchCompensationEnabled());
        }

        // Update toolbar UI
        updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
        updateOpacityUI();
        updateSpeedUI(clip.getSpeedMultiplier());
        updateRotateUI(clip.getRotationDegrees());
        updateFlipUI(clip.isFlipHorizontal(), clip.isFlipVertical());
        updateCropUI(clip.getCropPreset());
        updateFilterUI(clip);
        applyPreviewColorGrade(clip);
        updateCanvasUI(project.getCanvasPreset());
        updateAudioToolUI();

        // Update preview transforms (rotation, flip, crop, canvas)
        updatePreviewTransforms();

        // Update time displays
        updateCurrentTimeDisplay(0);
        refreshTotalTimeDisplay();

        // Re-prepare audio player for any audio clip changes
        prepareAudioPlayer();

        // Bind any waveform visualizers and extract their audio.
        refreshWaveformOverlays();

        // Sync timeline overlay rows (text / caption spans / visualizers) so
        // caption + overlay undo/redo is reflected on the timeline, and re-evaluate
        // the caption overlays for the current playhead.
        syncTimelineOverlays();
        // Rebuild the on-canvas text/image overlay layer from the (possibly
        // restored) model so overlay add/delete/move/keyframe undo is reflected
        // in the preview, then re-position it for the current playhead.
        if (overlayLayer != null) {
            overlayLayer.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleTextOverlays(project.getTimeline()), overlayLayerCallback());
            overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
        }
        if (captionsActive) {
            Clip cc = getSelectedClip();
            if (cc != null && cc.isCaptionsEnabled() && cc.hasTranscript()) {
                bindCaptionData(cc);
            }
        }
    }

    /**
     * Bind placed waveform visualizers to the preview layer and kick off (cached) audio
     * extraction for any source not yet analyzed. Safe to call repeatedly.
     */
    private void refreshWaveformOverlays() {
        if (project == null || waveformOverlayView == null) return;
        java.util.List<com.fadcam.ui.faditor.model.WaveformOverlayInstance> overlays =
                project.getTimeline().getWaveformOverlays();
        waveformOverlayView.setOverlays(overlays);
        waveformOverlayView.setData(waveformDataBySource);
        waveformOverlayView.setPlayheadMs(editorTimeline.getPlayheadPositionMs());
        waveformOverlayView.setOnChangeListener(
                new com.fadcam.ui.faditor.waveform.WaveformOverlayView.OnChangeListener() {
                    @Override
                    public void onWaveformChanged() {
                        scheduleAutoSave();
                    }

                    @Override
                    public void onWaveformDeleted(
                            @NonNull com.fadcam.ui.faditor.model.WaveformOverlayInstance overlay) {
                        project.getTimeline().removeWaveformOverlay(overlay);
                        undoManager.recordAction(new EditActions.LambdaAction("Remove visualizer",
                                () -> project.getTimeline().removeWaveformOverlay(overlay),
                                () -> project.getTimeline().addWaveformOverlay(overlay)));
                        waveformOverlayView.setOverlays(project.getTimeline().getWaveformOverlays());
                        waveformOverlayView.invalidate();
                        scheduleAutoSave();
                        Toast.makeText(FaditorEditorActivity.this, "Visualizer removed",
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onWaveformTapped(
                            @NonNull com.fadcam.ui.faditor.model.WaveformOverlayInstance overlay) {
                        showVisualizerStylePicker(overlay);
                    }
                });
        if (!overlays.isEmpty()) {
            waveformOverlayView.bringToFront(); // sit above other layers so it receives touches
            if (audioCaptionOverlay != null) audioCaptionOverlay.bringToFront();
            if (captionOverlay != null) captionOverlay.bringToFront();
        }
        if (overlays.isEmpty()) return;
        // Refresh each overlay's source-time mapping from its driving clip (trim in-point + speed)
        // so the visualizer reads the correct part of the waveform for trimmed/sped clips.
        for (com.fadcam.ui.faditor.model.WaveformOverlayInstance o : overlays) {
            String ref = o.getAudioSourceRef();
            Clip src = ref != null ? findClipById(ref) : null;
            if (src != null) {
                o.setSourceMapping(src.getInPointMs(), src.getSpeedMultiplier());
                if (src.hasLoopExtension()) {
                    o.setLoopExtension(src.getTrimmedDurationMs());
                }
            }
        }
        java.util.Set<String> needed = new java.util.HashSet<>();
        for (com.fadcam.ui.faditor.model.WaveformOverlayInstance o : overlays) {
            String ref = o.getAudioSourceRef();
            if (ref != null && !waveformDataBySource.containsKey(ref)
                    && !waveformExtractInFlight.contains(ref)) {
                needed.add(ref);
            }
        }
        for (String ref : needed) {
            Clip clip = findClipById(ref);
            if (clip == null) continue;
            android.net.Uri uri = resolvePlaybackUri(clip.getSourceUri());
            if (waveformExtractor == null) {
                waveformExtractor = new com.fadcam.ui.faditor.waveform.WaveformExtractor(this);
            }
            waveformExtractInFlight.add(ref);
            // Only extract the source span the clip actually uses (its trim window + a ~1.2s lead-in
            // for the amplitude scrolling window) instead of the whole — possibly 30-min — file.
            long spanStartMs = Math.max(0, clip.getInPointMs() - 1200);
            long spanEndMs = clip.getOutPointMs() + 200;
            FLog.d(TAG, "Waveform extract START ref=" + ref + " uri=" + uri
                    + " span=" + spanStartMs + ".." + spanEndMs);
            waveformExtractor.extractAsync(uri, 64, spanStartMs, spanEndMs,
                    new com.fadcam.ui.faditor.waveform.WaveformExtractor.Callback() {
                        @Override
                        public void onReady(@NonNull com.fadcam.ui.faditor.model.WaveformData data) {
                            runOnUiThread(() -> {
                                waveformExtractInFlight.remove(ref);
                                waveformDataBySource.put(ref, data);
                                FLog.d(TAG, "Waveform extract DONE ref=" + ref
                                        + " buckets=" + data.bucketCount()
                                        + " bands=" + data.bandCount()
                                        + " durMs=" + data.durationMs);
                                if (waveformOverlayView != null) {
                                    waveformOverlayView.setData(waveformDataBySource);
                                    waveformOverlayView.invalidate();
                                }
                            });
                        }

                        @Override
                        public void onProgress(float fraction) {
                            runOnUiThread(() -> {
                                if (waveformOverlayView != null) {
                                    waveformOverlayView.setExtractionProgress(ref, fraction);
                                }
                            });
                        }

                        @Override
                        public void onError(@NonNull String message) {
                            runOnUiThread(() -> waveformExtractInFlight.remove(ref));
                            FLog.w(TAG, "Waveform extract FAILED for " + ref + ": " + message);
                        }
                    });
        }
    }

    // ── Text overlays ────────────────────────────────────────────────

    /**
     * Bind the overlay layer to the project's text overlays and keep the
     * overlay views repositioned as the preview lays out.
     */
    /** Push the current overlays to the timeline so their layer bars show. */
    private void syncTimelineOverlays() {
        if (editorTimeline != null && project != null) {
            Timeline tl = project.getTimeline();
            // G5: attached visualizers re-derive their windows from their hosts' CURRENT
            // spans. Every edit path funnels through this sync, so time-riding is one call.
            tl.resyncAttachedVisualizers();
            // G9: host/rider link groups re-derive rider times the same way (one write-point).
            tl.resyncLinkGroups();
            // Layers-UX Slice C: the OLD read-only layer bars (EditorTimelineView#drawLayers —
            // text/image overlays, visualizers, captions) are RETIRED. Captions & visualizers
            // are now first-class headered Track rows in LayerRowRenderer (Slice A/B), so still
            // feeding the old renderer would DOUBLE-RENDER them (JoyRaptor's FEEDBACK #1). We stop
            // feeding setOverlays/setWaveformLayers/setCaptionSpans; those lists stay empty →
            // drawLayers early-returns, its old hit-testing goes inert (handleM6RowTouch already
            // takes priority), and its reserved band collapses (getM6RowsTopPx == getLayerTopPx,
            // no empty reserved band). (Audio's old bar path is consolidated in a later slice.)
            editorTimeline.setTransitions(tl.getTransitions());
            // Slice B: captions & visualizers ride the SAME headered-row renderer as every other
            // item type. Appended to the floating layer band; Slice E re-splits the bands
            // (overlays+CC on top). Empty for a plain single-track project (renders nothing).
            java.util.List<com.fadcam.ui.faditor.layers.Track> layerBand =
                    new java.util.ArrayList<>(tl.getLayers());
            layerBand.addAll(tl.getVisualizerTracks());
            layerBand.addAll(tl.getCaptionTracks());
            // Audio consolidation (2026-07-07, replaces the FEEDBACK #1 suppression): audio
            // rows now ride the unified LayerRowRenderer in their own band BELOW master
            // (Slice-E order preserved), and the legacy audio bar path in EditorTimelineView
            // is gated off whenever these tracks are non-empty — so there is exactly ONE
            // audio UI. Every op anchored on getSelectedAudioIndex keeps working: that
            // method now DERIVES the index from LayerGestureController's unified selection
            // (TimedItem id == AudioClip id).
            editorTimeline.setLayerTracks(layerBand, tl.getAudioTracks());
            // M-COMP-1: re-bind the preview overlay layers from the (possibly track-
            // hidden-filtered) Track model. TextOverlayLayer already got the filtered
            // list via overlayLayer.setData(...) at each of its own call sites; here we
            // additionally refresh the IMAGE-track preview surface (always empty today —
            // no IMAGE-track creation UI exists — so this is a no-op for every current
            // project; see LayerPreviewController#visibleImageItems).
            if (layerImageOverlay != null) {
                layerImageOverlay.setItems(
                        com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleImageItems(tl));
                layerImageOverlay.setPlayheadMs(lastPlayheadAbsoluteMs);
            }
            // S4: re-bind the sprite preview from the (hidden-filtered) Track model —
            // same single-authority filter S6's export will consume.
            if (spriteOverlayView != null) {
                spriteOverlayView.setData(
                        com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleSpriteItems(tl),
                        spriteOverlayCallback());
                spriteOverlayView.setPlayheadMs(lastPlayheadAbsoluteMs);
            }
            // S3: the palette panel mirrors the same filtered list when open.
            if (spritePalettePanel != null && spritePalettePanel.isAttachedToWindow()) {
                spritePalettePanel.setData(
                        com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleSpriteItems(tl));
            }
            // M-COMP-2: re-bind the live PiP layer from the (hidden-filtered) Track
            // model — the same single authority M-EXPORT-2's export must consume.
            if (overlayVideoLayer != null) {
                overlayVideoLayer.setClips(
                        com.fadcam.ui.faditor.compositor.LayerPreviewController
                                .visibleOverlayVideoClips(tl),
                        overlayVideoCallback());
                overlayVideoLayer.setPlayheadMs(lastPlayheadAbsoluteMs,
                        playerManager != null && playerManager.isPlaying());
            }
            // PHASE-P P3: keep the ripple/gap button in sync with the model (covers
            // initial load, toggle, and undo/redo — all funnel through this sync).
            updateRippleModeButton();
        }
    }

    private static final String PREF_TIMELINE_BAND_DP = "timeline_band_max_dp";

    /**
     * G6.2 snap detents (contract §5): the sensible band-height splits the grab bar snaps to on release.
     * Match {@code LayerRowRenderer}'s MIN (40) / DEFAULT (140) / CAP (460) — video-dominant · balanced ·
     * timeline-dominant. {@code setLayerBandMaxHeightDp} re-clamps to that same range, so these self-heal
     * if the renderer's bounds ever change.
     */
    private static final float[] TIMELINE_BAND_DETENTS_DP = { 40f, 140f, 460f };
    /** Snap radius (dp): within this of a detent on release → snap; further out keeps the free-drag split. */
    private static final float TIMELINE_BAND_SNAP_RADIUS_DP = 32f;

    /** G6.2: nearest detent to {@code dp} within {@link #TIMELINE_BAND_SNAP_RADIUS_DP}, else {@code dp} unchanged. */
    private float snapTimelineBandToDetent(float dp) {
        float best = dp, bestDist = TIMELINE_BAND_SNAP_RADIUS_DP;
        for (float d : TIMELINE_BAND_DETENTS_DP) {
            float dist = Math.abs(dp - d);
            if (dist < bestDist) { bestDist = dist; best = d; }
        }
        return best;
    }

    /**
     * G6 resizable timeline (contract §5): wire the grab bar sitting on the preview↔timeline boundary.
     * A vertical drag reallocates space — drag UP → taller timeline (more layer rows visible, smaller
     * preview); drag DOWN → bigger preview — by driving {@link EditorTimelineView}'s layer-band viewport
     * cap (the band grows/shrinks inside the cap, changing the timeline's measured height, which reflows
     * the {@code layout_weight=1} preview above it). The chosen size PERSISTS per install.
     */
    /** True while the user has a finger down on the G6 grab bar (read by PreviewPipController). */
    private boolean grabBarDragging = false;
    /** G6.3/G6.4: promotes the preview to a draggable PiP when its slot collapses. */
    @Nullable private com.fadcam.ui.faditor.player.PreviewPipController previewPip;

    private void setupTimelineResizeGrabBar() {
        final View grabBar = findViewById(R.id.timeline_grab_bar);
        if (grabBar == null || editorTimeline == null) return;
        final android.content.SharedPreferences ui =
                getSharedPreferences("faditor_ui", MODE_PRIVATE);
        // Restore the persisted band cap (default = the view's built-in default).
        editorTimeline.setLayerBandMaxHeightDp(
                ui.getFloat(PREF_TIMELINE_BAND_DP, editorTimeline.getLayerBandDefaultMaxHeightDp()));
        final float density = getResources().getDisplayMetrics().density;
        grabBar.setOnTouchListener(new View.OnTouchListener() {
            float downRawY;
            float baselineDp;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = e.getRawY();
                        baselineDp = editorTimeline.getLayerBandMaxHeightDp();
                        grabBarDragging = true;
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        // Drag UP (rawY decreases) grows the timeline; DOWN shrinks it.
                        float deltaDp = (downRawY - e.getRawY()) / density;
                        float targetDp = baselineDp + deltaDp;
                        // G6.3: never grow the band past the space actually available —
                        // the preview promotes to PiP as it collapses, but the tool row /
                        // caption strip below must always stay on screen (contract §5).
                        if (previewPip != null) {
                            targetDp = Math.min(targetDp, previewPip.maxBandDpFor(
                                    editorTimeline.getLayerBandMaxHeightDp()));
                        }
                        editorTimeline.setLayerBandMaxHeightDp(targetDp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        grabBarDragging = false;
                        v.setPressed(false);
                        // G6.2 (contract §5): snap the released split to the nearest sensible detent
                        // (video-dominant / balanced / timeline-dominant) when close; free-drag
                        // positions further from any detent are kept as-is.
                        float snapped = snapTimelineBandToDetent(editorTimeline.getLayerBandMaxHeightDp());
                        if (snapped != editorTimeline.getLayerBandMaxHeightDp()) {
                            editorTimeline.setLayerBandMaxHeightDp(snapped);
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                        ui.edit().putFloat(PREF_TIMELINE_BAND_DP,
                                editorTimeline.getLayerBandMaxHeightDp()).apply();
                        v.performClick();
                        return true;
                }
                return false;
            }
        });

        // G6.3/G6.4 (contract §5): PiP promotion of the preview when its slot collapses —
        // via the grab bar's top extreme OR landscape rotation. Fully additive: with a
        // comfortable preview slot the controller never engages.
        try {
            android.widget.LinearLayout editorRoot = findViewById(R.id.editor_root);
            android.widget.FrameLayout container = findViewById(R.id.player_container);
            if (editorRoot != null && container != null) {
                previewPip = new com.fadcam.ui.faditor.player.PreviewPipController(
                        editorRoot, container,
                        new com.fadcam.ui.faditor.player.PreviewPipController.Host() {
                            @Override public float canvasAspect() {
                                try { return resolveCanvasAspect(); }
                                catch (Exception e) { return 0f; }
                            }
                            @Override public float getBandDp() {
                                return editorTimeline.getLayerBandMaxHeightDp();
                            }
                            @Override public void setBandDp(float dp) {
                                editorTimeline.setLayerBandMaxHeightDp(dp);
                            }
                            @Override public boolean isGrabBarDragging() {
                                return grabBarDragging;
                            }
                        });
            }
        } catch (Exception e) {
            FLog.e(TAG, "PreviewPipController setup failed — inline preview only", e);
        }
    }

    // ═══════════ G8 marquee multi-select (contract §5.5) — activity glue ═══════════

    /** OFF → INCLUSIVE (crossing) → EXCLUSIVE (window) → OFF, with icon tint + hint. */
    private void cycleMarqueeMode() {
        if (editorTimeline == null) return;
        com.fadcam.ui.faditor.timeline.EditorTimelineView.MarqueeMode next;
        String hint;
        int tint;
        switch (editorTimeline.getMarqueeMode()) {
            case OFF:
                next = com.fadcam.ui.faditor.timeline.EditorTimelineView.MarqueeMode.INCLUSIVE;
                hint = "Select: touch anything the box crosses";
                tint = 0xFF4CAF50;
                break;
            case INCLUSIVE:
                next = com.fadcam.ui.faditor.timeline.EditorTimelineView.MarqueeMode.EXCLUSIVE;
                hint = "Select: only fully-boxed objects";
                tint = 0xFFFFB74D;
                break;
            default:
                next = com.fadcam.ui.faditor.timeline.EditorTimelineView.MarqueeMode.OFF;
                hint = "Select mode off";
                tint = 0xFFCCCCCC;
                break;
        }
        editorTimeline.setMarqueeMode(next);
        TextView icon = findViewById(R.id.tool_select_icon);
        if (icon != null) icon.setTextColor(tint);
        Toast.makeText(this, hint, Toast.LENGTH_SHORT).show();
    }

    private void wireMarqueeListener() {
        if (editorTimeline == null) return;
        editorTimeline.setMarqueeListener(
                new com.fadcam.ui.faditor.timeline.EditorTimelineView.MarqueeListener() {
                    @Override
                    public void onBatchActionRequested(@NonNull java.util.List<
                            com.fadcam.ui.faditor.layers.LayerRowRenderer.ItemHit> items) {
                        showMarqueeBatchMenu(items);
                    }

                    @Override
                    public void onMarqueeSelectionChanged(int count) {
                        // Selection visuals live on the timeline itself; nothing modal here.
                    }
                });
    }

    /** Batch menu: only actions UNIVERSAL to every selected type appear (contract §5.5).
     *  v1 ships DELETE; more batch props (opacity/lock/move) ride later slices. */
    private void showMarqueeBatchMenu(@NonNull java.util.List<
            com.fadcam.ui.faditor.layers.LayerRowRenderer.ItemHit> items) {
        if (project == null || items.isEmpty()) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(items.size() + " objects selected")
                .setItems(new CharSequence[]{"Delete selected"}, (d, w) -> {
                    if (w == 0) confirmMarqueeBatchDelete(items);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmMarqueeBatchDelete(@NonNull java.util.List<
            com.fadcam.ui.faditor.layers.LayerRowRenderer.ItemHit> items) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Delete " + items.size() + " selected objects?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> performMarqueeBatchDelete(items))
                .show();
    }

    /**
     * One-shot batch delete with ONE composite undo step. Handles the payload types
     * whose add/remove pairs are pure timeline mutations (text/image overlays, sprites,
     * visualizers, PiP overlay clips). Captions (clip-owned, no delete semantics) and
     * audio clips (index-anchored legacy subsystem) are skipped with a note.
     */
    private void performMarqueeBatchDelete(@NonNull java.util.List<
            com.fadcam.ui.faditor.layers.LayerRowRenderer.ItemHit> items) {
        if (project == null) return;
        final Timeline timeline = project.getTimeline();
        final java.util.List<com.fadcam.ui.faditor.model.TextOverlayItem> texts =
                new java.util.ArrayList<>();
        final java.util.List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> sprites =
                new java.util.ArrayList<>();
        final java.util.List<com.fadcam.ui.faditor.model.WaveformOverlayInstance> waves =
                new java.util.ArrayList<>();
        final java.util.List<Clip> pips = new java.util.ArrayList<>();
        int skipped = 0;
        for (com.fadcam.ui.faditor.layers.LayerRowRenderer.ItemHit h : items) {
            com.fadcam.ui.faditor.layers.TimedItem it = h.item;
            if (it.getTextOverlay() != null) texts.add(it.getTextOverlay());
            else if (it.getSprite() != null) sprites.add(it.getSprite());
            else if (it.getWaveform() != null) waves.add(it.getWaveform());
            else if (it.getClip() != null && it.getClip().isOverlayClip()) pips.add(it.getClip());
            else skipped++;
        }
        int deletable = texts.size() + sprites.size() + waves.size() + pips.size();
        if (deletable == 0) {
            Toast.makeText(this, "Nothing deletable in the selection", Toast.LENGTH_SHORT).show();
            return;
        }
        final Runnable applyDelete = () -> {
            for (com.fadcam.ui.faditor.model.TextOverlayItem t : texts) timeline.removeTextOverlay(t);
            for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : sprites) timeline.removeSpriteOverlay(s);
            for (com.fadcam.ui.faditor.model.WaveformOverlayInstance v : waves) timeline.removeWaveformOverlay(v);
            for (Clip c : pips) timeline.removeOverlayClip(c);
            refreshAfterMarqueeBatchDelete();
        };
        final Runnable revertDelete = () -> {
            for (com.fadcam.ui.faditor.model.TextOverlayItem t : texts) timeline.addTextOverlay(t);
            for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : sprites) timeline.addSpriteOverlay(s);
            for (com.fadcam.ui.faditor.model.WaveformOverlayInstance v : waves) timeline.addWaveformOverlay(v);
            for (Clip c : pips) timeline.addOverlayClip(c);
            refreshAfterMarqueeBatchDelete();
        };
        applyDelete.run();
        undoManager.recordAction(new EditActions.LambdaAction(
                "Delete " + deletable + " objects", applyDelete, revertDelete));
        if (editorTimeline != null) editorTimeline.clearMarqueeSelection();
        scheduleAutoSave();
        Toast.makeText(this, deletable + " deleted"
                + (skipped > 0 ? " (" + skipped + " skipped)" : ""), Toast.LENGTH_SHORT).show();
    }

    /** Every preview surface a batch delete can touch, refreshed in one place. */
    private void refreshAfterMarqueeBatchDelete() {
        syncTimelineOverlays();
        if (overlayLayer != null && project != null) {
            overlayLayer.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController
                    .visibleTextOverlays(project.getTimeline()), overlayLayerCallback());
            overlayLayer.invalidate();
        }
        if (waveformOverlayView != null && project != null) {
            waveformOverlayView.setOverlays(project.getTimeline().getWaveformOverlays());
            waveformOverlayView.invalidate();
        }
        refreshSpritePreviewData();
        if (editorTimeline != null) editorTimeline.invalidate();
    }

    /**
     * M6 row-header toggle glue (PLAN Part 7, row M6; scope items 3-5): flips the
     * tapped flag in {@code Timeline}'s persistent {@code TrackFlags} side-table
     * (the fix for the M5 status note ⚠️ — a plain mutation on the Track VIEW object
     * would be lost on the next rebuild-from-flat), records it as one undo step via
     * the same {@code EditActions.LambdaAction} pattern used elsewhere in this class
     * (e.g. "Remove visualizer" / "Delete text overlay" above), then refreshes the
     * timeline. Toggles only affect THIS row's rendering/hit-testing in M6 — preview/
     * export wiring is M-COMP-1 / M-EXPORT-1 (TODOs below at each effect site).
     */
    private void onTrackHeaderAction(@NonNull com.fadcam.ui.faditor.layers.Track track,
                                      @NonNull com.fadcam.ui.faditor.layers.LayerRowRenderer.HitZone zone) {
        if (project == null) return;
        final Timeline timeline = project.getTimeline();
        final String trackId = track.getId();
        final com.fadcam.ui.faditor.layers.TrackFlags before =
                timeline.getOrCreateTrackFlags(trackId).copy();

        String description;
        switch (zone) {
            case CARET:
                track.setCollapsed(!track.isCollapsed());
                description = track.isCollapsed() ? "Collapse track" : "Expand track";
                break;
            case HIDE:
                track.setHidden(!track.isHidden());
                // M-COMP-1: a hidden track is now also skipped by LayerPreviewController
                // (see visibleTextOverlays/visibleImageItems), applied below via
                // syncTimelineOverlays(). TODO(M-EXPORT-1): mirror in ExportManager.
                description = track.isHidden() ? "Hide track" : "Show track";
                break;
            case LOCK:
                track.setLocked(!track.isLocked());
                // Locked already takes effect at the timeline level in M6 (row-body
                // taps/gestures are swallowed by LayerRowRenderer/EditorTimelineView).
                description = track.isLocked() ? "Lock track" : "Unlock track";
                break;
            case MUTE:
                track.setMuted(!track.isMuted());
                // M-COMP-1: a muted AUDIO track now silences its clips' MediaPlayer
                // preview volume (LayerPreviewController#effectivePreviewVolume, applied
                // at every existing per-clip volume site). Push the new volume to any
                // already-playing player immediately rather than waiting for the next
                // playheadUpdater tick (mirrors the clip-level mute button's live push).
                // TODO(M-EXPORT-1): mirror in ExportManager's audio mix.
                applyAudioTrackMuteLive(timeline);
                description = track.isMuted() ? "Mute track" : "Unmute track";
                break;
            default:
                return;
        }

        // Persist into the side-table (the Track passed in is a rebuilt-from-flat VIEW —
        // writing through it directly would be lost on the next getLayers()/etc. call).
        com.fadcam.ui.faditor.layers.TrackFlags flags = timeline.getOrCreateTrackFlags(trackId);
        flags.collapsed = track.isCollapsed();
        flags.hidden = track.isHidden();
        flags.locked = track.isLocked();
        flags.muted = track.isMuted();
        flags.zIndex = track.getZIndex();
        timeline.pruneDefaultTrackFlags();
        final com.fadcam.ui.faditor.layers.TrackFlags after = flags.copy();

        undoManager.recordAction(new EditActions.LambdaAction(description,
                () -> { timeline.setTrackFlags(trackId, after.copy()); syncTimelineOverlays();
                        refreshPreviewOverlayVisibility(); applyAudioTrackMuteLive(timeline); },
                () -> { timeline.setTrackFlags(trackId, before.copy()); syncTimelineOverlays();
                        refreshPreviewOverlayVisibility(); applyAudioTrackMuteLive(timeline); }));

        syncTimelineOverlays();
        // PHASE-R R1 (M-EXPORT-1 finding): syncTimelineOverlays() refreshes the TIMELINE
        // rows + the (always-empty) image overlay surface, but the on-canvas PREVIEW
        // TextOverlayLayer keeps whatever list its last setData() call fed it — so a
        // hide toggle dimmed the row while the text stayed visible in the preview until
        // a project reload re-fed it. Re-feed it through the same visibility authority
        // here (and in the undo/redo lambdas above; performUndo/-Redo additionally goes
        // through refreshEditorAfterUndoRedo which already re-feeds).
        refreshPreviewOverlayVisibility();
        scheduleAutoSave();
    }

    /**
     * PHASE-R R1: re-feed the preview {@code TextOverlayLayer} from
     * {@link com.fadcam.ui.faditor.compositor.LayerPreviewController#visibleTextOverlays}
     * (the single preview/export visibility authority) and re-evaluate it at the current
     * playhead, so track hide/unhide takes effect in the preview immediately. Mirrors the
     * refresh {@code refreshEditorAfterUndoRedo()} already performs.
     */
    private void refreshPreviewOverlayVisibility() {
        if (overlayLayer != null && project != null) {
            overlayLayer.setData(
                    com.fadcam.ui.faditor.compositor.LayerPreviewController
                            .visibleTextOverlays(project.getTimeline()),
                    overlayLayerCallback());
            overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
        }
    }

    // ── PHASE-P P1: layer header long-press menu (rename / move / delete) ──────────

    /**
     * Long-press on a layer/audio row header (PHASE-P P1): compact dark-card menu with
     * Rename / Move up / Move down / Delete layer (delete only for user-created
     * {@code LayerTrackDef} tracks — the fixed default tracks and the master row have
     * nothing to delete; the master row never even reaches here because it is not part
     * of the {@code LayerRowRenderer} row band). Styling mirrors the undo-history popup
     * (dark 0xFF1A1A2E card, 12dp radius). Every action records ONE undo step.
     */
    private void onTrackHeaderLongPress(@NonNull com.fadcam.ui.faditor.layers.Track track,
                                        float viewX, float viewY) {
        if (project == null || editorTimeline == null) return;
        if (track.getKind() == com.fadcam.ui.faditor.layers.TrackKind.MASTER) return; // structural exclusion
        final Timeline timeline = project.getTimeline();
        final boolean floatingBand = editorTimeline.isLayerTrackFloatingBand(track);
        final boolean userCreated = timeline.getLayerTrackDef(track.getId()) != null;

        float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padV = (int) (6 * dp);
        list.setPadding(0, padV, 0, padV);

        android.graphics.drawable.GradientDrawable cardBg =
                new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFF1A1A2E);
        cardBg.setCornerRadius(12 * dp);
        cardBg.setStroke((int) (1 * dp), 0xFF444444);
        list.setBackground(cardBg);

        final android.widget.PopupWindow popup = new android.widget.PopupWindow(
                list, (int) (190 * dp), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT));
        popup.setElevation(8 * dp);

        // TODO(strings): hardcoded per the rebrand-freeze standing rule.
        addTrackMenuRow(list, popup, "Rename", () -> showRenameTrackDialog(track));
        addTrackMenuRow(list, popup, "Move up", () -> moveTrackZ(track, true, floatingBand));
        addTrackMenuRow(list, popup, "Move down", () -> moveTrackZ(track, false, floatingBand));
        if (userCreated) {
            addTrackMenuRow(list, popup, "Delete layer", () -> confirmDeleteLayerTrack(track));
        }

        int[] loc = new int[2];
        editorTimeline.getLocationOnScreen(loc);
        popup.showAtLocation(editorTimeline, android.view.Gravity.NO_GRAVITY,
                loc[0] + (int) viewX + (int) (8 * dp), loc[1] + (int) viewY - (int) (8 * dp));
    }

    /** One tappable row of the P1 track menu; dismisses the popup, then runs the action. */
    private void addTrackMenuRow(@NonNull android.widget.LinearLayout parent,
                                 @NonNull android.widget.PopupWindow popup,
                                 @NonNull String label, @NonNull Runnable action) {
        float dp = getResources().getDisplayMetrics().density;
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextColor(0xFFEDEDED);
        row.setTextSize(14f);
        row.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), (int) (10 * dp));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setOnClickListener(v -> { popup.dismiss(); action.run(); });
        parent.addView(row);
    }

    /**
     * P1 Rename: user-created tracks persist via {@code LayerTrackDef#setName} (already
     * serialized in the trackDefs block); the fixed DEFAULT tracks ("text"/"audio"/
     * "sprite") have no def, so the rename persists in the additive
     * {@code TrackFlags.customName} side-table field (serialized as the layers-block
     * "trackNames" map — absent for any project that never renamed one, so old
     * projects/builds are untouched). One undo step either way.
     */
    private void showRenameTrackDialog(@NonNull com.fadcam.ui.faditor.layers.Track track) {
        if (project == null) return;
        final Timeline timeline = project.getTimeline();
        final String trackId = track.getId();

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(track.getName());
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Rename layer") // TODO(strings)
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(track.getName())) return;
                    com.fadcam.ui.faditor.layers.LayerTrackDef def =
                            timeline.getLayerTrackDef(trackId);
                    if (def != null) {
                        final String before = def.getName();
                        def.setName(newName);
                        undoManager.recordAction(new EditActions.LambdaAction("Rename layer",
                                () -> { def.setName(newName); syncTimelineOverlays(); },
                                () -> { def.setName(before); syncTimelineOverlays(); }));
                    } else {
                        final com.fadcam.ui.faditor.layers.TrackFlags before =
                                timeline.getOrCreateTrackFlags(trackId).copy();
                        com.fadcam.ui.faditor.layers.TrackFlags flags =
                                timeline.getOrCreateTrackFlags(trackId);
                        flags.customName = newName;
                        timeline.pruneDefaultTrackFlags();
                        final com.fadcam.ui.faditor.layers.TrackFlags after = flags.copy();
                        undoManager.recordAction(new EditActions.LambdaAction("Rename layer",
                                () -> { timeline.setTrackFlags(trackId, after.copy());
                                        syncTimelineOverlays(); },
                                () -> { timeline.setTrackFlags(trackId, before.copy());
                                        syncTimelineOverlays(); }));
                    }
                    syncTimelineOverlays();
                    scheduleAutoSave();
                })
                .show();
    }

    /**
     * P2 Move up/down: mutates the persisted {@code TrackFlags.zIndex} for EVERY track
     * in the band (normalized so top row = highest z, bottom = 0), with the target and
     * its neighbor swapped. Row render order ({@code Timeline#getLayers()}/{@code
     * getAudioTracks()} stable-sort DESC), preview paint order ({@code
     * LayerPreviewController#visibleTextOverlays} stable-sort ASC, later = on top) and
     * export (same shared authority, M-EXPORT-1) all follow the same field, so "row
     * above" always means "painted on top". Per-item zHint stays out of scope
     * (ephemeral view field — PLAN M5 note). One undo step restores the whole band.
     */
    private void moveTrackZ(@NonNull com.fadcam.ui.faditor.layers.Track track,
                            boolean up, boolean floatingBand) {
        if (project == null) return;
        final Timeline timeline = project.getTimeline();
        java.util.List<com.fadcam.ui.faditor.layers.Track> band =
                floatingBand ? timeline.getLayers() : timeline.getAudioTracks();
        int idx = -1;
        for (int i = 0; i < band.size(); i++) {
            if (band.get(i).getId().equals(track.getId())) { idx = i; break; }
        }
        int target = idx + (up ? -1 : 1);
        if (idx < 0 || target < 0 || target >= band.size()) {
            Toast.makeText(this, up ? "Already at top" : "Already at bottom",
                    Toast.LENGTH_SHORT).show(); // TODO(strings)
            return;
        }

        // Snapshot BEFORE state of every band track's flags (null = no entry).
        final java.util.Map<String, com.fadcam.ui.faditor.layers.TrackFlags> before =
                snapshotBandFlags(timeline, band);

        // Desired render order = current order with idx/target swapped; normalize
        // zIndex = (n-1-i) so the top row carries the highest z.
        java.util.List<String> order = new java.util.ArrayList<>();
        for (com.fadcam.ui.faditor.layers.Track t : band) order.add(t.getId());
        java.util.Collections.swap(order, idx, target);
        int n = order.size();
        for (int i = 0; i < n; i++) {
            timeline.getOrCreateTrackFlags(order.get(i)).zIndex = n - 1 - i;
        }
        timeline.pruneDefaultTrackFlags();
        final java.util.Map<String, com.fadcam.ui.faditor.layers.TrackFlags> after =
                snapshotBandFlags(timeline, band);

        undoManager.recordAction(new EditActions.LambdaAction(
                up ? "Move layer up" : "Move layer down",
                () -> applyBandFlags(timeline, after),
                () -> applyBandFlags(timeline, before)));

        syncTimelineOverlays();
        refreshPreviewOverlayVisibility();
        scheduleAutoSave();
    }

    /** Copy each band track's current flags entry (or null) keyed by id, for undo. */
    @NonNull
    private java.util.Map<String, com.fadcam.ui.faditor.layers.TrackFlags> snapshotBandFlags(
            @NonNull Timeline timeline,
            @NonNull java.util.List<com.fadcam.ui.faditor.layers.Track> band) {
        java.util.Map<String, com.fadcam.ui.faditor.layers.TrackFlags> snap =
                new java.util.HashMap<>();
        for (com.fadcam.ui.faditor.layers.Track t : band) {
            com.fadcam.ui.faditor.layers.TrackFlags f = timeline.getTrackFlags(t.getId());
            snap.put(t.getId(), f == null ? null : f.copy());
        }
        return snap;
    }

    /** Restore a band flags snapshot wholesale (undo/redo of a z-order move). */
    private void applyBandFlags(@NonNull Timeline timeline,
            @NonNull java.util.Map<String, com.fadcam.ui.faditor.layers.TrackFlags> snap) {
        for (java.util.Map.Entry<String, com.fadcam.ui.faditor.layers.TrackFlags> e
                : snap.entrySet()) {
            timeline.setTrackFlags(e.getKey(),
                    e.getValue() == null ? null : e.getValue().copy());
        }
        syncTimelineOverlays();
        refreshPreviewOverlayVisibility();
    }

    /**
     * P1 Delete layer (user-created tracks only — the menu row is hidden otherwise):
     * items still on the layer MIGRATE to the default track of their band (layerId →
     * null routes text/sticker items to "text", sprites to "sprite", audio to "audio"
     * — see {@code Timeline#getLayers()}/{@code getAudioTracks()} grouping), then the
     * def + its flags entry are removed. Confirmed first when items exist. ONE undo
     * step restores the def, the flags, and every migrated item's layerId.
     */
    private void confirmDeleteLayerTrack(@NonNull com.fadcam.ui.faditor.layers.Track track) {
        if (project == null) return;
        final Timeline timeline = project.getTimeline();
        final String trackId = track.getId();
        final com.fadcam.ui.faditor.layers.LayerTrackDef def = timeline.getLayerTrackDef(trackId);
        if (def == null) return;

        final java.util.List<com.fadcam.ui.faditor.model.TextOverlayItem> texts = new java.util.ArrayList<>();
        for (com.fadcam.ui.faditor.model.TextOverlayItem o : timeline.getTextOverlays()) {
            if (trackId.equals(o.getLayerId())) texts.add(o);
        }
        final java.util.List<AudioClip> audios = new java.util.ArrayList<>();
        for (AudioClip ac : timeline.getAudioClips()) {
            if (trackId.equals(ac.getLayerId())) audios.add(ac);
        }
        final java.util.List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> sprites =
                new java.util.ArrayList<>();
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem so : timeline.getSpriteOverlays()) {
            if (trackId.equals(so.getLayerId())) sprites.add(so);
        }
        int itemCount = texts.size() + audios.size() + sprites.size();

        Runnable doDelete = () -> {
            final com.fadcam.ui.faditor.layers.TrackFlags flagsBefore =
                    timeline.getTrackFlags(trackId) == null
                            ? null : timeline.getTrackFlags(trackId).copy();
            Runnable redo = () -> {
                for (com.fadcam.ui.faditor.model.TextOverlayItem o : texts) o.setLayerId(null);
                for (AudioClip ac : audios) ac.setLayerId(null);
                for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem so : sprites) {
                    so.setLayerId(null);
                }
                timeline.removeLayerTrackDef(trackId);
                timeline.setTrackFlags(trackId, null);
                syncTimelineOverlays();
                refreshPreviewOverlayVisibility();
            };
            Runnable undo = () -> {
                timeline.restoreLayerTrackDef(def);
                timeline.setTrackFlags(trackId,
                        flagsBefore == null ? null : flagsBefore.copy());
                for (com.fadcam.ui.faditor.model.TextOverlayItem o : texts) o.setLayerId(trackId);
                for (AudioClip ac : audios) ac.setLayerId(trackId);
                for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem so : sprites) {
                    so.setLayerId(trackId);
                }
                syncTimelineOverlays();
                refreshPreviewOverlayVisibility();
            };
            redo.run();
            undoManager.recordAction(new EditActions.LambdaAction("Delete layer", redo, undo));
            scheduleAutoSave();
        };

        if (itemCount == 0) {
            doDelete.run();
        } else {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Delete layer") // TODO(strings)
                    .setMessage("Move " + itemCount + " item(s) to the default track and delete this layer?")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Delete", (d, w) -> doDelete.run())
                    .show();
        }
    }

    // ── PHASE-P P3 (M11): master ripple/gap edit-mode toggle ───────────────────────

    /**
     * Toggle {@code Timeline.rippleMode} "ripple" ↔ "gap" (M11). Ripple (today's
     * default) is untouched: a master delete shifts later clips left. Gap mode makes
     * {@link #deleteSelectedSegment()} leave a black spacer instead (see
     * {@link #gapDeleteSelectedSegment}). Persisted via the existing rippleMode
     * serialization (M5); non-"ripple" also stamps the project v8
     * (ProjectStorage#usesLayerFeatures). One undo step.
     */
    private void toggleRippleMode() {
        if (project == null) return;
        final Timeline timeline = project.getTimeline();
        final String before = timeline.getRippleMode();
        final String after = "ripple".equals(before) ? "gap" : "ripple";
        timeline.setRippleMode(after);
        undoManager.recordAction(new EditActions.LambdaAction("Edit mode: " + after,
                () -> { timeline.setRippleMode(after); updateRippleModeButton(); },
                () -> { timeline.setRippleMode(before); updateRippleModeButton(); }));
        updateRippleModeButton();
        scheduleAutoSave();
        // TODO(strings)
        Toast.makeText(this, "gap".equals(after)
                ? "Gap mode: deleting a clip leaves a black gap"
                : "Ripple mode: deleting a clip closes the gap", Toast.LENGTH_SHORT).show();
    }

    /** Sync the toggle's tint with the model: green = ripple (default), amber = gap. */
    private void updateRippleModeButton() {
        if (btnRippleMode == null || project == null) return;
        boolean ripple = "ripple".equals(project.getTimeline().getRippleMode());
        btnRippleMode.setTextColor(ripple ? 0xFF4CAF50 : 0xFFFFB300);
    }

    /**
     * Gap-mode master delete (M11): the clip is REPLACED in place by a black still-image
     * spacer of the same timeline duration, so later clips do NOT shift and the timeline
     * keeps its length. Representation choice: an {@code isImageClip} Clip pointing at a
     * generated black PNG — the least invasive form that already survives save/load
     * (plain clip serialization), previews (the existing image-clip preview path) and
     * exports black (ExportManager's existing image-clip branch) with ZERO new machinery;
     * a true first-class gap object would touch every clip iterator in the app. Floating
     * layers/audio are absolute-positioned and are untouched by construction. One undo
     * step swaps the original clip back.
     */
    private void gapDeleteSelectedSegment(@NonNull Timeline timeline) {
        final int index = selectedClipIndex;
        if (index < 0 || index >= timeline.getClipCount()) return;
        final Clip original = timeline.getClip(index);
        Uri blackUri = ensureBlackSpacerUri();
        if (blackUri == null) {
            Toast.makeText(this, "Could not create gap spacer", Toast.LENGTH_SHORT).show();
            return;
        }
        long durMs = original.hasLoopExtension()
                ? original.getVisualDurationMs() : original.getTrimmedDurationMs();
        final Clip spacer = new Clip(blackUri, Math.max(100, durMs));
        spacer.setImageClip(true);
        spacer.setAudioMuted(true);
        spacer.setDisplayName("Gap"); // TODO(strings)

        timeline.removeClip(index);
        timeline.addClip(index, spacer);
        undoManager.recordAction(new EditActions.LambdaAction("Delete clip (gap)",
                () -> { timeline.removeClip(index); timeline.addClip(index, spacer); },
                () -> { timeline.removeClip(index); timeline.addClip(index, original); }));

        editorTimeline.setTimeline(timeline, index);
        selectSegment(index);
        syncTimelineOverlays();
        editorTimeline.invalidate();
        refreshTotalTimeDisplay();
        saveProjectNow();
        Toast.makeText(this, "Clip removed — gap left in place", Toast.LENGTH_SHORT).show(); // TODO(strings)
    }

    /**
     * Lazily generate the shared 16×16 black PNG the gap spacer clips reference
     * (internal files dir, "images" — same home {@code copyUriToInternalStorage} uses
     * for imported image assets, so project bundling/export asset resolution treat it
     * exactly like any user image).
     */
    @Nullable
    private Uri ensureBlackSpacerUri() {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "images");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "faditor_gap_black.png");
            if (!f.exists() || f.length() == 0) {
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        16, 16, android.graphics.Bitmap.Config.ARGB_8888);
                bmp.eraseColor(0xFF000000);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
                bmp.recycle();
            }
            return Uri.fromFile(f);
        } catch (Exception e) {
            FLog.e(TAG, "ensureBlackSpacerUri failed", e);
            return null;
        }
    }

    /**
     * M-COMP-1: push each audio clip's {@link com.fadcam.ui.faditor.compositor
     * .LayerPreviewController#effectivePreviewVolume} onto its live {@code MediaPlayer}
     * immediately, so a track-mute toggle (or its undo/redo) is audible right away
     * instead of waiting for the next {@code playheadUpdater} tick. No-op (iterates
     * zero/unready players) for a plain project with no audio, matching every other
     * path in this milestone.
     */
    private void applyAudioTrackMuteLive(@NonNull Timeline timeline) {
        java.util.List<AudioClip> acs = timeline.getAudioClips();
        for (int ai = 0; ai < acs.size() && ai < audioPlayers.size(); ai++) {
            if (ai >= audioPlayersReady.size() || !audioPlayersReady.get(ai)) continue;
            AudioClip ac = acs.get(ai);
            if (ac == null) continue;
            float v = com.fadcam.ui.faditor.compositor.LayerPreviewController
                    .effectivePreviewVolume(timeline, ac);
            try { audioPlayers.get(ai).setVolume(v, v); } catch (Exception ignored) { }
        }
    }

    /**
     * M7 glue: {@link com.fadcam.ui.faditor.layers.LayerGestureController} owns the
     * move/trim/delete gesture math for floating items on the M6 rows (PLAN Part 7 row
     * M7); this callback only does what the activity is uniquely responsible for —
     * recording exactly ONE undo step per completed gesture using the SAME undo action
     * classes the on-canvas/existing-lane edits already use ({@code OverlayTransformAction}
     * for text, {@code AudioTrimAction}/{@code LambdaAction} for audio), and re-running the
     * existing refresh paths so BOTH surfaces (the row on the timeline AND the on-canvas
     * {@code TextOverlayLayer} / the audio lane) stay coherent, since both read the SAME
     * mutated payload objects (PLAN scope item 6).
     *
     * <p><b>M10 one-undo-step merge:</b> {@code onItemMovedToTrack}/{@code onItemDroppedOnNewLayer}
     * fire BEFORE {@code onGestureFinished} for the same gesture (see
     * {@code LayerGestureController#onRowBodyUp}). They apply the track mutation
     * immediately (so the model + preview are correct right away) but do NOT call
     * {@code undoManager.recordAction} themselves — they stash the mutation's undo/redo
     * halves in {@link #pendingLayerTrackUndo}, which {@code onGestureFinished} below
     * picks up and folds into the SAME single action it records for the position
     * change. A diagonal drag (changes both time AND track — the common case) therefore
     * still produces exactly one {@code undoStack} entry (PLAN M10 acceptance (d)).</p>
     */
    private com.fadcam.ui.faditor.layers.LayerGestureController.Callback layerGestureCallback() {
        return new com.fadcam.ui.faditor.layers.LayerGestureController.Callback() {

            @Override
            public void onGestureLive(@NonNull com.fadcam.ui.faditor.layers.TimedItem item) {
                // Immediate visual feedback on every surface, mirroring
                // onOverlayRangeChanged/onAudioTrimChanged: refresh the timeline rows AND
                // the on-canvas overlay layer (for text) so a row-drag looks identical to
                // an on-canvas drag while it's happening.
                if (item.getTextOverlay() != null && overlayLayer != null) {
                    overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
                }
                syncTimelineOverlays();
            }

            @Override
            public void onGestureFinished(@NonNull com.fadcam.ui.faditor.layers.TimedItem item,
                    @NonNull com.fadcam.ui.faditor.layers.LayerGestureController.GestureKind kind) {
                if (project == null || editorTimeline == null) return;
                com.fadcam.ui.faditor.layers.LayerGestureController ctrl =
                        editorTimelineGestureController();
                if (ctrl == null) return;

                // M10: pick up (and clear) any track-change staged by onItemMovedToTrack/
                // onItemDroppedOnNewLayer, which ran immediately before this callback for
                // the SAME gesture — see class doc above.
                PendingLayerTrackUndo trackChange = pendingLayerTrackUndo;
                pendingLayerTrackUndo = null;

                if (item.getTextOverlay() != null) {
                    com.fadcam.ui.faditor.model.TextOverlayItem o = item.getTextOverlay();
                    com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot before = ctrl.getTextBeforeSnapshot();
                    if (before == null) { maybeRecordTrackOnlyChange(trackChange); return; }
                    com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot after = o.snapshotTransform();
                    boolean positionChanged = !before.matches(after);
                    if (positionChanged || trackChange != null) {
                        String desc = trackChange != null
                                ? trackChange.description
                                : (kind == com.fadcam.ui.faditor.layers.LayerGestureController.GestureKind.MOVE
                                        ? "Move overlay" : "Overlay time range");
                        undoManager.recordAction(mergedAction(desc,
                                positionChanged ? () -> o.restoreTransform(after) : null,
                                positionChanged ? () -> o.restoreTransform(before) : null,
                                trackChange));
                    }
                    if (overlayLayer != null) {
                        overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
                        overlayLayer.rebuild();
                    }
                } else if (item.getAudioClip() != null) {
                    AudioClip ac = item.getAudioClip();
                    long beforeOffset = ctrl.getAudioBeforeOffsetMs();
                    long beforeIn = ctrl.getAudioBeforeInMs();
                    long beforeOut = ctrl.getAudioBeforeOutMs();
                    long afterOffset = ac.getOffsetMs();
                    long afterIn = ac.getInPointMs();
                    long afterOut = ac.getOutPointMs();
                    if (kind == com.fadcam.ui.faditor.layers.LayerGestureController.GestureKind.MOVE) {
                        boolean positionChanged = beforeOffset != afterOffset;
                        if (positionChanged || trackChange != null) {
                            String desc = trackChange != null ? trackChange.description : "Move audio clip";
                            undoManager.recordAction(mergedAction(desc,
                                    positionChanged ? () -> ac.setOffsetMs(afterOffset) : null,
                                    positionChanged ? () -> ac.setOffsetMs(beforeOffset) : null,
                                    trackChange));
                        }
                    } else if (beforeIn != afterIn || beforeOut != afterOut) {
                        // TRIM never carries a track change (LayerGestureController only
                        // resolves a drag target for MOVE gestures), so no merge needed.
                        undoManager.recordAction(new EditActions.AudioTrimAction(
                                ac, beforeIn, beforeOut, afterIn, afterOut));
                    } else {
                        maybeRecordTrackOnlyChange(trackChange);
                    }
                    editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
                    prepareAudioPlayer();
                } else if (item.getClip() != null && item.getClip().isOverlayClip()) {
                    Clip oc = item.getClip();
                    long beforeStart = ctrl.getClipBeforeStartMs();
                    long beforeIn = ctrl.getClipBeforeInMs();
                    long beforeOut = ctrl.getClipBeforeOutMs();
                    long afterStart = oc.getOverlayStartMs();
                    long afterIn = oc.getInPointMs();
                    long afterOut = oc.getOutPointMs();
                    if (kind == com.fadcam.ui.faditor.layers.LayerGestureController.GestureKind.MOVE) {
                        boolean positionChanged = beforeStart != afterStart;
                        if (positionChanged || trackChange != null) {
                            String desc = trackChange != null ? trackChange.description : "Move PiP";
                            undoManager.recordAction(mergedAction(desc,
                                    positionChanged ? () -> oc.setOverlayStartMs(afterStart) : null,
                                    positionChanged ? () -> oc.setOverlayStartMs(beforeStart) : null,
                                    trackChange));
                        }
                    } else {
                        boolean startChanged = beforeStart != afterStart;
                        boolean trimChanged = beforeIn != afterIn || beforeOut != afterOut;
                        if (startChanged || trimChanged) {
                            String desc = trackChange != null ? trackChange.description : "Trim PiP";
                            undoManager.recordAction(mergedAction(desc,
                                    () -> { oc.setOverlayStartMs(afterStart); oc.setInPointMs(afterIn); oc.setOutPointMs(afterOut); },
                                    () -> { oc.setOverlayStartMs(beforeStart); oc.setInPointMs(beforeIn); oc.setOutPointMs(beforeOut); },
                                    trackChange));
                        } else {
                            maybeRecordTrackOnlyChange(trackChange);
                        }
                    }
                }
                syncTimelineOverlays();
                scheduleAutoSave();
            }

            @Override
            public void onItemDeleteRequested(@NonNull com.fadcam.ui.faditor.layers.Track track,
                    @NonNull com.fadcam.ui.faditor.layers.TimedItem item) {
                if (item.getTextOverlay() != null) {
                    // Reuse the EXACT existing text/image-overlay delete confirmation
                    // (see onOverlayLayerLongPressed above) so the affordance and undo
                    // step are identical regardless of which surface triggered it.
                    deleteTextOverlayWithConfirmation(item.getTextOverlay());
                } else if (item.getAudioClip() != null) {
                    deleteAudioClipWithConfirmation(item.getAudioClip());
                } else if (item.getClip() != null && item.getClip().isOverlayClip()) {
                    deleteOverlayClipWithConfirmation(item.getClip());
                } else if (item.getWaveform() != null) {
                    // Layers-UX Slice C: visualizer delete now rides the selection badge
                    // (the old long-press-on-bar path is retired with drawLayers).
                    deleteVisualizerWithConfirmation(item.getWaveform());
                }
            }

            @Override
            public void onItemMovedToTrack(@NonNull com.fadcam.ui.faditor.layers.TimedItem item,
                    @NonNull com.fadcam.ui.faditor.layers.Track fromTrack,
                    @NonNull com.fadcam.ui.faditor.layers.Track toTrack) {
                pendingLayerTrackUndo = stageMoveItemToLayerTrack(item, fromTrack.getId(), toTrack.getId());
            }

            @Override
            public void onItemDroppedOnNewLayer(@NonNull com.fadcam.ui.faditor.layers.TimedItem item,
                    @NonNull com.fadcam.ui.faditor.layers.Track fromTrack) {
                pendingLayerTrackUndo = stageCreateLayerAndMoveItem(item, fromTrack);
            }

            @Override
            public void onItemDoubleTapped(@NonNull com.fadcam.ui.faditor.layers.TimedItem item) {
                // G1 (gesture contract §1): double-tap a layer-row item = the express lane
                // to that object's type editor / power-tools drawer. The item is already
                // selected (first tap); here we just open the right editor per payload type.
                if (item.getTextOverlay() != null) {
                    showTextOverlayEditor(item.getTextOverlay());
                } else if (item.getSprite() != null) {
                    openSpritePalette();
                } else if (item.getWaveform() != null) {
                    showVisualizerDrawer(true);
                }
                // Audio / PiP: no dedicated type editor exists yet — the selection from the
                // first tap stands, and double-tap will route here once those power-tools
                // drawers land (gesture contract §2 general menu / per-type editors).
            }

            @Override
            public void onItemMenuRequested(@NonNull com.fadcam.ui.faditor.layers.Track track,
                    @NonNull com.fadcam.ui.faditor.layers.TimedItem item) {
                // G1→G2 (gesture contract §1/§2): hold → release-in-place opens the
                // object's general advanced menu — now the G2 peek/expand bottom sheet:
                // peek = active property row + diamond with the timeline still live;
                // expand = full property/action menu; More… = the double-tap type editor.
                if (item.getTextOverlay() != null) {
                    showObjectMenuSheetForTextOverlay(item.getTextOverlay());
                } else if (item.getSprite() != null) {
                    showObjectMenuSheetForSprite(item.getSprite());
                }
                // Audio / PiP / visualizer: no general menu yet — the item stays
                // lifted-then-dropped-in-place with no side effect (the pickup already gave
                // haptic feedback), wired as their §2 Prop adapters come online.
            }

            @Override
            public void onItemSelectionChanged(
                    @Nullable com.fadcam.ui.faditor.layers.Track track,
                    @Nullable com.fadcam.ui.faditor.layers.TimedItem item) {
                // G4 (gesture contract §1): tap-select spawns manipulation handles
                // in the preview; deselect (or selecting a type without a handles
                // target yet) hides them.
                updatePreviewHandlesForSelection(item);
                // G7 (contract §6/§7): first-ever selection teaches the invisible
                // per-item gestures (double-tap / hold / drag) once.
                if (item != null) maybeShowGestureCoachMark();
                // Audio consolidation: selecting/deselecting an audio ROW item replaces the
                // legacy onAudioClipSelected side effect — keep the open transcript panel
                // following the audio selection (getSelectedAudioIndex now derives from
                // this same unified selection, so the panel reads the right clip).
                if ((item == null || item.getAudioClip() != null)
                        && transcriptPanel != null
                        && transcriptPanel.getVisibility() == View.VISIBLE) {
                    loadTranscriptPanelContent();
                }
            }
        };
    }

    /**
     * M10: the track-mutation half of a completed cross-row drag, staged by
     * {@code onItemMovedToTrack}/{@code onItemDroppedOnNewLayer} and picked up by the
     * immediately-following {@code onGestureFinished} call (same gesture — see
     * {@code LayerGestureController#onRowBodyUp}) so BOTH halves land in exactly one
     * {@code undoStack} entry (PLAN M10 acceptance (d)). {@code redo}/{@code undo} only
     * touch the {@code layerId} (+ track creation/pruning); the position-change
     * redo/undo (if any) is layered around these by {@code mergedAction}.
     */
    private static final class PendingLayerTrackUndo {
        final String description;
        final Runnable redo, undo;
        PendingLayerTrackUndo(@NonNull String description, @NonNull Runnable redo, @NonNull Runnable undo) {
            this.description = description;
            this.redo = redo;
            this.undo = undo;
        }
    }

    /** Set by onItemMovedToTrack/onItemDroppedOnNewLayer, consumed by the very next onGestureFinished. */
    @Nullable private PendingLayerTrackUndo pendingLayerTrackUndo;

    /**
     * Builds ONE {@code EditActions.LambdaAction} covering the position-change redo/undo
     * (nullable — a drag can change track without changing time) AND the staged
     * track-change redo/undo (nullable — a drag can change time without changing track),
     * running the track half AFTER the position half on redo and BEFORE it on undo (undo
     * order mirrors "last mutation applied, first mutation reverted"), so the merged
     * action is a faithful single step regardless of which half(es) are present. At
     * least one of {@code positionRedo}/{@code trackChange} is non-null whenever this is
     * called (callers only call it when they already know something changed).
     */
    @NonNull
    private EditActions.LambdaAction mergedAction(@NonNull String description,
            @Nullable Runnable positionRedo, @Nullable Runnable positionUndo,
            @Nullable PendingLayerTrackUndo trackChange) {
        return new EditActions.LambdaAction(description,
                () -> {
                    if (positionRedo != null) positionRedo.run();
                    if (trackChange != null) trackChange.redo.run();
                },
                () -> {
                    if (trackChange != null) trackChange.undo.run();
                    if (positionUndo != null) positionUndo.run();
                });
    }

    /**
     * A MOVE gesture can end with a track change but NO position change (e.g. the
     * before-snapshot was unavailable, or the finger moved purely vertically) — in that
     * case {@code onGestureFinished}'s normal "did the position change" guard would
     * otherwise silently DROP the already-applied track mutation's undo step. Called
     * from every early-return path in {@code onGestureFinished} so a track-only change
     * is never lost.
     */
    private void maybeRecordTrackOnlyChange(@Nullable PendingLayerTrackUndo trackChange) {
        if (trackChange == null) return;
        undoManager.recordAction(mergedAction(trackChange.description, null, null, trackChange));
        syncTimelineOverlays();
        scheduleAutoSave();
    }

    /**
     * M10 glue: apply a cross-row drag (PLAN Part 7 row M10 scope 1) by writing the
     * item's {@code layerId} IMMEDIATELY (so the model/preview are correct as soon as
     * the finger lifts) and returning the staged undo/redo halves for
     * {@code onGestureFinished} to fold into ONE undo action alongside the position
     * change (see {@link #pendingLayerTrackUndo}; PLAN M10 acceptance (d)).
     */
    @Nullable
    private PendingLayerTrackUndo stageMoveItemToLayerTrack(@NonNull com.fadcam.ui.faditor.layers.TimedItem item,
                                       @NonNull String fromTrackId, @NonNull String toTrackId) {
        if (project == null) return null;
        final com.fadcam.ui.faditor.model.TextOverlayItem textPayload = item.getTextOverlay();
        final AudioClip audioPayload = item.getAudioClip();
        final Clip clipPayload = item.getClip();
        if (textPayload == null && audioPayload == null && (clipPayload == null || !clipPayload.isOverlayClip())) return null;
        // "text"/"audio" are the fixed default-track ids the migration always assigns;
        // storing null (rather than the literal string) for a move BACK to the default
        // track keeps old-shaped/never-touched items indistinguishable from ones
        // explicitly re-homed to the default (matches the serializer's omit-when-default
        // convention for layerId — see ProjectStorage).
        final String toStored = ("text".equals(toTrackId) || "audio".equals(toTrackId)) ? null : toTrackId;
        final String fromStored = ("text".equals(fromTrackId) || "audio".equals(fromTrackId)) ? null : fromTrackId;
        // CRITICAL: overlay clip's layerId must NEVER be null (null = master-clip semantics;
        // isOverlayClip() breaks). Store the literal toTrackId always.
        final String clipToStored = toTrackId;
        final String clipFromStored = fromTrackId != null ? fromTrackId : "video";
        if (textPayload != null) textPayload.setLayerId(toStored);
        else if (audioPayload != null) audioPayload.setLayerId(toStored);
        else if (clipPayload != null) clipPayload.setLayerId(clipToStored);
        syncTimelineOverlays();
        maybeRemoveEmptyLayerTrack(fromTrackId);
        return new PendingLayerTrackUndo("Move to layer",
                () -> {
                    if (textPayload != null) textPayload.setLayerId(toStored);
                    else if (audioPayload != null) audioPayload.setLayerId(toStored);
                    else if (clipPayload != null) clipPayload.setLayerId(clipToStored);
                    syncTimelineOverlays();
                    maybeRemoveEmptyLayerTrack(fromTrackId);
                },
                () -> {
                    if (textPayload != null) textPayload.setLayerId(fromStored);
                    else if (audioPayload != null) audioPayload.setLayerId(fromStored);
                    else if (clipPayload != null) clipPayload.setLayerId(clipFromStored);
                    syncTimelineOverlays();
                });
    }

    /**
     * M10 glue: drop-to-new-layer (PLAN Part 7 row M10 scope 2). Creates a new
     * persistent track definition matching the dragged item's own band/kind (a TEXT/
     * STICKER item creates a TEXT track; an AUDIO item creates an AUDIO track — cross-
     * band drops are not offered by the gesture controller, see
     * {@code LayerGestureController#updateDragTarget}'s same-band guard), reassigns the
     * item to it IMMEDIATELY, and returns the staged undo/redo halves (track creation +
     * move) for {@code onGestureFinished} to fold into ONE undo action alongside the
     * position change (undoing removes the item from the new track; since the track was
     * created empty and only this item was ever added, the resulting empty track is
     * pruned by the same {@link #maybeRemoveEmptyLayerTrack} helper the cross-row move
     * uses).
     */
    @Nullable
    private PendingLayerTrackUndo stageCreateLayerAndMoveItem(@NonNull com.fadcam.ui.faditor.layers.TimedItem item,
                                         @NonNull com.fadcam.ui.faditor.layers.Track fromTrack) {
        if (project == null || editorTimeline == null) return null;
        final com.fadcam.ui.faditor.model.TextOverlayItem textPayload = item.getTextOverlay();
        final AudioClip audioPayload = item.getAudioClip();
        if (textPayload == null && audioPayload == null) return null;
        final Timeline timeline = project.getTimeline();
        boolean floatingBand = editorTimeline.isLayerTrackFloatingBand(fromTrack);
        com.fadcam.ui.faditor.layers.TrackKind newKind = floatingBand
                ? com.fadcam.ui.faditor.layers.TrackKind.TEXT
                : com.fadcam.ui.faditor.layers.TrackKind.AUDIO;
        int nextNum = (floatingBand ? timeline.getLayers().size() : timeline.getAudioTracks().size()) + 1;
        String newName = (floatingBand ? "Text " : "Audio ") + nextNum;
        final String newTrackId = timeline.createLayerTrack(newKind, newName);
        final com.fadcam.ui.faditor.layers.LayerTrackDef createdDef =
                timeline.getLayerTrackDef(newTrackId);
        final String fromTrackId = fromTrack.getId();
        final String fromStored = ("text".equals(fromTrackId) || "audio".equals(fromTrackId)) ? null : fromTrackId;

        if (textPayload != null) textPayload.setLayerId(newTrackId);
        else audioPayload.setLayerId(newTrackId);
        syncTimelineOverlays();
        maybeRemoveEmptyLayerTrack(fromTrackId);
        return new PendingLayerTrackUndo("New layer",
                () -> {
                    if (createdDef != null) timeline.restoreLayerTrackDef(createdDef);
                    if (textPayload != null) textPayload.setLayerId(newTrackId);
                    else audioPayload.setLayerId(newTrackId);
                    syncTimelineOverlays();
                    maybeRemoveEmptyLayerTrack(fromTrackId);
                },
                () -> {
                    if (textPayload != null) textPayload.setLayerId(fromStored);
                    else audioPayload.setLayerId(fromStored);
                    timeline.removeLayerTrackDef(newTrackId);
                    syncTimelineOverlays();
                });
    }

    /**
     * PLAN Part 7 row M10 scope 2: "deleting the last item of a non-migrated layer
     * removes the empty track." Removes {@code trackId}'s {@code LayerTrackDef} if it
     * is a user-created track (no-op for the fixed "text"/"audio" ids — they always
     * exist) AND it no longer has any items pointing at it. Called after every move/
     * delete that could have emptied a track.
     */
    private void maybeRemoveEmptyLayerTrack(@NonNull String trackId) {
        if (project == null) return;
        if ("text".equals(trackId) || "audio".equals(trackId)) return;
        Timeline timeline = project.getTimeline();
        if (timeline.getLayerTrackDef(trackId) == null) return; // not a user-created track
        if (timeline.layerTrackHasItems(trackId)) return;
        timeline.removeLayerTrackDef(trackId);
    }

    /**
     * Exposes {@code editorTimeline}'s M7 gesture controller for the callback above to
     * read its before-gesture snapshot. Package-private accessor kept private/local since
     * only this activity's callback needs it.
     */
    @Nullable
    private com.fadcam.ui.faditor.layers.LayerGestureController editorTimelineGestureController() {
        return editorTimeline != null ? editorTimeline.getLayerGestureController() : null;
    }
    // (editorTimeline itself may be null very early in onCreate, hence the null check above
    // even though the view method itself is @NonNull once constructed.)

    /**
     * Delete confirmation for a text/image overlay triggered from a ROW gesture (M7).
     * Mirrors {@code onOverlayLayerLongPressed} exactly (same dialog copy, same
     * {@code LambdaAction} undo pattern) so the two entry points are indistinguishable
     * to the user and to undo history.
     */
    private void deleteTextOverlayWithConfirmation(@NonNull com.fadcam.ui.faditor.model.TextOverlayItem o) {
        if (project == null) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(o.isImage() ? "Remove image overlay?" : "Remove text overlay?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    project.getTimeline().removeTextOverlay(o);
                    undoManager.recordAction(new EditActions.LambdaAction(
                            o.isImage() ? "Delete image overlay" : "Delete text overlay",
                            () -> project.getTimeline().removeTextOverlay(o),
                            () -> project.getTimeline().addTextOverlay(o)));
                    syncTimelineOverlays();
                    if (overlayLayer != null) {
                        overlayLayer.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleTextOverlays(project.getTimeline()), overlayLayerCallback());
                        overlayLayer.invalidate();
                    }
                    editorTimeline.invalidate();
                    scheduleAutoSave();
                    Toast.makeText(FaditorEditorActivity.this, "Overlay removed", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /**
     * Delete confirmation for an audio clip triggered from a ROW gesture (M7). Mirrors
     * {@code deleteSelectedAudioClip} exactly (same {@code DeleteAudioClipAction} undo,
     * same audio-player re-prepare) behind a confirmation dialog (row long-press has no
     * dedicated trash button to route through, unlike the selected-audio-clip + toolbar
     * trash-icon path).
     */
    private void deleteAudioClipWithConfirmation(@NonNull AudioClip clip) {
        if (project == null) return;
        Timeline timeline = project.getTimeline();
        int index = timeline.getAudioClips().indexOf(clip);
        if (index < 0) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Remove audio clip?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    undoManager.recordAction(new EditActions.DeleteAudioClipAction(timeline, clip, index));
                    timeline.removeAudioClip(clip);
                    editorTimeline.setAudioClips(timeline.getAudioClips());
                    syncTimelineOverlays();
                    editorTimeline.invalidate();
                    releaseAudioPlayer();
                    if (timeline.hasAudioClips()) {
                        prepareAudioPlayer();
                    }
                    scheduleAutoSave();
                    Toast.makeText(FaditorEditorActivity.this, "Audio clip removed", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void deleteOverlayClipWithConfirmation(@NonNull Clip clip) {
        if (project == null) return;
        Timeline timeline = project.getTimeline();
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Remove video overlay?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    undoManager.recordAction(new EditActions.LambdaAction("Remove video overlay",
                            () -> timeline.removeOverlayClip(clip),
                            () -> timeline.addOverlayClip(clip)));
                    timeline.removeOverlayClip(clip);
                    syncTimelineOverlays();
                    editorTimeline.invalidate();
                    scheduleAutoSave();
                    Toast.makeText(FaditorEditorActivity.this, "Video overlay removed", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /**
     * Layers-UX Slice C: delete a visualizer via the new selection delete-badge, preserving the
     * EXACT removal + undo the retired long-press-on-old-bar path used ({@code onVisualizerLayerLongPressed}).
     */
    private void deleteVisualizerWithConfirmation(
            @NonNull com.fadcam.ui.faditor.model.WaveformOverlayInstance wv) {
        if (project == null) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Remove visualizer?")
                .setMessage("This removes the visualizer overlay from the timeline.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    project.getTimeline().removeWaveformOverlay(wv);
                    undoManager.recordAction(new EditActions.LambdaAction("Remove visualizer",
                            () -> project.getTimeline().removeWaveformOverlay(wv),
                            () -> project.getTimeline().addWaveformOverlay(wv)));
                    if (waveformOverlayView != null) {
                        waveformOverlayView.setOverlays(project.getTimeline().getWaveformOverlays());
                        waveformOverlayView.invalidate();
                    }
                    syncTimelineOverlays();
                    editorTimeline.invalidate();
                    scheduleAutoSave();
                    Toast.makeText(FaditorEditorActivity.this, "Visualizer removed",
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /**
     * Manual word-edit dialog (the companion to the AI {@code correct_transcript} tool):
     * long-pressing a transcript word opens this so the user can fix a mis-transcribed
     * word. Typing multiple words splits the original word's time span across them;
     * clearing the text deletes the word.
     */
    private void showWordEditDialog(int index, @NonNull String currentText) {
        final float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);
        final Clip clip = getSelectedClip();
        final long segStart = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
        final float speed = Math.max(0.01f, clip.getSpeedMultiplier());
        final long clipIn = clip.getInPointMs();
        final long frameMs = 33L; // ≈ one frame at 30fps

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad / 2, pad, 0);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(currentText);
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setTextColor(0xFFFFFFFF);
        col.addView(input);

        // ── Timing row: [◄ frame] [absolute timestamp box, tap to type] [► frame] ──
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        timeRow.setPadding(0, (int) (14 * dp), 0, 0);
        android.graphics.Typeface iconFont =
                androidx.core.content.res.ResourcesCompat.getFont(this, R.font.materialicons);

        TextView prev = new TextView(this), next = new TextView(this);
        for (TextView c : new TextView[]{prev, next}) {
            if (iconFont != null) c.setTypeface(iconFont);
            c.setTextSize(26);
            c.setTextColor(0xFFCCCCCC);
            c.setGravity(android.view.Gravity.CENTER);
            c.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
            c.setBackgroundResource(R.drawable.floating_button_item_bg);
        }
        prev.setText("chevron_left");
        next.setText("chevron_right");

        final TextView tsBox = new TextView(this);
        tsBox.setTextColor(0xFF4DD0E1);
        tsBox.setTextSize(18);
        tsBox.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tsBox.setGravity(android.view.Gravity.CENTER);
        tsBox.setPadding((int) (14 * dp), (int) (8 * dp), (int) (14 * dp), (int) (8 * dp));
        tsBox.setBackgroundResource(R.drawable.settings_home_row_bg);

        // Renders the word's current ABSOLUTE timeline start into the box.
        final Runnable refreshTs = () -> {
            com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(index);
            if (w == null) return;
            long absMs = segStart + (long) ((w.startMs - clipIn) / speed);
            tsBox.setText(formatTsMs(Math.max(0, absMs)));
        };
        refreshTs.run();

        // Apply a new ABSOLUTE start to the word (live: updates the panel + inline timeline + caption).
        final java.util.function.LongConsumer applyAbs = absMs -> {
            long srcStart = clipIn + (long) (Math.max(0, absMs) * speed) - (long) (segStart * speed);
            transcriptView.setWordStart(index, Math.max(0, srcStart));
            refreshTs.run();
            syncTimelineTranscript();
            editorTimeline.invalidate();
            if (captionsActive && clip.hasTranscript()) bindCaptionData(clip);
            scheduleAutoSave();
        };
        prev.setOnClickListener(v -> {
            com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(index);
            if (w == null) return;
            long absMs = segStart + (long) ((w.startMs - clipIn) / speed);
            applyAbs.accept(absMs - frameMs);
        });
        next.setOnClickListener(v -> {
            com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(index);
            if (w == null) return;
            long absMs = segStart + (long) ((w.startMs - clipIn) / speed);
            applyAbs.accept(absMs + frameMs);
        });
        // Tap the timestamp box → type an exact value
        tsBox.setOnClickListener(v -> {
            com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(index);
            if (w == null) return;
            long curAbs = segStart + (long) ((w.startMs - clipIn) / speed);
            showExactTimeDialog(curAbs, applyAbs);
        });

        timeRow.addView(prev);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tsLp.setMarginStart((int) (10 * dp));
        tsLp.setMarginEnd((int) (10 * dp));
        timeRow.addView(tsBox, tsLp);
        timeRow.addView(next);
        col.addView(timeRow);

        TextView hint = new TextView(this);
        hint.setText("Tap the time to type an exact value · ◄ ► nudge ±1 frame");
        hint.setTextColor(0xFF888888);
        hint.setTextSize(11);
        hint.setPadding(0, (int) (8 * dp), 0, 0);
        col.addView(hint);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Edit word")
                .setView(col)
                .setPositiveButton("Save", (d, w) -> {
                    transcriptView.editWord(index, input.getText().toString());
                    syncRemovedSpansFromTranscript();
                    syncTimelineTranscript();
                    if (captionsActive && clip.hasTranscript()) bindCaptionData(clip);
                    scheduleAutoSave();
                })
                .setNegativeButton("Close", null)
                .show();
        input.requestFocus();
    }

    /** mm:ss.SSS compact timestamp for the word-edit box. */
    private String formatTsMs(long ms) {
        long s = ms / 1000;
        long millis = ms % 1000;
        return String.format(java.util.Locale.US, "%d:%02d.%03d", s / 60, s % 60, millis);
    }

    private long parseTsMs(String text) throws NumberFormatException {
        text = text.trim();
        if (text.isEmpty()) throw new NumberFormatException("Empty input");
        String[] parts = text.split(":");
        if (parts.length == 1) {
            double secs = Double.parseDouble(parts[0]);
            return (long) (secs * 1000.0);
        } else if (parts.length == 2) {
            long mins = Long.parseLong(parts[0]);
            double secs = Double.parseDouble(parts[1]);
            return mins * 60000L + (long) (secs * 1000.0);
        } else if (parts.length == 3) {
            long hours = Long.parseLong(parts[0]);
            long mins = Long.parseLong(parts[1]);
            double secs = Double.parseDouble(parts[2]);
            return hours * 3600000L + mins * 60000L + (long) (secs * 1000.0);
        } else {
            throw new NumberFormatException("Invalid format: " + text);
        }
    }

    private void showExactTimeDialog(long curAbs, @NonNull java.util.function.LongConsumer onTimeSet) {
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(pad, pad / 2, pad, pad / 2);

        TextView origTimeView = new TextView(this);
        origTimeView.setText(formatTsMs(curAbs));
        origTimeView.setTextColor(0xFF888888);
        origTimeView.setTextSize(16);
        row.addView(origTimeView);

        TextView arrowView = new TextView(this);
        arrowView.setText(" → ");
        arrowView.setTextColor(0xFF888888);
        arrowView.setTextSize(16);
        row.addView(arrowView);

        final android.widget.EditText tin = new android.widget.EditText(this);
        tin.setText(formatTsMs(curAbs));
        tin.setSelectAllOnFocus(true);
        tin.setSingleLine(true);
        tin.setTextColor(0xFF4DD0E1);
        tin.setTextSize(16);
        tin.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(tin, lp);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Exact start time")
                .setView(row)
                .setPositiveButton("Set", (d, which) -> {
                    try {
                        long newAbs = parseTsMs(tin.getText().toString());
                        onTimeSet.accept(newAbs);
                    } catch (Exception e) {
                        Toast.makeText(this, "Invalid time format", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        tin.post(() -> {
            tin.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(tin, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    /** Absolute timeline start of the word at {@code index} (respects clip speed + trim). */
    private long wordAbsoluteStart(int index) {
        com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(index);
        if (w == null) return 0;
        Clip clip = getSelectedClip();
        if (clip == null) return w.startMs;
        long segStart = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
        float speed = Math.max(0.01f, clip.getSpeedMultiplier());
        long clipIn = clip.getInPointMs();
        return segStart + (long) ((w.startMs - clipIn) / speed);
    }

    /** Apply a new ABSOLUTE timeline start to the word at {@code index} (live update). */
    private void applyWordAbsStart(int index, long absMs) {
        com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(index);
        if (w == null) return;
        Clip clip = getSelectedClip();
        if (clip == null) return;
        long segStart = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
        float speed = Math.max(0.01f, clip.getSpeedMultiplier());
        long clipIn = clip.getInPointMs();
        long srcStart = clipIn + (long) (Math.max(0, absMs) * speed) - (long) (segStart * speed);
        transcriptView.setWordStart(index, Math.max(0, srcStart));
        refreshWordScrubChrome();
        syncTimelineTranscript();
        editorTimeline.invalidate();
        if (captionsActive && clip.hasTranscript()) bindCaptionData(clip);
        scheduleAutoSave();
    }

    /**
     * Show the non-modal word scrub drawer (replaces the old AlertDialog).
     * The drawer sits at the top so the bottom timeline stays visible while
     * the user scrubs the word's timing with the acceleration strip.
     */
    private void showWordScrubDrawer(int index) {
        if (wordScrubDrawer == null || transcriptView == null) return;
        wordScrubCurrentIndex = index;
        wordScrubGroupLength = 1;
        wireWordScrubDrawer();

        // Close other drawers
        if (volumeDrawerOpen) hideVolumeDrawer();
        if (transitionPanelOpen) showTransitionPanel(false);
        if (visualizerDrawerOpen) showVisualizerDrawer(false);

        com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(index);
        if (w != null && wordScrubWordText != null) {
            wordScrubWordText.setText(w.text);
        }
        refreshWordScrubChrome();

        wordScrubDrawerOpen = true;
        wordScrubDrawer.setVisibility(View.VISIBLE);
        wordScrubDrawer.post(() -> {
            wordScrubDrawer.setTranslationY(-wordScrubDrawer.getHeight());
            wordScrubDrawer.animate().translationY(0f).setDuration(180).start();
        });
    }

    /** Apply a delta (ms) to every word in the current scrub group. */
    private void applyWordGroupDelta(long deltaMs) {
        if (wordScrubCurrentIndex < 0) return;
        Clip clip = getSelectedClip();
        if (clip == null) return;
        float speed = Math.max(0.01f, clip.getSpeedMultiplier());
        for (int i = 0; i < wordScrubGroupLength; i++) {
            int idx = wordScrubCurrentIndex + i;
            com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(idx);
            if (w == null) continue;
            long srcStart = w.startMs + (long) (deltaMs * speed);
            transcriptView.setWordStart(idx, Math.max(0, srcStart));
        }
        refreshWordScrubChrome();
        syncTimelineTranscript();
        editorTimeline.invalidate();
        if (captionsActive && clip.hasTranscript()) bindCaptionData(clip);
        scheduleAutoSave();
    }

    private void hideWordScrubDrawer() {
        if (wordScrubDrawer == null || !wordScrubDrawerOpen) return;
        wordScrubDrawerOpen = false;
        wordScrubCurrentIndex = -1;
        wordScrubGroupLength = 1;
        if (wordScrubStrip != null) wordScrubStrip.reset();
        wordScrubDrawer.animate().translationY(-wordScrubDrawer.getHeight()).setDuration(160)
                .withEndAction(() -> wordScrubDrawer.setVisibility(View.GONE)).start();
    }

    private void refreshWordScrubChrome() {
        if (wordScrubCurrentIndex < 0 || wordScrubTimestamp == null) return;
        com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(wordScrubCurrentIndex);
        if (w != null) {
            long absMs = wordAbsoluteStart(wordScrubCurrentIndex);
            wordScrubTimestamp.setText(formatTsMs(absMs));
        }
    }

    private boolean wordScrubDrawerWired = false;

    private void wireWordScrubDrawer() {
        if (wordScrubDrawerWired || wordScrubDrawer == null) return;
        wordScrubDrawerWired = true;

        View close = wordScrubDrawer.findViewById(R.id.word_scrub_close);
        if (close != null) close.setOnClickListener(v -> hideWordScrubDrawer());

        // EditText word editor: commit on IME action or focus loss.
        if (wordScrubWordText != null) {
            wordScrubWordText.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    commitWordTextEdit();
                    return true;
                }
                return false;
            });
            wordScrubWordText.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) commitWordTextEdit();
            });
        }

        // Prev/Next nudge the whole word group together.
        if (wordScrubPrev != null) {
            wordScrubPrev.setOnClickListener(v -> {
                if (wordScrubCurrentIndex < 0) return;
                applyWordGroupDelta(-33L);
            });
        }
        if (wordScrubNext != null) {
            wordScrubNext.setOnClickListener(v -> {
                if (wordScrubCurrentIndex < 0) return;
                applyWordGroupDelta(33L);
            });
        }

        if (wordScrubTimestamp != null) {
            wordScrubTimestamp.setOnClickListener(v -> {
                if (wordScrubCurrentIndex < 0) return;
                com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(wordScrubCurrentIndex);
                if (w == null) return;
                long curAbs = wordAbsoluteStart(wordScrubCurrentIndex);
                showExactTimeDialog(curAbs, newAbs -> {
                    applyWordGroupDelta(newAbs - curAbs);
                });
            });
        }

        if (wordScrubCenter != null) {
            wordScrubCenter.setOnClickListener(v -> centerSelectedWord());
        }

        // WordScrubView listener — scrub applies to every word in the group.
        if (wordScrubStrip != null) {
            wordScrubStrip.setListener(new com.fadcam.ui.faditor.WordScrubView.Listener() {
                @Override
                public void onScrubStarted() {
                    if (wordScrubCurrentIndex < 0) return;
                    wordScrubOriginalStarts = new long[wordScrubGroupLength];
                    for (int i = 0; i < wordScrubGroupLength; i++) {
                        com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(wordScrubCurrentIndex + i);
                        wordScrubOriginalStarts[i] = (w != null) ? w.startMs : 0L;
                    }
                }

                @Override
                public void onWordDeltaMs(long deltaMs) {
                    if (wordScrubCurrentIndex < 0 || wordScrubOriginalStarts == null) return;
                    Clip clip = getSelectedClip();
                    if (clip == null) return;
                    float speed = Math.max(0.01f, clip.getSpeedMultiplier());
                    
                    for (int i = 0; i < wordScrubGroupLength; i++) {
                        if (i >= wordScrubOriginalStarts.length) break;
                        int idx = wordScrubCurrentIndex + i;
                        com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(idx);
                        if (w == null) continue;
                        long srcStart = wordScrubOriginalStarts[i] + (long) (deltaMs * speed);
                        transcriptView.setWordStart(idx, Math.max(0, srcStart));
                    }
                    refreshWordScrubChrome();
                    syncTimelineTranscript();
                    editorTimeline.invalidate();
                    if (captionsActive && clip.hasTranscript()) bindCaptionData(clip);
                    scheduleAutoSave();
                }

                @Override
                public void onScrubFinished() {
                    wordScrubOriginalStarts = null;
                    if (wordScrubStrip != null) wordScrubStrip.reset();
                }
            });
        }

        // Swipe up on the drawer header to dismiss
        View.OnTouchListener swipeUp = new View.OnTouchListener() {
            float downY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downY = e.getRawY(); return true;
                    case MotionEvent.ACTION_UP:
                        if (downY - e.getRawY() > 40f * getResources().getDisplayMetrics().density) {
                            hideWordScrubDrawer();
                        }
                        return true;
                }
                return false;
            }
        };
        wordScrubDrawer.setOnTouchListener(swipeUp);
    }

    /** Commit the word text edit: replace current word(s) via transcriptView.editWord. */
    private void commitWordTextEdit() {
        if (wordScrubCurrentIndex < 0 || transcriptView == null) return;
        String raw = wordScrubWordText.getText().toString();
        String[] toks = raw.trim().isEmpty() ? new String[0] : raw.trim().split("\\s+");
        // Skip if unchanged single word
        if (toks.length == 1 && transcriptView.getWord(wordScrubCurrentIndex) != null
                && toks[0].equals(transcriptView.getWord(wordScrubCurrentIndex).text)) {
            return;
        }
        transcriptView.editWord(wordScrubCurrentIndex, raw);
        wordScrubGroupLength = toks.length;
        refreshWordScrubChrome();
        syncTimelineTranscript();
        editorTimeline.invalidate();
        Clip clip = getSelectedClip();
        if (clip != null && captionsActive && clip.hasTranscript()) bindCaptionData(clip);
        scheduleAutoSave();
    }

    private void centerSelectedWord() {
        if (wordScrubCurrentIndex < 0 || transcriptView == null) return;
        Clip clip = getSelectedClip();
        if (clip == null) return;

        long t_A_end;
        if (wordScrubCurrentIndex > 0) {
            com.fadcam.ui.faditor.transcript.TranscriptWord prevWord = transcriptView.getWord(wordScrubCurrentIndex - 1);
            t_A_end = prevWord != null ? prevWord.endMs : clip.getInPointMs();
        } else {
            t_A_end = clip.getInPointMs();
        }

        int nextIdx = wordScrubCurrentIndex + wordScrubGroupLength;
        long t_C_start;
        if (nextIdx < transcriptView.getWordCount()) {
            com.fadcam.ui.faditor.transcript.TranscriptWord nextWord = transcriptView.getWord(nextIdx);
            t_C_start = nextWord != null ? nextWord.startMs : clip.getOutPointMs();
        } else {
            t_C_start = clip.getOutPointMs();
        }

        com.fadcam.ui.faditor.transcript.TranscriptWord firstWord = transcriptView.getWord(wordScrubCurrentIndex);
        com.fadcam.ui.faditor.transcript.TranscriptWord lastWord = transcriptView.getWord(wordScrubCurrentIndex + wordScrubGroupLength - 1);
        if (firstWord == null || lastWord == null) return;

        long groupDur = lastWord.endMs - firstWord.startMs;
        if (groupDur < 0) groupDur = 0;

        long newStartMs = (t_A_end + t_C_start - groupDur) / 2;
        if (newStartMs < 0) newStartMs = 0;

        long deltaMs = newStartMs - firstWord.startMs;

        for (int i = 0; i < wordScrubGroupLength; i++) {
            int idx = wordScrubCurrentIndex + i;
            com.fadcam.ui.faditor.transcript.TranscriptWord w = transcriptView.getWord(idx);
            if (w == null) continue;
            transcriptView.setWordStart(idx, Math.max(0, w.startMs + deltaMs));
        }

        refreshWordScrubChrome();
        syncTimelineTranscript();
        editorTimeline.invalidate();
        if (captionsActive && clip.hasTranscript()) bindCaptionData(clip);
        scheduleAutoSave();
        Toast.makeText(this, "Centered word", Toast.LENGTH_SHORT).show();
    }

    /** Push the current transcript to the timeline so words show below segments. */
    private void syncTimelineTranscript() {
        if (editorTimeline == null || project == null) return;
        Timeline tl = project.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            com.fadcam.ui.faditor.transcript.NamedTranscript nt = c.getActiveNamedTranscript();
            if (nt != null) {
                editorTimeline.setSegmentTranscript(i, nt.transcript);
            } else {
                editorTimeline.setSegmentTranscript(i, null);
            }
        }
        // Sync audio clip transcripts
        for (int i = 0; i < tl.getAudioClips().size(); i++) {
            AudioClip ac = tl.getAudioClips().get(i);
            com.fadcam.ui.faditor.transcript.NamedTranscript nt = ac.getActiveNamedTranscript();
            if (nt != null) {
                editorTimeline.setAudioClipTranscript(i, nt.transcript);
            } else {
                editorTimeline.setAudioClipTranscript(i, null);
            }
        }
    }

    private void setupOverlayLayer() {
        if (overlayLayer == null || project == null) return;
        overlayLayer.setSnapEnabled(overlaySoftSnapEnabled);
        overlayLayer.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleTextOverlays(project.getTimeline()), overlayLayerCallback());
        syncTimelineOverlays();
        // Reposition overlays whenever the preview area changes. On a SIZE change (rotation, or a
        // preview/timeline split), RE-FLOW the whole preview — the canvas rect + every explicitly-
        // sized preview view are pinned to the container size in applyCanvasFrame(), so without this
        // they stay stuck at the old framing (the "preview went tiny to fit landscape and stayed tiny
        // back in portrait" bug, JoyRaptor 2026-07-07). A position-only change just rebuilds the overlays.
        playerContainer.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> {
                    boolean sizeChanged = (r - l) != (or - ol) || (b - t) != (ob - ot);
                    if (sizeChanged) {
                        reflowPreview();
                    } else if (l != ol || t != ot || r != or || b != ob) {
                        overlayLayer.post(() -> overlayLayer.rebuild());
                    }
                });
    }

    /**
     * Re-flow the entire preview after the player container changes size (rotation, or a
     * preview/timeline split change): recompute the canvas rect + every explicitly-sized preview
     * view via {@link #applyCanvasFrame()}, then — once those have laid out — re-apply the
     * crop/rotation transforms and reposition every overlay. Root fix for "the preview went tiny to
     * fit landscape and stayed tiny back in portrait" (JoyRaptor 2026-07-07): applyCanvasFrame pins pixel
     * sizes to the container, so a resize must recompute them or the old framing sticks.
     */
    private void reflowPreview() {
        if (project == null) return;
        applyCanvasFrame();
        if (playerView == null) return;
        // Defer transforms + rebuilds until the resized preview views have laid out, so
        // updatePreviewTransforms() reads the NEW playerView dimensions (not the stale ones).
        playerView.post(() -> {
            updatePreviewTransforms();
            if (overlayLayer != null) overlayLayer.rebuild();
            if (spriteOverlayView != null) spriteOverlayView.invalidate();
            if (waveformOverlayView != null) waveformOverlayView.invalidate();
            if (captionOverlay != null) captionOverlay.invalidate();
            if (audioCaptionOverlay != null) audioCaptionOverlay.invalidate();
        });
    }

    /**
     * Slice F — "compact lanes" tool (JoyRaptor 2026-07-07): drop every overlay into the FEWEST no-overlap
     * lanes, reclaiming the orphan-lane sprawl (incl. the T8 one-sprite-per-lane sprawl). Snapshots
     * each item's lane id before/after so the whole thing folds into ONE undo step, then re-derives
     * the timeline rows.
     */
    private void compactLayers() {
        if (project == null) return;
        Timeline tl = project.getTimeline();
        java.util.Map<TextOverlayItem, String> textBefore = new java.util.HashMap<>();
        for (TextOverlayItem o : tl.getTextOverlays()) textBefore.put(o, o.getLayerId());
        java.util.Map<com.fadcam.ui.faditor.sprite.SpriteOverlayItem, String> spriteBefore =
                new java.util.HashMap<>();
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : tl.getSpriteOverlays()) {
            spriteBefore.put(s, s.getLayerId());
        }

        int changed = tl.compactOverlayLanes();
        if (changed == 0) {
            Toast.makeText(this, "Layers already compact", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.Map<TextOverlayItem, String> textAfter = new java.util.HashMap<>();
        for (TextOverlayItem o : textBefore.keySet()) textAfter.put(o, o.getLayerId());
        java.util.Map<com.fadcam.ui.faditor.sprite.SpriteOverlayItem, String> spriteAfter =
                new java.util.HashMap<>();
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteBefore.keySet()) {
            spriteAfter.put(s, s.getLayerId());
        }

        undoManager.recordAction(new EditActions.LambdaAction("Compact layers",
                () -> { applyLaneSnapshot(textBefore, spriteBefore); refreshAfterLaneChange(); },
                () -> { applyLaneSnapshot(textAfter, spriteAfter); refreshAfterLaneChange(); }));

        refreshAfterLaneChange();
        Toast.makeText(this, "Compacted " + changed + " layer" + (changed == 1 ? "" : "s"),
                Toast.LENGTH_SHORT).show();
    }

    private void applyLaneSnapshot(
            @NonNull java.util.Map<TextOverlayItem, String> text,
            @NonNull java.util.Map<com.fadcam.ui.faditor.sprite.SpriteOverlayItem, String> sprites) {
        for (java.util.Map.Entry<TextOverlayItem, String> e : text.entrySet()) {
            e.getKey().setLayerId(e.getValue());
        }
        for (java.util.Map.Entry<com.fadcam.ui.faditor.sprite.SpriteOverlayItem, String> e
                : sprites.entrySet()) {
            e.getKey().setLayerId(e.getValue());
        }
    }

    /** Re-derive the timeline rows after a lane-id change (compact / undo / redo) + persist it. */
    private void refreshAfterLaneChange() {
        syncTimelineOverlays();
        if (editorTimeline != null) {
            editorTimeline.requestLayout();
            editorTimeline.invalidate();
        }
        scheduleAutoSave();
    }

    private void toggleOverlaySoftSnap() {
        overlaySoftSnapEnabled = !overlaySoftSnapEnabled;
        if (overlayLayer != null) overlayLayer.setSnapEnabled(overlaySoftSnapEnabled);
        if (btnSoftSnap != null) {
            btnSoftSnap.setTextColor(overlaySoftSnapEnabled ? 0xFF4CAF50 : 0xFF888888);
            btnSoftSnap.setAlpha(overlaySoftSnapEnabled ? 1f : 0.45f);
        }
        Toast.makeText(this,
                overlaySoftSnapEnabled ? R.string.faditor_soft_snap_on : R.string.faditor_soft_snap_off,
                Toast.LENGTH_SHORT).show();
    }

    /** Add a new text overlay at the centre and open its editor. */
    /** Place a waveform/spectrum visualizer over the selected clip, driven by its audio. */
    private void addWaveformVisualizer() {
        if (project == null) return;
        if (inCropMode) exitCropMode(false);
        Clip clip = getSelectedClip();
        if (clip == null) {
            Toast.makeText(this, "Select a clip first", Toast.LENGTH_SHORT).show();
            return;
        }
        com.fadcam.ui.faditor.model.WaveformOverlayInstance wo =
                new com.fadcam.ui.faditor.model.WaveformOverlayInstance("spectrum_mirror");
        wo.setAudioSourceRef(clip.getId());
        long start = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
        long durMs = clip.hasLoopExtension() ? clip.getVisualDurationMs() : clip.getTrimmedDurationMs();
        wo.setTimeRange(start, start + durMs);
        wo.setCenter(0.5f, 0.78f);
        wo.setSize(0.85f, 0.22f);
        final com.fadcam.ui.faditor.model.WaveformOverlayInstance addedWo = wo;
        project.getTimeline().addWaveformOverlay(addedWo);
        undoManager.recordAction(new EditActions.LambdaAction("Add visualizer",
                () -> project.getTimeline().addWaveformOverlay(addedWo),
                () -> project.getTimeline().removeWaveformOverlay(addedWo)));
        refreshWaveformOverlays();
        scheduleAutoSave();
        Toast.makeText(this, "Visualizer added", Toast.LENGTH_SHORT).show();
    }

    /** Open the color/filter editor for the selected clip; live-applies to the preview. */
    private void openFilterSheet() {
        if (project == null) return;
        if (inCropMode) exitCropMode(false);
        Clip clip = getSelectedClip();
        if (clip == null) {
            Toast.makeText(this, "Select a clip first", Toast.LENGTH_SHORT).show();
            return;
        }
        // Snapshot the color grade BEFORE editing so the whole grading session
        // becomes ONE undo entry (recorded on sheet dismiss), rather than nothing.
        final com.fadcam.ui.faditor.effects.EffectStack filterBefore =
                new com.fadcam.ui.faditor.effects.EffectStack(clip.getEffectStack());
        FilterBottomSheet sheet = new FilterBottomSheet();
        sheet.setTarget(clip.getEffectStack(), new FilterBottomSheet.Callback() {
            @Override
            public void onEffectsChanged() {
                // RenderEffect is a cheap GPU View property — apply directly each change (no decode).
                applyPreviewColorGrade(clip);
                updateFilterUI(clip);
                scheduleAutoSave();
            }

            @Override
            public void onCopyToAll() {
                com.fadcam.ui.faditor.effects.EffectStack src = clip.getEffectStack();
                int count = 0;
                for (Clip c : project.getTimeline().getClips()) {
                    if (c != clip) {
                        c.getEffectStack().copyFrom(src);
                        count++;
                    }
                }
                scheduleAutoSave();
                Toast.makeText(FaditorEditorActivity.this,
                        "Look applied to " + count + " clip(s)", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onClosed() {
                // Record one undo entry for the grading session if anything changed.
                com.fadcam.ui.faditor.effects.EffectStack after = clip.getEffectStack();
                if (!filterBefore.equals(after)) {
                    undoManager.recordAction(new EditActions.EffectStackAction(
                            clip, filterBefore, after));
                    saveProjectNow();
                }
            }
        });
        sheet.show(getSupportFragmentManager(), "filterSheet");
    }

    /** Re-tap a visualizer to choose its style from the built-in presets. */
    /**
     * Visualizer chooser: a GRADIENT list (the look) plus quick-cycle ARCHITECTURE toggles (justify,
     * data mode, horizontal mirror) that are orthogonal to the gradient — pick a gradient you like,
     * then cycle only the structural aspect that bothers you. Non-dimming + top-anchored so the live
     * video + seek/play stay visible while you test.
     */
    private void showVisualizerStylePicker(
            @NonNull com.fadcam.ui.faditor.model.WaveformOverlayInstance overlay) {
        java.util.List<com.fadcam.ui.faditor.model.WaveformStyle> styles =
                com.fadcam.ui.faditor.waveform.WaveformStyleIO.loadBuiltins(this);
        // Auto-append any user-saved styles found in the pinned folder (invisible when none exist).
        if (project != null && project.getPinnedAssetDir() != null) {
            styles.addAll(com.fadcam.ui.faditor.waveform.WaveformStyleIO.loadUserStyles(
                    this, android.net.Uri.parse(project.getPinnedAssetDir())));
        }
        if (styles.isEmpty()) return;
        final float dp = getResources().getDisplayMetrics().density;

        // STAGE 2: 3-column Rolodex (left icons · centre style carousel · right gradient carousel).
        View root = buildVisualizerRolodex(overlay, styles);

        // Show in the NON-BLOCKING top drawer (the live canvas stays visible below, so style/colour/
        // sensitivity changes preview live on the real visualizer) instead of a video-blocking dialog.
        android.widget.FrameLayout content = findViewById(R.id.visualizer_drawer_content);
        if (content == null) return;
        content.removeAllViews();
        content.addView(root, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        setupVisualizerDrawerChrome();
        showVisualizerDrawer(true);
    }

    // ── Visualizer Stage-2 Rolodex (3-column: icons · style carousel · gradient carousel) ──

    private interface RolodexSettle { void onSettle(int pos); }

    /**
     * Build the compact 3-column visualizer Rolodex: a top row (sensitivity + save), then
     * LEFT vertical icon toggles (justify/mode/mirror), a CENTRE vertical carousel of style
     * thumbnails, and a RIGHT vertical carousel of gradient/solid swatches. The two carousels
     * snap to centre and scale/fade items by distance (the "Rolodex barrel" look); the centred
     * item applies live to the on-canvas visualizer.
     */
    private View buildVisualizerRolodex(
            @NonNull com.fadcam.ui.faditor.model.WaveformOverlayInstance overlay,
            @NonNull java.util.List<com.fadcam.ui.faditor.model.WaveformStyle> styles) {
        final float dp = getResources().getDisplayMetrics().density;
        int colH = (int) (getResources().getDisplayMetrics().heightPixels * 0.22f);
        android.graphics.Typeface iconFont =
                androidx.core.content.res.ResourcesCompat.getFont(this, R.font.materialicons);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (10 * dp));

        // ── Top row: sensitivity slider + Save (room for more buttons later) ──
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.SeekBar sensSeek = new android.widget.SeekBar(this);
        sensSeek.setMax(100);
        float curSens = overlay.getSensitivityOverride() > 0f ? overlay.getSensitivityOverride() : 1f;
        sensSeek.setProgress(Math.round(Math.max(0f, Math.min(1f, (curSens - 0.5f) / 2.5f)) * 100));
        sensSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                overlay.setSensitivityOverride(0.5f + p / 100f * 2.5f);
                if (waveformOverlayView != null) waveformOverlayView.invalidate();
                scheduleAutoSave();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        TextView sensIcon = new TextView(this);
        if (iconFont != null) sensIcon.setTypeface(iconFont);
        sensIcon.setText("tune");
        sensIcon.setTextColor(0xFF9E9E9E);
        sensIcon.setTextSize(18);
        sensIcon.setPadding(0, 0, (int) (8 * dp), 0);
        topRow.addView(sensIcon);
        LinearLayout.LayoutParams ssp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topRow.addView(sensSeek, ssp);
        TextView saveBtn = new TextView(this);
        saveBtn.setText("💾");
        saveBtn.setTextSize(18);
        saveBtn.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
        saveBtn.setBackgroundResource(R.drawable.settings_home_row_bg);
        saveBtn.setOnClickListener(v -> saveCurrentVisualizerStyle(overlay));
        topRow.addView(saveBtn);
        root.addView(topRow);

        // ── Frequency range + bar count row (compact, below sensitivity) ──
        LinearLayout freqRow = new LinearLayout(this);
        freqRow.setOrientation(LinearLayout.HORIZONTAL);
        freqRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        freqRow.setPadding(0, (int) (2 * dp), 0, 0);

        TextView freqLabel = new TextView(this);
        freqLabel.setText("Hz");
        freqLabel.setTextColor(0xFF9E9E9E);
        freqLabel.setTextSize(11);
        freqLabel.setPadding(0, 0, (int) (6 * dp), 0);
        freqRow.addView(freqLabel);

        TextView lowHzText = new TextView(this);
        lowHzText.setTextSize(11);
        lowHzText.setTextColor(0xFFCCCCCC);
        lowHzText.setMinEms(2);
        freqRow.addView(lowHzText);

        SeekBar lowSeek = new SeekBar(this);
        lowSeek.setMax(200); // 0..200 → 1..2000 Hz (geometric-ish via progress)
        int lowProg = Math.max(0, Math.min(200, overlay.getFrequencyRangeLowHz()));
        lowSeek.setProgress(lowProg);
        LinearLayout.LayoutParams lowLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lowLp.setMargins(0, 0, (int) (4 * dp), 0);
        freqRow.addView(lowSeek, lowLp);

        TextView highHzText = new TextView(this);
        highHzText.setTextSize(11);
        highHzText.setTextColor(0xFFCCCCCC);
        highHzText.setMinEms(3);
        freqRow.addView(highHzText);

        SeekBar highSeek = new SeekBar(this);
        highSeek.setMax(220);
        int highProg = Math.max(0, Math.min(220, overlay.getFrequencyRangeHighHz() / 100));
        highSeek.setProgress(highProg);
        LinearLayout.LayoutParams highLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f);
        highLp.setMargins((int) (4 * dp), 0, (int) (4 * dp), 0);
        freqRow.addView(highSeek, highLp);

        Runnable updateFreqLabels = () -> {
            int lo = overlay.getFrequencyRangeLowHz();
            int hi = overlay.getFrequencyRangeHighHz();
            lowHzText.setText(String.valueOf(lo));
            highHzText.setText(hi >= 1000 ? (hi / 1000) + "k" : String.valueOf(hi));
        };
        updateFreqLabels.run();
        lowSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                overlay.setFrequencyRangeLowHz(Math.max(1, p));
                updateFreqLabels.run();
                if (waveformOverlayView != null) waveformOverlayView.invalidate();
                scheduleAutoSave();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        highSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                overlay.setFrequencyRangeHighHz(Math.max(overlay.getFrequencyRangeLowHz() + 1, p * 100));
                updateFreqLabels.run();
                if (waveformOverlayView != null) waveformOverlayView.invalidate();
                scheduleAutoSave();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        TextView barsLabel = new TextView(this);
        barsLabel.setText("Bars");
        barsLabel.setTextColor(0xFF9E9E9E);
        barsLabel.setTextSize(11);
        barsLabel.setPadding((int) (10 * dp), 0, (int) (4 * dp), 0);
        freqRow.addView(barsLabel);

        TextView barsText = new TextView(this);
        barsText.setTextSize(11);
        barsText.setTextColor(0xFFCCCCCC);
        barsText.setMinEms(2);
        freqRow.addView(barsText);

        SeekBar barsSeek = new SeekBar(this);
        barsSeek.setMax(120); // 0..120 → offset from 8
        int bcount = overlay.getBandCountOverride();
        if (bcount > 0) barsSeek.setProgress(Math.max(0, Math.min(120, bcount - 8)));
        else barsSeek.setProgress(40); // default ~48
        LinearLayout.LayoutParams barsLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        barsLp.setMargins((int) (4 * dp), 0, 0, 0);
        freqRow.addView(barsSeek, barsLp);
        Runnable updateBarsLabel = () -> {
            int v = overlay.getBandCountOverride();
            barsText.setText(v > 0 ? String.valueOf(v) : "auto");
        };
        updateBarsLabel.run();
        barsSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                if (p < 4) overlay.setBandCountOverride(0); // auto
                else overlay.setBandCountOverride(8 + p);
                updateBarsLabel.run();
                if (waveformOverlayView != null) waveformOverlayView.invalidate();
                scheduleAutoSave();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        root.addView(freqRow);

        // ── Bar width + gap row (visualizer-studio Phase 3: per-instance overrides,
        //    0/left edge = "auto" i.e. the preset's own values) ──
        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        barRow.setPadding(0, (int) (2 * dp), 0, 0);

        TextView widthLabel = new TextView(this);
        widthLabel.setText("Width");
        widthLabel.setTextColor(0xFF9E9E9E);
        widthLabel.setTextSize(11);
        widthLabel.setPadding(0, 0, (int) (4 * dp), 0);
        barRow.addView(widthLabel);

        TextView widthText = new TextView(this);
        widthText.setTextSize(11);
        widthText.setTextColor(0xFFCCCCCC);
        widthText.setMinEms(2);
        barRow.addView(widthText);

        SeekBar widthSeek = new SeekBar(this);
        widthSeek.setMax(48); // 0 = auto, 1..48 dp
        widthSeek.setProgress(Math.round(overlay.getBarWidthOverrideDp()));
        LinearLayout.LayoutParams widthLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        widthLp.setMargins(0, 0, (int) (4 * dp), 0);
        barRow.addView(widthSeek, widthLp);

        TextView gapLabel = new TextView(this);
        gapLabel.setText("Gap");
        gapLabel.setTextColor(0xFF9E9E9E);
        gapLabel.setTextSize(11);
        gapLabel.setPadding((int) (6 * dp), 0, (int) (4 * dp), 0);
        barRow.addView(gapLabel);

        TextView gapText = new TextView(this);
        gapText.setTextSize(11);
        gapText.setTextColor(0xFFCCCCCC);
        gapText.setMinEms(2);
        barRow.addView(gapText);

        SeekBar gapSeek = new SeekBar(this);
        gapSeek.setMax(24); // 0 = auto, 1..24 dp
        gapSeek.setProgress(Math.round(overlay.getBarGapOverrideDp()));
        LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        gapLp.setMargins((int) (4 * dp), 0, 0, 0);
        barRow.addView(gapSeek, gapLp);

        Runnable updateBarDimLabels = () -> {
            float bw = overlay.getBarWidthOverrideDp();
            float bg = overlay.getBarGapOverrideDp();
            widthText.setText(bw > 0f ? String.valueOf(Math.round(bw)) : "auto");
            gapText.setText(bg > 0f ? String.valueOf(Math.round(bg)) : "auto");
        };
        updateBarDimLabels.run();
        widthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                overlay.setBarWidthOverrideDp(p); // 0 = auto (preset value)
                updateBarDimLabels.run();
                if (waveformOverlayView != null) waveformOverlayView.invalidate();
                scheduleAutoSave();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        gapSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                overlay.setBarGapOverrideDp(p); // 0 = auto (preset value)
                updateBarDimLabels.run();
                if (waveformOverlayView != null) waveformOverlayView.invalidate();
                scheduleAutoSave();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        root.addView(barRow);

        // ── Columns area (with a faint centre groove band behind the carousels) ──
        android.widget.FrameLayout colsFrame = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams cflp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, colH);
        cflp.topMargin = (int) (6 * dp);
        colsFrame.setLayoutParams(cflp);

        int itemH = (int) (26 * dp);
        View groove = new View(this);
        android.graphics.drawable.GradientDrawable grooveBg = new android.graphics.drawable.GradientDrawable();
        grooveBg.setColor(0x144DD0E1);
        grooveBg.setCornerRadius(8 * dp);
        groove.setBackground(grooveBg);
        android.widget.FrameLayout.LayoutParams glp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, itemH);
        glp.gravity = android.view.Gravity.CENTER_VERTICAL;
        colsFrame.addView(groove, glp);

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // LEFT: vertical icon toggles (justify / mode / mirror / center-mode / render-mode).
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(android.view.Gravity.CENTER);
        TextView justBtn = new TextView(this), modeBtn = new TextView(this), mirBtn = new TextView(this),
                centerBtn = new TextView(this), rendBtn = new TextView(this),
                attachBtn = new TextView(this);
        for (TextView b : new TextView[]{justBtn, modeBtn, mirBtn, centerBtn, rendBtn, attachBtn}) {
            if (iconFont != null) b.setTypeface(iconFont);
            b.setTextColor(0xFFEEEEEE);
            b.setGravity(android.view.Gravity.CENTER);
            int p = (int) (6 * dp);
            b.setPadding(p, p, p, p);
        }
        justBtn.setTextSize(22); modeBtn.setTextSize(22); mirBtn.setTextSize(22);
        centerBtn.setTextSize(18); rendBtn.setTextSize(18); attachBtn.setTextSize(18);
        Runnable labels = () -> {
            int j = overlay.getJustify();
            justBtn.setText(j == 1 ? "vertical_align_center"
                    : (j == 2 ? "vertical_align_top" : "vertical_align_bottom"));
            modeBtn.setText(overlay.getDataMode() == 1 ? "equalizer" : "graphic_eq");
            mirBtn.setText("flip");
            mirBtn.setTextColor(overlay.isHorizontalMirror() ? 0xFF4CAF50 : 0xFFEEEEEE);
            int cm = overlay.getCenterMode();
            centerBtn.setText(cm == 0 ? "blur_on" : (cm == 1 ? "blur_off" : "view_stream"));
            centerBtn.setTextColor(cm >= 0 ? 0xFF4CAF50 : 0xFFEEEEEE);
            rendBtn.setText(overlay.getRenderMode() == 1 ? "radio_button_checked" : "equalizer");
            rendBtn.setTextColor(overlay.getRenderMode() == 1 ? 0xFF4CAF50 : 0xFFEEEEEE);
            // G5: link = attached (time-rides its host clip), link_off = detached.
            attachBtn.setText(overlay.isAttached() ? "link" : "link_off");
            attachBtn.setTextColor(overlay.isAttached() ? 0xFF4CAF50 : 0xFFEEEEEE);
        };
        labels.run();
        Runnable live = () -> {
            if (waveformOverlayView != null) waveformOverlayView.invalidate();
            scheduleAutoSave();
        };
        justBtn.setOnClickListener(v -> { overlay.cycleJustify(); labels.run(); live.run(); });
        modeBtn.setOnClickListener(v -> { overlay.cycleDataMode(); labels.run(); live.run(); });
        mirBtn.setOnClickListener(v -> { overlay.toggleHorizontalMirror(); labels.run(); live.run(); });
        centerBtn.setOnClickListener(v -> { overlay.cycleCenterMode(); labels.run(); live.run(); });
        rendBtn.setOnClickListener(v -> { overlay.toggleRenderMode(); labels.run(); live.run(); });
        // G5 attach/detach (contract §4): tether the visualizer to the master clip under its
        // start so it TIME-RIDES that clip (trims/moves/reorders shift it along); detach keeps
        // the current absolute window. ONE undo step restoring the full attachment + window +
        // audio-source state either way.
        attachBtn.setOnClickListener(v -> {
            if (project == null) return;
            final Timeline tl = project.getTimeline();
            final com.fadcam.ui.faditor.model.WaveformOverlayInstance wo = overlay;
            final String beforeId = wo.getAttachedClipId();
            final long beforeOff = wo.getAttachOffsetMs(), beforeDur = wo.getAttachDurationMs();
            final long beforeStart = wo.getStartMs(), beforeEnd = wo.getEndMs();
            final String beforeSrc = wo.getAudioSourceRef();
            String desc;
            if (wo.isAttached()) {
                tl.detachVisualizer(wo);
                desc = "Detach visualizer";
                Toast.makeText(this, "Visualizer detached — window frozen where it is",
                        Toast.LENGTH_SHORT).show();
            } else {
                Clip host = tl.attachVisualizerToHostUnderStart(wo);
                if (host == null) {
                    Toast.makeText(this, "No clip to attach to", Toast.LENGTH_SHORT).show();
                    return;
                }
                int hostIdx = tl.getClips().indexOf(host) + 1;
                desc = "Attach visualizer";
                Toast.makeText(this, "Attached to clip " + hostIdx
                        + " — rides its trims and moves", Toast.LENGTH_SHORT).show();
            }
            final String afterId = wo.getAttachedClipId();
            final long afterOff = wo.getAttachOffsetMs(), afterDur = wo.getAttachDurationMs();
            final long afterStart = wo.getStartMs(), afterEnd = wo.getEndMs();
            final String afterSrc = wo.getAudioSourceRef();
            undoManager.recordAction(new EditActions.LambdaAction(desc,
                    () -> {
                        wo.setAttachedClipId(afterId);
                        wo.setAttachOffsetMs(afterOff);
                        wo.setAttachDurationMs(afterDur);
                        wo.setTimeRange(afterStart, afterEnd);
                        wo.setAudioSourceRef(afterSrc);
                        syncTimelineOverlays();
                        if (waveformOverlayView != null) waveformOverlayView.invalidate();
                    },
                    () -> {
                        wo.setAttachedClipId(beforeId);
                        wo.setAttachOffsetMs(beforeOff);
                        wo.setAttachDurationMs(beforeDur);
                        wo.setTimeRange(beforeStart, beforeEnd);
                        wo.setAudioSourceRef(beforeSrc);
                        syncTimelineOverlays();
                        if (waveformOverlayView != null) waveformOverlayView.invalidate();
                    }));
            syncTimelineOverlays();
            labels.run();
            live.run();
        });
        left.addView(justBtn); left.addView(modeBtn); left.addView(mirBtn);
        left.addView(centerBtn); left.addView(rendBtn); left.addView(attachBtn);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams((int) (52 * dp),
                LinearLayout.LayoutParams.MATCH_PARENT);
        cols.addView(left, leftLp);

        // CENTRE: style-type carousel (thumbnails). Pre-render thumbs once.
        com.fadcam.ui.faditor.model.WaveformData sampleWf = sampleWaveformData();
        com.fadcam.ui.faditor.waveform.WaveformStyleRenderer thumbRenderer =
                new com.fadcam.ui.faditor.waveform.WaveformStyleRenderer();
        int thumbW = (int) (180 * dp), thumbH = (int) (42 * dp);
        final android.graphics.Bitmap[] thumbs = new android.graphics.Bitmap[styles.size()];
        for (int i = 0; i < styles.size(); i++) {
            try { thumbs[i] = thumbRenderer.render(sampleWf, styles.get(i), thumbW, thumbH, 1200L, dp); }
            catch (Exception ignored) { }
        }
        androidx.recyclerview.widget.RecyclerView styleRv = new androidx.recyclerview.widget.RecyclerView(this);
        styleRv.setAdapter(new androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            @NonNull @Override
            public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(
                    @NonNull android.view.ViewGroup parent, int viewType) {
                android.widget.ImageView iv = new android.widget.ImageView(FaditorEditorActivity.this);
                iv.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(
                        androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT, itemH));
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                return new androidx.recyclerview.widget.RecyclerView.ViewHolder(iv) { };
            }
            @Override public void onBindViewHolder(
                    @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder h, int position) {
                ((android.widget.ImageView) h.itemView).setImageBitmap(thumbs[position]);
                h.itemView.setOnClickListener(v -> styleRv.smoothScrollToPosition(position));
            }
            @Override public int getItemCount() { return styles.size(); }
        });
        LinearLayout.LayoutParams centerLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        cols.addView(styleRv, centerLp);

        // RIGHT: gradient + solid swatch carousel. spec: null=clear, [hex]=solid, [a,b]=gradient.
        final java.util.List<String[]> swatches = new java.util.ArrayList<>();
        swatches.add(null); // clear → preset
        for (String hex : new String[]{"#00E676", "#2196F3", "#FF1744", "#FFEA00", "#E040FB", "#FF6D00", "#FFFFFF"})
            swatches.add(new String[]{hex});
        for (String[] g : new String[][]{
                {"#00E5FF", "#2979FF"}, {"#FF6D00", "#FF1744"}, {"#FFEA00", "#FF6D00"},
                {"#E040FB", "#7C4DFF"}, {"#69F0AE", "#00BFA5"}, {"#FF80AB", "#7C4DFF"},
                {"#FFFFFF", "#9E9E9E"}, {"#FFD54F", "#F57F17"}})
            swatches.add(g);
        androidx.recyclerview.widget.RecyclerView gradRv = new androidx.recyclerview.widget.RecyclerView(this);
        gradRv.setAdapter(new androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            @NonNull @Override
            public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(
                    @NonNull android.view.ViewGroup parent, int viewType) {
                android.widget.FrameLayout wrap = new android.widget.FrameLayout(FaditorEditorActivity.this);
                wrap.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(
                        androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT, itemH));
                View sw = new View(FaditorEditorActivity.this);
                int sz = (int) (24 * dp);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(sz, sz);
                lp.gravity = android.view.Gravity.CENTER;
                wrap.addView(sw, lp);
                return new androidx.recyclerview.widget.RecyclerView.ViewHolder(wrap) { };
            }
            @Override public void onBindViewHolder(
                    @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder h, int position) {
                View sw = ((android.widget.FrameLayout) h.itemView).getChildAt(0);
                String[] g = swatches.get(position);
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                if (g == null) { gd.setColor(0x00000000); gd.setStroke((int) (2 * dp), 0xFF888888); }
                else if (g.length == 1) { gd.setColor(android.graphics.Color.parseColor(g[0]));
                    gd.setStroke((int) (2 * dp), 0x55FFFFFF); }
                else { gd.setColors(new int[]{android.graphics.Color.parseColor(g[0]),
                        android.graphics.Color.parseColor(g[1])});
                    gd.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
                    gd.setStroke((int) (2 * dp), 0x55FFFFFF); }
                sw.setBackground(gd);
                h.itemView.setOnClickListener(v -> gradRv.smoothScrollToPosition(position));
            }
            @Override public int getItemCount() { return swatches.size(); }
        });
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams((int) (40 * dp),
                LinearLayout.LayoutParams.MATCH_PARENT);
        cols.addView(gradRv, rightLp);

        colsFrame.addView(cols);
        root.addView(colsFrame);

        // Carousel behaviour + settle handlers.
        int styleStart = Math.max(0, indexOfStyle(styles, overlay.getStyleId()));
        setupRolodex(styleRv, itemH, pos -> {
            if (pos < 0 || pos >= styles.size()) return;
            com.fadcam.ui.faditor.model.WaveformStyle s = styles.get(pos);
            overlay.setStyleId(s.id);
            if (waveformOverlayView != null) { waveformOverlayView.putStyle(s); waveformOverlayView.invalidate(); }
            scheduleAutoSave();
        });
        styleRv.post(() -> styleRv.scrollToPosition(styleStart));
        setupRolodex(gradRv, itemH, pos -> {
            if (pos < 0 || pos >= swatches.size()) return;
            String[] g = swatches.get(pos);
            if (g == null) { overlay.setColorOverride(null); overlay.setGradientOverride(null, null); }
            else if (g.length == 1) overlay.setColorOverride(g[0]);
            else overlay.setGradientOverride(g[0], g[1]);
            if (waveformOverlayView != null) waveformOverlayView.invalidate();
            scheduleAutoSave();
        });
        return root;
    }

    private int indexOfStyle(java.util.List<com.fadcam.ui.faditor.model.WaveformStyle> styles, String id) {
        if (id == null) return 0;
        for (int i = 0; i < styles.size(); i++) if (id.equals(styles.get(i).id)) return i;
        return 0;
    }

    /** Wire a vertical RecyclerView as a snapping, scale/fade "Rolodex" barrel carousel. */
    private void setupRolodex(androidx.recyclerview.widget.RecyclerView rv, int itemH, RolodexSettle settle) {
        rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(
                this, androidx.recyclerview.widget.LinearLayoutManager.VERTICAL, false));
        rv.setClipToPadding(false);
        rv.setClipChildren(false);
        ((android.view.ViewGroup) rv.getParent()).setClipChildren(false);
        rv.setHorizontalScrollBarEnabled(false);
        rv.setVerticalScrollBarEnabled(false);
        rv.post(() -> {
            int padV = Math.max(0, (rv.getHeight() - itemH) / 2);
            rv.setPadding(0, padV, 0, padV);
            scaleRolodexChildren(rv);
        });
        new androidx.recyclerview.widget.LinearSnapHelper().attachToRecyclerView(rv);
        rv.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull androidx.recyclerview.widget.RecyclerView r, int dx, int dy) {
                scaleRolodexChildren(r);
            }
            @Override public void onScrollStateChanged(@NonNull androidx.recyclerview.widget.RecyclerView r, int state) {
                scaleRolodexChildren(r);
                if (state == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    int pos = rolodexCenteredPosition(r);
                    if (pos >= 0) settle.onSettle(pos);
                }
            }
        });
    }

    /**
     * Apply the barrel barrel-perspective transform to each carousel child:
     * <ul>
     *   <li><b>Y projection</b>: {@code R * sin(delta/R)} — items near the centre map
     *       approximately linearly (sin(x)≈x), but farther items saturate so they bunch up
     *       toward the centre, giving the apparent roundness of a Rolodex cylinder.
     *       The selected (centred) item has translationY = 0 → no overlap.</li>
     *   <li><b>Scale + fade</b>: smooth-step falloff so the centred item is full-size
     *       and visible; edges shrink and dim.</li>
     * </ul>
     */
    private void scaleRolodexChildren(androidx.recyclerview.widget.RecyclerView r) {
        float cy = r.getHeight() / 2f;
        if (cy <= 0) return;
        // Barrel radius; smaller R = stronger roundness (more edge compression). About half the
        // carousel height gives a pronounced barrel feel without inverting.
        float R = cy * 0.7f;
        // Style carousel (ImageView children) gets a 200% X boost so waveform thumbnails read wide.
        float xBoost = (r.getChildCount() > 0 && r.getChildAt(0) instanceof android.widget.ImageView)
                ? 2.0f : 1f;
        for (int i = 0; i < r.getChildCount(); i++) {
            View c = r.getChildAt(i);
            float childCy = (c.getTop() + c.getBottom()) / 2f;
            float delta = childCy - cy;
            float dist = Math.min(1f, Math.abs(delta) / cy);
            // Barrel Y projection: edges saturate toward centre.
            float projectedDelta = R * (float) Math.sin(delta / R);
            c.setTranslationY(projectedDelta - delta);
            // Smoothstep scale + fade: full-size at centre, shrinks/dims toward edges.
            float eased = dist * dist * (3f - 2f * dist);
            float scale = 1.0f - 0.4f * eased;
            c.setScaleX(scale * xBoost);
            c.setScaleY(scale);
            c.setAlpha(1.0f - 0.95f * eased);     // fully transparent at edges, fully opaque near centre
            c.setTranslationZ(-eased * 8f);
        }
    }

    private int rolodexCenteredPosition(androidx.recyclerview.widget.RecyclerView r) {
        float cy = r.getHeight() / 2f;
        float best = Float.MAX_VALUE;
        int pos = -1;
        for (int i = 0; i < r.getChildCount(); i++) {
            View c = r.getChildAt(i);
            float childCy = (c.getTop() + c.getBottom()) / 2f;
            float d = Math.abs(childCy - cy);
            if (d < best) { best = d; pos = r.getChildAdapterPosition(c); }
        }
        return pos;
    }

    private boolean visualizerDrawerChromeWired = false;

    /** Wire the drawer's close button + swipe-up-to-dismiss once. */
    private void setupVisualizerDrawerChrome() {
        if (visualizerDrawerChromeWired) return;
        View close = findViewById(R.id.visualizer_drawer_close);
        if (close != null) close.setOnClickListener(v -> showVisualizerDrawer(false));
        // Tapping the preview area (player_container) dismisses the drawer — but NOT the
        // timeline/play area since those are separate views outside player_container.
        View playerContainer = findViewById(R.id.player_container);
        if (playerContainer != null) {
            playerContainer.setClickable(true);
            playerContainer.setOnClickListener(v -> showVisualizerDrawer(false));
        }
        final android.view.GestureDetector swipe = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                           float vx, float vy) {
                        if (vy < -700 && Math.abs(vy) > Math.abs(vx)) {
                            showVisualizerDrawer(false);
                            return true;
                        }
                        return false;
                    }
                });
        View header = findViewById(R.id.visualizer_drawer_header);
        if (header != null) header.setOnTouchListener((v, ev) -> { swipe.onTouchEvent(ev); return true; });
        // Grab bar also dismisses on swipe-up (and on tap, as a quick affordance).
        View grab = findViewById(R.id.visualizer_drawer_grab);
        if (grab != null) {
            grab.setOnTouchListener((v, ev) -> { swipe.onTouchEvent(ev); return true; });
        }
        // Bottom grab bar also dismisses on swipe-up.
        View grabBottom = findViewById(R.id.visualizer_drawer_grab_bottom);
        if (grabBottom != null) {
            grabBottom.setOnTouchListener((v, ev) -> { swipe.onTouchEvent(ev); return true; });
        }
        visualizerDrawerChromeWired = true;
    }

    private void setupCaptionDrawerChrome() {
        if (captionDrawerChromeWired) return;
        captionDrawerChromeWired = true;
        View close = findViewById(R.id.caption_drawer_close);
        if (close != null) close.setOnClickListener(v -> showCaptionDrawer(false));
        View grab = findViewById(R.id.caption_drawer_grab);
        View grabBottom = findViewById(R.id.caption_drawer_grab_bottom);
        View.OnTouchListener swipeDismiss = new View.OnTouchListener() {
            private float startY;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startY = event.getY();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                        && startY - event.getY() > 100) {
                    showCaptionDrawer(false);
                    return true;
                }
                return false;
            }
        };
        if (grab != null) grab.setOnTouchListener(swipeDismiss);
        if (grabBottom != null) grabBottom.setOnTouchListener(swipeDismiss);
    }

    private void showVisualizerDrawer(boolean show) {
        View drawer = findViewById(R.id.visualizer_drawer);
        if (drawer == null) return;
        float off = -getResources().getDisplayMetrics().heightPixels;
        if (show) {
            closeAllTopPanels();
            drawer.setVisibility(View.VISIBLE);
            drawer.setTranslationY(off);
            drawer.animate().translationY(0f).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        } else {
            drawer.animate().translationY(off).setDuration(180)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
        }
        visualizerDrawerOpen = show;
        // Light up the visualizer tool button when the drawer is active.
        TextView toolIcon = findViewById(R.id.tool_visualizer_icon);
        TextView toolLabel = findViewById(R.id.tool_visualizer_label);
        int activeColor = show ? 0xFF4CAF50 : 0xFF888888;
        if (toolIcon != null) toolIcon.setTextColor(activeColor);
        if (toolLabel != null) toolLabel.setTextColor(activeColor);
    }

    private void showCaptionDrawer(boolean show) {
        View drawer = findViewById(R.id.caption_drawer);
        if (drawer == null) return;
        if (show) {
            closeAllTopPanels();
            setupCaptionDrawerChrome();
            buildCaptionDrawerContent();
            drawer.setVisibility(View.VISIBLE);
            drawer.animate().cancel();
            drawer.setTranslationY(-drawer.getHeight());
            drawer.animate().translationY(0).setDuration(200).start();
        } else {
            drawer.animate().cancel();
            drawer.animate().translationY(-drawer.getHeight()).setDuration(150)
                    .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
        }
        captionDrawerOpen = show;
    }

    private void buildCaptionDrawerContent() {
        FrameLayout content = findViewById(R.id.caption_drawer_content);
        if (content == null) return;
        content.removeAllViews();

        float d = getResources().getDisplayMetrics().density;
        int pad = (int)(12 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, 0, pad, pad);
        root.setBackgroundColor(0xFF1A1A1A);
        content.addView(root);

        // Style chips row
        TextView styleLabel = new TextView(this);
        styleLabel.setText("Style");
        styleLabel.setTextColor(0xFFAAAAAA);
        styleLabel.setTextSize(12);
        styleLabel.setPadding(0, pad, 0, (int)(4*d));
        root.addView(styleLabel);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chipRow);

        for (com.fadcam.ui.faditor.transcript.CaptionStyle s : com.fadcam.ui.faditor.transcript.CaptionStyle.presets()) {
            TextView chip = new TextView(this);
            chip.setText(s.label);
            chip.setTextColor(s.activeColor);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextSize(13);
            int cp = (int)(10*d);
            chip.setPadding(cp, cp/2, cp, cp/2);
            chip.setBackgroundResource(R.drawable.floating_button_item_bg);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = (int)(6*d);
            chip.setLayoutParams(clp);
            chip.setOnClickListener(v -> {
                applyCaptionStyle(s.id);
                for (int i = 0; i < chipRow.getChildCount(); i++) {
                    chipRow.getChildAt(i).setAlpha(i == chipRow.indexOfChild(v) ? 1f : 0.5f);
                }
            });
            chipRow.addView(chip);
        }
        for (int i = 0; i < chipRow.getChildCount(); i++) chipRow.getChildAt(i).setAlpha(0.5f);
        root.addView(chipScroll);

        // Position presets
        root.addView(makeDivider(d));
        TextView posLabel = new TextView(this);
        posLabel.setText("Position");
        posLabel.setTextColor(0xFFAAAAAA);
        posLabel.setTextSize(12);
        posLabel.setPadding(0, pad, 0, (int)(4*d));
        root.addView(posLabel);

        LinearLayout posRow = new LinearLayout(this);
        posRow.setOrientation(LinearLayout.HORIZONTAL);
        posRow.setGravity(Gravity.CENTER);

        String[][] positions = {{"Top", "0.5", "0.15"}, {"Middle", "0.5", "0.5"}, {"Bottom", "0.5", "0.85"}};
        for (String[] p : positions) {
            TextView btn = new TextView(this);
            btn.setText(p[0]);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(12);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding((int)(16*d), (int)(8*d), (int)(16*d), (int)(8*d));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            blp.leftMargin = (int)(2*d);
            blp.rightMargin = (int)(2*d);
            btn.setLayoutParams(blp);
            btn.setBackgroundColor(0xFF333333);
            btn.setOnClickListener(v -> {
                applyCaptionPosition(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                for (int i = 0; i < posRow.getChildCount(); i++) {
                    posRow.getChildAt(i).setBackgroundColor(i == posRow.indexOfChild(v) ? 0xFF4CAF50 : 0xFF333333);
                }
            });
            posRow.addView(btn);
        }
        root.addView(posRow);

        // Size slider
        root.addView(makeDivider(d));
        TextView sizeLabel = new TextView(this);
        sizeLabel.setText("Text Size");
        sizeLabel.setTextColor(0xFFAAAAAA);
        sizeLabel.setTextSize(12);
        sizeLabel.setPadding(0, pad, 0, (int)(4*d));
        root.addView(sizeLabel);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(sizeRow);

        Slider sizeSlider = new Slider(new ContextThemeWrapper(this, R.style.Widget_FadCam_BottomSheetSlider));
        sizeSlider.setValueFrom(0.02f);
        sizeSlider.setValueTo(0.20f);
        sizeSlider.setStepSize(0.005f);
        sizeSlider.setValue(getCurrentCaptionSize());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sizeSlider.setLayoutParams(slp);
        sizeSlider.setTrackActiveTintList(ColorStateList.valueOf(0xFF4CAF50));
        sizeSlider.setThumbTintList(ColorStateList.valueOf(0xFF4CAF50));
        sizeSlider.setTrackInactiveTintList(ColorStateList.valueOf(0xFF333333));
        sizeRow.addView(sizeSlider);

        TextView sizeVal = new TextView(this);
        sizeVal.setTextSize(12);
        sizeVal.setTextColor(0xFF4CAF50);
        sizeVal.setPadding((int)(8*d), 0, 0, 0);
        sizeRow.addView(sizeVal);

        sizeSlider.addOnChangeListener((sl, value, fromUser) -> {
            if (!fromUser) return;
            sizeVal.setText(Math.round(value * 100) + "%");
            applyCaptionSize(value);
        });
        sizeVal.setText(Math.round(getCurrentCaptionSize() * 100) + "%");
    }

    private View makeDivider(float d) {
        View div = new View(this);
        div.setBackgroundColor(0xFF2A2A2A);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int)(1 * d));
        dlp.topMargin = (int)(12 * d);
        dlp.bottomMargin = (int)(8 * d);
        div.setLayoutParams(dlp);
        return div;
    }

    private float getCurrentCaptionSize() {
        boolean preferAudio = (editorTimeline.getSelectedAudioIndex() >= 0
                && editorTimeline.getSelectedAudioIndex() < project.getTimeline().getAudioClips().size());
        if (preferAudio) {
            int idx = editorTimeline.getSelectedAudioIndex();
            if (idx >= 0 && idx < project.getTimeline().getAudioClips().size()) {
                return project.getTimeline().getAudioClips().get(idx).getCaptionSizeFraction();
            }
        }
        Clip cc = getSelectedClip();
        return cc != null ? cc.getCaptionSizeFraction() : 0.06f;
    }

    private void applyCaptionStyle(String styleId) {
        boolean preferAudio = (editorTimeline.getSelectedAudioIndex() >= 0
                && editorTimeline.getSelectedAudioIndex() < project.getTimeline().getAudioClips().size());
        if (preferAudio) {
            int idx = editorTimeline.getSelectedAudioIndex();
            if (idx >= 0 && idx < project.getTimeline().getAudioClips().size()) {
                final AudioClip ac = project.getTimeline().getAudioClips().get(idx);
                final String beforeStyle = ac.getCaptionStyleId();
                final boolean beforeEnabled = ac.isCaptionsEnabled();
                ac.setCaptionStyleId(styleId);
                ac.setCaptionsEnabled(true);
                activeCaptionIsAudio = true;
                audioCaptionClipId = ac.getId();
                bindAudioCaptionData(ac);
                audioCaptionOverlay.setStyle(com.fadcam.ui.faditor.transcript.CaptionStyle.byId(styleId));
                audioCaptionOverlay.setVisibility(View.VISIBLE);
                if (captionStyleBar != null) captionStyleBar.setVisibility(View.VISIBLE);
                if (!styleId.equals(beforeStyle) || !beforeEnabled) {
                    undoManager.recordAction(new EditActions.LambdaAction("Caption style",
                            () -> { ac.setCaptionStyleId(styleId); ac.setCaptionsEnabled(true); },
                            () -> { ac.setCaptionStyleId(beforeStyle); ac.setCaptionsEnabled(beforeEnabled); }));
                }
                scheduleAutoSave();
            }
        } else {
            final Clip cc = getSelectedClip();
            if (cc != null && cc.hasTranscript()) {
                final String beforeStyle = cc.getCaptionStyleId();
                final boolean beforeEnabled = cc.isCaptionsEnabled();
                cc.setCaptionStyleId(styleId);
                cc.setCaptionsEnabled(true);
                bindCaptionData(cc);
                captionOverlay.setVisibility(View.VISIBLE);
                if (captionStyleBar != null) captionStyleBar.setVisibility(View.VISIBLE);
                if (!styleId.equals(beforeStyle) || !beforeEnabled) {
                    undoManager.recordAction(new EditActions.LambdaAction("Caption style",
                            () -> { cc.setCaptionStyleId(styleId); cc.setCaptionsEnabled(true); },
                            () -> { cc.setCaptionStyleId(beforeStyle); cc.setCaptionsEnabled(beforeEnabled); }));
                }
                scheduleAutoSave();
            }
        }
    }

    private void applyCaptionPosition(float x, float y) {
        boolean preferAudio = (editorTimeline.getSelectedAudioIndex() >= 0
                && editorTimeline.getSelectedAudioIndex() < project.getTimeline().getAudioClips().size());
        if (preferAudio) {
            int idx = editorTimeline.getSelectedAudioIndex();
            if (idx >= 0 && idx < project.getTimeline().getAudioClips().size()) {
                final AudioClip ac = project.getTimeline().getAudioClips().get(idx);
                final float beforeX = ac.getCaptionCenterX();
                final float beforeY = ac.getCaptionCenterY();
                ac.setCaptionCenter(x, y);
                bindAudioCaptionData(ac);
                if (beforeX != x || beforeY != y) {
                    undoManager.recordAction(new EditActions.LambdaAction("Caption position",
                            () -> ac.setCaptionCenter(x, y),
                            () -> ac.setCaptionCenter(beforeX, beforeY)));
                }
                scheduleAutoSave();
            }
        } else {
            final Clip cc = getSelectedClip();
            if (cc != null && cc.hasTranscript()) {
                final float beforeX = cc.getCaptionCenterX();
                final float beforeY = cc.getCaptionCenterY();
                cc.setCaptionCenter(x, y);
                bindCaptionData(cc);
                if (beforeX != x || beforeY != y) {
                    undoManager.recordAction(new EditActions.LambdaAction("Caption position",
                            () -> cc.setCaptionCenter(x, y),
                            () -> cc.setCaptionCenter(beforeX, beforeY)));
                }
                scheduleAutoSave();
            }
        }
    }

    private void applyCaptionSize(float size) {
        boolean preferAudio = (editorTimeline.getSelectedAudioIndex() >= 0
                && editorTimeline.getSelectedAudioIndex() < project.getTimeline().getAudioClips().size());
        if (preferAudio) {
            int idx = editorTimeline.getSelectedAudioIndex();
            if (idx >= 0 && idx < project.getTimeline().getAudioClips().size()) {
                final AudioClip ac = project.getTimeline().getAudioClips().get(idx);
                final float beforeSize = ac.getCaptionSizeFraction();
                ac.setCaptionSizeFraction(size);
                bindAudioCaptionData(ac);
                if (beforeSize != size) {
                    undoManager.recordAction(new EditActions.LambdaAction("Caption size",
                            () -> ac.setCaptionSizeFraction(size),
                            () -> ac.setCaptionSizeFraction(beforeSize)));
                }
                scheduleAutoSave();
            }
        } else {
            final Clip cc = getSelectedClip();
            if (cc != null && cc.hasTranscript()) {
                final float beforeSize = cc.getCaptionSizeFraction();
                cc.setCaptionSizeFraction(size);
                bindCaptionData(cc);
                if (beforeSize != size) {
                    undoManager.recordAction(new EditActions.LambdaAction("Caption size",
                            () -> cc.setCaptionSizeFraction(size),
                            () -> cc.setCaptionSizeFraction(beforeSize)));
                }
                scheduleAutoSave();
            }
        }
    }

    /** Deep-copy snapshot of a clip's caption-style keyframe list (for undo capture). */
    private java.util.List<Clip.CaptionStyleKeyframe> snapshotCaptionStyleKeyframes(@NonNull Clip cc) {
        java.util.List<Clip.CaptionStyleKeyframe> out = new java.util.ArrayList<>();
        for (Clip.CaptionStyleKeyframe kf : cc.getCaptionStyleKeyframes()) {
            out.add(new Clip.CaptionStyleKeyframe(kf.timeMs, kf.styleId));
        }
        return out;
    }

    /** True when two caption-style keyframe lists are identical (time + styleId, in order). */
    private boolean captionStyleKeyframesEqual(
            @NonNull java.util.List<Clip.CaptionStyleKeyframe> a,
            @NonNull java.util.List<Clip.CaptionStyleKeyframe> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Clip.CaptionStyleKeyframe ka = a.get(i), kb = b.get(i);
            if (ka.timeMs != kb.timeMs || !ka.styleId.equals(kb.styleId)) return false;
        }
        return true;
    }

    /**
     * Record an undo action for a caption-style keyframe edit on {@code cc}, given the
     * before-snapshot captured prior to the mutation. No-op if nothing changed.
     */
    private void recordCaptionStyleKeyframeEdit(
            @NonNull Clip cc, @NonNull java.util.List<Clip.CaptionStyleKeyframe> before) {
        java.util.List<Clip.CaptionStyleKeyframe> after = snapshotCaptionStyleKeyframes(cc);
        if (captionStyleKeyframesEqual(before, after)) return;
        undoManager.recordAction(
                new EditActions.CaptionStyleKeyframesAction(cc, before, after));
    }

    private void saveCurrentVisualizerStyle(
            @NonNull com.fadcam.ui.faditor.model.WaveformOverlayInstance overlay) {
        if (project == null) return;
        String pinned = project.getPinnedAssetDir();
        if (pinned == null) {
            Toast.makeText(this, "Pin a folder first (push-pin icon) to save your look",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<com.fadcam.ui.faditor.model.WaveformStyle> builtins =
                com.fadcam.ui.faditor.waveform.WaveformStyleIO.loadBuiltins(this);
        com.fadcam.ui.faditor.model.WaveformStyle base = null;
        for (com.fadcam.ui.faditor.model.WaveformStyle s : builtins) {
            if (s.id.equals(overlay.getStyleId())) { base = s; break; }
        }
        if (base == null && !builtins.isEmpty()) base = builtins.get(0);
        if (base == null) return;
        com.fadcam.ui.faditor.model.WaveformStyle effective = overlay.applyOverrides(base).copy();
        String stamp = new java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        effective.id = "my_viz_" + stamp;
        effective.displayName = "My Visualizer " + stamp;
        String fileName = com.fadcam.ui.faditor.waveform.WaveformStyleIO.saveToPinned(
                this, android.net.Uri.parse(pinned), effective);
        Toast.makeText(this, fileName != null ? "Saved " + fileName + " to your folder"
                : "Save failed", Toast.LENGTH_SHORT).show();
    }

    /** Synthetic waveform data for rendering style-preview thumbnails (a representative audio shape). */
    private com.fadcam.ui.faditor.model.WaveformData sampleWaveformData() {
        int n = 80, bands = 32;
        float[] amps = new float[n];
        float[][] spec = new float[n][bands];
        for (int i = 0; i < n; i++) {
            amps[i] = (float) (0.25 + 0.6 * Math.abs(Math.sin(i * 0.28))
                    * (0.6 + 0.4 * Math.sin(i * 0.05)));
            for (int b = 0; b < bands; b++) {
                spec[i][b] = (float) (0.15 + 0.7 * Math.abs(Math.sin((i * 0.3) + b * 0.5))
                        * (1.0 - b / (double) bands * 0.7));
            }
        }
        return new com.fadcam.ui.faditor.model.WaveformData(amps, spec, 2000);
    }

    private TextView vizSectionLabel(@NonNull String text, float dp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF9E9E9E);
        t.setTextSize(12);
        t.setPadding(0, (int) (6 * dp), 0, (int) (2 * dp));
        return t;
    }

    private TextView vizToggle(float dp) {
        TextView t = new TextView(this);
        t.setGravity(android.view.Gravity.CENTER);
        t.setTextColor(0xFFEEEEEE);
        t.setTextSize(12);
        t.setLineSpacing(0f, 1.05f);
        t.setBackgroundResource(R.drawable.settings_home_row_bg);
        t.setPadding((int) (6 * dp), (int) (8 * dp), (int) (6 * dp), (int) (8 * dp));
        return t;
    }

    private void addTextOverlay() {
        if (project == null) return;
        if (inCropMode) {
            exitCropMode(false);
        }
        com.fadcam.ui.faditor.model.TextOverlayItem item =
                new com.fadcam.ui.faditor.model.TextOverlayItem(
                        getString(R.string.faditor_text_hint),
                        0xFFFFFFFF, 0.5f, 0.5f, 0.10f, 0f);
        project.getTimeline().addTextOverlay(item);
        overlayLayer.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleTextOverlays(project.getTimeline()),
                overlayLayerCallback());
        syncTimelineOverlays();
        scheduleAutoSave();
        showTextOverlayEditor(item);
    }

    private android.graphics.Typeface getTypefaceForKey(String key) {
        if (key.startsWith("file:")) {
            try { return android.graphics.Typeface.createFromFile(key.substring(5)); }
            catch (Exception e) { return android.graphics.Typeface.DEFAULT_BOLD; }
        }
        switch (key) {
            case "serif": case "classy": return android.graphics.Typeface.SERIF;
            case "mono": return android.graphics.Typeface.MONOSPACE;
            case "dramatic": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC);
            case "country": case "serif_bold": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD);
            case "condensed": return android.graphics.Typeface.create(
                    "sans-serif-condensed", android.graphics.Typeface.NORMAL);
            case "condensed_bold": return android.graphics.Typeface.create(
                    "sans-serif-condensed", android.graphics.Typeface.BOLD);
            case "casual": return android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL);
            case "cursive": return android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL);
            case "sans_light": return android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL);
            case "sans_thin": return android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL);
            case "sans_medium": return android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL);
            case "sans_black": return android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL);
            case "mono_bold": return android.graphics.Typeface.create(
                    android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            case "popular_italic": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD_ITALIC);
            case "serif_italic": case "classy_italic": return android.graphics.Typeface.create(
                    android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC);
            default: return android.graphics.Typeface.DEFAULT_BOLD;
        }
    }

    /**
     * Record an undo step for an overlay timeline-handle drag (range edge or
     * keyframe move) using the before-snapshot captured at drag start. No-op when
     * nothing changed or the captured index doesn't match the finished overlay.
     */
    private void recordOverlayTimelineDrag(
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem item,
            int overlayIndex, @NonNull String description) {
        com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot before = overlayTimelineDragBefore;
        int beforeIdx = overlayTimelineDragIndex;
        overlayTimelineDragBefore = null;
        overlayTimelineDragIndex = -1;
        if (before == null || beforeIdx != overlayIndex) return;
        com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot after = item.snapshotTransform();
        if (before.matches(after)) return;
        undoManager.recordAction(new EditActions.OverlayTransformAction(
                item, before, after, description));
    }

    private com.fadcam.ui.faditor.overlay.TextOverlayLayer.Callback overlayLayerCallback() {
        return new com.fadcam.ui.faditor.overlay.TextOverlayLayer.Callback() {
            @NonNull
            @Override
            public android.graphics.RectF getVideoContentRect() {
                // Canvas-relative so text overlays match the export framing/size.
                return computeCanvasRect();
            }

            @Override
            public void onOverlayChanged() {
                scheduleAutoSave();
            }

            @Override
            public void onEditRequested(
                    @NonNull com.fadcam.ui.faditor.model.TextOverlayItem item) {
                showTextOverlayEditor(item);
            }

            @Override
            public void onOverlayManipulated(
                    @NonNull com.fadcam.ui.faditor.model.TextOverlayItem item,
                    @NonNull com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot before) {
                com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot after =
                        item.snapshotTransform();
                if (before.matches(after)) return;
                undoManager.recordAction(new EditActions.OverlayTransformAction(
                        item, before, after, "Move overlay"));
            }
        };
    }

    /** S3: sprite palette panel (AssetBrowserPanel overlay idiom). */
    @Nullable private com.fadcam.ui.faditor.sprite.SpritePalettePanel spritePalettePanel;

    /**
     * S3: Sprites tool now opens the PALETTE PANEL (micro/palette detents);
     * the sheet-manager dialog stays reachable via the panel's ⚙ / empty-state
     * "+ Load" (and still handles new-sheet import + Avatar Studio entry).
     */
    private void openSpritePalette() {
        if (project == null) return;
        if (spritePalettePanel != null && spritePalettePanel.isAttachedToWindow()) return;
        com.fadcam.ui.faditor.sprite.SpritePalettePanel p =
                new com.fadcam.ui.faditor.sprite.SpritePalettePanel(this);
        spritePalettePanel = p;
        p.setCallback(new com.fadcam.ui.faditor.sprite.SpritePalettePanel.Callback() {
            @Override
            public void onCellChipTapped(
                    @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item, int cellIndex) {
                // Drop/replace a swap at the playhead (item-LOCAL time base). One
                // undo step restores the exact prior key list.
                final long localMs = Math.max(0, item.toLocalMs(lastPlayheadAbsoluteMs));
                final java.util.List<com.fadcam.ui.faditor.sprite.FrameTrack.Key> before =
                        new java.util.ArrayList<>(item.getFrameTrack().keys());
                item.getFrameTrack().put(
                        com.fadcam.ui.faditor.sprite.FrameTrack.Key.ofCell(localMs, cellIndex));
                final java.util.List<com.fadcam.ui.faditor.sprite.FrameTrack.Key> after =
                        new java.util.ArrayList<>(item.getFrameTrack().keys());
                undoManager.recordAction(new EditActions.LambdaAction("Sprite swap",
                        () -> { restoreFrameKeys(item, after); },
                        () -> { restoreFrameKeys(item, before); }));
                scheduleAutoSave();
                if (spriteOverlayView != null) spriteOverlayView.invalidate();
                p.setPlayheadMs(lastPlayheadAbsoluteMs);
                Toast.makeText(FaditorEditorActivity.this,
                        R.string.sprite_palette_swap_dropped, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onInstanceSelected(
                    @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item) {
                p.setPlayheadMs(lastPlayheadAbsoluteMs);
            }

            @Override
            public void onFlipH(@NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item) {
                item.setFlipH(!item.isFlipH());
                scheduleAutoSave();
                if (spriteOverlayView != null) spriteOverlayView.invalidate();
                p.rebuild();
            }

            @Override
            public void onFlipV(@NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item) {
                item.setFlipV(!item.isFlipV());
                scheduleAutoSave();
                if (spriteOverlayView != null) spriteOverlayView.invalidate();
                p.rebuild();
            }

            @Override
            public void onEndBehaviorCycled(
                    @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item) {
                String next = "hold".equals(item.getEndBehavior()) ? "loop"
                        : "loop".equals(item.getEndBehavior()) ? "pingpong" : "hold";
                item.setEndBehavior(next);
                scheduleAutoSave();
                if (spriteOverlayView != null) spriteOverlayView.invalidate();
                p.rebuild();
            }

            @Override
            public void onDeleteInstance(
                    @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item) {
                project.getTimeline().removeSpriteOverlay(item);
                syncTimelineOverlays();
                undoManager.recordAction(new EditActions.LambdaAction("Delete sprite",
                        () -> { project.getTimeline().removeSpriteOverlay(item); syncTimelineOverlays(); },
                        () -> { project.getTimeline().addSpriteOverlay(item); syncTimelineOverlays(); }));
                scheduleAutoSave();
                p.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController
                        .visibleSpriteItems(project.getTimeline()));
            }

            @Override
            public void onManageSheets() {
                openSpriteSheetManager();
            }

            @Override
            public void onFrameStep(int direction) {
                // One frame at the selected sprite's sheet fps (fallback 8fps).
                float fps = 8f;
                com.fadcam.ui.faditor.sprite.SpriteOverlayItem sel = p.getSelected();
                if (sel != null) {
                    com.fadcam.ui.faditor.sprite.SpriteSheet s =
                            project.spriteSheetById(sel.getSheetId());
                    if (s != null && s.getFps() > 0f) fps = s.getFps();
                }
                long step = Math.max(1, Math.round(1000f / fps));
                editorTimeline.seekToTimelineMs(
                        Math.max(0, lastPlayheadAbsoluteMs + direction * step));
            }

            @Override
            public void onPanelCollapsed() {
                spritePalettePanel = null;
            }

            @Override
            public com.fadcam.ui.faditor.sprite.SpriteSheet lookupSheet(@NonNull String sheetId) {
                return project.spriteSheetById(sheetId);
            }

            @Override
            public com.fadcam.ui.faditor.sprite.SpriteSheetRenderer lookupRenderer(
                    @NonNull String sheetId) {
                return spriteRendererFor(sheetId);
            }

            @Override
            public void onNudgeKey(
                    @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item, int direction) {
                long localMs = Math.max(0, item.toLocalMs(lastPlayheadAbsoluteMs));
                com.fadcam.ui.faditor.sprite.FrameTrack.Key found = null;
                for (com.fadcam.ui.faditor.sprite.FrameTrack.Key k : item.getFrameTrack().keys()) {
                    if (Math.abs(k.timeMs - localMs) <= 120) { found = k; break; }
                }
                if (found == null) return;
                final java.util.List<com.fadcam.ui.faditor.sprite.FrameTrack.Key> before =
                        new java.util.ArrayList<>(item.getFrameTrack().keys());
                item.getFrameTrack().removeAt(found.timeMs);
                long newTime = Math.max(0, found.timeMs + direction * 100L);
                item.getFrameTrack().put(
                        com.fadcam.ui.faditor.sprite.FrameTrack.Key.ofCell(newTime, found.cellIndex));
                final java.util.List<com.fadcam.ui.faditor.sprite.FrameTrack.Key> after =
                        new java.util.ArrayList<>(item.getFrameTrack().keys());
                undoManager.recordAction(new EditActions.LambdaAction("Nudge key",
                        () -> { restoreFrameKeys(item, after); },
                        () -> { restoreFrameKeys(item, before); }));
                scheduleAutoSave();
                if (spriteOverlayView != null) spriteOverlayView.invalidate();
                p.setPlayheadMs(lastPlayheadAbsoluteMs);
            }

            @Override
            public void onDeleteKeyAtPlayhead(
                    @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item) {
                long localMs = Math.max(0, item.toLocalMs(lastPlayheadAbsoluteMs));
                com.fadcam.ui.faditor.sprite.FrameTrack.Key found = null;
                for (com.fadcam.ui.faditor.sprite.FrameTrack.Key k : item.getFrameTrack().keys()) {
                    if (Math.abs(k.timeMs - localMs) <= 120) { found = k; break; }
                }
                if (found == null) return;
                final java.util.List<com.fadcam.ui.faditor.sprite.FrameTrack.Key> before =
                        new java.util.ArrayList<>(item.getFrameTrack().keys());
                item.getFrameTrack().removeAt(found.timeMs);
                final java.util.List<com.fadcam.ui.faditor.sprite.FrameTrack.Key> after =
                        new java.util.ArrayList<>(item.getFrameTrack().keys());
                undoManager.recordAction(new EditActions.LambdaAction("Delete key",
                        () -> { restoreFrameKeys(item, after); },
                        () -> { restoreFrameKeys(item, before); }));
                scheduleAutoSave();
                if (spriteOverlayView != null) spriteOverlayView.invalidate();
                p.setPlayheadMs(lastPlayheadAbsoluteMs);
            }
        });
        p.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController
                .visibleSpriteItems(project.getTimeline()));
        p.setPlayheadMs(lastPlayheadAbsoluteMs);
        ViewGroup root = findViewById(android.R.id.content);
        root.addView(p);
    }

    /** Replace an item's frame keys with a snapshot (sprite-swap undo/redo). */
    private void restoreFrameKeys(
            @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item,
            @NonNull java.util.List<com.fadcam.ui.faditor.sprite.FrameTrack.Key> snapshot) {
        java.util.List<com.fadcam.ui.faditor.sprite.FrameTrack.Key> live =
                item.getFrameTrack().keys();
        live.clear();
        live.addAll(snapshot);
        if (spriteOverlayView != null) spriteOverlayView.invalidate();
        if (spritePalettePanel != null) spritePalettePanel.setPlayheadMs(lastPlayheadAbsoluteMs);
    }

    /** S4: sprite preview callback — mirrors {@link #overlayLayerCallback()}. */
    private com.fadcam.ui.faditor.sprite.SpriteOverlayView.Callback spriteOverlayCallback() {
        return new com.fadcam.ui.faditor.sprite.SpriteOverlayView.Callback() {
            @NonNull
            @Override
            public android.graphics.RectF getVideoContentRect() {
                // Canvas-relative so sprites match the export framing/size.
                return computeCanvasRect();
            }

            @Override
            public com.fadcam.ui.faditor.sprite.SpriteSheet lookupSheet(@NonNull String sheetId) {
                return project != null ? project.spriteSheetById(sheetId) : null;
            }

            @Override
            public com.fadcam.ui.faditor.sprite.SpriteSheetRenderer lookupRenderer(
                    @NonNull String sheetId) {
                return spriteRendererFor(sheetId);
            }

            @Override
            public void onSpriteChanged() {
                scheduleAutoSave();
            }

            @Override
            public void onSpriteManipulated(
                    @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item,
                    @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem.TransformSnapshot before) {
                com.fadcam.ui.faditor.sprite.SpriteOverlayItem.TransformSnapshot after =
                        item.snapshotTransform();
                if (before.matches(after)) return;
                undoManager.recordAction(new EditActions.LambdaAction("Move sprite",
                        () -> {
                            item.restoreTransform(after);
                            if (spriteOverlayView != null) spriteOverlayView.invalidate();
                        },
                        () -> {
                            item.restoreTransform(before);
                            if (spriteOverlayView != null) spriteOverlayView.invalidate();
                        }));
            }
        };
    }

    /**
     * M-COMP-2: glue for the live PiP layer. Same shape as {@link #spriteOverlayCallback}:
     * content rect from the shared canvas math, the SAME remux-to-seekable resolver the
     * master gapless engine uses, autosave on change, one undo step per gesture via a
     * whole-KeyframeSet snapshot.
     */
    private com.fadcam.ui.faditor.compositor.OverlayVideoPreviewView.Callback overlayVideoCallback() {
        return new com.fadcam.ui.faditor.compositor.OverlayVideoPreviewView.Callback() {
            @NonNull
            @Override
            public android.graphics.RectF getVideoContentRect() {
                return computeCanvasRect();
            }

            @NonNull
            @Override
            public Uri resolveSeekable(@NonNull Clip clip) {
                return resolvePlaybackUri(clip.getSourceUri());
            }

            @Override
            public void onOverlayVideoChanged() {
                scheduleAutoSave();
            }

            @Override
            public void onOverlayVideoManipulated(@NonNull Clip clip,
                    @NonNull com.fadcam.ui.faditor.keyframe.KeyframeSet before) {
                final com.fadcam.ui.faditor.keyframe.KeyframeSet after =
                        clip.getOverlayTransform() != null
                                ? clip.getOverlayTransform().copy()
                                : new com.fadcam.ui.faditor.keyframe.KeyframeSet();
                undoManager.recordAction(new EditActions.LambdaAction("Move video overlay",
                        () -> {
                            restoreOverlayTransform(clip, after);
                        },
                        () -> {
                            restoreOverlayTransform(clip, before);
                        }));
            }
        };
    }

    /** Undo/redo helper: restore a PiP transform snapshot in place + refresh the layer. */
    private void restoreOverlayTransform(@NonNull Clip clip,
            @NonNull com.fadcam.ui.faditor.keyframe.KeyframeSet snapshot) {
        com.fadcam.ui.faditor.keyframe.KeyframeSet kf = clip.getOverlayTransform();
        if (kf == null) {
            kf = new com.fadcam.ui.faditor.keyframe.KeyframeSet();
            clip.setOverlayTransform(kf);
        }
        kf.copyFrom(snapshot);
        if (overlayVideoLayer != null) {
            overlayVideoLayer.setPlayheadMs(lastPlayheadAbsoluteMs,
                    playerManager != null && playerManager.isPlaying());
        }
    }

    /**
     * M-COMP-2b: place a picked video as a floating overlay (PiP) starting at the
     * playhead — mirrors {@link #onVideoAssetPicked}'s background import (the copy +
     * duration probe ANR lesson), then creates an overlay {@link Clip} on the default
     * "video" layer with the standard top-right-corner starter transform (keyframes at
     * t=0 — the same convention preview AND export sample via KeyframeSet.valueAt).
     * One undo step; persists via {@code Timeline.overlayClips} (schema v8 stamp).
     */
    private void onOverlayVideoPicked(@NonNull Uri pickedUri) {
        showRemuxProgress();
        final Uri srcUri = pickedUri;
        assetImportExecutor.execute(() -> {
            Uri resolvedUri = srcUri;
            long durationMs = -1;
            try {
                resolvedUri = copyUriToInternalStorage(srcUri, "videos");
                try {
                    getContentResolver().takePersistableUriPermission(
                            resolvedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    FLog.w(TAG, "Could not take persistable URI permission", e);
                }
                durationMs = getVideoDuration(resolvedUri);
            } catch (Exception e) {
                FLog.e(TAG, "Failed to import overlay video (IO)", e);
            }

            final Uri videoUri = resolvedUri;
            final long finalDuration = durationMs;
            runOnUiThread(() -> {
                hideRemuxProgress();
                if (isFinishing() || isDestroyed() || project == null) return;
                if (finalDuration <= 0) {
                    Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                final Clip overlay = new Clip(videoUri, finalDuration);
                overlay.setLayerId("video");
                overlay.setOverlayStartMs(Math.max(0, lastPlayheadAbsoluteMs));
                overlay.setAudioMuted(true); // pixels only in preview AND export (M-EXPORT-1 rule)
                com.fadcam.ui.faditor.keyframe.KeyframeSet kf =
                        new com.fadcam.ui.faditor.keyframe.KeyframeSet();
                kf.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.X).put(0L,
                        com.fadcam.ui.faditor.compositor.OverlayVideoPreviewView.DEFAULT_X,
                        com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
                kf.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y).put(0L,
                        com.fadcam.ui.faditor.compositor.OverlayVideoPreviewView.DEFAULT_Y,
                        com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
                kf.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE).put(0L,
                        com.fadcam.ui.faditor.compositor.OverlayVideoPreviewView.DEFAULT_SCALE,
                        com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
                overlay.setOverlayTransform(kf);

                project.getTimeline().addOverlayClip(overlay);
                syncTimelineOverlays();
                undoManager.recordAction(new EditActions.LambdaAction("Add video overlay",
                        () -> { project.getTimeline().addOverlayClip(overlay); syncTimelineOverlays(); },
                        () -> { project.getTimeline().removeOverlayClip(overlay); syncTimelineOverlays(); }));
                scheduleAutoSave();
                Toast.makeText(this, R.string.faditor_pip_added, Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * Decode-once renderer for a sheet, cache-validated by SpriteSheet object
     * identity so a project reload (new model objects, possibly re-sliced
     * geometry) re-decodes once instead of serving stale pixels. A null
     * renderer (missing art) is cached too — no per-frame retry storm; the
     * overlay draws the MISSING placeholder (S7 rule).
     */
    private com.fadcam.ui.faditor.sprite.SpriteSheetRenderer spriteRendererFor(
            @NonNull String sheetId) {
        com.fadcam.ui.faditor.sprite.SpriteSheet sheet =
                project != null ? project.spriteSheetById(sheetId) : null;
        if (sheet == null) return null;
        android.util.Pair<com.fadcam.ui.faditor.sprite.SpriteSheet,
                com.fadcam.ui.faditor.sprite.SpriteSheetRenderer> cached =
                spriteRendererCache.get(sheetId);
        if (cached != null && cached.first == sheet) return cached.second;
        if (cached != null && cached.second != null) cached.second.recycle();
        com.fadcam.ui.faditor.sprite.SpriteSheetRenderer r =
                com.fadcam.ui.faditor.sprite.SpriteSheetRenderer.load(this, sheet);
        spriteRendererCache.put(sheetId, android.util.Pair.create(sheet, r));
        return r;
    }

    /**
     * Build an OPEN_DOCUMENT picker intent that grants PERSISTABLE read access.
     * (ACTION_GET_CONTENT cannot be persisted, so its URIs go blank once the
     * process is killed — the cause of imported images vanishing after idle.)
     */
    @NonNull
    private Intent openDocumentIntent(@NonNull String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType(mime);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    /** Launch the system picker to choose a PNG/image to overlay on the video. */
    private void pickImageOverlay() {
        if (inCropMode) {
            exitCropMode(false);
        }
        overlayImagePickerLauncher.launch(openDocumentIntent("image/*"));
    }

    /** Add a picked image as a draggable/scalable overlay on the video. */
    private void onOverlayImagePicked(@NonNull Uri imageUri) {
        if (project == null) return;
        try {
            getContentResolver().takePersistableUriPermission(
                    imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            FLog.w(TAG, "Could not take persistable URI permission for overlay", e);
        }
        final com.fadcam.ui.faditor.model.TextOverlayItem item =
                com.fadcam.ui.faditor.model.TextOverlayItem.createImage(
                        imageUri.toString(), 0.5f, 0.5f, 0.30f);
        project.getTimeline().addTextOverlay(item);
        overlayLayer.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleTextOverlays(project.getTimeline()), overlayLayerCallback());
        syncTimelineOverlays();
        undoManager.recordAction(new EditActions.LambdaAction("Add image overlay",
                () -> project.getTimeline().addTextOverlay(item),
                () -> project.getTimeline().removeTextOverlay(item)));
        scheduleAutoSave();
    }

    /**
     * P1 (reliable cross-layer path): add a picked image as a brand-NEW floating
     * layer track above the master. Reuses the SAME image payload the M-COMP-1 /
     * M-EXPORT-1 preview+export path already renders ({@link
     * com.fadcam.ui.faditor.model.TextOverlayItem#createImage}, fed to
     * {@code TextOverlayLayer} via {@link
     * com.fadcam.ui.faditor.compositor.LayerPreviewController#visibleTextOverlays}),
     * and the SAME project-bundle asset copy the other imported assets use
     * ({@link #importInsertedAsset}, which yields a URI that serializes as a
     * portable {@code project://} path — see {@code ProjectStorage#toStorageUri}).
     * The image is dropped onto a fresh TEXT-kind {@link
     * com.fadcam.ui.faditor.layers.LayerTrackDef} sitting on TOP of every existing
     * layer (highest zIndex — reuses the Phase-P z convention), at the current
     * playhead with a bounded {@value #IMAGE_CLIP_DURATION_MS}ms window. ONE undo
     * step covers track-creation + item-add (mirrors {@link
     * #stageCreateLayerAndMoveItem}'s create+assign+prune pattern).
     */
    private void onImageAsNewLayerPicked(@NonNull Uri pickedUri) {
        if (project == null) return;
        try {
            getContentResolver().takePersistableUriPermission(
                    pickedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            FLog.w(TAG, "Could not take persistable URI permission for image layer", e);
        }
        // Portable copy into the project bundle (same as every other imported asset).
        final Uri storedUri = importInsertedAsset(
                pickedUri, com.fadcam.ui.faditor.assetbrowser.AssetItem.Type.IMAGE, null);

        final Timeline timeline = project.getTimeline();

        // Create a new TEXT-kind layer track ABOVE all existing layers.
        final String newTrackId = timeline.createLayerTrack(
                com.fadcam.ui.faditor.layers.TrackKind.TEXT,
                "Image " + (timeline.getLayers().size() + 1)); // TODO(strings)
        final com.fadcam.ui.faditor.layers.LayerTrackDef createdDef =
                timeline.getLayerTrackDef(newTrackId);
        final int topZ = topLayerZIndex(timeline) + 1;
        timeline.getOrCreateTrackFlags(newTrackId).zIndex = topZ;

        // Build the image payload at the current playhead, bounded window.
        long playheadMs = editorTimeline != null ? editorTimeline.getPlayheadPositionMs() : 0;
        final com.fadcam.ui.faditor.model.TextOverlayItem item =
                com.fadcam.ui.faditor.model.TextOverlayItem.createImage(
                        storedUri.toString(), 0.5f, 0.5f, 0.30f);
        item.setLayerId(newTrackId);
        item.setTimeRange(playheadMs, playheadMs + IMAGE_CLIP_DURATION_MS);

        Runnable redo = () -> {
            if (createdDef != null) timeline.restoreLayerTrackDef(createdDef);
            timeline.getOrCreateTrackFlags(newTrackId).zIndex = topZ;
            timeline.addTextOverlay(item);
            refreshAfterOverlayLayerChange();
        };
        Runnable undo = () -> {
            timeline.removeTextOverlay(item);
            timeline.removeLayerTrackDef(newTrackId);
            timeline.setTrackFlags(newTrackId, null);
            refreshAfterOverlayLayerChange();
        };
        redo.run();
        undoManager.recordAction(new EditActions.LambdaAction("Add image as new layer", redo, undo));
        scheduleAutoSave();
        Toast.makeText(this, "Image added as new layer", Toast.LENGTH_SHORT).show(); // TODO(strings)
    }

    /**
     * Highest persisted {@code zIndex} across the floating (text/sticker/image/…)
     * layer band, or 0 when nothing has ever been z-ordered. Used to place a
     * newly-created layer above ({@code +1}) or below ({@code min-1}) the stack,
     * matching the Phase-P {@code moveTrackZ} convention (higher z = painted on
     * top / drawn as the upper row).
     */
    private int topLayerZIndex(@NonNull Timeline timeline) {
        int max = 0;
        boolean any = false;
        for (com.fadcam.ui.faditor.layers.Track t : timeline.getLayers()) {
            if (!any || t.getZIndex() > max) { max = t.getZIndex(); any = true; }
        }
        return any ? max : 0;
    }

    /** Lowest persisted {@code zIndex} across the floating layer band (see {@link #topLayerZIndex}). */
    private int bottomLayerZIndex(@NonNull Timeline timeline) {
        int min = 0;
        boolean any = false;
        for (com.fadcam.ui.faditor.layers.Track t : timeline.getLayers()) {
            if (!any || t.getZIndex() < min) { min = t.getZIndex(); any = true; }
        }
        return any ? min : 0;
    }

    /**
     * Shared refresh after any change to the floating-overlay layer set (add/move/
     * new-layer): re-feed the preview overlay from the shared visibility authority,
     * resync the timeline rows, and refresh preview visibility — the exact trio the
     * existing overlay mutations call individually.
     */
    private void refreshAfterOverlayLayerChange() {
        if (project == null) return;
        if (overlayLayer != null) {
            overlayLayer.setData(
                    com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleTextOverlays(
                            project.getTimeline()), overlayLayerCallback());
            overlayLayer.invalidate();
        }
        syncTimelineOverlays();
        refreshPreviewOverlayVisibility();
        if (editorTimeline != null) editorTimeline.invalidate();
    }

    // ── P2: reliable cross-layer move (button-driven — closes the "sandwich" blocker) ──

    /**
     * The move-clip dialog for a SELECTED LAYER ITEM (P2 reliable path). Explicit
     * buttons — never the fragile drag engine — that let the user put an item onto a
     * new layer above/below (enabling the layer sandwich they want) or shift it to an
     * adjacent existing layer. Each action is ONE undo step. Delete stays available.
     */
    // ── G2: general advanced menu — peek/expand bottom sheet (contract §2/§3) ──

    /** Lazily created, lives in the root FrameLayout ABOVE editor_root so peek
     *  mode overlays only the bottom strip while timeline + preview stay live. */
    @Nullable private ObjectMenuSheet objectMenuSheet;

    @NonNull
    private ObjectMenuSheet ensureObjectMenuSheet() {
        if (objectMenuSheet == null) {
            objectMenuSheet = new ObjectMenuSheet(this);
            android.view.ViewGroup root =
                    (android.view.ViewGroup) findViewById(R.id.editor_root).getParent();
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM);
            root.addView(objectMenuSheet, lp);
            // G3: the focused keyframeable property drives the top ribbon.
            objectMenuSheet.setFocusListener(prop -> {
                ribbonProp = prop;
                refreshKeyframeRibbon();
            });
        }
        return objectMenuSheet;
    }

    // ── G3: top keyframe ribbon — rides the preview's lower edge while a
    //    keyframeable property is focused; the timeline stays fully clear ──

    @Nullable private android.widget.LinearLayout keyframeRibbon;
    @Nullable private ObjectMenuSheet.Prop ribbonProp;
    @Nullable private TextView ribbonLabel, ribbonDiamond;

    private void ensureKeyframeRibbon() {
        if (keyframeRibbon != null) return;
        float d = getResources().getDisplayMetrics().density;
        keyframeRibbon = new android.widget.LinearLayout(this);
        keyframeRibbon.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        keyframeRibbon.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xE61C1C1E);
        bg.setCornerRadius(18 * d);
        keyframeRibbon.setBackground(bg);
        int padH = (int) (10 * d);
        keyframeRibbon.setPadding(padH, (int) (2 * d), padH, (int) (2 * d));
        keyframeRibbon.setElevation(10 * d);

        ribbonLabel = new TextView(this);
        ribbonLabel.setTextColor(0xFF999999);
        ribbonLabel.setTextSize(12);
        ribbonLabel.setPadding(0, 0, (int) (6 * d), 0);
        keyframeRibbon.addView(ribbonLabel);

        TextView prev = ribbonGlyph("◀");
        prev.setOnClickListener(v -> {
            if (ribbonProp != null) ribbonProp.prevKey();
        });
        keyframeRibbon.addView(prev);

        ribbonDiamond = ribbonGlyph("◇");
        ribbonDiamond.setTextSize(18);
        ribbonDiamond.setOnClickListener(v -> {
            if (ribbonProp == null) return;
            // On a key → delete it; off a key → drop one (contract §3 add/del).
            if (ribbonProp.onKeyAt(lastPlayheadAbsoluteMs)) ribbonProp.deleteKey();
            else ribbonProp.dropKey();
            refreshKeyframeRibbon();
            if (objectMenuSheet != null && objectMenuSheet.isShowing()) {
                objectMenuSheet.onPlayheadChanged(lastPlayheadAbsoluteMs);
            }
        });
        keyframeRibbon.addView(ribbonDiamond);

        TextView next = ribbonGlyph("▶");
        next.setOnClickListener(v -> {
            if (ribbonProp != null) ribbonProp.nextKey();
        });
        keyframeRibbon.addView(next);

        android.widget.FrameLayout playerContainer = findViewById(R.id.player_container);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        // Clear of the caption-style strip that also rides the preview bottom.
        lp.bottomMargin = (int) (58 * d);
        playerContainer.addView(keyframeRibbon, lp);
        keyframeRibbon.setVisibility(View.GONE);
    }

    @NonNull
    private TextView ribbonGlyph(@NonNull String glyph) {
        float d = getResources().getDisplayMetrics().density;
        TextView v = new TextView(this);
        v.setText(glyph);
        v.setTextColor(0xFFCCCCCC);
        v.setTextSize(15);
        v.setGravity(android.view.Gravity.CENTER);
        v.setPadding((int) (10 * d), (int) (6 * d), (int) (10 * d), (int) (6 * d));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        v.setBackgroundResource(tv.resourceId);
        return v;
    }

    /** Show/hide + repaint the ribbon from the focused property's key state. */
    private void refreshKeyframeRibbon() {
        if (ribbonProp == null) {
            if (keyframeRibbon != null) keyframeRibbon.setVisibility(View.GONE);
            return;
        }
        ensureKeyframeRibbon();
        keyframeRibbon.setVisibility(View.VISIBLE);
        ribbonLabel.setText(ribbonProp.label());
        boolean on = ribbonProp.onKeyAt(lastPlayheadAbsoluteMs);
        ribbonDiamond.setText(on ? "◆" : "◇");
        ribbonDiamond.setTextColor(on ? 0xFF4CAF50 : 0xFFAAAAAA);
    }

    // ── G4: preview manipulation handles — tap-select a layer-row item and its
    //    bounding box + scale corners + rotate stalk appear over the preview,
    //    wired to the SAME keyframe-aware transform writes as the G2 menu ──

    @Nullable private com.fadcam.ui.faditor.overlay.PreviewHandlesOverlay previewHandlesOverlay;

    @NonNull
    private com.fadcam.ui.faditor.overlay.PreviewHandlesOverlay ensurePreviewHandlesOverlay() {
        if (previewHandlesOverlay == null) {
            float d = getResources().getDisplayMetrics().density;
            previewHandlesOverlay =
                    new com.fadcam.ui.faditor.overlay.PreviewHandlesOverlay(this);
            // Above the text/sprite/caption layers (XML, elevation 0), below the
            // keyframe ribbon (10dp) so the ribbon stays tappable during a drag.
            previewHandlesOverlay.setElevation(8 * d);
            android.widget.FrameLayout playerContainer = findViewById(R.id.player_container);
            playerContainer.addView(previewHandlesOverlay,
                    new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return previewHandlesOverlay;
    }

    /**
     * G4 routing from {@code onItemSelectionChanged}: text/image + sprite get
     * handle targets (their transform models + G2 write conventions exist);
     * audio/PiP/visualizer/caption → handles hidden (their targets come online
     * with their §2 Prop adapters, same staging as the G2 menu).
     */
    private void updatePreviewHandlesForSelection(
            @Nullable com.fadcam.ui.faditor.layers.TimedItem item) {
        if (item != null && item.getTextOverlay() != null) {
            ensurePreviewHandlesOverlay().setTarget(textHandlesTarget(item.getTextOverlay()));
        } else if (item != null && item.getSprite() != null) {
            ensurePreviewHandlesOverlay().setTarget(spriteHandlesTarget(item.getSprite()));
        } else if (previewHandlesOverlay != null) {
            previewHandlesOverlay.setTarget(null);
        }
        if (previewHandlesOverlay != null) {
            previewHandlesOverlay.setPlayheadMs(lastPlayheadAbsoluteMs);
        }
    }

    // ── G7: one-time gesture coach-mark (contract §6/§7) ──────────────────
    /** Pref flag (in {@code faditor_ui}): the per-item gesture coach-mark has been shown. */
    private static final String PREF_COACHMARK_ITEM_GESTURES = "coachmark_item_gestures_shown";
    /** Live coach-mark banner view, if one is on screen (null otherwise). */
    @Nullable private View gestureCoachMark;

    /**
     * The first time the user ever selects a timeline item, surface a one-time,
     * non-blocking banner teaching the three invisible per-item gestures
     * (double-tap = edit, hold = its menu, drag = move). Hold and double-tap are
     * undiscoverable, so JoyRaptor explicitly asked for a first-run hint (contract §6).
     * Shows once ever (persisted), auto-dismisses after a few seconds or on tap,
     * and is fully wrapped so a layout hiccup can never break selection.
     */
    private void maybeShowGestureCoachMark() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("faditor_ui", MODE_PRIVATE);
            if (prefs.getBoolean(PREF_COACHMARK_ITEM_GESTURES, false)) return;
            if (gestureCoachMark != null) return; // already up this session
            prefs.edit().putBoolean(PREF_COACHMARK_ITEM_GESTURES, true).apply();

            View rootView = findViewById(R.id.editor_root);
            if (rootView == null || !(rootView.getParent() instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup root = (android.view.ViewGroup) rootView.getParent();

            float d = getResources().getDisplayMetrics().density;
            android.widget.LinearLayout card = new android.widget.LinearLayout(this);
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (14 * d);
            card.setPadding(pad, (int) (12 * d), pad, (int) (12 * d));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xF21E1E1E);
            bg.setCornerRadius(14 * d);
            bg.setStroke((int) (1 * d), 0x554CAF50);
            card.setBackground(bg);
            card.setElevation(12 * d);

            TextView title = new TextView(this);
            title.setText(R.string.faditor_coachmark_gestures_title);
            title.setTextColor(0xFF4CAF50);
            title.setTextSize(13);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            card.addView(title);

            TextView body = new TextView(this);
            body.setText(R.string.faditor_coachmark_gestures_body);
            body.setTextColor(0xFFEEEEEE);
            body.setTextSize(13);
            android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = (int) (4 * d);
            card.addView(body, blp);

            TextView gotIt = new TextView(this);
            gotIt.setText(R.string.faditor_coachmark_got_it);
            gotIt.setTextColor(0xFF4CAF50);
            gotIt.setTextSize(13);
            gotIt.setTypeface(gotIt.getTypeface(), android.graphics.Typeface.BOLD);
            gotIt.setGravity(android.view.Gravity.END);
            android.widget.LinearLayout.LayoutParams glp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.topMargin = (int) (8 * d);
            card.addView(gotIt, glp);

            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            lp.topMargin = (int) (72 * d); // clear of the top bar
            lp.leftMargin = lp.rightMargin = (int) (16 * d);
            gestureCoachMark = card;
            root.addView(card, lp);

            card.setAlpha(0f);
            card.setTranslationY(-8 * d);
            card.animate().alpha(1f).translationY(0f).setDuration(220).start();

            gotIt.setOnClickListener(v -> dismissGestureCoachMark());
            card.setOnClickListener(v -> dismissGestureCoachMark());
            card.postDelayed(this::dismissGestureCoachMark, 7000);
        } catch (Exception e) {
            FLog.e(TAG, "coach-mark show failed (non-fatal)", e);
        }
    }

    /** Animate the coach-mark banner out and detach it (idempotent, null-safe). */
    private void dismissGestureCoachMark() {
        final View card = gestureCoachMark;
        if (card == null) return;
        gestureCoachMark = null;
        float d = getResources().getDisplayMetrics().density;
        card.animate().alpha(0f).translationY(-8 * d).setDuration(180).withEndAction(() -> {
            if (card.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) card.getParent()).removeView(card);
            }
        }).start();
    }

    /**
     * Handles target for a text/image overlay. Box = the EXACT laid-out
     * {@link com.fadcam.ui.faditor.overlay.TextOverlayLayer} child for this item
     * (same view the user sees — no duplicated measure math; both layers are
     * MATCH_PARENT siblings in player_container so coordinates line up). Writes
     * mirror {@link #overlayMenuProp}'s setter: armed → record/update keys at
     * the playhead, unarmed → static setters. ONE undo step per gesture via
     * {@link #recordOverlayMenuUndo}.
     */
    @NonNull
    private com.fadcam.ui.faditor.overlay.PreviewHandlesOverlay.Target textHandlesTarget(
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem o) {
        return new com.fadcam.ui.faditor.overlay.PreviewHandlesOverlay.Target() {
            @Nullable com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot before;

            @Override
            public boolean frame(long timeMs, @NonNull android.graphics.RectF outRect) {
                if (project == null
                        || !project.getTimeline().getTextOverlays().contains(o)
                        || !o.isVisibleAt(timeMs) || overlayLayer == null) {
                    return false;
                }
                for (int i = 0; i < overlayLayer.getChildCount(); i++) {
                    View v = overlayLayer.getChildAt(i);
                    if (v.getTag() == o) {
                        android.widget.FrameLayout.LayoutParams lp =
                                (android.widget.FrameLayout.LayoutParams) v.getLayoutParams();
                        if (lp.width <= 0 || lp.height <= 0) return false; // pre-layout
                        outRect.set(lp.leftMargin, lp.topMargin,
                                lp.leftMargin + lp.width, lp.topMargin + lp.height);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public float rotationDeg(long timeMs) { return o.animatedRotation(timeMs); }

            @Override
            public float centerX(long timeMs) { return o.animatedCenterX(timeMs); }

            @Override
            public float centerY(long timeMs) { return o.animatedCenterY(timeMs); }

            @Override
            public float sizeFraction(long timeMs) { return o.animatedSizeFraction(timeMs); }

            @NonNull
            @Override
            public android.graphics.RectF videoRect() { return computeCanvasRect(); }

            @Override
            public void beginGesture() { before = o.snapshotTransform(); }

            @Override
            public void moveTo(float normCx, float normCy, long timeMs) {
                if (o.isArmed()) {
                    o.addPropertyKeyframeAt(
                            com.fadcam.ui.faditor.keyframe.KeyframeSet.X, timeMs, normCx);
                    o.addPropertyKeyframeAt(
                            com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, timeMs, normCy);
                } else {
                    o.setCenter(normCx, normCy);
                }
                refreshTextAfterHandleWrite();
            }

            @Override
            public void scaleTo(float sizeFraction, long timeMs) {
                if (o.isArmed()) {
                    o.addPropertyKeyframeAt(
                            com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, timeMs, sizeFraction);
                } else {
                    o.setSizeFraction(sizeFraction);
                }
                refreshTextAfterHandleWrite();
            }

            @Override
            public void rotateTo(float deg, long timeMs) {
                if (o.isArmed()) {
                    o.addPropertyKeyframeAt(
                            com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION, timeMs, deg);
                } else {
                    o.setRotationDeg(deg);
                }
                refreshTextAfterHandleWrite();
            }

            @Override
            public void commit(@NonNull String what) {
                if (before != null) recordOverlayMenuUndo(o, before, what + " overlay");
                before = null;
                syncTimelineOverlays();
                if (objectMenuSheet != null && objectMenuSheet.isShowing()) {
                    objectMenuSheet.onPlayheadChanged(lastPlayheadAbsoluteMs);
                }
            }
        };
    }

    /** Light per-drag-frame refresh: reposition text children, no rebuild. */
    private void refreshTextAfterHandleWrite() {
        if (overlayLayer != null) overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
    }

    /**
     * Handles target for a sprite. Box math mirrors SpriteOverlayView#drawSprite
     * (height fraction × cell aspect); writes/undo mirror {@link #spriteMenuProp}.
     */
    @NonNull
    private com.fadcam.ui.faditor.overlay.PreviewHandlesOverlay.Target spriteHandlesTarget(
            @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem s) {
        return new com.fadcam.ui.faditor.overlay.PreviewHandlesOverlay.Target() {
            @Nullable com.fadcam.ui.faditor.sprite.SpriteOverlayItem.TransformSnapshot before;

            @Override
            public boolean frame(long timeMs, @NonNull android.graphics.RectF outRect) {
                if (project == null
                        || !project.getTimeline().getSpriteOverlays().contains(s)
                        || !s.isVisibleAt(timeMs)) {
                    return false;
                }
                android.graphics.RectF r = computeCanvasRect();
                if (r.width() <= 0 || r.height() <= 0) return false;
                float cx = r.left + s.animatedCenterX(timeMs) * r.width();
                float cy = r.top + s.animatedCenterY(timeMs) * r.height();
                float h = s.animatedSizeFraction(timeMs) * r.height();
                com.fadcam.ui.faditor.sprite.SpriteSheetRenderer renderer =
                        spriteRendererFor(s.getSheetId());
                float aspect = renderer != null ? renderer.cellAspect() : 1f;
                float w = h * (aspect > 0 ? aspect : 1f);
                outRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
                return true;
            }

            @Override
            public float rotationDeg(long timeMs) { return s.animatedRotation(timeMs); }

            @Override
            public float centerX(long timeMs) { return s.animatedCenterX(timeMs); }

            @Override
            public float centerY(long timeMs) { return s.animatedCenterY(timeMs); }

            @Override
            public float sizeFraction(long timeMs) { return s.animatedSizeFraction(timeMs); }

            @NonNull
            @Override
            public android.graphics.RectF videoRect() { return computeCanvasRect(); }

            @Override
            public void beginGesture() { before = s.snapshotTransform(); }

            @Override
            public void moveTo(float normCx, float normCy, long timeMs) {
                if (s.isArmed()) {
                    s.addPropertyKeyframeAt(
                            com.fadcam.ui.faditor.keyframe.KeyframeSet.X, timeMs, normCx);
                    s.addPropertyKeyframeAt(
                            com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, timeMs, normCy);
                } else {
                    s.setCenter(normCx, normCy);
                }
                refreshSpriteAfterHandleWrite();
            }

            @Override
            public void scaleTo(float sizeFraction, long timeMs) {
                if (s.isArmed()) {
                    s.addPropertyKeyframeAt(
                            com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, timeMs, sizeFraction);
                } else {
                    s.setSizeFraction(sizeFraction);
                }
                refreshSpriteAfterHandleWrite();
            }

            @Override
            public void rotateTo(float deg, long timeMs) {
                if (s.isArmed()) {
                    s.addPropertyKeyframeAt(
                            com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION, timeMs, deg);
                } else {
                    s.setRotationDeg(deg);
                }
                refreshSpriteAfterHandleWrite();
            }

            @Override
            public void commit(@NonNull String what) {
                if (before != null) recordSpriteMenuUndo(s, before, what + " sprite");
                before = null;
                syncTimelineOverlays();
                if (objectMenuSheet != null && objectMenuSheet.isShowing()) {
                    objectMenuSheet.onPlayheadChanged(lastPlayheadAbsoluteMs);
                }
            }
        };
    }

    /** Light per-drag-frame refresh: re-evaluate sprite transforms + repaint. */
    private void refreshSpriteAfterHandleWrite() {
        if (spriteOverlayView != null) spriteOverlayView.setPlayheadMs(lastPlayheadAbsoluteMs);
    }

    /**
     * G2 (gesture contract §2): the general advanced menu for a text/image
     * overlay — general keyframeable property rows (position/scale/rotation/
     * opacity, each with a G2-basic keyframe diamond), the object's layer
     * actions, header delete, and "More…" into the same type editor double-tap
     * opens. Supersedes the interim showLayerItemActionsDialog list dialog.
     * One undo step per slider gesture / keyframe drop (TransformSnapshot).
     */
    private void showObjectMenuSheetForTextOverlay(
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem o) {
        if (project == null) return;
        final Timeline timeline = project.getTimeline();
        final String K_X = com.fadcam.ui.faditor.keyframe.KeyframeSet.X;
        final String K_Y = com.fadcam.ui.faditor.keyframe.KeyframeSet.Y;
        final String K_SCALE = com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE;
        final String K_ROT = com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION;
        final String K_OP = com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY;

        ObjectMenuSheet.ValueFormat pct = v -> Math.round(v * 100f) + "%";
        ObjectMenuSheet.ValueFormat deg = v -> Math.round(normDeg(v)) + "°";

        // Contract §2 general order: Transform (position · scale · rotation),
        // then Opacity. Peek still defaults to Opacity (the everyday row).
        java.util.List<ObjectMenuSheet.Prop> props = new java.util.ArrayList<>();
        props.add(overlayMenuProp(o, K_X, "Pos X", 0f, 1f, pct,         // TODO(strings)
                ms -> o.animatedCenterX(ms)));
        props.add(overlayMenuProp(o, K_Y, "Pos Y", 0f, 1f, pct,         // TODO(strings)
                ms -> o.animatedCenterY(ms)));
        props.add(overlayMenuProp(o, K_SCALE, "Scale", 0.02f, 0.6f, pct, // TODO(strings)
                ms -> o.animatedSizeFraction(ms)));
        props.add(overlayMenuProp(o, K_ROT, "Rotate", -180f, 180f, deg, // TODO(strings)
                ms -> normDeg(o.animatedRotation(ms))));
        props.add(overlayMenuProp(o, K_OP, "Opacity", 0f, 1f, pct,      // TODO(strings)
                ms -> o.animatedOpacity(ms)));

        java.util.List<ObjectMenuSheet.Action> actions = new java.util.ArrayList<>();
        actions.add(new ObjectMenuSheet.Action("New layer above", false, // TODO(strings)
                () -> moveOverlayItemToNewLayer(o, true)));
        actions.add(new ObjectMenuSheet.Action("New layer below", false, // TODO(strings)
                () -> moveOverlayItemToNewLayer(o, false)));
        // Adjacent-layer moves only make sense with >1 floating layer present.
        java.util.List<com.fadcam.ui.faditor.layers.Track> layers = timeline.getLayers();
        int rowIdx = overlayItemRowIndex(o, layers);
        if (layers.size() > 1 && rowIdx >= 0) {
            if (rowIdx > 0) { // not already the top row (row 0 = highest z)
                actions.add(new ObjectMenuSheet.Action("Move to layer ▲", false, // TODO(strings)
                        () -> moveOverlayItemToAdjacentLayer(o, true)));
            }
            if (rowIdx < layers.size() - 1) {
                actions.add(new ObjectMenuSheet.Action("Move to layer ▼", false, // TODO(strings)
                        () -> moveOverlayItemToAdjacentLayer(o, false)));
            }
        }

        final com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot[] sliderBefore =
                new com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot[1];
        ObjectMenuSheet.GestureHooks hooks = new ObjectMenuSheet.GestureHooks() {
            @Override public void onSliderStart() { sliderBefore[0] = o.snapshotTransform(); }
            @Override public void onSliderCommit(@NonNull String what) {
                if (sliderBefore[0] != null) recordOverlayMenuUndo(o, sliderBefore[0], what);
                sliderBefore[0] = null;
            }
        };

        String title = o.isImage() ? "Image"                            // TODO(strings)
                : (o.getText().length() > 18 ? o.getText().substring(0, 18) + "…" : o.getText());
        Integer swatch = o.isImage() ? null : o.getColorInt();
        ensureObjectMenuSheet().show(title, swatch, props, actions,
                () -> showTextOverlayEditor(o),
                () -> deleteTextOverlayWithConfirmation(o),
                hooks, lastPlayheadAbsoluteMs, null);
    }

    /** Normalize degrees into the slider's [-180, 180) window. */
    private static float normDeg(float v) {
        return ((v % 360f) + 540f) % 360f - 180f;
    }

    /**
     * G2: the same general advanced menu for a SPRITE instance (SpriteOverlayItem
     * mirrors TextOverlayItem's transform/keyframe shape deliberately). More… =
     * the sprite palette (same as double-tap); delete = confirm + one undo step.
     */
    private void showObjectMenuSheetForSprite(
            @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem s) {
        if (project == null) return;
        final String K_ROT = com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION;
        final String K_OP = com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY;

        ObjectMenuSheet.ValueFormat pct = v -> Math.round(v * 100f) + "%";
        ObjectMenuSheet.ValueFormat deg = v -> Math.round(normDeg(v)) + "°";

        java.util.List<ObjectMenuSheet.Prop> props = new java.util.ArrayList<>();
        props.add(spriteMenuProp(s, com.fadcam.ui.faditor.keyframe.KeyframeSet.X,
                "Pos X", 0f, 1f, pct, ms -> s.animatedCenterX(ms)));      // TODO(strings)
        props.add(spriteMenuProp(s, com.fadcam.ui.faditor.keyframe.KeyframeSet.Y,
                "Pos Y", 0f, 1f, pct, ms -> s.animatedCenterY(ms)));      // TODO(strings)
        props.add(spriteMenuProp(s, com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE,
                "Scale", 0.01f, 1f, pct, ms -> s.animatedSizeFraction(ms))); // TODO(strings)
        props.add(spriteMenuProp(s, K_ROT, "Rotate", -180f, 180f, deg,    // TODO(strings)
                ms -> normDeg(s.animatedRotation(ms))));
        props.add(spriteMenuProp(s, K_OP, "Opacity", 0f, 1f, pct,         // TODO(strings)
                ms -> s.animatedOpacity(ms)));

        final com.fadcam.ui.faditor.sprite.SpriteOverlayItem.TransformSnapshot[] sliderBefore =
                new com.fadcam.ui.faditor.sprite.SpriteOverlayItem.TransformSnapshot[1];
        ObjectMenuSheet.GestureHooks hooks = new ObjectMenuSheet.GestureHooks() {
            @Override public void onSliderStart() { sliderBefore[0] = s.snapshotTransform(); }
            @Override public void onSliderCommit(@NonNull String what) {
                if (sliderBefore[0] != null) recordSpriteMenuUndo(s, sliderBefore[0], what);
                sliderBefore[0] = null;
            }
        };

        com.fadcam.ui.faditor.sprite.SpriteSheet sheet = project.spriteSheetById(s.getSheetId());
        String title = sheet != null ? sheet.getName() : "Sprite";        // TODO(strings)
        Runnable onDelete = () -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Remove sprite?")                                // TODO(strings)
                .setNegativeButton("Cancel", null)                         // TODO(strings)
                .setPositiveButton("Remove", (d, w) -> {                   // TODO(strings)
                    project.getTimeline().removeSpriteOverlay(s);
                    syncTimelineOverlays();
                    refreshSpritePreviewData();
                    undoManager.recordAction(new EditActions.LambdaAction("Delete sprite",
                            () -> { project.getTimeline().removeSpriteOverlay(s);
                                    syncTimelineOverlays(); refreshSpritePreviewData(); },
                            () -> { project.getTimeline().addSpriteOverlay(s);
                                    syncTimelineOverlays(); refreshSpritePreviewData(); }));
                    scheduleAutoSave();
                })
                .show();
        ensureObjectMenuSheet().show(title, null, props,
                new java.util.ArrayList<>(), // sprites: one-per-lane (T8), no layer actions yet
                this::openSpritePalette, onDelete, hooks, lastPlayheadAbsoluteMs, null);
    }

    /** Sprite twin of {@link #overlayMenuProp}: keyframe-aware write + diamond. */
    @NonNull
    private ObjectMenuSheet.Prop spriteMenuProp(
            @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem s,
            @NonNull String key, @NonNull String label, float min, float max,
            @NonNull ObjectMenuSheet.ValueFormat fmt, @NonNull ObjectMenuSheet.Getter get) {
        ObjectMenuSheet.Setter set = (v, ms) -> {
            if (s.isArmed()) {
                s.addPropertyKeyframeAt(key, ms, v);
            } else if (com.fadcam.ui.faditor.keyframe.KeyframeSet.X.equals(key)) {
                s.setCenter(v, s.getCenterY());
            } else if (com.fadcam.ui.faditor.keyframe.KeyframeSet.Y.equals(key)) {
                s.setCenter(s.getCenterX(), v);
            } else if (com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE.equals(key)) {
                s.setSizeFraction(v);
            } else if (com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION.equals(key)) {
                s.setRotationDeg(v);
            } else if (com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY.equals(key)) {
                s.setOpacity(v);
            }
            refreshSpriteAfterMenuWrite();
        };
        ObjectMenuSheet.OnKeyQuery onKey = ms -> {
            com.fadcam.ui.faditor.keyframe.KeyframeTrack tr = s.getKeyframes().get(key);
            if (tr == null) return false;
            long local = Math.max(0, ms - s.getStartMs());
            for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) {
                if (Math.abs(k.timeMs - local) <= 66) return true;
            }
            return false;
        };
        Runnable dropKey = () -> {
            com.fadcam.ui.faditor.sprite.SpriteOverlayItem.TransformSnapshot before =
                    s.snapshotTransform();
            if (s.isArmed()) {
                s.addPropertyKeyframeAt(key, lastPlayheadAbsoluteMs,
                        get.at(lastPlayheadAbsoluteMs));
            } else {
                s.addKeyframeAt(lastPlayheadAbsoluteMs);
            }
            recordSpriteMenuUndo(s, before, "Add keyframe");
            refreshSpriteAfterMenuWrite();
        };
        // G3: diamond swipe-nav + long-press-delete (sprite twins).
        Runnable prevKey = () -> jumpToAdjacentKey(s.getKeyframes().get(key), s.getStartMs(), false);
        Runnable nextKey = () -> jumpToAdjacentKey(s.getKeyframes().get(key), s.getStartMs(), true);
        Runnable deleteKey = () -> {
            com.fadcam.ui.faditor.keyframe.KeyframeTrack tr = s.getKeyframes().get(key);
            Long hit = keyUnderPlayheadLocalMs(tr, s.getStartMs());
            if (hit == null) return;
            com.fadcam.ui.faditor.sprite.SpriteOverlayItem.TransformSnapshot before =
                    s.snapshotTransform();
            s.getKeyframes().removeKey(key, hit);
            recordSpriteMenuUndo(s, before, "Delete keyframe");
            refreshSpriteAfterMenuWrite();
        };
        return new ObjectMenuSheet.Prop(key, label, min, max, fmt, get, set, onKey, dropKey,
                prevKey, nextKey, deleteKey);
    }

    private void refreshSpriteAfterMenuWrite() {
        if (spriteOverlayView != null) {
            spriteOverlayView.setPlayheadMs(lastPlayheadAbsoluteMs);
            spriteOverlayView.invalidate();
        }
        syncTimelineOverlays();
    }

    /** Re-feed the sprite preview layer after add/remove (palette-delete parity). */
    private void refreshSpritePreviewData() {
        if (spriteOverlayView != null && project != null) {
            spriteOverlayView.setData(
                    com.fadcam.ui.faditor.compositor.LayerPreviewController
                            .visibleSpriteItems(project.getTimeline()),
                    spriteOverlayCallback());
            spriteOverlayView.invalidate();
        }
    }

    /** One undo step per committed sprite-menu gesture (mirrors onSpriteManipulated). */
    private void recordSpriteMenuUndo(
            @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem s,
            @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem.TransformSnapshot before,
            @NonNull String description) {
        com.fadcam.ui.faditor.sprite.SpriteOverlayItem.TransformSnapshot after =
                s.snapshotTransform();
        if (before.matches(after)) return;
        undoManager.recordAction(new EditActions.LambdaAction(description,
                () -> { s.restoreTransform(after);
                        if (spriteOverlayView != null) spriteOverlayView.invalidate(); },
                () -> { s.restoreTransform(before);
                        if (spriteOverlayView != null) spriteOverlayView.invalidate(); }));
        scheduleAutoSave();
    }

    /** Build one §2 property row adapter: keyframe-aware write + diamond state. */
    @NonNull
    private ObjectMenuSheet.Prop overlayMenuProp(
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem o,
            @NonNull String key, @NonNull String label, float min, float max,
            @NonNull ObjectMenuSheet.ValueFormat fmt, @NonNull ObjectMenuSheet.Getter get) {
        ObjectMenuSheet.Setter set = (v, ms) -> {
            if (o.isArmed()) {
                // Armed = value changes record/update a keyframe at the playhead
                // (AUTO mode, contract §2 [JOYRAPTOR-CAN-FLIP]) — mirrors the shipped
                // opacity-slider behavior in buildOverlayAnimationControls.
                o.addPropertyKeyframeAt(key, ms, v);
            } else if (com.fadcam.ui.faditor.keyframe.KeyframeSet.X.equals(key)) {
                o.setCenter(v, o.getCenterY());
            } else if (com.fadcam.ui.faditor.keyframe.KeyframeSet.Y.equals(key)) {
                o.setCenter(o.getCenterX(), v);
            } else if (com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE.equals(key)) {
                o.setSizeFraction(v);
            } else if (com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION.equals(key)) {
                o.setRotationDeg(v);
            } else if (com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY.equals(key)) {
                o.setOpacity(v);
            }
            if (overlayLayer != null) {
                overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
                overlayLayer.rebuild();
            }
            syncTimelineOverlays();
        };
        ObjectMenuSheet.OnKeyQuery onKey = ms -> overlayPropOnKeyAt(o, key, ms);
        Runnable dropKey = () -> {
            com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot before =
                    o.snapshotTransform();
            if (o.isArmed()) {
                o.addPropertyKeyframeAt(key, lastPlayheadAbsoluteMs,
                        get.at(lastPlayheadAbsoluteMs));
            } else {
                // First key ARMS the overlay — record the whole current pose,
                // same as the type editor's "Add keyframe" button.
                o.addKeyframeAt(lastPlayheadAbsoluteMs);
            }
            recordOverlayMenuUndo(o, before, "Add keyframe");
            if (overlayLayer != null) overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
            syncTimelineOverlays();
        };
        // G3: diamond swipe-nav + long-press-delete.
        Runnable prevKey = () -> jumpToAdjacentKey(o.getKeyframes().get(key), o.getStartMs(), false);
        Runnable nextKey = () -> jumpToAdjacentKey(o.getKeyframes().get(key), o.getStartMs(), true);
        Runnable deleteKey = () -> {
            com.fadcam.ui.faditor.keyframe.KeyframeTrack tr = o.getKeyframes().get(key);
            Long hit = keyUnderPlayheadLocalMs(tr, o.getStartMs());
            if (hit == null) return;
            com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot before =
                    o.snapshotTransform();
            o.getKeyframes().removeKey(key, hit);
            recordOverlayMenuUndo(o, before, "Delete keyframe");
            if (overlayLayer != null) {
                overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
                overlayLayer.rebuild();
            }
            syncTimelineOverlays();
        };
        return new ObjectMenuSheet.Prop(key, label, min, max, fmt, get, set, onKey, dropKey,
                prevKey, nextKey, deleteKey);
    }

    /** G3: seek the playhead to the nearest key strictly before/after it. */
    private void jumpToAdjacentKey(@Nullable com.fadcam.ui.faditor.keyframe.KeyframeTrack tr,
                                   long itemStartMs, boolean forward) {
        if (tr == null || editorTimeline == null) return;
        long local = Math.max(0, lastPlayheadAbsoluteMs - itemStartMs);
        Long best = null;
        for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) {
            if (forward ? k.timeMs > local + 66 : k.timeMs < local - 66) {
                if (best == null || (forward ? k.timeMs < best : k.timeMs > best)) {
                    best = k.timeMs;
                }
            }
        }
        if (best != null) editorTimeline.seekToTimelineMs(itemStartMs + best);
    }

    /** G3: the exact key time (item-local) sitting under the playhead, or null. */
    @Nullable
    private Long keyUnderPlayheadLocalMs(
            @Nullable com.fadcam.ui.faditor.keyframe.KeyframeTrack tr, long itemStartMs) {
        if (tr == null) return null;
        long local = Math.max(0, lastPlayheadAbsoluteMs - itemStartMs);
        for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) {
            if (Math.abs(k.timeMs - local) <= 66) return k.timeMs;
        }
        return null;
    }

    /** Is the playhead sitting on (within ~2 frames of) a key of this property? */
    private boolean overlayPropOnKeyAt(@NonNull com.fadcam.ui.faditor.model.TextOverlayItem o,
                                       @NonNull String key, long playheadMs) {
        com.fadcam.ui.faditor.keyframe.KeyframeTrack tr = o.getKeyframes().get(key);
        if (tr == null) return false;
        long local = Math.max(0, playheadMs - o.getStartMs());
        for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) {
            if (Math.abs(k.timeMs - local) <= 66) return true;
        }
        return false;
    }

    /** One undo step per committed menu gesture (slider drag / diamond tap). */
    private void recordOverlayMenuUndo(
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem o,
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot before,
            @NonNull String description) {
        com.fadcam.ui.faditor.model.TextOverlayItem.TransformSnapshot after =
                o.snapshotTransform();
        if (after.matches(before)) return;
        undoManager.recordAction(new EditActions.OverlayTransformAction(o, before, after, description));
        scheduleAutoSave();
    }

    /**
     * Row index of the layer holding {@code o} within {@code getLayers()} (0 = top
     * row = highest z). {@code -1} if the item's track isn't in the floating band
     * (shouldn't happen for a text/image/sticker item). The item's {@code layerId}
     * is {@code null} for the default "text" track.
     */
    private int overlayItemRowIndex(@NonNull com.fadcam.ui.faditor.model.TextOverlayItem o,
                                    @NonNull java.util.List<com.fadcam.ui.faditor.layers.Track> layers) {
        String layerId = o.getLayerId();
        String effective = (layerId == null) ? "text" : layerId;
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).getId().equals(effective)) return i;
        }
        return -1;
    }

    /**
     * P2 core: create a brand-new TEXT-kind layer track ABOVE ({@code above=true},
     * highest z) or BELOW ({@code above=false}, lowest z) the current floating stack
     * and move {@code o} onto it — this is what builds the user's layer sandwich. The
     * old track is pruned if it was user-created and is now empty. ONE undo step
     * (create + reassign + z; undo reverts layerId, deletes the track, prunes flags).
     */
    private void moveOverlayItemToNewLayer(@NonNull com.fadcam.ui.faditor.model.TextOverlayItem o,
                                           boolean above) {
        if (project == null) return;
        final Timeline timeline = project.getTimeline();
        final String fromLayerId = o.getLayerId(); // null = default "text" track
        final String newTrackId = timeline.createLayerTrack(
                com.fadcam.ui.faditor.layers.TrackKind.TEXT,
                (o.isImage() ? "Image " : "Text ") + (timeline.getLayers().size() + 1)); // TODO(strings)
        final com.fadcam.ui.faditor.layers.LayerTrackDef createdDef =
                timeline.getLayerTrackDef(newTrackId);
        final int newZ = above ? topLayerZIndex(timeline) + 1 : bottomLayerZIndex(timeline) - 1;

        Runnable redo = () -> {
            if (createdDef != null) timeline.restoreLayerTrackDef(createdDef);
            timeline.getOrCreateTrackFlags(newTrackId).zIndex = newZ;
            o.setLayerId(newTrackId);
            if (fromLayerId != null) maybeRemoveEmptyLayerTrack(fromLayerId);
            refreshAfterOverlayLayerChange();
        };
        Runnable undo = () -> {
            o.setLayerId(fromLayerId);
            timeline.removeLayerTrackDef(newTrackId);
            timeline.setTrackFlags(newTrackId, null);
            refreshAfterOverlayLayerChange();
        };
        redo.run();
        undoManager.recordAction(new EditActions.LambdaAction(
                above ? "New layer above" : "New layer below", redo, undo));
        scheduleAutoSave();
        Toast.makeText(this, above ? "Moved to new layer above" : "Moved to new layer below",
                Toast.LENGTH_SHORT).show(); // TODO(strings)
    }

    /**
     * P2: move {@code o} onto the ADJACENT existing layer — the row above ({@code
     * up=true}) or below — reusing the Phase-P row order ({@code getLayers()} sorted
     * DESC by z; row 0 = top). Just reassigns {@code layerId} (no track creation);
     * the vacated source track is pruned if empty. ONE undo step.
     */
    private void moveOverlayItemToAdjacentLayer(@NonNull com.fadcam.ui.faditor.model.TextOverlayItem o,
                                                boolean up) {
        if (project == null) return;
        final Timeline timeline = project.getTimeline();
        java.util.List<com.fadcam.ui.faditor.layers.Track> layers = timeline.getLayers();
        int rowIdx = overlayItemRowIndex(o, layers);
        int target = rowIdx + (up ? -1 : 1);
        if (rowIdx < 0 || target < 0 || target >= layers.size()) return;
        final String fromLayerId = o.getLayerId();
        String targetId = layers.get(target).getId();
        final String toLayerId = "text".equals(targetId) ? null : targetId;

        Runnable redo = () -> {
            o.setLayerId(toLayerId);
            if (fromLayerId != null) maybeRemoveEmptyLayerTrack(fromLayerId);
            refreshAfterOverlayLayerChange();
        };
        Runnable undo = () -> { o.setLayerId(fromLayerId); refreshAfterOverlayLayerChange(); };
        redo.run();
        undoManager.recordAction(new EditActions.LambdaAction(
                up ? "Move to layer up" : "Move to layer down", redo, undo));
        scheduleAutoSave();
        Toast.makeText(this, up ? "Moved up a layer" : "Moved down a layer",
                Toast.LENGTH_SHORT).show(); // TODO(strings)
    }

    /** Simple dialog to edit an overlay's text and colour, or delete it. */
    private void showTextOverlayEditor(
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem item) {
        // Image overlays have nothing to type — offer animation + delete only.
        if (item.isImage()) {
            int ipad = (int) (16 * getResources().getDisplayMetrics().density);
            android.widget.LinearLayout iroot = new android.widget.LinearLayout(this);
            iroot.setOrientation(android.widget.LinearLayout.VERTICAL);
            iroot.setPadding(ipad, ipad, ipad, 0);
            TextView desc = new TextView(this);
            desc.setText(R.string.faditor_image_overlay_desc);
            desc.setTextColor(0xFFBBBBBB);
            iroot.addView(desc);
            iroot.addView(buildOverlayAnimationControls(item));
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.faditor_image_overlay_title)
                    .setView(iroot)
                    .setNeutralButton(R.string.faditor_text_delete, (d, w) -> {
                        project.getTimeline().removeTextOverlay(item);
                        overlayLayer.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleTextOverlays(project.getTimeline()),
                                overlayLayerCallback());
                        syncTimelineOverlays();
                        undoManager.recordAction(new EditActions.LambdaAction("Delete image overlay",
                                () -> project.getTimeline().removeTextOverlay(item),
                                () -> project.getTimeline().addTextOverlay(item)));
                        scheduleAutoSave();
                    })
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, 0);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.faditor_text_hint);
        // Don't pre-fill the placeholder hint text as real content.
        if (!getString(R.string.faditor_text_hint).equals(item.getText())) {
            input.setText(item.getText());
        }
        input.setSelectAllOnFocus(true);
        input.setTextColor(0xFFFFFFFF);
        root.addView(input);

        // Colour swatches
        final int[] colors = {0xFFFFFFFF, 0xFF000000, 0xFFF44336, 0xFFFFEB3B,
                0xFF4CAF50, 0xFF2196F3, 0xFFFF9800, 0xFFE91E63};
        final int[] chosen = {item.getColorInt()};
        android.widget.LinearLayout swatchRow = new android.widget.LinearLayout(this);
        swatchRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        swatchRow.setPadding(0, pad, 0, 0);
        int sw = (int) (32 * getResources().getDisplayMetrics().density);
        for (int c : colors) {
            View swatch = new View(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(sw, sw);
            lp.rightMargin = (int) (8 * getResources().getDisplayMetrics().density);
            swatch.setLayoutParams(lp);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(c);
            bg.setStroke((int) (2 * getResources().getDisplayMetrics().density), 0xFF888888);
            swatch.setBackground(bg);
            swatch.setOnClickListener(v -> {
                chosen[0] = c;
                input.setTextColor(c == 0xFF000000 ? 0xFF888888 : c);
            });
            swatchRow.addView(swatch);
        }
        root.addView(swatchRow);

        // Font selector — distinct personalities + custom fonts
        final String[][] fonts = {
                {"popular", "Popular"},
                {"popular_italic", "Popular Italic"},
                {"designer", "Designer"},
                {"trendy", "Trendy"},
                {"light", "Light"},
                {"sans_light", "Airy"},
                {"sans_thin", "Thin"},
                {"sans_medium", "Medium"},
                {"sans_black", "Heavy"},
                {"condensed", "Condensed"},
                {"condensed_bold", "Condensed Bold"},
                {"classy", "Classy"},
                {"classy_italic", "Classy Italic"},
                {"serif_bold", "Bold Serif"},
                {"serif_italic", "Serif Italic"},
                {"country", "Country"},
                {"dramatic", "Dramatic"},
                {"mono", "Mono"},
                {"mono_bold", "Mono Bold"},
                {"casual", "Casual"},
                {"cursive", "Cursive"},
        };

        // Scan for custom .ttf/.otf fonts in the assets bucket
        File fontsDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES), "FadCam/fonts");
        List<String[]> customFonts = new ArrayList<>();
        if (fontsDir.exists()) {
            File[] fontFiles = fontsDir.listFiles((dir, name) ->
                    name.toLowerCase().endsWith(".ttf") || name.toLowerCase().endsWith(".otf"));
            if (fontFiles != null) {
                for (File f : fontFiles) {
                    String key = "file:" + f.getAbsolutePath();
                    String name = f.getName().replaceFirst("\\.[^.]+$", "");
                    customFonts.add(new String[]{key, name});
                }
            }
        }

        final String[] chosenFont = {item.getFontFamily()};

        TextView fontLabel = new TextView(this);
        fontLabel.setText("FONT");
        fontLabel.setTextColor(0xFF888888);
        fontLabel.setTextSize(12);
        fontLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        fontLabel.setAllCaps(true);
        fontLabel.setLetterSpacing(0.06f);
        fontLabel.setPadding(0, pad, 0, pad / 2);
        root.addView(fontLabel);

        android.widget.HorizontalScrollView fontScroll = new android.widget.HorizontalScrollView(this);
        android.widget.LinearLayout fontRow = new android.widget.LinearLayout(this);
        fontRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        for (String[] f : fonts) {
            TextView fontChip = new TextView(this);
            fontChip.setText(f[1]);
            fontChip.setTextColor(f[0].equals(chosenFont[0]) ? 0xFF4CAF50 : 0xFFCCCCCC);
            fontChip.setTypeface(getTypefaceForKey(f[0]));
            fontChip.setTextSize(14);
            fontChip.setPadding(pad, pad / 2, pad, pad / 2);
            android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
            chipBg.setCornerRadius(8 * getResources().getDisplayMetrics().density);
            chipBg.setColor(f[0].equals(chosenFont[0]) ? 0xFF1B3A20 : 0xFF2A2A2A);
            chipBg.setStroke((int) getResources().getDisplayMetrics().density, f[0].equals(chosenFont[0]) ? 0xFF4CAF50 : 0xFF444444);
            fontChip.setBackground(chipBg);
            fontChip.setOnClickListener(v -> {
                chosenFont[0] = f[0];
                // Refresh chip colors
                for (int ci = 0; ci < fontRow.getChildCount(); ci++) {
                    TextView chip = (TextView) fontRow.getChildAt(ci);
                    String chipKey = ci < fonts.length ? fonts[ci][0] : customFonts.get(ci - fonts.length)[0];
                    boolean selected = chipKey.equals(chosenFont[0]);
                    chip.setTextColor(selected ? 0xFF4CAF50 : 0xFFCCCCCC);
                    android.graphics.drawable.GradientDrawable bg =
                            (android.graphics.drawable.GradientDrawable) chip.getBackground();
                    bg.setColor(selected ? 0xFF1B3A20 : 0xFF2A2A2A);
                    bg.setStroke((int) getResources().getDisplayMetrics().density, selected ? 0xFF4CAF50 : 0xFF444444);
                }
            });
            fontRow.addView(fontChip);
        }
        // Add custom fonts
        for (String[] f : customFonts) {
            TextView fontChip = new TextView(this);
            fontChip.setText(f[1] + " ★");
            fontChip.setTextColor(f[0].equals(chosenFont[0]) ? 0xFF4CAF50 : 0xFFCCCCCC);
            try {
                fontChip.setTypeface(android.graphics.Typeface.createFromFile(f[0].substring(5)));
            } catch (Exception ignored) { }
            fontChip.setTextSize(14);
            fontChip.setPadding(pad, pad / 2, pad, pad / 2);
            android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
            chipBg.setCornerRadius(8 * getResources().getDisplayMetrics().density);
            chipBg.setColor(f[0].equals(chosenFont[0]) ? 0xFF1B3A20 : 0xFF2A2A2A);
            chipBg.setStroke((int) getResources().getDisplayMetrics().density, f[0].equals(chosenFont[0]) ? 0xFF4CAF50 : 0xFF444444);
            fontChip.setBackground(chipBg);
            fontChip.setOnClickListener(v -> {
                chosenFont[0] = f[0];
                for (int ci = 0; ci < fontRow.getChildCount(); ci++) {
                    TextView chip = (TextView) fontRow.getChildAt(ci);
                    String chipKey = ci < fonts.length ? fonts[ci][0] : customFonts.get(ci - fonts.length)[0];
                    boolean selected = chipKey.equals(chosenFont[0]);
                    chip.setTextColor(selected ? 0xFF4CAF50 : 0xFFCCCCCC);
                    android.graphics.drawable.GradientDrawable bg =
                            (android.graphics.drawable.GradientDrawable) chip.getBackground();
                    bg.setColor(selected ? 0xFF1B3A20 : 0xFF2A2A2A);
                    bg.setStroke((int) getResources().getDisplayMetrics().density, selected ? 0xFF4CAF50 : 0xFF444444);
                }
            });
            fontRow.addView(fontChip);
        }
        if (!customFonts.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("★ = custom fonts from Pictures/FadCam/fonts/");
            hint.setTextColor(0xFF666666);
            hint.setTextSize(10);
            hint.setPadding(0, pad / 4, 0, 0);
            root.addView(hint);
        }
        fontScroll.addView(fontRow);
        root.addView(fontScroll);
        root.addView(buildOverlayAnimationControls(item));

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.faditor_text_edit_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String txt = input.getText().toString();
                    if (txt.trim().isEmpty()) {
                        // No text entered → don't leave an empty "Enter text" ghost.
                        project.getTimeline().removeTextOverlay(item);
                        overlayLayer.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleTextOverlays(project.getTimeline()),
                                overlayLayerCallback());
                        syncTimelineOverlays();
                        scheduleAutoSave();
                        return;
                    }
                    item.setText(txt);
                    item.setColorInt(chosen[0]);
                    item.setFontFamily(chosenFont[0]);
                    overlayLayer.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleTextOverlays(project.getTimeline()),
                            overlayLayerCallback());
                    syncTimelineOverlays();
                    // Record ADD undo only once the overlay is committed with real text
                    // (placeholder is still in the timeline from addTextOverlay()).
                    if (project.getTimeline().getTextOverlays().contains(item)
                            && !textOverlayAddRecorded.contains(item)) {
                        textOverlayAddRecorded.add(item);
                        undoManager.recordAction(new EditActions.LambdaAction("Add text overlay",
                                () -> project.getTimeline().addTextOverlay(item),
                                () -> project.getTimeline().removeTextOverlay(item)));
                    }
                    scheduleAutoSave();
                })
                .setNeutralButton(R.string.faditor_text_delete, (d, w) -> {
                    boolean wasCommitted = textOverlayAddRecorded.remove(item);
                    project.getTimeline().removeTextOverlay(item);
                    overlayLayer.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleTextOverlays(project.getTimeline()),
                            overlayLayerCallback());
                    syncTimelineOverlays();
                    // Only record a DELETE if this overlay had been committed (its ADD was
                    // recorded). Deleting a never-committed placeholder records nothing.
                    if (wasCommitted) {
                        undoManager.recordAction(new EditActions.LambdaAction("Delete text overlay",
                                () -> project.getTimeline().removeTextOverlay(item),
                                () -> project.getTimeline().addTextOverlay(item)));
                    }
                    scheduleAutoSave();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    // Clean up a never-filled placeholder so it can't get stuck.
                    String cur = item.getText();
                    if (cur == null || cur.trim().isEmpty()
                            || cur.equals(getString(R.string.faditor_text_hint))) {
                        project.getTimeline().removeTextOverlay(item);
                        overlayLayer.setData(com.fadcam.ui.faditor.compositor.LayerPreviewController.visibleTextOverlays(project.getTimeline()),
                                overlayLayerCallback());
                        syncTimelineOverlays();
                        scheduleAutoSave();
                    }
                })
                .show();
    }

    /**
     * Build the keyframe-animation controls for an overlay: add a keyframe at the
     * current playhead, set the visible time range, or clear animation. This is
     * the "direct manipulation" model — position/scale the overlay, then tap
     * "Add keyframe"; do it again at another time to animate between them.
     */
    @NonNull
    private View buildOverlayAnimationControls(
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem item) {
        float density = getResources().getDisplayMetrics().density;
        int gap = (int) (8 * density);

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(0, gap * 2, 0, 0);

        TextView header = new TextView(this);
        header.setText(R.string.faditor_kf_section);
        header.setTextColor(0xFF888888);
        header.setTextSize(12);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setAllCaps(true);
        header.setLetterSpacing(0.06f);
        box.addView(header);

        final TextView status = new TextView(this);
        status.setTextColor(0xFFAAAAAA);
        status.setTextSize(12);
        status.setPadding(0, gap / 2, 0, gap);
        box.addView(status);

        Runnable refreshStatus = () -> {
            int n = item.getKeyframes().get(com.fadcam.ui.faditor.keyframe.KeyframeSet.X) != null
                    ? item.getKeyframes().get(com.fadcam.ui.faditor.keyframe.KeyframeSet.X)
                            .keyframes.size() : 0;
            status.setText(n == 0
                    ? getString(R.string.faditor_kf_hint_start)
                    : getString(R.string.faditor_kf_hint_armed, n));
        };
        refreshStatus.run();

        // ── Opacity / transparency ──────────────────────────────────────
        // Mirrors the volume-keyframe model: when the overlay is ARMED (has
        // keyframes) the slider drops/updates an OPACITY keyframe at the
        // playhead (a fade); otherwise it sets the static opacity for the
        // whole overlay. Preview updates live.
        TextView opLabel = new TextView(this);
        opLabel.setText("OPACITY");
        opLabel.setTextColor(0xFF888888);
        opLabel.setTextSize(12);
        opLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        opLabel.setAllCaps(true);
        opLabel.setLetterSpacing(0.06f);
        opLabel.setPadding(0, gap, 0, gap / 2);
        box.addView(opLabel);

        android.widget.LinearLayout opRow = new android.widget.LinearLayout(this);
        opRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        opRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        final android.widget.SeekBar opBar = new android.widget.SeekBar(this);
        opBar.setMax(100);
        opBar.setProgress(Math.round(item.animatedOpacity(lastPlayheadAbsoluteMs) * 100f));
        android.widget.LinearLayout.LayoutParams opLp =
                new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        opBar.setLayoutParams(opLp);
        opRow.addView(opBar);

        final TextView opPct = new TextView(this);
        opPct.setTextColor(0xFFCCCCCC);
        opPct.setTextSize(13);
        opPct.setPadding(gap, 0, 0, 0);
        opPct.setText(opBar.getProgress() + "%");
        opRow.addView(opPct);
        box.addView(opRow);

        opBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                opPct.setText(progress + "%");
                if (!fromUser) return;
                float op = progress / 100f;
                if (item.isArmed()) {
                    item.addOpacityKeyframeAt(lastPlayheadAbsoluteMs, op);
                } else {
                    item.setOpacity(op);
                }
                if (overlayLayer != null) {
                    overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
                    overlayLayer.rebuild();
                }
                syncTimelineOverlays();
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                refreshStatus.run();
                scheduleAutoSave();
            }
        });

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);

        android.widget.Button addKf = new android.widget.Button(this);
        addKf.setText(R.string.faditor_kf_add);
        addKf.setAllCaps(false);
        addKf.setOnClickListener(v -> {
            item.addKeyframeAt(lastPlayheadAbsoluteMs);
            if (overlayLayer != null) overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
            syncTimelineOverlays();
            refreshStatus.run();
            scheduleAutoSave();
            Toast.makeText(this, R.string.faditor_kf_added, Toast.LENGTH_SHORT).show();
        });
        row.addView(addKf);

        android.widget.Button clearKf = new android.widget.Button(this);
        clearKf.setText(R.string.faditor_kf_clear);
        clearKf.setAllCaps(false);
        clearKf.setOnClickListener(v -> {
            item.clearKeyframes();
            // Also reset the visible range so an accidental "Start/End here"
            // can't leave the overlay hidden — fully back to static + always-on.
            item.setTimeRange(0, Long.MAX_VALUE);
            if (overlayLayer != null) overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
            syncTimelineOverlays();
            refreshStatus.run();
            scheduleAutoSave();
        });
        row.addView(clearKf);
        box.addView(row);

        // Time-range controls: clip the overlay's appearance to the playhead.
        android.widget.LinearLayout rangeRow = new android.widget.LinearLayout(this);
        rangeRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);

        android.widget.Button setStart = new android.widget.Button(this);
        setStart.setText(R.string.faditor_kf_set_start);
        setStart.setAllCaps(false);
        setStart.setOnClickListener(v -> {
            if (item.getEndMs() != Long.MAX_VALUE && lastPlayheadAbsoluteMs >= item.getEndMs()) {
                Toast.makeText(this, R.string.faditor_kf_range_invalid,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            item.setTimeRange(lastPlayheadAbsoluteMs, item.getEndMs());
            if (overlayLayer != null) overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
            syncTimelineOverlays();
            scheduleAutoSave();
            Toast.makeText(this, R.string.faditor_kf_range_set, Toast.LENGTH_SHORT).show();
        });
        rangeRow.addView(setStart);

        android.widget.Button setEnd = new android.widget.Button(this);
        setEnd.setText(R.string.faditor_kf_set_end);
        setEnd.setAllCaps(false);
        setEnd.setOnClickListener(v -> {
            if (lastPlayheadAbsoluteMs <= item.getStartMs()) {
                Toast.makeText(this, R.string.faditor_kf_range_invalid,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            item.setTimeRange(item.getStartMs(), lastPlayheadAbsoluteMs);
            if (overlayLayer != null) overlayLayer.setPlayheadMs(lastPlayheadAbsoluteMs);
            syncTimelineOverlays();
            scheduleAutoSave();
            Toast.makeText(this, R.string.faditor_kf_range_set, Toast.LENGTH_SHORT).show();
        });
        rangeRow.addView(setEnd);
        box.addView(rangeRow);

        return box;
    }

    // ── Transcript editing ───────────────────────────────────────────

    private void setupTranscriptPanel() {
        transcriptPanel = findViewById(R.id.transcript_panel);
        transcriptView = findViewById(R.id.transcript_view);
        transcriptReopenTab = findViewById(R.id.transcript_reopen_tab);
        transcriptBreakBtn = findViewById(R.id.transcript_break);
        transcriptProgress = findViewById(R.id.transcript_progress);
        transcriptProgressText = findViewById(R.id.transcript_progress_text);
        transcriptProgressBar = findViewById(R.id.transcript_progress_bar);
        transcriptModelChoice = findViewById(R.id.transcript_model_choice);
        transcriptVersionBar = findViewById(R.id.transcript_version_bar);
        transcriptVersionScroll = findViewById(R.id.transcript_version_scroll);
        if (transcriptPanel == null) return;

        if (transcriptionEngine == null) {
            transcriptionEngine = new com.fadcam.ui.faditor.transcript.TranscriptionEngine(this);
        }
        findViewById(R.id.transcript_model_fast).setOnClickListener(v ->
                startTranscription(com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType.FAST));
        findViewById(R.id.transcript_model_accurate).setOnClickListener(v ->
                startTranscription(com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType.ACCURATE));
        findViewById(R.id.transcript_model_whisper).setOnClickListener(v ->
                startTranscription(com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType.WHISPER_BASE_EN));

        // ── Search ──
        final View searchBar = findViewById(R.id.transcript_search_bar);
        final android.widget.EditText searchInput = findViewById(R.id.transcript_search_input);
        final TextView searchCount = findViewById(R.id.transcript_search_count);
        findViewById(R.id.transcript_search_btn).setOnClickListener(v -> {
            boolean show = searchBar.getVisibility() != View.VISIBLE;
            searchBar.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                searchInput.requestFocus();
                showSoftKeyboard(searchInput);
            } else {
                transcriptView.clearSearch();
                hideSoftKeyboard(searchInput);
            }
        });
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                int total = transcriptView.search(s.toString());
                searchCount.setText(total == 0 ? "0/0"
                        : transcriptView.getSearchCurrentOneBased() + "/" + total);
            }
        });
        findViewById(R.id.transcript_search_prev).setOnClickListener(v -> {
            int cur = transcriptView.moveSearch(-1);
            searchCount.setText(cur + "/" + transcriptView.getSearchTotal());
        });
        findViewById(R.id.transcript_search_next).setOnClickListener(v -> {
            int cur = transcriptView.moveSearch(1);
            searchCount.setText(cur + "/" + transcriptView.getSearchTotal());
        });
        findViewById(R.id.transcript_search_close).setOnClickListener(v -> {
            searchInput.setText("");
            transcriptView.clearSearch();
            searchBar.setVisibility(View.GONE);
            hideSoftKeyboard(searchInput);
        });

        transcriptView.setListener(new com.fadcam.ui.faditor.transcript.TranscriptPanelView.Listener() {
            @Override
            public void onSeekToMs(long sourceMs) {
                Clip clip = getSelectedClip();
                if (clip == null) return;
                long segStart = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
                long timelineMs = segStart + (long)
                        ((sourceMs - clip.getInPointMs()) / clip.getSpeedMultiplier());
                playerManager.pause();
                editorTimeline.seekToTimelineMs(timelineMs);
            }

            @Override
            public void onStrikesChanged() {
                // Live, non-destructive: update the clip's skip-list so preview
                // skips it and the timeline shows it immediately.
                syncRemovedSpansFromTranscript();
            }

            @Override
            public void onEditWord(int index, @NonNull String currentText) {
                showWordScrubDrawer(index);
            }

            @Override
            public void onLineBreaksChanged() {
                // Caption-only edit; no timeline recomputation needed, just persist.
                updateTranscriptBreakButton();
                scheduleAutoSave();
            }

            @Override
            public void onActiveWordChanged(int index) {
                updateTranscriptBreakButton();
            }
        });

        findViewById(R.id.transcript_close).setOnClickListener(v -> showTranscriptPanel(false));
        transcriptReopenTab.setOnClickListener(v -> showTranscriptPanel(true));
        findViewById(R.id.transcript_clean).setOnClickListener(v -> {
            if (currentTranscript == null) return;
            int n = currentTranscript.strikeFillers();
            transcriptView.invalidate();
            syncRemovedSpansFromTranscript();
            Toast.makeText(this, getString(R.string.faditor_transcript_cleaned, n),
                    Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.transcript_apply).setOnClickListener(v -> commitTranscript());

        if (transcriptBreakBtn != null) {
            transcriptBreakBtn.setOnClickListener(v -> {
                int idx = transcriptView != null ? transcriptView.getActiveIndex() : -1;
                if (idx < 0 || currentTranscript == null || idx >= currentTranscript.words.size()) {
                    return;
                }
                com.fadcam.ui.faditor.transcript.TranscriptWord w = currentTranscript.words.get(idx);
                w.forceLineBreakAfter = !w.forceLineBreakAfter;
                transcriptView.invalidate();
                updateTranscriptBreakButton();
                scheduleAutoSave();
            });
        }

        // Drag the left handle to resize the panel width.
        View handle = findViewById(R.id.transcript_resize_handle);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downRawX;
            int startWidth;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        startWidth = transcriptPanel.getWidth();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (downRawX - e.getRawX());
                        int screenW = getResources().getDisplayMetrics().widthPixels;
                        int w = Math.max((int) (screenW * 0.30f),
                                Math.min((int) (screenW * 0.92f), startWidth + dx));
                        ViewGroup.LayoutParams lp = transcriptPanel.getLayoutParams();
                        lp.width = w;
                        transcriptPanel.setLayoutParams(lp);
                        return true;
                }
                return false;
            }
        });

        // Bottom grab handle — swipe up to dismiss the transcript panel.
        View grabHandle = findViewById(R.id.transcript_grab_handle);
        grabHandle.setOnTouchListener(new View.OnTouchListener() {
            float downRawY;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = e.getRawY();
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        float dy = downRawY - e.getRawY();
                        float density = getResources().getDisplayMetrics().density;
                        if (dy > 30f * density) {
                            showTranscriptPanel(false);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /**
     * Highlight the caption-style chip matching {@code styleId} (the style of the clip
     * under the playhead) and un-highlight the rest, so the active style is obvious as the
     * playhead crosses clips with different caption styles.
     */
    private void highlightActiveCaptionChip(@Nullable String styleId) {
        if (captionStyleChips.isEmpty()) return;
        if (styleId != null && styleId.equals(highlightedCaptionStyleId)) return;
        highlightedCaptionStyleId = styleId;
        for (java.util.Map.Entry<String, TextView> e : captionStyleChips.entrySet()) {
            TextView chip = e.getValue();
            boolean active = e.getKey().equals(styleId);
            int pl = chip.getPaddingLeft(), pt = chip.getPaddingTop();
            int pr = chip.getPaddingRight(), pb = chip.getPaddingBottom();
            if (active) {
                chip.setBackgroundColor(0x554DD0E1); // soft cyan = active style
            } else {
                chip.setBackgroundResource(R.drawable.floating_button_item_bg);
            }
            chip.setPadding(pl, pt, pr, pb); // background swap resets padding
        }
    }

    // ── Animated captions ────────────────────────────────────────────

    private void setupCaptions() {
        captionsActive = true;
        captionOverlay = findViewById(R.id.caption_overlay);
        audioCaptionOverlay = findViewById(R.id.audio_caption_overlay);
        captionStyleBar = findViewById(R.id.caption_style_bar);
        wireCaptionKfArmShortcut();
        android.widget.LinearLayout row = findViewById(R.id.caption_style_row);
        if (captionOverlay == null || row == null) return;
        captionStyleChips.clear();
        highlightedCaptionStyleId = null;

        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        for (com.fadcam.ui.faditor.transcript.CaptionStyle s
                : com.fadcam.ui.faditor.transcript.CaptionStyle.presets()) {
            TextView chip = new TextView(this);
            chip.setText(s.label);
            chip.setTextColor(s.activeColor);
            chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            chip.setTextSize(14);
            chip.setPadding(pad, pad / 2, pad, pad / 2);
            chip.setBackgroundResource(R.drawable.floating_button_item_bg);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = pad / 2;
            chip.setLayoutParams(lp);
            chip.setTag(s.id); // so we can highlight the active clip's style chip
            captionStyleChips.put(s.id, chip);
            chip.setOnClickListener(v -> {
                boolean preferAudio = (editorTimeline.getSelectedAudioIndex() >= 0
                        && editorTimeline.getSelectedAudioIndex() < project.getTimeline().getAudioClips().size());
                if (preferAudio) {
                    AudioClip ac = project.getTimeline().getAudioClips().get(editorTimeline.getSelectedAudioIndex());
                    if (ac != null && ac.hasTranscript()) {
                        ac.setCaptionStyleId(s.id);
                        ac.setCaptionsEnabled(true);
                        activeCaptionIsAudio = true;
                        audioCaptionClipId = ac.getId();
                        bindAudioCaptionData(ac);
                        audioCaptionOverlay.setStyle(s);
                        audioCaptionOverlay.setVisibility(View.VISIBLE);
                        if (captionStyleBar != null) captionStyleBar.setVisibility(View.VISIBLE);
                        scheduleAutoSave();
                    }
                } else {
                    Clip cc = getSelectedClip();
                    if (cc != null) {
                        if (captionStyleKeyframeMode && cc.hasCaptionStyleKeyframes()) {
                            java.util.List<Clip.CaptionStyleKeyframe> beforeKfs =
                                    snapshotCaptionStyleKeyframes(cc);
                            com.fadcam.ui.faditor.captions.CaptionStyleKeyframeController.TapAction action =
                                    com.fadcam.ui.faditor.captions.CaptionStyleKeyframeController
                                            .tapActionFor(cc, lastSourcePositionInSegmentMs);
                            cc.addOrUpdateCaptionStyleKeyframe(lastSourcePositionInSegmentMs, s.id);
                            recordCaptionStyleKeyframeEdit(cc, beforeKfs);
                            cc.setCaptionsEnabled(true);
                            bindCaptionData(cc);
                            captionOverlay.setStyle(s);
                            captionOverlay.setVisibility(View.VISIBLE);
                            if (editorTimeline != null) editorTimeline.invalidate();
                            scheduleAutoSave();
                            refreshCaptionKeyframeDrawer();
                            Toast.makeText(this,
                                    action == com.fadcam.ui.faditor.captions.CaptionStyleKeyframeController.TapAction.REPLACE
                                            ? "Keyframe style replaced"
                                            : "Style keyframe dropped",
                                    Toast.LENGTH_SHORT).show();
                        } else if (cc.hasTranscript()) {
                            cc.setCaptionStyleId(s.id);
                            cc.setCaptionsEnabled(true);
                            activeCaptionIsAudio = false;
                            bindCaptionData(cc);
                            captionOverlay.setStyle(s);
                            captionOverlay.setVisibility(View.VISIBLE);
                            scheduleAutoSave();
                        }
                    }
                }
            });
            chip.setOnLongClickListener(v -> {
                showApplyStyleToAllClipsDialog(s.id, s.label);
                return true;
            });
            row.addView(chip);
        }
        // 6th pill: "Hidden" (eye-slash) — hides captions for THIS clip only (per-clip visibility).
        TextView hideChip = new TextView(this);
        hideChip.setText("visibility_off");
        hideChip.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.materialicons));
        hideChip.setTextColor(0xFF9E9E9E);
        hideChip.setTextSize(16);
        hideChip.setIncludeFontPadding(false);
        hideChip.setGravity(android.view.Gravity.CENTER);
        hideChip.setPadding(pad, pad / 2, pad, pad / 2);
        hideChip.setBackgroundResource(R.drawable.floating_button_item_bg);
        android.widget.LinearLayout.LayoutParams hlp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.rightMargin = pad / 2;
        hideChip.setLayoutParams(hlp);
        hideChip.setOnClickListener(v -> {
            boolean preferAudio = (editorTimeline.getSelectedAudioIndex() >= 0
                    && editorTimeline.getSelectedAudioIndex() < project.getTimeline().getAudioClips().size());
            if (preferAudio) {
                AudioClip ac = project.getTimeline().getAudioClips().get(editorTimeline.getSelectedAudioIndex());
                if (ac != null) {
                    ac.setCaptionsEnabled(false);
                    if (audioCaptionOverlay != null) audioCaptionOverlay.setVisibility(View.GONE);
                    scheduleAutoSave();
                    Toast.makeText(this, "Captions hidden — tap a style chip to show again", Toast.LENGTH_SHORT).show();
                }
            } else {
                Clip cc = getSelectedClip();
                if (cc != null) {
                    if (captionStyleKeyframeMode && cc.hasCaptionStyleKeyframes()) {
                        java.util.List<Clip.CaptionStyleKeyframe> beforeKfs =
                                snapshotCaptionStyleKeyframes(cc);
                        cc.addOrUpdateCaptionStyleKeyframe(lastSourcePositionInSegmentMs, "hidden");
                        recordCaptionStyleKeyframeEdit(cc, beforeKfs);
                        if (editorTimeline != null) editorTimeline.invalidate();
                        scheduleAutoSave();
                        refreshCaptionKeyframeDrawer();
                        if (captionOverlay != null) captionOverlay.setVisibility(View.GONE);
                    } else {
                        cc.setCaptionsEnabled(false);
                        if (captionOverlay != null) captionOverlay.setVisibility(View.GONE);
                        scheduleAutoSave();
                        Toast.makeText(this, "Captions hidden — tap a style chip to show again", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
        hideChip.setOnLongClickListener(v -> {
            showHideCaptionsOnAllClipsDialog();
            return true;
        });
        row.addView(hideChip);
    }

    /**
     * Long-press on the "Hidden" (eye-slash) pill: offer to hide captions on ALL clips in
     * the timeline (video + audio), mirroring the style chips' apply-to-all long-press.
     * Caption-style keyframes are left untouched (captionsEnabled=false gates rendering
     * entirely); undo restores each clip's prior enabled state. One undoable step.
     */
    private void showHideCaptionsOnAllClipsDialog() {
        if (project == null) return;
        java.util.List<Clip> clips = project.getTimeline().getClips();
        java.util.List<AudioClip> audioClips = project.getTimeline().getAudioClips();
        if (clips.isEmpty() && audioClips.isEmpty()) return;

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Hide captions on all clips?")
                .setMessage("Captions on every clip in the timeline will be hidden. "
                        + "Tap a style chip on a clip to show its captions again.")
                .setPositiveButton(android.R.string.ok, (d, w) -> hideCaptionsOnAllClips())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Hide captions on every video + audio clip, recorded as a single undoable step. */
    private void hideCaptionsOnAllClips() {
        if (project == null) return;
        final java.util.List<Clip> videoTargets =
                new java.util.ArrayList<>(project.getTimeline().getClips());
        final java.util.List<AudioClip> audioTargets =
                new java.util.ArrayList<>(project.getTimeline().getAudioClips());
        final java.util.List<Boolean> videoBeforeEnabled = new java.util.ArrayList<>();
        for (Clip c : videoTargets) videoBeforeEnabled.add(c.isCaptionsEnabled());
        final java.util.List<Boolean> audioBeforeEnabled = new java.util.ArrayList<>();
        for (AudioClip a : audioTargets) audioBeforeEnabled.add(a.isCaptionsEnabled());

        Runnable applyForward = () -> {
            for (Clip c : videoTargets) c.setCaptionsEnabled(false);
            for (AudioClip a : audioTargets) a.setCaptionsEnabled(false);
            refreshCaptionOverlayVisibilityAfterBulkHide();
        };
        Runnable restoreBackward = () -> {
            for (int i = 0; i < videoTargets.size(); i++) {
                videoTargets.get(i).setCaptionsEnabled(videoBeforeEnabled.get(i));
            }
            for (int i = 0; i < audioTargets.size(); i++) {
                audioTargets.get(i).setCaptionsEnabled(audioBeforeEnabled.get(i));
            }
            refreshCaptionOverlayVisibilityAfterBulkHide();
        };

        applyForward.run();
        undoManager.recordAction(new EditActions.LambdaAction(
                "Hide captions on all clips", applyForward, restoreBackward));
        scheduleAutoSave();
        Toast.makeText(this, "Captions hidden on all clips", Toast.LENGTH_SHORT).show();
    }

    /**
     * Sync both caption overlays' visibility with the bound clips' captionsEnabled state after
     * a bulk hide (or its undo), mirroring refreshActiveCaptionOverlaysAfterBulkStyleChange.
     */
    private void refreshCaptionOverlayVisibilityAfterBulkHide() {
        if (captionOverlay != null) {
            Clip cc = getSelectedClip();
            if (cc != null && cc.hasTranscript() && cc.isCaptionsEnabled()) {
                bindCaptionData(cc);
                captionOverlay.setVisibility(View.VISIBLE);
            } else {
                captionOverlay.setVisibility(View.GONE);
            }
        }
        if (audioCaptionOverlay != null) {
            AudioClip ac = audioCaptionClipId != null ? findAudioClipById(audioCaptionClipId) : null;
            if (ac != null && ac.hasTranscript() && ac.isCaptionsEnabled()) {
                bindAudioCaptionData(ac);
                audioCaptionOverlay.setVisibility(View.VISIBLE);
            } else {
                audioCaptionOverlay.setVisibility(View.GONE);
            }
        }
        if (editorTimeline != null) editorTimeline.invalidate();
    }

    /**
     * Long-press on a caption style chip: offer to apply {@code styleId} to ALL clips in
     * the timeline (video clips, and audio clips that carry their own caption style/track),
     * optionally also copying caption position and/or size from the long-pressed source clip.
     * Base style only — per-clip caption-style KEYFRAMES are untouched. Undoable as one step.
     */
    private void showApplyStyleToAllClipsDialog(@NonNull String styleId, @NonNull String styleLabel) {
        if (project == null) return;
        java.util.List<Clip> clips = project.getTimeline().getClips();
        java.util.List<AudioClip> audioClips = project.getTimeline().getAudioClips();
        if (clips.isEmpty() && audioClips.isEmpty()) return;

        // Source values to (optionally) copy come from the currently selected clip/audio clip
        // (i.e. whichever clip the long-pressed chip would apply to under a normal tap).
        boolean preferAudio = (editorTimeline.getSelectedAudioIndex() >= 0
                && editorTimeline.getSelectedAudioIndex() < audioClips.size());
        float srcCenterX, srcCenterY, srcSizeFraction;
        if (preferAudio) {
            AudioClip src = audioClips.get(editorTimeline.getSelectedAudioIndex());
            srcCenterX = src.getCaptionCenterX();
            srcCenterY = src.getCaptionCenterY();
            srcSizeFraction = src.getCaptionSizeFraction();
        } else {
            Clip src = getSelectedClip();
            if (src == null) {
                // Fall back to defaults matching Clip's own defaults.
                srcCenterX = 0.5f; srcCenterY = 0.82f; srcSizeFraction = 0.060f;
            } else {
                srcCenterX = src.getCaptionCenterX();
                srcCenterY = src.getCaptionCenterY();
                srcSizeFraction = src.getCaptionSizeFraction();
            }
        }
        final float fSrcCenterX = srcCenterX, fSrcCenterY = srcCenterY, fSrcSizeFraction = srcSizeFraction;

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);

        TextView helper = new TextView(this);
        helper.setText("Apply “" + styleLabel + "” to all clips in the timeline?");
        helper.setTextColor(0xFFBBBBBB);
        helper.setTextSize(13);
        root.addView(helper);

        final android.widget.CheckBox copyPosition = new android.widget.CheckBox(this);
        copyPosition.setText("Also apply caption position to all clips");
        copyPosition.setTextColor(0xFFFFFFFF);
        copyPosition.setChecked(false);
        android.widget.LinearLayout.LayoutParams posLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        posLp.topMargin = pad / 2;
        copyPosition.setLayoutParams(posLp);
        root.addView(copyPosition);

        final android.widget.CheckBox copySize = new android.widget.CheckBox(this);
        copySize.setText("Also apply caption size to all clips");
        copySize.setTextColor(0xFFFFFFFF);
        copySize.setChecked(false);
        android.widget.LinearLayout.LayoutParams sizeLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        sizeLp.topMargin = pad / 4;
        copySize.setLayoutParams(sizeLp);
        root.addView(copySize);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Apply style to all clips?")
                .setView(root)
                .setPositiveButton(android.R.string.ok, (d, w) -> applyCaptionStyleToAllClips(
                        styleId, copyPosition.isChecked(), copySize.isChecked(),
                        fSrcCenterX, fSrcCenterY, fSrcSizeFraction))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Apply {@code styleId} to every video clip and every audio clip (that has captions
     * data) in the timeline, optionally also copying caption position and/or size from the
     * long-pressed source clip. Recorded as a single undoable step that restores each clip's
     * prior style/position/size on undo.
     * <p>
     * Per-clip caption-style KEYFRAMES take priority over the base style at render time
     * (see {@link Clip#captionStyleAtClipMs(long)}, used by both the live preview and
     * {@code ExportManager}/{@code CompositeExportOverlay}). If a clip has any keyframes,
     * merely changing its base style is invisible — the keyframed value keeps winning. So
     * that "apply to all" actually makes the chosen style show on every clip, any existing
     * caption-style keyframes on the video targets are cleared as part of this same step
     * (and restored verbatim on undo).
     */
    private void applyCaptionStyleToAllClips(@NonNull String styleId,
                                              boolean copyPosition, boolean copySize,
                                              float srcCenterX, float srcCenterY, float srcSizeFraction) {
        if (project == null) return;
        java.util.List<Clip> clips = project.getTimeline().getClips();
        java.util.List<AudioClip> audioClips = project.getTimeline().getAudioClips();

        // Snapshot "before" state for every clip so undo can restore it exactly.
        final java.util.List<Clip> videoTargets = new java.util.ArrayList<>(clips);
        final java.util.List<String> videoBeforeStyle = new java.util.ArrayList<>();
        final java.util.List<float[]> videoBeforePosSize = new java.util.ArrayList<>();
        final java.util.List<java.util.List<Clip.CaptionStyleKeyframe>> videoBeforeKeyframes =
                new java.util.ArrayList<>();
        for (Clip c : videoTargets) {
            videoBeforeStyle.add(c.getCaptionStyleId());
            videoBeforePosSize.add(new float[]{c.getCaptionCenterX(), c.getCaptionCenterY(), c.getCaptionSizeFraction()});
            videoBeforeKeyframes.add(snapshotCaptionStyleKeyframes(c));
        }
        final java.util.List<AudioClip> audioTargets = new java.util.ArrayList<>(audioClips);
        final java.util.List<String> audioBeforeStyle = new java.util.ArrayList<>();
        final java.util.List<float[]> audioBeforePosSize = new java.util.ArrayList<>();
        for (AudioClip a : audioTargets) {
            audioBeforeStyle.add(a.getCaptionStyleId());
            audioBeforePosSize.add(new float[]{a.getCaptionCenterX(), a.getCaptionCenterY(), a.getCaptionSizeFraction()});
        }
        // Was keyframe-editing mode active for the clip currently selected? If that clip's
        // keyframes get cleared below, mode gets turned off the same way deleteCurrentCaptionStyleKeyframe() does.
        final Clip selectedAtApplyTime = getSelectedClip();

        Runnable applyForward = () -> {
            for (int i = 0; i < videoTargets.size(); i++) {
                Clip c = videoTargets.get(i);
                c.setCaptionStyleId(styleId);
                if (copyPosition) c.setCaptionCenter(srcCenterX, srcCenterY);
                if (copySize) c.setCaptionSizeFraction(srcSizeFraction);
                // Clear style keyframes so the newly-applied base style actually renders —
                // otherwise a keyframed clip keeps showing its old keyframed style everywhere
                // the keyframe track covers (preview AND export both read keyframes first).
                if (c.hasCaptionStyleKeyframes()) {
                    c.clearCaptionStyleKeyframes();
                    if (c == selectedAtApplyTime) captionStyleKeyframeMode = false;
                }
            }
            for (int i = 0; i < audioTargets.size(); i++) {
                AudioClip a = audioTargets.get(i);
                a.setCaptionStyleId(styleId);
                if (copyPosition) a.setCaptionCenter(srcCenterX, srcCenterY);
                if (copySize) a.setCaptionSizeFraction(srcSizeFraction);
            }
            refreshActiveCaptionOverlaysAfterBulkStyleChange();
        };
        Runnable restoreBackward = () -> {
            for (int i = 0; i < videoTargets.size(); i++) {
                Clip c = videoTargets.get(i);
                c.setCaptionStyleId(videoBeforeStyle.get(i));
                float[] ps = videoBeforePosSize.get(i);
                if (copyPosition) c.setCaptionCenter(ps[0], ps[1]);
                if (copySize) c.setCaptionSizeFraction(ps[2]);
                c.setCaptionStyleKeyframes(videoBeforeKeyframes.get(i));
                // Undo restores the keyframes that existed before apply-to-all, so restore
                // keyframe-editing mode to match (it was only turned off if we cleared them).
                if (c == selectedAtApplyTime && c.hasCaptionStyleKeyframes()) {
                    captionStyleKeyframeMode = true;
                }
            }
            for (int i = 0; i < audioTargets.size(); i++) {
                AudioClip a = audioTargets.get(i);
                a.setCaptionStyleId(audioBeforeStyle.get(i));
                float[] ps = audioBeforePosSize.get(i);
                if (copyPosition) a.setCaptionCenter(ps[0], ps[1]);
                if (copySize) a.setCaptionSizeFraction(ps[2]);
            }
            refreshActiveCaptionOverlaysAfterBulkStyleChange();
        };

        applyForward.run();
        undoManager.recordAction(new EditActions.LambdaAction(
                "Apply caption style \"" + styleId + "\" to all clips", applyForward, restoreBackward));
        scheduleAutoSave();
        Toast.makeText(this, "Style applied to all clips", Toast.LENGTH_SHORT).show();
    }

    /**
     * Refresh whichever caption overlay(s) are currently bound so the visible preview reflects
     * a bulk style change immediately (mirrors what the single-chip tap handler does for the
     * one clip it targets). Used by both the forward-apply and the undo/redo restore paths.
     */
    private void refreshActiveCaptionOverlaysAfterBulkStyleChange() {
        if (captionsActive) {
            Clip cc = getSelectedClip();
            if (cc != null && cc.hasTranscript()) {
                bindCaptionData(cc);
            }
        }
        AudioClip ac = audioCaptionClipId != null ? findAudioClipById(audioCaptionClipId) : null;
        if (ac != null) {
            bindAudioCaptionData(ac);
        }
        if (editorTimeline != null) editorTimeline.invalidate();
        // Bulk apply may have cleared the selected clip's caption-style keyframes (or undo may
        // have restored them) — keep the keyframe-dot drawer in sync if it's showing.
        refreshCaptionKeyframeDrawer();
    }

    /** Toggle the caption customization drawer. */
    private void toggleCaptions() {
        showCaptionDrawer(!captionDrawerOpen);
    }

    /**
     * Bind and show the caption overlay for {@code clip}, applying its persisted
     * style and position. Shared by the toolbar toggle and project-load restore.
     */
    private void showCaptionsForClip(@NonNull Clip clip) {
        if (captionOverlay == null || !clip.hasTranscript()) return;
        captionsActive = true;
        bindCaptionData(clip);
        captionOverlay.setVisibility(View.VISIBLE);
        captionStyleBar.setVisibility(View.VISIBLE);
    }

    /**
     * Bind the caption overlay's transcript / style / position to {@code clip} WITHOUT touching
     * visibility or the editing toolbar. Used by the playback loop to switch captions to the clip under
     * the playhead at each cut (captions are clip-specific, like the visualizer).
     */
    private void bindCaptionData(@NonNull Clip clip) {
        if (captionOverlay == null) return;
        captionClipId = clip.getId();
        highlightActiveCaptionChip(clip.getCaptionStyleId());
        captionOverlay.setData(clip.getTranscript(),
                com.fadcam.ui.faditor.transcript.CaptionStyle.byId(clip.getCaptionStyleId()),
                new com.fadcam.ui.faditor.transcript.CaptionOverlayView.Callback() {
                    @NonNull
                    @Override
                    public android.graphics.RectF getVideoContentRect() {
                        // Canvas-relative: caption size/position must match the export
                        // (font = fraction × canvas height), not the per-clip video rect.
                        return computeCanvasRect();
                    }

                    @Override
                    public void onMoved() {
                        final Clip cc = findClipById(captionClipId);
                        if (cc != null) {
                            final float beforeX = cc.getCaptionCenterX();
                            final float beforeY = cc.getCaptionCenterY();
                            final float afterX = captionOverlay.getCenterX();
                            final float afterY = captionOverlay.getCenterY();
                            cc.setCaptionCenter(afterX, afterY);
                            if (beforeX != afterX || beforeY != afterY) {
                                undoManager.recordAction(new EditActions.LambdaAction("Caption position",
                                        () -> cc.setCaptionCenter(afterX, afterY),
                                        () -> cc.setCaptionCenter(beforeX, beforeY)));
                            }
                            scheduleAutoSave();
                        }
                    }

                    @Override
                    public void onTapped() {
                        activeCaptionIsAudio = false;
                        if (captionStyleBar != null) {
                            captionStyleBar.setVisibility(View.VISIBLE);
                        }
                    }

                    @Override
                    public void onLongPressed() {
                        // Long-press a caption → hide captions for THIS clip (tap=props,
                        // long-hold=delete, the interaction the user likes on the visualizer).
                        final Clip cc = findClipById(captionClipId);
                        if (cc != null) {
                            cc.setCaptionsEnabled(false);
                            captionOverlay.setVisibility(View.GONE);
                            undoManager.recordAction(new EditActions.LambdaAction("Hide captions",
                                    () -> cc.setCaptionsEnabled(false),
                                    () -> cc.setCaptionsEnabled(true)));
                            scheduleAutoSave();
                            Toast.makeText(FaditorEditorActivity.this,
                                    "Captions hidden for this clip", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        captionOverlay.setCenter(clip.getCaptionCenterX(), clip.getCaptionCenterY());
    }

    private void bindAudioCaptionData(@NonNull AudioClip clip) {
        if (audioCaptionOverlay == null) return;
        audioCaptionClipId = clip.getId();
        highlightActiveCaptionChip(clip.getCaptionStyleId());
        audioCaptionOverlay.setData(clip.getTranscript(),
                com.fadcam.ui.faditor.transcript.CaptionStyle.byId(clip.getCaptionStyleId()),
                new com.fadcam.ui.faditor.transcript.CaptionOverlayView.Callback() {
                    @NonNull @Override
                    public android.graphics.RectF getVideoContentRect() {
                        // Canvas-relative so audio captions match the export size/position.
                        return computeCanvasRect();
                    }
                    @Override
                    public void onMoved() {
                        final AudioClip ac = findAudioClipById(audioCaptionClipId);
                        if (ac != null) {
                            final float beforeX = ac.getCaptionCenterX();
                            final float beforeY = ac.getCaptionCenterY();
                            final float afterX = audioCaptionOverlay.getCenterX();
                            final float afterY = audioCaptionOverlay.getCenterY();
                            ac.setCaptionCenter(afterX, afterY);
                            if (beforeX != afterX || beforeY != afterY) {
                                undoManager.recordAction(new EditActions.LambdaAction("Caption position",
                                        () -> ac.setCaptionCenter(afterX, afterY),
                                        () -> ac.setCaptionCenter(beforeX, beforeY)));
                            }
                            scheduleAutoSave();
                        }
                    }
                    @Override
                    public void onTapped() {
                        activeCaptionIsAudio = true;
                        if (captionStyleBar != null) {
                            captionStyleBar.setVisibility(View.VISIBLE);
                        }
                    }
                    @Override
                    public void onLongPressed() {
                        final AudioClip ac = findAudioClipById(audioCaptionClipId);
                        if (ac != null) {
                            ac.setCaptionsEnabled(false);
                            audioCaptionOverlay.setVisibility(View.GONE);
                            undoManager.recordAction(new EditActions.LambdaAction("Hide captions",
                                    () -> ac.setCaptionsEnabled(false),
                                    () -> ac.setCaptionsEnabled(true)));
                            scheduleAutoSave();
                            Toast.makeText(FaditorEditorActivity.this,
                                    "Captions hidden — tap a style chip to show again", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        audioCaptionOverlay.setCenter(clip.getCaptionCenterX(), clip.getCaptionCenterY());
    }

    /** After loading a saved project, re-show captions for the clip that had them. */
    private void restoreCaptionsAfterLoad() {
        if (captionOverlay == null) return;
        Timeline tl = project.getTimeline();
        // First try to restore a video clip with captions.
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip clip = tl.getClip(i);
            if (clip.isCaptionsEnabled() && clip.hasTranscript()) {
                currentTranscript = clip.getTranscript();
                transcriptClipId = clip.getId();
                activeCaptionIsAudio = false;
                showCaptionsForClip(clip);
                return;
            }
        }
        // Fallback to audio-clip captions.
        if (audioCaptionOverlay != null) {
            for (AudioClip ac : tl.getAudioClips()) {
                if (ac.isCaptionsEnabled() && ac.hasTranscript()) {
                    currentTranscript = ac.getTranscript();
                    transcriptClipId = ac.getId();
                    activeCaptionIsAudio = true;
                    audioCaptionClipId = ac.getId();
                    captionsActive = true;
                    bindAudioCaptionData(ac);
                    audioCaptionOverlay.setVisibility(View.VISIBLE);
                    return;
                }
            }
        }
    }

    private void showSoftKeyboard(@NonNull View v) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(v,
                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideSoftKeyboard(@NonNull View v) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void setupTransitionPanel() {
        transitionPanel = findViewById(R.id.transition_panel);
        if (transitionPanel == null) return;
        transitionPanel.setTranslationY(-getResources().getDisplayMetrics().heightPixels);
        transitionTypeViews.clear();
        // The panel must NOT insert on drop — that was sending every drop to the playhead seam (time 0 /
        // previous scene). Only the TIMELINE inserts now (with nearest-seam snapping + a live preview).
        // Dropping a card back on the panel just cancels. (Tapping a card still quick-inserts at playhead.)
        transitionPanel.setOnDragListener((v, event) ->
                event.getAction() == android.view.DragEvent.ACTION_DRAG_STARTED);
        setupTransitionDragDrop();
        findViewById(R.id.transition_panel_close).setOnClickListener(v -> {
            hideTransitionInspector();
            showTransitionPanel(false);
        });
        // Swipe UP on the header to dismiss the drawer (the user prefers a gesture over the X button).
        View transitionHeader = findViewById(R.id.transition_panel_header);
        if (transitionHeader != null) {
            final android.view.GestureDetector swipe = new android.view.GestureDetector(this,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                               float vx, float vy) {
                            if (vy < -700 && Math.abs(vy) > Math.abs(vx)) {
                                hideTransitionInspector();
                                showTransitionPanel(false);
                                return true;
                            }
                            return false;
                        }
                    });
            transitionHeader.setOnTouchListener((v, ev) -> {
                swipe.onTouchEvent(ev);
                return true;
            });
        }
        setupTransitionDrag(findViewById(R.id.transition_cross_dissolve), Transition.Type.CROSS_DISSOLVE);
        setupTransitionDrag(findViewById(R.id.transition_fade_black), Transition.Type.FADE_OUT_TO_BLACK);
        setupTransitionDrag(findViewById(R.id.transition_fade_white), Transition.Type.FADE_OUT_TO_WHITE);
        setupTransitionDrag(findViewById(R.id.transition_wipe), Transition.Type.WIPE_LEFT);
        setupTransitionDrag(findViewById(R.id.transition_radial), Transition.Type.RADIAL);
        setupTransitionDrag(findViewById(R.id.transition_linear_mirror), Transition.Type.LINEAR_MIRROR_WIPE);
        setupTransitionDrag(findViewById(R.id.transition_push), Transition.Type.PUSH_LEFT);
        setupTransitionDrag(findViewById(R.id.transition_glitch), Transition.Type.GLITCH);
        setupTransitionDrag(findViewById(R.id.transition_tv_channel), Transition.Type.TV_CHANNEL);
        // Replace the single "GL Effect" card with ONE card per GL transition in the catalog, so every
        // shader is browsable inline alongside the basics (no nested sub-list).
        View glCard = findViewById(R.id.transition_gl_shader);
        if (glCard != null) glCard.setVisibility(View.GONE);
        populateGlTransitionCards();
    }

    /** Append a preview card for each catalog GL transition to the transitions card row. */
    private void populateGlTransitionCards() {
        android.widget.LinearLayout basicsRow = findViewById(R.id.transition_card_row);
        if (basicsRow == null) return;
        float d = getResources().getDisplayMetrics().density;

        // Put GL effects (26 catalog + any user .glsl) in a SECOND scrolling row below the basics, so the
        // basic transitions aren't buried in one endless horizontal scroll.
        android.view.ViewParent scroll = basicsRow.getParent();
        if (!(scroll instanceof View)) return;
        android.view.ViewParent panelParent = ((View) scroll).getParent();
        if (!(panelParent instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup panel = (android.view.ViewGroup) panelParent;
        int insertIdx = panel.indexOfChild((View) scroll) + 1;

        android.widget.LinearLayout glRow = new android.widget.LinearLayout(this);
        glRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        int hp = (int) (10 * d);
        glRow.setPadding(hp, 0, hp, hp);

        for (com.fadcam.ui.faditor.gltransitions.GLTransitionCatalog.Entry e
                : com.fadcam.ui.faditor.gltransitions.GLTransitionCatalog.entries()) {
            glRow.addView(buildGlTransitionCard(e.id, e.displayName, d));
        }
        // Auto-append any user-supplied .glsl GL transitions found in the pinned folder (invisible when none).
        if (project != null && project.getPinnedAssetDir() != null) {
            for (com.fadcam.ui.faditor.gltransitions.GlExternalTransitions.Item item
                    : com.fadcam.ui.faditor.gltransitions.GlExternalTransitions.scanAndRegister(
                            this, android.net.Uri.parse(project.getPinnedAssetDir()))) {
                glRow.addView(buildGlTransitionCard(item.id, item.displayName, d));
            }
        }

        android.widget.HorizontalScrollView glScroll = new android.widget.HorizontalScrollView(this);
        glScroll.setHorizontalScrollBarEnabled(false);
        glScroll.addView(glRow);

        // "Pull down for more rows" (studio-drawers §C, the last remaining TODO): the GL-effects
        // row starts COLLAPSED so the basic transitions aren't buried; a discoverable affordance
        // bar reveals it on tap or a downward fling (up-fling / re-tap collapses it).
        View affordance = buildTransitionMoreAffordance(d);
        panel.addView(affordance, insertIdx);
        glScroll.setVisibility(View.GONE);
        panel.addView(glScroll, insertIdx + 1);
        transitionGlRow = glScroll;
        transitionGlExpanded = false;
    }

    /** The tappable "⌄ More effects" bar that reveals/collapses the GL-transition row. */
    private View buildTransitionMoreAffordance(float d) {
        android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER);
        int vp = (int) (7 * d);
        bar.setPadding(0, vp, 0, vp);

        TextView label = new TextView(this);
        label.setTextColor(0xFFAAAAAA);
        label.setTextSize(12);
        label.setText(R.string.faditor_transitions_more_effects);
        bar.addView(label);
        transitionGlToggleLabel = label;

        final android.view.GestureDetector fling = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                           float vx, float vy) {
                        if (Math.abs(vy) > Math.abs(vx) && Math.abs(vy) > 500) {
                            setTransitionGlRowExpanded(vy > 0); // pull down = reveal, up = collapse
                            return true;
                        }
                        return false;
                    }
                });
        bar.setOnTouchListener((v, ev) -> {
            fling.onTouchEvent(ev);
            return false; // still let the tap-to-toggle click through
        });
        bar.setOnClickListener(v -> setTransitionGlRowExpanded(!transitionGlExpanded));
        return bar;
    }

    /** Reveal or collapse the GL-transition row and flip the affordance chevron/label. */
    private void setTransitionGlRowExpanded(boolean expanded) {
        if (transitionGlRow == null) return;
        transitionGlExpanded = expanded;
        transitionGlRow.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (transitionGlToggleLabel != null) {
            transitionGlToggleLabel.setText(expanded
                    ? R.string.faditor_transitions_fewer_effects
                    : R.string.faditor_transitions_more_effects);
        }
    }

    /** Build one GL-transition preview card (used for both catalog + user-supplied shaders). */
    private View buildGlTransitionCard(@NonNull final String glId, @NonNull String label, float d) {
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER);
        int pad = (int) (8 * d);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundColor(Color.parseColor("#332A2A2A"));
        android.widget.LinearLayout.LayoutParams clp =
                new android.widget.LinearLayout.LayoutParams((int) (96 * d), (int) (118 * d));
        clp.setMarginEnd((int) (8 * d));
        card.setLayoutParams(clp);

        com.fadcam.ui.faditor.player.TransitionPreviewCardView preview =
                new com.fadcam.ui.faditor.player.TransitionPreviewCardView(this);
        Transition demo = new Transition(Transition.Type.GL_SHADER, 1500, 0);
        demo.glTransitionId = glId;
        preview.setTransition(demo);
        card.addView(preview, new android.widget.LinearLayout.LayoutParams((int) (64 * d), (int) (64 * d)));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(0xFFFFFFFF);
        lbl.setTextSize(11);
        lbl.setMaxLines(1);
        lbl.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.LinearLayout.LayoutParams llp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = (int) (6 * d);
        card.addView(lbl, llp);

        card.setOnClickListener(v -> insertGlTransitionAtPlayhead(glId));
        card.setOnLongClickListener(v -> {
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
            v.startDragAndDrop(android.content.ClipData.newPlainText("glshader", glId),
                    shadow, "gl:" + glId, 0);
            return true;
        });
        return card;
    }

    private void setupTransitionDrag(View v, final Transition.Type type) {
        if (v == null) return;
        transitionTypeViews.put(type, v);
        // Swap the static preview swatch (first child) for an ANIMATED A→B demo of this transition.
        // The preview view is non-interactive, so the card's tap (add) and long-press (drag) still work.
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup card = (android.view.ViewGroup) v;
            if (card.getChildCount() > 0) {
                ViewGroup.LayoutParams lp = card.getChildAt(0).getLayoutParams();
                com.fadcam.ui.faditor.player.TransitionPreviewCardView preview =
                        new com.fadcam.ui.faditor.player.TransitionPreviewCardView(this);
                Transition demo = new Transition(type, 1500, 0);
                if (type == Transition.Type.GL_SHADER) demo.glTransitionId = "CrossZoom";
                preview.setTransition(demo);
                card.removeViewAt(0);
                card.addView(preview, 0, lp);
            }
        }
        v.setOnClickListener(view -> insertTransitionAtPlayhead(type));
        v.setOnLongClickListener(view -> {
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(view);
            view.startDragAndDrop(android.content.ClipData.newPlainText("transition", type.name()), shadow, type, 0);
            return true;
        });
    }

    private void setupTransitionDragDrop() {
        if (editorTimeline == null) return;
        editorTimeline.setOnDragListener((v, event) -> {
            Object local = event.getLocalState();
            boolean isTransition = (local instanceof Transition.Type)
                    || (local instanceof String && ((String) local).startsWith("gl:"));
            switch (event.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                    // Collapse the panel so the timeline (which the open panel pushes down/off-screen) is
                    // reachable for the drop. The drag shadow keeps following the finger regardless.
                    if (isTransition && transitionPanelOpen) showTransitionPanel(false);
                    return isTransition;
                case android.view.DragEvent.ACTION_DRAG_LOCATION:
                    // Live preview: highlight the seam the transition will snap to as you move.
                    if (isTransition) {
                        editorTimeline.setTransitionDragSeam(
                                editorTimeline.getNearestSeamAtX(event.getX()));
                    }
                    return true;
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    editorTimeline.clearTransitionDrag();
                    return true;
                case android.view.DragEvent.ACTION_DROP:
                    editorTimeline.clearTransitionDrag();
                    int seam = editorTimeline.getNearestSeamAtX(event.getX());
                    if (local instanceof Transition.Type) {
                        insertTransitionAtSeam((Transition.Type) local, null, seam);
                        return true;
                    }
                    if (local instanceof String && ((String) local).startsWith("gl:")) {
                        insertTransitionAtSeam(Transition.Type.GL_SHADER,
                                ((String) local).substring(3), seam);
                        return true;
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    /**
     * Insert a transition at a specific seam (left-clip index) — snapped from the drop point to the
     * NEAREST seam boundary, so a drop over a cut lands on that cut (buttressing both clips) instead of
     * the old clip-centre mapping that drifted to the previous seam. Scrolls to reveal where it landed.
     */
    private void insertTransitionAtSeam(@NonNull Transition.Type type, @Nullable String glId, int seam) {
        if (project == null || editorTimeline == null) return;
        Timeline timeline = project.getTimeline();
        if (timeline.getClipCount() < 2) {
            Toast.makeText(this, R.string.faditor_transition_need_two_clips, Toast.LENGTH_SHORT).show();
            return;
        }
        seam = Math.max(0, Math.min(seam, timeline.getClipCount() - 2));
        timeline.removeTransitionAtSeam(seam);
        Transition transition = new Transition(type, 600, seam);
        if (type == Transition.Type.GL_SHADER) {
            transition.glTransitionId = glId != null ? glId : "CrossZoom";
        }
        timeline.addTransition(transition);
        editorTimeline.setTransitions(timeline.getTransitions());
        editorTimeline.setSelectedTransitionIndex(timeline.getTransitions().size() - 1);
        updateTransitionSelection();
        editorTimeline.scrollToSeam(seam); // reveal where it landed so the user isn't hunting
        showTransitionInspector(timeline.getTransitions().size() - 1);
        saveProjectNow();
        Toast.makeText(this, R.string.faditor_transition_added, Toast.LENGTH_SHORT).show();
    }

    private void updateTransitionSelection() {
        if (project == null) return;
        Transition selected = null;
        if (editorTimeline != null) {
            int index = editorTimeline.getSelectedTransitionIndex();
            if (index >= 0 && index < project.getTimeline().getTransitions().size()) {
                selected = project.getTimeline().getTransitions().get(index);
            }
        }
        for (Map.Entry<Transition.Type, View> entry : transitionTypeViews.entrySet()) {
            View view = entry.getValue();
            if (view == null) continue;
            boolean selectedType = selected != null && selected.type == entry.getKey();
            view.setBackgroundColor(selectedType ? Color.parseColor("#2A4CAF50") : Color.parseColor("#332A2A2A"));
        }
    }

    private void showTransitionPanel(boolean show) {
        if (transitionPanel == null) return;
        if (show && visualizerDrawerOpen) showVisualizerDrawer(false); // mutually exclusive top drawers
        // Cover the top bar while choosing a transition (AI/pin/close/export aren't needed here) — this
        // also raises the drawer up. The top bar is restored when the drawer closes.
        View topBar = findViewById(R.id.editor_top_bar);
        if (topBar != null) topBar.setVisibility(show ? View.GONE : View.VISIBLE);
        transitionPanel.setVisibility(View.VISIBLE);
        transitionPanel.animate()
                .translationY(show ? 0f : -getResources().getDisplayMetrics().heightPixels)
                .setDuration(show ? 220 : 180)
                .setInterpolator(show
                        ? new android.view.animation.DecelerateInterpolator()
                        : new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    if (!show) transitionPanel.setVisibility(View.GONE);
                })
                .start();
        transitionPanelOpen = show;
        updateTransitionSelection();
    }

    private void insertTransitionAtPlayhead(@NonNull Transition.Type type) {
        insertTransitionAtPlayhead(type, type == Transition.Type.GL_SHADER ? "CrossZoom" : null);
    }

    /** Insert a GL transition with a specific catalog effect at the playhead seam. */
    private void insertGlTransitionAtPlayhead(@NonNull String glId) {
        insertTransitionAtPlayhead(Transition.Type.GL_SHADER, glId);
    }

    private void insertTransitionAtPlayhead(@NonNull Transition.Type type, @Nullable String glId) {
        if (project == null || editorTimeline == null) return;
        Timeline timeline = project.getTimeline();
        if (timeline.getClipCount() < 2) {
            Toast.makeText(this, R.string.faditor_transition_need_two_clips, Toast.LENGTH_SHORT).show();
            return;
        }
        int seam = resolveTransitionSeamAtPlayhead();
        if (seam < 0 || seam >= timeline.getClipCount()) {
            Toast.makeText(this, R.string.faditor_transition_need_two_clips, Toast.LENGTH_SHORT).show();
            return;
        }
        timeline.removeTransitionAtSeam(seam);
        Transition transition = new Transition(type, 600, seam);
        if (type == Transition.Type.GL_SHADER) {
            transition.glTransitionId = glId != null ? glId : "CrossZoom";
        }
        timeline.addTransition(transition);
        editorTimeline.setTransitions(timeline.getTransitions());
        editorTimeline.setSelectedTransitionIndex(timeline.getTransitions().size() - 1);
        updateTransitionSelection();
        editorTimeline.invalidate();
        showTransitionInspector(timeline.getTransitions().size() - 1);
        showTransitionPanel(false);
        saveProjectNow();
        Toast.makeText(this, R.string.faditor_transition_added, Toast.LENGTH_SHORT).show();
    }

    /**
     * Returns the LEFT-clip index of the seam nearest the playhead — the {@code Transition.clipIndex}
     * convention (seam between clip[i] and clip[i+1], valid i in [0, clipCount-2]). Splits the clip at
     * the playhead to create a new seam when not near an existing boundary. (Previously returned the
     * RIGHT-clip / insert index, off by one, which orphaned every transition.)
     */
    private int resolveTransitionSeamAtPlayhead() {
        Timeline timeline = project.getTimeline();
        int clipCount = timeline.getClipCount();
        if (clipCount < 2) return -1;
        int maxSeam = clipCount - 2;
        long playhead = editorTimeline.getPlayheadPositionMs();

        // Existing boundaries: seam i sits at the start of clip[i+1].
        int bestSeam = 0;
        long best = Long.MAX_VALUE;
        for (int i = 0; i <= maxSeam; i++) {
            long boundaryTime = editorTimeline.getSegmentStartTimeMs(i + 1);
            long dist = Math.abs(playhead - boundaryTime);
            if (dist < best) {
                best = dist;
                bestSeam = i;
            }
        }
        if (best <= 350) return bestSeam;

        int seg = editorTimeline.getSegmentAtPlayhead();
        if (seg < 0 || seg >= clipCount) return bestSeam;
        Clip clip = timeline.getClip(seg);
        long segStart = editorTimeline.getSegmentStartTimeMs(seg);
        long localMs = Math.max(0, playhead - segStart);
        long effectiveMs = clip.getEffectiveDurationMs();
        if (localMs <= 350) {
            return Math.max(0, Math.min(seg - 1, maxSeam)); // boundary before clip seg = seam seg-1
        }
        if (localMs >= effectiveMs - 350) {
            return Math.max(0, Math.min(seg, maxSeam));      // boundary after clip seg = seam seg
        }
        long sourceSplitMs = clip.getInPointMs() + (long)(localMs * clip.getSpeedMultiplier());
        if (sourceSplitMs <= clip.getInPointMs() + 250 || sourceSplitMs >= clip.getOutPointMs() - 250) {
            return Math.max(0, Math.min(seg, maxSeam));
        }
        int splitIndex = timeline.splitAt(seg, sourceSplitMs);
        if (splitIndex < 0) return Math.max(0, Math.min(bestSeam, maxSeam));
        timeline.shiftTransitionsAfterSplit(splitIndex);
        selectedClipIndex = splitIndex + 1;
        editorTimeline.setTimeline(timeline, selectedClipIndex);
        editorTimeline.setTrimFromClip(timeline.getClip(selectedClipIndex));
        editorTimeline.setTransitions(timeline.getTransitions());
        refreshTotalTimeDisplay();
        saveProjectNow();
        // New clips at splitIndex and splitIndex+1; the seam between them = left index splitIndex.
        return splitIndex;
    }

    private void updateTransitionInspector(int index) {
        View inspector = findViewById(R.id.transition_inspector);
        if (inspector == null || inspector.getVisibility() != View.VISIBLE) return;
        TextView durationView = findViewById(R.id.transition_inspector_duration);
        if (durationView != null && project != null
                && index >= 0 && index < project.getTimeline().getTransitions().size()) {
            durationView.setText(TimeFormatter.formatAuto(project.getTimeline().getTransitions().get(index).durationMs));
        }
    }

    private void showTransitionInspector(int index) {
        View inspector = findViewById(R.id.transition_inspector);
        if (inspector == null) return;
        TextView typeView = findViewById(R.id.transition_inspector_type);
        TextView durationView = findViewById(R.id.transition_inspector_duration);
        View deleteView = findViewById(R.id.btn_transition_delete);
        if (index < 0 || project == null || index >= project.getTimeline().getTransitions().size()) {
            inspector.setVisibility(View.GONE);
            return;
        }
        Transition t = project.getTimeline().getTransitions().get(index);
        if (typeView != null) {
            // Tap the type to EXCHANGE it (any transition → any other). GL transitions show their
            // effect name; picking "GL Effect…" in the type picker opens the 26-entry GL chooser.
            typeView.setText((t.isGlShader()
                    ? com.fadcam.ui.faditor.gltransitions.GLTransitionCatalog.displayName(t.glTransitionId)
                    : t.type.name().replace('_', ' ')) + " ▾");
            typeView.setOnClickListener(v -> showTransitionTypePicker(t, index));
        }
        if (durationView != null) {
            durationView.setText(TimeFormatter.formatAuto(t.durationMs) + " ▾");
            durationView.setOnClickListener(v -> showTransitionDurationPicker(t, index));
        }
        if (deleteView != null) {
            deleteView.setOnClickListener(v -> deleteTransition(index));
        }
        inspector.setVisibility(View.VISIBLE);
    }

    /** Quick-pick a transition's duration from common presets. */
    private void showTransitionDurationPicker(@NonNull Transition transition, int index) {
        final long[] presets = {250L, 500L, 750L, 1000L, 1500L, 2000L};
        CharSequence[] labels = new CharSequence[presets.length];
        int checked = -1;
        for (int i = 0; i < presets.length; i++) {
            labels[i] = TimeFormatter.formatAuto(presets[i]);
            if (presets[i] == transition.durationMs) checked = i;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.faditor_transition_duration_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    transition.durationMs = presets[which];
                    if (editorTimeline != null) {
                        editorTimeline.setTransitions(project.getTimeline().getTransitions());
                    }
                    saveProjectNow();
                    showTransitionInspector(index);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Exchange a transition's type in place (one curated option per family + GL effects). */
    private void showTransitionTypePicker(@NonNull Transition transition, int index) {
        final Transition.Type[] types = {
                Transition.Type.CROSS_DISSOLVE, Transition.Type.FADE_OUT_TO_BLACK,
                Transition.Type.FADE_OUT_TO_WHITE, Transition.Type.WIPE_LEFT,
                Transition.Type.PUSH_LEFT, Transition.Type.RADIAL,
                Transition.Type.LINEAR_MIRROR_WIPE, Transition.Type.GLITCH,
                Transition.Type.TV_CHANNEL, Transition.Type.GL_SHADER};
        final CharSequence[] labels = {"Cross Dissolve", "Fade to Black", "Fade to White",
                "Wipe", "Push", "Radial", "Mirror Wipe", "Glitch", "TV Channel", "GL Effect…"};
        int checked = -1;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == transition.type) checked = i;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.faditor_transition_type_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Transition.Type picked = types[which];
                    transition.type = picked;
                    if (editorTimeline != null) {
                        editorTimeline.setTransitions(project.getTimeline().getTransitions());
                    }
                    saveProjectNow();
                    dialog.dismiss();
                    if (glTransitionPreviewView != null) glTransitionPreviewView.clear();
                    if (picked == Transition.Type.GL_SHADER) {
                        if (transition.glTransitionId == null) transition.glTransitionId = "CrossZoom";
                        showGlTransitionPicker(transition, index);
                    } else if (picked == Transition.Type.WIPE_LEFT || picked == Transition.Type.PUSH_LEFT) {
                        showTransitionDirectionPicker(transition, index, picked == Transition.Type.PUSH_LEFT);
                    } else {
                        showTransitionInspector(index);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Pick the direction for a wipe/push transition (the "+"-badge option). */
    private void showTransitionDirectionPicker(@NonNull Transition transition, int index, boolean push) {
        final Transition.Type[] types = push
                ? new Transition.Type[]{Transition.Type.PUSH_LEFT, Transition.Type.PUSH_RIGHT,
                        Transition.Type.PUSH_UP, Transition.Type.PUSH_DOWN}
                : new Transition.Type[]{Transition.Type.WIPE_LEFT, Transition.Type.WIPE_RIGHT,
                        Transition.Type.WIPE_UP, Transition.Type.WIPE_DOWN};
        final CharSequence[] labels = {"Left", "Right", "Up", "Down"};
        int checked = -1;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == transition.type) checked = i;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.faditor_transition_direction_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    transition.type = types[which];
                    if (editorTimeline != null) {
                        editorTimeline.setTransitions(project.getTimeline().getTransitions());
                    }
                    saveProjectNow();
                    dialog.dismiss();
                    showTransitionInspector(index);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Choose which of the 26 GL transitions a GL_SHADER transition uses (from the catalog). */
    private void showGlTransitionPicker(@NonNull Transition transition, int index) {
        java.util.List<com.fadcam.ui.faditor.gltransitions.GLTransitionCatalog.Entry> entries =
                com.fadcam.ui.faditor.gltransitions.GLTransitionCatalog.entries();
        CharSequence[] labels = new CharSequence[entries.size()];
        int checked = -1;
        for (int i = 0; i < entries.size(); i++) {
            com.fadcam.ui.faditor.gltransitions.GLTransitionCatalog.Entry e = entries.get(i);
            labels[i] = e.displayName + "  ·  " + e.category;
            if (e.id.equals(transition.glTransitionId)) checked = i;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.faditor_gl_transition_pick_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    transition.glTransitionId = entries.get(which).id;
                    dialog.dismiss();
                    if (editorTimeline != null) {
                        editorTimeline.setTransitions(project.getTimeline().getTransitions());
                    }
                    saveProjectNow();
                    showTransitionInspector(index);
                    if (glTransitionPreviewView != null) glTransitionPreviewView.clear();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void hideTransitionInspector() {
        View inspector = findViewById(R.id.transition_inspector);
        if (inspector != null) inspector.setVisibility(View.GONE);
    }

    private void deleteTransition(int index) {
        if (project == null || index < 0 || index >= project.getTimeline().getTransitions().size()) return;
        project.getTimeline().removeTransition(index);
        if (editorTimeline != null) {
            editorTimeline.setTransitions(project.getTimeline().getTransitions());
            editorTimeline.setSelectedTransitionIndex(-1);
        }
        updateTransitionSelection();
        hideTransitionInspector();
        saveProjectNow();
        Toast.makeText(this, R.string.faditor_transition_removed, Toast.LENGTH_SHORT).show();
    }

    /**
     * Sprites tool (PLAN_SPRITE_ANIMATION S2): list the project's sprite sheets +
     * "+ New sprite sheet", launching the full-screen setup editor. Cross-activity
     * write-back rides the ChatAssistant pattern: our onPause autosave runs when the
     * editor activity opens; it saves + signalModified; we reload on resume.
     */
    private void openSpriteSheetManager() {
        java.util.List<com.fadcam.ui.faditor.sprite.SpriteSheet> sheets = project.getSpriteSheets();
        String[] items = new String[sheets.size() + 2];
        for (int i = 0; i < sheets.size(); i++) items[i] = sheets.get(i).getName();
        items[sheets.size()] = getString(R.string.sprite_sheet_picker_new);
        items[sheets.size() + 1] = getString(R.string.sprite_sheet_picker_avatars);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sprite_sheet_picker_title)
                .setItems(items, (d, which) -> {
                    if (which == sheets.size() + 1) {
                        openAvatarStudioManager();
                        return;
                    }
                    if (which < sheets.size()) {
                        showSpriteSheetActions(sheets.get(which));
                        return;
                    }
                    launchSpriteSheetEditor(null); // + New sprite sheet
                })
                .show();
    }

    /** Existing sheet tapped: edit, place an instance, or relink dead art (S7). */
    private void showSpriteSheetActions(@NonNull com.fadcam.ui.faditor.sprite.SpriteSheet sheet) {
        String[] actions = {
                getString(R.string.sprite_sheet_action_edit),
                getString(R.string.sprite_sheet_action_place),
                getString(R.string.sprite_sheet_action_relink)};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(sheet.getName())
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        launchSpriteSheetEditor(sheet.getId());
                    } else if (which == 1) {
                        placeSpriteOnVideo(sheet);
                    } else {
                        android.content.Intent it = new android.content.Intent(this,
                                com.fadcam.ui.faditor.sprite.SpriteSheetEditorActivity.class);
                        it.putExtra(com.fadcam.ui.faditor.sprite.SpriteSheetEditorActivity
                                .EXTRA_PROJECT_ID, project.getId());
                        it.putExtra(com.fadcam.ui.faditor.sprite.SpriteSheetEditorActivity
                                .EXTRA_SHEET_ID, sheet.getId());
                        it.putExtra(com.fadcam.ui.faditor.sprite.SpriteSheetEditorActivity
                                .EXTRA_RELINK, true);
                        startActivity(it);
                    }
                })
                .show();
    }

    private void launchSpriteSheetEditor(@Nullable String sheetId) {
        android.content.Intent it = new android.content.Intent(this,
                com.fadcam.ui.faditor.sprite.SpriteSheetEditorActivity.class);
        it.putExtra(com.fadcam.ui.faditor.sprite.SpriteSheetEditorActivity
                .EXTRA_PROJECT_ID, project.getId());
        if (sheetId != null) {
            it.putExtra(com.fadcam.ui.faditor.sprite.SpriteSheetEditorActivity
                    .EXTRA_SHEET_ID, sheetId);
        }
        startActivity(it);
    }

    /**
     * S4 placement: drop a sprite instance at the playhead, first cell showing
     * from time 0 of the item (a single-cell "hold" — S5's lane + the S3 palette
     * add real frame animation on top). One undo step; persists via autosave.
     */
    private void placeSpriteOnVideo(@NonNull com.fadcam.ui.faditor.sprite.SpriteSheet sheet) {
        if (project == null) return;
        final com.fadcam.ui.faditor.sprite.SpriteOverlayItem item =
                com.fadcam.ui.faditor.sprite.SpriteOverlayItem.create(sheet.getId());
        // T8: every placed sprite gets its OWN lane (unique layerId) so two sprites never
        // collapse onto one shared "sprite" track and overlap (FEEDBACK_20260706 #2).
        item.setLayerId(com.fadcam.ui.faditor.model.Timeline.spriteLayerIdFor(item));
        item.getFrameTrack().put(
                com.fadcam.ui.faditor.sprite.FrameTrack.Key.ofCell(0, firstEnabledCell(sheet)));
        item.setTimeRange(Math.max(0, lastPlayheadAbsoluteMs), Long.MAX_VALUE);
        project.getTimeline().addSpriteOverlay(item);
        syncTimelineOverlays();
        undoManager.recordAction(new EditActions.LambdaAction("Place sprite",
                () -> { project.getTimeline().addSpriteOverlay(item); syncTimelineOverlays(); },
                () -> { project.getTimeline().removeSpriteOverlay(item); syncTimelineOverlays(); }));
        scheduleAutoSave();
        Toast.makeText(this, R.string.sprite_placed, Toast.LENGTH_SHORT).show();
    }

    /** Lowest-index ENABLED cell (cells with no meta default to enabled). */
    private int firstEnabledCell(@NonNull com.fadcam.ui.faditor.sprite.SpriteSheet sheet) {
        for (int i = 0; i < sheet.cellCount(); i++) {
            com.fadcam.ui.faditor.sprite.SpriteSheet.Cell meta = sheet.cellAt(i);
            if (meta == null || meta.enabled) return i;
        }
        return 0;
    }

    /**
     * Avatar Studio rig list (PLAN_AVATAR_STUDIO A1-UI): the project's rigs +
     * "+ New avatar", launching the matrix editor. Same write-back pattern as
     * the sprite setup editor. Editor-side surface stays THIN per the
     * architecture contract — authoring lives entirely in Avatar Studio.
     */
    private void openAvatarStudioManager() {
        java.util.List<com.fadcam.ui.faditor.avatar.AvatarRig> rigs = project.getAvatarRigs();
        String[] items = new String[rigs.size() + 1];
        for (int i = 0; i < rigs.size(); i++) items[i] = rigs.get(i).getName();
        items[rigs.size()] = getString(R.string.avatar_studio_new);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.avatar_studio_title)
                .setItems(items, (d, which) -> {
                    android.content.Intent it = new android.content.Intent(this,
                            com.fadcam.ui.faditor.avatar.AvatarStudioActivity.class);
                    it.putExtra(com.fadcam.ui.faditor.avatar.AvatarStudioActivity
                            .EXTRA_PROJECT_ID, project.getId());
                    if (which < rigs.size()) {
                        it.putExtra(com.fadcam.ui.faditor.avatar.AvatarStudioActivity
                                .EXTRA_RIG_ID, rigs.get(which).getId());
                    }
                    startActivity(it);
                })
                .show();
    }

    /** Opens the panel; reuses an existing transcript, picks a model, or transcribes. */
    private void openTranscriptPanel() {
        if (inCropMode) exitCropMode(false);

        // Determine whether we're editing an audio clip's transcript or a video clip's.
        resolveTranscriptTarget();

        if (!transcriptIsForAudio) {
            Clip clip = getSelectedClip();
            if (clip == null) return;
            if (clip.isImageClip()) {
                Toast.makeText(this, R.string.faditor_transcript_image, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        boolean alreadyOpen = transcriptPanel != null
                && transcriptPanel.getVisibility() == View.VISIBLE;
        showTranscriptPanel(true);
        if (!alreadyOpen || currentTranscript == null) {
            loadTranscriptPanelContent();
        } else {
            // Panel is already open and may have a stale transcript/selection;
            // refresh to match the newly selected clip.
            loadTranscriptPanelContent();
        }
    }

    /** Decides whether the transcript panel targets the selected audio clip or video clip. */
    private void resolveTranscriptTarget() {
        transcriptIsForAudio = false;
        transcriptAudioIndex = -1;
        if (editorTimeline.getSelectedAudioIndex() >= 0
                && editorTimeline.getSelectedAudioIndex() < project.getTimeline().getAudioClips().size()) {
            AudioClip audioClip = project.getTimeline().getAudioClips()
                    .get(editorTimeline.getSelectedAudioIndex());
            if (audioClip != null) {
                transcriptIsForAudio = true;
                transcriptAudioIndex = editorTimeline.getSelectedAudioIndex();
            }
        }
    }

    /** Loads the transcript (or model picker) for the current target without animating the panel. */
    private void loadTranscriptPanelContent() {
        resolveTranscriptTarget();

        if (transcriptIsForAudio && transcriptAudioIndex >= 0
                && transcriptAudioIndex < project.getTimeline().getAudioClips().size()) {
            AudioClip audioClip = project.getTimeline().getAudioClips().get(transcriptAudioIndex);
            if (audioClip == null) return;
            if (audioClip.hasTranscript()) {
                currentTranscript = audioClip.getTranscript();
                transcriptClipId = audioClip.getId();
            }
            refreshTranscriptVersionBar();
            if (currentTranscript != null && audioClip.getId().equals(transcriptClipId)) {
                transcriptModelChoice.setVisibility(View.GONE);
                transcriptProgress.setVisibility(View.GONE);
                transcriptView.setVisibility(View.VISIBLE);
                transcriptView.setTranscript(currentTranscript);
                return;
            }
        } else {
            Clip clip = getSelectedClip();
            if (clip == null) return;
            if (clip.hasTranscript()) {
                currentTranscript = clip.getTranscript();
                transcriptClipId = clip.getId();
            }
            refreshTranscriptVersionBar();
            if (currentTranscript != null && clip.getId().equals(transcriptClipId)) {
                transcriptModelChoice.setVisibility(View.GONE);
                transcriptProgress.setVisibility(View.GONE);
                transcriptView.setVisibility(View.VISIBLE);
                transcriptView.setTranscript(currentTranscript);
                return;
            }
        }

        // No transcript yet — show model choice
        transcriptProgress.setVisibility(View.GONE);
        transcriptView.setVisibility(View.GONE);
        transcriptModelChoice.setVisibility(View.VISIBLE);
        updateModelChoiceReadyLabels();
    }

    /** Tag already-downloaded models in the picker so the choice is informed. */
    private void updateModelChoiceReadyLabels() {
        if (transcriptionEngine == null || transcriptModelChoice == null) return;
        markModelReady(R.id.transcript_model_fast,
                com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType.FAST);
        markModelReady(R.id.transcript_model_accurate,
                com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType.ACCURATE);
        markModelReady(R.id.transcript_model_whisper,
                com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType.WHISPER_BASE_EN);
    }

    private void markModelReady(int rowId,
            @NonNull com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType type) {
        View row = findViewById(rowId);
        if (row == null) return;
        boolean ready = transcriptionEngine.isModelReady(type);
        // A downloaded model gets a subtle highlight so the user knows it's instant.
        row.setAlpha(ready ? 1f : 0.85f);
    }

    /** Download (if needed) the chosen model and transcribe the selected clip. */
    private void startTranscription(
            @NonNull com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType type) {
        startTranscription(type, null);
    }

    /**
     * Same as {@link #startTranscription(com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType)},
     * but invokes {@code onDone} once this run finishes (success or error) —
     * used by {@link #runNextQueuedTranscription()} to chain multiple
     * engines sequentially against the "Transcribe this video?" prompt
     * (Feature A) without running them concurrently.
     */
    private void startTranscription(
            @NonNull com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType type,
            @Nullable Runnable onDone) {
        boolean isAudio = transcriptIsForAudio && transcriptAudioIndex >= 0
                && transcriptAudioIndex < project.getTimeline().getAudioClips().size();
        Clip clip = null;
        AudioClip audioClip = null;
        if (isAudio) {
            audioClip = project.getTimeline().getAudioClips().get(transcriptAudioIndex);
        } else {
            clip = getSelectedClip();
        }
        if (clip == null && audioClip == null) return;

        final Clip fClip = clip;
        final AudioClip fAudioClip = audioClip;

        transcriptModelChoice.setVisibility(View.GONE);
        transcriptView.setVisibility(View.GONE);
        transcriptView.setTranscript(null);
        transcriptProgress.setVisibility(View.VISIBLE);
        transcriptProgressText.setText(R.string.faditor_transcript_working);
        addActiveTranscription(type);

        final String clipId = isAudio ? audioClip.getId() : clip.getId();
        final Uri sourceUri = isAudio ? audioClip.getSourceUri() : clip.getSourceUri();
        final long inMs = isAudio ? audioClip.getInPointMs() : clip.getInPointMs();
        final long outMs = isAudio ? audioClip.getOutPointMs() : clip.getOutPointMs();
        // Create a new transcript version for this run (the user can keep a fast
        // Vosk pass for editing AND a Whisper pass for captions, side by side).
        String engine = type.engine
                == com.fadcam.ui.faditor.transcript.TranscriptionEngine.Engine.WHISPER
                ? "whisper" : "vosk";
        com.fadcam.ui.faditor.transcript.NamedTranscript version =
                new com.fadcam.ui.faditor.transcript.NamedTranscript(
                        type.label, engine,
                        new com.fadcam.ui.faditor.transcript.Transcript());
        if (isAudio) {
            audioClip.addTranscript(version);
        } else {
            clip.addTranscript(version);
        }
        final String versionId = version.id;
        currentTranscript = version.transcript;
        transcriptClipId = clipId;
        refreshTranscriptVersionBar();

        transcriptionEngine.transcribe(sourceUri, inMs, outMs, type,
                new com.fadcam.ui.faditor.transcript.TranscriptionEngine.Callback() {
                    @Override
                    public void onProgress(@NonNull String status, float fraction) {
                        transcriptProgressText.setText(status);
                        updateTranscriptionProgress(type, status, fraction);
                        if (transcriptProgressBar != null) {
                            if (fraction >= 0f) {
                                transcriptProgressBar.setIndeterminate(false);
                                transcriptProgressBar.setProgress(
                                        Math.max(0, Math.min(100, (int) (fraction * 100))));
                            } else {
                                transcriptProgressBar.setIndeterminate(true);
                            }
                        }
                    }

                    @Override
                    public void onPartial(@NonNull com.fadcam.ui.faditor.transcript.Transcript t) {
                        if (updateTranscriptVersion(clipId, versionId, t)) {
                            currentTranscript = t;
                            transcriptClipId = clipId;
                            if (transcriptView != null) {
                                transcriptView.setVisibility(View.VISIBLE);
                                transcriptView.setTranscript(t);
                            }
                            if (isAudio && fAudioClip != null) {
                                editorTimeline.setAudioClipTranscript(transcriptAudioIndex, t);
                            }
                            scheduleAutoSave();
                        }
                    }

                    @Override
                    public void onResult(@NonNull com.fadcam.ui.faditor.transcript.Transcript t) {
                        transcriptProgress.setVisibility(View.GONE);
                        finishTranscription(type);
                        if (t.isEmpty()) {
                            Toast.makeText(FaditorEditorActivity.this,
                                    R.string.faditor_transcript_empty, Toast.LENGTH_SHORT).show();
                        }
                        if (updateTranscriptVersion(clipId, versionId, t)) {
                            // Re-running the same model REPLACES its previous run
                            // instead of stacking another copy: drop older versions
                            // of the same engine+label that carry no user edits.
                            // (Edited/active versions are never removed — see
                            // TranscriptDedup's rule.)
                            if (isAudio && fAudioClip != null) {
                                com.fadcam.ui.faditor.transcript.TranscriptDedup
                                        .dedupAudioClip(fAudioClip, true);
                            } else if (fClip != null) {
                                com.fadcam.ui.faditor.transcript.TranscriptDedup
                                        .dedupClip(fClip, true);
                            }
                            currentTranscript = t;
                            transcriptClipId = clipId;
                            transcriptView.setTranscript(t);
                            // Sync to timeline
                            if (isAudio && fAudioClip != null) {
                                editorTimeline.setAudioClipTranscript(transcriptAudioIndex, t);
                            } else {
                                syncTimelineTranscript();
                            }
                            refreshTranscriptVersionBar();
                            saveProjectNow();
                        }
                        if (onDone != null) onDone.run();
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        transcriptProgress.setVisibility(View.GONE);
                        finishTranscription(type);
                        if (isAudio && fAudioClip != null) {
                            int vi = indexOfVersion(fAudioClip, versionId);
                            if (vi >= 0 && fAudioClip.getTranscripts().get(vi).transcript.isEmpty()) {
                                fAudioClip.removeTranscript(vi);
                            }
                        } else if (fClip != null) {
                            int vi = indexOfVersion(fClip, versionId);
                            if (vi >= 0 && fClip.getTranscripts().get(vi).transcript.isEmpty()) {
                                fClip.removeTranscript(vi);
                            }
                        }
                        if (currentTranscript == null || currentTranscript.isEmpty()) {
                            transcriptModelChoice.setVisibility(View.VISIBLE);
                        }
                        refreshTranscriptVersionBar();
                        Toast.makeText(FaditorEditorActivity.this,
                                getString(R.string.faditor_transcript_error, message),
                                Toast.LENGTH_LONG).show();
                        if (onDone != null) onDone.run();
                    }
                });
    }

    private void addActiveTranscription(@NonNull com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType type) {
        Integer count = activeTranscriptionModels.get(type);
        activeTranscriptionModels.put(type, count == null ? 1 : count + 1);
        updateTranscriptionStatusUI();
    }

    private void finishTranscription(@NonNull com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType type) {
        Integer count = activeTranscriptionModels.get(type);
        if (count == null || count <= 1) {
            activeTranscriptionModels.remove(type);
        } else {
            activeTranscriptionModels.put(type, count - 1);
        }
        updateTranscriptionStatusUI();
    }

    private void updateTranscriptionProgress(@NonNull com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType type,
                                             @NonNull String status,
                                             float fraction) {
        runOnUiThread(() -> {
            updateTranscriptionStatusUI(status, fraction);
            if (transcriptProgressBar != null) {
                if (fraction >= 0f) {
                    transcriptProgressBar.setIndeterminate(false);
                    transcriptProgressBar.setProgress(Math.max(0, Math.min(100, (int) (fraction * 100))));
                } else {
                    transcriptProgressBar.setIndeterminate(true);
                }
            }
        });
    }

    private void updateTranscriptionStatusUI() {
        updateTranscriptionStatusUI(null, -1f);
    }

    private void updateTranscriptionStatusUI(@Nullable String status, float fraction) {
        if (activeTranscriptionModels.isEmpty()) {
            if (transcriptStatusBadge != null) {
                transcriptStatusBadge.setVisibility(View.GONE);
                transcriptStatusBadge.setAlpha(1f);
            }
            if (transcriptGlobalProgressBar != null) transcriptGlobalProgressBar.setVisibility(View.GONE);
            if (toolTranscript != null) {
                toolTranscript.setAlpha(1f);
                toolTranscript.setForeground(null);
            }
            TextView icon = findViewById(R.id.tool_transcript_icon);
            TextView label = findViewById(R.id.tool_transcript_label);
            if (icon != null) icon.setTextColor(0xFF888888);
            if (label != null) label.setTextColor(0xFF888888);
            if (transcriptionPulseAnimator != null) {
                transcriptionPulseAnimator.cancel();
                transcriptionPulseAnimator = null;
            }
            return;
        }
        List<com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType> models =
                new ArrayList<>(activeTranscriptionModels.keySet());
        com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType pulseModel =
                models.get(transcriptionPulseIndex % models.size());
        int color = modelAccentColor(pulseModel);
        String label = activeTranscriptionModels.size() == 1
                ? "Transcribing " + pulseModel.label
                : "Transcribing " + models.size() + " engines";
        if (status != null && !status.isEmpty()) label = status;
        if (transcriptStatusBadge != null) {
            transcriptStatusBadge.setVisibility(View.VISIBLE);
            transcriptStatusBadge.setText(label);
            transcriptStatusBadge.setTextColor(color);
            transcriptStatusBadge.setBackgroundColor(combineColorWithAlpha(color, 0x2A));
        }
        if (transcriptGlobalProgressBar != null) {
            transcriptGlobalProgressBar.setVisibility(View.VISIBLE);
            if (fraction >= 0f) {
                transcriptGlobalProgressBar.setIndeterminate(false);
                transcriptGlobalProgressBar.setProgress(Math.max(0, Math.min(100, (int) (fraction * 100))));
            } else {
                transcriptGlobalProgressBar.setIndeterminate(true);
            }
        }
        TextView icon = findViewById(R.id.tool_transcript_icon);
        TextView transcriptLabel = findViewById(R.id.tool_transcript_label);
        if (icon != null) icon.setTextColor(color);
        if (transcriptLabel != null) transcriptLabel.setTextColor(color);
        startTranscriptionPulse(color);
    }

    private void startTranscriptionPulse(int color) {
        if (transcriptionPulseAnimator != null) return;
        transcriptionPulseAnimator = ValueAnimator.ofFloat(0.45f, 1f);
        transcriptionPulseAnimator.setDuration(720);
        transcriptionPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        transcriptionPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        transcriptionPulseAnimator.addUpdateListener(animation -> {
            float alpha = (float) animation.getAnimatedValue();
            if (toolTranscript != null) toolTranscript.setAlpha(alpha);
            if (transcriptStatusBadge != null) transcriptStatusBadge.setAlpha(alpha);
            if (transcriptGlobalProgressBar != null) transcriptGlobalProgressBar.setAlpha(alpha);
        });
        transcriptionPulseAnimator.start();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!activeTranscriptionModels.isEmpty()) {
                transcriptionPulseIndex = (transcriptionPulseIndex + 1) % activeTranscriptionModels.size();
                updateTranscriptionStatusUI();
            }
        }, 720);
    }

    private int modelAccentColor(@NonNull com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType type) {
        switch (type) {
            case FAST:
                return 0xFF42A5F5;
            case ACCURATE:
                return 0xFF4CAF50;
            case WHISPER_BASE_EN:
                return 0xFFFFC107;
            default:
                return 0xFF4CAF50;
        }
    }

    private int combineColorWithAlpha(int color, int alpha) {
        return (alpha & 0xFF) << 24 | (color & 0x00FFFFFF);
    }

    /** Index of the version with this id in the clip, or -1. */
    private int indexOfVersion(@NonNull Clip clip, @NonNull String versionId) {
        java.util.List<com.fadcam.ui.faditor.transcript.NamedTranscript> vs =
                clip.getTranscripts();
        for (int i = 0; i < vs.size(); i++) {
            if (vs.get(i).id.equals(versionId)) return i;
        }
        return -1;
    }

    private int indexOfVersion(@NonNull AudioClip clip, @NonNull String versionId) {
        java.util.List<com.fadcam.ui.faditor.transcript.NamedTranscript> vs =
                clip.getTranscripts();
        for (int i = 0; i < vs.size(); i++) {
            if (vs.get(i).id.equals(versionId)) return i;
        }
        return -1;
    }

    /** Replace the words of a transcript version (during/after a run). */
    private boolean updateTranscriptVersion(@NonNull String clipId,
            @NonNull String versionId,
            @NonNull com.fadcam.ui.faditor.transcript.Transcript t) {
        Clip clip = findClipById(clipId);
        if (clip != null) {
            int vi = indexOfVersion(clip, versionId);
            if (vi < 0) return false;
            clip.getTranscripts().get(vi).transcript = t;
            clip.setActiveTranscriptIndex(vi);
            return true;
        }
        // Try audio clip
        AudioClip ac = findAudioClipById(clipId);
        if (ac != null) {
            int vi = indexOfVersion(ac, versionId);
            if (vi < 0) return false;
            ac.getTranscripts().get(vi).transcript = t;
            ac.setActiveTranscriptIndex(vi);
            return true;
        }
        return false;
    }

    /**
     * Rebuild the row of transcript-version chips above the transcript view.
     * Each chip switches the active version; a "+" chip adds another pass.
     */
    private void refreshTranscriptVersionBar() {
        if (transcriptVersionBar == null) return;
        transcriptVersionBar.removeAllViews();
        boolean preferAudio = (editorTimeline.getSelectedAudioIndex() >= 0
                && editorTimeline.getSelectedAudioIndex() < project.getTimeline().getAudioClips().size());
        java.util.List<com.fadcam.ui.faditor.transcript.NamedTranscript> versions;
        int active;
        if (preferAudio) {
            AudioClip ac = project.getTimeline().getAudioClips().get(editorTimeline.getSelectedAudioIndex());
            versions = ac == null ? java.util.Collections.emptyList() : ac.getTranscripts();
            active = ac == null ? -1 : ac.getActiveTranscriptIndex();
        } else {
            Clip clip = getSelectedClip();
            versions = clip == null ? java.util.Collections.emptyList() : clip.getTranscripts();
            active = clip == null ? -1 : clip.getActiveTranscriptIndex();
        }
        if (versions.isEmpty()) {
            if (transcriptVersionScroll != null) transcriptVersionScroll.setVisibility(View.GONE);
            return;
        }
        if (transcriptVersionScroll != null) transcriptVersionScroll.setVisibility(View.VISIBLE);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (8 * density);
        for (int i = 0; i < versions.size(); i++) {
            final int idx = i;
            com.fadcam.ui.faditor.transcript.NamedTranscript v = versions.get(i);
            TextView chip = new TextView(this);
            boolean isActive = i == active;
            // Whisper chips green-tinted, Vosk blue-tinted; active = filled dot.
            int accent = "whisper".equals(v.engine) ? 0xFFFFC107 : 0xFF42A5F5;
            chip.setText((isActive ? "● " : "") + v.label);
            chip.setTextColor(isActive ? accent : 0xFF999999);
            chip.setTextSize(12);
            chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            chip.setPadding(pad, pad / 2, pad, pad / 2);
            chip.setBackgroundResource(R.drawable.floating_button_item_bg);
            chip.setAlpha(isActive ? 1f : 0.75f);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = pad / 2;
            chip.setLayoutParams(lp);
            chip.setOnClickListener(view -> switchTranscriptVersion(idx));
            chip.setOnLongClickListener(view -> { confirmDeleteVersion(idx); return true; });
            transcriptVersionBar.addView(chip);
        }
        // "+ New" chip to add another pass via the model picker.
        TextView add = new TextView(this);
        add.setText("+");
        add.setTextColor(0xFF4CAF50);
        add.setTextSize(14);
        add.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        add.setPadding(pad + pad / 2, pad / 2, pad + pad / 2, pad / 2);
        add.setBackgroundResource(R.drawable.floating_button_item_bg);
        add.setOnClickListener(view -> {
            transcriptView.setVisibility(View.GONE);
            transcriptModelChoice.setVisibility(View.VISIBLE);
            updateModelChoiceReadyLabels();
        });
        transcriptVersionBar.addView(add);
    }

    /** Switch which transcript version is active (panel + captions follow). */
    private void switchTranscriptVersion(int index) {
        boolean preferAudio = (editorTimeline.getSelectedAudioIndex() >= 0
                && editorTimeline.getSelectedAudioIndex() < project.getTimeline().getAudioClips().size());
        if (preferAudio) {
            AudioClip ac = project.getTimeline().getAudioClips().get(editorTimeline.getSelectedAudioIndex());
            if (ac == null) return;
            ac.setActiveTranscriptIndex(index);
            currentTranscript = ac.getTranscript();
            transcriptClipId = ac.getId();
            if (currentTranscript != null) {
                transcriptModelChoice.setVisibility(View.GONE);
                transcriptView.setVisibility(View.VISIBLE);
                transcriptView.setTranscript(currentTranscript);
                editorTimeline.setAudioClipTranscript(transcriptAudioIndex, currentTranscript);
                if (captionsActive && ac.getId().equals(audioCaptionClipId)) {
                    bindAudioCaptionData(ac);
                }
            }
        } else {
            Clip clip = getSelectedClip();
            if (clip == null) return;
            clip.setActiveTranscriptIndex(index);
            currentTranscript = clip.getTranscript();
            transcriptClipId = clip.getId();
            if (currentTranscript != null) {
                transcriptModelChoice.setVisibility(View.GONE);
                transcriptView.setVisibility(View.VISIBLE);
                transcriptView.setTranscript(currentTranscript);
                if (captionsActive && clip.getId().equals(captionClipId)) {
                    showCaptionsForClip(clip);
                }
            }
        }
        refreshTranscriptVersionBar();
        scheduleAutoSave();
    }

    private void confirmDeleteVersion(int index) {
        boolean preferAudio = (editorTimeline.getSelectedAudioIndex() >= 0
                && editorTimeline.getSelectedAudioIndex() < project.getTimeline().getAudioClips().size());
        java.util.List<com.fadcam.ui.faditor.transcript.NamedTranscript> versions;
        Runnable delete;
        if (preferAudio) {
            AudioClip ac = project.getTimeline().getAudioClips().get(editorTimeline.getSelectedAudioIndex());
            if (ac == null || index < 0 || index >= ac.getTranscripts().size()) return;
            versions = ac.getTranscripts();
            delete = () -> {
                ac.removeTranscript(index);
                currentTranscript = ac.getTranscript();
                if (currentTranscript != null) {
                    transcriptView.setTranscript(currentTranscript);
                } else {
                    transcriptView.setVisibility(View.GONE);
                    transcriptModelChoice.setVisibility(View.VISIBLE);
                    updateModelChoiceReadyLabels();
                }
                refreshTranscriptVersionBar();
                saveProjectNow();
            };
        } else {
            Clip clip = getSelectedClip();
            if (clip == null || index < 0 || index >= clip.getTranscripts().size()) return;
            versions = clip.getTranscripts();
            delete = () -> {
                clip.removeTranscript(index);
                currentTranscript = clip.getTranscript();
                if (currentTranscript != null) {
                    transcriptView.setTranscript(currentTranscript);
                } else {
                    transcriptView.setVisibility(View.GONE);
                    transcriptModelChoice.setVisibility(View.VISIBLE);
                    updateModelChoiceReadyLabels();
                }
                refreshTranscriptVersionBar();
                saveProjectNow();
            };
        }
        String label = versions.get(index).label;
        String title = getString(R.string.faditor_transcript_delete_version_title);
        String msg = getString(R.string.faditor_transcript_delete_version_msg, label);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(msg)
                .setNegativeButton(R.string.faditor_cancel, null)
                .setPositiveButton(R.string.faditor_delete_confirm, (d, w) -> delete.run())
                .show();
    }

    private void showTranscriptPanel(boolean show) {
        if (transcriptPanel == null) return;
        ViewGroup.LayoutParams lp = transcriptPanel.getLayoutParams();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        if (lp.width < screenW * 0.20f) {
            lp.width = (int) (screenW * 0.60f);
            transcriptPanel.setLayoutParams(lp);
        }
        transcriptPanel.animate().cancel();
        boolean alreadyOpen = transcriptPanel.getVisibility() == View.VISIBLE && transcriptPanelOpen;
        if (show == alreadyOpen) return;
        if (show) {
            transcriptPanel.setVisibility(View.VISIBLE);
            transcriptPanel.setTranslationX(transcriptPanelOpen ? 0f : screenW);
            transcriptPanel.animate()
                    .translationX(0f)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
            transcriptReopenTab.setVisibility(View.GONE);
            transcriptPanelOpen = true;
            updateTranscriptBreakButton();
        } else {
            transcriptPanelOpen = false;
            transcriptPanel.animate()
                    .translationX(screenW)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (transcriptPanelOpen) return;
                        transcriptPanel.setVisibility(View.GONE);
                        transcriptReopenTab.setVisibility(
                                currentTranscript != null ? View.VISIBLE : View.GONE);
                    })
                    .start();
        }
    }

    /**
     * Rebuild the selected clip's removed-span skip list from the struck words.
     * Non-destructive and live: preview skips these, the timeline draws them dark,
     * and export bakes them out. The clip stays a single editable unit.
     */
    private void syncRemovedSpansFromTranscript() {
        if (currentTranscript == null) return;
        Clip clip = getSelectedClip();
        if (clip == null || !clip.getId().equals(transcriptClipId)) return;

        // Pad each struck word slightly: Vosk timestamps a word's onset a touch
        // late, so without this the very start of the word ("the m of ma'am")
        // leaks through. Clamped to the clip's trim.
        long inMs = clip.getInPointMs();
        long outMs = clip.getOutPointMs();
        List<long[]> raw = new ArrayList<>();
        for (com.fadcam.ui.faditor.transcript.TranscriptWord w : currentTranscript.words) {
            // Only words fully inside this clip's trim window contribute cuts (defensive: once the
            // transcript-windowing rework lands, a clip keeps the full source transcript). Mirrors
            // the timeline's word filter. See tasks/PLAN_transcript_windowing.md.
            if (w.startMs < inMs || w.endMs > outMs) continue;
            if (w.struck) {
                long s = Math.max(inMs, w.startMs - 70);
                long e = Math.min(outMs, w.endMs + 50);
                raw.add(new long[]{s, e});
            }
        }
        java.util.Collections.sort(raw, (a, b) -> Long.compare(a[0], b[0]));

        // Merge adjacent/overlapping struck spans into continuous regions.
        List<long[]> merged = new ArrayList<>();
        for (long[] s : raw) {
            if (!merged.isEmpty() && s[0] <= merged.get(merged.size() - 1)[1] + 80) {
                long[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], s[1]);
            } else {
                merged.add(new long[]{s[0], s[1]});
            }
        }
        clip.setRemovedSpans(merged);
        editorTimeline.invalidate();
        refreshTotalTimeDisplay();
        scheduleAutoSave();
    }

    /**
     * "Commit" — finalise the transcript edits. They are already live on the
     * clip (non-destructive), so this just closes the panel; export will apply
     * them. The clip remains one unit, so the timeline isn't shredded with cuts.
     */
    private void commitTranscript() {
        showTranscriptPanel(false);
        transcriptReopenTab.setVisibility(
                currentTranscript != null ? View.VISIBLE : View.GONE);
        Toast.makeText(this, R.string.faditor_transcript_committed, Toast.LENGTH_SHORT).show();
    }

    /** Reflect the active word's forced-break state in the header toggle. */
    private void updateTranscriptBreakButton() {
        if (transcriptBreakBtn == null) return;
        int idx = transcriptView != null ? transcriptView.getActiveIndex() : -1;
        boolean enabled = idx >= 0 && currentTranscript != null
                && idx < currentTranscript.words.size();
        boolean active = enabled && currentTranscript.words.get(idx).forceLineBreakAfter;
        transcriptBreakBtn.setEnabled(enabled);
        transcriptBreakBtn.setAlpha(enabled ? 1f : 0.4f);
        transcriptBreakBtn.setTextColor(active ? 0xFF4DD0E1 : 0x80FFFFFF);
    }

    // ── Silence detection → yellow candidates → tap to cut ───────────

    private SilenceDetector silenceDetector;

    /** Toggle the yellow silence-candidate view; detect on first show. */
    private void toggleSilenceDetect() {
        if (editorTimeline.isShowSilence()) {
            removeDetectedGaps();
            return;
        }
        Clip clip = getSelectedClip();
        if (clip == null) return;
        if (clip.isImageClip()) {
            Toast.makeText(this, R.string.faditor_silence_image, Toast.LENGTH_SHORT).show();
            return;
        }
        if (inCropMode) exitCropMode(false);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);
        android.widget.TextView label = new android.widget.TextView(this);
        label.setText(R.string.faditor_silence_sensitivity);
        label.setTextColor(0xFFCCCCCC);
        root.addView(label);
        final android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(100);
        seek.setProgress(50);
        root.addView(seek);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.faditor_silence_detect_title)
                .setMessage(R.string.faditor_silence_detect_desc)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.faditor_silence_detect_btn, (d, w) ->
                        runSilenceDetect(seek.getProgress() / 100f))
                .show();
    }

    private void runSilenceDetect(float sensitivity) {
        final Clip clip = getSelectedClip();
        if (clip == null) return;
        Toast.makeText(this, R.string.faditor_silence_analyzing, Toast.LENGTH_SHORT).show();
        playerManager.pause();
        if (silenceDetector == null) silenceDetector = new SilenceDetector(this);
        final long inMs = clip.getInPointMs();
        final long outMs = clip.getOutPointMs();
        silenceDetector.detect(clip.getSourceUri(), inMs, outMs, sensitivity,
                new SilenceDetector.Callback() {
                    @Override
                    public void onResult(@NonNull List<long[]> keepRanges,
                                         int gapsRemoved, long msSaved) {
                        // Silent spans = the gaps between kept ranges.
                        List<long[]> silent = new ArrayList<>();
                        long cursor = inMs;
                        for (long[] k : keepRanges) {
                            if (k[0] > cursor) silent.add(new long[]{cursor, k[0]});
                            cursor = Math.max(cursor, k[1]);
                        }
                        if (cursor < outMs) silent.add(new long[]{cursor, outMs});

                        if (silent.isEmpty()) {
                            Toast.makeText(FaditorEditorActivity.this,
                                    R.string.faditor_silence_none, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        clip.setSilenceCandidates(silent);
                        editorTimeline.setShowSilence(true);
                        editorTimeline.invalidate();
                        Toast.makeText(FaditorEditorActivity.this,
                                getString(R.string.faditor_silence_found, silent.size()),
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        Toast.makeText(FaditorEditorActivity.this,
                                R.string.faditor_silence_none, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** Toggle a tapped yellow candidate: add cut if not cut, remove cut if already cut. */
    private void convertSilenceCandidate(int segIndex, long startMs, long endMs) {
        Timeline tl = project.getTimeline();
        if (segIndex < 0 || segIndex >= tl.getClipCount()) return;
        Clip clip = tl.getClip(segIndex);

        // Check if this span is already in the removedSpans (already cut)
        boolean alreadyCut = false;
        int cutIndex = -1;
        for (int i = 0; i < clip.getRemovedSpans().size(); i++) {
            long[] s = clip.getRemovedSpans().get(i);
            if (s[0] == startMs && s[1] == endMs) {
                alreadyCut = true;
                cutIndex = i;
                break;
            }
        }

        if (alreadyCut) {
            // UNDO: remove this cut span and restore it as a yellow candidate
            List<long[]> spans = new ArrayList<>(clip.getRemovedSpans());
            spans.remove(cutIndex);
            clip.setRemovedSpans(spans);

            if (editorTimeline.isShowSilence()) {
                List<long[]> cands = new ArrayList<>(clip.getSilenceCandidates());
                cands.add(new long[]{startMs, endMs});
                java.util.Collections.sort(cands, (a, b) -> Long.compare(a[0], b[0]));
                clip.setSilenceCandidates(cands);
            }

            // Refresh the timeline's segment data so it sees the updated spans
            editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);
            editorTimeline.invalidate();
            refreshTotalTimeDisplay();
            scheduleAutoSave();
            Toast.makeText(this, "Cut removed", Toast.LENGTH_SHORT).show();
            return;
        }

        List<long[]> spans = new ArrayList<>(clip.getRemovedSpans());
        spans.add(new long[]{startMs, endMs});
        java.util.Collections.sort(spans, (a, b) -> Long.compare(a[0], b[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] s : spans) {
            if (!merged.isEmpty() && s[0] <= merged.get(merged.size() - 1)[1] + 1) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], s[1]);
            } else {
                merged.add(new long[]{s[0], s[1]});
            }
        }
        clip.setRemovedSpans(merged);

        List<long[]> cands = new ArrayList<>(clip.getSilenceCandidates());
        cands.removeIf(c -> c[0] == startMs && c[1] == endMs);
        clip.setSilenceCandidates(cands);

        // Refresh the timeline's segment data so it sees the updated spans
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);
        editorTimeline.invalidate();
        refreshTotalTimeDisplay();
        scheduleAutoSave();
    }

    /** Dialog with a sensitivity slider, then runs detection + jump-cut removal. */
    private void showSilenceRemovalDialog() {
        Clip clip = getSelectedClip();
        if (clip == null) return;
        if (clip.isImageClip()) {
            Toast.makeText(this, R.string.faditor_silence_image, Toast.LENGTH_SHORT).show();
            return;
        }
        if (inCropMode) exitCropMode(false);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);

        android.widget.TextView label = new android.widget.TextView(this);
        label.setText(R.string.faditor_silence_sensitivity);
        label.setTextColor(0xFFCCCCCC);
        root.addView(label);

        final android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(100);
        seek.setProgress(50);
        root.addView(seek);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.faditor_silence_title)
                .setMessage(R.string.faditor_silence_desc)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.faditor_silence_title, (d, w) ->
                        runSilenceRemoval(seek.getProgress() / 100f))
                .show();
    }

    private void removeDetectedGaps() {
        final Clip clip = getSelectedClip();
        if (clip == null) return;
        if (clip.getSilenceCandidates() == null || clip.getSilenceCandidates().isEmpty()) {
            Toast.makeText(this, R.string.faditor_silence_none, Toast.LENGTH_SHORT).show();
            return;
        }
        List<long[]> candidates = new ArrayList<>(clip.getSilenceCandidates());
        java.util.Collections.sort(candidates, (a, b) -> Long.compare(a[0], b[0]));
        List<long[]> keepRanges = new ArrayList<>();
        long cursor = clip.getInPointMs();
        long saved = 0;
        for (long[] gap : candidates) {
            if (gap[0] > cursor) {
                keepRanges.add(new long[]{cursor, Math.min(gap[0], clip.getOutPointMs())});
            }
            long end = Math.min(gap[1], clip.getOutPointMs());
            if (end > gap[0]) saved += end - gap[0];
            cursor = Math.max(cursor, end);
        }
        if (cursor < clip.getOutPointMs()) {
            keepRanges.add(new long[]{cursor, clip.getOutPointMs()});
        }
        if (keepRanges.size() <= 1) {
            Toast.makeText(this, R.string.faditor_silence_none, Toast.LENGTH_SHORT).show();
            return;
        }
        applySilenceCuts(selectedClipIndex, clip, keepRanges, candidates.size(), saved);
    }

    private void runSilenceRemoval(float sensitivity) {
        final Clip clip = getSelectedClip();
        if (clip == null) return;
        final int index = selectedClipIndex;

        Toast.makeText(this, R.string.faditor_silence_analyzing, Toast.LENGTH_SHORT).show();
        playerManager.pause();

        if (silenceDetector == null) {
            silenceDetector = new SilenceDetector(this);
        }
        silenceDetector.detect(clip.getSourceUri(),
                clip.getInPointMs(), clip.getOutPointMs(), sensitivity,
                new SilenceDetector.Callback() {
                    @Override
                    public void onResult(@NonNull List<long[]> keepRanges,
                                         int gapsRemoved, long msSaved) {
                        if (gapsRemoved == 0 || keepRanges.size() <= 1) {
                            Toast.makeText(FaditorEditorActivity.this,
                                    R.string.faditor_silence_none, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        applySilenceCuts(index, clip, keepRanges, gapsRemoved, msSaved);
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        Toast.makeText(FaditorEditorActivity.this,
                                R.string.faditor_silence_none, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** Replace the clip with one jump-cut clip per keep range. */
    private void applySilenceCuts(int index, @NonNull Clip original,
                                  @NonNull List<long[]> keepRanges,
                                  int gapsRemoved, long msSaved) {
        if (index < 0 || index >= project.getTimeline().getClipCount()) return;

        List<Clip> keeps = new ArrayList<>();
        for (long[] range : keepRanges) {
            Clip c = new Clip(original); // copies all effects, fresh id
            c.setInPointMs(range[0]);
            c.setOutPointMs(range[1]);
            keeps.add(c);
        }

        Timeline timeline = project.getTimeline();
        timeline.removeClip(index);
        for (int i = keeps.size() - 1; i >= 0; i--) {
            timeline.addClip(index, keeps.get(i));
        }
        undoManager.recordAction(new EditActions.ReplaceClipsAction(
                timeline, index, original, keeps));

        editorTimeline.setTimeline(timeline, index);
        selectSegment(index);
        syncTimelineOverlays();
        editorTimeline.invalidate();
        refreshTotalTimeDisplay();
        saveProjectNow();

        Toast.makeText(this,
                getString(R.string.faditor_silence_result, gapsRemoved, msSaved / 1000f),
                Toast.LENGTH_LONG).show();
    }

    // ── Jump to time ─────────────────────────────────────────────────

    /** Prompt for a time (s, m:ss, or h:mm:ss) and seek the timeline there. */
    private void showSeekToTimeDialog() {
        if (project == null || editorTimeline == null) return;

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("m:ss");
        input.setInputType(android.text.InputType.TYPE_CLASS_DATETIME
                | android.text.InputType.TYPE_DATETIME_VARIATION_TIME);
        input.setText(TimeFormatter.formatAuto(editorTimeline.getPlayheadPositionMs()));
        input.setSelectAllOnFocus(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.faditor_jump_to_time)
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    long ms = parseTimeToMs(input.getText().toString());
                    if (ms >= 0) {
                        playerManager.pause();
                        editorTimeline.seekToTimelineMs(ms);
                    }
                })
                .show();
    }

    /** Parse "ss", "m:ss", or "h:mm:ss" (decimals allowed) to ms; -1 if invalid. */
    private long parseTimeToMs(@NonNull String text) {
        try {
            String t = text.trim();
            if (t.isEmpty()) return -1;
            String[] parts = t.split(":");
            double seconds;
            if (parts.length == 1) {
                seconds = Double.parseDouble(parts[0]);
            } else if (parts.length == 2) {
                seconds = Integer.parseInt(parts[0]) * 60 + Double.parseDouble(parts[1]);
            } else if (parts.length == 3) {
                seconds = Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60 + Double.parseDouble(parts[2]);
            } else {
                return -1;
            }
            return (long) (seconds * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ── Auto-save ────────────────────────────────────────────────────

    private void scheduleAutoSave() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS);
    }

    /**
     * Save project immediately (blocking on current thread, fast for small JSON).
     * Also persists the undo history snapshots alongside the project.
     */
    private void saveProjectNow() {
        saveProjectNow(false);
    }

    /**
     * @param forceUndoHistory when true (e.g. onPause), always flush the undo history;
     *                         otherwise it is throttled to avoid the per-edit cost of
     *                         re-serializing every snapshot.
     */
    private void saveProjectNow(boolean forceUndoHistory) {
        if (project != null && projectStorage != null) {
            autoSaveHandler.removeCallbacks(autoSaveRunnable);
            // forceUndoHistory is set on the critical paths (onPause/onDestroy) where
            // we must guarantee the bytes hit disk before the activity can die — use
            // the synchronous save + flush there. The per-edit hot path uses the
            // async save (serialize on UI thread, write on a background thread) so
            // trimming/importing/transitions don't block on disk I/O.
            if (forceUndoHistory) {
                projectStorage.save(project);
            } else {
                projectStorage.saveAsync(project);
            }

            // Persist undo history (throttled — see UNDO_HISTORY_SAVE_THROTTLE_MS).
            long now = android.os.SystemClock.elapsedRealtime();
            if (forceUndoHistory || now - lastUndoHistorySaveMs > UNDO_HISTORY_SAVE_THROTTLE_MS) {
                lastUndoHistorySaveMs = now;
                List<UndoManager.HistoryEntry> history = undoManager.getUndoHistory();
                if (!history.isEmpty()) {
                    List<String> descriptions = new ArrayList<>();
                    List<String> snapshots = new ArrayList<>();
                    for (UndoManager.HistoryEntry entry : history) {
                        if (entry.getSnapshotBefore() != null) {
                            descriptions.add(entry.getDescription());
                            snapshots.add(entry.getSnapshotBefore());
                        }
                    }
                    if (forceUndoHistory) {
                        projectStorage.saveUndoHistory(project.getId(), descriptions, snapshots);
                    } else {
                        projectStorage.saveUndoHistoryAsync(project.getId(), descriptions, snapshots);
                    }
                }
            }

            if (forceUndoHistory) {
                projectStorage.flushPendingWrites();
            }
            FLog.d(TAG, "Project saved: " + project.getId());
        }
    }

    // ── Utility ──────────────────────────────────────────────────────

    /**
     * Get video duration using FFprobeKit (reliable for fragmented MP4),
     * with MediaMetadataRetriever as fallback.
     */
    private long getVideoDuration(@NonNull Uri videoUri) {
        // ── FFprobeKit (primary — reliable for fMP4) ────────────
        try {
            String filePath = getFFprobePathForUri(videoUri);
            com.arthenica.ffmpegkit.MediaInformationSession session =
                    com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(filePath);
            com.arthenica.ffmpegkit.MediaInformation info = session.getMediaInformation();
            if (info != null) {
                String durationStr = info.getDuration();
                if (durationStr != null) {
                    double durationSec = Double.parseDouble(durationStr);
                    long durationMs = (long) (durationSec * 1000);
                    FLog.d(TAG, "Duration from FFprobe: " + durationMs + "ms");
                    return durationMs;
                }
            }
        } catch (Exception e) {
            FLog.e(TAG, "FFprobe duration failed", e);
        }

        // ── MediaMetadataRetriever fallback ──────────────────────
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            if ("file".equals(videoUri.getScheme()) && videoUri.getPath() != null) {
                retriever.setDataSource(videoUri.getPath());
            } else {
                // For content:// URIs, try to resolve to a file path first
                // (ContentResolver-based setDataSource can fail on SD card)
                String resolvedPath = resolveSafPath(videoUri);
                if (resolvedPath != null) {
                    java.io.File f = new java.io.File(resolvedPath);
                    if (f.exists() && f.canRead()) {
                        retriever.setDataSource(resolvedPath);
                    } else {
                        retriever.setDataSource(this, videoUri);
                    }
                } else {
                    retriever.setDataSource(this, videoUri);
                }
            }
            String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long ms = Long.parseLong(durationStr);
                FLog.d(TAG, "Duration from MMR fallback: " + ms + "ms");
                return ms;
            }
        } catch (Exception e) {
            FLog.e(TAG, "MMR duration failed", e);
        } finally {
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) { }
            }
        }

        return -1;
    }

    /**
     * Build a file path suitable for FFprobeKit.
     * Uses {@link #resolveSafPath(Uri)} for SAF content:// URIs from
     * both internal storage and SD card.
     */
    @NonNull
    private String getFFprobePathForUri(@NonNull Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return uri.getPath();
        }
        String resolved = resolveSafPath(uri);
        if (resolved != null) {
            java.io.File f = new java.io.File(resolved);
            if (f.exists() && f.canRead()) {
                FLog.d(TAG, "FFprobe using resolved path: " + resolved);
                return resolved;
            }
        }
        return "saf:" + uri.toString();
    }


    // ── Add Asset ──────────────────────────────────────────────────

    /**
     * Registers ActivityResultLaunchers for image and video asset picking.
     * Must be called early in onCreate (before onStart).
     */
    private void registerAssetPickers() {
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            if (relinkPendingIndex >= 0) {
                                int idx = relinkPendingIndex;
                                relinkPendingIndex = -1;
                                handleRelinkPick(idx, uri);
                            } else {
                                onImageAssetPicked(uri);
                            }
                        }
                    }
                });

        videoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            if (relinkPendingIndex >= 0) {
                                int idx = relinkPendingIndex;
                                relinkPendingIndex = -1;
                                handleRelinkPick(idx, uri);
                            } else if (overlayVideoPickerPending) {
                                overlayVideoPickerPending = false;
                                onOverlayVideoPicked(uri);
                            } else {
                                onVideoAssetPicked(uri);
                            }
                        } else {
                            overlayVideoPickerPending = false;
                        }
                    } else {
                        overlayVideoPickerPending = false;
                    }
                });

        overlayImagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    boolean asNewLayer = imageAsNewLayerPending;
                    imageAsNewLayerPending = false;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            if (asNewLayer) {
                                onImageAsNewLayerPicked(uri);
                            } else {
                                onOverlayImagePicked(uri);
                            }
                        }
                    }
                });

        audioPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            if (relinkPendingIndex < 0) {
                                onAudioFilePicked(uri);
                            } else {
                                int idx = relinkPendingIndex;
                                relinkPendingIndex = -1;
                                handleRelinkPick(idx, uri);
                            }
                        }
                    }
                });
    }

    /** Import an external audio file as an audio-track clip. */
    private void onAudioFilePicked(@NonNull Uri audioUri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    audioUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }

        long durationMs = 0;
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(this, audioUri);
            String d = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            r.release();
            if (d != null) durationMs = Long.parseLong(d);
        } catch (Exception e) {
            FLog.w(TAG, "Audio duration probe failed", e);
        }
        if (durationMs <= 0) {
            Toast.makeText(this, R.string.faditor_audio_import_error, Toast.LENGTH_SHORT).show();
            return;
        }

        final com.fadcam.ui.faditor.model.AudioClip ac =
                new com.fadcam.ui.faditor.model.AudioClip(audioUri, durationMs);
        ac.setLabel(getString(R.string.faditor_add_asset_audio));
        // Place at current playhead position, not beginning
        long playheadMs = editorTimeline != null ? editorTimeline.getPlayheadPositionMs() : 0;
        ac.setOffsetMs(playheadMs);
        project.getTimeline().addAudioClip(ac);
        editorTimeline.setAudioClips(project.getTimeline().getAudioClips());

        // Waveform in the background.
        new com.fadcam.ui.faditor.util.AudioExtractor(this).generateWaveform(audioUri,
                new com.fadcam.ui.faditor.util.AudioExtractor.WaveformCallback() {
                    @Override
                    public void onWaveformReady(@NonNull int[] waveform) {
                        ac.setWaveform(waveform);
                        editorTimeline.invalidate();
                    }

                    @Override
                    public void onError(@NonNull Exception error) { }
                });

        prepareAudioPlayer();
        updateAudioToolUI();
        refreshTotalTimeDisplay();
        saveProjectNow();
        Toast.makeText(this, R.string.faditor_audio_import_added, Toast.LENGTH_SHORT).show();
    }

    /**
     * Show a bottom sheet letting the user choose between adding an image or video asset.
     */
    // ── Asset Browser ──────────────────────────────────────────────────

    /**
     * Show the asset browser panel (drops down from the top bar).
     * If no directory is pinned, prompts the user to pick one first.
     */
    private void showAssetBrowser() {
        if (project == null) return;

        // If no directory is pinned, prompt to pick one
        if (project.getPinnedAssetDir() == null) {
            assetDirPickerLauncher.launch(null);
            return;
        }

        // If panel is already open, no-op (keep it open)
        if (assetBrowserPanel != null && assetBrowserPanel.isAttachedToWindow()) {
            return;
        }

        // Create and show the panel
        assetBrowserPanel = new com.fadcam.ui.faditor.assetbrowser.AssetBrowserPanel(this);
        assetBrowserPanel.setProject(project);
        assetBrowserPanel.setCallback(new com.fadcam.ui.faditor.assetbrowser.AssetBrowserPanel.Callback() {
            @Override
            public void onAssetSelected(@NonNull com.fadcam.ui.faditor.assetbrowser.AssetItem item) {
                if (relinkPendingIndex >= 0) {
                    int idx = relinkPendingIndex;
                    relinkPendingIndex = -1;
                    // Copy to internal storage and apply
                    Uri picked = item.uri;
                    String subDir = item.type == com.fadcam.ui.faditor.assetbrowser.AssetItem.Type.IMAGE
                            ? "images" : "videos";
                    picked = copyUriToInternalStorage(picked, subDir);
                    if ("content".equals(picked.getScheme())) {
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    picked, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException ignored) { }
                    }
                    String name = item.displayName != null ? item.displayName : "file";
                    applyRelink(idx, picked, name, item.durationMs);
                } else {
                    selectedAsset = item;
                    updateInsertAffordanceVisibility();
                }
            }

            @Override
            public void onAssetDragStarted(@NonNull com.fadcam.ui.faditor.assetbrowser.AssetItem item,
                                           @NonNull View sourceView, float localX, float localY) {
                startAssetDrag(item, sourceView, localX, localY);
            }

            @Override
            public void onAssetInserted(@NonNull com.fadcam.ui.faditor.assetbrowser.AssetItem item, int timelineIndex) {
                insertAssetAtIndex(item, timelineIndex);
            }

            @Override
            public void onChangeDirectoryRequested() {
                assetDirPickerLauncher.launch(null);
            }

            @Override
            public void onNavigateDirectory(@NonNull String treeUriStr) {
                if (project != null) {
                    project.setPinnedAssetDir(treeUriStr);
                    saveProjectNow();
                }
            }

            @Override
            public void onRenameAsset(@NonNull com.fadcam.ui.faditor.assetbrowser.AssetItem item,
                                      @NonNull String newName) {
                renameAsset(item, newName);
            }

            @Override
            public void onDeleteFromHistory(@NonNull String treeUriStr) {
                deleteDirFromHistory(treeUriStr);
            }

            @Override
            public void onPanelCollapsed() {
                assetBrowserPanel = null;
                // Clear relink mode if user dismissed without picking
                relinkPendingIndex = -1;
                // The insert affordance belongs to the open panel — clear it so a
                // stray tap can't insert the last-selected asset after closing.
                selectedAsset = null;
                if (insertAffordanceBar != null) {
                    insertAffordanceBar.setVisibility(View.GONE);
                }
            }
        });

        // Add to root frame layout so it overlays everything
        ViewGroup root = findViewById(android.R.id.content);
        root.addView(assetBrowserPanel);
        assetBrowserPanel.expand();

        // Insert affordance bar (added AFTER the panel, so it's on top)
        if (insertAffordanceBar == null) {
            insertAffordanceBar = createInsertAffordanceBar();
        }
        if (insertAffordanceBar.getParent() == null) {
            root.addView(insertAffordanceBar);
        }
        updateInsertAffordanceVisibility();
    }

    private View createInsertAffordanceBar() {
        float d = getResources().getDisplayMetrics().density;
        int barPad = (int)(8 * d);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(barPad, barPad, barPad, barPad);
        bar.setBackgroundColor(Color.parseColor("#DD333333"));
        bar.setElevation(24 * d);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int)(100 * d); // sits above the panel
        bar.setLayoutParams(lp);

        // Insert at start
        TextView btnStart = new TextView(this);
        btnStart.setText("|< Insert");
        btnStart.setTextColor(Color.WHITE);
        btnStart.setTextSize(12);
        btnStart.setPadding((int)(12*d), (int)(8*d), (int)(12*d), (int)(8*d));
        btnStart.setBackgroundColor(Color.parseColor("#664CAF50"));
        btnStart.setGravity(Gravity.CENTER);
        btnStart.setOnClickListener(v -> {
            if (selectedAsset != null && project != null) {
                insertAssetAtIndex(selectedAsset, 0);
            }
        });
        bar.addView(btnStart);

        // Main insert at playhead (green arrow)
        TextView btnPlayhead = new TextView(this);
        btnPlayhead.setTypeface(ResourcesCompat.getFont(this, R.font.materialicons));
        btnPlayhead.setText("play_arrow");
        btnPlayhead.setTextColor(Color.WHITE);
        btnPlayhead.setTextSize(28);
        btnPlayhead.setBackground(getDrawable(R.drawable.asset_insert_triangle_bg));
        btnPlayhead.setGravity(Gravity.CENTER);
        int size = (int)(48 * d);
        btnPlayhead.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        btnPlayhead.setOnClickListener(v -> insertSelectedAssetAtPlayhead());
        bar.addView(btnPlayhead);

        // Insert at end
        TextView btnEnd = new TextView(this);
        btnEnd.setText("Insert >|");
        btnEnd.setTextColor(Color.WHITE);
        btnEnd.setTextSize(12);
        btnEnd.setPadding((int)(12*d), (int)(8*d), (int)(12*d), (int)(8*d));
        btnEnd.setBackgroundColor(Color.parseColor("#664CAF50"));
        btnEnd.setGravity(Gravity.CENTER);
        btnEnd.setOnClickListener(v -> {
            if (selectedAsset != null && project != null) {
                int lastIdx = project.getTimeline().getClips().size();
                insertAssetAtIndex(selectedAsset, lastIdx);
            }
        });
        bar.addView(btnEnd);

        return bar;
    }

    private void updateInsertAffordanceVisibility() {
        if (insertAffordanceBar != null) {
            insertAffordanceBar.setVisibility(
                    selectedAsset != null ? View.VISIBLE : View.GONE);
        }
    }

    private void startAssetDrag(@NonNull AssetItem item, @NonNull View sourceView,
                                float localX, float localY) {
        assetDragItem = item;
        assetDragSourceView = sourceView;
        assetDragActive = true;
        assetDragInsertIndex = -1;
        assetDragLastInsertIndex = -1;
        if (assetBrowserPanel != null) {
            assetBrowserPanel.highlightAsset(null);
        }

        float density = getResources().getDisplayMetrics().density;
        int size = (int)(64 * density);
        assetDragView = new TextView(this);
        assetDragView.setTypeface(ResourcesCompat.getFont(this, R.font.materialicons));
        assetDragView.setText(getAssetDragIcon(item));
        assetDragView.setTextColor(Color.parseColor("#FFFFFFFF"));
        assetDragView.setGravity(Gravity.CENTER);
        assetDragView.setTextSize(26);
        assetDragView.setBackgroundResource(R.drawable.asset_insert_btn_bg);
        assetDragView.setElevation(18f * density);

        ViewGroup root = findViewById(android.R.id.content);
        root.addView(assetDragView, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START));

        int[] loc = new int[2];
        sourceView.getLocationOnScreen(loc);
        float x = loc[0] + localX - size / 2f;
        float y = loc[1] + localY - size / 2f;
        assetDragView.setX(x);
        assetDragView.setY(y);
        assetDragView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        editorTimeline.startAssetDrag();
    }

    private void updateAssetDrag(float screenX, float screenY) {
        if (!assetDragActive || assetDragView == null) return;
        TextView view = assetDragView;
        float density = getResources().getDisplayMetrics().density;
        int size = view.getWidth() > 0 ? view.getWidth() : (int)(64 * density);
        float x = Math.max(0, Math.min(screenX - size / 2f,
                ((ViewGroup) view.getParent()).getWidth() - size));
        float y = Math.max(0, Math.min(screenY - size / 2f,
                ((ViewGroup) view.getParent()).getHeight() - size));
        view.setX(x);
        view.setY(y);
        int newIndex = editorTimeline.updateAssetDrag(screenX, screenY);
        if (newIndex >= 0 && newIndex != assetDragLastInsertIndex) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            assetDragLastInsertIndex = newIndex;
        }
        assetDragInsertIndex = newIndex;
    }

    private void endAssetDrag(boolean commit) {
        if (!assetDragActive) return;
        AssetItem item = assetDragItem;
        int index = assetDragInsertIndex;
        assetDragActive = false;
        assetDragInsertIndex = -1;
        editorTimeline.endAssetDrag(commit);
        if (assetDragView != null && assetDragView.getParent() instanceof ViewGroup) {
            ((ViewGroup) assetDragView.getParent()).removeView(assetDragView);
        }
        if (!commit && assetBrowserPanel != null && item != null) {
            assetBrowserPanel.highlightAsset(item);
        }
        assetDragView = null;
        assetDragItem = null;
        assetDragSourceView = null;
        if (commit && item != null && index >= 0) {
            insertAssetAtIndex(item, index);
        }
    }

    @NonNull
    private String getAssetDragIcon(@NonNull AssetItem item) {
        switch (item.type) {
            case IMAGE:
                return "photo";
            case AUDIO:
                return "music_note";
            case VIDEO:
            default:
                return "movie";
        }
    }

    /**
     * Insert the currently selected asset at the playhead position.
     * If the playhead is mid-clip, the clip is split and the asset inserted
     * between the two halves. If between clips, inserted at that position.
     */
    private void insertSelectedAssetAtPlayhead() {
        if (selectedAsset == null || project == null) return;
        insertAssetAtPlayhead(selectedAsset);
    }

    /**
     * Insert an asset at the current playhead position.
     * Splits the clip at the playhead if needed, then inserts the new clip.
     */
    /** Copy inserted assets up to this size into the project; larger ones stay referenced. */
    private static final long ASSET_COPY_MAX_BYTES = 25L * 1024 * 1024;

    /**
     * Make an inserted asset durable (B1 fix). All images, plus any file at or under
     * {@link #ASSET_COPY_MAX_BYTES}, are copied into {@code <projectDir>/assets/} and
     * referenced by the internal {@code file://} URI — so they survive reinstall and
     * travel with the project (saved as {@code project://} relative paths). Large
     * videos keep their original URI to avoid duplicating gigabytes; a best-effort
     * persistable tree grant is taken by the caller. Returns the URI to store on the
     * clip — the copy when copied, otherwise the original.
     */
    @NonNull
    private Uri importInsertedAsset(@NonNull Uri src,
                                    @NonNull com.fadcam.ui.faditor.assetbrowser.AssetItem.Type type,
                                    @Nullable String displayName) {
        try {
            if (project == null || projectStorage == null) return src;
            boolean isImage = type == com.fadcam.ui.faditor.assetbrowser.AssetItem.Type.IMAGE;
            long size = queryAssetSize(src);
            boolean shouldCopy = isImage || (size >= 0 && size <= ASSET_COPY_MAX_BYTES);
            if (!shouldCopy) return src;
            File assetsDir = new File(projectStorage.projectDir(project.getId()), "assets");
            if (!assetsDir.exists() && !assetsDir.mkdirs()) return src;
            File dest = new File(assetsDir,
                    java.util.UUID.randomUUID() + assetExtension(src, displayName, type));
            try (InputStream in = getContentResolver().openInputStream(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                if (in == null) { dest.delete(); return src; }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            FLog.d(TAG, "Copied inserted asset into project: " + dest.getName() + " (" + size + " bytes)");
            return Uri.fromFile(dest);
        } catch (Exception e) {
            FLog.w(TAG, "Asset copy failed, referencing original: " + src, e);
            return src;
        }
    }

    /** Size of an asset in bytes, or -1 if unknown. */
    private long queryAssetSize(@NonNull Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            return f.exists() ? f.length() : -1;
        }
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    /** File extension (with leading dot) for a copied asset, from name, MIME, or type. */
    @NonNull
    private String assetExtension(@NonNull Uri uri, @Nullable String displayName,
                                  @NonNull com.fadcam.ui.faditor.assetbrowser.AssetItem.Type type) {
        if (displayName != null) {
            int dot = displayName.lastIndexOf('.');
            if (dot >= 0 && dot < displayName.length() - 1) return displayName.substring(dot);
        }
        String mime = getContentResolver().getType(uri);
        String ext = mime != null
                ? android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) : null;
        if (ext != null) return "." + ext;
        switch (type) {
            case IMAGE: return ".jpg";
            case AUDIO: return ".m4a";
            default: return ".mp4";
        }
    }

    private void insertAssetAtPlayhead(@NonNull com.fadcam.ui.faditor.assetbrowser.AssetItem item) {
        try {
            // Best-effort persist the original grant first (helps large referenced
            // videos), then copy small assets into the project for durability.
            try {
                getContentResolver().takePersistableUriPermission(
                        item.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            Uri uri = importInsertedAsset(item.uri, item.type, item.displayName);

            // Create the clip
            Clip newClip;
            if (item.type == com.fadcam.ui.faditor.assetbrowser.AssetItem.Type.IMAGE) {
                newClip = new Clip(uri, IMAGE_CLIP_DURATION_MS);
                newClip.setImageClip(true);
                newClip.setAudioMuted(true);
            } else if (item.type == com.fadcam.ui.faditor.assetbrowser.AssetItem.Type.AUDIO) {
                // Audio files are added as audio clips, not video clips
                long dur = getAudioDuration(uri);
                AudioClip ac = new AudioClip(uri, dur);
                ac.setLabel(item.displayName);
                project.getTimeline().addAudioClip(ac);
                editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
                refreshTotalTimeDisplay();
                saveProjectNow();
                Toast.makeText(this, R.string.faditor_asset_added, Toast.LENGTH_SHORT).show();
                return;
            } else {
                long dur = getVideoDuration(uri);
                if (dur <= 0) {
                    Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                newClip = new Clip(uri, dur);
            }

            // Determine insert index from playhead position
            long playheadMs = editorTimeline.getPlayheadPositionMs();
            int insertIndex = computeInsertIndexAtTimelineMs(playheadMs);

            // If playhead is mid-clip, split first
            if (insertIndex >= 0 && selectedClipIndex >= 0) {
                Clip currentClip = getSelectedClip();
                if (currentClip != null && !currentClip.isImageClip()) {
                    long segStart = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
                    long segEffective = currentClip.getEffectiveDurationMs();
                    long localMs = playheadMs - segStart;
                    // Only split if playhead is strictly inside the clip (not at edges)
                    if (localMs > 200 && localMs < segEffective - 200) {
                        long sourceSplitMs = currentClip.getInPointMs()
                                + (long)(localMs * currentClip.getSpeedMultiplier());
                        project.getTimeline().splitAt(selectedClipIndex, sourceSplitMs);
                        project.getTimeline().shiftTransitionsAfterSplit(selectedClipIndex);
                        insertIndex = selectedClipIndex + 1;
                    }
                }
            }

            if (insertIndex < 0) insertIndex = project.getTimeline().getClipCount();
            project.getTimeline().addClip(insertIndex, newClip);
            project.getTimeline().shiftTransitionsAfterInsert(insertIndex);
            undoManager.recordAction(new EditActions.AddClipAction(
                    project.getTimeline(), newClip, insertIndex));

            selectSegment(insertIndex);
            editorTimeline.setTransitions(project.getTimeline().getTransitions());
            editorTimeline.scrollToSegment(insertIndex);
            syncTimelineOverlays();
            editorTimeline.invalidate();
            refreshTotalTimeDisplay();
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_asset_added, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FLog.e(TAG, "Failed to insert asset at playhead", e);
            Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Insert an asset at a specific timeline index (used by drag-drop).
     */
    private void insertAssetAtIndex(@NonNull com.fadcam.ui.faditor.assetbrowser.AssetItem item, int timelineIndex) {
        try {
            try {
                getContentResolver().takePersistableUriPermission(
                        item.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            Uri uri = importInsertedAsset(item.uri, item.type, item.displayName);

            Clip newClip;
            if (item.type == com.fadcam.ui.faditor.assetbrowser.AssetItem.Type.IMAGE) {
                newClip = new Clip(uri, IMAGE_CLIP_DURATION_MS);
                newClip.setImageClip(true);
                newClip.setAudioMuted(true);
            } else if (item.type == com.fadcam.ui.faditor.assetbrowser.AssetItem.Type.AUDIO) {
                long dur = getAudioDuration(uri);
                AudioClip ac = new AudioClip(uri, dur);
                ac.setLabel(item.displayName);
                project.getTimeline().addAudioClip(ac);
                editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
                refreshTotalTimeDisplay();
                saveProjectNow();
                return;
            } else {
                long dur = getVideoDuration(uri);
                if (dur <= 0) {
                    Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                newClip = new Clip(uri, dur);
            }

            int idx = Math.max(0, Math.min(timelineIndex, project.getTimeline().getClipCount()));
            project.getTimeline().addClip(idx, newClip);
            project.getTimeline().shiftTransitionsAfterInsert(idx);
            undoManager.recordAction(new EditActions.AddClipAction(
                    project.getTimeline(), newClip, idx));
            selectSegment(idx);
            editorTimeline.setTransitions(project.getTimeline().getTransitions());
            editorTimeline.scrollToSegment(idx);
            syncTimelineOverlays();
            editorTimeline.invalidate();
            refreshTotalTimeDisplay();
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_asset_added, Toast.LENGTH_SHORT).show();
            if (item.type != com.fadcam.ui.faditor.assetbrowser.AssetItem.Type.IMAGE
                    && item.type != com.fadcam.ui.faditor.assetbrowser.AssetItem.Type.AUDIO) {
                maybeShowTranscribePrompt();
            }
        } catch (Exception e) {
            FLog.e(TAG, "Failed to insert asset", e);
            Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Compute the timeline index at which to insert a new clip so that it
     * appears at the given timeline position (ms).
     */
    private int computeInsertIndexAtTimelineMs(long timelineMs) {
        Timeline tl = project.getTimeline();
        if (tl.getClipCount() == 0) return 0;
        long cumul = 0;
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            long effective = c.getEffectiveDurationMs();
            if (timelineMs <= cumul + effective / 2) {
                return i;
            }
            cumul += effective;
        }
        return tl.getClipCount();
    }

    /** Get audio file duration via MediaMetadataRetriever. */
    private long getAudioDuration(@NonNull Uri uri) {
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(this, uri);
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            r.release();
            if (d != null) return Long.parseLong(d);
        } catch (Exception e) {
            FLog.w(TAG, "Failed to get audio duration", e);
        }
        return 5000;
    }

    /**
     * Rename a file in the pinned directory (SAF).
     * Preserves the file extension.
     */
    private void renameAsset(@NonNull com.fadcam.ui.faditor.assetbrowser.AssetItem item,
                             @NonNull String newName) {
        try {
            android.provider.DocumentsContract.Document doc = null;
            // Use DocumentFile for SAF rename
            androidx.documentfile.provider.DocumentFile df =
                    androidx.documentfile.provider.DocumentFile.fromSingleUri(this, item.uri);
            if (df != null && df.canWrite()) {
                // Ensure extension is preserved
                String oldName = item.displayName;
                String ext = "";
                int dot = oldName.lastIndexOf('.');
                if (dot >= 0) ext = oldName.substring(dot);
                String finalName = newName;
                if (!finalName.toLowerCase().endsWith(ext.toLowerCase())) {
                    finalName = finalName + ext;
                }
                if (df.renameTo(finalName)) {
                    item.displayName = finalName;
                    // Update display name on all clips referencing this asset
                    if (project != null) {
                        for (int i = 0; i < project.getTimeline().getClipCount(); i++) {
                            Clip clip = project.getTimeline().getClip(i);
                            if (clip.getSourceUri().toString().equals(item.uri.toString())) {
                                clip.setDisplayName(finalName);
                            }
                        }
                        saveProjectNow();
                    }
                    if (assetBrowserPanel != null) {
                        assetBrowserPanel.refresh();
                    }
                    Toast.makeText(this,
                            getString(R.string.faditor_asset_browser_rename_success, finalName),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.faditor_asset_browser_rename_error,
                            Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            FLog.e(TAG, "Failed to rename asset", e);
            Toast.makeText(this, R.string.faditor_asset_browser_rename_error,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Remove a directory from the pinned history. Blocked if files from
     * that directory are currently in use by the project.
     */
    private void deleteDirFromHistory(@NonNull String treeUriStr) {
        if (project == null) return;
        if (isDirectoryInUse(treeUriStr)) {
            Toast.makeText(this, R.string.faditor_asset_browser_delete_blocked,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        project.removeAssetDirFromHistory(treeUriStr);
        saveProjectNow();
        if (assetBrowserPanel != null) {
            assetBrowserPanel.onDirectoryRemovedFromHistory(treeUriStr);
        }
    }

    private boolean isDirectoryInUse(@NonNull String treeUriStr) {
        for (Clip c : project.getTimeline().getClips()) {
            if (isUriUnderTree(c.getSourceUri(), treeUriStr)) {
                return true;
            }
        }
        for (AudioClip ac : project.getTimeline().getAudioClips()) {
            if (isUriUnderTree(ac.getSourceUri(), treeUriStr)) {
                return true;
            }
        }
        return false;
    }

    /** Check if a document URI is under a tree URI. */
    private boolean isUriUnderTree(@NonNull Uri docUri, @NonNull String treeStr) {
        String docUriStr = docUri.toString();
        if (docUriStr.startsWith(treeStr)) {
            return true;
        }
        Uri treeUri = Uri.parse(treeStr);
        String treeId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
        String docId = android.provider.DocumentsContract.getDocumentId(docUri);
        return docId != null && treeId != null && (docId.equals(treeId) || docId.startsWith(treeId + "/"));
    }

    private void showAddAssetPicker() {
        AddAssetBottomSheet sheet = AddAssetBottomSheet.newInstance();
        sheet.setCallback(new AddAssetBottomSheet.Callback() {
            @Override
            public void onAudioSelected() {
                audioPickerLauncher.launch(openDocumentIntent("audio/*"));
            }

            @Override
            public void onOverlayVideoSelected() {
                // M-COMP-2b: pick a source for the floating PiP layer — same
                // FadCam-recordings-first source sheet the master video path uses.
                VideoSourceBottomSheet vs = new VideoSourceBottomSheet();
                vs.setCallback(new VideoSourceBottomSheet.Callback() {
                    @Override
                    public void onRecordingSelected(@NonNull Uri videoUri) {
                        onOverlayVideoPicked(videoUri);
                    }

                    @Override
                    public void onBrowseDevice() {
                        overlayVideoPickerPending = true;
                        videoPickerLauncher.launch(openDocumentIntent("video/*"));
                    }
                });
                vs.show(getSupportFragmentManager(), "pipVideoSource");
            }

            @Override
            public void onAssetTypeSelected(boolean isImage) {
            if (isImage) {
                imagePickerLauncher.launch(openDocumentIntent("image/*"));
            } else {
                // Offer FadCam's own recordings (file:// — durable, reliable for
                // export) instead of forcing the OS picker (content:// — loses
                // access on reinstall and fails the export asset loader).
                VideoSourceBottomSheet vs = new VideoSourceBottomSheet();
                vs.setCallback(new VideoSourceBottomSheet.Callback() {
                    @Override
                    public void onRecordingSelected(@NonNull Uri videoUri) {
                        onVideoAssetPicked(videoUri);
                    }

                    @Override
                    public void onBrowseDevice() {
                        videoPickerLauncher.launch(openDocumentIntent("video/*"));
                    }
                });
                vs.show(getSupportFragmentManager(), "videoSource");
                }
            }

            @Override
            public void onImageAsNewLayerSelected() {
                imageAsNewLayerPending = true;
                overlayImagePickerLauncher.launch(openDocumentIntent("image/*"));
            }
        });
        sheet.show(getSupportFragmentManager(), "addAsset");
    }

    /**
     * Handle a picked image URI: create a still-image clip (5 seconds)
     * and add it to the timeline after the currently selected segment.
     */
    private void onImageAssetPicked(@NonNull Uri imageUri) {
        try {
            // Copy content URI to internal storage so it survives app restart
            imageUri = copyUriToInternalStorage(imageUri, "images");

            // Take persistable permission so the URI stays valid across sessions
            try {
                getContentResolver().takePersistableUriPermission(
                        imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                FLog.w(TAG, "Could not take persistable URI permission", e);
            }

            Clip imageClip = new Clip(imageUri, IMAGE_CLIP_DURATION_MS);
            imageClip.setImageClip(true);
            imageClip.setAudioMuted(true); // Images have no audio

            // Insert after the currently selected segment
            Timeline timeline = project.getTimeline();
            int insertIndex = selectedClipIndex + 1;
            timeline.addClip(insertIndex, imageClip);
            timeline.shiftTransitionsAfterInsert(insertIndex);

            // Record undo action
            undoManager.recordAction(new EditActions.AddClipAction(
                    timeline, imageClip, insertIndex));

            // Select the new clip and bring it into view
            selectSegment(insertIndex);
            editorTimeline.setTransitions(project.getTimeline().getTransitions());
            editorTimeline.scrollToSegment(insertIndex);
            syncTimelineOverlays();
            editorTimeline.invalidate();
            refreshTotalTimeDisplay();
            saveProjectNow();

            Toast.makeText(this, R.string.faditor_asset_added, Toast.LENGTH_SHORT).show();
            FLog.d(TAG, "Image asset added at index " + insertIndex
                    + " duration=" + IMAGE_CLIP_DURATION_MS + "ms uri=" + imageUri);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to add image asset", e);
            Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Handle a picked video URI: determine its duration, create a clip,
     * and add it to the timeline after the currently selected segment.
     */
    private void onVideoAssetPicked(@NonNull Uri pickedUri) {
        // The file copy + duration probe (FFprobe/MMR) can block for seconds on a
        // SAF/content URI — doing it inline ANR-ed the main thread on every insert.
        // Run the heavy IO on a background thread, then mutate the timeline on the
        // main thread once the duration is known.
        showRemuxProgress();
        final Uri srcUri = pickedUri;
        assetImportExecutor.execute(() -> {
            Uri resolvedUri = srcUri;
            long durationMs = -1;
            try {
                // Copy content URI to internal storage so it survives app restart
                resolvedUri = copyUriToInternalStorage(srcUri, "videos");

                // Take persistable permission so the URI stays valid across sessions
                try {
                    getContentResolver().takePersistableUriPermission(
                            resolvedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    FLog.w(TAG, "Could not take persistable URI permission", e);
                }

                durationMs = getVideoDuration(resolvedUri);
            } catch (Exception e) {
                FLog.e(TAG, "Failed to import video asset (IO)", e);
            }

            final Uri videoUri = resolvedUri;
            final long finalDuration = durationMs;
            runOnUiThread(() -> {
                hideRemuxProgress();
                if (isFinishing() || isDestroyed() || project == null) return;
                if (finalDuration <= 0) {
                    FLog.w(TAG, "Could not determine video duration for added asset");
                    Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    Clip videoClip = new Clip(videoUri, finalDuration);

                    // Insert after the currently selected segment
                    Timeline timeline = project.getTimeline();
                    int insertIndex = selectedClipIndex + 1;
                    timeline.addClip(insertIndex, videoClip);
                    timeline.shiftTransitionsAfterInsert(insertIndex);

                    // Record undo action
                    undoManager.recordAction(new EditActions.AddClipAction(
                            timeline, videoClip, insertIndex));

                    // Select the new clip and bring it into view
                    selectSegment(insertIndex);
                    editorTimeline.setTransitions(project.getTimeline().getTransitions());
                    editorTimeline.scrollToSegment(insertIndex);
                    syncTimelineOverlays();
                    editorTimeline.invalidate();
                    refreshTotalTimeDisplay();
                    saveProjectNow();

                    Toast.makeText(this, R.string.faditor_asset_added, Toast.LENGTH_SHORT).show();
                    FLog.d(TAG, "Video asset added at index " + insertIndex
                            + " duration=" + finalDuration + "ms uri=" + videoUri);

                    maybeShowTranscribePrompt();
                } catch (Exception e) {
                    FLog.e(TAG, "Failed to add video asset", e);
                    Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ── Feature A: "Transcribe this video?" prompt ──────────────────────────

    /**
     * Shown right after a new video clip finishes importing (see
     * {@link #onVideoAssetPicked}). Offers to kick off one or more of the
     * existing transcription engines for the clip that was just added (which
     * is already the selected segment at this point, via {@code selectSegment}
     * in the caller). Respects the "Don't ask me again" preference, which can
     * be re-enabled from the editor's Settings sheet
     * ({@link FaditorSettingsBottomSheet}).
     */
    private void maybeShowTranscribePrompt() {
        if (prefsManager == null || !prefsManager.isFaditorAskToTranscribeEnabled()) return;
        if (project == null) return;

        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * dp), pad, 0);

        TextView message = new TextView(this);
        message.setText(R.string.faditor_transcribe_prompt_message);
        message.setTextColor(0xFFCCCCCC);
        message.setTextSize(13);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.bottomMargin = (int) (12 * dp);
        message.setLayoutParams(msgLp);
        root.addView(message);

        final android.widget.CheckBox cbFast = new android.widget.CheckBox(this);
        cbFast.setText(getString(R.string.faditor_transcript_model_fast)
                + " — " + getString(R.string.faditor_transcript_model_fast_sub));
        cbFast.setTextColor(0xFFFFFFFF);
        root.addView(cbFast);

        final android.widget.CheckBox cbAccurate = new android.widget.CheckBox(this);
        cbAccurate.setText(getString(R.string.faditor_transcript_model_accurate)
                + " — " + getString(R.string.faditor_transcript_model_accurate_sub));
        cbAccurate.setTextColor(0xFFFFFFFF);
        root.addView(cbAccurate);

        final android.widget.CheckBox cbWhisper = new android.widget.CheckBox(this);
        cbWhisper.setText(getString(R.string.faditor_transcript_model_whisper)
                + " — " + getString(R.string.faditor_transcript_model_whisper_sub));
        cbWhisper.setTextColor(0xFFFFFFFF);
        root.addView(cbWhisper);

        final android.widget.CheckBox cbDontAsk = new android.widget.CheckBox(this);
        cbDontAsk.setText(R.string.faditor_transcribe_prompt_dont_ask);
        cbDontAsk.setTextColor(0xFF999999);
        cbDontAsk.setTextSize(12);
        cbDontAsk.setChecked(false);
        LinearLayout.LayoutParams dontAskLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dontAskLp.topMargin = (int) (14 * dp);
        cbDontAsk.setLayoutParams(dontAskLp);
        root.addView(cbDontAsk);

        androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.faditor_transcribe_prompt_title)
                        .setView(root)
                        .setCancelable(true)
                        .create();

        // Central OK button: built ourselves (instead of builder's right-aligned
        // positive button) so it renders centered per spec. Cancel/dismiss (back
        // press, tap-outside, or system back) is treated as "none selected" —
        // no transcription starts and the "don't ask again" pref is untouched,
        // matching MaterialAlertDialogBuilder's default cancel behavior.
        TextView okButton = new TextView(this);
        okButton.setText(android.R.string.ok);
        okButton.setTextColor(0xFF4DD0E1);
        okButton.setTextSize(15);
        okButton.setTypeface(null, Typeface.BOLD);
        okButton.setGravity(Gravity.CENTER);
        okButton.setBackground(getDrawable(android.R.drawable.list_selector_background));
        okButton.setPadding(0, (int) (14 * dp), 0, (int) (14 * dp));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        okLp.topMargin = (int) (18 * dp);
        okLp.bottomMargin = (int) (8 * dp);
        okButton.setLayoutParams(okLp);
        okButton.setOnClickListener(v -> {
            if (cbDontAsk.isChecked()) {
                prefsManager.setFaditorAskToTranscribeEnabled(false);
            }
            java.util.List<com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType> selected =
                    new java.util.ArrayList<>();
            if (cbFast.isChecked()) {
                selected.add(com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType.FAST);
            }
            if (cbAccurate.isChecked()) {
                selected.add(com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType.ACCURATE);
            }
            if (cbWhisper.isChecked()) {
                selected.add(com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType.WHISPER_BASE_EN);
            }
            dialog.dismiss();
            startQueuedTranscriptions(selected);
        });
        root.addView(okButton);

        dialog.show();
    }

    /** Queue of transcription models still waiting to run for the clip that triggered them. */
    private final java.util.ArrayDeque<com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType>
            pendingTranscribePromptModels = new java.util.ArrayDeque<>();

    /**
     * Runs the selected models one at a time via the existing
     * {@link #startTranscription} path. {@link TranscriptionEngine} already
     * serializes work onto a single background executor, so queuing here
     * (rather than firing all calls at once) avoids piling up UI state
     * (progress bar/text, {@code activeTranscriptionModels} badge) for
     * several concurrent runs against the same clip; each model starts once
     * the previous one's {@code onResult}/{@code onError} callback fires.
     */
    private void startQueuedTranscriptions(
            @NonNull java.util.List<com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType> models) {
        if (models.isEmpty()) return;
        pendingTranscribePromptModels.clear();
        pendingTranscribePromptModels.addAll(models);
        runNextQueuedTranscription();
    }

    private void runNextQueuedTranscription() {
        com.fadcam.ui.faditor.transcript.TranscriptionEngine.ModelType next =
                pendingTranscribePromptModels.poll();
        if (next == null) return;
        // Make sure the transcription targets the clip the prompt was shown for
        // (it's already the selected segment from onVideoAssetPicked/selectSegment).
        resolveTranscriptTarget();
        startTranscription(next, this::runNextQueuedTranscription);
    }

    // ── Segment Operations ──────────────────────────────────────────────────

    private static final long HEAL_DEFAULT_RANGE_MS = 500L;
    private static final long HEAL_MIN_EDGE_MS = 100L;
    private static final long SPLIT_HEAL_SEAM_MS = 250L;

    private void splitOrHealAtPlayhead() {
        if (splitHealMode) {
            healAtPlayhead();
        } else {
            splitAtPlayhead();
        }
    }

    private void updateSplitHealButton() {
        Timeline timeline = project != null ? project.getTimeline() : null;
        Clip clip = getSelectedClip();
        boolean heal = false;
        if (timeline != null && clip != null && !clip.isImageClip()) {
            long playhead = editorTimeline != null ? editorTimeline.getPlayheadPositionMs() : 0;
            for (int i = 1; i < timeline.getClipCount(); i++) {
                long seam = editorTimeline.getSegmentStartTimeMs(i);
                if (Math.abs(playhead - seam) <= SPLIT_HEAL_SEAM_MS) {
                    heal = true;
                    break;
                }
            }
        }
        splitHealMode = heal;
        if (toolSplitIcon != null) {
            toolSplitIcon.setText(heal ? "healing" : "content_cut");
            toolSplitIcon.setTextColor(heal ? 0xFFFFC107 : 0xFF888888);
        }
        if (toolSplitLabel != null) {
            toolSplitLabel.setText(heal ? "Heal" : getString(R.string.faditor_tool_split));
            toolSplitLabel.setTextColor(heal ? 0xFFFFC107 : 0xFF888888);
        }
        if (toolSplitIcon != null) {
            toolSplitIcon.animate().cancel();
            toolSplitIcon.setScaleX(1f);
            toolSplitIcon.setScaleY(1f);
            toolSplitIcon.animate()
                    .scaleX(1.18f).scaleY(1.18f)
                    .setDuration(120)
                    .withEndAction(() -> {
                        toolSplitIcon.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(120)
                                .start();
                    })
                    .start();
        }
    }

    /**
     * Remove a small non-destructive gap around the playhead.
     */
    private void healAtPlayhead() {
        try {
            if (editorTimeline.getSelectedAudioIndex() >= 0) {
                Toast.makeText(this, R.string.faditor_split_error, Toast.LENGTH_SHORT).show();
                return;
            }

            Clip clip = getSelectedClip();
            if (clip == null || clip.isImageClip()) {
                Toast.makeText(this, R.string.faditor_heal_error, Toast.LENGTH_SHORT).show();
                return;
            }

            playerManager.pause();

            long playheadMs = editorTimeline.getPlayheadPositionMs();
            long segStartMs = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
            long localEffectiveMs = Math.max(0, playheadMs - segStartMs);
            long playheadSourceMs = clip.getInPointMs()
                    + (long)(localEffectiveMs * clip.getSpeedMultiplier());
            playheadSourceMs = Math.max(clip.getInPointMs(),
                    Math.min(playheadSourceMs, clip.getOutPointMs()));

            long leftRoom = playheadSourceMs - clip.getInPointMs();
            long rightRoom = clip.getOutPointMs() - playheadSourceMs;
            if (leftRoom < HEAL_MIN_EDGE_MS || rightRoom < HEAL_MIN_EDGE_MS) {
                Toast.makeText(this, R.string.faditor_heal_error, Toast.LENGTH_SHORT).show();
                return;
            }

            long half = HEAL_DEFAULT_RANGE_MS / 2L;
            long start = Math.max(clip.getInPointMs() + HEAL_MIN_EDGE_MS,
                    playheadSourceMs - half);
            long end = Math.min(clip.getOutPointMs() - HEAL_MIN_EDGE_MS,
                    playheadSourceMs + half);
            if (end - start < HEAL_MIN_EDGE_MS) {
                Toast.makeText(this, R.string.faditor_heal_error, Toast.LENGTH_SHORT).show();
                return;
            }

            final long finalStart = start;
            final long finalEnd = end;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.faditor_heal_title)
                    .setMessage(getString(R.string.faditor_heal_msg,
                            TimeFormatter.formatAuto(finalEnd - finalStart)))
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        Clip c = getSelectedClip();
                        if (c == null) return;
                        c.getRemovedSpans().add(new long[]{finalStart, finalEnd});
                        undoManager.recordAction(new EditActions.AddRemovedSpanAction(
                                c, finalStart, finalEnd));
                        editorTimeline.invalidate();
                        refreshTotalTimeDisplay();
                        saveProjectNow();
                        Toast.makeText(FaditorEditorActivity.this,
                                R.string.faditor_heal_success, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Exception e) {
            FLog.e(TAG, "healAtPlayhead failed", e);
            Toast.makeText(this, R.string.faditor_heal_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Split the selected segment at the current playhead position.
     */
    private void splitAtPlayhead() {
        try {
            // Check if an audio clip is selected — split that
            int audioIdx = editorTimeline.getSelectedAudioIndex();
            if (audioIdx >= 0) {
                splitAudioAtPlayhead(audioIdx);
                return;
            }

            Clip clip = getSelectedClip();
            if (clip == null) return;

            playerManager.pause();

            // Derive the split point from the VISUAL timeline playhead (authoritative)
            // rather than playerManager.getCurrentPosition(), which may not have settled
            // yet if a seek was still in-flight when the user tapped the split button.
            long playheadMs = editorTimeline.getPlayheadPositionMs();
            long segStartMs = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
            long localEffectiveMs = Math.max(0, playheadMs - segStartMs);
            // Convert effective (timeline) time → absolute source position (accounts for speed)
            long absoluteSplitMs = clip.getInPointMs()
                    + (long)(localEffectiveMs * clip.getSpeedMultiplier());
            absoluteSplitMs = Math.max(clip.getInPointMs(),
                    Math.min(absoluteSplitMs, clip.getOutPointMs()));

            FLog.d(TAG, "splitAtPlayhead: playhead=" + playheadMs + " segStart=" + segStartMs
                    + " local=" + localEffectiveMs + " absoluteSplit=" + absoluteSplitMs);

            // Save reference to original clip before split
            Clip originalClip = clip;
            int originalIndex = selectedClipIndex;

            Timeline timeline = project.getTimeline();
            int newIndex = timeline.splitAt(selectedClipIndex, absoluteSplitMs);
            if (newIndex < 0) {
                Toast.makeText(this, R.string.faditor_split_error, Toast.LENGTH_SHORT).show();
                return;
            }
            timeline.shiftTransitionsAfterSplit(newIndex);

            // Record undo action with the two new clips
            Clip clipA = timeline.getClip(newIndex);
            Clip clipB = timeline.getClip(newIndex + 1);
            undoManager.recordAction(new EditActions.SplitClipAction(
                    timeline, originalIndex, originalClip, clipA, clipB));

            // Update the player's trim end to match clip A's new out-point.
            // Use updateTrimEndOnly so we DON'T trigger an unwanted seekTo(0).
            // Then park the player 1 ms before the trim end (avoids the edge case where
            // pos == trimEndMs would let play() think we're out of bounds).
            playerManager.updateTrimEndOnly(clipA.getOutPointMs());
            long trimmedMs = clipA.getOutPointMs() - clipA.getInPointMs();
            if (trimmedMs > 1) {
                playerManager.seekTo(trimmedMs - 1);
            }

            selectSegment(selectedClipIndex);
            syncTimelineOverlays();
            editorTimeline.invalidate();
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_split_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FLog.e(TAG, "splitAtPlayhead failed", e);
            Toast.makeText(this, R.string.faditor_split_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Split the selected audio clip at the current playhead position.
     * Creates two audio clips from the original at the split point.
     */
    private void splitAudioAtPlayhead(int audioIdx) {
        Timeline timeline = project.getTimeline();
        AudioClip ac = timeline.getAudioClip(audioIdx);
        if (ac == null) return;

        long playheadMs = editorTimeline.getPlayheadPositionMs();
        long audioStartOnTimeline = ac.getOffsetMs();
        long audioEndOnTimeline = ac.getEndOnTimelineMs();

        // Check if playhead is within the audio clip
        if (playheadMs <= audioStartOnTimeline || playheadMs >= audioEndOnTimeline) {
            Toast.makeText(this, R.string.faditor_split_error, Toast.LENGTH_SHORT).show();
            return;
        }

        // Minimum 500ms on each side
        long splitInSource = ac.getInPointMs() + (playheadMs - audioStartOnTimeline);
        if (splitInSource - ac.getInPointMs() < 500 || ac.getOutPointMs() - splitInSource < 500) {
            Toast.makeText(this, R.string.faditor_split_error, Toast.LENGTH_SHORT).show();
            return;
        }

        // Create two clips from the original
        AudioClip left = new AudioClip(ac);
        left.setOutPointMs(splitInSource);

        AudioClip right = new AudioClip(ac);
        right.setInPointMs(splitInSource);
        right.setOffsetMs(playheadMs); // Starts at the split point on the timeline

        // Record undo action before modifying timeline
        undoManager.recordAction(new EditActions.SplitAudioClipAction(
                timeline, audioIdx, ac, left, right));

        // Remove original, add the two new clips
        timeline.removeAudioClip(audioIdx);
        timeline.addAudioClip(left, false);
        timeline.addAudioClip(right, false);

        editorTimeline.setAudioClips(timeline.getAudioClips());
        prepareAudioPlayer();
        scheduleAutoSave();
        Toast.makeText(this, R.string.faditor_split_success, Toast.LENGTH_SHORT).show();
    }

    /**
     * Delete the currently selected segment (cannot delete the last remaining segment).
     */
    private void deleteSelectedSegment() {
        try {
            // If a transition is selected (blue), the trash icon removes the TRANSITION, not the clip —
            // users intuitively hit trash to remove a selected transition and must not lose their clip.
            int transIdx = editorTimeline.getSelectedTransitionIndex();
            if (transIdx >= 0) {
                deleteTransition(transIdx);
                return;
            }

            // Check if an audio clip is selected — delete that instead
            int audioIdx = editorTimeline.getSelectedAudioIndex();
            if (audioIdx >= 0) {
                deleteSelectedAudioClip(audioIdx);
                return;
            }

            Timeline timeline = project.getTimeline();
            if (timeline.getClipCount() <= 1) {
                Toast.makeText(this, R.string.faditor_delete_last_segment, Toast.LENGTH_SHORT).show();
                return;
            }

            // PHASE-P P3 (M11): in gap mode a master delete leaves a black spacer in
            // place instead of rippling later clips left. Ripple mode = unchanged path.
            if ("gap".equals(timeline.getRippleMode())) {
                gapDeleteSelectedSegment(timeline);
                return;
            }

            // Record undo action before deletion
            Clip deletedClip = timeline.getClip(selectedClipIndex);
            int deletedIndex = selectedClipIndex;
            undoManager.recordAction(new EditActions.DeleteClipAction(
                    timeline, deletedClip, deletedIndex));

            timeline.removeClip(selectedClipIndex);
            timeline.removeTransitionsForDeletedClip(deletedIndex);

            int newIndex = Math.min(selectedClipIndex, timeline.getClipCount() - 1);
            selectSegment(newIndex);
            editorTimeline.setTransitions(timeline.getTransitions());
            syncTimelineOverlays();
            editorTimeline.invalidate();
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_segment_deleted, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FLog.e(TAG, "deleteSelectedSegment failed", e);
        }
    }

    /**
     * Deletes the selected audio clip from the timeline.
     */
    private void deleteSelectedAudioClip(int audioIndex) {
        Timeline timeline = project.getTimeline();
        if (audioIndex < 0 || audioIndex >= timeline.getAudioClipCount()) return;

        // Record undo action before deletion
        AudioClip deletedClip = timeline.getAudioClip(audioIndex);
        undoManager.recordAction(new EditActions.DeleteAudioClipAction(
                timeline, deletedClip, audioIndex));

        timeline.removeAudioClip(audioIndex);
        editorTimeline.setAudioClips(timeline.getAudioClips());
        syncTimelineOverlays();
        editorTimeline.invalidate();
        releaseAudioPlayer();
        if (timeline.hasAudioClips()) {
            prepareAudioPlayer();
        }
        updateAudioToolUI();
        saveProjectNow();
        Toast.makeText(this, R.string.faditor_segment_deleted, Toast.LENGTH_SHORT).show();
    }

    /**
     * Duplicate the currently selected segment (inserts a copy right after it).
     */
    private void duplicateSelectedSegment() {
        try {
            Timeline timeline = project.getTimeline();
            int newIndex = timeline.duplicateClip(selectedClipIndex);
            if (newIndex < 0) return;
            timeline.shiftTransitionsAfterInsert(newIndex);

            // Record undo action for the duplication
            Clip duplicated = timeline.getClip(newIndex);
            undoManager.recordAction(new EditActions.DuplicateClipAction(
                    timeline, duplicated, newIndex));

            selectSegment(newIndex);
            editorTimeline.setTransitions(timeline.getTransitions());
            syncTimelineOverlays();
            editorTimeline.invalidate();
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_segment_duplicated, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FLog.e(TAG, "duplicateSelectedSegment failed", e);
        }
    }

}
