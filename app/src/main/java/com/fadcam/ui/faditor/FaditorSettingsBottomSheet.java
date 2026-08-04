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
import androidx.core.widget.NestedScrollView;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * "Editor settings" bottom sheet for Faditor Mini.
 *
 * <p>Currently exposes a single switch that controls whether the editor
 * offers to transcribe a newly-added video clip (see
 * {@link SharedPreferencesManager#isFaditorAskToTranscribeEnabled()}). The
 * content is built as a simple vertical stack of "settings rows" inside a
 * {@link NestedScrollView} so this sheet stays scrollable and future rows are
 * trivial to add — just call {@link #addSwitchRow} again.</p>
 *
 * <p>Uses the same dark gradient bottom-sheet styling and programmatic-view
 * convention as {@link FaditorInfoBottomSheet}.</p>
 */
public class FaditorSettingsBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "FaditorSettingsBottomSheet";

    /** Notified immediately when the safe-zone guide toggle changes, so the editor
     *  can update the live preview overlay without waiting for a resume/reopen. */
    public interface Callback {
        void onSafeZoneOverlayToggled(boolean enabled);
    }

    @Nullable private Callback callback;

    /** AV4: tapped when the "Waveform visualizer" row is clicked — the host opens the
     *  {@link com.fadcam.ui.faditor.waveform.WaveformVisualizerSettingsSheet}. Kept as a
     *  separate setter (not a Callback method) so the existing method-reference wiring of
     *  {@link Callback} stays intact. */
    @Nullable private Runnable onOpenWaveformVisualizer;

    /** Factory method. */
    @NonNull
    public static FaditorSettingsBottomSheet newInstance() {
        return new FaditorSettingsBottomSheet();
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    /** AV4: set the action run when the "Waveform visualizer" row is tapped. */
    public void setOnOpenWaveformVisualizer(@Nullable Runnable action) {
        this.onOpenWaveformVisualizer = action;
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
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
            }
        });
        return dialog;
    }

    // ── View creation ────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(requireContext());

        // Root layout
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * dp), 0, (int) (32 * dp));

        // ── Header row (title + close button) ───────────────────
        LinearLayout headerRow = new LinearLayout(requireContext());
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (12 * dp), (int) (4 * dp));
        root.addView(headerRow);

        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_settings_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(title);

        // Close button
        TextView closeBtn = new TextView(requireContext());
        closeBtn.setTypeface(materialIcons);
        closeBtn.setText("close");
        closeBtn.setTextColor(0xFF999999);
        closeBtn.setTextSize(22);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding((int) (8 * dp), (int) (8 * dp),
                (int) (8 * dp), (int) (8 * dp));
        closeBtn.setOnClickListener(v -> dismiss());
        headerRow.addView(closeBtn);

        // ── Scrollable content (NestedScrollView — small-screen rule) ──
        NestedScrollView scrollView = new NestedScrollView(requireContext());
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding((int) (20 * dp), (int) (8 * dp), (int) (20 * dp), 0);
        scrollView.addView(content);
        root.addView(scrollView);

        // ── Settings rows ────────────────────────────────────────
        // Future rows: just call addSwitchRow(...) (or a new row helper) again.
        addSwitchRow(content, dp,
                getString(R.string.faditor_settings_ask_transcribe_title),
                getString(R.string.faditor_settings_ask_transcribe_desc),
                prefs.isFaditorAskToTranscribeEnabled(),
                (isChecked) -> prefs.setFaditorAskToTranscribeEnabled(isChecked));

        addSwitchRow(content, dp,
                getString(R.string.faditor_settings_safe_zone_title),
                getString(R.string.faditor_settings_safe_zone_desc),
                prefs.isFaditorSafeZoneOverlayEnabled(),
                (isChecked) -> {
                    prefs.setFaditorSafeZoneOverlayEnabled(isChecked);
                    if (callback != null) callback.onSafeZoneOverlayToggled(isChecked);
                });

        // AV4: opens the quad-band tape "Waveform visualizer" settings sheet (crossovers,
        // per-band colors/lanes, shaping, FX, and eager/lazy analysis timing). Inline literals
        // because strings.xml is owned by another lane.
        addButtonRow(content, dp,
                "Waveform visualizer",
                "Customize the audio waveform look and when clips are analyzed.",
                () -> {
                    if (onOpenWaveformVisualizer != null) onOpenWaveformVisualizer.run();
                    dismiss();
                });

        // §4A: what happens to layer objects anchored to a clip you delete. A BUTTON row opening a
        // three-choice dialog rather than a new tri-state widget — addButtonRow already exists,
        // and a bespoke control for one preference is bloat.
        addButtonRow(content, dp,
                getString(R.string.faditor_settings_orphan_title),
                getString(R.string.faditor_settings_orphan_desc,
                        orphanPolicyLabel(prefs.getFaditorOrphanAnchorPolicy())),
                () -> showOrphanPolicyChooser(prefs));

        // NOTE (v2): the old "Tool order" manual/recent switch was removed. The
        // carousel now uses the divider model — pinned home row left of the
        // divider (manual order), usage-sorted right of it — controlled directly
        // by drag-and-drop in the carousel's edit mode.

        return root;
    }

    /** Human label for a stored orphan-anchor policy value. */
    @NonNull
    private String orphanPolicyLabel(@NonNull String policy) {
        switch (policy) {
            case "reanchor": return getString(R.string.faditor_orphan_keep);
            case "delete":   return getString(R.string.faditor_orphan_delete);
            default:         return getString(R.string.faditor_settings_orphan_ask);
        }
    }

    /**
     * Three-choice picker for the orphan-anchor policy. "Always ask" is offered explicitly so a
     * remembered choice can be UNDONE — a "remember my preference" checkbox that cannot be
     * un-remembered is a trap, which is the whole reason this row exists.
     */
    private void showOrphanPolicyChooser(@NonNull com.fadcam.SharedPreferencesManager prefs) {
        final String[] values = {"ask", "reanchor", "delete"};
        String[] labels = new String[values.length];
        int current = 0;
        String cur = prefs.getFaditorOrphanAnchorPolicy();
        for (int i = 0; i < values.length; i++) {
            labels[i] = orphanPolicyLabel(values[i]);
            if (values[i].equals(cur)) current = i;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.faditor_settings_orphan_title)
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    prefs.setFaditorOrphanAnchorPolicy(values[which]);
                    d.dismiss();
                    dismiss();   // reopen shows the updated summary line
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Simple callback for a row's switch toggling. */
    private interface OnToggle {
        void onToggle(boolean isChecked);
    }

    // ── Helper: add a titled row with a trailing switch ───────────────

    /**
     * Adds a themed settings row: title + description on the left, a
     * {@link MaterialSwitch} on the right. Structured so more rows (of this
     * or other kinds) can be appended trivially as this sheet grows.
     */
    private void addSwitchRow(@NonNull LinearLayout parent,
                              float dp,
                              @NonNull String title,
                              @NonNull String description,
                              boolean initialChecked,
                              @NonNull OnToggle onToggle) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_group_card_bg);
        int pad = (int) (14 * dp);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = (int) (10 * dp);
        row.setLayoutParams(rowLp);

        LinearLayout textCol = new LinearLayout(requireContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleTv = new TextView(requireContext());
        titleTv.setText(title);
        titleTv.setTextColor(0xFFFFFFFF);
        titleTv.setTextSize(15);
        titleTv.setTypeface(null, Typeface.BOLD);
        textCol.addView(titleTv);

        TextView descTv = new TextView(requireContext());
        descTv.setText(description);
        descTv.setTextColor(0xFF999999);
        descTv.setTextSize(12);
        descTv.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = (int) (4 * dp);
        descTv.setLayoutParams(descLp);
        textCol.addView(descTv);

        row.addView(textCol);

        MaterialSwitch toggle = new MaterialSwitch(requireContext());
        toggle.setChecked(initialChecked);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        switchLp.setMarginStart((int) (12 * dp));
        toggle.setLayoutParams(switchLp);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> onToggle.onToggle(isChecked));
        row.addView(toggle);

        parent.addView(row);
    }

    /**
     * AV4: a titled settings row that acts as a button — title + description on the left, a
     * trailing chevron, whole row tappable. Mirrors {@link #addSwitchRow}'s themed card so the
     * sheet stays visually consistent.
     */
    private void addButtonRow(@NonNull LinearLayout parent,
                              float dp,
                              @NonNull String title,
                              @NonNull String description,
                              @NonNull Runnable onClick) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_group_card_bg);
        int pad = (int) (14 * dp);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = (int) (10 * dp);
        row.setLayoutParams(rowLp);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onClick.run());

        LinearLayout textCol = new LinearLayout(requireContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleTv = new TextView(requireContext());
        titleTv.setText(title);
        titleTv.setTextColor(0xFFFFFFFF);
        titleTv.setTextSize(15);
        titleTv.setTypeface(null, Typeface.BOLD);
        textCol.addView(titleTv);

        TextView descTv = new TextView(requireContext());
        descTv.setText(description);
        descTv.setTextColor(0xFF999999);
        descTv.setTextSize(12);
        descTv.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = (int) (4 * dp);
        descTv.setLayoutParams(descLp);
        textCol.addView(descTv);

        row.addView(textCol);

        TextView chevron = new TextView(requireContext());
        chevron.setText("›");
        chevron.setTextColor(0xFF999999);
        chevron.setTextSize(22);
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        chLp.setMarginStart((int) (12 * dp));
        chevron.setLayoutParams(chLp);
        row.addView(chevron);

        parent.addView(row);
    }
}
