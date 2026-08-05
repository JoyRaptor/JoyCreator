package com.fadcam.ui.faditor.project;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Copy everything this project points at INTO the project, so it stops depending on files it does
 * not own.
 *
 * <p><b>Why.</b> {@code PLAN_asset_browser_v2_layers.md} calls this "the safety feature —
 * REQUIRED". New inserts have been self-contained for a while (copy-on-insert + {@code project://}
 * paths), but a project built before that, or one assembled from the gallery, still points at
 * files outside its own directory: a gallery video the user later deletes, a clip on an SD card, a
 * {@code content://} URI whose permission grant does not survive a reboot. {@link ProjectIntegrity}
 * now *detects* that; this is the half that *fixes* it.</p>
 *
 * <p><b>The safety property, and it is the whole design.</b> Copy first, repoint second, and
 * <b>never delete the original</b>. If a copy fails, that one reference is left exactly as it was —
 * a partially consolidated project is still a working project, just one that is partly still
 * dependent. There is no state in which the model points at a file that does not exist yet, because
 * the URI is only rewritten after the bytes are on disk and the size has been checked.</p>
 *
 * <p><b>Idempotent.</b> A reference already inside the project directory is skipped, so running it
 * twice copies nothing the second time and running it after adding one clip copies only that clip.
 * The destination name is derived from the URI, so the same source always maps to the same file
 * rather than accumulating duplicates.</p>
 */
public final class ProjectConsolidator {

    private ProjectConsolidator() {}

    public static final class Result {
        public int copied;
        public int alreadyLocal;
        public int failed;
        public long bytesCopied;
        /** True if anything changed and the project therefore needs saving. */
        public boolean changed() { return copied > 0; }
    }

    /**
     * @param mediaDir the project's own media directory; created if absent.
     * @return counts. The caller saves the project when {@link Result#changed()}.
     */
    @NonNull
    public static Result consolidate(@NonNull Context context, @Nullable FaditorProject project,
                                     @NonNull File mediaDir) {
        Result res = new Result();
        if (project == null || project.getTimeline() == null) return res;
        if (!mediaDir.exists() && !mediaDir.mkdirs()) return res;

        // Resolve each distinct source ONCE. Ten clips cut from one recording share a file; copying
        // it per clip would multiply a 2GB source by ten and could fill the device — the failure
        // this feature is supposed to prevent, caused by the feature itself.
        Map<String, Uri> resolved = new HashMap<>();
        Timeline tl = project.getTimeline();

        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            if (c == null) continue;
            Uri to = localise(context, c.getSourceUri(), mediaDir, resolved, res);
            if (to != null) c.repointConsolidatedSource(to);
        }
        for (Clip oc : tl.getOverlayClips()) {
            if (oc == null) continue;
            Uri to = localise(context, oc.getSourceUri(), mediaDir, resolved, res);
            if (to != null) oc.repointConsolidatedSource(to);
        }
        for (AudioClip ac : tl.getAudioClips()) {
            if (ac == null) continue;
            Uri to = localise(context, ac.getSourceUri(), mediaDir, resolved, res);
            if (to != null) ac.setSourceUri(to);
        }
        return res;
    }

    /**
     * @return the new local URI to point at, or null to leave the reference untouched (already
     *         local, or the copy failed — those two are deliberately indistinguishable to the
     *         caller, because the correct action for both is "change nothing").
     */
    @Nullable
    private static Uri localise(@NonNull Context context, @Nullable Uri src, @NonNull File mediaDir,
                                @NonNull Map<String, Uri> resolved, @NonNull Result res) {
        if (src == null) return null;
        String key = src.toString();
        if (key.isEmpty()) return null;
        if (resolved.containsKey(key)) return resolved.get(key);

        if (isInside(src, mediaDir)) {
            res.alreadyLocal++;
            resolved.put(key, null);
            return null;
        }

        File dest = new File(mediaDir, nameFor(src));
        if (dest.exists() && dest.length() > 0) {
            // A previous run already brought this one in. Repoint at it rather than re-copying.
            Uri u = Uri.fromFile(dest);
            resolved.put(key, u);
            res.alreadyLocal++;
            return u;
        }

        File tmp = new File(mediaDir, dest.getName() + ".part");
        long written = 0;
        try (InputStream in = open(context, src); OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new java.io.IOException("cannot open source");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); written += n; }
        } catch (Exception e) {
            // Leave the reference alone. Half a file must never become the thing the project
            // points at, so the partial is removed and the original stays authoritative.
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            res.failed++;
            resolved.put(key, null);
            return null;
        }
        // Rename only after the stream closed cleanly. Writing straight to `dest` would leave a
        // truncated file with the RIGHT name if the process died mid-copy, and the next run would
        // treat it as already consolidated.
        if (written <= 0 || !tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            res.failed++;
            resolved.put(key, null);
            return null;
        }
        res.copied++;
        res.bytesCopied += written;
        Uri u = Uri.fromFile(dest);
        resolved.put(key, u);
        return u;
    }

    @Nullable
    private static InputStream open(@NonNull Context context, @NonNull Uri uri) throws Exception {
        String scheme = uri.getScheme();
        if (scheme == null || "file".equals(scheme)) {
            String p = uri.getPath();
            return p == null ? null : new java.io.FileInputStream(p);
        }
        return context.getContentResolver().openInputStream(uri);
    }

    private static boolean isInside(@NonNull Uri uri, @NonNull File dir) {
        if (!"file".equals(uri.getScheme()) && uri.getScheme() != null) return false;
        String p = uri.getPath();
        if (p == null) return false;
        try {
            return new File(p).getCanonicalPath().startsWith(dir.getCanonicalPath());
        } catch (Exception e) {
            return p.startsWith(dir.getAbsolutePath());
        }
    }

    /**
     * Stable, collision-resistant, filesystem-safe name for a source. Derived from the URI so the
     * same source always maps to the same file — that is what makes re-running cheap instead of
     * duplicating. The hash carries uniqueness; the trailing readable fragment is only so a human
     * poking at the directory can tell what they are looking at.
     */
    @NonNull
    static String nameFor(@NonNull Uri uri) {
        String s = uri.toString();
        String tail = uri.getLastPathSegment();
        if (tail == null) tail = "media";
        tail = tail.replaceAll("[^A-Za-z0-9._-]", "_");
        if (tail.length() > 40) tail = tail.substring(tail.length() - 40);
        return Integer.toHexString(s.hashCode()) + "_" + tail;
    }
}
