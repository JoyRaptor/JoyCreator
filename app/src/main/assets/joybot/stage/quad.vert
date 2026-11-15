#version 300 es
// Joybot's stage: one quad per ring, light, glint or haze patch, placed in dp (y down).
precision highp float;
layout(location = 0) in vec2 aCorner;   // -1..1
layout(location = 1) in vec2 aCentre;   // dp
layout(location = 2) in vec2 aHalf;     // dp
layout(location = 3) in float aRot;     // radians
layout(location = 4) in vec4 aC1;
layout(location = 5) in vec4 aC2;
layout(location = 6) in vec4 aP;
uniform vec2 uView;                     // target size, dp
out vec2 vLocal;
out vec2 vHalf;
out vec2 vScreen;
out vec4 vC1;
out vec4 vC2;
out vec4 vP;
void main() {
    vec2 l = aCorner * aHalf;
    float c = cos(aRot), s = sin(aRot);
    vec2 p = aCentre + vec2(c * l.x - s * l.y, s * l.x + c * l.y);
    vLocal = l;
    vHalf = aHalf;
    vScreen = p;
    vC1 = aC1;
    vC2 = aC2;
    vP = aP;
    gl_Position = vec4(p.x / uView.x * 2.0 - 1.0, 1.0 - p.y / uView.y * 2.0, 0.0, 1.0);
}
