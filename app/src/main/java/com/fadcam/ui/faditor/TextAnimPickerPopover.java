package com.fadcam.ui.faditor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.transcript.CaptionAnimator;

/**
 * SPEC_TEXT_ANIMATION step 2: the text-animation preset grid and granularity selector — the
 * reporter's "KineMaster keyframe-curve picker, but presets". Deliberately the same shape as
 * {@link EasePickerPopover}: a compact popover of rounded-square tiles, each drawing its own
 * thumbnail FROM THE EVALUATOR ({@link CaptionAnimator#presetTransform}) rather than from a
 * hand-drawn asset, so a tile cannot advertise a motion the renderers do not produce.
 *
 * <h3>Two things this surface deliberately does</h3>
 * <ul>
 *   <li><b>Only implemented presets appear.</b> Five of the ten are declared on
 *       {@link CaptionAnimator.Preset} but cannot be expressed as a {@code Transform} yet, and
 *       each returns identity rather than an approximation. Offering them would be offering a
 *       tile that does nothing.</li>
 *   <li><b>Granularity sits in the same popover as the preset.</b> It is orthogonal to the preset
 *       — one preset at four granularities is four quite different effects — but it is never
 *       useful on its own, so splitting them across two surfaces would just cost a tap.</li>
 * </ul>
 *
 * <p>The TIMING is not here. It is the tape carets, for every preset at every granularity, which
 * is the reporter's model and the reason there is no duration control on this popover.</p>
 */
public final class TextAnimPickerPopover {

    public interface OnPick {
        void onPreset(@NonNull CaptionAnimator.Preset p);
        void onGranularity(@NonNull CaptionAnimator.Granularity g);
    }

    private static final int COLS = 3;

    private static final int BG = 0xFF1C1C1E;
    private static final int TILE_BG = 0xFF2A2A2E;
    private static final int GLYPH = 0xFFDDDDDD;
    private static final int ACCENT = 0xFF4CAF50;
    private static final int RING_FILL = 0x1F4CAF50;
    private static final int TXT_DIM = 0xFF888888;

    /**
     * The three progress values each tile freezes a glyph at. A preset's character is in how its
     * units differ ACROSS the sweep, so one still frame of three staggered units says more than
     * an animation would in a 60dp tile — and it cannot drift out of sync with playback.
     *
     * <p>Starting at exactly 0 is what makes TYPEWRITER legible: its first glyph is absent and
     * its other two are fully formed and identical, which is precisely "no easing, it just
     * appears". Every eased preset instead shows three distinct states.</p>
     */
    private static final float[] SAMPLES = {0f, 0.5f, 1f};

    private TextAnimPickerPopover() {}

    /**
     * @param anchor      the motion button the popover hangs off of.
     * @param current     the clip's current preset (green-ringed).
     * @param currentGran the clip's current granularity (green-ringed).
     */
    public static void show(@NonNull View anchor,
                            @Nullable CaptionAnimator.Preset current,
                            @Nullable CaptionAnimator.Granularity currentGran,
                            @NonNull OnPick onPick) {
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

        container.addView(caption(ctx, d, "Motion")); // TODO(strings)

        int tile = (int) (60 * d);
        int cell = (int) (4 * d);
        LinearLayout gridRow = null;
        int shown = 0;
        for (CaptionAnimator.Preset p : CaptionAnimator.Preset.values()) {
            if (!p.implemented) continue; // see class doc — an unimplemented tile does nothing
            if (shown % COLS == 0) {
                gridRow = new LinearLayout(ctx);
                gridRow.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(gridRow);
            }
            shown++;
            final PresetTileView view = new PresetTileView(ctx, p, p == current);
            view.setOnClickListener(v -> {
                view.setSelectedRing(true);
                onPick.onPreset(p);
                anchor.postDelayed(pop::dismiss, 150);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, tile);
            lp.setMargins(cell, cell, cell, cell);
            gridRow.addView(view, lp);
        }

        // ── Granularity: what counts as one animating unit ──────────────
        container.addView(caption(ctx, d, "Animate by")); // TODO(strings)
        LinearLayout granRow = new LinearLayout(ctx);
        granRow.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(granRow);
        CaptionAnimator.Granularity[] grans = CaptionAnimator.Granularity.values();
        String[] granNames = {"Letter", "Word", "Sentence", "Block"}; // TODO(strings)
        for (int i = 0; i < grans.length; i++) {
            final CaptionAnimator.Granularity g = grans[i];
            TextView chip = new TextView(ctx);
            chip.setText(granNames[i]);
            chip.setTextSize(12);
            chip.setTextColor(g == currentGran ? ACCENT : GLYPH);
            chip.setAlpha(g == currentGran ? 1f : 0.5f);
            int cp = (int) (8 * d);
            chip.setPadding(cp, cp / 2, cp, cp / 2);
            GradientDrawable cbg = new GradientDrawable();
            cbg.setCornerRadius(8 * d);
            cbg.setColor(TILE_BG);
            if (g == currentGran) cbg.setStroke((int) (1.5f * d), ACCENT);
            chip.setBackground(cbg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins((int) (3 * d), (int) (2 * d), (int) (3 * d), (int) (2 * d));
            granRow.addView(chip, lp);
            chip.setOnClickListener(v -> {
                onPick.onGranularity(g);
                anchor.postDelayed(pop::dismiss, 150);
            });
        }

        popShow(pop, container, anchor, d);
    }

    @NonNull
    private static TextView caption(@NonNull Context ctx, float d, @NonNull String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(TXT_DIM);
        tv.setTextSize(11);
        tv.setPadding((int) (6 * d), (int) (6 * d), 0, (int) (2 * d));
        return tv;
    }

    private static void popShow(@NonNull PopupWindow pop, @NonNull View container,
                                @NonNull View anchor, float d) {
        // Prefer above the anchor so the popover never covers the drawer rows below it; fall
        // back to below only when there isn't room. Same rule as EasePickerPopover.
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

    /**
     * One preset tile: three "A"s frozen at {@link #SAMPLES}, each put through
     * {@link CaptionAnimator#presetTransform} exactly as a renderer would, plus the preset's name.
     * The transform is applied the same way both renderers apply it — translate, then scale about
     * the unit's own centre — so the thumbnail is the motion, not an artist's idea of it.
     */
    private static final class PresetTileView extends View {
        private final CaptionAnimator.Preset preset;
        private final float density;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean selected;

        PresetTileView(@NonNull Context ctx, @NonNull CaptionAnimator.Preset p, boolean selected) {
            super(ctx);
            this.preset = p;
            this.selected = selected;
            this.density = ctx.getResources().getDisplayMetrics().density;
            labelPaint.setColor(TXT_DIM);
            labelPaint.setTextAlign(Paint.Align.CENTER);
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

            if (preset == CaptionAnimator.Preset.NONE) {
                // ⊘ "no animation" — the same affordance the ease picker gives LINEAR.
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f * density);
                paint.setColor(GLYPH);
                float cx = w / 2f, cy = h * 0.42f, rr = Math.min(w, h) * 0.18f;
                c.drawCircle(cx, cy, rr, paint);
                float s = rr * 0.72f;
                c.drawLine(cx - s, cy + s, cx + s, cy - s, paint);
            } else {
                drawSamples(c, w, h);
            }

            labelPaint.setTextSize(8.5f * density);
            c.drawText(label(), w / 2f, h - 6 * density, labelPaint);

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

        private void drawSamples(@NonNull Canvas c, float w, float h) {
            // fontPx is what the distance-based presets scale their motion against, so the
            // thumbnail must pass a real one rather than 1 — otherwise RISE and GHOST, whose
            // whole character is a font-proportional offset, would render as identity.
            float fontPx = h * 0.30f;
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(fontPx);
            float baseY = h * 0.55f;
            for (int i = 0; i < SAMPLES.length; i++) {
                CaptionAnimator.Transform t =
                        CaptionAnimator.presetTransform(preset, SAMPLES[i], fontPx);
                if (t.alpha <= 0.004f) continue;
                float cx = w * (0.26f + 0.24f * i);
                c.save();
                c.translate(t.dx, t.dy);
                c.scale(t.scaleX, t.scaleY, cx, baseY - fontPx * 0.35f);
                paint.setColor(CaptionAnimator.applyAlpha(0xFF000000 | (GLYPH & 0xFFFFFF), t.alpha));
                // NOTE: t.blurPx is deliberately NOT applied. Neither CaptionOverlayView nor
                // CaptionExportRenderer consumes it today (see CaptionAnimator.Transform#blurPx),
                // so blurring here would make GHOST's tile advertise a softness the app never
                // draws — the one thing a thumbnail rendered from the evaluator exists to rule
                // out. When a renderer gains blur, this line is where the tile follows it.
                c.drawText("A", cx, baseY, paint);
                c.restore();
            }
        }

        /** TODO(strings) — the extraction is frozen behind the rebrand (road_map.md:49). */
        @NonNull
        private String label() {
            switch (preset) {
                case NONE:       return "None";
                case TYPEWRITER: return "Type";
                case FADE:       return "Fade";
                case RISE:       return "Rise";
                case GHOST:      return "Ghost";
                case BEAM:       return "Beam";
                default:         return preset.name();
            }
        }
    }
}
