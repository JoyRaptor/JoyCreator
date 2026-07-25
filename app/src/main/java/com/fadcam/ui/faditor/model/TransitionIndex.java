package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The transition-index bookkeeping that structural clip edits require, extracted from
 * {@link Timeline} so it can be tested directly.
 *
 * <p>Every method here mutates {@code Transition.clipIndex} IN PLACE, which is what makes this
 * logic worth isolating: re-adding or re-removing a clip does NOT reverse it, so an undo step
 * that restores clips without restoring the transition list leaves transitions attached to the
 * wrong seams — the transition still plays, just at a cut the user never chose. See
 * {@code tasks/AUDIT_TRANSITION_INDEX_UNDO.md}.</p>
 *
 * <p>{@link Timeline} delegates to these; the split exists purely so the rules can be exercised
 * without dragging in {@code Clip} (and therefore {@code android.net.Uri}) — the JVM harness
 * test {@code tools/jvm-harness/TransitionIndexTest.java} compiles this class against nothing
 * but the annotation stubs.</p>
 */
public final class TransitionIndex {

    private TransitionIndex() { }

    /**
     * Clip at {@code clipIndex} was deleted: drop the transitions on the seams either side of
     * it, and renumber everything after it down one.
     */
    public static void removeForDeletedClip(@NonNull List<Transition> transitions, int clipIndex) {
        for (int i = transitions.size() - 1; i >= 0; i--) {
            Transition t = transitions.get(i);
            if (t.clipIndex == clipIndex || t.clipIndex == clipIndex - 1) {
                transitions.remove(i);
            } else if (t.clipIndex > clipIndex) {
                t.clipIndex--;
            }
        }
    }

    /** A clip was inserted at {@code insertIndex}: renumber everything from there up one. */
    public static void shiftAfterInsert(@NonNull List<Transition> transitions, int insertIndex) {
        for (Transition t : transitions) {
            if (t.clipIndex >= insertIndex) t.clipIndex++;
        }
    }

    /** A clip at {@code splitIndex} became two: renumber everything from there up one. */
    public static void shiftAfterSplit(@NonNull List<Transition> transitions, int splitIndex) {
        for (Transition t : transitions) {
            if (t.clipIndex >= splitIndex) t.clipIndex++;
        }
    }

    /**
     * DEEP snapshot for an undo step. Deep because {@code clipIndex} is mutated in place — a
     * shallow list copy would alias the exact field the snapshot exists to preserve.
     */
    @NonNull
    public static List<Transition> snapshot(@NonNull List<Transition> transitions) {
        List<Transition> copy = new ArrayList<>(transitions.size());
        for (Transition t : transitions) copy.add(t.copy());
        return copy;
    }

    /** Replace {@code target}'s contents with a deep copy of {@code snapshot}. */
    public static void restore(@NonNull List<Transition> target,
                               @NonNull List<Transition> snapshot) {
        target.clear();
        for (Transition t : snapshot) target.add(t.copy());
    }
}
