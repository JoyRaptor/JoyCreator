package com.fadcam.vislab;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.gl.ShaderLib;
import com.fadcam.vislab.gl.VisRenderer;
import com.fadcam.vislab.model.VisScene;
import com.fadcam.vislab.model.VisTypes;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * VisLab's front door for the rest of the app: the registry of primitives, the shader library
 * and the built-in looks, loaded once from {@code assets/vislab} and shared by the VisLab room,
 * Studio and the floating on-screen visualiser.
 */
public final class VisLab {

    private static final String TAG = "VisLab";
    private static volatile VisLab instance;

    private final VisTypes types;
    private final ShaderLib shaders;
    private final List<VisScene> builtIns;

    private VisLab(@NonNull VisTypes types, @NonNull ShaderLib shaders, @NonNull List<VisScene> builtIns) {
        this.types = types;
        this.shaders = shaders;
        this.builtIns = Collections.unmodifiableList(builtIns);
    }

    @NonNull
    public static VisLab get(@NonNull Context context) {
        VisLab v = instance;
        if (v != null) return v;
        synchronized (VisLab.class) {
            if (instance == null) instance = load(context.getApplicationContext().getAssets());
            return instance;
        }
    }

    @NonNull
    private static VisLab load(@NonNull AssetManager am) {
        try {
            VisTypes types = VisTypes.parse(ShaderLib.readAll(am.open("vislab/types.json")));
            ShaderLib shaders = ShaderLib.fromAssets(am);
            List<VisScene> looks = new ArrayList<>();
            JsonObject index = JsonParser.parseString(ShaderLib.readAll(am.open("vislab/scenes/index.json"))).getAsJsonObject();
            for (JsonElement e : index.getAsJsonArray("scenes")) {
                try {
                    looks.add(VisScene.parse(ShaderLib.readAll(am.open("vislab/scenes/" + e.getAsString()))));
                } catch (IOException | RuntimeException ex) {
                    Log.w(TAG, "skipped look " + e, ex);
                }
            }
            return new VisLab(types, shaders, looks);
        } catch (IOException e) {
            throw new IllegalStateException("VisLab assets missing", e);
        }
    }

    @NonNull
    public VisTypes types() {
        return types;
    }

    @NonNull
    public ShaderLib shaders() {
        return shaders;
    }

    /** A fresh renderer. Each GL context needs its own. */
    @NonNull
    public VisRenderer newRenderer() {
        return new VisRenderer(shaders, types);
    }

    /** The looks shipped in the app, in their showcase order. Copy one before editing it. */
    @NonNull
    public List<VisScene> builtInLooks() {
        return builtIns;
    }

    @Nullable
    public VisScene builtIn(@NonNull String id) {
        for (VisScene s : builtIns) if (s.id().equals(id)) return s;
        return null;
    }
}
