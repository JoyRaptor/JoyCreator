package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.audio.fx.AudioFxChainFactory;
import com.fadcam.ui.faditor.model.AudioClip;

import java.util.List;

/**
 * A9 — ONE audio-clip preview player, ExoPlayer-based, whose audio flows through the
 * SAME processor chain export builds ({@link AudioFxChainFactory#buildLaneChain}).
 *
 * <p>Replaces the per-clip legacy {@code MediaPlayer}: two engines meant two behaviours —
 * preview ignored PAN entirely (MediaPlayer volume is stereo-paired), approximated the
 * volume envelope by polling at tick rate while export applied it sample-exactly, and
 * heard nothing of the real-time FX chain. Every difference of that shape is gone by
 * construction here: preview and export call the ONE factory, so they cannot disagree.</p>
 *
 * <p>Surface mirrors the MediaPlayer subset the editor's sync loop already uses
 * (prepareAsync / start / pause / isPlaying / seekTo(sourceMs) / getDuration / release),
 * so the swap is mechanical. TWO deliberate contract changes:</p>
 * <ul>
 *   <li>{@link #setVolume} does NOT set a raw player gain — gain belongs to the model
 *       (volume level + envelope + pan) and the processors read it; this method pushes a
 *       fresh {@link #refresh()} instead. Call sites that wrote computed gains directly
 *       now write the model first (they already do) and land here.</li>
 *   <li>Position semantics are SOURCE ms (ClippingConfiguration plays in-point-relative),
 *       identical to what every existing seek site computes.</li>
 * </ul>
 */
public final class AudioClipPreviewPlayer {

    private static final String TAG = "AudioClipPreview";
    public static final long PARK_TOLERANCE_MS = 15L;

    @NonNull private final Context context;
    @NonNull private final AudioClip clip;
    private final int projectSampleRate;
    private final boolean fxBypassedSnapshot;
    /**
     * Whether the voice chain applies — the CLIP's own opt-in ({@code isVoiceFxEnabled}).
     *
     * <p>Carried here for PARITY. The factory's four-argument overload defaults this to false,
     * which is the right default for a factory and the wrong one for preview: export passes the
     * clip's actual per-clip flag, so preview reading a hardcoded false would put the two
     * engines back out of step, which is the exact split A9 existed to close.</p>
     */
    private final boolean applyVoiceChain;

    @Nullable private ExoPlayer player;
    @Nullable private List<AudioProcessor> chain;
    private boolean prepared = false;
    @Nullable private Long parkTargetMs = null;

    public AudioClipPreviewPlayer(@NonNull Context context,
                                  @NonNull AudioClip clip,
                                  int projectSampleRate,
                                  boolean fxBypassedSnapshot,
                                  boolean applyVoiceChain) {
        this.context = context.getApplicationContext();
        this.clip = clip;
        this.projectSampleRate = projectSampleRate;
        this.fxBypassedSnapshot = fxBypassedSnapshot;
        this.applyVoiceChain = applyVoiceChain;
    }

    /** Async prepare — mirrors MediaPlayer.prepareAsync(). Safe to call twice. */
    public void prepareAsync() {
        if (player != null) return;
        try {
            // The chain must exist BEFORE the sink is built (processors configure when the
            // pipeline starts). Built from the ONE factory export uses.
            chain = AudioFxChainFactory.buildLaneChain(
                    clip, sampleRateOfSource(), projectSampleRate, fxBypassedSnapshot,
                    applyVoiceChain);

            androidx.media3.exoplayer.DefaultRenderersFactory rf =
                    new androidx.media3.exoplayer.DefaultRenderersFactory(context) {
                        @Override
                        @Nullable
                        protected AudioSink buildAudioSink(android.content.Context ctx,
                                boolean enableFloatOutput,
                                boolean enableAudioTrackPlaybackParams) {
                            // Force Sonic (time-stretch without pitch) for micro-speed drift correction.
                    // Hardware AudioTrack playbackParams DOES repitch; Sonic path does not.
                    return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(ctx)
                                    .setEnableFloatOutput(enableFloatOutput)
                                    .setEnableAudioTrackPlaybackParams(false)
                                    .setAudioProcessors(chain.toArray(new AudioProcessor[0]))
                                    .build();
                        }
                    };

            MediaItem item = new MediaItem.Builder()
                    .setUri(clip.getSourceUri())
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.getInPointMs())
                            .setEndPositionMs(clip.getOutPointMs())
                            .build())
                    .build();

            ExoPlayer p = new ExoPlayer.Builder(context, rf).build();
            p.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        prepared = true;
                    }
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    FLog.e(TAG, "preview error for " + clip.getId()
                            + ": " + error.getMessage());
                    prepared = false;
                }
            });
            p.setMediaItem(item);
            p.prepare();
            this.player = p;
        } catch (Exception e) {
            FLog.e(TAG, "Failed to prepare preview player for " + clip.getId(), e);
            release();
        }
    }

    public boolean isReady() {
        return prepared && player != null;
    }

    public void start() {
        if (player != null) player.play();
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    /**
     * SOURCE time to the player's own WINDOW time.
     *
     * <p>THE TWO DOMAINS. This class's public API is documented in SOURCE time — every call site
     * computes {@code inPointMs + playhead - offset} and hands that in. But the media item is
     * built with a {@link MediaItem.ClippingConfiguration} from the in-point to the out-point, and
     * a clipped ExoPlayer timeline starts at ZERO at the in-point. So the player's domain is the
     * WINDOW, and the difference between the two is exactly {@code inPointMs}.
     *
     * <p>Nothing bridged them. Every seek therefore overshot by the in-point, which is silent on a
     * clip trimmed from the start of its file (in-point 0, the two domains coincide) and wrong by
     * the whole trim on any other. JoyRaptor, 2026-09-13: <i>"the first audio clip was working till
     * I cut it and trimmed it, now it's just giving one short sample over and over again, the next
     * audio right after it still works."</i> The one that still worked had an in-point of zero.
     * His device log names the fault outright:
     *
     * <pre>AudioPlayer[2] seekPos=42950 exceeds mediaDuration=14202, clamping</pre>
     *
     * <p>42950 is the correct SOURCE position — in-point 32751 plus 10199 of playhead. 14202 is
     * the WINDOW's length. The guard then clamped the seek to just before the window's end, so
     * every tick re-parked on the same final fragment and played it again: one short sample, over
     * and over.
     */
    private long toWindowMs(long sourceMs) {
        long w = sourceMs - clip.getInPointMs();
        if (w < 0L) return 0L;
        long dur = player != null ? player.getDuration() : androidx.media3.common.C.TIME_UNSET;
        if (dur != androidx.media3.common.C.TIME_UNSET && dur > 0 && w > dur) return dur;
        return w;
    }

    /** The player's WINDOW time back to SOURCE time — the inverse of {@link #toWindowMs}. */
    private long toSourceMs(long windowMs) {
        return Math.max(0L, windowMs) + clip.getInPointMs();
    }

    /**
     * Seek to an absolute position in the SOURCE file (the domain every existing call
     * site computes in: {@code inPointMs + playhead - offset}).
     */
    public void seekTo(long sourceMs) {
        if (player != null) player.seekTo(toWindowMs(sourceMs));
    }

    /** Park (seek + buffer) at sourceMs WITHOUT starting. */
    public void parkAt(long sourceMs) {
        long target = Math.max(0L, sourceMs);
        parkTargetMs = target;   // kept in SOURCE time — isParkedAt is asked in the same domain
        if (player == null) {
            prepareAsync();
            // seek will happen once player exists; target kept for isParkedAt
            return;
        }
        try {
            player.setPlayWhenReady(false);
            player.seekTo(toWindowMs(target));
            if (player.getPlaybackState() == Player.STATE_IDLE) {
                player.prepare();
            }
        } catch (Exception e) {
            FLog.w(TAG, "parkAt failed for " + clip.getId(), e);
        }
    }

    /** True once buffered and within PARK_TOLERANCE_MS of requested park point. */
    public boolean isParkedAt(long sourceMs) {
        if (player == null) return false;
        if (player.getPlaybackState() != Player.STATE_READY) return false;
        long pos = toSourceMs(player.getCurrentPosition());
        return Math.abs(pos - sourceMs) <= PARK_TOLERANCE_MS;
    }

    /**
     * Current position in SOURCE time, as the name says — the drift loop compares this against
     * {@code inPointMs + playhead - offset}, so it must be in that domain. It used to return the
     * raw window position, which on a trimmed clip is short by the in-point; the drift baseline
     * quietly absorbed the difference and called a whole trim's worth of offset "pipeline
     * latency". See {@link #toWindowMs}.
     */
    public long getCurrentPosition() {
        if (player == null) return 0L;
        return toSourceMs(player.getCurrentPosition());
    }

    public int getPlaybackState() {
        if (player == null) return Player.STATE_IDLE;
        return player.getPlaybackState();
    }

    public void setPlaybackSpeed(float speed) {
        if (player == null) return;
        try {
            androidx.media3.common.PlaybackParameters pp = player.getPlaybackParameters();
            // Keep pitch at 1f — Sonic time-stretch, not repitch.
            androidx.media3.common.PlaybackParameters next =
                    new androidx.media3.common.PlaybackParameters(speed, 1f);
            // Avoid redundant sets (avoids re-buffer).
            if (pp.speed != next.speed) player.setPlaybackParameters(next);
        } catch (Exception e) {
            FLog.w(TAG, "setPlaybackSpeed failed", e);
        }
    }

    public float getPlaybackSpeed() {
        if (player == null) return 1f;
        try { return player.getPlaybackParameters().speed; } catch (Exception e) { return 1f; }
    }

    /**
     * The last SOURCE position this player can reach, or 0 while unknown.
     *
     * <p>In SOURCE time, to match {@link #seekTo} and {@link #getCurrentPosition}. The player's
     * own duration is the WINDOW's length, and callers compare this against a source-domain seek
     * position — which is how a legitimate seek to 42950 came to be judged "past the end" of a
     * 14202-long file and clamped onto a repeating fragment.
     */
    public long getDuration() {
        if (player == null) return 0L;
        long d = player.getDuration();
        if (d == androidx.media3.common.C.TIME_UNSET) return 0L;
        return toSourceMs(Math.max(0L, d));
    }

    /**
     * NOT raw gain — see class doc. Pushes the CURRENT model state (volume, envelope,
     * pan) into the mounted processors so slider/fade edits are audible immediately.
     */
    public void setVolume(float left, float right) {
        refresh();
    }

    /** Re-read the live model into the mounted processors. Cheap. */
    public void refresh() {
        if (chain != null) {
            AudioFxChainFactory.refreshLaneChain(chain, clip);
        }
    }

    public void release() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        chain = null;
        prepared = false;
    }

    private int sampleRateOfSource() {
        try {
            android.media.MediaExtractor ex = new android.media.MediaExtractor();
            ex.setDataSource(context, clip.getSourceUri(), null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    int sr = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                    ex.release();
                    return sr;
                }
            }
            ex.release();
        } catch (Exception e) {
            FLog.w(TAG, "sample rate probe failed for " + clip.getSourceUri(), e);
        }
        return -1;
    }
}
