// TextLab look book: letters into distance pictures, and where each letter sits.
//
// A PIECE is one shape's distance picture: for every pixel, how far it is from the shape's edge
// (em, positive inside). Everything else in TextLab is made from that. A piece is usually one
// letter, so letters can move on their own; it could as well be a whole pen drawing.
//
// The app's twin does the same with Android's Canvas: draw the glyph, run the same transform.
(function () {
  const TL = (window.TL = window.TL || {});

  TL.GLYPH_EM = 208;   // pixels per em in a piece
  TL.SPREAD = 0.25;    // em: the field stores no distance beyond this
  const SS = 2;        // drawn this many times larger, then averaged down: steadier edges
  const INF = 1e20;

  // The fonts the app already ships. `css` is the family name the page loads the file under.
  TL.FONTS = {
    archivo: { css: 'TLArchivo', file: 'app/src/main/res/font/archivo_variable.ttf', weight: '100 900', stretch: '62% 125%' },
    plex: { css: 'TLPlex', file: 'app/src/main/res/font/plex_sans_variable.ttf', weight: '100 700', stretch: '85% 100%' },
    mono: { css: 'TLMono', file: 'app/src/main/res/font/plex_mono_semibold.ttf', weight: '600' },
    ubuntu: { css: 'TLUbuntu', file: 'app/src/main/res/font/ubuntu_regular.ttf', weight: '400' },
    doto: { css: 'TLDoto', file: 'app/src/main/assets/doto.ttf', weight: '400' },
  };
  TL.loadFonts = async (urlOf) => {
    for (const f of Object.values(TL.FONTS)) {
      if (!urlOf(f.file)) continue;   // a one-file build carries only the fonts its looks use
      try {
        const face = new FontFace(f.css, 'url(' + urlOf(f.file) + ')', { weight: f.weight, stretch: f.stretch || 'normal' });
        document.fonts.add(await face.load());
      } catch (e) { console.warn('font not loaded: ' + f.file, e); }
    }
  };
  const stretchWord = (w) => (w <= 68 ? 'extra-condensed' : w <= 81 ? 'condensed' : w <= 93 ? 'semi-condensed'
    : w < 106 ? 'normal' : w < 119 ? 'semi-expanded' : 'expanded');

  // Felzenszwalb and Huttenlocher's exact distance transform, one row or column at a time.
  function edt1d(grid, offset, stride, length, f, v, z) {
    v[0] = 0; z[0] = -INF; z[1] = INF; f[0] = grid[offset];
    for (let q = 1, k = 0, s = 0; q < length; q++) {
      f[q] = grid[offset + q * stride];
      const q2 = q * q;
      do { const r = v[k]; s = (f[q] - f[r] + q2 - r * r) / (q - r) / 2; } while (s <= z[k] && --k > -1);
      k++; v[k] = q; z[k] = s; z[k + 1] = INF;
    }
    for (let q = 0, k = 0; q < length; q++) {
      while (z[k + 1] < q) k++;
      const r = v[k], qr = q - r;
      grid[offset + q * stride] = f[r] + qr * qr;
    }
  }
  function edt(grid, w, h) {
    const n = Math.max(w, h), f = new Float64Array(n), v = new Uint16Array(n), z = new Float64Array(n + 1);
    for (let x = 0; x < w; x++) edt1d(grid, x, w, h, f, v, z);
    for (let y = 0; y < h; y++) edt1d(grid, y * w, 1, w, f, v, z);
  }

  // Coverage (0..255, rows top to bottom, w x h both even) to a distance picture at half that
  // size: em, positive inside, rows BOTTOM to top. Edge pixels use their coverage as a sub-pixel
  // distance, which is what keeps a bevel from shimmering.
  TL.distanceFromCoverage = (alpha, w, h) => {
    const outer = new Float64Array(w * h), inner = new Float64Array(w * h);
    for (let i = 0; i < w * h; i++) {
      const a = alpha[i] / 255;
      if (a <= 0) { outer[i] = INF; inner[i] = 0; }
      else if (a >= 1) { outer[i] = 0; inner[i] = INF; }
      else { const d = 0.5 - a; outer[i] = d > 0 ? d * d : 0; inner[i] = d < 0 ? d * d : 0; }
    }
    edt(outer, w, h); edt(inner, w, h);
    const ow = w / SS, oh = h / SS, out = new Float32Array(ow * oh);
    const toEm = 1 / (SS * TL.GLYPH_EM);
    for (let y = 0; y < oh; y++) for (let x = 0; x < ow; x++) {
      let s = 0;
      for (let j = 0; j < SS; j++) for (let i = 0; i < SS; i++) {
        const k = (y * SS + j) * w + x * SS + i;
        s += Math.sqrt(inner[k]) - Math.sqrt(outer[k]);
      }
      out[(oh - 1 - y) * ow + x] = Math.max(s / (SS * SS) * toEm, -TL.SPREAD);
    }
    return { d: out, w: ow, h: oh };
  };

  // Points on a piece: its outline (with the outward direction), its inside, and its CORNERS
  // (where the outline turns sharply outward: where a made object catches a glint). Cell em, y up.
  function pointsOf(pic) {
    const { d, w, h } = pic, edge = [], fill = [], half = 0.6 / TL.GLYPH_EM, cand = [];
    const R = 3;
    const dirAt = (x, y) => {
      const gx = d[y * w + x + 1] - d[y * w + x - 1], gy = d[(y + 1) * w + x] - d[(y - 1) * w + x];
      const l = Math.hypot(gx, gy) || 1;
      return [gx / l, gy / l];
    };
    for (let y = 1; y < h - 1; y++) for (let x = 1; x < w - 1; x++) {
      const v = d[y * w + x];
      if (Math.abs(v) < half) {
        let gx = d[y * w + x + 1] - d[y * w + x - 1], gy = d[(y + 1) * w + x] - d[(y - 1) * w + x];
        const l = Math.hypot(gx, gy) || 1;
        edge.push((x + 0.5) / TL.GLYPH_EM, (y + 0.5) / TL.GLYPH_EM, -gx / l, -gy / l);
        if (x > R && y > R && x < w - R - 1 && y < h - R - 1) {
          // how fast the inward direction converges here: strongly negative at an outward corner
          const div = (dirAt(x + R, y)[0] - dirAt(x - R, y)[0] + dirAt(x, y + R)[1] - dirAt(x, y - R)[1]) / (2 * R);
          if (div < -0.07) cand.push([-div, (x + 0.5) / TL.GLYPH_EM, (y + 0.5) / TL.GLYPH_EM, -gx / l, -gy / l]);
        }
      } else if (v > 0.012 && ((x * 7 + y * 13) % 5) === 0) {
        fill.push((x + 0.5) / TL.GLYPH_EM, (y + 0.5) / TL.GLYPH_EM, 0, 1);
      }
    }
    cand.sort((a, b) => b[0] - a[0]);
    const corner = [];
    for (const c of cand) {
      let near = false;
      for (let i = 0; i < corner.length && !near; i += 4) near = Math.hypot(corner[i] - c[1], corner[i + 1] - c[2]) < 0.07;
      if (!near) corner.push(c[1], c[2], c[3], c[4]);
      if (corner.length >= 4 * 16) break;
    }
    return { edge, fill, corner };
  }

  TL.Glyphs = class {
    constructor(glw) {
      this.glw = glw; this.cache = new Map();
      this.cv = document.createElement('canvas');
      this.ctx = this.cv.getContext('2d', { willReadFrequently: true });
    }
    fontString(font) {
      const f = TL.FONTS[font.family] || TL.FONTS.archivo;
      return (font.weight || 700) + ' ' + stretchWord(font.width || 100) + ' ' + (TL.GLYPH_EM * SS) + 'px ' + f.css;
    }
    measure(font, s) { this.ctx.font = this.fontString(font); return this.ctx.measureText(s); }

    // One letter as a piece (cached). Its cell is the letter's ink plus the spread all round.
    piece(ch, font) {
      const key = ch + '|' + JSON.stringify([font.family, font.weight, font.width, font.slant]);
      let p = this.cache.get(key);
      if (p !== undefined) return p;
      const ctx = this.ctx, px = TL.GLYPH_EM * SS, slant = font.slant || 0;
      const m = this.measure(font, ch);
      const pad = Math.ceil(TL.SPREAD * px) + 2;
      const asc = Math.ceil(m.actualBoundingBoxAscent) + 1, desc = Math.ceil(m.actualBoundingBoxDescent) + 1;
      const left = Math.ceil(m.actualBoundingBoxLeft + slant * desc) + 1, right = Math.ceil(m.actualBoundingBoxRight + slant * asc) + 1;
      if (m.actualBoundingBoxRight + m.actualBoundingBoxLeft <= 0) { this.cache.set(key, null); return null; }   // a space
      let w = left + right + 2 * pad, h = asc + desc + 2 * pad;
      w += w % SS; h += h % SS;
      this.cv.width = w; this.cv.height = h;
      ctx.font = this.fontString(font);
      const ox = pad + left, by = pad + asc;
      ctx.setTransform(1, 0, -slant, 1, slant * by, 0);
      ctx.fillStyle = '#fff'; ctx.textBaseline = 'alphabetic';
      ctx.fillText(ch, ox, by);
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      const img = ctx.getImageData(0, 0, w, h).data, alpha = new Uint8Array(w * h);
      for (let i = 0; i < w * h; i++) alpha[i] = img[i * 4 + 3];
      const pic = TL.distanceFromCoverage(alpha, w, h);
      p = {
        tex: this.glw.floatTexture(pic.w, pic.h, pic.d),
        cellW: pic.w / TL.GLYPH_EM, cellH: pic.h / TL.GLYPH_EM,
        originX: ox / px, originY: (h - by) / px,      // from the cell's bottom left to the pen's point on the baseline, em
        points: pointsOf(pic),
      };
      this.cache.set(key, p);
      return p;
    }

    // Where every letter sits, in em, inside a field with room round the words.
    // margin = [left, top, right, bottom] em.
    layout(text, font, margin) {
      // "tube": letters drawn as a neon bender makes them, from the tube font's centre lines
      if (font.family === 'tube' && this.tubefont) {
        this.tubeCache = this.tubeCache || new Map();
        return TL.layoutTubes(this.glw, this.tubefont, this.tubeCache, text, font, margin);
      }
      const px = TL.GLYPH_EM * SS;
      const lines = String(font.caps ? text.toUpperCase() : text).split('\n');
      const cap = this.measure(font, 'H').actualBoundingBoxAscent / px;
      const pitch = cap + (font.leading === undefined ? 0.42 : font.leading);
      const track = font.tracking || 0;
      const rows = lines.map((line) => {
        const chars = Array.from(line), xs = [];
        let acc = '';
        for (let i = 0; i < chars.length; i++) { xs.push(this.measure(font, acc).width / px + track * i); acc += chars[i]; }
        return { chars, xs, width: this.measure(font, acc).width / px + track * Math.max(chars.length - 1, 0) };
      });
      const boxW = Math.max(0.5, ...rows.map((r) => r.width)), boxH = cap + (rows.length - 1) * pitch;
      const [ml, mt, mr, mb] = margin;
      const fieldW = boxW + ml + mr, fieldH = boxH + mt + mb;
      const letters = [];
      rows.forEach((r, li) => {
        const baseline = mb + (rows.length - 1 - li) * pitch;
        const x0 = ml + (boxW - r.width) / 2;
        r.chars.forEach((ch, i) => {
          const piece = this.piece(ch, font);
          if (!piece) return;
          const penX = x0 + r.xs[i];
          letters.push({
            ch, piece, baseline, cap,
            x: penX - piece.originX, y: baseline - piece.originY,
            pivotX: penX + (piece.cellW - 2 * piece.originX) / 2, pivotY: baseline + cap / 2,
          });
        });
      });
      letters.forEach((l, i) => { l.index = i; l.count = letters.length; });
      return { letters, fieldW, fieldH, cap };
    }
  };

  // The emitter's point list for a laid-out line of letters: 64 x 192 points. Rows 0..63 lie on
  // the outline, 64..127 inside, 128..191 on corners. Each point: x, y (field em), outward x, y.
  TL.emitterPoints = (letters) => {
    const W = 64, H = 192, out = new Float32Array(W * H * 4);
    let seed = 0x9e3779b9;
    const rnd = () => { seed = (seed + 0x6d2b79f5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
    const fillHalf = (which, row0) => {
      const sizes = letters.map((l) => l.piece.points[which].length / 4);
      const total = sizes.reduce((a, b) => a + b, 0);
      for (let i = 0; i < W * 64; i++) {
        const o = (row0 * W + i) * 4;
        if (!total) { out[o] = -99; out[o + 1] = -99; out[o + 3] = 1; continue; }
        let pick = rnd() * total, li = 0;
        while (li < sizes.length - 1 && pick >= sizes[li]) { pick -= sizes[li]; li++; }
        const l = letters[li], pts = l.piece.points[which], k = Math.min(Math.floor(pick), sizes[li] - 1) * 4;
        out[o] = l.x + pts[k]; out[o + 1] = l.y + pts[k + 1]; out[o + 2] = pts[k + 2]; out[o + 3] = pts[k + 3];
      }
    };
    fillHalf('edge', 0); fillHalf('fill', 64); fillHalf('corner', 128);
    return { w: W, h: H, data: out };
  };
})();
