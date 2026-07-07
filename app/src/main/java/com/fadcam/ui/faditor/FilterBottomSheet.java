package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.effects.EffectStack;
import com.fadcam.ui.faditor.effects.LutManager;
import com.fadcam.ui.faditor.effects.LutPreset;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import java.util.List;

/**
 * Color &amp; filter editor for the selected clip's {@link EffectStack}. Edits the stack in place and
 * fires {@link Callback#onEffectsChanged()} live on every change so the host can re-apply the preview
 * effects and persist. Built programmatically (one row per parameter) to avoid a large layout file.
 */
public class FilterBottomSheet extends BottomSheetDialogFragment {

    public interface Callback {
        /** The stack changed — re-apply to the live preview and save. */
        void onEffectsChanged();
        /** Copy this clip's look to every clip on the timeline. */
        default void onCopyToAll() {}
        /** Sheet dismissed — commit one undo entry for the whole grading session. */
        default void onClosed() {}
    }

    private interface Getter { float get(EffectStack s); }
    private interface Setter { void set(EffectStack s, float v); }

    private static final class Spec {
        final String label; final float min, max, def; final Getter g; final Setter s;
        Spec(String label, float min, float max, float def, Getter g, Setter s) {
            this.label = label; this.min = min; this.max = max; this.def = def; this.g = g; this.s = s;
        }
    }

    @Nullable private EffectStack stack;
    @Nullable private Callback callback;

    private Slider[] sliders;
    private TextView[] valueTexts;
    private Spec[] specs;
    @Nullable private TextView lutValue;
    @Nullable private LinearLayout lutIntensityRow;
    @Nullable private Slider lutIntensitySlider;
    @Nullable private TextView lutIntensityValue;

    /** Bind the target stack + callback before showing. */
    public void setTarget(@NonNull EffectStack stack, @NonNull Callback callback) {
        this.stack = stack;
        this.callback = callback;
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        if (callback != null) callback.onClosed();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        // Do NOT dim the screen — the user must see the live video behind the panel while grading.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(0f);
        }
        dialog.setOnShowListener(d -> {
            BottomSheetDialog bsd = (BottomSheetDialog) dialog;
            View bs = bsd.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bs != null) {
                bs.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
                // Cap the panel at ~1/3 of the screen so it never covers the video; the inner
                // ScrollView handles the rest. Fixing the height (peek == expanded) also kills the
                // "drag past fullscreen then it eats the top" bug.
                int third = (int) (getResources().getDisplayMetrics().heightPixels / 3.0);
                ViewGroup.LayoutParams lp = bs.getLayoutParams();
                lp.height = third;
                bs.setLayoutParams(lp);
                com.google.android.material.bottomsheet.BottomSheetBehavior<View> beh =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(bs);
                beh.setPeekHeight(third);
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
        if (stack == null) {
            dismissAllowingStateLoss();
            return new View(requireContext());
        }
        final float dp = getResources().getDisplayMetrics().density;

        // NestedScrollView (not plain ScrollView) so it COORDINATES with the bottom-sheet's nested
        // scrolling: dragging the content scrolls it, and the sheet only drags-to-dismiss once the
        // content is scrolled to the very top — fixing the "swipe-up just dismisses" conflict.
        androidx.core.widget.NestedScrollView scroll =
                new androidx.core.widget.NestedScrollView(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (20 * dp), (int) (8 * dp), (int) (20 * dp), (int) (28 * dp));
        scroll.addView(root);

        // Grabber handle: an explicit grip to pull the panel down to dismiss.
        View grabber = new View(requireContext());
        grabber.setBackgroundColor(0xFF5A5A5A);
        LinearLayout.LayoutParams grabLp = new LinearLayout.LayoutParams(
                (int) (40 * dp), (int) (4 * dp));
        grabLp.gravity = Gravity.CENTER_HORIZONTAL;
        grabLp.bottomMargin = (int) (8 * dp);
        grabber.setLayoutParams(grabLp);
        root.addView(grabber);

        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_filter_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, (int) (8 * dp));
        root.addView(title);

        specs = new Spec[]{
                new Spec("Exposure", -1f, 1f, 0f, EffectStack::getExposure, EffectStack::setExposure),
                new Spec("Contrast", -1f, 1f, 0f, EffectStack::getContrast, EffectStack::setContrast),
                new Spec("Saturation", 0f, 2f, 1f, EffectStack::getSaturation, EffectStack::setSaturation),
                new Spec("Temperature", -1f, 1f, 0f, EffectStack::getTemperature, EffectStack::setTemperature),
                new Spec("Tint", -1f, 1f, 0f, EffectStack::getTint, EffectStack::setTint),
                new Spec("Highlights", -1f, 1f, 0f, EffectStack::getHighlights, EffectStack::setHighlights),
                new Spec("Shadows", -1f, 1f, 0f, EffectStack::getShadows, EffectStack::setShadows),
                new Spec("Fade", -1f, 1f, 0f, EffectStack::getFade, EffectStack::setFade),
                new Spec("Vignette", 0f, 1f, 0f, EffectStack::getVignette, EffectStack::setVignette),
                new Spec("Grain", 0f, 1f, 0f, EffectStack::getGrain, EffectStack::setGrain),
        };
        sliders = new Slider[specs.length];
        valueTexts = new TextView[specs.length];

        buildPresetRow(root, dp);

        for (int i = 0; i < specs.length; i++) {
            final Spec spec = specs[i];
            final int idx = i;

            LinearLayout labelRow = new LinearLayout(requireContext());
            labelRow.setOrientation(LinearLayout.HORIZONTAL);
            labelRow.setGravity(Gravity.CENTER_VERTICAL);
            labelRow.setPadding(0, (int) (2 * dp), 0, 0);
            root.addView(labelRow);

            TextView label = new TextView(requireContext());
            label.setText(spec.label);
            label.setTextColor(0xFFCCCCCC);
            label.setTextSize(14);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(labelLp);
            labelRow.addView(label);

            TextView value = new TextView(requireContext());
            value.setTextColor(0xFF888888);
            value.setTextSize(13);
            value.setTypeface(null, Typeface.BOLD);
            value.setText(format(spec.g.get(stack)));
            labelRow.addView(value);
            valueTexts[i] = value;

            Slider slider = new Slider(new ContextThemeWrapper(requireContext(),
                    R.style.Widget_FadCam_BottomSheetSlider));
            slider.setValueFrom(spec.min);
            slider.setValueTo(spec.max);
            slider.setValue(clamp(spec.g.get(stack), spec.min, spec.max));
            slider.setTrackActiveTintList(
                    android.content.res.ColorStateList.valueOf(0xFF4DD0E1));
            slider.setThumbTintList(
                    android.content.res.ColorStateList.valueOf(0xFF4DD0E1));
            slider.setTrackInactiveTintList(
                    android.content.res.ColorStateList.valueOf(0xFF333333));
            root.addView(slider);
            sliders[i] = slider;

            slider.addOnChangeListener((sl, v, fromUser) -> {
                if (!fromUser || stack == null) return;
                spec.s.set(stack, v);
                valueTexts[idx].setText(format(spec.g.get(stack)));
                if (callback != null) callback.onEffectsChanged();
            });
        }

        // ── LUT row ──────────────────────────────────────────────────
        View div = new View(requireContext());
        div.setBackgroundColor(0xFF2A2A2A);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * dp));
        divLp.topMargin = (int) (14 * dp);
        divLp.bottomMargin = (int) (8 * dp);
        div.setLayoutParams(divLp);
        root.addView(div);

        LinearLayout lutRow = new LinearLayout(requireContext());
        lutRow.setOrientation(LinearLayout.HORIZONTAL);
        lutRow.setGravity(Gravity.CENTER_VERTICAL);
        lutRow.setBackgroundResource(R.drawable.settings_home_row_bg);
        lutRow.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (14 * dp));
        root.addView(lutRow);

        TextView lutLabel = new TextView(requireContext());
        lutLabel.setText(R.string.faditor_filter_lut);
        lutLabel.setTextColor(0xFFCCCCCC);
        lutLabel.setTextSize(15);
        LinearLayout.LayoutParams lutLabelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lutLabel.setLayoutParams(lutLabelLp);
        lutRow.addView(lutLabel);

        lutValue = new TextView(requireContext());
        lutValue.setTextColor(0xFF4DD0E1);
        lutValue.setTextSize(14);
        lutValue.setTypeface(null, Typeface.BOLD);
        lutValue.setText(currentLutName());
        lutRow.addView(lutValue);

        lutRow.setOnClickListener(v -> showLutChooser());

        // ── LUT intensity slider (visible only while a LUT is active) ─
        lutIntensityRow = new LinearLayout(requireContext());
        lutIntensityRow.setOrientation(LinearLayout.VERTICAL);
        root.addView(lutIntensityRow);

        LinearLayout intLabelRow = new LinearLayout(requireContext());
        intLabelRow.setOrientation(LinearLayout.HORIZONTAL);
        intLabelRow.setGravity(Gravity.CENTER_VERTICAL);
        intLabelRow.setPadding(0, (int) (6 * dp), 0, 0);
        lutIntensityRow.addView(intLabelRow);

        TextView intLabel = new TextView(requireContext());
        intLabel.setText("Intensity");
        intLabel.setTextColor(0xFFCCCCCC);
        intLabel.setTextSize(14);
        LinearLayout.LayoutParams intLabelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        intLabel.setLayoutParams(intLabelLp);
        intLabelRow.addView(intLabel);

        lutIntensityValue = new TextView(requireContext());
        lutIntensityValue.setTextColor(0xFF888888);
        lutIntensityValue.setTextSize(13);
        lutIntensityValue.setTypeface(null, Typeface.BOLD);
        lutIntensityValue.setText(format(stack.getLutIntensity()));
        intLabelRow.addView(lutIntensityValue);

        lutIntensitySlider = new Slider(new ContextThemeWrapper(requireContext(),
                R.style.Widget_FadCam_BottomSheetSlider));
        lutIntensitySlider.setValueFrom(0f);
        lutIntensitySlider.setValueTo(1f);
        lutIntensitySlider.setValue(clamp(stack.getLutIntensity(), 0f, 1f));
        lutIntensitySlider.setTrackActiveTintList(
                android.content.res.ColorStateList.valueOf(0xFF4DD0E1));
        lutIntensitySlider.setThumbTintList(
                android.content.res.ColorStateList.valueOf(0xFF4DD0E1));
        lutIntensitySlider.setTrackInactiveTintList(
                android.content.res.ColorStateList.valueOf(0xFF333333));
        lutIntensityRow.addView(lutIntensitySlider);
        lutIntensitySlider.addOnChangeListener((sl, v, fromUser) -> {
            if (!fromUser || stack == null) return;
            stack.setLutIntensity(v);
            if (lutIntensityValue != null) lutIntensityValue.setText(format(v));
            if (callback != null) callback.onEffectsChanged();
        });
        updateLutIntensityVisibility();

        // ── Reset row ────────────────────────────────────────────────
        LinearLayout resetRow = new LinearLayout(requireContext());
        resetRow.setOrientation(LinearLayout.HORIZONTAL);
        resetRow.setGravity(Gravity.CENTER_VERTICAL);
        resetRow.setBackgroundResource(R.drawable.settings_home_row_bg);
        resetRow.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (14 * dp));
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resetLp.topMargin = (int) (8 * dp);
        resetRow.setLayoutParams(resetLp);
        root.addView(resetRow);

        TextView resetLabel = new TextView(requireContext());
        resetLabel.setText(R.string.faditor_filter_reset);
        resetLabel.setTextColor(0xFFF44336);
        resetLabel.setTextSize(15);
        resetLabel.setTypeface(null, Typeface.BOLD);
        resetRow.addView(resetLabel);
        resetRow.setOnClickListener(v -> resetAll());

        // ── Copy-to-all row ──────────────────────────────────────────
        LinearLayout copyRow = new LinearLayout(requireContext());
        copyRow.setOrientation(LinearLayout.HORIZONTAL);
        copyRow.setGravity(Gravity.CENTER_VERTICAL);
        copyRow.setBackgroundResource(R.drawable.settings_home_row_bg);
        copyRow.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (14 * dp));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        copyLp.topMargin = (int) (8 * dp);
        copyRow.setLayoutParams(copyLp);
        root.addView(copyRow);

        TextView copyLabel = new TextView(requireContext());
        copyLabel.setText(R.string.faditor_filter_copy_all);
        copyLabel.setTextColor(0xFF4DD0E1);
        copyLabel.setTextSize(15);
        copyLabel.setTypeface(null, Typeface.BOLD);
        copyRow.addView(copyLabel);
        copyRow.setOnClickListener(v -> {
            if (callback != null) callback.onCopyToAll();
        });

        return scroll;
    }

    private void showLutChooser() {
        if (stack == null) return;
        List<LutPreset> presets = LutManager.builtInPresets();
        CharSequence[] labels = new CharSequence[presets.size() + 1];
        labels[0] = getString(R.string.faditor_filter_lut_none);
        int checked = (!stack.isLutEnabled() || stack.getLutId() == null) ? 0 : -1;
        for (int i = 0; i < presets.size(); i++) {
            labels[i + 1] = presets.get(i).displayName;
            if (presets.get(i).id.equals(stack.getLutId())) checked = i + 1;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.faditor_filter_lut)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    if (which == 0) {
                        stack.setLutEnabled(false);
                        stack.setLutId(null);
                    } else {
                        stack.setLutEnabled(true);
                        stack.setLutId(presets.get(which - 1).id);
                    }
                    if (lutValue != null) lutValue.setText(currentLutName());
                    updateLutIntensityVisibility();
                    if (callback != null) callback.onEffectsChanged();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void resetAll() {
        if (stack == null) return;
        float[] defs = new float[specs.length];
        for (int i = 0; i < specs.length; i++) defs[i] = specs[i].def;
        applyValues(defs, null);
    }

    /** Horizontal row of one-tap look presets above the manual sliders. */
    private void buildPresetRow(@NonNull LinearLayout root, float dp) {
        final String[] names = {"None", "Cinematic", "Warm", "Cool", "Vivid", "Vintage", "Mono"};
        HorizontalScrollView hs = new HorizontalScrollView(requireContext());
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(row);
        for (String name : names) {
            TextView chip = new TextView(requireContext());
            chip.setText(name);
            chip.setTextColor(0xFFEEEEEE);
            chip.setTextSize(13);
            chip.setPadding((int) (14 * dp), (int) (8 * dp), (int) (14 * dp), (int) (8 * dp));
            chip.setBackgroundResource(R.drawable.settings_home_row_bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (8 * dp));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> applyPreset(name));
            row.addView(chip);
        }
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hsLp.bottomMargin = (int) (6 * dp);
        hs.setLayoutParams(hsLp);
        root.addView(hs);
    }

    /** Curated looks. Values order: exposure,contrast,saturation,temperature,tint,highlights,shadows,fade,vignette,grain. */
    private void applyPreset(@NonNull String name) {
        switch (name) {
            case "Cinematic":
                applyValues(new float[]{0f, 0.15f, 0.9f, 0f, 0f, -0.1f, 0.12f, 0.08f, 0.25f, 0f}, "teal_orange");
                break;
            case "Warm":
                applyValues(new float[]{0.05f, 0.1f, 1.05f, 0.35f, 0f, 0f, 0.05f, 0f, 0.1f, 0f}, "warm");
                break;
            case "Cool":
                applyValues(new float[]{0f, 0.1f, 1.0f, -0.3f, 0f, 0f, 0.05f, 0f, 0.1f, 0f}, "cool");
                break;
            case "Vivid":
                applyValues(new float[]{0.05f, 0.2f, 1.3f, 0f, 0f, -0.05f, 0.05f, 0f, 0f, 0f}, "punchy");
                break;
            case "Vintage":
                applyValues(new float[]{0f, -0.05f, 0.8f, 0.15f, 0f, -0.1f, 0.1f, 0.3f, 0.35f, 0.25f}, "desaturated");
                break;
            case "Mono":
                applyValues(new float[]{0f, 0.1f, 0f, 0f, 0f, 0f, 0f, 0f, 0.15f, 0.05f}, null);
                break;
            case "None":
            default:
                applyValues(new float[]{0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f}, null);
                break;
        }
    }

    private void applyValues(@NonNull float[] v, @Nullable String lutId) {
        if (stack == null) return;
        for (int i = 0; i < specs.length && i < v.length; i++) {
            specs[i].s.set(stack, v[i]);
            if (sliders[i] != null) sliders[i].setValue(clamp(v[i], specs[i].min, specs[i].max));
            if (valueTexts[i] != null) valueTexts[i].setText(format(specs[i].g.get(stack)));
        }
        stack.setLutEnabled(lutId != null);
        stack.setLutId(lutId);
        stack.setLutIntensity(1f);
        if (lutIntensitySlider != null) lutIntensitySlider.setValue(1f);
        if (lutIntensityValue != null) lutIntensityValue.setText(format(1f));
        if (lutValue != null) lutValue.setText(currentLutName());
        updateLutIntensityVisibility();
        if (callback != null) callback.onEffectsChanged();
    }

    /** The intensity slider only makes sense while a LUT is selected. */
    private void updateLutIntensityVisibility() {
        if (lutIntensityRow == null || stack == null) return;
        boolean show = stack.isLutEnabled() && stack.getLutId() != null;
        lutIntensityRow.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @NonNull
    private String currentLutName() {
        if (stack == null || !stack.isLutEnabled() || stack.getLutId() == null) {
            return getString(R.string.faditor_filter_lut_none);
        }
        for (LutPreset p : LutManager.builtInPresets()) {
            if (p.id.equals(stack.getLutId())) return p.displayName;
        }
        return stack.getLutId();
    }

    private static String format(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}

