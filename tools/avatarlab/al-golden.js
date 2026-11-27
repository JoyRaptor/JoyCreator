/* AvatarLab: GOLDEN FILES for the phone's solver (AVA-1 step 0; the phone's CharacterSolver must land every vertex within 0.5 px).
 *
 *   AL.golden.cases(ch)          the standard list of poses for a character: rest, head turned left / right / up, both hands reaching,
 *                                talking, eyes shut, each stance at full (all fours for Topps), each mood that moves the lids
 *   AL.golden.make(ch, cases)    solves each case with a FRESH solver (nothing carried over from the room), settles it for 8 frames at
 *                                30 a second, and returns one document: { format: 'joycreator.golden/1', character, w, h, frames, cases:
 *                                [{ name, signals, items: [{ id, img, a, ghost, under, v: [x, y, ...] }] }] }. Vertices are character
 *                                pixels rounded to 0.01. The triangles and texture places are not written: they come from the art.
 *   AL.golden.put(doc, name)     saves it beside the page through the pilot's own server (serve.mjs: PUT into local/), for copying
 *                                into tasks/avatar/golden/.
 * Not loaded by the published page's users for anything; it only reads the character. Walking is not a case: the walker is not in the
 * solver (it feeds it foot and body signals), so its parity is the walker's own test.
 */
(function (root) {
  'use strict';
  var AL = root.AL, G = AL.golden = {};
  G.FRAMES = 8; G.DT = 1 / 30;
  function r2(v) { return Math.round(v * 100) / 100; }
  function base() { var S = AL.restSignals(); S.pose = {}; S.tone = {}; return S; }

  G.cases = function (ch) {
    var out = [], b = AL.boundsOf(ch.root) || { x0: 0, y0: 0, x1: ch.w, y1: ch.h }, cx = (b.x0 + b.x1) / 2, w = b.x1 - b.x0;
    function add(name, fn) { var S = base(); fn(S); out.push({ name: name, signals: S }); }
    add('rest', function () { });
    add('turn left', function (S) { S.turnX = -0.6; });
    add('turn right', function (S) { S.turnX = 0.6; });
    add('turn up', function (S) { S.turnY = -0.6; });
    add('reach', function (S) { S.hand.L = { x: cx + w * 0.55, y: b.y0 + (b.y1 - b.y0) * 0.25 }; S.hand.R = { x: cx - w * 0.55, y: b.y0 + (b.y1 - b.y0) * 0.3 }; });
    add('talking', function (S) { S.mouth = 0.8; S.viseme = 'Aa'; });
    add('eyes shut', function (S) { S.blinkL = 1; S.blinkR = 1; });
    (ch.poses || []).forEach(function (p) { add('stance: ' + p.name, function (S) { if (p.trig) S.trig[p.trig] = true; S.pose[p.id] = 1; }); });
    ['Angry', 'Cheery'].forEach(function (m) { var q = AL.MOODS && AL.MOODS[m]; if (q) add('mood: ' + m, function (S) { S.lidUp = q[0]; S.lidTilt = q[1]; S.lidLow = q[2]; S.browL = S.browR = q[3]; }); });
    return out;
  };

  G.make = function (ch, cases) {
    cases = cases || G.cases(ch);
    var doc = { format: 'joycreator.golden/1', character: ch.name, characterFormat: ch.format, w: ch.w, h: ch.h, frames: G.FRAMES, dt: G.DT, cases: [] };
    cases.forEach(function (cs) {
      var sv = new AL.Solver(ch), out = null, S = JSON.parse(JSON.stringify(cs.signals));
      for (var i = 0; i < G.FRAMES; i++) { S.t = i * G.DT; out = sv.eval(S, { live: true, dt: G.DT, force: null, show: null }); }
      doc.cases.push({ name: cs.name, signals: cs.signals, items: out.items.map(function (it) {
        var v = new Array(it.v.length); for (var k = 0; k < it.v.length; k++) v[k] = r2(it.v[k]);
        return { id: it.id, img: it.img, a: r2(it.a), ghost: !!it.ghost, under: it.under || null, v: v };
      }) });
    });
    return doc;
  };

  G.put = function (doc, name) {
    return fetch('local/' + encodeURIComponent(name || 'golden.json'), { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(doc) }).then(function (r) { return r.ok; });
  };
})(typeof self !== 'undefined' ? self : this);
