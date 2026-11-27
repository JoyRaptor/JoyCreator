/* AvatarLab: EXPRESSION POSES on the face mask (job FACE-2, stage 3; the owner's design, mockup rounds 1 and 2, 11-22).
 *
 * A creature is not a person: what a smile, a jaw dropping or brows going up means on a triceratops's muzzle, a duck bill or
 * a brachiosaurus whose jaw falls to its neck is the artist's call. So a pose is two things:
 *   1. Make the face: you make that face at the camera and Capture; only how the mask's face points moved is kept (no picture).
 *   2. Pose: you drag the mask's points on one drawing into YOUR version of it; the head bends as one piece. Made on one
 *      drawing, it is carried to the others by their real turns; a fix made on another drawing is kept there.
 *   Optionally Swap a drawing: while the pose is strong, a part shows another of its drawings (an open mouth), per drawing.
 * Live, how much of each pose your face holds now is worked out from your captured faces (never negative), and the poses
 * blend; what they do not explain still follows your face directly (the Directly dial). The weights are lanes mp.<pose id>,
 * recorded and played back like every face lane; the solver reads them through AL.mask.bendFor / AL.mask.swapsNow.
 * Data, in the head's set.face: poses [{ id, name, cue, idea, face: { <n>: [x, y] }, at: { <angle>: { <n>: [dx, dy] } },
 * swap: { <angle>: { <set id>: <kid id> } } }], direct; written by carry: posed { <pose>: { <angle>: { <n>: [dx, dy] } } } and
 * swapped { <pose>: { <set id>: <kid id> } } for every drawing.
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h, mp = AL.maskPoses = {};
  mp.DEFAULTS = [
    ['smile', 'Smile', 'Give a big smile.', 'Pull his mouth ends up and back.'],
    ['jaw', 'Jaw open', 'Drop your jaw open.', 'Drop his chin and lower lip (and swap in an open mouth).'],
    ['brows', 'Brows up', 'Raise your eyebrows high.', 'Lift his brows.'],
    ['puff', 'Cheeks puffed', 'Puff your cheeks out.', 'Push his cheeks out.'],
    ['pucker', 'Pucker', 'Pucker your lips.', 'Squeeze his muzzle in.']
  ];
  function data(set) { return AL.mask.data(set); }
  mp.list = function (set) { var f = set && set.face; return (f && f.poses) || []; };
  mp.get = function (set, id) { return mp.list(set).filter(function (p) { return p.id === id; })[0] || null; };
  /** The usual five, the first time poses are opened (one undo step with the opening). */
  mp.ensure = function (set) {
    var f = data(set); if (f.poses && f.poses.length) return false;
    f.poses = mp.DEFAULTS.map(function (d) { return { id: d[0], name: d[1], cue: d[2], idea: d[3], face: null, at: {}, swap: {} }; });
    return true;
  };
  mp.add = function (set, name) { var f = data(set); f.poses = f.poses || []; var id = AL.id('pz'); f.poses.push({ id: id, name: name || 'Pose ' + (f.poses.length + 1), cue: 'Make the face you want.', idea: 'Drag his points into your version of it.', face: null, at: {}, swap: {} }); return id; };
  mp.remove = function (set, id) { var f = data(set); f.poses = mp.list(set).filter(function (p) { return p.id !== id; }); mp.carry(set); };
  mp.rename = function (set, id, name) { var p = mp.get(set, id); if (p) p.name = String(name || '').slice(0, 40) || p.name; };
  /** Keep your face as it is now (the mask's raw movement, mask units) as this pose's face. */
  mp.capture = function (set, id, raw) {
    var p = mp.get(set, id); if (!p || !raw) return false;
    var face = {}, any = false; Object.keys(raw).forEach(function (n) { var v = raw[n]; face[n] = [Math.round(v[0] * 1000) / 1000, Math.round(v[1] * 1000) / 1000]; if (Math.abs(v[0]) + Math.abs(v[1]) > 0.002) any = true; });
    p.face = face; return any;
  };
  /** Point n of the mask moved by (dx, dy) in this pose on drawing a (the head's frame): yours there. */
  mp.move = function (set, id, a, n, dx, dy) { var p = mp.get(set, id); if (!p) return; p.at[a] = p.at[a] || {}; p.at[a][n] = [Math.round(dx * 10) / 10, Math.round(dy * 10) / 10]; mp.carry(set); };
  mp.clearAngle = function (set, id, a) { var p = mp.get(set, id); if (!p) return; delete p.at[a]; delete p.swap[a]; mp.carry(set); };
  /** A starting pose on drawing a: your captured face, turned to the drawing, at the drawing's size. */
  mp.guess = function (set, id, a) {
    var p = mp.get(set, id), f = set.face, fit = f && f.fit && f.fit[a]; if (!p || !p.face || !fit) return false;
    var r = AL.mask.R3(fit[0], fit[1]), at = {};
    Object.keys(p.face).forEach(function (n) { var v = AL.mask.fwd(r, [p.face[n][0], p.face[n][1], 0]); if (Math.abs(v[0]) + Math.abs(v[1]) > 1e-4) at[n] = [Math.round(v[0] * fit[2] * 10) / 10, Math.round(v[1] * fit[3] * 10) / 10]; });
    p.at[a] = at; mp.carry(set); return true;
  };
  /** Which drawing a part shows while this pose is strong, on drawing a (null: as painted). */
  mp.swapSet = function (set, id, a, setId, kidId) { var p = mp.get(set, id); if (!p) return; p.swap[a] = p.swap[a] || {}; if (kidId) p.swap[a][setId] = kidId; else delete p.swap[a][setId]; if (!Object.keys(p.swap[a]).length) delete p.swap[a]; mp.carry(set); };

  /* ── carrying a pose to every drawing (by their real turns), and its swaps (by names) ── */
  function tdist(a, b) { return (a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]); }
  function nearest(fit, own, a) { var t = fit[a]; if (!t) return null; return own.filter(function (o) { return fit[o]; }).sort(function (x, y) { return tdist(fit[x], t) - tdist(fit[y], t); })[0] || null; }
  function norm(s) { return String(s || '').toLowerCase().replace(/[^a-z0-9]/g, ''); }
  /** The parts of one drawing that can show other drawings (a set of mouths), by plain name. */
  mp.swappable = function (kid) { var out = []; AL.walk(kid, function (k) { if (k !== kid && k.type === 'set' && (k.kids || []).length > 1) out.push(k); }); return out; };
  mp.carry = function (set) {
    var f = data(set), fit = f.fit || {}, kids = AL.mask.angles(set), posed = {}, swapped = {};
    mp.list(set).forEach(function (p) {
      var own = Object.keys(p.at || {}).filter(function (a) { return Object.keys(p.at[a]).length; }), ps = posed[p.id] = {};
      kids.forEach(function (k) {
        if (p.at[k.id] && Object.keys(p.at[k.id]).length) { ps[k.id] = p.at[k.id]; return; }
        var src = nearest(fit, own, k.id); if (!src) return;
        var fa = fit[src], fb = fit[k.id], ra = AL.mask.R3(fa[0], fa[1]), rb = AL.mask.R3(fb[0], fb[1]), o = {};
        Object.keys(p.at[src]).forEach(function (n) { var d = p.at[src][n], v = AL.mask.fwd(rb, AL.mask.back(ra, [d[0] / fa[2], d[1] / fa[3], 0])); o[n] = [Math.round(v[0] * fb[2] * 10) / 10, Math.round(v[1] * fb[3] * 10) / 10]; });
        ps[k.id] = o;
      });
      var sw = swapped[p.id] = {}, ownS = Object.keys(p.swap || {});
      kids.forEach(function (k) {
        var src = p.swap[k.id] ? k.id : nearest(fit, ownS, k.id); if (!src) return;
        var map = p.swap[src], srcKid = AL.find(set, function (q) { return q.id === src; });
        Object.keys(map).forEach(function (sid) {
          if (src === k.id) { sw[sid] = map[sid]; return; }
          var sA = srcKid && AL.find(srcKid, function (q) { return q.id === sid; }), kA = sA && (sA.kids || []).filter(function (q) { return q.id === map[sid]; })[0]; if (!sA || !kA) return;
          var sB = mp.swappable(k).filter(function (q) { return norm(q.name) === norm(sA.name); })[0], kB = sB && (sB.kids || []).filter(function (q) { return norm(q.name || q.shape) === norm(kA.name || kA.shape); })[0];
          if (sB && kB) sw[sB.id] = kB.id;
        });
      });
    });
    f.posed = posed; f.swapped = swapped;
  };

  /* ── live: how much of each pose your face holds now ── */
  /** Non-negative least squares over the captured faces (a few poses, coordinate descent). m = { <n>: [x, y] }. */
  mp.weights = function (set, m) {
    var P = mp.list(set).filter(function (p) { return p.face; }), w = P.map(function () { return 0; }), keys = {};
    P.forEach(function (p) { Object.keys(p.face).forEach(function (n) { keys[n] = 1; }); }); Object.keys(m).forEach(function (n) { keys[n] = 1; });
    var ns = Object.keys(keys), C = P.map(function (p) { var v = []; ns.forEach(function (n) { var q = p.face[n] || [0, 0]; v.push(q[0], q[1]); }); return v; }), y = [];
    ns.forEach(function (n) { var q = m[n] || [0, 0]; y.push(q[0], q[1]); });
    var cc = C.map(function (c) { var s = 0; c.forEach(function (v) { s += v * v; }); return s; }), r = y.slice(), it, k, j;
    for (it = 0; it < 30; it++) for (k = 0; k < C.length; k++) {
      if (cc[k] < 1e-9) continue; var dot = 0; for (j = 0; j < r.length; j++) dot += C[k][j] * r[j];
      var nw = Math.max(0, Math.min(1.5, w[k] + dot / cc[k])), dw = nw - w[k]; if (!dw) continue; w[k] = nw; for (j = 0; j < r.length; j++) r[j] -= dw * C[k][j];
    }
    var out = { w: {}, rest: {} }; P.forEach(function (p, i) { out.w[p.id] = w[i]; });
    ns.forEach(function (n, i) { out.rest[n] = [r[i * 2], r[i * 2 + 1]]; });
    return out;
  };
  /** For the solver: the parts to show another drawing now ({ set id: kid id }), from the poses strong enough. */
  mp.swapsNow = function (set, S) {
    var f = set && set.face, sw = f && f.swapped; if (!sw) return null; var out = null;
    Object.keys(sw).forEach(function (id) { if ((S['mp.' + id] || 0) > 0.5) Object.keys(sw[id]).forEach(function (sid) { (out || (out = {}))[sid] = sw[id][sid]; }); });
    return out;
  };
  /** A pose's move of point n on drawing a now (carried or yours), or null. */
  mp.offsetOf = function (set, id, a, n) { var f = set && set.face, ps = f && f.posed && f.posed[id] && f.posed[id][a]; return ps && ps[n] || null; };
  mp.statusOn = function (set, id, a) { var p = mp.get(set, id); if (!p) return 'none'; if (p.at[a] && Object.keys(p.at[a]).length) return 'you'; var f = set.face; return f.posed && f.posed[id] && f.posed[id][a] ? 'carried' : 'none'; };
})(typeof self !== 'undefined' ? self : this);
