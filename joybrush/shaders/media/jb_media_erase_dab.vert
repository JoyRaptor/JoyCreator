#version 300 es
// jb_media_erase_dab.vert — one eraser dab on a media layer (app only; the lab has no eraser): a soft round mark of
// how much to take away, drawn into the coverage target with MAX blending so overlapping dabs never erase more than
// once. Uses the media dab layout: only the first instanced vec4 is read.
precision highp float;
layout(location = 0) in vec2 a_corner;    // (0,0)..(1,1)
layout(location = 1) in vec4 a_i0;        // centre.xy (doc px), radius (doc px), strength 0..1
uniform vec2 u_targetSize;
uniform vec2 u_layerOrigin;
uniform float u_layerScale;
out vec2 v_unit;                           // position in dab radii, (0,0) at the centre
flat out float v_strength;
flat out float v_feather;
void main() {
    float r = max(a_i0.z * u_layerScale, 0.5);
    float pad = r + 1.5;
    vec2 c = (a_i0.xy - u_layerOrigin) * u_layerScale;
    vec2 p = c + (a_corner * 2.0 - 1.0) * pad;
    v_unit = (p - c) / r;
    v_strength = clamp(a_i0.w, 0.0, 1.0);
    v_feather = min(0.5, 1.5 / r);          // about a pixel and a half of soft edge at any size
    gl_Position = vec4(p / u_targetSize * 2.0 - 1.0, 0.0, 1.0);
}
