package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An AI key typed or pasted into Joybot's chat (owner, 10-28: "you could enter the API key into the
 * chat itself, and that would be protected"). It is caught on the phone before anything else sees
 * the message: never sent to the AI, never kept in the conversation, the message log or any file.
 * The bubble shows only "key ending …1a2b". Pure Java (tested in IdeaEngineTest, fake keys only).
 */
public final class KeyCatcher {
    private KeyCatcher() { }

    /** A caught key and what the chat may keep of the message instead. */
    public static final class Found {
        /** The key, cleaned of spaces and line breaks. Hand it to {@code AiKey} only. */
        @NonNull public final String key;
        /** Whose key its shape says it is, or null when the shape says nothing. */
        @Nullable public final AiProviders.Provider provider;
        /** The message with the key masked: the only form the chat shows or remembers. */
        @NonNull public final String shown;

        Found(@NonNull String key, @Nullable AiProviders.Provider provider, @NonNull String shown) {
            this.key = key;
            this.provider = provider;
            this.shown = shown;
        }
    }

    // A known prefix (see AiProviders.fromKey), then enough key characters to be a real key.
    private static final String KNOWN = "(?:sk-or-|sk-ant-|sk-|AIza|gsk_|xai-)[A-Za-z0-9_-]{20,}";
    private static final Pattern KNOWN_IN_TEXT = Pattern.compile("(?<![A-Za-z0-9_-])" + KNOWN + "(?![A-Za-z0-9_-])");
    private static final Pattern KNOWN_WHOLE = Pattern.compile(KNOWN);
    // While Joybot is waiting for a key: any long unbroken run of key characters with letters and digits.
    private static final Pattern ANY_IN_TEXT = Pattern.compile("(?<![A-Za-z0-9_./:-])[A-Za-z0-9_-]{24,}(?![A-Za-z0-9_./:-])");

    /**
     * The key in {@code text}, or null. A known shape is caught any time; while Joybot has just asked
     * for a key ({@code expectingKey}) any long key-like run counts too.
     */
    @Nullable
    public static Found find(@Nullable String text, boolean expectingKey) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        // The whole message is one key, maybe broken across lines by the copy (an OpenAI key arrived
        // with a line break in the middle on the Note 9, 10-15).
        String compact = t.replaceAll("\\s+", "");
        if (KNOWN_WHOLE.matcher(compact).matches()) {
            return new Found(compact, AiProviders.fromKey(compact), shown(compact));
        }
        Matcher m = KNOWN_IN_TEXT.matcher(t);
        if (m.find()) return around(t, m);
        if (expectingKey) {
            if (!t.contains(" ") && compact.length() >= 24 && looksLikeKey(compact)
                    && compact.matches("[A-Za-z0-9_-]+")) {
                return new Found(compact, AiProviders.fromKey(compact), shown(compact));
            }
            Matcher a = ANY_IN_TEXT.matcher(t);
            while (a.find()) {
                if (looksLikeKey(a.group())) return around(t, a);
            }
        }
        return null;
    }

    /** "key ending …1a2b": all the chat ever shows of a key. */
    @NonNull
    public static String shown(@NonNull String key) {
        return "🔑 key ending …" + (key.length() <= 4 ? "" : key.substring(key.length() - 4));
    }

    @NonNull
    private static Found around(@NonNull String text, @NonNull Matcher m) {
        String key = m.group();
        String rest = (text.substring(0, m.start()) + shown(key) + text.substring(m.end())).trim();
        return new Found(key, AiProviders.fromKey(key), rest);
    }

    private static boolean looksLikeKey(@NonNull String s) {
        boolean letter = false, digit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) digit = true;
            else if (Character.isLetter(c)) letter = true;
        }
        return letter && digit;
    }
}
