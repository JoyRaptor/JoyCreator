// jb_blend.glsl -- GENERATED FILE, DO NOT EDIT BY HAND (JB-2.20b, LEAD_RULINGS R23).
//
// This is the Studio's own blend function, BlendModes.glslBlendFnWithModeParam(), printed
// by tools/blend-glsl/GenBlendGlsl.java. Nothing below the marker line was typed here.
// Regenerate:  bash tools/blend-glsl/gen_blend_glsl.sh
// Drift check: bash tools/blend-glsl/gen_blend_glsl.sh --check   (non-zero on ANY difference)
//
// vec3 blendPix(vec3 b, vec3 s, float uBlendMode): b = backdrop, s = source, both STRAIGHT
// (un-premultiplied) rgb in 0..1; uBlendMode is the Studio's mode code 0..25 (BlendRgb.studioCodeOf).
// NORMAL returns s, because the caller's alpha composite is already source-over.
// Functions only: no #version, no main, no uniforms. Include AFTER `precision highp float;`.
// ---- the Studio's function starts on the next line ----
vec3 blendPix(vec3 b, vec3 s, float uBlendMode) {
  if (uBlendMode < 0.5) return s;
  if (uBlendMode < 1.5) return b * s;
  if (uBlendMode < 2.5) return 1.0 - (1.0 - b) * (1.0 - s);
  if (uBlendMode < 3.5) {
    vec3 lo = 2.0 * b * s;
    vec3 hi = 1.0 - 2.0 * (1.0 - b) * (1.0 - s);
    return vec3(b.r < 0.5 ? lo.r : hi.r,
                b.g < 0.5 ? lo.g : hi.g,
                b.b < 0.5 ? lo.b : hi.b);
  }
  if (uBlendMode < 4.5) return min(b + s, vec3(1.0));
  if (uBlendMode < 5.5) return abs(b - s);
  if (uBlendMode < 6.5) {
  vec3 lw = vec3(0.3, 0.59, 0.11);
  vec3 c = s + (dot(b, lw) - dot(s, lw));
  float cl = dot(c, lw);
  float cn = min(min(c.r, c.g), c.b);
  float cx = max(max(c.r, c.g), c.b);
  if (cn < 0.0) c = cl + (c - cl) * cl / max(cl - cn, 0.00001);
  if (cx > 1.0) c = cl + (c - cl) * (1.0 - cl) / max(cx - cl, 0.00001);
  return clamp(c, 0.0, 1.0);
  }
  if (uBlendMode < 7.5) return min(b, s);
  if (uBlendMode < 8.5) return max(b, s);
  if (uBlendMode < 9.5) return min(b / max(1.0 - s, 0.00001), vec3(1.0));
  if (uBlendMode < 10.5) return 1.0 - min((1.0 - b) / max(s, 0.00001), vec3(1.0));
  if (uBlendMode < 11.5) return max(b + s - 1.0, vec3(0.0));
  if (uBlendMode < 12.5) {
    vec3 hlo = 2.0 * b * s;
    vec3 hhi = 1.0 - 2.0 * (1.0 - b) * (1.0 - s);
    return vec3(s.r < 0.5 ? hlo.r : hhi.r,
                s.g < 0.5 ? hlo.g : hhi.g,
                s.b < 0.5 ? hlo.b : hhi.b);
  }
  if (uBlendMode < 13.5) {
    vec3 sb = sqrt(max(b, vec3(0.0)));
    vec3 sp = ((16.0 * b - 12.0) * b + 4.0) * b;
    vec3 dd = mix(sb, sp, step(b, vec3(0.25)));
    vec3 slo = b - (1.0 - 2.0 * s) * b * (1.0 - b);
    vec3 shi = b + (2.0 * s - 1.0) * (dd - b);
    return mix(shi, slo, step(s, vec3(0.5)));
  }
  if (uBlendMode < 14.5) {
    vec3 vb = 1.0 - min((1.0 - b) / max(2.0 * s, 0.00001), vec3(1.0));
    vec3 vd = min(b / max(2.0 - 2.0 * s, 0.00001), vec3(1.0));
    return mix(vd, vb, step(s, vec3(0.5)));
  }
  if (uBlendMode < 15.5) return clamp(b + 2.0 * s - 1.0, 0.0, 1.0);
  if (uBlendMode < 16.5) {
    vec3 pl = min(b, 2.0 * s);
    vec3 ph = max(b, 2.0 * s - 1.0);
    return mix(ph, pl, step(s, vec3(0.5)));
  }
  if (uBlendMode < 17.5) return step(vec3(1.0), b + s);
  if (uBlendMode < 18.5) return b + s - 2.0 * b * s;
  if (uBlendMode < 19.5) return max(b - s, vec3(0.0));
  if (uBlendMode < 20.5) return min(b / max(s, 0.00001), vec3(1.0));
  if (uBlendMode < 21.5) {
    vec3 dw = vec3(0.3, 0.59, 0.11);
    return dot(s, dw) < dot(b, dw) ? s : b;
  }
  if (uBlendMode < 22.5) {
    vec3 gw = vec3(0.3, 0.59, 0.11);
    return dot(s, gw) > dot(b, gw) ? s : b;
  }
  if (uBlendMode < 25.5) {
    vec3 nw = vec3(0.3, 0.59, 0.11);
    vec3 nc = b;
    float tl = dot(s, nw);
    if (uBlendMode < 24.5) {
      vec3 hb = uBlendMode < 23.5 ? s : b;
      vec3 hs = uBlendMode < 23.5 ? b : s;
      float sat = max(max(hs.r, hs.g), hs.b) - min(min(hs.r, hs.g), hs.b);
      float hn = min(min(hb.r, hb.g), hb.b);
      float hx = max(max(hb.r, hb.g), hb.b);
      nc = hx > hn ? (hb - hn) * sat / (hx - hn) : vec3(0.0);
      tl = dot(b, nw);
    }
    nc = nc + (tl - dot(nc, nw));
    float nl = dot(nc, nw);
    float nn = min(min(nc.r, nc.g), nc.b);
    float nx = max(max(nc.r, nc.g), nc.b);
    if (nn < 0.0) nc = nl + (nc - nl) * nl / max(nl - nn, 0.00001);
    if (nx > 1.0) nc = nl + (nc - nl) * (1.0 - nl) / max(nx - nl, 0.00001);
    return clamp(nc, 0.0, 1.0);
  }
  return s;
}
