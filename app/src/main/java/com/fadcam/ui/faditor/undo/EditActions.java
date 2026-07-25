package com.fadcam.ui.faditor.undo;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.effects.EffectStack;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;

/**
 * Collection of concrete {@link EditAction} implementations for all
 * Faditor editor operations.
 *
 * <p>Each action captures the old and new state so it can be reversed.
 * Actions operate directly on the project model.</p>
 */
public final class EditActions {

    private EditActions() {} // Utility class

    // ═══════════════════════════════════════════════════════════════
    // Video Clip Actions
    // ═══════════════════════════════════════════════════════════════

    /** Trim change (in-point and/or out-point). */
    public static final class TrimAction implements EditAction {
        private final Clip clip;
        private final long oldIn, oldOut, newIn, newOut;

        public TrimAction(@NonNull Clip clip,
                          long oldIn, long oldOut,
                          long newIn, long newOut) {
            this.clip = clip;
            this.oldIn = oldIn;
            this.oldOut = oldOut;
            this.newIn = newIn;
            this.newOut = newOut;
        }

        @Override public void execute() {
            clip.setInPointMs(newIn);
            clip.setOutPointMs(newOut);
        }
        @Override public void undo() {
            clip.setInPointMs(oldIn);
            clip.setOutPointMs(oldOut);
        }
        @NonNull @Override public String getDescription() {
            return "Trim [" + oldIn + "–" + oldOut + "] → [" + newIn + "–" + newOut + "]";
        }
    }

    /** Volume level change. */
    public static final class VolumeAction implements EditAction {
        private final Clip clip;
        private final float oldVolume, newVolume;

        public VolumeAction(@NonNull Clip clip, float oldVolume, float newVolume) {
            this.clip = clip;
            this.oldVolume = oldVolume;
            this.newVolume = newVolume;
        }

        @Override public void execute() { clip.setVolumeLevel(newVolume); }
        @Override public void undo() { clip.setVolumeLevel(oldVolume); }
        @NonNull @Override public String getDescription() {
            return "Volume " + oldVolume + " → " + newVolume;
        }
    }

    /** Audio mute/unmute toggle. */
    public static final class MuteAction implements EditAction {
        private final Clip clip;
        private final boolean oldMuted, newMuted;
        private final float oldVolume, newVolume;

        public MuteAction(@NonNull Clip clip,
                          boolean oldMuted, float oldVolume,
                          boolean newMuted, float newVolume) {
            this.clip = clip;
            this.oldMuted = oldMuted;
            this.oldVolume = oldVolume;
            this.newMuted = newMuted;
            this.newVolume = newVolume;
        }

        @Override public void execute() {
            clip.setAudioMuted(newMuted);
            clip.setVolumeLevel(newVolume);
        }
        @Override public void undo() {
            clip.setAudioMuted(oldMuted);
            clip.setVolumeLevel(oldVolume);
        }
        @NonNull @Override public String getDescription() {
            return "Mute " + oldMuted + " → " + newMuted;
        }
    }

    /** Speed multiplier change. */
    public static final class SpeedAction implements EditAction {
        private final Clip clip;
        private final float oldSpeed, newSpeed;

        public SpeedAction(@NonNull Clip clip, float oldSpeed, float newSpeed) {
            this.clip = clip;
            this.oldSpeed = oldSpeed;
            this.newSpeed = newSpeed;
        }

        @Override public void execute() { clip.setSpeedMultiplier(newSpeed); }
        @Override public void undo() { clip.setSpeedMultiplier(oldSpeed); }
        @NonNull @Override public String getDescription() {
            return "Speed " + oldSpeed + "x → " + newSpeed + "x";
        }
    }

    /** Rotation change. */
    public static final class RotateAction implements EditAction {
        private final Clip clip;
        private final int oldDegrees, newDegrees;

        public RotateAction(@NonNull Clip clip, int oldDegrees, int newDegrees) {
            this.clip = clip;
            this.oldDegrees = oldDegrees;
            this.newDegrees = newDegrees;
        }

        @Override public void execute() { clip.setRotationDegrees(newDegrees); }
        @Override public void undo() { clip.setRotationDegrees(oldDegrees); }
        @NonNull @Override public String getDescription() {
            return "Rotate " + oldDegrees + "° → " + newDegrees + "°";
        }
    }

    /**
     * Generic before/after action driven by two {@link Runnable}s. Lets callers
     * wire undo for property changes that span multiple model types (e.g. a caption
     * change that may target a Clip OR an AudioClip) without a bespoke class each.
     * The runnables must ONLY restore model data — the editor refreshes the UI after
     * undo/redo via refreshEditorAfterUndoRedo().
     */
    public static final class LambdaAction implements EditAction {
        private final String description;
        private final Runnable redo, undo;

        public LambdaAction(@NonNull String description,
                            @NonNull Runnable redo, @NonNull Runnable undo) {
            this.description = description;
            this.redo = redo;
            this.undo = undo;
        }

        @Override public void execute() { redo.run(); }
        @Override public void undo() { undo.run(); }
        @NonNull @Override public String getDescription() { return description; }
    }

    /** Color-grade / filter change (whole EffectStack before → after). */
    public static final class EffectStackAction implements EditAction {
        private final Clip clip;
        private final EffectStack before, after;

        public EffectStackAction(@NonNull Clip clip,
                                 @NonNull EffectStack before, @NonNull EffectStack after) {
            this.clip = clip;
            this.before = new EffectStack(before);
            this.after = new EffectStack(after);
        }

        @Override public void execute() { clip.getEffectStack().copyFrom(after); }
        @Override public void undo() { clip.getEffectStack().copyFrom(before); }
        @NonNull @Override public String getDescription() { return "Filter / color"; }
    }

    /** Opacity-keyframe change (whole keyframe list before → after). */
    public static final class OpacityKeyframesAction implements EditAction {
        private final Clip clip;
        private final java.util.List<Clip.OpacityKeyframe> before, after;

        public OpacityKeyframesAction(@NonNull Clip clip,
                                      @NonNull java.util.List<Clip.OpacityKeyframe> before,
                                      @NonNull java.util.List<Clip.OpacityKeyframe> after) {
            this.clip = clip;
            this.before = copy(before);
            this.after = copy(after);
        }

        private static java.util.List<Clip.OpacityKeyframe> copy(
                java.util.List<Clip.OpacityKeyframe> src) {
            java.util.List<Clip.OpacityKeyframe> out = new java.util.ArrayList<>();
            for (Clip.OpacityKeyframe kf : src) {
                out.add(new Clip.OpacityKeyframe(kf.timeMs, kf.opacity));
            }
            return out;
        }

        @Override public void execute() { clip.setOpacityKeyframes(after); }
        @Override public void undo() { clip.setOpacityKeyframes(before); }
        @NonNull @Override public String getDescription() { return "Opacity keyframes"; }
    }

    /** Caption-style-keyframe change (whole keyframe list before → after). */
    public static final class CaptionStyleKeyframesAction implements EditAction {
        private final Clip clip;
        private final java.util.List<Clip.CaptionStyleKeyframe> before, after;

        public CaptionStyleKeyframesAction(@NonNull Clip clip,
                                           @NonNull java.util.List<Clip.CaptionStyleKeyframe> before,
                                           @NonNull java.util.List<Clip.CaptionStyleKeyframe> after) {
            this.clip = clip;
            this.before = copy(before);
            this.after = copy(after);
        }

        private static java.util.List<Clip.CaptionStyleKeyframe> copy(
                java.util.List<Clip.CaptionStyleKeyframe> src) {
            java.util.List<Clip.CaptionStyleKeyframe> out = new java.util.ArrayList<>();
            for (Clip.CaptionStyleKeyframe kf : src) {
                out.add(new Clip.CaptionStyleKeyframe(kf.timeMs, kf.styleId));
            }
            return out;
        }

        @Override public void execute() { clip.setCaptionStyleKeyframes(after); }
        @Override public void undo() { clip.setCaptionStyleKeyframes(before); }
        @NonNull @Override public String getDescription() { return "Caption style keyframes"; }
    }

    /** Horizontal flip change. */
    public static final class FlipHorizontalAction implements EditAction {
        private final Clip clip;
        private final boolean oldFlip, newFlip;

        public FlipHorizontalAction(@NonNull Clip clip,
                                    boolean oldFlip, boolean newFlip) {
            this.clip = clip;
            this.oldFlip = oldFlip;
            this.newFlip = newFlip;
        }

        @Override public void execute() { clip.setFlipHorizontal(newFlip); }
        @Override public void undo() { clip.setFlipHorizontal(oldFlip); }
        @NonNull @Override public String getDescription() {
            return "Flip horizontal " + oldFlip + " \u2192 " + newFlip;
        }
    }

    /** Vertical flip change. */
    public static final class FlipVerticalAction implements EditAction {
        private final Clip clip;
        private final boolean oldFlip, newFlip;

        public FlipVerticalAction(@NonNull Clip clip,
                                  boolean oldFlip, boolean newFlip) {
            this.clip = clip;
            this.oldFlip = oldFlip;
            this.newFlip = newFlip;
        }

        @Override public void execute() { clip.setFlipVertical(newFlip); }
        @Override public void undo() { clip.setFlipVertical(oldFlip); }
        @NonNull @Override public String getDescription() {
            return "Flip vertical " + oldFlip + " \u2192 " + newFlip;
        }
    }

    /** Crop preset / bounds change. */
    public static final class CropAction implements EditAction {
        private final Clip clip;
        private final String oldPreset, newPreset;
        private final float oldL, oldT, oldR, oldB;
        private final float newL, newT, newR, newB;

        public CropAction(@NonNull Clip clip,
                          @NonNull String oldPreset, float oldL, float oldT, float oldR, float oldB,
                          @NonNull String newPreset, float newL, float newT, float newR, float newB) {
            this.clip = clip;
            this.oldPreset = oldPreset;
            this.oldL = oldL; this.oldT = oldT; this.oldR = oldR; this.oldB = oldB;
            this.newPreset = newPreset;
            this.newL = newL; this.newT = newT; this.newR = newR; this.newB = newB;
        }

        @Override public void execute() {
            clip.setCropPreset(newPreset);
            clip.setCustomCropBounds(newL, newT, newR, newB);
            // Override preset back since setCustomCropBounds always sets "custom"
            clip.setCropPreset(newPreset);
        }
        @Override public void undo() {
            clip.setCropPreset(oldPreset);
            clip.setCustomCropBounds(oldL, oldT, oldR, oldB);
            clip.setCropPreset(oldPreset);
        }
        @NonNull @Override public String getDescription() {
            return "Crop " + oldPreset + " → " + newPreset;
        }
    }

    /** Canvas preset change (project-level). */
    public static final class CanvasPresetAction implements EditAction {
        private final FaditorProject project;
        private final String oldPreset, newPreset;

        public CanvasPresetAction(@NonNull FaditorProject project,
                                  @NonNull String oldPreset,
                                  @NonNull String newPreset) {
            this.project = project;
            this.oldPreset = oldPreset;
            this.newPreset = newPreset;
        }

        @Override public void execute() { project.setCanvasPreset(newPreset); }
        @Override public void undo() { project.setCanvasPreset(oldPreset); }
        @NonNull @Override public String getDescription() {
            return "Canvas " + oldPreset + " → " + newPreset;
        }
    }

    /**
     * Text/image overlay transform change from a drag/pinch gesture, a timeline
     * time-range edge drag, or a timeline keyframe move — restores the overlay's
     * full transform/time/keyframe snapshot (before ↔ after).
     */
    public static final class OverlayTransformAction implements EditAction {
        private final TextOverlayItem item;
        private final TextOverlayItem.TransformSnapshot before, after;
        private final String description;

        public OverlayTransformAction(@NonNull TextOverlayItem item,
                                      @NonNull TextOverlayItem.TransformSnapshot before,
                                      @NonNull TextOverlayItem.TransformSnapshot after,
                                      @NonNull String description) {
            this.item = item;
            this.before = before;
            this.after = after;
            this.description = description;
        }

        @Override public void execute() { item.restoreTransform(after); }
        @Override public void undo() { item.restoreTransform(before); }
        @NonNull @Override public String getDescription() { return description; }
    }

    // ═══════════════════════════════════════════════════════════════
    // Audio Clip Actions
    // ═══════════════════════════════════════════════════════════════

    /** Add an audio clip to the timeline. */
    public static final class AddAudioClipAction implements EditAction {
        private final Timeline timeline;
        private final AudioClip audioClip;
        /** Video clip that was auto-muted after extraction (null if N/A). */
        private final Clip autoMutedClip;
        private final boolean prevMuted;
        private final float prevVolume;

        public AddAudioClipAction(@NonNull Timeline timeline,
                                  @NonNull AudioClip audioClip,
                                  @NonNull Clip autoMutedClip,
                                  boolean prevMuted, float prevVolume) {
            this.timeline = timeline;
            this.audioClip = audioClip;
            this.autoMutedClip = autoMutedClip;
            this.prevMuted = prevMuted;
            this.prevVolume = prevVolume;
        }

        @Override public void execute() {
            timeline.addAudioClip(audioClip, false);
            if (autoMutedClip != null) {
                autoMutedClip.setAudioMuted(true);
                autoMutedClip.setVolumeLevel(0f);
            }
        }
        @Override public void undo() {
            timeline.removeAudioClip(audioClip);
            if (autoMutedClip != null) {
                autoMutedClip.setAudioMuted(prevMuted);
                autoMutedClip.setVolumeLevel(prevVolume);
            }
        }
        @NonNull @Override public String getDescription() {
            return "Add audio: " + audioClip.getLabel();
        }
    }

    /** Remove an audio clip from the timeline. */
    public static final class RemoveAudioClipAction implements EditAction {
        private final Timeline timeline;
        private final AudioClip audioClip;
        private final int index;

        public RemoveAudioClipAction(@NonNull Timeline timeline,
                                     @NonNull AudioClip audioClip,
                                     int index) {
            this.timeline = timeline;
            this.audioClip = audioClip;
            this.index = index;
        }

        @Override public void execute() { timeline.removeAudioClip(audioClip); }
        @Override public void undo() {
            // Re-insert at original position
            if (index >= 0 && index <= timeline.getAudioClipCount()) {
                timeline.getAudioClips(); // just verify
                // Timeline doesn't have addAudioClip(index), so add at end
                timeline.addAudioClip(audioClip, false);
            } else {
                timeline.addAudioClip(audioClip, false);
            }
        }
        @NonNull @Override public String getDescription() {
            return "Remove audio: " + audioClip.getLabel();
        }
    }

    /** Audio clip trim change. */
    public static final class AudioTrimAction implements EditAction {
        private final AudioClip clip;
        private final long oldIn, oldOut, newIn, newOut;

        public AudioTrimAction(@NonNull AudioClip clip,
                               long oldIn, long oldOut,
                               long newIn, long newOut) {
            this.clip = clip;
            this.oldIn = oldIn;
            this.oldOut = oldOut;
            this.newIn = newIn;
            this.newOut = newOut;
        }

        @Override public void execute() {
            clip.setInPointMs(newIn);
            clip.setOutPointMs(newOut);
        }
        @Override public void undo() {
            clip.setInPointMs(oldIn);
            clip.setOutPointMs(oldOut);
        }
        @NonNull @Override public String getDescription() {
            return "Audio trim [" + oldIn + "–" + oldOut + "] → [" + newIn + "–" + newOut + "]";
        }
    }

    /** Audio clip volume change. */
    public static final class AudioVolumeAction implements EditAction {
        private final AudioClip clip;
        private final float oldVolume, newVolume;

        public AudioVolumeAction(@NonNull AudioClip clip, float oldVolume, float newVolume) {
            this.clip = clip;
            this.oldVolume = oldVolume;
            this.newVolume = newVolume;
        }

        @Override public void execute() { clip.setVolumeLevel(newVolume); }
        @Override public void undo() { clip.setVolumeLevel(oldVolume); }
        @NonNull @Override public String getDescription() {
            return "Audio volume " + oldVolume + " → " + newVolume;
        }
    }

    /** Audio clip mute toggle. */
    public static final class AudioMuteAction implements EditAction {
        private final AudioClip clip;
        private final boolean oldMuted, newMuted;

        public AudioMuteAction(@NonNull AudioClip clip, boolean oldMuted, boolean newMuted) {
            this.clip = clip;
            this.oldMuted = oldMuted;
            this.newMuted = newMuted;
        }

        @Override public void execute() { clip.setMuted(newMuted); }
        @Override public void undo() { clip.setMuted(oldMuted); }
        @NonNull @Override public String getDescription() {
            return "Audio mute " + oldMuted + " → " + newMuted;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Timeline Structure Actions
    // ═══════════════════════════════════════════════════════════════

    /** Split a video clip into two at a given point. */
    public static final class SplitClipAction implements EditAction {
        private final Timeline timeline;
        private final int originalIndex;
        private final Clip originalClip;
        private final Clip clipA;
        private final Clip clipB;

        /**
         * Transitions as they were BEFORE the split. A split runs
         * {@code shiftTransitionsAfterSplit}, which increments every {@code clipIndex} at or
         * after the split IN PLACE; re-joining the two halves does not decrement them back, so
         * a split+undo used to leave every later transition one seam too far right — the
         * transition still plays, just at a cut the user never chose. Split is the most-used
         * structural edit in the app, so this was the most-hit instance of that bug.
         * Captured at construction, which the call site does after splitting but the list is
         * only read on undo, so the pre-split state must be captured by the CALLER order —
         * see the note in {@code splitAtPlayhead}: the snapshot is taken before the shift.
         */
        private final java.util.List<com.fadcam.ui.faditor.model.Transition> transitionsBefore;

        public SplitClipAction(@NonNull Timeline timeline, int originalIndex,
                               @NonNull Clip originalClip,
                               @NonNull Clip clipA, @NonNull Clip clipB,
                               @NonNull java.util.List<com.fadcam.ui.faditor.model.Transition>
                                       transitionsBefore) {
            this.timeline = timeline;
            this.originalIndex = originalIndex;
            this.originalClip = originalClip;
            this.clipA = clipA;
            this.clipB = clipB;
            this.transitionsBefore = transitionsBefore;
        }

        @Override public void execute() {
            // Remove original, insert two split clips
            timeline.removeClip(originalIndex);
            timeline.addClip(originalIndex, clipB);
            timeline.addClip(originalIndex, clipA);
            // REDO must reproduce the index shift too — undo() restored the pre-split list.
            timeline.shiftTransitionsAfterSplit(originalIndex);
        }
        @Override public void undo() {
            // Remove two split clips, re-insert original
            timeline.removeClip(originalIndex + 1);
            timeline.removeClip(originalIndex);
            timeline.addClip(originalIndex, originalClip);
            timeline.restoreTransitions(transitionsBefore);
        }
        @NonNull @Override public String getDescription() { return "Split clip"; }
    }

    /** Split an audio clip into two at a given point. */
    public static final class SplitAudioClipAction implements EditAction {
        private final Timeline timeline;
        private final AudioClip originalClip;
        private final AudioClip leftClip;
        private final AudioClip rightClip;
        private final int originalIndex;

        public SplitAudioClipAction(@NonNull Timeline timeline, int originalIndex,
                                    @NonNull AudioClip originalClip,
                                    @NonNull AudioClip leftClip,
                                    @NonNull AudioClip rightClip) {
            this.timeline = timeline;
            this.originalIndex = originalIndex;
            this.originalClip = originalClip;
            this.leftClip = leftClip;
            this.rightClip = rightClip;
        }

        @Override public void execute() {
            timeline.removeAudioClip(originalClip);
            timeline.addAudioClip(leftClip, false);
            timeline.addAudioClip(rightClip, false);
        }
        @Override public void undo() {
            timeline.removeAudioClip(rightClip);
            timeline.removeAudioClip(leftClip);
            timeline.addAudioClip(originalClip, false);
        }
        @NonNull @Override public String getDescription() { return "Split audio clip"; }
    }

    /** Delete a video clip from the timeline. */
    public static final class DeleteClipAction implements EditAction {
        private final Timeline timeline;
        private final Clip clip;
        private final int index;
        /**
         * Transitions as they were BEFORE the delete. Deleting a clip also runs
         * {@code removeTransitionsForDeletedClip}, which drops the transitions adjacent to
         * the removed clip AND renumbers every later {@code clipIndex} in place. Re-adding
         * the clip undid neither, so a delete+undo used to leave every later transition on
         * the WRONG seam (one between clips 5 and 6 came back between 4 and 5) with the
         * adjacent ones gone for good — silent corruption of the edit, not just data loss.
         * Captured at construction, which every call site does before mutating.
         */
        private final java.util.List<com.fadcam.ui.faditor.model.Transition> transitionsBefore;

        public DeleteClipAction(@NonNull Timeline timeline,
                                @NonNull Clip clip, int index) {
            this.timeline = timeline;
            this.clip = clip;
            this.index = index;
            this.transitionsBefore = timeline.snapshotTransitions();
        }

        @Override public void execute() {
            timeline.removeClip(clip);
            // REDO must reproduce the whole delete, transitions included — undo() restored
            // them, so without this a redo would leave them pointing at a clip that is gone.
            timeline.removeTransitionsForDeletedClip(index);
        }
        @Override public void undo() {
            if (index >= 0 && index <= timeline.getClipCount()) {
                timeline.addClip(index, clip);
            } else {
                timeline.addClip(clip);
            }
            timeline.restoreTransitions(transitionsBefore);
        }
        @NonNull @Override public String getDescription() { return "Delete clip"; }
    }

    /** Add a non-destructive removed span to a video clip. */
    public static final class AddRemovedSpanAction implements EditAction {
        private final Clip clip;
        private final long startMs;
        private final long endMs;

        public AddRemovedSpanAction(@NonNull Clip clip, long startMs, long endMs) {
            this.clip = clip;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        @Override public void execute() {
            clip.getRemovedSpans().add(new long[]{startMs, endMs});
        }
        @Override public void undo() {
            java.util.Iterator<long[]> it = clip.getRemovedSpans().iterator();
            while (it.hasNext()) {
                long[] span = it.next();
                if (span.length == 2 && span[0] == startMs && span[1] == endMs) {
                    it.remove();
                    return;
                }
            }
        }
        @NonNull @Override public String getDescription() { return "Heal gap"; }
    }

    /**
     * Replace one clip with a sequence of clips at the same position
     * (used by silence removal, which turns one clip into several jump-cuts).
     */
    public static final class ReplaceClipsAction implements EditAction {
        private final Timeline timeline;
        private final int index;
        private final Clip original;
        private final java.util.List<Clip> replacements;

        public ReplaceClipsAction(@NonNull Timeline timeline, int index,
                                  @NonNull Clip original,
                                  @NonNull java.util.List<Clip> replacements) {
            this.timeline = timeline;
            this.index = index;
            this.original = original;
            this.replacements = replacements;
        }

        @Override public void execute() {
            timeline.removeClip(index);
            for (int i = replacements.size() - 1; i >= 0; i--) {
                timeline.addClip(index, replacements.get(i));
            }
        }
        @Override public void undo() {
            for (int i = 0; i < replacements.size(); i++) {
                timeline.removeClip(index);
            }
            timeline.addClip(index, original);
        }
        @NonNull @Override public String getDescription() { return "Remove silence"; }
    }

    /** Delete an audio clip from the timeline. */
    public static final class DeleteAudioClipAction implements EditAction {
        private final Timeline timeline;
        private final AudioClip audioClip;
        private final int index;

        public DeleteAudioClipAction(@NonNull Timeline timeline,
                                     @NonNull AudioClip audioClip, int index) {
            this.timeline = timeline;
            this.audioClip = audioClip;
            this.index = index;
        }

        @Override public void execute() { timeline.removeAudioClip(audioClip); }
        @Override public void undo() {
            if (index >= 0 && index <= timeline.getAudioClipCount()) {
                timeline.addAudioClip(audioClip, false);
            } else {
                timeline.addAudioClip(audioClip, false);
            }
        }
        @NonNull @Override public String getDescription() { return "Delete audio clip"; }
    }

    /** Duplicate a clip (insert copy after original). */
    public static final class DuplicateClipAction implements EditAction {
        private final Timeline timeline;
        private final Clip duplicatedClip;
        private final int insertIndex;

        public DuplicateClipAction(@NonNull Timeline timeline,
                                   @NonNull Clip duplicatedClip, int insertIndex) {
            this.timeline = timeline;
            this.duplicatedClip = duplicatedClip;
            this.insertIndex = insertIndex;
        }

        @Override public void execute() {
            timeline.addClip(insertIndex, duplicatedClip);
            timeline.shiftTransitionsAfterInsert(insertIndex); // redo the index shift too
        }
        @Override public void undo() {
            timeline.removeClip(duplicatedClip);
            // Duplicating inserts a clip, which renumbered every later transition in place;
            // removing it does not put them back. Exact inverse — see AddClipAction.
            timeline.unshiftTransitionsAfterInsert(insertIndex);
        }
        @NonNull @Override public String getDescription() { return "Duplicate clip"; }
    }

    /** Add a clip (image or video) to the timeline. */
    public static final class AddClipAction implements EditAction {
        private final Timeline timeline;
        private final Clip clip;
        private final int insertIndex;

        public AddClipAction(@NonNull Timeline timeline,
                             @NonNull Clip clip, int insertIndex) {
            this.timeline = timeline;
            this.clip = clip;
            this.insertIndex = insertIndex;
        }

        @Override public void execute() {
            timeline.addClip(insertIndex, clip);
            // REDO must reproduce the index shift the insert performs, or the transitions
            // undo() pushed back down stay one seam too far left.
            timeline.shiftTransitionsAfterInsert(insertIndex);
        }
        @Override public void undo() {
            timeline.removeClip(clip);
            // Inserting renumbered every later transition's clipIndex IN PLACE, and removing
            // the clip does not put them back — so an undone insert used to leave every later
            // transition one seam too far right (it still plays, at a cut the user never
            // chose). An insert drops nothing, so the arithmetic inverse is exact and no
            // snapshot is needed.
            timeline.unshiftTransitionsAfterInsert(insertIndex);
        }
        @NonNull @Override public String getDescription() { return "Add clip"; }
    }

    /** Reorder a clip (move from one position to another). */
    public static final class ReorderClipAction implements EditAction {
        private final Timeline timeline;
        private final int fromIndex, toIndex;

        public ReorderClipAction(@NonNull Timeline timeline,
                                 int fromIndex, int toIndex) {
            this.timeline = timeline;
            this.fromIndex = fromIndex;
            this.toIndex = toIndex;
        }

        @Override public void execute() { timeline.moveClip(fromIndex, toIndex); }
        @Override public void undo() { timeline.moveClip(toIndex, fromIndex); }
        @NonNull @Override public String getDescription() {
            return "Reorder clip " + fromIndex + " → " + toIndex;
        }
    }

    /** Replace a clip's source URI while keeping all edits (relink/replace media). */
    public static final class ReplaceClipSourceAction implements EditAction {
        @NonNull private final Timeline timeline;
        private final int index;
        @NonNull private final Clip oldClip;
        @NonNull private final Clip newClip;

        public ReplaceClipSourceAction(@NonNull Timeline timeline, int index,
                                       @NonNull Clip oldClip, @NonNull Clip newClip) {
            this.timeline = timeline;
            this.index = index;
            this.oldClip = oldClip;
            this.newClip = newClip;
        }

        @Override public void execute() {
            timeline.removeClip(index);
            timeline.addClip(index, newClip);
        }
        @Override public void undo() {
            timeline.removeClip(index);
            timeline.addClip(index, oldClip);
        }
        @NonNull @Override public String getDescription() { return "Replace media"; }
    }

    /** Loop mode + extension change. */
    public static final class LoopAction implements EditAction {
        private final Clip clip;
        private final int oldMode, newMode;
        private final long oldBefore, oldAfter, newBefore, newAfter;

        public LoopAction(@NonNull Clip clip,
                          int oldMode, long oldBefore, long oldAfter,
                          int newMode, long newBefore, long newAfter) {
            this.clip = clip;
            this.oldMode = oldMode;
            this.oldBefore = oldBefore;
            this.oldAfter = oldAfter;
            this.newMode = newMode;
            this.newBefore = newBefore;
            this.newAfter = newAfter;
        }

        @Override public void execute() {
            clip.setLoopMode(newMode);
            clip.setLoopBeforeMs(newBefore);
            clip.setLoopAfterMs(newAfter);
        }
        @Override public void undo() {
            clip.setLoopMode(oldMode);
            clip.setLoopBeforeMs(oldBefore);
            clip.setLoopAfterMs(oldAfter);
        }
        @NonNull @Override public String getDescription() {
            return "Loop " + oldMode + " → " + newMode + " [" + oldBefore + "+" + oldAfter + "] → [" + newBefore + "+" + newAfter + "]";
        }
    }
}
