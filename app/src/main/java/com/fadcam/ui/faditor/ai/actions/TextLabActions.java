package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Type;

import cc.joycreator.textlab.TextLabRoom;

import java.util.Collections;
import java.util.List;

/**
 * TextLab, the film-poster words room (TEXT-1), as actions (app-wide host). They act on the look the TextLab room
 * shows (through {@link TextLabRoom}, on the room's own thread); a request's changes are one undo step there.
 * Putting a look on a Studio title is Studio's {@code set_title_look} (TextLookActions).
 */
final class TextLabActions {
    private TextLabActions() { }

    private static final String FAMILY = "textlab";

    static void register(@NonNull ActionRegistry<AppCtx> r) {
        r.add(ActionSpec.of("textlab_looks", "List the TextLab looks")
                .help("Lists TextLab's looks (film-poster words: chrome, gold, neon, fire, ice…), the person's own"
                        + " ones marked, and which one the room shows. Call before picking a look by name.")
                .wing("APP", FAMILY).effect(Effect.READ)
                .where("TextLab › the looks row")
                .build(), TextLabActions::looks);

        r.add(ActionSpec.of("textlab_describe", "Read the TextLab look")
                .help("Reads the look on screen: its name, words, pieces, slider groups and the sliders it has"
                        + " changed. With slot (a group: Shape, Depth, Coats, Material, Light, Fire, Rust…): every"
                        + " slider of that group with its id, range and value now. With pieces=true: every piece"
                        + " a look can be built from. Read before changing.")
                .wing("APP", FAMILY).effect(Effect.READ)
                .where("TextLab › the tabs under the picture")
                .text("slot", false, "A slider group to list in full (a tab's name)")
                .flag("pieces", false, "List the pieces a look can be built from")
                .build(), TextLabActions::describe);

        r.add(ActionSpec.of("textlab_pick_look", "Show a TextLab look")
                .help("Puts a look on screen, as tapping it in the row does. look: its name.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("TextLab › the looks row")
                .text("look", true, "The look's name (e.g. Dark Steel, Neon Sign, Blaze)")
                .build(), TextLabActions::pick);

        r.add(ActionSpec.of("textlab_change", "Change the TextLab look")
                .help("Changes the look on screen; put a whole request in one call (it is one undo step)."
                        + " changes: lines \"<slider label or id> = <number or #rrggbb>\" (textlab_describe slot=… lists"
                        + " them with their ranges), \"piece <name> on\" / \"piece <name> off\" to add or take away a"
                        + " piece (Fire, Rust, Burn, Glass, Lightning, Vine…), \"words = …\", \"font = <name>\""
                        + " (Archivo, Bebas Neue, Cinzel, Orbitron, Racing Sans, Pacifico, Plex Sans, Plex Mono,"
                        + " Ubuntu, Doto), \"background = <name>\" (clear, black, dark stage, smoke, brick wall,"
                        + " far lights, dusk grid, sea and sky, night window, paper, cloth). Nothing is saved until"
                        + " textlab_save.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("TextLab › a tab › its sliders")
                .param("changes", Type.STRING_LIST, true, "Lines like \"Pushed back = 1.2\" or \"piece Fire on\"")
                .build(), TextLabActions::change);

        r.add(ActionSpec.of("textlab_save", "Save the TextLab look")
                .help("Saves the look on screen into the Joy Creator folder (Supplies › Looks). A built-in look"
                        + " becomes the person's own copy in its place (Back to the original undoes that). as_new:"
                        + " keep the original and save as a new look with this name.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("TextLab › Save")
                .text("as_new", false, "Save as a new look with this name")
                .build(), TextLabActions::save);
    }

    @Nullable
    private static String group() {
        String g = AIChatState.liveWriteRequest;
        return g == null || g.isEmpty() ? null : "joybot:" + g;
    }

    @NonNull
    private static ActionResult looks(@NonNull AppCtx ctx, @NonNull Args a) {
        List<String> names = TextLabRoom.lookNames(ctx.app);
        StringBuilder sb = new StringBuilder(names.size() + " looks: ").append(String.join(", ", names)).append('.');
        if (TextLabRoom.isOpen()) {
            try {
                String d = TextLabRoom.describe();
                int semi = d.indexOf(';');
                sb.append(" On screen: ").append(semi > 0 ? d.substring(0, semi) : d).append('.');
            } catch (TextLabRoom.Refused ignored) { }
        } else {
            sb.append(" The TextLab room isn't open (open_room textlab opens it).");
        }
        return ActionResult.unchanged(sb.toString());
    }

    @NonNull
    private static ActionResult describe(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        try {
            if (Boolean.TRUE.equals(a.bool("pieces"))) return ActionResult.unchanged(TextLabRoom.pieces());
            String slot = a.str("slot");
            if (slot != null && !slot.trim().isEmpty()) return ActionResult.unchanged(TextLabRoom.slot(slot));
            return ActionResult.unchanged(TextLabRoom.describe());
        } catch (TextLabRoom.Refused e) {
            throw new ActionError(e.getMessage());
        }
    }

    @NonNull
    private static ActionResult pick(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        String look = a.str("look");
        if (look == null || look.trim().isEmpty()) throw new ActionError("Which look? textlab_looks lists them.");
        try {
            if (!TextLabRoom.pick(look.trim(), group())) {
                throw new ActionError("No look called " + look + " (textlab_looks lists them).");
            }
        } catch (TextLabRoom.Refused e) {
            throw new ActionError(e.getMessage());
        }
        return ActionResult.changed("Showing " + look.trim() + ".", Collections.emptyList());
    }

    @NonNull
    private static ActionResult change(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        List<String> lines = a.list("changes");
        if (lines.isEmpty()) throw new ActionError("Nothing to change: give changes lines.");
        try {
            List<String> done = TextLabRoom.change(lines, group());
            return ActionResult.changed(String.join("\n", done) + "\n(Not saved yet: textlab_save keeps it.)",
                    Collections.emptyList());
        } catch (TextLabRoom.Refused e) {
            throw new ActionError(e.getMessage());
        }
    }

    @NonNull
    private static ActionResult save(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        try {
            return ActionResult.changed(TextLabRoom.save(a.str("as_new")), Collections.emptyList());
        } catch (TextLabRoom.Refused e) {
            throw new ActionError(e.getMessage());
        }
    }
}
