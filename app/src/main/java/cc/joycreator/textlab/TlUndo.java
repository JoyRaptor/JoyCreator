package cc.joycreator.textlab;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The room's undo: one press, one step (the owner's rule). A step is the whole look and its words as they were
 * before a change (a slider dragged and let go, a colour chosen, a piece added, a font picked), named for the
 * button's hover label. Small: a look file is a few kilobytes, and 100 steps are kept.
 */
final class TlUndo {

    static final class Step {
        final String look, words, what;

        Step(String look, String words, String what) {
            this.look = look;
            this.words = words;
            this.what = what;
        }
    }

    private static final int MAX = 100;
    private final Deque<Step> back = new ArrayDeque<>(), ahead = new ArrayDeque<>();

    /** True while a run of typing is going on: its later letters join the step its first one made. */
    private boolean typing;

    /** Before a change: remember how it was. A new change forgets the redo steps. */
    void before(@NonNull String look, @NonNull String words, @NonNull String what) {
        typing = false;
        Step top = back.peek();
        if (top != null && top.look.equals(look) && top.words.equals(words)) return;   // nothing changed since
        back.push(new Step(look, words, what));
        while (back.size() > MAX) back.removeLast();
        ahead.clear();
    }

    /** Undo: the step to go back to (the current state is kept for redo), or null. */
    @Nullable
    Step undo(@NonNull String look, @NonNull String words) {
        typing = false;
        Step s = back.poll();
        if (s != null) ahead.push(new Step(look, words, s.what));
        return s;
    }

    @Nullable
    Step redo(@NonNull String look, @NonNull String words) {
        typing = false;
        Step s = ahead.poll();
        if (s != null) back.push(new Step(look, words, s.what));
        return s;
    }

    boolean canUndo() {
        return !back.isEmpty();
    }

    boolean canRedo() {
        return !ahead.isEmpty();
    }

    @Nullable
    String undoWhat() {
        Step s = back.peek();
        return s != null ? s.what : null;
    }

    @Nullable
    String redoWhat() {
        Step s = ahead.peek();
        return s != null ? s.what : null;
    }

    void clear() {
        back.clear();
        ahead.clear();
        typing = false;
    }

    /**
     * The words were typed into (LAB-06): {@code wordsBefore} is how they were before this letter. The first letter
     * of a run makes one step (and forgets the redo steps, as any new change does); the rest of the run joins it, so
     * one press of Undo takes back the whole run. Any other change, an undo or a redo ends the run.
     */
    void typed(@NonNull String look, @NonNull String wordsBefore, @NonNull String what) {
        if (typing) return;
        before(look, wordsBefore, what);
        typing = true;
    }

    /** The person left the words box: the next letter starts a new step. */
    void endTyping() {
        typing = false;
    }

    // ---- kept with a draft (KNOWN-LAB-01) -----------------------------------------------------------

    @NonNull
    org.json.JSONObject save() throws org.json.JSONException {
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("back", steps(back));
        o.put("ahead", steps(ahead));
        return o;
    }

    void load(@Nullable org.json.JSONObject o) {
        clear();
        if (o == null) return;
        read(o.optJSONArray("back"), back);
        read(o.optJSONArray("ahead"), ahead);
    }

    @NonNull
    private static org.json.JSONArray steps(@NonNull Deque<Step> d) throws org.json.JSONException {
        org.json.JSONArray a = new org.json.JSONArray();
        for (Step s : d) {   // newest first, as the deque holds them
            a.put(new org.json.JSONObject().put("look", s.look).put("words", s.words).put("what", s.what));
        }
        return a;
    }

    private static void read(@Nullable org.json.JSONArray a, @NonNull Deque<Step> into) {
        if (a == null) return;
        for (int i = 0; i < a.length() && into.size() < MAX; i++) {
            org.json.JSONObject s = a.optJSONObject(i);
            if (s == null || !s.has("look")) continue;
            into.addLast(new Step(s.optString("look"), s.optString("words"), s.optString("what")));
        }
    }
}
