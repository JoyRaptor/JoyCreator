package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.ReadableNames;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.android.SafJoyFs;
import com.fadcam.joyfolder.studio.StudioMirror;
import com.fadcam.ui.faditor.ai.AiKey;
import com.fadcam.ui.faditor.ai.AiService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Pictures for the project: generated through OpenRouter's picture service, or handed to the
 * person's own ChatGPT / Gemini app and brought back with Share.
 *
 * <p>Roadmap 3.10. Measured 2026-10-14: OpenRouter has no free picture model, so generating costs
 * money on the person's key. Joybot therefore shows the price — read from OpenRouter's own
 * catalogue, never guessed — on the button that starts it, and the free route (their own AI app,
 * back through Share to Joy Creator) is always offered beside it.</p>
 *
 * <p>Every picture is saved into the Joy Creator folder with a readable name (owner's rule: work
 * is never kept somewhere hidden), then reaches the timeline through the same hand-off the share
 * sheet uses, so it inserts at the playhead as one undo step.</p>
 */
public final class JoybotPictures {

    private JoybotPictures() {}

    /** Cheapest picture model on OpenRouter when checked (2026-10-14): ~$0.014 per megapixel. */
    public static final String DEFAULT_MODEL = "black-forest-labs/flux.2-klein-4b";
    private static final String PREFS = "joybot_pictures";
    private static final String KEY_MODEL = "model";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @NonNull
    public static String model(@NonNull Context c) {
        String m = prefs(c).getString(KEY_MODEL, "");
        return m == null || m.trim().isEmpty() ? DEFAULT_MODEL : m.trim();
    }

    public static void setModel(@NonNull Context c, @Nullable String model) {
        prefs(c).edit().putString(KEY_MODEL, model == null ? "" : model.trim()).apply();
    }

    /** Generating needs a key and OpenRouter (a custom service may have no picture endpoint). */
    public static boolean canGenerate(@NonNull Context c) {
        String key = AiKey.get(c);
        return key != null && !key.isEmpty() && !AiService.isCustom(c);
    }

    // ── price ──────────────────────────────────────────────────────────────

    /** "about 1½¢", or null when the catalogue could not be read. Blocking. */
    @WorkerThread
    @Nullable
    public static String priceWords(@NonNull String model) {
        Double usd = priceUsd(model);
        if (usd == null) return null;
        double cents = usd * 100.0;
        if (cents < 1) return "under 1¢";
        if (cents < 10) {
            double half = Math.round(cents * 2) / 2.0;
            String whole = String.valueOf((int) Math.floor(half));
            return "about " + whole + (half % 1 != 0 ? "½" : "") + "¢";
        }
        return String.format(Locale.US, "about %d¢", Math.round(cents));
    }

    private static final java.util.Map<String, Double> PRICES = new java.util.HashMap<>();

    /** One picture's price in dollars from OpenRouter's catalogue, for a ~1 megapixel picture. */
    @WorkerThread
    @Nullable
    static Double priceUsd(@NonNull String model) {
        synchronized (PRICES) {
            if (PRICES.containsKey(model)) return PRICES.get(model);
        }
        Double out = null;
        try (Response r = HTTP.newCall(new Request.Builder()
                .url("https://openrouter.ai/api/v1/images/models/" + model + "/endpoints").get().build())
                .execute()) {
            if (r.isSuccessful() && r.body() != null) {
                JSONArray eps = new JSONObject(r.body().string()).optJSONArray("endpoints");
                JSONObject ep = eps == null ? null : eps.optJSONObject(0);
                JSONArray pricing = ep == null ? null : ep.optJSONArray("pricing");
                if (pricing != null) {
                    for (int i = 0; i < pricing.length(); i++) {
                        JSONObject p = pricing.optJSONObject(i);
                        if (p == null || !"output_image".equals(p.optString("billable"))) continue;
                        String unit = p.optString("unit");
                        double cost = p.optDouble("cost_usd", Double.NaN);
                        if (Double.isNaN(cost)) continue;
                        // "image" is per picture; "megapixel" is per MP, and we ask for about one.
                        if ("image".equals(unit) || "megapixel".equals(unit)) out = cost;
                    }
                }
            }
        } catch (Exception ignored) { }
        synchronized (PRICES) { PRICES.put(model, out); }
        return out;
    }

    // ── generate ───────────────────────────────────────────────────────────

    public static final class Picture {
        @NonNull public final byte[] bytes;
        @NonNull public final String mediaType;

        Picture(@NonNull byte[] bytes, @NonNull String mediaType) {
            this.bytes = bytes;
            this.mediaType = mediaType;
        }

        @NonNull public String ending() {
            return mediaType.contains("jpeg") || mediaType.contains("jpg") ? ".jpg"
                    : mediaType.contains("webp") ? ".webp" : ".png";
        }
    }

    /** Thrown with a sentence Joybot can say as-is. */
    public static final class Problem extends Exception {
        public Problem(@NonNull String plain) { super(plain); }
    }

    /**
     * Make one picture. Blocking (often 5–30 s).
     *
     * @param aspect a canvas preset ("16:9", "9:16", "1:1", "4:5", "original")
     */
    @WorkerThread
    @NonNull
    public static Picture generate(@NonNull Context c, @NonNull String prompt, @NonNull String aspect)
            throws Problem {
        if (!canGenerate(c)) {
            throw new Problem(AiService.isCustom(c)
                    ? "Making pictures works through OpenRouter; your AI is set to another service."
                    : "Making pictures needs an OpenRouter key. Add one in ⚙.");
        }
        try {
            JSONObject body = new JSONObject()
                    .put("model", model(c))
                    .put("prompt", prompt)
                    .put("n", 1)
                    .put("aspect_ratio", ratioFor(aspect));
            Request req = new Request.Builder()
                    .url(AiService.DEFAULT_BASE + "/images")
                    .addHeader("Authorization", "Bearer " + AiKey.get(c))
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            try (Response r = HTTP.newCall(req).execute()) {
                String text = r.body() == null ? "" : r.body().string();
                if (!r.isSuccessful()) throw new Problem(plainHttp(r.code(), text));
                JSONArray data = new JSONObject(text).optJSONArray("data");
                JSONObject first = data == null ? null : data.optJSONObject(0);
                String b64 = first == null ? null : first.optString("b64_json", null);
                if (b64 == null || b64.isEmpty()) throw new Problem("The picture service sent nothing back. Try again.");
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                return new Picture(bytes, first.optString("media_type", "image/png"));
            }
        } catch (Problem p) {
            throw p;
        } catch (java.net.UnknownHostException e) {
            throw new Problem("I can't reach the picture service — check the internet.");
        } catch (java.net.SocketTimeoutException e) {
            throw new Problem("The picture service took too long. Try again in a moment.");
        } catch (Exception e) {
            throw new Problem("Making the picture failed: " + e.getMessage());
        }
    }

    @NonNull
    static String plainHttp(int code, @NonNull String body) {
        String l = body.toLowerCase(Locale.US);
        if (code == 401) return "The picture service didn't accept your key. Check it in ⚙.";
        if (code == 402 || l.contains("credit") || l.contains("insufficient")) {
            return "Pictures cost a little, and your OpenRouter account needs credit for them.";
        }
        if (code == 429) return "The picture service is busy. Try again in a minute.";
        if (l.contains("moderation") || l.contains("safety") || l.contains("policy")) {
            return "The picture service turned that one down. Try describing it differently.";
        }
        return "The picture service said no (" + code + "). Try again, or describe it differently.";
    }

    /** The service's ratios: close enough to the canvas, from the set FLUX and friends accept. */
    @NonNull
    static String ratioFor(@NonNull String preset) {
        switch (preset) {
            case "9:16": return "9:16";
            case "1:1": return "1:1";
            case "4:5": return "3:4";
            case "16:9": return "16:9";
            default: return "16:9";
        }
    }

    // ── keep ───────────────────────────────────────────────────────────────

    /**
     * Save into the Joy Creator folder, beside the project's own folder when it has one.
     *
     * @return the picture's address, for the timeline hand-off
     */
    /** Where a picture was kept: its address, and the folder + name in words. */
    public static final class Saved {
        @NonNull public final Uri uri;
        /** "Client A/" or "" for the top of the Joy Creator folder. */
        @NonNull public final String dir;
        @NonNull public final String name;

        Saved(@NonNull Uri uri, @NonNull String dir, @NonNull String name) {
            this.uri = uri;
            this.dir = dir;
            this.name = name;
        }

        /** "“Cat astronaut.png” in Joy Creator › Client A". */
        @NonNull public String where() {
            return "“" + name + "” in Joy Creator" + (dir.isEmpty() ? "" : " › " + dir.replace("/", " › "));
        }
    }

    @WorkerThread
    @NonNull
    public static Saved save(@NonNull Context c, @Nullable String projectId, @NonNull Picture pic,
                             @NonNull String prompt) throws Problem {
        if (!JoyFolderHome.isReachable(c)) {
            throw new Problem(JoyFolderHome.isChosen(c)
                    ? "I can't reach your Joy Creator folder right now, so I didn't keep the picture. Reconnect it and try again."
                    : "Choose your Joy Creator folder first (Settings → Storage) so the picture has a home.");
        }
        try {
            JoyFolder jf = JoyFolderHome.open(c);
            SafJoyFs saf = JoyFolderHome.fsOf(jf);
            if (jf == null || saf == null) throw new Problem("I can't open your Joy Creator folder.");
            String workRel = projectId == null ? null : StudioMirror.folderPathOf(c, projectId);
            String dir = jf.prepareDir(Placement.What.IMPORT, workRel, null, null);
            String name = ReadableNames.sanitize(nameFor(prompt)) + pic.ending();
            String rel = jf.addFile(dir, name, new ByteArrayInputStream(pic.bytes), "joybot");
            jf.saveIndex();
            Uri u = saf.documentUri(rel);
            if (u == null) throw new Problem("The picture was saved, but I can't find it again.");
            return new Saved(u, dir, Placement.nameOf(rel));
        } catch (Problem p) {
            throw p;
        } catch (Exception e) {
            throw new Problem("I couldn't save the picture: " + e.getMessage());
        }
    }

    /** "Neon raccoon on a rooftop" from the description: the first few words, capitalised. */
    @NonNull
    static String nameFor(@NonNull String prompt) {
        String[] w = prompt.trim().replaceAll("[^\\p{L}\\p{N} '-]", " ").trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < w.length && i < 6; i++) {
            if (w[i].isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w[i]);
        }
        String s = b.length() == 0 ? "Joybot picture" : b.toString();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ── the free route: their own AI app ───────────────────────────────────

    /** A prompt worth pasting into ChatGPT or Gemini, ending with how to bring it back. */
    @NonNull
    public static String handoffPrompt(@NonNull String subject, @NonNull String aspect) {
        String shape;
        switch (ratioFor(aspect)) {
            case "9:16": shape = "tall (9:16, phone-screen shaped)"; break;
            case "1:1": shape = "square"; break;
            case "3:4": shape = "slightly tall (4:5)"; break;
            default: shape = "wide (16:9, video shaped)"; break;
        }
        return "Please make a picture: " + subject.trim()
                + ". Make it " + shape + ", with no words or watermark in it unless I asked for words.";
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
