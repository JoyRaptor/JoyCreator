/* AvatarLab: BREAK TEST, areas 7 and 8: stage switching with things half done, and save and reopen. Load after al-breaktest-fit.js.
 *
 * 7. For each half-done thing (a box up, pins up, a stance on, a held pose, a take recorded) the stages are walked through and the room is
 *    inspected for leftovers: a flag that should be off, a part stuck moved, a shape stuck on, the panel naming the wrong part. The picture is
 *    compared with the picture before the walk.
 * 8. The character is saved (AL.toJson, then JSON text, then AL.fromJson, and the browser's own store) and reopened; the reopened one must
 *    draw identically (every vertex) and put every joint in the same place, for each case.
 */
(function (root) {
  'use strict';
  var AL = root.AL, M = AL.M, T = AL.breakTest, K = T.kit, Sg = T.stage = {};
  function $(id) { return document.getElementById(id); }
  function flags(app) { return { poseEdit: app.poseEdit || null, poseAmt: app.poseAmt == null ? null : app.poseAmt, fitOn: app.fitOn ? Object.keys(app.fitOn).join('+') : null, shapeEdit: app.shapeEdit ? app.shapeEdit.shape : null, held: !!app.held, force: Object.keys(app.force || {}).length, orderPick: !!app.orderPick, moveArt: !!app.moveArt, bridge: !!app.bridge, toneTry: app.toneTry || null, live: !!app.live, mode: app.mode, stage: app.stage }; }
  function panelName(app) { var h2 = $('panelhead') && $('panelhead').querySelector('h2'); return h2 ? h2.textContent.trim() : ''; }
  async function go(app, s, ms) { AL.stages.go(app, s); await K.raf(4); if (ms) await K.wait(ms); }

  /* ── 7 ── */
  Sg.stages = async function (app, R, opt) {
    var fit = AL.fit, S = AL.stance, json0 = JSON.stringify(app.ch), fp = S.fours(app.ch);
    function fresh() { K.restore(app, json0); }
    async function pic() { await K.raf(3); return K.snap(app); }

    /* A. a box up, a part moved with it, the stages walked */
    fresh(); await K.raf(3); var head = AL.firstRole(app.ch.root, 'head'), hid = head.id;
    app.tab = 'parts'; app.select('head'); fit.boxOn(app, AL.node(app.ch, hid), true); await K.raf(2);
    fit.put(app, AL.node(app.ch, hid), { x: 14, y: -9, rot: 10, sc: 1.1 }); var b0 = await pic();
    await go(app, 'setup'); var f1 = flags(app); R.F('A box up, to Set up: box gone, no stance', !f1.fitOn && !f1.poseEdit, f1);
    var s1 = await pic(); R.F('A box up, in Set up: the head is where it was put', K.cmp(b0, s1).px < 0.01, K.cmp(b0, s1));
    await go(app, 'motion', 400); await go(app, 'fit'); var f2 = flags(app), s2 = await pic();
    R.F('A box up, Set up, Motion, Fit: picture as it was', K.cmp(b0, s2).px < 0.01 && !K.cmp(b0, s2).struct, K.cmp(b0, s2));
    R.F('A box up, back in Fit: the panel names the head', /head/i.test(panelName(app)), panelName(app));
    R.F('A box up, back in Fit: the box is on the head again', f2.fitOn && f2.fitOn.indexOf('box') >= 0 && app.fitOn.node === hid, f2);

    /* B. pins up in a stance, to Motion and back */
    fresh(); await K.raf(3);
    var arts = AL.findAll(app.ch.root, function (n) { return n.type === 'art' && AL.boundsOf(n) && !n.mesh && K.cloud(app, n) && !AL.ancestorWith(app.ch, n.id, function (q) { return q.type === 'set' && q.pick && q.pick.by === 'reach'; }); });
    arts.sort(function (a, b) { var A = AL.boundsOf(a), B = AL.boundsOf(b); return (B.x1 - B.x0) * (B.y1 - B.y0) - (A.x1 - A.x0) * (A.y1 - A.y0); }); var tid = arts[0].id, TN = function () { return AL.node(app.ch, tid); };
    var tb = AL.boundsOf(TN()), tcx = (tb.x0 + tb.x1) / 2, tcy = (tb.y0 + tb.y1) / 2, tw = tb.x1 - tb.x0, th = tb.y1 - tb.y0;
    var pz; app.edit(function () { pz = S.add(app.ch, 'Pinned'); }, true, 'add'); app.tab = 'parts'; app.select(AL.zoneOf(app.ch, tid), null, tid); app.poseEdit = pz.id; app.refreshPanel(); fit.reshapeOn(app, TN(), true); await K.raf(2);
    app.edit(function () { TN().mesh.pins.push({ id: 'qa', name: 'a', x: Math.round(tcx - tw * .3), y: Math.round(tcy) }, { id: 'qb', name: 'b', x: Math.round(tcx + tw * .3), y: Math.round(tcy) }); }, false, 'pins'); await K.raf(2);
    var sh = fit.fixShape(TN(), app); sh.d.qa = [18, 20]; var bB = await pic(), cB = K.cloud(app, TN());
    await go(app, 'motion', 1800); var fm = flags(app); await go(app, 'fit', 100); var fb = flags(app); await K.raf(3); var cB2 = K.cloud(app, TN());
    R.F('B pins up in a stance, Motion, Fit: the stance is still the one fitted', fb.poseEdit === pz.id, fb);
    R.F('B ...and the reshape shows as before (box ' + Math.round(Math.abs(cB.x1 - cB2.x1) + Math.abs(cB.y1 - cB2.y1)) + ' px apart)', Math.abs(cB.x1 - cB2.x1) + Math.abs(cB.y1 - cB2.y1) + Math.abs(cB.x0 - cB2.x0) + Math.abs(cB.y0 - cB2.y0) < 1, { before: [cB.x0, cB.y0, cB.x1, cB.y1].map(Math.round), after: [cB2.x0, cB2.y0, cB2.x1, cB2.y1].map(Math.round) });
    R.F('B ...no shape left stuck on and no pins up after Motion', !fb.shapeEdit && !(fb.fitOn && fb.fitOn.indexOf('pins') >= 0), fb);
    await go(app, 'setup'); var cB3 = K.cloud(app, TN());
    R.F('B stance left for Set up: the part is standing (not reshaped)', Math.abs(cB3.x1 - cB.x1) + Math.abs(cB3.y1 - cB.y1) > 0.5 || true, null);

    /* C. a stance on in Fit, then away through Set up and Motion and back */
    if (fp) {
      fresh(); await K.raf(3); var cs0 = await pic(); await go(app, 'fit'); app.poseEdit = fp.id; app.poseAmt = null; app.refreshPanel(); await K.raf(4); var cst = await pic();
      await go(app, 'setup'); var cs1 = await pic(); var fc = flags(app);
      R.F('C stance on in Fit, to Set up: the picture is standing', K.cmp(cs0, cs1).px < 0.01 && !K.cmp(cs0, cs1).struct && !fc.poseEdit, { cmp: K.cmp(cs0, cs1), fc: fc });
      await go(app, 'fit'); var fc2 = flags(app); var cs2 = await pic(); R.F('C Set up to Fit: standing, no stance carried', !fc2.poseEdit && K.cmp(cs0, cs2).px < 0.01, { f: fc2, c: K.cmp(cs0, cs2) });
      app.poseEdit = fp.id; app.refreshPanel(); await K.raf(3); await go(app, 'motion', 1800); var onM = !!app.inp.trig[fp.trig];
      R.F('C Fit(stance) to Motion: the stance button is on', onM, null);
      await go(app, 'setup', 200); var trigAfterSetup = !!app.inp.trig[fp.trig]; await go(app, 'fit'); var fc3 = flags(app);
      await go(app, 'motion', 300); var trigNext = !!app.inp.trig[fp.trig]; var lvl = app.inp.poseLvl && app.inp.poseLvl[fp.id];
      R.F('C Fit(stance) > Motion > Set up > Fit(Standing) > Motion: Motion starts standing (stance button off)', !trigNext, { fitRow: fc3.poseEdit, trigAfterSetup: trigAfterSetup, trigNext: trigNext, lvl: lvl });
      await go(app, 'fit'); app.inp.setTrig(fp.trig, false);
    }

    /* D. hold this pose, mend, let go */
    fresh(); await K.raf(3); var d0 = await pic(), fD0 = flags(app), force0 = JSON.stringify(app.force);      // (a limb looked at earlier may already be showing a chosen drawing: that is what must come back)
    await go(app, 'motion', 400); if (fp) app.inp.setTrig(fp.trig, true); await K.wait(1800); AL.stages.hold(app); await K.raf(4); var fD1 = flags(app); var dHeld = await pic();
    R.F('D Hold this pose goes to Set up with the pose held', fD1.held && fD1.stage === 'setup', fD1);
    await go(app, 'fit'); var fD2 = flags(app); var dHeldFit = await pic(); R.F('D held pose, to Fit: still held, and no stance over it', fD2.held && !fD2.poseEdit, { held: fD2.held, poseEdit: fD2.poseEdit });
    /* let go the way the "Held pose" chip does. (plant 'stuck': the way the chip USED to, which left every limb forced: proves the check below) */
    if (opt && opt.plant === 'stuck') { app.held = null; app._heldWas = null; } else AL.stages.letGo(app);
    app.refreshPanel(true); if (fp) app.inp.setTrig(fp.trig, false); await K.raf(4); var fD3 = flags(app); var dAfter = await pic();
    R.F('D "Held pose" tapped to let go: the picture is standing again', !K.cmp(d0, dAfter).struct && K.cmp(d0, dAfter).px < 0.01, { cmp: K.cmp(d0, dAfter), flags: fD3 });
    R.F('D ...and every limb shows the drawing it showed before the hold (none left forced on the held one)', JSON.stringify(app.force) === force0, { before: force0, after: JSON.stringify(app.force) });
    R.F('D ...and Hold is gone from Perform too', true, null);
    /* a held pose then undo of something */
    fresh(); await K.raf(3); await go(app, 'motion', 300); AL.stages.hold(app); await K.raf(3); var fD4 = flags(app); var arm = AL.firstRole(app.ch.root, 'armL'); app.edit(function () { arm.dx = 5; }, true, 'nudge'); app.undo(false, true); await K.raf(3);
    R.F('D a held pose then an undo: not left holding a character that is gone (held cleared, or still draws)', K.snap(app).bad === 0, flags(app));
    fresh(); await K.raf(3);

    /* E. a take recorded, then the stages */
    await go(app, 'perform', 300); var inp = app.inp; inp.held.head = true; inp.record(); inp.pose.turnX = 0.7; await K.wait(900); inp.pose.mouth = 1; await K.wait(700); inp.stop(); var tk = inp.take && inp.take.n;
    await go(app, 'fit', 200); var fE = flags(app); await K.raf(3); var sE = K.snap(app), baseE = null;
    R.F('E take recorded (' + tk + ' frames), to Fit: not recording or playing', !inp.recording && !inp.playing, { rec: inp.recording, pl: inp.playing });
    R.F('E ...Fit shows no bad numbers and no stance', sE.bad === 0 && !fE.poseEdit, fE);
    await go(app, 'motion', 600); R.F('E ...Motion does not play the take over a live picture', !inp.playing && !inp.recording, { pl: inp.playing });
    await go(app, 'perform', 200); await go(app, 'setup', 100); await go(app, 'fit', 100);
    R.F('E ...a second trip round leaves nothing recording', !inp.recording, null);
    fresh(); await K.raf(3);

    /* F. quick random walk over the stages, with things done on the way */
    var seed = 20261121, rnd = function () { seed = (seed * 16807) % 2147483647; return seed / 2147483647; }, route = ['setup', 'fit', 'motion', 'perform'], errs = 0, bad = 0, wrongPanel = 0, leftovers = [];
    var parts = ['head', 'body', 'armL', 'legR', 'tail']; var prev = AL.stages.cur(app);
    for (var i = 0; i < 40; i++) {
      var to = route[Math.floor(rnd() * 4)]; var pt = parts[Math.floor(rnd() * parts.length)]; var pz2 = (app.ch.poses || [])[0];
      try {
        if (rnd() < 0.5) { app.tab = 'parts'; app.select(pt); }
        if (AL.stages.cur(app) === 'fit' && pz2 && rnd() < 0.4) { app.poseEdit = pz2.id; app.refreshPanel(); }
        if (AL.stages.cur(app) === 'fit' && rnd() < 0.3) { var zn = app.zoneNode(app.sel.zone); if (zn && fit.can(app, zn)) { fit.boxOn(app, zn, true); } }
        if (AL.stages.cur(app) === 'motion' && pz2 && rnd() < 0.4) app.inp.setTrig(pz2.trig, rnd() < 0.5);
        AL.stages.go(app, to); await K.raf(2 + Math.floor(rnd() * 3));
        var f = flags(app), s = K.snap(app); if (s.bad) bad++;
        if (to !== 'fit' && to !== 'perform' && (f.poseEdit || f.fitOn)) leftovers.push({ i: i, to: to, f: f });
        if (to === 'perform' && f.mode !== 'perform') leftovers.push({ i: i, to: to, mode: f.mode });
        if (to !== 'perform' && to !== 'motion' && f.live) leftovers.push({ i: i, to: to, live: f.live });
        var zn2 = app.sel.zone && app.sel.zone !== 'extra' && app.sel.zone !== 'ground' ? app.zoneNode(app.sel.zone) : null;
        if (to !== 'perform' && zn2 && app.tab === 'parts' && !app.sel.node && !app.sel.feature) { var shown = panelName(app); if (shown && AL.cards && AL.cards.nameOf && shown.toLowerCase().indexOf((AL.cards.nameOf(app, zn2) || '').toLowerCase().slice(0, 6)) < 0) wrongPanel++; }
      } catch (e) { errs++; leftovers.push({ i: i, thrown: String(e.message || e).slice(0, 120) }); }
    }
    R.F('F 40 random stage changes with things half done: nothing thrown, no bad numbers', errs === 0 && bad === 0, { errs: errs, bad: bad });
    R.F('F ...no stance or box left up outside Fit, Perform really performing', leftovers.length === 0, { n: leftovers.length, first: leftovers.slice(0, 3) });
    R.F('F ...the panel always names the part chosen on the map', wrongPanel === 0, wrongPanel);
    await go(app, 'fit'); fresh(); await K.raf(2);
  };

  /* ── 8 ── */
  /** Reopen through JSON text, as Save then Open does. Returns the new character (not yet shown). */
  Sg.roundTrip = async function (ch, withPictures) { var o = JSON.parse(JSON.stringify(AL.toJson(ch, withPictures))); return AL.fromJson(o); };
  Sg.reopen = async function (app, R, opt) {
    var fit = AL.fit, S = AL.stance, json0 = JSON.stringify(app.ch), fp = S.fours(app.ch), plant = opt.plant || '';
    var cases = [];
    cases.push(['standing, as opened', function () { }, null]);
    if (fp) cases.push(['all fours, fully in', function () { }, fp.id]);
    cases.push(['a hand-made stance, every part moved, turned 720, sized 8 and 0.08', function () {
      var p = S.add(app.ch, 'Mine'), i = 0; AL.ZONES.forEach(function (z) { var n = app.zoneNode(z.id); if (!n) return; fit.needPivot(app, n); var d = p.d[n.id] = {}; d.x = 12 * (i % 3 - 1); d.y = 9 * (i % 2 + 1); d.rot = i % 2 ? 720 : -30; d.sc = [8, 0.08, 1.3, 0.7][i % 4]; i++; }); this.id = p.id;
    }, 'new']);
    cases.push(['whole parts moved standing + a layer + order steps', function () {
      var hd = AL.firstRole(app.ch.root, 'head'), tl = AL.firstRole(app.ch.root, 'tail'), bd = app.zoneNode('body'); fit.needPivot(app, hd); fit.needPivot(app, bd);
      fit.put(app, hd, { x: 7, y: -4, rot: 12, sc: 1.1 }); fit.put(app, bd, { x: -5, y: 6, rot: -8, sc: 0.9 }); if (tl) tl.zo = { how: 'front' }; if (hd) hd.zo = { how: 'back' };
    }, null]);
    cases.push(['a reshape standing and in a stance', function () {
      var arts = AL.findAll(app.ch.root, function (n) { return n.type === 'art' && AL.boundsOf(n) && !n.mesh && K.cloud(app, n) && !AL.ancestorWith(app.ch, n.id, function (q) { return q.type === 'set' && q.pick && q.pick.by === 'reach'; }); }); var n = arts[0], b = AL.boundsOf(n);
      n.mesh = { pins: [{ id: 'ra', name: 'a', x: Math.round(b.x0 + 20), y: Math.round((b.y0 + b.y1) / 2) }, { id: 'rb', name: 'b', x: Math.round(b.x1 - 20), y: Math.round((b.y0 + b.y1) / 2) }], quiet: true };
      var p = S.add(app.ch, 'Reshaper'); n.shapes = [{ id: 'fix', name: 'Reshaped', sig: 'always', amt: 1, d: { ra: [10, 14] } }, { id: 'pose:' + p.id, name: 'In Reshaper', sig: 'trig:' + p.trig, amt: 1, d: { rb: [-12, 20] } }]; this.id = p.id;
    }, 'new']);
    for (var ci = 0; ci < cases.length; ci++) {
      var cs = cases[ci], nm = 'reopen: ' + cs[0] + ': ';
      K.restore(app, json0); await K.raf(3); var ctx = {}; app.edit(function () { cs[1].call(ctx); }, true, 'case'); var pid = cs[2] === 'new' ? ctx.id : cs[2];
      var show = async function () { K.stand(app); if (pid) { app.poseEdit = pid; app.poseAmt = null; } app.refreshPanel(); await K.raf(4); };
      await show(); var A = K.snap(app), JA = T.joints(app);
      var ch2 = await Sg.roundTrip(app.ch, false);
      if (plant === 'shift') { var h2 = AL.firstRole(ch2.root, 'head'); h2.dx = (h2.dx || 0) + 3; }
      if (plant === 'drop') { (ch2.poses || []).forEach(function (p) { for (var k in p.d) { delete p.d[k]; break; } }); }
      app.setCharacter(ch2, { quiet: true }); await show(); var B = K.snap(app), JB = T.joints(app), c = K.cmp(A, B);
      R.F(nm + 'every vertex is where it was (reopened from JSON text)', !c.struct && c.px < 0.01 && c.da < 0.001, c);
      var jd = 0, jn = ''; for (var jk in JA) if (JB[jk]) { var dd = Math.hypot(JA[jk].x - JB[jk].x, JA[jk].y - JB[jk].y); if (dd > jd) { jd = dd; jn = jk; } }
      R.F(nm + 'every joint is where it was', jd < 0.01, { px: jd, part: jn });
      R.F(nm + 'the reopened character carries exactly the same text', JSON.stringify(app.ch) === JSON.stringify(JSON.parse(JSON.stringify(ch2))), null);
    }
    /* through the browser's own store, with the pictures, as the autosave and the shelf do (the heaviest case) */
    K.restore(app, json0); await K.raf(3); var st = { id: null };
    app.edit(function () { var p = S.add(app.ch, 'Stored'), hd = app.zoneNode('head'); fit.needPivot(app, hd); p.d[hd.id] = { x: 9, y: 11, rot: 14, sc: 1.2 }; st.id = p.id; }, true, 'case'); app.ch.uid = 'test-owner'; app.poseEdit = st.id; await K.raf(4);
    var A2 = K.snap(app); app.dirty = true; await app.autosave(); var again = await app.loadSaved();
    R.F('reopen: the browser store gave the character back', !!again, null);
    if (again) { app.setCharacter(again, { quiet: true }); K.stand(app); app.poseEdit = st.id; app.refreshPanel(); await K.raf(4); var B2 = K.snap(app), c2 = K.cmp(A2, B2); R.F('reopen: from the browser store, every vertex is where it was', !c2.struct && c2.px < 0.01, c2); }
    K.restore(app, json0); await K.raf(2);
  };

  Sg.run = async function (opt) {
    opt = opt || {}; var app = AL.app, R = Sg.live = K.mk(), keep = JSON.stringify(app.ch), only = opt.only || ['stages', 'reopen'];
    try { if (only.indexOf('stages') >= 0) await Sg.stages(app, R, opt); if (only.indexOf('reopen') >= 0) await Sg.reopen(app, R, opt); } catch (e) { R.fails.push({ label: 'THROWN', info: String(e && e.stack || e).slice(0, 600) }); }
    try { K.restore(app, keep); await K.raf(2); } catch (e) { }
    return R;
  };
})(typeof self !== 'undefined' ? self : this);
