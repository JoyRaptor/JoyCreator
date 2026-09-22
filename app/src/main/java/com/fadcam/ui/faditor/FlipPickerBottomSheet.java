package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.core.widget.NestedScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Material bottom sheet for flip (horizontal/vertical) selection.
 */
public class FlipPickerBottomSheet extends BottomSheetDialogFragment {

    /** Callback when user changes flip state. */
    public interface Callback {
        void onFlipChanged(boolean flipH, boolean flipV);
    }

    @Nullable
    private Callback callback;
    private boolean flipH = false;
    private boolean flipV = false;

    /**
     * Create a new FlipPickerBottomSheet with current flip state.
     *
     * @param flipH whether horizontal flip is active
     * @param flipV whether vertical flip is active
     * @return a new instance
     */
    public static FlipPickerBottomSheet newInstance(boolean flipH, boolean flipV) {
        FlipPickerBottomSheet sheet = new FlipPickerBottomSheet();
        Bundle args = new Bundle();
        args.putBoolean("flipH", flipH);
        args.putBoolean("flipV", flipV);
        sheet.setArguments(args);
        return sheet;
    }

    /**
     * Set the callback for flip changes.
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
            flipH = getArguments().getBoolean("flipH", false);
            flipV = getArguments().getBoolean("flipV", false);
        }

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(requireContext(), 16));

        root.addView(SheetKit.header(requireContext(),
                getString(R.string.faditor_flip_title), null).view);

        // Horizontal flip row
        root.addView(createFlipRow("Flip Horizontal", flipH, true));

        // Vertical flip row
        root.addView(createFlipRow("Flip Vertical", flipV, false));

        // Reset row
        if (flipH || flipV) {
            root.addView(createResetRow());
        }

        NestedScrollView scroll = new NestedScrollView(requireContext());
        scroll.setFillViewport(true);
        scroll.addView(root);
        return SheetKit.fitNavBar(scroll);
    }

    /**
     * Creates a flip option row. Tapping toggles that axis, reports both axes, and closes.
     */
    private View createFlipRow(String label, boolean isActive, boolean isHorizontal) {
        SheetKit.Row row = SheetKit.row(requireContext(), "flip", label, isActive,
                SheetKit.Trail.CHECK, v -> {
                    if (isHorizontal) {
                        flipH = !flipH;
                    } else {
                        flipV = !flipV;
                    }
                    if (callback != null) {
                        callback.onFlipChanged(flipH, flipV);
                    }
                    dismiss();
                });
        // The same mirror glyph, turned a quarter, reads as the vertical axis.
        if (!isHorizontal && row.icon != null) {
            row.icon.setRotation(90);
        }
        return row.view;
    }

    /**
     * Creates a reset row to clear all flips.
     */
    private View createResetRow() {
        SheetKit.Row row = SheetKit.row(requireContext(), "refresh", "Reset", false,
                SheetKit.Trail.NONE, v -> {
                    flipH = false;
                    flipV = false;
                    if (callback != null) {
                        callback.onFlipChanged(false, false);
                    }
                    dismiss();
                });
        SheetKit.tintRow(row, Studio.DANGER, true);
        ((LinearLayout.LayoutParams) row.view.getLayoutParams()).topMargin =
                SheetKit.dp(requireContext(), 8);
        return row.view;
    }
}
