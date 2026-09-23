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
    /** Studio green. Was bright blue; JoyRaptor 2026-09-18: "video ... should have the studio
     *  green gradient because it's video." The flat is the tape's first stop, so the tool
     *  row's tint and the drawer's accent agree with the tape they belong to. */
    public static final int VIDEO      = com.fadcam.ui.faditor.Studio.ROOM_STUDIO;
    /** Still images (previously aliased to VIDEO blue — F-COLOR gives them their own hue). */
    public static final int IMAGE      = 0xFF26A69A; // teal
    /** Audio clips. Matches the legacy audio-band waveform green, which was already 0xFF35F6BF. */
    /**
     * Audio clips.
     *
     * <p>Was Material #35F6BF, which the whole editor also used for "on", "modified",
     * "selected" and "go" — five meanings on one value. Now a spring green that belongs
     * to no state: under the gradient-means-action rule a FLAT colour is never an
     * action, so audio can stay green without competing with the Studio's own aqua.
     */
    public static final int AUDIO      = 0xFF4ADE80;
    /** Keyframed sprites. */
    /** Sprite Lab pink. Was amber; JoyRaptor 2026-09-18: "the Sprite tape should correspond
     *  to the gradient for sprite lab." Record 06 calls the object's colour on the drawer
     *  and tool row "the whole 'what am I editing' signal" — so it has to match the tape,
     *  or the signal says one thing and the timeline another. */
    public static final int SPRITE     = com.fadcam.ui.faditor.Studio.ROOM_SPRITE;
    /** Caption / CC spans. */
    public static final int CAPTION    = 0xFFFFC107; // gold
    /**
     * An IMAGE that has puppet pins on it. Not a kind of its own — it is still an image —
     * so this is applied per item in {@link #forItem}, never by {@link #forKind}.
     *
     * <p>JoyRaptor 2026-09-18: "as soon as they have a puppet tool like pins on it it should
     * change color to the Avatar Studio colors so that you can tell at a glance which
     * images have puppeteering rigs active."
     */
    public static final int RIGGED     = com.fadcam.ui.faditor.Studio.ROOM_AVATAR;
    /** Waveform / spectrum visualizers. */
    public static final int VISUALIZER = 0xFFEC407A; // pink
    /**
     * Adjustment layers. Its own hue on purpose: an adjustment layer is the one object that
     * changes what is ALREADY there rather than adding to it, and reading as a PiP would hide
     * exactly the distinction the user needs to see in the band.
     */
    public static final int ADJUSTMENT = 0xFF8A8A94; // slate — "affects, does not add"
    /** The master spine reads as video — same hue, named separately for call-site clarity. */
    public static final int MASTER     = VIDEO;

    // ══ THE TAPES ═══════════════════════════════════════════════════════════
    // JoyRaptor: "the object colors came from an older theme, map them onto there
    // closest gradient from our latest html styleguides ... tapes should be gradients."
    //
    // The flat hues above were picked before the palette existed. They are not WRONG —
    // they were pulled from the July brand list — they are just lonely: a mid purple on
    // its own reads as a leftover, while the same purple running into indigo reads as
    // part of a family.
    //
    // Every pair below is an ADJACENT pair from the Swatch Room's fourteen-colour wheel
    // (design record 01, §03 Gradients). That is the rule the wheel was ordered for: any
    // two neighbours blend without going muddy through the middle, because there is no
    // hue between them to pass through. Non-adjacent pairs — purple into orange — cross
    // the whole wheel and turn grey at the midpoint.
    //
    // ── what each kind gets, and why ────────────────────────────────────────
    // ── 2026-09-18: the tapes join the ROOM gradients ──────────────────────────
    // JoyRaptor: "We are further tying together the color gradient ecosystem into the object
    // tapes colors." Three kinds now wear the gradient of the ROOM they belong to, taken from
    // the Grand Design's own :root rather than typed again:
    //   VIDEO    Studio  #35F6BF -> #97FE8B   "because it's video"
    //   SPRITE   Sprites #FF008C -> #CC27FF   "correspond to the gradient for sprite lab"
    //   IMAGE + pins  Avatar #CC27FF -> #8C3DFA  "so you can tell at a glance which images
    //                                            have puppeteering rigs active"
    // This reverses the rule written further down — that the aqua-to-lime stretch stays empty
    // because it belongs to GO. It does belong to GO, and to the Studio room, and video now
    // wears the room. His call, made knowing the Studio is where video lives. The practical
    // safeguard is record 06 MAJOR 03's: every tape carries its TYPE GLYPH at the left cap, so
    // no object is ever identified by colour alone.
    //
    // Sprites -> Avatar -> Text now run as three neighbours along the wheel, each handing over
    // at a shared stop. That is the wheel doing what it was ordered for; the glyph tells them
    // apart where hue alone gets close.
    //
    // The table as it stood before this change, kept for its reasoning:
    //   TEXT        purple -> indigo        keeps the purple it already had
    //   VIDEO       (was) indigo -> bright blue
    //   SPRITE      (was) amber -> orange
    //   IMAGE       bright blue -> cyan     was teal and alone; now the cool end
    //   AUDIO       yellow-green -> golden  warm means SOUND in this system, and it is
    //                                       far from the Studio's aqua so "audio" can
    //                                       never be mistaken for "go"
    //   CAPTION     orange -> deep orange   warm, next to audio, because captions ARE audio
    //   VISUALIZER  red-pink -> neon pink   keeps its pink
    //   ADJUSTMENT  grey -> grey            deliberately hueless: it is the one object
    //                                       that CHANGES what is already there rather
    //                                       than adding to it, and a colour would make
    //                                       it look like another layer of content
    //
    // The aqua-to-lime stretch of the wheel is left empty on purpose. That belongs to
    // the Studio's own GO gradient, and an object wearing it would read as a button.

    private static final int[] G_TEXT       = {0xFF8C3DFA, 0xFF5C43FD};
    private static final int[] G_VIDEO      = {com.fadcam.ui.faditor.Studio.ROOM_STUDIO,
                                               com.fadcam.ui.faditor.Studio.ROOM_STUDIO_END};
    private static final int[] G_IMAGE      = {0xFF4397FD, 0xFF55E0F9};
    private static final int[] G_AUDIO      = {0xFFCEFF5B, 0xFFF9F462};
    private static final int[] G_SPRITE     = {com.fadcam.ui.faditor.Studio.ROOM_SPRITE,
                                               com.fadcam.ui.faditor.Studio.ROOM_SPRITE_END};
    private static final int[] G_RIGGED     = {com.fadcam.ui.faditor.Studio.ROOM_AVATAR,
                                               com.fadcam.ui.faditor.Studio.ROOM_AVATAR_DEEP};
    private static final int[] G_CAPTION    = {0xFFFAA03D, 0xFFFC6818};
    private static final int[] G_VISUALIZER = {0xFFFA3D5D, 0xFFFF008C};
    private static final int[] G_ADJUSTMENT = {0xFF52525B, 0xFF33333C};

    /**
     * The two stops for a kind's tape, in draw order (left to right along the timeline).
     *
     * <p>Returned as a shared array rather than copied: this is read inside onDraw for
     * every visible object on every frame while scrubbing, and allocating two ints per
     * object per frame is exactly the kind of thing that turns a smooth timeline into a
     * stuttery one. Callers must not write to it.
     */
    @NonNull
    public static int[] gradientFor(@NonNull TrackKind kind) {
        switch (kind) {
            case TEXT:       return G_TEXT;
            case STICKER:
            case IMAGE:      return G_IMAGE;
            case AUDIO:      return G_AUDIO;
            case SPRITE:     return G_SPRITE;
            case CAPTION:    return G_CAPTION;
            case VISUALIZER: return G_VISUALIZER;
            case ADJUSTMENT: return G_ADJUSTMENT;
            default:         return G_VIDEO;
        }
    }

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
            case ADJUSTMENT: return ADJUSTMENT;
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
        if (isRigged(item)) return RIGGED;
        return forKind(payloadKindOf(item, rowKind));
    }

    /**
     * The tape's two stops for an ITEM. Use this, not {@link #gradientFor(TrackKind)}, from
     * anything that draws a specific object — a rigged image is still kind IMAGE, and only
     * the item knows it has pins.
     */
    @NonNull
    public static int[] gradientForItem(@NonNull TimedItem item, @NonNull TrackKind rowKind) {
        if (isRigged(item)) return G_RIGGED;
        return gradientFor(payloadKindOf(item, rowKind));
    }

    /**
     * An image with at least one puppet PIN. {@code getOrCreatePuppet()} builds a rig lazily
     * the moment the puppet drawer is opened, so a non-null rig is not enough — an image
     * someone merely looked at in that drawer must not change colour. Pins are what the
     * owner named: "as soon as they have a puppet tool like pins on it".
     */
    public static boolean isRigged(@NonNull TimedItem item) {
        return isRigged(item.getTextOverlay());
    }

    /** The overlay-level form of {@link #isRigged(TimedItem)}; the one place the rule lives. */
    public static boolean isRigged(@androidx.annotation.Nullable com.fadcam.ui.faditor.model.TextOverlayItem o) {
        if (o == null || !o.isImage()) return false;
        com.fadcam.ui.faditor.puppet.PuppetRig rig = o.getPuppet();
        return rig != null && rig.pinCount() > 0;
    }

    /**
     * An overlay's colour when all you have is the overlay (a drawer opened for it), not its
     * timeline item. Same answer {@link #forItem} gives: a pinned image is Avatar violet, any
     * other image IMAGE, text TEXT.
     */
    public static int forOverlay(@NonNull com.fadcam.ui.faditor.model.TextOverlayItem o) {
        if (isRigged(o)) return RIGGED;
        return o.isImage() ? IMAGE : TEXT;
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
        if (item.getAdjustment() != null) return TrackKind.ADJUSTMENT;
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
