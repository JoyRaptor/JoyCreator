package com.fadcam.ui.faditor.export;

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

    /** Handler for periodic progress polling. */
    private final Handler progressHandler = new Handler(Looper.getMainLooper());

    /** Reusable progress holder to avoid allocation on every poll. */
    private final ProgressHolder progressHolder = new ProgressHolder();

    /** Interval between progress polls (ms). */
    private static final long PROGRESS_POLL_INTERVAL_MS = 300;

    /** Length (ms) of the pre-generated silence WAV used to pad audio-track gaps. */
    private static final long SILENCE_FILE_MS = 600_000L; // 10 minutes

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
            if (exportRemuxer.needsRemux(f) && exportRemuxer.hasRemuxedVersion(f)) {
                java.io.File remuxed = exportRemuxer.getRemuxedFile(f);
                if (remuxed != null && remuxed.exists()) {
                    return android.net.Uri.fromFile(remuxed);
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "resolveSeekableSourceUri failed", e);
        }
        return uri;
    }

    /**
     * Callback interface for export progress and completion events.
     */
    public interface ExportListener {
        void onExportStarted(@NonNull String outputPath);
        void onExportProgress(float progress);
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

        String outputPath = generateOutputPath(project);
        isExporting = true;

        try {
            // Build the Transformer
            Transformer.Builder builder = new Transformer.Builder(context)
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
            ExportSettings exportSettings = project.getExportSettings();
            boolean qualityIsDefault = exportSettings == null
                    || exportSettings.getQuality() == ExportSettings.Quality.HIGH;
            boolean resolutionIsDefault = exportSettings == null
                    || exportSettings.getResolution() == ExportSettings.Resolution.ORIGINAL;
            if (!qualityIsDefault) {
                int bitrate = suggestedExportBitrate(project);
                if (bitrate > 0) {
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
                    builder.setEncoderFactory(new DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(encoderSettings.build())
                            .build());
                    FLog.d(TAG, "Export quality " + exportSettings.getQuality()
                            + " → requested video bitrate " + bitrate
                            + (REQUEST_BASELINE_PROFILE ? " (H.264 Baseline)" : ""));
                }
            }

            // For simple trim (single clip, no effects, normal speed, audio intact,
            // and no audio clips on the audio track) use near-lossless
            // optimization. The fast-trim path bypasses the effects chain, so we
            // must exclude any project that has overlays — otherwise text /
            // captions / waveforms would be silently dropped.
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
                    && !project.getTimeline().hasTextOverlays()
                    && !project.getTimeline().getClip(0).isCaptionsEnabled()
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

            // Add progress listener
            builder.addListener(new Transformer.Listener() {
                @Override
                public void onCompleted(@NonNull Composition composition,
                                        @NonNull ExportResult result) {
                    stopProgressPolling();
                    isExporting = false;
                    String finalPath = outputPath;

                    // If exported to temp for SAF, copy to custom storage now
                    if (pendingSafCopy) {
                        String safResult = copyTempToSaf(outputPath);
                        if (safResult != null) {
                            finalPath = safResult;
                            FLog.d(TAG, "Export copied to SAF: " + safResult);
                        } else {
                            FLog.e(TAG, "SAF copy failed, file remains at: " + outputPath);
                        }
                        pendingSafCopy = false;
                        safExportFileName = null;
                    }

                    FLog.d(TAG, "Export completed: " + finalPath);
                    if (listener != null) {
                        listener.onExportCompleted(finalPath, result);
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
                    // Clean up temp file on error
                    File tempFile = new File(outputPath);
                    if (tempFile.getParentFile() != null
                            && tempFile.getParentFile().getName().equals("faditor_export")) {
                        tempFile.delete();
                    }
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

            // Start export
            transformer.start(composition, outputPath);

            // Begin polling for progress (Transformer doesn't push progress via Listener)
            startProgressPolling();

            FLog.d(TAG, "Export started → " + outputPath);
            if (listener != null) {
                listener.onExportStarted(outputPath);
            }

        } catch (Exception e) {
            isExporting = false;
            FLog.e(TAG, "Failed to start export", e);
            writeExportErrorLog(project, e, generateOutputPath(project));
            if (listener != null) {
                listener.onExportError(e);
            }
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
        if (isExporting) {
            FLog.w(TAG, "Export already in progress");
            return;
        }
        if (project.getTimeline().isEmpty()) {
            if (listener != null) {
                listener.onExportError(new IllegalStateException("Timeline is empty"));
            }
            return;
        }

        // Match export(): re-derive attached-visualizer + link-group rider times first.
        project.getTimeline().resyncAttachedVisualizers();
        project.getTimeline().resyncLinkGroups();

        String outputPath = generateOutputPath(project, "m4a");
        isExporting = true;

        try {
            Transformer.Builder builder = new Transformer.Builder(context)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC);

            builder.addListener(new Transformer.Listener() {
                @Override
                public void onCompleted(@NonNull Composition composition,
                                        @NonNull ExportResult result) {
                    stopProgressPolling();
                    isExporting = false;
                    String finalPath = outputPath;
                    if (pendingSafCopy) {
                        String safResult = copyTempToSaf(outputPath);
                        if (safResult != null) {
                            finalPath = safResult;
                        } else {
                            FLog.e(TAG, "SAF copy failed, file remains at: " + outputPath);
                        }
                        pendingSafCopy = false;
                        safExportFileName = null;
                    }
                    FLog.d(TAG, "Audio-only export completed: " + finalPath);
                    if (listener != null) {
                        listener.onExportCompleted(finalPath, result);
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
                    File tempFile = new File(outputPath);
                    if (tempFile.getParentFile() != null
                            && tempFile.getParentFile().getName().equals("faditor_export")) {
                        tempFile.delete();
                    }
                    FLog.e(TAG, "Audio-only export failed", exception);
                    writeExportErrorLog(project, exception, outputPath);
                    if (listener != null) {
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

            transformer.start(composition, outputPath);
            startProgressPolling();

            FLog.d(TAG, "Audio-only export started → " + outputPath);
            if (listener != null) {
                listener.onExportStarted(outputPath);
            }
        } catch (Exception e) {
            isExporting = false;
            FLog.e(TAG, "Failed to start audio-only export", e);
            writeExportErrorLog(project, e, outputPath);
            if (listener != null) {
                listener.onExportError(e);
            }
        }
    }

    /**
     * Build an audio-only {@link Composition}: one sequence of the master clips' audio
     * (image / muted / loop segments → silence, preserving timing) plus the {@link AudioClip}
     * track (via {@link #buildAudioSequence}), mixed. Reuses the existing per-clip audio
     * treatment (Sonic speed, volume envelope/static, mute) without touching the video path.
     */
    @NonNull
    private Composition buildAudioOnlyComposition(@NonNull FaditorProject project) {
        Timeline timeline = project.getTimeline();
        File silenceFile = getOrCreateSilenceFile();
        Uri silenceUri = silenceFile != null ? Uri.fromFile(silenceFile) : null;

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
                long overlapSourceMs = Math.round(prevTrans.durationMs * clip.getSpeedMultiplier());
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
                    if (speed != 1.0f) {
                        SonicAudioProcessor sap = new SonicAudioProcessor();
                        sap.setSpeed(speed);
                        if (clip.isPitchCompensationEnabled()) sap.setPitch(1.0f);
                        aps.add(sap);
                    }
                    if (clip.hasVolumeKeyframes()) {
                        List<Clip.VolumeKeyframe> kfs = clip.getVolumeKeyframes();
                        long[] times = new long[kfs.size()];
                        float[] vols = new float[kfs.size()];
                        for (int i = 0; i < kfs.size(); i++) {
                            times[i] = kfs.get(i).timeMs;
                            vols[i] = kfs.get(i).volume;
                        }
                        VolumeAudioProcessor vp = new VolumeAudioProcessor();
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
            sequences.add(new EditedMediaItemSequence.Builder(master).build());
        }
        if (timeline.hasAudioClips()) {
            EditedMediaItemSequence audioSequence = buildAudioSequence(timeline);
            if (audioSequence != null) {
                sequences.add(audioSequence);
            }
        }
        // SPEC_PIP_AUDIO: an opted-in PiP contributes audio to the audio-only export too.
        EditedMediaItemSequence overlayAudioOnly = buildOverlayAudioSequence(timeline);
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
        return new Composition.Builder(sequences).build();
    }

    /** Append chunked silence totalling {@code durationMs} to {@code items} (no-op if there
     *  is no silence source or the duration is non-positive). Chunks at {@link #SILENCE_FILE_MS}
     *  so gaps longer than the silence file are covered by multiple items. */
    private void addSilence(@NonNull List<EditedMediaItem> items, @Nullable Uri silenceUri,
                            long durationMs) {
        if (silenceUri == null || durationMs <= 0) return;
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
        if (transformer != null && isExporting) {
            stopProgressPolling();
            transformer.cancel();
            isExporting = false;
            FLog.d(TAG, "Export cancelled");
        }
    }

    /**
     * Start periodic progress polling using Transformer.getProgress().
     * Media3 Transformer does not push progress via its Listener; it must be polled.
     */
    private void startProgressPolling() {
        progressHandler.removeCallbacksAndMessages(null);
        progressHandler.postDelayed(progressPoller, PROGRESS_POLL_INTERVAL_MS);
    }

    /** Stop polling for progress. */
    private void stopProgressPolling() {
        progressHandler.removeCallbacksAndMessages(null);
    }

    /** Runnable that periodically polls Transformer progress and forwards to listener. */
    private final Runnable progressPoller = new Runnable() {
        @Override
        public void run() {
            if (transformer == null || !isExporting) return;
            try {
                int state = transformer.getProgress(progressHolder);
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    float progress = progressHolder.progress / 100f;
                    if (listener != null) {
                        listener.onExportProgress(progress);
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

    @NonNull
    private Composition buildComposition(@NonNull FaditorProject project) {
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

        // Pre-load waveform data for all waveform overlays
        Map<String, WaveformData> waveformCache = preloadWaveformData(timeline);

        // Pre-load waveform style presets
        List<WaveformStyle> builtinStyles = WaveformStyleIO.loadBuiltins(context);

        // Build per-clip composite overlays
        List<CompositeExportOverlay.WaveformSlot> waveformSlots =
                buildWaveformSlots(timeline, waveformCache, builtinStyles, outW, outH);

        List<EditedMediaItem> items = new ArrayList<>();
        long timelineCursorMs = 0;

        for (int ci = 0; ci < timeline.getClipCount(); ci++) {
            Clip clip = timeline.getClip(ci);

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

            // ── Loop/ping-pong extensions BEFORE the main clip ──
            if (clip.hasLoopExtension() && !clip.isImageClip()) {
                long trimmedPlayMs = clip.getTrimmedDurationMs();
                long loopBeforeMs = clip.getLoopBeforeMs();

                if (loopBeforeMs > 0 && trimmedPlayMs > 0) {
                    int reps = (int) Math.ceil(loopBeforeMs / (double) trimmedPlayMs);
                    for (int r = 0; r < reps; r++) {
                        EditedMediaItem extItem = buildLoopExtensionItem(project, clip,
                                loopBeforeMs, trimmedPlayMs, reps, r,
                                timelineCursorMs, outW, outH, canvasDims,
                                waveformSlots, true);
                        if (extItem != null) {
                            items.add(extItem);
                            timelineCursorMs += extItem.durationUs / 1000;
                        }
                    }
                }
            }

            // ── Build the main clip item (the part NOT in the transition) ──
            if (mainOutMs > clipInMs && !mainBodyDegenerate) {
                long mainDurationMs = clipInMs >= clipOutMs ? 0 : (mainOutMs - clipInMs);
                EditedMediaItem mainItem = buildClipItem(project, clip, clipInMs, mainOutMs,
                        timelineCursorMs, outW, outH, canvasDims,
                        waveformSlots);
                items.add(mainItem);
                timelineCursorMs += mainItem.durationUs / 1000;
            } else if (mainOutMs > clipInMs) {
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
                if (transOutMs > transInMs && transTimelineMs >= MIN_EXPORT_SEGMENT_MS) {
                    // Transition item uses the first clip's source (clipped to overlap) with GL effect
                    EditedMediaItem transItem = buildTransitionItem(project, clip, transInMs, transOutMs,
                            nextClip, trans, timelineCursorMs, outW, outH, canvasDims, waveformSlots);
                    if (transItem != null) {
                        items.add(transItem);
                        timelineCursorMs += transItem.durationUs / 1000;
                    }
                } else if (transOutMs > transInMs) {
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
                        EditedMediaItem extItem = buildLoopExtensionItem(project, clip,
                                loopAfterMs, trimmedPlayMs, reps, r,
                                timelineCursorMs, outW, outH, canvasDims,
                                waveformSlots, false);
                        if (extItem != null) {
                            items.add(extItem);
                            timelineCursorMs += extItem.durationUs / 1000;
                        }
                    }
                }
            }
        }

        EditedMediaItemSequence videoSequence =
                new EditedMediaItemSequence.Builder(items).build();

        List<EditedMediaItemSequence> sequences = new ArrayList<>();
        sequences.add(videoSequence);

        // M-EXPORT-2: overlay-VIDEO (PiP) clips are composited by CompositeExportOverlay
        // (the BitmapOverlay pass in assembleClipVideoEffects), NOT by a second video
        // sequence. Probe #3 (PLAN Part 10, verified against DefaultVideoCompositor
        // source): the compositor draws sequences back-to-front with the PRIMARY stream
        // ON TOP, so a secondary sequence composites the PiP UNDERNEATH the opaque
        // master — invisible. The overlay pass also keeps the PiP below text/captions,
        // matching the preview stack, which a second sequence never could.

        // Build audio sequence from AudioClips on the audio track (if any)
        if (timeline.hasAudioClips()) {
            EditedMediaItemSequence audioSequence = buildAudioSequence(timeline);
            if (audioSequence != null) {
                sequences.add(audioSequence);
            }
        }
        // SPEC_PIP_AUDIO: PiP audio rides its own audio-only sequence (the pixels come from
        // the overlay pass above). Null unless a PiP opted in → composition unchanged.
        EditedMediaItemSequence overlayAudio = buildOverlayAudioSequence(timeline);
        if (overlayAudio != null) {
            sequences.add(overlayAudio);
        }

        return new Composition.Builder(sequences).build();
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


    @NonNull
    private EditedMediaItem buildClipItem(@NonNull FaditorProject project,
                                           @NonNull Clip clip,
                                           long clipInMs, long clipOutMs,
                                           long timelineCursorMs,
                                           int outW, int outH,
                                           @Nullable int[] canvasDims,
                                           @NonNull List<CompositeExportOverlay.WaveformSlot> waveformSlots) {
        float speed = clip.getSpeedMultiplier();

        MediaItem mediaItem;
        long sourceDurationMs;
        if (clip.isImageClip()) {
            sourceDurationMs = Math.max(1L, clipOutMs - clipInMs);
            mediaItem = new MediaItem.Builder()
                    .setUri(clip.getSourceUri())
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
        boolean dropAudio = clip.isAudioMuted() || clip.isImageClip();
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
            if (speed != 1.0f) {
                SonicAudioProcessor sonicProcessor = new SonicAudioProcessor();
                sonicProcessor.setSpeed(speed);
                if (clip.isPitchCompensationEnabled()) sonicProcessor.setPitch(1.0f);
                audioProcessors.add(sonicProcessor);
            }
            VolumeAudioProcessor volumeProcessor = new VolumeAudioProcessor();
            boolean volumeAdjusted = false;
            if (clip.hasVolumeKeyframes()) {
                List<Clip.VolumeKeyframe> kfs = clip.getVolumeKeyframes();
                long[] times = new long[kfs.size()];
                float[] vols = new float[kfs.size()];
                for (int i = 0; i < kfs.size(); i++) {
                    times[i] = kfs.get(i).timeMs;
                    vols[i] = kfs.get(i).volume;
                }
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
            if (speed != 1.0f) {
                SonicAudioProcessor sap = new SonicAudioProcessor();
                sap.setSpeed(speed);
                if (clip.isPitchCompensationEnabled()) sap.setPitch(1.0f);
                aps.add(sap);
            }
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
        if (speed != 1.0f && !clip.isAudioMuted()) {
            SonicAudioProcessor sap = new SonicAudioProcessor();
            sap.setSpeed(speed);
            audioProcessors.add(sap);
        }

        // No reverse-mirror effect: a true reverse leg comes pre-reversed (video AND areverse'd
        // audio) from the baked file, and the un-baked fallback is plain forward. Effects (overlay,
        // opacity, presentation, crop, color) apply normally on top.
        List<Effect> videoEffects = assembleClipVideoEffects(
                clip, project, timelineCursorMs, outW, outH,
                canvasDims, waveformSlots,
                /* isTransitionItem = */ false,
                /* preOverlayExtra = */ null);

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
                /* preOverlayExtra = */ null);

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
    private Map<String, WaveformData> preloadWaveformData(@NonNull Timeline timeline) {
        Map<String, WaveformData> cache = new HashMap<>();
        FLog.d(TAG, "preloadWaveformData: waveformOverlayCount="
                + timeline.getWaveformOverlays().size()
                + " hasAny=" + timeline.hasWaveformOverlays());
        if (!timeline.hasWaveformOverlays()) return cache;
        WaveformExtractor extractor = new WaveformExtractor(context);
        for (WaveformOverlayInstance woi : LayerPreviewController.visibleWaveformOverlays(timeline)) { // §4.5 per-object eye
            String clipId = woi.getAudioSourceRef();
            FLog.d(TAG, "preloadWaveformData: waveform " + woi.getId()
                    + " style=" + woi.getStyleId()
                    + " audioSourceRef=" + clipId);
            if (clipId == null) continue;
            android.net.Uri uri = resolveWaveformUri(timeline, clipId);
            if (uri == null) {
                FLog.w(TAG, "preloadWaveformData: no clip with id " + clipId
                        + " for waveform " + woi.getId());
                continue;
            }
            String key = uri.toString();
            if (cache.containsKey(key)) continue;
            try {
                WaveformData data = extractor.extract(uri, 64);
                if (data != null) {
                    cache.put(key, data);
                    FLog.d(TAG, "preloadWaveformData: extracted " + data.amplitudes.length
                            + " buckets for " + key);
                } else {
                    FLog.w(TAG, "preloadWaveformData: extractor returned null for " + key);
                }
            } catch (Exception e) {
                FLog.w(TAG, "Failed to preload waveform data for " + key, e);
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
     * Build an audio-only {@link EditedMediaItemSequence} from the timeline's
     * {@link AudioClip}s. Silence gaps are inserted so that each clip starts
     * at its correct {@link AudioClip#getOffsetMs()} position.
     *
     * @param timeline the project timeline
     * @return the audio sequence, or null if building failed
     */
    @Nullable
    private EditedMediaItemSequence buildAudioSequence(@NonNull Timeline timeline) {
        List<AudioClip> clips = new ArrayList<>(timeline.getAudioClips());
        FLog.d(TAG, "buildAudioSequence: audioClipCount=" + clips.size());
        if (clips.isEmpty()) return null;

        // Sort by offset so we insert gaps correctly
        Collections.sort(clips, Comparator.comparingLong(AudioClip::getOffsetMs));

        // Generate a reusable silence WAV file in cache
        File silenceFile = getOrCreateSilenceFile();
        if (silenceFile == null) {
            FLog.e(TAG, "Failed to create silence file — skipping audio track");
            return null;
        }
        Uri silenceUri = Uri.fromFile(silenceFile);

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
                long remainingGapMs = clipStartMs - cursorMs;
                while (remainingGapMs > 0) {
                    long chunkMs = Math.min(remainingGapMs, SILENCE_FILE_MS);
                    audioItems.add(buildSilenceItem(silenceUri, chunkMs));
                    remainingGapMs -= chunkMs;
                }
                cursorMs = clipStartMs;
            }

            // Build the audio clip item with trim & volume
            MediaItem.ClippingConfiguration clipping =
                    new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(ac.getInPointMs())
                            .setEndPositionMs(ac.getOutPointMs())
                            .build();

            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(ac.getSourceUri())
                    .setClippingConfiguration(clipping)
                    .build();

            EditedMediaItem.Builder editBuilder = new EditedMediaItem.Builder(mediaItem)
                    .setRemoveVideo(true); // audio only

            // Apply volume: the keyframe envelope (blue automation curve) takes
            // precedence over a static level. The envelope was previously dropped here
            // (only the static level was honored), so audio-clip fades did nothing on
            // export. Times are clip-local ms (0 = clip in-point), matching
            // VolumeAudioProcessor's frame-position clock for the clipped item.
            float volume = ac.getVolumeLevel();
            VolumeAudioProcessor volumeProcessor = new VolumeAudioProcessor();
            boolean volumeAdjusted = false;
            if (ac.hasVolumeKeyframes()) {
                List<AudioClip.VolumeKeyframe> kfs = ac.getVolumeKeyframes();
                long[] times = new long[kfs.size()];
                float[] vols = new float[kfs.size()];
                for (int i = 0; i < kfs.size(); i++) {
                    times[i] = kfs.get(i).timeMs;
                    vols[i] = kfs.get(i).volume;
                }
                volumeProcessor.setVolumeEnvelope(times, vols);
                volumeAdjusted = true;
            } else if (Math.abs(volume - 1.0f) >= 0.01f) {
                volumeProcessor.setVolume(volume);
                volumeAdjusted = true;
            }
            if (volumeAdjusted) {
                List<AudioProcessor> processors = new ArrayList<>();
                processors.add(volumeProcessor);
                editBuilder.setEffects(new Effects(processors, Collections.emptyList()));
            }

            audioItems.add(editBuilder.build());
            cursorMs = clipStartMs + ac.getTrimmedDurationMs();
        }

        if (audioItems.isEmpty()) {
            FLog.d(TAG, "All audio clips muted — no audio sequence");
            return null;
        }

        FLog.d(TAG, "buildAudioSequence: built " + audioItems.size()
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
    private EditedMediaItemSequence buildOverlayAudioSequence(@NonNull Timeline timeline) {
        // Source the clip list from the SHARED visibility authority, not the raw list: a PiP
        // hidden by its lane's eye (or its own per-object eye) is excluded from the exported
        // PIXELS by this same method, and an object excluded from the export must not keep
        // contributing audio. Reading getOverlayClips() here would have done exactly that.
        // Hoist getLayers() out of the loop: it rebuilds every lane view from the flat lists
        // on each call, and the volume authority needs it per clip.
        List<Track> lanes = timeline.getLayers();
        List<Clip> overlays = new ArrayList<>();
        Map<String, Float> volumes = new HashMap<>();
        for (Clip c : LayerPreviewController.visibleOverlayVideoClips(timeline)) {
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
                long gap = startMs - cursorMs;
                while (gap > 0) {
                    long chunk = Math.min(gap, SILENCE_FILE_MS);
                    items.add(buildSilenceItem(silenceUri, chunk));
                    gap -= chunk;
                }
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
            if (Math.abs(speed - 1.0f) >= 0.001f && speed > 0) {
                SonicAudioProcessor sonic = new SonicAudioProcessor();
                sonic.setSpeed(speed);
                if (c.isPitchCompensationEnabled()) sonic.setPitch(1.0f);
                processors.add(sonic);
            }
            float volume = volumes.get(c.getId());
            if (Math.abs(volume - 1.0f) >= 0.01f) {
                VolumeAudioProcessor vp = new VolumeAudioProcessor();
                vp.setVolume(volume);
                processors.add(vp);
            }
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
     * Build an {@link EditedMediaItem} of silence for the given duration.
     * Uses a pre-generated 1-second silent WAV file and clips it to the
     * required duration. For gaps longer than 1 s the file is looped or
     * a longer file is generated.
     */
    @NonNull
    private EditedMediaItem buildSilenceItem(@NonNull Uri silenceUri, long durationMs) {
        // Clip the silence file to the required duration
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(silenceUri)
                .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(0)
                                .setEndPositionMs(durationMs)
                                .build())
                .build();

        return new EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .build();
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
        File cacheDir = new File(context.getCacheDir(), "faditor_export");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        File silenceFile = new File(cacheDir, "silence.wav");
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
     *   <li>{@link OverlayEffect} for text + captions + waveform</li>
     *   <li>{@link OpacityExportEffect} — post-process, affects the composited result</li>
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
        if (isVideo) {
            String cropPreset = clip.getCropPreset();
            if ("custom".equals(cropPreset)) {
                float left   = clip.getCropLeft()  * 2f - 1f;
                float right  = clip.getCropRight() * 2f - 1f;
                float top    = 1f - clip.getCropTop()    * 2f;
                float bottom = 1f - clip.getCropBottom() * 2f;
                videoEffects.add(new Crop(left, right, bottom, top));
            } else if (!"none".equals(cropPreset)) {
                float[] cr = getCropRect(cropPreset);
                if (cr != null) videoEffects.add(new Crop(cr[0], cr[1], cr[2], cr[3]));
            }
        }

        if (!isTransitionItem && clip.getEffectStack().isActive()) {
            videoEffects.addAll(clip.getEffectStack().toEffects(context, false));
        }

        if (preOverlayExtra != null) {
            videoEffects.add(preOverlayExtra);
        }

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
        // Inserting a Presentation here (image clips only) scales the decoded image
        // to the authoring canvas up front, so configure() sees overlayW x overlayH,
        // the overlay scale is 1:1, and overlays composite correctly. Video clips are
        // NOT touched — their branch of this method is byte-identical to before, so
        // the M-EXPORT-1 no-overlay/healthy-clip regression gate is preserved.
        if (!isTransitionItem && clip.isImageClip() && overlayW > 0 && overlayH > 0) {
            videoEffects.add(Presentation.createForWidthAndHeight(
                    overlayW, overlayH, Presentation.LAYOUT_SCALE_TO_FIT));
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
            long clipTlStart = timelineCursorMs;
            long clipTlEnd = timelineCursorMs + clip.getTrimmedDurationMs();
            for (AudioClip ac : project.getTimeline().getAudioClips()) {
                if (!ac.isCaptionsEnabled() || !ac.hasTranscript()) continue;
                if ("hidden".equals(ac.getCaptionStyleId())) continue;
                long aStart = ac.getOffsetMs();
                long aEnd = ac.getOffsetMs() + ac.getTrimmedDurationMs();
                if (aStart < clipTlEnd && aEnd > clipTlStart) {
                    audioCaptionOverlaps = true;
                    break;
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
                        project.getAvatarRigs());
                videoEffects.add(new OverlayEffect(Collections.singletonList(belowOverlay)));
            }
            java.util.Set<String> servingMatteIds = new java.util.HashSet<>();
            for (Clip oc : exportOverlayVideoClips) {
                com.fadcam.ui.faditor.model.CompositingSpec cs = oc.getCompositing();
                if (cs != null && cs.mattePeerId != null) {
                    servingMatteIds.add(cs.mattePeerId);
                }
            }
            for (Clip oc : exportOverlayVideoClips) {
                if (servingMatteIds.contains(oc.getId())) continue; // matte source: hidden
                com.fadcam.ui.faditor.model.CompositingSpec cs = oc.getCompositing();
                Clip matte = null;
                if (cs != null && cs.mattePeerId != null) {
                    for (Clip peer : exportOverlayVideoClips) {
                        if (peer.getId().equals(cs.mattePeerId)) { matte = peer; break; }
                    }
                }
                videoEffects.add(new BlendModeGlEffect(context, oc, matte));
            }
            boolean hasOverlays = !exportTextOverlays.isEmpty()
                    || !exportSpriteItems.isEmpty()
                    || clip.isCaptionsEnabled()
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
                        project.getAvatarRigs());
                videoEffects.add(new OverlayEffect(Collections.singletonList(overlay)));
            }
        } else if (!isTransitionItem) {
            FLog.w(TAG, "assembleClipVideoEffects: cannot infer overlay dimensions for clip "
                    + clip.getId() + " (uri=" + clip.getSourceUri() + "); skipping overlay");
        }

        if (!isTransitionItem && clip.hasOpacityKeyframes()) {
            videoEffects.add(new OpacityExportEffect(clip, timelineCursorMs));
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
     * Returns crop bounds [left, right, bottom, top] for a Crop effect based on
     * the aspect ratio preset, or null if the preset is unknown.
     *
     * @param preset crop preset key
     * @return float array or null
     */
    @Nullable
    private static float[] getCropRect(@NonNull String preset) {
        switch (preset) {
            case "1:1":   return new float[]{-1f, 1f, -1f, 1f};
            case "16:9":  return new float[]{-1f, 1f, -1f, 1f};
            case "9:16":  return new float[]{-0.3125f, 0.3125f, -1f, 1f};
            case "4:3":   return new float[]{-0.833f, 0.833f, -1f, 1f};
            case "3:4":   return new float[]{-0.375f, 0.375f, -1f, 1f};
            case "21:9":  return new float[]{-1f, 1f, -0.643f, 0.643f};
            default:      return null;
        }
    }

    /**
     * Get the source video width using MediaMetadataRetriever.
     *
     * @param clip the clip to query
     * @return width in pixels, or 0 on failure
     */
    private int getSourceWidth(@NonNull Clip clip) {
        if (clip.getSourceUri() == null) return 0;
        try {
            setRetrieverDataSource(clip.getSourceUri());
            android.media.MediaMetadataRetriever r = acquireRetriever();
            String w = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            return w != null ? Integer.parseInt(w) : 0;
        } catch (Exception e) {
            FLog.w(TAG, "Failed to get source width", e);
            return 0;
        }
    }

    /**
     * Get the source video height using MediaMetadataRetriever.
     *
     * @param clip the clip to query
     * @return height in pixels, or 0 on failure
     */
    private int getSourceHeight(@NonNull Clip clip) {
        if (clip.getSourceUri() == null) return 0;
        try {
            setRetrieverDataSource(clip.getSourceUri());
            android.media.MediaMetadataRetriever r = acquireRetriever();
            String h = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            return h != null ? Integer.parseInt(h) : 0;
        } catch (Exception e) {
            FLog.w(TAG, "Failed to get source height", e);
            return 0;
        }
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
            String mime = name.toLowerCase(Locale.US).endsWith(".m4a")
                    ? "audio/mp4"
                    : "video/" + Constants.RECORDING_FILE_EXTENSION;
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
}
