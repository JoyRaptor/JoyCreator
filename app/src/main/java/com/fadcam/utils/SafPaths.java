package com.fadcam.utils;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;

/**
 * The real file behind a link to phone storage or an SD card picked through Android's folder
 * picker (the Joy Creator folder, the recorder's custom folder): {@code content://…/primary%3AJoy%20Creator%2Fx.mp4}
 * is {@code /storage/emulated/0/Joy Creator/x.mp4}.
 *
 * <p>Moved out of FaditorEditorActivity (was its private resolveSafPath) so preview and export use
 * the same rule: preview could already open such files directly, while export skipped them and a
 * clipped raw recording failed with "Illegal clipping: not seekable to start".</p>
 */
public final class SafPaths {

    private static final String TAG = "SafPaths";

    private SafPaths() {}

    /** The filesystem path a storage document link points at, or null when it isn't one. */
    @Nullable
    public static String pathOf(@NonNull Uri uri) {
        String uriPath = uri.getPath();
        if (uriPath == null || !uriPath.contains(":")) return null;
        try {
            int lastColon = uriPath.lastIndexOf(':');
            String encodedRel = uriPath.substring(lastColon + 1);
            String relativePath = java.net.URLDecoder.decode(encodedRel, "UTF-8");

            String beforeColon = uriPath.substring(0, lastColon);
            int lastSlash = beforeColon.lastIndexOf('/');
            String encodedId = lastSlash >= 0
                    ? beforeColon.substring(lastSlash + 1) : beforeColon;
            String storageId = java.net.URLDecoder.decode(encodedId, "UTF-8");

            String mountPoint = "primary".equalsIgnoreCase(storageId)
                    ? "/storage/emulated/0"
                    : "/storage/" + storageId;

            return mountPoint + "/" + relativePath;
        } catch (Exception e) {
            FLog.w(TAG, "pathOf failed for " + uri, e);
            return null;
        }
    }

    /** The readable file behind a {@code file://} link or a storage document link, or null. */
    @Nullable
    public static File fileOf(@Nullable Uri uri) {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) return uri.getPath() == null ? null : new File(uri.getPath());
        if (!"content".equals(uri.getScheme())
                || !"com.android.externalstorage.documents".equals(uri.getAuthority())) return null;
        String path = pathOf(uri);
        if (path == null) return null;
        File f = new File(path);
        return f.isFile() && f.canRead() ? f : null;
    }
}
