// TextLab look book: neon tubes from PATHS (the tube font, and later any pen stroke).
//
// A tube is a centre line. This bakes one tube into a piece: for every pixel, how far it is from
// the centre line (stored as the piece's distance, so a tube is an exact round rod), and whether
// the glass there is bare or painted black. The two ends are always painted: that is where the
// tube turns back into the case for its electrode.
//
// The app's twin does the same sums in Kotlin; nothing here needs a canvas.
(function () {
  const TL = (window.TL = window.TL || {});
  TL.TUBE_R = 0.03;      // em: the radius a baked tube has before the look thickens or thins it
  const PX = 144;        // pixels per em in a tube's piece (tubes are smooth, so this is plenty)
  const END = 0.03;      // em of each end painted black (less on a very short tube)

  // Round every corner of a centre line with a bend of the given radius (a torch bends, never folds).
  function bendCorners(pts, radius) {
    const out = [pts[0]];
    for (let i = 1; i < pts.length - 1; i++) {
      const a = pts[i - 1], b = pts[i], c = pts[i + 1];
      const la = Math.hypot(a[0] - b[0], a[1] - b[1]), lc = Math.hypot(c[0] - b[0], c[1] - b[1]);
      const t = Math.min(radius, la / 2, lc / 2);
      const p1 = [b[0] + ((a[0] - b[0]) / la) * t, b[1] + ((a[1] - b[1]) / la) * t];
      const p2 = [b[0] + ((c[0] - b[0]) / lc) * t, b[1] + ((c[1] - b[1]) / lc) * t];
      for (let k = 0; k <= 6; k++) {
        const u = k / 6, v = 1 - u;
        out.push([v * v * p1[0] + 2 * v * u * b[0] + u * u * p2[0], v * v * p1[1] + 2 * v * u * b[1] + u * u * p2[1]]);
      }
    }
    out.push(pts[pts.length - 1]);
    return out;
  }

  // One tube (flat x,y list in the font's cap units) into a piece. `scale` = em per cap unit.
  TL.bakeTube = (glw, flat, scale, bend, pointsOf) => {
    let pts = [];
    for (let i = 0; i < flat.length; i += 2) pts.push([flat[i] * scale, flat[i + 1] * scale]);
    pts = bendCorners(pts, bend * scale);
    const len = [0];
    for (let i = 1; i < pts.length; i++) len.push(len[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
    const total = len[len.length - 1];
    const pad = TL.SPREAD + TL.TUBE_R + 2 / PX;
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const p of pts) { x0 = Math.min(x0, p[0]); y0 = Math.min(y0, p[1]); x1 = Math.max(x1, p[0]); y1 = Math.max(y1, p[1]); }
    x0 -= pad; y0 -= pad; x1 += pad; y1 += pad;
    const w = Math.ceil((x1 - x0) * PX), h = Math.ceil((y1 - y0) * PX);
    const data = new Float32Array(w * h * 2), dOnly = new Float32Array(w * h);
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) {
      const px = x0 + (i + 0.5) / PX, py = y0 + (j + 0.5) / PX;
      let best = 1e9, at = 0;
      for (let s = 1; s < pts.length; s++) {
        const ax = pts[s - 1][0], ay = pts[s - 1][1], bx = pts[s][0] - ax, by = pts[s][1] - ay;
        const ll = bx * bx + by * by;
        let u = ll > 0 ? ((px - ax) * bx + (py - ay) * by) / ll : 0;
        u = u < 0 ? 0 : u > 1 ? 1 : u;
        const dx = px - ax - bx * u, dy = py - ay - by * u, dd = dx * dx + dy * dy;
        if (dd < best) { best = dd; at = len[s - 1] + (len[s] - len[s - 1]) * u; }
      }
      const d = Math.max(TL.TUBE_R - Math.sqrt(best), -TL.SPREAD);
      const fromEnd = Math.min(at, total - at);
      const bare = Math.min(1, Math.max(0, (fromEnd - Math.min(END, total * 0.16)) / 0.012));
      const k = j * w + i;
      data[k * 2] = d; data[k * 2 + 1] = bare; dOnly[k] = d;
    }
    // the piece's cell, in the letter's own em space (its origin is the letter's baseline, left edge)
    return {
      tex: glw.pairTexture(w, h, data), paint: true,
      cellW: w / PX, cellH: h / PX, cellX: x0, cellY: y0,
      points: pointsOfTube(dOnly, w, h),
    };
  };

  // outline and inside points for the emitter (same meaning as a letter's)
  function pointsOfTube(d, w, h) {
    const edge = [], fill = [], half = 0.6 / PX;
    for (let y = 1; y < h - 1; y++) for (let x = 1; x < w - 1; x++) {
      const v = d[y * w + x];
      if (Math.abs(v) < half) {
        const gx = d[y * w + x + 1] - d[y * w + x - 1], gy = d[(y + 1) * w + x] - d[(y - 1) * w + x], l = Math.hypot(gx, gy) || 1;
        edge.push((x + 0.5) / PX, (y + 0.5) / PX, -gx / l, -gy / l);
      } else if (v > 0.008 && ((x * 7 + y * 13) % 5) === 0) fill.push((x + 0.5) / PX, (y + 0.5) / PX, 0, 1);
    }
    return { edge, fill, corner: edge.slice(0, 64) };
  }

  // Lay a line of tube letters out. Returns the same shape as Glyphs.layout.
  TL.layoutTubes = (glw, font, cache, text, spec, margin) => {
    const cap = font.cap, gap = (spec.tracking === undefined ? font.gap : spec.tracking) * cap;
    const lines = String(text).toUpperCase().split('\n');
    const pitch = cap + (spec.leading === undefined ? 0.5 : spec.leading);
    const rows = lines.map((line) => {
      let x = 0; const items = [];
      for (const ch of Array.from(line)) {
        const g = font.glyphs[ch];
        if (!g) { x += cap * 0.4; continue; }
        items.push({ ch, g, x }); x += g.w * cap + gap;
      }
      return { items, width: Math.max(x - gap, 0) };
    });
    const boxW = Math.max(0.5, ...rows.map((r) => r.width)), boxH = cap + (rows.length - 1) * pitch;
    const [ml, mt, mr, mb] = margin;
    const letters = [];
    let index = 0;
    const count = rows.reduce((n, r) => n + r.items.length, 0);
    rows.forEach((r, li) => {
      const baseline = mb + (rows.length - 1 - li) * pitch, left = ml + (boxW - r.width) / 2;
      for (const it of r.items) {
        it.g.t.forEach((flat, order) => {
          const key = it.ch + ':' + order;
          let piece = cache.get(key);
          if (!piece) { piece = TL.bakeTube(glw, flat, cap, font.bend); cache.set(key, piece); }
          letters.push({
            ch: it.ch, piece, baseline, cap, order, index, count,
            x: left + it.x + piece.cellX, y: baseline + piece.cellY,
            pivotX: left + it.x + (it.g.w * cap) / 2, pivotY: baseline + cap / 2,
          });
        });
        index++;
      }
    });
    return { letters, fieldW: boxW + ml + mr, fieldH: boxH + mt + mb, cap, tubes: true };
  };
})();
