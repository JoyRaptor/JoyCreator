package com.fadcam.vislab.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One knob of a VisLab primitive, as declared in {@code assets/vislab/types.json}. The same
 * registry builds the web pilot's editor and the app's, so a knob is only ever described once.
 *
 * <p>{@link #slot} is where the shader reads it: {@code uP[slot / 4]} component {@code slot % 4}.
 * A knob with no slot ({@code -1}) is read by the host (particle count, flow rate, bloom size).</p>
 */
public final class VisKnob {

    @NonNull public final String id;
    @NonNull public final String label;
    /** Flat index into the 48-float parameter block, or -1 when the host reads it. */
    public final int slot;
    public final float min;
    public final float max;
    public final float def;
    /** 0 = continuous. */
    public final float step;
    /** Slider moves in log space (counts that span 256..131072). */
    public final boolean log;
    public final boolean toggle;
    /** Choice names when this knob picks one of several looks; null for a number. */
    @Nullable public final String[] options;
    /** Optional heading the editor groups knobs under ("Light", "Lens"...). */
    @Nullable public final String group;
    /**
     * Shown only while another knob of the same layer (this id) has one of {@link #showValues}:
     * a scene's own controls appear with the scene. Null = always shown.
     */
    @Nullable public final String showKnob;
    @Nullable public final int[] showValues;
    /**
     * A speed the shader multiplies by TIME ("rate": true in types.json). With a music link the
     * host keeps the distance travelled instead (see {@link KnobSet}): TIME x a changing speed
     * jumps the picture by the whole elapsed time on every beat.
     */
    public boolean rate;

    public VisKnob(@NonNull String id, @NonNull String label, int slot, float min, float max,
                   float def, float step, boolean log, boolean toggle,
                   @Nullable String[] options, @Nullable String group) {
        this(id, label, slot, min, max, def, step, log, toggle, options, group, null, null);
    }

    public VisKnob(@NonNull String id, @NonNull String label, int slot, float min, float max,
                   float def, float step, boolean log, boolean toggle,
                   @Nullable String[] options, @Nullable String group,
                   @Nullable String showKnob, @Nullable int[] showValues) {
        this.showKnob = showKnob;
        this.showValues = showValues;
        this.id = id;
        this.label = label;
        this.slot = slot;
        this.min = min;
        this.max = max;
        this.def = def;
        this.step = step;
        this.log = log;
        this.toggle = toggle;
        this.options = options;
        this.group = group;
    }

    /** Whether the editor shows this knob when its {@link #showKnob} is at {@code value}. */
    public boolean shownAt(int value) {
        if (showValues == null) return true;
        for (int v : showValues) if (v == value) return true;
        return false;
    }

    /** Range used to scale music modulation: amount 1 = the whole range. */
    public float range() {
        return max - min;
    }

    public boolean isChoice() {
        return options != null || toggle;
    }

    /** "10x" -> 40, "3w" -> 15. Returns -1 for null or malformed text. */
    public static int parseSlot(@Nullable String s) {
        if (s == null || s.length() < 2) return -1;
        char axis = s.charAt(s.length() - 1);
        int a;
        switch (axis) {
            case 'x': a = 0; break;
            case 'y': a = 1; break;
            case 'z': a = 2; break;
            case 'w': a = 3; break;
            default: return -1;
        }
        try {
            int vec = Integer.parseInt(s.substring(0, s.length() - 1));
            if (vec < 0 || vec >= 12) return -1;
            return vec * 4 + a;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
