import com.fadcam.vislab.gl.SpriteClock;

/**
 * Sprite layers (roadmap R7): which frame shows. Its own speed at the sheet's frames a second,
 * one frame per beat (the beat phase wrapping), one per kick (the kick's age dropping), the
 * loudness picking, one held; looping or back and forth.
 */
public class SpriteClockTest {

    static int fails, passes;
    static final double DT = 1.0 / 60.0;

    public static void main(String[] args) {
        SpriteClock c = new SpriteClock();
        // Own speed: 8 fps, 1 s = 8 frames on, a loop of 8 comes back to frame 0.
        int at = 0;
        for (int i = 0; i < 60; i++) at = c.step(SpriteClock.OWN_SPEED, 8, false, 8f, 1f, 0f, DT, 0f, 99f, 0f);
        check("own speed: after 1 s at 8 fps a loop of 8 is back at 0 (" + at + ")", at == 0);
        c.reset();
        for (int i = 0; i < 30; i++) at = c.step(SpriteClock.OWN_SPEED, 8, false, 8f, 1f, 0f, DT, 0f, 99f, 0f);
        check("own speed: half a second = frame 4 (" + at + ")", at == 4 || at == 3);
        c.reset();
        for (int i = 0; i < 30; i++) at = c.step(SpriteClock.OWN_SPEED, 8, false, 8f, 2f, 0f, DT, 0f, 99f, 0f);
        check("speed 2: half a second = back at 0 (" + at + ")", at == 0 || at == 7);

        // A frame per beat: 120 bpm, the phase wraps every 0.5 s.
        c.reset();
        double t = 0;
        for (int i = 0; i < 150; i++) {   // 2.5 s = 5 beats (wraps at 0.5, 1.0, ... 2.5)
            t += DT;
            float ph = (float) ((t / 0.5) % 1.0);
            at = c.step(SpriteClock.PER_BEAT, 8, false, 8f, 1f, 0f, DT, ph, 99f, 0f);
        }
        check("a frame per beat: 5 beats in 2.5 s = frame 4 or 5 (" + at + ")", at == 4 || at == 5);

        // A frame per kick: the kick's age drops to 0 on each kick.
        c.reset();
        float age = 99f;
        int kicks = 0;
        for (int i = 0; i < 120; i++) {
            if (i % 20 == 10) { age = 0f; kicks++; } else age += (float) DT;
            at = c.step(SpriteClock.PER_KICK, 4, false, 8f, 1f, 0f, DT, 0f, age, 0f);
        }
        check("a frame per kick: " + kicks + " kicks on a loop of 4 = frame " + (kicks % 4) + " (" + at + ")", at == kicks % 4);

        // Loudness picks; hold one frame.
        check("loudness 0 = first", c.step(SpriteClock.LOUDNESS, 5, false, 8f, 1f, 0f, DT, 0f, 99f, 0f) == 0);
        check("loudness 1 = last", c.step(SpriteClock.LOUDNESS, 5, false, 8f, 1f, 0f, DT, 0f, 99f, 1f) == 4);
        check("loudness 0.5 = middle", c.step(SpriteClock.LOUDNESS, 5, false, 8f, 1f, 0f, DT, 0f, 99f, 0.5f) == 2);
        check("hold 0.75 of 5 = frame 3", c.step(SpriteClock.HOLD, 5, false, 8f, 1f, 0.75f, DT, 0f, 99f, 0f) == 3);
        check("one frame: always 0", c.step(SpriteClock.OWN_SPEED, 1, true, 8f, 1f, 0f, DT, 0f, 99f, 0f) == 0);

        // Back and forth: 0 1 2 3 2 1 0 1 ...
        int[] want = {0, 1, 2, 3, 2, 1, 0, 1};
        c.reset();
        StringBuilder got = new StringBuilder();
        boolean ok = true;
        int f = 0;
        for (int i = 0; i < 8; i++) {
            // exactly one frame's time per step at 8 fps, landing just past each whole frame
            f = c.step(SpriteClock.OWN_SPEED, 4, true, 8f, 1f, 0f, i == 0 ? 0.0001 : 0.125, 0f, 99f, 0f);
            got.append(f).append(' ');
            if (f != want[i]) ok = false;
        }
        check("back and forth: " + got.toString().trim(), ok);

        System.out.println("SpriteClockTest: " + (fails == 0 ? "PASS" : "FAILURES: " + fails) + " (passed " + passes + ")");
        if (fails > 0) System.exit(1);
    }

    static void check(String what, boolean ok) {
        if (ok) passes++;
        else fails++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
