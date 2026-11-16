package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * How often this person has made each kind of thing from the create tree (BOT-7): kept on the
 * phone only, so the bubbles they use most come first ({@link CreateTree#ordered}).
 */
public final class CreateUsage {
    private CreateUsage() { }

    private static final String PREFS = "joybot_create_usage";

    @NonNull
    public static Map<String, Integer> counts(@NonNull Context c) {
        Map<String, Integer> m = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            if (e.getValue() instanceof Integer) m.put(e.getKey(), (Integer) e.getValue());
        }
        return m;
    }

    public static void made(@NonNull Context c, @NonNull String kindId) {
        SharedPreferences p = prefs(c);
        p.edit().putInt(kindId, p.getInt(kindId, 0) + 1).apply();
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
