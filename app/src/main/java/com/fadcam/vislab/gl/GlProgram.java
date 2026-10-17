package com.fadcam.vislab.gl;

import android.opengl.GLES30;

import androidx.annotation.NonNull;

import java.util.HashMap;

/**
 * A linked VisLab program with its uniform locations cached by name. The Audio uniform block
 * is bound to slot 0 at link time. A uniform the driver optimised away reports -1, and every
 * setter here treats -1 as "not used by this shader" (GL defines that as a no-op anyway).
 */
final class GlProgram {

    final int id;
    @NonNull final String name;
    private final HashMap<String, Integer> loc = new HashMap<>();

    private GlProgram(int id, @NonNull String name) {
        this.id = id;
        this.name = name;
    }

    int u(@NonNull String uniform) {
        Integer l = loc.get(uniform);
        if (l == null) {
            l = GLES30.glGetUniformLocation(id, uniform);
            loc.put(uniform, l);
        }
        return l;
    }

    void delete() {
        GLES30.glDeleteProgram(id);
    }

    @NonNull
    static GlProgram link(@NonNull String name, @NonNull String vertSrc, @NonNull String fragSrc) {
        int vs = compile(GLES30.GL_VERTEX_SHADER, name, vertSrc);
        int fs;
        try {
            fs = compile(GLES30.GL_FRAGMENT_SHADER, name, fragSrc);
        } catch (RuntimeException e) {
            GLES30.glDeleteShader(vs);
            throw e;
        }
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, vs);
        GLES30.glAttachShader(p, fs);
        GLES30.glLinkProgram(p);
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(p);
            GLES30.glDeleteProgram(p);
            throw new IllegalStateException(name + " link: " + log);
        }
        int block = GLES30.glGetUniformBlockIndex(p, "Audio");
        if (block != GLES30.GL_INVALID_INDEX) GLES30.glUniformBlockBinding(p, block, 0);
        return new GlProgram(p, name);
    }

    private static int compile(int type, @NonNull String name, @NonNull String src) {
        int sh = GLES30.glCreateShader(type);
        GLES30.glShaderSource(sh, src);
        GLES30.glCompileShader(sh);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(sh);
            GLES30.glDeleteShader(sh);
            throw new IllegalStateException(name + (type == GLES30.GL_VERTEX_SHADER ? " (vertex): " : ": ") + log);
        }
        return sh;
    }
}
