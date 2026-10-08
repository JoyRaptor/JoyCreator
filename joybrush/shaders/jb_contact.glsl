// jb_contact.glsl: shared stamp and paint contact, including live stylus geometry and paper.
// Coverage times flow only; the caller supplies either cap or carried colour.
// Texture reads stay behind uniform branches so implicit derivatives are valid.
#include "jb_tip.glsl"
#include "jb_grain.glsl"
#include "jb_paper.glsl"
#include "jb_tile_paper.glsl"
#include "jb_grain_sample.glsl"

uniform float u_aspect;
uniform float u_corner;
uniform float u_taper;
uniform float u_hardness;
uniform float u_minPx;

// Legacy stroke fallback; v7 contacts override depth independently at each dab.
uniform float u_tipDepth;
uniform float u_tipEdge;
uniform float u_tipTiltGradient;
uniform float u_tipRadial;
uniform float u_paperDepth;
uniform float u_paperEdge;
uniform float u_paperTiltGradient;
uniform float u_paperRadial;
uniform float u_paperInfluence; // brush influence multiplied by document bite on the CPU
uniform float u_paperDirectional;
uniform float u_paperWet;
// Per batch: the pen's lean, ALREADY guarded on the CPU (Decision 1) — a finger arrives as 0 and (0,0).
uniform float u_tiltAmount;
uniform vec2 u_leanDir;

in vec2 v_offset;
in vec2 v_dabCentre;
in float v_radius;
in float v_angle;
in float v_flow;
in float v_cap;
flat in vec2 v_travel;
flat in vec4 v_contact;
flat in vec4 v_pen;
flat in float v_live;

float jb_contactCoverage() {
    bool live = v_live > 0.5;
    float aspect = live ? v_contact.x : u_aspect;
    float hardness = live ? v_contact.y : u_hardness;
    float tipDepth = live ? v_contact.z : u_tipDepth;
    float paperDepth = live ? v_contact.w : u_paperDepth;
    float tilt = live ? v_pen.y : u_tiltAmount;
    vec2 lean = live ? v_pen.zw : u_leanDir;
    vec2 contactOffset = v_offset + (live ? v_pen.x : 0.0) * v_radius * vec2(cos(v_angle), sin(v_angle));
    JbTip t = JbTip(v_radius, v_angle, aspect, u_corner, u_taper, hardness, u_minPx);
    float tipCov = jb_tipCoverage(contactOffset, t);
    float d = tipCov * v_flow;

    bool tipOn = u_tipGrainPitchPx > 0.0;
    bool paperOn = u_paperGrainPitchPx > 0.0 && u_paperInfluence > 0.0;

    // texture() needs UNIFORM control flow for its mip-level derivatives, and a branch on a uniform is
    // uniform: every fragment of the draw takes the same side. So a grain that is off costs no texture read.
    float hTip = 1.0;
    if (tipOn) hTip = jb_tipGrainHeight(contactOffset, v_angle);
    float hPaper = 1.0;
    if (paperOn) {
        vec2 docPx = v_dabCentre + v_offset;
        // Old height-only brushes retain the cheap read and exactly the existing height.
        if (u_paperDirectional <= 0.0 && u_paperWet <= 0.0) hPaper = jb_tilePaperHeight(docPx);
        else {
            vec4 surface = jb_tilePaperSurface(docPx);
            float coarse = u_paperWet > 0.0 ? jb_tilePaperCoarseHeight(docPx) : surface.z;
            hPaper = jb_paperEffectiveHeight(surface, coarse, v_travel,
                u_paperSlopeRange / u_paperTexelPx, u_paperDirectional, u_paperWet);
        }
    }

    if (tipOn || paperOn) {
        // The lean is in DOCUMENT space and so is v_offset, so they are already in one frame. (Rotating
        // both into the tip's frame, as the spec's Decision 4 describes, changes nothing: a dot product
        // is invariant under a common rotation. Not doing it is the same answer for less work.)
        vec2 localN = contactOffset / max(v_radius, 1e-3);
        float g = 1.0;
        if (tipOn) {
            float level = jb_grainLevel(tipDepth, tipCov, localN, lean, tilt, u_tipTiltGradient, u_tipRadial);
            g = min(g, jb_heightCoverage(hTip, level, u_tipEdge));
        }
        if (paperOn) {
            float level = jb_grainLevel(paperDepth, tipCov, localN, lean, tilt, u_paperTiltGradient, u_paperRadial);
            g = min(g, mix(1.0, jb_heightCoverage(hPaper, level, u_paperEdge), u_paperInfluence));
        }
        d = jb_grainedCoverage(tipCov, g) * v_flow;
    }
    return d;
}
