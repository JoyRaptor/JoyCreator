package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet that explains Faditor Mini's features and behaviour.
 *
 * <p>Sections cover: how it works, smart fMP4→MP4 conversion, export naming,
 * temporary files, and recent projects management. Uses the same dark gradient
 * styling as other FadCam bottom sheets.</p>
 */
public class FaditorInfoBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "FaditorInfoBottomSheet";

    /** Factory method. */
    @NonNull
    public static FaditorInfoBottomSheet newInstance() {
        return new FaditorInfoBottomSheet();
    }

    // ── Theme & dark styling ─────────────────────────────────────────

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

    // ── View creation ────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Root layout
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(requireContext(), 20));

        // ── Header (title + close button) ───────────────────────
        SheetKit.Header header = SheetKit.header(requireContext(),
                getString(R.string.faditor_info_title), null);
        header.addTrailing(SheetKit.closeButton(requireContext(), this::dismiss));
        root.addView(header.view);

        // Subtitle
        root.addView(SheetKit.subtitle(requireContext(), getString(R.string.faditor_info_subtitle)));

        // ── Scrollable content ──────────────────────────────────
        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content);
        root.addView(scrollView);

        // ── Info sections ───────────────────────────────────────
        // One grey for every glyph: these mark topics, they are not states, and five
        // colours on one card stack was colour spent on nothing ("greys fade, colours guide").
        addInfoSection(content, "movie_edit",
                R.string.faditor_info_how_title,
                R.string.faditor_info_how_desc);

        addInfoSection(content, "sync",
                R.string.faditor_info_convert_title,
                R.string.faditor_info_convert_desc);

        addInfoSection(content, "save",
                R.string.faditor_info_export_title,
                R.string.faditor_info_export_desc);

        addInfoSection(content, "cached",
                R.string.faditor_info_temp_title,
                R.string.faditor_info_temp_desc);

        addInfoSection(content, "history",
                R.string.faditor_info_projects_title,
                R.string.faditor_info_projects_desc);

        return SheetKit.fitNavBar(root);
    }

    // ── Helper: add an info section ──────────────────────────────────

    /**
     * Adds an info card with an icon, title, and description.
     */
    private void addInfoSection(@NonNull LinearLayout parent,
                                @NonNull String iconLigature,
                                int titleRes,
                                int descRes) {
        parent.addView(SheetKit.detailRow(requireContext(), iconLigature, SheetKit.ROW_ICON,
                getString(titleRes), getString(descRes), null, false));
    }
}
