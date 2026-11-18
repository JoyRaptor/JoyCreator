#version 300 es
// What Joybot clutches to his chest: the drawing rolled into a scroll (kind 0), or the room sucked
// into a ball (kind 1). Both are made of the leaving room's own picture and colours.
// Drawn with the stage's quad.vert: vLocal and vHalf in dp.
precision highp float;
in vec2 vLocal;
in vec2 vHalf;
in vec4 vC1;      // the leaving room's first colour; a = opacity
in vec4 vC2;      // its second colour
in vec4 vP;       // x: kind; y: seconds
uniform sampler2D uA;
uniform float uPx;
out vec4 o;
void main() {
    vec2 q = vLocal / max(vHalf, vec2(0.01));
    vec3 col;
    float a;
    if (vP.x < 0.5) {
        // a capsule of paper
        float hw = vHalf.x - vHalf.y;
        float dx = max(abs(vLocal.x) - hw, 0.0);
        float d = length(vec2(dx, vLocal.y)) - vHalf.y;
        a = 1.0 - smoothstep(-uPx, uPx * 0.5, d);
        vec3 pic = texture(uA, vec2(0.5 + q.x * 0.32, 0.5 + q.y * 0.10)).rgb;
        col = mix(vec3(0.95, 0.93, 0.88), pic, 0.22);
        col *= 0.72 + 0.28 * cos(q.y * 1.35);                                      // round like a roll
        col = mix(col, vC1.rgb, (1.0 - smoothstep(0.03, 0.045, abs(q.x + 0.2))) * 0.9);   // its band
        col *= 1.0 - 0.35 * smoothstep(-2.0, 0.0, d);
    } else {
        float r = length(q);
        a = 1.0 - smoothstep(1.0 - uPx / max(vHalf.x, 1.0) * 1.5, 1.0, r);
        float nz = sqrt(max(0.0, 1.0 - r * r));
        float an = vP.y * 3.0 + r * 4.0;
        float cs = cos(an), sn = sin(an);
        vec2 uv = 0.5 + vec2(q.x * cs - q.y * sn, q.x * sn + q.y * cs) * 0.33;
        vec3 pic = texture(uA, uv).rgb;
        col = mix(mix(vC1.rgb, vC2.rgb, q.y * 0.5 + 0.5), pic, 0.55) * (0.45 + 0.75 * nz);
        col += vC2.rgb * pow(1.0 - nz, 2.5) * 0.7;                                  // the rim
        col += vec3(1.0) * pow(max(0.0, 1.0 - length(q - vec2(-0.35, -0.38)) * 2.2), 2.0) * 0.6;   // a wet glint
    }
    a *= vC1.a;
    o = vec4(col * a, a);
}
