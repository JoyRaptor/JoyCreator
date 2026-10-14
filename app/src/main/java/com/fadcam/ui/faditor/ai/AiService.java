package com.fadcam.ui.faditor.ai;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fadcam.SharedPreferencesManager;

/**
 * Which AI service the assistant talks to: OpenRouter by default, or any server that speaks the
 * same OpenAI-style chat API at an address the user types (roadmap 3.8: "OpenRouter and a custom
 * URL box"). One place for the address, so the chat and every tool use the same service.
 *
 * <p>A custom server may need no key (a model running on the phone, say), so "connected" means a
 * key is set OR a custom address is. Android's network rules here allow plain http only to the
 * phone itself; anything else must be https.</p>
 */
public final class AiService {

    public static final String DEFAULT_BASE = "https://openrouter.ai/api/v1";
    private static final String PREF_BASE = "ai_base_url";

    private AiService() {}

    /** The service's base address, without a trailing slash. */
    @NonNull
    public static String base(@NonNull Context context) {
        String b = SharedPreferencesManager.getInstance(context).sharedPreferences.getString(PREF_BASE, "");
        return b == null || b.isEmpty() ? DEFAULT_BASE : b;
    }

    @NonNull
    public static String chatUrl(@NonNull Context context) {
        return base(context) + "/chat/completions";
    }

    /** True when the user pointed the assistant somewhere other than OpenRouter. */
    public static boolean isCustom(@NonNull Context context) {
        return !DEFAULT_BASE.equals(base(context));
    }

    /** Enough to make a call: a key, or a custom server that may not need one. */
    public static boolean ready(@NonNull Context context, String key) {
        return (key != null && !key.isEmpty()) || isCustom(context);
    }

    /**
     * Save the address as typed; empty goes back to OpenRouter. A pasted full endpoint
     * ("…/v1/chat/completions") is trimmed to its base.
     */
    public static void setBase(@NonNull Context context, @NonNull String typed) {
        String b = typed.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) b = b.substring(0, b.length() - "/chat/completions".length());
        if (b.equals(DEFAULT_BASE)) b = "";
        SharedPreferencesManager.getInstance(context).sharedPreferences.edit().putString(PREF_BASE, b).apply();
    }
}
