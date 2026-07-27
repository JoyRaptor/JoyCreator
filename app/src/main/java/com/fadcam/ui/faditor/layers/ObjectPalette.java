package com.fadcam.ui.faditor.layers;

import androidx.annotation.NonNull;

/**
 * THE palette for editor objects — one place, one table (SPEC_OBJECT_TIME_SCRUBBER F-COLOR).
 *
 * <p>An object is coloured by <b>what it is</b>, never by the lane it happens to sit on.
 * That distinction is the whole point of this class. Since the neutral substrate landed, a
 * lane no longer describes its contents: a text object dropped onto a lane whose kind is
 * {@code VIDEO} (or the neutral {@code LAYER}) is still a text object and must still read
 * purple. Colouring from the row's kind is what made a purple object turn blue after being
 * moved to another lane.</p>
 *
 * <p>Before this class the same hues were written out twice — {@code LayerRowRenderer}'s
 * {@code COLOR_ITEM_*} (item bodies, 0xDD alpha) and {@code EditorTimelineView}'s
 * {@code COLOR_PH_*} (playhead tint, opaque) — with no IMAGE entry in either, so images
 * silently inherited VIDEO blue through a {@code default:} branch. Both now read from here,
 * so a hue is changed in exactly one place and cannot drift apart again.</p>
 *
 * <p>Hues are stored opaque; call {@link #body(int)} for the item-body alpha rather than
 * hard-coding a second constant per type.</p>
 */
public final class ObjectPalette {

    private ObjectPalette() {}

    // ── The table (F-COLOR) ──────────────────────────────────────────
    /** Text and stickers. */
    public static final int TEXT       = 0xFF8C3DFA; // purple
    /** Overlay video / PiP, and the master spine. */
    public static final int VIDEO      = 0xFF4397FD; // blue
    /** Still images (previously aliased to VIDEO blue — F-COLOR gives them their own hue). */
    public static final int IMAGE      = 0xFF26A69A; // teal
    /** Audio clips. Matches the legacy audio-band waveform green, which was already 0xFF4CAF50. */
    public static final int AUDIO      = 0xFF4CAF50; // green
    /** Keyframed sprites. */
    public static final int SPRITE     = 0xFFFFB74D; // amber
    /** Caption / CC spans. */
    public static final int CAPTION    = 0xFFFFC107; // gold
    /** Waveform / spectrum visualizers. */
    public static final int VISUALIZER = 0xFFEC407A; // pink
    /** The master spine reads as video — same hue, named separately for call-site clarity. */
    public static final int MASTER     = VIDEO;

    /** Alpha used for item bodies drawn on a lane row. */
    private static final int BODY_ALPHA = 0xDD;

    /**
     * The colour for a kind. {@code MASTER}, {@code VIDEO} and any kind added later without a
     * hue of its own fall back to {@link #VIDEO} — deliberately the same fallback the two old
     * tables used, so this refactor changes no colour except the ones F-COLOR asked for.
     */
    public static int forKind(@NonNull TrackKind kind) {
        switch (kind) {
            case TEXT:
            case STICKER:    return TEXT;
            case IMAGE:      return IMAGE;
            case AUDIO:      return AUDIO;
            case SPRITE:     return SPRITE;
            case CAPTION:    return CAPTION;
            case VISUALIZER: return VISUALIZER;
            case VIDEO:
            case MASTER:
            case LAYER:
            default:         return VIDEO;
        }
    }

    /**
     * The colour for an ITEM — resolved from its payload, falling back to the row's kind only
     * when the payload has no identity of its own. This is the call every renderer should use;
     * passing a row's kind straight to {@link #forKind} is the bug this class exists to stop.
     */
    public static int forItem(@NonNull TimedItem item, @NonNull TrackKind rowKind) {
        return forKind(payloadKindOf(item, rowKind));
    }

    /**
     * The OBJECT's own kind (JoyRaptor 2026-07-19: badges ride objects, lanes are neutral).
     * Falls back to the row kind for payloads without a distinct identity.
     *
     * <p>Single source of truth — {@code LayerRowRenderer.payloadKindOf} delegates here so the
     * badge, the playhead tint, the item body and the mini-map can never disagree about what
     * an object IS.</p>
     */
    @NonNull
    public static TrackKind payloadKindOf(@NonNull TimedItem item, @NonNull TrackKind rowKind) {
        if (item.getTextOverlay() != null) {
            return item.getTextOverlay().isImage() ? TrackKind.IMAGE : TrackKind.TEXT;
        }
        if (item.getSprite() != null) return TrackKind.SPRITE;
        if (item.getAudioClip() != null) return TrackKind.AUDIO;
        if (item.getWaveform() != null) return TrackKind.VISUALIZER;
        if (item.getCaptionSpan() != null) return TrackKind.CAPTION;
        if (item.getClip() != null && item.getClip().isOverlayClip()) return TrackKind.VIDEO;
        return rowKind;
    }

    /** The same hue at item-body alpha. */
    public static int body(int color) {
        return withAlpha(color, BODY_ALPHA);
    }

    /** {@code color} with {@code alpha} (0..255) substituted. */
    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
