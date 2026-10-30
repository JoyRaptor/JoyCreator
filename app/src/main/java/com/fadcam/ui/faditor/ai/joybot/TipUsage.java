package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.library.LibraryGlance;
import com.fadcam.ui.library.LibraryItem;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Which wings this person really uses, for {@link TipBook}: what they made in the last 60 days
 * (read from the Library at most once a day, in the background) plus where they open Joybot.
 * Also which tips they've seen, so one doesn't come back until the rest have. Kept in a small
 * private preferences file; nothing leaves the phone.
 */
public final class TipUsage {
    private TipUsage() { }

    private static final String PREFS = "joybot_tips";
    private static final String SEEN = "seen";
    private static final String READ_AT = "library_read_at";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long WINDOW_MS = 60L * DAY_MS;

    /** A tip for now, leaning to what this person makes; remembered as seen. Any thread. */
    @NonNull
    public static TipBook.Tip next(@NonNull Context context) {
        SharedPreferences p = prefs(context);
        refreshFromLibraryIfStale(context, p);
        Set<String> seen = new HashSet<>(p.getStringSet(SEEN, new HashSet<String>()));
        TipBook.Tip tip = TipBook.pick(uses(p), seen, new Random());
        if (seen.size() >= TipBook.TIPS.size() - 1) seen.clear();   // all seen: start again
        seen.add(tip.id);
        p.edit().putStringSet(SEEN, seen).apply();
        return tip;
    }

    /** Joybot was opened in this room: a little weight toward its wing. */
    public static void openedIn(@NonNull Context context, @NonNull IdeaEngine.Room room) {
        TipBook.Wing w = wingOf(room);
        if (w == null) return;
        SharedPreferences p = prefs(context);
        p.edit().putInt("open_" + w.name(), Math.min(1000, p.getInt("open_" + w.name(), 0) + 1)).apply();
    }

    @NonNull
    private static Map<TipBook.Wing, Integer> uses(@NonNull SharedPreferences p) {
        Map<TipBook.Wing, Integer> m = new EnumMap<>(TipBook.Wing.class);
        for (TipBook.Wing w : TipBook.Wing.values()) {
            int n = p.getInt("made_" + w.name(), 0) * 3 + p.getInt("open_" + w.name(), 0);
            if (n > 0) m.put(w, n);
        }
        return m;
    }

    private static void refreshFromLibraryIfStale(@NonNull Context context, @NonNull SharedPreferences p) {
        // The Library read reports back on the main thread; from Joybot's worker, skip it this time.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong(READ_AT, 0) < DAY_MS) return;
        p.edit().putLong(READ_AT, now).apply();   // once a day, even if the read fails
        try {
            new LibraryGlance(context.getApplicationContext()).loadAll((items, complete) -> {
                if (!complete) return;
                count(p, items, now);
            });
        } catch (Throwable ignored) { }
    }

    private static void count(@NonNull SharedPreferences p, @NonNull List<LibraryItem> items, long now) {
        Map<TipBook.Wing, Integer> made = new EnumMap<>(TipBook.Wing.class);
        for (LibraryItem it : items) {
            if (now - it.modified > WINDOW_MS) continue;
            TipBook.Wing w = wingOf(it);
            if (w != null) made.put(w, (made.containsKey(w) ? made.get(w) : 0) + 1);
        }
        SharedPreferences.Editor e = p.edit();
        for (TipBook.Wing w : TipBook.Wing.values()) {
            Integer n = made.get(w);
            e.putInt("made_" + w.name(), n == null ? 0 : Math.min(500, n));
        }
        e.apply();
    }

    @Nullable
    private static TipBook.Wing wingOf(@NonNull LibraryItem it) {
        switch (it.kind) {
            case PROJECTS: case EXPORTS: case CLIPS: return TipBook.Wing.STUDIO;
            case DRAWINGS: return TipBook.Wing.JOYBRUSH;
            case SPRITE_SHEETS: case AVATARS: return TipBook.Wing.SPRITELAB;
            case RECORDINGS: return TipBook.Wing.CAPTURE;
            default: return null;
        }
    }

    @Nullable
    private static TipBook.Wing wingOf(@NonNull IdeaEngine.Room room) {
        switch (room) {
            case STUDIO: return TipBook.Wing.STUDIO;
            case VISLAB: return TipBook.Wing.VISLAB;
            case JOYBRUSH: return TipBook.Wing.JOYBRUSH;
            case SPRITELAB: case AVATAR: return TipBook.Wing.SPRITELAB;
            case LIBRARY: return TipBook.Wing.LIBRARY;
            default: return null;
        }
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
