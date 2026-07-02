// License: MIT
// Author: FadCam
// PUSH_LEFT — old clip slides left out of frame, new clip slides in from the right.
// Standard CapCut-style push transition: at progress=p, the old clip occupies the
// left (1-p) of the screen (sampled to fill that region) and the new clip occupies
// the right p of the screen (also sampled to fill).

vec4 transition(vec2 uv) {
  if (progress <= 0.001) return getFromColor(uv);
  if (progress >= 0.999) return getToColor(uv);
  float fromWidth = 1.0 - progress;
  float toWidth = progress;
  if (uv.x < fromWidth) {
    return getFromColor(vec2(uv.x / fromWidth, uv.y));
  } else {
    return getToColor(vec2((uv.x - fromWidth) / toWidth, uv.y));
  }
}
