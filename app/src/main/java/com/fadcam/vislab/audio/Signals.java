package com.fadcam.vislab.audio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The music signals a knob can react to, as one flat array (same ids and maths as the pilot's
 * {@code VisEngine.signalsOf}). Two of them are not sound at all: slow and wave are gentle
 * clocks, so a look can breathe on a silent intro.
 */
public final class Signals {

    public static final String[] IDS = {
            "bass", "low", "mid", "high", "air", "level", "voice", "bright",
            "kick", "snare", "hat", "beat", "beatSine", "bar", "energy", "slow", "wave"
    };

    public final float[] v = new float[IDS.length];

    /** Index of a signal id, or -1 when unknown (a typo in a hand-edited look is ignored). */
    public static int indexOf(@Nullable String id) {
        if (id == null) return -1;
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    /** The engine clock (the shaders' TIME) for this frame, seconds. */
    public double time;

    public void set(@NonNull Features f, double time) {
        this.time = time;
        v[0] = f.bass; v[1] = f.low; v[2] = f.mid; v[3] = f.high; v[4] = f.air;
        v[5] = f.level; v[6] = f.voice; v[7] = f.bright;
        v[8] = f.kick; v[9] = f.snare; v[10] = f.hat; v[11] = f.beat;
        v[12] = (float) (0.5 + 0.5 * Math.sin(f.beatPhase * Math.PI * 2));
        v[13] = f.barPhase;
        v[14] = f.energy;
        v[15] = (float) (0.5 + 0.5 * Math.sin(time * 0.31));
        v[16] = (float) (0.5 + 0.5 * Math.sin(time * 1.57));
    }
}
