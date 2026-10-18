package com.fadcam.vislab.gl;

import android.opengl.GLES30;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Times the stages of a frame on the GPU (GL_EXT_disjoint_timer_query), without stalling it:
 * each stage's query is read back a few frames later. Used in debug builds to find where a look
 * spends its time; costs nothing when the phone has no timer queries or profiling is off.
 */
final class GpuTimer {

    private static final int GL_TIME_ELAPSED = 0x88BF;
    private static final int GL_GPU_DISJOINT = 0x8FBB;
    private static final int RING = 4;

    private final String[] names;
    private final int[] ids;
    private final boolean[] pending;
    private final double[] sum;
    private int frames;
    private int slot;
    private int open = -1;
    private final boolean ok;

    GpuTimer(@NonNull String... stages) {
        names = stages;
        ids = new int[RING * stages.length];
        pending = new boolean[ids.length];
        sum = new double[stages.length];
        String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        ok = ext != null && ext.contains("GL_EXT_disjoint_timer_query");
        if (ok) GLES30.glGenQueries(ids.length, ids, 0);
    }

    boolean available() {
        return ok;
    }

    /** Start timing stage {@code i} (ends any stage still open). */
    void begin(int i) {
        if (!ok) return;
        end();
        int q = slot * names.length + i;
        if (pending[q]) return;   // still unread from RING frames ago: skip, never stall
        GLES30.glBeginQuery(GL_TIME_ELAPSED, ids[q]);
        pending[q] = true;
        open = q;
    }

    void end() {
        if (!ok || open < 0) return;
        GLES30.glEndQuery(GL_TIME_ELAPSED);
        open = -1;
    }

    /** Call once at the end of a frame: collects results that are ready, moves to the next slot. */
    void frameDone() {
        if (!ok) return;
        end();
        int[] v = new int[1];
        GLES30.glGetIntegerv(GL_GPU_DISJOINT, v, 0);
        boolean disjoint = v[0] != 0;
        int next = (slot + 1) % RING;
        for (int i = 0; i < names.length; i++) {
            int q = next * names.length + i;
            if (!pending[q]) continue;
            GLES30.glGetQueryObjectuiv(ids[q], GLES30.GL_QUERY_RESULT_AVAILABLE, v, 0);
            if (v[0] == 0) continue;
            GLES30.glGetQueryObjectuiv(ids[q], GLES30.GL_QUERY_RESULT, v, 0);
            pending[q] = false;
            if (!disjoint) sum[i] += (v[0] & 0xFFFFFFFFL) / 1e6;
        }
        frames++;
        slot = next;
    }

    /** "living 0.4 layers 6.1 bloom 1.2 finish 3.3 (GPU ms)" averaged since the last call; resets. */
    @NonNull
    String report() {
        if (!ok || frames == 0) return "";
        StringBuilder b = new StringBuilder("gpu");
        double total = 0;
        for (int i = 0; i < names.length; i++) {
            double ms = sum[i] / frames;
            total += ms;
            b.append(' ').append(names[i]).append(' ').append(String.format(Locale.ROOT, "%.1f", ms));
            sum[i] = 0;
        }
        b.append(" = ").append(String.format(Locale.ROOT, "%.1f", total)).append("ms");
        frames = 0;
        return b.toString();
    }

    void release() {
        if (ok) GLES30.glDeleteQueries(ids.length, ids, 0);
    }
}
