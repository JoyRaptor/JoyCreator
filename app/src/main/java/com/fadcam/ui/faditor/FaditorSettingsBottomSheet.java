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
    @Nullable private Runnable onConsolidateProject;

    /** Wire the "Make project self-contained" row (audit 3.4's packaging half). */
    public void setOnConsolidateProject(@Nullable Runnable action) { this.onConsolidateProject = action; }

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
        SheetKit.install(dialog, null);
        return dialog;
    }

    // ── View creation ────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        float dp = getResources().getDisplayMetrics().density;
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(requireContext());

        // Root layout
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(requireContext(), 20));

        // ── Header (title + close button) ───────────────────────
        SheetKit.Header header = SheetKit.header(requireContext(),
                getString(R.string.faditor_settings_title), null);
        header.addTrailing(SheetKit.closeButton(requireContext(), this::dismiss));
        root.addView(header.view);

        // ── Scrollable content (NestedScrollView — small-screen rule) ──
        NestedScrollView scrollView = new NestedScrollView(requireContext());
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
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

        // 3.4: copy every referenced file INTO the project so it stops depending on media it does
        // not own. A BUTTON row, not a switch — it is an action with a cost (disk, time), not a
        // preference, and doing it automatically on open would copy gigabytes nobody asked for.
        addButtonRow(content, dp,
                "Make project self-contained",
                "Copy every video, image and audio file this project uses into the project itself, "
                        + "so it keeps working if the originals are moved or deleted. "
                        + "Originals are never changed or removed.",
                () -> {
                    if (onConsolidateProject != null) onConsolidateProject.run();
                    dismiss();
                });

        // NOTE (v2): the old "Tool order" manual/recent switch was removed. The
        // carousel now uses the divider model — pinned home row left of the
        // divider (manual order), usage-sorted right of it — controlled directly
        // by drag-and-drop in the carousel's edit mode.

        return SheetKit.fitNavBar(root);
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
     * Adds a settings row: title + description on the left, a {@link MaterialSwitch} on the
     * right. Only the switch toggles; the row is a card, not a button.
     */
    private void addSwitchRow(@NonNull LinearLayout parent,
                              float dp,
                              @NonNull String title,
                              @NonNull String description,
                              boolean initialChecked,
                              @NonNull OnToggle onToggle) {
        MaterialSwitch toggle = new MaterialSwitch(requireContext());
        toggle.setChecked(initialChecked);
        SheetKit.label(toggle, title);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> onToggle.onToggle(isChecked));
        parent.addView(SheetKit.detailRow(requireContext(), null, 0,
                title, description, toggle, false));
    }

    /**
     * AV4: a titled settings row that acts as a button — title + description on the left, a
     * trailing chevron, whole row tappable. Same card as {@link #addSwitchRow}.
     */
    private void addButtonRow(@NonNull LinearLayout parent,
                              float dp,
                              @NonNull String title,
                              @NonNull String description,
                              @NonNull Runnable onClick) {
        LinearLayout row = SheetKit.detailRow(requireContext(), null, 0,
                title, description, SheetKit.chevron(requireContext()), true);
        row.setOnClickListener(v -> onClick.run());
        parent.addView(row);
    }
}
