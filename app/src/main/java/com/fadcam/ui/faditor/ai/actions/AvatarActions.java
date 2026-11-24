package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;

import cc.joycreator.avatar.AvatarRoom;

import java.util.Collections;

/**
 * Avatar Studio, the character room (AVA-1), as actions (app-wide host). They act on the character the room shows,
 * through {@link AvatarRoom} on the room's own thread; each change is one named undo step there.
 */
final class AvatarActions {
    private AvatarActions() { }

    private static final String FAMILY = "avatar";

    static void register(@NonNull ActionRegistry<AppCtx> r) {
        r.add(ActionSpec.of("avatar_describe", "Read the Avatar Studio character")
                .help("Reads the character on screen: its name, how many layers (and how many are hidden), the stage"
                        + " (Set up, Arrange, Motion, Perform), the part in hand, the parts it has, and whether it is saved.")
                .wing("APP", FAMILY).effect(Effect.READ)
                .where("Avatar Studio")
                .build(), AvatarActions::describe);

        r.add(ActionSpec.of("avatar_part_choose", "Take a part in hand")
                .help("Puts a layer or part in hand, as tapping it does. part: its name or its part word (head, nose,"
                        + " left arm ...).")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("Avatar Studio > Parts, or Layers")
                .text("part", true, "The layer's name or part (e.g. Nose, head, armL)")
                .build(), AvatarActions::choose);

        r.add(ActionSpec.of("avatar_stage", "Go to an Avatar Studio stage")
                .help("Switches the room to a stage: Set up, Arrange, Motion or Perform.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("Avatar Studio > the four stages")
                .text("stage", true, "set up, arrange, motion or perform")
                .build(), AvatarActions::stage);

        r.add(ActionSpec.of("avatar_layer_visible", "Show or hide a layer")
                .help("Shows or hides one layer of the character, as its eye does. One undo step.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("Avatar Studio > Layers")
                .text("layer", true, "The layer's name")
                .flag("shown", true, "true to show it, false to hide it")
                .build(), AvatarActions::visible);

        r.add(ActionSpec.of("avatar_layer_stack", "Move a layer in front or behind")
                .help("Moves one layer in the drawing order among its neighbours. one undo step.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("Avatar Studio > Arrange > In front or behind")
                .text("layer", true, "The layer's name")
                .text("how", true, "back one, forward one, very back or very front")
                .build(), AvatarActions::stack);

        r.add(ActionSpec.of("avatar_save", "Save the Avatar Studio character")
                .help("Saves the character into the Joy Creator folder now (it also saves by itself after a change).")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("Avatar Studio > Menu > Save")
                .build(), AvatarActions::save);
    }

    @NonNull
    private static ActionResult describe(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        try {
            return ActionResult.unchanged(AvatarRoom.describe());
        } catch (AvatarRoom.Refused e) {
            throw new ActionError(e.getMessage());
        }
    }

    @NonNull
    private static ActionResult choose(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        String part = a.str("part");
        if (part == null || part.trim().isEmpty()) throw new ActionError("Which part? avatar_describe lists them.");
        try {
            if (!AvatarRoom.choosePart(part.trim())) throw new ActionError("No layer or part called " + part + " (avatar_describe lists the parts).");
        } catch (AvatarRoom.Refused e) {
            throw new ActionError(e.getMessage());
        }
        return ActionResult.changed(part.trim() + " is in hand.", Collections.emptyList());
    }

    @NonNull
    private static ActionResult stage(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        String stage = a.str("stage");
        if (stage == null || stage.trim().isEmpty()) throw new ActionError("Which stage? Set up, Arrange, Motion or Perform.");
        try {
            if (!AvatarRoom.stage(stage.trim())) throw new ActionError("No stage called " + stage + ". The stages are Set up, Arrange, Motion and Perform.");
        } catch (AvatarRoom.Refused e) {
            throw new ActionError(e.getMessage());
        }
        return ActionResult.changed("Now on " + stage.trim() + ".", Collections.emptyList());
    }

    @NonNull
    private static ActionResult visible(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        String layer = a.str("layer");
        Boolean shown = a.bool("shown");
        if (layer == null || layer.trim().isEmpty() || shown == null) throw new ActionError("Give the layer's name and shown true or false.");
        try {
            String said = AvatarRoom.shown(layer.trim(), shown);
            if (said == null) throw new ActionError("No layer called " + layer + " (avatar_describe lists the parts).");
            return ActionResult.changed(said, Collections.emptyList());
        } catch (AvatarRoom.Refused e) {
            throw new ActionError(e.getMessage());
        }
    }

    @NonNull
    private static ActionResult stack(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        String layer = a.str("layer"), how = a.str("how");
        if (layer == null || layer.trim().isEmpty() || how == null || how.trim().isEmpty())
            throw new ActionError("Give the layer's name and how: back one, forward one, very back or very front.");
        try {
            String said = AvatarRoom.stack(layer.trim(), how.trim());
            if (said == null) throw new ActionError("No layer called " + layer + " (avatar_describe lists the parts).");
            if (said.isEmpty()) throw new ActionError("how must be back one, forward one, very back or very front.");
            return ActionResult.changed(said, Collections.emptyList());
        } catch (AvatarRoom.Refused e) {
            throw new ActionError(e.getMessage());
        }
    }

    @NonNull
    private static ActionResult save(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        try {
            AvatarRoom.save();
            return ActionResult.changed("Saved in the Joy Creator folder.", Collections.emptyList());
        } catch (AvatarRoom.Refused e) {
            throw new ActionError(e.getMessage());
        }
    }
}
