package com.fadcam.ui.faditor.tools;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * ONE colour picker for the whole app (JoyRaptor's compact dialog, 2026-08-08).
 *
 * <p>Hue/saturation/brightness sliders down the left with tappable numeric read-outs, a hue ring
 * with a saturation/brightness triangle, a copyable hex field, a row of useful fixed swatches
 * including a "none", and eight RECENT swatches shared by every caller in the app.</p>
 *
 * <p><b>Recents are app-wide on purpose.</b> A palette that only remembers what you did in one
 * drawer is a palette you have to rebuild in every other one; the whole value of "the colour I
 * just used" is that it follows you to the next thing you colour.</p>
 *
 * <p><b>Supports NONE as a first-class answer</b> ({@code null}), because several of the things
 * this picks for — outline, glow, background, plate — are legitimately absent, and forcing a
 * transparent black to mean "off" is how a colour picker starts lying about state.</p>
 */
public final class ColorPickerDialog {

    /** Result: a colour, or {@code null} for "none". */
    public interface OnPicked { void onPicked(@Nullable Integer color); }

    private static final String PREFS = "faditor_color_picker";
    private static final String KEY_RECENTS = "recents";
    private static final int RECENT_SLOTS = 8;

    /** Useful fixed colours: greys at both ends, then a spread that covers the common asks. */
    private static final int[] SWATCHES = {
            0xFF000000, 0xFF404040, 0xFF808080, 0xFFC0C0C0, 0xFFFFFFFF,
            0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF00ACC1,
            0xFF1E88E5, 0xFF5E35B1, 0xFFD81B60, 0xFF6D4C41,
    };

    private ColorPickerDialog() {}

    /**
     * @param initial the current colour, or {@code null} when the thing has none.
     * @param allowNone whether to offer the "none" swatch at all. Text colour, for instance,
     *                  cannot be none — invisible text is a bug, not a style.
     */
    public static void show(@NonNull Context ctx, @NonNull String title,
                            @Nullable Integer initial, boolean allowNone,
                            @NonNull OnPicked onPicked) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int pad = Math.round(14 * d);

        final float[] hsb = new float[3];
        Color.colorToHSV(initial != null ? initial : 0xFFFFFFFF, hsb);
        final int[] alpha = {initial != null ? Color.alpha(initial) : 255};
        final boolean[] isNone = {initial == null};

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, 0);

        // ── top: sliders on the left, wheel on the right ────────────────────────────────
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout sliders = new LinearLayout(ctx);
        sliders.setOrientation(LinearLayout.VERTICAL);
        sliders.setGravity(Gravity.CENTER_VERTICAL);
        ColorWheelView wheel = new ColorWheelView(ctx);
        top.addView(sliders, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(wheel, new LinearLayout.LayoutParams(
                Math.round(132 * d), Math.round(132 * d)));
        root.addView(top);

        final View preview = new View(ctx);
        final TextView hex = new TextView(ctx);
        final SeekBar[] bars = new SeekBar[3];
        final TextView[] nums = new TextView[3];

        // Declared before the rows so each row's listener can push the whole state at once.
        final Runnable[] syncFromHsb = new Runnable[1];

        String[] labels = {"H", "S", "B"};
        int[] maxes = {360, 100, 100};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView lab = new TextView(ctx);
            lab.setText(labels[i]);
            lab.setTextColor(0xFFBBBBBB);
            lab.setTextSize(13f);
            lab.setWidth(Math.round(18 * d));
            row.addView(lab);

            SeekBar bar = new SeekBar(ctx);
            bar.setMax(maxes[i]);
            bars[i] = bar;
            row.addView(bar, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // LOOKS LIKE TEXT, IS A CONTROL. Tapping opens a numeric entry, because a slider
            // cannot express "exactly 210" on a 360-wide track and the value is right there.
            TextView num = new TextView(ctx);
            num.setTextColor(0xFFE8E8E8);
            num.setTextSize(12.5f);
            num.setWidth(Math.round(34 * d));
            num.setGravity(Gravity.END);
            nums[i] = num;
            row.addView(num);
            sliders.addView(row);

            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    if (!fromUser) return;
                    hsb[idx] = idx == 0 ? p : p / 100f;
                    isNone[0] = false;
                    syncFromHsb[0].run();
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            num.setOnClickListener(v -> promptForNumber(ctx, labels[idx], maxes[idx],
                    Math.round(idx == 0 ? hsb[0] : hsb[idx] * 100f), val -> {
                        hsb[idx] = idx == 0 ? val : val / 100f;
                        isNone[0] = false;
                        syncFromHsb[0].run();
                    }));
        }

        // ── hex row: swatch · copy · value ──────────────────────────────────────────────
        LinearLayout hexRow = new LinearLayout(ctx);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        hexRow.setPadding(0, Math.round(10 * d), 0, Math.round(4 * d));

        FrameLayout previewBox = new FrameLayout(ctx);
        previewBox.addView(preview, new FrameLayout.LayoutParams(
                Math.round(26 * d), Math.round(26 * d)));
        hexRow.addView(previewBox);

        TextView copy = new TextView(ctx);
        copy.setText("⧉");
        copy.setTextSize(17f);
        copy.setTextColor(0xFFBBBBBB);
        copy.setPadding(Math.round(10 * d), 0, Math.round(6 * d), 0);
        hexRow.addView(copy);

        hex.setTextColor(0xFFE8E8E8);
        hex.setTextSize(14f);
        hex.setTypeface(android.graphics.Typeface.MONOSPACE);
        // Selectable so it can be copied by hand as well as by the icon — the icon is the fast
        // path, not the only one.
        hex.setTextIsSelectable(true);
        hexRow.addView(hex);
        root.addView(hexRow);

        copy.setOnClickListener(v -> {
            ClipboardManager cm =
                    (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("color", hex.getText().toString()));
                Toast.makeText(ctx, hex.getText().toString() + " copied",
                        Toast.LENGTH_SHORT).show();
            }
        });

        // ── fixed swatches (+ none) ─────────────────────────────────────────────────────
        LinearLayout fixedRow1 = swatchRow(ctx);
        LinearLayout fixedRow2 = swatchRow(ctx);
        root.addView(fixedRow1);
        root.addView(fixedRow2);

        if (allowNone) {
            fixedRow1.addView(noneSwatch(ctx, d, () -> {
                isNone[0] = true;
                syncFromHsb[0].run();
            }));
        }
        for (int i = 0; i < SWATCHES.length; i++) {
            final int c = SWATCHES[i];
            View sw = swatch(ctx, d, c, () -> {
                Color.colorToHSV(c, hsb);
                alpha[0] = 255;
                isNone[0] = false;
                syncFromHsb[0].run();
            });
            (i < 7 ? fixedRow1 : fixedRow2).addView(sw);
        }

        // ── recents ─────────────────────────────────────────────────────────────────────
        TextView recentLabel = new TextView(ctx);
        recentLabel.setText("RECENT");                                    // TODO(strings)
        recentLabel.setTextColor(0xFF8A8A8A);
        recentLabel.setTextSize(10.5f);
        recentLabel.setPadding(0, Math.round(8 * d), 0, Math.round(2 * d));
        root.addView(recentLabel);

        LinearLayout recentRow = swatchRow(ctx);
        root.addView(recentRow);
        List<Integer> recents = loadRecents(ctx);
        for (int i = 0; i < RECENT_SLOTS; i++) {
            if (i < recents.size()) {
                final int c = recents.get(i);
                recentRow.addView(swatch(ctx, d, c, () -> {
                    Color.colorToHSV(c, hsb);
                    alpha[0] = Color.alpha(c);
                    isNone[0] = false;
                    syncFromHsb[0].run();
                }));
            } else {
                // EMPTY slots are drawn, not omitted: the row keeps its shape as it fills, so
                // the swatch you used last does not move under your thumb every session.
                recentRow.addView(emptySwatch(ctx, d));
            }
        }

        // ── the one place that pushes state into every control ──────────────────────────
        syncFromHsb[0] = () -> {
            int rgb = Color.HSVToColor(alpha[0], hsb);
            bars[0].setProgress(Math.round(hsb[0]));
            bars[1].setProgress(Math.round(hsb[1] * 100f));
            bars[2].setProgress(Math.round(hsb[2] * 100f));
            nums[0].setText(String.valueOf(Math.round(hsb[0])));
            nums[1].setText(String.valueOf(Math.round(hsb[1] * 100f)));
            nums[2].setText(String.valueOf(Math.round(hsb[2] * 100f)));
            wheel.setHsb(hsb[0], hsb[1], hsb[2]);
            hex.setText(isNone[0] ? "none" : String.format("#%06X", rgb & 0xFFFFFF));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(4 * d);
            bg.setColor(isNone[0] ? 0x00000000 : rgb);
            bg.setStroke(Math.round(1 * d), 0xFF777777);
            preview.setBackground(bg);
        };
        wheel.setListener((h, s, b) -> {
            hsb[0] = h; hsb[1] = s; hsb[2] = b;
            isNone[0] = false;
            syncFromHsb[0].run();
        });
        syncFromHsb[0].run();

        new MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setView(root)
                .setPositiveButton("Set", (dlg, w) -> {                   // TODO(strings)
                    if (isNone[0]) { onPicked.onPicked(null); return; }
                    int rgb = Color.HSVToColor(alpha[0], hsb);
                    pushRecent(ctx, rgb);
                    onPicked.onPicked(rgb);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── Recents storage ─────────────────────────────────────────────────────────────────

    @NonNull
    private static SharedPreferences prefs(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public static List<Integer> loadRecents(@NonNull Context ctx) {
        List<Integer> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_RECENTS, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            try { out.add((int) Long.parseLong(part.trim(), 16)); } catch (Exception ignored) { }
            if (out.size() >= RECENT_SLOTS) break;
        }
        return out;
    }

    /** Most recent first, de-duplicated, capped at the number of slots the row draws. */
    public static void pushRecent(@NonNull Context ctx, @ColorInt int color) {
        List<Integer> cur = loadRecents(ctx);
        cur.remove(Integer.valueOf(color));
        cur.add(0, color);
        while (cur.size() > RECENT_SLOTS) cur.remove(cur.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cur.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Integer.toHexString(cur.get(i)));
        }
        prefs(ctx).edit().putString(KEY_RECENTS, sb.toString()).apply();
    }

    // ── Small views ─────────────────────────────────────────────────────────────────────

    @NonNull
    private static LinearLayout swatchRow(@NonNull Context ctx) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    @NonNull
    private static View swatch(@NonNull Context ctx, float d, @ColorInt int color,
                               @NonNull Runnable onTap) {
        View v = new View(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        bg.setStroke(Math.round(1 * d), 0x55FFFFFF);
        v.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(26 * d), Math.round(26 * d));
        lp.rightMargin = Math.round(6 * d);
        lp.topMargin = Math.round(3 * d);
        lp.bottomMargin = Math.round(3 * d);
        v.setLayoutParams(lp);
        v.setOnClickListener(x -> onTap.run());
        return v;
    }

    @NonNull
    private static View emptySwatch(@NonNull Context ctx, float d) {
        View v = new View(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x00000000);
        bg.setStroke(Math.round(1 * d), 0x33FFFFFF);
        v.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(26 * d), Math.round(26 * d));
        lp.rightMargin = Math.round(6 * d);
        v.setLayoutParams(lp);
        return v;
    }

    /** The circle with a diagonal through it: unmistakably "no colour at all". */
    @NonNull
    private static View noneSwatch(@NonNull Context ctx, float d, @NonNull Runnable onTap) {
        View v = new View(ctx) {
            private final android.graphics.Paint p =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(@NonNull android.graphics.Canvas c) {
                float r = Math.min(getWidth(), getHeight()) / 2f - 1.5f * d;
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(1.6f * d);
                p.setColor(0xFFCCCCCC);
                c.drawCircle(cx, cy, r, p);
                double a = Math.toRadians(45);
                float dx = (float) Math.cos(a) * r, dy = (float) Math.sin(a) * r;
                c.drawLine(cx - dx, cy + dy, cx + dx, cy - dy, p);
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(26 * d), Math.round(26 * d));
        lp.rightMargin = Math.round(6 * d);
        v.setLayoutParams(lp);
        v.setOnClickListener(x -> onTap.run());
        return v;
    }

    private interface OnNumber { void onNumber(int value); }

    private static void promptForNumber(@NonNull Context ctx, @NonNull String label, int max,
                                        int current, @NonNull OnNumber cb) {
        EditText in = new EditText(ctx);
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setText(String.valueOf(current));
        in.setSelectAllOnFocus(true);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(label)
                .setView(in)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    try {
                        int v = Integer.parseInt(in.getText().toString().trim());
                        cb.onNumber(Math.max(0, Math.min(max, v)));
                    } catch (NumberFormatException ignored) {
                        // Typed nonsense keeps the old value rather than snapping to zero,
                        // which would silently destroy a colour the user was refining.
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
