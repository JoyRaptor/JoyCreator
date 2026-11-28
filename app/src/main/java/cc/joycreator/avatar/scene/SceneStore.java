package cc.joycreator.avatar.scene;

import android.content.Context;
import android.util.Log;

import cc.joycreator.avatar.perform.Take;

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
import java.util.Map;

/**
 * The person's scenes in the Joy Creator folder: one readable {@code <Name>.scene.json} each, the same pattern as
 * {@code CharacterStore}, so an uninstall never loses the work. Call on the folder thread ({@link JoyFolderHome#io()}).
 */
public final class SceneStore {
    private static final String TAG = "AvatarRoom";

    /** One scene file in the folder. */
    public static final class Entry {
        public final String path, name;
        public final long modified;

        Entry(String path, String name, long modified) {
            this.path = path;
            this.name = name;
            this.modified = modified;
        }
    }

    private SceneStore() {
    }

    /** Every scene in the folder, the one touched last first. Empty when the folder cannot be reached. */
    public static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        JoyFolder jf = JoyFolderHome.open(c);
        if (jf == null) return out;
        for (FolderIndex.Item it : jf.index.items()) {
            if (FileKind.of(Placement.nameOf(it.path)) != FileKind.SCENE) continue;
            out.add(new Entry(it.path, FileKind.baseOf(Placement.nameOf(it.path)), it.modified));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    /** Reads one scene by its path; null (and a line in the log) when it cannot be read. {@code problems} of the result says what is wrong with a damaged file. */
    public static SceneFile.Parsed read(Context c, String path) {
        try {
            JoyFolder jf = JoyFolderHome.open(c);
            if (jf == null) return null;
            return SceneFile.read(new String(jf.readAll(path), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "scene not read: " + path, e);
            return null;
        }
    }

    /** Writes the scene into the folder: over {@code path}, else a new file named for it. Returns the path, or null. */
    public static String save(Context c, Scene s, Map<String, Take> takes, Map<String, JSONObject> characters, Map<String, String> pictures, String path) {
        try {
            JoyFolder jf = JoyFolderHome.open(c);
            if (jf == null) return null;
            JSONObject out = SceneFile.write(s, takes, characters, pictures);
            String rel = path;
            if (rel == null || jf.fs.stat(rel) == null) {
                String dir = jf.prepareDir(Placement.What.NEW_WORK, null, null, FileKind.SCENE);
                rel = Placement.join(dir, jf.freeName(dir, ReadableNames.sanitize(s.name().isEmpty() ? "New scene" : s.name()) + FileKind.SCENE.ending()));
            }
            jf.writeAtomic(rel, out.toString(2).replace("\\/", "/").getBytes(StandardCharsets.UTF_8));
            JoyFs.Entry e = jf.fs.stat(rel);
            if (e != null) jf.index.put(rel, e.size, e.modified, null, null);
            jf.saveIndex();
            return rel;
        } catch (Exception e) {
            Log.w(TAG, "scene not saved", e);
            return null;
        }
    }

    /** Puts a scene in the folder's Trash (the characters in it stay on the shelf). */
    public static boolean delete(Context c, String path) {
        try {
            JoyFolder jf = JoyFolderHome.open(c);
            if (jf == null || path == null) return false;
            jf.trash(path, System.currentTimeMillis());                // into the folder's own Trash: it can be put back
            jf.index.remove(path);
            jf.saveIndex();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "scene not deleted", e);
            return false;
        }
    }
}
