package com.fadcam.ui.faditor.layers;

/**
 * Persistent mutable UI/edit flags for a {@link Track}, keyed by the track's stable id
 * (PLAN Part 7, M6; M5 status note ⚠️).
 *
 * <p>M5 built {@link Track} as a <em>synchronized view</em> rebuilt from the flat lists
 * on every {@code Timeline.getMasterTrack()}/{@code getLayers()}/{@code getAudioTracks()}
 * call (see {@code Timeline}'s class doc). That means a mutation like
 * {@code track.setCollapsed(true)} on a view object is thrown away the instant the view
 * is rebuilt on the next call — M5 left this as required follow-up work. {@code TrackFlags}
 * is the persistent side-table that fixes it: {@code Timeline} holds a
 * {@code Map<String, TrackFlags>} keyed by track id ("master" / "text" / "audio" — see
 * {@code Timeline.getMasterTrack()} et al. for where those literal ids are assigned), the
 * view builders APPLY a track's stored flags when constructing its {@link Track} view, and
 * M6's row-header toggles WRITE to this table instead of the ephemeral view object.</p>
 *
 * <p>Plain mutable holder (not immutable) so a toggle can flip one field in place; undo is
 * handled by the caller recording an {@code EditActions.LambdaAction} that snapshots/
 * restores the whole flags object (see {@link #copy()}).</p>
 */
public final class TrackFlags {

    public boolean collapsed;
    public boolean hidden;
    public boolean locked;
    public boolean muted;
    public int zIndex;
    /**
     * PHASE-P P1: user rename home for the DEFAULT tracks ("text"/"audio"/"sprite"),
     * which have no {@code LayerTrackDef} to carry a name (user-created tracks rename
     * via {@code LayerTrackDef#setName} instead). {@code null} = no rename, the view
     * builder's built-in default name applies — additive, old projects never set it.
     */
    @androidx.annotation.Nullable
    public String customName;

    public TrackFlags() {
    }

    public TrackFlags(boolean collapsed, boolean hidden, boolean locked, boolean muted, int zIndex) {
        this.collapsed = collapsed;
        this.hidden = hidden;
        this.locked = locked;
        this.muted = muted;
        this.zIndex = zIndex;
    }

    /** True if every field is at its default (i.e. this entry is safe to omit/drop). */
    public boolean isDefault() {
        return !collapsed && !hidden && !locked && !muted && zIndex == 0
                && (customName == null || customName.isEmpty());
    }

    /** Deep copy, used by undo actions to snapshot before/after state. */
    public TrackFlags copy() {
        TrackFlags c = new TrackFlags(collapsed, hidden, locked, muted, zIndex);
        c.customName = customName;
        return c;
    }

    /** Copy all fields from another instance into this one (in-place restore for undo). */
    public void copyFrom(TrackFlags other) {
        this.collapsed = other.collapsed;
        this.hidden = other.hidden;
        this.locked = other.locked;
        this.muted = other.muted;
        this.zIndex = other.zIndex;
        this.customName = other.customName;
    }

    /**
     * Field-by-field comparison against another instance (named {@code equalsFlags}
     * rather than overriding {@link #equals}/{@link #hashCode} — this class is used
     * as a plain mutable holder elsewhere and changing identity semantics there
     * risks unrelated behavior; this is a narrow value-comparison helper for
     * {@code Timeline#trackFlagsChangedSinceLoad} (Stage 1 P0 fix's concurrent-
     * instance merge guard) only.
     */
    public boolean equalsFlags(TrackFlags other) {
        if (other == null) return false;
        return collapsed == other.collapsed && hidden == other.hidden
                && locked == other.locked && muted == other.muted
                && zIndex == other.zIndex
                && java.util.Objects.equals(customName, other.customName);
    }
}
