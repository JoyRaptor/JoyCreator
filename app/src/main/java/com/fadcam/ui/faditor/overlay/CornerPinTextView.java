package com.fadcam.ui.faditor.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.CornerPin;
import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
     * The preview's text-overlay view: an ordinary {@link TextBoxView} that can ALSO draw its glyphs
     * through a full 3x3 {@link Matrix}, so a corner-pinned text box is possible at all.
     *
     * <p><b>Why a subclass and not a new View class.</b> A text overlay's placement, z-order,
     * hit-testing, in-canvas editor hosting, drawer selection highlight and the
     * {@code view instanceof TextBoxView} branches in {@code TextOverlayLayer.position} are all built
     * around this being a TextBoxView. Routing pinned text to a SEPARATE view type would have meant a
     * second copy of every one of those, and a class swap in the middle of playback the first time an
     * animated pin crossed zero. Subclassing keeps one view type for every text box, so nothing above
     * this file has to know which path is taken — exactly the reason {@code CornerPinImageView}
     * subclasses {@code ImageView} rather than replacing it.</p>
     *
     * <p><b>The fast path is literally the old path.</b> With no pin and no pin inset,
     * {@link #onDraw} calls {@code super.onDraw} and this view IS a {@code TextBoxView}, down to the
     * same renderer call with the same arguments. Nothing about the 99% case is re-implemented, so
     * nothing about it can regress.</p>
     *
     * <p><b>The pin inset.</b> A pinned corner is drawn OUTSIDE the box's own rectangle, and a child
     * View's drawing does not survive its parent's clip. So the layer lays this view out LARGER than
     * the box by the largest corner excursion and passes that margin here; the box is drawn into the
     * inset rect and the pulled corner lands in the margin. The margin rides inside
     * {@link #boxInsetPx()} — on top of the excursion margin the box already carries for animated
     * glyphs — so the layer's existing {@code boxInset} machinery centres the BOX on cx/cy, the
     * measure grows, and the in-canvas editor insets all follow with no second change. The inset is
     * zero for an unpinned item, which is what keeps the fast path byte-identical.</p>
     *
     * <p><b>Sharpness.</b> The pin is a matrix on glyph OUTLINES, not a warped raster: the matrix is
     * concat-ed around the same {@code TextBoxRenderer.draw} call the unpinned path makes, and the
     * glyphs are re-rasterised from their vectors through it every frame. A pinned box stays
     * vector-sharp; there is no bitmap anywhere in this path to go soft.</p>
     *
     * <p><b>No mirror here.</b> {@code CornerPinImageView} carries SPEC G mirror flags because an
     * image's flip is applied inside its view. A text box's flip has no view-level application on
     * either surface — {@code TextBoxRenderer} draws no mirror and the export concats none — so there
     * is nothing to order against the pin. If text ever gains a mirror, it belongs OUTSIDE the pin
     * about the unpinned box centre, the order the image path documents.</p>
     *
     * <p><b>Editing a pinned box.</b> While the in-canvas {@link EditText} is attached ({@link #editor}
     * is non-null), the pin is SUPPRESSED — the fast path is taken regardless of {@link #pinInsetPx}.
     * This keeps the caret and selection handles aligned with the undistorted glyphs the editor
     * renders, and prevents the editor's layout from fighting the homography. The pin re-engages
     * automatically when the drawer closes and {@link #detachEditor} nulls the editor.
     */
public class CornerPinTextView extends TextBoxView {

    /** Live corner offsets (fractions of the box's own size), packed as {@link CornerPin}. */
    private final float[] pin = new float[CornerPin.SIZE];
    /**
     * Margin on every side beyond the excursion margin, in px, holding the corner excursion.
     * 0 = unpinned fast path. Carried inside {@link #boxInsetPx()}, not beside it.
     */
    private float pinInsetPx;

    // Reused, never allocated in onDraw: this view is redrawn on every playhead tick.
    private final Matrix pinMatrix = new Matrix();

    // Snapshot of the last bind()/setSelection(), so the matrix path can replay the exact draw
    // the fast path would have made — through the same renderer with the same arguments — with
    // only the concat added. Every mutation flows through bind() or setSelection(), so the
    // snapshot cannot drift from what super.onDraw would draw.
    @NonNull private TextOverlayItem snapItem;
    @NonNull private String snapText = "";
    private float snapFontPx = 1f;
    private long snapMediaMs;
    private long snapProjectDurationMs;
    private boolean snapAnimate = true;
    private float snapObjectAlpha = 1f;
    private int snapSelStart = -1;
    private int snapSelEnd = -1;

    public CornerPinTextView(@NonNull Context ctx, @NonNull TextOverlayItem o) {
        super(ctx, o);
        snapItem = o;
    }

    /**
     * Set the corner pin for the frame about to be drawn.
     *
     * @param off8    the offsets at the current time, or null / all-zero for undistorted
     * @param insetPx the excursion margin the layer added on every side (0 when unpinned)
     */
    public void setCornerPin(@Nullable float[] off8, float insetPx) {
        boolean changed = this.pinInsetPx != insetPx;
        this.pinInsetPx = insetPx;
        for (int i = 0; i < CornerPin.SIZE; i++) {
            float v = (off8 != null && off8.length >= CornerPin.SIZE) ? off8[i] : 0f;
            if (pin[i] != v) { pin[i] = v; changed = true; }
        }
        // Only when something actually moved: this is called on every tick and an unconditional
        // invalidate would redraw the whole layer at rest.
        if (changed) invalidate();
    }

    /**
     * Half the difference between this view and the text box it contains, in px — the excursion
     * margin every text box carries, PLUS the corner-pin excursion when pinned.
     *
     * <p>Overridden (not shadowed) so every existing reader follows automatically: the layer's
     * layout, {@code measureView}, and the in-canvas editor insets all read this method, so the
     * box stays centred on cx/cy and the caret stays on the glyphs with no second change. Zero
     * added when unpinned, which is what keeps unpinned layout bit-for-bit.
     */
    @Override
    public float boxInsetPx() {
        return super.boxInsetPx() + pinInsetPx;
    }

    @Override
    public boolean bind(@NonNull TextOverlayItem o, @NonNull String text, float fontPx,
                        long mediaMs, long projectDurationMs, boolean animate,
                        float objectAlpha) {
        snapItem = o;
        snapText = text;
        snapFontPx = fontPx;
        snapMediaMs = mediaMs;
        snapProjectDurationMs = projectDurationMs;
        snapAnimate = animate;
        snapObjectAlpha = objectAlpha;
        return super.bind(o, text, fontPx, mediaMs, projectDurationMs, animate, objectAlpha);
    }

    @Override
    public void setSelection(int start, int end) {
        snapSelStart = start;
        snapSelEnd = end;
        super.setSelection(start, end);
    }

    /** True when this frame needs the matrix path rather than plain {@code TextBoxView} drawing. */
    private boolean usesMatrix() {
        // Suppress the pin while the in-canvas editor is attached: the editor renders undistorted
        // glyphs and its caret/hit-testing must match them. Taking the fast path here avoids a
        // caret/glyph misalignment that the audit caught.
        if (hasEditor()) return false;
        return pinInsetPx > 0.5f || !CornerPin.isFlat(pin);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        // BEND FIRST, if there is one. A mesh is a warp of the drawn PICTURE, so unlike the pin it
        // cannot stay on glyph outlines — the box is rasterised once and the lattice moves the
        // raster. That costs the vector sharpness the pin deliberately keeps, which is why the two
        // are separate features and why an unbent box never touches this path.
        //
        // The editor is suppressed here for the same reason the pin is: a caret drawn through a
        // warp does not land where the finger is.
        if (!hasEditor() && meshBend(canvas)) return;
        if (!usesMatrix()) {
            // THE FAST PATH — unchanged TextBoxView behaviour for every text box that has never
            // been pinned. Same renderer, same arguments, same inset.
            super.onDraw(canvas);
            return;
        }
        float inset = boxInsetPx();
        float w = getWidth() - inset * 2f;
        float h = getHeight() - inset * 2f;
        if (w <= 0f || h <= 0f) { super.onDraw(canvas); return; }

        // The box's own rect inside this (inflated) view, then the homography INNERMOST relative
        // to everything the parent applies. The View's rotation, scale and translation are applied
        // by the parent ABOVE this canvas, so a concat here is necessarily inside them — the
        // composition order the export mirrors deliberately (see
        // TextOverlayItem.cornerPinMatrix). The matrix comes from the model's ONE method both
        // surfaces call, so this cannot drift from the export. A refused solve (degenerate quad)
        // draws UNPINNED rather than nothing: readable text for a frame is recoverable, a blank
        // box reads as the overlay having vanished.
        canvas.save();
        if (snapItem.cornerPinMatrix(pinMatrix, snapMediaMs, inset, inset, w, h)) {
            canvas.concat(pinMatrix);
        }
        TextBoxRenderer.draw(canvas, snapItem, snapText, inset, inset, snapFontPx, snapMediaMs,
                snapProjectDurationMs, snapAnimate, snapObjectAlpha, snapSelStart, snapSelEnd);
        canvas.restore();
    }

    /** Shared with the export — see {@code CompositeExportOverlay}'s text branch. */
    private final com.fadcam.ui.faditor.sprite.SpriteMeshDraw meshDraw =
            new com.fadcam.ui.faditor.sprite.SpriteMeshDraw();
    private final android.graphics.RectF meshRect = new android.graphics.RectF();

    /**
     * Draw this box BENT, or return false and let the ordinary path run.
     *
     * <p>The pin is applied INSIDE the bend, exactly as it is on the unbent path and exactly as
     * the export does it: a box can be pinned and bent at once, and the order has to be the same
     * on both surfaces or the two disagree the moment anyone uses both.
     */
    private boolean meshBend(@NonNull Canvas canvas) {
        if (snapItem == null || !snapItem.hasMesh()) return false;
        final float inset = boxInsetPx();
        final float w = getWidth() - inset * 2f;
        final float h = getHeight() - inset * 2f;
        if (!(w > 1f) || !(h > 1f)) return false;
        meshRect.set(inset, inset, inset + w, inset + h);
        return meshDraw.draw(canvas, snapItem.getMesh(),
                snapItem.meshLocalTime(snapMediaMs), meshRect,
                (c, into) -> {
                    c.save();
                    if (snapItem.cornerPinMatrix(pinMatrix, snapMediaMs,
                            into.left, into.top, into.width(), into.height())) {
                        c.concat(pinMatrix);
                    }
                    TextBoxRenderer.draw(c, snapItem, snapText, into.left, into.top, snapFontPx,
                            snapMediaMs, snapProjectDurationMs, snapAnimate, snapObjectAlpha,
                            snapSelStart, snapSelEnd);
                    c.restore();
                }, null);
    }
}
