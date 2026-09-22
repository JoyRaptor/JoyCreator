package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.effects.EffectStack;
import com.fadcam.ui.faditor.effects.GradePresetStore;
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
    @Nullable private LinearLayout savedPresetsRow;

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
        SheetKit.install(dialog, bs -> {
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
        LinearLayout outer = new LinearLayout(requireContext());
        outer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(outer);

        // Header: the grab pill (an explicit grip to pull the panel down) and the title.
        outer.addView(SheetKit.header(requireContext(),
                getString(R.string.faditor_filter_title), null).view);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(SheetKit.dp(requireContext(), SheetKit.ROW_INSET_DP), 0,
                SheetKit.dp(requireContext(), SheetKit.ROW_INSET_DP), (int) (20 * dp));
        outer.addView(root);

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

        // ── "Save as preset…" row ───────────────────────────────────
        SheetKit.Row save = SheetKit.row(requireContext(), "bookmark_add",
                getString(R.string.faditor_filter_preset_save), false, SheetKit.Trail.NONE,
                v -> showSavePresetDialog());
        SheetKit.tintRow(save, Studio.ARMED, true);
        SheetKit.flush(save.view, 8);
        root.addView(save.view);

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
            label.setTextColor(Studio.INK_DIM);
            label.setTextSize(13);
            com.fadcam.ui.type.Type.body(label, com.fadcam.ui.type.Type.REGULAR);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(labelLp);
            labelRow.addView(label);

            TextView value = new TextView(requireContext());
            value.setTextColor(Studio.INK_FAINT);
            value.setTextSize(12);
            com.fadcam.ui.type.Type.mono(value, com.fadcam.ui.type.Type.MEDIUM);
            value.setText(format(spec.g.get(stack)));
            labelRow.addView(value);
            valueTexts[i] = value;

            Slider slider = new Slider(new ContextThemeWrapper(requireContext(),
                    R.style.Widget_FadCam_BottomSheetSlider));
            slider.setValueFrom(spec.min);
            slider.setValueTo(spec.max);
            slider.setValue(clamp(spec.g.get(stack), spec.min, spec.max));
            slider.setContentDescription(spec.label);
            slider.setTrackActiveTintList(
                    android.content.res.ColorStateList.valueOf(Studio.ARMED));
            slider.setThumbTintList(
                    android.content.res.ColorStateList.valueOf(Studio.ARMED));
            slider.setTrackInactiveTintList(
                    android.content.res.ColorStateList.valueOf(Studio.OFF));
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
        div.setBackgroundColor(Studio.LINE);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * dp));
        divLp.topMargin = (int) (12 * dp);
        divLp.bottomMargin = (int) (8 * dp);
        div.setLayoutParams(divLp);
        root.addView(div);

        SheetKit.Row lut = SheetKit.row(requireContext(), "palette",
                getString(R.string.faditor_filter_lut), false, SheetKit.Trail.NONE,
                v -> showLutChooser());
        LinearLayout lutRow = lut.view;
        SheetKit.flush(lutRow, 0);
        root.addView(lutRow);

        // The current LUT's name, trailing: ARMED, because it names what is selected.
        lutValue = new TextView(requireContext());
        lutValue.setTextColor(Studio.ARMED);
        lutValue.setTextSize(13);
        com.fadcam.ui.type.Type.body(lutValue, com.fadcam.ui.type.Type.SEMIBOLD);
        lutValue.setSingleLine(true);
        lutValue.setText(currentLutName());
        lutRow.addView(lutValue);

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
        intLabel.setTextColor(Studio.INK_DIM);
        intLabel.setTextSize(13);
        com.fadcam.ui.type.Type.body(intLabel, com.fadcam.ui.type.Type.REGULAR);
        LinearLayout.LayoutParams intLabelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        intLabel.setLayoutParams(intLabelLp);
        intLabelRow.addView(intLabel);

        lutIntensityValue = new TextView(requireContext());
        lutIntensityValue.setTextColor(Studio.INK_FAINT);
        lutIntensityValue.setTextSize(12);
        com.fadcam.ui.type.Type.mono(lutIntensityValue, com.fadcam.ui.type.Type.MEDIUM);
        lutIntensityValue.setText(format(stack.getLutIntensity()));
        intLabelRow.addView(lutIntensityValue);

        lutIntensitySlider = new Slider(new ContextThemeWrapper(requireContext(),
                R.style.Widget_FadCam_BottomSheetSlider));
        lutIntensitySlider.setValueFrom(0f);
        lutIntensitySlider.setValueTo(1f);
        lutIntensitySlider.setValue(clamp(stack.getLutIntensity(), 0f, 1f));
        lutIntensitySlider.setContentDescription("Intensity");
        lutIntensitySlider.setTrackActiveTintList(
                android.content.res.ColorStateList.valueOf(Studio.ARMED));
        lutIntensitySlider.setThumbTintList(
                android.content.res.ColorStateList.valueOf(Studio.ARMED));
        lutIntensitySlider.setTrackInactiveTintList(
                android.content.res.ColorStateList.valueOf(Studio.OFF));
        lutIntensityRow.addView(lutIntensitySlider);
        lutIntensitySlider.addOnChangeListener((sl, v, fromUser) -> {
            if (!fromUser || stack == null) return;
            stack.setLutIntensity(v);
            if (lutIntensityValue != null) lutIntensityValue.setText(format(v));
            if (callback != null) callback.onEffectsChanged();
        });
        updateLutIntensityVisibility();

        // ── Reset row ────────────────────────────────────────────────
        SheetKit.Row reset = SheetKit.row(requireContext(), "refresh",
                getString(R.string.faditor_filter_reset), false, SheetKit.Trail.NONE,
                v -> resetAll());
        SheetKit.tintRow(reset, Studio.DANGER, true);
        SheetKit.flush(reset.view, 8);
        root.addView(reset.view);

        // ── Copy-to-all row ──────────────────────────────────────────
        SheetKit.Row copy = SheetKit.row(requireContext(), "content_copy",
                getString(R.string.faditor_filter_copy_all), false, SheetKit.Trail.NONE, v -> {
                    if (callback != null) callback.onCopyToAll();
                });
        SheetKit.tintRow(copy, Studio.ARMED, true);
        SheetKit.flush(copy.view, 8);
        root.addView(copy.view);

        return SheetKit.fitNavBar(scroll);
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

    private void showSavePresetDialog() {
        if (stack == null) return;
        float dp = getResources().getDisplayMetrics().density;
        EditText input = new EditText(requireContext());
        input.setHint(R.string.faditor_filter_preset_name_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        int pad = (int) (20 * dp);
        input.setPadding(pad, (int) (8 * dp), pad, 0);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.faditor_filter_preset_save)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    GradePresetStore.save(requireContext(), name, stack);
                    rebuildSavedPresetChips(dp);
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

    /** Sync every slider + value label to the current stack state (after loading a preset). */
    private void refreshSlidersFromStack() {
        if (stack == null || specs == null) return;
        for (int i = 0; i < specs.length; i++) {
            float v = specs[i].g.get(stack);
            if (sliders[i] != null) sliders[i].setValue(clamp(v, specs[i].min, specs[i].max));
            if (valueTexts[i] != null) valueTexts[i].setText(format(v));
        }
        if (lutIntensitySlider != null) lutIntensitySlider.setValue(
                clamp(stack.getLutIntensity(), 0f, 1f));
        if (lutIntensityValue != null) lutIntensityValue.setText(format(stack.getLutIntensity()));
        if (lutValue != null) lutValue.setText(currentLutName());
        updateLutIntensityVisibility();
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
            TextView chip = SheetKit.chip(requireContext(), name);
            chip.setTextColor(Studio.INK);
            chip.setOnClickListener(v -> applyPreset(name));
            row.addView(chip);
        }
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hsLp.bottomMargin = (int) (6 * dp);
        hs.setLayoutParams(hsLp);
        root.addView(hs);

        // ── saved user presets ───────────────────────────────────────
        savedPresetsRow = new LinearLayout(requireContext());
        savedPresetsRow.setOrientation(LinearLayout.VERTICAL);
        root.addView(savedPresetsRow);
        rebuildSavedPresetChips(dp);
    }

    private void rebuildSavedPresetChips(float dp) {
        if (savedPresetsRow == null) return;
        savedPresetsRow.removeAllViews();
        List<String> names = GradePresetStore.listNames(requireContext());
        if (names.isEmpty()) return;

        // Divider above saved presets
        LinearLayout header = SheetKit.sectionLabel(requireContext(),
                getString(R.string.faditor_filter_preset_save), 0);
        header.setPadding(0, (int) (6 * dp), 0, (int) (5 * dp));
        savedPresetsRow.addView(header);

        HorizontalScrollView hs = new HorizontalScrollView(requireContext());
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(requireContext());
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(chipRow);
        for (String name : names) {
            TextView chip = SheetKit.chip(requireContext(), name);
            chip.setTextColor(Studio.ARMED);   // the user's own looks, set apart from the curated ones
            chip.setOnClickListener(v -> applySavedPreset(name));
            chip.setOnLongClickListener(v -> { confirmDeletePreset(name, dp); return true; });
            chipRow.addView(chip);
        }
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hsLp.bottomMargin = (int) (4 * dp);
        hs.setLayoutParams(hsLp);
        savedPresetsRow.addView(hs);
    }

    private void applySavedPreset(@NonNull String name) {
        if (stack == null) return;
        GradePresetStore.load(requireContext(), name, stack);
        refreshSlidersFromStack();
        if (callback != null) callback.onEffectsChanged();
        com.google.android.material.snackbar.Snackbar.make(
                requireView(),
                getString(R.string.faditor_filter_preset_applied, name),
                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
    }

    private void confirmDeletePreset(@NonNull String name, float dp) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(String.format(
                        getString(R.string.faditor_filter_preset_delete), name))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    GradePresetStore.delete(requireContext(), name);
                    rebuildSavedPresetChips(dp);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

