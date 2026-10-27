// Joy Beats: our own test music for VisLab's MIDI source, in the spirit of the little 4-channel
// demoscene "beats" modules the owner likes (tight 16th breaks with ghost-note rolls, choked double
// hits, galloping hats, a syncopated bass and short cut-off stabs). Every pattern here is written
// fresh; nothing is copied from those modules.
//
//   node tools/test-music/joy-beats.mjs   ->  tools/test-music/joy-beats.kar  (a .mid with words)
//
// 126 bpm, A minor, 32 bars (~61 s): intro, groove A, groove B with stabs, a drum break, groove A
// again with karaoke words, outro. Played by VisLab's built-in synth (channel 10 = drums: 36 kick,
// 38 snare, 37 ghost, 39 clap, 42 closed hat, 46 open hat, 49 crash, 45/47/50 toms).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BPM = 126, PPQ = 480, STEP = PPQ / 4;          // one step = a 16th
const ev = [];                                         // [tick, order, bytes]
let order = 0;
const vlq = (v) => { const b = [v & 0x7F]; while ((v >>= 7) > 0) b.unshift((v & 0x7F) | 0x80); return b; };
const meta = (t, type, data) => ev.push([t, order++, [0xFF, type, ...vlq(data.length), ...data]]);
const text = (t, type, s) => meta(t, type, [...Buffer.from(s, 'latin1')]);
const note = (t, ch, n, vel, len) => {
  ev.push([t, order++, [0x90 | ch, n, Math.max(1, Math.min(127, vel))]]);
  ev.push([t + Math.max(1, len), order++, [0x80 | ch, n, 0]]);
};
const DR = 9, BASS = 0, STAB = 1;

// ---- the parts, one character per 16th --------------------------------------------------------
// Drums: K kick, k soft kick, S snare, g ghost snare, C clap, r rising snare roll, - nothing.
const BREAK = {
  a1: 'K--gS--K-KgS-g--',
  a2: 'K--gS-K--KgSgg-K',
  b1: 'K-gKS--g-K-S-ggg',
  b2: 'K--KS-gK-gKS-rrr',
  hole: 'K-K---K-K---S-S-',                // the break: choked doubles
  roll: 'K---S---rrrrrrrr',
  out: 'K---------------',
};
// Hats: x closed, o open, - none. The gallop: three of every four 16ths.
const HATS = { gallop: 'x-xxx-xxx-xxx-xo', eighths: 'x-x-x-x-x-x-x-x-', sparse: 'x---x---x---x---' };
// Bass: two bars, notes on steps (MIDI numbers; A1 = 33).
const BASSLINE = [
  { 0: 33, 3: 33, 6: 36, 10: 33, 12: 40, 14: 38 },
  { 0: 33, 3: 33, 6: 31, 8: 33, 11: 36, 14: 40 },
];
// Stabs: chord per bar, a cut-off rhythm (s = stab one step long).
const STABS = 'ss--s-s---s-s---';
const CHORDS = [[57, 60, 64, 67], [57, 60, 64, 67], [53, 57, 60, 64], [55, 59, 62, 67]];
const WORDS = ['Joy ', 'Cre', 'a', 'tor ', 'lights ', 'up ', 'the ', 'beat ',
  'paint ', 'it ', 'loud ', 'now ', 'feel ', 'the ', 'bass ', 'drop '];

// ---- the arrangement -------------------------------------------------------------------------
const plan = [];
const add = (n, bar) => { for (let i = 0; i < n; i++) plan.push(bar(i)); };
add(4, (i) => ({ drums: i < 2 ? null : 'out', hats: i < 2 ? 'sparse' : 'gallop', bass: true }));
add(8, (i) => ({ drums: i % 2 ? 'a2' : 'a1', hats: 'gallop', bass: true }));
add(8, (i) => ({ drums: i === 7 ? 'b2' : (i % 2 ? 'b1' : 'a1'), hats: 'gallop', bass: true, stabs: true }));
add(2, (i) => ({ drums: i ? 'roll' : 'hole', hats: 'eighths', crash: i === 0 }));
add(8, (i) => ({ drums: i % 2 ? 'b1' : 'a1', hats: 'gallop', bass: true, stabs: i >= 4, words: true, crash: i === 0 }));
add(2, (i) => ({ drums: i ? null : 'out', hats: i ? null : 'sparse', bass: i === 0, crash: i === 0 }));

// ---- write it --------------------------------------------------------------------------------
meta(0, 0x51, [(6e7 / BPM) >> 16 & 0xFF, (6e7 / BPM) >> 8 & 0xFF, (6e7 / BPM) & 0xFF]);
meta(0, 0x58, [4, 2, 24, 8]);
text(0, 0x03, 'Joy Beats');
ev.push([0, order++, [0xC0 | BASS, 38]]);   // synth bass (a saw)
ev.push([0, order++, [0xC0 | STAB, 81]]);   // saw lead, played short as stabs
plan.forEach((bar, b) => {
  const t0 = b * 16 * STEP;
  if (bar.crash) note(t0, DR, 49, 100, STEP * 8);
  if (bar.drums) {
    const p = BREAK[bar.drums];
    let roll = 0;
    for (let s = 0; s < 16; s++) {
      const c = p[s], t = t0 + s * STEP;
      if (c === 'K') note(t, DR, 36, 120, STEP);
      else if (c === 'k') note(t, DR, 36, 80, STEP);
      else if (c === 'S') { note(t, DR, 38, 112, STEP); if (bar.drums.startsWith('b')) note(t, DR, 39, 70, STEP); }
      else if (c === 'g') note(t, DR, 37, 42, STEP);
      else if (c === 'C') note(t, DR, 39, 100, STEP);
      else if (c === 'r') note(t, DR, 38, 50 + 9 * roll++, STEP);
    }
    if (bar.drums === 'hole') { note(t0 + 10 * STEP, DR, 47, 90, STEP * 2); note(t0 + 11 * STEP, DR, 45, 90, STEP * 2); }
  }
  if (bar.hats) {
    const h = HATS[bar.hats];
    for (let s = 0; s < 16; s++) {
      const t = t0 + s * STEP;
      if (h[s] === 'x') note(t, DR, 42, s % 4 === 0 ? 90 : (s % 2 ? 55 : 72), STEP);
      if (h[s] === 'o') note(t, DR, 46, 80, STEP * 2);
    }
  }
  if (bar.bass) {
    for (const [s, n] of Object.entries(BASSLINE[b % 2])) {
      const st = +s, next = Object.keys(BASSLINE[b % 2]).map(Number).find((x) => x > st) ?? 16;
      note(t0 + st * STEP, BASS, n, st % 4 === 0 ? 110 : 92, Math.min(next - st, 3) * STEP - 10);
    }
  }
  if (bar.stabs) {
    const ch = CHORDS[b % 4];
    for (let s = 0; s < 16; s++) if (STABS[s] === 's') for (const n of ch) note(t0 + s * STEP, STAB, n, 78, STEP - 20);
  }
  if (bar.words) for (let beat = 0; beat < 4; beat++) text(t0 + beat * 4 * STEP, 0x05, WORDS[((b * 4) + beat) % WORDS.length]);
});

ev.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
const trk = [];
let last = 0;
for (const [t, , bytes] of ev) { trk.push(...vlq(t - last), ...bytes); last = t; }
trk.push(0, 0xFF, 0x2F, 0);
const be = (v, n) => Array.from({ length: n }, (_, i) => (v >>> (8 * (n - 1 - i))) & 0xFF);
const file = Buffer.from([...Buffer.from('MThd'), ...be(6, 4), ...be(0, 2), ...be(1, 2), ...be(PPQ, 2),
  ...Buffer.from('MTrk'), ...be(trk.length, 4), ...trk]);
const out = path.join(path.dirname(fileURLToPath(import.meta.url)), 'joy-beats.kar');
fs.writeFileSync(out, file);
const secs = plan.length * 16 * STEP / PPQ * 60 / BPM;
console.log(out + '  ' + file.length + ' bytes  ' + plan.length + ' bars  ' + secs.toFixed(1) + ' s');
