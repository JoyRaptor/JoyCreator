package com.fadcam.ui.faditor.layers;

import androidx.annotation.NonNull;

/**
 * One participant of a {@link LinkGroup} — a (payload kind, payload id) reference plus, for
 * host/rider groups, the rider's HOST-RELATIVE offsets. Ids are the payload objects' OWN stable
 * UUIDs (never minted for linking), so undo/redo of unrelated edits never needs id remapping —
 * the same convention G5's {@code WaveformOverlayInstance.attachedClipId} shipped with
 * (PLAN_G9_LINK_ENGINE.md §2).
 *
 * <p>{@code kind} uses {@link TimedItem#payloadKind()}'s stable tags ("clip", "textOverlay",
 * "audioClip", "sprite", "waveform"). Host-relative fields are meaningful only on RIDERS of a
 * host/rider group; peer-group members leave them at the {@link #UNSET} sentinel.</p>
 */
public final class LinkMember {

    /** Sentinel for "no host-relative value captured" (peer groups / the host itself). */
    public static final long UNSET = Long.MIN_VALUE;

    @NonNull public final String kind;
    @NonNull public final String id;
    /** Exactly one member of a host/rider group is the host; every member of a peer group is false. */
    public boolean isHost;

    /** Rider's start offset (ms) within/behind the host's timeline start. {@link #UNSET} = none. */
    public long hostOffsetMs = UNSET;

    public LinkMember(@NonNull String kind, @NonNull String id, boolean isHost) {
        this.kind = kind;
        this.id = id;
        this.isHost = isHost;
    }
}
