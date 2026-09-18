package com.fadcam.ui.faditor;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.ImageAnimPreset;
import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * Image preset picker with animated previews (§3.1).
 * <p>
 * Each tile is a small looping animation: solid rectangle = canvas, dotted rectangle = image
 * performing the actual move on a ~1.5s loop. Every tile is driven from the SAME code that
 * computes the real animation ({@link TextOverlayItem#applyImagePreset}) so the picker cannot
 * lie — exactly as {@code EasePickerPopover} renders curves from {@code Easing.apply()}.
 * Loops pause when the picker is closed.
 */
public final class ImagePresetPicker {

    public interface OnPick { void onPick(@NonNull ImageAnimPreset.Kind kind); }

    private static final ImageAnimPreset.Kind[] ORDER = {
            ImageAnimPreset.Kind.NONE,
            ImageAnimPreset.Kind.PAN_LEFT, ImageAnimPreset.Kind.PAN_RIGHT,
            ImageAnimPreset.Kind.PAN_UP, ImageAnimPreset.Kind.PAN_DOWN,
            ImageAnimPreset.Kind.ZOOM_IN, ImageAnimPreset.Kind.ZOOM_OUT,
            ImageAnimPreset.Kind.SLIDE_IN_LEFT, ImageAnimPreset.Kind.SLIDE_IN_RIGHT,
            ImageAnimPreset.Kind.SLIDE_IN_TOP, ImageAnimPreset.Kind.SLIDE_IN_BOTTOM,
            ImageAnimPreset.Kind.SLIDE_OUT_LEFT, ImageAnimPreset.Kind.SLIDE_OUT_RIGHT,
            ImageAnimPreset.Kind.SLIDE_OUT_TOP, ImageAnimPreset.Kind.SLIDE_OUT_BOTTOM
    };

    private static String labelOf(@NonNull ImageAnimPreset.Kind k) {
        switch (k) {
            case NONE: return "No animation";
            case PAN_LEFT: return "Pan ←";
            case PAN_RIGHT: return "Pan →";
            case PAN_UP: return "Pan ↑";
            case PAN_DOWN: return "Pan ↓";
            case ZOOM_IN: return "Zoom In";
            case ZOOM_OUT: return "Zoom Out";
            case SLIDE_IN_LEFT: return "Slide ←";
            case SLIDE_IN_RIGHT: return "Slide →";
            case SLIDE_IN_TOP: return "Slide ↑";
            case SLIDE_IN_BOTTOM: return "Slide ↓";
            case SLIDE_OUT_LEFT: return "Out ←";
            case SLIDE_OUT_RIGHT: return "Out →";
            case SLIDE_OUT_TOP: return "Out ↑";
            case SLIDE_OUT_BOTTOM: return "Out ↓";
            default: return k.name();
        }
    }

    private static final int COLS = 4;
    private static final int BG = Studio.RAISED;
    private static final int TILE_BG = Studio.LINE;
    private static final int ACCENT = Studio.GO;
    private static final int RING_FILL = 0x1F35F6BF;
    private static final int CANVAS_SOLID = Studio.OFF;
    private static final int IMAGE_DOTTED = Studio.INK_DIM;

    private ImagePresetPicker() {}

    public static void show(@NonNull View anchor, @Nullable ImageAnimPreset.Kind current, @NonNull OnPick onPick) {
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

        // Hint for None
        TextView hint = new TextView(ctx);
        hint.setText("No animation (reset) — centred, cover-scaled, static");
        hint.setTextColor(Studio.INK_FAINT);
        hint.setTextSize(10);
        hint.setPadding(0, 0, 0, (int)(6*d));
        container.addView(hint);

        final PopupWindow pop = new PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setElevation(16 * d);

        int tile = (int) (78 * d);
        int gap = (int) (6 * d);
        LinearLayout row = null;
        java.util.List<PresetTileView> tiles = new java.util.ArrayList<>();
        for (int i = 0; i < ORDER.length; i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row);
            }
            final ImageAnimPreset.Kind k = ORDER[i];
            final boolean selected = k == current;
            PresetTileView v = new PresetTileView(ctx, k, selected);
            v.setOnClickListener(vv -> {
                onPick.onPick(k);
                anchor.postDelayed(pop::dismiss, 120);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, tile);
            lp.setMargins(gap, gap, gap, gap);
            row.addView(v, lp);
            tiles.add(v);
            // Label below? Instead draw label inside tile's onDraw
        }

        // Pause loops when closed
        pop.setOnDismissListener(() -> {
            for (PresetTileView t : tiles) t.stopAnim();
        });

        // Show
        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int h = container.getMeasuredHeight();
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int gapPx = (int)(6*d);
        if (loc[1] - h - gapPx > 0) {
            pop.showAsDropDown(anchor, 0, -(h + anchor.getHeight() + gapPx), Gravity.START);
        } else {
            pop.showAsDropDown(anchor, 0, gapPx, Gravity.START);
        }
        // Start anims
        for (PresetTileView t : tiles) t.startAnim();
    }

    private static final class PresetTileView extends View {
        private final ImageAnimPreset.Kind kind;
        private final boolean selected;
        private final float density;
        private final Paint solidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dottedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextOverlayItem probe;
        private final long probeDur = 1500L;
        private ValueAnimator animator;
        private float fraction = 0f;
        private final float canvasW = 100f, canvasH = 100f; // preview canvas in px units for probe
        // Per-kind image size to demonstrate overhang correctly
        private final float imgW, imgH;

        PresetTileView(Context ctx, ImageAnimPreset.Kind k, boolean sel) {
            super(ctx);
            this.kind = k;
            this.selected = sel;
            this.density = ctx.getResources().getDisplayMetrics().density;
            solidPaint.setColor(CANVAS_SOLID);
            solidPaint.setStyle(Paint.Style.STROKE);
            solidPaint.setStrokeWidth(2f * density);
            dottedPaint.setColor(IMAGE_DOTTED);
            dottedPaint.setStyle(Paint.Style.STROKE);
            dottedPaint.setStrokeWidth(1.6f * density);
            dottedPaint.setPathEffect(new DashPathEffect(new float[]{4f*density, 3f*density}, 0));
            textPaint.setColor(Studio.INK_FAINT);
            textPaint.setTextSize(9f * density);
            textPaint.setTextAlign(Paint.Align.CENTER);

            // Pick image size that shows the move
            if (k == ImageAnimPreset.Kind.PAN_LEFT || k == ImageAnimPreset.Kind.PAN_RIGHT) { imgW = 160f; imgH = 90f; }
            else if (k == ImageAnimPreset.Kind.PAN_UP || k == ImageAnimPreset.Kind.PAN_DOWN) { imgW = 90f; imgH = 160f; }
            else if (k == ImageAnimPreset.Kind.ZOOM_IN || k == ImageAnimPreset.Kind.ZOOM_OUT) { imgW = 120f; imgH = 120f; }
            else if (k == ImageAnimPreset.Kind.SLIDE_OUT_LEFT || k == ImageAnimPreset.Kind.SLIDE_OUT_RIGHT || k == ImageAnimPreset.Kind.SLIDE_IN_LEFT || k == ImageAnimPreset.Kind.SLIDE_IN_RIGHT) { imgW = 84f; imgH = 84f; }
            else if (k == ImageAnimPreset.Kind.SLIDE_OUT_TOP || k == ImageAnimPreset.Kind.SLIDE_OUT_BOTTOM || k == ImageAnimPreset.Kind.SLIDE_IN_TOP || k == ImageAnimPreset.Kind.SLIDE_IN_BOTTOM) { imgW = 84f; imgH = 84f; }
            else { imgW = 120f; imgH = 90f; }

            probe = new TextOverlayItem("probe", "", Studio.INK, 0.5f, 0.5f, 0.5f, 0f);
            probe.setImageUri("probe://preview");
            if (k != ImageAnimPreset.Kind.NONE) {
                probe.applyImagePreset(k, canvasW, canvasH, imgW, imgH, probeDur);
            } else {
                // NONE = reset to cover static
                float fill = Math.max(canvasW/imgW, canvasH/imgH);
                float coverFrac = (imgH*fill)/canvasH;
                probe.setSizeFraction(Math.max(0.02f, Math.min(10f, coverFrac)));
                probe.setCenter(0.5f,0.5f);
            }
        }

        void startAnim() {
            if (kind == ImageAnimPreset.Kind.NONE) return;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1500);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.RESTART);
            animator.setInterpolator(null);
            animator.addUpdateListener(a -> {
                fraction = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }
        void stopAnim() {
            if (animator != null) { animator.cancel(); animator = null; }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            // Background
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(selected ? RING_FILL : Studio.LINE);
            if (selected) { bg.setColor(RING_FILL); } else bg.setColor(Studio.LINE);
            float rad = 10*density;
            bg.setStyle(Paint.Style.FILL);
            c.drawRoundRect(0,0,w,h,rad,rad,bg);
            if (selected) {
                Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(2f*density);
                ring.setColor(ACCENT);
                c.drawRoundRect(1*density,1*density,w-1*density,h-1*density,rad,rad,ring);
            }
            // Canvas solid rect centered
            float canvasPxW = Math.min(w*0.72f, h*0.62f);
            float canvasPxH = canvasPxW; // square for preview
            if (canvasW != canvasH) {
                float aspect = canvasW/canvasH;
                if (aspect > 1) canvasPxH = canvasPxW / aspect;
                else canvasPxW = canvasPxH * aspect;
            }
            float cx = w/2f, cy = h/2f - 6*density;
            float left = cx - canvasPxW/2f, top = cy - canvasPxH/2f, right = left+canvasPxW, bottom = top+canvasPxH;
            c.drawRect(left, top, right, bottom, solidPaint);

            // Dotted image rect driven from real preset
            long tMs = (long)(fraction * probeDur);
            float normCx = probe.animatedCenterX(tMs);
            float normCy = probe.animatedCenterY(tMs);
            float sizeFrac = probe.animatedSizeFraction(tMs);
            // Convert to pixel rect: sizeFrac*canvasH is height in canvas units; map to preview pixels
            // Preview canvasH corresponds to preview canvasPxH
            float renderedH = sizeFrac * canvasH; // in canvas units
            float renderedW = renderedH * (imgW/imgH);
            float scalePx = canvasPxH / canvasH; // canvas units -> preview px
            float pw = renderedW * scalePx;
            float ph = renderedH * scalePx;
            // normCx in [maybe -1..2], convert to pixel: left + normCx*canvasPxW - pw/2? But canvasPx maps 0..1 to left..right
            // For preview square canvas, 0..1 maps to left..right horizontally and top..bottom vertically
            float imgCx = left + normCx * canvasPxW;
            float imgCy = top + normCy * canvasPxH;
            RectF r = new RectF(imgCx - pw/2f, imgCy - ph/2f, imgCx + pw/2f, imgCy + ph/2f);
            c.drawRect(r, dottedPaint);

            // Label
            String lbl = labelOf(kind);
            c.drawText(lbl, w/2f, h - 6*density, textPaint);
        }
    }
}
