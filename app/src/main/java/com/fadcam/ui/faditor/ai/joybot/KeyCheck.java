package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Does a key work? One free call to the service before the key is saved, so the person hears
 * "it works" or exactly what's wrong while they're still in the chat. Never logs the key.
 */
public final class KeyCheck {
    private KeyCheck() { }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    /** Ask the service about this key. Costs nothing: no model runs. */
    @WorkerThread
    @NonNull
    public static SetupTalk.Check check(@NonNull String key, @NonNull AiProviders.Provider p) {
        // OpenRouter answers "whose key is this" at /key; the others list their models for a good key.
        String url = p.isOpenRouter() ? "https://openrouter.ai/api/v1/key" : p.base + "/models";
        try {
            Request.Builder rb = new Request.Builder().url(url).get()
                    .addHeader("Authorization", "Bearer " + key);
            if (p == AiProviders.ANTHROPIC) {
                rb.addHeader("x-api-key", key).addHeader("anthropic-version", "2023-06-01");
            }
            try (Response r = HTTP.newCall(rb.build()).execute()) {
                return SetupTalk.checkOf(r.code());
            }
        } catch (IOException e) {
            return SetupTalk.Check.OFFLINE;
        } catch (Exception e) {
            // A malformed key can make the header itself invalid: a wrong key, never a crash.
            return SetupTalk.Check.WRONG_KEY;
        }
    }
}
