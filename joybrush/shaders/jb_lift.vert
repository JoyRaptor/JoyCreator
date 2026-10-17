#version 300 es
// jb_lift.vert — one tile of lifted lines (Select's live preview, NATIVE_LINES_V1 v1.1), placed by
// view x homography: the w the homography makes is kept, so a perspective drag stays perspective-correct.
precision highp float;

layout(location = 0) in vec2 a_corner01;  // 0..1

uniform vec2 u_tileOrigin;
uniform float u_tileSize;
uniform mat3 u_docToClip;                 // view * H (H: document -> document)

out vec2 v_uv;

void main() {
    vec2 doc = u_tileOrigin + a_corner01 * u_tileSize;
    vec3 c = u_docToClip * vec3(doc, 1.0);
    gl_Position = vec4(c.xy, 0.0, c.z);
    v_uv = a_corner01;
}
