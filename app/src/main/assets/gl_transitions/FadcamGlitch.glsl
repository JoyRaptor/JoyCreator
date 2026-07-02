// License: MIT
// Author: FadCam
// GLITCH — heavy horizontal block displacement, RGB channel separation, scanlines,
// and bright flashes. Designed to be obviously visible on a 1080p video. The
// effect ramps from 0 (clean from clip) at progress=0 to 1 (clean to clip) at
// progress=1, with a strong glitch peak in the middle.

float fadcamGlitchRandom(vec2 st) {
  return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453);
}

float fadcamGlitchHash(float n) {
  return fract(sin(n) * 43758.5453);
}

vec4 fadcamGlitchSampleFrom(vec2 uv, float p) {
  // 12 horizontal bands. Each band has its own per-frame random offset.
  float band = floor(uv.y * 12.0);
  float frameSeed = floor(p * 30.0);
  float seed = fadcamGlitchRandom(vec2(band, frameSeed));
  // Displace ±35% of screen width at full intensity. Skip a few bands entirely
  // ("missing" rows) to break the image up.
  float skip = step(0.85, seed);
  if (skip > 0.5 && p < 0.95) {
    return vec4(0.0, 0.0, 0.0, 1.0);
  }
  float displace = (seed - 0.5) * 0.7 * p;
  vec2 fromCoord = vec2(clamp(uv.x + displace, 0.0, 1.0), uv.y);
  return getFromColor(fromCoord);
}

vec4 transition(vec2 uv) {
  // Two layered samples with different seeds so adjacent bands don't all move together.
  float p = progress;

  // Base mix: blend displaced-from with to-clip.
  vec4 fromA = fadcamGlitchSampleFrom(uv, p);
  vec4 fromB = fadcamGlitchSampleFrom(vec2(uv.x, 1.0 - uv.y), p * 0.7 + 0.3);
  vec4 toColor = getToColor(uv);
  vec4 base = mix(fromA, toColor, p);
  base = mix(base, fromB, 0.35 * p);

  // Strong RGB channel separation around the seam (progress ≈ 0.5).
  float seam = 1.0 - abs(p - 0.5) * 2.0;
  seam = max(seam, 0.0);
  float r = fadcamGlitchSampleFrom(vec2(clamp(uv.x - 0.04 * seam, 0.0, 1.0), uv.y), p).r;
  float g = base.g;
  float b = getToColor(vec2(clamp(uv.x + 0.04 * seam, 0.0, 1.0), uv.y)).b;
  base.r = mix(base.r, r, seam * 0.85);
  base.b = mix(base.b, b, seam * 0.85);

  // Scanline distortion — much more visible than before.
  float scan = sin(uv.y * 320.0) * 0.12 * p;
  base.rgb += vec3(scan);

  // Random bright flash on a small fraction of frames.
  float flashSeed = fadcamGlitchRandom(vec2(floor(p * 50.0), 1.0));
  if (flashSeed > 0.88) {
    base.rgb = mix(base.rgb, vec3(1.0), 0.65);
  }

  // Static-noise sparkle overlay — only at peak glitch intensity.
  if (p > 0.3 && p < 0.85) {
    float sparkleSeed = fadcamGlitchRandom(uv * 800.0 + floor(p * 30.0));
    if (sparkleSeed > 0.992) {
      base.rgb = vec3(1.0);
    }
  }

  return base;
}
