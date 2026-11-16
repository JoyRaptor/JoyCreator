// Peel and Carry: the leaving room is peeled off by its corner (Joybot's fists ride the lip of the
// curl), the zooming star field is behind it, and the arriving room bursts open out of the middle.
uniform sampler2D uStars;  // the star field, drawn by its own pass
void main() {
    vec2 v = vec2(vUv.x, 1.0 - vUv.y);
    float pp = clamp(uP, 0.0, 1.0);
    float a = smoothstep(0.0, 0.3, pp), c = smoothstep(0.8, 1.0, pp);
    float f = (v.x * 0.55 + v.y) / 1.55;
    float th = 1.02 - a * 1.2, w = 0.09;
    vec3 stars = texture(uStars, vUv).rgb;
    vec3 col;
    if (f < th) {
        col = roomA(v);
    } else if (f < th + w && a < 1.0) {
        float k = (f - th) / w;
        vec2 m = v - vec2(0.55, 1.0) / 1.55 * (2.0 * (f - th)) * 1.2;
        col = mix(vec3(0.93, 0.91, 0.86), roomA(clamp(m, 0.0, 1.0)), 0.18) * (1.0 - 0.5 * k * k);
    } else {
        col = stars * (1.0 - 0.5 * exp(-(f - th - w) / 0.05) * (1.0 - a));
    }
    vec2 mid = vec2(0.5, 0.42);
    vec2 q = (v - mid) * vec2(1.0, uAspect);
    float R = c * (0.6 + 0.5 * uAspect), d = length(q);
    if (d < R) {
        float zz = mix(0.45, 1.0, c * c * (3.0 - 2.0 * c));
        col = roomB((v - mid) / zz + mid);
    }
    col += mix(uB1, uB2, v.y) * exp(-sq((d - R) / 0.03)) * step(0.001, c) * (1.0 - c) * 2.0;
    o = vec4(col, 1.0);
}
