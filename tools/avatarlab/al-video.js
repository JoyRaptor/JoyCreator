/* AvatarLab: VIDEO. The take made into an ordinary video file: a character alone, or the whole scene with its camera lane, at the
 * frame's shape, with the take's voice as its sound.
 *
 * HOW. Not a screen recording: every picture is made on purpose, at its exact moment (1/30 s apart), by FRESH copies of the
 * performers' own engines playing their takes from the start, so the video is the same however slow the device is, and the room
 * on the screen is not touched while it is made. Each picture is drawn the way the One frame picture is (the same painting, the same
 * tick boxes), laid over the stage or over a flat key colour (never see-through), and handed to the browser's own video encoder
 * (WebCodecs: H.264). The voices are mixed at the take's own times and handed to its sound encoder (AAC). al-mp4.js puts them in one
 * MP4. It shows how far it has got and can be stopped at any moment.
 *
 * WHERE IT WORKS. A browser with WebCodecs and an H.264 encoder (Chrome and Edge on a computer or an Android phone; Safari 16.4 and
 * later). The sizes offered are only those this browser says it can make; the sound needs an AAC encoder. Elsewhere the choice is
 * not offered, and the sheet says what to do instead.
 *
 * Phone: Video Studio's ExportService / ExportManager (Media3 Transformer, H.264 + AAC, MP4; 1080p / 720p / 480p), with the
 * character as an avatar SpriteOverlayItem playing its AvatarParamTrack, the voice as an AudioClip, and a solid-colour image
 * clip underneath for the key colour (Studio has no background colour setting of its own).
 */
(function (root) {
  'use strict';
  var AL = root.AL, scene = AL.scene, frame = AL.frame, Vd = AL.video = {}, FPS = 30, W = scene.W, H = scene.H;
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  Vd.FPS = FPS;
  /** Can this browser make a video at all? (Which sizes it can make: Vd.sizes.) */
  Vd.can = function () { return !!(root.VideoEncoder && root.VideoFrame && root.EncodedVideoChunk && root.MessageChannel); };
  Vd.SIZES = [[1080, '1080p'], [720, '720p'], [480, '480p']];           // (Video Studio's own list, by the short side)
  function even(x) { return Math.max(2, Math.round(x / 2) * 2); }
  /** The picture size for a short side, at this shape (ar = width / height). */
  Vd.dims = function (ar, s) { return ar >= 1 ? { w: even(s * ar), h: s } : { w: s, h: even(s / ar) }; };
  function level(w, h) { var mb = Math.ceil(w / 16) * Math.ceil(h / 16); return mb <= 3600 ? '1f' : mb <= 8192 ? '28' : mb <= 22080 ? '32' : '33'; }
  function bitrate(w, h) { return Math.round(clamp(w * h * FPS * 0.13, 1.2e6, 12e6)); }
  var CFG = {}, ACFG = null;
  /** The encoder settings that work here for w x h (H.264: High, then Main, then Baseline), or null. */
  Vd.config = function (w, h) {
    var key = w + 'x' + h; if (CFG[key]) return CFG[key];
    var lv = level(w, h), list = ['avc1.6400' + lv, 'avc1.4d00' + lv, 'avc1.42e0' + lv];
    CFG[key] = (function next(i) {
      if (i >= list.length || !Vd.can()) return Promise.resolve(null);
      var c = { codec: list[i], width: w, height: h, bitrate: bitrate(w, h), framerate: FPS, avc: { format: 'avc' } };
      return VideoEncoder.isConfigSupported(c).then(function (r) { return r && r.supported ? c : next(i + 1); }, function () { return next(i + 1); });
    })(0);
    return CFG[key];
  };
  /** The sound settings that work here (AAC, mono), or null. */
  Vd.audioConfig = function () {
    if (ACFG) return ACFG;
    ACFG = (function next(rates) {
      if (!rates.length || !root.AudioEncoder || !root.AudioData) return Promise.resolve(null);
      var c = { codec: 'mp4a.40.2', sampleRate: rates[0], numberOfChannels: 1, bitrate: 128000 };
      return AudioEncoder.isConfigSupported(c).then(function (r) { return r && r.supported ? c : next(rates.slice(1)); }, function () { return next(rates.slice(1)); });
    })([48000, 44100]);
    return ACFG;
  };
  /** The sizes this browser can make at the frame's shape: [{ s, label, w, h, cfg }], biggest first. */
  Vd.sizes = function (app) {
    var ar = frame.ar(app);
    return Promise.all(Vd.SIZES.map(function (q) { var d = Vd.dims(ar, q[0]); return Vd.config(d.w, d.h).then(function (cfg) { return cfg ? { s: q[0], label: q[1], w: d.w, h: d.h, cfg: cfg } : null; }); }))
      .then(function (l) { return l.filter(Boolean); });
  };

  /* ── what is in the video ── */
  function R0(app) { return scene.on(app) ? app.scene : null; }
  /** A performer of its own for the video: the character's engine, playing the take, and nothing else. */
  function performer(ch, take, flip) {
    var mini = { ch: ch, mode: 'perform', touch: function () { }, refreshPanel: function () { } }, inp = new AL.Inputs(mini); mini.inp = inp;
    if (take && take.n) inp.take = take; inp.pose.flip = !!flip;
    return { ch: ch, inp: inp, solver: new AL.Solver(ch), out: null, T: null, lk: 0 };
  }
  function has(a) { return !!(a.inp.take && a.inp.take.n); }
  /* one moment of one performer: at the take's place (it holds its last pose after its take ends), or alive by itself without a take */
  function stepAt(a, t, dt) {
    if (has(a)) { a.inp.playing = false; a.inp.recording = false; a.inp.scrub = true; a.inp.ph = Math.min(t, a.inp.duration()); }
    return a.inp.step(dt);
  }
  /**
   * Everything needed to make the video, as it is now: { len, n, voices, ar, draw(k, w, h) -> canvas of picture k (see-through where
   * nothing is drawn) }. skip = what is ticked off. Null when there is nothing to make (no take).
   */
  Vd.plan = function (app, skip) {
    var R = R0(app); skip = skip || {};
    return R ? scenePlan(app, R, skip) : plainPlan(app);
  };
  function plainPlan(app) {
    var take = app.inp && app.inp.take; if (!app.ch || !take || !take.n) return null;
    var a = performer(app.ch, take, app.inp.pose.flip), F = frame.plainRect(app), V0 = app.vt || app.view, V = { s: V0.s, ox: V0.ox, oy: V0.oy }, len = take.n / take.fps, at = -1;
    function go(k) {
      if (at < 0) { for (var i = 0; i < 8; i++) a.out = a.solver.eval(stepAt(a, 0, 1 / FPS), { live: true, dt: 1 / FPS, force: null, show: null }); at = 0; }      // (settled first: hair and tails come to rest)
      while (at <= k) { var t = at / FPS; a.out = a.solver.eval(stepAt(a, t, 1 / FPS), { live: true, dt: 1 / FPS, force: null, show: null }); at++; }
    }
    return { len: len, n: Math.max(1, Math.round(len * FPS)), voices: AL.voice && AL.voice.has(take) ? [take.voice] : [], ar: frame.ar(app),
      draw: function (k, w, h) {
        go(k); var X = frame.gl(w, h), s = w / F.w;
        X.G.keep = false; X.G.draw(a.out.items.filter(function (it) { return !it.ghost; }), { s: V.s * s, ox: (V.ox - F.x) * s, oy: (V.oy - F.y) * s }, null, a.out.colour);
        return X.c;
      } };
  }
  function scenePlan(app, R, skip) {
    var d = R.data, cast = scene.cast(R), len = scene.length(app); if (!cast.length || len <= 0) return null;
    var X = { data: d, actors: {}, t: 0, cam: { x: W / 2, y: H / 2, z: 1 }, shot: 'wide', easing: null, cutNow: true, free: R.free ? { x: R.free.x, y: R.free.y, z: R.free.z } : null, camAt: null, f: 1, cx: 0, cy: 0, clip: null, focus: R.focus },
      fake = { scene: X }, fr = scene.frame(d), lane = (d.cam || []).slice().sort(function (p, q) { return p.t - q.t; }), lead = R.focus ? scene.item(R, R.focus) : null, at = -1, voices = [];
    cast.forEach(function (it) { var o = R.actors[it.id]; X.actors[it.id] = performer(o.ch, o.inp.take, it.flip); if (!skip[it.id] && AL.voice && AL.voice.has(o.inp.take)) voices.push(o.inp.take.voice); });
    function setShot(key, ease) { X.shot = key; if (ease > 0) X.easing = { from: { x: X.cam.x, y: X.cam.y, z: X.cam.z }, t0: X.t, dur: ease }; else { X.easing = null; X.cutNow = true; } }
    function moment(t, dt) {
      X.t += dt;
      cast.forEach(function (it) {
        var a = X.actors[it.id]; a.T = scene.place(d, it, a.ch); var S = stepAt(a, t, dt);
        if (lead && !has(a) && it.id !== R.focus) { var dir = clamp((lead.x - it.x) / 420, -1, 1) * (S.flip ? -1 : 1); a.lk += (dir - a.lk) * (1 - Math.exp(-3 * dt)); S.lookX = clamp((S.lookX || 0) + a.lk * 0.85, -1, 1); S.turnX = clamp((S.turnX || 0) + a.lk * 0.4, -1, 1); }      // (nobody drives this one: it watches the one in hand, as on the stage)
        a.out = a.solver.eval(S, { live: true, dt: dt, force: null, show: null });
      });
    }
    function camera(t, dt) {                                             // the camera lane, exactly as the scene's clock reads it (al-scene-take.js)
      if (!frame.on()) return;
      var e = null; for (var i = 0; i < lane.length; i++) { if (lane[i].t <= t + 1e-4) e = lane[i]; else break; }
      if (e && e !== X.camAt) { X.camAt = e; if (scene.shotOk(fake, e.shot)) setShot(e.shot, at < 0 ? 0 : e.ease || 0); }
      scene.camNow(fake, fr, dt);
    }
    function go(k) {
      if (at < 0) {
        for (var i = 0; i < 8; i++) moment(0, 1 / FPS);                   // (settled first: hair and tails come to rest)
        var s0 = R.shot && R.shot !== 'free' ? R.shot : R.shot === 'free' && X.free ? 'free' : 'wide'; setShot(scene.shotOk(fake, s0) || s0 === 'free' ? s0 : 'wide', 0); camera(0, 0);
        at = 0;
      }
      while (at <= k) { var t = at / FPS; camera(t, 1 / FPS); moment(t, 1 / FPS); at++; }
    }
    return { len: len, n: Math.max(1, Math.round(len * FPS)), voices: voices, ar: fr.w / fr.h,
      draw: function (k, w, h) { go(k); return scene.picture(fake, w, h, skip); } };
  }

  /* ── making it ── */
  function later() { return new Promise(function (r) { var m = new MessageChannel(); m.port1.onmessage = function () { r(); }; m.port2.postMessage(0); }); }
  /** The plan's voices mixed at the take's own times, rate a second, exactly secs long. */
  Vd.mix = function (voices, rate, secs) {
    var n = Math.max(1, Math.round(secs * rate)); if (!voices.length) return Promise.resolve(null);
    var C = root.OfflineAudioContext || root.webkitOfflineAudioContext; if (!C) return Promise.resolve(null);
    var c = new C(1, n, rate);
    voices.forEach(function (v) { var p = AL.voice.pcm(v); if (!p || !p.d.length) return; var b = c.createBuffer(1, p.d.length, p.rate); b.getChannelData(0).set(p.d); var s = c.createBufferSource(); s.buffer = b; s.connect(c.destination); s.start(0); });
    return c.startRendering().then(function (buf) { var d = buf.getChannelData(0), pk = 0, i; for (i = 0; i < d.length; i++) pk = Math.max(pk, Math.abs(d[i])); if (pk > 0.99) for (i = 0; i < d.length; i++) d[i] *= 0.99 / pk; return d; });      // (several voices at once never clip)
  };
  /** The picture behind: the plain dark stage, or one flat colour. */
  Vd.ground = function (g, w, h, key) { if (key) { g.fillStyle = key; g.fillRect(0, 0, w, h); } else frame.ground(g, w, h); };
  /**
   * Make the video. o = { size: { w, h, cfg }, key: '#rrggbb' or null (the stage), skip, progress(f, words), stop: { now } }.
   * Resolves { blob, len, w, h, frames, sound } or null when stopped. Rejects with a plain sentence when it cannot be made.
   */
  Vd.make = function (app, o) {
    var plan = Vd.plan(app, o.skip), w = o.size.w, h = o.size.h, stop = o.stop || {}, pr = o.progress || function () { };
    if (!plan) return Promise.reject(new Error('There is no take to make a video of yet.'));
    var chunks = [], avcC = null, bad = null, enc, c2 = document.createElement('canvas'); c2.width = w; c2.height = h; var g = c2.getContext('2d');
    var playMs = Math.round(plan.n / FPS * 1000);
    try {
      enc = new VideoEncoder({ output: function (c, m) { var b = new Uint8Array(c.byteLength); c.copyTo(b); chunks.push({ data: b, key: c.type === 'key', ts: c.timestamp }); if (m && m.decoderConfig && m.decoderConfig.description && !avcC) { var dd = m.decoderConfig.description; avcC = ArrayBuffer.isView(dd) ? new Uint8Array(dd.buffer.slice(dd.byteOffset, dd.byteOffset + dd.byteLength)) : new Uint8Array(dd.slice(0)); } }, error: function (e) { bad = e; } });
      enc.configure(o.size.cfg);
    } catch (e) { return Promise.reject(e); }
    var k = 0;
    function pictures() {
      if (stop.now) return Promise.resolve(false); if (bad) return Promise.reject(bad);
      if (k >= plan.n) return enc.flush().then(function () { return true; });
      var pic = plan.draw(k, w, h);
      Vd.ground(g, w, h, o.key); if (pic) g.drawImage(pic, 0, 0, w, h);
      var vf = new VideoFrame(c2, { timestamp: Math.round(k * 1e6 / FPS), duration: Math.round(1e6 / FPS) });
      enc.encode(vf, { keyFrame: k % (FPS * 2) === 0 }); vf.close(); k++;
      pr(0.9 * k / plan.n, 'Picture ' + k + ' of ' + plan.n);
      return (function drain() { return enc.encodeQueueSize > 2 ? later().then(drain) : later(); })().then(pictures);
    }
    var sound = null;
    return pictures().then(function (done) {
      try { enc.close(); } catch (e) { }
      if (!done) return null;
      if (!avcC || chunks.length !== plan.n) throw new Error('the encoder gave ' + chunks.length + ' of ' + plan.n + ' pictures');
      for (var i = 1; i < chunks.length; i++) if (chunks[i].ts <= chunks[i - 1].ts) throw new Error('pictures out of order');
      pr(0.92, 'The sound');
      return (plan.voices.length ? Vd.audioConfig() : Promise.resolve(null)).then(function (acfg) {
        if (!acfg || stop.now) return null;
        return Vd.mix(plan.voices, acfg.sampleRate, plan.n / FPS).then(function (pcm) { return pcm ? encodeSound(pcm, acfg) : null; });
      }).then(function (a) {
        if (stop.now) return null; sound = a;
        pr(0.98, 'Writing the file');
        var blob = AL.mp4.make({ w: w, h: h, timescale: FPS * 1000, frameDur: 1000, avcC: avcC, samples: chunks }, a, playMs);
        pr(1, 'Done');
        return { blob: blob, len: plan.n / FPS, w: w, h: h, frames: plan.n, sound: !!a, voices: plan.voices.length };
      });
    }, function (e) { try { enc.close(); } catch (x) { } throw e; });
  };
  function encodeSound(pcm, cfg) {
    var out = [], asc = null, bad = null, ae = new AudioEncoder({ output: function (c, m) { var b = new Uint8Array(c.byteLength); c.copyTo(b); out.push({ data: b, dur: Math.round((c.duration || 1024 * 1e6 / cfg.sampleRate) * cfg.sampleRate / 1e6) }); if (m && m.decoderConfig && m.decoderConfig.description && !asc) { var dd = m.decoderConfig.description; asc = ArrayBuffer.isView(dd) ? new Uint8Array(dd.buffer.slice(dd.byteOffset, dd.byteOffset + dd.byteLength)) : new Uint8Array(dd.slice(0)); } }, error: function (e) { bad = e; } });
    ae.configure(cfg);
    var step = 4800; for (var i = 0; i < pcm.length; i += step) { var part = pcm.subarray(i, Math.min(pcm.length, i + step)); ae.encode(new AudioData({ format: 'f32', sampleRate: cfg.sampleRate, numberOfFrames: part.length, numberOfChannels: 1, timestamp: Math.round(i / cfg.sampleRate * 1e6), data: part.slice() })); }
    return ae.flush().then(function () { ae.close(); if (bad) throw bad; if (!asc) asc = ascFor(cfg); return { rate: cfg.sampleRate, channels: 1, asc: asc, bitrate: cfg.bitrate, samples: out }; });
  }
  /* AAC-LC's two-byte description, for an encoder that does not give one */
  function ascFor(cfg) { var idx = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000].indexOf(cfg.sampleRate); return new Uint8Array([(2 << 3) | (idx >> 1), ((idx & 1) << 7) | (cfg.numberOfChannels << 3)]); }
})(typeof self !== 'undefined' ? self : this);
