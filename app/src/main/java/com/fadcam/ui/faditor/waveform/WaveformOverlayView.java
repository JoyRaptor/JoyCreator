package com.fadcam.ui.faditor.waveform;

import com.fadcam.ui.faditor.Studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.View;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.WaveformData;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.model.WaveformStyle;
import com.fadcam.vislab.room.VisLabSession;
import com.fadcam.vislab.studio.StudioVis;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Editor preview layer for placed waveform/spectrum visualizers. Sized to the canvas (like the
 * player view) so an overlay's normalized placement maps straight to pixels. Driven by the
 * playhead — {@link #setPlayheadMs(long)} re-renders each active overlay at its current local
 * time via {@link WaveformStyleRenderer}, so it animates with playback and scrubbing.
 *
 * <p><b>This view only DRAWS.</b> It takes no touches: a visualiser is selected, moved, resized and
 * turned on Studio's shared transform box ({@code TransformOverlayView}, hosted by
 * {@code VisualizerTransformHost}) like every other object, and a touch that lands here falls
 * through to the views beneath. The older dashed box it used to carry is kept below, drawn only
 * for an overlay handed to {@link #setSelected}, which the editor no longer does.</p>
 */
public class WaveformOverlayView extends View {

    private final WaveformStyleRenderer renderer = new WaveformStyleRenderer();
    private final Map<String, WaveformStyle> styles = new HashMap<>();
    private final float density;

    private List<WaveformOverlayInstance> overlays = Collections.emptyList();
    private Map<String, WaveformData> dataBySource = Collections.emptyMap();
    private final Map<String, Float> progressBySource = new HashMap<>();
    private long playheadMs = 0;
    /** audioSourceRef -> the source file, for items with a VisLab look (they hear the source). */
    private Map<String, Uri> sourceUris = Collections.emptyMap();
    /** A VisLab look's preview is drawn at most this many pixels on its long side, then scaled. */
    private static final float VIS_PREVIEW_MAX = 720f;
    private final RectF visDst = new RectF();
    /** Where a source's transcript is (the editor answers live, so a new transcript shows at once). */
    public interface TranscriptSource {
        @Nullable com.fadcam.ui.faditor.transcript.Transcript forSource(@NonNull String sourceRef);
    }
    @Nullable private TranscriptSource transcripts;
    /** Per item: the transcript words last handed to its look, by key (rebuilt only on a change). */
    private final Map<String, com.fadcam.vislab.studio.StudioWords> visWords = new HashMap<>();
    private final Map<String, Long> visWordsKey = new HashMap<>();
    @Nullable private Paint visPaint;
    /**
     * R11, true blend preview: visualizers the GL composite draws this frame (a blend mode other
     * than Normal), so this view stops painting them; their latest frame and alpha are kept for
     * {@link #glRasterFor} instead. Told by the editor every tick (FxLivePreviewController).
     */
    @NonNull private final java.util.Set<String> glOwned = new java.util.HashSet<>();
    @NonNull private final java.util.Map<String, Bitmap> glRasters = new java.util.HashMap<>();
    @NonNull private final java.util.Map<String, Bitmap> glSources = new java.util.HashMap<>();
    @NonNull private final java.util.Map<String, Float> glAlphas = new java.util.HashMap<>();
    private boolean looksAsked;

    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Fade-knob alpha paint (FADE_KNOBS §2.5) — lazily created, only when a fade is active. */
    @androidx.annotation.Nullable private Paint fadePaint;
    /** The overlay the older dashed box is drawn on; nothing in the editor sets it any more. */
    @Nullable private WaveformOverlayInstance selected;

    public WaveformOverlayView(Context context) {
        this(context, null);
    }

    public WaveformOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        this.density = getResources().getDisplayMetrics().density;
        for (WaveformStyle s : WaveformStyleIO.loadBuiltins(context)) {
            styles.put(s.id, s);
        }
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setColor(Studio.VIDEO);
        selectionPaint.setStrokeWidth(2f * density);
        selectionPaint.setPathEffect(new DashPathEffect(new float[]{8f * density, 6f * density}, 0));
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Studio.VIDEO);
        // Software layer: makes drawBitmap copy immediately (so the reused render bitmap is
        // safe across multiple overlays) and lets the glow BlurMaskFilter composite correctly.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    /**
     * Draw the older dashed selection box (with its eight dots) on {@code overlay}, or on nothing.
     * Drawing only: the box takes no touches. The editor selects on the shared transform box now.
     */
    public void setSelected(@Nullable WaveformOverlayInstance overlay) {
        this.selected = overlay;
        invalidate();
    }

    /** Add/replace a user style not in the built-in set (from the Studio). */
    public void putStyle(@NonNull WaveformStyle style) {
        styles.put(style.id, style);
        invalidate();
    }

    public void setOverlays(@NonNull List<WaveformOverlayInstance> overlays) {
        this.overlays = overlays;
        invalidate();
    }

    /** Map of audioSourceRef → extracted data. */
    public void setData(@NonNull Map<String, WaveformData> dataBySource) {
        this.dataBySource = dataBySource;
        invalidate();
    }

    /** Map of audioSourceRef -> the source's Uri (what a VisLab look hears). */
    public void setSourceUris(@NonNull Map<String, Uri> uris) {
        this.sourceUris = uris;
        invalidate();
    }

    /** Where each source's transcript is: the words a lyrics look shows (S5). */
    public void setTranscriptSource(@Nullable TranscriptSource src) {
        this.transcripts = src;
        invalidate();
    }

    /** The clip's transcript words for a VisLab item, rebuilt only when a word changed. */
    @Nullable
    private com.fadcam.vislab.studio.StudioWords visWordsFor(@NonNull WaveformOverlayInstance o) {
        String ref = o.getAudioSourceRef();
        com.fadcam.ui.faditor.transcript.Transcript t =
                ref != null && transcripts != null ? transcripts.forSource(ref) : null;
        long key = com.fadcam.vislab.studio.StudioWords.keyOf(t);
        Long had = visWordsKey.get(o.getId());
        if (had == null || had != key) {
            visWordsKey.put(o.getId(), key);
            visWords.put(o.getId(), com.fadcam.vislab.studio.StudioWords.of(t));
        }
        return visWords.get(o.getId());
    }

    /** A VisLab look's file text, or null (gone: the classic style draws instead). */
    @Nullable
    private String visLookJson(@NonNull String id) {
        VisLabSession s = VisLabSession.get(getContext());
        String json = s.lookJson(id);
        if (json == null && !looksAsked) {
            looksAsked = true;   // the person's own looks may not be read yet: draw again then
            s.whenLoaded(this::invalidate);
        }
        return json;
    }

    /** Report extraction progress (0..1) for a source so its placeholder fills as it decodes. */
    public void setExtractionProgress(@NonNull String sourceRef, float fraction) {
        progressBySource.put(sourceRef, fraction);
        invalidate();
    }

    public void setPlayheadMs(long ms) {
        this.playheadMs = ms;
        invalidate();
    }

    /** The visualizers the GL composite draws (R11); this view keeps their frames instead. */
    public void setGlOwned(@NonNull java.util.Set<String> ids) {
        if (glOwned.equals(ids)) return;
        glOwned.clear();
        glOwned.addAll(ids);
        glRasters.keySet().retainAll(ids);
        glSources.keySet().retainAll(ids);
        glAlphas.keySet().retainAll(ids);
        invalidate();
    }

    /** True when visualizer {@code o} should be drawn by the GL composite (a non-Normal blend). */
    public static boolean wantsGlBlend(@NonNull WaveformOverlayInstance o) {
        return !o.isHidden() && !"NORMAL".equals(o.getOverlayBlendMode());
    }

    /**
     * The latest frame of a GL-owned visualizer, box-sized, its own copy (the GL thread may still
     * be reading the one before, so none is ever recycled here), or null before the first.
     */
    @Nullable
    public Bitmap glRasterFor(@NonNull String id) {
        return glRasters.get(id);
    }

    /** Its alpha this frame (fade, opacity, VisLab Fade), 1 when not known. */
    public float glAlphaFor(@NonNull String id) {
        Float a = glAlphas.get(id);
        return a != null ? a : 1f;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || overlays.isEmpty()) return;
        for (WaveformOverlayInstance o : overlays) {
            if (playheadMs < o.getStartMs() || playheadMs > o.getEndMs()) continue;
            // SPEC_VIZ_ENGINE §4 (Layers UI lane): resolve through the shared helper so an instance's
            // inline custom layer stack wins over the styleId preset (a customized visualizer previews
            // exactly as it will export). Overrides (colour/gradient/sensitivity) still layer on top.
            WaveformStyle style = WaveformStyleIO.resolveEffectiveStyle(o, styles.get(o.getStyleId()));
            if (style == null) continue;
            WaveformData data = o.getAudioSourceRef() != null
                    ? dataBySource.get(o.getAudioSourceRef()) : null;

            // WORLD pose (a visualizer that follows a parent is drawn where the parent takes it).
            int rw = Math.max(1, (int) (o.animatedWidthFraction(playheadMs) * w));
            int rh = Math.max(1, (int) (o.animatedHeightFraction(playheadMs) * h));
            // A VisLab look (VISLAB_SPEC section 7): StudioVis draws it on its own thread from the
            // source's sound; this frame shows its newest finished picture (none at first) and
            // asks for this moment, then invalidates when it is in.
            String visId = o.visLookAt(playheadMs);   // its look keys can change the look
            String visJson = visId != null ? visLookJson(visId) : null;
            Bitmap vis = null;
            if (visJson != null) {
                float s = Math.min(1f, VIS_PREVIEW_MAX / Math.max(rw, rh));
                String ref = o.getAudioSourceRef();
                vis = StudioVis.get(getContext()).previewFrame(StudioVis.previewKey(o.getId()), visJson,
                        ref != null ? sourceUris.get(ref) : null, o.mapToSourceMs(playheadMs), playheadMs,
                        Math.max(1, Math.round(rw * s)), Math.max(1, Math.round(rh * s)),
                        // This view is the canvas, so its shape is the frame's (look layers
                        // following an object, R8).
                        com.fadcam.vislab.studio.VisControls.at(o, playheadMs,
                                (float) getWidth() / Math.max(1, getHeight())),
                        visWordsFor(o), this::invalidate);
            }
            // Placeholder until the audio finishes extracting, then the live render.
            // renderReusable returns a shared, reused bitmap (no per-frame allocation);
            // it's drawn to the canvas immediately, so the next overlay can reuse it.
            Bitmap bmp = null;
            if (visJson != null) {
                // drawn below, scaled into the item's box
            } else if (data == null) {
                Float prog = o.getAudioSourceRef() != null
                        ? progressBySource.get(o.getAudioSourceRef()) : null;
                bmp = renderer.renderPlaceholderReusable(style, rw, rh, density,
                        prog != null ? prog : -1f);
            } else {
                bmp = renderer.renderReusable(data, style, rw, rh,
                        o.mapToSourceMs(playheadMs), density,
                        o.getJustify(), o.getDataMode(), o.isHorizontalMirror(),
                        o.getCenterMode(), o.getRenderMode(), o.getRadialRingSize(),
                        o.getFrequencyRangeLowHz(), o.getFrequencyRangeHighHz(),
                        o.getBandCountOverride());
            }
            // BLEND MODE (o.getOverlayBlendMode()): drawn NORMAL here, on purpose. The export blends
            // the visualizer against the frame beneath it (VisualizerBlendGlEffect), but this View
            // is a software layer above the whole video stack: a Paint blend mode on these draws
            // would blend against this View's own transparent backing store, not the video, so it
            // would do nothing, and View.setLayerPaint cannot stand in either (Skia's mode list is
            // not the 26 GLSL equations the export runs, and a hardware layer would break the
            // immediate-copy bitmap reuse and the glow below). A true preview means feeding the
            // visualizer's frame into the GL composite (FxPreviewTextureView) as a Pip, which sits
            // in fx_below_group under this View: a separate piece of work, so the drawer's Blend
            // tab says "applied when you export" rather than pretending.
            float cx = o.animatedCenterX(playheadMs) * w;
            float cy = o.animatedCenterY(playheadMs) * h;
            canvas.save();
            float rot = o.animatedRotation(playheadMs);
            if (rot != 0f) canvas.rotate(rot, cx, cy);
            // FADE_KNOBS §2.5: the knob fade multiplies the visualizer's alpha (§2.2 "same idea
            // for audio" — display fades even though no audio is muted by it).
            float fadeAlpha = o.fadeFactorAt(playheadMs) * o.animatedOpacity(playheadMs);
            if (glOwned.contains(o.getId())) {
                // Drawn by the GL composite with its blend mode: keep the frame, paint nothing.
                Bitmap src = visJson != null ? vis : bmp;
                if (src != null && !src.isRecycled()) {
                    // A VisLab frame is a new Bitmap per picture: copy only a new one. A classic
                    // frame reuses one Bitmap, so its pixels must be copied every time.
                    if (visJson == null || glSources.get(o.getId()) != src) {
                        glRasters.put(o.getId(), src.copy(Bitmap.Config.ARGB_8888, false));
                        glSources.put(o.getId(), visJson != null ? src : null);
                    }
                }
                glAlphas.put(o.getId(), visJson != null ? fadeAlpha * o.visFadeAt(playheadMs) : fadeAlpha);
            } else if (visJson != null) {
                if (vis != null && !vis.isRecycled()) {
                    if (visPaint == null) visPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    float a = fadeAlpha * o.visFadeAt(playheadMs);   // the VisLab Fade, keyframable
                    visPaint.setAlpha(Math.round(Math.max(0f, Math.min(1f, a)) * 255f));
                    visDst.set(cx - rw / 2f, cy - rh / 2f, cx + rw / 2f, cy + rh / 2f);
                    canvas.drawBitmap(vis, null, visDst, visPaint);
                }
            } else if (fadeAlpha >= 0.999f) {
                canvas.drawBitmap(bmp, cx - rw / 2f, cy - rh / 2f, null);
            } else {
                if (fadePaint == null) fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                fadePaint.setAlpha(Math.round(Math.max(0f, Math.min(1f, fadeAlpha)) * 255f));
                canvas.drawBitmap(bmp, cx - rw / 2f, cy - rh / 2f, fadePaint);
            }
            if (o == selected) {
                float left = cx - rw / 2f, top = cy - rh / 2f;
                float right = cx + rw / 2f, bottom = cy + rh / 2f;
                canvas.drawRect(left, top, right, bottom, selectionPaint);
                // Draw corner + edge-midpoint handle dots so the user knows they can drag to resize.
                float hs = 6f * density; // visual handle radius
                float midX = (left + right) / 2f, midY = (top + bottom) / 2f;
                float[][] hp = {
                        {left, top}, {right, top}, {left, bottom}, {right, bottom}, // corners
                        {midX, top}, {midX, bottom}, {left, midY}, {right, midY}  // edge midpoints
                };
                for (float[] p : hp) canvas.drawCircle(p[0], p[1], hs, handlePaint);
            }
            canvas.restore();
        }
    }

    /** Preview drawing yields all gestures to the shared transform controls beneath it. */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }
}
