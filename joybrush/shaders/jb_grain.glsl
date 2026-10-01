// jb_grain.glsl — Joy Brush grain (JB-1.02). SHARED by the phone and the PC Brush Lab.
// GLSL ES 3.00 functions only. Include AFTER jb_tip.glsl.
//
// The owner's "cloud + gradient + threshold" idea (blueprint §1, idea 2) — the same operation as
// Photoshop's texture depth and Krita's Height mode (R3/R4), plus the tilt-aimed gradient neither has.
//
// Picture the texture as a landscape of bumps: height 1 = the highest bump, 0 = the deepest pit.
// Pressing the brush lowers a flat "paint level" into it. Paint lands wherever the ground is ABOVE
// the level's reach. Light pressure → only the tops of the bumps catch paint (dry, clumpy marks);
// heavy pressure → paint fills the pits too (solid).
//
// Two independent textures use this same maths:
//   * TIP texture  — sampled in DAB space, so it turns with the brush (bristle clumps, the cloud).
//   * PAPER grain  — sampled in CANVAS space, so it stays put (pencil and charcoal read "on paper").
// Sampling is the caller's job (the two live in different coordinate spaces); these functions get
// the height value already sampled.

// Coverage from a height value.
//   height : texture value 0..1 at this pixel.
//   level  : 0 = nothing takes paint, 1 = everything takes paint.
//   edge   : 0 = crisp clumps (Krita "Hard Mix"), ~0.33 = softer, 1 = smooth ramp (Krita "Height").
float jb_heightCoverage(float height, float level, float edge) {
    float w = max(edge, 1e-3);
    float threshold = 1.0 - level;               // ground above this takes paint
    return clamp((height - threshold) / w + 0.5, 0.0, 1.0);
}

// The paint level across one dab.
//   depth       : how deep the brush presses, usually a pressure curve's output (0..1).
//   tipCov      : jb_tipCoverage() at this pixel — soft tip edges press less deep, so edges break
//                 up naturally into grain.
//   localN      : pixel offset from the dab centre divided by radiusPx (roughly -1..1 each axis),
//                 in the SAME frame the tilt direction is given in.
//   leanDir     : unit vector of the pen's lean (from azimuth), same frame as localN.
//   tiltAmount  : sin(tilt): 0 upright … 1 flat.
//   tiltGradient: how strongly lean tips the level plane across the dab. Positive = the side the pen
//                 leans towards takes paint first; the owner tunes the sign and size in the Brush Lab.
//   radial      : how much the level drops towards the rim (a pointed, rounded tip). 0 = flat tip.
float jb_grainLevel(float depth, float tipCov, vec2 localN, vec2 leanDir,
                    float tiltAmount, float tiltGradient, float radial) {
    float plane = tiltGradient * tiltAmount * dot(localN, leanDir);
    float dome  = radial * dot(localN, localN);
    return clamp(depth * tipCov + plane - dome, 0.0, 1.0);
}

// Final dab alpha for a grained tip: grain decides WHERE paint lands; the tip's own antialiased edge
// still bounds it so the dab never leaks past its shape.
float jb_grainedCoverage(float tipCov, float grainCov) {
    return grainCov * smoothstep(0.0, 0.06, tipCov);
}

const float JB_DIR_GAIN = 0.35;
const float JB_WET_GAIN = 1.0;
float jb_paperEffectiveHeight(vec4 surf, float hCoarse, vec2 v, float slopeRangeDocPx,
                              float directional, float wet) {
    float face = clamp(dot(surf.xy, v) / max(slopeRangeDocPx, 1e-6), -1.0, 1.0);
    float hDry = surf.z + JB_DIR_GAIN * directional * face;
    float hWet = surf.z + JB_WET_GAIN * (hCoarse - surf.z);
    return mix(hDry, hWet, wet);
}
