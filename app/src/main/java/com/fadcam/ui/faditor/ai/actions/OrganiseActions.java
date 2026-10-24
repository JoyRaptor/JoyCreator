package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Type;
import com.fadcam.ui.faditor.layers.TimedItem;
import com.fadcam.ui.faditor.layers.Track;
import com.fadcam.ui.faditor.layers.TrackKind;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The organise family (gap F2): copy a layer, hide or lock it, move it one row up or down, and
 * drop markers on the time ruler. Each one mirrors a control the Studio already has, on the model
 * only (the host saves once, the open editor adopts the change).
 *
 * <p>Mirrors, by editor method (FaditorEditorActivity):</p>
 * <ul>
 *   <li>duplicate_layer: {@code duplicateSelectedObject} + {@code laneForDuplicate} + {@code
 *       nearbyLaneOr}: a copy gets a new id, lands on the row BELOW the original if it is free over
 *       the copy's time, else ABOVE, else on a new row. A locked layer is refused, because the
 *       Studio asks before copying one and Joybot cannot answer that question.</li>
 *   <li>set_layer_flags: {@code addObjectVisibilityActions} and the per-payload Hide/Lock toggles.
 *       No lock gate: the Studio toggles these directly.</li>
 *   <li>set_layer_order (FORWARD/BACKWARD only): the object menu's {@code moveItemToAdjacentLane}
 *       with {@code stageMoveItemToLayerTrack}'s stored-id rules and {@code maybeRemoveEmptyLayerTrack}.
 *       The Studio has no front/back for an object, so those are not offered.</li>
 *   <li>add_marker / list_markers: {@code FaditorProject.addBookmark} / {@code getBookmarksMs}, the
 *       model side of the ruler's long-press ({@code EditorTimelineView.toggleBookmarkNear}).</li>
 * </ul>
 */
final class OrganiseActions {
    private OrganiseActions() { }

    private static final String FAMILY = "organise";
    private static final String LAYER_HELP = "Which layer: its id, or its name or words";
    private static final List<String> DIRECTIONS = Arrays.asList("FORWARD", "BACKWARD");

    /**
     * Two markers closer than this are the same marker. The Studio's own tolerance follows the zoom
     * ({@code EditorTimelineView.bookmarkTolMs}); this is its fallback value, since Joybot has no zoom.
     */
    private static final long MARKER_TOL_MS = 200L;

    /** On: add_marker / list_markers are registered. Off hides them (their sidecar handling is in StudioActions). */
    private static final boolean MARKERS_PERSIST = true;
    /**
     * add_marker stays OFF (2026-10-15): markers live in bookmarks.json, and an undo step restores
     * project.json only, so Undo would leave Joybot's marker behind — and every Joybot change must
     * be one undo step. Turn on once markers are part of the editor's undo snapshot.
     */
    private static final boolean MARKER_ADD_UNDOABLE = false;

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("duplicate_layer", "Copy a layer")
                .help("Make a copy of a text, picture, sprite, adjustment or video layer. The copy starts at the"
                        + " same time as the original, on the row beside it (or a new row if there is no room),"
                        + " and gets its own id. Visualizers can't be copied. Locked layers must be unlocked first.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › select a layer › Duplicate in the tool strip")
                .id("layer", "layer", true, LAYER_HELP)
                .build(), OrganiseActions::duplicate);

        r.add(ActionSpec.of("set_layer_flags", "Hide or lock a layer")
                .help("Hide a layer so it doesn't show in the picture (hidden: true), show it again (hidden: false),"
                        + " or lock it so it can't be changed by accident (locked: true) or unlock it. Send only"
                        + " what should change.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › tap an object › its menu › Hide / Lock")
                .id("layer", "layer", true, LAYER_HELP)
                .flag("hidden", false, "true to hide it, false to show it again")
                .flag("locked", false, "true to lock it, false to unlock it")
                .build(), OrganiseActions::setFlags);

        r.add(ActionSpec.of("set_layer_order", "Move a layer up or down a row")
                .help("Move a layer one row up (FORWARD: it is drawn over the rows below) or one row down (BACKWARD)."
                        + " One step per call. Visualizers can't be moved to another row.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › tap an object › its menu › Move layer up / down")
                .id("layer", "layer", true, LAYER_HELP)
                .choice("to", true, "FORWARD = one row up, BACKWARD = one row down", DIRECTIONS)
                .build(), OrganiseActions::moveOneRow);

        // Markers live in the bookmarks.json sidecar (BookmarkStore), not project.json. StudioActions
        // loads that sidecar before an action and writes it back after the save; an open editor takes
        // it in swapInProject, so neither a save nor the editor drops a marker.
        if (MARKERS_PERSIST && MARKER_ADD_UNDOABLE) r.add(ActionSpec.of("add_marker", "Drop a marker on the ruler")
                .help("Put a marker on the time ruler at a moment, so the person can find it again. No marker is"
                        + " added if one is already within a fraction of a second of that moment.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › long-press the time ruler")
                .param("at", Type.TIME_MS, true, "Where on the timeline to drop it")
                .build(), OrganiseActions::addMarker);

        if (MARKERS_PERSIST) r.add(ActionSpec.of("list_markers", "List the markers")
                .help("The markers on the time ruler, in time order.")
                .wing("STUDIO", FAMILY).effect(Effect.READ)
                .where("Studio › the time ruler")
                .build(), OrganiseActions::listMarkers);
    }

    // ── handlers ─────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult duplicate(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Timeline tl = ctx.project.getTimeline();
        Hit h = find(tl, a.str("layer"));
        if (h.kind == Kind.VISUALIZER) {
            throw new ActionError("A visualizer can't be copied in the Studio, so it can't be copied here either.");
        }
        if (h.locked()) {
            throw new ActionError(cap(h.describe()) + " is locked. Unlock it first, then copy it.");
        }
        String copyId;
        boolean beside;
        if (h.kind == Kind.TEXT) {
            TextOverlayItem copy = h.text.copyWithNewId(UUID.randomUUID().toString());
            String near = laneNear(tl, h.id, copy.getStartMs(), copy.getEndMs());
            beside = near != null;
            copy.setLayerId(beside ? near : tl.createLayerTrack(TrackKind.TEXT, "Text"));
            tl.addTextOverlay(copy);
            copyId = copy.getId();
        } else if (h.kind == Kind.SPRITE) {
            SpriteOverlayItem copy = h.sprite.copyWithNewId(UUID.randomUUID().toString());
            String near = laneNear(tl, h.id, copy.getStartMs(), copy.getEndMs());
            beside = near != null;
            copy.setLayerId(beside ? near : tl.createLayerTrack(TrackKind.SPRITE, "Sprite"));
            tl.addSpriteOverlay(copy);
            copyId = copy.getId();
        } else if (h.kind == Kind.ADJUSTMENT) {
            AdjustmentLayer copy = h.adjustment.copy();
            copy.setId(UUID.randomUUID().toString());
            copy.setName(h.adjustment.getName() + " copy");
            String near = laneNear(tl, h.id, copy.getStartMs(), copy.getEndMs());
            beside = near != null;
            copy.setLayerId(beside ? near : tl.createLayerTrack(TrackKind.ADJUSTMENT, "Adjustment"));
            tl.addAdjustmentLayer(copy);
            copyId = copy.getId();
        } else {
            // Video layer (picture-in-picture clip). The copy constructor mints the new id.
            Clip copy = new Clip(h.clip);
            long start = copy.getOverlayStartMs();
            String near = laneNear(tl, h.id, start, start + Math.max(0, copy.getTrimmedDurationMs()));
            beside = near != null;
            copy.setLayerId(beside ? near : tl.createLayerTrack(TrackKind.LAYER, "Layer"));
            tl.addOverlayClip(copy);
            copyId = copy.getId();
        }
        return ActionResult.changed("Made a copy of " + h.describe() + (beside
                        ? ", on the row beside it." : ", on a new row."),
                Collections.singletonList(copyId));
    }

    @NonNull
    private static ActionResult setFlags(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Hit h = find(ctx.project.getTimeline(), a.str("layer"));
        if (!a.has("hidden") && !a.has("locked")) {
            throw new ActionError("Say whether it should be hidden, locked, or both.");
        }
        boolean changed = false;
        List<String> now = new ArrayList<>();
        if (a.has("hidden")) {
            boolean want = a.bool("hidden");
            if (h.hidden() != want) {
                h.setHidden(want);
                changed = true;
            }
            now.add(want ? "hidden" : "shown");
        }
        if (a.has("locked")) {
            boolean want = a.bool("locked");
            if (h.locked() && !want && !PersonSaid.asked("\\bunlock") && !PersonSaid.yesTo("unlock")) {
                // Locked means "don't change this by accident": only the person unlocks it — in
                // their own words, or "yes" to Joybot asking (a free model unlocked a title on its
                // own and moved it, Note 9 2026-10-23).
                throw new ActionError("Waiting for your OK to unlock it.\nNot done: " + h.describe() + " is locked"
                        + " and only the person can unlock it. Ask them whether to unlock it and stop; unlock it"
                        + " only if they say so.");
            }
            if (h.locked() != want) {
                h.setLocked(want);
                changed = true;
            }
            now.add(want ? "locked" : "unlocked");
        }
        String state = join(now, " and ");
        if (!changed) return ActionResult.unchanged(cap(h.describe()) + " is already " + state + ".");
        return ActionResult.changed(cap(h.describe()) + " is now " + state + ".",
                Collections.singletonList(h.id));
    }

    @NonNull
    private static ActionResult moveOneRow(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Timeline tl = ctx.project.getTimeline();
        Hit h = find(tl, a.str("layer"));
        if (h.kind == Kind.VISUALIZER) {
            throw new ActionError("A visualizer can't be moved to another row in the Studio.");
        }
        boolean up = "FORWARD".equals(a.choice("to", DIRECTIONS));
        List<Track> rows = tl.getLayers();          // row 0 = top = drawn over the rest
        String from = rowIdOf(h);
        int row = -1;
        for (int i = 0; from != null && i < rows.size(); i++) {
            if (rows.get(i).getId().equals(from)) { row = i; break; }
        }
        if (row < 0) throw new ActionError(cap(h.describe()) + " isn't on a row, so it can't move.");
        int target = row + (up ? -1 : 1);
        if (target < 0 || target >= rows.size()) {
            return ActionResult.unchanged(cap(h.describe()) + " is already " + (up ? "at the top" : "at the bottom")
                    + " of the rows.");
        }
        String to = rows.get(target).getId();
        // Stored the way the Studio stores it (stageMoveItemToLayerTrack): text and sprite keep null
        // for their own default row; a video or adjustment layer always stores the row id.
        switch (h.kind) {
            case TEXT: h.text.setLayerId("text".equals(to) ? null : to); break;
            case SPRITE: h.sprite.setLayerId("sprite".equals(to) ? null : to); break;
            case ADJUSTMENT: h.adjustment.setLayerId(to); break;
            default: h.clip.setLayerId(to); break;
        }
        removeRowIfEmpty(tl, from);
        return ActionResult.changed("Moved " + h.describe() + (up ? " up" : " down") + " one row.",
                Collections.singletonList(h.id));
    }

    @NonNull
    private static ActionResult addMarker(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Long at = a.whole("at");
        long total = ctx.project.getTimeline().getTotalDurationMs();
        if (at == null || at < 0 || (total > 0 && at > total)) {
            throw new ActionError("A marker has to be on the timeline, between 0:00 and "
                    + StudioCtx.clock(Math.max(0, total)) + ".");
        }
        for (long b : ctx.project.getBookmarksMs()) {
            if (Math.abs(b - at) <= MARKER_TOL_MS) {
                return ActionResult.unchanged("There's already a marker at " + StudioCtx.clock(b) + ".");
            }
        }
        ctx.project.addBookmark(at, MARKER_TOL_MS);
        return ActionResult.changed("Added a marker at " + StudioCtx.clock(at) + ".",
                Collections.<String>emptyList());
    }

    @NonNull
    private static ActionResult listMarkers(@NonNull StudioCtx ctx, @NonNull Args a) {
        List<Long> marks = ctx.project.getBookmarksMs();
        if (marks.isEmpty()) return ActionResult.unchanged("There are no markers yet.");
        StringBuilder sb = new StringBuilder("Markers at ");
        for (int i = 0; i < marks.size(); i++) {
            sb.append(i > 0 ? ", " : "").append(StudioCtx.clock(marks.get(i)));
        }
        return ActionResult.unchanged(sb.append('.').toString());
    }

    // ── layers: finding them and what they hold ──────────────────────────────

    private enum Kind { TEXT, SPRITE, ADJUSTMENT, VIDEO, VISUALIZER }

    /** One layer object of any kind the Studio can show in a row. */
    private static final class Hit {
        final Kind kind;
        final String id;
        final TextOverlayItem text;
        final SpriteOverlayItem sprite;
        final AdjustmentLayer adjustment;
        final Clip clip;
        final WaveformOverlayInstance wave;

        Hit(Kind kind, String id, @Nullable TextOverlayItem text, @Nullable SpriteOverlayItem sprite,
            @Nullable AdjustmentLayer adjustment, @Nullable Clip clip, @Nullable WaveformOverlayInstance wave) {
            this.kind = kind; this.id = id; this.text = text; this.sprite = sprite;
            this.adjustment = adjustment; this.clip = clip; this.wave = wave;
        }

        boolean hidden() {
            switch (kind) {
                case TEXT: return text.isHidden();
                case SPRITE: return sprite.isHidden();
                case ADJUSTMENT: return adjustment.isHidden();
                case VIDEO: return clip.isHiddenObject();
                default: return wave.isHidden();
            }
        }

        void setHidden(boolean v) {
            switch (kind) {
                case TEXT: text.setHidden(v); break;
                case SPRITE: sprite.setHidden(v); break;
                case ADJUSTMENT: adjustment.setHidden(v); break;
                case VIDEO: clip.setHiddenObject(v); break;
                default: wave.setHidden(v); break;
            }
        }

        boolean locked() {
            switch (kind) {
                case TEXT: return text.isLocked();
                case SPRITE: return sprite.isLocked();
                case ADJUSTMENT: return adjustment.isLocked();
                case VIDEO: return clip.isLockedObject();
                default: return wave.isLocked();
            }
        }

        void setLocked(boolean v) {
            switch (kind) {
                case TEXT: text.setLocked(v); break;
                case SPRITE: sprite.setLocked(v); break;
                case ADJUSTMENT: adjustment.setLocked(v); break;
                case VIDEO: clip.setLockedObject(v); break;
                default: wave.setLocked(v); break;
            }
        }

        /** Its name as a person would type it: the words of a text, a grade's name, a video's label. */
        @NonNull
        String name() {
            switch (kind) {
                case TEXT: return text.isImage() || text.getText() == null ? "" : text.getText();
                case ADJUSTMENT: return adjustment.getName();
                case VIDEO: return clip.getLabel() == null ? "" : clip.getLabel();
                default: return "";
            }
        }

        /** Person words for it in replies: "“Title”", "the sprite", "the picture". */
        @NonNull
        String describe() {
            switch (kind) {
                case TEXT: {
                    if (text.isImage()) return "the picture";
                    String w = shorten(text.getText());
                    return w.isEmpty() ? "the text" : "\"" + w + "\"";
                }
                case SPRITE: return "the sprite";
                case ADJUSTMENT: return "the grade \"" + adjustment.getName() + "\"";
                case VIDEO: return name().isEmpty() ? "the video layer" : "\"" + name() + "\"";
                default: return "the visualizer";
            }
        }
    }

    @NonNull
    private static List<Hit> all(@NonNull Timeline tl) {
        List<Hit> out = new ArrayList<>();
        for (TextOverlayItem t : tl.getTextOverlays()) {
            out.add(new Hit(Kind.TEXT, t.getId(), t, null, null, null, null));
        }
        for (SpriteOverlayItem s : tl.getSpriteOverlays()) {
            out.add(new Hit(Kind.SPRITE, s.getId(), null, s, null, null, null));
        }
        for (AdjustmentLayer al : tl.getAdjustmentLayers()) {
            out.add(new Hit(Kind.ADJUSTMENT, al.getId(), null, null, al, null, null));
        }
        for (Clip c : tl.getOverlayClips()) {
            if (c.isOverlayClip()) out.add(new Hit(Kind.VIDEO, c.getId(), null, null, null, c, null));
        }
        for (WaveformOverlayInstance w : tl.getWaveformOverlays()) {
            out.add(new Hit(Kind.VISUALIZER, w.getId(), null, null, null, null, w));
        }
        return out;
    }

    /** A layer by id, else by name or words (the same rule as the text actions); ambiguity is an error. */
    @NonNull
    private static Hit find(@NonNull Timeline tl, @NonNull String ref) throws ActionError {
        List<Hit> all = all(tl);
        if (all.isEmpty()) throw new ActionError("This project has no layers.");
        String r = ref.trim();
        for (Hit h : all) if (r.equals(h.id)) return h;
        String want = norm(r);
        List<Hit> exact = new ArrayList<>(), part = new ArrayList<>();
        for (Hit h : all) {
            String n = norm(h.name());
            if (n.isEmpty()) continue;
            if (n.equals(want)) exact.add(h);
            else if (!want.isEmpty() && n.contains(want)) part.add(h);
        }
        List<Hit> hit = !exact.isEmpty() ? exact : part;
        if (hit.size() == 1) return hit.get(0);
        if (hit.isEmpty()) {
            throw new ActionError("No layer is called \"" + r + "\". The layers are: " + list(all) + ".");
        }
        throw new ActionError(hit.size() + " layers match \"" + r + "\": " + list(hit) + ". Ask which one, or use its id.");
    }

    /** The id of the row a layer sits on, named the way Timeline#getLayers names it. */
    @Nullable
    private static String rowIdOf(@NonNull Hit h) {
        switch (h.kind) {
            case TEXT: return h.text.getLayerId() == null ? "text" : h.text.getLayerId();
            case SPRITE: return h.sprite.getLayerId() == null ? "sprite" : h.sprite.getLayerId();
            case ADJUSTMENT: return h.adjustment.getLayerId().isEmpty() ? "adjustment" : h.adjustment.getLayerId();
            case VIDEO: return h.clip.getLayerId();
            default: return null;
        }
    }

    /**
     * The row BELOW the original when it is free over the copy's time, else ABOVE, else null (make a
     * new row). Mirrors FaditorEditorActivity#laneForDuplicate: audio rows and locked rows are never
     * used, and a row is free only if no item on it overlaps the copy.
     */
    @Nullable
    private static String laneNear(@NonNull Timeline tl, @NonNull String originalId, long startMs, long endMs) {
        List<Track> lanes = tl.getLayers();
        int at = -1;
        for (int i = 0; i < lanes.size() && at < 0; i++) {
            for (TimedItem it : lanes.get(i).getItems()) {
                if (originalId.equals(it.getId())) { at = i; break; }
            }
        }
        if (at < 0) return null;
        long total = tl.getTotalDurationMs();
        int[] tries = {at - 1, at + 1};
        for (int to : tries) {
            if (to < 0 || to >= lanes.size()) continue;
            Track lane = lanes.get(to);
            if (lane.isLocked() || lane.getKind() == TrackKind.AUDIO) continue;
            boolean clash = false;
            for (TimedItem other : lane.getItems()) {
                long os = other.getTimelineStartMs();
                long oe = os + Math.max(0, other.getDisplayDurationMs(total));
                if (os < endMs && startMs < oe) { clash = true; break; }
            }
            if (!clash) return lane.getId();
        }
        return null;
    }

    /** Mirrors FaditorEditorActivity#maybeRemoveEmptyLayerTrack: a row the person made, now empty, goes. */
    private static void removeRowIfEmpty(@NonNull Timeline tl, @NonNull String rowId) {
        if ("text".equals(rowId) || "audio".equals(rowId)) return;
        if (tl.getLayerTrackDef(rowId) == null) return;
        if (tl.layerTrackHasItems(rowId)) return;
        tl.removeLayerTrackDef(rowId);
    }

    // ── small helpers ────────────────────────────────────────────────────────

    @NonNull
    private static String norm(@Nullable String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String shorten(@Nullable String s) {
        String w = s == null ? "" : s.replace('\n', ' ').trim();
        return w.length() > 30 ? w.substring(0, 30) + "…" : w;
    }

    @NonNull
    private static String cap(@NonNull String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @NonNull
    private static String list(@NonNull List<Hit> hits) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Hit h : hits) {
            if (n++ == 12) { sb.append(", … ").append(hits.size() - 12).append(" more"); break; }
            if (sb.length() > 0) sb.append(", ");
            sb.append(h.describe()).append(" (id ").append(h.id).append(')');
        }
        return sb.toString();
    }

    @NonNull
    private static String join(@NonNull List<String> l, @NonNull String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() > 0 ? sep : "").append(s);
        return sb.toString();
    }
}
