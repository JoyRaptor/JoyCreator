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
