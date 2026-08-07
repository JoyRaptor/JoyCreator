package com.fadcam.ui.faditor.compositor;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.fx.FxCompiler;
import com.fadcam.ui.faditor.fx.FxCost;
import com.fadcam.ui.faditor.fx.FxPreviewTier;
import com.fadcam.ui.faditor.fx.FxStack;
import com.fadcam.ui.faditor.fx.FxUniforms;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.List;

/**
 * Puts an adjustment layer's effect stack on the LIVE PREVIEW
 * (SPEC_ADJUSTMENT_LAYERS_FX M5).
 *
 * <p><b>Preview was always the hard half.</b> Export gets this nearly free because its effect
 * chain is already ordered and already holds "everything beneath". Preview is an Android view
 * z-stack — a PlayerView with Canvas overlays above it — and nothing anywhere holds the
 * composited frame as a texture. The answer is to make one exist: {@code fx_below_group} wraps
 * the canvas backdrop through the PiP plane, so a single {@code setRenderEffect} on that
 * wrapper grades all of it at once.</p>
 *
 * <p><b>The same shader source as export.</b> {@link FxCompiler} emits AGSL from the very bodies
 * the export path compiles as GLSL, so the two cannot drift by editing one and forgetting the
 * other. That is the entire reason the compiler exists rather than each renderer owning its own
 * strings.</p>
 *
 * <p><b>Zero calls per frame when nothing animates.</b> {@code setRenderEffect} invalidates the
 * whole subtree, so calling it every tick would repaint the entire preview stack continuously
 * during playback. {@link #sync} therefore returns early unless the resolved state actually
 * changed — which, for an unanimated stack, is always.</p>
 */
public final class AdjustmentPreviewController {

    private static final String TAG = "AdjustPreview";

    @NonNull private final View wrapper;

    /** The state currently ON the wrapper. Null means "no effect applied". */
    @Nullable private String appliedKey;
    /** Latched after a compile failure so a broken shader is attempted once, not every frame. */
    private boolean degraded;

    public AdjustmentPreviewController(@NonNull View wrapper) {
        this.wrapper = wrapper;
    }

    /**
     * Bring the preview into line with {@code timeline} at {@code playheadMs}.
     *
     * <p>Called from the existing preview tick, on the main thread. No new thread: RenderEffect
     * is a view property and must be set where views are touched.</p>
     */
    public void sync(@Nullable Timeline timeline, long playheadMs) {
        if (timeline == null) { clear(); return; }
        if (FxPreviewTier.current() == FxPreviewTier.Tier.EXPORT_ONLY) {
            // Below API 31 there is no RenderEffect at all. The layer chip carries the badge;
            // there is nothing to attempt here.
            clear();
            return;
        }
        List<AdjustmentLayer> live =
                LayerPreviewController.visibleAdjustmentLayers(timeline, playheadMs);
        if (live.isEmpty()) { clear(); return; }

        // v1 previews the TOPMOST layer only. Chaining several would multiply the offscreen
        // cost per frame, and the second layer's result is the one the user is looking at.
        AdjustmentLayer layer = live.get(live.size() - 1);
        FxStack resolved = layer.getFx().resolveAt(playheadMs);
        FxCompiler.Plan plan = FxCompiler.plan(resolved);
        if (plan.passes.isEmpty()) { clear(); return; }

        String key = stateKey(layer, resolved, plan, playheadMs);
        if (key.equals(appliedKey)) return;   // the early-out that makes playback free
        if (degraded) return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Tier B: RenderEffect exists, AGSL does not. Blur is the only thing worth
            // approximating, and it IS an approximation — Skia's blur and this project's own
            // Gaussian differ visibly at the same radius, which is why export ships its own.
            applyPartial(resolved);
            appliedKey = key;
            return;
        }
        applyFull(plan, layer, playheadMs, key);
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void applyFull(@NonNull FxCompiler.Plan plan, @NonNull AdjustmentLayer layer,
                           long playheadMs, @NonNull String key) {
        try {
            RenderEffect chain = null;
            int budget = FxCost.PREVIEW_PASS_BUDGET;
            for (FxCompiler.Pass pass : plan.passes) {
                if (budget-- <= 0) break;   // the rest is badged export-only, not attempted
                RuntimeShader shader = new RuntimeShader(FxCompiler.emitAgsl(pass, 8));
                float w = Math.max(1, wrapper.getWidth());
                float h = Math.max(1, wrapper.getHeight());
                // The normalization prologue's inputs. Getting these wrong is precisely how the
                // shipping preview vignette spent its whole life evaluating to zero.
                shader.setFloatUniform("uOrigin", 0f, 0f);
                shader.setFloatUniform("uSize", w, h);
                shader.setFloatUniform("uTexel", 1f / w, 1f / h);
                shader.setFloatUniform("uAspect", w / h);
                shader.setFloatUniform("uTime", playheadMs / 1000f);
                shader.setFloatUniform("uDir", 1f, 0f);
                for (FxUniforms.Value v : FxUniforms.forPass(pass)) {
                    switch (v.components()) {
                        case 2: shader.setFloatUniform(v.name, v.data[0], v.data[1]); break;
                        case 3: shader.setFloatUniform(v.name, v.data[0], v.data[1], v.data[2]);
                                break;
                        default: shader.setFloatUniform(v.name, v.data[0]);
                    }
                }
                RenderEffect step = RenderEffect.createRuntimeShaderEffect(shader, "inputShader");
                chain = chain == null ? step : RenderEffect.createChainEffect(step, chain);
            }
            wrapper.setRenderEffect(chain);
            appliedKey = key;
        } catch (Exception e) {
            // A lost preview is never worth taking the editor down, and the export is
            // unaffected either way.
            FLog.w(TAG, "preview effect failed, showing ungraded: " + e);
            degraded = true;
            clear();
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void applyPartial(@NonNull FxStack resolved) {
        try {
            float radius = 0f;
            for (com.fadcam.ui.faditor.fx.FxInstance c : resolved.active()) {
                com.fadcam.ui.faditor.fx.FxEffectDef def = c.def();
                if (def == null || def.family != com.fadcam.ui.faditor.fx.FxEffectDef.Family.BLUR) {
                    continue;
                }
                com.fadcam.ui.faditor.fx.FxParam r = def.param("radius");
                if (r != null) radius = Math.max(radius, c.getScalar(r));
            }
            if (radius <= 0.01f) { clear(); return; }
            wrapper.setRenderEffect(RenderEffect.createBlurEffect(
                    radius, radius, android.graphics.Shader.TileMode.CLAMP));
        } catch (Exception e) {
            FLog.w(TAG, "partial preview failed: " + e);
            degraded = true;
            clear();
        }
    }

    /** Take any effect off the wrapper. Cheap and idempotent — it no-ops when already clear. */
    public void clear() {
        if (appliedKey == null) return;
        appliedKey = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wrapper.setRenderEffect(null);
        }
    }

    /**
     * Everything that would change the rendered result, and nothing that would not.
     *
     * <p>Includes the wrapper's SIZE: the shader bakes {@code uSize} in, so a resize with an
     * unchanged stack still needs a rebuild — the same stale-dimension bug the colour-grade
     * preview had, arriving by a different door.</p>
     */
    @NonNull
    private String stateKey(@NonNull AdjustmentLayer layer, @NonNull FxStack resolved,
                            @NonNull FxCompiler.Plan plan, long playheadMs) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(layer.getId()).append('|')
          .append(wrapper.getWidth()).append('x').append(wrapper.getHeight()).append('|')
          .append(resolved.toJson()).append('|')
          .append(layer.opacityAt(playheadMs));
        // Time only matters when something actually reads it, or every frame would rebuild.
        for (FxCompiler.Pass p : plan.passes) {
            for (com.fadcam.ui.faditor.fx.FxInstance c : p.cards) {
                com.fadcam.ui.faditor.fx.FxEffectDef def = c.def();
                if (def != null && def.glslBody.contains("FX_TIME")) {
                    sb.append('|').append(playheadMs);
                    return sb.toString();
                }
            }
        }
        return sb.toString();
    }
}
