#version 300 es
// Copies a target. uLight = 1: the effects go out to the window as light: Android's compositor
// clamps colour brighter than its alpha, so a light's alpha is at least its brightness
// (VisLab composite.frag, TextLab final.frag: "glow is light, not paint").
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uGain;
uniform float uLight;
out vec4 o;
void main() {
    vec4 c = texture(uTex, vUv) * uGain;
    c.rgb = min(c.rgb, vec3(1.0));
    if (uLight > 0.5) c.a = clamp(max(c.a, max(c.r, max(c.g, c.b))), 0.0, 1.0);
    o = c;
}
