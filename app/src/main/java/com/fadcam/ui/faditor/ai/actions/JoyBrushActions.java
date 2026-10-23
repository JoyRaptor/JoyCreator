package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Type;

import java.util.ArrayList;
import java.util.List;

import cc.joycreator.joybrush.android.HandsException;
import cc.joycreator.joybrush.android.JoybotHands;

/**
 * JoyBrush, the paint and animation room, as actions (app-wide host). They act on the drawing that is open
 * on screen, through {@link JoybotHands} (the same canvas calls the buttons use), so a layer or stroke made
 * here is one the person could have made by hand. Every change a request makes is one Undo press
 * (the request token is the canvas's undo group). Deleting a layer or frame needs the person's own yes.
 */
final class JoyBrushActions {
    private JoyBrushActions() { }

    private static final String FAMILY = "joybrush";

    static void register(@NonNull ActionRegistry<AppCtx> r) {
        r.add(ActionSpec.of("joybrush_drawings", "List the drawings")
                .help("Lists the person's JoyBrush drawings (newest first) and says which is open now.")
                .wing("APP", FAMILY).effect(Effect.READ)
                .where("Joy Brush › Drawings")
                .build(), JoyBrushActions::drawings);

        r.add(ActionSpec.of("joybrush_describe", "Read the open drawing")
                .help("Reads the open JoyBrush drawing in words: size, its layers (number, name, shown or hidden, opacity,"
                        + " which one is being painted on), its animation boards and frames, and the brush, size and colour"
                        + " in hand. Call it before changing anything.")
                .wing("APP", FAMILY).effect(Effect.READ)
                .where("Joy Brush › Layers")
                .build(), JoyBrushActions::describe);

        r.add(ActionSpec.of("joybrush_layers", "Change layers")
                .help("Changes the open drawing's layers. lines, one command each (a whole request is one Undo):"
                        + " \"add <name>\", \"rename <layer> to <name>\", \"hide <layer>\", \"show <layer>\", \"lock <layer>\","
                        + " \"unlock <layer>\", \"select <layer>\" (paint on it), \"opacity <layer> <0-100>\","
                        + " \"move <layer> to <number|top|bottom>\", \"duplicate <layer>\", \"delete <layer>\"."
                        + " <layer> is a number (1 = bottom), a name, or top / bottom / active. Deleting needs the person's yes:"
                        + " ASK FIRST (name the layer) and wait; call again with confirmed=true only after they say yes.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("Joy Brush › Layers")
                .param("lines", Type.STRING_LIST, true, "Lines like \"add Sketch\" or \"opacity 2 40\"")
                .flag("confirmed", false, "true once the person said yes to a delete")
                .build(), JoyBrushActions::layers);

        r.add(ActionSpec.of("joybrush_frames", "Change animation frames")
                .help("Animation frames of the drawing's animation board. lines, one command each: \"add blank\" (an empty frame"
                        + " after the current one), \"add copy\" (a duplicate of it), \"add link\", \"select <n>\" (go to frame n),"
                        + " \"delete [n]\" (default: the current frame), \"new board <name>\" (make an animation board when the"
                        + " drawing has none). Deleting needs the person's yes: ASK FIRST and wait; call again with"
                        + " confirmed=true only after they say yes.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("Joy Brush › the animation board's frame strip")
                .param("lines", Type.STRING_LIST, true, "Lines like \"add copy\" or \"select 3\"")
                .flag("confirmed", false, "true once the person said yes to a delete")
                .build(), JoyBrushActions::frames);

        r.add(ActionSpec.of("joybrush_brush", "Set brush, colour, size")
                .help("Picks what the person paints with: brush (name or id; unknown name lists the brushes), colour (#RRGGBB),"
                        + " size in pixels, opacity 1-100. Give any of them.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("Joy Brush › the tool strip")
                .text("brush", false, "A brush's name or id")
                .color("color", false, "Paint colour, #RRGGBB")
                .number("size", false, "Brush size in pixels", 0.5, 2000.0)
                .number("opacity", false, "Opacity 1-100 (percent)", 1.0, 100.0)
                .build(), JoyBrushActions::brush);

        r.add(ActionSpec.of("joybrush_draw", "Draw shapes")
                .help("Draws strokes with the brush and colour in hand, on the layer being painted on (or layer). shapes, one stroke"
                        + " each, coordinates are PERCENT of the page (0,0 top-left; 100,100 bottom-right):"
                        + " \"line x1,y1 x2,y2\", \"rect x,y width,height\", \"ellipse cx,cy rx,ry\" (\"circle cx,cy r\"),"
                        + " \"polyline x,y x,y …\", \"polygon x,y x,y …\" (closed). They are real strokes, so Undo removes them."
                        + " Set the colour first with joybrush_brush. This is for simple shapes and guides, not for a finished painting.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("Joy Brush › the canvas")
                .param("shapes", Type.STRING_LIST, true, "Lines like \"circle 50,50 20\" or \"line 10,10 90,10\"")
                .text("layer", false, "Layer to paint on (number or name) instead of the current one")
                .build(), JoyBrushActions::draw);
    }

    // ------------------------------------------------------------------ handlers

    @NonNull
    private static ActionResult drawings(@NonNull AppCtx ctx, @NonNull Args a) {
        return ActionResult.unchanged(JoybotHands.drawings(ctx.app));
    }

    @NonNull
    private static ActionResult describe(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        try {
            return ActionResult.unchanged(JoybotHands.describe());
        } catch (HandsException e) {
            throw new ActionError(e.getMessage());
        }
    }

    @NonNull
    private static ActionResult layers(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        List<String> lines = a.list("lines");
        if (lines.isEmpty()) throw new ActionError("Give lines like \"add Sketch\".");
        List<String> deletes = JoybotHands.deletes(lines);
        if (!deletes.isEmpty()) {
            String names = JoybotHands.deleteNames(lines);
            VisLabLookFiles.askedFirst(a, "jb-layers:" + String.join("|", deletes), names, "Delete " + names
                    + "? Undo brings it back.");
        }
        return run(() -> JoybotHands.layers(lines, token()));
    }

    @NonNull
    private static ActionResult frames(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        List<String> lines = a.list("lines");
        if (lines.isEmpty()) throw new ActionError("Give lines like \"add copy\".");
        List<String> deletes = JoybotHands.deletes(lines);
        if (!deletes.isEmpty()) {
            String what = JoybotHands.deleteFrameName(lines);
            VisLabLookFiles.askedFirst(a, "jb-frames:" + String.join("|", deletes), what, "Delete " + what
                    + "? Undo brings it back.");
        }
        return run(() -> JoybotHands.frames(lines, token()));
    }

    @NonNull
    private static ActionResult brush(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        Integer color = a.color("color");
        return run(() -> JoybotHands.brush(a.str("brush"), color, a.num("size"), a.num("opacity"), token()));
    }

    @NonNull
    private static ActionResult draw(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        List<String> shapes = a.list("shapes");
        if (shapes.isEmpty()) throw new ActionError("Give shapes like \"circle 50,50 20\".");
        return run(() -> JoybotHands.draw(shapes, a.str("layer"), token()));
    }

    // ------------------------------------------------------------------ helpers

    private interface Hands { String go() throws HandsException; }

    @NonNull
    private static ActionResult run(@NonNull Hands h) throws ActionError {
        try {
            return ActionResult.changed(h.go(), new ArrayList<>());
        } catch (HandsException e) {
            throw new ActionError(e.getMessage());
        }
    }

    /** The request this change belongs to: changes in one request merge into one Undo press. */
    @Nullable
    private static String token() {
        String g = AIChatState.liveWriteRequest;
        return g == null || g.isEmpty() ? null : "joybot:" + g;
    }
}
