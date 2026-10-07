#version 300 es
// jb_dry_dab.vert — one dry-media dab (a stick pressed into the paper), drawn as an instanced quad
// covering where the stick can touch. Per-instance data comes from lab/media/js/stick.js (stickContact).
precision highp float;

layout(location = 0) in vec2 a_corner;    // (0,0)..(1,1)
layout(location = 1) in vec4 a_i0;        // centre.xy (doc px, the pen tip), lean direction u.xy (unit, toward the barrel)
layout(location = 2) in vec4 a_i1;        // xMin, xMax, yMax (mm, stick frame), squeeze at the tip D (mm)
layout(location = 3) in vec4 a_i2;        // zones: point radius R0, side-1 length, side-2 length, face half-width (mm)
layout(location = 4) in vec4 a_i3;        // zone weights w0 w1 w2, slide (mm)
layout(location = 5) in vec4 a_i4;        // travel direction.xy (unit), evenness 0..1, pen pressure 0..1

uniform vec2 u_targetSize;    // layer texture size (layer px)
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
