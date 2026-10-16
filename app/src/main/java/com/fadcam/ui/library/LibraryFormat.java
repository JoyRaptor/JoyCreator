package com.fadcam.ui.library;

import android.content.Context;
import android.text.format.DateUtils;
import android.text.format.Formatter;

import androidx.annotation.NonNull;

import com.fadcam.R;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * The words and numbers under a card. Same voice as the lobby's recents ("RECORDING · 2h ago"), so a
 * thing reads the same on the front door and in the Library.
 */
final class LibraryFormat {

    private LibraryFormat() {}

    /** "just now", "12m ago", "3h ago", "5d ago", "2mo ago". */
    @NonNull
    static String ago(@NonNull Context c, long when) {
        long d = Math.max(0, System.currentTimeMillis() - when);
        if (d < TimeUnit.MINUTES.toMillis(2)) return c.getString(R.string.library_ago_now);
        if (d < TimeUnit.HOURS.toMillis(1)) return c.getString(R.string.library_ago_minutes, (int) TimeUnit.MILLISECONDS.toMinutes(d));
        if (d < TimeUnit.DAYS.toMillis(1)) return c.getString(R.string.library_ago_hours, (int) TimeUnit.MILLISECONDS.toHours(d));
        long days = TimeUnit.MILLISECONDS.toDays(d);
        if (days < 30) return c.getString(R.string.library_ago_days, (int) days);
        return c.getString(R.string.library_ago_months, (int) (days / 30));
    }

    /** "24 MB", or "" when the size is not known. */
    @NonNull
    static String size(@NonNull Context c, long bytes) {
        return bytes < 0 ? "" : Formatter.formatShortFileSize(c, bytes);
    }

    /** A full date for the item sheet: "13 Oct 2026, 14:32" in the phone's own style. */
    @NonNull
    static String date(@NonNull Context c, long when) {
        return DateUtils.formatDateTime(c, when, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH);
    }

    /**
     * The card's second line. A sheet or a character says which project it lives in, since that is
     * what tells two "Walk cycle"s apart; everything else says how long ago.
     */
    @NonNull
    static String meta(@NonNull Context c, @NonNull LibraryItem it) {
        String tag = c.getString(LibraryKindStyle.tag(it));
        String second;
        if (it.needsRestore) {
            second = c.getString(R.string.library_folder_only);
        } else if (it.parentName != null && !it.parentName.isEmpty()) {
            second = c.getString(R.string.library_in_project, it.parentName);
        } else if (it.avatarDir != null) {
            second = c.getString(R.string.library_avatar_library);
        } else {
            second = ago(c, it.modified);
        }
        return c.getString(R.string.library_meta, tag, second);
    }

    /** What a screen reader says for a card: name, kind, age, and whether it was shared in. */
    @NonNull
    static String describe(@NonNull Context c, @NonNull LibraryItem it) {
        String s = c.getString(R.string.library_a11y_card, it.name,
                c.getString(LibraryKindStyle.label(it.kind)), ago(c, it.modified));
        return it.sharedIn ? s + " " + c.getString(R.string.library_a11y_shared) : s;
    }

    /** Local midnight today, for the date bands. */
    static long startOfToday() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    @NonNull
    static String bandLabel(@NonNull Context c, @NonNull LibrarySections.Band b) {
        switch (b) {
            case TODAY:      return c.getString(R.string.library_band_today);
            case YESTERDAY:  return c.getString(R.string.library_band_yesterday);
            case THIS_WEEK:  return c.getString(R.string.library_band_week);
            case THIS_MONTH: return c.getString(R.string.library_band_month);
            default:         return c.getString(R.string.library_band_earlier);
        }
    }
}
