package cc.joycreator.textlab;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The room's unsaved work, kept in the app's own files while the room is out of sight (KNOWN-LAB-01): Android may
 * end the app behind another one, and the look, its words and its undo steps lived only in memory. Written when the
 * room goes out of sight with changes; gone after a save, a "Don't save" or a clean leave. Not for a Studio title's
 * look (its way back is Studio, which a new start does not have). Pure Java: the JVM harness tests it.
 */
final class TlDraft {

    static final int VERSION = 1;

    /** The look and words on screen; the look and words as last opened or saved (a change back is not a change). */
    @NonNull final String look, words, savedLook, savedWords;
    /** The shelf key of the look being edited ("folder:Supplies/Looks/Dust.text.json", a built-in's id), or null. */
    @Nullable final String key;
    /** The look's name, for the question that offers it back. */
    @NonNull final String name;
    /** {@link TlUndo#save}. */
    @Nullable final JSONObject undo;

    TlDraft(@NonNull String look, @NonNull String words, @NonNull String savedLook, @NonNull String savedWords,
            @Nullable String key, @NonNull String name, @Nullable JSONObject undo) {
        this.look = look;
        this.words = words;
        this.savedLook = savedLook;
        this.savedWords = savedWords;
        this.key = key;
        this.name = name;
        this.undo = undo;
    }

    @NonNull
    String toJson() throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("version", VERSION);
        o.put("look", look);
        o.put("words", words);
        o.put("savedLook", savedLook);
        o.put("savedWords", savedWords);
        if (key != null) o.put("key", key);
        o.put("name", name);
        if (undo != null) o.put("undo", undo);
        return o.toString();
    }

    /** A draft read back, or null when there is none, it is from a later version, or it does not read. */
    @Nullable
    static TlDraft parse(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            if (o.optInt("version", 0) != VERSION || !o.has("look")) return null;
            new JSONObject(o.getString("look"));   // the look itself must read
            return new TlDraft(o.getString("look"), o.optString("words"), o.optString("savedLook"),
                    o.optString("savedWords"), o.has("key") ? o.optString("key") : null, o.optString("name"),
                    o.optJSONObject("undo"));
        } catch (Exception e) {
            return null;
        }
    }

    // ---- the file ---------------------------------------------------------------------------------

    @NonNull
    static File file(@NonNull File filesDir) {
        return new File(new File(filesDir, "textlab"), "draft.json");
    }

    /** Write it whole, or leave the last one as it was. */
    static void write(@NonNull File f, @NonNull String json) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder for the draft");
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("draft not moved into place");
        }
    }

    @Nullable
    static TlDraft read(@NonNull File f) {
        try {
            if (!f.isFile()) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            }
            return parse(new String(b.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static void delete(@NonNull File f) {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
