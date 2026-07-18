package com.fadcam.ui.faditor.player;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.os.SystemClock;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.ui.PlayerView;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.compositor.MasterPlaybackEngine;

/**
 * Manages ExoPlayer lifecycle for the Faditor editor.
 *
 * <p>Binds to an Activity lifecycle to auto-pause on background and release on destroy.
 * Handles single-clip playback with manual trim bounds (no ClippingConfiguration)
 * to support fragmented MP4 and SAF content:// URIs reliably.</p>
 *
 * <p><b>M-COMP-0 (gapless master playback):</b> when {@link #GAPLESS_ENGINE} is on and the
 * project is a plain-cut single track (PLUS L1: any NORMAL-mode loop-extension clips; PLUS L2:
 * PING_PONG loop clips whose reversed segment is baked/cached — see PLAN_LOOP_PINGPONG.md), this
 * manager delegates its single-clip public API to a {@link MasterPlaybackEngine} that plays the
 * whole track as one pre-buffered ClippingConfiguration playlist — so plain cuts, loop-extension
 * wraps, AND true ping-pong reverse legs (played from a baked reversed file) all cross warm (no
 * cold re-prepare / boundary freeze, no poll-based seekTo(0)). All position/seek/transport calls
 * are preserved as clip-local (CONTINUOUS across a looped clip's reps — see
 * {@link MasterPlaybackEngine#getCurrentPositionInWindow()}) so {@code FaditorEditorActivity}'s
 * polling loop is unchanged. IMAGE clips play as native media3 image windows (P0 fix 2026-07-07 —
 * a freeze-frame insert no longer disqualifies the whole project). STILL loop clips, transitions,
 * and PING_PONG clips whose reverse bake isn't ready yet fall back to the legacy single-clip path
 * below (for the last, that means a FORWARD-TAIL preview until the bake completes and a rebuild
 * promotes it). See {@code MasterPlaybackEngine} for details.</p>
 */
public class FaditorPlayerManager implements DefaultLifecycleObserver {

    private static final String TAG = "FaditorPlayerManager";
    private static final long SEEK_GRACE_MS = 250L;

    /**
     * M-COMP-0 feature flag. When true and the project is eligible (plain cuts only), the master
     * track plays as a gapless ClippingConfiguration playlist. DEFAULT ON — device acceptance on
     * the Note 9 sandbox showed no regressions on the plain-cut path and the boundary freeze is
     * eliminated; ineligible projects transparently keep today's engine. Flip to false to force
     * every project back to the legacy per-seam single-clip path.
     */
    public static final boolean GAPLESS_ENGINE = true;

    @Nullable
    private ExoPlayer player;

    @Nullable
    private PlayerView playerView;

    // ── M-COMP-0 gapless engine (null unless flag on AND project eligible) ──
    @Nullable
    private MasterPlaybackEngine gaplessEngine;
    @Nullable
    private Timeline gaplessTimeline;
    @Nullable
    private MasterPlaybackEngine.SourceResolver gaplessResolver;
    @Nullable
    private MasterPlaybackEngine.SeamListener gaplessSeamListener;
    /** Rank-1: forwarded to every engine built so a reverse-leg decode failure poisons that clip's
     *  reversed URI + rebuilds (per-clip forward degrade) instead of a whole-timeline blackout. */
    @Nullable
    private MasterPlaybackEngine.ErrorRecoveryListener gaplessErrorRecoveryListener;
    @Nullable
    private String exportResumeClipId;
    private long exportResumePosMs = 0L;
    @Nullable
    private String gaplessResumeClipId;
    private long gaplessResumePosMs = 0L;
    /**
     * Listeners registered via {@link #addListener(Player.Listener)}. Retained so that every time
     * the gapless engine builds a NEW {@link ExoPlayer} (initial prepare, trim rebuild, onStart /
     * after-export re-acquire), they are re-attached to the player actually rendering — otherwise
     * the activity's play-state / video-size / duration-correction callbacks would silently stop
     * firing after the first engine teardown.
     */
    @NonNull
    private final java.util.List<Player.Listener> registeredListeners = new java.util.ArrayList<>();

    @NonNull
    private final Context context;

    @Nullable
    private Clip currentClip;

    private boolean playWhenReady = false;
    private long lastPosition = 0;

    /** LoudnessEnhancer for volume amplification above 100%. */
    @Nullable
    private LoudnessEnhancer loudnessEnhancer;

    /**
     * F6 (PERF_SPEC_LONGFILE_20260718): builds a moof-indexed, seekable MediaSource for a
     * RAW fragmented MP4 in seconds (the same VLC-like path PlayerHolder uses) — so the
     * legacy single-clip path can actually PLAY/seek a raw fMP4 without the 40s remux that
     * F1 removed. Without this, a raw fMP4 handed to plain setMediaItem has no seek map:
     * seekTo(trimStart) lands nowhere and every clip reads as instantly ENDED, so playback
     * auto-advances clip→clip and sticks (the regression F1 exposed). Lazily created.
     */
    @Nullable
    private com.fadcam.playback.SeekableFragmentedMp4MediaSourceFactory fmp4SourceFactory;

    // ── Manual trim bounds (replaces ClippingConfiguration) ──────────
    private long trimStartMs = 0;
    private long trimEndMs = Long.MAX_VALUE;

    /** Pending seek after player becomes READY (e.g. after prepare). */
    private long pendingSeekMs = -1;
    private long lastSeekRequestMs = -1L;

    /** Whether the media source needs re-preparation (URI changed). */
    private boolean needsPrepare = true;

    /** Internal listener for handling pending seeks and playback state. */
    private final Player.Listener internalListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            String stateStr;
            switch (playbackState) {
                case Player.STATE_IDLE: stateStr = "IDLE"; break;
                case Player.STATE_BUFFERING: stateStr = "BUFFERING"; break;
                case Player.STATE_READY: stateStr = "READY"; break;
                case Player.STATE_ENDED: stateStr = "ENDED"; break;
                default: stateStr = "UNKNOWN(" + playbackState + ")";
            }
            FLog.d(TAG, "Playback state: " + stateStr + ", pendingSeek=" + pendingSeekMs);

            if (playbackState == Player.STATE_READY && pendingSeekMs >= 0) {
                if (player != null) {
                    FLog.d(TAG, "Executing pending seek to " + pendingSeekMs + "ms (absolute)");
                    player.seekTo(pendingSeekMs);
                }
                pendingSeekMs = -1;
            }
        }

        @Override
        public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
            FLog.e(TAG, "Player error: " + error.getMessage()
                    + ", code=" + error.errorCode, error);
        }
    };

    public FaditorPlayerManager(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        initializePlayer();
        reacquireGaplessIfNeeded();
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        if (player == null) {
            initializePlayer();
        }
        reacquireGaplessIfNeeded();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        if (gapless()) {
            playWhenReady = gaplessEngine.getPlayWhenReady();
            gaplessEngine.pause();
        }
        if (player != null) {
            if (!gapless()) playWhenReady = player.getPlayWhenReady();
            lastPosition = player.getCurrentPosition();
            player.pause();
        }
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        // Remember the gapless position so onStart/onResume can restore it.
        if (gapless()) {
            gaplessResumeClipId = currentClip != null ? currentClip.getId() : null;
            gaplessResumePosMs = gaplessEngine.getCurrentPositionInWindow();
        }
        releaseGaplessEngine();
        releasePlayer();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        releaseGaplessEngine();
        releasePlayer();
    }

    private void releaseGaplessEngine() {
        if (gaplessEngine != null) {
            gaplessEngine.releasePlayer();
            gaplessEngine = null;
        }
    }

    /** Rebuild the gapless playlist after a lifecycle release (onStop), restoring position. */
    private void reacquireGaplessIfNeeded() {
        if (!GAPLESS_ENGINE || gaplessEngine != null || gaplessTimeline == null
                || gaplessResolver == null || gaplessSeamListener == null || playerView == null) {
            return;
        }
        if (!MasterPlaybackEngine.isEligible(gaplessTimeline, gaplessResolver)) return;
        gaplessEngine = new MasterPlaybackEngine(context, gaplessResolver, gaplessSeamListener);
        configureEngine(gaplessEngine);
        if (!gaplessEngine.prepareTimeline(gaplessTimeline, playerView)) {
            gaplessEngine.releasePlayer();
            gaplessEngine = null;
            return;
        }
        attachRegisteredListenersToEngine();
        if (gaplessResumeClipId != null) {
            gaplessEngine.seekInClip(gaplessResumeClipId, gaplessResumePosMs);
        }
        if (player != null) player.pause();
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Bind to a PlayerView for rendering.
     */
    public void setPlayerView(@NonNull PlayerView view) {
        this.playerView = view;
        if (player != null) {
            view.setPlayer(player);
        }
    }

    /** Whether the gapless engine is active and driving playback right now. */
    private boolean gapless() {
        return gaplessEngine != null && gaplessEngine.isPrepared();
    }

    /**
     * Public mirror of {@link #gapless()} for callers outside this class (L1: the activity's
     * playback tick needs to know whether the gapless engine already handles loop-extension wraps
     * for the current clip via its playlist, so it can skip the legacy poll-based wrap/seek logic
     * that would otherwise fight the engine — see {@code FaditorEditorActivity}'s tick, ~6805).
     */
    public boolean isGapless() {
        return gapless();
    }

    /**
     * M-COMP-0: after the gapless engine auto-advances a seam warm, the activity's
     * {@code onGaplessSeam} calls this to point the manager's tracked clip at the new window —
     * WITHOUT any player op (the engine already crossed the cut). Keeps the {@code currentClip}-
     * derived getters ({@link #getSourceDuration()}, {@link #seekToAbsolute(long)}) and the
     * onStop / releaseForExport resume state consistent with the window actually playing. No-op
     * outside gapless mode.
     */
    public void syncGaplessCurrentClip(@NonNull Clip clip) {
        if (!gapless()) return;
        this.currentClip = clip;
        this.trimStartMs = clip.getInPointMs();
        this.trimEndMs = clip.getOutPointMs();
    }

    /**
     * M-COMP-0: hand the manager the master track + a seekable-URI resolver + a seam callback.
     * If {@link #GAPLESS_ENGINE} is on and the timeline is eligible (plain cuts only), builds the
     * gapless playlist immediately so the first {@link #loadClip} serves from it. No-op (leaves
     * the legacy path in place) when the flag is off or the project isn't eligible.
     */
    public void setGaplessTimeline(@NonNull Timeline timeline,
                                   @NonNull MasterPlaybackEngine.SourceResolver resolver,
                                   @NonNull MasterPlaybackEngine.SeamListener seamListener) {
        this.gaplessTimeline = timeline;
        this.gaplessResolver = resolver;
        this.gaplessSeamListener = seamListener;
        if (!GAPLESS_ENGINE || !MasterPlaybackEngine.isEligible(timeline, resolver)) {
            FLog.d(TAG, "Gapless engine NOT active (flag=" + GAPLESS_ENGINE
                    + ", eligible=" + MasterPlaybackEngine.isEligible(timeline, resolver) + ")");
            return;
        }
        if (player == null) initializePlayer();
        gaplessEngine = new MasterPlaybackEngine(context, resolver, seamListener);
        configureEngine(gaplessEngine);
        boolean ok = playerView != null
                && gaplessEngine.prepareTimeline(timeline, playerView);
        if (!ok) {
            gaplessEngine.releasePlayer();
            gaplessEngine = null;
            FLog.w(TAG, "Gapless prepare failed; using legacy path");
        } else {
            // The gapless engine owns the PlayerView surface now. Pause/park the legacy
            // single-clip player so it holds no decoder while gapless is active.
            attachRegisteredListenersToEngine();
            if (player != null) player.pause();
            FLog.i(TAG, "Gapless engine ACTIVE for " + timeline.getClipCount() + " clips");
        }
    }

    /**
     * Rebuild the gapless playlist after a timeline edit (trim/add/delete/reorder). Called from
     * the same refresh path the legacy engine uses to re-prepare after edits. Re-evaluates
     * eligibility: if the project became ineligible (e.g. a transition or loop was added), tears
     * the engine down so the legacy path takes over on the next {@link #loadClip}.
     */
    public void rebuildGaplessTimeline() {
        if (!GAPLESS_ENGINE || gaplessResolver == null || gaplessSeamListener == null
                || gaplessTimeline == null) {
            return;
        }
        if (!MasterPlaybackEngine.isEligible(gaplessTimeline, gaplessResolver)) {
            if (gaplessEngine != null) {
                gaplessEngine.releasePlayer();
                gaplessEngine = null;
                FLog.i(TAG, "Project no longer gapless-eligible; reverting to legacy path");
            }
            return;
        }
        if (playerView == null) return;
        // Resume by CLIP ID + visual position, not a raw window index: once a looped clip (L1)
        // spans multiple playlist windows, a window index is no longer interchangeable with a
        // timeline clip index (a stale "window index used as clip index" here would resume on the
        // WRONG clip whenever the user was scrubbed into a loop-rep window past index
        // clipCount-1, or into any rep of a clip that isn't the first one).
        String resumeClipId = gapless() ? gaplessEngine.getCurrentClipId() : null;
        long resumePos = gapless() ? gaplessEngine.getCurrentPositionInWindow() : 0L;
        boolean wasPlaying = gapless() && gaplessEngine.getPlayWhenReady();
        if (gaplessEngine == null) {
            gaplessEngine = new MasterPlaybackEngine(context, gaplessResolver, gaplessSeamListener);
        }
        configureEngine(gaplessEngine);
        boolean ok = gaplessEngine.prepareTimeline(gaplessTimeline, playerView);
        if (!ok) {
            gaplessEngine.releasePlayer();
            gaplessEngine = null;
            return;
        }
        attachRegisteredListenersToEngine();
        gaplessEngine.seekInCurrentWindow(0L);
        if (resumeClipId != null) {
            // Best-effort: restore the clip + visual position the user was on (positions may
            // shift after edits, e.g. a trim on an earlier clip, or the edited clip itself).
            gaplessEngine.seekInClip(resumeClipId, resumePos);
        }
        if (wasPlaying) gaplessEngine.play();
    }

    /**
     * Load a clip for playback. No ClippingConfiguration — trim bounds
     * are managed manually via seek + position check so fragmented MP4
     * and non-seekable content:// sources work reliably.
     */
    public void loadClip(@NonNull Clip clip) {
        this.currentClip = clip;
        this.trimStartMs = clip.getInPointMs();
        this.trimEndMs = clip.getOutPointMs();

        // ── M-COMP-0: in gapless mode, "loading" a clip that lives in the playlist is just a
        // seek to that window — NO cold prepare. This is what eliminates the boundary freeze
        // when the activity's seam logic (or a segment tap) calls loadClip at a cut.
        if (gapless()) {
            int window = gaplessEngine.windowForClipId(clip.getId());
            if (window >= 0) {
                gaplessEngine.seekInClip(clip.getId(), 0L);
                return;
            }
            // Clip not in the playlist (shouldn't happen for master clips) — fall through.
        }

        this.needsPrepare = true;
        if (player == null) {
            initializePlayer();
        }
        preparePlayer(clip);
    }

    /**
     * Update trim bounds after handles are released.
     *
     * <p>Does NOT re-prepare the player. Simply updates internal bounds
     * and seeks to the start of the new trimmed region.</p>
     */
    public void updateTrimBounds(@NonNull Clip clip) {
        this.currentClip = clip;
        this.trimStartMs = clip.getInPointMs();
        this.trimEndMs = clip.getOutPointMs();

        // Gapless: trim bounds are baked into each item's ClippingConfiguration, so a trim edit
        // means rebuilding the playlist. Rebuild, then seek to the start of the edited clip.
        if (gapless()) {
            rebuildGaplessTimeline();
            if (gapless()) {
                gaplessEngine.seekInClip(clip.getId(), 0L);
                gaplessEngine.pause();
            }
            return;
        }

        if (player == null) return;

        // Seek to beginning of new trimmed region
        int state = player.getPlaybackState();
        if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
            player.seekTo(trimStartMs);
            player.setPlayWhenReady(false);
            FLog.d(TAG, "Trim bounds updated (seek): in=" + trimStartMs
                    + " out=" + trimEndMs
                    + " duration=" + (trimEndMs - trimStartMs) + "ms");
        } else {
            // Player not ready yet, queue the seek
            pendingSeekMs = trimStartMs;
            FLog.d(TAG, "Trim bounds updated (queued): in=" + trimStartMs
                    + " out=" + trimEndMs);
        }
    }

    /**
     * Update ONLY the trim-end bound without seeking or pausing the player.
     *
     * <p>Used by duration correction so the corrected end-point is respected
     * going forward, without disrupting the current playback position.
     * Contrast with {@link #updateTrimBounds} which also seeks to trimStart.</p>
     *
     * @param newTrimEndMs the corrected out-point in absolute source milliseconds
     */
    public void updateTrimEndOnly(long newTrimEndMs) {
        this.trimEndMs = newTrimEndMs;
        if (currentClip != null) {
            this.currentClip.setOutPointMs(newTrimEndMs);
        }
        FLog.d(TAG, "Trim end updated (no seek): trimEnd=" + newTrimEndMs + "ms");
    }

    /**
     * Update BOTH trim bounds without seeking or pausing the player.
     *
     * <p>Used by duration correction when the clip's in/out points are reset
     * (e.g. fMP4 where stored duration was wrong). Previously only
     * {@link #updateTrimEndOnly} was called, leaving {@code trimStartMs}
     * stale — which caused every subsequent seek to clamp to the old
     * trim-start value instead of the tapped position.</p>
     *
     * @param newTrimStartMs the corrected in-point in absolute source milliseconds
     * @param newTrimEndMs   the corrected out-point in absolute source milliseconds
     */
    public void updateTrimBoundsSilently(long newTrimStartMs, long newTrimEndMs) {
        this.trimStartMs = newTrimStartMs;
        this.trimEndMs = newTrimEndMs;
        FLog.d(TAG, "Trim bounds updated silently: in=" + newTrimStartMs
                + " out=" + newTrimEndMs + "ms");
    }

    private long effectiveTrimEnd() {
        if (player == null) return trimEndMs;
        long duration = player.getDuration();
        if (duration == Long.MIN_VALUE) return trimEndMs;
        return Math.min(trimEndMs, Math.max(0L, duration));
    }

    public void play() {
        if (gapless()) {
            // In gapless mode the engine holds the whole playlist; if it already ended, restart
            // from the beginning (mirrors the legacy seek-to-start-on-ENDED behavior below).
            if (gaplessEngine.isEnded()) {
                gaplessEngine.seekInClip(gaplessTimeline.getClip(0).getId(), 0L);
            }
            gaplessEngine.play();
            return;
        }
        if (player != null) {
            if (pendingSeekMs >= 0 && player.getPlaybackState() == Player.STATE_READY) {
                player.seekTo(pendingSeekMs);
                pendingSeekMs = -1;
            }
            long now = SystemClock.elapsedRealtime();
            boolean seekInFlight = lastSeekRequestMs >= 0L
                    && now - lastSeekRequestMs < SEEK_GRACE_MS;
            if (pendingSeekMs < 0 && !seekInFlight) {
                long pos = player.getCurrentPosition();
                long effectiveStart = Math.min(trimStartMs, trimEndMs);
                long effectiveEnd = effectiveTrimEnd();
                if (player.getPlaybackState() == Player.STATE_ENDED || pos < effectiveStart || pos > effectiveEnd) {
                    player.seekTo(effectiveStart);
                    lastSeekRequestMs = now;
                }
            }
            player.play();
        }
    }

    public void pause() {
        if (gapless()) {
            gaplessEngine.pause();
            return;
        }
        if (player != null) {
            player.pause();
        }
    }

    /**
     * Release the preview player to free its hardware video decoder + buffers for a
     * memory/codec-heavy operation (export). The editor stays in the foreground, so
     * call {@link #reacquireAfterExport()} when the export ends to restore preview.
     * Devices have a small number of hardware codec instances; holding the preview
     * decoder while the exporter needs a decoder + encoder can starve the export.
     */
    public void releaseForExport() {
        // Gapless: free the playlist player's decoder for the exporter, remembering where we were.
        if (gaplessEngine != null && gaplessEngine.isPrepared()) {
            exportResumeClipId = currentClip != null ? currentClip.getId() : null;
            exportResumePosMs = gaplessEngine.getCurrentPositionInWindow();
            gaplessEngine.releasePlayer();
        }
        if (player != null) {
            lastPosition = player.getCurrentPosition();
            playWhenReady = false;
            releasePlayer();
        }
    }

    /** Re-create the preview player after {@link #releaseForExport()} (export ended). */
    public void reacquireAfterExport() {
        // Gapless: rebuild the playlist and restore position.
        if (GAPLESS_ENGINE && gaplessTimeline != null && gaplessResolver != null
                && gaplessSeamListener != null
                && MasterPlaybackEngine.isEligible(gaplessTimeline, gaplessResolver) && playerView != null) {
            if (player == null) initializePlayer();
            gaplessEngine = new MasterPlaybackEngine(context, gaplessResolver, gaplessSeamListener);
            configureEngine(gaplessEngine);
            if (gaplessEngine.prepareTimeline(gaplessTimeline, playerView)) {
                attachRegisteredListenersToEngine();
                if (exportResumeClipId != null) {
                    gaplessEngine.seekInClip(exportResumeClipId, exportResumePosMs);
                }
                if (player != null) player.pause();
                return;
            }
            gaplessEngine.releasePlayer();
            gaplessEngine = null;
        }
        if (player == null && currentClip != null) {
            loadClip(currentClip);
        }
    }

    /**
     * Set the player volume.
     *
     * <p>For values 0.0 – 1.0, uses ExoPlayer's native volume control.
     * For values above 1.0, sets native volume to 1.0 and uses
     * {@link LoudnessEnhancer} to amplify beyond 100% (like VLC).</p>
     *
     * @param volume 0.0 = muted, 1.0 = normal, 2.0 = 200%
     */
    public void setVolume(float volume) {
        // In gapless mode the engine owns the active player; route volume there. The
        // LoudnessEnhancer (>100% boost) still needs an audio session; for values ≤100% the
        // engine's native volume is enough. Boost >100% falls through to the legacy enhancer
        // path below only if a legacy player exists.
        if (gapless()) {
            gaplessEngine.setVolume(Math.min(1f, Math.max(0f, volume)));
            if (volume <= 1.0f) return;
            // else fall through to also drive the LoudnessEnhancer if a legacy player exists
        }
        if (player == null) return;

        float clampedVolume = Math.max(0f, volume);

        if (clampedVolume <= 1.0f) {
            // Normal range: use ExoPlayer's native volume
            player.setVolume(clampedVolume);
            setLoudnessGain(0);
        } else {
            // Above 100%: max out native volume, use LoudnessEnhancer for extra gain
            player.setVolume(1.0f);
            // Convert volume factor to gain in millibels: dB = 20*log10(v), mB = dB*100
            int gainMb = (int) (2000.0 * Math.log10(clampedVolume));
            setLoudnessGain(gainMb);
        }
        FLog.d(TAG, "Volume set to " + volume
                + " (native=" + Math.min(clampedVolume, 1.0f)
                + ", loudnessGainMb=" + (clampedVolume > 1.0f
                    ? (int) (2000.0 * Math.log10(clampedVolume)) : 0) + ")");
    }

    /**
     * Apply loudness gain via {@link LoudnessEnhancer}.
     * Creates the enhancer lazily on first use.
     *
     * @param gainMb gain in millibels (0 = no boost)
     */
    private void setLoudnessGain(int gainMb) {
        if (player == null) return;

        try {
            if (loudnessEnhancer == null) {
                int audioSessionId = player.getAudioSessionId();
                if (audioSessionId == 0) {
                    FLog.w(TAG, "Audio session ID is 0, cannot create LoudnessEnhancer");
                    return;
                }
                loudnessEnhancer = new LoudnessEnhancer(audioSessionId);
            }
            loudnessEnhancer.setTargetGain(gainMb);
            loudnessEnhancer.setEnabled(gainMb > 0);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to set loudness gain", e);
        }
    }

    /**
     * Set the playback speed for preview (does not affect export).
     *
     * @param speed multiplier (e.g. 0.5 = half speed, 2.0 = double speed)
     * @param pitchCompensation true to keep original pitch, false to shift with speed
     */
    public void setPlaybackSpeed(float speed, boolean pitchCompensation) {
        float pitch = pitchCompensation ? 1.0f : speed;
        if (gapless()) {
            gaplessEngine.setPlaybackSpeed(speed, pitchCompensation);
            return;
        }
        if (player != null) {
            player.setPlaybackParameters(
                    new androidx.media3.common.PlaybackParameters(speed, pitch));
            FLog.d(TAG, "Playback speed set to " + speed + "x (pitchCompensation=" + pitchCompensation + ")");
        }
    }

    /**
     * Seek to a position (0-based within the trimmed region).
     * Internally converted to absolute position.
     */
    /**
     * Toggle exact (frame-precise) vs keyframe seeking. Use EXACT for tap-to-word
     * and transcript skips; CLOSEST_SYNC for fast timeline drag-scrubbing.
     */
    public void setExactSeek(boolean exact) {
        if (gapless()) {
            gaplessEngine.setExactSeek(exact);
            return;
        }
        if (player == null) return;
        player.setSeekParameters(exact ? SeekParameters.EXACT : SeekParameters.CLOSEST_SYNC);
    }

    public void seekTo(long positionMs) {
        // Gapless: position is 0-based within the current window's clip, which is exactly what the
        // ClippingConfiguration player uses natively — seek directly, no trim-offset arithmetic.
        if (gapless()) {
            gaplessEngine.seekInCurrentWindow(Math.max(0L, positionMs));
            return;
        }
        if (player == null) return;
        lastSeekRequestMs = SystemClock.elapsedRealtime();
        // Defensive: if trimStart > trimEnd (stale state), treat trimEnd as the
        // only valid position so seeks don't clamp to a nonsensical value.
        long effectiveStart = Math.min(trimStartMs, trimEndMs);
        long effectiveEnd = effectiveTrimEnd();
        long absoluteMs = effectiveStart + positionMs;
        absoluteMs = Math.max(effectiveStart, Math.min(absoluteMs, effectiveEnd));

        int state = player.getPlaybackState();
        // Seek is valid in READY, BUFFERING, and ENDED.
        // In ENDED, calling seekTo() revives the player back to BUFFERING → READY
        // so the new frame renders.  Previously we only queued a pendingSeekMs here,
        // which never fired (STATE_READY never fires again from ENDED without a new
        // seekTo), leaving the player stuck at the last frame and causing
        // "plays from 0" when play() was pressed after the user scrubbed.
        if (state == Player.STATE_READY
                || state == Player.STATE_BUFFERING
                || state == Player.STATE_ENDED) {
            player.seekTo(absoluteMs);
            pendingSeekMs = -1;
            FLog.d(TAG, "Seek to " + positionMs + "ms (rel) / " + absoluteMs + "ms (abs)"
                    + " state=" + state);
        } else {
            pendingSeekMs = absoluteMs;
            FLog.d(TAG, "Seek to " + positionMs + "ms (queued, state=" + state + ")");
        }
    }

    /**
     * Seek to an absolute position in the source video.
     * Used for live scrub/trim preview where the position is already
     * in absolute terms (not relative to trim start).
     *
     * @param absoluteMs absolute position in the source video (milliseconds)
     */
    public void seekToAbsolute(long absoluteMs) {
        // Gapless: absoluteMs is in source-time; the current window's clip plays clip-local, so
        // convert to clip-local by subtracting the current clip's in-point.
        if (gapless()) {
            long inPoint = currentClip != null ? currentClip.getInPointMs() : 0L;
            gaplessEngine.seekInCurrentWindow(Math.max(0L, absoluteMs - inPoint));
            return;
        }
        if (player == null) return;
        absoluteMs = Math.max(0, absoluteMs);

        int state = player.getPlaybackState();
        if (state == Player.STATE_READY
                || state == Player.STATE_BUFFERING
                || state == Player.STATE_ENDED) {
            player.seekTo(absoluteMs);
            pendingSeekMs = -1;
        } else {
            pendingSeekMs = absoluteMs;
            FLog.d(TAG, "Seek absolute queued: " + absoluteMs + "ms (state=" + state + ")");
        }
    }

    /**
     * Get current playback position relative to trim start (0-based).
     */
    public long getCurrentPosition() {
        if (gapless()) {
            // Engine reports clip-local position for the current window already.
            return gaplessEngine.getCurrentPositionInWindow();
        }
        if (player == null) return 0;
        long rawPos = player.getCurrentPosition();
        long effectiveStart = Math.min(trimStartMs, trimEndMs);
        return Math.max(0, rawPos - effectiveStart);
    }

    /**
     * Get the raw ExoPlayer source duration (total, un-trimmed).
     * Returns the actual content duration as reported by ExoPlayer.
     */
    public long getSourceDuration() {
        // Gapless: report the current window's SOURCE (un-clipped) duration so the activity's
        // duration-correction logic keeps working. The clip carries the source duration; use it.
        if (gapless()) {
            return currentClip != null ? currentClip.getSourceDurationMs() : 0L;
        }
        return player != null ? player.getDuration() : 0;
    }

    /**
     * Get the trimmed duration (outPoint - inPoint).
     */
    public long getDuration() {
        if (gapless()) {
            return gaplessEngine.getCurrentWindowDuration();
        }
        long effectiveStart = Math.min(trimStartMs, trimEndMs);
        long effectiveEnd = effectiveTrimEnd();
        return Math.max(0, effectiveEnd - effectiveStart);
    }

    public boolean isPlaying() {
        if (gapless()) return gaplessEngine.isPlaying();
        return player != null && player.isPlaying();
    }

    /**
     * Returns whether the player will play when ready (user intent to play).
     * More reliable than isPlaying() which returns false during buffering.
     */
    public boolean getPlayWhenReady() {
        if (gapless()) return gaplessEngine.getPlayWhenReady();
        return player != null && player.getPlayWhenReady();
    }

    /**
     * Check if the player is in a ready state for playback.
     */
    public boolean isReady() {
        if (gapless()) return gaplessEngine.isReady();
        return player != null && player.getPlaybackState() == Player.STATE_READY;
    }

    /**
     * Whether playback has reached the end (STATE_ENDED). Note {@link #getPlayWhenReady()}
     * stays true at STATE_ENDED, so callers driving audio off play-intent must also check this
     * to avoid running audio past the video end.
     */
    public boolean isEnded() {
        if (gapless()) return gaplessEngine.isEnded();
        return player != null && player.getPlaybackState() == Player.STATE_ENDED;
    }

    /**
     * Check if playback has reached the trim end point.
     * Call this periodically and pause if true.
     *
     * @return true if the player is at or beyond the trim end
     */
    public boolean isAtTrimEnd() {
        // Gapless: report end ONLY at the end of the whole playlist — never at an internal seam
        // (the engine crosses those warm itself). This makes the activity's isAtEnd→advance logic
        // fire only to stop/pause at the true timeline end; internal cuts are invisible to it.
        if (gapless()) return gaplessEngine.isAtEndOfTimeline();
        if (player == null) return false;
        long pos = player.getCurrentPosition();
        long effectiveEnd = effectiveTrimEnd();
        return player.getPlaybackState() == Player.STATE_ENDED || pos >= effectiveEnd - 150L;
    }

    /**
     * Get the raw ExoPlayer instance (for advanced listeners).
     */
    @Nullable
    public ExoPlayer getPlayer() {
        if (gapless()) return gaplessEngine.getPlayer();
        return player;
    }

    /**
     * Add a Player.Listener for playback events. In gapless mode the listener is attached to the
     * engine's playlist player (the one actually rendering), so the activity's video-size /
     * duration-correction / play-state callbacks fire from the right player. Also attach to the
     * legacy player so callbacks survive a fallback to the legacy path.
     */
    public void addListener(@NonNull Player.Listener listener) {
        if (!registeredListeners.contains(listener)) {
            registeredListeners.add(listener);
        }
        if (gapless()) {
            ExoPlayer ep = gaplessEngine.getPlayer();
            if (ep != null) ep.addListener(listener);
        }
        if (player != null) {
            player.addListener(listener);
        }
    }

    /**
     * Re-attach every listener registered via {@link #addListener} to the gapless engine's current
     * player. Called after the engine builds a fresh {@link ExoPlayer} (initial prepare / trim
     * rebuild / re-acquire) so the activity's callbacks keep firing across engine teardowns.
     */
    private void attachRegisteredListenersToEngine() {
        if (gaplessEngine == null) return;
        ExoPlayer ep = gaplessEngine.getPlayer();
        if (ep == null) return;
        for (Player.Listener l : registeredListeners) {
            ep.addListener(l);
        }
    }

    /**
     * Rank-1: install the reverse-leg failure recovery hook that every engine build will honour.
     * The activity supplies the poison-and-rebuild behaviour. Safe to call before or after the
     * engine exists; re-applied on every (re)build via {@link #configureEngine}.
     */
    public void setErrorRecoveryListener(
            @Nullable MasterPlaybackEngine.ErrorRecoveryListener listener) {
        this.gaplessErrorRecoveryListener = listener;
        if (gaplessEngine != null) {
            gaplessEngine.setErrorRecoveryListener(listener);
        }
    }

    /**
     * Apply the cross-build engine configuration (error-recovery hook + debug EventLogger) to a
     * freshly constructed engine. Called from EVERY place that builds a {@link MasterPlaybackEngine}
     * BEFORE {@link MasterPlaybackEngine#prepareTimeline} so the config takes effect on the player
     * that build creates.
     */
    private void configureEngine(@NonNull MasterPlaybackEngine engine) {
        engine.setErrorRecoveryListener(gaplessErrorRecoveryListener);
        engine.setEventLoggingEnabled(com.fadcam.BuildConfig.DEBUG);
    }


    // ── Internal ─────────────────────────────────────────────────────

    private void initializePlayer() {
        if (player != null) return;

        try {
            player = new ExoPlayer.Builder(context).build();
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
            // Keyframe seeking by default for fast/responsive scrubbing; precise
            // operations (tap-to-word, transcript skips) flip to EXACT via
            // setExactSeek(true) so they land on the exact time.
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
            player.addListener(internalListener);

            if (playerView != null) {
                playerView.setPlayer(player);
            }

            // Reload current clip if we had one
            if (currentClip != null && needsPrepare) {
                preparePlayer(currentClip);
                if (lastPosition > 0) {
                    pendingSeekMs = trimStartMs + lastPosition;
                }
                player.setPlayWhenReady(playWhenReady);

                // Re-apply volume from clip state.  A newly created ExoPlayer
                // defaults to volume 1.0 — if the clip was muted (e.g. after audio
                // extraction) we must enforce that here, otherwise the user hears
                // double audio after a lifecycle resume.
                float vol = currentClip.isAudioMuted() ? 0f : currentClip.getVolumeLevel();
                setVolume(vol);
                FLog.d(TAG, "Restored volume from clip state: vol=" + vol
                        + ", muted=" + currentClip.isAudioMuted());
            }

            FLog.d(TAG, "ExoPlayer initialized");
        } catch (Exception e) {
            FLog.e(TAG, "Failed to initialize ExoPlayer", e);
        }
    }

    /**
     * Prepare the player with a plain MediaItem (no ClippingConfiguration).
     * Seeks to trimStartMs once ready.
     *
     * <p>Converts content:// URIs to file:// when possible so ExoPlayer
     * uses {@code FileDataSource} (supports random-access seeking) instead
     * of {@code ContentDataSource} (which cannot seek in fMP4).</p>
     */
    private void preparePlayer(@NonNull Clip clip) {
        if (player == null) return;

        Uri sourceUri = clip.getSourceUri();
        Uri resolvedUri = resolveFileUri(sourceUri);

        FLog.d(TAG, "Preparing clip: originalUri=" + sourceUri
                + " resolvedUri=" + resolvedUri
                + " trimIn=" + trimStartMs
                + " trimOut=" + trimEndMs
                + " sourceDur=" + clip.getSourceDurationMs());

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(resolvedUri)
                .build();

        // F6 (PERF_SPEC_LONGFILE_20260718): a RAW fragmented MP4 (FadCam recordings) has no
        // seek index — plain setMediaItem leaves it unseekable, so trim-start seeks fail and
        // the clip reads as instantly ENDED (playback jumps clip→clip and sticks). Build a
        // moof-indexed seekable source instead (index scan is ~ms-seconds, not the 40s remux
        // F1 removed). Falls back to the plain item for non-fMP4 or on any index failure.
        boolean usedFmp4 = false;
        try {
            if (fmp4SourceFactory == null) {
                fmp4SourceFactory =
                        new com.fadcam.playback.SeekableFragmentedMp4MediaSourceFactory(context);
            }
            if (fmp4SourceFactory.isFragmentedMp4(resolvedUri)) {
                androidx.media3.exoplayer.source.MediaSource src =
                        fmp4SourceFactory.createMediaSource(mediaItem);
                player.setMediaSource(src);
                usedFmp4 = true;
                FLog.d(TAG, "Prepared clip via seekable fMP4 index source");
            }
        } catch (Exception e) {
            FLog.w(TAG, "fMP4 index source failed; falling back to plain MediaItem", e);
        }
        if (!usedFmp4) {
            player.setMediaItem(mediaItem);
        }
        player.prepare();
        needsPrepare = false;

        // Queue seek to trim start after prepare completes. Always queue it,
        // even when trimStartMs is 0, so a newly loaded clip restarts cleanly
        // after auto-advancing from an ENDED previous clip.
        pendingSeekMs = trimStartMs;
    }

    /**
     * Resolve a content:// URI to a file:// URI when possible.
     *
     * <p>SAF content:// URIs use {@code ContentDataSource} in ExoPlayer,
     * which does NOT support random-access seeking. For fragmented MP4
     * recorded by FadCam, this means seeks silently fail. Converting to
     * file:// enables {@code FileDataSource} with proper seeking.</p>
     *
     * <p>Handles both internal storage ("primary" → /storage/emulated/0)
     * and SD card ("ABCD-1234" → /storage/ABCD-1234) mount points.</p>
     *
     * @param uri the original URI (may be content://, file://, or other)
     * @return file:// URI if the file exists on disk, otherwise the original URI
     */
    @NonNull
    private Uri resolveFileUri(@NonNull Uri uri) {
        // Already a file URI — nothing to do
        if ("file".equals(uri.getScheme())) {
            return uri;
        }

        // Parse SAF content:// URI — handles both primary and SD card storage IDs
        // URI path format: /document/STORAGE_ID:relative/path/to/file.mp4
        // STORAGE_ID is "primary" for internal storage or e.g. "ABCD-1234" for SD card
        String uriPath = uri.getPath();
        if (uriPath != null && uriPath.contains(":")) {
            try {
                int lastColon = uriPath.lastIndexOf(':');
                String encodedRel = uriPath.substring(lastColon + 1);
                String relativePath = java.net.URLDecoder.decode(encodedRel, "UTF-8");

                // Storage ID is the path segment immediately before the colon
                String beforeColon = uriPath.substring(0, lastColon);
                int lastSlash = beforeColon.lastIndexOf('/');
                String encodedId = lastSlash >= 0
                        ? beforeColon.substring(lastSlash + 1) : beforeColon;
                String storageId = java.net.URLDecoder.decode(encodedId, "UTF-8");

                // Map storage ID → mount point
                String mountPoint = "primary".equalsIgnoreCase(storageId)
                        ? "/storage/emulated/0"
                        : "/storage/" + storageId;

                java.io.File f = new java.io.File(mountPoint + "/" + relativePath);
                if (f.exists() && f.canRead()) {
                    Uri fileUri = Uri.fromFile(f);
                    FLog.d(TAG, "Resolved content:// → file:// (storageId='" + storageId + "'): " + fileUri);
                    return fileUri;
                } else {
                    FLog.w(TAG, "Resolved path does not exist: " + f.getAbsolutePath());
                }
            } catch (Exception e) {
                FLog.w(TAG, "Failed to parse SAF URI path: " + uri, e);
            }
        }

        // Fallback: use original URI (ContentDataSource, limited seeking)
        FLog.w(TAG, "Cannot resolve to file URI, using content:// (seeking may fail): " + uri);
        return uri;
    }

    private void releasePlayer() {
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.release();
            } catch (Exception e) {
                FLog.w(TAG, "Failed to release LoudnessEnhancer", e);
            }
            loudnessEnhancer = null;
        }
        if (player != null) {
            playWhenReady = player.getPlayWhenReady();
            lastPosition = Math.max(0, player.getCurrentPosition() - trimStartMs);
            player.removeListener(internalListener);
            player.release();
            player = null;
            pendingSeekMs = -1;
            needsPrepare = true;
            FLog.d(TAG, "ExoPlayer released");
        }
    }
}
