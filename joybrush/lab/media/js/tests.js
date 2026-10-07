// tests.js — fixed stroke sheets with exact pressure and tilt, so a look can be judged as an image on the
// PC and on the phone (adb swipes cannot vary pressure). Coordinates are doc px, y UP; angles in degrees;
// az = the direction the barrel leans, doc space, counter-clockwise from +x.

const D2R = Math.PI / 180;

// A stroke: path(u) → [x, y] for u in 0..1, plus per-u pressure/tilt/az; mm per second sets the timing.
function stroke(tool, lenMm, path, { p, tilt, az }, speedMm = 70, pxPerMm = 20) {
  const n = Math.max(2, Math.ceil(lenMm / (speedMm * 0.004)));   // 250 Hz pen
  const samples = [];
  for (let i = 0; i <= n; i++) {
    const u = i / n;
    const [x, y] = path(u);
    samples.push({ x, y, p: val(p, u), tilt: val(tilt, u) * D2R, az: val(az, u) * D2R, t: i * 4 });
  }
  return { tool, samples };
}
const val = (v, u) => (typeof v === 'function' ? v(u) : v);
const line = (x0, y0, x1, y1) => u => [x0 + (x1 - x0) * u, y0 + (y1 - y0) * u];
// Back-and-forth hatching over a box, as a hand shades.
function zigzag(x0, y0, w, h, passes) {
  return u => {
    const f = u * passes, k = Math.floor(Math.min(f, passes - 1e-6)), t = f - k;
    const x = k % 2 === 0 ? x0 + w * t : x0 + w * (1 - t);
    const y = y0 + h * (k + t) / passes;
    return [x, y];
  };
}
const ramp = (a, b) => u => a + (b - a) * u;
const hill = (a, b) => u => a + (b - a) * Math.sin(Math.PI * u);
const MM = 20;

const bez = (a, b, c, d) => u => {
  const v = 1 - u;
  return [v * v * v * a[0] + 3 * v * v * u * b[0] + 3 * v * u * u * c[0] + u * u * u * d[0],
          v * v * v * a[1] + 3 * v * v * u * b[1] + 3 * v * u * u * c[1] + u * u * u * d[1]];
};
const sstep = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); };
// A hand's pressure through a stroke: lands, holds, eases off at the end.
const env = (peak, tail = 0.15) => u => peak * sstep(0, 0.12, u) * (1 - (1 - tail) * sstep(0.7, 1, u));
const zig = (x0, x1, y, amp, n) => u => {
  const x = x0 + (x1 - x0) * u, ph = u * n * 2;
  const k = Math.floor(ph), t = ph - k;
  return [x, y + amp * (k % 2 === 0 ? t : 1 - t) * 2 - amp];
};

export const SHEETS = {
  // Wetness (owner, 2026-10-07: Expresii's drop and napkin): the all-round brush at dry, damp, wet, loaded and
  // runny, left to right; then the paper tilted toward the bottom for ?tiltS= seconds (runny paint should run).
  runny: {
    size: [1600, 1000],
    paper: 'cold_press',
    strokes() {
      const q = new URLSearchParams(location.search), MM = 20, L = -45;
      const s = [];
      for (let i = 0; i < 5; i++) {
        const st = { ...stroke('All-round', 30, bez([(6 + i * 15) * MM, 44 * MM], [(12 + i * 15) * MM, 40 * MM], [(4 + i * 15) * MM, 30 * MM], [(10 + i * 15) * MM, 22 * MM]), { p: env(0.85, 0.5), tilt: 35, az: L }, 40), kind: 'wet', color: [0.62, 0.22, 0.18], wetness: i };
        s.push(st);
      }
      s.push({ tiltDeg: Number(q.get('tiltDeg') || 25), tiltDir: [0, -1] }, { wait: Number(q.get('tiltS') || 15) }, { tiltDeg: 0, tiltDir: [0, 0] }, { wait: 120 });
      return s;
    },
  },
  // Dabs and stroke ends (owner, 2026-10-07): a light dab must be a dot, not straight lines squirting out;
  // tails only grow with travel; splayed hairs come in late; no straight-cut starts or ends. Each row: three
  // dabs (light, medium, firm, with a hand's jitter and no travel), then a short curved stroke, then a longer
  // stroke that lightens toward its end. Rows: Oil round, All-round, Oil flat, Fan blender.
  dabs: {
    size: [1600, 1200],
    paper: 'canvas_linen',
    strokes() {
      const s = [], MM = 20, L = -60;
      const paste = (tool, color, st) => ({ ...st, kind: 'paste', tool, color });
      const dab = (x, y, p) => {
        const n = 40, samples = [];
        for (let i = 0; i <= n; i++) {
          const u = i / n, j = 0.04 * MM;
          samples.push({ x: x + j * Math.sin(i * 2.7), y: y + j * Math.cos(i * 3.1), p: p * Math.sin(Math.PI * u), tilt: 25 * Math.PI / 180, az: L * Math.PI / 180, t: i * 6 });
        }
        return { tool: 'x', samples };
      };
      const rows = [['Oil round', [0.70, 0.18, 0.12]], ['All-round', [0.14, 0.30, 0.55]], ['Oil flat', [0.85, 0.62, 0.20]], ['Fan blender', [0.25, 0.45, 0.30]]];
      rows.forEach(([tool, c], r) => {
        const y = (52 - r * 13) * MM;
        [0.2, 0.45, 0.8].forEach((p, i) => s.push(paste(tool, c, dab((6 + i * 8) * MM, y, p))));
        s.push(paste(tool, c, stroke(tool, 10, bez([30 * MM, y - 3 * MM], [33 * MM, y + 4 * MM], [37 * MM, y + 4 * MM], [40 * MM, y - 2 * MM]), { p: env(0.4, 0.3), tilt: 25, az: L }, 50)));
        s.push(paste(tool, c, stroke(tool, 30, bez([46 * MM, y], [56 * MM, y + 5 * MM], [64 * MM, y - 5 * MM], [76 * MM, y]), { p: u => 0.85 * (1 - 0.8 * u) * Math.min(1, u * 8), tilt: 30, az: L }, 60)));
      });
      return s;
    },
  },
  // Isolated side strokes for measuring the fade (2026-10-07): pressures 0.15 0.3 0.5 0.75 1.0, at tilt
  // ?t= (fraction of the pen range), 34 mm apart, barrel to the left so the tip edge is the right edge.
  sidepro: {
    size: [3600, 900],
    paper: 'drawing_tooth',
    strokes() {
      const q = new URLSearchParams(location.search), MM = 20, t = Number(q.get('t') || 0.85);
      return [0.15, 0.3, 0.5, 0.75, 1.0].map((p, i) =>
        stroke('Proto', 30, line((30 + i * 34) * MM, 38 * MM, (30 + i * 34) * MM, 8 * MM), { p, tilt: t * 90, az: 180 }, 80));
    },
  },
  // Holes check (owner, 2026-10-06: holes in the paint that ignore new strokes): lumpy knife paint with
  // dips, then a loaded round and the all-round brush painted over it in another colour. Nothing should
  // stay uncovered where a loaded brush passed. Then the all-round brush: hairline to full belly.
  holes: {
    size: [1400, 1000],
    paper: 'canvas_linen',
    strokes() {
      const s = [], MM = 20;
      const paste = (tool, color, len, path, o, speed = 60, dirty = false) => ({ ...stroke(tool, len, path, o, speed), kind: 'paste', color, dirty });
      for (let k = 0; k < 5; k++)
        s.push(paste('Palette knife', [0.9, 0.85, 0.75], 40, line(4 * MM, (46 - k * 7) * MM, 40 * MM, (44 - k * 7) * MM), { p: ramp(0.1, 0.5), tilt: 66, az: 90 }, 45));
      for (let k = 0; k < 4; k++)
        s.push(paste('Scraper', [0, 0, 0], 30, line((8 + k * 8) * MM, 46 * MM, (12 + k * 8) * MM, 14 * MM), { p: 0.6, tilt: 30, az: 0 }, 50));
      s.push(paste('Oil round', [0.75, 0.15, 0.12], 40, line(6 * MM, 38 * MM, 38 * MM, 30 * MM), { p: 0.8, tilt: 30, az: -60 }));
      s.push(paste('All-round', [0.12, 0.35, 0.6], 40, line(6 * MM, 24 * MM, 38 * MM, 18 * MM), { p: 0.9, tilt: 30, az: -60 }));
      // all-round: pressure ramps 0 → 1 → 0 along an S curve, then tilted
      s.push(paste('All-round', [0.15, 0.15, 0.18], 60, bez([44 * MM, 44 * MM], [70 * MM, 44 * MM], [44 * MM, 26 * MM], [68 * MM, 24 * MM]), { p: hill(0.02, 1.0), tilt: 15, az: -60 }, 70));
      s.push(paste('All-round', [0.15, 0.15, 0.18], 30, line(44 * MM, 12 * MM, 68 * MM, 12 * MM), { p: hill(0.02, 0.7), tilt: 60, az: -60 }, 70));
      [0.05, 0.15, 0.3].forEach((p, i) => s.push(paste('All-round', [0.15, 0.15, 0.18], 18, line((46 + i * 7) * MM, 7 * MM, (50 + i * 7) * MM, 2 * MM), { p, tilt: 15, az: -60 }, 60)));
      return s;
    },
  },
  // Blades (owner, 2026-10-06): a thick blue knife field, then the scraper: upright thin gouges at rising
  // pressure, a tilted angled scrape, a flat wide scrape, wavy scrapes, and light shaves of the peaks.
  blade: {
    size: [1400, 1100],
    paper: 'canvas_linen',
    strokes() {
      const s = [], MM = 20;
      const paste = (tool, color, len, path, o, speed = 60, dirty = false) => ({ ...stroke(tool, len, path, o, speed), kind: 'paste', color, dirty });
      const BL = [0.20, 0.32, 0.72];
      for (let k = 0; k < 7; k++)
        s.push(paste('Palette knife', BL, 60, line(4 * MM, (50 - k * 6.5) * MM, 66 * MM, (50 - k * 6.5) * MM), { p: 0.35, tilt: 62, az: 90 }, 45));
      // upright gouges: same path, pressure 0.2 → 1 (left to right)
      [0.2, 0.45, 0.7, 1.0].forEach((p, i) => s.push(paste('Scraper', BL, 20, line((8 + i * 6) * MM, 50 * MM, (11 + i * 6) * MM, 32 * MM), { p, tilt: 25, az: 0 }, 50)));
      // tilted, edge along the lean (az 90° = up), dragged sideways: an angled scrape, deeper at the point
      s.push(paste('Scraper', BL, 30, line(36 * MM, 40 * MM, 62 * MM, 40 * MM), { p: 1.0, tilt: 70, az: 90 }, 50));
      // flat: an even wide scrape
      s.push(paste('Scraper', BL, 30, line(36 * MM, 22 * MM, 62 * MM, 22 * MM), { p: 1.0, tilt: 86, az: 90 }, 50));
      // wavy scrape pushing paint
      s.push(paste('Scraper', BL, 40, u => [(6 + 26 * u) * MM, (16 + 4 * Math.sin(u * 9)) * MM], { p: 0.8, tilt: 72, az: 90 }, 50));
      // light shaves: only the tops of the peaks
      s.push(paste('Scraper', BL, 30, line(36 * MM, 8 * MM, 62 * MM, 8 * MM), { p: 0.25, tilt: 86, az: 90 }, 50));
      return s;
    },
  },
  // Back and forth (owner, 2026-10-06): light passes over the same patch build an even, soft dusting;
  // a firm pass piles dark graphite. Left: 1, 3 and 6 light passes; right: one firm pass, then one light.
  passes: {
    size: [1600, 700],
    paper: 'drawing_tooth',
    strokes() {
      const s = [], L = 200, MM = 20;
      const patch = (x0, n, p, tilt) => { for (let k = 0; k < n; k++) s.push(stroke('Proto', 90, zig(x0, x0 + 14 * MM, 350 + (k % 2) * 6, 120, 7), { p: env(p, 0.6), tilt, az: L }, 120)); };
      patch(60, 1, 0.22, 78); patch(420, 3, 0.22, 78); patch(780, 6, 0.22, 78);
      patch(1140, 1, 0.8, 74); patch(1140, 1, 0.2, 80);
      return s;
    },
  },
  // Tilt ladder (owner, 2026-10-06: test the slight angles too, not just flat): the same pressure from upright
  // to lying flat, vertical strokes, barrel leaning straight left so the side lies across the stroke.
  tiltladder: {
    size: [4400, 1300],
    paper: 'drawing_tooth',
    strokes() {
      const q = new URLSearchParams(location.search), MM = 20;
      const P = Number(q.get('p') || 0.5);
      const T = [30, 45, 55, 62, 68, 72, 76, 80, 84, 90];
      return T.map((t, i) => stroke('Proto', 50, line((6 + i * 21) * MM, 60 * MM, (6 + i * 21) * MM, 10 * MM), { p: P, tilt: t, az: 180 }, 80));
    },
  },
  // Like the owner's Proko close-up (2026-10-06, image 4): four tall side strokes drawn top to bottom with a
  // right-handed lean, then switchback scribbles. Same pencil throughout, only pressure and tilt change.
  prokoside: {
    size: [1000, 2000],
    paper: 'drawing_tooth',
    strokes() {
      const s = [], L = 200;   // barrel to the lower left of the hand: the tip edge is on the right
      const vert = (x, p, tilt, lenPx = 1100) => stroke('Proto', lenPx / 20, u => [x + 30 * Math.sin(u * 2.4), 1900 - u * lenPx], { p, tilt, az: L }, 90);
      s.push(vert(140, env(0.75, 0.6), 76));      // 3: firm, dark tip edge
      s.push(vert(390, env(0.45, 0.6), 80));      // 2: wide, softer
      s.push(vert(630, env(0.22, 0.6), 79));      // 1: light, soft
      s.push(vert(870, env(0.6, 0.5), 75));       // 4: curved, darker edge
      s.push(stroke('Proto', 120, zig(80, 700, 560, 140, 3.5), { p: env(0.55, 0.5), tilt: 74, az: L }, 110));
      s.push(stroke('Proto', 90, zig(60, 760, 240, 90, 6), { p: env(0.9, 0.5), tilt: 30, az: L }, 110));
      return s;
    },
  },
  // Pressure ladders for measuring (2026-10-06): upright lines and side strokes, vertical, 30 mm long.
  // Side strokes lean straight left (az 180°) so the side lies across the stroke: width = contact length.
  ladder: {
    size: [3200, 1500],
    strokes() {
      const s = [], MM = 20;
      const P = [0.03, 0.06, 0.1, 0.2, 0.35, 0.5, 0.7, 1.0];
      P.forEach((p, i) => s.push(stroke('Proto', 30, line((8 + i * 12) * MM, 70 * MM, (8 + i * 12) * MM, 40 * MM), { p, tilt: 6, az: 180 }, 70)));
      const PS = [0.05, 0.15, 0.35, 0.6, 1.0];
      const sideTilt = Number(new URLSearchParams(location.search).get('sidetilt') || 84);
      PS.forEach((p, i) => s.push(stroke('Proto', 30, line((30 + i * 30) * MM, 34 * MM, (30 + i * 30) * MM, 4 * MM), { p, tilt: sideTilt, az: 180 }, 70)));
      return s;
    },
  },
  // The owner's impasto references (2026-10-06): thick knife loads with colour variation and lumps, a
  // scraped-off area where the canvas takes over, scraper lines cut through, a flat brush pulled out of the
  // edge into broken bristle fingers, and paint piled where strokes lift.
  impasto: {
    size: [1400, 1100],
    paper: 'canvas_linen',
    strokes() {
      const s = [], L = -60, MM = 20;
      const paste = (tool, color, len, path, o, speed = 60, dirty = false) => ({ ...stroke(tool, len, path, o, speed), kind: 'paste', color, dirty });
      const BL = [0.20, 0.32, 0.72];
      for (let k = 0; k < 4; k++)
        s.push(paste('Palette knife', BL, 40, bez([(8 + k * 3) * MM, (48 - k * 7) * MM], [(22 + k * 2) * MM, (52 - k * 7) * MM], [(36 + k) * MM, (40 - k * 7) * MM], [(50 + k * 2) * MM, (44 - k * 7) * MM]), { p: 0.55, tilt: 50, az: L }, 45));
      // scrape a band off, hard: the canvas weave should come up through the thin paint
      s.push(paste('Palette knife', BL, 30, line(14 * MM, 24 * MM, 44 * MM, 27 * MM), { p: 1.0, tilt: 50, az: L }, 45, true));
      // scraper lines through the wet paint
      s.push(paste('Scraper', BL, 40, bez([12 * MM, 46 * MM], [24 * MM, 36 * MM], [34 * MM, 30 * MM], [48 * MM, 20 * MM]), { p: 0.8, tilt: 30, az: L }, 60));
      s.push(paste('Scraper', BL, 40, bez([20 * MM, 50 * MM], [30 * MM, 42 * MM], [42 * MM, 40 * MM], [54 * MM, 30 * MM]), { p: 0.8, tilt: 30, az: L }, 60));
      // a clean flat brush pulled out of the paint's edge to the right: broken bristle fingers
      for (let k = 0; k < 3; k++)
        s.push(paste('Oil flat', BL, 30, line(50 * MM, (40 - k * 6) * MM, 66 * MM, (38 - k * 6) * MM), { p: ramp(0.8, 0.2), tilt: 30, az: L }, 70, true));
      // colour: orange and yellow knife strokes pulled over each other while wet
      s.push(paste('Palette knife', [0.95, 0.55, 0.15], 30, line(8 * MM, 10 * MM, 34 * MM, 12 * MM), { p: 0.5, tilt: 50, az: L }, 45));
      s.push(paste('Palette knife', [0.98, 0.85, 0.20], 30, line(20 * MM, 6 * MM, 46 * MM, 9 * MM), { p: 0.5, tilt: 50, az: L }, 45));
      return s;
    },
  },
  roundtwo: {
    size: [800, 400], paper: 'canvas_linen',
    strokes() {
      const st = { ...stroke('Oil round', 30, line(100, 200, 700, 200), { p: 1.0, tilt: 30, az: -60 }, 60), kind: 'paste', color: [0.14, 0.2, 0.58], belly: [0.95, 0.88, 0.70] };
      const q = new URLSearchParams(location.search);
      if (q.get('cut')) st.samples = st.samples.slice(0, Number(q.get('cut')));
      return [st];
    },
  },
  // Oil and knife (2026-10-06): wet-in-wet strokes, a clean brush pulled across them (it picks the colours
  // up and streaks them on), the same brush pressed harder (its belly shows what wicked up), a knife smear,
  // a round stroke, and a fan blender softening an edge.
  oil: {
    size: [1400, 1000],
    paper: 'canvas_linen',
    strokes() {
      const s = [], L = -60, MM = 20;
      const paste = (tool, color, len, path, o, speed = 60, dirty = false) => ({ ...stroke(tool, len, path, o, speed), kind: 'paste', color, dirty });
      const OC = [0.80, 0.58, 0.22], RD = [0.78, 0.14, 0.10], UB = [0.14, 0.20, 0.58], WH = [0.95, 0.94, 0.90], GR = [0.10, 0.42, 0.32];
      [OC, RD, UB].forEach((c, i) =>
        s.push(paste('Oil flat', c, 40, line((6 + i * 7) * MM, 44 * MM, (6 + i * 7) * MM, 14 * MM), { p: 0.7, tilt: 30, az: L })));
      s.push(paste('Oil flat', WH, 40, line(3 * MM, 38 * MM, 30 * MM, 36 * MM), { p: 0.6, tilt: 30, az: L }));
      s.push(paste('Oil flat', WH, 40, line(3 * MM, 26 * MM, 30 * MM, 24 * MM), { p: 1.0, tilt: 45, az: L }, 60, true));
      s.push(paste('Palette knife', WH, 30, line(36 * MM, 40 * MM, 64 * MM, 34 * MM), { p: 0.8, tilt: 50, az: L }, 50));
      s.push(paste('Palette knife', GR, 26, line(40 * MM, 26 * MM, 62 * MM, 30 * MM), { p: 0.6, tilt: 50, az: L }, 50));
      s.push(paste('Oil round', RD, 40, bez([36 * MM, 18 * MM], [44 * MM, 6 * MM], [54 * MM, 22 * MM], [64 * MM, 8 * MM]), { p: env(1.0, 0.3), tilt: 25, az: L }));
      s.push(paste('Fan blender', WH, 30, zigzag(4 * MM, 10 * MM, 26 * MM, 3 * MM, 3), { p: 0.35, tilt: 40, az: L }, 80, false));
      // Two-colour load (tip red, belly cream): a light stroke shows the tip, pressing brings the belly in.
      const tw = paste('Oil flat', RD, 30, line(36 * MM, 47 * MM, 50 * MM, 47 * MM), { p: 0.7, tilt: 30, az: L });
      tw.belly = [0.95, 0.88, 0.70];
      const tr = paste('Oil round', UB, 30, line(52 * MM, 47 * MM, 67 * MM, 47 * MM), { p: ramp(0.1, 1.0), tilt: 30, az: L });
      tr.belly = [0.95, 0.88, 0.70];
      s.push(tw, tr);
      return s;
    },
  },
  // Tilt: three washes, then the paper tilted 20° (toward the bottom) for 12 s, then levelled to dry.
  tilt: {
    size: [1200, 900],
    paper: 'cold_press',
    strokes() {
      const s = [], L = -45, MM = 20;
      const wet = (tool, color, len, path, o, speed = 45) => ({ ...stroke(tool, len, path, o, speed), kind: 'wet', color });
      s.push(wet('Wash', [0.22, 0.38, 0.75], 60, zigzag(4 * MM, 26 * MM, 22 * MM, 12 * MM, 3), { p: 0.9, tilt: 30, az: L }, 60));
      s.push(wet('Wash', [0.85, 0.35, 0.45], 60, zigzag(30 * MM, 26 * MM, 22 * MM, 12 * MM, 3), { p: 0.9, tilt: 30, az: L }, 60));
      s.push({ tiltDeg: 20, tiltDir: [0, -1] }, { wait: 12 }, { tiltDeg: 0, tiltDir: [0, 0] }, { wait: 150 });
      return s;
    },
  },
  // The owner's wet behaviours (2026-10-06): wet meets wet softly, wet meets dry with a hard pooled edge,
  // doubling back over a wet stroke mingles, water dropped into a half-dry wash blooms.
  mingle: {
    size: [1400, 1100],
    paper: 'cold_press',
    strokes() {
      const s = [], L = -45, MM = 20;
      const wet = (tool, color, len, path, o, speed = 45) => ({ ...stroke(tool, len, path, o, speed), kind: 'wet', color });
      const UB = [0.22, 0.38, 0.75], RS = [0.85, 0.35, 0.45], YO = [0.90, 0.72, 0.25], TE = [0.12, 0.48, 0.52];
      // 1. wet into wet: blue wash, red touching it at once
      s.push(wet('Wash', UB, 60, zigzag(4 * MM, 40 * MM, 24 * MM, 12 * MM, 3), { p: 0.85, tilt: 30, az: L }, 60));
      s.push(wet('Wash', RS, 60, zigzag(26 * MM, 40 * MM, 22 * MM, 12 * MM, 3), { p: 0.85, tilt: 30, az: L }, 60));
      // 2. wet against dry: yellow wash, left to dry, then teal laid half over it
      s.push(wet('Wash', YO, 60, zigzag(4 * MM, 4 * MM, 24 * MM, 12 * MM, 3), { p: 0.85, tilt: 30, az: L }, 60));
      s.push({ wait: 120 });
      s.push(wet('Wash', TE, 60, zigzag(20 * MM, 8 * MM, 24 * MM, 12 * MM, 3), { p: 0.85, tilt: 30, az: L }, 60));
      // 3. doubled back over itself while wet
      s.push(wet('Round', RS, 80, zigzag(50 * MM, 4 * MM, 16 * MM, 16 * MM, 4), { p: 0.8, tilt: 30, az: L }, 60));
      // 4. bloom: a blue wash left to half-dry, then clear water touched into it
      s.push(wet('Wash', UB, 60, zigzag(50 * MM, 32 * MM, 16 * MM, 18 * MM, 3), { p: 0.85, tilt: 30, az: L }, 60));
      s.push({ wait: 25 });
      s.push(wet('Water', UB, 8, line(56 * MM, 42 * MM, 60 * MM, 41 * MM), { p: env(0.9, 0.6), tilt: 20, az: L }, 25));
      s.push({ wait: 160 });
      return s;
    },
  },
  // Like the owner's reference swatches (2026-10-06): one loaded brush per stroke, left to right, the hand
  // easing in and out; plus a long stroke that runs dry, and a stroke doubled back over itself.
  swatch: {
    size: [1400, 1500],
    paper: 'cold_press',
    strokes() {
      const s = [], L = -45, MM = 20;
      const wet = (tool, color, len, path, o, speed = 45) => ({ ...stroke(tool, len, path, o, speed), kind: 'wet', color });
      const cols = [[0.52, 0.66, 0.50], [0.76, 0.62, 0.32], [0.86, 0.62, 0.62], [0.78, 0.42, 0.30], [0.55, 0.57, 0.60]];
      cols.forEach((c, i) => {
        const y = (68 - i * 11) * MM;
        const wob = u => Math.sin(u * 7 + i) * 0.6 * MM;
        s.push(wet('Wash', c, 52, u => [(8 + 50 * u) * MM, y + wob(u)], { p: env(0.85, 0.5), tilt: 35, az: L }, 55));
      });
      // A long round stroke that runs out of water: dark pooled start, fading, breaking up at the end.
      s.push(wet('Round', [0.20, 0.42, 0.78], 110, u => [(66 + 0 * u) * MM, (68 - 60 * u) * MM], { p: env(0.9, 0.6), tilt: 30, az: L }, 45));
      // Doubled back: down, then back up beside and over the first pass while it is still wet.
      s.push(wet('Round', [0.10, 0.45, 0.50], 60, zigzag(8 * MM, 4 * MM, 50 * MM, 10 * MM, 2), { p: 0.8, tilt: 30, az: L }, 50));
      s.push({ wait: 150 });
      return s;
    },
  },
  // Fast flicks as a real pen reports them (few points per stroke). Left: points joined straight (the
  // faceting the owner saw). Right: the same points through the spline. Same samples, nothing smoothed.
  flick: {
    size: [1000, 600],
    strokes() {
      const s = [];
      const sparse = (cx, cy, r, n, spline) => {
        const st = stroke('Proto', 1, u => [cx + r * Math.cos(u * 5.6), cy + r * Math.sin(u * 5.6)], { p: env(0.9, 0.4), tilt: 8, az: -45 });
        st.samples = st.samples.filter((_, i) => i % Math.ceil(st.samples.length / n) === 0);
        st.spline = spline;
        return st;
      };
      const sCurve = (x0, spline) => {
        const st = stroke('Proto', 1, bez([x0, 120], [x0 + 260, 120], [x0 - 60, 520], [x0 + 260, 500]), { p: env(1, 0.3), tilt: 8, az: -45 });
        st.samples = st.samples.filter((_, i) => i % Math.ceil(st.samples.length / 10) === 0);
        st.spline = spline;
        return st;
      };
      s.push(sparse(250, 300, 150, 14, false), sparse(750, 300, 150, 14, true));
      s.push(sCurve(80, false), sCurve(580, true));
      return s;
    },
  },
  // One stroke left to dry for ?wait= seconds: the drying sequence frame by frame.
  drop: {
    size: [700, 400],
    paper: 'cold_press',
    strokes() {
      const q = new URLSearchParams(location.search), UB = [0.22, 0.30, 0.72];
      const wet = (tool, color, len, path, o, speed = 40) => ({ ...stroke(tool, len, path, o, speed), kind: 'wet', color });
      return [wet('Round', UB, 24, line(80, 200, 600, 220), { p: env(1.0, 0.5), tilt: 20, az: -45 }), { wait: Number(q.get('wait') || 0) }];
    },
  },
  // Watercolour (2026-10-06): the classic behaviours, each from the same engine with only brush/paint changed.
  wash: {
    size: [1400, 1000],
    paper: 'cold_press',
    strokes() {
      const s = [], L = -45, MM = 20;
      const UB = [0.22, 0.30, 0.72], SI = [0.62, 0.30, 0.16], YE = [0.96, 0.80, 0.18], AL = [0.72, 0.10, 0.22];
      const wet = (tool, color, len, path, o, speed = 40) => ({ ...stroke(tool, len, path, o, speed), kind: 'wet', color });
      // A: single round strokes, light to firm
      [0.3, 0.6, 1.0].forEach((p, i) =>
        s.push(wet('Round', UB, 18, line((4 + i * 22) * MM, 46 * MM, (20 + i * 22) * MM, 44 * MM), { p: env(p, 0.4), tilt: 20, az: L })));
      // B: a flat wash in overlapping bands
      for (let k = 0; k < 4; k++)
        s.push(wet('Wash', UB, 26, line(4 * MM, (38 - k * 2.6) * MM, 30 * MM, (38 - k * 2.6) * MM), { p: 0.8, tilt: 30, az: L }, 45));
      // C: wet-in-wet: clear water, then colour dropped in while it is wet
      s.push(wet('Water', UB, 160, zigzag(38 * MM, 28 * MM, 26 * MM, 12 * MM, 6), { p: 0.85, tilt: 30, az: L }, 70));
      s.push(wet('Round', AL, 14, bez([44 * MM, 34 * MM], [50 * MM, 38 * MM], [56 * MM, 30 * MM], [60 * MM, 35 * MM]), { p: env(0.9, 0.5), tilt: 20, az: L }));
      // D: glaze: yellow dries, then blue crosses it
      s.push(wet('Wash', YE, 60, zigzag(4 * MM, 14 * MM, 18 * MM, 8 * MM, 3), { p: 0.8, tilt: 30, az: L }, 45));
      s.push({ wait: 70 });
      s.push(wet('Round', UB, 26, line(2 * MM, 15 * MM, 26 * MM, 23 * MM), { p: env(1.0, 0.5), tilt: 25, az: L }));
      // E: dry brush: a nearly empty brush dragged fast over the tooth
      s.push(wet('Dry brush', SI, 30, line(30 * MM, 18 * MM, 64 * MM, 20 * MM), { p: 0.9, tilt: 45, az: L }, 120));
      // F: bloom: a blue wash, left to half-dry, then water touched in
      s.push(wet('Wash', UB, 60, zigzag(32 * MM, 3 * MM, 20 * MM, 9 * MM, 3), { p: 0.85, tilt: 30, az: L }, 45));
      s.push({ wait: 9 });
      s.push(wet('Water', UB, 6, line(40 * MM, 7 * MM, 44 * MM, 8 * MM), { p: env(0.9, 0.5), tilt: 20, az: L }, 30));
      s.push({ wait: 90 });
      return s;
    },
  },
  // The owner's scratch pad (2026-10-06): many overlapping scribbles in one patch, mixed pressure and tilt.
  // Layering must only ever darken toward the tooth's limit, never whiten or block up.
  scribble: {
    size: [700, 500],
    strokes() {
      const s = [], L = -45;
      for (let k = 0; k < 24; k++) {
        const y0 = 120 + (k % 6) * 30, tilt = [8, 30, 52, 64][k % 4], p = [0.35, 0.7, 1.0, 0.55][(k >> 2) % 4];
        s.push(stroke('Proto', 120, zig(120 + (k % 3) * 20, 580, y0 + 80, 70 + (k % 5) * 10, 5 + (k % 3)), { p: env(p, 0.4), tilt, az: L }, 120));
      }
      return s;
    },
  },
  // Laid out like the owner's Infinite Painter "Proto pencil" screenshot (2026-10-06): every mark is the
  // same pencil with no slider touched; only pressure, tilt and lean change.
  proto: {
    size: [1000, 1500],
    strokes() {
      const s = [], L = -45;   // right-handed lean: barrel toward lower right
      s.push(stroke('Proto', 45, bez([185, 1245], [150, 1370], [420, 1080], [720, 1330]), { p: env(0.95, 0.1), tilt: 8, az: L }, 90));
      s.push(stroke('Proto', 50, bez([125, 1085], [60, 880], [480, 1000], [800, 1160]), { p: env(1.0, 0.25), tilt: 6, az: L }, 90));
      s.push(stroke('Proto', 60, bez([60, 930], [330, 660], [520, 1120], [780, 760]), { p: env(0.5, 0.3), tilt: 52, az: L }, 80));
      s.push(stroke('Proto', 70, bez([40, 520], [420, 300], [200, 980], [680, 560]), { p: env(0.65, 0.4), tilt: 66, az: L }, 80));
      s.push(stroke('Proto', 70, zig(260, 760, 300, 120, 3.5), { p: env(0.75, 0.4), tilt: 67, az: L }, 90));
      s.push(stroke('Proto', 40, zig(740, 900, 170, 70, 4), { p: env(0.9, 0.5), tilt: 64, az: L }, 90));
      [0.05, 0.12, 0.25, 0.45, 0.7, 1.0].forEach((p, i) =>
        s.push(stroke('Proto', 12, line(750, 1010 - i * 34, 900 + i * 12, 960 - i * 40), { p: env(p, 0.4), tilt: 7, az: L }, 90)));
      s.push(stroke('Proto', 50, bez([60, 70], [160, 300], [300, 160], [440, 420]), { p: env(0.12, 0.6), tilt: 70, az: L }, 60));
      [0.15, 0.6, 0.9].forEach((p, i) =>
        s.push(stroke('Proto', 5, line(110, 470 - i * 30, 175, 430 - i * 30), { p: env(p, 0.3), tilt: 8, az: L }, 70)));
      // Soft shading: a tilted sweep that eases from firm to a feather touch fans out wide and faint.
      s.push(stroke('Proto', 45, bez([520, 1460], [640, 1400], [760, 1420], [960, 1300]), { p: ramp(0.55, 0.04), tilt: 68, az: L }, 70));
      return s;
    },
  },
  pencil: {
    size: [1800, 1500],
    rows: [
      'HB upright: pressure 0 → 1 (width grows a little, darkness and grain fill a lot)',
      'HB upright pressure ladder 0.1 · 0.25 · 0.4 · 0.6 · 0.8 · 1.0',
      'HB pressure 0.5, tilt upright → 75° → upright (angle sets size)',
      'HB side shading, tilt 72°: light (0.3) and firm (0.8)',
      'Woodless 8B, nearly flat (76°): light and firm',
      'Soft 6B lines, then a light woodless pass across them (subtle smudge)',
    ],
    strokes(pxPerMm = MM) {
      const s = [];
      const top = 1500 - 6 * MM;
      const row = i => top - i * 12 * MM;
      s.push(stroke('HB', 75, line(5 * MM, row(0), 85 * MM, row(0)), { p: ramp(0.02, 1), tilt: 4, az: -60 }));
      [0.1, 0.25, 0.4, 0.6, 0.8, 1.0].forEach((p, i) =>
        s.push(stroke('HB', 9, line((8 + i * 13) * MM, row(1) + 4 * MM, (12 + i * 13) * MM, row(1) - 5 * MM), { p, tilt: 4, az: -60 })));
      s.push(stroke('HB', 75, line(5 * MM, row(2), 85 * MM, row(2)), { p: 0.5, tilt: hill(2, 75), az: -70 }));
      s.push(stroke('HB', 400, zigzag(6 * MM, row(3) - 4 * MM, 34 * MM, 8 * MM, 14), { p: 0.3, tilt: 72, az: -65 }, 120));
      s.push(stroke('HB', 400, zigzag(48 * MM, row(3) - 4 * MM, 34 * MM, 8 * MM, 14), { p: 0.8, tilt: 72, az: -65 }, 120));
      s.push(stroke('Woodless 8B', 300, zigzag(6 * MM, row(4) - 3 * MM, 34 * MM, 6 * MM, 8), { p: 0.3, tilt: 76, az: -80 }, 120));
      s.push(stroke('Woodless 8B', 300, zigzag(48 * MM, row(4) - 3 * MM, 34 * MM, 6 * MM, 8), { p: 0.85, tilt: 76, az: -80 }, 120));
      for (let i = 0; i < 6; i++)
        s.push(stroke('6B', 9, line((10 + i * 12) * MM, row(5) + 4 * MM, (14 + i * 12) * MM, row(5) - 4 * MM), { p: 0.9, tilt: 5, az: -60 }));
      s.push(stroke('Woodless 8B', 75, line(5 * MM, row(5) + 1 * MM, 85 * MM, row(5) - 1 * MM), { p: 0.25, tilt: 72, az: -90 }, 60));
      return s;
    },
  },
};
