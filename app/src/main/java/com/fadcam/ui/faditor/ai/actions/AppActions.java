package com.fadcam.ui.faditor.ai.actions;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * The app-wide actions (gap F2, second host) — the ones that aren't about one project: open a room,
 * look through the Library, start something new. {@link StudioActions} covers everything done TO a
 * project; together they are the one list. {@link com.fadcam.ui.faditor.ai.AIToolExecutor} asks the
 * Studio first, then here, then its old switch, so every caller (the model, idea buttons, recipes)
 * reaches these through the door it already uses.
 */
public final class AppActions {
    private AppActions() { }

    @Nullable private static volatile ActionRegistry<AppCtx> REG;
    /** Where the last NAVIGATE action asked to go; the chat takes it (main thread). */
    @Nullable private static volatile String pendingRoute;

    @NonNull
    public static ActionRegistry<AppCtx> registry() {
        ActionRegistry<AppCtx> r = REG;
        if (r == null) {
            synchronized (AppActions.class) {
                if ((r = REG) == null) REG = r = build();
            }
        }
        return r;
    }

    private interface Family { void register(@NonNull ActionRegistry<AppCtx> r); }

    @NonNull
    private static ActionRegistry<AppCtx> build() {
        ActionRegistry<AppCtx> r = new ActionRegistry<>();
        add(r, "navigate", NavigateActions::register);
        add(r, "library", LibraryActions::register);
        return r;
    }

    /** One family that fails to declare itself costs only its own actions (see StudioActions.add). */
    private static void add(@NonNull ActionRegistry<AppCtx> r, @NonNull String name, @NonNull Family f) {
        try {
            f.register(r);
        } catch (Throwable t) {
            com.fadcam.FLog.e("AppActions", "action family \"" + name + "\" left out", t);
        }
    }

    /** Runs an app-wide action. Returns the text executeTool's callers read ("Error: …" to fix). */
    @NonNull
    public static String execute(@NonNull Context app, @Nullable String projectId, @NonNull String tool,
                                 @NonNull JSONObject args) {
        AppCtx ctx = new AppCtx(app.getApplicationContext(), projectId);
        try {
            ActionResult res = registry().run(ctx, tool, StudioActions.toMap(args));
            if (ctx.route != null) pendingRoute = ctx.route;
            return res.toToolText();
        } catch (ActionError e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + tool + " failed: " + e.getMessage();
        }
    }

    /** Where an action asked to go, once; null when nothing is waiting. Main thread. */
    @Nullable
    public static String takePendingRoute() {
        String r = pendingRoute;
        pendingRoute = null;
        return r;
    }
}
