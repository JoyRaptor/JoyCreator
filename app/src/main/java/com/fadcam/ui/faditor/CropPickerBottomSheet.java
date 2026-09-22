package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Material bottom sheet for choosing crop aspect ratio.
 */
public class CropPickerBottomSheet extends BottomSheetDialogFragment {

    /** Callback when user picks a crop preset. */
    public interface Callback {
        void onCropSelected(@NonNull String preset);
    }

    private static final String[][] CROP_OPTIONS = {
        {"none",    "Original",        "crop_free"},
        {"custom",  "Free Crop",       "crop"},
    };

    @Nullable
    private Callback callback;
    @NonNull
    private String currentPreset = "none";

    /**
     * Create a new CropPickerBottomSheet with the given current crop preset.
     *
     * @param currentPreset the currently selected crop preset
     * @return a new instance
     */
    public static CropPickerBottomSheet newInstance(@NonNull String currentPreset) {
        CropPickerBottomSheet sheet = new CropPickerBottomSheet();
        Bundle args = new Bundle();
        args.putString("currentPreset", currentPreset);
        sheet.setArguments(args);
        return sheet;
    }

    /**
     * Set the callback for crop selection events.
     *
     * @param callback the callback to notify
     */
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
            currentPreset = getArguments().getString("currentPreset", "none");
        }

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(requireContext(), 16));

        root.addView(SheetKit.header(requireContext(),
                getString(R.string.faditor_crop_title), null).view);

        for (String[] option : CROP_OPTIONS) {
            String key = option[0];
            String label = option[1];
            String icon = option[2];
            boolean selected = key.equals(currentPreset);

            root.addView(SheetKit.row(requireContext(), icon, label, selected,
                    SheetKit.Trail.CHECK, v -> {
                        if (callback != null) {
                            callback.onCropSelected(key);
                        }
                        dismiss();
                    }).view);
        }

        return SheetKit.fitNavBar(root);
    }
}
