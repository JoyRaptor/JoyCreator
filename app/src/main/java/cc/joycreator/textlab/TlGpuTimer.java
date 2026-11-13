package cc.joycreator.textlab;

import android.opengl.GLES30;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bench's stopwatch for each step of a frame, timed by the GPU itself (EXT_disjoint_timer_query). Off unless the
 * bench screen switches it on; the engine's {@link #mark} calls then cost nothing. GL thread only.
 */
final class TlGpuTimer {

    private static final int TIME_ELAPSED = 0x88BF, GPU_DISJOINT = 0x8FBB;

    /** The one stopwatch, or null (the usual: nothing timed). */
    static TlGpuTimer on;

    private final List<String> names = new ArrayList<>();
    private final List<Integer> ids = new ArrayList<>();
    private final Map<String, double[]> sums = new LinkedHashMap<>();   // name -> {ms, frames}
    private boolean open;
    private int frames;

    /** Null when this GPU has no timer. */
    static TlGpuTimer start() {
        String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        if (ext == null || !ext.contains("GL_EXT_disjoint_timer_query")) return null;
        return new TlGpuTimer();
    }

    /** The engine: a new step of the frame starts here (the step before ends). */
    static void mark(@NonNull String step) {
        TlGpuTimer t = on;
        if (t == null) return;
        if (t.open) GLES30.glEndQuery(TIME_ELAPSED);
        int[] id = new int[1];
        GLES30.glGenQueries(1, id, 0);
        GLES30.glBeginQuery(TIME_ELAPSED, id[0]);
        t.names.add(step);
        t.ids.add(id[0]);
        t.open = true;
    }

    /** The engine: the frame's last step ends. */
    static void end() {
        TlGpuTimer t = on;
        if (t == null || !t.open) return;
        GLES30.glEndQuery(TIME_ELAPSED);
        t.open = false;
    }

    /** After glFinish: add this frame's steps to the sums (a frame the GPU says was disturbed is left out). */
    void collect() {
        end();
        int[] d = new int[1];
        GLES30.glGetIntegerv(GPU_DISJOINT, d, 0);
        Map<String, Double> frame = new LinkedHashMap<>();
        int[] v = new int[1];
        for (int i = 0; i < ids.size(); i++) {
            GLES30.glGetQueryObjectuiv(ids.get(i), GLES30.GL_QUERY_RESULT, v, 0);
            double ms = (v[0] & 0xFFFFFFFFL) / 1e6;
            frame.merge(names.get(i), ms, Double::sum);
        }
        int[] all = new int[ids.size()];
        for (int i = 0; i < all.length; i++) all[i] = ids.get(i);
        if (all.length > 0) GLES30.glDeleteQueries(all.length, all, 0);
        ids.clear();
        names.clear();
        if (d[0] != 0 || frame.isEmpty()) return;
        frames++;
        for (Map.Entry<String, Double> e : frame.entrySet()) {
            double[] s = sums.computeIfAbsent(e.getKey(), k -> new double[2]);
            s[0] += e.getValue();
            s[1]++;
        }
    }

    /** The average of each step since the last report, then start again. */
    @NonNull
    String report() {
        StringBuilder b = new StringBuilder();
        double total = 0;
        for (Map.Entry<String, double[]> e : sums.entrySet()) {
            double ms = e.getValue()[0] / Math.max(frames, 1);
            total += ms;
            b.append(String.format(Locale.ROOT, " %s=%.2f", e.getKey(), ms));
        }
        String s = String.format(Locale.ROOT, "gpu %.2f ms over %d frames:", total, frames) + b;
        sums.clear();
        frames = 0;
        return s;
    }
}
