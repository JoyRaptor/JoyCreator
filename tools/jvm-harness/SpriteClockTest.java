import com.fadcam.vislab.gl.SpriteClock;

/**
 * Sprite layers (roadmap R7): which frame shows. Its own speed at the sheet's frames a second,
 * one frame per beat (the beat phase wrapping), one per kick (the kick's age dropping), the
 * loudness picking, one held; looping or back and forth. And the frame belongs to the moment
 * (review V4): a seek, a scrub backwards or a rebuilt renderer lands where playing lands.
 */
public class SpriteClockTest {

    static int fails, passes;
    static final double DT = 1.0 / 60.0;

    public static void main(String[] args) {
        SpriteClock c = new SpriteClock();
        // Own speed: 8 fps, 1 s = 8 frames on, a loop of 8 comes back to frame 0.
        int at = 0;
        for (int i = 1; i <= 60; i++) at = own(c, 8, false, 8f, 1f, i * DT);
        check("own speed: after 1 s at 8 fps a loop of 8 is back at 0 (" + at + ")", at == 0 || at == 7);
        at = own(new SpriteClock(), 8, false, 8f, 1f, 0.5);
        check("own speed: half a second = frame 4 (" + at + ")", at == 4);
        at = own(new SpriteClock(), 8, false, 8f, 2f, 0.5);
        check("speed 2: half a second = back at 0 (" + at + ")", at == 0);

        // A frame per beat: 120 bpm, the phase wraps every 0.5 s.
        c.reset();
        double t = 0;
        for (int i = 0; i < 150; i++) {   // 2.5 s = 5 beats (wraps at 0.5, 1.0, ... 2.5)
            t += DT;
            float ph = (float) ((t / 0.5) % 1.0);
            at = c.step(SpriteClock.PER_BEAT, 8, false, 8f, 1f, 0f, t, ph, 99f, 0f);
        }
        check("a frame per beat: 5 beats in 2.5 s = frame 4 or 5 (" + at + ")", at == 4 || at == 5);

        // A frame per kick: the kick's age drops to 0 on each kick.
        c.reset();
        float age = 99f;
        int kicks = 0;
        t = 0;
        for (int i = 0; i < 120; i++) {
            if (i % 20 == 10) { age = 0f; kicks++; } else age += (float) DT;
            t += DT;
            at = c.step(SpriteClock.PER_KICK, 4, false, 8f, 1f, 0f, t, 0f, age, 0f);
        }
        check("a frame per kick: " + kicks + " kicks on a loop of 4 = frame " + (kicks % 4) + " (" + at + ")", at == kicks % 4);

        // Loudness picks; hold one frame.
        check("loudness 0 = first", c.step(SpriteClock.LOUDNESS, 5, false, 8f, 1f, 0f, t, 0f, 99f, 0f) == 0);
        check("loudness 1 = last", c.step(SpriteClock.LOUDNESS, 5, false, 8f, 1f, 0f, t, 0f, 99f, 1f) == 4);
        check("loudness 0.5 = middle", c.step(SpriteClock.LOUDNESS, 5, false, 8f, 1f, 0f, t, 0f, 99f, 0.5f) == 2);
        check("hold 0.75 of 5 = frame 3", c.step(SpriteClock.HOLD, 5, false, 8f, 1f, 0.75f, t, 0f, 99f, 0f) == 3);
        check("one frame: always 0", c.step(SpriteClock.OWN_SPEED, 1, true, 8f, 1f, 0f, t, 0f, 99f, 0f) == 0);

        // Back and forth: 0 1 2 3 2 1 0 1 ...
        int[] want = {0, 1, 2, 3, 2, 1, 0, 1};
        c.reset();
        StringBuilder got = new StringBuilder();
        boolean ok = true;
        int f = 0;
        for (int i = 0; i < 8; i++) {
            // landing just past each whole frame at 8 fps
            f = own(c, 4, true, 8f, 1f, 0.0001 + i * 0.125);
            got.append(f).append(' ');
            if (f != want[i]) ok = false;
        }
        check("back and forth: " + got.toString().trim(), ok);

        seeks();
        exactCounts();
        musicLinkedSpeed();

        System.out.println("SpriteClockTest: " + (fails == 0 ? "PASS" : "FAILURES: " + fails) + " (passed " + passes + ")");
        if (fails > 0) System.exit(1);
    }

    static int own(SpriteClock c, int n, boolean bounce, float fps, float speed, double time) {
        return c.step(SpriteClock.OWN_SPEED, n, bounce, fps, speed, 0f, time, 0f, 99f, 0f);
    }

    /** The review's probe: 8 frames at 12 fps, playing vs Studio's seek sequence to 5 s. */
    static void seeks() {
        SpriteClock playing = new SpriteClock();
        int atFive = 0;
        for (int i = 1; i <= 150; i++) atFive = own(playing, 8, false, 12f, 1f, i / 30.0);
        SpriteClock scrubbed = new SpriteClock();
        own(scrubbed, 8, false, 12f, 1f, 0);
        int seeked = own(scrubbed, 8, false, 12f, 1f, 5.0);
        check("V4 own speed: playing to 5 s (" + atFive + ") = seeking to 5 s (" + seeked + ")", atFive == seeked);
        check("V4 the probe's frame: 60 frames at 12 fps = frame 4 (" + atFive + ")", atFive == 4);

        // Every frame of an export against a clock pushed around at random, a reverse scrub, and
        // a renderer rebuilt after idling.
        java.util.Random r = new java.util.Random(11);
        boolean same = true;
        SpriteClock seeking = new SpriteClock();
        for (int i = 0; i < 2000; i++) {
            double when = r.nextInt(600_000) / 1000.0;
            if (own(seeking, 7, true, 11f, 1.5f, when) != own(new SpriteClock(), 7, true, 11f, 1.5f, when)) same = false;
        }
        check("V4 random seeks: a clock with a past = a clock without one", same);
        SpriteClock playA = new SpriteClock(), backB = new SpriteClock();
        int[] forward = new int[900];
        for (int i = 0; i < 900; i++) forward[i] = own(playA, 8, false, 12f, 1f, i / 30.0);
        same = true;
        for (int i = 899; i >= 0; i--) if (own(backB, 8, false, 12f, 1f, i / 30.0) != forward[i]) same = false;
        check("V4 reverse scrubbing shows every frame playing showed", same);
    }

    /** Beats and kicks from the sound's exact counts, so seeking is no different. */
    static void exactCounts() {
        SpriteClock a = new SpriteClock(), b = new SpriteClock();
        // Played: the counts climb. Seeked: straight to the last count, with no wrap ever seen.
        int played = 0;
        for (int beat = 0; beat <= 17; beat++) {
            a.setCounts(beat, -1);
            played = a.step(SpriteClock.PER_BEAT, 8, false, 8f, 1f, 0f, beat * 0.5, 0.1f, 99f, 0f);
        }
        b.setCounts(17, -1);
        int seeked = b.step(SpriteClock.PER_BEAT, 8, false, 8f, 1f, 0f, 8.5, 0.7f, 99f, 0f);
        check("V4 per beat: played (" + played + ") = seeked (" + seeked + ") = 17 mod 8", played == seeked && seeked == 1);
        b.setCounts(-1, 6);
        check("V4 per kick reads its own count", b.step(SpriteClock.PER_KICK, 4, false, 8f, 1f, 0f, 9, 0f, 99f, 0f) == 2);
        b.setCounts(9, -1);
        check("V4 back and forth reads the count too (9 of 0123210... = 3)",
                b.step(SpriteClock.PER_BEAT, 4, true, 8f, 1f, 0f, 9, 0f, 99f, 0f) == 3);
        // The room has no counts: it still counts as they land.
        SpriteClock room = new SpriteClock();
        room.setCounts(-1, -1);
        room.step(SpriteClock.PER_BEAT, 8, false, 8f, 1f, 0f, 0, 0.9f, 99f, 0f);
        check("V4 no exact counts: counted as they land",
                room.step(SpriteClock.PER_BEAT, 8, false, 8f, 1f, 0f, 0.02, 0.05f, 99f, 0f) == 1);
    }

    /** A Speed knob with a music link travels; it never jumps by the whole elapsed time. */
    static void musicLinkedSpeed() {
        SpriteClock c = new SpriteClock();
        c.setSteadySpeed(1f);
        own(c, 1000, false, 10f, 1f, 100.0);              // lands where a steady speed would be: 1000
        int before = c.step(SpriteClock.OWN_SPEED, 1000, false, 10f, 1f, 0f, 100.0, 0f, 99f, 0f);
        int after = own(c, 1000, false, 10f, 3f, 100.11); // the bass triples it for 0.11 s: 3.3 frames on
        check("V4 linked speed: a loud moment moves it 3 frames, not 200 (" + before + " -> " + after + ")",
                after - before == 3);
    }

    static void check(String what, boolean ok) {
        if (ok) passes++;
        else fails++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
