package com.fadcam.joyfolder.studio;

import android.app.Activity;
import android.widget.ScrollView;

import com.fadcam.joyfolder.android.JoyFolderHome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "These files are outside your folder" — and for each one, the person's call: Copy, Move or Leave.
 *
 * <p>The owner, 2026-10-14: a recording made for this project should MOVE (no stray copy left on the phone); a
 * family video used for a birthday edit should be COPIED (the original stays in the gallery); or take no action.
 * "Copy all / Move all / Leave all" sets every row; each row can still be changed. The guess is visible and one
 * tap to change: recordings this app made start on Move, everything else on Copy.</p>
 */
public final class BringInDialog {

    public enum Choice { COPY, MOVE, LEAVE }

    public interface Done {
        void onChosen(Map<String, Choice> choices);
    }

    private BringInDialog() {}

    /** Read names and sizes off the main thread, then ask. */
    public static void show(Activity a, List<String> refs, Done done) {
        if (refs.isEmpty()) return;
        JoyFolderHome.io().execute(() -> {
            List<String> names = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            for (String r : refs) {
                names.add(StudioMirror.nameOf(a, r));
                sizes.add(StudioMirror.sizeOf(a, r));
            }
            a.runOnUiThread(() -> {
                if (!a.isFinishing()) build(a, refs, names, sizes, done);
            });
        });
    }

    /** The panel (shared with Joybot's chat card, BringInPanel) in a dialog with Do it / Cancel. */
    private static void build(Activity a, List<String> refs, List<String> names, List<Long> sizes, Done done) {
        BringInPanel panel = BringInPanel.build(a, refs, names, sizes, true);
        int pad = dp(a, 20);
        panel.view.setPadding(pad, dp(a, 4), pad, 0);
        ScrollView scroll = new ScrollView(a);
        scroll.addView(panel.view);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                .setTitle(refs.size() == 1 ? "1 file is outside your folder" : refs.size() + " files are outside your folder")
                .setView(scroll)
                .setPositiveButton("Do it", (d, w) -> done.onChosen(panel.choices))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
