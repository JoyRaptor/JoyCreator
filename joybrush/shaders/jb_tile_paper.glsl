// Include after jb_paper.glsl. Tile-only periodic adapter; canonical hex reads remain unchanged.
// Four canonical surface reads = 12 texture fetches vs 3. Device performance remains unmeasured.
// No fluid/media tiling: those tools remain refused while Tile is armed.
uniform vec4 u_tilePaperRect; // document px: xy origin, zw periods. Either zw <= 0 disables Tile.

// The Tile blend every periodic read shares: [q] is the point's copy inside the board, and the result is the smoothstep
// weights of its four period copies, in the order q, q-(w,0), q-(0,h), q-(w,h).
vec4 jb_tilePeriodicWeights(vec2 docPx, out vec2 q) {
    vec2 period = u_tilePaperRect.zw;
    vec2 relative = mod(docPx - u_tilePaperRect.xy, period);
    q = u_tilePaperRect.xy + relative;
    vec2 s = smoothstep(vec2(0.0), vec2(1.0), relative / period);
    return vec4((1.0-s.x)*(1.0-s.y), s.x*(1.0-s.y), (1.0-s.x)*s.y, s.x*s.y);
}

vec4 jb_tilePaperRead(vec2 docPx, int seed, bool slopes, float derivativeScale) {
    if (u_tilePaperRect.z <= 0.0 || u_tilePaperRect.w <= 0.0)
        return jb_paperRead(docPx, seed, slopes, derivativeScale);
    // Differentiate RAW texel coordinates before modulo; wrapping derivatives select false mips.
    vec2 raw = docPx / u_paperTexelPx;
    vec2 dx = dFdx(raw) * derivativeScale, dy = dFdy(raw) * derivativeScale;
    vec2 period = u_tilePaperRect.zw;
    vec2 q;
    vec4 weights = jb_tilePeriodicWeights(docPx, q);
    vec4 a = jb_paperReadGrad(q, seed, slopes, dx, dy);
    vec4 b = jb_paperReadGrad(q - vec2(period.x, 0.0), seed, slopes, dx, dy);
    vec4 c = jb_paperReadGrad(q - vec2(0.0, period.y), seed, slopes, dx, dy);
    vec4 d = jb_paperReadGrad(q - period, seed, slopes, dx, dy);
    float keep = inversesqrt(dot(weights, weights));
    vec2 slope = (weights.x*a.xy + weights.y*b.xy + weights.z*c.xy + weights.w*d.xy) * keep;
    float height = clamp(u_paperHeightMean +
        (weights.x*(a.z-u_paperHeightMean) + weights.y*(b.z-u_paperHeightMean) +
         weights.z*(c.z-u_paperHeightMean) + weights.w*(d.z-u_paperHeightMean))*keep, 0.0, 1.0);
    vec4 variance = max(vec4(a.w-a.z*a.z, b.w-b.z*b.z, c.w-c.z*c.z, d.w-d.z*d.z), vec4(0.0));
    float moment = height*height + dot(weights*weights, variance)*keep*keep;
    return vec4(slope, height, moment);
}

// A plain one-channel texture made periodic on a Tile board with the SAME blend as the paper: the four copies' deviations
// from [pivot], weighted and renormalised so the texture keeps its contrast across the board. Gradients come from the RAW
// coordinate, as for the paper, so the wrap never selects a false mip. Call only while Tile is armed (a uniform branch).
float jb_tileTextureR(sampler2D tex, vec2 docPx, vec2 offset, float world, float pivot) {
    vec2 q;
    vec4 w = jb_tilePeriodicWeights(docPx, q);
    vec2 period = u_tilePaperRect.zw;
    vec2 raw = (docPx + offset) / world;
    vec2 dx = dFdx(raw), dy = dFdy(raw);
    float a = textureGrad(tex, (q + offset) / world, dx, dy).r;
    float b = textureGrad(tex, (q - vec2(period.x, 0.0) + offset) / world, dx, dy).r;
    float c = textureGrad(tex, (q - vec2(0.0, period.y) + offset) / world, dx, dy).r;
    float d = textureGrad(tex, (q - period + offset) / world, dx, dy).r;
    float keep = inversesqrt(dot(w, w));
    return clamp(pivot + (w.x*(a-pivot) + w.y*(b-pivot) + w.z*(c-pivot) + w.w*(d-pivot))*keep, 0.0, 1.0);
}

vec4 jb_tilePaperRead(vec2 docPx, int seed, bool slopes) { return jb_tilePaperRead(docPx, seed, slopes, 1.0); }
vec4 jb_tilePaperSurface(vec2 docPx, int seed) { return jb_tilePaperRead(docPx, seed, true); }
vec4 jb_tilePaperSurface(vec2 docPx) { return jb_tilePaperSurface(docPx, 0); }
float jb_tilePaperHeight(vec2 docPx) { return jb_tilePaperRead(docPx, 0, false).z; }
float jb_tilePaperCoarseHeight(vec2 docPx) { return jb_tilePaperRead(docPx, 0, false, 8.0).z; }
