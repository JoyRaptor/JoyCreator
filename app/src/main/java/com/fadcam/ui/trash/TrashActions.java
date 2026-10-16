package com.fadcam.ui.trash;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.studio.JoyTrashBridge;
import com.fadcam.model.TrashItem;
import com.fadcam.utils.TrashManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Restore, Delete forever, Empty trash and a new Trash rule: each one calls EXACTLY the helpers the
 * classic Trash screen ({@code com.fadcam.ui.TrashFragment}) calls, with the same split between the
 * app's own Trash and the Joy Creator folder's, in the same order. No file is moved here; this only
 * decides which helper gets which items and puts the outcome into words.
 *
 * <table>
 *   <tr><td>Restore</td><td>folder items: {@code JoyTrashBridge.restore} (back where they were; a
 *       project comes back into the app); recordings: {@code TrashManager.restoreItemsFromTrash}
 *       to the chosen place, then the {@code ACTION_FILES_RESTORED} broadcast the classic screen
 *       falls back to, so Records re-reads.</td></tr>
 *   <tr><td>Delete forever</td><td>{@code TrashManager.permanentlyDeleteItems} +
 *       {@code JoyTrashBridge.delete}, success judged as the classic screen judges it.</td></tr>
 *   <tr><td>Empty trash</td><td>{@code JoyTrashBridge.emptyAll}, then
 *       {@code TrashManager.emptyAllTrash}.</td></tr>
 *   <tr><td>Rule</td><td>{@code setTrashAutoDeleteMinutes}, then {@code TrashManager.autoDeleteExpiredItems}
 *       + {@code JoyTrashBridge.autoDelete}.</td></tr>
 * </table>
 *
 * All run on the folder thread, one after another; {@link Done} is called on the main thread.
 */
final class TrashActions {

    interface Done {
        void onDone(@NonNull String message);
    }

    private static final String TAG = "TrashActions";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private TrashActions() {}

    /** Folder items first, then the app's own, as the classic screen splits them. */
    static void split(@NonNull List<TrashItem> items, @NonNull List<TrashItem> folder,
                      @NonNull List<TrashItem> recordings) {
        for (TrashItem item : items) (JoyTrashBridge.isFolderItem(item) ? folder : recordings).add(item);
    }

    /** @param destination where recordings go; unused (may be null) when there are none */
    static void restore(@NonNull Context c, @NonNull List<TrashItem> items,
                        @Nullable TrashManager.RestoreDestination destination, @NonNull Done done) {
        final Context app = c.getApplicationContext();
        final List<TrashItem> folder = new ArrayList<>(), recordings = new ArrayList<>();
        split(items, folder, recordings);
        JoyFolderHome.io().execute(() -> {
            int back = 0;
            try {
                if (!folder.isEmpty()) back += JoyTrashBridge.restore(app, folder);
                if (!recordings.isEmpty() && destination != null) {
                    if (TrashManager.restoreItemsFromTrash(app, recordings, destination)) {
                        back += recordings.size();
                        // What the classic screen sends when it cannot reach Records directly.
                        app.sendBroadcast(new Intent(Constants.ACTION_FILES_RESTORED).setPackage(app.getPackageName()));
                    }
                }
            } catch (Exception e) {
                FLog.w(TAG, "restore failed", e);
            }
            int total = items.size();
            String msg = back == total
                    ? app.getResources().getQuantityString(R.plurals.trash_done_restored, back, back)
                    : app.getString(R.string.trash_done_restored_part, back, total);
            MAIN.post(() -> done.onDone(msg));
        });
    }

    static void deleteForever(@NonNull Context c, @NonNull List<TrashItem> items, @NonNull Done done) {
        final Context app = c.getApplicationContext();
        final List<TrashItem> folder = new ArrayList<>(), recordings = new ArrayList<>();
        split(items, folder, recordings);
        JoyFolderHome.io().execute(() -> {
            boolean ok = false;
            try {
                // TrashFragment.performDeleteInBackground, word for word.
                ok = (recordings.isEmpty() || TrashManager.permanentlyDeleteItems(app, recordings))
                        & JoyTrashBridge.delete(app, folder) == folder.size();
            } catch (Exception e) {
                FLog.w(TAG, "delete failed", e);
            }
            String msg = ok
                    ? app.getResources().getQuantityString(R.plurals.trash_done_deleted, items.size(), items.size())
                    : app.getString(R.string.trash_done_delete_failed);
            MAIN.post(() -> done.onDone(msg));
        });
    }

    static void emptyAll(@NonNull Context c, @NonNull Done done) {
        final Context app = c.getApplicationContext();
        JoyFolderHome.io().execute(() -> {
            boolean ok = false;
            try {
                // TrashFragment.showEmptyAllConfirmationSheet's order: the folder's Trash, then the app's.
                JoyTrashBridge.emptyAll(app);
                ok = TrashManager.emptyAllTrash(app);
            } catch (Exception e) {
                FLog.w(TAG, "empty failed", e);
            }
            String msg = app.getString(ok ? R.string.trash_done_emptied : R.string.trash_done_empty_failed);
            MAIN.post(() -> done.onDone(msg));
        });
    }

    /** A new Trash rule, applied at once as the classic picker applies it. Message empty when nothing went. */
    static void setRule(@NonNull Context c, int minutes, @NonNull Done done) {
        final Context app = c.getApplicationContext();
        TrashPolicy.setMinutes(app, minutes);
        JoyFolderHome.io().execute(() -> {
            int gone = 0;
            try {
                gone = TrashManager.autoDeleteExpiredItems(app, minutes) + JoyTrashBridge.autoDelete(app, minutes);
            } catch (Exception e) {
                FLog.w(TAG, "rule sweep failed", e);
            }
            String msg = gone > 0 ? app.getResources().getQuantityString(R.plurals.trash_done_swept, gone, gone) : "";
            MAIN.post(() -> done.onDone(msg));
        });
    }
}
