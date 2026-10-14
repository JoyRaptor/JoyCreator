package com.fadcam.joyfolder.studio;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fadcam.joyfolder.android.JoyFolderHome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private static final int CYAN = 0xFF22D3EE;
    private static final int GROUND = 0xFF050507;
    private static final int PANEL_2 = 0xFF1C1C22;
    private static final int TEXT = 0xFFE9E9EE;
    private static final int MUTED = 0xFFA1A1AC;

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

    private static void build(Activity a, List<String> refs, List<String> names, List<Long> sizes, Done done) {
        final Map<String, Choice> choices = new LinkedHashMap<>();
        final List<TextView[]> rows = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < refs.size(); i++) {
            choices.put(refs.get(i), looksLikeOwnRecording(names.get(i)) ? Choice.MOVE : Choice.COPY);
            total += Math.max(0, sizes.get(i));
        }

        int pad = dp(a, 20);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(a, 4), pad, 0);

        TextView why = text(a, (refs.size() == 1 ? "It's" : "They're") + " linked where "
                + (refs.size() == 1 ? "it is" : "they are") + " (" + Formatter.formatShortFileSize(a, total)
                + "), not in your Joy Creator folder. If one is moved or deleted there, the project loses it, and a new "
                + "phone won't have it.", 14, MUTED, false);
        col.addView(why);

        TextView key = text(a, "Copy keeps the original where it is. Move takes it out of there (good for something you "
                + "recorded for this project). Leave keeps the link.", 12, MUTED, false);
        key.setPadding(0, dp(a, 8), 0, dp(a, 4));
        col.addView(key);

        // Copy all / Move all / Leave all
        if (refs.size() > 1) {
            LinearLayout all = new LinearLayout(a);
            all.setOrientation(LinearLayout.HORIZONTAL);
            all.setPadding(0, dp(a, 8), 0, dp(a, 4));
            for (Choice c : Choice.values()) {
                TextView b = pill(a, label(c) + " all", false);
                b.setOnClickListener(v -> {
                    for (String r : refs) choices.put(r, c);
                    for (int i = 0; i < rows.size(); i++) paint(rows.get(i), choices.get(refs.get(i)));
                });
                all.addView(b);
                gap(a, all);
            }
            col.addView(all);
        }

        for (int i = 0; i < refs.size(); i++) {
            final String ref = refs.get(i);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(a, 10), 0, 0);
            long size = sizes.get(i);
            TextView name = text(a, names.get(i) + (size >= 0 ? "  ·  " + Formatter.formatShortFileSize(a, size) : ""),
                    14, TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(name);
            LinearLayout seg = new LinearLayout(a);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setPadding(0, dp(a, 6), 0, 0);
            TextView[] three = new TextView[3];
            for (Choice c : Choice.values()) {
                TextView b = pill(a, label(c), false);
                b.setOnClickListener(v -> {
                    choices.put(ref, c);
                    paint(three, c);
                });
                three[c.ordinal()] = b;
                seg.addView(b);
                gap(a, seg);
            }
            paint(three, choices.get(ref));
            rows.add(three);
            row.addView(seg);
            col.addView(row);
        }

        ScrollView scroll = new ScrollView(a);
        scroll.addView(col);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                .setTitle(refs.size() == 1 ? "1 file is outside your folder" : refs.size() + " files are outside your folder")
                .setView(scroll)
                .setPositiveButton("Do it", (d, w) -> done.onChosen(choices))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Recordings this app made: "Camera 2026-10-13 23.51.mp4", "Screen recording …", or the older "FadCam_…". */
    private static boolean looksLikeOwnRecording(String name) {
        return name.startsWith("FadCam_") || com.fadcam.utils.RecordingStoragePaths.JoyKind.ofName(name) != null;
    }

    private static String label(Choice c) {
        return c == Choice.COPY ? "Copy" : c == Choice.MOVE ? "Move" : "Leave";
    }

    /** ON is solid cyan with dark text; the others are zinc pills. */
    private static void paint(TextView[] three, Choice on) {
        for (Choice c : Choice.values()) {
            TextView b = three[c.ordinal()];
            boolean sel = c == on;
            GradientDrawable bg = (GradientDrawable) b.getBackground();
            bg.setColor(sel ? CYAN : PANEL_2);
            b.setTextColor(sel ? GROUND : TEXT);
            b.setSelected(sel);
        }
    }

    private static TextView pill(Activity a, String label, boolean primary) {
        TextView b = text(a, label, 13, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? CYAN : PANEL_2);
        bg.setCornerRadius(dp(a, 999));
        b.setBackground(bg);
        b.setContentDescription(label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setTooltipText(label);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private static void gap(Activity a, LinearLayout row) {
        View g = new View(a);
        g.setLayoutParams(new LinearLayout.LayoutParams(dp(a, 8), 1));
        row.addView(g);
    }

    private static TextView text(Activity a, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
