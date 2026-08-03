package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.StaticOverlaySettings;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.WaveformData;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.model.WaveformStyle;
import com.fadcam.ui.faditor.overlay.TextOverlayRenderer;
import com.fadcam.ui.faditor.transcript.CaptionStyle;
import com.fadcam.ui.faditor.transcript.Transcript;
import com.fadcam.ui.faditor.waveform.WaveformStyleIO;
import com.fadcam.ui.faditor.waveform.WaveformStyleRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders all timeline overlays (text, captions, waveform visualizers) into a
 * single per-frame bitmap via {@link BitmapOverlay}. One instance per video
 * clip, with that clip's cumulative start offset so timelines times resolve.
 */
public class CompositeExportOverlay extends BitmapOverlay {

    private final Context context;
    private final long clipTimelineStartMs;
    private final Clip clip;
    private final int outW;
    private final int outH;
    // Captions for THIS video clip. The renderer is (re)built per effective style:
    // caption-style keyframes can change the style — or hide captions ("hidden")
    // partway through a clip, and the export must mirror the live preview exactly.
    @Nullable private final Transcript captionTranscript;   // windowed to clip in/out, or null
    private final float captionCenterX, captionCenterY, captionSizeFraction;
    @Nullable private CaptionExportRenderer captionRenderer;
    @Nullable private String captionRendererStyleId;
    private final List<AudioCaptionSlot> audioCaptionSlots;
    private final List<TextOverlayItem> textOverlays;
    private final List<WaveformSlot> waveformSlots;
    private final WaveformStyleRenderer waveRenderer;

    /**
     * Absolute timeline end (ms) for this overlay instance. For clips with loop/ping-pong
     * extensions this is {@code clipTimelineStartMs + clip.getVisualDurationMs()} so that
     * text overlays scheduled inside loop extension regions are not prematurely filtered out.
     * The render loop still evaluates each overlay's own time range via
     * {@link TextOverlayItem#isVisibleAt(long)}, so using the full visual duration here is a
     * safe upper bound.
     */
    private final long clipVisualEndMs;
    /**
     * SPEC_TIMER_OBJECT: total project duration — a timer's ABSOLUTE basis counts against
     * it, and it is also the fallback out-point for an untrimmed (endMs == MAX_VALUE) tape.
     * A constructor parameter rather than a setter on purpose: a forgotten setter would
     * leave export computing timers against 0 while the preview used the real duration,
     * which is precisely the preview/export divergence this feature must not have.
     */
    private final long projectDurationMs;

    /**
     * Composition-time → editor-time correction for this clip (LEDGER §2d): the cumulative
     * transition duration preceding it, i.e. {@code editorClipStart - compressedClipStart}.
     * <b>Zero whenever the project has no transitions</b>, which is what makes this change a
     * provable no-op for those projects rather than a behavioural risk to every export.
     */
    private final long editorTimeOffsetMs;

    private Bitmap bitmap;
    private Canvas canvas;

    /** Per-overlay decoded PNG frame reuse: overlayId → (frameIndex, bitmap). */
    private final java.util.Map<String, android.util.Pair<Integer, Bitmap>>
            generatedOverlayFrames = new java.util.HashMap<>();

    /**
     * Decoded frame of an AI-authored overlay slide at a timeline position.
     * The sequence was baked with the stretch mapping applied, so the index is
     * a straight overlay-local time lookup. Caches the last decoded frame per
     * overlay — consecutive export frames usually hit the same PNG or its
     * neighbor.
     */
    @Nullable
    /**
     * Decoded image-overlay bitmaps, keyed by overlay id, decoded once and reused for every frame.
     *
     * <p>A null VALUE is a cached failure: an overlay whose URI cannot be decoded must not be
     * re-attempted 900 times, and must not log 900 times either. Presence of the key is the
     * "already tried" flag, so {@code containsKey} — not {@code get() != null} — is the test.</p>
     */
    private final java.util.Map<String, Bitmap> imageOverlayBitmaps = new java.util.HashMap<>();

    /**
     * The bitmap for an image overlay, decoded on first use and cached for the clip's lifetime.
     *
     * <p>Downsampled so the decode is bounded by the OUTPUT frame rather than by the source file:
     * a 12-megapixel photo dropped on a 480p export would otherwise be held at full size for every
     * frame of the clip. The bound is the frame's larger dimension, so an overlay scaled up to fill
     * the frame still has pixels to spare.</p>
     *
     * @return the bitmap, or null if it could not be decoded (logged once per overlay).
     */
    @Nullable
    private Bitmap imageOverlayBitmap(@NonNull TextOverlayItem o) {
        String key = o.getId();
        if (imageOverlayBitmaps.containsKey(key)) {
            Bitmap cached = imageOverlayBitmaps.get(key);
            return (cached != null && !cached.isRecycled()) ? cached : null;
        }
        Bitmap out = null;
        String uriStr = o.getImageUri();
        try {
            android.net.Uri uri = android.net.Uri.parse(uriStr);
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            int maxEdge = Math.max(outW, outH);
            int sample = 1;
            while (bounds.outHeight / (sample * 2) >= maxEdge
                    && bounds.outWidth / (sample * 2) >= 1) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options opts =
                    new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                out = android.graphics.BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Throwable t) {
            FLog.w(TAG, "image overlay " + key + " could not be decoded from " + uriStr
                    + " — it will be absent from the exported file", t);
        }
        if (out == null) {
            FLog.w(TAG, "image overlay " + key + " decoded to null from " + uriStr
                    + " — it will be absent from the exported file");
        }
        imageOverlayBitmaps.put(key, out);
        return out;
    }

    private Bitmap generatedOverlayFrame(@NonNull TextOverlayItem o, long timelineMs) {
        com.fadcam.ui.faditor.model.GeneratedSource gs = o.getGeneratedSource();
        if (gs == null || gs.renderSequenceDir == null) return null;
        String dirPath = android.net.Uri.parse(gs.renderSequenceDir).getPath();
        if (dirPath == null) return null;
        long local = Math.max(0, timelineMs - o.getStartMs());
        int idx = (int) Math.round((local / 1000.0)
                * com.fadcam.ui.faditor.slides.SlideRenderer.RENDER_FPS);
        java.io.File f;
        while (idx >= 0) {
            f = new java.io.File(dirPath, String.format(java.util.Locale.US,
                    "frame%04d.png", idx));
            if (f.isFile()) break;
            idx--; // clamp onto the last existing frame (held final state)
        }
        if (idx < 0) return null;
        android.util.Pair<Integer, Bitmap> cached = generatedOverlayFrames.get(o.getId());
        if (cached != null && cached.first == idx && cached.second != null
                && !cached.second.isRecycled()) {
            return cached.second;
        }
        Bitmap bmp = android.graphics.BitmapFactory.decodeFile(
                new java.io.File(dirPath, String.format(java.util.Locale.US,
                        "frame%04d.png", idx)).getAbsolutePath());
        if (cached != null && cached.second != null && !cached.second.isRecycled()) {
            cached.second.recycle();
        }
        if (bmp != null) {
            generatedOverlayFrames.put(o.getId(), new android.util.Pair<>(idx, bmp));
        }
        return bmp;
    }

    // BitmapOverlay keeps the most recently returned bitmap to detect generation
    // id changes and re-upload the texture. We hold the two previous returned
    // bitmaps in a small ring and recycle the oldest one at the start of each
    // new frame. A two-frame delay is safe even on Adreno drivers that keep a
    // short-lived internal reference to the Java bitmap after upload.
    @Nullable private Bitmap lastReturnedBitmap;
    @Nullable private Bitmap pendingRecycleBitmap;

    public static class WaveformSlot {
        @NonNull public final WaveformOverlayInstance instance;
        @NonNull public final WaveformData data;
        @NonNull public final WaveformStyle style;
        public final int posX, posY, slotW, slotH;
        public final float density;
        /** G5(b) piggyback-looks: an ATTACHED rider's host clip (null = detached or
         *  host gone → full opacity) + the host's absolute timeline start, so the
         *  draw loop can mirror the host's opacity envelope onto the visualizer.
         *  Mutable post-construction: the slot builder fills them when it has the
         *  timeline in hand. */
        @Nullable public com.fadcam.ui.faditor.model.Clip hostClip;
        public long hostStartMs;

        public WaveformSlot(@NonNull WaveformOverlayInstance instance, @NonNull WaveformData data,
                            @NonNull WaveformStyle style, int outW, int outH, float density) {
            this.instance = instance;
            this.data = data;
            this.style = style;
            this.density = density;
            int w = Math.max(1, Math.round(outW * instance.getWidthFraction()));
            int h = Math.max(1, Math.round(outH * instance.getHeightFraction()));
            this.slotW = w;
            this.slotH = h;
            this.posX = Math.round(outW * instance.getCenterX() - w / 2f);
            this.posY = Math.round(outH * instance.getCenterY() - h / 2f);
        }
    }

    /**
     * Holds an audio clip's caption renderer plus the per-frame time-mapping
     * data needed to compute the source-position from the timeline position.
     */
    private static class AudioCaptionSlot {
        @NonNull final CaptionExportRenderer renderer;
        final long offsetMs;
        final long inPointMs;
        AudioCaptionSlot(@NonNull CaptionExportRenderer renderer, long offsetMs, long inPointMs) {
            this.renderer = renderer;
            this.offsetMs = offsetMs;
            this.inPointMs = inPointMs;
        }
    }

    // S6: sprites for this clip window + their project-level sheet definitions.
    // Renderers decode lazily (one shared bitmap per sheet) and recycle in release().
    private final List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> spriteItems;
    private final List<com.fadcam.ui.faditor.sprite.SpriteSheet> spriteSheets;
    private final java.util.Map<String, com.fadcam.ui.faditor.sprite.SpriteSheetRenderer>
            spriteRenderers = new java.util.HashMap<>();
    private final Paint spritePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    /** G5(b): reused per-frame paint for host-opacity fades on attached visualizers. */
    private final Paint hostOpacityPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // Bake-to-keyframes: rigs for avatar items' replay render. Puppets build
    // lazily per performing item (null cached = broken linkage, no per-frame
    // retry) and release() recycles them. Export frames arrive in time order,
    // which is exactly the DiscreteState stepping the replay doctrine wants.
    private final List<com.fadcam.ui.faditor.avatar.AvatarRig> avatarRigs;
    private final java.util.Map<String, com.fadcam.ui.faditor.avatar.AvatarItemPuppet>
            avatarPuppets = new java.util.HashMap<>();

    // (PiP drawing moved OUT to BlendModeGlEffect/PipFrameOverlay — the
    // z-unification fix: all PiPs composite in the effect chain in z-order;
    // this overlay keeps only sprites/text/captions/waveforms, which sit
    // ABOVE every PiP per the preview stack.)

    public CompositeExportOverlay(@NonNull Context context,
                                   long clipTimelineStartMs,
                                   @NonNull Clip clip,
                                   int outW, int outH,
                                   @NonNull List<TextOverlayItem> allTextOverlays,
                                   @NonNull List<WaveformSlot> waveformSlots,
                                   @NonNull List<AudioClip> audioClips,
                                   @NonNull List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> allSpriteItems,
                                   @NonNull List<com.fadcam.ui.faditor.sprite.SpriteSheet> spriteSheets,
                                   @NonNull List<com.fadcam.ui.faditor.avatar.AvatarRig> avatarRigs,
                                   long projectDurationMs,
                                   long editorTimeOffsetMs) {
        this.editorTimeOffsetMs = editorTimeOffsetMs;
        this.projectDurationMs = projectDurationMs;
        this.context = context.getApplicationContext();
        // Custom caption styles resolve through the store; the :export process is
        // fresh per export, so init here before any CaptionStyle.byId call.
        com.fadcam.ui.faditor.transcript.CaptionStyleStore.ensureInit(this.context);
        this.clipTimelineStartMs = clipTimelineStartMs;
        this.clip = clip;
        this.outW = Math.max(1, outW);
        this.outH = Math.max(1, outH);
        this.clipVisualEndMs = clipTimelineStartMs + clip.getVisualDurationMs();
        this.textOverlays = filterTextOverlays(allTextOverlays);
        this.spriteItems = filterSpriteItems(allSpriteItems);
        this.spriteSheets = spriteSheets;
        this.avatarRigs = avatarRigs;
        this.waveformSlots = waveformSlots;
        this.waveRenderer = new WaveformStyleRenderer();

        // Keep the windowed transcript + position; the renderer itself is built
        // lazily per-frame from the effective caption style (see getBitmap), so a
        // base style of "hidden" no longer means "never draw" — a keyframe may make
        // it visible later, and vice versa, matching the preview.
        Transcript capT = null;
        float capCx = 0.5f, capCy = 0.82f, capSize = 0.06f;
        if (clip.isCaptionsEnabled()) {
            Transcript t = clip.getTranscript();
            if (t != null && !t.words.isEmpty()) {
                capT = t.windowed(clip.getInPointMs(), clip.getOutPointMs());
                capCx = clip.getCaptionCenterX();
                capCy = clip.getCaptionCenterY();
                capSize = clip.getCaptionSizeFraction();
            }
        }
        this.captionTranscript = capT;
        this.captionCenterX = capCx;
        this.captionCenterY = capCy;
        this.captionSizeFraction = capSize;
        this.captionRenderer = null;
        this.captionRendererStyleId = null;

        this.audioCaptionSlots = buildAudioCaptionSlots(audioClips);
    }

    @NonNull
    private List<AudioCaptionSlot> buildAudioCaptionSlots(@NonNull List<AudioClip> audioClips) {
        List<AudioCaptionSlot> slots = new ArrayList<>();
        long clipEndMs = clipTimelineStartMs + clip.getTrimmedDurationMs();
        for (AudioClip ac : audioClips) {
            if (!ac.isCaptionsEnabled() || !ac.hasTranscript()) continue;
            String styleId = ac.getCaptionStyleId();
            if ("hidden".equals(styleId)) continue;
            long audioStartMs = ac.getOffsetMs();
            long audioEndMs = ac.getOffsetMs() + ac.getTrimmedDurationMs();
            // Only include audio clips that overlap this video clip's timeline range
            if (audioStartMs < clipEndMs && audioEndMs > clipTimelineStartMs) {
                Transcript t = ac.getTranscript();
                if (t == null || t.words.isEmpty()) continue;
                CaptionStyle cs = CaptionStyle.byId(styleId);
                CaptionExportRenderer r = new CaptionExportRenderer(
                        t.windowed(ac.getInPointMs(), ac.getOutPointMs()),
                        cs, ac.getCaptionCenterX(), ac.getCaptionCenterY(),
                        ac.getCaptionSizeFraction(), outW, outH);
                slots.add(new AudioCaptionSlot(r, ac.getOffsetMs(), ac.getInPointMs()));
            }
        }
        return slots;
    }

    /** Same clip-window filter as text overlays (visual-duration upper bound;
     *  the per-frame isVisibleAt check does the exact gating). */
    private List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> filterSpriteItems(
            List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> all) {
        List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> out = new ArrayList<>();
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem o : all) {
            if (o.getEndMs() < clipTimelineStartMs) continue;
            if (o.getStartMs() > clipVisualEndMs) continue;
            out.add(o);
        }
        return out;
    }

    /** Lazy decode-once renderer per sheet; null (missing art) cached too. */
    @Nullable
    private com.fadcam.ui.faditor.sprite.SpriteSheetRenderer spriteRendererFor(
            @NonNull String sheetId) {
        if (spriteRenderers.containsKey(sheetId)) return spriteRenderers.get(sheetId);
        com.fadcam.ui.faditor.sprite.SpriteSheet sheet = null;
        for (com.fadcam.ui.faditor.sprite.SpriteSheet s : spriteSheets) {
            if (s.getId().equals(sheetId)) { sheet = s; break; }
        }
        com.fadcam.ui.faditor.sprite.SpriteSheetRenderer r = sheet != null
                ? com.fadcam.ui.faditor.sprite.SpriteSheetRenderer.load(context, sheet) : null;
        spriteRenderers.put(sheetId, r);
        return r;
    }

    @Nullable
    private com.fadcam.ui.faditor.sprite.SpriteSheet sheetById(@NonNull String sheetId) {
        for (com.fadcam.ui.faditor.sprite.SpriteSheet s : spriteSheets) {
            if (s.getId().equals(sheetId)) return s;
        }
        return null;
    }

    /** Lazy replay puppet per performing avatar item (mirrors the preview's
     *  SpriteOverlayView cache; null cached = missing rig, no per-frame retry). */
    @Nullable
    private com.fadcam.ui.faditor.avatar.AvatarItemPuppet puppetFor(
            @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem o) {
        if (!o.hasAvatarPerformance()) return null;
        if (avatarPuppets.containsKey(o.getId())) return avatarPuppets.get(o.getId());
        com.fadcam.ui.faditor.avatar.AvatarRig rig = null;
        for (com.fadcam.ui.faditor.avatar.AvatarRig r : avatarRigs) {
            if (r.getId().equals(o.getAvatarRigId())) { rig = r; break; }
        }
        com.fadcam.ui.faditor.avatar.AvatarItemPuppet p =
                com.fadcam.ui.faditor.avatar.AvatarItemPuppet.forItem(
                        context, o, rig, this::spriteRendererFor, this::sheetById);
        avatarPuppets.put(o.getId(), p);
        return p;
    }

    private List<TextOverlayItem> filterTextOverlays(List<TextOverlayItem> all) {
        List<TextOverlayItem> out = new ArrayList<>();
        // Use the clip's full visual duration (trimmed range + loop/ping-pong
        // extensions) as a safe upper bound. This ensures text overlays that
        // are scheduled inside loop extension regions are kept and rendered by
        // the overlay instances that overlap them; the per-frame
        // isVisibleAt(timelineMs) check drops any that don't belong on the
        // current item.
        for (TextOverlayItem o : all) {
            if (o.getEndMs() < clipTimelineStartMs) continue;
            if (o.getStartMs() > clipVisualEndMs) continue;
            out.add(o);
        }
        return out;
    }

    @Override
    public void configure(@NonNull Size size) {
        int w = Math.max(1, size.getWidth());
        int h = Math.max(1, size.getHeight());
        if (bitmap != null && bitmap.getWidth() == w && bitmap.getHeight() == h) return;
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(bitmap);
    }

    private static final String TAG = "CompositeExportOverlay";
    private int frameCount = 0;
    private int framesWithText = 0;
    private int framesWithCaption = 0;
    private int framesWithWaveform = 0;
    private int framesWithSprite = 0;
    private boolean loggedNullTextWarning = false;
    private boolean loggedNullCaptionWarning = false;
    private boolean loggedNullWaveformWarning = false;
    // Once-only flags so a throwing (non-essential) overlay sub-renderer is logged
    // a single time instead of crashing the encoder thread on every frame.
    private boolean loggedTextDrawError = false;
    private boolean loggedCaptionDrawError = false;
    private boolean loggedWaveformDrawError = false;
    private boolean loggedSpriteDrawError = false;

    @NonNull
    @Override
    public Bitmap getBitmap(long presentationTimeUs) {
        if (bitmap == null || bitmap.isRecycled()) {
            bitmap = Bitmap.createBitmap(Math.max(1, outW), Math.max(1, outH), Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
        }

        // Don't recycle lastReturnedBitmap here — even though GlUtil.setTexture
        // copies the pixel data, some Adreno drivers keep an internal reference
        // to the bitmap for caching. Recycle is deferred to release() so the
        // GC can collect any per-frame allocations. This is the cause of the
        // "first frame uploads, all others render the first frame" symptom the
        // user reported. Bounded: ~8 MB per frame for 1080p, freed by GC.

        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);

        // Media3 Transformer passes presentationTimeUs as absolute timeline time across the whole
        // Composition, not reset to 0 per EditedMediaItem.
        //
        // ⚠ LEDGER §2d — THE COMPOSITION CLOCK IS NOT THE EDITOR CLOCK. A transition SHORTENS the
        // two clips it straddles (ExportManager:923-940), so Composition time runs ahead of the
        // editor's by the cumulative transition duration. Every overlay start/end below was
        // authored against the EDITOR's timeline, which ignores transitions entirely
        // (EditorTimelineView.getSegmentStartTime). Comparing the two directly is what made every
        // overlay after a seam render late by exactly one transition — measured on device
        // 2026-08-03: a PiP authored at 5501ms appeared at 5.50s where it belonged at 4.90s.
        //
        // Converting HERE fixes every consumer at once, because all of them — text, sprites,
        // captions, waveforms — compare against editor-authored values. With no transitions the
        // offset is exactly 0, so this is a no-op for any project that has none.
        long timelineMs = presentationTimeUs / 1000 + editorTimeOffsetMs;
        long clipLocalMs = ExportManager.clipMsFor(presentationTimeUs, clipTimelineStartMs);

        frameCount++;
        if (frameCount <= 5 || frameCount % 30 == 0) {
            FLog.d(TAG, "getBitmap frame=" + frameCount
                    + " pts=" + presentationTimeUs + " timelineMs=" + timelineMs
                    + " clipLocalMs=" + clipLocalMs
                    + " canvas=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " textOverlays=" + textOverlays.size()
                    + " hasCaptions=" + (captionTranscript != null)
                    + " waveformSlots=" + waveformSlots.size());
        }

        // The overlay sub-renderers (captions, text, waveforms) author content in the
        // canvas coordinate space (outW x outH). This overlay's bitmap, however, is
        // sized to the CURRENT video frame (set in configure()), which is the clip's
        // SOURCE resolution — the Presentation resize-to-canvas effect runs AFTER this
        // overlay. When source res differs from the canvas (e.g. a 1080x1920 clip in an
        // 870x1546 9:16 canvas), drawing at outW/outH coords onto the larger frame
        // anchored everything top-left and clipped it: the "captions stuck up and to the
        // left on export" bug. Scale so outW/outH space maps onto the actual frame; the
        // downstream Presentation then scales frame+overlay together to the canvas.
        int frameW = bitmap.getWidth();
        int frameH = bitmap.getHeight();
        canvas.save();
        if (outW > 0 && outH > 0 && (frameW != outW || frameH != outH)) {
            canvas.scale(frameW / (float) outW, frameH / (float) outH);
        }

        // S6 sprites — drawn FIRST so they sit above the video but BELOW text +
        // captions, matching the preview stack (SpriteOverlayView sits under
        // TextOverlayLayer) and the plan's draw-order rule. Same evaluation as
        // the preview by construction: SpriteFrameResolver for the cell,
        // animated* keyframe reads for the transform — divergence impossible.
        int drawnSprite = 0;
        int spriteSaveCount = canvas.getSaveCount();
        try {
            for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem o : spriteItems) {
                if (!o.isVisibleAt(timelineMs)) continue;
                float opacity = o.animatedOpacity(timelineMs);
                if (opacity <= 0.001f) continue;
                com.fadcam.ui.faditor.sprite.SpriteSheet sheet = sheetById(o.getSheetId());
                com.fadcam.ui.faditor.sprite.SpriteSheetRenderer r =
                        sheet != null ? spriteRendererFor(o.getSheetId()) : null;
                if (sheet == null || r == null) continue; // missing art: preview shows
                                                          // the placeholder; export omits
                com.fadcam.ui.faditor.avatar.AvatarItemPuppet puppet = puppetFor(o);
                int cell = com.fadcam.ui.faditor.sprite.SpriteFrameResolver
                        .resolveCellAt(sheet, o, timelineMs);
                if (puppet == null
                        && cell == com.fadcam.ui.faditor.sprite.SpriteFrameResolver.NO_CELL) {
                    continue;
                }
                float cx = o.animatedCenterX(timelineMs) * outW;
                float cy = o.animatedCenterY(timelineMs) * outH;
                float h = o.animatedSizeFraction(timelineMs) * outH;
                float aspect = r.cellAspect();
                float w = h * (aspect > 0 ? aspect : 1f);
                spritePaint.setAlpha(Math.round(opacity * 255));
                canvas.save();
                canvas.rotate(o.animatedRotation(timelineMs), cx, cy);
                if (o.isFlipH() || o.isFlipV()) {
                    canvas.scale(o.isFlipH() ? -1f : 1f, o.isFlipV() ? -1f : 1f, cx, cy);
                }
                android.graphics.RectF dest = new android.graphics.RectF(
                        cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
                if (puppet != null && o.getAvatarTrack() != null) {
                    // Bake-to-keyframes replay: live puppet in the SAME dest box
                    // the neutral cell occupied (preview parity by construction —
                    // SpriteOverlayView takes the identical branch).
                    puppet.draw(canvas, dest, o.getAvatarTrack(),
                            Math.max(0, o.toLocalMs(timelineMs)), opacity);
                } else {
                    r.drawCell(canvas, cell, dest, spritePaint);
                }
                canvas.restore();
                drawnSprite++;
            }
        } catch (Throwable t) {
            canvas.restoreToCount(spriteSaveCount);
            if (!loggedSpriteDrawError) {
                FLog.w(TAG, "Sprite draw threw; sprites skipped for this frame "
                        + "(this warning is logged once)", t);
                loggedSpriteDrawError = true;
            }
        }
        if (drawnSprite > 0) framesWithSprite++;

        // Text overlays
        int drawnText = 0;
        // Wrap the (non-essential) text overlay drawing so a bad overlay can't
        // throw out of the per-frame callback and abort the whole export. The
        // save-count is captured up front and restored on failure so a throw
        // mid-draw never leaves the canvas matrix stack imbalanced.
        int textSaveCount = canvas.getSaveCount();
        try {
        for (TextOverlayItem o : textOverlays) {
            if (!o.isVisibleAt(timelineMs)) continue;
            float opacity = o.animatedOpacity(timelineMs);
            if (opacity <= 0.001f) continue;
            if (o.isGeneratedSlide()) {
                // AI-authored transparent overlay slide (spec Phase 4): composite
                // the pre-rendered PNG frame for this timeline position across the
                // full canvas — the HTML owns its own layout inside the frame.
                Bitmap frame = generatedOverlayFrame(o, timelineMs);
                if (frame != null && !frame.isRecycled()) {
                    Paint gp = new Paint(Paint.FILTER_BITMAP_FLAG);
                    gp.setAlpha(Math.round(opacity * 255));
                    android.graphics.Rect src =
                            new android.graphics.Rect(0, 0, frame.getWidth(), frame.getHeight());
                    android.graphics.Rect dst = new android.graphics.Rect(0, 0, outW, outH);
                    canvas.drawBitmap(frame, src, dst, gp);
                    drawnText++;
                }
                continue;
            }
            float cx = o.animatedCenterX(timelineMs) * outW;
            float cy = o.animatedCenterY(timelineMs) * outH;
            float sizeFrac = o.animatedSizeFraction(timelineMs);
            float rot = o.animatedRotation(timelineMs);

            // ── TEXT: draw through the SHARED renderer the preview uses ─────────────────────
            // This replaced "rasterise the whole box to a bitmap, then transform the bitmap".
            // That approach could only ever animate the box as ONE body, which is why text boxes
            // were BLOCK-only — the recorded reason blamed the preview's TextView, but this side
            // could not do it either. Drawing straight onto the frame canvas, through the same
            // TextBoxRenderer the preview's TextBoxView calls, is what makes WORD and LETTER
            // honest here: there is one layout and one set of per-unit transforms, not two that
            // have to be kept in agreement.
            //
            // Note there is no excursion margin on this side, unlike the preview's TextBoxView:
            // a glyph that animates outside its box simply lands elsewhere on the frame canvas,
            // which has no bounds to be clipped by. The margin is a View artefact, not a
            // property of the animation, which is why it does not belong in the renderer.
            if (!o.isImage()) {
                String shown = com.fadcam.ui.faditor.overlay.TextBoxRenderer.textAt(
                        o, timelineMs, projectDurationMs);
                float fontPx = Math.max(1f, sizeFrac * outH);
                float[] size = new float[2];
                com.fadcam.ui.faditor.overlay.TextBoxRenderer.measure(o, shown, fontPx, size);
                canvas.save();
                canvas.rotate(rot, cx, cy);
                com.fadcam.ui.faditor.overlay.TextBoxRenderer.draw(canvas, o, shown,
                        cx - size[0] / 2f, cy - size[1] / 2f, fontPx, timelineMs,
                        projectDurationMs, true, opacity);
                canvas.restore();
                drawnText++;
                continue;
            }

            // ── IMAGE: draw the bitmap. Until 2026-07-30 this fell through to the text path ──────
            // below, which called setImageUri() on a throwaway item and handed it to
            // TextOverlayRenderer — a text rasteriser with ZERO references to images, which
            // substitutes " " for empty text. An image overlay's text IS empty, so every image
            // overlay exported as one blank space: measured at 0 of 409,920 pixels changed. The
            // setter call looked like function and was a call into a void. See LEDGER "BUG C".
            //
            // Geometry is MIRRORED from the preview (TextOverlayLayer.position), not re-derived:
            // height is a fraction of the frame height, width follows the bitmap's own aspect,
            // and the rect is centred on the object's animated centre. The preview's ImageView is
            // FIT_XY, so drawing into that dst rect stretches identically.
            if (o.isImage()) {
                Bitmap img = imageOverlayBitmap(o);
                if (img == null) continue; // already logged once
                com.fadcam.ui.faditor.transcript.CaptionAnimator.Transform ianim =
                        com.fadcam.ui.faditor.transcript.CaptionAnimator.textBoxTransformAt(
                                com.fadcam.ui.faditor.transcript.CaptionAnimator
                                        .parsePreset(o.getTextAnimPreset()),
                                timelineMs, o.getStartMs(), o.animSpanMs(projectDurationMs),
                                o.getTextAnimInPct(), o.getTextAnimOutPct(), sizeFrac * outH);
                float aspect = img.getHeight() > 0
                        ? img.getWidth() / (float) img.getHeight() : 1f;
                float ih = Math.max(1f, sizeFrac * outH);
                float iw = Math.max(1f, ih * aspect);
                Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG);
                // The preview composes the preset's alpha OVER the keyframed opacity
                // ("compose, don't replace"), so this multiplies rather than picking one.
                int ia = Math.round(opacity * ianim.alpha * 255f);
                ip.setAlpha(Math.max(0, Math.min(255, ia)));
                canvas.save();
                // Same order as the text path and as the preview's View properties.
                canvas.translate(ianim.dx, ianim.dy);
                canvas.rotate(rot, cx, cy);
                canvas.scale(ianim.scaleX, ianim.scaleY, cx, cy);
                // MASK_WIPE's reveal. No ink-pad inset here, unlike the text path: that pad is a
                // TextOverlayRenderer artefact (transparent margin round the glyphs), and an
                // image's drawn rect IS its bounds — which is also what the preview clips.
                if (ianim.revealFrac < 1f) {
                    canvas.clipRect(cx - iw / 2f, cy - ih / 2f,
                            cx - iw / 2f + iw * Math.max(0f, ianim.revealFrac), cy + ih / 2f);
                }
                canvas.drawBitmap(img,
                        new android.graphics.Rect(0, 0, img.getWidth(), img.getHeight()),
                        new android.graphics.RectF(cx - iw / 2f, cy - ih / 2f,
                                cx + iw / 2f, cy + ih / 2f), ip);
                canvas.restore();
                drawnText++;
                continue;
            }
            // SPEC_TIMER_OBJECT: a timer overlay draws a COMPUTED string for this frame;
            // everything else about it (style, transform, keyframes) is unchanged, which
            // is what makes a timer inherit the caption look. Same authority the preview
            // calls, so the two cannot drift.
            String frameText = o.getText();
            if (o.isTimer()) {
                String t = com.fadcam.ui.faditor.model.TimerText.format(
                        o.getTimerSpec(), timelineMs, o.getStartMs(), o.getEndMs(),
                        projectDurationMs, com.fadcam.ui.faditor.model.TimerText.DEFAULT_FPS);
                if (t != null) frameText = t;
            } else {
                // MATRIX substitutes CHARACTERS, so the string is per-frame here exactly as a
                // timer's is. Same authority the preview's TextOverlayLayer calls, so the two
                // cannot churn differently. Inert for every other preset.
                frameText = com.fadcam.ui.faditor.transcript.CaptionAnimator.textBoxTextAt(
                        com.fadcam.ui.faditor.transcript.CaptionAnimator
                                .parsePreset(o.getTextAnimPreset()),
                        frameText, timelineMs, o.getStartMs(),
                        o.animSpanMs(projectDurationMs),
                        o.getTextAnimInPct(), o.getTextAnimOutPct());
            }
            TextOverlayItem frameOverlay = new TextOverlayItem(frameText, o.getColorInt(),
                    cx / outW, cy / outH, sizeFrac, rot);
            frameOverlay.setStrokeColorInt(o.getStrokeColorInt());
            frameOverlay.setStrokeWidthPx(o.getStrokeWidthPx());
            frameOverlay.setShadowColorInt(o.getShadowColorInt());
            frameOverlay.setShadowRadiusPx(o.getShadowRadiusPx());
            frameOverlay.setGlowColorInt(o.getGlowColorInt());
            frameOverlay.setGlowRadiusPx(o.getGlowRadiusPx());
            frameOverlay.setBackgroundColorInt(o.getBackgroundColorInt());
            frameOverlay.setFontFamily(o.getFontFamily());
            // NOTE: setImageUri() used to be called here. It was a CALL INTO A VOID —
            // TextOverlayRenderer has no image support whatsoever — and it is what made BUG C
            // look implemented for months. Images are now handled by the branch above and can
            // never reach this point, so there is no image URI to pass on. Deleted rather than
            // left in place, because a setter nobody reads is the exact §3a failure mode.
            Bitmap textBmp = TextOverlayRenderer.render(frameOverlay, outW, outH);
            if (textBmp == null || textBmp.isRecycled()) {
                if (!loggedNullTextWarning) {
                    FLog.w(TAG, "TextOverlayRenderer returned null/recycled bitmap; "
                            + "text overlay skipped (this warning is logged once)");
                    loggedNullTextWarning = true;
                }
                continue;
            }
            drawnText++;
            // Entrance/exit animation — the SAME evaluator call the preview's TextOverlayLayer
            // makes, so the two surfaces cannot drift. fontPx is in OUTPUT pixels here and in
            // preview pixels there, which is what keeps a preset's dx/dy proportional rather
            // than correct on one surface and wrong on the other.
            com.fadcam.ui.faditor.transcript.CaptionAnimator.Transform anim =
                    com.fadcam.ui.faditor.transcript.CaptionAnimator.textBoxTransformAt(
                            com.fadcam.ui.faditor.transcript.CaptionAnimator
                                    .parsePreset(o.getTextAnimPreset()),
                            timelineMs, o.getStartMs(), o.animSpanMs(projectDurationMs),
                            o.getTextAnimInPct(), o.getTextAnimOutPct(), sizeFrac * outH);
            Paint p = new Paint();
            int alpha = Math.round(opacity * anim.alpha * 255);
            p.setAlpha(Math.max(0, Math.min(255, alpha)));
            canvas.save();
            // Same composition order the View properties give the preview: scale and rotate
            // about the object's centre, THEN translate.
            canvas.translate(anim.dx, anim.dy);
            canvas.rotate(rot, cx, cy);
            canvas.scale(anim.scaleX, anim.scaleY, cx, cy);
            // MASK_WIPE's reveal — the third animated channel, and the export half of what
            // TextOverlayLayer#applyReveal does with View.setClipBounds. Clipped AFTER the matrix,
            // which is the same thing the preview gets by clipping in the view's local space and
            // then transforming: in both cases the mask is carried by the object's own transform
            // rather than standing still in frame space.
            //
            // The wipe runs across the INK, not across the bitmap. TextOverlayRenderer leaves a
            // 0.35em transparent margin so shadows and outlines are not clipped by the bitmap
            // edge, and the preview's TextView has no equivalent margin — so wiping the raw bitmap
            // width would put the mask edge somewhere the preview never puts it.
            if (anim.revealFrac < 1f) {
                float bw = textBmp.getWidth(), bh = textBmp.getHeight();
                int pad = com.fadcam.ui.faditor.overlay.TextOverlayRenderer
                        .padPxFor(frameOverlay, outH);
                float inkL = cx - bw / 2f + pad;
                float inkW = Math.max(1f, bw - pad * 2f);
                canvas.clipRect(inkL, cy - bh / 2f,
                        inkL + inkW * Math.max(0f, anim.revealFrac), cy + bh / 2f);
            }
            canvas.drawBitmap(textBmp, cx - textBmp.getWidth() / 2f,
                    cy - textBmp.getHeight() / 2f, p);
            canvas.restore();
            textBmp.recycle();
        }
        } catch (Throwable t) {
            canvas.restoreToCount(textSaveCount);
            if (!loggedTextDrawError) {
                FLog.w(TAG, "Text overlay draw threw; text skipped for this frame "
                        + "(this warning is logged once)", t);
                loggedTextDrawError = true;
            }
        }
        if (drawnText > 0) framesWithText++;

        // Captions. Transcript.windowed() shares the same TranscriptWord references
        // as the source transcript, so each word's startMs/endMs remain in the
        // SOURCE time coordinate system (not window-relative). sourceMs below
        // therefore maps directly to the windowed transcript's index lookup.
        boolean drewCaption = false;
        // Wrap the (non-essential) caption drawing so a throwing caption renderer
        // can't abort the export. Restore the canvas save-count on failure.
        int captionSaveCount = canvas.getSaveCount();
        try {
        if (captionTranscript != null) {
            // Effective caption style at this clip-local position. Caption-style
            // keyframes (and a "hidden" pseudo-style) are evaluated against the
            // SOURCE-local position (0-based within the trimmed region), exactly as
            // the preview does via captionStyleAtClipMs(positionInCurrentSegmentMs).
            long clipSourceLocalMs = (long) (clipLocalMs * clip.getSpeedMultiplier());
            String styleId = clip.hasCaptionStyleKeyframes()
                    ? clip.captionStyleAtClipMs(clipSourceLocalMs)
                    : clip.getCaptionStyleId();
            // Guard against a null style id (the getter/keyframe lookup is not
            // @NonNull): treat null as "hidden" so the per-frame draw never NPEs
            // on styleId.equals(...) below and crashes the encoder thread.
            if (styleId != null && !"hidden".equals(styleId)) {
                if (captionRenderer == null || !styleId.equals(captionRendererStyleId)) {
                    captionRenderer = new CaptionExportRenderer(captionTranscript,
                            CaptionStyle.byId(styleId), captionCenterX, captionCenterY,
                            captionSizeFraction, outW, outH);
                    captionRendererStyleId = styleId;
                    // Text animation (SPEC_TEXT_ANIMATION): the SAME four values the preview
                    // reads off this clip in bindCaptionData. Set on construction rather than
                    // per frame because they cannot change during an export.
                    captionRenderer.setCaptionAnimation(clip.getCaptionAnimPreset(),
                            clip.getCaptionAnimGranularity(),
                            clip.getCaptionAnimInPct(), clip.getCaptionAnimOutPct());
                }
                long sourceMs = clip.getInPointMs() + clipSourceLocalMs;
                Bitmap captionBmp = captionRenderer.render(sourceMs);
                if (captionBmp == null || captionBmp.isRecycled()) {
                    if (!loggedNullCaptionWarning) {
                        FLog.w(TAG, "CaptionExportRenderer returned null/recycled bitmap; "
                                + "caption skipped (this warning is logged once)");
                        loggedNullCaptionWarning = true;
                    }
                } else {
                    canvas.drawBitmap(captionBmp, 0, 0, null);
                    drewCaption = true;
                }
            }
        }
        if (drewCaption) framesWithCaption++;

        // Audio clip captions: render all audio clips that overlap this video clip
        // (mirrors the live-preview behaviour in updateCurrentTimeDisplay).
        if (!audioCaptionSlots.isEmpty()) {
            long audioCaptionTimelineMs = clipTimelineStartMs + clipLocalMs;
            for (AudioCaptionSlot slot : audioCaptionSlots) {
                long audioSourceMs = audioCaptionTimelineMs - slot.offsetMs + slot.inPointMs;
                Bitmap captionBmp = slot.renderer.render(audioSourceMs);
                if (captionBmp != null && !captionBmp.isRecycled()) {
                    canvas.drawBitmap(captionBmp, 0, 0, null);
                }
            }
        }
        } catch (Throwable t) {
            canvas.restoreToCount(captionSaveCount);
            if (!loggedCaptionDrawError) {
                FLog.w(TAG, "Caption draw threw; captions skipped for this frame "
                        + "(this warning is logged once)", t);
                loggedCaptionDrawError = true;
            }
        }

        // Waveform visualizers
        int drawnWaveform = 0;
        // Wrap the (non-essential) waveform drawing so a throwing renderer can't
        // abort the export. Restore the canvas save-count on failure.
        int waveformSaveCount = canvas.getSaveCount();
        try {
        for (WaveformSlot ws : waveformSlots) {
            // Source time for the audio data is ALWAYS the mapped timeline time
            // (accounting for clip trim/speed/loop). The previous code used the
            // raw timelineMs when horizontalMirror was true, which made the
            // visualizer read the wrong audio bucket (audio is indexed by
            // source time, not timeline time). horizontalMirror is a VISUAL
            // toggle that the WaveformStyleRenderer applies to the drawing; it
            // must not change the audio-data lookup.
            long wsMs = ws.instance.mapToSourceMs(timelineMs);
            Bitmap wfBmp = waveRenderer.render(ws.data, ws.style,
                    ws.slotW, ws.slotH, wsMs, ws.density,
                    ws.instance.getJustify(), ws.instance.getDataMode(),
                    ws.instance.isHorizontalMirror(),
                    ws.instance.getCenterMode(), ws.instance.getRenderMode(),
                    ws.instance.getRadialRingSize(),
                    ws.instance.getFrequencyRangeLowHz(),
                    ws.instance.getFrequencyRangeHighHz(),
                    ws.instance.getBandCountOverride());
            if (wfBmp == null || wfBmp.isRecycled()) {
                if (!loggedNullWaveformWarning) {
                    FLog.w(TAG, "WaveformStyleRenderer returned null/recycled bitmap; "
                            + "waveform overlay skipped (this warning is logged once)");
                    loggedNullWaveformWarning = true;
                }
                continue;
            }
            // G5(b) piggyback-looks: an attached rider fades WITH its host clip's
            // opacity envelope (clip-local time, same convention as
            // OpacityExportShaderProgram). Detached / hostless slots draw at full.
            Paint wfPaint = null;
            if (ws.hostClip != null) {
                float hostOpacity = Math.max(0f, Math.min(1f,
                        ws.hostClip.opacityAtClipMs(timelineMs - ws.hostStartMs)));
                if (hostOpacity <= 0.004f) {
                    wfBmp.recycle();
                    continue; // host fully faded — rider vanishes with it
                }
                if (hostOpacity < 0.999f) {
                    hostOpacityPaint.setAlpha(Math.round(hostOpacity * 255f));
                    wfPaint = hostOpacityPaint;
                }
            }
            drawnWaveform++;
            canvas.save();
            canvas.rotate(ws.instance.getRotationDeg(),
                    ws.posX + ws.slotW / 2f, ws.posY + ws.slotH / 2f);
            canvas.drawBitmap(wfBmp, ws.posX, ws.posY, wfPaint);
            canvas.restore();
            wfBmp.recycle();
        }
        } catch (Throwable t) {
            canvas.restoreToCount(waveformSaveCount);
            if (!loggedWaveformDrawError) {
                FLog.w(TAG, "Waveform draw threw; waveforms skipped for this frame "
                        + "(this warning is logged once)", t);
                loggedWaveformDrawError = true;
            }
        }
        if (drawnWaveform > 0) framesWithWaveform++;

        canvas.restore(); // end the outW/outH -> frame coordinate scale

        if (frameCount <= 5 || frameCount % 30 == 0) {
            FLog.d(TAG, "getBitmap frame=" + frameCount
                    + " drawnText=" + drawnText
                    + " drewCaption=" + drewCaption
                    + " drawnWaveform=" + drawnWaveform
                    + " pixel(0,0)=" + Integer.toHexString(bitmap.getPixel(0, 0)));
        }

        // BitmapOverlay.getTextureId() uses Bitmap.getGenerationId() to decide
        // whether to re-upload the texture. The generationId only changes when
        // the bitmap's underlying memory is reallocated — drawing into the
        // scratch bitmap in place does NOT bump it. Returning the same scratch
        // bitmap every frame would cause the first frame to be uploaded once
        // and then frozen for the rest of the export. createBitmap(scratch)
        // makes a fresh allocation with a new generationId so the texture
        // upload fires every frame.
        if (pendingRecycleBitmap != null && !pendingRecycleBitmap.isRecycled()) {
            pendingRecycleBitmap.recycle();
        }
        Bitmap result = Bitmap.createBitmap(bitmap);
        pendingRecycleBitmap = lastReturnedBitmap;
        lastReturnedBitmap = result;
        return result;
    }

    @NonNull
    @Override
    public StaticOverlaySettings getOverlaySettings(long presentationTimeUs) {
        return new StaticOverlaySettings.Builder().setAlphaScale(1f).build();
    }

    @Override
    public void release() {
        FLog.i(TAG, "Export overlay summary: frames=" + frameCount
                + " textFrames=" + framesWithText
                + " captionFrames=" + framesWithCaption
                + " waveformFrames=" + framesWithWaveform
                + " spriteFrames=" + framesWithSprite
                + " (clip " + clip.getId() + " in=" + clip.getInPointMs()
                + " out=" + clip.getOutPointMs()
                + " speed=" + clip.getSpeedMultiplier() + ")");
        for (com.fadcam.ui.faditor.avatar.AvatarItemPuppet p : avatarPuppets.values()) {
            if (p != null) p.release();
        }
        avatarPuppets.clear();
        for (com.fadcam.ui.faditor.sprite.SpriteSheetRenderer r : spriteRenderers.values()) {
            if (r != null) r.recycle();
        }
        spriteRenderers.clear();
        for (Bitmap b : imageOverlayBitmaps.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        imageOverlayBitmaps.clear();
        if (lastReturnedBitmap != null && !lastReturnedBitmap.isRecycled()) {
            lastReturnedBitmap.recycle();
        }
        lastReturnedBitmap = null;
        if (pendingRecycleBitmap != null && !pendingRecycleBitmap.isRecycled()) {
            pendingRecycleBitmap.recycle();
        }
        pendingRecycleBitmap = null;
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        canvas = null;
    }
}
