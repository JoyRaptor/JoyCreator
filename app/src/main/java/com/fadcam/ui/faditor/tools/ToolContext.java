package com.fadcam.ui.faditor.tools;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.layers.TrackKind;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WHICH TOOLS MATTER RIGHT NOW.
 *
 * <p>JoyRaptor asked what was blocking a contextual tool row, and the honest answer was that
 * I had used the word "pins" for two different things. His pins are the mesh-warp and puppet
 * control points on an object in the preview. The ones I meant are TOOL pins: the row already
 * lets him pin a tool to the front and drag to reorder, and everything unpinned drifts by how
 * recently it was used.
 *
 * <p>So the conflict was never technical. It was ownership. A row that rearranges itself when
 * the selection changes would move the tools he arranged, and muscle memory built on "Split is
 * third" dies the first time it is fourth.
 *
 * <h3>The resolution: context sorts the section that already resorts</h3>
 * {@link FaditorToolPrefs#resolveOrder} builds the row in two halves — pinned tools in his
 * stored order, then everything else by recency. Contextual relevance is applied ONLY to the
 * second half.
 *
 * <p>That gives the feature its whole value without costing anything:
 * <ul>
 *   <li>pinned tools never move, ever — the half he owns is untouched;</li>
 *   <li>the unpinned half already reorders on its own as he works, so reordering there is
 *       behaviour he has, not behaviour he has to learn;</li>
 *   <li>selecting a text object puts the text tools at the front of that half instead of
 *       wherever they last drifted to.</li>
 * </ul>
 *
 * <h3>Relevance, not exclusion</h3>
 * Nothing is ever HIDDEN by context. A contextual row that removes tools is worse than no
 * contextual row, because the one time you want a tool it has decided is irrelevant, it is
 * gone and there is no way to reason about where. Everything stays; some things move nearer.
 */
public final class ToolContext {

    private ToolContext() { }

    /**
     * Tools that act on any object at all.
     *
     * <p>These come AFTER the kind-specific ones, which is the opposite of the obvious
     * order and was corrected by watching it run. Put first, they swallow the whole visible
     * part of the unpinned section — and they are the least useful things to promote,
     * because universal verbs are exactly what a user pins, so they are usually already at
     * the front of the row and need no help. The kind-specific tools are the ones that are
     * hard to find, so they are the ones that move.
     */
    private static final List<String> ANY = Arrays.asList(
            "split", "duplicate", "delete", "move", "opacity");

    private static final List<String> TEXT = Arrays.asList(
            "text", "align", "captions", "transitions");

    private static final List<String> VIDEO = Arrays.asList(
            "speed", "crop", "filter", "rotate", "flip", "transitions", "canvas", "adjustment");

    private static final List<String> IMAGE = Arrays.asList(
            "crop", "rotate", "flip", "filter", "canvas", "align", "adjustment");

    private static final List<String> AUDIO = Arrays.asList(
            "audio", "mute", "silence", "fix_audio", "beats", "visualizer", "speed");

    private static final List<String> SPRITE = Arrays.asList(
            "sprites", "align", "rotate", "flip", "opacity");

    private static final List<String> CAPTION = Arrays.asList(
            "captions", "transcript", "text", "align");

    private static final List<String> VISUALIZER = Arrays.asList(
            "visualizer", "audio", "beats", "filter");

    private static final List<String> ADJUSTMENT = Arrays.asList(
            "adjustment", "filter", "opacity");

    /**
     * The tool ids relevant to {@code kind}, most relevant first.
     *
     * <p>Order inside the list matters — it becomes the order they appear in, and the
     * kind-specific tools lead. See {@link #ANY} for why that is the opposite of the
     * obvious choice.
     *
     * @param kind the selected object's kind, or null when nothing is selected
     */
    @NonNull
    public static List<String> relevantTo(@Nullable TrackKind kind) {
        if (kind == null) return Collections.emptyList();
        List<String> specific;
        switch (kind) {
            case TEXT:       specific = TEXT; break;
            case STICKER:
            case IMAGE:      specific = IMAGE; break;
            case AUDIO:      specific = AUDIO; break;
            case SPRITE:     specific = SPRITE; break;
            case CAPTION:    specific = CAPTION; break;
            case VISUALIZER: specific = VISUALIZER; break;
            case ADJUSTMENT: specific = ADJUSTMENT; break;
            default:         specific = VIDEO; break;
        }
        java.util.List<String> out = new java.util.ArrayList<>(ANY.size() + specific.size());
        out.addAll(specific);
        for (String id : ANY) if (!out.contains(id)) out.add(id);
        return out;
    }

    /** The same thing as a set, for a membership test in a comparator. */
    @NonNull
    public static Set<String> relevantSet(@Nullable TrackKind kind) {
        return new HashSet<>(relevantTo(kind));
    }
}
