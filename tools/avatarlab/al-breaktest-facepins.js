/* AvatarLab: BREAK TEST, face pins (job FACE-1). Not part of the page: load it by hand after al-breaktest.js and
 * al-breaktest-fit.js (the kit), then in the console:
 *
 *   await AL.breakTest.facepins.run()      the checks, on the character that is open
 *   await AL.breakTest.facepins.proof()    plants each fault; every one must be caught
 *
 * No camera: a made-up face of 478 points is fed to the face stage exactly as the camera's would be, so every check
 * knows what the face did. It drives time by hand (K.sim): reload the page afterwards.
 *
 * plant = which fault to put in:
 *   nofeed     the face points never reach the signals          noturn     the head's turn is not taken out of a point
 *   nostrength the strength slider does nothing                  norecord   a pass does not record the face pin
 *   noflip     a flipped character drops its face pins           noundo     making a face pin is not one undo step
 *   noremove   removing a face pin leaves it pulling             noread     the face camera stops reading after one frame
 *
 * The made-up face goes in through the pilot's own camera code (al-inputs.js watch) as a MediaPipe result would, so a fault
 * in that path (as the 11-22 name clash that stopped the tracker after one frame) is caught too.
 */
(function (root) {
  'use strict';
  var AL = root.AL, T = AL.breakTest = AL.breakTest || {}, K = T.kit, X = T.facepins = {};

  /** A made-up face: 478 points in a lumpy oval with a domed depth (the same recipe as the phone's FaceEngineTest). */
  function cloud() {
    var p = new Float32Array(478 * 3), seed = 12345;
    function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
    for (var i = 0; i < 478; i++) {
      var a = rnd() * Math.PI * 2, r = 0.2 + 0.8 * rnd();
      p[i * 3] = 0.5 + Math.cos(a) * r * 0.08; p[i * 3 + 1] = 0.6 + Math.sin(a) * r * 0.11; p[i * 3 + 2] = -0.06 * (1 - r * r);
    }
    return p;
  }
  function posed(p, yaw, pitch, roll, scale, dx, dy) {
    var D = Math.PI / 180, cy = Math.cos(yaw * D), sy = Math.sin(yaw * D), cp = Math.cos(pitch * D), sp = Math.sin(pitch * D), cr = Math.cos(roll * D), sr = Math.sin(roll * D);
    var o = new Float32Array(p.length);
    for (var i = 0; i < p.length / 3; i++) {
      var x = p[i * 3] - 0.5, y = p[i * 3 + 1] - 0.6, z = p[i * 3 + 2];
      var x1 = cy * x + sy * z, z1 = -sy * x + cy * z, y1 = cp * y - sp * z1, z2 = sp * y + cp * z1, x2 = cr * x1 - sr * y1, y2 = sr * x1 + cr * y1;
      o[i * 3] = 0.5 + dx + x2 * scale; o[i * 3 + 1] = 0.6 + dy + y2 * scale; o[i * 3 + 2] = z2 * scale;
    }
    return o;
  }
  function smileOf(p) { var o = Float32Array.from(p); o[61 * 3] -= 0.012; o[61 * 3 + 1] -= 0.008; return o; }   // out and up (the person's right corner)

  X.run = async function (opt) {
    opt = opt || {};
    var plant = opt.plant || null, app = AL.app, inp = app.inp, R = K.mk(), FP = AL.facepins, F = AL.face;
    var savedCal = root.localStorage ? root.localStorage.getItem('al.face.cal') : null;   // the owner's own neutral, put back after
    var saved = K.ids(app.ch), keep = { on: inp.cam.on, mode: inp.cam.mode, stage: inp.cam._faceStage, lm: inp.cam.lm, v: inp.cam.v, take: inp.take, flip: inp.pose.flip, pilotFace: F.pilotFace },
      realFeed = FP.feed, realOff = F.pointOffsets, realStrength = FP.strength, realChannels = inp.channels, realEdit = app.edit, realUnbind = FP.unbind;
    K.sim(true);
    try {
      if (plant === 'nofeed') FP.feed = function (i, f, dt) { realFeed(i, null, dt); };
      if (plant === 'noturn') F.pointOffsets = function (rest, now, chosen, side) {          // plain differences: the head's turn is left in
        if (!rest || !now) return null; var o = new Float32Array(chosen.length * 2);
        chosen.forEach(function (i, k) { o[k * 2] = (now[i * 3] - rest[i * 3]) * side; o[k * 2 + 1] = now[i * 3 + 1] - rest[i * 3 + 1]; }); return o;
      };
      if (plant === 'nostrength') FP.strength = function () {};
      if (plant === 'norecord') inp.channels = function () { return realChannels.call(this).filter(function (n) { return n.slice(0, 3) !== 'pt.'; }); };
      if (plant === 'noflip') FP.feed = function (i, f, dt) {                                 // the signals are there, but a mirror cannot see them
        realFeed(i, f, dt); for (var k in i.S) if (k.slice(0, 3) === 'pt.') Object.defineProperty(i.S, k, { value: i.S[k], enumerable: false, writable: true, configurable: true });
      };
      if (plant === 'noremove') FP.unbind = function (ch, id) { ch.facePins = FP.list(ch).filter(function (r) { return r.id !== id; }); };
      if (plant === 'noread') F.pilotFace = function (r, cam, tt) { var o = keep.pilotFace(r, cam, tt); cam.lm = r && r.faceLandmarks ? r.faceLandmarks[0] : null; return o; };

      AL.stages.go(app, 'perform');
      inp.take = null; inp.pose.flip = false;
      var set = app.ch.settings = app.ch.settings || {}; set.breathe = false; set.idle = false;   // still, so only the face moves it
      // the face: a stage whose neutral is the made-up face, fed whatever "the camera sees" now
      var base = cloud(), now = { xyz: base }, t = 0, rawHead = { headYawDeg: 0, headPitchDeg: 0, headRollDeg: 0 };
      var st = new F.Stage(F.calibration(), {});
      for (var i = 0; i < 12; i++) st.process(rawHead, (t += 1 / 30), base);
      R.F('1 the neutral keeps where every face point rests', !!(st.cal.neutralSet && st.cal.points));
      // the camera: a stand-in for MediaPipe's FaceLandmarker and the video, read by the pilot's own watch()
      var W = 640, Hh = 480, tall = Hh / W;
      function result(xyz) {
        var marks = []; for (var j = 0; j < 478; j++) marks.push({ x: xyz[j * 3], y: xyz[j * 3 + 1] / tall, z: xyz[j * 3 + 2] });
        return { faceBlendshapes: [{ categories: [] }], facialTransformationMatrixes: [{ data: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1] }], faceLandmarks: [marks] };
      }
      inp.cam.on = true; inp.cam.mode = 'always'; inp.cam._faceStage = st;
      inp.cam.v = { readyState: 4, videoWidth: W, videoHeight: Hh };
      inp.cam.lm = { detectForVideo: function () { return result(now.xyz); } };
      var unthrottle = function () { inp.cam.last = -1e9; };                      // every simulated frame reads the camera
      app.hooks.push(unthrottle);
      await K.step(8);

      // pin your right mouth corner to the part under the middle of the head
      var hd = AL.firstRole(app.ch.root, 'head') || AL.find(app.ch.root, function (k) { return k.type === 'art'; }), cc = K.cloud(app, hd);
      var px = { x: cc.x * app.view.s + app.view.ox, y: (cc.y + cc.h * 0.18) * app.view.s + app.view.oy };
      var past = app.hist.past.length;
      if (plant === 'noundo') app.edit = function (fn) { fn(); AL.reindex(app.ch); app.touch(); };
      var ok = FP.pinAt(app, 61, px);
      app.edit = realEdit;
      R.F('2 a tap on the character makes a face pin', ok && FP.list(app.ch).length === 1, { pins: FP.list(app.ch).length });
      R.F('2 ...as one undo step', app.hist.past.length === past + 1, { steps: app.hist.past.length - past });
      var q = FP.list(app.ch)[0];
      if (!q) throw new Error('no face pin to test');
      var part = AL.node(app.ch, q.node);
      function snap() { return K.snap(app); }
      function cmp(a, b) {                                     // only the pinned part's drawing
        var px = 0; for (var j = 0; j < a.ids.length; j++) { if (a.ids[j].split('#')[0] !== part.id) continue; var k = b.ids.indexOf(a.ids[j]); if (k < 0) return 1e9;
          for (var v = 0; v < a.v[j].length; v += 2) px = Math.max(px, Math.hypot(a.v[j][v] - b.v[k][v], a.v[j][v + 1] - b.v[k][v + 1])); }
        return px;
      }

      now.xyz = base; await K.step(20); var rest = snap();
      now.xyz = smileOf(base); await K.step(20); var smile = snap(), moved = cmp(rest, smile);
      R.F('3 a smile moves the pinned part', moved > 2, { px: moved });
      now.xyz = posed(base, 25, -10, 12, 1.3, 0.05, -0.03); await K.step(20);
      var px1 = Math.abs(inp.S['pt.61.x'] || 0), py1 = Math.abs(inp.S['pt.61.y'] || 0);
      R.F('4 turning, nodding, tilting and coming closer do not pull the face pin', px1 < 0.01 && py1 < 0.01, { x: px1, y: py1 });

      now.xyz = base; await K.step(20); var rest2 = snap();
      FP.strength(app, q.id, 0); now.xyz = smileOf(base); await K.step(20);
      var still = cmp(rest2, snap());
      R.F('5 strength 0 holds the pin still', still < 0.5, { px: still });
      FP.strength(app, q.id, 1); await K.step(20); var s1 = cmp(rest2, snap());
      FP.strength(app, q.id, 2); await K.step(20); var s2 = cmp(rest2, snap());
      R.F('5 strength 2 pulls about twice as far as 1', s2 > s1 * 1.6, { one: s1, two: s2 });
      FP.strength(app, q.id, 1);

      inp.pose.flip = true; now.xyz = base; await K.step(25); var frest = snap();
      now.xyz = smileOf(base); await K.step(20); var fmoved = cmp(frest, snap());
      R.F('6 a character turned the other way still follows its face pin', fmoved > 2, { px: fmoved });
      inp.pose.flip = false; now.xyz = base; await K.step(20);

      inp.record(); await K.step(10); now.xyz = smileOf(base); await K.step(15); now.xyz = base; await K.step(5); inp.stop();
      var lane = inp.take && inp.take.ch['pt.61.x'];
      R.F('7 a pass records the face pin', !!lane && lane.some(function (v) { return v != null && Math.abs(v) > 0.01; }), { lane: lane ? lane.length : 0 });
      inp.cam.on = false; inp.play(); var seen = 0;
      for (i = 0; i < 40; i++) { await K.step(1); seen = Math.max(seen, Math.abs(inp.S['pt.61.x'] || 0)); }
      inp.stop();
      R.F('7 ...and plays it back with the camera off', seen > 0.01, { most: seen });

      inp.cam.on = true; now.xyz = base; await K.step(15); var before = snap();
      FP.remove(app, q.id); now.xyz = smileOf(base); await K.step(20);
      var after = cmp(before, snap()), left = (AL.node(app.ch, q.node).shapes || []).filter(function (s) { return s.face; }).length;
      R.F('8 removing a face pin stops it pulling', after < 0.5 && !left && !FP.list(app.ch).length, { px: after, shapesLeft: left });
      app.undo();
      R.F('8 ...and Undo brings it back', FP.list(app.ch).length === 1);
    } catch (e) {
      R.fails.push({ label: 'error: ' + (e && e.message), info: e && e.stack });
    } finally {
      FP.feed = realFeed; F.pointOffsets = realOff; FP.strength = realStrength; inp.channels = realChannels; app.edit = realEdit; FP.unbind = realUnbind; F.pilotFace = keep.pilotFace;
      if (typeof unthrottle === 'function' && app.hooks.indexOf(unthrottle) >= 0) app.hooks.splice(app.hooks.indexOf(unthrottle), 1);
      inp.cam.on = keep.on; inp.cam.mode = keep.mode; inp.cam._faceStage = keep.stage; inp.cam.lm = keep.lm; inp.cam.v = keep.v; inp.take = keep.take; inp.pose.flip = keep.flip;
      K.restore(app, saved);
      try { if (savedCal == null) root.localStorage.removeItem('al.face.cal'); else root.localStorage.setItem('al.face.cal', savedCal); } catch (e2) { /* no storage */ }
    }
    return { checks: R.n, fails: R.fails, note: R.note, plant: plant };
  };

  X.proof = async function () {
    var out = {}, want = { nofeed: /^3 a smile/, noturn: /^4 turning/, nostrength: /^5 strength 0/, norecord: /^7 a pass records/,
      noflip: /^6 a character turned/, noundo: /^2 \.\.\.as one undo step/, noremove: /^8 removing/, noread: /^3 a smile/ };
    for (var k in want) { var r = await X.run({ plant: k }); out[k] = { caught: r.fails.some(function (f) { return want[k].test(f.label); }), fails: r.fails.map(function (f) { return f.label; }).slice(0, 4) }; }
    return out;
  };
})(typeof self !== 'undefined' ? self : this);
