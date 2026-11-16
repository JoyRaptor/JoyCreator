// A zooming star field in the two rooms' colours: stars start in the leaving room's gradient and
// give way to the arriving room's (uP). This is the stand-in for VisLab's Lyric Flight field, which
// the owner picked for this (16 Nov); it is used only where that engine cannot be started.
void main() {
    vec2 v = vec2(vUv.x, 1.0 - vUv.y);
    float k = smoothstep(0.34, 0.76, clamp(uP, 0.0, 1.0));
    vec2 q = (v - vec2(0.5, 0.47)) * vec2(1.0, uAspect);
    float r = length(q) + 0.001, an0 = atan(q.y, q.x) / 6.28318 + 0.5;
    vec3 c = mix(uA1, uB1, k) * 0.03;
    for (int i = 0; i < 4; i++) {
        float N = i == 0 ? 37.0 : (i == 1 ? 71.0 : (i == 2 ? 127.0 : 211.0));
        float an = an0 + 0.012 * log(r) + float(i) * 0.37;
        float id = floor(an * N), h = hash(id + N * 7.0), h2 = hash(id * 1.7 + N), h3 = hash(id * 2.3 + N * 3.0);
        float w = abs(fract(an * N) - 0.5);
        float z = log(r) * (0.55 + 0.25 * h) - uT * (0.9 + 0.9 * h2) + h * 9.0;
        float f = fract(z);
        float on = step(0.4, hash(id + floor(z) * 13.0 + N));
        float head = smoothstep(0.8, 0.975, f) * (1.0 - smoothstep(0.975, 1.0, f));
        float tail = f * f * f * 0.55 * (1.0 - step(0.975, f));
        float wd = min(0.5, (0.0028 * (1.0 + 2.2 * r) * (0.6 + h3) * N) / (6.283 * r));
        float line = 1.0 - smoothstep(wd * 0.3, wd, w);
        vec3 col = mix(mix(mix(uA1, uA2, h2), mix(uB1, uB2, h2), step(hash(id * 3.1 + N + 5.0), k)), vec3(1.0), step(0.86, h3) * 0.7);
        c += (col * (tail * 1.5 + head * 2.2) + vec3(1.0) * head * 0.25) * line * on * smoothstep(0.02, 0.3, r) * (0.5 + 1.5 * r);
    }
    c += c * c * 0.2;
    c *= 1.0 - 0.45 * smoothstep(0.7, 1.35, r);
    o = vec4(c, 1.0);
}
