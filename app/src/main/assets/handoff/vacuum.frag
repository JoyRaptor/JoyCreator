// The Vacuum Sweep: a radial wipe whose edge Joybot hauls round the screen. The leaving room
// streams toward his fists as the edge passes, and what is left of it twists and shrinks to the
// middle, where it becomes the ball he carries off.
uniform float uSweep;      // the wipe's angle, radians from straight up, clockwise
uniform float uColl;       // 0..1 how far what is left has collapsed to the middle
void main() {
    vec2 v = vec2(vUv.x, 1.0 - vUv.y);
    vec2 c0 = vec2(0.5, 0.469);
    vec2 q = (v - c0) * vec2(1.0, uAspect);
    float r = length(q);
    float th = mod(atan(q.y, q.x) + 1.5708 + 12.56637, 6.28318);
    th += (noise(r * 14.0 + 3.0) - 0.5) * 0.3 + (noise(r * 37.0) - 0.5) * 0.12;
    float w = 1.0 - smoothstep(uSweep - 0.1, uSweep + 0.5, th);
    if (uSweep > 6.8) w = 1.0;
    float sc = 1.0 - uColl * 0.93;
    float tw = uColl * 4.5 * exp(-r / max(sc, 0.07));
    float cs = cos(tw), sn = sin(tw);
    vec2 qa = vec2(q.x * cs + q.y * sn, -q.x * sn + q.y * cs) / sc;
    vec2 ua = c0 + qa / vec2(1.0, uAspect);
    ua = uGrab + (ua - uGrab) * (1.0 + 3.0 * w * w);
    float rim = r / (mix(1.25, 0.6, uColl) * sc);
    float inside = step(0.0, ua.x) * step(ua.x, 1.0) * step(0.0, ua.y) * step(ua.y, 1.0) * (1.0 - smoothstep(0.97, 1.0, rim));
    float eaten = max(smoothstep(0.55, 0.92, w), 1.0 - inside);
    vec3 col = mix(roomA(clamp(ua, 0.0, 1.0)), roomB(v), eaten);
    col += mix(uA1, uA2, v.y) * (smoothstep(0.0, 0.7, w) * (1.0 - eaten) * 0.45 + exp(-sq((rim - 1.0) / 0.04)) * step(0.02, uColl) * 0.7);
    o = vec4(col, 1.0);
}
