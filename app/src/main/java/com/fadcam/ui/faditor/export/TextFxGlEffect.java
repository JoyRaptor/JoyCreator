package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.GlEffect;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.fx.FxCompiler;
import com.fadcam.ui.faditor.fx.FxInstance;
import com.fadcam.ui.faditor.fx.FxStack;
import com.fadcam.ui.faditor.fx.FxUniforms;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.overlay.TextOverlayRenderer;

/**
 * Renders ONE text overlay that carries its own effects (SPEC_ADJUSTMENT_LAYERS_FX M7).
 *
 * <p><b>Why text needed its own effect at all.</b> A PiP already reaches the screen through a GL
 * shader ({@code BlendModeGlEffect}), so M7 spliced the compiled FX straight into it. Text
 * reaches the screen through {@code CompositeExportOverlay} — a {@code BitmapOverlay} drawn on a
 * Canvas — and there is no shader there to splice into. This class gives a text overlay the same
 * GL path a PiP has: rasterise it, upload it, run its FX on those pixels, composite the result.</p>
 *
 * <p><b>It reuses the rasteriser, not a copy of it.</b> {@link TextOverlayRenderer#render} is the
 * same call {@code CompositeExportOverlay} makes, producing a full-frame bitmap with the text
 * already positioned — the exact role {@code PipFrameOverlay} plays for a PiP. So the glyphs,
 * decorations and placement are identical whichever path a given overlay takes.</p>
 *
 * <p><b>Only overlays that actually HAVE effects come here.</b> Everything else stays on the
 * Canvas path untouched, which is what makes this inert for every existing project.</p>
 *
 * <p><b>Z-ORDER CAVEAT, stated rather than discovered.</b> This runs inside the video effect
 * chain, so a text overlay with effects composites BEFORE the Canvas overlay pass — meaning
 * beneath any other text, sprite or caption. For the ordinary case (one styled title) that is
 * invisible; for a project that deliberately stacks text over text it is a real difference, and
 * the fix is to move the whole overlay pass into GL rather than to special-case it here.</p>
 */
public final class TextFxGlEffect implements GlEffect {

    @NonNull private final Context context;
    @NonNull private final TextOverlayItem overlay;
    private final long editorTimeOffsetMs;

    public TextFxGlEffect(@NonNull Context context, @NonNull TextOverlayItem overlay,
                          long editorTimeOffsetMs) {
        this.context = context.getApplicationContext();
        this.overlay = overlay;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) {
            throw new VideoFrameProcessingException("HDR text effects are not supported");
        }
        return new Program(overlay, editorTimeOffsetMs);
    }

    /** The text raster, as a texture. {@link BitmapOverlay} owns the upload and the cache. */
    private static final class TextFrame extends BitmapOverlay {
        @NonNull private final TextOverlayItem item;
        private final long editorTimeOffsetMs;
        private int outW = 1, outH = 1;

        TextFrame(@NonNull TextOverlayItem item, long editorTimeOffsetMs) {
            this.item = item;
            this.editorTimeOffsetMs = editorTimeOffsetMs;
        }

        @Override
        public void configure(@NonNull Size videoSize) {
            outW = Math.max(1, videoSize.getWidth());
            outH = Math.max(1, videoSize.getHeight());
        }

        @NonNull
        @Override
        public Bitmap getBitmap(long presentationTimeUs) {
            long timelineMs = (presentationTimeUs / 1000L) + editorTimeOffsetMs;
            // The ANIMATED transform, through the same accessors CompositeExportOverlay uses.
            // Rendering the AUTHORED values instead was a real bug caught by the first export:
            // an overlay with a scale keyframe came out at its authored size, so styling a
            // caption silently resized it.
            TextOverlayItem frameItem = new TextOverlayItem(
                    item.getText(), item.getColorInt(),
                    item.animatedCenterX(timelineMs), item.animatedCenterY(timelineMs),
                    item.animatedSizeFraction(timelineMs), item.animatedRotation(timelineMs));
            frameItem.setStrokeColorInt(item.getStrokeColorInt());
            frameItem.setStrokeWidthPx(item.getStrokeWidthPx());
            frameItem.setShadowColorInt(item.getShadowColorInt());
            frameItem.setShadowRadiusPx(item.getShadowRadiusPx());
            frameItem.setGlowColorInt(item.getGlowColorInt());
            frameItem.setGlowRadiusPx(item.getGlowRadiusPx());
            frameItem.setBackgroundColorInt(item.getBackgroundColorInt());
            frameItem.setFontFamily(item.getFontFamily());
            Bitmap textBmp = TextOverlayRenderer.render(frameItem, outW, outH);
            // COMPOSE ONTO A FULL FRAME. render() returns a bitmap sized to the TEXT, which
            // CompositeExportOverlay then positions with canvas.drawBitmap(cx - w/2, ...).
            // Handing that small bitmap to BitmapOverlay stretched it across the whole frame,
            // so a styled caption came out enormous -- caught by looking at the first export
            // rather than by reading, because both paths call the same renderer and the bug
            // was in what happens AFTER it.
            Bitmap full = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            if (textBmp != null && !textBmp.isRecycled()) {
                android.graphics.Canvas c = new android.graphics.Canvas(full);
                float cx = frameItem.getCenterX() * outW;
                float cy = frameItem.getCenterY() * outH;
                float rot = frameItem.getRotationDeg();
                c.save();
                if (rot != 0f) c.rotate(rot, cx, cy);
                c.drawBitmap(textBmp, cx - textBmp.getWidth() / 2f,
                        cy - textBmp.getHeight() / 2f, null);
                c.restore();
                textBmp.recycle();
            }
            // A NEW instance every frame: BitmapOverlay caches the GL texture on bitmap
            // IDENTITY, so returning a reused scratch would freeze frame one forever. The same
            // trap PipFrameOverlay and GlTransitionFrameOverlay both document.
            return full;
        }
    }

    private static final class Program extends BaseGlShaderProgram {

        private static final String VERTEX_SHADER =
                "#version 100\n"
                + "attribute vec4 aFramePosition;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "void main() {\n"
                + "  gl_Position = aFramePosition;\n"
                + "  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;\n"
                + "}\n";

        /** Composite the FX'd text over the frame. Straight SRC_OVER — text carries no blend. */
        private static final String BASE_FRAGMENT =
                "#version 100\n"
                + "precision mediump float;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "uniform sampler2D uVideoTexSampler0;\n"
                + "uniform sampler2D uTextTexSampler0;\n"
                + "void main() {\n"
                + "  vec4 base = texture2D(uVideoTexSampler0, vTexSamplingCoord);\n"
                // V-FLIPPED, for the reason BlendModeGlEffect documents at length: overlay UVs
                // are Y-up, and sampling unflipped composites every overlay upside-down.
                + "  vec2 ovc = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);\n"
                + "  vec4 src = texture2D(uTextTexSampler0, ovc);\n"
                + "  vec3 sc = src.rgb / max(src.a, 0.001);\n"
                + "  float a = src.a;\n"
                + "  gl_FragColor = vec4(mix(base.rgb, clamp(sc, 0.0, 1.0), a), base.a);\n"
                + "}\n";

        @NonNull private final TextOverlayItem item;
        @NonNull private final TextFrame frame;
        private final long editorTimeOffsetMs;
        @Nullable private GlProgram program;
        private boolean degraded;

        Program(@NonNull TextOverlayItem item, long editorTimeOffsetMs)
                throws VideoFrameProcessingException {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.item = item;
            this.frame = new TextFrame(item, editorTimeOffsetMs);
            this.editorTimeOffsetMs = editorTimeOffsetMs;
            try {
                this.program = new GlProgram(VERTEX_SHADER, fragmentFor(item));
                this.program.setBufferAttribute("aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(), 4);
            } catch (Exception e) {
                // A lost text effect beats a lost export — the same call the adjustment layer
                // makes, and for the same reason.
                FLog.w("TextFx", "shader failed, text will render unstyled: " + e);
                degraded = true;
            }
        }

        /**
         * Splice this overlay's FX into the composite, exactly as {@code BlendModeGlEffect} does
         * for a PiP — the compiler's declarations ahead of {@code main}, its per-card functions
         * folded onto the text's own colour.
         */
        @NonNull
        private static String fragmentFor(@NonNull TextOverlayItem item) {
            FxStack stack = item.getFx();
            if (stack == null || stack.active().isEmpty()) return BASE_FRAGMENT;
            FxCompiler.Plan plan = FxCompiler.plan(stack);
            FxCompiler.Pass fused = null;
            for (FxCompiler.Pass p : plan.passes) {
                if (!p.sampler) { fused = p; break; }
            }
            if (fused == null) return BASE_FRAGMENT;
            String emitted = FxCompiler.emitGlsl(fused, 8);
            int mainAt = emitted.indexOf("void main()");
            if (mainAt < 0) return BASE_FRAGMENT;
            String decls = emitted.substring(0, mainAt)
                    .replace("uniform sampler2D uTexSampler;\n", "")
                    .replace("varying vec2 vFxUv;\n", "")
                    .replace("precision mediump float;\n", "")
                    .replace("precision highp float;\n", "");
            StringBuilder fold = new StringBuilder();
            for (FxInstance card : fused.cards) {
                fold.append("  fxc = fxBlendOver(fxc, fx").append(card.slot)
                        .append("(ovc, fxc), ")
                        .append(FxCompiler.foldOpacityName(card)).append(", ")
                        .append(FxCompiler.foldBlendName(card)).append(");\n");
            }
            return BASE_FRAGMENT
                    .replace("varying vec2 vTexSamplingCoord;\n",
                            "varying vec2 vTexSamplingCoord;\n" + decls)
                    .replace("  gl_FragColor = vec4(mix(base.rgb",
                            "  vec4 fxc = vec4(sc, 1.0);\n" + fold + "  sc = fxc.rgb;\n"
                                    + "  gl_FragColor = vec4(mix(base.rgb");
        }

        @Override
        public Size configure(int inputWidth, int inputHeight) {
            Size size = new Size(Math.max(1, inputWidth), Math.max(1, inputHeight));
            frame.configure(size);
            return size;
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            try {
                long timelineMs = (presentationTimeUs / 1000L) + editorTimeOffsetMs;
                GlProgram p = program;
                // Gate on visibility here, not in the chain: this effect is emitted once for the
                // whole clip and self-gates per frame, the way PipFrameOverlay.activeAt does.
                if (degraded || p == null || item.isHidden() || !item.isVisibleAt(timelineMs)) {
                    passthrough(inputTexId);
                    return;
                }
                p.use();
                p.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, 0);
                p.setSamplerTexIdUniform("uTextTexSampler0",
                        frame.getTextureId(presentationTimeUs), 1);
                FxStack resolved = item.getFx() == null
                        ? null : item.getFx().resolveAt(timelineMs);
                if (resolved != null) {
                    FxCompiler.Plan plan = FxCompiler.plan(resolved);
                    for (FxCompiler.Pass pass : plan.passes) {
                        if (pass.sampler) continue;
                        for (FxUniforms.Value v : FxUniforms.forPass(pass)) {
                            try {
                                if (v.components() == 1) p.setFloatUniform(v.name, v.data[0]);
                                else p.setFloatsUniform(v.name, v.data);
                            } catch (RuntimeException ignored) {
                                // Stripped by the driver — guarded from the start here, because
                                // this exact thing cost an export cycle on the adjustment layer.
                            }
                        }
                        break;
                    }
                }
                p.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (Exception e) {
                if (!degraded) {
                    FLog.w("TextFx", "degrading to passthrough", e);
                    degraded = true;
                }
                passthrough(inputTexId);
            }
        }

        @Nullable private GlProgram passProgram;

        private static final String PASSTHROUGH_FRAGMENT =
                "#version 100\n"
                + "precision mediump float;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "uniform sampler2D uVideoTexSampler0;\n"
                + "void main() {\n"
                + "  gl_FragColor = texture2D(uVideoTexSampler0, vTexSamplingCoord);\n"
                + "}\n";

        private void passthrough(int inputTexId) throws VideoFrameProcessingException {
            // A real draw, not a no-op: media3 has already bound this program's output
            // framebuffer, so writing nothing would leave whatever the texture last held.
            try {
                if (passProgram == null) {
                    passProgram = new GlProgram(VERTEX_SHADER, PASSTHROUGH_FRAGMENT);
                    passProgram.setBufferAttribute("aFramePosition",
                            GlUtil.getNormalizedCoordinateBounds(), 4);
                }
                passProgram.use();
                passProgram.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, 0);
                passProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            super.release();
            try {
                if (program != null) program.delete();
                if (passProgram != null) passProgram.delete();
                frame.release();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
