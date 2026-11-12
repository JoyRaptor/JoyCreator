package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Size and last-changed time of a gallery source ({@code content://}), asked of its provider once
 * per export and remembered, for the render-cache key (E13): replacing a picked photo or clip must
 * re-render the parts that show it. Whatever the provider will not say is simply left out, which
 * is how these sources were always treated.
 */
final class ContentUriStamps implements RenderCacheKeys.UriStamper {

    private final Context context;
    private final Map<String, String> known = new HashMap<>();

    ContentUriStamps(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @Nullable
    @Override
    public synchronized String stampOf(@NonNull String uri) {
        if (known.containsKey(uri)) return known.get(uri);
        String stamp = ask(uri);
        known.put(uri, stamp);
        return stamp;
    }

    @Nullable
    private String ask(@NonNull String uri) {
        // No projection: asking a provider for a column it lacks throws, so read what it offers.
        try (Cursor c = context.getContentResolver().query(Uri.parse(uri), null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            long size = longAt(c, "_size");
            long changed = longAt(c, "last_modified");           // document providers
            if (changed < 0) changed = longAt(c, "date_modified"); // the media store (seconds)
            if (size < 0 && changed < 0) return null;
            return size + "/" + changed;
        } catch (Exception e) {
            return null;
        }
    }

    private static long longAt(@NonNull Cursor c, @NonNull String column) {
        try {
            int i = c.getColumnIndex(column);
            if (i < 0 || c.isNull(i)) return -1L;
            return c.getLong(i);
        } catch (Exception e) {
            return -1L;
        }
    }
}
