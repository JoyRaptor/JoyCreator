package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The visualizer Style tab's layout pieces: small section titles and HALF-WIDTH cells laid two
 * to a row, so short controls sit side by side instead of every slider spanning the drawer.
 * JoyRaptor, 2026-10-16: "Not all scrollbars need to go whole width. We can have two columns."
 *
 * <p>Every face here is the drawer kit's ({@link ObjectDrawer.Kit}): section label, row label,
 * slider, value readout. No colours of its own. Each cell is {@code [label][control][value]}
 * with a fixed label and value width, so the sliders in a column line up down the tab.</p>
 */
public final class VizDrawerGrid {

    private VizDrawerGrid() {}

    /** Writes a slider's value into the model (called on every user tick). */
    public interface Setter { void set(float v); }

    /** Formats a slider's value for its readout. */
    public interface Format { @NonNull String of(float v); }

    /** The cell's label column. Fits "Softness" / "Release" at 11sp without ellipsizing. */
    private static final int LABEL_DP = 50;
    /** The readout column: "1000ms" and "0.25" both fit on one line. */
    private static final int VALUE_DP = 40;
    /** Gutter between the two halves of a row. */
    private static final int GAP_DP = 14;
    /** A cell's minimum height: a finger target, kept dense (the owner prefers the dense look). */
    private static final int CELL_MIN_DP = 34;
    /** Slider resolution, as the old full-width rows had it. */
    private static final int STEPS = 1000;

    /** A small uppercase section title ({@code .dsec}). */
    @NonNull
    public static TextView section(@NonNull Context ctx, @NonNull CharSequence title) {
        return ObjectDrawer.Kit.sectionLabel(ctx, title);
    }

    /**
     * One row of two equal halves. {@code right} may be null: that half stays empty so the
     * columns still line up with the rows above and below it.
     */
    @NonNull
    public static LinearLayout pair(@NonNull Context ctx, @NonNull View left, @Nullable View right) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int half = ObjectDrawer.Kit.dp(ctx, GAP_DP / 2f);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        l.setMarginEnd(half);
        row.addView(left, l);
        LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        r.setMarginStart(half);
        row.addView(right != null ? right : new Space(ctx), r);
        return row;
    }

    /**
     * A half-width slider cell: label, slider, live readout. Maps 0..{@value #STEPS} onto
     * [{@code min}, {@code max}] and calls {@code onChange} on every user tick, exactly as the
     * old full-width rows did. {@code name} is the slider's hover / TalkBack name.
     */
    /** Called when a finger lands on / leaves a slider: one drag is one undo step. */
    public interface Gesture { void onStart(); void onStop(); }

    @NonNull
    public static LinearLayout slider(@NonNull Context ctx, @NonNull CharSequence label,
                                      @NonNull CharSequence name, float min, float max, float value,
                                      @NonNull Format format, @NonNull Setter onChange) {
        return slider(ctx, label, name, min, max, value, format, onChange, null);
    }

    @NonNull
    public static LinearLayout slider(@NonNull Context ctx, @NonNull CharSequence label,
                                      @NonNull CharSequence name, float min, float max, float value,
                                      @NonNull Format format, @NonNull Setter onChange,
                                      @androidx.annotation.Nullable Gesture gesture) {
        LinearLayout cell = cell(ctx, label);
        final SeekBar bar = new SeekBar(ctx);
        bar.setMax(STEPS);
        float clamped = Math.max(min, Math.min(max, value));
        bar.setProgress(Math.round((clamped - min) / (max - min) * STEPS));
        ObjectDrawer.Kit.styleSlider(bar);
        // 8dp ends, not the platform's 16: in a half-width cell the default padding eats a
        // third of the track. 8 still clears the kit's 15dp thumb.
        int pad = ObjectDrawer.Kit.dp(ctx, 8);
        bar.setPadding(pad, 0, pad, 0);
        ObjectDrawer.Kit.describe(bar, name);

        final TextView readout = ObjectDrawer.Kit.value(ctx, VALUE_DP);
        readout.setSingleLine(true);
        final Runnable show = () -> readout.setText(
                format.of(min + bar.getProgress() / (float) STEPS * (max - min)));
        show.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                show.run();
                onChange.set(min + p / (float) STEPS * (max - min));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { if (gesture != null) gesture.onStart(); }
            @Override public void onStopTrackingTouch(SeekBar s) { if (gesture != null) gesture.onStop(); }
        });
        cell.addView(bar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        cell.addView(readout);
        return cell;
    }

    /** A half-width cell holding a row of choice chips after its label. */
    @NonNull
    public static LinearLayout choice(@NonNull Context ctx, @NonNull CharSequence label,
                                      @NonNull View choices) {
        LinearLayout cell = cell(ctx, label);
        cell.addView(choices, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return cell;
    }

    /** Value readout: two decimals. */
    @NonNull
    public static Format decimal() {
        return v -> String.format(java.util.Locale.US, "%.2f", v);
    }

    /** Value readout: a whole number. */
    @NonNull
    public static Format whole() {
        return v -> String.valueOf(Math.round(v));
    }

    /** Value readout: a whole number in a format string resource, e.g. "%1$dms". */
    @NonNull
    public static Format whole(@NonNull Context ctx, int formatRes) {
        return v -> ctx.getString(formatRes, Math.round(v));
    }

    @NonNull
    private static LinearLayout cell(@NonNull Context ctx, @NonNull CharSequence label) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setMinimumHeight(ObjectDrawer.Kit.dp(ctx, CELL_MIN_DP));
        c.addView(ObjectDrawer.Kit.rowLabel(ctx, label, LABEL_DP));
        return c;
    }
}
