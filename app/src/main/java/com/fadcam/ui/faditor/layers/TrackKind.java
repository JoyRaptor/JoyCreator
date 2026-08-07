package com.fadcam.ui.faditor.layers;

import androidx.annotation.NonNull;

/**
 * The kind of a {@link Track} in the schema-v8 layer model (PLAN Part 2).
 *
 * <ul>
 *   <li>{@link #MASTER} — the spine (gapless in ripple mode); wraps {@code timeline.clips}.</li>
 *   <li>{@link #VIDEO} — a floating overlay-video layer (above the master).</li>
 *   <li>{@link #IMAGE} — a floating still-image layer.</li>
 *   <li>{@link #TEXT} — a text-overlay layer; wraps {@code timeline.textOverlays}.</li>
 *   <li>{@link #STICKER} — a PNG/sticker layer (also backed by TextOverlayItem's imageUri).</li>
 *   <li>{@link #SPRITE} — a keyframed sprite layer (payload not yet implemented — see sprite plan).</li>
 *   <li>{@link #CAPTION} — a caption/CC row; a read-only VIEW over clip-owned caption spans
 *       (captions stay {@code Clip}-owned — the Track never becomes a second source of truth).</li>
 *   <li>{@link #VISUALIZER} — an audio-waveform/spectrum visualizer; wraps
 *       {@code timeline.waveformOverlays}.</li>
 *   <li>{@link #AUDIO} — an audio track (below the master); wraps {@code timeline.audioClips}.</li>
 *   <li>{@link #LAYER} — a NEUTRAL user-created floating lane (SPEC_NEUTRAL_SUBSTRATE):
 *       holds text/sticker/sprite/image/video items MIXED. Only exists as a
 *       {@code LayerTrackDef} kind — {@code Timeline#getLayers()} merges every payload
 *       type sharing its layerId into one Track. Old builds reading a project with a
 *       LAYER def degrade via {@link #fromName}'s VIDEO fallback, which does not crash
 *       but IS data loss on that build's next autosave — see
 *       {@link #minSchemaVersion()}.</li>
 * </ul>
 *
 * <p>Only {@code MASTER}, {@code TEXT} and {@code AUDIO} are produced by the M5 auto-migration
 * (PLAN §2.2). The remaining kinds exist so later milestones can add layers without a schema bump.
 * {@code CAPTION}/{@code VISUALIZER} were added by the layers-UX renderer-consolidation (Slice A,
 * 2026-07-06) so captions and visualizers become headered Track rows in {@code LayerRowRenderer}.</p>
 */
public enum TrackKind {
    MASTER,
    VIDEO,
    IMAGE,
    TEXT,
    STICKER,
    SPRITE,
    CAPTION,
    VISUALIZER,
    AUDIO,
    LAYER,
    /**
     * An ADJUSTMENT LAYER lane (SPEC_ADJUSTMENT_LAYERS_FX M3) — a layer that TRANSFORMS
     * everything beneath it in z rather than compositing over it.
     *
     * <p>Emitted AFTER the video/PiP phase, so a new adjustment lane defaults above the PiPs:
     * "grade everything I have built so far" is the After Effects reading and the one people
     * expect.</p>
     */
    ADJUSTMENT;

    /**
     * True if a row of this kind is a real LANE — a container whose membership is decided by
     * its items' {@code layerId}, and which may therefore receive a dropped payload.
     *
     * <p>Deliberately a WHITELIST: the floating band also carries rows that are read-only
     * VIEWS over data owned elsewhere — {@link #CAPTION} (clip-owned caption spans) and
     * {@link #VISUALIZER} (flat {@code WaveformOverlayInstance} list, no layerId at all) —
     * plus the {@link #MASTER} spine and the {@link #AUDIO} band, none of which can own a
     * dropped item. Dropping onto one would write a layerId nothing routes to, orphaning the
     * item into a phantom lane. A new kind added later therefore defaults to NOT-a-lane:
     * a refused drop is a small annoyance, a silently orphaned item is data loss.</p>
     */
    public boolean isLane() {
        return this == VIDEO || this == IMAGE || this == TEXT
                || this == STICKER || this == SPRITE || this == LAYER
                || this == ADJUSTMENT;
    }

    /**
     * The minimum project {@code schemaVersion} that must be STAMPED on any project which
     * persists a {@code LayerTrackDef} of this kind, so that a build too old to know the
     * kind refuses the file outright instead of quietly coercing it.
     *
     * <p>{@link #fromName} maps an unknown kind to {@link #VIDEO}. That is a safe way to
     * avoid a crash and an UNSAFE way to avoid a schema bump, because the coerced kind is
     * then re-serialized by the old build's next autosave. Kind decides a lane's emission
     * phase, emission phase decides band position, and band position is paint order under
     * cross-type Z — so the round-trip permanently changes what paints over what, with no
     * error and no way back. That is exactly what shipped for {@link #LAYER}
     * (SPEC_NEUTRAL_SUBSTRATE declared "Storage: FREE... No schema bump" on the strength of
     * the fallback; audit 1.2 caught it). Reproduced offline in
     * {@code tasks/schema_layer_stamp.py}: a LAYER lane swaps band position with an orphan
     * PiP lane after one old-build load/save.
     *
     * <p>7 = representable by every build that has the layer model at all. <b>Any kind
     * added from here on MUST return the {@code SCHEMA_VERSION} of the build that
     * introduced it.</b> The value is a literal on purpose: it names the version that
     * first understood the kind, so a later unrelated bump must not drag it along.
     */
    public int minSchemaVersion() {
        if (this == ADJUSTMENT) return 13;   // LITERAL, per the contract above. Do not compute.
        return this == LAYER ? 11 : 7;
    }

    /**
     * Parse a persisted name, defaulting to {@link #VIDEO} for an unknown value.
     *
     * <p>This fallback keeps an old build from crashing; it does NOT make a new kind
     * storage-safe. Pair every new kind with {@link #minSchemaVersion()}.
     */
    @NonNull
    public static TrackKind fromName(@NonNull String name) {
        try {
            return TrackKind.valueOf(name);
        } catch (IllegalArgumentException e) {
            return VIDEO;
        }
    }
}
