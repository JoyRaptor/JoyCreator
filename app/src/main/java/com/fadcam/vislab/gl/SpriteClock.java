package com.fadcam.vislab.gl;

/**
 * Which frame a Sprite layer shows now, worked out from the moment and not from the path taken to
 * it, so a seek, a scrub backwards or a renderer rebuilt after a pause land on the frame playing
 * or an export shows there. Five ways to play (the layer's Play knob):
 * <ul>
 *   <li>its own speed: the sheet's frames a second, times Speed;</li>
 *   <li>a frame per beat, or per kick, so it dances in time: from the exact counts of the sound
 *       when the host has them ({@link #setCounts}), else counted as they land (the room);</li>
 *   <li>the loudness picks the frame: quiet shows the first, loud the last (a mouth that opens
 *       with the voice, a meter that fills);</li>
 *   <li>one frame held.</li>
 * </ul>
 * Looping, or back and forth. Beats and kicks are counted whatever the mode, so switching modes
 * mid-song carries on in step. A Speed knob with a music link has no one answer for a moment: it
 * travels, as {@code KnobSet} speeds do, from where its steady speed would be after a jump.
 * Pure Java: the JVM harness tests it.
 */
public final class SpriteClock {

    public static final int OWN_SPEED = 0, PER_BEAT = 1, PER_KICK = 2, LOUDNESS = 3, HOLD = 4;
    /** A bigger step than this between two frames is a jump, not playing. */
    private static final double JUMP_SEC = 0.25;

    private double travelled;
    private double lastTime = Double.NaN;
    private float steadySpeed = Float.NaN;
    private long exactBeats = -1, exactKicks = -1;
    private long beats, kicks;
    private float lastPhase = -1f;
    private float lastKickAge = Float.NaN;

    /** Back to the first frame (a seek, a new look). */
    public void reset() {
        travelled = 0;
        lastTime = Double.NaN;
        beats = kicks = 0;
        lastPhase = -1f;
        lastKickAge = Float.NaN;
    }

    /**
     * The Speed knob's value without its music link, or NaN when it has none. Set before
     * {@link #step}.
     */
    public void setSteadySpeed(float steady) {
        steadySpeed = steady;
    }

    /** Beats and kicks landed by now in the sound, or -1 where they are not known. */
    public void setCounts(long heardBeats, long heardKicks) {
        exactBeats = heardBeats;
        exactKicks = heardKicks;
    }

    /**
     * @param mode      one of the constants above
     * @param n         frames in the play order
     * @param bounce    back and forth instead of looping
     * @param fps       the sheet's (or clip's) frames a second
     * @param speed     Speed knob, for {@link #OWN_SPEED}
     * @param pick      0..1, the held frame for {@link #HOLD}
     * @param time      the look's clock, seconds
     * @param beatPhase 0..1 through the current beat (wraps on each beat)
     * @param kickAge   seconds since the latest kick (large when there has been none)
     * @param level     0..1 loudness, for {@link #LOUDNESS}
     * @return the position in the play order, 0..n-1
     */
    public int step(int mode, int n, boolean bounce, float fps, float speed, float pick,
                    double time, float beatPhase, float kickAge, float level) {
        if (lastPhase >= 0f && beatPhase < lastPhase - 0.5f) beats++;
        lastPhase = beatPhase;
        if (!Float.isNaN(lastKickAge) && kickAge < lastKickAge - 1e-4f) kicks++;
        lastKickAge = kickAge;
        travel(time, fps, speed);
        if (n <= 1) return 0;
        switch (mode) {
            case LOUDNESS: return Math.round(clamp01(level) * (n - 1));
            case HOLD: return Math.round(clamp01(pick) * (n - 1));
            case PER_BEAT: return wrap(exactBeats >= 0 ? exactBeats : beats, n, bounce);
            case PER_KICK: return wrap(exactKicks >= 0 ? exactKicks : kicks, n, bounce);
            default: return wrap((long) Math.floor(travelled), n, bounce);
        }
    }

    private void travel(double time, float fps, float speed) {
        double moved = time - lastTime;
        if (Float.isNaN(steadySpeed)) travelled = time * fps * speed;
        else if (moved >= 0 && moved <= JUMP_SEC) travelled += moved * fps * speed;   // false on NaN
        else travelled = time * fps * steadySpeed;
        lastTime = time;
    }

    /** Step {@code i} of a loop of {@code n}, or of a back-and-forth (0 1 2 3 2 1 0 1 ...). */
    static int wrap(long i, int n, boolean bounce) {
        if (n <= 1) return 0;
        if (!bounce) return (int) Math.floorMod(i, (long) n);
        long period = 2L * n - 2;
        int k = (int) Math.floorMod(i, period);
        return k < n ? k : (int) (period - k);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
