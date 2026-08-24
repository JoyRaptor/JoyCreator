package com.fadcam.ui.faditor.audio;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.VolumeKeyframe;
import com.fadcam.ui.faditor.undo.EditAction;
import com.fadcam.ui.faditor.undo.EditActions;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes a {@link Ducker} curve onto a clip — the half of {@code C5.E} that touches the project.
 *
 * <p><b>This replaces the target's volume envelope outright,</b> and that is a destructive act:
 * the user may have drawn that curve by hand. Two things follow, and both are the whole reason
 * this class exists rather than the caller doing it inline.</p>
 *
 * <p><b>1. It is undoable</b> (spec rule 7). The action captures the envelope BEFORE and AFTER as
 * independent copies, so undo restores the hand-drawn curve exactly — not a re-derived
 * approximation of it. Ducking is a suggestion; a suggestion the user cannot take back is a
 * demand.</p>
 *
 * <p><b>2. It reports what it displaced</b> via {@link Result#replacedKeyframes}, so the caller can
 * ask before destroying real work. Silently overwriting a curve someone spent time on is the
 * kind of thing that is discovered three edits later, when undo can no longer reach it.</p>
 */
public final class DuckApplier {

    private DuckApplier() {}

    /** What {@link #apply} did, and the means to take it back. */
    public static final class Result {
        /** Register this with the UndoManager. Never null. */
        @NonNull public final EditAction action;
        /** How many keyframes the curve displaced. 0 means nothing of the user's was lost. */
        public final int replacedKeyframes;
        /** How many keyframes the duck curve wrote. 0 means there was nothing to duck under. */
        public final int writtenKeyframes;

        Result(@NonNull EditAction action, int replacedKeyframes, int writtenKeyframes) {
            this.action = action;
            this.replacedKeyframes = replacedKeyframes;
            this.writtenKeyframes = writtenKeyframes;
        }

        /** True when applying this would destroy an envelope the user already had. */
        public boolean displacesExistingWork() { return replacedKeyframes > 0; }
    }

    /**
     * Put {@code curve} onto {@code target}, replacing its volume envelope.
     *
     * <p>The returned action has NOT been executed. Execute it through the UndoManager so the
     * change and its undo entry arrive together — applying first and recording afterwards leaves
     * a window where the edit exists and cannot be reversed.</p>
     *
     * <p>An empty {@code curve} is applied as an empty envelope, not ignored. "Nothing to duck
     * under" is a real answer and the user must be able to see it land — silently doing nothing
     * is indistinguishable from the feature being broken, which is exactly how the volume
     * rubber-band went unnoticed for months (row {@code G18}).</p>
     */
    @NonNull
    public static Result apply(@NonNull AudioClip target,
                               @NonNull List<? extends VolumeKeyframe> curve) {
        final List<VolumeKeyframe> before = copy(target.getVolumeKeyframes());
        final List<VolumeKeyframe> after = copy(curve);

        EditAction action = new EditActions.LambdaAction(
                "Duck under voice",
                () -> target.setVolumeKeyframes(copy(after)),
                () -> target.setVolumeKeyframes(copy(before)));

        return new Result(action, before.size(), after.size());
    }

    /**
     * Independent copies, always.
     *
     * <p>Storing the caller's list would alias the model: a later edit to the clip's envelope
     * would silently rewrite the undo snapshot, and undo would restore the state it was meant to
     * reverse. Copying on the way in AND on every execute/undo keeps the two snapshots
     * genuinely frozen no matter how often the action is replayed.</p>
     */
    @NonNull
    private static List<VolumeKeyframe> copy(@NonNull List<? extends VolumeKeyframe> src) {
        List<VolumeKeyframe> out = new ArrayList<>(src.size());
        for (VolumeKeyframe kf : src) out.add(new VolumeKeyframe(kf.timeMs, kf.volume));
        return out;
    }
}
