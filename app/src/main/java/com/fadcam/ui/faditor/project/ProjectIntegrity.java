package com.fadcam.ui.faditor.project;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Does every file this project points at still exist and still open?
 *
 * <p><b>Why this exists.</b> Nothing checked. A project whose source video was deleted, moved, or
 * had its permission grant revoked opened looking completely normal — the clips are still on the
 * timeline, still the right length, still showing their cached thumbnails, because the thumbnails
 * live in the project directory rather than in the source. The user found out at PREVIEW (a black
 * clip) or, worse, at EXPORT, after committing render time. The information existed at open and was
 * simply never asked for. Audit 3.4 calls the packaging half of this "the safety feature —
 * REQUIRED"; this is the half that needs no UI to be worth having.</p>
 *
 * <p><b>It reports; it never repairs.</b> Re-pointing a clip at a guessed replacement, or dropping
 * a clip whose file is missing, are both irreversible and both guesses. A missing file is very
 * often TEMPORARY — an unmounted SD card, a cloud provider that has not finished signing in, a USB
 * transfer in progress — and "helpfully" pruning the timeline in that state destroys the project
 * for a condition that would have cleared itself. So this answers a question and stops.</p>
 */
public final class ProjectIntegrity {

    private ProjectIntegrity() {}

    /** One unreadable reference. {@code label} is for humans; {@code uri} is for the log. */
    public static final class Missing {
        public final String label;
        public final String uri;
        Missing(String label, String uri) { this.label = label; this.uri = uri; }
    }

    public static final class Report {
        public final List<Missing> missing = new ArrayList<>();
        public int checked;
        /** Distinct sources checked — one file backing ten clips is one problem, not ten. */
        public int distinctSources;
        public boolean isClean() { return missing.isEmpty(); }
    }

    /**
     * Every distinct media URI this project depends on, mapped to a human label.
     *
     * <p>Deliberately keyed BY URI: a 45-minute recording split into thirty clips is one file, and
     * reporting it thirty times would bury a second, genuinely different missing file in the noise.
     * The label keeps the first clip that referenced it so the user can find it on the timeline.</p>
     *
     * <p>Android-free apart from {@link Uri#toString()}, so the collection half is testable in the
     * JVM harness while only the existence check needs a device.</p>
     */
    @NonNull
    public static Map<String, String> collectMediaUris(@Nullable FaditorProject project) {
        Map<String, String> out = new LinkedHashMap<>();
        if (project == null || project.getTimeline() == null) return out;
        Timeline tl = project.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            put(out, tl.getClip(i) != null ? tl.getClip(i).getSourceUri() : null, "Clip " + (i + 1));
        }
        int n = 1;
        for (Clip oc : tl.getOverlayClips()) {
            put(out, oc != null ? oc.getSourceUri() : null, "Overlay " + (n++));
        }
        n = 1;
        for (AudioClip ac : tl.getAudioClips()) {
            put(out, ac != null ? ac.getSourceUri() : null, "Audio " + (n++));
        }
        return out;
    }

    private static void put(@NonNull Map<String, String> out, @Nullable Uri uri, String label) {
        if (uri == null) return;
        String key = uri.toString();
        if (key.isEmpty()) return;
        // First label wins — see the by-URI note above.
        if (!out.containsKey(key)) out.put(key, label);
    }

    /**
     * Open each distinct source briefly. Opening is the only honest test: a {@code content://} URI
     * can be listed by a provider and still fail to open because the persisted permission grant was
     * dropped, which is one of the most common ways a project breaks and is invisible to an
     * existence check.
     */
    @NonNull
    public static Report scan(@NonNull Context context, @Nullable FaditorProject project) {
        Report r = new Report();
        Map<String, String> uris = collectMediaUris(project);
        r.distinctSources = uris.size();
        for (Map.Entry<String, String> e : uris.entrySet()) {
            r.checked++;
            if (!canOpen(context, e.getKey())) r.missing.add(new Missing(e.getValue(), e.getKey()));
        }
        return r;
    }

    private static boolean canOpen(@NonNull Context context, @NonNull String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            String scheme = uri.getScheme();
            if (scheme == null || "file".equals(scheme)) {
                String path = uri.getPath();
                return path != null && new java.io.File(path).canRead();
            }
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                return in != null;
            }
        } catch (Exception e) {
            // SecurityException (grant revoked), FileNotFoundException (deleted), and a provider
            // that simply throws all mean the same thing to the user: this will not play.
            return false;
        }
    }
}
