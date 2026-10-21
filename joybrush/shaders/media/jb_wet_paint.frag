#version 300 es
// jb_wet_paint.frag — coarse water, fine paint (tasks/joybrush/specs/COARSE_WATER_20261021.md): what one water step does to
// each pixel's paint, from its 2 x 2 cell's water as it stood mid-step (written by jb_wet_update.frag built with
// JB_WET_COARSE) and the pixel's own paint and paper, by jb_wetChild: the same function the cell used to change its water
// by the mean of its pixels, so what leaves the water lands on the paper. Reads the paint the step started from, writes
// the other half of the ping-pong (as the full-resolution update did).
precision highp float;
precision highp int;

#include "media/jb_media_common.glsl"
#include "media/jb_media_atlas.glsl"
#include "media/jb_wet_coarse.glsl"

uniform sampler2D u_mid0;       // the cell's suspended pigment mid-step (half resolution)
uniform sampler2D u_mid1;       // the cell's depth, edge factor, stain share, brush flag mid-step (half resolution)
uniform sampler2D u_in0;        // the cell's brush input (half resolution)
uniform sampler2D u_in1;
uniform sampler2D u_p0;         // deposited K rgb, volume
uniform sampler2D u_p1;         // deposited S rgb, soluble fraction
uniform sampler2D u_paperBake;  // .b = height
uniform sampler2D u_waterBake;  // R = paper height smoothed to fibre scale
uniform float u_dt;
uniform float u_minMm;
uniform float u_settle;
uniform float u_gran;
uniform float u_lift;
uniform float u_noBody;

layout(location = 0) out vec4 o_p0;
layout(location = 1) out vec4 o_p1;

void main() {
    jb_atlasBegin();
    ivec2 c = jb_cell();
    vec4 p0 = jb_fetch(u_p0, c);
    vec4 p1 = jb_fetch(u_p1, c);
    ivec2 cell = c >> 1;   // floor, for negative cells too
    vec4 mid0 = jb_fetchS(u_mid0, cell, 7);
    vec4 mid1 = jb_fetchS(u_mid1, cell, 7);
    o_p0 = p0; o_p1 = p1;
    // No water, nothing suspended and no brush: the step does nothing here (as the cell's own sum said).
    if (!(mid1.x > 0.0 || mid0 != vec4(0.0) || mid1.w > 0.5)) return;
    vec4 i0 = vec4(0.0), i1 = vec4(0.0);
    if (mid1.w > 0.5) { i0 = jb_fetchS(u_in0, cell, 7); i1 = jb_fetchS(u_in1, cell, 7); }
    JbWetChild r = jb_wetChild(mid0, mid1, i0, i1, p0, p1, jb_fetch(u_paperBake, c).b, jb_fetch(u_waterBake, c).r,
        u_minMm, u_dt, u_settle, u_gran, u_lift);
    p1.a = r.soluble;
    p0.rgb -= p0.rgb * r.liftFrac;
    p1.rgb -= p1.rgb * r.liftFrac;
    p0.rgb += r.dep.rgb;
    p1.rgb += vec3(r.dep.a);
    p0.a += dot(r.dep.rgb, vec3(1.0)) * 0.0004 * (1.0 - u_noBody);   // pigment film volume (as jb_wet_update.frag)
    o_p0 = p0;
    o_p1 = p1;
}
