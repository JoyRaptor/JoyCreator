package cc.joycreator.handoff;

import static cc.joycreator.handoff.HandoffMoves.cl;
import static cc.joycreator.handoff.HandoffMoves.io;
import static cc.joycreator.handoff.HandoffMoves.lerp;
import static cc.joycreator.handoff.HandoffMoves.ss;

/**
 * The running order of one hand-off (FX-1), stepped a frame at a time. Pure, no Android.
 *
 * <p>ASK (he is out and waiting for the answer) → LAUNCH → MAIN → LAND → HOME → DONE. The move can
 * WAIT part-way: the room he is carrying the work to may still be opening, and he keeps tugging (or
 * circling, or bracing) until it is there. He never freezes and the show never runs ahead of the
 * real room: the item is slapped down only where the room says it really is.</p>
 */
final class HandoffScript {

    enum Phase { ASK, LAUNCH, MAIN, LAND, HOME, DONE }

    /** Everything the renderer needs for one frame. */
    static final class Frame {
        Phase phase = Phase.ASK;
        final HandoffMoves.Pose him = new HandoffMoves.Pose();
        /** The rooms are drawn by the show (false: only Joybot and his light, over whatever is really there). */
        boolean rooms;
        /** How far the move has gone. */
        float p;
        float amp, sig = .1f;          // Big Pull: the seam
        float sheet, off = 1f;         // the bed sheet, and where the old room was left
        float sweep = -1.2f, coll;     // Vacuum Sweep
        float pre;                     // Box Kick: wind-up squash, landing bounce
        /** Where the pull's seam, the sheet's twist and the wipe's suction centre on: his fists, as a share of the screen. */
        float gx = .5f, gy = .5f;
        /** The show's own seconds, for anything that only needs to keep moving. */
        float t;
        /** Seconds into a picture that plays once (the slap, the salute). */
        float actT;
        boolean hold;
        /** One-shot marks: this frame the kick lands, the item is slapped down, the show is over. */
        boolean kick, slap, done, launched;
    }

    final int mode;
    final HandoffMoves.Stage stage;

    private Phase phase;
    private float t;                 // seconds in this phase
    private float clock;             // seconds since the show began
    private float mainT;             // seconds of the move itself, waits left out
    private boolean holding;
    private float ht, leaveAt = -1f;
    private boolean ready, skip;
    private float fromX, fromY, fromSc;
    private boolean kicked, slapped, finished, launchMarked;
    private float slapClock;
    private final float[] t3 = new float[3];

    /** {@code ask}: he flies out and waits for an answer first ({@link #pick}); else straight into the move. */
    HandoffScript(int mode, HandoffMoves.Stage stage, boolean ask) {
        this.mode = mode;
        this.stage = stage;
        if (ask) phase = Phase.ASK;
        else { phase = Phase.LAUNCH; fromX = stage.btnX; fromY = stage.btnY; fromSc = .3f; }
    }

    Phase phase() { return phase; }
    boolean waiting() { return holding; }
    boolean slapped() { return slapped; }

    /** The answer is in: he leaves his seat for the move, from wherever his hover has him. */
    void pick(HandoffMoves.Pose now) {
        if (phase != Phase.ASK) return;
        fromX = now.x; fromY = now.y; fromSc = now.sc;
        phase = Phase.LAUNCH;
        t = 0f;
    }

    /** The next room is open, pictured, and has said where the item is ({@link #stage} holds it). */
    void roomReady() { ready = true; }

    /** A tap: straight to the end, as soon as there is a room to end in. */
    void skip() { skip = true; }

    void advance(float dt) {
        if (phase == Phase.DONE) return;
        clock += dt;
        if (slapped) slapClock += dt;
        if (skip && ready && phase != Phase.ASK) { phase = Phase.DONE; return; }
        int m = mode;
        switch (phase) {
            case ASK:
                t += dt;
                break;
            case LAUNCH:
                t += dt;
                if (t >= HandoffMoves.launchS(m) * stage.pace()) { phase = Phase.MAIN; mainT = 0f; t = 0f; startHoldIfDue(); }
                break;
            case MAIN: {
                float dur = HandoffMoves.mainS(m), at = HandoffMoves.holdAt(m) * dur;
                if (holding) {
                    ht += dt * (ready ? 2f : 1f);       // the room is there: finish this beat briskly
                    if (ready) {
                        float beat = HandoffMoves.holdBeatS(m);
                        if (leaveAt < 0f) leaveAt = beat <= 0f ? ht : (float) Math.ceil(ht / beat) * beat;
                        if (ht >= leaveAt) { ht = leaveAt; holding = false; }
                    }
                } else {
                    boolean before = mainT < at;
                    mainT += dt;
                    if (before && mainT >= at && !ready) { mainT = at; holding = true; ht = 0f; leaveAt = -1f; }
                    if (mainT >= dur) { phase = Phase.LAND; t = 0f; }
                }
                break;
            }
            case LAND:
                t += dt;
                if (t >= HandoffMoves.landS(m)) { phase = Phase.HOME; t = 0f; }
                break;
            case HOME:
                t += dt;
                if (t >= HandoffMoves.HOME_S) phase = Phase.DONE;
                break;
            default:
                break;
        }
    }

    private void startHoldIfDue() {
        if (HandoffMoves.holdAt(mode) == 0f && !ready) { holding = true; ht = 0f; leaveAt = -1f; }
    }

    /** Fill {@code f} for the moment the script is at. */
    void frame(Frame f) {
        int m = mode;
        HandoffMoves.Stage s = stage;
        HandoffMoves.Pose o = f.him;
        f.phase = phase;
        f.t = clock;
        f.p = 0f; f.amp = 0f; f.sig = .1f; f.sheet = 0f; f.off = 1f; f.sweep = -1.2f; f.coll = 0f; f.pre = 0f;
        f.hold = false; f.kick = f.slap = f.done = f.launched = false;
        f.rooms = true;
        switch (phase) {
            case ASK:
                HandoffMoves.askPose(s, t, o);
                f.rooms = false;
                break;
            case LAUNCH: {
                float u = t / (HandoffMoves.launchS(m) * s.pace());
                HandoffMoves.launchPose(s, m, u, fromX, fromY, fromSc, o);
                if (m == HandoffMoves.KICK) f.pre = ss(.75f, 1f, u) * .6f;
                if (!launchMarked) { launchMarked = true; f.launched = true; }
                break;
            }
            case MAIN: {
                float u = cl(mainT / HandoffMoves.mainS(m));
                f.hold = holding;
                HandoffMoves.mainPose(s, m, u, holding, ht, o);
                if (o.carry) HandoffMoves.strain(o, clock);
                if (m == HandoffMoves.PULL) {
                    HandoffMoves.pullShape(u, holding, ht, t3);
                    f.amp = t3[0]; f.sig = t3[1]; f.p = t3[2];
                } else if (m == HandoffMoves.PEEL) {
                    f.p = u;
                } else if (m == HandoffMoves.KICK) {
                    if (holding) f.pre = .6f + .3f * (float) Math.sin(ht * 7f);
                    else if (u < .4f) f.pre = .6f * (1f - u / .4f);
                    else f.p = io((u - .4f) / .6f);
                    if (!holding && u >= .4f && !kicked) { kicked = true; f.kick = true; }
                } else {
                    HandoffMoves.vacShape(u, holding, ht, t3);
                    f.sweep = t3[1]; f.coll = t3[2];
                }
                break;
            }
            case LAND: {
                float u = t / HandoffMoves.landS(m), slapU = HandoffMoves.slapAt(m);
                HandoffMoves.landPose(s, m, u, o);
                if (o.carry) HandoffMoves.strain(o, clock);
                after(f);
                if (u < slapU && (m == HandoffMoves.PULL || m == HandoffMoves.KICK)) {
                    float e = HandoffMoves.sheetAt(u, m);
                    f.sheet = e;
                    f.off = m == HandoffMoves.PULL ? .94f : 1f;
                    if (m == HandoffMoves.PULL) f.amp = lerp(.94f, 1f, ss(0f, .5f, e));
                    if (m == HandoffMoves.KICK) f.pre = -.5f * (float) Math.sin(u * 20f) * Math.max(0f, 1f - u * 4f);
                    if (!o.hand) { f.gx = s.x(352f) / s.w; f.gy = s.y(330f) / s.h; }
                }
                if (m == HandoffMoves.KICK && !kicked) { kicked = true; f.kick = true; }
                if (u >= slapU) { markSlap(f); f.rooms = false; f.actT = slapClock; }   // from the slap on, the real room is what you see
                break;
            }
            case HOME:
                HandoffMoves.homePose(s, t / HandoffMoves.HOME_S, o);
                f.actT = o.act == HandoffMoves.ACT_SALUTE ? t - HandoffMoves.HOME_SALUTE * HandoffMoves.HOME_S : slapClock;
                after(f);
                markSlap(f);
                f.rooms = false;
                break;
            default:
                o.reset();
                o.visible = false;
                o.set(s.homeX, s.homeY, .3f);
                after(f);
                markSlap(f);
                f.rooms = false;
                if (!finished) { finished = true; f.done = true; }
                break;
        }
        if (o.hand) { f.gx = o.hx / s.w; f.gy = o.hy / s.h; }
        else if (phase == Phase.MAIN || phase == Phase.LAUNCH) { f.gx = o.x / s.w; f.gy = o.y / s.h; }
    }

    /** The move is over: the new room fills the screen. */
    private void after(Frame f) {
        f.p = 1f; f.sweep = 8f; f.coll = 1f; f.amp = 1f; f.sig = 9f;
    }

    private void markSlap(Frame f) {
        if (!slapped) { slapped = true; f.slap = true; }
    }
}
