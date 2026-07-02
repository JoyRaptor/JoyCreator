package com.fadcam.ui.faditor.layers;

import androidx.annotation.NonNull;

import java.util.UUID;

/**
 * A persistent, user-created layer-track DEFINITION (M10; PLAN Part 7 row M10
 * track-membership design).
 *
 * <p>M5/M6 only ever produce the two fixed, implicit tracks {@code "text"} and
 * {@code "audio"} — every {@link TextOverlayItem}/{@code AudioClip} with a
 * {@code null} {@code layerId} belongs to one of those two. M10 lets the user
 * create ADDITIONAL layer tracks (currently only {@link TrackKind#TEXT} /
 * {@link TrackKind#STICKER} kind, since items are {@code TextOverlayItem}-backed —
 * see the M10 build report's IMAGE-path decision); this class is the persistent
 * record of "a track with this id/kind/name exists," independent of whether it
 * currently holds any items. {@link Track}/{@link TimedItem} stay ephemeral views
 * rebuilt from the flat lists (the architectural ruleM5-M7 established); THIS
 * object — not a view — is what {@code Timeline} stores and serializes so a
 * newly-created EMPTY track survives a save/reload before the user has dragged
 * anything into it.</p>
 *
 * <p>{@link TrackFlags} (collapsed/hidden/locked/muted/zIndex) is looked up
 * separately by id, exactly as it already is for "text"/"audio" — this class only
 * carries the identity/kind/name a Track view needs to be constructed from
 * scratch. Additive; old projects never produce any {@code LayerTrackDef}, so
 * {@code Timeline.getLayers()}/{@code getAudioTracks()} degrade to exactly the M5/M6
 * single-fixed-track behavior when the list is empty (see {@code Timeline}'s
 * grouping logic).</p>
 */
public final class LayerTrackDef {

    @NonNull private final String id;
    @NonNull private final TrackKind kind;
    @NonNull private String name;

    public LayerTrackDef(@NonNull TrackKind kind, @NonNull String name) {
        this(UUID.randomUUID().toString(), kind, name);
    }

    public LayerTrackDef(@NonNull String id, @NonNull TrackKind kind, @NonNull String name) {
        this.id = id;
        this.kind = kind;
        this.name = name;
    }

    @NonNull public String getId() { return id; }

    @NonNull public TrackKind getKind() { return kind; }

    @NonNull public String getName() { return name; }

    public void setName(@NonNull String name) { this.name = name; }
}
