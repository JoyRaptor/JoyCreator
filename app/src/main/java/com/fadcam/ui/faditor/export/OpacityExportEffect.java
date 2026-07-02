package com.fadcam.ui.faditor.export;

import androidx.annotation.NonNull;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

import com.fadcam.ui.faditor.model.Clip;

public class OpacityExportEffect implements GlEffect {

    @NonNull
    private final Clip clip;

    /**
     * Absolute timeline position (ms) of the start of the {@code EditedMediaItem}
     * this effect is attached to. The shader program subtracts this from the
     * per-frame presentation time to recover the clip-local time expected by
     * {@link Clip#opacityAtClipMs(long)}.
     */
    private final long clipTimelineOffsetMs;

    public OpacityExportEffect(@NonNull Clip clip, long clipTimelineOffsetMs) {
        this.clip = clip;
        this.clipTimelineOffsetMs = clipTimelineOffsetMs;
    }

    @NonNull
    @Override
    public GlShaderProgram toGlShaderProgram(@NonNull android.content.Context context, boolean hdr)
            throws VideoFrameProcessingException {
        if (hdr) {
            throw new VideoFrameProcessingException("HDR opacity export is not supported");
        }
        return new OpacityExportShaderProgram(clip, clipTimelineOffsetMs);
    }

    @Override
    public boolean isNoOp(int width, int height) {
        return false;
    }
}
