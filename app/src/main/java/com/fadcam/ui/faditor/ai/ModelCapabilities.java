package com.fadcam.ui.faditor.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * "Can the connected model SEE?" — OpenRouter model capability lookup.
 *
 * <p>OpenRouter publishes, per model, which input modalities it accepts:
 * {@code data[].architecture.input_modalities} is a string array like
 * {@code ["text","image","video"]}. The endpoint also filters server-side, so asking for
 * {@code ?input_modalities=image} returns only the models that can see — a few hundred ids
 * instead of the ~534KB full catalogue. That is what this fetches.</p>
 *
 * <h3>The routers</h3>
 * Two ids are not leaf models but ROUTERS, and they need their own answer:
 * <ul>
 *   <li>{@code openrouter/free} — the "Free Models Router". Advertises <b>text + image</b>. Free,
 *       and the one most users mean by "the free option".</li>
 *   <li>{@code openrouter/auto} — the "Auto Router". Advertises text/image/audio/file/video, and
 *       is <b>paid</b> (its pricing is "whatever it picks").</li>
 * </ul>
 * Both accept images, so the indicator is honestly ON for either. Routing also PRE-FILTERS by
 * what the request contains — attach an image and the router only considers models that can see —
 * so this lookup exists to tell the USER what to expect, not to gate the request.
 *
 * <h3>Deliberately not a gate</h3>
 * OpenRouter does not document what happens when an image reaches a text-only model, so nothing
 * here refuses to send. The send path attempts and handles failure; this only decides whether an
 * eye is shown. A capability check that blocked sending would turn an undocumented maybe into a
 * definite no.
 */
public final class ModelCapabilities {

    private ModelCapabilities() {}

    private static final String MODELS_IMAGE_URL =
            "https://openrouter.ai/api/v1/models?input_modalities=image";

    /** Re-fetch after this long. The catalogue changes, but not minute to minute. */
    private static final long TTL_MS = 6 * 60 * 60 * 1000L;

    /** Lower-cased ids of every model that accepts image input. Empty until the first fetch. */
    @NonNull private static volatile Set<String> imageCapable = Collections.emptySet();
    private static volatile long fetchedAtMs = 0L;
    private static volatile boolean fetching = false;

    /**
     * What we know about the configured model's vision support.
     *
     * <p>Three states, because "we have not asked yet" is genuinely different from "no": showing
     * a dark eye before the first fetch lands would claim the model cannot see when nobody has
     * checked.</p>
     */
    public enum Vision {
        /** Catalogue not fetched yet (or the fetch failed) — show nothing. */
        UNKNOWN,
        /** The model accepts image input. */
        YES,
        /** The model is in the catalogue and does NOT accept image input. */
        NO
    }

    /** Vision support for {@code modelId}, from whatever is currently cached. Never blocks. */
    @NonNull
    public static Vision visionFor(@Nullable String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) return Vision.UNKNOWN;
        String id = modelId.trim().toLowerCase(Locale.US);
        // The routers are answered without the catalogue: they are not leaf models, and both
        // accept images. Answering them from the fetch would report UNKNOWN until it lands, on
        // the two ids most likely to be configured.
        if (id.equals("openrouter/free") || id.equals("openrouter/auto")
                || id.equals("openrouter/auto-beta")) {
            return Vision.YES;
        }
        Set<String> caps = imageCapable;
        if (caps.isEmpty()) return Vision.UNKNOWN;
        if (caps.contains(id)) return Vision.YES;
        // A ":free"/":nitro"-style variant suffix is the same underlying model.
        int colon = id.indexOf(':');
        if (colon > 0 && caps.contains(id.substring(0, colon))) return Vision.YES;
        return Vision.NO;
    }

    /** True when the cache is stale enough to be worth re-fetching. */
    public static boolean needsRefresh() {
        return !fetching && (imageCapable.isEmpty()
                || System.currentTimeMillis() - fetchedAtMs > TTL_MS);
    }

    /**
     * Fetch the image-capable model ids. BLOCKING — call from a background executor.
     *
     * <p>Requires no API key: the models endpoint is public. Failure is silent and leaves the
     * previous answer in place, because a network hiccup should not flip a correct indicator to
     * a wrong one.</p>
     *
     * @param onDone run after a SUCCESSFUL refresh (on the calling thread), for the UI to redraw
     */
    public static void refreshBlocking(@Nullable Runnable onDone) {
        if (fetching) return;
        fetching = true;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(MODELS_IMAGE_URL).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONArray data = new JSONObject(sb.toString()).optJSONArray("data");
            if (data == null) return;
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("id", "");
                if (id.isEmpty()) continue;
                // Trust but verify: the server-side filter is undocumented, so confirm each
                // entry really does list "image" rather than assuming the query worked.
                JSONObject arch = m.optJSONObject("architecture");
                if (arch != null && !acceptsImage(arch)) continue;
                ids.add(id.toLowerCase(Locale.US));
            }
            if (ids.isEmpty()) return;
            imageCapable = Collections.unmodifiableSet(ids);
            fetchedAtMs = System.currentTimeMillis();
            if (onDone != null) onDone.run();
        } catch (Exception ignored) {
            // Silent: the indicator simply stays at whatever it already knew.
        } finally {
            fetching = false;
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean acceptsImage(@NonNull JSONObject architecture) {
        JSONArray in = architecture.optJSONArray("input_modalities");
        if (in != null) {
            for (int i = 0; i < in.length(); i++) {
                if ("image".equalsIgnoreCase(in.optString(i, ""))) return true;
            }
            return false;
        }
        // Older entries express the same thing as "text+image->text".
        String modality = architecture.optString("modality", "");
        return modality.toLowerCase(Locale.US).contains("image");
    }

    /**
     * Record the model an actual response came from, upgrading a ROUTER's prediction to a fact.
     *
     * <p>A chat completion's top-level {@code model} field names the model that really served
     * the request, which for {@code openrouter/free} is only knowable after the fact. Callers
     * pass it so the label can say what answered rather than what was asked for.</p>
     */
    @Nullable private static volatile String lastServedModel;

    public static void noteServedModel(@Nullable String modelId) {
        if (modelId != null && !modelId.trim().isEmpty()) lastServedModel = modelId.trim();
    }

    /** The model that served the most recent response, or null. */
    @Nullable
    public static String lastServedModel() { return lastServedModel; }
}
