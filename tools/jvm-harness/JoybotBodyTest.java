package com.fadcam.ui.lobby;

import java.util.Random;

/**
 * Joybot's hover and body language (BOT-5, owner's picks of 11-15 evening), off device: two minutes of random changes
 * between every body language at 60 frames a second, in the chat and in a small button.
 *
 * <p>Checks he never jumps or spins from one frame to the next (a change of body language blends), never swells or
 * shrinks out of bounds, keeps to his room (the chat: up to 90 dp off to the side when a long think wanders; a 40 dp
 * button: a few dp), and that every value is a number.</p>
 *
 * <p>Usage: {@code bash tools/jvm-harness/run-joybot-body.sh}</p>
 */
public class JoybotBodyTest {

    private static int fails = 0;

    private static void check(boolean ok, String what) {
        if (!ok) { fails++; if (fails < 30) System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) {
        String[] kinds = {null, "nervous", "idea", "think", "talk", "type", "show-right", "show-left", "show-down", "cheer",
                "read", "notes", "work", "paint", "greet"};
        for (boolean roomy : new boolean[]{true, false}) {
            JoybotBody b = new JoybotBody();
            b.roomy = roomy;
            JoybotBody.Pose p = new JoybotBody.Pose();
            Random r = new Random(7);
            String kind = null;
            long next = 0L;
            float px = Float.NaN, py = 0f, pr = 0f;
            float maxStep = 0f, maxRot = 0f, minX = 0f, maxX = 0f, minS = 9f, maxS = 0f;
            int sparks[] = {0};
            for (long t = 1000L; t < 121_000L; t += 16L) {
                if (t >= next) {
                    kind = kinds[r.nextInt(kinds.length)];
                    next = t + ("think".equals(kind) ? 4000L + r.nextInt(12_000) : 1500L + r.nextInt(5000));
                }
                b.pose(t, kind, p, (x, y, n, g) -> sparks[0] += n);
                check(!Float.isNaN(p.x) && !Float.isNaN(p.y) && !Float.isNaN(p.rot) && !Float.isNaN(p.scale), "NaN at " + t);
                if (!Float.isNaN(px)) {
                    maxStep = Math.max(maxStep, (float) Math.hypot(p.x - px, p.y - py));
                    maxRot = Math.max(maxRot, Math.abs(p.rot - pr));
                }
                px = p.x; py = p.y; pr = p.rot;
                minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
                float s = p.scale * Math.max(p.sx, p.sy);
                minS = Math.min(minS, p.scale * Math.min(p.sx, p.sy)); maxS = Math.max(maxS, s);
            }
            String w = roomy ? "chat" : "button";
            System.out.printf("%s: biggest step %.2f dp/frame, turn %.2f deg/frame, x %.1f..%.1f dp, size %.2f..%.2f, %d sparks%n",
                    w, maxStep, maxRot, minX, maxX, minS, maxS, sparks[0]);
            check(maxStep < 6.5f, w + ": jumps " + maxStep + " dp in a frame");
            check(maxRot < 4f, w + ": turns " + maxRot + " degrees in a frame");
            // the celebration's landing squash on top of its depth reaches about 0.72 and 1.29: meant
            check(minS > .68f && maxS < 1.35f, w + ": size out of bounds " + minS + ".." + maxS);
            if (roomy) check(minX > -32f && maxX < 112f, w + ": strays to " + minX + ".." + maxX);
            else check(minX > -12f && maxX < 30f, w + ": strays to " + minX + ".." + maxX + " in a small button");
            check(sparks[0] > 0, w + ": no sparks from an idea or a celebration");
        }
        System.out.println(fails == 0 ? "body language: all pass" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
