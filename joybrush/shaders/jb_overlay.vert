#version 300 es
// jb_overlay.vert: one quad covering a rectangle of the document (u_rect = x, y, width, height in document px), placed by a
// document→clip matrix: a picture laid over a layer (GlPaintEngine.LayerOverlay: the wet sheet's look). jb_tile.vert with a
// rectangle in place of a square tile; drawn with jb_tile.frag, on screen and into a layer's tiles alike, so what is printed
// into the tiles is what was shown.
precision highp float;

layout(location = 0) in vec2 a_corner01;  // 0..1

uniform vec4 u_rect;
uniform mat3 u_docToClip;

out vec2 v_uv;
out vec2 v_doc;   // document px (jb_overlay_grain.frag reads the paper there)

void main() {
    vec2 doc = u_rect.xy + a_corner01 * u_rect.zw;
    vec3 c = u_docToClip * vec3(doc, 1.0);
    gl_Position = vec4(c.xy, 0.0, 1.0);
    v_uv = a_corner01;
    v_doc = doc;
}
