#version 300 es
// jb_paste_brush.frag — one step of a paste brush, the brush's side of the trade. One fragment per brush
// cell (32 lanes × 8 depths). The cell reads the canvas under it and applies the SAME rules as
// jb_paste_dab.frag from the other side: what it lays, it loses; what it ploughs up, it carries; wet paint
// it stirs dirties it. Then paint wicks slowly between neighbouring cells, mostly from the tip up toward
// the belly, so colour picked up at the tips spreads into the brush and shows when the belly is pressed
// or tilted down later. The brush keeps this state between strokes until it is cleaned or reloaded.
precision highp float;
precision highp int;

#include "media/jb_media_common.glsl"
#include "media/jb_paste.glsl"

uniform sampler2D u_p0;
uniform sampler2D u_p1;
uniform sampler2D u_paperBake;
uniform vec2 u_layerSize;        // the LAYER's size (for clamping canvas reads)
uniform float u_pxPerMm;
uniform float u_toothMm;
uniform vec2 u_layerOrigin;
uniform float u_layerScale;
uniform float u_wick;            // per step, toward the neighbours (0 = none)
uniform float u_active;          // 0: no contact this step (wicking only)

layout(location = 0) out vec4 o_b0;
layout(location = 1) out vec4 o_b1;

float surfaceAt(vec2 lp) {
    return texelFetch(u_paperBake, ivec2(clamp(lp, vec2(0.0), u_layerSize - 1.0)), 0).b * u_toothMm
         + jb_bilinear(u_p0, lp).a;
}

void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy);
    vec4 b0 = texelFetch(u_brush0, cell, 0);
    vec4 b1 = texelFetch(u_brush1, cell, 0);

    if (u_shape > 1.5) {
        // Rigid blade: only row 0 is used; each lane is a stretch of the edge. It gains what it scrapes off
        // above its underside and gives paint into the lows below it (mirror of jb_paste_dab.frag).
        if (cell.y != 0 || u_active < 0.5) { o_b0 = b0; o_b1 = b1; return; }
        float xb = (float(cell.x) + 0.5) / JB_LANES * u_bladeLen;
        float half_ = jb_bladeHalf(xb);
        vec2 n = vec2(-u_bladeDir.y, u_bladeDir.x);
        vec2 docBase = u_center + xb * u_bladeDir * u_pxPerMm;
        float gain = 0.0, give = 0.0;
        vec3 gainK = vec3(0.0); float gainS = 0.0;
        for (int k = 0; k < 5; k++) {
            float yb = ((float(k) + 0.5) / 5.0 * 2.0 - 1.0) * half_;
            vec2 docPos = docBase + yb * n * u_pxPerMm;
            vec2 pos = (docPos - u_layerOrigin) * u_layerScale;
            vec4 p0 = jb_bilinear(u_p0, pos);
            vec4 p1 = jb_bilinear(u_p1, pos);
            float t = p0.a;
            float valley = (1.0 - texelFetch(u_paperBake, ivec2(clamp(pos, vec2(0.0), u_layerSize - 1.0)), 0).b) * u_toothMm;
            float target = jb_bladeTarget(xb, yb, valley, b0.a);
            float r = 1.0 - exp(-u_rate * u_slideMm / max(2.0 * half_, 0.3));
            float rPick = 1.0 - exp(-10.0 * u_rate * u_slideMm / max(2.0 * half_, 0.3));
            if (t > target) {
                float pick = (t - target) * rPick * max(p1.a, 0.15);
                gain += pick;
                gainK += (t > 1e-6 ? p0.rgb / t : vec3(0.0)) * pick;
                gainS += (t > 1e-6 ? p1.r / t : 0.0) * pick;
            } else {
                give += min(target - t, b0.a) * r;
            }
        }
        // The cell trades the AVERAGE of its strip (the same per-pixel units the canvas pass uses).
        float strip = 0.2;
        give = min(give * strip, b0.a);
        float kb = b0.a > 1e-6 ? 1.0 - give / b0.a : 0.0;
        b0.rgb *= kb; b1.r *= kb; b0.a -= give;
        b0.rgb += gainK * strip; b1.r += gainS * strip; b0.a += gain * strip;
        if (any(isnan(b0)) || any(isnan(b1))) { o_b0 = texelFetch(u_brush0, cell, 0); o_b1 = texelFetch(u_brush1, cell, 0); return; }
        o_b0 = max(b0, vec4(0.0));
        o_b1 = max(b1, vec4(0.0));
        return;
    }
    float a = ((float(cell.x) + 0.5) / JB_LANES) * 2.0 - 1.0;
    float delta = (float(cell.y) + 0.5) / JB_DEPTH;
    float b = u_len - delta * u_lenMax;
    u_hairLen = jb_vnoise1((a + 1.0) * 8.0, uint(u_seed) + 41u);
    float cover = u_active > 0.5 && u_shape < 2.5 ? jb_pasteCover(a, b, 0.02, 0.05) : 0.0;
    if (cover > 0.0) {
        vec2 docPos = u_center + (a * u_halfW * u_wDir + b * u_lDir) * u_pxPerMm;
        vec2 pos = (docPos - u_layerOrigin) * u_layerScale;          // layer px
        // The cell is a strip of hair: read the paint across its width.
        vec2 side = u_wDir * (u_halfW / JB_LANES) * u_pxPerMm * u_layerScale;
        vec4 p0 = (jb_bilinear(u_p0, pos - side) + jb_bilinear(u_p0, pos) + jb_bilinear(u_p0, pos + side)) / 3.0;
        vec4 p1 = (jb_bilinear(u_p1, pos - side) + jb_bilinear(u_p1, pos) + jb_bilinear(u_p1, pos + side)) / 3.0;
        float load = b0.a;
        float hair = jb_pasteHair(a, docPos);
        float here = surfaceAt(pos);
        float o = 8.0 * u_layerScale;
        float around = 0.25 * (surfaceAt(pos + vec2(o, 0)) + surfaceAt(pos - vec2(o, 0))
                             + surfaceAt(pos + vec2(0, o)) + surfaceAt(pos - vec2(0, o)));
        float give = u_pressure * 0.25 + clamp(load / max(u_cellCap, 1e-6), 0.0, 1.0) * (2.0 * u_thick + 0.2);
        float c = cover * smoothstep(-0.02, 0.02, here - around + give - 0.06 * (1.0 - hair));

        float t = p0.a;
        vec3 Kc = t > 1e-6 ? p0.rgb / t : vec3(0.0);
        vec3 Sc = t > 1e-6 ? p1.rgb / t : vec3(0.0);
        float open = p1.a;
        float valley = (1.0 - texelFetch(u_paperBake, ivec2(clamp(pos, vec2(0.0), u_layerSize - 1.0)), 0).b) * u_toothMm;
        float target = jb_pasteTarget(a, load, hair, b, valley, docPos);
        float r = 1.0 - exp(-u_rate * u_slideMm / max(u_len, 0.2));
        if (target > t) {
            float dep = min((target - t) * r * c, load);
            vec3 kv = load > 1e-6 ? b0.rgb / load : vec3(0.0);
            float sv = load > 1e-6 ? b1.r / load : 0.0;
            b0.rgb -= kv * dep; b1.r -= sv * dep; b0.a -= dep;
        } else {
            float pick = (t - target) * r * c * open;
            b0.rgb += Kc * pick; b1.r += dot(Sc, vec3(1.0 / 3.0)) * pick; b0.a += pick;
        }
        // Swap, the brush's side: what went down is replaced in the cell by what came up.
        float sw = jb_pasteSwapRate(hair) * r * c * open * smoothstep(0.0, 0.2 * u_cellCap, b0.a);
        if (b0.a > 1e-6 && t > 1e-6 && sw > 0.0) {
            // Per unit of canvas area the cell trades a layer t thick; relative to the cell's own amount.
            float ex = min(1.0, sw * t / max(b0.a, 1e-6));
            vec3 kv = mix(b0.rgb / b0.a, Kc, ex);
            float sv = mix(b1.r / b0.a, dot(Sc, vec3(1.0 / 3.0)), ex);
            b0.rgb = kv * b0.a; b1.r = sv * b0.a;
        }
        // Stirring dirties the brush with the wet paint it moves through.
        float m = u_mix * r * c * open * 0.5;
        if (b0.a > 1e-6 && t > 1e-6 && m > 0.0) {
            vec3 kv = mix(b0.rgb / b0.a, Kc, m);
            float sv = mix(b1.r / b0.a, dot(Sc, vec3(1.0 / 3.0)), m);
            b0.rgb = kv * b0.a; b1.r = sv * b0.a;
        }
    }
    // Wick: paint creeps between neighbouring cells, mostly along the hair (tip ↔ belly).
    if (u_wick > 0.0) {
        ivec2 sz = ivec2(int(JB_LANES), int(JB_DEPTH));
        vec4 up = texelFetch(u_brush0, clamp(cell + ivec2(0, 1), ivec2(0), sz - 1), 0);
        vec4 dn = texelFetch(u_brush0, clamp(cell - ivec2(0, 1), ivec2(0), sz - 1), 0);
        vec4 lf = texelFetch(u_brush0, clamp(cell - ivec2(1, 0), ivec2(0), sz - 1), 0);
        vec4 rt = texelFetch(u_brush0, clamp(cell + ivec2(1, 0), ivec2(0), sz - 1), 0);
        vec4 own = texelFetch(u_brush0, cell, 0);
        b0 += u_wick * ((up + dn - 2.0 * own) + 0.25 * (lf + rt - 2.0 * own));
        vec4 up1 = texelFetch(u_brush1, clamp(cell + ivec2(0, 1), ivec2(0), sz - 1), 0);
        vec4 dn1 = texelFetch(u_brush1, clamp(cell - ivec2(0, 1), ivec2(0), sz - 1), 0);
        vec4 lf1 = texelFetch(u_brush1, clamp(cell - ivec2(1, 0), ivec2(0), sz - 1), 0);
        vec4 rt1 = texelFetch(u_brush1, clamp(cell + ivec2(1, 0), ivec2(0), sz - 1), 0);
        vec4 own1 = texelFetch(u_brush1, cell, 0);
        b1 += u_wick * ((up1 + dn1 - 2.0 * own1) + 0.25 * (lf1 + rt1 - 2.0 * own1));
    }
    if (any(isnan(b0)) || any(isnan(b1))) { b0 = texelFetch(u_brush0, cell, 0); b1 = texelFetch(u_brush1, cell, 0); }
    o_b0 = max(b0, vec4(0.0));
    o_b1 = max(b1, vec4(0.0));
}
