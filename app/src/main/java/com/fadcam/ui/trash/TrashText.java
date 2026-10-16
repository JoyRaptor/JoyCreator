package com.fadcam.ui.trash;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.library.LibraryKindStyle;

/**
 * The words on a card: {@code RECORDING · 12 days left}, in the Library's voice. Only the time left
 * turns the careful amber, and only when three days or fewer remain.
 */
final class TrashText {

    private TrashText() {}

    @NonNull
    static String left(@NonNull Context c, @NonNull TrashExpiry x) {
        switch (x.unit) {
            case KEPT:  return c.getString(R.string.trash_left_kept);
            case DAYS:  return c.getResources().getQuantityString(R.plurals.trash_days_left, x.n, x.n);
            case HOURS: return c.getResources().getQuantityString(R.plurals.trash_hours_left, x.n, x.n);
            default:    return c.getString(R.string.trash_left_soon);
        }
    }

    @NonNull
    static CharSequence meta(@NonNull Context c, @NonNull TrashEntry e, @NonNull TrashExpiry x) {
        String tag = c.getString(LibraryKindStyle.tag(e.look));
        String left = left(c, x);
        SpannableStringBuilder s = new SpannableStringBuilder(c.getString(R.string.library_meta, tag, left));
        if (x.nearing) {
            int at = s.length() - left.length();
            s.setSpan(new ForegroundColorSpan(Studio.CAREFUL), at, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    /** What a screen reader and a hover say: name, kind, time left, and whether it is chosen. */
    @NonNull
    static String describe(@NonNull Context c, @NonNull TrashEntry e, @NonNull TrashExpiry x, boolean selected) {
        String s = c.getString(R.string.trash_a11y_card, e.look.name,
                c.getString(LibraryKindStyle.label(e.look.kind)), left(c, x));
        return selected ? s + " " + c.getString(R.string.trash_a11y_selected) : s;
    }
}
