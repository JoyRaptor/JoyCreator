#version 300 es
precision highp float;
uniform sampler2D u_plain;
uniform sampler2D u_pooled;
uniform float u_amount;
in vec2 v_docPx;
out vec4 o_color;
void main() {
    vec2 uv=gl_FragCoord.xy/vec2(textureSize(u_plain,0));
    o_color=mix(texture(u_plain,uv),texture(u_pooled,uv),clamp(u_amount,0.0,1.0));
}
