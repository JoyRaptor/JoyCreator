package com.fadcam.ui.faditor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeGlyph;

/**
 * D2a: the ease-curve picker — a compact popover anchored at the {@code ♦}
 * (KineMaster "Graphs" look, JoyRaptor's green). A grid of rounded-square tiles, each
 * a thin monochrome thumbnail RENDERED FROM {@link Easing#apply} itself (so the
 * preview can never lie about the math). The selected tile carries a green ring;
 * the live preview is the object itself moving in the preview window, so this
 * surface stays small and obeys D4 (a popover, not another sheet).
 */
public final class EasePickerPopover {

    public interface OnPick { void onPick(@NonNull Easing e); }

    // Tile 1 = ⊘ "linear/none"; the rest render their curve. No custom-graph tile
    // (the Easing enum contract keeps project files simple / AI-authorable).
    private static final Easing[] ORDER = {
            Easing.LINEAR, Easing.EASE_IN, Easing.EASE_OUT, Easing.EASE_IN_OUT,
            Easing.HOLD, Easing.EASE_IN_EXPO, Easing.EASE_OUT_EXPO, Easing.ANTICIPATE,
            Easing.OVERSHOOT, Easing.SPRING_SOFT, Easing.SPRING, Easing.SPRING_BOUNCY,
            Easing.BOUNCE, Easing.STAIRS_4
    };
    private static final int COLS = 4;

    private static final int BG = 0xFF1F1F26;
    private static final int TILE_BG = 0xFF2C2C35;
    private static final int BASELINE = 0xFF33333C;
    private static final int CURVE = 0xFFC4C4CE;
    private static final int ACCENT = 0xFF35F6BF;
    private static final int RING_FILL = 0x1F35F6BF;
    private static final int TXT_DIM = 0xFF8A8A94;

    private EasePickerPopover() {}

    /**
     * @param anchor  the diamond the popover hangs off of.
     * @param enabled false = there's no editable segment (empty track / playhead
     *                before the first key) → a disabled "Drop keyframes first" hint.
     * @param current the segment's current easing (green-ringed), or null.
     */
    public static void show(@NonNull View anchor, boolean enabled,
                            @Nullable Easing current, @NonNull OnPick onPick) {
        Context ctx = anchor.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(12 * d);
        container.setBackground(bg);
        container.setElevation(16 * d);
        int pad = (int) (10 * d);
        container.setPadding(pad, pad, pad, pad);

        final PopupWindow pop = new PopupWindow(container,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        pop.setElevation(16 * d);

        if (!enabled) {
            TextView msg = new TextView(ctx);
            msg.setText("Drop keyframes first"); // TODO(strings)
            msg.setTextColor(TXT_DIM);
            msg.setTextSize(13);
            msg.setPadding((int) (6 * d), (int) (4 * d), (int) (6 * d), (int) (4 * d));
            container.addView(msg);
            popShow(pop, container, anchor, d);
            return;
        }

        int tile = (int) (56 * d);
        int cell = (int) (4 * d);
        LinearLayout gridRow = null;
        for (int i = 0; i < ORDER.length; i++) {
            if (i % COLS == 0) {
                gridRow = new LinearLayout(ctx);
                gridRow.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(gridRow);
            }
            final Easing e = ORDER[i];
            final boolean selected = e == current;
            View t = buildTile(ctx, d, tile, e, selected, chosen -> {
                onPick.onPick(chosen);
                // Let the ring land before the popover disappears (~150ms).
                anchor.postDelayed(pop::dismiss, 150);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, tile);
            lp.setMargins(cell, cell, cell, cell);
            gridRow.addView(t, lp);
        }
        popShow(pop, container, anchor, d);
    }

    private static void popShow(@NonNull PopupWindow pop, @NonNull View container,
                                @NonNull View anchor, float d) {
        // Prefer above the diamond (never cover the slider row below it); fall back
        // to below only when there isn't room above.
        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int h = container.getMeasuredHeight();
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int gap = (int) (6 * d);
        if (loc[1] - h - gap > 0) {
            pop.showAsDropDown(anchor, 0, -(h + anchor.getHeight() + gap), Gravity.START);
        } else {
            pop.showAsDropDown(anchor, 0, gap, Gravity.START);
        }
    }

    private interface TileTap { void tap(@NonNull Easing e); }

    @NonNull
    private static View buildTile(@NonNull Context ctx, float d, int size,
                                  @NonNull Easing e, boolean selected,
                                  @NonNull TileTap onTap) {
        final EaseTileView view = new EaseTileView(ctx, e, selected);
        view.setOnClickListener(v -> {
            view.setSelectedRing(true); // show the ring lands on tap before dismiss
            onTap.tap(e);
        });
        return view;
    }

    /** One picker tile: rounded-square bg, glyph silhouette + curve thumbnail (or ⊘ for LINEAR),
     *  optional green selection ring. Now drawn via {@link KeyframeGlyph} so every site
     *  shares the ONE mapping. */
    private static final class EaseTileView extends View {
        private final Easing easing;
        private final float density;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path silhouettePath = new Path();
        private final Path curvePath = new Path();
        private boolean selected;

        EaseTileView(@NonNull Context ctx, @NonNull Easing e, boolean selected) {
            super(ctx);
            this.easing = e;
            this.selected = selected;
            this.density = ctx.getResources().getDisplayMetrics().density;
        }

        void setSelectedRing(boolean s) {
            if (s == selected) return;
            selected = s;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            float w = getWidth(), h = getHeight();
            float rad = 10 * density;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(TILE_BG);
            c.drawRoundRect(0, 0, w, h, rad, rad, paint);

            if (easing == Easing.LINEAR) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f * density);
                paint.setColor(CURVE);
                float cx = w / 2f, cy = h / 2f, rr = Math.min(w, h) * 0.24f;
                c.drawCircle(cx, cy, rr, paint);
                float s = rr * 0.72f;
                c.drawLine(cx - s, cy + s, cx + s, cy - s, paint);
            } else {
                drawGlyph(c, w, h);
            }

            if (selected) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(RING_FILL);
                c.drawRoundRect(0, 0, w, h, rad, rad, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f * density);
                paint.setColor(ACCENT);
                float inset = 1f * density;
                c.drawRoundRect(inset, inset, w - inset, h - inset, rad, rad, paint);
            }
        }

        private void drawGlyph(@NonNull Canvas c, float w, float h) {
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) * 0.28f;
            // Single source: silhouette + curve drawn from Easing.apply()
            KeyframeGlyph.silhouetteFor(easing, cx, cy, r, silhouettePath);
            KeyframeGlyph.curveFor(easing, cx, cy, r, curvePath);
            // Faint silhouette so family is legible at tile size
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f * density);
            paint.setColor(BASELINE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(silhouettePath, paint);
            if (!curvePath.isEmpty()) {
                paint.setStrokeWidth(1.4f * density);
                paint.setColor(CURVE);
                c.drawPath(curvePath, paint);
            }
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStrokeJoin(Paint.Join.MITER);
        }
    }
}
