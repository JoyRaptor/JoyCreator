#version 300 es
// jb_commit.frag — a layer tile with the stroke applied (JB-0.07). Premultiplied RGBA.
// Used twice with the same maths: to COMMIT a stroke into a new tile (u_layerOpacity = 1,
// blending off), and to PREVIEW the stroke live on screen (u_layerOpacity = the layer's opacity,
// premultiplied-over blending). Must match RefCanvas.endStroke in the core.
precision highp float;

uniform sampler2D u_layer;     // premultiplied RGBA tile (a 1×1 transparent texture if none)
uniform sampler2D u_stroke;    // stroke buffer, R channel
uniform vec3 u_color;          // brush colour, straight (not premultiplied)
uniform float u_strokeScale;   // opacity for BUILD_UP strokes, 1 for WASH
uniform int u_erase;           // 1 = erase
uniform int u_smudge;          // 1 = the stroke buffer holds PREMULTIPLIED RGBA carried paint, not a coverage (JB-1.06)
uniform float u_layerOpacity;

in vec2 v_uv;
out vec4 o_color;

void main() {
    vec4 dst = texture(u_layer, v_uv);
    if (u_smudge == 1) {
        // A smudge only moves paint that is already there: where the layer has no alpha the stroke leaves it alone
        // (Smudge Decision 4). The stroke buffer is the accumulated carried paint, so this is "over", masked.
        // Scale all four premultiplied channels, as for an ordinary build-up stroke. Scaling
        // RGB alone would still replace the backdrop at full strength; ignoring this scale
        // made the smudge tool's opacity control ineffective.
        vec4 s = texture(u_stroke, v_uv) * u_strokeScale;
        float m = dst.a > 0.0 ? 1.0 : 0.0;
        o_color = (s * m + dst * (1.0 - s.a * m)) * u_layerOpacity;
        return;
    }
    float a = texture(u_stroke, v_uv).r * u_strokeScale;
    vec4 outc = (u_erase == 1) ? dst * (1.0 - a) : vec4(u_color * a, a) + dst * (1.0 - a);
    o_color = outc * u_layerOpacity;
}
