// The bed sheet (the owner's sketch, 16 Nov): the leaving room, hauled or kicked off the screen's
// edge, is dragged back in by Joybot's fists like a sheet, twisting down into the new item's place.
// The old room BECOMES the clip. Drawn over the room pass; clear wherever the sheet is not.
uniform float uSheet;      // 0..1 how far it has been dragged in
uniform float uOff;        // where the leaving room's left edge was left, as a share of the screen's width
void main() {
    vec2 v = vec2(vUv.x, 1.0 - vUv.y);
    float e = uSheet;
    float fl = 1.0 + 2.5 * smoothstep(0.75, 1.0, e);
    vec2 d = (v - uGrab) * vec2(1.0, uAspect * fl);
    float rv = length(d) + 0.0001;
    float s = 1.0 - e * 0.94;
    float g = 1.0 + 1.8 * sin(3.14159 * e);
    float r0 = 1.3 * pow(rv / (s * 1.3), 1.0 / g);
    float ph = 5.5 * e * exp(-r0 * 1.3);
    float cs = cos(ph), sn = sin(ph);
    vec2 dn = d / rv;
    vec2 q = uGrab + vec2(dn.x * cs + dn.y * sn, -dn.x * sn + dn.y * cs) * r0 / vec2(1.0, uAspect);
    vec2 ua = q - vec2(uOff, 0.0);
    if (ua.x < 0.0 || ua.x > 1.0 || ua.y < 0.0 || ua.y > 1.0) { o = vec4(0.0); return; }
    vec3 sc = roomA(ua);
    sc *= 1.0 - (0.3 - 0.3 * cos(ph * 4.0 + r0 * 9.0)) * min(1.0, e * 4.0);
    sc += mix(uA1, uA2, ua.y) * 0.3 * e;
    o = vec4(sc, 1.0);
}
