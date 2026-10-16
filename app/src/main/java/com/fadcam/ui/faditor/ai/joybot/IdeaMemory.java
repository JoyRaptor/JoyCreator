package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * What Joybot remembers about his own suggestions, per project, so he stays easy to ignore.
 *
 * <ul>
 *   <li><b>Seen</b> — the idea was on screen. His glow is only for ideas you have not seen.</li>
 *   <li><b>Ignored</b> — seen and the chat closed without it being tapped. Twice → quiet.</li>
 *   <li><b>Quiet</b> — never offered again for this project (waved away, or ignored twice).</li>
 * </ul>
 * A tap clears the ignore count: an idea you use is not one you are tired of.
 */
public final class IdeaMemory {

    private static final String PREFS = "joybot_ideas";
    private static final int IGNORES_TO_QUIET = 2;

    private final SharedPreferences prefs;

    public IdeaMemory(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public Set<String> quiet(@NonNull String projectId) {
        return new HashSet<>(prefs.getStringSet(k(projectId, "quiet"), new HashSet<>()));
    }

    public void quieten(@NonNull String projectId, @NonNull String quietId) {
        Set<String> q = quiet(projectId);
        q.add(quietId);
        prefs.edit().putStringSet(k(projectId, "quiet"), q).apply();
    }

    public boolean seen(@NonNull String projectId, @NonNull String quietId) {
        return prefs.getBoolean(k(projectId, "seen|" + quietId), false);
    }

    public void markSeen(@NonNull String projectId, @NonNull String quietId) {
        prefs.edit().putBoolean(k(projectId, "seen|" + quietId), true).apply();
    }

    /** The chat closed with this idea on screen and untouched. */
    public void markIgnored(@NonNull String projectId, @NonNull String quietId) {
        String key = k(projectId, "ignored|" + quietId);
        int n = prefs.getInt(key, 0) + 1;
        prefs.edit().putInt(key, n).apply();
        if (n >= IGNORES_TO_QUIET) quieten(projectId, quietId);
    }

    public void markTapped(@NonNull String projectId, @NonNull String quietId) {
        prefs.edit().remove(k(projectId, "ignored|" + quietId)).apply();
    }

    /** True when the best idea is one you have not seen yet: the glow's whole rule. */
    public boolean hasFreshIdea(@NonNull String projectId, @Nullable Idea best) {
        return best != null && !seen(projectId, best.quietId());
    }

    private static String k(String projectId, String what) {
        return projectId + "|" + what;
    }
}
