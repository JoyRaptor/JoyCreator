/* VisLab sound analysis (web host).
 *
 * FeatureExtractor is the musical "ear" every effect reacts to. The Android port
 * (com.fadcam.vislab.audio.FeatureExtractor) must stay numerically in step with this file:
 * same bins, same time constants, same thresholds. Feed it at ~60 Hz; the app's offline
 * analysis (Studio) runs it at exactly 60 Hz so preview, scrub and export hear the same thing.
 *
 * Input per frame: dB magnitude spectrum (fftSize/2 bins, like AnalyserNode
 * getFloatFrequencyData), left and right time-domain samples (>= 2048), dt in seconds.
 */
(function (global) {
  'use strict';

  const NB = 128, NW = 512, NH = 64;
  const F_LO = 30, F_HI = 16000;
  const BANDS = [[30, 150], [150, 400], [400, 2000], [2000, 6000], [6000, 16000]];

  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function ease(cur, target, dt, up, down) {
    const tau = target > cur ? up : down;
    return cur + (target - cur) * (1 - Math.exp(-dt / tau));
  }

  class OnsetDetector {
    constructor(lo, hi, minFlux, refractory, decay) {
      this.lo = lo; this.hi = hi; this.minFlux = minFlux; this.refractory = refractory; this.decay = decay;
      this.hist = new Float32Array(32); this.hi_ = 0; this.n = 0;
      this.since = 1; this.value = 0; this.fired = 0;
    }
    update(raw, prev, dt) {
      let flux = 0;
      for (let k = this.lo; k < this.hi; k++) { const d = raw[k] - prev[k]; if (d > 0) flux += d; }
      flux /= Math.max(1, this.hi - this.lo);
      let m = 0, s = 0;
      const n = Math.min(this.n, this.hist.length);
      for (let i = 0; i < n; i++) m += this.hist[i];
      m = n ? m / n : 0;
      for (let i = 0; i < n; i++) s += (this.hist[i] - m) * (this.hist[i] - m);
      s = n ? Math.sqrt(s / n) : 0;
      this.hist[this.hi_] = flux; this.hi_ = (this.hi_ + 1) % this.hist.length; this.n++;
      const th = m + 1.4 * s + this.minFlux;
      this.since += dt;
      this.value *= Math.exp(-dt / this.decay);
      this.fired = 0;
      if (flux > th && this.since > this.refractory) {
        const strength = clamp(0.5 + (flux - th) / (th + 1e-4), 0.5, 1);
        this.value = Math.max(this.value, strength);
        this.fired = strength;
        this.since = 0;
      }
      this.flux = flux;
      return this.fired;
    }
  }

  class FeatureExtractor {
    constructor(sampleRate, fftSize) {
      this.sr = sampleRate; this.n = fftSize;
      const hz = sampleRate / fftSize;
      this.a = new Float32Array(NB); this.b = new Float32Array(NB); this.tilt = new Float32Array(NB);
      this.fc = new Float32Array(NB);
      for (let k = 0; k < NB; k++) {
        const e0 = F_LO * Math.pow(F_HI / F_LO, k / NB);
        const e1 = F_LO * Math.pow(F_HI / F_LO, (k + 1) / NB);
        this.a[k] = e0 / hz; this.b[k] = e1 / hz;
        this.fc[k] = Math.sqrt(e0 * e1);
        this.tilt[k] = 3.0 * Math.log2(this.fc[k] / 1000);
      }
      this.binOf = (f) => clamp(Math.round(Math.log(f / F_LO) / Math.log(F_HI / F_LO) * NB), 0, NB);
      this.bandK = BANDS.map(([lo, hi]) => [this.binOf(lo), Math.max(this.binOf(lo) + 1, this.binOf(hi))]);
      this.db = new Float32Array(NB);
      this.raw = new Float32Array(NB); this.prevRaw = new Float32Array(NB);
      this.spec = new Float32Array(NB); this.peak = new Float32Array(NB);
      this.hist = new Float32Array(NB * NH);
      this.waveL = new Float32Array(NW); this.waveR = new Float32Array(NW);
      this.agc = -40; this.levelAgc = -30; this.waveGain = 4;
      this.env = new Float32Array(5); this.avg = new Float32Array(5).fill(0.2); this.mx = new Float32Array(5).fill(0.3);
      this.bands = new Float32Array(5);
      this.level = 0; this.voice = 0; this.bright = 0.4; this.energy = 0;
      this.kick = new OnsetDetector(this.binOf(30), this.binOf(150), 0.03, 0.12, 0.16);
      this.snare = new OnsetDetector(this.binOf(1800), this.binOf(6500), 0.025, 0.1, 0.13);
      this.hat = new OnsetDetector(this.binOf(7000), NB, 0.02, 0.06, 0.08);
      this.beat = 0; this.phase = 0; this.period = 0.5; this.beatCount = 0;
      this.onsetEnv = new Float32Array(360); this.onsetIdx = 0; this.onsetClock = 0; this.tempoClock = 0;
      this.bassAcc = 0; this.levelAcc = 0; this.shocks = []; this.histClock = 0; this.time = 0;
      this.punch = 0;
    }

    update(dbSpec, timeL, timeR, dt) {
      dt = clamp(dt, 1 / 240, 0.25);
      this.time += dt;
      const nbins = dbSpec.length;
      // 1. log-spaced bins, power-averaged, with a pink tilt so the highs are not always dead.
      let frameMax = -160;
      for (let k = 0; k < NB; k++) {
        const a = this.a[k], b = this.b[k];
        let p;
        if (b - a < 1) {
          const x = clamp((a + b) * 0.5, 0, nbins - 1.001);
          const i = Math.floor(x), f = x - i;
          const p0 = Math.pow(10, Math.max(dbSpec[i], -160) / 10), p1 = Math.pow(10, Math.max(dbSpec[i + 1], -160) / 10);
          p = p0 + (p1 - p0) * f;
        } else {
          let s = 0, c = 0;
          for (let i = Math.floor(a); i < Math.ceil(b) && i < nbins; i++) { s += Math.pow(10, Math.max(dbSpec[i], -160) / 10); c++; }
          p = c ? s / c : 0;
        }
        const d = 10 * Math.log10(p + 1e-16) + this.tilt[k];
        this.db[k] = d;
        if (d > frameMax) frameMax = d;
      }
      // 2. automatic gain: follow the loudest bin fast up, slowly down. Silence stays dark.
      if (frameMax > this.agc) this.agc += (frameMax - this.agc) * (1 - Math.exp(-dt / 0.05));
      else this.agc -= 4 * dt;
      this.agc = Math.max(this.agc, -72);
      const range = 50, floor = this.agc - range;
      const gate = clamp((frameMax + 85) / 20, 0, 1);
      this.prevRaw.set(this.raw);
      for (let k = 0; k < NB; k++) {
        let v = clamp((this.db[k] - floor) / range, 0, 1);
        v = Math.pow(v, 1.6) * gate;
        this.raw[k] = v;
        this.spec[k] = ease(this.spec[k], v, dt, 0.02, 0.16);
        this.peak[k] = Math.max(this.spec[k], this.peak[k] - 0.6 * dt);
      }
      // 3. bands, each normalised to its own recent range so it always uses 0..1.
      for (let i = 0; i < 5; i++) {
        const [k0, k1] = this.bandK[i];
        let s = 0;
        for (let k = k0; k < k1; k++) s += this.raw[k];
        s /= Math.max(1, k1 - k0);
        this.env[i] = ease(this.env[i], s, dt, 0.015, i === 0 ? 0.22 : 0.15);
        this.avg[i] = ease(this.avg[i], this.env[i], dt, 3, 3);
        this.mx[i] = Math.max(this.env[i], this.mx[i] - 0.15 * dt);
        const lo = this.avg[i] * 0.6;
        const norm = clamp((this.env[i] - lo) / Math.max(this.mx[i] - lo, 0.08), 0, 1.2);
        const quiet = clamp((this.env[i] - 0.02) / 0.1, 0, 1);
        this.bands[i] = (this.env[i] * 0.35 + norm * 0.65) * quiet;
      }
      // 4. loudness from the waveform itself.
      let sum = 0, pk = 0;
      const nt = timeL.length;
      for (let i = 0; i < nt; i++) { const s = timeL[i]; sum += s * s; const a = Math.abs(s); if (a > pk) pk = a; }
      const rmsDb = 10 * Math.log10(sum / nt + 1e-12);
      if (rmsDb > this.levelAgc) this.levelAgc += (rmsDb - this.levelAgc) * (1 - Math.exp(-dt / 0.1));
      else this.levelAgc -= 3 * dt;
      this.levelAgc = Math.max(this.levelAgc, -60);
      const lv = clamp((rmsDb - (this.levelAgc - 30)) / 30, 0, 1) * gate;
      this.level = ease(this.level, lv * lv, dt, 0.02, 0.25);
      this.energy = ease(this.energy, this.level, dt, 6, 6);
      // 5. voice band and brightness.
      let vs = 0, vc = 0, cw = 0, cs = 0;
      const v0 = this.binOf(150), v1 = this.binOf(3500);
      for (let k = 0; k < NB; k++) {
        const r = this.raw[k];
        if (k >= v0 && k < v1) { vs += r; vc++; }
        cw += r * r * k; cs += r * r;
      }
      this.voice = ease(this.voice, vc ? clamp(vs / vc * 1.6, 0, 1) : 0, dt, 0.03, 0.2);
      this.bright = ease(this.bright, cs > 1e-6 ? cw / cs / NB : this.bright, dt, 0.2, 0.2);
      // 6. hits.
      const kf = this.kick.update(this.raw, this.prevRaw, dt);
      this.snare.update(this.raw, this.prevRaw, dt);
      this.hat.update(this.raw, this.prevRaw, dt);
      this.punch = kf;
      if (kf > 0) { this.shocks.unshift(0); if (this.shocks.length > 8) this.shocks.length = 8; }
      for (let i = 0; i < this.shocks.length; i++) this.shocks[i] += dt;
      // 7. tempo: autocorrelate the kick-flux envelope, then a phase-locked loop on the kicks.
      this.onsetClock += dt;
      while (this.onsetClock >= 1 / 60) {
        this.onsetClock -= 1 / 60;
        this.onsetEnv[this.onsetIdx] = this.kick.flux || 0;
        this.onsetIdx = (this.onsetIdx + 1) % this.onsetEnv.length;
      }
      this.tempoClock += dt;
      if (this.tempoClock > 0.5) { this.tempoClock = 0; this._tempo(); }
      this.phase += dt / this.period;
      if (kf > 0) {
        const err = this.phase - Math.round(this.phase);
        this.phase -= err * 0.35;
      }
      this.beat *= Math.exp(-dt / 0.12);
      if (this.phase >= 1) { this.phase -= Math.floor(this.phase); this.beat = 1; this.beatCount++; }
      if (this.phase < 0) this.phase += 1;
      // 8. travel: always creeping forward, faster with the music.
      this.bassAcc += (0.15 + this.bands[0]) * dt;
      this.levelAcc += (0.15 + this.level) * dt;
      // 9. scope: trigger on a rising zero crossing so the wave stands still, auto-levelled.
      this._scope(timeL, timeR, pk, dt, gate);
      // 10. two seconds of spectrum history, a row every 1/30 s.
      this.histClock += dt;
      if (this.histClock >= 1 / 30) {
        this.histClock -= 1 / 30;
        this.hist.copyWithin(NB, 0, NB * (NH - 1));
        this.hist.set(this.spec, 0);
      }
      return this.frame();
    }

    _tempo() {
      const o = this.onsetEnv, n = o.length;
      let best = -1, bestL = 30, r0 = 0;
      for (let i = 0; i < n; i++) r0 += o[i] * o[i];
      if (r0 < 1e-6) return;
      const score = new Float32Array(60);
      for (let L = 20; L <= 51; L++) {
        let r = 0;
        for (let i = 0; i < n; i++) r += o[i] * o[(i - L + n) % n];
        const bpm = 3600 / L;
        const prior = Math.exp(-0.5 * Math.pow(Math.log2(bpm / 120) / 0.8, 2));
        score[L] = r * prior;
        if (score[L] > best) { best = score[L]; bestL = L; }
      }
      const y0 = score[bestL - 1] || 0, y1 = score[bestL], y2 = score[bestL + 1] || 0;
      const den = y0 - 2 * y1 + y2;
      const off = Math.abs(den) > 1e-9 ? clamp(0.5 * (y0 - y2) / den, -0.5, 0.5) : 0;
      if (best / r0 > 0.08) {
        const p = (bestL + off) / 60;
        this.period = Math.abs(p - this.period) / this.period < 0.08 ? this.period * 0.7 + p * 0.3 : p;
      }
    }

    _scope(L, R, pk, dt, gate) {
      const n = L.length;
      const span = Math.min(1024, n - 1);
      let start = 0;
      for (let i = 1; i < n - span; i++) { if (L[i - 1] < 0 && L[i] >= 0) { start = i; break; } }
      const target = clamp(0.8 / Math.max(pk, 0.01), 0.5, 40);
      this.waveGain = ease(this.waveGain, target, dt, 0.3, 1.0);
      const g = this.waveGain * gate;
      const step = span / NW;
      for (let i = 0; i < NW; i++) {
        const j = start + Math.floor(i * step);
        this.waveL[i] = clamp(L[j] * g, -1.2, 1.2);
        this.waveR[i] = clamp((R ? R[j] : L[Math.max(0, j - 23)]) * g, -1.2, 1.2);
      }
    }

    frame() {
      return {
        bass: this.bands[0], low: this.bands[1], mid: this.bands[2], high: this.bands[3], air: this.bands[4],
        level: this.level, voice: this.voice, bright: this.bright,
        kick: this.kick.value, snare: this.snare.value, hat: this.hat.value, beat: this.beat,
        beatPhase: this.phase, barPhase: ((this.beatCount % 4) + this.phase) / 4,
        bassAcc: this.bassAcc, levelAcc: this.levelAcc, energy: this.energy, punch: this.punch,
        shocks: this.shocks, spec: this.spec, peak: this.peak, raw: this.raw,
        waveL: this.waveL, waveR: this.waveR, hist: this.hist, bpm: 60 / this.period
      };
    }
  }

  /* --------------------------------------------------------------- audio rig */
  class AudioRig {
    constructor() {
      this.ctx = null; this.source = 'none'; this.fx = null;
      this.fft = 2048;
    }
    _ensure() {
      if (this.ctx) return;
      const AC = window.AudioContext || window.webkitAudioContext;
      this.ctx = new AC();
      const ctx = this.ctx;
      this.input = ctx.createGain();
      this.split = ctx.createChannelSplitter(2);
      this.anL = ctx.createAnalyser(); this.anR = ctx.createAnalyser();
      for (const a of [this.anL, this.anR]) { a.fftSize = this.fft; a.smoothingTimeConstant = 0; a.minDecibels = -120; a.maxDecibels = 0; }
      this.input.connect(this.split);
      this.split.connect(this.anL, 0); this.split.connect(this.anR, 1);
      this.anMono = ctx.createAnalyser(); this.anMono.fftSize = this.fft; this.anMono.smoothingTimeConstant = 0;
      this.anMono.minDecibels = -120; this.anMono.maxDecibels = 0;
      this.input.connect(this.anMono);
      this.monitor = ctx.createGain();
      this.monitor.connect(ctx.destination);
      this.freq = new Float32Array(this.fft / 2);
      this.tL = new Float32Array(this.fft); this.tR = new Float32Array(this.fft);
      this.fx = new FeatureExtractor(ctx.sampleRate, this.fft);
    }
    async useDemo(startBar) {
      this._ensure(); await this.ctx.resume();
      this.stopAll();
      this.demo = new DemoSong(this.ctx);
      this.demo.step = Math.max(0, Math.floor(startBar || 0)) * 16;
      this.demo.out.connect(this.input);
      this.demo.out.connect(this.monitor);
      this.demo.start();
      this.source = 'demo';
    }
    async useMic() {
      this._ensure(); await this.ctx.resume();
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } });
      this.stopAll();
      this.mic = this.ctx.createMediaStreamSource(stream);
      this.micStream = stream;
      this.mic.connect(this.input);
      this.source = 'mic';
    }
    async useFile(file) {
      this._ensure(); await this.ctx.resume();
      this.stopAll();
      const el = new Audio();
      el.src = URL.createObjectURL(file);
      el.loop = true;
      this.el = el;
      this.media = this.ctx.createMediaElementSource(el);
      this.media.connect(this.input);
      this.media.connect(this.monitor);
      await el.play();
      this.source = 'file';
    }
    pause(p) {
      if (!this.ctx) return;
      if (p) { this.ctx.suspend(); if (this.el) this.el.pause(); }
      else { this.ctx.resume(); if (this.el) this.el.play(); }
    }
    stopAll() {
      if (this.demo) { this.demo.stop(); this.demo.out.disconnect(); this.demo = null; }
      if (this.mic) { this.mic.disconnect(); this.micStream.getTracks().forEach((t) => t.stop()); this.mic = null; }
      if (this.el) { this.el.pause(); this.media.disconnect(); this.el = null; }
    }
    read(dt) {
      if (!this.ctx) return null;
      this.anMono.getFloatFrequencyData(this.freq);
      this.anL.getFloatTimeDomainData(this.tL);
      this.anR.getFloatTimeDomainData(this.tR);
      const mono = this.source === 'mic';
      return this.fx.update(this.freq, this.tL, mono ? null : this.tR, dt);
    }
  }

  /* --------------------------------------------------------------- demo song
   * A procedural 124 bpm track with an intro, a groove, a build, a drop and a breakdown, so
   * every look can be judged against quiet, busy and explosive music without a file.
   */
  class DemoSong {
    constructor(ctx) {
      this.ctx = ctx;
      this.out = ctx.createGain();
      this.out.gain.value = 0.8;
      const comp = ctx.createDynamicsCompressor();
      comp.threshold.value = -14; comp.ratio.value = 4; comp.attack.value = 0.005; comp.release.value = 0.15;
      this.bus = ctx.createGain();
      this.bus.connect(comp); comp.connect(this.out);
      // Reverb from a generated tail.
      const len = ctx.sampleRate * 2.2;
      const ir = ctx.createBuffer(2, len, ctx.sampleRate);
      for (let c = 0; c < 2; c++) {
        const d = ir.getChannelData(c);
        for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 3);
      }
      this.verb = ctx.createConvolver(); this.verb.buffer = ir;
      this.verbSend = ctx.createGain(); this.verbSend.gain.value = 0.25;
      this.verbSend.connect(this.verb); this.verb.connect(this.bus);
      // Dotted-eighth delay for the arp, ping-ponged.
      this.delay = ctx.createDelay(1); this.delay.delayTime.value = 60 / 124 * 0.75;
      this.fb = ctx.createGain(); this.fb.gain.value = 0.35;
      this.delayPan = ctx.createStereoPanner(); this.delayPan.pan.value = -0.6;
      this.delay.connect(this.fb); this.fb.connect(this.delay); this.delay.connect(this.delayPan); this.delayPan.connect(this.bus);
      const nlen = ctx.sampleRate * 2;
      this.noise = ctx.createBuffer(1, nlen, ctx.sampleRate);
      const nd = this.noise.getChannelData(0);
      for (let i = 0; i < nlen; i++) nd[i] = Math.random() * 2 - 1;
      this.bpm = 124;
      this.sixteenth = 60 / this.bpm / 4;
      this.step = 0;
      this.chords = [[57, 60, 64], [53, 57, 60], [48, 52, 55], [55, 59, 62]];   // Am F C G
      this.roots = [45, 41, 36, 43];
    }
    start() { this.next = this.ctx.currentTime + 0.1; this.timer = setInterval(() => this._schedule(), 25); }
    stop() { clearInterval(this.timer); }
    _hz(m) { return 440 * Math.pow(2, (m - 69) / 12); }
    _schedule() {
      while (this.next < this.ctx.currentTime + 0.12) { this._play(this.step, this.next); this.next += this.sixteenth; this.step++; }
    }
    _section(bar) {
      const b = bar % 44;
      if (b < 8) return 'intro';
      if (b < 16) return 'groove';
      if (b < 20) return 'build';
      if (b < 36) return 'drop';
      return 'break';
    }
    _play(step, t) {
      const bar = Math.floor(step / 16), s = step % 16, beat = s % 4 === 0;
      const sec = this._section(bar);
      const ci = bar % 4;
      const barInSec = bar % 44;
      if (s === 0) this._pad(t, this.chords[ci], sec);
      if ((sec === 'groove' || sec === 'drop') && beat) this._kick(t, 1);
      if (sec === 'break' && s === 0) this._kick(t, 0.6);
      if ((sec === 'groove' || sec === 'drop') && (s === 4 || s === 12)) this._clap(t);
      if (sec !== 'build') this._hat(t, s % 4 === 2 ? 0.2 : 0.035, s % 4 === 2 && (sec === 'drop') ? 0.5 : (s % 2 ? 0.18 : 0.28));
      if (sec === 'build') {
        const k = barInSec - 16;
        const every = k < 2 ? 4 : k < 3 ? 2 : 1;
        if (s % every === 0) this._snare(t, 0.3 + (k * 16 + s) / 64 * 0.6);
        if (s === 0 && k === 0) this._riser(t, 4 * 16 * this.sixteenth);
      }
      if (sec === 'groove' && (s % 4 === 2)) this._bass(t, this.roots[ci], 0.18, 600);
      if (sec === 'drop') this._bass(t, this.roots[ci] + (s % 8 === 6 ? 12 : 0), 0.1, 900 + 600 * (s % 4 === 2));
      if (sec !== 'groove' || bar % 2) {
        const notes = this.chords[ci];
        const n = notes[s % 3] + 12 * (1 + (Math.floor(s / 3) % 2));
        const open = sec === 'drop' ? 3800 : sec === 'build' ? 800 + (barInSec - 16) * 800 + s * 40 : sec === 'intro' ? 1100 : 1600;
        if (sec !== 'intro' || s % 2 === 0) this._arp(t, n, open, sec === 'drop' ? 0.09 : 0.06);
      }
      if (sec === 'drop' && (s === 3 || s === 11)) this._stab(t, this.chords[ci]);
    }
    _env(g, t, a, peak, d) {
      g.gain.setValueAtTime(0.0001, t);
      g.gain.exponentialRampToValueAtTime(peak, t + a);
      g.gain.exponentialRampToValueAtTime(0.0001, t + a + d);
    }
    _kick(t, v) {
      const c = this.ctx, o = c.createOscillator(), g = c.createGain();
      o.frequency.setValueAtTime(160, t); o.frequency.exponentialRampToValueAtTime(46, t + 0.11);
      this._env(g, t, 0.002, 1.1 * v, 0.42);
      o.connect(g); g.connect(this.bus); o.start(t); o.stop(t + 0.5);
      const n = c.createBufferSource(), hp = c.createBiquadFilter(), ng = c.createGain();
      n.buffer = this.noise; hp.type = 'highpass'; hp.frequency.value = 2500;
      this._env(ng, t, 0.001, 0.25 * v, 0.012);
      n.connect(hp); hp.connect(ng); ng.connect(this.bus); n.start(t); n.stop(t + 0.03);
    }
    _clap(t) {
      const c = this.ctx, n = c.createBufferSource(), bp = c.createBiquadFilter(), g = c.createGain(), p = c.createStereoPanner();
      n.buffer = this.noise; bp.type = 'bandpass'; bp.frequency.value = 1600; bp.Q.value = 0.9; p.pan.value = 0.1;
      g.gain.setValueAtTime(0.0001, t);
      for (let i = 0; i < 3; i++) { g.gain.setValueAtTime(0.7, t + i * 0.011); g.gain.exponentialRampToValueAtTime(0.1, t + i * 0.011 + 0.009); }
      g.gain.setValueAtTime(0.6, t + 0.034); g.gain.exponentialRampToValueAtTime(0.0001, t + 0.22);
      n.connect(bp); bp.connect(g); g.connect(p); p.connect(this.bus); p.connect(this.verbSend);
      n.start(t, Math.random()); n.stop(t + 0.3);
    }
    _snare(t, v) {
      const c = this.ctx, n = c.createBufferSource(), hp = c.createBiquadFilter(), g = c.createGain();
      n.buffer = this.noise; hp.type = 'highpass'; hp.frequency.value = 1200;
      this._env(g, t, 0.001, 0.5 * v, 0.1);
      n.connect(hp); hp.connect(g); g.connect(this.bus); g.connect(this.verbSend); n.start(t, Math.random()); n.stop(t + 0.15);
      const o = c.createOscillator(), og = c.createGain(); o.type = 'triangle'; o.frequency.value = 200;
      this._env(og, t, 0.001, 0.3 * v, 0.07); o.connect(og); og.connect(this.bus); o.start(t); o.stop(t + 0.1);
    }
    _hat(t, len, v) {
      const c = this.ctx, n = c.createBufferSource(), hp = c.createBiquadFilter(), g = c.createGain(), p = c.createStereoPanner();
      n.buffer = this.noise; hp.type = 'highpass'; hp.frequency.value = 8000; p.pan.value = 0.35;
      this._env(g, t, 0.001, v, len);
      n.connect(hp); hp.connect(g); g.connect(p); p.connect(this.bus); n.start(t, Math.random()); n.stop(t + len + 0.05);
    }
    _riser(t, dur) {
      const c = this.ctx, n = c.createBufferSource(), bp = c.createBiquadFilter(), g = c.createGain();
      n.buffer = this.noise; n.loop = true; bp.type = 'bandpass'; bp.Q.value = 2;
      bp.frequency.setValueAtTime(400, t); bp.frequency.exponentialRampToValueAtTime(9000, t + dur);
      g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.35, t + dur); g.gain.setValueAtTime(0.0001, t + dur + 0.01);
      n.connect(bp); bp.connect(g); g.connect(this.bus); g.connect(this.verbSend); n.start(t); n.stop(t + dur + 0.05);
    }
    _bass(t, m, len, cut) {
      const c = this.ctx, o = c.createOscillator(), s = c.createOscillator(), f = c.createBiquadFilter(), g = c.createGain();
      o.type = 'sawtooth'; o.frequency.value = this._hz(m); s.type = 'sine'; s.frequency.value = this._hz(m - 12);
      f.type = 'lowpass'; f.Q.value = 6; f.frequency.setValueAtTime(cut * 2, t); f.frequency.exponentialRampToValueAtTime(cut * 0.4, t + len);
      this._env(g, t, 0.004, 0.45, len);
      o.connect(f); s.connect(g); f.connect(g); g.connect(this.bus);
      o.start(t); s.start(t); o.stop(t + len + 0.05); s.stop(t + len + 0.05);
    }
    _pad(t, chord, sec) {
      const c = this.ctx, bar = 16 * this.sixteenth;
      const f = c.createBiquadFilter(), g = c.createGain();
      f.type = 'lowpass'; f.frequency.value = sec === 'drop' ? 2200 : sec === 'build' ? 1400 : 900; f.Q.value = 1;
      const v = sec === 'drop' ? 0.1 : 0.14;
      g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(v, t + 0.35);
      g.gain.setValueAtTime(v, t + bar - 0.15); g.gain.exponentialRampToValueAtTime(0.0001, t + bar + 0.4);
      f.connect(g); g.connect(this.bus); g.connect(this.verbSend);
      for (const m of chord) for (const det of [-7, 7]) {
        const o = c.createOscillator(); o.type = 'sawtooth'; o.frequency.value = this._hz(m); o.detune.value = det;
        const p = c.createStereoPanner(); p.pan.value = det > 0 ? 0.4 : -0.4;
        o.connect(p); p.connect(f); o.start(t); o.stop(t + bar + 0.5);
      }
    }
    _arp(t, m, cut, v) {
      const c = this.ctx, o = c.createOscillator(), f = c.createBiquadFilter(), g = c.createGain();
      o.type = 'square'; o.frequency.value = this._hz(m);
      f.type = 'lowpass'; f.frequency.value = cut; f.Q.value = 4;
      this._env(g, t, 0.002, v, 0.14);
      o.connect(f); f.connect(g); g.connect(this.bus); g.connect(this.delay);
      o.start(t); o.stop(t + 0.2);
    }
    _stab(t, chord) {
      const c = this.ctx, f = c.createBiquadFilter(), g = c.createGain();
      f.type = 'lowpass'; f.frequency.setValueAtTime(5000, t); f.frequency.exponentialRampToValueAtTime(600, t + 0.2);
      this._env(g, t, 0.003, 0.12, 0.22);
      f.connect(g); g.connect(this.bus); g.connect(this.verbSend);
      for (const m of chord) {
        const o = c.createOscillator(); o.type = 'sawtooth'; o.frequency.value = this._hz(m + 12);
        o.connect(f); o.start(t); o.stop(t + 0.3);
      }
    }
  }

  /** A believable stand-in when no audio is running at all (thumbnails, first paint). */
  function idleFeatures(fx, t, dt) {
    const db = new Float32Array(1024), L = new Float32Array(2048), R = new Float32Array(2048);
    for (let i = 0; i < 1024; i++) db[i] = -55 - i * 0.03 + 8 * Math.sin(t * 2 + i * 0.05);
    for (let i = 0; i < 2048; i++) { L[i] = 0.2 * Math.sin(i * 0.03 + t * 5); R[i] = 0.2 * Math.cos(i * 0.031 + t * 5); }
    return fx.update(db, L, R, dt);
  }

  global.VisAudio = { FeatureExtractor, AudioRig, DemoSong, idleFeatures, NB, NW, NH };
})(typeof window !== 'undefined' ? window : globalThis);
