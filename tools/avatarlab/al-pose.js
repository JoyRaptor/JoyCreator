/* AvatarLab: SHOULDERS (job SHRUG-1; the owner, 11-22: "Is there a way for us to get the shoulders animated for shoulder
 * shrugs?"). The camera's body tracker (MediaPipe Pose: 33 points, both ears and both shoulders) runs beside the face
 * tracker on the same video, about ten times a second on a thread of its own, while the face camera is on and the
 * Shoulders tick box is.
 *
 * What it measures (ShoulderStage.java, line for line): how far each shoulder sits below the middle of the ears, in
 * shoulder widths, against your own rest (the first still half-second with your shoulders in view; Re-centre takes it
 * again). A shrug brings a shoulder up toward the ears. The ears' middle barely moves when the head tilts or nods; the
 * shoulders' width cancels coming closer; leaning moves both. Out come shrugL and shrugR (the character's sides, Mirror as
 * the face: your right shoulder lifts the character's left) and shrug (both). Lost shoulders hold a moment, then ease.
 *
 * On the character it is three Links the pilot already draws (al-solve.js local()): each arm moves up with its side's
 * shrug and the head sinks a little with both. They are added the first time your shoulders are seen, sized from the
 * arm and the head, and tuned in Set up (Body: Shrug). Any part's Links can use a shrug too.
 *
 * Hooks: al-inputs.js calls AL.pose.feed every step (and owns shrugL/R/shrug as the "shoulders" lane of a take);
 * al-solve.js swaps the sides in a mirror; al-perform.js shows the tick box; al-cards.js the Set up section and Links.
 */
(function (root) {
  var AL = root.AL = root.AL || {};
  var P = AL.pose = {};
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }

  /* ── the maths (ShoulderStage.java) ── */
  P.SHRUG_L = 'shrugL'; P.SHRUG_R = 'shrugR'; P.SHRUG = 'shrug'; P.BODY = 'body'; P.LANE = 'shoulders';
  P.EAR_L = 7; P.EAR_R = 8; P.SHOULDER_L = 11; P.SHOULDER_R = 12; P.COUNT = 33;
  var SEEN = 0.5, SPAN = 0.13, DEAD = 0.04, HOLD = 0.35, EASE_OUT = 0.8, EASE_IN = 0.3, RATE = 30, NEUTRAL_FRAMES = 5, STILL = 0.03;
  function vis(p, i) { return p[i * 3 + 2]; }
  function px(p, i) { return p[i * 3]; }
  function py(p, i, tall) { return p[i * 3 + 1] * tall; }
  /** [the picture's left shoulder (the person's right), the picture's right] below the ears' middle, in shoulder widths. */
  P.drops = function (xyv, tall) {
    if (!xyv || xyv.length < P.COUNT * 3) return null;
    if (vis(xyv, P.SHOULDER_L) < SEEN || vis(xyv, P.SHOULDER_R) < SEEN) return null;
    var el = vis(xyv, P.EAR_L) >= SEEN, er = vis(xyv, P.EAR_R) >= SEEN;
    if (!el && !er) return null;
    var earY = el && er ? (py(xyv, P.EAR_L, tall) + py(xyv, P.EAR_R, tall)) / 2 : py(xyv, el ? P.EAR_L : P.EAR_R, tall);
    var dx = px(xyv, P.SHOULDER_L) - px(xyv, P.SHOULDER_R), dy = py(xyv, P.SHOULDER_L, tall) - py(xyv, P.SHOULDER_R, tall);
    var w = Math.sqrt(dx * dx + dy * dy);
    if (w < 1e-4) return null;
    return [(py(xyv, P.SHOULDER_R, tall) - earY) / w, (py(xyv, P.SHOULDER_L, tall) - earY) / w];
  };
  P.lift = function (nearer, sens) { return clamp((nearer - DEAD) * (sens == null ? 1 : sens) / (SPAN - DEAD), 0, 1); };
  function smooth(k) { return k * k * (3 - 2 * k); }
  function out(l, r, body) { var o = {}; o[P.SHRUG_L] = l; o[P.SHRUG_R] = r; o[P.SHRUG] = (l + r) / 2; o[P.BODY] = body; return o; }

  P.Stage = function () {
    this.restL = NaN; this.restR = NaN; this.wantNeutral = true; this.capN = 0;
    this.smL = 0; this.smR = 0; this.lastL = 0; this.lastR = 0; this.lastT = NaN; this.lostSince = NaN; this.presence = 0; this.tracking = false;
    this.sensitivity = 1;
  };
  var S = P.Stage.prototype;
  S.requestNeutral = function () { this.wantNeutral = true; this.capN = 0; };
  S.hasNeutral = function () { return this.restL === this.restL; };
  /** How little movement makes a full shrug (2 = half the movement); the dead zone stays. */
  S.setSensitivity = function (s) { this.sensitivity = clamp(s, 0.25, 4); };
  /** One reading: drops from P.drops (null when not seen); mirror = the Mirror setting off. */
  S.process = function (drops, t, mirror) {
    var dt = this.lastT !== this.lastT ? 0 : clamp(t - this.lastT, 0, 0.5), trackL, trackR;
    this.lastT = t;
    if (drops) {
      if (this.wantNeutral) this.capture(drops);
      if (!this.hasNeutral()) return out(0, 0, 0);
      if (!this.tracking) { this.smL = drops[0]; this.smR = drops[1]; this.tracking = true; this.lostSince = NaN; }
      var k = 1 - Math.exp(-RATE * dt);
      this.smL += (drops[0] - this.smL) * k;
      this.smR += (drops[1] - this.smR) * k;
      var pl = P.lift(this.restL - this.smL, this.sensitivity), pr = P.lift(this.restR - this.smR, this.sensitivity);
      this.lastL = mirror ? pr : pl;
      this.lastR = mirror ? pl : pr;
      this.presence = Math.min(1, this.presence + dt / EASE_IN);
      trackL = this.lastL; trackR = this.lastR;
    } else {
      if (this.tracking) { this.tracking = false; this.lostSince = t; }
      this.capN = 0;
      if (this.lostSince === this.lostSince && t - this.lostSince >= HOLD) this.presence = Math.max(0, this.presence - dt / EASE_OUT);
      trackL = this.lastL; trackR = this.lastR;
    }
    var p = smooth(this.presence);
    return out(trackL * p, trackR * p, p);
  };
  S.capture = function (d) {
    if (this.capN > 0 && (Math.abs(d[0] - this.cap0L) > STILL || Math.abs(d[1] - this.cap0R) > STILL)) this.capN = 0;
    if (this.capN === 0) { this.cap0L = d[0]; this.cap0R = d[1]; this.capSumL = 0; this.capSumR = 0; }
    this.capSumL += d[0]; this.capSumR += d[1]; this.capN++;
    if (this.capN >= NEUTRAL_FRAMES) { this.restL = this.capSumL / this.capN; this.restR = this.capSumR / this.capN; this.wantNeutral = false; this.capN = 0; }
  };

  /* ── the pilot's body tracker ── */
  var BASE = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14';
  var MODEL = 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task';
  var EVERY = 0.1;                                       // seconds between readings: shoulders move slowly
  P.DELEGATE = 'CPU';
  P.KEY = 'al.pose.on'; P.SENS_KEY = 'al.pose.sens';
  /** Shoulder sensitivity, kept on this device (a body, not a character): 1 as built, 2 = half the movement for a full shrug. */
  P.sens = function () { var v = NaN; try { v = parseFloat(root.localStorage.getItem(P.SENS_KEY)); } catch (e) { /* none kept */ } return v === v ? clamp(v, 0.25, 4) : 1; };
  P.setSens = function (v) { try { root.localStorage.setItem(P.SENS_KEY, String(v)); } catch (e) { /* kept for this visit */ } if (P.stage) P.stage.setSensitivity(v); };
  P.isOn = function () { try { return root.localStorage.getItem(P.KEY) !== '0'; } catch (e) { return true; } };
  P.setOn = function (on) { try { root.localStorage.setItem(P.KEY, on ? '1' : '0'); } catch (e) { /* kept for this visit */ } };
  P.stats = { n: 0, ms: 0, seen: 0 };                   // readings, average time of one, readings with shoulders in view

  /*
   * The body model runs on a thread of its own (a worker): one reading takes about 60 ms on the Note 9, and on the page's
   * own thread that stalled the picture (57 frames a second fell to 24, the face from 17 readings to 8; measured 11-22).
   * The page sends a still of the video when the worker is free; the worker answers with the 33 points. It uses the
   * processor, not the graphics chip, so it never queues behind the face model or the drawing.
   */
  function poseWorker() {
    var lm = null;
    self.onmessage = function (e) {
      var m = e.data;
      if (m.init) {
        // the CommonJS build, run from its text (the CDN does not label it as a script, so importScripts refuses it)
        fetch(m.base + '/vision_bundle.cjs').then(function (r) { if (!r.ok) throw new Error('vision bundle ' + r.status); return r.text(); }).then(function (code) {
          var mod = { exports: {} };
          new Function('module', 'exports', code)(mod, mod.exports);
          var mp = mod.exports;
          return mp.FilesetResolver.forVisionTasks(m.base + '/wasm').then(function (fs) {
            return mp.PoseLandmarker.createFromOptions(fs, { baseOptions: { modelAssetPath: m.model, delegate: m.delegate || 'CPU' }, runningMode: 'VIDEO', numPoses: 1 });
          });
        }).then(function (x) { lm = x; self.postMessage({ ready: true }); }, function (err) { self.postMessage({ failed: String(err && err.message || err) }); });
        return;
      }
      if (!m.frame) return;
      var out = null, t0 = performance.now();
      try {
        var r = lm.detectForVideo(m.frame, m.ts), b = r && r.landmarks && r.landmarks[0];
        if (b && b.length >= 33) { out = new Float32Array(99); for (var i = 0; i < 33; i++) { out[i * 3] = b[i].x; out[i * 3 + 1] = b[i].y; out[i * 3 + 2] = b[i].visibility == null ? 1 : b[i].visibility; } }
      } catch (err) { out = null; }
      try { m.frame.close(); } catch (err2) { /* already gone */ }
      self.postMessage({ xyv: out, t: m.t, tall: m.tall, ms: performance.now() - t0 }, out ? [out.buffer] : []);
    };
  }
  function start() {
    if (P.worker || P.failed) return;
    try {
      var url = URL.createObjectURL(new Blob(['(' + poseWorker.toString() + ')()'], { type: 'text/javascript' }));
      P.worker = new Worker(url);
    } catch (e) { P.failed = String(e && e.message || e); return; }
    P.busy = true;                                          // until the model is ready
    P.worker.onmessage = function (e) {
      var m = e.data;
      if (m.ready) { P.busy = false; P.ready = true; return; }
      if (m.failed) { P.failed = m.failed; P.busy = false; return; }
      P.busy = false;
      P.stats.n++; P.stats.ms += (m.ms - P.stats.ms) / Math.min(P.stats.n, 20);
      P.reading(m.xyv, m.tall, m.t);
    };
    P.worker.postMessage({ init: true, base: BASE, model: MODEL, delegate: P.DELEGATE });
  }
  /** One reading of the body (from the worker, or a test's stand-in tracker). */
  P.reading = function (xyv, tall, t) {
    var app = P.app, d = P.drops(xyv, tall), stg = P.stage || (P.stage = new P.Stage());
    if (d) P.stats.seen++;
    stg.setSensitivity(P.sens());
    P.out = stg.process(d, t, !!(P.cam && P.cam.facing));
    if (d && !P.rigged && app && app.ch) { P.rigged = true; P.rig(app, true); }
    if (!d) { P.unseen = (P.unseen || 0) + EVERY; if (P.unseen > 2.5 && !P.hinted && app && app.toast) { P.hinted = true; app.toast('Shoulders: keep them in the picture to shrug (hold the phone a little further away).'); } }
    else P.unseen = 0;
  };
  /** The tracker's 33 points to x, y, visibility per point (0..1 of the picture), or null with no body. */
  P.xyv = function (r) {
    var b = r && r.landmarks && r.landmarks[0];
    if (!b || b.length < P.COUNT) return null;
    var o = new Float32Array(P.COUNT * 3);
    for (var i = 0; i < P.COUNT; i++) { o[i * 3] = b[i].x; o[i * 3 + 1] = b[i].y; o[i * 3 + 2] = b[i].visibility == null ? 1 : b[i].visibility; }
    return o;
  };

  /** Every step (al-inputs.js): send the video to the body model when it is due and free, put the shrugs on the signals. */
  P.feed = function (inp, faceOn, dt) {
    var cam = inp.cam, Sg = inp.S, o = null;
    P.app = inp.app; P.cam = cam;
    var on = !!faceOn && cam.on && P.isOn();
    if (on && !P.lm) start();
    if (on && cam.v && cam.v.readyState >= 2) {
      var now = inp.t || 0;                              // the pilot's own clock: a take and a test run see what the camera saw
      var due = P.last == null || now < P.last || now - P.last >= EVERY - 1e-6;
      var tall = cam.v.videoWidth ? cam.v.videoHeight / cam.v.videoWidth : 0.75;
      if (due && P.lm) {                                 // a stand-in tracker (the break test): read here, at once
        P.last = now;
        var r = null; try { r = P.lm.detectForVideo(cam.v, P.ts = Math.max((P.ts || 0) + 1, performance.now())); } catch (e) { r = null; }
        P.stats.n++;
        P.reading(P.xyv(r), tall, now);
      } else if (due && P.worker && !P.busy && typeof createImageBitmap === 'function') {
        P.last = now; P.busy = true;
        var ts = P.ts = Math.max((P.ts || 0) + 1, performance.now());
        createImageBitmap(cam.v).then(function (bm) { P.worker.postMessage({ frame: bm, ts: ts, t: now, tall: tall }, [bm]); }, function () { P.busy = false; });
      }
      o = P.out;
    } else if (P.out && P.out.body > 0) {                // switched off or the camera went: ease the last shrug away
      var k = Math.exp(-(dt || 0) / 0.3);
      P.out = out(P.out[P.SHRUG_L] * k, P.out[P.SHRUG_R] * k, P.out[P.BODY] * k < 0.01 ? 0 : P.out[P.BODY] * k);
      o = P.out;
    }
    // the readings come four to six times a second: glide to each one every frame (no steps; the owner, 11-22: "a little bit jerky")
    [P.SHRUG_L, P.SHRUG_R, P.SHRUG].forEach(function (k) { Sg[k] = P.glide(k, o ? o[k] : 0, dt); });
    P.live = !!(on && P.stage && P.stage.tracking);
  };
  /** A critically damped follow (no overshoot, no corner): where the shoulders are shown, every frame. */
  var GLIDE = 14;                                        // per second: about a quarter of a second to settle
  P.glide = function (k, target, dt) {
    var f = P.follow || (P.follow = {}), e = f[k] || (f[k] = { x: target, v: 0 }), left = Math.min(dt || 0, 0.25);
    while (left > 1e-6) { var h = Math.min(left, 1 / 60); e.v += (GLIDE * GLIDE * (target - e.x) - 2 * GLIDE * e.v) * h; e.x += e.v * h; left -= h; }
    return clamp(e.x, 0, 1);
  };
  /** Re-centre: the next still half-second is the shoulders' rest too. */
  P.neutral = function () { if (P.stage) P.stage.requestNeutral(); };

  /* ── on the character: three Links ── */
  function shrugLinks(n) { return (n && n.links || []).filter(function (l) { return l.shrug; }); }
  function parts(ch) {
    return { armL: AL.firstRole(ch.root, 'armL'), armR: AL.firstRole(ch.root, 'armR'), head: AL.firstRole(ch.root, 'head') };
  }
  function armLength(n) {
    var k = n && (n.limb ? n : (n.kids || []).filter(function (q) { return q.limb; })[0]);
    if (!k || !k.limb) { var b = n ? AL.boundsOf(n) : null; return b ? Math.max(b.x1 - b.x0, b.y1 - b.y0) : 60; }
    return Math.hypot(k.limb.c.x - k.limb.a.x, k.limb.c.y - k.limb.a.y);
  }
  /** Is the character set up to shrug? */
  P.rigged = false;
  P.has = function (ch) { var p = parts(ch); return !!(shrugLinks(p.armL).length || shrugLinks(p.armR).length || shrugLinks(p.head).length); };
  /**
   * Give the character its shrug (once, unless asked again): each arm up with its side, the head down a little with both.
   * Sized from the drawing: the arm lifts six tenths of its length, the head sinks half of that (the owner, 11-22: "still too small").
   */
  P.rig = function (app, quiet) {
    var ch = app.ch; if (!ch || ch.noShrug) return false;
    if (P.has(ch)) {
      // shrugs set up by an earlier round (11-22: "a bit too small", then "still too small") are set up again at today's size, once
      var old = [parts(ch).armL, parts(ch).armR, parts(ch).head].some(function (n) { return shrugLinks(n).some(function (l) { return l.shrug < 3; }); });
      if (!old) return false;
      [parts(ch).armL, parts(ch).armR, parts(ch).head].forEach(function (n) { if (n && n.links) n.links = n.links.filter(function (l) { return !l.shrug; }); });
    }
    var p = parts(ch), added = 0;
    function add(n, sig, amt) { if (!n) return; n.links = n.links || []; n.links.push({ sig: sig, prop: 'y', amt: Math.round(amt * 2) / 2, shrug: 3 }); added++; }
    var lift = 0;
    ['armL', 'armR'].forEach(function (z) { if (p[z]) { var l = armLength(p[z]) * 0.6; lift = Math.max(lift, l); add(p[z], z === 'armL' ? P.SHRUG_L : P.SHRUG_R, -l); } });
    if (p.head) { var hb = AL.boundsOf(p.head); add(p.head, P.SHRUG, lift ? lift * 0.5 : (hb ? (hb.y1 - hb.y0) * 0.05 : 5)); }   // sinks half what the arms lift
    if (!added) return false;
    if (app.touch) app.touch();
    if (quiet && app.toast) app.toast('Shrugs set up for ' + (ch.name || 'this character') + '. Set up, Body, Shrug changes how far.');
    return true;
  };
  /** Set up: the body card's Shrug section. */
  P.section = function (app, pb) {
    var ui = app.ui, ch = app.ch, p = parts(ch), arms = shrugLinks(p.armL).concat(shrugLinks(p.armR)), hd = shrugLinks(p.head), on = arms.length + hd.length > 0;
    pb.appendChild(ui.lab('Shrug', 'motion'));
    pb.appendChild(ui.toggle('Shrugs with your shoulders', on, function (v) {
      app.edit(function () {
        if (v) { delete ch.noShrug; P.rig(app); }
        else { ch.noShrug = true; [p.armL, p.armR, p.head].forEach(function (n) { if (n && n.links) n.links = n.links.filter(function (l) { return !l.shrug; }); }); }
      }, false, v ? 'Shrugs' : 'No shrugs');
      app.refreshPanel(true);
    }, 'With the face camera on in Perform', 'With the face camera on in Perform and your shoulders in the picture: shrug and the arms lift, the head sinks a little'));
    if (!on) return;
    if (arms.length) pb.appendChild(ui.slider('Arms lift', { min: 0, max: 60, step: 0.5, value: -arms[0].amt, set: function (v) { arms.forEach(function (l) { l.amt = -v; }); }, fmt: function (v) { return v + ' px'; } }));
    if (hd.length) pb.appendChild(ui.slider('Head sinks', { min: 0, max: 30, step: 0.5, value: hd[0].amt, set: function (v) { hd.forEach(function (l) { l.amt = v; }); }, fmt: function (v) { return v + ' px'; } }));
    pb.appendChild(P.sensSlider(app));
    var bs = app._bs || app.buildSignals(), tv = bs[P.SHRUG] || 0;
    pb.appendChild(ui.slider('Try', { min: 0, max: 1, step: 0.05, value: tv, undo: false, set: function (v) { var b = app._bs || app.buildSignals(); b[P.SHRUG_L] = b[P.SHRUG_R] = b[P.SHRUG] = v; }, fmt: function (v) { return v > 0.05 ? 'shrug' : 'rest'; } }));
  };
  /** The shoulder sensitivity slider (Perform, under the controls; Set up, Body, Shrug). A setting of this device, not the character. */
  P.sensSlider = function (app) {
    return app.ui.slider('Shoulder sensitivity', { min: 0.5, max: 3, step: 0.1, value: P.sens(), undo: false,
      set: function (v) { P.setSens(v); }, fmt: function (v) { return (Math.round(v * 10) / 10) + '×'; },
      title: 'Higher: a small shrug lifts the character all the way. Lower: it takes a big one. Kept on this device' });
  };
  /** Perform: the tick box, beside Mirror. */
  P.tick = function (app) {
    return app.ui.toggle('Shoulders', P.isOn(), function (v) { P.setOn(v); P.hinted = false; app.refreshPanel(true); }, null,
      'On: shrug and the character shrugs (your shoulders need to be in the picture). Off: the face only, a little lighter on the phone');
  };
})(typeof self !== 'undefined' ? self : this);
