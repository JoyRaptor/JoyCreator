// License: MIT
// Author: FadCam
// WIPE_UP — old clip stays in place, new clip reveals from the bottom edge upward.

vec4 transition(vec2 uv) {
  if (progress <= 0.001) return getFromColor(uv);
  if (progress >= 0.999) return getToColor(uv);
  if (uv.y < 1.0 - progress) {
    return getFromColor(uv);
  } else {
    return getToColor(vec2(uv.x, (uv.y - (1.0 - progress)) / progress));
  }
}
