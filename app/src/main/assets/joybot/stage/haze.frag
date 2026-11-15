#version 300 es
// Heat haze: the air his thruster heats bends what is behind it (owner, 14 Nov: "a little gas
// distortion in the trail after him and underneath him ... like how heat distorts things").
// A soft disc that redraws the screen behind it, shifted by moving noise. "Behind" is the screen
// the chat opened over (a picture taken as it opened) with the chat drawer on it, opened as far as
// it is at this moment.
precision highp float;
in vec2 vLocal;
in vec2 vHalf;
in vec2 vScreen;
in vec4 vC1;     // x: strength 0..1
in vec4 vP;      // x, y: noise frequency per dp; z: how far it bends, dp; w: seed
uniform sampler2D uBack;     // the screen behind the chat
uniform sampler2D uPanel;    // the chat drawer, fully open
uniform vec4 uBackMap;       // dp -> uv for uBack: (x0, y0, w, h)
uniform vec4 uPanelRect;     // the drawer at rest, dp: (x0, y0, w, h)
uniform vec4 uRev;           // drawer opening: circle (cx, cy, r; r < 0 = no circle), w = slide down, dp
uniform float uHasPanel;
uniform float uBias;         // frost: how far the screen behind the drawer is blurred (mip bias)
uniform float uTime;         // seconds
out vec4 o;

float hash12(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x), mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p) { return 0.65 * vnoise(p) + 0.35 * vnoise(p * 2.03 + 11.7); }

vec3 behind(vec2 p) {
    vec2 bu = (p - uBackMap.xy) / uBackMap.zw;
    vec2 pp = p - vec2(0.0, uRev.w);
    vec2 uv = (pp - uPanelRect.xy) / uPanelRect.zw;
    bool inside = uHasPanel > 0.5 && uv.x >= 0.0 && uv.y >= 0.0 && uv.x <= 1.0 && uv.y <= 1.0
            && (uRev.z < 0.0 || distance(p, uRev.xy) < uRev.z);
    vec3 back = texture(uBack, bu, inside ? uBias : 0.0).rgb;
    if (!inside) return back;
    vec4 pan = texture(uPanel, uv);
    return pan.rgb + back * (1.0 - pan.a);
}

void main() {
    vec2 q = vLocal / max(vHalf, vec2(0.01));
    float m = (1.0 - smoothstep(0.35, 1.0, length(q))) * vC1.x;
    if (m < 0.003) discard;
    vec2 s = vLocal * vP.xy + vP.w;
    vec2 n = vec2(fbm(s + vec2(0.0, uTime * 0.9)), fbm(s + vec2(5.2, 1.3 + uTime * 0.9)));
    vec3 c = behind(vScreen + (n - 0.5) * vP.z);
    o = vec4(c * m, m);
}
