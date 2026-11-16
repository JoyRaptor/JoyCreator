package com.fadcam.ui.lobby;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

/**
 * How Joybot holds himself while his frames play: his hover, and his body language for each expression.
 *
 * <p>Owner, 11-15 evening (picked on the hover picks page): "Hover D (Alive), 12 dp above the seat ... every once in a
 * while a thruster pulse would be good to throw in there". Body language: "his hovering is a part of his expression and
 * his reactions ... when he does the oopsies, he's going to be a little bit lower because he's a little bit shy. Whereas
 * when he has an idea, he's going to pop up"; thinking paces, and on a long think "travels and paces longer and stops
 * longer at places, maybe wanders off to the side for a little bit, and then once thinking's about over, he can hop
 * back"; celebrating hops in a circle, "coming at you, going to the right, coming at you, crossing over, going back to
 * the left, using scale to imitate depth".</p>
 *
 * <p>Pure motion maths in dp and degrees; the view draws him with it. Each animation names its body language in
 * acts.json ("moves.body"); a change of body language blends from where he was over 350 ms, so he never jumps.</p>
 */
final class JoybotBody {

    /** Where he is this frame, relative to his resting place: dp (y down), degrees, squash, size, which way he faces. */
    static final class Pose {
        float x, y, rot, sx = 1f, sy = 1f, scale = 1f;
        int face = 1;
        /** A thruster puff this frame: drop a ring. */
        boolean ring;

        void set(@NonNull Pose p) { x = p.x; y = p.y; rot = p.rot; sx = p.sx; sy = p.sy; scale = p.scale; face = p.face; }
    }

    /** Told when he should throw a few sparks: where, in dp from his resting peg, and whether off the ground. */
    interface Sparks { void at(float xDp, float yDp, int n, boolean ground); }

    private static final double TAU = Math.PI * 2.0;
    private static final long BLEND_MS = 350L;

    /** True in the chat, where he has room to pace and show; small (a 40 dp button) keeps his travels tiny. */
    boolean roomy;

    private final Random dice = new Random();
    @Nullable private String kind;
    private long kindAt, blendAt = -1L;
    private final Pose prev = new Pose(), last = new Pose(), tmp = new Pose();
    private boolean hopBack;

    // the hover's habits (owner's D): a glance, a dart, a re-hop, and now and then a thruster puff
    private long habitAt = -1L, habitEnd;
    private int habit, habitDir = 1;

    // thinking: pacing runs
    private float paceFrom, paceTo, paceNow;
    private long legAt, legMs, stopUntil;
    private int paceFace = 1;

    // one-off effects inside a body language
    private boolean popSparked;
    private long lastHop = -1L, lastTurn;
    private int readFace = 1;

    /** His pose at {@code now} (uptime ms) for body language {@code body} (null: just his hover). */
    void pose(long now, @Nullable String body, @NonNull Pose out, @Nullable Sparks sparks) {
        if (!eq(body, kind)) {
            prev.set(last);
            hopBack = "think".equals(kind) && Math.abs(last.x) > 10f;
            kind = body;
            kindAt = now;
            blendAt = now;
            popSparked = false;
            lastHop = -1L;
            if ("think".equals(body)) { paceNow = 0f; paceFrom = 0f; paceTo = 0f; legMs = 0L; stopUntil = now + 600L; paceFace = 1; }
        }
        float t = now / 1000f, tk = (now - kindAt) / 1000f;
        target(t, tk, now, tmp, sparks);
        long blend = hopBack ? 650L : BLEND_MS;                 // a hop back from pacing takes its time
        float u = blendAt < 0L ? 1f : Math.min(1f, (now - blendAt) / (float) blend);
        float e = ease(u);
        out.x = lerp(prev.x, tmp.x, e);
        out.y = lerp(prev.y, tmp.y, e) - (hopBack && u < 1f ? 10f * (float) Math.sin(Math.PI * u) : 0f);
        out.rot = lerp(prev.rot, tmp.rot, e);
        out.sx = lerp(prev.sx, tmp.sx, e);
        out.sy = lerp(prev.sy, tmp.sy, e);
        out.scale = lerp(prev.scale, tmp.scale, e);
        out.face = u < .5f ? prev.face : tmp.face;
        out.ring = tmp.ring;
        if (u >= 1f) { blendAt = -1L; hopBack = false; }
        last.set(out);
    }

    /** The hover plus the body language, at full strength. */
    private void target(float t, float tk, long now, @NonNull Pose o, @Nullable Sparks sparks) {
        float room = roomy ? 1f : .25f, calm = 1f;
        String k = kind;
        if ("read".equals(k) || "notes".equals(k)) calm = .6f;
        else if ("work".equals(k) || "type".equals(k)) calm = .7f;
        else if ("think".equals(k)) calm = .8f;
        // Eve drift: a slow figure eight, leaning the way he drifts, a soft squash at the bottom
        o.x = 2.6f * calm * (float) Math.sin(TAU * t / 5.2);
        o.y = 3.4f * calm * (float) Math.sin(TAU * t / 2.9 + .6);
        o.rot = 3f * calm * (float) Math.cos(TAU * t / 5.2);
        float low = Math.max(0f, (float) Math.sin(TAU * t / 2.9 + .6));
        o.sx = 1f + .025f * low * low;
        o.sy = 1f - .025f * low * low;
        o.scale = 1f;
        o.face = 1;
        o.ring = false;
        if (k == null || "greet".equals(k)) habits(now, o, room);
        if (k == null) return;
        switch (k) {
            case "nervous":                       // the owner's favourite: lower, smaller, shy, a fine shiver
                o.y += 6f;
                o.scale *= .95f;
                o.x += 1.2f * (float) Math.sin(TAU * tk * 9);
                o.rot += -4f + .8f * (float) Math.sin(TAU * tk * 7);
                break;
            case "idea": {                        // pops up with a stretch and sparks, then hovers high and light
                float pop = tk < .5f ? (float) Math.sin(Math.PI / 2 * tk / .5) * 1.18f
                        : 1f - .18f * (float) (Math.exp(-(tk - .5f) * 5f) * Math.cos((tk - .5f) * 9f));
                o.y -= 12f * pop;
                if (tk < .3f) { o.sy *= 1.1f; o.sx *= .93f; }
                if (tk > .25f && !popSparked && sparks != null) { popSparked = true; sparks.at(0f, -40f, 10, false); }
                o.y += 1.5f * (float) Math.sin(TAU * tk / 1.4);
                break;
            }
            case "think": pace(now, tk, o, room); break;
            case "talk":
                o.rot += -2.5f;
                o.y -= 1.6f * Math.abs((float) Math.sin(TAU * tk * 2.2));
                break;
            case "type":                          // typing his reply: leaning in, quick small jiggles
                o.rot += -2f;
                o.y -= .8f * Math.abs((float) Math.sin(TAU * tk * 6));
                o.x += .4f * (float) Math.sin(TAU * tk * 11);
                break;
            case "show-right":
            case "show-left": {                   // gets up and slides toward what he shows, leaning in
                float f = ease(Math.min(1f, tk / .5f)), dir = "show-left".equals(k) ? -1f : 1f;
                o.y -= 8f * f;
                o.x += 14f * f * dir * room;
                o.rot += 4f * f * dir;
                break;
            }
            case "show-down": {                   // leans over the box below him
                float f = ease(Math.min(1f, tk / .5f));
                o.y -= 3f * f;
                o.rot += 2f * f;
                o.sy *= 1f - .03f * f;
                break;
            }
            case "cheer": {                       // hops round in a circle, nearer and further (owner, 11-15)
                double th = TAU * tk / 2.4;
                o.x += 18f * (float) Math.sin(th) * room;
                o.scale *= 1f + .16f * (float) Math.cos(th);
                o.face = Math.cos(th) < 0 ? -1 : 1;
                float P = .45f, p = (tk % P) / P;
                o.y -= 12f * 4f * p * (1f - p);
                float q = p < .12f ? (1f - p / .12f) * .14f : 0f;
                o.sx *= 1f + q;
                o.sy *= 1f - q;
                long hop = (long) (tk / P);
                if (hop != lastHop) { lastHop = hop; if (hop > 0 && sparks != null) sparks.at(o.x, 0f, 5, true); }
                break;
            }
            case "read":                          // reading: a little lean, and now and then he looks the other way
                o.rot += -2f;
                if (now - lastTurn > 4000L + dice.nextInt(3000)) { lastTurn = now; if (dice.nextInt(3) == 0) readFace = -readFace; }
                o.face = readFace;
                break;
            case "notes":
                o.rot += -1.5f;
                o.y -= .6f * (float) Math.sin(TAU * tk * 5);
                break;
            case "work":
                o.rot += -2f;
                break;
            case "paint":                         // sweeping with his strokes
                o.x += 6f * (float) Math.sin(TAU * tk / 1.6) * room;
                o.rot += 6f * (float) Math.sin(TAU * tk / 1.6 + .4);
                break;
            case "greet":
                o.y -= 4f * (float) Math.sin(Math.PI * Math.min(1f, tk / .8f));
                break;
            default:
                break;
        }
    }

    /** The hover's little habits every few seconds: a glance, a small dart, a re-hop, a thruster puff (owner's D). */
    private void habits(long now, @NonNull Pose o, float room) {
        if (habitAt < 0L || now > habitEnd + 200L) {
            habitAt = now + 2500L + dice.nextInt(4000);
            habit = dice.nextInt(5);                 // 0 glance, 1 dart, 2 re-hop, 3-4 a puff (the owner asked for those)
            habitDir = dice.nextBoolean() ? 1 : -1;
            habitEnd = habitAt + (habit == 0 || habit == 1 ? 1100L : habit == 2 ? 550L : 1300L);
        }
        if (now < habitAt || now > habitEnd) return;
        float u = (now - habitAt) / (float) (habitEnd - habitAt), bump = (float) Math.sin(Math.PI * u);
        switch (habit) {
            case 0:
                o.rot += 7f * habitDir * Math.min(1f, bump * 1.6f);
                break;
            case 1: {
                float f = u < .25f ? ease(u / .25f) : 1f - ease((u - .25f) / .75f);
                o.x += 8f * habitDir * f * room;
                o.rot += 4f * habitDir * f;
                break;
            }
            case 2: {
                o.y -= 6f * bump;
                float q = u > .85f ? (u - .85f) / .15f : 0f;
                o.sx += .06f * q;
                o.sy -= .06f * q;
                break;
            }
            default: {                            // a thruster puff: a quick lift, a slow sink, a ring
                float up = u < .18f ? 1f - (float) Math.pow(1f - u / .18f, 3) : 1f - ease((u - .18f) / .82f);
                o.y -= 4f * up;
                float st = u < .18f ? .05f * (float) Math.sin(Math.PI * u / .18f) : 0f;
                o.sy *= 1f + st;
                o.sx *= 1f - st;
                if (u < .05f && !puffed) { puffed = true; o.ring = true; }
                if (u > .5f) puffed = false;
                break;
            }
        }
    }

    private boolean puffed;

    /**
     * Pacing while he thinks: legs to and fro over his seat, facing the way he goes, stopping a while at each end; the
     * longer the think, the further he wanders (off to the side) and the longer he stops. Leaving, he hops back.
     */
    private void pace(long now, float tk, @NonNull Pose o, float room) {
        if (legMs > 0L && now < legAt + legMs) {
            float u = ease((now - legAt) / (float) legMs);
            paceNow = lerp(paceFrom, paceTo, u);
            o.y -= 2f * (float) Math.sin(Math.PI * u);
        } else if (legMs > 0L) {
            paceNow = paceTo;
            legMs = 0L;
            boolean long_ = tk > 6f;
            stopUntil = now + (long_ ? 1400L + dice.nextInt(2200) : 700L + dice.nextInt(1300));
        } else if (now >= stopUntil) {
            boolean long_ = tk > 6f;
            float far = long_ && dice.nextInt(3) == 0 ? 90f : 40f;    // a long think wanders off to the side
            float to = -6f + dice.nextFloat() * (far + 6f);
            if (Math.abs(to - paceNow) < 12f) to = paceNow > 20f ? -4f : Math.min(far, paceNow + 24f);
            paceFrom = paceNow;
            paceTo = to;
            legAt = now;
            legMs = (long) Math.max(500f, Math.abs(to - paceNow) / 26f * 1000f);
            paceFace = to < paceNow ? -1 : 1;
        }
        boolean moving = legMs > 0L;
        o.x += paceNow * room;
        o.face = moving ? paceFace : (now / 1700L % 3 == 0 ? 1 : paceFace);
        // his lean follows the way he faces, eased (a turn is a turn, not a twitch)
        float dt = paceLeanAt < 0L ? 0f : Math.min(.05f, (now - paceLeanAt) / 1000f);
        paceLeanAt = now;
        paceLean += (3f * o.face - paceLean) * Math.min(1f, dt * 6f);
        o.rot += paceLean;
        o.y -= 1f;
    }

    private float paceLean;
    private long paceLeanAt = -1L;

    private static boolean eq(@Nullable String a, @Nullable String b) { return a == null ? b == null : a.equals(b); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static float ease(float u) {
        u = Math.max(0f, Math.min(1f, u));
        return u < .5f ? 4f * u * u * u : 1f - (float) Math.pow(-2f * u + 2f, 3) / 2f;
    }
}
