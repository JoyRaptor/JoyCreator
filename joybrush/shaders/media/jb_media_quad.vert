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
// Media Atlas v2 (jb_media_atlas.glsl): one instance per slot the pass writes, its part of the rect in that tile.
// JB_PLAIN_TARGET: a pass whose target is not layer-shaped (the paste brush's cells) draws u_rect as before.
#if defined(JB_ATLAS) && !defined(JB_PLAIN_TARGET)
layout(location = 1) in vec4 a_slot;      // slot origin (target texels, xy) and its tile (zw)
layout(location = 2) in vec4 a_clip;      // x0, y0, x1, y1: the pass rect inside that tile (layer px, the pass's resolution)
uniform highp int u_atlasShift;
#endif
#ifdef JB_ATLAS
flat out highp ivec4 v_slot;
#endif
void main() {
#if defined(JB_ATLAS) && !defined(JB_PLAIN_TARGET)
    vec2 p = mix(a_clip.xy, a_clip.zw, a_corner);
    highp ivec4 slot = ivec4(a_slot);
    v_slot = slot;
    vec2 t = p + vec2(slot.xy - (slot.zw << u_atlasShift));   // layer cell → atlas texel, offset made in ints (identity: +0)
#else
    vec2 p = mix(u_rect.xy, u_rect.zw, a_corner);
    vec2 t = p;
#ifdef JB_ATLAS
    v_slot = ivec4(0, 0, -1073741824, -1073741824);           // no layer cell has this tile: every read goes through the table
#endif
#endif
    v_layerPx = p;
    v_docPx = u_layerOrigin + p / u_layerScale;
    gl_Position = vec4(t / u_targetSize * 2.0 - 1.0, 0.0, 1.0);
}
