#version 300 es
// FX-1, Joybot carries your work between rooms: shared by every room pass.
// The two rooms are PICTURES of the real screens (uA: the room we are leaving, uB: the room we are
// going to), so every warp is made of the screens themselves (owner: effects are made of the same
// stuff as the scene, never stickers). v is the screen, 0..1, y down.
precision highp float;
in vec2 vUv;
out vec4 o;
uniform sampler2D uA;
uniform sampler2D uB;
uniform float uHaveB;      // 0: the next room is still opening (its colours stand in); 1: its picture
uniform float uP;          // how far the move has gone
uniform float uT;          // seconds
uniform float uAspect;     // screen height / width
uniform vec2 uGrab;        // Joybot's fists, 0..1
uniform vec3 uA1;          // the leaving room's gradient
uniform vec3 uA2;
uniform vec3 uB1;          // the arriving room's gradient
uniform vec3 uB2;
uniform float uMirror;     // 1: the move is played left to right reversed (the next room lies to the right)
float sq(float x) { return x * x; }
float hash(float n) { return fract(sin(n) * 43758.5453); }
float noise(float x) { float i = floor(x), f = fract(x); f = f * f * (3.0 - 2.0 * f); return mix(hash(i), hash(i + 1.0), f); }
// The screen point this pixel stands for in the move's own space, and back again for the pictures:
// the move is mirrored, the rooms are never shown mirrored.
vec2 scr() { vec2 v = vec2(vUv.x, 1.0 - vUv.y); if (uMirror > 0.5) v.x = 1.0 - v.x; return v; }
vec2 pic(vec2 uv) { return uMirror > 0.5 ? vec2(1.0 - uv.x, uv.y) : uv; }
vec3 roomA(vec2 uv) { return texture(uA, pic(uv)).rgb; }
// The next room: its picture, or (while it is still opening) a dark breath of its own colours.
vec3 roomB(vec2 uv) {
    vec3 stand = mix(uB1, uB2, uv.y) * (0.10 + 0.05 * sin(uv.y * 9.0 + uT * 2.0));
    if (uHaveB <= 0.0) return stand;
    return mix(stand, texture(uB, pic(uv)).rgb, uHaveB);
}
