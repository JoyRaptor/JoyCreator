package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;

/**
 * The link button's brain (SPEC_20260924_LINKING §12, JoyRaptor's design).
 *
 * <p>TAP arms it with whatever is selected: the button lights, every object that can take the
 * link is marked, and the NEXT single tap on one of them picks the parent and opens the details
 * popup (which properties follow, remembered from the last link). Tapping the button again
 * disarms it and nothing happens. Navigation (scroll, pinch, scrub, minimap) never picks.
 *
 * <p>This class holds only the state and the popup; the host does the hit-testing, the model
 * write and the undo step.
 */
public final class LinkTool {

    /** What a link will carry. Remembered per phone: the owner's "remembers the last settings". */
    public static final class Props {
        public boolean position = true, scale = true, rotation = true, opacity = false;
    }

    public interface Host {
        /** The link button's lit state changed: repaint it and the "can link here" marks. */
        void onArmedChanged(boolean armed);
    }

    private static final String PREFS = "studio_link";

    @Nullable private String armedSourceId;
    @NonNull private final Host host;

    public LinkTool(@NonNull Host host) { this.host = host; }

    public boolean isArmed() { return armedSourceId != null; }

    @Nullable public String armedSource() { return armedSourceId; }

    /** Tap on the link button: arm with {@code selectedId}, or disarm if already armed. */
    public void toggle(@Nullable String selectedId) {
        armedSourceId = armedSourceId == null ? selectedId : null;
        host.onArmedChanged(isArmed());
    }

    public void disarm() {
        if (armedSourceId == null) return;
        armedSourceId = null;
        host.onArmedChanged(false);
    }

    @NonNull
    public static Props lastProps(@NonNull Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Props p = new Props();
        p.position = sp.getBoolean("pos", true);
        p.scale = sp.getBoolean("scale", true);
        p.rotation = sp.getBoolean("rot", true);
        p.opacity = sp.getBoolean("op", false);
        return p;
    }

    private static void remember(@NonNull Context ctx, @NonNull Props p) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("pos", p.position).putBoolean("scale", p.scale)
                .putBoolean("rot", p.rotation).putBoolean("op", p.opacity).apply();
    }

    /**
     * The details popup: "[child] follows [parent]" and one switch per property. OK hands the
     * choice to {@code onOk} (and remembers it); Cancel does nothing.
     */
    public static void showDetails(@NonNull Context ctx, @NonNull CharSequence childName,
                                   @NonNull CharSequence parentName,
                                   @NonNull java.util.function.Consumer<Props> onOk) {
        final Props p = lastProps(ctx);
        final com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(ctx,
                        R.style.CustomBottomSheetDialogTheme);
        SheetKit.install(dialog, null);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(ctx, 16));
        SheetKit.Header header = SheetKit.header(ctx,
                ctx.getString(R.string.link_details_title, childName, parentName), null);
        header.addTrailing(SheetKit.closeButton(ctx, dialog::dismiss));
        root.addView(header.view);
        root.addView(SheetKit.subtitle(ctx, ctx.getString(R.string.link_details_hint)));

        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        int side = SheetKit.dp(ctx, 16);
        chips.setPadding(side, SheetKit.dp(ctx, 10), side, SheetKit.dp(ctx, 12));
        addToggle(ctx, chips, R.string.link_prop_position, () -> p.position, v -> p.position = v);
        addToggle(ctx, chips, R.string.link_prop_scale, () -> p.scale, v -> p.scale = v);
        addToggle(ctx, chips, R.string.link_prop_rotation, () -> p.rotation, v -> p.rotation = v);
        addToggle(ctx, chips, R.string.link_prop_opacity, () -> p.opacity, v -> p.opacity = v);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(chips);
        root.addView(scroll);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(android.view.Gravity.END);
        buttons.setPadding(side, 0, side, 0);
        buttons.addView(SheetKit.pillButton(ctx, ctx.getString(android.R.string.cancel), false,
                v -> dialog.dismiss()));
        View gap = new View(ctx);
        buttons.addView(gap, new LinearLayout.LayoutParams(SheetKit.dp(ctx, 8), 1));
        buttons.addView(SheetKit.pillButton(ctx, ctx.getString(R.string.link_details_ok), true, v -> {
            remember(ctx, p);
            dialog.dismiss();
            onOk.accept(p);
        }));
        root.addView(buttons);
        dialog.setContentView(SheetKit.fitNavBar(root));
        // Fully open, always: in landscape the default peek hid the choices and the Link button
        // below the fold (Note 9, 2026-09-25).
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        dialog.show();
    }

    private interface Get { boolean get(); }
    private interface Set { void set(boolean v); }

    private static void addToggle(@NonNull Context ctx, @NonNull LinearLayout row, int label,
                                  @NonNull Get get, @NonNull Set set) {
        TextView chip = SheetKit.chip(ctx, ctx.getString(label));
        SheetKit.setChipSelected(chip, get.get());
        chip.setOnClickListener(v -> {
            set.set(!get.get());
            SheetKit.setChipSelected(chip, get.get());
        });
        row.addView(chip);
    }
}
