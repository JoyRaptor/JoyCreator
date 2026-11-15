package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.ai.AIToolExecutor;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.ai.joybot.Shape;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;

import org.json.JSONObject;

/**
 * Making something new (BOT-7, the lobby's "Create something"). The owner asked for "a new blank
 * Studio project, 3:1" and Joybot had no way to make one, so he said "doing it" and then "I can't".
 * Now there is: any shape, offline, landing in the Studio already set up. Used by the model and by
 * the create tree's buttons alike ({@link com.fadcam.ui.faditor.ai.joybot.CreateTree}).
 */
final class CreateActions {
    private CreateActions() { }

    private static final String FAMILY = "create";

    /** The chat's route() opens "project:<id>" in the Studio editor. */
    static final String ROUTE_PROJECT = "project:";
    /** The chat's route() starts a blank audio project (the Studio's own "Blank audio project"). */
    static final String ROUTE_AUDIO = "studio_audio";

    static void register(@NonNull ActionRegistry<AppCtx> r) {
        r.add(ActionSpec.of("new_studio_project", "Start a new Studio project")
                .help("Makes a NEW Studio project and opens it. For a video: at the picture shape asked for"
                        + " (16:9, 9:16, 1:1, 4:5, 21:9, or any other such as a 3:1 banner), with a plain title card"
                        + " first so it isn't empty; the person adds their videos and pictures there. audio=true makes"
                        + " an audio-only project instead (no picture). Works with no AI. Use it for \"a new blank"
                        + " project, 3:1\", \"start a vertical video\", \"new podcast project\".")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("Lobby › Joybot › Create something › Video")
                .text("shape", false, "The picture's shape: \"16:9\", \"9:16\", \"3:1\", \"square\"… (default 16:9)")
                .text("name", false, "The project's name (default: \"Untitled\" and its shape)")
                .flag("audio", false, "An audio-only project (podcast, music): no picture, no shape")
                .build(), CreateActions::newStudioProject);
    }

    @NonNull
    private static ActionResult newStudioProject(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        if (Boolean.TRUE.equals(a.bool("audio"))) {
            ctx.route = ROUTE_AUDIO;
            return ActionResult.unchanged("Opening a new audio project in the Studio.");
        }
        String said = a.str("shape");
        Shape shape = said == null || said.trim().isEmpty() ? Shape.of(16, 9) : Shape.parse(said);
        if (shape == null) {
            throw new ActionError("\"" + said + "\" isn't a shape I know. Say it as width:height, like 16:9, 9:16 or 3:1.");
        }
        ProjectStorage storage = new ProjectStorage(ctx.app);
        String name = a.str("name");
        if (name == null || name.trim().isEmpty()) name = "Untitled " + shape.ratio();
        FaditorProject p = new FaditorProject(storage.uniqueName(name.trim()));
        p.setCanvasPreset(shape.studioCanvas());
        if (!storage.save(p)) throw new ActionError("the new project couldn't be saved (is the phone full?), so nothing was made.");
        // A project with nothing in it can't be reopened (the editor wants a clip), so it starts with
        // a plain title card in its own shape, made on the phone (no AI, no cost).
        try {
            JSONObject args = new JSONObject()
                    .put("mode", "fullscreen")
                    .put("title_or_text", p.getName())
                    .put("style_hint", "plain title card; clean, modern")
                    .put("duration_ms_hint", 3000)
                    .put("kind", "title")
                    .put("built_in", true)
                    .put("placement", new JSONObject().put("insertAtClipIndex", 0));
            String made = new AIToolExecutor(ctx.app, p.getId()).executeTool("generate_slide", args);
            if (made.startsWith("Error") || made.startsWith("Validation") || made.startsWith("Apply")) {
                throw new ActionError("the new project's first card couldn't be made (" + made + "), so it isn't ready.");
            }
        } catch (ActionError e) {
            throw e;
        } catch (Exception e) {
            throw new ActionError("the new project's first card couldn't be made: " + e.getMessage());
        }
        ctx.route = ROUTE_PROJECT + p.getId();
        return ActionResult.changed("Made a new " + shape.words() + " Studio project, “" + p.getName()
                + "”, with a title card to start, and I'm opening it.", java.util.Collections.<String>emptyList());
    }
}
