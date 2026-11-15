package com.fadcam.ui.lobby.arrival.stage;

import androidx.annotation.NonNull;

import com.fadcam.ui.lobby.arrival.Flight;

import java.util.ArrayList;
import java.util.Random;

/**
 * Joybot's thruster and the effects around him, simulated frame by frame (GL thread only).
 *
 * <p>Owner, 14 Nov: "his little hover thruster bottom emits little plasma push rings that grow ...
 * size is determined by the amount of acceleration ... drift away from his bottom"; a quarter of the
 * drift, sizes from a quarter to full; "more sizzle"; the reverse-thrust burst only on the steep last
 * leg; "fade away to a very calm glow and calm low emission once he's in place"; "make him magical"
 * in the special-effects sense; a light trail in hot pink to yellowish white laid on with Screen; heat
 * haze under him and along his trail. Every effect is made of his own stuff: his plasma pink and the
 * white-hot core of his thruster.</p>
 *
 * <p>A port of {@code Mock.prototype.fx} on the design page; dp and milliseconds throughout.</p>
 */
final class Fx {

    static final class Ring { float x, y, vx, vy, age, life, r0, grow, rot; boolean calm; }
    static final class Spark { float x, y, vx, vy, g, age, life, s; }
    static final class Glint { float x, y, age, life, s, a; }
    /** Where he was a moment ago: the trail's heat haze sits on these. */
    static final class Mark { float x, y, rot; }

    final ArrayList<Ring> rings = new ArrayList<>();
    final ArrayList<Spark> sparks = new ArrayList<>();
    final ArrayList<Glint> glints = new ArrayList<>();
    final ArrayList<Mark> marks = new ArrayList<>();
    /** Landing flash: age < 0 means none. */
    float flashX, flashY, flashAge = -1f, flashLife = 320f, flashS;
    /** 0 cold .. 1 flying hot. */
    float heat;
    /** Thruster glow strength this frame. */
    float glow;
    /** His peg (where the thruster is) and the way his thrust points, this frame. */
    float pegX, pegY, dnX, dnY;
    float speed;
    Flight.Mode mode = Flight.Mode.FLY;

    private final Random dice = new Random();
    private long lastT = -1L;
    private float px = Float.NaN, py;
    private float vX, vY;
    private float accRing, accSpark, accGlint, accMark;
    private boolean touched;
    private final float pegUp;

    Fx(float pegUp) { this.pegUp = pegUp; }

    private float rnd(float a, float b) { return a + dice.nextFloat() * (b - a); }

    /** Advance to time {@code t} with him at {@code pose}. */
    void step(long t, @NonNull Flight.Pose pose) {
        if (lastT < 0 || t < lastT || t - lastT > 400) {
            rings.clear(); sparks.clear(); glints.clear(); marks.clear();
            flashAge = -1f; px = Float.NaN; vX = vY = 0f; heat = 0f; touched = false;
            accRing = accSpark = accGlint = accMark = 0f;
            lastT = t;
        }
        float dtm = Math.max(1f, t - lastT), dt = dtm / 1000f;
        lastT = t;
        double th = Math.toRadians(pose.rot);
        dnX = (float) -Math.sin(th);
        dnY = (float) Math.cos(th);
        pegX = pose.x + dnX * pegUp;
        pegY = pose.y + dnY * pegUp;
        if (!Float.isNaN(px)) {
            float vx = (pose.x - px) / dt, vy = (pose.y - py) / dt, kk = Math.min(1f, dt * 18f);
            vX += (vx - vX) * kk;
            vY += (vy - vY) * kk;
        }
        px = pose.x;
        py = pose.y;
        speed = (float) Math.hypot(vX, vY);
        mode = pose.mode;
        float k = pose.k;
        float want = mode == Flight.Mode.CALM ? 0f : mode == Flight.Mode.COAST ? .45f
                : mode == Flight.Mode.SKID || mode == Flight.Mode.HOVER ? .7f : 1f;
        heat += (want - heat) * Math.min(1f, dt * (want < heat ? 1.6f : 6f));
        accRing += dtm; accSpark += dtm; accGlint += dtm; accMark += dtm;

        // push rings: slow drift, sizes from a quarter to full
        float ringEvery = mode == Flight.Mode.BURST ? 34f : mode == Flight.Mode.FLY ? 150f
                : mode == Flight.Mode.HOVER ? 230f : mode == Flight.Mode.CALM ? 1100f : 1e9f;
        if (accRing >= ringEvery && rings.size() < 60) {
            accRing = 0f;
            float base = mode == Flight.Mode.BURST ? 1.8f + 1.4f * k
                    : mode == Flight.Mode.FLY || mode == Flight.Mode.HOVER ? .6f : .4f;
            float f0 = rnd(.25f, 1f);
            Ring r = new Ring();
            r.x = pegX; r.y = pegY;
            r.vx = dnX * (10f + 14f * base) + vX * .02f;
            r.vy = dnY * (10f + 14f * base) + vY * .02f;
            r.calm = mode == Flight.Mode.CALM;
            r.life = r.calm ? 1300f : 560f + 300f * Math.min(base, 2.5f);
            r.r0 = (3f + 1.6f * base) * f0;
            r.grow = (9f + 9f * Math.min(base, 3f)) * f0;
            r.rot = (float) th;
            rings.add(r);
        }
        // sparks: the sizzle while he flies, a spray in the burn, a skid off the floor
        float sparkEvery = mode == Flight.Mode.BURST ? 9f : mode == Flight.Mode.FLY ? 20f : mode == Flight.Mode.SKID ? 14f
                : mode == Flight.Mode.HOVER ? 40f : mode == Flight.Mode.COAST ? 70f : 900f;
        while (accSpark >= sparkEvery && sparks.size() < 160) {
            accSpark -= sparkEvery;
            Spark s = new Spark();
            if (mode == Flight.Mode.SKID) {
                s.x = pegX + rnd(-4f, 4f); s.y = pegY;
                s.vx = -vX * .35f + rnd(30f, 130f); s.vy = rnd(-90f, -15f);
                s.g = 520f; s.life = rnd(260f, 480f); s.s = rnd(.8f, 1.8f);
            } else {
                float spread = rnd(-.7f, .7f), sp = mode == Flight.Mode.BURST ? rnd(80f, 230f) : rnd(25f, 110f);
                float c = (float) Math.cos(spread), si = (float) Math.sin(spread);
                float ddx = dnX * c - dnY * si, ddy = dnX * si + dnY * c;
                boolean calm = mode == Flight.Mode.CALM;
                s.x = pegX + rnd(-3f, 3f); s.y = pegY + rnd(-3f, 3f);
                s.vx = ddx * sp + vX * .15f; s.vy = ddy * sp + vY * .15f;
                s.g = calm ? -30f : 240f; s.life = calm ? 900f : rnd(240f, 560f); s.s = calm ? rnd(.6f, 1f) : rnd(.8f, 2.2f);
            }
            sparks.add(s);
        }
        if (accSpark > 2000f) accSpark = 0f;
        // plasma glints along the trail
        if ((mode == Flight.Mode.FLY || mode == Flight.Mode.BURST) && accGlint >= 70f) {
            accGlint = 0f;
            if (dice.nextFloat() < .65f && glints.size() < 24) {
                Glint g = new Glint();
                g.x = pegX + rnd(-10f, 10f) - vX * .02f; g.y = pegY + rnd(-8f, 8f) - vY * .02f;
                g.life = rnd(300f, 460f); g.s = rnd(3f, 7f); g.a = rnd(0f, .8f);
                glints.add(g);
            }
        }
        // where he has just been, for the trail's haze
        if (accMark >= 34f) {
            accMark = 0f;
            if (speed > 420f && mode != Flight.Mode.CALM) {
                Mark m = new Mark();
                m.x = pose.x; m.y = pose.y; m.rot = (float) th;
                marks.add(0, m);
            } else if (!marks.isEmpty()) marks.remove(marks.size() - 1);
            while (marks.size() > 6) marks.remove(marks.size() - 1);
        }
        // the landing flash, once
        if (pose.down && !touched) {
            touched = true;
            float big = pose.touch == Flight.Approach.PLOP ? 1f : pose.touch == Flight.Approach.ROCKET ? .6f : .4f;
            flashX = pegX; flashY = pegY; flashAge = 0f; flashS = big;
            int n = Math.round(18f * big);
            for (int i = 0; i < n; i++) {
                Spark s = new Spark();
                float side = dice.nextBoolean() ? -1f : 1f;
                s.x = pegX + rnd(-6f, 6f); s.y = pegY;
                s.vx = side * rnd(60f, 240f) * big; s.vy = rnd(-160f, -30f) * big;
                s.g = 620f; s.life = rnd(300f, 600f); s.s = rnd(1f, 2.4f);
                sparks.add(s);
            }
        }
        glow = mode == Flight.Mode.BURST ? .65f + .5f * k : mode == Flight.Mode.FLY ? .45f
                : mode == Flight.Mode.SKID || mode == Flight.Mode.HOVER ? .5f : mode == Flight.Mode.COAST ? .15f
                : .28f + .05f * (float) Math.sin(t / 2400.0 * Math.PI * 2.0);

        // move and age everything
        float ringDrag = (float) Math.pow(.3, dt);
        for (int i = rings.size() - 1; i >= 0; i--) {
            Ring r = rings.get(i);
            r.age += dtm;
            if (r.age >= r.life) { rings.remove(i); continue; }
            r.x += r.vx * dt; r.y += r.vy * dt; r.vx *= ringDrag; r.vy *= ringDrag;
        }
        float sdx = (float) Math.pow(.4, dt), sdy = (float) Math.pow(.6, dt);
        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.age += dtm;
            if (s.age >= s.life) { sparks.remove(i); continue; }
            s.vy += s.g * dt; s.vx *= sdx; s.vy *= sdy; s.x += s.vx * dt; s.y += s.vy * dt;
        }
        for (int i = glints.size() - 1; i >= 0; i--) {
            Glint g = glints.get(i);
            g.age += dtm;
            if (g.age >= g.life) glints.remove(i);
        }
        if (flashAge >= 0f) {
            flashAge += dtm;
            if (flashAge >= flashLife) flashAge = -1f;
        }
    }

    /** Nothing left to draw: every ring, spark and glint has gone and he is calm. */
    boolean quiet() {
        return rings.isEmpty() && sparks.isEmpty() && glints.isEmpty() && flashAge < 0f && heat < .02f;
    }
}
