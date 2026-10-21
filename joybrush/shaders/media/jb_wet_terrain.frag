#version 300 es
// jb_wet_terrain.frag — coarse water (tasks/joybrush/specs/COARSE_WATER_20261021.md): the ground under each 2 x 2 pixel cell,
// once a frame, for jb_wet_flux.frag built with JB_WET_COARSE: R = paper relief x tooth + paint body (mm), G = absorbency,
// each the mean of the cell's four pixels. The paint's body changes only by a hair's breadth a step (pigment film volume),
// so a frame's steps share it.
precision highp float;
precision highp int;

#include "media/jb_media_common.glsl"
#include "media/jb_media_atlas.glsl"

uniform sampler2D u_p0;         // .a = paint body (mm)
uniform sampler2D u_waterBake;  // R = paper height smoothed to fibre scale (0..1)
uniform sampler2D u_fluidBake;  // R = absorbency
uniform float u_toothMm;

out vec4 o_terrain;

void main() {
    jb_atlasBegin();
    ivec2 c = jb_cell();   // the cell (half resolution)
    float g = 0.0, a = 0.0;
    for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
        ivec2 px = c * 2 + ivec2(i, j);
        g += jb_fetch(u_waterBake, px).r * u_toothMm + jb_fetch(u_p0, px).a;
        a += jb_fetch(u_fluidBake, px).r;
    }
    o_terrain = vec4(0.25 * g, 0.25 * a, 0.0, 1.0);
}
