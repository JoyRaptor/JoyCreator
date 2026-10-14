package com.fadcam.joyfolder.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Log;

import com.fadcam.joyfolder.JoyFolder;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where the user's Joy Creator folder is, and the one place every wing opens it from.
 *
 * <p>Holds the folder the user picked (and Android's lasting permission to it), hands out a
 * ready {@link JoyFolder}, and owns the single background thread all folder work runs on, so two
 * wings never write the folder at the same time.</p>
 */
public final class JoyFolderHome {

    private static final String TAG = "JoyFolderHome";
    private static final String PREFS = "joy_folder";
    private static final String KEY_TREE = "tree_uri";
    private static final String KEY_COPY_MEDIA = "copy_media";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "joy-folder");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static volatile JoyFolder cached;
    private static volatile String cachedFor;

    private JoyFolderHome() {}

    /** The one thread all folder reads and writes run on. */
    public static ExecutorService io() {
        return IO;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The picked folder, or null when the user hasn't chosen one. */
    public static Uri treeUri(Context c) {
        String s = prefs(c).getString(KEY_TREE, null);
        return s == null ? null : Uri.parse(s);
    }

    public static boolean isChosen(Context c) {
        return treeUri(c) != null;
    }

    /** True when a folder is chosen and Android still lets us write to it. */
    public static boolean isReachable(Context c) {
        Uri tree = treeUri(c);
        if (tree == null) return false;
        boolean granted = false;
        for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isReadPermission() && p.isWritePermission()) granted = true;
        }
        if (!granted) return false;
        // The permission outlives the folder: an SD card taken out, or the folder renamed or deleted
        // in a file manager. Ask for the folder itself. One small query.
        try (android.database.Cursor cur = c.getContentResolver().query(
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return cur != null && cur.moveToFirst();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The open folder, or null when none is chosen or it can't be reached (SD card out,
     * permission revoked). Call from {@link #io()}, never the main thread.
     */
    public static JoyFolder open(Context c) {
        Uri tree = treeUri(c);
        if (tree == null || !isReachable(c)) return null;
        String key = tree.toString();
        JoyFolder jf = cached;
        if (jf != null && key.equals(cachedFor)) return jf;
        try {
            jf = JoyFolder.open(new SafJoyFs(c, tree));
            cached = jf;
            cachedFor = key;
            return jf;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Joy Creator folder unavailable", e);
            return null;
        }
    }

    /** The {@link SafJoyFs} behind {@link #open}, for building content URIs into the folder. */
    public static SafJoyFs fsOf(JoyFolder jf) {
        return jf != null && jf.fs instanceof SafJoyFs ? (SafJoyFs) jf.fs : null;
    }

    /**
     * The system folder picker, opening at the top of the phone's storage so "Joy Creator" can be
     * made right there. Android 11+ won't hand an app the storage root itself, but any folder
     * directly under it is fine.
     */
    public static Intent pickerIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildRootUri(
                    "com.android.externalstorage.documents", "primary"));
        }
        return i;
    }

    /** Remember the picked folder and keep Android's permission to it across restarts. */
    public static void choose(Context c, Uri tree) {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        c.getContentResolver().takePersistableUriPermission(tree, flags);
        Uri old = treeUri(c);
        if (old != null && !old.equals(tree)) {
            try {
                c.getContentResolver().releasePersistableUriPermission(old, flags);
            } catch (SecurityException ignored) {
            }
        }
        prefs(c).edit().putString(KEY_TREE, tree.toString()).apply();
        cached = null;
        cachedFor = null;
    }

    /** The user agreed to copy media that only lives inside the app into their folder. */
    public static boolean copyMediaAllowed(Context c) {
        return prefs(c).getBoolean(KEY_COPY_MEDIA, false);
    }

    public static void setCopyMediaAllowed(Context c, boolean allowed) {
        prefs(c).edit().putBoolean(KEY_COPY_MEDIA, allowed).apply();
    }

    /** "Phone storage / Joy Creator" or "SD card / Art / Joy Creator", for showing the user. */
    public static String describe(Uri tree) {
        if (tree == null) return "";
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (IllegalArgumentException e) {
            return tree.toString();
        }
        int colon = id.indexOf(':');
        if (colon < 0) return id;
        String volume = id.substring(0, colon);
        String path = id.substring(colon + 1);
        String where = "primary".equalsIgnoreCase(volume) ? "Phone storage" : "SD card";
        return path.isEmpty() ? where : where + " / " + path.replace("/", " / ");
    }
}
