package com.fadcam.ui.studio;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

/**
 * A video's readable name from its link, without the file ending: what a clip is called on the
 * timeline, and what a project never given a name of its own is called in a list.
 *
 * <p>Moved unchanged out of the classic Studio list (FaditorMiniFragment), so that list, a new
 * multi-clip project and the Library all call the same file the same thing.</p>
 */
public final class SourceName {

    private static final String TAG = "SourceName";

    private SourceName() {}

    /**
     * The file's name: a content:// link asks its provider (a query, so keep it off the main thread
     * when there are many); anything else is read off the path, including the storage-picker shape
     * {@code …/primary:Movies/FadCam_x.mp4}. Null when nothing readable is there.
     */
    @Nullable
    public static String of(@NonNull Context c, @Nullable String uriString) {
        if (uriString == null) return null;
        try {
            Uri uri = Uri.parse(uriString);
            if ("content".equals(uri.getScheme())) {
                try (Cursor cursor = c.getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) {
                            String name = cursor.getString(idx);
                            if (name != null && !name.isEmpty()) return stripExtension(name);
                        }
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "ContentResolver query failed for display name", e);
                }
            }
            String path = uri.getPath();
            if (path != null) {
                if (path.contains(":")) {
                    String afterColon = path.substring(path.lastIndexOf(':') + 1);
                    if (afterColon.contains("/")) {
                        afterColon = afterColon.substring(afterColon.lastIndexOf('/') + 1);
                    }
                    if (!afterColon.isEmpty()) return stripExtension(afterColon);
                }
                String last = uri.getLastPathSegment();
                if (last != null && !last.isEmpty()) return stripExtension(last);
            }
        } catch (Exception e) {
            FLog.w(TAG, "Failed to extract display name from: " + uriString, e);
        }
        return null;
    }

    @NonNull
    private static String stripExtension(@NonNull String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
