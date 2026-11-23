/* AvatarLab: BREAK TEST, walking with your face, and toward the camera and away (al-face-walk.js, al-depth-walk.js, the walker's depth).
 * It turns on simulated time itself (AL.breakTest.kit.sim(true)): reload the page after it, to get the page's own clock back.
 * Load after al-breaktest.js, al-breaktest-fit.js, al-breaktest-walk.js and al-breaktest-scene-walk.js. Uses the owner's saved Topps when it is
 * there. The face is a stand-in (AL.faceWalk.source is replaced for the run): the camera itself is not needed.
 *
 *   await AL.breakTest.faceWalk.run()        every check          .run({ only: ['deep'] })   some parts
 *   await AL.breakTest.faceWalk.proof()      every planted fault must be caught by its own check
 *
 * Parts and the faults planted for them:
 *   lr       lroff     the Left and right tick box is ignored         nodead   no dead zone: a little drift walks it
 *            tilt      a head tilt (it swings the face sideways) walks it
 *            tight     the tilt guard is so tight that his real lean (a little tilt) cannot walk it
 *   deep     float     it grows where it stands (the ground line does not come down)
 *            slide     a planted foot slides along with the body      nostep   the feet never step while it comes closer
 *   front    front     it never turns to its front drawing
 *   close    stuck     unticking Up close does not walk it back
 *   drawer   drawer    the drawer never opens
 *   scene    order     the nearer one is not drawn in front           flat     in a scene it does not grow as it comes closer
 *   take     shrink    a take loses how big it was drawn             keepown  Play does not take the body back from the face walk
 *   own      unowned   a later pass does not record what the face walked
 *   plain    always    the plain stage is given depth with every tick box off
 *   fours    leg       a leg comes off while it walks closer on all fours
 *   deadz    nodeadz   no dead zone for closer and further
 *   stick    stickflat the joystick in perspective still jumps and crouches   nogrid   no floor grid in the pad
 *            stickundo the perspective tick is changed behind Undo's back     noflick  a flick up no longer jumps in perspective
 *   angle    flatgrow  overhead, it still grows as it comes nearer        gridflat the grid does not show the camera's angle
 *            sceneangle in a scene the camera angle is not the scene's floor (or not one Undo step)
 */
(function (root) {
  'use strict';
  var AL = root.AL, T = AL.breakTest, K = T.kit, Wk = T.walk, FWT = T.faceWalk = {}, scene = AL.scene, fw = AL.faceWalk, dw = AL.depthWalk, W = scene.W, H = scene.H;
  var PARTS = ['lr', 'deep', 'front', 'close', 'drawer', 'scene', 'take', 'own', 'plain', 'fours', 'deadz', 'stick', 'angle'];
  var FACE = { leanX: 0, near: 0, face: 1 }, KEEP = { plain: dw.plain, front: dw.front, footY: dw.footY, scene: dw.scene, drawer: fw.drawer, step: fw.step, on: fw.on, sdOn: AL.stickDepth.on, sdGrid: AL.stickDepth.grid, sdSet: AL.stickDepth.setAngle };
  function q(sel) { return document.querySelector(sel); }
  function r1(v) { return Math.round(v * 10) / 10; }
  function face(lx, nz) { FACE.leanX = lx; FACE.near = nz; }
  async function ownTopps(app) {
    var o = null; try { o = await fetch('local/owner-topps.character.json').then(function (r) { return r.ok ? r.json() : null; }); } catch (e) { o = null; }
    if (!o) { await AL.intake.builtIn(app, 'topps'); return 'built in'; }
    delete o.take; AL.img.map = {}; var ch = await AL.fromJson(o); app.setMode('build'); app.setCharacter(ch, { quiet: true }); return 'the owner\'s';
  }
  /** Everything back to standing at its place, no tick boxes, the stand-in face at rest. */
  async function rest(app) {
    var inp = app.inp, w = inp.walker; fw.room.lr = false; fw.room.depth = false; inp._close = false; face(0, 0);
    inp.clearTake(); inp.resetPose(); inp.held = {}; w.reset(); inp.pose.flip = false; inp._fw = null; inp._depth = false;
    (app.ch.poses || []).forEach(function (p) { if (p.trig) inp.setTrig(p.trig, false); });
    await K.raf(60);
  }
  /** Gaps of the one in hand over what they are standing (or in the stance): the walking test's measure. */
  function apart(X) {
    var app = AL.app, m = T.measure(app), jg = Wk.jointGaps(app), w = { gap: 0, joint: 0, bad: !!m.bad }, fp = AL.stance.fours(app.ch), lv = fp && app.inp.poseLvl ? Math.min(1, Math.max(0, app.inp.poseLvl[fp.id] || 0)) : 0, k;
    var s = app.inp.S.rootScale || 1;      // (gaps are measured on the drawing: a character drawn half as big again has gaps half as big again, so they are read at its own size)
    for (k in m.gaps) { var g = m.gaps[k] / s; if (g >= 0) w.gap = Math.max(w.gap, g - Math.max(0, X.base[k] || 0)); }
    for (k in jg) { var b0 = X.jbase[k] || 0, b1 = X.jbaseS && X.jbaseS[k] != null ? X.jbaseS[k] : b0; w.joint = Math.max(w.joint, jg[k] / s - (b0 + (b1 - b0) * lv)); }
    return w;
  }
  function whole(w) { return !w.bad && w.gap <= 40 && w.joint <= 20; }
  function worse(a, b) { return { gap: Math.max(a.gap, b.gap), joint: Math.max(a.joint, b.joint), bad: a.bad || b.bad }; }
  function say(w) { return { gap: Math.round(w.gap), joint: Math.round(w.joint), bad: w.bad }; }
  /** The drawn picture's top and bottom (its own units, alone on the plain stage). */
  function box(app) { var y0 = 1e9, y1 = -1e9; app.out.items.forEach(function (it) { if (it.ghost) return; for (var i = 1; i < it.v.length; i += 2) { y0 = Math.min(y0, it.v[i]); y1 = Math.max(y1, it.v[i]); } }); return { y0: y0, y1: y1 }; }
  function legKeys(w) { var l = w.legs(); return l ? l.base.map(function (q) { return q.key; }) : []; }

  /** Simulated time, with the page's last real frame let through first: a frame the page had already asked for still comes once, with a real
      timestamp, and would put every take's clock off its 33 ms steps for the rest of the run (the first run after a load failed that way). */
  async function simOn() { if (K._sim) return; K.sim(true); await new Promise(function (r) { setTimeout(r, 1200); }); K._sim.now = performance.now() + 50; }
  var part = {};
  part.lr = async function (X, F, plant, app) {
    await rest(app); var w = app.inp.walker, on0 = fw.on;
    if (plant === 'lroff') fw.on = function (k) { return k === 'lr' ? false : on0(k); };
    if (plant === 'nodead') fw.DEAD.x = 0; if (plant === 'tight') fw.TILT = 0.2;
    try {
      fw.room.lr = true; FACE.roll = 0.23; face(0.6, 0); await K.raf(60); var right = w.tx; FACE.roll = 0;      // (his real lean tilts the head a little: 0.23 on the Note 9)
      face(-0.6, 0); await K.raf(120); var left = w.tx;
      F('lr: your face to the right walks it right, to the left walks it left', right > 40 && left < right - 60, { right: Math.round(right), left: Math.round(left) });
      face(0, 0); await K.raf(30); face(0.1, 0); await K.raf(30); var t0 = w.tx; await K.raf(90);      // (the face settles first: the walk eases off on its way back to the middle)
      F('lr: a little drift of the face (inside the dead zone) walks nothing', Math.abs(w.tx - t0) < 0.5, { moved: r1(w.tx - t0) });
      if (plant === 'tilt') fw.TILT = 9;
      face(0, 0); await K.raf(30); FACE.roll = 0.44; face(0.38, 0); await K.raf(30); var t1 = w.tx; await K.raf(60); FACE.roll = 0;
      F('lr: tilting the head toward a shoulder (which swings the face sideways) walks nothing', Math.abs(w.tx - t1) < 0.5, { moved: r1(w.tx - t1) });
    } finally { fw.on = on0; fw.DEAD.x = 0.18; }
  };
  part.deep = async function (X, F, plant, app) {
    await rest(app); var inp = app.inp, w = inp.walker, b0 = box(app), keys = legKeys(w), slid = 0, behind = 0, fr = 0, s1 = 1, stride = w.cfg().stride || 60, plain0 = dw.plain;
    if (plant === 'float') dw.plain = function (ch) { var z = plain0(ch); z.lim = [0, 0]; z.s = function () { return 1 + Math.min(fr, 60) / 60 * dw.NEAR; }; return z; };      // (it grows where it stands)
    fw.room.depth = true; face(0, 1); var ww = { gap: 0, joint: 0 };
    for (var i = 0; i < 110; i++) {
      var before = {}; keys.forEach(function (k2) { var f = w.feet && w.feet[k2]; if (f && f.pos) before[k2] = { x: f.pos.x, y: f.pos.y, st: !!f.step }; });
      await K.raf(1); fr++;
      if (plant === 'slide') keys.forEach(function (k2) { var f = w.feet[k2]; if (f && f.home && !f.step) { f.pos.x = f.home.x; f.pos.y = f.home.y; } });
      if (plant === 'nostep') keys.forEach(function (k2) { var f = w.feet[k2]; if (f) f.step = null; });
      keys.forEach(function (k2) { var f = w.feet[k2], b = before[k2]; if (!f || !f.pos || !b) return; if (!b.st && !f.step && Math.hypot(f.pos.x - b.x, f.pos.y - b.y) > 0.5) slid++; if (!f.step) behind = Math.max(behind, Math.hypot(f.home.x - f.pos.x, f.home.y - f.pos.y) / (inp.S.rootScale || 1)); });      // (a planted foot, at its own size: one in the air is on its way)
      ww = worse(ww, apart(X)); s1 = inp.S.rootScale;
    }
    await K.raf(40); var b1 = box(app);
    F('deep: your face closer walks it toward the camera: it comes nearly half as big again', s1 > 1 + dw.NEAR * 0.9, { scale: r1(s1 * 100) / 100 });
    F('deep: ...growing from its eye line down: the top of it stays, its feet come down the screen', Math.abs(b1.y0 - b0.y0) < (b0.y1 - b0.y0) * 0.05 && b1.y1 - b0.y1 > (b0.y1 - b0.y0) * dw.NEAR * 0.8, { topMoved: r1(b1.y0 - b0.y0), feetDown: r1(b1.y1 - b0.y1), tall: r1(b0.y1 - b0.y0) });
    F('deep: ...with real steps: a planted foot never slides, and no foot is left more than a stride behind', slid === 0 && behind < stride * 1.1, { slid: slid, behind: r1(behind), stride: stride });
    F('deep: ...and nothing comes off the body on the way', whole(ww), say(ww));
    dw.plain = plain0; w.z = null;
  };
  part.front = async function (X, F, plant, app) {
    await rest(app); var inp = app.inp, w = inp.walker, f0 = null, dwf = dw.front;
    if (plant === 'front') dw.front = function () { return null; };
    try {
      inp.pose.turnX = 0.8; await K.raf(30); var t0 = inp.S.turnX, least = 9;
      inp._close = true; for (var i = 0; i < 40; i++) { await K.raf(1); if (w.z) f0 = w.z.front; least = Math.min(least, Math.abs(inp.S.turnX - (f0 == null ? 0 : f0))); }
      F('front: walking toward the camera it turns to its front drawing (Topps: Frontal)', t0 > 0.6 && dwf(app.ch) != null && least < 0.25, { from: r1(t0), closest: r1(least), front: dwf(app.ch) });
      await K.raf(60); F('front: ...and back to where its head was once it stands', Math.abs(inp.S.turnX - 0.8) < 0.15, { turnX: r1(inp.S.turnX) });
    } finally { dw.front = dwf; if (app.ch) app.ch._dwIx = null; }
  };
  part.close = async function (X, F, plant, app) {
    await rest(app); var inp = app.inp, w = inp.walker, hook = function () { if (plant === 'stuck' && X.wasClose) inp._close = true; };
    app.hooks.unshift(hook);
    try {
      fw.setClose(app, true); X.wasClose = true; await K.raf(110); var near = inp.S.rootScale;
      fw.setClose(app, false); await K.raf(160);
      F('close: Up close walks it to the camera; unticked it walks back to its place, and the plain stage is as before', near > 1 + dw.NEAR * 0.9 && Math.abs(w.base) < 0.1 && !w.z && inp.S.rootScale === 1, { near: r1(near * 100) / 100, base: r1(w.base), z: !!w.z, scale: inp.S.rootScale });
    } finally { app.hooks.splice(app.hooks.indexOf(hook), 1); X.wasClose = false; inp._close = false; }
  };
  part.drawer = async function (X, F, plant, app) {
    await rest(app); var dr = fw.drawer; if (plant === 'drawer') fw.drawer = function () { };
    try {
      app.faceWalkOpen = false; app.refreshPanel(true); await K.raf(2);
      var chip = [].filter.call(document.querySelectorAll('#panelbody button'), function (b) { return b.textContent.trim() === 'Walk with your face'; })[0];
      if (chip) chip.click(); await K.raf(2);
      var tg = [].slice.call(document.querySelectorAll('#panelbody .facewalk .tg')), names = tg.map(function (b) { return b.textContent; }), labelled = tg.every(function (b) { return b.title && b.title.length > 20; });
      var lr = tg.filter(function (b) { return /^Left and right/.test(b.textContent); })[0]; if (lr) lr.click(); await K.raf(2); var stored = JSON.parse(localStorage.getItem('al.facewalk') || '{}').lr;
      F('drawer: "Walk with your face" under Walking opens a drawer of three tick boxes, each with a hover label, and a tick is kept', !!chip && tg.length === 3 && labelled && fw.on('lr') && stored === true && !!q('#panelbody .facewalk .tg.on'), { chip: !!chip, ticks: names, labelled: labelled, kept: stored });
    } finally { fw.drawer = dr; fw.room.lr = false; try { localStorage.setItem('al.facewalk', '{}'); } catch (e) { } app.faceWalkOpen = false; app.refreshPanel(true); }
  };
  part.plain = async function (X, F, plant, app) {
    await rest(app); var inp = app.inp, w = inp.walker, hook = function () { if (plant === 'always') inp._depth = true; }, sc = 0, zs = 0;
    app.hooks.push(hook);
    try {
      inp.held.body = true; w.grab([0, 0]); for (var i = 1; i <= 40; i++) { w.drag([i * 8, 0]); await K.raf(1); if (inp.S.rootScale !== 1) sc++; if (w.z) zs++; } w.release(); inp.held.body = false; await K.raf(10);
      F('plain: with every tick box off the plain stage is exactly as before (no depth, never a different size)', sc === 0 && zs === 0 && dw.room(app) === 0.05, { sized: sc, depthModel: zs });
    } finally { app.hooks.splice(app.hooks.indexOf(hook), 1); inp._depth = false; }
  };
  part.fours = async function (X, F, plant, app) {
    var fp = AL.stance.fours(app.ch); if (!fp) { X.note.push('fours: not run, no all fours'); return; }
    await rest(app); var inp = app.inp, leg = AL.firstRole(app.ch.root, 'legR'), dx0 = leg && leg.dx, ww = { gap: 0, joint: 0 };
    inp.setTrig(fp.trig, true); await K.raf(60);
    try {
      inp._close = true; for (var i = 0; i < 100; i++) { if (plant === 'leg' && i === 40 && leg) leg.dx = (dx0 || 0) + 200; await K.raf(1); ww = worse(ww, apart(X)); }
      inp._close = false; for (i = 0; i < 120; i++) { await K.raf(1); ww = worse(ww, apart(X)); }
      F('fours: on all fours, walking up close and back: every limb stays joined to the body', whole(ww), say(ww));
    } finally { if (leg) { if (dx0 == null) delete leg.dx; else leg.dx = dx0; } inp.setTrig(fp.trig, false); }
  };
  part.deadz = async function (X, F, plant, app) {
    await rest(app); var w = app.inp.walker; if (plant === 'nodeadz') fw.DEAD.z = 0;
    try { fw.room.depth = true; face(0, 0.1); await K.raf(90); F('deadz: a little drift nearer (inside the dead zone) walks nothing', Math.abs(w.base) < 0.5, { base: r1(w.base) }); }
    finally { fw.DEAD.z = 0.15; }
  };

  /* the joystick in perspective (al-stick-depth.js): up walks away, down walks toward you, left and right as before, flicks still jump; a floor grid in the pad */
  function cyan(pad) { var c = pad.querySelector('canvas'), g = c.getContext('2d'), d = g.getImageData(0, 0, c.width, c.height).data, n = 0; for (var i = 0; i < d.length; i += 4) if (d[i + 3] > 10 && d[i + 2] > d[i] + 40 && d[i + 1] > d[i] + 30) n++; return n; }
  part.stick = async function (X, F, plant, app) {
    await rest(app); var inp = app.inp, w = inp.walker, sd = AL.stickDepth, on0 = sd.on, grid0 = sd.grid, sw0 = AL.stick.SWIPE.dist, st = app.ch.settings, keep = JSON.stringify(st.stick || null);
    var ev = function (el, type, x, y) { el.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, pointerId: 5, clientX: x, clientY: y, button: 0, isPrimary: true })); };
    var wait = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
    if (plant === 'stickflat') sd.on = function () { return false; };
    if (plant === 'nogrid') sd.grid = function () { };
    if (plant === 'noflick') AL.stick.SWIPE.dist = 99;
    try {
      AL.stick.set(app, true); await K.raf(3); var pad = q('.stickpad'); if (!pad) { F('stick: the joystick in perspective walks away and toward you', false, 'no pad'); return; }
      var n0 = app.hist.past.length;
      if (plant === 'stickundo') { st.stick = st.stick || {}; st.stick.depth = true; } else { var tick = sd.toggle(app).querySelector('.tg'); tick.click(); }
      var oneStep = app.hist.past.length === n0 + 1; await K.raf(4); pad = q('.stickpad');
      var lit = cyan(pad); st.stick.depth = false; app.refreshPanel(true); await K.raf(3); var dark = cyan(q('.stickpad')); st.stick.depth = true; app.refreshPanel(true); await K.raf(3); pad = q('.stickpad');
      F('stick: in the joystick\'s settings, "Up and down walk into the picture" is one Undo step, and a thin floor grid shows in the pad while it is on', oneStep && lit > 200 && dark < 20, { undoStep: oneStep, gridPixels: lit, without: dark });
      var r = pad.getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height / 2, y0 = w.y - w.base;
      ev(pad, 'pointerdown', cx, r.top + 4); await K.raf(40); await wait(420); ev(pad, 'pointerup', cx, r.top + 4); await K.raf(10); var away = w.base, sAway = inp.S.rootScale, crouch = Math.abs(w.y - w.base - y0);
      ev(pad, 'pointerdown', cx, r.bottom - 4); await K.raf(90); await wait(420); ev(pad, 'pointerup', cx, r.bottom - 4); await K.raf(40); var near = w.base, sNear = inp.S.rootScale;
      ev(pad, 'pointerdown', r.left + 4, cy); await K.raf(25); await wait(420); ev(pad, 'pointerup', r.left + 4, cy); await K.raf(10); var left = w.tx;
      F('stick: pushed up it walks away from you (smaller), pulled down it comes toward you (bigger), left still walks left, and it neither jumps nor crouches', away < -5 && sAway < 0.97 && near > 5 && sNear > 1.05 && left < -10 && crouch < 6 && !w.air, { away: Math.round(away), scaleAway: r1(sAway * 100) / 100, near: Math.round(near), scaleNear: r1(sNear * 100) / 100, left: Math.round(left), crouch: r1(crouch) });
      w.home(); await K.raf(150); var y1 = w.y, air = false, top = w.y;
      ev(pad, 'pointerdown', cx, cy); ev(pad, 'pointermove', cx, cy - r.height * 0.35); ev(pad, 'pointerup', cx, cy - r.height * 0.35);
      for (var i = 0; i < 34; i++) { await K.raf(1); await wait(30); if (w.air) air = true; top = Math.min(top, w.y); }
      F('stick: in perspective a flick up still jumps', air && top < y1 - 5, { air: air, rose: Math.round(y1 - top) });
    } finally { sd.on = on0; sd.grid = grid0; AL.stick.SWIPE.dist = sw0; st.stick = JSON.parse(keep) || undefined; AL.stick.release(app); AL.stick.set(app, false); await K.raf(2); }
  };

  /* the camera's angle (al-stick-depth.js): overhead no change of size, lower more; the grid shows it; in a scene it is the scene's floor */
  function gridPic(deg) { var c = document.createElement('canvas'); c.width = c.height = 64; var g = c.getContext('2d'); AL.stickDepth.grid(g, 64, 32, 26, 0, deg); return g.getImageData(0, 0, 64, 64).data; }
  async function closeScale(app) { var inp = app.inp, w = inp.walker; inp._close = true; await K.raf(110); var o = { s: inp.S.rootScale, b: w.base }; inp._close = false; await K.raf(160); return o; }
  part.angle = async function (X, F, plant, app) {
    var sd = AL.stickDepth, plain0 = dw.plain, grid0 = sd.grid, set0 = sd.setAngle, st = app.ch.settings, a0 = st.floorAngle;
    if (plant === 'flatgrow') dw.plain = function (ch) { var keep = ch.settings.floorAngle; ch.settings.floorAngle = 45; try { return plain0(ch); } finally { ch.settings.floorAngle = keep; } };
    if (plant === 'gridflat') sd.grid = function (g, s, m, r, ph) { return grid0(g, s, m, r, ph, 45); };
    if (plant === 'sceneangle') sd.setAngle = function (a, deg) { if (!(a.scene && !a.scene.dive)) return set0.apply(sd, arguments); };
    try {
      await rest(app); st.floorAngle = 0; var flat = await closeScale(app); st.floorAngle = 45; var mid = await closeScale(app); st.floorAngle = 75; var low = await closeScale(app);
      F('angle: overhead it walks toward you down the picture with no change of size; the lower the camera, the more it grows', flat.b > 50 && Math.abs(flat.s - 1) < 0.01 && mid.s > 1.2 && low.s > mid.s + 0.05, { overhead: r1(flat.s * 100) / 100, at45: r1(mid.s * 100) / 100, at75: r1(low.s * 100) / 100, cameDown: Math.round(flat.b) });
      var p0 = gridPic(0), p75 = gridPic(75), diff = 0; for (var i = 3; i < p0.length; i += 4) if (Math.abs(p0[i] - p75[i]) > 40) diff++;
      F('angle: the floor grid shows the camera angle (overhead and low are different floors)', diff > 150, { pixelsDiffer: diff });
      st.floorAngle = a0;
      var R = await inScene(X, app), sh0 = R.data.floor.shrink, n0 = R.hist.past.length;
      sd.setAngle(app, 70, true); sd.setAngle(app, 72, false); var sh1 = R.data.floor.shrink, back = sd.angle(app), oneStep = R.hist.past.length === n0 + 1;
      AL.scene.undo(app, false, true); var undone = R.data.floor.shrink;
      F('angle: in a scene the camera angle is the scene\'s own floor (lower: smaller at the back), and a drag is one Undo step of the scene', sh1 < sh0 - 0.05 && Math.abs(back - 72) < 1.5 && oneStep && Math.abs(undone - sh0) < 1e-6, { shrink: [sh0, sh1, undone], readBack: r1(back), oneStep: oneStep });
    } finally { dw.plain = plain0; sd.grid = grid0; sd.setAngle = set0; if (a0 == null) delete st.floorAngle; else st.floorAngle = a0; }
  };

  /* ── in a scene ── */
  async function inScene(X, app) {
    if (!app.scene) { await scene.create(app, 'meadow', 'Face walk test'); await K.raf(8); }
    var R = app.scene, cast = scene.cast(R);
    if (cast.length < 2) { await scene.addCast(app, { stock: 'topps', name: 'Topps' }); await K.raf(8); cast = scene.cast(R); }
    X.R = R; X.tp = cast[0]; X.other = cast[1];
    scene.stop(app); if (R.stage !== 'perform') scene.go(app, 'perform');
    scene.set(app, X.tp.id, { x: 760, y: Math.round(H * 0.8) }); scene.set(app, X.other.id, { x: 1100, y: Math.round(H * 0.86) });
    scene.hand(app, X.tp.id); cast.forEach(function (it) { var i = R.actors[it.id].inp; i.clearTake(); i.resetPose(); i.held = {}; i.walker.reset(); i._close = false; i._fw = null; });
    fw.room.lr = false; fw.room.depth = false; face(0, 0); await K.raf(40);
    return R;
  }
  function order(R, X) { var L = R.data.items.filter(function (it) { return it.kind === 'cast'; }).map(function (it) { return { id: it.id, y: it._fy == null ? it.y : it._fy, i: it._i }; }); L.sort(function (a, b) { return a.y - b.y || a.i - b.i; }); return L.map(function (q) { return q.id; }).indexOf(X.tp.id) > L.map(function (q) { return q.id; }).indexOf(X.other.id) ? 'front' : 'behind'; }
  part.scene = async function (X, F, plant, app) {
    var R = await inScene(X, app), A = R.actors[X.tp.id], fy0 = dw.footY, sc0 = dw.scene, b0 = scene.boxOf(R, X.tp), o0 = order(R, X), log = [], G = app.G, d0 = G.draw;
    if (plant === 'order') dw.footY = function (R2, it) { return it.y; };
    if (plant === 'flat') dw.scene = function (d, it, Tt, ch) { var z = sc0(d, it, Tt, ch); z.s = function () { return 1; }; return z; };
    try {
      fw.room.depth = true; face(0, 1); await K.raf(140);
      var b1 = scene.boxOf(R, X.tp), fy = dw.footY === fy0 ? fy0(R, X.tp) : sc0(R.data, X.tp, A.T, A.ch).inv(A.inp.S.rootScale) * A.T.k + X.tp.y, want = scene.depth(R.data, fy) / scene.depth(R.data, X.tp.y), got = (b1[3] - b1[1]) / (b0[3] - b0[1]);
      G.draw = function (items) { if (items.length && items[0].id) log.push(items[0].id); return d0.apply(G, arguments); }; await K.raf(1); G.draw = d0;
      var mine = {}; AL.walk(A.ch.root, function (n) { if (n.type === 'art') mine[n.id] = 1; }); var firstMine = -1, firstOther = -1, oc = R.actors[X.other.id].ch, theirs = {}; AL.walk(oc.root, function (n) { if (n.type === 'art') theirs[n.id] = 1; });
      log.forEach(function (id, i) { if (firstMine < 0 && mine[id] && !theirs[id]) firstMine = i; if (firstOther < 0 && theirs[id] && !mine[id]) firstOther = i; });
      F('scene: coming closer in a scene it walks down the floor and grows by the scene\'s own rule', fy > X.tp.y + 60 && Math.abs(got / want - 1) < 0.04, { feetAt: Math.round(fy), place: X.tp.y, grew: r1(got * 100) / 100, floorRule: r1(want * 100) / 100 });
      F('scene: ...and once it is nearer than the other character it is drawn in front of it', o0 === 'behind' && order(R, X) === 'front' && (firstOther < 0 || firstMine < 0 || firstMine > firstOther), { before: o0, after: order(R, X) });
    } finally { dw.footY = fy0; dw.scene = sc0; G.draw = d0; fw.room.depth = false; face(0, 0); }
  };
  part.take = async function (X, F, plant, app) {
    var R = await inScene(X, app), A = R.actors[X.tp.id], inp = A.inp, live = [], pb = [];
    scene.rec(app); for (var i = 0; i < 10; i++) { await K.raf(1); live.push({ ph: inp.ph, fy: dw.footY(R, X.tp), s: inp.S.rootScale, o: order(R, X) }); }
    inp._close = true; for (i = 0; i < 100; i++) { await K.raf(1); live.push({ ph: inp.ph, fy: dw.footY(R, X.tp), s: inp.S.rootScale, o: order(R, X) }); }
    inp._close = false; for (i = 0; i < 24; i++) { await K.raf(1); live.push({ ph: inp.ph, fy: dw.footY(R, X.tp), s: inp.S.rootScale, o: order(R, X) }); }
    scene.rec(app);      // (stopped while it is still walking back: the face walk still has the body, and Play must take it at once)
    if (plant === 'keepown') fw.yields = false;
    if (plant === 'shrink') inp.take.ch.rootS = inp.take.ch.rootS.map(function (v) { return v == null ? v : 1 + (v - 1) * 0.8; });
    var ownedAtPlay = !!(inp._fw && inp._fw.own); scene.play(app);
    for (i = 0; i < live.length - 2; i++) { await K.raf(1); pb.push({ ph: inp.ph, fy: dw.footY(R, X.tp), s: inp.S.rootScale, o: order(R, X) }); }
    scene.stop(app);
    var by = {}, n = 0, off = 0, flips = 0, big = 1; pb.forEach(function (s) { by[s.ph.toFixed(5)] = s; });
    live.forEach(function (l, j) { var s = by[l.ph.toFixed(5)]; if (!s || !j) return; n++; big = Math.max(big, l.s); if (Math.abs(l.fy - s.fy) > 0.5 || Math.abs(l.s - s.s) > 1e-3) off++; if (l.o !== s.o) flips++; });
    F('take: a walk toward the camera and back is recorded and plays back the same, frame by frame, even when Play is pressed while it is still walking back: where its feet are on the floor, its size, who is in front', n > live.length * 0.9 && big > 1.1 && off === 0 && flips === 0 && ownedAtPlay, { frames: n, biggest: r1(big * 100) / 100, off: off, orderDiffers: flips, faceHadIt: ownedAtPlay });
    fw.yields = true;
  };
  part.own = async function (X, F, plant, app) {
    var R = await inScene(X, app), A = R.actors[X.tp.id], inp = A.inp, st0 = fw.step;
    scene.rec(app); await K.raf(40); scene.rec(app); await K.raf(3);                 // an older take: it stands at its place
    if (plant === 'unowned') fw.step = function (a, dt) { st0(a, dt); if (a.inp) a.inp.held.body = false; };
    try {
      fw.room.lr = true; scene.rec(app); face(0.7, 0); await K.raf(30); face(0, 0); await K.raf(10); scene.rec(app); await K.raf(3);
      var xs = (inp.take.ch.rootX || []).filter(function (v) { return v != null; }), most = Math.max.apply(null, xs);
      F('own: in a later pass, what the face walked is recorded (the take has it walking)', most > 30, { furthest: Math.round(most) });
    } finally { fw.step = st0; fw.room.lr = false; face(0, 0); }
  };

  FWT.run = async function (opt) {
    await simOn();      // (every count of frames here is a count of 33 ms steps, as in a take: on the page's own clock, 60 a second, they would be half as long. Reload the page afterwards)
    opt = opt || {}; var app = AL.app, X = K.mk(), F = X.F, plant = opt.plant || '', only = opt.only || PARTS, src0 = fw.source, room0 = JSON.stringify({ lr: fw.room.lr, depth: fw.room.depth });
    try {
      if (app.scene) await scene.close(app, true);
      X.note.push('Topps: ' + await ownTopps(app)); await K.raf(6);
      var st = app.ch.settings; st.idle = false; st.blinkAuto = false; st.breathe = false;
      K.stand(app); await K.raf(3); X.base = T.measure(app).gaps; X.jbase = Wk.jointGaps(app); X.jbaseS = await Wk.stanceJoints(app);
      fw.source = function () { return FACE; };
      AL.stages.go(app, 'perform'); await K.raf(10);
      for (var i = 0; i < PARTS.length; i++) if (only.indexOf(PARTS[i]) >= 0) {
        if (/^(scene|take|own)$/.test(PARTS[i]) === false && app.scene) { await scene.close(app, true); AL.stages.go(app, 'perform'); await K.raf(6); }
        await part[PARTS[i]](X, F, plant, app);
        if (scene._err) { F(PARTS[i] + ': nothing thrown in the frame loop', false); scene._err = false; }
      }
      F('99 nothing thrown by the end', !scene._err);
    } catch (e) { X.fails.push({ label: 'THROWN', info: String(e && e.stack || e).slice(0, 700) }); }
    fw.source = src0; dw.plain = KEEP.plain; dw.front = KEEP.front; dw.footY = KEEP.footY; dw.scene = KEEP.scene; fw.drawer = KEEP.drawer; fw.step = KEEP.step; fw.on = KEEP.on; AL.stickDepth.on = KEEP.sdOn; AL.stickDepth.grid = KEEP.sdGrid; AL.stickDepth.setAngle = KEEP.sdSet; fw.DEAD.x = 0.18; fw.DEAD.z = 0.15; fw.TILT = 0.36; fw.yields = true; FACE.roll = 0;      // (whatever a planted fault replaced, back)
    try { var r0 = JSON.parse(room0); fw.room.lr = r0.lr; fw.room.depth = r0.depth; } catch (e) { }
    try { if (AL.app.scene) await scene.close(AL.app, true); } catch (e2) { }
    try { var mine = (await scene.list(AL.app)).filter(function (e) { return e.name === 'Face walk test'; }); for (var m = 0; m < mine.length; m++) await scene.forget(AL.app, mine[m].uid); } catch (e4) { }
    try { localStorage.removeItem('al.scene'); } catch (e3) { }
    try { AL.app.inp._close = false; AL.app.inp._depth = false; } catch (e5) { }
    return { n: X.n, fails: X.fails, note: X.note };
  };
  FWT.WANT = { lroff: ['lr', /^lr: your face to the right/], tilt: ['lr', /^lr: tilting the head/], tight: ['lr', /^lr: your face to the right/], nodead: ['lr', /^lr: a little drift/], float: ['deep', /^deep: \.\.\.growing from its eye line/], slide: ['deep', /^deep: \.\.\.with real steps/], nostep: ['deep', /^deep: \.\.\.with real steps/],
    front: ['front', /^front: walking toward/], stuck: ['close', /^close: /], drawer: ['drawer', /^drawer: /], order: ['scene', /^scene: \.\.\.and once/], flat: ['scene', /^scene: coming closer/], shrink: ['take', /^take: /],
    unowned: ['own', /^own: /], keepown: ['take', /^take: /], always: ['plain', /^plain: /], leg: ['fours', /^fours: /], nodeadz: ['deadz', /^deadz: /],
    stickflat: ['stick', /^stick: pushed up/], nogrid: ['stick', /^stick: in the joystick/], stickundo: ['stick', /^stick: in the joystick/], noflick: ['stick', /^stick: in perspective a flick/],
    flatgrow: ['angle', /^angle: overhead/], gridflat: ['angle', /^angle: the floor grid/], sceneangle: ['angle', /^angle: in a scene/] };
  FWT.proof = async function (only) {
    var out = {}, want = FWT.WANT;
    for (var k in want) { if (only && only.indexOf(k) < 0) continue; var r = await FWT.run({ plant: k, only: [want[k][0]] }); out[k] = { caught: r.fails.some(function (f) { return want[k][1].test(f.label); }), fails: r.fails.map(function (f) { return f.label.slice(0, 70); }).slice(0, 4) }; }
    return out;
  };
})(typeof self !== 'undefined' ? self : this);
