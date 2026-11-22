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
 *   noentry    Face pins only in Set up (not Motion)             nozoom     the first tap does not zoom in on the spot
 *   notry      no live try after placing (Set up holds still)    noexpr     your cheeks puffing never reaches Links and Shapes
 *
 * The made-up face goes in through the pilot's own camera code (al-inputs.js watch) as a MediaPipe result would, so a fault
 * in that path (as the 11-22 name clash that stopped the tracker after one frame) is caught too.
 */
(function (root) {
  'use strict';
  var AL = root.AL, T = AL.breakTest = AL.breakTest || {}, K = T.kit, X = T.facepins = {};
  function h0(tag) { return document.createElement(tag); }

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
  function smileLeft(p) { var o = Float32Array.from(p); o[291 * 3] += 0.012; o[291 * 3 + 1] -= 0.008; return o; }   // the person's left corner
  /** A tap on the stage at stage pixel p, as a finger makes it (the pointer events the stage listens to). */
  function tapStage(p) {
    var st = document.getElementById('stage'), r = st.getBoundingClientRect(), k = st.clientWidth / r.width;
    var o = { bubbles: true, cancelable: true, pointerId: 77, pointerType: 'touch', isPrimary: true, clientX: r.left + p.x / k, clientY: r.top + p.y / k, button: 0, buttons: 1 };
    st.dispatchEvent(new PointerEvent('pointerdown', o)); o.buttons = 0; st.dispatchEvent(new PointerEvent('pointerup', o));
  }

  X.run = async function (opt) {
    opt = opt || {};
    var plant = opt.plant || null, app = AL.app, inp = app.inp, R = K.mk(), FP = AL.facepins, F = AL.face;
    var savedCal = root.localStorage ? root.localStorage.getItem('al.face.cal') : null;   // the owner's own neutral, put back after
    var saved = K.ids(app.ch), keep = { on: inp.cam.on, mode: inp.cam.mode, stage: inp.cam._faceStage, lm: inp.cam.lm, v: inp.cam.v, take: inp.take, flip: inp.pose.flip, pilotFace: F.pilotFace },
      realFeed = FP.feed, realOff = F.pointOffsets, realStrength = FP.strength, realChannels = inp.channels, realEdit = app.edit, realUnbind = FP.unbind,
      realSection = FP.section, realZoom = FP.zoomAt, realTry = FP.tryPin, realExpr = FP.expressions;
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
      if (plant === 'noentry') FP.section = function (a, pb, n) { var w = h0('div'); realSection(a, w, n); w.querySelectorAll('[data-st]').forEach(function (e) { e.setAttribute('data-st', 'setup'); }); while (w.firstChild) pb.appendChild(w.firstChild); };
      if (plant === 'nozoom') FP.zoomAt = function () { return false; };
      if (plant === 'notry') FP.tryPin = function () {};
      if (plant === 'noexpr') FP.expressions = function () {};
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
        return { faceBlendshapes: [{ categories: Object.keys(now.bs || {}).map(function (k) { return { categoryName: k, score: now.bs[k] }; }) }], facialTransformationMatrixes: [{ data: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1] }], faceLandmarks: [marks] };
      }
      function arm() {                                  // the camera on (leaving Perform turns it off; Face pins turns it back on)
        inp.cam.on = true; inp.cam.mode = 'always'; inp.cam._faceStage = st;
        inp.cam.v = { readyState: 4, videoWidth: W, videoHeight: Hh };
        inp.cam.lm = { detectForVideo: function () { return result(now.xyz); } };
      }
      arm();
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
      function cmp(a, b, only) {                               // only the pinned part's drawing
        var pid = (only || part).id, px = 0; for (var j = 0; j < a.ids.length; j++) { if (a.ids[j].split('#')[0] !== pid) continue; var k = b.ids.indexOf(a.ids[j]); if (k < 0) return 1e9;
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

      // 9 reachable where you build and where it moves (the owner, 11-22): the head's card and the body's, Set up and Motion
      var found = {}, stgs = ['setup', 'motion'], zs = ['head', 'body'];
      for (var a1 = 0; a1 < stgs.length; a1++) for (var z1 = 0; z1 < zs.length; z1++) {
        AL.stages.go(app, stgs[a1]); app.select(zs[z1]); app.refreshPanel(true);
        found[stgs[a1] + ':' + zs[z1]] = Array.prototype.some.call(document.querySelectorAll('#panelbody button'), function (bt) { return /^Face pins/.test(bt.textContent.trim()); });
      }
      R.F("9 Face pins is on the head's and the body's card, in Set up and in Motion", Object.keys(found).every(function (k) { return found[k]; }), found);

      // 10 the first tap on the character zooms in on that spot, the second places the pin
      AL.stages.go(app, 'setup'); app.select('head'); app.refreshPanel(true); app.userView = false; app.fit(true); arm(); await K.step(3);
      var s0 = app.view.s, n0 = FP.list(app.ch).length, spot = { x: cc.x, y: cc.y + cc.h * 0.3 };
      function stagePx(c) { return { x: c.x * app.view.s + app.view.ox, y: c.y * app.view.s + app.view.oy }; }
      FP.place(app, 291);
      tapStage(stagePx(spot)); await K.step(40);
      var zoomed = app.view.s / s0, n1 = FP.list(app.ch).length;
      tapStage(stagePx(spot)); await K.step(2);
      var n2 = FP.list(app.ch).length;
      R.F('10 the first tap zooms in on the spot, the second places the pin', zoomed > 1.8 && n1 === n0 && n2 === n0 + 1, { zoomed: zoomed, before: n0, afterFirst: n1, afterSecond: n2 });

      // 11 placed in Set up (where the picture holds still), the pin follows your face at once, then Done goes back
      var q2 = FP.list(app.ch)[FP.list(app.ch).length - 1], part2 = q2 ? AL.node(app.ch, q2.node) : null;
      now.xyz = base; await K.step(20); var t0 = snap();
      now.xyz = smileLeft(base); await K.step(20); var tryMoved = part2 ? cmp(t0, snap(), part2) : 0;
      R.F('11 placed in Set up, the pin follows your face right away', tryMoved > 2 && !!FP.trying, { px: tryMoved, trying: !!FP.trying });
      if (FP.tryDone) FP.tryDone(app); await K.step(60);
      var backTo = Math.abs(app.view.s / s0 - 1);
      now.xyz = base; await K.step(10); var t1 = snap(); now.xyz = smileLeft(base); await K.step(15); var stillAfter = part2 ? cmp(t1, snap(), part2) : 0;
      R.F('11 ...Done goes back to the view you had, and Set up holds still again', backTo < 0.05 && stillAfter < 0.5, { view: backTo, px: stillAfter });
      now.xyz = base;

      // 12 your cheeks puffing (or a smile, the jaw, wide eyes) drives any part through Links and Shapes
      AL.stages.go(app, 'perform'); arm(); now.bs = {}; await K.step(20); var c0 = inp.S['face.cheekPuff'] || 0;
      var hdNow = AL.firstRole(app.ch.root, 'head') || hd;      // (an Undo above put in a fresh copy of the character)
      hdNow.links = (hdNow.links || []).concat([{ sig: 'face.cheekPuff', prop: 'y', amt: 24, test: 1 }]); await K.step(5);
      function drawnMove(a, b) {                                // how far anything drawn moved
        var px = 0; for (var j = 0; j < a.ids.length; j++) { var k = b.ids.indexOf(a.ids[j]); if (k < 0) continue;   // (breathing and idle are off: only the linked part can move)
          for (var v = 0; v < a.v[j].length; v += 2) px = Math.max(px, Math.hypot(a.v[j][v] - b.v[k][v], a.v[j][v + 1] - b.v[k][v + 1])); }
        return px;
      }
      var h0s = snap();
      now.bs = { cheekPuff: 0.8 }; await K.step(25); var c1 = inp.S['face.cheekPuff'] || 0, h1s = snap(), linked = drawnMove(h0s, h1s);
      hdNow.links = hdNow.links.filter(function (l) { return !l.test; }); now.bs = {};
      R.F('12 your cheeks puffing reaches the signals, and a part linked to it moves', c0 < 0.1 && c1 > 0.5 && linked > 5,
        { before: c0, after: c1, px: linked });
      var offered = AL.cards && AL.cards.SIGS ? AL.cards.SIGS.map(function (q) { return q[0]; }) : [];
      R.F('12 ...and it is offered in the Links and Shapes lists', ['face.cheekPuff', 'face.smile', 'face.jaw', 'face.wide'].every(function (k) { return offered.indexOf(k) >= 0; }), { offered: offered.filter(function (k) { return k.slice(0, 5) === 'face.'; }) });
    } catch (e) {
      R.fails.push({ label: 'error: ' + (e && e.message), info: e && e.stack });
    } finally {
      FP.feed = realFeed; F.pointOffsets = realOff; FP.strength = realStrength; inp.channels = realChannels; app.edit = realEdit; FP.unbind = realUnbind; F.pilotFace = keep.pilotFace;
      FP.section = realSection; FP.zoomAt = realZoom; FP.tryPin = realTry; FP.expressions = realExpr; if (FP.tryDone && FP.trying) FP.tryDone(app);
      if (typeof unthrottle === 'function' && app.hooks.indexOf(unthrottle) >= 0) app.hooks.splice(app.hooks.indexOf(unthrottle), 1);
      inp.cam.on = keep.on; inp.cam.mode = keep.mode; inp.cam._faceStage = keep.stage; inp.cam.lm = keep.lm; inp.cam.v = keep.v; inp.take = keep.take; inp.pose.flip = keep.flip;
      K.restore(app, saved);
      try { if (savedCal == null) root.localStorage.removeItem('al.face.cal'); else root.localStorage.setItem('al.face.cal', savedCal); } catch (e2) { /* no storage */ }
    }
    return { checks: R.n, fails: R.fails, note: R.note, plant: plant };
  };

  X.proof = async function () {
    var out = {}, want = { nofeed: /^3 a smile/, noturn: /^4 turning/, nostrength: /^5 strength 0/, norecord: /^7 a pass records/,
      noflip: /^6 a character turned/, noundo: /^2 \.\.\.as one undo step/, noremove: /^8 removing/, noread: /^3 a smile/,
      noentry: /^9 Face pins is on/, nozoom: /^10 the first tap/, notry: /^11 placed in Set up/, noexpr: /^12 your cheeks puffing/ };
    for (var k in want) { var r = await X.run({ plant: k }); out[k] = { caught: r.fails.some(function (f) { return want[k].test(f.label); }), fails: r.fails.map(function (f) { return f.label; }).slice(0, 4) }; }
    return out;
  };
})(typeof self !== 'undefined' ? self : this);
