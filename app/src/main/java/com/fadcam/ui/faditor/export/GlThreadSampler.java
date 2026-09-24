package com.fadcam.ui.faditor.export;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Where the export's frame time goes, measured on the device during a real export.
 *
 * <p>Media3 runs every video effect of an export on ONE thread
 * ({@code Effect:DefaultVideoFrameProcessor:GlThread}), so that thread's time per frame is the
 * export's speed ceiling. This samples its stack ~100x a second and sorts each sample into a
 * named bucket (CPU image raster, captions, full-frame copies, CPU->GPU uploads, waiting on the
 * decoder/encoder...). A bucket's share of samples is its share of the frame budget.</p>
 *
 * <p>Why not the platform profiler: {@code am profile} could not write its trace for the
 * {@code :export} process (0-byte file, 2026-09-23) and simpleperf needs a security property
 * changed. This needs neither, and runs on every export at negligible cost.</p>
 */
final class GlThreadSampler {

    interface Sink { void line(@NonNull String s); }

    private static final String GL_THREAD = "DefaultVideoFrameProcessor:GlThread";
    private static final long PERIOD_MS = 10L;
    private static final long REPORT_MS = 20_000L;

    private final Sink sink;
    @Nullable private volatile Thread worker;
    /** Which thread to watch, and the report's tag. */
    private final String threadName;
    private final String label;
    /** Threads of that name that are NOT ours (another pipeline's), by id. */
    private final java.util.Set<Long> exclude = new java.util.HashSet<>();

    GlThreadSampler(@NonNull Sink sink) { this(sink, GL_THREAD, "GL_SAMPLE"); }

    /**
     * Sample another thread by name, e.g. the sound pass's "Transformer:Internal" (SOUND_SAMPLE).
     * Call {@link #excludeExisting()} just before the pipeline starts, so a same-named thread of
     * a pipeline already running (a video part) is not the one watched.
     */
    GlThreadSampler(@NonNull Sink sink, @NonNull String threadName, @NonNull String label) {
        this.sink = sink;
        this.threadName = threadName;
        this.label = label;
    }

    void excludeExisting() {
        exclude.clear();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().contains(threadName)) exclude.add(t.getId());
        }
    }

    void start() {
        stop();
        Thread t = new Thread(this::run, "export-gl-sampler");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    void stop() {
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
    }

    private void run() {
        Map<String, Integer> buckets = new HashMap<>();
        Map<String, Integer> other = new HashMap<>();
        Map<String, Integer> callers = new HashMap<>();
        int total = 0;
        long lastReport = System.currentTimeMillis();
        Thread gl = null;
        while (worker == Thread.currentThread()) {
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                break;
            }
            if (gl == null || !gl.isAlive()) gl = findGlThread();
            if (gl != null) {
                StackTraceElement[] st = gl.getStackTrace();
                String b = classify(gl.getState(), st);
                if (b.startsWith("other:")) other.merge(b.substring(6), 1, Integer::sum);
                buckets.merge(b.startsWith("other:") ? "other" : b, 1, Integer::sum);
                if (!b.equals("idleWaiting")) {
                    String kind = b.startsWith("other:") ? b.substring(6) : b;
                    callers.merge(kind + "<-" + appCaller(st), 1, Integer::sum);
                }
                total++;
            }
            long now = System.currentTimeMillis();
            if (now - lastReport >= REPORT_MS && total > 0) {
                report(buckets, other, callers, total);
                buckets.clear();
                other.clear();
                callers.clear();
                total = 0;
                lastReport = now;
            }
        }
        if (total > 0) report(buckets, other, callers, total);
    }

    private void report(@NonNull Map<String, Integer> buckets,
                        @NonNull Map<String, Integer> other,
                        @NonNull Map<String, Integer> callers, int total) {
        StringBuilder sb = new StringBuilder(label).append(" n=").append(total);
        buckets.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> sb.append(' ').append(e.getKey()).append('=')
                        .append(Math.round(100f * e.getValue() / total)).append('%'));
        if (!other.isEmpty()) {
            sb.append(" | other top:");
            other.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(6)
                    .forEach(e -> sb.append(' ').append(e.getKey()).append('=')
                            .append(Math.round(100f * e.getValue() / total)).append('%'));
        }
        if (!callers.isEmpty()) {
            // WHO asked for it: a bucket names the kind of work (a bitmap draw, an upload), the
            // caller names the feature. "canvasBlit=17%" alone could not say which one.
            sb.append(" | by caller:");
            callers.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(8)
                    .forEach(e -> sb.append(' ').append(e.getKey()).append('=')
                            .append(Math.round(100f * e.getValue() / total)).append('%'));
        }
        sink.line(sb.toString());
    }

    /** The first app frame on the stack (else the first media3 one): the feature doing the work. */
    @NonNull
    static String appCaller(@NonNull StackTraceElement[] st) {
        String media3 = null;
        for (StackTraceElement f : st) {
            String c = f.getClassName();
            if (c.startsWith("com.fadcam")) {
                return c.substring(c.lastIndexOf('.') + 1) + "." + f.getMethodName();
            }
            if (media3 == null && c.startsWith("androidx.media3")) {
                media3 = c.substring(c.lastIndexOf('.') + 1) + "." + f.getMethodName();
            }
        }
        return media3 != null ? media3 : "?";
    }

    @Nullable
    private Thread findGlThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().contains(threadName) && !exclude.contains(t.getId())) return t;
        }
        return null;
    }

    /** First recognised frame from the top of the stack names the bucket. */
    @NonNull
    static String classify(@NonNull Thread.State state, @NonNull StackTraceElement[] st) {
        for (StackTraceElement f : st) {
            String c = f.getClassName();
            String m = f.getMethodName();
            if (c.endsWith("GLUtils") || (c.endsWith("GlUtil") && m.startsWith("setTexture"))
                    || m.startsWith("glTexImage2D") || m.startsWith("glTexSubImage2D")) {
                return "upload";
            }
            if (c.equals("android.graphics.Bitmap") && (m.startsWith("createBitmap")
                    || m.startsWith("nativeCopy") || m.equals("copy"))) {
                return "bitmapCopy";
            }
            if (c.endsWith("ImageOverlayDraw") && m.equals("decode")) return "imageDecode";
            if (c.endsWith("ImageOverlayDraw")) return "imageDrawCpu";
            if (c.contains("MaskPathBuilder")) return "maskCpu";
            if (c.endsWith("CaptionExportRenderer")) return "captionsCpu";
            if (c.contains("TextOverlayRenderer") || c.contains("TextBoxRenderer")) {
                return "textCpu";
            }
            if (c.contains("WaveformStyleRenderer")) return "waveformCpu";
            if (c.contains("Sprite")) return "spriteCpu";
            if (c.contains("MeshStamp")) return "meshGl";
            if (c.equals("android.graphics.Canvas") || c.equals("android.graphics.BaseCanvas")) {
                if (m.startsWith("drawColor") || m.startsWith("drawRGB")) return "canvasClear";
                if (m.startsWith("drawBitmap")) return "canvasBlit";
            }
            if (m.startsWith("glDrawArrays") || m.startsWith("glFinish")
                    || m.startsWith("eglSwapBuffers") || m.startsWith("glReadPixels")) {
                return "glDraw";
            }
            if (c.startsWith("com.fadcam")) {
                String s = c.substring(c.lastIndexOf('.') + 1);
                return "other:" + s + "." + m;
            }
        }
        if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
                || state == Thread.State.BLOCKED) {
            return "idleWaiting";
        }
        if (st.length > 0) {
            String c = st[0].getClassName();
            return "other:" + c.substring(c.lastIndexOf('.') + 1) + "." + st[0].getMethodName();
        }
        return "unknown";
    }
}
