// License: MIT
// Author: FadCam
// WIPE_DOWN — old clip stays in place, new clip reveals from the top edge downward.

vec4 transition(vec2 uv) {
  if (progress <= 0.001) return getFromColor(uv);
  if (progress >= 0.999) return getToColor(uv);
  if (uv.y > progress) {
    return getFromColor(uv);
  } else {
    return getToColor(vec2(uv.x, uv.y / progress));
  }
}
