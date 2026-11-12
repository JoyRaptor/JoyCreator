// Rounds the field: a blur of the distance, one direction per pass (run it across, then up).
// Blurring distances melts neighbours into each other and takes the knife edge off a bevel.
// The first pass may also wobble where it reads from, which makes the outline move like liquid.
in vec2 vUv;
out vec4 o;
uniform sampler2D uSrc;
uniform vec2  uStep;     // one field pixel along this pass's direction, in uv
uniform float uRadius;   // blur radius in field pixels
uniform vec4  uWobble;   // amount em, scale, speed, 1 = this pass wobbles
uniform vec2  uChip;     // crystal chips: how deep a chip bites (em), crystals per em
uniform float uGrow;     // crystal growth: how far a shard stands proud of the outline (em)
uniform vec4  uDrip;     // drips from undersides: longest (em), drips per em, thickness (em), speed
uniform vec4  uSplash;   // drops that leap off the tops: how high (em), drops per em, drop size (em), speed
float smax(float a, float b, float k) { float h = max(k - abs(a - b), 0.0) / k; return max(a, b) + h * h * k * 0.25; }

// How far along its column (dir +1 up, -1 down) from this point the letter's edge is; -1 = none in reach.
// It walks by the distance the field itself reports: a step that long can never pass an edge, and it
// lands on a level edge at once. (An edge that runs nearly straight up is never reached: nothing hangs
// from a wall.) Always the same seven looks, so every row of the picture agrees and the phone is not left
// to guess which looks to skip.
float edgeAlong(float x, float y, float dir, float reach) {
    float d = texture(uSrc, vec2(x, y) / uField.xy).r;
    if (d > 0.0) return -1.0;                                     // this point is inside the letter
    float t = clamp(-d, 0.0, 0.25);
    for (int k = 0; k < 6; k++) {
        d = texture(uSrc, vec2(x, y + dir * t) / uField.xy).r;
        t += clamp(-d, 0.0, 0.25);
    }
    return (d > -0.012 && t <= reach) ? t : -1.0;
}

// One column's drip: how far inside it this point is (em, negative outside; -1 = this column has none),
// and the facts (reveal, letter, line height) of the letter it hangs from.
// From an edge that faces down the stuff runs: a thread with a bead at its end. It lengthens, the bead
// lets go and falls, the thread springs back, and it starts again; every column keeps its own time.
float dripOf(vec2 p, float colm, out vec3 facts) {
    facts = vec3(0.0);
    vec4 r = hash41(colm * 7.3 + 1.0);
    if (r.y < 0.2) return -1.0;                                   // not every column drips
    float cx = (colm + 0.25 + 0.5 * r.x) / uDrip.y;
    float reach = uDrip.x * 2.4;
    float up = edgeAlong(cx, p.y, 1.0, reach);                    // the letter's underside straight above, in this drip's column
    if (up < 0.0) return -1.0;
    float edgeY = p.y + up;
    facts = texture(uSrc, vec2(cx, edgeY + 0.03) / uField.xy).gba;
    float cyc = fract(uTime * uDrip.w * (0.06 + 0.1 * r.y) + r.z);
    float len = uDrip.x * (0.3 + 0.7 * r.w), rad = uDrip.z * (0.65 + 0.5 * r.x);
    float grow = smoothstep(0.0, 0.7, cyc), fall = max(cyc - 0.7, 0.0) / 0.3;
    float neck = len * grow * (1.0 - 0.65 * smoothstep(0.0, 0.5, fall));
    float tipY = edgeY - neck;
    float thread = rad * 0.5 - length(p - vec2(cx, clamp(p.y, tipY, edgeY + rad)));
    float bead = rad * (fall > 0.0 ? 0.6 : 0.6 + 0.4 * grow) - length(p - vec2(cx, tipY));
    float d = smax(thread, bead, rad * 0.6);
    if (fall > 0.0) {
        vec2 c = vec2(cx, edgeY - len - (reach - len) * fall * fall);
        d = max(d, rad * (1.0 - smoothstep(0.7, 1.0, fall)) - length((p - c) * vec2(1.0, 0.72)));
    }
    return d;
}

// One column's leaping drop: thrown up off the top of the letter, round at the top of its path,
// stretched where it is fast, with a smaller drop following it; it falls back and merges.
float splashOf(vec2 p, float colm, out vec3 facts) {
    facts = vec3(0.0);
    vec4 r = hash41(colm * 5.9 + 11.0);
    if (r.y < 0.3) return -1.0;
    float cx = (colm + 0.35 + 0.3 * r.x) / uSplash.y;
    float dn = edgeAlong(cx, p.y, -1.0, uSplash.x * 1.3 + uSplash.z);    // the letter's top edge straight below, in this drop's column
    if (dn < 0.0) return -1.0;
    float edgeY = p.y - dn;
    facts = texture(uSrc, vec2(cx, edgeY - 0.03) / uField.xy).gba;
    float t = fract(uTime * uSplash.w * (0.28 + 0.3 * r.y) + r.z);              // 0 leaves .. 1 lands
    float top = uSplash.x * (0.35 + 0.65 * r.w);
    float d = -1.0;
    for (int i = 0; i < 4; i++) {
        float ti = clamp(t - 0.11 * float(i), 0.0, 1.0);                         // each follower is a beat behind the one before
        float rad = uSplash.z * (0.6 + 0.55 * r.x) * (i == 0 ? 1.0 : i == 1 ? 0.7 : i == 2 ? 0.6 : 0.5);
        float fast = abs(1.0 - 2.0 * ti);                                        // 1 leaving and landing, 0 at the top
        float aside = (i == 0 ? 0.0 : i == 1 ? 0.5 : i == 2 ? -0.75 : 1.0) * (r.z < 0.5 ? -1.0 : 1.0);
        float high = i == 0 ? 1.0 : i == 1 ? 0.9 : i == 2 ? 1.15 : 0.75;
        vec2 c = vec2(cx + ((r.z - 0.5) * 0.2 * (2.0 * ti - 1.0) + aside * 0.16 * ti) / uSplash.y,
                      edgeY + top * high * 4.0 * ti * (1.0 - ti) - rad * 0.4);
        d = max(d, rad - length((p - c) * vec2(1.0 + 0.4 * fast, 1.0 / (1.0 + 0.55 * fast))));
    }
    return d;
}

void main() {
    vec2 uv = vUv;
    if (uWobble.w > 0.5 && uWobble.x > 0.0) {
        vec2 p = uv * uField.xy;
        vec2 w = vec2(fbm2(p * uWobble.y + vec2(0.0, -uTime * uWobble.z)),
                      fbm2(p * uWobble.y + vec2(31.7, uTime * uWobble.z * 0.8))) - 0.5;
        uv += w * uWobble.x * 2.0 / uField.xy;
    }
    vec4 c = texture(uSrc, uv);
    if (uWobble.w > 0.5 && uChip.x + uGrow > 0.0) {
        // crystal chips: whole angular cells of the shape are bitten back, so the outline breaks in
        // straight facets (the same cells the look draws its fissures along, so a chip leads to a crack).
        // Other crystals have GROWN: they stand a little proud of the outline, as shards do.
        vec3 v = voronoi(uv * uField.xy * uChip.y);
        c.r -= uChip.x * smoothstep(0.5, 0.72, v.z) * (0.35 + 0.65 * v.z);
        c.r += uGrow * smoothstep(0.38, 0.12, v.z) * (1.0 - smoothstep(0.25, 0.6, v.x));
    }
    if (uWobble.w > 0.5 && uDrip.x > 0.0) {
        // DRIPS: the nearer two columns' drips, joined to the letter with a wet fillet. A drip is a true
        // shape in the field, so it takes the look's whole finish (bevel, gloss, shadow) like the letter.
        vec2 p = uv * uField.xy;
        float u = p.x * uDrip.y;
        vec3 fa, fb;
        float da = dripOf(p, floor(u), fa), db = -1.0;
        // (the next column's drip can reach this one only if a drip is fatter than its column's margin)
        if (uDrip.z * 1.2 + 0.03 > 0.25 / uDrip.y) db = dripOf(p, floor(u) + (fract(u) < 0.5 ? -1.0 : 1.0), fb);
        if (db > da) { da = db; fa = fb; }
        if (da > -0.9) {
            float k = uDrip.z, h = max(k - abs(c.r - da), 0.0) / k;
            if (da > c.r) c.gba = fa;
            c.r = max(c.r, da) + h * h * k * 0.25;
        }
    }
    if (uWobble.w > 0.5 && uSplash.x > 0.0) {
        // SPLASHES: drops leap from the tops and fall back; where one touches the letter the two run together
        vec2 p = uv * uField.xy;
        float u = p.x * uSplash.y;
        vec3 fa, fb;
        float da = splashOf(p, floor(u), fa), db = -1.0;
        if (uSplash.z * 1.15 > 0.25 / uSplash.y) db = splashOf(p, floor(u) + (fract(u) < 0.5 ? -1.0 : 1.0), fb);
        if (db > da) { da = db; fa = fb; }
        if (da > -0.9) {
            if (da > c.r) c.gba = fa;
            c.r = smax(c.r, da, uSplash.z * 1.2);
        }
    }
    if (uRadius < 0.25) { o = c; return; }
    float sigma = uRadius * 0.5;
    float sum = c.r, wsum = 1.0;
    for (int i = 1; i <= 8; i++) {
        float x = float(i) * uRadius / 8.0;
        float w = exp(-0.5 * x * x / (sigma * sigma));
        sum += w * (texture(uSrc, uv + uStep * x).r + texture(uSrc, uv - uStep * x).r);
        wsum += 2.0 * w;
    }
    o = vec4(sum / wsum, c.gba);
}
