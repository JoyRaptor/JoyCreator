/* AvatarLab: stances.
 *
 * A STANCE is a whole-body pose the character melts into and out of: all fours, sitting, a crouch, a bow.
 * It is a short list of parts, each moved, turned or resized by a set amount ({ x, y, rot, sc }), and
 * optionally swung round a point ({ orbit: { x, y, deg } }) so it travels an arc and not a straight line
 * (a torso tipping forward about its hips carries the shoulders round with it).
 *
 *   ch.poses = [{ id, name, trig, time, bounce, d: { nodeId: { x, y, rot, sc, orbit } }, fours: { hip, angle, drop, auto } }]
 *
 * Each stance has a button (a trigger), so it is recorded in a take like any other button. Its level eases
 * 0..1 on a spring (al-inputs.js); the solver blends every part by that level (al-solve.js, local()).
 *
 * ALL FOURS is worked out from the art: the torso tips forward about the hips until the hands can reach the
 * ground; the legs and tail stay where they are; the head rides down on the neck and stays level. The arms
 * are told they may walk, and the walker does the rest: a hand whose shoulder has come within reach of the
 * ground plants and steps like a foot (al-walk.js). Push the character low and it goes down by itself.
 * Any part can then be nudged for the stance with the transform box (al-fit.js).
 */
(function (root) {
  'use strict';
  var AL = root.AL, stance = AL.stance = {};
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function hyp(x, y) { return Math.sqrt(x * x + y * y); }
  function restKid(ch, set) { var k = set && set.pick ? AL.node(ch, set.pick.rest) : null; if (!k || !k.limb) k = set ? (set.kids || []).filter(function (q) { return q.limb; })[0] : null; return k || null; }
  function limbLen(l) { return hyp(l.b.x - l.a.x, l.b.y - l.a.y) + hyp(l.c.x - l.b.x, l.c.y - l.b.y); }

  stance.find = function (ch, id) { var ps = ch.poses || []; for (var i = 0; i < ps.length; i++) if (ps[i].id === id) return ps[i]; return null; };
  stance.fours = function (ch) { var w = (ch.settings || {}).walk; return w && w.fours ? stance.find(ch, w.fours) : null; };
  /** A new, empty stance with its own button. */
  stance.add = function (ch, name) {
    var t = { id: AL.id('t'), name: name }, p = { id: AL.id('z'), name: name, trig: t.id, time: 0.6, d: {} };
    (ch.triggers = ch.triggers || []).push(t); (ch.poses = ch.poses || []).push(p); return p;
  };
  stance.remove = function (ch, p) {
    ch.poses = (ch.poses || []).filter(function (q) { return q !== p; }); ch.triggers = (ch.triggers || []).filter(function (t) { return t.id !== p.trig; });
    if (ch.settings.walk && ch.settings.walk.fours === p.id) delete ch.settings.walk.fours;
  };
  /** Can this character go onto all fours? It needs a torso, two legs that bend and at least one arm that bends. */
  stance.parts = function (ch) {
    var torso = AL.firstRole(ch.root, 'torso'), body = AL.firstRole(ch.root, 'body'), lL = AL.firstRole(ch.root, 'legL'), lR = AL.firstRole(ch.root, 'legR'), aL = AL.firstRole(ch.root, 'armL'), aR = AL.firstRole(ch.root, 'armR');
    var kl = restKid(ch, lL), kr = restKid(ch, lR), arms = [aL, aR].filter(function (a) { return a && a.type === 'set' && restKid(ch, a); });
    if (!(torso || body) || !kl || !kr || !arms.length) return null;
    return { torso: torso || body, legs: [lL, lR], arms: arms, head: AL.firstRole(ch.root, 'head'), tails: AL.byRole(ch.root, 'tail') };
  };
  /** Does `n` move when `torso` moves? (It hangs from it, or sits inside it.) */
  function carried(ch, n, torso) {
    var guard = 0, q = n;
    while (q && guard++ < 40) { if (q === torso) return true; if (q.attach) { if (q.attach === torso.id) return true; q = AL.node(ch, q.attach); continue; } q = AL.parentOf(ch, q.id); }
    return false;
  }
  /** Work the all-fours stance out from the art. `angle` (degrees of tip) is found when not given. Returns the stance (made, or updated). */
  stance.makeFours = function (ch, angle) {
    var P = stance.parts(ch); if (!P) return null;
    var set = ch.settings, wk = set.walk || (set.walk = AL.walkDefaults(ch) || {}), kl = restKid(ch, P.legs[0]).limb, kr = restKid(ch, P.legs[1]).limb;
    var H = { x: Math.round((kl.a.x + kr.a.x) / 2), y: Math.round((kl.a.y + kr.a.y) / 2) }, gy = wk.groundY || Math.max(kl.c.y, kr.c.y);
    var sx = 0, sy = 0, len = 0; P.arms.forEach(function (a) { var l = restKid(ch, a).limb; sx += l.a.x / P.arms.length; sy += l.a.y / P.arms.length; len += limbLen(l) / P.arms.length; });
    /* which way is forward: the way it faces; failing that, the side its head is on */
    var hb = P.head ? AL.boundsOf(P.head) : null, dir = set.facing === 'left' ? -1 : set.facing === 'right' ? 1 : (hb && (hb.x0 + hb.x1) / 2 < H.x ? -1 : 1);
    function shoulderY(deg) { var a = deg * Math.PI / 180 * dir; return H.y + Math.sin(a) * (sx - H.x) + Math.cos(a) * (sy - H.y); }
    var palm = len * 0.1, want = len * 0.9 + palm, A = angle, drop = 0;
    if (A == null) { A = 104; for (var t = 40; t <= 104; t++) if (gy - shoulderY(t) <= want) { A = t; break; } }
    drop = Math.max(0, Math.round(gy - shoulderY(A) - want));                        // arms too short even so: the hips come down the rest of the way
    var p = stance.fours(ch) || stance.add(ch, 'All fours'), old = p.d || {}, sgnA = A * dir, d = {};
    /* what was nudged by hand stays nudged: each part keeps its own difference from what is worked out here */
    function keep(id, base) {
      var o = old[id] || {}; base.autoRot = base.autoRot || 0; base.autoX = base.autoX || 0; base.autoY = base.autoY || 0;
      base.x = Math.round((base.autoX + ((o.x || 0) - (o.autoX || 0))) * 10) / 10; base.y = Math.round((base.autoY + ((o.y || 0) - (o.autoY || 0))) * 10) / 10;
      base.rot = base.autoRot + ((o.rot || 0) - (o.autoRot || 0)); if (o.sc != null && o.sc !== 1) base.sc = o.sc; if (o.z) base.z = o.z; base.auto = true; return base;
    }
    for (var id0 in old) if (!old[id0].auto) d[id0] = old[id0];
    var torso = P.torso;
    d[torso.id] = keep(torso.id, { autoRot: sgnA, orbit: { x: H.x, y: H.y, deg: sgnA } });
    P.legs.concat(P.tails).forEach(function (n) { if (carried(ch, n, torso)) d[n.id] = keep(n.id, { autoRot: -sgnA, orbit: { x: H.x, y: H.y, deg: -sgnA } }); });       // carried by the torso: turned back, so they stay put
    if (P.head) {
      if (carried(ch, P.head, torso)) { if (!P.head.pivot) { var b = AL.boundsOf(P.head); if (b) P.head.pivot = { x: (b.x0 + b.x1) / 2, y: b.y1 }; } d[P.head.id] = keep(P.head.id, { autoRot: -sgnA }); }      // rides down on the neck, stays level
      else d[P.head.id] = keep(P.head.id, { autoRot: 0, orbit: { x: H.x, y: H.y, deg: sgnA } });
    }
    /* The arms go round with the torso and may now walk. A body drawn three-quarters on has one shoulder painted well to the side of
       the other, and tipping the picture would leave one shoulder high and one low; so each shoulder is also slid to where a front leg
       stands: under the front of the tipped body, at the height its hand reaches the ground from (the far one a little higher, as the
       far foot is). The slide is measured in the torso's own frame, so it swings round with the torso on the way down. */
    var tb = AL.boundsOf(torso), tlen = tb ? Math.max(20, H.y - tb.y0) : len, ar = sgnA * Math.PI / 180, ca = Math.cos(ar), sa = Math.sin(ar);
    P.arms.forEach(function (a) {
      var l = restKid(ch, a).limb, alen = limbLen(l), lk = Math.abs(l.a.x - kl.a.x) < Math.abs(l.a.x - kr.a.x) ? kl : kr, far = Math.max(0, gy - lk.c.y), isFar = lk.c.y < Math.max(kl.c.y, kr.c.y) - 1, apalm = a.pick.walk && a.pick.walk.palm != null ? a.pick.walk.palm : AL.palmGuess(ch, restKid(ch, a));
      var vx = l.a.x - H.x, vy = l.a.y - H.y, rx = H.x + ca * vx - sa * vy, ry = H.y + sa * vx + ca * vy;            // where tipping alone leaves this shoulder
      var tx = H.x + dir * (tlen * 0.75 + (isFar ? Math.abs(kl.c.x - kr.c.x) * 0.45 : 0)), ty = gy + drop - far - apalm - alen * 0.9, wx = tx - rx, wy = ty - ry;       // where it should be, and so how far to slide it
      /* the slide belongs to being down on the ground: a body only tipped a little keeps its arms on its shoulders */
      var sf = Math.max(0, Math.min(1, (A - 40) / 35)); sf = sf * sf * (3 - 2 * sf); wx *= sf; wy *= sf;
      var base = { autoX: ca * wx + sa * wy, autoY: -sa * wx + ca * wy };
      if (!carried(ch, a, torso)) { base.autoRot = sgnA; base.orbit = { x: H.x, y: H.y, deg: sgnA }; base.autoX = wx; base.autoY = wy; }
      d[a.id] = keep(a.id, base); if (!a.pick.walk) a.pick.walk = { lift: true };
    });
    p.d = d; p.fours = { hip: H, angle: A, drop: drop }; if (p.time == null || p.time === 0.6) p.time = 0.7;
    wk.fours = p.id; if (wk.autoFours == null) wk.autoFours = true;
    return p;
  };

  /* ── the cards ── */
  stance.section = function (app, pb) {
    var ui = app.ui, h = AL.h, ch = app.ch, list = ch.poses || [], ed = app.poseEdit ? stance.find(ch, app.poseEdit) : null;
    var staged = AL.stages.on();
    pb.appendChild(ui.lab(staged && ed ? 'This stance: ' + ed.name : 'Stances', 'fit'));
    if (staged && !ed) { pb.appendChild(ui.note(list.length ? '<b>Standing</b> is how it was drawn. Choose a stance in the row on the picture to place the parts for it, or <b>+</b> there for a new one.' : 'A stance is a whole-body pose it melts into at the press of a button: all fours, sitting, a crouch. Tap <b>+</b> in the row on the picture to make one.')); return; }
    if (!list.length) pb.appendChild(ui.note('A stance is a whole-body pose it melts into at the press of a button: all fours, sitting, a crouch. Parts are moved for the stance with the same box you move them with anywhere else.'));
    var row = list.map(function (p) { return ui.chip(p.name, { on: ed === p, icon: 'person', title: ed === p ? 'Stop working on this stance' : 'Work on this stance: the character stands in it, and what you move is moved for it', fn: function () { app.poseEdit = ed === p ? null : p.id; app.poseAmt = null; app.fitOn = null; app.refreshPanel(true); } }); });
    if (!stance.fours(ch) && stance.parts(ch)) row.push(ui.chip('Make an all-fours stance', { icon: 'wand', cls: 'go', title: 'Tips the body forward about the hips until the hands reach the ground, keeps the legs, tail and head where they belong, and lets the hands walk as front feet', fn: function () { var made; app.edit(function () { made = stance.makeFours(ch); }, true, 'All fours'); if (made) { app.poseEdit = made.id; app.toast('All fours made. Nudge any part with Pick a piece; Try it and push it low.'); } app.refreshPanel(true); } }));
    row.push(ui.chip('Add a stance', { icon: 'plus', title: 'A new whole-body pose with its own button', fn: function () { app.promptText('Name this stance', 'Sitting', function (v) { var made; app.edit(function () { made = stance.add(ch, v); }, true, 'Add a stance'); app.poseEdit = made.id; app.refreshPanel(true); }); } }));
    if (!staged) pb.appendChild(ui.row(row));
    if (!ed) return;
    pb.appendChild(ui.slider('See it', { min: 0, max: 100, value: Math.round((app.poseAmt == null ? 1 : app.poseAmt) * 100), undo: false, set: function (v) { app.poseAmt = v / 100; }, fmt: function (v) { return v >= 100 ? 'fully in' : v <= 0 ? 'standing' : v + '%'; }, title: 'Slide to watch the character go into this stance and out again' }));
    if (ed.fours) pb.appendChild(ui.slider('How far it tips', { min: 30, max: 125, value: ed.fours.angle, set: function (v) { stance.makeFours(ch, v); }, fmt: function (v) { return v + '°'; }, title: 'How far the body leans forward about the hips' }));
    if (ed.fours) pb.appendChild(ui.slider('Hips come down', { min: 0, max: 80, value: ed.fours.drop || 0, set: function (v) { ed.fours.drop = v; }, fmt: function (v) { return v ? v + ' px' : 'not at all'; }, title: 'How far the hips sink while it is on all fours. The feet stay planted, so the back legs fold. Watch the back legs as you slide.' }));
    pb.appendChild(ui.slider('It takes', { min: 0.15, max: 2, step: 0.05, value: ed.time == null ? 0.6 : ed.time, set: function (v) { ed.time = v; }, fmt: function (v) { return v.toFixed(2) + ' s'; }, title: 'How long it takes to get into and out of this stance' }));
    var wk = ch.settings.walk;
    pb.appendChild(ui.row([
      staged ? null : ui.chip('Pick a piece', { on: !!app.pickPiece, icon: 'pin', title: 'Then tap any part on the picture to move, turn or resize it for this stance', fn: function () { app.pickPiece = !app.pickPiece; app.refreshPanel(true); } }),
      ui.chip('Settles with a bounce', { on: !!ed.bounce, title: 'It goes a little too far and comes back, like something heavy arriving', fn: function () { app.edit(function () { ed.bounce = !ed.bounce; }, false, 'Bouncy stance'); } }),
      ed.fours && wk ? ui.chip('Goes down by itself', { on: wk.autoFours !== false, title: 'On: pushed low it drops onto all fours instead of squatting deeper, and pulled up it stands again. Off: only its button does it.', fn: function () { app.edit(function () { wk.autoFours = wk.autoFours === false; }, false, 'All fours by itself'); } }) : null,
      ui.chip('Rename', { cls: 'quiet', fn: function () { app.promptText('Name this stance', ed.name, function (v) { app.edit(function () { var t = (ch.triggers || []).filter(function (q) { return q.id === ed.trig; })[0]; if (t) t.name = v; ed.name = v; }, false, 'Rename a stance'); }); } }),
      ui.chip('Remove', { cls: 'quiet', icon: 'trash', title: 'Take this stance away. Undo brings it back.', fn: function () { app.edit(function () { stance.remove(ch, ed); app.poseEdit = null; }, false, 'Remove a stance'); } }),
      staged ? null : ui.chip('Done', { cls: 'go', fn: function () { app.poseEdit = null; app.fitOn = null; app.refreshPanel(true); } })
    ]));
    var n = Object.keys(ed.d || {}).length;
    if (staged) { pb.appendChild(ui.note('The character is in <b>' + ed.name + '</b>. Tap a part on the picture and drag it: it is moved for this stance only. Standing is not touched.')); return; }
    pb.appendChild(ui.note('The character is standing in <b>' + ed.name + '</b>. ' + (n ? n + ' part' + (n === 1 ? ' is' : 's are') + ' placed for it. ' : '') + 'Turn on <b>Pick a piece</b> and tap a part: move, turn or resize it, and say what it passes in front of or behind, for this stance only. Standing is not touched. Its button is in Perform' + (ed.fours ? ', and a walking character pushed low goes into it by itself.' : '.')));
  };
})(typeof self !== 'undefined' ? self : this);
