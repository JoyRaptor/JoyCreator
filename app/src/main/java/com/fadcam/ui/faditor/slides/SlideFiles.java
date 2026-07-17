package com.fadcam.ui.faditor.slides;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.FaditorProject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared filesystem + hashing helpers for AI-authored slides. Used by both the
 * EditScript applier (which provisions the HTML) and the export pre-pass (which
 * renders it). Keeps slide artifacts under the project's own directory.
 */
public final class SlideFiles {

    public static final String SLIDES_DIR_NAME = "slides";

    private SlideFiles() { }

    @NonNull
    public static File slidesDir(@NonNull File projectDir) {
        File d = new File(projectDir, SLIDES_DIR_NAME);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * Target slide pixel dimensions for a project. Slides pass through the same
     * canvas/Presentation scaling as other clips at export, so exact match isn't
     * required — these are aspect-correct defaults capped to a 1920px long edge.
     */
    @NonNull
    public static int[] dimensionsFor(@NonNull FaditorProject project) {
        String preset = normalizePreset(project.getCanvasPreset());
        int ratioW, ratioH;
        switch (preset) {
            case "16:9": ratioW = 16; ratioH = 9; break;
            case "1:1":  ratioW = 1;  ratioH = 1; break;
            case "4:3":  ratioW = 4;  ratioH = 3; break;
            case "3:4":  ratioW = 3;  ratioH = 4; break;
            case "4:5":  ratioW = 4;  ratioH = 5; break;
            case "21:9": ratioW = 21; ratioH = 9; break;
            case "9:16":
            default:     ratioW = 9;  ratioH = 16; break; // portrait phone default
        }
        int longEdge = 1920;
        int w, h;
        if (ratioW >= ratioH) {
            w = longEdge;
            h = Math.round((float) longEdge * ratioH / ratioW);
        } else {
            h = longEdge;
            w = Math.round((float) longEdge * ratioW / ratioH);
        }
        w = (w / 2) * 2;
        h = (h / 2) * 2;
        return new int[]{Math.max(2, w), Math.max(2, h)};
    }

    @NonNull
    private static String normalizePreset(@NonNull String preset) {
        return preset.replace('_', ':').trim();
    }

    /** Cache key: sha256 of HTML bytes + dimensions + requested duration. */
    @NonNull
    public static String contentHash(@NonNull String html, int width, int height,
                                     long durationMs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(html.getBytes(StandardCharsets.UTF_8));
            md.update(("|" + width + "x" + height + "|" + durationMs)
                    .getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // Fallback: never crash a slide insert over hashing.
            return Integer.toHexString((html + width + height + durationMs).hashCode());
        }
    }

    /** Write HTML to {@code <slidesDir>/<id>.html} and return the file. */
    @NonNull
    public static File writeHtml(@NonNull File projectDir, @NonNull String id,
                                 @NonNull String html) throws Exception {
        File file = new File(slidesDir(projectDir), id + ".html");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(html.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    /** Runtime scripts every slide references relatively. */
    private static final String[] RUNTIME_ASSETS = {"gsap.min.js", "faditor_runtime.js"};

    /**
     * Copy the vendored runtime scripts from app assets into {@code dir} (the
     * folder holding a slide's HTML) so the HTML can be loaded via
     * {@code file://} with its OWN directory as base — which is what lets
     * relative {@code <img src="...">} refs resolve to files the user drops
     * next to the HTML. Cheap and idempotent: skips files whose size matches.
     */
    public static void ensureRuntimeIn(@NonNull android.content.Context context,
                                       @NonNull File dir) {
        if (!dir.exists() && !dir.mkdirs()) return;
        for (String name : RUNTIME_ASSETS) {
            File out = new File(dir, name);
            try (java.io.InputStream is = context.getAssets().open("faditor/" + name)) {
                long assetLen = is.available();
                if (out.isFile() && out.length() == assetLen && assetLen > 0) continue;
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                }
            } catch (Exception ignored) {
                // Preview/render falls back to broken script refs only if assets
                // are unreadable, which would break far more than slides.
            }
        }
    }
}
