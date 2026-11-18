// The Big Pull: Joybot hauls the next room in by a seam pinned to his fists. A small steep tug, a
// harder wider one, then the whole side goes; the leaving room is squashed like rubber against the
// far edge and the seam glows in the arriving room's colours (the highlight edge the owner liked).
uniform float uAmp;        // the seam's bulge at his fists, as a share of the screen's width
uniform float uSig;        // how tall the bulge is, as a share of the height
uniform float uSheet;      // > 0: the bed sheet has the leaving room now (handoff/sheet.frag)
void main() {
    vec2 v = scr();
    float pp = clamp(uP, 0.0, 1.0);
    float sx = uAmp * exp(-sq((v.y - uGrab.y) / uSig));
    vec2 u = v;
    float pin = exp(-sq((v.x - sx) / 0.13)) * exp(-sq((v.y - uGrab.y) / (uSig * 1.6))) * min(1.0, uAmp * 5.0) * (1.0 - pp);
    u.y = uGrab.y + (v.y - uGrab.y) * (1.0 + 0.3 * pin);
    vec3 col;
    if (v.x < sx || uSheet > 0.0) {
        u.x = mix(v.x / max(sx, 0.001), 1.0 - (sx - v.x), 0.75 * (1.0 - pp));
        col = roomB(u);
    } else {
        float a = (v.x - sx) / max(1.0 - sx, 0.001);
        u.x = mix(a, v.x - sx, 0.45 + 0.55 * pp);
        col = roomA(u) * (1.0 - 0.5 * exp(-(v.x - sx) / 0.07) * step(0.004, sx));
    }
    if (uSheet <= 0.0) col += mix(uB1, uB2, v.y) * exp(-sq((v.x - sx) / 0.011)) * 1.2 * step(0.004, sx) * (1.0 - step(0.995, sx));
    o = vec4(col, 1.0);
}
