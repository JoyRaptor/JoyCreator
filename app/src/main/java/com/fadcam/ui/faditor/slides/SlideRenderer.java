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

    /**
     * Trim ceiling for slide clips (JoyRaptor 2026-07-16): a slide can be stretched
     * up to this long — the animation time-remaps to fill whatever window the
     * trim bars define, so the ceiling is a UX constant, not the authored length.
     */
    public static final long SLIDE_MAX_DURATION_MS = 30_000L;

    private SlideRenderer() { }

    /**
     * Map a slide clip's SOURCE-time position to the authored animation time
     * (JoyRaptor 2026-07-16 stretch + freeze zones). The animated window runs from
     * {@code in + freezeStartMs} to {@code out - freezeEndMs}; before it the
     * first frame holds, after it the last frame holds, and inside it the
     * authored timeline is linearly stretched to fill it exactly.
     */
    public static long mapSourceToAnimMs(@NonNull Clip clip, long sourceMs) {
        GeneratedSource gs = clip.getGeneratedSource();
        if (gs == null) return sourceMs;
        long authored = Math.max(1, gs.authoredDurationMs);
        long a = clip.getInPointMs() + Math.max(0, gs.freezeStartMs);
        long b = clip.getOutPointMs() - Math.max(0, gs.freezeEndMs);
        if (b <= a) return sourceMs < b ? 0 : authored;
        if (sourceMs <= a) return 0;
        if (sourceMs >= b) return authored;
        return Math.round((double) authored * (sourceMs - a) / (b - a));
    }

    /**
     * Stamp of everything the rendered MP4 depends on beyond the HTML itself:
     * the trim window and freeze zones the stretch mapping bakes in. Compared
     * against {@link GeneratedSource#renderStateHash} to detect stale renders.
     */
    /**
     * Renderer/runtime revision folded into the state stamp: bumping it makes
     * every existing bake stale so runtime fixes (e.g. the true-duration seek
     * rescale) reach already-rendered slides.
     */
    private static final String RENDERER_REV = "r2";

    @NonNull
    public static String renderStateHash(@NonNull Clip clip, @NonNull GeneratedSource gs) {
        return gs.contentHash + "|" + clip.getInPointMs() + "|" + clip.getOutPointMs()
                + "|" + gs.freezeStartMs + "|" + gs.freezeEndMs + "|" + RENDERER_REV;
    }

    /** State stamp for a brand-new slide clip: untrimmed, no freeze zones. */
    @NonNull
    public static String initialRenderStateHash(@NonNull String contentHash, long durationMs) {
        return contentHash + "|0|" + durationMs + "|0|0|" + RENDERER_REV;
    }

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
            String state = renderStateHash(clip, gs);
            File baked = cache.mp4ForState(gs.contentHash, state);
            boolean missing = !(baked.isFile() && baked.length() > 0);
            boolean stale = !state.equals(gs.renderStateHash);
            boolean mispointed = clip.getSourceUri() == null
                    || !baked.getAbsolutePath().equals(clip.getSourceUri().getPath());
            if (missing || stale || mispointed) pending.add(clip);
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
     * Drop cached MP4s no live slide clip points at any more (old trim-state
     * bakes). Safe with shared authored HTML: every live clip's CURRENT file is
     * in the keep set.
     */
    public static void pruneCache(@NonNull File projectDir, @NonNull FaditorProject project) {
        try {
            SlideCache cache = new SlideCache(projectDir);
            java.util.Set<String> keep = new java.util.HashSet<>();
            for (Clip clip : project.getTimeline().getClips()) {
                GeneratedSource gs = clip.getGeneratedSource();
                if (gs == null || gs.contentHash == null) continue;
                keep.add(cache.mp4ForState(gs.contentHash,
                        renderStateHash(clip, gs)).getName());
                keep.add(cache.mp4For(gs.contentHash).getName());
            }
            cache.pruneMp4sExcept(keep);
        } catch (Exception ignored) { }
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
        String stateHash = renderStateHash(clip, gs);
        File baked = cache.mp4ForState(gs.contentHash, stateHash);
        if (baked.isFile() && baked.length() > 0
                && stateHash.equals(gs.renderStateHash)) {
            // Already rendered for this exact trim/freeze state — just make sure
            // the clip points at it (a re-opened project after a crash may not).
            clip.repointGeneratedSlideSource(android.net.Uri.fromFile(baked));
            gs.renderCacheUri = android.net.Uri.fromFile(baked).toString();
            return null;
        }

        int w = gs.width > 0 ? gs.width : 1080;
        int h = gs.height > 0 ? gs.height : 1920;
        long authoredMs = Math.max(100, gs.authoredDurationMs);
        // The MP4 covers source time 0..outPoint so the player's [in,out]
        // clipping window always lands on real frames; the stretch/freeze
        // mapping is baked in per frame (mapSourceToAnimMs).
        long totalMs = Math.max(100, clip.getOutPointMs());
        long animStartMs = clip.getInPointMs() + Math.max(0, gs.freezeStartMs);
        long animEndMs = clip.getOutPointMs() - Math.max(0, gs.freezeEndMs);

        // Frames are a throwaway intermediate for fullscreen mode; keep them in a
        // hash-scoped temp dir inside the cache so concurrent renders can't clash.
        File frameDir = new File(cache.getCacheDir(), "frames_" + gs.contentHash);
        try {
            FLog.i(TAG, "Rendering slide " + clip.getId() + " (" + w + "x" + h + ", "
                    + totalMs + "ms window, authored " + authoredMs + "ms, anim "
                    + animStartMs + ".." + animEndMs + " @" + RENDER_FPS + "fps)");
            SlideCaptureEngine.Result captured = new SlideCaptureEngine()
                    .capturePngSequence(context, html.getAbsolutePath(), w, h,
                            RENDER_FPS, authoredMs, totalMs, animStartMs, animEndMs,
                            frameDir);
            if (!captured.ok) {
                return "slide capture failed: " + captured.error;
            }
            boolean encoded = new SlideEncoder()
                    .encodePngSequenceToMp4(frameDir, baked, RENDER_FPS);
            if (!encoded) {
                return "slide encode failed";
            }
            gs.renderStateHash = stateHash;
            gs.renderCacheUri = android.net.Uri.fromFile(baked).toString();
            clip.repointGeneratedSlideSource(android.net.Uri.fromFile(baked));
            return null;
        } finally {
            deleteRecursive(frameDir);
        }
    }

    // ── Overlay (transparent) slides — spec Phase 4 ─────────────────

    /**
     * Stretch mapping for an overlay slide: the authored animation fills the
     * overlay's whole time range (start..end), analogous to a fullscreen
     * slide's trim window. No freeze zones for overlays (v1).
     */
    public static long mapOverlayToAnimMs(
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem o, long timelineMs) {
        GeneratedSource gs = o.getGeneratedSource();
        if (gs == null) return 0;
        long authored = Math.max(1, gs.authoredDurationMs);
        long a = o.getStartMs();
        long b = o.getEndMs();
        if (b <= a || b == Long.MAX_VALUE) return Math.min(authored,
                Math.max(0, timelineMs - a));
        if (timelineMs <= a) return 0;
        if (timelineMs >= b) return authored;
        return Math.round((double) authored * (timelineMs - a) / (b - a));
    }

    @NonNull
    public static String overlayRenderStateHash(
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem o, @NonNull GeneratedSource gs) {
        long span = (o.getEndMs() == Long.MAX_VALUE || o.getEndMs() <= o.getStartMs())
                ? gs.authoredDurationMs : o.getEndMs() - o.getStartMs();
        return gs.contentHash + "|ov|" + span + "|" + RENDERER_REV;
    }

    /** Overlay slides whose PNG frame sequence is missing or stale. */
    @NonNull
    public static List<com.fadcam.ui.faditor.model.TextOverlayItem> collectUnrenderedOverlays(
            @NonNull File projectDir, @NonNull FaditorProject project) {
        List<com.fadcam.ui.faditor.model.TextOverlayItem> pending = new ArrayList<>();
        SlideCache cache = new SlideCache(projectDir);
        for (com.fadcam.ui.faditor.model.TextOverlayItem o
                : project.getTimeline().getTextOverlays()) {
            GeneratedSource gs = o.getGeneratedSource();
            if (gs == null || gs.contentHash == null || gs.contentHash.isEmpty()) continue;
            String state = overlayRenderStateHash(o, gs);
            File dir = overlayFrameDir(cache, gs.contentHash, state);
            boolean missing = !new File(dir, "frame0000.png").isFile();
            boolean stale = !state.equals(gs.renderStateHash);
            if (missing || stale) pending.add(o);
        }
        return pending;
    }

    @NonNull
    private static File overlayFrameDir(@NonNull SlideCache cache, @NonNull String contentHash,
                                        @NonNull String state) {
        return cache.frameDirFor(contentHash + "_" + SlideCache.shortHash(state));
    }

    /** Render every pending overlay slide's PNG sequence, blocking. */
    @Nullable
    public static String renderAllOverlays(@NonNull Context context, @NonNull File projectDir,
            @NonNull List<com.fadcam.ui.faditor.model.TextOverlayItem> pending) {
        for (com.fadcam.ui.faditor.model.TextOverlayItem o : pending) {
            String err = renderOverlayOne(context, projectDir, o);
            if (err != null) return err;
        }
        return null;
    }

    @Nullable
    public static String renderOverlayOne(@NonNull Context context, @NonNull File projectDir,
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem overlay) {
        GeneratedSource gs = overlay.getGeneratedSource();
        if (gs == null) return "not a generated overlay";
        File html = fileFromUri(gs.htmlUri);
        if (html == null || !html.isFile()) return "slide HTML missing: " + gs.htmlUri;

        SlideCache cache = new SlideCache(projectDir);
        String state = overlayRenderStateHash(overlay, gs);
        File frameDir = overlayFrameDir(cache, gs.contentHash, state);
        if (new File(frameDir, "frame0000.png").isFile()
                && state.equals(gs.renderStateHash)) {
            gs.renderSequenceDir = android.net.Uri.fromFile(frameDir).toString();
            return null;
        }

        int w = gs.width > 0 ? gs.width : 1080;
        int h = gs.height > 0 ? gs.height : 1920;
        long authoredMs = Math.max(100, gs.authoredDurationMs);
        long span = (overlay.getEndMs() == Long.MAX_VALUE
                || overlay.getEndMs() <= overlay.getStartMs())
                ? authoredMs : overlay.getEndMs() - overlay.getStartMs();

        FLog.i(TAG, "Rendering overlay slide " + overlay.getId() + " (" + w + "x" + h
                + ", " + span + "ms window, authored " + authoredMs + "ms @"
                + RENDER_FPS + "fps)");
        SlideCaptureEngine.Result captured = new SlideCaptureEngine()
                .capturePngSequence(context, html.getAbsolutePath(), w, h,
                        RENDER_FPS, authoredMs, span, 0, span, frameDir);
        if (!captured.ok) return "overlay capture failed: " + captured.error;
        gs.renderSequenceDir = android.net.Uri.fromFile(frameDir).toString();
        gs.renderStateHash = state;
        return null;
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
