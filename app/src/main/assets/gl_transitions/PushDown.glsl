// License: MIT
// Author: FadCam
// PUSH_DOWN — old clip slides down out of frame, new clip slides in from the top.

vec4 transition(vec2 uv) {
  if (progress <= 0.001) return getFromColor(uv);
  if (progress >= 0.999) return getToColor(uv);
  float fromHeight = 1.0 - progress;
  float toHeight = progress;
  if (uv.y > toHeight) {
    return getFromColor(vec2(uv.x, (uv.y - toHeight) / fromHeight));
  } else {
    return getToColor(vec2(uv.x, uv.y / toHeight));
  }
}
