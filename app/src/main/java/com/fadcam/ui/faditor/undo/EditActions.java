package com.fadcam.ui.faditor.undo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
        /**
         * The timeline this clip lives on, so undo/redo can RIPPLE its riders.
         *
         * <p>Changing a clip's length moves the start of every clip after it, and anything
         * anchored to those clips has to move with them or it desynchronises from the words and
         * pictures it was placed against. Every interactive structural edit already brackets
         * itself with {@code beginStructuralEdit}/{@code endStructuralEdit} to do exactly that —
         * but the UNDO of a trim did not, because this action only ever held the Clip and had no
         * way to reach the timeline. So a trim moved the riders and undoing it left them where
         * the trim had put them, which is worse than never moving them: the project silently
         * drifts further out of sync with every undo.</p>
         *
         * <p>Nullable so older call sites keep compiling and simply behave as before.</p>
         */
        @Nullable private final Timeline timeline;

        public TrimAction(@NonNull Clip clip,
                          long oldIn, long oldOut,
                          long newIn, long newOut) {
            this(null, clip, oldIn, oldOut, newIn, newOut);
        }

        public TrimAction(@Nullable Timeline timeline, @NonNull Clip clip,
                          long oldIn, long oldOut,
                          long newIn, long newOut) {
            this.timeline = timeline;
            this.clip = clip;
            this.oldIn = oldIn;
            this.oldOut = oldOut;
            this.newIn = newIn;
            this.newOut = newOut;
        }

        /** Apply a bounds change and carry the riders with it, as the live edit paths do. */
        private void retrim(long in, long out) {
            if (timeline == null) {
                clip.setInPointMs(in);
                clip.setOutPointMs(out);
                return;
            }
            // beginStructural, not captureClipStarts: the editor already brackets undo and redo
            // wholesale, and two brackets each applying the same delta move a rider TWICE — as far
            // wrong as never moving it. The depth count makes this inner pair a no-op when nested,
            // while keeping the action correct when the AI or a script runs it with no editor.
            java.util.Map<String, Long> before = timeline.beginStructural();
            clip.setInPointMs(in);
            clip.setOutPointMs(out);
            timeline.endStructural(before);
        }

        @Override public void execute() { retrim(newIn, newOut); }
        @Override public void undo() { retrim(oldIn, oldOut); }
        /**
         * Shown to the user now (the undo toast and the history popup), so it reads in clock time
         * rather than raw milliseconds. Says which EDGE moved and by how much, because that is what
         * someone scanning their history is actually trying to recognise.
         */
        @NonNull @Override public String getDescription() {
            boolean inMoved = oldIn != newIn, outMoved = oldOut != newOut;
            if (inMoved && !outMoved) {
                return "Trim start " + com.fadcam.ui.faditor.util.TimeFormatter.formatAuto(oldIn)
                        + " → " + com.fadcam.ui.faditor.util.TimeFormatter.formatAuto(newIn);
            }
            if (outMoved && !inMoved) {
                return "Trim end " + com.fadcam.ui.faditor.util.TimeFormatter.formatAuto(oldOut)
                        + " → " + com.fadcam.ui.faditor.util.TimeFormatter.formatAuto(newOut);
            }
            return "Trim " + com.fadcam.ui.faditor.util.TimeFormatter.formatAuto(newIn) + "–"
                    + com.fadcam.ui.faditor.util.TimeFormatter.formatAuto(newOut);
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
        /** The timeline this clip lives on, so undo/redo can RIPPLE its riders. See TrimAction. */
        @Nullable private final Timeline timeline;

        public SpeedAction(@NonNull Clip clip, float oldSpeed, float newSpeed) {
            this(null, clip, oldSpeed, newSpeed);
        }

        public SpeedAction(@Nullable Timeline timeline, @NonNull Clip clip,
                           float oldSpeed, float newSpeed) {
            this.timeline = timeline;
            this.clip = clip;
            this.oldSpeed = oldSpeed;
            this.newSpeed = newSpeed;
        }

        /** Apply a speed change and carry the riders with it, as the live edit paths do. */
        private void apply(float speed) {
            if (timeline == null) {
                clip.setSpeedMultiplier(speed);
                return;
            }
            java.util.Map<String, Long> before = timeline.beginStructural();
            clip.setSpeedMultiplier(speed);
            timeline.endStructural(before);
        }

        @Override public void execute() { apply(newSpeed); }
        @Override public void undo() { apply(oldSpeed); }
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
        /** The timeline the clip lives on, so undo/redo can RIPPLE its riders. See TrimAction. */
        @Nullable private final Timeline timeline;

        public AudioTrimAction(@NonNull AudioClip clip,
                               long oldIn, long oldOut,
                               long newIn, long newOut) {
            this(null, clip, oldIn, oldOut, newIn, newOut);
        }

        public AudioTrimAction(@Nullable Timeline timeline,
                               @NonNull AudioClip clip,
                               long oldIn, long oldOut,
                               long newIn, long newOut) {
            this.timeline = timeline;
            this.clip = clip;
            this.oldIn = oldIn;
            this.oldOut = oldOut;
            this.newIn = newIn;
            this.newOut = newOut;
        }

        /** Apply a bounds change and carry the riders with it, as the live edit paths do. */
        private void retrim(long in, long out) {
            if (timeline == null) {
                clip.setInPointMs(in);
                clip.setOutPointMs(out);
                return;
            }
            java.util.Map<String, Long> before = timeline.beginStructural();
            clip.setInPointMs(in);
            clip.setOutPointMs(out);
            timeline.endStructural(before);
        }

        @Override public void execute() { retrim(newIn, newOut); }
        @Override public void undo() { retrim(oldIn, oldOut); }
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

        /**
         * The original's trim range AS IT WAS at split time.
         *
         * <p>{@code originalClip} is a live object that is no longer on the timeline, and nothing
         * stops another code path writing to it in the meantime — which is exactly what happened:
         * the player's {@code updateTrimEndOnly} wrote clip A's out-point through to it, so undo
         * restored a clip truncated to clip A's range and the project came back shorter than it
         * went in. That write is gone, but an undo record that depends on nobody else touching a
         * detached object is a trap left in the road; two longs make it independent of them.</p>
         */
        private final long originalIn;
        private final long originalOut;

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
            this.originalIn = originalClip.getInPointMs();
            this.originalOut = originalClip.getOutPointMs();
        }

        @Override public void execute() {
            // BY IDENTITY, not by index — see undo() for why.
            int at = timeline.indexOfClip(originalClip);
            if (at < 0) return;
            timeline.removeClip(originalClip);
            timeline.addClip(at, clipB);
            timeline.addClip(at, clipA);
            // REDO must reproduce the index shift too — undo() restored the pre-split list.
            timeline.shiftTransitionsAfterSplit(at);
            // …and re-home the riders, which undo() put back on the original. splitAt does this
            // for the FIRST split; this path rebuilds the halves by hand and so must do it itself,
            // or one undo+redo round trip leaves every rider anchored to a clip that is gone.
            timeline.reanchorAfterManualSplit(originalClip.getId(), at);
        }
        @Override public void undo() {
            // BY IDENTITY. This removed clips at originalIndex and originalIndex+1 on trust:
            // two removals and one insertion, so if those slots did NOT hold this split's two
            // halves it destroyed two unrelated clips and left the timeline one clip shorter.
            // Indices are not stable — any edit before this point in the list shifts them, and
            // undo runs arbitrarily long after the split was recorded. A clip vanished from a
            // real 43-minute project this way (JoyRaptor, 2026-08-12), and the loss is silent
            // because autosave writes it out immediately.
            //
            // Refusing to act when the halves are not both present is deliberate: an undo that
            // cannot find what it created has already lost track of the document, and guessing
            // at indices is precisely how it deletes someone's footage.
            int ia = timeline.indexOfClip(clipA);
            int ib = timeline.indexOfClip(clipB);
            if (ia < 0 || ib < 0) return;
            int at = Math.min(ia, ib);
            timeline.removeClip(clipA);
            timeline.removeClip(clipB);
            // Re-apply the range captured at split time, in case anything wrote to this detached
            // object while it was off the timeline. See originalIn/originalOut.
            originalClip.setInPointMs(originalIn);
            originalClip.setOutPointMs(originalOut);
            timeline.addClip(Math.min(at, timeline.getClipCount()), originalClip);
            timeline.restoreTransitions(transitionsBefore);
            // The halves are gone; anything anchored to them would dangle, and a dangling host is
            // reported as an orphan and never ripples again — a silent, permanent desync that
            // survives every later edit. The join is unambiguous, so it is repaired rather than
            // asked about (unlike a delete, where §4A must ask).
            timeline.reanchorAfterJoin(originalClip.getId(), clipA.getId(), clipB.getId());
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
            // Defensive copy: the silence-removal pass reuses/mutates its list after
            // recordAction, and redo must replay what was recorded, not what it became.
            this.replacements = new java.util.ArrayList<>(replacements);
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
        /** The timeline this clip lives on, so undo/redo can RIPPLE its riders. See TrimAction. */
        @Nullable private final Timeline timeline;

        public LoopAction(@NonNull Clip clip,
                          int oldMode, long oldBefore, long oldAfter,
                          int newMode, long newBefore, long newAfter) {
            this(null, clip, oldMode, oldBefore, oldAfter, newMode, newBefore, newAfter);
        }

        public LoopAction(@Nullable Timeline timeline,
                          @NonNull Clip clip,
                          int oldMode, long oldBefore, long oldAfter,
                          int newMode, long newBefore, long newAfter) {
            this.timeline = timeline;
            this.clip = clip;
            this.oldMode = oldMode;
            this.oldBefore = oldBefore;
            this.oldAfter = oldAfter;
            this.newMode = newMode;
            this.newBefore = newBefore;
            this.newAfter = newAfter;
        }

        /** Apply a loop change and carry the riders with it, as the live edit paths do. */
        private void apply(int mode, long beforeMs, long afterMs) {
            if (timeline == null) {
                clip.setLoopMode(mode);
                clip.setLoopBeforeMs(beforeMs);
                clip.setLoopAfterMs(afterMs);
                return;
            }
            java.util.Map<String, Long> before = timeline.beginStructural();
            clip.setLoopMode(mode);
            clip.setLoopBeforeMs(beforeMs);
            clip.setLoopAfterMs(afterMs);
            timeline.endStructural(before);
        }

        @Override public void execute() { apply(newMode, newBefore, newAfter); }
        @Override public void undo() { apply(oldMode, oldBefore, oldAfter); }
        @NonNull @Override public String getDescription() {
            return "Loop " + oldMode + " → " + newMode + " [" + oldBefore + "+" + oldAfter + "] → [" + newBefore + "+" + newAfter + "]";
        }
    }

    /** Transcript word-strikes (S6) — one button press = one undo step, however many words. */
    public static final class TranscriptStrikesAction implements EditAction {
        @NonNull private final com.fadcam.ui.faditor.transcript.Transcript transcript;
        @NonNull private final java.util.List<Boolean> beforeStrikes;
        @NonNull private final java.util.List<Boolean> afterStrikes;

        public TranscriptStrikesAction(@NonNull com.fadcam.ui.faditor.transcript.Transcript transcript,
                                       @NonNull java.util.List<Boolean> before,
                                       @NonNull java.util.List<Boolean> after) {
            this.transcript = transcript;
            // Defensive copy — see S2; the caller reuses/mutates its list after recordAction
            this.beforeStrikes = new java.util.ArrayList<>(before);
            this.afterStrikes = new java.util.ArrayList<>(after);
        }

        private void apply(@NonNull java.util.List<Boolean> strikes) {
            int n = Math.min(strikes.size(), transcript.words.size());
            for (int i = 0; i < n; i++) {
                transcript.words.get(i).struck = strikes.get(i);
            }
        }

        @Override public void execute() { apply(afterStrikes); }
        @Override public void undo() { apply(beforeStrikes); }
        @NonNull @Override public String getDescription() {
            int changed = 0;
            int m = Math.min(beforeStrikes.size(), afterStrikes.size());
            for (int i = 0; i < m; i++) if (!beforeStrikes.get(i).equals(afterStrikes.get(i))) changed++;
            return "Transcript strikes (" + changed + " words)";
        }
    }
}
