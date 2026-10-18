/* VisLab words: the timed words a `words` layer turns into art (a transcript's words, the demo
 * song's sample lyrics, or words the person types) and the glyph atlas the shader reads.
 *
 * The demo song's sample words live in app/src/main/assets/vislab/words/demo-lyrics.json (shared
 * with the app). Contract with the shader (and the app's WordFeed):
 *   uWords        2048 x 2048 texture: 16 slots of 2048 x 128 stacked top to bottom, white ink,
 *                 alpha = ink, the word drawn from x = 0 with padding on both sides.
 *   uWord[i]      (slot, start - now, end - now, width / height)       seconds; box aspect
 *   uWordB[i]     (flow - flow now, order, hash, duration)              flow in text heights
 *   uWordCount    how many of the 16 are filled
 * "Flow" is the words laid end to end (each word's width plus a gap), so a scene can scroll text
 * along a spiral or a tunnel wall and have each word arrive at the anchor exactly when it starts.
 */
(function (global) {
  'use strict';
  const SLOTS = 16, SW = 2048, SH = 128, GAP = 0.6, PAD = 0.25;   // GAP, PAD in text heights
  const FONT = '800 96px Archivo, system-ui, sans-serif';

  class WordFeed {
    constructor() {
      this.words = [];          // {text, start, end, aspect, flow}
      this.period = 0;          // > 0: the list repeats every `period` seconds (a looping song)
      this.flowPeriod = 0;
      this.slotKey = new Array(SLOTS).fill(null);
      this.slotUsed = new Array(SLOTS).fill(-1);
      this.dirty = new Set();
      this.U = new Float32Array(SLOTS * 4);
      this.UB = new Float32Array(SLOTS * 4);
      this.canvas = document.createElement('canvas');
      this.canvas.width = SW; this.canvas.height = SH;
      this.c2d = this.canvas.getContext('2d');
      this.tex = null;
      this.frame = 0;
      this.label = '';
    }

    /** Timed words: [{text, start, end}] in seconds. `period` > 0 repeats them (a looping song). */
    setWords(list, period, label) {
      const c = this.c2d;
      c.font = FONT;
      let flow = 0;
      this.words = list.slice().sort((a, b) => a.start - b.start).map((w) => {
        const aspect = (c.measureText(w.text).width / SH) + PAD * 2;
        const o = { text: w.text, start: w.start, end: Math.max(w.end, w.start + 0.05), aspect, flow };
        flow += aspect + GAP;
        return o;
      });
      this.flowPeriod = flow;
      this.period = period || 0;
      this.label = label || '';
      this.slotKey.fill(null);
      this.slotUsed.fill(-1);
      this.dirty.clear();
    }

    /**
     * Words the person typed, one per beat from `from` (seconds), each a beat long, repeating.
     * Punctuation stays with its word; blank input clears.
     */
    setTyped(text, from, beat) {
      const parts = String(text || '').split(/\s+/).filter(Boolean).slice(0, 200);
      if (!parts.length) { this.setWords([], 0, ''); return; }
      const list = parts.map((t, i) => ({ text: t, start: i * beat, end: (i + 0.92) * beat }));
      this.setWords(list, parts.length * beat, 'typed');
      this.offset = from;   // typed words start at `from`, then repeat
    }

    /** Flow position at time t (text heights): words arrive at the anchor as they start. */
    flowAt(t) {
      const w = this.words;
      if (!w.length) return 0;
      const P = this.period, first = w[0], last = w[w.length - 1];
      const lerp = (t0, f0, t1, f1) => f0 + (f1 - f0) * Math.min(1, Math.max(0, (t - t0) / Math.max(t1 - t0, 1e-3)));
      if (t < first.start) {
        // Looping: carry on from the previous loop's last word, so the seam never jumps.
        return P > 0 ? lerp(last.start - P, last.flow - this.flowPeriod, first.start, first.flow)
          : first.flow - (first.start - t) * 2;
      }
      for (let i = 0; i < w.length - 1; i++) {
        if (t < w[i + 1].start) return lerp(w[i].start, w[i].flow, w[i + 1].start, w[i + 1].flow);
      }
      return P > 0 ? lerp(last.start, last.flow, P + first.start, this.flowPeriod + first.flow)
        : last.flow + (t - last.start) * 2;
    }

    /** The GL texture, with any newly placed words drawn in. */
    texture(gl) {
      if (!this.tex) {
        this.tex = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, this.tex);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, SW, SW, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.generateMipmap(gl.TEXTURE_2D);
      }
      if (this.dirty.size) {
        gl.bindTexture(gl.TEXTURE_2D, this.tex);
        const c = this.c2d;
        for (const slot of this.dirty) {
          c.clearRect(0, 0, SW, SH);
          c.font = FONT;
          c.fillStyle = '#fff';
          c.textBaseline = 'middle';
          const text = this.slotKey[slot] ? this.slotKey[slot].text : '';
          c.fillText(text, PAD * SH, SH * 0.54);
          gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, slot * SH, gl.RGBA, gl.UNSIGNED_BYTE, this.canvas);
        }
        gl.generateMipmap(gl.TEXTURE_2D);
        this.dirty.clear();
      }
      return this.tex;
    }

    /**
     * Uniforms for time `now`: up to 16 words around now (from `back` seconds ago to `lead`
     * seconds ahead), each given a slot in the atlas.
     */
    uniforms(now, lead, back) {
      this.frame++;
      const U = this.U, UB = this.UB;
      U.fill(0); UB.fill(0);
      const w = this.words;
      let n = 0;
      if (w.length) {
        lead = lead || 3; back = back || 3;
        const t = now - (this.offset || 0);
        const P = this.period;
        const k0 = P > 0 ? Math.floor((t - back) / P) : 0, k1 = P > 0 ? Math.floor((t + lead) / P) : 0;
        const pick = [];
        for (let k = k0; k <= k1; k++) {
          const base = k * P, flowBase = k * this.flowPeriod;
          for (let i = 0; i < w.length; i++) {
            const s = w[i].start + base, e = w[i].end + base;
            if (s - t > lead || t - e > back) continue;
            pick.push({ word: w[i], key: k + ':' + i, order: i + k * w.length, s, e, flow: w[i].flow + flowBase });
          }
        }
        // The nearest words win when more than 16 are in view.
        pick.sort((a, b) => Math.abs(a.s - t) - Math.abs(b.s - t));
        const flowNow = this.flowAt(P > 0 ? ((t % P) + P) % P : t) + (P > 0 ? Math.floor(t / P) * this.flowPeriod : 0);
        this.flowNow = flowNow;
        const chosen = pick.slice(0, SLOTS);
        const keep = new Set(chosen.map((p) => p.key));
        for (const p of chosen) {
          let slot = this.slotKey.findIndex((s) => s && s.key === p.key);
          if (slot < 0) {
            slot = this.slotKey.findIndex((s) => !s || !keep.has(s.key));
            if (slot < 0) continue;
            this.slotKey[slot] = { key: p.key, text: p.word.text };
            this.dirty.add(slot);
          }
          const j = n * 4;
          U[j] = slot; U[j + 1] = p.s - t; U[j + 2] = p.e - t; U[j + 3] = p.word.aspect;
          UB[j] = p.flow - flowNow; UB[j + 1] = p.order; UB[j + 2] = fract(Math.sin(p.order * 12.9898) * 43758.5453); UB[j + 3] = p.e - p.s;
          n++;
        }
      }
      return { 'uWord[0]': U, 'uWordB[0]': UB, uWordCount: n, uFlowNow: this.flowNow || 0 };
    }
  }

  function fract(x) { return x - Math.floor(x); }

  global.WordFeed = WordFeed;
})(typeof window !== 'undefined' ? window : globalThis);
