package com.fadcam.joyfolder.studio;

import android.content.Context;
import android.util.Log;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.model.TrashItem;

import java.util.ArrayList;
import java.util.List;

/**
 * One Trash for the user. The app's Trash screen ({@code TrashFragment}) shows recordings from the
 * app's own Trash folder; this adds everything deleted from the Joy Creator folder (projects, and
 * later drawings and any other file) to the same list, and routes Put back / Delete / Empty to it.
 *
 * <p>Folder items keep living in {@code .joycreator/Trash} inside the user's folder (so they survive
 * an uninstall, and JoyBrush can trash into it without depending on the app). They appear here as
 * {@link TrashItem}s whose trash name starts with {@link #PREFIX}; nothing else changes about how the
 * Trash screen holds its list.</p>
 */
public final class JoyTrashBridge {

    private static final String TAG = "JoyTrashBridge";
    /** Marks a Trash-screen item that lives in the Joy Creator folder's Trash. */
    public static final String PREFIX = "joyfolder/";

    private JoyTrashBridge() {}

    public static boolean isFolderItem(TrashItem item) {
        return item != null && item.getTrashFileName() != null && item.getTrashFileName().startsWith(PREFIX);
    }

    private static String nameOf(TrashItem item) {
        return item.getTrashFileName().substring(PREFIX.length());
    }

    /** Folder Trash items as Trash-screen items, newest first. Empty when no folder is reachable. */
    public static List<TrashItem> items(Context context) {
        List<TrashItem> out = new ArrayList<>();
        if (!JoyFolderHome.isReachable(context)) return out;
        JoyFolder jf = JoyFolderHome.open(context);
        if (jf == null) return out;
        try {
            for (JoyFolder.TrashItem t : jf.trashItems()) {
                long size = jf.trashSize(t.name);
                if (size < 0) continue;   // removed outside the app
                out.add(new TrashItem(t.from, t.originalName(), PREFIX + t.name, t.at, true, size));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read the folder's Trash", e);
        }
        return out;
    }

    /**
     * Put folder items back where they were (beside, if that name is taken now). A project that
     * comes back to the folder also comes back into the app.
     *
     * @return how many were put back
     */
    public static int restore(Context context, List<TrashItem> items) {
        JoyFolder jf = JoyFolderHome.open(context);
        if (jf == null) return 0;
        int n = 0;
        boolean project = false;
        for (TrashItem item : items) {
            if (!isFolderItem(item)) continue;
            try {
                String rel = jf.restore(nameOf(item));
                n++;
                if (FileKind.of(rel) == FileKind.PROJECT) project = true;
            } catch (Exception e) {
                Log.w(TAG, "Could not put back " + item.getOriginalDisplayName(), e);
            }
        }
        if (project) {
            try {
                StudioMirror.restoreMissing(context, null);
            } catch (Exception e) {
                Log.w(TAG, "Put back a project but could not bring it into the app", e);
            }
        }
        return n;
    }

    /** Remove folder items for good. Returns how many went. */
    public static int delete(Context context, List<TrashItem> items) {
        JoyFolder jf = JoyFolderHome.open(context);
        if (jf == null) return 0;
        int n = 0;
        for (TrashItem item : items) {
            if (!isFolderItem(item)) continue;
            try {
                if (jf.deleteFromTrash(nameOf(item))) n++;
            } catch (Exception e) {
                Log.w(TAG, "Could not delete " + item.getOriginalDisplayName(), e);
            }
        }
        return n;
    }

    /** Empty the folder's Trash. Returns how many items went. */
    public static int emptyAll(Context context) {
        JoyFolder jf = JoyFolderHome.open(context);
        if (jf == null) return 0;
        try {
            return jf.emptyTrash();
        } catch (Exception e) {
            Log.w(TAG, "Could not empty the folder's Trash", e);
            return 0;
        }
    }

    /** Apply the user's Trash auto-delete setting (minutes; never = keep) to the folder's Trash. */
    public static int autoDelete(Context context, int autoDeleteMinutes) {
        if (autoDeleteMinutes == SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER) return 0;
        if (!JoyFolderHome.isReachable(context)) return 0;
        JoyFolder jf = JoyFolderHome.open(context);
        if (jf == null) return 0;
        try {
            return jf.emptyOlderThan(System.currentTimeMillis(), autoDeleteMinutes * 60_000L);
        } catch (Exception e) {
            Log.w(TAG, "Could not tidy the folder's Trash", e);
            return 0;
        }
    }
}
