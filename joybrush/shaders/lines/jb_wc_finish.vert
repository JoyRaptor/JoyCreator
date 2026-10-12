#version 300 es
precision highp float;
layout(location=0) in vec2 a_unit;
uniform vec2 u_tileOrigin;
uniform float u_outputDocSize;
out vec2 v_docPx;
void main() {
    v_docPx=u_tileOrigin+a_unit*u_outputDocSize;
    gl_Position=vec4(a_unit*2.0-1.0,0.0,1.0);
}
