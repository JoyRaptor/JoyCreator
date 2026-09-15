package com.fadcam.ui.faditor.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
 *
 * <h3>Why a FrameLayout now (2026-08-09, WYSIWYG reframe)</h3>
 * The "preview IS the textbox" reframe puts the text input ON the box: a transparent
 * {@link EditText} child layered over the drawn glyphs gives the user the framework's caret,
 * selection handles and IME exactly where the rendered text is, while the glyphs below keep
 * coming from {@code TextBoxRenderer} (the export's renderer — so what you type is what exports).
 * The view was a plain {@code View}; it is a {@code FrameLayout} so the input surface can be a
 * child. Children draw AFTER {@link #onDraw}, so a transparent child cannot cover the glyphs.
 */
public class TextBoxView extends FrameLayout {

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

    /**
     * The drawer's live selection (display-string indices), both {@code < 0} to draw none.
     * The preview draws a highlight over exactly these characters (W5-2 §3.8); the export
     * never receives one — drawn by this view only, never by TextBoxRenderer's other callers.
     */
    private int selStart = -1;
    private int selEnd = -1;

    /**
     * The in-canvas WYSIWYG input surface (the 2026-08-09 reframe): a transparent EditText laid
     * over the BOX (inside the excursion margin) while this item's drawer is open. Its text is
     * invisible, its caret and selection handles are not — so typing lands visually on the
     * renderer's glyphs below, and the two never have to agree on glyph metrics to line up.
     * The layer owns the instance; this view only hosts it and keeps its insets in sync.
     */
    @Nullable private EditText editor;

    /** Whether the in-canvas editor is currently attached (drawer open). */
    protected boolean hasEditor() {
        return editor != null;
    }

    public TextBoxView(@NonNull Context ctx, @NonNull TextOverlayItem o) {
        super(ctx);
        this.item = o;
        // The renderer paints every pixel this view shows, including its background pill, so the
        // view must not also draw one.
        setWillNotDraw(false);
    }

    /** Set the drawer's selection to highlight, or pass both negative to clear it. */
    public void setSelection(int start, int end) {
        if (this.selStart != start || this.selEnd != end) {
            this.selStart = start;
            this.selEnd = end;
            invalidate();
        }
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
        updateEditorInsets();
        invalidate();
        return resized;
    }

    /**
     * Host the layer's in-canvas input surface over the box (2026-08-09 WYSIWYG reframe).
     * Re-attaching the SAME instance is a no-op apart from the inset refresh — the layer
     * rebuilds this view on every keystroke, so it re-parents the editor into the fresh box.
     */
    public void attachEditor(@NonNull EditText e) {
        // Snapshot the selection BEFORE any detach: the rebuild path re-parents the editor
        // through this method (old box → fresh box), and detaching an EditText collapses its
        // selection to a caret, firing onSelectionChanged(selStart==selEnd). If that collapse
        // reaches the session, the drawer forgets the range being styled and the next toggle
        // falls back to whole-item formatting. Restoring the range here keeps the user's
        // selection alive across every rebuild (a style tap rebuilds the preview layer).
        int ss = e.getSelectionStart();
        int se = e.getSelectionEnd();
        if (editor != null && editor != e) {
            removeView(editor);
        }
        editor = e;
        if (e.getParent() != this) {
            // The layer rebuilds this box on every reflow mid-EDIT; the editor's previous
            // parent is the OLD box, already evicted from the tree. Reparent, don't duplicate.
            if (e.getParent() != null) {
                ((android.view.ViewGroup) e.getParent()).removeView(e);
            }
            addView(e, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        updateEditorInsets();
        // Undo the detach-collapse: a real range is restored, a caret is left alone.
        if (ss >= 0 && se > ss) {
            int len = e.getText().length();
            if (ss <= len && se <= len) {
                e.setSelection(ss, se);
            }
        }
    }

    /** Detach the in-canvas input surface (drawer closed — the box needs it no more). */
    public void detachEditor() {
        if (editor != null) {
            removeView(editor);
            editor = null;
        }
    }

    /**
     * Keep the editor EXACTLY over the box, inside the excursion margin: the box is the view
     * inset by {@link #boxInsetPx()} on every side. Called from {@link #bind} because the inset
     * moves when the type size moves.
     *
     * <p>Also keeps the editor's own text layout matching the renderer's: same type size
     * ({@link #fontPx}) and, via {@code setIncludeFontPadding(false)}, the same 1.0-multiplier
     * line height — otherwise the editor wraps text at different character counts than
     * {@code TextBoxRenderer} draws, and a tap or drag lands on a different character than the
     * one under the finger.</p>
     */
    private void updateEditorInsets() {
        if (editor == null) return;
        // The caret and hit-testing must follow the glyphs the renderer draws below.
        if (editor.getTextSize() != Math.max(1f, fontPx)) {
            editor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(1f, fontPx));
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) editor.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT);
        }
        // THE INK PAD COUNTS TOO, or the caret floats above the glyphs.
        //
        // TextBoxRenderer lays its first baseline at `top + pad - maxAscent` with
        // pad = fontPx * padEm() (0.35em on every side). The editor has includeFontPadding=false
        // and is top-aligned, so ITS first baseline is `top - ascent` — higher than the drawn
        // text by exactly one pad. At 0.35em that is roughly a fifth of the line, which is what
        // "the text edit indicator is vertically about 20% too high" describes (JoyRaptor,
        // 2026-08-14).
        //
        // It was never only cosmetic. The caret, the native selection handles and the character
        // hit-testing all come from this view's layout, so every tap to place a caret was being
        // resolved against glyphs drawn a pad lower than where the editor thought they were.
        // Adding the pad on all four sides puts the editor's text box exactly over the
        // renderer's, which is also what keeps the two wrapping at the same character.
        int i = Math.round(boxInsetPx() + fontPx * TextBoxRenderer.padEm());
        if (lp.leftMargin != i || lp.topMargin != i
                || lp.rightMargin != i || lp.bottomMargin != i) {
            lp.leftMargin = i;
            lp.topMargin = i;
            lp.rightMargin = i;
            lp.bottomMargin = i;
            editor.setLayoutParams(lp);
        }
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
                projectDurationMs, animate, objectAlpha, selStart, selEnd);
    }
}
