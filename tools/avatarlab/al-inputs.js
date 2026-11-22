/* AvatarLab: where the performance comes from.
 * Touch, voice, face camera, the automatic "life" (blinks, breathing, eyes that lead the head) and
 * recorded takes all end up as ONE small record of signals per frame, which the solver draws.
 * A take stores those signals, never pixels, so a replay is the same character re-solved
 * (phone twins: TrackingDriverBus, LifeSignals, SpectralVisemeAnalyzer, AvatarParamTrack).
 */
(function (root) {
  'use strict';
  var AL = root.AL = root.AL || {};
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function ease(cur, to, rate, dt) { return cur + (to - cur) * (1 - Math.exp(-rate * dt)); }

  var GROUPS = AL.TAKE_GROUPS = {
    head: ['turnX', 'turnY', 'roll', 'lookX', 'lookY'], face: ['mouth', 'vis', 'browL', 'browR', 'blinkL', 'blinkR', 'lidUp', 'lidLow', 'lidTilt'],
    handL: ['hLx', 'hLy'], handR: ['hRx', 'hRy'], body: ['sway', 'bounce', 'flip', 'wind', 'rootX', 'rootY', 'rootS', 'walk'], footL: ['fLx', 'fLy'], footR: ['fRx', 'fRy'], trig: []
  };
  AL.TAKE_LABELS = { head: 'Head', face: 'Face', handL: 'Left hand', handR: 'Right hand', body: 'Body', trig: 'Buttons' };

  AL.Inputs = function (app) {
    this.app = app; this.t = 0;
    this.pose = { turnX: 0, turnY: 0, roll: 0, sway: 0, bounce: 0, mouth: 0, brow: 0, lidUp: 0, lidLow: 0, lidTilt: 0, wind: 0, mood: 'Neutral', handL: null, handR: null, footL: null, footR: null, dial: {}, flip: false };
    this.walker = new AL.Walker(app, this); this.smd = {};
    this.sm = { turnX: 0, turnY: 0, roll: 0, sway: 0, bounce: 0, mouth: 0, lookX: 0, lookY: 0, browL: 0, browR: 0, lidUp: 0, lidLow: 0, lidTilt: 0, wind: 0, hL: null, hR: null };
    this.trig = {}; this.talking = false; this.auto = false; this.held = {};
    this.life = { next: 1.5, bt: -1, sx: 0, sy: 0, ns: 1 };
    this.babble = { env: 0, next: 0, vis: 'Aa', gap: 0 };
    this.mic = { on: false, level: 0, vis: 'Neutral', kind: null };
    this.cam = { on: false, v: null, swap: false, mode: 'talk', zero: null };
    this.take = null; this.playing = false; this.recording = false; this.ph = 0; this.wrote = {}; this.fade = {}; this.trigTouched = {}; this.pinTouched = {}; this.blinkManual = 0; this.backup = null;
    this.S = AL.restSignals();
  };
  var P = AL.Inputs.prototype;

  P.restHand = function (side) {
    var ch = this.app.ch, set = AL.firstRole(ch.root, side === 'L' ? 'armL' : 'armR'); if (!set || !set.pick) return null;
    var k = AL.node(ch, set.pick.rest), r = set.pick.restAt || (k && k.limb ? k.limb.c : null); return k && k.limb ? { x: r.x, y: r.y, ax: k.limb.a.x, ay: k.limb.a.y } : null;
  };
  P.blinkNow = function () { this.life.bt = 0; this.blinkManual = 0.2; };
  P.resetPose = function () { var p = this.pose; p.turnX = p.turnY = p.roll = p.sway = p.bounce = p.mouth = p.brow = p.lidUp = p.lidLow = p.lidTilt = p.wind = 0; p.mood = 'Neutral'; p.handL = p.handR = p.footL = p.footR = null; p.dial = {}; this.trig = {}; this.walker.home(); };
  /* a mood is three numbers for the lids and one for the brows */
  AL.MOODS = { Neutral: [0, 0, 0, 0], Relaxed: [0.42, 0, 0, 0], Sleepy: [0.72, 0, 0.12, -0.3], Angry: [0.3, 1, 0.18, -0.9], Sad: [0.26, -1, 0, 0.35], Cheery: [0, 0, 0.8, 0.45], Wary: [0.4, 0.35, 0.45, -0.4] };
  P.setMood = function (name) { var m = AL.MOODS[name] || AL.MOODS.Neutral, p = this.pose; p.mood = name; p.lidUp = m[0]; p.lidTilt = m[1]; p.lidLow = m[2]; p.brow = m[3]; this.held.face = true; var self = this; clearTimeout(this._mt); this._mt = setTimeout(function () { self.held.face = false; }, 700); };

  /* ── voice ── */
  P.audioGraph = function () {
    if (!this.actx) { var C = root.AudioContext || root.webkitAudioContext; this.actx = new C(); this.an = this.actx.createAnalyser(); this.an.fftSize = 1024; this.an.smoothingTimeConstant = 0.45; this.td = new Float32Array(1024); this.fd = new Uint8Array(512); }
    if (this.actx.state === 'suspended') this.actx.resume();
    return this.actx;
  };
  P.micStart = function () {
    var self = this;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) return Promise.reject(new Error('no-mic'));
    return navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } }).then(function (stream) {
      var ac = self.audioGraph(); self.micStop(); self.stream = stream; self.src = ac.createMediaStreamSource(stream); self.src.connect(self.an);
      self.mic.on = true; self.mic.kind = 'mic';
    });
  };
  P.playVoice = function (file) {
    var self = this, ac = this.audioGraph(); this.micStop();
    var el = this.audioEl = new Audio(); this.audioUrl = URL.createObjectURL(file); el.src = this.audioUrl;
    this.src = ac.createMediaElementSource(el); this.src.connect(this.an); this.an.connect(ac.destination); this.toSpeaker = true;
    el.onended = function () { self.micStop(); self.app.refreshPanel(); };
    this.mic.on = true; this.mic.kind = 'file';
    return el.play();
  };
  P.micStop = function () {
    if (this.stream) { this.stream.getTracks().forEach(function (t) { t.stop(); }); this.stream = null; }
    if (this.audioEl) { this.audioEl.pause(); this.audioEl = null; }
    if (this.audioUrl) { URL.revokeObjectURL(this.audioUrl); this.audioUrl = null; }
    if (this.src) { try { this.src.disconnect(); } catch (e) { } this.src = null; }
    if (this.toSpeaker) { try { this.an.disconnect(); } catch (e2) { } this.toSpeaker = false; }
    this.mic.on = false; this.mic.level = 0; this.mic.kind = null;
  };
  P.listen = function () {
    var an = this.an; an.getFloatTimeDomainData(this.td); an.getByteFrequencyData(this.fd);
    var s = 0, i; for (i = 0; i < this.td.length; i++) s += this.td[i] * this.td[i];
    var rms = Math.sqrt(s / this.td.length), open = Math.pow(clamp((rms - 0.012) / 0.13, 0, 1), 0.65);
    var hz = this.actx.sampleRate / 1024, b = [0, 0, 0, 0], edges = [200, 520, 1250, 3000, 7200];
    for (i = 1; i < this.fd.length; i++) { var f = i * hz; for (var k = 0; k < 4; k++) if (f >= edges[k] && f < edges[k + 1]) b[k] += this.fd[i]; }
    var tot = b[0] + b[1] + b[2] + b[3] + 1, lo = b[0] / tot, mid = b[1] / tot, him = b[2] / tot, hi = b[3] / tot, vis;
    if (open < 0.06) vis = 'Neutral'; else if (hi > 0.42) vis = 'S'; else if (lo > 0.5 && him < 0.16) vis = open < 0.4 ? 'W-Oo' : 'Oh'; else if (him > 0.3 && mid < 0.32) vis = 'Ee'; else vis = 'Aa';
    this.mic.level = open; this.mic.vis = vis;
  };

  /* ── face camera (works on the local page; never shows or stores the picture) ── */
  P.camStart = function () {
    var self = this, base = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14';
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) return Promise.reject(new Error('no-camera'));
    return navigator.mediaDevices.getUserMedia({ video: { width: 320, height: 240, facingMode: 'user' } }).then(function (stream) {
      self.camStream = stream; var v = self.cam.v = document.createElement('video'); v.srcObject = stream; v.muted = true; v.playsInline = true;
      return v.play().then(function () { return import(base + '/vision_bundle.mjs'); });
    }).then(function (mp) {
      return mp.FilesetResolver.forVisionTasks(base + '/wasm').then(function (fs) {
        return mp.FaceLandmarker.createFromOptions(fs, { baseOptions: { modelAssetPath: 'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task', delegate: 'GPU' }, outputFaceBlendshapes: true, outputFacialTransformationMatrixes: true, runningMode: 'VIDEO', numFaces: 1 });
      });
    }).then(function (lm) { if (self.camGone) { self.camGone = false; self.camStop(); throw new Error('stopped'); } self.cam.lm = lm; self.cam.on = true; self.cam.last = 0; self.cam.face = null; })
      .catch(function (e) { self.camStop(); throw e; });
  };
  P.camStop = function () { if (!this.cam.on && this.camStream) this.camGone = true; if (this.camStream) this.camStream.getTracks().forEach(function (t) { t.stop(); }); this.camStream = null; this.cam.on = false; this.cam.v = null; this.cam.lm = null; this.cam.face = null; };
  P.watch = function () {
    var c = this.cam, now = performance.now(); if (!c.lm || !c.v || c.v.readyState < 2 || now - c.last < 50) return;
    c.last = now;
    var r; try { r = c.lm.detectForVideo(c.v, now); } catch (e) { return; }
    if (AL.face) { c.face = AL.face.pilotFace(r, c, now / 1000); return; }   // FACE-1: neutral, two sides, lost and found, face pins
    if (!r || !r.faceBlendshapes || !r.faceBlendshapes.length) return;                       // no face: hold, do not fade
    var bs = {}; r.faceBlendshapes[0].categories.forEach(function (q) { bs[q.categoryName] = q.score; });
    var f = { mouth: clamp((bs.jawOpen || 0) * 1.8, 0, 1), blinkL: bs.eyeBlinkLeft > 0.5 ? 1 : 0, blinkR: bs.eyeBlinkRight > 0.5 ? 1 : 0,
      brow: clamp(((bs.browInnerUp || 0) + (bs.browOuterUpLeft || 0) + (bs.browOuterUpRight || 0)) / 1.6 - (bs.browDownLeft || 0), -1, 1),
      lookX: clamp(((bs.eyeLookOutLeft || 0) - (bs.eyeLookInLeft || 0)) * 1.6, -1, 1), lookY: clamp(((bs.eyeLookDownLeft || 0) - (bs.eyeLookUpLeft || 0)) * 1.6, -1, 1), smile: ((bs.mouthSmileLeft || 0) + (bs.mouthSmileRight || 0)) / 2,
      lidUp: clamp(Math.min(bs.eyeBlinkLeft || 0, bs.eyeBlinkRight || 0) * 1.5, 0, 0.8), lidLow: clamp(((bs.eyeSquintLeft || 0) + (bs.eyeSquintRight || 0)) * 0.9 + ((bs.cheekSquintLeft || 0) + (bs.cheekSquintRight || 0)) * 0.6, 0, 1), lidTilt: clamp(((bs.browDownLeft || 0) + (bs.browDownRight || 0)) * 1.2 - (bs.browInnerUp || 0) * 1.4, -1, 1) };
    var m = r.facialTransformationMatrixes && r.facialTransformationMatrixes[0] && r.facialTransformationMatrixes[0].data;
    if (m) { var sg = c.swap ? -1 : 1; f.turnX = clamp(Math.atan2(m[8], m[10]) / 0.55, -1, 1) * sg; f.turnY = clamp(Math.asin(clamp(-m[9], -1, 1)) / 0.45, -1, 1) * -1; f.roll = clamp(Math.atan2(m[1], m[5]) / 0.6, -1, 1) * -sg; f.lookX *= sg; }
    c.face = f;
  };

  /* ── takes ──
     A take stores signals per frame. Every signal has an OWNER (head, mouth, mood, blink, each hand, each foot, body,
     wind, each button, each pinned point). Recording over a take is punch-in, punch-out: an owner is written only
     while something live is driving it, then handed back to the old take over a quarter of a second. Whatever you
     did not touch is the same afterwards, sample for sample. One Undo takes the whole pass back. */
  var OWN = { turnX: 'head', turnY: 'head', roll: 'head', lookX: 'head', lookY: 'head', mouth: 'mouth', vis: 'mouth', browL: 'mood', browR: 'mood', lidUp: 'mood', lidLow: 'mood', lidTilt: 'mood',
    blinkL: 'blink', blinkR: 'blink', hLx: 'handL', hLy: 'handL', hLw: 'handL', hRx: 'handR', hRy: 'handR', hRw: 'handR', fLx: 'footL', fLy: 'footL', fRx: 'footR', fRy: 'footR', fLr: 'footL', fRr: 'footR', spin: 'body',
    sway: 'body', bounce: 'body', flip: 'body', rootX: 'body', rootY: 'body', rootS: 'body', walk: 'body', wind: 'wind',
    shrugL: 'shoulders', shrugR: 'shoulders', shrug: 'shoulders' };    // SHRUG-1
  var HAND = { hLx: ['L', 0], hLy: ['L', 1], hRx: ['R', 0], hRy: ['R', 1], fLx: ['FL', 0], fLy: ['FL', 1], fRx: ['FR', 0], fRy: ['FR', 1] };
  var STEP = { vis: 1, flip: 1, hLw: 1, hRw: 1 }, FADE = 0.25;      // (hLw, hRw: that hand is WALKING, planted on the stage like a foot; see al-take-walk.js)
  AL.TAKE_LANES = [['head', 'Head'], ['mouth', 'Mouth'], ['mood', 'Mood'], ['blink', 'Blink'], ['handL', 'Left hand'], ['handR', 'Right hand'], ['body', 'Body and feet'], ['other', 'Buttons, wind, pins']];
  P.ownerOf = function (name) { if (OWN[name]) return OWN[name]; var p = name.slice(0, 3); if (p === 'pt.' || name.slice(0, 5) === 'face.') return 'facepins'; if (p === 'dx:' || p === 'dy:') return 'head'; if (name.slice(0, 2) === 'p:') return name.slice(0, name.lastIndexOf(':')); return name; };
  P.channels = function () {
    var seen = {}, out = [], k; function add(n) { if (!seen[n]) { seen[n] = 1; out.push(n); } }
    for (k in OWN) add(k);
    for (k in this.pose.dial) { add('dx:' + k); add('dy:' + k); }
    (this.app.ch.triggers || []).forEach(function (t) { add('g:' + t.id); });
    for (k in this.S.pin) { add('p:' + k + ':x'); add('p:' + k + ':y'); }
    for (k in this.S) if (k.slice(0, 3) === 'pt.' || k.slice(0, 5) === 'face.') add(k);   // FACE-1 face pins and expressions
    if (this.take) for (k in this.take.ch) add(k);                  // whatever the take holds is played, even after a reload
    return out;
  };
  /** Who is being driven live, right now. */
  P.owned = function (talking, faceOn) {
    var hd = this.held, face = !!faceOn && !!this.cam.face, k;
    var o = { head: !!hd.head || face, mouth: !!talking || this.mic.on || face, mood: !!hd.face || face, blink: !!this.blinkHold || this.blinkManual > 0 || face,
      facepins: face, shoulders: !!(AL.pose && AL.pose.live), handL: !!hd.handL, handR: !!hd.handR, footL: !!hd.footL || !!hd.body, footR: !!hd.footR || !!hd.body, body: !!hd.body, wind: !!hd.wind };
    for (k in this.trigTouched) o['g:' + k] = true;                 // a button pressed in this pass is yours to the end of it
    for (k in this.pinTouched) o['p:' + k] = true;
    return o;
  };
  P.toggleTrig = function (id) { this.setTrig(id, !this.trig[id]); };
  /** Switch a button on or off. Colour sets take turns: switching one on switches the others off, so one melts into the next. */
  P.setTrig = function (id, on) {
    var self = this, ch = this.app.ch; on = !!on; if (!!this.trig[id] === on) return;
    this.trig[id] = on; if (this.recording) this.trigTouched[id] = true;
    if (on) (ch.palettes || []).forEach(function (p) { if (p.trig !== id) return; (ch.palettes || []).forEach(function (o) { if (o !== p && o.trig && self.trig[o.trig]) { self.trig[o.trig] = false; if (self.recording) self.trigTouched[o.trig] = true; } }); });
    if (this.app.mode === 'perform' && this.app.refreshPanel && this === this.app.inp) { var a = this.app; clearTimeout(a._trigT); a._trigT = setTimeout(function () { a.refreshPanel(true); }, 30); }
  };
  /**
   * STANCES ease in and out on a spring that starts gently and settles without a bump (or with a small one, if the stance
   * is marked bouncy); COLOUR SETS sweep across at a steady pace. Both follow their buttons, so a take that recorded the
   * button plays the same change back.
   */
  P.easeLevels = function (dt, S) {
    var ch = this.app.ch, pl = this.poseLvl || (this.poseLvl = {}), pv = this.poseVel || (this.poseVel = {}), tl = this.toneLvl || (this.toneLvl = {});
    (ch.poses || []).forEach(function (p) {
      var tgt = p.trig && S.trig[p.trig] ? 1 : 0, x = pl[p.id] || 0, v = pv[p.id] || 0, om = 5.4 / Math.max(0.08, p.time == null ? 0.6 : p.time), z = p.bounce ? 0.6 : 1, h = dt / 3, i;
      for (i = 0; i < 3; i++) { v += (om * om * (tgt - x) - 2 * z * om * v) * h; x += v * h; }
      if (Math.abs(tgt - x) < 0.0006 && Math.abs(v) < 0.004) { x = tgt; v = 0; }
      pl[p.id] = x; pv[p.id] = v;
    });
    (ch.palettes || []).forEach(function (p) { var tgt = p.trig && S.trig[p.trig] ? 1 : 0, x = tl[p.id] || 0, st = dt / Math.max(0.05, p.time == null ? 0.9 : p.time); tl[p.id] = x < tgt ? Math.min(tgt, x + st) : Math.max(tgt, x - st); });
    S.pose = pl; S.tone = tl;
  };
  P.record = function () {
    if (!this.take) this.take = { fps: 30, n: 0, ch: {} };
    this.backup = JSON.stringify(this.take);
    this.recording = true; this.playing = true; this.scrub = false; this.ph = 0; this.wrote = {}; this.fade = {}; this.trigTouched = {}; this.pinTouched = {}; this.fresh = this.take.n === 0;
    if (AL.voice) AL.voice.pass(this);                                   // (the voice is kept with the take: al-voice.js)
  };
  P.stop = function () { var was = this.recording; if (was && this.take) this.take.n = Math.max(this.take.n, Math.floor(this.ph * this.take.fps)); this.recording = false; this.playing = false; if (was && AL.voice) AL.voice.endPass(this); if (was && this.app.touch) this.app.touch(); };
  /** Take the last recording pass back, whole. */
  P.undoPass = function () { if (!this.backup) return false; if (AL.voice) AL.voice.cancel(this); this.recording = false; this.playing = false; var t = JSON.parse(this.backup); this.backup = null; this.take = t.n ? t : null; this.ph = 0; if (this.app.touch) this.app.touch(); return true; };
  P.play = function () { if (!this.take || !this.take.n) return; this.playing = true; this.recording = false; this.scrub = false; if (this.ph >= this.take.n / this.take.fps - 0.05) this.ph = 0; };
  P.clearTake = function () { this.recording = false; this.playing = false; this.take = null; this.backup = null; this.ph = 0; if (this.app.touch) this.app.touch(); };
  P.duration = function () { return this.take ? this.take.n / this.take.fps : 0; };

  function flat(S, trigs) {
    var o = { turnX: S.turnX, turnY: S.turnY, roll: S.roll, lookX: S.lookX, lookY: S.lookY, mouth: S.mouth == null ? 0 : S.mouth, vis: AL.SHAPES.indexOf(S.viseme), browL: S.browL, browR: S.browR, blinkL: S.blinkL, blinkR: S.blinkR,
      lidUp: S.lidUp, lidLow: S.lidLow, lidTilt: S.lidTilt, wind: S.wind, sway: S.sway, bounce: S.bounce, flip: S.flip ? 1 : 0, rootX: S.root ? S.root.x : 0, rootY: S.root ? S.root.y : 0, rootS: S.rootScale || 1, walk: S.walk || 0, fLr: S.foot ? S.foot.FL : 0, fRr: S.foot ? S.foot.FR : 0, spin: S.spin || 0 }, k;
    for (k in HAND) { var hh = S.hand[HAND[k][0]]; o[k] = hh ? (HAND[k][1] ? hh.y : hh.x) : null; }      // null = at rest, and stays null through a saved file
    o.hLw = S.hand.L && S.hand.L.w ? 1 : 0; o.hRw = S.hand.R && S.hand.R.w ? 1 : 0;                       // a walking hand's place is on the stage, not on the body: the take has to say which it was
    trigs.forEach(function (t) { o['g:' + t.id] = S.trig[t.id] ? 1 : 0; });
    for (k in (S.dial || {})) { o['dx:' + k] = S.dial[k][0]; o['dy:' + k] = S.dial[k][1]; }
    for (k in (S.pin || {})) { o['p:' + k + ':x'] = S.pin[k].x; o['p:' + k + ':y'] = S.pin[k].y; }
    for (var pk in S) if (pk.slice(0, 3) === 'pt.' || pk.slice(0, 5) === 'face.') o[pk] = S[pk];   // FACE-1 face pins and expressions
    o.shrugL = S.shrugL || 0; o.shrugR = S.shrugR || 0; o.shrug = S.shrug || 0;   // SHRUG-1
    return o;
  }
  function unflat(S, name, v) {
    if (v === undefined) return;
    if (HAND[name]) { var hk = HAND[name]; if (!hk[1]) S.hand[hk[0]] = v == null || v !== v ? null : { x: v, y: S.hand[hk[0]] ? S.hand[hk[0]].y : 0 }; else if (S.hand[hk[0]] && v != null) S.hand[hk[0]].y = v; return; }
    if (v == null) return;
    if (name === 'hLw' || name === 'hRw') { var hw = S.hand[name === 'hLw' ? 'L' : 'R']; if (hw) hw.w = v > 0.5; return; }
    if (name === 'vis') S.viseme = AL.SHAPES[v] || 'Neutral';
    else if (name === 'rootX') S.root.x = v; else if (name === 'rootY') S.root.y = v; else if (name === 'rootS') S.rootScale = v;
    else if (name === 'flip') S.flip = v > 0.5;
    else if (name === 'fLr') (S.foot = S.foot || {}).FL = v; else if (name === 'fRr') (S.foot = S.foot || {}).FR = v;
    else if (name.slice(0, 2) === 'g:') S.trig[name.slice(2)] = v > 0.5;
    else if (name.slice(0, 3) === 'dx:') (S.dial[name.slice(3)] = S.dial[name.slice(3)] || [0, 0])[0] = v;
    else if (name.slice(0, 3) === 'dy:') (S.dial[name.slice(3)] = S.dial[name.slice(3)] || [0, 0])[1] = v;
    else if (name.slice(0, 2) === 'p:') { var pid = name.slice(2, name.lastIndexOf(':')), ax = name.slice(-1); (S.pin[pid] = S.pin[pid] || { x: 0, y: 0 })[ax] = v; }
    else S[name] = v;
  }

  /** One frame. Returns the signals to draw. */
  P.step = function (dt) {
    var app = this.app, ch = app.ch, set = ch.settings || {}, p = this.pose, sm = this.sm, S = this.S, L = this.life, held = this.held;
    this.t += dt; var t = (this.take && (this.playing || this.recording || this.scrub)) ? this.ph : this.t;
    if (this.blinkManual > 0) this.blinkManual -= dt;
    var tg = { turnX: p.turnX, turnY: p.turnY, roll: p.roll, sway: p.sway, bounce: p.bounce, hL: p.handL, hR: p.handR }, mouth = p.mouth, vis = mouth > 0.08 ? 'Aa' : 'Neutral', brow = p.brow, lidUp = p.lidUp, lidLow = p.lidLow, lidTilt = p.lidTilt, talking = this.talking, look = null, blinkCam = null;

    if (this.auto) {
      if (!held.head) { tg.turnX = 0.9 * Math.sin(t * 0.55); tg.turnY = 0.55 * Math.sin(t * 0.37 + 1); tg.roll = 0.3 * Math.sin(t * 0.8); }
      if (!held.body) tg.sway = 0.5 * Math.sin(t * 0.9);
      var self = this;
      ['L', 'R'].forEach(function (sd, i) {
        if (held['hand' + sd]) return; var r = self.restHand(sd); if (!r) return;
        var ph = (t + i * 3.1) % 6.2, len = Math.sqrt((r.x - r.ax) * (r.x - r.ax) + (r.y - r.ay) * (r.y - r.ay)), side = r.ax >= (set.axisX == null ? ch.w / 2 : set.axisX) ? 1 : -1;
        if (ph < 2.6) { var a = (2.35 + 0.35 * Math.sin(ph * 9)) * Math.min(1, ph * 3) * Math.min(1, (2.6 - ph) * 3); tg['h' + sd] = { x: r.ax + side * Math.sin(a) * len, y: r.ay + Math.cos(a) * len }; }
        else if (ph < 4.4) { var a2 = 1.2 + 0.5 * Math.sin(ph * 2.2); tg['h' + sd] = { x: r.ax + side * Math.sin(a2) * len * 0.9, y: r.ay + Math.cos(a2) * len * 0.9 }; }
        else tg['h' + sd] = { x: r.x, y: r.y };
      });
      if (!held.face) talking = (t % 7.5) < 4.2;
    }
    /* Face camera. "While talking": it takes over only while Talk is held. Either way the first face it sees is
       taken as "straight ahead", and the head moves from wherever the hand had left it: no jump in, no jump out. */
    var faceOn = this.cam.on && (this.cam.mode === 'always' || this.talking);
    if (!faceOn) { this.cam.zero = null; this.cam.face = null; }
    if (faceOn) { this.watch(); var f = this.cam.face; if (f) {
      if (f.turnX != null && !this.cam.zero) this.cam.zero = { fx: f.turnX, fy: f.turnY, fr: f.roll, px: p.turnX, py: p.turnY, pr: p.roll };
      var z = this.cam.zero;
      if (!held.head && z) { tg.turnX = z.px + (f.turnX - z.fx); tg.turnY = z.py + (f.turnY - z.fy); tg.roll = z.pr + (f.roll - z.fr); }
      if (!held.face || this.talking) { mouth = f.mouth; vis = f.mouth > 0.12 ? (f.smile > 0.5 ? 'Ee' : 'Aa') : 'Neutral'; brow = f.brow; lidUp = f.lidUp; lidLow = f.lidLow; lidTilt = f.lidTilt; blinkCam = f; } look = f; } }
    if (AL.facepins) AL.facepins.feed(this, faceOn ? this.cam.face : null, dt);   // FACE-1 face pins
    if (AL.pose) AL.pose.feed(this, faceOn, dt);                                    // SHRUG-1 shoulders

    /* a voice, or the Talk button's babble */
    if (this.mic.on) { this.listen(); mouth = Math.max(mouth, this.mic.level); vis = this.mic.vis; }
    else if (talking && !(faceOn && this.cam.face)) {
      var b = this.babble; b.next -= dt;
      if (b.next <= 0) { if (b.gap > 0 && Math.random() < 0.16) { b.env = 0; b.vis = 'M'; b.next = 0.12 + Math.random() * 0.22; b.gap = 0; } else { b.env = 0.45 + Math.random() * 0.55; b.vis = ['Aa', 'Ee', 'Oh', 'Aa', 'W-Oo', 'Aa', 'F', 'Ee', 'L'][(Math.random() * 9) | 0]; b.next = 0.09 + Math.random() * 0.13; b.gap = 1; } }
      mouth = Math.max(mouth, b.env); vis = b.env > 0.08 ? b.vis : 'M';
    }

    /* idle life */
    var idle = set.idle !== false && !faceOn ? 1 : 0;
    tg.turnX += idle * 0.035 * Math.sin(t * 0.43); tg.turnY += idle * 0.03 * Math.sin(t * 0.31 + 2); tg.sway += idle * 0.07 * Math.sin(t * 0.52);
    sm.turnX = ease(sm.turnX, clamp(tg.turnX, -1, 1), 11, dt); sm.turnY = ease(sm.turnY, clamp(tg.turnY, -1, 1), 11, dt);
    sm.roll = ease(sm.roll, tg.roll, 10, dt); sm.sway = ease(sm.sway, tg.sway, 8, dt); sm.bounce = ease(sm.bounce, tg.bounce, 12, dt);
    sm.mouth = ease(sm.mouth, mouth, mouth > sm.mouth ? 38 : 22, dt);
    ['L', 'R'].forEach(function (sd) {
      var k = 'h' + sd, want = tg[k];
      if (!want) { sm[k] = null; return; }
      if (!sm[k]) sm[k] = { x: want.x, y: want.y };
      sm[k].x = ease(sm[k].x, want.x, 20, dt); sm[k].y = ease(sm[k].y, want.y, 20, dt);
    });
    /* eyes: they go first, the head follows; plus small darts */
    L.ns -= dt; if (L.ns <= 0) { L.ns = 1.6 + Math.random() * 2.6; L.sx = (Math.random() - 0.5) * 0.5; L.sy = (Math.random() - 0.5) * 0.3; }
    var lx = look ? look.lookX : (set.eyesLead !== false ? clamp((tg.turnX - sm.turnX) * 2.6, -1, 1) : 0) + idle * L.sx, ly = look ? look.lookY : (set.eyesLead !== false ? clamp((tg.turnY - sm.turnY) * 2.6, -1, 1) : 0) + idle * L.sy;
    sm.lookX = ease(sm.lookX, clamp(lx, -1, 1), 26, dt); sm.lookY = ease(sm.lookY, clamp(ly, -1, 1), 26, dt);
    var lift = mouth > 0.75 ? 0.25 : 0, bL = blinkCam && blinkCam.browL != null ? blinkCam.browL : brow, bR = blinkCam && blinkCam.browR != null ? blinkCam.browR : brow;
    sm.browL = ease(sm.browL, bL + lift, 14, dt); sm.browR = ease(sm.browR, bR + lift, 14, dt);   // FACE-1: one brow can rise alone
    sm.lidUp = ease(sm.lidUp, lidUp, 12, dt); sm.lidLow = ease(sm.lidLow, lidLow, 12, dt); sm.lidTilt = ease(sm.lidTilt, lidTilt, 10, dt); sm.wind = ease(sm.wind, p.wind, 3, dt);
    /* blinks: uneven on purpose */
    var bl = 0;
    if (L.bt >= 0) { L.bt += dt; bl = L.bt < 0.11 ? 1 : 0; if (L.bt >= 0.11) L.bt = -1; }
    else if (set.blinkAuto !== false && !blinkCam) { L.next -= dt; if (L.next <= 0) { L.bt = 0; var ev = set.blinkEvery || 3.6; L.next = Math.random() < 0.18 ? 0.24 : ev * (0.45 + Math.random() * 1.1); } }

    if (this.blinkHold) bl = 1;
    S.t = t; S.turnX = sm.turnX; S.turnY = sm.turnY; S.roll = sm.roll; S.sway = sm.sway; S.bounce = sm.bounce;
    S.mouth = sm.mouth < 0.015 ? 0 : sm.mouth; S.viseme = vis; S.lookX = sm.lookX; S.lookY = sm.lookY; S.browL = sm.browL; S.browR = sm.browR;
    S.blinkL = blinkCam ? blinkCam.blinkL : bl; S.blinkR = blinkCam ? blinkCam.blinkR : bl;
    S.lidUp = sm.lidUp; S.lidLow = sm.lidLow; S.lidTilt = sm.lidTilt; S.wind = sm.wind;
    S.breath = set.breathe !== false ? Math.sin(t * 2 * Math.PI * (set.breathRate || 0.28)) : 0;
    S.hand.L = sm.hL ? { x: sm.hL.x, y: sm.hL.y } : null; S.hand.R = sm.hR ? { x: sm.hR.x, y: sm.hR.y } : null;
    var dn; S.dial = S.dial || {};
    for (dn in p.dial) { var dq = this.smd[dn] || (this.smd[dn] = [0, 0]); dq[0] = ease(dq[0], p.dial[dn][0], 11, dt); dq[1] = ease(dq[1], p.dial[dn][1], 11, dt); S.dial[dn] = [dq[0], dq[1]]; }
    sm.spin = ease(sm.spin || 0, p.spin || 0, 7, dt); S.spin = sm.spin;                 // the whole character turned round (round bodies only)
    this.walker.step(dt, S);
    S.flip = !!p.flip; S.trig = {}; for (var id in this.trig) if (this.trig[id]) S.trig[id] = true;

    /* a take: play it back under whatever is being driven live; recording writes only what is being driven */
    var tk = this.take;
    if (tk && (this.playing || this.recording || this.scrub)) {
      var i = Math.floor(this.ph * tk.fps), live = flat(S, ch.triggers || []), names = this.channels(), own = this.owned(talking, faceOn), fade = this.fade || (this.fade = {}), n, g, q, arr, had, val;
      for (q = 0; q < names.length; q++) {
        n = names[q]; g = this.ownerOf(n); arr = tk.ch[n]; had = arr && arr.length ? arr[Math.min(i, arr.length - 1)] : undefined;
        var mine = !!own[g], fading = !mine && fade[g] > 0;
        if (this.recording && (this.fresh || mine || fading)) {
          val = live[n];
          if (fading) { if (had === undefined) continue; val = (STEP[n] || n.charAt(1) === ':' || had == null || val == null || typeof val !== 'number') ? had : had + (val - had) * (fade[g] / FADE); }
          arr = arr || (tk.ch[n] = []); while (arr.length < i) arr.push(arr.length ? arr[arr.length - 1] : val); arr[i] = val; this.wrote[g] = true;
          if (fading) unflat(S, n, val);
        } else if (!mine && had !== undefined) unflat(S, n, had);
      }
      for (g in fade) if (!own[g]) fade[g] = Math.max(0, fade[g] - dt);
      for (g in own) if (own[g]) fade[g] = FADE;
      if (this.playing || this.recording) this.ph += AL.voice ? AL.voice.clock(this, dt) : dt;      // (while a voice is kept, the take keeps the sound's time)
      if (this.recording) { tk.n = Math.max(tk.n, i + 1); if (this.ph > 120) this.stop(); }
      else if (this.playing && this.ph >= Math.max(tk.n / tk.fps, this.sceneLen || 0)) this.ph = 0;      // (in a scene the take runs as long as the longest one in it: al-scene-take.js)
    }
    this.easeLevels(dt, S);
    return S;
  };
})(typeof self !== 'undefined' ? self : this);
