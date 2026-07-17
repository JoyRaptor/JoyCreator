package com.fadcam.ui.faditor.slides;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * Content-addressed cache for rendered slide artifacts, living under the
 * project's own directory so it travels with the project and is easy to nuke.
 *
 * <ul>
 *   <li>Fullscreen mode: a single MP4 named {@code <hash>.mp4}.</li>
 *   <li>Overlay mode: a directory {@code <hash>/} of PNG frames.</li>
 * </ul>
 *
 * <p>Cache entries are never the source of truth — they are always regenerable
 * from the authored HTML (see {@link com.fadcam.ui.faditor.model.GeneratedSource}).</p>
 */
public class SlideCache {

    public static final String CACHE_DIR_NAME = "slide_cache";

    @NonNull
    private final File cacheDir;

    public SlideCache(@NonNull File projectDir) {
        this.cacheDir = new File(projectDir, CACHE_DIR_NAME);
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    @NonNull
    public File getCacheDir() {
        return cacheDir;
    }

    /** Path (which may not yet exist) for a fullscreen slide's rendered MP4. */
    @NonNull
    public File mp4For(@NonNull String contentHash) {
        return new File(cacheDir, contentHash + ".mp4");
    }

    /**
     * Path for a fullscreen slide render baked for one specific trim/freeze
     * state. Distinct from {@link #mp4For}: two clips sharing the same authored
     * HTML (a split slide) must not clobber each other's stretch-mapped bakes,
     * so the state participates in the file identity.
     */
    @NonNull
    public File mp4ForState(@NonNull String contentHash, @NonNull String renderStateHash) {
        return new File(cacheDir, contentHash + "_" + shortHash(renderStateHash) + ".mp4");
    }

    /**
     * Delete cached slide MP4s that are in none of the given keep set — called
     * after a render pass with every live clip's current file, so stale
     * stretch-bakes don't pile up while shared-content siblings stay safe.
     */
    public void pruneMp4sExcept(@NonNull java.util.Set<String> keepNames) {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".mp4")
                    && !keepNames.contains(f.getName())) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** Stable 12-hex digest of an arbitrary state string, for file naming. */
    @NonNull
    public static String shortHash(@NonNull String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Directory (which may not yet exist) for an overlay slide's PNG frames. */
    @NonNull
    public File frameDirFor(@NonNull String contentHash) {
        return new File(cacheDir, contentHash);
    }

    /** @return the cached MP4 if present and non-empty, else null. */
    @Nullable
    public File getMp4(@NonNull String contentHash) {
        File f = mp4For(contentHash);
        return (f.isFile() && f.length() > 0) ? f : null;
    }

    /** @return the cached frame directory if it contains at least one frame, else null. */
    @Nullable
    public File getFrameDir(@NonNull String contentHash) {
        File dir = frameDirFor(contentHash);
        if (dir.isDirectory()) {
            File first = new File(dir, "frame0000.png");
            if (first.isFile()) return dir;
        }
        return null;
    }
}
