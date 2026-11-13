package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * What lets a Joybot job outlive the app (gap F4). Two kinds of file, both private to the app:
 * <ul>
 *   <li>{@code joybot/job.json} — the request running now and its conversation, rewritten every
 *   round and deleted when it ends. If the phone kills the app mid-job, it is still there next
 *   time, and the chat offers "Carry on".</li>
 *   <li>{@code cache/joybot-review/<request>/<n>.json} — the project just before each change of a
 *   Director run, for the Review card's "Undo from here". Cache: Android may clear it, and Undo
 *   from here then says it can't (the editor's own undo still can).</li>
 * </ul>
 */
public final class JobFile {
    private JobFile() { }

    private static final String TAG = "JoybotJobFile";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** A job the app stopped in the middle of. */
    public static final class Interrupted {
        /** The Studio project it ran on, or null for a VisLab, JoyBrush or TextLab job. */
        @Nullable public final String projectId;
        /** Its conversation ({@link JobKey}): where "Carry on" is offered and what it restores. */
        @NonNull public final String key;
        /** The room it ran in, by name ("STUDIO" for a project's job). */
        @NonNull public final String room;
        /** The person's request, in their words. */
        @NonNull public final String text;
        @NonNull public final String undoLabel;
        public final boolean director;
        /** Rounds it had done. */
        public final int round;
        @NonNull final List<JSONObject> history;

        Interrupted(@Nullable String projectId, @NonNull String key, @NonNull String text, @NonNull String undoLabel,
                    boolean director, int round, @NonNull List<JSONObject> history) {
            this.projectId = projectId;
            this.key = key;
            this.room = JobKey.roomOf(key);
            this.text = text;
            this.undoLabel = undoLabel;
            this.director = director;
            this.round = round;
            this.history = history;
        }
    }

    @NonNull
    private static File jobFile(@NonNull Context app) {
        return new File(new File(app.getFilesDir(), "joybot"), "job.json");
    }

    /** Rewrites the running job's file. Never the API key: that is read fresh when it carries on. */
    static void save(@NonNull Context app, @NonNull JoybotAgent.Ask ask, int round,
                     @NonNull java.util.List<JSONObject> history) {
        // A Studio project's job, or one in a room whose work lives outside a project (VisLab,
        // JoyBrush, TextLab: BOT-A16, 11-13). Before, only Studio jobs could carry on.
        if (!JobKey.resumable(ask.projectId, ask.situation.room.name())) return;
        try {
            JSONArray h = new JSONArray();
            synchronized (history) {
                for (JSONObject m : history) h.put(m);
            }
            JSONObject o = new JSONObject()
                    .put("key", ask.key())
                    .put("text", ask.text)
                    .put("undoLabel", ask.undoLabel)
                    .put("director", ask.director)
                    .put("round", round)
                    .put("at", System.currentTimeMillis())
                    .put("history", h);
            if (ask.projectId != null) o.put("projectId", ask.projectId);
            write(jobFile(app), o.toString());
        } catch (Exception e) {
            FLog.w(TAG, "could not save the job: " + e.getMessage());
        }
    }

    /**
     * A request ended: forget ITS job file, not another conversation's. With jobs in several rooms
     * (BOT-A16), a quick VisLab question must not wipe a Studio job the phone killed earlier and
     * that is still waiting for its "Carry on". An unreadable file goes too.
     */
    static void clearIf(@NonNull Context app, @NonNull String key) {
        File f = jobFile(app);
        if (!f.isFile()) return;
        String saved = null;
        try {
            String s = read(f);
            if (s != null) {
                JSONObject o = new JSONObject(s);
                saved = JobKey.fromSaved(o.optString("key", ""), o.optString("projectId", ""));
            }
        } catch (Exception ignored) { }
        if (saved == null || saved.equals(key)) clear(app);
    }

    static void clear(@NonNull Context app) {
        File f = jobFile(app);
        if (f.isFile() && !f.delete()) FLog.w(TAG, "could not delete " + f);
    }

    /** The interrupted job, or null. One older than two days is forgotten: nobody remembers it. */
    @Nullable
    static Interrupted find(@NonNull Context app) {
        File f = jobFile(app);
        if (!f.isFile()) return null;
        try {
            String s = read(f);
            if (s == null) return null;
            JSONObject o = new JSONObject(s);
            if (System.currentTimeMillis() - o.optLong("at", 0) > 2L * 24 * 3600 * 1000) {
                clear(app);
                return null;
            }
            List<JSONObject> h = new ArrayList<>();
            JSONArray a = o.optJSONArray("history");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                if (m != null) h.add(m);
            }
            String pid = o.optString("projectId", "");
            // Files from before 11-13 carry only a project id (they were all Studio jobs).
            String key = JobKey.fromSaved(o.optString("key", ""), pid);
            if (key == null || h.isEmpty()) { clear(app); return null; }
            return new Interrupted(pid.isEmpty() ? null : pid, key, o.optString("text", ""),
                    o.optString("undoLabel", "Joybot"),
                    o.optBoolean("director", false), o.optInt("round", 0), h);
        } catch (Exception e) {
            FLog.w(TAG, "unreadable job file, dropped: " + e.getMessage());
            clear(app);
            return null;
        }
    }

    /** Keeps the project as it was before change {@code n} of request {@code token}; null if it couldn't. */
    @Nullable
    static File keepBefore(@NonNull Context app, @NonNull String token, int n, @NonNull String projectJson) {
        File dir = new File(new File(app.getCacheDir(), "joybot-review"), token);
        if (n == 0) pruneOld(dir.getParentFile(), dir);
        File f = new File(dir, n + ".json");
        try {
            write(f, projectJson);
            return f;
        } catch (Exception e) {
            FLog.w(TAG, "could not keep a review snapshot: " + e.getMessage());
            return null;
        }
    }

    /** Only the last few jobs' snapshots are worth their space. */
    private static void pruneOld(@Nullable File root, @NonNull File keep) {
        File[] jobs = root == null ? null : root.listFiles();
        if (jobs == null || jobs.length < 4) return;
        java.util.Arrays.sort(jobs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 3; i < jobs.length; i++) {
            if (jobs[i].equals(keep)) continue;
            File[] in = jobs[i].listFiles();
            if (in != null) for (File x : in) x.delete();
            jobs[i].delete();
        }
    }

    @Nullable
    static String read(@Nullable File f) {
        if (f == null || !f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), UTF8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * AtomicFile: the old file stays readable until the new one is complete, so a kill at any
     * moment leaves one whole file (the delete-then-rename it replaced had a gap with none, audit J10).
     */
    private static void write(@NonNull File f, @NonNull String s) throws Exception {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new Exception("no folder " + dir);
        android.util.AtomicFile af = new android.util.AtomicFile(f);
        FileOutputStream out = af.startWrite();
        try {
            out.write(s.getBytes(UTF8));
            af.finishWrite(out);
        } catch (Exception e) {
            af.failWrite(out);
            throw e;
        }
    }
}
