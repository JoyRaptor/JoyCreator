package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.vislab.room.VisLabSession;
import com.google.gson.JsonObject;

/**
 * VisLab looks as the person's files (Supplies/Looks/&lt;Name&gt;.vis.json, kept by
 * {@link VisLabSession} a moment after any change): save a copy under a new name, rename, put a
 * built-in look back as it came, delete one of their own. The last two need the person's yes
 * first — the room asks with the same words. The yes has to come from the person: confirmed=true
 * counts only in a LATER request than the one that asked (a model can set the flag itself; it
 * can't make the person answer). All four are undo steps that merge into the request's one undo.
 */
final class VisLabLookFiles {
    private VisLabLookFiles() { }

    private static final String FAMILY = "vislab";

    /** "delete:<id>" / "revert:<id>" → the request that asked the person. */
    private static final java.util.Map<String, String> ASKED = new java.util.concurrent.ConcurrentHashMap<>();

    static void register(@NonNull ActionRegistry<AppCtx> r) {
        r.add(ActionSpec.of("vislab_save_as", "Save a VisLab look under a new name")
                .help("Saves a copy of a look under a new name and shows it (\"save this as Club Night\"). Looks"
                        + " are kept as files in the Joy Creator folder on their own, so this is only for a"
                        + " copy. look defaults to the one on screen.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("VisLab › Edit › Duplicate")
                .text("name", true, "The new look's name")
                .text("look", false, "The look to copy (default: the one on screen)")
                .build(), VisLabLookFiles::saveAs);

        r.add(ActionSpec.of("vislab_rename", "Rename a VisLab look")
                .help("Gives a look a new name; its file in the Joy Creator folder follows.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("VisLab › Edit › Rename")
                .text("name", true, "The new name")
                .text("look", false, "The look to rename (default: the one on screen)")
                .build(), VisLabLookFiles::rename);

        r.add(ActionSpec.of("vislab_revert", "Put a VisLab look back as it came")
                .help("Puts a look that came with the app back to how it came, replacing every change made to"
                        + " it. ASK FIRST (before calling) and wait for their answer, in these words: \"Revert <name> to default? This will override any"
                        + " customisations you have made to this look. Undo can still bring them back.\" Call"
                        + " with confirmed=true only after they say yes.")
                .wing("APP", FAMILY).effect(Effect.DESTRUCTIVE)
                .where("VisLab › Edit › Revert to default")
                .text("look", false, "The look (default: the one on screen)")
                .flag("confirmed", false, "true once the person said yes")
                .build(), VisLabLookFiles::revert);

        r.add(ActionSpec.of("vislab_delete_look", "Delete a VisLab look")
                .help("Deletes one of the person's own looks; its file goes to the Trash in the Joy Creator"
                        + " folder (looks that came with the app can only be reverted). It is NOT permanent: undo brings it back. ASK FIRST (before calling) and wait for their answer: \"Delete"
                        + " <name>? Its file goes to the Trash in your Joy Creator folder. Undo brings it back.\""
                        + " Call with confirmed=true only after they say yes.")
                .wing("APP", FAMILY).effect(Effect.DESTRUCTIVE)
                .where("VisLab › Edit › Delete")
                .text("look", true, "The look's name or id")
                .flag("confirmed", false, "true once the person said yes")
                .build(), VisLabLookFiles::delete);
    }

    /** For the looks list: whose it is and whether it differs from how it came. */
    @NonNull
    static String status(@NonNull VisLabSession s, @NonNull String id) {
        if (!s.isBuiltIn(id)) return " [yours]";
        return s.isCustomised(id) ? " [changed from how it came]" : "";
    }

    @NonNull
    private static ActionResult saveAs(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        VisLabSession s = VisLabActions.session(ctx);
        String name = name(a);
        JsonObject look = VisLabActions.look(s, a.str("look"));
        String id = VisLabEdit.str(look, "id");
        JsonObject now = VisLabActions.parse(s.currentLookJson());
        // duplicate() copies the look on screen, so show the one asked for first (same undo step).
        if (now == null || !id.equals(VisLabEdit.str(now, "id"))) {
            VisLabSession.ApplyResult shown = s.pickLook(id, VisLabActions.label(), VisLabActions.group());
            if (!shown.ok) return VisLabActions.result(s, shown, "");
        }
        VisLabSession.ApplyResult r = s.duplicate(name, VisLabActions.label(), VisLabActions.group());
        return VisLabActions.result(s, r, "Saved a copy of “" + VisLabEdit.str(look, "name") + "” as “"
                + (r.name == null ? name : r.name) + "”" + where(s) + ".");
    }

    @NonNull
    private static ActionResult rename(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        VisLabSession s = VisLabActions.session(ctx);
        String name = name(a);
        JsonObject look = VisLabActions.look(s, a.str("look"));
        VisLabSession.ApplyResult r = s.rename(VisLabEdit.str(look, "id"), name, VisLabActions.label(), VisLabActions.group());
        return VisLabActions.result(s, r, "Renamed “" + VisLabEdit.str(look, "name") + "” to “"
                + (r.name == null ? name : r.name) + "”" + where(s) + ".");
    }

    @NonNull
    private static ActionResult revert(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        VisLabSession s = VisLabActions.session(ctx);
        JsonObject look = VisLabActions.look(s, a.str("look"));
        String id = VisLabEdit.str(look, "id"), name = VisLabEdit.str(look, "name");
        if (!s.isBuiltIn(id)) {
            throw new ActionError("“" + name + "” is one of their own looks, so it has no version to go back to.");
        }
        if (!s.isCustomised(id)) return ActionResult.unchanged("“" + name + "” is already as it came with the app.");
        askedFirst(a, "revert:" + id, name, "Revert " + name + " to default? This will override any customisations"
                + " you have made to this look. Undo can still bring them back.");
        VisLabSession.ApplyResult r = s.revertToDefault(id, VisLabActions.label(), VisLabActions.group());
        return VisLabActions.result(s, r, "“" + name + "” is back as it came with the app. Undo brings your changes back.");
    }

    @NonNull
    private static ActionResult delete(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        VisLabSession s = VisLabActions.session(ctx);
        JsonObject look = VisLabActions.look(s, a.str("look"));
        String id = VisLabEdit.str(look, "id"), name = VisLabEdit.str(look, "name");
        if (s.isBuiltIn(id)) {
            throw new ActionError("“" + name + "” came with the app, so it can't be deleted — only put back as it came"
                    + " (vislab_revert).");
        }
        askedFirst(a, "delete:" + id, name, "Delete " + name + "? Its file goes to the Trash in your Joy Creator"
                + " folder. Undo brings it back.");
        VisLabSession.ApplyResult r = s.deleteLook(id, VisLabActions.label(), VisLabActions.group());
        return VisLabActions.result(s, r, "Deleted “" + name + "”; its file is in the Trash in the Joy Creator folder."
                + " Undo brings it back.");
    }

    /**
     * Goes on only when the person said yes in a later message than the one where Joybot asked.
     * Otherwise remembers that this request asked, and tells the model to ask and stop.
     */
    private static void askedFirst(@NonNull Args a, @NonNull String key, @NonNull String name,
                                   @NonNull String question) throws ActionError {
        String now = VisLabActions.group();
        String asked = ASKED.get(key);
        boolean yes = Boolean.TRUE.equals(a.bool("confirmed"))
                && ((asked != null && !asked.equals(now)) || answeredYes(name));
        if (yes) {
            ASKED.remove(key);
            return;
        }
        if (now != null) ASKED.put(key, now);
        // First line is what the person sees on the step; the rest is for the model.
        throw new ActionError("Waiting for your yes.\nNot done: the person must say yes first. Ask them exactly this"
                + " and stop: \"" + question + "\" If they say yes, call again with confirmed=true.");
    }

    /** The person's message is a yes, and Joybot's message before it asked about this look. */
    private static boolean answeredYes(@NonNull String lookName) {
        return PersonSaid.yesTo(lookName);
    }

    @NonNull
    private static String name(@NonNull Args a) throws ActionError {
        String n = a.str("name");
        if (n == null || n.trim().isEmpty()) throw new ActionError("What should it be called?");
        return n.trim();
    }

    /** ", kept as Supplies/Looks/X.vis.json" for the look now on screen, once it's written. */
    @NonNull
    private static String where(@NonNull VisLabSession s) {
        s.flush();
        JsonObject now = VisLabActions.parse(s.currentLookJson());
        String file = now == null ? null : s.lookFile(VisLabEdit.str(now, "id"));
        return file == null ? "" : ", kept in your Joy Creator folder as " + file;
    }
}
