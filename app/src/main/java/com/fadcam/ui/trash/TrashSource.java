package com.fadcam.ui.trash;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.fadcam.FLog;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.studio.JoyTrashBridge;
import com.fadcam.model.TrashItem;
import com.fadcam.ui.library.FolderReader;
import com.fadcam.utils.TrashManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the one Trash, through exactly the two lists the classic Trash screen reads:
 * {@link TrashManager#loadTrashMetadata} (the app's own Trash folder) and
 * {@link JoyTrashBridge#items} (the Joy Creator folder's Trash). Nothing is moved or deleted here,
 * except the classic screen's own sweep on opening ({@link #load} with {@code sweep}), which goes
 * through the same two helpers it calls.
 *
 * <p>On the folder thread ({@link JoyFolderHome#io()}), which every Trash action also runs on, so a
 * read queued after a restore or delete always sees its result.</p>
 */
final class TrashSource {

    interface Callback {
        /** On the main thread. */
        void onLoaded(@NonNull Loaded loaded);
    }

    static final class Loaded {
        final List<TrashEntry> entries = new ArrayList<>();
        /** The legacy items the helpers take, by {@link TrashEntry#key}. */
        final Map<String, TrashItem> items = new HashMap<>();
        /** How many the opening sweep deleted for good. */
        int swept;
        /** A folder is chosen but cannot be reached: its Trash could not be listed. */
        boolean folderUnreachable;
    }

    private static final String TAG = "TrashSource";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context app;
    private int generation;

    TrashSource(@NonNull Context c) {
        app = c.getApplicationContext();
    }

    /** Forget any read in flight: its answer will not be delivered. */
    void cancel() {
        generation++;
    }

    /** @param sweep first apply the Trash rule, as the classic screen does every time it opens */
    void load(boolean sweep, @NonNull Callback cb) {
        final int gen = ++generation;
        JoyFolderHome.io().execute(() -> {
            Loaded out = new Loaded();
            try {
                if (sweep) {
                    int minutes = TrashPolicy.minutes(app);
                    out.swept = TrashManager.autoDeleteExpiredItems(app, minutes)
                            + JoyTrashBridge.autoDelete(app, minutes);
                }
                List<TrashItem> all = new ArrayList<>(TrashManager.loadTrashMetadata(app));
                all.addAll(JoyTrashBridge.items(app));
                out.folderUnreachable = JoyFolderHome.isChosen(app) && !JoyFolderHome.isReachable(app);
                read(all, out);
            } catch (Exception e) {
                FLog.w(TAG, "trash read failed", e);
            }
            MAIN.post(() -> {
                if (gen == generation) cb.onLoaded(out);
            });
        });
    }

    private void read(@NonNull List<TrashItem> all, @NonNull Loaded out) {
        File appTrash = TrashManager.getTrashDirectory(app);
        TrashCovers covers = new TrashCovers(app);
        for (TrashItem item : all) {
            if (item == null || item.getTrashFileName() == null) continue;
            try {
                TrashEntry e = toEntry(item, appTrash, covers);
                out.entries.add(e);
                out.items.put(e.key, item);
            } catch (Exception ex) {
                FLog.w(TAG, "skipping " + item.getTrashFileName(), ex);
            }
        }
    }

    @NonNull
    private static TrashEntry toEntry(@NonNull TrashItem item, File appTrash, @NonNull TrashCovers covers) {
        String name = item.getOriginalDisplayName();
        if (name == null || name.isEmpty()) name = new File(item.getTrashFileName()).getName();
        boolean inFolder = JoyTrashBridge.isFolderItem(item);
        long size = item.getSizeBytes();
        File file = null;
        if (!inFolder && appTrash != null) {
            file = new File(appTrash, item.getTrashFileName());
            if (file.exists()) size = file.length();   // as the classic cards measure it
            else file = null;
        }
        TrashEntry e = TrashClassifier.entry(item.getTrashFileName(), name, item.getDateTrashed(), size,
                inFolder, item.isForensicsEvidence(), FolderReader.captureOf(name));
        FileKind fk = e.look.fileKind;
        if (file != null && (fk == FileKind.VIDEO || fk == FileKind.IMAGE)) {
            e.look.thumb = file.getAbsolutePath();
            e.look.uri = file.getAbsolutePath();
        } else if (inFolder) {
            e.look.thumb = covers.of(item, fk);
        }
        return e;
    }
}
