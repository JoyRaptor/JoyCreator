package cc.joycreator.handoff;

import java.util.ArrayList;
import java.util.Random;

/**
 * Joybot's thruster and the light around him during a hand-off: push rings, sparks, glints and the
 * flash when something lands. GL thread only.
 *
 * <p>Lifted from his arrival ({@code ui/lobby/arrival/stage/Fx}, BOT-5, the Joybot sprites lane), with
 * its numbers: the owner asked for "all the flight elements of the trail and rings and sparkles
 * that he has" (16 Nov). It differs only in what drives it: any pose a frame, not the arrival's one
 * flight. If the arrival's stage is ever opened to other callers, this file goes and that one is used.
 * dp and milliseconds throughout.</p>
 */
final class HandoffFx {

    static final class Ring { float x, y, vx, vy, age, life, r0, grow, rot; boolean calm; }
    static final class Spark { float x, y, vx, vy, g, age, life, s; }
    static final class Glint { float x, y, age, life, s, a; }

    final ArrayList<Ring> rings = new ArrayList<>();
    final ArrayList<Spark> sparks = new ArrayList<>();
    final ArrayList<Glint> glints = new ArrayList<>();
    /** A flash where something landed: age < 0 means none. */
    float flashX, flashY, flashAge = -1f, flashLife = 320f, flashS;
    /** 0 cold .. 1 flying hot. */
    float heat;
    float glow;
    float pegX, pegY, dnX, dnY;
    float scale = 1f;
    float speed;
    int mode = HandoffMoves.FX_FLY;

    private final Random dice = new Random();
    private boolean started;
    private float px, py, vX, vY;
    private float accRing, accSpark, accGlint;
    private float clockMs;

    private float rnd(float a, float b) { return a + dice.nextFloat() * (b - a); }

    /** Advance {@code dtm} ms with him at {@code x, y}, turned {@code rot} rad, his peg {@code pegUp} dp under his body. */
    void step(float dtm, float x, float y, float rot, float sc, int fxMode, float pegUp, boolean visible) {
        dtm = Math.max(1f, Math.min(100f, dtm));
        float dt = dtm / 1000f;
        clockMs += dtm;
        dnX = (float) -Math.sin(rot);
        dnY = (float) Math.cos(rot);
        scale = sc;
        pegX = x + dnX * pegUp * sc;
        pegY = y + dnY * pegUp * sc;
        if (started) {
            float vx = (x - px) / dt, vy = (y - py) / dt, kk = Math.min(1f, dt * 18f);
            vX += (vx - vX) * kk;
            vY += (vy - vY) * kk;
        }
        started = true;
        px = x;
        py = y;
        speed = (float) Math.hypot(vX, vY);
        mode = fxMode;
        boolean burst = mode == HandoffMoves.FX_BURST, fly = mode == HandoffMoves.FX_FLY,
                hover = mode == HandoffMoves.FX_HOVER, calm = mode == HandoffMoves.FX_CALM;
        float k = .6f;
        float want = calm ? 0f : hover ? .7f : 1f;
        heat += (want - heat) * Math.min(1f, dt * (want < heat ? 1.6f : 6f));
        accRing += dtm; accSpark += dtm; accGlint += dtm;

        if (visible) {
            float ringEvery = burst ? 34f : fly ? 150f : hover ? 230f : 1100f;
            if (accRing >= ringEvery && rings.size() < 60) {
                accRing = 0f;
                float base = burst ? 1.8f + 1.4f * k : calm ? .4f : .6f;
                float f0 = rnd(.25f, 1f);
                Ring r = new Ring();
                r.x = pegX; r.y = pegY;
                r.vx = dnX * (10f + 14f * base) + vX * .02f;
                r.vy = dnY * (10f + 14f * base) + vY * .02f;
                r.calm = calm;
                r.life = calm ? 1300f : 560f + 300f * Math.min(base, 2.5f);
                r.r0 = (3f + 1.6f * base) * f0 * scale;
                r.grow = (9f + 9f * Math.min(base, 3f)) * f0 * scale;
                r.rot = rot;
                rings.add(r);
            }
            float sparkEvery = burst ? 9f : fly ? 20f : hover ? 40f : 900f;
            while (accSpark >= sparkEvery && sparks.size() < 160) {
                accSpark -= sparkEvery;
                Spark s = new Spark();
                float spread = rnd(-.7f, .7f), sp = burst ? rnd(80f, 230f) : rnd(25f, 110f);
                float c = (float) Math.cos(spread), si = (float) Math.sin(spread);
                float ddx = dnX * c - dnY * si, ddy = dnX * si + dnY * c;
                s.x = pegX + rnd(-3f, 3f); s.y = pegY + rnd(-3f, 3f);
                s.vx = ddx * sp + vX * .15f; s.vy = ddy * sp + vY * .15f;
                s.g = calm ? -30f : 240f; s.life = calm ? 900f : rnd(240f, 560f); s.s = calm ? rnd(.6f, 1f) : rnd(.8f, 2.2f);
                sparks.add(s);
            }
            if ((fly || burst) && accGlint >= 70f) {
                accGlint = 0f;
                if (dice.nextFloat() < .65f && glints.size() < 24) {
                    Glint g = new Glint();
                    g.x = pegX + rnd(-10f, 10f) - vX * .02f; g.y = pegY + rnd(-8f, 8f) - vY * .02f;
                    g.life = rnd(300f, 460f); g.s = rnd(3f, 7f) * Math.max(1f, scale * .7f); g.a = rnd(0f, .8f);
                    glints.add(g);
                }
            }
        }
        if (accSpark > 2000f) accSpark = 0f;
        glow = !visible ? 0f : burst ? .65f + .5f * k : fly ? .45f : hover ? .5f
                : .28f + .05f * (float) Math.sin(clockMs / 2400.0 * Math.PI * 2.0);

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

    /** Something landed at {@code x, y}: the arrival's landing flash and its spray of sparks ({@code big} 0..1.6). */
    void land(float x, float y, float big) {
        flashX = x; flashY = y; flashAge = 0f; flashS = big;
        int n = Math.round(18f * big);
        for (int i = 0; i < n && sparks.size() < 200; i++) {
            Spark s = new Spark();
            float side = dice.nextBoolean() ? -1f : 1f;
            s.x = x + rnd(-6f, 6f); s.y = y;
            s.vx = side * rnd(60f, 240f) * big; s.vy = rnd(-160f, -30f) * big;
            s.g = 620f; s.life = rnd(300f, 600f); s.s = rnd(1f, 2.4f);
            sparks.add(s);
        }
        for (int i = 0; i < 6 && glints.size() < 30; i++) {
            Glint g = new Glint();
            g.x = x + rnd(-40f, 40f) * big; g.y = y + rnd(-26f, 14f) * big;
            g.life = rnd(300f, 520f); g.s = rnd(4f, 9f); g.a = rnd(0f, .8f);
            glints.add(g);
        }
    }

    /** A push ring he leaves where he sets off or arrives. */
    void puff(float x, float y, float rot, float size) {
        Ring r = new Ring();
        r.x = x; r.y = y; r.rot = rot;
        r.life = 620f; r.r0 = 5f * size; r.grow = 26f * size;
        rings.add(r);
    }
}
