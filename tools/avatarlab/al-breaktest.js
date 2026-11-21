/* AvatarLab: BREAK TEST. Not part of the page; load it by hand in the pilot (a script tag, or paste) and call AL.breakTest.run().
 *
 * The owner's rule (2026-11-21): a body must never come apart because one setting was changed. This measures exactly that.
 * For every case it draws the character and takes, for each limb, the head and each tail, the GAP: how far the nearest drawn point of
 * that part is from the drawn torso (0 when they touch or overlap). A part whose gap is more than SLACK px bigger than its gap when
 * standing has come off the body, and the case fails. It also fails on a number that is not a number, and on a thrown error.
 *
 * Cases: every tip, every hip drop, the ground moved, blends half way into the stance, hand placements made at one tip and the tip then
 * changed (the fault of 2026-11-21), a body turned by hand when standing, undo and redo across a change, the stance removed and made
 * again, and the same alive in Motion with the stance's button on and off.
 * Use a character that HAS hand placements (the owner's saved one), not only the clean demo.
 */
(function (root) {
  'use strict';
  var AL = root.AL, T = AL.breakTest = {}, SLACK = 14;
  function raf(n) { var kit = AL.breakTest && AL.breakTest.kit; if (kit && kit._sim) return kit.step(n || 2);      // (simulated time, when al-breaktest-fit.js has it on: the pane draws a frame or two a second when not watched)
    return new Promise(function (r) { var k = n || 2; (function f() { if (k-- <= 0) return r(); requestAnimationFrame(f); })(); }); }
  function wait(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function parts(app) {
    var ch = app.ch, list = [], torso = AL.firstRole(ch.root, 'torso') || AL.firstRole(ch.root, 'body');
    AL.walk(ch.root, function (n) { if (n.type === 'set' && n.pick && n.pick.by === 'reach' && n.pick.hand) list.push({ name: n.name || n.role, node: n }); });
    var hd = AL.firstRole(ch.root, 'head'); if (hd) list.push({ name: 'Head', node: hd });
    AL.byRole(ch.root, 'tail').forEach(function (t) { list.push({ name: t.name || 'Tail', node: t }); });
    return { torso: torso, list: list };
  }
  function cloud(app, n, skip) {
    var ids = {}, pts = [];
    AL.walk(n, function (k) { if (k.type === 'art') ids[k.id] = 1; });
    if (skip) skip.forEach(function (s) { AL.walk(s, function (k) { delete ids[k.id]; }); });
    app.out.items.forEach(function (it) { if (it.ghost || !ids[it.id]) return; for (var i = 0; i < it.v.length; i += 2) pts.push([it.v[i], it.v[i + 1]]); });
    return pts;
  }
  /** Gaps now: { part name: px from the torso's drawn box } and a flag for a bad number. */
  T.measure = function (app) {
    var P = parts(app), out = { gaps: {}, bad: false }; if (!P.torso || !app.out) return out;
    var tp = cloud(app, P.torso, P.list.map(function (q) { return q.node; })), b = null;
    tp.forEach(function (p) { if (!(p[0] === p[0] && p[1] === p[1])) out.bad = true; if (!b) b = { x0: p[0], y0: p[1], x1: p[0], y1: p[1] }; else { b.x0 = Math.min(b.x0, p[0]); b.x1 = Math.max(b.x1, p[0]); b.y0 = Math.min(b.y0, p[1]); b.y1 = Math.max(b.y1, p[1]); } });
    if (!b) return out;
    P.list.forEach(function (q) {
      var pts = cloud(app, q.node), best = 1e9;
      pts.forEach(function (p) { if (!(p[0] === p[0] && p[1] === p[1])) { out.bad = true; return; } var dx = Math.max(b.x0 - p[0], 0, p[0] - b.x1), dy = Math.max(b.y0 - p[1], 0, p[1] - b.y1), d = Math.sqrt(dx * dx + dy * dy); if (d < best) best = d; });
      out.gaps[q.name] = pts.length ? Math.round(best) : -1;                // -1: nothing of it is drawn (hidden)
    });
    return out;
  };
  /** Each part's joint on the picture and its turn: { name: { x, y, deg } }. A limb's joint is its shoulder or hip pin. */
  T.joints = function (app) {
    var P = parts(app), M = AL.M, out = {};
    P.list.forEach(function (q) {
      var n = q.node, m = app.out.M[n.id]; if (!m) return;
      var rest = n.pick ? (AL.node(app.ch, n.pick.rest) || (n.kids || []).filter(function (k) { return k.limb; })[0]) : null, b = AL.boundsOf(n);
      var a = rest && rest.limb ? rest.limb.a : (n.pivot || (b ? { x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 } : { x: 0, y: 0 })), w = M.ap(m, a.x, a.y);
      out[q.name] = { x: w[0], y: w[1], deg: Math.atan2(m[1], m[0]) * 180 / Math.PI, ride: !!(n.pick && n.pick.hand && !/^leg/.test(n.role || '') && n.pick.hand.charAt(0) !== 'F') };
    });
    return out;
  };
  function dAng(a, b) { var d = a - b; while (d > 180) d -= 360; while (d < -180) d += 360; return Math.abs(d); }
  T.run = async function (opt) {
    opt = opt || {};
    var app = AL.app, S = AL.stance, fails = [], n = 0, snap0 = JSON.stringify(AL.toJson ? null : null);
    var keepCh = JSON.stringify({ poses: app.ch.poses, walk: app.ch.settings.walk, triggers: app.ch.triggers });
    function restore() { var k = JSON.parse(keepCh); app.ch.poses = k.poses; app.ch.settings.walk = k.walk; app.ch.triggers = k.triggers; AL.reindex(app.ch); }
    function fours() { return S.fours(app.ch); }
    async function still(poseId, amt) { if (app.live) AL.stages.go(app, 'fit'); app.held = null; app.poseEdit = poseId || null; app.poseAmt = amt == null ? null : amt; app.shapeEdit = null; await raf(3); }
    var base = null;
    async function check(label) {
      n++; var m;
      try { await raf(2); m = T.measure(app); } catch (e) { fails.push({ label: label, error: String(e && e.message || e) }); return; }
      if (m.bad) { fails.push({ label: label, error: 'a number that is not a number' }); return; }
      var worst = null;
      for (var k in m.gaps) { var g = m.gaps[k], b0 = base && base[k] != null ? Math.max(0, base[k]) : 0; if (g < 0) continue; if (g - b0 > SLACK && (!worst || g - b0 > worst.by)) worst = { part: k, by: g - b0 }; }
      if (worst) fails.push({ label: label, part: worst.part, gap: worst.by });
    }
    function rnd(seed) { var s = seed; return function () { s = (s * 16807) % 2147483647; return s / 2147483647; }; }

    try {
      AL.stages.go(app, 'fit'); app.tab = 'parts'; app.select('body');
      await still(null); base = T.measure(app).gaps;
      if (!fours() && S.parts(app.ch)) { S.makeFours(app.ch); keepCh = JSON.stringify({ poses: app.ch.poses, walk: app.ch.settings.walk, triggers: app.ch.triggers }); }
      var p = fours(); if (!p) return { cases: 0, fails: [], base: base, note: 'no all fours is worked out for this character (drawn facing straight out, or no arms and legs that bend)' };
      var a0 = p.fours.angle, t, d;

      /* 1. every tip, as saved */
      for (t = 30; t <= 125; t += 5) { S.makeFours(app.ch, t); await still(p.id); await check('tip ' + t); }
      restore(); p = fours();
      /* 2. every hip drop at three tips */
      for (var ti = 0; ti < 3; ti++) { t = [45, 80, a0][ti]; S.makeFours(app.ch, t); p = fours(); for (d = 0; d <= 80; d += 20) { p.fours.drop = d; await still(p.id); await check('tip ' + t + ', hips down ' + d); } }
      restore(); p = fours();
      /* 3. half way in */
      for (var am = 0; am <= 1.001; am += 0.25) { await still(p.id, am); await check('blend ' + am); }
      /* 4. the ground moved, then the tip changed */
      var wk = app.ch.settings.walk || {}; if (wk.groundY != null) { var g0 = wk.groundY; for (var gi = 0; gi < 4; gi++) { wk.groundY = g0 + [-60, -25, 25, 60][gi]; for (t = 40; t <= 120; t += 40) { S.makeFours(app.ch, t); await still(fours().id); await check('ground ' + (wk.groundY - g0) + ', tip ' + t); } } }
      restore(); p = fours();
      /* 5. hand placements made at one tip, the tip then changed (seeded, so a failure can be run again) */
      var R = rnd(opt.seed || 20261121), P = parts(app);
      for (var round = 0; round < (opt.rounds || 8); round++) {
        restore(); p = fours(); var at = 40 + Math.floor(R() * 80); S.makeFours(app.ch, at); p = fours();
        P.list.forEach(function (q) {                                      // a small, sane hand move on every part: it stays attached at the tip it was made at
          var e = p.d[q.node.id]; if (!e) return; if (AL.fit && AL.fit.needPivot) AL.fit.needPivot(app, q.node);      // (as the box does before it turns a part)
          e.x = (e.x || 0) + (R() - 0.5) * 16; e.y = (e.y || 0) + (R() - 0.5) * 16;
          var isArm = q.node.type === 'set' && q.node.pick && q.node.pick.hand && !/^leg/.test(q.node.role || '') && q.node.pick.hand.charAt(0) !== 'F' && !e.orbit;
          var sgn = (p.d[P.torso.id] && p.d[P.torso.id].autoRot) || 0;
          e.rot = (e.rot || 0) + (isArm ? -sgn * (R() * 0.9 + 0.1) : (R() - 0.5) * 12);      // an arm that rides on the torso, turned back toward hanging down (the owner's own fit)
        });
        /* THE RULE: a hand placement stays where it was put on the picture. Measured as each part's joint against the same joint with
           every hand move forgotten (the worked-out place at that tip): that difference, and an arm's own turn, must not change with the tip. */
        var dev = async function () {
          await still(fours().id); var J = T.joints(app), keepD = JSON.stringify(fours().d);
          S.clearHand(fours()); await still(fours().id); var J0 = T.joints(app); fours().d = JSON.parse(keepD); await still(fours().id);
          var o = {}; for (var k in J) if (J0[k]) o[k] = { dx: J[k].x - J0[k].x, dy: J[k].y - J0[k].y, deg: J[k].deg, rel: J[k].deg - J0[k].deg, ride: J[k].ride, x: J[k].x, y: J[k].y };
          return o;
        };
        var A = await dev(); await check('hand moves made at tip ' + at);
        var same = function (B, label, exact) {
          n++; var worst = null;
          for (var k in A) { if (!B[k]) continue; var dp = Math.hypot(B[k].dx - A[k].dx, B[k].dy - A[k].dy), da = A[k].ride ? dAng(B[k].deg, A[k].deg) : dAng(B[k].rel, A[k].rel);
            if (exact) { dp = Math.hypot(B[k].x - A[k].x, B[k].y - A[k].y); da = dAng(B[k].deg, A[k].deg); }
            if ((dp > 8 || da > 6) && (!worst || dp + da > worst.moved + worst.turned)) worst = { part: k, moved: Math.round(dp), turned: Math.round(da) }; }
          if (worst) fails.push({ label: label, part: worst.part, moved: worst.moved, turned: worst.turned });
        };
        for (t = 30; t <= 125; t += 19) { if (opt.oldWay) fours().fours.angle = t; S.makeFours(app.ch, t); var B = await dev(); same(B, 'hand moves made at tip ' + at + ', tip then ' + t); await check('hand moves made at tip ' + at + ', tip then ' + t + ' (comes off)'); }
        if (opt.oldWay) fours().fours.angle = at; S.makeFours(app.ch, at); same(await dev(), 'hand moves made at tip ' + at + ', tip changed and brought back', true);
      }
      restore(); p = fours();
      /* 6. the torso turned and moved by hand when standing */
      var torso = P.torso, r0 = torso.rot, x0 = torso.dx;
      for (var k2 = 0; k2 < 2; k2++) { torso.rot = [12, -15][k2]; await still(null); var b1 = base; base = T.measure(app).gaps; for (t = 45; t <= 105; t += 30) { S.makeFours(app.ch, t); await still(fours().id); await check('body turned ' + torso.rot + ' when standing, tip ' + t); } base = b1; }
      if (r0 == null) delete torso.rot; else torso.rot = r0; if (x0 == null) delete torso.dx; else torso.dx = x0;
      restore(); p = fours();
      /* 7. undo and redo across a tip change */
      app.edit(function () { S.makeFours(app.ch, 50); }, true, 'test'); await still(fours().id); await check('tip 50 by an edit');
      app.undo(false, true); await still(fours() && fours().id); await check('after undo'); app.undo(true, true); await still(fours() && fours().id); await check('after redo'); app.undo(false, true);
      restore(); p = fours();
      /* 8. removed and made again; Work it out again */
      S.remove(app.ch, p); S.makeFours(app.ch); p = fours(); await still(p.id); await check('stance removed and made again');
      restore(); p = fours(); S.clearHand(p); await still(p.id); await check('Work it out again');
      restore(); p = fours();
      /* 9. alive: Motion, the button on, off, on */
      if (!opt.noLive) {
        for (t = 0; t < 3; t++) { var tip = [45, 80, a0][t]; restore(); S.makeFours(app.ch, tip); p = fours(); app.poseEdit = null; AL.stages.go(app, 'motion'); await wait(250);
          app.inp.setTrig(p.trig, true); await wait(1700); await check('Motion, All fours on, tip ' + tip);
          app.inp.setTrig(p.trig, false); await wait(1500); await check('Motion, stood up again, tip ' + tip);
          app.inp.setTrig(p.trig, true); await wait(500); await check('Motion, half way down, tip ' + tip); app.inp.setTrig(p.trig, false); await wait(300); }
        AL.stages.go(app, 'fit');
      }
    } catch (e) { fails.push({ label: 'the test itself stopped', error: String(e && e.stack || e).slice(0, 300) }); }
    restore(); app.poseEdit = null; app.poseAmt = null; app.refreshPanel(); await raf(2);
    return { cases: n, fails: fails, base: base };
  };
})(typeof self !== 'undefined' ? self : this);
