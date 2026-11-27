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
 * stage 2 (your face drives the mask; a made-up face of 478 points goes in through the pilot's own camera code):
 *   nodrive   the face never reaches the mask's signals      nopass   the solver never bends the drawing by the mask
 *   noturnbend the face's movement is not turned to the drawing's real turn
 *   norecordmk a pass does not record the mask's lanes        nogain2  the All points dial does nothing
 * stage 3 (poses):
 *   nocapture  Make the face keeps nothing                    noposemove dragging a point while posing does nothing
 *   noblend    your face never says how much of a pose it holds  nocarrypose a pose stays on the drawing it was made on
 *   noswap     a strong pose never swaps a part's drawing      norecordmp a pass does not record the poses
 * stage 4 (across the head turn):
 *   noturnfollow  mid-turn, the mask bends where its points rest, not where the turn has carried them
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
  function cloud() {
    var p = new Float32Array(478 * 3), seed = 12345;
    function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
    for (var i = 0; i < 478; i++) { var a = rnd() * Math.PI * 2, r = 0.2 + 0.8 * rnd(); p[i * 3] = 0.5 + Math.cos(a) * r * 0.08; p[i * 3 + 1] = 0.6 + Math.sin(a) * r * 0.11; p[i * 3 + 2] = -0.06 * (1 - r * r); }
    [[33, -0.035, 0.57], [133, -0.012, 0.57], [263, 0.035, 0.57], [362, 0.012, 0.57]].forEach(function (e) { p[e[0] * 3] = 0.5 + e[1]; p[e[0] * 3 + 1] = e[2]; });   // eye corners where eyes are
    return p;
  }
  function browsUp(p) { var o = Float32Array.from(p); [107, 336, 70, 300, 9, 105, 334].forEach(function (i) { o[i * 3 + 1] -= 0.014; }); return o; }   // brows raised (no pose made for it here)
  function smileOf(p) { var o = Float32Array.from(p); [[61, -0.014, -0.01], [291, 0.014, -0.01]].forEach(function (q) { o[q[0] * 3] += q[1]; o[q[0] * 3 + 1] += q[2]; }); return o; }   // both mouth corners out and up
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
    var realSnap = null, unthrottle = null, saved = K.ids(app.ch), real = { place: MA.place, carry: MA.carry, hide: MA.hide, addPoint: MA.addPoint, fitTurn: MA.fitTurn, solve: MA.solve, chip: MS.chip, edit: app.edit,
      drive: MA.drive, bendFor: MA.bendFor, setGain: MA.setGain, swapsNow: MA.swapsNow, channels: app.inp.channels, cam: { on: app.inp.cam.on, mode: app.inp.cam.mode, stage: app.inp.cam._faceStage, lm: app.inp.cam.lm, v: app.inp.cam.v }, take: app.inp.take };
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

      // 10-13 (stage 2) your face drives the mask: a made-up face through the pilot's own camera code
      var inp = app.inp, F = AL.face, base = cloud(), now = { xyz: base }, t = 0, rawHead = { headYawDeg: 0, headPitchDeg: 0, headRollDeg: 0 };
      var fst = new F.Stage(F.calibration(), {}); for (var q0 = 0; q0 < 12; q0++) fst.process(rawHead, (t += 1 / 30), base);
      var keepCam = { on: inp.cam.on, mode: inp.cam.mode, stage: inp.cam._faceStage, lm: inp.cam.lm, v: inp.cam.v };
      function result(xyz) { var marks = []; for (var j = 0; j < 478; j++) marks.push({ x: xyz[j * 3], y: xyz[j * 3 + 1] / 0.75, z: xyz[j * 3 + 2] }); return { faceBlendshapes: [{ categories: [] }], facialTransformationMatrixes: [{ data: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1] }], faceLandmarks: [marks] }; }
      function arm() { inp.cam.on = true; inp.cam.mode = 'always'; inp.cam._faceStage = fst; inp.cam.v = { readyState: 4, videoWidth: 640, videoHeight: 480, paused: false, play: function () { return Promise.resolve(); } }; inp.cam.lm = { detectForVideo: function () { return result(now.xyz); } }; }
      unthrottle = function () { inp.cam.last = -1e9; }; app.hooks.push(unthrottle); arm();
      if (plant === 'nodrive') MA.drive = function () {};
      if (plant === 'nopass') MA.bendFor = function () { return null; };
      if (plant === 'noturnbend') MA.bendFor = function (set1, kid1, s1) { var f1 = set1.face, keep1 = f1.fit; f1.fit = {}; Object.keys(keep1).forEach(function (id) { f1.fit[id] = [0, 0, keep1[id][2], keep1[id][3]]; }); try { return real.bendFor(set1, kid1, s1); } finally { f1.fit = keep1; } };
      if (plant === 'nogain2') MA.setGain = function () {};
      if (plant === 'norecordmk') inp.channels = function () { return real.channels.call(this).filter(function (n) { return n.slice(0, 3) !== 'mk.'; }); };
      if (plant === 'norecordmp') inp.channels = function () { return real.channels.call(this).filter(function (n) { return n.slice(0, 3) !== 'mp.'; }); };
      set = MA.headSet(app.ch); mk = MA.piece(set);
      if (app.maskMode.angle !== front.id) MS.goTo(app, front.id);
      var tb = button(/^Test$/); if (tb) tb.click(); await K.step(3);
      function under(id) { return !!AL.ancestorWith(app.ch, id, function (k) { return k.id === front.id; }); }
      function moves(a, b2) { var o = {}; for (var j = 0; j < a.ids.length; j++) { var pid = a.ids[j].split('#')[0]; if (!under(pid)) continue; var k = b2.ids.indexOf(a.ids[j]); if (k < 0 || b2.v[k].length !== a.v[j].length) continue; for (var v = 0; v < a.v[j].length; v += 2) o[pid] = Math.max(o[pid] || 0, Math.hypot(a.v[j][v] - b2.v[k][v], a.v[j][v + 1] - b2.v[k][v + 1])); } return o; }
      function most(o) { return Math.max.apply(null, [0].concat(Object.keys(o).map(function (k) { return o[k]; }))); }
      now.xyz = base; await K.step(15); var s0 = K.snap(app); now.xyz = smileOf(base); await K.step(20); var s1 = K.snap(app), mv = moves(s0, s1), big = most(mv), slices = Object.keys(mv).filter(function (k) { return mv[k] > 1.5; }).length;
      // the outline away from the mouth (the frill) holds
      var bF = AL.boundsOf(front), mT = app.out.M[set.id], iT = AL.M.inv(mT), edge = 0, mouthW = [idx(set, 'mouthL'), idx(set, 'mouthR')].map(function (i) { var q = mk.at[front.id][i]; return AL.M.ap(mT, q[0], q[1]); });
      for (var j2 = 0; j2 < s0.ids.length; j2++) { var pid2 = s0.ids[j2].split('#')[0]; if (!under(pid2)) continue; var k2 = s1.ids.indexOf(s0.ids[j2]); if (k2 < 0 || s1.v[k2].length !== s0.v[j2].length) continue;
        for (var v2 = 0; v2 < s0.v[j2].length; v2 += 2) { var wx = s0.v[j2][v2], wy = s0.v[j2][v2 + 1], lp10 = AL.M.ap(iT, wx, wy), bw = bF.x1 - bF.x0, bh = bF.y1 - bF.y0;
          if (!(lp10[0] < bF.x0 + bw * 0.05 || lp10[0] > bF.x1 - bw * 0.05 || lp10[1] < bF.y0 + bh * 0.05 || lp10[1] > bF.y1 - bh * 0.05)) continue;
          if (mouthW.some(function (m0) { return Math.hypot(m0[0] - wx, m0[1] - wy) < Math.max(bw, bh) * 0.4; })) continue;
          edge = Math.max(edge, Math.hypot(wx - s1.v[k2][v2], wy - s1.v[k2][v2 + 1])); } }
      R.F('10 Test: your smile bends the head round the mouth, as one piece (several slices together), the outline held', !!tb && big > 2.5 && slices >= 3 && edge < big * 0.15,
        { button: !!tb, most: Math.round(big * 10) / 10, slices: slices, edge: Math.round(edge * 10) / 10 });
      // 11 turned to each drawing's real turn: on a profile, a smile's sideways pull mostly goes into the depth
      var lp = kids.filter(function (k) { return k.name === 'Left Profile'; })[0], S11 = app._bs || {}, iM = idx(set, 'mouthL'), iM2 = idx(set, 'mouthR');
      function side(kid) { var bf = MA.bendFor(set, kid, S11); if (!bf) return null; var row = mk.at[kid.id], k0 = 0, dx = 0, dy = 0; mk.pts.forEach(function (p, i) { var q = row[i]; if (!q || q[2] === 'off' || q[2] === 'behind') return; if (i === iM || i === iM2) { dx = Math.max(dx, Math.abs(bf.Q[k0][0] - bf.P[k0][0])); dy = Math.max(dy, Math.abs(bf.Q[k0][1] - bf.P[k0][1])); } k0++; }); return { dx: dx, dy: dy }; }
      var sf = side(front), sp = lp ? side(lp) : null;
      R.F("11 the face's movement is turned to each drawing's real turn (on a profile the sideways pull mostly goes into the depth)", !!sf && !!sp && sf.dx > 0.5 && sp.dx < sf.dx * 0.45, { frontal: sf, profile: sp });
      // 13 the All points dial
      MA.setGain && (app.edit(function () { MA.setGain(set, 0.5); }, false, 'All points')); now.xyz = base; await K.step(15); var h0 = K.snap(app); now.xyz = smileOf(base); await K.step(20); var half = most(moves(h0, K.snap(app)));
      app.edit(function () { MA.setGain(set, 1); }, false, 'All points');
      R.F('13 All points at half moves the head about half as far', half > big * 0.3 && half < big * 0.7, { full: Math.round(big * 10) / 10, half: Math.round(half * 10) / 10 });
      // 14-18 (stage 3) poses: make the face, pose him, blended live, carried, a drawing swapped
      var MP = AL.maskPoses, realMP = { capture: MP.capture, move: MP.move, weights: MP.weights, carry: MP.carry };
      if (plant === 'nocapture') MP.capture = function () { return false; };
      if (plant === 'noposemove') MP.move = function () {};
      if (plant === 'noblend') MP.weights = function (set2, m2) { return { w: {}, rest: m2 }; };
      if (plant === 'nocarrypose') MP.carry = function (set2) { realMP.carry(set2); var f2 = set2.face; Object.keys(f2.posed || {}).forEach(function (id) { var pz = MP.get(set2, id); Object.keys(f2.posed[id]).forEach(function (a) { if (!pz || !pz.at[a]) delete f2.posed[id][a]; }); }); };
      if (plant === 'noswap') MA.swapsNow = function () { return null; };
      try {
        var pb2 = button(/^Poses$/); if (pb2) pb2.click(); await K.step(2);
        var openB = button(/^Open$/); if (openB) openB.click(); await K.step(2);
        var pose = app.maskMode.pose && MP.get(MA.headSet(app.ch), app.maskMode.pose);
        now.xyz = smileOf(base); await K.step(20);
        if (pose) { MS.capture(app, pose.id); for (var w14 = 0; w14 < 40 && app.maskMode && app.maskMode.capturing !== null; w14++) await new Promise(function (r) { setTimeout(r, 200); }); await K.step(2); }   // (the count is in real time)
        set = MA.headSet(app.ch); mk = MA.piece(set); pose = pose && MP.get(set, pose.id);
        var iML = idx(set, 'mouthL'), nML = mk.pts[iML].n, faceKept = pose && pose.face ? Math.hypot(pose.face[nML][0], pose.face[nML][1]) : 0;
        var rawNow = app.inp._mkRaw || {}, nz = pose && pose.face ? Object.keys(pose.face).filter(function (k) { return Math.abs(pose.face[k][0]) + Math.abs(pose.face[k][1]) > 0.002; }) : [];
        R.F('14 Make the face keeps how your face points moved (the mouth ends, for a smile)', !!pose && faceKept > 0.01, { pose: pose && pose.id, mouthEnd: faceKept, nML: nML, kept: nz, rawMouth: rawNow[nML], rawKeys: Object.keys(rawNow).length, sel: app.maskMode && app.maskMode.pose, test: !!(app.maskMode && app.maskMode.tryHook) });
        // 15 pose him: drag his mouth end; the pose (not the mask) keeps the move
        now.xyz = base; var stopB = button(/^Stop$/); if (stopB) stopB.click(); await K.step(3);
        var restRow = mk.at[front.id][iML].slice(), d0 = drawn(app, iML), dTo = d0 && { x: d0.x + 14, y: d0.y - 11 }; drag(d0, dTo); await K.step(3);
        set = MA.headSet(app.ch); mk = MA.piece(set); pose = MP.get(set, pose.id);
        var mv15 = pose.at[front.id] && pose.at[front.id][nML], d1 = drawn(app, iML), rowSame = mk.at[front.id][iML][0] === restRow[0] && mk.at[front.id][iML][1] === restRow[1];
        R.F('15 posing: drag his point and the pose keeps the move (the mask stays where you placed it), drawn where you let go', !!mv15 && dist(d1, dTo) < 3 && rowSame && Math.abs(mv15[0] - 14 / app.view.s) < 1.5,
          { move: mv15, off: dist(d1, dTo), maskKept: rowSame });
        // 16 live: your face says how much of the pose it holds; the head takes YOUR pose (not your face's own movement)
        var tb3 = button(/^Test$/); if (tb3) tb3.click(); now.xyz = smileOf(base); await K.step(25);
        var w1 = (app._bs || {})['mp.' + pose.id] || 0, bf16 = MA.bendFor(set, front, app._bs || {}), k16 = -1, c16 = 0;
        mk.pts.forEach(function (pp, i) { var q = mk.at[front.id][i]; if (!q || q[2] === 'off' || q[2] === 'behind') return; if (i === iML) k16 = c16; c16++; });
        var got = bf16 && k16 >= 0 ? [bf16.Q[k16][0] - bf16.P[k16][0], bf16.Q[k16][1] - bf16.P[k16][1]] : [0, 0], want = mv15 ? [mv15[0] * w1, mv15[1] * w1] : [0, 0];
        var smF = smileOf(base), half = base.map(function (v, i) { return v + (smF[i] - v) * 0.5; }); now.xyz = half; await K.step(25);
        var wHalf = (app._bs || {})['mp.' + pose.id] || 0;
        R.F('16 live, your smile says how much of the pose it holds, and the head takes your pose', w1 > 0.8 && wHalf > 0.3 && wHalf < 0.7 && Math.hypot(got[0] - want[0], got[1] - want[1]) < Math.hypot(want[0], want[1]) * 0.35 + 0.5,
          { full: Math.round(w1 * 100) / 100, half: Math.round(wHalf * 100) / 100, got: got.map(function (v) { return Math.round(v * 10) / 10; }), want: want.map(function (v) { return Math.round(v * 10) / 10; }) });
        // 17 carried to a drawing it was not made on
        var onLq = set.face.posed && set.face.posed[pose.id] && set.face.posed[pose.id][lq.id] && set.face.posed[pose.id][lq.id][nML];
        R.F('17 a pose made on one drawing is carried to the others (his mouth end moves on Left Quarter too)', !!onLq && Math.hypot(onLq[0], onLq[1]) > Math.hypot(mv15[0], mv15[1]) * 0.3, { leftQuarter: onLq, frontal: mv15 });
        // 18 swap a drawing: while the smile is strong, his mouth shows his smile drawing
        var mouthSet = MP.swappable(front)[0], drawnNow = function (k) { return (app.out.items || []).some(function (it) { return !it.ghost && !!AL.ancestorWith(app.ch, it.id, function (q) { return q.id === k.id; }); }); };
        now.xyz = base; await K.step(25);
        var target = mouthSet && (mouthSet.kids || []).filter(function (k) { return !drawnNow(k); })[0];
        var chipT = target && Array.prototype.filter.call(document.querySelectorAll('#panelbody button'), function (b2) { return b2.textContent.trim() === (target.name || target.shape); })[0];
        if (chipT) chipT.click(); await K.step(2);
        now.xyz = smileOf(base); await K.step(25); var shownSmile = target ? drawnNow(target) : false;
        now.xyz = base; await K.step(25); var shownRest = target ? drawnNow(target) : true;
        set = MA.headSet(app.ch); var lqMouth = MP.swappable(lq)[0], carriedSwap = !!(set.face.swapped && set.face.swapped[pose.id] && lqMouth && set.face.swapped[pose.id][lqMouth.id]);
        R.F('18 swap a drawing: while the pose is strong his mouth shows the drawing you chose, and the other drawings take it too', !!target && !!chipT && shownSmile && !shownRest && carriedSwap,
          { part: mouthSet && mouthSet.name, drawing: target && (target.name || target.shape), whenStrong: shownSmile, atRest: shownRest, carried: carriedSwap });
        var tb4 = button(/^Stop$/); if (tb4) tb4.click(); await K.step(2);
        // 20 (stage 4) mid-turn the pose bends the mouth where the turn has carried it (the head turning from Frontal toward Left Quarter)
        if (plant === 'noturnfollow') MA.turnFollow = false;
        var sol20 = new AL.Solver(app.ch), S20 = AL.restSignals(), live20 = { live: true, dt: 1 / 30 }, kidIds = {};
        AL.walk(front, function (k) { kidIds[k.id] = 1; });
        function ev(turn, w) { S20.turnX = turn; S20['mp.' + pose.id] = w; var o; for (var e = 0; e < 6; e++) o = sol20.eval(S20, live20); return o; }
        function pick(o, test) { var m = {}; o.items.forEach(function (it, ii) { if (!it.ghost && kidIds[it.id] && test(it)) m[it.id + '#' + ii] = it.v; }); return m; }
        function peak(a, b2) {                                     // the middle of what the pose moved (weighted by how far), among the points moved at least half the most
          var L = [], bd = 0; Object.keys(a).forEach(function (k) { var v0 = a[k], v1 = b2[k]; if (!v1 || v1.length !== v0.length) return; for (var j = 0; j < v0.length; j += 2) { var d = Math.hypot(v1[j] - v0[j], v1[j + 1] - v0[j + 1]); bd = Math.max(bd, d); L.push([v0[j], v0[j + 1], d]); } });
          var x = 0, y = 0, w = 0; L.forEach(function (q) { if (q[2] < bd * 0.5) return; x += q[0] * q[2]; y += q[1] * q[2]; w += q[2]; }); return w ? [x / w, y / w] : null;
        }
        var mouthIds = {}; AL.walk(MP.swappable(front)[0] || front, function (k) { mouthIds[k.id] = 1; });
        function centreOf(o) { var x = 0, y = 0, n = 0; o.items.forEach(function (it) { if (it.ghost || !mouthIds[it.id] || !kidIds[it.id]) return; for (var j = 0; j < it.v.length; j += 2) { x += it.v[j]; y += it.v[j + 1]; n++; } }); return n ? [x / n, y / n] : null; }
        var a0 = ev(0, 0), m0 = centreOf(a0), p0 = peak(pick(a0, function () { return true; }), pick(ev(0, 1), function () { return true; }));
        var turn20 = (lq.at ? lq.at[0] : -0.5) * 0.45, b0 = ev(turn20, 0), m1 = centreOf(b0), p1 = peak(pick(b0, function () { return true; }), pick(ev(turn20, 1), function () { return true; }));
        var T = m0 && m1 ? [m1[0] - m0[0], m1[1] - m0[1]] : [0, 0], Dp = p0 && p1 ? [p1[0] - p0[0], p1[1] - p0[1]] : [0, 0], tl = Math.hypot(T[0], T[1]);
        MA.turnFollow = true;
        R.F('20 mid-turn, your pose bends the mouth where the turn has carried it', tl > 3 && Math.hypot(Dp[0] - T[0], Dp[1] - T[1]) < tl * 0.5,
          { turnMovedMouth: T.map(function (v) { return Math.round(v * 10) / 10; }), poseMoved: Dp.map(function (v) { return Math.round(v * 10) / 10; }) });
      } finally { MP.capture = realMP.capture; MP.move = realMP.move; MP.weights = realMP.weights; MP.carry = realMP.carry; }

      // 12 a pass records the mask's lanes and plays them back with the camera off
      var sb = button(/^Stop$/); if (sb) sb.click(); await K.step(2);
      MS.close(app); AL.stages.go(app, 'perform'); arm(); inp.take = null; now.xyz = base; await K.step(10);
      inp.record(); await K.step(8); now.xyz = smileOf(base); await K.step(15); now.xyz = browsUp(base); await K.step(15); now.xyz = base; await K.step(5); inp.stop();
      var lane = null, lanes = inp.take ? Object.keys(inp.take.ch).filter(function (n) { return n.slice(0, 3) === 'mk.'; }) : [];   // (raised brows: no pose explains them, so they ride the mask's own lanes)
      lanes.forEach(function (n) { if (!lane && inp.take.ch[n].some(function (v) { return v != null && Math.abs(v) > 0.005; })) lane = n; });
      inp.cam.on = false; inp.play(); var seen = 0; for (var q1 = 0; q1 < 40; q1++) { await K.step(1); if (lane) seen = Math.max(seen, Math.abs(inp.S[lane] || 0)); } inp.stop();
      R.F('12 a pass records the mask and plays it back with the camera off', !!lane && seen > 0.005, { lanes: lanes.length, moving: lane, played: seen });
      var mpl = inp.take ? Object.keys(inp.take.ch).filter(function (n) { return n.slice(0, 3) === 'mp.' && inp.take.ch[n].some(function (v) { return v != null && v > 0.2; }); }) : [];
      R.F('19 ...and records how much of each pose your face held', mpl.length > 0, { poseLanes: mpl });
      inp.cam.on = keepCam.on; inp.cam.mode = keepCam.mode; inp.cam._faceStage = keepCam.stage; inp.cam.lm = keepCam.lm; inp.cam.v = keepCam.v;
      AL.stages.go(app, 'setup'); app.select('head'); app.refreshPanel(true); MS.open(app); await K.step(2);

      var done = button(/^Done$/); if (done) done.click(); await K.step(2);
      R.F('9 Done closes the mask and the head shows as before', !app.maskMode, { open: !!app.maskMode });
    } catch (e) {
      R.fails.push({ label: 'error: ' + (e && e.message), info: e && e.stack });
    } finally {
      if (realSnap) MA.snapOf = realSnap;
      MA.place = real.place; MA.carry = real.carry; MA.hide = real.hide; MA.addPoint = real.addPoint; MA.fitTurn = real.fitTurn; MA.solve = real.solve; MS.chip = real.chip; app.edit = real.edit;
      MA.drive = real.drive; MA.bendFor = real.bendFor; MA.setGain = real.setGain; MA.swapsNow = real.swapsNow; app.inp.channels = real.channels;
      if (unthrottle && app.hooks.indexOf(unthrottle) >= 0) app.hooks.splice(app.hooks.indexOf(unthrottle), 1);
      var c0 = app.inp.cam; c0.on = real.cam.on; c0.mode = real.cam.mode; c0._faceStage = real.cam.stage; c0.lm = real.cam.lm; c0.v = real.cam.v; app.inp.take = real.take;
      if (app.maskMode) MS.close(app);
      K.restore(app, saved);
    }
    return { checks: R.n, fails: R.fails, note: R.note, plant: plant };
  };

  X.proof = async function (only) {
    var out = {}, want = { nostay: /^2 \.\.\.and a point you placed/, norefit: /^2 dragging a big handle/, nohide: /^3 Hidden/, noadd: /^4 Add a point/,
      noturn: /^5 each drawing/, nolearn: /^6 carried/, noundo: /^1 \.\.\.as one undo|^8 each drag/, noentry: /^1 Face mask is on/,
      nodrive: /^10 Test/, nopass: /^10 Test/, noturnbend: /^11 the face/, norecordmk: /^12 a pass/, nogain2: /^13 All points/,
      nocapture: /^14 Make the face/, noposemove: /^15 posing/, noblend: /^16 live/, nocarrypose: /^17 a pose made/, noswap: /^18 swap/, norecordmp: /^19 /,
      noturnfollow: /^20 mid-turn/ };
    for (var k in want) { if (only && only.indexOf(k) < 0) continue; var r = await X.run({ plant: k }); out[k] = { caught: r.fails.some(function (f) { return want[k].test(f.label); }), fails: r.fails.map(function (f) { return f.label; }).slice(0, 4) }; }
    return out;
  };
})(typeof self !== 'undefined' ? self : this);
