#version 300 es
// jb_wet_feather.frag: a rectangle of a kept copy of the wet sheet's state laid onto the sheet again, weighted by where it
// falls (WetSheet.cutCarry, tasks/joybrush/specs/WET_SHEET_20261110.md rule 10).
//
// When the painter moves the view and paints again, a wash wider than the new sheet cannot come along whole. It is parted
// in two by a weight k that is 1 well inside the new sheet and falls to 0 toward its edge over u_feather cells: k of every
// amount (water, pigment in the water, pigment on the paper) stays wet on the new sheet, 1 - k dries where it lies and is
// printed into the layer. The two parts are drawn with this one shader (u_invert picks which), so together they are
// exactly what was there, and no line separates them: a clean cut left one as soon as water flowed across it.
//
// Drawn with jb_media_quad.vert over the target rectangle; v_layerPx is the target's cell.
precision highp float;
precision highp int;

uniform sampler2D u_src;     // the kept copy (half floats)
uniform vec4 u_srcRect;      // x0, y0, x1, y1 of the copy laid here, in the copy's own texels
uniform vec4 u_dstRect;      // the rectangle it is laid on, in this target's cells
uniform vec4 u_inner;        // x0, y0, x1, y1 (target cells): k is 0 at and beyond this rectangle's edge ...
uniform float u_feather;     // ... and 1 this many cells inside it
uniform vec4 u_channels;     // 1 for a channel that holds an amount (weighted), 0 for one that holds a ratio or an age (kept)
uniform float u_invert;      // 0: the part that stays (k); 1: the part that is left behind (1 - k)

in vec2 v_docPx;
in vec2 v_layerPx;
out vec4 o_out;

void main() {
    vec2 t = (v_layerPx - u_dstRect.xy) / max(u_dstRect.zw - u_dstRect.xy, vec2(1.0e-6));
    vec2 uv = mix(u_srcRect.xy, u_srcRect.zw, t) / vec2(textureSize(u_src, 0));
    vec4 s = texture(u_src, uv);
    float d = min(min(v_layerPx.x - u_inner.x, u_inner.z - v_layerPx.x), min(v_layerPx.y - u_inner.y, u_inner.w - v_layerPx.y));
    float k = clamp(d / max(u_feather, 1.0e-3), 0.0, 1.0);
    k = k * k * (3.0 - 2.0 * k);
    if (u_invert > 0.5) k = 1.0 - k;
    o_out = s * mix(vec4(1.0), vec4(k), u_channels);
}
