package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;

import com.fadcam.ui.faditor.model.BlendModes;
import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * Blend modes for an IMAGE overlay: the picture is composited against the accumulated frame with
 * MULTIPLY / SCREEN / OVERLAY / ADD, which is the one thing a {@code BitmapOverlay} cannot express
 * — a Canvas has no video underneath it to blend with.
 *
 * <p>Emitted only for an image overlay whose mode is NOT normal. Everything else stays on
 * {@link CompositeExportOverlay}'s canvas path exactly as it always did, so this is provably inert
 * for every project that has not chosen a blend mode.</p>
 *
 * <p><b>Now also carries the chroma key and per-object FX</b> — this note used to say it
 * deliberately did not, with "when those features become real for images, they arrive here". They
 * did, and they arrived here. Both are DEVICE-VERIFIED by measuring exported frames against a dump
 * of the overlay bitmap rather than by eye: with an invert card, 174 of 190 sampled points inside
 * the mask are closer to the INVERSE of the un-effected image than to it; with a max-tolerance key
 * the image leaves the frame entirely. Reading those frames by eye is what produced a wrong
 * conclusion and a needless revert first time round — the fixture's overlay is a photo of the same
 * scene as the video, so "the image, inverted" and "the video, inverted" look alike.
 *
 * <p>Track MATTES remain PiP-only: an image has no peer clip to matte against.</p>
 *
 * <p><b>Z-ORDER CAVEAT, stated plainly.</b> Chain position is paint order, so a blended image
 * composites where this effect sits in the chain — above the video and every PiP, below the
 * canvas overlay pass. An image overlay that a user ordered ABOVE a text overlay will therefore
 * appear BENEATH it once a blend mode is chosen. This is the same compromise
 * {@code TextFxGlEffect} already makes for text that carries effects, and it is why blend stays
 * an opt-in rather than something every image goes through.</p>
 *
 * <p><b>The preview now runs all three.</b> This note used to say the preview showed the image
 * NORMAL, and that the fix would need image overlays fed into the GL composite as 2D textures.
 * That is exactly what happened: {@code TextOverlayLayer.fxPipFor} builds a
 * {@code FxPreviewTextureView.Pip} per image whose {@code wantsGlExport()} is true, backed by the
 * same decoded bitmap the {@code ImageView} shows, and the composite compiles the same FX splice,
 * the same {@code BlendModes} equations and the same {@code ChromaKey} function this effect does.
 * Measured on a device rather than read by eye — see this class's note above for why that
 * distinction is not pedantry here.</p>
 */
final class ImageBlendGlEffect implements GlEffect {

    private final Context context;
    private final TextOverlayItem item;
    private final long projectDurationMs;
    private final long editorTimeOffsetMs;

    ImageBlendGlEffect(@NonNull Context context, @NonNull TextOverlayItem item,
                       long projectDurationMs, long editorTimeOffsetMs) {
        this.context = context.getApplicationContext();
        this.item = item;
        this.projectDurationMs = projectDurationMs;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) {
            throw new VideoFrameProcessingException("HDR blend modes are not supported");
        }
        return new Program(context, item, projectDurationMs, editorTimeOffsetMs);
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

        private static final String FRAGMENT_SHADER =
                "#version 100\n"
                + "precision mediump float;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "uniform sampler2D uVideoTexSampler0;\n"
                + "uniform sampler2D uOverlayTexSampler0;\n"
                + "uniform float uBlendMode;\n" // float: ES2 int uniforms are patchy on old drivers
                + "uniform vec3 uKeyColor;\n"
                // x=enabled(0/1), y=tolerance, z=fuzziness, w=offset — packed by ChromaKey
                + "uniform vec4 uKeyParams;\n"
                // The key itself is NOT written here, for the same reason BlendModeGlEffect does
                // not write it: one shared source means the preview tier and both export paths
                // cannot drift apart on what "green enough" means.
                + com.fadcam.ui.faditor.model.ChromaKey.GLSL_KEY_FN
                // The blend equations are NOT written here — same reason BlendModeGlEffect does
                // not write them. One authority means OVERLAY's per-channel branch exists once,
                // and BlendModesTest pins the text byte-for-byte.
                + BlendModes.GLSL_BLEND_FN
                + "void main() {\n"
                + "  vec4 base = texture2D(uVideoTexSampler0, vTexSamplingCoord);\n"
                // Bitmap textures upload Y-DOWN (row 0 = top) while the frame UVs are Y-UP —
                // sample the overlay V-flipped or the image composites upside-down. Device-proven
                // on the PiP path, where the pre-fix symptom was an overlay authored at y=.378
                // exporting at y=.622.
                + "  vec2 ovc = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);\n"
                + "  vec4 src = texture2D(uOverlayTexSampler0, ovc);\n"
                + "  vec3 sc = src.rgb / max(src.a, 0.001);\n" // unpremultiply
                // Chroma key on the STRAIGHT colour, BEFORE any effect grades it — keying measures
                // distance from a colour in the SOURCE image, so inverting or grading first would
                // stop a green screen being green and the key would silently miss. The enabled
                // test lives inside the shared function, so there is no second place to forget it.
                + "  float a = fadKeyAlpha(sc, src.a, uKeyColor, uKeyParams);\n"
                // Mix by the overlay's own alpha, which already carries the item's keyframed
                // opacity and its entrance animation via the Canvas paint, and now the key.
                + "  vec3 outc = mix(base.rgb, clamp(blendPix(base.rgb, sc), 0.0, 1.0), a);\n"
                + "  gl_FragColor = vec4(outc, base.a);\n"
                + "}\n";

        /**
         * SPEC E mesh-stamp composite base: {@link #FRAGMENT_SHADER} sampling a frame-sized stamp
         * FBO instead of a {@code BitmapOverlay} bitmap, plus a frame-space mask.
         *
         * <p>Two differences from the bitmap path, both forced by what the stamp IS:
         * <ul>
         *   <li>NO V-FLIP. Bitmap uploads are top-row-first (row 0 = top) while frame UVs are
         *       Y-UP, hence {@code ovc=(x,1-y)}. A stamp FBO is bottom-up like every other GL
         *       target in both renderers (preview ping-pongs, {@code GlPipFrameOverlay}), sampled
         *       without a flip — the same reason {@code PIP_MESH_FRAGMENT} drops the flip preview-
         *       side. Sampling a stamp flipped would composite it upside-down.</li>
         *   <li>MASK IN FRAME SPACE. The bitmap path bakes the mask into pixels via
         *       {@code ImageOverlayDraw.beginMask} (Canvas {@code Path}); a GL stamp cannot run a
         *       {@code Path}, and {@code ImageBlendGlEffect} has no mask uniforms at all today.
         *       So the mesh branch evaluates the SAME {@code MaskSdf} field the preview Pip does,
         *       at {@code vTexSamplingCoord} (frame space) — which is also where the export's
         *       Canvas mask already lived (opened BEFORE item transforms), so a mask cuts the
         *       warped result and never bends with it, on both sides.</li>
         * </ul>
         *
         * <p>Everything else is untouched: key on straight colour before FX, fused FX only, blend
         * via {@code blendPix}, mix by stamp alpha (which already bakes opacity+wipe like the Canvas
         * paint did). {@code __MASKN__} is substituted with the item's shape count like the preview.
         * An unmeshed image never compiles this string.
         */
        private static final String FRAGMENT_MESH_BASE =
                "#version 100\n"
                + "precision mediump float;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "uniform sampler2D uVideoTexSampler0;\n"
                + "uniform sampler2D uOverlayTexSampler0;\n"
                + "uniform float uBlendMode;\n"
                + "uniform vec3 uKeyColor;\n"
                + "uniform vec4 uKeyParams;\n"
                + "uniform float uMeshMaskOn;\n"
                + "uniform float uMeshMaskInvert;\n"
                + "uniform vec4 uMeshMaskGeo[__MASKN__];\n"
                + "uniform vec2 uMeshMaskRot[__MASKN__];\n"
                + "uniform float uMeshMaskCorner[__MASKN__];\n"
                + "uniform float uMeshMaskFeather[__MASKN__];\n"
                + "uniform float uMeshMaskOp[__MASKN__];\n"
                + "uniform vec2 uMeshTexel;\n"
                + com.fadcam.ui.faditor.model.ChromaKey.GLSL_KEY_FN
                + BlendModes.GLSL_BLEND_FN
                + com.fadcam.ui.faditor.model.MaskSdf.GLSL_MASK_FN
                + "void main() {\n"
                + "  vec4 base = texture2D(uVideoTexSampler0, vTexSamplingCoord);\n"
                + "  vec2 ovc = vTexSamplingCoord;\n"
                + "  vec4 src = texture2D(uOverlayTexSampler0, ovc);\n"
                + "  vec3 sc = src.rgb / max(src.a, 0.001);\n"
                + "  float a = fadKeyAlpha(sc, src.a, uKeyColor, uKeyParams);\n"
                + "  if (uMeshMaskOn > 0.5) {\n"
                + "    vec2 frame = vec2(1.0) / uMeshTexel;\n"
                + "    float inside = 0.0;\n"
                + "    for (int i = 0; i < __MASKN__; i++) {\n"
                + "      float sd = fxShapeSd(vTexSamplingCoord, frame, uMeshMaskGeo[i],\n"
                + "                           uMeshMaskRot[i], uMeshMaskCorner[i]);\n"
                + "      float c = fxCoverageOf(sd, uMeshMaskFeather[i]);\n"
                + "      if (i == 0) inside = c;\n"
                + "      else if (uMeshMaskOp[i] > 1.5) inside = min(inside, c);\n"
                + "      else if (uMeshMaskOp[i] > 0.5) inside = min(inside, 1.0 - c);\n"
                + "      else inside = max(inside, c);\n"
                + "    }\n"
                // HOLE BY DEFAULT — see fxMaskCover in MaskSdf.GLSL_MASK_FN, the single
                // statement of this policy shared by all three GL consumers.
                + "    float cover = fxMaskCover(inside, uMeshMaskInvert);\n"
                + "    a *= clamp(cover, 0.0, 1.0);\n"
                + "  }\n"
                + "  vec3 outc = mix(base.rgb, clamp(blendPix(base.rgb, sc), 0.0, 1.0), a);\n"
                + "  gl_FragColor = vec4(outc, base.a);\n"
                + "}\n";

        private static String withMeshMaskShapes(@NonNull String fragment, int maskShapes) {
            int n = Math.max(1, Math.min(com.fadcam.ui.faditor.model.MaskSdf.MAX_SHAPES, maskShapes));
            return fragment.replace("__MASKN__", Integer.toString(n));
        }

        /**
         * Per-object FX for an IMAGE overlay, spliced into this shader — the same construction
         * {@code BlendModeGlEffect.fragmentFor} performs for a PiP, against the same
         * {@code FxStack} type, so the two renderers cannot drift into different fold orders.
         *
         * <p><b>The no-FX case returns the string that shipped, untouched.</b> An image that
         * carries no effects compiles exactly the source it always did, so this is provably inert
         * for every existing project — the same gate discipline the blend path itself uses.</p>
         *
         * <p><b>Only the FUSED pass.</b> A SAMPLER card (blur and friends) needs the object
         * rendered to its own FBO first, which is the adjustment layer's machinery; such a card is
         * skipped here exactly as it is for a PiP, and {@code FxPreviewTier} is what tells the UI
         * so. That is a smaller gap than the whole tab being inert, and an honest one.</p>
         */
        @NonNull
        private static String fragmentFor(@NonNull TextOverlayItem item) {
            com.fadcam.ui.faditor.fx.FxStack stack = item.getFx();
            if (stack == null || stack.active().isEmpty()) return FRAGMENT_SHADER;
            com.fadcam.ui.faditor.fx.FxCompiler.Plan plan =
                    com.fadcam.ui.faditor.fx.FxCompiler.plan(stack);
            com.fadcam.ui.faditor.fx.FxCompiler.Pass fused = null;
            for (com.fadcam.ui.faditor.fx.FxCompiler.Pass p : plan.passes) {
                if (!p.sampler) { fused = p; break; }
            }
            if (fused == null) return FRAGMENT_SHADER;

            String emitted = com.fadcam.ui.faditor.fx.FxCompiler.emitGlsl(fused, 8);
            int mainAt = emitted.indexOf("void main()");
            if (mainAt < 0) return FRAGMENT_SHADER;
            // Everything the compiler declared BEFORE its entry point; its own main() is dropped
            // because this shader has one and the subject here is one object's colour.
            String decls = emitted.substring(0, mainAt)
                    // Already declared below; declaring either twice fails to compile.
                    .replace("uniform sampler2D uTexSampler;\n", "")
                    .replace("varying vec2 vFxUv;\n", "")
                    .replace("precision mediump float;\n", "")
                    .replace("precision highp float;\n", "");

            StringBuilder fold = new StringBuilder();
            for (com.fadcam.ui.faditor.fx.FxInstance card : fused.cards) {
                fold.append("  fxc = fxBlendOver(fxc, fx").append(card.slot)
                        .append("(ovc, fxc), ")
                        .append(com.fadcam.ui.faditor.fx.FxCompiler.foldOpacityName(card))
                        .append(", ")
                        .append(com.fadcam.ui.faditor.fx.FxCompiler.foldBlendName(card))
                        .append(");\n");
            }
            // Applied to the UNPREMULTIPLIED colour and BEFORE the blend, so an effect grades the
            // picture and the blend mode then composites the graded picture — the order the drawer
            // shows the two tabs in, and the order the preview will have to match.
            String apply = "  vec4 fxc = vec4(sc, 1.0);\n" + fold + "  sc = fxc.rgb;\n";
            return FRAGMENT_SHADER
                    .replace("varying vec2 vTexSamplingCoord;\n",
                            "varying vec2 vTexSamplingCoord;\n" + decls)
                    .replace("  vec3 outc = mix(", apply + "  vec3 outc = mix(");
        }

        /**
         * SPEC E: FX splice into the mesh base. Same construction as {@link #fragmentFor} (same
         * fold order: key, then FX, then blend — key measures source colour, FX grades it, blend
         * composites it), applied to {@link #FRAGMENT_MESH_BASE} instead of {@link #FRAGMENT_SHADER}
         * so a meshed image's cards run. No-FX returns the mesh base sized to the mask count (an
         * unmeshed image never compiles this string).
         */
        @NonNull
        private static String fragmentForMesh(@NonNull TextOverlayItem item, int maskShapes) {
            String base = withMeshMaskShapes(FRAGMENT_MESH_BASE, maskShapes);
            com.fadcam.ui.faditor.fx.FxStack stack = item.getFx();
            if (stack == null || stack.active().isEmpty()) return base;
            com.fadcam.ui.faditor.fx.FxCompiler.Plan plan =
                    com.fadcam.ui.faditor.fx.FxCompiler.plan(stack);
            com.fadcam.ui.faditor.fx.FxCompiler.Pass fused = null;
            for (com.fadcam.ui.faditor.fx.FxCompiler.Pass p : plan.passes) {
                if (!p.sampler) { fused = p; break; }
            }
            if (fused == null) return base;
            String emitted = com.fadcam.ui.faditor.fx.FxCompiler.emitGlsl(fused, 8);
            int mainAt = emitted.indexOf("void main()");
            if (mainAt < 0) return base;
            String decls = emitted.substring(0, mainAt)
                    .replace("uniform sampler2D uTexSampler;\n", "")
                    .replace("varying vec2 vFxUv;\n", "")
                    .replace("precision mediump float;\n", "")
                    .replace("precision highp float;\n", "");
            StringBuilder fold = new StringBuilder();
            for (com.fadcam.ui.faditor.fx.FxInstance card : fused.cards) {
                fold.append("  fxc = fxBlendOver(fxc, fx").append(card.slot)
                        .append("(ovc, fxc), ")
                        .append(com.fadcam.ui.faditor.fx.FxCompiler.foldOpacityName(card))
                        .append(", ")
                        .append(com.fadcam.ui.faditor.fx.FxCompiler.foldBlendName(card))
                        .append(");\n");
            }
            String apply = "  vec4 fxc = vec4(sc, 1.0);\n" + fold + "  sc = fxc.rgb;\n";
            return base
                    .replace("varying vec2 vTexSamplingCoord;\n",
                            "varying vec2 vTexSamplingCoord;\n" + decls)
                    .replace("  vec3 outc = mix(", apply + "  vec3 outc = mix(");
        }

        private final GlProgram glProgram;
        private final ImageOverlayFrameOverlay overlay;
        private final float mode;
        private final float[] keyColor;
        private final float[] keyParams;
        /** Kept for its FX uniform values; the geometry all lives in the overlay. */
        @NonNull private final TextOverlayItem fxItem;
        /** SPEC E: true when this effect was built for a bent image (fixed at export start, like blend). */
        private final boolean mesh;
        /** SPEC E mesh shape count the program compiled with (1 when unmasked; MAX-capped). */
        private final int meshMaskShapes;
        /** SPEC E shared stamp (own FBO on THIS effect thread; lazy GL init inside). Null when flat. */
        @Nullable private final com.fadcam.ui.faditor.compositor.MeshStampGl meshStamp;
        /** SPEC E flat fallback program (correct V-flip for the bitmap) — mesh effects hold both. */
        @Nullable private final GlProgram flatProgram;
        /** SPEC E app context for the stamp's decode (same ceiling as the flat overlay). */
        @NonNull private final Context appContext;
        /** SPEC E decoded source for the stamp (same decode as the flat overlay, once per effect). */
        @Nullable private android.graphics.Bitmap meshSource;
        private boolean meshDecoded;
        private int meshFrameW = 1, meshFrameH = 1;
        private final long meshProjectDurationMs;
        private final long meshEditorTimeOffsetMs;

        Program(@NonNull Context context, @NonNull TextOverlayItem item,
                long projectDurationMs, long editorTimeOffsetMs)
                throws VideoFrameProcessingException {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.appContext = context.getApplicationContext();
            this.overlay = new ImageOverlayFrameOverlay(
                    context, item, projectDurationMs, editorTimeOffsetMs);
            this.mode = BlendModes.modeCode(item.getOverlayBlendMode());
            // Packed by the shared authority, not unpacked by hand here — the preview tier and the
            // PiP path pack the identical uniforms from the identical spec, so a clamp added on
            // one side can never be missing on another.
            com.fadcam.ui.faditor.model.CompositingSpec spec = item.getCompositing();
            this.keyColor = com.fadcam.ui.faditor.model.ChromaKey.packColor(spec);
            this.keyParams = com.fadcam.ui.faditor.model.ChromaKey.packParams(spec);
            this.fxItem = item;
            this.meshProjectDurationMs = projectDurationMs;
            this.meshEditorTimeOffsetMs = editorTimeOffsetMs;
            // SPEC E routing, fixed at build time like blend: a bent image (v1 images only) takes
            // the stamp + mesh fragment; everything else compiles exactly what shipped.
            boolean wantMesh = false;
            int maskShapes = 1;
            com.fadcam.ui.faditor.compositor.MeshStampGl stamp = null;
            try {
                wantMesh = item.isImage() && item.hasMesh();
                if (wantMesh) {
                    int n = 1;
                    if (spec != null && !spec.masks.isEmpty()) {
                        int[] ops = com.fadcam.ui.faditor.model.MaskSdf.packOps(spec);
                        n = Math.max(1, ops.length);
                    }
                    maskShapes = Math.max(1, Math.min(
                            com.fadcam.ui.faditor.model.MaskSdf.MAX_SHAPES, n));
                    stamp = new com.fadcam.ui.faditor.compositor.MeshStampGl();
                    item.installMeshCurve();
                }
            } catch (Exception ignored) {
                wantMesh = false;
                stamp = null;
                maskShapes = 1;
            }
            this.mesh = wantMesh;
            this.meshMaskShapes = maskShapes;
            this.meshStamp = stamp;
            // SPEC E compile fallback (same ranking as preview: flat for a session beats a failed
            // export). A mesh fragment that refuses (e.g. mask arrays over a weak driver's uniform
            // budget) degrades THIS effect to flat — the preview's mesh→flat fallback draws flat
            // too, so the two stay in step instead of one failing loudly while the other warps.
            String fragment;
            GlProgram flat = null;
            if (mesh) {
                String meshFragment;
                try {
                    meshFragment = fragmentForMesh(item, meshMaskShapes);
                } catch (Exception e) {
                    com.fadcam.FLog.w("ImageBlendMesh",
                            "mesh fragment refused; exporting flat", e);
                    meshFragment = null;
                }
                if (meshFragment == null) {
                    // Mesh refused at compile: run FULLY flat (like preview's fallback draws flat).
                    // fragment=flat (correct flip for the bitmap) and flat=null so the mesh branch
                    // below is never entered (its guard requires flatProgram != null) — the effect
                    // is then byte-for-byte the flat one it always was. Preview draws flat too.
                    fragment = fragmentFor(item);
                    flat = null;
                    try {
                        com.fadcam.ui.faditor.compositor.MeshStampGl s = this.meshStamp;
                        if (s != null) { try { s.release(); } catch (Exception ignored2) { } }
                    } catch (Exception ignored) { }
                } else {
                    // Flat fallback program (correct flip for the bitmap) — compiled alongside so
                    // identity/degraded/transparent frames draw flat, never vanishing. If even the
                    // shipped flat string refuses, there is nothing to fall back to: the GlProgram
                    // constructor below throws loudly, same as today.
                    fragment = meshFragment;
                    try {
                        flat = new GlProgram(VERTEX_SHADER, fragmentFor(item));
                        flat.setBufferAttribute("aFramePosition",
                                GlUtil.getNormalizedCoordinateBounds(), 4);
                    } catch (Exception ignored) {
                        flat = null;
                    }
                }
            } else {
                fragment = fragmentFor(item);
            }
            try {
                this.glProgram = new GlProgram(VERTEX_SHADER, fragment);
                this.glProgram.setBufferAttribute("aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(), 4);
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
            this.flatProgram = flat;
        }

        @NonNull
        @Override
        public Size configure(int inputWidth, int inputHeight) {
            Size size = new Size(Math.max(1, inputWidth), Math.max(1, inputHeight));
            overlay.configure(size);
            meshFrameW = size.getWidth();
            meshFrameH = size.getHeight();
            if (meshStamp != null) meshStamp.configure(meshFrameW, meshFrameH);
            return size;
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            GlErrors.drain("pending when ImageBlendGlEffect began (left by an earlier step)");
            // SPEC E mesh branch (gated at construction; flat path below untouched and byte-identical
            // for every unmeshed image — same program string, same uniforms, same order).
            if (mesh && meshStamp != null && flatProgram != null) {
                drawMeshFrame(inputTexId, presentationTimeUs);
                return;
            }
            try {
                glProgram.use();
                glProgram.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, 0);
                glProgram.setSamplerTexIdUniform("uOverlayTexSampler0",
                        overlay.getTextureId(presentationTimeUs), 1);
                glProgram.setFloatUniform("uBlendMode", mode);
                glProgram.setFloatsUniform("uKeyColor", keyColor);
                glProgram.setFloatsUniform("uKeyParams", keyParams);
                // Per-object FX, resolved at the playhead so a keyed parameter animates — the same
                // stack and the same resolver an adjustment layer and a PiP use.
                setFxUniforms(presentationTimeUs);
                glProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        /**
         * SPEC E mesh draw: stamp the bend (shared wrapper, same matrices as preview), composite
         * the stamp with mask/key/FX/blend. Identity/degraded/undecoded/transparent all degrade
         * INSIDE to the flat bitmap via {@link #flatProgram} (correct flip) — a bent image drawn
         * flat for a frame is recoverable, a vanished image is not. Never throws except via
         * {@link VideoFrameProcessingException} like the flat path.
         */
        private void drawMeshFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            long timelineMs = presentationTimeUs / 1000 + meshEditorTimeOffsetMs;
            TextOverlayItem item = fxItem;
            // Flat fallback helper draws the ordinary bitmap path through flatProgram (correct flip,
            // no mask — the flat bitmap already bakes its Canvas mask). Used for transparent,
            // undecoded, degenerate placement, identity and degraded stamp alike.
            try {
                if (!item.isImage() || !item.isVisibleAt(timelineMs)) {
                    drawFlatFallback(inputTexId, presentationTimeUs);
                    return;
                }
                if (!meshDecoded) {
                    meshDecoded = true;
                    try {
                        meshSource = ImageOverlayDraw.decode(appContext, item, meshFrameW, meshFrameH);
                        if (meshSource == null) {
                            com.fadcam.FLog.w("ImageBlendMesh", "mesh source decode null; drawing flat");
                        }
                    } catch (Throwable t) {
                        com.fadcam.FLog.w("ImageBlendMesh", "mesh source decode failed; drawing flat", t);
                        meshSource = null;
                    }
                }
                if (meshSource == null || meshSource.isRecycled()
                        || meshSource.getWidth() <= 0 || meshSource.getHeight() <= 0) {
                    drawFlatFallback(inputTexId, presentationTimeUs);
                    return;
                }
                // Unfolded animated placement (same model authorities as the preview enrichment).
                float cx = item.animatedCenterX(timelineMs);
                float cy = item.animatedCenterY(timelineMs);
                float sizeFrac = item.animatedSizeFraction(timelineMs);
                float sx = item.animatedScaleX(timelineMs);
                float sy = item.animatedScaleY(timelineMs);
                float rot = item.animatedRotation(timelineMs);
                float opacity = item.animatedOpacity(timelineMs);
                if (!(sizeFrac > 0f) || !(sx > 0f) || !(sy > 0f) || meshFrameW <= 0 || meshFrameH <= 0) {
                    drawFlatFallback(inputTexId, presentationTimeUs);
                    return;
                }
                float imageAspect = meshSource.getWidth() / (float) meshSource.getHeight();
                float frameAspect = meshFrameW / (float) meshFrameH;
                if (!(imageAspect > 0f) || !(frameAspect > 0f)) {
                    drawFlatFallback(inputTexId, presentationTimeUs);
                    return;
                }
                float wNorm = sizeFrac * imageAspect * sx / frameAspect;
                float hNorm = sizeFrac * sy;
                if (!(wNorm > 0f) || !(hNorm > 0f)) {
                    drawFlatFallback(inputTexId, presentationTimeUs);
                    return;
                }
                float[] pins = null;
                if (item.hasCornerPin()) {
                    pins = new float[com.fadcam.ui.faditor.model.CornerPin.SIZE];
                    item.animatedCornerPin(timelineMs, pins);
                }
                float pivOffX, pivOffY;
                boolean applyPivot;
                try {
                    pivOffX = item.mirrorSignX() * item.pivotOffsetFromCentreX(wNorm, hNorm, pins);
                    pivOffY = item.mirrorSignY() * item.pivotOffsetFromCentreY(wNorm, hNorm, pins);
                    applyPivot = !item.isRotationPivotNeutral(pins);
                } catch (Exception ignored) {
                    drawFlatFallback(inputTexId, presentationTimeUs);
                    return;
                }
                com.fadcam.ui.faditor.transcript.CaptionAnimator.Transform preset;
                try {
                    preset = com.fadcam.ui.faditor.transcript.CaptionAnimator.textBoxTransformAt(
                            com.fadcam.ui.faditor.transcript.CaptionAnimator.parsePreset(
                                    item.getTextAnimPreset()),
                            timelineMs, item.motionRangeStartMs(),
                            item.motionSpanMs(meshProjectDurationMs),
                            item.getTextAnimInPct(), item.getTextAnimOutPct(),
                            sizeFrac * meshFrameH);
                } catch (Exception ignored) {
                    drawFlatFallback(inputTexId, presentationTimeUs);
                    return;
                }
                float dxNorm = preset.dx / (float) meshFrameW;
                float dyNorm = preset.dy / (float) meshFrameH;
                float alpha = Math.max(0f, Math.min(1f, opacity * preset.alpha));
                float reveal = Math.max(0f, Math.min(1f, preset.revealFrac));
                if (alpha <= 0.001f) {
                    drawFlatFallback(inputTexId, presentationTimeUs);
                    return;
                }
                com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec spec = null;
                long localMs = 0L;
                try {
                    item.installMeshCurve();
                    com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec srcSpec = item.getMesh();
                    if (srcSpec == null || !srcSpec.hasWarp()) {
                        drawFlatFallback(inputTexId, presentationTimeUs);
                        return;
                    }
                    spec = srcSpec.copy();
                    localMs = item.meshLocalTime(timelineMs);
                } catch (Exception ignored) {
                    drawFlatFallback(inputTexId, presentationTimeUs);
                    return;
                }
                meshStamp.configure(meshFrameW, meshFrameH);
                int stampTex = 0;
                try {
                    // SPEC H mirror — the model's ONE shared definition
                    // (TextOverlayItem.mirrorSignX/Y), same values the preview passes.
                    stampTex = meshStamp.renderToStamp(meshSource, spec, localMs,
                            cx, cy, wNorm, hNorm, pivOffX, pivOffY, applyPivot, rot,
                            preset.scaleX, preset.scaleY, dxNorm, dyNorm,
                            alpha, reveal, pins,
                            item.mirrorSignX(), item.mirrorSignY());
                } catch (Exception e) {
                    com.fadcam.FLog.w("ImageBlendMesh", "stamp failed; drawing flat", e);
                    stampTex = 0;
                }
                if (stampTex == 0) {
                    drawFlatFallback(inputTexId, presentationTimeUs);
                    return;
                }
                // Composite the stamp (no flip — FBO, like every other GL target).
                try {
                    glProgram.use();
                    glProgram.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, 0);
                    glProgram.setSamplerTexIdUniform("uOverlayTexSampler0", stampTex, 1);
                    glProgram.setFloatUniform("uBlendMode", mode);
                    glProgram.setFloatsUniform("uKeyColor", keyColor);
                    glProgram.setFloatsUniform("uKeyParams", keyParams);
                    setFxUniforms(presentationTimeUs);
                    uploadMeshMask(timelineMs);
                    glProgram.bindAttributesAndUniforms();
                    uploadMeshMaskArrays(timelineMs);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    GlUtil.checkGlError();
                } catch (Exception e) {
                    throw new VideoFrameProcessingException(e);
                }
            } catch (VideoFrameProcessingException e) {
                throw e;
            } catch (Exception e) {
                // A bend never costs the picture: last-resort flat.
                try {
                    drawFlatFallback(inputTexId, presentationTimeUs);
                } catch (Exception fallbackFails) {
                    throw new VideoFrameProcessingException(fallbackFails);
                }
            }
        }

        /** Flat fallback through {@link #flatProgram} (correct bitmap flip, Canvas-baked mask). */
        private void drawFlatFallback(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            GlProgram flat = flatProgram != null ? flatProgram : glProgram;
            try {
                flat.use();
                flat.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, 0);
                flat.setSamplerTexIdUniform("uOverlayTexSampler0",
                        overlay.getTextureId(presentationTimeUs), 1);
                flat.setFloatUniform("uBlendMode", mode);
                flat.setFloatsUniform("uKeyColor", keyColor);
                flat.setFloatsUniform("uKeyParams", keyParams);
                setFxUniformsOn(flat, presentationTimeUs);
                flat.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        /** Mask uniforms for the mesh program (frame-space, same field as preview). No-op when unmasked. */
        private void uploadMeshMask(long timelineMs) {
            try {
                com.fadcam.ui.faditor.model.CompositingSpec cs = fxItem.getCompositing();
                boolean on = cs != null && !cs.masks.isEmpty();
                setFloatGuarded(glProgram, "uMeshMaskOn", on ? 1f : 0f);
                if (!on) return;
                setFloatGuarded(glProgram, "uMeshMaskInvert",
                        (cs != null && cs.invertMasks) ? 1f : 0f);
                setF2Guarded(glProgram, "uMeshTexel",
                        1f / Math.max(1, meshFrameW), 1f / Math.max(1, meshFrameH));
            } catch (Exception ignored) { }
        }

        /** Mask arrays AFTER bind (GlProgram cannot express array uniforms — same workaround as AdjustmentLayer). */
        private void uploadMeshMaskArrays(long timelineMs) {
            try {
                // Resolved at this frame, like the preview and the Canvas export: mask keys
                // animate, and a mask linked to the picture ("Move with the object") follows it.
                com.fadcam.ui.faditor.model.CompositingSpec cs =
                        fxItem.compositingAt(timelineMs, meshFrameW, meshFrameH);
                if (cs == null || cs.masks.isEmpty()) return;
                float[] geo = com.fadcam.ui.faditor.model.MaskSdf.packShapes(
                        cs, meshFrameW, meshFrameH);
                int shapes = Math.max(1, Math.min(meshMaskShapes,
                        geo.length / com.fadcam.ui.faditor.model.MaskSdf.FLOATS_PER_SHAPE));
                int[] ops = com.fadcam.ui.faditor.model.MaskSdf.packOps(cs);
                float[] g4 = new float[shapes * 4];
                float[] r2 = new float[shapes * 2];
                float[] co = new float[shapes];
                float[] fe = new float[shapes];
                float[] op = new float[shapes];
                for (int i = 0; i < shapes; i++) {
                    int o = i * com.fadcam.ui.faditor.model.MaskSdf.FLOATS_PER_SHAPE;
                    g4[i * 4] = geo[o]; g4[i * 4 + 1] = geo[o + 1];
                    g4[i * 4 + 2] = geo[o + 2]; g4[i * 4 + 3] = geo[o + 3];
                    r2[i * 2] = geo[o + 4]; r2[i * 2 + 1] = geo[o + 5];
                    co[i] = geo[o + 6]; fe[i] = geo[o + 7];
                    op[i] = i < ops.length ? ops[i] : 0f;
                }
                int n = Math.max(1, shapes);
                try {
                    GLES20.glUniform4fv(glProgram.getUniformLocation("uMeshMaskGeo"), n, g4, 0);
                } catch (RuntimeException ignored) { }
                try {
                    GLES20.glUniform2fv(glProgram.getUniformLocation("uMeshMaskRot"), n, r2, 0);
                } catch (RuntimeException ignored) { }
                try {
                    GLES20.glUniform1fv(glProgram.getUniformLocation("uMeshMaskCorner"), n, co, 0);
                } catch (RuntimeException ignored) { }
                try {
                    GLES20.glUniform1fv(glProgram.getUniformLocation("uMeshMaskFeather"), n, fe, 0);
                } catch (RuntimeException ignored) { }
                try {
                    GLES20.glUniform1fv(glProgram.getUniformLocation("uMeshMaskOp"), n, op, 0);
                } catch (RuntimeException ignored) { }
            } catch (Exception ignored) { }
        }

        private void setFloatGuarded(@NonNull GlProgram p, @NonNull String name, float v) {
            try {
                p.setFloatUniform(name, v);
            } catch (RuntimeException ignored) {
                // Stripped by the driver; harmless (same guard as setFxUniforms).
            }
        }

        private void setF2Guarded(@NonNull GlProgram p, @NonNull String name, float a, float b) {
            try {
                p.setFloatsUniform(name, new float[]{a, b});
            } catch (RuntimeException ignored) { }
        }

        /**
         * Upload this image's FX parameters.
         *
         * <p>Each set is GUARDED: an unused uniform is stripped by the driver and media3 looks
         * names up in the LINKED program, so setting one it removed throws. That cost a whole
         * export cycle to find on the adjustment layer and again on the PiP; it does not get to
         * cost a third.</p>
         *
         * <p>Silent no-op with no FX, because then the shader is the original string and none of
         * these names exist at all.</p>
         */
        private void setFxUniforms(long presentationTimeUs) {
            setFxUniformsOn(glProgram, presentationTimeUs);
        }

        /** Same upload onto an explicit program (mesh composite vs flat fallback share it). */
        private void setFxUniformsOn(@NonNull GlProgram target, long presentationTimeUs) {
            com.fadcam.ui.faditor.fx.FxStack stack = fxItem.getFx();
            if (stack == null || stack.active().isEmpty()) return;
            com.fadcam.ui.faditor.fx.FxStack resolved = stack.resolveAt(presentationTimeUs / 1000L);
            com.fadcam.ui.faditor.fx.FxCompiler.Plan plan =
                    com.fadcam.ui.faditor.fx.FxCompiler.plan(resolved);
            for (com.fadcam.ui.faditor.fx.FxCompiler.Pass p : plan.passes) {
                if (p.sampler) continue;   // not compiled into this shader — see fragmentFor
                for (com.fadcam.ui.faditor.fx.FxUniforms.Value v
                        : com.fadcam.ui.faditor.fx.FxUniforms.forPass(p)) {
                    try {
                        if (v.components() == 1) target.setFloatUniform(v.name, v.data[0]);
                        else target.setFloatsUniform(v.name, v.data);
                    } catch (RuntimeException ignored) {
                        // Stripped by the driver; harmless.
                    }
                }
                break;   // only the first fused pass is in this shader
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            try {
                overlay.release();
                try {
                    if (meshStamp != null) meshStamp.release();
                } catch (Exception ignored) { }
                if (meshSource != null && !meshSource.isRecycled()) {
                    try { meshSource.recycle(); } catch (Exception ignored) { }
                }
                meshSource = null;
                try {
                    if (flatProgram != null) flatProgram.delete();
                } catch (Exception ignored) { }
                glProgram.delete();
                super.release();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
