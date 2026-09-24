package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import com.fadcam.R;
import com.fadcam.ui.faditor.tools.SnapSettings;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * The magnet's panel: hold the magnet to choose WHAT snaps and HOW STRONGLY (JoyRaptor,
 * 2026-09-24). The magnet itself is the master switch at the top; each row below keeps its own
 * setting when the magnet is off, so turning the magnet back on restores exactly what was set.
 * Everything writes {@link SnapSettings} straight away; there is nothing to confirm.
 */
public class SnapSettingsSheet extends BottomSheetDialogFragment {

    /**
     * The rows shown, in order. A kind is listed only once a surface really reads it: a
     * switch that changes nothing is worse than no switch.
     */
    static final SnapSettings.Kind[] SHOWN = {
            SnapSettings.Kind.TIMELINE_EDGES,
            SnapSettings.Kind.BEATS,
            SnapSettings.Kind.CANVAS,
            SnapSettings.Kind.OBJECTS,
            SnapSettings.Kind.ROTATION,
    };

    @NonNull
    public static SnapSettingsSheet newInstance() { return new SnapSettingsSheet(); }

    @Override
    public int getTheme() { return R.style.CustomBottomSheetDialogTheme; }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        SheetKit.install(dialog, null);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        final Context ctx = requireContext();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(ctx, 20));

        SheetKit.Header header = SheetKit.header(ctx, getString(R.string.snap_title), null);
        header.addTrailing(SheetKit.closeButton(ctx, this::dismiss));
        root.addView(header.view);

        NestedScrollView scroll = new NestedScrollView(ctx);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll);

        // The magnet, as the first row: the same switch the transport button flips.
        final java.util.List<View> kindRows = new java.util.ArrayList<>();
        MaterialSwitch master = new MaterialSwitch(ctx);
        master.setChecked(SnapSettings.master(ctx));
        SheetKit.label(master, getString(R.string.snap_master));
        master.setOnCheckedChangeListener((b, on) -> {
            SnapSettings.setMaster(ctx, on);
            for (View r : kindRows) r.setAlpha(on ? 1f : 0.45f);
        });
        content.addView(SheetKit.detailRow(ctx, null, 0, getString(R.string.snap_master),
                getString(R.string.snap_master_desc), master, false));

        for (SnapSettings.Kind k : SHOWN) {
            View row = kindRow(ctx, k);
            row.setAlpha(SnapSettings.master(ctx) ? 1f : 0.45f);
            kindRows.add(row);
            content.addView(row);
        }
        return SheetKit.fitNavBar(root);
    }

    /** Title + description + switch, then Gentle / Normal / Strong (and the angle for rotation). */
    @NonNull
    private View kindRow(@NonNull Context ctx, @NonNull SnapSettings.Kind k) {
        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        MaterialSwitch sw = new MaterialSwitch(ctx);
        sw.setChecked(SnapSettings.kindOn(ctx, k));
        SheetKit.label(sw, getString(titleOf(k)));
        block.addView(SheetKit.detailRow(ctx, null, 0, getString(titleOf(k)),
                getString(descOf(k)), sw, false));

        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        int side = SheetKit.dp(ctx, 20);
        chips.setPadding(side, 0, side, SheetKit.dp(ctx, 10));
        final int[] labels = {R.string.snap_gentle, R.string.snap_normal, R.string.snap_strong};
        final TextView[] strength = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int s = i;
            TextView c = SheetKit.chip(ctx, getString(labels[i]));
            SheetKit.label(c, getString(R.string.snap_strength_desc, getString(labels[i])));
            c.setOnClickListener(v -> {
                SnapSettings.setStrength(ctx, k, s);
                for (int j = 0; j < strength.length; j++) SheetKit.setChipSelected(strength[j], j == s);
            });
            SheetKit.setChipSelected(c, SnapSettings.strength(ctx, k) == s);
            strength[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(SheetKit.dp(ctx, 6));
            chips.addView(c, lp);
        }
        // Strength only where the surface reads it (SnapSettings.reach); elsewhere the chips
        // would be controls that change nothing.
        if (SnapSettings.hasStrength(k)) block.addView(chips);

        if (k == SnapSettings.Kind.ROTATION) {
            LinearLayout steps = new LinearLayout(ctx);
            steps.setOrientation(LinearLayout.HORIZONTAL);
            steps.setPadding(side, 0, side, SheetKit.dp(ctx, 10));
            final TextView[] stepChips = new TextView[SnapSettings.ROTATION_STEPS.length];
            for (int i = 0; i < stepChips.length; i++) {
                final int deg = SnapSettings.ROTATION_STEPS[i];
                final int idx = i;
                TextView c = SheetKit.chip(ctx, deg + "°");
                SheetKit.label(c, getString(R.string.snap_rotation_step_desc, deg));
                c.setOnClickListener(v -> {
                    SnapSettings.setRotationStepDeg(ctx, deg);
                    for (int j = 0; j < stepChips.length; j++) {
                        SheetKit.setChipSelected(stepChips[j], j == idx);
                    }
                });
                SheetKit.setChipSelected(c, SnapSettings.rotationStepDeg(ctx) == deg);
                stepChips[i] = c;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(SheetKit.dp(ctx, 6));
                steps.addView(c, lp);
            }
            block.addView(steps);
        }

        final View chipRows = chips;
        chipRows.setAlpha(sw.isChecked() ? 1f : 0.45f);
        sw.setOnCheckedChangeListener((b, on) -> {
            SnapSettings.setKindOn(ctx, k, on);
            chipRows.setAlpha(on ? 1f : 0.45f);
        });
        return block;
    }

    private static int titleOf(@NonNull SnapSettings.Kind k) {
        switch (k) {
            case TIMELINE_EDGES: return R.string.snap_timeline_edges;
            case BEATS:          return R.string.snap_beats;
            case CANVAS:         return R.string.snap_canvas;
            case OBJECTS:        return R.string.snap_objects;
            default:             return R.string.snap_rotation;
        }
    }

    private static int descOf(@NonNull SnapSettings.Kind k) {
        switch (k) {
            case TIMELINE_EDGES: return R.string.snap_timeline_edges_desc;
            case BEATS:          return R.string.snap_beats_desc;
            case CANVAS:         return R.string.snap_canvas_desc;
            case OBJECTS:        return R.string.snap_objects_desc;
            default:             return R.string.snap_rotation_desc;
        }
    }
}
