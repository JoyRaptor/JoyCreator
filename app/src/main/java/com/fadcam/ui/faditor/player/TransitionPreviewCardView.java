package com.fadcam.ui.faditor.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Transition;

/**
 * Small self-contained, looping + scrubbable preview of a transition (outgoing sample → incoming
 * sample) via {@link TransitionRenderer}. Used in the transitions drawer so each card actually DEMOS
 * the effect instead of a static picture. Drag left/right to scrub it manually.
 */
public class TransitionPreviewCardView extends View {

    private static Bitmap sampleA, sampleB; // shared across all cards (two distinct frames)

    @Nullable private Transition transition;
    @Nullable private Transition previewTransition; // GL shaders preview via a category-proxy animation
    private boolean hasOptions; // direction/params → show a "+" badge
    private float progress;

    // For GL shaders: once a real baked sprite strip is ready we frame-cycle it instead of the proxy.
    @Nullable private Bitmap glStrip;
    @Nullable private String glStripId;
    private final android.graphics.Rect stripSrc = new android.graphics.Rect();
    private final RectF stripDst = new RectF();
    private final Paint stripPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    @Nullable private ValueAnimator animator;
    private final float density;
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TransitionPreviewCardView(Context context) { this(context, null); }

    public TransitionPreviewCardView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        this.density = getResources().getDisplayMetrics().density;
    }

    public void setTransition(@NonNull Transition transition) {
        this.transition = transition;
        // GL shaders can't run in this Canvas card, so the preview animates a CATEGORY-representative
        // basic transition — at least the cards differ instead of all looking identical. (The real GL
        // shader still bakes correctly on export/preview; this is only the tiny drawer thumbnail.)
        this.previewTransition = (transition.type == Transition.Type.GL_SHADER
                && transition.glTransitionId != null) ? glPreviewProxy(transition) : transition;
        // Flexible transitions (direction-able wipes/pushes, GL effects) get a "+" badge so the user
        // knows there are extra options/helpers when they pick one.
        this.hasOptions = transition.isWipe() || transition.isPush() || transition.isGlShader();
        maybeRequestGlStrip(transition);
        invalidate();
    }

    /**
     * For GL shader cards: try to show the REAL baked effect (sprite strip) instead of the category
     * proxy. If a strip is already baked we use it immediately; otherwise we ask the baker to produce
     * one off the main thread and keep drawing the proxy until it swaps in. Failure never blocks: the
     * proxy simply stays. See {@link com.fadcam.ui.faditor.gltransitions.GlTransitionCardBaker}.
     */
    private void maybeRequestGlStrip(@NonNull Transition t) {
        String id = (t.type == Transition.Type.GL_SHADER) ? t.glTransitionId : null;
        if (id == null) {
            glStrip = null;
            glStripId = null;
            return;
        }
        if (id.equals(glStripId) && glStrip != null && !glStrip.isRecycled()) return;
        glStrip = null;
        glStripId = id;
        Bitmap ready = com.fadcam.ui.faditor.gltransitions.GlTransitionCardBaker.peek(id);
        if (ready != null) {
            glStrip = ready;
            return;
        }
        com.fadcam.ui.faditor.gltransitions.GlTransitionCardBaker.request(getContext(), id,
                (readyId, strip) -> {
                    // Card instances aren't recycled, but guard against a late callback for a stale id.
                    if (readyId.equals(glStripId) && !strip.isRecycled()) {
                        glStrip = strip;
                        invalidate();
                    }
                });
    }

    @NonNull
    private static Transition glPreviewProxy(@NonNull Transition t) {
        com.fadcam.ui.faditor.gltransitions.GLTransitionCatalog.Entry e =
                com.fadcam.ui.faditor.gltransitions.GLTransitionCatalog.find(t.glTransitionId);
        String cat = e != null ? e.category : "";
        Transition.Type type;
        switch (cat) {
            case "flash":            type = Transition.Type.FADE_OUT_TO_WHITE; break;
            case "strobe":           type = Transition.Type.GLITCH; break;
            case "wipe": case "warp":type = Transition.Type.WIPE_LEFT; break;
            case "slide":            type = Transition.Type.PUSH_LEFT; break;
            case "wave": case "mask":type = Transition.Type.RADIAL; break;
            case "3d":               type = Transition.Type.LINEAR_MIRROR_WIPE; break;
            default:                 type = Transition.Type.CROSS_DISSOLVE; break; // zoom/blur/color
        }
        return new Transition(type, t.durationMs, 0);
    }

    private void ensureSamples() {
        if (sampleA == null || sampleA.isRecycled()) {
            sampleA = loadSample(com.fadcam.R.drawable.transition_frame_a, true);
        }
        if (sampleB == null || sampleB.isRecycled()) {
            sampleB = loadSample(com.fadcam.R.drawable.transition_frame_b, false);
        }
    }

    /** Decode a real preview frame and centre-crop it to the card's sample aspect (200×130). */
    private Bitmap loadSample(int resId, boolean first) {
        Bitmap src = null;
        try {
            src = android.graphics.BitmapFactory.decodeResource(getResources(), resId);
        } catch (Throwable ignored) { }
        if (src == null) return makeSample(first); // synthetic fallback
        int tw = 200, th = 130;
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float scale = Math.max(tw / (float) src.getWidth(), th / (float) src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        RectF dst = new RectF((tw - dw) / 2f, (th - dh) / 2f, (tw + dw) / 2f, (th + dh) / 2f);
        c.drawBitmap(src, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        if (!src.isRecycled()) src.recycle();
        return out;
    }

    /** Synthetic fallback frames (used only if the bundled images fail to decode). */
    private Bitmap makeSample(boolean first) {
        int w = 200, h = 130;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int top = first ? 0xFF1E88E5 : 0xFFF4511E;     // blue vs deep-orange
        int bot = first ? 0xFF0D47A1 : 0xFFBF360C;
        p.setShader(new LinearGradient(0, 0, 0, h, top, bot, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0x66FFFFFF);
        if (first) {
            c.drawCircle(w * 0.5f, h * 0.5f, h * 0.28f, p);
        } else {
            float s = h * 0.26f;
            c.drawRect(w * 0.5f - s, h * 0.5f - s, w * 0.5f + s, h * 0.5f + s, p);
        }
        p.setColor(0xFFFFFFFF);
        p.setTextSize(h * 0.5f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(first ? "A" : "B", w * 0.5f, h * 0.5f + h * 0.18f, p);
        return bmp;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureSamples();
        startAnim();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnim();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) startAnim(); else stopAnim();
    }

    private void startAnim() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1500);
        animator.setStartDelay(250);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopAnim() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    /** Blit the sprite-strip frame nearest the current animation progress, stretched to the card. */
    private void drawGlStripFrame(@NonNull Canvas canvas, int w, int h) {
        int frames = com.fadcam.ui.faditor.gltransitions.GlTransitionCardBaker.FRAME_COUNT;
        int fw = glStrip.getWidth() / frames;
        int fh = glStrip.getHeight();
        int idx = Math.round(Math.max(0f, Math.min(1f, progress)) * (frames - 1));
        int left = idx * fw;
        stripSrc.set(left, 0, left + fw, fh);
        stripDst.set(0, 0, w, h);
        canvas.drawBitmap(glStrip, stripSrc, stripDst, stripPaint);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || transition == null) return;
        ensureSamples();
        float r = 6f * density;
        canvas.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(new RectF(0, 0, w, h), r, r, android.graphics.Path.Direction.CW);
        canvas.clipPath(clip);
        if (glStrip != null && !glStrip.isRecycled()) {
            drawGlStripFrame(canvas, w, h);
        } else {
            TransitionRenderer.compose(canvas, sampleA, sampleB,
                    previewTransition != null ? previewTransition : transition, progress, w, h);
        }
        canvas.restore();

        if (hasOptions) {
            float cx = w - 9f * density, cy = 9f * density, rad = 7f * density;
            badgePaint.setStyle(Paint.Style.FILL);
            badgePaint.setColor(0xCC1A1A1A);
            canvas.drawCircle(cx, cy, rad, badgePaint);
            badgePaint.setColor(0xFF4CAF50);
            badgePaint.setStrokeWidth(1.6f * density);
            float arm = rad * 0.5f;
            canvas.drawLine(cx - arm, cy, cx + arm, cy, badgePaint);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, badgePaint);
        }
    }
}
