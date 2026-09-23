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
    // Captions for THIS video clip — one slot per enabled binding (SPEC_20260829_CAPTION_LAYERS).
    // The renderer for each slot is (re)built per effective style for binding 0 (keyframe support);
    // other bindings use their static style. Mirrors live preview exactly.
    private final List<ClipCaptionSlot> clipCaptionSlots;
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

    /**
     * This clip's HEAD transition (ms), i.e. how much of its front the export handed to the
     * transition item (LEDGER §2d, second half).
     *
     * <p>Distinct from {@link #editorTimeOffsetMs}, which is the CUMULATIVE figure for placing
     * overlays on the timeline. This one is about the clip's own SOURCE position: the main item's
     * first frame is not {@code inPoint}, it is {@code inPoint + headTransition}, so anything
     * resolved against clip-local time — caption text, caption style keyframes — lands early by
     * this much unless it is added back.</p>
     */
    private final long headTransitionMs;

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
     * Decoded image-overlay bitmaps, keyed by overlay id — BYTE-BOUNDED, not unbounded.
     *
     * <p>This was a plain HashMap held for the clip's whole export, so every image overlay the
     * clip ever showed stayed decoded until release. JoyRaptor's project has 78 image overlays; at
     * export resolution that is hundreds of MB of native heap that no 4 GB phone survives. Only
     * the overlays VISIBLE at the current frame are ever asked for (the draw loop skips
     * {@code !o.isVisibleAt}), so the working set is "how many images overlap at one moment",
     * not "how many the project contains" — a byte budget costs at most a re-decode when a
     * long-gone overlay comes back.</p>
     *
     * <p><b>Evicted entries are NOT recycled, deliberately.</b> An evicted bitmap may still be
     * referenced by an in-flight frame on the encoder side, and freeing its pixels underneath
     * that is a native crash no try/catch reaches. Dropping the reference is enough: the
     * collector frees the pixels once nothing holds them. Eviction means "stop holding it",
     * never "destroy it".</p>
     */
    // Assigned in the constructor, NOT here: the budget asks the ActivityManager through
    // `context`, and field initialisers run before the constructor body has assigned it.
    private final android.util.LruCache<String, Bitmap> imageOverlayBitmaps;

    /**
     * Overlay ids that could not be decoded. Separate from the cache because LruCache cannot
     * store a null VALUE, and the cached failure is the thing that stops a broken URI being
     * re-decoded (and re-logged) on all 900 frames — the reason the old map tested
     * {@code containsKey} rather than {@code get() != null}. Bounded by the overlay count and
     * holds no pixels, so a plain set is right here.
     */
    private final java.util.Set<String> imageOverlayFailed = new java.util.HashSet<>();

    /**
     * Image-overlay bitmap budget for ONE exporting clip, sized to the DEVICE.
     *
     * <p>Deliberately tighter than the editor's preview cache (TextOverlayLayer uses 2% clamped
     * to 160 MB): during an export the media codec holds its own input/output buffers, the
     * frame compositor holds several full-frame ARGB_8888 bitmaps, and the editor process may
     * still be holding its preview cache — so the export is the wrong place to be greedy.
     * Bitmap pixels live in the native heap, so {@code Runtime.maxMemory} and the ActivityManager
     * memory class (both Java-heap bounds) do not describe what is available; total physical RAM
     * is the honest proxy.</p>
     *
     * <p>1.5% of physical RAM, clamped to [24 MB, 96 MB]. On a 4 GB phone that is ~61 MB, which
     * still holds seven full-frame 1080p images at once — far more than realistically overlap on
     * a single frame — while leaving the codec its room. The floor keeps an unknown device from
     * thrashing the decoder; the ceiling keeps a 12 GB phone from parking 200 MB in an export
     * that runs in the background.</p>
     */
    private int imageOverlayCacheBudgetBytes() {
        long total = 0L;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo mi =
                        new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                total = mi.totalMem;
            }
        } catch (Exception ignored) {
            // Fall through to the floor: too small costs a re-decode, guessing high on an
            // unknown device costs the export.
        }
        long budget = total > 0 ? (total * 3L) / 200L : 0L;        // 1.5%
        long clamped = Math.max(24L * 1024 * 1024, Math.min(budget, 96L * 1024 * 1024));
        return (int) clamped;
    }

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
        if (imageOverlayFailed.contains(key)) return null;
        Bitmap cached = imageOverlayBitmaps.get(key);
        if (cached != null) {
            // A recycled entry can only come from release(); drop it rather than hand back
            // pixels that are gone.
            if (!cached.isRecycled()) return cached;
            imageOverlayBitmaps.remove(key);
        }
        // Decode + downsampling live in ImageOverlayDraw, shared with the blend path, so a
        // blended image is never decoded at a different sharpness than an unblended one.
        Bitmap out = ImageOverlayDraw.decode(context, o, outW, outH);
        if (out == null) {
            FLog.w(TAG, "image overlay " + key + " decoded to null from " + o.getImageUri()
                    + " — it will be absent from the exported file");
            imageOverlayFailed.add(key);
            return null;
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
    /** Second scratch buffer — see the ping-pong note in {@link #getBitmap}. */
    @Nullable private Bitmap bitmapB;
    @Nullable private Canvas canvasB;
    /** Scratch for the sprite corner pin — allocated once, never per frame. */
    private final android.graphics.Matrix spritePinMatrix = new android.graphics.Matrix();
    /** Scratch for the text corner pin (SPEC ZC) — allocated once, never per frame. */
    private final android.graphics.Matrix textPinMatrix = new android.graphics.Matrix();
    /** The bend, shared with the preview — see SpriteMeshDraw. */
    /** The text bend, shared with the preview — see CornerPinTextView.meshBend. */
    private final com.fadcam.ui.faditor.sprite.SpriteMeshDraw textMeshDraw =
            new com.fadcam.ui.faditor.sprite.SpriteMeshDraw();
    private final android.graphics.RectF textMeshRect = new android.graphics.RectF();

    private final com.fadcam.ui.faditor.sprite.SpriteMeshDraw spriteMeshDraw =
            new com.fadcam.ui.faditor.sprite.SpriteMeshDraw();
    /** EXPORT COST INSTRUMENTATION: how much of an export this clip's overlay pass actually is. */
    /** Identity-stable, never drawn into; see the empty-frame branch in getBitmap. */
    @Nullable private Bitmap emptyBitmap;
    private int emptyFrames = 0;
    private int costFrames = 0;
    private long costDrawNanos = 0L;

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

    private static class ClipCaptionSlot {
        @NonNull final Transcript transcript; // windowed to clip in/out
        @NonNull final Clip.CaptionBinding binding;
        @Nullable CaptionExportRenderer renderer;
        @Nullable String rendererStyleId;
        ClipCaptionSlot(@NonNull Transcript transcript, @NonNull Clip.CaptionBinding binding) {
            this.transcript = transcript;
            this.binding = binding;
        }
    }

    private static List<ClipCaptionSlot> buildClipCaptionSlots(@NonNull Clip clip, int outW, int outH) {
        List<ClipCaptionSlot> out = new ArrayList<>();
        for (Clip.CaptionBinding b : clip.getCaptionBindings()) {
            if (!b.enabled) continue;
            if ("hidden".equals(b.styleId)) continue;
            com.fadcam.ui.faditor.transcript.NamedTranscript nt = clip.transcriptForBinding(b);
            if (nt == null || nt.transcript == null || nt.transcript.isEmpty()) continue;
            Transcript win = nt.transcript.windowed(clip.getInPointMs(), clip.getOutPointMs());
            if (win.words.isEmpty()) continue;
            out.add(new ClipCaptionSlot(win, b));
        }
        // Legacy fallback: if no bindings but old single caption fields would have produced one, keep legacy path via binding synthesis already done in storage.
        return out;
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
                                   long editorTimeOffsetMs,
                                   long headTransitionMs) {
        this.editorTimeOffsetMs = editorTimeOffsetMs;
        this.headTransitionMs = headTransitionMs;
        this.projectDurationMs = projectDurationMs;
        this.context = context.getApplicationContext();
        this.imageOverlayBitmaps =
                new android.util.LruCache<String, Bitmap>(imageOverlayCacheBudgetBytes()) {
                    @Override protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                        return value.getAllocationByteCount();
                    }
                };
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

        this.clipCaptionSlots = buildClipCaptionSlots(clip, outW, outH);
        this.audioCaptionSlots = buildAudioCaptionSlots(audioClips);
    }

    @NonNull
    private List<AudioCaptionSlot> buildAudioCaptionSlots(@NonNull List<AudioClip> audioClips) {
        List<AudioCaptionSlot> slots = new ArrayList<>();
        long clipEndMs = clipTimelineStartMs + clip.getTrimmedDurationMs();
        for (AudioClip ac : audioClips) {
            java.util.List<AudioClip.CaptionBinding> bs = ac.getCaptionBindings();
            if (bs.isEmpty()) {
                if (!ac.isCaptionsEnabled() || !ac.hasTranscript()) continue;
                String styleId = ac.getCaptionStyleId();
                if ("hidden".equals(styleId)) continue;
                long audioStartMs = ac.getOffsetMs();
                long audioEndMs = ac.getOffsetMs() + ac.getTrimmedDurationMs();
                if (audioStartMs < clipEndMs && audioEndMs > clipTimelineStartMs) {
                    Transcript t = ac.getTranscript();
                    if (t == null || t.words.isEmpty()) continue;
                    CaptionStyle cs = CaptionStyle.byId(styleId);
                    CaptionExportRenderer r = new CaptionExportRenderer(
                            t.windowed(ac.getInPointMs(), ac.getOutPointMs()),
                            cs, ac.getCaptionCenterX(), ac.getCaptionCenterY(),
                            ac.getCaptionSizeFraction(), outW, outH);
                    // Motion preset — the audio path previewed it and exported without it
                    // (the video path has always applied this; see the clip-caption slots).
                    r.setCaptionAnimation(ac.getCaptionAnimPreset(), ac.getCaptionAnimGranularity(),
                            ac.getCaptionAnimInPct(), ac.getCaptionAnimOutPct());
                    slots.add(new AudioCaptionSlot(r, ac.getOffsetMs(), ac.getInPointMs()));
                }
            } else {
                for (AudioClip.CaptionBinding b : bs) {
                    if (!b.enabled) continue;
                    if ("hidden".equals(b.styleId)) continue;
                    com.fadcam.ui.faditor.transcript.NamedTranscript nt = ac.transcriptForBinding(b);
                    if (nt == null || nt.transcript == null || nt.transcript.isEmpty()) continue;
                    long audioStartMs = ac.getOffsetMs();
                    long audioEndMs = ac.getOffsetMs() + ac.getTrimmedDurationMs();
                    if (audioStartMs >= clipEndMs || audioEndMs <= clipTimelineStartMs) continue;
                    CaptionStyle cs = CaptionStyle.byId(b.styleId);
                    CaptionExportRenderer r = new CaptionExportRenderer(
                            nt.transcript.windowed(ac.getInPointMs(), ac.getOutPointMs()),
                            cs, b.centerX, b.centerY, b.sizeFraction, b.boxWidthFraction, outW, outH);
                    // Box alignment + opacity fade travel with the binding, exactly as the preview
                    // overlay reads them — without these the export re-centred a pinned block and
                    // ignored the fade the user watched the veil grow for.
                    r.setBoxAlign(b.anchor, b.justify);
                    r.setCaptionFade(b.fadeInMs, b.fadeOutMs, ac.getTrimmedDurationMs());
                    // Motion preset lives on the audio CLIP (there is no per-binding animation
                    // model on the audio side), so every binding of a clip animates alike —
                    // exactly what the preview does.
                    r.setCaptionAnimation(ac.getCaptionAnimPreset(), ac.getCaptionAnimGranularity(),
                            ac.getCaptionAnimInPct(), ac.getCaptionAnimOutPct());
                    slots.add(new AudioCaptionSlot(r, ac.getOffsetMs(), ac.getInPointMs()));
                }
            }
        }
        return slots;
    }

    /**
     * Same clip-window filter as text overlays (visual-duration upper bound;
     * the per-frame isVisibleAt check does the exact gating).
     *
     * <p>SPEC_ZA: a sprite that belongs to the GL composite is dropped here, exactly as
     * {@code filterTextOverlays} drops an image that belongs to {@code ImageBlendGlEffect}.
     * The two decisions consult the SAME single predicate — {@code wantsGl()} — and must
     * stay exactly complementary: {@code ExportManager} emits a {@code SpriteBlendGlEffect}
     * for every sprite where it is true, so dropping anything else here would draw it
     * twice (once warped in the shader, once plain on top of the grade it moved to GL
     * to receive), and keeping anything it covers would erase it from the export
     * entirely. One predicate makes that structural instead of something a test has to
     * keep catching — the same arrangement the image path already relies on.
     */
    private List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> filterSpriteItems(
            List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> all) {
        List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> out = new ArrayList<>();
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem o : all) {
            if (o.getEndMs() < clipTimelineStartMs) continue;
            if (o.getStartMs() > clipVisualEndMs) continue;
            if (o.wantsGl()) continue;
            out.add(o);
        }
        return out;
    }

    /**
     * Longest edge of the frame currently being composited — the decode bound for image-sequence
     * frames (§8). Zero until the first frame, where the {@code max(256, …)} floor covers it.
     */
    private int lastOutputMaxDim;

    /** Lazy decode-once renderer per sheet; null (missing art) cached too. */
    @Nullable
    private com.fadcam.ui.faditor.sprite.SpriteSheetRenderer spriteRendererFor(
            @NonNull String sheetId) {
        if (spriteRenderers.containsKey(sheetId)) return spriteRenderers.get(sheetId);
        com.fadcam.ui.faditor.sprite.SpriteSheet sheet = null;
        for (com.fadcam.ui.faditor.sprite.SpriteSheet s : spriteSheets) {
            if (s.getId().equals(sheetId)) { sheet = s; break; }
        }
        // SPEC_IMAGE_SEQUENCE §8 Memory: bound an image sequence's per-frame decodes against the
        // OUTPUT size, not a screen-sized default. "A 500-frame 4K sequence must never decode all
        // frames" — and it must not decode any of them larger than the frame it is drawn into.
        // lastOutputMaxDim is the composition's own longest edge, so a 1080x1920 export decodes
        // at most 1920px regardless of how large the source stills are.
        com.fadcam.ui.faditor.sprite.SpriteSheetRenderer r = sheet != null
                ? com.fadcam.ui.faditor.sprite.SpriteSheetRenderer.load(context, sheet,
                        Math.max(256, lastOutputMaxDim))
                : null;
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
            // M7: an overlay carrying its OWN effects is rendered by TextFxGlEffect instead,
            // because a Canvas has no shader to run them through. Skipping it here is what
            // stops it being drawn twice — once styled in GL and once plain on top.
            // IMAGE overlays are the exception, and deliberately: TextFxGlEffect rasterises
            // TEXT (TextOverlayRenderer substitutes " " for empty text), so an image with FX
            // handed to it exports as a blank space. Until that effect learns to rasterise the
            // image bitmap (build-list), an image with FX stays on the plain canvas path — the
            // effect persists, but the picture must not disappear.
            if (o.hasActiveFx() && !o.isImage()) continue;
            // An image that chose a blend mode is composited by ImageBlendGlEffect instead — a
            // Canvas has no video underneath it to blend with. Dropping it here is what stops it
            // being drawn twice, once blended in the shader and once plain on top. ExportManager
            // emits that effect for BOTH z buckets, so this exclusion never orphans an item.
            if (ExportManager.exportGlRouted(o)) continue;
            out.add(o);
        }
        return out;
    }

    // ── Captions, shared by the Canvas pass and the GL caption pass ─────────────────────
    private boolean captionsViaGl = false;
    private final java.util.List<CaptionExportRenderer> captionFrame = new ArrayList<>();
    private final android.graphics.Rect captionScratch = new android.graphics.Rect();

    /** When true this pass leaves captions to {@link GlCaptionEffect} (drawn after it). */
    void setCaptionsViaGl(boolean viaGl) { this.captionsViaGl = viaGl; }

    boolean hasCaptions() { return !clipCaptionSlots.isEmpty() || !audioCaptionSlots.isEmpty(); }

    /** The GL caption pass's entry: every caption that draws at this presentation time. */
    @NonNull
    java.util.List<CaptionExportRenderer> renderCaptionsAt(long presentationTimeUs) {
        return renderCaptions(ExportManager.clipMsFor(presentationTimeUs, clipTimelineStartMs));
    }

    /**
     * Render every caption slot (clip bindings, then audio bindings) for this frame; returns the
     * renderers that drew something. ONE implementation for both passes, so the GL captions are
     * the Canvas captions' own pixels.
     */
    @NonNull
    private java.util.List<CaptionExportRenderer> renderCaptions(long clipLocalMs) {
        captionFrame.clear();
        if (!clipCaptionSlots.isEmpty()) {
            long clipSourceLocalMs =
                    (long) ((clipLocalMs + headTransitionMs) * clip.getSpeedMultiplier());
            long sourceMs = clip.getInPointMs() + clipSourceLocalMs;
            boolean isFirstBinding = true;
            for (ClipCaptionSlot slot : clipCaptionSlots) {
                String styleId;
                if (isFirstBinding && clip.hasCaptionStyleKeyframes()) {
                    styleId = clip.captionStyleAtClipMs(clipSourceLocalMs);
                } else {
                    styleId = slot.binding.styleId;
                }
                isFirstBinding = false;
                if (styleId == null || "hidden".equals(styleId)) continue;
                if (slot.renderer != null && !styleId.equals(slot.rendererStyleId)) {
                    // Caption style KEYFRAME transition. Swap the style in place rather than
                    // rebuilding: the constructor allocates a full-frame ARGB_8888 bitmap (8.3 MB
                    // at 1080p) and drops the old one for the GC, and it also discards the fit
                    // caches, so a clip alternating between two styles re-fit its whole
                    // transcript at every switch. setStyle resets every piece of style-derived
                    // state and keeps the bitmap; the settings below are binding/clip properties,
                    // not style properties, so they survive the swap unchanged.
                    slot.renderer.setStyle(CaptionStyle.byId(styleId));
                    slot.rendererStyleId = styleId;
                }
                if (slot.renderer == null) {
                    slot.renderer = new CaptionExportRenderer(slot.transcript,
                            CaptionStyle.byId(styleId), slot.binding.centerX, slot.binding.centerY,
                            slot.binding.sizeFraction, slot.binding.boxWidthFraction, outW, outH);
                    slot.rendererStyleId = styleId;
                    slot.renderer.setCaptionAnimation(clip.getCaptionAnimPreset(),
                            clip.getCaptionAnimGranularity(),
                            clip.getCaptionAnimInPct(), clip.getCaptionAnimOutPct());
                    // The binding's box alignment and opacity fade — the two properties the
                    // export used to drop on the floor. The fade's span is the caption's own
                    // placement on the timeline, which for a clip caption IS the clip's span
                    // (CaptionSpanRef), so the local clock below is clipLocalMs.
                    slot.renderer.setBoxAlign(slot.binding.anchor, slot.binding.justify);
                    slot.renderer.setCaptionFade(slot.binding.fadeInMs, slot.binding.fadeOutMs,
                            Math.max(1L, clip.getVisualDurationMs()));
                }
                Bitmap captionBmp = slot.renderer.render(sourceMs, clipLocalMs);
                if (captionBmp == null || captionBmp.isRecycled()) {
                    if (!loggedNullCaptionWarning) {
                        FLog.w(TAG, "CaptionExportRenderer returned null/recycled bitmap; "
                                + "caption skipped (this warning is logged once)");
                        loggedNullCaptionWarning = true;
                    }
                } else if (slot.renderer.tightBounds(captionScratch)) {
                    captionFrame.add(slot.renderer);
                }
            }
        }
        if (!audioCaptionSlots.isEmpty()) {
            long audioCaptionTimelineMs = clipTimelineStartMs + clipLocalMs;
            for (AudioCaptionSlot slot : audioCaptionSlots) {
                long audioSourceMs = audioCaptionTimelineMs - slot.offsetMs + slot.inPointMs;
                // Local time within the AUDIO clip's own span — the clock the binding's fade is
                // measured against (its span on the timeline starts at the clip's offset).
                long audioSpanLocalMs = audioCaptionTimelineMs - slot.offsetMs;
                Bitmap captionBmp = slot.renderer.render(audioSourceMs, audioSpanLocalMs);
                if (captionBmp != null && !captionBmp.isRecycled()
                        && slot.renderer.tightBounds(captionScratch)) {
                    captionFrame.add(slot.renderer);
                }
            }
        }
        return captionFrame;
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
        long drawStartNs = System.nanoTime();
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
                    + " hasCaptions=" + (!clipCaptionSlots.isEmpty())
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
        // Remembered for the sequence decode bound (see spriteRendererFor). Set here rather
        // than in configure() because this is where the true per-frame size is known.
        lastOutputMaxDim = Math.max(frameW, frameH);
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
                // SPEC Z slice 1 — the sprite's corner pin, built by the SAME method the preview
                // calls (SpriteOverlayItem.cornerPinMatrix) and concat-ed at the SAME point:
                // inside the rotate and the flip, immediately around the draw below. Both
                // branches under it — a rig puppet and a plain cell — draw into `dest`, so the
                // pin distorts the composed result either way and a bend authored on a head is
                // inherited by every cell. An undistorted sprite takes the byte-identical path
                // it always did, because the method returns false and nothing is concat-ed.
                if (o.cornerPinMatrix(spritePinMatrix, timelineMs,
                        dest.left, dest.top, dest.width(), dest.height())) {
                    canvas.concat(spritePinMatrix);
                }
                // SPEC Z slice 1 — THE BEND, through the SAME SpriteMeshDraw the preview calls.
                // One class, one deformation authority, two surfaces: the sprite export and the
                // sprite preview cannot disagree about a bend because there is only one of it.
                final int fCell = cell;
                final com.fadcam.ui.faditor.avatar.AvatarItemPuppet fPuppet = puppet;
                final float fOpacity = opacity;
                com.fadcam.ui.faditor.sprite.SpriteMeshDraw.Content content = (c, into) -> {
                    if (fPuppet != null && o.getAvatarTrack() != null) {
                        fPuppet.draw(c, into, o.getAvatarTrack(),
                                Math.max(0, o.toLocalMs(timelineMs)), fOpacity);
                    } else {
                        r.drawCell(c, fCell, into, spritePaint);
                    }
                };
                if (!spriteMeshDraw.draw(canvas, o, timelineMs, dest, content, spritePaint)) {
                    // No bend: the byte-identical path this always took.
                    content.draw(canvas, dest);
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
                // BEND, through the SAME SpriteMeshDraw the preview's CornerPinTextView calls —
                // one rasterise-and-warp for every type that bends on a Canvas, so a bent text box
                // here cannot disagree with the one on screen. The pin goes INSIDE the bend on
                // both surfaces; a box can be pinned and bent at once.
                if (o.hasMesh()) {
                    textMeshRect.set(cx - size[0] / 2f, cy - size[1] / 2f,
                            cx + size[0] / 2f, cy + size[1] / 2f);
                    final float fpx = fontPx;
                    boolean bent = textMeshDraw.draw(canvas, o.getMesh(),
                            o.meshLocalTime(timelineMs), textMeshRect,
                            (c, into) -> {
                                c.save();
                                if (o.cornerPinMatrix(textPinMatrix, timelineMs,
                                        into.left, into.top, into.width(), into.height())) {
                                    c.concat(textPinMatrix);
                                }
                                com.fadcam.ui.faditor.overlay.TextBoxRenderer.draw(c, o, shown,
                                        into.left, into.top, fpx, timelineMs,
                                        projectDurationMs, true, opacity);
                                c.restore();
                            }, null);
                    if (bent) {
                        canvas.restore();
                        drawnText++;
                        continue;
                    }
                }
                // SPEC ZC — the text corner pin, built by the SAME method the preview calls
                // (TextOverlayItem.cornerPinMatrix) and concat-ed at the SAME point: inside the
                // rotate, immediately around the draw below. An undistorted box takes the
                // byte-identical path it always did, because the method returns false and nothing
                // is concat-ed. The pin is a matrix on glyph outlines, not a warped raster, so
                // the box stays vector-sharp here exactly as in the preview.
                if (o.cornerPinMatrix(textPinMatrix, timelineMs,
                        cx - size[0] / 2f, cy - size[1] / 2f, size[0], size[1])) {
                    canvas.concat(textPinMatrix);
                }
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
            //
            // The arithmetic moved to ImageOverlayDraw when ImageOverlayFrameOverlay needed the
            // identical picture on its own frame-sized bitmap (blend modes). Not a copy: one
            // authority, called from both, so the blended and unblended paths cannot place the
            // same image in two different spots.
            if (o.isImage()) {
                Bitmap img = imageOverlayBitmap(o);
                if (img == null) continue; // already logged once
                if (ImageOverlayDraw.draw(canvas, img, o, timelineMs, projectDurationMs,
                        outW, outH, 1f)) {
                    drawnText++;
                }
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
                        frameText, timelineMs, o.motionRangeStartMs(),
                        o.motionSpanMs(projectDurationMs),
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
            // Alignment rides along too — M3 (adversarial review): the frame item is what
            // TextOverlayRenderer.render rasterises, and without this the export centred every
            // LEFT/RIGHT/JUSTIFY overlay even though the preview honoured it.
            frameOverlay.setTextAlign(o.getTextAlign());
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
                            timelineMs, o.motionRangeStartMs(), o.motionSpanMs(projectDurationMs),
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

        // Captions — one renderer per enabled binding (SPEC_20260829_CAPTION_LAYERS).
        // Each slot shares the same windowed transcript semantics as before (source time).
        // On the GL caption path GlCaptionEffect draws them AFTER this pass, from the same
        // renderers (renderCaptionsAt) — same pixels, no full-frame blit or upload.
        boolean drewCaption = false;
        int captionSaveCount = canvas.getSaveCount();
        try {
            if (!captionsViaGl) {
                for (CaptionExportRenderer r : renderCaptions(clipLocalMs)) {
                    canvas.drawBitmap(r.lastBitmap(), 0, 0, null);
                    drewCaption = true;
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
        if (drewCaption) framesWithCaption++;

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
            // FADE_KNOBS §2.5: the visualizer's own fade knobs multiply in too — the
            // piggyback envelope and the instance's fades COMPOSE (both are multipliers).
            float ownFade = ws.instance.fadeFactorAt(timelineMs);
            if (ownFade <= 0.004f) {
                wfBmp.recycle();
                continue; // fully faded by its own knobs — nothing to draw
            }
            if (ws.hostClip != null) {
                float hostOpacity = Math.max(0f, Math.min(1f,
                        ws.hostClip.opacityAtClipMs(timelineMs - ws.hostStartMs))) * ownFade;
                if (hostOpacity <= 0.004f) {
                    wfBmp.recycle();
                    continue; // host fully faded — rider vanishes with it
                }
                if (hostOpacity < 0.999f) {
                    hostOpacityPaint.setAlpha(Math.round(hostOpacity * 255f));
                    wfPaint = hostOpacityPaint;
                }
            } else if (ownFade < 0.999f) {
                hostOpacityPaint.setAlpha(Math.round(ownFade * 255f));
                wfPaint = hostOpacityPaint;
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

        // PING-PONG INSTEAD OF ALLOCATING. BitmapOverlay.getTextureId() decides whether to
        // re-upload with:
        //
        //     if (bitmap != lastBitmap || generationId != lastBitmapGenerationId)
        //
        // The FIRST half of that is an identity check, and it is the half that matters here.
        // This used to satisfy the test with Bitmap.createBitmap(scratch) — a full copy of an
        // outW x outH ARGB_8888 frame, EVERY FRAME, purely to obtain a fresh generationId.
        // At 720p that is ~3.7MB allocated, copied and thrown away thirty times a second;
        // JoyRaptor's export crawled through its trailing 18-second black spacer at roughly one
        // percent a minute ("export took a long time and then failed", and after the watchdog
        // fix, took a long time and kept going).
        //
        // Handing back two scratch bitmaps in alternation satisfies `bitmap != lastBitmap` on
        // every frame with ZERO per-frame allocation: consecutive frames are always different
        // objects. The one being drawn into is never the one media3 just uploaded, and the
        // upload is synchronous inside getTextureId, so a buffer is free again by the time it
        // comes back around — strictly safer than the recycle-the-previous-one dance this
        // replaces, which handed the driver a bitmap it might still be caching.
        // NOTHING TO SAY, SO SAY IT WITH THE SAME OBJECT TWICE. The identity check above cuts
        // both ways: hand back the SAME bitmap as last frame and media3 skips the upload
        // entirely. An overlay pass is added to a clip's chain when the PROJECT has content for
        // it, not when this clip's window does, so a project with one lane below a blend puts
        // three of these in every item and two of them draw nothing for most of the export —
        // and each was still uploading a full transparent frame thirty times a second.
        //
        // The empty bitmap is allocated once, never drawn into, and never enters the ping-pong,
        // so its identity and generationId are both stable for as long as the run of empty
        // frames lasts. The pass still runs on the GPU; what stops is the CPU->GPU traffic.
        if (drawnSprite == 0 && drawnText == 0 && !drewCaption && drawnWaveform == 0) {
            if (emptyBitmap == null || emptyBitmap.isRecycled()
                    || emptyBitmap.getWidth() != bitmap.getWidth()
                    || emptyBitmap.getHeight() != bitmap.getHeight()) {
                if (emptyBitmap != null && !emptyBitmap.isRecycled()) emptyBitmap.recycle();
                emptyBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                        Bitmap.Config.ARGB_8888);
            }
            emptyFrames++;
            costFrames++;
            costDrawNanos += System.nanoTime() - drawStartNs;
            return emptyBitmap;
        }

        if (bitmapB == null || bitmapB.isRecycled()) {
            bitmapB = Bitmap.createBitmap(Math.max(1, outW), Math.max(1, outH),
                    Bitmap.Config.ARGB_8888);
            canvasB = new Canvas(bitmapB);
        }
        Bitmap result = bitmap;
        // Swap: next frame draws into the buffer we are handing over now, and vice versa.
        Bitmap heldBitmap = bitmapB;
        Canvas heldCanvas = canvasB;
        bitmapB = bitmap;
        canvasB = canvas;
        bitmap = heldBitmap;
        canvas = heldCanvas;
        lastReturnedBitmap = result;
        costFrames++;
        costDrawNanos += System.nanoTime() - drawStartNs;
        return result;
    }

    @NonNull
    @Override
    public StaticOverlaySettings getOverlaySettings(long presentationTimeUs) {
        return new StaticOverlaySettings.Builder().setAlphaScale(1f).build();
    }

    @Override
    public void release() {
        // WHERE THE EXPORT ACTUALLY GOES. JoyRaptor, 2026-08-27: "export time is obscene." A
        // 46-second project at 720p/Low took ~15 minutes on the Note 9, and progress stalled
        // hard around 30% -- nowhere near the trailing black spacer that looked like the
        // obvious suspect. Guessing has cost enough this week; this says, per clip, how many
        // overlay frames were drawn and how long they took, so the next change targets the
        // clip that is actually expensive.
        if (costFrames > 0) {
            FLog.i(TAG, "EXPORT_COST clip@" + clipTimelineStartMs + "ms frames=" + costFrames
                    + " overlayDrawMs=" + (costDrawNanos / 1_000_000L)
                    + " emptyFrames=" + emptyFrames
                    + " avgMsPerFrame=" + (costDrawNanos / 1_000_000L / Math.max(1, costFrames))
                    + " size=" + outW + "x" + outH);
        }
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
        // ORDER IS LOAD-BEARING: snapshot, then evictAll, THEN recycle.
        //
        // evictAll() calls sizeOf() again on every entry to decrement the cache's running total,
        // and LruCache throws IllegalStateException("sizeOf() is reporting inconsistent results!")
        // if that answer differs from the one given at put() time. A RECYCLED bitmap reports a
        // different getAllocationByteCount() than a live one, so recycling before evicting made
        // every export of a project containing an image overlay die here with
        // "Video frame processing error" (JoyRaptor, 2026-09-02, two failed exports in a row).
        //
        // Evicting first asks sizeOf() while the pixels are still there, so the totals agree and
        // the cache empties cleanly. Recycling afterwards is still safe for the reason the old
        // comment gave: this is the one point where the export is finished with every frame, so
        // freeing the pixels here cannot pull them out from under an in-flight encoder frame the
        // way eviction mid-export could.
        java.util.Map<String, Bitmap> overlayBitmapsToFree = imageOverlayBitmaps.snapshot();
        imageOverlayBitmaps.evictAll();
        for (Bitmap b : overlayBitmapsToFree.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        imageOverlayFailed.clear();
        if (bitmapB != null && !bitmapB.isRecycled()) {
            bitmapB.recycle();
        }
        bitmapB = null;
        canvasB = null;
        if (lastReturnedBitmap != null && !lastReturnedBitmap.isRecycled()) {
            lastReturnedBitmap.recycle();
        }
        lastReturnedBitmap = null;
        if (emptyBitmap != null && !emptyBitmap.isRecycled()) emptyBitmap.recycle();
        emptyBitmap = null;
        if (pendingRecycleBitmap != null && !pendingRecycleBitmap.isRecycled()) {
            pendingRecycleBitmap.recycle();
        }
        pendingRecycleBitmap = null;
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        canvas = null;
    }
}

