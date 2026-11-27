/* AvatarLab: BREAK TEST, the face mask (job FACE-2, stage 1). Not part of the page: load it by hand after al-breaktest.js
 * and al-breaktest-fit.js (the kit), then in the console:
 *
 *   await AL.breakTest.mask.run()      the checks, on the character that is open (its head must turn: Topps)
 *   await AL.breakTest.mask.proof()    plants each fault; every one must be caught
 *
 * The screen is driven as a finger drives it (pointer events on the stage, where the points are DRAWN); the turn and the
 * carry are checked with a made-up placing (the mask's own shape turned by known amounts), so every check knows the truth.
 * It drives time by hand (K.sim): reload the page afterwards.
 *
 * plant = which fault to put in:
 *   nostay    a point you place does not stay put (the owner's round-2 complaint)   norefit  handles move only themselves
 *   nohide    Hidden on this drawing does nothing           noadd    Add a point adds nothing
 *   noturn    a drawing's turn is read from its dot on the pad, not its points       nolearn  the creature's own shape is never learned
 *   noundo    an edit is not one undo step                  noentry  no Face mask on the head's card
 */
(function (root) {
  'use strict';
  var AL = root.AL, T = AL.breakTest = AL.breakTest || {}, K = T.kit, X = T.mask = {};

  function stageXY(p) { var st = document.getElementById('stage'), r = st.getBoundingClientRect(), k = st.clientWidth / r.width; return { x: r.left + p.x / k, y: r.top + p.y / k }; }
  function ptr(type, c, b, id) { document.getElementById('stage').dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, pointerId: id || 81, pointerType: 'touch', isPrimary: true, clientX: c.x, clientY: c.y, button: 0, buttons: b })); }
  function drag(a, b) { var A = stageXY(a), B = stageXY(b); ptr('pointerdown', A, 1); for (var i = 1; i <= 6; i++) { var t = i / 6; ptr('pointermove', { x: A.x + (B.x - A.x) * t, y: A.y + (B.y - A.y) * t }, 1); } ptr('pointerup', B, 0); }
  function tap(a) { var A = stageXY(a); ptr('pointerdown', A, 1); ptr('pointerup', A, 0); }
  /** Where point i is DRAWN on the stage now (stage pixels), and how. */
  function drawn(app, i) {
    var W = app.maskMode, g = W && W.svg && W.svg.querySelector('[data-mask="' + i + '"]'); if (!g) return null;
    var mark = g.querySelector('path') || g.querySelectorAll('circle')[g.querySelectorAll('circle').length > 1 && W.sel === i ? 1 : 0];
    return { x: +g.getAttribute('data-x'), y: +g.getAttribute('data-y'), state: g.getAttribute('data-state'), mine: g.getAttribute('data-mine') === '1', diamond: !!g.querySelector('path'), dashed: !!(mark && mark.getAttribute('stroke-dasharray')) };
  }
  function dist(a, b) { return a && b ? Math.hypot(a.x - b.x, a.y - b.y) : 1e9; }
  function button(re) { return Array.prototype.filter.call(document.querySelectorAll('#panelbody button'), function (b) { return re.test(b.textContent.trim()); })[0] || null; }
  function idx(set, key) { return AL.mask.piece(set).pts.map(function (p) { return p.key; }).indexOf(key); }
  /** A made-up placing: the mask's points as a drawing turned (yaw, pitch) would show them, round the drawing's middle. */
  function synth(set, kid, yaw, pitch, shape) {
    var b = AL.boundsOf(kid), cx = (b.x0 + b.x1) / 2, cy = (b.y0 + b.y1) / 2 + (b.y1 - b.y0) * 0.1, sc = Math.min(b.x1 - b.x0, b.y1 - b.y0) * 0.16, r = AL.mask.R3(yaw, pitch), out = {};
    AL.mask.piece(set).pts.forEach(function (p, i) { var s3 = shape(p); if (!s3) return; var q = AL.mask.fwd(r, s3); if (q[2] > 0.05) out[i] = [cx + sc * q[0], cy + sc * q[1]]; });
    out.sc = sc; return out;
  }
  function tplShape(p) { var t = AL.mask.TPL.filter(function (q) { return q[0] === p.key; })[0]; return t ? [t[2], t[3], t[4]] : null; }
  /** A creature's own face: the snout far forward, the eyes and mouth wide. */
  function creature(p) {
    var s = tplShape(p); if (!s) return null;
    if (p.key === 'snout' || p.key === 'lipU' || p.key === 'bridge') s = [s[0], s[1] + 0.1, s[2] + 0.7];
    if (/^eye/.test(p.key)) s = [s[0] * 1.35, s[1], s[2] - 0.15];
    if (/^mouth/.test(p.key)) s = [s[0] * 1.25, s[1], s[2] + 0.2];
    return s;
  }

  X.run = async function (opt) {
    opt = opt || {};
    var plant = opt.plant || null, app = AL.app, R = K.mk(), MS = AL.maskScreen, MA = AL.mask;
    var realSnap = null, saved = K.ids(app.ch), real = { place: MA.place, carry: MA.carry, hide: MA.hide, addPoint: MA.addPoint, fitTurn: MA.fitTurn, solve: MA.solve, chip: MS.chip, edit: app.edit };
    K.sim(true);
    try {
      if (plant === 'nostay') MA.place = function (set, a, i, x, y) { real.place(set, a, i, x, y); var L = MA.data(set).mine[a] || []; var k = L.indexOf(i); if (k >= 0) L.splice(k, 1); real.carry(set); };
      if (plant === 'norefit') MA.carry = function (set) { return MA.solve(set); };
      if (plant === 'nohide') MA.hide = function () {};
      if (plant === 'noadd') MA.addPoint = function () { return -1; };
      if (plant === 'noturn') MA.fitTurn = function (cons, prior) { return { yaw: prior[0], pitch: prior[1], sure: false }; };
      if (plant === 'nolearn') MA.solve = function (set, o) { o = o || {}; o.noLearn = true; return real.solve(set, o); };
      if (plant === 'noundo') app.edit = function (fn) { fn(); AL.reindex(app.ch); app.touch(); };
      if (plant === 'noentry') MS.chip = function () { return document.createElement('span'); };

      AL.stages.go(app, 'setup'); app.select('head'); app.refreshPanel(true); app.userView = false; app.fit(true); await K.step(3);
      var set0 = MA.headSet(app.ch); if (!set0) throw new Error('no turning head on this character');
      if (MA.has(set0)) { MA.remove(set0); app.refreshPanel(true); await K.step(2); }
      // 1 the way in: the head's card, Set up and Motion
      var found = {};
      ['setup', 'motion'].forEach(function (s) { AL.stages.go(app, s); app.select('head'); app.refreshPanel(true); found[s] = !!button(/face mask/i); });
      R.F("1 Face mask is on the head's card, in Set up and in Motion", found.setup && found.motion, found);
      AL.stages.go(app, 'setup'); app.select('head'); app.refreshPanel(true); await K.step(2);
      var past0 = app.hist.past.length, chip = button(/face mask/i);
      if (chip) chip.click(); else MS.open(app);
      await K.step(3);
      var set = MA.headSet(app.ch), mk = MA.piece(set), W = app.maskMode, kids = MA.angles(set);
      var named = mk ? mk.pts.filter(function (p) { return p.key && p.name && p.face && p.face.length; }).length : 0;
      var rows = mk ? kids.every(function (k) { return mk.at[k.id] && mk.at[k.id].length === mk.pts.length; }) : false;
      var bad = AL.format.check(JSON.parse(JSON.stringify(app.ch, function (k, v) { return k === '_ix' ? undefined : v; }))).map(function (b) { return b.code; });
      R.F('1 putting the mask on gives the head 20 named points that follow your face, on every drawing, and the file stays valid', !!W && named === 20 && rows && !bad.length, { open: !!W, named: named, rows: rows, rules: bad.slice(0, 3) });
      R.F('1 ...as one undo step', app.hist.past.length === past0 + 1, { steps: app.hist.past.length - past0 });
      if (!W || !mk) throw new Error('the mask did not open');

      // 2 a point you place stays put (the owner: "when I would move one of the points that had a cyan circle around it, that would move all the points that I had just placed")
      var fa = W.angle, iBrow = idx(set, 'browRi'), iEyeL = idx(set, 'eyeL'), iEyeR = idx(set, 'eyeR'), iCheek = idx(set, 'cheekR');
      W.all = true; app.refreshPanel(true); await K.step(2);
      var b0 = drawn(app, iBrow), bTo = b0 && { x: b0.x - 9, y: b0.y - 12 }; drag(b0, bTo); await K.step(2);
      var b1 = drawn(app, iBrow), rowB = mk.at[fa][iBrow].slice();
      var c0 = drawn(app, iCheek), e0 = drawn(app, iEyeR), eTo = e0 && { x: e0.x - 22, y: e0.y + 14 }; drag(e0, eTo); await K.step(2);
      var e1 = drawn(app, iEyeR), c1 = drawn(app, iCheek);
      R.F('2 dragging a big handle puts it where the finger let go, and the points you have not placed follow', dist(e1, eTo) < 3 && dist(c0, c1) > 2, { handleOff: dist(e1, eTo), cheekMoved: dist(c0, c1) });
      var l0 = drawn(app, iEyeL); drag(l0, { x: l0.x + 18, y: l0.y - 10 }); await K.step(2);
      var b2 = drawn(app, iBrow), rowB2 = mk.at[fa][iBrow];
      R.F('2 ...and a point you placed stays exactly where you put it, however the handles move', dist(b1, bTo) < 3 && dist(b2, b1) < 0.5 && rowB2[0] === rowB[0] && rowB2[1] === rowB[1] && b2.mine,
        { placedOff: dist(b1, bTo), movedAfter: dist(b2, b1), solid: b2 && b2.mine });

      // 3 Hidden on this drawing (the far mouth end behind his muzzle)
      var iMouth = idx(set, 'mouthL'), m0 = drawn(app, iMouth); tap(m0); await K.step(1);
      var hb = button(/^Hidden$/); if (hb) hb.click(); await K.step(2);
      var m1 = drawn(app, iMouth), st = mk.at[fa][iMouth][2];
      R.F('3 Hidden on this drawing: the point is behind there, drawn dashed', !!hb && st === 'behind' && m1 && m1.dashed, { button: !!hb, state: st, dashed: m1 && m1.dashed });

      // 4 a point of your own (a horn tip)
      var n0 = mk.pts.length, ab = button(/^Add a point$/); if (ab) ab.click(); await K.step(1);
      var hornAt = { x: drawn(app, iEyeL).x + 40, y: drawn(app, iEyeL).y - 70 }; tap(hornAt); await K.step(2);
      var iH = mk.pts.length - 1, h1 = drawn(app, iH), every = kids.every(function (k) { return mk.at[k.id] && mk.at[k.id].length === mk.pts.length; });
      R.F('4 Add a point: a new numbered point where you tapped, yours there, a diamond, on every drawing', mk.pts.length === n0 + 1 && !!mk.pts[iH].mask && dist(h1, hornAt) < 3 && h1.mine && h1.diamond && every,
        { added: mk.pts.length - n0, off: dist(h1, hornAt), diamond: h1 && h1.diamond, everyRow: every });

      // 5 every drawing's REAL turn from its points (his Frontal is 25 degrees toward Left Profile, his Right Quarter face on)
      var front = kids.filter(function (k) { return k.name === 'Frontal'; })[0] || kids[0], rq = kids.filter(function (k) { return k.name === 'Right Quarter'; })[0] || kids[kids.length - 1];
      var lq = kids.filter(function (k) { return k.name === 'Left Quarter'; })[0] || kids[1];
      function placeAll(kid, pl, keys) { app.edit(function () { Object.keys(pl).forEach(function (i) { if (i === 'sc') return; if (keys && keys.indexOf(mk.pts[i].key) < 0) return; MA.place(set, kid.id, +i, pl[i][0], pl[i][1]); }); }, false, 'Place the mask'); }
      realSnap = MA.snapOf; MA.snapOf = function () { return {}; };           // the made-up placing alone (Topps's own slices would argue with it)
      [front, rq, lq].forEach(function (k) { app.edit(function () { MA.resetAngle(set, k.id); }, false, 'Reset'); });
      placeAll(front, synth(set, front, -25, 0, tplShape)); placeAll(rq, synth(set, rq, 0, 0, tplShape));
      var sol = MA.solve(set), tf = sol.angles[front.id].turn, tr = sol.angles[rq.id].turn;
      R.F("5 each drawing's real turn is worked out from its points, not its name or its dot on the pad", Math.abs(tf[0] + 25) <= 6 && Math.abs(tr[0]) <= 6 && Math.abs(tf[1]) <= 8,
        { frontal: tf, rightQuarter: tr });

      // 6 carried to a drawing you barely touched, the mask lands on its features: the creature's own shape is learned
      [front, rq, lq].forEach(function (k) { app.edit(function () { MA.resetAngle(set, k.id); }, false, 'Reset'); });
      placeAll(front, synth(set, front, -25, 0, creature)); placeAll(rq, synth(set, rq, 0, 0, creature));
      var truth = synth(set, lq, -55, 0, creature); placeAll(lq, truth, ['eyeL', 'eyeR', 'snout', 'chin']);
      function err(o) { var s = MA.solve(set, o).angles[lq.id], e = 0, n = 0; Object.keys(truth).forEach(function (i) { if (i === 'sc') return; var p = mk.pts[i]; if (!p.key || ['eyeL', 'eyeR', 'snout', 'chin'].indexOf(p.key) >= 0) return; var q = s.pts[i]; e += Math.hypot(q.x - truth[i][0], q.y - truth[i][1]); n++; }); return n ? e / n : 1e9; }
      var learned = err(), plain = real.solve === MA.solve ? err({ noLearn: true }) : learned;
      MA.snapOf = realSnap;
      R.F('6 carried to another drawing, the mask lands on its features: the creature’s own face is learned from your placing', learned < plain * 0.7 && learned < truth.sc * 0.25,
        { learned: Math.round(learned * 10) / 10, templateOnly: Math.round(plain * 10) / 10, faceSize: Math.round(truth.sc) });

      // 7 the head turn bends through the mask (its points are the head's landmarks)
      var pairs = AL.bridgeFor(app.ch, set, front, lq, '') || [], bl = AL.boundsOf(lq), inside = (mk.at[lq.id] || []).filter(function (q) { return q && q[2] !== 'off' && q[0] > bl.x0 - 20 && q[0] < bl.x1 + 20 && q[1] > bl.y0 - 20 && q[1] < bl.y1 + 20; }).length;
      R.F('7 the head turn bends through the mask: its points are landmarks on both drawings, on the drawing', pairs.length >= 10 && inside >= 10, { pairs: pairs.length, onDrawing: inside });

      // 8 one undo step each, and Undo puts a place back
      if (app.maskMode.angle !== front.id) MS.goTo(app, front.id);
      await K.step(2);                                             // (what is drawn now, after the placing above)
      var shown8 = MS.points(app).filter(function (q) { return q && q.shown && q.state !== 'off'; }), iC8 = iCheek, far8 = -1;
      shown8.forEach(function (q) { if (q.big || !q.p.key) return; var d = Math.min.apply(null, shown8.filter(function (o) { return o !== q; }).map(function (o) { return Math.hypot(o.x - q.x, o.y - q.y); })); if (d > far8) { far8 = d; iC8 = q.i; } });
      iCheek = iC8;                                                // a point no other sits on (a big handle wins where two overlap)
      var before8 = JSON.stringify(mk.at[front.id][iCheek]), past8 = app.hist.past.length, cz = drawn(app, iCheek);
      drag(cz, { x: cz.x + 15, y: cz.y + 15 }); await K.step(2);
      var steps8 = app.hist.past.length - past8, moved8 = JSON.stringify(mk.at[front.id][iCheek]) !== before8; app.undo(); await K.step(2);
      var set8 = MA.headSet(app.ch), back8 = JSON.stringify(MA.piece(set8).at[front.id][iCheek]);
      R.F('8 each drag is one undo step, and Undo puts the point back', steps8 === 1 && moved8 && back8 === before8, { steps: steps8, moved: moved8, back: back8 === before8 });

      var done = button(/^Done$/); if (done) done.click(); await K.step(2);
      R.F('9 Done closes the mask and the head shows as before', !app.maskMode, { open: !!app.maskMode });
    } catch (e) {
      R.fails.push({ label: 'error: ' + (e && e.message), info: e && e.stack });
    } finally {
      if (realSnap) MA.snapOf = realSnap;
      MA.place = real.place; MA.carry = real.carry; MA.hide = real.hide; MA.addPoint = real.addPoint; MA.fitTurn = real.fitTurn; MA.solve = real.solve; MS.chip = real.chip; app.edit = real.edit;
      if (app.maskMode) MS.close(app);
      K.restore(app, saved);
    }
    return { checks: R.n, fails: R.fails, note: R.note, plant: plant };
  };

  X.proof = async function () {
    var out = {}, want = { nostay: /^2 \.\.\.and a point you placed/, norefit: /^2 dragging a big handle/, nohide: /^3 Hidden/, noadd: /^4 Add a point/,
      noturn: /^5 each drawing/, nolearn: /^6 carried/, noundo: /^1 \.\.\.as one undo|^8 each drag/, noentry: /^1 Face mask is on/ };
    for (var k in want) { var r = await X.run({ plant: k }); out[k] = { caught: r.fails.some(function (f) { return want[k].test(f.label); }), fails: r.fails.map(function (f) { return f.label; }).slice(0, 4) }; }
    return out;
  };
})(typeof self !== 'undefined' ? self : this);
