package com.fadcam.joyfolder.studio;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The second half of a MOVE: once a file has been copied into the Joy Creator folder and every project points
 * at the copy, the original is removed from where it was (the owner, 2026-10-14: "something I recorded
 * specifically with a project in mind, I don't want to have a copy laying around on my phone").
 *
 * <p>Tried in order: the file itself (phones that grant this app storage access), the picked folder's own
 * delete, and finally Android's "Allow Joy Creator to delete …?" prompt for gallery items (Android 11+), shown
 * once for all of them. Never called before the copy has landed.</p>
 */
public final class OriginalsRemover {

    private static final String TAG = "OriginalsRemover";

    private OriginalsRemover() {}

    /**
     * Remove what can be removed quietly; hand the rest to Android's prompt. Runs the file work on the calling
     * thread (not the main one); the prompt, if any, is started on the activity.
     *
     * @return names of originals that could not be removed (and needed no prompt), for the person to delete
     */
    public static List<String> remove(Activity activity, List<String> refs) {
        List<String> failed = new ArrayList<>();
        ArrayList<Uri> needAsk = new ArrayList<>();
        for (String ref : refs) {
            if (removeQuietly(activity, ref)) continue;
            Uri media = mediaUriOf(activity, ref);
            if (media != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) needAsk.add(media);
            else failed.add(StudioMirror.nameOf(activity, ref));
        }
        if (!needAsk.isEmpty()) {
            activity.runOnUiThread(() -> {
                try {
                    android.app.PendingIntent pi = MediaStore.createDeleteRequest(activity.getContentResolver(), needAsk);
                    activity.startIntentSenderForResult(pi.getIntentSender(), 0x4A0D, null, 0, 0, 0);
                } catch (Exception e) {
                    Log.w(TAG, "delete prompt unavailable", e);
                }
            });
        }
        return failed;
    }

    private static boolean removeQuietly(Context c, String ref) {
        Uri uri = Uri.parse(ref);
        // 1. The file itself, when this app may touch it.
        String path = realPath(c, ref, uri);
        if (path != null) {
            File f = new File(path);
            try {
                if (f.isFile() && f.delete()) {
                    MediaScannerConnection.scanFile(c, new String[]{path}, null, null);
                    return true;
                }
            } catch (SecurityException ignored) {
            }
        }
        // 2. A document in a folder the person picked: that folder's own delete.
        if ("content".equals(uri.getScheme())) {
            try {
                if (DocumentsContract.isDocumentUri(c, uri) && DocumentsContract.deleteDocument(c.getContentResolver(), uri)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** The real path behind a link, when there is one. */
    private static String realPath(Context c, String ref, Uri uri) {
        if ("file".equals(uri.getScheme())) return uri.getPath();
        if (ref.startsWith("/")) return ref;
        String key = StudioMirror.usageKey(ref);
        if (key.startsWith("/")) return key;
        Uri media = mediaUriOf(c, ref);
        if (media == null) return null;
        try (Cursor cur = c.getContentResolver().query(media, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** The gallery (MediaStore) item behind a link, or null when it isn't one. */
    private static Uri mediaUriOf(Context c, String ref) {
        Uri uri = Uri.parse(ref);
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return null;
        if (MediaStore.AUTHORITY.equals(uri.getAuthority())) return uri;
        if ("com.android.providers.media.documents".equals(uri.getAuthority())
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return MediaStore.getMediaUri(c, uri);
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
