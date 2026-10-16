package com.fadcam.ui.trash;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * The Trash rule: how long deleted things wait before they go for good. The SAME setting the classic
 * Trash writes ({@code SharedPreferencesManager.getTrashAutoDeleteMinutes}), with the same ten
 * choices and the same labels, so changing it on either screen changes it on both.
 */
final class TrashPolicy {

    private TrashPolicy() {}

    /** The classic Trash's choices, in its order (TrashFragment.showAutoDeleteBottomSheet). */
    static final int[] MINUTES = {
            0, 60, 5 * 60, 10 * 60, 24 * 60, 7 * 24 * 60, 30 * 24 * 60, 60 * 24 * 60, 90 * 24 * 60,
            SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER
    };
    static final int[] LABELS = {
            R.string.auto_delete_immediate, R.string.auto_delete_1_hour, R.string.auto_delete_5_hours,
            R.string.auto_delete_10_hours, R.string.auto_delete_1_day, R.string.auto_delete_7_days,
            R.string.auto_delete_30_days, R.string.auto_delete_60_days, R.string.auto_delete_90_days,
            R.string.auto_delete_never
    };

    static int minutes(@NonNull Context c) {
        return SharedPreferencesManager.getInstance(c).getTrashAutoDeleteMinutes();
    }

    static void setMinutes(@NonNull Context c, int minutes) {
        SharedPreferencesManager.getInstance(c).setTrashAutoDeleteMinutes(minutes);
    }

    /** Milliseconds a thing is kept, or -1 when it is kept until deleted by hand. */
    static long keepMillis(int minutes) {
        return minutes == SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER ? -1L : minutes * 60_000L;
    }

    /** The choice's label ("After 30 Days (Default)"), or the nearest words for a value not in the list. */
    @NonNull
    static String label(@NonNull Context c, int minutes) {
        for (int i = 0; i < MINUTES.length; i++) {
            if (MINUTES[i] == minutes) return c.getString(LABELS[i]);
        }
        return rule(c, minutes);
    }

    /** The quiet line in the dock: "Kept 30 days, then deleted for good." */
    @NonNull
    static String rule(@NonNull Context c, int minutes) {
        if (minutes == SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER) return c.getString(R.string.trash_rule_never);
        if (minutes <= 0) return c.getString(R.string.trash_rule_now);
        if (minutes % (24 * 60) == 0) {
            int days = minutes / (24 * 60);
            return c.getResources().getQuantityString(R.plurals.trash_rule_days, days, days);
        }
        int hours = Math.max(1, minutes / 60);
        return c.getResources().getQuantityString(R.plurals.trash_rule_hours, hours, hours);
    }
}
