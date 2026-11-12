package cc.joycreator.textlab;

import android.opengl.GLES30;
import android.util.Log;

import androidx.annotation.NonNull;

import com.fadcam.vislab.gl.GlProgram;

import java.util.HashMap;
import java.util.Map;

/**
 * A linked TextLab program that knows every uniform's type and size, so a knob is set by name with
 * the right call. The twin of {@code Program} in {@code tools/textlab-web/tl-gl.js}: a uniform the
 * shader does not have is skipped, and a value with the wrong count of numbers is refused with a
 * warning rather than half set.
 */
final class TlProgram {

    private static final String TAG = "TextLab";

    private static final class U {
        final int loc, type, size;

        U(int loc, int type, int size) {
            this.loc = loc;
            this.type = type;
            this.size = size;
        }
    }

    /** The shared noise texture, bound to unit 7 for every program that reads it. */
    static int noise;

    final GlProgram gl;
    final String label;
    private final Map<String, U> u = new HashMap<>();
    private final java.util.Set<String> warned = new java.util.HashSet<>();

    TlProgram(@NonNull String vs, @NonNull String fs, @NonNull String label) {
        this.gl = GlProgram.link(label, vs, fs);
        this.label = label;
        int[] count = new int[1];
        GLES30.glGetProgramiv(gl.id, GLES30.GL_ACTIVE_UNIFORMS, count, 0);
        int[] len = new int[1], size = new int[1], type = new int[1];
        byte[] name = new byte[256];
        for (int i = 0; i < count[0]; i++) {
            GLES30.glGetActiveUniform(gl.id, i, name.length, len, 0, size, 0, type, 0, name, 0);
            String full = new String(name, 0, len[0]);
            String key = full.endsWith("[0]") ? full.substring(0, full.length() - 3) : full;
            u.put(key, new U(GLES30.glGetUniformLocation(gl.id, full), type[0], size[0]));
        }
    }

    TlProgram use() {
        GLES30.glUseProgram(gl.id);
        U n = u.get("uNoise");
        if (n != null && noise != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE7);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noise);
            GLES30.glUniform1i(n.loc, 7);
        }
        return this;
    }

    boolean has(@NonNull String name) {
        return u.containsKey(name);
    }

    TlProgram set(@NonNull String name, @NonNull Object value) {
        U x = u.get(name);
        if (x == null) return this;
        float[] v = TlValues.flat(value);
        int need;
        switch (x.type) {
            case GLES30.GL_FLOAT: need = 1; break;
            case GLES30.GL_FLOAT_VEC2: need = 2; break;
            case GLES30.GL_FLOAT_VEC3: need = 3; break;
            case GLES30.GL_FLOAT_VEC4: need = 4; break;
            default: need = 0;
        }
        if (need > 0 && v.length != need * x.size) {
            // once per program and name: a knob that shares a uniform's name with a host-set value
            // (uArc: the lightning knob and a tube's share of its letter) is refused every frame, as in the look book
            if (warned.add(name)) Log.w(TAG, label + ": " + name + " wants " + need * x.size + " numbers, got " + v.length);
            return this;
        }
        switch (x.type) {
            case GLES30.GL_FLOAT: GLES30.glUniform1fv(x.loc, x.size, v, 0); break;
            case GLES30.GL_FLOAT_VEC2: GLES30.glUniform2fv(x.loc, x.size, v, 0); break;
            case GLES30.GL_FLOAT_VEC3: GLES30.glUniform3fv(x.loc, x.size, v, 0); break;
            case GLES30.GL_FLOAT_VEC4: GLES30.glUniform4fv(x.loc, x.size, v, 0); break;
            default: if (v.length > 0) GLES30.glUniform1i(x.loc, (int) v[0]);
        }
        return this;
    }

    /** Every uniform this program has that the map names. */
    TlProgram setAll(@NonNull Map<String, ?> map) {
        for (String k : u.keySet()) {
            Object v = map.get(k);
            if (v != null) set(k, v);
        }
        return this;
    }

    TlProgram tex(@NonNull String name, int texture, int unit) {
        U x = u.get(name);
        if (x == null) return this;
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glUniform1i(x.loc, unit);
        return this;
    }

    void delete() {
        gl.delete();
    }
}
