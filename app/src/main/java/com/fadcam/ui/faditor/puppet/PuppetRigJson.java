package com.fadcam.ui.faditor.puppet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The rig's WIRE FORMAT — kept out of {@link PuppetRig} on purpose.
 *
 * <p>{@code PuppetRig} and {@link PuppetPin} import nothing at all, which is what lets the JVM
 * harness prove the chain walk and the index renumbering with no device and no gson on the
 * classpath. Putting {@code toJson} on the rig would have dragged gson in and taken that away, so
 * the codec lives here instead — the same split the mesh engine keeps for the same reason.
 *
 * <h3>Omit-empty, like everything else in the project file</h3>
 * <p>{@link #toJson} returns {@code null} for a rig with no pins, so an image that has never been
 * rigged saves BYTE-IDENTICALLY to how it saved before puppeteering existed. That is the rule
 * {@code MeshWarpSpec.toJson} already follows and the reason a schema bump is not needed to open
 * an old project.
 *
 * <p>Settings are written only when they differ from the default, for the same reason: a rig with
 * three pins and untouched sliders is three pins of JSON, not thirty numbers.
 *
 * <h3>Bones are indices, and that is why the reader validates</h3>
 * <p>A bone names its two pins by position. A file edited by hand — or written by a future
 * version that dropped a pin — could point a bone past the end of the list, and the chain walk
 * would then throw somewhere far away from the cause. {@link #fromJson} drops any bone whose ends
 * are not real pins rather than trusting the file, because a rig with one missing bone is
 * recoverable and a crash on open is not.
 */
public final class PuppetRigJson {

    private PuppetRigJson() {}

    private static final String K_PINS = "pins";
    private static final String K_BONES = "bones";
    private static final String K_NAME = "n";
    private static final String K_TYPE = "t";
    private static final String K_MINE = "nm";
    private static final String K_MUTED = "m";
    private static final String K_WEIGHT = "w";

    private static final String K_ROOT = "r";
    private static final String K_TIP = "p";
    private static final String K_LEN = "l";
    private static final String K_STRETCHY = "st";
    private static final String K_LIMITS = "jl";
    private static final String K_BEND = "bs";

    /** @return the rig as JSON, or null when there is nothing worth writing. */
    @Nullable
    public static JsonObject toJson(@Nullable PuppetRig rig) {
        if (rig == null || rig.pinCount() == 0) return null;

        JsonObject o = new JsonObject();

        JsonArray pins = new JsonArray();
        for (PuppetPin p : rig.pins()) {
            JsonObject pj = new JsonObject();
            pj.addProperty(K_NAME, p.name);
            pj.addProperty(K_TYPE, p.type.name());
            // Always written, never omitted: a pin without a position is not a pin.
            pj.addProperty("x", p.restX);
            pj.addProperty("y", p.restY);
            if (p.nameIsMine) pj.addProperty(K_MINE, true);
            if (p.muted) pj.addProperty(K_MUTED, true);
            if (!p.weightIsAuto()) pj.addProperty(K_WEIGHT, p.weight);
            switch (p.type) {
                case STIFF:
                    put(pj, "sa", p.stiffArea, 0.44f);
                    put(pj, "ss", p.stiffStrength, 0.70f);
                    break;
                case DANGLE:
                    put(pj, "sp", p.spring, 0.62f);
                    put(pj, "se", p.settle, 0.38f);
                    put(pj, "ms", p.mass, 0.45f);
                    put(pj, "mx", p.maxStretch, 0.25f);
                    break;
                case FREE:
                    put(pj, "sc", p.scale, 1f);
                    break;
                default:
                    break;      // an anchor has nothing to write
            }
            pins.add(pj);
        }
        o.add(K_PINS, pins);

        if (rig.boneCount() > 0) {
            JsonArray bones = new JsonArray();
            for (PuppetRig.Bone b : rig.bones()) {
                JsonObject bj = new JsonObject();
                bj.addProperty(K_ROOT, b.rootPin);
                bj.addProperty(K_TIP, b.tipPin);
                bj.addProperty(K_NAME, b.name);
                bj.addProperty(K_LEN, b.restLength);
                if (b.stretchy) {
                    bj.addProperty(K_STRETCHY, true);
                    put(bj, "mx", b.maxStretch, 0.35f);
                }
                if (b.jointLimits) {
                    bj.addProperty(K_LIMITS, true);
                    put(bj, "mn", b.minAngleDeg, -170f);
                    put(bj, "ma", b.maxAngleDeg, 170f);
                }
                if (b.bendSign != 1) bj.addProperty(K_BEND, b.bendSign);
                bones.add(bj);
            }
            o.add(K_BONES, bones);
        }

        // Character and recording settings — only where they were moved.
        put(o, "soft", rig.softness, 0.5f);
        put(o, "det", rig.meshDetail, 0.55f);
        put(o, "grav", rig.gravity, 0.60f);
        put(o, "wind", rig.wind, 0f);
        put(o, "wdir", rig.windDirDeg, 0f);
        put(o, "eth", rig.edgeThreshold, 0.12f);
        put(o, "eex", rig.edgeExpansion, 0.02f);
        if (!rig.showPins) o.addProperty("hp", true);
        if (!rig.showBones) o.addProperty("hb", true);
        if (rig.showMesh) o.addProperty("sm", true);
        if (rig.locked) o.addProperty("lock", true);
        if (!rig.recordOnTouch) o.addProperty("nrt", true);
        put(o, "rdet", rig.detail, 0.78f);
        if (rig.blendOutMs != 200) o.addProperty("blend", rig.blendOutMs);
        if (!rig.snapToKeys) o.addProperty("nsnap", true);

        return o;
    }

    /** @return the rig, or null when the JSON holds no usable pins. */
    @Nullable
    public static PuppetRig fromJson(@Nullable JsonObject o) {
        if (o == null || !o.has(K_PINS) || !o.get(K_PINS).isJsonArray()) return null;

        PuppetRig rig = new PuppetRig();
        JsonArray pins = o.getAsJsonArray(K_PINS);
        for (JsonElement e : pins) {
            if (!e.isJsonObject()) continue;
            JsonObject pj = e.getAsJsonObject();
            PuppetPin.Type type = typeOf(str(pj, K_TYPE, "FREE"));
            // addPin names by region; the stored name overwrites it straight after, so a file
            // that somehow lost a name still opens with a sensible one rather than a blank.
            int idx = rig.addPin(type, num(pj, "x", 0.5f), num(pj, "y", 0.5f));
            PuppetPin p = rig.pin(idx);
            String n = str(pj, K_NAME, null);
            if (n != null && !n.trim().isEmpty()) p.name = n;
            p.nameIsMine = bool(pj, K_MINE, false);
            p.muted = bool(pj, K_MUTED, false);
            p.weight = num(pj, K_WEIGHT, PuppetPin.WEIGHT_AUTO);
            p.stiffArea = num(pj, "sa", 0.44f);
            p.stiffStrength = num(pj, "ss", 0.70f);
            p.spring = num(pj, "sp", 0.62f);
            p.settle = num(pj, "se", 0.38f);
            p.mass = num(pj, "ms", 0.45f);
            p.maxStretch = num(pj, "mx", 0.25f);
            p.scale = num(pj, "sc", 1f);
        }
        if (rig.pinCount() == 0) return null;

        if (o.has(K_BONES) && o.get(K_BONES).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(K_BONES)) {
                if (!e.isJsonObject()) continue;
                JsonObject bj = e.getAsJsonObject();
                int root = (int) num(bj, K_ROOT, -1);
                int tip = (int) num(bj, K_TIP, -1);
                // Validate rather than trust: addBone already refuses out-of-range ends,
                // duplicates and cycles, so a damaged file loses a bone instead of crashing.
                int made = rig.addBone(root, tip, num(bj, K_LEN, 0.2f));
                if (made < 0) continue;
                PuppetRig.Bone b = rig.bone(made);
                String bn = str(bj, K_NAME, null);
                if (bn != null && !bn.trim().isEmpty()) b.name = bn;
                b.stretchy = bool(bj, K_STRETCHY, false);
                b.maxStretch = num(bj, "mx", 0.35f);
                b.jointLimits = bool(bj, K_LIMITS, false);
                b.minAngleDeg = num(bj, "mn", -170f);
                b.maxAngleDeg = num(bj, "ma", 170f);
                b.bendSign = (int) num(bj, K_BEND, 1);
            }
        }

        rig.softness = num(o, "soft", 0.5f);
        rig.meshDetail = num(o, "det", 0.55f);
        rig.gravity = num(o, "grav", 0.60f);
        rig.wind = num(o, "wind", 0f);
        rig.windDirDeg = num(o, "wdir", 0f);
        rig.edgeThreshold = num(o, "eth", 0.12f);
        rig.edgeExpansion = num(o, "eex", 0.02f);
        rig.showPins = !bool(o, "hp", false);
        rig.showBones = !bool(o, "hb", false);
        rig.showMesh = bool(o, "sm", false);
        rig.locked = bool(o, "lock", false);
        rig.recordOnTouch = !bool(o, "nrt", false);
        rig.detail = num(o, "rdet", 0.78f);
        rig.blendOutMs = (int) num(o, "blend", 200);
        rig.snapToKeys = !bool(o, "nsnap", false);
        return rig;
    }

    // ── omit-empty helpers ───────────────────────────────────────────────

    private static void put(@NonNull JsonObject o, @NonNull String k, float v, float dflt) {
        if (Math.abs(v - dflt) > 1e-5f) o.addProperty(k, v);
    }

    private static float num(@NonNull JsonObject o, @NonNull String k, float dflt) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsFloat() : dflt;
        } catch (Exception ignored) { return dflt; }
    }

    private static boolean bool(@NonNull JsonObject o, @NonNull String k, boolean dflt) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : dflt;
        } catch (Exception ignored) { return dflt; }
    }

    @Nullable
    private static String str(@NonNull JsonObject o, @NonNull String k, @Nullable String dflt) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : dflt;
        } catch (Exception ignored) { return dflt; }
    }

    @NonNull
    private static PuppetPin.Type typeOf(@Nullable String name) {
        if (name == null) return PuppetPin.Type.FREE;
        for (PuppetPin.Type t : PuppetPin.Type.values()) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        return PuppetPin.Type.FREE;
    }
}
