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
 * Material bottom sheet for choosing an asset type to add (image or video).
 * Follows the same design pattern as FlipPickerBottomSheet / CropPickerBottomSheet.
 */
public class AddAssetBottomSheet extends BottomSheetDialogFragment {

    /** Callback when user picks an asset type. */
    public interface Callback {
        /**
         * Called when the user selects image or video.
         *
         * @param isImage true for image, false for video
         */
        void onAssetTypeSelected(boolean isImage);

        /**
         * Called when the user picks "Black clip" — a solid-black still on the SPINE, for a
         * title card, a deliberate pause, or a black intro/outro. Default no-op so existing
         * callers compile unchanged.
         */
        default void onBlankClipSelected() { }

        /** Called when the user selects audio. */
        void onAudioSelected();

        /**
         * Called when the user picks "Add image as new layer" — imports a still
         * image onto a brand-new floating layer track above the master (the
         * reliable, button-driven cross-layer path). Default no-op so existing
         * callers compile unchanged.
         */
        default void onImageAsNewLayerSelected() { }

        /**
         * Called when the user picks "Video overlay (PiP)" — a floating video
         * layer over the master track (M-COMP-2). Default no-op so existing
         * callers compile unchanged.
         */
        default void onOverlayVideoSelected() { }

        /**
         * Called when the user picks "AI slide" — the copy-a-prompt / paste-HTML
         * flow for AI-authored animated slides. Default no-op so existing
         * callers compile unchanged.
         */
        default void onGeneratedSlideSelected() { }

        /**
         * Called when the user picks "FX Adjustment Layer" — an empty container in the lanes
         * whose effects transform everything BENEATH it, as distinct from adjusting one object.
         *
         * <p>It belongs here, in Add, because it is a thing you ADD to the timeline. It was
         * previously reachable only through the Adjust tool, which is where nobody looked: the
         * owner went to Add, did not find it, and only found the feature by guessing at a
         * toolbar icon. Default no-op so existing callers compile unchanged.</p>
         */
        default void onAdjustmentLayerSelected() { }

        /**
         * B5.U — record voiceover against playback (punch-in). Add is where people go to
         * add things, and 27 carousel tools are already too many, so this lives here
         * beside "Audio". Default no-op.
         */
        default void onVoiceoverRecordSelected() { }

        /**
         * SPEC_20260829_QUICK_WINS S1: Image as clip (timeline segment — rare).
         * Promoted Image (overlay) lives in the toolbox (<=2 taps); this is the demoted
         * spine path. Reuses the SAME {@code newImageClip} payload as {@code onAssetTypeSelected(true)}
         * used to, but that path now routes through the internal picker in overlay mode,
         * so this callback is the clip-specific one. Default no-op for back-compat.
         */
        default void onImageAsClipSelected() { }
    }

    @Nullable
    private Callback callback;

    /**
     * Create a new AddAssetBottomSheet instance.
     *
     * @return a new instance
     */
    public static AddAssetBottomSheet newInstance() {
        return new AddAssetBottomSheet();
    }

    /**
     * Set the callback for asset type selection.
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

        // Title
        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_add_asset_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (20 * dp), (int) (16 * dp));
        root.addView(title);

        // Video row -- primary spine addition (video is still a spine clip; image-as-clip
        // is the rare case and is demoted to the bottom section -- see bottom of sheet).
        root.addView(createOptionRow(
                getString(R.string.faditor_add_asset_video),
                "videocam", materialIcons, dp,
                () -> { if (callback != null) callback.onAssetTypeSelected(false); }));

        // Image-as-new-layer -- overlay on a NEW floating layer track (the reliable cross-
        // layer path). Kept near Video because it is ALSO an overlay-like addition, not a
        // spine segment. Uses the SAME image payload as the toolbox Image overlay.
        root.addView(createOptionRow(
                "Image as new layer",
                "layers", materialIcons, dp,
                () -> { if (callback != null) callback.onImageAsNewLayerSelected(); }));

        // Black clip -- a spine still with no picture (title card / pause). Kept here
        // because it IS a spine segment, same machinery as image-as-clip, but it is its
        // own concept (JoyRaptor: "having it be part of image").
        root.addView(createOptionRow(
                "Black clip (title card / pause)",
                "crop_din", materialIcons, dp,
                () -> { if (callback != null) callback.onBlankClipSelected(); }));

        // Overlay video (PiP) row — feature-flagged with M-COMP-2.
        if (com.fadcam.ui.faditor.compositor.OverlayVideoPreviewView.LIVE_PIP) {
            root.addView(createOptionRow(
                    getString(R.string.faditor_add_asset_pip),
                    "picture_in_picture", materialIcons, dp,
                    () -> { if (callback != null) callback.onOverlayVideoSelected(); }));
        }

        // Audio row
        root.addView(createOptionRow(
                getString(R.string.faditor_add_asset_audio),
                "music_note", materialIcons, dp,
                () -> { if (callback != null) callback.onAudioSelected(); }));

        // B5.U — record voiceover against playback (punch-in). Add is where
        // people go to add things; NO new carousel tool (27 already too many).
        root.addView(createOptionRow(
                "Record voiceover",
                "mic", materialIcons, dp,
                () -> { if (callback != null) callback.onVoiceoverRecordSelected(); }));

        // AI slide row — copy-a-prompt / paste-HTML animated slide flow.
        // TODO(strings)
        root.addView(createOptionRow(
                "AI slide (animated)",
                "auto_awesome", materialIcons, dp,
                () -> { if (callback != null) callback.onGeneratedSlideSelected(); }));

        // FX adjustment layer — an empty container that grades everything beneath it.
        // TODO(strings)
        root.addView(createOptionRow(
                "FX Adjustment Layer",
                "auto_fix_high", materialIcons, dp,
                () -> { if (callback != null) callback.onAdjustmentLayerSelected(); }));

        // -- Demoted: Image as clip (spine segment) -- SPEC_20260829_QUICK_WINS S1 --
        // The RARE path: inserting a picture as its own segment of the spine. Promoted
        // Image (overlay) lives in the toolbox (<=2 taps, beside Add); this stays reachable
        // but is deliberately last and visually de-emphasized. Long-press on the toolbox
        // Image button will also offer this same callback (same code path, no duplicate).
        View demoteDivider = new View(requireContext());
        demoteDivider.setBackgroundColor(0xFF2A2A2A);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.setMargins((int)(12*dp), (int)(16*dp), (int)(12*dp), (int)(8*dp));
        demoteDivider.setLayoutParams(divLp);
        root.addView(demoteDivider);

        TextView demoteHeader = new TextView(requireContext());
        demoteHeader.setText("More - timeline segments");
        demoteHeader.setTextColor(0xFF666666);
        demoteHeader.setTextSize(11);
        demoteHeader.setPadding((int)(20*dp), 0, (int)(20*dp), (int)(6*dp));
        root.addView(demoteHeader);

        root.addView(createOptionRow(
                getString(R.string.faditor_add_asset_image) + " as clip (timeline segment - rare)",
                "image", materialIcons, dp,
                () -> { if (callback != null) callback.onImageAsClipSelected(); }));

        NestedScrollView scroll = new NestedScrollView(requireContext());
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    /**
     * Creates an option row matching the existing Faditor bottom sheet style.
     *
     * @param label         row label text
     * @param icon          Material Icon ligature name
     * @param materialIcons Material Icons typeface
     * @param dp            density factor
     * @param isImage       true for image option, false for video
     * @return the constructed row View
     */
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

        // Icon
        TextView iconView = new TextView(requireContext());
        iconView.setTypeface(materialIcons);
        iconView.setText(icon);
        iconView.setTextSize(20);
        iconView.setTextColor(0xFF888888);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (28 * dp), (int) (28 * dp));
        iconLp.setMarginEnd((int) (16 * dp));
        iconView.setLayoutParams(iconLp);
        iconView.setGravity(Gravity.CENTER);
        row.addView(iconView);

        // Label
        TextView labelView = new TextView(requireContext());
        labelView.setText(label);
        labelView.setTextSize(15);
        labelView.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelView.setLayoutParams(labelLp);
        row.addView(labelView);

        // Arrow
        TextView arrow = new TextView(requireContext());
        arrow.setTypeface(materialIcons);
        arrow.setText("chevron_right");
        arrow.setTextSize(18);
        arrow.setTextColor(0xFF666666);
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
