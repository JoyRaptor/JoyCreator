package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Connecting an AI as a conversation in Joybot's chat ({@link SetupTalk} has the words). Holds a
 * caught key only in memory, only until it is checked: then it is saved through the host or dropped.
 */
public final class SetupFlow {

    /** What the chat screen does for the flow. */
    public interface Host {
        @NonNull Context context();
        void say(@NonNull String text);
        void show(@NonNull View card);
        void openUrl(@NonNull String url);
        /** Point the typing box at a key ("Paste your key here"). */
        void readyForKey(boolean on);
        /** Save the checked key as an account and switch to it. */
        void connect(@NonNull String key, @NonNull AiProviders.Provider p);
        /** The account in use, when one works now; else null. */
        @Nullable String connectedTo();
        /** Whether the computer door (BOT-6 bridge) is built into this app. */
        boolean bridgeReady();
        void openBridge();
    }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean expectingKey;
    /** A caught key whose service its shape didn't name, waiting for the person's pick. */
    @Nullable private String unplacedKey;

    public SetupFlow(@NonNull Host host) {
        this.host = host;
    }

    /** True right after Joybot asked for a key: any long key-like text then counts as one. */
    public boolean expectingKey() {
        return expectingKey;
    }

    /** "Connect an AI": the first question. */
    public void start() {
        Context c = host.context();
        JoybotChatViews.Card card = JoybotChatViews.resultCard(c, SetupTalk.opener(host.connectedTo()), null);
        card.buttons.addView(button(c, "Claude", "I pay for Claude", v -> choose(SetupTalk.Path.CLAUDE)));
        card.buttons.addView(button(c, "ChatGPT", "I pay for ChatGPT", v -> choose(SetupTalk.Path.CHATGPT)));
        card.buttons.addView(button(c, "Another", "Another AI service", v -> choose(SetupTalk.Path.OTHER)));
        card.buttons.addView(button(c, "No, free", "No: set up the free one", v -> choose(SetupTalk.Path.FREE)));
        wrap(card);
        host.show(card.root);
    }

    private void choose(@NonNull SetupTalk.Path p) {
        Context c = host.context();
        boolean bridge = host.bridgeReady();
        JoybotChatViews.Card card = JoybotChatViews.resultCard(c, SetupTalk.explain(p, bridge), null);
        switch (p) {
            case FREE:
                card.buttons.addView(button(c, "Get a free key", "Opens OpenRouter in your browser",
                        v -> { host.openUrl(SetupTalk.FREE_KEY_URL); askForKey(false); }));
                card.buttons.addView(button(c, "I've got it", "Paste it in the chat", v -> askForKey(true)));
                break;
            case CLAUDE:
            case CHATGPT:
                if (bridge) {
                    card.buttons.addView(button(c, "Connect my computer",
                            p == SetupTalk.Path.CLAUDE ? "Claude Code on your computer gets my tools"
                                    : "Codex on your computer gets my tools", v -> host.openBridge()));
                }
                card.buttons.addView(button(c, "Free one", "Set up the free AI", v -> choose(SetupTalk.Path.FREE)));
                card.buttons.addView(button(c, "API key", "I have an API key",
                        v -> {
                            host.openUrl(p == SetupTalk.Path.CLAUDE ? SetupTalk.ANTHROPIC_KEY_URL : SetupTalk.OPENAI_KEY_URL);
                            askForKey(false);
                        }));
                break;
            case OTHER:
            default:
                askForKey(false);
                break;
        }
        wrap(card);
        host.show(card.root);
    }

    /** Joybot asks for the key; the typing box says so. {@code say}: also say it in words. */
    private void askForKey(boolean say) {
        expectingKey = true;
        host.readyForKey(true);
        if (say) host.say(SetupTalk.askForKey());
    }

    /** A key was caught in the chat (already masked in the bubble). Check it, then save or drop it. */
    public void gotKey(@NonNull KeyCatcher.Found found) {
        expectingKey = false;
        host.readyForKey(false);
        if (found.provider == null) {
            unplacedKey = found.key;
            Context c = host.context();
            JoybotChatViews.Card card = JoybotChatViews.resultCard(c, SetupTalk.whoseKey(), null);
            for (AiProviders.Provider p : new AiProviders.Provider[]{AiProviders.OPENROUTER, AiProviders.OPENAI,
                    AiProviders.ANTHROPIC, AiProviders.GOOGLE, AiProviders.GROQ, AiProviders.XAI}) {
                card.buttons.addView(button(c, p.name, "It's a " + p.name + " key", v -> {
                    String k = unplacedKey;
                    unplacedKey = null;
                    if (k != null) check(k, p);
                }));
            }
            wrap(card);
            host.show(card.root);
            return;
        }
        check(found.key, found.provider);
    }

    private void check(@NonNull String key, @NonNull AiProviders.Provider p) {
        host.say("Checking it with " + p.name + "…");
        new Thread(() -> {
            SetupTalk.Check r = KeyCheck.check(key, p);
            main.post(() -> {
                if (r == SetupTalk.Check.WORKS) {
                    host.say(SetupTalk.afterCheck(r, p.name));
                    host.connect(key, p);
                } else {
                    host.say(SetupTalk.afterCheck(r, p.name));
                    if (r != SetupTalk.Check.NO_CREDIT) askForKey(false);
                }
            });
        }, "joybot-key-check").start();
    }

    @NonNull
    private static View button(@NonNull Context c, @NonNull String text, @NonNull String hint,
                               @NonNull View.OnClickListener l) {
        return JoybotChatViews.cardButton(c, text, hint, l);
    }

    /** Four buttons don't fit one row on a phone: let the row wrap. */
    private static void wrap(@NonNull JoybotChatViews.Card card) {
        JoybotChatViews.wrapButtons(card);
    }
}
