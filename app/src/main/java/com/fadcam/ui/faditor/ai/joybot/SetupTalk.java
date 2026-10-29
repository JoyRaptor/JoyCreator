package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Connecting an AI by talking to Joybot (owner, 10-28: "so simple my grandma can use it"). He asks
 * whether you already pay for one, explains the choices good news first, takes the key right in the
 * chat ({@link KeyCatcher}) and checks it before saving. ⚙ stays for power users. These are the
 * words and the decisions; {@code SetupFlow} draws them. Pure Java (tested in IdeaEngineTest).
 *
 * <p>How the $20 plans connect was checked 10-28 (BOT-6 card); those products change often.</p>
 */
public final class SetupTalk {
    private SetupTalk() { }

    /** What the person already has. */
    public enum Path { CLAUDE, CHATGPT, OTHER, FREE }

    /** What the service said about a key. */
    public enum Check { WORKS, WRONG_KEY, NO_CREDIT, OFFLINE, SERVICE_TROUBLE }

    public static final String FREE_KEY_URL = "https://openrouter.ai/keys";
    public static final String ANTHROPIC_KEY_URL = "https://console.anthropic.com/settings/keys";
    public static final String OPENAI_KEY_URL = "https://platform.openai.com/api-keys";

    /** "Connect an AI", "set up my AI", "I have Claude Pro", "how do I add my key". */
    public static boolean asksToConnect(@Nullable String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        // Brand names and "add" alone are too common in edit requests ("add a title that says ChatGPT"),
        // so each pattern needs a connecting verb or "I have / pay for".
        return t.matches(".*\\b(connect|hook up|set ?up|plug in|link)\\b.*\\b(ai|api key|claude|chatgpt|chat gpt|"
                + "openai|open ai|openrouter|gemini|codex)\\b.*")
                || t.matches(".*\\b(add|enter|paste|put in|change|use) (my |an |a |the )?(own )?(api key|ai key|"
                + "openrouter key|ai account)\\b.*")
                || t.matches(".*\\bi (have|pay for|got|ve got|use) (a |an |the |my )?(claude|chatgpt|chat gpt|openai|"
                + "gemini)\\b.*")
                || t.matches(".*\\bi (have|pay for|got|ve got) (a |an |the |my )?ai (plan|subscription|account|key)\\b.*")
                || t.matches("(an )?api key|connect|connect ai|ai setup|set up ai");
    }

    /** The first question. {@code connectedTo}: the account in use, or null when none works yet. */
    @NonNull
    public static String opener(@Nullable String connectedTo) {
        String q = "Let's connect an AI. Do you already pay for one?";
        if (connectedTo == null) return q;
        return "You're connected to " + connectedTo + " now. A new one is added beside it, and you can "
                + "switch any time by tapping the model name at the top.\n\n" + q;
    }

    /** The choices for what they have, good news first. {@code bridgeReady}: the computer door is built. */
    @NonNull
    public static String explain(@NonNull Path p, boolean bridgeReady) {
        switch (p) {
            case FREE:
                return "Good news: OpenRouter has free AI models, and a free account is all it takes. "
                        + "They can be a little slower at busy times, and they're fine for most things.\n\n"
                        + "1. Tap “Get a free key”: it opens OpenRouter in your browser.\n"
                        + "2. Sign in (a Google account works) and tap Create key. Copy it.\n"
                        + "3. Come back and paste it here, in the chat.\n\n" + PROMISE;
            case CLAUDE:
                return plan("Claude", "Claude Code", "an Anthropic API key", bridgeReady);
            case CHATGPT:
                return plan("ChatGPT", "Codex", "an OpenAI API key", bridgeReady);
            case OTHER:
            default:
                return "Paste its key here and I'll work out whose it is: I know OpenRouter, OpenAI, Anthropic, "
                        + "Google Gemini, Groq and xAI. An AI running on your own computer (like Ollama or "
                        + "LM Studio) goes in ⚙ › Address instead.\n\n" + PROMISE;
        }
    }

    private static String plan(String plan, String tool, String key, boolean bridgeReady) {
        String computer = bridgeReady
                ? "Your " + plan + " plan can drive Joy Creator with no key: it works through " + tool
                        + " on your computer. I open a little door on this phone, your computer connects over your "
                        + "Wi-Fi (or a USB cable), and " + tool + " gets all my tools: your video, VisLab, JoyBrush.\n\n"
                : "";
        return computer + "To talk to me right here on the phone:\n"
                + "• the free AI: no cost, a little slower at busy times; or\n"
                + "• " + key + ": pay as you go, separate from your " + plan + " plan.";
    }

    /** Said when Joybot asks for the key itself. */
    public static final String PROMISE = "I'll check the key, then lock it away on this phone. It never goes to "
            + "the AI, and I'll only ever show its last four letters.";

    @NonNull
    public static String askForKey() {
        return "Paste your key here in the chat. " + PROMISE;
    }

    /** A key whose shape names no service. */
    @NonNull
    public static String whoseKey() {
        return "I don't recognise that key's shape. Which service is it from?";
    }

    /** What an HTTP status from the key check means for the person. */
    @NonNull
    public static Check checkOf(int code) {
        if (code >= 200 && code < 300) return Check.WORKS;
        if (code == 401 || code == 403) return Check.WRONG_KEY;
        if (code == 402) return Check.NO_CREDIT;
        return Check.SERVICE_TROUBLE;
    }

    /** What Joybot says after checking a key with {@code service}. */
    @NonNull
    public static String afterCheck(@NonNull Check c, @NonNull String service) {
        switch (c) {
            case WORKS:
                return "✓ That key works. You're connected to " + service + ".";
            case WRONG_KEY:
                return service + " says that key isn't right. Check you copied all of it (keys are long), "
                        + "then paste it again. Nothing was saved.";
            case NO_CREDIT:
                return "That key is real, but the " + service + " account has no credit yet. Add some on their "
                        + "site, or use the free AI. Nothing was saved.";
            case OFFLINE:
                return "I couldn't reach " + service + " to check it: your phone may be offline. Paste it again "
                        + "when you're back online. Nothing was saved.";
            case SERVICE_TROUBLE:
            default:
                return service + " didn't answer properly just now (it may be busy). Paste it again in a "
                        + "minute. Nothing was saved.";
        }
    }

    /** The last line once connected: something to try. */
    @NonNull
    public static String firstTry(boolean inProject) {
        return inProject
                ? "Ask me anything. Try “make a title that says Hello”."
                : "Ask me anything. Try “what can I make here?”.";
    }
}
