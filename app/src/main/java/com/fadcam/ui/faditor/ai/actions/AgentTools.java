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

    @NonNull
    public static List<String> nativeToolsJson() {
        List<String> out = new ArrayList<>();
        out.add("{\"type\":\"function\",\"function\":{\"name\":" + ActionSpec.quote(PLAN.tool)
                + ",\"description\":" + ActionSpec.quote(PLAN.description())
                + ",\"parameters\":" + PLAN.schemaJson() + "}}");
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
