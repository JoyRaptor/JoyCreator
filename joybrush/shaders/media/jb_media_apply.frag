#version 300 es
// jb_media_apply.frag — once per frame over the dirty rectangle: turn the dry stroke delta into deposit,
// paper crush and smear. Reads the current state, writes the next (ping-pong, three targets).
//
//   deposit:  dV/dW = k · (cap − V)   ⇒   V' = cap − (cap − V)·exp(−k·W)   (the tooth fills up; heavy
//             graphite stops taking more, which is why it goes smooth and shiny)
//   flakes:   dry media are opaque flakes, not a film: the paper state keeps the deposit volume V and
//             Σ V·(flake reflectance), and the display covers paper with flakes by area (see render)
//   crush:    the tooth is flattened by hard pressure and stays flattened (paper state, shared)
//   smear:    soft sticks drag a little of what is already down along the stroke
precision highp float;
precision highp int;

#include "media/jb_media_common.glsl"
#include "media/jb_media_atlas.glsl"

uniform sampler2D u_p0;          // K·X rgb, deposit volume V (mm)
uniform sampler2D u_p1;          // S·X rgb, openness
uniform sampler2D u_paperState;  // R crush (tooth units), G dry volume V (mm), B Σ V·flake reflectance, A the tinted share of V
uniform sampler2D u_delta;       // from jb_dry_dab.frag
uniform vec2 u_targetSize;
uniform float u_capMm;           // how much dry deposit the tooth holds (mm)
uniform float u_abrasion;        // k, 1/mm²
uniform float u_flakeR;          // linear reflectance of this stick's flakes (graphite ~0.01–0.25 by grade)
uniform float u_crushRate;       // tooth units per mm² of crush work
uniform float u_crushMax;
uniform float u_smear;           // fraction moved per mm slid in contact
uniform vec2 u_smearPx;          // how far loose deposit is dragged (LAYER px, along travel)
#ifdef JB_INK
// A coloured stick (core/media/StickInk.kt), its own build (JB_INK) so graphite keeps the program it had: the deposit
// goes down as a pigment film in the paint planes, in the same grain and amounts, and counts as tinted so the display
// does not also draw it as grey flakes. The film's openness (p1.a) is left alone: pencil is set paint to oil.
uniform vec3 u_inkK;             // K per mm of deposit
uniform vec3 u_inkS;             // S per mm of deposit
#endif

in vec2 v_docPx;
in vec2 v_layerPx;
layout(location = 0) out vec4 o_p0;
layout(location = 1) out vec4 o_p1;
layout(location = 2) out vec4 o_paper;

void main() {
    jb_atlasBegin();
    ivec2 c = jb_cell();
    vec2 lp = jb_fragPx();          // this cell's centre, exact (not the interpolated v_layerPx)
    vec4 p0 = jb_fetch(u_p0, c);
    vec4 p1 = jb_fetch(u_p1, c);
    vec4 ps = jb_fetch(u_paperState, c);
    vec4 dl = jb_fetch(u_delta, c);
    vec4 keepS = ps;
    // A bad delta (NaN/inf from any dab) must never reach the layer: drop it for this pixel.
    if (any(isnan(dl)) || any(isinf(dl))) dl = vec4(0.0);

    // Going back over graphite pushes it (the owner, 2026-10-06): the rubbing lead drags loose graphite a
    // little along the stroke and softens it into its neighbours. That is half of why repeated light passes
    // read as a soft dusting rather than as dots. Rate ∝ how much the lead rubbed this pixel this frame.
    float sw = clamp(dl.a * u_smear, 0.0, 0.3);
    if (sw > 0.0) {
#ifdef JB_INK
        vec3 up = jb_bilinearL(u_paperState, lp - u_smearPx).gba;
        vec3 nb = 0.25 * (jb_bilinearL(u_paperState, lp + vec2(1.5, 0.0)).gba + jb_bilinearL(u_paperState, lp - vec2(1.5, 0.0)).gba
                        + jb_bilinearL(u_paperState, lp + vec2(0.0, 1.5)).gba + jb_bilinearL(u_paperState, lp - vec2(0.0, 1.5)).gba);
        ps.gba = mix(ps.gba, mix(nb, up, 0.6), sw);
#else
        vec2 up = jb_bilinearL(u_paperState, lp - u_smearPx).gb;
        vec2 nb = 0.25 * (jb_bilinearL(u_paperState, lp + vec2(1.5, 0.0)).gb + jb_bilinearL(u_paperState, lp - vec2(1.5, 0.0)).gb
                        + jb_bilinearL(u_paperState, lp + vec2(0.0, 1.5)).gb + jb_bilinearL(u_paperState, lp - vec2(0.0, 1.5)).gb);
        ps.gb = mix(ps.gb, mix(nb, up, 0.6), sw);
#endif
    }

    // Pile saturates as the tooth fills; dust settles freely until the tooth is full.
    float room = max(u_capMm - ps.g, 0.0);
    float pile = room * (1.0 - exp(-u_abrasion * dl.r));
    float add = pile + min(dl.b, room - pile);
    ps.g += add;
    ps.b += add * u_flakeR;
#ifdef JB_INK
    // The colour uses a sane deposit only: a pixel whose deposit is not a number lays none.
    float inkAdd = abs(add) < 1.0 ? add : 0.0;
    ps.a += inkAdd;
#endif
    ps.r = min(u_crushMax, ps.r + u_crushRate * dl.g);

#ifdef JB_INK
    // A pixel whose state went bad lays no colour either: the paint planes keep what they had.
    float ink = any(isnan(ps)) ? 0.0 : inkAdd;
    if (any(isnan(ps))) ps = keepS;
    o_p0 = p0 + vec4(ink * u_inkK, 0.0);
    o_p1 = p1 + vec4(ink * u_inkS, 0.0);
#else
    if (any(isnan(ps))) ps = keepS;
    o_p0 = p0;
    o_p1 = p1;
#endif
    o_paper = ps;
}
