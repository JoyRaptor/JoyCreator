// Include after jb_paper.glsl. Tile-only periodic adapter; canonical hex reads remain unchanged.
// Four canonical surface reads = 12 texture fetches vs 3. Device performance remains unmeasured.
// No fluid/media tiling: those tools remain refused while Tile is armed.
uniform vec4 u_tilePaperRect; // document px: xy origin, zw periods. Either zw <= 0 disables Tile.

vec4 jb_tilePaperRead(vec2 docPx, int seed, bool slopes, float derivativeScale) {
    if (u_tilePaperRect.z <= 0.0 || u_tilePaperRect.w <= 0.0)
        return jb_paperRead(docPx, seed, slopes, derivativeScale);
    // Differentiate RAW texel coordinates before modulo; wrapping derivatives select false mips.
    vec2 raw = docPx / u_paperTexelPx;
    vec2 dx = dFdx(raw) * derivativeScale, dy = dFdy(raw) * derivativeScale;
    vec2 period = u_tilePaperRect.zw;
    vec2 relative = mod(docPx - u_tilePaperRect.xy, period);
    vec2 q = u_tilePaperRect.xy + relative;
    vec2 s = smoothstep(vec2(0.0), vec2(1.0), relative / period);
    vec4 weights = vec4((1.0-s.x)*(1.0-s.y), s.x*(1.0-s.y), (1.0-s.x)*s.y, s.x*s.y);
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

vec4 jb_tilePaperRead(vec2 docPx, int seed, bool slopes) { return jb_tilePaperRead(docPx, seed, slopes, 1.0); }
vec4 jb_tilePaperSurface(vec2 docPx, int seed) { return jb_tilePaperRead(docPx, seed, true); }
vec4 jb_tilePaperSurface(vec2 docPx) { return jb_tilePaperSurface(docPx, 0); }
float jb_tilePaperHeight(vec2 docPx) { return jb_tilePaperRead(docPx, 0, false).z; }
float jb_tilePaperCoarseHeight(vec2 docPx) { return jb_tilePaperRead(docPx, 0, false, 8.0).z; }
