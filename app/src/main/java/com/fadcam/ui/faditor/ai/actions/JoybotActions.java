package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;

import java.util.Arrays;
import java.util.List;

/**
 * Joybot's own tricks for the model (BOT-7, owner 11-15): "show me a trick", "can you dance for me?". Typed trick
 * commands are done offline by the chat already ({@code JoybotDirector.trickFor}); this is the model's way to the
 * same tricks. The action only names one; the chat plays it once the reply is on screen
 * ({@link AppActions#takePendingTrick}), so his working pose doesn't cut it short. Changes nothing.
 */
final class JoybotActions {
    private JoybotActions() { }

    /** {@code com.fadcam.ui.lobby.JoybotDirector.TRICKS}, spelt out so this pure list needs no Android view class. */
    static final List<String> TRICKS = Arrays.asList("sleep", "hop", "backflip", "beg", "roll-over", "shake");

    static void register(@NonNull ActionRegistry<AppCtx> r) {
        r.add(ActionSpec.of("do_trick", "Joybot does a trick")
                .help("You (Joybot) do a trick in the chat, right after your reply: sleep (curls up, a tap wakes him),"
                        + " hop (on one foot), backflip, beg, roll-over, shake (a buzzing shake). Use it when the person"
                        + " asks for a trick or to play (\"show me a trick\", \"do something fun\"), or once to celebrate"
                        + " a finished job they're happy with. Never instead of their task. Say it in your reply too.")
                .wing("APP", "joybot").effect(Effect.READ)
                .where("Joybot › tap him, or type \"do a backflip\"")
                .choice("name", true, "Which trick", TRICKS)
                .build(), JoybotActions::trick);
    }

    @NonNull
    private static ActionResult trick(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        String name = a.choice("name", TRICKS);
        if (name == null) throw new ActionError("do_trick needs a name: " + TRICKS + ".");
        ctx.trick = name;
        return ActionResult.unchanged("Doing a " + name.replace('-', ' ') + " once the reply is shown.");
    }
}
