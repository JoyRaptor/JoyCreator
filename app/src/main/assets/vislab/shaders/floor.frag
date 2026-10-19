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
#define KIND     uP[3].x   // 0 woven ribbons, 1 Launchpad pads
#define GAP      uP[3].y   // space between pads
#define RIPPLEK  uP[3].z   // kick ripples across the pads
#define CHASE    uP[3].w   // beat-chase sweep
#define OCEAN    uP[4].x   // the floor rolls like a swell
#define LEDS     uP[4].y   // LED brightness steps (0 = smooth)
// A tall (portrait) frame looks further down the floor: scale it so the nearest tiles do not
// stretch into long slabs. Landscape keeps the original 0.3.
#define FLOORK   (0.3 * mix(1.0, max(EXT.y, 1.0), 0.6))

// LAUNCHPAD / DISCO OCEAN: a grid of glowing pads. Each column listens to its own frequency
// (bass in the middle, highs out to the sides) and each row is that sound a moment ago, so
// the music rolls toward you across the pads. Kicks send rings of light out from the front,
// a sweep chases across on the bar, snares sparkle random pads, and the whole grid heaves.
vec3 pads(vec2 p, float depth, float travel) {
    float z0 = FLOORK / depth;
    float x0 = p.x * z0;
    // The swell lifts and drops the grid on screen (bigger near the viewer), like a sea surface.
    float swell = OCEAN * (0.5 * sin(x0 * 1.3 + z0 * 0.9 - TIME * 1.5) + 0.35 * sin(x0 * 0.7 - z0 * 1.6 + TIME * 1.1));
    float d2 = max(depth * (1.0 + swell * 0.18), 1e-3);
    float z = FLOORK / d2;
    float x = p.x * z;
    float z2 = z;
    float crest = OCEAN * cos(x * 1.3 + z * 0.9 - TIME * 1.5);
    vec2 g = vec2(x * DENSE, z2 * DENSE * 0.5 + travel);
    vec2 cell = floor(g);
    vec2 f = fract(g);
    // Rounded square pad, antialiased in cell space.
    vec2 q = abs(f - 0.5);
    float half_ = 0.5 - GAP * 0.5;
    float rr = 0.12;
    float d = length(max(q - (half_ - rr), 0.0)) - rr;
    float aa = max(fwidth(g.x), fwidth(g.y)) * 0.8 + 1e-4;
    float inPad = smoothstep(aa, -aa, d);
    // What this pad hears.
    float colN = abs(cell.x + 0.5) / max(DENSE * 2.2, 1.0);
    float fq = clamp(colN, 0.0, 1.0) * 0.85;
    float age = clamp((z2 - 0.3) / 5.0, 0.0, 1.0);
    // Pads stay dark until their part of the sound is really there.
    float e = smoothstep(0.3, 0.95, hist(fq, age) * SWELL * 0.75);
    if (LEDS >= 2.0) e = floor(e * LEDS + 0.5) / LEDS;
    // Kick rings, in pad units from the front centre.
    float ring = 0.0;
    vec2 pc = vec2(cell.x + 0.5, (cell.y - floor(travel)) - 1.0);
    float pd = length(pc);
    for (int i = 0; i < 4; i++) {
        float a = shockAge(i);
        if (a > 2.0) continue;
        ring += exp(-abs(pd - a * 14.0) * 1.2) * exp(-a * 1.8);
    }
    ring *= RIPPLEK;
    float sweep = CHASE * smoothstep(0.9, 1.0, fract((cell.x * 0.5 + cell.y) / 8.0 - BARPH));
    float sparkle = step(1.0 - SNARE * 0.12, hash12(cell + floor(TIME * 14.0))) * SNARE;
    float lit = clamp(e + ring + sweep + sparkle, 0.0, 2.5);
    float ct = CMODE < 0.5 ? clamp(colN, 0.0, 1.0) : fract(cell.y * 0.07);
    vec3 c = palw(ct * 0.5 + PALSHIFT + TIME * HUESPIN);
    // LED: a hot centre, a glassy dark pad when off, a faint rim always.
    float core = 1.0 - 0.55 * length(f - 0.5) * 2.0;
    vec3 led = c * lit * (0.6 + 0.6 * core);
    vec3 glass = c * 0.012 + vec3(0.008);
    float rim = smoothstep(aa * 3.0, 0.0, abs(d)) * 0.15;
    vec3 col = (glass + led) * inPad + c * rim * (0.3 + lit);
    // Light spills between pads: the floor glows where the pads are lit.
    col += c * lit * 0.06 * (1.0 - inPad);
    col *= 0.8 + 0.4 * max(crest, 0.0);
    float fade = exp(-z * FADEF * 0.1) * smoothstep(0.0, 0.03, depth);
    return col * fade;
}

void main() {
    vec2 p = vPos;
    float depth = HORIZON - p.y;
    if (depth <= 0.0) { o = vec4(0.0); return; }
    float travel = TIME * SPEEDF + BASSACC * SPEEDF * 0.4;
    if (KIND > 0.5) {
        vec3 c = pads(p, depth, travel);
        c += palw(0.25 + PALSHIFT) * GLOWC * exp(-depth * 14.0) * (0.5 + BASS * 0.5);
        o = vec4(c * BRIGHTN, (1.0 - uAdd) * smoothstep(0.0, 0.01, depth)) * uOpacity;
        return;
    }
    float z = FLOORK / depth;                    // distance into the floor
    float x = p.x * z;
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
