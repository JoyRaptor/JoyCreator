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
 *       underneath"); a shape with {@code subtract=true} protects its region
 *       FROM the hole ("mask out part of my mask" → the kept notch).
 *       {@code invertMasks=true} flips the whole stack into a window: the
 *       item is visible ONLY inside the (add−subtract) region.</li>
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

    /** One rounded-rect mask shape, canvas-normalized. */
    public static class MaskShape {
        public float cx = 0.5f, cy = 0.5f;   // center, 0..1 of canvas
        public float w = 0.3f, h = 0.2f;     // size, 0..1 of canvas
        /** 0 = sharp corners … 1 = fully round (radius = min(w,h)/2). */
        public float corner = 0f;
        public float rotationDeg = 0f;
        /** false = this shape ADDS to the hole/window; true = it SUBTRACTS
         *  (protects its region — the user's "notch" case). */
        public boolean subtract = false;

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
            m.corner = corner; m.rotationDeg = rotationDeg; m.subtract = subtract;
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
     * <p>Applies to {@code masks.get(0)} plus the spec-level {@link #maskFeather}. That is not a
     * shortcut: shape 0 is the ONLY shape any authoring UI can create, and pretending otherwise
     * would mean seven tracks per shape that nothing can reach — dead structure the serializer
     * would then have to keep forever.</p>
     *
     * <p>{@code null} for every project that has never keyed a mask, which is the whole cost of
     * this feature to them: {@link MaskAnimator#resolve} returns the input spec untouched.</p>
     */
    @Nullable public com.fadcam.ui.faditor.keyframe.KeyframeSet maskKeys;

    /** True when any mask parameter animates — the only reason to resolve per frame. */
    public boolean hasMaskKeys() {
        return maskKeys != null && !maskKeys.isEmpty();
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
    public boolean hasFeather() { return !masks.isEmpty() && maskFeather > 0f; }

    /** {@link #maskFeather} = 1 blurs by this fraction of the frame's SHORTER side. */
    public static final float MAX_FEATHER_FRACTION = 0.08f;

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
            for (MaskShape m : masks) {
                JsonObject mj = new JsonObject();
                mj.addProperty("cx", m.cx);
                mj.addProperty("cy", m.cy);
                mj.addProperty("w", m.w);
                mj.addProperty("h", m.h);
                if (m.corner != 0f) mj.addProperty("corner", m.corner);
                if (m.rotationDeg != 0f) mj.addProperty("rot", m.rotationDeg);
                if (m.subtract) mj.addProperty("sub", true);
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
            kj.addProperty("color", String.format("#%06X", keyColor & 0xFFFFFF));
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
                    m.cx = clamp01(optFloat(mj, "cx", 0.5f));
                    m.cy = clamp01(optFloat(mj, "cy", 0.5f));
                    m.w = clamp(optFloat(mj, "w", 0.3f), 0.001f, 1f);
                    m.h = clamp(optFloat(mj, "h", 0.2f), 0.001f, 1f);
                    m.corner = clamp01(optFloat(mj, "corner", 0f));
                    m.rotationDeg = optFloat(mj, "rot", 0f);
                    m.subtract = mj.has("sub") && mj.get("sub").getAsBoolean();
                    m.linkedToObject = mj.has("link") && mj.get("link").getAsBoolean();
                    m.linkBaseX = optFloat(mj, "linkBaseX", 0.5f);
                    m.linkBaseY = optFloat(mj, "linkBaseY", 0.5f);
                    // Never 0: the base scale is a DIVISOR in the link maths, and a hand-edited
                    // 0 would turn every linked mask into NaN geometry.
                    m.linkBaseScale = Math.max(0.001f, optFloat(mj, "linkBaseScale", 1f));
                    m.linkBaseRotDeg = optFloat(mj, "linkBaseRot", 0f);
                    s.masks.add(m);
                }
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
            return (int) (Long.parseLong(hex, 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return 0x00FF00;
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
