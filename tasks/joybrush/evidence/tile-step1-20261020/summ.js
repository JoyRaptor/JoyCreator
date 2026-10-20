// node summ.js base.log patch.log : per-cell compare of two matrix logs (JoyBrushPerf json lines)
const fs = require('fs');
function load(f) {
  const m = new Map();
  for (const line of fs.readFileSync(f, 'utf8').split('\n')) {
    const i = line.indexOf('{"case":"matrix","idx"'); if (i < 0) continue;
    let j; try { j = JSON.parse(line.slice(i)); } catch (e) { continue; }
    if (j.event) continue;
    m.set(j.idx + '_' + j.board, j);
  }
  return m;
}
const a = load(process.argv[2]), b = load(process.argv[3]);
const rows = [];
let hashSame = 0, hashDiff = 0, verdictDiff = 0;
const byBoard = {};
for (const [k, x] of a) {
  const y = b.get(k); if (!y) { rows.push(k + ' missing in patch'); continue; }
  const hs = x.afterHash === y.afterHash;
  if (hs) hashSame++; else hashDiff++;
  if (x.verdict !== y.verdict || x.changedPx !== y.changedPx || x.wrapPx !== y.wrapPx || x.leakPx !== y.leakPx) verdictDiff++;
  const bd = (byBoard[x.board] = byBoard[x.board] || []);
  bd.push({ k, name: x.name, base: x, patch: y, hs });
}
for (const [board, list] of Object.entries(byBoard)) {
  console.log('\n== ' + board);
  for (const r of list) {
    const x = r.base, y = r.patch;
    if (x.refused || y.refused) continue;
    console.log(String(r.name).padEnd(26), 'p50', String(x.p50).padStart(7), '->', String(y.p50).padStart(7), ' fr', String(x.frames).padStart(3), '->', String(y.frames).padStart(3),
      ' hash', r.hs ? 'same' : 'DIFF', ' v', x.verdict, y.verdict === x.verdict ? '' : '-> ' + y.verdict, ' px', x.changedPx, y.changedPx === x.changedPx ? '' : '-> ' + y.changedPx,
      board === 'tile' ? ' wrap ' + x.wrapPx + (y.wrapPx === x.wrapPx ? '' : '->' + y.wrapPx) + ' leak ' + x.leakPx + (y.leakPx === x.leakPx ? '' : '->' + y.leakPx) : '');
  }
}
console.log('\nhash same', hashSame, 'diff', hashDiff, 'result-fields differ', verdictDiff);
