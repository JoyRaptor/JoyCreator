package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Tools that belong to Joybot himself rather than to the project (gap F3): the model calls them,
 * but JoybotAgent answers them — they never reach a handler. Declared with the same
 * {@link ActionSpec} so the model sees an exact schema either way.
 */
public final class AgentTools {
    private AgentTools() { }

    public static final String PROPOSE_PLAN = "propose_plan";

    @NonNull
    static final ActionSpec PLAN = ActionSpec.of(PROPOSE_PLAN, "Show a plan first")
            .help("Before a BIG job (more than about four changes, or anything over the whole video:"
                    + " 'add B-roll and chapter titles', 'clean up the whole talk'), call this with a short"
                    + " title and the steps in plain words, then STOP. The person sees it with a Go ahead"
                    + " button; once they say go you get many more steps and should do all of it without"
                    + " asking again. Small requests: just do them.")
            .wing("JOYBOT", "director")
            .effect(ActionSpec.Effect.READ)
            .text("title", true, "What the job is, in a few words (\"B-roll and chapters\")")
            .param("steps", ActionSpec.Type.STRING_LIST, true,
                    "The steps, each one short plain sentence a non-technical person understands")
            .build();

    /**
     * {@code look}'s exact schema for tool-calling models. Its time had been an open argument
     * described in prose, and a free model asked "is the title readable at 0:03?" looked at the
     * playhead instead (Note 9, 2026-10-24). The numbered text list keeps describing it (42g).
     */
    @NonNull
    static final ActionSpec LOOK = ActionSpec.of("look", "See the finished video or a thing in the project")
            .help("SEE something: the picture comes back with the next message. id \"playhead\" (the"
                    + " default) is the FINISHED video exactly as it exports (titles, pictures, sprites,"
                    + " visualizers, grade, crop, effects) with the ids of what is on screen: use it for \"is the"
                    + " title readable\", \"does the picture cover the face\", \"is the visualizer behind the"
                    + " text\", and look again after a change to check it. Any other id shows that one thing: a"
                    + " sprite sheet (cells numbered for label_sprite_cells / author_sprite_animation), a sprite"
                    + " or picture layer, or a clip's raw frame. Look first, then act; don't guess from names.")
            .wing("JOYBOT", "inspect")
            .effect(ActionSpec.Effect.READ)
            .text("id", false, "\"playhead\" for the finished video (default), or the id of a sprite sheet,"
                    + " sprite layer, picture layer or clip")
            .param("atMs", ActionSpec.Type.INT, false, "WHEN, in milliseconds. With playhead: the time on the"
                    + " timeline, so 0:03 is 3000 (leave it out only for the playhead's own time). With a clip:"
                    + " time into that clip (default its middle)")
            .flag("source", false, "With playhead: only the main clip's raw frame, no layers (quicker)")
            .build();

    @NonNull
    public static List<String> nativeToolsJson() {
        List<String> out = new ArrayList<>();
        for (ActionSpec s : new ActionSpec[]{PLAN, LOOK}) {
            out.add("{\"type\":\"function\",\"function\":{\"name\":" + ActionSpec.quote(s.tool)
                    + ",\"description\":" + ActionSpec.quote(s.description())
                    + ",\"parameters\":" + s.schemaJson() + "}}");
        }
        return out;
    }

    /** The numbered-text form for models without tool calling. */
    @NonNull
    public static String docsText(@NonNull String number) {
        return PLAN.docsEntry(number);
    }

    /**
     * Tools that only read, so a Director run needn't snapshot around them. Registered actions say
     * so themselves; the older tools are read-only by their name.
     */
    public static boolean isRead(@NonNull String tool) {
        ActionSpec s = StudioActions.registry().spec(tool);
        if (s != null) return s.effect == ActionSpec.Effect.READ;
        return tool.startsWith("get_") || tool.startsWith("list_") || tool.startsWith("describe_")
                || tool.startsWith("detect_") || tool.startsWith("analyze_") || tool.startsWith("suggest_")
                || tool.equals("health_check") || tool.equals("look") || tool.equals(PROPOSE_PLAN);
    }
}
