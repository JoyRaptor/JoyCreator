// One particle's picture (premultiplied; uPCol.a decides how much it hides versus adds).
in vec2 vQ;
in float vAge;
in float vBright;
in float vRand;
out vec4 o;
uniform vec4  uPKind;    // shape, twinkle, glint, seed
uniform vec3  uPColA;    // when born
uniform vec3  uPColB;    // when old
uniform vec4  uPCol;     // gain, fade-in share of life, fade-out share of life, 0 = pure light .. 1 = paint
void main() {
    float r = length(vQ);
    int kind = int(uPKind.x + 0.5);
    float m;
    if (kind == 1) {
        // four-point star: a hot centre and two thin crossing rays
        vec2 q = abs(vQ);
        float rays = max(exp(-q.y * 26.0) * (1.0 - q.x), exp(-q.x * 26.0) * (1.0 - q.y));
        m = sat(rays) * 0.8 + exp(-r * r * 22.0) * 1.6;
        m *= 1.0 - smoothstep(0.85, 1.0, max(q.x, q.y));
    } else if (kind == 2) {
        // a drop: dark where it faces the lamp, a bright crescent on the far side, one hard glint
        float body = smoothstep(1.0, 0.86, r);
        float far = smoothstep(0.1, 0.95, dot(vQ, vec2(0.55, -0.83))) * smoothstep(0.35, 0.95, r);
        float glintDot = exp(-dot(vQ - vec2(-0.36, 0.42), vQ - vec2(-0.36, 0.42)) * 40.0);
        m = body * (0.22 + 1.1 * far) + glintDot * 2.5 * body;
    } else if (kind == 3) {
        m = exp(-vQ.y * vQ.y * 5.0) * (1.0 - smoothstep(0.6, 1.0, abs(vQ.x))) * exp(-r * r * 1.5);
    } else {
        m = exp(-r * r * 4.5) * (1.0 - smoothstep(0.85, 1.0, r));
    }
    float fade = smoothstep(0.0, max(uPCol.y, 1e-3), vAge) * (1.0 - smoothstep(1.0 - max(uPCol.z, 1e-3), 1.0, vAge));
    vec3 col = mix(uPColA, uPColB, sat(vAge * (0.7 + 0.6 * vRand)));
    float k = m * fade * vBright;
    o = vec4(col * uPCol.x * k, sat(k) * uPCol.w);
}
