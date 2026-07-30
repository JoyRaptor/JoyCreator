package com.fadcam.ui.faditor.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.transcript.CaptionAnimator;

/**
 * The live-preview surface for one TEXT overlay — a plain {@link View} that draws its glyphs
 * through {@link TextBoxRenderer}, the same renderer the export calls.
 *
 * <h3>What this replaced, and why</h3>
 * A text overlay used to be an Android {@code TextView}. One view holding one string cannot move
 * individual characters, which is the whole reason text boxes were restricted to BLOCK
 * granularity while captions had LETTER / WORD / SENTENCE. Swapping it for a view that draws the
 * text itself is what lifts that restriction — and doing it through a SHARED renderer rather than
 * a preview-only one is what stops the restriction being replaced by a silent preview/export
 * divergence, which is the failure mode this whole area exists to prevent.
 *
 * <h3>The excursion margin</h3>
 * This is the one genuinely new problem the swap creates, and it has bitten every editor that has
 * done it. The old {@code TextView} was moved by VIEW properties — {@code translationY} and
 * friends — so a rising or scattering box moved bodily and could never clip itself. Now the
 * motion happens INSIDE {@code onDraw}, and a view's drawing is clipped to its own bounds. A RISE
 * that starts {@code 0.9em} below the baseline, or an UNSCRAMBLE that starts {@code 1.6em} away,
 * would be sliced off at the view edge for the whole entrance — and it would look like a
 * rendering bug rather than a clipped view, because the text would simply be missing a chunk.
 *
 * <p>So the view is deliberately LARGER than the text box by {@link #EXCURSION_EM} on every side,
 * and the box is drawn inset by that much. {@link #boxInsetPx} is public because the layer has to
 * place the view by the BOX's centre, not the view's — get that wrong and every text box on the
 * timeline shifts.
 *
 * <p>The margin is sized from the furthest any implemented preset travels: UNSCRAMBLE's scatter
 * radius (1.6em) is the largest, ahead of RISE (0.9em) and BEAM's 2x vertical scale (~0.6em each
 * way on a 1.2em glyph). It is deliberately not computed per preset — a margin that changed size
 * when the preset changed would re-measure and re-layout the box on every pick, and a slightly
 * oversized transparent margin costs nothing.
 */
public class TextBoxView extends View {

    /**
     * Slack around the box for glyphs that animate outside it, as a multiple of the type size.
     * See the class javadoc — this is sized from UNSCRAMBLE, the furthest-travelling preset.
     */
    public static final float EXCURSION_EM = 1.8f;

    @NonNull private TextOverlayItem item;
    @NonNull private String text = "";
    private float fontPx = 1f;
    private long mediaMs;
    private long projectDurationMs;
    /** False while the user drags this object, so it follows the finger rather than the tape. */
    private boolean animate = true;
    /**
     * The object's own keyframed opacity. Passed INTO the renderer rather than applied with
     * {@code setAlpha} on this view, because the export has no view to set alpha on and would
     * otherwise composite the object's opacity at a different stage than the preview does.
     */
    private float objectAlpha = 1f;

    public TextBoxView(@NonNull Context ctx, @NonNull TextOverlayItem o) {
        super(ctx);
        this.item = o;
        // The renderer paints every pixel this view shows, including its background pill, so the
        // view must not also draw one.
        setWillNotDraw(false);
    }

    /**
     * Everything that can change between frames, in one call — deliberately, so a caller cannot
     * update the time and forget the string, which on a timer or on MATRIX would draw a stale
     * frame. Returns true when the box's SIZE changed and the caller must re-layout.
     */
    public boolean bind(@NonNull TextOverlayItem o, @NonNull String text, float fontPx,
                        long mediaMs, long projectDurationMs, boolean animate,
                        float objectAlpha) {
        boolean resized = !this.text.equals(text) || this.fontPx != fontPx || this.item != o;
        this.item = o;
        this.text = text;
        this.fontPx = fontPx;
        this.mediaMs = mediaMs;
        this.projectDurationMs = projectDurationMs;
        this.animate = animate;
        this.objectAlpha = objectAlpha;
        applyBlurLayerPolicy();
        invalidate();
        return resized;
    }

    /** Half the difference between this view and the text box it contains, in px. */
    public float boxInsetPx() {
        return fontPx * EXCURSION_EM;
    }

    /**
     * The view's size: the box from {@link TextBoxRenderer#measure} plus the excursion margin on
     * all four sides.
     *
     * @param out receives {@code {width, height}}
     */
    public void measureView(@NonNull float[] out) {
        TextBoxRenderer.measure(item, text, fontPx, out);
        float m = boxInsetPx() * 2f;
        out[0] += m;
        out[1] += m;
    }

    /**
     * The preview's half of the GHOST-blur decision.
     *
     * <p>{@code BlurMaskFilter} is ignored on a hardware-accelerated canvas and honoured on a
     * software one, so a box whose preset blurs has to be drawn through a software layer or the
     * export would blur and the preview would not — the exact divergence
     * {@code TextBoxRenderer} exists to prevent.
     *
     * <p><b>Why it is safe to just do this, rather than an export-only divergence.</b> The cost
     * was measured rather than assumed (numbers on {@link CaptionAnimator#presetBlurs}): about
     * +0.4ms per draw, ~2.4% of a 16.7ms frame. The spec had recorded the price as "every frame
     * of playback", which was pessimistic on two counts — the preview draws each text box in its
     * OWN view, so only a blurring box pays, and it pays only while it is on screen. A project
     * with no GHOST box pays exactly nothing.
     *
     * <p>Applied on every {@link #bind} because the preset can change under a live view when the
     * user picks in the animation popover; {@link View#setLayerType} is a no-op when the type is
     * already what is asked for, so this is not a per-frame cost.
     */
    private void applyBlurLayerPolicy() {
        boolean blurs = CaptionAnimator.presetBlurs(
                animate ? CaptionAnimator.parsePreset(item.getTextAnimPreset())
                        : CaptionAnimator.Preset.NONE);
        setLayerType(blurs ? LAYER_TYPE_SOFTWARE : LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float inset = boxInsetPx();
        TextBoxRenderer.draw(canvas, item, text, inset, inset, fontPx, mediaMs,
                projectDurationMs, animate, objectAlpha);
    }
}
