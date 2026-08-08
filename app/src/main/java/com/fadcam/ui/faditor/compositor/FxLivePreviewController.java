package com.fadcam.ui.faditor.compositor;

import android.view.Surface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides when the master video should render through {@link FxPreviewTextureView} instead of the
 * ordinary {@code PlayerView}, and keeps that view fed.
 *
 * <p><b>Routing is per-PROJECT, not per-frame.</b> The obvious rule — route while a layer is live
 * at the playhead — would tear the decoder off its surface and back every time the user scrubbed
 * across a layer's edge, and a surface swap costs a visible black flash. So the decoder moves
 * once, when the project first contains an adjustment layer that renders anything, and stays
 * there. The GL view passes frames through untouched when no layer is live at the playhead, so
 * the two states are pixel-identical anyway; all the churn bought was flicker.</p>
 *
 * <p><b>It does not fight the PlayerView for the surface.</b> An earlier shape of this hid or
 * detached the PlayerView while routed; both make {@code PlayerView} re-grab the video output at
 * its own next attach, silently un-routing the preview. The arrangement that holds is to leave
 * PlayerView entirely alone and simply DRAW OVER it — {@link FxPreviewTextureView} is the next
 * sibling in {@code fx_below_group} and letterboxes to exactly the same rect, so the stale frame
 * underneath is never visible.</p>
 */
public final class FxLivePreviewController {

    private static final String TAG = "FxLivePreview";

    /** What this controller needs from the editor, so it needs nothing else from it. */
    public interface Host {
        /** The player rendering video right now — legacy or gapless. Null before preparation. */
        @Nullable ExoPlayer activeVideoPlayer();
        /** Hand video output back to the PlayerView's own surface. */
        void restoreVideoOutput();
        /** The clip under the playhead, whose colour grade the preview must show. */
        @Nullable com.fadcam.ui.faditor.model.Clip clipAtPlayhead();

        /**
         * The PiP layer, so its decoder can be composited INTO this chain rather than drawn over
         * the graded result. Null on a host that has no PiP support.
         */
        @Nullable default OverlayVideoPreviewView overlayVideoLayer() { return null; }
    }

    /**
     * Resolve a clip's colour grade for the renderer, or null when it has none.
     *
     * <p><b>The matrices come from media3, not from arithmetic here.</b> {@code Brightness},
     * {@code Contrast} and {@code RgbAdjustment} are {@code RgbMatrix} implementations, so asking
     * them for their matrix gives the export's exact numbers — including any future change to how
     * media3 defines them. Building the same matrix by hand would be a copy that silently ages.</p>
     *
     * <p>The activation thresholds mirror {@code EffectStack.toEffects} exactly, because a stage
     * export SKIPS must not run here: an HSL round trip at neutral saturation is not a perfect
     * identity, and "the preview drifts slightly on every clip" is a horrible bug to chase.</p>
     */
    @Nullable
    static FxPreviewTextureView.Grade gradeOf(
            @Nullable com.fadcam.ui.faditor.model.Clip clip) {
        if (clip == null || clip.isImageClip()) return null;
        com.fadcam.ui.faditor.effects.EffectStack s = clip.getEffectStack();
        if (s == null || !s.isActive()) return null;

        // Exposure then contrast, composed into one matrix — media3 runs them as two chained
        // effects, and chaining two matrix stages IS multiplying them.
        float[] matA = identity();
        boolean matAOn = false;
        if (Math.abs(s.getExposure()) > 0.001f) {
            matA = mul(new androidx.media3.effect.Brightness(s.getExposure())
                    .getMatrix(0L, false), matA);
            matAOn = true;
        }
        if (Math.abs(s.getContrast()) > 0.001f) {
            matA = mul(new androidx.media3.effect.Contrast(s.getContrast())
                    .getMatrix(0L, false), matA);
            matAOn = true;
        }

        boolean satOn = Math.abs(s.getSaturation() - 1f) > 0.001f;
        // HslAdjustment takes a PERCENTAGE and HslShaderProgram divides by 100, so what the
        // shader actually sees is the plain fractional delta. Getting this wrong is what once
        // made export ~100x less saturated than the preview.
        float satAdj = s.getSaturation() - 1f;

        float[] matB = identity();
        boolean matBOn = Math.abs(s.getTemperature()) > 0.001f || Math.abs(s.getTint()) > 0.001f;
        if (matBOn) {
            matB = new androidx.media3.effect.RgbAdjustment.Builder()
                    .setRedScale(1f + s.getTemperature() * 0.18f)
                    .setGreenScale(1f + s.getTint() * 0.08f)
                    .setBlueScale(1f - s.getTemperature() * 0.18f)
                    .build()
                    .getMatrix(0L, false);
        }

        boolean shaderOn = Math.abs(s.getHighlights()) > 0.001f
                || Math.abs(s.getShadows()) > 0.001f
                || Math.abs(s.getFade()) > 0.001f
                || s.getVignette() > 0.001f
                || s.getGrain() > 0.001f;

        return new FxPreviewTextureView.Grade(
                matA, matAOn, satOn, satAdj, matB, matBOn, shaderOn,
                s.getHighlights(), s.getShadows(), s.getFade(), s.getVignette(), s.getGrain());
    }

    @NonNull
    private static float[] identity() {
        float[] m = new float[16];
        android.opengl.Matrix.setIdentityM(m, 0);
        return m;
    }

    /** {@code out = lhs * rhs} — apply {@code rhs} first, then {@code lhs}. */
    @NonNull
    private static float[] mul(@NonNull float[] lhs, @NonNull float[] rhs) {
        float[] out = new float[16];
        android.opengl.Matrix.multiplyMM(out, 0, lhs, 0, rhs, 0);
        return out;
    }

    @NonNull private final FxPreviewTextureView view;
    @NonNull private final Host host;

    /** The decoder-facing surface, once the GL thread has published one. */
    @Nullable private Surface inputSurface;
    /** The PiP's decoder-facing surface, once the GL thread has published one. */
    @Nullable private Surface pipSurface;
    /** True while the decoder is rendering into {@link #view} rather than the PlayerView. */
    private boolean routed;
    /**
     * The player {@link #routed} describes. A decoder error releases the player and a later clip
     * builds a NEW one wired to the PlayerView; without this the stale flag would match, routing
     * would early-return, and the preview would render nothing at all — the identical
     * self-invalidating check {@link OverlayVideoPreviewView} needs for the keyed tier.
     */
    @Nullable private ExoPlayer routedPlayer;

    public FxLivePreviewController(@NonNull FxPreviewTextureView view, @NonNull Host host) {
        this.view = view;
        this.host = host;
        view.setSurfaceListener(new FxPreviewTextureView.SurfaceListener() {
            @Override public void onFxInputSurfaceReady(@NonNull Surface s) {
                inputSurface = s;
                // The surface arrives a frame or so after the view is laid out, which is usually
                // AFTER the first sync asked to route. Re-running the decision here is what makes
                // the deferred attach actually happen.
                route();
            }
            @Override public void onFxInputSurfaceLost() {
                inputSurface = null;
                pipSurface = null;
                OverlayVideoPreviewView ov = host.overlayVideoLayer();
                if (ov != null) ov.setFxCompositeSurface(null);
                if (routed) {
                    routed = false;
                    routedPlayer = null;
                    host.restoreVideoOutput();
                }
            }
            @Override public void onFxPipSurfaceReady(@NonNull Surface s) {
                pipSurface = s;
            }
        });
    }

    /**
     * Bring the live preview into line with {@code timeline} at {@code playheadMs}. Called from
     * the editor's existing playhead tick, on the main thread.
     */
    public void sync(@Nullable Timeline timeline, long playheadMs) {
        if (timeline == null) { stop(); return; }

        // Either reason is enough to take the decoder: a layer that renders something, or a clip
        // that carries a grade. Both are things the export will do and the user must therefore
        // see; the routing itself does not care which.
        List<AdjustmentLayer> all = timeline.getAdjustmentLayers();
        boolean anyRenders = false;
        if (all != null) {
            for (AdjustmentLayer l : all) {
                if (l.rendersAnything()) { anyRenders = true; break; }
            }
        }
        FxPreviewTextureView.Grade g = gradeOf(host.clipAtPlayhead());
        // A project whose ONLY effects are on an object still has to route, or those effects
        // render nowhere in the editor — which is the state that made "I don't see anything
        // working in a video layer" true.
        boolean objectFx = false;
        for (Clip c : timeline.getOverlayClips()) {
            if (c.getFx() != null && !c.getFx().active().isEmpty()) { objectFx = true; break; }
        }
        if (!anyRenders && g == null && !objectFx) { stop(); return; }

        if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);
        view.setGrade(g);

        // Resolve on THIS thread — the GL thread must never walk the live model. See
        // FxPreviewTextureView.Layer for why a snapshot rather than the layer itself.
        int[] size = videoSize();
        List<AdjustmentLayer> live = all == null ? java.util.Collections.emptyList()
                : LayerPreviewController.visibleAdjustmentLayers(timeline, playheadMs);
        List<FxPreviewTextureView.Layer> snapshot = new ArrayList<>(live.size());
        for (AdjustmentLayer l : live) {
            FxPreviewTextureView.Layer s =
                    FxPreviewTextureView.Layer.of(l, playheadMs, size[0], size[1]);
            if (s != null) snapshot.add(s);
        }
        view.setVideoSize(size[0], size[1]);
        view.setVideoRotation(rotationDegrees());
        view.setLayers(snapshot);
        syncPip();
        route();
    }

    /**
     * The decoded picture's dimensions, rotation applied.
     *
     * <p>The chain runs at this size, so getting it wrong does not merely look wrong — it changes
     * what a blur radius means relative to the export. Falls back to 1080p rather than to the
     * view's size, because the view is letterboxed and its height would make the aspect wrong.</p>
     */
    @NonNull
    private int[] videoSize() {
        ExoPlayer p = host.activeVideoPlayer();
        if (p == null) return new int[]{1920, 1080};
        VideoSize vs = p.getVideoSize();
        if (vs.width <= 0 || vs.height <= 0) return new int[]{1920, 1080};
        // The UPRIGHT size: the renderer stages the frame already rotated, so everything
        // downstream — the FBOs, uTexel, uAspect, the fit rect — is in display orientation.
        boolean swap = vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270;
        return swap ? new int[]{vs.height, vs.width} : new int[]{vs.width, vs.height};
    }

    /** @see FxPreviewTextureView#setVideoRotation */
    private int rotationDegrees() {
        ExoPlayer p = host.activeVideoPlayer();
        return p == null ? 0 : p.getVideoSize().unappliedRotationDegrees;
    }

    /**
     * Offer the PiP layer our composite surface, and push whatever geometry it reports.
     *
     * <p>The offer is made every tick rather than once: the PiP layer creates its player lazily
     * (only when a PiP clip is actually on screen) and releases it when the list empties, so the
     * moment routing becomes possible is not knowable from here.</p>
     */
    private void syncPip() {
        OverlayVideoPreviewView ov = host.overlayVideoLayer();
        if (ov == null || pipSurface == null) { view.setPip(null); return; }
        ov.setFxCompositeSurface(pipSurface);
        // Geometry only counts once the pixels are actually coming here. A keyed PiP keeps its
        // own live tier, so it stays a sibling View and must NOT also be drawn in the chain.
        view.setPip(ov.isFxRouted() ? ov.fxPipGeometry() : null);
    }

    /** Attach the decoder to the GL view, if it is not already there. */
    private void route() {
        Surface s = inputSurface;
        if (s == null) return;
        ExoPlayer p = host.activeVideoPlayer();
        if (p == null) return;
        if (routed && p == routedPlayer) return;
        try {
            p.setVideoSurface(s);
            routed = true;
            routedPlayer = p;
            FLog.i(TAG, "live FX preview engaged");
        } catch (RuntimeException e) {
            FLog.w(TAG, "could not route video to the FX preview: " + e);
        }
    }

    /**
     * Give the video back and hide the GL view. Idempotent — the common case is a project with no
     * adjustment layers at all, where this runs on every tick and must cost nothing.
     */
    public void stop() {
        if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
        if (!routed) return;
        routed = false;
        routedPlayer = null;
        view.setLayers(java.util.Collections.emptyList());
        view.setGrade(null);
        view.setPip(null);
        OverlayVideoPreviewView ov = host.overlayVideoLayer();
        if (ov != null) ov.setFxCompositeSurface(null);   // give the PiP its own surface back
        host.restoreVideoOutput();
        FLog.i(TAG, "live FX preview released");
    }
}
