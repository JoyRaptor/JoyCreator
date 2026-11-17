#version 300 es
// jb_media_bake.frag — the paper surface baked at layer resolution (slopes, height, E[h²]) so per-cell
// simulation passes read it with one texelFetch instead of the hex-tiled read. Rebuilt when the paper or
// its placement changes (on the phone: per tile, on demand).
precision highp float;
precision highp int;

#include "jb_paper.glsl"
#include "media/jb_media_common.glsl"
#include "media/jb_media_atlas.glsl"

uniform vec2 u_layerOrigin;   // layerPx = (docPx - origin) * scale
uniform float u_paperHBot;     // the paper's 2nd and 98th height percentiles: bakes are normalised to them,
uniform float u_paperHTop;     // so a low-contrast map still has true peaks and true valleys for paint and water
uniform float u_layerScale;
layout(location = 0) out vec4 o_surface;
layout(location = 1) out vec4 o_fluid;
layout(location = 2) out vec4 o_water;   // the paper as water feels it: R = height smoothed to fibre scale

void main() {
    vec2 docPx = u_layerOrigin + jb_fragPx() / u_layerScale;
    o_surface = jb_paperSurface(docPx);
    o_surface.b = clamp((o_surface.b - u_paperHBot) / max(u_paperHTop - u_paperHBot, 0.05), 0.0, 1.0);
    o_fluid = jb_paperFluid(docPx);
    // The fibre direction comes back signed (-1..1, turned with its patch of paper); the bake is 8-bit, so it is kept as
    // 0.5 + 0.5 x direction (0.5, 0.5 = no direction). Read by jb_wet_flux.frag and jb_wet_update.frag for PaperFibres;
    // the absorbency in R is untouched.
    o_fluid.gb = o_fluid.gb * 0.5 + 0.5;
    // A paper without a fluid map still has uneven sizing: derive a slow, decorrelated variation from its
    // own surface (another hex arrangement, far down the mips) so fronts and blooms are never perfectly even.
    if (u_paperFluidTexelPx <= 0.0)
        o_fluid.r = clamp(0.5 + 2.2 * (jb_paperRead(docPx, 7, false, 16.0).z - u_paperHeightMean), 0.0, 1.0);
    // A water surface bridges the finest tooth (its meniscus spans a few fibres), so flow and pinning read
    // the height two mips down; granulation still reads the full-detail height.
    o_water = vec4(clamp((jb_paperRead(docPx, 0, false, 4.0).z - u_paperHBot) / max(u_paperHTop - u_paperHBot, 0.05), 0.0, 1.0), 0.0, 0.0, 0.0);
}
