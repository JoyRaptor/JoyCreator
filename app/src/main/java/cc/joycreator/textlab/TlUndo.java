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

    /** Before a change: remember how it was. A new change forgets the redo steps. */
    void before(@NonNull String look, @NonNull String words, @NonNull String what) {
        Step top = back.peek();
        if (top != null && top.look.equals(look) && top.words.equals(words)) return;   // nothing changed since
        back.push(new Step(look, words, what));
        while (back.size() > MAX) back.removeLast();
        ahead.clear();
    }

    /** Undo: the step to go back to (the current state is kept for redo), or null. */
    @Nullable
    Step undo(@NonNull String look, @NonNull String words) {
        Step s = back.poll();
        if (s != null) ahead.push(new Step(look, words, s.what));
        return s;
    }

    @Nullable
    Step redo(@NonNull String look, @NonNull String words) {
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
    }
}
