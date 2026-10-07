// media_golden.mjs — the lab's own outputs for fixed inputs, so the app's Kotlin port (core/media) can be checked
// number for number against what the owner approved in the lab.
//   node joybrush/tools/media_golden.mjs   →  core/src/jvmTest/resources/media/golden.json
// Rerun after any change to lab/media/js/{stick,wet,paste,spline,paper}.js and commit the result with it.
import fs from 'fs';
import path from 'path';
import os from 'os';
import { fileURLToPath, pathToFileURL } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const lab = path.join(here, '..', 'lab', 'media', 'js');
// The lab modules are ES modules in a folder without "type": "module": load copies from a temp folder that has one.
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'jb-golden-'));
fs.writeFileSync(path.join(tmp, 'package.json'), '{"type":"module"}');
for (const f of ['stick.js', 'wet.js', 'paste.js', 'spline.js', 'paper.js']) fs.copyFileSync(path.join(lab, f), path.join(tmp, f));
const imp = f => import(pathToFileURL(path.join(tmp, f)).href);
const stick = await imp('stick.js'), wet = await imp('wet.js'), paste = await imp('paste.js'), spline = await imp('spline.js'), paperJs = await imp('paper.js');

const r6 = v => (typeof v === 'number' ? Number(v.toPrecision(7)) : v);
const round = o => JSON.parse(JSON.stringify(o, (k, v) => (typeof v === 'number' ? r6(v) : v)));

// A deterministic paper: a two-bump height histogram, as a scanned surface has.
const hist = new Float64Array(256);
for (let b = 0; b < 256; b++) hist[b] = Math.exp(-((b - 150) ** 2) / 900) + 0.35 * Math.exp(-((b - 90) ** 2) / 400);
const hs = hist.reduce((a, b) => a + b, 0);
for (let b = 0; b < 256; b++) hist[b] /= hs;
const paper = { toothMm: 0.12, compliance: 0.4, phi: paperJs.makePhiTable(hist), physical: { sizing: 0.6, absorbency: 0.5, capacity: 0.5, wickSpeed: 0.5 }, uniforms: { u_paperHeightMean: 0.5 } };

// Pen samples: a curve with pressure, tilt and lean changing along it (doc px, radians, ms), plus a still dab.
function samples(n, { x0 = 100, y0 = 200, len = 300, p = u => 0.2 + 0.7 * Math.sin(Math.PI * u), tilt = u => 0.3 + 0.9 * u, az = u => -1 + 0.8 * u, ms = 4 } = {}) {
  const out = [];
  for (let i = 0; i <= n; i++) {
    const u = i / n;
    out.push({ x: x0 + len * u, y: y0 + 40 * Math.sin(u * 5), p: p(u), tilt: tilt(u), az: az(u), t: i * ms });
  }
  return out;
}
const dab = n => { const o = []; for (let i = 0; i <= n; i++) o.push({ x: 50 + 0.4 * Math.sin(i * 2.7), y: 60 + 0.4 * Math.cos(i * 3.1), p: 0.6 * Math.sin(Math.PI * i / n), tilt: 0.4, az: -1, t: i * 6 }); return o; };

const out = { paper: { toothMm: paper.toothMm, compliance: paper.compliance, hist: Array.from(hist) } };
// The exact pen inputs, so the port replays the same samples.
const IN = { s40: samples(40), s30: samples(30), s30flat: samples(30, { tilt: () => 0.2 }), s20: samples(20), dab20: dab(20) };
out.inputs = IN;

// ---- paper statistics
out.phi = Array.from(paper.phi);
out.psi = Array.from(stick.makePsiTable(paper));
const lut = stick.contactLut(paper);
out.lut = { a: Array.from(lut.a), p: Array.from(lut.p), maxMm: lut.maxMm };

// ---- pencil
out.sticks = stick.STICKS;
out.zones = stick.ZONES;
out.press = stick.PRESS;
out.materials = Object.fromEntries(Object.entries(stick.STICKS).map(([k, s]) => [k, stick.stickMaterial(s)]));
out.contacts = [];
for (const name of Object.keys(stick.STICKS)) for (const t of [0, 0.3, 0.6, 0.8, 0.95, 1]) for (const P of [0.05, 0.3, 0.7, 1]) {
  const c = stick.stickContact(stick.STICKS[name], t * Math.PI / 2, P, paper);
  out.contacts.push({ name, t, P, c });
}
out.squeeze = [];
{
  const c = stick.stickContact(stick.STICKS.Proto, 0.85 * Math.PI / 2, 0.6, paper);
  for (const x of [-0.5, -0.1, 0, 0.3, 1, 3, 6, 10, 14]) for (const y of [0, 0.4, 1.2]) out.squeeze.push({ x, y, v: stick.stickSqueeze(x, y, c) });
}
const runDry = (name, smp) => {
  const ds = new stick.DryStroke(stick.STICKS[name], paper, 20);
  const dabs = [];
  for (const s of smp) { ds.add(s); const b = ds.take(); for (let i = 0; i < b.count; i++) dabs.push(Array.from(b.dabs.slice(i * 20, i * 20 + 20))); }
  return dabs;
};
out.dry = { Proto: runDry('Proto', IN.s40), HB: runDry('HB', IN.s30flat), dab: runDry('Proto', IN.dab20) };

// ---- wet
out.wet = { WET: wet.WET, WETNESS: wet.WETNESS, WET_BRUSHES: wet.WET_BRUSHES, THINNER: wet.THINNER };
out.paintFromColor = [[0.62, 0.22, 0.18], [0.2, 0.32, 0.72], [0.9, 0.9, 0.85]].map(c => ({ c, p: wet.paintFromColor(c), half: wet.paintFromColor(c, 0.5) }));
out.tiltSlope = [[0, [0, -1]], [45, [0, -1]], [30, [0.6, 0.8]], [120, [1, 0]]].map(([d, dir]) => ({ d, dir, s: wet.tiltSlope(d, dir) }));
out.wetUniforms = wet.wetUniforms(paper);
const runWet = (ws, smp) => {
  const dabs = [];
  smp.forEach((s, i) => { ws.add(s); if (i === smp.length - 1) ws.finish(); const b = ws.take(); for (let k = 0; k < b.count; k++) dabs.push(Array.from(b.dabs.slice(k * 20, k * 20 + 20))); });
  return dabs;
};
const C = [0.62, 0.22, 0.18];
out.wetStrokes = {
  allroundLoaded: runWet(new wet.WetStroke(wet.WET_BRUSHES['All-round'], wet.paintFromColor(C), paper, 20, 3, wet.WETNESS[3]), IN.s40),
  roundOwnLoad: runWet(new wet.WetStroke(wet.WET_BRUSHES.Round, wet.paintFromColor(C), paper, 20, 5, null), IN.s30),
  dryBrush: runWet(new wet.WetStroke(wet.WET_BRUSHES['Dry brush'], wet.paintFromColor(C), paper, 20, 7, wet.WETNESS[0]), IN.s30),
  waterDab: runWet(new wet.WetStroke(wet.WET_BRUSHES.Water, wet.paintFromColor(C), paper, 20, 9, wet.WETNESS[4]), IN.dab20),
  thinner: runWet(wet.thinnerStroke(paste.PASTE_BRUSHES['All-round'], C, paper, 20, 11, 3), IN.s30),
};

// ---- paste
out.paste = { PASTE_BRUSHES: paste.PASTE_BRUSHES, LANES: paste.PASTE_LANES, DEPTH: paste.PASTE_DEPTH, EDGE_MODES: paste.EDGE_MODES, PRESS_MODES: paste.PRESS_MODES, FACE_MODES: paste.FACE_MODES };
out.opaquePaint = [[0.75, 0.25, 0.22], [0.02, 0.02, 0.02], [1, 1, 1]].map(c => ({ c, p: paste.opaquePaint(c) }));
out.cellCap = Object.fromEntries(Object.entries(paste.PASTE_BRUSHES).map(([k, b]) => [k, paste.cellCap(b)]));
out.bladeTan = [0, 0.25, 0.5, 0.75, 1].map(t => [t, paste.bladeTan(t)]);
const runPaste = (name, smp, opts = {}) => {
  const ps = new paste.PasteStroke(paste.PASTE_BRUSHES[name], 20, 1, opts);
  if (opts.body) ps.body = opts.body;
  const steps = [];
  for (const s of smp) { ps.add(s); steps.push(...ps.take()); }
  return steps;
};
out.pasteStrokes = {};
for (const name of Object.keys(paste.PASTE_BRUSHES)) out.pasteStrokes[name] = runPaste(name, IN.s30);
for (const edge of paste.EDGE_MODES) for (const press of paste.PRESS_MODES) for (const face of paste.FACE_MODES)
  out.pasteStrokes[`Palette knife 2|${edge}|${press}|${face}`] = runPaste('Palette knife 2', IN.s20, { edge, press, face });
for (const edge of paste.EDGE_MODES) out.pasteStrokes[`Scraper|${edge}`] = runPaste('Scraper', IN.s20, { edge });
out.pasteStrokes['Oil round|dab'] = runPaste('Oil round', IN.dab20);
out.pasteStrokes['All-round|thin'] = runPaste('All-round', IN.s30, { body: 0.4 });

// ---- spline
{
  const sparse = [];
  for (let i = 0; i <= 8; i++) sparse.push({ x: 100 + 60 * i, y: 300 + 80 * Math.sin(i * 0.9), p: 0.2 + 0.08 * i, tilt: 0.1 * i, az: 3 - 0.8 * i, t: i * 16 });
  const dense = [];
  const f = new spline.SplineFeeder(s => dense.push(s));
  for (const s of sparse) f.add(s);
  f.finish();
  out.spline = { sparse, dense };
}

const dest = path.join(here, '..', 'core', 'src', 'jvmTest', 'resources', 'media', 'golden.json');
fs.mkdirSync(path.dirname(dest), { recursive: true });
// Outputs are rounded to 7 digits; the INPUTS are kept exact (a direction from a 0.4 px jitter is sensitive to them).
const rounded = round(out);
rounded.inputs = IN;
fs.writeFileSync(dest, JSON.stringify(rounded));
console.log(dest, (fs.statSync(dest).size / 1024).toFixed(0) + ' KB',
  { dry: out.dry.Proto.length, wet: out.wetStrokes.allroundLoaded.length, paste: Object.keys(out.pasteStrokes).length, spline: out.spline.dense.length });
