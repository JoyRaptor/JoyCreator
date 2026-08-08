package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.fx.FxStack;
import com.fadcam.ui.faditor.keyframe.KeyframeCodec;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * An ADJUSTMENT LAYER — a layer spanning the canvas that TRANSFORMS everything beneath it in
 * z-order, rather than compositing something over it.
 *
 * <p>Put a blur on it and everything below blurs. Put a gradient map on it and everything below
 * is remapped. Objects animating underneath ripple through it live. That is the After Effects
 * behaviour JoyRaptor asked for on 2026-08-06, and it is the reason this type exists at all.</p>
 *
 * <p><b>Why this was the cheap half.</b> {@code ExportManager.assembleClipVideoEffects} already
 * builds ONE ordered list in which chain position literally IS z-order — at position k the
 * accumulated frame already holds the master video plus every PiP below k. So an adjustment
 * layer is just an entry in that same loop that transforms the frame instead of drawing over it.
 * No compositor work; the semantic falls out.</p>
 *
 * <p>It carries {@link CompositingSpec} wholesale, so masks and chroma key work on it exactly as
 * they do on a PiP — but they restrict WHERE the effect applies rather than what is drawn.</p>
 *
 * <p><b>Self-serializing</b> (the {@code CompositingSpec} idiom), and android-free so the JVM
 * harness can reach it.</p>
 */
public final class AdjustmentLayer {

    /** Stable identity. Never null, never reused. */
    @NonNull private String id = UUID.randomUUID().toString();

    /**
     * Which lane this layer lives in. NEVER NULL — the same rule overlayClips follow, because a
     * null layerId is what makes an item fall back into the master spine and change paint order.
     */
    @NonNull private String layerId = "";

    /** Editor timeline span, absolute ms. */
    private long startMs = 0L;
    private long durationMs = 0L;

    /** Shown on the lane chip. Auto-named on creation; renameable from the object menu. */
    @NonNull private String name = "Adjustment";

    /** Masks + chroma key + matte, reused wholesale. Restricts WHERE the effect applies. */
    @NonNull private CompositingSpec compositing = new CompositingSpec();

    /** The effect stack this layer runs over everything beneath it. */
    @NonNull private FxStack fx = new FxStack();

    /**
     * How the graded result combines with the original, past plain opacity mix — the same
     * {@code BlendModes.ALL} vocabulary a PiP's overlay blend uses ({@code Clip#overlayBlendMode}).
     * "NORMAL" (the default) is what every layer had before this field existed, so it is never
     * serialized — see {@link #toJson}.
     */
    @NonNull private String blendMode = "NORMAL";

    /** Layer-level animation. v1 uses {@code opacity} only — how much of the effect lands. */
    @Nullable private KeyframeSet transform;

    private boolean hidden = false;
    private boolean locked = false;

    public AdjustmentLayer() {}

    // ── Accessors ───────────────────────────────────────────────────────────

    @NonNull public String getId() { return id; }
    public void setId(@NonNull String v) { id = v; }

    @NonNull public String getLayerId() { return layerId; }
    public void setLayerId(@NonNull String v) { layerId = v; }

    public long getStartMs() { return startMs; }
    public void setStartMs(long v) { startMs = Math.max(0L, v); }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long v) { durationMs = Math.max(0L, v); }

    /** Exclusive end, in the same editor-time base as {@link #getStartMs}. */
    public long getEndMs() { return startMs + durationMs; }

    /**
     * True when this layer is live at {@code editorMs} — what the renderers gate on.
     *
     * <p>A duration of zero means OPEN-ENDED (runs to the end of the timeline), matching what
     * {@code TimedItem.getDisplayDurationMs} already draws for it. They disagreed: the lane drew
     * a full-width bar while every renderer treated the layer as never active, so a layer could
     * look like it covered the whole project and grade nothing. The timeline is the thing the
     * user reads, so the renderers were the ones that were wrong.</p>
     */
    public boolean activeAt(long editorMs) {
        if (hidden || editorMs < startMs) return false;
        return durationMs <= 0L || editorMs < getEndMs();
    }

    @NonNull public String getName() { return name; }
    public void setName(@NonNull String v) { name = v; }

    @NonNull public CompositingSpec getCompositing() { return compositing; }
    public void setCompositing(@NonNull CompositingSpec v) { compositing = v; }

    @NonNull public FxStack getFx() { return fx; }
    public void setFx(@NonNull FxStack v) { fx = v; }

    @NonNull public String getBlendMode() { return blendMode; }
    public void setBlendMode(@Nullable String v) { blendMode = v == null ? "NORMAL" : v; }

    @Nullable public KeyframeSet getTransform() { return transform; }
    public void setTransform(@Nullable KeyframeSet v) { transform = v; }

    public boolean isHidden() { return hidden; }
    public void setHidden(boolean v) { hidden = v; }

    public boolean isLocked() { return locked; }
    public void setLocked(boolean v) { locked = v; }

    /** How much of the effect lands at {@code editorMs}. 1 when nothing is keyed. */
    public float opacityAt(long editorMs) {
        if (transform == null) return 1f;
        return Math.max(0f, Math.min(1f,
                transform.valueAt(KeyframeSet.OPACITY, editorMs, 1f)));
    }

    /**
     * True when this layer would change any pixel. A layer with an empty stack is a real object
     * the user can see and move, but it must contribute NO pass — otherwise every adjustment
     * layer would cost a full-screen copy for nothing.
     */
    public boolean rendersAnything() {
        return !hidden && !fx.active().isEmpty();
    }

    // ── Serialization ───────────────────────────────────────────────────────

    @NonNull
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("layerId", layerId);
        o.addProperty("startMs", startMs);
        o.addProperty("durationMs", durationMs);
        o.addProperty("name", name);
        // Flags omitted at their defaults — the additive rule the rest of the model follows.
        if (hidden) o.addProperty("hidden", true);
        if (locked) o.addProperty("locked", true);
        if (!compositing.isEmpty()) o.add("compositing", compositing.toJson());
        if (!fx.isEmpty()) o.add("fx", fx.toJson());
        if (!"NORMAL".equals(blendMode)) o.addProperty("blendMode", blendMode);
        if (transform != null && !transform.isEmpty()) {
            o.add("transform", KeyframeCodec.toJson(transform));
        }
        return o;
    }

    @Nullable
    public static AdjustmentLayer fromJson(@Nullable JsonObject o) {
        if (o == null || !o.has("id")) return null;
        AdjustmentLayer a = new AdjustmentLayer();
        a.id = o.get("id").getAsString();
        a.layerId = o.has("layerId") ? o.get("layerId").getAsString() : "";
        a.startMs = o.has("startMs") ? Math.max(0L, o.get("startMs").getAsLong()) : 0L;
        a.durationMs = o.has("durationMs") ? Math.max(0L, o.get("durationMs").getAsLong()) : 0L;
        if (o.has("name")) a.name = o.get("name").getAsString();
        a.hidden = o.has("hidden") && o.get("hidden").getAsBoolean();
        a.locked = o.has("locked") && o.get("locked").getAsBoolean();
        if (o.has("compositing") && o.get("compositing").isJsonObject()) {
            a.compositing = CompositingSpec.fromJson(o.getAsJsonObject("compositing"));
        }
        if (o.has("fx") && o.get("fx").isJsonObject()) {
            a.fx = FxStack.fromJson(o.getAsJsonObject("fx"));
        }
        if (o.has("blendMode")) a.blendMode = o.get("blendMode").getAsString();
        if (o.has("transform") && o.get("transform").isJsonObject()) {
            a.transform = KeyframeCodec.fromJson(o.getAsJsonObject("transform"));
        }
        return a;
    }

    @NonNull
    public AdjustmentLayer copy() {
        AdjustmentLayer c = new AdjustmentLayer();
        c.id = id;
        c.layerId = layerId;
        c.startMs = startMs;
        c.durationMs = durationMs;
        c.name = name;
        c.hidden = hidden;
        c.locked = locked;
        c.compositing = compositing.copy();
        c.fx = fx.copy();
        c.blendMode = blendMode;
        c.transform = transform == null ? null : transform.copy();
        return c;
    }

    @NonNull
    @Override
    public String toString() { return "AdjustmentLayer(" + name + ", " + fx.size() + " fx)"; }
}
