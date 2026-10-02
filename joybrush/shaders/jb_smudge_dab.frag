#version 300 es
// Paint deposition uses the same physical contact as stamps. One carried premultiplied
// colour arrives from SmudgeStroke; paper changes where it lands, not the pickup reservoir.
precision highp float;

#include "jb_contact.glsl"

in vec4 v_carried;
uniform float u_texturePickup;
uniform vec2 u_tileOrigin;
uniform float u_tileSize;
uniform sampler2D u_pickup00;
uniform sampler2D u_pickup10;
uniform sampler2D u_pickup20;
uniform sampler2D u_pickup01;
uniform sampler2D u_pickup11;
uniform sampler2D u_pickup21;
uniform sampler2D u_pickup02;
uniform sampler2D u_pickup12;
uniform sampler2D u_pickup22;

vec4 jb_pickupPixel(vec2 docPx) {
    vec2 q = (docPx - u_tileOrigin) / u_tileSize;
    ivec2 cell = ivec2(floor(q)) + ivec2(1);
    vec2 uv = fract(q);
    // Explicit sampler branches are ES3 portable; explicit LOD avoids divergent derivatives.
    if (cell.y == 0) {
        if (cell.x == 0) return textureLod(u_pickup00, uv, 0.0);
        if (cell.x == 1) return textureLod(u_pickup10, uv, 0.0);
        return textureLod(u_pickup20, uv, 0.0);
    }
    if (cell.y == 1) {
        if (cell.x == 0) return textureLod(u_pickup01, uv, 0.0);
        if (cell.x == 1) return textureLod(u_pickup11, uv, 0.0);
        return textureLod(u_pickup21, uv, 0.0);
    }
    if (cell.x == 0) return textureLod(u_pickup02, uv, 0.0);
    if (cell.x == 1) return textureLod(u_pickup12, uv, 0.0);
    return textureLod(u_pickup22, uv, 0.0);
}
out vec4 o_color;

void main() {
    float d = jb_contactCoverage();
    vec4 carried = v_carried;
    if (u_texturePickup > 0.0) {
        vec2 docPx = v_dabCentre + v_offset;
        vec2 drag = v_travel * min(v_radius * 0.35, u_tileSize * 0.5);
        vec4 under = jb_pickupPixel(docPx - drag);
        // CPU twin: PaintPickup.mix. This local pixel is never a second paint reservoir.
        if (under.a > 0.0) {
            vec4 local = vec4(under.rgb / under.a, 1.0) * carried.a;
            carried = mix(carried, local, u_texturePickup * under.a);
        }
    }
    o_color = carried * d;
}
