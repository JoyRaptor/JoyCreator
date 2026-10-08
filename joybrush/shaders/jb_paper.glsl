// JB-9.03: canonical JB-9.02 hex sampler, in document coordinates.
precision highp float;
precision highp int; // lowbias32 requires all 32 bits on mobile fragment processors.
uniform sampler2D u_paperSurface;
uniform float u_paperTexelPx;
uniform float u_paperSize;
uniform float u_paperHexTexels;
uniform float u_paperSlopeRange;
uniform bool u_paperRotatable;
uniform float u_paperHeightMean;   // the surface's mean height: the variance-preserving blend's centre

float jb_hash(int i, int j, int k) {
    uint x = uint(i) * 73856093u ^ uint(j) * 19349663u ^ uint(k) * 83492791u;
    x ^= x >> 16u; x *= 0x7feb352du; x ^= x >> 15u;
    x *= 0x846ca68bu; x ^= x >> 16u;
    return float(x >> 8u) / 16777216.0;
}

// Explicit gradients are in paper TEXELS per fragment, already scaled for the requested mip.
// Tile mode computes them from raw coordinates before wrapping. The canonical arithmetic below
// and every existing wrapper's gradient calculation remain identical.
vec4 jb_paperReadGrad(vec2 docPx, int seed, bool slopes, vec2 dx, vec2 dy) {
    vec2 p = docPx / u_paperTexelPx;
    const float SQRT3 = 1.7320508075688772;
    vec2 q = p / u_paperHexTexels;
    vec2 ab = vec2(q.x - q.y / SQRT3, 2.0 * q.y / SQRT3);
    ivec2 base = ivec2(floor(ab));
    vec2 f = fract(ab);
    ivec2 vertices[3];
    vec3 w;
    if (f.x + f.y > 1.0) {
        vertices[0] = base + ivec2(1, 1);
        vertices[1] = base + ivec2(1, 0);
        vertices[2] = base + ivec2(0, 1);
        w = vec3(f.x + f.y - 1.0, 1.0 - f.y, 1.0 - f.x);
    } else {
        vertices[0] = base;
        vertices[1] = base + ivec2(1, 0);
        vertices[2] = base + ivec2(0, 1);
        w = vec3(1.0 - f.x - f.y, f.x, f.y);
    }
    w = w * w * w;
    w /= w.x + w.y + w.z;
    // Variance-preserving (HexTile.contrastKeep): a blend of three patches keeps the contrast of one.
    float keep = inversesqrt(dot(w, w));
    vec4 result = vec4(0.0);
    float spread = 0.0;   // Σ w²·(within-read variance A − B²): what is left inside each read at this mip
    for (int n = 0; n < 3; ++n) {
        int i = vertices[n].x, j = vertices[n].y;
        vec2 centre = u_paperHexTexels * vec2(float(i) + float(j) / 2.0, float(j) * SQRT3 / 2.0);
        vec2 offset = vec2(jb_hash(i, j, 1 + seed), jb_hash(i, j, 2 + seed)) * u_paperSize;
        float theta = u_paperRotatable ? jb_hash(i, j, 3 + seed) * 6.283185307179586 : 0.0;
        float c = cos(theta), s = sin(theta);
        mat2 rotation = mat2(c, s, -s, c);
        vec2 t = rotation * (p - centre) + centre + offset;
        vec4 sampleValue = textureGrad(u_paperSurface, t / u_paperSize,
                                      rotation * dx / u_paperSize, rotation * dy / u_paperSize);
        vec2 slope = vec2(0.0);
        if (slopes) {
            vec2 delta = sampleValue.rg * 255.0 - 127.0;
            // UNORM filtering may move byte 127 by two float ulps. Preserve exact flatness;
            // no byte rounding: every meaningful fractional slope still decodes linearly.
            delta = mix(delta, vec2(0.0), lessThanEqual(abs(delta), vec2(1.0 / 65536.0)));
            slope = delta / 127.0 * u_paperSlopeRange;
            slope = mat2(c, -s, s, c) * slope;
        }
        result += w[n] * vec4(slope, sampleValue.b - u_paperHeightMean, 0.0);
        spread += w[n] * w[n] * max(sampleValue.a - sampleValue.b * sampleValue.b, 0.0);
    }
    result.xy *= keep / u_paperTexelPx;
    result.z = clamp(u_paperHeightMean + result.z * keep, 0.0, 1.0);
    // A = E[h²] of the blend, consistent with the blend's own mean: B² plus the reads' leftover variance, weighted as the
    // blend weights them (Σw²σ²/Σw²). So A − B² stays a valid local variance at every mip (the wet/dry engines prefilter on it).
    result.w = result.z * result.z + spread * keep * keep;
    return result;
}

vec4 jb_paperRead(vec2 docPx, int seed, bool slopes, float derivativeScale) {
    vec2 p = docPx / u_paperTexelPx;
    // Different hexes rotate independently: differentiate before selecting a vertex.
    vec2 dx = dFdx(p) * derivativeScale, dy = dFdy(p) * derivativeScale;
    return jb_paperReadGrad(docPx, seed, slopes, dx, dy);
}
vec4 jb_paperRead(vec2 docPx, int seed, bool slopes) { return jb_paperRead(docPx, seed, slopes, 1.0); }
vec4 jb_paperSurface(vec2 docPx, int seed) { return jb_paperRead(docPx, seed, true); }
vec4 jb_paperSurface(vec2 docPx) { return jb_paperSurface(docPx, 0); }
float jb_paperHeight(vec2 docPx) { return jb_paperRead(docPx, 0, false).z; }
// Same global hex arrangement; only the wet surroundings' mip footprint changes.
float jb_paperCoarseHeight(vec2 docPx) { return jb_paperRead(docPx, 0, false, 8.0).z; }

// ── The FLUID map (2026-10-06), for the wet and impasto engines. Same hex arrangement idea, its own texture and scale.
// Returns vec4(absorbency 0..1, fibre direction (cos2θ, sin2θ)·coherence in the DOCUMENT frame (signed), pore capacity
// 0..1). A rotated hex turns its fibres by 2θ (double angle) so a direction means the same everywhere on the page.
// Twin of HexTile.sampleFluid. u_paperFluidTexelPx <= 0 means "this paper has no fluid map": a neutral answer.
uniform sampler2D u_paperFluid;
uniform float u_paperFluidTexelPx;
uniform float u_paperFluidSize;
uniform float u_paperFluidHexTexels;

vec4 jb_paperFluidRead(vec2 docPx, float derivativeScale) {
    if (u_paperFluidTexelPx <= 0.0) return vec4(0.5, 0.0, 0.0, 0.5);
    vec2 p = docPx / u_paperFluidTexelPx;
    vec2 dx = dFdx(p) * derivativeScale, dy = dFdy(p) * derivativeScale;
    const float SQRT3 = 1.7320508075688772;
    vec2 q = p / u_paperFluidHexTexels;
    vec2 ab = vec2(q.x - q.y / SQRT3, 2.0 * q.y / SQRT3);
    ivec2 base = ivec2(floor(ab));
    vec2 f = fract(ab);
    ivec2 vertices[3];
    vec3 w;
    if (f.x + f.y > 1.0) {
        vertices[0] = base + ivec2(1, 1); vertices[1] = base + ivec2(1, 0); vertices[2] = base + ivec2(0, 1);
        w = vec3(f.x + f.y - 1.0, 1.0 - f.y, 1.0 - f.x);
    } else {
        vertices[0] = base; vertices[1] = base + ivec2(1, 0); vertices[2] = base + ivec2(0, 1);
        w = vec3(1.0 - f.x - f.y, f.x, f.y);
    }
    w = w * w * w;
    w /= w.x + w.y + w.z;
    float keep = inversesqrt(dot(w, w));
    vec4 result = vec4(0.0);
    for (int n = 0; n < 3; ++n) {
        int i = vertices[n].x, j = vertices[n].y;
        vec2 centre = u_paperFluidHexTexels * vec2(float(i) + float(j) / 2.0, float(j) * SQRT3 / 2.0);
        vec2 offset = vec2(jb_hash(i, j, 1), jb_hash(i, j, 2)) * u_paperFluidSize;
        float theta = u_paperRotatable ? jb_hash(i, j, 3) * 6.283185307179586 : 0.0;
        float c = cos(theta), s = sin(theta);
        mat2 rotation = mat2(c, s, -s, c);
        vec2 t = rotation * (p - centre) + centre + offset;
        vec4 v = textureGrad(u_paperFluid, t / u_paperFluidSize,
                             rotation * dx / u_paperFluidSize, rotation * dy / u_paperFluidSize);
        vec2 dir = v.gb * 2.0 - 1.0;
        float c2 = cos(-2.0 * theta), s2 = sin(-2.0 * theta);
        dir = vec2(c2 * dir.x - s2 * dir.y, s2 * dir.x + c2 * dir.y);
        result += w[n] * vec4(v.r - 0.5, dir, v.a - 0.5);
    }
    return vec4(clamp(0.5 + result.x * keep, 0.0, 1.0), result.yz, clamp(0.5 + result.w * keep, 0.0, 1.0));
}

vec4 jb_paperFluid(vec2 docPx) { return jb_paperFluidRead(docPx, 1.0); }
// The surroundings a wet front feels: the same arrangement, a coarser mip.
vec4 jb_paperFluidCoarse(vec2 docPx) { return jb_paperFluidRead(docPx, 8.0); }
