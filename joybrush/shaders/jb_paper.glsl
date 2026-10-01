// JB-9.03: canonical JB-9.02 hex sampler, in document coordinates.
precision highp int; // lowbias32 requires all 32 bits on mobile fragment processors.
uniform sampler2D u_paperSurface;
uniform float u_paperTexelPx;
uniform float u_paperSize;
uniform float u_paperHexTexels;
uniform float u_paperSlopeRange;
uniform bool u_paperRotatable;

float jb_hash(int i, int j, int k) {
    uint x = uint(i) * 73856093u ^ uint(j) * 19349663u ^ uint(k) * 83492791u;
    x ^= x >> 16u; x *= 0x7feb352du; x ^= x >> 15u;
    x *= 0x846ca68bu; x ^= x >> 16u;
    return float(x >> 8u) / 16777216.0;
}

vec4 jb_paperSurface(vec2 docPx) {
    vec2 p = docPx / u_paperTexelPx;
    // Different hexes rotate independently: differentiate before selecting a vertex.
    vec2 dx = dFdx(p), dy = dFdy(p);
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
    vec4 result = vec4(0.0);
    for (int n = 0; n < 3; ++n) {
        int i = vertices[n].x, j = vertices[n].y;
        vec2 centre = u_paperHexTexels * vec2(float(i) + float(j) / 2.0, float(j) * SQRT3 / 2.0);
        vec2 offset = vec2(jb_hash(i, j, 1), jb_hash(i, j, 2)) * u_paperSize;
        float theta = u_paperRotatable ? jb_hash(i, j, 3) * 6.283185307179586 : 0.0;
        float c = cos(theta), s = sin(theta);
        mat2 rotation = mat2(c, s, -s, c);
        vec2 t = rotation * (p - centre) + centre + offset;
        vec4 sampleValue = textureGrad(u_paperSurface, t / u_paperSize,
                                      rotation * dx / u_paperSize, rotation * dy / u_paperSize);
        vec2 slope = (sampleValue.rg * 2.0 - 1.0) * u_paperSlopeRange;
        slope = mat2(c, -s, s, c) * slope;
        result += w[n] * vec4(slope, sampleValue.ba);
    }
    result.xy /= u_paperTexelPx;
    return result;
}
