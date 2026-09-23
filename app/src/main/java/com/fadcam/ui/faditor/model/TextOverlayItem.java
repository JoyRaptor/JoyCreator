package com.fadcam.ui.faditor.model;

import com.fadcam.ui.faditor.Studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.graphics.Color;

import java.util.UUID;

/**
 * A text overlay rendered on top of the whole timeline (CapCut-style title/caption).
 *
 * <p>Position is stored as the overlay's CENTER in normalised video-content
 * coordinates ([0,1] where 0,0 is the top-left of the visible video and 1,1 is
 * the bottom-right). Size is stored as a fraction of the video height so the
 * overlay scales identically in the on-screen preview and in the exported file.</p>
 *
 * <p>For v1 the overlay spans the entire timeline. Time-range trimming and image
 * (PNG) overlays reuse this same model and the same export path.</p>
 */
public class TextOverlayItem {

    // §4.5 per-OBJECT visibility/lock (LANE_BADGES spec, built 2026-07-19): the eye/lock
    // moved off the row gutter onto the object itself. Hidden = excluded from preview AND
    // export via LayerPreviewController's single-authority filters; locked = selectable
    // but never trims/moves/deletes. Tolerant storage: absent = false.
    private boolean hidden;
    private boolean locked;

    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    @NonNull
    private final String id;

    @NonNull
    private String text;

    /** ARGB colour of the text. */
    private int colorInt;

    private int strokeColorInt = Color.TRANSPARENT;

    private float strokeWidthPx;

    private int shadowColorInt = 0xCC000000;

    private float shadowRadiusPx;

    private int glowColorInt = Color.TRANSPARENT;

    private float glowRadiusPx;

    private int backgroundColorInt = Color.TRANSPARENT;

    // ── Bottom text drawer, top row (SPEC_TEXT_DRAWER) ──────────────────────────────────
    // Bold/italic/underline are simple booleans rather than a style enum, because the user
    // can toggle any combination independently (bold+underline, italic alone, all three) —
    // an enum would force picking one shape at a time. Baked into getTypeface() so every
    // caller that already asks this item for its Typeface gets them for free, on both
    // renderers, without a second place that has to remember to apply them.
    private boolean bold;
    private boolean italic;
    private boolean underline;

    public boolean isBold() { return bold; }
    public void setBold(boolean bold) { this.bold = bold; }
    public boolean isItalic() { return italic; }
    public void setItalic(boolean italic) { this.italic = italic; }
    public boolean isUnderline() { return underline; }
    public void setUnderline(boolean underline) { this.underline = underline; }

    /** Case transform names — see {@link #applyCase}. Aliased to the resolver's single authority. */
    public static final String CASE_NONE = TextStyleResolver.CASE_NONE;
    public static final String CASE_CAPITALIZE_FIRST = TextStyleResolver.CASE_CAPITALIZE_FIRST;
    public static final String CASE_ALL_CAPS = TextStyleResolver.CASE_ALL_CAPS;
    public static final String CASE_SMALL_CAPS = TextStyleResolver.CASE_SMALL_CAPS;

    @NonNull
    private String textCase = CASE_NONE;

    @NonNull
    public String getTextCase() { return textCase; }

    public void setTextCase(@NonNull String textCase) { this.textCase = textCase; }

    // ── Rich text spans (W5-2 §3.8) ────────────────────────────────────────────────────
    // Per-selection formatting on top of the base style above. The spans are the ONE
    // per-glyph override list: their rules (last-span-wins per property, base fallback,
    // edit realignment) are all owned by TextStyleResolver, which is pure-Java and pinned
    // by the JVM harness — renderers only consume its resolved runs and so cannot invent a
    // different meaning for a span.
    //
    // Null until something is set, so every overlay that predates W5-2 serializes
    // byte-identically (the storage writes "styleSpans" only when non-empty).

    @Nullable
    private java.util.List<StyleSpan> styleSpans;

    /** The item's spans, or null when it has none. Renderers read this through the resolver. */
    @Nullable
    public java.util.List<StyleSpan> getStyleSpans() { return styleSpans; }

    /** The mutable span list, created on first access — what the drawer edits. */
    @NonNull
    public java.util.List<StyleSpan> getOrCreateStyleSpans() {
        if (styleSpans == null) styleSpans = new java.util.ArrayList<>();
        return styleSpans;
    }

    /** True when any span carries at least one override. */
    public boolean hasStyleSpans() {
        if (styleSpans == null) return false;
        for (StyleSpan s : styleSpans) {
            if (!s.isNoop()) return true;
        }
        return false;
    }

    /** Replace the span list wholesale (deep copy — never alias another item's list). */
    public void setStyleSpans(@Nullable java.util.List<StyleSpan> spans) {
        styleSpans = copySpans(spans);
    }

    private static java.util.List<StyleSpan> copySpans(@Nullable java.util.List<StyleSpan> src) {
        if (src == null) return null;
        java.util.List<StyleSpan> out = new java.util.ArrayList<>(src.size());
        for (StyleSpan s : src) out.add(s.copy());
        return out;
    }

    /**
     * The {@link TextStyleResolver.Base} this item's base style means — the unspanned look.
     * The drawer feeds this to the resolver's queries; both renderers build it to resolve.
     */
    @NonNull
    public TextStyleResolver.Base resolveBase() {
        TextStyleResolver.Base b = new TextStyleResolver.Base();
        b.fontFamily = fontFamily;
        b.bold = bold;
        b.italic = italic;
        b.underline = underline;
        b.textCase = textCase;
        b.fillColor = colorInt;
        b.strokeColor = strokeColorInt;
        b.glowColor = glowColorInt;
        b.shadowColor = shadowColorInt;
        b.backgroundColor = backgroundColorInt;
        return b;
    }

    /**
     * Apply this item's case transform to displayed text. Called by BOTH renderers on the
     * string they are about to lay out, so a case choice can never look different in preview
     * vs export.
     *
     * <p>SMALL_CAPS has no real small-caps glyphs available on stock Android type — it upper-
     * cases like ALL_CAPS. Kept as a distinct value anyway (rather than aliased to ALL_CAPS)
     * because a future font swap could give it real small-caps metrics without a model change.
     */
    @NonNull
    public String applyCase(@NonNull String text) {
        switch (textCase) {
            case CASE_ALL_CAPS:
            case CASE_SMALL_CAPS:
                return text.toUpperCase(java.util.Locale.getDefault());
            case CASE_CAPITALIZE_FIRST: {
                StringBuilder sb = new StringBuilder(text.length());
                boolean atWordStart = true;
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (Character.isWhitespace(c)) {
                        atWordStart = true;
                        sb.append(c);
                    } else if (atWordStart) {
                        sb.append(Character.toUpperCase(c));
                        atWordStart = false;
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
            default:
                return text;
        }
    }

    /** Horizontal alignment of this text box's lines within its own measured width. */
    public static final String ALIGN_CENTER = "CENTER";
    public static final String ALIGN_LEFT = "LEFT";
    public static final String ALIGN_RIGHT = "RIGHT";
    public static final String ALIGN_JUSTIFY = "JUSTIFY";

    @NonNull
    private String textAlign = ALIGN_CENTER;

    @NonNull
    public String getTextAlign() { return textAlign; }

    public void setTextAlign(@NonNull String textAlign) { this.textAlign = textAlign; }

    /** Cycle LEFT → CENTER → RIGHT → JUSTIFY → LEFT, the order the top-row button steps through. */
    @NonNull
    public static String nextAlign(@NonNull String current) {
        switch (current) {
            case ALIGN_LEFT: return ALIGN_CENTER;
            case ALIGN_CENTER: return ALIGN_RIGHT;
            case ALIGN_RIGHT: return ALIGN_JUSTIFY;
            default: return ALIGN_LEFT;
        }
    }

    // ── Shadow direction (SPEC_TEXT_DRAWER shadow row) ──────────────────────────────────
    // The renderer used to offset the shadow by a small FIXED (0, fontPx*0.04) — a shadow that
    // always fell slightly down. angle/distance replace that with a direction the user can
    // scrub, expressed the same "percent of font size" way every other decoration radius is
    // (see decorRadiusPx) so it scales identically in preview and export.

    /** Clockwise degrees from straight down (0°), matching the knob's scrub direction. */
    private float shadowAngleDeg = 0f;

    /** Distance as a percent of font size — same unit family as stroke/glow/shadow radius. */
    private float shadowDistancePx = 4f;

    public float getShadowAngleDeg() { return shadowAngleDeg; }
    public void setShadowAngleDeg(float shadowAngleDeg) {
        float v = shadowAngleDeg % 360f;
        this.shadowAngleDeg = v < 0f ? v + 360f : v;
    }

    public float getShadowDistancePx() { return shadowDistancePx; }
    public void setShadowDistancePx(float shadowDistancePx) {
        this.shadowDistancePx = Math.max(0f, shadowDistancePx);
    }

    /** Shadow offset in actual pixels, resolved against this item's own angle/distance/fontPx. */
    public static float shadowDx(float angleDeg, float distancePercent, float fontPx) {
        double rad = Math.toRadians(angleDeg);
        return (float) Math.sin(rad) * decorRadiusPx(distancePercent, fontPx);
    }

    public static float shadowDy(float angleDeg, float distancePercent, float fontPx) {
        double rad = Math.toRadians(angleDeg);
        return (float) Math.cos(rad) * decorRadiusPx(distancePercent, fontPx);
    }

    /** Centre X in normalised video-content coords [0,1]. */
    private float centerX;

    /** Centre Y in normalised video-content coords [0,1]. */
    private float centerY;

    /**
     * How far the centre may travel beyond each canvas edge, as a fraction of the
     * canvas size, per axis. Grown by the render layer each layout to be
     * proportional to the object's own rendered size, so a huge overlay can be
     * pushed fully off-frame and still be reachable (user, 2026-08-09). The
     * clamp in {@link #setCenter} and the X/Y keyframe writer both use these.
     */
    private float centerLimitX = 1f;
    private float centerLimitY = 1f;

    /** Text height as a fraction of the video height (e.g. 0.08 = 8%). */
    private float sizeFraction;

    /**
     * Per-axis scale multipliers on {@link #sizeFraction}, used when the Scale row's chain is
     * UNLINKED (image-overlay drawer). Linked (default) = both 1, so every pre-existing project
     * renders exactly as it always did and serializes nothing extra. Unlinked, the effective
     * box size is {@code sizeFraction*scaleX} wide by {@code sizeFraction*scaleY} tall.
     */
    private float scaleX = 1f;
    private float scaleY = 1f;

    /** Chain state of the Scale row: true = one uniform slider (SCALE track), false = X/Y pair. */
    private boolean scaleLinked = true;

    /**
     * SPEC G mirror (flipH/flipV) — the picture mirrored inside its own box, about its own
     * centre lines. False/false is the default and is every overlay in every project written
     * before this.
     *
     * <p>Why booleans and not a negative scaleX/scaleY: the scale rails clamp to [0.02, 10]
     * in the setters, the keyframe writer and the decode bound, and every renderer's size
     * math assumes a positive extent (the GL path even refuses to draw a non-positive
     * half-extent). A negative scale would have to re-teach all three renderers what a size
     * means; a mirror is a sign applied at draw time and nothing else. Same choice the two
     * older overlay families already made ({@code SpriteOverlayItem.isFlipH/V},
     * {@code Clip.isFlipHorizontal/Vertical}), with the same sparse storage (absent = false)
     * and the same snapshot cover, so the three families finally agree on what a flip is.
     *
     * <p>Deliberately NOT keyframable, like the rotation pivot: a flip is a discrete mirror
     * edit (the transform ring's flip actions), and interpolating a boolean would be a pop
     * dressed as an animation. An armed flip stays a pin permutation (animated); an unarmed
     * one toggles these and spends no corner-pin budget at all.
     *
     * <p>Composition order (SPEC G, stated here because all three renderers implement it):
     * bitmap → MIRROR → pin → rotate/scale → translate, the mirror about the UNPINNED box
     * centre. The pin offsets therefore live in the unmirrored box frame however these
     * stand. Renderers read the sign from {@link #mirrorSignX}/{@link #mirrorSignY}, the one
     * shared definition — never a second transcription of the flag.
     */
    private boolean flipH;
    private boolean flipV;

    /**
     * SPEC B — the object's rotation pivot, as fractions of its own PICTURE box in 0..1
     * (0.5/0.5 = centre, the default and every overlay in every project written before this).
     * Deliberately NOT keyframable: interpolating a moving pivot produces swooping arcs nobody
     * authored, so the pivot is a static property of the object — the picker snaps taps to the
     * nine anchors and {@link #setRotationPivot} re-snaps on load, which is also what keeps the
     * sparse serialization stable (a centre pivot writes nothing; see ProjectStorage).
     *
     * <p>Renderers never transcribe the pivot arithmetic — they read it from
     * {@link #pivotOffsetFromCentreX(float)} / {@link #pivotOffsetFromCentreY(float)}, the one
     * definition the preview (View and GL) and the export share.</p>
     */
    private float rotationPivotX = 0.5f;
    private float rotationPivotY = 0.5f;

    /**
     * CORNER PIN — four (dx, dy) offsets as fractions of this item's own untransformed size,
     * packed TL, TR, BR, BL. All zero = undistorted, which is the default and is every overlay in
     * every project written before this existed.
     *
     * <p>Deliberately one array rather than eight fields: the eight components are always read
     * together (the matrix needs all four corners or none), the packed order is the exact order
     * {@code Matrix.setPolyToPoly} wants, and one array is one thing to copy in
     * {@link #copyWithNewId} and one thing to snapshot for undo. See {@link CornerPin} for the
     * unit, the clamp, the track names and the matrix itself.</p>
     */
    @NonNull
    private final float[] cornerPin = new float[CornerPin.SIZE];

    /**
     * Touch pass-through — the image-overlay twin of a PiP's. true = the preview ignores taps
     * on this overlay so the thing beneath it is reachable (export is unaffected; this is a
     * preview interaction, exactly like a PiP's pass-through).
     */
    private boolean passThrough;

    /**
     * Per-item compositing (masks + chroma key + matte), the same {@link CompositingSpec} a
     * PiP or adjustment layer carries. Null = nothing, serialized only when non-empty. The
     * Mask/Key tabs of the image drawer edit it.
     */
    @Nullable
    private com.fadcam.ui.faditor.model.CompositingSpec compositing;

    /** Blend-mode NAME ({@code layers.BlendMode}) — the Blend tab of the image drawer. */
    @NonNull
    private String overlayBlendMode = "NORMAL";

    /**
     * SPEC E — mesh warp (bend) for IMAGE overlays. Null = no bend, which is every overlay
     * written before this existed. Self-serialising via {@code MeshWarpSpec.toJson/fromJson};
     * {@code toJson} returns null for an identity pose so opening the tool and changing nothing
     * saves byte-identically. v1 images only (see {@link #hasMesh} / storage tolerance).
     */
    @Nullable
    private com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec mesh;

    /**
     * SPEC_20260915_PUPPET_UI — the puppet rig: pin names and types, bones, and the
     * character/recording settings. Null = never rigged, which is every overlay written before
     * this existed, and {@code PuppetRigJson.toJson} returns null for a rig with no pins, so an
     * un-rigged image still saves byte-identically.
     *
     * <p>SEPARATE from {@link #mesh} on purpose. The mesh holds pin POSITIONS because the solver
     * needs them; this holds everything the solver does not care about. Pin {@code i} here is
     * handle {@code i} there — see {@code PuppetRig.matchesTopology}.
     */
    @Nullable
    private com.fadcam.ui.faditor.puppet.PuppetRig puppet;

    /** Clockwise rotation in degrees. */
    private float rotationDeg;

    /**
     * Static opacity [0,1] used when the overlay has no OPACITY keyframes (the
     * fallback for {@link #animatedOpacity(long)}). 1 = fully opaque.
     */
    private float opacity = 1f;

    // ── Image animation preset + opacity fade handles (SPEC_20260829_IMAGE_ANIM_PRESETS) ──
    /** When non-null and active, owns two amber keyframes at the item's ends. Null = none. */
    @Nullable private ImageAnimPreset imageAnimPreset;
    /** Fade durations for image opacity (stackable multiply, not keyframes). 0 = none. */
    private long imageFadeInMs = 0;
    private long imageFadeOutMs = 0;

    /** Font family key for text overlays (e.g. "default", "serif", "mono", "dramatic"). */
    @NonNull
    private String fontFamily = "default";

    /**
     * When non-null this overlay is an IMAGE (PNG/sticker) loaded from this URI,
     * and {@link #text}/{@link #colorInt} are ignored. Reuses the same position,
     * size, rotation, drag/pinch and export path as text overlays.
     */
    @Nullable
    private String imageUri;

    /** Visible range in timeline ms. endMs == Long.MAX_VALUE means "to the end". */
    private long startMs = 0;
    private long endMs = Long.MAX_VALUE;

    // ── Entrance / exit animation (SPEC_TEXT_ANIMATION, text-box half) ───────────────
    //
    // The same four values a captioned Clip carries, evaluated by the same
    // CaptionAnimator. A text box is the EASY case of the user's model: captions had to
    // invent a "line" because a clip holds many phrases, while a text box IS one line —
    // its own startMs…endMs span. So the carets he asked for map here directly, and this
    // is the object he reserved them for.

    // ── Rider attachment (PLAN_TIMELINE_MANIPULATION_V1 §2.0, addendum §4A) ──────────────
    // This overlay's tether to a master clip. Its policy is SHIFT_ONLY: it travels when its
    // host moves and its DURATION never changes, because the user chose that duration and a
    // ripple is not an edit to it. The visualizer's equivalent pair lives on
    // WaveformOverlayInstance (attachedClipId/attachOffsetMs) under SHIFT_TRUNCATE — same
    // concept, different policy; converge the naming only alongside a behaviour test, since
    // that one ships today.

    /** Host master-clip id, or null = unanchored (absolute time). */
    @Nullable
    private String hostClipId;

    /**
     * Offset from the host clip's START. Stored rather than derived so a save/load cycle cannot
     * re-derive a different host: {@code AnchorMath.offsetWithinHost} clamps it to
     * {@code span - 1}, which is what keeps an attachment from walking forward one clip each time.
     */
    private long hostOffsetMs;

    /**
     * THE single authority turning an authored decoration size into a pixel radius.
     *
     * <p><b>The stored value is a PERCENTAGE OF FONT SIZE, not pixels</b>, despite the historical
     * {@code ...Px} field names (kept so the JSON keys stay stable). It has to be, for exactly the
     * reason {@link com.fadcam.ui.faditor.model.CompositingSpec#featherRadiusPx} exists: the
     * preview draws into a view a few hundred pixels tall while the export draws into a full
     * frame, and {@code fontPx} scales with the surface while a raw pixel radius does not. A glow
     * authored as "6px" against the preview therefore came out two to three times thinner in the
     * exported file — same slider, two different looks, which is the divergence this project
     * treats as a defect in itself.</p>
     *
     * <p>Expressing it as a fraction of the text's own size also matches what a user means by
     * "outline thickness", and it makes the renderers' existing built-in default —
     * {@code fontPx * 0.10f} for the shadow — expressible as the value 10.</p>
     *
     * <p>Safe to introduce as a semantic change because nothing could ever WRITE these fields:
     * the access-point audit found the only writer was the deserializer, and the one other
     * source, {@code TextStyleIO}, belongs to a subsystem that is never instantiated.</p>
     */
    public static float decorRadiusPx(float authoredPercent, float fontPx) {
        return Math.max(0f, authoredPercent) / 100f * Math.max(0f, fontPx);
    }

    /** @see #hostClipId */
    @Nullable
    public String getHostClipId() { return hostClipId; }

    /** @see #hostOffsetMs */
    public long getHostOffsetMs() { return hostOffsetMs; }

    /** Attach to {@code clipId} at {@code offsetMs}, or pass null to detach (absolute time). */
    public void setHostAnchor(@Nullable String clipId, long offsetMs) {
        this.hostClipId = clipId;
        this.hostOffsetMs = clipId == null ? 0L : Math.max(0L, offsetMs);
    }

    /** {@code CaptionAnimator.Preset} name. "NONE" = no entrance/exit. */
    @NonNull
    private String textAnimPreset = "NONE";

    /**
     * {@code CaptionAnimator.Granularity} name. LETTER (owner, 2026-09-22: a whole BLOCK
     * flying in can read as an unclear flash, per-letter reads as a title). Missing on
     * load inherits this — owner's ruling, sole user, BLOCK never authored.
     */
    @NonNull
    private String textAnimGranularity = "LETTER";

    /** Entrance zone as a fraction of this box's own visible span, 0…0.5. */
    private float textAnimInPct = 0f;

    /** Exit zone, same units. */
    private float textAnimOutPct = 0f;

    /**
     * Persistent home for WHICH layer track this item belongs to (M10; PLAN Part 7
     * row M10 track-membership design). {@code null} = the default/auto-migrated
     * single TEXT track (id {@code "text"}) — this is the ONLY value every project
     * saved before M10 can have, since the field did not exist, so
     * {@code Timeline.getLayers()} grouping every item with a null/"text" layerId
     * into ONE track reproduces exactly today's single-TEXT-layer behavior. A
     * non-null value (a {@link com.fadcam.ui.faditor.layers.Track#getId()} minted by
     * M10's "new layer" flow) routes this item into a user-created layer track
     * instead. See {@code Timeline#getLayers()} for the grouping logic.
     */
    @Nullable
    private String layerId;

    /** Optional per-property animation (position/scale/opacity/rotation over time). */
    @NonNull
    private final com.fadcam.ui.faditor.keyframe.KeyframeSet keyframes =
            new com.fadcam.ui.faditor.keyframe.KeyframeSet();

    /**
     * PER-OBJECT effects (SPEC_ADJUSTMENT_LAYERS_FX M7) — this text's own {@code FxStack}.
     *
     * <p>The same model a {@code Clip} carries and the same one an adjustment layer runs: same
     * registry, same compiler, same panel. JoyRaptor asked for exactly this — "a primitive that
     * could be either put directly onto a video image or text layer... only affecting the areas
     * that are opaque".</p>
     *
     * <p>Null until something is added, so every text overlay that predates per-object FX
     * serializes byte-identically.</p>
     *
     * <p><b>Rendered by {@code TextFxGlEffect}.</b> A PiP reaches the screen through a GL
     * shader, so its FX splice straight in; text reaches it through a Canvas
     * {@code BitmapOverlay}, where there is no shader at all. So an overlay carrying effects
     * leaves the Canvas path entirely and gets its own GL effect, which rasterises it through
     * the SAME {@code TextOverlayRenderer} and then runs its stack on those pixels.</p>
     */
    @Nullable
    private com.fadcam.ui.faditor.fx.FxStack fx;

    public TextOverlayItem(@NonNull String text, int colorInt,
                           float centerX, float centerY,
                           float sizeFraction, float rotationDeg) {
        this.id = UUID.randomUUID().toString();
        this.text = text;
        this.colorInt = colorInt;
        this.centerX = centerX;
        this.centerY = centerY;
        this.sizeFraction = sizeFraction;
        this.rotationDeg = rotationDeg;
    }

    /** Deserialisation / cloning constructor (keeps the supplied id). */
    public TextOverlayItem(@NonNull String id, @NonNull String text, int colorInt,
                           float centerX, float centerY,
                           float sizeFraction, float rotationDeg) {
        this.id = id;
        this.text = text;
        this.colorInt = colorInt;
        this.centerX = centerX;
        this.centerY = centerY;
        this.sizeFraction = sizeFraction;
        this.rotationDeg = rotationDeg;
    }

    /**
     * A DEEP copy under a NEW id — what "duplicate this object" needs.
     *
     * <p><b>Every reference type is copied, not shared.</b> {@link #keyframes} is final and
     * copied through {@code copyFrom}; {@link #generatedSource} and {@link #timerSpec} have
     * their own {@code copy()}. Sharing any of the three would produce two objects that animate
     * together, or two captions that rewrite each other — a bug that looks like the editor
     * having a mind of its own rather than like an aliased field.</p>
     *
     * <p>{@code layerId} is NOT copied. A duplicate belongs to whichever lane the caller puts it
     * on, and inheriting the original's lane is exactly what makes two objects overlap on a row
     * that forbids overlap.</p>
     */
    /**
     * This overlay's pose — X, Y, SCALE, ROTATION — on the TIMELINE clock (absolute ms), for a
     * mask linked to it ("Move with the object": the mask in OBJECT space rather than screen
     * space). The overlay's own keys are LOCAL to its start, which is why its masks had no
     * link at all: a mask's keys and link base are absolute. Each track is shifted by the
     * start; a property with no keys becomes one key holding its static value, so a picture
     * that is simply dragged somewhere carries its linked mask too.
     */
    @NonNull
    public com.fadcam.ui.faditor.keyframe.KeyframeSet timelinePose() {
        com.fadcam.ui.faditor.keyframe.KeyframeSet out =
                new com.fadcam.ui.faditor.keyframe.KeyframeSet();
        long off = Math.max(0L, startMs);
        copyPoseTrack(out, com.fadcam.ui.faditor.keyframe.KeyframeSet.X, centerX, off);
        copyPoseTrack(out, com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, centerY, off);
        copyPoseTrack(out, com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, sizeFraction, off);
        copyPoseTrack(out, com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION, rotationDeg, off);
        return out;
    }

    private void copyPoseTrack(@NonNull com.fadcam.ui.faditor.keyframe.KeyframeSet out,
                               @NonNull String key, float staticValue, long off) {
        com.fadcam.ui.faditor.keyframe.KeyframeTrack src = keyframes.get(key);
        com.fadcam.ui.faditor.keyframe.KeyframeTrack dst = out.getOrCreate(key);
        if (src == null || src.keyframes.isEmpty()) {
            dst.put(0L, staticValue, com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
            return;
        }
        for (com.fadcam.ui.faditor.keyframe.Keyframe k : src.keyframes) {
            dst.put(k.timeMs + off, k.value, k.easing);
        }
    }

    /** {@link #timelinePose} when any mask rides this object, else null (costs nothing). */
    @Nullable
    public com.fadcam.ui.faditor.keyframe.KeyframeSet maskLinkPose() {
        return compositing != null && compositing.hasLinkedMask() ? timelinePose() : null;
    }

    /**
     * The compositing spec AS IT STANDS at {@code timelineMs}: mask keyframes applied, and any
     * mask linked to this object carried by it. The GL routes used to hand the raw spec to the
     * shader, so an image's mask neither animated nor followed it there. Returns the spec
     * itself when nothing animates or links.
     */
    @Nullable
    public CompositingSpec compositingAt(long timelineMs, float frameW, float frameH) {
        return MaskAnimator.resolve(compositing, maskLinkPose(), timelineMs, frameW, frameH);
    }

    /** This text's own effect stack, created on first access. @see #fx */
    @NonNull
    public com.fadcam.ui.faditor.fx.FxStack getOrCreateFx() {
        if (fx == null) fx = new com.fadcam.ui.faditor.fx.FxStack();
        return fx;
    }

    /** @see #fx */
    @Nullable
    public com.fadcam.ui.faditor.fx.FxStack getFx() { return fx; }

    /**
     * ONCE ATTACHED, THE INSTANCE STAYS — see {@code Clip.setFx} for the whole story. This
     * used to null an empty stack for tidiness, but {@code ProjectStorage} already skips an
     * empty one when writing, and detaching the object the FX panel is holding meant a later
     * undo restored a stack nothing pointed at.
     */
    public void setFx(@Nullable com.fadcam.ui.faditor.fx.FxStack v) {
        fx = v;
    }

    /** True when this text's own effects would change any pixel. */
    public boolean hasActiveFx() {
        return fx != null && !fx.active().isEmpty();
    }

    @NonNull
    public TextOverlayItem copyWithNewId(@NonNull String newId) {
        TextOverlayItem c = new TextOverlayItem(newId, text, colorInt,
                centerX, centerY, sizeFraction, rotationDeg);
        c.hidden = hidden;
        c.locked = locked;
        c.strokeColorInt = strokeColorInt;
        c.strokeWidthPx = strokeWidthPx;
        c.shadowColorInt = shadowColorInt;
        c.shadowRadiusPx = shadowRadiusPx;
        c.glowColorInt = glowColorInt;
        c.glowRadiusPx = glowRadiusPx;
        c.backgroundColorInt = backgroundColorInt;
        c.bold = bold;
        c.italic = italic;
        c.underline = underline;
        c.textCase = textCase;
        c.textAlign = textAlign;
        c.shadowAngleDeg = shadowAngleDeg;
        c.shadowDistancePx = shadowDistancePx;
        c.motionStartMs = motionStartMs;
        c.motionEndMs = motionEndMs;
        c.opacity = opacity;
        c.fontFamily = fontFamily;
        c.imageUri = imageUri;
        c.startMs = startMs;
        c.endMs = endMs;
        c.hostClipId = hostClipId;
        c.hostOffsetMs = hostOffsetMs;
        c.textAnimPreset = textAnimPreset;
        c.textAnimGranularity = textAnimGranularity;
        c.textAnimInPct = textAnimInPct;
        c.textAnimOutPct = textAnimOutPct;
        c.keyframes.copyFrom(keyframes);
        // Deep-copied, never shared: two texts fed by one stack would grade together.
        c.fx = fx == null ? null : fx.copy();
        c.generatedSource = generatedSource == null ? null : generatedSource.copy();
        c.timerSpec = timerSpec == null ? null : timerSpec.copy();
        c.styleSpans = copySpans(styleSpans);
        c.scaleX = scaleX;
        c.scaleY = scaleY;
        c.scaleLinked = scaleLinked;
        // SPEC G: a duplicate is the object's twin — its mirror rides along.
        c.flipH = flipH;
        c.flipV = flipV;
        // SPEC B: a duplicate is the object's twin — its pivot rides along like the rest of
        // the static pose it belongs with.
        c.rotationPivotX = rotationPivotX;
        c.rotationPivotY = rotationPivotY;
        System.arraycopy(cornerPin, 0, c.cornerPin, 0, CornerPin.SIZE);
        c.passThrough = passThrough;
        c.compositing = compositing == null ? null : compositing.copy();
        // SPEC E: a duplicate is the object's twin — its bend rides along (deep copy, never
        // shared; two items fed by one spec would bend together). Null stays null: every
        // pre-mesh duplicate is byte-identical.
        c.mesh = mesh == null ? null : mesh.copy();
        if (c.mesh != null) c.installMeshCurve();
        // Same rule for the rig: a duplicate that SHARED its pins with the original would let
        // renaming one rename both, and deleting a pin in one corrupt the other's bone indices.
        c.puppet = puppet == null ? null : puppet.copy();
        c.overlayBlendMode = overlayBlendMode;
        c.imageAnimPreset = imageAnimPreset == null ? null : imageAnimPreset.copy();
        c.imageFadeInMs = imageFadeInMs;
        c.imageFadeOutMs = imageFadeOutMs;
        return c;
    }

    @NonNull
    public String getId() { return id; }

    @NonNull
    public String getText() { return text; }

    public void setText(@NonNull String text) { this.text = text; }

    public int getColorInt() { return colorInt; }

    public void setColorInt(int colorInt) { this.colorInt = colorInt; }

    public int getStrokeColorInt() { return strokeColorInt; }
    public void setStrokeColorInt(int strokeColorInt) { this.strokeColorInt = strokeColorInt; }

    public float getStrokeWidthPx() { return strokeWidthPx; }
    public void setStrokeWidthPx(float strokeWidthPx) { this.strokeWidthPx = Math.max(0f, strokeWidthPx); }

    public int getShadowColorInt() { return shadowColorInt; }
    public void setShadowColorInt(int shadowColorInt) { this.shadowColorInt = shadowColorInt; }

    public float getShadowRadiusPx() { return shadowRadiusPx; }
    public void setShadowRadiusPx(float shadowRadiusPx) { this.shadowRadiusPx = Math.max(0f, shadowRadiusPx); }

    public int getGlowColorInt() { return glowColorInt; }
    public void setGlowColorInt(int glowColorInt) { this.glowColorInt = glowColorInt; }

    public float getGlowRadiusPx() { return glowRadiusPx; }
    public void setGlowRadiusPx(float glowRadiusPx) { this.glowRadiusPx = Math.max(0f, glowRadiusPx); }

    public int getBackgroundColorInt() { return backgroundColorInt; }
    public void setBackgroundColorInt(int backgroundColorInt) { this.backgroundColorInt = backgroundColorInt; }

    public float getCenterX() { return centerX; }

    public float getCenterY() { return centerY; }

    public void setCenter(float x, float y) {
        this.centerX = Math.max(-centerLimitX, Math.min(1f + centerLimitX, x));
        this.centerY = Math.max(-centerLimitY, Math.min(1f + centerLimitY, y));
    }

    /**
     * Set how far the centre may travel beyond each canvas edge, in canvas fractions.
     * Always at least one half-frame so a tiny overlay can still be moved off-screen
     * (otherwise the user's "just off the screen" is unachievable for small objects).
     */
    public void setCenterTravelLimit(float x, float y) {
        this.centerLimitX = Math.max(0.5f, x);
        this.centerLimitY = Math.max(0.5f, y);
    }

    /** How far the centre may travel beyond each canvas edge (canvas fractions). */
    public float getCenterLimitX() { return centerLimitX; }

    /** How far the centre may travel beyond each canvas edge (canvas fractions). */
    public float getCenterLimitY() { return centerLimitY; }

    public float getSizeFraction() { return sizeFraction; }

    public void setSizeFraction(float sizeFraction) {
        this.sizeFraction = Math.max(0.02f, Math.min(10f, sizeFraction));
    }

    public float getRotationDeg() { return rotationDeg; }

    /**
     * <b>No modulo on purpose.</b> Rotation is keyframed (SPEC A): 370° and 10° are the same
     * POSE but a different ANIMATION, so the stored value keeps its winding — 720 stays 720,
     * -45 stays -45. Display layers may fold degrees into a familiar window; this setter,
     * on the storage path, may not. Non-finite input falls back to 0 exactly as
     * {@code Clip.setSpineRotationDeg} does.
     */
    public void setRotationDeg(float rotationDeg) {
        this.rotationDeg = Float.isNaN(rotationDeg) || Float.isInfinite(rotationDeg)
                ? 0f : rotationDeg;
    }

    // ── Per-axis scale + chain (image-overlay drawer) ─────────────────

    /** Effective X multiplier (linked mode = 1). */
    public float getScaleX() { return scaleX; }

    /** Effective Y multiplier (linked mode = 1). */
    public float getScaleY() { return scaleY; }

    public void setScaleX(float scaleX) {
        this.scaleX = Math.max(0.02f, Math.min(10f, scaleX));
    }

    public void setScaleY(float scaleY) {
        this.scaleY = Math.max(0.02f, Math.min(10f, scaleY));
    }

    public boolean isScaleLinked() { return scaleLinked; }

    public void setScaleLinked(boolean scaleLinked) { this.scaleLinked = scaleLinked; }

    /** True when the picture is mirrored horizontally (SPEC G). */
    public boolean isFlipH() { return flipH; }

    public void setFlipH(boolean flipH) { this.flipH = flipH; }

    /** True when the picture is mirrored vertically (SPEC G). */
    public boolean isFlipV() { return flipV; }

    public void setFlipV(boolean flipV) { this.flipV = flipV; }

    /** True when either mirror flag stands — the picture draws mirrored. */
    public boolean hasMirror() { return flipH || flipV; }

    /**
     * THE shared mirror definition. -1 when mirrored on that axis, +1 otherwise — the sign
     * every renderer multiplies its draw-time extent (or scale) by. One method so the
     * preview View path, the GL Pip path and the export cannot disagree on what a flip is.
     */
    public float mirrorSignX() { return flipH ? -1f : 1f; }

    /** @see #mirrorSignX — the same definition on the vertical axis. */
    public float mirrorSignY() { return flipV ? -1f : 1f; }

    // ── Rotation pivot (SPEC B) — static, not keyframable ─────────────

    public float rotationPivotXNorm() { return rotationPivotX; }

    public float rotationPivotYNorm() { return rotationPivotY; }

    /**
     * TRUE WHEN THE PIVOT IS THE PICTURE'S CENTRE — rotation turns about the box centre
     * and no pivot fold applies, however the corners are pinned.
     *
     * <p>Deliberately blind to the pins (SPEC K, 2026-09-07): the fold used to anchor on
     * the pinned quad's centroid, whose offset is the distortion mean — unbounded, up to
     * ~2 picture sizes on hard perspective work — so any rotation parked picture,
     * handles and selection box several frame-heights from the pose and read as
     * "disappeared" (a centre pivot with a 2-size distortion mean at ~180° folds ~4
     * sizes off). The box centre is the After Effects anchor too, and every render fast
     * path already agrees on it. Non-centre pivots still anchor on the pinned quad
     * (SPEC B: a corner pick computed on the box floats in empty space beside a
     * distorted picture), where the fraction range ±0.5 keeps the fold bounded.
     *
     * @param pins8 accepted and ignored; kept so every call site reads unchanged
     */
    public boolean isRotationPivotNeutral(@Nullable float[] pins8) {
        return isRotationPivotCentre();
    }

    public boolean isRotationPivotCentre() {
        return rotationPivotX == 0.5f && rotationPivotY == 0.5f;
    }

    /**
     * Set the pivot from normalized fractions. EACH AXIS SNAPS to the nearest of {0, 0.5, 1} —
     * the picker only offers the nine anchors, and snapping here too means a value that has been
     * through storage round-trips to exactly what was saved, so the sparse write's
     * byte-identity test stays a simple equality. Non-finite input falls back to centre, the
     * same defensive shape as {@link #setRotationDeg}.
     */
    public void setRotationPivot(float normX, float normY) {
        this.rotationPivotX = snapPivotAxis(normX);
        this.rotationPivotY = snapPivotAxis(normY);
    }

    private static float snapPivotAxis(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0.5f;
        return v < 0.25f ? 0f : (v > 0.75f ? 1f : 0.5f);
    }

    /**
     * THE SHARED PIVOT ARITHMETIC. The pivot's signed offset from the picture box's CENTRE, in
     * whatever unit {@code pictureSizePx} is measured in (view px, canvas px or normalized
     * frame fractions — every renderer hands in its own box width). The export reads this in
     * {@code ImageOverlayDraw}, the preview in {@code TextOverlayLayer} (View path and
     * {@code fxPipFor}); none of them restates (pivot − 0.5) · size, which is how the two
     * surfaces cannot drift. At the centre pivot this is exactly 0, so every renderer's
     * expression collapses to the anchor it used before this existed.
     */
    public float pivotOffsetFromCentreX(float pictureWidthPx) {
        return (rotationPivotX - 0.5f) * pictureWidthPx;
    }

    /** @see #pivotOffsetFromCentreX(float) — the same definition on the vertical axis. */
    public float pivotOffsetFromCentreY(float pictureHeightPx) {
        return (rotationPivotY - 0.5f) * pictureHeightPx;
    }

    /**
     * SPEC B, pinned pictures — the pivot's offset from the box centre when the picture is
     * CORNER-PINNED. The pins displace and reshape the picture the user actually sees: its
     * visual quad can sit more than a full picture-width away from the box this arithmetic
     * anchored to, so a "top left" pivot computed on the box floated in empty space above the
     * drawn picture (JoyRaptor 2026-09-05: every selector dot pivoted from somewhere else — centre
     * read as top-right, bottom-right as top-right, top-left "way higher up"). The pivot is
     * defined on the PICTURE THE USER SEES: the pinned quad's pose-frame bounding box, which
     * for flat pins IS the box, so every renderer hands in its already-evaluated offsets and
     * unpinned projects take the byte-identical path above.
     *
     * <p>{@code pins8} is the caller's eight evaluated offsets — static or animated, whoever
     * owns the clock evaluates; null or flat collapses to the box arithmetic. The offsets are
     * CornerPin's unit (fractions of the item's own size), the same numbers the preview's pin
     * matrix and the export's {@code cornerPinMatrix} apply, so this cannot disagree with what
     * is on screen.</p>
     */
    public float pivotOffsetFromCentreX(float pictureW, float pictureH, @Nullable float[] pins8) {
        if (CornerPin.isFlat(pins8)) return (rotationPivotX - 0.5f) * pictureW;
        return quadPointX(pictureW, pins8, rotationPivotX, rotationPivotY);
    }

    /** @see #pivotOffsetFromCentreX(float, float, float[]) — the same definition on the vertical axis. */
    public float pivotOffsetFromCentreY(float pictureW, float pictureH, @Nullable float[] pins8) {
        if (CornerPin.isFlat(pins8)) return (rotationPivotY - 0.5f) * pictureH;
        return quadPointY(pictureH, pins8, rotationPivotX, rotationPivotY);
    }

    /**
     * The nine anchors ON THE PINNED QUAD, by bilinear interpolation of its four corners.
     *
     * <p>This replaces a bounding-box formulation (2026-09-05) that mixed two different anchors:
     * it added the quad's VERTEX CENTROID to a fraction of the quad's BOUNDING-BOX SPAN. Those
     * agree only on a symmetric pull. On JoyRaptor's test picture — corners dragged unevenly to near
     * the ±2 wall — they disagreed by a quarter of a picture width, which is precisely the
     * residual he kept reporting after the box-vs-quad fix ("centre still is not 100% aligned").</p>
     *
     * <p>Bilinear is also the RIGHT definition, not merely a corrected one: on a trapezoid a
     * bounding box's "bottom right" is a point in empty space beside the picture, while
     * {@code (u,v) = (1,1)} is the picture's own bottom-right corner. At flat pins it reduces
     * ALGEBRAICALLY to {@code (pivot - 0.5) · size} — the branch above is a fast path, not a
     * different answer — so unpinned projects are byte-identical.</p>
     *
     * <p>Coordinates are the POSE frame, which is the frame the pin matrix outputs into and the
     * frame rotation and scale are applied in (see ImageOverlayDraw: pins are concatenated
     * INSIDE rotate/scale), so this is the same space every caller's cx/cy already lives in.</p>
     */
    private static float quadPointX(float w, @NonNull float[] pins8, float u, float v) {
        float xTL = -w / 2f + pins8[CornerPin.TL * 2] * w;
        float xTR = w / 2f + pins8[CornerPin.TR * 2] * w;
        float xBR = w / 2f + pins8[CornerPin.BR * 2] * w;
        float xBL = -w / 2f + pins8[CornerPin.BL * 2] * w;
        return (1f - u) * (1f - v) * xTL + u * (1f - v) * xTR
                + u * v * xBR + (1f - u) * v * xBL;
    }

    /** @see #quadPointX — the same bilinear point on the vertical axis. */
    private static float quadPointY(float h, @NonNull float[] pins8, float u, float v) {
        float yTL = -h / 2f + pins8[CornerPin.TL * 2 + 1] * h;
        float yTR = -h / 2f + pins8[CornerPin.TR * 2 + 1] * h;
        float yBR = h / 2f + pins8[CornerPin.BR * 2 + 1] * h;
        float yBL = h / 2f + pins8[CornerPin.BL * 2 + 1] * h;
        return (1f - u) * (1f - v) * yTL + u * (1f - v) * yTR
                + u * v * yBR + (1f - u) * v * yBL;
    }

    // ── Corner pin / skew ─────────────────────────────────────────────
    //
    // The distortion the View API cannot express. Read CornerPin's class doc first: it owns the
    // unit (fractions of the item's own untransformed size), the clamp, the eight track names and
    // the setPolyToPoly matrix. Everything here is the item's copy of the state plus the two
    // evaluators — static and animated — that the preview and the export both go through.

    /** One corner component's STATIC value. See {@link CornerPin#TL}/{@link CornerPin#DX}. */
    public float getCornerPin(int corner, int axis) {
        int i = pinIndex(corner, axis);
        return i < 0 ? 0f : cornerPin[i];
    }

    /** Set one corner component, clamped to {@link CornerPin#MAX_OFFSET}. */
    public void setCornerPin(int corner, int axis, float value) {
        int i = pinIndex(corner, axis);
        if (i >= 0) cornerPin[i] = CornerPin.clamp(value);
    }

    /** Set all four corners at once from a packed array; shorter/null input is ignored. */
    public void setCornerPin(@Nullable float[] off8) {
        if (off8 == null || off8.length < CornerPin.SIZE) return;
        for (int i = 0; i < CornerPin.SIZE; i++) cornerPin[i] = CornerPin.clamp(off8[i]);
    }

    /** Copy the STATIC offsets into {@code out8} (length {@link CornerPin#SIZE}). */
    public void copyCornerPinInto(@NonNull float[] out8) {
        if (out8.length < CornerPin.SIZE) return;
        System.arraycopy(cornerPin, 0, out8, 0, CornerPin.SIZE);
    }

    /** Back to undistorted — the state every item starts in. */
    public void clearCornerPin() {
        java.util.Arrays.fill(cornerPin, 0f);
    }

    /**
     * Is this item distorted AT ALL — statically or by any keyframe?
     *
     * <p><b>This is the skip gate every render path checks first.</b> False means the item can
     * take the exact affine path it always did: no matrix solve, no custom draw, no inflated view
     * bounds. It reads the TRACKS rather than sampling a time on purpose — the answer must not
     * flicker between frames of one animation, or the preview would swap the image's View class
     * mid-playback.</p>
     */
    public boolean hasCornerPin() {
        if (!CornerPin.isFlat(cornerPin)) return true;
        for (int c = 0; c < 4; c++) {
            for (int a = 0; a < 2; a++) {
                com.fadcam.ui.faditor.keyframe.KeyframeTrack t =
                        keyframes.get(CornerPin.trackFor(c, a));
                if (t == null || t.isEmpty()) continue;
                for (com.fadcam.ui.faditor.keyframe.Keyframe k : t.keyframes) {
                    if (Math.abs(k.value) > CornerPin.EPSILON) return true;
                }
            }
        }
        return false;
    }

    /**
     * The offsets at {@code timelineMs} — each of the eight tracks evaluated on this item's own
     * local time base, falling back to the static value exactly like every other animated
     * property here.
     */
    public void animatedCornerPin(long timelineMs, @NonNull float[] out8) {
        if (out8.length < CornerPin.SIZE) return;
        long t = localTime(timelineMs);
        for (int c = 0; c < 4; c++) {
            for (int a = 0; a < 2; a++) {
                int i = c * 2 + a;
                out8[i] = CornerPin.clamp(
                        keyframes.valueAt(CornerPin.trackFor(c, a), t, cornerPin[i]));
            }
        }
    }

    /**
     * The corner-pin matrix for this item at {@code timelineMs}, over the untransformed drawn
     * rect {@code (left, top, w, h)} in the CALLER's pixel space.
     *
     * <p>The one method the preview and the export both call, so there is no second transcription
     * of the arithmetic to drift — the same discipline {@code ImageOverlayDraw} already records
     * about mirroring {@code TextOverlayLayer.position}. Concat it INNERMOST, immediately around
     * the bitmap draw and inside the existing translate/rotate/scale; {@link CornerPin#buildMatrix}
     * documents why that order and no other.</p>
     *
     * @return true when {@code out} must be concat-ed; false when the item is undistorted at this
     *         time and the caller should draw exactly as it always did
     */
    public boolean cornerPinMatrix(@NonNull android.graphics.Matrix out, long timelineMs,
                                   float left, float top, float w, float h) {
        if (!hasCornerPin()) { out.reset(); return false; }
        float[] off = new float[CornerPin.SIZE];
        animatedCornerPin(timelineMs, off);
        return CornerPin.buildMatrix(out, left, top, w, h, off);
    }

    /** Index into {@link #cornerPin}, or -1 for a bad corner/axis. */
    private static int pinIndex(int corner, int axis) {
        if (corner < 0 || corner > CornerPin.BL) return -1;
        if (axis != CornerPin.DX && axis != CornerPin.DY) return -1;
        return corner * 2 + axis;
    }

    /**
     * Keyframe one corner component at {@code timelineMs} — the animated write a UI lane makes,
     * routed through {@link #addPropertyKeyframeAt} so easing, the canonical X-track diamond and
     * the shared undo step all behave exactly as they do for position and scale.
     */
    public void addCornerPinKeyframeAt(int corner, int axis, long timelineMs, float value) {
        addPropertyKeyframeAt(CornerPin.trackFor(corner, axis), timelineMs, value);
    }

    // ── Pass-through, blend, compositing ──────────────────────────────

    /** True when the preview ignores touch on this overlay (PiP-style pass-through). */
    public boolean isPassThrough() { return passThrough; }

    public void setPassThrough(boolean passThrough) { this.passThrough = passThrough; }

    @Nullable
    public com.fadcam.ui.faditor.model.CompositingSpec getCompositing() { return compositing; }

    public void setCompositing(@Nullable com.fadcam.ui.faditor.model.CompositingSpec c) {
        this.compositing = c;
    }

    /** Get or lazily create the compositing spec (masks/chroma key/matte). */
    @NonNull
    public com.fadcam.ui.faditor.model.CompositingSpec getOrCreateCompositing() {
        if (compositing == null) compositing = new com.fadcam.ui.faditor.model.CompositingSpec();
        return compositing;
    }

    /** Whether any compositing is configured (used for serialization sparsity). */
    public boolean hasActiveCompositing() {
        return compositing != null && !compositing.isEmpty();
    }

    @NonNull
    public String getOverlayBlendMode() { return overlayBlendMode; }

    public void setOverlayBlendMode(@NonNull String overlayBlendMode) {
        this.overlayBlendMode = overlayBlendMode == null ? "NORMAL" : overlayBlendMode;
    }

    /**
     * Whether this overlay must leave the export's Canvas path for a shader, because it wants a
     * blend mode that has no Canvas expression: blending against the video needs the video, and a
     * {@code BitmapOverlay} has nothing underneath it.
     *
     * <p><b>Lives here, in the model, on purpose.</b> Two places consume it —
     * {@code ImageBlendGlEffect} decides whether to exist, and
     * {@code CompositeExportOverlay.filterTextOverlays} decides whether to drop the item — and
     * they must be EXACTLY complementary. Either one drifting means the image is drawn twice
     * (blended in the shader and plain on top of it) or not at all. One predicate makes that
     * structural instead of something a test has to keep catching.</p>
     *
     * <p>Images only. A text overlay's blend has no export path yet, so promising it one here
     * would drop the text from the canvas and render nothing in its place.</p>
     */
    public boolean wantsExportBlend() {
        return isImage() && BlendModes.modeCode(overlayBlendMode) != 0;
    }

    /**
     * True when this IMAGE overlay carries effect cards that only a shader could run.
     *
     * <p>A Canvas cannot run a fragment shader, so an image carrying effects has to leave the
     * canvas path exactly as a blended one does. Text overlays are excluded for the same reason
     * they are excluded from {@link #wantsExportBlend()}: their effects already reach the export
     * through {@code TextFxGlEffect}, and routing them twice would draw them twice.</p>
     */
    public boolean hasExportFx() {
        return isImage() && hasActiveFx();
    }

    /**
     * True when this IMAGE overlay carries an ACTIVE chroma key, which only a shader can apply.
     *
     * <p>A key with its switch off is inert — {@code ChromaKey.isActive} is false — so an image that
     * has never been keyed keeps the canvas path exactly as before.</p>
     */
    public boolean hasExportKey() {
        return isImage() && com.fadcam.ui.faditor.model.ChromaKey.isActive(compositing);
    }

    /**
     * True when this IMAGE overlay carries an ACTIVE mask (at least one shape).
     *
     * <p>An image with a mask must leave the Canvas path: a plain {@code ImageView} cannot clip to
     * a mask, so the mask is invisible until something else (a blend mode, an effect, a key)
     * drags the image into GL. That is exactly what JoyRaptor saw — "the mask does not show up
     * when set to normal. And if I send it to any blending modes, it does not apply those
     * blending modes to an image underneath it" — the mask and the below-image were both stranded
     * on Canvas (see §3A). A mask list that is empty is inert, so an image that has never been
     * masked keeps the canvas path exactly as before.</p>
     */
    public boolean hasExportMask() {
        return isImage() && compositing != null && !compositing.masks.isEmpty();
    }

    /**
     * The ONE predicate that decides an image overlay leaves the Canvas path for the GL one.
     *
     * <p>Single authority on purpose (the reasoning in {@code 3152cc4}): the emitter and the canvas
     * skip must agree exactly, or an image is drawn twice or not at all. Every caller asks this,
     * never the two halves separately.</p>
     *
     * <p>SPEC E: a bent image ({@link #hasMesh}) rides GL too — the Canvas cannot bend. Gated on
     * {@code isImage()} (v1 images only; a stale mesh on text must never route) and false for
     * every overlay without a warp, so pre-mesh projects are byte-identical.</p>
     */
    public boolean wantsGlExport() {
        return wantsExportBlend() || hasExportFx() || hasExportKey() || hasExportMask()
                || (isImage() && hasMesh());
    }

    /**
     * SPEC E gate: true when a bend is authored. False for null spec, unknown topology, or an
     * identity pose — checked BEFORE any GL object exists, so a project with no bend creates no
     * framebuffer, compiles no program and costs exactly zero (same gate the storage writer and
     * both renderers read).
     */
    public boolean hasMesh() {
        return mesh != null && mesh.hasWarp();
    }

    /** The bend spec, or null for none. Renderers copy it per-frame; never hand the live one out. */
    @Nullable
    public com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec getMesh() { return mesh; }

    /** Set/replace the bend (null clears). Installs the shared easing curve on its track. */
    public void setMesh(@Nullable com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec m) {
        this.mesh = m;
        installMeshCurve();
    }

    /** The puppet rig, or null when this overlay has never been rigged. */
    @Nullable
    public com.fadcam.ui.faditor.puppet.PuppetRig getPuppet() { return puppet; }

    /** Set/replace the rig (null clears every pin, bone and setting). */
    public void setPuppet(@Nullable com.fadcam.ui.faditor.puppet.PuppetRig r) { this.puppet = r; }

    /** The rig, made on first use — so the drawer never has to null-check before showing rows. */
    @NonNull
    public com.fadcam.ui.faditor.puppet.PuppetRig getOrCreatePuppet() {
        if (puppet == null) puppet = new com.fadcam.ui.faditor.puppet.PuppetRig();
        return puppet;
    }

    /**
     * Ensure the pose track eases via the app's single Easing implementation (no second copy).
     *
     * <p>The curve itself moved to {@link com.fadcam.ui.faditor.transform.mesh.MeshCurves} when
     * SPEC Z gave sprites, PiP and the spine a mesh of their own: an easing shared by four object
     * types is not a property of the text/image model, and leaving it here would have meant three
     * other types reaching into this class to find out how a bend eases.
     */
    public void installMeshCurve() {
        com.fadcam.ui.faditor.transform.mesh.MeshCurves.install(mesh);
    }

    /** Mesh time base is LOCAL like every other animated property (see {@code localTime}). */
    public long meshLocalTime(long timelineMs) {
        return Math.max(0, timelineMs - startMs);
    }

    /** Static opacity [0,1] used when there are no OPACITY keyframes. */
    public float getOpacity() { return opacity; }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0f, Math.min(1f, opacity));
    }

    // ── ImageAnimPreset + opacity fade handles (SPEC_20260829_IMAGE_ANIM_PRESETS) ──

    @Nullable public ImageAnimPreset getImageAnimPreset() { return imageAnimPreset; }
    public void setImageAnimPreset(@Nullable ImageAnimPreset p) { this.imageAnimPreset = p; }
    @NonNull public ImageAnimPreset getOrCreateImageAnimPreset() {
        if (imageAnimPreset == null) imageAnimPreset = new ImageAnimPreset();
        return imageAnimPreset;
    }
    public boolean hasActiveImagePreset() { return imageAnimPreset != null && imageAnimPreset.isActive(); }

    // Opacity fades (stackable multiply, not keyframes) — shares gesture code with AudioClip fades
    public long getImageFadeInMs() { return imageFadeInMs; }
    public long getImageFadeOutMs() { return imageFadeOutMs; }
    public long getImageDurationMs(long timelineDurationMs) {
        long end = (endMs == Long.MAX_VALUE || endMs <= 0) ? timelineDurationMs : endMs;
        return Math.max(0L, end - startMs);
    }
    public void setImageFadeInMs(long ms, long timelineDurationMs) {
        long dur = getImageDurationMs(timelineDurationMs);
        if (dur <= 0) { imageFadeInMs = 0; return; }
        ms = Math.max(0, Math.min(ms, dur / 2));
        imageFadeInMs = ms;
        if (imageFadeOutMs > dur / 2) imageFadeOutMs = dur / 2;
        if (imageFadeInMs + imageFadeOutMs > dur) imageFadeOutMs = dur - imageFadeInMs;
    }
    public void setImageFadeOutMs(long ms, long timelineDurationMs) {
        long dur = getImageDurationMs(timelineDurationMs);
        if (dur <= 0) { imageFadeOutMs = 0; return; }
        ms = Math.max(0, Math.min(ms, dur / 2));
        imageFadeOutMs = ms;
        if (imageFadeInMs > dur / 2) imageFadeInMs = dur / 2;
        if (imageFadeInMs + imageFadeOutMs > dur) imageFadeInMs = dur - imageFadeOutMs;
    }
    // Overload without timeline (uses current bounded duration if any)
    public void setImageFadeInMs(long ms) {
        long dur = (endMs == Long.MAX_VALUE ? 0 : endMs - startMs);
        if (dur > 0) setImageFadeInMs(ms, endMs);
        else imageFadeInMs = Math.max(0, ms);
    }
    public void setImageFadeOutMs(long ms) {
        long dur = (endMs == Long.MAX_VALUE ? 0 : endMs - startMs);
        if (dur > 0) setImageFadeOutMs(ms, endMs);
        else imageFadeOutMs = Math.max(0, ms);
    }
    /** Fade multiplier 0..1 at local time (0 = start of this item). Multiplies base opacity. */
    public float imageFadeFactorAt(long localMs, long timelineDurationMs) {
        long dur = getImageDurationMs(timelineDurationMs);
        if (dur <= 0) return 1f;
        if (imageFadeInMs > 0 && localMs < imageFadeInMs) {
            return imageFadeInMs == 0 ? 1f : (float) localMs / (float) imageFadeInMs;
        }
        if (imageFadeOutMs > 0 && localMs > dur - imageFadeOutMs) {
            long remaining = dur - localMs;
            return imageFadeOutMs == 0 ? 1f : Math.max(0f, (float) remaining / (float) imageFadeOutMs);
        }
        return 1f;
    }
    public float imageFadeFactorAtLocal(long localMs) {
        long dur = (endMs == Long.MAX_VALUE ? localMs+imageFadeOutMs+1 : endMs - startMs);
        if (dur <= 0) return 1f;
        if (imageFadeInMs > 0 && localMs < imageFadeInMs) return (float) localMs / (float) imageFadeInMs;
        if (imageFadeOutMs > 0 && localMs > dur - imageFadeOutMs) {
            long rem = dur - localMs; return Math.max(0f, (float) rem / (float) imageFadeOutMs);
        }
        return 1f;
    }

    // Fit / Fill — §3.5 stand-alone, no animation
    /**
     * Scale so whole image is visible inside canvas (letterbox). Keeps center at 0.5,0.5 for predictability.
     * @param canvasW/H canvas size in same units (e.g. preview content rect)
     * @param imgW/H intrinsic image size
     */
    /**
     * Size the image so it FITS inside the canvas (no cropping), centred.
     *
     * <p>JoyRaptor, 2026-09-13: <i>"fit and fill buttons in transform drawer do NOT work."</i> They
     * wrote the STATIC {@code sizeFraction} and nothing else. But the renderer reads
     * {@link #animatedSizeFraction(long)}, which returns the SCALE keyframe track's value whenever
     * that track holds keys and only falls back to the static field when it is empty — so on any
     * animated image the button changed a number nothing was reading. On JoyRaptor's own project
     * 62 of 80 image overlays carry a scale track, which is both why it looked completely broken to
     * him and why it was never caught: the 18 without keys worked perfectly.
     *
     * <p><b>What an animated Fit means.</b> Rescaling a moving image to "fit" is ambiguous, so the
     * rule is the one the words already imply: after Fit the image is never LARGER than the frame,
     * and after Fill never SMALLER than covering it. The whole track is multiplied by a single
     * ratio, so the animation keeps its exact shape and timing and only its overall size changes.
     * Flattening the track to one value would have been easier and would have silently deleted
     * work.
     *
     * <p>Position is left alone when x/y tracks hold keys: those are a motion path the user
     * authored, and re-centring would destroy it. A still image still centres, as before.
     */
    public void applyFit(float canvasW, float canvasH, float imgW, float imgH) {
        applyFitOrFill(canvasW, canvasH, imgW, imgH, true);
    }

    /** Size the image so it COVERS the canvas (cropping the overflow), centred. See {@link #applyFit}. */
    public void applyFill(float canvasW, float canvasH, float imgW, float imgH) {
        applyFitOrFill(canvasW, canvasH, imgW, imgH, false);
    }

    private void applyFitOrFill(float canvasW, float canvasH, float imgW, float imgH, boolean fit) {
        if (canvasW <= 0 || canvasH <= 0 || imgW <= 0 || imgH <= 0) return;
        // The drawn size is sizeFraction * canvasH for height and sizeFraction * canvasH * aspect
        // for width (TextOverlayLayer.imageHeightPx / imageWidthPx), so the fraction that makes
        // the image exactly touch the frame is the ratio of the two aspects, clamped by which
        // axis binds. Fit takes the smaller, Fill the larger — the two differ only there.
        float scale = fit
                ? Math.min(canvasW / imgW, canvasH / imgH)
                : Math.max(canvasW / imgW, canvasH / imgH);
        if (!(scale > 0f)) return;
        float target = (imgH * scale) / canvasH;
        target = Math.max(0.02f, Math.min(10f, target));

        com.fadcam.ui.faditor.keyframe.KeyframeTrack scaleTrack =
                keyframes.get(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE);
        boolean animatedScale = scaleTrack != null && !scaleTrack.keyframes.isEmpty();

        if (animatedScale) {
            // Reference = the extreme the rule speaks about: Fit bounds the LARGEST the image
            // ever gets, Fill bounds the SMALLEST. Using the static field as the reference would
            // be meaningless here — it is precisely the value nothing reads.
            float ref = fit ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
            for (com.fadcam.ui.faditor.keyframe.Keyframe k : scaleTrack.keyframes) {
                ref = fit ? Math.max(ref, k.value) : Math.min(ref, k.value);
            }
            if (!(ref > 0f) || Float.isInfinite(ref)) return;
            float ratio = target / ref;
            if (!(ratio > 0f) || Float.isNaN(ratio)) return;
            for (com.fadcam.ui.faditor.keyframe.Keyframe k : scaleTrack.keyframes) {
                k.value = Math.max(0.02f, Math.min(10f, k.value * ratio));
            }
            // Keep the static field consistent with the track it shadows, so a later edit that
            // deletes every key does not resurrect a stale size.
            setSizeFraction(sizeFraction * ratio);
        } else {
            setSizeFraction(target);
        }

        // Per-axis multipliers: only meaningful to reset when nothing is animating them.
        if (!keyframes.hasProperty(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE_X)
                && !keyframes.hasProperty(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE_Y)) {
            setScaleX(1f);
            setScaleY(1f);
            setScaleLinked(true);
        }

        // A motion path is deliberate work — centre only a still image.
        if (!keyframes.hasProperty(com.fadcam.ui.faditor.keyframe.KeyframeSet.X)
                && !keyframes.hasProperty(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y)) {
            setCenter(0.5f, 0.5f);
        }

        // Fit/Fill are static framing decisions, not animations — an amber preset would fight
        // the size that was just chosen.
        if (hasActiveImagePreset()) { clearImagePresetOwnership(); }
    }

    // ── IMAGE_ANIM_PRESETS V2 — FULL RESET semantics (§1) ────────────────────────
    /** True if any track has presetOwned keys */
    public boolean hasPresetOwnedKeys() {
        for (com.fadcam.ui.faditor.keyframe.KeyframeTrack tr : keyframes.tracks()) {
            for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) if (k.presetOwned) return true;
        }
        return false;
    }
    /** Whether this item has custom animation (spec §3.7 replace warning): >2 keys on any track OR 2 not exactly at ends, and not presetOwned swap. */
    public boolean hasCustomAnimation(long timelineDurationMs) {
        long dur = getImageDurationMs(timelineDurationMs);
        for (com.fadcam.ui.faditor.keyframe.KeyframeTrack tr : keyframes.tracks()) {
            if (tr.keyframes.isEmpty()) continue;
            boolean allPreset = true;
            for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) if (!k.presetOwned) { allPreset = false; break; }
            if (allPreset) continue;
            if (tr.keyframes.size() > 2) return true;
            if (tr.keyframes.size() == 2) {
                long t0 = tr.keyframes.get(0).timeMs, t1 = tr.keyframes.get(1).timeMs;
                if (t0 != 0 || t1 != dur) return true;
            }
            if (tr.keyframes.size() == 1) {
                long t = tr.keyframes.get(0).timeMs;
                if (t != 0 && t != dur) return true;
            }
        }
        return false;
    }
    /** Single helper: delete EVERY presetOwned key on EVERY track (§1 step 1). */
    private void deleteAllPresetOwnedKeys() {
        for (String p : new String[]{
                com.fadcam.ui.faditor.keyframe.KeyframeSet.X,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.Y,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE_X,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE_Y,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY}) {
            com.fadcam.ui.faditor.keyframe.KeyframeTrack tr = keyframes.get(p);
            if (tr != null) {
                java.util.Iterator<com.fadcam.ui.faditor.keyframe.Keyframe> it = tr.keyframes.iterator();
                while (it.hasNext()) if (it.next().presetOwned) it.remove();
                if (tr.isEmpty()) keyframes.removeProperty(p);
            }
        }
        // Any additional track that may have presetOwned (e.g. future props) — sweep all tracks too
        // KeyframeSet.tracks() returns Iterable, not Collection, so the ArrayList(Collection)
        // constructor does not apply — copy explicitly. The copy itself is REQUIRED: the loop
        // calls removeProperty(), which mutates the very map being iterated.
        java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> allTracks =
                new java.util.ArrayList<>();
        for (com.fadcam.ui.faditor.keyframe.KeyframeTrack t0 : keyframes.tracks()) allTracks.add(t0);
        for (com.fadcam.ui.faditor.keyframe.KeyframeTrack tr : allTracks) {
            java.util.Iterator<com.fadcam.ui.faditor.keyframe.Keyframe> it = tr.keyframes.iterator();
            while (it.hasNext()) if (it.next().presetOwned) it.remove();
            if (tr.isEmpty()) keyframes.removeProperty(tr.property);
        }
    }
    /** §1 step 2 — reset static transform to centred, clear rotation, set scale to cover for new preset. */
    private void resetStaticToCover(float coverFrac) {
        setCenter(0.5f, 0.5f);
        setRotationDeg(0f);
        setScaleLinked(true);
        setScaleX(1f);
        setScaleY(1f);
        setSizeFraction(Math.max(0.02f, Math.min(10f, coverFrac)));
    }
    /** Clear preset ownership on every key and reset preset to NONE. Also deletes the owned keys. */
    public void clearImagePresetOwnership() {
        deleteAllPresetOwnedKeys();
        if (imageAnimPreset != null) imageAnimPreset.kind = ImageAnimPreset.Kind.NONE;
    }
    /** Full reset with no keys — "No animation (reset)" §2.6. Centred, cover-scaled, static. */
    public void resetToStaticCover(float canvasW, float canvasH, float imgW, float imgH) {
        deleteAllPresetOwnedKeys();
        float cover = computeCoverScale(canvasW, canvasH, imgW, imgH);
        resetStaticToCover(cover);
        // Also reset preset params to defaults so next zoom is centred
        if (imageAnimPreset != null) {
            imageAnimPreset.kind = ImageAnimPreset.Kind.NONE;
            imageAnimPreset.zoomCenterX = 0.5f;
            imageAnimPreset.zoomCenterY = 0.5f;
            imageAnimPreset.zoomRegionScale = 0.68f;
            imageAnimPreset.rotationDelta = 0f;
        }
    }
    /** Re-place owned keys at new ends (sticky bookends). Single helper for all trim sites (§2.1). */
    public void reflowPresetOwnedKeys(long timelineDurationMs) {
        long dur = getImageDurationMs(timelineDurationMs);
        if (dur <= 0) return;
        for (com.fadcam.ui.faditor.keyframe.KeyframeTrack tr : keyframes.tracks()) {
            java.util.List<com.fadcam.ui.faditor.keyframe.Keyframe> owned = new java.util.ArrayList<>();
            for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) if (k.presetOwned) owned.add(k);
            if (owned.isEmpty()) continue;
            java.util.Collections.sort(owned, (a,b)-> Long.compare(a.timeMs,b.timeMs));
            float startVal = owned.get(0).value;
            com.fadcam.ui.faditor.keyframe.Easing ease0 = owned.get(0).easing;
            float endVal = owned.get(owned.size()-1).value;
            com.fadcam.ui.faditor.keyframe.Easing ease1 = owned.size()>1 ? owned.get(owned.size()-1).easing : ease0;
            java.util.Iterator<com.fadcam.ui.faditor.keyframe.Keyframe> it = tr.keyframes.iterator();
            while (it.hasNext()) if (it.next().presetOwned) it.remove();
            com.fadcam.ui.faditor.keyframe.Keyframe nk0 = new com.fadcam.ui.faditor.keyframe.Keyframe(0, startVal, ease0);
            nk0.presetOwned = true;
            com.fadcam.ui.faditor.keyframe.Keyframe nk1 = new com.fadcam.ui.faditor.keyframe.Keyframe(dur, endVal, ease1);
            nk1.presetOwned = true;
            tr.keyframes.add(nk0); tr.keyframes.add(nk1);
            java.util.Collections.sort(tr.keyframes, (a,b)-> Long.compare(a.timeMs,b.timeMs));
        }
    }
    /** One helper for every trim site — shift + reflow in one call (§2.1). */
    public void reflowPresetOwnedKeysToCurrentDuration(long timelineDurationMs) {
        if (hasPresetOwnedKeys()) reflowPresetOwnedKeys(timelineDurationMs);
    }
    /**
     * Apply a preset: FULL RESET (§1) then derive from geometry alone (§1 step 3).
     * Returns false if refused (pan with no overhang). No mutation on refusal.
     */
    public boolean applyImagePreset(@NonNull ImageAnimPreset.Kind kind, float canvasW, float canvasH, float imgW, float imgH, long timelineDurationMs) {
        if (canvasW <= 0 || canvasH <= 0 || imgW <= 0 || imgH <= 0) return false;
        long dur = getImageDurationMs(timelineDurationMs);
        if (dur <= 0) dur = 5000;
        // Validate before any mutation (§1 invariant + §2.3 overhang check must not delete on refusal)
        if (kind == ImageAnimPreset.Kind.NONE) {
            // §2.6 None = reset with no keys — still a full reset, deletes owned
            deleteAllPresetOwnedKeys();
            if (imageAnimPreset == null) imageAnimPreset = new ImageAnimPreset();
            imageAnimPreset.kind = ImageAnimPreset.Kind.NONE;
            imageAnimPreset.zoomCenterX = 0.5f;
            imageAnimPreset.zoomCenterY = 0.5f;
            imageAnimPreset.zoomRegionScale = 0.68f;
            imageAnimPreset.rotationDelta = 0f;
            float coverNone = computeCoverScale(canvasW, canvasH, imgW, imgH);
            resetStaticToCover(coverNone);
            return true;
        }
        if (!isImage()) return false; // no mutation for non-image
        boolean isPan = isPanKind(kind);
        if (isPan) {
            boolean horizontal = (kind == ImageAnimPreset.Kind.PAN_LEFT || kind == ImageAnimPreset.Kind.PAN_RIGHT);
            float panFillScale = horizontal ? canvasH / imgH : canvasW / imgW;
            float renderedPanW = imgW * panFillScale;
            float renderedPanH = imgH * panFillScale;
            float extra = horizontal ? (renderedPanW - canvasW) : (renderedPanH - canvasH);
            if (extra <= 1f) return false; // no room to pan — refuse without mutation (§2.3)
        }
        // §1 step 1 — delete every presetOwned key on every track (only after validation)
        deleteAllPresetOwnedKeys();
        // Prepare preset descriptor and FULL RESET params to defaults (§1 step 2 — as if dropped in)
        if (imageAnimPreset == null) imageAnimPreset = new ImageAnimPreset();
        // FULL RESET of tunable params — invariant: fresh apply must be byte-identical regardless of history
        imageAnimPreset.zoomCenterX = 0.5f;
        imageAnimPreset.zoomCenterY = 0.5f;
        imageAnimPreset.zoomRegionScale = 0.68f;
        imageAnimPreset.rotationDelta = 0f;
        imageAnimPreset.kind = kind;
        com.fadcam.ui.faditor.keyframe.Easing ease = com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT;
        boolean isZoom = kind == ImageAnimPreset.Kind.ZOOM_IN || kind == ImageAnimPreset.Kind.ZOOM_OUT;
        boolean isSlide = isSlideKind(kind);
        if (isPan) {
            // §2.3 — scale to cover the axis panning across, travel full overhang
            boolean horizontal = (kind == ImageAnimPreset.Kind.PAN_LEFT || kind == ImageAnimPreset.Kind.PAN_RIGHT);
            float panFillScale = horizontal ? canvasH / imgH : canvasW / imgW;
            float panCoverFrac = (imgH * panFillScale) / canvasH;
            panCoverFrac = Math.max(0.02f, Math.min(10f, panCoverFrac));
            // §1 step 2 — reset static to pan cover
            resetStaticToCover(panCoverFrac);
            // Full overhang, not 90% — recompute extra after validation (same as pre-delete check)
            float renderedPanW2 = imgW * panFillScale;
            float renderedPanH2 = imgH * panFillScale;
            float extra2 = horizontal ? (renderedPanW2 - canvasW) : (renderedPanH2 - canvasH);
            float panRange = extra2 / (horizontal ? canvasW : canvasH);
            // Write keys — SCALE constant
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, 0, panCoverFrac, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, dur, panCoverFrac, ease, dur);
            if (horizontal) {
                if (kind == ImageAnimPreset.Kind.PAN_LEFT) {
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, 0.5f + panRange/2f, ease, dur);
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, dur, 0.5f - panRange/2f, ease, dur);
                } else {
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, 0.5f - panRange/2f, ease, dur);
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, dur, 0.5f + panRange/2f, ease, dur);
                }
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, 0, 0.5f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, dur, 0.5f, ease, dur);
            } else {
                if (kind == ImageAnimPreset.Kind.PAN_UP) {
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, 0, 0.5f + panRange/2f, ease, dur);
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, dur, 0.5f - panRange/2f, ease, dur);
                } else {
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, 0, 0.5f - panRange/2f, ease, dur);
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, dur, 0.5f + panRange/2f, ease, dur);
                }
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, 0.5f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, dur, 0.5f, ease, dur);
            }
        } else if (isZoom) {
            float cover = computeCoverScale(canvasW, canvasH, imgW, imgH);
            // §1 step 2 — start with cover; final zoomed scale may be larger after no-peek check
            resetStaticToCover(cover);
            float regionScale = imageAnimPreset.zoomRegionScale;
            float zoomedScale = cover / Math.max(0.2f, regionScale);
            // §2.4 no-peek check: compute minimum scale to cover at focal point
            float zx = imageAnimPreset.zoomCenterX, zy = imageAnimPreset.zoomCenterY;
            float minAtCenter = computeMinScaleToCoverAt(zx, zy, canvasW, canvasH, imgW, imgH);
            zoomedScale = Math.max(zoomedScale, minAtCenter);
            zoomedScale = Math.max(zoomedScale, cover); // never below cover
            // Also ensure cover itself covers at 0.5 (it does by definition) — but if focal is far edge, zoomed needs more
            float startScale, endScale;
            if (kind == ImageAnimPreset.Kind.ZOOM_IN) { startScale = cover; endScale = zoomedScale; }
            else { startScale = zoomedScale; endScale = cover; }
            // Re-apply static scale to whichever is larger? Keep static at cover for predictability; keys carry animation
            if (kind == ImageAnimPreset.Kind.ZOOM_IN) {
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, 0.5f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, dur, zx, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, 0, 0.5f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, dur, zy, ease, dur);
            } else {
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, zx, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, dur, 0.5f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, 0, zy, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, dur, 0.5f, ease, dur);
            }
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, 0, startScale, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, dur, endScale, ease, dur);
        } else if (isSlide) {
            // Fit so whole image visible when on screen (§2.5)
            float fitScalePx = Math.min(canvasW / imgW, canvasH / imgH);
            float targetScale = (imgH * fitScalePx) / canvasH;
            targetScale = Math.max(0.02f, Math.min(10f, targetScale));
            resetStaticToCover(targetScale);
            float renderedW = imgW * fitScalePx;
            float renderedH = imgH * fitScalePx;
            float offLeft = -renderedW / (2f * canvasW);
            float offRight = 1f + renderedW / (2f * canvasW);
            float offTop = -renderedH / (2f * canvasH);
            float offBottom = 1f + renderedH / (2f * canvasH);
            float startX = 0.5f, startY = 0.5f, endX = 0.5f, endY = 0.5f;
            switch (kind) {
                case SLIDE_IN_LEFT: startX = offLeft; endX = 0.5f; break;
                case SLIDE_IN_RIGHT: startX = offRight; endX = 0.5f; break;
                case SLIDE_IN_TOP: startY = offTop; endY = 0.5f; break;
                case SLIDE_IN_BOTTOM: startY = offBottom; endY = 0.5f; break;
                case SLIDE_OUT_LEFT: startX = 0.5f; endX = offLeft; break;
                case SLIDE_OUT_RIGHT: startX = 0.5f; endX = offRight; break;
                case SLIDE_OUT_TOP: startY = 0.5f; endY = offTop; break;
                case SLIDE_OUT_BOTTOM: startY = 0.5f; endY = offBottom; break;
                default: break;
            }
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, startX, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, dur, endX, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, 0, startY, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, dur, endY, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, 0, targetScale, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, dur, targetScale, ease, dur);
            // SLIDE has opacity fade in/out? Keep subtle: IN fades 0→1, OUT 1→0, but ensure at t=0 no peek is by position, not opacity. Keep opacity keys for visual polish.
            if (isSlideInKind(kind)) {
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY, 0, 0f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY, dur, 1f, ease, dur);
            } else {
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY, 0, 1f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY, dur, 0f, ease, dur);
            }
        }
        // Rotation delta: full reset also clears previous rotation keys; apply if present
        if (imageAnimPreset.rotationDelta != 0) {
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION, 0, 0f, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION, dur, imageAnimPreset.rotationDelta, ease, dur);
        }
        return true;
    }
    private void putPresetKey(String prop, long t, float v, com.fadcam.ui.faditor.keyframe.Easing e, long dur) {
        com.fadcam.ui.faditor.keyframe.KeyframeTrack tr = keyframes.getOrCreate(prop);
        tr.removeAt(t);
        tr.put(t, v, e);
        for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) if (k.timeMs==t) { k.presetOwned=true; break; }
    }
    private static boolean isPanKind(ImageAnimPreset.Kind k) { return k==ImageAnimPreset.Kind.PAN_LEFT||k==ImageAnimPreset.Kind.PAN_RIGHT||k==ImageAnimPreset.Kind.PAN_UP||k==ImageAnimPreset.Kind.PAN_DOWN; }
    private static boolean isSlideKind(ImageAnimPreset.Kind k) {
        return k==ImageAnimPreset.Kind.SLIDE_IN_LEFT||k==ImageAnimPreset.Kind.SLIDE_IN_RIGHT||k==ImageAnimPreset.Kind.SLIDE_IN_TOP||k==ImageAnimPreset.Kind.SLIDE_IN_BOTTOM
                ||k==ImageAnimPreset.Kind.SLIDE_OUT_LEFT||k==ImageAnimPreset.Kind.SLIDE_OUT_RIGHT||k==ImageAnimPreset.Kind.SLIDE_OUT_TOP||k==ImageAnimPreset.Kind.SLIDE_OUT_BOTTOM;
    }
    private static boolean isSlideInKind(ImageAnimPreset.Kind k) {
        return k==ImageAnimPreset.Kind.SLIDE_IN_LEFT||k==ImageAnimPreset.Kind.SLIDE_IN_RIGHT||k==ImageAnimPreset.Kind.SLIDE_IN_TOP||k==ImageAnimPreset.Kind.SLIDE_IN_BOTTOM;
    }
    private float computeCoverScale(float canvasW, float canvasH, float imgW, float imgH) {
        if (canvasW<=0||canvasH<=0||imgW<=0||imgH<=0) return getSizeFraction();
        float fillScale = Math.max(canvasW / imgW, canvasH / imgH);
        float coverFrac = (imgH * fillScale)/canvasH;
        return Math.max(0.02f, Math.min(10f, coverFrac));
    }
    private float computeFitOrPanCover(float canvasW, float canvasH, float imgW, float imgH, boolean horizontal) {
        float fillScale = horizontal ? canvasH / imgH : canvasW / imgW;
        return Math.max(0.02f, Math.min(10f, (imgH * fillScale)/canvasH));
    }
    /** Minimum sizeFraction to cover canvas when centred at (cx,cy) without peek. */
    private float computeMinScaleToCoverAt(float cx, float cy, float canvasW, float canvasH, float imgW, float imgH) {
        if (canvasW<=0||canvasH<=0||imgW<=0||imgH<=0) return 0.02f;
        float imgAspect = imgW / imgH;
        float maxXDist = Math.max(cx, 1f - cx);
        float maxYDist = Math.max(cy, 1f - cy);
        // Need renderedHalfW >= maxXDist*canvasW and renderedHalfH >= maxYDist*canvasH
        // renderedHalfW = sizeFrac*canvasH*imgAspect/2, renderedHalfH = sizeFrac*canvasH/2
        float needByX = (2f * maxXDist * canvasW) / (canvasH * imgAspect);
        float needByY = 2f * maxYDist;
        float need = Math.max(needByX, needByY);
        return Math.max(0.02f, Math.min(10f, need));
    }
    private boolean isSquareOnSquare(float imgW, float imgH, float canvasW, float canvasH) {
        if (canvasW<=0||canvasH<=0||imgW<=0||imgH<=0) return false;
        float imgAspect = imgW/imgH, canvasAspect = canvasW/canvasH;
        if (Math.abs(imgAspect - canvasAspect) > 0.08f) return false;
        boolean imgSquare = Math.abs(imgAspect - 1f) < 0.08f;
        boolean canvasSquare = Math.abs(canvasAspect - 1f) < 0.12f;
        return imgSquare && canvasSquare;
    }
    /** §2.4 — dragging in preview while ZOOM preset active: edit focal param and re-derive both keys from scratch. */
    public boolean updatePresetFocalPoint(float newCenterX, float newCenterY, float canvasW, float canvasH, float imgW, float imgH, long timelineDurationMs) {
        if (!hasActiveImagePreset()) return false;
        ImageAnimPreset p = imageAnimPreset;
        if (p.kind != ImageAnimPreset.Kind.ZOOM_IN && p.kind != ImageAnimPreset.Kind.ZOOM_OUT) return false;
        p.zoomCenterX = Math.max(0f, Math.min(1f, newCenterX));
        p.zoomCenterY = Math.max(0f, Math.min(1f, newCenterY));
        // Re-derive from scratch preserving the just-edited focal (apply would reset to 0.5)
        return rederiveCurrentPreset(canvasW, canvasH, imgW, imgH, timelineDurationMs);
    }
    /** Re-derive current preset from its stored params without resetting them — for preview edits (§2.4). */
    public boolean rederiveCurrentPreset(float canvasW, float canvasH, float imgW, float imgH, long timelineDurationMs) {
        if (!hasActiveImagePreset()) return false;
        if (canvasW <= 0 || canvasH <= 0 || imgW <= 0 || imgH <= 0) return false;
        ImageAnimPreset.Kind kind = imageAnimPreset.kind;
        if (kind == ImageAnimPreset.Kind.NONE) return false;
        if (!isImage()) return false;
        long dur = getImageDurationMs(timelineDurationMs);
        if (dur <= 0) dur = 5000;
        // Validate pan overhang before mutation (same as apply)
        if (isPanKind(kind)) {
            boolean horizontal = (kind == ImageAnimPreset.Kind.PAN_LEFT || kind == ImageAnimPreset.Kind.PAN_RIGHT);
            float panFillScale = horizontal ? canvasH / imgH : canvasW / imgW;
            float extra = horizontal ? (imgW * panFillScale - canvasW) : (imgH * panFillScale - canvasH);
            if (extra <= 1f) return false;
        }
        // §1 step 1 — delete owned keys, but keep params as the user just edited them
        deleteAllPresetOwnedKeys();
        com.fadcam.ui.faditor.keyframe.Easing ease = com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT;
        boolean isZoom = kind == ImageAnimPreset.Kind.ZOOM_IN || kind == ImageAnimPreset.Kind.ZOOM_OUT;
        boolean isPan = isPanKind(kind);
        boolean isSlide = isSlideKind(kind);
        if (isPan) {
            boolean horizontal = (kind == ImageAnimPreset.Kind.PAN_LEFT || kind == ImageAnimPreset.Kind.PAN_RIGHT);
            float panFillScale = horizontal ? canvasH / imgH : canvasW / imgW;
            float panCoverFrac = (imgH * panFillScale) / canvasH;
            panCoverFrac = Math.max(0.02f, Math.min(10f, panCoverFrac));
            resetStaticToCover(panCoverFrac);
            float extra = horizontal ? (imgW * panFillScale - canvasW) : (imgH * panFillScale - canvasH);
            float panRange = extra / (horizontal ? canvasW : canvasH);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, 0, panCoverFrac, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, dur, panCoverFrac, ease, dur);
            if (horizontal) {
                if (kind == ImageAnimPreset.Kind.PAN_LEFT) {
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, 0.5f + panRange/2f, ease, dur);
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, dur, 0.5f - panRange/2f, ease, dur);
                } else {
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, 0.5f - panRange/2f, ease, dur);
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, dur, 0.5f + panRange/2f, ease, dur);
                }
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, 0, 0.5f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, dur, 0.5f, ease, dur);
            } else {
                if (kind == ImageAnimPreset.Kind.PAN_UP) {
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, 0, 0.5f + panRange/2f, ease, dur);
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, dur, 0.5f - panRange/2f, ease, dur);
                } else {
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, 0, 0.5f - panRange/2f, ease, dur);
                    putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, dur, 0.5f + panRange/2f, ease, dur);
                }
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, 0.5f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, dur, 0.5f, ease, dur);
            }
        } else if (isZoom) {
            float cover = computeCoverScale(canvasW, canvasH, imgW, imgH);
            resetStaticToCover(cover);
            float regionScale = imageAnimPreset.zoomRegionScale;
            float zoomedScale = cover / Math.max(0.2f, regionScale);
            float zx = imageAnimPreset.zoomCenterX, zy = imageAnimPreset.zoomCenterY;
            float minAtCenter = computeMinScaleToCoverAt(zx, zy, canvasW, canvasH, imgW, imgH);
            zoomedScale = Math.max(zoomedScale, minAtCenter);
            zoomedScale = Math.max(zoomedScale, cover);
            float startScale, endScale;
            if (kind == ImageAnimPreset.Kind.ZOOM_IN) { startScale = cover; endScale = zoomedScale; }
            else { startScale = zoomedScale; endScale = cover; }
            if (kind == ImageAnimPreset.Kind.ZOOM_IN) {
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, 0.5f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, dur, zx, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, 0, 0.5f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, dur, zy, ease, dur);
            } else {
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, zx, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, dur, 0.5f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, 0, zy, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, dur, 0.5f, ease, dur);
            }
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, 0, startScale, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, dur, endScale, ease, dur);
        } else if (isSlide) {
            float fitScalePx = Math.min(canvasW / imgW, canvasH / imgH);
            float targetScale = (imgH * fitScalePx) / canvasH;
            targetScale = Math.max(0.02f, Math.min(10f, targetScale));
            resetStaticToCover(targetScale);
            float renderedW = imgW * fitScalePx;
            float renderedH = imgH * fitScalePx;
            float offLeft = -renderedW / (2f * canvasW);
            float offRight = 1f + renderedW / (2f * canvasW);
            float offTop = -renderedH / (2f * canvasH);
            float offBottom = 1f + renderedH / (2f * canvasH);
            float startX = 0.5f, startY = 0.5f, endX = 0.5f, endY = 0.5f;
            switch (kind) {
                case SLIDE_IN_LEFT: startX = offLeft; endX = 0.5f; break;
                case SLIDE_IN_RIGHT: startX = offRight; endX = 0.5f; break;
                case SLIDE_IN_TOP: startY = offTop; endY = 0.5f; break;
                case SLIDE_IN_BOTTOM: startY = offBottom; endY = 0.5f; break;
                case SLIDE_OUT_LEFT: startX = 0.5f; endX = offLeft; break;
                case SLIDE_OUT_RIGHT: startX = 0.5f; endX = offRight; break;
                case SLIDE_OUT_TOP: startY = 0.5f; endY = offTop; break;
                case SLIDE_OUT_BOTTOM: startY = 0.5f; endY = offBottom; break;
                default: break;
            }
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, startX, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, dur, endX, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, 0, startY, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, dur, endY, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, 0, targetScale, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, dur, targetScale, ease, dur);
            if (isSlideInKind(kind)) {
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY, 0, 0f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY, dur, 1f, ease, dur);
            } else {
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY, 0, 1f, ease, dur);
                putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY, dur, 0f, ease, dur);
            }
        }
        if (imageAnimPreset.rotationDelta != 0) {
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION, 0, 0f, ease, dur);
            putPresetKey(com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION, dur, imageAnimPreset.rotationDelta, ease, dur);
        }
        return true;
    }
    /** Legacy shim — prefer updatePresetFocalPoint with canvas/img geometry for correct no-peek. */
    public void updatePresetFromPreview(float newCenterX, float newCenterY, float newScale, float newRotation, long timelineDurationMs) {
        if (!hasActiveImagePreset()) return;
        ImageAnimPreset p = imageAnimPreset;
        if (p.kind == ImageAnimPreset.Kind.ZOOM_IN || p.kind == ImageAnimPreset.Kind.ZOOM_OUT) {
            // If caller has geometry, they should call updatePresetFocalPoint; fallback to direct edit without geometry — not ideal but preserves old behaviour for tests
            p.zoomCenterX = newCenterX; p.zoomCenterY = newCenterY;
            for (com.fadcam.ui.faditor.keyframe.KeyframeTrack tr : keyframes.tracks()) {
                for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) if (k.presetOwned) {
                    if (tr.property.equals(com.fadcam.ui.faditor.keyframe.KeyframeSet.X)) {
                        if (p.kind == ImageAnimPreset.Kind.ZOOM_IN && k.timeMs != 0) k.value = newCenterX;
                        if (p.kind == ImageAnimPreset.Kind.ZOOM_OUT && k.timeMs == 0) k.value = newCenterX;
                    }
                    if (tr.property.equals(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y)) {
                        if (p.kind == ImageAnimPreset.Kind.ZOOM_IN && k.timeMs != 0) k.value = newCenterY;
                        if (p.kind == ImageAnimPreset.Kind.ZOOM_OUT && k.timeMs == 0) k.value = newCenterY;
                    }
                }
            }
        }
    }

    @NonNull
    public String getFontFamily() { return fontFamily; }

    public void setFontFamily(@NonNull String fontFamily) { this.fontFamily = fontFamily; }

    /**
     * Get the Android Typeface for this overlay's font family, WITH bold/italic applied.
     *
     * <p>Baked in here rather than left to each renderer's paint setup: every caller that asks
     * this item for its Typeface already gets bold/italic for free, on both renderers, with no
     * second place that could apply them differently or forget to.</p>
     *
     * <p>Span-aware callers resolve per-run typefaces via {@link #typefaceFor} with the run's
     * family/bold/italic — the same lookup, parameterised.</p>
     */
    @NonNull
    public android.graphics.Typeface getTypeface() {
        return typefaceFor(fontFamily, bold, italic);
    }

    /** THE typeface lookup: one family↔Typeface map, applied for any bold/italic pair. Used by
     * the base style (via {@link #getTypeface}) and by every resolved span run. */
    @NonNull
    public static android.graphics.Typeface typefaceFor(@NonNull String family,
                                                        boolean bold, boolean italic) {
        android.graphics.Typeface base;
        // Custom font file (loaded from storage)
        if (family.startsWith("file:")) {
            // Cached: this is a per-run lookup on every text draw, and Typeface.createFromFile
            // re-parses the font file on each call (see FontLibrary's typeface-cache note).
            base = com.fadcam.ui.faditor.text.FontLibrary.typefaceForFile(family.substring(5));
            if (base == null) base = android.graphics.Typeface.DEFAULT_BOLD;
        } else {
            switch (family) {
                case "serif": base = android.graphics.Typeface.SERIF; break;
                case "serif_italic": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC); break;
                case "mono": base = android.graphics.Typeface.MONOSPACE; break;
                case "mono_bold": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); break;
                case "dramatic": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC); break;
                case "techie": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); break;
                case "designer": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL); break;
                case "classy": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL); break;
                case "classy_italic": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC); break;
                case "trendy": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL); break;
                case "country": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD); break;
                case "popular": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD); break;
                case "popular_italic": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD_ITALIC); break;
                case "light": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL); break;
                case "condensed": base = android.graphics.Typeface.create(
                        "sans-serif-condensed", android.graphics.Typeface.NORMAL); break;
                case "condensed_bold": base = android.graphics.Typeface.create(
                        "sans-serif-condensed", android.graphics.Typeface.BOLD); break;
                case "casual": base = android.graphics.Typeface.create(
                        "casual", android.graphics.Typeface.NORMAL); break;
                case "cursive": base = android.graphics.Typeface.create(
                        "cursive", android.graphics.Typeface.NORMAL); break;
                case "serif_bold": base = android.graphics.Typeface.create(
                        android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD); break;
                case "sans_light": base = android.graphics.Typeface.create(
                        "sans-serif-light", android.graphics.Typeface.NORMAL); break;
                case "sans_thin": base = android.graphics.Typeface.create(
                        "sans-serif-thin", android.graphics.Typeface.NORMAL); break;
                case "sans_medium": base = android.graphics.Typeface.create(
                        "sans-serif-medium", android.graphics.Typeface.NORMAL); break;
                case "sans_black": base = android.graphics.Typeface.create(
                        "sans-serif-black", android.graphics.Typeface.NORMAL); break;
                default: base = android.graphics.Typeface.DEFAULT_BOLD;
            }
        }
        if (!bold && !italic) return base;
        int style = (bold ? android.graphics.Typeface.BOLD : 0)
                | (italic ? android.graphics.Typeface.ITALIC : 0);
        return android.graphics.Typeface.create(base, style);
    }

    @Nullable
    public String getImageUri() { return imageUri; }

    public void setImageUri(@Nullable String imageUri) { this.imageUri = imageUri; }

    /** True if this overlay is an image/PNG rather than text. */
    public boolean isImage() { return imageUri != null; }

    // ── AI-authored animated overlay slide (spec Phase 4) ───────────────

    /** Recipe for an AI-authored transparent overlay slide, or null. */
    @Nullable
    private GeneratedSource generatedSource;

    @Nullable
    public GeneratedSource getGeneratedSource() { return generatedSource; }

    public void setGeneratedSource(@Nullable GeneratedSource gs) {
        this.generatedSource = gs;
    }

    /** True if this overlay renders from an AI-authored PNG frame sequence. */
    public boolean isGeneratedSlide() { return generatedSource != null; }

    // ── Countdown / count-up timer (SPEC_TIMER_OBJECT) ──────────────────

    /**
     * Turns this overlay into a live clock, or {@code null} for ordinary text.
     * Only the displayed STRING changes — every style field above still applies, so a
     * timer inherits the caption look for free. See {@link TimerText}, which is the one
     * authority preview and export both read.
     */
    @Nullable
    private TimerSpec timerSpec;

    @Nullable
    public TimerSpec getTimerSpec() { return timerSpec; }

    public void setTimerSpec(@Nullable TimerSpec spec) { this.timerSpec = spec; }

    /** True if this overlay displays a computed time rather than its authored text. */
    public boolean isTimer() { return timerSpec != null; }

    // ── Layer-track membership (M10) ────────────────────────────────────

    /** Stable id of the layer track this item belongs to, or {@code null} for the default TEXT track. */
    @Nullable
    public String getLayerId() { return layerId; }

    public void setLayerId(@Nullable String layerId) { this.layerId = layerId; }

    // ── Time range ───────────────────────────────────────────────────

    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }

    // ── Entrance / exit animation ───────────────────────────────────────────────────

    @NonNull
    public String getTextAnimPreset() { return textAnimPreset; }

    public void setTextAnimPreset(@NonNull String presetName) {
        this.textAnimPreset = presetName;
    }

    @NonNull
    public String getTextAnimGranularity() { return textAnimGranularity; }

    public void setTextAnimGranularity(@NonNull String granularityName) {
        this.textAnimGranularity = granularityName;
    }

    public float getTextAnimInPct() { return textAnimInPct; }

    public float getTextAnimOutPct() { return textAnimOutPct; }

    /** True when either zone would animate anything. */
    /** Whether any entrance/exit TIMING is stored — regardless of whether a preset uses it. */
    public boolean hasTextAnim() { return textAnimInPct > 0f || textAnimOutPct > 0f; }

    /**
     * Whether this box is actually animating: a real preset AND a zone for it to run in.
     *
     * <p>Distinct from {@link #hasTextAnim} on purpose. Selecting None keeps the timings so a
     * user comparing options does not lose work they plotted out, so "has timing stored" and "is
     * animating" stopped being the same question. Anything describing what the viewer SEES must
     * ask this one; anything describing what is STORED asks the other. TextBoxRenderer already
     * gates on the same pair of conditions, which is what makes kept-but-inert zones safe.
     */
    public boolean isTextAnimActive() {
        return hasTextAnim()
                && !"NONE".equals(textAnimPreset);
    }

    /**
     * Set both zones at once, each 0…0.5 of this box's visible span.
     *
     * <p>One setter, because the constraint is on their SUM — the same funnel rule the
     * caption zones use, so the picker, a caret drag and an AI edit all inherit the clamp
     * rather than each remembering it. At 0.5/0.5 the entrance ends exactly where the exit
     * begins, which is the user's stated model and holds at every span length because the
     * zones are fractions.</p>
     *
     * <p>Excess comes off the EXIT zone: one control moves at a time, and the one being
     * moved keeps the value that was asked for.</p>
     */
    public void setTextAnimZonePct(float inPct, float outPct) {
        float in = clampTextZonePct(inPct);
        float out = clampTextZonePct(outPct);
        if (in + out > 1f) out = 1f - in;
        this.textAnimInPct = in;
        this.textAnimOutPct = out;
    }

    private static float clampTextZonePct(float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.max(0f, Math.min(0.5f, v));
    }

    /**
     * The span the zones are fractions OF, resolved against the timeline.
     *
     * <p>{@link #endMs} defaults to {@code Long.MAX_VALUE} ("to the end"), and a fraction of
     * an unbounded span is not a duration — it would overflow before it animated. So an open
     * end resolves to the timeline's total, which is what "to the end" already means
     * everywhere else. Returns 0 when there is nothing to animate over, and every caller
     * treats 0 as "no animation" rather than dividing by it.</p>
     */
    public long animSpanMs(long timelineDurationMs) {
        long end = (endMs == Long.MAX_VALUE || endMs <= 0) ? timelineDurationMs : endMs;
        return Math.max(0L, end - startMs);
    }

    /**
     * Whether a granularity can actually be honoured for a TEXT BOX.
     *
     * <p><b>ALL of them, since 2026-07-30.</b> Both surfaces now draw a text box glyph by glyph
     * through ONE shared renderer, {@code TextBoxRenderer} — the preview via
     * {@code TextBoxView.onDraw} and the export via {@code CompositeExportOverlay}, which no
     * longer rasterises the box to a bitmap first. There is one layout and one set of per-unit
     * transforms, so a granularity cannot mean different things on the two sides.</p>
     *
     * <p><b>What this used to say, and why the correction matters more than the fix.</b> It read:
     * the preview draws a {@code TextView} which cannot transform individual characters, while the
     * export "draws the same overlay with {@code canvas.drawText}, where per-glyph work IS
     * reachable" — i.e. one side to build. That was true of the primitive and false about the
     * state: the export rasterised the whole box and animated the bitmap, so it was every bit as
     * BLOCK-only as the preview. Anyone planning from the old wording would have estimated half
     * the work and discovered the other half at the end. <b>Do not describe a capability by the
     * API that could provide it; describe it by what the code does.</b></p>
     *
     * <p>Kept as a method rather than deleted because it is the gate the picker asks, and a
     * future surface that genuinely cannot do per-unit work (a widget, a thumbnail) should have
     * somewhere to say so.</p>
     */
    public static boolean textAnimGranularitySupported(@NonNull String granularityName) {
        return true;
    }

    // ── Motion range (SPEC_TEXT_DRAWER follow-up, 2026-08-08) ───────────────────────────────
    // A text box can be on screen far longer than its entrance/exit animation should run —
    // "if you have text on screen for a long period of time, it can still have its animation be
    // short and not stretched the entire duration". -1 = unset, meaning "use the full
    // startMs..endMs span", which is the box's behaviour before this existed and stays the
    // default for every overlay that never opens the motion-range controls.
    private long motionStartMs = -1L;
    private long motionEndMs = -1L;

    public boolean hasMotionRange() { return motionStartMs >= 0L && motionEndMs >= 0L; }

    public long getMotionStartMs() { return motionStartMs; }
    public long getMotionEndMs() { return motionEndMs; }

    public void setMotionRange(long startMs, long endMs) {
        this.motionStartMs = Math.max(0L, startMs);
        this.motionEndMs = Math.max(this.motionStartMs + 1L, endMs);
        // Clamp on the way IN as well as when the span changes. The chips that write this take
        // the live playhead, which the user can park outside the object entirely, so without
        // this "Start here" from beyond the object's end would store a range the object never
        // reaches. Same one funnel, so the two directions cannot disagree.
        clampMotionRangeToSpan();
    }

    public void clearMotionRange() {
        this.motionStartMs = -1L;
        this.motionEndMs = -1L;
    }

    /**
     * The span the entrance/exit zones should evaluate against: the motion range if the user
     * set one, else the object's own full startMs..endMs span (today's behaviour, unchanged for
     * every overlay that never touches this control).
     */
    public long motionRangeStartMs() { return hasMotionRange() ? motionStartMs : startMs; }

    public long motionRangeEndMs(long timelineDurationMs) {
        return hasMotionRange() ? motionEndMs
                : (endMs == Long.MAX_VALUE || endMs <= 0 ? timelineDurationMs : endMs);
    }

    /**
     * The span the entrance/exit zones evaluate against — {@link #motionRangeEndMs} minus
     * {@link #motionRangeStartMs}. Identical to {@link #animSpanMs} for every overlay without an
     * explicit motion range, which is what keeps the default behaviour byte-for-byte unchanged.
     */
    public long motionSpanMs(long timelineDurationMs) {
        return Math.max(0L, motionRangeEndMs(timelineDurationMs) - motionRangeStartMs());
    }

    public void setTimeRange(long startMs, long endMs) {
        this.startMs = Math.max(0, startMs);
        // Guard against a degenerate range (end at/before start) that would make
        // the overlay invisible everywhere — treat it as "visible to the end".
        this.endMs = (endMs <= this.startMs) ? Long.MAX_VALUE : endMs;
        clampMotionRangeToSpan();
    }

    /**
     * Keep an explicit motion range inside the object's visible span.
     *
     * <p>The motion range is the sub-window the entrance/exit zones evaluate against, and it is
     * stored in absolute project time — so shortening the object leaves it pointing at time the
     * object no longer occupies. Found in JoyRaptor's project on 2026-08-19: a text box trimmed to
     * end at 663891 still carried a motion range ending at 671793, 7.9 SECONDS past its own end.
     * Nothing clamps on the way in, because the object's span and its motion range are set by
     * different controls that never consult each other.
     *
     * <p>The consequence is not cosmetic. {@code motionSpanMs} is the denominator the zone
     * percentages are taken of and {@code unitProgress} divides by it, so a motion range hanging
     * off the end silently rescales the whole animation: the entrance runs at the wrong speed and
     * the exit can be unreachable, because the tail of the range is time that never plays.
     *
     * <p>A range that ends up degenerate is CLEARED rather than pinned to a sliver. Cleared means
     * "use the object's full span", which is the documented default and the behaviour every
     * overlay that never touches these controls already has — whereas a one-millisecond motion
     * window would make the animation flash past in a frame and look like a different bug.
     */
    private void clampMotionRangeToSpan() {
        if (!hasMotionRange()) return;
        long spanEnd = (endMs == Long.MAX_VALUE) ? Long.MAX_VALUE : endMs;
        long s = Math.max(startMs, motionStartMs);
        long e = Math.min(spanEnd, motionEndMs);
        if (e <= s) {
            clearMotionRange();
            return;
        }
        motionStartMs = s;
        motionEndMs = e;
    }

    /**
     * Set the window with TRIM semantics: the keys stay where they are in PROJECT time.
     *
     * <p><b>Why this is a second method rather than a flag on {@link #setTimeRange}.</b> The two
     * gestures mean opposite things and both are correct:</p>
     * <ul>
     *   <li><b>Move</b> — dragging the object along the timeline takes its animation with it, so
     *       the keys keep their LOCAL times and {@link #setTimeRange} is exactly right.</li>
     *   <li><b>Trim</b> — dragging an EDGE changes how much of the object is shown, not when its
     *       animation happens. The right edge already behaves this way for free: extending the end
     *       moves no key, because the start (the key time base) never moves. The LEFT edge did
     *       not: {@code localTime} is measured from {@code startMs}, so pulling the front earlier
     *       dragged every key later in project time along with it. Reported as: "extend the front
     *       and the keyframes go with it — if I wanted that I could just move the object."</li>
     * </ul>
     *
     * <p>The rebase is by the amount the START actually moved, read back after the set so a clamp
     * cannot desynchronise it. Keys pushed before zero are KEPT rather than clamped — see
     * {@link com.fadcam.ui.faditor.keyframe.KeyframeSet#shiftAll} for why that is what makes
     * dragging the handle out and back again exactly reversible.</p>
     *
     * <p>Only the TRANSFORM keys are rebased. An FX card's parameter keys live on
     * {@code FxStack.keys} and are resolved against absolute timeline ms, so they are already
     * where they belong and moving them would be the bug this fixes, in the other direction.</p>
     */
    public void setTrimmedTimeRange(long startMs, long endMs) {
        // Legacy path — infer timeline duration from the new end when bounded
        long timelineDurForReflow = (endMs == Long.MAX_VALUE || endMs <= 0) ? (startMs + 5000) : endMs;
        setTrimmedTimeRange(startMs, endMs, timelineDurForReflow);
    }
    /** Trim with sticky bookends — ONE helper for every trim site (§2.1). */
    public void setTrimmedTimeRange(long startMs, long endMs, long timelineDurationMs) {
        long before = this.startMs;
        setTimeRange(startMs, endMs);
        long after = this.startMs;
        if (after != before) keyframes.shiftAll(before - after);
        // SPEC E: mesh poses ride the same rebase (same reversible contract as KeyframeSet.shiftAll
        // — negative times kept, so trimming out and back restores exactly). Null-safe: every
        // pre-mesh trim is byte-identical.
        if (after != before && mesh != null && mesh.track() != null) {
            try { mesh.track().shiftAll(before - after); } catch (Exception ignored) { }
        }
        if (hasPresetOwnedKeys()) reflowPresetOwnedKeys(timelineDurationMs);
    }

    /** Whether this overlay should be drawn at the given timeline time. */
    public boolean isVisibleAt(long timelineMs) {
        return timelineMs >= startMs && timelineMs <= endMs;
    }

    // ── Keyframe animation ───────────────────────────────────────────

    @NonNull
    public com.fadcam.ui.faditor.keyframe.KeyframeSet getKeyframes() {
        return keyframes;
    }

    public boolean isAnimated() {
        return keyframes.isAnimated();
    }

    /**
     * "Armed" = has at least one keyframe. Once armed, dragging the overlay at a
     * new playhead time should record a keyframe (and the preview/export read the
     * keyframed value rather than the static one).
     */
    public boolean isArmed() {
        return !keyframes.isEmpty();
    }

    /** Local time (ms from this overlay's start) used as the keyframe time base. */
    private long localTime(long timelineMs) {
        return Math.max(0, timelineMs - startMs);
    }

    public float animatedCenterX(long timelineMs) {
        return keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.X,
                localTime(timelineMs), centerX);
    }

    public float animatedCenterY(long timelineMs) {
        return keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y,
                localTime(timelineMs), centerY);
    }

    /** Animated size fraction (the scale track stores the absolute fraction). */
    public float animatedSizeFraction(long timelineMs) {
        return keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE,
                localTime(timelineMs), sizeFraction);
    }

    /** Per-axis X multiplier at a time (SCALE_X track, falling back to static). */
    public float animatedScaleX(long timelineMs) {
        return keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE_X,
                localTime(timelineMs), scaleX);
    }

    /** Per-axis Y multiplier at a time (SCALE_Y track, falling back to static). */
    public float animatedScaleY(long timelineMs) {
        return keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE_Y,
                localTime(timelineMs), scaleY);
    }

    // ── How large is this image EVER drawn? (the decode bound) ────────────────────────────────
    //
    // THE CHANNEL THE DECODERS WERE READING WAS THE WRONG ONE. Both the preview cache and the
    // export decoder bounded their sample size by scaleX/scaleY and the SCALE track "zoom", and
    // pinch-zoom writes NEITHER: it multiplies sizeFraction (TextOverlayLayer's pinch handler),
    // and the drawn height is `sizeFraction * frameHeight * scaleY`. So a 6x zoomed image was
    // decoded at a 1920px edge and then magnified 6x — "I have a heavily zoomed-in image and its
    // quality is abysmal" (JoyRaptor). These two methods are the honest answer, on the model, so the
    // preview and the export cannot read different channels again.

    /**
     * The largest multiple of the FRAME HEIGHT this item's picture is ever drawn at.
     *
     * <p>Reads the extremes rather than sampling a time: interpolation between two keys never
     * exceeds both, so the track IS the set of extremes and one walk of it is exact. The static
     * field is folded in too, because an un-keyframed item animates nothing and its static
     * {@code sizeFraction} is the whole answer — which is precisely the case the old bound
     * missed.</p>
     */
    public float maxDrawnHeightFactor() {
        float size = Math.max(0f, trackMax(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE,
                sizeFraction));
        float mul = Math.max(
                trackMax(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE_X, scaleX),
                trackMax(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE_Y, scaleY));
        return Math.max(0f, size * Math.max(0.02f, mul) * pinExcursionFactor());
    }

    /**
     * The same, per unit of the item's own WIDTH — identical except that width takes Scale X
     * alone. Callers multiply by the source aspect ratio.
     */
    public float maxDrawnWidthFactor() {
        float size = Math.max(0f, trackMax(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE,
                sizeFraction));
        float mul = trackMax(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE_X, scaleX);
        return Math.max(0f, size * Math.max(0.02f, mul) * pinExcursionFactor());
    }

    /**
     * How much a corner pin can stretch the picture past its own rectangle. A homography
     * magnifies locally, so the far edge of a hard tilt needs pixels the un-pinned rect never
     * asked for; one plus the largest corner excursion is a cheap, monotone over-estimate and it
     * is exactly 1 (i.e. free, and byte-identical) for an unpinned item.
     */
    private float pinExcursionFactor() {
        if (!hasCornerPin()) return 1f;
        float worst = 0f;
        for (int c = 0; c < 4; c++) {
            for (int a = 0; a < 2; a++) {
                worst = Math.max(worst, Math.abs(
                        trackMax(CornerPin.trackFor(c, a), Math.abs(cornerPin[c * 2 + a]))));
            }
        }
        return 1f + Math.min(worst, CornerPin.MAX_OFFSET);
    }

    /** {@code staticValue}, or the largest key on {@code property} if that is larger. */
    private float trackMax(@NonNull String property, float staticValue) {
        float max = staticValue;
        try {
            com.fadcam.ui.faditor.keyframe.KeyframeTrack t = keyframes.get(property);
            if (t != null) {
                for (com.fadcam.ui.faditor.keyframe.Keyframe k : t.keyframes) {
                    if (k.value > max) max = k.value;
                }
            }
        } catch (Exception ignored) {
            // A missing or oddly-shaped track just means "no extra zoom" — never a reason to
            // fail a decode.
        }
        return max;
    }

    /**
     * Record the overlay's current static transform as a keyframe at the given
     * timeline time (position, size, rotation, and opacity). Repeating this at
     * different times with different transforms produces animation.
     */
    public void addKeyframeAt(long timelineMs) {
        long t = localTime(timelineMs);
        com.fadcam.ui.faditor.keyframe.Easing ease =
                com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT;
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.X).put(t, centerX, ease);
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y).put(t, centerY, ease);
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE)
                .put(t, sizeFraction, ease);
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION)
                .put(t, rotationDeg, ease);
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY)
                .put(t, opacity, ease);
    }

    /**
     * Add a keyframe with an explicit opacity value (for fade in/out animation).
     */
    public void addOpacityKeyframeAt(long timelineMs, float opacity) {
        addPropertyKeyframeAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY,
                timelineMs, opacity);
    }

    /**
     * G2 (gesture contract §2): keyframe-aware single-property write — drop/update
     * a keyframe for ONE property at the given timeline time with an explicit
     * value, anchoring the shared X/Y/SCALE pose tracks at that time exactly like
     * {@link #addOpacityKeyframeAt} always has (the timeline uses X as the
     * canonical key-time list). Values are clamped to the same ranges as the
     * static setters.
     */
    public void addPropertyKeyframeAt(@NonNull String property, long timelineMs, float value) {
        long t = localTime(timelineMs);
        com.fadcam.ui.faditor.keyframe.Easing ease =
                com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT;
        // Seed the sibling transform tracks so the timeline's canonical X track has a diamond at
        // this moment — but SEED ONLY, and from the value the object actually has AT t.
        //
        // This used to overwrite X, Y and SCALE at t on EVERY keyframe write, with the STATIC
        // fields. Two things went wrong, and between them they account for most of "keyframing in
        // images moving doesn't work very well" (JoyRaptor, 2026-08-12). Dragging a corner to scale
        // wrote position keys as a side effect, so an object animating across frame was yanked
        // back to its static centre and its motion path was rewritten by a gesture that had
        // nothing to do with position. And each of those writes clamped X and Y to centerLimit,
        // which is recomputed from the object's size only when it is next DRAWN — so straight
        // after scaling up, the clamp still belonged to the old smaller size. When one axis was
        // pinned by a stale limit and the other was not, dragging slid along a single axis: "it
        // sometimes won't let me, like a lock to vertical".
        seedTransformTrack(com.fadcam.ui.faditor.keyframe.KeyframeSet.X,
                animatedCenterX(timelineMs), t, ease);
        seedTransformTrack(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y,
                animatedCenterY(timelineMs), t, ease);
        seedTransformTrack(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE,
                animatedSizeFraction(timelineMs), t, ease);
        float v = value;
        // Corner-pin components share one clamp and there are eight of them, so they are matched
        // by name ahead of the switch rather than as eight near-identical cases.
        if (CornerPin.isPinTrack(property)) {
            keyframes.getOrCreate(property).put(t, CornerPin.clamp(value), ease);
            return;
        }
        switch (property) {
            // centerLimit is the object's own half-extent, refreshed when it is drawn; the rails
            // fall back to the fixed ones for anything small. Written as max() of the two so a
            // stale (too small) limit can no longer pin an axis — see the note above.
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.X:
                v = clampCenter(value, centerLimitX);
                break;
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.Y:
                v = clampCenter(value, centerLimitY);
                break;
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY:
                v = Math.max(0f, Math.min(1f, value));
                break;
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE:
                v = Math.max(0.02f, Math.min(10f, value));
                break;
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE_X:
                v = Math.max(0.02f, Math.min(10f, value));
                break;
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE_Y:
                v = Math.max(0.02f, Math.min(10f, value));
                break;
            default:
                break; // rotation is unclamped (degrees)
        }
        keyframes.getOrCreate(property).put(t, v, ease);
    }

    /**
     * Put {@code value} on {@code property} at {@code t} ONLY if that track has no key there yet.
     * An existing key is the user's, and a write for a different property must not move it.
     */
    private void seedTransformTrack(@NonNull String property, float value, long t,
                                    @NonNull com.fadcam.ui.faditor.keyframe.Easing ease) {
        com.fadcam.ui.faditor.keyframe.KeyframeTrack tr = keyframes.getOrCreate(property);
        for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) {
            if (k.timeMs == t) return;
        }
        tr.put(t, value, ease);
    }

    /** Position clamp: the object's own half-extent, never tighter than the fixed rails. */
    private static float clampCenter(float value, float limit) {
        float lo = Math.min(com.fadcam.ui.faditor.keyframe.KeyframeSet.POS_MIN, -limit);
        float hi = Math.max(com.fadcam.ui.faditor.keyframe.KeyframeSet.POS_MAX, 1f + limit);
        return Math.max(lo, Math.min(hi, value));
    }

    /**
     * Move a shared transform keyframe to a new local time on every keyed
     * property. Timeline diamonds use the X track as the canonical time list,
     * but the user's intent is to move the whole pose at that moment.
     */
    public boolean moveKeyframeLocalTime(long oldLocalMs, long newLocalMs) {
        newLocalMs = Math.max(0, newLocalMs);
        boolean moved = false;
        boolean wasPresetOwned = false;
        for (com.fadcam.ui.faditor.keyframe.KeyframeTrack track : keyframes.tracks()) {
            com.fadcam.ui.faditor.keyframe.Keyframe found = null;
            for (com.fadcam.ui.faditor.keyframe.Keyframe keyframe : track.keyframes) {
                if (keyframe.timeMs == oldLocalMs) {
                    found = keyframe.copy();
                    break;
                }
            }
            if (found != null) {
                if (found.presetOwned) wasPresetOwned = true;
                track.removeAt(oldLocalMs);
                track.put(newLocalMs, found.value, found.easing);
                for (com.fadcam.ui.faditor.keyframe.Keyframe kk : track.keyframes) {
                    if (kk.timeMs == newLocalMs) { kk.presetOwned = found.presetOwned; break; }
                }
                moved = true;
            }
        }
        if (wasPresetOwned) {
            // Spec §3.2: dragging an owned key in the timeline converts — clear every flag + preset.
            clearImagePresetOwnership();
        }
        return moved;
    }

    /** Remove all animation, leaving the static transform. */
    public void clearKeyframes() {
        for (String p : new String[]{
                com.fadcam.ui.faditor.keyframe.KeyframeSet.X,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.Y,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY,
                com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION}) {
            com.fadcam.ui.faditor.keyframe.KeyframeTrack tr = keyframes.get(p);
            if (tr != null) {
                while (!tr.keyframes.isEmpty()) {
                    keyframes.removeKey(p, tr.keyframes.get(0).timeMs);
                }
            }
        }
    }

    // ── STYLE-row keyframes (SPEC_TEXT_DRAWER) ──────────────────────────────────────────
    //
    // A SEPARATE set of track names from the pose keyframe above (X/Y/SCALE/ROTATION/OPACITY),
    // and deliberately NOT routed through addPropertyKeyframeAt/addKeyframeAt: those always seed
    // X/Y/SCALE too, because they arm the "move the whole pose" idiom the canvas drag uses.
    // Keying a stroke width has nothing to do with the object's position, and forcing a position
    // keyframe on it would silently arm canvas-drag animation the user never asked for. Colour
    // itself is NOT keyframeable — no numeric colour track exists anywhere in this codebase
    // (position/scale/rotation/opacity are the full set), so these key the same SIZE value the
    // sliders already are, which is consistent with that limit rather than a new one.
    public static final String TRACK_STROKE_WIDTH = "textStrokeWidth";
    public static final String TRACK_GLOW_RADIUS = "textGlowRadius";
    public static final String TRACK_SHADOW_RADIUS = "textShadowRadius";
    public static final String TRACK_SHADOW_ANGLE = "textShadowAngle";
    public static final String TRACK_SHADOW_DISTANCE = "textShadowDistance";

    private void putStyleKeyframe(@NonNull String track, long timelineMs, float value) {
        keyframes.getOrCreate(track).put(localTime(timelineMs), value,
                com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT);
    }

    public void addStrokeKeyframeAt(long timelineMs) {
        putStyleKeyframe(TRACK_STROKE_WIDTH, timelineMs, strokeWidthPx);
    }

    public void addGlowKeyframeAt(long timelineMs) {
        putStyleKeyframe(TRACK_GLOW_RADIUS, timelineMs, glowRadiusPx);
    }

    /**
     * ONE keyframe entry covering angle + distance + blur together — JoyRaptor's "all of those
     * values will go under the same keyframe": scrubbing the direction knob, dragging distance
     * or dragging blur all arm and record the same three tracks at once, so the shadow always
     * animates as one coherent motion rather than three independently-timed ones.
     */
    public void addShadowKeyframeAt(long timelineMs) {
        putStyleKeyframe(TRACK_SHADOW_ANGLE, timelineMs, shadowAngleDeg);
        putStyleKeyframe(TRACK_SHADOW_DISTANCE, timelineMs, shadowDistancePx);
        putStyleKeyframe(TRACK_SHADOW_RADIUS, timelineMs, shadowRadiusPx);
    }

    public boolean isStrokeArmed() { return keyframes.get(TRACK_STROKE_WIDTH) != null
            && !keyframes.get(TRACK_STROKE_WIDTH).isEmpty(); }
    public boolean isGlowArmed() { return keyframes.get(TRACK_GLOW_RADIUS) != null
            && !keyframes.get(TRACK_GLOW_RADIUS).isEmpty(); }
    public boolean isShadowArmed() { return keyframes.get(TRACK_SHADOW_ANGLE) != null
            && !keyframes.get(TRACK_SHADOW_ANGLE).isEmpty(); }

    public float animatedStrokeWidthPx(long timelineMs) {
        return keyframes.valueAt(TRACK_STROKE_WIDTH, localTime(timelineMs), strokeWidthPx);
    }

    public float animatedGlowRadiusPx(long timelineMs) {
        return keyframes.valueAt(TRACK_GLOW_RADIUS, localTime(timelineMs), glowRadiusPx);
    }

    public float animatedShadowRadiusPx(long timelineMs) {
        return keyframes.valueAt(TRACK_SHADOW_RADIUS, localTime(timelineMs), shadowRadiusPx);
    }

    public float animatedShadowAngleDeg(long timelineMs) {
        return keyframes.valueAt(TRACK_SHADOW_ANGLE, localTime(timelineMs), shadowAngleDeg);
    }

    public float animatedShadowDistancePx(long timelineMs) {
        return keyframes.valueAt(TRACK_SHADOW_DISTANCE, localTime(timelineMs), shadowDistancePx);
    }

    public float animatedOpacity(long timelineMs) {
        float base = keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY,
                localTime(timelineMs), opacity);
        // Stackable fade handles multiply the base opacity (spec §3.6) — images AND text
        // (FADE_KNOBS §2.5: text shares the same 0..1 intensity host; knob drag writes
        // imageFadeInMs/OutMs for both, preview + export both read animatedOpacity).
        if (imageFadeInMs > 0 || imageFadeOutMs > 0) {
            long local = localTime(timelineMs);
            // Need duration to compute fade factor; use start/end directly if bounded, else local+1
            long dur = (endMs == Long.MAX_VALUE ? local + imageFadeOutMs + 1 : endMs - startMs);
            float factor;
            if (imageFadeInMs > 0 && local < imageFadeInMs) factor = (float) local / (float) imageFadeInMs;
            else if (imageFadeOutMs > 0 && local > dur - imageFadeOutMs) {
                long rem = dur - local; factor = Math.max(0f, (float) rem / (float) imageFadeOutMs);
            } else factor = 1f;
            return base * factor;
        }
        return base;
    }

    public float animatedRotation(long timelineMs) {
        return keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION,
                localTime(timelineMs), rotationDeg);
    }

    // ── Undo snapshot ────────────────────────────────────────────────

    /**
     * Immutable deep copy of the mutable transform/time/keyframe state of an
     * overlay, used by undo/redo to capture a before/after of a drag gesture
     * (canvas move/scale/rotate, timeline range edge drag, or keyframe move).
     */
    public static final class TransformSnapshot {
        private final float centerX, centerY, sizeFraction, rotationDeg, opacity;
        private final float scaleX, scaleY;
        private final boolean scaleLinked;
        private final boolean flipH, flipV;
        // SPEC B — the rotation pivot rides the snapshot, so a pose undo/redo restores it
        // together with the centre it was compensating (a pivot pick shifts the centre to keep
        // the picture still; the pair must move as one).
        private final float rotationPivotX, rotationPivotY;
        /** Corner-pin offsets, copied not shared — the item's array is mutated in place. */
        @NonNull private final float[] cornerPin;
        /** SPEC E bend, deep-copied (a pose undo/redo restores the bend with the centre it rode). */
        @Nullable private final com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec mesh;
        private final long startMs, endMs;
        private final long imageFadeInMs, imageFadeOutMs;
        @Nullable private final ImageAnimPreset imageAnimPreset;
        @NonNull private final com.fadcam.ui.faditor.keyframe.KeyframeSet keyframes;

        private TransformSnapshot(@NonNull TextOverlayItem o) {
            this.centerX = o.centerX;
            this.centerY = o.centerY;
            this.sizeFraction = o.sizeFraction;
            this.rotationDeg = o.rotationDeg;
            this.opacity = o.opacity;
            this.scaleX = o.scaleX;
            this.scaleY = o.scaleY;
            this.scaleLinked = o.scaleLinked;
            // SPEC G: the mirror is pose (a flip and its budget bake must undo as one).
            this.flipH = o.flipH;
            this.flipV = o.flipV;
            this.rotationPivotX = o.rotationPivotX;
            this.rotationPivotY = o.rotationPivotY;
            this.cornerPin = o.cornerPin.clone();
            this.mesh = o.mesh == null ? null : o.mesh.copy();
            this.startMs = o.startMs;
            this.endMs = o.endMs;
            this.imageFadeInMs = o.imageFadeInMs;
            this.imageFadeOutMs = o.imageFadeOutMs;
            this.imageAnimPreset = o.imageAnimPreset == null ? null : o.imageAnimPreset.copy();
            this.keyframes = o.keyframes.copy();
        }

        /** True when this snapshot is value-identical to {@code other}. */
        public boolean matches(@NonNull TransformSnapshot other) {
            if (centerX != other.centerX
                    || centerY != other.centerY
                    || sizeFraction != other.sizeFraction
                    || rotationDeg != other.rotationDeg
                    || opacity != other.opacity
                    || scaleX != other.scaleX
                    || scaleY != other.scaleY
                    || scaleLinked != other.scaleLinked
                    || flipH != other.flipH
                    || flipV != other.flipV
                    || rotationPivotX != other.rotationPivotX
                    || rotationPivotY != other.rotationPivotY
                    || startMs != other.startMs
                    || endMs != other.endMs
                    || imageFadeInMs != other.imageFadeInMs
                    || imageFadeOutMs != other.imageFadeOutMs) return false;
            if (!java.util.Arrays.equals(cornerPin, other.cornerPin)) return false;
            if (!meshEqual(mesh, other.mesh)) return false;
            if (imageAnimPreset == null && other.imageAnimPreset != null) return false;
            if (imageAnimPreset != null && other.imageAnimPreset == null) return false;
            if (imageAnimPreset != null && other.imageAnimPreset != null) {
                if (imageAnimPreset.kind != other.imageAnimPreset.kind) return false;
                if (imageAnimPreset.zoomCenterX != other.imageAnimPreset.zoomCenterX) return false;
                if (imageAnimPreset.zoomCenterY != other.imageAnimPreset.zoomCenterY) return false;
                if (imageAnimPreset.zoomRegionScale != other.imageAnimPreset.zoomRegionScale) return false;
                if (imageAnimPreset.rotationDelta != other.imageAnimPreset.rotationDelta) return false;
            }
            return keyframesEqual(keyframes, other.keyframes);
        }

        private static boolean keyframesEqual(
                @NonNull com.fadcam.ui.faditor.keyframe.KeyframeSet a,
                @NonNull com.fadcam.ui.faditor.keyframe.KeyframeSet b) {
            java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> ta = trackList(a);
            java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> tb = trackList(b);
            if (ta.size() != tb.size()) return false;
            for (int i = 0; i < ta.size(); i++) {
                com.fadcam.ui.faditor.keyframe.KeyframeTrack x = ta.get(i), y = tb.get(i);
                if (!x.property.equals(y.property)) return false;
                if (x.keyframes.size() != y.keyframes.size()) return false;
                for (int j = 0; j < x.keyframes.size(); j++) {
                    com.fadcam.ui.faditor.keyframe.Keyframe kx = x.keyframes.get(j);
                    com.fadcam.ui.faditor.keyframe.Keyframe ky = y.keyframes.get(j);
                    if (kx.timeMs != ky.timeMs || kx.value != ky.value
                            || kx.easing != ky.easing || kx.presetOwned != ky.presetOwned) return false;
                }
            }
            return true;
        }

        private static java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> trackList(
                @NonNull com.fadcam.ui.faditor.keyframe.KeyframeSet s) {
            java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> out = new java.util.ArrayList<>();
            for (com.fadcam.ui.faditor.keyframe.KeyframeTrack t : s.tracks()) out.add(t);
            return out;
        }

        /**
         * SPEC E bend equality. Null and an identity spec (no warp) count as EQUAL — both mean
         * "no bend" for persistence (toJson writes nothing for identity) and for rendering (the
         * gate is hasWarp), so a snapshot round-trip through an untouched tool must match.
         */
        private static boolean meshEqual(
                @Nullable com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec a,
                @Nullable com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec b) {
            if (a == b) return true;
            boolean aw = a != null && a.hasWarp();
            boolean bw = b != null && b.hasWarp();
            if (!aw && !bw) return true;
            if (!aw || !bw) return false;
            if (a.topology() == null || b.topology() == null) return false;
            if (!a.topology().kind().equals(b.topology().kind())) return false;
            if (!java.util.Arrays.equals(a.topology().params(), b.topology().params())) return false;
            if (!java.util.Arrays.equals(a.handles(), b.handles())) return false;
            com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack ta = a.track();
            com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack tb = b.track();
            if (ta == tb) return true;
            if (ta == null || tb == null) return ta == null ? tb.isEmpty() : ta.isEmpty();
            if (ta.arity() != tb.arity() || ta.size() != tb.size()) return false;
            java.util.List<com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack.Pose> pa = ta.poses();
            java.util.List<com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack.Pose> pb = tb.poses();
            for (int i = 0; i < pa.size(); i++) {
                com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack.Pose x = pa.get(i);
                com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack.Pose y = pb.get(i);
                if (x.timeMs != y.timeMs) return false;
                if (x.presetOwned != y.presetOwned) return false;
                if (x.easing == null ? y.easing != null : !x.easing.equals(y.easing)) return false;
                if (!java.util.Arrays.equals(x.values, y.values)) return false;
            }
            return true;
        }
    }

    /** Capture a deep snapshot of this overlay's transform/time/keyframe state. */
    @NonNull
    public TransformSnapshot snapshotTransform() {
        return new TransformSnapshot(this);
    }

    /** Restore a previously captured {@link TransformSnapshot} (for undo/redo). */
    public void restoreTransform(@NonNull TransformSnapshot s) {
        this.centerX = s.centerX;
        this.centerY = s.centerY;
        this.sizeFraction = s.sizeFraction;
        this.rotationDeg = s.rotationDeg;
        this.opacity = s.opacity;
        this.scaleX = s.scaleX;
        this.scaleY = s.scaleY;
        this.scaleLinked = s.scaleLinked;
        this.flipH = s.flipH;
        this.flipV = s.flipV;
        this.rotationPivotX = s.rotationPivotX;
        this.rotationPivotY = s.rotationPivotY;
        System.arraycopy(s.cornerPin, 0, this.cornerPin, 0, CornerPin.SIZE);
        // SPEC E: bend restores with the pose (deep copy; curve reinstalled for easing parity).
        this.mesh = s.mesh == null ? null : s.mesh.copy();
        if (this.mesh != null) installMeshCurve();
        this.startMs = s.startMs;
        this.endMs = s.endMs;
        this.imageFadeInMs = s.imageFadeInMs;
        this.imageFadeOutMs = s.imageFadeOutMs;
        this.imageAnimPreset = s.imageAnimPreset == null ? null : s.imageAnimPreset.copy();
        this.keyframes.copyFrom(s.keyframes);
    }
    /** Timed drag of a preset-owned key in the timeline must convert to manual (spec §3.2). */
    public boolean convertPresetIfTimelineDrag(long oldLocalMs) {
        // Check if any key at oldLocalMs was presetOwned
        boolean wasOwned = false;
        for (com.fadcam.ui.faditor.keyframe.KeyframeTrack tr : keyframes.tracks()) {
            for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) if (k.timeMs == oldLocalMs && k.presetOwned) { wasOwned = true; break; }
            if (wasOwned) break;
        }
        if (wasOwned) clearImagePresetOwnership();
        return wasOwned;
    }

    /**
     * Convenience factory for an image overlay centred on the video.
     */
    @NonNull
    public static TextOverlayItem createImage(@NonNull String imageUri,
                                              float centerX, float centerY,
                                              float sizeFraction) {
        // WHITE, and a literal on purpose: this is CONTENT, not chrome. An image overlay's
        // colour is a multiply, and white is "untinted". It was 0xFFFFFFFF until a palette
        // sweep read it as a UI grey and pointed it at Studio.INK — which then moved to
        // #E4E4E7 when the ink ramp was corrected, so every new image came in tinted grey.
        // A value that ends up in the exported video must never follow a UI token.
        TextOverlayItem item = new TextOverlayItem("", 0xFFFFFFFF,
                centerX, centerY, sizeFraction, 0f);
        item.setImageUri(imageUri);
        return item;
    }
}
