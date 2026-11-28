package cc.joycreator.avatar.ui;

import android.util.Log;

import cc.joycreator.avatar.file.CharacterDoc;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The room's one way to change a character: a named step that is checked, can be taken back and put back, and is
 * exactly one press of Undo (rule: one press = one undo step; a drag, a batch and a slider drag are one step each).
 *
 * <p>A step keeps the character's tree before and after as text (the pictures never change in a step, so they are not
 * copied: an undo of a picture being brought in leaves the picture in the file until it is saved without it).
 * Joybot's tools go through {@link #apply} too, with their own names (spec A12).</p>
 */
public final class RoomEdit {
    private static final String TAG = "AvatarRoom";
    private static final int KEEP = 100;

    /** A change to the tree; runs on the live JSON of {@link CharacterDoc#json}. */
    public interface Change {
        void run(CharacterDoc doc) throws JSONException;
    }

    /** Who needs to know the character or the undo list changed. */
    public interface Listener {
        void changed(CharacterDoc doc);
    }

    private static final class Step {
        final String name, before, after;

        Step(String name, String before, String after) {
            this.name = name;
            this.before = before;
            this.after = after;
        }
    }

    private CharacterDoc doc;
    private final List<Step> undo = new ArrayList<>(), redo = new ArrayList<>();
    private final Listener listener;

    public RoomEdit(CharacterDoc doc, Listener listener) {
        this.doc = doc;
        this.listener = listener;
    }

    public CharacterDoc doc() {
        return doc;
    }

    /** A different character on the stage: the steps of the last one are gone with it. */
    public void reset(CharacterDoc d) {
        doc = d;
        undo.clear();
        redo.clear();
        listener.changed(doc);
    }

    /** Runs the change as one named step. Returns false (and changes nothing) when it fails. */
    public boolean apply(String name, Change change) {
        if (doc == null) return false;
        String before = tree();
        try {
            change.run(doc);
            doc = doc.reread();
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "step refused: " + name, e);
            restore(before);
            return false;
        }
        undo.add(new Step(name, before, tree()));
        if (undo.size() > KEEP) undo.remove(0);
        redo.clear();
        listener.changed(doc);
        return true;
    }

    public String undoName() {
        return undo.isEmpty() ? null : undo.get(undo.size() - 1).name;
    }

    public String redoName() {
        return redo.isEmpty() ? null : redo.get(redo.size() - 1).name;
    }

    public boolean undo() {
        if (undo.isEmpty()) return false;
        Step s = undo.remove(undo.size() - 1);
        restore(s.before);
        redo.add(s);
        listener.changed(doc);
        return true;
    }

    public boolean redo() {
        if (redo.isEmpty()) return false;
        Step s = redo.remove(redo.size() - 1);
        restore(s.after);
        undo.add(s);
        listener.changed(doc);
        return true;
    }

    /** Never copied by a step: the pictures (they never change in one, and they are the bulk of the file). */
    private static final String PICTURES = "pictures";

    /**
     * What a step keeps: everything in the file but the pictures: the tree, the take, and what lives beside the tree (the
     * settings, the stances, the colour sets, the buttons, the face pins). (The face session, 11-28: an undone face pin left its
     * record behind, and an undone setting stayed, while only the tree and the take were kept.)
     */
    private String tree() {
        try {
            JSONObject o = new JSONObject();
            for (java.util.Iterator<String> it = doc.json.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!PICTURES.equals(k)) o.put(k, doc.json.get(k));
            }
            if (!o.has("root")) o.put("root", new JSONObject());
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private void restore(String text) {
        try {
            JSONObject o = new JSONObject(text);
            List<String> gone = new ArrayList<>();
            for (java.util.Iterator<String> it = doc.json.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!PICTURES.equals(k) && !o.has(k)) gone.add(k);
            }
            for (String k : gone) doc.json.remove(k);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                doc.json.put(k, o.get(k));
            }
            doc = doc.reread();
        } catch (JSONException e) {
            Log.w(TAG, "undo could not restore", e);
        }
    }
}
