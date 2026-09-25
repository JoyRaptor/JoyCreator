package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * "This follows that" in the picture (SPEC_20260924_LINKING §3 / §12).
 *
 * <p>A follower keeps its OWN pose (its statics and keyframes, exactly as authored) and is drawn
 * at {@code parentNow ∘ parentAtLink⁻¹ ∘ own}: whatever the parent has done since the moment of
 * linking is applied on top. So linking moves nothing (the delta is the identity at that
 * moment), a follower's own animation keeps meaning what it meant, and unlinking can bake the
 * current delta in so the object "stays where it is at that moment" (owner's answer, §12).
 *
 * <p>Each property follows only if its switch is on. Opacity follows only when switched on
 * (owner: "only if opacity is set as a linked property").
 *
 * <p>Positions are frame fractions (x of width, y of height); rotating them needs the frame's
 * aspect, which is captured at link time — the canvas shape does not change under a link.
 */
public final class SpaceLink {

    @NonNull public final String parentId;
    public boolean position = true, scale = true, rotation = true, opacity = false;
    /** Parent pose at the moment of linking: x, y, size, rotation, opacity. */
    public final float baseX, baseY, baseSize, baseRot, baseOpacity;
    /** Frame width / height when linked. */
    public final float aspect;

    /** Resolved at load / after edits by {@link Timeline#resolveSpaceLinks()}; null = inert. */
    @Nullable public transient LinkPose parentRef;

    public SpaceLink(@NonNull String parentId, float baseX, float baseY, float baseSize,
                     float baseRot, float baseOpacity, float aspect) {
        this.parentId = parentId;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseSize = baseSize;
        this.baseRot = baseRot;
        this.baseOpacity = baseOpacity;
        this.aspect = aspect > 0.01f ? aspect : 1f;
    }

    /** Link {@code parent} as it stands at {@code t}. */
    @NonNull
    public static SpaceLink capture(@NonNull LinkPose parent, long t, float aspect) {
        return new SpaceLink(parent.getId(), parent.animatedCenterX(t), parent.animatedCenterY(t),
                parent.animatedSizeFraction(t), parent.animatedRotation(t),
                parent.animatedOpacity(t), aspect);
    }

    @NonNull
    public SpaceLink copy() {
        SpaceLink c = new SpaceLink(parentId, baseX, baseY, baseSize, baseRot, baseOpacity, aspect);
        c.position = position;
        c.scale = scale;
        c.rotation = rotation;
        c.opacity = opacity;
        c.parentRef = parentRef;
        return c;
    }

    /** Is this link live (its parent was found)? */
    public boolean active() { return parentRef != null; }

    private float k(long t) {
        if (!scale || parentRef == null || baseSize < 1e-5f) return 1f;
        return Math.max(1e-4f, parentRef.animatedSizeFraction(t) / baseSize);
    }

    private float dr(long t) {
        return rotation && parentRef != null ? parentRef.animatedRotation(t) - baseRot : 0f;
    }

    /** OWN (x, y) → WORLD (x, y) at {@code t}, into {@code out}. */
    public void toWorld(float ownX, float ownY, long t, @NonNull float[] out) {
        LinkPose p = parentRef;
        if (p == null) { out[0] = ownX; out[1] = ownY; return; }
        float k = k(t);
        double r = Math.toRadians(dr(t));
        float vx = (ownX - baseX) * aspect, vy = ownY - baseY;
        float rx = (float) (vx * Math.cos(r) - vy * Math.sin(r)) * k;
        float ry = (float) (vx * Math.sin(r) + vy * Math.cos(r)) * k;
        float px = position ? p.animatedCenterX(t) : baseX;
        float py = position ? p.animatedCenterY(t) : baseY;
        out[0] = px + rx / aspect;
        out[1] = py + ry;
    }

    /** WORLD (x, y) → OWN (x, y) at {@code t}: what a drag must write. */
    public void toOwn(float worldX, float worldY, long t, @NonNull float[] out) {
        LinkPose p = parentRef;
        if (p == null) { out[0] = worldX; out[1] = worldY; return; }
        float k = k(t);
        double r = Math.toRadians(-dr(t));
        float px = position ? p.animatedCenterX(t) : baseX;
        float py = position ? p.animatedCenterY(t) : baseY;
        float vx = (worldX - px) * aspect / k, vy = (worldY - py) / k;
        float rx = (float) (vx * Math.cos(r) - vy * Math.sin(r));
        float ry = (float) (vx * Math.sin(r) + vy * Math.cos(r));
        out[0] = baseX + rx / aspect;
        out[1] = baseY + ry;
    }

    public float sizeToWorld(float own, long t) { return own * k(t); }
    public float sizeToOwn(float world, long t) { return world / k(t); }
    public float rotToWorld(float own, long t) { return own + dr(t); }
    public float rotToOwn(float world, long t) { return world - dr(t); }

    public float opacityToWorld(float own, long t) {
        if (!opacity || parentRef == null || baseOpacity < 1e-4f) return own;
        return Math.max(0f, Math.min(1f, own * parentRef.animatedOpacity(t) / baseOpacity));
    }

    // ── storage ───────────────────────────────────────────────────────────────────────────────

    @NonNull
    public com.google.gson.JsonObject toJson() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("parent", parentId);
        o.addProperty("pos", position);
        o.addProperty("scale", scale);
        o.addProperty("rot", rotation);
        o.addProperty("op", opacity);
        com.google.gson.JsonArray b = new com.google.gson.JsonArray();
        b.add(baseX); b.add(baseY); b.add(baseSize); b.add(baseRot); b.add(baseOpacity);
        o.add("base", b);
        o.addProperty("aspect", aspect);
        return o;
    }

    /** Null on anything malformed: a bad link costs the link, never the object. */
    @Nullable
    public static SpaceLink fromJson(@Nullable com.google.gson.JsonObject o) {
        try {
            if (o == null || !o.has("parent") || !o.has("base")) return null;
            com.google.gson.JsonArray b = o.getAsJsonArray("base");
            if (b.size() < 5) return null;
            SpaceLink l = new SpaceLink(o.get("parent").getAsString(),
                    b.get(0).getAsFloat(), b.get(1).getAsFloat(), b.get(2).getAsFloat(),
                    b.get(3).getAsFloat(), b.get(4).getAsFloat(),
                    o.has("aspect") ? o.get("aspect").getAsFloat() : 1f);
            if (o.has("pos")) l.position = o.get("pos").getAsBoolean();
            if (o.has("scale")) l.scale = o.get("scale").getAsBoolean();
            if (o.has("rot")) l.rotation = o.get("rot").getAsBoolean();
            if (o.has("op")) l.opacity = o.get("op").getAsBoolean();
            return l;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
