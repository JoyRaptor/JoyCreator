package com.fadcam.ui.faditor.waveform;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.VizLayer;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.model.WaveformStyle;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads built-in waveform style presets from {@code assets/waveform_styles/} and handles
 * SAF import/export of user style JSON files (same convention as text-style IO).
 */
public class WaveformStyleIO {

    private static final String TAG = "WaveformStyleIO";
    private static final String ASSET_DIR = "waveform_styles";
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
    /** Suffix marking a user-saved visualizer style file in the pinned folder. */
    public static final String USER_STYLE_SUFFIX = ".waveform.json";

    private WaveformStyleIO() {}

    /**
     * Load user-saved visualizer styles ({@code *.waveform.json}) from the pinned-assets SAF tree, so a
     * style a user saved to their folder auto-appears in the picker. Empty list if the URI is null/unset
     * or has no such files (no clutter when the folder is empty).
     */
    @NonNull
    public static List<WaveformStyle> loadUserStyles(@NonNull Context context,
                                                     @Nullable android.net.Uri treeUri) {
        List<WaveformStyle> out = new ArrayList<>();
        if (treeUri == null) return out;
        try {
            androidx.documentfile.provider.DocumentFile dir =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri);
            if (dir == null || !dir.isDirectory()) return out;
            for (androidx.documentfile.provider.DocumentFile f : dir.listFiles()) {
                String name = f.getName();
                if (name == null || !name.toLowerCase().endsWith(USER_STYLE_SUFFIX)) continue;
                try (InputStream in = context.getContentResolver().openInputStream(f.getUri())) {
                    if (in == null) continue;
                    WaveformStyle s = fromJsonWithLayers(readUtf8(in));
                    if (s != null && s.type != null && s.id != null) out.add(s);
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Failed loading user waveform styles", e);
        }
        return out;
    }

    /**
     * Save a style as a human-readable (pretty-printed) {@code <id>.waveform.json} into the pinned
     * folder. The user can hand-edit the hex colours/params and drop it back; it'll be re-loaded. Returns
     * the written file name, or null on failure.
     */
    @Nullable
    public static String saveToPinned(@NonNull Context context, @Nullable android.net.Uri treeUri,
                                      @NonNull WaveformStyle style) {
        if (treeUri == null) return null;
        try {
            androidx.documentfile.provider.DocumentFile dir =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri);
            if (dir == null || !dir.isDirectory()) return null;
            String fileName = sanitize(style.id) + USER_STYLE_SUFFIX;
            androidx.documentfile.provider.DocumentFile existing = dir.findFile(fileName);
            if (existing != null) existing.delete();
            androidx.documentfile.provider.DocumentFile file = dir.createFile("application/json", fileName);
            if (file == null) return null;
            try (OutputStream out = context.getContentResolver().openOutputStream(file.getUri())) {
                if (out == null) return null;
                out.write(toJsonWithLayers(style, PRETTY).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            return fileName;
        } catch (Exception e) {
            FLog.w(TAG, "Failed saving waveform style to pinned folder", e);
            return null;
        }
    }

    @NonNull
    private static String sanitize(@Nullable String id) {
        if (id == null || id.isEmpty()) return "visualizer";
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /** Load all built-in presets bundled in assets. Never returns null; skips bad files. */
    @NonNull
    public static List<WaveformStyle> loadBuiltins(@NonNull Context context) {
        List<WaveformStyle> styles = new ArrayList<>();
        AssetManager am = context.getAssets();
        try {
            String[] files = am.list(ASSET_DIR);
            if (files != null) {
                for (String name : files) {
                    if (!name.endsWith(".json")) continue;
                    WaveformStyle s = readAsset(am, ASSET_DIR + "/" + name);
                    if (s != null) styles.add(s);
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Failed listing built-in waveform styles", e);
        }
        return styles;
    }

    @Nullable
    private static WaveformStyle readAsset(@NonNull AssetManager am, @NonNull String path) {
        try (InputStream in = am.open(path)) {
            return fromJsonWithLayers(readUtf8(in));
        } catch (Exception e) {
            FLog.w(TAG, "Failed reading waveform style: " + path, e);
            return null;
        }
    }

    /** Serialize a style to JSON. */
    @NonNull
    public static String toJson(@NonNull WaveformStyle style) {
        return toJsonWithLayers(style, GSON);
    }

    /** Parse a style from JSON text (SAF import); null on failure. */
    @Nullable
    public static WaveformStyle fromJson(@NonNull String json) {
        try {
            return fromJsonWithLayers(json);
        } catch (Exception e) {
            FLog.w(TAG, "Failed parsing waveform style json", e);
            return null;
        }
    }

    /** Read a style from an opened stream (SAF import). */
    @Nullable
    public static WaveformStyle read(@NonNull InputStream in) {
        try {
            return fromJsonWithLayers(readUtf8(in));
        } catch (Exception e) {
            FLog.w(TAG, "Failed reading waveform style stream", e);
            return null;
        }
    }

    // ── Joy Viz Engine layer stack (SPEC_VIZ_ENGINE §4) ──────────────────────
    // The {@code layers} field on WaveformStyle is transient (Gson never touches it), so a legacy
    // style with {@code layers == null} serializes BYTE-FOR-BYTE as before. When a stack is present
    // we splice a self-serialized "layers" array in, and on read we parse it back tolerantly —
    // unknown emitter strings are skipped so a bad/forward design never crashes a load.

    /** Serialize with the given Gson (compact or pretty), splicing in the layer stack when present. */
    @NonNull
    private static String toJsonWithLayers(@NonNull WaveformStyle style, @NonNull Gson gson) {
        String base = gson.toJson(style);
        if (style.layers == null) return base; // legacy → untouched, byte-identical
        try {
            JsonObject obj = JsonParser.parseString(base).getAsJsonObject();
            JsonArray arr = new JsonArray();
            for (VizLayer l : style.layers) arr.add(l.toJson());
            obj.add("layers", arr);
            return gson.toJson(obj);
        } catch (RuntimeException e) {
            FLog.w(TAG, "Failed serializing waveform style layers; wrote base style only", e);
            return base;
        }
    }

    /** Parse a style, then tolerantly hydrate its {@code layers} stack (null when absent/empty). */
    @Nullable
    private static WaveformStyle fromJsonWithLayers(@NonNull String json) {
        WaveformStyle s = GSON.fromJson(json, WaveformStyle.class);
        if (s == null) return null;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (root.isJsonObject() && root.getAsJsonObject().has("layers")) {
                JsonElement le = root.getAsJsonObject().get("layers");
                if (le.isJsonArray()) {
                    List<VizLayer> layers = new ArrayList<>();
                    for (JsonElement e : le.getAsJsonArray()) {
                        if (!e.isJsonObject()) continue;
                        VizLayer l = VizLayer.fromJson(e.getAsJsonObject());
                        if (VizLayer.isKnownEmitter(l.emitter)) layers.add(l); // skip unknown
                    }
                    s.layers = layers.isEmpty() ? null : layers; // null (not empty) = legacy
                }
            }
        } catch (RuntimeException e) {
            // A malformed layers array must never take the style (or the load) down.
            FLog.w(TAG, "Failed parsing waveform style layers; kept base style", e);
        }
        return s;
    }

    /**
     * Resolve the EFFECTIVE style an instance renders (SPEC_VIZ_ENGINE §4, Layers UI lane) — the ONE
     * place preview, export and Save/Export-style all share, so a customized layer stack renders the
     * same everywhere. Precedence: the instance's inline {@link WaveformOverlayInstance#getCustomStyleJson()
     * custom style JSON} (a full layered {@link WaveformStyle}) WINS; if it is absent or fails to parse
     * we fall back to {@code fallbackBase} (the {@code styleId} preset the caller already looked up).
     * The instance's scalar overrides (colour / gradient / sensitivity / bar dims) are then layered on
     * via {@link WaveformOverlayInstance#applyOverrides} — exactly as before — so the gradient Rolodex
     * and sensitivity slider keep working over a custom stack (applyOverrides pushes onto layer 0).
     *
     * @return the effective style, or {@code null} only when the custom JSON is absent/unparseable AND
     *         {@code fallbackBase} is null (caller should skip drawing, matching the old behaviour).
     */
    @Nullable
    public static WaveformStyle resolveEffectiveStyle(
            @NonNull WaveformOverlayInstance instance, @Nullable WaveformStyle fallbackBase) {
        WaveformStyle base = null;
        String custom = instance.getCustomStyleJson();
        if (custom != null) {
            base = fromJson(custom); // tolerant; null on parse failure → fall back to the preset
        }
        if (base == null) base = fallbackBase;
        if (base == null) return null;
        return instance.applyOverrides(base);
    }

    /** Write a style as JSON to an opened stream (SAF export). */
    public static boolean write(@NonNull OutputStream out, @NonNull WaveformStyle style) {
        try {
            out.write(toJson(style).getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Exception e) {
            FLog.w(TAG, "Failed writing waveform style stream", e);
            return false;
        }
    }

    @NonNull
    private static String readUtf8(@NonNull InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
