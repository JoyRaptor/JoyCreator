// The Box Kick: the app is a box and every room is a face. uPre is the wind-up squash before the
// hit and the bounce as the new face lands.
uniform float uPre;
void main() {
    vec2 v = vec2(vUv.x, 1.0 - vUv.y);
    float pp = clamp(uP, 0.0, 1.0);
    float zoom = 1.0 - 0.17 * sin(3.14159 * pp) - uPre * 0.07;
    vec2 q = (v - 0.5) / zoom + 0.5;
    q.x = (q.x - 0.5) / (1.0 - uPre * 0.12) + 0.5;
    float s = pp, K = 0.62;
    vec3 col = mix(vec3(0.02), uB1 * 0.14, smoothstep(0.3, 1.0, v.y));
    if (q.x >= 0.0 && q.x <= 1.0) {
        if (q.x < s) {
            float u = q.x / max(s, 0.001);
            float sc = mix(1.0 - 0.5 * (1.0 - s) * K, 1.0, u);
            float y = (q.y - 0.5) / sc + 0.5;
            if (y >= 0.0 && y <= 1.0) col = roomB(vec2(u, y)) * mix(0.5, 1.0, s);
        } else {
            float u = (q.x - s) / max(1.0 - s, 0.001);
            float sc = mix(1.0, 1.0 - 0.5 * s * K, u);
            float y = (q.y - 0.5) / sc + 0.5;
            if (y >= 0.0 && y <= 1.0) col = roomA(vec2(u, y)) * mix(1.0, 0.4, s);
        }
        col += mix(uB1, uB2, v.y) * exp(-sq((q.x - s) / 0.006)) * 0.6 * sin(3.14159 * pp);
    }
    o = vec4(col, 1.0);
}
