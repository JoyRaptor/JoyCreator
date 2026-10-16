package com.fadcam.ui.faditor.ai.actions;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * What an APP-WIDE action is handed (gap F2, second host): the app, and the project Joybot was opened
 * with if any. Unlike {@link StudioCtx} there is no loaded project to change — these actions read the
 * Library, open rooms, start things. A handler that wants to go somewhere sets {@link #route}; the
 * chat screen takes it there on the main thread once the action has finished.
 */
public final class AppCtx {
    @NonNull public final Context app;
    /** The project Joybot was opened with, or null (the Lobby). */
    @Nullable public final String projectId;
    /** Where to go next ("spritelab", "library"…), or null to stay. Set by NAVIGATE actions. */
    @Nullable public String route;

    public AppCtx(@NonNull Context app, @Nullable String projectId) {
        this.app = app;
        this.projectId = projectId;
    }
}
