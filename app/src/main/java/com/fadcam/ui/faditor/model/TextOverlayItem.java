package com.fadcam.ui.faditor.model;

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

    /** Clockwise rotation in degrees. */
    private float rotationDeg;

    /**
     * Static opacity [0,1] used when the overlay has no OPACITY keyframes (the
     * fallback for {@link #animatedOpacity(long)}). 1 = fully opaque.
     */
    private float opacity = 1f;

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
     * {@code CaptionAnimator.Granularity} name. **BLOCK is the only honest value here —
     * see {@link #textAnimGranularitySupported}.**
     */
    @NonNull
    private String textAnimGranularity = "BLOCK";

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

    public void setRotationDeg(float rotationDeg) {
        this.rotationDeg = rotationDeg % 360f;
    }

    /** Static opacity [0,1] used when there are no OPACITY keyframes. */
    public float getOpacity() { return opacity; }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0f, Math.min(1f, opacity));
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
            try {
                base = android.graphics.Typeface.createFromFile(family.substring(5));
            } catch (Exception e) {
                base = android.graphics.Typeface.DEFAULT_BOLD;
            }
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
    public boolean hasTextAnim() { return textAnimInPct > 0f || textAnimOutPct > 0f; }

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
        // Ensure X/Y/SCALE tracks exist so the keyframe time is consistent
        // across all tracks (the timeline uses X as canonical).
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.X).put(t, centerX, ease);
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y).put(t, centerY, ease);
        keyframes.getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE)
                .put(t, sizeFraction, ease);
        float v = value;
        switch (property) {
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.X:
                v = Math.max(-centerLimitX, Math.min(1f + centerLimitX, value));
                break;
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.Y:
                v = Math.max(-centerLimitY, Math.min(1f + centerLimitY, value));
                break;
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY:
                v = Math.max(0f, Math.min(1f, value));
                break;
            case com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE:
                v = Math.max(0.02f, Math.min(10f, value));
                break;
            default:
                break; // rotation is unclamped (degrees)
        }
        keyframes.getOrCreate(property).put(t, v, ease);
    }

    /**
     * Move a shared transform keyframe to a new local time on every keyed
     * property. Timeline diamonds use the X track as the canonical time list,
     * but the user's intent is to move the whole pose at that moment.
     */
    public boolean moveKeyframeLocalTime(long oldLocalMs, long newLocalMs) {
        newLocalMs = Math.max(0, newLocalMs);
        boolean moved = false;
        for (com.fadcam.ui.faditor.keyframe.KeyframeTrack track : keyframes.tracks()) {
            com.fadcam.ui.faditor.keyframe.Keyframe found = null;
            for (com.fadcam.ui.faditor.keyframe.Keyframe keyframe : track.keyframes) {
                if (keyframe.timeMs == oldLocalMs) {
                    found = keyframe.copy();
                    break;
                }
            }
            if (found != null) {
                track.removeAt(oldLocalMs);
                track.put(newLocalMs, found.value, found.easing);
                moved = true;
            }
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
        return keyframes.valueAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY,
                localTime(timelineMs), opacity);
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
        private final long startMs, endMs;
        @NonNull private final com.fadcam.ui.faditor.keyframe.KeyframeSet keyframes;

        private TransformSnapshot(@NonNull TextOverlayItem o) {
            this.centerX = o.centerX;
            this.centerY = o.centerY;
            this.sizeFraction = o.sizeFraction;
            this.rotationDeg = o.rotationDeg;
            this.opacity = o.opacity;
            this.startMs = o.startMs;
            this.endMs = o.endMs;
            this.keyframes = o.keyframes.copy();
        }

        /** True when this snapshot is value-identical to {@code other}. */
        public boolean matches(@NonNull TransformSnapshot other) {
            return centerX == other.centerX
                    && centerY == other.centerY
                    && sizeFraction == other.sizeFraction
                    && rotationDeg == other.rotationDeg
                    && opacity == other.opacity
                    && startMs == other.startMs
                    && endMs == other.endMs
                    && keyframesEqual(keyframes, other.keyframes);
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
                            || kx.easing != ky.easing) return false;
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
        this.startMs = s.startMs;
        this.endMs = s.endMs;
        this.keyframes.copyFrom(s.keyframes);
    }

    /**
     * Convenience factory for an image overlay centred on the video.
     */
    @NonNull
    public static TextOverlayItem createImage(@NonNull String imageUri,
                                              float centerX, float centerY,
                                              float sizeFraction) {
        TextOverlayItem item = new TextOverlayItem("", 0xFFFFFFFF,
                centerX, centerY, sizeFraction, 0f);
        item.setImageUri(imageUri);
        return item;
    }
}
