package com.fadcam.ui.faditor.tools;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.VolumeKeyframe;

import java.util.ArrayList;
import java.util.List;

/**
 * The audio drawer's Clear button: wipe a clip's volume envelope as ONE undoable step (owner rule: one press, one step).
 *
 * <p>Pure Java so it is tested off the device. STU-4 (11-24, Note 9): the drawer's Clear passed the undo and the redo to
 * {@code Host.recordUndo} in the wrong order (that method takes redo FIRST), so Undo cleared an already clear line and the
 * earlier ducking was what got undone instead; and the row's "10 pts" text was never refreshed. This holds the order and the
 * refresh in one place.</p>
 */
public final class EnvelopeClearStep {
    private EnvelopeClearStep() {}

    /** The clip's envelope, as the drawer sees it. */
    public interface Envelope {
        boolean has();

        /** A copy of the keys now. */
        @NonNull List<VolumeKeyframe> snapshot();

        void restore(@NonNull List<VolumeKeyframe> keys);

        void clear();
    }

    /** {@code Host.recordUndo}: redo comes FIRST, then undo. */
    public interface Recorder {
        void record(@NonNull String label, @NonNull Runnable redo, @NonNull Runnable undo);
    }

    /**
     * Clear the envelope and record one step. {@code afterChange} runs after the clear, after an undo and after a redo (the
     * host repaints and saves, and the drawer's state row re-reads the key count). False when there was nothing to clear.
     */
    public static boolean press(@NonNull Envelope env, @NonNull String label, @NonNull Recorder recorder,
                                @NonNull Runnable afterChange) {
        if (!env.has()) return false;
        final List<VolumeKeyframe> before = new ArrayList<>(env.snapshot());
        env.clear();
        recorder.record(label,
                () -> { env.clear(); afterChange.run(); },                 // redo: clear again
                () -> { env.restore(new ArrayList<>(before)); afterChange.run(); });   // undo: the keys come back
        afterChange.run();
        return true;
    }
}
