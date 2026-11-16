package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A request asked somewhere it couldn't run, carried to where it can (BOT-7). In the lobby, "make a
 * transition" asks "in the last project you had open?"; "Open it" opens the project, and the chat
 * opened there offers it once: "You asked me to “make a transition”. Do it here now?". Kept ten
 * minutes, offered once, never run without that second yes.
 */
public final class CarriedAsk {
    private CarriedAsk() { }

    private static final String PREFS = "joybot_carried_ask";
    private static final long KEEP_MS = 10 * 60_000L;

    @NonNull public static String forProject(@NonNull String projectId) { return "project:" + projectId; }
    @NonNull public static String forRoom(@NonNull String room) { return "room:" + room.toLowerCase(java.util.Locale.US); }

    public static void save(@NonNull Context c, @NonNull String where, @Nullable String asked) {
        if (asked == null || asked.trim().isEmpty()) return;
        prefs(c).edit().clear()
                .putString("where", where).putString("asked", asked.trim())
                .putLong("at", System.currentTimeMillis()).apply();
    }

    /** The request carried to {@code where}, once (it is forgotten as it is read); null if none. */
    @Nullable
    public static String take(@NonNull Context c, @NonNull String where) {
        SharedPreferences p = prefs(c);
        if (!where.equals(p.getString("where", null))) return null;
        String asked = p.getString("asked", null);
        long at = p.getLong("at", 0L);
        p.edit().clear().apply();
        return asked != null && System.currentTimeMillis() - at <= KEEP_MS ? asked : null;
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
