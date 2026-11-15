#version 300 es
// A flat colour: fades the trail (with a ZERO, ONE_MINUS_SRC_ALPHA blend) and clears its last crumbs.
precision highp float;
uniform vec4 uColour;
out vec4 o;
void main() { o = uColour; }
