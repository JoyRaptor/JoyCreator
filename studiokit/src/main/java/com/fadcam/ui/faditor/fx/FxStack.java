package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.KeyframeCodec;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * An ORDERED list of {@link FxInstance} cards plus ONE {@link KeyframeSet} for all of them.
 *
 * <p><b>Order is meaning.</b> Bottom card first: generate a texture, blur THAT, gradient-map the
 * result. It is the same reading the export chain already has, where chain position literally is
 * z-order — so the stack does not introduce a second convention.</p>
 *
 * <p><b>One KeyframeSet, not one per card.</b> Track names are already namespaced by slot, so a
 * single set holds everything without collision, {@code KeyframeCodec} round-trips it for free,
 * and a frame costs ONE {@code isAnimated()} check rather than one per card.</p>
 *
 * <p><b>{@link #resolveAt} returns {@code this} by identity when nothing animates.</b> The
 * {@code MaskAnimator.resolve} discipline: a stack nobody keyed must not allocate per frame, and
 * a copy would also drop the aliasing the renderers rely on.</p>
 *
 * <p>Android-free so the JVM harness can reach it.</p>
 */
public final class FxStack {

    @NonNull private final List<FxInstance> cards = new ArrayList<>();

    /** Animation for every card, keyed by {@code "fx<slot>.<param>"}. Null until something is keyed. */
    @Nullable public KeyframeSet keys;

    /**
     * Next slot to hand out. Monotonic and PERSISTED, so a card added after a delete cannot take
     * a number an existing keyframe track still refers to.
     */
    private int nextSlot = 0;

    // ── The list ────────────────────────────────────────────────────────────

    @NonNull
    public List<FxInstance> cards() { return cards; }

    public int size() { return cards.size(); }

    public boolean isEmpty() { return cards.isEmpty(); }

    /** Cards that will actually render, bottom-up. A disabled card contributes no pass at all. */
    @NonNull
    public List<FxInstance> active() {
        List<FxInstance> out = new ArrayList<>(cards.size());
        for (FxInstance c : cards) {
            if (c.enabled && c.def() != null) out.add(c);
        }
        return out;
    }

    /** Append a card running {@code effectId}, with a fresh slot. */
    @NonNull
    public FxInstance add(@NonNull String effectId) {
        FxInstance fx = new FxInstance(effectId, nextSlot++);
        cards.add(fx);
        return fx;
    }

    /**
     * Remove the card at {@code index} and DELETE its keyframe tracks — the slot is retired with
     * it and never handed out again, so nothing can inherit those keys later.
     */
    @Nullable
    public FxInstance remove(int index) {
        if (index < 0 || index >= cards.size()) return null;
        FxInstance gone = cards.remove(index);
        FxEffectDef def = gone.def();
        if (keys != null && def != null) {
            for (FxParam p : def.params) {
                for (int c = 0; c < p.kind.components; c++) keys.removeProperty(gone.track(p, c));
            }
        }
        return gone;
    }

    /**
     * Move a card. Deliberately touches NOTHING but list order: slots are stable, so the
     * keyframes follow the card without a single track being renamed. That is the entire reason
     * slots exist.
     */
    public void move(int from, int to) {
        if (from < 0 || from >= cards.size() || to < 0 || to >= cards.size() || from == to) return;
        cards.add(to, cards.remove(from));
    }

    /** Duplicate a card, values and all, onto a NEW slot directly above the original. */
    @NonNull
    public FxInstance duplicate(int index) {
        FxInstance src = cards.get(index);
        FxInstance c = new FxInstance(src.effectId, nextSlot++);
        FxEffectDef def = src.def();
        if (def != null) for (FxParam p : def.params) c.set(p, src.get(p));
        c.enabled = src.enabled;
        c.opacity = src.opacity;
        c.blendMode = src.blendMode;
        // Keyframes are NOT copied: they are named off the source's slot, and a copy that
        // shared them would move whenever the original was keyed.
        cards.add(index + 1, c);
        return c;
    }

    // ── Animation ───────────────────────────────────────────────────────────

    public boolean isAnimated() { return keys != null && keys.isAnimated(); }

    /**
     * The stack to render at {@code timelineMs} — ABSOLUTE timeline ms, the base
     * {@code overlayTransform} and {@code maskKeys} already use, so a drawer's {@code playheadMs()}
     * needs no conversion.
     *
     * @return {@code this} BY IDENTITY when nothing is keyed; otherwise a resolved copy. Callers
     *         may and do compare with {@code ==} to skip work.
     */
    @NonNull
    public FxStack resolveAt(long timelineMs) {
        if (!isAnimated()) return this;
        FxStack out = new FxStack();
        out.nextSlot = nextSlot;
        for (FxInstance c : cards) {
            FxInstance rc = c.copy();
            FxEffectDef def = c.def();
            if (def != null) {
                for (FxParam p : def.params) {
                    if (!p.keyable) continue;
                    float[] cur = c.get(p);
                    boolean touched = false;
                    float[] v = new float[p.kind.components];
                    for (int i = 0; i < v.length; i++) {
                        String track = c.track(p, i);
                        v[i] = keys.valueAt(track, timelineMs, cur[i]);
                        if (v[i] != cur[i]) touched = true;
                    }
                    if (touched) rc.set(p, v);
                }
            }
            out.cards.add(rc);
        }
        // The resolved copy is a VALUE, not an authoring surface: it carries no keys, so
        // resolving it again is a no-op rather than a second interpolation.
        return out;
    }

    // ── Serialization ───────────────────────────────────────────────────────

    @NonNull
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (FxInstance c : cards) arr.add(c.toJson());
        o.add("cards", arr);
        o.addProperty("nextSlot", nextSlot);
        // NOT isAnimated(): a track holding a SINGLE key is a static value, not an animation, so
        // isAnimated() is false for it — and gating the write on that silently dropped the key
        // on save. Persist whenever anything is keyed; resolve only when something moves.
        if (keys != null && !keys.isEmpty()) o.add("keys", KeyframeCodec.toJson(keys));
        return o;
    }

    @NonNull
    public static FxStack fromJson(@Nullable JsonObject o) {
        FxStack s = new FxStack();
        if (o == null) return s;
        if (o.has("cards") && o.get("cards").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("cards");
            for (int i = 0; i < arr.size(); i++) {
                if (!arr.get(i).isJsonObject()) continue;
                FxInstance c = FxInstance.fromJson(arr.get(i).getAsJsonObject());
                if (c != null) s.cards.add(c);
            }
        }
        if (o.has("nextSlot")) s.nextSlot = o.get("nextSlot").getAsInt();
        // A file written by a build with MORE effects than this one loses those cards, and
        // nextSlot must still clear every slot that survived or a new card would collide with
        // a retained keyframe track.
        for (FxInstance c : s.cards) s.nextSlot = Math.max(s.nextSlot, c.slot + 1);
        if (o.has("keys") && o.get("keys").isJsonObject()) {
            s.keys = KeyframeCodec.fromJson(o.getAsJsonObject("keys"));
        }
        return s;
    }

    @NonNull
    public FxStack copy() {
        FxStack s = new FxStack();
        for (FxInstance c : cards) s.cards.add(c.copy());
        s.nextSlot = nextSlot;
        if (keys != null) s.keys = keys.copy();
        return s;
    }

    /** Become {@code other} IN PLACE — undo needs it, for the {@code CompositingSpec} reason. */
    public void copyFrom(@NonNull FxStack other) {
        cards.clear();
        for (FxInstance c : other.cards) cards.add(c.copy());
        nextSlot = other.nextSlot;
        keys = other.keys == null ? null : other.keys.copy();
    }
}
