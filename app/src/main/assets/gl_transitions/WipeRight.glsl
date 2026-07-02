// License: MIT
// Author: FadCam
// WIPE_RIGHT — old clip stays in place, new clip reveals from the left edge rightward.

vec4 transition(vec2 uv) {
  if (progress <= 0.001) return getFromColor(uv);
  if (progress >= 0.999) return getToColor(uv);
  if (uv.x > progress) {
    return getFromColor(uv);
  } else {
    return getToColor(vec2(uv.x / progress, uv.y));
  }
}
