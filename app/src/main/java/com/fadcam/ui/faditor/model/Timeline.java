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

    /**
     * Cross-fades between adjacent AUDIO lanes (SPEC_AUDIO_UX_V1 §5, row B2).
     * Kept on the timeline rather than on either clip because a cross-fade is a
     * RELATIONSHIP between two lanes — see {@link AudioCrossfade}'s class note.
     */
    @NonNull
    private final List<AudioCrossfade> audioCrossfades;

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
     * Master edit behavior (schema v8). "ripple" = an edit that changes length at time T shifts
     * everything after T; "gap" = it leaves a gap and nothing else moves. Default "ripple".
     *
     * <p>"Everything" includes floating layers. It used to mean clips and anchored riders only,
     * which made one trim silently desynchronise every unanchored object in the project — see
     * {@link #applyAnchorShift}.</p>
     */
    @NonNull
    private String rippleMode = "ripple";

    /**
     * The "before" map of the OUTERMOST open structural bracket — see {@link #beginStructural()}.
     * Not persisted and not part of the model; it lives only for the duration of one user action.
     */
    @Nullable
    private transient Map<String, Long> structuralOwner = null;

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
        this.audioCrossfades = new ArrayList<>();
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

    // ── Adjustment layers (SPEC_ADJUSTMENT_LAYERS_FX M3) ─────────────

    /**
     * Layers that TRANSFORM everything beneath them in z, rather than compositing over it.
     * Mirrors {@link #overlayClips} deliberately: they are siblings in the z order, and giving
     * them different shapes is how the two lists would drift apart.
     */
    private final List<AdjustmentLayer> adjustmentLayers = new ArrayList<>();

    /** Unmodifiable view of the adjustment layers. */
    @NonNull
    public List<AdjustmentLayer> getAdjustmentLayers() {
        return Collections.unmodifiableList(adjustmentLayers);
    }

    /** Add an adjustment layer. It must carry a non-empty {@code layerId}, like an overlay. */
    public void addAdjustmentLayer(@NonNull AdjustmentLayer layer) {
        adjustmentLayers.add(layer);
    }

    public boolean removeAdjustmentLayer(@NonNull AdjustmentLayer layer) {
        return adjustmentLayers.remove(layer);
    }

    @Nullable
    public AdjustmentLayer findAdjustmentLayer(@NonNull String id) {
        for (AdjustmentLayer a : adjustmentLayers) {
            if (a.getId().equals(id)) return a;
        }
        return null;
    }

    /** Adjustment layers live at {@code editorMs}, in list order (bottom-up). */
    @NonNull
    public List<AdjustmentLayer> visibleAdjustmentLayers(long editorMs) {
        List<AdjustmentLayer> out = new ArrayList<>();
        for (AdjustmentLayer a : adjustmentLayers) {
            if (a.activeAt(editorMs)) out.add(a);
        }
        return out;
    }

    /**
     * True when any adjustment layer exists at all — the schema-v13 trigger for this feature.
     *
     * <p>An empty list is what every project that predates adjustment layers has, so the stamp
     * stays off for all of them and they remain openable by older builds.</p>
     */
    public boolean usesAdjustmentLayers() { return !adjustmentLayers.isEmpty(); }

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
    @Nullable
    public Clip getClip(int index) {
        if (index < 0 || index >= clips.size()) return null;
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
     * The furthest timeline point reached by any overlay object that has a REAL end —
     * text, sprite, waveform and adjustment items — or 0 if none overhangs anything.
     *
     * <p><b>Open-ended items are deliberately excluded, and that exclusion is the whole point.</b>
     * Throughout this codebase an end of {@link Long#MAX_VALUE} (or an end at/before the start,
     * or a zero duration for an adjustment) means "run to the end of the project" — see
     * {@code TimedItem.getDisplayDurationMs}, which resolves exactly these cases against the
     * project length. Such an item FOLLOWS the project; it does not lead it. Feeding one into a
     * routine that grows the project to cover its objects would ask for a project long enough to
     * contain something defined as "however long the project is" — it would either demand
     * {@code Long.MAX_VALUE} of black outright, or grow by a step every pass and never converge.
     *
     * <p>So a text object set to span the whole project stops at the last real object, which is
     * the behaviour a user expects and asked for. Only an object with a definite end can push the
     * project longer.
     *
     * <p>Audio is not counted HERE - see {@link #maxAudioEndMs()}, which the spine sync adds
     * alongside this. The two are kept apart because the open-ended reasoning above applies only
     * to overlay items; an audio clip always has a definite end, bounded by a real file.
     */
    public long maxBoundedOverlayEndMs() {
        long max = 0;
        for (TextOverlayItem o : textOverlays) {
            long s = Math.max(0, o.getStartMs());
            long e = o.getEndMs();
            if (e != Long.MAX_VALUE && e > s && e > max) max = e;
        }
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem o : spriteOverlays) {
            long s = Math.max(0, o.getStartMs());
            long e = o.getEndMs();
            if (e != Long.MAX_VALUE && e > s && e > max) max = e;
        }
        for (WaveformOverlayInstance o : waveformOverlays) {
            long s = Math.max(0, o.getStartMs());
            long e = o.getEndMs();
            if (e != Long.MAX_VALUE && e > s && e > max) max = e;
        }
        for (AdjustmentLayer a : adjustmentLayers) {
            if (a.getDurationMs() <= 0) continue; // open-ended: "grade everything"
            long e = a.getEndMs();
            if (e > max) max = e;
        }
        return max;
    }

    /**
     * The latest point any audio clip reaches on the timeline, or 0 if there is none.
     *
     * <p>Used by the spine sync to extend the black tail. It used to be deliberate that audio did
     * NOT do this - the reasoning being that silence needs no picture under it - and that holds
     * for a video with a music bed laid over it. It fails for the case JoyRaptor hit first: load a
     * song, then place pictures along it. There the song IS the project, and with no spine under
     * it there is no base frame to drop a picture onto and nowhere for the playhead to go.
     *
     * <p>It was also already inconsistent. {@link #getTotalDurationMs()} counts audio, so the
     * project was ALREADY 6:11 long with a 4.6s spine, and the exporter appends its own black
     * filler to cover the difference ("appended a 1201ms black filler so overlays and audio past
     * the last clip are still rendered"). The file you got already had the black in it; only the
     * timeline was hiding it. This makes the spine tell the truth about the export.
     *
     * <p>Bounded by construction: an audio clip's end is its offset plus a trimmed duration that
     * came from a real file, so unlike an open-ended overlay it cannot chase the project length.
     */
    public long maxAudioEndMs() {
        long max = 0;
        for (AudioClip a : audioClips) {
            if (a == null) continue;
            long e = a.getEndOnTimelineMs();
            if (e > max) max = e;
        }
        return max;
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
        String originalId = clips.get(clipIndex).getId();
        clips.remove(clipIndex);
        clips.add(clipIndex, clipB);
        clips.add(clipIndex, clipA); // A goes first

        reanchorAfterSplit(originalId, clipIndex);

        return clipIndex;
    }

    // ── M12: moving a clip between the SPINE and a floating LAYER ───────────────────────────
    //
    // A master clip and a PiP are the SAME CLASS in two lists, discriminated by
    // isOverlayClip() → layerId != null. So the move itself is a list transfer. What is NOT
    // trivial — and is the actual work — is that several fields CHANGE MEANING across the
    // boundary. The audit of 2026-08-03 found four that would otherwise break silently:
    //
    //   audio    overlayAudioEnabled defaults FALSE and only opted-in PiPs get an audio
    //            sequence, so a demoted clip would go SILENT. Carried explicitly below.
    //   captions clip-owned but MASTER-ONLY (getCaptionTracks iterates `clips`). Data survives
    //            the round trip; RENDERING does not, until the caption-attach slice lands.
    //   hidden/  honoured for PiPs only ("master tape clips ignore both"), so a hidden object
    //   locked   would REAPPEAR on promote. Cleared on the way up.
    //   transform overlayTransform/overlayBlendMode are serialized ONLY when layerId != null,
    //            so promote-then-save would drop the keyframe envelope permanently.
    //
    // Both directions preserve the clip's ABSOLUTE timeline position, which is what makes the
    // gesture feel like a move rather than a reset.

    /**
     * Move master clip {@code clipIndex} onto floating layer {@code layerId} (spine → layer),
     * keeping its absolute timeline position. The spine ripple-closes behind it.
     *
     * @return the demoted clip, or null if the index is invalid.
     */
    @Nullable
    public Clip demoteToLayer(int clipIndex, @NonNull String layerId) {
        if (clipIndex < 0 || clipIndex >= clips.size()) return null;
        Clip c = clips.get(clipIndex);
        long absStart = segmentStartMs(clipIndex);

        clips.remove(clipIndex);
        removeTransitionsForDeletedClip(clipIndex);

        // layerId is what MAKES it an overlay; it must never be null in overlayClips.
        c.setLayerId(layerId);
        c.setOverlayStartMs(absStart);
        // Keep it audible. A PiP is silent unless opted in, so without this the clip's sound
        // vanishes the moment it leaves the spine — a silent data loss the user did not ask for.
        c.setOverlayAudioEnabled(true);
        overlayClips.add(c);
        return c;
    }

    /**
     * Move a floating clip onto the spine at {@code insertIndex} (layer → spine), pushing later
     * clips right. The inverse of {@link #demoteToLayer}.
     *
     * @return true if the clip was found among the overlays and moved.
     */
    public boolean promoteToMaster(@NonNull Clip overlayClip, int insertIndex) {
        if (!overlayClips.remove(overlayClip)) return false;
        int idx = Math.max(0, Math.min(insertIndex, clips.size()));

        // Overlay-only state, cleared deliberately rather than left to rot:
        //  • hidden/locked mean nothing on the spine and would silently un-hide the object.
        //  • layerId null IS the master-clip signal (isOverlayClip()).
        overlayClip.setHiddenObject(false);
        overlayClip.setLockedObject(false);
        overlayClip.setLayerId(null);
        overlayClip.setOverlayStartMs(0L);

        clips.add(idx, overlayClip);
        shiftTransitionsAfterInsert(idx);
        return true;
    }

    /**
     * Re-home riders anchored to a clip that has just been split into halves at
     * {@code indexA} / {@code indexA + 1} (§4A).
     *
     * <p><b>Why this is not the orphan case.</b> A split mints a fresh {@code UUID} on BOTH halves
     * ({@code new Clip(other)}), so every anchor to the original would dangle — but the clip has
     * not gone anywhere, it is merely two clips now. The user's intent is unambiguous, so this
     * repairs silently and correctly, where a DELETE must stop and ask.</p>
     *
     * <p>A rider re-homes to whichever half its own START falls in, under the same half-open rule
     * everything else uses, and its offset is recaptured against that half.</p>
     */
    /**
     * Public form, for split implementations that build the halves by hand instead of calling
     * {@link #splitAt} — {@code EditScriptApplier} has two of them (plain split and b-roll
     * cutaway). Without this they mint fresh UUIDs and silently orphan every rider on the clip,
     * which is the same defect {@code splitAt} already guards against.
     */
    public void reanchorAfterManualSplit(@NonNull String originalId, int indexA) {
        reanchorAfterManualSplit(originalId, indexA, 2);
    }

    /**
     * As above, for a split that produced {@code partCount} clips rather than two — the AI's
     * b-roll cutaway replaces one clip with THREE ({@code before, broll, after}).
     *
     * <p>Re-homing such a split with the two-way form put every rider whose start fell in the
     * LAST part onto the middle clip, with its offset then clamped to that clip's much shorter
     * span. Generalised rather than special-cased, so a four-way split later cannot repeat it.</p>
     */
    public void reanchorAfterManualSplit(@NonNull String originalId, int indexA, int partCount) {
        if (indexA < 0 || partCount < 2 || indexA + partCount > clips.size()) return;
        for (TextOverlayItem o : textOverlays) {
            if (!originalId.equals(o.getHostClipId())) continue;
            int part = indexA;
            for (int i = indexA; i < indexA + partCount; i++) {
                if (o.getStartMs() >= segmentStartMs(i)) part = i; else break;
            }
            long hostStart = segmentStartMs(part);
            o.setHostAnchor(clips.get(part).getId(),
                    AnchorMath.offsetWithinHost(o.getStartMs(), hostStart,
                            clipSpanMs(clips.get(part))));
        }
    }

    /**
     * Re-home every rider whose {@code hostClipId} names a clip that is not on the timeline, using
     * the clip under the rider's own start — the same rule {@link #attachOverlayToHostUnderStart}
     * applies when a rider is first placed. Returns one line per repair, for the caller to LOG.
     *
     * <p><b>Why this runs at load.</b> A dangling host is a rider that {@link #applyAnchorShift}
     * treats as an orphan forever: reported, never moved, so it silently stops tracking its footage
     * while still looking anchored. Projects already in the wild carry them — one real project has
     * eight, from splits made before re-homing existed and from the split-undo leak fixed alongside
     * this. Nothing else will ever clear them, and there is no user question to ask: the stored
     * pointer is to a clip that does not exist, and the rider's own time says which clip it is over.
     *
     * <p>Deliberately narrow. It only touches anchors that CANNOT resolve, never a valid one, and it
     * never changes a rider's time — same conservatism as {@link #pruneOrphanedTrackFlags}. With no
     * clips at all it does nothing rather than clearing every anchor in the project.</p>
     */
    @NonNull
    public List<String> healDanglingHostAnchors() {
        List<String> healed = new ArrayList<>();
        if (clips.isEmpty()) return healed;
        for (TextOverlayItem o : textOverlays) {
            String host = o.getHostClipId();
            if (host == null) continue;
            int idx = indexOfMasterClipId(host);
            if (idx >= 0) {
                // DRIFTED, not dangling: the host exists but start != hostStart + offset. The start
                // is what the preview and the export both draw, so it is the truth the user sees;
                // re-home to keep the anchor honest (the same repair a gap-mode edit now makes).
                if (o.getStartMs() == segmentStartMs(idx) + o.getHostOffsetMs()) continue;
                String newHost = attachOverlayToHostUnderStart(o);
                healed.add(o.getId() + " drift " + host + "→"
                        + (newHost == null ? "unanchored" : newHost));
                continue;
            }
            String newHost = attachOverlayToHostUnderStart(o);
            healed.add(o.getId() + " " + host + "→" + (newHost == null ? "unanchored" : newHost));
        }
        return healed;
    }

    /**
     * The inverse of {@link #reanchorAfterManualSplit}: parts became ONE clip again, so every rider
     * anchored to any of {@code mergedIds} is re-homed onto {@code survivorId}.
     *
     * <p><b>Why undo needs this at all.</b> A split re-homes riders onto the halves; re-joining them
     * left those anchors pointing at clips that no longer exist. A dangling host is not cosmetic —
     * {@link #applyAnchorShift} classifies such a rider as an ORPHAN and never moves it again, so it
     * silently stops tracking its footage for the rest of the project's life while still looking
     * anchored. One real project accumulated 8 that way, and they were mistaken for legacy data
     * rather than an ongoing leak.</p>
     *
     * <p>Unambiguous, unlike a DELETE: the content is still there and there is exactly one clip it
     * can belong to, so this repairs silently where §4A's orphan prompt would have to ask. Times are
     * untouched — a re-home is not a move.</p>
     */
    public void reanchorAfterJoin(@NonNull String survivorId, @NonNull String... mergedIds) {
        int idx = indexOfMasterClipId(survivorId);
        if (idx < 0) return;
        long hostStart = segmentStartMs(idx);
        long hostSpan = clipSpanMs(clips.get(idx));
        for (TextOverlayItem o : textOverlays) {
            String host = o.getHostClipId();
            if (host == null) continue;
            boolean merged = false;
            for (String id : mergedIds) {
                if (host.equals(id)) { merged = true; break; }
            }
            if (!merged) continue;
            o.setHostAnchor(survivorId,
                    AnchorMath.offsetWithinHost(o.getStartMs(), hostStart, hostSpan));
        }
    }

    private void reanchorAfterSplit(@NonNull String originalId, int indexA) {
        if (indexA < 0 || indexA + 1 >= clips.size()) return;
        for (TextOverlayItem o : textOverlays) {
            if (!originalId.equals(o.getHostClipId())) continue;
            int half = (o.getStartMs() >= segmentStartMs(indexA + 1)) ? indexA + 1 : indexA;
            long hostStart = segmentStartMs(half);
            o.setHostAnchor(clips.get(half).getId(),
                    AnchorMath.offsetWithinHost(o.getStartMs(), hostStart,
                            clipSpanMs(clips.get(half))));
        }
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
        String freeLane = findFreeAudioLane(audioClip);
        if (freeLane != null) {
            // The requested TIME is what the user chose; the lane is ours to pick.
            audioClip.setLayerId(freeLane);
            audioClips.add(audioClip);
            return;
        }
        // Every lane is busy across this span — fall back to the old behaviour and slide it
        // along its own lane rather than dropping it on top of something.
        long resolved = resolveAudioOverlap(audioClip.getOffsetMs(), audioClip);
        if (resolved != audioClip.getOffsetMs()) {
            audioClip.setOffsetMs(resolved);
        }
        audioClips.add(audioClip);
    }

    /**
     * Pick an audio lane on which {@code candidate} can sit AT THE OFFSET IT ASKED FOR.
     *
     * <p>JoyRaptor, 2026-09-13: <i>"adding audio when there's already audio had songs stack in one
     * lane, not drop another audio lane as expected."</i> Audio was the one overlay family with no
     * no-overlap lane rule — text, PiP/video and adjustment layers each have an
     * {@code enforceNoOverlap*Lanes}, and audio instead had {@link #resolveAudioOverlap}, which
     * keeps the LANE and moves the clip in TIME. For a music bed that is the wrong trade: where a
     * song starts is the user's decision and which row it draws on is not, so shoving a second
     * song to the tail of the first silently destroys the only part they chose.
     *
     * <p>Tries the lane the clip already names, then every other existing audio lane in row order,
     * then mints a deterministic {@code "audio-<id>"} lane — the same scheme
     * {@link #enforceNoOverlapTextLanes()} uses for overflow, so a fresh lane surfaces through
     * {@link #getAudioTracks()}'s leftover-bucket pass without needing a registered def.
     *
     * @return a lane id that is free across the candidate's span, or {@code null} if the candidate
     *         has no usable duration (in which case the caller keeps its old behaviour).
     */
    @Nullable
    private String findFreeAudioLane(@NonNull AudioClip candidate) {
        long start = Math.max(0, candidate.getOffsetMs());
        long end = start + Math.max(1, candidate.getTrimmedDurationMs());

        // Candidate lanes, in the order a user would expect them to fill: the one it names
        // first, then the rest of the existing rows.
        List<String> lanes = new ArrayList<>();
        String own = candidate.getLayerId() != null ? candidate.getLayerId() : "audio";
        lanes.add(own);
        for (AudioClip ac : audioClips) {
            if (ac == candidate) continue;
            String id = ac.getLayerId() != null ? ac.getLayerId() : "audio";
            if (!lanes.contains(id)) lanes.add(id);
        }
        for (LayerTrackDef def : extraLayerTracks) {
            if (def.getKind() == TrackKind.AUDIO && !lanes.contains(def.getId())) {
                lanes.add(def.getId());
            }
        }

        for (String lane : lanes) {
            if (audioLaneFreeOver(lane, start, end, candidate)) return lane;
        }
        return "audio-" + candidate.getId();
    }

    /** True when no audio clip on {@code laneId} (other than {@code self}) covers [startMs, endMs). */
    private boolean audioLaneFreeOver(@NonNull String laneId, long startMs, long endMs,
                                      @Nullable AudioClip self) {
        for (AudioClip ac : audioClips) {
            if (ac == self) continue;
            String id = ac.getLayerId() != null ? ac.getLayerId() : "audio";
            if (!laneId.equals(id)) continue;
            long s = ac.getOffsetMs();
            long e = s + Math.max(1, ac.getTrimmedDurationMs());
            if (rangesOverlap(startMs, endMs, s, e)) return false;
        }
        return true;
    }

    /**
     * Enforce the no-overlap invariant on AUDIO lanes, mirroring
     * {@link #enforceNoOverlapTextLanes()}. Audio was the family this pass never covered, so a
     * project saved before {@link #findFreeAudioLane} existed can still hold two clips on one lane
     * at the same time. Idempotent: overflow clips take a deterministic {@code "audio-<id>"} lane,
     * so running it on every load settles rather than churns.
     *
     * @return how many clips were moved to a fresh lane (0 = nothing to fix).
     */
    public int enforceNoOverlapAudioLanes() {
        Map<String, List<AudioClip>> byLayer = new LinkedHashMap<>();
        for (AudioClip ac : audioClips) {
            String id = ac.getLayerId() != null ? ac.getLayerId() : "audio";
            byLayer.computeIfAbsent(id, k -> new ArrayList<>()).add(ac);
        }
        int moved = 0;
        for (List<AudioClip> lane : byLayer.values()) {
            if (lane.size() < 2) continue;
            List<AudioClip> sorted = new ArrayList<>(lane);
            sorted.sort((a, b) -> Long.compare(a.getOffsetMs(), b.getOffsetMs()));
            List<Long> subLaneEnd = new ArrayList<>();
            for (AudioClip ac : sorted) {
                long s = ac.getOffsetMs();
                long e = s + Math.max(1, ac.getTrimmedDurationMs());
                int placed = -1;
                for (int k = 0; k < subLaneEnd.size(); k++) {
                    if (subLaneEnd.get(k) <= s) { placed = k; break; }
                }
                if (placed < 0) { placed = subLaneEnd.size(); subLaneEnd.add(e); }
                else subLaneEnd.set(placed, e);
                if (placed > 0) {
                    String want = "audio-" + ac.getId();
                    if (!want.equals(ac.getLayerId())) { ac.setLayerId(want); moved++; }
                }
            }
        }
        return moved;
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
    /** Live list — mutate through the add/remove helpers so callers stay undoable. */
    @NonNull
    public List<AudioCrossfade> getAudioCrossfades() { return audioCrossfades; }

    public void addAudioCrossfade(@NonNull AudioCrossfade x) { audioCrossfades.add(x); }

    public boolean removeAudioCrossfadeById(@NonNull String id) {
        for (int i = 0; i < audioCrossfades.size(); i++) {
            if (audioCrossfades.get(i).getId().equals(id)) { audioCrossfades.remove(i); return true; }
        }
        return false;
    }

    @androidx.annotation.Nullable
    public AudioCrossfade findAudioCrossfade(@NonNull String id) {
        for (AudioCrossfade x : audioCrossfades) if (x.getId().equals(id)) return x;
        return null;
    }

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
     * SPEC W — ONE definition of "does this overlap", shared by every add path, the
     * carry/drop resolver and the move-drag resolver. Two half-open ranges overlap
     * when each starts before the other ends; merely butting (end == start) is NOT
     * an overlap, so back-to-back objects may share a lane.
     */
    public static boolean rangesOverlap(long aStartMs, long aEndMs, long bStartMs, long bEndMs) {
        return aStartMs < bEndMs && bStartMs < aEndMs;
    }

    /**
     * SPEC W §3 — ONE definition of "full span", shared by the carry/drop resolver,
     * the move-drag resolver and the auto-pan guards. "Place this before or after
     * that" is meaningless when THAT spans the entire timeline — there is no before
     * and no after — so the before/after shortcut must not apply. An object counts
     * when its duration covers all but a small tolerance of the timeline: exact
     * equality would flip the rule on trim rounding and open-ended resolution, and
     * 5% of a typical 30–60 s project (1.5–3 s) is far shorter than any deliberate
     * "almost the whole timeline" grade.
     */
    public static final float FULL_SPAN_FRACTION = 0.95f;

    /** See {@link #FULL_SPAN_FRACTION}. {@code totalMs <= 0} never counts. */
    public static boolean isFullSpanRange(long startMs, long durMs, long totalMs) {
        if (totalMs <= 0 || durMs <= 0) return false;
        if (startMs > totalMs * (1f - FULL_SPAN_FRACTION)) return false;
        return durMs >= (long) (totalMs * FULL_SPAN_FRACTION);
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
     * SPEC W item 2 — enforce the no-overlap invariant on ADJUSTMENT lanes, the last
     * item type after text / sprite (T8) / PiP. Before this, every adjustment layer
     * was created with {@code layerId "adjustment"} and {@link #getLayers()} bucketed
     * them ALL into one lane, so the second layer's translucent body painted straight
     * over the first's fx/trash badges (SPEC W item 1's "faded" badges) and the buried
     * object could not be selected, moved or deleted. Same packing as text/video:
     * per lane, in start order, each layer lands on the first sub-lane whose previous
     * layer has ended, else a fresh deterministic {@code "adjustment-<id>"} lane is
     * minted. Idempotent (safe on every load / undo-redo restore). Returns how many
     * layers were moved (0 = clean).
     */
    public int enforceNoOverlapAdjustmentLanes() {
        Map<String, List<AdjustmentLayer>> byLayer = new LinkedHashMap<>();
        for (AdjustmentLayer a : adjustmentLayers) {
            String id = a.getLayerId().isEmpty() ? "adjustment" : a.getLayerId();
            byLayer.computeIfAbsent(id, k -> new ArrayList<>()).add(a);
        }
        int moved = 0;
        for (List<AdjustmentLayer> lane : byLayer.values()) {
            if (lane.size() < 2) continue;
            List<AdjustmentLayer> sorted = new ArrayList<>(lane);
            sorted.sort((a, b) -> Long.compare(a.getStartMs(), b.getStartMs()));
            List<Long> subLaneEnd = new ArrayList<>(); // last end (ms) per sub-lane
            for (AdjustmentLayer a : sorted) {
                long s = a.getStartMs();
                long e = adjustmentEndForPacking(a);
                int placed = -1;
                for (int k = 0; k < subLaneEnd.size(); k++) {
                    if (subLaneEnd.get(k) <= s) { placed = k; break; }
                }
                if (placed < 0) { placed = subLaneEnd.size(); subLaneEnd.add(e); }
                else subLaneEnd.set(placed, e);
                if (placed > 0) {
                    // Overflow → its own deterministic lane (idempotent across loads).
                    String want = "adjustment-" + a.getId();
                    if (!want.equals(a.getLayerId())) { a.setLayerId(want); moved++; }
                }
                // placed == 0 keeps its original lane id (may be the seeded "adjustment").
            }
        }
        return moved;
    }

    /**
     * Slice F — "compact lanes" (JoyRaptor's CapCut orphan-lane pain): drop every overlay of a kind into
     * the FEWEST no-overlap lanes. Unlike {@link #enforceNoOverlapTextLanes()} (which only SPLITS
     * overlaps within one lane), this MERGES across lanes: within each family it re-packs the items
     * onto the lanes that family ALREADY occupies, lowest lane first, and then deletes the lane
     * definitions left empty. A MANUAL, undoable action (never run automatically). Covers EVERY lane
     * family in one pass — PiP/video + image overlays ({@code overlayClips}), sprites, text and
     * adjustment layers — so orphan-lane sprawl disappears across all of them. Captions are
     * Clip-owned and live on a single CAPTION track ({@link #getCaptionTracks()}), so they are
     * exempt by construction.
     *
     * <p><b>It reuses lane ids; it never mints one.</b> The previous implementation minted a fresh
     * {@code "<prefix>-<itemId>"} id for every lane past the first, which is how "compact" managed to
     * ADD rows: the vacated {@link LayerTrackDef}s went on emitting their (now empty) lanes, while the
     * minted ids surfaced as extra orphan rows in a different phase of {@link #getLayers()} — so a
     * project could come out of a compaction with more rows than it went in with, and with a new row
     * sitting between two items that had been cleanly stacked. Packing onto the family's existing lane
     * ids makes the row COUNT monotonically non-increasing by construction, and keeps each lane's
     * persisted {@code TrackFlags} (name, z, hidden, locked) attached to the items that stayed on it.</p>
     *
     * <p><b>Paint order is preserved.</b> Items are packed in the band's bottom→top order (the same
     * order {@code LayerPreviewController.orderedVisualItems} derives: lanes stable-sorted ascending by
     * {@link Track#getZIndex()}), and an item may never land BELOW a lower-z item it overlaps in time.
     * Start-order greedy packing — what this used to do — has no such rule, so a clip could be packed
     * underneath something it used to cover, silently changing the composite.</p>
     *
     * <p>MATTE COUPLING EXEMPTION (the one exception): a lane that takes part in a track-matte
     * pairing — either a RECIPIENT whose {@code CompositingSpec.mattePeerId} names another clip, or
     * the lane holding that matte PEER — is left COMPLETELY undisturbed. The renderers resolve the
     * pairing by clip id and expect both participants to keep their z/row position, so compacting
     * either would silently change what gets keyed. Those lanes are counted and reported back so the
     * UI can tell the user why.</p>
     *
     * @return the number of items that changed lane, the lane definitions deleted because nothing
     *         was left on them, and how many matte-coupled lanes were deliberately left alone.
     */
    @NonNull
    public CompactResult compactOverlayLanes() {
        java.util.Set<String> exempt = matteCoupledLaneIds();
        List<String> band = laneIdsBottomToTop();
        int moved = 0;
        moved += compactTextLanes(exempt, band);
        moved += compactSpriteLanes(exempt, band);
        moved += compactVideoLanes(exempt, band);
        moved += compactAdjustmentLanes(exempt, band);
        return new CompactResult(moved, countExemptLanes(exempt), removeEmptyLaneDefs(exempt));
    }

    /**
     * Every floating lane id in PAINT order (bottom → top) — the ordering the compositor uses, not
     * the one the rows are drawn in. {@link #getLayers()} hands back the band top-first (DESCENDING
     * z, see {@link #sortBandByZIndex}); {@code LayerPreviewController.orderedVisualItems} re-sorts
     * that same list ASCENDING with a STABLE sort before painting, so equal-z lanes paint in
     * emission order. This reproduces exactly that, because the packer's whole z guarantee is stated
     * in terms of it.
     */
    @NonNull
    private List<String> laneIdsBottomToTop() {
        List<Track> lanes = new ArrayList<>(getLayers());
        lanes.sort(java.util.Comparator.comparingInt(Track::getZIndex));
        List<String> ids = new ArrayList<>(lanes.size());
        for (Track t : lanes) ids.add(t.getId());
        return ids;
    }

    /**
     * Delete every user-created lane DEFINITION that now holds nothing at all — the second half of
     * "compact", and the reason the old verb looked inert: packing emptied lanes but the defs kept
     * emitting them, so the user watched their items merge and the empty rows stay. A def is removed
     * only when NO item of any payload type (text, sprite, overlay clip, adjustment, audio) still
     * names it and it is not matte-coupled. AUDIO defs are never touched — the audio band is not part
     * of this verb. Returns the removed defs with their original list index so the caller can undo.
     */
    @NonNull
    private List<RemovedLane> removeEmptyLaneDefs(@NonNull java.util.Set<String> exempt) {
        java.util.Set<String> inhabited = new java.util.HashSet<>();
        for (TextOverlayItem o : textOverlays) inhabited.add(o.getLayerId());
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteOverlays) {
            inhabited.add(s.getLayerId());
        }
        for (Clip oc : overlayClips) inhabited.add(oc.getLayerId());
        for (AdjustmentLayer a : adjustmentLayers) inhabited.add(a.getLayerId());
        for (AudioClip ac : audioClips) inhabited.add(ac.getLayerId());
        List<RemovedLane> removed = new ArrayList<>();
        for (int i = 0; i < extraLayerTracks.size(); i++) {
            LayerTrackDef def = extraLayerTracks.get(i);
            if (def.getKind() == TrackKind.AUDIO) continue;
            if (inhabited.contains(def.getId())) continue;
            if (exempt.contains(def.getId())) continue;
            removed.add(new RemovedLane(def, i));
        }
        for (RemovedLane rl : removed) extraLayerTracks.remove(rl.def);
        return removed;
    }

    /**
     * Every lane that is part of a track-matte pairing and must survive compaction untouched:
     * <ul>
     *   <li>the lane of any RECIPIENT (an overlay clip, text or adjustment layer) whose
     *       {@code CompositingSpec.mattePeerId} is set — it consumes another lane's pixels;</li>
     *   <li>the lane of the matte PEER overlay clip that id names — its pixels ARE the mask.</li>
     * </ul>
     * A master clip can also name a matte peer, so its peer's lane is exempted too (the master itself
     * has no lane to compact). Null lane ids are preserved (HashSet allows null) so the default
     * bucket of a family is exempted exactly like any named lane.
     */
    @NonNull
    private java.util.Set<String> matteCoupledLaneIds() {
        java.util.Set<String> exempt = new java.util.HashSet<>();
        for (Clip oc : overlayClips) {
            addMatteCoupledLane(exempt, oc.getCompositing(), oc.getLayerId());
        }
        for (Clip c : clips) {
            addMattePeerLane(exempt, c.getCompositing());
        }
        for (TextOverlayItem t : textOverlays) {
            addMatteCoupledLane(exempt, t.getCompositing(), t.getLayerId());
        }
        for (AdjustmentLayer a : adjustmentLayers) {
            addMatteCoupledLane(exempt, a.getCompositing(), a.getLayerId());
        }
        return exempt;
    }

    private void addMatteCoupledLane(@NonNull java.util.Set<String> exempt,
            @Nullable CompositingSpec cs, @Nullable String recipientLane) {
        if (cs == null || cs.mattePeerId == null) return;
        exempt.add(recipientLane);
        addMattePeerLane(exempt, cs);
    }

    private void addMattePeerLane(@NonNull java.util.Set<String> exempt,
            @Nullable CompositingSpec cs) {
        if (cs == null || cs.mattePeerId == null) return;
        // Resolved against overlayClips, like the renderers do.
        Clip peer = findOverlayClip(cs.mattePeerId);
        if (peer != null) exempt.add(peer.getLayerId());
    }

    /**
     * How many DISTINCT lanes in the exempt set actually carry an item today — the number a user
     * would see skipped on screen. {@code omittedLanes} in {@link CompactResult} is this count, not
     * the raw set size, so an exempt lane that happens to be empty never inflates the toast.
     */
    private int countExemptLanes(@NonNull java.util.Set<String> exempt) {
        if (exempt.isEmpty()) return 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        int n = 0;
        for (Clip oc : overlayClips) {
            if (exempt.contains(oc.getLayerId()) && seen.add(oc.getLayerId())) n++;
        }
        for (TextOverlayItem t : textOverlays) {
            if (exempt.contains(t.getLayerId()) && seen.add(t.getLayerId())) n++;
        }
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteOverlays) {
            if (exempt.contains(s.getLayerId()) && seen.add(s.getLayerId())) n++;
        }
        for (AdjustmentLayer a : adjustmentLayers) {
            if (exempt.contains(a.getLayerId()) && seen.add(a.getLayerId())) n++;
        }
        return n;
    }

    private int compactTextLanes(@NonNull java.util.Set<String> exempt, @NonNull List<String> band) {
        return packFamily(textOverlays,
                TextOverlayItem::getStartMs,
                Timeline::textEndForPacking,
                o -> o.getLayerId() == null ? "text" : o.getLayerId(),
                (o, lane) -> o.setLayerId("text".equals(lane) ? null : lane),
                band, canonicalExempt(exempt, "text", null));
    }

    private int compactSpriteLanes(@NonNull java.util.Set<String> exempt, @NonNull List<String> band) {
        return packFamily(spriteOverlays,
                com.fadcam.ui.faditor.sprite.SpriteOverlayItem::getStartMs,
                Timeline::spriteEndForPacking,
                o -> o.getLayerId() == null ? "sprite" : o.getLayerId(),
                (o, lane) -> o.setLayerId("sprite".equals(lane) ? null : lane),
                band, canonicalExempt(exempt, "sprite", null));
    }

    private int compactVideoLanes(@NonNull java.util.Set<String> exempt, @NonNull List<String> band) {
        return packFamily(overlayClips,
                Clip::getOverlayStartMs,
                Timeline::videoEndForPacking,
                o -> o.getLayerId() == null ? "video" : o.getLayerId(),
                (o, lane) -> o.setLayerId("video".equals(lane) ? null : lane),
                band, canonicalExempt(exempt, "video", null));
    }

    private int compactAdjustmentLanes(@NonNull java.util.Set<String> exempt,
            @NonNull List<String> band) {
        return packFamily(adjustmentLayers,
                AdjustmentLayer::getStartMs,
                Timeline::adjustmentEndForPacking,
                a -> a.getLayerId().isEmpty() ? "adjustment" : a.getLayerId(),
                (a, lane) -> a.setLayerId("adjustment".equals(lane) ? "" : lane),
                band, canonicalExempt(exempt, "adjustment", ""));
    }

    /**
     * The exempt lane ids as {@link #getLayers()} would name them. {@code matteCoupledLaneIds()}
     * collects RAW {@code layerId} values, and a family's default bucket is written raw as
     * {@code null} (text/sprite/video) or {@code ""} (adjustment) while the lane it lands in is
     * called "text"/"sprite"/"video"/"adjustment". Translating only THIS family's default value
     * preserves the old behaviour exactly: a matte sitting on the default bucket blocks that
     * bucket, and nothing else changes.
     */
    @NonNull
    private static java.util.Set<String> canonicalExempt(@NonNull java.util.Set<String> raw,
            @NonNull String seedLaneId, @Nullable String defaultRawValue) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String id : raw) {
            out.add(id == null ? (defaultRawValue == null ? seedLaneId : null)
                    : (id.equals(defaultRawValue) ? seedLaneId : id));
        }
        return out;
    }

    /**
     * Re-pack ONE lane family onto the lanes it already occupies.
     *
     * <p>The lanes this family's items sit on are taken in band paint order (bottom → top) and split
     * into runs at every matte-exempt lane, so an exempt lane is both untouched AND a barrier — no
     * item may hop across it, which would change what it masks. Each run is packed independently by
     * {@link #packSegment}, onto its OWN lane ids: the number of lanes can only go down, never up,
     * and no id is invented.</p>
     */
    private static <T> int packFamily(
            @NonNull List<T> items,
            @NonNull java.util.function.ToLongFunction<T> startFn,
            @NonNull java.util.function.ToLongFunction<T> endFn,
            @NonNull java.util.function.Function<T, String> laneOf,
            @NonNull java.util.function.BiConsumer<T, String> assignLane,
            @NonNull List<String> bandBottomToTop,
            @NonNull java.util.Set<String> exemptLanes) {
        if (items.size() < 2) return 0;
        Map<String, List<T>> byLane = new LinkedHashMap<>();
        for (T item : items) {
            byLane.computeIfAbsent(laneOf.apply(item), k -> new ArrayList<>()).add(item);
        }
        if (byLane.size() < 2) return 0;
        // Lanes this family occupies, bottom → top. The band is the authority on order; anything
        // it somehow does not list (it lists every inhabited lane by construction) trails behind
        // in discovery order rather than being dropped.
        List<String> lanes = new ArrayList<>();
        for (String id : bandBottomToTop) {
            if (byLane.containsKey(id)) lanes.add(id);
        }
        for (String id : byLane.keySet()) {
            if (!lanes.contains(id)) lanes.add(id);
        }
        int moved = 0;
        int i = 0;
        while (i < lanes.size()) {
            if (exemptLanes.contains(lanes.get(i))) { i++; continue; }
            int j = i;
            while (j < lanes.size() && !exemptLanes.contains(lanes.get(j))) j++;
            moved += packSegment(lanes.subList(i, j), byLane, startFn, endFn, laneOf, assignLane);
            i = j;
        }
        return moved;
    }

    /**
     * Pack one contiguous run of lanes, bottom lane first.
     *
     * <p>Items are visited in paint order (lane by lane, then each lane's backing-list order, which
     * IS its within-lane z). Each item takes the LOWEST lane that (a) is free for its whole time
     * range and (b) is not below any already-placed item it overlaps in time. Rule (b) is what keeps
     * z honest: two items that never coexist on screen may swap lanes freely, two that do may not.
     * Because every item's original lane satisfies both rules, the search always succeeds inside the
     * run — but if it somehow did not, the whole run is abandoned unchanged rather than spilling
     * into a new lane, so this can never grow the band.</p>
     */
    private static <T> int packSegment(
            @NonNull List<String> lanes,
            @NonNull Map<String, List<T>> byLane,
            @NonNull java.util.function.ToLongFunction<T> startFn,
            @NonNull java.util.function.ToLongFunction<T> endFn,
            @NonNull java.util.function.Function<T, String> laneOf,
            @NonNull java.util.function.BiConsumer<T, String> assignLane) {
        if (lanes.size() < 2) return 0;
        List<T> ordered = new ArrayList<>();
        for (String id : lanes) {
            List<T> bucket = byLane.get(id);
            if (bucket != null) ordered.addAll(bucket);
        }
        int n = ordered.size();
        long[] starts = new long[n];
        long[] ends = new long[n];
        int[] target = new int[n];
        List<List<long[]>> occupancy = new ArrayList<>();
        for (int k = 0; k < lanes.size(); k++) occupancy.add(new ArrayList<>());
        for (int idx = 0; idx < n; idx++) {
            T item = ordered.get(idx);
            starts[idx] = startFn.applyAsLong(item);
            ends[idx] = Math.max(starts[idx], endFn.applyAsLong(item));
            int floor = 0;
            for (int p = 0; p < idx; p++) {
                if (overlapsInTime(starts[p], ends[p], starts[idx], ends[idx])) {
                    floor = Math.max(floor, target[p]);
                }
            }
            int placed = -1;
            for (int k = floor; k < lanes.size(); k++) {
                if (laneIsFree(occupancy.get(k), starts[idx], ends[idx])) { placed = k; break; }
            }
            if (placed < 0) return 0; // unreachable in practice — abandon the run, change nothing
            target[idx] = placed;
            occupancy.get(placed).add(new long[]{starts[idx], ends[idx]});
        }
        int moved = 0;
        for (int idx = 0; idx < n; idx++) {
            String want = lanes.get(target[idx]);
            T item = ordered.get(idx);
            if (!want.equals(laneOf.apply(item))) { assignLane.accept(item, want); moved++; }
        }
        return moved;
    }

    /** Half-open overlap: two items that merely BUTT (one ends where the next starts) do not. */
    private static boolean overlapsInTime(long aStart, long aEnd, long bStart, long bEnd) {
        return rangesOverlap(aStart, aEnd, bStart, bEnd);
    }

    private static boolean laneIsFree(@NonNull List<long[]> occupied, long s, long e) {
        for (long[] iv : occupied) {
            if (overlapsInTime(iv[0], iv[1], s, e)) return false;
        }
        return true;
    }

    /** Open-ended sprite occupies its lane forever (mirrors the old inline guard). */
    private static long spriteEndForPacking(@NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem o) {
        long e = o.getEndMs();
        return (e == Long.MAX_VALUE || e <= o.getStartMs()) ? Long.MAX_VALUE : e;
    }

    /** Open-ended adjustment layer (duration <= 0) occupies its lane forever. */
    private static long adjustmentEndForPacking(@NonNull AdjustmentLayer a) {
        return a.getDurationMs() <= 0L ? Long.MAX_VALUE : a.getEndMs();
    }

    /** A lane definition {@link #compactOverlayLanes()} deleted, with the index it was removed from. */
    public static final class RemovedLane {
        @NonNull public final LayerTrackDef def;
        /** Its index in the definition list before removal — {@code restoreLayerTrackDefAt} needs it. */
        public final int index;

        public RemovedLane(@NonNull LayerTrackDef def, int index) {
            this.def = def;
            this.index = index;
        }
    }

    /** Outcome of {@link #compactOverlayLanes()}. */
    public static final class CompactResult {
        /** How many items actually changed lane (0 = already minimal). */
        public final int moved;
        /** How many distinct lanes were left alone because they take part in a track matte. */
        public final int omittedLanes;
        /**
         * The empty lane definitions that were deleted, oldest index first — reported so the toast
         * can say so honestly, and so undo can put them back where they were.
         */
        @NonNull public final List<RemovedLane> removedLanes;

        public CompactResult(int moved, int omittedLanes) {
            this(moved, omittedLanes, Collections.emptyList());
        }

        public CompactResult(int moved, int omittedLanes, @NonNull List<RemovedLane> removedLanes) {
            this.moved = moved;
            this.omittedLanes = omittedLanes;
            this.removedLanes = removedLanes;
        }
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

    // The index bookkeeping below lives in TransitionIndex so it can be exercised by the JVM
    // harness without dragging in Clip (and android.net.Uri). See that class for why these
    // in-place mutations are undo-hazardous.

    public void removeTransitionsForDeletedClip(int clipIndex) {
        TransitionIndex.removeForDeletedClip(transitions, clipIndex);
    }

    public void shiftTransitionsAfterInsert(int insertIndex) {
        TransitionIndex.shiftAfterInsert(transitions, insertIndex);
    }

    /** Exact inverse of {@link #shiftTransitionsAfterInsert} — see TransitionIndex. */
    public void unshiftTransitionsAfterInsert(int insertIndex) {
        TransitionIndex.unshiftAfterInsert(transitions, insertIndex);
    }

    public void shiftTransitionsAfterSplit(int splitIndex) {
        TransitionIndex.shiftAfterSplit(transitions, splitIndex);
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
        return TransitionIndex.snapshot(transitions);
    }

    /** Restore a {@link #snapshotTransitions()} result, replacing the current list. */
    public void restoreTransitions(@NonNull List<Transition> snapshot) {
        TransitionIndex.restore(transitions, snapshot);
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
    /** SPEC_NEUTRAL_SUBSTRATE validation 5/6/7 only — see the probe at the end of getLayers(). */
    private static final boolean LANEPROBE = false;

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
        // M3. Every project written before adjustment layers has an EMPTY map here, so every
        // phase below behaves exactly as it did — the emitted band is provably identical.
        Map<String, List<AdjustmentLayer>> adjustmentsByLayer = new LinkedHashMap<>();
        for (AdjustmentLayer al : adjustmentLayers) {
            String id = al.getLayerId().isEmpty() ? "adjustment" : al.getLayerId();
            adjustmentsByLayer.computeIfAbsent(id, k -> new ArrayList<>()).add(al);
        }
        LaneBuckets buckets = new LaneBuckets(textsByLayer, spritesByLayer, videosByLayer,
                adjustmentsByLayer);
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

        // ADJUSTMENT phase — emitted AFTER video/PiP so a new adjustment lane defaults ABOVE
        // the PiPs. That is the After Effects reading JoyRaptor described: "grade everything I have
        // built so far". With no adjustment layers the maps are empty and nothing is emitted,
        // so the band is unchanged for every project that predates the feature.
        if (buckets.adjustments.containsKey("adjustment")) {
            layers.add(buildLaneTrack("adjustment", TrackKind.ADJUSTMENT, "Adjustment", buckets));
        }
        emitDefs(layers, buckets, TrackKind.ADJUSTMENT, null);
        flushLeftovers(layers, buckets, buckets.adjustments, defIds, TrackKind.ADJUSTMENT,
                "Adjustment");

        emitDefs(layers, buckets, TrackKind.LAYER, null);
        // Belt-and-braces: an id owned by a def of a kind no phase emits (CAPTION/
        // VISUALIZER/MASTER — nothing creates those defs today) would have been skipped by
        // every flush above, so surface it rather than silently dropping its items. Empty
        // in every reachable case.
        for (String orphanId : buckets.remainingIds()) {
            layers.add(buildLaneTrack(orphanId, TrackKind.LAYER, "Layer", buckets));
        }

        sortBandByZIndex(layers); // PHASE-P P2: row order follows persisted zIndex
        // LANEPROBE (SPEC_NEUTRAL_SUBSTRATE validation 5/6/7): lane headers are CANVAS-drawn, so
        // row names and kinds are invisible to uiautomator and unreadable from a screenshot. This
        // is the only way to assert what a drop actually produced. Debug-gated and off by default;
        // flip LANEPROBE to true when running those items.
        if (LANEPROBE) {
            StringBuilder sb = new StringBuilder();
            for (Track t : layers) {
                sb.append('[').append(t.getId()).append(" name=").append(t.getName())
                  .append(" kind=").append(t.getKind())
                  .append(" items=").append(t.getItems().size()).append(']');
            }
            android.util.Log.d("LANEPROBE", layers.size() + " rows " + sb);
        }
        return layers;
    }

    /**
     * The three layerId→items groupings {@link #getLayers()} routes from, bundled so a
     * lane can be built from ALL of them by id (neutral substrate: membership is the
     * layerId, never the payload's backing list). Consuming a lane REMOVES its id from
     * every map, so each item lands in exactly one row and whatever is left over at the
     * end is by definition an orphan id.
     */
    /**
     * The three lane ids that are NOT owned by a {@link LayerTrackDef} but are still
     * emitted by a phase of their own in {@link #getLayers()} (the seeded Text/Sprite/PiP
     * rows). A per-phase leftover flush must skip them for the same reason it skips
     * def-owned ids: an EARLIER phase's flush would otherwise claim the lane, emit it
     * under that phase's kind + generic name, and consume it before its own branch runs.
     * Reachable the moment the neutral substrate lets a payload sit on another type's
     * seeded lane — e.g. a text on the "video" lane renamed the PiP row to "Text" and
     * hoisted it above the sprite rows, which under cross-type Z is a silent z change.
     */
    private static final java.util.Set<String> SEEDED_LANE_IDS =
            java.util.Collections.unmodifiableSet(
                    new java.util.HashSet<>(java.util.Arrays.asList("text", "sprite", "video")));

    private static final class LaneBuckets {
        final Map<String, List<TextOverlayItem>> texts;
        final Map<String, List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem>> sprites;
        final Map<String, List<Clip>> videos;
        /** M3: adjustment layers, emitted in their own phase ABOVE the video/PiP one. */
        final Map<String, List<AdjustmentLayer>> adjustments;

        LaneBuckets(@NonNull Map<String, List<TextOverlayItem>> texts,
                @NonNull Map<String, List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem>> sprites,
                @NonNull Map<String, List<Clip>> videos,
                @NonNull Map<String, List<AdjustmentLayer>> adjustments) {
            this.texts = texts;
            this.sprites = sprites;
            this.videos = videos;
            this.adjustments = adjustments;
        }

        /** True if ANY payload type has items for {@code id}. */
        boolean hasItems(@NonNull String id) {
            return texts.containsKey(id) || sprites.containsKey(id) || videos.containsKey(id)
                    || adjustments.containsKey(id);
        }

        /** Every id still unconsumed, in text→sprite→video discovery order, deduped. */
        @NonNull
        List<String> remainingIds() {
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(texts.keySet());
            ids.addAll(sprites.keySet());
            ids.addAll(videos.keySet());
            ids.addAll(adjustments.keySet());
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
            if (SEEDED_LANE_IDS.contains(id)) continue; // its own phase emits it later
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
        List<AdjustmentLayer> ab = buckets.adjustments.remove(id);
        if (ab != null) {
            for (AdjustmentLayer al : ab) track.addItem(TimedItem.ofAdjustment(al));
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
        int n = clips.size();
        // Find max bindings across clips (capped at 3).
        int maxBindings = 0;
        for (int i = 0; i < n; i++) {
            Clip c = clips.get(i);
            if (c != null) maxBindings = Math.max(maxBindings, c.getCaptionBindings().size());
        }
        // Legacy fallback: bindings empty but old single-track would have shown something (e.g. failed migration).
        if (maxBindings == 0) {
            List<TimedItem> legacy = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Clip c = clips.get(i);
                if (c != null && c.isCaptionsEnabled() && c.hasTranscript()) {
                    long start = segmentStartMs(i);
                    long end = (i + 1 < n) ? segmentStartMs(i + 1) : Long.MAX_VALUE;
                    legacy.add(TimedItem.ofCaptionSpan(
                            new com.fadcam.ui.faditor.layers.CaptionSpanRef(c, i, start, end, 0)));
                }
            }
            if (legacy.isEmpty()) return Collections.emptyList();
            Track track = new Track("caption", TrackKind.CAPTION, "CC");
            for (TimedItem it : legacy) track.addItem(it);
            applyTrackFlags(track);
            return Collections.singletonList(track);
        }
        List<Track> out = new ArrayList<>();
        for (int b = 0; b < maxBindings && b < Clip.MAX_CAPTION_BINDINGS; b++) {
            List<TimedItem> items = new ArrayList<>();
            String label = null;
            for (int i = 0; i < n; i++) {
                Clip c = clips.get(i);
                if (c == null) continue;
                java.util.List<Clip.CaptionBinding> bs = c.getCaptionBindings();
                if (b >= bs.size()) continue;
                Clip.CaptionBinding bd = bs.get(b);
                if (!bd.enabled) continue;
                com.fadcam.ui.faditor.transcript.NamedTranscript nt = c.transcriptForBinding(bd);
                if (nt == null || nt.transcript == null || nt.transcript.isEmpty()) continue;
                long start = segmentStartMs(i);
                long end = (i + 1 < n) ? segmentStartMs(i + 1) : Long.MAX_VALUE;
                items.add(TimedItem.ofCaptionSpan(
                        new com.fadcam.ui.faditor.layers.CaptionSpanRef(c, i, start, end, b)));
                if (label == null) label = bd.label;
            }
            if (items.isEmpty()) continue;
            if (label == null || label.isEmpty()) label = "CC " + (b + 1);
            else if (maxBindings == 1 && "Captions".equals(label)) label = "CC";
            Track track = new Track("caption-" + b, TrackKind.CAPTION, label);
            for (TimedItem it : items) track.addItem(it);
            applyTrackFlags(track);
            out.add(track);
        }
        if (out.isEmpty()) return Collections.emptyList();
        return out;
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
    /**
     * Absolute on-timeline start (ms) of the master clip at {@code index} — the PUBLIC form of
     * {@link #segmentStartMs}, added for rider attachment (PLAN_TIMELINE_MANIPULATION_V1 §2.0).
     *
     * <p>Exists because the only other implementation of this sum lives on
     * {@code EditorTimelineView}, and an anchor hook must not depend on a View — the AI and undo
     * paths mutate the clip list with no editor attached at all.</p>
     */
    public long getClipStartMs(int index) {
        return segmentStartMs(index);
    }

    /**
     * Snapshot of every master clip's start, keyed by clip id — the "before" half of the
     * capture → mutate → diff cycle that drives every anchored rider.
     *
     * <p><b>Why a snapshot rather than a hook on the mutators.</b> There is no clip start FIELD to
     * observe; a start is a prefix sum, so it changes whenever any earlier clip changes membership,
     * order, in/out point, speed or loop extent. And one user action calls several primitives — a
     * split is {@code remove + add + add} — so a per-primitive hook would fire on a half-mutated
     * list and shift a rider two or three times. Capture at the ACTION boundary, diff after.</p>
     *
     * <p>Keyed by ID, not index, because the AI reorder path clears and rebuilds the whole list
     * ({@code EditScriptApplier.applyReorderClips}) and split mints fresh UUIDs — an index-keyed
     * diff silently mis-pairs riders in both cases.</p>
     */
    /**
     * Open a structural bracket: {@link #captureClipStarts()}, plus a note of WHICH bracket is the
     * outermost one, so that an edit nested inside another edit does not shift the riders twice.
     *
     * <p><b>Why this is needed at all.</b> The editor brackets undo and redo wholesale
     * ({@code performUndo}), and some actions — {@code TrimAction} — also bracket themselves so they
     * stay correct when the AI or a script runs them with no editor around. Both are right alone and
     * wrong together: each computes the SAME delta from the same length change, so the rider travels
     * twice as far as its footage — as wrong as never moving it, and harder to spot because it looks
     * like the ripple is working.</p>
     *
     * <p><b>Keyed on the returned map's identity, not on a depth count.</b> Two of the editor's
     * bracket sites can return early between begin and end (a demote or promote that declines), and
     * a counter left +1 by such a path would silently switch ripple off for the rest of the session
     * — a latched, invisible failure of exactly the kind this file has been bitten by before. With
     * ownership, the outermost bracket still matches when it closes, applies its shift, and clears
     * the flag; a leaked inner bracket costs nothing.</p>
     *
     * <p>Callers that only want to READ starts should keep calling {@link #captureClipStarts()};
     * this pair is for the capture → mutate → shift cycle.</p>
     */
    @NonNull
    public Map<String, Long> beginStructural() {
        Map<String, Long> before = captureClipStarts();
        if (structuralOwner == null) structuralOwner = before;
        return before;
    }

    /**
     * Close a structural bracket, applying {@link #applyAnchorShift} only for the outermost one.
     *
     * <p>A nested level returns an EMPTY result rather than null: the caller's contract is "here is
     * what moved", and nothing moved at this level because the outer bracket has not run yet.</p>
     *
     * <p>A map that never came from {@link #beginStructural()} (no bracket is open) is applied
     * directly — that is the standalone contract the harness and the AI paths rely on.</p>
     */
    @NonNull
    public AnchorShiftResult endStructural(@NonNull Map<String, Long> beforeStarts) {
        if (structuralOwner != null && structuralOwner != beforeStarts) {
            return new AnchorShiftResult();       // nested; the outermost bracket owns the shift
        }
        structuralOwner = null;
        return applyAnchorShift(beforeStarts);
    }

    @NonNull
    public Map<String, Long> captureClipStarts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < clips.size(); i++) out.put(clips.get(i).getId(), segmentStartMs(i));
        return out;
    }

    /** On-timeline span of a master clip — public form of {@link #clipSpanMs}. */
    public long getClipSpanMs(int index) {
        return (index < 0 || index >= clips.size()) ? 0L : clipSpanMs(clips.get(index));
    }

    /**
     * Attach {@code o} to the master clip under its own start, capturing the host-relative offset.
     * No-op with no clips. Returns the host id, or null if the overlay sits past the last clip
     * (which stays UNANCHORED — absolute time — per §4A).
     *
     * <p>Deliberately unlike {@code attachVisualizerToHostUnderStart}, which falls back to the LAST
     * clip for an item past the end. That divergence is a policy difference, not an inconsistency:
     * a visualizer is bound to a clip's audio and must have one, while a layer item beyond the
     * timeline is legitimately free-floating. Both behaviours are pinned in their harnesses so
     * neither gets "corrected" into the other.</p>
     */
    @Nullable
    public String attachOverlayToHostUnderStart(@NonNull TextOverlayItem o) {
        int idx = hostIndexForTime(o.getStartMs());
        if (idx < 0) {
            o.setHostAnchor(null, 0L);
            return null;
        }
        long hostStart = segmentStartMs(idx);
        o.setHostAnchor(clips.get(idx).getId(),
                AnchorMath.offsetWithinHost(o.getStartMs(), hostStart, clipSpanMs(clips.get(idx))));
        return clips.get(idx).getId();
    }

    /**
     * Index of the master clip covering {@code timeMs} under the HALF-OPEN rule, or -1.
     * Delegates to {@link AnchorMath} so the editor, the export and the harness cannot drift
     * into three different answers about which clip owns a seam.
     */
    public int hostIndexForTime(long timeMs) {
        int n = clips.size();
        long[] spans = new long[n];
        for (int i = 0; i < n; i++) spans[i] = clipSpanMs(clips.get(i));
        return AnchorMath.hostIndexForStart(AnchorMath.startsFromSpans(spans), spans, timeMs);
    }

    /**
     * Apply the consequences of a structural master edit to every anchored rider — the "after"
     * half of {@link #captureClipStarts()}.
     *
     * <p>Riders whose host survived are shifted by THAT host's delta, per policy. Riders whose
     * host is GONE are returned in {@link AnchorShiftResult#orphanedOverlayIds} and are NOT
     * touched: §4A makes that a user-facing choice (re-anchor vs delete), and this method must not
     * pre-empt it.</p>
     *
     * <p><b>UNANCHORED riders ripple too, in ripple mode.</b> A host anchor and a ripple answer
     * different questions: an anchor survives REORDERING, which no time-shift can, while ripple
     * covers everything that changes LENGTH at a time T. An overlay with no host sits at absolute
     * time, so without this it stays put while the footage under it slides — one trim silently
     * desynchronises the rest of the project, which is the failure users do not forgive. Such a
     * rider is shifted by the delta of the clip whose OLD span contained its start, reconstructed
     * from {@code beforeStarts}.</p>
     *
     * <p><b>In GAP mode nothing is shifted at all</b> — that is what the mode means (the
     * industry's per-track sync lock, expressed project-wide). Orphans are still REPORTED, because
     * a deleted host dangles an anchor whatever the mode, and answering that is the user's call.</p>
     *
     * <p><b>Known limit: an IN-point trim does not move riders inside the clip being trimmed.</b>
     * Dragging a clip's START moves that clip's own content left or right underneath any rider
     * sitting in it, but the clip's START TIME does not change, so there is no delta here to apply
     * — {@code beforeStarts} records starts, and this one did not move. Riders in LATER clips are
     * unaffected by the distinction and shift correctly. Out-point trims, the common case and the
     * one this was built for, are exact. The shipped anchored-rider path has always behaved this
     * way too, so the two agree; fixing it needs the per-clip in-point delta captured alongside
     * the starts.</p>
     *
     * <p><b>Known limit.</b> A rider starting past the LAST clip's old start moves with that clip.
     * If only the last clip's own length changes, no start changes at all and such a rider does not
     * move — {@code beforeStarts} records starts, not spans, so the old end of the timeline is not
     * reconstructible. Nothing plays out there, so the desync is invisible; if that ever needs
     * fixing, capture the old total duration alongside the starts rather than guessing here.</p>
     *
     * <p>Returns what moved so the caller can build ONE undo step and — per §4A's no-silent-repair
     * rule — tell the user when the editor moved something they did not.</p>
     */
    @NonNull
    public AnchorShiftResult applyAnchorShift(@NonNull Map<String, Long> beforeStarts) {
        Map<String, Long> after = captureClipStarts();
        AnchorShiftResult res = new AnchorShiftResult();
        boolean ripple = !"gap".equals(rippleMode);
        for (TextOverlayItem o : textOverlays) {
            String host = o.getHostClipId();
            if (host == null) {
                if (!ripple) continue;
                long delta = unanchoredDeltaAt(o.getStartMs(), beforeStarts, after);
                if (delta == 0) continue;
                o.setTimeRange(AnchorMath.shiftStart(o.getStartMs(), delta),
                        AnchorMath.shiftEnd(o.getEndMs(), delta));
                res.movedOverlayIds.add(o.getId());
                continue;
            }
            Long newStart = after.get(host);
            if (newStart == null) { res.orphanedOverlayIds.add(o.getId()); continue; }
            if (!ripple) {
                // GAP MODE: the rider stays at its time — but if its host MOVED, the anchor no
                // longer describes that time, so re-home it to whatever clip is under it now.
                // Skipping this left start and host+offset disagreeing (found in a real project
                // 2026-09-24: an image kept 7293 ms against an anchor that now said 3224 ms, after
                // a slice's first half moved to a lane and the tape closed up). The preview and
                // the export then read different times for the same object.
                Long was = beforeStarts.get(host);
                if (was != null && !was.equals(newStart)) attachOverlayToHostUnderStart(o);
                continue;
            }
            Long oldStart = beforeStarts.get(host);
            if (oldStart == null) continue;           // host is new; nothing to shift relative to
            long delta = newStart - oldStart;
            if (delta == 0) continue;
            int idx = indexOfMasterClipId(host);
            long[] win = AnchorMath.shiftRider(o.getStartMs(), o.getEndMs(), delta,
                    RiderPolicy.SHIFT_ONLY, newStart,
                    idx < 0 ? 0L : clipSpanMs(clips.get(idx)));
            o.setTimeRange(win[0], win[1]);
            res.movedOverlayIds.add(o.getId());
        }
        if (ripple) rippleEverythingElse(beforeStarts, after, res);
        return res;
    }

    /**
     * The rest of the timeline's objects — sprites, PiP/overlay clips, audio clips, adjustment
     * layers and free-standing visualizers — travel with their footage too.
     *
     * <p><b>Why they need their own pass.</b> Only {@link TextOverlayItem} can carry a host anchor,
     * so every other object family sits at absolute time with no mechanism at all to follow an edit.
     * A trim early in the tape left a PiP, a music bed and an adjustment layer sitting over
     * different footage than the user placed them on — the same failure as the unanchored overlay,
     * on four more object kinds, and just as silent.</p>
     *
     * <p><b>Attached visualizers are skipped ON PURPOSE.</b> They re-derive their window from their
     * host's current span in {@link #resyncAttachedVisualizers()}, which every edit path already
     * calls. That is a recompute rather than a delta, so shifting them here would move them twice.
     * Only detached ones — which nothing else would ever move — are rippled.</p>
     */
    private void rippleEverythingElse(@NonNull Map<String, Long> beforeStarts,
                                      @NonNull Map<String, Long> after,
                                      @NonNull AnchorShiftResult res) {
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteOverlays) {
            long d = unanchoredDeltaAt(s.getStartMs(), beforeStarts, after);
            if (d == 0) continue;
            s.setTimeRange(AnchorMath.shiftStart(s.getStartMs(), d),
                    AnchorMath.shiftEnd(s.getEndMs(), d));
            res.movedObjectIds.add(s.getId());
        }
        for (Clip pip : overlayClips) {
            long d = unanchoredDeltaAt(pip.getOverlayStartMs(), beforeStarts, after);
            if (d == 0) continue;
            pip.setOverlayStartMs(AnchorMath.shiftStart(pip.getOverlayStartMs(), d));
            res.movedObjectIds.add(pip.getId());
        }
        for (AudioClip a : audioClips) {
            long d = unanchoredDeltaAt(a.getOffsetMs(), beforeStarts, after);
            if (d == 0) continue;
            a.setOffsetMs(AnchorMath.shiftStart(a.getOffsetMs(), d));
            res.movedObjectIds.add(a.getId());
        }
        for (AdjustmentLayer al : adjustmentLayers) {
            long d = unanchoredDeltaAt(al.getStartMs(), beforeStarts, after);
            if (d == 0) continue;
            // Only the start moves: setDurationMs is a LENGTH, and a ripple does not stretch.
            al.setStartMs(AnchorMath.shiftStart(al.getStartMs(), d));
            res.movedObjectIds.add(al.getId());
        }
        for (WaveformOverlayInstance w : waveformOverlays) {
            if (w.getAttachedClipId() != null) continue;   // resynced from its host; see the doc
            long d = unanchoredDeltaAt(w.getStartMs(), beforeStarts, after);
            if (d == 0) continue;
            w.setTimeRange(AnchorMath.shiftStart(w.getStartMs(), d),
                    AnchorMath.shiftEnd(w.getEndMs(), d));
            res.movedObjectIds.add(w.getId());
        }
    }

    /**
     * How far an UNANCHORED rider starting at {@code startMs} must travel: the delta of the last
     * SURVIVING clip whose old start was at or before it.
     *
     * <p>"Last at or before" is the same containment rule {@link AnchorMath#hostIndexForStart} uses,
     * expressed on the old starts alone — the old spans are the differences between consecutive old
     * starts, so asking which old span contained the rider and asking which old start last preceded
     * it are the same question. Deleted clips are skipped rather than resolved: a rider stranded in
     * a region that no longer exists takes the delta of the surviving clip before it, so it lands at
     * the seam that replaced its footage instead of jumping somewhere unrelated. A rider that
     * precedes every survivor does not move — there is no earlier edit to have displaced it.</p>
     *
     * <p>Chooses by comparing old starts rather than by trusting iteration order: the map arrives
     * from a caller, and one that hands over a plain {@code HashMap} would otherwise silently pick
     * whichever clip happened to hash last.</p>
     */
    private long unanchoredDeltaAt(long startMs,
                                   @NonNull Map<String, Long> beforeStarts,
                                   @NonNull Map<String, Long> after) {
        long delta = 0L;
        long best = Long.MIN_VALUE;
        for (Map.Entry<String, Long> e : beforeStarts.entrySet()) {
            long oldStart = e.getValue();
            if (oldStart > startMs || oldStart <= best) continue;
            Long newStart = after.get(e.getKey());
            if (newStart == null) continue;           // clip is gone; it has no delta to lend
            best = oldStart;
            delta = newStart - oldStart;
        }
        return delta;
    }

    /** What {@link #applyAnchorShift} did — the input to one undo step and to the user-facing notice. */
    public static final class AnchorShiftResult {
        /** Text/image overlays that moved — with their host, or with their footage if unanchored. */
        @NonNull public final List<String> movedOverlayIds = new ArrayList<>();
        /** Riders whose host no longer exists. UNRESOLVED — §4A's prompt decides their fate. */
        @NonNull public final List<String> orphanedOverlayIds = new ArrayList<>();
        /**
         * Everything else that rippled: sprites, PiP clips, audio clips, adjustment layers,
         * detached visualizers. Kept apart from {@link #movedOverlayIds} because callers resolve
         * ORPHANS against that list, and only a text overlay can carry a host anchor to orphan.
         */
        @NonNull public final List<String> movedObjectIds = new ArrayList<>();

        public boolean isEmpty() {
            return movedOverlayIds.isEmpty() && orphanedOverlayIds.isEmpty()
                    && movedObjectIds.isEmpty();
        }
    }

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
     * The link-engine kind of the object with this id ("clip" for an OVERLAY clip, "textOverlay",
     * "audioClip", "sprite", "waveform"), or null for a master clip or an unknown id. Used by the
     * group move (SPEC_20260924_LINKING §7), which shifts any mix of these by one time delta.
     */
    @androidx.annotation.Nullable
    public String movableKindOfId(@NonNull String id) {
        for (Clip oc : overlayClips) if (id.equals(oc.getId())) return "clip";
        for (TextOverlayItem t : textOverlays) if (id.equals(t.getId())) return "textOverlay";
        for (AudioClip a : audioClips) if (id.equals(a.getId())) return "audioClip";
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteOverlays) {
            if (id.equals(s.getId())) return "sprite";
        }
        for (WaveformOverlayInstance w : waveformOverlays) if (id.equals(w.getId())) return "waveform";
        return null;
    }

    /**
     * Point every follower at its parent object (SPEC_20260924_LINKING). Run after a load and
     * after anything that adds or removes objects. A parent that is gone leaves the link inert
     * (drawn at its own pose), never a crash. Parents: text, images and sprites.
     */
    public void resolveSpaceLinks() {
        java.util.Map<String, LinkPose> byId = new java.util.HashMap<>();
        for (TextOverlayItem t : textOverlays) byId.put(t.getId(), t);
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteOverlays) byId.put(s.getId(), s);
        for (WaveformOverlayInstance w : waveformOverlays) byId.put(w.getId(), w);
        for (LinkFollower f : linkFollowers()) {
            SpaceLink l = f.getSpaceLink();
            if (l == null) continue;
            LinkPose p = byId.get(l.parentId);
            l.parentRef = (p == f || wouldCycle(f, p, byId)) ? null : p;
        }
    }

    /** Everything that can follow a parent: text, pictures and sprites. */
    @NonNull
    public java.util.List<LinkFollower> linkFollowers() {
        java.util.List<LinkFollower> out = new java.util.ArrayList<>(textOverlays);
        out.addAll(spriteOverlays);
        out.addAll(waveformOverlays);
        return out;
    }

    /** The follower with this id, or null. */
    @androidx.annotation.Nullable
    public LinkFollower linkFollowerById(@NonNull String id) {
        for (LinkFollower f : linkFollowers()) if (f.getId().equals(id)) return f;
        return null;
    }

    /** Would following {@code parent} make {@code child} its own ancestor? */
    public boolean wouldCycle(@NonNull LinkFollower child, @androidx.annotation.Nullable LinkPose parent,
                              @NonNull java.util.Map<String, LinkPose> byId) {
        LinkPose cur = parent;
        for (int guard = 0; cur != null && guard < 64; guard++) {
            if (cur.getId().equals(child.getId())) return true;
            if (!(cur instanceof LinkFollower)) return false;
            SpaceLink l = ((LinkFollower) cur).getSpaceLink();
            cur = l == null ? null : byId.get(l.parentId);
        }
        return cur != null;
    }

    /** The object a link can point at, by id: a text, image or sprite; null otherwise. */
    @androidx.annotation.Nullable
    public LinkPose linkPoseById(@NonNull String id) {
        for (TextOverlayItem t : textOverlays) if (t.getId().equals(id)) return t;
        for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem s : spriteOverlays) {
            if (s.getId().equals(id)) return s;
        }
        for (WaveformOverlayInstance w : waveformOverlays) if (w.getId().equals(id)) return w;
        return null;
    }

    /**
     * MOVE one object's start, keeping its length, as a user drag would: after the time
     * changes, a text box or visualizer is re-homed onto the clip now under its start, exactly
     * as the single-item drag does. Without that the old anchor's offset pulls it back on the
     * next anchor pass, and an undo appears to land in the wrong place.
     */
    public void moveStartMs(@NonNull String kind, @NonNull String id, long startMs) {
        applyLinkStartMs(kind, id, startMs);
        if ("textOverlay".equals(kind)) {
            for (TextOverlayItem t : textOverlays) {
                if (id.equals(t.getId())) { attachOverlayToHostUnderStart(t); return; }
            }
        } else if ("waveform".equals(kind)) {
            for (WaveformOverlayInstance w : waveformOverlays) {
                if (id.equals(w.getId())) { attachVisualizerToHostUnderStart(w); return; }
            }
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
        // SPEC W item 2: adjustment layers were MISSING here, so a user ADJUSTMENT
        // track still holding them could be pruned by maybeRemoveEmptyLayerTrack,
        // orphaning its layers into defensive leftover buckets.
        for (AdjustmentLayer a : adjustmentLayers) {
            if (trackId.equals(a.getLayerId())) return true;
        }
        return false;
    }
}
