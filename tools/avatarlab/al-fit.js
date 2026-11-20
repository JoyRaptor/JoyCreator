/* AvatarLab: fitting a part by hand.
 *
 * Three ways to put a piece where it belongs, on any layer, folder, head angle or whole stance:
 *
 *   PICK A PIECE   tap the picture and the very layer under the finger is chosen; everything else steps back.
 *   THE BOX        drag inside it to move, a corner to resize, the round handle below it to turn (the same box
 *                  as in the Studio and JoyBrush). Nothing is repainted: the part remembers a move, a turn and
 *                  a size, and "Put it back" clears them.
 *   RESHAPE        tap the part to drop pins on it, then drag a pin: the part stretches from there, and the pins
 *                  you leave alone hold the rest still. (It is a Shape that is always on, so it also blends.)
 *
 * While a STANCE is being edited, the box writes to the stance instead: "in this stance, this part sits here".
 * Phone: the shared transform box; the numbers are the same four per part (x, y, turn, size).
 */
(function (root) {
  'use strict';
  var AL = root.AL, M = AL.M, fit = AL.fit = {};
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function round1(v) { return Math.round(v * 10) / 10; }

  /** The stance being edited, if any. */
  fit.pose = function (app) { var id = app.poseEdit, ps = app.ch.poses || []; if (!id) return null; for (var i = 0; i < ps.length; i++) if (ps[i].id === id) return ps[i]; return null; };
  /** Where a part's move, turn and size are kept right now: on the part, or in the stance being edited. */
  fit.get = function (app, n) {
    var p = fit.pose(app);
    if (p) { var d = (p.d && p.d[n.id]) || {}; return { x: d.x || 0, y: d.y || 0, rot: d.rot || 0, sc: d.sc == null ? 1 : d.sc }; }
    return { x: n.dx || 0, y: n.dy || 0, rot: n.rot || 0, sc: n.sc == null ? 1 : n.sc };
  };
  fit.put = function (app, n, v) {
    var p = fit.pose(app);
    if (p) { var d = (p.d = p.d || {})[n.id] || (p.d[n.id] = {}); d.x = round1(v.x); d.y = round1(v.y); d.rot = round1(v.rot); d.sc = Math.round(v.sc * 1000) / 1000; return; }
    n.dx = round1(v.x); n.dy = round1(v.y); n.rot = round1(v.rot); n.sc = Math.round(v.sc * 1000) / 1000;
    if (!n.dx) delete n.dx; if (!n.dy) delete n.dy;
  };
  fit.moved = function (app, n) { var v = fit.get(app, n), p = fit.pose(app), d = p && p.d ? p.d[n.id] : null; if (d && d.auto) return Math.abs(v.x - (d.autoX || 0)) > 0.05 || Math.abs(v.y - (d.autoY || 0)) > 0.05 || Math.abs(v.rot - (d.autoRot || 0)) > 0.05 || v.sc !== 1; return !!(v.x || v.y || v.rot || v.sc !== 1); };
  fit.reset = function (app, n) { var p = fit.pose(app); if (p) { var d = p.d && p.d[n.id]; if (d) { d.x = d.auto ? d.autoX || 0 : 0; d.y = d.auto ? d.autoY || 0 : 0; d.rot = d.auto ? d.autoRot || 0 : 0; delete d.sc; if (!d.auto && !d.z) delete p.d[n.id]; } } else { delete n.dx; delete n.dy; delete n.rot; delete n.sc; } };
  /** A part needs a point to turn and resize about. A drawing has its middle; a folder gets one the first time it is needed. */
  fit.needPivot = function (app, n) {
    if (n.pivot || n.type === 'art') return;
    var par = AL.parentOf(app.ch, n.id), b = AL.boundsOf(n.type === 'mirror' ? AL.node(app.ch, n.of) : n);
    var p = (par && par.type === 'set' && par.pick && par.pick.by === 'turn' && par.pivot) ? par.pivot : (b ? { x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 } : null);
    if (p) n.pivot = { x: p.x, y: p.y };
  };
  fit.can = function (app, n) {
    if (!n || n.type === 'mirror') return false;
    if (n.limb) return false;
    var par = AL.parentOf(app.ch, n.id); if (par && par.pick && par.pick.by === 'reach') return false;
    return !(n.type === 'set' && n.pick && n.pick.by === 'reach' && !fit.pose(app));      // a limb is slid with "Move the whole limb"; in a stance it can be moved like anything else
  };

  /**
   * The box, as pins for the stage. Every handle works out the part's new numbers from where it was when the finger went
   * down, so nothing drifts however long the drag is.
   */
  fit.pins = function (app, n) {
    var out = app.out, M0 = out && out.M[n.id], B0 = out && out.B && out.B[n.id], b = AL.boundsOf(n); if (!M0 || !B0 || !b) return [];
    var v0 = fit.get(app, n), inv = M.inv(B0), pv = n.pivot || (n.type === 'art' ? { x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 } : { x: 0, y: 0 });
    var cx = (b.x0 + b.x1) / 2, cy = (b.y0 + b.y1) / 2, sgn = (B0[0] * B0[3] - B0[1] * B0[2]) < 0 ? -1 : 1;
    function par(x, y) { var w = M.ap(M0, x, y); return M.ap(inv, w[0], w[1]); }           // a point of the part, in the space its move is measured in
    function parOf(pin, x, y) { var w = M.ap(pin.m, x, y); return M.ap(inv, w[0], w[1]); }
    var c0 = par(cx, cy), p0 = par(pv.x, pv.y), list = [];
    var vs = app.view.s, half = Math.max(18, Math.min(b.x1 - b.x0, b.y1 - b.y0) * vs * 0.5 * Math.abs(v0.sc));
    list.push({ key: 'fx:m', kind: 'move', undo: 'Move ' + n.name, x: cx, y: cy, node: n.id, rad: Math.min(half, 90), prio: -40,
      box: [[b.x0, b.y0], [b.x1, b.y0], [b.x1, b.y1], [b.x0, b.y1]],
      set: function (x, y) { var q = parOf(this, x, y); fit.put(app, n, { x: v0.x + q[0] - c0[0], y: v0.y + q[1] - c0[1], rot: v0.rot, sc: v0.sc }); } });
    [[b.x0, b.y0], [b.x1, b.y0], [b.x1, b.y1], [b.x0, b.y1]].forEach(function (k, i) {
      var k0 = par(k[0], k[1]), r0 = Math.hypot(k0[0] - p0[0], k0[1] - p0[1]) || 1;
      list.push({ key: 'fx:c' + i, kind: 'corner', undo: 'Resize ' + n.name, x: k[0], y: k[1], node: n.id, rad: 20, prio: 6,
        set: function (x, y) { var q = parOf(this, x, y); fit.put(app, n, { x: v0.x, y: v0.y, rot: v0.rot, sc: clamp(v0.sc * Math.hypot(q[0] - p0[0], q[1] - p0[1]) / r0, 0.08, 8) }); } });
    });
    var hx = cx, hy = b.y1, h0 = par(hx, hy), a0 = Math.atan2(h0[1] - p0[1], h0[0] - p0[0]);
    if (Math.hypot(h0[0] - p0[0], h0[1] - p0[1]) < 6) { hx = b.x1; hy = cy; h0 = par(hx, hy); a0 = Math.atan2(h0[1] - p0[1], h0[0] - p0[0]); }      // the pivot sits on the bottom edge: turn from the side instead
    list.push({ key: 'fx:r', kind: 'turn', undo: 'Turn ' + n.name, x: hx, y: hy, node: n.id, oy: 30, rad: 22, prio: 8,
      set: function (x, y) { var q = parOf(this, x, y), d = Math.atan2(q[1] - p0[1], q[0] - p0[0]) - a0; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; fit.put(app, n, { x: v0.x, y: v0.y, rot: v0.rot + d * 180 / Math.PI, sc: v0.sc }); } });
    if (n.pivot && !fit.pose(app)) list.push({ key: 'fx:p', label: 'Turns about', small: true, color: '#fbbf24', x: n.pivot.x, y: n.pivot.y, node: n.id, prio: 10, set: function (x, y) { n.pivot.x = round1(x); n.pivot.y = round1(y); } });
    return list;
  };

  /** Choose the very layer that was tapped, show it alone, and put the box on it. */
  fit.pick = function (app, id) {
    var ch = app.ch, n = AL.node(ch, id); if (!n) return;
    var limbSet = AL.ancestorWith(ch, id, function (a) { return a.type === 'set' && a.pick && a.pick.by === 'reach'; });
    if (limbSet && !fit.pose(app)) { app.pickPiece = false; app.selectByNode(id); return; }                  // a limb opens its own card (pins, drawings)
    if (limbSet) n = limbSet;                                                                                // in a stance, the whole limb is the piece
    var keep = app.poseEdit;
    app.tab = 'parts'; app.select(AL.zoneOf(ch, n.id), null, n.id); app.poseEdit = keep;
    if (fit.can(app, n)) { if (!n.pivot && n.type !== 'art') app.edit(function () { fit.needPivot(app, n); }, true, 'A point to turn about'); app.fitOn = { node: n.id, box: true }; }
    app.refreshPanel();
  };
  fit.boxOn = function (app, n, on) {
    if (on) { if (!n.pivot && n.type !== 'art') app.edit(function () { fit.needPivot(app, n); }, true, 'A point to turn about'); app.fitOn = { node: n.id, box: true }; app.shapeEdit = null; } else app.fitOn = null;
    app.refreshPanel(true);
  };

  /* ── reshape with pins ── */
  var FIX = 'fix';
  fit.fixShape = function (n) { return (n.shapes || []).filter(function (q) { return q.id === FIX; })[0] || null; };
  fit.reshapeOn = function (app, n, on) {
    if (!on) { app.fitOn = null; app.shapeEdit = null; app.refreshPanel(true); return; }
    if (!n.mesh || !n.mesh.pins || !fit.fixShape(n)) app.edit(function () {
      n.mesh = n.mesh || { pins: [], quiet: true }; n.mesh.pins = n.mesh.pins || [];
      if (!fit.fixShape(n)) (n.shapes = n.shapes || []).unshift({ id: FIX, name: 'Reshaped', sig: 'always', amt: 1, d: {} });
    }, true, 'Reshape with pins');
    var w = {}; w[n.id] = {}; w[n.id][FIX] = 1; app.shapeEdit = { node: n.id, shape: FIX, w: w }; app.fitOn = { node: n.id, pins: true };
    app.refreshPanel(true);
  };
  /** A tap on the part while reshaping: a new pin there. Returns true when it took the tap. */
  fit.tapPin = function (app, c) {
    var n = AL.node(app.ch, app.fitOn.node), m = n && app.out.M[n.id]; if (!n || !m || !n.mesh) return false;
    var hit = AL.hit(app.out, c[0], c[1], null, app.selIds); if (!hit) { app.toast('Tap on the part itself to drop a pin.'); return true; }
    var l = M.ap(M.inv(m), c[0], c[1]);
    app.edit(function () { n.mesh.pins.push({ id: AL.id('p'), name: 'Pin ' + (n.mesh.pins.length + 1), x: Math.round(l[0]), y: Math.round(l[1]) }); }, false, 'Add a pin');
    return true;
  };

  /**
   * THE FACE STRETCHES AS IT TALKS. A small mouth drawn on a side view opens only a little; this lets the lower face go with it.
   * It is nothing new: a Shape called "Talking" on the face drawing, driven by the mouth, with one pin on the jaw that drops and
   * four that hold the snout, cheek and brow still. Open the face's Shapes to change it. One head angle at a time.
   */
  fit.jawParts = function (kid) {
    var face = AL.find(kid, function (n) { return n.type === 'art' && (n.role === 'face' || /^face$/i.test(n.name)); }), chin = AL.find(kid, function (n) { return n.role === 'jaw'; }), mo = AL.find(kid, function (n) { return n.type === 'art' && (n.hinge || n.squash); });
    return face && chin && mo ? { face: face, chin: chin, mouth: mo } : null;
  };
  fit.hasJawDrop = function (kid) { var p = fit.jawParts(kid); return !!(p && (p.face.shapes || []).some(function (q) { return q.jaw; })); };
  fit.jawDrop = function (kid, on, pull) {
    var p = fit.jawParts(kid); if (!p) return false; var face = p.face;
    if (!on) { var sh0 = (face.shapes || []).filter(function (q) { return q.jaw; })[0]; if (sh0) { face.shapes = face.shapes.filter(function (q) { return q !== sh0; }); if (!face.shapes.length) delete face.shapes; if (face.mesh) face.mesh.pins = face.mesh.pins.filter(function (q) { return !q.jawPin; }); } return true; }
    if (fit.hasJawDrop(kid)) return true;
    var b = AL.boundsOf(face), cb = AL.boundsOf(p.chin), mb = AL.boundsOf(p.mouth), jx = (cb.x0 + cb.x1) / 2, jy = (cb.y0 + cb.y1) / 2, my = (mb.y0 + mb.y1) / 2, top = Math.min(my - 14, b.y0 + (b.y1 - b.y0) * 0.45), back = jx > (b.x0 + b.x1) / 2 ? -1 : 1;
    face.mesh = face.mesh || { pins: [], quiet: true }; face.mesh.pins = face.mesh.pins || []; face.mesh.quiet = true;
    function pin(name, x, y) { var q = { id: AL.id('p'), name: name, x: Math.round(x), y: Math.round(y), jawPin: true }; face.mesh.pins.push(q); return q; }
    pin('Holds the snout', mb.x0 - 4 + (mb.x1 - mb.x0) * 0.1, my - 12); pin('Holds the cheek', mb.x1 + 10, my - 10); pin('Holds the brow', (b.x0 + b.x1) / 2, top - 10); pin('Holds the back', jx + back * (b.x1 - b.x0) * 0.35, jy - 6);
    var j = pin('Jaw', jx, jy + 2), d = {}; d[j.id] = [0, pull || 8];
    (face.shapes = face.shapes || []).push({ id: AL.id('s'), name: 'Talking', sig: 'mouth', amt: 1, d: d, jaw: true });
    if (p.chin.links && p.chin.links[0] && p.chin.links[0].sig === 'mouth' && p.chin.links[0].amt < (pull || 8)) p.chin.links[0].amt = pull || 8;
    return true;
  };

  /** In a stance a part may sit in front of, or behind, something it is not in front of when standing. */
  fit.orderRow = function (app, pb, n, pose) {
    var ui = app.ui, ch = app.ch, d = pose.d && pose.d[n.id], z = d && d.z, tn = z && z.of ? AL.node(ch, z.of) : null, ask = app.orderPick && app.orderPick.node === n.id ? app.orderPick : null;
    function set(v) { app.orderPick = null; app.edit(function () { var e = (pose.d = pose.d || {})[n.id] || (pose.d[n.id] = {}); if (v) e.z = v; else { delete e.z; if (!e.auto && !e.x && !e.y && !e.rot && (e.sc == null || e.sc === 1) && !e.orbit) delete pose.d[n.id]; } }, false, 'In front or behind, in ' + pose.name); }
    function pick(how) { app.orderPick = ask && ask.how === how ? null : { node: n.id, how: how }; app.pickPiece = false; app.refreshPanel(true); }
    pb.appendChild(ui.lab('In front or behind, in this stance'));
    pb.appendChild(ui.row([
      ui.chip('As when standing', { on: !z && !ask, title: 'The same order as when it stands', fn: function () { set(null); } }),
      ui.chip(z && z.how === 'under' && tn ? 'Behind ' + tn.name : 'Behind a part…', { on: !!(z && z.how === 'under') || !!(ask && ask.how === 'under'), icon: 'target', title: 'Then tap the part on the picture that this should pass behind', fn: function () { pick('under'); } }),
      ui.chip(z && z.how === 'over' && tn ? 'In front of ' + tn.name : 'In front of a part…', { on: !!(z && z.how === 'over') || !!(ask && ask.how === 'over'), icon: 'target', title: 'Then tap the part on the picture that this should pass in front of', fn: function () { pick('over'); } }),
      ui.chip('In front of everything', { on: !!(z && z.how === 'front'), fn: function () { set({ how: 'front' }); } }),
      ui.chip('Behind everything', { on: !!(z && z.how === 'back'), fn: function () { set({ how: 'back' }); } })
    ]));
    if (ask) pb.appendChild(ui.note('<b>Tap the part on the picture</b> that this should be ' + (ask.how === 'under' ? 'behind' : 'in front of') + '. Tapping the head means the whole head.'));
    else if (z) pb.appendChild(ui.slider('Changes over at', { min: 5, max: 95, value: Math.round((z.at == null ? 0.5 : z.at) * 100), set: function (v) { z.at = v / 100; }, fmt: function (v) { return v + '% of the way in'; }, title: 'How far into the stance the order changes. Pick the moment the two parts are not touching and it cannot be seen.' }));
    app.orderSet = set;
  };
  /** The tap that answers "behind which part?": the whole head, body or limb under the finger, or the layer itself. */
  fit.orderTap = function (app, id) {
    var ch = app.ch, q = app.orderPick, pose = fit.pose(app), n = q && AL.node(ch, q.node); if (!q || !pose || !n) { app.orderPick = null; return; }
    var limb = AL.ancestorWith(ch, id, function (a) { return a.type === 'set' && a.pick && a.pick.by === 'reach'; }), zone = AL.zoneOf(ch, id), zn = zone !== 'extra' ? app.zoneNode(zone) : null, tgt = limb || zn || AL.node(ch, id);
    if (!tgt || tgt.id === n.id || AL.ancestorWith(ch, tgt.id, function (a) { return a.id === n.id; })) { app.toast('Tap a different part'); return; }
    var how = q.how; app.orderPick = null;
    app.edit(function () { var e = (pose.d = pose.d || {})[n.id] || (pose.d[n.id] = {}); e.z = { how: how, of: tgt.id }; }, false, (how === 'under' ? 'Behind ' : 'In front of ') + tgt.name + ', in ' + pose.name);
  };

  /** The section every part's card gets. */
  fit.section = function (app, pb, n) {
    var ui = app.ui, h = AL.h, pose = fit.pose(app), on = app.fitOn && app.fitOn.node === n.id ? app.fitOn : null, can = fit.can(app, n);
    if (!can && !n.limb) return;
    pb.appendChild(ui.lab(pose ? 'Fit it by hand, for the stance “' + pose.name + '”' : 'Fit it by hand'));
    var v = fit.get(app, n), row = [];
    if (can) row.push(ui.chip('Move, turn, resize', { on: !!(on && on.box), icon: 'move', cls: on ? '' : 'go', title: 'A box on the picture: drag inside it to move, a corner to resize, the round handle below to turn', fn: function () { fit.boxOn(app, n, !(on && on.box)); } }));
    if (can && !pose && n.type !== 'set') row.push(ui.chip('Reshape with pins', { on: !!(on && on.pins), icon: 'pin', title: 'Tap the part to drop pins, then drag one: the part stretches from there and the other pins hold the rest still', fn: function () { fit.reshapeOn(app, n, !(on && on.pins)); } }));
    if (can && fit.moved(app, n)) row.push(ui.chip('Put it back', { cls: 'quiet', title: pose ? 'Forget where this part was put for this stance' : 'Forget the move, the turn and the size: back to how it was drawn', fn: function () { app.edit(function () { fit.reset(app, n); }, false, 'Put it back'); } }));
    pb.appendChild(ui.row(row));
    var pd0 = pose && pose.d ? pose.d[n.id] : null, ax0 = pd0 && pd0.auto ? pd0.autoX || 0 : 0, ay0 = pd0 && pd0.auto ? pd0.autoY || 0 : 0, ar0 = pd0 && pd0.auto ? pd0.autoRot || 0 : 0;      // in a worked-out stance, show only what the hand has added
    if (on && on.box) pb.appendChild(ui.note('Moved <b>' + Math.round(v.x - ax0) + ', ' + Math.round(v.y - ay0) + '</b> · turned <b>' + Math.round(v.rot - ar0) + '°</b> · size <b>' + Math.round(v.sc * 100) + '%</b>.' + (pose ? ' Only while it is in this stance.' : ' It stays like this.')));
    if (pose) fit.orderRow(app, pb, n, pose);
    if (on && on.pins) {
      var fs = fit.fixShape(n), np = n.mesh ? n.mesh.pins.length : 0;
      pb.appendChild(ui.note(np ? '<b>Drag a pin</b> to stretch the part from there. Pins you leave alone hold it still. Tap the part for another pin.' : '<b>Tap the part</b> where it should stay put, and again where you want to pull it. Then drag a pin.'));
      pb.appendChild(ui.row([
        np ? ui.chip('Remove the last pin', { cls: 'quiet', fn: function () { app.edit(function () { var q = n.mesh.pins.pop(); if (q && fs && fs.d) delete fs.d[q.id]; }, false, 'Remove a pin'); } }) : null,
        fs && Object.keys(fs.d || {}).length ? ui.chip('Start again', { cls: 'quiet', title: 'Every pin back to where it was dropped', fn: function () { app.edit(function () { fs.d = {}; }, false, 'Clear the reshape'); } }) : null,
        ui.chip('Done', { cls: 'go', fn: function () { fit.reshapeOn(app, n, false); } })
      ]));
    }
  };
})(typeof self !== 'undefined' ? self : this);
