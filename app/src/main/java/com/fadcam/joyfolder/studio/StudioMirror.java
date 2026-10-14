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
import com.fadcam.joyfolder.UsageMap;
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

    /**
     * Where the project's own file is in the Joy Creator folder ("Client A/Promo.joyproject"), or
     * null when it hasn't been saved there yet. Exports go beside it. Folder I/O: not the main thread.
     */
    public static String folderPathOf(Context context, String projectId) {
        JoyFolder jf = JoyFolderHome.open(context);
        if (jf == null) return null;
        State st = State.load(jf);
        String rel = st.projectPath(projectId);
        try {
            if (rel != null && jf.fs.stat(rel) != null) return rel;
            return findById(jf, projectId);
        } catch (IOException e) {
            return null;
        }
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
        final java.util.Set<String> outside = new java.util.LinkedHashSet<>();
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
            if (isOutside(context, value)) outside.add(value);
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
        rememberOutside(context, projectId, outside);
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

    // ── Gather into one folder (the folder's Consolidate) ────────────────

    /** What gathering a project would do, worked out before anything moves (for the dialog). */
    public static final class Gather {
        public com.fadcam.joyfolder.ConsolidatePlan plan;
        public String projectName;
        /** True when the user already has an Assets folder (shared files default to it). */
        public boolean hasAssets;
        /** Recordings the project uses inside the Recordings folder (the "also move recordings" box). */
        public int recordingsUsed;
        Map<String, String> idByWork = new HashMap<>();
        java.util.List<String> allFiles;
        java.util.Set<String> dirs;
        UsageMap usage;
        String workRel;
    }

    /**
     * Plan "Gather <project> into one folder". Mirrors the project first so its folder copy is
     * current. Folder I/O: call on {@link JoyFolderHome#io()}. Null when the folder isn't reachable.
     */
    public static Gather planGather(Context context, String projectId,
                                    com.fadcam.joyfolder.ConsolidatePlan.Options opts) throws IOException {
        JoyFolder jf = JoyFolderHome.open(context);
        if (jf == null) return null;
        mirror(context, projectId);
        State st = State.load(jf);
        String workRel = st.projectPath(projectId);
        if (workRel == null || jf.fs.stat(workRel) == null) workRel = findById(jf, projectId);
        if (workRel == null) return null;

        Gather g = new Gather();
        g.workRel = workRel;
        g.projectName = FileKind.baseOf(Placement.nameOf(workRel));
        g.hasAssets = jf.roles.get(com.fadcam.joyfolder.FolderRoles.Role.ASSETS) != null;
        jf.index.scan(jf.fs);
        g.allFiles = new ArrayList<>();
        for (FolderIndex.Item it : jf.index.items()) g.allFiles.add(it.path);
        g.dirs = new java.util.HashSet<>();
        for (String d : new String[]{"", Placement.parentOf(workRel)}) {
            for (JoyFs.Entry e : jf.fs.list(d)) if (e.dir) g.dirs.add(Placement.join(d, e.name));
        }
        java.util.List<String> used = readUsage(jf, st, g);
        String recordings = jf.roles.get(com.fadcam.joyfolder.FolderRoles.Role.RECORDINGS);
        if (recordings != null) for (String f : used) if (Placement.isInside(f, recordings)) g.recordingsUsed++;
        g.plan = com.fadcam.joyfolder.ConsolidatePlan.plan(workRel, used, g.allFiles, g.dirs, g.usage, jf.roles, opts);
        return g;
    }

    /**
     * Which works use which files, from every project's folder copy, into {@code g}.
     * @return the files {@code g.workRel} uses
     */
    private static java.util.List<String> readUsage(JoyFolder jf, State st, Gather g) {
        g.usage = new UsageMap();
        java.util.List<String> used = new ArrayList<>();
        for (Map.Entry<String, State.Project> p : st.projects.entrySet()) g.idByWork.put(p.getValue().path, p.getKey());
        for (FolderIndex.Item it : jf.index.items()) {
            if (FileKind.of(Placement.nameOf(it.path)) != FileKind.PROJECT) continue;
            for (String f : filesOfCopy(jf, it.path)) {
                g.usage.add(it.path, f);
                if (it.path.equals(g.workRel)) used.add(f);
            }
        }
        return used;
    }

    // ── Earned folders ───────────────────────────────────────────────────

    /**
     * A folder worth offering now ("38 recordings at the top: put them in a Recordings folder?"),
     * or null. Recordings are known by the recorder's names. Folder I/O: call on {@link JoyFolderHome#io()}.
     */
    public static com.fadcam.joyfolder.Suggestions.Suggestion suggestion(
            Context context, java.util.Set<com.fadcam.joyfolder.FolderRoles.Role> dismissed) throws IOException {
        JoyFolder jf = JoyFolderHome.open(context);
        if (jf == null) return null;
        if (jf.roles.reconcile(jf.fs)) jf.saveRoles();
        jf.index.scan(jf.fs);
        java.util.List<com.fadcam.joyfolder.Suggestions.Suggestion> all = com.fadcam.joyfolder.Suggestions.check(
                jf.index, jf.roles, dismissed,
                name -> com.fadcam.utils.RecordingStoragePaths.JoyKind.ofName(name) != null);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * "Yes" to a suggestion: make the folder, move the pile in, give the folder its role so new
     * recordings (or exports) go there too, and keep every project pointing at its files.
     *
     * @return how many files moved
     */
    public static int fileAway(Context context, com.fadcam.joyfolder.Suggestions.Suggestion s) throws IOException {
        JoyFolder jf = JoyFolderHome.open(context);
        if (jf == null || s == null) return 0;
        jf.index.scan(jf.fs);
        Gather g = new Gather();
        g.allFiles = new ArrayList<>();
        for (FolderIndex.Item it : jf.index.items()) g.allFiles.add(it.path);
        g.plan = com.fadcam.joyfolder.Suggestions.plan(s, g.allFiles);
        readUsage(jf, State.load(jf), g);
        jf.fs.mkdirs(g.plan.boxDir);
        jf.roles.set(s.role, g.plan.boxDir);
        jf.saveRoles();
        return applyGather(context, g);
    }

    /** The folder files a project's folder copy points at (its joy: references). */
    private static java.util.List<String> filesOfCopy(JoyFolder jf, String rel) {
        java.util.List<String> out = new ArrayList<>();
        try {
            JsonObject copy = JsonParser.parseString(new String(jf.readAll(rel), StandardCharsets.UTF_8)).getAsJsonObject();
            if (copy.has("joyFolder") && copy.getAsJsonObject("joyFolder").has("files")) {
                for (Map.Entry<String, JsonElement> e : copy.getAsJsonObject("joyFolder").getAsJsonObject("files").entrySet()) {
                    if (jf.fs.stat(e.getKey()) != null) out.add(e.getKey());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * Carry out a gather plan: move the files, then bring every affected project's records up to
     * date (its folder copy, and the app's own copy for projects that point into the folder).
     *
     * @return how many files moved
     */
    public static int applyGather(Context context, Gather g) throws IOException {
        JoyFolder jf = JoyFolderHome.open(context);
        if (jf == null || g == null) return 0;
        State st = State.load(jf);
        int moved = 0;
        java.util.Set<String> touchedWorks = new java.util.HashSet<>();
        for (com.fadcam.joyfolder.ConsolidatePlan.Move m : g.plan.moves) {
            String toDir = Placement.parentOf(m.to);
            String toName = Placement.nameOf(m.to);
            String fromName = Placement.nameOf(m.from);
            jf.fs.mkdirs(toDir);
            // Rename first when the name changes, so the move can't collide in the target folder.
            String source = m.from;
            if (!fromName.equals(toName)) {
                if (!jf.fs.rename(m.from, toName)) throw new IOException("Couldn't rename " + fromName);
                source = Placement.join(Placement.parentOf(m.from), toName);
            }
            if (!Placement.parentOf(source).equals(toDir) && !jf.fs.move(source, toDir)) {
                throw new IOException("Couldn't move " + fromName + " into " + toDir);
            }
            moved++;
            // Keep the bookkeeping pointing at where things are now.
            for (Map.Entry<String, State.Media> e : st.media.entrySet()) {
                if (e.getValue().path.equals(m.from)) e.setValue(new State.Media(m.to, e.getValue().fp));
            }
            for (Map.Entry<String, State.Project> e : st.projects.entrySet()) {
                if (e.getValue().path.equals(m.from)) e.setValue(new State.Project(m.to, null));
            }
            for (String w : g.usage.worksUsing(m.from)) touchedWorks.add(w);
            if (m.from.equals(g.workRel)) touchedWorks.add(g.workRel);
        }
        st.save(jf);
        jf.index.scan(jf.fs);
        jf.saveIndex();
        // Rewrite each affected project: the app copy (if it points into the folder) and the folder copy.
        for (String w : touchedWorks) {
            String id = g.idByWork.get(w);
            if (id == null) continue;
            healMovedFiles(context, id, new File(new File(projectsRoot(context), id), "project.json"));
            mirror(context, id);
        }
        return moved;
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

    // ── Files outside the folder ─────────────────────────────────────────
    //
    // The owner, 2026-10-14: really large files are linked where they are rather than copied, with a
    // warning and a Copy button; a project can be gathered into the folder at any time; and there's an
    // easy way to see which projects still use files from outside it (the yellow dot).

    private static final String OUTSIDE_PREFS = "joy_outside";

    /** How much of a project lives outside the Joy Creator folder (and outside the app). */
    public static final class Outside {
        public final int count;
        public final long bytes;

        Outside(int count, long bytes) {
            this.count = count;
            this.bytes = bytes;
        }
    }

    /**
     * A link to a file that is neither in the Joy Creator folder nor inside the app: the gallery, Downloads, another
     * app's folder. Removing it there breaks the project, and a new phone won't have it. Main thread is fine.
     */
    public static boolean isOutside(Context context, String ref) {
        if (ref == null || ref.startsWith(JOY) || ref.startsWith("project://")) return false;
        Uri uri = parse(ref);
        if (uri == null) return false;
        if ("content".equals(uri.getScheme())) {
            String auth = uri.getAuthority();
            if (auth == null || auth.startsWith(context.getPackageName())) return false;
            // A folder link (the asset browser's pinned folder, its history) is not a file.
            String p = uri.getPath();
            if (p != null && p.startsWith("/tree/") && !p.contains("/document/")) return false;
            return !inJoyFolder(context, usageKey(ref));
        }
        String path = ref.startsWith("file://") ? uri.getPath() : ref.startsWith("/") ? ref : null;
        if (path == null) return false;
        File f = new File(path);
        return !insideApp(context, f) && !inJoyFolder(context, path) && f.isFile();
    }

    private static boolean inJoyFolder(Context context, String key) {
        Uri tree = JoyFolderHome.treeUri(context);
        if (tree == null || key == null) return false;
        String root = usageKey(tree.toString());
        return key.equals(root) || key.startsWith(root + "/");
    }

    /** The last count for a project's outside files, from its last save; null when there are none. */
    public static Outside outside(Context context, String projectId) {
        String v = context.getSharedPreferences(OUTSIDE_PREFS, Context.MODE_PRIVATE).getString(projectId, null);
        if (v == null) return null;
        try {
            int bar = v.indexOf('|');
            int n = Integer.parseInt(v.substring(0, bar));
            return n > 0 ? new Outside(n, Long.parseLong(v.substring(bar + 1))) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void rememberOutside(Context context, String projectId, java.util.Collection<String> refs) {
        long bytes = 0;
        for (String r : refs) bytes += Math.max(0, sizeOf(context, r));
        android.content.SharedPreferences.Editor ed =
                context.getSharedPreferences(OUTSIDE_PREFS, Context.MODE_PRIVATE).edit();
        if (refs.isEmpty()) ed.remove(projectId);
        else ed.putString(projectId, refs.size() + "|" + bytes);
        ed.apply();
    }

    /** A file's size in bytes, or -1 when it can't be told. */
    public static long sizeOf(Context context, String ref) {
        Uri uri = parse(ref);
        if (uri == null) return -1;
        if ("content".equals(uri.getScheme())) {
            try (android.content.res.AssetFileDescriptor fd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                return fd == null ? -1 : fd.getLength();
            } catch (Exception e) {
                return -1;
            }
        }
        String path = ref.startsWith("file://") ? uri.getPath() : ref;
        return path == null ? -1 : new File(path).length();
    }

    /** The outside files a saved project links to, read fresh from its file. Not the main thread. */
    public static java.util.List<String> outsideFiles(Context context, String projectId) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        File json = new File(new File(projectsRoot(context), projectId), "project.json");
        try {
            rewrite(JsonParser.parseString(readText(json)), value -> {
                if (isOutside(context, value)) out.add(value);
                return value;
            });
        } catch (Exception ignored) {
        }
        return new ArrayList<>(out);
    }

    /**
     * Copy one outside file into the folder, beside the project (the owner's rule for things that belong to a
     * work), named as it was. The same file brought in twice is one copy. Returns its link in the folder.
     * Folder I/O: call on {@link JoyFolderHome#io()}.
     */
    public static Uri bringIn(Context context, String projectId, String ref) throws IOException {
        JoyFolder jf = JoyFolderHome.open(context);
        SafJoyFs saf = JoyFolderHome.fsOf(jf);
        if (jf == null || saf == null) throw new IOException("your Joy Creator folder can't be reached");
        Uri uri = parse(ref);
        if (uri == null) throw new IOException("not a file link");
        String workRel = null;
        try {
            workRel = folderPathOf(context, projectId);
        } catch (Exception ignored) {
        }
        String name;
        InputStream in;
        if ("content".equals(uri.getScheme())) {
            name = displayName(context, uri);
            in = context.getContentResolver().openInputStream(uri);
        } else {
            File f = new File(ref.startsWith("file://") ? uri.getPath() : ref);
            name = f.getName();
            in = new FileInputStream(f);
        }
        if (in == null) throw new IOException("can't read " + name);
        String rel;
        try (InputStream src = in) {
            String dir = jf.prepareDir(Placement.What.IMPORT, workRel, null, null);
            rel = jf.addFile(dir, name, src, "import");
        }
        // Already there under another name? Keep the first copy.
        FolderIndex.Item it = jf.index.get(rel);
        if (it != null && it.fingerprint != null) {
            for (FolderIndex.Item other : jf.index.items()) {
                if (!other.path.equals(rel) && it.fingerprint.equals(other.fingerprint) && jf.fs.stat(other.path) != null) {
                    jf.fs.delete(rel);
                    jf.index.remove(rel);
                    rel = other.path;
                    break;
                }
            }
        }
        jf.saveIndex();
        return saf.documentUri(rel);
    }

    /**
     * Gather a closed project's outside files into the folder: each is copied in and the project's links are
     * pointed at the copies. The editor does the same for an open project in memory. Returns how many.
     * Folder I/O: call on {@link JoyFolderHome#io()}.
     */
    public static int bringInAll(Context context, String projectId, Progress progress) throws IOException {
        File json = new File(new File(projectsRoot(context), projectId), "project.json");
        Map<String, String> moved = new HashMap<>();
        for (String ref : outsideFiles(context, projectId)) {
            if (progress != null) progress.onStep(displayName(context, Uri.parse(ref)));
            moved.put(ref, bringIn(context, projectId, ref).toString());
        }
        if (moved.isEmpty()) return 0;
        JsonElement root = JsonParser.parseString(readText(json));
        rewrite(root, value -> moved.containsKey(value) ? moved.get(value) : value);
        File tmp = new File(json.getPath() + ".joytmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(json)) throw new IOException("couldn't update the project file");
        mirror(context, projectId);
        return moved.size();
    }

    // ── "Not used in any project" ────────────────────────────────────────

    /**
     * Every file any Studio project points at, as {@link #usageKey} keys, for the asset browser's
     * "not used in any project" filter. Reads each project's saved file; call off the main thread.
     */
    public static java.util.Set<String> usedByAnyProject(Context context) {
        java.util.Set<String> used = new java.util.HashSet<>();
        File[] dirs = projectsRoot(context).listFiles();
        if (dirs == null) return used;
        for (File d : dirs) {
            File json = new File(d, "project.json");
            if (!json.isFile()) continue;
            try {
                rewrite(JsonParser.parseString(readText(json)), value -> {
                    used.add(usageKey(value));
                    return value;
                });
            } catch (Exception ignored) {
            }
        }
        // The folder's own copies count too: media copied in so a project survives an uninstall is
        // linked only from the project's folder copy (the app keeps working from its own copy).
        try {
            JoyFolder jf = JoyFolderHome.open(context);
            SafJoyFs saf = JoyFolderHome.fsOf(jf);
            if (jf != null && saf != null) {
                jf.index.scan(jf.fs);
                for (FolderIndex.Item it : jf.index.items()) {
                    if (FileKind.of(Placement.nameOf(it.path)) != FileKind.PROJECT) continue;
                    for (String f : filesOfCopy(jf, it.path)) used.add(usageKey(saf.documentUri(f).toString()));
                }
            }
        } catch (Exception ignored) {
        }
        return used;
    }

    /**
     * Where each link sits in the Joy Creator folder ("Clips/x.mp4"), or null for links outside
     * it. Folder I/O: call on {@link JoyFolderHome#io()}.
     */
    public static java.util.List<String> folderPathsOf(Context context, java.util.List<Uri> uris) {
        java.util.List<String> out = new ArrayList<>();
        SafJoyFs saf = JoyFolderHome.fsOf(JoyFolderHome.open(context));
        for (Uri u : uris) out.add(saf == null ? null : saf.relOf(u));
        return out;
    }

    /**
     * Move folder files to the one Trash (restorable from "Recently deleted").
     * Folder I/O: call on {@link JoyFolderHome#io()}.
     *
     * @return how many went
     */
    public static int trashFiles(Context context, java.util.List<String> rels) throws IOException {
        JoyFolder jf = JoyFolderHome.open(context);
        if (jf == null) return 0;
        int n = 0;
        long now = System.currentTimeMillis();
        for (String rel : rels) {
            if (rel == null || jf.fs.stat(rel) == null) continue;
            jf.trash(rel, now);
            n++;
        }
        jf.saveIndex();
        return n;
    }

    /**
     * One spelling for one file, whichever way a project linked it: a folder-picker link
     * ({@code content://…/document/primary%3AJoy%20Creator%2Fx.mp4}), a {@code file://} link or a
     * plain path all become {@code /storage/emulated/0/Joy Creator/x.mp4}. Other links stay as they are.
     */
    public static String usageKey(String ref) {
        if (ref.startsWith("content://")) {
            Uri uri = parse(ref);
            if (uri != null && "com.android.externalstorage.documents".equals(uri.getAuthority())) {
                try {
                    String docId;
                    try {
                        docId = android.provider.DocumentsContract.getDocumentId(uri);
                    } catch (IllegalArgumentException folderOnly) {
                        docId = android.provider.DocumentsContract.getTreeDocumentId(uri);
                    }
                    int c = docId.indexOf(':');
                    if (c > 0) {
                        String volume = docId.substring(0, c);
                        return ("primary".equalsIgnoreCase(volume) ? "/storage/emulated/0" : "/storage/" + volume)
                                + "/" + docId.substring(c + 1);
                    }
                } catch (RuntimeException ignored) {
                }
            }
            return ref;
        }
        if (ref.startsWith("file://")) {
            Uri uri = parse(ref);
            return uri != null && uri.getPath() != null ? uri.getPath() : ref;
        }
        return ref;
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
