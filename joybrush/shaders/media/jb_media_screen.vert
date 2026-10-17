#version 300 es
// jb_media_screen.vert — one triangle that covers the whole viewport (display passes).
precision highp float;
#ifdef JB_ATLAS
flat out highp ivec4 v_slot;              // not per-slot: a tile no layer cell has, so reads go through the table
#endif
void main() {
#ifdef JB_ATLAS
    v_slot = ivec4(0, 0, -1073741824, -1073741824);
#endif
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
