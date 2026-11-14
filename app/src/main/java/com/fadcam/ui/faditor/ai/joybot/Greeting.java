package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Joybot's first words on an empty chat, with and without an AI (audit BOT-A10). The owner drafted
 * both on 9-09 (tasks/INBOX.md): with no AI, what he does free on the phone with no account and
 * how to give him more; with one, who he is talking to and that costs are the provider's alone —
 * "Joy Creator never adds a markup and never sees your bill", and on a paid model "keep an eye on
 * your usage". Pure Java, tested in IdeaEngineTest.
 */
public final class Greeting {
    private Greeting() { }

    /** A model that costs nothing (OpenRouter's ":free" models and its free router). */
    public static boolean freeModel(@Nullable String model) {
        if (model == null) return false;
        String m = model.trim().toLowerCase(java.util.Locale.US);
        return m.endsWith(":free") || m.equals("openrouter/free");
    }

    /** No AI connected. {@code did} is the "Did you know…" sentence. */
    @NonNull
    public static String offline(@NonNull String did) {
        return "Hi, I'm Joybot. " + did + "\n\nRight here on the phone, free and with no account, I can transcribe, "
                + "add captions, cut the ums and silences, add transitions and make slides — tap a button below. "
                + "Connect an AI (there's a free one) and I can take on anything you can describe: tap "
                + "“Connect an AI” and I'll walk you through it.";
    }

    /** An AI connected: {@code account} as the person named it, {@code model} the model id (may be empty). */
    @NonNull
    public static String connected(@NonNull String did, @Nullable String account, @Nullable String model) {
        StringBuilder b = new StringBuilder("Hi, I'm Joybot. ").append(did).append("\n\n");
        String who = account == null || account.trim().isEmpty() ? null : account.trim();
        String mdl = model == null || model.trim().isEmpty() ? null : shortModel(model.trim());
        if (who != null || mdl != null) {
            b.append("Connected to ").append(who != null ? who : mdl);
            if (who != null && mdl != null) b.append(" (").append(mdl).append(')');
            b.append(". ");
        }
        b.append("Tap a button below, or tell me what you'd like to make. Prompts and pictures cost whatever your "
                + "AI service charges: Joy Creator never adds a markup and never sees your bill.");
        if (!freeModel(model)) b.append(" You're not on a free model, so keep an eye on your usage.");
        return b.toString();
    }

    /** "google/gemma-4-31b-it:free" → "gemma-4-31b-it:free": the provider prefix is noise to a person. */
    @NonNull
    static String shortModel(@NonNull String id) {
        int slash = id.lastIndexOf('/');
        return slash >= 0 && slash < id.length() - 1 && !id.equals("openrouter/free") ? id.substring(slash + 1) : id;
    }
}
