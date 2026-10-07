#version 300 es
// jb_media_render.frag — show a media layer on its paper: Kubelka–Munk optics over the paper colour,
// lit by one lamp across the true relief (paper tooth − crush + paint body). Display only; the state
// it reads is never changed here, so the lamp can move and export can choose lit or flat.
precision highp float;
precision highp int;

#include "media/jb_media_common.glsl"
#include "jb_paper.glsl"

uniform sampler2D u_p0;          // K·X rgb, volume
uniform sampler2D u_p1;          // S·X rgb, openness
uniform sampler2D u_paperState;  // R crush, G dry flake volume V (mm), B Σ V·flake reflectance
uniform sampler2D u_w0;          // water: w (mm), s (capillary saturation)
uniform sampler2D u_w1;          // pigment still in the water: K rgb, S
uniform vec2 u_targetSize;       // layer size, LAYER px
uniform vec2 u_layerOrigin;      // layerPx = (docPx - origin) * scale
uniform float u_layerScale;
uniform vec2 u_pan;              // screen px of doc origin
uniform float u_zoom;            // screen px per doc px
uniform float u_pxPerMm;
uniform float u_toothMm;
uniform vec3 u_paperColor;       // sRGB
uniform vec3 u_lamp;             // unit vector toward the lamp
uniform float u_relief;          // 0 = flat, 1 = physical relief
uniform float u_sheen;           // graphite sheen on heavy, burnished deposit
uniform float u_capMm;
uniform int u_mode;              // 0 final, 1 paper height, 2 deposit volume, 3 crush
uniform float u_impasto;         // paint relief shown (1 = physical)
uniform float u_paperHBot;       // the paper's height percentiles: paint fills the NORMALISED relief (see the
uniform float u_paperHTop;       // bake), so under paint the paper's relief is read on the same scale

out vec4 o_color;

float crushAt(vec2 lp) { return jb_bilinear(u_paperState, lp).r; }

void main() {
    vec2 docPx = (gl_FragCoord.xy - u_pan) / u_zoom;
    vec2 lp = (docPx - u_layerOrigin) * u_layerScale;
    float lpm = u_pxPerMm * u_layerScale;          // layer px per mm
    if (lp.x < 0.0 || lp.y < 0.0 || lp.x > u_targetSize.x || lp.y > u_targetSize.y) {
        o_color = vec4(0.16, 0.16, 0.17, 1.0);
        return;
    }
    vec4 p0 = jb_bilinear(u_p0, lp);
    vec4 p1 = jb_bilinear(u_p1, lp);
    vec4 wa = jb_bilinear(u_w0, lp);
    vec4 wp = jb_bilinear(u_w1, lp);
    vec4 s = jb_paperSurface(docPx);
    float crush = crushAt(lp);

    if (u_mode == 1) { o_color = vec4(vec3(s.z), 1.0); return; }
    vec4 dry = jb_bilinear(u_paperState, lp);
    if (u_mode == 2) { o_color = vec4(vec3(1.0 - clamp(dry.g / max(u_capMm, 1e-6), 0.0, 1.0)), 1.0); return; }
    if (u_mode == 3) { o_color = vec4(vec3(1.0 - clamp(crush * 2.0, 0.0, 1.0)), 1.0); return; }
    if (u_mode == 4) { o_color = vec4(clamp(wa.r / 0.3, 0.0, 1.0), clamp(wa.g, 0.0, 1.0), wa.r > 0.002 ? 0.5 : 0.0, 1.0); return; }
    if (u_mode == 6) { o_color = vec4(clamp(p0.rgb / 6.0, 0.0, 1.0), 1.0); return; }
    if (u_mode == 7) { o_color = vec4(clamp(p1.r / 2.0, 0.0, 1.0), p1.a, 0.0, 1.0); return; }
    if (u_mode == 5) { o_color = vec4(vec3(1.0 - clamp(dot(wp.rgb, vec3(0.333)) * 0.5, 0.0, 1.0)), 1.0); return; }

    float T = u_toothMm;
    float k = T * u_pxPerMm;
    // Gradients over ±1 layer px, in units per mm (lpm layer px per mm); crush is in tooth units.
    vec2 dC = vec2(crushAt(lp + vec2(1, 0)) - crushAt(lp - vec2(1, 0)),
                   crushAt(lp + vec2(0, 1)) - crushAt(lp - vec2(0, 1))) * 0.5 * u_layerScale;
    vec2 dW = vec2(jb_bilinear(u_w0, lp + vec2(1, 0)).r - jb_bilinear(u_w0, lp - vec2(1, 0)).r,
                   jb_bilinear(u_w0, lp + vec2(0, 1)).r - jb_bilinear(u_w0, lp - vec2(0, 1)).r) * 0.5 * u_layerScale;
    // Paint body: its thickness is real relief (mm), lit like the paper.
    vec2 dT = vec2(jb_bilinear(u_p0, lp + vec2(1, 0)).a - jb_bilinear(u_p0, lp - vec2(1, 0)).a,
                   jb_bilinear(u_p0, lp + vec2(0, 1)).a - jb_bilinear(u_p0, lp - vec2(0, 1)).a) * 0.5 * u_layerScale;
    vec2 gPaper = (s.xy - dC) * k;                       // paper/canvas relief, mm per mm
    vec2 gPaint = dT * u_pxPerMm;                        // paint body, mm per mm
    float bodyHere = smoothstep(0.004, 0.04, p0.a);
    float normK = 1.0 / max(u_paperHTop - u_paperHBot, 0.05);
    vec2 grad = mix(gPaper * u_relief, (gPaper * normK + gPaint) * u_impasto, bodyHere) + dW * u_pxPerMm * 0.35;
    vec3 n = normalize(vec3(-grad, 1.0));
    float amb = 0.55, dif = 0.45;
    float shade = (amb + dif * max(dot(n, u_lamp), 0.0)) / (amb + dif * u_lamp.z);
    // Soft shadows from thick paint: march toward the lamp; a ridge that rises above the light's line
    // shades this point, softer the farther away it is (penumbra grows with distance).
    if (u_impasto > 0.0 && u_zoom > 0.15) {
        float t0 = p0.a * u_impasto;
        vec2 ldir = normalize(u_lamp.xy + vec2(1e-6));
        float tanE = u_lamp.z / max(length(u_lamp.xy), 1e-3);
        float lit = 1.0;
        for (int i = 1; i <= 10; i++) {
            float d = float(i * i) * 0.5 + 1.0;                       // doc px, spreading out
            float tq = jb_bilinear(u_p0, lp + ldir * d * u_layerScale).a * u_impasto;
            float rise = (tq - t0) - d / u_pxPerMm * tanE;            // mm above the light's line
            lit = min(lit, clamp(1.0 - rise / (0.01 + 0.25 * d / u_pxPerMm), 0.0, 1.0));
        }
        shade *= mix(0.55, 1.0, lit);
    }

    // Wet paper is darker; pigment still floating reads a little deeper than when it has dried.
    vec3 Rg = jb_srgbToLinear(u_paperColor) * (1.0 - 0.10 * wa.g - 0.06 * smoothstep(0.0, 0.05, wa.r));
    vec3 R = jb_km(p0.rgb + wp.rgb * 1.15, p1.rgb + vec3(wp.a), Rg);
    // Dry media: opaque flakes covering the paper by AREA. A little graphite covers a little paper (light
    // grey, soft grain that builds), a full tooth covers nearly all of it (the stick's own dark). Flakes
    // sit on top of transparent washes but are hidden under opaque paint.
    float cover = 1.0 - exp(-dry.g / max(0.18 * u_capMm, 1e-7));
    float flake = dry.g > 1e-8 ? dry.b / dry.g : 0.0;
    float hide = 1.0 - exp(-0.6 * (p1.r + wp.a));
    R = mix(R, vec3(flake), cover * (1.0 - hide));

    // Heavy graphite on flattened tooth reads silvery: a soft specular from the lamp off the deposit.
    float fill = cover * (1.0 - hide);
    vec3 hv = normalize(u_lamp + vec3(0.0, 0.0, 1.0));
    float spec = pow(max(dot(n, hv), 0.0), 24.0);
    R += vec3(u_sheen * fill * fill * (0.35 + 0.65 * clamp(crush * 3.0, 0.0, 1.0)) * (0.25 + spec));

    // Wet oil is glossy: highlights on the ridges while the paint is open.
    float body = smoothstep(0.01, 0.06, p0.a);
    R += vec3(0.08 * body * p1.a * pow(max(dot(n, hv), 0.0), 30.0));
    // Standing water glints.
    float wet = smoothstep(0.004, 0.03, wa.r);
    R += vec3(0.12 * wet * pow(max(dot(n, hv), 0.0), 120.0));
    o_color = vec4(jb_linearToSrgb(R * shade), 1.0);
}
