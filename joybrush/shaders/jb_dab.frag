#version 300 es
// jb_dab.frag — one dab into the stroke buffer (JB-0.07, grain JB-1.05c).
//
// Output R = cap·d, A = d, where d = tip coverage × flow — or, when a grain is on, GRAINED coverage ×
// flow. With blending set to (ONE, ONE_MINUS_SRC_ALPHA) the buffer does  s' = cap·d + s·(1 − d)  — so a
// WASH stroke (cap = opacity) approaches its opacity and never passes it, and a BUILD_UP stroke (cap = 1)
// accumulates freely. Fixed-function blending: dabs batch with no read-back.
// Grain only ever scales d (Decision 3): flow, cap, accumulate and the commit maths are untouched.
// With both grain pitches at 0 (the default) this draws exactly what it drew before grain existed.
// Must match RefCanvas.stamp in the core; the grain maths must match core/grain/GrainMath.kt.
precision highp float;

#include "jb_tip.glsl"
#include "jb_grain.glsl"
#include "jb_grain_sample.glsl"

uniform float u_aspect;
uniform float u_corner;
uniform float u_taper;
uniform float u_hardness;
uniform float u_minPx;

// Per stroke (Decision 10): a grain's depth is evaluated at the first dab and then fixed.
uniform float u_tipDepth;
uniform float u_tipEdge;
uniform float u_tipTiltGradient;
uniform float u_tipRadial;
uniform float u_paperDepth;
uniform float u_paperEdge;
uniform float u_paperTiltGradient;
uniform float u_paperRadial;
// Per batch: the pen's lean, ALREADY guarded on the CPU (Decision 1) — a finger arrives as 0 and (0,0).
uniform float u_tiltAmount;
uniform vec2 u_leanDir;

in vec2 v_offset;
in vec2 v_dabCentre;
in float v_radius;
in float v_angle;
in float v_flow;
in float v_cap;

out vec4 o_color;

void main() {
    JbTip t = JbTip(v_radius, v_angle, u_aspect, u_corner, u_taper, u_hardness, u_minPx);
    float tipCov = jb_tipCoverage(v_offset, t);
    float d = tipCov * v_flow;

    bool tipOn = u_tipGrainPitchPx > 0.0;
    bool paperOn = u_paperGrainPitchPx > 0.0;

    // Both textures are sampled every time, outside any branch: texture() needs uniform control flow
    // for its mip-level derivatives.
    float hTip = jb_tipGrainHeight(v_offset, v_angle);
    float hPaper = jb_paperGrainHeight(v_dabCentre + v_offset);

    if (tipOn || paperOn) {
        // The lean is in DOCUMENT space and so is v_offset, so they are already in one frame. (Rotating
        // both into the tip's frame, as the spec's Decision 4 describes, changes nothing: a dot product
        // is invariant under a common rotation. Not doing it is the same answer for less work.)
        vec2 localN = v_offset / max(v_radius, 1e-3);
        float g = 1.0;
        if (tipOn) {
            float level = jb_grainLevel(u_tipDepth, tipCov, localN, u_leanDir, u_tiltAmount, u_tipTiltGradient, u_tipRadial);
            g = min(g, jb_heightCoverage(hTip, level, u_tipEdge));
        }
        if (paperOn) {
            float level = jb_grainLevel(u_paperDepth, tipCov, localN, u_leanDir, u_tiltAmount, u_paperTiltGradient, u_paperRadial);
            g = min(g, jb_heightCoverage(hPaper, level, u_paperEdge));
        }
        d = jb_grainedCoverage(tipCov, g) * v_flow;
    }
    o_color = vec4(v_cap * d, 0.0, 0.0, d);
}
