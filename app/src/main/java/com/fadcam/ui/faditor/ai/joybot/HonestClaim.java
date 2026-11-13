package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Joybot's "done" checked against what really happened in one request (BOT-3, audit BOT-A2).
 *
 * <p>The agent already refuses a "Done!" when every step failed and nothing changed. Two holes were
 * left: a claim worded without the old tell-tale words ("The first clip is called Intro now." after a
 * rename that failed) slipped through, and when ONE step of several failed, a reply claiming all of
 * it was shown as it was, because something else had changed. This class owns both decisions. Pure
 * Java, so the wording rules are tested off the phone (IdeaEngineTest).</p>
 */
public final class HonestClaim {
    private HonestClaim() { }

    /** Words that say the work changed ("Done", "I've set…", "is called Intro now"). */
    private static final String[] TELLS = {"done", "i've ", "i have ", "has been", "have been", "will end",
            "now ends", "will start", "now starts", "is now", "are now", "set the", "added", "removed",
            "trimmed", "moved", "applied", "updated", "changed",
            // 11-13: claims the list above missed (bench BOT-A2)
            "is called", "are called", "now called", "renamed", "named it", "named the", "faded", "muted",
            "unmuted", "deleted", "split ", "cut the", "cut out", "put the", "turned the", "made the", "made it",
            "now reads", "now says", "now plays", "now shows", "it's now", "they're now", "all set"};

    /** Words that own up to a step that did not work: the reply is already honest. */
    private static final String[] ADMITS = {"didn't work", "did not work", "couldn't", "could not", "failed",
            "wasn't able", "was not able", "didn't happen", "did not happen", "not everything", "except",
            "but the ", "however", "error"};

    /** A reply that says the person's work changed. */
    public static boolean claims(@NonNull String reply) {
        String l = reply.toLowerCase(Locale.US).replace('’', '\'');
        for (String w : TELLS) if (l.contains(w)) return true;
        return false;
    }

    /** A reply that already says some of it didn't work (or plainly can't be done). */
    public static boolean admits(@NonNull String reply) {
        if (HelpBook.saysItCant(reply)) return true;
        String l = reply.toLowerCase(Locale.US).replace('’', '\'');
        for (String w : ADMITS) if (l.contains(w)) return true;
        return false;
    }

    /**
     * The steps of one request that failed and were not later made good: a tool that failed and
     * then ran again successfully counts as done. Keyed by tool name, oldest first.
     */
    public static final class Ledger {
        private final Map<String, String> failed = new LinkedHashMap<>();
        private int wrote;

        /** One tool finished: {@code error} is its failure text, or null when it worked. */
        public void ran(@NonNull String tool, @Nullable String error, boolean changedWork) {
            if (error != null) {
                failed.put(tool, error);
            } else {
                failed.remove(tool);
                if (changedWork) wrote++;
            }
        }

        public int wrote() { return wrote; }
        public boolean anyFailed() { return !failed.isEmpty(); }
    }

    /**
     * What to add to a reply that claims more than happened, or null when it is honest as it is.
     * Only for the mixed case: some steps changed the work and some failed. (Nothing changed at
     * all is the agent's own "That didn't work, so nothing changed" path.)
     */
    @Nullable
    public static String note(@NonNull String reply, @NonNull Ledger ledger) {
        if (!ledger.anyFailed() || ledger.wrote == 0) return null;
        if (!claims(reply) || admits(reply)) return null;
        StringBuilder b = new StringBuilder("Not all of that happened: ");
        int n = 0;
        for (Map.Entry<String, String> e : ledger.failed.entrySet()) {
            if (n++ > 0) b.append("; ");
            b.append('“').append(e.getKey().replace('_', ' ')).append("” didn't work (")
                    .append(shortError(e.getValue())).append(')');
        }
        b.append(". ").append(ledger.wrote == 1 ? "The other change did go through."
                : "The other " + ledger.wrote + " changes did go through.");
        return b.toString();
    }

    @NonNull
    static String shortError(@NonNull String e) {
        String t = e.replaceFirst("^\\s*Error:\\s*", "").trim();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        if (t.length() > 90) t = t.substring(0, 87).trim() + "…";
        if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t.isEmpty() ? "no reason given" : t;
    }
}
