package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.ai.AiService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * One non-streaming chat completion for any AI call site outside the Joybot agent.
 *
 * <p>Services differ in what they accept: OpenAI's newer models want {@code max_completion_tokens}
 * and refuse a custom {@code temperature}. When a 400/404/422 names one of those settings, this
 * drops or swaps it, retries, and remembers the answer for that address (the same rules as the
 * agent's Quirks, kept separate so the agent stays untouched).
 */
public final class AiRequest {

    private static final String TAG = "AiRequest";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient BASE = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    /** What a service at one address accepts, learned from its refusals and kept while the app runs. */
    private static final class Quirks {
        volatile boolean completionTokens, temperature = true;

        /** Change what this error is about; false when it is about none of ours. */
        boolean adapt(@NonNull String err) {
            String l = err.toLowerCase(Locale.US);
            if (!completionTokens && l.contains("max_tokens")) { completionTokens = true; return true; }
            if (temperature && l.contains("temperature")) { temperature = false; return true; }
            return false;
        }
    }

    private static final ConcurrentHashMap<String, Quirks> QUIRKS = new ConcurrentHashMap<>();

    /** The service answered with an error status; {@link #code} is that HTTP status. */
    public static final class HttpError extends IOException {
        public final int code;

        private HttpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private AiRequest() {}

    /**
     * Posts {@code messages} to the configured chat service and returns the reply text.
     *
     * @param temperature sent only when non-null (and the service accepts it)
     * @param timeoutSec  read timeout for the reply
     * @throws HttpError when the service answers with an error status
     * @throws IOException for any other failure, including a reply with no content
     */
    @NonNull
    public static String complete(@NonNull Context context, @NonNull String model, @NonNull String apiKey,
                                  @NonNull JSONArray messages, int maxTokens, @Nullable Double temperature,
                                  int timeoutSec) throws IOException {
        String url = AiService.chatUrl(context);
        Quirks q = quirksFor(url);
        OkHttpClient client = BASE.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build();
        try {
            for (int attempt = 0; ; attempt++) {
                JSONObject body = new JSONObject()
                        .put("model", model)
                        .put("messages", messages)
                        .put(q.completionTokens ? "max_completion_tokens" : "max_tokens", maxTokens);
                if (temperature != null && q.temperature) body.put("temperature", temperature.doubleValue());

                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    int code = response.code();
                    String text = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        JSONArray choices = new JSONObject(text).optJSONArray("choices");
                        JSONObject msg = choices == null || choices.length() == 0
                                ? null : choices.getJSONObject(0).optJSONObject("message");
                        if (msg == null || msg.isNull("content")) {
                            throw new IOException("The AI service sent no reply.");
                        }
                        return msg.optString("content", "");
                    }
                    if ((code == 400 || code == 404 || code == 422) && attempt < 5 && q.adapt(text)) {
                        FLog.i(TAG, "service refused a setting (HTTP " + code + "); now max_completion_tokens "
                                + q.completionTokens + ", temperature " + q.temperature);
                        continue;
                    }
                    throw new HttpError(code, "API error " + code + ": "
                            + text.substring(0, Math.min(200, text.length())));
                }
            }
        } catch (JSONException e) {
            throw new IOException("The AI service sent a reply that could not be read.", e);
        }
    }

    @NonNull
    private static Quirks quirksFor(@NonNull String url) {
        Quirks q = QUIRKS.get(url);
        if (q == null) {
            q = new Quirks();
            QUIRKS.put(url, q);
        }
        return q;
    }
}
