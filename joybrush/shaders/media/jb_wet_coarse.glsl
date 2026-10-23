// jb_wet_coarse.glsl — coarse water, fine paint (tasks/joybrush/specs/COARSE_WATER_20261021.md). GLSL ES 3.00 functions
// only. Include after jb_media_common.glsl.
//
// The water lives on a grid of cells F x F pixels each (F = 2); the paint, the paper and the look stay at full resolution.
// What one step does to a pixel's paint (the brush's solubility blend, settling, lifting) is decided from its parent cell's
// water as it stood MID-step and from the pixel's own paint and paper, by jb_wetChild, which BOTH passes call:
// - the coarse pass (jb_wet_update.frag built with JB_WET_COARSE) changes the water by the mean of its children's results;
// - the fine pass (jb_wet_paint.frag) applies each pixel's own result to its paint.
// Same inputs, same function: the pigment that leaves the water is the pigment that lands on the paper.
//
// Mid-step water, as the coarse pass writes it for its children:
//   mid0 = pigment suspended after moving, the brush, drying and soaking (before settling): K rgb, S
//   mid1 = (x) water depth then (mm), (y) the drying edge factor, (z) the fraction of the pigment soaked into the paper with the
//          water (its stain), (w) 1 when the brush added pigment this step (the solubility blend applies)
//   in0, in1 = the brush input the coarse cell took this step (only read when mid1.w is 1)

struct JbWetChild {
    vec4 dep;      // pigment put down on this pixel: K rgb, S
    vec4 lift;     // pigment taken back up into the water: K rgb, S (the mean of the S taken from the paper's S rgb)
    float liftFrac;// the share of the pixel's deposited pigment lifted (p0.rgb and p1.rgb each lose this share)
    float soluble; // the pixel's soluble fraction after the brush's pigment (p1.a)
    bool dryOut;   // the water is gone: what was suspended lies where it is
};

// [p0], [p1]: the pixel's paint at the step's start; [h]: its paper height (paperBake.b); [hs]: its paper height smoothed
// to fibre scale (waterBake.r). The uniforms are jb_wet_update.frag's.
JbWetChild jb_wetChild(vec4 mid0, vec4 mid1, vec4 in0, vec4 in1, vec4 p0, vec4 p1, float h, float hs,
                       float minMm, float dt, float settle, float gran, float lift) {
    JbWetChild r;
    float w = mid1.x;
    float edge = mid1.y;
    float f = mid1.z;
    vec4 pig = mid0;
    // The pigment soaked in with the water (taken out of mid0 by the coarse pass) is put down here, evenly.
    vec4 stain = f < 1.0 ? pig * (f / (1.0 - f)) : vec4(0.0);
    r.soluble = p1.a;
    if (mid1.w > 0.5) {
        // Remember how soluble the newest pigment is (graphite: 0, watercolour: ~1), as the full-resolution step does with
        // the water's pigment as the brush left it (before soaking: mid0 + stain).
        float kNew = dot(max(in0.gba, 0.0), vec3(1.0));
        float kOld = dot(p0.rgb, vec3(1.0)) + dot((pig + stain).rgb, vec3(1.0));
        if (kNew > 0.0) r.soluble = mix(p1.a, clamp(in1.g / kNew, 0.0, 1.0), kNew / max(kNew + kOld, 1.0e-6));
    }
    r.dep = stain;
    r.lift = vec4(0.0);
    r.liftFrac = 0.0;
    r.dryOut = w <= minMm;
    if (r.dryOut) {
        r.dep += pig;                   // the last of the water leaves its pigment where it is
    } else {
        // Granulation: pigment grains lodge in the paper's FINE tooth (its height minus its fibre-scale mean).
        float g = clamp(1.0 + gran * (hs - h) * 5.0, 0.3, 2.5);
        float k = 1.0 - exp(-settle * g * edge * dt * min(20.0, 0.15 / max(w, 0.004)));
        r.dep += pig * k;
        // Lift: water re-dissolves soluble pigment lying under it.
        float l = lift * dt * r.soluble * smoothstep(minMm, minMm * 4.0, w);
        vec3 ls = p1.rgb * l;
        r.lift = vec4(p0.rgb * l, dot(ls, vec3(1.0 / 3.0)));
        r.liftFrac = l;
    }
    return r;
}
