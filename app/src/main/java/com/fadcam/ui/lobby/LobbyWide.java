package com.fadcam.ui.lobby;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.size.WindowSize;

/**
 * The lobby on a wide window (TAB-1 §K, the owner's 11-15 picks L1 A · L3 A · L4 B · L5 A; he will
 * rethink the lobby later, this is "for now").
 *
 * <p>The phone lobby is one column. On an expanded window (840 dp and up) the same views are moved,
 * not copied, into two columns: on the left the last project at 16:9, its name with New and Carry
 * on, the New buttons at their phone size, and the floor words grouped under them; on the right the
 * Library door, recent work as one row and Joybot's line. Every view keeps its id and its own
 * listeners, so the fragment's code works unchanged. A phone, or half a tablet, never gets here.
 */
final class LobbyWide {

    private LobbyWide() {}

    /** The left column never grows past a wide phone, so nothing in it is stretched. */
    private static final int LEFT_MAX_DP = 560;
    private static final float LEFT_SHARE = 0.42f;
    private static final int GAP_DP = 28;

    static boolean wanted(@NonNull Context c) { return WindowSize.isExpanded(c); }

    /** Rearrange {@code root} (the fragment's view) into two columns. Safe to call once. */
    static void apply(@NonNull View root) {
        Context c = root.getContext();
        LinearLayout lobby = root.findViewById(R.id.lobby_root);
        View hero = root.findViewById(R.id.lobby_hero);
        View heroName = root.findViewById(R.id.lobby_hero_name);
        if (lobby == null || hero == null || heroName == null) return;
        if (lobby.getTag(R.id.tab1_margin_base) != null) return;   // already two columns
        lobby.setTag(R.id.tab1_margin_base, Boolean.TRUE);
        View heroRow = (View) heroName.getParent().getParent();
        View door = root.findViewById(R.id.lobby_library_door);
        View carry = root.findViewById(R.id.lobby_carry_label);
        View recents = root.findViewById(R.id.lobby_recents_scroll);
        View newRow = root.findViewById(R.id.lobby_new_row);
        View botLine = root.findViewById(R.id.lobby_bot_line);
        View floor = root.findViewById(R.id.lobby_floor);

        float density = c.getResources().getDisplayMetrics().density;
        int widthPx = c.getResources().getDisplayMetrics().widthPixels;
        int windowPx = Math.round(WindowSize.widthDp(c) * density);
        if (windowPx > 0) widthPx = windowPx;
        int leftW = Math.min(Math.round(LEFT_MAX_DP * density), Math.round(widthPx * LEFT_SHARE));

        int at = lobby.indexOfChild(hero);
        // The phone's spacer (weight 3, above the floor) shared the leftover height with the hero;
        // the columns take that height now, so it goes.
        View spacer = floor != null ? lobby.getChildAt(lobby.indexOfChild(floor) - 1) : null;
        if (spacer != null && spacer.getId() == View.NO_ID && !(spacer instanceof ViewGroup)) {
            spacer.setVisibility(View.GONE);
        }

        LinearLayout cols = new LinearLayout(c);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = column(c);
        LinearLayout right = column(c);

        move(hero, left, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(leftW * 9f / 16f)));
        move(heroRow, left, null);
        move(newRow, left, null);
        View fill = new View(c);
        left.addView(fill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        move(floor, left, null);

        move(door, right, null);
        move(carry, right, null);
        move(recents, right, null);
        move(botLine, right, null);

        cols.addView(left, new LinearLayout.LayoutParams(leftW, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rlp.leftMargin = Math.round(GAP_DP * density);
        cols.addView(right, rlp);
        lobby.addView(cols, Math.min(at, lobby.getChildCount()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @NonNull
    private static LinearLayout column(@NonNull Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    /** Move {@code v} into {@code to}, keeping its own size unless {@code lp} says otherwise. */
    private static void move(@Nullable View v, @NonNull LinearLayout to,
                             @Nullable LinearLayout.LayoutParams lp) {
        if (v == null || !(v.getParent() instanceof ViewGroup)) return;
        ViewGroup.LayoutParams old = v.getLayoutParams();
        ((ViewGroup) v.getParent()).removeView(v);
        if (lp == null) {
            lp = old instanceof LinearLayout.LayoutParams
                    ? new LinearLayout.LayoutParams((LinearLayout.LayoutParams) old)
                    : old instanceof ViewGroup.MarginLayoutParams
                    ? new LinearLayout.LayoutParams((ViewGroup.MarginLayoutParams) old)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.weight = 0f;
            if (lp.height == 0) lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        to.addView(v, lp);
    }
}
