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

    private Bitmap bitmap;
    private Canvas canvas;

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
                                   @NonNull List<com.fadcam.ui.faditor.avatar.AvatarRig> avatarRigs) {
        this.context = context.getApplicationContext();
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

        // Media3 Transformer passes presentationTimeUs as absolute timeline time
        // across the whole Composition, not reset to 0 per EditedMediaItem.
        long timelineMs = presentationTimeUs / 1000;
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
            float cx = o.animatedCenterX(timelineMs) * outW;
            float cy = o.animatedCenterY(timelineMs) * outH;
            float sizeFrac = o.animatedSizeFraction(timelineMs);
            float rot = o.animatedRotation(timelineMs);
            TextOverlayItem frameOverlay = new TextOverlayItem(o.getText(), o.getColorInt(),
                    cx / outW, cy / outH, sizeFrac, rot);
            frameOverlay.setStrokeColorInt(o.getStrokeColorInt());
            frameOverlay.setStrokeWidthPx(o.getStrokeWidthPx());
            frameOverlay.setShadowColorInt(o.getShadowColorInt());
            frameOverlay.setShadowRadiusPx(o.getShadowRadiusPx());
            frameOverlay.setGlowColorInt(o.getGlowColorInt());
            frameOverlay.setGlowRadiusPx(o.getGlowRadiusPx());
            frameOverlay.setBackgroundColorInt(o.getBackgroundColorInt());
            frameOverlay.setFontFamily(o.getFontFamily());
            frameOverlay.setImageUri(o.getImageUri());
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
            Paint p = new Paint();
            int alpha = Math.round(opacity * 255);
            p.setAlpha(alpha);
            canvas.save();
            canvas.rotate(rot, cx, cy);
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
