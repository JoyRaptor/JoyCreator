package cc.joycreator.handoff;

import android.content.Context;
import android.opengl.GLES30;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.audio.Features;
import com.fadcam.vislab.gl.ShaderLib;
import com.fadcam.vislab.gl.VisRenderer;
import com.fadcam.vislab.model.VisScene;
import com.fadcam.vislab.model.VisTypes;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;


/**
 * The star field Joybot flies through in Peel and Carry: VisLab's own Lyric Flight look, run by
 * VisLab's own renderer inside the show, without its words (FX-1; GL thread only).
 *
 * <p>The owner, 16 Nov: "use the zooming field from his Labs lyric flight ... and then just have it
 * where the colors from those prefab preferences are going from one room's gradient to another
 * room's gradient." The look's five colours start as the leaving room's and are replaced one at a
 * time by the arriving room's, so stars of the new room appear among the old until they are all there
 * is. Nothing of VisLab's is changed: the look file is read, its words layer is left out here.</p>
 */
final class HandoffStars {

    private static final String TAG = "Handoff";
    private static final String LOOK = "vislab/scenes/lyric-flight.vis.json";

    private final VisRenderer vis;
    private final Features music = new Features();
    private final String[][] colours = new String[5][];
    private int shown = -1;
    private boolean broken;

    private HandoffStars(VisRenderer vis, int a1, int a2, int b1, int b2) {
        this.vis = vis;
        // a quiet, steady pulse: the look answers to music, and there is none here
        music.level = .65f; music.bass = .45f; music.low = .4f; music.mid = .35f; music.energy = .5f;
        String[] from = {"#FFFFFF", hex(a2), hex(a1), hex(mix(a1, a2, .5f)), hex(mix(a2, 0xFFFFFFFF, .35f))};
        String[] to = {"#FFFFFF", hex(b1), hex(b2), hex(mix(b1, b2, .5f)), hex(mix(b1, 0xFFFFFFFF, .35f))};
        for (int n = 0; n < colours.length; n++) {
            String[] stops = from.clone();
            for (int i = 0; i < n; i++) stops[4 - i] = to[4 - i];
            colours[n] = stops;
        }
    }

    /** With a GL context current. Null when VisLab's look cannot be started here: the show then draws its own plain field. */
    @Nullable
    static HandoffStars open(@NonNull Context app, int a1, int a2, int b1, int b2) {
        try {
            ShaderLib lib = ShaderLib.fromAssets(app.getAssets());
            VisTypes types = VisTypes.parse(read(app, "vislab/types.json"));
            JsonObject look = VisScene.parse(read(app, LOOK)).json();
            JsonArray layers = look.getAsJsonArray("layers"), kept = new JsonArray();
            for (int i = 0; i < layers.size(); i++) {
                JsonObject layer = layers.get(i).getAsJsonObject();
                if (!"words".equals(layer.has("type") ? layer.get("type").getAsString() : "")) kept.add(layer);
            }
            look.add("layers", kept);
            VisRenderer vis = new VisRenderer(lib, types);
            vis.setTransparent(false);
            vis.setScene(new VisScene(look));
            vis.init();
            return new HandoffStars(vis, a1, a2, b1, b2);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "Lyric Flight could not be started; the plain star field is used", e);
            return null;
        }
    }

    /**
     * Draw the field into {@code fbo}. {@code k}: 0 all the leaving room's colours .. 1 all the arriving
     * room's. False when it could not be drawn (the caller draws its own field instead, from then on).
     */
    boolean draw(float k, float t, float dt, int fbo, int w, int h) {
        if (broken) return false;
        try {
            int n = Math.max(0, Math.min(4, Math.round(k * 4f)));
            if (n != shown) { shown = n; vis.setBrandPalette(colours[n]); }
            vis.render(music, t, dt, fbo, w, h);
            if (vis.lastError() != null) throw new IllegalStateException(vis.lastError());
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Lyric Flight stopped drawing; the plain star field takes over", e);
            broken = true;
            return false;
        } finally {
            // VisLab's renderer leaves its own state behind: put back what the show's passes rely on
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
            GLES30.glDisable(GLES30.GL_CULL_FACE);
            GLES30.glColorMask(true, true, true, true);
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindVertexArray(0);
        }
    }

    void release() {
        try { vis.release(); } catch (RuntimeException ignored) { /* the context goes anyway */ }
    }

    private static String read(Context app, String path) throws Exception {
        return ShaderLib.readAll(app.getAssets().open(path));
    }

    private static int mix(int a, int b, float u) {
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * u);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * u);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * u);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static String hex(int c) {
        return String.format(java.util.Locale.US, "#%06X", c & 0xFFFFFF);
    }
}
