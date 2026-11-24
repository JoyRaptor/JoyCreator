package com.fadcam.ui.library;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.StudioDialog;
import com.fadcam.ui.faditor.project.DiskStamp;
import com.fadcam.ui.faditor.project.ProjectStorage;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * SAFE-1: bring back an earlier copy of a project.
 *
 * <p>When a project is saved over a version somebody else wrote (a second editor, Joybot, a board
 * sent in from Joy Brush), the older version is kept in the project's {@code backups} folder. This
 * lists those copies by when they were kept and puts the chosen one back
 * ({@link DiskStamp#restore}), which first keeps what is there now, so a wrong pick is one more
 * pick away from being undone.</p>
 */
final class KeptCopiesSheet {

    private KeptCopiesSheet() {}

    interface Done { void done(@NonNull String message, boolean changed); }

    private static List<File> copies(@NonNull Context c, @NonNull String projectId) {
        File dir = new ProjectStorage(c).projectDir(projectId);
        List<File> all = new ArrayList<>(DiskStamp.kept(dir, DiskStamp.REPLACED_PREFIX));
        all.addAll(DiskStamp.kept(dir, DiskStamp.BEFORE_RESTORE_PREFIX));
        all.sort((a, b) -> Long.compare(DiskStamp.keptAt(b), DiskStamp.keptAt(a)));
        return all;
    }

    static boolean hasAny(@NonNull Context c, @NonNull LibraryItem it) {
        if (it.kind != LibraryKind.PROJECTS || it.projectId == null) return false;
        try {
            return !copies(c, it.projectId).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void show(@NonNull Context c, @NonNull LibraryItem it, @NonNull Done done) {
        final String projectId = it.projectId;
        if (projectId == null) return;
        final List<File> list = copies(c, projectId);
        if (list.isEmpty()) { done.done(c.getString(R.string.kept_copies_none), false); return; }
        final DateFormat when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        CharSequence[] labels = new CharSequence[list.size()];
        for (int i = 0; i < labels.length; i++) {
            File f = list.get(i);
            boolean mine = f.getName().startsWith(DiskStamp.BEFORE_RESTORE_PREFIX);
            labels[i] = c.getString(mine ? R.string.kept_copies_item_before_restore
                    : R.string.kept_copies_item_replaced, when.format(new Date(DiskStamp.keptAt(f))));
        }
        StudioDialog.builder(c)
                .setTitle(c.getString(R.string.kept_copies_title, it.name))
                .setItems(labels, (d, which) -> confirm(c, it, list.get(which), done))
                .setNegativeButton(R.string.kept_copies_cancel, null)
                .show();
    }

    private static void confirm(@NonNull Context c, @NonNull LibraryItem it, @NonNull File copy,
                                @NonNull Done done) {
        final String projectId = it.projectId;
        if (projectId == null) return;
        final Context app = c.getApplicationContext();
        StudioDialog.builder(c)
                .setTitle(R.string.kept_copies_confirm_title)
                .setMessage(R.string.kept_copies_confirm_body)
                .setPositiveButton(R.string.kept_copies_confirm_go, (d, w) ->
                        new Thread(() -> {
                            File dir = new ProjectStorage(app).projectDir(projectId);
                            boolean ok = DiskStamp.restore(dir, copy);
                            new Handler(Looper.getMainLooper()).post(() -> done.done(
                                    app.getString(ok ? R.string.kept_copies_done : R.string.kept_copies_failed),
                                    ok));
                        }, "kept-copy-restore").start())
                .setNegativeButton(R.string.kept_copies_cancel, null)
                .show();
    }
}
