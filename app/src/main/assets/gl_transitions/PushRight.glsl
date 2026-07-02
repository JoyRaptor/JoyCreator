// License: MIT
// Author: FadCam
// PUSH_RIGHT — old clip slides right out of frame, new clip slides in from the left.

vec4 transition(vec2 uv) {
  if (progress <= 0.001) return getFromColor(uv);
  if (progress >= 0.999) return getToColor(uv);
  float fromWidth = 1.0 - progress;
  float toWidth = progress;
  if (uv.x > toWidth) {
    return getFromColor(vec2((uv.x - toWidth) / fromWidth, uv.y));
  } else {
    return getToColor(vec2(uv.x / toWidth, uv.y));
  }
}
