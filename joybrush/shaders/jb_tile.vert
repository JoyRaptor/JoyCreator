#version 300 es
// jb_tile.vert — one quad covering one 256×256 tile, placed by a document→clip matrix (JB-0.07).
// Used to commit a stroke into a tile (matrix = tile → whole target) and to draw tiles on screen
// (matrix = the view). v_uv (0..1) always addresses the tile's own texels, so both uses agree.
precision highp float;

layout(location = 0) in vec2 a_corner01;  // 0..1

uniform vec2 u_tileOrigin;
uniform float u_tileSize;
uniform mat3 u_docToClip;

out vec2 v_uv;

void main() {
    vec2 doc = u_tileOrigin + a_corner01 * u_tileSize;
    vec3 c = u_docToClip * vec3(doc, 1.0);
    gl_Position = vec4(c.xy, 0.0, 1.0);
    v_uv = a_corner01;
}
