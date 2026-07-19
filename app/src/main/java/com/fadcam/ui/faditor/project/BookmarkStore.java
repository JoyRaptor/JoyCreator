package com.fadcam.ui.faditor.project;

import androidx.annotation.NonNull;

import com.fadcam.FLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Standalone sidecar persistence for timeline bookmarks (KineMaster-class playhead
 * lane, JoyRaptor 2026-07-19).
 *
 * <p>Reads/writes {@code <projectDir>/bookmarks.json} — a tiny
 * {@code {"bookmarksMs":[..]}} document — WITHOUT touching the held
 * {@link ProjectStorage}. This is deliberately a separate file so the bookmark
 * feature can ship while {@code ProjectStorage} is frozen for the GL review; the
 * TODO is to fold {@code bookmarksMs} into the project.json path
 * (ProjectSerializer/ProjectDeserializer) once that lands. See
 * tasks/PLAYHEAD_KINEMASTER_20260719.md.</p>
 */
public final class BookmarkStore {

    private static final String TAG = "BookmarkStore";
    private static final String FILE_NAME = "bookmarks.json";
    private static final String KEY = "bookmarksMs";

    private BookmarkStore() {}

    @NonNull
    public static File fileFor(@NonNull File projectDir) {
        return new File(projectDir, FILE_NAME);
    }

    /**
     * Load the bookmark list for a project directory. Returns an empty (mutable,
     * sorted) list when the sidecar is missing or unreadable — never null.
     */
    @NonNull
    public static List<Long> load(@NonNull File projectDir) {
        List<Long> out = new ArrayList<>();
        File f = fileFor(projectDir);
        if (!f.exists()) return out;
        try {
            byte[] bytes = readAll(f);
            String json = new String(bytes, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray(KEY);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    long ms = arr.optLong(i, -1L);
                    if (ms >= 0) out.add(ms);
                }
            }
        } catch (JSONException | IOException e) {
            FLog.w(TAG, "load: could not read " + f + ": " + e.getMessage());
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Persist the bookmark list to {@code <projectDir>/bookmarks.json}. An empty
     * list still writes an empty array (so a cleared set is remembered). Failures
     * are logged, not thrown — bookmarks are a convenience layer.
     */
    public static void save(@NonNull File projectDir, @NonNull List<Long> bookmarksMs) {
        try {
            if (!projectDir.exists()) projectDir.mkdirs();
            JSONArray arr = new JSONArray();
            List<Long> sorted = new ArrayList<>(bookmarksMs);
            Collections.sort(sorted);
            for (long ms : sorted) arr.put(ms);
            JSONObject root = new JSONObject();
            root.put(KEY, arr);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            File f = fileFor(projectDir);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(bytes);
            }
        } catch (JSONException | IOException e) {
            FLog.w(TAG, "save: could not write bookmarks for " + projectDir + ": " + e.getMessage());
        }
    }

    @NonNull
    private static byte[] readAll(@NonNull File f) throws IOException {
        long len = f.length();
        byte[] buf = new byte[(int) Math.max(0, len)];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            int off = 0;
            int r;
            while (off < buf.length && (r = fis.read(buf, off, buf.length - off)) != -1) {
                off += r;
            }
        }
        return buf;
    }
}
