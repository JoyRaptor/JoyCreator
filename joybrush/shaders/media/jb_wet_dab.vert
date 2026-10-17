#version 300 es
// jb_wet_dab.vert — one dab of a wet brush (water + pigment), as an instanced quad over its footprint.
// The footprint is a 2D ellipse aimed along the brush's drag (no 3D bristle model: patent guard '464).
precision highp float;

layout(location = 0) in vec2 a_corner;
layout(location = 1) in vec4 a_i0;   // centre.xy (doc px), axis.xy (unit, along the drag)
layout(location = 2) in vec4 a_i1;   // half-length, half-width (mm), water per mm slid (mm), brush wetness 0..1
layout(location = 3) in vec4 a_i2;   // pigment per mm of water: K rgb, S
layout(location = 4) in vec4 a_i3;   // slide (mm), dryness 0..1, solubility, seed
layout(location = 5) in vec4 a_i4;   // tip-to-belly lane colour shift (unused yet), -, -, -

uniform vec2 u_targetSize;
uniform float u_pxPerMm;
uniform vec2 u_layerOrigin;   // layerPx = (docPx - origin) * scale (see jb_media_quad.vert)
uniform float u_layerScale;
#ifdef JB_ATLAS
layout(location = 6) in vec4 a_slot;      // the atlas slot this copy of the dab is drawn into: origin (texels), tile
uniform highp int u_atlasShift;
flat out highp ivec4 v_slot;
#endif

out vec2 v_q;          // brush frame, mm (x along the drag, y across)
out vec2 v_docPx;
out vec2 v_layerPx;
flat out vec4 v_i1;
flat out vec4 v_i2;
flat out vec4 v_i3;
flat out vec4 v_i0;    // centre.xy, axis.xy: the fragment derives its brush-frame position from these exactly

void main() {
    vec2 r = a_i1.xy + 0.08;
    vec2 q = mix(-r, r, a_corner);
    vec2 u = a_i0.zw, v = vec2(-u.y, u.x);
    vec2 docPx = a_i0.xy + (q.x * u + q.y * v) * u_pxPerMm;
    v_q = q;
    v_docPx = docPx;
    v_i1 = a_i1; v_i2 = a_i2; v_i3 = a_i3; v_i0 = a_i0;
    v_layerPx = (docPx - u_layerOrigin) * u_layerScale;
#ifdef JB_ATLAS
    highp ivec4 slot = ivec4(a_slot);
    v_slot = slot;
    vec2 t = v_layerPx + vec2(slot.xy - (slot.zw << u_atlasShift));   // layer px → atlas texel, offset made in ints (identity: +0)
#else
    vec2 t = v_layerPx;
#endif
    gl_Position = vec4(t / u_targetSize * 2.0 - 1.0, 0.0, 1.0);
}
