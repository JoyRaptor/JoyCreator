package com.fadcam.ui.faditor.export;

import com.fadcam.ui.faditor.Studio;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodecInfo;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.SpeedChangingAudioProcessor;
import androidx.media3.effect.Crop;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.common.Effect;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.playback.FragmentedMp4Remuxer;
import com.fadcam.ui.faditor.CanvasPickerBottomSheet;
import com.fadcam.ui.faditor.gltransitions.GlTransitionExportEffect;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.audio.LoudnessAnalyzer;
import com.fadcam.ui.faditor.audio.fx.AudioFxChainFactory;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.ExportSettings;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.Transition;
import com.fadcam.ui.faditor.compositor.LayerPreviewController;
import com.fadcam.ui.faditor.layers.BlendMode;
import com.fadcam.ui.faditor.layers.TimedItem;
import com.fadcam.ui.faditor.layers.Track;
import com.fadcam.ui.faditor.layers.TrackFlags;
import com.fadcam.ui.faditor.layers.TrackKind;
import com.fadcam.ui.faditor.model.WaveformData;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.model.WaveformStyle;
import com.fadcam.ui.faditor.export.OpacityExportEffect;
import com.fadcam.ui.faditor.waveform.WaveformExtractor;
import com.fadcam.ui.faditor.waveform.WaveformStyleIO;
import com.fadcam.utils.RecordingStoragePaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestrates video export using Media3 Transformer.
 *
 * <p>Handles both near-lossless trim (single clip, no effects) and
 * full re-encode (effects, speed changes, multi-clip).</p>
 */
public class ExportManager {

    private static final String TAG = "ExportManager";

    /** C4 — Loudness targets for export (EBU R128). Stored in ExportSettings via ProjectStorage, but also mirrored here for wiring. */
    public enum LoudnessTarget {
        OFF(null, "Off"),
        YOUTUBE(-14d, "YouTube -14 LUFS"),
        PODCAST(-16d, "Podcast -16 LUFS"),
        TIKTOK(-14d, "TikTok -14 LUFS"),
        BROADCAST(-23d, "Broadcast -23 LUFS");

        @Nullable public final Double lufs;
        @NonNull public final String label;
        LoudnessTarget(@Nullable Double lufs, @NonNull String label) { this.lufs = lufs; this.label = label; }
    }

    // Compatibility hook: when true, non-default-quality exports request the H.264 Baseline
    // profile instead of the encoder's default (High on API >= 26). Default OFF — flip this (or
    // later wire it to an ExportSettings flag) to opt into Baseline. Requires the matching
    // "FadCam patch" in the media3-patched DefaultEncoderFactory to take effect.
    private static final boolean REQUEST_BASELINE_PROFILE = false;

    @NonNull
    private final Context context;

    @NonNull
    private final SharedPreferencesManager prefsManager;

    @Nullable
    private Transformer transformer;

    @Nullable
    private ExportListener listener;

    private boolean isExporting = false;

    /** When true, the export was written to a temp file that needs to be copied to SAF. */
    private boolean pendingSafCopy = false;

    /** The display filename used for the SAF DocumentFile. */
    @Nullable
    private String safExportFileName = null;

    /** C4 — pending loudness target for the next export (set by the export dialog, not persisted in model/). */
    @NonNull
    private LoudnessTarget pendingLoudnessTarget = LoudnessTarget.OFF;

    public void setPendingLoudnessTarget(@NonNull LoudnessTarget target) {
        this.pendingLoudnessTarget = target;
    }

    @NonNull
    public LoudnessTarget getPendingLoudnessTarget() {
        return pendingLoudnessTarget;
    }

    /**
     * C4 — integrated LUFS of the LAST completed export, measured with ebur128
     * immediately before ({@code Before}) and immediately after ({@code After}) the
     * loudnorm correction pass. {@code After} is null when no correction ran (target
     * OFF and Clean Audio unchecked) or the pass failed.
     */
    @Nullable private volatile Double lastExportLoudnessBefore;
    @Nullable private volatile Double lastExportLoudnessAfter;

    /**
     * C7 — A/B bypass snapshot taken ONCE at export start and passed into every FX chain
     * this export builds. Export runs in a service: it must consult screen state exactly
     * once, deterministically, at a defined moment — never mid-flight, and never as an
     * ambient read that goes stale after process death.
     */
    private boolean fxBypassedSnapshot = false;

    /**
     * Whether the user asked for audio PROCESSING on this export ("Clean Audio").
     *
     * <p>Snapshotted beside {@link #fxBypassedSnapshot} at export start for the same reason:
     * an export runs in a service and must not read a screen's live state part-way through.
     * Gates the voice chain, which until 2026-08-24 was applied to every audio clip
     * unconditionally — a gate and a de-esser across every music track.</p>
     */
    private boolean cleanAudioSnapshot = false;

    @Nullable
    public Double getLastExportLoudnessBeforeLUFS() { return lastExportLoudnessBefore; }

    @Nullable
    public Double getLastExportLoudnessAfterLUFS() { return lastExportLoudnessAfter; }

    /** Handler for periodic progress polling. */
    private final Handler progressHandler = new Handler(Looper.getMainLooper());

    /** Reusable progress holder to avoid allocation on every poll. */
    private final ProgressHolder progressHolder = new ProgressHolder();
    /** EXPORT_PACE state: last percent printed, and the clock it is measured from. */
    private int lastLoggedProgressPct = -1;
    private long progressEpochMs = 0L;
    /**
     * ITEM-0 seam timing: composition-start ms of every video-sequence item, recorded
     * when the composition is built, so the progress poller can log EXPORT_SEAM lines
     * as the muxer crosses item boundaries. Per-item wall times are the hot measurement
     * the 30:35 stall diagnosis needs (cold baseline comes from the pre-flight probe).
     */
    private long[] exportItemStartMs = new long[0];
    private long exportItemTotalMs = 0L;
    private int lastSeamItem = -1;

    /** Interval between progress polls (ms). */
    private static final long PROGRESS_POLL_INTERVAL_MS = 300;

    /** Length (ms) of the pre-generated silence WAV used to pad audio-track gaps. */
    /**
     * Length of the silence spacer WAV; longer gaps are cut into pieces this long. 1 minute
     * (5 MB) rather than 10 (53 MB) because it now lives in DURABLE storage — see
     * getOrCreateSilenceFile.
     */
    private static final long SILENCE_FILE_MS = 60_000L;

    /**
     * Minimum TIMELINE length (ms) an exported video segment must have to be worth
     * emitting. Anything shorter is guaranteed to be under one output-frame interval
     * (≈40ms == one frame at 25fps), so Media3 clips it to a zero/near-zero-duration
     * {@link EditedMediaItem} that produces NO output sample. A no-sample item stalls
     * the muxer until its watchdog aborts the whole export with
     * "Muxer error … Abort: no output sample written in the last 10000 milliseconds".
     * Such micro-segments only arise at transition seams where a transition is as long
     * as (or longer than) the clip it straddles — never in the normal case (transition
     * shorter than both clips), so this guard is a no-op for ordinary timelines.
     */
    private static final long MIN_EXPORT_SEGMENT_MS = 40L;

    /**
     * Slop (ms) when deciding whether a clipped window still overlaps the source's AUDIO
     * track. Audio streams routinely end a few ms before the video track; a window that
     * starts within this margin of the audio end is treated as past-audio.
     */
    private static final long AUDIO_COVERAGE_EPS_MS = 5L;

    /**
     * Per-source cache: uri → audio-track duration ms. {@code 0} = source has NO audio
     * track; {@link Long#MAX_VALUE} = duration unknown/unreadable (assume covered — never
     * strip audio on a guess). Filled lazily during composition builds via a one-shot
     * MediaExtractor probe (device-verify 2026-07-12 found the class of muxer stall the
     * 313e7fa seam-clamp missed: a transition-trimmed residual window that starts PAST the
     * end of the source's audio track produces ZERO audio samples, and the AudioGraph
     * stalls the whole export until the 10s watchdog aborts — AudioExportVerify clip[4],
     * window 4811..4884 of a source whose audio ends earlier).
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> sourceAudioDurMs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> sourceAudioSampleRate =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Thread-local MediaMetadataRetriever cache used during composition building.
     *
     * <p>{@link MediaMetadataRetriever} is <strong>not thread-safe</strong>: a single instance
     * must never be accessed concurrently from multiple threads. We keep one retriever per
     * calling thread and reuse it across source-dimension probes and still-frame extractions
     * within one {@link #buildComposition} call. The retriever is released in a
     * {@code finally} block after the composition is built.</p>
     *
     * <p>Each thread has its own instance, so this satisfies the "one retriever per worker
     * thread, never share" contract.</p>
     */
    private final ThreadLocal<android.media.MediaMetadataRetriever> retrieverPool = new ThreadLocal<>();
    private final ThreadLocal<String> retrieverCurrentUri = new ThreadLocal<>();

    /**
     * FIX-4: the in-progress output path of the running export (final path +
     * {@code .exporting}), or null when idle. The muxer writes here; success renames it
     * onto the final name, error/cancel deletes it — a failed export must never leave a
     * short file wearing the finished name (2026-09-21: 28-min and 30:35 partials
     * mistaken for completed exports). Same directory, so the commit is an atomic rename.
     */
    @Nullable
    private String currentStagingPath = null;

    /** Staging path for a final output path (same dir → rename commit is atomic). */
    @NonNull
    private static String stagingPathFor(@NonNull String finalPath) {
        return finalPath + ".exporting";
    }

    /**
     * Commit a finished staging file onto its final name. Returns the path the caller
     * should announce (final on success; staging itself if the rename impossibly
     * fails, so a good export is never lost to a rename error).
     */
    @NonNull
    private String commitStaging(@NonNull String stagingPath, @NonNull String finalPath) {
        currentStagingPath = null;
        File staging = new File(stagingPath);
        File fin = new File(finalPath);
        if (!staging.exists()) {
            FLog.e(TAG, "commitStaging: staging file missing: " + stagingPath);
            return finalPath;
        }
        if (staging.getAbsolutePath().equals(fin.getAbsolutePath())) return finalPath;
        if (fin.exists() && !fin.delete()) {
            FLog.w(TAG, "commitStaging: could not remove existing " + finalPath);
        }
        if (staging.renameTo(fin)) {
            trace("TRACE_COMMIT " + fin.getName() + " (" + fin.length() + " bytes)");
            closeTrace();
            return finalPath;
        }
        FLog.e(TAG, "commitStaging: rename failed, keeping " + stagingPath);
        closeTrace();
        return stagingPath;
    }

    /** Delete an in-progress staging file, if any. Idempotent. */
    private void discardStaging(@Nullable String stagingPath) {
        currentStagingPath = null;
        if (stagingPath != null) {
            trace("TRACE_DISCARD " + new java.io.File(stagingPath).getName());
        }
        closeTrace();
        if (stagingPath == null) return;
        try {
            File f = new File(stagingPath);
            if (f.exists() && !f.delete()) {
                FLog.w(TAG, "discardStaging: could not delete " + stagingPath);
            }
        } catch (Exception e) {
            FLog.w(TAG, "discardStaging failed", e);
        }
    }

    /**
     * Lazily-created remuxer used only to LOOK UP a cached seekable copy of a raw
     * fragmented-MP4 source. The cache is warmed off the main thread by
     * {@link ExportService} before export; this class never blocks on remuxing.
     */
    @Nullable
    private FragmentedMp4Remuxer exportRemuxer;

    /**
     * L2: lazily-created cache of baked TRUE-reversed segments for PING_PONG loop legs. Used only
     * as a LOOKUP here (never blocks) — {@link ExportService} warms it synchronously off the main
     * thread before export so a reverse leg the preview showed as true-reverse is baked in time.
     */
    @Nullable
    private ReversedSegmentCache exportReversedCache;

    /**
     * The cached baked-reversed file URI for a PING_PONG clip's current trim range, or null if not
     * cached / span too long. Pure lookup — mirrors the preview resolver so preview==export.
     */
    @Nullable
    private android.net.Uri resolveReversedFileUri(@NonNull Clip clip) {
        if (clip.getLoopMode() != Clip.LOOP_MODE_PING_PONG || clip.isImageClip()) return null;
        if (exportReversedCache == null) exportReversedCache = new ReversedSegmentCache(context);
        long in = clip.getInPointMs();
        long out = clip.getOutPointMs();
        if (!ReversedSegmentCache.canBake(in, out)) return null;
        if (exportReversedCache.isCached(clip.getSourceUri(), in, out)) {
            return android.net.Uri.fromFile(
                    exportReversedCache.fileFor(clip.getSourceUri(), in, out));
        }
        return null;
    }

    /**
     * Resolve a clip's source to a SEEKABLE URI for the MediaItem builders.
     *
     * <p>Raw FadCam recordings are fragmented MP4s that are NOT seekable to a
     * non-zero start, so a {@link MediaItem.ClippingConfiguration} with a non-zero
     * {@code startPositionMs} fails with "Illegal clipping: not seekable to start".
     * If a cached remuxed (faststart) copy already exists for this source, point at
     * it instead. This is a PURE LOOKUP — it never triggers a (blocking) remux. The
     * cache is warmed off the main thread by {@link ExportService} before export.</p>
     *
     * <p>Returns the original URI unchanged for image clips, non-{@code file://}
     * sources, or when no cached remux exists (the common imported/remuxed case).</p>
     */
    /** Lazily-created pre-trim cache (lookup-only from this class; see the field's doc). */
    @Nullable
    private PreTrimCache exportPreTrimmer = null;

    /**
     * An AUDIO clip's source, seekable: a detached recording's sound is the raw fragmented
     * file, and an audio clip that starts mid-file ("Illegal clipping: not seekable to start",
     * Note 9 ZA_CONTROL, 2026-09-24) needs the remuxed copy the warm phase makes, exactly as a
     * video clip does. Non-file and non-fragmented sources come back unchanged.
     */
    @Nullable
    private android.net.Uri seekableUriFor(@Nullable android.net.Uri uri) {
        if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) return uri;
        try {
            java.io.File f = new java.io.File(uri.getPath());
            if (exportRemuxer == null) exportRemuxer = new FragmentedMp4Remuxer(context);
            if (exportRemuxer.needsRemux(f) && exportRemuxer.hasRemuxedVersion(f)) {
                java.io.File r = exportRemuxer.getRemuxedFile(f);
                if (r != null && r.exists()) return android.net.Uri.fromFile(r);
            }
        } catch (Exception e) {
            FLog.w(TAG, "seekableUriFor: using the original " + uri, e);
        }
        return uri;
    }

    @Nullable
    private android.net.Uri resolveSeekableSourceUri(@NonNull Clip clip) {
        android.net.Uri uri = clip.getSourceUri();
        if (clip.isImageClip() || uri == null
                || !"file".equals(uri.getScheme()) || uri.getPath() == null) {
            return uri;
        }
        try {
            java.io.File f = new java.io.File(uri.getPath());
            if (exportRemuxer == null) exportRemuxer = new FragmentedMp4Remuxer(context);
            java.io.File input = f;
            boolean remuxed = false;
            if (exportRemuxer.needsRemux(f) && exportRemuxer.hasRemuxedVersion(f)) {
                java.io.File remuxedFile = exportRemuxer.getRemuxedFile(f);
                if (remuxedFile != null && remuxedFile.exists()) {
                    input = remuxedFile;
                    remuxed = true;
                }
            }
            // 2026-09-22 pre-trim: a baked window for THIS exact [in, out] beats a deep
            // seek into the big file. Lookup only — the warm phase bakes. Same timestamps
            // (padded superset), so every caller below is unaffected either way.
            if (exportPreTrimmer == null) exportPreTrimmer = new PreTrimCache(context);
            if (exportPreTrimmer.hasPreTrim(input, clip.getInPointMs(), clip.getOutPointMs())) {
                java.io.File trim = exportPreTrimmer.getPreTrimFile(input,
                        clip.getInPointMs(), clip.getOutPointMs());
                if (trim.exists()) return android.net.Uri.fromFile(trim);
            }
            if (remuxed) return android.net.Uri.fromFile(input);
        } catch (Exception e) {
            FLog.w(TAG, "resolveSeekableSourceUri failed", e);
        }
        return uri;
    }

    /**
     * FIX-3: a clip window the pre-flight probe could not get a decoded frame from.
     * Null from {@link #probeClipWindows} means every window produced a frame.
     */
    public static final class WindowProbeFailure {
        public final int clipIndex;
        public final long inMs;
        public final long outMs;
        public final String uri;
        public final String codecName;
        public final long costMs;
        public final String reason;
        WindowProbeFailure(int clipIndex, long inMs, long outMs, String uri,
                           String codecName, long costMs, String reason) {
            this.clipIndex = clipIndex;
            this.inMs = inMs;
            this.outMs = outMs;
            this.uri = uri;
            this.codecName = codecName;
            this.costMs = costMs;
            this.reason = reason;
        }
    }

    /** Per-window decode budget for the pre-flight probe (ms). */
    private static final long PROBE_WINDOW_BUDGET_MS = 30_000L;

    /**
     * FIX-3: cold-baseline pre-flight probe. For every non-image {@code file://} spine
     * window, open the RESOLVED export URI (remuxed copy when one is in force — the same
     * file the export will read), seek to the window start, and decode until the first
     * output frame. Logs one {@code PROBE} line per window (clip, source position,
     * remux yes/no, decoder name, cost) whether it passes or not.
     *
     * <p>Why decode and not just extract: the 30:35 stall is "no DECODED output in
     * 120 s" with a healthy container, so an extractor-only probe would pass it and hand
     * the user false reassurance. Typical cost is ~1–3 s per window on hardware decode
     * (it also warms the page cache for the export itself).
     *
     * <p>Runs on the caller's thread — ExportService calls it on the warm background
     * thread, never the main thread. Creates and releases its own decoder per window.
     *
     * @return the first window that produced no frame within budget, or null when all pass.
     */
    @Nullable
    public WindowProbeFailure probeClipWindows(@NonNull FaditorProject project) {
        if (project.getTimeline() == null) return null;
        WindowProbeFailure firstFailure = null;
        int n = project.getTimeline().getClipCount();
        for (int ci = 0; ci < n; ci++) {
            Clip clip = project.getTimeline().getClip(ci);
            if (clip.isImageClip()) continue;
            android.net.Uri uri = clip.getSourceUri();
            if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) {
                trace("PROBE clip[" + ci + "] SKIP (non-file source "
                        + (uri != null ? uri.getScheme() : "null") + ")");
                continue;
            }
            long inMs = clip.getInPointMs();
            long outMs = clip.getOutPointMs();
            if (outMs <= inMs) {
                trace("PROBE clip[" + ci + "] SKIP (degenerate window)");
                continue;
            }
            android.net.Uri resolved = resolveSeekableSourceUri(clip);
            String path = resolved.getPath();
            boolean isRemux = resolved.toString().contains("-remuxed-");
            long t0 = android.os.SystemClock.elapsedRealtime();
            String codecName = "?";
            String failReason = null;
            long firstPtsUs = -1;
            android.media.MediaExtractor ex = null;
            android.media.MediaCodec codec = null;
            android.view.Surface surface = null;
            android.graphics.SurfaceTexture surfaceTexture = null;
            try {
                ex = new android.media.MediaExtractor();
                ex.setDataSource(path);
                int videoTrack = -1;
                android.media.MediaFormat format = null;
                for (int t = 0; t < ex.getTrackCount(); t++) {
                    android.media.MediaFormat f = ex.getTrackFormat(t);
                    String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        videoTrack = t;
                        format = f;
                        break;
                    }
                }
                if (videoTrack < 0 || format == null) {
                    failReason = "no video track in " + new java.io.File(path).getName();
                } else {
                    String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                    // 2026-09-22: video-ends-early warning (non-fatal). A window running
                    // past its source's last video frame (clip 0: picture ends 4.75s,
                    // clip runs to 5.226s) freezes the tail in preview AND export — no
                    // overlay timed over the dead region can ever look right. The export
                    // keeps today's hold-last-frame behavior; this line tells the owner
                    // which clip to trim instead of failing a 2-hour run over it.
                    try {
                        if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                            long videoDurMs = format.getLong(
                                    android.media.MediaFormat.KEY_DURATION) / 1000L;
                            // 2026-09-22 fix: videoDur is FILE-relative (a pre-trim starts
                            // at padStart, not 0) — the old absolute comparison cried wolf
                            // on all 20 windows. Same needMs rule as the trim validation,
                            // with the same 10 s pad rule the baker uses.
                            long padStartMs = 0L;
                            if (resolved.toString().contains("-v2")) {
                                padStartMs = Math.max(0L, inMs - 10_000L);
                            }
                            long needMs = outMs - padStartMs - 250;
                            if (videoDurMs > 0 && videoDurMs < needMs) {
                                trace("PROBE clip[" + ci + "] WARN video-ends-early videoDur="
                                        + videoDurMs + "ms windowEnd=" + outMs + "ms src="
                                        + new java.io.File(path).getName()
                                        + " (tail freezes; trim the clip or move overlays)");
                            }
                        }
                    } catch (Exception ignored) {}
                    ex.selectTrack(videoTrack);
                    ex.seekTo(inMs * 1000L,
                            android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    codec = android.media.MediaCodec.createDecoderByType(mime);
                    codecName = codec.getName();
                    // Headless output: a detached SurfaceTexture sinks frames with no GL.
                    surfaceTexture = new android.graphics.SurfaceTexture(0);
                    surface = new android.view.Surface(surfaceTexture);
                    codec.configure(format, surface, null, 0);
                    codec.start();
                    long deadline = t0 + PROBE_WINDOW_BUDGET_MS;
                    boolean inputEos = false;
                    boolean gotFrame = false;
                    android.media.MediaCodec.BufferInfo info =
                            new android.media.MediaCodec.BufferInfo();
                    while (!gotFrame && android.os.SystemClock.elapsedRealtime() < deadline) {
                        if (!inputEos) {
                            int inIdx = codec.dequeueInputBuffer(10_000);
                            if (inIdx >= 0) {
                                java.nio.ByteBuffer buf = codec.getInputBuffer(inIdx);
                                int sampleSize = (buf == null) ? -1
                                        : ex.readSampleData(buf, 0);
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0,
                                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    inputEos = true;
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, sampleSize,
                                            ex.getSampleTime(), ex.getSampleFlags());
                                    ex.advance();
                                }
                            }
                        }
                        int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                        if (outIdx >= 0) {
                            boolean isConfig = (info.flags
                                    & android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                            boolean isEos = (info.flags
                                    & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                            if (!isConfig && info.size > 0) {
                                firstPtsUs = info.presentationTimeUs;
                                gotFrame = true;
                            }
                            try {
                                codec.releaseOutputBuffer(outIdx, false);
                            } catch (Exception ignored) {}
                            if (isEos) break;
                        }
                    }
                    if (!gotFrame) {
                        failReason = inputEos
                                ? "end of stream before any frame (truncated source?)"
                                : "no decoded frame within " + (PROBE_WINDOW_BUDGET_MS / 1000)
                                + "s (decoder stall at this seam)";
                    }
                }
            } catch (Exception e) {
                failReason = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                if (codec != null) {
                    try { codec.stop(); } catch (Exception ignored) {}
                    try { codec.release(); } catch (Exception ignored) {}
                }
                if (surface != null) {
                    try { surface.release(); } catch (Exception ignored) {}
                }
                if (surfaceTexture != null) {
                    try { surfaceTexture.release(); } catch (Exception ignored) {}
                }
                if (ex != null) {
                    try { ex.release(); } catch (Exception ignored) {}
                }
            }
            long costMs = android.os.SystemClock.elapsedRealtime() - t0;
            String shortName = new java.io.File(path).getName();
            if (failReason == null) {
                trace("PROBE clip[" + ci + "] ok in=" + inMs + "ms pts=" + firstPtsUs
                        + "us remux=" + (isRemux ? "yes" : "no") + " codec=" + codecName
                        + " costMs=" + costMs + " src=" + shortName);
            } else {
                String failLine = "PROBE clip[" + ci + "] FAIL in=" + inMs + "ms out=" + outMs + "ms"
                        + " remux=" + (isRemux ? "yes" : "no") + " codec=" + codecName
                        + " costMs=" + costMs + " src=" + shortName + " reason=" + failReason;
                FLog.w(TAG, failLine);
                trace(failLine);
                if (firstFailure == null) {
                    firstFailure = new WindowProbeFailure(ci, inMs, outMs,
                            resolved.toString(), codecName, costMs, failReason);
                }
            }
        }
        return firstFailure;
    }

    /**
     * Callback interface for export progress and completion events.
     */
    public interface ExportListener {
        void onExportStarted(@NonNull String outputPath);
        void onExportProgress(float progress);
        /**
         * FIX-6: rich progress. itemIndex is approximate (derived from the composition
         * map against Media3's own curve — never present it without ≈), bytesWritten is
         * exact (staging file size), etaRemainingMs is pace-measured (composition time
         * per wall second), -1 when unknowable.
         */
        void onExportProgressDetailed(float progress, int itemIndex, int itemCount,
                                      long bytesWritten, long etaRemainingMs);
        /** FIX-6: the muxer is done; the loudness/SAF finalize (minutes on GB files) starts. */
        void onExportFinalizing();
        /**
         * Chunked export (2026-09-22): human phase label for long runs ("Part 2 of 6",
         * "Sound", "Joining"). Only implementer is ExportService.
         */
        void onChunkPhase(@NonNull String phase);
        void onExportCompleted(@NonNull String outputPath, @NonNull ExportResult result);
        void onExportError(@NonNull Exception error);
    }

    public ExportManager(@NonNull Context context,
                          @NonNull SharedPreferencesManager prefsManager) {
        this.context = context.getApplicationContext();
        this.prefsManager = prefsManager;
    }

    /**
     * Acquires a {@link MediaMetadataRetriever} for the current thread, creating it lazily.
     * The caller must call {@link #releasePerThreadRetriever()} on the same thread when done.
     */
    @NonNull
    private android.media.MediaMetadataRetriever acquireRetriever() {
        android.media.MediaMetadataRetriever r = retrieverPool.get();
        if (r == null) {
            r = new android.media.MediaMetadataRetriever();
            retrieverPool.set(r);
            retrieverCurrentUri.set(null);
        }
        return r;
    }

    /**
     * Points the thread-local retriever at {@code uri}, reusing the existing instance when
     * possible. Must be called on the same thread that called {@link #acquireRetriever()}.
     */
    private void setRetrieverDataSource(@NonNull android.net.Uri uri) {
        android.media.MediaMetadataRetriever r = acquireRetriever();
        String uriString = uri.toString();
        if (!uriString.equals(retrieverCurrentUri.get())) {
            r.setDataSource(context, uri);
            retrieverCurrentUri.set(uriString);
        }
    }

    /**
     * Releases the retriever owned by the current thread and clears the thread-local cache.
     * Safe to call even if no retriever was acquired on this thread.
     */
    private void releasePerThreadRetriever() {
        android.media.MediaMetadataRetriever r = retrieverPool.get();
        if (r != null) {
            try { r.release(); } catch (Exception ignored) {}
            retrieverPool.remove();
            retrieverCurrentUri.remove();
        }
    }

    public void setExportListener(@Nullable ExportListener listener) {
        this.listener = listener;
    }

    /** The listener set by {@link #setExportListener}, or null (used by ExportService's dispatch). */
    @Nullable
    public ExportListener getExportListener() {
        return listener;
    }

    public boolean isExporting() {
        return isExporting;
    }

    /**
     * Export the project timeline to a video file.
     *
     * @param project the project to export
     */
    public void export(@NonNull FaditorProject project) {
        if (isExporting) {
            FLog.w(TAG, "Export already in progress");
            return;
        }

        if (project.getTimeline().isEmpty()) {
            FLog.e(TAG, "Cannot export empty timeline");
            if (listener != null) {
                listener.onExportError(new IllegalStateException("Timeline is empty"));
            }
            return;
        }

        // G5: attached visualizers derive their windows from their host clips' current
        // spans — resync before any overlay slot is built so export matches preview.
        project.getTimeline().resyncAttachedVisualizers();
        // G9: host/rider link groups re-derive rider times too (same one-write-point rule).
        project.getTimeline().resyncLinkGroups();

        // Chunked driver for long timelines: bounded fresh-pipeline sessions per chunk,
        // resume via manifest, exact progress. Short timelines take the legacy path
        // unchanged (byte-identical output).
        if (project.getTimeline().getTotalDurationMs() >= CHUNKED_THRESHOLD_MS) {
            exportChunked(project, generateOutputPath(project));
            return;
        }

        String outputPath = generateOutputPath(project);
        // FIX-4: staging path hoisted so the catch below can discard it too.
        final String stagingPath = stagingPathFor(outputPath);
        currentStagingPath = stagingPath;
        isExporting = true;
        resetChunkState();
        lastLoggedProgressPct = -1;
        progressEpochMs = 0L;
        paceBaseCompMs = -1L;
        lastSeamItem = -1;
        // C7 snapshot: one deterministic read at export start (see the field's doc).
        fxBypassedSnapshot = com.fadcam.ui.faditor.tools.AudioDrawerTabs.fxChainBypassed;
        FLog.d(TAG, "C1.E FX chain: bypassed=" + fxBypassedSnapshot);
        cleanAudioSnapshot = project.getExportSettings().isCleanAudio();
        FLog.d(TAG, "C1.E voice chain applied=" + cleanAudioSnapshot + " (Clean Audio)");

        try {
            // Build the Transformer (base settings shared with the single-frame path —
            // ONE configuration authority so the frame export encodes identically).
            Transformer.Builder builder = baseVideoTransformerBuilder(project);

            // For simple trim (single clip, no effects, normal speed, audio intact,
            // and no audio clips on the audio track) use near-lossless
            // optimization. The fast-trim path bypasses the effects chain, so we
            // must exclude any project that has overlays — otherwise text /
            // captions / waveforms would be silently dropped.
            ExportSettings exportSettings = project.getExportSettings();
            boolean qualityIsDefault = exportSettings == null
                    || exportSettings.getQuality() == ExportSettings.Quality.HIGH;
            boolean resolutionIsDefault = exportSettings == null
                    || exportSettings.getResolution() == ExportSettings.Resolution.ORIGINAL;
            boolean isSimpleTrim = project.getTimeline().getClipCount() == 1
                    && !project.getTimeline().hasAudioClips()
                    && !project.getTimeline().getClip(0).isImageClip()
                    && project.getTimeline().getClip(0).getSpeedMultiplier() == 1.0f
                    && !project.getTimeline().getClip(0).isAudioMuted()
                    && Math.abs(project.getTimeline().getClip(0).getVolumeLevel() - 1.0f) < 0.01f
                    && project.getTimeline().getClip(0).getRotationDegrees() == 0
                    && !project.getTimeline().getClip(0).isFlipHorizontal()
                    && !project.getTimeline().getClip(0).isFlipVertical()
                    && "none".equals(project.getTimeline().getClip(0).getCropPreset())
                    && !project.getTimeline().getClip(0).hasOpacityKeyframes()
                    // ADVERSARIAL FIX 4: a master spine fade is applied by OpacityExportEffect,
                    // which the near-lossless trim path skips entirely. Listed here beside its
                    // sibling clip-level opacity test rather than inside
                    // usesLayerFeaturesAffectingExport, which is about LAYER features — a master
                    // fade is a property of the spine clip itself. (Media3 may re-encode anyway
                    // in practice; that is library behaviour, not a guard.)
                    && !project.getTimeline().getClip(0).hasMasterFade()
                    // SPINE_TRANSFORM: same reasoning, same shelf. The canvas placement is a
                    // GlEffect and the near-lossless path bypasses the effects chain, so a
                    // single-clip project that had merely been nudged left would have exported
                    // untouched and silently centred. Costs nothing for the projects the fast
                    // path is actually for: hasSpineTransform() is false unless a pose was
                    // authored.
                    && !project.getTimeline().getClip(0).hasSpineTransform()
                    && !project.getTimeline().hasTextOverlays()
                    && !hasAnyVisibleCaptionBinding(project.getTimeline().getClip(0))
                    && !project.getTimeline().hasWaveformOverlays()
                    && !project.getTimeline().getClip(0).hasLoopExtension()
                    && "original".equals(project.getCanvasPreset())
                    // M-EXPORT-1 (PLAN §5.3(2)): the near-lossless fast path bypasses the
                    // effects chain entirely, so it must be excluded whenever ANY layer
                    // feature is present — layers take the full re-encode path, always.
                    && !usesLayerFeaturesAffectingExport(project.getTimeline())
                    // A non-default resolution/quality choice requires a re-encode: the
                    // near-lossless path passes source samples through untouched.
                    && qualityIsDefault && resolutionIsDefault;

            if (isSimpleTrim) {
                builder.experimentalSetTrimOptimizationEnabled(true);
                FLog.d(TAG, "Using near-lossless trim optimization");
            } else {
                FLog.d(TAG, "Using full re-encode path (effects, overlays, or canvas transform present)");
            }

            // FIX-4: the muxer writes to the hoisted staging path; the final name
            // appears only via atomic rename in onCompleted. onExportStarted still
            // announces the FINAL path (the name the user picked) — it just doesn't
            // exist on disk yet.
            // Add progress listener
            builder.addListener(new Transformer.Listener() {
                @Override
                public void onCompleted(@NonNull Composition composition,
                                        @NonNull ExportResult result) {
                    stopProgressPolling();
                    isExporting = false;
                    String committed = commitStaging(stagingPath, outputPath);
                    finalizeExportAsync(project, committed, result, "Export completed");
                }

                @Override
                public void onError(@NonNull Composition composition,
                                    @NonNull ExportResult result,
                                    @NonNull ExportException exception) {
                    stopProgressPolling();
                    isExporting = false;
                    pendingSafCopy = false;
                    safExportFileName = null;
                    // FIX-4: a failed export leaves no file behind — not even in SAF-temp
                    // mode (that path is staging too now).
                    discardStaging(stagingPath);
                    FLog.e(TAG, "Export failed", exception);
                    writeExportErrorLog(project, exception, outputPath);
                    if (listener != null) {
                        listener.onExportError(exception);
                    }
                }
            });

            transformer = builder.build();

            // Build Composition from timeline clips
            Composition composition;
            try {
                composition = buildComposition(project);
            } finally {
                // Release the thread-local MediaMetadataRetriever used during composition
                // building (source dimension probes + still-frame extraction). Each export
                // call runs on the calling thread, so releasing here is safe and prevents
                // leaking the native retriever across exports.
                releasePerThreadRetriever();
            }

            // Start export — into staging (FIX-4); the final name is committed in
            // onCompleted and announced below (it does not exist on disk yet).
            transformer.start(composition, stagingPath);

            // Begin polling for progress (Transformer doesn't push progress via Listener)
            startProgressPolling();

            FLog.d(TAG, "Export started → " + outputPath + " (staging " + stagingPath + ")");
            if (listener != null) {
                listener.onExportStarted(outputPath);
            }

        } catch (Exception e) {
            isExporting = false;
            discardStaging(stagingPath);
            FLog.e(TAG, "Failed to start export", e);
            writeExportErrorLog(project, e, generateOutputPath(project));
            if (listener != null) {
                listener.onExportError(e);
            }
        }
    }

    /**
     * The base Transformer configuration shared by the video export and the single-frame
     * export — ONE configuration authority so a frame export encodes through the same
     * codecs, encoder settings and portrait rule a video export uses.
     *
     * <p>Extracted verbatim from {@link #export(FaditorProject)} (2026-09-05, SPEC_C):
     * the settings, their values and their order are unchanged.</p>
     */
    @NonNull
    private Transformer.Builder baseVideoTransformerBuilder(@NonNull FaditorProject project) {
        // SPEC A lane, 2026-09-05: this method arrived mid-refactor with `builder` referenced
        // but never declared (the old `return new Transformer.Builder(...)` chain and the
        // encoder-factory tail could not both hold). Completed MECHANICALLY — same builder,
        // same settings, same order; no behaviour changed.
        Transformer.Builder builder = new Transformer.Builder(context)
                .setAssetLoaderFactory(hardwareFirstAssetLoaderFactory())
                // TEN SECONDS IS NOT ENOUGH ON AN OLD PHONE. media3 kills an export when
                // the muxer goes DEFAULT_MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS (10_000 on a
                // real device) without receiving a sample, and reports it as the bare
                // "Muxer error" that JoyRaptor hit on 2026-08-26 after a long wait — the
                // structured record caught the watchdog frames in the stack:
                // Transformer.maybeInitializeExportWatchdogTimer -> WatchdogTimer.onTimeout.
                //
                // His project is 11 clips drawn from six distinct sources, with PiP
                // compositing, text overlays and an 18-second still at the end, on a Note 9
                // (API 29, ~2 hardware decoders). A single hard segment there can easily
                // out-wait ten seconds without emitting one sample, and the export dies
                // having done all the work up to that point.
                //
                // This raises the ceiling; it does NOT make export faster, and the slowness
                // is worth attacking separately. A watchdog exists to catch a genuine hang,
                // and a hang still trips this one — it just no longer mistakes a slow device
                // for a broken one.
                //
                // 2026-09-22: 120 s proved too short. The 48-minute project stalls at the
                // clip18→clip19 seam (source 31:13) on a HOT phone while the same window
                // decodes in seconds cold (pre-flight probe passes) — a slow seam, not a
                // stuck one. 300 s lets a throttled 2–4 min seam through and still aborts a
                // true hang with 10x margin over anything healthy.
                .setMaxDelayBetweenMuxerSamplesMs(300_000L)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                // Encode portrait output NATIVELY (coded WxH portrait, rotation=0)
                // instead of Media3's default "landscape + rotation flag"
                // optimization. That optimization made 9:16 exports come out as
                // 1546x870 with a rotate(-90) flag — correct on players that honor
                // the flag, but sideways / wrongly-sized on those that ignore it
                // (some social/web players). Forcing portrait encoding bakes the
                // pixels upright so the file is correct everywhere.
                .setPortraitEncodingEnabled(true);
        // ── User export settings (resolution cap + quality/bitrate) ──
        // Defaults (ORIGINAL + HIGH) leave this entire path byte-identical to
        // before: no encoder factory is set and no resolution cap applies.
        DefaultEncoderFactory encoderFactory = qualityEncoderFactoryOrNull(project);
        if (encoderFactory != null) {
            builder.setEncoderFactory(encoderFactory);
        }
        return builder;
    }

    /**
     * The encoder factory honouring the project's export quality (bitrate request), or
     * null to leave media3 at its default — exactly the behaviour export() had inline.
     */
    @Nullable
    private DefaultEncoderFactory qualityEncoderFactoryOrNull(@NonNull FaditorProject project) {
        ExportSettings exportSettings = project.getExportSettings();
        boolean qualityIsDefault = exportSettings == null
                || exportSettings.getQuality() == ExportSettings.Quality.HIGH;
        // Always a factory now: the sound bitrate (stereo, 256 kbps) applies at every quality.
        // Video stays at media3's own default when the quality is the default.
        if (qualityIsDefault) return exportEncoderFactory(project, null);
        int bitrate = suggestedExportBitrate(project);
        if (bitrate <= 0) return exportEncoderFactory(project, null);
        VideoEncoderSettings.Builder encoderSettings =
                new VideoEncoderSettings.Builder().setBitrate(bitrate);
        // Optional H.264 Baseline-profile request for max-compatibility / low-bandwidth
        // exports. Default OFF → the requested profile stays NO_VALUE and this whole
        // path is byte-identical to before. When enabled we pass Baseline with a
        // NO_VALUE level on purpose: the patched DefaultEncoderFactory reads the
        // original requested profile and auto-derives a supported level itself (see the
        // "FadCam patch" comments in adjustMediaFormatForH264EncoderSettings), so we
        // never have to pick a level here.
        if (REQUEST_BASELINE_PROFILE) {
            encoderSettings.setEncodingProfileLevel(
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                    VideoEncoderSettings.NO_VALUE);
        }
        FLog.d(TAG, "Export quality " + exportSettings.getQuality()
                + " → requested video bitrate " + bitrate
                + (REQUEST_BASELINE_PROFILE ? " (H.264 Baseline)" : ""));
        return exportEncoderFactory(project, encoderSettings.build());
    }

    // ── Single-frame image export (SPEC_C) ─────────────────────────────

    /**
     * Export ONE composed frame of the project as a PNG (lossless) or JPG image, at the
     * project's export resolution, with every overlay the video export would draw.
     *
     * <p>HOW IT COMPOSES: through the video export's own pipeline — no second compositor.
     * {@link #buildComposition} runs twice (see {@link FrameExportDirective}): pass 1
     * records which EditedMediaItem covers the requested editor time; pass 2 emits only
     * that item, padded to its full-export composition start and end-clamped just past
     * the frame. The frame is then pulled from the encoded temp video with
     * MediaMetadataRetriever. The image therefore differs from the same frame of a video
     * export by nothing except H.264 encode-generation rounding (the frame has passed
     * through one H.264 encode/decode round trip, as every exported video frame does).</p>
     *
     * <p>Called from {@link ExportService} on the service thread; Transformer callbacks
     * arrive there and the finalization runs on its own thread (retriever + compress are
     * not free).</p>
     *
     * @param project     the project to render
     * @param frameTimeMs the requested frame on the EDITOR timeline (the playhead's clock)
     * @param jpeg        true → JPG (quality 90); false → PNG (lossless)
     * @param listener    progress/completion callbacks
     */
    public void exportSingleFrame(@NonNull FaditorProject project,
                                  long frameTimeMs,
                                  boolean jpeg,
                                  @NonNull ExportListener listener) {
        if (isExporting) {
            FLog.w(TAG, "Export already in progress");
            return;
        }
        if (project.getTimeline().isEmpty()) {
            listener.onExportError(new IllegalStateException("Timeline is empty"));
            return;
        }
        long totalMs = project.getTimeline().getTotalDurationMs();
        if (frameTimeMs < 0 || frameTimeMs >= totalMs) {
            // Backstop for the dialog's own validation: rejected, never clamped.
            listener.onExportError(new IllegalArgumentException(
                    "Frame time " + frameTimeMs + "ms is outside the project (0.."
                            + totalMs + "ms)"));
            return;
        }

        // Same pre-flight the video export does: attached visualizers and rider times are
        // derived from the current spans, and the C7 screen-state snapshots are read once.
        project.getTimeline().resyncAttachedVisualizers();
        project.getTimeline().resyncLinkGroups();
        fxBypassedSnapshot = com.fadcam.ui.faditor.tools.AudioDrawerTabs.fxChainBypassed;
        cleanAudioSnapshot = project.getExportSettings() != null
                && project.getExportSettings().isCleanAudio();

        isExporting = true;
        resetChunkState();
        lastLoggedProgressPct = -1;
        progressEpochMs = 0L;
        paceBaseCompMs = -1L;
        lastSeamItem = -1;
        try {
            // Pass 1 — record where every item sits on both clocks (no waveform/audio
            // work; items are built but nothing is rendered).
            FrameExportDirective plan = FrameExportDirective.framePlan(frameTimeMs);
            buildComposition(project, plan);
            int covering = plan.coveringOrdinal();
            long natural = plan.coveringNaturalCompMs();
            long leadFillerMs = plan.coveringLeadFillerMs();
            long localTargetMs = plan.coveringLocalTargetMs();
            long compTargetMs = leadFillerMs + localTargetMs;
            long clampLocalMs = Math.min(natural, localTargetMs + FRAME_CLAMP_LOOKAHEAD_MS);
            FLog.i(TAG, "single-frame: T=" + frameTimeMs + "ms → item#" + covering
                    + " compTarget=" + compTargetMs + "ms lead=" + leadFillerMs
                    + "ms natural=" + natural + "ms clamp=" + clampLocalMs + "ms");

            // Pass 2 — the truncated composition: [lead filler] + [clamped covering item].
            FrameExportDirective clamp = FrameExportDirective.frameClamp(
                    covering, leadFillerMs, clampLocalMs, natural);
            Composition composition;
            try {
                composition = buildComposition(project, clamp);
            } finally {
                releasePerThreadRetriever();
            }
            if (!clamp.wasCoveringEmitted()) {
                throw new IllegalStateException(
                        "single-frame export: covering item #" + covering
                                + " was not emitted by the clamp pass");
            }

            File tempDir = new File(context.getCacheDir(), "faditor_export");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            final File tempVideo = new File(tempDir,
                    "frame_" + System.currentTimeMillis() + ".mp4");

            Transformer.Builder builder = baseVideoTransformerBuilder(project);
            builder.addListener(new Transformer.Listener() {
                @Override
                public void onCompleted(@NonNull Composition c, @NonNull ExportResult result) {
                    stopProgressPolling();
                    new Thread(() -> finishSingleFrame(project, tempVideo, compTargetMs,
                            jpeg, result, listener), "faditor-frame-finalize").start();
                }

                @Override
                public void onError(@NonNull Composition c, @NonNull ExportResult result,
                                    @NonNull ExportException exception) {
                    stopProgressPolling();
                    isExporting = false;
                    deleteQuietly(tempVideo);
                    FLog.e(TAG, "Single-frame export failed", exception);
                    writeExportErrorLog(project, exception, tempVideo.getPath());
                    listener.onExportError(exception);
                }
            });
            transformer = builder.build();
            transformer.start(composition, tempVideo.getPath());
            startProgressPolling();
            FLog.d(TAG, "Single-frame export started → " + tempVideo.getPath());
            listener.onExportStarted(tempVideo.getPath());
        } catch (Exception e) {
            isExporting = false;
            FLog.e(TAG, "Failed to start single-frame export", e);
            writeExportErrorLog(project, e, tempVideoPathForLog());
            listener.onExportError(e);
        }
    }

    @NonNull
    private String tempVideoPathForLog() {
        return new File(new File(context.getCacheDir(), "faditor_export"),
                "frame_failed.mp4").getPath();
    }

    /**
     * Pull the requested frame out of the encoded temp video and write the image where
     * video exports go (internal Faditor dir, or the SAF copy flow in custom-storage
     * mode). The temp video is ALWAYS deleted — it is an intermediate, not a product.
     */
    private void finishSingleFrame(@NonNull FaditorProject project,
                                   @NonNull File tempVideo,
                                   long targetCompMs,
                                   boolean jpeg,
                                   @NonNull ExportResult result,
                                   @NonNull ExportListener listener) {
        Bitmap frame = null;
        try {
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            try {
                mmr.setDataSource(tempVideo.getPath());
                String durStr = mmr.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durMs = -1L;
                if (durStr != null) {
                    try { durMs = Long.parseLong(durStr); } catch (NumberFormatException ignored) {}
                }
                long atMs = targetCompMs;
                if (durMs > 0) atMs = Math.min(atMs, Math.max(0L, durMs - 5L));
                frame = mmr.getFrameAtTime(atMs * 1000L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) {
                    // Sync fallback: better a neighbouring keyframe than nothing.
                    frame = mmr.getFrameAtTime(atMs * 1000L,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
            if (frame == null || frame.isRecycled()) {
                throw new IllegalStateException("frame extraction returned no bitmap at "
                        + targetCompMs + "ms of " + tempVideo.getName());
            }
            // Some devices decode video frames as RGB_565 — compressing THAT to PNG bakes
            // 16-bit colour banding into a "lossless" file. Normalize once, here.
            if (frame.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap argb = frame.copy(Bitmap.Config.ARGB_8888, false);
                frame.recycle();
                frame = argb;
            }

            // Same destination rule as a video export: generateOutputPath honours the
            // custom file name and the custom-storage (SAF) mode; copyTempToSaf below
            // performs the copy for that mode. No new location, no new permission flow.
            String finalPath = generateOutputPath(project, jpeg ? "jpg" : "png");
            File outFile = new File(finalPath);
            boolean compressed;
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {
                compressed = frame.compress(jpeg
                        ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG,
                        jpeg ? 90 : 100, out);
                out.flush();
            }
            frame.recycle();
            frame = null;
            if (!compressed) {
                throw new java.io.IOException("Bitmap.compress returned false for " + finalPath);
            }
            if (pendingSafCopy) {
                String safName = copyTempToSaf(finalPath);
                if (safName != null) {
                    finalPath = safName;
                    FLog.d(TAG, "Single-frame export (copied to SAF): " + safName);
                } else {
                    FLog.e(TAG, "SAF copy failed, image remains at: " + finalPath);
                }
                pendingSafCopy = false;
                safExportFileName = null;
            }
            isExporting = false;
            FLog.i(TAG, "Single-frame export completed: " + finalPath);
            listener.onExportCompleted(finalPath, result);
        } catch (Exception e) {
            isExporting = false;
            if (frame != null && !frame.isRecycled()) frame.recycle();
            FLog.e(TAG, "Single-frame finalize failed", e);
            writeExportErrorLog(project, e, tempVideo.getPath());
            listener.onExportError(e);
        } finally {
            deleteQuietly(tempVideo);
        }
    }

    private static void deleteQuietly(@Nullable File f) {
        if (f != null && f.exists() && !f.delete()) {
            FLog.w(TAG, "Could not delete temp file " + f.getPath());
        }
    }

    // ── Audio-only export (isolated additive path — the video export above is untouched) ──

    /**
     * Export ONLY the composed audio mix to an {@code .m4a} file (no video track). Every
     * timeline clip contributes its audio via {@code setRemoveVideo(true)} (mirroring its
     * speed / volume / mute); image, muted, and loop-extension segments contribute silence
     * of their duration so the audio stays time-aligned with the normal video export; the
     * separate {@link AudioClip} track is mixed in as a second sequence.
     *
     * <p><b>v1 limitations (device/ffmpeg verification owed):</b> loop-extension segments
     * export as silence (timing preserved, looped audio not repeated); a transition overlap
     * is treated as a continuation of the outgoing clip's audio (no audio crossfade).</p>
     */
    public void exportAudioOnly(@NonNull FaditorProject project) {
        exportAudioOnly(project, generateOutputPath(project, "m4a"), null);
    }

    /**
     * Terminal override for the chunked driver's intermediate audio pass: when set, the
     * audio file is handed to the driver instead of finalized + announced (the single
     * loudness pass runs once, on the final mux). Cleared when consumed. Null = legacy.
     */
    @Nullable
    private ExportListener audioTerminalOverride = null;

    /**
     * Audio-only export to an explicit path with an optional terminal override
     * (chunked driver). Progress/started/finalizing still flow to the field listener.
     */
    public void exportAudioOnly(@NonNull FaditorProject project, @NonNull String outputPath,
                                @Nullable ExportListener terminalOverride) {
        if (isExporting) {
            FLog.w(TAG, "Export already in progress");
            return;
        }
        audioTerminalOverride = terminalOverride;
        // EMPTY means NO master clips AND no audio lanes. An audio-first project (G21/B9:
        // blank video start + audio lane) has an empty MASTER SPINE by design — refusing it
        // here made audio-only export impossible for exactly the projects the feature was
        // built for ("Timeline is empty" on a timeline that holds an hour of audio).
        // buildAudioOnlyComposition already renders a valid short silence when literally
        // nothing is audible, so this guard only needs to catch the truly-empty case.
        if (project.getTimeline().isEmpty() && !project.getTimeline().hasAudioClips()) {
            if (listener != null) {
                listener.onExportError(new IllegalStateException("Timeline is empty"));
            }
            return;
        }

        // Match export(): re-derive attached-visualizer + link-group rider times first.
        project.getTimeline().resyncAttachedVisualizers();
        project.getTimeline().resyncLinkGroups();

        // FIX-4: same staging discipline as the video path (see export()).
        final String audioStagingPath = stagingPathFor(outputPath);
        currentStagingPath = audioStagingPath;
        isExporting = true;
        resetChunkState();
        lastLoggedProgressPct = -1;
        progressEpochMs = 0L;
        paceBaseCompMs = -1L;
        lastSeamItem = -1;
        // C7 snapshot: one deterministic read at export start (see the field's doc).
        fxBypassedSnapshot = com.fadcam.ui.faditor.tools.AudioDrawerTabs.fxChainBypassed;
        FLog.d(TAG, "C1.E FX chain (audio-only): bypassed=" + fxBypassedSnapshot);
        cleanAudioSnapshot = project.getExportSettings().isCleanAudio();
        FLog.d(TAG, "C1.E voice chain applied=" + cleanAudioSnapshot + " (Clean Audio)");

        try {
            Transformer.Builder builder = new Transformer.Builder(context)
                    .setAssetLoaderFactory(hardwareFirstAssetLoaderFactory())
                    // Same watchdog headroom as the video path above (300 s, 2026-09-22).
                    .setMaxDelayBetweenMuxerSamplesMs(300_000L)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    // Stereo 256 kbps — the chunked driver's ONE sound pass comes from here.
                    .setEncoderFactory(exportEncoderFactory(project, null));

            builder.addListener(new Transformer.Listener() {
                @Override
                public void onCompleted(@NonNull Composition composition,
                                        @NonNull ExportResult result) {
                    stopProgressPolling();
                    isExporting = false;
                    String committed = commitStaging(audioStagingPath, outputPath);
                    ExportListener term = audioTerminalOverride;
                    audioTerminalOverride = null;
                    if (term != null) {
                        // Chunked driver: intermediate audio, no finalize yet.
                        term.onExportCompleted(committed, result);
                    } else {
                        finalizeExportAsync(project, committed, result, "Audio-only export completed");
                    }
                }

                @Override
                public void onError(@NonNull Composition composition,
                                    @NonNull ExportResult result,
                                    @NonNull ExportException exception) {
                    stopProgressPolling();
                    isExporting = false;
                    pendingSafCopy = false;
                    safExportFileName = null;
                    discardStaging(audioStagingPath);
                    FLog.e(TAG, "Audio-only export failed", exception);
                    writeExportErrorLog(project, exception, outputPath);
                    ExportListener term = audioTerminalOverride;
                    audioTerminalOverride = null;
                    if (term != null) {
                        term.onExportError(exception);
                    } else if (listener != null) {
                        listener.onExportError(exception);
                    }
                }
            });

            transformer = builder.build();

            Composition composition;
            try {
                composition = buildAudioOnlyComposition(project);
            } finally {
                releasePerThreadRetriever();
            }

            if (sampleSound) soundSampler.excludeExisting();
            transformer.start(composition, audioStagingPath);
            startProgressPolling();

            FLog.d(TAG, "Audio-only export started → " + outputPath
                    + " (staging " + audioStagingPath + ")");
            if (listener != null) {
                listener.onExportStarted(outputPath);
            }
        } catch (Exception e) {
            isExporting = false;
            discardStaging(audioStagingPath);
            FLog.e(TAG, "Failed to start audio-only export", e);
            writeExportErrorLog(project, e, outputPath);
            ExportListener term = audioTerminalOverride;
            audioTerminalOverride = null;
            if (term != null) {
                term.onExportError(e);
            } else if (listener != null) {
                listener.onExportError(e);
            }
        }
    }

    // ── Chunked export driver (2026-09-22) ────────────────────────────────
    //
    // WHY: a 48-minute single-pass export decays 30x→0.14x realtime with a COOLING SoC
    // (12:17 trace) and wedges at the same seam four times — an accumulation the long
    // session grows (per-frame native allocation churn is the prime suspect; MEM lines
    // now watch it). Bounded sessions can't accumulate: each ≤~6 min chunk runs at
    // early-pipeline speed with a fresh decoder/encoder/muxer/GL, a failed chunk retries
    // alone, progress is linear, and completed chunks survive restarts (manifest).
    // One audio pass + ffmpeg concat+mux join the parts; joins never touch audio.
    //
    // CORRECTNESS NOTES (why the parts equal the whole):
    // - Overlay clocks are absolute: chunkBaseMs (exact, measured from built items, never
    //   estimated) rides every editorTimeOffsetFor site; clip-local effects keep the
    //   chunk-relative cursor. Cuts never straddle transitions or split a clip's span.
    // - Same builder/settings per chunk ⇒ identical codec params ⇒ clean -c copy concat.
    // - Short timelines (< 12 min) never enter: legacy path byte-identical.

    /** Timelines at/above this total take the chunked path. */
    private static final long CHUNKED_THRESHOLD_MS = 720_000L;
    /** Target composition length per chunk; cuts land on clip seams near it. */
    private static final long CHUNK_TARGET_MS = 300_000L;

    /** Visualizer data for this export, analysed once (see buildComposition). */
    @Nullable
    private volatile Map<String, WaveformData> sharedWaveformCache = null;
    /** True while builtRangeDurationMs builds a composition only to measure it. */
    private boolean measuringOnly = false;

    /** Absolute composition start of each part, measured once by {@link #planChunks}. */
    @Nullable
    private long[] chunkBases = null;
    /** Per part, from {@link #planChunks}: composition length, editor start and length. */
    @Nullable
    private long[] chunkCompDurs = null;
    @Nullable
    private long[] chunkEditorBases = null;
    @Nullable
    private long[] chunkEditorDurs = null;
    /** Set while the chunked driver owns the run (chain stops when cancelled). */
    private volatile boolean chunkCancelled = false;
    /** Last chunk's ExportResult, handed to the final finalize (mirrors single-pass). */
    @Nullable
    private ExportResult lastChunkResult = null;
    /** A worker writes into its driver's trace (prefixed) instead of owning a file. */
    @Nullable
    private ExportManager traceParent = null;
    @NonNull
    private String tracePrefix = "";
    /** Only one worker at a time samples the GL thread (the threads share a name). */
    private boolean sampleGl = true;
    /** The sound worker samples its own Transformer thread instead (SOUND_SAMPLE lines). */
    private boolean sampleSound = false;
    private final GlThreadSampler soundSampler =
            new GlThreadSampler(this::trace, "Transformer:Internal", "SOUND_SAMPLE");

    /** Progress weights: video chunks dominate; audio/join/finalize are quick. */
    private static final float CHUNK_VIDEO_FRAC = 0.85f;
    private static final float CHUNK_AUDIO_FRAC = 0.07f;

    /**
     * Cut the timeline into [startClip, endClip) ranges of ~CHUNK_TARGET_MS composition
     * (approximated by trimmed durations — sizes only, never correctness), never cutting
     * at a transition seam. A trailing runt merges into the previous chunk.
     */
    @NonNull
    private List<int[]> computeChunkRanges(@NonNull Timeline timeline) {
        List<int[]> ranges = new ArrayList<>();
        int n = timeline.getClipCount();
        int start = 0;
        long acc = 0L;
        for (int i = 0; i < n; i++) {
            long dur = Math.max(1L, timeline.getClip(i).getTrimmedDurationMs());
            boolean isLast = (i == n - 1);
            boolean atTarget = acc + dur >= CHUNK_TARGET_MS;
            // Never cut right after clip i if a transition straddles seam i→i+1.
            boolean seamClean = isLast || findTransitionAtSeam(timeline, i) == null;
            if (!isLast && !(atTarget && seamClean && i > start)) {
                acc += dur;
                continue;
            }
            acc += dur;
            ranges.add(new int[]{start, i + 1});
            start = i + 1;
            acc = 0L;
        }
        if (start < n) ranges.add(new int[]{start, n});
        // Merge a trailing runt (< 60 s and not the only chunk) into its predecessor.
        if (ranges.size() > 1) {
            int[] last = ranges.get(ranges.size() - 1);
            long lastDur = 0L;
            for (int i = last[0]; i < last[1]; i++) {
                lastDur += Math.max(1L, timeline.getClip(i).getTrimmedDurationMs());
            }
            if (lastDur < 60_000L) {
                int[] prev = ranges.get(ranges.size() - 2);
                ranges.set(ranges.size() - 2, new int[]{prev[0], last[1]});
                ranges.remove(ranges.size() - 1);
            }
        }
        if (ranges.isEmpty() && n > 0) ranges.add(new int[]{0, n});
        return ranges;
    }

    /** Manifest dir for this project's chunk resume state (created on demand). */
    @NonNull
    private File chunkDirFor(@NonNull FaditorProject project) {
        String id = project.getId() != null
                ? project.getId().replaceAll("[^A-Za-z0-9_-]", "_") : "noid";
        File dir = new File(new File(context.getFilesDir(), "faditor/chunks"), id);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * SMART RE-EXPORT (Stage 4, 2026-09-24). Every part is stored under a fingerprint of ITS OWN
     * content ({@link RenderCacheKeys}): its clips and whatever is on screen during it. A part
     * whose file already exists under its key is reused as-is, so after a small edit only the
     * part(s) that edit touches re-render, and the sound pass (keyed on sound-only data)
     * re-runs only for sound edits. The file name IS the "done" flag: a part is written to a
     * temporary name and renamed only once it has been checked. Kept after the export (see
     * {@link #pruneRenderCache}), so this also resumes a failed or cancelled export.
     *
     * <p>Also fills {@link #chunkBases}: each part's exact absolute start, measured once here
     * rather than re-measured before every part.</p>
     */
    @NonNull
    private org.json.JSONObject planChunks(@NonNull FaditorProject project,
                                           @NonNull List<int[]> ranges) {
        File dir = chunkDirFor(project);
        Timeline tl = project.getTimeline();
        String json = null;
        try {
            json = new com.fadcam.ui.faditor.project.ProjectStorage(context).toJson(project);
        } catch (Exception e) {
            FLog.w(TAG, "planChunks: project JSON unavailable; nothing will be reused", e);
        }
        String extras = renderCacheExtras();
        long total = tl.getTotalDurationMs();
        chunkBases = new long[ranges.size()];
        chunkCompDurs = new long[ranges.size()];
        chunkEditorBases = new long[ranges.size()];
        chunkEditorDurs = new long[ranges.size()];
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            org.json.JSONArray chunks = new org.json.JSONArray();
            long compBase = 0L;
            long editorBase = 0L;
            for (int i = 0; i < ranges.size(); i++) {
                int[] r = ranges.get(i);
                long compDur = builtRangeDurationMs(project, r[0], r[1]);
                long editorDur = 0L;
                for (int ci = r[0]; ci < r[1] && ci < tl.getClipCount(); ci++) {
                    editorDur += tl.getClip(ci).getVisualDurationMs();
                }
                chunkBases[i] = compBase;
                chunkCompDurs[i] = compDur;
                chunkEditorBases[i] = editorBase;
                chunkEditorDurs[i] = editorDur;
                long winStart = Math.min(compBase, editorBase);
                long winEnd = Math.max(compBase + compDur, editorBase + editorDur);
                String key = json == null ? null : RenderCacheKeys.partKey(json, r[0], r[1],
                        winStart, winEnd, total, i == ranges.size() - 1, extras);
                // No key (should not happen) = a one-off name: rendered, never reused.
                String name = key != null ? "part_" + key.substring(0, 24)
                        : "part_once_" + System.currentTimeMillis() + "_" + i;
                File f = new File(dir, name + ".mp4");
                org.json.JSONObject c = new org.json.JSONObject();
                c.put("index", i);
                c.put("startClip", r[0]);
                c.put("endClip", r[1]);
                c.put("key", key == null ? "" : key);
                c.put("file", f.getAbsolutePath());
                c.put("done", key != null && f.isFile());
                chunks.put(c);
                compBase += compDur;
                editorBase += editorDur;
            }
            o.put("chunks", chunks);
            String akey = json == null ? null : RenderCacheKeys.audioKey(json, extras
                    + "|fxBypass=" + fxBypassedSnapshot + "|clean=" + cleanAudioSnapshot);
            String aname = akey != null ? "audio_" + akey.substring(0, 24)
                    : "audio_once_" + System.currentTimeMillis();
            File af = new File(dir, aname + ".m4a");
            o.put("audioKey", akey == null ? "" : akey);
            o.put("audioFile", af.getAbsolutePath());
            o.put("audioDone", akey != null && af.isFile() && af.length() > 0);
            saveChunkManifest(project, o);   // a record of the plan, for traces; never read back
        } catch (Exception e) {
            FLog.w(TAG, "planChunks failed", e);
        }
        return o;
    }

    /**
     * Everything outside the project file that changes a part's pixels: the installed build
     * (any new build may draw differently, so an update re-renders everything once), the
     * custom caption styles, and the GPU routing switches.
     */
    @NonNull
    private String renderCacheExtras() {
        StringBuilder sb = new StringBuilder();
        try {
            android.content.pm.PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            sb.append("build=").append(pi.lastUpdateTime).append('/').append(pi.versionName);
        } catch (Exception e) {
            sb.append("build=").append(System.currentTimeMillis());   // unknown: never reuse
        }
        try {
            java.util.Map<String, ?> styles = context.getSharedPreferences("caption_styles",
                    Context.MODE_PRIVATE).getAll();
            sb.append("|styles=").append(new java.util.TreeMap<>(styles).toString().hashCode());
        } catch (Exception ignored) { }
        sb.append("|glImg=").append(GL_IMAGE_PASS).append("|glCap=").append(GL_CAPTION_PASS)
                .append("|glTxt=").append(GL_TEXT_PASS);
        return sb.toString();
    }

    /** Most bytes of kept parts across all projects; the oldest projects' parts go first. */
    private static final long RENDER_CACHE_MAX_BYTES = 6L * 1024 * 1024 * 1024;
    /** Never keep parts when that would leave the phone with less free space than this. */
    private static final long RENDER_CACHE_MIN_FREE_BYTES = 4L * 1024 * 1024 * 1024;

    /**
     * After a committed export: keep exactly the parts and sound this export used (the next
     * export of this project reuses them), delete everything else in the workspace (parts
     * from older versions of the project, temporary files), then hold every project's kept
     * parts under {@link #RENDER_CACHE_MAX_BYTES} and the phone's free space above
     * {@link #RENDER_CACHE_MIN_FREE_BYTES}, dropping least-recently-exported projects first.
     */
    private void pruneRenderCache(@NonNull FaditorProject project,
                                  @NonNull org.json.JSONObject manifest) {
        try {
            File dir = chunkDirFor(project);
            java.util.Set<String> keep = new java.util.HashSet<>();
            org.json.JSONArray chunks = manifest.optJSONArray("chunks");
            if (chunks != null) {
                for (int i = 0; i < chunks.length(); i++) {
                    org.json.JSONObject c = chunks.optJSONObject(i);
                    if (c != null && !c.optString("key", "").isEmpty()) {
                        keep.add(new File(c.optString("file")).getName());
                    }
                }
            }
            if (!manifest.optString("audioKey", "").isEmpty()) {
                keep.add(new File(manifest.optString("audioFile")).getName());
            }
            keep.add("manifest.json");
            long freed = 0L;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (keep.contains(f.getName())) continue;
                    long len = f.length();
                    if (f.delete()) freed += len;
                }
            }
            //noinspection ResultOfMethodCallIgnored
            dir.setLastModified(System.currentTimeMillis());
            // Across projects, oldest export first; this project last of all.
            File root = dir.getParentFile();
            File[] projects = root == null ? null : root.listFiles(File::isDirectory);
            long total = 0L;
            java.util.List<File> order = new java.util.ArrayList<>();
            if (projects != null) {
                for (File pd : projects) {
                    total += dirBytes(pd);
                    if (!pd.equals(dir)) order.add(pd);
                }
            }
            order.sort((x, y) -> Long.compare(x.lastModified(), y.lastModified()));
            order.add(dir);
            for (File pd : order) {
                if (total <= RENDER_CACHE_MAX_BYTES
                        && dir.getUsableSpace() >= RENDER_CACHE_MIN_FREE_BYTES) break;
                long bytes = dirBytes(pd);
                File[] fs = pd.listFiles();
                if (fs != null) for (File f : fs) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                pd.delete();
                total -= bytes;
                freed += bytes;
                trace("RENDER_CACHE evicted " + pd.getName() + " (" + (bytes >> 20) + " MB)");
            }
            trace("RENDER_CACHE kept " + (dir.exists() ? (dirBytes(dir) >> 20) : 0)
                    + " MB for this project, freed " + (freed >> 20) + " MB, all projects "
                    + (Math.max(0L, total) >> 20) + " MB");
        } catch (Exception e) {
            FLog.w(TAG, "Render cache prune failed", e);
        }
    }

    private static long dirBytes(@NonNull File dir) {
        long sum = 0L;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) sum += f.length();
        return sum;
    }

    private void saveChunkManifest(@NonNull FaditorProject project,
                                   @NonNull org.json.JSONObject manifest) {
        try {
            File mf = new File(chunkDirFor(project), "manifest.json");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(mf)) {
                fos.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            FLog.w(TAG, "Chunk manifest save failed", e);
        }
    }

    /** A manifest-marked chunk file is reusable when it exists with sane duration. */
    private boolean chunkFileValid(@NonNull String path, long expectedMs) {
        try {
            File f = new File(path);
            if (!f.exists() || f.length() <= 0) return false;
            long dur = PreTrimCache.probeVideoDurationMs(f);
            if (dur <= 0) return false;
            return Math.abs(dur - expectedMs) <= Math.max(20_000L, expectedMs / 10);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Entry point for long timelines (called from export()). Video chunks (fresh
     * pipeline each) → single audio pass → ffmpeg concat+mux → shared finalize.
     * Completed chunks survive failures/restarts via the manifest (resume, not redo).
     */
    public void exportChunked(@NonNull FaditorProject project,
                              @NonNull String finalOutputPath) {
        startChunked(project, finalOutputPath, -1L, -1L);
    }

    /**
     * RANGE EXPORT (Stage 4b, 2026-09-24): editor times [startMs, endMs) as their own file.
     * Runs the chunked driver over the whole project's parts but renders only the parts the
     * range touches (each still reused from the render cache when unchanged), joins those,
     * and trims the join to the exact range with Media3's trim optimization, which
     * re-encodes only up to the first keyframe and copies everything after it. The sound is
     * the project's cached sound pass, cut to the same span. Works for any project length.
     */
    public void exportRange(@NonNull FaditorProject project, long startMs, long endMs) {
        long total = project.getTimeline().getTotalDurationMs();
        long a = Math.max(0L, Math.min(startMs, endMs));
        long b = Math.min(total, Math.max(startMs, endMs));
        if (b - a < 100L) {
            if (listener != null) {
                listener.onExportError(new IllegalArgumentException(
                        "The range to export is empty"));
            }
            return;
        }
        project.getTimeline().resyncAttachedVisualizers();
        project.getTimeline().resyncLinkGroups();
        startChunked(project, generateOutputPath(project), a, b);
    }

    private void startChunked(@NonNull FaditorProject project,
                              @NonNull String finalOutputPath, long rangeStartMs,
                              long rangeEndMs) {
        if (isExporting) {
            FLog.w(TAG, "Export already in progress");
            return;
        }
        if (project.getTimeline().isEmpty()) {
            FLog.e(TAG, "Cannot export empty timeline");
            if (listener != null) {
                listener.onExportError(new IllegalStateException("Timeline is empty"));
            }
            return;
        }
        project.getTimeline().resyncAttachedVisualizers();
        project.getTimeline().resyncLinkGroups();

        resetChunkState();
        isExporting = true;
        chunkCancelled = false;
        sharedWaveformCache = null;   // a new export analyses its (possibly edited) sources
        lastChunkResult = null;
        lastLoggedProgressPct = -1;
        progressEpochMs = 0L;
        paceBaseCompMs = -1L;
        lastSeamItem = -1;
        fxBypassedSnapshot = com.fadcam.ui.faditor.tools.AudioDrawerTabs.fxChainBypassed;
        cleanAudioSnapshot = project.getExportSettings().isCleanAudio();
        openTrace("chunked");

        List<int[]> ranges = computeChunkRanges(project.getTimeline());
        long planStart = System.currentTimeMillis();
        org.json.JSONObject manifest = planChunks(project, ranges);
        int reuse = 0;
        org.json.JSONArray planned = manifest.optJSONArray("chunks");
        for (int i = 0; planned != null && i < planned.length(); i++) {
            if (planned.optJSONObject(i).optBoolean("done", false)) reuse++;
        }
        trace("CHUNKED ranges=" + ranges.size() + " total="
                + project.getTimeline().getTotalDurationMs() + "ms; RENDER_CACHE has " + reuse
                + "/" + ranges.size() + " parts" + (manifest.optBoolean("audioDone", false)
                ? " + sound" : "") + " (planned in "
                + (System.currentTimeMillis() - planStart) + " ms)");
        if (listener != null) {
            listener.onExportStarted(finalOutputPath);
        }
        ChunkRun run = new ChunkRun(project, finalOutputPath, manifest, ranges);
        if (rangeStartMs >= 0 && rangeEndMs > rangeStartMs) run.limitTo(rangeStartMs, rangeEndMs);
        chunkRun = run;
        startWaveformAnalysis(run);
        chunkWorkers.clear();
        String audioPath = manifest.optString("audioFile", "");
        if (manifest.optBoolean("audioDone", false) && !audioPath.isEmpty()
                && new File(audioPath).length() > 0) {
            run.soundState = 2;
            trace("CHUNK sound reused (sound unchanged: " + new File(audioPath).getName() + ")");
        } else {
            startSoundPass(run);   // alongside the parts, from the first second
        }
        pumpChunks(run);
    }

    /**
     * PARALLEL PARTS (export speed Stage 3, 2026-09-24). Parts render on their own
     * ExportManager instances ("workers": no shared mutable state, each with its own
     * Transformer, codecs and GL thread), {@link #PARALLEL_PARTS} at a time, and the sound
     * pass runs on another worker ALONGSIDE them from the start instead of after the last
     * part (it took 5-9 min on the 48-minute lecture, all of it serial). A part that fails
     * while others run is retried once ALONE (the phone may simply not have had room for
     * two pipelines); a second failure is the export's failure, as before.
     */
    private static final int PARALLEL_PARTS = 2;

    /** The chunked run in flight (driver side); null outside one. Main-thread state. */
    private final class ChunkRun {
        final FaditorProject project;
        final String finalOutputPath;
        final org.json.JSONObject manifest;
        final List<int[]> ranges;
        final int n;
        final long[] expected;
        final long totalExpected;
        final float[] progress;
        final boolean[] done;
        final boolean[] running;
        final java.util.ArrayDeque<Integer> retry = new java.util.ArrayDeque<>();
        final java.util.Set<Integer> retried = new java.util.HashSet<>();
        int next = 0;
        int active = 0;
        int maxParallel = PARALLEL_PARTS;
        /** 0 = not started, 1 = running, 2 = done, 3 = failed. */
        int soundState = 0;
        boolean soundRetried = false;
        boolean joining = false;
        /** Visualizer analysis for the rendered parts is in (or there is none). */
        boolean waveformsReady = true;
        /** Parts that show a visualizer, held until its analysis is in. */
        final java.util.ArrayDeque<Integer> waitingForWaveforms = new java.util.ArrayDeque<>();
        @Nullable ExportManager soundWorker;

        /** Parts this run renders and joins (all of them, unless it is a range export). */
        final boolean[] needed;
        /** Range export: editor span, or -1 for the whole project. */
        long rangeStartMs = -1L;
        long rangeEndMs = -1L;

        ChunkRun(@NonNull FaditorProject project, @NonNull String finalOutputPath,
                 @NonNull org.json.JSONObject manifest, @NonNull List<int[]> ranges) {
            this.project = project;
            this.finalOutputPath = finalOutputPath;
            this.manifest = manifest;
            this.ranges = ranges;
            this.n = ranges.size();
            this.expected = new long[n];
            long total = 0L;
            for (int i = 0; i < n; i++) {
                expected[i] = Math.max(1L, rangeExpectedMs(project.getTimeline(),
                        ranges.get(i)[0], ranges.get(i)[1]));
                total += expected[i];
            }
            this.totalExpected = Math.max(1L, total);
            this.progress = new float[n];
            this.done = new boolean[n];
            this.running = new boolean[n];
            this.needed = new boolean[n];
            java.util.Arrays.fill(needed, true);
            this.neededExpected = totalExpected;
        }

        /** Sum of expected ms over the needed parts (the progress bar's whole). */
        long neededExpected;

        boolean isRange() { return rangeStartMs >= 0; }

        /** Keep only the parts whose editor window meets [a, b). */
        void limitTo(long a, long b) {
            rangeStartMs = a;
            rangeEndMs = b;
            long sum = 0L;
            for (int i = 0; i < n; i++) {
                long eb = chunkEditorBases != null ? chunkEditorBases[i] : 0L;
                long ed = chunkEditorDurs != null ? chunkEditorDurs[i] : expected[i];
                needed[i] = eb < b && eb + ed > a;
                if (needed[i]) sum += expected[i];
            }
            neededExpected = Math.max(1L, sum);
        }

        int firstNeeded() {
            for (int i = 0; i < n; i++) if (needed[i]) return i;
            return 0;
        }

        int lastNeeded() {
            for (int i = n - 1; i >= 0; i--) if (needed[i]) return i;
            return n - 1;
        }

        /** Composition time of editor time t (linear inside the part holding it). */
        long compOf(long t) {
            if (chunkBases == null || chunkEditorBases == null || chunkEditorDurs == null
                    || chunkCompDurs == null) return t;
            for (int i = 0; i < n; i++) {
                if (t < chunkEditorBases[i] + chunkEditorDurs[i] || i == n - 1) {
                    long local = Math.max(0L, t - chunkEditorBases[i]);
                    return chunkBases[i] + Math.min(local, chunkCompDurs[i]);
                }
            }
            return t;
        }
    }

    @Nullable
    private volatile ChunkRun chunkRun = null;
    /** Workers of the run in flight, for cancel. */
    private final List<ExportManager> chunkWorkers = new ArrayList<>();

    /** Worker-side: a part's progress and end, reported to the driver. */
    private interface PartCallback {
        void onProgress(float p);
        void onDone(@NonNull ExportResult result);
        void onError(@NonNull Exception e);
    }

    /** A fresh engine for one part or the sound pass, set up like this one. */
    @NonNull
    private ExportManager newChunkWorker(@NonNull String tracePrefix, boolean ownTrace) {
        ExportManager w = new ExportManager(context, prefsManager);
        w.fxBypassedSnapshot = fxBypassedSnapshot;
        w.sharedWaveformCache = sharedWaveformCache;
        w.cleanAudioSnapshot = cleanAudioSnapshot;
        if (!ownTrace) {
            w.traceParent = this;
            w.tracePrefix = tracePrefix;
        }
        chunkWorkers.add(w);
        return w;
    }

    private void cancelChunkWorkers() {
        List<ExportManager> ws = new ArrayList<>(chunkWorkers);
        chunkWorkers.clear();
        for (ExportManager w : ws) {
            try {
                w.cancel();
            } catch (Exception e) {
                FLog.w(TAG, "worker cancel failed", e);
            }
        }
    }

    /** Start whatever may start now; join when every part and the sound are in. */
    private void pumpChunks(@NonNull ChunkRun run) {
        if (run != chunkRun || chunkCancelled || !isExporting || run.joining) return;
        confinedToSlowCores();   // logs CPU_GROUP changes; parallel still wins there (below)
        while (run.active < run.maxParallel) {
            Integer i = run.retry.poll();
            if (i == null) {
                if (run.next >= run.n) break;
                i = run.next++;
            }
            if (!run.needed[i]) continue;   // outside the exported range
            if (partReusable(run, i)) continue;
            if (!run.waveformsReady && partShowsVisualizer(run, i)) {
                run.waitingForWaveforms.add(i);   // other parts render meanwhile
                continue;
            }
            startPart(run, i);
            if (run != chunkRun || !isExporting) return;   // failed synchronously
        }
        reportChunkProgress(run);
        boolean videoDone = run.active == 0 && run.retry.isEmpty() && run.next >= run.n
                && run.waitingForWaveforms.isEmpty();
        if (!videoDone) return;
        if (run.soundState == 2) {
            run.joining = true;
            runChunkJoin(run.project, run.finalOutputPath, run.manifest, run.ranges, run);
        } else if (run.soundState == 3 || run.soundState == 0) {
            if (run.soundState == 3 && run.soundRetried) {
                chunkFail(run.project, run.finalOutputPath, run.manifest, run.ranges, run.n,
                        new IllegalStateException("The sound pass failed twice"));
                return;
            }
            if (run.soundState == 3) run.soundRetried = true;
            startSoundPass(run);   // alone now: the retry, or a sound pass never started
        }
        // soundState 1: still running; its progress now drives the bar (startSoundPass /
        // below), and its completion calls back in here.
        if (run.soundState == 1 && run.soundWorker != null && listener != null) {
            run.soundWorker.setExportListener(new ChunkProgressAdapter(listener,
                    CHUNK_VIDEO_FRAC, CHUNK_AUDIO_FRAC, "Sound"));
            listener.onChunkPhase("Sound");
        }
    }

    /**
     * VISUALIZERS OFF THE MAIN THREAD (2026-09-24). A waveform/spectrum visualizer needs its
     * source analysed; for the 48-min lecture that took 12+ minutes, and on the main thread it
     * froze every part (their progress, completion and scheduling all live there). It now runs
     * on its own thread, through the editor's disk cache, for the visualizers the rendered
     * parts show; parts that show one wait for it, all others render meanwhile.
     */
    private void startWaveformAnalysis(@NonNull ChunkRun run) {
        Timeline tl = run.project.getTimeline();
        if (!tl.hasWaveformOverlays() || chunkEditorBases == null || chunkEditorDurs == null) {
            return;
        }
        boolean any = false;
        long from = Long.MAX_VALUE, to = Long.MIN_VALUE;
        for (int i = 0; i < run.n; i++) {
            if (!run.needed[i] || !partShowsVisualizer(run, i)) continue;
            any = true;
            from = Math.min(from, chunkEditorBases[i] - 10_000L);
            to = Math.max(to, chunkEditorBases[i] + chunkEditorDurs[i] + 10_000L);
        }
        if (!any) return;
        run.waveformsReady = false;
        final long f = from, t = to;
        trace("WAVEFORM analysis started (background) for " + (f / 1000) + ".." + (t / 1000) + " s");
        final long t0 = System.currentTimeMillis();
        new Thread(() -> {
            Map<String, WaveformData> m = new HashMap<>();
            try {
                m = preloadWaveformData(tl, m, f, t);
            } catch (Throwable e) {
                FLog.w(TAG, "visualizer analysis failed; those parts draw without data", e);
            }
            final Map<String, WaveformData> done = m;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                trace("WAVEFORM analysis done in " + (System.currentTimeMillis() - t0) / 1000
                        + " s (" + done.size() + " source(s))");
                sharedWaveformCache = done;
                run.waveformsReady = true;
                while (!run.waitingForWaveforms.isEmpty()) {
                    run.retry.addLast(run.waitingForWaveforms.pollFirst());
                }
                pumpChunks(run);
            });
        }, "faditor-waveform-analysis").start();
    }

    /** Does a visible visualizer's span meet part i's editor window (padded)? */
    private boolean partShowsVisualizer(@NonNull ChunkRun run, int i) {
        if (chunkEditorBases == null || chunkEditorDurs == null) return true;
        long a = chunkEditorBases[i] - 10_000L;
        long b = chunkEditorBases[i] + chunkEditorDurs[i] + 10_000L;
        for (WaveformOverlayInstance woi
                : LayerPreviewController.visibleWaveformOverlays(run.project.getTimeline())) {
            if (woi.getEndMs() >= a && woi.getStartMs() <= b) return true;
        }
        return false;
    }

    /**
     * True when the OS has confined this process to the phone's slow cores, logged as
     * CPU_GROUP. Measured on the Note 20 (2026-09-24, phone locked): Samsung moves the export
     * process /foreground -> /moderate -> /abnormal (cores 0-3, the 1.8 GHz little cluster)
     * within ~90 s. Even there two parts side by side beat one: 1.22x combined vs 1.02x (an
     * earlier "0.5x each" had a visualizer analysis competing), so this only reports.
     * Unreadable = not confined.
     */
    private boolean confinedToSlowCores() {
        String group = "";
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/cpuset"))) {
            String line = r.readLine();
            if (line != null) group = line.trim();
        } catch (Exception ignored) {
            return false;
        }
        boolean confined = group.endsWith("/abnormal") || group.endsWith("/background")
                || group.endsWith("/system-background") || group.endsWith("/restricted");
        if (!group.equals(lastCpuGroup)) {
            lastCpuGroup = group;
            trace("CPU_GROUP " + group + (confined ? " (slow cores only)" : ""));
        }
        return confined;
    }

    @NonNull
    private String lastCpuGroup = "";

    /** A part whose keyed file already exists (render cache / resume) is not rendered again. */
    private boolean partReusable(@NonNull ChunkRun run, int i) {
        try {
            org.json.JSONObject c = run.manifest.getJSONArray("chunks").getJSONObject(i);
            String path = c.getString("file");
            if (c.optBoolean("done", false) && chunkFileValid(path, run.expected[i])) {
                run.done[i] = true;
                run.progress[i] = 1f;
                trace("CHUNK " + i + "/" + run.n + " reused (unchanged since it was rendered: "
                        + new File(path).getName() + ")");
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void startPart(@NonNull ChunkRun run, final int i) {
        final String chunkPath;
        try {
            chunkPath = run.manifest.getJSONArray("chunks").getJSONObject(i).getString("file");
        } catch (Exception e) {
            chunkFail(run.project, run.finalOutputPath, run.manifest, run.ranges, i,
                    new IllegalStateException("Chunk manifest unreadable", e));
            return;
        }
        // Written under a temporary name and renamed once checked: the final name is the
        // render cache's "this part is done" flag, so a half-written file must never wear it.
        final String writePath = chunkPath.replace(".mp4", ".writing.mp4");
        //noinspection ResultOfMethodCallIgnored
        new File(writePath).delete();
        long baseMs = 0L;
        if (chunkBases != null && i < chunkBases.length) {
            baseMs = chunkBases[i];
        } else {
            for (int r = 0; r < i; r++) {
                baseMs += builtRangeDurationMs(run.project, run.ranges.get(r)[0],
                        run.ranges.get(r)[1]);
            }
        }
        final int[] range = run.ranges.get(i);
        final long expectedMs = run.expected[i];
        final ExportManager w = newChunkWorker("P" + (i + 1) + " ", false);
        w.sampleGl = run.active == 0;   // one GL_SAMPLE stream: both GL threads share a name
        run.active++;
        run.running[i] = true;
        run.progress[i] = 0f;
        trace("CHUNK " + i + "/" + run.n + " clips " + range[0] + ".." + range[1]
                + " base=" + baseMs + "ms expected~" + expectedMs + "ms (" + run.active
                + " running)");
        w.renderPart(run.project, range[0], range[1], baseMs, writePath, new PartCallback() {
            @Override
            public void onProgress(float p) {
                if (run != chunkRun) return;
                run.progress[i] = p;
                reportChunkProgress(run);
            }

            @Override
            public void onDone(@NonNull ExportResult result) {
                chunkWorkers.remove(w);
                run.active--;
                run.running[i] = false;
                if (run != chunkRun || chunkCancelled || !isExporting) return;
                lastChunkResult = result;
                if (!chunkFileValid(writePath, expectedMs)) {
                    long gotMs = -1L;
                    try {
                        gotMs = PreTrimCache.probeVideoDurationMs(new File(writePath));
                    } catch (Exception ignored) {}
                    trace("CHUNK " + i + " length mismatch: expected ~" + expectedMs
                            + "ms, got " + gotMs + "ms");
                    chunkFail(run.project, run.finalOutputPath, run.manifest, run.ranges, i,
                            new IllegalStateException("Part " + (i + 1) + " of " + run.n
                                    + " came out the wrong length (expected "
                                    + (expectedMs / 1000) + " s, got " + (gotMs / 1000)
                                    + " s). Export again to redo just this part."));
                    return;
                }
                File done = new File(chunkPath);
                //noinspection ResultOfMethodCallIgnored
                done.delete();
                if (!new File(writePath).renameTo(done)) {
                    chunkFail(run.project, run.finalOutputPath, run.manifest, run.ranges, i,
                            new IllegalStateException("Part " + (i + 1)
                                    + " could not be saved (rename failed)."));
                    return;
                }
                try {
                    run.manifest.getJSONArray("chunks").getJSONObject(i).put("done", true);
                } catch (Exception ignored) {}
                run.done[i] = true;
                run.progress[i] = 1f;
                trace("CHUNK " + i + " done (" + done.length() + " bytes)");
                pumpChunks(run);
            }

            @Override
            public void onError(@NonNull Exception e) {
                chunkWorkers.remove(w);
                run.active--;
                run.running[i] = false;
                if (run != chunkRun || chunkCancelled || !isExporting) return;
                boolean parallel = run.maxParallel > 1 || run.active > 0
                        || run.soundState == 1;
                if (parallel && run.retried.add(i)) {
                    run.maxParallel = 1;
                    run.retry.addFirst(i);
                    trace("CHUNK " + i + " failed with other work running (" + e
                            + ") - retrying it alone, one part at a time from here");
                    pumpChunks(run);
                    return;
                }
                chunkFail(run.project, run.finalOutputPath, run.manifest, run.ranges, i, e);
            }
        });
        // The part's composition is built by now (synchronously): its visualizer analysis, if
        // it needed one, is shared with every part started after it.
        if (sharedWaveformCache == null && w.sharedWaveformCache != null) {
            sharedWaveformCache = w.sharedWaveformCache;
        }
    }

    /** The sound pass on its own worker (own trace file: it closes its trace when it commits). */
    private void startSoundPass(@NonNull ChunkRun run) {
        final String audioPath = run.manifest.optString("audioFile",
                new File(chunkDirFor(run.project), "audio_full.m4a").getAbsolutePath());
        final ExportManager w = newChunkWorker("S ", true);
        w.sampleGl = false;   // no GL thread of its own
        w.sampleSound = true; // where the sound pass's time goes (it took ~15 min alongside parts)
        w.silenceAsGaps = !run.soundRetried;   // the retry uses the silence file, as before
        w.openTrace("sound");
        run.soundWorker = w;
        run.soundState = 1;
        trace("CHUNK sound pass started" + (run.active > 0 ? " alongside the video parts" : ""));
        // The audio clock fit reads each source's audio frame index once - a 48-min
        // recording's whole audio track. Read here, not on the main thread where the
        // composition is built.
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            w.readAudioClocks(run.project.getTimeline());
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (run != chunkRun || chunkCancelled || !isExporting) return;
                trace("CHUNK audio clocks read in " + (System.currentTimeMillis() - t0) + " ms");
                runSoundPass(run, w, audioPath);
            });
        }, "faditor-audio-clocks").start();
    }

    /** Warm {@link #aacIndexOf} for every source the sound pass will read. Any thread. */
    private void readAudioClocks(@NonNull Timeline timeline) {
        try {
            for (int i = 0; i < timeline.getClipCount(); i++) {
                Clip c = timeline.getClip(i);
                if (c.isImageClip() || c.isAudioMuted()) continue;
                Uri u = resolveSeekableSourceUri(c);
                if (u != null) aacIndexOf(u);
            }
            for (AudioClip ac : timeline.getAudioClips()) {
                if (ac.isMuted() || ac.getSourceUri() == null) continue;
                aacIndexOf(seekableUriFor(ac.getSourceUri()));
            }
        } catch (RuntimeException e) {
            FLog.w(TAG, "readAudioClocks: the sound pass reads what is left itself", e);
        }
    }

    private void runSoundPass(@NonNull ChunkRun run, @NonNull ExportManager w,
                              @NonNull String audioPath) {
        w.exportAudioOnly(run.project, audioPath, new ExportListener() {
            @Override public void onExportStarted(@NonNull String outputPath) {}
            @Override public void onExportProgress(float progress) {}
            @Override public void onExportProgressDetailed(float p, int ii, int ic, long b, long e) {}
            @Override public void onExportFinalizing() {}
            @Override public void onChunkPhase(@NonNull String p) {}
            @Override public void onExportCompleted(@NonNull String outputPath,
                                                    @NonNull ExportResult result) {
                chunkWorkers.remove(w);
                run.soundWorker = null;
                if (run != chunkRun || chunkCancelled || !isExporting) return;
                run.soundState = 2;
                try {
                    run.manifest.put("audioDone", true);
                    run.manifest.put("audioFile", outputPath);
                } catch (Exception ignored) {}
                trace("CHUNK sound done (" + new File(outputPath).length() + " bytes)");
                pumpChunks(run);
            }
            @Override public void onExportError(@NonNull Exception error) {
                chunkWorkers.remove(w);
                run.soundWorker = null;
                if (run != chunkRun || chunkCancelled || !isExporting) return;
                run.soundState = 3;
                trace("CHUNK sound pass failed (" + error + ")"
                        + (run.soundRetried ? "" : " - it runs again alone after the parts"));
                pumpChunks(run);
            }
        });
    }

    /** One progress bar over parts running side by side (weighted by their length). */
    private void reportChunkProgress(@NonNull ChunkRun run) {
        if (listener == null) return;
        double sum = 0;
        StringBuilder running = new StringBuilder();
        for (int i = 0; i < run.n; i++) {
            if (!run.needed[i]) continue;
            if (run.done[i]) {
                sum += run.expected[i];
            } else if (run.running[i]) {
                sum += run.expected[i] * Math.max(0f, Math.min(1f, run.progress[i]));
                running.append(running.length() == 0 ? "" : " + ").append(i + 1);
            }
        }
        if (running.length() == 0) return;   // between parts, or on to the sound
        float o = (float) (CHUNK_VIDEO_FRAC * sum / run.neededExpected);
        String phase = (running.indexOf("+") >= 0 ? "Parts " : "Part ") + running
                + " of " + run.n;
        listener.onChunkPhase(phase);
        listener.onExportProgress(o);
        listener.onExportProgressDetailed(o, -1, -1, -1L, -1L);
    }

    /** Expected composition duration of a range (approx: trimmed sums; validation only). */
    private long rangeExpectedMs(@NonNull Timeline timeline, int start, int end) {
        long sum = 0L;
        for (int i = start; i < end && i < timeline.getClipCount(); i++) {
            sum += Math.max(1L, timeline.getClip(i).getTrimmedDurationMs());
        }
        return sum;
    }

    /** Adapter mapping one step's Media3 progress into overall progress + phase. */
    private final class ChunkProgressAdapter implements ExportListener {
        final ExportListener downstream;
        final float baseFrac;
        final float spanFrac;
        final String phase;
        ChunkProgressAdapter(ExportListener downstream, float baseFrac, float spanFrac,
                             @NonNull String phase) {
            this.downstream = downstream;
            this.baseFrac = baseFrac;
            this.spanFrac = spanFrac;
            this.phase = phase;
        }
        private float overall(float p) {
            return Math.min(1f, Math.max(0f, baseFrac + p * spanFrac));
        }
        @Override public void onExportStarted(@NonNull String outputPath) {}
        @Override public void onExportProgress(float progress) {
            onExportProgressDetailed(progress, -1, -1, -1L, -1L);
        }
        @Override public void onExportProgressDetailed(float progress, int itemIndex,
                                                       int itemCount, long bytesWritten,
                                                       long etaRemainingMs) {
            float o = overall(progress);
            downstream.onChunkPhase(phase);
            downstream.onExportProgress(o);
            downstream.onExportProgressDetailed(o, -1, -1, -1L, -1L);
        }
        @Override public void onExportFinalizing() {}
        @Override public void onChunkPhase(@NonNull String p) {}
        @Override public void onExportCompleted(@NonNull String outputPath,
                                                @NonNull ExportResult result) {}
        @Override public void onExportError(@NonNull Exception error) {}
    }

    /**
     * WORKER SIDE: render clips [clipStart, clipEnd) at absolute composition base
     * {@code baseMs}, video only, into {@code writePath}. This instance is the worker; the
     * driver owns naming, checking and scheduling.
     */
    private void renderPart(@NonNull FaditorProject project, int clipStart, int clipEnd,
                            long baseMs, @NonNull String writePath,
                            @NonNull PartCallback cb) {
        isExporting = true;
        chunkBaseMs = baseMs;
        chunkVideoOnly = true;
        chunkClipStart = clipStart;
        chunkClipEnd = clipEnd;
        lastLoggedProgressPct = -1;
        progressEpochMs = 0L;
        paceBaseCompMs = -1L;
        lastSeamItem = -1;
        this.listener = new ExportListener() {
            @Override public void onExportStarted(@NonNull String outputPath) {}
            @Override public void onExportProgress(float progress) { cb.onProgress(progress); }
            @Override public void onExportProgressDetailed(float p, int ii, int ic, long b, long e) {}
            @Override public void onExportFinalizing() {}
            @Override public void onChunkPhase(@NonNull String p) {}
            @Override public void onExportCompleted(@NonNull String outputPath,
                                                    @NonNull ExportResult result) {}
            @Override public void onExportError(@NonNull Exception error) {}
        };
        try {
            Transformer.Builder builder = baseVideoTransformerBuilder(project);
            builder.addListener(new Transformer.Listener() {
                @Override
                public void onCompleted(@NonNull Composition composition,
                                        @NonNull ExportResult result) {
                    stopProgressPolling();
                    if (!isExporting) return;   // cancelled
                    isExporting = false;
                    cb.onDone(result);
                }

                @Override
                public void onError(@NonNull Composition composition,
                                    @NonNull ExportResult result,
                                    @NonNull ExportException exception) {
                    stopProgressPolling();
                    if (!isExporting) return;
                    isExporting = false;
                    cb.onError(exception);
                }
            });
            transformer = builder.build();
            Composition composition;
            try {
                composition = buildComposition(project, null);
            } finally {
                releasePerThreadRetriever();
            }
            transformer.start(composition, writePath);
            startProgressPolling();
        } catch (Exception e) {
            isExporting = false;
            cb.onError(e);
        }
    }

    /**
     * Composition duration of a clip range, measured from built items (exact — whatever
     * the loop emitted, including loop reps and transition compression). No encode.
     */
    private long builtRangeDurationMs(@NonNull FaditorProject project, int start, int end) {
        long keepBase = chunkBaseMs;
        boolean keepVideoOnly = chunkVideoOnly;
        int keepStart = chunkClipStart;
        int keepEnd = chunkClipEnd;
        boolean keepMeasuring = measuringOnly;
        try {
            chunkBaseMs = 0L;
            chunkVideoOnly = true;
            chunkClipStart = start;
            chunkClipEnd = end;
            measuringOnly = true;
            Composition c = buildComposition(project, null);
            long sum = 0L;
            for (EditedMediaItemSequence seq : c.sequences) {
                for (EditedMediaItem it : seq.editedMediaItems) {
                    if (it.durationUs > 0) sum += it.durationUs / 1000L;
                }
            }
            return sum;
        } catch (Exception e) {
            FLog.w(TAG, "builtRangeDurationMs failed, estimating", e);
            long sum = 0L;
            Timeline tl = project.getTimeline();
            for (int i = start; i < end && i < tl.getClipCount(); i++) {
                sum += Math.max(1L, tl.getClip(i).getTrimmedDurationMs());
            }
            return sum;
        } finally {
            measuringOnly = keepMeasuring;
            chunkBaseMs = keepBase;
            chunkVideoOnly = keepVideoOnly;
            chunkClipStart = keepStart;
            chunkClipEnd = keepEnd;
        }
    }

    /** Abort the chain with a part-numbered error (chunks stay for resume). */
    private void chunkFail(@NonNull FaditorProject project,
                           @NonNull String finalOutputPath,
                           @NonNull org.json.JSONObject manifest,
                           @NonNull List<int[]> ranges, int step,
                           @NonNull Throwable error) {
        isExporting = false;
        chunkRun = null;
        cancelChunkWorkers();
        resetChunkState();
        String where = step < ranges.size()
                ? "Part " + (step + 1) + " of " + ranges.size()
                : "Sound";
        FLog.e(TAG, "Chunked export failed at " + where, error);
        writeExportErrorLog(project, error, finalOutputPath);
        if (listener != null) {
            String msg = error.getMessage() != null ? error.getMessage() : error.toString();
            listener.onExportError(new Exception(where + ": " + msg, error));
        }
    }

    /**
     * Join: concat video chunks (stream copy) + mux the single audio pass, then the
     * shared finalize (loudness once, SAF copy). Runs off-main; failures keep chunks.
     */
    private void runChunkJoin(@NonNull FaditorProject project,
                              @NonNull String finalOutputPath,
                              @NonNull org.json.JSONObject manifest,
                              @NonNull List<int[]> ranges,
                              @NonNull ChunkRun run) {
        // The audio pass commits its own staging file, which closes the trace; reopen so the
        // join (the step that failed silently on 2026-09-23) leaves a durable record.
        openTrace("join");
        if (listener != null) listener.onChunkPhase("Joining");
        if (listener != null) {
            listener.onExportProgress(CHUNK_VIDEO_FRAC + CHUNK_AUDIO_FRAC);
            listener.onExportProgressDetailed(CHUNK_VIDEO_FRAC + CHUNK_AUDIO_FRAC,
                    -1, -1, -1L, -1L);
        }
        final String stagingPath = stagingPathFor(finalOutputPath);
        currentStagingPath = stagingPath;
        new Thread(() -> {
            try {
                File dir = chunkDirFor(project);
                File listFile = new File(dir, "concat_list.txt");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ranges.size(); i++) {
                    if (!run.needed[i]) continue;
                    String p = manifest.getJSONArray("chunks").getJSONObject(i)
                            .getString("file");
                    sb.append("file '").append(p.replace("'", "'\\''")).append("'\n");
                }
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(listFile)) {
                    fos.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                File videoFull = new File(dir, "video_full.mp4");
                if (videoFull.exists()) videoFull.delete();
                String audioPath = manifest.optString("audioFile",
                        new File(dir, "audio_full.m4a").getAbsolutePath());
                int joined = 0;
                for (boolean need : run.needed) if (need) joined++;
                trace("CHUNK joining " + joined + " of " + ranges.size() + " parts + audio");
                com.arthenica.ffmpegkit.FFmpegSession s1 =
                        com.arthenica.ffmpegkit.FFmpegKit.execute(
                                "-f concat -safe 0 -i \"" + listFile.getAbsolutePath()
                                        + "\" -map 0:v:0 -an -c copy -f mp4 -movflags +faststart -y \""
                                        + videoFull.getAbsolutePath() + "\"");
                if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(s1.getReturnCode())
                        || !videoFull.exists() || videoFull.length() <= 0) {
                    traceFfmpegTail("join video", s1);
                    throw new IllegalStateException("Joining video parts failed: "
                            + s1.getReturnCode());
                }
                // RANGE: the joined parts start at the first needed part's composition time; the
                // sound is the whole project's, so it is cut to the same span, and the result is
                // trimmed to the exact range afterwards (unless the range IS whole parts).
                String audioCut = "";
                long trimStartMs = 0L, trimEndMs = -1L;
                if (run.isRange() && chunkBases != null && chunkCompDurs != null) {
                    int f = run.firstNeeded(), l = run.lastNeeded();
                    long spanStart = chunkBases[f];
                    long spanMs = chunkBases[l] + chunkCompDurs[l] - spanStart;
                    audioCut = String.format(Locale.US, "-ss %.3f -t %.3f ",
                            spanStart / 1000.0, spanMs / 1000.0);
                    trimStartMs = Math.max(0L, run.compOf(run.rangeStartMs) - spanStart);
                    trimEndMs = Math.min(spanMs, run.compOf(run.rangeEndMs) - spanStart);
                    if (trimStartMs < 40L && trimEndMs > spanMs - 40L) trimEndMs = -1L;
                    trace("RANGE " + run.rangeStartMs + ".." + run.rangeEndMs + "ms editor -> parts "
                            + (f + 1) + ".." + (l + 1) + ", trim " + trimStartMs + ".."
                            + trimEndMs + "ms of " + spanMs + "ms");
                }
                final boolean trim = trimEndMs > 0;
                File staging = new File(stagingPath);
                if (staging.exists()) staging.delete();
                File muxOut = trim ? new File(dir, "range_joined.mp4") : staging;
                if (muxOut.exists()) muxOut.delete();
                com.arthenica.ffmpegkit.FFmpegSession s2 =
                        com.arthenica.ffmpegkit.FFmpegKit.execute(
                                // EXPLICIT maps. Every video part carries Media3's forced
                                // SILENT audio track; unmapped, ffmpeg picks "the best"
                                // audio across inputs and on a tie takes input 0's — the
                                // silence — shipping a full-length export with no sound.
                                "-i \"" + videoFull.getAbsolutePath() + "\" " + audioCut
                                        + "-i \"" + audioPath
                                        // -f mp4: the staging name ends ".exporting", and ffmpeg
                                        // picks the container from the extension — without it
                                        // the join died "Error opening output file" (exit 1)
                                        // after 58 minutes of good parts (2026-09-23 05:42).
                                        + "\" -map 0:v:0 -map 1:a:0 -c copy -f mp4 -movflags +faststart -y \""
                                        + muxOut.getAbsolutePath() + "\"");
                if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(s2.getReturnCode())
                        || !muxOut.exists() || muxOut.length() <= 0) {
                    traceFfmpegTail("join sound", s2);
                    throw new IllegalStateException("Joining sound failed: "
                            + s2.getReturnCode());
                }
                if (chunkCancelled) {
                    discardStaging(stagingPath);
                    isExporting = false;
                    return;
                }
                if (trim) {
                    //noinspection ResultOfMethodCallIgnored
                    videoFull.delete();
                    final long ts = trimStartMs, te = trimEndMs;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            runRangeTrim(project, run, muxOut, ts, te, stagingPath,
                                    finalOutputPath));
                    return;
                }
                String committed = commitStaging(stagingPath, finalOutputPath);
                // The finished file is committed. The joined video (~the export's size) goes
                // now; the parts and the sound stay as the render cache, so the next export of
                // this project redoes only what changed (bounded by pruneRenderCache).
                //noinspection ResultOfMethodCallIgnored
                videoFull.delete();
                pruneRenderCache(project, manifest);
                chunkRun = null;
                // Every part and the sound can come from the render cache, leaving no fresh
                // ExportResult - the loudness pass and the copy to the chosen folder still run.
                ExportResult result = lastChunkResult != null ? lastChunkResult
                        : new ExportResult.Builder().build();
                isExporting = false;
                resetChunkState();
                finalizeExportAsync(project, committed, result, "Chunked export completed");
            } catch (Exception e) {
                if (chunkCancelled) {
                    isExporting = false;
                    return;
                }
                chunkFail(project, finalOutputPath, manifest, ranges, ranges.size() + 1, e);
            }
        }, "faditor-chunk-join").start();
    }

    /**
     * RANGE EXPORT's last step, on the main thread (Transformer needs a Looper): cut the joined
     * parts to [startMs, endMs). Trim optimization re-encodes only up to the first keyframe
     * after the cut and copies the rest, so the range costs seconds and keeps the parts'
     * pixels; Media3 falls back to a full transcode on its own when it cannot.
     */
    private void runRangeTrim(@NonNull FaditorProject project, @NonNull ChunkRun run,
                              @NonNull File joined, long startMs, long endMs,
                              @NonNull String stagingPath, @NonNull String finalOutputPath) {
        if (run != chunkRun || chunkCancelled || !isExporting) return;
        if (listener != null) {
            listener.onChunkPhase("Trimming to the range");
            listener.onExportProgress(0.97f);
            listener.onExportProgressDetailed(0.97f, -1, -1, -1L, -1L);
        }
        try {
            MediaItem item = new MediaItem.Builder()
                    .setUri(Uri.fromFile(joined))
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .setEndPositionMs(endMs)
                            .build())
                    .build();
            Transformer t = new Transformer.Builder(context)
                    .experimentalSetTrimOptimizationEnabled(true)
                    .setMaxDelayBetweenMuxerSamplesMs(300_000L)
                    // NO encoder factory: ours requests an audio bitrate, which Media3 reads
                    // as "transcode the audio" and abandons trim optimization (measured:
                    // optimizationResult 3, the whole range re-encoded). The joined file is
                    // already in the export's format; only the head GOP is re-encoded, at
                    // Media3's default video settings.
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(@NonNull Composition composition,
                                                @NonNull ExportResult result) {
                            //noinspection ResultOfMethodCallIgnored
                            joined.delete();
                            if (run != chunkRun || chunkCancelled || !isExporting) {
                                discardStaging(stagingPath);
                                return;
                            }
                            // 0 none, 1 succeeded (head re-encoded, rest copied), 2 keyframe
                            // already on the cut, 3/4 abandoned (full transcode).
                            trace("RANGE trimmed (" + new File(stagingPath).length()
                                    + " bytes, trim optimization=" + result.optimizationResult
                                    + ", " + (System.currentTimeMillis() - rangeTrimStartMs)
                                    + " ms)");
                            new Thread(() -> finishChunked(project, run.manifest, stagingPath,
                                    finalOutputPath), "faditor-range-finish").start();
                        }

                        @Override
                        public void onError(@NonNull Composition composition,
                                            @NonNull ExportResult result,
                                            @NonNull ExportException exception) {
                            //noinspection ResultOfMethodCallIgnored
                            joined.delete();
                            if (run != chunkRun || chunkCancelled || !isExporting) return;
                            chunkFail(project, finalOutputPath, run.manifest, run.ranges,
                                    run.n + 1, exception);
                        }
                    })
                    .build();
            transformer = t;
            rangeTrimStartMs = System.currentTimeMillis();
            t.start(item, stagingPath);
        } catch (Exception e) {
            chunkFail(project, finalOutputPath, run.manifest, run.ranges, run.n + 1, e);
        }
    }

    private long rangeTrimStartMs = 0L;

    /** Commit the finished file, keep the render cache, and hand over to finalize. */
    private void finishChunked(@NonNull FaditorProject project,
                               @NonNull org.json.JSONObject manifest,
                               @NonNull String stagingPath, @NonNull String finalOutputPath) {
        if (chunkCancelled) {
            discardStaging(stagingPath);
            isExporting = false;
            return;
        }
        String committed = commitStaging(stagingPath, finalOutputPath);
        pruneRenderCache(project, manifest);
        chunkRun = null;
        ExportResult result = lastChunkResult != null ? lastChunkResult
                : new ExportResult.Builder().build();
        isExporting = false;
        resetChunkState();
        finalizeExportAsync(project, committed, result, "Range export completed");
    }

    /** Last ~1.5 KB of an ffmpeg session's log into the durable trace — its own reason for failing. */
    private void traceFfmpegTail(@NonNull String step,
                                 @NonNull com.arthenica.ffmpegkit.FFmpegSession session) {
        try {
            String logs = session.getAllLogsAsString();
            if (logs == null) logs = "";
            String tail = logs.length() > 1500 ? logs.substring(logs.length() - 1500) : logs;
            trace("FFMPEG_FAIL " + step + " rc=" + session.getReturnCode() + '\n' + tail);
        } catch (Exception ignored) { }
    }

    /**
     * C4/C8 — shared post-export finalize for BOTH export paths: run the loudness
     *
     * <p>The correction pass runs ffmpeg three times (measure, apply, re-measure) —
     * always OFF the main thread Media3 delivers {@code onCompleted} on.</p>
     */
    private void finalizeExportAsync(@NonNull FaditorProject project,
                                     @NonNull String outputPath,
                                     @NonNull ExportResult result,
                                     @NonNull String completedLogTag) {
        final LoudnessTarget target = pendingLoudnessTarget;
        // C8 — THE consumer of ExportSettings.isCleanAudio(): when no explicit C4 target is
        // chosen, Clean Audio still runs its chain at BakedAudioCache's default -16 LUFS.
        final boolean cleanAudio = project.getExportSettings() != null
                && project.getExportSettings().isCleanAudio();
        final boolean needsPass = target.lufs != null || cleanAudio;
        if (!needsPass) {
            FLog.d(TAG, "C4/C8 loudness pass not requested (target Off, Clean Audio unchecked)");
        }
        new Thread(() -> {
            String finalPath = outputPath;
            // FIX-6: announce the finalize phase (loudness + SAF copy run minutes on GB
            // files with zero UI state today) BEFORE doing it.
            try {
                if (listener != null) listener.onExportFinalizing();
            } catch (Exception ignored) {}
            if (needsPass) {
                applyLoudnessPass(new File(outputPath), target, cleanAudio);
            }
            // If exported to temp for SAF, copy to custom storage now
            if (pendingSafCopy) {
                String safResult = copyTempToSaf(outputPath);
                if (safResult != null) {
                    finalPath = safResult;
                    FLog.d(TAG, completedLogTag + " (copied to SAF): " + safResult);
                } else {
                    FLog.e(TAG, "SAF copy failed, file remains at: " + outputPath);
                }
                pendingSafCopy = false;
                safExportFileName = null;
            }
            FLog.d(TAG, completedLogTag + ": " + finalPath);
            if (listener != null) {
                listener.onExportCompleted(finalPath, result);
            }
        }, "faditor-loudness-finalize").start();
    }

    /**
     * C4/C8 — measure → two-pass loudnorm → re-measure one exported file, IN PLACE
     * (temp sibling + atomic rename). Best-effort: any failure leaves the original
     * export untouched and logs it; never throws. BLOCKS on ffmpeg — bg thread only.
     */
    private void applyLoudnessPass(@NonNull File outFile,
                                   @NonNull LoudnessTarget target,
                                   boolean cleanAudio) {
        try {
            double effectiveTargetLUFS = target.lufs != null ? target.lufs : -16.0d;
            LoudnessAnalyzer.Result before = LoudnessAnalyzer.measure(outFile);
            lastExportLoudnessBefore = before != null ? before.integratedLUFS : null;
            lastExportLoudnessAfter = null;
            if (before == null) {
                FLog.w(TAG, "C4/C8 loudness pass skipped: mix measured as silence/unreadable");
                return;
            }
            File tmp = new File(outFile.getParentFile(), outFile.getName() + ".loudnorm.tmp");
            boolean ok = LoudnessAnalyzer.normalize(outFile, tmp, effectiveTargetLUFS, cleanAudio);
            if (!ok) {
                tmp.delete();
                FLog.w(TAG, String.format(Locale.US,
                        "C4/C8 loudnorm failed — export kept UN-normalized at %.1f LUFS",
                        before.integratedLUFS));
                return;
            }
            if (!tmp.renameTo(outFile)) {
                // rename can refuse over an existing file on some filesystems
                outFile.delete();
                if (!tmp.renameTo(outFile)) {
                    tmp.delete();
                    FLog.e(TAG, "C4/C8 loudnorm commit failed — keeping un-normalized export");
                    return;
                }
            }
            LoudnessAnalyzer.Result after = LoudnessAnalyzer.measure(outFile);
            lastExportLoudnessAfter = after != null ? after.integratedLUFS : null;
            FLog.i(TAG, String.format(Locale.US,
                    "C4 LOUDNESS: before %.1f LUFS → after %s LUFS (target %.0f LUFS%s)",
                    before.integratedLUFS,
                    after != null ? String.format(Locale.US, "%.1f", after.integratedLUFS) : "?",
                    effectiveTargetLUFS,
                    cleanAudio ? ", Clean Audio chain" : ""));
        } catch (Throwable t) {
            FLog.w(TAG, "C4/C8 loudness pass crashed — export kept un-normalized", t);
        }
    }

    /**
     * Build an audio-only {@link Composition}: one sequence of the master clips' audio
     * (image / muted / loop segments → silence, preserving timing) plus the {@link AudioClip}
     * track (via {@link #buildAudioSequences}), mixed. Reuses the existing per-clip audio
     * treatment (Sonic speed, volume envelope/static, mute) without touching the video path.
     */
    @NonNull
    private Composition buildAudioOnlyComposition(@NonNull FaditorProject project) {
        Timeline timeline = project.getTimeline();
        File silenceFile = getOrCreateSilenceFile();
        Uri silenceUri = silenceFile != null ? Uri.fromFile(silenceFile) : null;

        // A6: determine project sample rate from the master track's first audio source.
        int projectSampleRate = resolveProjectSampleRate(timeline);

        List<EditedMediaItem> master = new ArrayList<>();
        for (int ci = 0; ci < timeline.getClipCount(); ci++) {
            Clip clip = timeline.getClip(ci);
            long clipInMs = clip.getInPointMs();
            long clipOutMs = clip.getOutPointMs();

            // Mirror buildComposition's HEAD-overlap trim: a clip that is the SECOND in a
            // transition has its head covered by the previous clip's transition tail, so its
            // audio starts later. We keep each clip's FULL tail (the transition is a
            // video-only crossfade), so total audio length still matches the video export.
            Transition prevTrans = ci > 0 ? findTransitionAtSeam(timeline, ci - 1) : null;
            if (prevTrans != null) {
                // ⚠ effectiveTransitionMs, NOT prevTrans.durationMs. The VIDEO path clamps a
                // transition to what the two clips at that seam can actually give it (:926); this
                // path used the raw authored value. Whenever a transition IS clamped — a short
                // clip, or a long dissolve — the master audio was trimmed by MORE than the video,
                // so a clip's own sound ran ahead of its own picture for the rest of the export.
                // Same seam, two different lengths. (Adversarial review 2026-08-03.)
                long effMs = effectiveTransitionMs(timeline, prevTrans, ci - 1);
                long overlapSourceMs = Math.round(effMs * clip.getSpeedMultiplier());
                clipInMs = Math.min(clipOutMs, clipInMs + overlapSourceMs);
            }

            if (clip.hasLoopExtension() && !clip.isImageClip() && clip.getLoopBeforeMs() > 0) {
                addSilence(master, silenceUri, clip.getLoopBeforeMs());
            }

            float speed = clip.getSpeedMultiplier();
            if (clip.isImageClip()) {
                addSilence(master, silenceUri, Math.max(1L, clipOutMs - clipInMs));
            } else if (clipInMs >= Math.min(clipOutMs, clip.getSourceDurationMs())) {
                // The seam transition's head-trim consumed the ENTIRE clip (transition
                // overlap >= clip duration). Emitting a zero-length clipped item produces
                // no samples and stalls the AudioGraph until the muxer watchdog aborts
                // ("no output sample written in the last 10000 ms") — skip it; the
                // previous clip's full tail already covers this span.
            } else {
                long endMs = Math.min(clipOutMs, clip.getSourceDurationMs());
                long srcDurMs = Math.max(1L, endMs - clipInMs);
                long timelineDurMs = Math.max(1L, (long) (srcDurMs / Math.max(0.1f, speed)));
                if (clip.isAudioMuted()) {
                    addSilence(master, silenceUri, timelineDurMs);
                } else {
                    MediaItem mediaItem = new MediaItem.Builder()
                            .setUri(resolveSeekableSourceUri(clip))
                            .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(clipInMs)
                                    .setEndPositionMs(endMs)
                                    .build())
                            .build();
                    EditedMediaItem.Builder eb = new EditedMediaItem.Builder(mediaItem)
                            .setRemoveVideo(true)
                            .setDurationUs(timelineDurMs * 1000);
                    List<AudioProcessor> aps = new ArrayList<>();
                    SonicAudioProcessor sap = speedAndFit(speed,
                            audioClockFit(resolveSeekableSourceUri(clip), clipInMs, endMs));
                    if (sap != null) aps.add(sap);
                    if (clip.hasVolumeKeyframes()) {
                        @SuppressWarnings("unchecked")
                        List<Clip.VolumeKeyframe> kfs = (List<Clip.VolumeKeyframe>) clip.getVolumeKeyframes();
                        long[] times = new long[kfs.size()];
                        float[] vols = new float[kfs.size()];
                        for (int i = 0; i < kfs.size(); i++) {
                            times[i] = kfs.get(i).timeMs;
                            vols[i] = kfs.get(i).volume;
                        }
                        VolumeAudioProcessor vp = new VolumeAudioProcessor();
                        // B1.Q: envelope values are MULTIPLIERS over the static level —
                        // without this the base stayed 1.0 and a boosted clip's fade
                        // capped its whole length at 100%.
                        vp.setVolume(clip.getVolumeLevel());
                        vp.setVolumeEnvelope(times, vols);
                        aps.add(vp);
                    } else if (Math.abs(clip.getVolumeLevel() - 1.0f) >= 0.01f) {
                        VolumeAudioProcessor vp = new VolumeAudioProcessor();
                        vp.setVolume(clip.getVolumeLevel());
                        aps.add(vp);
                    }
                    if (!aps.isEmpty()) {
                        eb.setEffects(new Effects(aps, Collections.emptyList()));
                    }
                    master.add(eb.build());
                }
            }

            if (clip.hasLoopExtension() && !clip.isImageClip() && clip.getLoopAfterMs() > 0) {
                addSilence(master, silenceUri, clip.getLoopAfterMs());
            }
        }

        List<EditedMediaItemSequence> sequences = new ArrayList<>();
        if (!master.isEmpty()) {
            // Same reason as the video spine below: this list interleaves addSilence() items,
            // which carry audio and no video, with real clips. Force the audio track so a
            // leading item without one cannot abort the export.
            sequences.add(new EditedMediaItemSequence.Builder(master)
                    .experimentalSetForceAudioTrack(true)
                    .build());
        }
        if (timeline.hasAudioClips()) {
            // A8: one sequence PER AUDIO LANE, mixed in parallel by the Composition.
            sequences.addAll(buildAudioSequences(timeline));
        }
        // SPEC_PIP_AUDIO: an opted-in PiP contributes audio to the audio-only export too.
        EditedMediaItemSequence overlayAudioOnly = buildOverlayAudioSequence(timeline, projectSampleRate);
        if (overlayAudioOnly != null) {
            sequences.add(overlayAudioOnly);
        }
        if (sequences.isEmpty()) {
            // Nothing audible at all — emit a short silence so the Transformer has valid input.
            List<EditedMediaItem> tiny = new ArrayList<>();
            addSilence(tiny, silenceUri, 500L);
            if (tiny.isEmpty()) {
                throw new IllegalStateException(
                        "Audio-only export: no audio present and no silence source available");
            }
            sequences.add(new EditedMediaItemSequence.Builder(tiny).build());
        }
        return new Composition.Builder(withStereoOutput(sequences)).build();
    }

    /** Append chunked silence totalling {@code durationMs} to {@code items} (no-op if there
     *  is no silence source or the duration is non-positive). Chunks at {@link #SILENCE_FILE_MS}
     *  so gaps longer than the silence file are covered by multiple items. */

    /**
     * An asset loader that decodes with the HARDWARE decoder whenever the device has one.
     *
     * <p>MEASURED, NOT ASSUMED. On JoyRaptor's Note 9 a 46-second 720p export took about fifteen
     * minutes, and the codec counts during it were unambiguous:
     *
     * <pre>
     *   OMX.qcom.video.decoder.hevc     4    hardware, advertised to 4096x2160@60
     *   c2.android.hevc.decoder      1414    SOFTWARE, doing essentially all of it
     * </pre>
     *
     * His camera records HEVC Main 1080x1920, so the whole export was decoding H.265 in
     * software on a 2019 phone. The same phone plays the same footage back live, with masks
     * and effects, because PREVIEW gets the hardware decoder -- which is exactly why "export
     * should be close to parity with playback" is the right expectation.
     *
     * <p>Why media3 chose software: {@code DefaultDecoderFactory} orders candidates with
     * {@code getDecoderInfosSortedByFullFormatSupport} and then, with decoder fallback OFF by
     * default, tries ONLY THE FIRST ONE. That sort is stable and scores purely on
     * {@code isFormatSupported}, so whenever the software decoder claims support and the
     * hardware one does not fully claim it for this profile/level, software wins outright and
     * hardware is never even attempted. Nothing was misconfigured; the default simply is not
     * built for a device whose hardware decoder under-advertises.
     *
     * <p>Two changes fix it together, and both are needed. The selector lists hardware
     * candidates first, and because the media3 sort is STABLE that order survives whenever the
     * two score equally. Decoder fallback is switched ON so the list is walked rather than
     * truncated to one entry -- which also means a hardware decoder that genuinely cannot
     * handle a file still degrades to software instead of failing the export.
     */
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private androidx.media3.transformer.AssetLoader.Factory hardwareFirstAssetLoaderFactory() {
        androidx.media3.exoplayer.mediacodec.MediaCodecSelector hardwareFirst =
                (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
                    java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> infos =
                            new java.util.ArrayList<>(
                                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                                            .getDecoderInfos(mimeType, requiresSecureDecoder,
                                                    requiresTunnelingDecoder));
                    java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> hw =
                            new java.util.ArrayList<>();
                    java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> sw =
                            new java.util.ArrayList<>();
                    for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo i : infos) {
                        (i.hardwareAccelerated ? hw : sw).add(i);
                    }
                    // ORDERING ALONE LOSES, SO OFFER ONLY HARDWARE WHEN THERE IS ANY.
                    // media3 re-sorts whatever this returns by decoderInfo.isFormatSupported
                    // (MediaCodecUtil.getDecoderInfosSortedByFullFormatSupport), and that
                    // question is being answered with a LIE about these files: FadCam's own
                    // recordings declare r_frame_rate = 90000/1 -- the MP4 timescale leaking
                    // into the frame-rate field -- while their real rate is ~29.9fps.
                    // Verified on both the camera original and the remuxed copy, so it is the
                    // recorder, not the remuxer. Asked "can you do 1080x1920 at 90000fps?" the
                    // hardware decoder correctly says no, scores 0, and sorts below the
                    // software decoder, which claims everything. Merely listing hardware first
                    // could not survive that sort -- measured: hardware HEVC use went 4 -> 22
                    // while software still did 1191.
                    //
                    // Preview decodes these very files on hardware, live, with effects. That
                    // is the proof the decoder can do it and the advertised rate is what is
                    // wrong. So when the device has a hardware decoder for this MIME, that is
                    // the list; software is kept only when there is no hardware option at all.
                    // SAY WHAT WAS ACTUALLY ON OFFER. Three attempts at this have now failed
                    // against assumptions about what this list contains; print it once per
                    // MIME rather than reason about it a fourth time.
                    if (loggedSelectorMimes.add(mimeType)) {
                        StringBuilder sb = new StringBuilder("DECODER_PICK ").append(mimeType);
                        for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo i : infos) {
                            sb.append("\n    ").append(i.name)
                                    .append(" hardwareAccelerated=").append(i.hardwareAccelerated)
                                    .append(" softwareOnly=").append(i.softwareOnly)
                                    .append(" vendor=").append(i.vendor);
                        }
                        sb.append("\n    -> offering ").append(hw.isEmpty()
                                ? "SOFTWARE (no hardware entry found)"
                                : (hw.size() + " hardware"));
                        trace(sb.toString());
                    }
                    if (!hw.isEmpty()) return hw;
                    return sw;
                };
        androidx.media3.transformer.DefaultDecoderFactory decoderFactory =
                new androidx.media3.transformer.DefaultDecoderFactory.Builder(context)
                        .setMediaCodecSelector(hardwareFirst)
                        .setEnableDecoderFallback(true)
                        .build();
        return new androidx.media3.transformer.DefaultAssetLoaderFactory(
                context, decoderFactory, androidx.media3.common.util.Clock.DEFAULT, null);
    }

    /** Media3's own gap marker (EditedMediaItem.GAP_MEDIA_ID, package-private there). */
    private static final String MEDIA3_GAP_MEDIA_ID = "androidx-media3-GapMediaItem";

    /** Silence as Media3 gaps (see addSilence). The sound-pass worker only; its retry is off. */
    private boolean silenceAsGaps = false;

    @NonNull
    private static EditedMediaItem gapItem(long durationMs) {
        return new EditedMediaItem.Builder(
                new MediaItem.Builder().setMediaId(MEDIA3_GAP_MEDIA_ID).build())
                .setDurationUs(durationMs * 1000L)
                .build();
    }

    private static boolean isGap(@NonNull EditedMediaItem it) {
        return MEDIA3_GAP_MEDIA_ID.equals(it.mediaItem.mediaId);
    }

    /** MIMEs already reported by the decoder selector, so it logs once each, not once per clip. */
    private final java.util.Set<String> loggedSelectorMimes = new java.util.HashSet<>();

    private void addSilence(@NonNull List<EditedMediaItem> items, @Nullable Uri silenceUri,
                            long durationMs) {
        if (durationMs <= 0) return;
        // SOUND PASS SPEED (2026-09-24): after the first item, silence is a Media3 GAP - zeros
        // generated in the format of the item before it, no decoder, no file. A 48-min
        // project's music lane was ~44 decoded minutes of silence file per short clip. Never
        // FIRST in a sequence: a gap has no format of its own and Media3 mixes in the first
        // input's format (the mono 44.1 kHz bug), so a sequence still opens with real audio.
        if (silenceAsGaps && !items.isEmpty()) {
            items.add(gapItem(durationMs));
            return;
        }
        if (silenceUri == null) return;
        long remaining = durationMs;
        while (remaining > 0) {
            long chunk = Math.min(remaining, SILENCE_FILE_MS);
            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(silenceUri)
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0)
                            .setEndPositionMs(chunk)
                            .build())
                    .build();
            items.add(new EditedMediaItem.Builder(mediaItem)
                    .setRemoveVideo(true)
                    .setDurationUs(chunk * 1000)
                    .build());
            remaining -= chunk;
        }
    }

    /**
     * Persist a detailed, timestamped export-failure report to
     * {@code <externalFiles>/faditor_export_errors/}. Export failures previously
     * left no durable trace (only a transient notification — and an OOM process
     * kill leaves nothing at all), making them hard to diagnose after the fact.
     * Best-effort: never throws.
     */
    private void writeExportErrorLog(@Nullable FaditorProject project,
                                     @NonNull Throwable error, @Nullable String outputPath) {
        try {
            File dir = new File(context.getExternalFilesDir(null), "faditor_export_errors");
            if (!dir.exists() && !dir.mkdirs()) {
                dir = context.getExternalFilesDir(null); // fall back to the files root
            }
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            File log = new File(dir, "export_error_" + ts + ".txt");
            StringBuilder sb = new StringBuilder();
            sb.append("Export failed: ").append(new java.util.Date()).append('\n');
            sb.append("output=").append(outputPath).append('\n');
            // 2026-09-22: the muxer position at death — names the stall's item without
            // needing the (rotated-away) logcat.
            if (lastSeamItem >= 0 && exportItemStartMs.length > 0
                    && lastSeamItem < exportItemStartMs.length) {
                sb.append("lastSeam=item ").append(lastSeamItem)
                        .append(" of ").append(exportItemStartMs.length)
                        .append(" (comp ").append(exportItemStartMs[lastSeamItem])
                        .append("ms of ").append(exportItemTotalMs).append("ms)\n");
            } else {
                sb.append("lastSeam=unknown (muxer never reached the first item boundary)\n");
            }
            if (project != null) {
                Timeline tl = project.getTimeline();
                sb.append("projectId=").append(project.getId()).append('\n');
                sb.append("canvasPreset=").append(project.getCanvasPreset()).append('\n');
                sb.append("clips=").append(tl.getClipCount())
                        .append(" audioClips=").append(tl.getAudioClips().size())
                        .append(" transitions=").append(tl.getTransitions().size()).append('\n');
                for (int i = 0; i < tl.getClipCount(); i++) {
                    Clip c = tl.getClip(i);
                    sb.append("  clip[").append(i).append("] img=").append(c.isImageClip())
                            .append(" in=").append(c.getInPointMs())
                            .append(" out=").append(c.getOutPointMs())
                            .append(" loop=").append(c.getLoopMode())
                            .append(" uri=").append(c.getSourceUri()).append('\n');
                }
            }
            sb.append("\n--- stack trace ---\n");
            java.io.StringWriter sw = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(sw));
            sb.append(sw);
            try (FileOutputStream fos = new FileOutputStream(log)) {
                fos.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            FLog.d(TAG, "Wrote export error log: " + log.getAbsolutePath());
        } catch (Throwable t) {
            FLog.w(TAG, "Failed to write export error log", t);
        }
    }

    /**
     * Cancel the current export.
     */
    public void cancel() {
        if (chunkRun != null) {
            // Chunked run: the parts and the sound live on workers. chunkCancelled also stops
            // a join already on its thread from committing (it was never set before).
            chunkCancelled = true;
            chunkRun = null;
            cancelChunkWorkers();
            if (isExporting) {
                isExporting = false;
                stopProgressPolling();
                discardStaging(currentStagingPath);
                FLog.d(TAG, "Chunked export cancelled");
            }
            return;
        }
        if (transformer != null && isExporting) {
            stopProgressPolling();
            transformer.cancel();
            isExporting = false;
            // FIX-4: cancel fires no listener callback — remove the staging file here,
            // or a cancelled export leaves a husk at the almost-final name.
            discardStaging(currentStagingPath);
            FLog.d(TAG, "Export cancelled");
        }
    }

    /**
     * Start periodic progress polling using Transformer.getProgress().
     * Media3 Transformer does not push progress via its Listener; it must be polled.
     */
    private void startProgressPolling() {
        progressHandler.removeCallbacksAndMessages(null);
        stallKey = Integer.MIN_VALUE;
        stallSinceMs = android.os.SystemClock.elapsedRealtime();
        lastStackDumpMs = 0L;
        progressHandler.postDelayed(progressPoller, PROGRESS_POLL_INTERVAL_MS);
        if (sampleGl) glSampler.start();
        if (sampleSound) soundSampler.start();
    }

    /** GL_SAMPLE lines: where the video thread's time goes, every 20 s of an export. */
    private final GlThreadSampler glSampler = new GlThreadSampler(this::trace);

    /**
     * STALL STACKS (2026-09-23). Every single-pass run of the 48-min project stopped at the
     * same point (~30:35) and all anyone ever got back was Media3's watchdog saying nothing
     * was written — never WHERE the pipeline was waiting. When progress has not moved for
     * 90 s, write every thread's stack to the durable trace (then at most every 5 min), so a
     * stall names its own frame. Costs nothing while progress moves.
     */
    private int stallKey = Integer.MIN_VALUE;
    private long stallSinceMs = 0L;
    private long lastStackDumpMs = 0L;
    private static final long STALL_STACKS_AFTER_MS = 90_000L;
    private static final long STALL_STACKS_REPEAT_MS = 300_000L;

    private void traceStacksIfStalled(int key) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (key != stallKey) {
            stallKey = key;
            stallSinceMs = now;
            return;
        }
        if (now - stallSinceMs < STALL_STACKS_AFTER_MS) return;
        if (lastStackDumpMs != 0L && now - lastStackDumpMs < STALL_STACKS_REPEAT_MS) return;
        lastStackDumpMs = now;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("STALL_STACKS no progress for ").append((now - stallSinceMs) / 1000L)
                    .append("s (state*1000+pct=").append(key).append(")\n");
            for (java.util.Map.Entry<Thread, StackTraceElement[]> e
                    : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                sb.append("  THREAD \"").append(t.getName()).append("\" ")
                        .append(t.getState()).append('\n');
                StackTraceElement[] st = e.getValue();
                for (int i = 0; i < st.length && i < 40; i++) {
                    sb.append("      at ").append(st[i]).append('\n');
                }
            }
            trace(sb.toString());
        } catch (Throwable t) {
            trace("STALL_STACKS failed: " + t);
        }
    }

    /** Stop polling for progress. */
    private void stopProgressPolling() {
        progressHandler.removeCallbacksAndMessages(null);
        glSampler.stop();
        soundSampler.stop();
    }

    /** FIX-6: composition ms the muxer had reached at the pace anchor, for ETA. */
    private long paceBaseCompMs = -1L;
    private long paceBaseWallMs = 0L;

    /**
     * Chunked-export state (2026-09-22): long timelines export as N sequential video
     * chunks (fresh decoder/encoder/muxer/GL per chunk — bounded sessions instead of
     * one 2-hour accumulation), one audio pass, then ffmpeg concat+mux. Sequential use
     * only. chunkBaseMs = absolute composition start of the chunk being built;
     * chunkVideoOnly strips all audio (the single audio pass covers it);
     * chunkClipStart/End bound the composition loop ([start, end), -1 = timeline end).
     * All zero/false when the legacy single-pass path runs — which is then
     * byte-identical to before.
     */
    private long chunkBaseMs = 0L;
    private boolean chunkVideoOnly = false;
    private int chunkClipStart = 0;
    private int chunkClipEnd = -1;

    private void resetChunkState() {
        chunkBaseMs = 0L;
        chunkVideoOnly = false;
        chunkClipStart = 0;
        chunkClipEnd = -1;
    }
    /**
     * 2026-09-22 MEM telemetry: the 12:17 trace showed throughput decaying 30x→0.14x
     * with the SoC COOLING (75°C→45°C) — starvation/leak, not heat. One MEM line per
     * minute names it: climbing native heap = leak (prime suspect: per-frame full-bitmap
     * copies in ImageOverlayFrameOverlay), flat heap + slow pace = blocked somewhere.
     */
    private long lastMemLogMs = 0L;

    private void traceMemIfDue() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastMemLogMs < 60_000L) return;
        lastMemLogMs = now;
        try {
            Runtime rt = Runtime.getRuntime();
            long dalvikUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long dalvikMaxMb = rt.maxMemory() / (1024 * 1024);
            long nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024);
            trace("MEM dalvik=" + dalvikUsedMb + "/" + dalvikMaxMb + "MB native="
                    + nativeMb + "MB threads=" + Thread.activeCount());
        } catch (Exception ignored) {}
    }

    /**
     * Durable per-export trace (2026-09-22): logcat rotates within the hour on this
     * phone, so a 2-hour export's PROBE/SEAM/PACE lines are gone before anyone reads
     * them. The lines that matter are mirrored to
     * {@code <external-files>/faditor_export_errors/export_trace_<ts>.txt} — same
     * folder as the error logs, pulled the same way. Best-effort: if the file can't
     * be opened, logging continues to logcat only.
     */
    @Nullable
    private java.io.PrintWriter traceWriter = null;
    private final Object traceLock = new Object();

    /** Open the durable trace; idempotent — the warm-phase probe may open it first. */
    public void openTrace(@NonNull String kind) {
        synchronized (traceLock) {
            if (traceWriter != null) return;
        }
        try {
            java.io.File dir = new java.io.File(context.getExternalFilesDir(null),
                    "faditor_export_errors");
            if (!dir.exists()) dir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(new java.util.Date());
            java.io.File f = new java.io.File(dir, "export_trace_" + ts + "_" + kind + ".txt");
            traceWriter = new java.io.PrintWriter(
                    new java.io.BufferedWriter(new java.io.FileWriter(f, true)), true);
            trace("TRACE_OPEN kind=" + kind + " file=" + f.getName());
        } catch (Exception e) {
            traceWriter = null;
        }
    }

    private void trace(@NonNull String line) {
        if (traceParent != null) {
            traceParent.trace(tracePrefix + line);
            return;
        }
        String stamped = new java.text.SimpleDateFormat("HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date()) + " " + line;
        FLog.i(TAG, line);
        synchronized (traceLock) {
            if (traceWriter != null) {
                try {
                    traceWriter.println(stamped);
                } catch (Exception ignored) {}
            }
        }
    }

    private void closeTrace() {
        if (traceParent != null) return;   // a worker never owns the driver's file
        synchronized (traceLock) {
            if (traceWriter != null) {
                try { traceWriter.close(); } catch (Exception ignored) {}
                traceWriter = null;
            }
        }
    }

    /**
     * 2026-09-22: read the hottest thermal zone (°C, one decimal) for the seam log.
     * The 30:35 stall was first blamed on heat with NO measurement; the owner reports
     * the phone stays barely warm. sysfs thermal zones are world-readable, so the
     * export now records the temperature it actually ran at — data, not adjectives.
     * Returns "?" when unreadable; never throws.
     */
    @NonNull
    private static String readThermalC() {
        try {
            java.io.File base = new java.io.File("/sys/class/thermal");
            String[] zones = base.list();
            int maxMilli = -1;
            if (zones != null) {
                for (String z : zones) {
                    if (!z.startsWith("thermal_zone")) continue;
                    java.io.BufferedReader br = null;
                    try {
                        br = new java.io.BufferedReader(new java.io.FileReader(
                                new java.io.File(base, z + "/temp")));
                        String v = br.readLine();
                        if (v != null) maxMilli = Math.max(maxMilli,
                                Integer.parseInt(v.trim()));
                    } catch (Exception ignored) {
                    } finally {
                        if (br != null) {
                            try { br.close(); } catch (Exception ignored) {}
                        }
                    }
                }
            }
            if (maxMilli < 0) return "?";
            return String.format(java.util.Locale.US, "%.1fC", maxMilli / 1000.0);
        } catch (Exception e) {
            return "?";
        }
    }

    /** Item index for a progress percent against the built composition map (-1 unknown). */
    private int currentItemForPct(int pct) {
        if (exportItemTotalMs <= 0 || exportItemStartMs.length == 0) return -1;
        long compMs = (long) pct * exportItemTotalMs / 100L;
        int cur = exportItemStartMs.length - 1;
        for (int i = 0; i < exportItemStartMs.length; i++) {
            if (compMs < exportItemStartMs[i]) {
                cur = Math.max(0, i - 1);
                break;
            }
        }
        return cur;
    }

    /** Runnable that periodically polls Transformer progress and forwards to listener. */
    private final Runnable progressPoller = new Runnable() {
        @Override
        public void run() {
            if (transformer == null || !isExporting) return;
            try {
                int state = transformer.getProgress(progressHolder);
                traceStacksIfStalled(state * 1000 + progressHolder.progress);
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    float progress = progressHolder.progress / 100f;
                    // EXPORT_PACE: the wall clock against the progress curve. Laid beside the
                    // EXPORT_ITEM boundaries above (same units), this names the slow item
                    // without guessing. One line per whole percent, so a fast export prints a
                    // hundred lines and a stalled one prints almost none — the silence is
                    // itself the signal.
                    int pct = progressHolder.progress;
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (progressEpochMs == 0L) progressEpochMs = now;
                    if (pct != lastLoggedProgressPct) {
                        trace("EXPORT_PACE " + pct + "% at +"
                                + ((now - progressEpochMs) / 1000L) + "s");
                        lastLoggedProgressPct = pct;
                    }
                    // ITEM-0: seam crossing — which composition item is the muxer in,
                    // and when did it get there. A stall prints its last seam, so the
                    // watchdog abort names the item instead of a bare percent.
                    int cur = currentItemForPct(pct);
                    if (cur >= 0 && cur != lastSeamItem) {
                        trace("EXPORT_SEAM entered item " + cur
                                + " of " + exportItemStartMs.length
                                + " (comp " + exportItemStartMs[cur] + "ms) at +"
                                + ((now - progressEpochMs) / 1000L) + "s"
                                + " temp=" + readThermalC());
                        lastSeamItem = cur;
                    }
                    traceMemIfDue();
                    if (listener != null) {
                        listener.onExportProgress(progress);
                        // FIX-6: pace-measured ETA — composition ms per wall second since
                        // the pace anchor. The only rate that survives Media3's curve.
                        long etaMs = -1L;
                        if (exportItemTotalMs > 0 && pct > 0) {
                            long compMs = (long) pct * exportItemTotalMs / 100L;
                            if (paceBaseCompMs < 0) {
                                paceBaseCompMs = compMs;
                                paceBaseWallMs = now;
                            } else if (now > paceBaseWallMs && compMs > paceBaseCompMs) {
                                double rate = (double) (compMs - paceBaseCompMs)
                                        / (double) (now - paceBaseWallMs);
                                if (rate > 0) {
                                    etaMs = (long) ((exportItemTotalMs - compMs) / rate);
                                }
                            }
                        }
                        long bytes = -1L;
                        try {
                            if (currentStagingPath != null) {
                                bytes = new java.io.File(currentStagingPath).length();
                            }
                        } catch (Exception ignored) {}
                        listener.onExportProgressDetailed(progress, cur,
                                exportItemStartMs.length, bytes, etaMs);
                    }
                }
                // Continue polling regardless of state (may become available later)
                progressHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS);
            } catch (Exception e) {
                FLog.w(TAG, "Progress poll error", e);
                // Keep polling in case of transient errors
                progressHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS);
            }
        }
    };

    // ── Internal ─────────────────────────────────────────────────────

    /**
     * SPEC_C_SINGLE_FRAME: the bookkeeping that lets {@link #exportSingleFrame} reuse
     * {@link #buildComposition} — THE one composer — for a single frame instead of
     * growing a second compositor.
     *
     * <p>Two passes over the same deterministic builder:</p>
     * <ol>
     *   <li><b>Record</b> ({@link #framePlan}): every emitted video item reports its
     *       composition start ({@code c0}), its media3-computed duration, and its EDITOR
     *       span ({@code e0}, {@code editorLenMs}). The item whose editor span contains
     *       the requested time is the covering item; the requested time maps into
     *       composition time by {@code c0 + (T - e0) * compLen / editorLen}. The ratio
     *       is 1 for every item except a transition item, whose composition length is
     *       the outgoing-leg only while its editor span also covers the incoming leg —
     *       exactly the compression LEDGER §2d documents.</li>
     *   <li><b>Clamp</b> ({@link #frameClamp}): the builder runs again, emitting ONLY the
     *       covering item, preceded by one black filler of exactly its composition start
     *       so every effect sees the same presentationTimeUs it would see in a full
     *       export, and with the covering item's source window END clamped to just past
     *       the requested frame. The clip's local-clock semantics (clipMsFor,
     *       editorTimeOffsetFor, caption fades) never see the truncation.</li>
     * </ol>
     */
    static final class FrameExportDirective {
        /** Pass-1 target: editor-timeline ms of the requested frame. */
        final long targetEditorMs;
        /** Pass-2: ordinal (emission order) of the covering item, as recorded in pass 1. */
        final int coveringIndex;
        /** Pass-2: composition start of the covering item == the lead filler's duration. */
        final long leadFillerMs;
        /** Pass-2: covering item's clamped composition length (target + lookahead). */
        final long clampLocalMs;
        /** Pass-2: the covering item's natural composition length (never clamp past it). */
        final long coveringNaturalCompMs;
        /** Number of items this directive has counted so far (both passes). */
        int itemOrdinal = 0;
        /** Clamp pass: true once the covering ordinal has actually been emitted. */
        boolean coveringEmitted = false;
        /** Clamp pass: true once the lead filler has been emitted. */
        boolean leadFillerEmitted = false;
        // Pass-1 recording state.
        private int bestIndex = -1;
        private long bestC0, bestE0, bestCompLen, bestEditorLen;
        private long bestTransitionLegMs = 0;
        private long lastEndCompMs = 0;
        private int lastOrdinal = -1;
        private boolean tailFillerLast = false;

        private FrameExportDirective(long targetEditorMs, int coveringIndex, long leadFillerMs,
                                     long clampLocalMs, long coveringNaturalCompMs) {
            this.targetEditorMs = targetEditorMs;
            this.coveringIndex = coveringIndex;
            this.leadFillerMs = leadFillerMs;
            this.clampLocalMs = clampLocalMs;
            this.coveringNaturalCompMs = coveringNaturalCompMs;
        }

        static FrameExportDirective framePlan(long targetEditorMs) {
            return new FrameExportDirective(targetEditorMs, -1, 0, 0, 0);
        }

        static FrameExportDirective frameClamp(int coveringIndex, long leadFillerMs,
                                               long clampLocalMs, long coveringNaturalCompMs) {
            return new FrameExportDirective(-1, coveringIndex, leadFillerMs, clampLocalMs,
                    coveringNaturalCompMs);
        }

        boolean isRecordPass() { return coveringIndex < 0; }
        boolean isClampPass() { return coveringIndex >= 0; }

        /** True when the item about to be built at the current site is the covering one. */
        boolean isCoveringItem() { return isClampPass() && itemOrdinal == coveringIndex; }

        /** Count a site that emits nothing in this pass (degenerate / skipped). */
        void skipSite() {
            if (isRecordPass()) {
                throw new IllegalStateException("skipSite outside clamp pass");
            }
            itemOrdinal++;
        }

        /**
         * Note one emitted video item. In the record pass this runs the covering-item
         * match; in the clamp pass it only keeps the ordinal in step with pass 1.
         *
         * @param c0          composition start of the item (the cursor before it)
         * @param compDurMs   the item's media3-computed duration
         * @param e0          the item's start in EDITOR-timeline ms
         * @param editorLenMs the item's span in EDITOR-timeline ms
         */
        void noteItem(long c0, long compDurMs, long e0, long editorLenMs) {
            noteItem(c0, compDurMs, e0, editorLenMs, 0L);
        }

        /**
         * Transition-item variant. {@code transitionLegMs} is the transition's effective
         * length, and it changes the TIME MAPPING, not just the span — see
         * {@link #coveringLocalTargetMs}.
         */
        void noteItem(long c0, long compDurMs, long e0, long editorLenMs, long transitionLegMs) {
            int ordinal = itemOrdinal++;
            if (isClampPass() && ordinal == coveringIndex) {
                coveringEmitted = true;
            }
            if (isRecordPass()) {
                long e1 = e0 + Math.max(1L, editorLenMs);
                if (e0 <= targetEditorMs && targetEditorMs < e1) {
                    // LAST match wins: at a seam the transition item is recorded after the
                    // main body it overlaps, and the transition is what the preview shows.
                    bestIndex = ordinal;
                    bestC0 = c0;
                    bestE0 = e0;
                    bestCompLen = Math.max(1L, compDurMs);
                    bestEditorLen = Math.max(1L, editorLenMs);
                    bestTransitionLegMs = Math.max(0L, transitionLegMs);
                }
                lastEndCompMs = c0 + Math.max(0L, compDurMs);
                lastOrdinal = ordinal;
            }
        }

        boolean foundCovering() { return bestIndex >= 0; }
        int coveringOrdinal() { return foundCovering() ? bestIndex : lastOrdinal; }
        long coveringNaturalCompMs() { return foundCovering() ? bestCompLen : Math.max(1L, lastEndCompMs); }
        long coveringLeadFillerMs() { return foundCovering() ? Math.max(0L, bestC0) : 0L; }
        /** Pass-1 record: the LAST emitted item was the tail filler. */
        void markTailFiller() { tailFillerLast = true; }
        boolean lastItemWasTailFiller() { return tailFillerLast; }
        boolean wasCoveringEmitted() { return coveringEmitted; }
        long coveringLocalTargetMs() {
            if (!foundCovering()) {
                // Requested time beyond every recorded span (e.g. the tail filler could not
                // be created): fall back to the last emitted frame rather than failing.
                return Math.max(0L, lastEndCompMs - 1L);
            }
            if (bestTransitionLegMs > 0L) {
                // ⚠ A TRANSITION ITEM'S CLOCK IS PIECEWISE, NOT A RATIO. The blend's
                // composition span is 1:1 with the editor's A-side region
                // [e0, e0+leg): at editor time T there, the file shows the blend at
                // progress (T - e0)/leg — the same blend the preview scrubs across the
                // outgoing clip's tail (FaditorEditorActivity.updateScrubTransitionPreview).
                // The incoming leg's head [e0+leg, e0+2·leg) does not exist as plain
                // timeline in the file — its content is the blend's own second half, so
                // editor time there maps to comp = T - leg (blend progress (T-e0-leg)/leg),
                // which is also exactly what the §2d clock (editorTimeOffsetMs) assigns.
                // A linear compLen/editorLen ratio would put a mid-blend playhead at HALF
                // the blend — a visibly half-faded frame where the playhead shows half.
                long leg = bestTransitionLegMs;
                long fromEditor = targetEditorMs - bestE0;
                if (fromEditor >= leg) fromEditor -= leg; // incoming-leg head → blend's second half
                long local = Math.round((double) fromEditor * bestCompLen
                        / (double) Math.max(1L, leg));
                return Math.max(0L, Math.min(bestCompLen - 1L, local));
            }
            long local = Math.round((targetEditorMs - bestE0) * (double) bestCompLen
                    / (double) bestEditorLen);
            return Math.max(0L, Math.min(bestCompLen - 1L, local));
        }
    }

    /** Composition ms of trailing coverage past the requested frame in a clamp pass. */
    private static final long FRAME_CLAMP_LOOKAHEAD_MS = 120L;

    @NonNull
    private Composition buildComposition(@NonNull FaditorProject project) {
        return buildComposition(project, null);
    }

    @NonNull
    private Composition buildComposition(@NonNull FaditorProject project,
                                         @Nullable FrameExportDirective frameDirective) {
        Timeline timeline = project.getTimeline();
        String canvasPreset = project.getCanvasPreset();
        int[] canvasDims = resolveCanvasDims(timeline, canvasPreset);
        // Export resolution cap (user setting). Composes with the canvas: the final
        // output is the canvas (or source) geometry scaled DOWN to fit the cap,
        // aspect preserved, never upscaled. Activating canvasDims here routes the
        // capped size through the exact same overlay-sizing + final-Presentation
        // machinery a canvas preset already exercises — one geometry authority.
        ExportSettings.Resolution exportRes = project.getExportSettings() != null
                ? project.getExportSettings().getResolution() : null;
        if (exportRes != null && exportRes != ExportSettings.Resolution.ORIGINAL) {
            int[] base = canvasDims != null ? canvasDims : inferSourceDims(timeline);
            int[] capped = capDimsToExportResolution(base, exportRes);
            if (base != null && capped != null
                    && (capped[0] != base[0] || capped[1] != base[1])) {
                canvasDims = capped;
                FLog.d(TAG, "Export resolution cap " + exportRes
                        + " → " + capped[0] + "x" + capped[1]);
            }
        }
        int outW = canvasDims != null ? canvasDims[0] : 0;
        int outH = canvasDims != null ? canvasDims[1] : 0;
        // For the "original" canvas preset we still need non-zero dimensions
        // for overlay bitmaps and transition ratio calculations.
        if ((outW <= 0 || outH <= 0) && timeline.getClipCount() > 0) {
            int[] inferred = inferSourceDims(timeline);
            if (inferred != null) {
                outW = inferred[0];
                outH = inferred[1];
            }
        }

        // Pre-load waveform data for all waveform overlays. The record pass only counts
        // items and editor spans — it never renders — so skip the audio-decode entirely.
        Map<String, WaveformData> waveformCache = new HashMap<>();
        if (measuringOnly) {
            // builtRangeDurationMs: lengths only, nothing is drawn - no audio analysis.
        } else if (frameDirective == null || !frameDirective.isRecordPass()) {
            Map<String, WaveformData> shared = sharedWaveformCache;
            waveformCache = preloadWaveformData(timeline,
                    shared != null ? shared : new HashMap<>());
            // ONCE PER EXPORT. The analysis decodes the WHOLE source (a 48-min lecture's
            // spectrum took minutes, on the main thread), and a chunked export builds a
            // composition per part plus one per part again to measure it: up to 14 full
            // analyses (2026-09-24, first long project with a visualizer). Chunk workers get
            // this map from their driver (newChunkWorker) and add only what they still miss.
            if (chunkRun != null || chunkVideoOnly) sharedWaveformCache = waveformCache;
        }

        // Pre-load waveform style presets
        List<WaveformStyle> builtinStyles = WaveformStyleIO.loadBuiltins(context);

        // Build per-clip composite overlays
        List<CompositeExportOverlay.WaveformSlot> waveformSlots =
                buildWaveformSlots(timeline, waveformCache, builtinStyles, outW, outH);

        // A6: determine project sample rate from the master track's first audio source.
        // If the master track has no audio, default to 48 kHz.
        int projectSampleRate = resolveProjectSampleRate(timeline);

        List<EditedMediaItem> items = new ArrayList<>();
        long timelineCursorMs = 0;
        // SPEC_C_SINGLE_FRAME: the EDITOR-timeline cursor — where the preview says clip ci
        // begins (sum of visual durations, transitions overlay the seam rather than removing
        // time). Recorded beside the composition cursor so a requested editor time can be
        // mapped onto the item that covers it (see FrameExportDirective).
        long editorCursorMs = 0;
        // Chunked export: clips before chunkClipStart are not emitted, but the editor
        // cursor (frameDirective spans; write-only without a directive) starts at their
        // true editor time for hygiene.
        if (frameDirective == null && chunkClipStart > 0) {
            for (int ci = 0; ci < chunkClipStart && ci < timeline.getClipCount(); ci++) {
                editorCursorMs += timeline.getClip(ci).getVisualDurationMs();
            }
        }
        // Lead filler for a clamp pass: emitted once, immediately before the covering item,
        // so the covering item starts at the same composition time it has in a full export.
        boolean leadFillerEmitted = false;

        // Chunked export: emit only this chunk's clip range. Transitions never straddle
        // a cut (the cutter forbids it), loop spans belong to their clip, so the body is
        // untouched — only the bounds move. Full range when chunkClipEnd < 0 (legacy).
        final int clipEndExclusive = chunkClipEnd < 0
                ? timeline.getClipCount() : Math.min(chunkClipEnd, timeline.getClipCount());
        for (int ci = Math.max(0, chunkClipStart); ci < clipEndExclusive; ci++) {
            Clip clip = timeline.getClip(ci);
            // SPEC_C_SINGLE_FRAME: editor-span inputs for this clip (record/clamp passes).
            long clipLoopBeforeMs = clip.hasLoopExtension() && !clip.isImageClip()
                    ? clip.getLoopBeforeMs() : 0L;
            long clipLoopAfterMs = clip.hasLoopExtension() && !clip.isImageClip()
                    ? clip.getLoopAfterMs() : 0L;
            boolean clipStillLoop = clip.getLoopMode() == Clip.LOOP_MODE_STILL;

            // Check for a transition at the seam AFTER this clip
            Transition trans = findTransitionAtSeam(timeline, ci);

            // Check for a transition at the seam BEFORE this clip
            Transition prevTrans = ci > 0 ? findTransitionAtSeam(timeline, ci - 1) : null;

            boolean hasTailTransition = trans != null;
            boolean hasHeadTransition = prevTrans != null;

            // ── Determine main clip range (possibly shortened by transitions) ──
            long clipInMs = clip.getInPointMs();
            long clipOutMs = clip.getOutPointMs();

            // If this clip is the SECOND clip in a transition, its head is overlapped.
            // Use the SEAM-clamped transition length (effectiveTransitionMs) so the
            // transition can never claim more of this (the incoming) clip than it has.
            if (hasHeadTransition) {
                long headTransMs = effectiveTransitionMs(timeline, prevTrans, ci - 1);
                long overlapSourceMs = Math.round(headTransMs * clip.getSpeedMultiplier());
                clipInMs = Math.min(clipOutMs, clipInMs + overlapSourceMs);
            }

            // If this clip is the FIRST clip in a transition, its tail is overlapped.
            // Same seam-clamp so the transition can never exceed this (the outgoing) clip.
            long mainOutMs = clipOutMs;
            if (hasTailTransition) {
                long tailTransMs = effectiveTransitionMs(timeline, trans, ci);
                long overlapSourceMs = Math.round(tailTransMs * clip.getSpeedMultiplier());
                mainOutMs = Math.max(clipInMs, clipOutMs - overlapSourceMs);
            }

            // A transition as long as (or longer than) the clip it straddles trims the
            // clip's un-transitioned body down to zero / a sub-frame sliver. Emitting
            // such a micro EditedMediaItem produces NO output sample and stalls the muxer
            // until its 10s watchdog aborts the whole export. Detect it and skip the main
            // item cleanly (below). Only clips actually touched by a transition can hit
            // this, so an ordinary standalone short clip keeps its exact prior behaviour.
            float clipSpeed = Math.max(0.1f, clip.getSpeedMultiplier());
            boolean touchesTransition = hasHeadTransition || hasTailTransition;
            long mainBodyTimelineMs = (long) ((mainOutMs - clipInMs) / clipSpeed);
            boolean mainBodyDegenerate = touchesTransition
                    && mainBodyTimelineMs < MIN_EXPORT_SEGMENT_MS;
            // SPEC_C_SINGLE_FRAME: the seam transitions' TIMELINE lengths, used only for the
            // item editor spans below (same clamp the trims above derive from).
            long effHeadTimelineMs = hasHeadTransition
                    ? effectiveTransitionMs(timeline, prevTrans, ci - 1) : 0L;
            long effTailTimelineMs = hasTailTransition
                    ? effectiveTransitionMs(timeline, trans, ci) : 0L;

            // ── Loop/ping-pong extensions BEFORE the main clip ──
            if (clip.hasLoopExtension() && !clip.isImageClip()) {
                long trimmedPlayMs = clip.getTrimmedDurationMs();
                long loopBeforeMs = clip.getLoopBeforeMs();

                if (loopBeforeMs > 0 && trimmedPlayMs > 0) {
                    int reps = (int) Math.ceil(loopBeforeMs / (double) trimmedPlayMs);
                    for (int r = 0; r < reps; r++) {
                        // SPEC_C_SINGLE_FRAME: this site emits an item in every pass iff
                        // buildLoopExtensionItem will (STILL mode emits only rep 0); the
                        // ordinal parity between passes depends on this exact condition.
                        boolean siteEmits = !(clipStillLoop && r > 0);
                        // Editor span of this rep (reps tile the extension head-first).
                        long extEditorStartMs = editorCursorMs
                                + (clipStillLoop ? 0L
                                        : Math.min((long) r * trimmedPlayMs, loopBeforeMs));
                        long extEditorLenMs = Math.max(1L,
                                loopBeforeMs - (extEditorStartMs - editorCursorMs));
                        long extMs = loopBeforeMs;
                        if (frameDirective != null && frameDirective.isClampPass()) {
                            if (!siteEmits) continue;
                            if (!frameDirective.isCoveringItem()) {
                                frameDirective.skipSite();
                                continue;
                            }
                            extMs = Math.min(loopBeforeMs, (long) r * trimmedPlayMs
                                    + Math.min(frameDirective.coveringNaturalCompMs,
                                            frameDirective.clampLocalMs));
                        }
                        EditedMediaItem extItem = buildLoopExtensionItem(project, clip,
                                extMs, trimmedPlayMs, reps, r,
                                timelineCursorMs, outW, outH, canvasDims,
                                waveformSlots, true);
                        if (extItem != null) {
                            if (frameDirective != null) {
                                frameDirective.noteItem(timelineCursorMs,
                                        extItem.durationUs / 1000,
                                        extEditorStartMs, extEditorLenMs);
                                emitLeadFillerIfNeeded(frameDirective, items);
                            }
                            items.add(extItem);
                            timelineCursorMs += extItem.durationUs / 1000;
                        } else if (frameDirective != null && frameDirective.isClampPass()
                                && siteEmits && frameDirective.isCoveringItem()) {
                            throw new IllegalStateException(
                                    "single-frame export: covering loop-extension item "
                                            + frameDirective.itemOrdinal
                                            + " could not be rebuilt");
                        }
                    }
                }
            }

            // ── Build the main clip item (the part NOT in the transition) ──
            // SPEC_C_SINGLE_FRAME: this site emits iff the condition below holds (same in
            // every pass — the degenerate decision depends only on clip data). In a clamp
            // pass the covering item's source window END is clamped to just past the frame
            // and the degenerate guard is bypassed (a deliberately short item is the point).
            boolean mainSiteEmits = mainOutMs > clipInMs && !mainBodyDegenerate;
            long mainEditorStartMs = editorCursorMs + clipLoopBeforeMs + effHeadTimelineMs;
            long mainEditorLenMs = Math.max(1L, (long) ((mainOutMs - clipInMs) / clipSpeed));
            if (frameDirective != null && frameDirective.isClampPass()) {
                if (!mainSiteEmits) {
                    // Pass 1 emitted nothing here either — keep the ordinal untouched.
                } else if (!frameDirective.isCoveringItem()) {
                    frameDirective.skipSite();
                    mainSiteEmits = false;
                } else {
                    long clampedWinMs = Math.min(frameDirective.coveringNaturalCompMs,
                            frameDirective.clampLocalMs);
                    mainOutMs = Math.min(mainOutMs,
                            clipInMs + Math.max(1L, Math.round(clampedWinMs * clipSpeed)));
                }
            }
            if (mainSiteEmits) {
                EditedMediaItem mainItem = buildClipItem(project, clip, clipInMs, mainOutMs,
                        timelineCursorMs, outW, outH, canvasDims,
                        waveformSlots, projectSampleRate);
                if (frameDirective != null) {
                    frameDirective.noteItem(timelineCursorMs, mainItem.durationUs / 1000,
                            mainEditorStartMs, mainEditorLenMs);
                    emitLeadFillerIfNeeded(frameDirective, items);
                }
                items.add(mainItem);
                timelineCursorMs += mainItem.durationUs / 1000;
            } else if (mainOutMs > clipInMs && (frameDirective == null || !frameDirective.isClampPass())) {
                // Degenerate body consumed by its transition overlap — skip it (do NOT
                // advance the cursor: it contributes ~0 to the timeline) so we never feed
                // the muxer a zero-sample item. The straddling transition item(s) already
                // cover this span.
                FLog.w(TAG, "buildComposition: skipping degenerate main item for clip " + ci
                        + " (body " + mainBodyTimelineMs + "ms < " + MIN_EXPORT_SEGMENT_MS
                        + "ms after transition trim) to avoid a no-output-sample muxer stall");
            }

            // ── Build the transition item (if there's a tail transition) ──
            if (hasTailTransition && ci + 1 < timeline.getClipCount()) {
                Clip nextClip = timeline.getClip(ci + 1);
                long transInMs = mainOutMs;
                long transOutMs = clipOutMs;
                long transTimelineMs = (long) ((transOutMs - transInMs) / clipSpeed);
                // SPEC_C_SINGLE_FRAME: emits iff the guard below holds (same every pass).
                boolean transSiteEmits = transOutMs > transInMs
                        && transTimelineMs >= MIN_EXPORT_SEGMENT_MS;
                // Editor span: the recorded span covers the outgoing clip's tail (the blend's
                // A-side, 1:1 with composition) AND the incoming clip's head (whose content
                // lives in the blend's second half). coveringLocalTargetMs maps the two
                // regions piecewise; see FrameExportDirective.noteItem(…, transitionLegMs).
                long transEditorStartMs = editorCursorMs + clipLoopBeforeMs
                        + clip.getTrimmedDurationMs() - effTailTimelineMs;
                long transEditorLenMs = Math.max(1L, 2L * effTailTimelineMs);
                if (frameDirective != null && frameDirective.isClampPass()) {
                    if (!transSiteEmits) {
                        // Pass 1 emitted nothing here either.
                    } else if (!frameDirective.isCoveringItem()) {
                        frameDirective.skipSite();
                        transSiteEmits = false;
                    } else {
                        // COVERING TRANSITION IS NOT WINDOW-CLAMPED. GlTransitionExportEffect
                        // takes the item's duration at BUILD time and drives the blend's
                        // progress from it (GlTransitionShaderProgram: progress = local /
                        // durationMs) — truncating the window would re-time the blend and
                        // hand back a different mix than the full export at the same moment.
                        // Transitions are seam-length by construction (≤ either clip), so
                        // emitting the full item costs nothing and keeps progress honest.
                        transSiteEmits = transOutMs > transInMs;
                    }
                }
                if (transSiteEmits) {
                    // Transition item uses the first clip's source (clipped to overlap) with GL effect
                    EditedMediaItem transItem = buildTransitionItem(project, clip, transInMs, transOutMs,
                            nextClip, trans, timelineCursorMs, outW, outH, canvasDims, waveformSlots);
                    if (transItem != null) {
                        if (frameDirective != null) {
                            frameDirective.noteItem(timelineCursorMs,
                                    transItem.durationUs / 1000,
                                    transEditorStartMs, transEditorLenMs,
                                    effTailTimelineMs);
                            emitLeadFillerIfNeeded(frameDirective, items);
                        }
                        items.add(transItem);
                        timelineCursorMs += transItem.durationUs / 1000;
                    } else if (frameDirective != null && frameDirective.isClampPass()
                            && frameDirective.isCoveringItem()) {
                        throw new IllegalStateException(
                                "single-frame export: covering transition item "
                                        + frameDirective.itemOrdinal + " could not be rebuilt");
                    }
                } else if (transOutMs > transInMs
                        && (frameDirective == null || !frameDirective.isClampPass())) {
                    // Sub-frame transition overlap (the outgoing clip was almost entirely
                    // consumed by its own head transition) — skip it rather than hand the
                    // muxer a zero-sample item. Cursor is not advanced.
                    FLog.w(TAG, "buildComposition: skipping degenerate transition item at seam "
                            + ci + " (" + transTimelineMs + "ms < " + MIN_EXPORT_SEGMENT_MS
                            + "ms) to avoid a no-output-sample muxer stall");
                }
            }

            // ── Loop/ping-pong extensions AFTER the main clip ──
            if (clip.hasLoopExtension() && !clip.isImageClip()) {
                long trimmedPlayMs = clip.getTrimmedDurationMs();
                long loopAfterMs = clip.getLoopAfterMs();

                if (loopAfterMs > 0 && trimmedPlayMs > 0) {
                    int reps = (int) Math.ceil(loopAfterMs / (double) trimmedPlayMs);
                    for (int r = 0; r < reps; r++) {
                        boolean siteEmits = !(clipStillLoop && r > 0);
                        long extEditorStartMs = editorCursorMs + clipLoopBeforeMs
                                + clip.getTrimmedDurationMs()
                                + (clipStillLoop ? 0L
                                        : Math.min((long) r * trimmedPlayMs, loopAfterMs));
                        long extEditorLenMs = Math.max(1L, loopAfterMs
                                - (extEditorStartMs - editorCursorMs - clipLoopBeforeMs
                                        - clip.getTrimmedDurationMs()));
                        long extMs = loopAfterMs;
                        if (frameDirective != null && frameDirective.isClampPass()) {
                            if (!siteEmits) continue;
                            if (!frameDirective.isCoveringItem()) {
                                frameDirective.skipSite();
                                continue;
                            }
                            extMs = Math.min(loopAfterMs, (long) r * trimmedPlayMs
                                    + Math.min(frameDirective.coveringNaturalCompMs,
                                            frameDirective.clampLocalMs));
                        }
                        EditedMediaItem extItem = buildLoopExtensionItem(project, clip,
                                extMs, trimmedPlayMs, reps, r,
                                timelineCursorMs, outW, outH, canvasDims,
                                waveformSlots, false);
                        if (extItem != null) {
                            if (frameDirective != null) {
                                frameDirective.noteItem(timelineCursorMs,
                                        extItem.durationUs / 1000,
                                        extEditorStartMs, extEditorLenMs);
                                emitLeadFillerIfNeeded(frameDirective, items);
                            }
                            items.add(extItem);
                            timelineCursorMs += extItem.durationUs / 1000;
                        } else if (frameDirective != null && frameDirective.isClampPass()
                                && siteEmits && frameDirective.isCoveringItem()) {
                            throw new IllegalStateException(
                                    "single-frame export: covering loop-after item "
                                            + frameDirective.itemOrdinal
                                            + " could not be rebuilt");
                        }
                    }
                }
            }

            // SPEC_C_SINGLE_FRAME: the editor cursor advances by the clip's full visual
            // span — the editor model (EditorTimelineView) plays clips back-to-back and
            // overlays the transition on the seam instead of removing time.
            editorCursorMs += clip.getVisualDurationMs();
        }

        // ── TAIL FILLER: a project can be LONGER than its master track ────────────────────────
        // Everything above builds the video sequence out of MASTER clips only, so the video
        // stream ends when they do. Anything living past that point — a PiP overlay, a text or
        // image overlay, a sprite, or simply audio — was silently dropped from the exported file
        // while the editor happily showed it. Measured on the sandbox 2026-07-30: video
        // duration 5.743s / 177 frames against an audio duration of 30.912s, i.e. 25 seconds of
        // the project missing, including a 5.6s PiP clip. See LEDGER "the export is PROVED".
        //
        // Overlays are composited per HOST CLIP (assembleClipVideoEffects runs off the item the
        // frame belongs to), so a span with no clip under it has nothing to draw them onto. The
        // fix is therefore a real item rather than a muxer duration hint: a black image clip for
        // the remainder, pushed through the SAME buildClipItem path so it picks up the overlay
        // pipeline exactly as any other clip does. That is also why this reuses the black spacer
        // the "Gap" feature already relies on — that feature is the existing proof that overlays
        // render correctly over a synthetic image clip.
        long projectTotalMs = timeline.getTotalDurationMs();
        // SPEC_C_SINGLE_FRAME: in a clamp pass the skipped items never advanced the cursor,
        // so the filler's length comes from the recorded pass-1 figure instead — and the
        // site's emit parity comes from the recorded flag, not from a recomputed tailMs.
        boolean clampPass = frameDirective != null && frameDirective.isClampPass();
        boolean fillerEmitHere = true;
        // Chunked export: timelineCursorMs restarts at 0 in every part, so the remainder has
        // to be measured from the part's ABSOLUTE end (chunkBaseMs + cursor). Measuring from
        // the chunk-relative cursor gave the last part of a 48-min project a 44-min black tail
        // (chunk6: 260,821 ms of content came out 2,907,125 ms long). chunkBaseMs is 0 on the
        // single-pass path, so this is byte-identical there.
        long tailMs = projectTotalMs - (chunkBaseMs + timelineCursorMs);
        if (clampPass) {
            if (!frameDirective.lastItemWasTailFiller()) {
                fillerEmitHere = false;   // pass 1 emitted no filler — this site must not either
            } else if (!frameDirective.isCoveringItem()) {
                frameDirective.skipSite();
                fillerEmitHere = false;
            } else {
                // Covering: clamp the natural (recorded) filler length to the frame.
                tailMs = Math.max(1L, Math.min(frameDirective.coveringNaturalCompMs,
                        frameDirective.clampLocalMs));
            }
        }
        // Chunked export: the tail filler (project remainder as black) belongs ONLY to
        // the last chunk — anywhere else it would append the rest of the timeline.
        boolean isLastChunk = chunkClipEnd < 0 || chunkClipEnd >= timeline.getClipCount();
        if (fillerEmitHere && isLastChunk && tailMs >= MIN_EXPORT_SEGMENT_MS) {
            Uri blackUri = ensureBlackFillerUri();
            if (blackUri != null) {
                Clip filler = new Clip(blackUri, tailMs);
                filler.setImageClip(true);
                filler.setAudioMuted(true);
                filler.setDisplayName("Tail filler"); // TODO(strings)
                EditedMediaItem fillItem = buildClipItem(project, filler, 0L, tailMs,
                        timelineCursorMs, outW, outH, canvasDims, waveformSlots, projectSampleRate);
                if (frameDirective != null) {
                    frameDirective.noteItem(timelineCursorMs, fillItem.durationUs / 1000,
                            editorCursorMs, Math.max(1L, tailMs));
                    frameDirective.markTailFiller();
                    emitLeadFillerIfNeeded(frameDirective, items);
                }
                items.add(fillItem);
                FLog.i(TAG, "buildComposition: project runs to " + projectTotalMs
                        + "ms but the master track ends at " + timelineCursorMs
                        + "ms — appended a " + tailMs + "ms black filler so overlays and audio"
                        + " past the last clip are still rendered");
                timelineCursorMs += fillItem.durationUs / 1000;
            } else {
                // Say so loudly rather than silently shipping a short file.
                FLog.w(TAG, "buildComposition: needed a " + tailMs + "ms tail filler but could"
                        + " not create the black spacer — the exported video will END EARLY at "
                        + timelineCursorMs + "ms while its audio runs to " + projectTotalMs + "ms");
            }
        }

        // AN IMAGE FIRST IN THE SPINE KILLED THE WHOLE EXPORT. media3 refuses a sequence that
        // begins with an item carrying no audio track and later reaches one that does, and it
        // names its own remedy in the thrown message:
        //
        //   "The preceding MediaItem does not contain any audio track. If the sequence starts
        //    with an item without audio track (like images), followed by items with audio
        //    tracks, then EditedMediaItemSequence.Builder.experimentalSetForceAudioTrack()
        //    needs to be set to true."
        //
        // Every export of such a project failed with a bare "Asset loader error" — JoyRaptor,
        // 2026-08-26, two attempts, structured record: cause=UNKNOWN clips=11 hasPip=true
        // hasText=true hasAudio=false. Starting a project with a title card or a photo is
        // completely ordinary, so this was not an edge case.
        //
        // Safe when it is not needed: the flag is documented as having NO EFFECT when the
        // first item already contains audio, so a project that opens on a video is unchanged.
        // It is only incompatible with setTransmuxAudio, which this exporter never calls
        // (checked: zero setTransmux* anywhere in export/).
        // WHICH ITEM IS SLOW. The progress curve is the only clock the export exposes, so
        // print the item boundaries in the SAME units and the two can be laid side by side.
        // JoyRaptor's Note 9 stalls between progress 0.29 and 0.37 -- 8% of the timeline eating
        // 80% of the wall time -- and neither the overlay pass (1-2ms/frame, measured) nor the
        // trailing black spacer is anywhere near there. Seven theories about this subsystem
        // have already died against this device; this one gets to name the item first.
        {
            long cum = 0L;
            for (int i = 0; i < items.size(); i++) {
                EditedMediaItem it = items.get(i);
                long durUs = it.durationUs;
                long durMs = durUs > 0 ? durUs / 1000L : -1L;
                String uri = it.mediaItem.localConfiguration != null
                        ? String.valueOf(it.mediaItem.localConfiguration.uri) : "?";
                // WHICH EFFECTS, not just how many. Each entry is a separate full-frame GL pass,
                // so the names are the per-frame cost of this item spelled out.
                StringBuilder fx = new StringBuilder();
                for (androidx.media3.common.Effect e : it.effects.videoEffects) {
                    if (fx.length() > 0) fx.append(',');
                    fx.append(e.getClass().getSimpleName());
                }
                // ITEM-0: explicit remux flag — tail-eyeballing "-remuxed-NNNN.mp4"
                // vs the original cost us a day on the 30:35 stall. The seam log below
                // + this flag settle "which file was actually read" without guessing.
                boolean isRemux = uri.contains("-remuxed-");
                // 2026-09-22: pre-trimmed windows carry in/out in the name — log it too.
                String srcTail = uri.substring(Math.max(0, uri.length() - 46));
                trace("EXPORT_ITEM[" + i + "] startMs=" + cum + " durMs=" + durMs
                        + " effects=" + it.effects.videoEffects.size()
                        + " [" + fx + "]"
                        + " remux=" + (isRemux ? "yes" : "no")
                        + " src=" + srcTail);
                if (durMs > 0) cum += durMs;
            }
                trace("EXPORT_ITEM total=" + cum + "ms across " + items.size() + " items");
            // ITEM-0: snapshot the boundaries for the seam-crossing log in the poller.
            long[] starts = new long[items.size()];
            long c2 = 0L;
            for (int i = 0; i < items.size(); i++) {
                starts[i] = c2;
                long d = items.get(i).durationUs > 0 ? items.get(i).durationUs / 1000L : 0L;
                c2 += d;
            }
            exportItemStartMs = starts;
            exportItemTotalMs = cum;
            lastSeamItem = -1;
        }
        EditedMediaItemSequence videoSequence =
                new EditedMediaItemSequence.Builder(items)
                        .experimentalSetForceAudioTrack(true)
                        .build();

        List<EditedMediaItemSequence> sequences = new ArrayList<>();
        sequences.add(videoSequence);

        // SPEC_C_SINGLE_FRAME: a clamp pass is video-only — audio lanes are irrelevant to
        // the frame and skipping them avoids decoding whole music sources for one picture.
        // M-EXPORT-2: overlay-VIDEO (PiP) clips are composited by CompositeExportOverlay
        // (the BitmapOverlay pass in assembleClipVideoEffects), NOT by a second video
        // sequence. Probe #3 (PLAN Part 10, verified against DefaultVideoCompositor
        // source): the compositor draws sequences back-to-front with the PRIMARY stream
        // ON TOP, so a secondary sequence composites the PiP UNDERNEATH the opaque
        // master — invisible. The overlay pass also keeps the PiP below text/captions,
        // matching the preview stack, which a second sequence never could.

        // Build audio sequences from AudioClips on the audio lanes (if any).
        // SPEC_C_SINGLE_FRAME: neither single-frame pass needs them — the frame's pixels
        // come from the video sequence, and skipping the lanes avoids decoding whole
        // music sources for one picture.
        // Chunked export: video chunks carry NO audio at all (the single audio pass
        // covers the whole timeline; joined later). Audio sequences would also decode
        // 48 minutes of music per chunk for nothing.
        if (frameDirective == null && !chunkVideoOnly && timeline.hasAudioClips()) {
            // A8: one sequence PER AUDIO LANE, mixed in parallel by the Composition.
            sequences.addAll(buildAudioSequences(timeline));
        }
        // SPEC_PIP_AUDIO: PiP audio rides its own audio-only sequence (the pixels come from
        // the overlay pass above). Null unless a PiP opted in → composition unchanged.
        if (frameDirective == null && !chunkVideoOnly) {
            EditedMediaItemSequence overlayAudio = buildOverlayAudioSequence(timeline, projectSampleRate);
            if (overlayAudio != null) {
                sequences.add(overlayAudio);
            }
        }

        return new Composition.Builder(withStereoOutput(sequences)).build();
    }

    /**
     * SPEC_C_SINGLE_FRAME: emits the one black filler that precedes the covering item in
     * a clamp pass, so presentationTimeUs inside the covering item is exactly what a full
     * export produces (the §2d effect-clock contract). Idempotent per pass.
     *
     * <p>The filler deliberately carries NO effects: its frames are pure padding and must
     * not spend the overlay pipeline (unlike the tail filler, whose frames ARE the
     * product). Throws rather than continuing without it — a missing pad would silently
     * shift the covering item's clock and return the wrong frame.</p>
     */
    private void emitLeadFillerIfNeeded(@NonNull FrameExportDirective frameDirective,
                                        @NonNull List<EditedMediaItem> items) {
        if (!frameDirective.isClampPass() || frameDirective.leadFillerEmitted
                || frameDirective.leadFillerMs <= 0L) {
            return;
        }
        Uri blackUri = ensureBlackFillerUri();
        if (blackUri == null) {
            throw new IllegalStateException(
                    "single-frame export: could not create the lead filler black spacer");
        }
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(blackUri)
                .setMimeType(com.fadcam.ui.faditor.util.ImageMime.of(context, blackUri))
                .setImageDurationMs(frameDirective.leadFillerMs)
                .build();
        EditedMediaItem.Builder eb = new EditedMediaItem.Builder(mediaItem);
        eb.setFrameRate(30);
        eb.setRemoveAudio(true);
        items.add(eb.build());
        frameDirective.leadFillerEmitted = true;
        FLog.i(TAG, "single-frame: lead filler " + frameDirective.leadFillerMs
                + "ms emitted so the covering item keeps its full-export clock");
    }

    @Nullable
    private static Transition findTransitionAtSeam(@NonNull Timeline timeline, int seam) {
        for (Transition t : timeline.getTransitions()) {
            if (t.clipIndex == seam) return t;
        }
        return null;
    }

    /**
     * Effective TIMELINE-ms length of the transition at {@code seam}, defensively clamped
     * so it can never exceed EITHER clip it straddles (the outgoing clip at {@code seam}
     * and the incoming clip at {@code seam + 1}). A transition longer than the clip it
     * hands off to would trim that clip's head — or the previous clip's tail — down to
     * (near) nothing, yielding a degenerate {@link EditedMediaItem} that emits no output
     * sample and stalls the muxer ("no output sample written in the last 10000 ms").
     *
     * <p>The clamp is a pure no-op for the normal case (transition shorter than both
     * clips), so byte-for-byte output is preserved there; only the pathological seam
     * where the transition meets or exceeds a clip is capped. Both the outgoing clip's
     * TAIL-trim iteration and the incoming clip's HEAD-trim iteration call this with the
     * SAME {@code seam}, so both derive their overlap from an identical clamped value and
     * stay in lock-step (no cursor desync).
     */
    /**
     * Audio-track duration (ms) of a source, probed once per export via MediaExtractor
     * and cached in {@link #sourceAudioDurMs}. Returns {@code 0} when the source has no
     * audio track at all (any window is "past audio"); {@link Long#MAX_VALUE} when the
     * track exists but reports no duration, or the source is unreadable — never strip
     * audio on a guess.
     */
    private long audioDurationMsOf(@NonNull Uri uri) {
        String key = uri.toString();
        Long cached = sourceAudioDurMs.get(key);
        if (cached != null) return cached;
        long result;
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            boolean hasAudio = false;
            long bestMs = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                hasAudio = true;
                if (f.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    bestMs = Math.max(bestMs, f.getLong(android.media.MediaFormat.KEY_DURATION) / 1000);
                }
            }
            result = !hasAudio ? 0L : (bestMs > 0 ? bestMs : Long.MAX_VALUE);
        } catch (Exception e) {
            result = Long.MAX_VALUE;
        } finally {
            extractor.release();
        }
        sourceAudioDurMs.put(key, result);
        return result;
    }

    /**
     * AUDIO CLOCK FIT (2026-09-25). Our recorders stamp each audio frame with the WALL CLOCK
     * ({@code RecordingClock.audioPtsUs}), and the audio hardware delivers slightly more
     * samples than wall time: the 48-min lecture's source holds ~3.1 s more sound than its
     * timestamps span. A player follows the timestamps, so preview stays in sync; Media3
     * strings decoded samples end to end (it pads a short item with silence, never trims a
     * long one), so the export's voice slid later clip after clip - lips and captions ~3 s
     * ahead of the sound by the end, and the file 3.1 s longer than its picture.
     *
     * <p>The fix is what the timestamps say: a clip's sound lasts exactly its span. This is
     * the Sonic speed that fits the frames {@code [inMs, outMs)} actually holds onto that
     * span; 1 when they already fit (any file whose timestamps come from its sample count -
     * mp3, other apps' AAC). Sonic at ~1.001 keeps the samples bit-exact and splices out one
     * pitch period every few seconds, so there is no pitch shift and no resampling blur.</p>
     */
    private float audioClockFit(@NonNull Uri uri, long inMs, long outMs) {
        AacIndex ix = aacIndexOf(uri);
        if (ix == null || outMs <= inMs) return 1f;
        long frameUs = 1024L * 1_000_000L / ix.sampleRate;
        long from = Math.max(inMs * 1000L, ix.pts[0]);
        long to = Math.min(outMs * 1000L, ix.pts[ix.pts.length - 1] + frameUs);
        if (to - from < 1_000_000L) return 1f;   // under a second: nothing to drift
        int a = lowerBound(ix.pts, inMs * 1000L), b = lowerBound(ix.pts, outMs * 1000L);
        double heldUs = (b - a) * 1024.0 * 1_000_000.0 / ix.sampleRate;
        double spanUs = to - from;
        if (Math.abs(heldUs - spanUs) < 20_000) return 1f;   // within a lip-sync frame
        float fit = (float) (heldUs / spanUs);
        // A clock runs a fraction of a percent off. Anything wilder is not drift (a broken
        // index, a variable frame size) - leave it as it was rather than guess.
        return fit < 0.97f || fit > 1.03f ? 1f : fit;
    }

    /** The user's speed times {@link #audioClockFit}, as one Sonic; null when it would be 1. */
    @Nullable
    private static SonicAudioProcessor speedAndFit(float speed, float fit) {
        float s = speed * fit;
        if (Math.abs(s - 1f) < 1e-5f) return null;
        SonicAudioProcessor sap = new SonicAudioProcessor();
        sap.setSpeed(s);   // pitch stays 1: time-stretch, not tape speed
        return sap;
    }

    /** Frame timestamps of a source's AAC track, read once from the file's index (no decode). */
    private static final class AacIndex {
        final long[] pts;
        final int sampleRate;

        AacIndex(long[] pts, int sampleRate) {
            this.pts = pts;
            this.sampleRate = sampleRate;
        }
    }

    /** Keyed by uri; a missing entry is "not yet read", {@link #NO_AAC_INDEX} "not applicable". */
    private static final java.util.concurrent.ConcurrentHashMap<String, AacIndex> aacIndexes =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final AacIndex NO_AAC_INDEX = new AacIndex(new long[0], 1);

    @Nullable
    private AacIndex aacIndexOf(@NonNull Uri uri) {
        String key = uri.toString();
        AacIndex cached = aacIndexes.get(key);
        if (cached != null) return cached == NO_AAC_INDEX ? null : cached;
        long t0 = System.currentTimeMillis();
        AacIndex result = NO_AAC_INDEX;
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        try {
            ex.setDataSource(context, uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat f = ex.getTrackFormat(i);
                if (!android.media.MediaFormat.MIMETYPE_AUDIO_AAC.equals(
                        f.getString(android.media.MediaFormat.KEY_MIME))) continue;
                // 1024 samples a frame holds for AAC Main/LC/LTP only; HE-AAC (SBR) doubles it.
                java.nio.ByteBuffer csd = f.getByteBuffer("csd-0");
                int aot = csd != null && csd.remaining() > 0 ? (csd.get(csd.position()) & 0xFF) >> 3 : 0;
                if ((aot != 1 && aot != 2 && aot != 4)
                        || !f.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) break;
                int rate = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                ex.selectTrack(i);
                long[] pts = new long[4096];
                int n = 0;
                long t;
                while ((t = ex.getSampleTime()) >= 0) {
                    if (n == pts.length) pts = java.util.Arrays.copyOf(pts, n * 2);
                    pts[n++] = t;
                    if (!ex.advance()) break;
                }
                if (n >= 2 && rate > 0) {
                    pts = java.util.Arrays.copyOf(pts, n);
                    java.util.Arrays.sort(pts);
                    result = new AacIndex(pts, rate);
                    double heldS = n * 1024.0 / rate;
                    double spanS = (pts[n - 1] - pts[0]) / 1e6 + 1024.0 / rate;
                    trace(String.format(Locale.US, "AUDIO_CLOCK %s: %d frames = %.3f s of sound"
                                    + " over %.3f s of timestamps (%+.4f%%), read in %d ms",
                            uri.getLastPathSegment(), n, heldS, spanS,
                            (heldS / spanS - 1) * 100, System.currentTimeMillis() - t0));
                }
                break;
            }
        } catch (Exception e) {
            FLog.w(TAG, "aacIndexOf: cannot read " + uri + " - no clock fit", e);
        } finally {
            ex.release();
        }
        aacIndexes.put(key, result);
        return result == NO_AAC_INDEX ? null : result;
    }

    /** First index whose value is >= v. */
    private static int lowerBound(long[] a, long v) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < v) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /**
     * Probe the sample rate of the first audio track in {@code uri}.
     * Returns 48000 (project default) if probing fails or no audio track found.
     */
    private int sampleRateOf(@NonNull Uri uri) {
        String key = uri.toString();
        Integer cached = sourceAudioSampleRate.get(key);
        if (cached != null) return cached;
        int result = 48000; // project default
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                if (f.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                    result = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                    break;
                }
            }
        } catch (Exception e) {
            // keep default
        } finally {
            extractor.release();
        }
        sourceAudioSampleRate.put(key, result);
        return result;
    }

    /**
     * Composition-time → editor-time correction for {@code clip} (LEDGER §2d).
     *
     * <p>The editor's timeline is a plain sum of clip spans and ignores transitions; the export
     * Composition is compressed by them. Overlays are authored against the former and rendered
     * against the latter, so without this every overlay after a seam lands early in the file by
     * the cumulative transition duration. Returns {@code editorStart - compressedStart}.</p>
     *
     * <p>Returns <b>0</b> when the clip is not on the master track or the project has no
     * transitions — so a project without transitions is bit-for-bit unaffected.</p>
     */
    /**
     * The transition consuming the HEAD of {@code clip} (0 if none) — see
     * {@code CompositeExportOverlay.headTransitionMs}.
     */
    private static long headTransitionMsFor(@NonNull Timeline timeline, @NonNull Clip clip) {
        int idx = -1;
        for (int i = 0; i < timeline.getClipCount(); i++) {
            if (timeline.getClip(i) == clip
                    || timeline.getClip(i).getId().equals(clip.getId())) { idx = i; break; }
        }
        if (idx <= 0) return 0L;
        for (Transition t : timeline.getTransitions()) {
            if (t.clipIndex == idx - 1) return effectiveTransitionMs(timeline, t, t.clipIndex);
        }
        return 0L;
    }

    /**
     * Instance version: the static computation PLUS this chunk's absolute base. Overlay
     * clocks are absolute (pts + offset must equal editor time), while the composition
     * cursor restarts at 0 per chunk — so chunked builds add chunkBaseMs here. Legacy
     * path: chunkBaseMs is 0, identical to the static result.
     */
    private long editorTimeOffsetForChunk(@NonNull Timeline timeline,
                                          @NonNull Clip clip,
                                          long compressedStartMs) {
        return editorTimeOffsetFor(timeline, clip, compressedStartMs) + chunkBaseMs;
    }

    private static long editorTimeOffsetFor(@NonNull Timeline timeline,
                                            @NonNull Clip clip,
                                            long compressedStartMs) {
        int idx = -1;
        for (int i = 0; i < timeline.getClipCount(); i++) {
            if (timeline.getClip(i) == clip
                    || timeline.getClip(i).getId().equals(clip.getId())) { idx = i; break; }
        }
        if (idx < 0) {
            // Not on the master track. The only such caller is the TAIL FILLER (:1055 builds a
            // Clip that is deliberately not in the timeline), which represents time PAST the
            // master track — i.e. after every transition has already been applied. Returning 0
            // here left the filler on raw composition time while every clip before it was on
            // editor time, so an overlay spanning the last-clip→filler seam jumped backwards by
            // the whole transition total. The correct extrapolation is that total.
            long all = 0L;
            for (Transition t : timeline.getTransitions()) {
                all += effectiveTransitionMs(timeline, t, t.clipIndex);
            }
            return all;
        }
        // ⚠ KNOWN LIMIT — this is a MODEL of the composition cursor, not the cursor itself.
        // It sums transitions, but the cursor is also compressed by DEGENERATE items that get
        // skipped without advancing it (a main body or transition item shorter than
        // MIN_EXPORT_SEGMENT_MS, :980-988 and :1004-1011). Every clip after such a skip drifts by
        // the un-modelled residual — bounded at 40ms per occurrence, so small, but real.
        //
        // EXACT FIX, when someone wants it: have buildComposition accumulate the true dropped
        // content as it emits (cursor delta per clip) and pass THAT in. Note this function already
        // takes compressedStartMs and never uses it — that parameter is the hook. Do not be
        // tempted by (editorStart - compressedStart); see immediately below for why that is zero
        // exactly where it matters.
        //
        // ⚠ NOT (editorStart - compressedStart). Measured on device 2026-08-03: for the clip
        // immediately AFTER a seam those two are EQUAL (both 3200 in the fixture), because a
        // transition does not push that clip later — it consumes 600ms off its HEAD by advancing
        // its in-point. Comparing starts therefore returns 0 for precisely the clip that needs the
        // correction most, and happens to be right for the one after it, which is what made the
        // first two attempts look plumbed-but-inert.
        //
        // The real quantity is the cumulative content the export has swallowed before this clip:
        // the sum of every transition at a seam BEFORE it.
        long off = 0L;
        for (Transition t : timeline.getTransitions()) {
            if (t.clipIndex < idx) off += effectiveTransitionMs(timeline, t, t.clipIndex);
        }
        return off;
    }


    private static long effectiveTransitionMs(@NonNull Timeline timeline,
                                              @NonNull Transition trans, int seam) {
        long d = Math.max(0L, trans.durationMs);
        if (seam >= 0 && seam < timeline.getClipCount()) {
            d = Math.min(d, transitionBudgetOnClip(timeline, trans, seam, /* clipIsOutgoing = */ true));
        }
        int next = seam + 1;
        if (next >= 0 && next < timeline.getClipCount()) {
            d = Math.min(d, transitionBudgetOnClip(timeline, trans, next, /* clipIsOutgoing = */ false));
        }
        return d;
    }

    /**
     * How much of the clip at {@code clipIndex} the transition {@code trans} may claim.
     * Normally the whole trimmed clip (the single-transition clamp). But when the SAME
     * clip is straddled by transitions on BOTH of its seams (e.g. a short slide with a
     * crossfade in and a radial out), the two would jointly overspend the clip and the
     * later one used to be degenerate-skipped in export while the preview drew both.
     * In that case each transition gets a share of the clip proportional to its
     * authored duration — both iterations (outgoing tail-trim and incoming head-trim)
     * compute from the same static timeline data, so they stay in lock-step.
     */
    private static long transitionBudgetOnClip(@NonNull Timeline timeline,
                                               @NonNull Transition trans,
                                               int clipIndex, boolean clipIsOutgoing) {
        long clipLen = Math.max(0L, timeline.getClip(clipIndex).getTrimmedDurationMs());
        // The transition on the clip's OTHER seam, if any: for a clip acting as the
        // outgoing side of `trans` (trans sits at seam == clipIndex), the other seam is
        // clipIndex - 1; for the incoming side (trans at seam == clipIndex - 1), it's
        // clipIndex.
        Transition other = findTransitionAtSeam(timeline, clipIsOutgoing ? clipIndex - 1 : clipIndex);
        if (other == null) return clipLen;
        long mine = Math.max(0L, trans.durationMs);
        long theirs = Math.max(0L, other.durationMs);
        if (mine + theirs <= clipLen || mine + theirs == 0) return clipLen;
        return Math.max(0L, (clipLen * mine) / (mine + theirs));
    }

    @Nullable
    private int[] resolveCanvasDims(@NonNull Timeline timeline,
                                      @NonNull String canvasPreset) {
        if ("original".equals(canvasPreset) || timeline.getClipCount() == 0) return null;
        int[] srcDims = inferSourceDims(timeline);
        if (srcDims == null) return null;
        return CanvasPickerBottomSheet.resolveCanvasDimensions(canvasPreset, srcDims[0], srcDims[1]);
    }

    /**
     * Scale {@code dims} down (never up) so its long/short edges fit within the
     * chosen resolution preset, aspect preserved, rounded to even. Orientation-aware:
     * portrait 1080p means 1080x1920. Returns {@code dims} unchanged when no cap
     * applies or the source is already within it.
     */
    @Nullable
    private static int[] capDimsToExportResolution(@Nullable int[] dims,
                                                   @Nullable ExportSettings.Resolution res) {
        if (dims == null || res == null || res == ExportSettings.Resolution.ORIGINAL) return dims;
        final int capLong;
        final int capShort;
        switch (res) {
            case FHD_1080P: capLong = 1920; capShort = 1080; break;
            case HD_720P:   capLong = 1280; capShort = 720;  break;
            case SD_480P:   capLong = 854;  capShort = 480;  break;
            default:        return dims;
        }
        int w = dims[0];
        int h = dims[1];
        if (w <= 0 || h <= 0) return dims;
        int longEdge = Math.max(w, h);
        int shortEdge = Math.min(w, h);
        float scale = Math.min(1f,
                Math.min((float) capLong / longEdge, (float) capShort / shortEdge));
        if (scale >= 1f) return dims; // already within the cap — never upscale
        int outW = Math.max(2, Math.round(w * scale / 2f) * 2);
        int outH = Math.max(2, Math.round(h * scale / 2f) * 2);
        return new int[]{outW, outH};
    }

    /**
     * Video bitrate (bps) for the project's chosen export {@link ExportSettings.Quality},
     * computed against the FINAL output dimensions (canvas/source after the resolution
     * cap) at a fixed 30fps reference. Returns 0 for HIGH (or when dimensions can't be
     * resolved), meaning "leave the encoder at its default" — the legacy path.
     */
    private int suggestedExportBitrate(@NonNull FaditorProject project) {
        ExportSettings settings = project.getExportSettings();
        if (settings == null) return 0;
        final float bitsPerPixel;
        switch (settings.getQuality()) {
            case MEDIUM: bitsPerPixel = 0.09f;  break;
            case LOW:    bitsPerPixel = 0.045f; break;
            default:     return 0; // HIGH → encoder default (legacy behavior)
        }
        Timeline timeline = project.getTimeline();
        int[] dims = resolveCanvasDims(timeline, project.getCanvasPreset());
        if (dims == null) dims = inferSourceDims(timeline);
        dims = capDimsToExportResolution(dims, settings.getResolution());
        if (dims == null || dims[0] <= 0 || dims[1] <= 0) return 0;
        return Math.max(500_000, Math.round(dims[0] * dims[1] * 30f * bitsPerPixel));
    }

    @Nullable
    private int[] inferSourceDims(@NonNull Timeline timeline) {
        for (Clip c : timeline.getClips()) {
            if (c.isImageClip()) continue;
            int w = getSourceWidth(c);
            int h = getSourceHeight(c);
            if (w > 0 && h > 0) return new int[]{w, h};
        }
        for (Clip c : timeline.getClips()) {
            if (!c.isImageClip()) continue;
            // Image clips don't have source dims; use a reasonable portrait default.
            return new int[]{1080, 1920};
        }
        return null;
    }


    /**
     * The shared 16x16 black PNG the tail filler references, created on first use.
     *
     * <p>Deliberately the SAME file the editor's "Gap" spacer uses
     * ({@code files/images/faditor_gap_black.png}, written by
     * {@code FaditorEditorActivity.ensureBlackSpacerUri}), so there is one black frame in the
     * project rather than two that could drift in size or colour. Either side may create it;
     * both write identical bytes, and the export must not depend on the editor having run first.
     * It lives in the same {@code images} directory as imported image assets so project bundling
     * and asset resolution treat it like any other image.</p>
     *
     * @return the file URI, or null if it could not be created — the caller must treat that as a
     *         loud failure rather than silently exporting a short file.
     */
    @Nullable
    private Uri ensureBlackFillerUri() {
        try {
            java.io.File dir = new java.io.File(context.getFilesDir(), "images");
            if (!dir.exists() && !dir.mkdirs()) {
                FLog.w(TAG, "ensureBlackFillerUri: could not create " + dir);
                return null;
            }
            java.io.File f = new java.io.File(dir, "faditor_gap_black.png");
            if (!f.exists() || f.length() == 0) {
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        16, 16, android.graphics.Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Studio.GROUND);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
                bmp.recycle();
            }
            return Uri.fromFile(f);
        } catch (Exception e) {
            FLog.e(TAG, "ensureBlackFillerUri failed", e);
            return null;
        }
    }

    @NonNull
    private EditedMediaItem buildClipItem(@NonNull FaditorProject project,
                                           @NonNull Clip clip,
                                           long clipInMs, long clipOutMs,
                                           long timelineCursorMs,
                                           int outW, int outH,
                                           @Nullable int[] canvasDims,
                                           @NonNull List<CompositeExportOverlay.WaveformSlot> waveformSlots,
                                           int projectSampleRate) {
        float speed = clip.getSpeedMultiplier();

        MediaItem mediaItem;
        long sourceDurationMs;
        if (clip.isImageClip()) {
            sourceDurationMs = Math.max(1L, clipOutMs - clipInMs);
            mediaItem = new MediaItem.Builder()
                    .setUri(clip.getSourceUri())
                    .setMimeType(com.fadcam.ui.faditor.util.ImageMime.of(context, clip.getSourceUri()))
                    .setImageDurationMs(sourceDurationMs)
                    .build();
        } else {
            long endMs = Math.min(clipOutMs, clip.getSourceDurationMs());
            sourceDurationMs = Math.max(1L, endMs - clipInMs);
            MediaItem.ClippingConfiguration clipping =
                    new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clipInMs)
                            .setEndPositionMs(endMs)
                            .build();
            mediaItem = new MediaItem.Builder()
                    .setUri(resolveSeekableSourceUri(clip))
                    .setClippingConfiguration(clipping)
                    .build();
        }

        EditedMediaItem.Builder editedBuilder = new EditedMediaItem.Builder(mediaItem);
        if (clip.isImageClip()) editedBuilder.setFrameRate(30);
        // Chunked export: video chunks drop all master audio (single audio pass later).
        boolean dropAudio = clip.isAudioMuted() || clip.isImageClip() || chunkVideoOnly;
        // A clipped window that starts past the end of the source's AUDIO track yields
        // zero audio samples → AudioGraph stall → watchdog "no output sample" abort.
        // Make such items video-only instead (arises at transition-trimmed tails and
        // deep end-trims; the visual output is identical, the audio there never existed).
        if (!dropAudio && clipInMs >= audioDurationMsOf(resolveSeekableSourceUri(clip))
                - AUDIO_COVERAGE_EPS_MS) {
            dropAudio = true;
            FLog.w(TAG, "buildClipItem: window " + clipInMs + "ms+ is past the source's"
                    + " audio end — emitting video-only item (prevents muxer stall)");
        }
        if (dropAudio) editedBuilder.setRemoveAudio(true);

        // Explicit timeline duration so callers can advance the composition cursor
        // without relying on EditedMediaItem.durationUs (unset for video items).
        long timelineDurationMs = Math.max(1L,
                (long) (sourceDurationMs / Math.max(0.1f, speed)));
        editedBuilder.setDurationUs(timelineDurationMs * 1000);

        List<AudioProcessor> audioProcessors = new ArrayList<>();
        float volume = clip.getVolumeLevel();

        if (!dropAudio) {
            // A6: resample to project sample rate if needed (before speed/volume).
            int clipSampleRate = sampleRateOf(resolveSeekableSourceUri(clip));
            if (clipSampleRate != projectSampleRate) {
                audioProcessors.add(new ResamplingAudioProcessor(clipSampleRate, projectSampleRate));
                FLog.d(TAG, "A6: clip " + clip.getId() + " resampled " + clipSampleRate + " → " + projectSampleRate + " Hz");
            }
            SonicAudioProcessor sonicProcessor = speedAndFit(speed, audioClockFit(
                    resolveSeekableSourceUri(clip), clipInMs, clipInMs + sourceDurationMs));
            if (sonicProcessor != null) audioProcessors.add(sonicProcessor);
            VolumeAudioProcessor volumeProcessor = new VolumeAudioProcessor();
            boolean volumeAdjusted = false;
            if (clip.hasVolumeKeyframes()) {
                @SuppressWarnings("unchecked")
                List<Clip.VolumeKeyframe> kfs = (List<Clip.VolumeKeyframe>) clip.getVolumeKeyframes();
                long[] times = new long[kfs.size()];
                float[] vols = new float[kfs.size()];
                for (int i = 0; i < kfs.size(); i++) {
                    times[i] = kfs.get(i).timeMs;
                    vols[i] = kfs.get(i).volume;
                }
                // B1.Q: multipliers need their base — see the master-sequence site.
                volumeProcessor.setVolume(volume);
                volumeProcessor.setVolumeEnvelope(times, vols);
                volumeAdjusted = true;
            } else if (Math.abs(volume - 1.0f) >= 0.01f) {
                volumeProcessor.setVolume(volume);
                volumeAdjusted = true;
            }
            // NOTE: do NOT gate on volumeProcessor.isActive() here — BaseAudioProcessor
            // only becomes "active" after the Transformer pipeline calls configure(),
            // which has not happened at composition-build time. isActive() is therefore
            // always false here, so the old gate silently dropped EVERY clip's volume
            // (static and keyframed) from the export. Add based on whether we set work.
            if (volumeAdjusted) {
                audioProcessors.add(volumeProcessor);
            }
            // C1.E: real-time FX chain — same factory the preview uses, so what the
            // user hears while editing is what lands in the file. The C7 bypass flag is
            // SNAPSHOT here (once per composition build): export runs in a service, so
            // it must never read a UI static mid-flight. The voice chain itself is
            // PER-CLIP now: only a clip whose own toggle asks for it is processed.
            AudioFxChainFactory.addTo(audioProcessors, clip, fxBypassedSnapshot,
                    clip.isVoiceFxEnabled(), projectSampleRate);
        }

        List<Effect> videoEffects = assembleClipVideoEffects(
                clip, project, timelineCursorMs, outW, outH,
                canvasDims, waveformSlots,
                /* isTransitionItem = */ false,
                /* preOverlayExtra = */ null);

        if (!audioProcessors.isEmpty() || !videoEffects.isEmpty()) {
            editedBuilder.setEffects(new Effects(audioProcessors, videoEffects));
        }

        return editedBuilder.build();
    }

    @Nullable
    private EditedMediaItem buildTransitionItem(@NonNull FaditorProject project,
                                                 @NonNull Clip clip,
                                                 long transInMs, long transOutMs,
                                                 @NonNull Clip nextClip,
                                                 @NonNull Transition transition,
                                                 long timelineCursorMs,
                                                 int outW, int outH,
                                                 @Nullable int[] canvasDims,
                                                 @NonNull List<CompositeExportOverlay.WaveformSlot> waveformSlots) {
        long sourceDur = transOutMs - transInMs;
        if (sourceDur <= 0) return null;

        float speed = clip.getSpeedMultiplier();
        long timelineDurMs = Math.max(1L, (long) (sourceDur / Math.max(0.1f, speed)));

        // When the OUTGOING clip of a transition is an image, it must be built via the
        // image pipeline (setImageDurationMs) — NOT as a clipped progressive media item.
        // A ClippingConfiguration forces Media3 to read the source with extractors, and a
        // JPEG/PNG cannot be read as a seekable video stream, so the export aborts with
        // UnrecognizedInputFormatException ("Source error") the moment Media3 prepares the
        // transition item. Mirror buildClipItem's image branch here.
        MediaItem mediaItem;
        EditedMediaItem.Builder eb;
        if (clip.isImageClip()) {
            mediaItem = new MediaItem.Builder()
                    .setUri(clip.getSourceUri())
                    .setMimeType(com.fadcam.ui.faditor.util.ImageMime.of(context, clip.getSourceUri()))
                    .setImageDurationMs(timelineDurMs)
                    .build();
            eb = new EditedMediaItem.Builder(mediaItem);
            eb.setFrameRate(30);
            eb.setRemoveAudio(true);
        } else {
            long endMs = Math.min(transOutMs, clip.getSourceDurationMs());
            mediaItem = new MediaItem.Builder()
                    .setUri(resolveSeekableSourceUri(clip))
                    .setClippingConfiguration(
                            new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(transInMs)
                                    .setEndPositionMs(endMs)
                                    .build())
                    .build();
            eb = new EditedMediaItem.Builder(mediaItem);
        }
        eb.setDurationUs(timelineDurMs * 1000);

        Effect glTrans = null;
        try {
            glTrans = new GlTransitionExportEffect(
                    context, transition, nextClip,
                    timelineDurMs, timelineCursorMs, canvasDims, nextClip.getSourceUri(),
                    outW, outH);
        } catch (Exception e) {
            FLog.w(TAG, "Failed to create transition effect", e);
        }

        List<Effect> videoEffects = assembleClipVideoEffects(
                clip, project, timelineCursorMs, outW, outH,
                canvasDims, waveformSlots,
                /* isTransitionItem = */ true,
                /* preOverlayExtra = */ glTrans);

        // Audio: mirror the outgoing clip's mute/volume. Previously the transition
        // segment carried the clip's ORIGINAL audio at full volume — so a muted clip
        // (e.g. one relying on the music track) had its sound briefly return during
        // the ~600ms transition overlap. Match buildClipItem's behaviour.
        List<AudioProcessor> aps = new ArrayList<>();
        if (clip.isImageClip() || clip.isAudioMuted()
                // Past-audio-end window (same stall class as buildClipItem): a transition
                // riding the very tail of a source whose audio track ends early would
                // emit zero audio samples and wedge the AudioGraph.
                || transInMs >= audioDurationMsOf(resolveSeekableSourceUri(clip))
                        - AUDIO_COVERAGE_EPS_MS) {
            eb.setRemoveAudio(true);
        } else {
            SonicAudioProcessor sap = speedAndFit(speed,
                    audioClockFit(resolveSeekableSourceUri(clip), transInMs,
                            Math.min(transOutMs, clip.getSourceDurationMs())));
            if (sap != null) aps.add(sap);
            float vol = clip.getVolumeLevel();
            if (Math.abs(vol - 1.0f) >= 0.01f) {
                VolumeAudioProcessor vp = new VolumeAudioProcessor();
                vp.setVolume(vol);
                aps.add(vp);
            }
        }

        if (!aps.isEmpty() || !videoEffects.isEmpty()) {
            eb.setEffects(new Effects(aps, videoEffects));
        }

        return eb.build();
    }

    @Nullable
    private EditedMediaItem buildLoopExtensionItem(@NonNull FaditorProject project,
                                                    @NonNull Clip clip,
                                                    long extensionMs,
                                                    long trimmedPlayMs,
                                                    int totalReps, int repIndex,
                                                    long timelineCursorMs,
                                                    int outW, int outH,
                                                    @Nullable int[] canvasDims,
                                                    @NonNull List<CompositeExportOverlay.WaveformSlot> waveformSlots,
                                                    boolean isBefore) {
        if (trimmedPlayMs <= 0) return null;

        int loopMode = clip.getLoopMode();

        // STILL mode renders a single still image for the entire extension.
        // Only the first rep (repIndex == 0) produces an item; subsequent reps
        // are skipped by returning null so the outer loop doesn't add empty
        // items to the timeline.
        if (loopMode == Clip.LOOP_MODE_STILL) {
            if (repIndex != 0) return null;
            if (extensionMs <= 0) return null;
            return buildStillLoopExtensionItem(project, clip, extensionMs, isBefore,
                    timelineCursorMs, outW, outH, canvasDims, waveformSlots);
        }

        // Compute this rep's actual played duration (clamped so the sum of all
        // rep durations equals exactly `extensionMs`).
        long playedMs = Math.min(trimmedPlayMs,
                extensionMs - (long) repIndex * trimmedPlayMs);
        if (playedMs <= 0) return null;

        // PARKED (Clip.PING_PONG_PARKED): while ping-pong is dormant, a PING_PONG clip exports as a
        // plain forward NORMAL-loop wrap — same graceful degrade as preview. Forcing reverse=false
        // routes every rep through the forward head-replay branch below, so export matches the
        // parked preview by construction and never references a baked reversed file.
        boolean reverse = !Clip.PING_PONG_PARKED
                && loopMode == Clip.LOOP_MODE_PING_PONG
                && ((isBefore ? totalReps - 1 - repIndex : repIndex) % 2 == 1);

        // Choose the source sub-range so playback length matches `playedMs`.
        float speed = clip.getSpeedMultiplier();
        long inPointMs = clip.getInPointMs();
        long outPointMs = clip.getOutPointMs();
        long sourceSpanMs = Math.max(1L, (long) (playedMs * speed));

        // L2: a reverse leg plays the SAME baked-reversed file the preview engine uses, mapped with
        // the identical coordinates → preview==export by construction. Baked-file time t ↔ source
        // outPoint-t, so the forward source range [inPoint, inPoint+span] maps to reversed-file
        // range [ (out-in)-span, (out-in) ] (== revStart = outPoint-endMs, revEnd = outPoint-startMs
        // minus the bake's inPoint offset). If NO bake exists (guard: span too long, or a warm
        // failure) we fall back to a plain FORWARD head replay [inPoint, inPoint+span] with NO
        // mirror — matching exactly what the (forward-tail) preview shows in that same un-baked case
        // (the old setScale(-1) horizontal-mirror stand-in is GONE — it never matched preview).
        android.net.Uri reversedUri = reverse ? resolveReversedFileUri(clip) : null;
        android.net.Uri itemUri;
        long startMs;
        long endMs;
        if (reverse && reversedUri != null) {
            long revLen = outPointMs - inPointMs; // reversed file's own duration
            itemUri = reversedUri;
            startMs = Math.max(0L, revLen - sourceSpanMs);
            endMs = revLen;
        } else {
            // Forward leg, or reverse leg with no bake → forward head replay.
            itemUri = resolveSeekableSourceUri(clip);
            startMs = inPointMs;
            endMs = Math.min(outPointMs, inPointMs + sourceSpanMs);
        }

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(itemUri)
                .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(endMs)
                                .build())
                .build();

        EditedMediaItem.Builder eb = new EditedMediaItem.Builder(mediaItem);
        eb.setDurationUs(Math.max(1L, playedMs) * 1000);
        if (clip.isAudioMuted() || clip.isImageClip()) eb.setRemoveAudio(true);

        List<AudioProcessor> audioProcessors = new ArrayList<>();
        if (!clip.isAudioMuted()) {
            SonicAudioProcessor sap = speedAndFit(speed, audioClockFit(itemUri, startMs, endMs));
            if (sap != null) audioProcessors.add(sap);
        }

        // No reverse-mirror effect: a true reverse leg comes pre-reversed (video AND areverse'd
        // audio) from the baked file, and the un-baked fallback is plain forward. Effects (overlay,
        // opacity, presentation, crop, color) apply normally on top.
        List<Effect> videoEffects = assembleClipVideoEffects(
                clip, project, timelineCursorMs, outW, outH,
                canvasDims, waveformSlots,
                /* isTransitionItem = */ false,
                /* preOverlayExtra = */ null,
                /* isLoopBeforeItem = */ isBefore);

        if (!audioProcessors.isEmpty() || !videoEffects.isEmpty()) {
            eb.setEffects(new Effects(audioProcessors, videoEffects));
        }
        return eb.build();
    }

    /**
     * Build a single {@link EditedMediaItem} that displays a frozen frame of
     * the source video for the full extension duration. The frame is taken
     * from the clip's first frame for a BEFORE extension (so playback can
     * "enter" the clip) and from the last frame for an AFTER extension (so
     * the clip's exit freezes in place).
     *
     * <p>The frame is extracted once as a JPEG in the export cache and reused
     * across subsequent exports.</p>
     */
    @Nullable
    private EditedMediaItem buildStillLoopExtensionItem(@NonNull FaditorProject project,
                                                         @NonNull Clip clip,
                                                         long extensionMs,
                                                         boolean isBefore,
                                                         long timelineCursorMs,
                                                         int outW, int outH,
                                                         @Nullable int[] canvasDims,
                                                         @NonNull List<CompositeExportOverlay.WaveformSlot> waveformSlots) {
        Uri stillUri = extractStillFrameForLoop(clip, isBefore);
        if (stillUri == null) {
            FLog.w(TAG, "STILL loop extension: failed to extract still frame; skipping");
            return null;
        }

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(stillUri)
                .setImageDurationMs(extensionMs)
                .build();

        EditedMediaItem.Builder eb = new EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(true)
                .setFrameRate(30)
                .setDurationUs(Math.max(1L, extensionMs) * 1000);

        // The clip here is the original source video (used to read opacity
        // envelopes, captions, and the in-point), but the MediaItem is the still
        // image. Media3 applies Speed/Scale/Crop to image MediaItems correctly,
        // so passing the original clip's properties through the canonical
        // helper is the right call — a still-loop extension honors the user's
        // speed/rotate/crop/captions/text exactly like a video extension would.
        List<Effect> videoEffects = assembleClipVideoEffects(
                clip, project, timelineCursorMs, outW, outH,
                canvasDims, waveformSlots,
                /* isTransitionItem = */ false,
                /* preOverlayExtra = */ null,
                /* isLoopBeforeItem = */ isBefore);

        if (!videoEffects.isEmpty()) {
            eb.setEffects(new Effects(Collections.emptyList(), videoEffects));
        }
        return eb.build();
    }

    /**
     * Extract a single frame of {@code clip}'s source as a JPEG in the export
     * cache and return its file URI. Uses the first frame for a BEFORE
     * extension and the last frame for an AFTER extension. The result is
     * cached on disk so repeated exports don't re-decode the same frame.
     */
    @Nullable
    private Uri extractStillFrameForLoop(@NonNull Clip clip, boolean isBefore) {
        // A clip with no source URI cannot yield a still frame; bail cleanly so the
        // caller skips this loop extension instead of throwing inside the retriever.
        if (clip.getSourceUri() == null) {
            FLog.w(TAG, "STILL loop extension: clip has null source URI; skipping");
            return null;
        }
        long sourceDurationMs = Math.max(1L, clip.getSourceDurationMs());
        long sourceMs;
        if (isBefore) {
            sourceMs = Math.max(0L, clip.getInPointMs());
        } else {
            sourceMs = Math.max(0L,
                    Math.min(sourceDurationMs - 1L, clip.getOutPointMs() - 1L));
        }

        File cacheDir = new File(context.getCacheDir(), "faditor_export");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            FLog.w(TAG, "STILL loop extension: cannot create cache dir for still frame");
            return null;
        }
        File frameFile = new File(cacheDir, "loop_still_" + clip.getId()
                + (isBefore ? "_first" : "_last") + ".jpg");
        if (frameFile.exists() && frameFile.length() > 0) {
            return Uri.fromFile(frameFile);
        }

        Bitmap frame = null;
        try {
            // Reuse the thread-local retriever instead of creating a new one per still frame.
            setRetrieverDataSource(clip.getSourceUri());
            android.media.MediaMetadataRetriever mmr = acquireRetriever();
            frame = mmr.getFrameAtTime(sourceMs * 1000L,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                FLog.w(TAG, "STILL loop extension: MediaMetadataRetriever returned null frame at "
                        + sourceMs + "ms");
                return null;
            }
            try (FileOutputStream fos = new FileOutputStream(frameFile)) {
                frame.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            }
            return Uri.fromFile(frameFile);
        } catch (Exception e) {
            FLog.w(TAG, "STILL loop extension: failed to extract still frame", e);
            return null;
        } finally {
            if (frame != null) frame.recycle();
        }
    }

    @NonNull
    private Map<String, WaveformData> preloadWaveformData(@NonNull Timeline timeline,
                                                          @NonNull Map<String, WaveformData> cache) {
        return preloadWaveformData(timeline, cache, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /** Visualizers whose span meets editor [from, to] only (the parts being rendered). */
    private Map<String, WaveformData> preloadWaveformData(@NonNull Timeline timeline,
                                                          @NonNull Map<String, WaveformData> cache,
                                                          long from, long to) {
        FLog.d(TAG, "preloadWaveformData: waveformOverlayCount="
                + timeline.getWaveformOverlays().size()
                + " hasAny=" + timeline.hasWaveformOverlays());
        if (!timeline.hasWaveformOverlays()) return cache;
        WaveformExtractor extractor = new WaveformExtractor(context);
        // A part of a chunked export analyses only the visualizers on screen during it (editor
        // time, padded): the analysis decodes a whole source, minutes for a long lecture.
        long partFrom = from, partTo = to;
        if (chunkClipEnd >= 0 && from == Long.MIN_VALUE && to == Long.MAX_VALUE) {
            long ed = 0L;
            for (int i = 0; i < chunkClipStart && i < timeline.getClipCount(); i++) {
                ed += timeline.getClip(i).getVisualDurationMs();
            }
            long len = 0L;
            for (int i = chunkClipStart; i < chunkClipEnd && i < timeline.getClipCount(); i++) {
                len += timeline.getClip(i).getVisualDurationMs();
            }
            partFrom = ed - 10_000L;
            partTo = ed + len + 10_000L;
        }
        // ONE ANALYSIS PER SOURCE, OVER THE SPAN THE EDITOR USES. The editor analyses a
        // visualizer's source over its clip's trim window (in - 1.2 s .. out + 0.2 s, keyed on
        // the raw file) and caches it; asking for the whole file under another key missed that
        // cache and analysed all 48 minutes for a 7-minute visualizer (30+ min on the little
        // cores, 2026-09-24). Several visualizers on one source share the union of their spans.
        Map<String, long[]> spans = new java.util.LinkedHashMap<>();
        Map<String, Uri> keyUris = new HashMap<>();
        for (WaveformOverlayInstance woi : LayerPreviewController.visibleWaveformOverlays(timeline)) { // §4.5 per-object eye
            if (woi.getEndMs() < partFrom || woi.getStartMs() > partTo) continue;
            String clipId = woi.getAudioSourceRef();
            if (clipId == null) continue;
            android.net.Uri uri = resolveWaveformUri(timeline, clipId);
            if (uri == null) {
                FLog.w(TAG, "preloadWaveformData: no clip with id " + clipId
                        + " for waveform " + woi.getId());
                continue;
            }
            long in, out;
            Clip sc = findClipById(timeline, clipId);
            AudioClip sa = sc == null ? findAudioClipById(timeline, clipId) : null;
            if (sc != null) {
                in = sc.getInPointMs();
                out = sc.getOutPointMs();
            } else if (sa != null) {
                in = sa.getInPointMs();
                out = sa.getOutPointMs();
            } else {
                continue;
            }
            String key = uri.toString();
            if (cache.containsKey(key)) continue;
            long[] span = spans.get(key);
            long s0 = Math.max(0, in - 1200), s1 = out + 200;
            if (span == null) {
                spans.put(key, new long[]{s0, s1});
            } else {
                span[0] = Math.min(span[0], s0);
                span[1] = Math.max(span[1], s1);
            }
            // The editor's key: the raw FILE's Uri.fromFile form.
            keyUris.put(key, "file".equals(uri.getScheme()) && uri.getPath() != null
                    ? android.net.Uri.fromFile(new File(uri.getPath())) : uri);
        }
        for (Map.Entry<String, long[]> e : spans.entrySet()) {
            try {
                long t0 = System.currentTimeMillis();
                WaveformData data = extractor.extractCachedSpan(keyUris.get(e.getKey()), 64,
                        e.getValue()[0], e.getValue()[1]);
                cache.put(e.getKey(), data);
                FLog.d(TAG, "preloadWaveformData: " + e.getKey() + " span " + e.getValue()[0]
                        + ".." + e.getValue()[1] + " in " + (System.currentTimeMillis() - t0)
                        + " ms");
            } catch (Exception ex) {
                FLog.w(TAG, "Failed to preload waveform data for " + e.getKey(), ex);
            }
        }
        return cache;
    }

    @Nullable
    private static Uri resolveWaveformUri(@NonNull Timeline timeline, @NonNull String clipId) {
        Clip clip = findClipById(timeline, clipId);
        if (clip != null) return clip.getSourceUri();
        AudioClip audioClip = findAudioClipById(timeline, clipId);
        if (audioClip != null) return audioClip.getSourceUri();
        return null;
    }

    @Nullable
    private static Clip findClipById(@NonNull Timeline timeline, @NonNull String clipId) {
        for (Clip c : timeline.getClips()) {
            if (clipId.equals(c.getId())) return c;
        }
        return null;
    }

    @Nullable
    private static AudioClip findAudioClipById(@NonNull Timeline timeline, @NonNull String clipId) {
        for (AudioClip ac : timeline.getAudioClips()) {
            if (clipId.equals(ac.getId())) return ac;
        }
        return null;
    }

    @NonNull
    private List<CompositeExportOverlay.WaveformSlot> buildWaveformSlots(
            @NonNull Timeline timeline,
            @NonNull Map<String, WaveformData> waveformCache,
            @NonNull List<WaveformStyle> builtinStyles,
            int outW, int outH) {
        List<CompositeExportOverlay.WaveformSlot> slots = new ArrayList<>();
        FLog.d(TAG, "buildWaveformSlots: count=" + timeline.getWaveformOverlays().size()
                + " outW=" + outW + " outH=" + outH
                + " cacheSize=" + waveformCache.size()
                + " builtinStyles=" + builtinStyles.size());
        if (!timeline.hasWaveformOverlays()) return slots;

        // When the caller passed 0×0 (original canvas for a single clip, no
        // resolved dims), fall back to the first clip's source dimensions so
        // waveform slots are still built. The slot positions/widths are
        // computed in source-pixel space and the Presentation effect downstream
        // scales the entire composited frame to the canvas.
        if (outW <= 0 || outH <= 0) {
            for (Clip c : timeline.getClips()) {
                if (c.isImageClip()) continue;
                int w = getSourceWidth(c);
                int h = getSourceHeight(c);
                if (w > 0 && h > 0) {
                    outW = w;
                    outH = h;
                    FLog.d(TAG, "buildWaveformSlots: inferred dimensions from clip "
                            + c.getId() + " → " + outW + "x" + outH);
                    break;
                }
            }
            if (outW <= 0 || outH <= 0) {
                FLog.w(TAG, "buildWaveformSlots: cannot infer dimensions; no slots");
                return slots;
            }
        }

        for (WaveformOverlayInstance woi : LayerPreviewController.visibleWaveformOverlays(timeline)) { // §4.5 per-object eye
            String clipId = woi.getAudioSourceRef();
            if (clipId == null) {
                FLog.w(TAG, "buildWaveformSlots: waveform overlay " + woi.getId()
                        + " has null audioSourceRef — skipping");
                continue;
            }
            Clip srcClip = findClipById(timeline, clipId);
            AudioClip srcAudioClip = srcClip == null ? findAudioClipById(timeline, clipId) : null;
            android.net.Uri wfUri;
            if (srcClip != null) {
                wfUri = srcClip.getSourceUri();
            } else if (srcAudioClip != null) {
                wfUri = srcAudioClip.getSourceUri();
            } else {
                // Fallback: the audioSourceRef clip ID didn't match any clip in
                // the timeline (likely the project was loaded and clip UUIDs
                // changed). Use the first clip that has audio as a last resort
                // so the visualizer still renders SOMETHING, and update the
                // instance's ref so per-clip filtering can attach it.
                FLog.w(TAG, "buildWaveformSlots: no clip with id " + clipId
                        + " for waveform " + woi.getId() + " — falling back to first clip with audio");
                Clip fallback = null;
                for (Clip c : timeline.getClips()) {
                    if (!c.isImageClip() && c.getSourceUri() != null) {
                        fallback = c;
                        break;
                    }
                }
                if (fallback == null) continue;
                srcClip = fallback;
                woi.setAudioSourceRef(fallback.getId());
                wfUri = fallback.getSourceUri();
                FLog.w(TAG, "buildWaveformSlots: using fallback clip "
                        + fallback.getId() + " uri=" + wfUri);
            }
            WaveformData data = waveformCache.get(wfUri.toString());
            if (data == null) {
                FLog.w(TAG, "buildWaveformSlots: no cached waveform data for uri=" + wfUri
                        + " (key=" + wfUri.toString() + ")");
                continue;
            }

            // Configure runtime source mapping so the visualizer reads the
            // correct trimmed/speed/loop position during export. Without this
            // the visualizer always samples from source time 0 at speed 1.0.
            if (srcClip != null) {
                woi.setSourceMapping(srcClip.getInPointMs(), srcClip.getSpeedMultiplier());
                if (srcClip.hasLoopExtension()) {
                    woi.setLoopExtension(srcClip.getTrimmedDurationMs());
                }
            } else if (srcAudioClip != null) {
                woi.setSourceMapping(srcAudioClip.getInPointMs(), 1.0f);
            }

            WaveformStyle base = null;
            for (WaveformStyle s : builtinStyles) {
                if (s.id.equals(woi.getStyleId())) { base = s; break; }
            }
            if (base == null) base = builtinStyles.isEmpty()
                    ? new WaveformStyle() : builtinStyles.get(0);

            // SPEC_VIZ_ENGINE §4 (Layers UI lane): the custom layer stack travels inline on the
            // instance (customStyleJson, persisted in project.json), so the same shared helper the
            // preview uses resolves it here — export matches the editor with no extra plumbing.
            WaveformStyle effective = WaveformStyleIO.resolveEffectiveStyle(woi, base);
            if (effective == null) effective = base;
            CompositeExportOverlay.WaveformSlot slot = new CompositeExportOverlay.WaveformSlot(
                    woi, data, effective, outW, outH, 1f);
            // G5(b) piggyback-looks: an ATTACHED rider mirrors its host clip's
            // opacity envelope at draw time. Fill the host linkage here (the one
            // place both the timeline and the slot are in hand); a missing host
            // leaves the slot at full opacity — never hide over a stale id.
            long[] hostWin = timeline.masterClipWindowMs(woi.getAttachedClipId());
            if (hostWin != null) {
                slot.hostClip = timeline.masterClipById(woi.getAttachedClipId());
                slot.hostStartMs = hostWin[0];
            }
            slots.add(slot);
            FLog.d(TAG, "buildWaveformSlots: added slot for waveform " + woi.getId()
                    + " (style=" + woi.getStyleId() + ", clipId=" + woi.getAudioSourceRef()
                    + " uri=" + wfUri + ")");
        }
        return slots;
    }

    /**
     * EXPORT PARITY + SPEED (2026-09-23): every image overlay except a bent one is composited on
     * the GPU by {@link GlImageOverlayEffect} with the preview's own placement and shader, instead
     * of being rasterised on the CPU into full-frame bitmaps and uploaded every frame (measured:
     * ~50% of the export's frame time on the Note 20). False restores the old routing exactly.
     */
    static final boolean GL_IMAGE_PASS = true;

    /**
     * Captions composited by {@link GlCaptionEffect} (same renderer pixels, tight boxes uploaded
     * only on change) instead of blitted into the full-frame Canvas pass. Proven on the Note 20
     * 2026-09-24: captions pixel-identical to the Canvas pass; part 3 10:19 vs 15+ min Canvas.
     */
    static final boolean GL_CAPTION_PASS = true;

    /** Slack on a clip item's overlay window: transitions and loop edges around its span. */
    private static final long RUN_WINDOW_PAD_MS = 5_000L;

    private static String runId(@NonNull Object o) {
        return o instanceof TextOverlayItem ? ((TextOverlayItem) o).getId()
                : ((com.fadcam.ui.faditor.sprite.SpriteOverlayItem) o).getId();
    }

    private static long runStartMs(@NonNull Object o) {
        return o instanceof TextOverlayItem ? ((TextOverlayItem) o).getStartMs()
                : ((com.fadcam.ui.faditor.sprite.SpriteOverlayItem) o).getStartMs();
    }

    private static long runEndMs(@NonNull Object o) {
        return o instanceof TextOverlayItem ? ((TextOverlayItem) o).getEndMs()
                : ((com.fadcam.ui.faditor.sprite.SpriteOverlayItem) o).getEndMs();
    }

    /**
     * Text boxes drawn into {@link GlImageOverlayEffect}'s run (by the Canvas pass's own
     * drawTextItem) instead of a Canvas OverlayEffect: images and text in one lane order then
     * need one GPU effect, and a box that stands still is drawn and uploaded once, not every
     * frame. The 48-minute lecture's chain was 37 alternating effects per clip, and the Canvas
     * text passes were ~40% of the export's GL-thread time (2026-09-24 GL_SAMPLE: upload 25%,
     * canvasBlit 12%, clear 4%). False restores the Canvas routing exactly.
     */
    static final boolean GL_TEXT_PASS = true;

    /** The ONE export routing question: does this overlay leave the Canvas for a GL pass? */
    static boolean exportGlRouted(@NonNull TextOverlayItem o) {
        if (exportGlRoutedImageLike(o)) return true;
        return glTextBox(o);
    }

    /** The routing before GL_TEXT_PASS: images (GL mode) and blended/keyed/masked items. */
    static boolean exportGlRoutedImageLike(@NonNull TextOverlayItem o) {
        if (GL_IMAGE_PASS && o.isImage()) return true;
        return o.wantsGlExport();
    }

    /** A text box the GL run draws: not an image, and not one TextFxGlEffect styles. */
    static boolean glTextBox(@NonNull TextOverlayItem o) {
        return GL_TEXT_PASS && GL_IMAGE_PASS && !o.isImage() && !o.hasActiveFx();
    }

    /** Export sound format: what YouTube and home-theatre playback expect. */
    static final int EXPORT_AUDIO_SAMPLE_RATE = 48_000;
    static final int EXPORT_AUDIO_BITRATE = 256_000;

    /**
     * STEREO OUT, ALWAYS (2026-09-23). Media3 mixes every sequence in the format of the FIRST
     * audio input it registers, and converts everything else down to it. On the 48-min
     * project that first input was the silence spacer (44.1 kHz mono), so the whole export -
     * stereo screen recording, stereo music - was folded to mono. Ending EVERY item's chain
     * in 48 kHz stereo makes the mix stereo whatever comes first: mono sources play equally
     * in both channels (unity gain, so a mono voice is as loud as it was), stereo passes
     * through untouched, and 3-8 channel sources fold to stereo at constant power.
     * Fresh processors per item - they are stateful.
     */
    @NonNull
    private static List<EditedMediaItemSequence> withStereoOutput(
            @NonNull List<EditedMediaItemSequence> sequences) {
        List<EditedMediaItemSequence> out = new ArrayList<>(sequences.size());
        for (EditedMediaItemSequence seq : sequences) {
            List<EditedMediaItem> items = new ArrayList<>(seq.editedMediaItems.size());
            for (EditedMediaItem it : seq.editedMediaItems) {
                if (isGap(it)) {   // no processors allowed; silence in the previous item's format
                    items.add(it);
                    continue;
                }
                List<AudioProcessor> aps = new ArrayList<>(it.effects.audioProcessors);
                aps.addAll(stereoOutputChain());
                items.add(it.buildUpon()
                        .setEffects(new Effects(aps, it.effects.videoEffects))
                        .build());
            }
            out.add(new EditedMediaItemSequence.Builder(items)
                    .setIsLooping(seq.isLooping)
                    .experimentalSetForceAudioTrack(seq.forceAudioTrack)
                    .experimentalSetForceVideoTrack(seq.forceVideoTrack)
                    .build());
        }
        return out;
    }

    @NonNull
    private static List<AudioProcessor> stereoOutputChain() {
        SonicAudioProcessor rate = new SonicAudioProcessor();
        rate.setOutputSampleRateHz(EXPORT_AUDIO_SAMPLE_RATE);   // inert when already 48 kHz
        androidx.media3.common.audio.ChannelMixingAudioProcessor mix =
                new androidx.media3.common.audio.ChannelMixingAudioProcessor();
        mix.putChannelMixingMatrix(
                androidx.media3.common.audio.ChannelMixingMatrix.createForConstantGain(1, 2));
        mix.putChannelMixingMatrix(
                androidx.media3.common.audio.ChannelMixingMatrix.createForConstantGain(2, 2));
        for (int in = 3; in <= 8; in++) {
            // Only the layouts media3 has a fold for (7->2 has none and threw at build time,
            // killing every export on 2026-09-23 15:09). A source with an unlisted count (a
            // 7-channel file) is refused by this processor — rare enough to accept for now.
            try {
                mix.putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix
                        .createForConstantPower(in, 2));
            } catch (UnsupportedOperationException ignored) { }
        }
        List<AudioProcessor> chain = new ArrayList<>(2);
        chain.add(rate);
        chain.add(mix);
        return chain;
    }

    /** Top AAC bitrate, used when the project holds lossless or high-bitrate sound. */
    static final int EXPORT_AUDIO_BITRATE_BEST = 320_000;

    /** Per-export answer of {@link #chooseExportAudioBitrate}, keyed so parts reuse it. */
    @Nullable private String audioBitrateKey = null;
    private int audioBitrateChosen = EXPORT_AUDIO_BITRATE;

    /**
     * BEST SOUND IN, BEST SOUND OUT. Before encoding, read every audible source in the
     * project (unmuted master clips, audio lanes, PiPs that carry sound) and size the AAC
     * bitrate to the best of them: lossless (WAV/FLAC/PCM) or >= 256 kbps sources get 320 kbps,
     * everything else 256 kbps - never below that, because the mix is re-encoded once more
     * by YouTube. Each source is written to the trace so a thin music file is visible.
     */
    private int chooseExportAudioBitrate(@Nullable FaditorProject project) {
        if (project == null) return EXPORT_AUDIO_BITRATE;
        String key = project.getId() + "@" + project.getLastModified();
        if (key.equals(audioBitrateKey)) return audioBitrateChosen;
        java.util.LinkedHashSet<Uri> sources = new java.util.LinkedHashSet<>();
        Timeline tl = project.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            if (!c.isAudioMuted() && !c.isImageClip() && c.getSourceUri() != null) {
                sources.add(c.getSourceUri());
            }
        }
        for (AudioClip ac : tl.getAudioClips()) {
            if (!ac.isMuted() && ac.getSourceUri() != null) sources.add(ac.getSourceUri());
        }
        for (Clip oc : tl.getOverlayClips()) {
            if (oc.isOverlayAudioEnabled() && oc.getSourceUri() != null) sources.add(oc.getSourceUri());
        }
        boolean best = false;
        String bestName = "none";
        int bestKbps = -1;
        for (Uri u : sources) {
            android.media.MediaExtractor ex = new android.media.MediaExtractor();
            try {
                ex.setDataSource(context, u, null);
                for (int t = 0; t < ex.getTrackCount(); t++) {
                    android.media.MediaFormat f = ex.getTrackFormat(t);
                    String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                    if (mime == null || !mime.startsWith("audio/")) continue;
                    int sr = f.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)
                            ? f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) : -1;
                    int ch = f.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                            ? f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT) : -1;
                    int kbps = f.containsKey(android.media.MediaFormat.KEY_BIT_RATE)
                            ? f.getInteger(android.media.MediaFormat.KEY_BIT_RATE) / 1000 : -1;
                    boolean lossless = mime.equals("audio/raw") || mime.equals("audio/flac")
                            || mime.contains("wav");
                    if (kbps < 0 && ex.getTrackCount() == 1
                            && f.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                        // Audio-only file with no declared rate: size over length.
                        long durUs = f.getLong(android.media.MediaFormat.KEY_DURATION);
                        long bytes = -1L;
                        try (android.content.res.AssetFileDescriptor fd = context
                                .getContentResolver().openAssetFileDescriptor(u, "r")) {
                            if (fd != null) bytes = fd.getLength();
                        } catch (Exception ignored) { }
                        if (bytes > 0 && durUs > 0) kbps = (int) (bytes * 8L * 1000L / durUs);
                    }
                    String name = u.getLastPathSegment() != null ? u.getLastPathSegment() : "?";
                    if (name.length() > 48) name = name.substring(name.length() - 48);
                    trace("AUDIO_SOURCE " + name + " " + mime + " " + sr + "Hz " + ch + "ch "
                            + (kbps > 0 ? kbps + "kbps" : "?kbps") + (lossless ? " lossless" : ""));
                    if (lossless || kbps >= 256) best = true;
                    if (lossless || kbps > bestKbps) {
                        bestKbps = lossless ? Integer.MAX_VALUE : kbps;
                        bestName = name;
                    }
                    break;
                }
            } catch (Exception e) {
                FLog.w(TAG, "audio probe failed for " + u, e);
            } finally {
                ex.release();
            }
        }
        int chosen = best ? EXPORT_AUDIO_BITRATE_BEST : EXPORT_AUDIO_BITRATE;
        trace("AUDIO_OUT " + EXPORT_AUDIO_SAMPLE_RATE + "Hz stereo AAC " + (chosen / 1000)
                + "kbps (best source: " + bestName + ")");
        audioBitrateKey = key;
        audioBitrateChosen = chosen;
        return chosen;
    }

    /** Encoder factory carrying the stereo audio bitrate, plus video settings when given. */
    @NonNull
    private DefaultEncoderFactory exportEncoderFactory(@Nullable FaditorProject project,
                                                       @Nullable VideoEncoderSettings video) {
        DefaultEncoderFactory.Builder b = new DefaultEncoderFactory.Builder(context)
                .setRequestedAudioEncoderSettings(
                        new androidx.media3.transformer.AudioEncoderSettings.Builder()
                                .setBitrate(chooseExportAudioBitrate(project)).build());
        if (video != null) b.setRequestedVideoEncoderSettings(video);
        return b.build();
    }

    /**
     * Build audio-only {@link EditedMediaItemSequence}s from the timeline's
     * {@link AudioClip}s — ONE SEQUENCE PER AUDIO LANE (A8). Within each lane,
     * silence gaps are inserted so that every clip starts at its correct
     * {@link AudioClip#getOffsetMs()} position.
     *
     * <p>THE BUG THIS FIXES: the old single-sequence walk inserted silence only when a clip's
     * start was past the cursor and had no else-branch, so two OVERLAPPING clips did not mix —
     * the second was appended AFTER the first and landed later than its authored offset. The
     * Composition mixes sequences in parallel (it already does for master vs PiP audio), so
     * one sequence per lane makes multi-lane audio export at its authored time.</p>
     *
     * @param timeline the project timeline
     * @return one sequence per inhabited audio lane (possibly empty; never null)
     */
    /**
     * The ONE sample rate every audio stream in this export is resampled to (A6).
     *
     * <p><b>Why this is centralised.</b> Three call sites each computed this for themselves and
     * they did not agree: the two video paths read the master clip (48000 Hz on JoyRaptor's test
     * project) while the audio-only path read the first AUDIO clip (44100 Hz). A rate derived
     * from the very clip being resampled makes {@code clipSampleRate != projectSampleRate}
     * false by construction, so the audio-only path installed no resampler AT ALL and A6 was a
     * silent no-op — confirmed on device by two contradictory "A6: project sample rate" lines in
     * one export and no "resampled" line at all.</p>
     *
     * <p><b>Order of preference.</b> Master video clips win, because the video's own audio track
     * is the one stream that cannot be resampled without also touching the muxed video, and
     * because it is what the viewer hears as "the recording". Added audio (music, voiceover) is
     * the guest and moves to meet it. With no usable video audio we fall back to the first audio
     * clip, and finally to 48000 Hz — the Android export default.</p>
     */
    private int resolveProjectSampleRate(@NonNull Timeline timeline) {
        for (int ci = 0; ci < timeline.getClipCount(); ci++) {
            Clip probeClip = timeline.getClip(ci);
            if (!probeClip.isAudioMuted() && !probeClip.isImageClip()) {
                Uri src = resolveSeekableSourceUri(probeClip);
                if (audioDurationMsOf(src) > 0) {
                    int sr = sampleRateOf(src);
                    if (sr > 0) {
                        FLog.d(TAG, "A6: project sample rate = " + sr
                                + " Hz (from master clip " + probeClip.getId() + ")");
                        return sr;
                    }
                }
            }
        }
        for (AudioClip ac : timeline.getAudioClips()) {
            if (!ac.isMuted()) {
                int sr = sampleRateOf(ac.getSourceUri());
                if (sr > 0) {
                    FLog.d(TAG, "A6: project sample rate = " + sr
                            + " Hz (no master audio; from audio clip " + ac.getId() + ")");
                    return sr;
                }
            }
        }
        FLog.d(TAG, "A6: project sample rate = 48000 Hz (default; nothing to probe)");
        return 48000;
    }

    @NonNull
    private List<EditedMediaItemSequence> buildAudioSequences(@NonNull Timeline timeline) {
        List<AudioClip> clips = new ArrayList<>(timeline.getAudioClips());
        FLog.d(TAG, "buildAudioSequences: audioClipCount=" + clips.size());
        if (clips.isEmpty()) return Collections.emptyList();

        int projectSampleRate = resolveProjectSampleRate(timeline);

        // Group by lane EXACTLY as Timeline.getAudioTracks() does: null layerId == the
        // default "audio" lane. Insertion order of the map follows the flat list, so a
        // single-lane project yields exactly one sequence in the same clip order as before.
        Map<String, List<AudioClip>> byLane = new LinkedHashMap<>();
        for (AudioClip ac : clips) {
            String lane = ac.getLayerId() != null ? ac.getLayerId() : "audio";
            byLane.computeIfAbsent(lane, k -> new ArrayList<>()).add(ac);
        }

        // Generate a reusable silence WAV file in cache
        File silenceFile = getOrCreateSilenceFile();
        if (silenceFile == null) {
            FLog.e(TAG, "Failed to create silence file — skipping audio track");
            return Collections.emptyList();
        }
        Uri silenceUri = Uri.fromFile(silenceFile);

        List<EditedMediaItemSequence> sequences = new ArrayList<>();
        for (Map.Entry<String, List<AudioClip>> e : byLane.entrySet()) {
            EditedMediaItemSequence seq = buildLaneAudioSequence(timeline, e.getKey(), e.getValue(), silenceUri, projectSampleRate);
            if (seq != null) sequences.add(seq);
        }
        return sequences;
    }

    /** Build ONE lane's audio-only sequence: clips sorted by offset, silence-gap items
     *  between them, each clip carrying its own trim/volume/envelope treatment. */
    @Nullable
    private EditedMediaItemSequence buildLaneAudioSequence(@NonNull Timeline timeline,
            @NonNull String laneId, @NonNull List<AudioClip> clips, @NonNull Uri silenceUri,
            int projectSampleRate) {
        // Sort by offset so we insert gaps correctly
        Collections.sort(clips, Comparator.comparingLong(AudioClip::getOffsetMs));

        List<EditedMediaItem> audioItems = new ArrayList<>();
        long cursorMs = 0; // current position on the timeline

        for (AudioClip ac : clips) {
            // M-EXPORT-1: track-level mute composes MULTIPLICATIVELY over the clip's own
            // mute — same semantics as the preview's LayerPreviewController
            // .effectivePreviewVolume (clip muted OR owning track muted → volume 0).
            // Volume 0 on export == skip the clip: the silence-gap logic below keys off
            // each clip's own offset, so skipping never shifts later clips (this is the
            // exact treatment ac.isMuted() has always received). A plain project has no
            // muted-track flags, so isAudioClipTrackMuted is false for every clip —
            // byte-identical output.
            if (ac.isMuted() || LayerPreviewController.isAudioClipTrackMuted(timeline, ac)) {
                FLog.d(TAG, "buildAudioSequence: skipping muted audio clip " + ac.getId()
                        + " (clipMuted=" + ac.isMuted() + ")");
                continue;
            }

            long clipStartMs = ac.getOffsetMs();

            // Insert silence gap if necessary. The silence WAV is SILENCE_FILE_MS long,
            // so a larger gap must be split into multiple items — otherwise the clip is
            // capped at the file length and every later audio clip slides earlier on the
            // timeline (the end song went silent because it landed ~250s too early).
            if (clipStartMs > cursorMs) {
                addSilence(audioItems, silenceUri, clipStartMs - cursorMs);
                cursorMs = clipStartMs;
            }

            // Build the audio clip item with trim & volume
            MediaItem.ClippingConfiguration clipping =
                    new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(ac.getInPointMs())
                            .setEndPositionMs(ac.getOutPointMs())
                            .build();

            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(seekableUriFor(ac.getSourceUri()))
                    .setClippingConfiguration(clipping)
                    .build();

            EditedMediaItem.Builder editBuilder = new EditedMediaItem.Builder(mediaItem)
                    .setRemoveVideo(true); // audio only

            // A9: the lane chain is built by the ONE shared factory (fx/AudioFxChainFactory)
            // — resample + volume/envelope/pan + FX — exactly what AudioClipPreviewPlayer
            // mounts for preview. Two call sites building their own chains is how preview
            // ignored pan and approximated fades while export applied them sample-exactly.
            int clipSampleRate = sampleRateOf(ac.getSourceUri());
            // The voice chain runs only when THIS CLIP asked for it (per-clip toggle in the
            // drawer's FX tab). The project-wide "Clean Audio" setting used to gate it here,
            // which processed a music lane underneath a voice lane identically to the voice.
            List<AudioProcessor> processors = new ArrayList<>(AudioFxChainFactory.buildLaneChain(
                    ac, clipSampleRate, projectSampleRate, fxBypassedSnapshot,
                    ac.isVoiceFxEnabled()));
            // First, so the lane's envelope and FX run on sound already fitted to its span.
            SonicAudioProcessor fit = speedAndFit(1f, audioClockFit(
                    seekableUriFor(ac.getSourceUri()), ac.getInPointMs(), ac.getOutPointMs()));
            if (fit != null) processors.add(0, fit);
            if (clipSampleRate > 0 && clipSampleRate != projectSampleRate) {
                FLog.d(TAG, "A6: audio clip " + ac.getId() + " resampled " + clipSampleRate + " → " + projectSampleRate + " Hz");
            }
            if (!processors.isEmpty()) {
                editBuilder.setEffects(new Effects(processors, Collections.emptyList()));
            }

            audioItems.add(editBuilder.build());
            cursorMs = clipStartMs + ac.getTrimmedDurationMs();
        }

        if (audioItems.isEmpty()) {
            FLog.d(TAG, "buildLaneAudioSequence(" + laneId + "): all clips muted — no sequence");
            return null;
        }

        FLog.d(TAG, "buildLaneAudioSequence(" + laneId + "): built " + audioItems.size()
                + " audio items, total ~" + cursorMs + "ms");
        return new EditedMediaItemSequence.Builder(audioItems).build();
    }

    /**
     * Build an audio-only {@link EditedMediaItemSequence} from the timeline's OVERLAY (PiP)
     * clips — SPEC_PIP_AUDIO slice B. PiPs are composited as PIXELS (the GL effect chain +
     * {@code CompositeExportOverlay}); they are not media items in the video sequence, so
     * their audio has no path into the export without this. Same proven shape as
     * {@link #buildAudioSequence}: silence-gap items place each clip at its own
     * {@code overlayStartMs}, then the clip rides as an audio-only item.
     *
     * <p>Returns null unless at least one PiP has explicitly OPTED IN
     * ({@code overlayAudioEnabled}); every existing project therefore builds the exact same
     * composition as before. The opt-in is not conservatism for its own sake — a dual-stream
     * pair is the same take recorded twice, so auto-enabling would double the voice.</p>
     *
     * <p>Volume/mute come from {@code LayerPreviewController.effectiveOverlayVolume}, the
     * same authority the preview player uses, so a lane-muted or clip-muted PiP is silent in
     * both. Volume 0 == skip the clip entirely: gaps key off each clip's own start, so
     * skipping never shifts a later one (identical treatment to a muted audio clip).</p>
     */
    @Nullable
    private EditedMediaItemSequence buildOverlayAudioSequence(@NonNull Timeline timeline, int projectSampleRate) {
        // Source the clip list from the SHARED visibility authority, not the raw list: a PiP
        // hidden by its lane's eye (or its own per-object eye) is excluded from the exported
        // PIXELS by this same method, and an object excluded from the export must not keep
        // contributing audio. Reading getOverlayClips() here would have done exactly that.
        // Hoist getLayers() out of the loop: it rebuilds every lane view from the flat lists
        // on each call, and the volume authority needs it per clip.
        List<Track> lanes = timeline.getLayers();
        List<Clip> overlays = new ArrayList<>();
        Map<String, Float> volumes = new HashMap<>();
        // renderable, not merely visible: a clip serving as another's luma matte is hidden
        // from the exported PIXELS by the video path, and an object excluded from the export
        // must not keep contributing audio — the same rule that rules out getOverlayClips().
        for (Clip c : LayerPreviewController.renderableOverlayVideoClips(timeline)) {
            if (c == null || c.isImageClip()) continue; // a still has no audio
            float vol = LayerPreviewController.effectiveOverlayVolume(c, lanes);
            if (vol <= 0f) continue;
            overlays.add(c);
            volumes.put(c.getId(), vol);
        }
        if (overlays.isEmpty()) return null;
        Collections.sort(overlays, Comparator.comparingLong(Clip::getOverlayStartMs));

        File silenceFile = getOrCreateSilenceFile();
        if (silenceFile == null) {
            FLog.e(TAG, "buildOverlayAudioSequence: no silence source — skipping PiP audio");
            return null;
        }
        Uri silenceUri = Uri.fromFile(silenceFile);

        List<EditedMediaItem> items = new ArrayList<>();
        long cursorMs = 0;
        for (Clip c : overlays) {
            Uri src = resolveSeekableSourceUri(c);
            // A source with no audio track would emit zero samples and wedge the AudioGraph
            // (same stall class the master path guards with audioDurationMsOf).
            if (audioDurationMsOf(src) <= 0) {
                FLog.d(TAG, "buildOverlayAudioSequence: PiP " + c.getId() + " has no audio — skipped");
                continue;
            }
            long startMs = Math.max(0, c.getOverlayStartMs());
            if (startMs > cursorMs) {
                addSilence(items, silenceUri, startMs - cursorMs);
                cursorMs = startMs;
            }

            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(src)
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(c.getInPointMs())
                            .setEndPositionMs(c.getOutPointMs())
                            .build())
                    .build();
            EditedMediaItem.Builder eb = new EditedMediaItem.Builder(mediaItem)
                    .setRemoveVideo(true); // audio only — the pixels come from the overlay pass

            List<AudioProcessor> processors = new ArrayList<>();
            float speed = c.getSpeedMultiplier();
            if (Math.abs(speed - 1.0f) < 0.001f || speed <= 0) speed = 1f;
            SonicAudioProcessor sonic = speedAndFit(speed,
                    audioClockFit(src, c.getInPointMs(), c.getOutPointMs()));
            if (sonic != null) processors.add(sonic);
            // PiP volume: ENVELOPE when the clip has keyframes, flat level otherwise.
            //
            // This sequence used to only ever call setVolume(), i.e. a constant — which is why
            // the PiP's Volume row shipped as a STATIC prop with no keyframe diamond. That was
            // the honest choice at the time: a diamond the export ignores is a control that
            // lies. Now that the envelope is wired, the diamond can exist.
            //
            // Deliberately MIRRORS the master-clip path (:1474) rather than inventing a second
            // envelope shape — same VolumeAudioProcessor, same times[]/vols[] pair — so PiP and
            // master audio cannot drift apart in how they read the same keyframe list.
            float volume = volumes.get(c.getId());
            // A6: resample PiP clip to project sample rate if needed.
            int clipSampleRate = sampleRateOf(resolveSeekableSourceUri(c));
            if (clipSampleRate != projectSampleRate) {
                processors.add(new ResamplingAudioProcessor(clipSampleRate, projectSampleRate));
                FLog.d(TAG, "A6: PiP clip " + c.getId() + " resampled " + clipSampleRate + " → " + projectSampleRate + " Hz");
            }
            if (c.hasVolumeKeyframes()) {
                @SuppressWarnings("unchecked")
                List<Clip.VolumeKeyframe> kfs = (List<Clip.VolumeKeyframe>) c.getVolumeKeyframes();
                long[] times = new long[kfs.size()];
                float[] vols = new float[kfs.size()];
                for (int i = 0; i < kfs.size(); i++) {
                    times[i] = kfs.get(i).timeMs;
                    vols[i] = kfs.get(i).volume;
                }
                // B1.Q: raw MULTIPLIERS now — the base rides via setVolume below,
                VolumeAudioProcessor vp = new VolumeAudioProcessor();
                vp.setVolume(volume);
                vp.setVolumeEnvelope(times, vols);
                processors.add(vp);
            } else if (Math.abs(volume - 1.0f) >= 0.01f) {
                VolumeAudioProcessor vp = new VolumeAudioProcessor();
                vp.setVolume(volume);
                processors.add(vp);
            }
            // C1.E: FX chain on PiP audio too — one factory everywhere, one snapshot.
            // Voice chain is PER-CLIP, same as the master and lane paths.
            AudioFxChainFactory.addTo(processors, c, fxBypassedSnapshot,
                    c.isVoiceFxEnabled(), projectSampleRate);
            if (!processors.isEmpty()) {
                eb.setEffects(new Effects(processors, Collections.emptyList()));
            }
            items.add(eb.build());
            // Advance by the REAL audio duration: Sonic compresses/stretches, so a sped-up
            // PiP occupies less. Getting this wrong would only ever mis-place the NEXT gap,
            // but that is exactly how a later clip drifts early.
            long dur = Math.max(0, c.getTrimmedDurationMs());
            if (Math.abs(speed - 1.0f) >= 0.001f && speed > 0) {
                dur = (long) (dur / speed);
            }
            cursorMs = startMs + dur;
        }
        if (items.isEmpty()) return null;
        FLog.d(TAG, "buildOverlayAudioSequence: " + items.size()
                + " items, total ~" + cursorMs + "ms");
        return new EditedMediaItemSequence.Builder(items).build();
    }

    /**
     * Get or create a silent WAV file in the app's cache directory.
     * The file is long enough to cover any reasonable gap (10 minutes).
     * Format: 16-bit PCM mono at 44100 Hz.
     *
     * @return the silence file, or null on failure
     */
    @Nullable
    private File getOrCreateSilenceFile() {
        // DURABLE, not getCacheDir(): Android trims the cache under storage pressure, and a
        // long export is exactly when storage runs low. On 2026-09-23 the 48-min export's sound
        // pass died with "silence.wav: ENOENT" five minutes in, right after ~2 GB of video
        // parts were written — the spacer was deleted while the pass was still reading it.
        File cacheDir = com.fadcam.ui.faditor.util.DurableCache.dir(context, "export_silence");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        File silenceFile = new File(cacheDir, "silence_60s.wav");
        if (silenceFile.exists() && silenceFile.length() > 44) {
            return silenceFile;
        }

        try {
            // Generate a silent WAV (gaps larger than this are split into chunks).
            int sampleRate = 44100;
            int channels = 1;
            int bitsPerSample = 16;
            long durationSeconds = SILENCE_FILE_MS / 1000;
            long numSamples = sampleRate * durationSeconds;
            long dataSize = numSamples * channels * (bitsPerSample / 8);

            // try-with-resources guarantees the stream is closed even if a write
            // throws partway through, preventing a leaked native file descriptor.
            try (FileOutputStream fos = new FileOutputStream(silenceFile)) {
                // WAV header (44 bytes)
                ByteBuffer header = ByteBuffer.allocate(44);
                header.order(ByteOrder.LITTLE_ENDIAN);
                // RIFF chunk
                header.put((byte) 'R'); header.put((byte) 'I');
                header.put((byte) 'F'); header.put((byte) 'F');
                header.putInt((int) (36 + dataSize)); // file size - 8
                header.put((byte) 'W'); header.put((byte) 'A');
                header.put((byte) 'V'); header.put((byte) 'E');
                // fmt sub-chunk
                header.put((byte) 'f'); header.put((byte) 'm');
                header.put((byte) 't'); header.put((byte) ' ');
                header.putInt(16);                          // sub-chunk size
                header.putShort((short) 1);                 // PCM format
                header.putShort((short) channels);
                header.putInt(sampleRate);
                header.putInt(sampleRate * channels * bitsPerSample / 8); // byte rate
                header.putShort((short) (channels * bitsPerSample / 8)); // block align
                header.putShort((short) bitsPerSample);
                // data sub-chunk
                header.put((byte) 'd'); header.put((byte) 'a');
                header.put((byte) 't'); header.put((byte) 'a');
                header.putInt((int) dataSize);

                fos.write(header.array());

                // Write silence data in chunks (all zeros = silence)
                byte[] zeroChunk = new byte[8192];
                long remaining = dataSize;
                while (remaining > 0) {
                    int toWrite = (int) Math.min(zeroChunk.length, remaining);
                    fos.write(zeroChunk, 0, toWrite);
                    remaining -= toWrite;
                }

                fos.flush();
            }

            FLog.d(TAG, "Created silence file: " + silenceFile.getAbsolutePath()
                    + " (" + silenceFile.length() + " bytes)");
            return silenceFile;

        } catch (Exception e) {
            FLog.e(TAG, "Failed to create silence WAV file", e);
            return null;
        }
    }

    /**
     * Convert an absolute Media3 presentation time to the clip-local time used
     * by per-frame effects (e.g. opacity envelopes, overlay animation). The
     * input is in microseconds; result is in milliseconds relative to the start
     * of the clip on the timeline.
     */
    static long clipMsFor(long presentationTimeUs, long clipTimelineStartMs) {
        return presentationTimeUs / 1000 - clipTimelineStartMs;
    }

    /**
     * M-EXPORT-1 (PLAN §5.3(2)): the single predicate answering "does this project use ANY
     * schema-v8 layer feature that affects export output?" — the fast-path/slow-path feature-set
     * lesson. If this returns true the near-lossless trim optimization is disabled and the full
     * re-encode path (which knows how to composite layers) is taken.
     *
     * <p>Checked, in order of cheapness:
     * <ol>
     *   <li>Sprite overlays (the S6 guard {@code Timeline#hasSpriteOverlays()} asked for).</li>
     *   <li>Any user-created layer track definition (M10 {@code LayerTrackDef}), even if
     *       still empty — its flags/kind could shape output the moment an item lands on it.</li>
     *   <li>Any item assigned to a non-default layer ({@code layerId != null}).</li>
     *   <li>Any persisted {@link TrackFlags} entry whose OUTPUT-affecting fields are set:
     *       hidden, muted, or a non-zero zIndex (collapsed/locked are UI-only and ignored).</li>
     *   <li>Any {@link TimedItem} across all track views with a non-NORMAL blend or a
     *       free-transform envelope. Today the Track views are rebuilt with default
     *       blend/transform on every call (M5 ephemeral-views note), so this is defensively
     *       future-proof rather than reachable — but it makes the predicate complete against
     *       the M6/M7 "persistent home for mutated fields" follow-up.</li>
     * </ol>
     * A project that never touched a layer feature hits none of these (empty defs, null
     * layerIds, no non-default flags) — the fast-path decision is byte-identical to before.</p>
     */
    static boolean usesLayerFeaturesAffectingExport(@NonNull Timeline timeline) {
        // M-EXPORT-2: a floating PiP clip ALWAYS forces the full re-encode path —
        // explicit check (cheapest first) rather than relying on the mirrored
        // TimedItem.hasTransform() below, which would miss a transform-less overlay.
        if (!timeline.getOverlayClips().isEmpty()) return true;
        if (timeline.hasSpriteOverlays()) return true;
        if (!timeline.getExtraLayerTracks().isEmpty()) return true;
        for (TextOverlayItem o : timeline.getTextOverlays()) {
            if (o.getLayerId() != null && !"text".equals(o.getLayerId())) return true;
        }
        for (AudioClip ac : timeline.getAudioClips()) {
            if (ac.getLayerId() != null && !"audio".equals(ac.getLayerId())) return true;
        }
        for (Map.Entry<String, TrackFlags> e : timeline.getAllTrackFlags().entrySet()) {
            TrackFlags f = e.getValue();
            if (f == null) continue;
            if (f.hidden || f.muted || f.zIndex != 0) return true;
        }
        List<Track> allTracks = new ArrayList<>();
        allTracks.add(timeline.getMasterTrack());
        allTracks.addAll(timeline.getLayers());
        allTracks.addAll(timeline.getAudioTracks());
        for (Track track : allTracks) {
            for (TimedItem item : track.getItems()) {
                if (item.getBlendMode() != BlendMode.NORMAL) return true;
                if (item.hasTransform()) return true;
            }
        }
        return false;
    }

    // (M-EXPORT-1's buildOverlayVideoSequence was DELETED in M-EXPORT-2: probe #3 proved
    // DefaultVideoCompositor draws the primary sequence ON TOP, so a second video sequence
    // composites the PiP invisibly under the master. PiP export now rides
    // CompositeExportOverlay — see the overlay-video pass there and PLAN_LAYERS_V2 §5.)

    /**
     * Assemble the canonical-ordered {@code List<Effect>} for a clip's
     * {@code EditedMediaItem}. Single source of truth for the export effect
     * pipeline — every {@code buildXxxItem} method routes through this helper
     * so the relative ordering of speed/rotate/crop/color-grade/overlay/opacity/
     * presentation can't drift between call sites.
     *
     * <p>Canonical order (first applied → last applied):
     * <ol>
     *   <li>{@link SpeedChangeEffect} (video-only, skip for image clips)</li>
     *   <li>{@link ScaleAndRotateTransformation} (rotate/flip, video-only)</li>
     *   <li>{@link Crop} (preset or custom, video-only)</li>
     *   <li>Color-grade {@code EffectStack.toEffects(...)}</li>
     *   <li>Optional pre-overlay extra transform (e.g. ping-pong reverse mirror)</li>
     *   <li>{@link OpacityExportEffect} — the CLIP's own opacity/master fade, applied to
     *       the clip picture BEFORE anything composites on top of it (owner ruling
     *       2026-09-02: a clip's fade fades that clip, not the overlays above it)</li>
     *   <li>PiP composite, adjustment layers, and the {@link OverlayEffect} for
     *       text + captions + waveform — all unaffected by the clip's fade</li>
     *   <li>{@link Presentation} canvas resize</li>
     * </ol>
     *
     * <p>Use {@code isTransitionItem=true} to draw only the speed change and any
     * {@code preOverlayExtra} effect (e.g. the GL transition). Transition items
     * must not apply per-clip transforms, overlays, opacity, or the canvas
     * presentation — their job is to render the transition between two clips.</p>
     */
    @NonNull
    private List<Effect> assembleClipVideoEffects(@NonNull Clip clip,
                                                  @NonNull FaditorProject project,
                                                  long timelineCursorMs,
                                                  int outW, int outH,
                                                  @Nullable int[] canvasDims,
                                                  @NonNull List<CompositeExportOverlay.WaveformSlot> allWaveformSlots,
                                                  boolean isTransitionItem,
                                                  @Nullable Effect preOverlayExtra) {
        return assembleClipVideoEffects(clip, project, timelineCursorMs, outW, outH, canvasDims,
                allWaveformSlots, isTransitionItem, preOverlayExtra,
                /* isLoopBeforeItem = */ false);
    }

    /**
     * @param isLoopBeforeItem true for a loop-BEFORE extension, which is emitted AHEAD of the
     *        head-trimmed main item and is itself untrimmed. Its content therefore has NOT had
     *        this clip's head transition taken out of it, so it must carry one term less of the
     *        §2d correction than the main item does — otherwise overlays over a loop-before
     *        extension on a post-seam clip render early by that transition. (Adversarial review
     *        2026-08-03.)
     */
    private List<Effect> assembleClipVideoEffects(@NonNull Clip clip,
                                                  @NonNull FaditorProject project,
                                                  long timelineCursorMs,
                                                  int outW, int outH,
                                                  @Nullable int[] canvasDims,
                                                  @NonNull List<CompositeExportOverlay.WaveformSlot> allWaveformSlots,
                                                  boolean isTransitionItem,
                                                  @Nullable Effect preOverlayExtra,
                                                  boolean isLoopBeforeItem) {
        List<Effect> videoEffects = new ArrayList<>();
        boolean isVideo = !clip.isImageClip();

        // Per-clip waveform slot filter: a visualizer's audioSourceRef points to
        // ONE specific clip, so the slot should only be drawn on that clip's
        // overlay — not on every clip in the timeline. Without this, the
        // visualizer appears on every frame of the export (the user sees it
        // "stuck" across transitions and into clips that don't have a
        // visualizer). Slots whose audioSourceRef doesn't match any clip (the
        // orphan-fallback case from buildWaveformSlots) are still drawn on the
        // first matching non-image clip encountered, so this filter is a
        // refinement, not a hard exclusion.
        List<CompositeExportOverlay.WaveformSlot> clipWaveformSlots = new ArrayList<>();
        if (isVideo && !isTransitionItem) {
            for (CompositeExportOverlay.WaveformSlot slot : allWaveformSlots) {
                String ref = slot.instance.getAudioSourceRef();
                if (ref == null || ref.equals(clip.getId())) {
                    clipWaveformSlots.add(slot);
                }
            }
        }
        FLog.d(TAG, "assembleClipVideoEffects clip=" + clip.getId()
                + " timelineCursorMs=" + timelineCursorMs
                + " isTransitionItem=" + isTransitionItem
                + " totalSlots=" + allWaveformSlots.size()
                + " clipSlots=" + clipWaveformSlots.size()
                + " hasOpacityKf=" + clip.hasOpacityKeyframes()
                + " hasCaptions=" + clip.isCaptionsEnabled());

        if (isVideo) {
            float speed = clip.getSpeedMultiplier();
            if (speed != 1.0f) {
                videoEffects.add(new SpeedChangeEffect(speed));
            }
        }

        if (isVideo && !isTransitionItem) {
            int rotation = clip.getRotationDegrees();
            boolean flipH = clip.isFlipHorizontal();
            boolean flipV = clip.isFlipVertical();
            if (rotation != 0 || flipH || flipV) {
                ScaleAndRotateTransformation.Builder tb = new ScaleAndRotateTransformation.Builder();
                if (rotation != 0) tb.setRotationDegrees(rotation);
                float sx = flipH ? -1f : 1f;
                float sy = flipV ? -1f : 1f;
                if (flipH || flipV) tb.setScale(sx, sy);
                videoEffects.add(tb.build());
            }
        }

        // Crop applies to TRANSITION items too (F12): it is added BEFORE preOverlayExtra
        // (the GlTransitionExportEffect), so the outgoing leg's frames reach the blend
        // already cropped — previously the exported transition popped from the cropped
        // framing to the raw source + black bars for the transition's duration. The
        // shader program's output size follows its input, so the segment's geometry
        // class is unchanged (crop dims now, source dims before); the incoming leg is
        // cropped inside GlTransitionFrameOverlay.
        //
        // The rect comes from Clip.effectiveCropRectNdc() — the ONE per-clip crop decision,
        // which FxLivePreviewController reads for the GL preview from the same method. The
        // inline custom-preset conversion that used to sit here was the second copy that let
        // the preview drift to full-frame while the export stayed correct.
        if (isVideo) {
            float[] cr = clip.effectiveCropRectNdc();
            if (cr != null) videoEffects.add(new Crop(cr[0], cr[1], cr[2], cr[3]));
        }

        if (!isTransitionItem && clip.getEffectStack().isActive()) {
            videoEffects.addAll(clip.getEffectStack().toEffects(context, false));
        }

        if (preOverlayExtra != null) {
            videoEffects.add(preOverlayExtra);
        }

        // overlayW/H is the AUTHORING CANVAS (outW/outH = canvasDims, or the first video
        // clip's dims for the "original" preset — see buildComposition). Every overlay
        // coordinate in the project is a fraction of THIS, never of a clip's own frame.
        // The fallback below is a last resort for a timeline whose canvas cannot be
        // resolved at all (inferSourceDims returned null); the 1080x1920 literal is the
        // same portrait default inferSourceDims itself uses, deliberately kept identical
        // so there is one "we have no idea" answer rather than two.
        int overlayW = outW;
        int overlayH = outH;
        if (!isTransitionItem && (overlayW <= 0 || overlayH <= 0)) {
            overlayW = clip.isImageClip() ? 1080 : getSourceWidth(clip);
            overlayH = clip.isImageClip() ? 1920 : getSourceHeight(clip);
        }

        // EXPORT-CANVAS-FIX: normalize an IMAGE clip's frame up to the authoring
        // canvas (overlayW x overlayH) BEFORE the OverlayEffect composites.
        //
        // The OverlayEffect sizes its per-frame bitmap to the CURRENT decoded frame
        // (CompositeExportOverlay.configure() receives that size). Overlays are
        // authored in overlayW x overlayH coordinates and then scaled by
        // frameW/overlayW onto that frame. For a video clip the decoded frame is the
        // real source resolution (e.g. 1080x1920), so the scale is ~1:1 and overlays
        // render full-size. But an IMAGE clip can decode to a tiny native size — the
        // project's black "Gap" placeholder is a 16x16 PNG — so the overlay bitmap
        // collapses to 16x16, the scale factor is 16/1080 ≈ 0.015, and EVERY overlay
        // (captions, text, image-as-layer) shrinks to sub-pixel and vanishes; the
        // image content itself is also left at 16x16 and upscaled to a blurry mess.
        // (When canvasPreset != "original" the downstream Presentation at the end of
        // this chain already normalized these clips — but for "original" preset
        // canvasDims is null, so that Presentation is skipped and nothing rescued the
        // 16x16 image. This is why the bug is preset-specific and pre-existing.)
        //
        // Inserting a Presentation here scales the decoded frame to the authoring
        // canvas up front, so configure() sees overlayW x overlayH, the overlay scale
        // is 1:1, and overlays composite correctly.
        //
        // ── 2026-09-01: THE GATE IS NO LONGER isImageClip(). ──────────────────────
        // c8eae1eb added this for image clips and deliberately left the video branch
        // untouched ("video path byte-identical") as a blast-radius choice, not because
        // the video path was correct. It is not. CompositeExportOverlay.getBitmap does
        //     canvas.scale(frameW / outW, frameH / outH)
        // which is ANISOTROPIC whenever the frame's aspect differs from the canvas's,
        // while the trailing Presentation (end of this method) scales frame+overlay
        // together by a single UNIFORM factor. The two only cancel when the aspects
        // match. Worked example — canvas 1080x1920, clip 1920x1080:
        //     overlay pre-scale (1920/1080, 1080/1920) = (1.7778, 0.5625)
        //     trailing SCALE_TO_FIT 1920x1080 -> 1080x1920 = uniform 0.5625
        //     net (1.0000, 0.3164)  ← x survives by coincidence, y collapses to 31.6%
        // i.e. every caption/text/sticker/waveform was squashed to a third of its
        // height and pulled toward the frame's vertical centre on any clip whose
        // aspect is not the canvas's. Normalising to the canvas BEFORE the overlay
        // makes frameW==outW and frameH==outH, so that scale() is skipped entirely
        // and the geometry is the identity the overlays were authored against.
        //
        // This also covers the CROPPED clip: a crop changes the frame's aspect even
        // when the source matched the canvas, and it is applied above — which is why
        // this must be a real Presentation here rather than a getSourceWidth() test.
        //
        // NO-OP PROOF (aspect already equal, i.e. essentially every project): let the
        // frame be F = k*(outW,outH). LAYOUT_SCALE_TO_FIT to (outW,outH) is a uniform
        // 1/k with zero letterbox, the overlay then draws at 1:1 instead of scale(k,k)
        // followed by the trailing uniform 1/k, and the trailing Presentation becomes
        // a same-size identity. Composed mapping before = (k)(1/k) = 1; after =
        // (1)(1) = 1. The picture likewise takes exactly one resample by 1/k either
        // way — only its position in the chain moves, and the overlay is now rastered
        // at output resolution instead of being downscaled after the fact (strictly
        // better, never different in geometry).
        //
        // TRANSITIONS are still excluded by !isTransitionItem: the GL transition
        // program's configure() reports its INPUT size, and the incoming leg is
        // cropped/blended inside GlTransitionFrameOverlay against that size.
        if (!isTransitionItem && overlayW > 0 && overlayH > 0) {
            videoEffects.add(Presentation.createForWidthAndHeight(
                    overlayW, overlayH, Presentation.LAYOUT_SCALE_TO_FIT));
        }

        // ── M7: a MASTER (spine) clip's OWN FxStack ───────────────────────────────────
        // Clip.getOrCreateFx() was written by the object drawer's Effects tab, badged by
        // the timeline lane and persisted by ProjectStorage — and read by NOTHING on the
        // spine. Every consumer was overlay-only: BlendModeGlEffect is emitted per entry
        // of exportOverlayVideoClips, ImageBlendGlEffect/TextFxGlEffect per overlay item,
        // AdjustmentLayerGlEffect per adjustment layer. So adding "Solid Color" to a spine
        // clip drew a card, lit the badge, saved to the project and changed no pixel
        // (JoyRaptor, 2026-09-01: "the white solid didn't apply").
        //
        // REUSED, not reimplemented. AdjustmentLayerGlEffect already is "run this FxStack
        // over the frame I am handed" — multi-pass planner, separable kernels, sampler
        // passes, per-frame keyframe resolution, degrade-to-passthrough on a driver
        // failure. Its extra machinery is exactly the part that vanishes at its own
        // defaults: with no masks and no chroma key MaskSdf coverage is 1, with no
        // transform opacityAt() is 1, and NORMAL blend makes the final line
        //     out = mix(base, blendNormal(base, graded), 1 * 1) = graded
        // i.e. plain "apply the stack to the whole frame", which is the spine semantic.
        // Writing a second shader host for that would be a second place for the FX
        // vocabulary to drift.
        //
        // WHERE IN THE ORDER. After the canvas Presentation above, so the stack runs at
        // canvas geometry — every spatial card (blur/RGB-shift radii, vignette, mask-less
        // gradients) is quoted against the frame it is given, and the GL preview now
        // stages the same clip on a canvas-aspect composite frame
        // (FxLivePreviewController.compositeFrame), so this is what makes the two agree.
        // Before the below-blend pass, the PiP loop, the adjustment-layer inserts and the
        // caption/text OverlayEffect, all of which composite ON TOP of the spine picture —
        // so an adjustment layer still grades "everything beneath it", this grade included.
        //
        // NO-OP PROOF: gated at BUILD time on active().isEmpty(). A spine clip with an
        // empty (or null) stack — every clip in every project written before the Effects
        // tab existed — appends nothing at all, so its List<Effect> is element-for-element
        // the list it was before this block, and the export is byte-identical. The gate is
        // active(), not isEmpty(), so a stack holding only DISABLED cards is equally free.
        //
        // TRANSITION items are excluded, matching the clip's legacy getEffectStack() above
        // and the Presentation this rides behind; the seam segment is un-graded exactly as
        // it is un-filtered today.
        if (!isTransitionItem && clip.getFx() != null && !clip.getFx().active().isEmpty()) {
            videoEffects.add(new AdjustmentLayerGlEffect(
                    context, spineFxLayer(clip),
                    editorTimeOffsetForChunk(project.getTimeline(), clip, timelineCursorMs)
                            - (isLoopBeforeItem
                                    ? headTransitionMsFor(project.getTimeline(), clip) : 0L)));
        }

        // ── SPINE CANVAS TRANSFORM — where this clip's picture SITS on the canvas ─────
        //
        // JoyRaptor, 2026-09-04: a 9:16 clip on a 16:9 canvas is fit-CENTRED and there was no way to
        // left-justify it, let alone move or rotate it, short of the crop tool. This places it.
        //
        // POSITION IN THE CHAIN IS THE WHOLE DESIGN. It rides immediately behind the canvas
        // Presentation above (and behind the spine FxStack, so a vignette or a blur belongs to
        // the CLIP and travels with it) and immediately AHEAD of OpacityExportEffect and
        // everything that composites on top -- the below-blend pass, the PiP loop, the
        // adjustment-layer inserts, the caption/text OverlayEffect. Chain order is paint order,
        // so the clip's picture moves and every overlay above it stays exactly where the user put
        // it on the canvas. That is the same ruling the opacity pass was moved for
        // ("the opacity should fade the CLIP, not everything above it"), applied to geometry.
        //
        // CROP STILL WORKS, UNTOUCHED. Crop is applied far upstream (media3's Crop, on the
        // decoded source) and the canvas Presentation turns whatever it produced into a
        // canvas-shaped frame; this then places that frame. The two never share arithmetic, so
        // the crop fixes landed today are not in this path at all.
        //
        // PREVIEW PARITY: SpineTransformExportEffect builds no maths of its own. It compiles
        // SpineTransform.fragmentShader(...) and uploads SpineTransform.uniforms(...), which are
        // the same two calls FxPreviewTextureView.drawSpineTransform makes. One method, two
        // hosts -- not two transcriptions.
        //
        // NO-OP PROOF: gated at BUILD time on Clip.hasSpineTransform(), which is false unless a
        // static differs from the fit-centre identity or a keyframe track exists. Every clip in
        // every project written before this feature answers false, so nothing at all is appended
        // and the List<Effect> is element-for-element the list it was -- byte-identical export.
        // TRANSITION items are excluded, matching the Presentation and the FxStack this rides
        // behind: the seam segment is un-placed exactly as it is un-graded and un-filtered.
        //
        // THE OFFSET IS timelineCursorMs, NOT editorTimeOffsetFor(...). The two are different
        // clocks and the choice follows the KEYFRAME TIME BASE: spine-transform keys are
        // CLIP-LOCAL (Clip.spinePoseAt, same base as Clip.opacityAtClipMs), so the offset must be
        // this EditedMediaItem's own start on the export timeline -- which is exactly what
        // OpacityExportEffect is handed on the very next line. editorTimeOffsetFor maps to
        // EDITOR-absolute time and is right for an adjustment layer, whose keys are authored
        // against the whole timeline; using it here would shift a spine key by the clip's
        // position in the project.
        if (!isTransitionItem && clip.hasSpineTransform()) {
            videoEffects.add(new SpineTransformExportEffect(clip, timelineCursorMs));
        }

        // ── Clip opacity / master fade — the CLIP's picture only ──────────────────────
        // OWNER RULING 2026-09-02 — "THE OPACITY SHOULD FADE THE CLIP, NOT EVERYTHING ABOVE
        // IT." This pass used to sit at the very END of the chain, after the PiP composite,
        // the adjustment layers and the caption/text OverlayEffect, so it multiplied the
        // ALREADY-COMPOSITED frame: a spine clip fading out dragged every overlay the owner
        // had placed above it — image layers, captions, stickers, visualiser — to black with
        // it, and they snapped back at the seam.
        //
        // It now runs HERE: after the clip's own geometry, colour grade and spine FxStack,
        // and BEFORE anything that composites on top. Chain order is paint order, so the
        // clip's picture is scaled by (keyframe opacity x master fade) and every later pass
        // draws over that at its own full strength. Arithmetic for a clip at fade factor f
        // with a caption and a text overlay on top:
        //     picture  = clipRGBA * f          (this pass)
        //     result   = overlay OVER picture  (OverlayEffect, unscaled)
        // which is exactly what the preview now does — FaditorEditorActivity puts
        // opacity * masterFade on the video/image surface and leaves player_container (the
        // shared parent of every overlay view) at alpha 1.
        //
        // SAME MOVE IS CORRECT FOR KEYFRAME OPACITY, the effect's other user: the live
        // preview has ALWAYS applied clip opacity keyframes to the video surface alone, so
        // moving the export pass pre-composite makes export match the preview it never did.
        //
        // NO-OP PROOF: the gate is unchanged. A clip with no opacity keyframes and no master
        // fade appends nothing at all, so its List<Effect> is element-for-element what it was
        // and its export is byte-identical.
        if (!isTransitionItem && (clip.hasOpacityKeyframes() || clip.hasMasterFade())) {
            videoEffects.add(new OpacityExportEffect(clip, timelineCursorMs));
        }

        if (!isTransitionItem && overlayW > 0 && overlayH > 0) {
            // The overlay is added when EITHER the project has text overlays, OR
            // this clip has captions enabled, OR this clip's URI is referenced by
            // any waveform overlay in the project (even if slot building failed
            // for that waveform). The last case is what carries visualizers on
            // clips whose waveform slot couldn't be built (e.g. because the
            // audioSourceRef clip ID didn't match any clip). Without this, clips
            // with a misconfigured visualizer end up with no overlay at all.
            boolean clipHasWaveformRef = false;
            for (com.fadcam.ui.faditor.model.WaveformOverlayInstance woi
                    : project.getTimeline().getWaveformOverlays()) {
                String ref = woi.getAudioSourceRef();
                if (ref != null && ref.equals(clip.getId())) {
                    clipHasWaveformRef = true;
                    break;
                }
            }
            // ALSO create the overlay when a captioned AUDIO clip overlaps this
            // video clip's timeline range. Audio-track captions render on whatever
            // video happens to be playing underneath; without this, a clip that has
            // no overlays of its own (no text, captions off, no visualizer) silently
            // dropped the audio caption — so captions vanished mid-song the moment
            // the timeline reached such a clip. (This was the "captions stop after a
            // few videos" bug.)
            boolean audioCaptionOverlaps = false;
            // EDITOR time, the clock audio clips are placed on: the composition cursor restarts
            // at 0 in every part of a chunked export (CompositeExportOverlay's constructor).
            long clipEditorStart = timelineCursorMs
                    + editorTimeOffsetForChunk(project.getTimeline(), clip, timelineCursorMs);
            long clipTlStart = clipEditorStart - 2_000L;
            long clipTlEnd = clipEditorStart + Math.max(clip.getTrimmedDurationMs(),
                    clip.getVisualDurationMs()) + 2_000L;
            for (AudioClip ac : project.getTimeline().getAudioClips()) {
                java.util.List<AudioClip.CaptionBinding> bs = ac.getCaptionBindings();
                if (bs.isEmpty()) {
                    if (!ac.isCaptionsEnabled() || !ac.hasTranscript()) continue;
                    if ("hidden".equals(ac.getCaptionStyleId())) continue;
                    long aStart = ac.getOffsetMs();
                    long aEnd = ac.getOffsetMs() + ac.getTrimmedDurationMs();
                    if (aStart < clipTlEnd && aEnd > clipTlStart) { audioCaptionOverlaps = true; break; }
                } else {
                    for (AudioClip.CaptionBinding b : bs) {
                        if (!b.enabled) continue;
                        if ("hidden".equals(b.styleId)) continue;
                        com.fadcam.ui.faditor.transcript.NamedTranscript nt = ac.transcriptForBinding(b);
                        if (nt == null || nt.transcript == null || nt.transcript.isEmpty()) continue;
                        long aStart = ac.getOffsetMs();
                        long aEnd = ac.getOffsetMs() + ac.getTrimmedDurationMs();
                        if (aStart < clipTlEnd && aEnd > clipTlStart) { audioCaptionOverlaps = true; break; }
                    }
                    if (audioCaptionOverlaps) break;
                }
            }
            // M-EXPORT-1: the text/image/sticker overlay list comes from the SAME
            // shared authority the live preview feeds TextOverlayLayer from
            // (LayerPreviewController.visibleTextOverlays): every item on every
            // non-hidden TEXT/STICKER layer track, in track-z order. For a plain
            // project (single unhidden "text" track) this is the exact same objects
            // in the exact same order as the old getTextOverlays() call — identical
            // composition, byte-identical output. Hidden layer tracks' items are
            // excluded here exactly as they are from the preview (PLAN §5.3(4)).
            // Z4 (SPEC_CROSSTYPE_Z): split the overlay content around the PiP plane. The
            // ABOVE bucket feeds the existing overlay pass; a non-empty BELOW bucket gets its
            // own pass inserted BEFORE the PiP composite further down, so a lane ordered
            // beneath a PiP lane actually renders beneath it. Both buckets come from the same
            // partition the preview consumes — that shared split is what keeps them in step.
            // Inert today: with every zIndex at its default the below bucket is empty and
            // exportTextOverlays/exportSpriteItems are exactly what they always were.
            final java.util.List<java.util.List<LayerPreviewController.VisualItem>> zBuckets =
                    LayerPreviewController.partitionAroundVideo(project.getTimeline());
            final java.util.List<LayerPreviewController.VisualItem> belowBucket = zBuckets.get(0);
            List<TextOverlayItem> exportTextOverlays =
                    LayerPreviewController.textsIn(zBuckets.get(1));
            // S6: sprites ride the SAME shared authority the preview's
            // SpriteOverlayView feeds from (visibleSpriteItems) — hidden SPRITE
            // tracks are excluded identically in both places by construction.
            List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> exportSpriteItems =
                    LayerPreviewController.spritesIn(zBuckets.get(1));
            // M-EXPORT-2: overlay-video (PiP) clips ride the SAME shared authority
            // the preview's OverlayVideoPreviewView binds from — hidden VIDEO
            // tracks are excluded identically in both places by construction.
            List<Clip> exportOverlayVideoClips =
                    LayerPreviewController.visibleOverlayVideoClips(project.getTimeline());
            // M-EXPORT-2 z-unification: EVERY PiP clip composites here, one
            // BlendModeGlEffect per clip in track z-order (bottom→top — chain
            // order IS z-order), before the text/caption OverlayEffect. NORMAL
            // renders as shader mode 0 (plain SRC_OVER). PiPs must all live in
            // ONE compositor: splitting NORMAL into CompositeExportOverlay and
            // blends into the chain z-inverted any project that interleaved them.
            //
            // Track mattes (§C B3): a clip whose compositing names a matte peer
            // gets that peer resolved and fed as its luma-matte source; the peer
            // itself is HIDDEN from normal rendering while it serves (its pixels
            // exist only as the recipient's alpha). A dangling peerId degrades
            // to unmatted — never a broken export.
            // Z4: the BELOW pass goes in FIRST — chain order IS paint order, so anything added
            // before the PiP blends below composites underneath them. Skipped entirely when the
            // bucket is empty, which is every project that has not reordered a lane under a PiP.
            List<TextOverlayItem> belowTexts = LayerPreviewController.textsIn(belowBucket);
            List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> belowSprites =
                    LayerPreviewController.spritesIn(belowBucket);
            if (!belowTexts.isEmpty() || !belowSprites.isEmpty()) {
                // No captions/waveforms in this pass: those are clip- and instance-owned
                // rather than lane-owned, so they have no lane z to sit below and stay in the
                // ABOVE pass where they have always been.
                CompositeExportOverlay belowOverlay = new CompositeExportOverlay(
                        context, timelineCursorMs, clip,
                        overlayW, overlayH,
                        belowTexts,
                        Collections.emptyList(),
                        project.getTimeline().getAudioClips(),
                        belowSprites,
                        project.getSpriteSheets(),
                        project.getAvatarRigs(),
                        project.getTimeline().getTotalDurationMs(),
                        editorTimeOffsetForChunk(project.getTimeline(), clip, timelineCursorMs)
                                - (isLoopBeforeItem
                                        ? headTransitionMsFor(project.getTimeline(), clip) : 0L),
                        isLoopBeforeItem ? 0L : headTransitionMsFor(project.getTimeline(), clip));
                videoEffects.add(new OverlayEffect(Collections.singletonList(belowOverlay)));
            }
            // Shared with the preview and with the overlay-audio sequence, so a matte peer
            // cannot be hidden in one place and rendered (or heard) in another.
            java.util.Set<String> servingMatteIds =
                    LayerPreviewController.servingMatteClipIds(exportOverlayVideoClips);
            // Where the PiP block begins, so adjustment layers can be inserted BETWEEN its
            // entries below rather than only after all of them.
            final int pipEffectStart = videoEffects.size();
            PipFrameStats.logForClips(exportOverlayVideoClips);
            for (Clip oc : exportOverlayVideoClips) {
                if (servingMatteIds.contains(oc.getId())) continue; // matte source: hidden
                com.fadcam.ui.faditor.model.CompositingSpec cs = oc.getCompositing();
                Clip matte = null;
                if (cs != null && cs.mattePeerId != null) {
                    for (Clip peer : exportOverlayVideoClips) {
                        if (peer.getId().equals(cs.mattePeerId)) { matte = peer; break; }
                    }
                }
                videoEffects.add(new BlendModeGlEffect(context, oc, matte,
                        editorTimeOffsetForChunk(project.getTimeline(), clip, timelineCursorMs)
                                - (isLoopBeforeItem
                                        ? headTransitionMsFor(project.getTimeline(), clip) : 0L)));
            }

            // ── Text overlays that carry their OWN effects (M7) ────────────────────────────
            // A Canvas has no shader, so these leave the CompositeExportOverlay path and get a
            // GL effect apiece. GATED on hasActiveFx(), so every text overlay that has no
            // effects — which is all of them in every project written before now — stays on
            // exactly the path it always took.
            //
            // Emitted BEFORE the adjustment layers below, so an adjustment layer still grades
            // "everything beneath it" including styled text. Emitted AFTER the PiP loop, so
            // text stays above the video, which is where it has always been.
            // IMAGE overlays are excluded here (and kept on the canvas path by
            // CompositeExportOverlay.filterTextOverlays): TextFxGlEffect rasterises text, so
            // an image handed to it would export blank. Pending the image-aware rasterizer
            // (build-list), an image's effect persists but the picture renders plain.
            for (com.fadcam.ui.faditor.model.TextOverlayItem to : exportTextOverlays) {
                if (!to.hasActiveFx() || to.isImage()) continue;
                videoEffects.add(new TextFxGlEffect(context, to,
                        editorTimeOffsetForChunk(project.getTimeline(), clip, timelineCursorMs)
                                - (isLoopBeforeItem
                                        ? headTransitionMsFor(project.getTimeline(), clip) : 0L)));
            }

            // TEXT/SPRITE below a blending GL IMAGE — static and animated both need to be under
            // the blend for the file to match the preview's promoted static raster. This is the
            // export twin of FxLivePreviewController's belowBlend bitmap: chain order IS paint
            // order, so an overlay added here composites BEFORE the ImageBlendGlEffects below.
            // Animated text/sprite below a blend remain a preview gap (per-frame raster would
            // blow 16.6ms), but the export can raster them correctly here — that divergence
            // is documented as a preview-only gap, so the file is correct even when the preview
            // shows the animated text over the blend.
            if (!GL_IMAGE_PASS) {   // GL mode interleaves by lane z below instead
                java.util.List<TextOverlayItem> allGlImages = new java.util.ArrayList<>();
                for (LayerPreviewController.VisualItem vv : LayerPreviewController.orderedVisualItems(project.getTimeline())) {
                    TextOverlayItem oo = vv.item.getTextOverlay();
                    if (oo != null && exportGlRoutedImageLike(oo)) allGlImages.add(oo);
                }
                java.util.List<TextOverlayItem> belowBlendTextsAll =
                        new java.util.ArrayList<>(LayerPreviewController.plainTextsBelowBlend(
                                project.getTimeline(), allGlImages));
                // PLAIN IMAGES below a GL-routed image belong in this same pass, and were
                // missing from it — the export half of the bug JoyRaptor reported in the preview.
                // A NORMAL image left in exportTextOverlays is painted by the FINAL
                // CompositeExportOverlay, which runs AFTER every ImageBlendGlEffect below, so
                // the blend sampled the video and then the plain picture was painted back over
                // the top of it: wrong composite AND inverted z, in the file, silently.
                // Promoting it here puts it in the overlay pass emitted BEFORE the blend block,
                // which is the export's mirror of the preview rung this promotion adds.
                // Same single authority as the preview (plainImagesBelowBlend), so the two
                // cannot answer this differently. Images already in the below-video bucket are
                // dropped by the alreadyBelowIds filter just below — that pass is emitted
                // ahead of the blends already, so they need nothing.
                // GL image pass: there ARE no plain images left to promote — every image is
                // composited by GlImageOverlayEffect at its own z.
                if (!GL_IMAGE_PASS) {
                    belowBlendTextsAll.addAll(LayerPreviewController.plainImagesBelowBlend(
                            project.getTimeline(), allGlImages));
                }
                // Back into ONE bottom→top order. The two helpers each return their own kind
                // in z order; concatenating them would paint every promoted image over every
                // promoted text no matter which lane was on top. A CompositeExportOverlay
                // paints its list in order, so the list has to BE the order.
                if (belowBlendTextsAll.size() > 1) {
                    final java.util.Map<String, Integer> zById = new java.util.HashMap<>();
                    java.util.List<LayerPreviewController.VisualItem> zOrder =
                            LayerPreviewController.orderedVisualItems(project.getTimeline());
                    for (int i = 0; i < zOrder.size(); i++) {
                        TextOverlayItem zo = zOrder.get(i).item.getTextOverlay();
                        if (zo != null) zById.put(zo.getId(), i);
                    }
                    java.util.Collections.sort(belowBlendTextsAll, (a, b) -> {
                        Integer ia = zById.get(a.getId()), ib = zById.get(b.getId());
                        return Integer.compare(ia == null ? 0 : ia, ib == null ? 0 : ib);
                    });
                }
                java.util.List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> belowBlendSpritesAll = LayerPreviewController.plainSpritesBelowBlend(project.getTimeline(), allGlImages);
                java.util.Set<String> alreadyBelowIds = new java.util.HashSet<>();
                for (TextOverlayItem b : belowTexts) alreadyBelowIds.add(b.getId());
                for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem b : belowSprites) alreadyBelowIds.add(b.getId());
                java.util.List<TextOverlayItem> belowBlendTexts = new java.util.ArrayList<>();
                for (TextOverlayItem b : belowBlendTextsAll) if (!alreadyBelowIds.contains(b.getId())) belowBlendTexts.add(b);
                java.util.List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> belowBlendSprites = new java.util.ArrayList<>();
                for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem b : belowBlendSpritesAll) if (!alreadyBelowIds.contains(b.getId())) belowBlendSprites.add(b);
                if (!belowBlendTexts.isEmpty() || !belowBlendSprites.isEmpty()) {
                    java.util.Set<String> promoteIds = new java.util.HashSet<>();
                    for (TextOverlayItem b : belowBlendTexts) promoteIds.add(b.getId());
                    for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem b : belowBlendSprites) promoteIds.add(b.getId());
                    java.util.List<TextOverlayItem> filteredExportTexts = new java.util.ArrayList<>();
                    for (TextOverlayItem o : exportTextOverlays) if (!promoteIds.contains(o.getId())) filteredExportTexts.add(o);
                    exportTextOverlays = filteredExportTexts;
                    java.util.List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> filteredSprites = new java.util.ArrayList<>();
                    for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : exportSpriteItems) if (!promoteIds.contains(s.getId())) filteredSprites.add(s);
                    exportSpriteItems = filteredSprites;
                    CompositeExportOverlay belowBlendOverlay = new CompositeExportOverlay(
                            context, timelineCursorMs, clip,
                            overlayW, overlayH,
                            belowBlendTexts,
                            Collections.emptyList(),
                            project.getTimeline().getAudioClips(),
                            belowBlendSprites,
                            project.getSpriteSheets(),
                            project.getAvatarRigs(),
                            project.getTimeline().getTotalDurationMs(),
                            editorTimeOffsetForChunk(project.getTimeline(), clip, timelineCursorMs)
                                    - (isLoopBeforeItem ? headTransitionMsFor(project.getTimeline(), clip) : 0L),
                            isLoopBeforeItem ? 0L : headTransitionMsFor(project.getTimeline(), clip));
                    videoEffects.add(new OverlayEffect(Collections.singletonList(belowBlendOverlay)));
                }
            }
                        // ── Image overlays that chose a BLEND MODE ─────────────────────────────────────
            // Blending against the video is the one thing a BitmapOverlay cannot do — a Canvas
            // has nothing underneath it — so these leave the canvas path for a shader, exactly
            // as a blended PiP does. CompositeExportOverlay.filterTextOverlays drops the same
            // items, which is what stops them being drawn twice (once blended, once plain on
            // top). GATED on a non-NORMAL mode, so every project that never picked one builds
            // the identical chain it always did.
            //
            // Emitted alongside the text-FX effects and for the same reason: chain order is
            // paint order, so a blended image sits above the video and the PiPs and below the
            // canvas overlay pass. An image the user ordered above a TEXT overlay will read as
            // below it once blended — the same z compromise TextFxGlEffect already makes, and
            // the reason blend is opt-in rather than a path every image takes.
            //
            // BOTH buckets, bottom-to-top. filterTextOverlays drops a blended image from the
            // canvas whichever bucket it is in, so iterating only the ABOVE list would have made
            // an image ordered beneath a PiP vanish from the export outright — a far worse
            // outcome than the z shift the caveat above describes.
            java.util.List<com.fadcam.ui.faditor.model.TextOverlayItem> blendCandidates =
                    new ArrayList<>(belowTexts);
            blendCandidates.addAll(exportTextOverlays);
            // SPEC_ZA — sprites that belong to the GL composite join THIS emission, merged
            // with the blended images at TRUE lane z (bottom→top) rather than appended as a
            // second group. Chain position IS z-order, so a blend samples the accumulated
            // frame beneath it: with the sprite emitted below the image above it, a
            // SCREEN-blended image over a bent sprite composites against the SPRITE, not
            // the video — which is the whole point of moving sprites into GL (on the
            // Canvas a sprite sat OVER the GL surface and no blend above it could see it).
            //
            // Provably inert for every project without a warped sprite: the sprite
            // candidates are gated on wantsGl() (false for all of them), so the merged
            // list holds exactly today's images in exactly today's order. Images among
            // themselves keep their relative order under the stable z-sort below (the
            // below bucket sorts wholly beneath the above bucket by lane zIndex, and each
            // bucket already arrives in orderedVisualItems order), so image behaviour is
            // unchanged — only a cross-type pair whose lane order contradicts type order
            // composites differently, and that is the correction, not a regression.
            //
            // The sprite candidates span all three Canvas buckets, because a warped
            // sprite below a blend was promoted out of exportSpriteItems into the
            // below-blend pass (and is dropped from its Canvas draw there by
            // filterSpriteItems, like everywhere else): emitting from exportSpriteItems
            // alone would orphan it. belowSprites and exportSpriteItems are still in
            // scope; the promotion remainder is re-derived here with the same pure
            // query the promotion block used.
            java.util.List<com.fadcam.ui.faditor.model.TextOverlayItem> allGlImagesZa =
                    new ArrayList<>();
            for (LayerPreviewController.VisualItem vv
                    : LayerPreviewController.orderedVisualItems(project.getTimeline())) {
                com.fadcam.ui.faditor.model.TextOverlayItem oo = vv.item.getTextOverlay();
                if (oo != null && exportGlRoutedImageLike(oo)) allGlImagesZa.add(oo);
            }
            java.util.Set<String> belowIdsZa = new java.util.HashSet<>();
            for (com.fadcam.ui.faditor.model.TextOverlayItem b : belowTexts) {
                belowIdsZa.add(b.getId());
            }
            for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem b : belowSprites) {
                belowIdsZa.add(b.getId());
            }
            java.util.List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> promotedSpritesZa =
                    new ArrayList<>();
            for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem b
                    : LayerPreviewController.plainSpritesBelowBlend(
                            project.getTimeline(), allGlImagesZa)) {
                if (!belowIdsZa.contains(b.getId())) promotedSpritesZa.add(b);
            }
            final java.util.Map<String, Integer> overlayZById = new java.util.HashMap<>();
            {
                java.util.List<LayerPreviewController.VisualItem> zOrder =
                        LayerPreviewController.orderedVisualItems(project.getTimeline());
                for (int i = 0; i < zOrder.size(); i++) {
                    LayerPreviewController.VisualItem v = zOrder.get(i);
                    com.fadcam.ui.faditor.model.TextOverlayItem to = v.item.getTextOverlay();
                    if (to != null && !overlayZById.containsKey(to.getId())) {
                        overlayZById.put(to.getId(), i);
                    }
                    com.fadcam.ui.faditor.sprite.SpriteOverlayItem sp = v.item.getSprite();
                    if (sp != null && !overlayZById.containsKey(sp.getId())) {
                        overlayZById.put(sp.getId(), i);
                    }
                }
            }
            java.util.List<Object> glOverlaysBottomTop = new java.util.ArrayList<>();
            for (com.fadcam.ui.faditor.model.TextOverlayItem to : blendCandidates) {
                if (exportGlRoutedImageLike(to)) glOverlaysBottomTop.add(to);
            }
            java.util.List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> spriteBucketsZa =
                    new ArrayList<>(belowSprites);
            spriteBucketsZa.addAll(promotedSpritesZa);
            spriteBucketsZa.addAll(exportSpriteItems);
            for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteBucketsZa) {
                if (s.wantsGl()) glOverlaysBottomTop.add(s);
            }
            final long overlayOffsetMs =
                    editorTimeOffsetForChunk(project.getTimeline(), clip, timelineCursorMs)
                            - (isLoopBeforeItem
                                    ? headTransitionMsFor(project.getTimeline(), clip) : 0L);
            glOverlaysBottomTop.sort((a, b) -> {
                String ida = (a instanceof com.fadcam.ui.faditor.model.TextOverlayItem)
                        ? ((com.fadcam.ui.faditor.model.TextOverlayItem) a).getId()
                        : ((com.fadcam.ui.faditor.sprite.SpriteOverlayItem) a).getId();
                String idb = (b instanceof com.fadcam.ui.faditor.model.TextOverlayItem)
                        ? ((com.fadcam.ui.faditor.model.TextOverlayItem) b).getId()
                        : ((com.fadcam.ui.faditor.sprite.SpriteOverlayItem) b).getId();
                Integer ia = overlayZById.get(ida), ib = overlayZById.get(idb);
                return Integer.compare(ia == null ? 0 : ia, ib == null ? 0 : ib);
            });
            if (GL_IMAGE_PASS) {
                // LAYER ORDER IS PAINT ORDER. Every image composites on the GPU now, so the
                // chain alternates by lane z: a run of images -> one GlImageOverlayEffect, a run
                // of texts/sprites -> one Canvas pass, bottom to top. The old "text below a GL
                // image" rule promoted every text below the HIGHEST image anywhere in the project
                // to a pass under ALL images - with images in every lane, full-frame background
                // pictures then covered nearly every text box (JoyRaptor, 2026-09-23 export:
                // "all the text layers are not rendering"). The last Canvas run is left for the
                // final pass below, which also carries captions and waveforms.
                final java.util.Map<String, Integer> zRun = overlayZById;
                java.util.List<Object> runItems = new ArrayList<>();
                for (TextOverlayItem o : exportTextOverlays) {
                    if (o.isHidden()) continue;
                    if (o.hasActiveFx() && !o.isImage()) continue;   // TextFxGlEffect, above
                    runItems.add(o);
                }
                for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem sp : exportSpriteItems) {
                    if (!sp.isHidden()) runItems.add(sp);
                }
                // ONLY WHAT THIS CLIP CAN SHOW. Every clip item's chain used to carry a pass for
                // every overlay in the project (37 effects on each of the lecture's 24 clips,
                // each a full-frame pass per frame even with nothing on screen). An overlay whose
                // time span misses this item's editor-time span (t = pts + overlayOffsetMs) can
                // never draw here; padded, since isVisibleAt does the exact per-frame gating.
                final long runWinLo = timelineCursorMs + overlayOffsetMs - RUN_WINDOW_PAD_MS;
                final long runWinHi = timelineCursorMs + overlayOffsetMs
                        + Math.max(clip.getVisualDurationMs(), clip.getTrimmedDurationMs())
                        + RUN_WINDOW_PAD_MS;
                runItems.removeIf(o -> runEndMs(o) < runWinLo || runStartMs(o) > runWinHi);
                runItems.sort((a, b) -> {
                    String ida = (a instanceof TextOverlayItem) ? ((TextOverlayItem) a).getId()
                            : ((com.fadcam.ui.faditor.sprite.SpriteOverlayItem) a).getId();
                    String idb = (b instanceof TextOverlayItem) ? ((TextOverlayItem) b).getId()
                            : ((com.fadcam.ui.faditor.sprite.SpriteOverlayItem) b).getId();
                    Integer ia = zRun.get(ida), ib = zRun.get(idb);
                    return Integer.compare(ia == null ? 0 : ia, ib == null ? 0 : ib);
                });
                // Text boxes in the GL run are drawn by the Canvas pass's own code, through one
                // drawer per clip (its lists are empty: it only lends drawTextItem).
                CompositeExportOverlay glTextDrawer = null;
                for (Object o : runItems) {
                    if (o instanceof TextOverlayItem && glTextBox((TextOverlayItem) o)) {
                        glTextDrawer = new CompositeExportOverlay(
                                context, timelineCursorMs, clip, overlayW, overlayH,
                                Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                project.getSpriteSheets(), project.getAvatarRigs(),
                                project.getTimeline().getTotalDurationMs(), overlayOffsetMs,
                                isLoopBeforeItem ? 0L
                                        : headTransitionMsFor(project.getTimeline(), clip));
                        glTextDrawer.setCaptionsViaGl(true);
                        break;
                    }
                }
                // FEWEST PASSES THAT KEEP THE ORDER. Order between a text and an image only
                // matters while both are on screen. A text above every image it shares time
                // with rides the final pass; below all of them, one bottom pass; only a text
                // with images both above AND below it at the same time needs the full
                // alternation below. Strict alternation over the whole project cost the Note 20
                // 3:32 -> 6:38 on part 1 (31% full-frame clears, 36% extra GPU passes).
                java.util.List<Object> glSeq = new ArrayList<>();
                java.util.List<Object> canvasSeq = new ArrayList<>();
                for (Object o : runItems) {
                    boolean img = o instanceof TextOverlayItem
                            && (((TextOverlayItem) o).isImage() || glTextBox((TextOverlayItem) o));
                    boolean glSp = o instanceof com.fadcam.ui.faditor.sprite.SpriteOverlayItem
                            && ((com.fadcam.ui.faditor.sprite.SpriteOverlayItem) o).wantsGl();
                    (img || glSp ? glSeq : canvasSeq).add(o);
                }
                boolean sandwiched = false;
                java.util.List<TextOverlayItem> bottomTexts = new ArrayList<>();
                java.util.List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> bottomSprites =
                        new ArrayList<>();
                java.util.List<TextOverlayItem> topTexts = new ArrayList<>();
                java.util.List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> topSprites =
                        new ArrayList<>();
                for (Object c : canvasSeq) {
                    long cs = runStartMs(c), ce = runEndMs(c);
                    Integer zc = zRun.get(runId(c));
                    int zcv = zc == null ? 0 : zc;
                    boolean imgAbove = false, imgBelow = false;
                    for (Object g : glSeq) {
                        if (runStartMs(g) >= ce || runEndMs(g) <= cs) continue;  // never together
                        Integer zg = zRun.get(runId(g));
                        if ((zg == null ? 0 : zg) > zcv) imgAbove = true; else imgBelow = true;
                    }
                    if (imgAbove && imgBelow) { sandwiched = true; break; }
                    boolean top = !imgAbove;
                    if (c instanceof TextOverlayItem) {
                        (top ? topTexts : bottomTexts).add((TextOverlayItem) c);
                    } else {
                        (top ? topSprites : bottomSprites)
                                .add((com.fadcam.ui.faditor.sprite.SpriteOverlayItem) c);
                    }
                }
                if (!sandwiched) {
                    if (!bottomTexts.isEmpty() || !bottomSprites.isEmpty()) {
                        CompositeExportOverlay bottom = new CompositeExportOverlay(
                                context, timelineCursorMs, clip, overlayW, overlayH,
                                bottomTexts, Collections.emptyList(),
                                project.getTimeline().getAudioClips(), bottomSprites,
                                project.getSpriteSheets(), project.getAvatarRigs(),
                                project.getTimeline().getTotalDurationMs(), overlayOffsetMs,
                                isLoopBeforeItem ? 0L
                                        : headTransitionMsFor(project.getTimeline(), clip));
                        bottom.setCaptionsViaGl(true);   // captions belong to the final pass
                        videoEffects.add(new OverlayEffect(Collections.singletonList(bottom)));
                    }
                    runItems = glSeq;   // the loop below now emits only the GPU run(s)
                }
                java.util.List<TextOverlayItem> imgRun = new ArrayList<>();
                java.util.List<TextOverlayItem> txtRun = new ArrayList<>();
                java.util.List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> sprRun =
                        new ArrayList<>();
                for (Object o : runItems) {
                    boolean image = o instanceof TextOverlayItem
                            && (((TextOverlayItem) o).isImage() || glTextBox((TextOverlayItem) o));
                    boolean glSprite = o instanceof com.fadcam.ui.faditor.sprite.SpriteOverlayItem
                            && ((com.fadcam.ui.faditor.sprite.SpriteOverlayItem) o).wantsGl();
                    if (image || glSprite) {
                        if (!txtRun.isEmpty() || !sprRun.isEmpty()) {
                            CompositeExportOverlay run = new CompositeExportOverlay(
                                    context, timelineCursorMs, clip, overlayW, overlayH,
                                    txtRun, Collections.emptyList(),
                                    project.getTimeline().getAudioClips(), sprRun,
                                    project.getSpriteSheets(), project.getAvatarRigs(),
                                    project.getTimeline().getTotalDurationMs(), overlayOffsetMs,
                                    isLoopBeforeItem ? 0L
                                            : headTransitionMsFor(project.getTimeline(), clip));
                            run.setCaptionsViaGl(true);   // captions belong to the final pass only
                            videoEffects.add(new OverlayEffect(Collections.singletonList(run)));
                            txtRun = new ArrayList<>();
                            sprRun = new ArrayList<>();
                        }
                        TextOverlayItem im = image ? (TextOverlayItem) o : null;
                        // Into the shared run: unbent images, and text boxes the Canvas pass
                        // would have drawn plainly. A text that blends, keys or masks keeps
                        // ImageBlendGlEffect below, as a bent image does.
                        if (im != null && (im.isImage() ? !im.hasMesh() : !im.wantsGlExport())) {
                            imgRun.add(im);
                            continue;
                        }
                        if (!imgRun.isEmpty()) {
                            videoEffects.add(new GlImageOverlayEffect(context, imgRun,
                                    project.getTimeline().getTotalDurationMs(), overlayOffsetMs,
                                    glTextDrawer));
                            imgRun = new ArrayList<>();
                        }
                        if (im != null) {
                            videoEffects.add(new ImageBlendGlEffect(context, im,
                                    project.getTimeline().getTotalDurationMs(), overlayOffsetMs));
                        } else {
                            videoEffects.add(new SpriteBlendGlEffect(context,
                                    (com.fadcam.ui.faditor.sprite.SpriteOverlayItem) o,
                                    project.getSpriteSheets(), project.getAvatarRigs(),
                                    project.getTimeline().getTotalDurationMs(), overlayOffsetMs));
                        }
                    } else {
                        if (!imgRun.isEmpty()) {
                            videoEffects.add(new GlImageOverlayEffect(context, imgRun,
                                    project.getTimeline().getTotalDurationMs(), overlayOffsetMs,
                                    glTextDrawer));
                            imgRun = new ArrayList<>();
                        }
                        if (o instanceof TextOverlayItem) txtRun.add((TextOverlayItem) o);
                        else sprRun.add((com.fadcam.ui.faditor.sprite.SpriteOverlayItem) o);
                    }
                }
                if (!imgRun.isEmpty()) {
                    videoEffects.add(new GlImageOverlayEffect(context, imgRun,
                            project.getTimeline().getTotalDurationMs(), overlayOffsetMs,
                            glTextDrawer));
                }
                if (sandwiched) {
                    exportTextOverlays = txtRun;     // the top run rides the final pass
                    exportSpriteItems = sprRun;
                } else {
                    exportTextOverlays = topTexts;
                    exportSpriteItems = topSprites;
                }
            } else {
                for (Object o : glOverlaysBottomTop) {
                    if (o instanceof com.fadcam.ui.faditor.model.TextOverlayItem) {
                        videoEffects.add(new ImageBlendGlEffect(context,
                                (com.fadcam.ui.faditor.model.TextOverlayItem) o,
                                project.getTimeline().getTotalDurationMs(), overlayOffsetMs));
                    } else {
                        com.fadcam.ui.faditor.sprite.SpriteOverlayItem sp =
                                (com.fadcam.ui.faditor.sprite.SpriteOverlayItem) o;
                        if (sp.isHidden()) continue;
                        videoEffects.add(new SpriteBlendGlEffect(context, sp,
                                project.getSpriteSheets(), project.getAvatarRigs(),
                                project.getTimeline().getTotalDurationMs(), overlayOffsetMs));
                    }
                }
            }

            // ── Adjustment layers (SPEC_ADJUSTMENT_LAYERS_FX M4) ───────────────────────────
            // Chain position IS z-order, so appending here puts these ABOVE every PiP — which
            // is exactly where getLayers emits the adjustment phase, so the default case is
            // correct by construction.
            //
            // GATED on the list being non-empty. Every project that predates adjustment layers
            // adds nothing at all here, so its chain is built by the identical code and the
            // export is byte-identical. That guarantee is the point of the gate: this sits in
            // the code the PiP z-unification fix wrote, and it must be provably inert.
            //
            // INTERLEAVED, not appended. Walking orderedCompositedItems and inserting each
            // adjustment layer at the position its LANE puts it in means a layer deliberately
            // ordered between two PiPs grades only the ones beneath it. Appending would have
            // graded all of them — wrong in the same direction as the intent, but still wrong,
            // and invisible until someone built exactly that stack.
            //
            // The PiP loop above already emitted every PiP in its own order; this pass inserts
            // the adjustment entries at the right INDEX in videoEffects rather than rebuilding
            // that loop, so the z-unification fix's code is read but never rewritten.
            if (!project.getTimeline().getAdjustmentLayers().isEmpty()) {
                final long adjustmentOffset =
                        editorTimeOffsetForChunk(project.getTimeline(), clip, timelineCursorMs)
                                - (isLoopBeforeItem
                                        ? headTransitionMsFor(project.getTimeline(), clip) : 0L);
                // How many PiP effects were just appended, and where they start. An adjustment
                // layer sitting above N of them belongs after the Nth.
                final int pipEffectCount = videoEffects.size() - pipEffectStart;
                int inserted = 0;
                int pipsSeen = 0;
                for (LayerPreviewController.VisualItem v
                        : LayerPreviewController.orderedCompositedItems(project.getTimeline())) {
                    Clip vc = v.item.getClip();
                    if (vc != null && vc.isOverlayClip()) {
                        // Only PiPs that actually reached the chain move the cursor — a matte
                        // source was skipped above and emitted nothing to sit after.
                        if (!servingMatteIds.contains(vc.getId())) pipsSeen++;
                        continue;
                    }
                    com.fadcam.ui.faditor.model.AdjustmentLayer al = v.item.getAdjustment();
                    // A layer with nothing to draw is skipped at BUILD time, not just gated per
                    // frame, so an empty layer costs no program and no pass at all.
                    if (al == null || !al.rendersAnything()) continue;
                    int at = pipEffectStart + Math.min(pipsSeen, pipEffectCount) + inserted;
                    videoEffects.add(at,
                            new AdjustmentLayerGlEffect(context, al, adjustmentOffset));
                    inserted++;
                }
            }
            boolean hasOverlays = !exportTextOverlays.isEmpty()
                    || !exportSpriteItems.isEmpty()
                    || hasAnyVisibleCaptionBinding(clip)
                    || !clipWaveformSlots.isEmpty()
                    || clipHasWaveformRef
                    || audioCaptionOverlaps;
            if (hasOverlays) {
                CompositeExportOverlay overlay = new CompositeExportOverlay(
                        context, timelineCursorMs, clip,
                        overlayW, overlayH,
                        exportTextOverlays,
                        clipWaveformSlots,
                        project.getTimeline().getAudioClips(),
                        exportSpriteItems,
                        project.getSpriteSheets(),
                        project.getAvatarRigs(),
                        project.getTimeline().getTotalDurationMs(),
                        editorTimeOffsetForChunk(project.getTimeline(), clip, timelineCursorMs)
                                - (isLoopBeforeItem
                                        ? headTransitionMsFor(project.getTimeline(), clip) : 0L),
                        isLoopBeforeItem ? 0L : headTransitionMsFor(project.getTimeline(), clip));
                // Captions leave this Canvas pass for GlCaptionEffect (same renderers, tight
                // boxes, uploaded only when they change). The Canvas pass itself is dropped when
                // captions were all it had to draw.
                boolean captionsOnGl = GL_CAPTION_PASS && overlay.hasCaptions();
                if (captionsOnGl) overlay.setCaptionsViaGl(true);
                boolean canvasWork = !exportSpriteItems.isEmpty() || !clipWaveformSlots.isEmpty()
                        || clipHasWaveformRef;
                for (TextOverlayItem to : exportTextOverlays) {
                    if (!exportGlRouted(to)) { canvasWork = true; break; }
                }
                if (!captionsOnGl || canvasWork) {
                    videoEffects.add(new OverlayEffect(Collections.singletonList(overlay)));
                }
                if (captionsOnGl) videoEffects.add(new GlCaptionEffect(overlay));
            }
        } else if (!isTransitionItem) {
            FLog.w(TAG, "assembleClipVideoEffects: cannot infer overlay dimensions for clip "
                    + clip.getId() + " (uri=" + clip.getSourceUri() + "); skipping overlay");
        }

        // Canvas resize applies to transition items TOO. The GL transition program
        // outputs frames at the outgoing clip's SOURCE size (its configure() returns
        // the input size), so without this the ~600ms transition segment was a
        // different resolution/aspect than the surrounding canvas-scaled clips —
        // the "aspect ratio messed up during the transition" glitch.
        if (canvasDims != null) {
            videoEffects.add(Presentation.createForWidthAndHeight(
                    canvasDims[0], canvasDims[1],
                    Presentation.LAYOUT_SCALE_TO_FIT));
        }

        return videoEffects;
    }

    /**
     * A spine clip's own {@link com.fadcam.ui.faditor.fx.FxStack}, dressed as the
     * full-frame, always-on, unmasked adjustment layer it semantically is, so
     * {@link AdjustmentLayerGlEffect} can host it.
     *
     * <p>Every field is left at its constructor default deliberately, and each default is
     * load-bearing: {@code startMs=0} with {@code durationMs=0} means OPEN-ENDED, so
     * {@code activeAt()} is true for every frame of the clip (the clip's own span already
     * bounds the chain — a time gate here would be a second, redundant authority that could
     * disagree with the trim); no masks and {@code keyEnabled=false} give mask coverage 1;
     * a null {@code transform} gives {@code opacityAt()==1}; {@code blendMode="NORMAL"}
     * makes the composite line the identity mix. The result is "apply the stack, keep
     * nothing of the original", which is what a clip-level effect means.</p>
     *
     * <p>The stack is ATTACHED, not copied — the same object the drawer edits — so an
     * export started while a slider is mid-drag reads the same values the preview does,
     * and {@code resolveAt()} keyframe animation works identically to an adjustment
     * layer's.</p>
     */
    @NonNull
    private static com.fadcam.ui.faditor.model.AdjustmentLayer spineFxLayer(@NonNull Clip clip) {
        com.fadcam.ui.faditor.model.AdjustmentLayer l =
                new com.fadcam.ui.faditor.model.AdjustmentLayer();
        l.setName("clip:" + clip.getId());
        l.setFx(clip.getOrCreateFx());
        return l;
    }

    /**
     * Get the source video width using MediaMetadataRetriever.
     *
     * @param clip the clip to query
     * @return width in pixels, or 0 on failure
     */
    private int getSourceWidth(@NonNull Clip clip) {
        int[] d = sourceDisplayDims(clip);
        return d[0];
    }

    /**
     * A clip's DISPLAY dimensions: stored width/height with the rotation tag applied.
     *
     * <p><b>Why the tag is not optional here.</b> A phone records portrait as 1920x1080 plus
     * "rotation 90"; media3 applies that when it decodes, so every frame this pipeline composites,
     * every overlay bitmap sized to it, and the final Presentation canvas are all in the ROTATED
     * space. Reading the raw metadata told the export a portrait project was landscape, and the
     * consequences all showed up at once in one report (JoyRaptor, 2026-08-13): a vertical project
     * exported on a landscape canvas with black bars down the sides of the video, and an image
     * overlay — whose bitmap is sized from these same numbers — drawn into a landscape bitmap that
     * was then squashed onto a portrait frame, so a 4:3 picture came out 3:4.
     *
     * <p>The editor has always been rotation-aware ({@code FaditorEditorActivity.displaySize} does
     * exactly this swap, and reads EXIF for stills), which is why the preview looked right and only
     * the export was wrong — the two disagreed about the shape of the project.</p>
     *
     * @return {@code {width, height}}, or {@code {0, 0}} if the source cannot be read.
     */
    @NonNull
    private int[] sourceDisplayDims(@NonNull Clip clip) {
        if (clip.getSourceUri() == null) return new int[]{0, 0};
        try {
            setRetrieverDataSource(clip.getSourceUri());
            android.media.MediaMetadataRetriever r = acquireRetriever();
            String w = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (w == null || h == null) return new int[]{0, 0};
            int wi = Integer.parseInt(w);
            int hi = Integer.parseInt(h);
            int deg = 0;
            try {
                String rot = r.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rot != null) deg = Integer.parseInt(rot);
            } catch (NumberFormatException ignored) {
                // Unreadable rotation: treat as upright rather than failing the whole export.
            }
            deg = ((deg % 360) + 360) % 360;
            return (deg == 90 || deg == 270) ? new int[]{hi, wi} : new int[]{wi, hi};
        } catch (Exception e) {
            FLog.w(TAG, "Failed to get source dimensions", e);
            return new int[]{0, 0};
        }
    }

    /**
     * Get the source video height using MediaMetadataRetriever.
     *
     * @param clip the clip to query
     * @return height in pixels, or 0 on failure
     */
    private int getSourceHeight(@NonNull Clip clip) {
        int[] d = sourceDisplayDims(clip);
        return d[1];
    }

    /**
     * Generate an output file path respecting the user's storage preference.
     *
     * <p>Internal mode: writes to the app-private FadCam directory
     * (same location as recordings).</p>
     * <p>Custom/SAF mode: writes to a temporary cache file; on export
     * completion the file is copied to the SAF directory.</p>
     */
    @NonNull
    private String generateOutputPath(@NonNull FaditorProject project) {
        return generateOutputPath(project, Constants.RECORDING_FILE_EXTENSION);
    }

    /** As {@link #generateOutputPath(FaditorProject)} but with an explicit file extension
     *  (e.g. {@code "m4a"} for audio-only export). Behaviour is otherwise identical. */
    private String generateOutputPath(@NonNull FaditorProject project, @NonNull String extension) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        String defaultBaseName = "Faditor_" + timestamp;

        String customBaseName = null;
        if (project.getExportSettings() != null) {
            customBaseName = sanitizeFileBaseName(project.getExportSettings().getOutputFileName());
        }
        String baseName = (customBaseName != null && !customBaseName.isEmpty())
                ? customBaseName
                : defaultBaseName;

        String fileName = baseName + "." + extension;

        String storageMode = prefsManager.getStorageMode();

        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            // SAF/Custom mode — Transformer only accepts file paths, so write to
            // a temp location first; onCompleted will copy to SAF.
            File tempDir = new File(context.getCacheDir(), "faditor_export");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            pendingSafCopy = true;
            safExportFileName = fileName;
            FLog.d(TAG, "Custom storage mode: exporting to temp, will copy to SAF");
            return new File(tempDir, fileName).getAbsolutePath();
        } else {
            // Internal mode — same directory as recordings
            File outputDir = RecordingStoragePaths.getInternalCategoryDir(
                    context,
                    RecordingStoragePaths.Category.FADITOR,
                    true
            );
            if (outputDir == null) {
                // Fallback to app files dir if category dir could not be created.
                outputDir = context.getExternalFilesDir(null);
            }
            pendingSafCopy = false;
            safExportFileName = null;
            return new File(outputDir, fileName).getAbsolutePath();
        }
    }

    /**
     * Defensively sanitize a user-provided output file base name (no extension).
     *
     * <p>Strips characters invalid in Android/FAT/NTFS file names, trims
     * whitespace, and returns {@code null} if the result is blank so callers
     * can fall back to the default timestamped name. This mirrors the
     * sanitization already applied in the export confirmation dialog, kept
     * here as a second line of defense in case this path is ever reached
     * with an unsanitized value.</p>
     *
     * @param rawName the raw user-entered base name, or {@code null}
     * @return a sanitized, trimmed base name, or {@code null}/empty if unusable
     */
    @Nullable
    private static String sanitizeFileBaseName(@Nullable String rawName) {
        if (rawName == null) return null;
        String cleaned = rawName.trim().replaceAll("[/\\\\:*?\"<>|]", "");
        return cleaned.trim();
    }

    /**
     * Copy a temp export file to the user's custom SAF storage location.
     *
     * @param tempFilePath path to the temporary export file
     * @return the SAF display name on success, or null on failure
     */
    @Nullable
    private String copyTempToSaf(@NonNull String tempFilePath) {
        String customUriString = prefsManager.getCustomStorageUri();
        if (customUriString == null) {
            FLog.e(TAG, "SAF copy: custom storage URI is null");
            return null;
        }

        File tempFile = new File(tempFilePath);
        if (!tempFile.exists()) {
            FLog.e(TAG, "SAF copy: temp file does not exist: " + tempFilePath);
            return null;
        }

        try {
            Uri treeUri = Uri.parse(customUriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(context, treeUri);
            if (pickedDir == null || !pickedDir.canWrite()) {
                FLog.e(TAG, "SAF copy: cannot write to custom directory");
                return null;
            }
            pickedDir = RecordingStoragePaths.findOrCreateChildDirectory(
                    pickedDir,
                    Constants.RECORDING_SUBDIR_FADITOR,
                    true
            );
            if (pickedDir == null || !pickedDir.canWrite()) {
                FLog.e(TAG, "SAF copy: cannot write to Faditor subdirectory");
                return null;
            }

            String name = safExportFileName != null ? safExportFileName : tempFile.getName();
            // Audio-only exports (.m4a) must be created under an audio mime — a video/*
            // mime makes some SAF providers append ".mp4" to the display name and lists
            // the file as a video. Video exports keep the exact mime used before.
            // SPEC_C_SINGLE_FRAME: image exports likewise need an image mime, or some
            // SAF providers mislabel/append to the display name.
            String lowerName = name.toLowerCase(Locale.US);
            String mime;
            if (lowerName.endsWith(".m4a")) {
                mime = "audio/mp4";
            } else if (lowerName.endsWith(".png")) {
                mime = "image/png";
            } else if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
                mime = "image/jpeg";
            } else {
                mime = "video/" + Constants.RECORDING_FILE_EXTENSION;
            }
            DocumentFile docFile = pickedDir.createFile(mime, name);
            if (docFile == null) {
                FLog.e(TAG, "SAF copy: failed to create DocumentFile: " + name);
                return null;
            }

            // Stream-copy temp file to SAF
            try (InputStream in = new FileInputStream(tempFile);
                 OutputStream out = context.getContentResolver()
                         .openOutputStream(docFile.getUri())) {
                if (out == null) {
                    FLog.e(TAG, "SAF copy: failed to open output stream");
                    return null;
                }
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }

            // Clean up temp file after successful copy
            if (tempFile.delete()) {
                FLog.d(TAG, "SAF copy: temp file deleted");
            }

            FLog.i(TAG, "SAF copy successful: " + docFile.getUri());
            return name;

        } catch (Exception e) {
            FLog.e(TAG, "SAF copy failed", e);
            return null;
        }
    }

    private static boolean hasAnyVisibleCaptionBinding(@NonNull Clip clip) {
        for (Clip.CaptionBinding b : clip.getCaptionBindings()) {
            if (!b.enabled) continue;
            if ("hidden".equals(b.styleId)) continue;
            com.fadcam.ui.faditor.transcript.NamedTranscript nt = clip.transcriptForBinding(b);
            if (nt == null || nt.transcript == null || nt.transcript.isEmpty()) continue;
            return true;
        }
        // Legacy fallback when bindings empty but old single caption fields indicate visible captions.
        if (clip.getCaptionBindings().isEmpty() && clip.isCaptionsEnabled() && clip.hasTranscript()) {
            return !"hidden".equals(clip.getCaptionStyleId());
        }
        return false;
    }
}
