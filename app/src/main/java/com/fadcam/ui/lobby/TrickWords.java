package com.fadcam.ui.lobby;

import androidx.annotation.Nullable;

/**
 * The words that ask Joybot for a trick, offline (owner, 11-15): "go to sleep", "can you hop on one foot?", "roll over
 * please". Only the whole message as a command counts, so "trim the sleep scene" is not a trick. Pure Java, so the
 * harness checks it (IdeaEngineTest); {@link JoybotDirector#trickFor} answers with it.
 */
public final class TrickWords {
    private TrickWords() { }

    /** The trick name ({@link JoybotDirector#TRICKS}) a short message asks for, or null. */
    @Nullable
    public static String trickFor(@Nullable String text) {
        if (text == null) return null;
        String t = text.toLowerCase(java.util.Locale.ROOT).replace('’', '\'').replaceAll("[^a-z' ]", " ")
                .replaceAll("\\s+", " ").trim();
        t = t.replaceFirst("^(hey )?(joybot )?(please )?(can you |could you |will you |would you |now )?(please )?", "");
        t = t.replaceFirst("( for me)?( please)?( joybot)?( now)?$", "").trim();
        if (t.matches("(go to sleep|go to bed|take a nap|have a nap|nap|nap time|sleep|bedtime|bed time|go night night|night night|nighty night|time for bed|time for a nap|sleepy time)")) return "sleep";
        if (t.matches("(do a |do the )?(hop|hop on one foot|hop around|hop hop|bunny hop|jump|jump around)")) return "hop";
        if (t.matches("(do a |do another |do the )?(backflip|back flip|flip|somersault)")) return "backflip";
        if (t.matches("(beg|do the beg|say please|puppy eyes|do puppy eyes)")) return "beg";
        if (t.matches("(roll over|roll|do a roll|barrel roll|do a barrel roll)")) return "roll-over";
        if (t.matches("(shake|shake it|shake it off|shake yourself|do a shake|shake shake)")) return "shake";
        return null;
    }
}
