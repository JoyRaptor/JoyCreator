package com.fadcam.ui.faditor.waveform;

import com.fadcam.ui.faditor.Studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

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
    private boolean looksAsked;

    /** Callbacks to the host so changes persist / overlays can be removed. */
    public interface OnChangeListener {
        void onWaveformChanged();
        void onWaveformDeleted(@NonNull WaveformOverlayInstance overlay);
        /** Re-tapping an already-selected visualizer — open the style chooser. */
        default void onWaveformTapped(@NonNull WaveformOverlayInstance overlay) {}
        /**
         * Hold on a visualizer — open its settings drawer (gesture contract §4.5).
         * Deletion is NOT on this gesture: it lives on the timeline row's trash-can
         * badge. The default used to fall through to an instant, confirm-less delete,
         * which meant a hold anywhere near a visualizer could destroy it; it now
         * routes to the same place a re-tap goes.
         */
        default void onWaveformLongPressed(@NonNull WaveformOverlayInstance overlay) {
            onWaveformTapped(overlay);
        }
    }

    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Fade-knob alpha paint (FADE_KNOBS §2.5) — lazily created, only when a fade is active. */
    @androidx.annotation.Nullable private Paint fadePaint;
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private OnChangeListener changeListener;
    @Nullable private WaveformOverlayInstance selected;
    private int dragMode = 0; // 0 none, 1 move, 2 pinch-resize, 3 handle-resize
    private float downX, downY, startCenterX, startCenterY, startW, startH, startDist;
    private boolean movedSinceDown;
    private boolean reTapSelected; // the down hit was already the selected overlay
    @Nullable private Runnable longPressRunnable;
    // Handle resize state: which handle (1..8) + the fixed bounding-box edges at touch start.
    private int resizeHandle = 0; // 1=TL 2=TR 3=BL 4=BR 5=T 6=B 7=L 8=R
    private float resizeStartLeft, resizeStartTop, resizeStartRight, resizeStartBottom;

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

    public void setOnChangeListener(@Nullable OnChangeListener l) {
        this.changeListener = l;
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
                        com.fadcam.vislab.studio.VisControls.at(o, playheadMs), visWordsFor(o), this::invalidate);
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
            if (visJson != null) {
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

    // ── Touch: select / move / pinch-resize / long-press delete ──────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float tx = e.getX(), ty = e.getY();
                // First: if an overlay is already selected, check for a resize-handle hit.
                if (selected != null && playheadMs >= selected.getStartMs() && playheadMs <= selected.getEndMs()) {
                    int handle = hitTestHandle(tx, ty, w, h, selected);
                    if (handle > 0) {
                        dragMode = 3;
                        resizeHandle = handle;
                        movedSinceDown = false;
                        float cx = selected.animatedCenterX(playheadMs) * w;
                        float cy = selected.animatedCenterY(playheadMs) * h;
                        float rw = selected.animatedWidthFraction(playheadMs) * w;
                        float rh = selected.animatedHeightFraction(playheadMs) * h;
                        resizeStartLeft = cx - rw / 2f;
                        resizeStartTop = cy - rh / 2f;
                        resizeStartRight = cx + rw / 2f;
                        resizeStartBottom = cy + rh / 2f;
                        cancelPendingLongPress();
                        return true;
                    }
                }
                WaveformOverlayInstance hit = hitTest(tx, ty, w, h);
                if (hit == null) {
                    if (selected != null) { selected = null; invalidate(); }
                    return false; // let the touch fall through to the views below
                }
                reTapSelected = (hit == selected);
                selected = hit;
                dragMode = 1;
                movedSinceDown = false;
                downX = tx;
                downY = ty;
                // World position at the grab: the drag moves what the eye sees.
                startCenterX = hit.animatedCenterX(playheadMs);
                startCenterY = hit.animatedCenterY(playheadMs);
                scheduleLongPress(hit);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (selected != null && e.getPointerCount() >= 2) {
                    cancelPendingLongPress();
                    dragMode = 2;
                    startDist = spacing(e);
                    startW = selected.getWidthFraction();
                    startH = selected.getHeightFraction();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (selected == null) return false;
                if (dragMode == 3) {
                    doHandleResize(e.getX(), e.getY(), w, h);
                    movedSinceDown = true;
                } else if (dragMode == 2 && e.getPointerCount() >= 2) {
                    float d = spacing(e);
                    float s = startDist > 0 ? d / startDist : 1f;
                    selected.setSize(startW * s, startH * s);
                    movedSinceDown = true;
                } else                 if (dragMode == 1) {
                    float dx = (e.getX() - downX) / w;
                    float dy = (e.getY() - downY) / h;
                    if (Math.abs(e.getX() - downX) > 8 || Math.abs(e.getY() - downY) > 8) {
                        movedSinceDown = true;
                        cancelPendingLongPress();
                    }
                    if (selected.isLinked()) {
                        float[] own = new float[2];
                        selected.worldToOwn(startCenterX + dx, startCenterY + dy, playheadMs, own);
                        selected.setCenter(own[0], own[1]);
                    } else {
                        selected.setCenter(startCenterX + dx, startCenterY + dy);
                    }
                }
                // A follower is placed by its parent; clamping its own pose would fight that.
                if (!selected.isLinked()) clampToCanvas(selected);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                dragMode = 0; // require a fresh press to continue (avoids post-pinch jump)
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                cancelPendingLongPress();
                if (selected != null && changeListener != null) {
                    if (movedSinceDown) {
                        changeListener.onWaveformChanged();
                    } else if (reTapSelected && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        // Clean re-tap on an already-selected visualizer → open the style chooser.
                        changeListener.onWaveformTapped(selected);
                    }
                }
                dragMode = 0;
                return true;
            }
            default:
                return selected != null;
        }
    }

    private void scheduleLongPress(@NonNull WaveformOverlayInstance target) {
        cancelPendingLongPress();
        longPressRunnable = () -> {
            if (!movedSinceDown && selected == target && changeListener != null) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                changeListener.onWaveformLongPressed(target);
            }
        };
        handler.postDelayed(longPressRunnable, 500);
    }

    private void cancelPendingLongPress() {
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private static float spacing(@NonNull MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    @Nullable
    private WaveformOverlayInstance hitTest(float x, float y, int w, int h) {
        for (int i = overlays.size() - 1; i >= 0; i--) {
            WaveformOverlayInstance o = overlays.get(i);
            if (playheadMs < o.getStartMs() || playheadMs > o.getEndMs()) continue;
            float cx = o.animatedCenterX(playheadMs) * w, cy = o.animatedCenterY(playheadMs) * h;
            float rw = o.animatedWidthFraction(playheadMs) * w;
            float rh = o.animatedHeightFraction(playheadMs) * h;
            if (x >= cx - rw / 2f && x <= cx + rw / 2f && y >= cy - rh / 2f && y <= cy + rh / 2f) {
                return o;
            }
        }
        return null;
    }

    /** Hit-test the 8 resize handles of the selected overlay. Returns 1..8 or 0 if no hit. */
    private int hitTestHandle(float x, float y, int w, int h, @NonNull WaveformOverlayInstance o) {
        float cx = o.animatedCenterX(playheadMs) * w, cy = o.animatedCenterY(playheadMs) * h;
        float rw = o.animatedWidthFraction(playheadMs) * w;
        float rh = o.animatedHeightFraction(playheadMs) * h;
        float left = cx - rw / 2f, top = cy - rh / 2f;
        float right = cx + rw / 2f, bottom = cy + rh / 2f;
        float midX = (left + right) / 2f, midY = (top + bottom) / 2f;
        float tol = 20f * density; // touch target radius
        float[][] hp = {
            {left, top}, {right, top}, {left, bottom}, {right, bottom}, // 1..4 corners
            {midX, top}, {midX, bottom}, {left, midY}, {right, midY}     // 5..8 edges
        };
        for (int i = 0; i < hp.length; i++) {
            if (Math.abs(x - hp[i][0]) <= tol && Math.abs(y - hp[i][1]) <= tol) return i + 1;
        }
        return 0;
    }

    /** Resize the selected overlay based on which handle is being dragged + current touch. */
    private void doHandleResize(float tx, float ty, int w, int h) {
        float left = resizeStartLeft, top = resizeStartTop;
        float right = resizeStartRight, bottom = resizeStartBottom;
        switch (resizeHandle) {
            case 1: left = tx; top = ty; break;                          // TL
            case 2: right = tx; top = ty; break;                          // TR
            case 3: left = tx; bottom = ty; break;                        // BL
            case 4: right = tx; bottom = ty; break;                        // BR
            case 5: top = ty; break;                                      // T
            case 6: bottom = ty; break;                                    // B
            case 7: left = tx; break;                                     // L
            case 8: right = tx; break;                                     // R
            default: return;
        }
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(w, right);
        bottom = Math.min(h, bottom);
        if (right <= left) right = left + 4 * density;
        if (bottom <= top) bottom = top + 4 * density;
        float newW = (right - left) / w;
        float newH = (bottom - top) / h;
        if (selected.isLinked()) {
            // Handles are drawn in the picture; a follower stores its own pose.
            float[] own = new float[2];
            selected.worldToOwn((left + right) / 2f / w, (top + bottom) / 2f / h, playheadMs, own);
            selected.setSize(selected.worldSizeToOwn(newW, playheadMs),
                    selected.worldSizeToOwn(newH, playheadMs));
            selected.setCenter(own[0], own[1]);
            return;
        }
        selected.setSize(newW, newH);
        selected.setCenter((left + right) / 2f / w, (top + bottom) / 2f / h);
    }

    private void clampToCanvas(@NonNull WaveformOverlayInstance o) {
        float hw = o.getWidthFraction() / 2f;
        float hh = o.getHeightFraction() / 2f;
        float cx = Math.max(hw, Math.min(1f - hw, o.getCenterX()));
        float cy = Math.max(hh, Math.min(1f - hh, o.getCenterY()));
        o.setCenter(cx, cy);
    }
}
