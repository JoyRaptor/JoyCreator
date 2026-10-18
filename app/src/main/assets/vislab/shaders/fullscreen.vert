// One big triangle that covers the target. No vertex buffer: the corners come from the id.
out vec2 vUv;
out vec2 vPos;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    // A layer moved / sized / turned is made in its own space (placeIn); everything else is as drawn.
    vPos = placeIn((p * 2.0 - 1.0) * EXT);
    vUv = vPos / EXT * 0.5 + 0.5;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
