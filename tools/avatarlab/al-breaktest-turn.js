/* AvatarLab: BREAK TEST, area 4: head turning. Load after al-breaktest-fit.js (it uses AL.breakTest.kit).
 *
 * Still stages: app.bt.x / app.bt.y drive every turning set over the whole pad; Motion: inp.pose.turnX / turnY (and flip, a stance button,
 * the mouth, a blink, a mood). Each sample checks:
 *   - no number that is not a number
 *   - the rest of the body does not move because the head turned (torso, legs, arms matrices as they were standing)
 *   - the face parts (eyes, mouth, nose, brows, pupils) have their drawn centre inside the head's own drawn box
 *   - the picked drawing does not flicker back and forth along a straight sweep of the pad (A, B, A)
 *   - a straight sweep of the pad has no jump in the head's drawn box bigger than JUMP px between neighbouring samples (angle swaps are
 *     counted separately and reported, since a swap in a still stage is the design)
 */
(function (root) {
  'use strict';
  var AL = root.AL, T = AL.breakTest, K = T.kit, Tn = T.turn = {}, JUMP = 30;
  function headBox(app, head) {
    var ids = {}; AL.walk(head, function (k) { if (k.type === 'art') ids[k.id] = 1; });
    var feat = {}; AL.walk(head, function (k) { if (k.role && /^(eye|pupil|mouth|nose|brow|lid|jaw)/i.test(k.role) || /(pupil|eyelid|lid|brow|mouth|eye)/i.test(k.name || '')) AL.walk(k, function (q) { feat[q.id] = 1; }); });
    var b = null; app.out.items.forEach(function (it) { if (it.ghost || !ids[it.id] || feat[it.id]) return; for (var i = 0; i < it.v.length; i += 2) { var x = it.v[i], y = it.v[i + 1]; if (!b) b = { x0: x, y0: y, x1: x, y1: y }; else { if (x < b.x0) b.x0 = x; if (x > b.x1) b.x1 = x; if (y < b.y0) b.y0 = y; if (y > b.y1) b.y1 = y; } } });
    return b;
  }
  /** Face parts drawn right now whose centre is outside the head's drawn box (+ margin). */
  Tn.strays = function (app, head, margin) {
    var b = headBox(app, head); if (!b) return []; var out = [], m = margin == null ? 12 : margin;
    AL.findAll(head, function (k) { return k.type === 'group' || k.type === 'art'; }).forEach(function (k) {
      if (!(k.role && /^(eyeL|eyeR|mouth|nose|pupil)/.test(k.role))) return;
      var arts = AL.findAll(k, function (q) { return q.type === 'art'; }), ids = {}; arts.forEach(function (a) { ids[a.id] = 1; });
      var sx = 0, sy = 0, c = 0; app.out.items.forEach(function (it) { if (it.ghost || !ids[it.id]) return; for (var i = 0; i < it.v.length; i += 2) { sx += it.v[i]; sy += it.v[i + 1]; c++; } });
      if (!c) return; var x = sx / c, y = sy / c;
      if (x < b.x0 - m || x > b.x1 + m || y < b.y0 - m || y > b.y1 + m) out.push({ part: k.name || k.role, at: [Math.round(x), Math.round(y)], box: [Math.round(b.x0), Math.round(b.y0), Math.round(b.x1), Math.round(b.y1)] });
    });
    return out;
  };
  Tn.turnSets = function (ch) { return AL.findAll(ch.root, function (n) { return n.type === 'set' && n.pick && n.pick.by === 'turn'; }); };

  /** Still stages: a grid over the pad, for every turning set, with the face busy in different ways. */
  Tn.grid = async function (app, R, opt) {
    opt = opt || {}; var plant = opt.plant || '';
    K.stand(app); await K.raf(3); var base = K.snap(app), sets = Tn.turnSets(app.ch), steps = opt.steps || 21;
    var faces = [['plain', {}], ['mouth open', { mouth: 1 }], ['blink', { blink: 1 }], ['mood angry', { lidUp: 0.3, lidTilt: 1, lidLow: 0.18, brow: -0.9 }], ['mouth open + blink + cheery', { mouth: 0.6, blink: 1, lidLow: 0.8, brow: 0.45 }]];
    if (plant === 'stray') AL.byRole(app.ch.root, 'mouth').forEach(function (mo) { mo.dx = 600; });
    if (plant === 'nan') { var mo2 = AL.firstRole(app.ch.root, 'mouth'); if (mo2) mo2.sc = NaN; }
    for (var si = 0; si < sets.length; si++) {
      var set = sets[si], nm = (set.name || set.role) + ': ';
      for (var fi = 0; fi < faces.length; fi++) {
        if (opt.facesOnly && opt.facesOnly.indexOf(fi) < 0) continue;
        var f = faces[fi][1], tag = nm + faces[fi][0] + ': '; app.test.mouth = f.mouth == null ? null : f.mouth; app.test.blink = f.blink || 0; app.test.lidUp = f.lidUp || 0; app.test.lidLow = f.lidLow || 0; app.test.lidTilt = f.lidTilt || 0; app.test.brow = f.brow || 0;
        var bad = 0, strays = [], moved = [], flick = 0, jumps = [], swaps = 0, nsamp = 0;
        var ny = fi === 0 ? 9 : 3, ys = []; for (var yy = 0; yy < ny; yy++) ys.push(-1 + 2 * yy / (ny - 1));
        for (var yi = 0; yi < ys.length; yi++) {
          var seq = [], prev = null, prevPick = null, picks = [];
          for (var xi = 0; xi < steps; xi++) {
            var x = -1 + 2 * xi / (steps - 1); app.bt.x = x; app.bt.y = ys[yi]; if (set.pick.dial) { app.btd = {}; app.btd[set.id] = { x: x, y: ys[yi] }; }
            await K.raf(2); nsamp++; var s = K.snap(app); if (s.bad) bad++;
            var st = Tn.strays(app, AL.node(app.ch, set.id)); if (st.length) strays.push({ x: x, y: ys[yi], s: st[0] });
            for (var mk in base.M) { var tn = AL.node(app.ch, mk); if (!tn || AL.ancestorWith(app.ch, mk, function (a) { return a === set; })) continue; if (s.M[mk]) for (var q = 0; q < 6; q++) if (Math.abs(s.M[mk][q] - base.M[mk][q]) > 0.02) { moved.push({ node: tn.name, x: x, y: ys[yi] }); break; } }
            var pk = app.out.picks[set.id]; if (prevPick !== null && pk !== prevPick) swaps++; picks.push(pk); prevPick = pk;
            var hb = K.cloud(app, AL.node(app.ch, set.id)); if (hb && prev) { var dj = Math.max(Math.abs(hb.x0 - prev.x0), Math.abs(hb.x1 - prev.x1), Math.abs(hb.y0 - prev.y0), Math.abs(hb.y1 - prev.y1)); if (dj > JUMP) jumps.push({ x: Math.round(x * 100) / 100, y: ys[yi], px: Math.round(dj), swap: picks.length > 1 && picks[picks.length - 1] !== picks[picks.length - 2] }); } prev = hb;
          }
          var seen = {}, order = []; picks.forEach(function (p) { if (order[order.length - 1] !== p) order.push(p); }); order.forEach(function (p) { if (seen[p]) flick++; seen[p] = 1; });
        }
        R.F(tag + nsamp + ' pad positions draw no bad numbers', bad === 0, bad);
        R.F(tag + 'face parts stay inside the head', strays.length === 0, { n: strays.length, first: strays[0] });
        R.F(tag + 'the rest of the body does not move when the head turns', moved.length === 0, { n: moved.length, first: moved[0] });
        R.F(tag + 'the picked drawing does not flicker along a sweep (A, B, A)', flick === 0, flick);
        var hard = jumps.filter(function (j) { return !j.swap; });
        R.F(tag + 'no jump over ' + JUMP + ' px in the head box between neighbouring pad positions, apart from a drawing swap', hard.length === 0, { n: hard.length, first: hard[0], swapJumps: jumps.length - hard.length, swaps: swaps });
      }
    }
    app.test.mouth = null; app.test.blink = 0; app.test.lidUp = app.test.lidLow = app.test.lidTilt = app.test.brow = 0; app.bt.x = app.bt.y = 0; app.btd = {};
  };

  /** Motion: turn on the live pad, flipped or not, with a stance button on or off. */
  Tn.live = async function (app, R, opt) {
    opt = opt || {}; var set = app.ch.settings, inp = app.inp, head = AL.firstRole(app.ch.root, 'head'), fp = AL.stance.fours(app.ch), plant = opt.plant || '';
    set.idle = false; set.blinkAuto = false; var combos = [];
    [false, true].forEach(function (flip) { [false, true].forEach(function (st) { if (st && !fp) return; combos.push({ flip: flip, st: st }); }); });
    var pts = [-1, -0.5, 0, 0.5, 1];
    for (var ci = 0; ci < combos.length; ci++) {
      var c = combos[ci], tag = 'Motion, ' + (c.flip ? 'flipped' : 'not flipped') + ', ' + (c.st ? (fp ? fp.name : 'stance') + ' on' : 'standing') + ': ';
      AL.stages.go(app, 'motion'); await K.wait(250); inp.auto = false; inp.held.head = true; inp.walker.home();
      if (fp) inp.setTrig(fp.trig, c.st); inp.pose.flip = c.flip; await K.wait(c.st ? 1700 : 400);
      var bad = 0, strays = [], n = 0, gapBad = [], dmax = 0, prev = null;
      for (var yi = 0; yi < pts.length; yi++) for (var xi = 0; xi < pts.length; xi++) {
        inp.pose.turnX = pts[xi]; inp.pose.turnY = pts[yi]; inp.pose.mouth = (xi + yi) % 2 ? 1 : 0; if ((xi * yi) % 3 === 1) inp.blinkNow(); await K.wait(480); n++;
        var s = K.snap(app); if (s.bad) bad++; var st = Tn.strays(app, head, 25); if (st.length) strays.push({ x: pts[xi], y: pts[yi], s: st[0] });
      }
      inp.pose.mouth = 0; inp.pose.turnX = inp.pose.turnY = 0;
      R.F(tag + n + ' pad positions draw no bad numbers', bad === 0, bad);
      R.F(tag + 'face parts stay inside the head', strays.length === 0, { n: strays.length, first: strays[0] });
      if (fp) inp.setTrig(fp.trig, false); inp.pose.flip = false; await K.wait(200);
    }
    AL.stages.go(app, 'fit');
  };

  Tn.run = async function (opt) {
    opt = opt || {}; var app = AL.app, R = Tn.live2 = K.mk(), keep = JSON.stringify(app.ch);
    try { if (!opt.only || opt.only.indexOf('grid') >= 0) await Tn.grid(app, R, opt); if (!opt.only || opt.only.indexOf('live') >= 0) await Tn.live(app, R, opt); } catch (e) { R.fails.push({ label: 'THROWN', info: String(e && e.stack || e).slice(0, 500) }); }
    K.restore(app, keep); await K.raf(2); return R;
  };
})(typeof self !== 'undefined' ? self : this);
