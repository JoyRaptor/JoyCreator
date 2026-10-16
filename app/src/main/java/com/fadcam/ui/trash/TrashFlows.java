package com.fadcam.ui.trash;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.model.TrashItem;
import com.fadcam.utils.TrashManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The steps between a tap and a change: which question to ask first, then the {@link TrashActions}
 * call. Anything that deletes for good asks first and names how many; restoring asks only where
 * recordings should go, as the classic screen does, because restoring loses nothing.
 */
final class TrashFlows {

    private TrashFlows() {}

    static void restore(@NonNull Context c, @NonNull List<TrashItem> items, @NonNull TrashActions.Done done) {
        if (items.isEmpty()) return;
        List<TrashItem> folder = new ArrayList<>(), recordings = new ArrayList<>();
        TrashActions.split(items, folder, recordings);
        if (recordings.isEmpty()) {
            // Folder things go back where they were: nothing to choose (TrashFragment.restoreFolderItems).
            TrashActions.restore(c, items, null, done);
            return;
        }
        TrashSheets.destination(c, items.size(), !folder.isEmpty(),
                TrashManager.getAvailableRestoreDestinations(recordings),
                d -> TrashActions.restore(c, items, d, done));
    }

    static void deleteForever(@NonNull Context c, @NonNull List<TrashItem> items, @NonNull TrashActions.Done done) {
        int n = items.size();
        if (n == 0) return;
        TrashSheets.confirm(c, c.getResources().getQuantityString(R.plurals.trash_confirm_delete, n, n),
                c.getString(R.string.trash_confirm_undone), c.getString(R.string.trash_delete_forever),
                () -> TrashActions.deleteForever(c, items, done));
    }

    /** @param total everything in the Trash, whatever chip is showing: Empty takes it all */
    static void emptyTrash(@NonNull Context c, int total, @NonNull TrashActions.Done done) {
        if (total == 0) return;
        TrashSheets.confirm(c, c.getResources().getQuantityString(R.plurals.trash_confirm_empty, total, total),
                c.getString(R.string.trash_confirm_undone), c.getString(R.string.trash_confirm_empty_button),
                () -> TrashActions.emptyAll(c, done));
    }

    /** A rule that would delete things now says how many, and asks first; otherwise it just applies. */
    static void rule(@NonNull Context c, @NonNull List<TrashEntry> entries, @NonNull TrashActions.Done done) {
        TrashOptionsSheet.rule(c, TrashPolicy.minutes(c), minutes -> {
            int n = TrashFilter.expiringUnder(entries, System.currentTimeMillis(), TrashPolicy.keepMillis(minutes));
            Runnable apply = () -> TrashActions.setRule(c, minutes, done);
            if (n == 0) {
                apply.run();
                return;
            }
            TrashSheets.confirm(c, c.getResources().getQuantityString(R.plurals.trash_confirm_delete, n, n),
                    c.getResources().getQuantityString(R.plurals.trash_confirm_rule, n, n) + " "
                            + c.getString(R.string.trash_confirm_undone),
                    c.getString(R.string.trash_confirm_rule_button), apply);
        });
    }
}
