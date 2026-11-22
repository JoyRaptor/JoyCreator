/* AvatarLab: BREAK TEST, the Fit box, hand-made stances and reshaping. Not part of the page: load it by hand after al-breaktest.js.
 *
 *   AL.breakTest.kit      shared helpers (snapshots of the drawn picture, wait, restore) used by every al-breaktest-<area>.js
 *   AL.breakTest.fit.run  areas 1 (hand-made stances), 2 (the Fit box on every kind of part) and 3 (reshape with pins)
 *   AL.breakTest.fit.proof  plants faults and checks the checks catch them
 *
 * Rule of the room: a check only counts once it has been seen to FAIL on a planted fault (see proof()).
 */
(function (root) {
  'use strict';
  var AL = root.AL, M = AL.M, T = AL.breakTest = AL.breakTest || {}, K = T.kit = T.kit || {};

  var realRaf = function (n) { return new Promise(function (r) { var k = n || 3; (function f() { if (k-- <= 0) return r(); requestAnimationFrame(f); })(); }); };
  var realWait = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  K.raf = function (n) { return K._sim ? K.step(n || 3) : realRaf(n); };
  K.wait = function (ms) { return K._sim ? K.step(Math.max(1, Math.round(ms / 33.3))) : realWait(ms); };
  /** SIMULATED TIME. The browser pane slows requestAnimationFrame to 1 to 4 frames a second when it is not being watched, which makes a long
      test take an hour. K.sim(true) stops the page's own loop and drives app.frame by hand, 33 ms per step, so a second of the room is 30 calls
      of the very same frame function the page runs. Reload the page afterwards. */
  K.sim = function (on) { if (on && !K._sim) { K._sim = { now: performance.now() }; window.requestAnimationFrame = function () { return 0; }; } };
  K.step = function (n) { for (var i = 0; i < n; i++) { K._sim.now += 33.3; AL.app.frame(K._sim.now); } return new Promise(function (r) { setTimeout(r, 0); }); };
  /** The whole drawn picture as numbers: every item's vertices and alpha, in draw order, and every part's matrix. */
  K.snap = function (app) {
    var o = app.out, s = { ids: [], v: [], a: [], bad: 0, M: {} };
    o.items.forEach(function (it) {
      if (it.xr) return;                                       // the cyan echo of the part in hand is a mark on the glass, not the picture
      s.ids.push(it.id + (it.tag ? '#' + it.tag : '') + (it.ghost ? 'g' : '') + (it.xr ? 'x' : '')); s.a.push(it.a);
      var v = new Float64Array(it.v); for (var i = 0; i < v.length; i++) if (!isFinite(v[i])) s.bad++; if (!isFinite(it.a)) s.bad++; s.v.push(v);
    });
    for (var k in o.M) { s.M[k] = o.M[k].slice(); for (var j = 0; j < 6; j++) if (!isFinite(s.M[k][j])) s.bad++; }
    s.order = s.ids.join(',');
    return s;
  };
  /** How far apart two pictures are: struct = the draw list differs; px = the biggest vertex move; da = the biggest alpha change. */
  K.cmp = function (a, b) {
    if (a.order !== b.order) return { struct: true, px: 1e9, da: 1, at: 'order' };
    var px = 0, da = 0, at = null;
    for (var i = 0; i < a.v.length; i++) {
      if (a.v[i].length !== b.v[i].length) return { struct: true, px: 1e9, da: 1, at: a.ids[i] };
      for (var j = 0; j < a.v[i].length; j += 2) { var d = Math.hypot(a.v[i][j] - b.v[i][j], a.v[i][j + 1] - b.v[i][j + 1]); if (d > px) { px = d; at = a.ids[i]; } }
      if (Math.abs(a.a[i] - b.a[i]) > da) da = Math.abs(a.a[i] - b.a[i]);
    }
    return { struct: false, px: px, da: da, at: at };
  };
  /** The drawn centre and box of a part (non-ghost items of its drawings). */
  K.cloud = function (app, n) {
    var ids = {}, x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9, sx = 0, sy = 0, c = 0;
    AL.walk(n.type === 'mirror' ? (AL.node(app.ch, n.of) || n) : n, function (k) { if (k.type === 'art') ids[k.id] = 1; });
    app.out.items.forEach(function (it) { if (it.ghost || !ids[it.id]) return; for (var i = 0; i < it.v.length; i += 2) { var x = it.v[i], y = it.v[i + 1]; sx += x; sy += y; c++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; } });
    return c ? { x: sx / c, y: sy / c, x0: x0, y0: y0, x1: x1, y1: y1, w: x1 - x0, h: y1 - y0, n: c } : null;
  };
  /** Every control back to rest: standing, in Fit, nothing held, no box, no pins, every button off. */
  K.stand = function (app) {
    if (app.live || AL.stages.cur(app) !== 'fit') AL.stages.go(app, 'fit');
    app.held = null; app.poseEdit = null; app.poseAmt = null; app.shapeEdit = null; app.fitOn = null; app.orderPick = null; app.bridge = null; app.toneTry = null;
    app.bt.x = 0; app.bt.y = 0; app.btd = {}; app.test.trig = {}; app.liftArt = app.liftArt;
  };
  K.restore = function (app, json) {
    var o = JSON.parse(json); AL.reindex(o); app.ch = o; app.solver.ch = o; app.solver.grids = {};
    app.hist.past.length = 0; app.hist.future.length = 0; K.stand(app); app.keepSel(); app.syncUndo(); app.refreshPanel();
  };
  K.ids = function (ch) { return JSON.stringify(ch); };
  /** Where a pin sits in the character's space, and the moves that put a finger on it and drag it by (dx, dy) in that space. */
  K.drag = function (app, pin, dx, dy) {
    var m = (pin.node && app.out.M[pin.node]) || app.out.rootM; pin.m = m;
    var w = M.ap(m, pin.x, pin.y), l = M.ap(M.inv(m), w[0] + dx, w[1] + dy); pin.set.call(pin, l[0], l[1]); app.touch();
  };
  K.dragTo = function (app, pin, tx, ty) { var m = (pin.node && app.out.M[pin.node]) || app.out.rootM; pin.m = m; var l = M.ap(M.inv(m), tx, ty); pin.set.call(pin, l[0], l[1]); app.touch(); };
  K.world = function (app, pin) { var m = (pin.node && app.out.M[pin.node]) || app.out.rootM; return M.ap(m, pin.x, pin.y); };
  K.mk = function () { var R = { n: 0, fails: [], note: [] }; R.F = function (label, ok, info) { R.n++; if (!ok) R.fails.push({ label: label, info: info }); }; return R; };
  /** A list of drawn numbers over time; true when a step is out of keeping with the rest (a jump). */
  K.jumps = function (series, k, floor) {
    var steps = [], i; for (i = 1; i < series.length; i++) steps.push(Math.hypot(series[i][0] - series[i - 1][0], series[i][1] - series[i - 1][1]));
    var mean = steps.reduce(function (s, v) { return s + v; }, 0) / Math.max(1, steps.length), worst = 0, at = -1;
    steps.forEach(function (s, j) { if (s > worst) { worst = s; at = j; } });
    return { bad: worst > (k || 4) * mean + (floor == null ? 2 : floor), worst: worst, mean: mean, at: at };
  };

  var F = T.fit = {};
  /** The parts the Fit box is tried on: whole parts, a layer inside a head angle, limbs, a mirrored part. */
  F.parts = function (app) {
    var ch = app.ch, list = [], seen = {};
    function add(n, kind) { if (!n || seen[n.id] || !AL.fit.can(app, n)) return; seen[n.id] = 1; list.push({ id: n.id, name: n.name + ' [' + kind + ']', kind: kind }); }
    AL.ZONES.forEach(function (z) { var n = app.zoneNode(z.id); if (n) add(n, 'whole ' + z.id); });
    AL.findAll(ch.root, function (n) { return n.type === 'set' && n.pick && n.pick.by === 'reach'; }).forEach(function (n) { add(n, 'limb'); });
    var head = AL.firstRole(ch.root, 'head');
    if (head) { var c = 0; AL.walk(head, function (n, par, d) { if (d >= 2 && c < 5 && (n.type === 'art' || n.type === 'group') && AL.boundsOf(n) && K.cloud(app, n)) { c++; add(n, 'head layer d' + d); } }); }
    AL.findAll(ch.root, function (n) { return n.type === 'mirror'; }).slice(0, 2).forEach(function (n) { list.push({ id: n.id, name: n.name + ' [mirror node: fit.can=' + AL.fit.can(app, n) + ']', kind: 'mirror', mirror: true }); });
    var arts = AL.findAll(ch.root, function (n) { return n.type === 'art' && AL.boundsOf(n) && K.cloud(app, n); }); [0.1, 0.5, 0.8].forEach(function (f) { add(arts[Math.floor(arts.length * f)], 'art'); });
    return list;
  };

  /** AREA 2: the box on one part, standing and in a stance. */
  F.box = async function (app, R, p, opt) {
    var fit = AL.fit, S = AL.stance, id = p.id, nm = p.name, n = AL.node(app.ch, id), plant = opt.plant || '';
    function N() { return AL.node(app.ch, id); }
    K.stand(app); await K.raf(3);
    var base = K.snap(app), json0 = JSON.stringify(app.ch);
    R.F(nm + ': picture has no bad numbers', base.bad === 0, base.bad);
    if (p.mirror) { R.F(nm + ': a mirror node is refused the box (fit.can false)', !fit.can(app, n)); return; }
    var c0 = K.cloud(app, n); if (!c0) { R.note.push(nm + ': nothing drawn (hidden), skipped'); return; }
    /* select the way the UI does: the box on the part, the pivot made for it */
    app.tab = 'parts'; app.select(AL.zoneOf(app.ch, id), null, AL.zoneOf(app.ch, id) === 'extra' || n.type === 'art' ? id : null); K.stand(app);
    fit.boxOn(app, N(), true); await K.raf(3);
    var b2 = K.snap(app), d0 = K.cmp(base, b2);
    R.F(nm + ': giving it a turning point changes nothing drawn', !d0.struct && d0.px < 0.01, d0);
    var pins = fit.pins(app, N()), mv = pins.filter(function (q) { return q.kind === 'move'; })[0], cors = pins.filter(function (q) { return q.kind === 'corner'; }), tn = pins.filter(function (q) { return q.kind === 'turn'; })[0];
    R.F(nm + ': box has move, 4 corners and a turn handle', !!mv && cors.length === 4 && !!tn, pins.map(function (q) { return q.kind; }));
    if (!mv || cors.length < 4 || !tn) return;

    /* a. move */
    app.hist.push(app.ch, 'test move'); K.drag(app, mv, 31, -17); await K.raf(3);
    var c1 = K.cloud(app, N()), sh = Math.hypot(c1.x - c0.x, c1.y - c0.y), v1 = fit.get(app, N());
    R.F(nm + ': a drag of 35 px moves it about 35 px (limbs and flips may differ)', sh > 8 && isFinite(sh), { moved: Math.round(sh), put: [v1.x, v1.y] });
    R.F(nm + ': stored move is finite', isFinite(v1.x) && isFinite(v1.y));
    /* undo / redo of that one drag */
    app.undo(false, true); await K.raf(3); var cu = K.cloud(app, N());
    R.F(nm + ': undo takes the whole drag back', Math.hypot(cu.x - c0.x, cu.y - c0.y) < 0.05, Math.hypot(cu.x - c0.x, cu.y - c0.y));
    app.undo(true, true); await K.raf(3); var cr = K.cloud(app, N());
    R.F(nm + ': redo puts it back', Math.hypot(cr.x - c1.x, cr.y - c1.y) < 0.05, Math.hypot(cr.x - c1.x, cr.y - c1.y));
    /* b. extremes, one by one from the same start */
    var ex = [['scale 0.08', { sc: 0.08 }], ['scale 8', { sc: 8 }], ['turn 720', { rot: 720 }], ['turn -360', { rot: -360 }], ['all at once', { x: 40, y: -40, rot: 720, sc: 8 }], ['tiny and turned', { x: -40, y: 40, rot: 361, sc: 0.08 }]];
    for (var i = 0; i < ex.length; i++) {
      var v = { x: 0, y: 0, rot: 0, sc: 1 }, e = ex[i][1]; for (var k in e) v[k] = e[k];
      fit.put(app, N(), v); await K.raf(3); var sn = K.snap(app), c = K.cloud(app, N());
      R.F(nm + ': ' + ex[i][0] + ' draws no bad numbers', sn.bad === 0, sn.bad);
      if (ex[i][0] === 'scale 0.08' && c) R.F(nm + ': scale 0.08 makes it small', c.w < c0.w * 0.5 + 1 && c.h < c0.h * 0.5 + 1, { w: c.w, w0: c0.w });
      if (ex[i][0] === 'scale 8' && c) R.F(nm + ': scale 8 makes it big', c.w > c0.w * 3 || c.h > c0.h * 3, { w: c.w, w0: c0.w });
      if (ex[i][0] === 'turn 720') { var dd = K.cmp(base, sn); R.F(nm + ': turn 720 looks the same as not turned', !dd.struct && dd.px < 0.6, dd); }
    }
    /* c. the corner and turn handles, with the clamps */
    var pv = N().pivot || { x: (c0.x0 + c0.x1) / 2, y: (c0.y0 + c0.y1) / 2 };
    fit.reset(app, N()); await K.raf(2);
    pins = fit.pins(app, N()); cors = pins.filter(function (q) { return q.kind === 'corner'; }); tn = pins.filter(function (q) { return q.kind === 'turn'; })[0];
    var pw = M.ap(app.out.M[id], pv.x, pv.y), cw = K.world(app, cors[0]);
    K.dragTo(app, cors[0], pw[0] + (cw[0] - pw[0]) * 100, pw[1] + (cw[1] - pw[1]) * 100); var s8 = fit.get(app, N()).sc;
    R.F(nm + ': corner dragged 100x out clamps at 8', Math.abs(s8 - 8) < 0.01, s8);
    pins = fit.pins(app, N()); cors = pins.filter(function (q) { return q.kind === 'corner'; }); cw = K.world(app, cors[0]); pw = M.ap(app.out.M[id], pv.x, pv.y);
    fit.reset(app, N()); pins = fit.pins(app, N()); cors = pins.filter(function (q) { return q.kind === 'corner'; }); cw = K.world(app, cors[0]); pw = M.ap(app.out.M[id], pv.x, pv.y);
    K.dragTo(app, cors[0], pw[0] + (cw[0] - pw[0]) * 0.001, pw[1] + (cw[1] - pw[1]) * 0.001); var s08 = fit.get(app, N()).sc;
    R.F(nm + ': corner dragged onto the middle clamps at 0.08', Math.abs(s08 - 0.08) < 0.005, s08);
    fit.reset(app, N()); pins = fit.pins(app, N()); tn = pins.filter(function (q) { return q.kind === 'turn'; })[0];
    var hw = K.world(app, tn), pw2 = M.ap(app.out.M[id], pv.x, pv.y), th = 40 * Math.PI / 180, rx = hw[0] - pw2[0], ry = hw[1] - pw2[1];
    K.dragTo(app, tn, pw2[0] + Math.cos(th) * rx - Math.sin(th) * ry, pw2[1] + Math.sin(th) * rx + Math.cos(th) * ry); var r40 = fit.get(app, N()).rot;
    R.F(nm + ': turn handle dragged 40 degrees stores about 40 (sign follows flips)', Math.abs(Math.abs(r40) - 40) < 1.5, r40);
    /* d. put it back, exactly (standing) */
    if (plant !== 'noReset') app.edit(function () { fit.reset(app, N()); }, true, 'Put it back'); await K.raf(3);
    if (plant === 'leftover') { N().dx = 3; await K.raf(3); }
    var bk = K.snap(app), dbk = K.cmp(base, bk), nn = N();
    R.F(nm + ': Put it back restores the picture exactly', !dbk.struct && dbk.px < 0.01, dbk);
    R.F(nm + ': Put it back leaves no move, turn or size on the part', !nn.dx && !nn.dy && !nn.rot && nn.sc == null, { dx: nn.dx, dy: nn.dy, rot: nn.rot, sc: nn.sc });

    /* e. the same in a stance: the part is moved for it alone and everything returns on leaving */
    K.restore(app, json0); await K.raf(3); base = K.snap(app);
    var pz; app.edit(function () { pz = S.add(app.ch, 'Test stance'); }, true, 'add stance'); var pid = pz.id;
    app.poseEdit = pid; app.poseAmt = null; app.fitOn = null; app.tab = 'parts'; app.select(AL.zoneOf(app.ch, id), null, N().type === 'art' ? id : null); app.poseEdit = pid; fit.boxOn(app, N(), true); await K.raf(3);
    var inBase = K.snap(app), cIn0 = K.cloud(app, N());
    pins = fit.pins(app, N()); mv = pins.filter(function (q) { return q.kind === 'move'; })[0]; cors = pins.filter(function (q) { return q.kind === 'corner'; }); tn = pins.filter(function (q) { return q.kind === 'turn'; })[0];
    K.drag(app, mv, 23, 19); await K.raf(3); var cIn1 = K.cloud(app, N());
    R.F(nm + ': in a stance the drag moves it', Math.hypot(cIn1.x - cIn0.x, cIn1.y - cIn0.y) > 8, Math.hypot(cIn1.x - cIn0.x, cIn1.y - cIn0.y));
    R.F(nm + ': the drag is stored on the stance, not on the part', !N().dx && !N().dy && !!(AL.stance.find(app.ch, pid).d[id]), { dx: N().dx });
    fit.put(app, N(), { x: 10, y: 10, rot: 720, sc: 8 }); await K.raf(3); R.F(nm + ': stance extreme draws no bad numbers', K.snap(app).bad === 0);
    fit.put(app, N(), { x: 10, y: 10, rot: 90, sc: 0.08 }); await K.raf(3); R.F(nm + ': stance tiny draws no bad numbers', K.snap(app).bad === 0);
    /* blends in the stance must be smooth */
    var series = []; for (var a = 0; a <= 1.0001; a += 0.025) { app.poseAmt = a; await K.raf(2); var cc = K.cloud(app, N()); series.push(cc ? [cc.x, cc.y] : [0, 0]); }
    var jp = K.jumps(series, 4, 2); if (plant === 'jump') { series[20][0] += 40; jp = K.jumps(series, 4, 2); }
    R.F(nm + ': blending 0 to 1 has no jump', !jp.bad, jp);
    var s0 = series[0], dz = Math.hypot(s0[0] - K.cloud(app, N()).x, 0);
    app.poseAmt = 0; await K.raf(3); var z0 = K.snap(app), dz0 = K.cmp(base, z0);
    R.F(nm + ': blend 0 is exactly standing', !dz0.struct && dz0.px < 0.01, dz0);
    app.poseAmt = null;
    /* Put it back in the stance, then leave */
    if (plant !== 'noReset') app.edit(function () { fit.reset(app, N()); }, true, 'Put it back'); await K.raf(3);
    var inAfter = K.snap(app), dIn = K.cmp(inBase, inAfter);
    R.F(nm + ': in a stance Put it back restores the stance picture', !dIn.struct && dIn.px < 0.01, dIn);
    R.F(nm + ': in a stance Put it back leaves no entry behind', !AL.stance.find(app.ch, pid).d[id], AL.stance.find(app.ch, pid).d[id]);
    fit.put(app, N(), { x: 12, y: -9, rot: 15, sc: 1.2 }); await K.raf(2);
    app.poseEdit = null; app.fitOn = null; app.refreshPanel(); await K.raf(3);
    if (plant === 'leak') { N().dx = 2; await K.raf(3); }
    var out = K.snap(app), dOut = K.cmp(base, out);
    R.F(nm + ': leaving the stance gives back exactly the standing picture', !dOut.struct && dOut.px < 0.01, dOut);
    R.F(nm + ': standing part has no move written on it by the stance', !N().dx && !N().dy && !N().rot && N().sc == null, { dx: N().dx, dy: N().dy, rot: N().rot, sc: N().sc });
    /* the stance in Motion, button on then off */
    AL.stages.go(app, 'motion'); await K.wait(300); var m0 = K.cloud(app, N());
    app.inp.setTrig(AL.stance.find(app.ch, pid).trig, true); await K.wait(1600); var m1 = K.cloud(app, N());
    app.inp.setTrig(AL.stance.find(app.ch, pid).trig, false); await K.wait(1800); var m2 = K.cloud(app, N());
    R.F(nm + ': Motion, stance on moves it', Math.hypot(m1.x - m0.x, m1.y - m0.y) > 5, Math.hypot(m1.x - m0.x, m1.y - m0.y));
    R.F(nm + ': Motion, stance off brings it back (idle sway allowed 6 px)', Math.hypot(m2.x - m0.x, m2.y - m0.y) < 6, Math.hypot(m2.x - m0.x, m2.y - m0.y));
    AL.stages.go(app, 'fit'); K.restore(app, json0); await K.raf(2);
  };

  /** AREA 1: a stance made by hand with several parts moved at once; standing untouched; smooth blends; stage switching. */
  F.stance = async function (app, R, opt) {
    var fit = AL.fit, S = AL.stance, plant = opt.plant || '';
    K.stand(app); await K.raf(3); var json0 = JSON.stringify(app.ch), base = K.snap(app);
    var ps = F.parts(app).filter(function (q) { return !q.mirror && /whole|limb/.test(q.kind); });
    var pz; app.edit(function () { pz = S.add(app.ch, 'Hand made'); }, true, 'add'); var pid = pz.id;
    app.poseEdit = pid; app.poseAmt = null; await K.raf(2);
    var moved = [];
    ps.forEach(function (q, i) { var n = AL.node(app.ch, q.id); if (!n || !AL.fit.can(app, n)) return; fit.needPivot(app, n); fit.put(app, n, { x: (i % 3 - 1) * 24, y: 18 + i * 3, rot: (i % 2 ? 1 : -1) * (12 + i * 5), sc: 1 + (i % 4 - 1) * 0.15 }); moved.push(q.id); });
    await K.raf(3); var inS = K.snap(app), dIn = K.cmp(base, inS);
    R.F('stance: ' + moved.length + ' parts moved for it show on the picture', dIn.px > 8 && inS.bad === 0, dIn);
    /* a series of the blend, per part */
    var series = {}, amts = []; for (var a = 0; a <= 1.0001; a += 0.02) amts.push(a);
    for (var i = 0; i < amts.length; i++) { app.poseAmt = amts[i]; await K.raf(2); moved.forEach(function (id) { var c = K.cloud(app, AL.node(app.ch, id)); (series[id] = series[id] || []).push(c ? [c.x, c.y] : [0, 0]); }); }
    moved.forEach(function (id) { var nn = AL.node(app.ch, id), j = K.jumps(series[id], 4, 2.5); if (plant === 'jump') { series[id][25][1] += 35; j = K.jumps(series[id], 4, 2.5); } R.F('stance blend: ' + nn.name + ' moves smoothly 0 to 1', !j.bad, j); });
    /* in reverse: no hysteresis (same place at the same amount) */
    app.poseAmt = 0.5; await K.raf(3); var up = K.snap(app); app.poseAmt = 1; await K.raf(2); app.poseAmt = 0.5; await K.raf(3); var down = K.snap(app), dh = K.cmp(up, down);
    R.F('stance blend: the same amount gives the same picture coming and going', !dh.struct && dh.px < 0.01, dh);
    /* leave: standing exactly */
    app.poseAmt = null; app.poseEdit = null; app.refreshPanel(); await K.raf(3); var out = K.snap(app), dOut = K.cmp(base, out);
    R.F('stance: leaving it gives back exactly the standing picture', !dOut.struct && dOut.px < 0.01 && dOut.da < 0.01, dOut);
    var leaked = []; AL.walk(app.ch.root, function (k) { if (k.dx || k.dy || k.rot || (k.sc != null && k.sc !== 1)) leaked.push(k.name); }); var orig = []; AL.walk(JSON.parse(json0).root, function (k) { if (k.dx || k.dy || k.rot || (k.sc != null && k.sc !== 1)) orig.push(k.name); });
    R.F('stance: nothing written onto the standing parts', leaked.length === orig.length, { leaked: leaked, orig: orig });
    /* undo the whole building of the stance by pressing undo (edits only) */
    /* stage dance with the stance up */
    app.poseEdit = pid; app.poseAmt = null; await K.raf(3); var inAgain = K.snap(app);
    R.F('stance: coming back to it draws it the same', K.cmp(inS, inAgain).px < 0.01, K.cmp(inS, inAgain));
    var route = ['setup', 'fit', 'motion', 'fit', 'perform', 'fit', 'setup', 'motion', 'setup', 'fit'];
    for (var r = 0; r < route.length; r++) {
      var was = app.poseEdit; AL.stages.go(app, route[r]); await K.raf(3); if (route[r] === 'motion' || route[r] === 'perform') await K.wait(300);
      if (route[r] === 'fit' && /^(setup|perform)$/.test(route[Math.max(0, r - 1)])) { /* fit entered from setup or perform: no stance */ R.F('stage ' + route[r - 1] + ' to fit: no stance left on', !app.poseEdit, app.poseEdit); }
      R.F('stage ' + route[r] + ': no bad numbers', K.snap(app).bad === 0);
    }
    AL.stages.go(app, 'fit'); K.stand(app); app.refreshPanel(); await K.raf(3);
    var fin = K.snap(app), dFin = K.cmp(base, fin);
    R.F('stance: after the stage dance, standing is exactly as drawn', !dFin.struct && dFin.px < 0.01, dFin);
    /* removing the stance */
    app.edit(function () { S.remove(app.ch, S.find(app.ch, pid)); }, true, 'rm'); await K.raf(3); var rm = K.snap(app), dRm = K.cmp(base, rm);
    R.F('stance: removed, the picture is exactly standing', !dRm.struct && dRm.px < 0.01, dRm);
    K.restore(app, json0); await K.raf(2);
  };

  F.run = async function (opt) {
    opt = opt || {}; var app = AL.app, R = K.mk(), keep = JSON.stringify(app.ch), only = opt.only || ['stance', 'box', 'reshape'];
    try {
      if (only.indexOf('stance') >= 0) await F.stance(app, R, opt);
      if (only.indexOf('box') >= 0) { var list = F.parts(app); if (opt.limit) list = list.slice(0, opt.limit); for (var i = 0; i < list.length; i++) await F.box(app, R, list[i], opt); }
      if (only.indexOf('reshape') >= 0) await F.reshape(app, R, opt);
      if (only.indexOf('reshape') >= 0 && F.reshapeWhole) await F.reshapeWhole(app, R);
    } catch (e) { R.fails.push({ label: 'THROWN', info: String(e && e.stack || e).slice(0, 500) }); }
    K.restore(app, keep); await K.raf(2);
    return R;
  };
  /** Plant faults and say whether the checks caught them. */
  F.proof = async function () {
    var out = {}, plants = [['noReset', ['box']], ['leftover', ['box']], ['leak', ['box']], ['jump', ['stance', 'box']]];
    for (var i = 0; i < plants.length; i++) { var r = await F.run({ plant: plants[i][0], only: plants[i][1], limit: 2 }); out[plants[i][0]] = { caught: r.fails.length > 0, labels: r.fails.map(function (f) { return f.label; }).slice(0, 4) }; }
    return out;
  };
})(typeof self !== 'undefined' ? self : this);
