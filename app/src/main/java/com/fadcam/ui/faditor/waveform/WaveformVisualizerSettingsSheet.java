package com.fadcam.ui.faditor.waveform;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Settings → "Waveform visualizer": edits the global {@link TapeWaveformStyle} live. Built
 * programmatically in the dark editor theme (matching {@code FilterBottomSheet}). Every control
 * writes straight back into the {@link TapeWaveformStyle} handed to {@link #newInstance}, persists
 * it to the app's default SharedPreferences, and fires {@link Listener#onStyleChanged(boolean)} so
 * the host can react — passing {@code true} only when a crossover/presence change means the cached
 * band envelopes must be re-EXTRACTED (expensive) rather than just re-shaped/redrawn (cheap).
 *
 * <p>The style + listener are held as plain (non-persisted) fields set right after construction;
 * the sheet is shown transiently from the editor, matching how the other faditor sheets carry live
 * callbacks. On process recreation with no style bound, it simply dismisses itself.</p>
 */
public class WaveformVisualizerSettingsSheet extends BottomSheetDialogFragment {

    /** Host hook: the style changed. {@code crossoversChanged} true → re-extract, else re-shape. */
    public interface Listener {
        void onStyleChanged(boolean crossoversChanged);
    }

    // Crossover Hz slider bounds (shared min so SeekBar progress == hz - HZ_MIN).
    private static final int HZ_MIN = 60;
    private static final int HZ_MAX = 8000;

    // Float-slider mapped ranges.
    private static final float CONTRAST_MIN = 0.5f, CONTRAST_MAX = 2.0f;
    private static final float SMOOTH_MIN = 0.05f, SMOOTH_MAX = 0.6f;
    private static final int FLOAT_STEPS = 1000; // SeekBar resolution for the float sliders

    private static final String[] BAND_LABELS = {"Bass", "Voice", "Presence", "Highs"};

    /** Preset swatches for the per-band color chooser. */
    private static final int[] PRESET_COLORS = {
            0xFFFF4438, 0xFFFF8A3D, 0xFFFBBF24, 0xFF35F6BF,
            0xFF22D3EE, 0xFF8A8A94, 0xFF8A8A94, 0xFFA78BFA,
            0xFFFF4438, 0xFFF4F4F5, 0xFFC4C4CE, 0xFF52525B,
    };

    @Nullable private TapeWaveformStyle style;
    @Nullable private Listener listener;

    // Live references needed by dialogs / cross-updates.
    @Nullable private View[] bandSwatches;
    @Nullable private TextView[] bandLaneButtons;
    @Nullable private TextView lowValue, presValue, highValue;

    /**
     * Bind the live style right after construction (fragment args can't carry a mutable object;
     * this sheet is transient so a plain field is fine).
     */
    @NonNull
    public static WaveformVisualizerSettingsSheet newInstance(@NonNull TapeWaveformStyle style) {
        WaveformVisualizerSettingsSheet s = new WaveformVisualizerSettingsSheet();
        s.style = style;
        return s;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            BottomSheetDialog bsd = (BottomSheetDialog) dialog;
            View bs = bsd.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bs != null) {
                bs.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
                int cap = (int) (getResources().getDisplayMetrics().heightPixels * 0.78);
                ViewGroup.LayoutParams lp = bs.getLayoutParams();
                lp.height = cap;
                bs.setLayoutParams(lp);
                com.google.android.material.bottomsheet.BottomSheetBehavior<View> beh =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(bs);
                beh.setPeekHeight(cap);
                beh.setFitToContents(true);
                beh.setSkipCollapsed(true);
                beh.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (style == null) {
            dismissAllowingStateLoss();
            return new View(requireContext());
        }
        final float dp = getResources().getDisplayMetrics().density;

        androidx.core.widget.NestedScrollView scroll =
                new androidx.core.widget.NestedScrollView(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (20 * dp), (int) (8 * dp), (int) (20 * dp), (int) (28 * dp));
        scroll.addView(root);

        // Grabber handle.
        View grabber = new View(requireContext());
        grabber.setBackgroundColor(0xFF52525B);
        LinearLayout.LayoutParams grabLp = new LinearLayout.LayoutParams(
                (int) (40 * dp), (int) (4 * dp));
        grabLp.gravity = Gravity.CENTER_HORIZONTAL;
        grabLp.bottomMargin = (int) (8 * dp);
        grabber.setLayoutParams(grabLp);
        root.addView(grabber);

        TextView title = new TextView(requireContext());
        title.setText("Waveform visualizer");
        title.setTextColor(0xFFF4F4F5);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, (int) (8 * dp));
        root.addView(title);

        // ── Analysis timing ──────────────────────────────────────────
        sectionHeader(root, dp, "Analysis");
        final CheckBox eager = new CheckBox(requireContext());
        eager.setText("Analyze waveforms immediately");
        eager.setTextColor(0xFFC4C4CE);
        eager.setTextSize(14);
        eager.setChecked(style.analyzeEager);
        root.addView(eager);
        final TextView eagerHint = new TextView(requireContext());
        eagerHint.setTextColor(0xFF8A8A94);
        eagerHint.setTextSize(12);
        eagerHint.setText(style.analyzeEager
                ? "Analyzed at import." : "Analyzed when a clip is first opened.");
        eagerHint.setPadding((int) (2 * dp), 0, 0, (int) (6 * dp));
        root.addView(eagerHint);
        eager.setOnCheckedChangeListener((b, checked) -> {
            if (style == null) return;
            style.analyzeEager = checked;
            eagerHint.setText(checked
                    ? "Analyzed at import." : "Analyzed when a clip is first opened.");
            commit(false); // timing only — no re-extract / re-shape needed
        });

        // ── Crossovers ───────────────────────────────────────────────
        sectionHeader(root, dp, "Crossovers");

        lowValue = new TextView(requireContext());
        presValue = new TextView(requireContext());
        highValue = new TextView(requireContext());

        SeekBar lowBar = hzRow(root, dp, "Bass / Voice", style.lowHz, lowValue);
        SeekBar presBar = hzRow(root, dp, "Voice / Presence", style.presHz, presValue);
        SeekBar highBar = hzRow(root, dp, "Presence / Highs", style.highHz, highValue);

        lowBar.setOnSeekBarChangeListener(new SimpleSeek(() -> {
            if (style == null) return;
            int v = HZ_MIN + lowBar.getProgress();
            // keep low < pres (leave a 10 Hz guard)
            if (v >= style.presHz) { v = Math.max(HZ_MIN, style.presHz - 10); lowBar.setProgress(v - HZ_MIN); }
            style.lowHz = v;
            lowValue.setText(hz(v));
            commit(true);
        }));
        presBar.setOnSeekBarChangeListener(new SimpleSeek(() -> {
            if (style == null) return;
            int v = HZ_MIN + presBar.getProgress();
            if (v <= style.lowHz) { v = style.lowHz + 10; presBar.setProgress(v - HZ_MIN); }
            if (v >= style.highHz) { v = style.highHz - 10; presBar.setProgress(v - HZ_MIN); }
            style.presHz = v;
            presValue.setText(hz(v));
            commit(true);
        }));
        highBar.setOnSeekBarChangeListener(new SimpleSeek(() -> {
            if (style == null) return;
            int v = HZ_MIN + highBar.getProgress();
            if (v <= style.presHz) { v = Math.min(HZ_MAX, style.presHz + 10); highBar.setProgress(v - HZ_MIN); }
            style.highHz = v;
            highValue.setText(hz(v));
            commit(true);
        }));

        final CheckBox presence = new CheckBox(requireContext());
        presence.setText("Presence band");
        presence.setTextColor(0xFFC4C4CE);
        presence.setTextSize(14);
        presence.setChecked(style.presenceOn);
        root.addView(presence);
        presence.setOnCheckedChangeListener((b, checked) -> {
            if (style == null) return;
            style.presenceOn = checked;
            commit(true); // presence toggle re-splits the bands → re-extract
        });

        // ── Per-band rows ────────────────────────────────────────────
        sectionHeader(root, dp, "Bands");
        bandSwatches = new View[BAND_LABELS.length];
        bandLaneButtons = new TextView[BAND_LABELS.length];
        for (int i = 0; i < BAND_LABELS.length; i++) {
            buildBandRow(root, dp, i);
        }

        // ── Normalize ────────────────────────────────────────────────
        sectionHeader(root, dp, "Normalize");
        final CheckBox norm = new CheckBox(requireContext());
        norm.setText("Normalize each band independently");
        norm.setTextColor(0xFFC4C4CE);
        norm.setTextSize(14);
        norm.setChecked(style.perBandNormalize);
        root.addView(norm);
        final TextView normHint = new TextView(requireContext());
        normHint.setTextColor(0xFF8A8A94);
        normHint.setTextSize(12);
        normHint.setText(style.perBandNormalize
                ? "Each band fills its own height." : "Bands keep relative loudness.");
        normHint.setPadding((int) (2 * dp), 0, 0, (int) (6 * dp));
        root.addView(normHint);
        norm.setOnCheckedChangeListener((b, checked) -> {
            if (style == null) return;
            style.perBandNormalize = checked;
            normHint.setText(checked
                    ? "Each band fills its own height." : "Bands keep relative loudness.");
            commit(false); // re-shape only
        });

        // ── Shaping ──────────────────────────────────────────────────
        sectionHeader(root, dp, "Shaping");
        final TextView contrastValue = new TextView(requireContext());
        SeekBar contrastBar = floatRow(root, dp, "Contrast",
                style.contrast, CONTRAST_MIN, CONTRAST_MAX, contrastValue);
        contrastBar.setOnSeekBarChangeListener(new SimpleSeek(() -> {
            if (style == null) return;
            style.contrast = fromProgress(contrastBar.getProgress(), CONTRAST_MIN, CONTRAST_MAX);
            contrastValue.setText(f2(style.contrast));
            commit(false);
        }));

        final TextView smoothValue = new TextView(requireContext());
        SeekBar smoothBar = floatRow(root, dp, "Smooth",
                style.smooth, SMOOTH_MIN, SMOOTH_MAX, smoothValue);
        smoothBar.setOnSeekBarChangeListener(new SimpleSeek(() -> {
            if (style == null) return;
            style.smooth = fromProgress(smoothBar.getProgress(), SMOOTH_MIN, SMOOTH_MAX);
            smoothValue.setText(f2(style.smooth));
            commit(false);
        }));

        // ── FX ───────────────────────────────────────────────────────
        sectionHeader(root, dp, "Effects");
        fxCheck(root, "Baseline glow", style.fxGlow, checked -> { if (style != null) { style.fxGlow = checked; commit(false); } });
        fxCheck(root, "Rounded", style.fxRound, checked -> { if (style != null) { style.fxRound = checked; commit(false); } });
        fxCheck(root, "Peak sparks", style.fxSparks, checked -> { if (style != null) { style.fxSparks = checked; commit(false); } });
        fxCheck(root, "Overlap tint", style.fxTint, checked -> { if (style != null) { style.fxTint = checked; commit(false); } });

        return scroll;
    }

    // ── Row builders ─────────────────────────────────────────────────

    private void sectionHeader(@NonNull LinearLayout root, float dp, @NonNull String text) {
        TextView h = new TextView(requireContext());
        h.setText(text.toUpperCase(java.util.Locale.US));
        h.setTextColor(0xFF8A8A94);
        h.setTextSize(12);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(0, (int) (14 * dp), 0, (int) (4 * dp));
        root.addView(h);
    }

    /** A labelled Hz SeekBar (60..8000). Returns the SeekBar so the caller wires the listener. */
    @NonNull
    private SeekBar hzRow(@NonNull LinearLayout root, float dp, @NonNull String label,
                          int initialHz, @NonNull TextView valueView) {
        LinearLayout labelRow = new LinearLayout(requireContext());
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        labelRow.setPadding(0, (int) (2 * dp), 0, 0);
        root.addView(labelRow);

        TextView l = new TextView(requireContext());
        l.setText(label);
        l.setTextColor(0xFFC4C4CE);
        l.setTextSize(14);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelRow.addView(l);

        valueView.setTextColor(0xFF8A8A94);
        valueView.setTextSize(13);
        valueView.setTypeface(null, Typeface.BOLD);
        valueView.setText(hz(initialHz));
        labelRow.addView(valueView);

        SeekBar bar = new SeekBar(requireContext());
        bar.setMax(HZ_MAX - HZ_MIN);
        bar.setProgress(clampInt(initialHz, HZ_MIN, HZ_MAX) - HZ_MIN);
        tintSeek(bar);
        root.addView(bar);
        return bar;
    }

    /** A labelled float SeekBar mapped onto [min,max] with FLOAT_STEPS resolution. */
    @NonNull
    private SeekBar floatRow(@NonNull LinearLayout root, float dp, @NonNull String label,
                             float initial, float min, float max, @NonNull TextView valueView) {
        LinearLayout labelRow = new LinearLayout(requireContext());
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        labelRow.setPadding(0, (int) (2 * dp), 0, 0);
        root.addView(labelRow);

        TextView l = new TextView(requireContext());
        l.setText(label);
        l.setTextColor(0xFFC4C4CE);
        l.setTextSize(14);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelRow.addView(l);

        valueView.setTextColor(0xFF8A8A94);
        valueView.setTextSize(13);
        valueView.setTypeface(null, Typeface.BOLD);
        valueView.setText(f2(initial));
        labelRow.addView(valueView);

        SeekBar bar = new SeekBar(requireContext());
        bar.setMax(FLOAT_STEPS);
        bar.setProgress(toProgress(initial, min, max));
        tintSeek(bar);
        root.addView(bar);
        return bar;
    }

    private void buildBandRow(@NonNull LinearLayout root, float dp, final int band) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
        root.addView(row);

        // color swatch
        View swatch = new View(requireContext());
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams((int) (28 * dp), (int) (28 * dp));
        swLp.setMarginEnd((int) (12 * dp));
        swatch.setLayoutParams(swLp);
        applySwatch(swatch, style.bandColor[band]);
        swatch.setOnClickListener(v -> showColorChooser(band));
        row.addView(swatch);
        if (bandSwatches != null) bandSwatches[band] = swatch;

        // label
        TextView l = new TextView(requireContext());
        l.setText(BAND_LABELS[band]);
        l.setTextColor(0xFFC4C4CE);
        l.setTextSize(15);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(l);

        // lane toggle (▲ top = -1, ▼ below = +1)
        TextView lane = new TextView(requireContext());
        lane.setTextSize(16);
        lane.setTypeface(null, Typeface.BOLD);
        lane.setPadding((int) (10 * dp), (int) (4 * dp), (int) (10 * dp), (int) (4 * dp));
        LinearLayout.LayoutParams laneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        laneLp.setMarginEnd((int) (8 * dp));
        lane.setLayoutParams(laneLp);
        updateLaneButton(lane, style.bandLane[band]);
        lane.setOnClickListener(v -> {
            if (style == null) return;
            style.bandLane[band] = (style.bandLane[band] <= 0) ? +1 : -1;
            updateLaneButton(lane, style.bandLane[band]);
            commit(false); // lane is a layout/redraw change, not a re-extract
        });
        row.addView(lane);
        if (bandLaneButtons != null) bandLaneButtons[band] = lane;

        // on/off
        CheckBox on = new CheckBox(requireContext());
        on.setChecked(style.bandOn[band]);
        on.setOnCheckedChangeListener((b, checked) -> {
            if (style == null) return;
            style.bandOn[band] = checked;
            commit(false);
        });
        row.addView(on);
    }

    private void fxCheck(@NonNull LinearLayout root, @NonNull String label, boolean initial,
                         @NonNull BoolSink sink) {
        CheckBox cb = new CheckBox(requireContext());
        cb.setText(label);
        cb.setTextColor(0xFFC4C4CE);
        cb.setTextSize(14);
        cb.setChecked(initial);
        cb.setOnCheckedChangeListener((b, checked) -> sink.set(checked));
        root.addView(cb);
    }

    // ── Color chooser (preset grid in a dialog) ──────────────────────

    private void showColorChooser(final int band) {
        if (style == null) return;
        final float dp = getResources().getDisplayMetrics().density;
        android.widget.GridLayout grid = new android.widget.GridLayout(requireContext());
        grid.setColumnCount(4);
        int pad = (int) (16 * dp);
        grid.setPadding(pad, pad, pad, pad);

        final Dialog[] holder = new Dialog[1];
        for (final int color : PRESET_COLORS) {
            View cell = new View(requireContext());
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = (int) (44 * dp);
            lp.height = (int) (44 * dp);
            lp.setMargins((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
            cell.setLayoutParams(lp);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            if (color == style.bandColor[band]) d.setStroke((int) (3 * dp), 0xFFF4F4F5);
            else d.setStroke((int) (1 * dp), 0xFF33333C);
            cell.setBackground(d);
            cell.setOnClickListener(v -> {
                if (style == null) return;
                style.bandColor[band] = color;
                if (bandSwatches != null && bandSwatches[band] != null) {
                    applySwatch(bandSwatches[band], color);
                }
                commit(false); // color is a redraw-only change
                if (holder[0] != null) holder[0].dismiss();
            });
            grid.addView(cell);
        }

        holder[0] = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(BAND_LABELS[band] + " color")
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── Commit / persist ─────────────────────────────────────────────

    /** Persist the style and notify the host. {@code crossoversChanged} → host must re-extract. */
    private void commit(boolean crossoversChanged) {
        if (style == null) return;
        SharedPreferences p =
                android.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
        style.saveTo(p);
        if (listener != null) listener.onStyleChanged(crossoversChanged);
    }

    // ── Small helpers ────────────────────────────────────────────────

    private void applySwatch(@NonNull View swatch, int color) {
        float dp = getResources().getDisplayMetrics().density;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(6 * dp);
        d.setColor(color);
        d.setStroke((int) (1 * dp), 0xFF33333C);
        swatch.setBackground(d);
    }

    private void updateLaneButton(@NonNull TextView lane, int laneVal) {
        boolean top = laneVal <= 0; // -1 = top lane
        lane.setText(top ? "▲" : "▼"); // ▲ / ▼
        lane.setTextColor(top ? 0xFF8A8A94 : 0xFFFBBF24);
    }

    private void tintSeek(@NonNull SeekBar bar) {
        android.content.res.ColorStateList active =
                android.content.res.ColorStateList.valueOf(0xFF22D3EE);
        bar.setProgressTintList(active);
        bar.setThumbTintList(active);
        bar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF33333C));
    }

    private static int toProgress(float v, float min, float max) {
        float t = (v - min) / (max - min);
        return clampInt(Math.round(t * FLOAT_STEPS), 0, FLOAT_STEPS);
    }

    private static float fromProgress(int progress, float min, float max) {
        return min + (max - min) * (progress / (float) FLOAT_STEPS);
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String hz(int v) {
        return v + " Hz";
    }

    private static String f2(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    /** Fire an action on progress change (from the user) and on stop; debounces nothing heavy. */
    private static final class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        private final Runnable onChange;
        SimpleSeek(Runnable onChange) { this.onChange = onChange; }
        @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) onChange.run();
        }
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) { onChange.run(); }
    }

    private interface BoolSink { void set(boolean value); }
}
