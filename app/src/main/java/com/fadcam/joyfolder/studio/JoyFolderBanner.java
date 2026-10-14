package com.fadcam.joyfolder.studio;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fadcam.joyfolder.android.JoyFolderHome;

/**
 * A slim card that appears only while the user's work is not protected: no Joy Creator folder
 * chosen yet, or the folder can't be reached. Tapping it opens {@link JoyFolderSetupActivity}.
 * Hidden once the folder is set and reachable.
 */
public final class JoyFolderBanner {

    private static final int PANEL = 0xFF111114;
    private static final int TEXT = 0xFFE9E9EE;
    private static final int MUTED = 0xFFA1A1AC;
    private static final int CAREFUL = 0xFFFBBF24;

    private JoyFolderBanner() {}

    /** Build the card and insert it into {@code parent} at {@code index}. Call {@link #refresh} on resume. */
    public static View attach(ViewGroup parent, int index, int sideMarginDp) {
        Context c = parent.getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 14);
        card.setPadding(dp(c, 16), p, dp(c, 16), p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(c, 14));
        card.setBackground(bg);
        TextView title = new TextView(c);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView body = new TextView(c);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setTextColor(MUTED);
        body.setPadding(0, dp(c, 4), 0, 0);
        card.addView(title);
        card.addView(body);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> v.getContext().startActivity(JoyFolderSetupActivity.intent(v.getContext())));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) card.setTooltipText("Choose where your work is kept");
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 8);
        lp.bottomMargin = dp(c, 8);
        lp.leftMargin = dp(c, sideMarginDp);
        lp.rightMargin = dp(c, sideMarginDp);
        card.setTag(new TextView[]{title, body});
        parent.addView(card, Math.min(index, parent.getChildCount()), lp);
        refresh(card);
        return card;
    }

    /**
     * The always-visible Settings entry: "Joy Creator folder" with where it is (or "Not chosen").
     * Refresh with {@link #refreshSettingsRow} on resume.
     */
    public static View attachSettingsRow(ViewGroup parent, int index) {
        View card = attach(parent, index, 0);
        refreshSettingsRow(card);
        return card;
    }

    public static void refreshSettingsRow(View card) {
        if (card == null) return;
        Context c = card.getContext();
        TextView[] tv = (TextView[]) card.getTag();
        tv[0].setTextColor(TEXT);
        tv[0].setText("Joy Creator folder");
        if (!JoyFolderHome.isChosen(c)) {
            tv[1].setText("Not chosen. Your projects only live inside the app.");
        } else if (!JoyFolderHome.isReachable(c)) {
            tv[0].setTextColor(CAREFUL);
            tv[1].setText(JoyFolderHome.describe(JoyFolderHome.treeUri(c)) + " · unavailable, tap to reconnect");
        } else {
            tv[1].setText(JoyFolderHome.describe(JoyFolderHome.treeUri(c)));
        }
        card.setVisibility(View.VISIBLE);
    }

    /** A plain card row ("Recently deleted") in the same style, for Settings. */
    public static View attachLinkRow(ViewGroup parent, int index, String title, String subtitle,
                                     View.OnClickListener onClick) {
        View card = attach(parent, index, 0);
        TextView[] tv = (TextView[]) card.getTag();
        tv[0].setTextColor(TEXT);
        tv[0].setText(title);
        tv[1].setText(subtitle);
        card.setOnClickListener(onClick);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) card.setTooltipText(title);
        card.setVisibility(View.VISIBLE);
        return card;
    }

    private static final String NUDGE_PREFS = "joy_folder_nudge";
    private static final long NUDGE_EVERY_MS = 3L * 24 * 60 * 60 * 1000;

    /**
     * In the editor, for someone who has never chosen a Joy Creator folder: one quiet line, at most
     * every three days, with a way straight to the setup screen. Nothing once a folder is chosen.
     */
    public static void maybeNudge(android.app.Activity activity) {
        if (JoyFolderHome.isChosen(activity)) return;
        android.content.SharedPreferences p = activity.getSharedPreferences(NUDGE_PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - p.getLong("last", 0) < NUDGE_EVERY_MS) return;
        p.edit().putLong("last", now).apply();
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        com.google.android.material.snackbar.Snackbar.make(root,
                        "Keep this project safe even if the app is removed: choose a Joy Creator folder.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setDuration(8000)
                .setActionTextColor(0xFF22D3EE)
                .setAction("Choose", v -> activity.startActivity(JoyFolderSetupActivity.intent(activity)))
                .show();
    }

    /** Show or hide the card for the folder's current state. */
    public static void refresh(View card) {
        if (card == null) return;
        Context c = card.getContext();
        TextView[] tv = (TextView[]) card.getTag();
        if (!JoyFolderHome.isChosen(c)) {
            tv[0].setTextColor(TEXT);
            tv[0].setText("Keep your work safe");
            tv[1].setText("Choose a Joy Creator folder so your projects survive even if the app is removed.");
            card.setVisibility(View.VISIBLE);
        } else if (!JoyFolderHome.isReachable(c)) {
            tv[0].setTextColor(CAREFUL);
            tv[0].setText("Joy Creator folder unavailable");
            tv[1].setText("Nothing has been lost. Tap to reconnect it.");
            card.setVisibility(View.VISIBLE);
        } else {
            card.setVisibility(View.GONE);
        }
    }

    private static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }
}
