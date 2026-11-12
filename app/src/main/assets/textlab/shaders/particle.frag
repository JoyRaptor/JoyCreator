// One particle's picture (premultiplied; uPCol.a decides how much it hides versus adds).
in vec2 vQ;
in float vAge;
in float vBright;
in float vRand;
in float vSeed;
in vec2 vAux;
out vec4 o;
uniform vec4  uPKind;    // shape, twinkle, glint, seed
uniform vec4  uPLife;    // seconds, size em, size at the end, stretch
uniform vec3  uPColA;    // when born
uniform vec3  uPColB;    // when old
uniform vec4  uPCol;     // gain, fade-in share of life, fade-out share of life, 0 = pure light .. 1 = paint
// A leaf's outline: u 0 at the stalk .. 1 at the tip, v across. How far inside it this point is.
float leafIn(float u, float v) {
    if (u < 0.0 || u > 1.0) return -1.0;
    float w = 0.62 * sin(PI * pow(u, 0.72)) * (1.0 - 0.3 * u);       // widest a third of the way along, drawn to a point
    return max(w, 0.04 * step(u, 0.25)) - abs(v);                     // and a short stalk
}
// Five petals round a middle.
float petalsIn(vec2 q) { return 0.5 + 0.42 * abs(cos(atan(q.y, q.x) * 2.5)) - length(q); }

void main() {
    float r = length(vQ);
    int kind = int(uPKind.x + 0.5);
    float m;
    if (kind == 1) {
        // a lens glint: a pin of light with one long thin ray sideways and a short one upright
        vec2 q = abs(vQ);
        float across = exp(-q.y * 70.0) * pow(1.0 - q.x, 2.5);
        float upright = exp(-q.x * 70.0) * pow(1.0 - min(q.y * 2.2, 1.0), 2.5) * 0.55;
        m = across + upright + exp(-r * r * 90.0) * 2.2;
    } else if (kind == 2) {
        // a drop: dark where it faces the lamp, a bright crescent on the far side, one hard glint
        float body = smoothstep(1.0, 0.86, r);
        float far = smoothstep(0.1, 0.95, dot(vQ, vec2(0.55, -0.83))) * smoothstep(0.35, 0.95, r);
        float glintDot = exp(-dot(vQ - vec2(-0.36, 0.42), vQ - vec2(-0.36, 0.42)) * 40.0);
        m = body * (0.22 + 1.1 * far) + glintDot * 2.5 * body;
    } else if (kind == 3) {
        m = exp(-vQ.y * vQ.y * 9.0) * (1.0 - smoothstep(0.5, 1.0, abs(vQ.x))) * exp(-r * r * 1.2);
    } else if (kind == 5) {
        // a bolt of lightning: a hair-thin white-hot path that jags more the further it gets from where it
        // left the metal, forks once, and strikes again along a new path a few times before it dies
        float u = vQ.x * 0.5 + 0.5, v = vQ.y;
        float strike = floor(vAge * 5.0), sd = vSeed + strike * 7.31;
        float env = min(u * 5.0, 1.0);
        float path = ((vnoise(vec2(u * 4.0, sd)) - 0.5) * 1.1 + (vnoise(vec2(u * 11.0, sd + 9.0)) - 0.5) * 0.5
                    + (vnoise(vec2(u * 29.0, sd + 4.0)) - 0.5) * 0.2) * env;
        float em = 0.4 * uPLife.y * (0.45 + 1.1 * vRand);                  // one unit across the bolt's picture, in em
        float thin = 1.0 - 0.6 * u;
        float d1 = abs(v - path) * em;
        float core = (exp(-d1 / (0.0022 * thin)) + 0.22 * exp(-d1 / 0.01)) * (1.0 - u * u);
        float u0 = 0.25 + 0.4 * hash11(sd * 3.3), side = hash11(sd * 5.1) < 0.5 ? -1.0 : 1.0;
        float path2 = path + side * max(u - u0, 0.0) * 1.1 + (vnoise(vec2(u * 17.0, sd + 21.0)) - 0.5) * 0.3 * step(u0, u);
        float d2 = abs(v - path2) * em;
        core += (exp(-d2 / 0.0016) + 0.15 * exp(-d2 / 0.008)) * step(u0, u) * (1.0 - smoothstep(u0, min(u0 + 0.45, 1.0), u)) * 0.6;
        float live = step(0.35, hash11(sd * 1.7));                       // between strikes there is nothing
        m = core * live * (0.5 + 0.5 * hash11(sd * 2.9)) * smoothstep(1.0, 0.75, abs(v)) * 3.0;
    } else if (kind == 8) {
        // a RIVET: a domed head lit from the upper left, rusted like the plate it holds; it sits in a ring of
        // shade and throws a small shadow
        float fw8 = fwidth(vQ.y) * 1.5, rr = length(vQ) / 0.72;
        float head = smoothstep(1.0, 1.0 - fw8 / 0.72, rr);
        vec3 nr = normalize(vec3(vQ / 0.72 * 0.9, sqrt(max(1.0 - rr * rr, 0.0)) + 0.3));
        float ndl8 = max(dot(nr, normalize(vec3(-0.55, 0.6, 0.6))), 0.0);
        float mott = tnoise(vQ * 2.6 + vSeed);
        vec3 tone8 = mix(uPColB, uPColA, mott) * (0.2 + 1.0 * ndl8) + vec3(1.0, 0.9, 0.75) * pow(ndl8, 14.0) * 0.16;
        tone8 *= 1.0 - 0.4 * smoothstep(0.75, 1.0, rr);
        float shade8 = max(smoothstep(1.4, 1.0, rr) * 0.5, smoothstep(1.0, 0.75, length(vQ - vAux) / 0.72) * 0.5);
        float k8 = head * vBright;
        o = vec4(tone8 * uPCol.x * k8, k8 + shade8 * vBright * (1.0 - head));
        return;
    } else if (kind == 6 || kind == 7) {
        // a LEAF (a pointed blade, folded a little along its midrib, veins swept forward, a waxy gleam)
        // or a BLOSSOM (five petals round a middle). Painted, not light; each throws a small shadow.
        float fw = fwidth(vQ.y) * 1.5, body, shadowM;
        vec2 qs = vQ - vAux;
        vec3 tone;
        if (kind == 6) {
            float u = vQ.x * 0.5 + 0.5, v = vQ.y, din = leafIn(u, v);
            body = smoothstep(0.0, fw, din);
            float rib = exp(-abs(v) / 0.035);
            float veins = smoothstep(0.8, 1.0, sin((u * 6.0 - abs(v) * 5.0) * TAU));
            tone = mix(uPColA, uPColB, vRand) * (v > 0.0 ? 1.12 : 0.78) * (0.8 + 0.35 * u)
                 * (1.0 - 0.3 * rib) * (1.0 - 0.13 * veins) * (0.72 + 0.28 * smoothstep(0.0, 0.2, din));
            tone += vec3(0.2, 0.26, 0.12) * exp(-sq((v - 0.22) / 0.12) - sq((u - 0.42) / 0.2)) * luma(uPColB) * 2.0;
            shadowM = smoothstep(0.0, fw * 4.0, leafIn(qs.x * 0.5 + 0.5, qs.y));
        } else {
            float din = petalsIn(vQ), rr = length(vQ);
            body = smoothstep(0.0, fw, din);
            tone = mix(uPColA * (0.72 + 0.38 * smoothstep(0.1, 0.8, rr)) * (0.88 + 0.12 * cos(atan(vQ.y, vQ.x) * 5.0)), uPColB, smoothstep(0.2, 0.13, rr));
            shadowM = smoothstep(0.0, fw * 4.0, petalsIn(qs));
        }
        float k6 = body * vBright;
        o = vec4(tone * uPCol.x * k6, k6 + shadowM * 0.42 * vBright * (1.0 - body));
        return;
    } else if (kind == 4) {
        // out of focus: a flat disc with a slightly brighter rim; the bigger, the fainter
        m = smoothstep(1.0, 0.86, r) * (0.55 + 0.45 * smoothstep(0.55, 0.95, r)) * (0.25 + 0.75 * (1.0 - vRand));
    } else {
        m = exp(-r * r * 5.5) * (1.0 - smoothstep(0.85, 1.0, r));
    }
    float fade = smoothstep(0.0, max(uPCol.y, 1e-3), vAge) * (1.0 - smoothstep(1.0 - max(uPCol.z, 1e-3), 1.0, vAge));
    vec3 col = mix(uPColA, uPColB, sat(vAge * (0.7 + 0.6 * vRand)));
    float k = m * fade * vBright;
    o = vec4(col * uPCol.x * k, sat(k) * uPCol.w);
}
