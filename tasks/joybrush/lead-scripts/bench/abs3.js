// node abs3.js <dir> <tag> : median over reps of p50 / p95, window vs atlas
const fs = require('fs'); const [, , dir, tag] = process.argv;
function load(f) { const m = new Map(); if (!fs.existsSync(f)) return m;
  for (const line of fs.readFileSync(f, 'utf8').split('\n')) { const i = line.indexOf('{'); if (i < 0 || !line.includes('JoyBrushPerf')) continue; let j; try { j = JSON.parse(line.slice(i)); } catch (e) { continue; }
    if (!j.brush || j.event) continue; m.set(j.brush, j); } return m; }
const med = (a) => { const s = a.filter((x) => x != null).sort((x, y) => x - y); return s.length ? s[Math.floor((s.length - 1) / 2)] : NaN; };
for (const kind of ['flat', 'tilt']) {
  console.log('== ' + kind + '   (median of 2 runs: p50 / p95 ms, frames)');
  const runs = { false: [1, 2].map((r) => load(`${dir}/${tag}${kind}${r}_falsetoday.log`)), true: [1, 2].map((r) => load(`${dir}/${tag}${kind}${r}_truetoday.log`)) };
  for (const k of runs.false[0].keys()) {
    const s = (m) => { const js = runs[m].map((r) => r.get(k)).filter(Boolean); if (js.some((j) => j.refused)) return 'REFUSED'; return `${med(js.map((j) => j.busyMs.p50)).toFixed(0)} / ${med(js.map((j) => j.busyMs.p95)).toFixed(0)}  fr ${med(js.map((j) => j.strokeFrames))}`; };
    console.log(k.padEnd(28), 'window', s('false').padEnd(22), 'atlas', s('true'));
  } }
