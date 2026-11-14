package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where to move each sprite cell so its drawing sits right (audit BOT-A31), the same arithmetic
 * as SpriteLab's "Auto-centre" and "Feet" buttons ({@code SpriteSheetEditorActivity.autoCentre}):
 * centre — the drawing's middle lands on the pivot; feet — every drawing's bottom edge lines up
 * with the set's average, and only the across-position is centred (centring a walk makes it skate,
 * because its middle rises and falls). Pure Java so it is tested off the phone; all values in the
 * same pixel units as the boxes.
 */
public final class InkAlign {
    private InkAlign() { }

    /** A cell's drawing: {left, top, right, bottom} inside the cell, and the cell's size. */
    public static final class Box {
        final float l, t, r, b, cellW, cellH;

        public Box(float l, float t, float r, float b, float cellW, float cellH) {
            this.l = l; this.t = t; this.r = r; this.b = b;
            this.cellW = Math.max(1f, cellW); this.cellH = Math.max(1f, cellH);
        }
    }

    /**
     * The move {dx, dy} for each cell with ink (empty cells are left out, as SpriteLab does).
     * Averaging the bottoms first means the set settles toward itself instead of each frame
     * chasing the pivot alone.
     */
    @NonNull
    public static Map<Integer, float[]> shifts(@NonNull Map<Integer, Box> boxes, float pivotX, float pivotY, boolean feet) {
        Map<Integer, float[]> out = new LinkedHashMap<>();
        if (boxes.isEmpty()) return out;
        float sumBottom = 0f;
        for (Box b : boxes.values()) sumBottom += b.b;
        float avgBottom = sumBottom / boxes.size();
        for (Map.Entry<Integer, Box> e : boxes.entrySet()) {
            Box b = e.getValue();
            float dx = pivotX * b.cellW - (b.l + b.r) * 0.5f;
            float dy = feet ? avgBottom - b.b : pivotY * b.cellH - (b.t + b.b) * 0.5f;
            out.put(e.getKey(), new float[]{dx, dy});
        }
        return out;
    }

    /** "centre", "center", "middle" → false; "feet", "bottom", "ground", "floor" → true; else null. */
    @Nullable
    public static Boolean feetMode(@Nullable String said) {
        if (said == null) return false;
        String s = said.trim().toLowerCase(java.util.Locale.US);
        if (s.isEmpty() || s.startsWith("cent") || s.equals("middle") || s.equals("pivot")) return false;
        if (s.equals("feet") || s.equals("bottom") || s.equals("ground") || s.equals("floor") || s.equals("baseline")) return true;
        return null;
    }
}
