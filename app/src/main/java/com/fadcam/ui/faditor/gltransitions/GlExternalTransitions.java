package com.fadcam.ui.faditor.gltransitions;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans the user's pinned SAF folder for {@code *.glsl} GL-transition shaders (e.g. dropped in from an
 * external library) and registers them with {@link GlTransitionShaderLoader} so they render in preview +
 * export. Returns the discovered items so the transitions drawer can show a card for each — invisible
 * when the folder has none, so there's no empty-state clutter.
 */
public final class GlExternalTransitions {

    private static final String TAG = "GlExternalTransitions";

    public static final class Item {
        @NonNull public final String id;
        @NonNull public final String displayName;
        Item(@NonNull String id, @NonNull String displayName) {
            this.id = id;
            this.displayName = displayName;
        }
    }

    private GlExternalTransitions() {}

    @NonNull
    public static List<Item> scanAndRegister(@NonNull Context context, @Nullable Uri treeUri) {
        List<Item> out = new ArrayList<>();
        if (treeUri == null) return out;
        try {
            androidx.documentfile.provider.DocumentFile dir =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri);
            if (dir == null || !dir.isDirectory()) return out;
            for (androidx.documentfile.provider.DocumentFile f : dir.listFiles()) {
                String name = f.getName();
                if (name == null || !name.toLowerCase().endsWith(".glsl")) continue;
                String base = name.substring(0, name.length() - ".glsl".length());
                String id = "user_" + base.replaceAll("[^a-zA-Z0-9_-]", "_");
                try (InputStream in = context.getContentResolver().openInputStream(f.getUri())) {
                    if (in == null) continue;
                    String body = readUtf8(in);
                    // Basic GL-Transitions sanity: must define a transition() entry point.
                    if (body.contains("transition(")) {
                        GlTransitionShaderLoader.registerExternal(id, body);
                        GlTransitionShaderLoader.registerExternalParams(id, parseParams(body));
                        out.add(new Item(id, base));
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Failed scanning pinned folder for GL transitions", e);
        }
        return out;
    }

    /**
     * Parse default param values from the GL-Transitions {@code uniform float NAME; // = VALUE}
     * convention, so an external shader's params render at their intended defaults (not 0).
     */
    @NonNull
    private static java.util.Map<String, Float> parseParams(@NonNull String body) {
        java.util.Map<String, Float> out = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("uniform\\s+float\\s+(\\w+)\\s*;\\s*//\\s*=\\s*([-+0-9.eE]+)")
                .matcher(body);
        while (m.find()) {
            try {
                out.put(m.group(1), Float.parseFloat(m.group(2)));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    @NonNull
    private static String readUtf8(@NonNull InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
