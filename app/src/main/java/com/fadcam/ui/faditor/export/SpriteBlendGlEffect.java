package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.List;

/**
 * SPEC ZA — a warped sprite exports through GL, like the preview now does.
 *
 * <p>Mirrors {@link ImageBlendGlEffect} in shape deliberately: one effect per item holding the
 * item plus the timeline duration and a time offset, a {@code Program} that samples the video
 * texture and stamps the item over it, an {@code installMeshCurve}/{@code getMesh} refusal of a
 * null/identity spec with a flat fallback, and the stamp through the SHARED
 * {@code MeshStampGl} — which takes a texture and does not know or care what the texture holds,
 * so it serves sprites unchanged. There is no second warp anywhere in this file.
 *
 * <p><b>The texture.</b> The image effect decodes its own bitmap; a sprite's pixels come from
 * its sheet. Whatever the sprite SHOWS at that instant — a sheet cell via
 * {@code SpriteSheetRenderer.drawCell} (the same call {@code CompositeExportOverlay}'s sprite
 * block makes) or a rig performance via {@code AvatarItemPuppet.draw} — is rasterised into one
 * bitmap and that picture is stamped. Rasterise-then-warp is the design, not a convenience:
 * it is what makes the bend a property of the SPRITE rather than of a cell, so a cell swap or
 * a rig pose change cannot change it (the same reason {@code SpriteMeshDraw} gives for the
 * Canvas fallback, which stays exactly where it is for anything GL cannot take).
 *
 * <p><b>Two composites, one formula.</b> A sprite carries no blend, key, FX or mask channel
 * (see {@code SpriteOverlayItem.wantsGl}, which lists exactly what would extend it), so both
 * fragments are straight SRC_OVER — the same equation {@code TextFxGlEffect} composites with.
 * They differ only in orientation, for the reason {@code ImageBlendGlEffect}'s mesh-branch doc
 * states: a CPU bitmap uploads top-row-first and is sampled V-flipped; a stamp FBO is bottom-up
 * like every other GL target and is sampled unflipped. Sampling either one the wrong way turns
 * the sprite upside-down.
 *
 * <p><b>Routing.</b> Emitted only for a sprite whose {@code wantsGl()} is true, and
 * {@code CompositeExportOverlay.filterSpriteItems} drops exactly those from the Canvas pass —
 * the two decisions consult the SAME predicate, so a sprite is drawn once, in GL, or once, on
 * the Canvas, never twice and never nowhere. A sprite with no pin and no mesh takes neither
 * new path: no effect is emitted and the Canvas draw is untouched, so its export is
 * byte-identical.
 *
 * <p><b>Z-order.</b> Chain position is paint order. This effect is emitted in the merged
 * bottom-to-top overlay pass (see the emission site), so a blend above a bent sprite samples
 * the accumulated frame WITH the sprite already in it — which is the whole point: on the
 * Canvas the sprite sat over the GL surface and no blend above it could ever see it.
 */
final class SpriteBlendGlEffect implements GlEffect {

    @NonNull private final Context context;
    @NonNull private final SpriteOverlayItem item;
    @NonNull private final List<com.fadcam.ui.faditor.sprite.SpriteSheet> sheets;
    @NonNull private final List<com.fadcam.ui.faditor.avatar.AvatarRig> rigs;
    private final long projectDurationMs;
    private final long editorTimeOffsetMs;

    SpriteBlendGlEffect(@NonNull Context context, @NonNull SpriteOverlayItem item,
                        @NonNull List<com.fadcam.ui.faditor.sprite.SpriteSheet> sheets,
                        @NonNull List<com.fadcam.ui.faditor.avatar.AvatarRig> rigs,
                        long projectDurationMs, long editorTimeOffsetMs) {
        this.context = context.getApplicationContext();
        this.item = item;
        this.sheets = sheets;
        this.rigs = rigs;
        this.projectDurationMs = projectDurationMs;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) {
            throw new VideoFrameProcessingException("HDR sprite overlays are not supported");
        }
        return new Program(context, item, sheets, rigs, projectDurationMs, editorTimeOffsetMs);
    }

    /**
     * The sprite's full-frame picture as a texture — the export twin of what the Canvas pass
     * paints. Placement, rotation, mirror, corner pin and content all come through the SAME
     * model authorities and the SAME draw calls {@code CompositeExportOverlay}'s sprite block
     * uses ({@code animated*}, {@code cornerPinMatrix}, {@code drawCell} /
     * {@code AvatarItemPuppet.draw}), so the flat path cannot disagree with the path it
     * replaces about where the sprite is or what it shows. Only the destination differs: an
     * offscreen that is then composited inside the chain instead of painted over it.
     *
     * <p>A NEW bitmap every frame: {@code BitmapOverlay} caches the GL texture on bitmap
     * IDENTITY, so returning a reused scratch would freeze frame one forever — the trap
     * {@code TextFxGlEffect.TextFrame} and {@code PipFrameOverlay} both document.
     */
    private static final class SpriteFrame extends BitmapOverlay {
        @NonNull private final Context appContext;
        @NonNull private final SpriteOverlayItem item;
        @NonNull private final List<com.fadcam.ui.faditor.sprite.SpriteSheet> sheets;
        @NonNull private final List<com.fadcam.ui.faditor.avatar.AvatarRig> rigs;
        private final long editorTimeOffsetMs;
        private int frameW = 1, frameH = 1;
        private final java.util.Map<String,
                com.fadcam.ui.faditor.sprite.SpriteSheetRenderer> renderers =
                new java.util.HashMap<>();
        @Nullable private com.fadcam.ui.faditor.avatar.AvatarItemPuppet puppet;
        private boolean puppetTried;
        private final Paint paint =
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix pinMatrix = new android.graphics.Matrix();

        SpriteFrame(@NonNull Context appContext, @NonNull SpriteOverlayItem item,
                    @NonNull List<com.fadcam.ui.faditor.sprite.SpriteSheet> sheets,
                    @NonNull List<com.fadcam.ui.faditor.avatar.AvatarRig> rigs,
                    long editorTimeOffsetMs) {
            this.appContext = appContext;
            this.item = item;
            this.sheets = sheets;
            this.rigs = rigs;
            this.editorTimeOffsetMs = editorTimeOffsetMs;
        }

        @Override
        public void configure(@NonNull Size videoSize) {
            frameW = Math.max(1, videoSize.getWidth());
            frameH = Math.max(1, videoSize.getHeight());
        }

        @Nullable
        private com.fadcam.ui.faditor.sprite.SpriteSheet sheetById(@NonNull String id) {
            for (com.fadcam.ui.faditor.sprite.SpriteSheet s : sheets) {
                if (s.getId().equals(id)) return s;
            }
            return null;
        }

        @Nullable
        private com.fadcam.ui.faditor.sprite.SpriteSheetRenderer rendererFor(
                @NonNull String sheetId) {
            if (renderers.containsKey(sheetId)) return renderers.get(sheetId);
            com.fadcam.ui.faditor.sprite.SpriteSheet sheet = sheetById(sheetId);
            com.fadcam.ui.faditor.sprite.SpriteSheetRenderer r = sheet != null
                    ? com.fadcam.ui.faditor.sprite.SpriteSheetRenderer.load(appContext, sheet,
                            Math.max(256, Math.max(frameW, frameH)))
                    : null;
            renderers.put(sheetId, r);
            return r;
        }

        @Nullable
        private com.fadcam.ui.faditor.avatar.AvatarItemPuppet puppetFor() {
            if (puppetTried) return puppet;
            puppetTried = true;
            if (!item.hasAvatarPerformance()) return null;
            com.fadcam.ui.faditor.avatar.AvatarRig rig = null;
            for (com.fadcam.ui.faditor.avatar.AvatarRig r : rigs) {
                if (r.getId().equals(item.getAvatarRigId())) { rig = r; break; }
            }
            puppet = com.fadcam.ui.faditor.avatar.AvatarItemPuppet.forItem(
                    appContext, item, rig, this::rendererFor, this::sheetById);
            return puppet;
        }

        @NonNull
        @Override
        public Bitmap getBitmap(long presentationTimeUs) {
            long timelineMs = presentationTimeUs / 1000L + editorTimeOffsetMs;
            Bitmap full = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(full);
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
            try {
                if (item.isHidden() || !item.isVisibleAt(timelineMs)) return full;
                float opacity = item.animatedOpacity(timelineMs);
                if (!(opacity > 0.001f)) return full;
                com.fadcam.ui.faditor.sprite.SpriteSheet sheet = sheetById(item.getSheetId());
                com.fadcam.ui.faditor.sprite.SpriteSheetRenderer r =
                        sheet != null ? rendererFor(item.getSheetId()) : null;
                com.fadcam.ui.faditor.avatar.AvatarItemPuppet p = puppetFor();
                int cell = com.fadcam.ui.faditor.sprite.SpriteFrameResolver.NO_CELL;
                if (sheet != null && r != null) {
                    cell = com.fadcam.ui.faditor.sprite.SpriteFrameResolver.resolveCellAt(
                            sheet, item, timelineMs);
                }
                // Missing art or an empty moment: the export omits, exactly as the Canvas
                // pass does (it continues past sheets it cannot load and cells it cannot
                // resolve). The preview's placeholder is a preview affordance, not a pixel.
                if (p == null && (sheet == null || r == null
                        || cell == com.fadcam.ui.faditor.sprite.SpriteFrameResolver.NO_CELL)) {
                    return full;
                }
                float cx = item.animatedCenterX(timelineMs) * frameW;
                float cy = item.animatedCenterY(timelineMs) * frameH;
                float h = item.animatedSizeFraction(timelineMs) * frameH;
                float aspect = r != null ? r.cellAspect() : 1f;
                float w = h * (aspect > 0f ? aspect : 1f);
                if (!(w > 0f) || !(h > 0f)) return full;
                paint.setAlpha(Math.round(Math.max(0f, Math.min(1f, opacity)) * 255));
                canvas.save();
                canvas.rotate(item.animatedRotation(timelineMs), cx, cy);
                if (item.isFlipH() || item.isFlipV()) {
                    canvas.scale(item.isFlipH() ? -1f : 1f,
                            item.isFlipV() ? -1f : 1f, cx, cy);
                }
                RectF dest = new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
                // The pin, from the ONE method both Canvas surfaces call, concat-ed at the
                // SAME point: inside rotate and flip, immediately around the content. A
                // pin-only sprite therefore exports pinned even though the stamp below
                // refuses it (the stamp needs a mesh); the warp and the pin never share
                // an implementation, so neither can drift from the other.
                if (item.cornerPinMatrix(pinMatrix, timelineMs,
                        dest.left, dest.top, dest.width(), dest.height())) {
                    canvas.concat(pinMatrix);
                }
                final int fCell = cell;
                final com.fadcam.ui.faditor.avatar.AvatarItemPuppet fPuppet = p;
                final float fOpacity = opacity;
                final com.fadcam.ui.faditor.sprite.SpriteSheetRenderer fr = r;
                if (fPuppet != null && item.getAvatarTrack() != null) {
                    fPuppet.draw(canvas, dest, item.getAvatarTrack(),
                            Math.max(0, item.toLocalMs(timelineMs)), fOpacity);
                } else if (fr != null
                        && fCell != com.fadcam.ui.faditor.sprite.SpriteFrameResolver.NO_CELL) {
                    fr.drawCell(canvas, fCell, dest, paint);
                }
                canvas.restore();
            } catch (Throwable t) {
                // A sprite never costs the frame: transparent is recoverable, a throw is not.
                try {
                    canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                } catch (Throwable ignored) { }
                FLog.w("SpriteBlend", "sprite frame bake failed; drawing empty", t);
            }
            return full;
        }

        @Override
        public void release() {
            try {
                if (puppet != null) {
                    try { puppet.release(); } catch (Exception ignored) { }
                    puppet = null;
                }
                for (com.fadcam.ui.faditor.sprite.SpriteSheetRenderer r : renderers.values()) {
                    if (r != null) {
                        try { r.recycle(); } catch (Exception ignored) { }
                    }
                }
                renderers.clear();
            } catch (Exception ignored) { }
            try {
                super.release();
            } catch (Exception ignored) { }
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

        /**
         * Composite the CPU-baked sprite over the frame. Straight SRC_OVER — a sprite
         * carries no blend channel — with the overlay sampled V-FLIPPED, for the reason
         * {@code TextFxGlEffect} documents: overlay UVs are Y-up and an unflipped sample
         * composites every overlay upside-down.
         */
        private static final String FRAGMENT_FLAT =
                "#version 100\n"
                + "precision mediump float;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "uniform sampler2D uVideoTexSampler0;\n"
                + "uniform sampler2D uOverlayTexSampler0;\n"
                + "void main() {\n"
                + "  vec4 base = texture2D(uVideoTexSampler0, vTexSamplingCoord);\n"
                + "  vec2 ovc = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);\n"
                + "  vec4 src = texture2D(uOverlayTexSampler0, ovc);\n"
                + "  vec3 sc = src.rgb / max(src.a, 0.001);\n"
                + "  float a = src.a;\n"
                + "  gl_FragColor = vec4(mix(base.rgb, clamp(sc, 0.0, 1.0), a), base.a);\n"
                + "}\n";

        /**
         * Composite the mesh stamp over the frame. Same equation as {@link #FRAGMENT_FLAT};
         * NO V-flip — a stamp FBO is bottom-up like every other GL target in both renderers,
         * the same reason the image mesh branch drops the flip. Sampling a stamp flipped
         * would composite it upside-down.
         */
        private static final String FRAGMENT_MESH =
                "#version 100\n"
                + "precision mediump float;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "uniform sampler2D uVideoTexSampler0;\n"
                + "uniform sampler2D uOverlayTexSampler0;\n"
                + "void main() {\n"
                + "  vec4 base = texture2D(uVideoTexSampler0, vTexSamplingCoord);\n"
                + "  vec4 src = texture2D(uOverlayTexSampler0, vTexSamplingCoord);\n"
                + "  vec3 sc = src.rgb / max(src.a, 0.001);\n"
                + "  float a = src.a;\n"
                + "  gl_FragColor = vec4(mix(base.rgb, clamp(sc, 0.0, 1.0), a), base.a);\n"
                + "}\n";

        /** A huge sprite must not allocate a huge bitmap — same ceiling as SpriteMeshDraw. */
        private static final int MAX_EDGE_PX = 2048;

        @NonNull private final Context appContext;
        @NonNull private final SpriteOverlayItem item;
        private final SpriteFrame frame;
        private final long projectDurationMs;
        private final long editorTimeOffsetMs;
        /** True when this effect was built for a bent sprite (fixed at export start). */
        private final boolean mesh;
        /** Shared stamp (own FBO on THIS effect thread; lazy GL init inside). Null when flat. */
        @Nullable private final com.fadcam.ui.faditor.compositor.MeshStampGl meshStamp;
        /** Flat program (correct flip for the bitmap) — mesh effects hold both. */
        @Nullable private final GlProgram flatProgram;
        private final GlProgram glProgram;
        @Nullable private GlProgram passProgram;
        private int frameW = 1, frameH = 1;
        private final Paint contentPaint =
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        Program(@NonNull Context context, @NonNull SpriteOverlayItem item,
                @NonNull List<com.fadcam.ui.faditor.sprite.SpriteSheet> sheets,
                @NonNull List<com.fadcam.ui.faditor.avatar.AvatarRig> rigs,
                long projectDurationMs, long editorTimeOffsetMs)
                throws VideoFrameProcessingException {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.appContext = context.getApplicationContext();
            this.item = item;
            this.frame = new SpriteFrame(appContext, item, sheets, rigs, editorTimeOffsetMs);
            this.projectDurationMs = projectDurationMs;
            this.editorTimeOffsetMs = editorTimeOffsetMs;
            boolean wantMesh = false;
            com.fadcam.ui.faditor.compositor.MeshStampGl stamp = null;
            try {
                item.installMeshCurve();
                wantMesh = item.hasMesh();
                if (wantMesh) stamp = new com.fadcam.ui.faditor.compositor.MeshStampGl();
            } catch (Exception ignored) {
                wantMesh = false;
                stamp = null;
            }
            this.mesh = wantMesh;
            this.meshStamp = stamp;
            GlProgram flat = null;
            String fragment;
            if (mesh) {
                // Flat fallback program (correct flip for the bitmap) — compiled alongside
                // so identity/degraded/transparent frames draw the pin-baked picture, never
                // vanishing. If even the flat string refuses, the constructor below throws
                // loudly, same as every other effect.
                fragment = FRAGMENT_MESH;
                try {
                    flat = new GlProgram(VERTEX_SHADER, FRAGMENT_FLAT);
                    flat.setBufferAttribute("aFramePosition",
                            GlUtil.getNormalizedCoordinateBounds(), 4);
                } catch (Exception ignored) {
                    flat = null;
                }
            } else {
                fragment = FRAGMENT_FLAT;
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
            frame.configure(size);
            frameW = size.getWidth();
            frameH = size.getHeight();
            if (meshStamp != null) meshStamp.configure(frameW, frameH);
            return size;
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            try {
                long timelineMs = presentationTimeUs / 1000L + editorTimeOffsetMs;
                // Gate on visibility here, not in the chain: this effect is emitted once per
                // clip and self-gates per frame, the way TextFxGlEffect does.
                if (item.isHidden() || !item.isVisibleAt(timelineMs)) {
                    passthrough(inputTexId);
                    return;
                }
                if (mesh && meshStamp != null && flatProgram != null) {
                    drawMeshFrame(inputTexId, presentationTimeUs, timelineMs);
                    return;
                }
                drawFlat(inputTexId, presentationTimeUs);
            } catch (VideoFrameProcessingException e) {
                throw e;
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        /**
         * Stamp the bend through the shared wrapper and composite the stamp. Identity,
         * degraded, undecoded and transparent all degrade INSIDE to the pin-baked flat
         * picture — a bent sprite drawn with its pin for a frame is recoverable, a
         * vanished sprite is not.
         *
         * <p>Numbers mirror the preview's {@code spritePip} through the SAME model
         * authorities, and the stamp builds its own matrices inside via
         * {@code MeshPlacement}, so preview and export cannot transcribe them
         * differently. A sprite has no stored rotation pivot, so the fold is about the
         * centre (0,0,false); no caption preset (identity scale, no offset); no wipe
         * (reveal 1). Aspect comes from the rasterised content — the exact pixels the
         * stamp samples — the same rule the image path states for itself.
         */
        private void drawMeshFrame(int inputTexId, long presentationTimeUs, long timelineMs)
                throws VideoFrameProcessingException {
            Bitmap content = null;
            try {
                float alpha = Math.max(0f, Math.min(1f, item.animatedOpacity(timelineMs)));
                if (!(alpha > 0.001f)) {
                    drawFlat(inputTexId, presentationTimeUs);
                    return;
                }
                content = rasterContent(timelineMs, alpha);
                if (content == null) {
                    drawFlat(inputTexId, presentationTimeUs);
                    return;
                }
                float hNorm = item.animatedSizeFraction(timelineMs);
                float aspect = content.getWidth() / (float) Math.max(1, content.getHeight());
                float frameAspect = frameW / (float) Math.max(1, frameH);
                if (!(hNorm > 0f) || !(aspect > 0f) || !(frameAspect > 0f)) {
                    drawFlat(inputTexId, presentationTimeUs);
                    return;
                }
                float wNorm = hNorm * aspect / frameAspect;
                float cx = item.animatedCenterX(timelineMs);
                float cy = item.animatedCenterY(timelineMs);
                float rot = item.animatedRotation(timelineMs);
                float[] pins = null;
                if (item.hasCornerPin()) {
                    pins = new float[com.fadcam.ui.faditor.model.CornerPin.SIZE];
                    item.animatedCornerPin(timelineMs, pins);
                }
                com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec spec;
                long localMs;
                try {
                    item.installMeshCurve();
                    com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec src = item.getMesh();
                    if (src == null || !src.hasWarp()) {
                        drawFlat(inputTexId, presentationTimeUs);
                        return;
                    }
                    spec = src.copy();
                    localMs = item.meshLocalTime(timelineMs);
                } catch (Exception ignored) {
                    drawFlat(inputTexId, presentationTimeUs);
                    return;
                }
                meshStamp.configure(frameW, frameH);
                int stampTex;
                try {
                    stampTex = meshStamp.renderToStamp(content, spec, localMs,
                            cx, cy, wNorm, hNorm,
                            0f, 0f, false, rot,
                            1f, 1f, 0f, 0f,
                            alpha, 1f, pins,
                            item.isFlipH() ? -1f : 1f, item.isFlipV() ? -1f : 1f);
                } catch (Exception e) {
                    FLog.w("SpriteBlend", "stamp failed; drawing flat", e);
                    stampTex = 0;
                }
                if (stampTex == 0) {
                    drawFlat(inputTexId, presentationTimeUs);
                    return;
                }
                try {
                    glProgram.use();
                    glProgram.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, 0);
                    glProgram.setSamplerTexIdUniform("uOverlayTexSampler0", stampTex, 1);
                    glProgram.bindAttributesAndUniforms();
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    GlUtil.checkGlError();
                } catch (Exception e) {
                    throw new VideoFrameProcessingException(e);
                }
            } finally {
                if (content != null && !content.isRecycled()) {
                    try { content.recycle(); } catch (Exception ignored) { }
                }
            }
        }

        /**
         * Whatever the sprite SHOWS at {@code timelineMs} — a rig's composed parts, a sheet
         * cell — rasterised tight at its drawn size, at full opacity (the stamp bakes the
         * alpha, so baking it here too would multiply it twice). Null when there is nothing
         * to stamp: missing art, an unresolvable cell, a degenerate placement.
         */
        @Nullable
        private Bitmap rasterContent(long timelineMs, float alpha) {
            try {
                com.fadcam.ui.faditor.sprite.SpriteSheet sheet = null;
                for (com.fadcam.ui.faditor.sprite.SpriteSheet s : frame.sheets) {
                    if (s.getId().equals(item.getSheetId())) { sheet = s; break; }
                }
                com.fadcam.ui.faditor.sprite.SpriteSheetRenderer r =
                        sheet != null ? frame.rendererFor(item.getSheetId()) : null;
                com.fadcam.ui.faditor.avatar.AvatarItemPuppet p = frame.puppetFor();
                int cell = com.fadcam.ui.faditor.sprite.SpriteFrameResolver.NO_CELL;
                if (sheet != null && r != null) {
                    cell = com.fadcam.ui.faditor.sprite.SpriteFrameResolver.resolveCellAt(
                            sheet, item, timelineMs);
                }
                if (p == null && (sheet == null || r == null
                        || cell == com.fadcam.ui.faditor.sprite.SpriteFrameResolver.NO_CELL)) {
                    return null;
                }
                float aspect = r != null ? r.cellAspect() : 1f;
                if (!(aspect > 0f)) aspect = 1f;
                float h = Math.max(1f, item.animatedSizeFraction(timelineMs) * frameH);
                float w = h * aspect;
                int bw = Math.max(1, Math.min(MAX_EDGE_PX, Math.round(w)));
                int bh = Math.max(1, Math.min(MAX_EDGE_PX, Math.round(h)));
                if (bw <= 0 || bh <= 0) return null;
                Bitmap bmp;
                try {
                    bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                } catch (OutOfMemoryError e) {
                    FLog.w("SpriteBlend", "content raster OOM; drawing flat", e);
                    return null;
                }
                Canvas c = new Canvas(bmp);
                c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                RectF into = new RectF(0f, 0f, bw, bh);
                if (p != null && item.getAvatarTrack() != null) {
                    p.draw(c, into, item.getAvatarTrack(),
                            Math.max(0, item.toLocalMs(timelineMs)), 1f);
                } else if (r != null
                        && cell != com.fadcam.ui.faditor.sprite.SpriteFrameResolver.NO_CELL) {
                    contentPaint.setAlpha(255);
                    r.drawCell(c, cell, into, contentPaint);
                } else {
                    bmp.recycle();
                    return null;
                }
                if (alpha <= 0f) {
                    bmp.recycle();
                    return null;
                }
                return bmp;
            } catch (Throwable t) {
                FLog.w("SpriteBlend", "content raster failed; drawing flat", t);
                return null;
            }
        }

        /** Composite the CPU-baked frame (correct bitmap flip). */
        private void drawFlat(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            GlProgram flat = flatProgram != null ? flatProgram : glProgram;
            try {
                flat.use();
                flat.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, 0);
                flat.setSamplerTexIdUniform("uOverlayTexSampler0",
                        frame.getTextureId(presentationTimeUs), 1);
                flat.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

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
            try {
                try {
                    frame.release();
                } catch (Exception ignored) { }
                try {
                    if (meshStamp != null) meshStamp.release();
                } catch (Exception ignored) { }
                try {
                    if (flatProgram != null) flatProgram.delete();
                } catch (Exception ignored) { }
                try {
                    if (passProgram != null) passProgram.delete();
                } catch (Exception ignored) { }
                glProgram.delete();
                super.release();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
