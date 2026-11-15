#version 300 es
// Joybot as drawn, or (uTint = 1) his light-trail copy: one colour of him, hot pink in the darks to a
// yellowish white in the lights (owner, 14 Nov).
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uAlpha;
uniform float uTint;
out vec4 o;
void main() {
    vec4 c = texture(uTex, vUv);          // premultiplied
    if (uTint > 0.5) {
        vec3 s = c.a > 0.0 ? c.rgb / c.a : vec3(0.0);
        float l = clamp((dot(s, vec3(0.3, 0.59, 0.11)) - 0.12) / 0.76, 0.0, 1.0);
        vec3 col = mix(vec3(1.0, 30.0 / 255.0, 170.0 / 255.0), vec3(1.0, 246.0 / 255.0, 205.0 / 255.0), l);
        c = vec4(col * c.a, c.a);
    }
    o = c * uAlpha;
}
