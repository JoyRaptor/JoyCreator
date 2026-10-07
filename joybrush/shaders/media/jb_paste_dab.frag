#version 300 es
// jb_paste_dab.frag — one step of a paste brush (oil, acrylic, knife) on the canvas: under each pixel the
// brush cell above it and the paint below trade paint. Reads the layer, writes the next (ping-pong over
// the footprint's rectangle). The brush cells are updated by jb_paste_brush.frag from the same rules.
//
//   lay      where the canvas is thinner than the brush leaves it, the cell's paint goes down
//   plough   where it is thicker and still wet, the brush scrapes it up (it goes into that cell)
//   stir     wet paint under a loaded brush is mixed with the brush's paint (volume-weighted, KM)
//   ridges   the brush's sides leave a little more (paint squeezed out), hair runs leave grooves
//   scumble  a nearly empty brush, or a light touch, only catches the high points of what is there
precision highp float;
precision highp int;

#include "media/jb_media_common.glsl"
#include "media/jb_paste.glsl"

uniform sampler2D u_p0;          // K rgb, thickness (mm)
uniform sampler2D u_p1;          // S rgb, openness (wet = movable)
uniform sampler2D u_paperBake;   // .b = paper height
uniform vec2 u_targetSize;
uniform float u_pxPerMm;
uniform float u_toothMm;
uniform vec2 u_layerOrigin;
uniform float u_layerScale;

in vec2 v_docPx;
in vec2 v_layerPx;
layout(location = 0) out vec4 o_p0;
layout(location = 1) out vec4 o_p1;

// Paper relief + paint body at a LAYER px (the paper bake is at layer resolution).
float surfaceAt(vec2 lp) {
    return texelFetch(u_paperBake, ivec2(clamp(lp, vec2(0.0), u_targetSize - 1.0)), 0).b * u_toothMm
         + jb_bilinear(u_p0, lp).a;
}

void main() {
    vec4 p0 = texelFetch(u_p0, ivec2(v_layerPx), 0);
    vec4 p1 = texelFetch(u_p1, ivec2(v_layerPx), 0);
    o_p0 = p0; o_p1 = p1;
    vec2 rel = (v_docPx - u_center) / u_pxPerMm;
    float a = dot(rel, u_wDir) / max(u_halfW, 1e-3);
    a *= 1.0 - jb_pasteRag(v_docPx);
    float b = dot(rel, u_lDir);
    float px = 1.0 / (u_pxPerMm * u_layerScale);   // one LAYER px, in mm
    u_hairLen = jb_vnoise1((a + 1.0) * 8.0, uint(u_seed) + 41u);
    float cover = jb_pasteCover(a, b, px / max(u_halfW, 1e-3), px);
    if (cover <= 0.0) return;

    vec4 b0, b1;
    jb_pasteRead(a, b, b0, b1);
    float load = b0.a;
    float hair = jb_pasteHair(a, v_docPx);

    // Scumble: only the high points are reached when the brush is light on paint or pressure.
    float here = surfaceAt(v_layerPx);
    float o = 8.0 * u_layerScale;
    float around = 0.25 * (surfaceAt(v_layerPx + vec2(o, 0)) + surfaceAt(v_layerPx - vec2(o, 0))
                         + surfaceAt(v_layerPx + vec2(0, o)) + surfaceAt(v_layerPx - vec2(0, o)));
    float give = u_pressure * 0.12 + clamp(load / max(u_cellCap, 1e-6), 0.0, 1.0) * 0.3;
    float reach = smoothstep(-0.02, 0.02, here - around + give - 0.06 * (1.0 - hair));
    float c = cover * reach;

    float t = p0.a;
    vec3 Kc = t > 1e-6 ? p0.rgb / t : vec3(0.0);
    vec3 Sc = t > 1e-6 ? p1.rgb / t : vec3(0.0);
    vec3 Kb = load > 1e-6 ? b0.rgb / load : vec3(0.0);
    float Sb = load > 1e-6 ? b1.r / load : 0.0;
    float open = p1.a;

    float valley = (1.0 - texelFetch(u_paperBake, ivec2(clamp(v_layerPx, vec2(0.0), u_targetSize - 1.0)), 0).b) * u_toothMm;
    float target = jb_pasteTarget(a, load, hair, b, valley, v_docPx);
    if (u_shape > 2.5) {
        // A cutting edge: the point ploughs a groove to the canvas and pushes the paint up beside it.
        float r0 = 1.0 - exp(-u_rate * u_slideMm / max(u_len, 0.2));
        float core = 1.0 - smoothstep(0.25, 0.45, abs(a));
        float flank = smoothstep(0.3, 0.5, abs(a)) * (1.0 - smoothstep(0.7, 1.0, abs(a)));
        float tNew = mix(t, min(t, 0.25 * valley), core * r0 * cover * open);   // down into the weave: canvas shows
        tNew += (t - valley) * 0.9 * flank * cover * open * u_slideMm / max(u_len, 0.2);
        float k = t > 1e-6 ? tNew / t : 0.0;
        p0.rgb *= k; p1.rgb *= k; p0.a = tNew;
        o_p0 = max(p0, vec4(0.0));
        o_p1 = vec4(max(p1.rgb, vec3(0.0)), clamp(p1.a, 0.0, 1.0));
        return;
    }
    float r = 1.0 - exp(-u_rate * u_slideMm / max(u_len, 0.2));
    if (target > t) {
        float dep = (target - t) * r * c;
        p0.rgb += Kb * dep; p1.rgb += vec3(Sb * dep);
        open = mix(open, 1.0, dep / max(t + dep, 1e-6));
        t += dep;
    } else {
        float pick = (t - target) * r * c * open;
        p0.rgb -= Kc * pick; p1.rgb -= Sc * pick;
        t -= pick;
    }
    // Swap: along some hairs the brush's paint goes down and the wet paint there comes up into the brush
    // (same volume both ways). Colours stay separate, as streaks.
    float sw = jb_pasteSwapRate(hair) * r * c * open * smoothstep(0.0, 0.2 * u_cellCap, load);
    if (t > 1e-6 && sw > 0.0) {
        float ex = min(sw, 1.0);
        vec3 kv = mix(p0.rgb / t, Kb, ex);
        vec3 sv = mix(p1.rgb / t, vec3(Sb), ex);
        p0.rgb = kv * t; p1.rgb = sv * t;
    }
    // Stir: the wet paint under a loaded brush takes on some of the brush's paint (same thickness).
    float m = u_mix * r * c * open * smoothstep(0.0, 0.2 * u_cellCap, load);
    if (t > 1e-6 && m > 0.0) {
        vec3 kv = mix(p0.rgb / t, Kb, m);
        vec3 sv = mix(p1.rgb / t, vec3(Sb), m);
        p0.rgb = kv * t; p1.rgb = sv * t;
    }
    p0.a = max(t, 0.0);
    p1.a = open;
    o_p0 = max(p0, vec4(0.0));
    o_p1 = vec4(max(p1.rgb, vec3(0.0)), clamp(p1.a, 0.0, 1.0));
}
