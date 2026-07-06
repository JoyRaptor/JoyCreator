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

        @NonNull
        MaskShape copy() {
            MaskShape m = new MaskShape();
            m.cx = cx; m.cy = cy; m.w = w; m.h = h;
            m.corner = corner; m.rotationDeg = rotationDeg; m.subtract = subtract;
            return m;
        }
    }

    @NonNull public final List<MaskShape> masks = new ArrayList<>();
    /** false (default): masks cut holes. true: item visible ONLY inside. */
    public boolean invertMasks = false;

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

    @NonNull
    public CompositingSpec copy() {
        CompositingSpec s = new CompositingSpec();
        for (MaskShape m : masks) s.masks.add(m.copy());
        s.invertMasks = invertMasks;
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
                arr.add(mj);
            }
            j.add("masks", arr);
            if (invertMasks) j.addProperty("invertMasks", true);
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
                    s.masks.add(m);
                }
                s.invertMasks = j.has("invertMasks")
                        && j.get("invertMasks").getAsBoolean();
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
