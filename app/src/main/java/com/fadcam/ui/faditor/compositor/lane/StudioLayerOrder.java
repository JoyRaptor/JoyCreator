package com.fadcam.ui.faditor.compositor.lane;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fadcam.BuildConfig;
import com.fadcam.ui.faditor.model.FaditorProject;

/** One rollout gate for the project control, new projects, and the normal editor render routes. */
public final class StudioLayerOrder {
    private static final String PREFS = "studio_debug";
    public static final String DEBUG_SWITCH = "lane_compositor";

    private StudioLayerOrder() {}

    public static boolean enabled(@NonNull Context context) {
        return BuildConfig.DEBUG && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(DEBUG_SWITCH, false);
    }

    public static void configureDebug(@NonNull Context context, boolean enabled) {
        if (!BuildConfig.DEBUG) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(DEBUG_SWITCH, enabled).apply();
    }

    @NonNull
    public static FaditorProject newProject(@NonNull Context context, @NonNull String name) {
        var project = new FaditorProject(name);
        project.setTrueLayerOrder(enabled(context));
        return project;
    }
}
