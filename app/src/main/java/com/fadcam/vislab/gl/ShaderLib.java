package com.fadcam.vislab.gl;

import android.content.res.AssetManager;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The VisLab shader files, read from {@code assets/vislab/shaders} and assembled by the one
 * contract the web pilot also follows:
 * {@code "#version 300 es" + precision + common.glsl + "#line 1" + body}.
 * Change it in both places or the browser and the phone stop agreeing.
 */
public final class ShaderLib {

    private static final String PRECISION = "precision highp float;\nprecision highp int;\nprecision highp sampler2D;\n";

    private final Map<String, String> files = new HashMap<>();

    /** Sources from the APK. Safe to call off the GL thread (it only reads text). */
    @NonNull
    public static ShaderLib fromAssets(@NonNull AssetManager am) throws IOException {
        ShaderLib lib = new ShaderLib();
        String[] names = am.list("vislab/shaders");
        if (names != null) {
            for (String n : names) lib.files.put(n, readAll(am.open("vislab/shaders/" + n)));
        }
        return lib;
    }

    /** Sources from a map (tests, packs layered over the built-ins). */
    @NonNull
    public static ShaderLib of(@NonNull Map<String, String> sources) {
        ShaderLib lib = new ShaderLib();
        lib.files.putAll(sources);
        return lib;
    }

    public boolean has(@NonNull String name) {
        return files.containsKey(name);
    }

    @NonNull
    public String assemble(@NonNull String name) {
        return assemble(name, "");
    }

    /** {@code defines}: lines put ahead of the shader to build a smaller variant of it. */
    @NonNull
    public String assemble(@NonNull String name, @NonNull String defines) {
        String body = files.get(name);
        if (body == null) throw new IllegalArgumentException("missing shader " + name);
        String common = files.get("common.glsl");
        if (common == null) throw new IllegalStateException("missing common.glsl");
        return "#version 300 es\n" + PRECISION + defines + common + "\n#line 1\n" + body;
    }

    @NonNull
    public static String readAll(@NonNull InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toString(StandardCharsets.UTF_8.name());
        }
    }
}
