// Cross-dissolve: smooth alpha blend from A to B
// Author: gl-transitions
// License: MIT
// NOTE: gl-transitions spec format — GlTransitionShaderLoader wraps this body
// (injects uniforms, getFromColor/getToColor and main()). A file with its own
// main()/samplers double-declares under the wrapper and fails shader-compile
// at RUNTIME (review fix 2026-07-05; the build cannot catch asset GLSL).

vec4 transition(vec2 uv) {
  return mix(getFromColor(uv), getToColor(uv), progress);
}
