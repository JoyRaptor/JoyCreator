package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.slider.Slider;

/**
 * Material bottom sheet for controlling playback speed with a slider.
 *
 * <p>Range: 0.25x – 4.0x (practical range), with logarithmic feel.
 * Shows a prominent speed display and preset buttons for common speeds.</p>
 */
public class SpeedSliderBottomSheet extends BottomSheetDialogFragment {

    /** Callback for speed changes. */
    public interface Callback {
        void onSpeedChanged(float speed);
        /** Called when the user toggles pitch compensation. Default no-op for existing callers. */
        default void onPitchCompensationChanged(boolean enabled) {}
        /**
         * Called ONCE when the sheet is dismissed — the COMMIT point for the final speed/pitch.
         * {@link #onSpeedChanged} fires per slider tick (a live preview); listeners that must do
         * heavier finalize work (e.g. re-baking a playback snapshot) should do it here, not there.
         * Default no-op for existing callers.
         */
        default void onSpeedCommitted() {}
    }

    @Nullable
    private Callback callback;
    private float currentSpeed = 1.0f;
    private boolean pitchCompensation = true;

    /** Default pitch compensation state (maintain pitch when speed changes). */
    private static final String ARG_PITCH = "pitch";

    /** Common preset speeds. */
    private static final float[] PRESETS = {0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 6.0f, 8.0f, 10.0f};

    /**
     * Create a new instance with the current speed.
     *
     * @param speed current playback speed
     * @return new instance
     */
    public static SpeedSliderBottomSheet newInstance(float speed) {
        return newInstance(speed, true);
    }

    public static SpeedSliderBottomSheet newInstance(float speed, boolean pitchCompensation) {
        SpeedSliderBottomSheet sheet = new SpeedSliderBottomSheet();
        Bundle args = new Bundle();
        args.putFloat("speed", speed);
        args.putBoolean(ARG_PITCH, pitchCompensation);
        sheet.setArguments(args);
        return sheet;
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        // COMMIT point: the sheet is closing, so the last onSpeedChanged/onPitchCompensationChanged
        // already mutated the model to its final value. Fire the one-shot commit callback so heavy
        // finalize work runs exactly once (never per slider tick).
        if (callback != null) callback.onSpeedCommitted();
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        SheetKit.install(dialog, null);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (getArguments() != null) {
            currentSpeed = getArguments().getFloat("speed", 1.0f);
            pitchCompensation = getArguments().getBoolean(ARG_PITCH, true);
        }

        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = SheetKit.icons(requireContext());

        LinearLayout outer = new LinearLayout(requireContext());
        outer.setOrientation(LinearLayout.VERTICAL);

        // -- Header: title + the live value --------------------------------
        SheetKit.Header header = SheetKit.header(requireContext(),
                getString(R.string.faditor_speed_title), null);
        TextView speedDisplay = new TextView(requireContext());
        speedDisplay.setTextSize(TypedValue.COMPLEX_UNIT_SP, SheetKit.TITLE_SP);
        com.fadcam.ui.type.Type.mono(speedDisplay, com.fadcam.ui.type.Type.SEMIBOLD);
        updateSpeedDisplay(speedDisplay, currentSpeed);
        header.addTrailing(speedDisplay);
        outer.addView(header.view);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(SheetKit.dp(requireContext(), SheetKit.ROW_INSET_DP), 0,
                SheetKit.dp(requireContext(), SheetKit.ROW_INSET_DP),
                SheetKit.dp(requireContext(), 16));
        outer.addView(root);

        // -- Slider row -----------------------------------------------------
        LinearLayout sliderRow = new LinearLayout(requireContext());
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        root.addView(sliderRow);

        // Speed icon
        TextView speedIcon = new TextView(requireContext());
        speedIcon.setTypeface(materialIcons);
        speedIcon.setText("speed");
        speedIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        speedIcon.setTextColor(SheetKit.ROW_ICON);
        speedIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (32 * dp), (int) (32 * dp));
        iconLp.setMarginEnd((int) (6 * dp));
        speedIcon.setLayoutParams(iconLp);
        sliderRow.addView(speedIcon);

        // Material Slider (use step-free for smooth control, 0.25 to 4.0)
        Slider slider = new Slider(new ContextThemeWrapper(requireContext(), R.style.Widget_FadCam_BottomSheetSlider));
        slider.setValueFrom(25f);   // 0.25x * 100
        slider.setValueTo(1000f);  // 10.0x * 100
        slider.setStepSize(5f);    // 0.05x increments
        slider.setValue(Math.max(25f, Math.min(currentSpeed * 100f, 1000f)));
        slider.setContentDescription(getString(R.string.faditor_speed_title));

        int trackColor = Math.abs(currentSpeed - 1f) < 0.01f ? Studio.INK_FAINT : Studio.GO;
        slider.setTrackActiveTintList(
                android.content.res.ColorStateList.valueOf(trackColor));
        slider.setThumbTintList(
                android.content.res.ColorStateList.valueOf(trackColor));
        slider.setTrackInactiveTintList(
                android.content.res.ColorStateList.valueOf(Studio.OFF));

        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slider.setLayoutParams(sliderLp);
        sliderRow.addView(slider);

        // -- Preset chips ---------------------------------------------------
        android.widget.HorizontalScrollView chipScroll =
                new android.widget.HorizontalScrollView(requireContext());
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setPadding(0, (int) (4 * dp), 0, 0);
        root.addView(chipScroll);

        LinearLayout chipRow = new LinearLayout(requireContext());
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipScroll.addView(chipRow);

        // List of preset TextViews so we can update their selection state
        java.util.List<TextView> chipViews = new java.util.ArrayList<>();
        final LinearLayout[] resetHolder = new LinearLayout[1];

        for (float presetSpeed : PRESETS) {
            TextView chip = SheetKit.chip(requireContext(), formatSpeed(presetSpeed));
            chipViews.add(chip);

            chip.setOnClickListener(v -> {
                currentSpeed = presetSpeed;
                slider.setValue(presetSpeed * 100f);
                updateSpeedDisplay(speedDisplay, presetSpeed);
                updateSliderColor(slider, presetSpeed);
                updateChipSelection(chipViews, presetSpeed);
                if (resetHolder[0] != null) {
                    resetHolder[0].setVisibility(Math.abs(presetSpeed - 1f) < 0.01f ? View.GONE : View.VISIBLE);
                }
                if (callback != null) callback.onSpeedChanged(presetSpeed);
            });

            chipRow.addView(chip);
        }
        updateChipSelection(chipViews, currentSpeed);

        // -- Pitch compensation toggle --------------------------------------
        LinearLayout pitchRow = new LinearLayout(requireContext());
        pitchRow.setOrientation(LinearLayout.HORIZONTAL);
        pitchRow.setGravity(Gravity.CENTER_VERTICAL);
        pitchRow.setPadding(0, (int) (8 * dp), 0, 0);
        root.addView(pitchRow);

        android.widget.CheckBox pitchCheck = new android.widget.CheckBox(requireContext());
        pitchCheck.setChecked(pitchCompensation);
        pitchCheck.setTextColor(SheetKit.ROW_LABEL);
        pitchCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, SheetKit.ROW_SP);
        com.fadcam.ui.type.Type.body(pitchCheck, com.fadcam.ui.type.Type.REGULAR);
        pitchCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(Studio.ARMED));
        pitchCheck.setText("Maintain pitch");
        SheetKit.label(pitchCheck, getString(R.string.lane_d_pitch_tip));
        pitchCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            pitchCompensation = isChecked;
            if (callback != null) callback.onPitchCompensationChanged(isChecked);
        });
        pitchRow.addView(pitchCheck);

        // -- Reset button (consistent row style) ----------------------------
        SheetKit.Row reset = SheetKit.row(requireContext(), "refresh", "Reset", false,
                SheetKit.Trail.NONE, null);
        SheetKit.tintRow(reset, Studio.DANGER, true);
        LinearLayout resetRow = reset.view;
        SheetKit.flush(resetRow, 10);
        resetHolder[0] = resetRow;

        resetRow.setOnClickListener(v -> {
            currentSpeed = 1.0f;
            slider.setValue(100f);
            updateSpeedDisplay(speedDisplay, 1.0f);
            updateSliderColor(slider, 1.0f);
            updateChipSelection(chipViews, 1.0f);
            resetRow.setVisibility(View.GONE);
            if (callback != null) callback.onSpeedChanged(1.0f);
        });

        // Only show reset if speed != 1.0
        resetRow.setVisibility(Math.abs(currentSpeed - 1f) < 0.01f ? View.GONE : View.VISIBLE);
        root.addView(resetRow);

        // -- Slider listener ------------------------------------------------
        slider.addOnChangeListener((sl, value, fromUser) -> {
            if (!fromUser) return;
            float speed = value / 100f;
            currentSpeed = speed;
            updateSpeedDisplay(speedDisplay, speed);
            updateSliderColor(slider, speed);
            updateChipSelection(chipViews, speed);
            resetRow.setVisibility(Math.abs(speed - 1f) < 0.01f ? View.GONE : View.VISIBLE);
            if (callback != null) callback.onSpeedChanged(speed);
        });

        return SheetKit.fitNavBar(outer);
    }

    private void updateSpeedDisplay(TextView tv, float speed) {
        tv.setText(formatSpeed(speed));
        tv.setTextColor(Math.abs(speed - 1f) < 0.01f ? Studio.INK_FAINT : Studio.GO);
    }

    private void updateSliderColor(Slider slider, float speed) {
        int color = Math.abs(speed - 1f) < 0.01f ? Studio.INK_FAINT : Studio.GO;
        slider.setTrackActiveTintList(
                android.content.res.ColorStateList.valueOf(color));
        slider.setThumbTintList(
                android.content.res.ColorStateList.valueOf(color));
    }

    private void updateChipSelection(java.util.List<TextView> chips, float speed) {
        for (int i = 0; i < chips.size(); i++) {
            boolean selected = Math.abs(PRESETS[i] - speed) < 0.01f;
            SheetKit.setChipSelected(chips.get(i), selected);
            // Numbers stay in the mono so the row does not shift as the selection moves.
            com.fadcam.ui.type.Type.mono(chips.get(i), selected
                    ? com.fadcam.ui.type.Type.SEMIBOLD : com.fadcam.ui.type.Type.MEDIUM);
        }
    }

    private static String formatSpeed(float speed) {
        if (speed == (int) speed) {
            return (int) speed + "x";
        }
        return String.format(java.util.Locale.US, "%.2gx", speed);
    }
}
