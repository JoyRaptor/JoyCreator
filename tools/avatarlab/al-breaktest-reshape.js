/* AvatarLab: BREAK TEST, area 3: reshape with pins, standing and in a stance. Load after al-breaktest-fit.js (it hangs on AL.breakTest.fit).
 *
 * A part with pins is drawn from a different mesh from a plain part, so the vertex lists cannot be compared; the drawn box and
 * centre of the part are (K.cloud). Numbers: tolerance 1.5 px against "plain", 0.5 px against itself.
 */
(function (root) {
  'use strict';
  var AL = root.AL, M = AL.M, T = AL.breakTest, K = T.kit, F = T.fit;
  function CD(a, b) { return Math.max(Math.abs(a.x0 - b.x0), Math.abs(a.y0 - b.y0), Math.abs(a.x1 - b.x1), Math.abs(a.y1 - b.y1), Math.hypot(a.x - b.x, a.y - b.y)); }
  F.reshapeTargets = function (app) {
    var arts = AL.findAll(app.ch.root, function (n) { return n.type === 'art' && AL.boundsOf(n) && !n.mesh && K.cloud(app, n) && !AL.ancestorWith(app.ch, n.id, function (q) { return q.type === 'set' && q.pick && q.pick.by === 'reach'; }); });  // (a limb's drawings are not offered Reshape: fit.can)
    arts.sort(function (a, b) { var A = AL.boundsOf(a), B = AL.boundsOf(b); return (B.x1 - B.x0) * (B.y1 - B.y0) - (A.x1 - A.x0) * (A.y1 - A.y0); });
    var grp = AL.findAll(app.ch.root, function (n) { return n.type === 'group' && n !== app.ch.root && n.kids && n.kids.length >= 2 && !n.mesh && !n.limb && K.cloud(app, n) && !AL.findAll(n, function (q) { return q.type === 'set'; }).length; });
    var out = [arts[0]]; if (grp[0]) out.push(grp[0]); if (arts[Math.floor(arts.length / 2)]) out.push(arts[Math.floor(arts.length / 2)]);
    return out;
  };
  F.reshape = async function (app, R, opt) {
    var fit = AL.fit, S = AL.stance, plant = opt.plant || '', ch0 = JSON.stringify(app.ch), tgt = F.reshapeTargets(app);
    for (var ti = 0; ti < tgt.length; ti++) {
      var id = tgt[ti].id, nm = 'reshape ' + tgt[ti].name + (tgt[ti].type === 'group' ? ' (a folder)' : '') + ': ';
      var N = function () { return AL.node(app.ch, id); };
      K.restore(app, ch0); await K.raf(3); var plain = K.cloud(app, N());
      var b = AL.boundsOf(N()), cx = (b.x0 + b.x1) / 2, cy = (b.y0 + b.y1) / 2, w = b.x1 - b.x0, h = b.y1 - b.y0;
      var addPin = function (x, y) { if (opt.oldPins) N().mesh.pins.push({ id: AL.id('p'), name: 'Pin ' + (N().mesh.pins.length + 1), x: Math.round(x), y: Math.round(y) }); else fit.dropPin(app, N(), [x, y]); };      // (oldPins: the bare pin the room used to drop, to prove the check catches it)
      var pinsOf = function () { app.refreshPanel(true); return (app.pinsNow ? app.pinsNow() : []).filter(function (q) { return q.node === id && q.key !== 'pv'; }); };
      var select = function () { app.tab = 'parts'; app.select(AL.zoneOf(app.ch, id), null, id); };
      /* the drawn point of the part nearest a spot (given as the finger gives it: in the part's own space, as drawn), to see whether THAT point moves */
      var allV = function () { var o = [], ids = {}; AL.walk(N(), function (k) { if (k.type === 'art') ids[k.id] = 1; }); app.out.items.forEach(function (it) { if (!it.ghost && ids[it.id]) for (var i = 0; i < it.v.length; i += 2) o.push([it.v[i], it.v[i + 1]]); }); return o; };
      var spot = function (lx, ly) { var m = app.out.M[id] || app.out.rootM, p = M.ap(m, lx, ly), v = allV(), bi = 0, bd = 1e9; v.forEach(function (q, i) { var d = Math.hypot(q[0] - p[0], q[1] - p[1]); if (d < bd) { bd = d; bi = i; } }); return { i: bi, p: v[bi] }; };
      var spotMoved = function (s) { var q = allV()[s.i]; return q ? Math.round(Math.hypot(q[0] - s.p[0], q[1] - s.p[1]) * 10) / 10 : 999; };
      var out = function () { app.poseEdit = null; app.fitOn = null; app.shapeEdit = null; app.refreshPanel(); };

      /* 1. standing: reshape on, three pins, nothing dragged yet */
      select(); fit.reshapeOn(app, N(), true); await K.raf(2);
      app.edit(function () { addPin(cx - w * 0.3, cy); addPin(cx + w * 0.3, cy); addPin(cx, cy - h * 0.3); }, false, 'pins'); await K.raf(3);
      var und = K.cloud(app, N()), cd = CD(plain, und);
      R.F(nm + 'three pins, none dragged: the part does not change', cd < 1.5, { px: Math.round(cd * 10) / 10 });
      out(); K.restore(app, ch0); await K.raf(3);

      /* 2. a reshape made only in a stance: standing untouched, in Motion on and off */
      var pz; app.edit(function () { pz = S.add(app.ch, 'Shaper'); }, true, 'add'); var pid = pz.id, trig = pz.trig;
      select(); app.poseEdit = pid; app.poseAmt = null; app.refreshPanel(); fit.reshapeOn(app, N(), true); await K.raf(2);
      var sid = fit.shapeId(app); R.F(nm + 'the stance shape is called pose:<id>', sid === 'pose:' + pid, sid);
      app.edit(function () { addPin(cx - w * 0.3, cy); addPin(cx + w * 0.3, cy); addPin(cx, cy - h * 0.3); }, false, 'pins'); await K.raf(3);
      var pn = pinsOf(); R.F(nm + 'three pins offered for dragging in the stance', pn.length === 3, pn.length);
      app.hist.push(app.ch, 'drag'); K.drag(app, pn[2], 22, -30); await K.raf(3);
      var inSt = K.cloud(app, N()), sh = N().shapes.filter(function (q) { return q.id === sid; })[0];
      R.F(nm + 'a pin dragged in the stance reshapes it (shown on the picture)', CD(und, inSt) > 2 && Object.keys(sh.d || {}).length === 1, { px: Math.round(CD(und, inSt) * 10) / 10, d: sh.d });
      out(); await K.raf(3); var stand = K.cloud(app, N());
      R.F(nm + 'leaving the stance: standing is as it was before the reshape', CD(plain, stand) < 1.5, { px: Math.round(CD(plain, stand) * 10) / 10 });
      AL.stages.go(app, 'motion'); await K.wait(500); var c0 = K.cloud(app, N()); await K.wait(1500); var cdrift = K.cloud(app, N());
      app.inp.setTrig(trig, true); await K.wait(1800); var c1 = K.cloud(app, N());
      app.inp.setTrig(trig, false); await K.wait(2000); var c2 = K.cloud(app, N());
      R.F(nm + 'Motion: the stance button on shows the reshape', CD(c0, c1) > 2, { px: Math.round(CD(c0, c1) * 10) / 10 });
      R.F(nm + 'Motion: button off gives the part back (idle drift measured with no press: ' + Math.round(CD(c0, cdrift) * 10) / 10 + ' px)', CD(c0, c2) < Math.max(4, CD(c0, cdrift) * 2 + 2), { px: Math.round(CD(c0, c2) * 10) / 10 });
      AL.stages.go(app, 'fit'); await K.raf(3);
      /* Start again, in the stance */
      select(); app.poseEdit = pid; app.refreshPanel(); fit.reshapeOn(app, N(), true); await K.raf(3);
      var fs = fit.fixShape(N(), app); app.edit(function () { fs.d = {}; }, false, 'Clear the reshape'); await K.raf(3);
      R.F(nm + 'Start again: the part is as it was with its pins undragged', CD(und, K.cloud(app, N())) < 0.6, { px: Math.round(CD(und, K.cloud(app, N())) * 10) / 10 });
      out(); K.restore(app, ch0); await K.raf(3);

      /* 3. standing reshape, then a stance adds a pin of its own, and removes one */
      select(); fit.reshapeOn(app, N(), true); await K.raf(2);
      app.edit(function () { addPin(cx - w * 0.3, cy); addPin(cx + w * 0.3, cy); }, false, 'pins'); await K.raf(2);
      pn = pinsOf(); K.drag(app, pn[0], 14, 10); pn = pinsOf(); K.drag(app, pn[1], -10, 22); await K.raf(3);
      fit.reshapeOn(app, N(), false); await K.raf(3); var shaped = K.cloud(app, N());
      R.F(nm + 'standing reshape shows (two pins dragged)', CD(plain, shaped) > 2, { px: Math.round(CD(plain, shaped) * 10) / 10 });
      var pz2; app.edit(function () { pz2 = S.add(app.ch, 'Second'); }, true, 'add'); select(); app.poseEdit = pz2.id; app.refreshPanel(); fit.reshapeOn(app, N(), true); await K.raf(2);
      app.edit(function () { addPin(cx, cy + h * 0.3); }, false, 'a pin in the stance'); await K.raf(3);
      pn = pinsOf(); K.drag(app, pn[pn.length - 1], 0, 25); await K.raf(3);
      out(); await K.raf(3); var after = K.cloud(app, N());
      R.F(nm + 'standing reshape unchanged after a stance adds a pin of its own', CD(shaped, after) < 0.6, { px: Math.round(CD(shaped, after) * 10) / 10 });
      /* 3b. in that stance: a pin landing on the reshaped part does not move the part; a pin the STANDING shape moved does not jump at the first touch */
      select(); app.poseEdit = pz2.id; app.refreshPanel(); fit.reshapeOn(app, N(), true); await K.raf(3);
      var b4 = K.cloud(app, N()), sp4 = spot(cx + w * 0.1, cy - h * 0.1); app.edit(function () { addPin(cx + w * 0.1, cy - h * 0.1); }, false, 'a second pin in the stance'); await K.raf(3);
      R.F(nm + 'in a stance, the spot under a new pin does not move when the pin lands', spotMoved(sp4) < 1.5, { px: spotMoved(sp4) });
      R.note.push(nm + 'a pin landing in a stance moved the rest of the part by up to ' + Math.round(CD(b4, K.cloud(app, N())) * 10) / 10 + ' px (the warp refits round every pin: known, see the job card)');
      pn = pinsOf(); var b5 = K.cloud(app, N()); K.drag(app, pn[0], plant === 'touch' ? 14 : 0.4, plant === 'touch' ? 10 : 0.4); await K.raf(3);      // (touch: the jump by the standing offset that the first touch used to give)
      R.F(nm + 'a pin the standing shape moved, nudged in the stance, does not jump', CD(b5, K.cloud(app, N())) < 2.5, { px: Math.round(CD(b5, K.cloud(app, N())) * 10) / 10 });
      out(); await K.raf(3);
      R.F(nm + 'and standing is still unchanged', CD(shaped, K.cloud(app, N())) < 0.6, { px: Math.round(CD(shaped, K.cloud(app, N())) * 10) / 10 });
      /* 3c. the other way round: a pin added while STANDING, with the stance reshaped. Standing must not move; how far the stance moves is noted */
      app.poseEdit = pz2.id; app.refreshPanel(); await K.raf(3); var st0 = K.cloud(app, N());
      select(); app.poseEdit = null; app.refreshPanel(); fit.reshapeOn(app, N(), true); await K.raf(3);
      var s0 = K.cloud(app, N()), sp0 = spot(cx - w * 0.1, cy + h * 0.1); app.edit(function () { addPin(cx - w * 0.1, cy + h * 0.1); }, false, 'a pin while standing'); await K.raf(3);
      R.F(nm + 'standing, the spot under a new pin does not move when the pin lands', spotMoved(sp0) < 1.5, { px: spotMoved(sp0) });
      R.note.push(nm + 'a pin landing while standing moved the rest of the part by up to ' + Math.round(CD(s0, K.cloud(app, N())) * 10) / 10 + ' px');
      app.edit(function () { fit.removePin(app, N()); }, false, 'Remove a pin'); await K.raf(3);
      R.F(nm + 'removing it again gives standing back', CD(s0, K.cloud(app, N())) < 0.6, { px: Math.round(CD(s0, K.cloud(app, N())) * 10) / 10 });
      app.edit(function () { addPin(cx - w * 0.1, cy + h * 0.1); }, false, 'a pin while standing'); await K.raf(2);
      out(); app.poseEdit = pz2.id; app.refreshPanel(); await K.raf(3); var st1 = K.cloud(app, N());
      R.note.push(nm + 'a pin added while standing moved the stance\'s own reshape by ' + Math.round(CD(st0, st1) * 10) / 10 + ' px');
      R.F(nm + 'a pin added while standing leaves the stance within 4 px', CD(st0, st1) < 4, { px: Math.round(CD(st0, st1) * 10) / 10 });
      app.poseEdit = null; app.refreshPanel(); fit.reshapeOn(app, N(), true); await K.raf(2); app.edit(function () { fit.removePin(app, N()); }, false, 'Remove a pin'); out(); await K.raf(3);
      select(); app.poseEdit = pz2.id; app.refreshPanel(); fit.reshapeOn(app, N(), true); await K.raf(2);
      app.edit(function () { fit.removePin(app, N()); }, false, 'Remove a pin'); await K.raf(2);
      out(); await K.raf(3); var after2 = K.cloud(app, N());
      R.F(nm + 'standing reshape unchanged after a stance removes its last pin', CD(shaped, after2) < 0.6, { px: Math.round(CD(shaped, after2) * 10) / 10 });
      /* 4. remove every pin standing */
      select(); app.poseEdit = null; app.refreshPanel(); fit.reshapeOn(app, N(), true); await K.raf(2);
      var guard = 0; while (N().mesh.pins.length && guard++ < 10) app.edit(function () { if (!fit.removePin(app, N())) N().mesh.pins.pop(); }, false, 'Remove a pin');
      await K.raf(3); fit.reshapeOn(app, N(), false); await K.raf(3);
      R.F(nm + 'every pin removed: the part is plain again', CD(plain, K.cloud(app, N())) < 1.5 && K.snap(app).bad === 0, { px: Math.round(CD(plain, K.cloud(app, N())) * 10) / 10 });
      out(); K.restore(app, ch0); await K.raf(3);

      /* 5. a stance A with a reshape, the pose changed to B and back to standing with pins up */
      var pa, pb2; app.edit(function () { pa = S.add(app.ch, 'A'); pb2 = S.add(app.ch, 'B'); }, true, 'add'); select(); app.poseEdit = pa.id; app.refreshPanel(); fit.reshapeOn(app, N(), true);
      app.edit(function () { addPin(cx - w * 0.3, cy); addPin(cx + w * 0.3, cy); }, false, 'pins'); await K.raf(2); pn = pinsOf(); K.drag(app, pn[0], 16, 16); await K.raf(2);
      var inA = K.cloud(app, N()); R.F(nm + 'A shows its reshape', CD(und, inA) > 2 || CD(plain, inA) > 2, { px: Math.round(CD(plain, inA) * 10) / 10 });
      app.poseEdit = pb2.id; app.poseAmt = null; app.refreshPanel(); await K.raf(3); var inB = K.cloud(app, N());
      R.F(nm + 'changing to stance B with pins up: B does not show A\'s reshape', CD(plain, inB) < 1.5, { px: Math.round(CD(plain, inB) * 10) / 10, shapeEdit: app.shapeEdit && app.shapeEdit.shape });
      app.poseEdit = null; app.refreshPanel(); await K.raf(3); R.F(nm + 'back to standing: plain', CD(plain, K.cloud(app, N())) < 1.5, { px: Math.round(CD(plain, K.cloud(app, N())) * 10) / 10 });
      /* 6. undo walks back through it all without a bad number */
      var guard2 = 0; while (app.hist.past.length && guard2++ < 30) { app.undo(false, true); }
      await K.raf(3); R.F(nm + 'undo all the way back: no bad numbers', K.snap(app).bad === 0);
      out(); K.restore(app, ch0); await K.raf(2);
    }
  };
})(typeof self !== 'undefined' ? self : this);
