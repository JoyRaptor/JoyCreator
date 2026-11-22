package com.fadcam.ui.faditor.avatar.face;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * FACE-1: the person's own taste for how strongly the face carries over. Small, plain and saved
 * as hand-editable JSON by whoever hosts the camera (the phone room keeps it with the character or
 * in its settings).
 *
 * <ul>
 *   <li>{@link #mirror}: move like your reflection or like someone facing you (swaps left and right
 *       and turns the other way).</li>
 *   <li>Four strengths, 0..2, 1 = as tracked: head, eyes, brows (and lids), mouth.</li>
 *   <li>{@link #blinkSync}: both eyes blink together unless one clearly winks (trackers read the two
 *       eyes a little differently; a premium puppet should not look lopsided for it).</li>
 * </ul>
 */
public final class FaceTuning {

    public boolean mirror = false;
    public float head = 1f, eyes = 1f, brows = 1f, mouth = 1f;
    public boolean blinkSync = true;

    @NonNull
    public FaceTuning copy() {
        FaceTuning t = new FaceTuning();
        t.mirror = mirror;
        t.head = head;
        t.eyes = eyes;
        t.brows = brows;
        t.mouth = mouth;
        t.blinkSync = blinkSync;
        return t;
    }

    @NonNull
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("mirror", mirror);
        o.addProperty("head", head);
        o.addProperty("eyes", eyes);
        o.addProperty("brows", brows);
        o.addProperty("mouth", mouth);
        o.addProperty("blinkSync", blinkSync);
        return o;
    }

    /** Tolerant read: anything missing or odd keeps its default. */
    @NonNull
    public static FaceTuning fromJson(@Nullable JsonObject o) {
        FaceTuning t = new FaceTuning();
        if (o == null) return t;
        try {
            if (o.has("mirror")) t.mirror = o.get("mirror").getAsBoolean();
            if (o.has("head")) t.head = strength(o.get("head").getAsFloat());
            if (o.has("eyes")) t.eyes = strength(o.get("eyes").getAsFloat());
            if (o.has("brows")) t.brows = strength(o.get("brows").getAsFloat());
            if (o.has("mouth")) t.mouth = strength(o.get("mouth").getAsFloat());
            if (o.has("blinkSync")) t.blinkSync = o.get("blinkSync").getAsBoolean();
        } catch (RuntimeException ignored) {
            // a hand edit gone wrong keeps what it could read
        }
        return t;
    }

    static float strength(float v) {
        return Float.isNaN(v) ? 1f : Math.max(0f, Math.min(2f, v));
    }
}
