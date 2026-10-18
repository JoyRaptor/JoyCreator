// SOFTEN: lays a layer that was drawn on its own (at half size) back onto the picture, blurred.
// Softness is one of the controls every layer has (types.json "layer"; owner 10-18: every part
// tweakable). Moving, sizing and turning need no pass: the layer is made in that space
// (common.glsl placeIn / placeOut). Premultiplied in, premultiplied out.
in vec2 vUv;
out vec4 o;
uniform sampler2D uSrc;
uniform float uSoft;   // 0 sharp .. 1 very soft

void main() {
    // A disc of taps (golden-angle spiral): smooth softness from few samples.
    float r = uSoft * 0.06;
    vec4 c = texture(uSrc, vUv);
    for (int k = 0; k < 12; k++) {
        float a = float(k) * 2.39996;
        float rr = r * sqrt((float(k) + 0.5) / 12.0);
        c += texture(uSrc, vUv + vec2(cos(a), sin(a)) * rr / EXT * 0.5);
    }
    o = c / 13.0 * uOpacity;
}
