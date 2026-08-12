package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * The text-alignment control: four bars drawn as a miniature paragraph, in a box whose size never
 * changes.
 *
 * <p><b>Why this is a View and not a character.</b> It used to be a {@code TextView} cycling
 * through {@code "≡⌐"}, {@code "⌐≡"}, {@code "☰"} and {@code "≡"} — four strings of two different
 * lengths, made of glyphs with different advances and stroke weights, in whatever font the device
 * happens to substitute for them. So the control changed WIDTH as you cycled it, jogging every
 * button to its right, and the four states did not read as the same object in four positions.
 * JoyRaptor (2026-08-12): "needs a proper 4 icon cycle that shows a paragraph shape change without
 * changing the aspect ratio of the icon itself."</p>
 *
 * <p>Drawing it makes the size a property of the control rather than of the text renderer, and it
 * makes each state say what it does: line lengths vary and their EDGES line up on the side the
 * alignment names — flush left, centred, flush right, or both edges flush with a short last line,
 * which is what justification actually looks like.</p>
 */
public final class AlignIconView extends View {

    /** Relative bar lengths, as fractions of the icon's width. The "paragraph". */
    private static final float[] RAGGED = {1f, 0.72f, 0.88f, 0.55f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    @NonNull private String align = TextOverlayItem.ALIGN_CENTER;

    public AlignIconView(@NonNull Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFEEEEEE);
    }

    public void setAlign(@NonNull String a) {
        if (align.equals(a)) return;
        align = a;
        invalidate();
    }

    public void setTint(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // A FIXED square, ignoring the ragged content entirely — that constancy is the whole point.
        int size = Math.round(20f * density);
        setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int n = RAGGED.length;
        float w = getWidth(), h = getHeight();
        float bar = Math.max(1.5f, 2f * density);
        float gap = (h - n * bar) / (n + 1);
        boolean justify = TextOverlayItem.ALIGN_JUSTIFY.equals(align);

        for (int i = 0; i < n; i++) {
            // Justified copy is flush on BOTH edges except its last line, which is why that is the
            // only state where the bars are full width and only the final one is short.
            float len = justify ? (i == n - 1 ? 0.6f : 1f) : RAGGED[i];
            float lw = w * len;
            float left;
            switch (align) {
                case TextOverlayItem.ALIGN_LEFT:
                case TextOverlayItem.ALIGN_JUSTIFY:
                    left = 0f;
                    break;
                case TextOverlayItem.ALIGN_RIGHT:
                    left = w - lw;
                    break;
                default:                                   // CENTER
                    left = (w - lw) / 2f;
                    break;
            }
            float top = gap + i * (bar + gap);
            canvas.drawRoundRect(left, top, left + lw, top + bar,
                    bar / 2f, bar / 2f, paint);
        }
    }
}
