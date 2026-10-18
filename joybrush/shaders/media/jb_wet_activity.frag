#version 300 es
// jb_wet_activity.frag — Media Atlas v2 B3: where the water is, coarsely. One output texel per 16×16 block of the water
// state, raw texel space (an atlas slot of 256² is exactly 16×16 output texels; no page table: the host maps slots to
// tiles). Read back asynchronously and reduced per slot on the CPU (MediaAtlasWater.observe).
precision highp float;
precision highp int;

uniform sampler2D u_w0;   // w (mm), s (0..1), wet time (s), outflow (mm/s)

out vec4 o_act;           // max w, max s, max outflow, 0

void main() {
    ivec2 base = ivec2(gl_FragCoord.xy) * 16;
    vec3 m = vec3(0.0);
    for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
        vec4 a = texelFetch(u_w0, base + ivec2(x, y), 0);
        m = max(m, vec3(a.r, a.g, a.a));
    }
    o_act = vec4(m, 0.0);
}
