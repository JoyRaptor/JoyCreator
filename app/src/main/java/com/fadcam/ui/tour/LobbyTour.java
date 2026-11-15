package com.fadcam.ui.tour;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The lobby's first run and its tour (BOT-7, owner 11-15). The first time the lobby shows after
 * the intro and the folder step, Joybot greets the person with his first buttons and the create
 * tree: that IS the first walkthrough. "Show me around" lights the lobby's controls one at a time
 * ({@link Spotlight}). Only the lobby is scripted; the rooms' tours come once they stop changing.
 */
public final class LobbyTour {
    private LobbyTour() { }

    private static final String PREFS = "joybot_first_run";
    private static final String DONE = "lobby_v1_done";
    /** Set by the chat's "Show me around": the lobby runs the tour when it next shows. */
    private static volatile boolean pending;

    /** The chat asks for the lobby tour; it runs as soon as the lobby is on screen again. */
    public static void request() {
        pending = true;
    }

    /**
     * The lobby is on screen (LobbyFragment.onResume). Runs a requested tour, or, the first time
     * ever after the intro, opens Joybot's welcome.
     */
    public static void onLobbyShown(@NonNull Activity a, @Nullable View lobbyRoot) {
        if (lobbyRoot == null) return;
        if (pending) {
            pending = false;
            lobbyRoot.postDelayed(() -> run(a, lobbyRoot), 450L);   // after the lobby's first paint
            return;
        }
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(DONE, false)) return;
        boolean onboarded = com.fadcam.SharedPreferencesManager.getInstance(a).sharedPreferences
                .getBoolean(com.fadcam.Constants.COMPLETED_ONBOARDING_KEY, false);
        if (!onboarded) return;
        p.edit().putBoolean(DONE, true).apply();
        lobbyRoot.postDelayed(() -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            a.startActivity(new Intent(a, com.fadcam.ui.faditor.ai.ChatAssistantActivity.class)
                    .putExtra(com.fadcam.ui.faditor.ai.ChatAssistantActivity.EXTRA_ROOM, "LOBBY")
                    .putExtra(com.fadcam.ui.faditor.ai.ChatAssistantActivity.EXTRA_FIRST_RUN, true));
        }, 900L);
    }

    /** The lobby, one control at a time. */
    static void run(@NonNull Activity a, @NonNull View root) {
        if (a.isFinishing() || a.isDestroyed()) return;
        List<Spotlight.Step> s = new ArrayList<>();
        add(s, root, R.id.lobby_marquee_scroll, "These are the rooms. Each one makes a different kind of thing:"
                + " videos, drawings, sprites, characters, music visuals, titles. Swipe along to see them all.");
        add(s, root, R.id.lobby_hero, "The room you pick shows here, with your newest work in it.");
        add(s, root, R.id.lobby_hero_action, "This carries on with it, or starts something new in that room.");
        add(s, root, R.id.lobby_recents_scroll, "Your recent work, newest first, from every room.");
        add(s, root, R.id.lobby_new_row, "Start something new here, straight into the right room.");
        add(s, root, R.id.lobby_library_door, "The Library holds everything you've made, in one place.");
        add(s, root, R.id.lobby_bot, "And this is me. Tap me any time: I'll help you make something, find your"
                + " work, or answer “how do I…”.");
        Spotlight.show(a, s, null);
    }

    private static void add(@NonNull List<Spotlight.Step> s, @NonNull View root, int id, @NonNull String line) {
        View v = root.findViewById(id);
        if (v != null) s.add(new Spotlight.Step(v, line));
    }
}
