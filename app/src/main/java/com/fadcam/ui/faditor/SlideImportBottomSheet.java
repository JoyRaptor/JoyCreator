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
 * Bottom sheet for the API-less AI-slide flow: copy a contract-teaching prompt
 * to hand any external chatbot, then bring the HTML it writes back in via paste
 * or file import. Both entries feed the same HTML→MP4 render pipeline as the
 * in-app {@code generate_slide} tool. Styled after {@link AddAssetBottomSheet}.
 */
public class SlideImportBottomSheet extends BottomSheetDialogFragment {

    /** Callback for the three slide-import actions. */
    public interface Callback {
        /** Copy the external-chatbot slide prompt to the clipboard. */
        void onCopyPrompt();

        /** Import slide HTML from the clipboard. */
        void onPasteHtml();

        /** Import slide HTML from a picked file. */
        void onImportFile();
    }

    @Nullable
    private Callback callback;

    public static SlideImportBottomSheet newInstance() {
        return new SlideImportBottomSheet();
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
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
            }
        });
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * dp), 0, (int) (24 * dp));

        // TODO(strings)
        TextView title = new TextView(requireContext());
        title.setText("AI slide");
        title.setTextColor(Studio.INK);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (20 * dp), (int) (4 * dp));
        root.addView(title);

        // TODO(strings)
        TextView subtitle = new TextView(requireContext());
        subtitle.setText("Have any AI chatbot design an animated slide: copy the "
                + "prompt, send it, then paste back the HTML it writes.");
        subtitle.setTextColor(Studio.INK_FAINT);
        subtitle.setTextSize(13);
        subtitle.setPadding((int) (20 * dp), 0, (int) (20 * dp), (int) (14 * dp));
        root.addView(subtitle);

        // TODO(strings)
        root.addView(createOptionRow("Copy slide prompt", "content_copy",
                materialIcons, dp,
                () -> { if (callback != null) callback.onCopyPrompt(); }));

        // TODO(strings)
        root.addView(createOptionRow("Paste slide HTML", "content_paste",
                materialIcons, dp,
                () -> { if (callback != null) callback.onPasteHtml(); }));

        // TODO(strings)
        root.addView(createOptionRow("Import .html file", "upload_file",
                materialIcons, dp,
                () -> { if (callback != null) callback.onImportFile(); }));

        NestedScrollView scroll = new NestedScrollView(requireContext());
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private View createOptionRow(String label, String icon,
                                 @Nullable Typeface materialIcons, float dp,
                                 @NonNull Runnable onClick) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        int hPad = (int) (20 * dp);
        int vPad = (int) (14 * dp);
        row.setPadding(hPad, vPad, hPad, vPad);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins((int) (12 * dp), (int) (2 * dp), (int) (12 * dp), (int) (2 * dp));
        row.setLayoutParams(rowLp);

        TextView iconView = new TextView(requireContext());
        iconView.setTypeface(materialIcons);
        iconView.setText(icon);
        iconView.setTextSize(20);
        iconView.setTextColor(Studio.INK_FAINT);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (28 * dp), (int) (28 * dp));
        iconLp.setMarginEnd((int) (16 * dp));
        iconView.setLayoutParams(iconLp);
        iconView.setGravity(Gravity.CENTER);
        row.addView(iconView);

        TextView labelView = new TextView(requireContext());
        labelView.setText(label);
        labelView.setTextSize(15);
        labelView.setTextColor(Studio.INK_DIM);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelView.setLayoutParams(labelLp);
        row.addView(labelView);

        TextView arrow = new TextView(requireContext());
        arrow.setTypeface(materialIcons);
        arrow.setText("chevron_right");
        arrow.setTextSize(18);
        arrow.setTextColor(Studio.INK_OFF);
        arrow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                (int) (24 * dp), (int) (24 * dp));
        arrow.setLayoutParams(arrowLp);
        row.addView(arrow);

        row.setOnClickListener(v -> {
            onClick.run();
            dismiss();
        });

        return row;
    }
}
