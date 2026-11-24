// Append to passes declaring these helpers. Matches VelocityPack: 12-bit signed X and Y at
// 1/16 px, RGB byte order. Flow buffers use nearest filtering and clamp-to-edge.
vec4 packVelocity(vec2 velocity, float alpha) {
    vec2 q = floor(clamp(velocity * 16.0, -2048.0, 2047.0) + 0.5);
    q = mod(q, 4096.0);
    float red = floor(q.x / 16.0);
    float green = mod(q.x, 16.0) * 16.0 + floor(q.y / 256.0);
    return vec4(vec3(red, green, mod(q.y, 256.0)) / 255.0, alpha);
}

vec2 unpackVelocity(vec4 encoded) {
    vec3 rgb = floor(encoded.rgb * 255.0 + 0.5);
    vec2 q = vec2(rgb.r * 16.0 + floor(rgb.g / 16.0), mod(rgb.g, 16.0) * 256.0 + rgb.b);
    return (q - step(2048.0, q) * 4096.0) / 16.0;
}

// Decode each corner before interpolation so carries between packed bytes don't affect the flow.
vec2 sampleVelocity(sampler2D field, vec2 size, vec2 pixel) {
    vec2 cell = floor(pixel);
    vec2 t = fract(pixel);
    vec2 uv = (cell + 0.5) / size;
    vec2 stepUv = 1.0 / size;
    vec2 a = unpackVelocity(texture2D(field, uv));
    vec2 b = unpackVelocity(texture2D(field, uv + vec2(stepUv.x, 0.0)));
    vec2 c = unpackVelocity(texture2D(field, uv + vec2(0.0, stepUv.y)));
    vec2 d = unpackVelocity(texture2D(field, uv + stepUv));
    return mix(mix(a, b, t.x), mix(c, d, t.x), t.y);
}
