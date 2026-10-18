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
        add(r, "vislab", VisLabActions::register);
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
            if (res.changed) CHANGES.incrementAndGet();
            if (ctx.route != null) pendingRoute = ctx.route;
            if (ctx.openItem != null) {
                pendingItem = ctx.openItem;
                pendingAsNewProject = ctx.asNewProject;
            }
            return res.toToolText();
        } catch (ActionError e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + tool + " failed: " + e.getMessage();
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong CHANGES = new java.util.concurrent.atomic.AtomicLong();

    /** How many app-wide actions have changed something (a VisLab look…), for "did it really change?". */
    public static long changes() {
        return CHANGES.get();
    }

    @Nullable private static volatile com.fadcam.ui.library.LibraryItem pendingItem;
    private static volatile boolean pendingAsNewProject;

    /**
     * Opens the Library thing an action named, once (as tapping it in the Library would, or as a
     * new Studio project). True when something was waiting. Main thread.
     */
    public static boolean takePendingOpen(@NonNull android.app.Activity a) {
        com.fadcam.ui.library.LibraryItem it = pendingItem;
        boolean asNew = pendingAsNewProject;
        pendingItem = null;
        if (it == null) return false;
        if (asNew && it.uri != null) {
            // The Library's "new project from this video": the editor builds the project from the
            // file, exactly as when one is picked (NewProjectFlow.openEditor, new-project branch).
            android.content.Intent i = new android.content.Intent(a, com.fadcam.ui.faditor.FaditorEditorActivity.class);
            i.setData(android.net.Uri.parse(it.uri));
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(i);
            return true;
        }
        return com.fadcam.ui.library.LibraryOpen.open(a, it);
    }

    /** True while an action's route is waiting for the chat (the request should end: the chat is leaving). */
    public static boolean hasPendingRoute() {
        return pendingRoute != null;
    }

    /** Where an action asked to go, once; null when nothing is waiting. Main thread. */
    @Nullable
    public static String takePendingRoute() {
        String r = pendingRoute;
        pendingRoute = null;
        return r;
    }
}
