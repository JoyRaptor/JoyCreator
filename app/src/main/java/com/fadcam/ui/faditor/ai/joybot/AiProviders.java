package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Which AI service a pasted key belongs to, from the key's own shape — so a person with an
 * OpenAI or Anthropic subscription pastes the key and Joybot fills in the address, instead of
 * them having to know what "https://api.openai.com/v1" is. Every service here speaks the same
 * OpenAI-style chat API Joybot uses. Pure Java (tested off device in IdeaEngineTest).
 */
public final class AiProviders {

    private AiProviders() {}

    /** One service. {@code base} "" means OpenRouter, the app's default. */
    public static final class Provider {
        @NonNull public final String name;
        @NonNull public final String base;
        /** The model to start on, or "" when the person should pick from their own list. */
        @NonNull public final String startModel;

        Provider(@NonNull String name, @NonNull String base, @NonNull String startModel) {
            this.name = name;
            this.base = base;
            this.startModel = startModel;
        }

        public boolean isOpenRouter() { return base.isEmpty(); }
    }

    public static final Provider OPENROUTER = new Provider("OpenRouter", "", "openrouter/free");
    public static final Provider OPENAI = new Provider("OpenAI", "https://api.openai.com/v1", "");
    public static final Provider ANTHROPIC = new Provider("Anthropic", "https://api.anthropic.com/v1", "");
    public static final Provider GOOGLE = new Provider("Google Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai", "");
    public static final Provider GROQ = new Provider("Groq", "https://api.groq.com/openai/v1", "");
    public static final Provider XAI = new Provider("xAI", "https://api.x.ai/v1", "");

    private static final Provider[] KNOWN = {OPENROUTER, OPENAI, ANTHROPIC, GOOGLE, GROQ, XAI};

    /** The service a key belongs to, or null when its shape says nothing. */
    @Nullable
    public static Provider fromKey(@Nullable String key) {
        if (key == null) return null;
        String k = key.trim();
        if (k.startsWith("sk-or-")) return OPENROUTER;
        if (k.startsWith("sk-ant-")) return ANTHROPIC;
        if (k.startsWith("AIza")) return GOOGLE;
        if (k.startsWith("gsk_")) return GROQ;
        if (k.startsWith("xai-")) return XAI;
        if (k.startsWith("sk-")) return OPENAI;   // sk-proj-, sk-svcacct-, and the old sk-
        return null;
    }

    /** A name for whatever service lives at {@code base} ("" = OpenRouter). */
    @NonNull
    public static String nameForBase(@Nullable String base) {
        String b = base == null ? "" : base.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        for (Provider p : KNOWN) if (p.base.equalsIgnoreCase(b)) return p.name;
        if (b.contains("openrouter.ai")) return OPENROUTER.name;
        if (b.contains("localhost") || b.contains("127.0.0.1")) return "On this phone";
        String host = b.replaceFirst("^[a-zA-Z]+://", "");
        int slash = host.indexOf('/');
        if (slash > 0) host = host.substring(0, slash);
        return host.isEmpty() ? "Custom" : host;
    }

    /**
     * Whether a model id from a service's own /models list is for chat. OpenAI's list also holds
     * speech, embedding, image and moderation models, which can't run Joybot.
     */
    public static boolean isChatModel(@NonNull String id) {
        String l = id.toLowerCase(Locale.US);
        String[] notChat = {"embedding", "whisper", "tts", "dall-e", "moderation", "transcribe",
                "realtime", "audio", "image", "search", "davinci", "babbage", "computer-use", "sora"};
        for (String n : notChat) if (l.contains(n)) return false;
        return true;
    }
}
