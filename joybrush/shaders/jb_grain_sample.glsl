// jb_grain_sample.glsl — where the two grain textures are READ (JB-1.05c). SHARED by the phone and the
// PC Brush Lab. GLSL ES 3.00 functions + their uniforms only: NO #version, NO main.
// Include AFTER jb_tip.glsl and jb_grain.glsl. jb_grain.glsl keeps its own contract untouched:
// "sampling is the caller's job; these functions get the height value already sampled".
//
// A pitch is DOCUMENT px per repeat of the texture and it is a fixed physical size: it does not grow
// with the brush, so a bigger brush shows more clumps rather than the same few blown up. A pitch <= 0
// means "this grain is OFF"; the caller (jb_dab.frag) then does not use the height at all — a height of
// 1 is NOT "takes paint everywhere" (jb_heightCoverage(1, 0, edge) is 0.5), so no fake height is returned.
uniform sampler2D u_tipGrain;        // tip texture   (dab space: turns with the brush)
uniform sampler2D u_paperGrain;      // paper grain   (canvas space: stays put)
uniform float u_tipGrainPitchPx;
uniform float u_paperGrainPitchPx;

// Tip texture height at a DAB-SPACE offset. The offset is rotated by -angle (the identical rotation
// jb_tipCoverage uses) so the texture turns with the tip.
float jb_tipGrainHeight(vec2 offsetPx, float angle) {
    float c = cos(angle), s = sin(angle);
    vec2 q = vec2(c * offsetPx.x + s * offsetPx.y, -s * offsetPx.x + c * offsetPx.y);
    vec2 uv = q / max(u_tipGrainPitchPx, 1e-3) + 0.5;
    return texture(u_tipGrain, uv).r;
}

// Paper grain height at a CANVAS-SPACE point. No rotation and no offset, ever: the same document point
// is the same texel whatever the brush is doing.
float jb_paperGrainHeight(vec2 docPx) {
    vec2 uv = docPx / max(u_paperGrainPitchPx, 1e-3);
    return texture(u_paperGrain, uv).r;
}
