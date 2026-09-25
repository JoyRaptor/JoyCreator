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
        for (Switch sw : switches) {
            Switch n = new Switch(sw.atMs, sw.parentId);
            n.ref = sw.ref;
            c.switches.add(n);
        }
        return c;
    }

    /** Is this link live (its parent was found, or it hands over later)? */
    public boolean active() { return parentRef != null || !switches.isEmpty(); }

    // ── keyed parent switches (SPEC_20260924_LINKING §12: "key the proxy's parent") ──────────

    /**
     * From {@code atMs} on, follow {@code parentId} instead ("" = let go: stay where it is at
     * that moment and follow nothing). Only the time and the parent are the user's; the rest is
     * SOLVED so the object does not jump at the handoff: the new parent's reference pose is
     * chosen so that, at {@code atMs}, "new parent now ∘ reference⁻¹" equals the old segment's
     * transform. Re-solved after every change ({@link #invalidate}), so it stays seamless when
     * switches are added, removed or reloaded.
     */
    public static final class Switch {
        public final long atMs;
        @NonNull public final String parentId;
        /** Solved: this segment's reference pose (x, y, size, rotation, opacity). */
        final float[] base = new float[5];
        /** Solved: the parent pose to hold when the parent is gone, or for a let-go. */
        final float[] frozen = new float[5];
        @Nullable public transient LinkPose ref;

        public Switch(long atMs, @NonNull String parentId) {
            this.atMs = Math.max(0, atMs);
            this.parentId = parentId;
        }

        public boolean letsGo() { return parentId.isEmpty(); }
    }

    /** Sorted by time; empty for an ordinary link. */
    @NonNull public final java.util.List<Switch> switches = new java.util.ArrayList<>();

    private transient volatile boolean solved;
    private transient boolean solving;

    /** The parent structure changed (a switch, a load, a parent re-resolved): solve again. */
    public void invalidate() { solved = false; }

    /** Add (or replace, at the same moment) a handoff. */
    public void putSwitch(long atMs, @NonNull String parentId) {
        removeSwitchAt(atMs);
        Switch sw = new Switch(atMs, parentId);
        int i = 0;
        while (i < switches.size() && switches.get(i).atMs < sw.atMs) i++;
        switches.add(i, sw);
        invalidate();
    }

    public void removeSwitchAt(long atMs) {
        for (int i = switches.size() - 1; i >= 0; i--) {
            if (switches.get(i).atMs == atMs) switches.remove(i);
        }
        invalidate();
    }

    /** Every object this link follows at some moment. */
    @NonNull
    public java.util.List<String> parentIds() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (!parentId.isEmpty()) out.add(parentId);
        for (Switch sw : switches) {
            if (!sw.letsGo() && !out.contains(sw.parentId)) out.add(sw.parentId);
        }
        return out;
    }

    /** The object followed at {@code t}, or null (let go, free, or gone). */
    @Nullable
    public LinkPose activeParent(long t) {
        Switch cur = switchAt(t);
        return cur == null ? parentRef : (cur.letsGo() ? null : cur.ref);
    }

    @Nullable
    private Switch switchAt(long t) {
        Switch cur = null;
        for (Switch sw : switches) {
            if (sw.atMs <= t) cur = sw; else break;
        }
        return cur;
    }

    private static void poseOf(@NonNull LinkPose p, long t, @NonNull float[] o) {
        o[0] = p.animatedCenterX(t);
        o[1] = p.animatedCenterY(t);
        o[2] = p.animatedSizeFraction(t);
        o[3] = p.animatedRotation(t);
        o[4] = p.animatedOpacity(t);
    }

    private void primaryBase(@NonNull float[] o, int at) {
        o[at] = baseX; o[at + 1] = baseY; o[at + 2] = baseSize; o[at + 3] = baseRot;
        o[at + 4] = baseOpacity;
    }

    /**
     * The segment in force at {@code t}: the parent pose it follows (0-4) and its reference pose
     * (5-9), with switched-off properties held at the reference, so "effective parent ∘
     * reference⁻¹" is the whole transform. False = inert (the parent is gone).
     */
    private boolean segAt(long t, @NonNull float[] o) {
        if (!solved) ensureSolved();
        Switch cur = switchAt(t);
        if (cur == null) {
            if (parentRef != null) poseOf(parentRef, t, o);
            else if (parentId.isEmpty()) primaryBase(o, 0);   // free until the first handoff
            else return false;
            primaryBase(o, 5);
        } else {
            if (cur.ref != null && !cur.letsGo()) poseOf(cur.ref, t, o);
            else System.arraycopy(cur.frozen, 0, o, 0, 5);
            System.arraycopy(cur.base, 0, o, 5, 5);
        }
        if (!position) { o[0] = o[5]; o[1] = o[6]; }
        if (!scale) o[2] = o[7];
        if (!rotation) o[3] = o[8];
        if (!opacity) o[4] = o[9];
        return true;
    }

    /** Solve every switch's reference pose, in time order, for a seamless handoff. */
    private synchronized void ensureSolved() {
        if (solved || solving) return;
        if (switches.isEmpty()) { solved = true; return; }
        solving = true;
        try {
            float[] prevP = new float[5], prevB = new float[5];
            primaryBase(prevB, 0);
            LinkPose prevRef = parentRef;
            float[] prevHold = prevB.clone();
            for (Switch sw : switches) {
                long ts = sw.atMs;
                if (prevRef != null) poseOf(prevRef, ts, prevP);
                else System.arraycopy(prevHold, 0, prevP, 0, 5);
                // The old segment's effective parent pose at the handoff.
                float ex = position ? prevP[0] : prevB[0], ey = position ? prevP[1] : prevB[1];
                float es = scale ? prevP[2] : prevB[2], er = rotation ? prevP[3] : prevB[3];
                float eo = opacity ? prevP[4] : prevB[4];
                float dr = er - prevB[3];
                float ds = prevB[2] > 1e-5f ? Math.max(1e-4f, es / prevB[2]) : 1f;
                float dO = prevB[4] > 1e-4f ? eo / prevB[4] : 1f;
                double rad = Math.toRadians(dr);
                float c = (float) Math.cos(rad), sn = (float) Math.sin(rad);
                // The old transform is v -> M·v + Dt with M = ds·R(dr), in aspect-corrected space.
                float bxa = prevB[0] * aspect, by = prevB[1];
                float dtx = ex * aspect - ds * (c * bxa - sn * by);
                float dty = ey - ds * (sn * bxa + c * by);
                if (sw.letsGo() || sw.ref == null) {
                    // Hold the old parent where it was: the transform freezes, nothing jumps.
                    sw.frozen[0] = ex; sw.frozen[1] = ey; sw.frozen[2] = es; sw.frozen[3] = er;
                    sw.frozen[4] = eo;
                    System.arraycopy(prevB, 0, sw.base, 0, 5);
                } else {
                    float[] b = new float[5];
                    poseOf(sw.ref, ts, b);
                    System.arraycopy(b, 0, sw.frozen, 0, 5);
                    sw.base[3] = rotation ? b[3] - dr : b[3];
                    sw.base[2] = scale ? b[2] / ds : b[2];
                    sw.base[4] = opacity && dO > 1e-4f ? b[4] / dO : b[4];
                    float nx, ny;
                    if (position) {
                        float vx = b[0] * aspect - dtx, vy = b[1] - dty;
                        nx = (c * vx + sn * vy) / ds;
                        ny = (-sn * vx + c * vy) / ds;
                    } else {
                        // Position held at the reference: solve (I - M)·reference = Dt.
                        float a = 1f - ds * c, q = ds * sn;
                        float det = a * a + q * q;
                        if (det < 1e-6f) { nx = b[0] * aspect; ny = b[1]; }
                        else { nx = (a * dtx - q * dty) / det; ny = (q * dtx + a * dty) / det; }
                    }
                    sw.base[0] = nx / aspect;
                    sw.base[1] = ny;
                }
                prevRef = sw.letsGo() ? null : sw.ref;
                System.arraycopy(sw.base, 0, prevB, 0, 5);
                System.arraycopy(sw.frozen, 0, prevHold, 0, 5);
            }
            solved = true;
        } finally {
            solving = false;
        }
    }

    // Each call takes its own scratch: the GPU preview reads poses off the UI thread.

    private float k(long t) {
        float[] seg = new float[10];
        if (!segAt(t, seg) || seg[7] < 1e-5f) return 1f;
        return Math.max(1e-4f, seg[2] / seg[7]);
    }

    private float dr(long t) {
        float[] seg = new float[10];
        return segAt(t, seg) ? seg[3] - seg[8] : 0f;
    }

    /** OWN (x, y) → WORLD (x, y) at {@code t}, into {@code out}. */
    public void toWorld(float ownX, float ownY, long t, @NonNull float[] out) {
        float[] seg = new float[10];
        if (!segAt(t, seg)) { out[0] = ownX; out[1] = ownY; return; }
        float k = seg[7] < 1e-5f ? 1f : Math.max(1e-4f, seg[2] / seg[7]);
        double r = Math.toRadians(seg[3] - seg[8]);
        float vx = (ownX - seg[5]) * aspect, vy = ownY - seg[6];
        float rx = (float) (vx * Math.cos(r) - vy * Math.sin(r)) * k;
        float ry = (float) (vx * Math.sin(r) + vy * Math.cos(r)) * k;
        out[0] = seg[0] + rx / aspect;
        out[1] = seg[1] + ry;
    }

    /** WORLD (x, y) → OWN (x, y) at {@code t}: what a drag must write. */
    public void toOwn(float worldX, float worldY, long t, @NonNull float[] out) {
        float[] seg = new float[10];
        if (!segAt(t, seg)) { out[0] = worldX; out[1] = worldY; return; }
        float k = seg[7] < 1e-5f ? 1f : Math.max(1e-4f, seg[2] / seg[7]);
        double r = Math.toRadians(-(seg[3] - seg[8]));
        float vx = (worldX - seg[0]) * aspect / k, vy = (worldY - seg[1]) / k;
        float rx = (float) (vx * Math.cos(r) - vy * Math.sin(r));
        float ry = (float) (vx * Math.sin(r) + vy * Math.cos(r));
        out[0] = seg[5] + rx / aspect;
        out[1] = seg[6] + ry;
    }

    public float sizeToWorld(float own, long t) { return own * k(t); }
    public float sizeToOwn(float world, long t) { return world / k(t); }
    public float rotToWorld(float own, long t) { return own + dr(t); }
    public float rotToOwn(float world, long t) { return world - dr(t); }

    public float opacityToWorld(float own, long t) {
        float[] seg = new float[10];
        if (!opacity || !segAt(t, seg) || seg[9] < 1e-4f) return own;
        return Math.max(0f, Math.min(1f, own * seg[4] / seg[9]));
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
        if (!switches.isEmpty()) {
            com.google.gson.JsonArray sa = new com.google.gson.JsonArray();
            for (Switch sw : switches) {
                com.google.gson.JsonObject so = new com.google.gson.JsonObject();
                so.addProperty("at", sw.atMs);
                so.addProperty("parent", sw.parentId);
                sa.add(so);
            }
            o.add("switch", sa);
        }
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
            if (o.has("switch")) {
                for (com.google.gson.JsonElement e : o.getAsJsonArray("switch")) {
                    com.google.gson.JsonObject so = e.getAsJsonObject();
                    l.putSwitch(so.get("at").getAsLong(),
                            so.has("parent") ? so.get("parent").getAsString() : "");
                }
            }
            return l;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
