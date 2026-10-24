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
uniform int u_blend;           // 0 = normal (default), 1 = multiply, 2 = screen (brush v9, VECTOR_RENDER_DESIGN H6); erase wins
uniform int u_rgbaStroke; // 1 = colour-varying watercolor, 0 leaves every existing branch unchanged
uniform int u_smudge;          // >0 = the stroke buffer holds PREMULTIPLIED RGBA carried paint, not a coverage (JB-1.06); 2 = with paint, 3 = a slab unit
uniform float u_layerOpacity;
uniform int u_coloredStroke;
uniform sampler2D u_colorMoments;

in vec2 v_uv;
out vec4 o_color;

void main() {
    vec4 dst = texture(u_layer, v_uv);
    if (u_coloredStroke == 1) {
        float a = texture(u_stroke, v_uv).r * u_strokeScale;
        vec3 sp = texture(u_colorMoments, v_uv).rgb * u_strokeScale;
        if (u_erase == 1) { o_color = dst * (1.0 - a) * u_layerOpacity; return; }
        vec3 rgb = sp + dst.rgb * (1.0 - a);
        if (u_blend == 1) rgb = sp * dst.rgb + sp * (1.0 - dst.a) + dst.rgb * (1.0 - a);
        if (u_blend == 2) rgb = sp + dst.rgb * (1.0 - sp);
        o_color = vec4(rgb, a + dst.a * (1.0 - a)) * u_layerOpacity;
        return;
    }
    if(u_rgbaStroke == 1) {
        vec4 src=texture(u_stroke,v_uv)*u_strokeScale;
        if(u_erase == 1) { o_color=dst*(1.0-src.a)*u_layerOpacity; return; }
        vec3 rgb=src.rgb+dst.rgb*(1.0-src.a);
        if(u_blend == 1) rgb=src.rgb*dst.rgb+src.rgb*(1.0-dst.a)+dst.rgb*(1.0-src.a);
        if(u_blend == 2) rgb=src.rgb+dst.rgb*(1.0-src.rgb);
        o_color=vec4(rgb,src.a+dst.a*(1.0-src.a))*u_layerOpacity;
        return;
    }
    if (u_smudge > 0) {
        // A smudge only moves paint that is already there: where the layer has no alpha the stroke leaves it alone
        // (Smudge Decision 4). The stroke buffer is the accumulated carried paint, so this is "over", masked.
        // Scale all four premultiplied channels, as for an ordinary build-up stroke. Scaling
        // RGB alone would still replace the backdrop at full strength; ignoring this scale
        // made the smudge tool's opacity control ineffective.
        vec4 s = texture(u_stroke, v_uv) * u_strokeScale;
        // 3 = a pixel slab's unit (editable lines v1.2): only the carried paint, where the layer under it had paint.
        if (u_smudge == 3) { o_color = dst.a > 0.0 ? s : vec4(0.0); return; }
        if (u_erase == 1) {
            o_color = dst * (1.0 - s.a) * u_layerOpacity;
            return;
        }
        float m = u_smudge == 2 || dst.a > 0.0 ? 1.0 : 0.0;
        o_color = (s * m + dst * (1.0 - s.a * m)) * u_layerOpacity;
        return;
    }
    float a = texture(u_stroke, v_uv).r * u_strokeScale;
    vec4 outc = (u_erase == 1) ? dst * (1.0 - a) : vec4(u_color * a, a) + dst * (1.0 - a);
    // The glaze blends: W3C multiply and screen on premultiplied colour, matching RefCanvas.endStroke and
    // render/Blend. Callers that never set u_blend get 0, so the line above is untouched for every existing stroke.
    if (u_erase != 1 && u_blend != 0) {
        vec3 sp = u_color * a;
        vec3 rgb = (u_blend == 1) ? sp * dst.rgb + sp * (1.0 - dst.a) + dst.rgb * (1.0 - a)
                                  : sp + dst.rgb * (1.0 - sp);
        outc = vec4(rgb, a + dst.a * (1.0 - a));
    }
    o_color = outc * u_layerOpacity;
}
