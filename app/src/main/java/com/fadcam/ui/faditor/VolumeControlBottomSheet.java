package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import androidx.core.widget.NestedScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.slider.Slider;

/**
 * Material bottom sheet for controlling audio volume with a slider.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Slider 0 – 200% (0.0 – 2.0)</li>
 *   <li>Visual red zone for values above 100% with quality warning</li>
 *   <li>Mute toggle button</li>
 *   <li>Live preview: calls back on every slider change</li>
 * </ul>
 */
public class VolumeControlBottomSheet extends BottomSheetDialogFragment {

    /** Callback for volume / mute changes. */
    public interface Callback {
        void onVolumeChanged(float volume, boolean muted);
    }

    @Nullable
    private Callback callback;
    private float currentVolume = 1.0f;
    private boolean currentMuted = false;

    public static VolumeControlBottomSheet newInstance(float volume, boolean muted) {

        VolumeControlBottomSheet sheet = new VolumeControlBottomSheet();
        Bundle args = new Bundle();
        args.putFloat("volume", volume);
        args.putBoolean("muted", muted);
        sheet.setArguments(args);
        return sheet;
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
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
            currentVolume = getArguments().getFloat("volume", 1.0f);
            currentMuted = getArguments().getBoolean("muted", false);
        }

        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = SheetKit.icons(requireContext());

        LinearLayout outer = new LinearLayout(requireContext());
        outer.setOrientation(LinearLayout.VERTICAL);

        // -- Header: title + the live percentage ----------------------------
        SheetKit.Header header = SheetKit.header(requireContext(),
                getString(R.string.faditor_volume_title), null);
        TextView percentText = new TextView(requireContext());
        percentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, SheetKit.TITLE_SP);
        com.fadcam.ui.type.Type.mono(percentText, com.fadcam.ui.type.Type.SEMIBOLD);
        updatePercentText(percentText, currentVolume, currentMuted);
        header.addTrailing(percentText);
        outer.addView(header.view);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(SheetKit.dp(requireContext(), SheetKit.ROW_INSET_DP), 0,
                SheetKit.dp(requireContext(), SheetKit.ROW_INSET_DP),
                SheetKit.dp(requireContext(), 16));
        outer.addView(root);

        // -- Volume icon + slider row ---------------------------------------
        LinearLayout sliderRow = new LinearLayout(requireContext());
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        root.addView(sliderRow);

        // Volume icon (acts as mute toggle)
        TextView volumeIcon = new TextView(requireContext());
        volumeIcon.setTypeface(materialIcons);
        volumeIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        volumeIcon.setGravity(Gravity.CENTER);
        updateVolumeIcon(volumeIcon, currentVolume, currentMuted);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (40 * dp), (int) (40 * dp));
        iconLp.setMarginEnd((int) (4 * dp));
        volumeIcon.setLayoutParams(iconLp);
        volumeIcon.setBackground(SheetKit.pillBackground(requireContext(), Studio.alpha(Studio.RAISED, 0)));
        volumeIcon.setClickable(true);
        volumeIcon.setFocusable(true);
        SheetKit.label(volumeIcon, getString(R.string.faditor_volume_mute));
        SheetKit.press(volumeIcon);
        sliderRow.addView(volumeIcon);

        // Material Slider
        Slider slider = new Slider(new ContextThemeWrapper(requireContext(), R.style.Widget_FadCam_BottomSheetSlider));
        slider.setValueFrom(0f);
        slider.setValueTo(200f); // 0% - 200%
        slider.setStepSize(1f);
        slider.setValue(currentMuted ? 0f : currentVolume * 100f);
        slider.setContentDescription(getString(R.string.faditor_volume_title));

        // Colors: green for normal, red for >100%
        updateSliderColors(slider, currentVolume, currentMuted);

        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slider.setLayoutParams(sliderLp);
        sliderRow.addView(slider);

        // -- Warning text for >100% -----------------------------------------
        TextView warningText = new TextView(requireContext());
        warningText.setText(R.string.faditor_volume_warning);
        warningText.setTextColor(Studio.DANGER);
        warningText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        com.fadcam.ui.type.Type.body(warningText, com.fadcam.ui.type.Type.REGULAR);
        warningText.setPadding((int) (44 * dp), 0, 0, (int) (2 * dp));
        warningText.setVisibility(currentVolume > 1.0f && !currentMuted ? View.VISIBLE : View.GONE);
        root.addView(warningText);

        // -- Mute toggle button ---------------------------------------------
        SheetKit.Row mute = SheetKit.row(requireContext(), "volume_off",
                getString(currentMuted ? R.string.faditor_volume_unmute : R.string.faditor_volume_mute),
                false, SheetKit.Trail.CHECK, null);
        LinearLayout muteRow = mute.view;
        SheetKit.flush(muteRow, 10);
        root.addView(muteRow);
        TextView muteLabel = mute.label;
        paintMute(mute, currentMuted);

        // -- Listeners ------------------------------------------------------
        // Holder for resetRow so lambdas can reference it before creation
        final LinearLayout[] resetRowRef = new LinearLayout[1];

        slider.addOnChangeListener((sl, value, fromUser) -> {
            if (!fromUser) return;
            float vol = value / 100f;
            currentVolume = vol;
            if (currentMuted && vol > 0) {
                currentMuted = false;
            }
            updatePercentText(percentText, vol, currentMuted);
            updateVolumeIcon(volumeIcon, vol, currentMuted);
            updateSliderColors(slider, vol, currentMuted);
            warningText.setVisibility(vol > 1.0f ? View.VISIBLE : View.GONE);
            muteLabel.setText(currentMuted
                    ? R.string.faditor_volume_unmute
                    : R.string.faditor_volume_mute);
            paintMute(mute, currentMuted);
            if (resetRowRef[0] != null) {
                boolean show = currentMuted || Math.abs(vol - 1.0f) > 0.01f;
                resetRowRef[0].setVisibility(show ? View.VISIBLE : View.GONE);
            }

            if (callback != null) callback.onVolumeChanged(vol, false);
        });

        muteRow.setOnClickListener(v -> {
            currentMuted = !currentMuted;
            updatePercentText(percentText, currentVolume, currentMuted);
            updateVolumeIcon(volumeIcon, currentVolume, currentMuted);
            if (currentMuted) {
                slider.setValue(0f);
            } else {
                slider.setValue(currentVolume * 100f);
            }
            updateSliderColors(slider, currentVolume, currentMuted);
            warningText.setVisibility(
                    currentVolume > 1.0f && !currentMuted ? View.VISIBLE : View.GONE);
            muteLabel.setText(currentMuted
                    ? R.string.faditor_volume_unmute
                    : R.string.faditor_volume_mute);
            paintMute(mute, currentMuted);
            if (resetRowRef[0] != null) {
                boolean show = currentMuted || Math.abs(currentVolume - 1.0f) > 0.01f;
                resetRowRef[0].setVisibility(show ? View.VISIBLE : View.GONE);
            }

            if (callback != null) callback.onVolumeChanged(currentVolume, currentMuted);
        });

        // Tap volume icon to toggle mute
        volumeIcon.setOnClickListener(v -> muteRow.performClick());

        // -- Reset row (matches mute row style) -----------------------------
        boolean needsReset = currentMuted || Math.abs(currentVolume - 1.0f) > 0.01f;
        SheetKit.Row reset = SheetKit.row(requireContext(), "refresh", "Reset", false,
                SheetKit.Trail.NONE, null);
        SheetKit.tintRow(reset, Studio.DANGER, true);
        LinearLayout resetRow = reset.view;
        SheetKit.flush(resetRow, 6);
        resetRowRef[0] = resetRow;

        resetRow.setOnClickListener(v -> {
            currentVolume = 1.0f;
            currentMuted = false;
            slider.setValue(100f);
            updatePercentText(percentText, 1.0f, false);
            updateVolumeIcon(volumeIcon, 1.0f, false);
            updateSliderColors(slider, 1.0f, false);
            warningText.setVisibility(View.GONE);
            muteLabel.setText(R.string.faditor_volume_mute);
            paintMute(mute, false);
            resetRow.setVisibility(View.GONE);
            if (callback != null) callback.onVolumeChanged(1.0f, false);
        });

        resetRow.setVisibility(needsReset ? View.VISIBLE : View.GONE);
        root.addView(resetRow);


        NestedScrollView scroll = new NestedScrollView(requireContext());
        scroll.setFillViewport(true);
        scroll.addView(outer);
        return SheetKit.fitNavBar(scroll);
    }

    /**
     * Muted is a DANGER state, not a selection: the whole row and its check go red while the
     * clip is silent, and return to the resting greys when it is not.
     */
    private static void paintMute(@NonNull SheetKit.Row mute, boolean muted) {
        if (muted) {
            SheetKit.tintRow(mute, Studio.DANGER, true);
        } else {
            SheetKit.paintRow(mute, false, false);
        }
        if (mute.trail != null) {
            mute.trail.setText("check");
            mute.trail.setTextColor(Studio.DANGER);
            mute.trail.setVisibility(muted ? View.VISIBLE : View.INVISIBLE);
        }
        SheetKit.label(mute.view, mute.label.getText());   // "Mute" / "Unmute", kept current
    }

    private void updatePercentText(TextView tv, float volume, boolean muted) {
        if (muted) {
            tv.setText(R.string.faditor_tool_muted);
            tv.setTextColor(Studio.DANGER);
        } else {
            int percent = Math.round(volume * 100);
            tv.setText(percent + "%");
            tv.setTextColor(volume > 1.0f ? Studio.DANGER : Studio.INK_DIM);
        }
    }

    private void updateVolumeIcon(TextView icon, float volume, boolean muted) {
        if (muted || volume == 0f) {
            icon.setText("volume_off");
            icon.setTextColor(Studio.DANGER);
        } else if (volume < 0.5f) {
            icon.setText("volume_mute");
            icon.setTextColor(Studio.INK_FAINT);
        } else if (volume <= 1.0f) {
            icon.setText("volume_up");
            icon.setTextColor(Studio.GO);
        } else {
            icon.setText("volume_up");
            icon.setTextColor(Studio.DANGER);
        }
    }

    private void updateSliderColors(Slider slider, float volume, boolean muted) {
        int trackColor;
        int thumbColor;
        if (muted) {
            trackColor = Studio.DANGER;
            thumbColor = Studio.DANGER;
        } else if (volume > 1.0f) {
            trackColor = Studio.DANGER;
            thumbColor = Studio.DANGER;
        } else {
            trackColor = Studio.GO;
            thumbColor = Studio.GO;
        }

        slider.setTrackActiveTintList(
                android.content.res.ColorStateList.valueOf(trackColor));
        slider.setThumbTintList(
                android.content.res.ColorStateList.valueOf(thumbColor));
        slider.setTrackInactiveTintList(
                android.content.res.ColorStateList.valueOf(Studio.OFF));
    }
}
