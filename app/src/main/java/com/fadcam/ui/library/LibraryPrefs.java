package com.fadcam.ui.library;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** The two choices the Library remembers between visits: grid or list, and how it is sorted. */
final class LibraryPrefs {

    private static final String FILE = "library_prefs";
    private static final String KEY_LIST = "dense_list";
    private static final String KEY_SORT = "sort";

    private LibraryPrefs() {}

    private static SharedPreferences prefs(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean list(@NonNull Context c) {
        return prefs(c).getBoolean(KEY_LIST, false);
    }

    static void setList(@NonNull Context c, boolean list) {
        prefs(c).edit().putBoolean(KEY_LIST, list).apply();
    }

    @NonNull
    static LibraryFilter.Sort sort(@NonNull Context c) {
        try {
            return LibraryFilter.Sort.valueOf(prefs(c).getString(KEY_SORT, LibraryFilter.Sort.NEWEST.name()));
        } catch (IllegalArgumentException e) {
            return LibraryFilter.Sort.NEWEST;
        }
    }

    static void setSort(@NonNull Context c, @NonNull LibraryFilter.Sort sort) {
        prefs(c).edit().putString(KEY_SORT, sort.name()).apply();
    }
}
