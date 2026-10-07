// paper.js — loads a document paper SURFACE from the app's own catalogue (joybrush/assets/paper) and sets
// the uniforms jb_paper.glsl reads. Also builds the CPU-side height statistics the force balance needs.
// The paper itself belongs to the paper session; this file only reads it.

export const DOC_PX_PER_MM = 20; // core/paper PaperPhysical.DOC_PX_PER_MM (R10: 1 doc px ≈ 0.05 mm)

// Physical numbers until the catalogue carries them (paper session is adding toothDepthMm, compliance …).
const PHYSICAL_DEFAULTS = {
  pulp_factory: { toothDepthMm: 0.05, compliance: 0.45 },
  pulp_artisan: { toothDepthMm: 0.09, compliance: 0.55 },
  pulp_handmade: { toothDepthMm: 0.16, compliance: 0.65 },
  canvas_linen: { toothDepthMm: 0.12, compliance: 0.3 },
  canvas_cotton_duck: { toothDepthMm: 0.16, compliance: 0.3 },
  canvas_jute: { toothDepthMm: 0.25, compliance: 0.3 },
};

// Merge catalogues; every surface remembers the folder it came from.
export async function loadCatalogue(roots) {
  const out = { surfaces: [], looks: [] };
  for (const [root, file] of roots) {
    const bundled = globalThis.__LAB_BUNDLE && globalThis.__LAB_BUNDLE.json[root + file];
    let c = bundled;
    if (!c) {
      let res;
      try { res = await fetch(root + file); } catch { continue; }
      if (!res.ok) continue;
      c = await res.json();
    }
    for (const s of c.surfaces || []) out.surfaces.push({ ...s, root });
    for (const l of c.looks || []) out.looks.push({ ...l, root });
  }
  return out;
}

async function loadBitmap(url) {
  const b64 = globalThis.__LAB_BUNDLE && globalThis.__LAB_BUNDLE.files[url];
  const blob = b64 ? new Blob([Uint8Array.from(atob(b64), ch => ch.charCodeAt(0))], { type: 'image/png' })
    : await (await fetch(url)).blob();
  // A = h², not opacity: never let the browser premultiply or colour-manage it.
  return createImageBitmap(blob, { premultiplyAlpha: 'none', colorSpaceConversion: 'none' });
}

export async function loadSurface(gl, catalogue, id) {
  const entry = catalogue.surfaces.find(s => s.id === id) || catalogue.surfaces[0];
  // A height-only picture is packed here exactly as tools/paper/pack.py packs it (smaller to ship).
  const heightOnly = !!entry.heightFile || entry.packed === false;
  const bmp = await loadBitmap(entry.root + (entry.heightFile || entry.file));
  const tex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
  gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL, gl.NONE);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, bmp);
  // Exact bytes back from the GPU copy.
  const fb = gl.createFramebuffer();
  gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
  gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
  let px = new Uint8Array(bmp.width * bmp.height * 4);
  gl.readPixels(0, 0, bmp.width, bmp.height, gl.RGBA, gl.UNSIGNED_BYTE, px);
  gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  gl.deleteFramebuffer(fb);
  if (heightOnly) {
    px = packHeight(px, bmp.width, bmp.height, entry.slopeRange);
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, bmp.width, bmp.height, 0, gl.RGBA, gl.UNSIGNED_BYTE, px);
  }
  gl.generateMipmap(gl.TEXTURE_2D);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);

  const hist = new Float64Array(256);
  for (let i = 2; i < px.length; i += 4) hist[px[i]]++;
  const total = bmp.width * bmp.height;
  let mean = 0;
  for (let b = 0; b < 256; b++) { hist[b] /= total; mean += hist[b] * b / 255; }
  // 2nd and 98th height percentiles: paint and water engines normalise to them (low-contrast maps too).
  let acc = 0, hBot = 0, hTop = 1;
  for (let b = 0; b < 256; b++) { const prev = acc; acc += hist[b]; if (prev < 0.02 && acc >= 0.02) hBot = b / 255; if (prev < 0.98 && acc >= 0.98) hTop = b / 255; }
  const fluid = await loadFluid(gl, entry);
  const phys = { toothDepthMm: 0.08, compliance: 0.5, sizing: 0.6, absorbency: 0.5, capacity: 0.5, wickSpeed: 0.5, anisotropy: 0.3, ...(PHYSICAL_DEFAULTS[entry.id] || {}), ...pickPhysical(entry) };
  return {
    id: entry.id,
    name: entry.name,
    tex,
    heightMean: mean,
    phi: makePhiTable(hist),
    toothMm: phys.toothDepthMm,
    compliance: phys.compliance,
    physical: phys,
    uniforms: {
      u_paperSurface: tex,
      // Lab preview until the paper session's finer drawing paper lands: the owner's Proko reference has
      // ~1–2 px grain, so drawing_tooth is shown at half texel size (a native 0.5 px build is requested).
      u_paperTexelPx: entry.texelPx * (globalThis.__TEXEL_SCALE || (entry.id === 'drawing_tooth' && entry.texelPx >= 1 ? 0.5 : 1)),
      u_paperSize: entry.size,
      u_paperHexTexels: entry.hexTexels,
      u_paperSlopeRange: entry.slopeRange,
      u_paperRotatable: !!entry.rotatable,
      u_paperHeightMean: entry.heightMean ?? mean,
      u_paperHBot: hBot, u_paperHTop: hTop,
      ...fluid,
    },
  };
}

// The paper session's fluid map (absorbency, fibre direction, pore capacity). A paper without one reads
// neutral (u_paperFluidTexelPx = 0), but the sampler still needs a texture bound.
let neutralFluid = null;
async function loadFluid(gl, entry) {
  if (!entry.fluid) {
    if (!neutralFluid) {
      neutralFluid = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, neutralFluid);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([128, 128, 128, 128]));
    }
    return { u_paperFluid: neutralFluid, u_paperFluidTexelPx: 0, u_paperFluidSize: 1, u_paperFluidHexTexels: 1 };
  }
  const bmp = await loadBitmap(entry.root + entry.fluid);
  const tex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
  gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL, gl.NONE);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, bmp);
  gl.generateMipmap(gl.TEXTURE_2D);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
  return { u_paperFluid: tex, u_paperFluidTexelPx: entry.fluidTexelPx, u_paperFluidSize: bmp.width, u_paperFluidHexTexels: entry.fluidHexTexels };
}

// Twin of tools/paper/pack.py: Scharr slopes on a torus (height units per texel), height, height².
function packHeight(grey, w, h, slopeRange) {
  const H = new Float32Array(w * h);
  for (let i = 0; i < w * h; i++) H[i] = grey[i * 4] / 255;
  const at = (x, y) => H[((y + h) % h) * w + ((x + w) % w)];
  const out = new Uint8Array(w * h * 4);
  const enc = s => Math.round(127 + 127 * Math.max(-1, Math.min(1, s / slopeRange)));
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const dx = (3 * (at(x + 1, y - 1) - at(x - 1, y - 1)) + 10 * (at(x + 1, y) - at(x - 1, y)) + 3 * (at(x + 1, y + 1) - at(x - 1, y + 1))) / 32;
    const dy = (3 * (at(x - 1, y + 1) - at(x - 1, y - 1)) + 10 * (at(x, y + 1) - at(x, y - 1)) + 3 * (at(x + 1, y + 1) - at(x + 1, y - 1))) / 32;
    const v = H[y * w + x], o = (y * w + x) * 4;
    out[o] = enc(dx); out[o + 1] = enc(dy); out[o + 2] = grey[(y * w + x) * 4]; out[o + 3] = Math.round(255 * v * v);
  }
  return out;
}

function pickPhysical(entry) {
  const out = {};
  for (const k of ['toothDepthMm', 'compliance', 'sizing', 'absorbency', 'capacity', 'wickSpeed', 'anisotropy']) {
    if (typeof entry[k] === 'number') out[k] = entry[k];
  }
  return out;
}

// Φ(a) = E[max(0, a − u)], u = 1 − h (depth below the tooth tops, in tooth units). A flat level pushed
// to depth a (tooth units) meets this much paper per unit area. Tabulated on [0,1]; linear beyond.
export const PHI_N = 256;
export function makePhiTable(hist) {
  const t = new Float64Array(PHI_N + 1);
  let meanU = 0;
  for (let b = 0; b < 256; b++) meanU += hist[b] * (1 - b / 255);
  for (let i = 0; i <= PHI_N; i++) {
    const a = i / PHI_N;
    let e = 0;
    for (let b = 0; b < 256; b++) { const u = 1 - b / 255; if (a > u) e += hist[b] * (a - u); }
    t[i] = e;
  }
  t.meanU = meanU;
  return t;
}
export function phiAt(table, a) {
  if (a <= 0) return 0;
  if (a >= 1) return table[PHI_N] + (a - 1);
  const f = a * PHI_N, i = Math.floor(f), w = f - i;
  return table[i] * (1 - w) + table[i + 1] * w;
}
