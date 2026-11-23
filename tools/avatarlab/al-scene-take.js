/* AvatarLab: THE SCENE'S CLOCK. Passes, the camera lane, shots.
 *
 * One performer, several characters: you have one pair of hands and one voice. So a scene is performed in PASSES.
 * The character in hand records. Everyone else plays back what they already did, in step, and anyone with nothing recorded
 * stays alive by themselves. Then you take the next character in hand and go again. One clock runs them all.
 *
 * THE CAMERA works in SHOTS, not a zoom slider:
 *   Wide                  the whole frame
 *   Two-shot              everyone, framed together (two or more characters)
 *   Close on <character>  found from the head, and it follows them
 *   your own              any view you framed by hand and kept
 * A shot is CUT to, or EASED to, as the Cut | Glide switch on the strip says (al-scene-strip.js). While recording, each change is written on the camera lane, and from
 * the first change in a pass the camera is yours from there on (older cuts after it are replaced), like any other pass.
 *
 * Phone: the clock is the same 30 fps take clock; the lane is a list of { t, shot, ease } in the scene document.
 */
(function (root) {
  'use strict';
  var AL = root.AL, scene = AL.scene, W = scene.W, H = scene.H;
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }

  /* a performer's own record / stop / play also move the scene's clock */
  scene.wire = function (app, a) {
    var inp = a.inp, rec0 = inp.record, stop0 = inp.stop, play0 = inp.play;
    inp.record = function () { rec0.call(inp); var R = app.scene; if (R) { R.camPass = false; R.camAt = null; R.clock.run = null; } };
    inp.stop = function () { var was = inp.recording; stop0.call(inp); var R = app.scene; if (R) { R.clock.run = null; if (was) scene.touch(app); } };
    inp.play = function () { play0.call(inp); var R = app.scene; if (R) { R.camAt = null; R.camPass = false; } };
  };
  /** How long the scene runs: the longest take in it, or the last camera change and a breath. */
  scene.length = function (app) {
    var R = app.scene, L = 0; scene.cast(R).forEach(function (it) { L = Math.max(L, R.actors[it.id].inp.duration()); });
    var lane = R.data.cam; if (lane.length) L = Math.max(L, lane[lane.length - 1].t + 1.5);
    return L;
  };
  scene.running = function (app) { var R = app.scene, a = R && R.actors[R.focus], m = a && a.inp; return m && m.recording ? 'rec' : (m && m.playing) || (R && R.clock.run === 'play') ? 'play' : null; };
  scene.rec = function (app) { var R = app.scene, a = R.actors[R.focus]; if (!a) { app.toast('Put a character in the scene first.'); return; } if (a.inp.recording) a.inp.stop(); else { a.inp.ph = 0; a.inp.record(); } app.refreshPanel(true); };
  scene.play = function (app) {
    var R = app.scene, a = R.actors[R.focus], m = a && a.inp;
    if (scene.running(app)) { scene.stop(app); app.refreshPanel(true); return; }
    if (m && m.take && m.take.n) { m.ph = 0; m.play(); m.scrub = false; } else if (scene.length(app) > 0) { R.clock.run = 'play'; R.clock.ph = 0; R.camAt = null; R.camPass = false; } else app.toast('Nothing is recorded in this scene yet.');
    app.refreshPanel(true);
  };
  scene.stop = function (app) {
    var R = app.scene; if (!R) return; var a = R.actors[R.focus];
    if (a && (a.inp.playing || a.inp.recording)) a.inp.stop();
    R.clock.run = null; R.clock.on = false; Object.keys(R.actors).forEach(function (id) { var o = R.actors[id]; if (o._follow) { o.inp.scrub = false; o._follow = false; } });
  };
  /** Once a frame, before anyone moves: who leads the clock, and everyone else in step with it. */
  scene.tick = function (app, dt) {
    var R = app.scene, C = R.clock, fa = R.actors[R.focus] || null, m = fa && fa.inp, L = scene.length(app), lead = !!(m && m.take && (m.recording || m.playing || m.scrub)), was = C.ph;
    if (m) m.sceneLen = L;
    if (lead) { C.ph = m.ph; C.on = true; C.run = null; }
    else if (C.run === 'play') { if (L <= 0) { C.run = null; C.on = false; } else { C.ph += dt; if (C.ph >= L) C.ph = 0; C.on = true; } }
    else C.on = false;
    if (C.on && C.ph < was - 0.2) R.camAt = null;                 // it came round again
    scene.cast(R).forEach(function (it) {
      var o = R.actors[it.id]; if (lead && o === fa) return;
      if (C.on && o.inp.take && o.inp.take.n) { o.inp.playing = false; o.inp.recording = false; o.inp.scrub = true; o.inp.ph = Math.min(C.ph, o.inp.duration()); o._follow = true; }
      else if (o._follow) { o.inp.scrub = false; o._follow = false; }
    });
    /* the camera lane */
    var lane = R.data.cam; if (!C.on || !lane.length || R.camPass) return;
    var e = null; for (var i = 0; i < lane.length; i++) { if (lane[i].t <= C.ph + 1e-4) e = lane[i]; else break; }
    if (e && e !== R.camAt) { R.camAt = e; if (scene.shotOk(app, e.shot)) setShot(R, e.shot, e.ease || 0); }
  };

  /* ── shots ── */
  function boxOf(a, ids) {
    var x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9, T = a.T; if (!a.out || !T) return null;
    a.out.items.forEach(function (it) { if (it.ghost || (ids && !ids[it.id])) return; for (var i = 0; i < it.v.length; i += 2) { var x = it.v[i], y = it.v[i + 1]; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; } });
    return x0 > x1 ? null : { x0: x0 * T.k + T.tx, y0: y0 * T.k + T.ty, x1: x1 * T.k + T.tx, y1: y1 * T.k + T.ty };
  }
  function headBox(a) {
    var hd = AL.firstRole(a.ch.root, 'head'), ids = null, b;
    if (hd) { ids = {}; AL.walk(hd, function (k) { if (k.type === 'art') ids[k.id] = 1; }); b = boxOf(a, ids); if (b) return b; }
    b = boxOf(a, null); if (!b) return null; return { x0: b.x0, x1: b.x1, y0: b.y0, y1: b.y0 + (b.y1 - b.y0) * 0.45 };      // no head named: the top of it
  }
  /** The shots on offer right now, in the order the strip shows them. */
  scene.shots = function (app) {
    var R = app.scene, cast = scene.cast(R), l = [{ key: 'wide', name: 'Wide', kind: 'wide' }];
    if (cast.length > 1) l.push({ key: 'two', name: cast.length === 2 ? 'Two-shot' : 'Everyone', kind: 'two' });
    cast.forEach(function (it) { l.push({ key: 'close:' + it.id, name: it.name, kind: 'close', pic: R.actors[it.id].pic }); });
    R.data.shots.forEach(function (s) { l.push({ key: s.id, name: s.name, kind: 'own' }); });
    return l;
  };
  scene.shotOk = function (app, key) { return scene.shots(app).some(function (s) { return s.key === key; }); };
  scene.shotName = function (app, key) { var n = key === 'free' ? 'By hand' : null; scene.shots(app).forEach(function (s) { if (s.key === key) n = s.kind === 'close' ? 'Close on ' + s.name : s.name; }); return n || 'Wide'; };
  /** Where the camera should be for a shot: its middle (stage units) and how far in (1 = the whole frame). */
  scene.target = function (app, key, fr) {
    var R = app.scene, wide = { x: W / 2, y: H / 2, z: 1 }, b, i;
    if (key === 'free' && R.free) return R.free;
    if (key === 'two') {
      var x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9; scene.cast(R).forEach(function (it) { var q = boxOf(R.actors[it.id], null); if (!q) return; x0 = Math.min(x0, q.x0); x1 = Math.max(x1, q.x1); y0 = Math.min(y0, q.y0); y1 = Math.max(y1, q.y1); });
      if (x0 > x1) return wide; return { x: (x0 + x1) / 2, y: (y0 + y1) / 2, z: clamp(Math.min(fr.w / ((x1 - x0) * 1.3), fr.h / ((y1 - y0) * 1.25)), 1, 4) };
    }
    if (key.slice(0, 6) === 'close:') { var a = R.actors[key.slice(6)]; b = a && headBox(a); if (!b) return wide; var hh = Math.max(30, b.y1 - b.y0); return { x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 + hh * 0.22, z: clamp(Math.min(fr.h / (hh * 2.3), fr.w / ((b.x1 - b.x0) * 1.5)), 1.15, 7) }; }
    for (i = 0; i < R.data.shots.length; i++) if (R.data.shots[i].id === key) return R.data.shots[i];
    return wide;
  };
  /* the frame never looks off the edge of the stage */
  scene.clampCam = function (c, fr) {
    var z = clamp(c.z, 1, 8), hw = fr.w / (2 * z), hh = fr.h / (2 * z);
    return { x: hw >= W / 2 ? W / 2 : clamp(c.x, hw, W - hw), y: hh >= H / 2 ? H / 2 : clamp(c.y, hh, H - hh), z: z };
  };
  function setShot(R, key, ease) { R.shot = key; if (ease > 0) R.easing = { from: { x: R.cam.x, y: R.cam.y, z: R.cam.z }, t0: R.t, dur: ease }; else { R.easing = null; R.cutNow = true; } }
  /** The camera this frame. A cut lands at once; an ease glides; a shot that follows someone keeps up with them softly. */
  scene.camNow = function (app, fr, dt) {
    var R = app.scene, tg = scene.clampCam(scene.target(app, R.shot, fr), fr), c = R.cam;
    if (R.easing) { var u = (R.t - R.easing.t0) / R.easing.dur; if (u >= 1) { R.easing = null; c = tg; } else { var s = u * u * (3 - 2 * u), f0 = R.easing.from; c = { x: f0.x + (tg.x - f0.x) * s, y: f0.y + (tg.y - f0.y) * s, z: Math.exp(Math.log(f0.z) + (Math.log(tg.z) - Math.log(f0.z)) * s) }; } }
    else if (R.cutNow) { c = tg; R.cutNow = false; }
    else { var k = 1 - Math.exp(-5 * dt); c = { x: c.x + (tg.x - c.x) * k, y: c.y + (tg.y - c.y) * k, z: c.z + (tg.z - c.z) * k }; }
    return (R.cam = c);
  };
  /** Go to a shot. ease = seconds to glide there (0 = cut). While recording, it is written on the camera lane. */
  scene.shot = function (app, key, ease) {
    var R = app.scene; if (!R || (key !== 'free' && !scene.shotOk(app, key))) return false;
    var a = R.actors[R.focus], m = a && a.inp, before = R.shot;
    if (m && m.recording) {
      var t = Math.round(m.ph * 100) / 100, lane = R.data.cam;
      if (!R.camPass) { R.camPass = true; lane = lane.filter(function (e) { return e.t < t; }); if (!lane.length && t > 0.05 && before !== 'free') lane.push({ t: 0, shot: before, ease: 0 }); }
      lane.push({ t: t, shot: key, ease: ease || 0 }); R.data.cam = lane; scene.touch(app);
    }
    setShot(R, key, ease || 0);
    return true;
  };
  /** Take the camera by hand (drag the empty stage, pinch, the wheel). fn changes { x, y, z }. */
  scene.freeCam = function (app, fn) {
    var R = app.scene, fr = scene.frame(R.data); if (R.shot !== 'free' || !R.free) R.free = { x: R.cam.x, y: R.cam.y, z: R.cam.z };
    fn(R.free); R.free = scene.clampCam(R.free, fr); if (R.shot !== 'free') R.shot = 'free'; R.easing = null; R.cutNow = true;
  };
  /** Keep the view you framed by hand as a shot of this scene. */
  scene.keepShot = function (app, name) {
    var R = app.scene, s = null;
    scene.edit(app, 'Keep this shot', function (d) { s = { id: AL.id('h'), name: name || 'Shot ' + (d.shots.length + 1), x: Math.round(R.cam.x), y: Math.round(R.cam.y), z: Math.round(R.cam.z * 100) / 100 }; d.shots.push(s); });
    R.shot = s.id; R.free = null; app.refreshPanel(true); return s;
  };
  scene.dropShot = function (app, id) { var R = app.scene; scene.edit(app, 'Remove a shot', function (d) { d.shots = d.shots.filter(function (s) { return s.id !== id; }); d.cam = d.cam.filter(function (c) { return c.shot !== id; }); if (R.shot === id) R.shot = 'wide'; }); };
  scene.clearCam = function (app) { scene.edit(app, 'Clear the camera lane', function (d) { d.cam = []; }); app.scene.camAt = null; };
})(typeof self !== 'undefined' ? self : this);
