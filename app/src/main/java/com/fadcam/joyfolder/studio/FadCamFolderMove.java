package com.fadcam.joyfolder.studio;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import com.fadcam.BuildConfig;
import com.fadcam.Constants;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.LegacyFolderMove;
import com.fadcam.joyfolder.android.JoyFolderHome;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves FadCam's old shared folders to their Joy Creator names, in the side-loaded build only (SHELL-44, FREE-2
 * step 3). The rules and the tests live in {@link LegacyFolderMove}; this class adds what needs Android: where the
 * folders are, the move log in the person's Joy Creator folder, pointing projects at the copies, and removing an
 * original from Android's media list.
 *
 * <p>The Play build never runs it: a Play install is a new app, and Android does not let it touch the side-loaded
 * build's files. On a phone with nothing in the old folders it costs one folder listing per start.
 *
 * <p>Test mode (debug builds only): when {@code Download/FadCam-move-test/} exists, the move works ONLY inside it,
 * on its own {@code Download/FadCam}, {@code Pictures/FadCam/assets} and {@code Documents/FadRec/Projects}, with
 * its own log and plan; the real folders are not touched.
 */
public final class FadCamFolderMove {

    private static final String TAG = "FadCamFolderMove";
    private static final String PREFS = "fadcam_folder_move";
    static final String LOG_NAME = "Moved from FadCam folders.txt";
    static final String PLAN_NAME = "FadCam folders move plan.txt";
    static final String TEST_DIR = "FadCam-move-test";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean ranThisProcess;

    private FadCamFolderMove() { }

    /** Once per run, a few seconds after the app opens, on the folder's thread. Never shows anything. */
    public static void runOnce(Context context) {
        if (BuildConfig.PLAY_BUILD) return;
        synchronized (FadCamFolderMove.class) {
            if (ranThisProcess) return;
            ranThisProcess = true;
        }
        final Context app = context.getApplicationContext();
        MAIN.postDelayed(() -> JoyFolderHome.io().execute(() -> {
            try {
                work(app);
            } catch (Throwable t) {
                // Rule: a failure keeps every old file where it is and is never shown to the person.
                Log.w(TAG, "The FadCam folder move stopped; nothing was removed.", t);
            }
        }), 5000);
    }

    private static void work(Context app) throws IOException {
        if (!JoyFolderHome.isReachable(app) || !hasFileAccess(app)) return;
        File test = testBase();
        File storage = test != null ? test : Environment.getExternalStorageDirectory();
        String tag = test != null ? " (test)" : "";
        List<LegacyFolderMove.Root> roots = roots(storage);
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String pendingKey = "copies_waiting" + (test != null ? "_test" : "");
        // Most starts: nothing in the old folders and no copy waiting, so the Joy Creator folder is not even opened.
        if (!prefs.getBoolean(pendingKey, false) && !LegacyFolderMove.anythingToMove(roots)) return;
        JoyFolder jf = JoyFolderHome.open(app);
        if (jf == null) return;
        LegacyFolderMove.LogStore store = joyStore(jf, JoyFolder.HIDDEN + "/" + withTag(LOG_NAME, tag));
        boolean started = !store.read().isEmpty();

        // A dry run first: the first start that finds old folders only writes down what it would move.
        String planKey = "plan_written" + (test != null ? "_test" : "");
        if (!started && !prefs.getBoolean(planKey, false)) {
            List<String> plan = new ArrayList<>(Arrays.asList(
                    "# What Joy Creator will move out of the old FadCam folders on its next start (nothing moved yet).",
                    "# Each old file stays until its checked copy has been in use for a start."));
            plan.addAll(LegacyFolderMove.plan(roots));
            jf.writeAtomic(JoyFolder.HIDDEN + "/" + withTag(PLAN_NAME, tag),
                    (android.text.TextUtils.join("\n", plan) + "\n").getBytes(StandardCharsets.UTF_8));
            prefs.edit().putBoolean(planKey, true).commit();
            Log.i(TAG, "Dry run written" + tag + ": " + (plan.size() - 2) + " file(s) would move.");
            return;
        }
        List<File> prune = Arrays.asList(new File(storage, Environment.DIRECTORY_PICTURES + "/" + Constants.RECORDING_DIRECTORY),
                new File(storage, Environment.DIRECTORY_DOCUMENTS + "/FadRec"));
        LegacyFolderMove.Result r = LegacyFolderMove.run(roots, store, new PhoneHooks(app), System.currentTimeMillis(), prune);
        prefs.edit().putBoolean(pendingKey, r.stillCopied > 0).commit();
        Log.i(TAG, "FadCam folder move" + tag + ": copied " + r.copied + ", removed " + r.removed
                + ", kept " + r.kept + ", waiting " + r.waiting + ", copies awaiting removal " + r.stillCopied);
    }

    static List<LegacyFolderMove.Root> roots(File storage) {
        String joy = Constants.PUBLIC_FOLDER_NAME;
        String fad = Constants.RECORDING_DIRECTORY;
        return Arrays.asList(
                new LegacyFolderMove.Root(new File(storage, Environment.DIRECTORY_DOWNLOADS + "/" + fad),
                        new File(storage, Environment.DIRECTORY_DOWNLOADS + "/" + joy), false),
                new LegacyFolderMove.Root(new File(storage, Environment.DIRECTORY_PICTURES + "/" + fad + "/assets"),
                        new File(storage, Environment.DIRECTORY_PICTURES + "/" + joy + "/assets"), false),
                new LegacyFolderMove.Root(new File(storage, Environment.DIRECTORY_DOCUMENTS + "/FadRec/Projects"),
                        new File(storage, Environment.DIRECTORY_DOCUMENTS + "/" + joy + "/Screen drawings"), true));
    }

    private static File testBase() {
        if (!BuildConfig.DEBUG) return null;
        File t = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), TEST_DIR);
        return t.isDirectory() ? t : null;
    }

    private static String withTag(String name, String tag) {
        return tag.isEmpty() ? name : name.replace(".txt", tag + ".txt");
    }

    private static boolean hasFileAccess(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** The move log as a file in the Joy Creator folder, saved whole and atomically after every change. */
    private static LegacyFolderMove.LogStore joyStore(JoyFolder jf, String rel) {
        return new LegacyFolderMove.LogStore() {
            @Override public List<String> read() {
                try {
                    String text = new String(jf.readAll(rel), StandardCharsets.UTF_8);
                    return new ArrayList<>(Arrays.asList(text.split("\n")));
                } catch (IOException missing) {
                    return new ArrayList<>();
                }
            }

            @Override public void write(List<String> lines) throws IOException {
                jf.writeAtomic(rel, (android.text.TextUtils.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    /** Pointing projects at copies, finding links to originals, removing originals: the parts that need Android. */
    private static final class PhoneHooks implements LegacyFolderMove.Hooks {
        private final Context app;

        PhoneHooks(Context app) {
            this.app = app;
        }

        @Override public void repoint(File from, File to) {
            Map<String, String> moved = new LinkedHashMap<>();
            for (String link : linksTo(from)) moved.put(link, to.getPath());
            StudioMirror.rememberMoved(app, moved);   // every later project load heals links to the original
            StudioMirror.repointOthers(app, moved, null);   // and saved projects are rewritten now
            MediaScannerConnection.scanFile(app, new String[] { to.getPath() }, null, null);
        }

        @Override public boolean stillReferenced(File original) {
            List<String> links = linksTo(original);
            File[] dirs = StudioMirror.projectsRoot(app).listFiles();
            if (dirs == null) return false;
            for (File d : dirs) {
                File json = new File(d, "project.json");
                if (!json.isFile()) continue;
                try {
                    String text = readText(json);
                    for (String link : links) if (text.contains(link)) return true;
                } catch (IOException e) {
                    return true;   // cannot tell: keep the original
                }
            }
            return false;
        }

        @Override public boolean delete(File original) {
            long id = mediaId(original);
            if (id >= 0) {
                try {
                    Uri uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id);
                    app.getContentResolver().delete(uri, null, null);
                } catch (RuntimeException ignored) {
                    // fall through to the plain delete
                }
            }
            return !original.exists() || original.delete();
        }

        private static String readText(File f) throws IOException {
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }

        /** Every way a project may have written down this file: its path, file:// link and media links. */
        private List<String> linksTo(File f) {
            List<String> links = new ArrayList<>();
            links.add(f.getPath());
            links.add(Uri.fromFile(f).toString());
            long id = mediaId(f);
            if (id >= 0) {
                links.add("content://media/external/file/" + id);
                links.add("content://media/external/video/media/" + id);
                links.add("content://media/external/images/media/" + id);
                links.add("content://media/external/audio/media/" + id);
                links.add("content://media/external/downloads/" + id);
                links.add("content://com.android.providers.media.documents/document/video%3A" + id);
                links.add("content://com.android.providers.media.documents/document/image%3A" + id);
                links.add("content://com.android.providers.media.documents/document/audio%3A" + id);
            }
            return links;
        }

        @SuppressWarnings("deprecation")   // DATA is the only way to find a file's media id by its path
        private long mediaId(File f) {
            try (Cursor c = app.getContentResolver().query(MediaStore.Files.getContentUri("external"),
                    new String[] { MediaStore.MediaColumns._ID }, MediaStore.MediaColumns.DATA + "=?",
                    new String[] { f.getPath() }, null)) {
                if (c != null && c.moveToFirst()) return c.getLong(0);
            } catch (RuntimeException ignored) {
                // no media id: links by path still work
            }
            return -1;
        }
    }
}
