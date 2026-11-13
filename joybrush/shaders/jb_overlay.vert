#version 300 es
// jb_overlay.vert: one quad covering a rectangle of the document (u_rect = x, y, width, height in document px), placed by a
// document→clip matrix: a picture laid over a layer (GlPaintEngine.LayerOverlay: the wet sheet's look). jb_tile.vert with a
// rectangle in place of a square tile; drawn with jb_tile.frag, on screen and into a layer's tiles alike, so what is printed
// into the tiles is what was shown.
precision highp float;

layout(location = 0) in vec2 a_corner01;  // 0..1

uniform vec4 u_rect;
// The part of that rectangle this quad draws (x, y, width, height in document px): all of it, or one of the pieces a
// layer's plane owns of it (an animation frame's board, or the page round the boards: GlPaintEngine.LayerOverlay.pieces).
uniform vec4 u_piece;
uniform mat3 u_docToClip;

out vec2 v_uv;
out vec2 v_doc;   // document px (jb_overlay_grain.frag reads the paper there)

void main() {
    vec2 doc = u_piece.xy + a_corner01 * u_piece.zw;
    vec3 c = u_docToClip * vec3(doc, 1.0);
    gl_Position = vec4(c.xy, 0.0, 1.0);
    v_uv = (doc - u_rect.xy) / u_rect.zw;
    v_doc = doc;
}
