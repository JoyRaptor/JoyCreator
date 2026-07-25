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

    /**
     * FLOATING overlay-video/PiP clips (M-COMP-2, PLAN_LAYERS_V2 §3.3) — the flat
     * storage-of-record for VIDEO/IMAGE layer items, exactly parallel to
     * {@link #textOverlays}/{@link #spriteOverlays}. NEVER mixed into {@link #clips}
     * (the master list stays byte-frozen); each clip here carries its own
     * {@code layerId}/{@code overlayStartMs}/{@code overlayTransform}. Empty for
     * every project that predates M-COMP-2.
     */
    @NonNull
    private final List<Clip> overlayClips = new ArrayList<>();

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

    // ── Floating overlay-video clips (M-COMP-2) ──────────────────────

    /** Unmodifiable view of the floating overlay (PiP) clips. */
    @NonNull
    public List<Clip> getOverlayClips() {
        return Collections.unmodifiableList(overlayClips);
    }

    /** Add a floating overlay clip. The clip must carry a non-null {@code layerId}. */
    public void addOverlayClip(@NonNull Clip clip) {
        overlayClips.add(clip);
    }

    public void removeOverlayClip(@NonNull Clip clip) {
        overlayClips.remove(clip);
    }

    /** Find a floating overlay clip by id, or null. */
    @Nullable
    public Clip findOverlayClip(@NonNull String id) {
        for (Clip c : overlayClips) {
            if (c.getId().equals(id)) return c;
        }
        return null;
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
     * Add an audio clip to the audio track, auto-resolving any overlap
     * with existing clips on the same layer track (audio-overlap P0 fix).
     */
    public void addAudioClip(@NonNull AudioClip audioClip) {
        long resolved = resolveAudioOverlap(audioClip.getOffsetMs(), audioClip);
        if (resolved != audioClip.getOffsetMs()) {
            audioClip.setOffsetMs(resolved);
        }
        audioClips.add(audioClip);
    }

    /**
     * Add an audio clip with optional overlap resolution. Pass {@code false}
     * for {@code resolveOverlap} when exact positioning must be preserved
     * (undo/restore).
     */
    public void addAudioClip(@NonNull AudioClip audioClip, boolean resolveOverlap) {
        if (resolveOverlap) {
            long resolved = resolveAudioOverlap(audioClip.getOffsetMs(), audioClip);
            if (resolved != audioClip.getOffsetMs()) {
                audioClip.setOffsetMs(resolved);
            }
        }
        audioClips.add(audioClip);
    }

    /**
     * Find the nearest non-overlapping offset for an audio clip on its
     * layer track. Uses the same block-coalescing algorithm as
     * LayerGestureController.nearestFreeStart. Always returns a legal
     * position (tail of the last sibling is always free).
     */
    private long resolveAudioOverlap(long desiredStartMs, @NonNull AudioClip self) {
        String selfLayer = self.getLayerId();
        long dur = Math.max(1, self.getTrimmedDurationMs());
        // Collect siblings on the same layer.
        List<AudioClip> sibs = new ArrayList<>();
        for (AudioClip ac : audioClips) {
            if (ac == self) continue;
            String layer = ac.getLayerId();
            if (selfLayer == null ? layer == null : selfLayer.equals(layer)) {
                sibs.add(ac);
            }
        }
        if (sibs.isEmpty()) return Math.max(0, desiredStartMs);

        // Build occupied blocks (coalescing touching/overlapping siblings).
        int n = sibs.size();
        long[] startBuf = new long[n];
        long[] endBuf = new long[n];
        for (int i = 0; i < n; i++) {
            AudioClip sib = sibs.get(i);
            long ss = sib.getOffsetMs();
            long se = ss + Math.max(1, sib.getTrimmedDurationMs());
            // Insertion sort by start.
            int j = i;
            while (j > 0 && startBuf[j - 1] > ss) {
                startBuf[j] = startBuf[j - 1];
                endBuf[j] = endBuf[j - 1];
                j--;
            }
            startBuf[j] = ss;
            endBuf[j] = se;
        }
        // Merge touching/overlapping blocks.
        int m = 0;
        for (int i = 1; i < n; i++) {
            if (startBuf[i] <= endBuf[m]) {
                endBuf[m] = Math.max(endBuf[m], endBuf[i]);
            } else {
                m++;
                startBuf[m] = startBuf[i];
                endBuf[m] = endBuf[i];
            }
        }
        int blockCount = m + 1;

        long bestStart = Long.MIN_VALUE, bestDist = Long.MAX_VALUE;
        // (a) Before the first block.
        long firstStart = startBuf[0];
        if (firstStart - dur >= 0) {
            long hi = firstStart - dur;
            long cand = Math.max(0, Math.min(desiredStartMs, hi));
            long dist = Math.abs(cand - desiredStartMs);
            if (dist < bestDist) { bestDist = dist; bestStart = cand; }
        }
        // (b) Between consecutive blocks.
        for (int i = 0; i + 1 < blockCount; i++) {
            long gapLo = endBuf[i];
            long gapHi = startBuf[i + 1] - dur;
            if (gapHi >= gapLo) {
                long cand = Math.max(gapLo, Math.min(desiredStartMs, gapHi));
                long dist = Math.abs(cand - desiredStartMs);
                if (dist < bestDist) { bestDist = dist; bestStart = cand; }
            }
        }
        // (c) After the last block — always feasible.
        long tailLo = endBuf[blockCount - 1];
        long cand = Math.max(tailLo, desiredStartMs);
        long dist = Math.abs(cand - desiredStartMs);
        if (dist < bestDist) { bestStart = cand; }
        return Math.max(0, bestStart);
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

    /**
     * T8: the stable, per-item sprite LANE id — one lane per placed sprite (FEEDBACK
     * _20260706 #2 "every item its own lane"). DETERMINISTIC (derived from the item id,
     * itself a UUID): re-deriving always yields the same id, so {@link #migrateSpriteLayers}
     * is idempotent and a sprite keeps its lane even across an UNSAVED session. There is no
     * {@code LayerTrackDef} — {@link #getLayers()}'s defensive leftover-bucket branch already
     * surfaces any non-default sprite {@code layerId} as its own {@code buildSpriteTrack}
     * lane, and {@code TrackFlags} (hide/lock/rename) key off the id directly.
     */
    @NonNull
    public static String spriteLayerIdFor(
            @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item) {
        return "sprite-" + item.getId();
    }

    /**
     * T8 migration: split legacy sprite overlays that share ONE timeline lane. Before T8
     * every placed sprite left {@code layerId == null}, so {@link #getLayers()} bucketed
     * them ALL into the single default {@code "sprite"} track where they visually
     * overlapped. For any lane that holds two or more sprites, this gives every sprite
     * PAST THE FIRST its own {@link #spriteLayerIdFor} lane, so N sprites become N lanes.
     * Idempotent: a no-op once every bucket holds at most one sprite (so it is safe to run
     * on every load, including undo/redo snapshot restores). Returns how many sprites were
     * moved to a fresh lane (0 = nothing to do).
     */
    public int migrateSpriteLayers() {
        Map<String, List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem>> byLayer =
                new LinkedHashMap<>();
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem so : spriteOverlays) {
            String id = so.getLayerId() != null ? so.getLayerId() : "sprite";
            byLayer.computeIfAbsent(id, k -> new ArrayList<>()).add(so);
        }
        int moved = 0;
        for (List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> bucket : byLayer.values()) {
            // Keep the first sprite on its current lane; move the rest to their own.
            for (int i = 1; i < bucket.size(); i++) {
                bucket.get(i).setLayerId(spriteLayerIdFor(bucket.get(i)));
                moved++;
            }
        }
        return moved;
    }

    /**
     * Slice F — enforce the no-overlap invariant on TEXT overlay lanes (FEEDBACK #2: "it must be
     * IMPOSSIBLE for two items to overlap on one lane"). {@link #getLayers()} groups text overlays by
     * {@code layerId}; items sharing a lane could OVERLAP in time. For each lane that holds
     * overlapping items, this packs them into the FEWEST no-overlap sub-lanes: in start order, each
     * item lands on the first sub-lane whose previous item has already ended, else a fresh sub-lane is
     * minted. Sub-lane 0 keeps the original lane; overflow items get a deterministic {@code "text-<id>"}
     * lane, so the result is IDEMPOTENT (safe on every load / undo-redo restore — a lane that already
     * holds ≤1 item, or whose items merely butt/gap, is untouched). This ENFORCES the invariant only;
     * it does NOT merge deliberately-separated non-overlapping lanes — that is the manual "compact
     * lanes" action. Returns how many items were moved to a fresh lane (0 = nothing to fix).
     */
    public int enforceNoOverlapTextLanes() {
        Map<String, List<TextOverlayItem>> byLayer = new LinkedHashMap<>();
        for (TextOverlayItem o : textOverlays) {
            String id = o.getLayerId() != null ? o.getLayerId() : "text";
            byLayer.computeIfAbsent(id, k -> new ArrayList<>()).add(o);
        }
        int moved = 0;
        for (List<TextOverlayItem> lane : byLayer.values()) {
            if (lane.size() < 2) continue;
            List<TextOverlayItem> sorted = new ArrayList<>(lane);
            sorted.sort((a, b) -> Long.compare(a.getStartMs(), b.getStartMs()));
            List<Long> subLaneEnd = new ArrayList<>(); // last end (ms) per sub-lane
            for (TextOverlayItem o : sorted) {
                long s = o.getStartMs();
                long e = textEndForPacking(o);
                int placed = -1;
                for (int k = 0; k < subLaneEnd.size(); k++) {
                    if (subLaneEnd.get(k) <= s) { placed = k; break; }
                }
                if (placed < 0) { placed = subLaneEnd.size(); subLaneEnd.add(e); }
                else subLaneEnd.set(placed, e);
                if (placed > 0) {
                    // Overflow → its own deterministic lane (idempotent across loads).
                    String want = "text-" + o.getId();
                    if (!want.equals(o.getLayerId())) { o.setLayerId(want); moved++; }
                }
                // placed == 0 keeps its original lane id (may be the default/null bucket).
            }
        }
        return moved;
    }

    /** End (ms) used to detect text-lane overlaps: an open-ended overlay occupies its lane forever. */
    private static long textEndForPacking(@NonNull TextOverlayItem o) {
        long e = o.getEndMs();
        return (e == Long.MAX_VALUE || e <= o.getStartMs()) ? Long.MAX_VALUE : e;
    }

    /**
     * Slice F — enforce the no-overlap invariant on PiP / video-overlay lanes ({@code overlayClips}),
     * the last item type after text (F1) / sprite (T8) / audio. {@link #getLayers()} groups overlay
     * clips by {@code layerId} into the default {@code "video"} lane + defs + leftover buckets; two PiPs
     * sharing a lane could OVERLAP in time. For each lane holding overlapping clips this packs them into
     * the FEWEST no-overlap sub-lanes: in start order, each clip lands on the first sub-lane whose
     * previous clip has already ended, else a fresh sub-lane is minted. Sub-lane 0 keeps the original
     * lane; overflow clips get a deterministic {@code "video-<id>"} lane, so the result is IDEMPOTENT
     * (safe on every load / undo-redo restore — a lane with ≤1 clip, or clips that merely butt/gap, is
     * untouched). ENFORCES the invariant only; it does NOT merge deliberately-separated lanes — that is
     * the manual "compact lanes" action. Returns how many clips were moved to a fresh lane (0 = clean).
     */
    public int enforceNoOverlapVideoLanes() {
        Map<String, List<Clip>> byLayer = new LinkedHashMap<>();
        for (Clip oc : overlayClips) {
            String id = oc.getLayerId() != null ? oc.getLayerId() : "video";
            byLayer.computeIfAbsent(id, k -> new ArrayList<>()).add(oc);
        }
        int moved = 0;
        for (List<Clip> lane : byLayer.values()) {
            if (lane.size() < 2) continue;
            List<Clip> sorted = new ArrayList<>(lane);
            sorted.sort((a, b) -> Long.compare(a.getOverlayStartMs(), b.getOverlayStartMs()));
            List<Long> subLaneEnd = new ArrayList<>(); // last end (ms) per sub-lane
            for (Clip oc : sorted) {
                long s = oc.getOverlayStartMs();
                long e = videoEndForPacking(oc);
                int placed = -1;
                for (int k = 0; k < subLaneEnd.size(); k++) {
                    if (subLaneEnd.get(k) <= s) { placed = k; break; }
                }
                if (placed < 0) { placed = subLaneEnd.size(); subLaneEnd.add(e); }
                else subLaneEnd.set(placed, e);
                if (placed > 0) {
                    // Overflow → its own deterministic lane (idempotent across loads).
                    String want = "video-" + oc.getId();
                    if (!want.equals(oc.getLayerId())) { oc.setLayerId(want); moved++; }
                }
                // placed == 0 keeps its original lane id (may be the default "video"/null bucket).
            }
        }
        return moved;
    }

    /** Timeline end (ms) of a PiP/video overlay = its start + displayed (loop-aware) duration. Always
     * bounded (a PiP has a finite window), so — unlike text — there is no Long.MAX_VALUE open-end case. */
    private static long videoEndForPacking(@NonNull Clip oc) {
        long dur = oc.hasLoopExtension() ? oc.getVisualDurationMs() : oc.getTrimmedDurationMs();
        return oc.getOverlayStartMs() + Math.max(0, dur);
    }

    /**
     * Slice F — "compact lanes" (JoyRaptor's CapCut orphan-lane pain): drop every overlay of a kind into
     * the FEWEST no-overlap lanes. Unlike {@link #enforceNoOverlapTextLanes()} (which only SPLITS
     * overlaps within one lane), this MERGES across lanes — gather all items of a type, greedily pack
     * them in start order onto the first lane whose previous item has already ended, minting a new lane
     * ONLY when a real time-overlap forces it. Lane 0 keeps the default bucket; extra lanes get a
     * deterministic {@code "<prefix>-<firstItemId>"}. A MANUAL, undoable action (never run
     * automatically). Also collapses the T8 one-sprite-per-lane sprawl. Returns how many items changed
     * lane (0 = already minimal).
     */
    public int compactOverlayLanes() {
        return compactTextLanes() + compactSpriteLanes();
    }

    private int compactTextLanes() {
        if (textOverlays.size() < 2) return 0;
        List<TextOverlayItem> sorted = new ArrayList<>(textOverlays);
        sorted.sort((a, b) -> Long.compare(a.getStartMs(), b.getStartMs()));
        List<Long> laneEnd = new ArrayList<>();
        List<String> laneId = new ArrayList<>();
        int changed = 0;
        for (TextOverlayItem o : sorted) {
            long s = o.getStartMs(), e = textEndForPacking(o);
            int placed = -1;
            for (int k = 0; k < laneEnd.size(); k++) {
                if (laneEnd.get(k) <= s) { placed = k; break; }
            }
            if (placed < 0) {
                placed = laneEnd.size();
                laneEnd.add(e);
                laneId.add(placed == 0 ? null : ("text-" + o.getId()));
            } else {
                laneEnd.set(placed, e);
            }
            String want = laneId.get(placed);
            if (!laneIdEquals(o.getLayerId(), want)) { o.setLayerId(want); changed++; }
        }
        return changed;
    }

    private int compactSpriteLanes() {
        if (spriteOverlays.size() < 2) return 0;
        List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> sorted = new ArrayList<>(spriteOverlays);
        sorted.sort((a, b) -> Long.compare(a.getStartMs(), b.getStartMs()));
        List<Long> laneEnd = new ArrayList<>();
        List<String> laneId = new ArrayList<>();
        int changed = 0;
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem o : sorted) {
            long s = o.getStartMs(), endMs = o.getEndMs();
            long e = (endMs == Long.MAX_VALUE || endMs <= s) ? Long.MAX_VALUE : endMs;
            int placed = -1;
            for (int k = 0; k < laneEnd.size(); k++) {
                if (laneEnd.get(k) <= s) { placed = k; break; }
            }
            if (placed < 0) {
                placed = laneEnd.size();
                laneEnd.add(e);
                laneId.add(placed == 0 ? null : spriteLayerIdFor(o));
            } else {
                laneEnd.set(placed, e);
            }
            String want = laneId.get(placed);
            if (!laneIdEquals(o.getLayerId(), want)) { o.setLayerId(want); changed++; }
        }
        return changed;
    }

    /** Null-safe layer-id compare (a null id == the default lane bucket). */
    private static boolean laneIdEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
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
        t.durationMs = Transition.clampDurationMs(durationMs);
    }

    public boolean setTransitionDuration(int index, long durationMs) {
        if (index < 0 || index >= transitions.size()) return false;
        // Export already seam-clamps a transition longer than its straddled clips
        // (effectiveTransitionMs), so the generous ceiling is safe.
        transitions.get(index).durationMs = Transition.clampDurationMs(durationMs);
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

    /**
     * Deep snapshot of the transition list, for an undo step that is about to run one of the
     * index-shifting helpers ({@link #removeTransitionsForDeletedClip} and friends).
     *
     * <p>Those helpers are DESTRUCTIVE in two ways at once: they drop the transitions adjacent
     * to the edited seam AND renumber every later {@code clipIndex} in place. Re-inserting the
     * clip does not undo either — so without snapshot/restore, undoing a delete leaves every
     * later transition attached to the WRONG seam, which is silent corruption of the user's
     * edit rather than mere data loss.</p>
     */
    @NonNull
    public List<Transition> snapshotTransitions() {
        List<Transition> copy = new ArrayList<>(transitions.size());
        for (Transition t : transitions) copy.add(t.copy());
        return copy;
    }

    /** Restore a {@link #snapshotTransitions()} result, replacing the current list. */
    public void restoreTransitions(@NonNull List<Transition> snapshot) {
        transitions.clear();
        for (Transition t : snapshot) transitions.add(t.copy());
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
        liveIds.add("video");  // default overlay-video track (M-COMP-2) — same bug class
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
        for (Clip oc : overlayClips) {
            if (oc.getLayerId() != null) liveIds.add(oc.getLayerId());
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
        // PHASE-P P1: rename home for the default "text"/"audio"/"sprite" tracks —
        // they have no LayerTrackDef to carry a name, so a user rename persists here.
        if (flags.customName != null && !flags.customName.isEmpty()) {
            track.setName(flags.customName);
        }
    }

    /**
     * PHASE-P P2: order a freshly-built band of Track views so the row RENDER order
     * follows the persisted zIndex. STABLE sort, DESCENDING zIndex: the top row (drawn
     * first by LayerRowRenderer) is the HIGHEST z — matching the preview/export paint
     * authority ({@code LayerPreviewController.visibleTextOverlays} stable-sorts
     * ASCENDING and draws later-elements on top, so highest z paints last/on top there
     * too). Every zIndex defaults to 0, so a project that never used Move up/down keeps
     * the exact builder order (stable sort = no-op) — byte-identical behavior.
     */
    private static void sortBandByZIndex(@NonNull List<Track> band) {
        band.sort((a, b) -> Integer.compare(b.getZIndex(), a.getZIndex()));
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
     * track-membership; NEUTRAL SUBSTRATE — see {@code tasks/SPEC_NEUTRAL_SUBSTRATE.md}).
     *
     * <p><b>Routing is layerId-FIRST.</b> A row is the set of visual items — text/sticker,
     * sprite, and overlay video/image alike — that share one {@code layerId}, regardless
     * of which backing list holds them. Every floating row is therefore a neutral lane
     * that can hold anything; a {@link TrackKind} survives only as the row's label and to
     * decide where in the band its lane is emitted, so an existing project's rows come out
     * in exactly the order they always did. (Audio is the one genuinely separate band —
     * it is never visually composited — and lives in {@link #getAudioTracks()}.)</p>
     *
     * <p>Three lane ids are SEEDED so items that never chose a lane still have a home:
     * {@code "text"} (also a {@code null} text layerId), {@code "sprite"} (null sprite
     * layerId) and {@code "video"} (an overlay clip's layerId is NEVER null — null means
     * master-clip). Each is emitted when ANY payload type has items for it, then the
     * user-created {@link #extraLayerTracks} definitions in their own order (emitted even
     * when empty, so a freshly-created lane survives save/reload), then any orphan id.
     * Freshly rebuilt on every call; persisted flags (M6) are re-applied per track.</p>
     */
    @NonNull
    public List<Track> getLayers() {
        // NEUTRAL SUBSTRATE (SPEC_NEUTRAL_SUBSTRATE): EVERY floating row is a lane that
        // holds ANY visual payload. Routing is layerId-FIRST — a row is the set of items
        // (text/sticker, sprite, overlay video/image) sharing one layerId, whatever their
        // backing list. A Track's kind no longer gates membership; it survives only as the
        // row's label/affordance hint and to decide EMISSION ORDER below, so an existing
        // project's rows come out in exactly the order they always did.
        //
        // Grouping maps, each built in its backing list's own order so a lane's items of
        // one type keep insertion order (which IS their per-type z — see
        // LayerPreviewController; cross-type paint order is the global surface stack).
        Map<String, List<TextOverlayItem>> textsByLayer = new LinkedHashMap<>();
        for (TextOverlayItem overlay : textOverlays) {
            String id = overlay.getLayerId() != null ? overlay.getLayerId() : "text";
            textsByLayer.computeIfAbsent(id, k -> new ArrayList<>()).add(overlay);
        }
        Map<String, List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem>> spritesByLayer =
                new LinkedHashMap<>();
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem so : spriteOverlays) {
            String id = so.getLayerId() != null ? so.getLayerId() : "sprite";
            spritesByLayer.computeIfAbsent(id, k -> new ArrayList<>()).add(so);
        }
        Map<String, List<Clip>> videosByLayer = new LinkedHashMap<>();
        for (Clip oc : overlayClips) {
            String id = oc.getLayerId() != null ? oc.getLayerId() : "video";
            videosByLayer.computeIfAbsent(id, k -> new ArrayList<>()).add(oc);
        }
        LaneBuckets buckets = new LaneBuckets(textsByLayer, spritesByLayer, videosByLayer);
        // Ids that a DEF owns. The per-phase leftover flushes below must skip these or a
        // def whose id happens to sit in an earlier phase's map would be pre-empted: the
        // flush would emit its items under a leftover name and the def would then emit an
        // EMPTY lane. (Reachable today: a LAYER def holding text is in the text map, and
        // the text flush runs before emitDefs(LAYER).)
        java.util.Set<String> defIds = new java.util.HashSet<>();
        for (LayerTrackDef def : extraLayerTracks) defIds.add(def.getId());

        List<Track> layers = new ArrayList<>();
        // Emission order below is EXACTLY the historical band order — seeded lane, its
        // defs, then its leftovers, per payload phase. Orphan ids (a layerId with items
        // but no def — real projects have them, e.g. legacy "sprite-<uuid>" ids) therefore
        // keep both their historical row POSITION and their type-derived name; only their
        // membership is now merged. Each seeded lane is emitted when ANY payload type has
        // items for its id, so a sprite dropped on the "text" lane keeps that lane alive
        // even after its last text leaves.
        if (buckets.hasItems("text")) {
            layers.add(buildLaneTrack("text", TrackKind.TEXT, "Text", buckets));
        }
        emitDefs(layers, buckets, TrackKind.TEXT, TrackKind.STICKER);
        flushLeftovers(layers, buckets, buckets.texts, defIds, TrackKind.TEXT, "Text");

        if (buckets.hasItems("sprite")) {
            layers.add(buildLaneTrack("sprite", TrackKind.SPRITE, "Sprite", buckets));
        }
        emitDefs(layers, buckets, TrackKind.SPRITE, null);
        flushLeftovers(layers, buckets, buckets.sprites, defIds, TrackKind.SPRITE, "Sprite");

        if (buckets.hasItems("video")) {
            layers.add(buildLaneTrack("video", TrackKind.VIDEO, "PiP", buckets));
        }
        emitDefs(layers, buckets, TrackKind.VIDEO, TrackKind.IMAGE);
        flushLeftovers(layers, buckets, buckets.videos, defIds, TrackKind.VIDEO, "PiP");

        emitDefs(layers, buckets, TrackKind.LAYER, null);
        // Belt-and-braces: an id owned by a def of a kind no phase emits (CAPTION/
        // VISUALIZER/MASTER — nothing creates those defs today) would have been skipped by
        // every flush above, so surface it rather than silently dropping its items. Empty
        // in every reachable case.
        for (String orphanId : buckets.remainingIds()) {
            layers.add(buildLaneTrack(orphanId, TrackKind.LAYER, "Layer", buckets));
        }

        sortBandByZIndex(layers); // PHASE-P P2: row order follows persisted zIndex
        return layers;
    }

    /**
     * The three layerId→items groupings {@link #getLayers()} routes from, bundled so a
     * lane can be built from ALL of them by id (neutral substrate: membership is the
     * layerId, never the payload's backing list). Consuming a lane REMOVES its id from
     * every map, so each item lands in exactly one row and whatever is left over at the
     * end is by definition an orphan id.
     */
    private static final class LaneBuckets {
        final Map<String, List<TextOverlayItem>> texts;
        final Map<String, List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem>> sprites;
        final Map<String, List<Clip>> videos;

        LaneBuckets(@NonNull Map<String, List<TextOverlayItem>> texts,
                @NonNull Map<String, List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem>> sprites,
                @NonNull Map<String, List<Clip>> videos) {
            this.texts = texts;
            this.sprites = sprites;
            this.videos = videos;
        }

        /** True if ANY payload type has items for {@code id}. */
        boolean hasItems(@NonNull String id) {
            return texts.containsKey(id) || sprites.containsKey(id) || videos.containsKey(id);
        }

        /** Every id still unconsumed, in text→sprite→video discovery order, deduped. */
        @NonNull
        List<String> remainingIds() {
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(texts.keySet());
            ids.addAll(sprites.keySet());
            ids.addAll(videos.keySet());
            return new ArrayList<>(ids);
        }
    }

    /**
     * Emit one Track per {@link LayerTrackDef} of the given kind(s), in
     * {@link #extraLayerTracks} order — kind decides only WHERE a lane appears in the
     * band (preserving the historical row order), never what it may hold. Emitted even
     * when empty so a freshly-created lane survives save/reload.
     */
    private void emitDefs(@NonNull List<Track> out, @NonNull LaneBuckets buckets,
            @NonNull TrackKind kind, @Nullable TrackKind alsoKind) {
        for (LayerTrackDef def : extraLayerTracks) {
            if (def.getKind() != kind && (alsoKind == null || def.getKind() != alsoKind)) continue;
            out.add(buildLaneTrack(def.getId(), def.getKind(), def.getName(), buckets));
        }
    }

    /**
     * Emit a lane for every id still left in {@code phaseMap} that no def owns — the
     * defensive path for a layerId whose def is missing (legacy data). Iterates a SNAPSHOT
     * of the keys because {@link #buildLaneTrack} removes from this very map (and from the
     * other two, so an orphan id holding several payload types still becomes ONE lane).
     */
    private void flushLeftovers(@NonNull List<Track> out, @NonNull LaneBuckets buckets,
            @NonNull Map<String, ?> phaseMap, @NonNull java.util.Set<String> defIds,
            @NonNull TrackKind kind, @NonNull String name) {
        for (String id : new ArrayList<>(phaseMap.keySet())) {
            if (defIds.contains(id)) continue; // its def emits it later, with its own name
            out.add(buildLaneTrack(id, kind, name, buckets));
        }
    }

    /**
     * Build ONE lane: every visual payload sharing {@code id}, whatever its backing list
     * (neutral substrate). Item order groups by type, which is sufficient because
     * cross-type paint order is the fixed global surface stack (overlay video under
     * sprite under text — see {@code LayerPreviewController} and the layout's overlay
     * stack); only per-type order carries z meaning inside a lane, and each type keeps
     * its backing-list order here.
     */
    @NonNull
    private Track buildLaneTrack(@NonNull String id, @NonNull TrackKind kind,
            @NonNull String name, @NonNull LaneBuckets buckets) {
        Track track = new Track(id, kind, name);
        List<TextOverlayItem> tb = buckets.texts.remove(id);
        if (tb != null) {
            for (TextOverlayItem overlay : tb) track.addItem(TimedItem.ofTextOverlay(overlay));
        }
        List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> sb = buckets.sprites.remove(id);
        if (sb != null) {
            for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem so : sb) {
                track.addItem(TimedItem.ofSprite(so));
            }
        }
        List<Clip> vb = buckets.videos.remove(id);
        if (vb != null) {
            for (Clip oc : vb) track.addItem(videoTimedItem(oc));
        }
        applyTrackFlags(track);
        return track;
    }

    /**
     * The TimedItem view of one overlay (PiP) clip — mirrors the clip's PERSISTED
     * overlay fields: {@code overlayStartMs} becomes the item start,
     * {@code overlayTransform} the item transform, and {@code overlayBlendMode} the item
     * blend, so preview/export consumers read the exact state storage round-trips
     * (single-authority rule, M-COMP-2).
     */
    @NonNull
    private static TimedItem videoTimedItem(@NonNull Clip oc) {
        TimedItem item = TimedItem.ofClip(oc, oc.getOverlayStartMs());
        item.setTransform(oc.getOverlayTransform());
        item.setBlendMode(com.fadcam.ui.faditor.layers.BlendMode
                .fromName(oc.getOverlayBlendMode()));
        return item;
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
        sortBandByZIndex(tracks); // PHASE-P P2: row order follows persisted zIndex
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

    // ── Caption / visualizer band views (layers-UX Slice A, 2026-07-06) ──
    // Read-only VIEWS so captions & visualizers become headered {@link Track} rows in
    // LayerRowRenderer alongside every other item type (renderer consolidation). NOT wired into
    // setLayerTracks yet — Slice B feeds these into the band(s) and adds the render cases; here
    // they only exist so the getters are ready and correct. Existing UI is byte-for-byte unchanged.

    /**
     * Build the caption/CC row(s) — a read-only VIEW over clip-owned caption spans, matching the
     * old {@code FaditorEditorActivity#syncTimelineOverlays} capSpans computation exactly (one span
     * per clip that {@code isCaptionsEnabled() && hasTranscript()}; the last clip runs open-ended).
     * Captions stay {@code Clip}-owned — this Track is never a second source of truth. Returns a
     * single CAPTION track (all spans share one CC row, as the old renderer drew them), or an empty
     * list when no clip has captions.
     */
    @NonNull
    public List<Track> getCaptionTracks() {
        List<TimedItem> items = new ArrayList<>();
        int n = clips.size();
        for (int i = 0; i < n; i++) {
            Clip c = clips.get(i);
            if (c != null && c.isCaptionsEnabled() && c.hasTranscript()) {
                long start = segmentStartMs(i);
                long end = (i + 1 < n) ? segmentStartMs(i + 1) : Long.MAX_VALUE;
                items.add(TimedItem.ofCaptionSpan(
                        new com.fadcam.ui.faditor.layers.CaptionSpanRef(c, i, start, end)));
            }
        }
        if (items.isEmpty()) return Collections.emptyList();
        Track track = new Track("caption", TrackKind.CAPTION, "CC");
        for (TimedItem it : items) track.addItem(it);
        applyTrackFlags(track);
        return Collections.singletonList(track);
    }

    /**
     * Build the visualizer row(s) — one VISUALIZER track per placed {@link WaveformOverlayInstance},
     * mirroring the old renderer where each waveform occupied its own row. {@code WaveformOverlayInstance}
     * carries no {@code layerId}, so each instance keys its own nameless lane (id {@code "viz:"+id}).
     * Read-only view over the live instances (single-authority rule).
     */
    @NonNull
    public List<Track> getVisualizerTracks() {
        List<Track> tracks = new ArrayList<>();
        for (WaveformOverlayInstance wf : waveformOverlays) {
            Track track = new Track("viz:" + wf.getId(), TrackKind.VISUALIZER, "VIZ");
            track.addItem(TimedItem.ofWaveform(wf));
            applyTrackFlags(track);
            tracks.add(track);
        }
        return tracks;
    }

    /**
     * Absolute start (ms) of the master clip at {@code index} — the gapless cumulative sum of prior
     * clips' on-timeline durations. Uses the same per-clip term as {@link #getTotalDurationMs()}
     * (loop-extended → visual duration, else trimmed duration), so caption spans line up exactly
     * with the master tape. Mirrors {@code EditorTimelineView#getSegmentStartTime}.
     */
    private long segmentStartMs(int index) {
        long t = 0;
        int upto = Math.min(index, clips.size());
        for (int i = 0; i < upto; i++) {
            Clip c = clips.get(i);
            t += c.hasLoopExtension() ? c.getVisualDurationMs() : c.getTrimmedDurationMs();
        }
        return t;
    }

    // ── G5 attach/detach (gesture contract §4): visualizer ↔ master-clip tether ──────

    /** On-timeline span length of a master clip (loop-extended → visual, else trimmed). */
    private static long clipSpanMs(@NonNull Clip c) {
        return c.hasLoopExtension() ? c.getVisualDurationMs() : c.getTrimmedDurationMs();
    }

    private int indexOfMasterClipId(@Nullable String clipId) {
        if (clipId == null) return -1;
        for (int i = 0; i < clips.size(); i++) {
            if (clipId.equals(clips.get(i).getId())) return i;
        }
        return -1;
    }

    // ── Dual-stream linked pairs (feature-dual-stream-recording-spec §3/§6) ──

    /**
     * Look up a clip by id across BOTH lanes — master tape clips and overlay/PiP
     * clips. A dual-stream pair links a master (screen) clip to an overlay (webcam)
     * clip, so the link resolver must see both lists; searching only {@code clips}
     * would make {@link #findLinkedClip} blind to a webcam partner living in
     * {@code overlayClips}. Returns null if the id is on neither lane.
     */
    @Nullable
    public Clip findClipById(@NonNull String id) {
        for (Clip c : clips) {
            if (id.equals(c.getId())) return c;
        }
        for (Clip c : overlayClips) {
            if (id.equals(c.getId())) return c;
        }
        return null;
    }

    /**
     * The dual-stream partner of {@code clip} (the master clip whose id equals
     * {@code clip.getLinkedClipId()}), or null when unlinked / the partner is gone.
     */
    @Nullable
    public Clip findLinkedClip(@NonNull Clip clip) {
        String partnerId = clip.getLinkedClipId();
        return partnerId == null ? null : findClipById(partnerId);
    }

    /** Index of {@code clip} in the master lane, or -1 if it isn't a master clip. */
    public int indexOfClip(@NonNull Clip clip) {
        return clips.indexOf(clip);
    }

    /** Link two clips as a synced pair — symmetric, so either can find the other. */
    public static void linkClips(@NonNull Clip a, @NonNull Clip b) {
        a.setLinkedClipId(b.getId());
        b.setLinkedClipId(a.getId());
    }

    /** Break {@code clip}'s link on BOTH sides (idempotent; safe if the partner is gone). */
    public void unlinkClip(@NonNull Clip clip) {
        Clip partner = findLinkedClip(clip);
        clip.setLinkedClipId(null);
        if (partner != null) partner.setLinkedClipId(null);
    }

    /**
     * Re-derive every ATTACHED visualizer's absolute {@code [startMs,endMs]} window from its
     * host master clip's CURRENT on-timeline span — the whole of G5 time-riding in one write
     * point. Call after any timeline mutation (the activity funnels through
     * {@code syncTimelineOverlays()}) and before export builds its overlay slots. Detached
     * instances are untouched. A host that no longer exists auto-detaches its rider in place
     * (the window keeps its last absolute values — nothing jumps).
     */
    /**
     * G5(b) piggyback-looks: the master clip with {@code clipId} and its absolute
     * timeline start, for consumers that mirror a HOST clip's look (opacity) onto an
     * attached rider. Returns null when the id is absent (rider treats host opacity
     * as 1 — never hides content over a stale id).
     */
    @Nullable
    public long[] masterClipWindowMs(@Nullable String clipId) {
        if (clipId == null) return null;
        int idx = indexOfMasterClipId(clipId);
        if (idx < 0) return null;
        return new long[]{segmentStartMs(idx), Math.max(1, clipSpanMs(clips.get(idx)))};
    }

    /** The master clip with {@code clipId}, or null. */
    @Nullable
    public Clip masterClipById(@Nullable String clipId) {
        if (clipId == null) return null;
        int idx = indexOfMasterClipId(clipId);
        return idx < 0 ? null : clips.get(idx);
    }

    public void resyncAttachedVisualizers() {
        for (WaveformOverlayInstance w : waveformOverlays) {
            String hostId = w.getAttachedClipId();
            if (hostId == null) continue;
            int idx = indexOfMasterClipId(hostId);
            if (idx < 0) {
                w.setAttachedClipId(null); // host deleted → detach in place
                continue;
            }
            long hostStart = segmentStartMs(idx);
            long hostSpan = Math.max(1, clipSpanMs(clips.get(idx)));
            long off = Math.min(w.getAttachOffsetMs(), hostSpan - 1);
            long start = hostStart + off;
            long end = w.getAttachDurationMs() == Long.MAX_VALUE
                    ? hostStart + hostSpan
                    : Math.min(start + w.getAttachDurationMs(), hostStart + hostSpan);
            w.setTimeRange(start, end);
        }
    }

    /**
     * Attach {@code w} to the master clip under its current start (clamped into the timeline).
     * Captures the host-relative offset/duration from the CURRENT absolute window, and — per the
     * contract's Axis-1 semantics (attached ⇒ content comes from the host) — points the
     * visualizer's audio source at the host clip. Returns the host clip, or null if the
     * timeline has no clips (attach impossible; state unchanged).
     */
    @Nullable
    public Clip attachVisualizerToHostUnderStart(@NonNull WaveformOverlayInstance w) {
        if (clips.isEmpty()) return null;
        int idx = clips.size() - 1;
        for (int i = 0; i < clips.size(); i++) {
            long s = segmentStartMs(i);
            if (w.getStartMs() >= s && w.getStartMs() < s + clipSpanMs(clips.get(i))) {
                idx = i;
                break;
            }
        }
        Clip host = clips.get(idx);
        long hostStart = segmentStartMs(idx);
        long hostSpan = Math.max(1, clipSpanMs(host));
        long off = Math.max(0, Math.min(w.getStartMs() - hostStart, hostSpan - 1));
        w.setAttachedClipId(host.getId());
        w.setAttachOffsetMs(off);
        w.setAttachDurationMs(w.getEndMs() == Long.MAX_VALUE
                ? Long.MAX_VALUE : Math.max(1, w.getEndMs() - w.getStartMs()));
        w.setAudioSourceRef(host.getId());
        resyncAttachedVisualizers();
        return host;
    }

    /**
     * Detach {@code w}: its window keeps the last host-derived absolute values (nothing jumps);
     * the audio source stays pointed at the former host (the explicit "pin to this source"
     * behavior — a true full-mix source is a separate feature, see contract §4.1).
     */
    public void detachVisualizer(@NonNull WaveformOverlayInstance w) {
        resyncAttachedVisualizers();
        w.setAttachedClipId(null);
    }

    // ── G9 object linking (gesture contract §5.6, PLAN_G9_LINK_ENGINE.md) ────────────

    /** Persisted ad-hoc link groups (tolerant storage: absent = unlinked, every pre-G9 project). */
    private final List<com.fadcam.ui.faditor.layers.LinkGroup> linkGroups = new ArrayList<>();
    /** TRANSIENT G5-preset groups synthesized per attached visualizer post-load (§4.1) —
     *  a separate list so the JSON writer never sees them; rebuilt by
     *  {@link #synthesizeG5PresetLinkGroups()}. Ground truth stays the attach fields. */
    private final transient List<com.fadcam.ui.faditor.layers.LinkGroup> syntheticPresetGroups =
            new ArrayList<>();

    /** The PERSISTED ad-hoc groups only (what ProjectStorage serializes). */
    @NonNull
    public List<com.fadcam.ui.faditor.layers.LinkGroup> getLinkGroups() {
        return linkGroups;
    }

    /** Persisted + synthesized-preset groups — the view UI/link-badges should consume. */
    @NonNull
    public List<com.fadcam.ui.faditor.layers.LinkGroup> getAllLinkGroupsView() {
        List<com.fadcam.ui.faditor.layers.LinkGroup> all = new ArrayList<>(linkGroups);
        all.addAll(syntheticPresetGroups);
        return all;
    }

    public void addLinkGroup(@NonNull com.fadcam.ui.faditor.layers.LinkGroup group) {
        linkGroups.add(group);
    }

    @Nullable
    public com.fadcam.ui.faditor.layers.LinkGroup removeLinkGroup(@NonNull String groupId) {
        for (int i = 0; i < linkGroups.size(); i++) {
            if (linkGroups.get(i).id.equals(groupId)) return linkGroups.remove(i);
        }
        return null;
    }

    /** Every group (persisted or preset) containing {@code itemId} — drives badges/dialogs. */
    @NonNull
    public List<com.fadcam.ui.faditor.layers.LinkGroup> getLinkGroupsForItem(@NonNull String itemId) {
        List<com.fadcam.ui.faditor.layers.LinkGroup> hits = new ArrayList<>();
        for (com.fadcam.ui.faditor.layers.LinkGroup g : getAllLinkGroupsView()) {
            if (g.findMember(itemId) != null) hits.add(g);
        }
        return hits;
    }

    /** True iff a payload with this (kind, id) is live on the timeline right now. */
    private boolean linkPayloadExists(@NonNull String kind, @NonNull String id) {
        return resolveLinkStartMs(kind, id) != Long.MIN_VALUE;
    }

    /**
     * Absolute timeline start (ms) of a linkable payload, or {@link Long#MIN_VALUE} when the
     * (kind, id) doesn't resolve to a live object. Master clips resolve via
     * {@link #segmentStartMs(int)} — the same authority captions/G5 use.
     */
    public long resolveLinkStartMs(@NonNull String kind, @NonNull String id) {
        switch (kind) {
            case "clip": {
                int idx = indexOfMasterClipId(id);
                if (idx >= 0) return segmentStartMs(idx);
                for (Clip oc : overlayClips) {
                    if (id.equals(oc.getId())) return oc.getOverlayStartMs();
                }
                return Long.MIN_VALUE;
            }
            case "textOverlay":
                for (TextOverlayItem t : textOverlays) {
                    if (id.equals(t.getId())) return t.getStartMs();
                }
                return Long.MIN_VALUE;
            case "audioClip":
                for (AudioClip a : audioClips) {
                    if (id.equals(a.getId())) return a.getOffsetMs();
                }
                return Long.MIN_VALUE;
            case "sprite":
                for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteOverlays) {
                    if (id.equals(s.getId())) return s.getStartMs();
                }
                return Long.MIN_VALUE;
            case "waveform":
                for (WaveformOverlayInstance w : waveformOverlays) {
                    if (id.equals(w.getId())) return w.getStartMs();
                }
                return Long.MIN_VALUE;
            default:
                return Long.MIN_VALUE;
        }
    }

    /**
     * Move a linkable RIDER payload's absolute start to {@code startMs}, preserving its duration
     * (v1 TIME-links are MOVE-only — trim never propagates, plan §3 lean (b)). Master clips are
     * never riders (their position is the cumulative tape) — silently ignored.
     */
    private void applyLinkStartMs(@NonNull String kind, @NonNull String id, long startMs) {
        long start = Math.max(0, startMs);
        switch (kind) {
            case "clip":
                for (Clip oc : overlayClips) {
                    if (id.equals(oc.getId())) { oc.setOverlayStartMs(start); return; }
                }
                return;
            case "textOverlay":
                for (TextOverlayItem t : textOverlays) {
                    if (id.equals(t.getId())) {
                        long end = t.getEndMs();
                        t.setTimeRange(start, end == Long.MAX_VALUE
                                ? Long.MAX_VALUE : start + Math.max(1, end - t.getStartMs()));
                        return;
                    }
                }
                return;
            case "audioClip":
                for (AudioClip a : audioClips) {
                    if (id.equals(a.getId())) { a.setOffsetMs(start); return; }
                }
                return;
            case "sprite":
                for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteOverlays) {
                    if (id.equals(s.getId())) {
                        long end = s.getEndMs();
                        s.setTimeRange(start, end == Long.MAX_VALUE
                                ? Long.MAX_VALUE : start + Math.max(1, end - s.getStartMs()));
                        return;
                    }
                }
                return;
            case "waveform":
                for (WaveformOverlayInstance w : waveformOverlays) {
                    if (id.equals(w.getId())) {
                        long end = w.getEndMs();
                        w.setTimeRange(start, end == Long.MAX_VALUE
                                ? Long.MAX_VALUE : start + Math.max(1, end - w.getStartMs()));
                        return;
                    }
                }
                //noinspection UnnecessaryReturnStatement
                return;
        }
    }

    /**
     * Drop dead members from every persisted group and dissolve groups left with fewer than two
     * — the same conservative lazy-prune philosophy the track-flag map uses. Never a hard failure.
     */
    public void pruneLinkGroups() {
        java.util.Iterator<com.fadcam.ui.faditor.layers.LinkGroup> it = linkGroups.iterator();
        while (it.hasNext()) {
            com.fadcam.ui.faditor.layers.LinkGroup g = it.next();
            g.members.removeIf(m -> !linkPayloadExists(m.kind, m.id));
            if (g.members.size() < 2) it.remove();
        }
    }

    /**
     * G9's one write-point, same call contract as {@link #resyncAttachedVisualizers()} (run after
     * every mutation via syncTimelineOverlays, at load, and before export):
     * <ul>
     *   <li>prunes dead membership;</li>
     *   <li>HOST/RIDER groups with TIME linked: re-derives each rider's absolute start from the
     *       host's CURRENT start + the rider's captured {@code hostOffsetMs} (pull — the exact
     *       G5 resync pattern generalized onto {@link com.fadcam.ui.faditor.layers.LinkMember});</li>
     *   <li>PEER groups: membership-validation no-op — peer propagation is push-based inside the
     *       gesture (plan §3), so there is nothing to pull here;</li>
     *   <li>PRESET groups are transient views over G5's own fields — their math stays in
     *       {@link #resyncAttachedVisualizers()}, which callers already run alongside this.</li>
     * </ul>
     */
    public void resyncLinkGroups() {
        pruneLinkGroups();
        for (com.fadcam.ui.faditor.layers.LinkGroup g : linkGroups) {
            // P1 multi-axis: peer groups resync EVERY axis they carry, independently —
            // an item can ride TIME in group A and OPACITY in group B simultaneously.
            if (g.getHost() == null
                    && g.properties.contains(com.fadcam.ui.faditor.layers.LinkedProperty.OPACITY)) {
                resyncPeerOpacityGroup(g);
            }
            if (!g.properties.contains(com.fadcam.ui.faditor.layers.LinkedProperty.TIME)) continue;
            com.fadcam.ui.faditor.layers.LinkMember host = g.getHost();
            if (host == null) {
                resyncPeerTimeGroup(g);
                continue;
            }
            long hostStart = resolveLinkStartMs(host.kind, host.id);
            if (hostStart == Long.MIN_VALUE) continue; // prune handles it next pass
            for (com.fadcam.ui.faditor.layers.LinkMember m : g.members) {
                if (m.isHost || m.hostOffsetMs == com.fadcam.ui.faditor.layers.LinkMember.UNSET) {
                    continue;
                }
                applyLinkStartMs(m.kind, m.id, hostStart + m.hostOffsetMs);
            }
        }
    }

    /**
     * G9e — peer TIME-link propagation, push-based but centralized HERE (not in gesture
     * code) via last-known-start tracking on each {@link com.fadcam.ui.faditor.layers.LinkMember}:
     * <ul>
     *   <li>this runs from {@code syncTimelineOverlays()} after EVERY mutation and per drag
     *       tick, so a linked item follows its partners live during a drag, after a drawer
     *       nudge, a butt-snap — any surface — with zero per-callsite wiring;</li>
     *   <li>UNDO IS ONE STEP FOR FREE: the existing per-item undo restores the dragged item,
     *       the next resync sees that as a move and walks the partners back (their unclamped
     *       {@code virtualStartMs} guarantees no drift through t=0 clamps);</li>
     *   <li>MOVE-only (JoyRaptor 2026-07-19 #1): a start change with a duration change is a trim —
     *       re-baseline, don't propagate;</li>
     *   <li>ambiguity is safe: zero or 2+ movers in one pass (project load, batch undo,
     *       simultaneous edits) → re-baseline everything, propagate nothing.</li>
     * </ul>
     */
    private void resyncPeerTimeGroup(@NonNull com.fadcam.ui.faditor.layers.LinkGroup g) {
        final long UNSET = com.fadcam.ui.faditor.layers.LinkMember.UNSET;
        int moverIdx = -1;
        int movedCount = 0;
        long delta = 0;
        boolean rebaselineOnly = false;
        for (int i = 0; i < g.members.size(); i++) {
            com.fadcam.ui.faditor.layers.LinkMember m = g.members.get(i);
            long s = resolveLinkStartMs(m.kind, m.id);
            if (s == Long.MIN_VALUE) continue; // dead id — prune next pass
            long d = resolveLinkDurationMs(m.kind, m.id);
            if (m.virtualStartMs == UNSET) {
                m.virtualStartMs = s;
                m.lastKnownDurMs = d;
                rebaselineOnly = true; // fresh baseline this pass — never propagate yet
                continue;
            }
            if (m.lastKnownDurMs != d) {
                // Trim (or any duration edit) — MOVE-only rule: absorb, don't propagate.
                m.virtualStartMs = s;
                m.lastKnownDurMs = d;
                rebaselineOnly = true;
                continue;
            }
            long knownClamped = Math.max(0, m.virtualStartMs);
            if (s != knownClamped) {
                movedCount++;
                moverIdx = i;
                delta = s - knownClamped;
            }
        }
        if (rebaselineOnly || movedCount != 1) {
            if (movedCount > 0) {
                // Ambiguous / mixed pass: accept reality as the new baseline.
                for (com.fadcam.ui.faditor.layers.LinkMember m : g.members) {
                    long s = resolveLinkStartMs(m.kind, m.id);
                    if (s == Long.MIN_VALUE) continue;
                    m.virtualStartMs = s;
                    m.lastKnownDurMs = resolveLinkDurationMs(m.kind, m.id);
                }
            }
            return;
        }
        // Exactly one mover: push its delta to every partner.
        for (int i = 0; i < g.members.size(); i++) {
            com.fadcam.ui.faditor.layers.LinkMember m = g.members.get(i);
            if (i == moverIdx) {
                m.virtualStartMs = resolveLinkStartMs(m.kind, m.id);
                continue;
            }
            if (m.virtualStartMs == UNSET) continue;
            m.virtualStartMs += delta; // unclamped virtual — drift-free through t=0
            applyLinkStartMs(m.kind, m.id, Math.max(0, m.virtualStartMs));
        }
    }

    /**
     * P1 — peer OPACITY propagation, the exact twin of {@link #resyncPeerTimeGroup}:
     * unclamped virtual opacities, exactly-one-mover delta push, ambiguity re-baselines.
     * Members without a static opacity (resolve returns NaN) simply don't participate.
     */
    private void resyncPeerOpacityGroup(@NonNull com.fadcam.ui.faditor.layers.LinkGroup g) {
        int moverIdx = -1;
        int movedCount = 0;
        float delta = 0f;
        boolean rebaselineOnly = false;
        for (int i = 0; i < g.members.size(); i++) {
            com.fadcam.ui.faditor.layers.LinkMember m = g.members.get(i);
            float v = resolveLinkOpacity(m.kind, m.id);
            if (Float.isNaN(v)) continue; // unsupported payload / dead id
            if (Float.isNaN(m.virtualOpacity)) {
                m.virtualOpacity = v;
                rebaselineOnly = true;
                continue;
            }
            float knownClamped = Math.max(0f, Math.min(1f, m.virtualOpacity));
            if (Math.abs(v - knownClamped) > 0.0005f) {
                movedCount++;
                moverIdx = i;
                delta = v - knownClamped;
            }
        }
        if (rebaselineOnly || movedCount != 1) {
            if (movedCount > 0) {
                for (com.fadcam.ui.faditor.layers.LinkMember m : g.members) {
                    float v = resolveLinkOpacity(m.kind, m.id);
                    if (!Float.isNaN(v)) m.virtualOpacity = v;
                }
            }
            return;
        }
        for (int i = 0; i < g.members.size(); i++) {
            com.fadcam.ui.faditor.layers.LinkMember m = g.members.get(i);
            if (i == moverIdx) {
                m.virtualOpacity = resolveLinkOpacity(m.kind, m.id);
                continue;
            }
            if (Float.isNaN(m.virtualOpacity)) continue;
            m.virtualOpacity += delta; // unclamped — drift-free through the 0/1 rails
            applyLinkOpacity(m.kind, m.id, Math.max(0f, Math.min(1f, m.virtualOpacity)));
        }
    }

    /** STATIC opacity of a linkable payload, or NaN (unsupported kind / dead id). v1:
     *  text + sprite only — viz rides its host, PiP opacity is keyframe-set-owned. */
    private float resolveLinkOpacity(@NonNull String kind, @NonNull String id) {
        switch (kind) {
            case "textOverlay":
                for (TextOverlayItem t : textOverlays) {
                    if (id.equals(t.getId())) return t.getOpacity();
                }
                return Float.NaN;
            case "sprite":
                for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteOverlays) {
                    if (id.equals(s.getId())) return s.getOpacity();
                }
                return Float.NaN;
            default:
                return Float.NaN;
        }
    }

    private void applyLinkOpacity(@NonNull String kind, @NonNull String id, float v) {
        switch (kind) {
            case "textOverlay":
                for (TextOverlayItem t : textOverlays) {
                    if (id.equals(t.getId())) { t.setOpacity(v); return; }
                }
                return;
            case "sprite":
                for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteOverlays) {
                    if (id.equals(s.getId())) { s.setOpacity(v); return; }
                }
                return;
            default:
        }
    }

    /**
     * Display-duration proxy for the MOVE-vs-TRIM discriminator. Open ends map to
     * {@link Long#MAX_VALUE} (stable across moves), dead ids to {@link Long#MIN_VALUE}.
     */
    private long resolveLinkDurationMs(@NonNull String kind, @NonNull String id) {
        switch (kind) {
            case "clip":
                for (Clip oc : overlayClips) {
                    if (id.equals(oc.getId())) return oc.getTrimmedDurationMs();
                }
                return Long.MIN_VALUE;
            case "textOverlay":
                for (TextOverlayItem t : textOverlays) {
                    if (id.equals(t.getId())) {
                        return t.getEndMs() == Long.MAX_VALUE
                                ? Long.MAX_VALUE : t.getEndMs() - t.getStartMs();
                    }
                }
                return Long.MIN_VALUE;
            case "audioClip":
                for (AudioClip a : audioClips) {
                    if (id.equals(a.getId())) return a.getTrimmedDurationMs();
                }
                return Long.MIN_VALUE;
            case "sprite":
                for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteOverlays) {
                    if (id.equals(s.getId())) {
                        return s.getEndMs() == Long.MAX_VALUE
                                ? Long.MAX_VALUE : s.getEndMs() - s.getStartMs();
                    }
                }
                return Long.MIN_VALUE;
            case "waveform":
                for (WaveformOverlayInstance w : waveformOverlays) {
                    if (id.equals(w.getId())) return w.getEndMs() - w.getStartMs();
                }
                return Long.MIN_VALUE;
            default:
                return Long.MIN_VALUE;
        }
    }

    /**
     * JoyRaptor's re-scope (2026-07-19 #3): membership is keyed by (item, property-axis). The
     * owner of an axis for an item is the FIRST live group claiming both; link-creation
     * must keep this unique — see {@code createTimeLinkGroup}'s conflict strip.
     */
    @Nullable
    public com.fadcam.ui.faditor.layers.LinkGroup axisOwner(
            @NonNull String itemId, @NonNull com.fadcam.ui.faditor.layers.LinkedProperty axis) {
        for (com.fadcam.ui.faditor.layers.LinkGroup g : getAllLinkGroupsView()) {
            if (g.properties.contains(axis) && g.findMember(itemId) != null) return g;
        }
        return null;
    }

    /**
     * Capture each rider's CURRENT offset behind the host — call once at link-creation time
     * (G9d) so subsequent resyncs re-derive from these. No-op for peer groups.
     */
    public void captureLinkHostOffsets(@NonNull com.fadcam.ui.faditor.layers.LinkGroup g) {
        com.fadcam.ui.faditor.layers.LinkMember host = g.getHost();
        if (host == null) return;
        long hostStart = resolveLinkStartMs(host.kind, host.id);
        if (hostStart == Long.MIN_VALUE) return;
        for (com.fadcam.ui.faditor.layers.LinkMember m : g.members) {
            if (m.isHost) continue;
            long s = resolveLinkStartMs(m.kind, m.id);
            if (s != Long.MIN_VALUE) m.hostOffsetMs = s - hostStart;
        }
    }

    /**
     * Rebuild the TRANSIENT G5-preset link groups — one PIGGYBACK/STRATIFIED host/rider group per
     * attached visualizer (plan §4.1) so the link UI surfaces G5 tethers through the same
     * machinery as ad-hoc groups. Deterministic ids ("g5:" + instance id) keep badges stable
     * across rebuilds. Never persisted; ground truth remains the attach fields, and unlinking a
     * preset group must route through {@link #detachVisualizer} (plan §5.3), not member removal.
     */
    public void synthesizeG5PresetLinkGroups() {
        syntheticPresetGroups.clear();
        for (WaveformOverlayInstance w : waveformOverlays) {
            if (w.getAttachedClipId() == null) continue;
            com.fadcam.ui.faditor.layers.LinkGroup g =
                    new com.fadcam.ui.faditor.layers.LinkGroup("g5:" + w.getId());
            g.presetKind = w.isStratified()
                    ? com.fadcam.ui.faditor.layers.LinkGroup.PRESET_STRATIFIED
                    : com.fadcam.ui.faditor.layers.LinkGroup.PRESET_PIGGYBACK;
            g.properties.add(com.fadcam.ui.faditor.layers.LinkedProperty.TIME);
            if (!w.isStratified()) {
                g.properties.add(com.fadcam.ui.faditor.layers.LinkedProperty.OPACITY);
            }
            g.members.add(new com.fadcam.ui.faditor.layers.LinkMember(
                    "clip", w.getAttachedClipId(), /* isHost= */ true));
            com.fadcam.ui.faditor.layers.LinkMember rider =
                    new com.fadcam.ui.faditor.layers.LinkMember(
                            "waveform", w.getId(), /* isHost= */ false);
            rider.hostOffsetMs = w.getAttachOffsetMs();
            g.members.add(rider);
            syntheticPresetGroups.add(g);
        }
    }

    /**
     * §4.5 one-time load migration: per-LAYER eye/lock is retired — any persisted
     * track-level hidden/locked pushes DOWN onto the track's current objects, then the
     * track flag clears, so no pre-§4.5 project is left invisibly hidden with the gutter
     * toggle gone. Idempotent (cleared flags never re-fire). Caption tracks are skipped
     * (caption visibility is the clip's captionsEnabled, not an object flag). Returns the
     * migrated-track count for logging.
     */
    public int migrateTrackEyeLockToObjects() {
        int migrated = 0;
        List<Track> all = new ArrayList<>(getLayers());
        all.addAll(getVisualizerTracks());
        all.addAll(getAudioTracks());
        for (Track t : all) {
            TrackFlags f = trackFlags.get(t.getId());
            if (f == null || (!f.hidden && !f.locked)) continue;
            for (TimedItem item : t.getItems()) {
                if (item.getTextOverlay() != null) {
                    if (f.hidden) item.getTextOverlay().setHidden(true);
                    if (f.locked) item.getTextOverlay().setLocked(true);
                } else if (item.getSprite() != null) {
                    if (f.hidden) item.getSprite().setHidden(true);
                    if (f.locked) item.getSprite().setLocked(true);
                } else if (item.getWaveform() != null) {
                    if (f.hidden) item.getWaveform().setHidden(true);
                    if (f.locked) item.getWaveform().setLocked(true);
                } else if (item.getAudioClip() != null) {
                    if (f.hidden) item.getAudioClip().setMuted(true); // audio eye == mute
                    if (f.locked) item.getAudioClip().setLocked(true);
                } else if (item.getClip() != null && item.getClip().isOverlayClip()) {
                    if (f.hidden) item.getClip().setHiddenObject(true);
                    if (f.locked) item.getClip().setLockedObject(true);
                }
            }
            f.hidden = false;
            f.locked = false;
            migrated++;
        }
        return migrated;
    }

    // ── User-created layer-track definitions (M10) ─────────────────────

    /**
     * Create a new, persistent, initially-EMPTY layer track and return its stable id.
     * {@code kind} must be {@link TrackKind#TEXT}/{@link TrackKind#STICKER}/
     * {@link TrackKind#SPRITE} (floating layer — SPRITE routed since schema v9,
     * PLAN_SPRITE_ANIMATION S1) or {@link TrackKind#AUDIO} (audio band) — the kinds
     * {@link #getLayers()}/{@link #getAudioTracks()} route by {@code layerId} today —
     * or {@link TrackKind#LAYER} for a neutral any-payload lane (SPEC_NEUTRAL_SUBSTRATE).
     */
    @NonNull
    public String createLayerTrack(@NonNull TrackKind kind, @NonNull String name) {
        LayerTrackDef def = new LayerTrackDef(kind, name);
        extraLayerTracks.add(def);
        return def.getId();
    }

    /** Re-insert a previously-created track definition (undo of a delete/creation). */
    public void restoreLayerTrackDef(@NonNull LayerTrackDef def) {
        restoreLayerTrackDefAt(def, -1);
    }

    /**
     * Re-insert a definition at its ORIGINAL index in {@link #extraLayerTracks}
     * ({@code index < 0} = append). Position matters: within a kind's phase,
     * {@link #getLayers()} emits defs in this list's order, so an undo that appended
     * instead of re-inserting would silently move the restored row to the end of its
     * phase whenever zIndex flags don't already pin the order.
     */
    public void restoreLayerTrackDefAt(@NonNull LayerTrackDef def, int index) {
        for (LayerTrackDef existing : extraLayerTracks) {
            if (existing.getId().equals(def.getId())) return; // already present
        }
        if (index >= 0 && index <= extraLayerTracks.size()) {
            extraLayerTracks.add(index, def);
        } else {
            extraLayerTracks.add(def);
        }
    }

    /** Index of {@code trackId} in {@link #extraLayerTracks}, or -1. */
    public int indexOfLayerTrackDef(@NonNull String trackId) {
        for (int i = 0; i < extraLayerTracks.size(); i++) {
            if (extraLayerTracks.get(i).getId().equals(trackId)) return i;
        }
        return -1;
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
        // SPEC_NEUTRAL_SUBSTRATE S0: sprites and overlay clips were MISSING here, so a
        // user SPRITE/VIDEO (and now LAYER) track still holding them could be pruned by
        // maybeRemoveEmptyLayerTrack, orphaning its items into defensive leftover buckets.
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem so : spriteOverlays) {
            if (trackId.equals(so.getLayerId())) return true;
        }
        for (Clip oc : overlayClips) {
            if (trackId.equals(oc.getLayerId())) return true;
        }
        return false;
    }
}
