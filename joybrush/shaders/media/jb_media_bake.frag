#version 300 es
// jb_media_bake.frag — the paper surface baked at layer resolution (slopes, height, E[h²]) so per-cell
// simulation passes read it with one texelFetch instead of the hex-tiled read. Rebuilt when the paper or
// its placement changes (on the phone: per tile, on demand).
precision highp float;
precision highp int;

#include "jb_paper.glsl"

uniform vec2 u_layerOrigin;   // layerPx = (docPx - origin) * scale
uniform float u_layerScale;
layout(location = 0) out vec4 o_surface;
layout(location = 1) out vec4 o_fluid;
layout(location = 2) out vec4 o_water;   // the paper as water feels it: R = height smoothed to fibre scale

void main() {
    vec2 docPx = u_layerOrigin + gl_FragCoord.xy / u_layerScale;
    o_surface = jb_paperSurface(docPx);
    o_fluid = jb_paperFluid(docPx);
    // A paper without a fluid map still has uneven sizing: derive a slow, decorrelated variation from its
    // own surface (another hex arrangement, far down the mips) so fronts and blooms are never perfectly even.
    if (u_paperFluidTexelPx <= 0.0)
        o_fluid.r = clamp(0.5 + 2.2 * (jb_paperRead(docPx, 7, false, 16.0).z - u_paperHeightMean), 0.0, 1.0);
    // A water surface bridges the finest tooth (its meniscus spans a few fibres), so flow and pinning read
    // the height two mips down; granulation still reads the full-detail height.
    o_water = vec4(jb_paperRead(docPx, 0, false, 4.0).z, 0.0, 0.0, 0.0);
}
