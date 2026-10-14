#version 300 es
// Ordered premultiplied source units. Destination is RGBA32F until the final look is quantized.
precision highp float;
uniform sampler2D u_destination;
uniform sampler2D u_source;
uniform int u_operator; // 0 normal, 1 erase, 2 behind, 3 multiply, 4 screen
in vec2 v_uv;
out vec4 o_color;
void main() {
    vec4 d = texture(u_destination, v_uv);
    vec4 s = texture(u_source, v_uv);
    if (u_operator == 1) { o_color = d * (1.0 - s.a); return; }
    if (u_operator == 2) { o_color = d + s * (1.0 - d.a); return; }
    vec3 rgb = s.rgb + d.rgb * (1.0 - s.a);
    if (u_operator == 3) rgb = s.rgb * d.rgb + s.rgb * (1.0 - d.a) + d.rgb * (1.0 - s.a);
    if (u_operator == 4) rgb = s.rgb + d.rgb * (1.0 - s.rgb);
    o_color = vec4(rgb, s.a + d.a * (1.0 - s.a));
}
