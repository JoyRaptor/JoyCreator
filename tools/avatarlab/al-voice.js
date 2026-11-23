/* AvatarLab: THE VOICE, KEPT WITH A TAKE. What the microphone heard, or the voice file that was playing, while a take was recorded.
 *
 * A take stores signals, not pixels (al-inputs.js). The mouth already followed the voice; now the voice itself is kept too, as one
 * plain sound beside the signals, exactly as long as the take and starting with it, so Play has its sound and a video has its sound.
 *
 * THE SAME RULE AS EVERY OTHER CHANNEL. A pass with the voice on (the Mic, or a Voice file playing) replaces the voice for the
 * stretch it was on, and only that stretch (a few milliseconds are blended at each end, so there is no click). A pass without it
 * leaves the voice alone. Undo takes the pass back whole, voice and all. Clearing the take clears its voice.
 *
 * IN STEP. While the voice is being kept, the take's clock is the sound's own clock: the picture can drop frames, the sound cannot,
 * so the take follows the sound, and the two can never drift apart.
 *
 * Kept as take.voice = { rate, n, wav }: a mono 16-bit WAV (24 000 a second), as a data URL inside the take, so it goes everywhere
 * the take goes (this browser's store, the character file, a scene file) with nothing beside it. A WAV of any rate reads back.
 * Hearing it: al-voice-play.js.
 *
 * Phone: Video Studio's own recorder, plugged in: VoiceoverRecorder (AudioRecord, a WAV) started at the take's first frame and
 * stopped with it; the stretch rule and the blend are this file's few lines, done on the WAV afterwards.
 */
(function (root) {
  'use strict';
  var AL = root.AL, V = AL.voice = {}, RATE = V.RATE = 24000, FADE = Math.round(0.006 * RATE);
  function now(inp) { return inp.actx ? inp.actx.currentTime : 0; }
  function live(inp) { return !!(inp.mic.on && inp.actx && inp.actx.state === 'running'); }

  /* ── listening in: a tap on the voice, just after the analyser the lip-sync already reads ── */
  var TAP = 'class T extends AudioWorkletProcessor{constructor(){super();this.on=false;this.b=new Float32Array(4096);this.n=0;this.f=0;this.port.onmessage=e=>{if(e.data===2){this.out();return}this.on=!!e.data;if(!this.on)this.out()}}' +
    'out(){if(this.n)this.port.postMessage({f0:this.f,d:this.b.slice(0,this.n)});this.n=0}' +
    'process(i){if(!this.on)return true;var c=i[0]&&i[0][0],L=c?c.length:128;if(!this.n)this.f=currentFrame;if(c)this.b.set(c,this.n);else this.b.fill(0,this.n,this.n+L);this.n+=L;if(this.n+128>this.b.length)this.out();return true}}' +
    'registerProcessor("al-voice-tap",T);';
  var mods = typeof WeakMap === 'function' ? new WeakMap() : null;
  /** Make the listening part ready for this sound graph (once), so the first words of a pass are not lost while it loads. */
  V.prime = function (ctx) {
    if (!ctx || !mods) return Promise.resolve(false); if (mods.has(ctx)) return mods.get(ctx);
    var p = Promise.resolve(false);
    try { if (ctx.audioWorklet && root.AudioWorkletNode) { var url = URL.createObjectURL(new Blob([TAP], { type: 'text/javascript' })); p = ctx.audioWorklet.addModule(url).then(function () { URL.revokeObjectURL(url); return true; }); } } catch (e) { }
    p = p.catch(function () { return false; }); mods.set(ctx, p); return p;
  };
  function capOf(inp) { var c = inp._vcap; if (c && c.ctx === inp.actx) return c; return (inp._vcap = { ctx: inp.actx, sr: inp.actx.sampleRate, chunks: [], node: null, on: false, arm: null }); }
  /**
   * The tap: an AudioWorklet where there is one (sample-exact: it says which sound frame each block began on); a ScriptProcessor
   * where there is not (near enough). Made once per sound graph, as soon as there is a voice to listen to.
   */
  V.tap = function (inp) {
    if (!inp.actx || !inp.an) return null;
    var c = capOf(inp), ctx = c.ctx;
    if (c.node) { inp.an.connect(c.node); return c; }                   // (again each time: stopping a voice file lets go of everything after the analyser)
    if (c.wait) return c; c.wait = true;
    V.prime(ctx).then(function (ok) {
      var n = null;
      if (ok) try {
        n = new AudioWorkletNode(ctx, 'al-voice-tap', { numberOfInputs: 1, numberOfOutputs: 1, channelCount: 1, channelCountMode: 'explicit' });
        n.port.onmessage = function (e) { c.chunks.push({ f0: e.data.f0, d: e.data.d }); }; c.arm = function (m) { n.port.postMessage(m); };
      } catch (e) { n = null; }
      if (!n) {
        n = ctx.createScriptProcessor(2048, 1, 1); c.arm = function () { };
        n.onaudioprocess = function (e) { if (!c.on) return; var d = e.inputBuffer.getChannelData(0); c.chunks.push({ f0: Math.round(e.playbackTime * c.sr) - 2 * d.length - 256, d: d.slice() }); };      // (what it hears is two blocks and two render steps behind what it would play: measured against the worklet in Chrome, 11-22)
      }
      n.connect(ctx.destination); c.node = n; c.wait = false; if (inp.an) inp.an.connect(n); c.arm(c.on ? 1 : 0);
    });
    return c;
  };
  function arm(c, on) { c.on = !!on; if (c.arm) c.arm(c.on ? 1 : 0); }
  function heardTo(c) { var q = c.chunks[c.chunks.length - 1]; return q ? (q.f0 + q.d.length) / c.sr : 0; }

  /* ── a pass ── */
  /** A recording pass begins (P.record). */
  V.pass = function (inp) { inp._vr = { take: inp.take, fresh: !!inp.fresh, open: null, list: [], last: 0, after: inp._vjob || null }; if (inp.actx) V.tap(inp); };
  function close(r) { var o = r.open; if (!o) return; o.ph1 = o.ph0 + (r.last - o.t0); if (o.ph1 > o.ph0) r.list.push(o); r.open = null; }
  /**
   * How far the take's clock moves this frame (called from P.step). While the voice is being kept it is the sound's own clock;
   * otherwise the frame's. This is also where a stretch begins (the voice came on) and ends (it went off), at the take's place then.
   */
  V.clock = function (inp, dt) {
    var r = inp._vr; if (!r || !inp.recording || r.take !== inp.take) return dt;
    var on = live(inp), t = on ? now(inp) : 0;
    if (on && !r.open) { var c = V.tap(inp); if (c) { if (!r.list.length) c.chunks = []; arm(c, true); } r.open = { ph0: inp.ph, t0: t }; r.last = t; return 0; }      // (from here the take's place is ph0 + the sound's time since t0)
    if (!on && r.open) close(r);
    if (!r.open) return dt;
    var d = t - r.last; r.last = t; return d > 0 ? d : 0;
  };
  /** The pass ends (P.stop). Once the last of its sound is in, the voice is made again for the whole take. */
  V.endPass = function (inp) {
    var r = inp._vr, tk = inp.take; inp._vr = null; if (!r || r.take !== tk || !tk) return Promise.resolve(false);
    close(r); var c = inp._vcap && inp._vcap.ctx === inp.actx ? inp._vcap : null;
    var need = 0; r.list.forEach(function (s) { need = Math.max(need, s.t0 + (s.ph1 - s.ph0)); });
    var lenNow = Math.round(tk.n / tk.fps * RATE), grew = !!(tk.voice && tk.voice.n !== lenNow), t0 = performance.now();
    if (!r.list.length && !grew) { if (c) arm(c, false); return Promise.resolve(false); }
    if (c && c.arm) c.arm(2);                                            // (send what is waiting in the tap now)
    var job = Promise.resolve(r.after).then(function () {
      return new Promise(function (res) {
        (function wait() {
          if (r.list.length && c && heardTo(c) < need && performance.now() - t0 < 800) { setTimeout(wait, 15); return; }
          if (c) arm(c, false);
          if (inp.take !== tk) { res(false); return; }                    // (taken back, or thrown away, meanwhile)
          tk.voice = compose(tk, r.fresh ? null : tk.voice, r.list, c); if (c) c.chunks = [];
          if (inp.app && inp.app.touch) inp.app.touch();
          res(!!tk.voice);
        })();
      });
    });
    inp._vjob = job; job.then(function () { if (inp._vjob === job) inp._vjob = null; });
    return job;
  };
  /** The pass is taken back (P.undoPass): nothing of it is kept. */
  V.cancel = function (inp) { var c = inp._vcap; inp._vr = null; if (c) { arm(c, false); c.chunks = []; } };

  /* ── making the voice for the whole take: the one it had, with this pass's stretches laid over it ── */
  /* what was heard, read in order: one sound frame at a time, across the tap's blocks */
  function reader(c) {
    var ch = c ? c.chunks : [], i = 0;
    return function (f) {
      if (!ch.length) return 0;
      while (i > 0 && f < ch[i].f0) i--;
      while (i < ch.length - 1 && f >= ch[i].f0 + ch[i].d.length) i++;
      var q = ch[i], k = f - q.f0; return k >= 0 && k < q.d.length ? q.d[k] : 0;
    };
  }
  function compose(tk, base, list, c) {
    var len = Math.round(tk.n / tk.fps * RATE), out = new Float32Array(len), old = base ? V.pcm(base) : null, k;
    if (!old && !list.length) return null;
    if (old) { if (old.rate === RATE) out.set(old.d.subarray(0, Math.min(len, old.d.length))); else for (k = 0; k < len; k++) out[k] = sampleAt(old, k / RATE); }
    var get = reader(c), sr = c ? c.sr : RATE, sm = sr / RATE >= 1.5;      // (a gentle three-tap smoothing before dropping to 24 000 a second, so it does not alias)
    function heard(t) { var x = t * sr, i = Math.floor(x), f = x - i; function s(j) { return sm ? 0.25 * get(j - 1) + 0.5 * get(j) + 0.25 * get(j + 1) : get(j); } return s(i) * (1 - f) + s(i + 1) * f; }
    list.forEach(function (s) {
      var p0 = Math.max(0, Math.round(s.ph0 * RATE)), p1 = Math.min(len, Math.round(s.ph1 * RATE));
      for (k = p0; k < p1; k++) { var a = Math.min(1, (k - p0 + 1) / FADE, (p1 - k) / FADE); out[k] = out[k] * (1 - a) + heard(s.t0 + (k / RATE - s.ph0)) * a; }
    });
    var v = { rate: RATE, n: len, wav: V.wav(out, RATE) }; cache(v, { rate: RATE, d: out }); return v;
  }
  function sampleAt(p, t) { var x = t * p.rate, i = Math.floor(x), f = x - i, d = p.d; return i + 1 < d.length ? d[i] * (1 - f) + d[i + 1] * f : i < d.length ? d[i] : 0; }

  /* ── the WAV: 44 bytes of header, then the samples ── */
  /** A mono 16-bit WAV of these samples, as a data URL. */
  V.wav = function (f, rate) {
    var n = f.length, b = new Uint8Array(44 + n * 2), dv = new DataView(b.buffer), i;
    function str(o, s) { for (var j = 0; j < s.length; j++) b[o + j] = s.charCodeAt(j); }
    str(0, 'RIFF'); dv.setUint32(4, 36 + n * 2, true); str(8, 'WAVE'); str(12, 'fmt '); dv.setUint32(16, 16, true); dv.setUint16(20, 1, true); dv.setUint16(22, 1, true);
    dv.setUint32(24, rate, true); dv.setUint32(28, rate * 2, true); dv.setUint16(32, 2, true); dv.setUint16(34, 16, true); str(36, 'data'); dv.setUint32(40, n * 2, true);
    for (i = 0; i < n; i++) { var s = f[i] < -1 ? -1 : f[i] > 1 ? 1 : f[i]; dv.setInt16(44 + i * 2, s < 0 ? s * 32768 : s * 32767, true); }
    var bin = ''; for (i = 0; i < b.length; i += 0x8000) bin += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000));
    return 'data:audio/wav;base64,' + btoa(bin);
  };
  /** A WAV data URL read back: { rate, d } (its first channel; 16-bit PCM). Null if it is not one. */
  V.unwav = function (url) {
    try {
      var s = String(url), bin = atob(s.slice(s.indexOf(',') + 1)), b = new Uint8Array(bin.length), i; for (i = 0; i < bin.length; i++) b[i] = bin.charCodeAt(i);
      var dv = new DataView(b.buffer), o = 12, rate = 0, chans = 1, bits = 16;
      while (o + 8 <= b.length) {
        var id = String.fromCharCode(b[o], b[o + 1], b[o + 2], b[o + 3]), sz = dv.getUint32(o + 4, true);
        if (id === 'fmt ') { chans = dv.getUint16(o + 10, true); rate = dv.getUint32(o + 12, true); bits = dv.getUint16(o + 22, true); }
        else if (id === 'data') {
          if (bits !== 16 || !rate) return null; var step = 2 * chans, n = Math.floor(Math.min(sz, b.length - o - 8) / step), d = new Float32Array(n);
          for (i = 0; i < n; i++) d[i] = dv.getInt16(o + 8 + i * step, true) / 32768;
          return { rate: rate, d: d };
        }
        o += 8 + sz + (sz & 1);
      }
    } catch (e) { }
    return null;
  };
  var CACHE = typeof WeakMap === 'function' ? new WeakMap() : null;
  function cache(v, p) { if (CACHE) CACHE.set(v, p); return p; }
  /** A take's voice as samples (read once, then kept beside it). */
  V.pcm = function (v) { if (!v || !v.wav) return null; var p = CACHE && CACHE.get(v); if (p) return p; p = V.unwav(v.wav); return p ? cache(v, p) : null; };
  /** Does this take have a voice? */
  V.has = function (tk) { return !!(tk && tk.n && tk.voice && tk.voice.wav); };
  /** How long its voice is, in seconds. */
  V.seconds = function (tk) { var p = V.has(tk) ? V.pcm(tk.voice) : null; return p ? p.d.length / p.rate : 0; };
})(typeof self !== 'undefined' ? self : this);
