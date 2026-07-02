package com.fadcam.ui.faditor.gltransitions;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.Effect;
import androidx.media3.effect.GlEffect;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Transition;

public class GlTransitionExportEffect implements GlEffect {

    private final Context context;
    private final Transition transition;
    private final Clip nextClip;
    private final long durationMs;
    /** Absolute composition time (ms) at which this transition item starts. */
    private final long timelineStartMs;
    @Nullable private final int[] canvasDims;
    @NonNull private final Uri sourceUri;
    private final int outW;
    private final int outH;

    public GlTransitionExportEffect(@NonNull Context context,
                                    @NonNull Transition transition,
                                    @NonNull Clip nextClip,
                                    long durationMs,
                                    long timelineStartMs,
                                    @Nullable int[] canvasDims,
                                    @NonNull Uri sourceUri,
                                    int outW, int outH) {
        this.context = context.getApplicationContext();
        this.transition = transition;
        this.nextClip = nextClip;
        this.durationMs = durationMs;
        this.timelineStartMs = timelineStartMs;
        this.canvasDims = canvasDims == null ? null : new int[]{canvasDims[0], canvasDims[1]};
        this.sourceUri = sourceUri;
        this.outW = outW;
        this.outH = outH;
    }

    @NonNull
    @Override
    public GlTransitionShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean hdr)
            throws VideoFrameProcessingException {
        if (hdr) {
            throw new VideoFrameProcessingException("HDR GL transitions are not supported");
        }
        String id = transition.resolveGlTransitionId();
        if (id == null) id = "CrossZoom";
        com.fadcam.FLog.d("GlTransitionExportEffect",
                "transition.type=" + transition.type
                + " glTransitionId=" + transition.glTransitionId
                + " → resolved=" + id
                + " outW=" + outW + " outH=" + outH
                + " durationMs=" + durationMs
                + " nextClip.uri=" + nextClip.getSourceUri());
        float ratio = (outW > 0 && outH > 0) ? (float) outW / (float) outH : 1f;
        try {
            String shader = GlTransitionShaderLoader.loadWrappedExportShader(context, id, outW, outH);
            GlTransitionFrameOverlay overlay = new GlTransitionFrameOverlay(context,
                    nextClip, transition, durationMs, timelineStartMs, canvasDims, sourceUri);
            return new GlTransitionShaderProgram(context, shader, transition, durationMs,
                    timelineStartMs, overlay);
        } catch (Exception e) {
            throw new VideoFrameProcessingException(e);
        }
    }

    @Override
    public boolean isNoOp(int width, int height) {
        return false;
    }
}
