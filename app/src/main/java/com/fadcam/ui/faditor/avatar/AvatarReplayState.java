package com.fadcam.ui.faditor.avatar;

import java.util.HashMap;
import java.util.Map;

/** Per-part cell history for the avatar's pin-snap crossfade. */
public final class AvatarReplayState {
    private static final long CROSSFADE_US = 130_000;

    public record Fade(long startUs, int oldCell) {
        public float alphaAt(long timeUs) {
            long age = timeUs - startUs;
            if (age < 0 || age >= CROSSFADE_US) return 0f;
            return 1f - age / (float) CROSSFADE_US;
        }
    }

    private final Map<String, Integer> lastCells = new HashMap<>();
    private final Map<String, Fade> fades = new HashMap<>();

    public void observe(String partId, int cell, boolean swapped, long timeUs) {
        Integer previous = lastCells.put(partId, cell);
        if (swapped && previous != null && previous != cell) {
            fades.put(partId, new Fade(timeUs, previous));
        }
    }

    public Fade fade(String partId) {
        return fades.get(partId);
    }

    public void clear() {
        lastCells.clear();
        fades.clear();
    }

    /** The fork can advance through an exposure without changing the sharp replay. */
    public AvatarReplayState copy() {
        AvatarReplayState copy = new AvatarReplayState();
        copy.lastCells.putAll(lastCells);
        copy.fades.putAll(fades);
        return copy;
    }
}
