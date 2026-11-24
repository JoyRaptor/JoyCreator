package cc.joycreator.avatar.file;

import android.content.Context;
import android.util.Log;

import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.FolderIndex;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.ReadableNames;
import com.fadcam.joyfolder.android.JoyFolderHome;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The person's characters in the Joy Creator folder: one readable {@code <Name>.character.json} each (the same
 * pattern as {@code AvatarBundleFile}: a file in the folder, so an uninstall never loses the work).
 * Every call is on the folder thread ({@link JoyFolderHome#io()}).
 */
public final class CharacterStore {
    private static final String TAG = "AvatarRoom";

    /** One character file in the folder. */
    public static final class Entry {
        public final String path, name;
        public final long modified;

        Entry(String path, String name, long modified) {
            this.path = path;
            this.name = name;
            this.modified = modified;
        }
    }

    private CharacterStore() {}

    /** Every character in the folder, the one touched last first. Empty when the folder cannot be reached. */
    public static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        JoyFolder jf = JoyFolderHome.open(c);
        if (jf == null) return out;
        for (FolderIndex.Item it : jf.index.items()) {
            if (FileKind.of(Placement.nameOf(it.path)) != FileKind.CHARACTER) continue;
            out.add(new Entry(it.path, FileKind.baseOf(Placement.nameOf(it.path)), it.modified));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    /** Opens one by its path; null (and a line in the log only) when it cannot be read. */
    public static CharacterDoc read(Context c, String path) {
        try {
            JoyFolder jf = JoyFolderHome.open(c);
            if (jf == null) return null;
            return CharacterOpen.read(new String(jf.readAll(path), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "character not read: " + path, e);
            return null;
        }
    }

    /**
     * Writes the character into the folder: over {@code path} when it has one, else as a new file named for it.
     * Returns the path, or null when the folder could not be reached (the caller says one plain sentence).
     */
    public static String save(Context c, CharacterDoc doc, String path) {
        try {
            JoyFolder jf = JoyFolderHome.open(c);
            if (jf == null) return null;
            JSONObject out = Js.copy(doc.json);
            if (!Js.truthy(Js.get(out, "uid"))) out.put("uid", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            out.put("edited", System.currentTimeMillis());
            String rel = path;
            if (rel == null || jf.fs.stat(rel) == null) {
                String dir = jf.prepareDir(Placement.What.NEW_WORK, null, null, FileKind.CHARACTER);
                String name = doc.name.isEmpty() ? "New character" : doc.name;
                rel = Placement.join(dir, jf.freeName(dir, ReadableNames.sanitize(name) + FileKind.CHARACTER.ending()));
            }
            // readable by hand: org.json writes every slash with a backslash before it, which no person would
            jf.writeAtomic(rel, out.toString(2).replace("\\/", "/").getBytes(StandardCharsets.UTF_8));
            JoyFs.Entry e = jf.fs.stat(rel);
            if (e != null) jf.index.put(rel, e.size, e.modified, null, null);
            jf.saveIndex();
            return rel;
        } catch (Exception e) {
            Log.w(TAG, "character not saved", e);
            return null;
        }
    }
}
