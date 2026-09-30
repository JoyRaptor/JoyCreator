package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The app-wide colour history (D.02c): ONE list that the Studio's colour picker, Joy Brush's recent-colours bar and any
 * future bar all read, so improving it in one place improves it everywhere.
 *
 * <p>Storage is where the picker always kept it — SharedPreferences {@code faditor_color_picker} / {@code recents}, a
 * comma-separated list of {@code Integer.toHexString} ARGB values, most recent first — so the owner's existing recents
 * appear in Joy Brush on day one. The cap rises from 8 to {@link #MAX}; the picker's own row still draws its first 8.</p>
 *
 * <p>The list logic is in pure static helpers ({@link #pushInto}, {@link #parse}, {@link #format}) so it is tested
 * without Android. Thread: main.</p>
 */
public final class ColorRecents {

    /** The most colours the history keeps. */
    public static final int MAX = 12;

    private static final String PREFS = "faditor_color_picker";
    private static final String KEY_RECENTS = "recents";

    private ColorRecents() { }

    /** Told after the stored history changed, from anywhere in the app. The caller removes its own listener. */
    public interface Listener { void onRecentsChanged(); }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void addListener(@NonNull Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }

    public static void removeListener(@NonNull Listener l) { LISTENERS.remove(l); }

    // ── pure helpers ────────────────────────────────────────────────────────────────────

    /**
     * Puts {@code argb} at the front of {@code list}: a colour with the same RGB (alpha ignored) that is already there
     * moves to the front instead of being added twice, and the list is trimmed to {@link #MAX}. Returns {@code list}.
     */
    @NonNull
    public static List<Integer> pushInto(@NonNull List<Integer> list, @ColorInt int argb) {
        final int rgb = argb & 0x00FFFFFF;
        for (int i = list.size() - 1; i >= 0; i--) {
            if ((list.get(i) & 0x00FFFFFF) == rgb) list.remove(i);
        }
        list.add(0, argb);
        while (list.size() > MAX) list.remove(list.size() - 1);
        return list;
    }

    /** The stored string as a list, most recent first; a part that is not hex is skipped, and at most {@link #MAX} are read. */
    @NonNull
    public static List<Integer> parse(String raw) {
        List<Integer> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            try { out.add((int) Long.parseLong(part.trim(), 16)); } catch (Exception ignored) { }
            if (out.size() >= MAX) break;
        }
        return out;
    }

    /** The stored form of a list: comma-separated {@code Integer.toHexString}. */
    @NonNull
    public static String format(@NonNull List<Integer> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Integer.toHexString(list.get(i)));
        }
        return sb.toString();
    }

    // ── the stored history ──────────────────────────────────────────────────────────────

    @NonNull
    private static SharedPreferences prefs(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** A copy of the history, most recent first, at most {@link #MAX}. */
    @NonNull
    public static List<Integer> get(@NonNull Context ctx) {
        return parse(prefs(ctx).getString(KEY_RECENTS, ""));
    }

    /** Adds a colour to the history and tells every listener. */
    public static void push(@NonNull Context ctx, @ColorInt int argb) {
        List<Integer> cur = get(ctx);
        pushInto(cur, argb);
        prefs(ctx).edit().putString(KEY_RECENTS, format(cur)).apply();
        for (Listener l : LISTENERS) l.onRecentsChanged();
    }
}
