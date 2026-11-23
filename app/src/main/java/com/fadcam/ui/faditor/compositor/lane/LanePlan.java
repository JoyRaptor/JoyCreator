package com.fadcam.ui.faditor.compositor.lane;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.compositor.LayerPreviewController;
import com.fadcam.ui.faditor.layers.BlendMode;
import com.fadcam.ui.faditor.layers.TimedItem;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * C4a (STU-7, tasks/motionblur/C4_COMPOSITOR_DESIGN.md): the bottom-to-top draw list for TRUE
 * layer order, the input of the shared lane compositor.
 *
 * <p>Built on {@link LayerPreviewController#orderedVisualItems}, which already is the one visual
 * ordering (lane zIndex, band-position tie-break, hidden lanes and per-object eyes applied once).
 * In true order nothing else reorders it: an effect layer changes every entry below it, a PiP
 * sits exactly where its lane is, and text, pictures, sprites and visualisers keep their lane
 * position instead of the legacy fixed surfaces. The spine clip is always the bottom (it is not
 * a lane item) and captions are always on top (they are owned by clips, not lanes), so neither
 * appears in {@link #entries()}.
 *
 * <p>{@link #legacyLooksTheSame} answers whether today's (legacy) pipeline already draws a
 * project in this order. It is deliberately conservative: when it says yes, switching that
 * project to the lane compositor cannot change its look; when unsure it says no and the project
 * keeps the legacy route.
 *
 * <p>Pure: reads the timeline, holds no GL, no time filter (z order does not depend on time;
 * hosts already know which items are live at t).
 */
public final class LanePlan {

    /** What an entry draws. */
    public enum Kind { PIP, IMAGE, TEXT, SPRITE, VISUALIZER, ADJUSTMENT }

    /** One draw, bottom to top. */
    public static final class Entry {
        @NonNull public final Kind kind;
        @NonNull public final String id;
        @NonNull public final String laneId;
        @NonNull public final TimedItem item;

        Entry(@NonNull Kind kind, @NonNull String id, @NonNull String laneId, @NonNull TimedItem item) {
            this.kind = kind;
            this.id = id;
            this.laneId = laneId;
            this.item = item;
        }

        /** PiPs and effect layers: the items the legacy pipeline interleaves in lane order. */
        public boolean composited() { return kind == Kind.PIP || kind == Kind.ADJUSTMENT; }

        @Override public String toString() { return kind + ":" + id; }
    }

    private final List<Entry> entries;

    private LanePlan(List<Entry> entries) { this.entries = Collections.unmodifiableList(entries); }

    /** The true-order plan for a timeline. */
    @NonNull
    public static LanePlan of(@NonNull Timeline timeline) {
        List<Entry> out = new ArrayList<>();
        for (LayerPreviewController.VisualItem v : LayerPreviewController.orderedVisualItems(timeline)) {
            Kind kind = kindOf(v.item);
            if (kind != null) out.add(new Entry(kind, v.item.getId(), v.lane.getId(), v.item));
        }
        return new LanePlan(out);
    }

    @NonNull
    public List<Entry> entries() { return entries; }

    /**
     * Everything an effect layer at {@code index} changes: the entries below it, bottom to top
     * (the spine clip is below all of them). Throws if {@code index} is not an effect layer.
     */
    @NonNull
    public List<Entry> scopeBelow(int index) {
        if (entries.get(index).kind != Kind.ADJUSTMENT) {
            throw new IllegalArgumentException("not an effect layer: " + entries.get(index));
        }
        return entries.subList(0, index);
    }

    /**
     * Whether the legacy pipeline already draws this timeline in true order, so it may move to
     * the lane compositor without any change in look. Yes only when ALL of these hold:
     * <ol>
     *   <li>every PiP and effect layer sits below every text, picture, sprite and visualiser
     *       (legacy draws those after the PiP/effect walk, ungraded; true order would grade a
     *       text an effect layer sits above, and legacy sends equal-z lanes above the video
     *       whatever their band row);</li>
     *   <li>above that, the fixed legacy surfaces' type order holds: visualisers, then sprites,
     *       then text and pictures (one surface, kept in lane order);</li>
     *   <li>no item has a blend mode, a picture blend, or text effects of its own (legacy moves
     *       those to separate passes in its own places);</li>
     *   <li>no sprite takes the GL sprite route (mesh or blend), which legacy interleaves.</li>
     * </ol>
     */
    public boolean legacyLooksTheSame() {
        int lastComposited = -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).composited()) lastComposited = i;
        int rank = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.composited()) {
                if (i > 0 && !entries.get(i - 1).composited()) return false;   // rule 1
                continue;
            }
            if (i < lastComposited) return false;                              // rule 1
            int r = surfaceRank(e.kind);
            if (r < rank) return false;                                        // rule 2
            rank = r;
            if (!plain(e)) return false;                                       // rules 3, 4
        }
        return true;
    }

    private static int surfaceRank(Kind kind) {
        switch (kind) {
            case VISUALIZER: return 0;
            case SPRITE: return 1;
            default: return 2;   // TEXT and IMAGE share the text surface
        }
    }

    private static boolean plain(Entry e) {
        if (e.item.getBlendMode() != null && e.item.getBlendMode() != BlendMode.NORMAL) return false;
        TextOverlayItem t = e.item.getTextOverlay();
        if (t != null) {
            if (t.hasActiveFx()) return false;
            if (t.isImage() && com.fadcam.ui.faditor.model.BlendModes.modeCode(t.getOverlayBlendMode()) != 0) {
                return false;
            }
        }
        com.fadcam.ui.faditor.sprite.SpriteOverlayItem s = e.item.getSprite();
        return s == null || !s.wantsGl();
    }

    @Nullable
    private static Kind kindOf(@NonNull TimedItem item) {
        if (item.getAdjustment() != null) return Kind.ADJUSTMENT;
        Clip clip = item.getClip();
        if (clip != null) return clip.isOverlayClip() ? Kind.PIP : null;
        TextOverlayItem t = item.getTextOverlay();
        if (t != null) return t.isImage() ? Kind.IMAGE : Kind.TEXT;
        if (item.getSprite() != null) return Kind.SPRITE;
        if (item.getWaveform() != null) return Kind.VISUALIZER;
        return null;   // audio, caption spans: not lane-ordered visuals
    }
}
