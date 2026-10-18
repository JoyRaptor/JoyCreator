package com.fadcam.joyfolder.studio;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.project.ProjectIntegrity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The files a project uses that live OUTSIDE the Joy Creator folder, and the Copy/Move guess for
 * each. One answer for the editor (whether Joybot breathes yellow) and for Joybot (what he says),
 * so the warning and the conversation can never disagree.
 *
 * <p>The guess is the owner's 10-14 rule (was BringInDialog's): a recording this app made MOVES
 * in (no stray copy left on the phone); anything else is COPIED (the original stays put).</p>
 */
public final class OutsideFiles {
    private OutsideFiles() { }

    /** One outside file. */
    public static final class File {
        @NonNull public final String ref;
        @NonNull public final String name;
        /** Bytes, or -1 when unknown. */
        public final long size;
        /** True: move it in (made here). False: copy it in. */
        public final boolean guessMove;

        File(@NonNull String ref, @NonNull String name, long size, boolean guessMove) {
            this.ref = ref; this.name = name; this.size = size; this.guessMove = guessMove;
        }
    }

    /** Every media link the project holds: clips, overlays, audio, image overlays. */
    @NonNull
    public static Set<String> mediaRefs(@NonNull FaditorProject project) {
        Set<String> refs = new LinkedHashSet<>(ProjectIntegrity.collectMediaUris(project).keySet());
        // Pictures placed as image overlays (the IMG track) keep their link on the overlay.
        for (TextOverlayItem o : project.getTimeline().getTextOverlays()) {
            if (o != null && o.getImageUri() != null && !o.getImageUri().isEmpty()) refs.add(o.getImageUri());
        }
        return refs;
    }

    /**
     * The outside links the person hasn't chosen to leave (cheap: string checks and a stat). Empty
     * when no folder is chosen.
     */
    @NonNull
    public static List<String> refs(@NonNull Context context, @NonNull FaditorProject project) {
        List<String> out = new ArrayList<>();
        if (!JoyFolderHome.isChosen(context)) return out;
        Set<String> left = left(context, project.getId());
        for (String ref : mediaRefs(project)) {
            if (!left.contains(ref) && StudioMirror.isOutside(context, ref)) out.add(ref);
        }
        return out;
    }

    // ── "Leave them": a decision, remembered, so Joybot stops glowing about those files ──

    private static final String PREFS = "joybot_outside_left";

    /** The person said to leave these files where they are, for this project. */
    public static void leave(@NonNull Context context, @NonNull String projectId, @NonNull List<String> refs) {
        Set<String> s = left(context, projectId);
        s.addAll(refs);
        prefs(context).edit().putStringSet(projectId, s).apply();
    }

    @NonNull
    private static Set<String> left(@NonNull Context context, @NonNull String projectId) {
        Set<String> s = prefs(context).getStringSet(projectId, null);
        return s == null ? new java.util.HashSet<String>() : new java.util.HashSet<>(s);
    }

    private static android.content.SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The outside files with names, sizes and guesses. Reads file details: not on the main thread. */
    @WorkerThread
    @NonNull
    public static List<File> list(@NonNull Context context, @NonNull FaditorProject project) {
        List<File> out = new ArrayList<>();
        for (String ref : refs(context, project)) {
            String name = StudioMirror.nameOf(context, ref);
            out.add(new File(ref, name, StudioMirror.sizeOf(context, ref), madeHere(name)));
        }
        return out;
    }

    /** A recording this app made (its own naming), which should MOVE rather than be copied. */
    public static boolean madeHere(@NonNull String name) {
        return name.startsWith("FadCam_") || com.fadcam.utils.RecordingStoragePaths.JoyKind.ofName(name) != null;
    }
}
