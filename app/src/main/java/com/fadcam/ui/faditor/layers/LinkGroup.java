package com.fadcam.ui.faditor.layers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * G9 object linking (gesture contract §5.6): a group of timeline objects whose
 * {@link LinkedProperty} axes are bound together. Two shapes (PLAN_G9_LINK_ENGINE.md §2):
 *
 * <ul>
 *   <li><b>Peer</b> — symmetric, no host; gestures delta-propagate (push) between members.</li>
 *   <li><b>Host/rider</b> — exactly one {@link LinkMember#isHost}; riders' absolute times are
 *       RE-DERIVED from the host's current state by {@code Timeline#resyncLinkGroups()} (pull),
 *       the same one-write-point pattern G5's visualizer attach shipped with.</li>
 * </ul>
 *
 * <p>{@code presetKind} is null for ad-hoc groups; "PIGGYBACK"/"STRATIFIED" marks the TRANSIENT
 * groups synthesized from G5 visualizer attachment ({@code Timeline#synthesizeG5PresetLinkGroups})
 * — those live in a separate unpersisted list and their ground truth stays the
 * {@code WaveformOverlayInstance} attach fields; the JSON writer never sees them.</p>
 */
public final class LinkGroup {

    public static final String PRESET_PIGGYBACK = "PIGGYBACK";
    public static final String PRESET_STRATIFIED = "STRATIFIED";

    @NonNull public final String id;
    /** Null = ad-hoc user group (persisted); non-null = synthesized G5 preset (transient). */
    @Nullable public String presetKind;
    @NonNull public final EnumSet<LinkedProperty> properties = EnumSet.noneOf(LinkedProperty.class);
    @NonNull public final List<LinkMember> members = new ArrayList<>();

    public LinkGroup(@NonNull String id) {
        this.id = id;
    }

    /** The host member of a host/rider group, or null for peer groups. */
    @Nullable
    public LinkMember getHost() {
        for (LinkMember m : members) {
            if (m.isHost) return m;
        }
        return null;
    }

    /** True when this group was synthesized from G5 attachment (transient, never persisted). */
    public boolean isPreset() {
        return presetKind != null;
    }

    @Nullable
    public LinkMember findMember(@NonNull String itemId) {
        for (LinkMember m : members) {
            if (m.id.equals(itemId)) return m;
        }
        return null;
    }
}
