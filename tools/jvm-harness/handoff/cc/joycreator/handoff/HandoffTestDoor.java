package cc.joycreator.handoff;

/**
 * The checks behind {@code HandoffMovesTest}. It lives in the show's package (test tree only) so it
 * can read the show's own classes without making them public in the app.
 */
public final class HandoffTestDoor {

    private static int fails, checks;
    private static final String[] NAMES = {"pull", "peel", "kick", "vacuum"};

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { fails++; System.out.println("  FAIL " + what); }
    }

    private static boolean finite(float v) { return !Float.isNaN(v) && !Float.isInfinite(v); }

    public static int runAll() {
        float[][] screens = {{360, 740}, {548, 1127}, {1127, 548}, {1232, 800}, {344, 882}};
        float[] readyAt = {0f, .4f, 1.3f, 3.5f};
        for (float[] scr : screens) {
            for (int m = 0; m < HandoffMoves.MODES; m++) {
                for (int ask = 0; ask < 2; ask++) {
                    for (float ra : readyAt) run(scr[0], scr[1], m, ask == 1, ra, false);
                    run(scr[0], scr[1], m, ask == 1, 1.0f, true);
                }
            }
        }
        neverReady();
        System.out.println(checks + " checks, " + fails + " failed");
        return fails;
    }

    private static HandoffMoves.Stage stage(float w, float h) {
        HandoffMoves.Stage s = new HandoffMoves.Stage();
        s.w = w; s.h = h; s.cell = 85f;
        s.btnX = w - 24f; s.btnY = 40f;
        s.homeX = w - 60f; s.homeY = 36f;
        return s;
    }

    private static void run(float w, float h, int m, boolean ask, float readyAfter, boolean skip) {
        String tag = NAMES[m] + " " + (int) w + "x" + (int) h + (ask ? " ask" : "") + " ready+" + readyAfter + (skip ? " skip" : "");
        HandoffMoves.Stage s = stage(w, h);
        if (ask) { s.hasSeat = true; s.seatX = 60f; s.seatY = h * .7f; }
        HandoffScript sc = new HandoffScript(m, s, ask);
        HandoffScript.Frame f = new HandoffScript.Frame();
        float dt = 1f / 60f, t = 0f, sincePick = -1f;
        int slaps = 0, dones = 0, kicks = 0;
        boolean wasReady = false, roomsAfterSlap = false, slapped = false;
        float px = Float.NaN, py = 0f, worstJump = 0f;
        String worstAt = "";
        float waited = 0f;
        for (int i = 0; i < 60 * 30; i++) {
            if (ask && sincePick < 0f && t >= 1.5f) { sc.frame(f); sc.pick(f.him); sincePick = 0f; }
            if (!ask && sincePick < 0f) sincePick = 0f;
            if (sincePick >= 0f) sincePick += dt;
            if (!wasReady && sincePick >= readyAfter) {
                wasReady = true;
                s.itemL = .1f * w; s.itemR = .9f * w; s.itemT = .8f * h; s.itemB = .8f * h + 30f;
                sc.roomReady();
            }
            if (skip && sincePick > 1.2f) sc.skip();
            sc.advance(dt);
            sc.frame(f);
            t += dt;
            HandoffMoves.Pose o = f.him;
            check(finite(o.x) && finite(o.y) && finite(o.sc) && finite(o.rot), tag + ": a pose that is not a number at " + t);
            check(finite(f.p) && finite(f.amp) && finite(f.sig) && finite(f.sheet) && finite(f.sweep) && finite(f.gx) && finite(f.gy),
                    tag + ": a room value that is not a number at " + t);
            check(o.sc > .05f && o.sc < 3.2f, tag + ": his size " + o.sc + " at " + t);
            if (o.hand) {
                // his fists are where the room says they are: the seam, the wipe and the sheet centre on them
                check(Math.abs(f.gx * w - o.hx) < .01f && Math.abs(f.gy * h - o.hy) < .01f, tag + ": fists off the grab point at " + t);
            }
            if (sc.waiting()) waited += dt;
            if (f.slap) slaps++;
            if (f.kick) kicks++;
            if (f.done) dones++;
            if (f.slap) slapped = true;
            if (slapped && f.rooms) roomsAfterSlap = true;
            if (f.slap) check(wasReady, tag + ": the item was slapped down before the room said where it is");
            if (o.visible && !Float.isNaN(px) && sc.phase() != HandoffScript.Phase.DONE) {
                float jump = (float) Math.hypot(o.x - px, o.y - py);
                if (jump > worstJump) { worstJump = jump; worstAt = sc.phase() + " at " + t; }
            }
            px = o.visible ? o.x : Float.NaN; py = o.y;
            if (sc.phase() == HandoffScript.Phase.DONE && dones > 0) break;
        }
        check(sc.phase() == HandoffScript.Phase.DONE, tag + ": never finished");
        check(slaps == 1, tag + ": slapped " + slaps + " times");
        check(dones == 1, tag + ": finished " + dones + " times");
        check(!roomsAfterSlap, tag + ": still drawing the room's picture after the slap");
        if (m == HandoffMoves.KICK) check(skip ? kicks <= 1 : kicks == 1, tag + ": kicked " + kicks + " times");
        // he flies in arcs: no jump cut in his path (a skip cuts on purpose). At most 1/6 of the long side a frame.
        if (!skip) check(worstJump < Math.max(w, h) / 6f, tag + ": he jumps " + worstJump + " dp in one frame, " + worstAt);
        // a room that is ready before he gets to the waiting point costs no wait at all
        if (readyAfter == 0f) check(waited == 0f, tag + ": waited " + waited + " s for a room that was ready");
    }

    /** A room that never reports: he waits (and waits moving) rather than landing anywhere; the engine's own watchdog ends it. */
    private static void neverReady() {
        for (int m = 0; m < HandoffMoves.MODES; m++) {
            HandoffScript sc = new HandoffScript(m, stage(548, 1127), false);
            HandoffScript.Frame f = new HandoffScript.Frame();
            float lastX = 0f, moved = 0f;
            boolean slap = false;
            for (int i = 0; i < 60 * 8; i++) {
                sc.advance(1f / 60f);
                sc.frame(f);
                slap |= f.slap;
                if (i > 60 * 5) moved += Math.abs(f.him.x - lastX) + Math.abs(f.p) * 0f;
                lastX = f.him.x;
            }
            check(!slap, NAMES[m] + ": slapped an item into a room that never opened");
            check(sc.waiting(), NAMES[m] + ": not waiting for a room that never opened");
            check(moved > 1f || m == HandoffMoves.VACUUM, NAMES[m] + ": frozen while waiting");
        }
    }
}
