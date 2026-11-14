package com.fadcam.ui.faditor.text;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import cc.joycreator.textlab.LookKnobs;
import cc.joycreator.textlab.TitleLook;

/**
 * TX-01 (11-13): a Studio title's look sliders and colours, in TextLab's own panel ({@link LookKnobs}), over the
 * editor. The title redraws as a slider moves; each grab of a slider or colour is ONE undo step (owner rule), and
 * "As it came" puts the look's own values back as one step. The look itself is changed only in the TextLab room.
 */
public final class LookSlidersSheet {

    /** How the editor applies a look to the title: live puts, and one undo step per change. */
    public interface Host {
        /** Put {@code look} on the title and redraw (no undo step). */
        void put(@NonNull TitleLook look);

        /** One undo step named {@code label}, from {@code before} to {@code after}. */
        void record(@NonNull String label, @NonNull TitleLook before, @NonNull TitleLook after);
    }

    private LookSlidersSheet() {}

    public static void show(@NonNull Activity a, @NonNull TextOverlayItem item, @NonNull Host host) {
        TitleLook worn = item.getTextLook();
        if (worn == null) return;
        final TitleLook t = worn.copy();
        // The change being made: its label and the look before it, committed when the next one starts or the sheet
        // closes, so a whole slider drag is one step.
        final String[] pendingLabel = {null};
        final TitleLook[] pendingBefore = {null};
        final Runnable commit = () -> {
            if (pendingLabel[0] == null) return;
            TitleLook after = t.copy();
            if (!LookKnobs.worn(after).toString().equals(LookKnobs.worn(pendingBefore[0]).toString())) {
                host.record(pendingLabel[0], pendingBefore[0], after);
            }
            pendingLabel[0] = null;
            pendingBefore[0] = null;
        };
        View panel = LookKnobs.panel(a, t, new LookKnobs.Listener() {
            @Override
            public void begin(@NonNull String what) {
                commit.run();
                pendingLabel[0] = a.getString(R.string.tx01_slider_step, what);
                pendingBefore[0] = t.copy();
            }

            @Override
            public void changed() {
                host.put(t.copy());
            }
        });
        if (panel == null) {
            Toast.makeText(a, R.string.tx01_no_sliders, Toast.LENGTH_SHORT).show();
            return;
        }

        BottomSheetDialog d = new BottomSheetDialog(a);
        int pad = dp(a, 16);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, pad);
        root.setBackgroundColor(Studio.alpha(Studio.GROUND, 0xEE));

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(a);
        title.setText(a.getString(R.string.tx01_title, t.name));
        title.setTextColor(Studio.INK);
        title.setTextSize(15);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView reset = new TextView(a);
        reset.setText(R.string.tx01_as_it_came);
        reset.setTextSize(13);
        reset.setTextColor(Studio.INK);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        reset.setMinHeight(dp(a, 36));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(a, 18));
        bg.setColor(Studio.RAISED);
        reset.setBackground(bg);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(reset, a.getString(R.string.tx01_as_it_came_hover));
        reset.setContentDescription(a.getString(R.string.tx01_as_it_came_hover));
        reset.setOnClickListener(v -> {
            if (!LookKnobs.changed(t)) return;
            commit.run();
            TitleLook before = t.copy();
            LookKnobs.reset(t);
            TitleLook after = t.copy();
            host.put(after);
            host.record(a.getString(R.string.tx01_as_it_came), before, after);
            LookKnobs.refresh(panel);
        });
        head.addView(reset);
        root.addView(head);
        root.addView(panel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 300)));

        d.setContentView(root);
        d.setOnDismissListener(x -> commit.run());
        // Open all the way: on a landscape tablet the default half state showed only the tab row
        // (Tab S8, 11-13).
        d.getBehavior().setSkipCollapsed(true);
        d.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        if (d.getWindow() != null) d.getWindow().setDimAmount(0f); // the title stays visible as it changes
        d.show();
    }

    private static int dp(@NonNull Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
