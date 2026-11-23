// FACE-1: does the web pilot read a face exactly as the phone does?
// Runs tools/avatarlab/al-face.js on the faces FaceParityDump ran through the phone's engine, and compares
// every signal. Usage: node tools/jvm-harness/face_parity.mjs <dump.json>   (run-face.sh does it for you)
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const src = fs.readFileSync(process.env.FACE_PILOT_JS || path.join(here, '..', 'avatarlab', 'al-face.js'), 'utf8');
const ctx = { self: {} };
ctx.self.localStorage = { getItem: () => null, setItem: () => {} };
vm.runInNewContext(src, ctx);
vm.runInNewContext(fs.readFileSync(process.env.POSE_PILOT_JS || path.join(here, '..', 'avatarlab', 'al-pose.js'), 'utf8'), ctx);   // SHRUG-1
const F = ctx.self.AL.face;
const dump = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const TOL = 1e-4;
let fails = 0, compared = 0;

function same(where, phone, pilot) {
  for (const k of Object.keys(phone)) {
    compared++;
    const a = phone[k], b = pilot[k];
    if (typeof b !== 'number' || Math.abs(a - b) > TOL) {
      if (fails < 12) console.log(`FAIL  ${where}: ${k} phone ${a} pilot ${b}`);
      fails++;
    }
  }
}

const cal = F.calibration(dump.cal);
dump.cases.forEach((c, i) => same(`face ${i}`, c.out, F.map(c.raw, cal, c.tun)));
const st = new F.Stage(F.calibration(), {});
dump.stage.forEach((fr, i) => same(`stage frame ${i}`, fr.out, st.process(fr.raw, fr.t)));

// face pins: point movements, and a stage that takes its neutral with points and watches two
dump.points.forEach((c, i) => {
  const got = F.pointOffsets(F.normalizePoints(Float32Array.from(c.rest)), F.normalizePoints(Float32Array.from(c.now)), c.chosen, -1);
  const phone = {}, pilot = {};
  c.out.forEach((v, k) => { phone[k] = v; pilot[k] = got ? got[k] : undefined; });
  same(`face pins ${i}`, phone, pilot);
});
const pst = new F.Stage(F.calibration(), {});
pst.watchPoints([61, 17]);
dump.pointStage.forEach((fr, i) => same(`face-pin stage frame ${i}`, fr.out,
  pst.process(fr.raw, fr.t, fr.xyz ? Float32Array.from(fr.xyz) : null)));

// the lids: the owner's eye-test frames, and a stage on drawn eyes that re-takes an old neutral
(dump.lidCases || []).forEach((c, i) => same(`lids ${i}`, c.out, F.map(c.raw, F.calibration(c.cal), c.tun)));
if (dump.lidStage) {
  const lst = new F.Stage(F.calibration(dump.lidStageCal), {});
  dump.lidStage.forEach((fr, i) => same(`lid stage frame ${i}`, fr.out, lst.process(fr.raw, fr.t, fr.xyz ? Float32Array.from(fr.xyz) : null)));
}

// shoulders (SHRUG-1): the same bodies through the pilot's shoulder stage, each Mirror setting with its own stage
if (dump.shoulders) {
  const PO = ctx.self.AL.pose; let sst = null, lastPass = null;
  dump.shoulders.forEach((fr, i) => {
    const pass = fr.pass != null ? fr.pass : fr.mirror;
    if (pass !== lastPass) { sst = new PO.Stage(); if (fr.sens != null) sst.setSensitivity(fr.sens); lastPass = pass; }
    if (fr.recentre) sst.requestNeutral();
    const d = PO.drops(Float32Array.from(fr.xyv), dump.tall);
    if (fr.drops) same(`shoulder drops ${i}`, { l: fr.drops[0], r: fr.drops[1] }, d ? { l: d[0], r: d[1] } : {});
    else if (d) { fails++; console.log(`FAIL  shoulder drops ${i}: phone sees no shoulders, pilot does`); }
    same(`shoulder frame ${i}`, fr.out, sst.process(d, fr.t, fr.mirror));
  });
}

console.log(fails === 0
  ? `PASS  the pilot reads every face as the phone does (${compared} values, ${dump.cases.length} faces, ${dump.stage.length} stage frames)`
  : `FAIL  ${fails} of ${compared} values differ between the phone and the pilot`);
process.exit(fails === 0 ? 0 : 1);
