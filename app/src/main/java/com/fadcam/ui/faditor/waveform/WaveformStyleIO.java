package com.fadcam.ui.faditor.waveform;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.WaveformStyle;
import com.google.gson.Gson;

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
                    WaveformStyle s = GSON.fromJson(readUtf8(in), WaveformStyle.class);
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
                out.write(PRETTY.toJson(style).getBytes(StandardCharsets.UTF_8));
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
            return GSON.fromJson(readUtf8(in), WaveformStyle.class);
        } catch (Exception e) {
            FLog.w(TAG, "Failed reading waveform style: " + path, e);
            return null;
        }
    }

    /** Serialize a style to JSON. */
    @NonNull
    public static String toJson(@NonNull WaveformStyle style) {
        return GSON.toJson(style);
    }

    /** Parse a style from JSON text (SAF import); null on failure. */
    @Nullable
    public static WaveformStyle fromJson(@NonNull String json) {
        try {
            return GSON.fromJson(json, WaveformStyle.class);
        } catch (Exception e) {
            FLog.w(TAG, "Failed parsing waveform style json", e);
            return null;
        }
    }

    /** Read a style from an opened stream (SAF import). */
    @Nullable
    public static WaveformStyle read(@NonNull InputStream in) {
        try {
            return GSON.fromJson(readUtf8(in), WaveformStyle.class);
        } catch (Exception e) {
            FLog.w(TAG, "Failed reading waveform style stream", e);
            return null;
        }
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
