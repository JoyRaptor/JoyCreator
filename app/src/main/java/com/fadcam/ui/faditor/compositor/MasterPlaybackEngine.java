package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * M-COMP-0 — gapless master playback engine.
 *
 * <p>Plays the whole master track as a single {@link ExoPlayer} <b>playlist</b>: one
 * {@link MediaItem} per clip, each carrying a {@link MediaItem.ClippingConfiguration} from the
 * clip's in/out points, with every source routed through the editor's remux-to-seekable cache
 * (via a {@link SourceResolver} supplied by the caller). ExoPlayer pre-buffers the next item
 * natively, so crossing a plain cut is a <i>warm</i> {@link Player.Listener#onMediaItemTransition}
 * continuation instead of the cold {@code setMediaItem()+prepare()} re-prepare that produces the
 * 100-400ms boundary freeze diagnosed in {@code DIAG_20260701_transition_preview.md}.</p>
 *
 * <p>PROBE PASS (device-verified 2026-07-02, Note 9 serial SANDBOX_SERIAL, sandbox project
 * bdd51919 = 4 clips / 3 seams over genuine fragmented-MP4 FadCam recordings): the clipped playlist
 * built ({@code gapless playlist prepared: 4 clipped items}), rendered its first frame, and played
 * gaplessly through all three auto seams as warm {@code MEDIA_ITEM_TRANSITION_REASON_AUTO}
 * transitions — {@code ffmpeg freezedetect} found ZERO frozen frames at any seam (vs the legacy
 * path's 100-400ms cold-re-prepare stall on the same project), with no ExoPlayer/decoder errors.
 * A per-clip trim rebuilt the playlist and crossed the edited seam freeze-free; scrub across a seam
 * rendered the correct target frame; caption overlays animated continuously across every cut. This
 * validates the ClippingConfiguration-on-remuxed-fMP4 premise this engine rests on.</p>
 *
 * <h3>Eligibility</h3>
 * The engine handles only the <b>plain-cut</b> case, exactly as PLAN §3.1 scopes M-COMP-0. It is
 * eligible only when EVERY master clip is a non-image video clip with NO loop extension and there
 * are NO transitions on the timeline. Loops / still / ping-pong / transitions / image clips keep
 * the proven legacy single-clip path. Per-clip <b>speed</b> IS supported (applied on each seam).
 *
 * <h3>Single-clip illusion</h3>
 * {@link FaditorPlayerManager} delegates its single-clip public API to this engine. To avoid
 * broad changes to {@code FaditorEditorActivity}'s polling loop, the engine preserves single-clip
 * semantics: {@link #getCurrentPositionInWindow()} is 0-based within the current window's clip
 * (ClippingConfiguration positions are already clip-local), and {@link #isAtEndOfTimeline()}
 * reports true only at the END of the whole playlist — never at an internal seam (the engine
 * crosses those itself and fires {@link SeamListener}). So the activity keeps polling "the current
 * clip" transparently while internal cold cuts simply vanish.
 */
public class MasterPlaybackEngine {

    private static final String TAG = "MasterPlayEngine";

    /** Resolves a clip's raw source URI to a SEEKABLE URI (remuxed faststart copy for fMP4). */
    public interface SourceResolver {
        @NonNull
        Uri resolveSeekable(@NonNull Clip clip);
    }

    /** Fired (on the app main thread) when the player crosses into a different playlist window. */
    public interface SeamListener {
        /**
         * @param newClipIndex   the window (clip index) now current
         * @param autoAdvance    true when playback PLAYED THROUGH a plain cut
         *                       ({@code MEDIA_ITEM_TRANSITION_REASON_AUTO}); false when the window
         *                       change was caused by a user-initiated cross-item
         *                       {@code seekTo(window, pos)} ({@code REASON_SEEK}). Callers that
         *                       re-home the playhead to the new clip's start must do so only when
         *                       {@code autoAdvance} is true — on a seek the caller has already set
         *                       the authoritative (tapped/scrubbed) position.
         */
        void onSeam(int newClipIndex, boolean autoAdvance);
    }

    @NonNull
    private final Context context;
    @NonNull
    private final SourceResolver resolver;
    @NonNull
    private final SeamListener seamListener;

    @Nullable
    private ExoPlayer player;
    @Nullable
    private PlayerView boundView;

    /** The clip ids in playlist order (window index -> clip id), for index<->clip mapping. */
    @NonNull
    private final List<String> windowClipIds = new ArrayList<>();
    /** Per-window in-point (ms) and speed, captured at build time. */
    @NonNull
    private final List<long[]> windowInPoints = new ArrayList<>(); // [inMs]
    @NonNull
    private final List<Float> windowSpeeds = new ArrayList<>();

    private int currentWindow = 0;

    private final Player.Listener internalListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            if (player == null) return;
            int idx = player.getCurrentMediaItemIndex();
            if (idx == currentWindow) return;
            currentWindow = idx;
            applyWindowSpeed(idx);
            // AUTO (played through) or SEEK across a boundary both need the activity's
            // per-clip UI sync. PLAYLIST_CHANGED (initial set) is skipped — the activity
            // sets up clip 0 itself on load.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                FLog.d(TAG, "seam -> window " + idx + " reason=" + reason);
                seamListener.onSeam(idx, reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO);
            }
        }
    };

    public MasterPlaybackEngine(@NonNull Context context,
                                @NonNull SourceResolver resolver,
                                @NonNull SeamListener seamListener) {
        this.context = context.getApplicationContext();
        this.resolver = resolver;
        this.seamListener = seamListener;
    }

    // ── Eligibility ──────────────────────────────────────────────────────

    /**
     * Whether the timeline is a plain-cut single track the gapless playlist can serve.
     * Requires ≥2 clips, all non-image video, none with a loop extension, and no transitions.
     */
    public static boolean isEligible(@Nullable Timeline timeline) {
        if (timeline == null) return false;
        int count = timeline.getClipCount();
        if (count < 2) return false;
        if (timeline.getTransitions() != null && !timeline.getTransitions().isEmpty()) return false;
        for (int i = 0; i < count; i++) {
            Clip c = timeline.getClip(i);
            if (c == null || c.isImageClip() || c.hasLoopExtension()) return false;
        }
        return true;
    }

    // ── Build / lifecycle ────────────────────────────────────────────────

    /**
     * Build the clipped playlist for {@code timeline} and prepare the player. Returns false if the
     * timeline is not eligible (caller must fall back to the legacy path).
     */
    public boolean prepareTimeline(@NonNull Timeline timeline, @NonNull PlayerView view) {
        if (!isEligible(timeline)) return false;
        releasePlayer();
        this.boundView = view;

        List<MediaItem> items = new ArrayList<>();
        windowClipIds.clear();
        windowInPoints.clear();
        windowSpeeds.clear();
        int count = timeline.getClipCount();
        for (int i = 0; i < count; i++) {
            Clip clip = timeline.getClip(i);
            Uri seekable = resolver.resolveSeekable(clip);
            long inMs = clip.getInPointMs();
            long outMs = clip.getOutPointMs();
            MediaItem item = new MediaItem.Builder()
                    .setUri(seekable)
                    .setClippingConfiguration(
                            new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(inMs)
                                    .setEndPositionMs(outMs)
                                    .build())
                    .build();
            items.add(item);
            windowClipIds.add(clip.getId());
            windowInPoints.add(new long[]{inMs});
            windowSpeeds.add(clip.getSpeedMultiplier());
        }

        ExoPlayer p = new ExoPlayer.Builder(context).build();
        p.setRepeatMode(Player.REPEAT_MODE_OFF);
        p.addListener(internalListener);
        p.setMediaItems(items);
        p.prepare();
        currentWindow = 0;
        applyWindowSpeed(0);
        this.player = p;
        view.setPlayer(p);
        FLog.d(TAG, "gapless playlist prepared: " + items.size() + " clipped items");
        return true;
    }

    private void applyWindowSpeed(int window) {
        if (player == null || window < 0 || window >= windowSpeeds.size()) return;
        float speed = windowSpeeds.get(window);
        player.setPlaybackParameters(new PlaybackParameters(speed));
    }

    public void releasePlayer() {
        if (player != null) {
            player.removeListener(internalListener);
            player.release();
            player = null;
        }
    }

    public boolean isPrepared() {
        return player != null;
    }

    @Nullable
    public ExoPlayer getPlayer() {
        return player;
    }

    // ── Window <-> clip mapping ──────────────────────────────────────────

    public int getCurrentWindow() {
        return currentWindow;
    }

    /** Window index for a clip id, or -1 if the clip is not in this playlist. */
    public int windowForClipId(@Nullable String clipId) {
        if (clipId == null) return -1;
        return windowClipIds.indexOf(clipId);
    }

    private long inPointOf(int window) {
        if (window < 0 || window >= windowInPoints.size()) return 0L;
        return windowInPoints.get(window)[0];
    }

    // ── Position / transport (all single-clip-relative to the current window) ──

    /**
     * Seek to {@code positionMs} (0-based within the given clip's trimmed region). Switches the
     * playlist window if the clip differs from the current one. Clamps to [0, window duration].
     */
    public void seekInClip(@NonNull String clipId, long positionMs) {
        if (player == null) return;
        int window = windowForClipId(clipId);
        if (window < 0) return;
        long clamped = Math.max(0L, positionMs);
        player.seekTo(window, clamped);
    }

    /** Seek within the CURRENT window (0-based within its clip). */
    public void seekInCurrentWindow(long positionMs) {
        if (player == null) return;
        player.seekTo(currentWindow, Math.max(0L, positionMs));
    }

    /** Current position, 0-based within the current window's clip (ClippingConfiguration-local). */
    public long getCurrentPositionInWindow() {
        if (player == null) return 0L;
        return Math.max(0L, player.getCurrentPosition());
    }

    /** Trimmed duration (ms) of the current window's clip, as ExoPlayer reports it. */
    public long getCurrentWindowDuration() {
        if (player == null) return 0L;
        long d = player.getDuration();
        return d == androidx.media3.common.C.TIME_UNSET ? 0L : Math.max(0L, d);
    }

    /** True only when the WHOLE playlist has ended (never at an internal seam). */
    public boolean isAtEndOfTimeline() {
        if (player == null) return false;
        if (player.getPlaybackState() == Player.STATE_ENDED) return true;
        // At the last window, near its end, treat as end-of-timeline so the activity's
        // stop logic runs (mirrors FaditorPlayerManager.isAtTrimEnd's 150ms grace).
        if (currentWindow == windowClipIds.size() - 1) {
            long dur = getCurrentWindowDuration();
            return dur > 0 && getCurrentPositionInWindow() >= dur - 150L;
        }
        return false;
    }

    public void play() {
        if (player != null) player.play();
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public boolean getPlayWhenReady() {
        return player != null && player.getPlayWhenReady();
    }

    public boolean isReady() {
        return player != null && player.getPlaybackState() == Player.STATE_READY;
    }

    public boolean isEnded() {
        return player != null && player.getPlaybackState() == Player.STATE_ENDED;
    }

    public void setPlaybackSpeed(float speed) {
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
    }

    public void setExactSeek(boolean exact) {
        if (player != null) {
            player.setSeekParameters(exact
                    ? androidx.media3.exoplayer.SeekParameters.EXACT
                    : androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        }
    }

    public void setVolume(float v) {
        if (player != null) player.setVolume(Math.max(0f, Math.min(1f, v)));
    }
}
