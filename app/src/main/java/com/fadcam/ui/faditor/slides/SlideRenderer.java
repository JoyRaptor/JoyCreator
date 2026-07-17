package com.fadcam.ui.faditor.slides;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.GeneratedSource;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders AI-authored slides (HTML → PNG frames → MP4) into the project's
 * content-addressed {@link SlideCache}, so a slide clip's {@code sourceUri}
 * points at a real file for playback and export.
 *
 * <p>This is the spec's {@code ensureGeneratedSlidesRendered} pre-pass, but it
 * lives in the editor process rather than {@code ExportManager}: the exporter
 * runs in the separate {@code :export} process, where the WebView capture can't
 * run (Android forbids one WebView data dir across two processes, and
 * {@link SlideCaptureEngine}'s completion latch is in-process). The cache is a
 * plain file under the project dir, so rendering here and exporting there
 * compose cleanly.</p>
 *
 * <p>All render methods block and must be called off the main thread.</p>
 */
public final class SlideRenderer {

    private static final String TAG = "SlideRenderer";

    /** Slide render framerate — matches the export pipeline's common framerate. */
    public static final int RENDER_FPS = 30;

    private SlideRenderer() { }

    /**
     * All fullscreen slide clips whose rendered MP4 is missing from the cache.
     * Cheap (filesystem stat per slide clip) — callable from any thread.
     */
    @NonNull
    public static List<Clip> collectUnrendered(@NonNull File projectDir,
                                               @NonNull FaditorProject project) {
        List<Clip> pending = new ArrayList<>();
        SlideCache cache = new SlideCache(projectDir);
        for (Clip clip : project.getTimeline().getClips()) {
            GeneratedSource gs = clip.getGeneratedSource();
            if (gs == null || !SlideContract.MODE_FULLSCREEN.equals(gs.mode)) continue;
            if (gs.contentHash == null || gs.contentHash.isEmpty()) continue;
            if (cache.getMp4(gs.contentHash) == null) pending.add(clip);
        }
        return pending;
    }

    /**
     * Render every pending slide, blocking until done.
     *
     * @return null on success, else a one-line reason the render failed.
     */
    @Nullable
    public static String renderAll(@NonNull Context context, @NonNull File projectDir,
                                   @NonNull List<Clip> pending) {
        for (Clip clip : pending) {
            String error = renderOne(context, projectDir, clip);
            if (error != null) return error;
        }
        return null;
    }

    /**
     * Render one slide clip's HTML into its content-addressed MP4.
     *
     * @return null on success, else a one-line reason.
     */
    @Nullable
    public static String renderOne(@NonNull Context context, @NonNull File projectDir,
                                   @NonNull Clip clip) {
        GeneratedSource gs = clip.getGeneratedSource();
        if (gs == null) return "not a generated slide";

        File html = fileFromUri(gs.htmlUri);
        if (html == null || !html.isFile()) {
            return "slide HTML missing: " + gs.htmlUri;
        }

        SlideCache cache = new SlideCache(projectDir);
        if (cache.getMp4(gs.contentHash) != null) return null; // already rendered

        int w = gs.width > 0 ? gs.width : 1080;
        int h = gs.height > 0 ? gs.height : 1920;
        long durationMs = Math.max(100, gs.authoredDurationMs);

        // Frames are a throwaway intermediate for fullscreen mode; keep them in a
        // hash-scoped temp dir inside the cache so concurrent renders can't clash.
        File frameDir = new File(cache.getCacheDir(), "frames_" + gs.contentHash);
        try {
            FLog.i(TAG, "Rendering slide " + clip.getId() + " (" + w + "x" + h + ", "
                    + durationMs + "ms @" + RENDER_FPS + "fps)");
            SlideCaptureEngine.Result captured = new SlideCaptureEngine()
                    .capturePngSequence(context, html.getAbsolutePath(), w, h,
                            RENDER_FPS, durationMs, frameDir);
            if (!captured.ok) {
                return "slide capture failed: " + captured.error;
            }
            File outMp4 = cache.mp4For(gs.contentHash);
            boolean encoded = new SlideEncoder()
                    .encodePngSequenceToMp4(frameDir, outMp4, RENDER_FPS);
            if (!encoded) {
                return "slide encode failed";
            }
            return null;
        } finally {
            deleteRecursive(frameDir);
        }
    }

    @Nullable
    private static File fileFromUri(@Nullable String uri) {
        if (uri == null || uri.isEmpty()) return null;
        if (uri.startsWith("file://")) {
            String p = android.net.Uri.parse(uri).getPath();
            return p != null ? new File(p) : null;
        }
        return new File(uri);
    }

    private static void deleteRecursive(@NonNull File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
