/* AvatarLab: BREAK TEST, area 5: draw order (al-stack.js). Load after al-breaktest-fit.js.
 *
 * Drives the real buttons in the panel ("Back one", "Forward one", "Very back", "Very front", "Its own place" / "As when standing"),
 * on every part, standing and in a stance, then undoes. After every press it reads the order the stage really draws (stack.read) and checks:
 *   - nothing is lost or doubled (the same drawings, the same count, are on the stage)
 *   - Forward one moves the part later in the row and Back one earlier (until it is at the end)
 *   - Very front / Very back put it last / first
 *   - Its own place / As when standing gives back exactly the order it had before
 *   - undo and redo go to exactly the orders they were at
 *   - in a stance, leaving it gives back the standing order exactly; standing order is not changed by anything done in a stance
 */
(function (root) {
  'use strict';
  var AL = root.AL, T = AL.breakTest, K = T.kit, St = T.stack = {};
  function btn(label) { return Array.prototype.slice.call(document.querySelectorAll('#panelbody button')).filter(function (b) { return b.textContent.trim() === label; })[0] || null; }
  function units(app, n) { var r = AL.stack.read(app, n); return r.runs.map(function (q) { return q.u.id; }); }
  function idsDrawn(app) { return app.out.items.filter(function (i) { return !i.ghost && !i.xr; }).map(function (i) { return i.id; }); }
  function same(a, b) { return a.length === b.length && a.every(function (v, i) { return v === b[i]; }); }
  function sorted(a) { var seen = {}; return a.filter(function (v) { if (seen[v]) return false; seen[v] = 1; return true; }).sort().join(','); }
  /* what every OTHER part says about its own order: a press may make one of them let go of a rule that contradicts it (stack.settle) */
  function othersZ(app, id) { var o = {}; (app.ch.poses || []).forEach(function (p) { for (var k in (p.d || {})) if (k !== id && p.d[k].z) o[p.id + ':' + k] = p.d[k].z; }); AL.walk(app.ch.root, function (q) { if (q.id !== id && q.zo) o[q.id] = q.zo; }); return JSON.stringify(o); }
  /* parts that are behind this one by their own rule go to the back with it */
  function tiedBehind(app, id, uid) { var p = AL.fit.pose(app), z = p ? (p.d && p.d[uid] && p.d[uid].z) : (AL.node(app.ch, uid) || {}).zo; return !!(z && z.of === id && z.how === 'under'); }
  async function press(app, label) { var b = btn(label); if (!b) return false; b.click(); await K.raf(4); return true; }

  St.part = async function (app, R, spec, inStance, opt) {
    var id = spec.id, nm = spec.name + (inStance ? ' (in a stance)' : ' (standing)') + ': ', plant = opt.plant || '';
    function N() { return AL.node(app.ch, id); }
    var json0 = JSON.stringify(app.ch); K.stand(app); await K.raf(3);
    var pz = null; if (inStance) { pz = AL.stance.fours(app.ch); if (!pz) app.edit(function () { pz = AL.stance.add(app.ch, 'Order test'); }, true, 'add'); }
    var stand = idsDrawn(app), standU = units(app, N());
    app.tab = 'parts'; app.select(AL.zoneOf(app.ch, id), null, spec.layer ? id : null); if (inStance) { app.poseEdit = pz.id; app.poseAmt = null; } app.refreshPanel(); await K.raf(4);
    var z0 = idsDrawn(app), u0 = units(app, N()); if (!btn('Forward one')) { R.note.push(nm + 'no order buttons offered (nothing to be in front of, or hidden)'); K.restore(app, json0); return; }
    var multiset = sorted(z0), bad = 0, oz0 = othersZ(app, id);
    var ownZ = function () { var p = AL.fit.pose(app); return p ? ((p.d || {})[id] || {}).z || null : N().zo || null; }, own0 = ownZ();      // (a part may come with an order of its own for this stance)
    function mine() { var u = units(app, N()); return { first: u.indexOf(id), last: u.lastIndexOf(id), len: u.length, u: u }; }
    var p0 = mine();
    R.F(nm + 'the part is in its own row', p0.first >= 0, p0);
    var hist0 = app.hist.past.length;
    /* forward, one press at a time, to the end */
    var prev = p0, steps = 0, ok = true, stuck = false;
    for (var i = 0; i < 40; i++) { var pr = await press(app, 'Forward one'); if (!pr) break; var cur = mine(); if (sorted(idsDrawn(app)) !== multiset) { ok = false; R.F(nm + 'Forward one keeps every drawing on the stage', false, { step: i }); break; } if (K.snap(app).bad) bad++; if (cur.last > prev.last) { steps++; } else { stuck = cur.last >= cur.len - 1 ? 'at end' : 'stuck at ' + cur.last + ' of ' + cur.len; break; } prev = cur; }
    R.F(nm + 'Forward one walks it to the front, one at a time (' + steps + ' presses)', stuck === 'at end' || prev.last >= prev.len - 1, { stuck: stuck, last: prev.last, len: prev.len });
    var pF = mine();
    /* back to the very start */
    prev = pF; steps = 0; stuck = false;
    for (var j = 0; j < 40; j++) { var pr2 = await press(app, 'Back one'); if (!pr2) break; var cur2 = mine(); if (sorted(idsDrawn(app)) !== multiset) { R.F(nm + 'Back one keeps every drawing on the stage', false, { step: j }); break; } if (K.snap(app).bad) bad++; if (cur2.first < prev.first) steps++; else { stuck = cur2.first <= 0 ? 'at start' : 'stuck at ' + cur2.first; break; } prev = cur2; }
    R.F(nm + 'Back one walks it to the back, one at a time (' + steps + ' presses)', stuck === 'at start' || prev.first <= 0, { stuck: stuck, first: prev.first, len: prev.len });
    /* the two ends */
    var ends = !!btn('Very front');                              // (a layer inside a part is not offered the two ends)
    if (ends) {
    await press(app, 'Very front'); var pVF = mine(); if (plant === 'front') { N().zo = { how: 'back' }; await K.raf(3); pVF = mine(); }
    R.F(nm + 'Very front puts it last', pVF.last === pVF.len - 1, pVF);
    R.F(nm + 'Very front keeps every drawing', sorted(idsDrawn(app)) === multiset);
    await press(app, 'Very back'); var pVB = mine();
    R.F(nm + 'Very back puts it first (but for parts that are behind it by their own rule)', pVB.u.slice(0, pVB.first).every(function (u) { return tiedBehind(app, id, u); }), pVB);
    R.F(nm + 'Very back keeps every drawing', sorted(idsDrawn(app)) === multiset);
    await press(app, 'Very back');                                // pressing it again turns it off (toggle)
    var again = mine(); R.F(nm + 'Very back pressed again is off: back where it was', same(again.u, p0.u) || again.first !== 0 || p0.first === 0, { again: again.first, p0: p0.first });
    }
    /* back to its own place */
    var lbl = inStance ? 'As when standing' : 'Its own place'; await press(app, 'Very front'); var had = !!btn(lbl); var pr3 = await press(app, lbl); await K.raf(3);
    var pOwn = mine(), draws = idsDrawn(app);
    if (plant === 'own') { N().zo = { how: 'front' }; await K.raf(3); pOwn = mine(); draws = idsDrawn(app); }
    if (othersZ(app, id) !== oz0) R.note.push(nm + 'another part let go of a rule that contradicted a press, so "' + lbl + '" is not expected to give back the first order (undo does: checked below)');
    else if (own0) R.F(nm + lbl + ' forgets the order this part had of its own (so it is not the first order again: undo is, checked below)', !ownZ(), ownZ());
    else if (had || (!spec.layer)) R.F(nm + lbl + ' gives back exactly the first order', same(draws, z0) && same(pOwn.u, p0.u), { same_draw: same(draws, z0), before: p0.u.map(function (x) { return x.slice(-3); }).join(' '), after: pOwn.u.map(function (x) { return x.slice(-3); }).join(' ') });
    /* a few presses then undo all the way */
    await press(app, 'Forward one'); await press(app, 'Forward one'); await press(app, 'Back one'); await press(app, 'Very back'); var edited = mine(), drawEd = idsDrawn(app);
    var n0 = app.hist.past.length, k = 0; while (k < 60 && app.hist.past.length > hist0) { app.undo(false, true); k++; await K.raf(2); }
    await K.raf(3); var u1 = idsDrawn(app);
    R.F(nm + 'undo of every press gives back the first order', same(u1, z0), { undone: k, from: n0 - hist0 });
    var rd = 0; while (app.hist.future.length && rd < 60) { app.undo(true, true); rd++; await K.raf(2); } await K.raf(3);
    R.F(nm + 'redo of them all gives back the last order', same(idsDrawn(app), drawEd), { redone: rd });
    /* standing must be untouched after a stance's order was played with */
    if (inStance) { app.poseEdit = null; app.refreshPanel(); await K.raf(4); var back = idsDrawn(app); R.F(nm + 'leaving the stance gives back the standing order exactly', same(back, stand), { n: back.length, was: stand.length }); }
    R.F(nm + 'no bad numbers in any press', bad === 0, bad);
    K.restore(app, json0); await K.raf(3);
  };

  St.run = async function (opt) {
    opt = opt || {}; var app = AL.app, R = St.live = K.mk(), keep = JSON.stringify(app.ch);
    try {
      K.stand(app); await K.raf(3);
      var list = []; AL.ZONES.forEach(function (z) { var n = app.zoneNode(z.id); if (n) list.push({ id: n.id, name: n.name, layer: false }); });
      var head = AL.firstRole(app.ch.root, 'head'); if (head) { var c = 0; AL.walk(head, function (n, par, d) { if (d >= 2 && c < 4 && (n.type === 'art' || n.type === 'group') && K.cloud(app, n)) { c++; list.push({ id: n.id, name: n.name + ' (layer in the head)', layer: true }); } }); }
      if (opt.limit) list = list.slice(0, opt.limit); if (opt.names) list = list.filter(function (q) { return opt.names.indexOf(q.name) >= 0; });
      for (var i = 0; i < list.length; i++) for (var s = 0; s < (opt.stanceOnly ? 1 : 2); s++) { var inSt = opt.stanceOnly ? true : s === 1; if (inSt && list[i].layer && !opt.layersInStance) continue; await St.part(app, R, list[i], inSt, opt); }
    } catch (e) { R.fails.push({ label: 'THROWN', info: String(e && e.stack || e).slice(0, 600) }); }
    K.restore(app, keep); await K.raf(2); return R;
  };
})(typeof self !== 'undefined' ? self : this);
