#version 300 es
// jb_media_quad.vert — a rectangle of the LAYER (u_rect = x0, y0, x1, y1 in layer px), for per-frame passes
// that only touch the dirty region. u_rect covering the whole target = a full-target pass.
// Two spaces: the layer texture's pixels, and the document (where the paper and brushes live):
//   layerPx = (docPx - u_layerOrigin) * u_layerScale. A raster layer is origin 0, scale 1; a vector view
//   renders a region of the page at screen resolution through the same passes.
precision highp float;
layout(location = 0) in vec2 a_corner;
uniform vec4 u_rect;
uniform vec2 u_targetSize;
uniform vec2 u_layerOrigin;
uniform float u_layerScale;
out vec2 v_docPx;
out vec2 v_layerPx;
void main() {
    vec2 p = mix(u_rect.xy, u_rect.zw, a_corner);
    v_layerPx = p;
    v_docPx = u_layerOrigin + p / u_layerScale;
    gl_Position = vec4(p / u_targetSize * 2.0 - 1.0, 0.0, 1.0);
}
