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
        // E6 (bug hunt 11-15): Android rebuilt the editor (unfolding the Fold, a dark-mode
        // switch, a font-size change) and restored this sheet without its owner, so every
        // button would do nothing. Close it; the person opens it again from the editor.
        if (savedInstanceState != null && callback == null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (isAdded()) dismissAllowingStateLoss();
            });
        }
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

        // How see-through every Studio drawer is (JoyRaptor, 2026-09-24): legibility of the
        // controls against seeing the video behind them is a balance only the person working
        // can strike. Drawers repaint live as the slider moves.
        addSeeThroughRow(content);

        // TAB-1 §K (the owner's 11-15 picks): where drawers open on a wide screen. Only rows
        // here; the drawer's own header carries the same choice as a one-touch switch.
        addPlaceRows(content, dp);

        // AV4: opens the quad-band tape "Waveform visualizer" settings sheet (crossovers,
        // per-band colors/lanes, shaping, FX, and eager/lazy analysis timing). Inline literals
        // because strings.xml is owned by another lane.
        // Shown only when a project is open behind the sheet (the editor wires it); opened from the
        // Settings hub there is no project, and a row that does nothing is worse than no row.
        if (onOpenWaveformVisualizer != null) addButtonRow(content, dp,
                getString(R.string.studio_sheet_settings_waveform_title),
                getString(R.string.studio_sheet_settings_waveform_desc),
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
        // Project-only, like the visualizer row: hidden when no project is open (the Settings hub).
        if (onConsolidateProject != null) addButtonRow(content, dp,
                getString(R.string.studio_sheet_settings_selfcontained_title),
                getString(R.string.studio_sheet_settings_selfcontained_desc),
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
        StudioDialog.builder(requireContext())
                .setTitle(R.string.faditor_settings_orphan_title)
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    prefs.setFaditorOrphanAnchorPolicy(values[which]);
                    d.dismiss();
                    dismiss();   // reopen shows the updated summary line
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Settings row: title, the live percentage, and a slider under them. */
    private void addSeeThroughRow(@NonNull LinearLayout parent) {
        final android.content.Context ctx = requireContext();
        final com.google.android.material.slider.Slider slider =
                new com.google.android.material.slider.Slider(ctx);
        final int min = com.fadcam.ui.faditor.tools.ObjectDrawer.Kit.SEE_THROUGH_MIN;
        final int max = com.fadcam.ui.faditor.tools.ObjectDrawer.Kit.SEE_THROUGH_MAX;
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(5f);
        int cur = com.fadcam.ui.faditor.tools.ObjectDrawer.Kit.seeThroughPct(ctx);
        slider.setValue(Math.round(cur / 5f) * 5f);
        SheetKit.label(slider, getString(R.string.settings_drawer_see_through));
        LinearLayout row = SheetKit.detailRow(ctx, null, 0,
                getString(R.string.settings_drawer_see_through),
                getString(R.string.settings_drawer_see_through_desc, cur), null, false);
        final TextView desc = findDescription(row, getString(R.string.settings_drawer_see_through_desc, cur));
        slider.addOnChangeListener((s, value, fromUser) -> {
            if (!fromUser) return;
            com.fadcam.ui.faditor.tools.ObjectDrawer.Kit.setSeeThroughPct(ctx, Math.round(value));
            if (desc != null) {
                desc.setText(getString(R.string.settings_drawer_see_through_desc, Math.round(value)));
            }
        });
        parent.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int side = SheetKit.dp(ctx, 16);
        lp.setMargins(side, 0, side, SheetKit.dp(ctx, 8));
        parent.addView(slider, lp);
    }

    /**
     * TAB-1: drawers on a wide screen. The first row cycles Automatic, Drop down, Beside on a tap
     * (its description says which is on); the switches pick the side layout and the hand.
     */
    private void addPlaceRows(@NonNull LinearLayout parent, float dp) {
        final android.content.Context ctx = requireContext();
        final String[] modes = {com.fadcam.ui.faditor.tools.DrawerPlace.AUTO,
                com.fadcam.ui.faditor.tools.DrawerPlace.DROP,
                com.fadcam.ui.faditor.tools.DrawerPlace.BESIDE};
        final int[] words = {R.string.tab1_settings_place_auto, R.string.tab1_settings_place_drop,
                R.string.tab1_settings_place_beside};
        String cur = com.fadcam.ui.faditor.tools.DrawerPlace.mode(ctx);
        int at = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(cur)) at = i;
        final int[] now = {at};
        final String first = getString(words[at]);
        LinearLayout row = SheetKit.detailRow(ctx, null, 0,
                getString(R.string.tab1_settings_place_title), first,
                SheetKit.chevron(ctx), true);
        final TextView desc = findDescription(row, first);
        row.setOnClickListener(v -> {
            now[0] = (now[0] + 1) % modes.length;
            com.fadcam.ui.faditor.tools.DrawerPlace.setMode(ctx, modes[now[0]]);
            if (desc != null) desc.setText(words[now[0]]);
        });
        parent.addView(row);
        addSwitchRow(parent, dp, getString(R.string.tab1_settings_column_title), getString(R.string.tab1_settings_column),
                com.fadcam.ui.faditor.tools.DrawerPlace.column(ctx),
                on -> com.fadcam.ui.faditor.tools.DrawerPlace.setColumn(ctx, on));
        addSwitchRow(parent, dp, getString(R.string.tab1_settings_left_title), getString(R.string.tab1_settings_left),
                com.fadcam.ui.faditor.tools.DrawerPlace.left(ctx),
                on -> com.fadcam.ui.faditor.tools.DrawerPlace.setLeft(ctx, on));
    }

    /** The TextView in {@code root} showing {@code text}, or null. */
    @Nullable
    private static TextView findDescription(@NonNull View root, @NonNull String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) {
            return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = findDescription(g.getChildAt(i), text);
                if (t != null) return t;
            }
        }
        return null;
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
