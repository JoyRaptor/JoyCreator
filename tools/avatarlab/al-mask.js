/* AvatarLab: THE FACE MASK, the model (job FACE-2; the owner's design in two mockup rounds, 11-22, "Build it" 11-27).
 *
 * The mask IS the head's own landmarks: the whole-drawing piece of the turn set (set.marks[''], AVA_FORMAT.md B2.1). Each mask
 * point is an ordinary numbered landmark whose entry also says what it is: { n, name, key: 'eyeL', face: [MediaPipe indices],
 * handle: true }. So putting the mask on also gives the head turn its landmarks, and the phone reads it as landmarks.
 *
 * What the landmarks alone cannot say lives in set.face (proposed in tasks/avatar/CHANNEL.md, 11-27):
 *   turn  { <angle id>: [yaw, pitch] }   a drawing's real turn, when the user set it (else worked out from its points)
 *   mine  { <angle id>: [i, ...] }       places the user put by hand: they never move again (the owner's round-2 complaint)
 *   hid   { <angle id>: { i: 'behind' | 'off' | 'here' } }   hidden / not here / shown, when the user said so (else guessed)
 *   ok    { <angle id>: true }           "Looks right": the user checked this drawing
 * Every other place in the rows is a GUESS, rewritten by carry(): the mask in 3D, turned to the drawing's real turn, fitted to
 * the places the user gave (and to slices named Left Eye, Nose, Mouth...: Snap to parts), and bent by what is left over.
 * A drawing's real turn is never read from its name or its dot on the pad (his: "a frontal is actually closer to a left
 * quarter, and a right quarter is an exact face on"); the dot is only a light nudge. With places on drawings turned apart,
 * each point's depth is learned, so the mask becomes the creature's own face shape.
 *
 * Positions are in the turn set's own frame (as every landmark). Yaw: + turns toward the character's left (the picture's
 * right). Pitch: + looks up. Mask x: + = the character's left; y down; z toward the viewer; about one unit = half a face.
 * Phone: to be specified for the room (FACE-2 card).
 */
(function (root) {
  'use strict';
  var AL = root.AL, MK = AL.marks, mask = AL.mask = {};
  var DG = Math.PI / 180;

  /* the template: key, name, x, y, z, big handle, the face points it follows (MediaPipe; the character's right = the person's right) */
  mask.TPL = [
    ['foreTop', 'Top of the forehead', 0, -1.0, 0.3, 0, [10]], ['foreMid', 'Mid forehead', 0, -0.76, 0.52, 0, [151]],
    ['browMid', 'Between the brows', 0, -0.52, 0.66, 0, [9]], ['bridge', 'Bridge of the nose', 0, -0.24, 0.8, 0, [168]],
    ['snout', 'Nose', 0, 0.1, 1.0, 1, [1]], ['lipU', 'Upper lip', 0, 0.38, 0.86, 0, [0]], ['lipD', 'Lower lip', 0, 0.6, 0.8, 0, [17]],
    ['chin', 'Chin', 0, 0.88, 0.62, 0, [152]],
    ['browRi', 'Right brow, inner', -0.26, -0.55, 0.62, 0, [107]], ['browRo', 'Right brow, outer', -0.78, -0.5, 0.3, 0, [70]],
    ['eyeR', 'Right eye', -0.45, -0.28, 0.55, 1, [33, 133]], ['cheekR', 'Right cheek', -0.68, 0.2, 0.4, 0, [205]],
    ['mouthR', 'Right mouth end', -0.4, 0.46, 0.5, 1, [61]], ['jawR', 'Right jaw', -0.72, 0.58, 0.1, 0, [172]],
    ['browLi', 'Left brow, inner', 0.26, -0.55, 0.62, 0, [336]], ['browLo', 'Left brow, outer', 0.78, -0.5, 0.3, 0, [300]],
    ['eyeL', 'Left eye', 0.45, -0.28, 0.55, 1, [263, 362]], ['cheekL', 'Left cheek', 0.68, 0.2, 0.4, 0, [425]],
    ['mouthL', 'Left mouth end', 0.4, 0.46, 0.5, 1, [291]], ['jawL', 'Left jaw', 0.72, 0.58, 0.1, 0, [397]]
  ];
  mask.EDGES = [['foreTop', 'foreMid'], ['foreMid', 'browMid'], ['browMid', 'bridge'], ['bridge', 'snout'], ['snout', 'lipU'], ['mouthR', 'lipU'], ['lipU', 'mouthL'],
    ['mouthL', 'lipD'], ['lipD', 'mouthR'], ['lipD', 'chin'], ['jawR', 'chin'], ['chin', 'jawL'], ['browRo', 'browRi'], ['browRi', 'browMid'], ['browMid', 'browLi'],
    ['browLi', 'browLo'], ['browRo', 'cheekR'], ['cheekR', 'jawR'], ['browLo', 'cheekL'], ['cheekL', 'jawL'], ['eyeR', 'bridge'], ['bridge', 'eyeL']];
  var TPL = {}; mask.TPL.forEach(function (t) { TPL[t[0]] = t; });

  /* ── the head and its drawings ── */
  /** The head that turns (the turn set marked as the head, else the first turn set). */
  mask.headSet = function (ch) {
    var turn = function (k) { return k.type === 'set' && k.pick && k.pick.by === 'turn'; };
    return AL.find(ch.root, function (k) { return turn(k) && k.role === 'head'; }) || AL.find(ch.root, turn);
  };
  /** The drawings of the head the user places on (a mirrored angle reads its source's places, as every landmark). */
  mask.angles = function (set) { return (set.kids || []).filter(function (k) { return k.type !== 'mirror' && (k.type === 'group' || k.type === 'art' || k.type === 'set'); }); };
  function piece(set, make) { return MK.piece(set, '', make); }
  mask.piece = function (set) { return piece(set, false); };
  /** Is mask point i one the mask moves (a template point or one added on the mask), not a landmark made elsewhere? */
  function ours(p) { return !!(p && (p.key || p.mask)); }
  mask.has = function (set) { var mk = set && piece(set, false); return !!(mk && mk.pts.some(ours)); };
  mask.data = function (set) { var f = set.face || (set.face = {}); ['turn', 'mine', 'hid', 'ok'].forEach(function (k) { f[k] = f[k] || {}; }); return f; };
  function mineList(set, a) { var f = mask.data(set); return f.mine[a] || (f.mine[a] = []); }
  mask.isMine = function (set, a, i) { var f = set.face; return !!(f && f.mine && f.mine[a] && f.mine[a].indexOf(i) >= 0); };
  function said(set, a, i) { var f = set.face; return f && f.hid && f.hid[a] ? f.hid[a][i] : undefined; }
  /** The 3D place of point i: learned from the user's places, else the template's, else the one an added point was given. */
  function base3(p) { var t = p.key && TPL[p.key]; return t ? [t[2], t[3], t[4]] : p.xyz ? p.xyz.slice() : null; }

  /* ── geometry: turning the mask, and fitting it to a drawing ── */
  function R3(yaw, pitch) { return { cy: Math.cos(yaw * DG), sy: Math.sin(yaw * DG), cp: Math.cos(pitch * DG), sp: Math.sin(pitch * DG) }; }
  function fwd(r, p) { var x = p[0] * r.cy + p[2] * r.sy, z = -p[0] * r.sy + p[2] * r.cy, y = p[1]; return [x, y * r.cp - z * r.sp, y * r.sp + z * r.cp]; }
  function back(r, q) { var y1 = q[1] * r.cp + q[2] * r.sp, z1 = -q[1] * r.sp + q[2] * r.cp; return [q[0] * r.cy - z1 * r.sy, y1, q[0] * r.sy + z1 * r.cy]; }
  mask.R3 = R3; mask.fwd = fwd; mask.back = back;
  /** Across and down scaled on their own, no shear (a creature's face can be flatter or longer than a person's). L: [x, y, u, v]. */
  function solveMap(L) {
    var n = L.length; if (n < 2) return null;
    var mx = 0, my = 0, mu = 0, mv = 0; L.forEach(function (l) { mx += l[0]; my += l[1]; mu += l[2]; mv += l[3]; }); mx /= n; my /= n; mu /= n; mv /= n;
    var cx = 0, vx = 0, cy = 0, vy = 0; L.forEach(function (l) { cx += (l[0] - mx) * (l[2] - mu); vx += (l[0] - mx) * (l[0] - mx); cy += (l[1] - my) * (l[3] - mv); vy += (l[1] - my) * (l[1] - my); });
    var sx = vx > 1e-5 ? cx / vx : null, sy = vy > 1e-5 ? cy / vy : null;
    if (sx == null && sy == null) return null; if (sx == null) sx = sy; if (sy == null) sy = sx;
    return { sx: sx, sy: sy, tx: mu - sx * mx, ty: mv - sy * my };
  }
  function turnCost(cons, yaw, pitch, prior) {
    var r = R3(yaw, pitch), behind = 0;
    var L = cons.map(function (c) { var q = fwd(r, c.p3); if (q[2] < -0.08) behind++; return [q[0], q[1], c.uv[0], c.uv[1]]; });
    var m = solveMap(L); if (!m || m.sx <= 0 || m.sy <= 0) return 1e9;
    var e = 0; L.forEach(function (l) { var ex = m.sx * l[0] + m.tx - l[2], ey = m.sy * l[1] + m.ty - l[3]; e += ex * ex + ey * ey; });
    e = e / L.length / ((m.sx * m.sx + m.sy * m.sy) / 2);
    var ratio = Math.log(m.sx / m.sy);
    return e + 0.01 * ratio * ratio + 0.04 * behind + 2e-6 * (yaw - prior[0]) * (yaw - prior[0]) + 1.2e-5 * (pitch - prior[1]) * (pitch - prior[1]);
  }
  /** A drawing's real turn, worked out from where its points are (a coarse look over every turn, then a fine one). */
  function fitTurn(cons, prior) {
    if (cons.length < 3) return { yaw: prior[0], pitch: prior[1], sure: false };
    var best = { c: 1e9, yaw: prior[0], pitch: prior[1] }, y, p, c;
    for (y = -90; y <= 90; y += 3) for (p = -45; p <= 45; p += 5) { c = turnCost(cons, y, p, prior); if (c < best.c) best = { c: c, yaw: y, pitch: p }; }
    var b0 = best;
    for (y = b0.yaw - 3; y <= b0.yaw + 3; y++) for (p = b0.pitch - 5; p <= b0.pitch + 5; p++) { if (Math.abs(y) > 90 || Math.abs(p) > 50) continue; c = turnCost(cons, y, p, prior); if (c < best.c) best = { c: c, yaw: y, pitch: p }; }
    return { yaw: best.yaw, pitch: best.pitch, sure: cons.length >= 4 };
  }
  mask.fitTurn = fitTurn;
  /** Only a nudge: the dot on the pad (the profiles about 75 degrees, up and down about 30). */
  function prior(kid) { var at = kid && kid.at || [0, 0]; return [Math.max(-1, Math.min(1, at[0])) * 75, -Math.max(-1, Math.min(1, at[1])) * 30]; }
  function solve3(A, b) {
    var Mm = A.map(function (r, i) { return r.concat([b[i]]); }), c, r, k;
    for (c = 0; c < 3; c++) { var pv = c; for (r = c + 1; r < 3; r++) if (Math.abs(Mm[r][c]) > Math.abs(Mm[pv][c])) pv = r; var t = Mm[c]; Mm[c] = Mm[pv]; Mm[pv] = t; var d = Mm[c][c] || 1e-9; for (r = 0; r < 3; r++) if (r !== c) { var f = Mm[r][c] / d; for (k = c; k < 4; k++) Mm[r][k] -= f * Mm[c][k]; } }
    return [Mm[0][3] / (Mm[0][0] || 1e-9), Mm[1][3] / (Mm[1][1] || 1e-9), Mm[2][3] / (Mm[2][2] || 1e-9)];
  }

  /* ── Snap to parts: slices whose names say what they are (the naming guide) ── */
  function norm(s) { return String(s || '').toLowerCase().replace(/[^a-z]/g, ''); }
  mask.NAMING = [['Left Eye, Right Eye', 'or Left Pupil, Right Pupil'], ['Left Brow, Right Brow', 'or Left Eyebrow, Right Eyebrow'],
    ['Nose', 'or Snout, Beak, Bill'], ['Mouth', ''], ['Chin', ''], ['Forehead', '']];
  /** The slices of one drawing, by plain name, with their middles and sizes in the set's frame. */
  function partsOf(kid) {
    var out = {}; AL.walk(kid, function (k) { if (k === kid || !k.name) return; var b = AL.boundsOf(k); if (b) { var n = norm(k.name); if (!out[n]) out[n] = b; } });
    return out;
  }
  function partOf(P, keys) {
    var ks = Object.keys(P), i, k;
    for (i = 0; i < keys.length; i++) if (P[keys[i]]) return P[keys[i]];
    for (i = 0; i < keys.length; i++) {
      var key = keys[i], c = ks.filter(function (n) { return n.indexOf(key) === 0 && !(/eye$/.test(key) && n.slice(key.length).indexOf('brow') === 0); }).sort(function (x, y) { return x.length - y.length; })[0];
      if (c) return P[c];
    }
    return null;
  }
  /** Where the named slices put the mask's points on one drawing ({ key: [x, y] }), as far as the drawing's turn shows them. */
  mask.snapOf = function (set, kid, turn) {
    var P = partsOf(kid), t = {}, r = R3(turn[0], turn[1]), b;
    function mid(q) { return [(q.x0 + q.x1) / 2, (q.y0 + q.y1) / 2]; }
    function vis(key) { var q = TPL[key]; return fwd(r, [q[2], q[3], q[4]])[2] > -0.05; }
    if ((b = partOf(P, ['lefteye', 'leftpupil'])) && vis('eyeL')) t.eyeL = mid(b);
    if ((b = partOf(P, ['righteye', 'rightpupil'])) && vis('eyeR')) t.eyeR = mid(b);
    if ((b = partOf(P, ['nose', 'snout', 'beak', 'bill']))) { var w = b.x1 - b.x0, s = Math.min(1, Math.abs(Math.sin(turn[0] * DG)) * 1.1); t.snout = [mid(b)[0] * (1 - s) + (turn[0] < 0 ? b.x0 + w * 0.02 : b.x1 - w * 0.02) * s, mid(b)[1]]; }
    if ((b = partOf(P, ['mouth']))) { var cy = (b.y0 + b.y1) / 2, ww = b.x1 - b.x0; if (vis('mouthR')) t.mouthR = [b.x0 + ww * 0.06, cy]; if (vis('mouthL')) t.mouthL = [b.x1 - ww * 0.06, cy]; }
    if ((b = partOf(P, ['chin']))) t.chin = [(b.x0 + b.x1) / 2, b.y0 + (b.y1 - b.y0) * 0.7];
    if ((b = partOf(P, ['forehead']))) t.foreMid = mid(b);
    if (t.eyeL && t.eyeR && t.eyeL[0] < t.eyeR[0]) { t._flipped = true; var e = t.eyeL; t.eyeL = t.eyeR; t.eyeR = e; }   // the left eye on the picture's left: a flipped copy whose names were kept
    var eyeMid = t.eyeL && t.eyeR ? (t.eyeL[0] + t.eyeR[0]) / 2 : t.snout ? t.snout[0] : null;
    (t._flipped ? [['R', ['lefteyebrow', 'leftbrow']], ['L', ['righteyebrow', 'rightbrow']]] : [['L', ['lefteyebrow', 'leftbrow']], ['R', ['righteyebrow', 'rightbrow']]]).forEach(function (sk) {
      var s2 = sk[0]; if (!(b = partOf(P, sk[1])) || !vis('brow' + s2 + 'i') || eyeMid == null) return;
      var cy2 = (b.y0 + b.y1) / 2, w2 = b.x1 - b.x0, e0 = [b.x0 + w2 * 0.06, cy2], e1 = [b.x1 - w2 * 0.06, cy2];
      var inner = Math.abs(e0[0] - eyeMid) < Math.abs(e1[0] - eyeMid) ? e0 : e1; t['brow' + s2 + 'i'] = inner; if (vis('brow' + s2 + 'o')) t['brow' + s2 + 'o'] = inner === e0 ? e1 : e0;
    });
    return t;
  };

  /* ── the model: every drawing's real turn, the mask fitted to it, and where each point sits ── */
  /**
   * Works everything out from the user's places. Returns { angles: { <id>: { turn, sure, said, map, pts: [{ x, y, z, state, how }] } },
   * shape: [3D per point] }. `snaps` (optional) = { <id>: snapOf(...) } so a caller can keep them steady during a drag.
   */
  mask.solve = function (set, opts) {
    opts = opts || {};
    var mk = piece(set, false), f = mask.data(set), kids = mask.angles(set), out = { angles: {}, shape: [] };
    if (!mk) return out;
    var P = mk.pts, snaps = {};
    kids.forEach(function (k) { var tr = f.turn[k.id] || prior(k); snaps[k.id] = opts.snaps && opts.snaps[k.id] || mask.snapOf(set, k, tr); });
    out.snaps = snaps;
    function consOf(k, shape) {                                    // what holds the mask on a drawing: your places, else a named slice's
      var row = mk.at[k.id], c = [];
      P.forEach(function (p, i) {
        if (!ours(p) || !shape[i]) return;
        var st = said(set, k.id, i); if (st === 'behind' || st === 'off') return;
        if (mask.isMine(set, k.id, i) && row && row[i]) c.push({ i: i, p3: shape[i], uv: [row[i][0], row[i][1]], mine: true });
        else if (p.key && snaps[k.id][p.key]) c.push({ i: i, p3: shape[i], uv: snaps[k.id][p.key], mine: false });
      });
      return c;
    }
    function mapFor(k, turn, cons) {
      var r = R3(turn[0], turn[1]), m = solveMap(cons.map(function (c) { var q = fwd(r, c.p3); return [q[0], q[1], c.uv[0], c.uv[1]]; }));
      if (!m || m.sx <= 0 || m.sy <= 0) { var b = AL.boundsOf(k) || { x0: 0, y0: 0, x1: 100, y1: 100 }, w = b.x1 - b.x0, hh = b.y1 - b.y0, sc = Math.min(w, hh) * 0.16; m = { sx: sc, sy: sc, tx: b.x0 + w * 0.5, ty: b.y0 + hh * 0.62 }; }
      return m;
    }
    /* the creature's own face shape: each point lifted into 3D from the drawings it was placed on (depth from two turned apart) */
    function learn(turns, maps) {
      return P.map(function (p, i) {
        var b3 = base3(p); if (!ours(p) || !b3 || opts.noLearn) return b3;
        var obs = [];
        kids.forEach(function (k) {
          if (!turns[k.id] || !mask.isMine(set, k.id, i)) return; var st = said(set, k.id, i); if (st === 'behind' || st === 'off') return;
          var q = mk.at[k.id] && mk.at[k.id][i], m = maps[k.id]; if (!q || !m) return;
          obs.push({ r: R3(turns[k.id][0], turns[k.id][1]), yaw: turns[k.id][0], m: [(q[0] - m.tx) / m.sx, (q[1] - m.ty) / m.sy] });
        });
        if (!obs.length) return b3;
        var yaws = obs.map(function (o) { return o.yaw; }), spread = Math.max.apply(null, yaws) - Math.min.apply(null, yaws);
        if (obs.length >= 2 && spread >= 15) {
          var A = [[0, 0, 0], [0, 0, 0], [0, 0, 0]], bb = [0, 0, 0], w0 = 0.15, a, j, kk;
          obs.forEach(function (o) { var r = o.r, rows = [[r.cy, 0, r.sy], [r.sp * r.sy, r.cp, -r.sp * r.cy]]; rows.forEach(function (row, k2) { for (a = 0; a < 3; a++) { bb[a] += row[a] * o.m[k2]; for (j = 0; j < 3; j++) A[a][j] += row[a] * row[j]; } }); });
          for (kk = 0; kk < 3; kk++) { A[kk][kk] += w0; bb[kk] += w0 * b3[kk]; }
          return solve3(A, bb);
        }
        var o0 = obs[0], z = fwd(o0.r, b3)[2]; return back(o0.r, [o0.m[0], o0.m[1], z]);
      });
    }
    var shape0 = P.map(base3), turns = {}, maps = {}, sure = {}, k1;
    /* 1. drawings whose turn the user gave teach the shape first; 2. every other drawing's turn is worked out with it */
    kids.forEach(function (k) { if (f.turn[k.id]) { turns[k.id] = f.turn[k.id]; maps[k.id] = mapFor(k, turns[k.id], consOf(k, shape0)); } });
    var known = learn(turns, maps);
    kids.forEach(function (k) {
      if (f.turn[k.id]) { sure[k.id] = true; return; }
      var cons = consOf(k, known), ft = mask.fitTurn(cons, prior(k)); turns[k.id] = [ft.yaw, ft.pitch];
      sure[k.id] = cons.filter(function (c) { return c.mine; }).length >= 3;            // named slices alone give only a rough guess
    });
    kids.forEach(function (k) { maps[k.id] = mapFor(k, turns[k.id], consOf(k, known)); });
    var shape = learn(turns, maps); out.shape = shape;
    /* 3. where each point sits on each drawing */
    for (k1 = 0; k1 < kids.length; k1++) {
      var k = kids[k1], r = R3(turns[k.id][0], turns[k.id][1]), cons = consOf(k, shape), m = mapFor(k, turns[k.id], cons), row = mk.at[k.id];
      var proj = function (p3) { var q = fwd(r, p3); return { x: m.sx * q[0] + m.tx, y: m.sy * q[1] + m.ty, fx: q[0], fy: q[1], z: q[2] }; };
      var res = cons.map(function (c) { var q = proj(c.p3); return { fx: q.fx, fy: q.fy, dx: c.uv[0] - q.x, dy: c.uv[1] - q.y }; });
      var pts = P.map(function (p, i) {
        var cur = row && row[i];
        if (!ours(p)) return cur ? { x: cur[0], y: cur[1], z: 0, state: MK.stateOf(cur), how: 'theirs' } : null;    // a landmark made elsewhere: left as it is
        var q = proj(shape[i] || [0, 0, 0]), st = said(set, k.id, i), auto = q.z < -0.05, how = 'follow', x = q.x, y = q.y;
        if (mask.isMine(set, k.id, i) && cur) { x = cur[0]; y = cur[1]; how = 'mine'; }
        else if (p.key && snaps[k.id][p.key] && st !== 'behind' && st !== 'off') { x = snaps[k.id][p.key][0]; y = snaps[k.id][p.key][1]; how = 'snap'; }
        else if (res.length) { var wx = 0, wy = 0, ws = 0; res.forEach(function (o) { var w = 1 / ((o.fx - q.fx) * (o.fx - q.fx) + (o.fy - q.fy) * (o.fy - q.fy) + 0.06); wx += o.dx * w; wy += o.dy * w; ws += w; }); x += wx / ws * 0.85; y += wy / ws * 0.85; }
        var state = st || (auto ? 'behind' : 'here');
        return { x: x, y: y, z: q.z, state: state, how: how, auto: !st && auto };
      });
      out.angles[k.id] = { turn: turns[k.id], sure: !!sure[k.id], said: !!f.turn[k.id], map: m, pts: pts, flipped: !!snaps[k.id]._flipped, placed: (f.mine[k.id] || []).length, ok: !!f.ok[k.id] };
    }
    return out;
  };

  /** Write the guesses into the landmark rows (every drawing; the user's places only take their hidden / shown state). */
  mask.carry = function (set, opts) {
    var mk = piece(set, false); if (!mk) return null;
    var sol = mask.solve(set, opts);
    Object.keys(sol.angles).forEach(function (id) {
      var row = MK.row(mk, id), A = sol.angles[id];
      A.pts.forEach(function (q, i) {
        if (!q || q.how === 'theirs') return;
        var x = Math.round(q.x * 10) / 10, y = Math.round(q.y * 10) / 10;
        row[i] = q.state === 'behind' || q.state === 'off' ? [x, y, q.state] : [x, y];
      });
      if (!row.some(function (q) { return q && q[2] !== 'off'; })) row[0] = [row[0][0], row[0][1]];      // (a row must place something: the rules)
    });
    var f = mask.data(set); f.fit = {};
    Object.keys(sol.angles).forEach(function (id) { var A = sol.angles[id]; f.fit[id] = [A.turn[0], A.turn[1], Math.round(A.map.sx * 100) / 100, Math.round(A.map.sy * 100) / 100]; });
    if (AL.maskPoses) AL.maskPoses.carry(set);                       // stage 3: the poses, carried by the new turns
    return sol;
  };

  /* ── stage 2: your face drives the mask ──
     Each mask point follows its face points' movement from your neutral face (al-face.js points: the head's turn, tilt and
     distance already taken out), in mask units, as signals mk.<n>.x / .y (recorded and played back like every face lane). The
     solver turns that movement to each drawing's real turn and bends the drawing last, as one piece, held at its edges
     (al-solve.js, the mask pass). With the front camera the character mirrors you: its right side follows your left. */
  var PARTNER = { 107: 336, 336: 107, 70: 300, 300: 70, 33: 263, 263: 33, 133: 362, 362: 133, 205: 425, 425: 205, 61: 291, 291: 61, 172: 397, 397: 172 };
  /** The face points the mask follows (both sides, so the camera's mirror can choose). */
  mask.watch = function (ch) {
    var set = mask.headSet(ch), mk = set && piece(set, false), seen = {}, out = []; if (!mk) return out;
    mk.pts.forEach(function (p) { (p.face || []).forEach(function (i) { [i, PARTNER[i]].forEach(function (j) { if (j != null && !seen[j]) { seen[j] = 1; out.push(j); } }); }); });
    return out;
  };
  /** Mask units per unit of the face points: the eyes are 0.9 apart on the mask. */
  function unitOf(cal) {
    var P = cal && cal.points; if (!P) return 0.75;
    function c(a, b) { return [(P[a * 3] + P[b * 3]) / 2, (P[a * 3 + 1] + P[b * 3 + 1]) / 2]; }
    var l = c(33, 133), r = c(263, 362), d = Math.hypot(l[0] - r[0], l[1] - r[1]);
    return d > 1e-6 ? 0.9 / d : 0.75;
  }
  /** From the face points' signals (already eased) to the mask's: S['mk.<n>.x' / '.y']. */
  mask.drive = function (inp, S) {
    var ch = inp.app.ch, set = mask.headSet(ch), mk = set && piece(set, false); if (!mk) return;
    var st = inp.cam && inp.cam._faceStage, mirrored = !(st && st.tun && st.tun.mirror), k = unitOf(st && st.cal), raw = {};
    mk.pts.forEach(function (p) {
      if (!p.face || !p.face.length) return;
      var sx = 0, sy = 0, n = 0;
      p.face.forEach(function (i) { var j = mirrored && PARTNER[i] != null ? PARTNER[i] : i, x = S['pt.' + j + '.x'], y = S['pt.' + j + '.y']; if (typeof x === 'number') { sx += x; sy += y || 0; n++; } });
      raw[p.n] = [n ? sx / n * k : 0, n ? sy / n * k : 0];
    });
    inp._mkRaw = raw;                                                  // (Make the face keeps this)
    /* stage 3: how much of each pose your face holds; the poses carry that, and what they do not explain follows directly */
    var MP = AL.maskPoses, poses = MP ? MP.list(set).filter(function (q) { return q.face; }) : [], rest = raw, f = set.face || {};
    for (var key in S) if (key.slice(0, 3) === 'mp.') delete S[key];
    if (poses.length) { var wt = MP.weights(set, raw); poses.forEach(function (q) { S['mp.' + q.id] = wt.w[q.id] || 0; }); rest = wt.rest; }
    var dg = f.direct == null ? 1 : f.direct;
    Object.keys(raw).forEach(function (n) { var v = rest[n] || [0, 0]; S['mk.' + n + '.x'] = v[0] * (poses.length ? dg : 1); S['mk.' + n + '.y'] = v[1] * (poses.length ? dg : 1); });
  };
  mask.swapsNow = function (ch, S) { var set = mask.headSet(ch); return set && AL.maskPoses ? AL.maskPoses.swapsNow(set, S) : null; };
  /** For the solver: how drawing `kid` of the head bends by the mask now (signals s), in the head's frame:
      { P, Q, moved }, or null when the head has no mask or the drawing no row. Points with no face (your own, landmarks made
      elsewhere) hold still; hidden and "not here" points take no part. All points: set.face.gain. */
  mask.bendFor = function (set, kid, s) {
    var mk = piece(set, false), f = set.face, fit = f && f.fit && f.fit[kid.id], row = mk && mk.at[kid.id]; if (!fit || !row) return null;
    var r = R3(fit[0], fit[1]), g = f.gain == null ? 1 : f.gain, P = [], Q = [], moved = false, posed = f.posed;
    mk.pts.forEach(function (p, i) {
      var q = row[i]; if (!q || q[2] === 'off' || q[2] === 'behind') return;
      var dx = 0, dy = 0;
      if (p.face && p.face.length) { var mx = s['mk.' + p.n + '.x'] || 0, my = s['mk.' + p.n + '.y'] || 0; if (mx || my) { var v = fwd(r, [mx, my, 0]); dx = v[0] * fit[2] * g; dy = v[1] * fit[3] * g; } }
      if (posed) for (var pid in posed) { var wv = s['mp.' + pid]; if (!wv) continue; var on = posed[pid][kid.id], mv = on && on[p.n]; if (mv) { dx += mv[0] * wv; dy += mv[1] * wv; } }      // your poses, as strong as your face holds them
      if (Math.abs(dx) > 0.01 || Math.abs(dy) > 0.01) moved = true;
      P.push([q[0], q[1]]); Q.push([q[0] + dx, q[1] + dy]);
    });
    return P.length ? { P: P, Q: Q, moved: moved } : null;
  };
  mask.setGain = function (set, g) { mask.data(set).gain = g; };
  mask.gain = function (set) { var f = set && set.face; return f && f.gain != null ? f.gain : 1; };

  /* ── edits (each called inside app.edit, so one is one undo step) ── */
  /** Put the mask on a head: the template's points after any landmarks it already has, guessed on every drawing. */
  mask.create = function (set) {
    var mk = piece(set, true), f = mask.data(set), first = mk.pts.length;
    mask.angles(set).forEach(function (k) { MK.row(mk, k.id); });
    mask.TPL.forEach(function (t) {
      mk.pts.push({ n: mk.pts.length + 1, name: t[1], key: t[0], face: t[6].slice(), handle: t[5] ? true : undefined });
      Object.keys(mk.at).forEach(function (id) { mk.at[id].push([0, 0]); });
    });
    mk.pts.forEach(function (p) { if (p.handle === undefined) delete p.handle; });
    void f; mask.carry(set);
    return first;
  };
  /** Place point i on drawing a by hand: it is yours there and stays put. */
  mask.place = function (set, a, i, x, y) {
    var mk = piece(set, false); if (!mk) return; MK.row(mk, a);
    var q = mk.at[a][i], st = said(set, a, i); mk.at[a][i] = st === 'behind' || st === 'off' ? [x, y, st] : [x, y];
    if (!q || true) { var L = mineList(set, a); if (L.indexOf(i) < 0) L.push(i); }
    mask.carry(set);
  };
  /** Let point i follow the mask again on drawing a. */
  mask.follow = function (set, a, i) { var L = mineList(set, a), k = L.indexOf(i); if (k >= 0) L.splice(k, 1); mask.carry(set); };
  /** Hidden on this drawing (behind), not on it (off), shown (here), or back to the guess (null). */
  mask.hide = function (set, a, i, st) { var f = mask.data(set); f.hid[a] = f.hid[a] || {}; if (st == null) delete f.hid[a][i]; else f.hid[a][i] = st; if (!Object.keys(f.hid[a]).length) delete f.hid[a]; mask.carry(set); };
  /** A drawing's real turn as the user says it ([yaw, pitch]), or null to work it out from the points again. */
  mask.setTurn = function (set, a, t) { var f = mask.data(set); if (t) f.turn[a] = [Math.round(t[0]), Math.round(t[1])]; else delete f.turn[a]; mask.carry(set); };
  mask.setOk = function (set, a, on) { var f = mask.data(set); if (on) f.ok[a] = true; else delete f.ok[a]; };
  /** A point of your own (a horn tip): placed here, then carried to every drawing with the mask (its depth from the mask around it). */
  mask.addPoint = function (set, a, x, y, name) {
    var mk = piece(set, false); if (!mk) return -1;
    var sol = mask.solve(set), A = sol.angles[a]; if (!A) return -1;
    var m = A.map, r = R3(A.turn[0], A.turn[1]), fx = (x - m.tx) / m.sx, fy = (y - m.ty) / m.sy, zs = 0, ws = 0;
    sol.shape.forEach(function (s, i) { if (!s || !ours(mk.pts[i])) return; var q = fwd(r, s); var w = 1 / ((q[0] - fx) * (q[0] - fx) + (q[1] - fy) * (q[1] - fy) + 0.02); zs += q[2] * w; ws += w; });
    var xyz = back(r, [fx, fy, ws ? zs / ws : 0.5]), places = {}; places[a] = [x, y];
    var i = MK.add(mk, places); mk.pts[i].name = name || 'Point ' + (i + 1); mk.pts[i].mask = true; mk.pts[i].xyz = xyz.map(function (v) { return Math.round(v * 1000) / 1000; });
    mineList(set, a).push(i);
    mask.carry(set);
    return i;
  };
  /** Take an added point off every drawing (the points after it are numbered again, and so are the user's places and hidings). */
  mask.removePoint = function (set, i) {
    var f = mask.data(set); AL.marks.remove(set, '', i);
    Object.keys(f.mine).forEach(function (a) { f.mine[a] = f.mine[a].filter(function (j) { return j !== i; }).map(function (j) { return j > i ? j - 1 : j; }); });
    Object.keys(f.hid).forEach(function (a) { var o = {}; Object.keys(f.hid[a]).forEach(function (j) { j = +j; if (j !== i) o[j > i ? j - 1 : j] = f.hid[a][j]; }); f.hid[a] = o; });
    if (piece(set, false)) mask.carry(set);
  };
  mask.rename = function (set, i, name) { var mk = piece(set, false); if (mk && mk.pts[i]) mk.pts[i].name = String(name || '').slice(0, 40) || 'Point ' + (i + 1); };
  mask.setHandle = function (set, i, on) { var mk = piece(set, false); if (mk && mk.pts[i]) { if (on) mk.pts[i].handle = true; else delete mk.pts[i].handle; } };
  /** Start this drawing again: every place on it back to the guess (the user's own points stay). */
  mask.resetAngle = function (set, a) { var f = mask.data(set); delete f.mine[a]; delete f.hid[a]; delete f.ok[a]; delete f.turn[a]; mask.carry(set); };
  /** Snap to parts on one drawing: the handles go to the named slices, as your places. Returns how many snapped. */
  mask.snapAngle = function (set, a) {
    var mk = piece(set, false), kid = AL.node(null, a); void kid;
    var k = mask.angles(set).filter(function (q) { return q.id === a; })[0]; if (!mk || !k) return 0;
    var sol = mask.solve(set), sn = sol.snaps[a] || {}, n = 0, L = mineList(set, a);
    mk.pts.forEach(function (p, i) { if (p.key && sn[p.key]) { MK.row(mk, a)[i] = [sn[p.key][0], sn[p.key][1]]; if (L.indexOf(i) < 0) L.push(i); n++; } });
    mask.carry(set);
    return n;
  };
  /** Take the mask off: its points go (landmarks made elsewhere stay), and set.face with them. */
  mask.remove = function (set) {
    var mk = piece(set, false); if (!mk) return;
    for (var i = mk.pts.length - 1; i >= 0; i--) if (ours(mk.pts[i])) MK.remove(set, '', i);
    delete set.face;
  };
  /** Plain words for a turn, in the owner's own terms (toward his Left Profile or Right Profile): "25° left", "face on", "12° left, 30° up". */
  mask.turnWords = function (t, long) {
    var y = Math.round(t[0]), p = Math.round(t[1]), s = Math.abs(y) < 6 ? 'face on' : long ? 'about ' + Math.abs(y) + '° toward ' + (y < 0 ? 'Left Profile' : 'Right Profile') : Math.abs(y) + '° ' + (y < 0 ? 'left' : 'right');
    if (Math.abs(p) >= 6) s += (long ? ', ' + Math.abs(p) + '° ' + (p > 0 ? 'looking up' : 'looking down') : ', ' + Math.abs(p) + '° ' + (p > 0 ? 'up' : 'down'));
    return s;
  };
  mask.ours = ours;
})(typeof self !== 'undefined' ? self : this);
