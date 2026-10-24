package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.AIChatState;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the PERSON said in the request now running — not what the model decided. A model can set
 * any flag ("confirmed", "locked: false") itself; it can't make the person type the words. Used
 * where the owner's rule is "only when they say so": deleting or reverting a look, unlocking.
 * A free model unlocked a locked title on its own and moved it (Note 9, 2026-10-23).
 */
public final class PersonSaid {
    private PersonSaid() { }

    private static final Pattern YES = Pattern.compile(
            "(?i)^\\W*(yes|yeah|yep|yup|y|ok|okay|sure|go ahead|do it|please do|confirm(ed)?|delete it|revert it|unlock it)\\b.*");

    /** The person's own words for this request contain {@code words} (a regex, case-insensitive). */
    public static boolean asked(@NonNull String words) {
        String said = AIChatState.liveAsk;
        return said != null && Pattern.compile("(?i)" + words).matcher(said).find();
    }

    /**
     * The person answered yes, and Joybot's message just before asked about {@code topic}
     * (both case-insensitive): "Unlock it? … " → "yes".
     */
    public static boolean yesTo(@Nullable String topic) {
        String said = AIChatState.liveAsk, before = AIChatState.liveReplyBefore;
        if (said == null || before == null || topic == null || topic.isEmpty()) return false;
        return YES.matcher(said.trim()).matches()
                && before.toLowerCase(Locale.ROOT).contains(topic.toLowerCase(Locale.ROOT));
    }
}
