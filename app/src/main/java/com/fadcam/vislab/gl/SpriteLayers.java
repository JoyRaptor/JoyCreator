package com.fadcam.vislab.gl;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.audio.Features;
import com.fadcam.vislab.model.CompiledScene;
import com.fadcam.vislab.model.VisType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * The GPU side of Sprite layers: one sheet texture and one {@link SpriteClock} per layer. The
 * sheet comes from the host's {@link VisRenderer.SpriteSource}, once per sheet and clip (the
 * layer's {@code sheet} and {@code clip}); a different one swaps it. GL thread only.
 */
final class SpriteLayers {

    /** What the shader needs for this frame of one layer. */
    static final class Frame {
        int tex;
        final float[] rect = new float[4];
        float aspect = 1f;
    }

    private static final class Slot {
        int tex;
        @Nullable SpriteAtlas atlas;
        @Nullable JsonElement sheet;
        @NonNull String clip = "";
        final SpriteClock clock = new SpriteClock();
    }

    private final IdentityHashMap<Object, Slot> slots = new IdentityHashMap<>();
    private final Frame frame = new Frame();

    /** The frame layer {@code L} shows now; its clock steps by {@code dt}. */
    @NonNull
    Frame frame(@NonNull CompiledScene.Layer L, @Nullable VisRenderer.SpriteSource src,
                @NonNull Features f, double dt) {
        Slot s = slots.get(L.source);
        if (s == null) {
            s = new Slot();
            slots.put(L.source, s);
        }
        JsonElement sheet = L.source.get("sheet");
        JsonElement c = L.source.get("clip");
        String clip = c != null && c.isJsonPrimitive() ? c.getAsString() : "";
        if (s.tex == 0 || s.sheet != sheet || !clip.equals(s.clip)) load(s, src, sheet, clip);
        SpriteAtlas a = s.atlas;
        frame.tex = s.tex;
        if (a == null) {
            frame.rect[0] = frame.rect[1] = 0f;
            frame.rect[2] = frame.rect[3] = 1f;
            frame.aspect = 1f;
            return frame;
        }
        VisType t = L.type;
        int mode = Math.round(L.knobs.value(t, "play", SpriteClock.OWN_SPEED));
        boolean bounce = a.pingpong || L.knobs.value(t, "bounce", 0f) > 0.5f;
        int at = s.clock.step(mode, a.order.length, bounce, a.fps, L.knobs.value(t, "speed", 1f),
                L.knobs.value(t, "frame", 0f), dt, f.beatPhase, f.shockAge(0), f.level);
        a.rect(a.order[Math.max(0, Math.min(a.order.length - 1, at))], frame.rect);
        frame.aspect = a.aspect();
        return frame;
    }

    private void load(@NonNull Slot s, @Nullable VisRenderer.SpriteSource src,
                      @Nullable JsonElement sheet, @NonNull String clip) {
        if (s.tex != 0) GLES30.glDeleteTextures(1, new int[]{s.tex}, 0);
        SpriteAtlas a = null;
        if (src != null) {
            try {
                a = src.atlas(sheet != null && sheet.isJsonObject() ? (JsonObject) sheet : null, clip);
            } catch (RuntimeException e) {
                a = null;   // a broken sheet draws nothing; the look carries on
            }
        }
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0]);
        Bitmap b = a != null ? a.bitmap : null;
        if (b != null && !b.isRecycled()) {
            // Premultiplied, as Android keeps it and as the shader expects.
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        } else {
            a = null;
            ByteBuffer clear = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, clear);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        s.tex = ids[0];
        s.atlas = a;
        s.sheet = sheet;
        s.clip = clip;
        s.clock.reset();
    }

    /** Give back the textures of layers that are gone. */
    void prune(@NonNull Set<Object> alive) {
        Iterator<Map.Entry<Object, Slot>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, Slot> en = it.next();
            if (alive.contains(en.getKey())) continue;
            int tex = en.getValue().tex;
            if (tex != 0) GLES30.glDeleteTextures(1, new int[]{tex}, 0);
            it.remove();
        }
    }

    /** A new look, a seek or the end: every texture back, every clock to the first frame. */
    void free() {
        for (Slot s : slots.values()) if (s.tex != 0) GLES30.glDeleteTextures(1, new int[]{s.tex}, 0);
        slots.clear();
    }

    /** The GL context went away with the textures in it: forget them without deleting. */
    void contextLost() {
        slots.clear();
    }
}
