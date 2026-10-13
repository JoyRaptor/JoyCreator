#version 300 es
precision highp float;
layout(location=0) in vec2 a_corner;
layout(location=1) in vec4 a_geometry; // document x/y, radius, angle
layout(location=2) in vec4 a_deposit;  // sample layer, three band deposition alphas
uniform vec2 u_tileOrigin;
uniform float u_tileSize;
uniform float u_scale;
out vec2 v_uv;
flat out float v_sample;
flat out vec3 v_bandAlpha;
void main() {
    float c=cos(a_geometry.w),s=sin(a_geometry.w);
    vec2 local=a_corner*a_geometry.z;
    vec2 doc=a_geometry.xy+mat2(c,s,-s,c)*local;
    gl_Position=vec4((doc-u_tileOrigin)*u_scale/u_tileSize*2.0-1.0,0.0,1.0);
    v_uv=a_corner*0.5+0.5;
    v_sample=a_deposit.x;
    v_bandAlpha=a_deposit.yzw;
}
