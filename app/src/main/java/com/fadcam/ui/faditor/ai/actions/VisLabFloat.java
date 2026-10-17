package com.fadcam.ui.faditor.ai.actions;

import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.vislab.overlay.VisOverlayService;
import com.fadcam.vislab.room.VisLabSession;
import com.google.gson.JsonObject;

/**
 * The floating visualiser (VisLab's overlay over the screen and other apps) as one action: on,
 * off, and which look floats. It needs Android's "Display over other apps", which only the person
 * can allow: without it Joybot says so, and on their say-so opens that settings page for them to
 * flip ({@link #ROUTE_PERMISSION}). Joybot never changes the permission itself.
 */
final class VisLabFloat {
    private VisLabFloat() { }

    /** The chat's route() opens Android's "Display over other apps" page for Joy Creator. */
    static final String ROUTE_PERMISSION = "overlay_permission";

    static void register(@NonNull ActionRegistry<AppCtx> r) {
        r.add(ActionSpec.of("vislab_float", "Float the visualiser over the screen")
                .help("Turns VisLab's floating visualiser on or off: a look that moves to the sound, floating over"
                        + " any app, for screen recordings. look: which look floats (default: the one on screen in"
                        + " VisLab); with the visualiser already floating, a look alone switches it. It needs"
                        + " Android's \"Display over other apps\" for Joy Creator (Samsung calls it \"Appear on top\"), which only the person can allow:"
                        + " if it's missing, say so and ask whether to open that settings page; call again with"
                        + " open_settings=true only when they say yes (it closes the chat).")
                .wing("APP", "vislab").effect(Effect.EDIT)
                .where("Capture › recording toolbar › Visualiser")
                .flag("on", false, "true: float it; false: stop it (leave out to just change the look)")
                .text("look", false, "The look's name or id")
                .flag("open_settings", false, "true: open the \"Display over other apps\" page for the person")
                .build(), VisLabFloat::run);
    }

    @NonNull
    private static ActionResult run(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        if (Boolean.TRUE.equals(a.bool("open_settings"))) {
            ctx.route = ROUTE_PERMISSION;
            return ActionResult.unchanged("Opening Android's \"Display over other apps\" page: switch Joy Creator on"
                    + " there, then come back and ask again.");
        }
        Boolean on = a.bool("on");
        String lookRef = a.str("look");
        if (on == null && lookRef == null) {
            return ActionResult.unchanged("The floating visualiser is " + (VisOverlayService.isRunning ? "on." : "off."));
        }
        if (Boolean.FALSE.equals(on)) {
            if (VisOverlayService.isRunning) VisOverlayService.toggle(ctx.app);
            return ActionResult.changed("The floating visualiser is off.", new java.util.ArrayList<>());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx.app)) {
            // First line is what the person sees on the step; the rest is for the model.
            throw new ActionError("Needs \"Display over other apps\" (Samsung: \"Appear on top\"), which only you can allow.\n"
                    + "Not done: tell them Joy Creator isn't allowed to \"Display over other apps\" yet and only they"
                    + " can allow it in Android's settings; ask whether to open that page (then call with"
                    + " open_settings=true).");
        }
        VisLabSession s = VisLabActions.session(ctx);
        JsonObject look = VisLabActions.look(s, lookRef);
        String name = VisLabEdit.str(look, "name");
        VisOverlayService.setLook(ctx.app, VisLabEdit.str(look, "id"));
        if (VisOverlayService.isRunning) {
            return ActionResult.changed("The floating visualiser now shows “" + name + "”.", new java.util.ArrayList<>());
        }
        VisOverlayService.toggle(ctx.app);
        return ActionResult.changed("The visualiser is floating over the screen with “" + name + "”. Drag it to move"
                + " it; its own buttons resize or close it.", new java.util.ArrayList<>());
    }
}
