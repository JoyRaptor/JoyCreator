package com.fadcam.joyfolder.studio;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.markers.MarkerWorkspaceStore;
import com.fadcam.ui.faditor.project.BookmarkStore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * What a project keeps beside {@code project.json} and a {@code .joyproject} must carry too: named
 * bookmarks, regions, export picks and the loop setting ({@code timeline-marks.json}), and the older
 * plain bookmark list ({@code bookmarks.json}).
 *
 * <p>They travel in the file's {@code joyFolder} block as readable JSON, whole, so anything a newer
 * build added comes back untouched. A restore writes them BEFORE {@code project.json}: that file is
 * what makes a project exist, so a restore cut short is simply run again.</p>
 */
final class MirrorSidecars {

    private static final String TAG = "MirrorSidecars";
    static final String MARKS = "timelineMarks";
    static final String BOOKMARKS = "bookmarks";
    /** The editor trusts whichever sidecar was written last, so the backup keeps that order. */
    static final String BOOKMARKS_NEWER = "bookmarksNewer";
    private static final Gson PLAIN = new Gson();

    private MirrorSidecars() {}

    /**
     * Add the project's sidecars to its {@code joyFolder} block.
     *
     * @param lastCopy the block in the folder copy now, asked for only when a sidecar here cannot
     *                 be read: a damaged file never replaces the good backup of it
     */
    static void embed(JsonObject block, File projectDir, Supplier<JsonObject> lastCopy) {
        JsonObject marks = null, bookmarks = null;
        boolean unreadable = false;
        try {
            marks = parse(MarkerWorkspaceStore.readRaw(projectDir));
        } catch (Exception e) {
            FLog.w(TAG, "Keeping the backed-up marks: " + e.getMessage());
            unreadable = true;
        }
        File list = BookmarkStore.fileFor(projectDir);
        try {
            if (list.isFile()) bookmarks = parse(readText(list));
        } catch (Exception e) {
            FLog.w(TAG, "Keeping the backed-up bookmarks: " + e.getMessage());
            unreadable = true;
        }
        JsonObject last = unreadable ? lastCopy.get() : null;
        if (last != null) {
            if (marks == null) marks = objectIn(last, MARKS);
            if (bookmarks == null) bookmarks = objectIn(last, BOOKMARKS);
        }
        if (marks != null) block.add(MARKS, marks);
        if (bookmarks != null) block.add(BOOKMARKS, bookmarks);
        if (marks != null && bookmarks != null) {
            block.addProperty(BOOKMARKS_NEWER, list.lastModified()
                    > MarkerWorkspaceStore.fileFor(projectDir).lastModified());
        }
    }

    /**
     * Write back what {@link #embed} carried, before the caller commits {@code project.json}.
     *
     * @throws IOException when a sidecar cannot be written: the caller stops, leaving no project
     *                     that looks complete without its marks
     */
    static void restore(JsonObject block, File dir) throws IOException {
        if (block == null) return;
        JsonObject marks = objectIn(block, MARKS);
        JsonObject bookmarks = objectIn(block, BOOKMARKS);
        boolean bookmarksLast = block.has(BOOKMARKS_NEWER) && block.get(BOOKMARKS_NEWER).isJsonPrimitive()
                && block.get(BOOKMARKS_NEWER).getAsBoolean();
        if (bookmarks != null && !bookmarksLast) writeBookmarks(dir, bookmarks);
        if (marks != null && !MarkerWorkspaceStore.writeRaw(dir, PLAIN.toJson(marks))) {
            throw new IOException("Could not restore the timeline marks");
        }
        if (bookmarks != null && bookmarksLast) {
            writeBookmarks(dir, bookmarks);
            // File times can tick slowly; "newer" has to stay true after the restore.
            File list = BookmarkStore.fileFor(dir);
            long marksTime = MarkerWorkspaceStore.fileFor(dir).lastModified();
            if (marks != null && list.lastModified() <= marksTime) list.setLastModified(marksTime + 2000L);
        }
    }

    private static void writeBookmarks(File dir, JsonObject bookmarks) throws IOException {
        File list = BookmarkStore.fileFor(dir);
        File tmp = new File(dir, list.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(PLAIN.toJson(bookmarks).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(list)) {
            tmp.delete();
            throw new IOException("Could not restore the bookmarks");
        }
    }

    /** Null text stays null; anything that is not one JSON object is an unreadable sidecar. */
    private static JsonObject parse(String text) {
        return text == null ? null : JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject objectIn(JsonObject block, String key) {
        JsonElement value = block.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int off = 0, n;
            while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) off += n;
            return new String(bytes, 0, off, StandardCharsets.UTF_8);
        }
    }
}
