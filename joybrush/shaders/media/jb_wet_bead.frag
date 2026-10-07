#version 300 es
// jb_wet_bead.frag — running water's bead field: the water's depth smoothed over a fraction of a millimetre,
// at half resolution. Pass 0 averages 2×2 cells of water; passes 1 and 2 blur that with a Gaussian along x,
// then along y. Only runs while the paper is tilted.
//
// A running bead moves as one body (surface tension holds it together), so the flux pass reads its speed,
// whether it breaks a dry edge, and its sideways slope from this field: water drains along a bead into a
// drip that has broken away, instead of the whole edge giving way. It must be a Gaussian, never a ring or a
// box of taps: their spectra have negative lobes, and a slope taken from them drives water UP some ripples,
// sorting a wash into stripes.
precision highp float;
precision highp int;

uniform sampler2D u_w0;     // pass 0: the water, full resolution (r = depth, mm)
uniform sampler2D u_src;    // passes 1, 2: the half-resolution field being blurred
uniform int u_pass;
uniform vec2 u_axis;        // (1, 0) or (0, 1)
uniform float u_sigma;      // in half-resolution cells (at most 4.8: the kernel stops at 12)

out vec4 o_bead;

void main() {
    ivec2 c = ivec2(gl_FragCoord.xy);
    if (u_pass == 0) {
        ivec2 size = textureSize(u_w0, 0);
        float s = 0.0;
        for (int j = 0; j < 2; j++)
            for (int i = 0; i < 2; i++)
                s += texelFetch(u_w0, clamp(c * 2 + ivec2(i, j), ivec2(0), size - 1), 0).r;
        o_bead = vec4(0.25 * s, 0.0, 0.0, 1.0);
        return;
    }
    ivec2 size = textureSize(u_src, 0);
    float r = min(12.0, ceil(2.5 * u_sigma));
    float sum = 0.0, wsum = 0.0;
    for (int k = -12; k <= 12; k++) {
        float fk = float(k);
        if (abs(fk) > r) continue;
        float wk = exp(-fk * fk / (2.0 * u_sigma * u_sigma));
        sum += wk * texelFetch(u_src, clamp(c + k * ivec2(u_axis), ivec2(0), size - 1), 0).r;
        wsum += wk;
    }
    o_bead = vec4(sum / wsum, 0.0, 0.0, 1.0);
}
