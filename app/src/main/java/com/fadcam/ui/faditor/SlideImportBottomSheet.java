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
        SheetKit.install(dialog, null);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(requireContext(), 16));

        root.addView(SheetKit.header(requireContext(), getString(R.string.studio_slideimport_title), null).view);

        root.addView(SheetKit.subtitle(requireContext(),
                getString(R.string.studio_slideimport_subtitle)));

        root.addView(createOptionRow(getString(R.string.studio_slideimport_copy_prompt), "content_copy",
                () -> { if (callback != null) callback.onCopyPrompt(); }));

        root.addView(createOptionRow(getString(R.string.studio_slideimport_paste_html), "content_paste",
                () -> { if (callback != null) callback.onPasteHtml(); }));

        root.addView(createOptionRow(getString(R.string.studio_slideimport_import_file), "upload_file",
                () -> { if (callback != null) callback.onImportFile(); }));

        NestedScrollView scroll = new NestedScrollView(requireContext());
        scroll.setFillViewport(true);
        scroll.addView(root);
        return SheetKit.fitNavBar(scroll);
    }

    private View createOptionRow(String label, String icon, @NonNull Runnable onClick) {
        return SheetKit.row(requireContext(), icon, label, false, SheetKit.Trail.CHEVRON, v -> {
            onClick.run();
            dismiss();
        }).view;
    }
}
