#version 300 es
// jb_overlay_grain.frag: the wet sheet's look laid over its layer WITH the paper's fine grain (WetSheet,
// tasks/joybrush/specs/WET_SHEET_20261110.md, "sharp when dry"). Drawn with jb_overlay.vert on screen and into the layer's
// tiles alike, so what is printed is still what was shown.
//
// The sheet's cells are wider than a pixel (1.75 px of the screen, more of the document when painted zoomed out), so its
// look carries no detail finer than a cell: zoomed in afterwards, a wash was a smooth blur on a paper full of tooth. Pigment
// gathers in the paper's valleys, and the valleys finer than a cell are in the paper map: here each pixel's paint is made
// denser where the paper lies under its cell's mean height and thinner where it stands above, as the water's own
// granulation does at the cell's scale (jb_wet_update.frag). The look is the least coverage that reproduces the paint over
// its ground, so what it lets through of white is 1 - a + rgb per channel: that is raised to the density's power and
// coded back the same way. Where a pixel is no finer than a cell (zoomed far out) nothing is changed.
precision highp float;

#include "jb_paper.glsl"

uniform sampler2D u_layer;      // the sheet's look: the paint alone, premultiplied, smoothed between cells
uniform float u_layerOpacity;
uniform float u_grain;          // how strongly pigment follows the fine tooth (the brush's granulation; 0 = not at all)
uniform float u_cellPx;         // the sheet's cell, document px
uniform float u_paperHBot;      // the paper's 2nd and 98th height percentiles: the tooth is read on that scale,
uniform float u_paperHTop;      // as the water reads it (jb_media_bake.frag)

in vec2 v_uv;
in vec2 v_doc;
out vec4 o_color;

void main() {
    vec4 c = texture(u_layer, v_uv);
    // Gradients are taken before any branch (they are undefined inside one that differs between neighbours).
    vec2 p = v_doc / max(u_paperTexelPx, 1.0e-6);
    vec2 dx = dFdx(p), dy = dFdy(p);
    float px = max(length(dFdx(v_doc)), length(dFdy(v_doc)));   // this pixel's width, document px
    float k = u_cellPx / max(px, 1.0e-4);                        // a cell's width in pixels
    if (c.a > 0.0 && u_grain > 0.0 && k > 1.05 && u_paperTexelPx > 0.0) {
        float hFine = jb_paperReadGrad(v_doc, 0, false, dx, dy).z;
        float hCell = jb_paperReadGrad(v_doc, 0, false, dx * k, dy * k).z;
        float g = clamp(1.0 + u_grain * 3.0 * (hCell - hFine) / max(u_paperHTop - u_paperHBot, 0.05), 0.55, 1.8);
        vec3 through = pow(clamp(vec3(1.0 - c.a) + c.rgb, 0.0, 1.0), vec3(g));
        float a = 1.0 - min(through.r, min(through.g, through.b));
        c = vec4(through - vec3(1.0 - a), a);
    }
    o_color = c * u_layerOpacity;
}
