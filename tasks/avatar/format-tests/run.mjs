// Round-trip test of the shared character and scene format (tasks/avatar/AVA_PHONE_SPEC.md, part B).
//   node tasks/avatar/format-tests/run.mjs            check every test file both ways round, and every planted fault
//   node tasks/avatar/format-tests/run.mjs <file>     check one file of your own (a pilot save, a phone save): rules + upgrade
// Uses tools/avatarlab/al-format.js, the same file the pilot page can load. Made-up art only in this folder.
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const here = dirname(fileURLToPath(import.meta.url));
const F = createRequire(import.meta.url)(join(here, '..', '..', '..', 'tools', 'avatarlab', 'al-format.js'));
const load = (p) => JSON.parse(readFileSync(p, 'utf8'));
const copy = (o) => JSON.parse(JSON.stringify(o));
let pass = 0, fail = 0;
function ok(cond, what, detail) { if (cond) { pass++; } else { fail++; console.log('FAIL ' + what + (detail ? '\n     ' + detail : '')); } }
const say = (list) => list.map((b) => b.code + ' @ ' + b.where + ': ' + b.say).join('\n     ');

/* one file of the user's own */
if (process.argv[2]) {
  const doc = load(process.argv[2]);
  const isScene = doc.format === F.SCENE;
  const bad = F.check(doc);
  console.log(bad.length ? 'Rules: ' + bad.length + ' problem(s)\n     ' + say(bad) : 'Rules: none broken');
  const up = isScene ? F.upgradeScene(doc) : F.upgrade(doc);
  up.report.forEach((s) => console.log('Upgrade: ' + s));
  const bad2 = F.check(up.doc);
  console.log(bad2.length ? 'After upgrade: ' + bad2.length + ' problem(s)\n     ' + say(bad2) : 'After upgrade: none broken');
  process.exit(bad.length || bad2.length ? 1 : 0);
}

/* every pair landmark of the /1 file must come back out of /2 -> /1, within the join distance, with its "they meet" */
function pairsKept(v1, back) {
  const miss = [];
  const sets = {}; (function w(n) { if (n.type === 'set') sets[n.id] = n; (n.kids || []).forEach(w); })(back.root);
  (function w(n) {
    if (n.type === 'set' && n.bridges) for (const br of n.bridges) for (const q of br.pts) {
      const got = (sets[n.id].bridges || []).filter((b) => (b.part || '') === (br.part || '') && ((b.a === br.a && b.b === br.b) || (b.a === br.b && b.b === br.a)));
      const hit = got.some((b) => { const rev = b.a !== br.a; return b.pts.some((p) => { const pa = rev ? p.b : p.a, pb = rev ? p.a : p.b; return Math.hypot(pa[0] - q.a[0], pa[1] - q.a[1]) <= F.JOIN_PX && Math.hypot(pb[0] - q.b[0], pb[1] - q.b[1]) <= F.JOIN_PX; }); });
      if (!hit) miss.push((br.part || 'whole') + ' ' + br.a + '-' + br.b + ' ' + JSON.stringify(q));
      const m0 = br.meet == null ? 0.5 : br.meet, b1 = got[0], m1 = b1 ? (b1.a === br.a ? b1.meet : 1 - b1.meet) : null;
      if (b1 && Math.abs(m0 - m1) > 1e-9) miss.push('meet ' + br.a + '-' + br.b + ' ' + m0 + ' became ' + m1);
    }
    (n.kids || []).forEach(w);
  })(v1.root);
  return miss;
}

const files = readdirSync(here).filter((f) => f.endsWith('.json')).sort();
for (const f of files) {
  const doc = load(join(here, f)), isScene = doc.format === F.SCENE;
  ok(F.check(doc).length === 0, f + ': follows the rules', say(F.check(doc)));
  const up = isScene ? F.upgradeScene(doc) : F.upgrade(doc);
  ok(F.check(up.doc).length === 0, f + ': follows the rules after upgrade', say(F.check(up.doc)));
  const up2 = isScene ? F.upgradeScene(up.doc) : F.upgrade(up.doc);
  ok(F.canonical(up2.doc) === F.canonical(up.doc), f + ': upgrading twice changes nothing');
  ok(F.canonical(JSON.parse(JSON.stringify(up.doc, null, 2))) === F.canonical(up.doc), f + ': survives being written and read as text');
  if (!isScene && doc.format === F.CHARACTER_OLD) {
    const miss = pairsKept(doc, F.downgrade(up.doc));
    ok(miss.length === 0, f + ': every landmark pair and "they meet" comes back from /2 to /1', miss.join('\n     '));
  }
  if (isScene) Object.values(up.doc.characters || {}).forEach((c) => ok(c.format === F.CHARACTER, f + ': a carried character is upgraded with its scene'));
  const keys = (o) => Object.keys(o).filter((k) => k !== 'format').sort().join(',');
  ok(keys(up.doc) === keys(doc), f + ': upgrade keeps every top-level field', keys(doc) + ' -> ' + keys(up.doc));
}

/* the conversion, by numbers (v1-three-angles: see the comments in AVA_PHONE_SPEC.md part B, "Worked example") */
{
  const doc = load(join(here, 'v1-three-angles.character.json'));
  const { doc: v2, report } = F.upgrade(doc);
  const head = v2.root.kids[0], whole = head.marks[''], frill = head.marks.frill;
  ok(!head.bridges, 'three angles: no pairs left in /2');
  ok(whole.pts.length === 4, 'three angles: 7 pairs of the whole head become 4 numbered landmarks', JSON.stringify(whole.pts));
  ok(JSON.stringify(whole.at.LQ[0]) === '[100.5,120.5]', 'three angles: the nose matched from Left Quarter to two neighbours is ONE landmark (dots 1.4 px apart joined)', JSON.stringify(whole.at.LQ[0]));
  ok(JSON.stringify(whole.at.UP[1]) === '[128,190.5]', 'three angles: the chin in Upward, marked twice 1 px apart, is averaged', JSON.stringify(whole.at.UP[1]));
  ok(JSON.stringify(whole.at.LQ[2]) === '[150,200]' && JSON.stringify(whole.at.UP[2]) === '[128,191]' && whole.at.FR[2][2] === 'off', 'three angles: a chin that disagrees in Left Quarter (40 px off) is split into its own landmark, "not here" in Frontal', JSON.stringify([whole.at.LQ[2], whole.at.UP[2], whole.at.FR[2]]));
  ok(whole.at.FR[3][2] === 'off' && JSON.stringify(whole.at.FR[3].slice(0, 2)) === '[65,70]', 'three angles: a landmark only Left Quarter and Upward have is "not here" in Frontal, parked between its places', JSON.stringify(whole.at.FR[3]));
  ok(frill.pts.length === 2 && JSON.stringify(frill.at.FR[0]) === '[60,40]' && frill.at.FR[1][2] === 'off', 'three angles: the frill keeps its own landmarks (3 pairs -> 2 points)', JSON.stringify(frill));
  ok(JSON.stringify(whole.meets) === JSON.stringify([{ a: 'LQ', b: 'FR', at: 0.4 }]) && !frill.meets, 'three angles: the one non-default "they meet" is kept, on the whole head only', JSON.stringify([whole.meets, frill.meets]));
  ok(JSON.stringify(head.warpWith) === '{"frill":"own"}', 'three angles: the frill still warps by its own landmarks');
  ok(report.some((s) => /second became a landmark of its own/.test(s)), 'three angles: the split is said in the report', report.join(' | '));
  ok(!whole.at.RQ, 'three angles: the mirrored angle has no row of its own');
  const b1 = F.blend(head, '', { FR: 0.5, UP: 0.5 });
  ok(JSON.stringify(b1[0]) === '[128,110]', 'blend: half way Frontal to Upward, the nose is half way', JSON.stringify(b1[0]));
  ok(JSON.stringify(b1[2]) === '[128,191]', 'blend: a point "not here" in Frontal takes Upward\'s place alone', JSON.stringify(b1[2]));
  ok(F.blend(head, '', { FR: 1 })[3] === null, 'blend: a point "not here" in the only angle is no point at all');
  ok(JSON.stringify(F.blend(head, '', { RQ: 1 })[0]) === '[155.5,120.5]', 'blend: the mirrored angle reads its source, mirrored about the axis', JSON.stringify(F.blend(head, '', { RQ: 1 })[0]));
  const v2c = load(join(here, 'v2-crest-hidden.character.json')), crest = v2c.root.kids[1];
  const c1 = F.blend(crest, '', { FR: 0.5, LP: 0.5 });
  ok(JSON.stringify(c1[4]) === '[134,3]', 'blend: a tip folded behind in profile still steers (half way)', JSON.stringify(c1[4]));
  ok(JSON.stringify(c1[6]) === '[186,10]', 'blend: a tip "not here" in profile is Frontal\'s alone', JSON.stringify(c1[6]));
  ok(JSON.stringify(F.blend(crest, '', { RP: 1 })[0]) === '[196,12]', 'blend: the mirrored profile places tip 1 mirrored', JSON.stringify(F.blend(crest, '', { RP: 1 })[0]));
  ok(F.blend(crest, '', { RP: 1 })[4].length === 2, 'blend: a mirrored "behind" point is still a place');
}

/* planted faults: each must be caught by its own rule */
const crest = load(join(here, 'v2-crest-hidden.character.json')), scene = load(join(here, 'two-on-a-meadow.scene.json'));
const faults = [
  ['dup-id', crest, (o) => { o.root.kids[0].id = 'head'; }],
  ['type', crest, (o) => { o.root.kids[0].type = 'sprite'; }],
  ['ref', crest, (o) => { o.root.kids[1].kids[2].of = 'nobody'; }],
  ['ref', crest, (o) => { o.root.kids[1].pick.rest = 'gone'; }],
  ['pic-missing', crest, (o) => { o.root.kids[0].img = 'p9'; }],
  ['trig-ref', crest, (o) => { o.root.kids[2].kids[1].trig = 't9'; }],
  ['trig-ref', crest, (o) => { o.poses[0].trig = 't9'; }],
  ['ref', crest, (o) => { o.poses[0].d.ghost = { x: 1 }; }],
  ['angle-at', crest, (o) => { delete o.root.kids[1].kids[0].at; }],
  ['mark-row', crest, (o) => { o.root.kids[1].marks[''].at.LP.pop(); }],
  ['mark-place', crest, (o) => { o.root.kids[1].marks[''].at.LP[0] = [1, 2, 'hidden']; }],
  ['mark-place', crest, (o) => { o.root.kids[1].marks[''].at.FR[0] = [1]; }],
  ['mark-angle', crest, (o) => { o.root.kids[1].marks[''].at.XX = o.root.kids[1].marks[''].at.LP; }],
  ['mark-n', crest, (o) => { o.root.kids[1].marks[''].pts[2].n = 7; }],
  ['mark-empty', crest, (o) => { o.root.kids[2].marks[''].at.m1 = [[0, 0, 'off'], [0, 0, 'off']]; }],
  ['meet', crest, (o) => { o.root.kids[1].marks[''].meets[0].at = 1.5; }],
  ['pairs-in-v2', crest, (o) => { o.root.kids[1].bridges = []; }],
  ['take-lane', crest, (o) => { o.take.ch.turnX = [0, 0, 0, 0, 0, 0]; }],
  ['voice-len', crest, (o) => { o.take.voice.n = 300; }],
  ['voice', crest, (o) => { o.take.voice.wav = 'hello'; }],
  ['take', crest, (o) => { o.take.fps = 0; }],
  ['palette', crest, (o) => { delete o.palettes[0].stops[0].from; }],
  ['pic-form', crest, (o) => { o.pictures.p1 = 'C:/Users/someone/art.png'; }],
  ['size', crest, (o) => { o.w = 0; }],
  ['format', crest, (o) => { o.format = 'joycreator.character/9'; }],
  ['tangle', crest, (o) => { let n = o.root; for (let i = 0; i < 45; i++) { const k = { id: 'deep' + i, type: 'group', kids: [] }; n.kids.push(k); n = k; } }],
  ['shape', scene, (o) => { o.shape = '21:9'; }],
  ['dup-id', scene, (o) => { o.items[1].id = 'k1'; }],
  ['kind', scene, (o) => { o.items[1].kind = 'prop'; }],
  ['cast-src', scene, (o) => { delete o.characters.k3; }],
  ['item-pic', scene, (o) => { delete o.items[4].img; }],
  ['pic-missing', scene, (o) => { o.items[1].frames.push('f9'); }],
  ['cam-shot', scene, (o) => { o.cam[2].shot = 'h9'; }],
  ['cam-order', scene, (o) => { o.cam[1].t = 5; }],
  ['take-who', scene, (o) => { o.takes.k2 = o.takes.k3; }],
  ['char-who', scene, (o) => { o.characters.k9 = o.characters.k3; }],
  ['ref', scene, (o) => { o.characters.k3.root.kids[0].clips = [{ by: 'none' }]; }]
];
for (const [code, base, plant] of faults) {
  const o = copy(base); plant(o); const got = F.check(o);
  ok(got.some((b) => b.code === code), 'planted fault "' + code + '" is caught', got.length ? say(got) : 'nothing was reported');
}
ok(F.check(crest).length === 0 && F.check(scene).length === 0, 'the planted faults did not touch the test files');

console.log((fail ? 'FAILED ' : 'PASSED ') + pass + ' checks, ' + fail + ' failures (' + faults.length + ' planted faults, ' + files.length + ' test files)');
process.exit(fail ? 1 : 0);
