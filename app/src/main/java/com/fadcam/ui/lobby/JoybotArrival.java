package com.fadcam.ui.lobby;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.size.WindowSize;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * How the Joybot chat opens, and how Joybot arrives in it: the owner's picks of 15 Nov 2026 as defaults,
 * each one a setting the person can change (design record {@code tasks/design/UI-Joybot-chat-arrival-options.html}).
 *
 * <p>Defaults depend on the window: on a wide window (a tablet, or a phone on its side, 840 dp and up) the
 * chat is a floating card that drops like a curtain with its title; on a phone it is a full sheet that
 * opens out of the Joybot button, its title growing out of the button too.</p>
 */
public final class JoybotArrival {

    private JoybotArrival() {}

    private static final String PREFS = "joybot_arrival";

    /** How the drawer appears. */
    public enum Reveal { BUTTON, CURTAIN, WAKE }
    /** How the title arrives. */
    public enum Title { BUTTON, DRAWER, LETTERS }
    /** What a later open of the day looks like (the first open of the day is always the whole show). */
    public enum Later { SHOW, SHORT, HOP }

    @NonNull
    private static SharedPreferences p(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean wide(@NonNull Context c) { return WindowSize.isExpanded(c); }

    @NonNull
    public static Reveal reveal(@NonNull Context c) {
        String k = wide(c) ? "reveal_wide" : "reveal";
        return parse(Reveal.class, p(c).getString(k, null), wide(c) ? Reveal.CURTAIN : Reveal.BUTTON);
    }

    public static void setReveal(@NonNull Context c, @NonNull Reveal r) {
        p(c).edit().putString(wide(c) ? "reveal_wide" : "reveal", r.name()).apply();
    }

    @NonNull
    public static Title title(@NonNull Context c) {
        String k = wide(c) ? "title_wide" : "title";
        return parse(Title.class, p(c).getString(k, null), wide(c) ? Title.DRAWER : Title.BUTTON);
    }

    public static void setTitle(@NonNull Context c, @NonNull Title t) {
        p(c).edit().putString(wide(c) ? "title_wide" : "title", t.name()).apply();
    }

    @NonNull
    public static Later later(@NonNull Context c) {
        return parse(Later.class, p(c).getString("later", null), Later.SHOW);
    }

    public static void setLater(@NonNull Context c, @NonNull Later l) { p(c).edit().putString("later", l.name()).apply(); }

    /** On a phone: false = full sheet (the default), true = a card he can size and place. Wide windows are always a card. */
    public static boolean phoneCard(@NonNull Context c) { return p(c).getBoolean("phone_card", false); }

    public static void setPhoneCard(@NonNull Context c, boolean on) { p(c).edit().putBoolean("phone_card", on).apply(); }

    /** True when the chat is a card rather than a full sheet. */
    public static boolean isCard(@NonNull Context c) { return wide(c) || phoneCard(c); }

    /** Frost (owner's rule: the drawer stays see-through; Frost only adds blur). On by default. See {@code FrostView}. */
    public static boolean frost(@NonNull Context c) { return p(c).getBoolean("frost", true); }

    public static void setFrost(@NonNull Context c, boolean on) { p(c).edit().putBoolean("frost", on).apply(); }

    /**
     * Where the card sits, as fractions of the window (left, top, right, bottom), or null for the default.
     * Kept separately for wide windows and phones.
     */
    @Nullable
    public static RectF cardBounds(@NonNull Context c) {
        String v = p(c).getString(wide(c) ? "card_wide" : "card_phone", null);
        if (v == null) return null;
        try {
            String[] s = v.split(",");
            RectF r = new RectF(Float.parseFloat(s[0]), Float.parseFloat(s[1]), Float.parseFloat(s[2]), Float.parseFloat(s[3]));
            return r.width() > .1f && r.height() > .1f ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void setCardBounds(@NonNull Context c, @Nullable RectF f) {
        String k = wide(c) ? "card_wide" : "card_phone";
        if (f == null) { p(c).edit().remove(k).apply(); return; }
        p(c).edit().putString(k, f.left + "," + f.top + "," + f.right + "," + f.bottom).apply();
    }

    /**
     * Called once per open: true when this open should be the whole show. The first open of the day always is;
     * later ones follow the "later opens" setting.
     */
    public static boolean fullShowNow(@NonNull Context c) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        boolean first = !today.equals(p(c).getString("last_show_day", ""));
        if (first) p(c).edit().putString("last_show_day", today).apply();
        return first || later(c) == Later.SHOW;
    }

    /**
     * A flight path for this open that is not the one he flew last time (owner, 15 Nov: "On later
     * opens, I don't think it should be a repeat of the same flight. It should be different each time").
     */
    public static int nextPath(@NonNull Context c, int count, @NonNull java.util.Random dice) {
        if (count <= 1) return 0;
        int last = p(c).getInt("last_path", -1), i;
        do { i = dice.nextInt(count); } while (i == last);
        p(c).edit().putInt("last_path", i).apply();
        return i;
    }

    /** Rocket, plop or skim: any of the three (owner, 15 Nov), never the same one twice running. */
    public static int nextApproach(@NonNull Context c, int count, @NonNull java.util.Random dice) {
        int last = p(c).getInt("last_approach", -1), i;
        do { i = dice.nextInt(count); } while (i == last && count > 1);
        p(c).edit().putInt("last_approach", i).apply();
        return i;
    }

    @NonNull
    private static <E extends Enum<E>> E parse(@NonNull Class<E> type, @Nullable String v, @NonNull E fallback) {
        if (v == null) return fallback;
        try { return Enum.valueOf(type, v); } catch (Exception e) { return fallback; }
    }
}
