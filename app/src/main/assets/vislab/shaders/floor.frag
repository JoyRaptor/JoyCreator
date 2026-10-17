// WOVEN FLOOR: a perspective plane of interlaced ribbons rolling toward you, lit from a
// glow on the horizon. Each ribbon is shaded across its width and dips under its
// neighbour, with dark gaps between, so it reads as woven fabric, not tiles. The spectrum
// history makes the weave swell as the sound rolls in.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define SPEEDF   uP[0].x
#define HORIZON  uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define DENSE    uP[1].x
#define WEAVE    uP[1].y
#define SWELL    uP[1].z
#define GLOSS    uP[1].w
#define HUESPIN  uP[2].x
#define GLOWC    uP[2].y
#define FADEF    uP[2].z
#define CMODE    uP[2].w

void main() {
    vec2 p = vPos;
    float depth = HORIZON - p.y;
    if (depth <= 0.0) { o = vec4(0.0); return; }
    float z = 0.3 / depth;                       // distance into the floor
    float x = p.x * z;
    float travel = TIME * SPEEDF + BASSACC * SPEEDF * 0.4;
    float age = clamp((z - 0.3) / 5.0, 0.0, 1.0);
    float sx = clamp(abs(x) * 0.22, 0.0, 1.0);
    float h = hist(sx * 0.8, age) * SWELL;       // the swell: newest sound at the front
    float u = x * DENSE;
    float v = z * DENSE * 0.5 + travel;
    // Ribbons run toward the viewer and ripple sideways with the swell.
    u += sin(v * 1.1 + h * 4.0 + floor(u) * 0.7) * 0.18;
    float lane = floor(u);
    float fx = fract(u);
    float row = floor(v);
    float fy = fract(v);
    // Glossy ribbon: rounded cross-section with a highlight stripe off-centre.
    float ribbon = pow(max(sin(PI * fx), 0.0), 0.6);
    float gloss = exp(-pow((fx - 0.35) * 7.0, 2.0)) * 0.8;
    // Cross-strands every row, alternately over and under the ribbons (the weave).
    float cw = 0.32 * WEAVE;
    float inCross = smoothstep(1.0 - cw - 0.03, 1.0 - cw, fy);
    float crossOnTop = mod(lane + row, 2.0);
    float crossShade = pow(max(sin(PI * clamp((fy - (1.0 - cw)) / max(cw, 1e-3), 0.0, 1.0)), 0.0), 0.6);
    float lit, twoTone;
    if (inCross > 0.5 && crossOnTop > 0.5) {
        lit = crossShade * 0.8;
        twoTone = 0.25;
    } else {
        // Where the ribbon dives under a cross-strand it darkens toward the crossing.
        float dive = inCross > 0.5 ? 0.35 : 1.0 - 0.4 * smoothstep(1.0 - cw - 0.25, 1.0 - cw, fy) * (1.0 - crossOnTop);
        lit = (ribbon + gloss * ribbon) * dive;
        twoTone = hash11(lane * 3.7) * 0.12;
    }
    lit = clamp(lit, 0.0, 1.6);

    float across = clamp(abs(p.x) / (EXT.x * 0.9), 0.0, 1.0);
    float ct = CMODE < 0.5 ? across : fract(v * 0.05);
    vec3 c = palw(ct * 0.5 + PALSHIFT + twoTone + TIME * HUESPIN + h * 0.15);
    // Light falls off with distance from the horizon glow and toward the sides.
    float key = exp(-depth * 3.6) * (1.0 - across * 0.5);
    float wave = 0.3 + 0.7 * smoothstep(0.05, 0.6, h);
    float fade = exp(-z * FADEF * 0.12) * smoothstep(0.0, 0.03, depth);
    vec3 col = c * lit * (0.02 + key * 1.6 * wave) * fade;
    // Sheen: a soft reflection of the horizon glow running down the middle.
    float sheen = exp(-abs(p.x) / (0.12 + depth * 0.5)) * exp(-depth * 2.0) * GLOSS;
    col += palw(0.25 + PALSHIFT) * sheen * lit * 0.6 * (0.5 + LEVEL);
    col += palw(0.25 + PALSHIFT) * GLOWC * exp(-depth * 14.0) * (0.5 + BASS * 0.5);
    o = vec4(col * BRIGHTN, (1.0 - uAdd) * smoothstep(0.0, 0.01, depth)) * uOpacity;
}
