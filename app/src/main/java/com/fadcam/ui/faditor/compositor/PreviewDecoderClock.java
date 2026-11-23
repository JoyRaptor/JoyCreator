package com.fadcam.ui.faditor.compositor;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.Player.PositionInfo;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;

import com.fadcam.ui.faditor.motion.PreviewMotionClock;

/** Owns the metadata listener for the decoder currently routed to a compositor surface. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
final class PreviewDecoderClock {
    record Anchor(Object windowUid, long mediaOriginUs, long editorStartUs,
                  long editorEndUs, float speed) {
        long toEditorUs(long sourceUs) {
            return editorStartUs + Math.round((sourceUs - mediaOriginUs) / (double) speed);
        }
    }

    private final PreviewMotionClock frames;
    @Nullable private ExoPlayer player;
    @Nullable private Anchor anchor;
    @Nullable private VideoFrameMetadataListener metadata;
    private final Player.Listener discontinuity = new Player.Listener() {
        @Override
        public void onPositionDiscontinuity(PositionInfo previous, PositionInfo current, int reason) {
            // Clear the latch immediately, before a frame from the old location can be drawn.
            frames.reset();
            if (previous.mediaItemIndex == current.mediaItemIndex && anchor != null && player != null
                    && previous.periodUid != null && previous.periodUid.equals(current.periodUid)) {
                installMetadataListener();
            } else {
                anchor = null;
            }
        }
    };

    PreviewDecoderClock(PreviewMotionClock frames) {
        this.frames = frames;
    }

    void bind(@Nullable ExoPlayer nextPlayer, @Nullable Anchor nextAnchor) {
        if (nextPlayer == null || nextAnchor == null) {
            clear();
            return;
        }
        if (nextPlayer == player && nextAnchor.equals(anchor)) return;
        clear();
        player = nextPlayer;
        anchor = nextAnchor;
        nextPlayer.addListener(discontinuity);
        installMetadataListener();
    }

    @Nullable Anchor anchor() { return anchor; }

    void clear() {
        if (player == null) return;
        frames.reset();
        if (metadata != null) player.clearVideoFrameMetadataListener(metadata);
        player.removeListener(discontinuity);
        metadata = null;
        anchor = null;
        player = null;
    }

    private void installMetadataListener() {
        ExoPlayer currentPlayer = player;
        Anchor currentAnchor = anchor;
        if (currentPlayer == null || currentAnchor == null) return;
        if (metadata != null) currentPlayer.clearVideoFrameMetadataListener(metadata);
        long generation = frames.reset();
        metadata = (presentationUs, releaseNs, format, mediaFormat) -> {
            long editorUs = currentAnchor.toEditorUs(presentationUs);
            if (editorUs >= currentAnchor.editorStartUs && editorUs < currentAnchor.editorEndUs) {
                frames.onFrame(generation, releaseNs, editorUs);
            }
        };
        currentPlayer.setVideoFrameMetadataListener(metadata);
    }
}
