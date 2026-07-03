package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fadcam.ui.faditor.layers.Track;
import com.fadcam.ui.faditor.layers.TrackKind;
import com.fadcam.ui.faditor.layers.TimedItem;
import com.fadcam.ui.faditor.layers.TrackFlags;
import com.fadcam.ui.faditor.layers.LayerTrackDef;

import java.util.LinkedHashMap;
import java.util.Map;
/**
 * Ordered list of {@link Clip}s that make up the editor timeline.
 *
 * <p>For MVP (Phase 1) this holds a single clip.
 * Scales to multi-clip editing in Phase 2 without changes.</p>
 *
 * <h3>Schema-v8 layer model (M5)</h3>
 * <p>The flat lists ({@link #clips}, {@link #textOverlays}, {@link #audioClips},
 * {@link #waveformOverlays}, {@link #transitions}) remain the <b>storage of record</b>.
 * The schema-v8 {@link Track} objects ({@link #getMasterTrack()}, {@link #getLayers()},
 * {@link #getAudioTracks()}) are <b>synchronized views built on demand</b> from those
 * flat lists per PLAN §2.2 — they are re-derived every call, never cached. This is the
 * "synchronized-from-flat" strategy from PLAN §2.3: every existing call site
 * (ExportManager, EditorTimelineView, FaditorEditorActivity, AIToolExecutor,
 * ProjectStorage) keeps using {@link #getClips()} / {@link #getTextOverlays()} /
 * {@link #getAudioClips()} unchanged and gets byte-identical content/order, while new
 * layer-aware code reads the Track views. Because the views are always freshly derived
 * from the flat lists, mutations through the existing flat APIs and reads through the
 * new Track APIs can never diverge.</p>
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

    /** Placed sprite instances (schema v9 — PLAN_SPRITE_ANIMATION S1). */
    @NonNull
    private final List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> spriteOverlays;

    /**
     * Master edit behavior (schema v8). "ripple" = deleting/trimming a master clip
     * shifts later clips; "gap" = leaves a gap. Default "ripple". Floating layers are
     * always absolute-time regardless of this setting. Only the master track honors it.
     */
    @NonNull
    private String rippleMode = "ripple";

    /**
     * Persistent home for Track UI/edit flags (collapsed/hidden/locked/muted/zIndex),
     * keyed by the track's stable id ("master" / "text" / "audio" — see
     * {@link #getMasterTrack()} et al.). Required because the {@link Track} objects
     * those methods return are rebuilt fresh on every call (M5 status note; M6 fix).
     * M5/M6 only ever produce those three literal ids; M10 (new tracks) will need a
     * stable-id scheme for user-created tracks, but that is out of scope here.
     */
    @NonNull
    private final Map<String, TrackFlags> trackFlags = new LinkedHashMap<>();

    /**
     * Read-only snapshot of {@link #trackFlags} taken once, right after this
     * project finished loading (see {@link #snapshotBaselineTrackFlags()}), used by
     * {@code ProjectStorage}'s concurrent-instance merge guard (Stage 1 P0 fix:
     * "shows unlocked, acts locked"). Null until the snapshot is taken (a brand-new,
     * never-loaded project has no baseline — nothing to diff against).
     *
     * <p>The merge needs to answer "did THIS session change track X's flags," which
     * is NOT the same question as "does {@link #trackFlags} currently have an entry
     * for X" — an entry can exist for reasons unrelated to a same-session edit
     * (e.g. a value that happens to already be there from load, or written by an
     * incidental code path), and a currently-empty {@link #trackFlags} for a track
     * id that HAD a non-default entry at load time is itself a real edit (the user
     * reverted it to default this session). Comparing the CURRENT value against
     * this baseline value distinguishes both cases correctly, where "does an entry
     * exist" alone cannot.</p>
     */
    @Nullable
    private Map<String, TrackFlags> baselineTrackFlags;

    /**
     * Take the one-time baseline snapshot used by the concurrent-instance merge
     * guard (see {@link #baselineTrackFlags}'s doc). Call exactly once, right after
     * a project finishes loading (before any user edit can occur) — {@code
     * ProjectStorage.load()} is the only caller. A no-op if already taken (so an
     * accidental double-call, e.g. from a defensive re-check, can't overwrite a
     * real baseline with a since-edited one).
     */
    public void snapshotBaselineTrackFlags() {
        if (baselineTrackFlags != null) return;
        Map<String, TrackFlags> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, TrackFlags> e : trackFlags.entrySet()) {
            snapshot.put(e.getKey(), e.getValue().copy());
        }
        baselineTrackFlags = snapshot;
    }

    /**
     * True if {@code trackId}'s CURRENT flags differ from what they were at load
     * time (see {@link #baselineTrackFlags}), i.e. this session has genuinely
     * edited this track's collapsed/hidden/locked/muted/zIndex state. Always true
     * if no baseline was ever taken (conservative default — treat as "already
     * touched" so a merge guard skips it rather than risk overwriting an edit it
     * can't actually verify is unrelated).
     */
    public boolean trackFlagsChangedSinceLoad(@NonNull String trackId) {
        if (baselineTrackFlags == null) return true;
        TrackFlags baseline = baselineTrackFlags.get(trackId);
        TrackFlags current = trackFlags.get(trackId);
        if (baseline == null && current == null) return false;
        if (baseline == null || current == null) return true;
        return !baseline.equalsFlags(current);
    }

    /**
     * Persistent, ordered list of USER-CREATED layer-track definitions (M10; PLAN
     * Part 7 row M10 track-membership design — the extension the M5 status note
     * asked for). {@link #getLayers()}/{@link #getAudioTracks()} produce one
     * {@link Track} view per distinct {@link TextOverlayItem#getLayerId()}/
     * {@code AudioClip#getLayerId()} value found among the flat lists, PLUS one
     * entry here for every still-empty user-created track (so a brand-new empty
     * track survives a save/reload before anything is dragged into it). Empty for
     * every project that predates M10 or never used it — in that case grouping
     * degrades to exactly the M5/M6 "every item → ONE fixed text/audio track"
     * behavior (see the grouping methods below).
     */
    @NonNull
    private final List<LayerTrackDef> extraLayerTracks = new ArrayList<>();

    public Timeline() {
        this.clips = new ArrayList<>();
        this.audioClips = new ArrayList<>();
        this.textOverlays = new ArrayList<>();
        this.transitions = new ArrayList<>();
        this.waveformOverlays = new ArrayList<>();
        this.spriteOverlays = new ArrayList<>();
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

    // ── Sprite overlay management (schema v9, PLAN_SPRITE_ANIMATION S1) ──

    public void addSpriteOverlay(@NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem sprite) {
        spriteOverlays.add(sprite);
    }

    public void removeSpriteOverlay(@NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem sprite) {
        spriteOverlays.remove(sprite);
    }

    @NonNull
    public List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> getSpriteOverlays() {
        return spriteOverlays;
    }

    /** S6 export guard: {@code isSimpleTrim} MUST include this (fast-path lesson). */
    public boolean hasSpriteOverlays() {
        return !spriteOverlays.isEmpty();
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

    // ── Schema-v8 layer model (M5) ───────────────────────────────────
    //
    // These are SYNCHRONIZED VIEWS built on demand from the flat lists above
    // (PLAN §2.2/§2.3). The flat lists remain the storage of record; nothing here
    // caches state, so the flat APIs and the Track APIs can never diverge.

    /** Master edit behavior ("ripple" or "gap"). Default "ripple". */
    @NonNull
    public String getRippleMode() {
        return rippleMode;
    }

    public void setRippleMode(@NonNull String mode) {
        // Accept only the two known values; anything else falls back to the default.
        this.rippleMode = "gap".equals(mode) ? "gap" : "ripple";
    }

    // ── Track flags side-table (M6; PLAN Part 7 M6 scope 3 / M5 status note) ──

    /**
     * Persistent flags for the track with the given stable id, or {@code null} if none
     * have ever been set (i.e. the track is at all-default state). Read-only lookup —
     * use {@link #getOrCreateTrackFlags(String)} to mutate.
     */
    @Nullable
    public TrackFlags getTrackFlags(@NonNull String trackId) {
        return trackFlags.get(trackId);
    }

    /**
     * Mutable flags entry for the given track id, creating a default (all-false/zero)
     * entry on first access. This is the entry point M6's row-header toggles write
     * through, followed by an undo-recording caller (see {@code FaditorEditorActivity}).
     */
    @NonNull
    public TrackFlags getOrCreateTrackFlags(@NonNull String trackId) {
        TrackFlags flags = trackFlags.get(trackId);
        if (flags == null) {
            flags = new TrackFlags();
            trackFlags.put(trackId, flags);
        }
        return flags;
    }

    /**
     * Replace the flags entry for a track id wholesale (used by undo to restore a
     * captured snapshot). Removes the entry entirely if the restored flags are all-default,
     * mirroring {@link #pruneDefaultTrackFlags()} so undo never leaves stray empty entries.
     */
    public void setTrackFlags(@NonNull String trackId, @Nullable TrackFlags flags) {
        if (flags == null || flags.isDefault()) {
            trackFlags.remove(trackId);
        } else {
            trackFlags.put(trackId, flags);
        }
    }

    /** Drop any all-default entries (keeps the map/serialized block minimal). */
    public void pruneDefaultTrackFlags() {
        trackFlags.values().removeIf(TrackFlags::isDefault);
    }

    /**
     * Drop any {@code trackFlags} entry whose id provably matches no current track
     * (Stage 1 P0 fix follow-up: migrate/clean stale flag entries on project load).
     * Conservative by design — this is user data (a locked/hidden/muted/collapsed
     * choice), so an id is only dropped when it CANNOT possibly resolve to a track
     * on the next {@link #getMasterTrack()}/{@link #getLayers()}/{@link
     * #getAudioTracks()} call: not {@code "master"}, not the fixed {@code "text"}/
     * {@code "audio"} defaults, not a {@link LayerTrackDef} id (a still-empty user-
     * created track legitimately has no items yet but IS a real track — see
     * {@link #createLayerTrack}), and not a {@code layerId} any current {@link
     * TextOverlayItem}/{@link AudioClip} still references. Anything else is a
     * leftover from a track that no longer exists (e.g. its last item was deleted
     * without going through {@link #removeLayerTrackDef}, or — the scenario this
     * milestone's bug was traced to — a stale flags entry left behind by an id
     * scheme change). Returns the dropped ids (empty if nothing was stale) so the
     * caller can log exactly what was removed rather than silently discarding it.
     */
    @NonNull
    public List<String> pruneOrphanedTrackFlags() {
        java.util.Set<String> liveIds = new java.util.HashSet<>();
        liveIds.add("master");
        liveIds.add("text");
        liveIds.add("audio");
        liveIds.add("sprite"); // default sprite track (schema v9) — review gate 2026-07-03
        for (LayerTrackDef def : extraLayerTracks) liveIds.add(def.getId());
        for (TextOverlayItem o : textOverlays) {
            if (o.getLayerId() != null) liveIds.add(o.getLayerId());
        }
        for (AudioClip ac : audioClips) {
            if (ac.getLayerId() != null) liveIds.add(ac.getLayerId());
        }
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem so : spriteOverlays) {
            if (so.getLayerId() != null) liveIds.add(so.getLayerId());
        }
        List<String> dropped = new ArrayList<>();
        java.util.Iterator<String> it = trackFlags.keySet().iterator();
        while (it.hasNext()) {
            String id = it.next();
            if (!liveIds.contains(id)) {
                dropped.add(id);
                it.remove();
            }
        }
        return dropped;
    }

    /**
     * Live map of every non-default track-flags entry, keyed by track id. Used by
     * {@code ProjectStorage} to serialize the side-table. Do not mutate the returned
     * map directly from outside the storage layer — use {@link #getOrCreateTrackFlags}
     * / {@link #setTrackFlags}.
     */
    @NonNull
    public Map<String, TrackFlags> getAllTrackFlags() {
        return trackFlags;
    }

    /**
     * Apply a track's persisted flags (if any) onto a freshly-built view object.
     *
     * <p>Note on the PLAN §6.1 "collapsed is the default (on phones)" guidance: this is
     * deliberately NOT implemented as an unpersisted rendering default here, because
     * {@code usesLayerFeatures()} (PLAN §4.1's dual-write v7/v8 boundary) treats
     * {@code isCollapsed()==true} as "the project uses a real layer feature." Seeding
     * every migrated text/audio track collapsed by default would make every ordinary
     * v7 project with a text overlay look v8-worthy on its very first load after this
     * milestone — a regression against the M5 downgrade-guard contract. So M6 leaves
     * the uncollapsed (expanded) state as the true, harmless default and only persists
     * an entry once the user actually taps the caret. A per-track-kind visual default
     * (rows starting visually collapsed without being a stored "feature") is left as a
     * follow-up — see the M6 build report's "surprises / deliberately not done."</p>
     */
    private void applyTrackFlags(@NonNull Track track) {
        TrackFlags flags = trackFlags.get(track.getId());
        if (flags == null) return;
        track.setCollapsed(flags.collapsed);
        track.setHidden(flags.hidden);
        track.setLocked(flags.locked);
        track.setMuted(flags.muted);
        track.setZIndex(flags.zIndex);
    }

    /**
     * Build the MASTER track from {@link #clips} (PLAN §2.2). Each master item's
     * absolute {@code timelineStartMs} is derived by summing prior clip durations,
     * using the same loop-aware per-clip contribution as {@link #getVideoTrackDurationMs()}.
     * Freshly rebuilt on every call; persisted flags (M6) are re-applied via
     * {@link #applyTrackFlags(Track)} so collapsed/hidden/locked/muted survive rebuilds.
     */
    @NonNull
    public Track getMasterTrack() {
        Track master = new Track("master", TrackKind.MASTER, "Master");
        long cursorMs = 0;
        for (Clip clip : clips) {
            master.addItem(TimedItem.ofClip(clip, cursorMs));
            cursorMs += clip.hasLoopExtension()
                    ? clip.getVisualDurationMs() : clip.getTrimmedDurationMs();
        }
        applyTrackFlags(master);
        return master;
    }

    /**
     * Build the floating layer tracks above the master (PLAN §2.2, extended M10 §6.3
     * track-membership). Groups {@link #textOverlays} by {@link TextOverlayItem#getLayerId()}:
     * every item with a {@code null} (or {@code "text"}) layerId lands in the single
     * fixed {@code "text"} track — EXACTLY today's M5 behavior, byte-for-byte, for
     * every project that predates M10 or never used it (no item has ever had a
     * non-default layerId, so there is only ever this one bucket). An item with a
     * distinct non-null layerId lands in its own track instead, keyed by that id;
     * {@link #extraLayerTracks} additionally seeds a still-EMPTY track definition (so
     * a freshly-created empty layer survives a save/reload — see M10 build report).
     * Order: the fixed "text" track first (if non-empty), then user-created tracks in
     * {@link #extraLayerTracks} order. Freshly rebuilt on every call; persisted flags
     * (M6) are re-applied per track.
     */
    @NonNull
    public List<Track> getLayers() {
        // id -> ordered items, built in textOverlays' own order so each track's items
        // stay in insertion order regardless of how many tracks they're split across.
        Map<String, List<TextOverlayItem>> byLayer = new LinkedHashMap<>();
        for (TextOverlayItem overlay : textOverlays) {
            String id = overlay.getLayerId() != null ? overlay.getLayerId() : "text";
            byLayer.computeIfAbsent(id, k -> new ArrayList<>()).add(overlay);
        }
        List<Track> layers = new ArrayList<>();
        List<TextOverlayItem> defaultBucket = byLayer.remove("text");
        if (defaultBucket != null && !defaultBucket.isEmpty()) {
            layers.add(buildTextTrack("text", TrackKind.TEXT, "Text", defaultBucket));
        }
        for (LayerTrackDef def : extraLayerTracks) {
            if (def.getKind() != TrackKind.TEXT && def.getKind() != TrackKind.STICKER) continue;
            List<TextOverlayItem> bucket = byLayer.remove(def.getId());
            layers.add(buildTextTrack(def.getId(), def.getKind(), def.getName(),
                    bucket != null ? bucket : Collections.emptyList()));
        }
        // Any remaining bucket (a layerId with items but no matching def — should not
        // happen via the normal M10 UI, but defensive: surface it as a track anyway
        // rather than silently dropping items) — mirrors old-build-tolerant patterns
        // elsewhere in this class.
        for (Map.Entry<String, List<TextOverlayItem>> e : byLayer.entrySet()) {
            layers.add(buildTextTrack(e.getKey(), TrackKind.TEXT, "Text", e.getValue()));
        }

        // SPRITE tracks (schema v9, PLAN_SPRITE_ANIMATION S1) — mirrors the text
        // grouping exactly: default "sprite" bucket first, then SPRITE LayerTrackDefs,
        // then defensive leftover buckets. Sprite rows sit after text rows in the
        // floating band (z stays list-order until row-reorder ships).
        Map<String, List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem>> spritesByLayer =
                new LinkedHashMap<>();
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem so : spriteOverlays) {
            String id = so.getLayerId() != null ? so.getLayerId() : "sprite";
            spritesByLayer.computeIfAbsent(id, k -> new ArrayList<>()).add(so);
        }
        List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> spriteDefault =
                spritesByLayer.remove("sprite");
        if (spriteDefault != null && !spriteDefault.isEmpty()) {
            layers.add(buildSpriteTrack("sprite", "Sprite", spriteDefault));
        }
        for (LayerTrackDef def : extraLayerTracks) {
            if (def.getKind() != TrackKind.SPRITE) continue;
            List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> bucket =
                    spritesByLayer.remove(def.getId());
            layers.add(buildSpriteTrack(def.getId(), def.getName(),
                    bucket != null ? bucket : Collections.emptyList()));
        }
        for (Map.Entry<String, List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem>> e
                : spritesByLayer.entrySet()) {
            layers.add(buildSpriteTrack(e.getKey(), "Sprite", e.getValue()));
        }
        return layers;
    }

    @NonNull
    private Track buildSpriteTrack(@NonNull String id, @NonNull String name,
            @NonNull List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> items) {
        Track track = new Track(id, TrackKind.SPRITE, name);
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem so : items) {
            track.addItem(TimedItem.ofSprite(so));
        }
        applyTrackFlags(track);
        return track;
    }

    @NonNull
    private Track buildTextTrack(@NonNull String id, @NonNull TrackKind kind, @NonNull String name,
                                  @NonNull List<TextOverlayItem> items) {
        Track track = new Track(id, kind, name);
        for (TextOverlayItem overlay : items) {
            track.addItem(TimedItem.ofTextOverlay(overlay));
        }
        applyTrackFlags(track);
        return track;
    }

    /**
     * Build the audio tracks below the master (PLAN §2.2, extended M10). Mirrors
     * {@link #getLayers()}'s grouping exactly, keyed by {@code AudioClip#getLayerId()}
     * against the fixed {@code "audio"} default track. Freshly rebuilt on every call;
     * persisted flags (M6) are re-applied per track.
     */
    @NonNull
    public List<Track> getAudioTracks() {
        Map<String, List<AudioClip>> byLayer = new LinkedHashMap<>();
        for (AudioClip ac : audioClips) {
            String id = ac.getLayerId() != null ? ac.getLayerId() : "audio";
            byLayer.computeIfAbsent(id, k -> new ArrayList<>()).add(ac);
        }
        List<Track> tracks = new ArrayList<>();
        List<AudioClip> defaultBucket = byLayer.remove("audio");
        if (defaultBucket != null && !defaultBucket.isEmpty()) {
            tracks.add(buildAudioTrack("audio", "Audio", defaultBucket));
        }
        for (LayerTrackDef def : extraLayerTracks) {
            if (def.getKind() != TrackKind.AUDIO) continue;
            List<AudioClip> bucket = byLayer.remove(def.getId());
            tracks.add(buildAudioTrack(def.getId(), def.getName(),
                    bucket != null ? bucket : Collections.emptyList()));
        }
        for (Map.Entry<String, List<AudioClip>> e : byLayer.entrySet()) {
            tracks.add(buildAudioTrack(e.getKey(), "Audio", e.getValue()));
        }
        return tracks;
    }

    @NonNull
    private Track buildAudioTrack(@NonNull String id, @NonNull String name,
                                   @NonNull List<AudioClip> items) {
        Track audioTrack = new Track(id, TrackKind.AUDIO, name);
        for (AudioClip ac : items) {
            audioTrack.addItem(TimedItem.ofAudioClip(ac));
        }
        applyTrackFlags(audioTrack);
        return audioTrack;
    }

    // ── User-created layer-track definitions (M10) ─────────────────────

    /**
     * Create a new, persistent, initially-EMPTY layer track and return its stable id.
     * {@code kind} must be {@link TrackKind#TEXT}/{@link TrackKind#STICKER}/
     * {@link TrackKind#SPRITE} (floating layer — SPRITE routed since schema v9,
     * PLAN_SPRITE_ANIMATION S1) or {@link TrackKind#AUDIO} (audio band) — the kinds
     * {@link #getLayers()}/{@link #getAudioTracks()} route by {@code layerId} today.
     */
    @NonNull
    public String createLayerTrack(@NonNull TrackKind kind, @NonNull String name) {
        LayerTrackDef def = new LayerTrackDef(kind, name);
        extraLayerTracks.add(def);
        return def.getId();
    }

    /** Re-insert a previously-created track definition (undo of a delete/creation). */
    public void restoreLayerTrackDef(@NonNull LayerTrackDef def) {
        for (LayerTrackDef existing : extraLayerTracks) {
            if (existing.getId().equals(def.getId())) return; // already present
        }
        extraLayerTracks.add(def);
    }

    /**
     * Remove a user-created track definition (does NOT touch any items still
     * pointing at its id — callers must reassign/delete those first; PLAN Part 7 M10
     * scope 2 "deleting the last item... removes the empty track"). No-op for the
     * fixed "text"/"audio" ids (they have no definition to remove).
     */
    public void removeLayerTrackDef(@NonNull String trackId) {
        extraLayerTracks.removeIf(d -> d.getId().equals(trackId));
    }

    @Nullable
    public LayerTrackDef getLayerTrackDef(@NonNull String trackId) {
        for (LayerTrackDef def : extraLayerTracks) {
            if (def.getId().equals(trackId)) return def;
        }
        return null;
    }

    /** Live list of every user-created track definition, for {@code ProjectStorage} serialization. */
    @NonNull
    public List<LayerTrackDef> getExtraLayerTracks() {
        return extraLayerTracks;
    }

    /**
     * True if any item currently references {@code trackId} as its layerId (used to
     * decide whether an empty user-created track is safe to auto-remove).
     */
    public boolean layerTrackHasItems(@NonNull String trackId) {
        for (TextOverlayItem o : textOverlays) {
            if (trackId.equals(o.getLayerId())) return true;
        }
        for (AudioClip ac : audioClips) {
            if (trackId.equals(ac.getLayerId())) return true;
        }
        return false;
    }
}
