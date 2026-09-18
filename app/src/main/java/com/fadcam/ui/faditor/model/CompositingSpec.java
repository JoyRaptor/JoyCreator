package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-item compositing spec (FEEDBACK_20260702 §C, binding architecture read):
 * masks + chroma key + track matte are ONE family, modeled once and evaluated
 * by BOTH the preview compositor and the export effects — never three
 * bolt-ons. Additive schema: an item without a spec (or with an
 * {@link #isEmpty()} one) serializes nothing and renders exactly as before.
 *
 * <p>Semantics of record:</p>
 * <ul>
 *   <li>MASKS (B1): rounded-rect vector shapes in CANVAS-normalized coords
 *       (0..1 of the video content rect — the user authors holes over the
 *       composed frame, and a hole must not travel when the item moves).
 *       Default read: shapes cut HOLES in the item ("reveal what's
 *       underneath"); a {@link #MODE_SUBTRACT} shape protects its region
 *       FROM the hole ("mask out part of my mask" → the kept notch), and a
 *       {@link #MODE_INTERSECT} shape keeps only what is inside both it and
 *       everything folded before it.
 *       {@code invertMasks=true} flips the whole stack into a window: the
 *       item is visible ONLY inside the combined region.</li>
 *   <li>CHROMA KEY (B2): key out {@code keyColor} with tolerance (distance
 *       where alpha starts), fuzziness (softness band width) and offset
 *       (post-key alpha bias, ±1). Evaluated per-pixel in the GL export path;
 *       preview approximation is a separate concern (export = ground truth,
 *       probe-#4 rule).</li>
 *   <li>TRACK MATTE (B3): {@code mattePeerId} names ANOTHER overlay clip
 *       whose LUMINANCE becomes this item's alpha wherever the two overlap in
 *       time (video-as-alpha; luma is the convention for video mattes —
 *       an alpha-channel mode can be added as data later). Only the RECIPIENT
 *       carries the ref; the matte peer is looked up by id at build time and
 *       is hidden from normal rendering while it serves as a matte.</li>
 * </ul>
 *
 * <p>Pure model: gson-only (self-serializing like {@code AvatarRig}); the
 * {@code android.graphics.Path} authority lives in {@code MaskPathBuilder}
 * so this class stays JVM-harness testable. All reads are tolerant with
 * clamped defaults — hand-edited or AI-authored JSON must degrade, not
 * crash.</p>
 */
public class CompositingSpec {

    // ── Boolean modes (M0). The wire keeps writing "sub" forever; see toJson. ──
    /** This shape ADDS to the hole/window. The default, and what every project means. */
    public static final int MODE_ADD = 0;
    /** This shape SUBTRACTS — it protects its region (the user's "notch" case). */
    public static final int MODE_SUBTRACT = 1;
    /** This shape INTERSECTS: only what is inside BOTH it and everything folded so far. */
    public static final int MODE_INTERSECT = 2;

    /** One rounded-rect mask shape, canvas-normalized. */
    public static class MaskShape {
        public float cx = 0.5f, cy = 0.5f;   // center, 0..1 of canvas
        public float w = 0.3f, h = 0.2f;     // size, 0..1 of canvas
        /** 0 = sharp corners … 1 = fully round (radius = min(w,h)/2). */
        public float corner = 0f;
        public float rotationDeg = 0f;

        /**
         * How this shape folds into the stack — {@link #MODE_ADD} / {@link #MODE_SUBTRACT} /
         * {@link #MODE_INTERSECT}. The ONE authority; there is deliberately no {@code subtract}
         * field beside it, because two writable representations of the same fact are how they
         * drift. Read it through {@link #isSubtract()} where only the old binary question
         * matters.
         */
        public int mode = MODE_ADD;

        /**
         * STABLE identity for keyframe namespacing — assigned at creation by
         * {@link CompositingSpec#addShape()}, <b>never reused and never renumbered on delete</b>.
         * Mask keyframe tracks are named off the slot, not the list index; renumbering on delete
         * would silently move shape 3's animation onto shape 2 (spec risk R11), which is the same
         * class of bug as the {@code TrackKind} coercion.
         *
         * <p>Slot 0 is special forever: it keeps the FLAT track names ({@code maskCx} …) that
         * shipped, so an old build still animates it correctly and no migration exists.</p>
         */
        public int slot = 0;

        /**
         * PER-SHAPE soft edge, or {@code -1} meaning "inherit {@link #maskFeather}".
         *
         * <p>Added because one hard edge and one soft edge in the same mask was not
         * expressible: feather was a property of the whole stack, so softening shape 2 softened
         * shape 1 with it (user, 2026-08-06). {@code -1} rather than {@code 0} is the default
         * precisely so "inherit" and "deliberately hard" stay different answers — with 0 as the
         * default, every legacy shape would read as an explicit hard edge and stop following
         * the stack slider.</p>
         *
         * <p>Serialized only when set, and it is a schema-v13 trigger: an older build ignores
         * the key and renders every edge at the stack feather, which is a visibly different
         * picture rather than a missing refinement.</p>
         */
        public float feather = -1f;

        /** True when this shape overrides the stack's soft edge. @see #feather */
        public boolean hasFeatherOverride() { return feather >= 0f; }

        /** The old binary question, derived. There is no {@code subtract} field to disagree. */
        public boolean isSubtract() { return mode == MODE_SUBTRACT; }

        /** True when this shape needs the ordered fold rather than the two-bucket fast path. */
        public boolean isIntersect() { return mode == MODE_INTERSECT; }

        /**
         * LINKED to the object (user, 2026-08-05). {@code false} (default, and what every
         * existing project means) = the mask is authored against the FRAME and stays put while
         * the item animates under it — the "window in the screen" reading the masks shipped
         * with. {@code true} = the mask keeps its position relative to the object, so a mask cut
         * over someone's face travels with them.
         *
         * <p>Both are useful and that is exactly the point: unlinked is how you reveal what is
         * behind a moving PiP, linked is how you cut a shape OUT of it.</p>
         */
        public boolean linkedToObject = false;

        /**
         * The object's pose at the moment {@link #linkedToObject} was switched on — the frame
         * the relative offset is measured FROM. Captured rather than assumed: without it,
         * "relative to the object" has no origin, and the mask would jump the first time the
         * object is anywhere but its default pose. Meaningless while unlinked.
         */
        public float linkBaseX = 0.5f, linkBaseY = 0.5f, linkBaseScale = 1f, linkBaseRotDeg = 0f;

        @NonNull
        MaskShape copy() {
            MaskShape m = new MaskShape();
            m.cx = cx; m.cy = cy; m.w = w; m.h = h;
            m.corner = corner; m.rotationDeg = rotationDeg;
            m.mode = mode; m.slot = slot;
            m.feather = feather;
            m.linkedToObject = linkedToObject;
            m.linkBaseX = linkBaseX; m.linkBaseY = linkBaseY;
            m.linkBaseScale = linkBaseScale; m.linkBaseRotDeg = linkBaseRotDeg;
            return m;
        }
    }

    @NonNull public final List<MaskShape> masks = new ArrayList<>();
    /** false (default): masks cut holes. true: item visible ONLY inside. */
    public boolean invertMasks = false;
    /**
     * Soft edges on the mask stack — 0 (default, a hard {@code clipPath} edge, i.e. exactly
     * the behaviour every project shipped with) … 1 (the softest edge offered). AUTHORED
     * units, not pixels: see {@link #featherRadiusPx}.
     */
    public float maskFeather = 0f;

    /**
     * Animation for the mask's seven authored numbers, in ABSOLUTE timeline ms — the same base
     * a PiP's {@code overlayTransform} uses, deliberately, so one clip does not carry two
     * conventions and the drawer can hand both the same playhead.
     *
     * <p>ONE set for the whole stack, namespaced by {@link MaskShape#slot}: slot 0 keeps the
     * FLAT names ({@code maskCx} …) it shipped with, slots 1..n use {@code mask<slot>.cx}. See
     * {@link MaskAnimator#trackFor}. {@code KeyframeCodec} round-trips arbitrary track names,
     * so multi-shape masks need NO migration and an old build still animates shape 0 exactly as
     * it always did.</p>
     *
     * <p>{@link #maskFeather} stays spec-level — one soften for the stack, matching the single
     * feather bitmap {@code MaskPathBuilder} builds — so its track is flat and belongs to no
     * shape.</p>
     *
     * <p>{@code null} for every project that has never keyed a mask, which is the whole cost of
     * this feature to them: {@link MaskAnimator#resolve} returns the input spec untouched.</p>
     */
    @Nullable public com.fadcam.ui.faditor.keyframe.KeyframeSet maskKeys;

    /** True when any mask parameter animates — the only reason to resolve per frame. */
    public boolean hasMaskKeys() {
        return maskKeys != null && !maskKeys.isEmpty();
    }

    // ── Shape list (M0) ──────────────────────────────────────────────────

    /**
     * Append a shape carrying a fresh, never-before-used {@link MaskShape#slot}.
     *
     * <p>The slot is one past the highest slot ANY current shape holds, not the list size:
     * after deleting the middle of three shapes the list is 2 long while slots 0 and 2 are
     * live, and reusing 1 would hand the new shape the deleted one's keyframe tracks.</p>
     */
    @NonNull
    public MaskShape addShape() {
        MaskShape m = new MaskShape();
        int next = 0;
        for (MaskShape s : masks) next = Math.max(next, s.slot + 1);
        m.slot = next;
        masks.add(m);
        return m;
    }

    /**
     * Remove the shape at {@code index} AND its keyframe tracks. Keyframe-aware on purpose:
     * an orphaned {@code mask2.cx} track survives every save, reappears in any track list the
     * UI builds from {@code maskKeys}, and animates nothing.
     *
     * <p>Slot 0's tracks are the flat spec-level ones, and {@link MaskAnimator#FEATHER} is NOT
     * among them — feather is a property of the stack, not of a shape, so deleting a shape must
     * never take the soften animation with it.</p>
     *
     * @return the removed shape, or null when {@code index} is out of range
     */
    @Nullable
    public MaskShape removeShape(int index) {
        if (index < 0 || index >= masks.size()) return null;
        MaskShape gone = masks.remove(index);
        if (maskKeys != null) {
            for (String track : MaskAnimator.shapeTracks(gone.slot)) {
                maskKeys.removeProperty(track);
            }
        }
        return gone;
    }

    /**
     * True when {@link #toJson} would write a key an older build cannot represent, and the save
     * path must therefore stamp schema v13.
     *
     * <p>TWO triggers, not one. {@code mode} is the obvious case. {@code slot} is the subtle one:
     * it is omitted while every slot still equals its list index, so it only appears after a
     * multi-shape delete — but once it does, an old build reads no slot, renumbers by index, and
     * hands shape 3's keyframe tracks to shape 2 (risk R11). Both must raise the stamp, so both
     * live behind this ONE predicate rather than being re-derived at the call site.</p>
     *
     * <p>Deliberately mirrors {@link #toJson}'s write conditions exactly. If a future key becomes
     * non-additive, add it here in the same commit — a stamp that disagrees with the serializer
     * is silent, permanent data loss.</p>
     */
    public boolean needsSchema13() {
        return usesIntersect() || hasExplicitSlots() || usesPerShapeFeather();
    }

    /**
     * True when some shape overrides the stack's soft edge — the gate that decides whether
     * {@code MaskPathBuilder} builds the feather bitmap the shipped way (ONE blur over the
     * combined path) or per shape. False for every project written before per-shape feather
     * existed, so they provably take the old path.
     */
    public boolean usesPerShapeFeather() {
        for (MaskShape m : masks) if (m.hasFeatherOverride()) return true;
        return false;
    }

    /** The soft edge {@code m} actually renders with: its own override, else the stack's. */
    public float featherOf(@NonNull MaskShape m) {
        return m.hasFeatherOverride() ? m.feather : maskFeather;
    }

    /** True when any shape uses {@link #MODE_INTERSECT} — see {@link #needsSchema13}. */
    public boolean usesIntersect() {
        for (MaskShape m : masks) if (m.isIntersect()) return true;
        return false;
    }

    /**
     * True when some shape's {@link MaskShape#slot} is not its list index, i.e. the JSON has to
     * carry an explicit {@code "slot"} to survive a round-trip. Only reachable after a
     * multi-shape delete, so it is false for every project that exists today.
     */
    public boolean hasExplicitSlots() {
        for (int i = 0; i < masks.size(); i++) if (masks.get(i).slot != i) return true;
        return false;
    }

    // ── Shape presets (data only — no geometry, no android) ──────────────

    public static final int PRESET_SQUARE = 0;
    public static final int PRESET_RECT = 1;
    public static final int PRESET_CIRCLE = 2;
    public static final int PRESET_PILL = 3;

    /**
     * Rewrite {@code m}'s {@code w}/{@code h}/{@code corner} into one of the four shipped
     * presets, leaving centre, rotation, mode, slot and link state alone — a preset is a
     * starting SHAPE, not a reset.
     *
     * <p>Square and Circle equalise w and h to their mean <b>in canvas-normalised units</b>.
     * That is not the same as square in pixels on a non-square frame, and it is the right
     * choice here: this class is deliberately android-free and has no frame size to ask, while
     * {@code MaskPathBuilder.shapePath} already derives the corner radius from
     * {@code min(w*frameW, h*frameH)} — so the pixel-space question belongs there, next to the
     * only code that knows the answer. A caller that wants pixel-square scales {@code w} by the
     * frame aspect afterwards.</p>
     *
     * <p>There is no ellipse: a true ellipse is not expressible through
     * {@code Path.addRoundRect}, and a fully-round rounded rect (Circle/Pill) is. Spec §1.1.</p>
     */
    public static void applyPreset(@NonNull MaskShape m, int preset) {
        switch (preset) {
            case PRESET_SQUARE: {
                float s = clamp((m.w + m.h) * 0.5f, 0.001f, 1f);
                m.w = s; m.h = s; m.corner = 0f;
                break;
            }
            case PRESET_CIRCLE: {
                float s = clamp((m.w + m.h) * 0.5f, 0.001f, 1f);
                m.w = s; m.h = s; m.corner = 1f;
                break;
            }
            case PRESET_PILL:
                m.corner = 1f;
                break;
            case PRESET_RECT:
            default:
                m.corner = 0f;
                break;
        }
    }

    /**
     * Become {@code other}, IN PLACE. Undo needs this: {@code Clip} holds a final reference to
     * its spec, so a snapshot restore cannot reassign the field — the same reason
     * {@code KeyframeSet.copyFrom} exists.
     */
    public void copyFrom(@NonNull CompositingSpec other) {
        if (other == this) return;
        masks.clear();
        for (MaskShape m : other.masks) masks.add(m.copy());
        invertMasks = other.invertMasks;
        maskFeather = other.maskFeather;
        maskKeys = other.maskKeys == null ? null : other.maskKeys.copy();
        keyEnabled = other.keyEnabled;
        keyColor = other.keyColor;
        keyTolerance = other.keyTolerance;
        keyFuzziness = other.keyFuzziness;
        keyOffset = other.keyOffset;
        mattePeerId = other.mattePeerId;
    }

    /** True when the mask travels with the item rather than staying put in the frame. */
    public boolean hasLinkedMask() {
        for (MaskShape m : masks) if (m.linkedToObject) return true;
        return false;
    }

    // ── Chroma key (null-signaled by keyEnabled; primitives keep gson simple) ──
    public boolean keyEnabled = false;
    /** Key color as 0xRRGGBB (alpha ignored). Default pure green. */
    public int keyColor = 0x00FF00;
    /** 0..1 — normalized RGB distance where keying begins. */
    public float keyTolerance = 0.18f;
    /** 0..1 — width of the soft edge band past the tolerance. */
    public float keyFuzziness = 0.10f;
    /** −1..1 — post-key alpha bias (matte choke/spread). */
    public float keyOffset = 0f;

    // ── Track matte ──
    /** Id of the overlay clip serving as this item's luma matte, or null. */
    @Nullable public String mattePeerId;

    /** True when the spec changes nothing — serializer omits it entirely. */
    public boolean isEmpty() {
        return masks.isEmpty() && !keyEnabled && mattePeerId == null;
    }

    public boolean hasMasks() { return !masks.isEmpty(); }

    /** True when the mask stack wants a soft edge — the only reason to pay for a layer. */
    public boolean hasFeather() {
        if (masks.isEmpty()) return false;
        if (maskFeather > 0f) return true;
        // A per-shape override can soften an edge while the STACK slider sits at zero, which
        // is the whole point of it. Asking only about maskFeather here would take the hard
        // clip path and silently discard that shape's soft edge.
        for (MaskShape m : masks) if (m.feather > 0f) return true;
        return false;
    }

    /**
     * {@link #maskFeather} = 1 blurs by this fraction of the frame's SHORTER side.
     *
     * <p>Raised from 0.08 to 0.25 on 2026-08-26. At 8% the slider's MAXIMUM was about 86px on
     * a 1080-wide frame — a tidy edge, not a soft one, and nowhere near enough to fade an
     * object into what is behind it. JoyRaptor: "soften edges is too small, should blur much more
     * at max to make soft gradient." A feathered mask whose softest setting still reads as a
     * cut is a slider that only does one thing.
     *
     * <p>Cheap where it matters: the GL path feathers with a distance band in the shader
     * ({@code MaskSdf.fxCoverageOf}), so a wider band costs the same as a narrow one, and
     * masked images route to GL since {@code hasExportMask} joined {@code wantsGlExport}. Only
     * the legacy Canvas path pays, through {@code BlurMaskFilter} in {@code MaskPathBuilder},
     * whose cost grows with radius — and that path is no longer the one a masked image takes.
     *
     * <p>Preview and export read this same constant through {@link #featherRadiusPx}, so they
     * cannot disagree about how soft "soft" is.</p>
     */
    public static final float MAX_FEATHER_FRACTION = 0.25f;

    /**
     * The single authority turning authored {@link #maskFeather} into a blur radius in
     * pixels. It lives here — on the android-free model class — rather than next to the
     * drawing code, because BOTH renderers must ask the same question: the export draws
     * into a full-size frame while the preview draws into a much smaller content rect, so
     * anything that reached for a fixed pixel radius would make the same slider mean two
     * different softnesses. Scaled off the SHORTER side so the edge stays even on a
     * letterboxed or portrait frame rather than smearing along one axis.
     *
     * <p>This is the same units trap that has been paid for twice already elsewhere in the
     * editor (animation zones in source vs timeline ms). Pinned in the harness.</p>
     */
    public static float featherRadiusPx(float feather, float w, float h) {
        if (Float.isNaN(feather) || feather <= 0f) return 0f;
        if (w <= 0f || h <= 0f) return 0f;
        return clamp01(feather) * MAX_FEATHER_FRACTION * Math.min(w, h);
    }

    @NonNull
    public CompositingSpec copy() {
        CompositingSpec s = new CompositingSpec();
        for (MaskShape m : masks) s.masks.add(m.copy());
        s.invertMasks = invertMasks;
        s.maskFeather = maskFeather;
        s.maskKeys = maskKeys == null ? null : maskKeys.copy();
        s.keyEnabled = keyEnabled;
        s.keyColor = keyColor;
        s.keyTolerance = keyTolerance;
        s.keyFuzziness = keyFuzziness;
        s.keyOffset = keyOffset;
        s.mattePeerId = mattePeerId;
        return s;
    }

    // ── JSON (self-serializing, tolerant read, omit-defaults write) ──────

    @NonNull
    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        if (!masks.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (int i = 0; i < masks.size(); i++) {
                MaskShape m = masks.get(i);
                JsonObject mj = new JsonObject();
                mj.addProperty("cx", m.cx);
                mj.addProperty("cy", m.cy);
                mj.addProperty("w", m.w);
                mj.addProperty("h", m.h);
                if (m.corner != 0f) mj.addProperty("corner", m.corner);
                if (m.rotationDeg != 0f) mj.addProperty("rot", m.rotationDeg);
                // WIRE-COMPATIBILITY SHIM (spec §1.1). "sub" is still the ONLY thing an
                // add/subtract shape writes, and it is written on exactly the same condition
                // as before, so every project that predates modes stays BYTE-IDENTICAL — the
                // property the conditional v13 stamp in ProjectStorage depends on. "mode" is
                // written ONLY for intersect, because that is the only value "sub" cannot
                // express; an old build reads such a shape as plain additive, which is the
                // graceful degradation, and the v13 stamp is what stops it saving that back.
                if (m.isSubtract()) mj.addProperty("sub", true);
                if (m.isIntersect()) mj.addProperty("mode", MODE_INTERSECT);
                // Omitted while the slot IS the list index, which is every shape any build has
                // ever created — only a multi-shape delete can make them diverge. Persisting it
                // is not optional: the keyframe tracks are named off the slot, so a slot that
                // renumbered on reload would hand shape 3's animation to shape 2 (risk R11).
                if (m.slot != i) mj.addProperty("slot", m.slot);
                // Omitted while inheriting, so a stack that softens uniformly -- every project
                // that predates per-shape feather -- stays byte-identical.
                if (m.hasFeatherOverride()) mj.addProperty("feather", m.feather);
                // Omitted while false, so every project that predates linking stays
                // byte-identical — the same additive-schema rule the rest of this class follows.
                if (m.linkedToObject) {
                    mj.addProperty("link", true);
                    mj.addProperty("linkBaseX", m.linkBaseX);
                    mj.addProperty("linkBaseY", m.linkBaseY);
                    mj.addProperty("linkBaseScale", m.linkBaseScale);
                    if (m.linkBaseRotDeg != 0f) mj.addProperty("linkBaseRot", m.linkBaseRotDeg);
                }
                arr.add(mj);
            }
            j.add("masks", arr);
            if (invertMasks) j.addProperty("invertMasks", true);
            // Inside the masks block on purpose: feather with no shapes is inert, so it must
            // not be able to make an otherwise-empty spec look non-empty.
            if (maskFeather > 0f) j.addProperty("feather", maskFeather);
            // Same reasoning: mask keyframes with no shape to animate are inert.
            JsonObject mk = com.fadcam.ui.faditor.keyframe.KeyframeCodec.toJson(maskKeys);
            if (mk != null) j.add("maskKeys", mk);
        }
        if (keyEnabled) {
            JsonObject kj = new JsonObject();
            kj.addProperty("color", String.format("#%06X", keyColor & 0xF4F4F5));
            kj.addProperty("tolerance", keyTolerance);
            kj.addProperty("fuzziness", keyFuzziness);
            if (keyOffset != 0f) kj.addProperty("offset", keyOffset);
            j.add("chromaKey", kj);
        }
        if (mattePeerId != null) {
            JsonObject tj = new JsonObject();
            tj.addProperty("peerId", mattePeerId);
            tj.addProperty("mode", "luma");
            j.add("matte", tj);
        }
        return j;
    }

    @NonNull
    public static CompositingSpec fromJson(@Nullable JsonObject j) {
        CompositingSpec s = new CompositingSpec();
        if (j == null) return s;
        try {
            if (j.has("masks")) {
                JsonArray arr = j.getAsJsonArray("masks");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject mj = arr.get(i).getAsJsonObject();
                    MaskShape m = new MaskShape();
                    // A mask centre may legitimately sit off-stage, so this must NOT clamp to
                    // 0..1 — doing so would quietly drag an off-screen shape back into frame
                    // on every reload. See KeyframeSet.POS_MIN.
                    m.cx = com.fadcam.ui.faditor.keyframe.KeyframeSet.clampPos(
                            optFloat(mj, "cx", 0.5f));
                    m.cy = com.fadcam.ui.faditor.keyframe.KeyframeSet.clampPos(
                            optFloat(mj, "cy", 0.5f));
                    m.w = clamp(optFloat(mj, "w", 0.3f), 0.001f, 1f);
                    m.h = clamp(optFloat(mj, "h", 0.2f), 0.001f, 1f);
                    m.corner = clamp01(optFloat(mj, "corner", 0f));
                    // -1 sentinel survives the clamp: absent means inherit, not "hard edge".
                    float fo = optFloat(mj, "feather", -1f);
                    m.feather = fo < 0f ? -1f : clamp01(fo);
                    m.rotationDeg = optFloat(mj, "rot", 0f);
                    // "mode" wins where present (it is the only carrier of INTERSECT);
                    // otherwise fall back to the legacy boolean, which is what every existing
                    // project carries. An unknown/garbage mode degrades to ADD rather than
                    // taking the project down.
                    boolean legacySub = mj.has("sub") && mj.get("sub").getAsBoolean();
                    m.mode = legacySub ? MODE_SUBTRACT : MODE_ADD;
                    if (mj.has("mode")) {
                        int mode = optInt(mj, "mode", m.mode);
                        if (mode == MODE_ADD || mode == MODE_SUBTRACT || mode == MODE_INTERSECT) {
                            m.mode = mode;
                        }
                    }
                    // Absent slot = "the slot is my index", the implicit numbering every
                    // pre-M0 project has. A duplicate or negative hand-edited slot is repaired
                    // below, after the whole array is read.
                    m.slot = optInt(mj, "slot", i);
                    m.linkedToObject = mj.has("link") && mj.get("link").getAsBoolean();
                    m.linkBaseX = optFloat(mj, "linkBaseX", 0.5f);
                    m.linkBaseY = optFloat(mj, "linkBaseY", 0.5f);
                    // Never 0: the base scale is a DIVISOR in the link maths, and a hand-edited
                    // 0 would turn every linked mask into NaN geometry.
                    m.linkBaseScale = Math.max(0.001f, optFloat(mj, "linkBaseScale", 1f));
                    m.linkBaseRotDeg = optFloat(mj, "linkBaseRot", 0f);
                    s.masks.add(m);
                }
                repairSlots(s.masks);
                s.invertMasks = j.has("invertMasks")
                        && j.get("invertMasks").getAsBoolean();
                s.maskFeather = clamp01(optFloat(j, "feather", 0f));
                s.maskKeys = com.fadcam.ui.faditor.keyframe.KeyframeCodec.fromJson(
                        j.has("maskKeys") ? j.getAsJsonObject("maskKeys") : null);
            }
            if (j.has("chromaKey")) {
                JsonObject kj = j.getAsJsonObject("chromaKey");
                s.keyEnabled = true;
                s.keyColor = parseColor(kj.has("color")
                        ? kj.get("color").getAsString() : "#00FF00");
                s.keyTolerance = clamp01(optFloat(kj, "tolerance", 0.18f));
                s.keyFuzziness = clamp01(optFloat(kj, "fuzziness", 0.10f));
                s.keyOffset = clamp(optFloat(kj, "offset", 0f), -1f, 1f);
            }
            if (j.has("matte")) {
                JsonObject tj = j.getAsJsonObject("matte");
                if (tj.has("peerId")) s.mattePeerId = tj.get("peerId").getAsString();
            }
        } catch (RuntimeException e) {
            // Malformed spec degrades to whatever parsed before the fault —
            // a bad hand-edit must never take the project down.
        }
        return s;
    }

    /** "#RRGGBB" (or "RRGGBB") → 0xRRGGBB; anything unparseable = green. */
    static int parseColor(@Nullable String s) {
        if (s == null) return 0x00FF00;
        String hex = s.startsWith("#") ? s.substring(1) : s;
        try {
            return (int) (Long.parseLong(hex, 16) & 0xF4F4F5);
        } catch (NumberFormatException e) {
            return 0x00FF00;
        }
    }

    /**
     * Make the slots of a freshly-parsed array unique and non-negative, changing nothing when
     * they already are (which is every well-formed file). Hand-edited JSON with two shapes on
     * the same slot would otherwise give them the SAME keyframe tracks — two shapes animating
     * as one, which looks like a broken renderer rather than a bad edit.
     *
     * <p>Repair walks forward and only ever moves a duplicate UP to a free slot, so the first
     * shape holding a slot keeps it and its keyframes.</p>
     */
    private static void repairSlots(@NonNull List<MaskShape> list) {
        java.util.HashSet<Integer> used = new java.util.HashSet<>();
        int next = 0;
        for (MaskShape m : list) {
            if (m.slot < 0 || !used.add(m.slot)) {
                while (!used.add(next)) next++;
                m.slot = next;
            }
        }
    }

    private static int optInt(@NonNull JsonObject j, @NonNull String k, int def) {
        try {
            return j.has(k) ? j.get(k).getAsInt() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static float optFloat(@NonNull JsonObject j, @NonNull String k, float def) {
        try {
            return j.has(k) ? j.get(k).getAsFloat() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static float clamp01(float v) { return clamp(v, 0f, 1f); }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
