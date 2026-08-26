package com.fadcam.ui.faditor.compositor;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.layers.Track;
import com.fadcam.ui.faditor.layers.TrackKind;
import com.fadcam.ui.faditor.layers.TimedItem;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * PLAN §3.2 (M-COMP-1): the single authority deciding, from {@code timeline.getLayers()} /
 * {@code timeline.getAudioTracks()} + the M6 {@code TrackFlags} side-table at the current
 * playhead, WHAT each existing preview overlay surface should show.
 *
 * <p>This does NOT introduce new rendering machinery. It models the existing View overlay
 * stack ({@code TextOverlayLayer}, the audio {@code MediaPlayer} volume paths, and the new
 * {@link LayerImageOverlayView}) as tracks and feeds them filtered data through their
 * existing setters. {@code FaditorEditorActivity} calls these pure query methods from its
 * existing sync/playhead paths ({@code syncTimelineOverlays()}, {@code updateCurrentTimeDisplay()},
 * {@code refreshEditorAfterUndoRedo()}) instead of growing new logic inline.</p>
 *
 * <p>Stateless / no fields — every method takes the current {@link Timeline} and derives its
 * answer fresh, mirroring the "synchronized-from-flat, never cached" contract {@code Timeline}
 * itself documents for {@code getLayers()}/{@code getAudioTracks()}. This guarantees a plain
 * project (no hidden/muted flags, no image tracks) is a pure pass-through: {@link Track#isHidden()}
 * and {@link Track#isMuted()} are {@code false} by construction when no {@code TrackFlags} entry
 * exists (see {@code Timeline#applyTrackFlags}), so every filter below no-ops.</p>
 */
public final class LayerPreviewController {

    private LayerPreviewController() { }

    // ── Z1: the single visual ordering (SPEC_CROSSTYPE_Z) ─────────────────────────

    /**
     * One visible item, with the lane it came from — the unit of
     * {@link #orderedVisualItems}.
     */
    public static final class VisualItem {
        @NonNull public final TimedItem item;
        @NonNull public final Track lane;

        VisualItem(@NonNull TimedItem item, @NonNull Track lane) {
            this.item = item;
            this.lane = lane;
        }
    }

    /**
     * Z1 (SPEC_CROSSTYPE_Z): EVERY visible visual item, across every floating lane and every
     * payload type, in one bottom→top paint order — lanes ascending by
     * {@link Track#getZIndex()} (stable, so equal-z lanes keep {@code getLayers()} emission
     * order), then items in lane order.
     *
     * <p>This is the ordering the three {@code visible*} methods below are now derived from,
     * which is the point: they used to each re-derive it, so the ONLY thing keeping their z
     * agreement honest was that three copies of the same six lines stayed in sync. Now there
     * is one copy. Hidden lanes and per-object eyes are applied here, once.</p>
     *
     * <p>It is also the foundation for cross-type z: today each consumer filters this list
     * down to its own payload type, which reproduces the historical per-type surfaces exactly;
     * SPEC_CROSSTYPE_Z's Z2 partitions this same list instead of filtering it.</p>
     */
    @NonNull
    public static List<VisualItem> orderedVisualItems(@NonNull Timeline timeline) {
        List<Track> lanes = new ArrayList<>(timeline.getLayers());
        // Per-item zHint is deliberately NOT consulted: TimedItem views are rebuilt with
        // default zHint=0 on every getLayers() call (M5 ephemeral-views note), so within a
        // lane, insertion order IS the z order today.
        //
        // BAND POSITION IS THE TIE-BREAK, AND IT RUNS BACKWARDS. The editor draws the band
        // top-down straight from getLayers() (FaditorEditorActivity builds layerBand as an
        // unsorted copy), so band index 0 is the TOP row. moveTrackZ states the contract for
        // that: "zIndex = (n-1-i) so the top row carries the highest z" — top row paints LAST.
        //
        // Explicit reorders satisfy it. The DEFAULT case did not, and the default case is
        // almost every project: TrackFlags are pruned when they hold defaults, so a project
        // that never had a lane moved by hand stores no zIndex at all — verified on JoyRaptor's
        // main project, whose trackFlags map is empty. Every lane then answers 0, a plain
        // stable sort is a no-op, and the lanes paint in emission order: band index 0 painted
        // FIRST, i.e. at the BOTTOM. Exactly inverted from the row order the user is looking
        // at, which is what he reported: "the top one is showing above in the timeline, and
        // yet it is being covered in the preview".
        //
        // Tie-breaking on DESCENDING band index restores the contract for equal-z lanes (last
        // row paints first, top row paints last) and cannot disturb explicitly-ordered lanes,
        // where the zIndex comparison decides before the tie-break is ever consulted.
        final java.util.Map<String, Integer> bandPos = new java.util.HashMap<>();
        for (int i = 0; i < lanes.size(); i++) bandPos.put(lanes.get(i).getId(), i);
        lanes.sort(java.util.Comparator
                .comparingInt(Track::getZIndex)
                .thenComparing(t -> -bandPos.getOrDefault(((Track) t).getId(), 0)));
        List<VisualItem> out = new ArrayList<>();
        for (Track lane : lanes) {
            if (lane.isHidden()) continue; // mirrored on export — these methods are shared
            for (TimedItem item : lane.getItems()) {
                if (isObjectHidden(item)) continue; // §4.5 per-OBJECT eye
                out.add(new VisualItem(item, lane));
            }
        }
        return out;
    }

    /**
     * Every item that takes part in the COMPOSITED image — PiP clips and adjustment layers —
     * in one bottom→top order (SPEC_ADJUSTMENT_LAYERS_FX M3).
     *
     * <p><b>ONE call, consumed by BOTH the export loop and the preview wrapper builder.</b> That
     * is the entire point, and it is the reason already written on
     * {@link #partitionAroundVideo}: giving each side its own helper is how they drift. Export
     * iterates this to build its effect chain, where position literally IS z; preview iterates
     * the same list to decide what sits beneath a given layer. If they ever answered
     * differently, an adjustment layer would grade one set of objects on screen and a different
     * set in the file.</p>
     *
     * <p><b>Merged, not concatenated.</b> PiPs and adjustment layers are interleaved in the ONE
     * ordering rather than appended as two groups — a project with a layer between two PiPs is
     * exactly the case a concatenation would z-invert, which is precisely the bug the PiP
     * z-unification fix was written to repair.</p>
     *
     * <p>Returns items in {@link #orderedVisualItems} order, so hidden lanes and per-object eyes
     * are already applied, once, by the same code every other consumer uses.</p>
     */
    @NonNull
    public static List<VisualItem> orderedCompositedItems(@NonNull Timeline timeline) {
        List<VisualItem> out = new ArrayList<>();
        for (VisualItem v : orderedVisualItems(timeline)) {
            com.fadcam.ui.faditor.model.Clip clip = v.item.getClip();
            boolean isPip = clip != null && clip.isOverlayClip();
            boolean isAdjustment = v.item.getAdjustment() != null;
            if (isPip || isAdjustment) out.add(v);
        }
        return out;
    }

    /**
     * The adjustment layers live at {@code editorMs}, bottom→top — mirroring
     * {@code visibleOverlayVideoClips}, and derived from the same ordering so the two cannot
     * disagree about which is on top.
     *
     * <p>A layer whose stack is empty or entirely disabled is EXCLUDED: it is a real object the
     * user can see and move, but it must cost no render pass, or every adjustment layer would
     * buy a full-screen copy for nothing.</p>
     */
    @NonNull
    public static List<com.fadcam.ui.faditor.model.AdjustmentLayer> visibleAdjustmentLayers(
            @NonNull Timeline timeline, long editorMs) {
        List<com.fadcam.ui.faditor.model.AdjustmentLayer> out = new ArrayList<>();
        for (VisualItem v : orderedCompositedItems(timeline)) {
            com.fadcam.ui.faditor.model.AdjustmentLayer a = v.item.getAdjustment();
            if (a != null && a.activeAt(editorMs) && a.rendersAnything()) out.add(a);
        }
        return out;
    }

    /**
     * Z2 (SPEC_CROSSTYPE_Z): does this item paint UNDER the PiP video surface?
     *
     * <p>The two-bucket model. The overlay-video surface is one plane that cannot be split
     * per item (it is a live decoder, not a canvas draw), so the question every other item
     * answers is binary: in front of it, or behind it. An item is BEHIND when its lane sits
     * below the highest PiP-bearing lane.</p>
     *
     * <p><b>Inert by construction.</b> With no PiP present there is no plane to be behind, and
     * with every zIndex at its default 0 no lane is strictly below another, so the "below"
     * bucket is empty and every consumer sees exactly today's ordering. The feature only turns
     * on when a user deliberately orders a lane beneath a PiP lane — which is precisely the
     * gesture that does nothing today.</p>
     *
     * <p>Ties go ABOVE deliberately: equal zIndex means the user never expressed an ordering,
     * and the historical stack draws overlays over video. So "no opinion" keeps today's look
     * rather than silently sending content behind the video.</p>
     */
    public static boolean paintsBelowVideo(@NonNull Timeline timeline, @NonNull VisualItem item) {
        return item.lane.getZIndex() < topPipLaneZ(timeline);
    }

    /**
     * Highest zIndex among lanes that hold a visible overlay (PiP) clip, or
     * {@link Integer#MIN_VALUE} when there is no PiP at all — which makes
     * {@link #paintsBelowVideo} false for everything, i.e. today's behavior.
     */
    public static int topPipLaneZ(@NonNull Timeline timeline) {
        int top = Integer.MIN_VALUE;
        for (VisualItem v : orderedVisualItems(timeline)) {
            com.fadcam.ui.faditor.model.Clip clip = v.item.getClip();
            if (clip != null && clip.isOverlayClip() && v.lane.getZIndex() > top) {
                top = v.lane.getZIndex();
            }
        }
        return top;
    }

    /**
     * Z2: the ordering split into the two paint buckets — {@code [0]} = behind the video
     * surface, {@code [1]} = in front of it. Both preserve {@link #orderedVisualItems} order,
     * so within a bucket the existing per-type consumers behave exactly as they do now.
     *
     * <p>Returned as one call because preview and export must consume the SAME split; giving
     * each side its own partition helper is how they would drift.</p>
     */
    @NonNull
    public static List<List<VisualItem>> partitionAroundVideo(@NonNull Timeline timeline) {
        // Walk the ordering ONCE. orderedVisualItems() rebuilds every lane view from the flat
        // lists (getLayers is not cached), and this runs on every preview sync, so deriving
        // pipZ via topPipLaneZ() here would double that cost for no benefit.
        List<VisualItem> ordered = orderedVisualItems(timeline);
        int pipZ = Integer.MIN_VALUE;
        for (VisualItem v : ordered) {
            com.fadcam.ui.faditor.model.Clip clip = v.item.getClip();
            if (clip != null && clip.isOverlayClip() && v.lane.getZIndex() > pipZ) {
                pipZ = v.lane.getZIndex();
            }
        }
        List<VisualItem> below = new ArrayList<>();
        List<VisualItem> above = new ArrayList<>();
        for (VisualItem v : ordered) {
            com.fadcam.ui.faditor.model.Clip clip = v.item.getClip();
            // The PiPs themselves ARE the plane — they belong to neither bucket.
            if (clip != null && clip.isOverlayClip()) continue;
            (v.lane.getZIndex() < pipZ ? below : above).add(v);
        }
        List<List<VisualItem>> out = new ArrayList<>(2);
        out.add(below);
        out.add(above);
        return out;
    }

    /**
     * Z3 (SPEC_CROSSTYPE_Z): what the ABOVE-video text surface shows — the existing
     * {@code TextOverlayLayer}. Identical to {@link #visibleTextOverlays} until a lane is
     * deliberately ordered beneath a PiP lane, because until then the below bucket is empty.
     */
    @NonNull
    public static List<TextOverlayItem> visibleTextOverlaysAboveVideo(@NonNull Timeline timeline) {
        return textsIn(partitionAroundVideo(timeline).get(1));
    }

    /** Z3: what the BELOW-video text surface shows. Empty for every project that has not
     *  ordered a lane under a PiP lane — the surface then draws nothing. */
    @NonNull
    public static List<TextOverlayItem> visibleTextOverlaysBelowVideo(@NonNull Timeline timeline) {
        return textsIn(partitionAroundVideo(timeline).get(0));
    }

    /** Z3: sprites on the ABOVE-video surface. See {@link #visibleTextOverlaysAboveVideo}. */
    @NonNull
    public static List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> visibleSpriteItemsAboveVideo(
            @NonNull Timeline timeline) {
        return spritesIn(partitionAroundVideo(timeline).get(1));
    }

    /** Z3: sprites on the BELOW-video surface. */
    @NonNull
    public static List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> visibleSpriteItemsBelowVideo(
            @NonNull Timeline timeline) {
        return spritesIn(partitionAroundVideo(timeline).get(0));
    }

    /** The text/sticker payloads of a {@link #partitionAroundVideo} bucket, in bucket order. */
    @NonNull
    public static List<TextOverlayItem> textsIn(@NonNull List<VisualItem> bucket) {
        List<TextOverlayItem> out = new ArrayList<>();
        for (VisualItem v : bucket) {
            TextOverlayItem o = v.item.getTextOverlay();
            if (o != null) out.add(o);
        }
        return out;
    }

    /** The sprite payloads of a {@link #partitionAroundVideo} bucket, in bucket order. */
    @NonNull
    public static List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> spritesIn(
            @NonNull List<VisualItem> bucket) {
        List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> out = new ArrayList<>();
        for (VisualItem v : bucket) {
            com.fadcam.ui.faditor.sprite.SpriteOverlayItem s = v.item.getSprite();
            if (s != null) out.add(s);
        }
        return out;
    }

    /**
     * §4.5 per-object eye, for whichever payload this item carries. Kept next to
     * {@link #orderedVisualItems} so preview and export skip the same objects by construction.
     */
    private static boolean isObjectHidden(@NonNull TimedItem item) {
        TextOverlayItem overlay = item.getTextOverlay();
        if (overlay != null) return overlay.isHidden();
        com.fadcam.ui.faditor.sprite.SpriteOverlayItem sprite = item.getSprite();
        if (sprite != null) return sprite.isHidden();
        com.fadcam.ui.faditor.model.Clip clip = item.getClip();
        if (clip != null) return clip.isHiddenObject();
        return false;
    }

    // ── Hidden TEXT/STICKER tracks → filtered TextOverlayLayer input ──────────────

    /**
     * The list to feed {@code TextOverlayLayer#setData}/{@code #rebuild}: every visible
     * {@link TextOverlayItem}, on any lane, in paint order.
     *
     * <p>M-EXPORT-1: the SHARED authority for both the live preview
     * ({@code TextOverlayLayer#setData} call sites) AND the export path
     * ({@code ExportManager#assembleClipVideoEffects} → {@code CompositeExportOverlay}), so
     * visibility and draw-order decisions cannot diverge between the two. A plain project has
     * one unhidden lane, so this returns {@code timeline.getTextOverlays()} unchanged — same
     * objects, same order.</p>
     *
     * <p>NEUTRAL SUBSTRATE: a lane's KIND is not a filter — any lane may hold any visual
     * payload, so this selects by PAYLOAD out of the single ordering ({@link
     * #orderedVisualItems}, which also applies lane-hidden and the per-object eye). Cross-type
     * z within a lane is still the fixed global surface stack (overlay video under sprite
     * under text); {@code SPEC_CROSSTYPE_Z} is what changes that.</p>
     */
    // NOTE (Z3): the PREVIEW no longer calls this — its surfaces are fed the bucketed
    // ...AboveVideo/...BelowVideo variants, so an item is drawn by exactly one of them.
    // This whole-set query is kept because "every visible text overlay, in paint order",
    // independent of which side of the video plane it lands on, is the right question for a
    // consumer that spans both — the merged hit-testing upgrade the spec's Z3 note describes.
    @NonNull
    public static List<TextOverlayItem> visibleTextOverlays(@NonNull Timeline timeline) {
        List<TextOverlayItem> result = new ArrayList<>();
        for (VisualItem v : orderedVisualItems(timeline)) {
            TextOverlayItem overlay = v.item.getTextOverlay();
            if (overlay != null) result.add(overlay);
        }
        return result;
    }

    // ── IMAGE tracks → LayerImageOverlayView input ─────────────────────────────────

    /**
     * Every {@link TimedItem} from a visible (not hidden) IMAGE track, across all IMAGE
     * tracks, for {@link LayerImageOverlayView#setItems}. Always empty today — nothing
     * can create an IMAGE track yet (PLAN §3.2 scope item 4) — so this is inert for every
     * current project; it exists so M10's creation UI has a working preview path already.
     */
    @NonNull
    public static List<TimedItem> visibleImageItems(@NonNull Timeline timeline) {
        List<Track> layers = timeline.getLayers();
        List<TimedItem> result = new ArrayList<>();
        for (Track track : layers) {
            if (track.getKind() != TrackKind.IMAGE) continue;
            if (track.isHidden()) continue; // TODO(M-EXPORT-1): mirror this skip in ExportManager.
            result.addAll(track.getItems());
        }
        return result;
    }

    // ── SPRITE tracks → SpriteOverlayView input ────────────────────────────────────

    /**
     * The list to feed {@code SpriteOverlayView#setData}: every
     * {@link com.fadcam.ui.faditor.sprite.SpriteOverlayItem} from a visible (not
     * hidden) SPRITE track, in zIndex-sorted track order (same authority rule as
     * {@link #visibleTextOverlays} — S6 export must call THIS method too, so
     * preview/export visibility cannot diverge). A project with no sprites returns
     * an empty list — the overlay view draws nothing and passes touches through.
     */
    /** Whole-set sprite query — see the note on {@link #visibleTextOverlays}. */
    @NonNull
    public static List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> visibleSpriteItems(
            @NonNull Timeline timeline) {
        // Payload-selected out of the single ordering (Z1) — see visibleTextOverlays.
        List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> result = new ArrayList<>();
        for (VisualItem v : orderedVisualItems(timeline)) {
            com.fadcam.ui.faditor.sprite.SpriteOverlayItem sprite = v.item.getSprite();
            if (sprite != null) result.add(sprite);
        }
        return result;
    }

    // ── TEXT/SPRITE below a blending/masked GL IMAGE → promote to GL (gap close) ───

    /**
     * Plain TEXT overlays (non-image, not hidden) whose lane sits below a GL-routed
     * IMAGE overlay. Those texts are stranded on Canvas while the blend above lives
     * in GL, so the blend composites against video instead of the text below — the
     * same split described for plainImagesBelowBlend but for text.
     */
    @NonNull
    public static List<TextOverlayItem> plainTextsBelowBlend(
            @NonNull Timeline timeline,
            @NonNull List<TextOverlayItem> glImages) {
        if (glImages.isEmpty()) return java.util.Collections.emptyList();
        List<VisualItem> ordered = orderedVisualItems(timeline);
        java.util.Map<String, Integer> idxById = new java.util.HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            TextOverlayItem o = ordered.get(i).item.getTextOverlay();
            if (o != null && o.isImage()) idxById.put(o.getId(), i);
        }
        int maxBlendIdx = -1;
        for (TextOverlayItem g : glImages) {
            Integer idx = idxById.get(g.getId());
            if (idx != null && idx > maxBlendIdx) maxBlendIdx = idx;
        }
        if (maxBlendIdx <= 0) return java.util.Collections.emptyList();
        List<TextOverlayItem> out = new ArrayList<>();
        for (int i = 0; i < maxBlendIdx; i++) {
            TextOverlayItem o = ordered.get(i).item.getTextOverlay();
            if (o == null || o.isImage() || o.isHidden()) continue;
            out.add(o);
        }
        return out;
    }

    /**
     * Plain SPRITE overlays whose lane sits below a GL-routed IMAGE overlay — the
     * sprite twin of plainTextsBelowBlend.
     */
    @NonNull
    public static List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> plainSpritesBelowBlend(
            @NonNull Timeline timeline,
            @NonNull List<TextOverlayItem> glImages) {
        if (glImages.isEmpty()) return java.util.Collections.emptyList();
        List<VisualItem> ordered = orderedVisualItems(timeline);
        java.util.Map<String, Integer> idxById = new java.util.HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            TextOverlayItem o = ordered.get(i).item.getTextOverlay();
            if (o != null && o.isImage()) idxById.put(o.getId(), i);
        }
        int maxBlendIdx = -1;
        for (TextOverlayItem g : glImages) {
            Integer idx = idxById.get(g.getId());
            if (idx != null && idx > maxBlendIdx) maxBlendIdx = idx;
        }
        if (maxBlendIdx <= 0) return java.util.Collections.emptyList();
        List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> out = new ArrayList<>();
        for (int i = 0; i < maxBlendIdx; i++) {
            com.fadcam.ui.faditor.sprite.SpriteOverlayItem s = ordered.get(i).item.getSprite();
            if (s == null || s.isHidden()) continue;
            out.add(s);
        }
        return out;
    }

        // ── Visualizer overlays → WaveformOverlayView / export slots ───────────────────

    /**
     * §4.5: every visualizer instance whose per-OBJECT eye is open. The SHARED authority
     * for the preview overlay view AND ExportManager's waveform slots (same rule as
     * {@link #visibleTextOverlays}) — visualizers ride a flat instance list rather than
     * track membership, so this is an instance-level filter only.
     */
    @NonNull
    public static List<com.fadcam.ui.faditor.model.WaveformOverlayInstance>
            visibleWaveformOverlays(@NonNull Timeline timeline) {
        List<com.fadcam.ui.faditor.model.WaveformOverlayInstance> result = new ArrayList<>();
        for (com.fadcam.ui.faditor.model.WaveformOverlayInstance w
                : timeline.getWaveformOverlays()) {
            if (!w.isHidden()) result.add(w);
        }
        return result;
    }

    // ── VIDEO (overlay/PiP) tracks → OverlayVideoPreviewView input ─────────────────

    /**
     * The list to feed {@link OverlayVideoPreviewView#setClips}: every overlay
     * {@link com.fadcam.ui.faditor.model.Clip} from a visible (not hidden) VIDEO
     * track, in zIndex-sorted track order, bottom→top (same authority rule as
     * {@link #visibleTextOverlays} — M-EXPORT-2's overlay-video sequence must
     * consume THIS method too, so preview/export visibility cannot diverge; the
     * current M-EXPORT-1 {@code buildOverlayVideoSequence} iterates tracks itself
     * and must be migrated when M-EXPORT-2 lands). Empty for every project without
     * PiP clips — the overlay view releases its decoder and passes touches through.
     */
    @NonNull
    public static List<com.fadcam.ui.faditor.model.Clip> visibleOverlayVideoClips(
            @NonNull Timeline timeline) {
        // Payload-selected out of the single ordering (Z1) — see visibleTextOverlays.
        List<com.fadcam.ui.faditor.model.Clip> result = new ArrayList<>();
        for (VisualItem v : orderedVisualItems(timeline)) {
            com.fadcam.ui.faditor.model.Clip clip = v.item.getClip();
            if (clip != null && clip.isOverlayClip()) result.add(clip);
        }
        return result;
    }

    /**
     * The ids of every visible overlay clip that is currently SERVING as another clip's
     * luma matte ({@code CompositingSpec.mattePeerId}). A matte peer's pixels exist only
     * as the recipient's alpha, so it must not also render as a PiP of its own — see
     * {@code CompositingSpec} B3.
     *
     * <p>Derived from {@link #visibleOverlayVideoClips}, so a peer hidden by its lane's
     * eye stops serving and stops appearing at the same instant. A DANGLING id (the
     * recipient names a clip that no longer exists) lands in the set harmlessly: nothing
     * matches it, and the recipient degrades to unmatted rather than to a broken export.</p>
     */
    @NonNull
    public static java.util.Set<String> servingMatteClipIds(@NonNull Timeline timeline) {
        return servingMatteClipIds(visibleOverlayVideoClips(timeline));
    }

    /**
     * List-precomputed overload — the export video path already holds the visible list
     * (it resolves each peer OUT of it) and must not walk the timeline twice.
     */
    @NonNull
    public static java.util.Set<String> servingMatteClipIds(
            @NonNull List<com.fadcam.ui.faditor.model.Clip> visibleOverlays) {
        java.util.Set<String> serving = new java.util.HashSet<>();
        for (com.fadcam.ui.faditor.model.Clip c : visibleOverlays) {
            com.fadcam.ui.faditor.model.CompositingSpec cs = c.getCompositing();
            if (cs != null && cs.mattePeerId != null) serving.add(cs.mattePeerId);
        }
        return serving;
    }

    /**
     * {@link #visibleOverlayVideoClips} MINUS the clips serving as mattes: the list for
     * anything that renders a PiP as itself, or that derives from a PiP being rendered —
     * the preview's {@code OverlayVideoPreviewView} and the export's overlay AUDIO
     * sequence.
     *
     * <p>The video export path deliberately does NOT use this: it needs the peers in
     * hand to resolve each recipient's matte, and applies {@link #servingMatteClipIds}
     * itself as it walks. So this is the shared authority for "shows up as a PiP",
     * while {@link #visibleOverlayVideoClips} stays the authority for "is visible at
     * all". Two divergences were paid for by conflating them: the preview drew the
     * stencil clip as a normal PiP, and — worse, because it survived into the exported
     * file — a matte peer with {@code overlayAudioEnabled} still contributed SOUND to
     * the export while its picture was hidden.</p>
     */
    @NonNull
    public static List<com.fadcam.ui.faditor.model.Clip> renderableOverlayVideoClips(
            @NonNull Timeline timeline) {
        List<com.fadcam.ui.faditor.model.Clip> visible = visibleOverlayVideoClips(timeline);
        java.util.Set<String> serving = servingMatteClipIds(visible);
        if (serving.isEmpty()) return visible;   // every project without a track matte
        List<com.fadcam.ui.faditor.model.Clip> result = new ArrayList<>();
        for (com.fadcam.ui.faditor.model.Clip c : visible) {
            if (!serving.contains(c.getId())) result.add(c);
        }
        return result;
    }

    // ── Muted AUDIO tracks → per-clip preview-volume gate ──────────────────────────

    /**
     * Whether {@code clip}'s owning AUDIO track is muted (PLAN §3.2 scope item 3).
     * A plain project has one AUDIO track, unmuted by default ({@code Track#isMuted()}
     * is {@code false} unless a {@code TrackFlags} entry says otherwise), so this
     * returns {@code false} for every clip in every project that never touched the M6
     * mute toggle. Track mute MULTIPLIES over the clip's own {@code isMuted()}/volume —
     * callers gate with {@code clip.isMuted() || LayerPreviewController.isTrackMuted(...)},
     * never replacing the clip's own setting.
     */
    public static boolean isAudioClipTrackMuted(@NonNull Timeline timeline, @NonNull AudioClip clip) {
        List<Track> audioTracks = timeline.getAudioTracks();
        for (Track track : audioTracks) {
            if (track.getKind() != TrackKind.AUDIO) continue;
            if (!track.isMuted()) continue;
            for (TimedItem item : track.getItems()) {
                if (item.getAudioClip() == clip) return true;
            }
        }
        return false;
    }

    // ── Overlay (PiP) clip audio — SPEC_PIP_AUDIO ─────────────────────────────────

    /**
     * Whether the FLOATING lane holding {@code clip} is muted. The overlay-clip sibling of
     * {@link #isAudioClipTrackMuted}, which only walks the audio band and only matches
     * {@link AudioClip}s — so without this a muted lane would silence its audio clips but
     * NOT its PiPs, while the row's mute icon (drawn by content since the neutral-substrate
     * work) told the user otherwise.
     */
    public static boolean isOverlayClipLaneMuted(@NonNull Timeline timeline,
            @NonNull com.fadcam.ui.faditor.model.Clip clip) {
        return isOverlayClipLaneMuted(clip, timeline.getLayers());
    }

    /**
     * Lane-precomputed overload. {@code getLayers()} rebuilds every lane view from the flat
     * lists on each call, so a caller looping over many clips (the export audio sequence)
     * must hoist it out of the loop rather than paying that rebuild per clip.
     */
    public static boolean isOverlayClipLaneMuted(@NonNull com.fadcam.ui.faditor.model.Clip clip,
            @NonNull List<Track> lanes) {
        for (Track track : lanes) {
            if (!track.isMuted()) continue;
            for (TimedItem item : track.getItems()) {
                if (item.getClip() == clip) return true;
            }
        }
        return false;
    }

    /**
     * Effective playback/export volume for an overlay (PiP) clip — the SHARED authority for
     * the preview player and the export audio sequence, so the two cannot diverge (same rule
     * as {@link #visibleOverlayVideoClips} for pixels).
     *
     * <p>0 unless the clip has been explicitly opted in ({@code overlayAudioEnabled}): a PiP
     * has always been silent, and making every existing one audible would change exports
     * behind the user's back — and would DOUBLE the voice on a dual-stream pair. On top of
     * that gate, the clip's own mute and its lane's mute both force silence (multiplicative,
     * never replacing the clip's own level) — mirroring
     * {@link #effectivePreviewVolume}'s treatment of audio clips.</p>
     */
    public static float effectiveOverlayVolume(@NonNull Timeline timeline,
            @NonNull com.fadcam.ui.faditor.model.Clip clip) {
        if (!clip.isOverlayAudioEnabled()) return 0f;   // cheap gates first: the common
        if (clip.isAudioMuted()) return 0f;             // case never touches getLayers()
        return effectiveOverlayVolume(clip, timeline.getLayers());
    }

    /** Lane-precomputed overload — see {@link #isOverlayClipLaneMuted(Clip, List)}. */
    public static float effectiveOverlayVolume(@NonNull com.fadcam.ui.faditor.model.Clip clip,
            @NonNull List<Track> lanes) {
        return effectiveOverlayVolumeAt(clip, lanes, Long.MIN_VALUE);
    }

    /**
     * Playhead-aware overload: the same mute/lane gates, but the LEVEL is sampled from the
     * clip's volume envelope at {@code clipLocalMs} so a PiP fade is audible while editing and
     * not only after export. Pass {@link Long#MIN_VALUE} for "no particular time", which reads
     * the envelope's first key — the flat level when there is no envelope.
     *
     * <p>The gates are checked BEFORE the envelope on purpose: a muted clip is silent at every
     * time, and asking the envelope first would make mute depend on where the playhead is.</p>
     */
    public static float effectiveOverlayVolumeAt(@NonNull com.fadcam.ui.faditor.model.Clip clip,
            @NonNull List<Track> lanes, long clipLocalMs) {
        if (!clip.isOverlayAudioEnabled()) return 0f;
        if (clip.isAudioMuted()) return 0f;
        if (isOverlayClipLaneMuted(clip, lanes)) return 0f;
        return clip.volumeAt(clipLocalMs);
    }

    /**
     * Effective preview playback volume for an audio clip: 0 if the clip itself is
     * muted OR its owning track is muted (track-mute multiplies over, never overwrites,
     * the clip's own mute/level per PLAN §3.2 scope item 3), else the clip's own level.
     * Convenience wrapper around {@link #isAudioClipTrackMuted} for the five call sites
     * in {@code FaditorEditorActivity} that already compute
     * {@code ac.isMuted() ? 0f : ac.getVolumeLevel()}.
     */
    public static float effectivePreviewVolume(@NonNull Timeline timeline, @NonNull AudioClip clip) {
        if (clip.isMuted() || isAudioClipTrackMuted(timeline, clip)) return 0f;
        return clip.getVolumeLevel();
    }
}
