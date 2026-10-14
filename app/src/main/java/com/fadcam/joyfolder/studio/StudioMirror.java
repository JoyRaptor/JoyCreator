package com.fadcam.joyfolder.studio;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.FolderIndex;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.ReadableNames;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.android.SafJoyFs;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps every Studio project in the user's Joy Creator folder, so uninstalling the app loses
 * nothing.
 *
 * <p>The app keeps editing its own fast working copy exactly as before. A few seconds after each
 * save, the project is written to the folder as {@code <Project name>.joyproject}: the same
 * readable JSON, with every file that only lives inside the app (imported pictures, extracted
 * audio, clips under {@code Android/data}) copied into the folder beside it and referred to by its
 * path in the folder ({@code joy:Lecture 3 image.jpg}). Files the user owns elsewhere (SD card,
 * Downloads) are left where they are: an uninstall can't touch them.</p>
 *
 * <p>After a reinstall, {@link #restoreMissing} reads those files back and points each project at
 * its media inside the folder. Files moved around in a file manager are found again by
 * fingerprint.</p>
 */
public final class StudioMirror {

    private static final String TAG = "StudioMirror";
    private static final String STATE = JoyFolder.HIDDEN + "/studio.json";
    static final String JOY = "joy:";
    private static final long DEBOUNCE_MS = 4000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Runnable> PENDING = new HashMap<>();

    private StudioMirror() {}

    // ── Hooks ────────────────────────────────────────────────────────────

    /** Called after every successful project save. Cheap: schedules a mirror a few seconds later. */
    public static void onProjectSaved(Context context, String projectId) {
        final Context app = context.getApplicationContext();
        if (!JoyFolderHome.isChosen(app)) return;
        MAIN.post(() -> {
            Runnable old = PENDING.remove(projectId);
            if (old != null) MAIN.removeCallbacks(old);
            Runnable r = new Runnable() {
                @Override public void run() {
                    PENDING.remove(projectId);
                    JoyFolderHome.io().execute(() -> {
                        try {
                            mirror(app, projectId);
                        } catch (Exception e) {
                            Log.w(TAG, "Mirror failed for " + projectId, e);
                        }
                    });
                }
            };
            PENDING.put(projectId, r);
            MAIN.postDelayed(r, DEBOUNCE_MS);
        });
    }

    /** Called when the user deletes a project in the app: its folder copy goes to Trash. */
    public static void onProjectDeleted(Context context, String projectId) {
        final Context app = context.getApplicationContext();
        if (!JoyFolderHome.isChosen(app)) return;
        JoyFolderHome.io().execute(() -> {
            try {
                JoyFolder jf = JoyFolderHome.open(app);
                if (jf == null) return;
                State st = State.load(jf);
                String rel = st.projectPath(projectId);
                if (rel != null && jf.fs.stat(rel) != null) {
                    String trashed = jf.trash(rel, System.currentTimeMillis());
                    synchronized (RECENTLY_TRASHED) {
                        RECENTLY_TRASHED.put(projectId, trashed);
                    }
                }
                st.projects.remove(projectId);
                st.save(jf);
            } catch (Exception e) {
                Log.w(TAG, "Could not move the folder copy of " + projectId + " to Trash", e);
            }
        });
    }

    /** Project id → its name in the folder's Trash, for the Undo right after a delete. */
    private static final Map<String, String> RECENTLY_TRASHED = new HashMap<>();

    /**
     * Undo a delete just made: put the projects' folder copies back and bring them into the app.
     * Runs on the folder thread; {@code onDone} is called on the main thread with how many came back.
     */
    public static void undoDelete(Context context, java.util.Collection<String> projectIds,
                                  java.util.function.IntConsumer onDone) {
        final Context app = context.getApplicationContext();
        final List<String> ids = new ArrayList<>(projectIds);
        JoyFolderHome.io().execute(() -> {   // queued after the deletes, so their Trash names exist
            int back = 0;
            try {
                JoyFolder jf = JoyFolderHome.open(app);
                if (jf != null) {
                    for (String id : ids) {
                        String name;
                        synchronized (RECENTLY_TRASHED) {
                            name = RECENTLY_TRASHED.remove(id);
                        }
                        if (name == null) continue;
                        jf.restore(name);
                    }
                    back = restoreMissing(app, null);
                }
            } catch (Exception e) {
                Log.w(TAG, "Undo delete failed", e);
            }
            final int n = back;
            MAIN.post(() -> onDone.accept(n));
        });
    }

    // ── Mirror ───────────────────────────────────────────────────────────

    /** Progress for the setup screen. */
    public interface Progress {
        void onStep(String what);
    }

    public static final class Result {
        public int projects;
        public int filesCopied;
        public long bytesCopied;
        public final List<String> problems = new ArrayList<>();
    }

    /** Mirror every project now. Runs on the calling thread: use {@link JoyFolderHome#io()}. */
    public static Result mirrorAll(Context context, Progress progress) {
        Result res = new Result();
        File root = projectsRoot(context);
        File[] dirs = root.listFiles();
        if (dirs == null) return res;
        JoyFolder folder = JoyFolderHome.open(context);
        if (folder != null) {
            try {
                folder.index.scan(folder.fs);   // so files already in the folder are reused, not copied again
            } catch (IOException e) {
                res.problems.add("Couldn't read the folder: " + e.getMessage());
            }
        }
        for (File d : dirs) {
            if (!new File(d, "project.json").isFile()) continue;
            try {
                Result one = mirror(context, d.getName(), progress);
                res.projects++;
                res.filesCopied += one.filesCopied;
                res.bytesCopied += one.bytesCopied;
                res.problems.addAll(one.problems);
            } catch (Exception e) {
                res.problems.add(d.getName() + ": " + e.getMessage());
            }
        }
        JoyFolder jf = JoyFolderHome.open(context);
        if (jf != null) {
            try {
                jf.saveIndex();
            } catch (IOException ignored) {
            }
        }
        return res;
    }

    static Result mirror(Context context, String projectId) throws IOException {
        return mirror(context, projectId, null);
    }

    static synchronized Result mirror(Context context, String projectId, Progress progress) throws IOException {
        Result res = new Result();
        JoyFolder jf = JoyFolderHome.open(context);
        SafJoyFs saf = JoyFolderHome.fsOf(jf);
        if (jf == null) return res;
        File projectDir = new File(projectsRoot(context), projectId);
        File json = new File(projectDir, "project.json");
        if (!json.isFile()) return res;
        JsonObject root = JsonParser.parseString(readText(json)).getAsJsonObject();
        String name = root.has("name") && root.get("name").isJsonPrimitive() ? root.get("name").getAsString() : "Untitled";
        if (progress != null) progress.onStep(name);

        State st = State.load(jf);
        String workRel = placeProject(jf, st, projectId, name);

        boolean copyMedia = JoyFolderHome.copyMediaAllowed(context);
        Map<String, String> files = new LinkedHashMap<>();
        final java.util.concurrent.atomic.AtomicReference<Map<String, String>> known =
                new java.util.concurrent.atomic.AtomicReference<>();
        rewrite(root, value -> {
            Uri uri = parse(value);
            // Already in the folder (a restored project): keep it as a folder path.
            if (saf != null && uri != null && "content".equals(uri.getScheme())) {
                String rel = saf.relOf(uri);
                if (rel != null) {
                    String fp;
                    if (jf.fs.stat(rel) != null) {
                        fp = jf.index.fingerprintOf(jf.fs, rel);
                    } else {
                        // Moved since the project loaded: keep what we knew, so it can still be found.
                        if (known.get() == null) known.set(knownFingerprints(jf, projectId));
                        fp = known.get().get(rel);
                    }
                    files.put(rel, fp);
                    return JOY + rel;
                }
            }
            if (value.startsWith(JOY)) return value;
            if (!copyMedia) return value;
            Source src = atRisk(context, projectDir, value, uri);
            if (src == null) return value;
            String rel = copyIn(context, jf, st, src, workRel, name, res);
            if (rel == null) return value;
            files.put(rel, st.mediaFingerprint(rel));
            return JOY + rel;
        });
        JsonObject block = new JsonObject();
        block.addProperty("version", 1);
        JsonObject fps = new JsonObject();
        for (Map.Entry<String, String> e : files.entrySet()) fps.addProperty(e.getKey(), e.getValue());
        block.add("files", fps);
        root.add("joyFolder", block);

        byte[] bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        String hash = sha(bytes);
        State.Project p = st.projects.get(projectId);
        if (p == null || !hash.equals(p.hash) || jf.fs.stat(workRel) == null) {
            jf.writeAtomic(workRel, bytes);
            JoyFs.Entry e = jf.fs.stat(workRel);
            if (e != null) jf.index.put(workRel, e.size, e.modified, null, "mirror");
        }
        st.projects.put(projectId, new State.Project(workRel, hash));
        st.save(jf);
        if (res.filesCopied > 0) jf.saveIndex();
        return res;
    }

    /** The project's path in the folder: where it was, found again if moved, renamed with the project. */
    private static String placeProject(JoyFolder jf, State st, String projectId, String name) throws IOException {
        String desired = ReadableNames.sanitize(name) + FileKind.PROJECT.ending();
        String rel = st.projectPath(projectId);
        if (rel != null && jf.fs.stat(rel) == null) rel = findById(jf, projectId);
        if (rel == null) {
            String dir = jf.prepareDir(Placement.What.NEW_WORK, null, null, null);
            return Placement.join(dir, jf.freeName(dir, desired));
        }
        String current = Placement.nameOf(rel);
        if (!current.equalsIgnoreCase(desired)) {
            String dir = Placement.parentOf(rel);
            String fresh = jf.freeName(dir, desired);
            if (jf.fs.rename(rel, fresh)) return Placement.join(dir, fresh);
        }
        return rel;
    }

    /** A .joyproject the user moved or renamed in a file manager, found by the id inside it. */
    private static String findById(JoyFolder jf, String projectId) throws IOException {
        jf.index.scan(jf.fs);
        for (FolderIndex.Item it : jf.index.items()) {
            if (FileKind.of(Placement.nameOf(it.path)) != FileKind.PROJECT) continue;
            if (projectId.equals(readId(jf, it.path))) return it.path;
        }
        return null;
    }

    private static String readId(JoyFolder jf, String rel) {
        try (InputStream in = jf.fs.open(rel)) {
            byte[] head = new byte[8192];
            int n = in.read(head);
            if (n <= 0) return null;
            String s = new String(head, 0, n, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(s);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ── What is at risk ──────────────────────────────────────────────────

    /** A file the app holds that an uninstall would delete. */
    private static final class Source {
        File file;          // a local file, or
        Uri uri;            // one of our own content URIs
        String name;
        long size;
        long modified;

        String key() {
            return (file != null ? file.getAbsolutePath() : uri.toString()) + "|" + size + "|" + modified;
        }

        InputStream open(Context c) throws IOException {
            if (file != null) return new FileInputStream(file);
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("Cannot read " + uri);
            return in;
        }
    }

    private static Source atRisk(Context c, File projectDir, String value, Uri uri) {
        File f = null;
        if (value.startsWith("project://")) {
            f = new File(projectDir, value.substring("project://".length()));
        } else if (value.startsWith("file://") && uri != null && uri.getPath() != null) {
            f = new File(uri.getPath());
        } else if (value.startsWith("/")) {
            f = new File(value);
        }
        if (f != null) {
            if (!f.isFile() || !insideApp(c, f)) return null;
            Source s = new Source();
            s.file = f;
            s.name = f.getName();
            s.size = f.length();
            s.modified = f.lastModified();
            return s;
        }
        if (uri != null && "content".equals(uri.getScheme()) && uri.getAuthority() != null
                && uri.getAuthority().startsWith(c.getPackageName())) {
            Source s = new Source();
            s.uri = uri;
            s.name = displayName(c, uri);
            s.size = 0;
            s.modified = 0;
            return s;
        }
        return null;
    }

    /** True for anything under this app's own storage, which Android deletes with the app. */
    static boolean insideApp(Context c, File f) {
        String path = canonical(f);
        List<File> roots = new ArrayList<>();
        roots.add(c.getFilesDir().getParentFile());
        File ext = c.getExternalFilesDir(null);
        if (ext != null && ext.getParentFile() != null) roots.add(ext.getParentFile());
        File[] all = c.getExternalFilesDirs(null);
        if (all != null) for (File d : all) if (d != null && d.getParentFile() != null) roots.add(d.getParentFile());
        for (File r : roots) {
            String rp = canonical(r);
            if (path.equals(rp) || path.startsWith(rp + File.separator)) return true;
        }
        return false;
    }

    private static String copyIn(Context c, JoyFolder jf, State st, Source src, String workRel,
                                 String projectName, Result res) {
        String key = src.key();
        State.Media known = st.media.get(key);
        try {
            if (known != null) {
                String now = jf.index.find(jf.fs, known.path, known.fp);
                if (now != null) {
                    if (!now.equals(known.path)) st.media.put(key, new State.Media(now, known.fp));
                    return now;
                }
            }
            // One copy per file: the same picture or clip used by several projects (each of which
            // may hold its own copy inside the app) is stored in the folder once.
            if (src.file != null) {
                String fp;
                try (InputStream in = new FileInputStream(src.file)) {
                    fp = com.fadcam.joyfolder.Fingerprint.of(in, src.size);
                }
                String same = jf.index.find(jf.fs, null, fp);
                if (same != null) {
                    st.media.put(key, new State.Media(same, fp));
                    return same;
                }
            }
            String dir = jf.prepareDir(Placement.What.IMPORT, workRel, null, null);
            String name = readableMediaName(src.name, projectName);
            String rel;
            try (InputStream in = src.open(c)) {
                rel = jf.addFile(dir, name, in, "mirror");
            }
            FolderIndex.Item it = jf.index.get(rel);
            st.media.put(key, new State.Media(rel, it != null ? it.fingerprint : null));
            res.filesCopied++;
            res.bytesCopied += it != null ? it.size : 0;
            return rel;
        } catch (IOException e) {
            res.problems.add(src.name + ": " + e.getMessage());
            return null;
        }
    }

    /** Keep a human name; replace a code-like one ("d122fdc4-….jpg") with "Lecture 3 image.jpg". */
    static String readableMediaName(String original, String projectName) {
        String name = original == null ? "" : original;
        String base = FileKind.baseOf(name);
        if (!ReadableNames.looksCodeLike(base)) return ReadableNames.sanitize(name);
        String word;
        switch (FileKind.of(name)) {
            case IMAGE: word = "image"; break;
            case VIDEO: word = "video"; break;
            case AUDIO: word = "sound"; break;
            case FONT: word = "font"; break;
            default: word = "file";
        }
        return ReadableNames.sanitize(projectName + " " + word + FileKind.endingOf(name));
    }

    // ── Restore after a reinstall ────────────────────────────────────────

    /**
     * Bring back every project in the folder that the app doesn't have (after a reinstall, or on a
     * new phone). Each project's media is pointed at its file in the folder, found by fingerprint
     * if it was moved. Returns the number restored. Runs on the calling thread.
     */
    public static int restoreMissing(Context context, Progress progress) throws IOException {
        JoyFolder jf = JoyFolderHome.open(context);
        SafJoyFs saf = JoyFolderHome.fsOf(jf);
        if (jf == null || saf == null) return 0;
        jf.index.scan(jf.fs);
        State st = State.load(jf);
        int restored = 0;
        for (FolderIndex.Item it : jf.index.items()) {
            if (FileKind.of(Placement.nameOf(it.path)) != FileKind.PROJECT) continue;
            JsonObject root;
            try {
                root = JsonParser.parseString(new String(jf.readAll(it.path), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                Log.w(TAG, "Not a readable project: " + it.path, e);
                continue;
            }
            if (!root.has("id")) continue;
            String id = root.get("id").getAsString();
            File dir = new File(projectsRoot(context), id);
            if (new File(dir, "project.json").isFile()) {
                if (st.projectPath(id) == null) st.projects.put(id, new State.Project(it.path, null));
                continue;
            }
            if (progress != null) progress.onStep(Placement.nameOf(it.path));
            final Map<String, String> fps = new HashMap<>();
            if (root.has("joyFolder") && root.getAsJsonObject("joyFolder").has("files")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("joyFolder").getAsJsonObject("files").entrySet()) {
                    fps.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString());
                }
            }
            rewrite(root, value -> {
                if (!value.startsWith(JOY)) return value;
                String rel = value.substring(JOY.length());
                String now = jf.index.find(jf.fs, rel, fps.get(rel));
                Uri doc = now == null ? null : saf.documentUri(now);
                return doc == null ? value : doc.toString();   // a missing file shows in the relink screen
            });
            root.remove("joyFolder");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            File tmp = new File(dir, "project.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(new File(dir, "project.json"))) throw new IOException("Cannot save " + id);
            st.projects.put(id, new State.Project(it.path, null));
            restored++;
        }
        st.save(jf);
        return restored;
    }

    // ── Rule 3: moving things never breaks anything ─────────────────────

    /**
     * Before a project loads: any of its files the user moved or renamed inside the Joy Creator
     * folder (with a file manager, or by gathering a project into its own folder) is found again
     * by its contents, and the project is pointed at where it is now. Projects that use nothing
     * inside the folder return straight away.
     *
     * @return true when the project file was updated
     */
    public static boolean healMovedFiles(Context context, String projectId, File projectJson) {
        Context app = context.getApplicationContext();
        if (!JoyFolderHome.isChosen(app) || !projectJson.isFile()) return false;
        try {
            String text = readText(projectJson);
            Uri tree = JoyFolderHome.treeUri(app);
            String treeId = tree == null ? null : android.provider.DocumentsContract.getTreeDocumentId(tree);
            // Fast path: a project pointing at nothing inside the folder needs no folder access at all.
            if (treeId == null || !text.contains(Uri.encode(treeId))) return false;
            JoyFolder jf = JoyFolderHome.open(app);
            SafJoyFs saf = JoyFolderHome.fsOf(jf);
            if (jf == null || saf == null) return false;
            final Map<String, String> fps = knownFingerprints(jf, projectId);
            final boolean[] scanned = {false};
            final boolean[] changed = {false};
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            rewrite(root, value -> {
                Uri u = parse(value);
                String rel = saf.relOf(u);
                if (rel == null || rel.isEmpty() || jf.fs.stat(rel) != null) return value;
                String fp = fps.get(rel);
                if (fp == null) return value;
                if (!scanned[0]) {
                    jf.index.scan(jf.fs);
                    scanned[0] = true;
                }
                String now = jf.index.find(jf.fs, null, fp);
                Uri doc = now == null ? null : saf.documentUri(now);
                if (doc == null) return value;   // truly gone: the relink screen takes it from here
                changed[0] = true;
                return doc.toString();
            });
            if (!changed[0]) return false;
            File tmp = new File(projectJson.getParentFile(), projectJson.getName() + ".heal.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(projectJson)) {
                tmp.delete();
                return false;
            }
            Log.i(TAG, "Found moved files again for " + projectId);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not check " + projectId + " for moved files", e);
            return false;
        }
    }

    /** Folder path → fingerprint for every folder file this project is known to use. */
    private static Map<String, String> knownFingerprints(JoyFolder jf, String projectId) {
        Map<String, String> fps = new HashMap<>();
        State st = State.load(jf);
        for (State.Media m : st.media.values()) if (m.fp != null) fps.put(m.path, m.fp);
        try {
            String rel = st.projectPath(projectId);
            if (rel == null || jf.fs.stat(rel) == null) rel = findById(jf, projectId);
            if (rel != null) {
                JsonObject copy = JsonParser.parseString(new String(jf.readAll(rel), StandardCharsets.UTF_8)).getAsJsonObject();
                if (copy.has("joyFolder") && copy.getAsJsonObject("joyFolder").has("files")) {
                    for (Map.Entry<String, JsonElement> e : copy.getAsJsonObject("joyFolder").getAsJsonObject("files").entrySet()) {
                        if (!e.getValue().isJsonNull()) fps.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fps;
    }

    // ── Consent: how much would be copied ────────────────────────────────

    /** Bytes of media that only lives inside the app and isn't in the folder yet. */
    public static long bytesAtRisk(Context context) {
        long total = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        File[] dirs = projectsRoot(context).listFiles();
        if (dirs == null) return 0;
        for (File d : dirs) {
            File json = new File(d, "project.json");
            if (!json.isFile()) continue;
            try {
                JsonObject root = JsonParser.parseString(readText(json)).getAsJsonObject();
                final File projectDir = d;
                final long[] sum = {0};
                rewrite(root, value -> {
                    Source s = atRisk(context, projectDir, value, parse(value));
                    if (s != null && s.file != null && seen.add(s.file.getAbsolutePath())) sum[0] += s.size;
                    return value;
                });
                total += sum[0];
            } catch (Exception ignored) {
            }
        }
        return total;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    interface Rewriter {
        String apply(String value) throws IOException;
    }

    /** Visit every string in the JSON that looks like a file reference, replacing it in place. */
    static void rewrite(JsonElement el, Rewriter fn) throws IOException {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : new ArrayList<>(o.entrySet())) {
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                    String s = v.getAsString();
                    if (looksLikeReference(s)) {
                        String r = fn.apply(s);
                        if (!r.equals(s)) o.add(e.getKey(), new JsonPrimitive(r));
                    }
                } else {
                    rewrite(v, fn);
                }
            }
        } else if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                JsonElement v = a.get(i);
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                    String s = v.getAsString();
                    if (looksLikeReference(s)) {
                        String r = fn.apply(s);
                        if (!r.equals(s)) a.set(i, new JsonPrimitive(r));
                    }
                } else {
                    rewrite(v, fn);
                }
            }
        }
    }

    static boolean looksLikeReference(String s) {
        return s.startsWith("project://") || s.startsWith("file://") || s.startsWith("content://")
                || s.startsWith(JOY) || (s.startsWith("/") && s.length() > 1 && s.indexOf('/', 1) > 0 && !s.contains("\n"));
    }

    private static Uri parse(String s) {
        try {
            return Uri.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (RuntimeException ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    static File projectsRoot(Context c) {
        return new File(c.getFilesDir(), "faditor/projects");
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String sha(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(java.util.Arrays.hashCode(b));
        }
    }

    // ── Bookkeeping in .joycreator/studio.json ───────────────────────────

    static final class State {
        static final class Project {
            final String path;
            final String hash;

            Project(String path, String hash) {
                this.path = path;
                this.hash = hash;
            }
        }

        static final class Media {
            final String path;
            final String fp;

            Media(String path, String fp) {
                this.path = path;
                this.fp = fp;
            }
        }

        final Map<String, Project> projects = new LinkedHashMap<>();
        final Map<String, Media> media = new LinkedHashMap<>();

        String projectPath(String id) {
            Project p = projects.get(id);
            return p == null ? null : p.path;
        }

        String mediaFingerprint(String rel) {
            for (Media m : media.values()) if (m.path.equals(rel)) return m.fp;
            return null;
        }

        static State load(JoyFolder jf) {
            State st = new State();
            try {
                if (jf.fs.stat(STATE) == null) return st;
                JsonObject o = JsonParser.parseString(new String(jf.readAll(STATE), StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("projects")) {
                    for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("projects").entrySet()) {
                        JsonObject p = e.getValue().getAsJsonObject();
                        st.projects.put(e.getKey(), new Project(p.get("path").getAsString(),
                                p.has("hash") ? p.get("hash").getAsString() : null));
                    }
                }
                if (o.has("media")) {
                    for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("media").entrySet()) {
                        JsonObject m = e.getValue().getAsJsonObject();
                        st.media.put(e.getKey(), new Media(m.get("path").getAsString(),
                                m.has("fp") ? m.get("fp").getAsString() : null));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "studio.json unreadable; starting fresh", e);
            }
            return st;
        }

        void save(JoyFolder jf) throws IOException {
            JsonObject o = new JsonObject();
            JsonObject ps = new JsonObject();
            for (Map.Entry<String, Project> e : projects.entrySet()) {
                JsonObject p = new JsonObject();
                p.addProperty("path", e.getValue().path);
                if (e.getValue().hash != null) p.addProperty("hash", e.getValue().hash);
                ps.add(e.getKey(), p);
            }
            o.add("projects", ps);
            JsonObject ms = new JsonObject();
            for (Map.Entry<String, Media> e : media.entrySet()) {
                JsonObject m = new JsonObject();
                m.addProperty("path", e.getValue().path);
                if (e.getValue().fp != null) m.addProperty("fp", e.getValue().fp);
                ms.add(e.getKey(), m);
            }
            o.add("media", ms);
            jf.writeAtomic(STATE, GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
        }
    }
}
