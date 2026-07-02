package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/**
 * Ordered list of {@link Clip}s that make up the editor timeline.
 *
 * <p>For MVP (Phase 1) this holds a single clip.
 * Scales to multi-clip editing in Phase 2 without changes.</p>
 */
public class Timeline {

    @NonNull
    private final List<Clip> clips;

    /** Audio clips on the audio track (independent of video segments). */
    @NonNull
    private final List<AudioClip> audioClips;

    /** Text overlays rendered on top of the whole timeline. */
    @NonNull
    private final List<TextOverlayItem> textOverlays;

    /** Transitions (fade to black, cross-dissolve, etc.) */
    @NonNull
    private final List<Transition> transitions;

    /** Placed waveform/spectrum visualizers (schema v7). */
    @NonNull
    private final List<WaveformOverlayInstance> waveformOverlays;

    public Timeline() {
        this.clips = new ArrayList<>();
        this.audioClips = new ArrayList<>();
        this.textOverlays = new ArrayList<>();
        this.transitions = new ArrayList<>();
        this.waveformOverlays = new ArrayList<>();
    }

    // ── Clip management ──────────────────────────────────────────────

    public void addClip(@NonNull Clip clip) {
        clips.add(clip);
    }

    public void addClip(int index, @NonNull Clip clip) {
        clips.add(index, clip);
    }

    public void removeClip(@NonNull Clip clip) {
        clips.remove(clip);
    }

    public void removeClip(int index) {
        if (index >= 0 && index < clips.size()) {
            clips.remove(index);
        }
    }

    /**
     * Returns an unmodifiable view of the clip list.
     */
    @NonNull
    public List<Clip> getClips() {
        return Collections.unmodifiableList(clips);
    }

    /**
     * Returns the clip at the given index, or null if out of bounds.
     */
    @NonNull
    public Clip getClip(int index) {
        return clips.get(index);
    }

    public int getClipCount() {
        return clips.size();
    }

    public boolean isEmpty() {
        return clips.isEmpty();
    }

    /**
     * Total duration of the timeline in milliseconds.
     * This is the maximum of the video track duration and the furthest audio clip end.
     */
    public long getTotalDurationMs() {
        long videoTotal = 0;
        for (Clip clip : clips) {
            videoTotal += clip.hasLoopExtension() ? clip.getVisualDurationMs() : clip.getTrimmedDurationMs();
        }
        long audioEnd = 0;
        for (AudioClip ac : audioClips) {
            long end = ac.getEndOnTimelineMs();
            if (end > audioEnd) audioEnd = end;
        }
        return Math.max(videoTotal, audioEnd);
    }

    /**
     * Total duration of the video track only (sum of all trimmed clip durations).
     */
    public long getVideoTrackDurationMs() {
        long total = 0;
        for (Clip clip : clips) {
            total += clip.hasLoopExtension() ? clip.getVisualDurationMs() : clip.getTrimmedDurationMs();
        }
        return total;
    }

    /**
     * Swap clip ordering (for drag-to-reorder).
     */
    public void swapClips(int fromIndex, int toIndex) {
        if (fromIndex >= 0 && fromIndex < clips.size()
                && toIndex >= 0 && toIndex < clips.size()) {
            Collections.swap(clips, fromIndex, toIndex);
        }
    }

    /**
     * Move a clip from one position to another (insert at new position).
     * This removes the clip from the old position and inserts it at the new position.
     *
     * @param fromIndex current index of the clip
     * @param toIndex   target index for the clip
     */
    public void moveClip(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= clips.size()
                || toIndex < 0 || toIndex >= clips.size()
                || fromIndex == toIndex) return;
        Clip clip = clips.remove(fromIndex);
        clips.add(toIndex, clip);
    }

    /**
     * Split a clip at the given absolute position into two new clips.
     *
     * <p>The original clip is replaced by two clips:
     * <ul>
     *   <li>Clip A: same source, inPoint = original.inPoint, outPoint = splitPointMs</li>
     *   <li>Clip B: same source, inPoint = splitPointMs, outPoint = original.outPoint</li>
     * </ul>
     * Both inherit all effects (speed, volume, rotate, flip, crop) from the original.</p>
     *
     * @param clipIndex       index of the clip to split
     * @param splitPointMs    absolute position in the source video to split at
     * @return the index of the first part (clipA), or -1 if invalid
     */
    public int splitAt(int clipIndex, long splitPointMs) {
        if (clipIndex < 0 || clipIndex >= clips.size()) return -1;

        Clip original = clips.get(clipIndex);

        // Validate split point is within the clip's trim region (with margin)
        long minSplitMs = original.getInPointMs() + 100; // At least 100ms from start
        long maxSplitMs = original.getOutPointMs() - 100; // At least 100ms from end
        if (splitPointMs < minSplitMs || splitPointMs > maxSplitMs) return -1;

        // Create two copies with the same effects
        Clip clipA = new Clip(original);
        clipA.setOutPointMs(splitPointMs);

        Clip clipB = new Clip(original);
        clipB.setInPointMs(splitPointMs);

        // Partition removed spans and transcript words by the split point so each
        // half only carries what falls in its own source range. Without this both
        // halves kept the FULL transcript, so the second half's words showed up
        // shifted/misaligned after every cut.
        partitionAfterSplit(original, clipA, clipB, splitPointMs);

        // Replace original with the two parts
        clips.remove(clipIndex);
        clips.add(clipIndex, clipB);
        clips.add(clipIndex, clipA); // A goes first

        return clipIndex;
    }

    /**
     * Split-time partitioning shared by all split paths: clip A keeps everything
     * before {@code splitPointMs}, clip B everything at/after it. Removed spans are
     * clamped to each half's source range; transcript words are kept by start time.
     */
    private static void partitionAfterSplit(@NonNull Clip original,
                                            @NonNull Clip clipA, @NonNull Clip clipB,
                                            long splitPointMs) {
        clipA.setRemovedSpans(clampSpans(original.getRemovedSpans(),
                original.getInPointMs(), splitPointMs));
        clipB.setRemovedSpans(clampSpans(original.getRemovedSpans(),
                splitPointMs, original.getOutPointMs()));
        // NOTE: Transcript is NOT partitioned on split — both halves keep the full source
        // transcript. The panel views a windowed subset via Transcript.windowed(). This makes
        // split non-destructive: lengthening a trim reveals previously-hidden words.
        // See tasks/PLAN_transcript_windowing.md
    }

    @NonNull
    private static List<long[]> clampSpans(@NonNull List<long[]> spans, long lo, long hi) {
        List<long[]> out = new ArrayList<>();
        for (long[] s : spans) {
            long a = Math.max(lo, s[0]);
            long b = Math.min(hi, s[1]);
            if (b > a) out.add(new long[]{a, b});
        }
        return out;
    }

    /** Drop transcript words on the wrong side of the split (by word start time). */
    private static void partitionWords(@NonNull Clip clip, long splitMs, boolean keepBefore) {
        for (com.fadcam.ui.faditor.transcript.NamedTranscript nt : clip.getTranscripts()) {
            java.util.Iterator<com.fadcam.ui.faditor.transcript.TranscriptWord> it =
                    nt.transcript.words.iterator();
            while (it.hasNext()) {
                com.fadcam.ui.faditor.transcript.TranscriptWord w = it.next();
                boolean before = w.startMs < splitMs;
                if (before != keepBefore) it.remove();
            }
        }
    }

    /**
     * Duplicate a clip at the given index. The copy is inserted
     * immediately after the original.
     *
     * @param clipIndex index of the clip to duplicate
     * @return the index of the new copy, or -1 if invalid
     */
    public int duplicateClip(int clipIndex) {
        if (clipIndex < 0 || clipIndex >= clips.size()) return -1;
        Clip copy = new Clip(clips.get(clipIndex));
        clips.add(clipIndex + 1, copy);
        return clipIndex + 1;
    }

    @NonNull
    @Override
    public String toString() {
        return "Timeline{clips=" + clips.size()
                + ", audioClips=" + audioClips.size()
                + ", totalMs=" + getTotalDurationMs() + "}";
    }

    // ── Audio clip management ────────────────────────────────────────

    /**
     * Add an audio clip to the audio track.
     */
    public void addAudioClip(@NonNull AudioClip audioClip) {
        audioClips.add(audioClip);
    }

    /**
     * Remove an audio clip from the audio track.
     */
    public void removeAudioClip(@NonNull AudioClip audioClip) {
        audioClips.remove(audioClip);
    }

    /**
     * Remove an audio clip by index.
     */
    public void removeAudioClip(int index) {
        if (index >= 0 && index < audioClips.size()) {
            audioClips.remove(index);
        }
    }

    /**
     * Returns an unmodifiable view of the audio clip list.
     */
    @NonNull
    public List<AudioClip> getAudioClips() {
        return Collections.unmodifiableList(audioClips);
    }

    /**
     * Returns the audio clip at the given index, or null if out of bounds.
     */
    @Nullable
    public AudioClip getAudioClip(int index) {
        if (index < 0 || index >= audioClips.size()) return null;
        return audioClips.get(index);
    }

    /**
     * Returns the number of audio clips.
     */
    public int getAudioClipCount() {
        return audioClips.size();
    }

    /**
     * Whether there are any audio clips.
     */
    public boolean hasAudioClips() {
        return !audioClips.isEmpty();
    }

    // ── Text overlay management ──────────────────────────────────────

    public void addTextOverlay(@NonNull TextOverlayItem overlay) {
        textOverlays.add(overlay);
    }

    public void removeTextOverlay(@NonNull TextOverlayItem overlay) {
        textOverlays.remove(overlay);
    }

    @NonNull
    public List<TextOverlayItem> getTextOverlays() {
        return textOverlays;
    }

    public boolean hasTextOverlays() {
        return !textOverlays.isEmpty();
    }

    // ── Transition management ───────────────────────────────────────

    public void addTransition(@NonNull Transition transition) {
        // A transition lives on the seam between clip[clipIndex] and clip[clipIndex+1], so the only
        // valid indices are [0, clipCount-2]. (The old clamp allowed clipCount-1 — a seam AFTER the
        // last clip that doesn't exist — which orphaned the transition: it rendered/exported nothing.)
        int maxSeam = Math.max(0, clips.size() - 2);
        transition.clipIndex = Math.max(0, Math.min(transition.clipIndex, maxSeam));
        transitions.add(transition);
    }

    public void removeTransition(int index) {
        if (index >= 0 && index < transitions.size()) {
            transitions.remove(index);
        }
    }

    public void removeTransitionAtSeam(int seam) {
        for (int i = transitions.size() - 1; i >= 0; i--) {
            if (transitions.get(i).clipIndex == seam) {
                transitions.remove(i);
            }
        }
    }

    public void replaceTransition(int index, @NonNull Transition.Type type, long durationMs) {
        if (index < 0 || index >= transitions.size()) return;
        Transition t = transitions.get(index);
        t.type = type;
        t.durationMs = Math.max(100, Math.min(2000, durationMs));
    }

    public boolean setTransitionDuration(int index, long durationMs) {
        if (index < 0 || index >= transitions.size()) return false;
        transitions.get(index).durationMs = Math.max(100, Math.min(2000, durationMs));
        return true;
    }

    public void removeTransitionsForDeletedClip(int clipIndex) {
        for (int i = transitions.size() - 1; i >= 0; i--) {
            Transition t = transitions.get(i);
            if (t.clipIndex == clipIndex || t.clipIndex == clipIndex - 1) {
                transitions.remove(i);
            } else if (t.clipIndex > clipIndex) {
                t.clipIndex--;
            }
        }
    }

    public void shiftTransitionsAfterInsert(int insertIndex) {
        for (Transition t : transitions) {
            if (t.clipIndex >= insertIndex) {
                t.clipIndex++;
            }
        }
    }

    public void shiftTransitionsAfterSplit(int splitIndex) {
        for (Transition t : transitions) {
            if (t.clipIndex >= splitIndex) {
                t.clipIndex++;
            }
        }
    }

    public void clearTransitions() {
        transitions.clear();
    }

    @NonNull
    public List<Transition> getTransitions() {
        return Collections.unmodifiableList(transitions);
    }

    public boolean hasTransitions() {
        return !transitions.isEmpty();
    }

    // ── Waveform visualizer overlays (schema v7) ─────────────────────

    public void addWaveformOverlay(@NonNull WaveformOverlayInstance overlay) {
        waveformOverlays.add(overlay);
    }

    public void removeWaveformOverlay(@NonNull WaveformOverlayInstance overlay) {
        waveformOverlays.remove(overlay);
    }

    @NonNull
    public List<WaveformOverlayInstance> getWaveformOverlays() {
        return waveformOverlays;
    }

    public boolean hasWaveformOverlays() {
        return !waveformOverlays.isEmpty();
    }
}
