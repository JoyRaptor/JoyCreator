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

    @NonNull private final Context context;
    @NonNull private final AudioClip clip;
    private final int projectSampleRate;
    private final boolean fxBypassedSnapshot;

    @Nullable private ExoPlayer player;
    @Nullable private List<AudioProcessor> chain;
    private boolean prepared = false;

    public AudioClipPreviewPlayer(@NonNull Context context,
                                  @NonNull AudioClip clip,
                                  int projectSampleRate,
                                  boolean fxBypassedSnapshot) {
        this.context = context.getApplicationContext();
        this.clip = clip;
        this.projectSampleRate = projectSampleRate;
        this.fxBypassedSnapshot = fxBypassedSnapshot;
    }

    /** Async prepare — mirrors MediaPlayer.prepareAsync(). Safe to call twice. */
    public void prepareAsync() {
        if (player != null) return;
        try {
            // The chain must exist BEFORE the sink is built (processors configure when the
            // pipeline starts). Built from the ONE factory export uses.
            chain = AudioFxChainFactory.buildLaneChain(
                    clip, sampleRateOfSource(), projectSampleRate, fxBypassedSnapshot);

            androidx.media3.exoplayer.DefaultRenderersFactory rf =
                    new androidx.media3.exoplayer.DefaultRenderersFactory(context) {
                        @Override
                        @Nullable
                        protected AudioSink buildAudioSink(android.content.Context ctx,
                                boolean enableFloatOutput,
                                boolean enableAudioTrackPlaybackParams) {
                            return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(ctx)
                                    .setEnableFloatOutput(enableFloatOutput)
                                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
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
     * Seek to an absolute position in the SOURCE file (the domain every existing call
     * site computes in: {@code inPointMs + playhead - offset}).
     */
    public void seekTo(long sourceMs) {
        if (player != null) player.seekTo(Math.max(0L, sourceMs));
    }

    /** Source duration in ms, or 0 while unknown. */
    public long getDuration() {
        if (player == null) return 0L;
        long d = player.getDuration();
        return d == androidx.media3.common.C.TIME_UNSET ? 0L : Math.max(0L, d);
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
