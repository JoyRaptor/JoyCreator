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

console.log(fails === 0
  ? `PASS  the pilot reads every face as the phone does (${compared} values, ${dump.cases.length} faces, ${dump.stage.length} stage frames)`
  : `FAIL  ${fails} of ${compared} values differ between the phone and the pilot`);
process.exit(fails === 0 ? 0 : 1);
