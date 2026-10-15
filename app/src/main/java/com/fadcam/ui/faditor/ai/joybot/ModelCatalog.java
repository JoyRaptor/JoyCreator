package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Every model on OpenRouter Joybot can talk to, with what each one can do — and the person's stars.
 *
 * <p>Owner, 2026-10-14: "search for models and put a star next to them, and then have the ability
 * to switch on the fly between my starred models. Things that are free should be denoted as free;
 * whether images are an option… and talking directly to an image generation model."</p>
 *
 * <p>Two catalogues, both public (no key): chat models ({@code /models}: free, sees pictures, makes
 * pictures) and picture models ({@code /images/models}). Cached on the phone for six hours so the
 * picker opens instantly and works offline from the last copy.</p>
 */
public final class ModelCatalog {

    private ModelCatalog() {}

    public static final class Model {
        @NonNull public final String id;
        @NonNull public final String name;
        /** Costs nothing (":free", or zero prompt and completion price). */
        public final boolean free;
        /** Accepts pictures as input (👁). */
        public final boolean sees;
        /** Produces pictures (🎨). */
        public final boolean makesPictures;
        /** From the picture catalogue: talks only in pictures. */
        public final boolean pictureModel;

        Model(@NonNull String id, @NonNull String name, boolean free, boolean sees,
              boolean makesPictures, boolean pictureModel) {
            this.id = id; this.name = name; this.free = free; this.sees = sees;
            this.makesPictures = makesPictures; this.pictureModel = pictureModel;
        }

        /** "FREE · 👁" — the badges, in words a row can show. */
        @NonNull public String badges() {
            StringBuilder b = new StringBuilder();
            if (free) b.append("FREE");
            if (sees) { if (b.length() > 0) b.append("  "); b.append("👁 sees pictures"); }
            if (makesPictures) { if (b.length() > 0) b.append("  "); b.append("🎨 makes pictures"); }
            return b.toString();
        }
    }

    private static final long TTL_MS = 6 * 60 * 60 * 1000L;
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();

    /** The free router is always first: a new person's first experience costs nothing. */
    public static final Model FREE_ROUTER = new Model("openrouter/free",
            "Free models (OpenRouter picks)", true, true, false, false);

    @WorkerThread
    @NonNull
    public static List<Model> chatModels(@NonNull Context c) {
        if (com.fadcam.ui.faditor.ai.AiService.isCustom(c)) return accountModels(c);
        String json = cached(c, "openrouter_models.json", "https://openrouter.ai/api/v1/models", null);
        List<Model> out = new ArrayList<>();
        out.add(FREE_ROUTER);
        if (json == null) return out;
        try {
            JSONArray data = new JSONObject(json).optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("id", "");
                if (id.isEmpty() || id.equals(FREE_ROUTER.id)) continue;
                JSONObject arch = m.optJSONObject("architecture");
                JSONObject price = m.optJSONObject("pricing");
                boolean free = id.endsWith(":free") || (price != null
                        && "0".equals(price.optString("prompt")) && "0".equals(price.optString("completion")));
                out.add(new Model(id, m.optString("name", id), free,
                        has(arch, "input_modalities", "image"), has(arch, "output_modalities", "image"), false));
            }
        } catch (Exception ignored) { }
        return out;
    }

    @WorkerThread
    @NonNull
    public static List<Model> pictureModels(@NonNull Context c) {
        // Pictures are made through OpenRouter only (JoybotPictures.canGenerate says the same).
        if (com.fadcam.ui.faditor.ai.AiService.isCustom(c)) return new ArrayList<>();
        String json = cached(c, "openrouter_image_models.json", "https://openrouter.ai/api/v1/images/models", null);
        List<Model> out = new ArrayList<>();
        if (json == null) {
            out.add(new Model(JoybotPictures.DEFAULT_MODEL, "FLUX.2 Klein", false, false, true, true));
            return out;
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray data = root.optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("id", "");
                if (id.isEmpty()) continue;
                JSONObject arch = m.optJSONObject("architecture");
                out.add(new Model(id, m.optString("name", id), false,
                        has(arch, "input_modalities", "image"), true, true));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * The chat models of the account in use when it is NOT OpenRouter — OpenAI, Anthropic, a server
     * on the person's computer — from that service's own {@code /models}, asked with their key
     * (OpenAI's list is private to the account). No prices or picture flags there: unknown, never a
     * wrong "free".
     */
    @WorkerThread
    @NonNull
    private static List<Model> accountModels(@NonNull Context c) {
        String base = com.fadcam.ui.faditor.ai.AiService.base(c);
        String key = com.fadcam.ui.faditor.ai.AiKey.get(c);
        String json = cached(c, "models_" + Integer.toHexString(base.hashCode()) + ".json",
                base + "/models", key);
        List<Model> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray data = new JSONObject(json).optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("id", "");
                if (id.startsWith("models/")) id = id.substring("models/".length());   // Google
                if (id.isEmpty() || !AiProviders.isChatModel(id)) continue;
                out.add(new Model(id, m.optString("display_name", id), false, false, false, false));
            }
        } catch (Exception ignored) { }
        java.util.Collections.sort(out, (a, b) -> a.id.compareToIgnoreCase(b.id));
        return out;
    }

    private static boolean has(@Nullable JSONObject arch, String key, String value) {
        JSONArray a = arch == null ? null : arch.optJSONArray(key);
        for (int i = 0; a != null && i < a.length(); i++) if (value.equals(a.optString(i))) return true;
        return false;
    }

    /** The cached catalogue, refreshed when older than six hours; the old copy if offline. */
    @WorkerThread
    @Nullable
    private static String cached(@NonNull Context c, @NonNull String file, @NonNull String url,
                                 @Nullable String key) {
        File f = new File(c.getCacheDir(), file);
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS;
        if (!fresh) {
            Request.Builder rb = new Request.Builder().url(url).get();
            if (key != null && !key.isEmpty()) {
                // Bearer for OpenAI-style services; Anthropic's /models reads its own header.
                rb.addHeader("Authorization", "Bearer " + key)
                        .addHeader("x-api-key", key).addHeader("anthropic-version", "2023-06-01");
            }
            try (Response r = HTTP.newCall(rb.build()).execute()) {
                if (r.isSuccessful() && r.body() != null) {
                    String body = r.body().string();
                    try (FileOutputStream o = new FileOutputStream(f)) {
                        o.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                    return body;
                }
            } catch (Exception ignored) { }
        }
        if (!f.exists()) return null;
        // FileInputStream, not java.nio.file.Files: that needs Android 8 and the app runs from 7.
        try (java.io.FileInputStream in = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Models matching the search words (name or id, any order), starred first. */
    @NonNull
    public static List<Model> filter(@NonNull List<Model> all, @NonNull String query, @NonNull Set<String> stars) {
        String[] words = query.toLowerCase(Locale.US).trim().split("\\s+");
        List<Model> out = new ArrayList<>();
        for (Model m : all) {
            String hay = (m.name + " " + m.id + (m.free ? " free" : "") + (m.sees ? " vision see" : "")
                    + (m.makesPictures ? " image picture" : "")).toLowerCase(Locale.US);
            boolean ok = true;
            for (String w : words) if (!w.isEmpty() && !hay.contains(w)) { ok = false; break; }
            if (ok) out.add(m);
        }
        List<Model> starred = new ArrayList<>(), rest = new ArrayList<>();
        for (Model m : out) (stars.contains(m.id) ? starred : rest).add(m);
        starred.addAll(rest);
        return starred;
    }

    // ── stars and what Joybot is talking to ────────────────────────────────

    private static final String PREFS = "joybot_models";

    @NonNull
    public static Set<String> stars(@NonNull Context c) {
        Set<String> s = prefs(c).getStringSet(starsKey(c), null);
        if (s == null) {
            s = new HashSet<>();
            if (!com.fadcam.ui.faditor.ai.AiService.isCustom(c)) {
                s.add(FREE_ROUTER.id);
                s.add(JoybotPictures.DEFAULT_MODEL);
            }
        }
        return new HashSet<>(s);
    }

    public static void setStar(@NonNull Context c, @NonNull String id, boolean on) {
        Set<String> s = stars(c);
        if (on) s.add(id); else s.remove(id);
        prefs(c).edit().putStringSet(starsKey(c), s).apply();
    }

    /** Stars belong to a service: an OpenRouter id means nothing to OpenAI, and the reverse. */
    @NonNull
    private static String starsKey(@NonNull Context c) {
        return com.fadcam.ui.faditor.ai.AiService.isCustom(c)
                ? "stars@" + com.fadcam.ui.faditor.ai.AiService.base(c) : "stars";
    }

    /** True when messages go to the picture model (each message makes a picture). */
    public static boolean talkingToPictures(@NonNull Context c) {
        return prefs(c).getBoolean("talk_pictures", false);
    }

    public static void setTalkingToPictures(@NonNull Context c, boolean on) {
        prefs(c).edit().putBoolean("talk_pictures", on).apply();
    }

    /** Remembered names for the header, so it never shows a bare id when the name is known. */
    public static void rememberName(@NonNull Context c, @NonNull Model m) {
        prefs(c).edit().putString("name:" + m.id, m.name).putBoolean("pic:" + m.id, m.pictureModel).apply();
    }

    /** True for a model from the picture catalogue (remembered when it was starred or picked). */
    public static boolean isPictureModel(@NonNull Context c, @NonNull String id) {
        return id.equals(JoybotPictures.DEFAULT_MODEL) || prefs(c).getBoolean("pic:" + id, false);
    }

    @NonNull
    public static String nameOf(@NonNull Context c, @NonNull String id) {
        if (id.equals(FREE_ROUTER.id)) return "Free models";
        String n = prefs(c).getString("name:" + id, null);
        if (n != null) return n;
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
