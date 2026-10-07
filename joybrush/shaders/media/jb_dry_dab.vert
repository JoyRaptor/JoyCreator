#version 300 es
// jb_dry_dab.vert — one dry-media dab (a stick pressed into the paper), drawn as an instanced quad
// covering where the stick can touch. Per-instance data is computed on the CPU by the force balance
// (lab/media/js/stick.js), which decides how deep the stick sinks for the pen's force.
precision highp float;

layout(location = 0) in vec2 a_corner;    // (0,0)..(1,1)
layout(location = 1) in vec4 a_i0;        // centre.xy (doc px), lean direction u.xy (unit)
layout(location = 2) in vec4 a_i1;        // xMin, xMax, yMax (mm, stick frame), depth d (mm)
layout(location = 3) in vec4 a_i2;        // tanB, tanBf, tipR, conformity eps (mm, the sheet's give)
layout(location = 4) in vec4 a_i3;        // tanA, rhoMax, xLimit, slide ds (mm)
layout(location = 5) in vec4 a_i4;        // travel direction.xy (unit), facet (mm), pen pressure 0..1

uniform vec2 u_targetSize;    // doc px of the layer texture
uniform float u_pxPerMm;
uniform vec2 u_layerOrigin;   // layerPx = (docPx - origin) * scale (see jb_media_quad.vert)
uniform float u_layerScale;

out vec2 v_q;                 // stick-frame position, mm
out vec2 v_docPx;
out vec2 v_layerPx;
flat out vec4 v_i1;
flat out vec4 v_i2;
flat out vec4 v_i3;
flat out vec4 v_i4;
flat out vec2 v_lean;

void main() {
    const float margin = 0.06;   // mm around the footprint (soft edges)
    vec2 lo = vec2(a_i1.x - margin, -a_i1.z - margin);
    vec2 hi = vec2(a_i1.y + margin, a_i1.z + margin);
    vec2 q = mix(lo, hi, a_corner);
    vec2 u = a_i0.zw;
    vec2 v = vec2(-u.y, u.x);
    vec2 docPx = a_i0.xy + (q.x * u + q.y * v) * u_pxPerMm;
    v_q = q;
    v_docPx = docPx;
    v_i1 = a_i1; v_i2 = a_i2; v_i3 = a_i3; v_i4 = a_i4;
    v_lean = u;
    v_layerPx = (docPx - u_layerOrigin) * u_layerScale;
    gl_Position = vec4(v_layerPx / u_targetSize * 2.0 - 1.0, 0.0, 1.0);
}
