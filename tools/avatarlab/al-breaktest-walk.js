/* AvatarLab: BREAK TEST, area 6: walking in Motion. Load after al-breaktest.js and al-breaktest-fit.js.
 *
 * Drives the walker exactly as a finger on the body does (walker.grab / drag / release), a frame at a time, and every frame checks:
 *   - no number that is not a number anywhere in the drawn picture or the walker
 *   - no limb or head further from the torso than it stands (AL.breakTest.measure, the same gap as the all-fours test), with the slack
 *     the swing of a walk needs (WALK_SLACK)
 *   - the body has not flown off (the walker's x and y stay inside the stage plus one body)
 * and, after the finger lets go, that it settles: the root stops moving and stands back on the ground line.
 */
(function (root) {
  'use strict';
  var AL = root.AL, T = AL.breakTest, K = T.kit, W = T.walk = {}, WALK_SLACK = 40, JOINT_SLACK = 20;
  /** How far each limb's root joint (shoulder or hip pin) has come away from where the torso's own drawing puts that same point. 0 when it stays on the body. */
  W.jointGaps = function (app) {
    var ch = app.ch, M = AL.M, torso = AL.firstRole(ch.root, 'torso') || AL.firstRole(ch.root, 'body'), out = {}; if (!torso || !app.out) return out;
    var mt = app.out.M[torso.id];
    AL.findAll(ch.root, function (n) { return n.type === 'set' && n.pick && n.pick.by === 'reach' && n.pick.hand; }).forEach(function (n) {
      var rest = AL.node(ch, n.pick.rest) || (n.kids || []).filter(function (k) { return k.limb; })[0], mn = app.out.M[n.id]; if (!rest || !rest.limb || !mn || !mt) return;
      var a = rest.limb.a, J = M.ap(mn, a.x, a.y), B = M.ap(mt, a.x, a.y); out[n.name || n.role] = Math.hypot(J[0] - B[0], J[1] - B[1]);
    });
    return out;
  };
  /** The same gaps with the all-fours stance fully on, as Fit draws it (the stance moves limbs on purpose). */
  W.stanceJoints = function (app) {
    var fp = AL.stance.fours(app.ch); if (!fp) return null; var was = [app.poseEdit, app.poseAmt]; app.poseEdit = fp.id; app.poseAmt = 1;
    return new Promise(function (res) { K.raf(4).then(function () { var g = W.jointGaps(app); app.poseEdit = was[0]; app.poseAmt = was[1]; res(g); }); });
  };
  function rms(a) { var s = 0; a.forEach(function (v) { s += v * v; }); return Math.sqrt(s / Math.max(1, a.length)); }

  /** One scenario: a list of [seconds, dx, dy] legs of a drag (character-space offsets from where the finger went down). */
  W.scenario = async function (app, R, name, legs, opt) {
    opt = opt || {}; var wk = app.inp.walker, base = opt.base, worst = { gap: 0, part: '', t: 0 }, worstJ = { gap: 0, part: '', t: 0 }, bad = 0, off = 0, maxR = 0, frames = 0, t = 0, trace = [];
    var fp = AL.stance.fours(app.ch); AL.stages.go(app, 'motion'); await K.wait(250); app.inp.held.head = true; (app.ch.poses || []).forEach(function (p) { if (p.trig) app.inp.setTrig(p.trig, false); }); wk.home(); wk.x = wk.y = 0; wk.air = false; wk.fallV = 0; wk.low = 0; await K.wait(1300);
    wk.grab([0, 0]); var dx0 = 0, dy0 = 0, held = true;
    function sample() {
      frames++; var m = T.measure(app), s = K.snap(app); if (m.bad || s.bad) bad++;
      if (!isFinite(wk.x) || !isFinite(wk.y) || !isFinite(app.out.rootM[4]) || !isFinite(app.out.rootM[5])) bad++;
      if (Math.abs(wk.x) > app.ch.w * 2.5 || Math.abs(wk.y) > app.ch.h * 2.5) off++;
      var jg = W.jointGaps(app); for (var jk in jg) { var jb0 = opt.jbase && opt.jbase[jk] || 0, jb1 = opt.jbaseS && opt.jbaseS[jk] != null ? opt.jbaseS[jk] : jb0, lv = fp && app.inp.poseLvl ? Math.max(0, Math.min(1, app.inp.poseLvl[fp.id] || 0)) : 0, jb = jb0 + (jb1 - jb0) * lv; if (jg[jk] - jb > worstJ.gap) worstJ = { gap: jg[jk] - jb, part: jk, t: t }; }
      for (var k in m.gaps) { var g = m.gaps[k], b0 = base && base[k] != null ? Math.max(0, base[k]) : 0; if (g >= 0 && g - b0 > worst.gap) worst = { gap: g - b0, part: k, t: t }; }
      trace.push([wk.x, wk.y]);
    }
    for (var i = 0; i < legs.length; i++) {
      var L = legs[i], steps = Math.max(1, Math.round(L[0] * 30));
      for (var s = 1; s <= steps; s++) { wk.drag([dx0 + (L[1] - dx0) * s / steps, dy0 + (L[2] - dy0) * s / steps]); await K.raf(2); t += 1 / 30; sample(); }
      dx0 = L[1]; dy0 = L[2];
    }
    wk.release(); held = false; if (opt.plant === 'leg') { var lg = AL.firstRole(app.ch.root, 'legR'); lg.dx = 200; }
    var tail = []; for (var j = 0; j < 90; j++) { if (opt.plant === 'drift') wk.tx += 3; await K.raf(2); t += 1 / 30; sample(); if (j >= 60) tail.push([wk.x, wk.y, app.out.rootM[4], app.out.rootM[5]]); }
    await K.wait(500); if (opt.plant === 'leg') delete AL.firstRole(app.ch.root, 'legR').dx;
    var y1 = tail[0][1], y2 = tail[tail.length - 1][1], mv = Math.hypot(tail[tail.length - 1][0] - tail[0][0], y2 - y1);
    R.F(name + ': no bad numbers in ' + frames + ' frames', bad === 0, bad);
    R.F(name + ': never off the stage', off === 0, { off: off, maxX: Math.max.apply(null, trace.map(function (q) { return Math.abs(q[0]); })), maxY: Math.max.apply(null, trace.map(function (q) { return Math.abs(q[1]); })) });
    R.F(name + ': nothing comes off the body by more than ' + WALK_SLACK + ' px', worst.gap <= WALK_SLACK, { gap: Math.round(worst.gap), part: worst.part, at: Math.round(worst.t * 10) / 10 + ' s' });
    R.F(name + ': every limb stays joined to the body (shoulder or hip pin within ' + JOINT_SLACK + ' px of the torso own drawing of that point)', worstJ.gap <= JOINT_SLACK, { gap: Math.round(worstJ.gap), part: worstJ.part, at: Math.round(worstJ.t * 10) / 10 + ' s' });
    R.F(name + ': settles after the finger lets go (root moves less than 1.5 px in the last 1 s)', mv < 1.5, { moved: Math.round(mv * 10) / 10, y: Math.round(y2) });
    R.note.push(name + ': worst joint gap ' + Math.round(worstJ.gap) + ' (' + worstJ.part + '), worst box gap ' + Math.round(worst.gap));
    return { worstJ: worstJ, worst: worst, settleY: y2, settleX: tail[tail.length - 1][0] };
  };

  W.run = async function (opt) {
    opt = opt || {}; var app = AL.app, R = W.live = K.mk(), keep = JSON.stringify(app.ch), plant = opt.plant || '';
    try {
      K.stand(app); await K.raf(3); var base = T.measure(app).gaps, jbase = W.jointGaps(app), jbaseS = await W.stanceJoints(app);
      var wk0 = app.ch.settings.walk || {}; if (!wk0.on) { R.note.push('no walking on this character'); return R; }
      app.ch.settings.idle = false; app.ch.settings.blinkAuto = false;
      var gy = wk0.groundY, scen = opt.only;
      var S = [
        ['push low', [[0.8, 0, 120], [1.5, 0, 120]]],
        ['push very low (to the floor)', [[0.8, 0, 260], [1.2, 0, 260]]],
        ['pull high', [[0.6, 0, -120], [1.2, 0, -120]]],
        ['pull very high', [[0.6, 0, -400], [1.2, 0, -400]]],
        ['walk right', [[1.5, 260, 0], [0.5, 260, 0]]],
        ['walk left', [[1.5, -260, 0], [0.5, -260, 0]]],
        ['walk right then straight back left', [[1.0, 220, 0], [1.4, -220, 0], [0.6, -220, 0]]],
        ['walk, push low while walking', [[1.0, 200, 0], [0.8, 200, 150], [1.2, 200, 150]]],
        ['pull up then push down', [[0.6, 0, -200], [0.5, 0, -200], [0.7, 0, 160], [1.2, 0, 160]]],
        ['shake it: quick back and forth', [[0.15, 150, 0], [0.15, -150, 0], [0.15, 150, 0], [0.15, -150, 0], [0.15, 150, 0], [0.15, -150, 0], [0.8, 0, 0]]]
      ];
      if (scen) S = S.filter(function (q) { return scen.indexOf(q[0]) >= 0; });
      for (var i = 0; i < S.length; i++) await W.scenario(app, R, S[i][0] + (opt.tag ? ' [' + opt.tag + ']' : ''), S[i][1], { base: base, jbase: jbase, jbaseS: jbaseS });
    } catch (e) { R.fails.push({ label: 'THROWN', info: String(e && e.stack || e).slice(0, 500) }); }
    AL.stages.go(app, 'fit'); K.restore(app, keep); await K.raf(2);
    return R;
  };
  /** The sliders: every walk setting at its two ends, then one push-low and one walk. */
  W.sliders = async function (opt) {
    opt = opt || {}; var app = AL.app, R = W.live = K.mk(), keep = JSON.stringify(app.ch);
    var sets = [['bob', [0, 0.8, 2.5, 4]], ['jumpAt', [0.2, 0.7, 1.6]], ['fourAt', [0.3, 0.82, 1]], ['stride', [8, 69, 220]], ['stepH', [0, 22, 90]], ['lean', [0, 0.5, 1.5]], ['armSwing', [0, 18, 50]]];
    try {
      K.stand(app); await K.raf(3); var base = T.measure(app).gaps, jbase = W.jointGaps(app), jbaseS = await W.stanceJoints(app); app.ch.settings.idle = false; app.ch.settings.blinkAuto = false;
      for (var i = 0; i < sets.length; i++) for (var j = 0; j < sets[i][1].length; j++) {
        if (opt.only && opt.only.indexOf(sets[i][0]) < 0) continue;
        app.ch.settings.walk[sets[i][0]] = sets[i][1][j]; var tag = sets[i][0] + '=' + sets[i][1][j];
        await W.scenario(app, R, 'walk right and push low, ' + tag, [[1.0, 250, 0], [0.7, 250, 140], [1.0, 250, 140]], { base: base, jbase: jbase, jbaseS: jbaseS });
        if (sets[i][0] === 'jumpAt' || sets[i][0] === 'fourAt') await W.scenario(app, R, 'pull high, ' + tag, [[0.6, 0, -260], [1.0, 0, -260]], { base: base, jbase: jbase, jbaseS: jbaseS });
        app.ch.settings.walk = JSON.parse(JSON.stringify(JSON.parse(keep).settings.walk));
      }
      /* the ground line moved up and down */
      var g0 = JSON.parse(keep).settings.walk.groundY;
      for (var gi = 0; gi < 4; gi++) { app.ch.settings.walk.groundY = g0 + [-90, -40, 40, 90][gi]; await W.scenario(app, R, 'ground moved ' + [-90, -40, 40, 90][gi] + ', walk then push low', [[1.0, 200, 0], [0.7, 200, 150], [1.0, 200, 150]], { base: null, jbase: jbase, jbaseS: jbaseS }); }
    } catch (e) { R.fails.push({ label: 'THROWN', info: String(e && e.stack || e).slice(0, 500) }); }
    AL.stages.go(app, 'fit'); K.restore(app, keep); await K.raf(2);
    return R;
  };
})(typeof self !== 'undefined' ? self : this);
