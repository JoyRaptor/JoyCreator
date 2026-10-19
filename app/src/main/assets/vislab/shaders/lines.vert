// LINES: glowing strokes built from instanced segments (one quad per segment).
// Each row/ring is drawn as a fill pass then a stroke pass, back to front, so ridgelines
// hide the rows behind them (the Joy Division look) while strokes still add light.
out float vAcross;   // distance across the stroke, scene units
out float vT;        // 0..1 along the curve
out float vRow;      // 0..1 back to front
out float vVal;      // local amplitude, 0..1
out float vKind;     // 0 stroke, 1 fill

#define MODE     uP[0].x   // 0 scope, 1 polar rings, 2 stereo XY, 3 ridgeline, 4 spectrum curve, 5 spiral
#define ROWS     uP[0].y
#define CEN      uP[0].zw
#define SIZE     uP[1].x
#define AMP      uP[1].y
#define THICK    uP[1].z
#define GLOW     uP[1].w
#define ROT      uP[2].x
#define SPACING  uP[2].y
#define CMODE    uP[2].z
#define PALSHIFT uP[2].w
#define BRIGHTN  uP[3].x
#define FILL     uP[3].y
#define PERSP    uP[3].z
#define TAPER    uP[3].w
#define GAIN     uP[4].x
#define FLO      uP[4].y
#define FHI      uP[4].z
#define SPREAD   uP[4].w   // each ring reads the wave a little later
#define HUESPIN  uP[5].x
#define SPIN     uP[5].y
#define MIRROR   uP[5].z

const float SEGS = 256.0;

float specMirror(float t) {
    float f = MIRROR > 0.5 ? abs(t * 2.0 - 1.0) : t;
    return mix(FLO, FHI, f);
}

// Position of the curve at t on row r (0 = back), and its local value.
vec2 curve(float t, float r, float nRows, out float val) {
    float rowT = nRows > 1.0 ? r / (nRows - 1.0) : 1.0;
    float m = MODE;
    float spin = (ROT + SPIN * TIME) * TAU;
    if (m < 0.5) {
        float w = waveL(fract(t + r * SPREAD)) * GAIN;
        float tap = pow(sin(PI * t), TAPER);
        val = abs(w);
        float x = (t - 0.5) * SIZE * 2.0 * EXT.x;
        float y = w * AMP * tap + (r - (nRows - 1.0) * 0.5) * SPACING;
        return CEN + rot2(spin) * vec2(x, y);
    }
    if (m < 1.5) {
        // Mirror the wave around the ring so the loop closes without a seam.
        float w = waveL(fract(abs(t * 2.0 - 1.0) * 0.5 + r * SPREAD)) * GAIN;
        val = abs(w);
        float a = t * TAU + spin * (1.0 + r * 0.15);
        float R = SIZE + r * SPACING;
        float rad = R + w * AMP * (1.0 + r * 0.2);
        return CEN + rad * vec2(sin(a), cos(a));
    }
    if (m < 2.5) {
        float tt = fract(t + r * SPREAD);
        vec2 xy = vec2(waveL(tt), waveR(tt)) * GAIN;
        val = length(xy);
        xy = rot2(spin + r * 0.3) * xy;
        return CEN + xy * vec2(SIZE, AMP) * (1.0 + r * SPACING);
    }
    if (m < 3.5) {
        float age = 1.0 - rowT;
        float s = hist(specMirror(t), age) * GAIN;
        float win = exp(-pow((t - 0.5) * 3.2, 2.0));
        float jag = 0.6 + 0.4 * hash12(vec2(floor(t * SEGS), r));
        val = s;
        float scale = 1.0 - PERSP * age * 0.5;
        float x = (t - 0.5) * SIZE * 2.0 * EXT.x * scale;
        float base = ((nRows - 1.0) * 0.5 - r) * SPACING;
        return CEN + vec2(x, base + s * win * jag * AMP * scale);
    }
    if (m < 4.5) {
        float s = spec(specMirror(t)) * GAIN;
        val = s;
        float x = (t - 0.5) * SIZE * 2.0 * EXT.x;
        return CEN + rot2(spin) * vec2(x, s * AMP + (r - (nRows - 1.0) * 0.5) * SPACING);
    }
    // Spiral: the wave wound outward.
    float w = waveL(t) * GAIN;
    val = abs(w);
    float turns = 3.0 + r;
    float a = t * TAU * turns + spin;
    float rad = SIZE * (0.15 + 0.85 * t) + w * AMP;
    return CEN + rad * vec2(sin(a), cos(a));
}

void main() {
    float nRows = max(1.0, floor(ROWS));
    float id = float(gl_InstanceID);
    float r = floor(id / (SEGS * 2.0));
    float k = id - r * SEGS * 2.0;
    float kind = k < SEGS ? 1.0 : 0.0;
    float seg = mod(k, SEGS);
    int v = gl_VertexID % 6;
    vec2 c = (v == 0) ? vec2(0, 0) : (v == 1) ? vec2(1, 0) : (v == 2) ? vec2(0, 1)
           : (v == 3) ? vec2(0, 1) : (v == 4) ? vec2(1, 0) : vec2(1, 1);

    bool wantFill = MODE > 2.5 && MODE < 3.5 && FILL > 0.0;
    if (r >= nRows || (kind > 0.5 && !wantFill)) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }

    float t0 = seg / SEGS, t1 = (seg + 1.0) / SEGS;
    float v0, v1;
    vec2 A = curve(t0, r, nRows, v0);
    vec2 B = curve(t1, r, nRows, v1);
    vT = mix(t0, t1, c.x);
    vRow = nRows > 1.0 ? r / (nRows - 1.0) : 1.0;
    vVal = mix(v0, v1, c.x);
    vKind = kind;
    vec2 pos;
    if (kind > 0.5) {
        // Fill: from the stroke straight down past the bottom of the frame.
        vec2 top = mix(A, B, c.x);
        float bottom = -EXT.y - 0.2;
        pos = vec2(top.x, mix(top.y, bottom, c.y));
        vAcross = 0.0;
    } else {
        vec2 d = B - A;
        float len = max(length(d), 1e-6);
        vec2 dir = d / len;
        vec2 nrm = vec2(-dir.y, dir.x);
        float hw = THICK * 0.5 + GLOW * 0.02 * 4.0 + uPx * 2.0;
        float across = c.y * 2.0 - 1.0;
        pos = mix(A, B, c.x) + nrm * across * hw + dir * (c.x * 2.0 - 1.0) * uPx * 0.5;
        vAcross = across * hw;
    }
    gl_Position = vec4(placeOut(pos) / EXT, 0.0, 1.0);
}
