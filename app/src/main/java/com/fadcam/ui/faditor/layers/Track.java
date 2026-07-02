package com.fadcam.ui.faditor.layers;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A track (row) in the schema-v8 layer model (PLAN Part 2, §2.1): the master spine,
 * a floating layer (video/image/text/sticker/sprite), or an audio track.
 *
 * <p>M5 note: a {@code Track} is a <em>synchronized view</em> built on demand from
 * {@code Timeline}'s flat lists. Its {@link #items} reference the same live model
 * objects the flat lists hold; the flat lists remain the storage of record. See the
 * shim documentation on {@code Timeline}. Track/TimedItem field mutations that later
 * milestones make (name, collapsed, hidden, locked, muted, zIndex, per-item blend/
 * transform) will be persisted via the v8 serializer block, but in M5 the migration
 * always rebuilds them from the flat lists on load, so there is no divergence.</p>
 */
public class Track {

    @NonNull
    private final String id;

    @NonNull
    private TrackKind kind;

    @NonNull
    private String name;

    /** Paint order within its band (higher = on top). */
    private int zIndex;

    /** UI: thin summary strip vs full row. */
    private boolean collapsed;

    /** Not previewed, not exported. */
    private boolean hidden;

    /** Not editable (taps/drags ignored). */
    private boolean locked;

    /** Audio muted (audio tracks + video-with-audio). */
    private boolean muted;

    @NonNull
    private final List<TimedItem> items = new ArrayList<>();

    public Track(@NonNull TrackKind kind, @NonNull String name) {
        this(UUID.randomUUID().toString(), kind, name);
    }

    public Track(@NonNull String id, @NonNull TrackKind kind, @NonNull String name) {
        this.id = id;
        this.kind = kind;
        this.name = name;
    }

    @NonNull
    public String getId() { return id; }

    @NonNull
    public TrackKind getKind() { return kind; }

    public void setKind(@NonNull TrackKind kind) { this.kind = kind; }

    @NonNull
    public String getName() { return name; }

    public void setName(@NonNull String name) { this.name = name; }

    public int getZIndex() { return zIndex; }

    public void setZIndex(int zIndex) { this.zIndex = zIndex; }

    public boolean isCollapsed() { return collapsed; }

    public void setCollapsed(boolean collapsed) { this.collapsed = collapsed; }

    public boolean isHidden() { return hidden; }

    public void setHidden(boolean hidden) { this.hidden = hidden; }

    public boolean isLocked() { return locked; }

    public void setLocked(boolean locked) { this.locked = locked; }

    public boolean isMuted() { return muted; }

    public void setMuted(boolean muted) { this.muted = muted; }

    /** Live list of items (references the same model objects the flat lists hold). */
    @NonNull
    public List<TimedItem> getItems() { return items; }

    public void addItem(@NonNull TimedItem item) { items.add(item); }

    public boolean isEmpty() { return items.isEmpty(); }
}
