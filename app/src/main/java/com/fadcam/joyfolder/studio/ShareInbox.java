package com.fadcam.joyfolder.studio;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-off between "Share to Joy Creator" and the Studio editor.
 *
 * <p>The editor says which project it has open while it is alive; the share card puts files for
 * that project here and brings the editor to the front; the editor takes them on resume and inserts
 * them at the playhead with its own insert path (the asset browser's), so undo, saving and the
 * folder copy all behave as for any other insert.</p>
 */
public final class ShareInbox {

    private ShareInbox() {}

    private static volatile String openProjectId;
    private static volatile String openProjectName;
    private static final Map<String, List<Uri>> PENDING = new HashMap<>();

    /** The editor has this project open (called on resume). */
    public static void setOpenProject(@NonNull String projectId, @Nullable String name) {
        openProjectId = projectId;
        openProjectName = name;
    }

    /** The editor for this project is gone (called on destroy). */
    public static void clearOpenProject(@NonNull String projectId) {
        if (projectId.equals(openProjectId)) {
            openProjectId = null;
            openProjectName = null;
        }
    }

    @Nullable
    public static String openProjectId() {
        return openProjectId;
    }

    @Nullable
    public static String openProjectName() {
        return openProjectName;
    }

    /** Queue files for a project's timeline. */
    public static void put(@NonNull String projectId, @NonNull List<Uri> uris) {
        synchronized (PENDING) {
            List<Uri> list = PENDING.get(projectId);
            if (list == null) PENDING.put(projectId, list = new ArrayList<>());
            list.addAll(uris);
        }
    }

    /** Files waiting for this project, in the order they were shared; empty when none. */
    @NonNull
    public static List<Uri> take(@NonNull String projectId) {
        synchronized (PENDING) {
            List<Uri> list = PENDING.remove(projectId);
            return list == null ? new ArrayList<>() : list;
        }
    }
}
