/* AvatarLab: the pose solver. ONE authority: signals in, a list of textured meshes out.
 * The stage, the recorder replay and the export all call this, so they cannot disagree.
 *
 *   var out = solver.eval(S, { live:true, dt:1/60 });
 *   out.items = [{ id, img, v:Float32Array(xy...), uv, idx, a }]   bottom-first
 *
 * No DOM, no GL: plain arithmetic that ports to Java line for line. Phone twins are named at each
 * step (PuppetPoseResolver, PinWarpStrip, FabrikSolver, DangleSim, PuppetRigSolver/MLS).
 */
(function (root) {
  'use strict';
  var AL = root.AL = root.AL || {};
  var DEG = Math.PI / 180;
  function hyp(x, y) { return Math.sqrt(x * x + y * y); }
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function smooth(t) { t = clamp(t, 0, 1); return t * t * (3 - 2 * t); }
  function wrapA(a) { while (a > Math.PI) a -= 2 * Math.PI; while (a < -Math.PI) a += 2 * Math.PI; return a; }

  /* 2x3 affine: [a b c d e f]  x' = a x + c y + e,  y' = b x + d y + f */
  var M = AL.M = {
    id: function () { return [1, 0, 0, 1, 0, 0]; },
    mul: function (m, n) { return [m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1], m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3], m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5]]; },
    trs: function (tx, ty, rot, sx, sy, px, py) {
      var c = Math.cos(rot), s = Math.sin(rot), a = c * sx, b = s * sx, c2 = -s * sy, d = c * sy;
      return [a, b, c2, d, px - (a * px + c2 * py) + tx, py - (b * px + d * py) + ty];
    },
    flipX: function (ax) { return [-1, 0, 0, 1, 2 * ax, 0]; },
    ap: function (m, x, y) { return [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]]; },
    inv: function (m) { var det = m[0] * m[3] - m[1] * m[2] || 1e-9; return [m[3] / det, -m[1] / det, -m[2] / det, m[0] / det, (m[2] * m[5] - m[3] * m[4]) / det, (m[1] * m[4] - m[0] * m[5]) / det]; }
  };

  /* a mouth set rarely has every sound: these are the nearest stand-ins */
  var VFALL = { S: ['Ee', 'D'], D: ['S', 'Ee'], F: ['M'], L: ['D', 'Ee'], R: ['W-Oo', 'Oh'], 'W-Oo': ['Oh'], Oh: ['W-Oo'], Ee: ['S'], Uh: ['Aa', 'Oh'], Surprised: ['Oh'], Smile: ['Ee'] };

  /**
   * A BRIDGE is a handful of matched landmarks between two neighbouring head angles (the same crest spike in
   * drawing A and in drawing B). As the head turns from A toward B, A is warped so its landmarks travel to
   * the meeting point; B arrives already warped to the same point and relaxes. The eye sees one shape turn.
   * Returned as seen from A: [{ from: where A has it, to: where it should be when B takes over }].
   */
  AL.bridgeFor = function (ch, set, A, B, part, full) {
    var bs = set.bridges || [], ax = set.axisX == null ? ch.w / 2 : set.axisX, pk = part || '';
    /* part: the landmarks of one PIECE of the drawing (a frill, a horn) that warps on its own; '' = the whole drawing.
       full: all the way from A to B (two looks that cross-fade), instead of only as far as where they meet. */
    function find(a, b) { for (var i = 0; i < bs.length; i++) { if ((bs[i].part || '') !== pk) continue; if (bs[i].a === a && bs[i].b === b) return { br: bs[i], rev: false }; if (bs[i].a === b && bs[i].b === a) return { br: bs[i], rev: true }; } return null; }
    var hit = find(A.id, B.id), flip = false;
    if (!hit && (A.type === 'mirror' || B.type === 'mirror')) {     // the far side of the face: the mirror image of a bridge matched by hand
      var a2 = A.type === 'mirror' ? A.of : (A.at && Math.abs(A.at[0]) < 0.05 ? A.id : null), b2 = B.type === 'mirror' ? B.of : (B.at && Math.abs(B.at[0]) < 0.05 ? B.id : null);
      if (a2 && b2) { hit = find(a2, b2); flip = !!hit; }
    }
    if (!hit || !hit.br.pts || !hit.br.pts.length) return null;
    var meet = hit.br.meet == null ? 0.5 : hit.br.meet;
    return hit.br.pts.map(function (q) {
      var pa = hit.rev ? q.b : q.a, pb = hit.rev ? q.a : q.b, m = full ? 1 : hit.rev ? 1 - meet : meet, fx = pa[0], tx = pa[0] + (pb[0] - pa[0]) * m;
      if (flip) { fx = 2 * ax - fx; tx = 2 * ax - tx; }
      return { from: [fx, pa[1]], to: [tx, pa[1] + (pb[1] - pa[1]) * m] };
    });
  };

  AL.restSignals = function () {
    return { turnX: 0, turnY: 0, roll: 0, lookX: 0, lookY: 0, blinkL: 0, blinkR: 0, browL: 0, browR: 0, mouth: null, viseme: 'Neutral',
      lidUp: 0, lidLow: 0, lidTilt: 0, wind: 0, breath: 0, sway: 0, bounce: 0, hand: { L: null, R: null, FL: null, FR: null }, foot: { FL: 0, FR: 0 }, spin: 0, root: { x: 0, y: 0 }, rootScale: 1, walk: 0, dial: {}, trig: {}, pin: {}, flip: false, t: 0 };
  };

  /* ── two-bone reach (phone: FabrikSolver for the chain, PinWarpStrip for the skin) ── */
  function solveLimb(l, T) {
    var ax = l.a.x, ay = l.a.y, bx = l.b.x, by = l.b.y, cx = l.c.x, cy = l.c.y;
    var L1 = hyp(bx - ax, by - ay), L2 = hyp(cx - bx, cy - by);
    if (L1 < 1 || L2 < 1) return null;
    var dx = T.x - ax, dy = T.y - ay, d = hyp(dx, dy);
    if (d < 0.001) { dx = cx - ax; dy = cy - ay; d = hyp(dx, dy) || 1; }
    var ux = dx / d, uy = dy / d, st = 1, full = L1 + L2;
    var stMax = 1 + (l.stretch == null ? 0.12 : l.stretch);
    if (d > full) st = Math.min(d / full, stMax);
    var l1 = L1 * st, l2 = L2 * st;
    var dd = clamp(d, Math.abs(l1 - l2) + 0.001, l1 + l2 - 0.001);
    var A = Math.acos(clamp((l1 * l1 + dd * dd - l2 * l2) / (2 * l1 * dd), -1, 1));
    var cr = (cx - ax) * (by - ay) - (cy - ay) * (bx - ax), side;
    if (Math.abs(cr) > 0.09 * L1 * L2) side = cr > 0 ? 1 : -1;
    else {                                                 // a straight limb bends the way it was told to: elbows out and down, knees forward
      var base = Math.atan2(uy, ux), ox = l.out ? l.out[0] : 0, oy = l.out ? l.out[1] : 1;
      side = (Math.cos(base + A) * ox + Math.sin(base + A) * oy) >= (Math.cos(base - A) * ox + Math.sin(base - A) * oy) ? 1 : -1;
    }
    if (l.flip) side = -side;
    var th = Math.atan2(uy, ux) + side * A;
    var ex = ax + Math.cos(th) * l1, ey = ay + Math.sin(th) * l1;
    var hx = T.x - ex, hy = T.y - ey, hd = hyp(hx, hy) || 1, wx = ex + hx / hd * l2, wy = ey + hy / hd * l2;
    return { st: st, r1: Math.atan2(ey - ay, ex - ax) - Math.atan2(by - ay, bx - ax), r2: Math.atan2(wy - ey, wx - ex) - Math.atan2(cy - by, cx - bx), e: [ex, ey], w: [wx, wy] };
  }
  function warpLimb(src, l, k) {
    var out = new Float32Array(src.length);
    var ax = l.a.x, ay = l.a.y, bx = l.b.x, by = l.b.y, cx = l.c.x, cy = l.c.y;
    var L1 = hyp(bx - ax, by - ay), L2 = hyp(cx - bx, cy - by);
    var d1x = (bx - ax) / L1, d1y = (by - ay) / L1, d2x = (cx - bx) / L2, d2y = (cy - by) / L2;
    var R = (l.soft == null ? 0.3 : l.soft) * Math.min(L1, L2) + 0.5;
    var c1 = Math.cos(k.r1) * k.st, s1 = Math.sin(k.r1) * k.st, c2 = Math.cos(k.r2) * k.st, s2 = Math.sin(k.r2) * k.st;
    /* Round the joint: each point near it is TURNED about the joint by an in-between angle, so the outside of an elbow follows
       an arc. (Sliding straight between the two bones' positions instead cuts the corner: a flat facet outside, a pinch inside.) */
    var dr = k.r2 - k.r1; while (dr > Math.PI) dr -= 2 * Math.PI; while (dr < -Math.PI) dr += 2 * Math.PI;
    for (var i = 0; i < src.length; i += 2) {
      var x = src[i], y = src[i + 1];
      var qx = x - bx, qy = y - by;
      var s = ((qx * d1x + qy * d1y) + (qx * d2x + qy * d2y)) * 0.5;
      var w = smooth((s + R) / (2 * R));
      if (w <= 0) { out[i] = ax + c1 * (x - ax) - s1 * (y - ay); out[i + 1] = ay + s1 * (x - ax) + c1 * (y - ay); continue; }
      if (w >= 1) { out[i] = k.e[0] + c2 * qx - s2 * qy; out[i + 1] = k.e[1] + s2 * qx + c2 * qy; continue; }
      var an = k.r1 + dr * w, ca = Math.cos(an) * k.st, sa = Math.sin(an) * k.st;
      out[i] = k.e[0] + ca * qx - sa * qy; out[i + 1] = k.e[1] + sa * qx + ca * qy;
    }
    return out;
  }
  /*
   * A FOOT with a foot bar (heel to toe) stays level instead of tipping with the shin, and rolls heel-to-toe as it walks:
   * everything nearer the bar than the shin turns about the ankle by `roll` (toe up is positive), whatever the leg is doing.
   */
  function warpFoot(src, out, l, k, roll) {
    var f = l.foot, cx = l.c.x, cy = l.c.y, bx = l.b.x, by = l.b.y, hx = f.heel.x, hy = f.heel.y, tx = f.toe.x, ty = f.toe.y;
    var Rf = Math.max(3, hyp(cx - bx, cy - by) * 0.16), ang = -roll * (tx >= hx ? 1 : -1), cs = Math.cos(ang), sn = Math.sin(ang), wx = k.w[0], wy = k.w[1];
    function dseg(x, y, x0, y0, x1, y1) { var dx = x1 - x0, dy = y1 - y0, L2 = dx * dx + dy * dy || 1, t = clamp(((x - x0) * dx + (y - y0) * dy) / L2, 0, 1); return hyp(x - x0 - dx * t, y - y0 - dy * t); }
    for (var i = 0; i < src.length; i += 2) {
      var x = src[i], y = src[i + 1], w = smooth((dseg(x, y, bx, by, cx, cy) - dseg(x, y, hx, hy, tx, ty)) / (2 * Rf) + 0.5);
      if (w <= 0) continue;
      var rx = x - cx, ry = y - cy, fx = wx + cs * rx - sn * ry, fy = wy + sn * rx + cs * ry;
      out[i] += (fx - out[i]) * w; out[i + 1] += (fy - out[i + 1]) * w;
    }
    return out;
  }

  /*
   * RUBBER HOSE: a limb with no elbow (an old-cartoon arm, a rope, a hose, a tentacle, an arc of plasma between two
   * sockets). The drawing is one strip from the shoulder pin to the hand pin; it is laid along a curve from the
   * shoulder to wherever the hand is. Closer than its length, it bows (give 0) or shrinks (give 1); further, it stretches.
   */
  function solveHose(l, T, t) {
    var ax = l.a.x, ay = l.a.y, ex = l.c.x - ax, ey = l.c.y - ay, L0 = hyp(ex, ey); if (L0 < 1) return null; ex /= L0; ey /= L0;
    var dx = T.x - ax, dy = T.y - ay, d = hyp(dx, dy); if (d < 0.001) { dx = ex; dy = ey; d = 0.001; }
    var ux = dx / d, uy = dy / d, maxL = L0 * (1 + (l.stretch == null ? 0.12 : l.stretch)); if (d > maxL) d = maxL;
    var give = l.give == null ? 0 : l.give, arc = d >= L0 ? d : d + (L0 - d) * (1 - give);
    var h = d * Math.sqrt(Math.max(0, 0.375 * (arc / d - 1))); h = Math.max(Math.min(h, arc * 0.7), (l.bow || 0) * d);
    /* which way it bows: the side the middle pin was dragged to, else it sags the way it was told (down, for an arm) */
    var nx = -uy, ny = ux, rest = ex * (l.b.y - ay) - ey * (l.b.x - ax), side;
    if (Math.abs(rest) > 0.04 * L0) side = rest > 0 ? 1 : -1; else side = clamp((nx * (l.out ? l.out[0] : 0) + ny * (l.out ? l.out[1] : 1)) * 4, -1, 1);
    if (l.flip) side = -side;
    var Ex = ax + ux * d, Ey = ay + uy * d, Cx = ax + ux * d / 2 + nx * side * 2 * h, Cy = ay + uy * d / 2 + ny * side * 2 * h;
    return { C: [Cx, Cy], T: [Ex, Ey], L0: L0, ex: ex, ey: ey, t: t || 0, mid: [0.25 * ax + 0.5 * Cx + 0.25 * Ex, 0.25 * ay + 0.5 * Cy + 0.25 * Ey], ang: Math.atan2(Ey - Cy, Ex - Cx) };
  }
  function warpHose(src, l, k) {
    var out = new Float32Array(src.length), ax = l.a.x, ay = l.a.y, wv = l.wave || 0, wn = (l.waves == null ? 1.5 : l.waves) * 6.2832, wt = (l.waveRate == null ? 1.3 : l.waveRate) * 6.2832 * k.t;
    for (var i = 0; i < src.length; i += 2) {
      var px = src[i] - ax, py = src[i + 1] - ay, u = (px * k.ex + py * k.ey) / k.L0, w = -px * k.ey + py * k.ex, uc = u < 0 ? 0 : u > 1 ? 1 : u, iu = 1 - uc;
      var bx = iu * iu * ax + 2 * uc * iu * k.C[0] + uc * uc * k.T[0], by = iu * iu * ay + 2 * uc * iu * k.C[1] + uc * uc * k.T[1];
      var tx = iu * (k.C[0] - ax) + uc * (k.T[0] - k.C[0]), ty = iu * (k.C[1] - ay) + uc * (k.T[1] - k.C[1]), tl = hyp(tx, ty) || 1; tx /= tl; ty /= tl;
      var off = w * (l.thick || 1) + (wv ? wv * Math.sin(wn * uc - wt) * Math.sin(3.1416 * uc) : 0), ext = (u - uc) * k.L0;
      out[i] = bx + tx * ext - ty * off; out[i + 1] = by + ty * ext + tx * off;
    }
    return out;
  }
  /** The direction a drawing-space angle points once a matrix has been applied (a mirror turns it over). */
  function wAng(m, a) { var cs = Math.cos(a), sn = Math.sin(a); return Math.atan2(m[1] * cs + m[3] * sn, m[0] * cs + m[2] * sn); }

  /** How strongly a pin holds right now: a pin that belongs to a stance (pin.home = 'pose:<id>') holds as far as that stance is in. */
  function pinHold(p, weightOf) { if (!p.home || p.home.slice(0, 5) !== 'pose:') return 1; var w = Math.abs(weightOf(p.home) || 0); return w > 1 ? 1 : w; }
  AL.pinHold = pinHold;

  /* ── moving-least-squares, rigid (phone: PuppetRigSolver) ── */
  /* K (optional): how strongly each pin holds, 0..1. A pin dropped in a stance belongs to that stance: it comes in with it and has no
     say at all while the character stands (0 = as if it were not there), so shaping a stance cannot disturb the standing shape. */
  function warpMLS(src, P, Q, geo, K) {
    var out = new Float32Array(src.length), n = P.length, live = n;
    if (K) { live = 0; for (var k0 = 0; k0 < n; k0++) if (K[k0] > 1e-4) live++; }
    if (!n || !live) { out.set(src); return out; }
    for (var i = 0; i < src.length; i += 2) {
      var x = src[i], y = src[i + 1], sw = 0, px = 0, py = 0, qx = 0, qy = 0, j, w, W = [], gi = -1;
      if (geo) gi = clamp(Math.floor((y - geo.y) / geo.cell), 0, geo.ch - 1) * geo.cw + clamp(Math.floor((x - geo.x) / geo.cell), 0, geo.cw - 1);
      for (j = 0; j < n; j++) {
        var dx = P[j][0] - x, dy = P[j][1] - y, d2 = dx * dx + dy * dy;
        if (geo) { var gd = geo.f[j][gi] * geo.cell; d2 = Math.max(d2, gd * gd); }          // the long way round, when there is one
        w = 1 / Math.pow(d2 + 1e-3, 1.15); if (K) w *= K[j] > 1e-4 ? K[j] : 0; W.push(w); sw += w; px += w * P[j][0]; py += w * P[j][1]; qx += w * Q[j][0]; qy += w * Q[j][1];
      }
      px /= sw; py /= sw; qx /= sw; qy /= sw;
      var a = 0, b = 0;
      for (j = 0; j < n; j++) { var ax = P[j][0] - px, ay = P[j][1] - py, bx = Q[j][0] - qx, by = Q[j][1] - qy; a += W[j] * (ax * bx + ay * by); b += W[j] * (ax * by - ay * bx); }
      var len = hyp(a, b);
      if (len < 1e-9) { out[i] = x + (qx - px); out[i + 1] = y + (qy - py); continue; }
      var c = a / len, s = b / len, vx = x - px, vy = y - py;
      out[i] = qx + c * vx - s * vy; out[i + 1] = qy + s * vx + c * vy;
    }
    return out;
  }

  /**
   * How far each pin is from every part of a drawing, measured THROUGH the drawing. A hand that hangs
   * beside the hip is far from the hip pin by this measure, so dragging it no longer drags the hip.
   * (Phone: PuppetWeights does the same job on the traced triangle mesh.)
   */
  function geoFields(n, im, alpha) {
    var cell = 4, cw = Math.ceil(im.w / cell), ch = Math.ceil(im.h / cell), solid = new Uint8Array(cw * ch), x, y, i;
    for (y = 0; y < im.h; y += 2) for (x = 0; x < im.w; x += 2) if (alpha[y * im.w + x] > 40) solid[((y / cell) | 0) * cw + ((x / cell) | 0)] = 1;
    var f = n.mesh.pins.map(function (p) {
      var d = new Float32Array(cw * ch).fill(1e9), heap = [], sx = clamp(Math.floor((p.x - n.x) / cell), 0, cw - 1), sy = clamp(Math.floor((p.y - n.y) / cell), 0, ch - 1);
      function push(v, k) { heap.push([v, k]); var c = heap.length - 1; while (c > 0) { var q = (c - 1) >> 1; if (heap[q][0] <= heap[c][0]) break; var t = heap[q]; heap[q] = heap[c]; heap[c] = t; c = q; } }
      function pop() { var top = heap[0], last = heap.pop(); if (heap.length) { heap[0] = last; var c = 0; for (;;) { var l = c * 2 + 1, r = l + 1, m = c; if (l < heap.length && heap[l][0] < heap[m][0]) m = l; if (r < heap.length && heap[r][0] < heap[m][0]) m = r; if (m === c) break; var t = heap[m]; heap[m] = heap[c]; heap[c] = t; c = m; } } return top; }
      d[sy * cw + sx] = 0; push(0, sy * cw + sx);
      while (heap.length) {
        var e = pop(), k = e[1]; if (e[0] > d[k] + 1e-3) continue; var kx = k % cw, ky = (k / cw) | 0;
        for (var oy = -1; oy <= 1; oy++) for (var ox = -1; ox <= 1; ox++) {
          if (!ox && !oy) continue; var nx = kx + ox, ny = ky + oy; if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue;
          var q = ny * cw + nx, nd = e[0] + (ox && oy ? 1.414 : 1) * (solid[q] ? 1 : 6);
          if (nd < d[q] - 1e-3) { d[q] = nd; push(nd, q); }
        }
      }
      return d;
    });
    return { cell: cell, cw: cw, ch: ch, x: n.x, y: n.y, f: f };
  }

  AL.Solver = function (ch) { this.ch = ch; this.st = { pick: {}, fade: {}, swing: {}, flip: 0, frame: 0 }; this.grids = {}; this.geo = {}; };
  /**
   * A limb's mesh cut in two along a line across it (at = 0 shoulder .. 1 hand). Triangles the line passes through are
   * split ON the line, so the two halves share their seam vertices: no overlap, no gap, no stair-step, and both halves
   * bend from the same points. Returns a mesh like grid(): { v, uv, idx, back, front }. Cached on the grid.
   */
  AL.Solver.prototype.splitGrid = function (g, l, at, tipBack) {
    var key = [l.a.x, l.a.y, l.b.x, l.b.y, l.c.x, l.c.y].map(Math.round).join(',') + '|' + at + '|' + tipBack;
    if (g._sk === key) return g._sp;
    var L1 = hyp(l.b.x - l.a.x, l.b.y - l.a.y) || 1, L2 = hyp(l.c.x - l.b.x, l.c.y - l.b.y) || 1, tot = L1 + L2;
    function along(x, y) {
      var u1 = clamp(((x - l.a.x) * (l.b.x - l.a.x) + (y - l.a.y) * (l.b.y - l.a.y)) / (L1 * L1), -0.4, 1), d1 = hyp(x - (l.a.x + u1 * (l.b.x - l.a.x)), y - (l.a.y + u1 * (l.b.y - l.a.y)));
      var u2 = clamp(((x - l.b.x) * (l.c.x - l.b.x) + (y - l.b.y) * (l.c.y - l.b.y)) / (L2 * L2), 0, 1.5), d2 = hyp(x - (l.b.x + u2 * (l.c.x - l.b.x)), y - (l.b.y + u2 * (l.c.y - l.b.y)));
      return d1 <= d2 ? u1 * L1 / tot : (L1 + u2 * L2) / tot;
    }
    var v = Array.prototype.slice.call(g.v), uv = Array.prototype.slice.call(g.uv), n0 = g.v.length / 2, t = [], i, root = [], tip = [], made = {};
    for (i = 0; i < n0; i++) t.push(along(g.v[i * 2], g.v[i * 2 + 1]) - at);
    function mid(p, q) {
      var k = p < q ? p + '_' + q : q + '_' + p; if (made[k] != null) return made[k];
      var f = t[p] / (t[p] - t[q]), id = v.length / 2;
      v.push(v[p * 2] + (v[q * 2] - v[p * 2]) * f, v[p * 2 + 1] + (v[q * 2 + 1] - v[p * 2 + 1]) * f); uv.push(uv[p * 2] + (uv[q * 2] - uv[p * 2]) * f, uv[p * 2 + 1] + (uv[q * 2 + 1] - uv[p * 2 + 1]) * f); t.push(0);
      return (made[k] = id);
    }
    for (i = 0; i < g.idx.length; i += 3) {
      var a = g.idx[i], b = g.idx[i + 1], c = g.idx[i + 2], sa = t[a] >= 0, sb = t[b] >= 0, sc = t[c] >= 0;
      if (sa === sb && sb === sc) { (sa ? tip : root).push(a, b, c); continue; }
      var lone = sa !== sb && sa !== sc ? a : (sb !== sa && sb !== sc ? b : c), o1 = lone === a ? b : a, o2 = lone === c ? b : c, m1 = mid(lone, o1), m2 = mid(lone, o2);
      (t[lone] >= 0 ? tip : root).push(lone, m1, m2); (t[lone] >= 0 ? root : tip).push(o1, o2, m2, o1, m2, m1);
    }
    g._sk = key;
    return (g._sp = { v: new Float32Array(v), uv: new Float32Array(uv), idx: g.idx, nx: g.nx, ny: g.ny, back: new Uint16Array(tipBack ? tip : root), front: new Uint16Array(tipBack ? root : tip) });
  };
  /**
   * A TURNED SHAPE: the outline of a drawing, row by row, read as the radius of a solid at that height (an egg, a pear,
   * a snout, a bottle). Parts painted on it slide round THAT shape as it turns, so they never float off its edge.
   */
  AL.Solver.prototype.lathe = function (b) {
    var n = AL.node(this.ch, b.shell), im = n && n.type === 'art' ? AL.img.get(n.img) : null; if (!im) return null;
    var key = n.img + '|' + im.ver + '|' + n.x + ',' + n.y, cache = this._lathe || (this._lathe = {}); if (cache[key]) return cache[key];
    var al = AL.img.alpha(n.img), cx = new Float32Array(im.h), R = new Float32Array(im.h), Rmax = 1, y, x, l, r, first = -1, last = -1;
    for (y = 0; y < im.h; y++) { l = -1; r = -1; for (x = 0; x < im.w; x++) if (al[y * im.w + x] > 60) { if (l < 0) l = x; r = x; } if (l >= 0) { cx[y] = n.x + (l + r + 1) / 2; R[y] = Math.max(3, (r - l + 1) / 2); if (first < 0) first = y; last = y; if (R[y] > Rmax) Rmax = R[y]; } }
    if (first < 0) return null;
    for (y = 0; y < im.h; y++) { if (y < first) { cx[y] = cx[first]; R[y] = R[first]; } else if (y > last) { cx[y] = cx[last]; R[y] = R[last]; } else if (!R[y]) { cx[y] = cx[y - 1]; R[y] = R[y - 1]; } }
    var R2 = new Float32Array(im.h), c2 = new Float32Array(im.h);                       // a nick in the outline must not kink what slides over it
    for (y = 0; y < im.h; y++) { var s = 0, sc = 0, m = 0; for (var k = -4; k <= 4; k++) { var q = y + k; if (q < 0 || q >= im.h) continue; s += R[q]; sc += cx[q]; m++; } R2[y] = s / m; c2[y] = sc / m; }
    return (cache[key] = { y0: n.y, n: im.h, cx: c2, R: R2, Rmax: Rmax });
  };
  AL.Solver.prototype.geoFor = function (n, im) {
    var key = im.ver + '|' + n.x + ',' + n.y + '|' + n.mesh.pins.map(function (p) { return p.id + ':' + Math.round(p.x) + ',' + Math.round(p.y); }).join(';'), g = this.geo[n.id];
    if (g && g.key === key) return g;
    g = this.geo[n.id] = geoFields(n, im, AL.img.alpha(n.img)); g.key = key; return g;
  };
  /** Where one spot of a part with pins is carried to when its shapes are worn at these strengths ({ shapeId: 0..1 }).
      Used to drop a new pin onto a part that is already reshaped without the part moving under it (al-fit.js, dropPin). */
  AL.Solver.prototype.warpAt = function (n, x, y, W) {
    var pins = (n.mesh && n.mesh.pins) || [], shp = n.shapes || [], P = [], Q = [], K = [], any = false;
    pins.forEach(function (p) { var q = [p.x, p.y]; shp.forEach(function (sh) { var w = W[sh.id] || 0, d = w && sh.d ? sh.d[p.id] : null; if (d) { q[0] += d[0] * w; q[1] += d[1] * w; any = true; } }); P.push([p.x, p.y]); Q.push(q); K.push(pinHold(p, function (id) { return W[id] || 0; })); });
    if (!any) return [x, y];
    var im = n.type === 'art' ? AL.img.get(n.img) : null, o = warpMLS(new Float32Array([x, y]), P, Q, im ? this.geoFor(n, im) : null, K);
    return [o[0], o[1]];
  };
  AL.Solver.prototype.reset = function () { this.st = { pick: {}, fade: {}, swing: {}, flip: this.st.flip, frame: this.st.frame || 0 }; };

  AL.Solver.prototype.grid = function (n, im, dense) {
    var key = n.id + '|' + im.ver + '|' + n.x + ',' + n.y + '|' + (dense || 0), g = this.grids[key];
    if (g) return g;
    var cellPx = dense === 2 ? 9 : 16, cap = dense === 2 ? 46 : 20;
    var nx = dense ? clamp(Math.round(im.w / cellPx), 3, cap) : 1, ny = dense ? clamp(Math.round(im.h / cellPx), 3, cap) : 1;
    var v = new Float32Array((nx + 1) * (ny + 1) * 2), uv = new Float32Array(v.length), idx = new Uint16Array(nx * ny * 6), o = 0, i, j;
    for (j = 0; j <= ny; j++) for (i = 0; i <= nx; i++) { v[o] = n.x + im.w * i / nx; v[o + 1] = n.y + im.h * j / ny; uv[o] = i / nx; uv[o + 1] = j / ny; o += 2; }
    o = 0;
    for (j = 0; j < ny; j++) for (i = 0; i < nx; i++) { var p = j * (nx + 1) + i; idx[o++] = p; idx[o++] = p + 1; idx[o++] = p + nx + 1; idx[o++] = p + 1; idx[o++] = p + nx + 2; idx[o++] = p + nx + 1; }
    g = this.grids[key] = { v: v, uv: uv, idx: idx, nx: nx, ny: ny };
    var keys = Object.keys(this.grids); if (keys.length > 900) this.grids = {};
    return g;
  };

  /**
   * opt.live    true = everything moves (Perform). false = the rest pose for building: no fades, no springs.
   * opt.force   { setId: kidId } show this drawing of a set whatever the signals say.
   * opt.show    { nodeId: true } draw a node that is normally waiting for a blink or a trigger.
   * opt.onion   setId: ghost the set's other drawings.
   */
  AL.Solver.prototype.eval = function (S, opt) {
    opt = opt || {};
    var self = this, ch = this.ch, ix = AL.ix(ch), st = this.st, set = ch.settings || {};
    var live = !!opt.live, dt = clamp(opt.dt || 1 / 60, 0.001, 1 / 20), force = opt.force || {}, show = opt.show || {};
    var out = { items: [], pins: {}, M: {}, picks: {}, first: {}, last: {}, tips: {}, meshAt: {}, rootM: null, flipped: false }, late = [];
    /* every button is also a level that eases 0..1, so any part can follow a button ("nervous", "angry") smoothly */
    var lvl = st.lvl || (st.lvl = {}); (ch.triggers || []).forEach(function (t) { var w = S.trig && S.trig[t.id] ? 1 : 0; lvl[t.id] = live && lvl[t.id] != null ? lvl[t.id] + (w - lvl[t.id]) * Math.min(1, dt * 9) : w; });

    /* whole-character mirror: the art faces one way, the character can face both */
    var F = set.facing === 'left' ? -1 : set.facing === 'right' ? 1 : 0, want = st.flipWant || 0;
    if (live && set.autoFlip && F) { var th = set.flipAt == null ? 0.4 : set.flipAt, x = S.turnX * F; if (x < -th) want = 1; else if (x > -th * 0.45) want = 0; }
    st.flipWant = want;
    var target = (want ? 1 : 0) ^ (S.flip ? 1 : 0);
    /* HOW it turns round. 'smooth' (the usual): the body tucks in toward its middle for a few frames, swaps to its mirror image while
       tucked, and opens out again, so a tail or an arm is pulled in behind the body, changes side out of sight and grows back out.
       A head with drawings for both sides does not tuck at all: it is already turning through its own angles, and the drawing
       it shows just before the swap is the one it shows just after. 'turn': a card swinging round. 'snap': no in-between. */
    var fstyle = set.flipStyle || 'smooth', fdur = fstyle === 'turn' ? 0.16 : (set.flipTime == null ? 0.14 : set.flipTime);
    if (!live || fstyle === 'snap' || fdur <= 0) st.flip = target; else st.flip = st.flip < target ? Math.min(target, st.flip + dt / fdur) : Math.max(target, st.flip - dt / fdur);
    var fx = Math.cos(st.flip * Math.PI), fy = 1, sqz = 1;
    if (fstyle === 'smooth') { var tuck = clamp(set.flipTuck == null ? 0.45 : set.flipTuck, 0, 0.92); sqz = 1 - tuck * (1 - Math.pow(Math.abs(fx), 1.4)); fx = (st.flip > 0.5 ? -1 : 1) * sqz; fy = 1 + (1 - sqz) * 0.08; }
    else if (Math.abs(fx) < 0.03) fx = fx < 0 ? -0.03 : 0.03;
    var axis = set.axisX == null ? ch.w / 2 : set.axisX;
    /* where the character stands on the stage (walking), how big (further away = smaller), then the mirror */
    var rt = S.root || { x: 0, y: 0 }, rs = S.rootScale || 1, gy = (set.walk && set.walk.groundY) || ch.h;
    var TS = M.trs(rt.x, rt.y, 0, rs, rs, axis, gy), rootM = out.rootM = M.mul(TS, M.trs(0, 0, 0, fx, fy, axis, gy)), flipped = out.flipped = st.flip > 0.5;

    /* springs and wind run in the character's own space, so turning the whole character around does not whip the hair */
    var invRoot = M.mul(TS, M.inv(rootM)), moving = live || !!opt.tween, frame = (st.frame = (st.frame || 0) + 1), windIn = (S.wind || 0) * (flipped ? -1 : 1);
    /* the stances that are in play right now, and how far in each is (0..1, eased by whoever drives it; forced while one is being edited) */
    var ZF = {};      /* a limb whose far end comes back in front of what its near end is behind: one shared order per target */
    function zFront(z) { return ZF[z.of] || (ZF[z.of] = { how: 'over', of: z.of }); }
    var PZ = []; (ch.poses || []).forEach(function (p) { var w = opt.poses && opt.poses[p.id] != null ? opt.poses[p.id] : (S.pose ? S.pose[p.id] || 0 : 0); if (w > 0.0005 || w < -0.0005) PZ.push({ p: p, w: w }); });
    out.B = {};
    function windAcc(x) { return windIn * 1500 * (0.6 + 0.4 * Math.sin(S.t * 1.9 + x * 0.013) + 0.25 * Math.sin(S.t * 4.7 + x * 0.031)); }
    function ctx(c, Mx, over) { var k = { M: Mx, a: c.a, mir: c.mir, P: c.P, rootIn: c.rootIn, clip: c.clip, tag: c.tag, vs: c.vs, ghost: c.ghost, warp: c.warp, reach: null, ball: c.ball, wrap: c.wrap, rel: c.rel, under: c.under, phase: c.phase, warps: c.warps, P0: c.P0, nc: c.nc, zo: c.zo }; if (over) for (var e in over) k[e] = over[e]; return k; }
    /** The same context with a few things changed (ctx() is for stepping DOWN to a child; this stays on the same node). */
    /* a stance may put a part in front of, or behind, something else while the character is in it */
    function zOf(n) { for (var i = PZ.length - 1; i >= 0; i--) { var d = PZ[i].p.d && PZ[i].p.d[n.id]; if (d && d.z && PZ[i].w > (d.z.at == null ? 0.5 : d.z.at)) return d.z; } return n.zo || null; }
    function also(c, over) { var k = {}, e; for (e in c) k[e] = c[e]; for (e in over) k[e] = over[e]; return k; }
    /* a round head: where a point painted on the front of the ball is, once the ball has turned. Returns [x, y, depth]. */
    /* how far round it has turned: the head's own turn, plus the whole character being spun round (S.spin, -1..1 = half a turn each way) */
    function ballRot(c) { var b = c.ball.b, s = sg(c), rel = c.rel || [s.turnX, s.turnY], th = (rel[0] * (b.yaw == null ? 50 : b.yaw) + (b.spin ? (s.spin || 0) * 180 : 0)) * DEG, ph = rel[1] * (b.pitch == null ? 30 : b.pitch) * DEG; return [Math.cos(th), Math.sin(th), Math.cos(ph), Math.sin(ph), th, c.ball.pr || null]; }
    function ballPt(b, r, x, y, lift, back) {
      var pr = r[5];
      if (pr) {                                                   // a turned shape: its own outline is the radius at each height
        var i0 = clamp(Math.round(y - pr.y0), 0, pr.n - 1), nxl = (x - pr.cx[i0]) / pr.R[i0], ext = Math.max(0, Math.abs(nxl) - 1), a = Math.asin(clamp(nxl, -1, 1));
        if (back) a = Math.PI - a; a += r[4];
        var zl = Math.cos(a), y2 = y + r[3] * zl * pr.Rmax * 0.5, i1 = clamp(Math.round(y2 - pr.y0), 0, pr.n - 1);
        return [pr.cx[i1] + pr.R[i1] * (1 + ext + (lift || 0)) * Math.sin(a), y2, zl];
      }
      var nx = (x - b.cx) / b.rx, ny = (y - b.cy) / b.ry, r2 = nx * nx + ny * ny, qz = 1 - r2, nz = qz > 0 ? Math.sqrt(qz) * Math.sqrt(Math.sqrt(qz)) : 0, k = 1 + (lift || 0);      // a little flatter than a true ball, so art near the outline does not tear
      if (back) nz = -nz;
      nx *= k; ny *= k; nz *= k;
      var x1 = nx * r[0] + nz * r[1], z1 = -nx * r[1] + nz * r[0], y1 = ny * r[2] + z1 * r[3];
      return [b.cx + x1 * b.rx, b.cy + y1 * b.ry, -ny * r[3] + z1 * r[2]];
    }
    /* something soft: it overshoots and settles instead of following exactly */
    function spring(key, v) { var bk = st.bnc || (st.bnc = {}), be = bk[key] || (bk[key] = { x: v, v: 0, f: frame }); if (frame - be.f > 3) { be.x = v; be.v = 0; } if (be.f !== frame) { var bdt = Math.min(dt, 1 / 30); be.v += ((v - be.x) * 260 - be.v * 13) * bdt; be.x += be.v * bdt; be.f = frame; } return be.x; }
    function sigVal(n, sig, s) { return sig === 'always' ? 1 : sig === 'blink' ? (n.side === 'R' ? s.blinkR : s.blinkL) : sig && sig.slice(0, 5) === 'trig:' ? (lvl[sig.slice(5)] || 0) : s[sig]; }
    /*
     * SHAPES: a part with pins can be posed into other shapes (an eye squeezed shut, a body squashed), each one a set of
     * pin offsets. A shape is blended in by whatever drives it (a signal, a button), so shapes melt into each other.
     * Returns where each pin is now. On a folder or a set the same pins bend every drawing inside it.
     */
    function pinTargets(n, s, c) {
      var P = [], Q = [], pins = n.mesh.pins, shp = n.shapes || [], forced = opt.shapes && opt.shapes[n.id], W = [], i, j, moved = false;
      for (j = 0; j < shp.length; j++) { var sh = shp[j], w = 0; if (forced) w = forced[sh.id] || 0; else { var sv = sigVal(n, sh.sig, s); if (typeof sv === 'number') { w = clamp(sv * (sh.amt == null ? 1 : sh.amt), -1.5, 1.5); if (sh.bounce && moving) w = spring(n.id + ':s:' + sh.id + (c.tag || ''), w); } } W.push(w); }
      var KK = [], wOf = function (id) { for (var k = 0; k < shp.length; k++) if (shp[k].id === id) return W[k]; return 0; };
      for (i = 0; i < pins.length; i++) {
        var p = pins[i], q = [p.x, p.y], hp = p.role === 'handL' ? s.hand.L : p.role === 'handR' ? s.hand.R : null;
        for (j = 0; j < shp.length; j++) { var d = W[j] && shp[j].d ? shp[j].d[p.id] : null; if (d) { q[0] += d[0] * W[j]; q[1] += d[1] * W[j]; moved = true; } }
        if (s.pin[p.id]) { q = [s.pin[p.id].x, s.pin[p.id].y]; moved = true; }
        else if (hp) { q = [hp.x, hp.y]; moved = true; }
        else if (p.role === 'head') { q = [q[0] + s.turnX * (n.mesh.headMove == null ? 10 : n.mesh.headMove), q[1] + s.turnY * 7 - s.breath * 1.5]; moved = true; }
        P.push([p.x, p.y]); Q.push(q); KK.push(p.home ? pinHold(p, wOf) : 1);
      }
      return { P: P, Q: Q, K: KK, moved: moved };
    }
    function centreOf(n) { if (n.pivot) return n.pivot; var b = AL.boundsOf(n); return b ? { x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 } : { x: 0, y: 0 }; }
    if (this._mkIx !== ix) { this._mkIx = ix; var mk = this.maskIds = {}; AL.walk(ch.root, function (n) { if (n.clips) n.clips.forEach(function (q) { mk[q.by] = 1; }); }); }
    var maskIds = this.maskIds, masks = {};

    /* signals as the art sees them: inside a mirror, left is right */
    function sigs(mir) {
      if (!mir) return S;
      return { turnX: -S.turnX, turnY: S.turnY, roll: -S.roll, lookX: -S.lookX, lookY: S.lookY, blinkL: S.blinkR, blinkR: S.blinkL, browL: S.browR, browR: S.browL,
        mouth: S.mouth, viseme: S.viseme, lidUp: S.lidUp, lidLow: S.lidLow, lidTilt: S.lidTilt, wind: -S.wind, breath: S.breath, sway: -S.sway, bounce: S.bounce, hand: S.hand, foot: S.foot, spin: -(S.spin || 0), walk: -(S.walk || 0), armSide: S.armSide, trig: S.trig, pin: S.pin, t: S.t };
    }
    function sigsFull(mir) { var o = sigs(mir); if (mir) { for (var k in S) if (k.slice(0, 3) === 'pt.' || k.slice(0, 5) === 'face.') o[k] = S[k]; o.shrugL = S.shrugR; o.shrugR = S.shrugL; o.shrug = S.shrug; } return o; }   // FACE-1 face pins ride a mirror as they are, like S.pin; SHRUG-1 shrugs swap sides
    var SM = [S, sigsFull(true)];
    function sg(c) { return SM[(c.mir ? 1 : 0) ^ (flipped ? 1 : 0)]; }

    function pivotOf(n) {
      if (n.pivot) return n.pivot;
      if (n.type === 'art') { var im = AL.img.get(n.img); if (im) return { x: n.x + im.w / 2, y: n.y + im.h / 2 }; }
      return { x: 0, y: 0 };
    }
    function local(n, c) {
      var s = sg(c), tx = 0, ty = 0, rot = 0, sx = 1, sy = 1, any = false, i;
      if (n.links) for (i = 0; i < n.links.length; i++) {
        var l = n.links[i], v = sigVal(n, l.sig, s); if (v == null || typeof v !== 'number') continue; any = true; v += l.bias || 0;
        if (l.bounce && moving) v = spring(n.id + ':' + i + (c.tag || ''), v);
        if (l.osc) v *= moving ? Math.sin(S.t * 6.2832 * l.osc + i * 1.3) : 0;             // back and forth: a hover, a bob, a nod in time
        if (l.prop === 'shake') { tx += Math.sin(S.t * 67 + i * 2.1) * v * l.amt; ty += Math.cos(S.t * 83 + i) * v * l.amt * 0.7; continue; }
        if (l.prop === 'spin') { var sq = st.spin || (st.spin = {}), se = sq[n.id + ':' + i] || (sq[n.id + ':' + i] = { a: 0, f: -1 }); if (se.f !== frame) { se.f = frame; if (moving) se.a += v * l.amt * 9 * DEG * dt; } rot += se.a; continue; }
        if (l.prop === 'x') tx += v * l.amt; else if (l.prop === 'y') ty += v * l.amt; else if (l.prop === 'rot') rot += v * l.amt * DEG;
        else if (l.prop === 'sx') sx *= 1 + v * l.amt; else if (l.prop === 'sy') sy *= 1 + v * l.amt; else if (l.prop === 's') { sx *= 1 + v * l.amt; sy *= 1 + v * l.amt; }
      }
      if (n.sc != null && n.sc !== 1) { sx *= n.sc; sy *= n.sc; any = true; }
      if (n.rot) { rot += n.rot * DEG; any = true; }
      if (n.dx || n.dy) { tx += n.dx || 0; ty += n.dy || 0; any = true; }                 // nudged into place by hand (the transform box)
      /* STANCES: a whole-body pose (all fours, sitting, a crouch) is a handful of parts each moved, turned or resized by a set amount.
         It is blended in by its level, and a part that turns about a point of its own choosing (a torso about its hips) travels
         an ARC as it goes, so the neck and shoulders swing round instead of sliding in a straight line. */
      for (i = 0; i < PZ.length; i++) {
        var pz = PZ[i], pd = pz.p.d && pz.p.d[n.id]; if (!pd) continue;
        var pw = pz.w, pr = (pd.rot || 0) * DEG * pw;
        tx += (pd.x || 0) * pw; ty += (pd.y || 0) * pw; rot += pr;
        if (pd.sc != null && pd.sc !== 1) { var psc = 1 + (pd.sc - 1) * pw; sx *= psc; sy *= psc; }
        if (pd.orbit && pd.orbit.deg) { var po = pd.orbit.deg * DEG * pw, pv1 = pivotOf(n), vx1 = pv1.x - pd.orbit.x, vy1 = pv1.y - pd.orbit.y, cp = Math.cos(po), sp1 = Math.sin(po); tx += cp * vx1 - sp1 * vy1 - vx1; ty += sp1 * vx1 + cp * vy1 - vy1; }
        any = true;
      }
      if (n.flip) { sx = -sx; any = true; }
      if (moving && n.depth && c.P && !(c.ball && c.wrap)) { tx += n.depth * c.P[0]; ty += n.depth * c.P[1]; any = true; }
      if (n.look) { tx += s.lookX * n.look.rx; ty += s.lookY * n.look.ry; any = true; }
      if (s.mouth != null) {
        if (n.hinge) { rot += (n.hinge.rest === 'closed' ? s.mouth : 1 - s.mouth) * n.hinge.angle * DEG; any = true; }
        if (n.squash) { var open = n.squash.rest === 'closed' ? 1 - s.mouth : s.mouth; sy *= n.squash.min + (1 - n.squash.min) * open; any = true; }
      }
      if (n.lid) {
        var shut = set.blinkLids ? (n.side === 'R' ? s.blinkR : s.blinkL) : 0;
        if (n.lid.kind === 'lower') { ty -= Math.max(s.lidLow || 0, shut * 0.35) * n.lid.travel; rot -= (s.lidTilt || 0) * n.lid.tilt * 0.25 * DEG * n.lid.sign; }
        else { ty += Math.max(s.lidUp || 0, shut) * n.lid.travel; rot += (s.lidTilt || 0) * n.lid.tilt * DEG * n.lid.sign; }
        any = true;
      }
      if (live && n.swing && n.swing.kind === 'stiff' && n.swing.on !== false) { var q = st.swing[n.id]; if (q && q.f === frame) { rot += q.out * q.sgn; any = true; } }
      if (!any) return null;
      var p = pivotOf(n);
      if (moving && c.ball && c.wrap === 'on') { var bq = ballPt(c.ball.b, ballRot(c), p.x, p.y, 0, n.back); p = { x: bq[0], y: bq[1] }; }      // a hinge painted on a round head turns with it
      return M.trs(tx, ty, rot, sx, sy, p.x, p.y);
    }
    var wm = {};
    function worldOf(id) {
      if (wm[id]) return wm[id];
      var n = ix.n[id]; if (!n) return M.id();
      var par = (n.attach && ix.n[n.attach]) ? n.attach : (ix.p[id] ? ix.p[id].id : null);
      wm[id] = M.id();                               // guards a cycle
      var base = par ? worldOf(par) : M.id();
      if (live && n.swing && n.swing.kind === 'stiff' && n.swing.on !== false) stepStiff(n, base);
      var L = local(n, { mir: false, P: null });
      return (wm[id] = L ? M.mul(base, L) : base);
    }

    function gated(n, c) {
      if (show[n.id]) return false;
      var s = sg(c);
      if (n.blink) { var b = set.blinkLids ? 0 : (n.side === 'R' ? s.blinkR : s.blinkL); if (n.blink === 'open' ? b > 0.5 : b <= 0.5) return true; }
      if (n.trig && !c.picked && !s.trig[n.trig]) return true;
      if (n.hideOn) for (var i = 0; i < n.hideOn.length; i++) if (s.trig[n.hideOn[i]]) return true;
      return false;
    }

    function draw(n, Mx, c) {
      if (n.type === 'art') emit(n, Mx, c);
      else if (n.type === 'set') walkSet(n, Mx, c);
      else if (n.kids) for (var i = n.kids.length - 1; i >= 0; i--) walk(n.kids[i], ctx(c, Mx));
    }
    /*
     * RIDES: a hand on the end of an arm, a lantern on a tail, a sword in a fist. The part is carried by the end of
     * a limb (or a swinging part) and turns with it. With no pin of its own it stays exactly where it was placed
     * against the resting hand. A set "by direction" also swaps to the drawing made for the way the limb points.
     */
    function ride(n, c, tp) {
      var rd = n.ride, turn = rd.turn == null ? 1 : rd.turn, base = tp.rest, pv = n.pivot, kid = null;
      if (n.type === 'set' && n.pick && n.pick.by === 'dir') {
        var prevId = st.pick[n.id], bd = 1e9, pd = 1e9, prev = null;
        (n.kids || []).forEach(function (k) { var sel = k.when != null ? k.when : k.dir; if (sel == null || k.trig) return; var d = Math.abs(wrapA(tp.ang - wAng(c.M, sel * DEG))); if (d < bd) { bd = d; kid = k; } if (k.id === prevId) { pd = d; prev = k; } });
        if (live && prev && pd - bd < 0.14) kid = prev;
        if (kid) { base = wAng(c.M, (kid.dir != null ? kid.dir : n.dir != null ? n.dir : kid.when) * DEG); if (kid.pivot) pv = kid.pivot; }
      }
      if (!kid && n.dir != null) base = wAng(c.M, n.dir * DEG);
      var pw = pv ? M.ap(c.M, pv.x, pv.y) : tp.restP, th = wrapA(tp.ang - base) * turn;
      if (rd.loose && live) {
        var rk = st.ride || (st.ride = {}), re = rk[n.id] || (rk[n.id] = { a: th, v: 0, f: frame });
        if (frame - re.f > 3) { re.a = th; re.v = 0; }
        if (re.f !== frame) { var rdt = Math.min(dt, 1 / 30), rkk = 420 - rd.loose * 330, rcc = 2 * Math.sqrt(rkk) * (0.62 - rd.loose * 0.34); re.v += (wrapA(th - re.a) * rkk - re.v * rcc) * rdt; re.a += re.v * rdt; re.f = frame; }
        th = re.a;
      }
      return also(c, { M: M.mul(M.mul(M.trs(tp.p[0] - pw[0], tp.p[1] - pw[1], 0, 1, 1, 0, 0), M.trs(0, 0, th, 1, 1, pw[0], pw[1])), c.M), dirKid: kid, late: false, under: rd.front ? c.under : (tp.under || c.under), zo: tp.zo || c.zo });
    }
    /*
     * STREAM: one drawing (or loop) sent out as several copies, each at a different moment of the same short life:
     * hover rings, bubbles, sparks, steam, rain, footstep dust. Each copy drifts, grows or shrinks, and fades.
     */
    function stream(n, Mx, c) {
      var em = n.emit, cnt = clamp(Math.round(em.n || 4), 1, 24), life = Math.max(0.15, em.life || 1.4), pv = centreOf(n), s0 = em.s0 == null ? 1 : em.s0, s1 = em.s1 == null ? 1 : em.s1;
      var tt = Math.max(0, sg(c).t) / life, list = [], fo = Math.max(0.02, em.fade == null ? 0.35 : em.fade), e;
      for (e = 0; e < cnt; e++) list.push(((tt + e / cnt) % 1 + 1) % 1);
      list.sort(function (a, b) { return b - a; });                                    // the oldest first, the newest on top
      for (e = 0; e < cnt; e++) {
        var ph = list[e], sc = s0 + (s1 - s0) * ph, env = Math.min(1, ph / 0.1, (1 - ph) / fo), wob = (em.sway || 0) * Math.sin(ph * 9.4 + e * 2.4);
        draw(n, M.mul(Mx, M.trs((em.dx || 0) * ph + wob, (em.dy || 0) * ph, (em.spin || 0) * ph * DEG, sc, sc, pv.x, pv.y)), also(c, { a: c.a * env, phase: ph }));
      }
    }
    function walk(n, c) {
      if (n.hidden && !c.picked && !show[n.id]) return;
      if (gated(n, c)) return;
      if (n.ride && ix.n[n.ride.of] && !c.ghost) {
        var tp = out.tips[n.ride.of];
        if (!tp && !c.late) { late.push({ n: n, c: c, at: out.items.length }); return; }      // its limb has not been posed yet (the limb is drawn above it): come back to it
        if (tp) c = ride(n, c, tp);
      }
      var Mx = c.M;
      if (n.noTone && !c.nc) c = also(c, { nc: true });                 // this part keeps its own colours when a colour set comes on
      if (n.attach && ix.n[n.attach]) Mx = M.mul(c.rootIn, worldOf(n.attach));
      if (live && n.swing && n.swing.kind === 'stiff' && n.swing.on !== false) stepStiff(n, M.mul(invRoot, Mx));
      /* a PIECE of a turning head with landmarks of its own (or sharing another piece's) warps by those, not by the whole head's */
      if (c.warps && n.name) { var wk = c.warps[String(n.name).toLowerCase().trim()]; if (wk !== undefined) c = also(c, { warp: wk, warps: null, P: wk && wk.par ? wk.par : (c.P0 || c.P) }); }
      /* pins on a folder or a set bend every drawing inside it (an eye made of parts, squeezed shut as one) */
      if (n.type !== 'art' && n.mesh && n.mesh.pins && n.mesh.pins.length) { var gp = pinTargets(n, sg(c), c); if (gp.moved) c = also(c, { warp: c.warp ? { P: c.warp.P.concat(gp.P), Q: c.warp.Q.concat(gp.Q), K: (c.warp.K || c.warp.P.map(function () { return 1; })).concat(gp.K) } : gp }); }
      /* ROUND SHAPE: this node is a ball (or a turned outline); parts inside marked "painted on" slide over it, parts marked "sticks out" ride its surface */
      if (n.ball && n.ball.on !== false) c = also(c, { ball: { b: n.ball, id: n.ball.shell || n.id, pr: n.ball.kind === 'lathe' ? self.lathe(n.ball) : null } });
      var out2 = null, an = null, dec = null, Mx0 = Mx;
      if (n.wrap && n.wrap !== 'out' && n.type !== 'art' && c.ball && moving) {
        /* a painted-on part made of parts (an eye with its lids, a mouth with its shapes) rides the surface in ONE piece: moved to where
           its middle now is. "Keeps its shape" 0 = squashed and sheared exactly as the surface is turned there (a sticker); more = it only
           narrows a little and stays upright, the way a cartoon eye does. Its lids and masks keep working inside it. */
        var da = centreOf(n), br0 = ballRot(c), b0 = c.ball.b, l0 = n.lift || 0, f0 = ballPt(b0, br0, da.x, da.y, l0, n.back), f1 = ballPt(b0, br0, da.x + 1, da.y, l0, n.back), f2 = ballPt(b0, br0, da.x, da.y + 1, l0, n.back);
        var kp = n.keep == null ? 0.6 : n.keep, zmin = kp > 0 ? -0.04 : 0.12;      // a sticker is gone before it is a razor-thin sliver; a part that keeps its shape slides to the very edge (mask it inside the head and the edge cuts it)
        if (f0[2] < zmin) return;
        var j00 = f1[0] - f0[0], j10 = f1[1] - f0[1], j01 = f2[0] - f0[0], j11 = f2[1] - f0[1];
        if (kp > 0) {
          var kx = hyp(j00, j10), ky = hyp(j01, j11); j00 = kx + (1 - kx) * kp; j11 = ky + (1 - ky) * kp; j10 = j01 = 0;
          /* on a turned outline it also narrows just enough to stay on the shape as it nears the edge (never thinner than about half),
             so the far eye of a turning head tucks against the outline instead of being sliced by it */
          var prr = br0[5], bn = prr ? AL.boundsOf(n) : null;
          if (bn) { var ir = clamp(Math.round(f0[1] - prr.y0), 0, prr.n - 1), hw = (bn.x1 - bn.x0) / 2, room = prr.R[ir] - Math.abs(f0[0] - prr.cx[ir]); if (hw > 1 && j00 * hw > room + hw * 0.18) j00 = Math.max(0.46, (room + hw * 0.18) / hw); }
        }
        dec = [j00, j10, j01, j11, f0[0] - j00 * da.x - j01 * da.y, f0[1] - j10 * da.x - j11 * da.y];
        c = also(c, { wrap: null, a: c.a * smooth((f0[2] - zmin) / 0.14) });
      } else if (n.wrap && c.ball && moving) { if (n.wrap === 'out') { an = centreOf(n); out2 = ballPt(c.ball.b, ballRot(c), an.x, an.y, n.lift || 0, n.back); out2[0] -= an.x; out2[1] -= an.y; c = also(c, { wrap: null, under: out2[2] < -0.03 ? c.ball.id : c.under }); } else c = also(c, { wrap: 'on', back: !!n.back }); }
      if (dec) Mx = M.mul(Mx, dec);
      if (out2) Mx0 = Mx = M.mul(Mx, M.trs(out2[0], out2[1], 0, n.flat ? clamp(Math.abs(out2[2]) / (1 + (n.lift || 0)), 0.2, 1) : 1, 1, an.x, an.y));
      out.B[n.id] = Mx;                              // where this part sits before its own move, turn and size (the transform box works in this space)
      var L = local(n, c); if (L) Mx = M.mul(Mx, L);
      out.M[n.id] = Mx;
      var zo1 = (PZ.length || n.zo) ? zOf(n) : null; if (zo1) c = also(c, { zo: zo1 });
      /* the tip of something that swings stiffly: a thing can ride on it (a lantern on a pole, a ring stream on a hover peg) */
      if (n.swing && n.swing.kind === 'stiff' && n.swing.tip && !c.ghost) { var pv0 = pivotOf(n), ra = Math.atan2(n.swing.tip.y - pv0.y, n.swing.tip.x - pv0.x); out.tips[n.id] = { p: M.ap(Mx, n.swing.tip.x, n.swing.tip.y), ang: wAng(Mx, ra), restP: M.ap(Mx0, n.swing.tip.x, n.swing.tip.y), rest: wAng(Mx0, ra), under: c.under || null }; }
      if (n.clips && n.clips.length) c = also(c, { clip: (c.clip || []).concat(n.clips) });
      var mark = out.items.length;
      if (n.emit && n.emit.on !== false && moving && !c.ghost) stream(n, Mx, c); else draw(n, Mx, c);
      if (out.items.length > mark) { out.first[n.id] = out.items[mark]; out.last[n.id] = out.items[out.items.length - 1]; }
      /* a part that masks others: remember what it drew; a mask-only part is then taken back off the stage */
      if (maskIds[n.id]) masks[n.id + (c.tag || '')] = out.items.slice(mark);
      if (n.maskOnly && !show[n.id]) out.items.length = mark;
    }

    function kidCtx(c, Mx, a, extra) { var k = ctx(c, Mx, { a: a, picked: true }); if (extra) for (var e in extra) k[e] = extra[e]; return k; }

    function walkSet(n, Mx, c) {
      var s = sg(c), pk = n.pick || {}, kids = n.kids || [], cur = null, i, k;
      if (!kids.length) return;
      var forced = force[n.id] && ix.n[force[n.id]] && ix.p[force[n.id]] === n ? ix.n[force[n.id]] : null;
      var trigKid = null;
      for (i = 0; i < kids.length; i++) if (kids[i].trig && s.trig[kids[i].trig]) { trigKid = kids[i]; break; }
      var prevId = st.pick[n.id], prev = prevId && ix.p[prevId] === n ? ix.n[prevId] : null, T = null;

      if (pk.by === 'turn') {
        var dl = pk.dial && S.dial ? S.dial[pk.dial] : null, tx = dl ? dl[0] * ((((c.mir ? 1 : 0) ^ (flipped ? 1 : 0))) ? -1 : 1) : s.turnX, ty = dl ? dl[1] : s.turnY, best = null, bd = 1e9, pd = 1e9;
        if (pk.gain != null && pk.gain !== 1) tx = clamp(tx * pk.gain, -1, 1);          // a body that follows the head's turn only part of the way
        for (i = 0; i < kids.length; i++) { k = kids[i]; if (!k.at) continue; var d = hyp(k.at[0] - tx, k.at[1] - ty); if (d < bd) { bd = d; best = k; } if (k === prev) pd = d; }
        cur = (live && prev && prev.at && pd - bd < 0.07) ? prev : best;
        /* turning right round: something with drawings for both sides keeps its full width while the body tucks */
        if (sqz < 0.999 && !pk.dial && kids.some(function (q) { return q.at && q.at[0] < -0.1; }) && kids.some(function (q) { return q.at && q.at[0] > 0.1; })) {
          var hb = n.pivot ? null : AL.boundsOf(n), hp = n.pivot || (hb ? { x: (hb.x0 + hb.x1) / 2, y: hb.y1 } : { x: axis, y: 0 });
          Mx = M.mul(Mx, M.trs(0, 0, 0, 1 / sqz, 1 / fy, hp.x, hp.y));
        }
        /* ANGLES THAT ARE TURNED OR RESIZED (a copy of the level head, tipped up): between the dots the tip and the size blend, so two
           copies of one drawing turn into each other with no jump. Each dot's pull falls off fast with distance, and is total on the dot. */
        var effOf = function (q) { var qs = q.type === 'mirror' ? ix.n[q.of] : null; return qs ? { rot: (q.rot || 0) - (qs.rot || 0), sc: (q.sc == null ? 1 : q.sc) * (qs.sc == null ? 1 : qs.sc), x: (q.dx || 0) - (qs.dx || 0), y: (q.dy || 0) + (qs.dy || 0) } : { rot: q.rot || 0, sc: q.sc == null ? 1 : q.sc, x: q.dx || 0, y: q.dy || 0 }; };
        var mix = null;
        if (moving && kids.some(function (q) { return q.at && (q.rot || q.dx || q.dy || (q.sc != null && q.sc !== 1)); })) {
          var gw = 0, gr = 0, gs = 0, gx = 0, gyy = 0;
          kids.forEach(function (q) { if (!q.at) return; var ef = effOf(q), dq = hyp(q.at[0] - tx, q.at[1] - ty), g = 1 / (dq * dq * dq + 1e-6); gw += g; gr += g * ef.rot; gs += g * Math.log(Math.max(0.01, ef.sc)); gx += g * ef.x; gyy += g * ef.y; });
          if (gw) mix = { rot: gr / gw, sc: Math.exp(gs / gw), x: gx / gw, y: gyy / gw };
        }
      } else if (pk.by === 'reach') {
        var hand = s.hand[pk.hand], foot = !!pk.hand && pk.hand.charAt(0) === 'F', restKid = ix.n[pk.rest] || null;
        if (!restKid || !restKid.limb) for (i = 0; i < kids.length; i++) if (kids[i].limb) { restKid = kids[i]; break; }
        var rAt = pk.restAt && moving ? pk.restAt : (restKid ? restKid.limb.c : null);
        var Tw = hand ? ((foot || hand.w) ? [hand.x, hand.y] : M.ap(c.rootIn, hand.x, hand.y)) : (rAt ? M.ap(Mx, rAt.x, rAt.y) : null);
        if (hand && hand.k != null && hand.k < 1 && Tw && rAt) { var fr0 = M.ap(Mx, rAt.x, rAt.y); Tw = [fr0[0] + (Tw[0] - fr0[0]) * hand.k, fr0[1] + (Tw[1] - fr0[1]) * hand.k]; }      // part-way to where it is wanted (a stance being eased in by its slider)
        if (!hand && !foot && restKid && live && s.walk) {             // nobody is holding this hand: it swings with the walk
          var sa = (set.walk && set.walk.armSwing != null ? set.walk.armSwing : 18) * DEG * s.walk * (s.armSide && s.armSide[pk.hand] != null ? s.armSide[pk.hand] : (pk.hand === 'L' ? 1 : -1)), la = restKid.limb.a, lc = rAt || restKid.limb.c, cs = Math.cos(sa), sn = Math.sin(sa);
          Tw = M.ap(Mx, la.x + cs * (lc.x - la.x) - sn * (lc.y - la.y), la.y + sn * (lc.x - la.x) + cs * (lc.y - la.y));
        }
        if (Tw) { var iv = M.inv(Mx), tl = M.ap(iv, Tw[0], Tw[1]); T = { x: tl[0], y: tl[1] }; }
        /* the walker asks where each limb grows from right now (a shoulder carried down by a stance comes within reach of the ground) */
        if (pk.hand && restKid && !c.ghost) (S.anchor || (S.anchor = {}))[pk.hand] = M.ap(Mx, restKid.limb.a.x, restKid.limb.a.y);
        /* what drives this hand has changed (a finger took it, it was let go, it planted on the ground as a foot, it lifted off again):
           it travels from where it was to where it is now wanted over a few frames, instead of jumping */
        if (live && T && !c.ghost) {
          var rb = st.rb || (st.rb = {}), rq = rb[n.id + (c.tag || '')] || (rb[n.id + (c.tag || '')] = { m: -1, t: 0, x: T.x, y: T.y, f: frame }), md = hand ? ((hand.w || foot) ? 2 : 1) : 0;
          if (rq.m !== md && rq.m >= 0 && frame - rq.f <= 2 && !foot) { rq.t = 1; rq.fx = rq.x; rq.fy = rq.y; }
          rq.m = md;
          if (rq.t > 0) { rq.t -= dt / 0.3; var rw = smooth(clamp(1 - rq.t, 0, 1)); T = { x: rq.fx + (T.x - rq.fx) * rw, y: rq.fy + (T.y - rq.fy) * rw }; }
          rq.x = T.x; rq.y = T.y; rq.f = frame;
        }
        if (T) {
          var bk = null, bdist = 1e9, pdist = 1e9;
          for (i = 0; i < kids.length; i++) { k = kids[i]; if (!k.limb) continue; var dd = hyp(k.limb.c.x - T.x, k.limb.c.y - T.y); if (dd < bdist) { bdist = dd; bk = k; } if (k === prev) pdist = dd; }
          cur = (live && prev && prev.limb && pk.swap !== false && pdist < bdist * 1.18 + 3) ? prev : bk;
          if (pk.swap === false && restKid) cur = restKid;
        } else cur = restKid || kids[0];
        /* a hand that is walking uses ONE drawing, the one chosen for it (the resting arm unless told otherwise): an arm fan drawn for
           reaching and waving would otherwise show its raised, palm-out drawings under a body that has tipped onto all fours */
        if (hand && hand.w && !foot && T && !(pk.walk && pk.walk.any)) { var wkid = pk.walk && pk.walk.kid ? ix.n[pk.walk.kid] : null; if (!wkid || ix.p[wkid.id] !== n || !wkid.limb) wkid = restKid; if (wkid) cur = wkid; }
        if (pk.loop && moving && T) { var lf = kids.filter(function (q) { return q.limb && !q.trig; }); if (lf.length) cur = lf[Math.floor(Math.max(0, s.t) * pk.loop) % lf.length]; }      // its drawings are frames, not angles
      } else if (pk.by === 'cycle') {
        var fr = kids.filter(function (q) { return !q.trig; });
        if (fr.length && c.phase != null && pk.life) cur = fr[Math.min(fr.length - 1, Math.floor(c.phase * fr.length))];           // inside a stream: one pass through the frames in each copy's life
        else if (fr.length && moving) cur = fr[Math.floor(Math.max(0, s.t) * (pk.fps || 12) + (c.phase || 0) * fr.length) % fr.length];
      } else if (pk.by === 'dir') {
        cur = c.dirKid || null;
      } else if (pk.by === 'viseme') {
        if (s.mouth != null) {
          var want = [s.viseme].concat(VFALL[s.viseme] || [], s.mouth > 0.25 ? ['Aa', 'Oh', 'Ee'] : [], ['Neutral', 'M']);
          for (var wi = 0; wi < want.length && !cur; wi++) for (i = 0; i < kids.length; i++) if (kids[i].shape === want[wi]) { cur = kids[i]; break; }
        }
      }
      if (!cur) cur = ix.n[pk.rest] && ix.p[pk.rest] === n ? ix.n[pk.rest] : null;
      if (!cur) for (i = 0; i < kids.length; i++) if (!kids[i].trig) { cur = kids[i]; break; }
      if (trigKid) cur = trigKid;
      if (forced) cur = forced;
      if (!cur) cur = kids[0];
      if (forced && forced.limb && !live) T = { x: forced.limb.c.x, y: forced.limb.c.y };     // building: the chosen drawing as it was drawn, not reaching anywhere
      var mo = opt.morph && opt.morph.set === n.id && ix.n[opt.morph.a] && ix.n[opt.morph.b] ? opt.morph : null; if (mo) cur = ix.n[mo.b];

      /* a swap fades in over the old drawing, both solved to the same pose, so it reads as a turn and not a pop.
         Two LOOKS that have been matched with landmarks (a smile and a frown, an open eye and a shut one) do more: each bends
         into the other's shape while they cross-fade, so the change is one shape melting into the next. */
      var fade = st.fade[n.id], looks = pk.by !== 'turn' && pk.by !== 'reach' && pk.by !== 'cycle' && n.bridges && n.bridges.length, fms = pk.fade == null ? (looks ? 170 : 110) : pk.fade;
      if (live && prev && prev !== cur && fms > 0 && (looks ? !!AL.bridgeFor(ch, n, prev, cur, '', true) : ((pk.by === 'turn' || pk.by === 'reach') && !pk.loop))) fade = st.fade[n.id] = { from: prev.id, t: 0, morph: !!looks };
      st.pick[n.id] = cur.id; out.picks[n.id] = cur.id;
      if (fade) { fade.t += dt * 1000 / Math.max(1, fms); if (fade.t >= 1 || !live || fade.from === cur.id || !ix.n[fade.from]) { delete st.fade[n.id]; fade = null; } }
      if (mo) fade = { from: mo.a, t: mo.t, morph: true };

      function one(kid, a, ghost) {
        var extra = { ghost: ghost || c.ghost };
        if (pk.by === 'reach') { extra.reach = (ghost && kid.limb) ? { x: kid.limb.c.x, y: kid.limb.c.y } : T; extra.lift = !live && forced === kid && opt.lift !== false; extra.limbSet = n.id; extra.handKey = pk.hand || null; extra.restLimb = restKid ? restKid.limb : null; extra.foot = s.foot && pk.hand ? s.foot[pk.hand] : 0; }
        if (fade && fade.morph && !ghost) {
          var oth = kid === cur ? ix.n[fade.from] : cur, me = smooth(fade.t), mw = kid === cur ? 1 - me : me, mq = oth && oth !== kid ? AL.bridgeFor(ch, n, kid, oth, '', true) : null;
          if (mq) extra.warp = { P: mq.map(function (q) { return q.from; }), Q: mq.map(function (q) { return [q.from[0] + (q.to[0] - q.from[0]) * mw, q.from[1] + (q.to[1] - q.from[1]) * mw]; }) };
        }
        if (pk.by === 'turn') extra.vs = n.id;
        if (pk.by === 'turn' && kid.at) { var dpx = pk.depth == null ? 14 : pk.depth; extra.P = [(tx - kid.at[0]) * dpx, (ty - kid.at[1]) * dpx]; extra.rel = [tx - kid.at[0], ty - kid.at[1]]; }
        var m2 = Mx, src = kid, ax = n.axisX;
        if (pk.by === 'turn' && moving && !ghost && kid.at && n.bridges && n.bridges.length) {
          /* warp this angle toward each neighbour it is turning to */
          var warpOf = function (part) {
            var BP = [], BQ = [], rx = tx - kid.at[0], ry = ty - kid.at[1], toward = [];
            kids.forEach(function (o) {
              if (o === kid || !o.at) return;
              var br = AL.bridgeFor(ch, n, kid, o, part); if (!br) return;
              var ddx = o.at[0] - kid.at[0], ddy = o.at[1] - kid.at[1], d2 = ddx * ddx + ddy * ddy || 1, tt = ((tx - kid.at[0]) * ddx + (ty - kid.at[1]) * ddy) / d2, w = smooth(clamp(tt / 0.5, 0, 1));
              if (tt > 0) toward.push([tt, ddx, ddy, d2]);
              if (w < 0.002) return;
              br.forEach(function (q) { var fxp = q.from[0], txp = q.from[0] + (q.to[0] - q.from[0]) * w; if (kid.type === 'mirror') { var axm = ax == null ? ch.w / 2 : ax; fxp = 2 * axm - fxp; txp = 2 * axm - txp; } BP.push([fxp, q.from[1]]); BQ.push([txp, q.from[1] + (q.to[1] - q.from[1]) * w]); });
            });
            /* The landmarks already say how far everything travels toward that neighbour. The depth slide is a GUESS at the same
               travel, made from each side: left in, the two drawings arrive at the swap pushed opposite ways and the piece jumps.
               So the slide keeps only the part of the turn that no matched neighbour accounts for. */
            toward.sort(function (p, q) { return q[0] - p[0]; }).forEach(function (q) { var t = (rx * q[1] + ry * q[2]) / q[3]; if (t > 0) { rx -= t * q[1]; ry -= t * q[2]; } });
            return BP.length ? { P: BP, Q: BQ, par: [rx * dpx, ry * dpx] } : null;
          };
          extra.P0 = extra.P;
          var whole = warpOf(''); if (whole) { extra.warp = whole; extra.P = whole.par; }
          /* pieces that do not warp with the whole head: by their own landmarks, or by another piece's */
          if (n.warpWith) { var ws = {}, wn = 0; for (var wkey in n.warpWith) { var tg = n.warpWith[wkey]; tg = tg === 'own' ? wkey : (n.warpWith[tg] === 'own' ? tg : null); ws[wkey] = tg ? warpOf(tg) : whole; wn++; } if (wn) extra.warps = ws; }
        }
        /* this angle's share of the blended tip and size (see above) */
        var er = 0, es = 1, ex0 = 0, ey0 = 0;
        if (mix && !ghost && kid.at) { var ek = effOf(kid); er = mix.rot - ek.rot; es = mix.sc / ek.sc; ex0 = mix.x - ek.x; ey0 = mix.y - ek.y; }
        var pvA = function (q) { if (q.pivot) return q.pivot; if (n.pivot) return n.pivot; var qb = AL.boundsOf(q); return qb ? { x: (qb.x0 + qb.x1) / 2, y: (qb.y0 + qb.y1) / 2 } : { x: 0, y: 0 }; };
        if (kid.type !== 'mirror' && (Math.abs(er) > 0.005 || Math.abs(es - 1) > 0.0005 || Math.abs(ex0) > 0.01 || Math.abs(ey0) > 0.01)) { var pk1 = pvA(kid); m2 = M.mul(Mx, M.trs(ex0, ey0, er * DEG, es, es, pk1.x, pk1.y)); }
        if (kid.type === 'mirror') {
          src = ix.n[kid.of]; if (!src) return;
          if (ax == null) { var b = AL.boundsOf(src); ax = b ? (b.x0 + b.x1) / 2 : ch.w / 2; }
          /* a mirrored angle can be tipped and resized on its own, like any other */
          var mr = (kid.rot || 0) + er, ms = (kid.sc == null ? 1 : kid.sc) * es;
          var mB = Mx;
          var mdx = (kid.dx || 0) + ex0, mdy = (kid.dy || 0) + ey0;
          if (Math.abs(mr) > 0.005 || Math.abs(ms - 1) > 0.0005 || mdx || mdy) { var pk2 = pvA(src); mB = M.mul(Mx, M.trs(mdx, mdy, mr * DEG, ms, ms, 2 * ax - pk2.x, pk2.y)); }
          m2 = M.mul(mB, M.flipX(ax)); extra.mir = !c.mir; extra.tag = (c.tag || '') + 'm'; if (extra.P) { extra.P = [-extra.P[0], extra.P[1]]; extra.rel = [-extra.rel[0], extra.rel[1]]; if (extra.P0) extra.P0 = [-extra.P0[0], extra.P0[1]]; if (extra.warps) for (var fk in extra.warps) { var fw = extra.warps[fk]; if (fw && fw.par && !fw.flp) { fw.flp = 1; fw.par = [-fw.par[0], fw.par[1]]; } } }
          out.M[kid.id] = m2;
        }
        walk(src, kidCtx(c, m2, a, extra));
      }
      function ghosts() { for (var gi = kids.length - 1; gi >= 0; gi--) if (kids[gi] !== cur && !kids[gi].trig && (!opt.onionKid || kids[gi].id === opt.onionKid)) one(kids[gi], c.a * (opt.onionAlpha || 0.2), true); }
      if (opt.onion === n.id && !opt.onionOver) ghosts();
      if (fade) { one(ix.n[fade.from], c.a); one(cur, c.a * smooth(fade.t)); } else one(cur, c.a);
      if (opt.onion === n.id && opt.onionOver) ghosts();
    }

    function emit(n, Mx, c) {
      var im = AL.img.get(n.img); if (!im) return;
      var s = sg(c), limbOn = n.limb && c.reach && n.limb.bend !== false, bendOn = n.swing && n.swing.kind !== 'stiff' && live && n.swing.on !== false, brOn = n.breathe && n.breathe.on !== false && s.breath, meshOn = n.mesh && n.mesh.pins && n.mesh.pins.length;
      var wrapOn = moving && c.ball && c.wrap === 'on', behind = null;
      var dense = (meshOn || wrapOn || limbOn) ? 2 : (n.limb || (n.swing && n.swing.kind !== 'stiff') || n.breathe || c.warp) ? 1 : 0;
      var g = self.grid(n, im, dense), i, zs = (n.zSplit && n.limb && !c.lift && !c.ghost && !c.zo) ? n.zSplit : null;
      var zt = (c.zo && c.zo.how === 'under' && c.zo.tip != null && n.limb && !c.lift && !c.ghost) ? c.zo : null;      // in a stance: the shoulder behind something, the hand in front of it
      if (zs) g = self.splitGrid(g, n.limb, zs.at == null ? 0.5 : zs.at, zs.behind === 'tip'); else if (zt) g = self.splitGrid(g, n.limb, zt.tip, false);
      var v = g.v;
      if (wrapOn) {
        /* painted on a round head: every point slides over the ball as it turns, so the part foreshortens toward the edge and
           is gone once it is round the back. One drawing turns like a whole head. */
        var bb = c.ball.b, br4 = ballRot(c), lf = n.lift || 0, wv = new Float32Array(v.length);
        for (i = 0; i < v.length; i += 2) { var bq = ballPt(bb, br4, v[i], v[i + 1], lf, n.back); wv[i] = bq[0]; wv[i + 1] = bq[1]; if (bq[2] < 0.08) (behind || (behind = new Uint8Array(v.length / 2)))[i >> 1] = 1; }
        v = wv;
      }
      if (c.warp) v = warpMLS(v, c.warp.P, c.warp.Q, null, c.warp.K);
      /* where this limb ends and which way it points there: hands and held things ride on it */
      function tipAt(x, y, ang) {
        if (c.ghost) return; var rl = c.restLimb || n.limb, hose = n.limb.style === 'hose';
        var t = { p: M.ap(Mx, x, y), ang: wAng(Mx, ang), restP: M.ap(Mx, rl.c.x, rl.c.y), rest: wAng(Mx, hose ? Math.atan2(rl.c.y - rl.a.y, rl.c.x - rl.a.x) : Math.atan2(rl.c.y - rl.b.y, rl.c.x - rl.b.x)) };
        t.under = c.lift ? null : zs ? (zs.behind === 'tip' ? (zs.under || n.zUnder || null) : null) : (n.zUnder || c.under || null);      // a hand goes where its arm goes: behind the body with it
        if (c.zo && !c.lift) { t.under = null; t.zo = zt ? zFront(zt) : c.zo; }
        out.tips[n.id] = t; if (c.limbSet) out.tips[c.limbSet] = t;
        if (c.handKey) (S.tips || (S.tips = {}))[c.handKey] = t.p;
      }
      if (limbOn && n.limb.style === 'hose') {
        var hk = solveHose(n.limb, c.reach, s.t);
        if (hk) { v = warpHose(v, n.limb, hk); out.pins[n.id] = { a: M.ap(Mx, n.limb.a.x, n.limb.a.y), b: M.ap(Mx, hk.mid[0], hk.mid[1]), c: M.ap(Mx, hk.T[0], hk.T[1]) }; tipAt(hk.T[0], hk.T[1], hk.ang); }
      } else if (limbOn) {
        var k = solveLimb(n.limb, c.reach);
        if (k) { var vr = v; v = warpLimb(v, n.limb, k); if (n.limb.foot) warpFoot(vr, v, n.limb, k, c.foot || 0); var pa = M.ap(Mx, n.limb.a.x, n.limb.a.y); out.pins[n.id] = { a: pa, b: M.ap(Mx, k.e[0], k.e[1]), c: M.ap(Mx, k.w[0], k.w[1]) }; tipAt(k.w[0], k.w[1], Math.atan2(k.w[1] - k.e[1], k.w[0] - k.e[0])); }
      } else if (n.limb && c.reach && n.limb.bend === false) {
        /* stiff: turn the whole drawing about the shoulder to point at the hand */
        var la = n.limb, r = Math.atan2(c.reach.y - la.a.y, c.reach.x - la.a.x) - Math.atan2(la.c.y - la.a.y, la.c.x - la.a.x);
        Mx = M.mul(Mx, M.trs(0, 0, r, 1, 1, la.a.x, la.a.y));
        tipAt(la.c.x, la.c.y, Math.atan2(la.c.y - la.b.y, la.c.x - la.b.x));
      }
      if (meshOn) {
        var pt = pinTargets(n, s, c);
        if (pt.moved) v = warpMLS(v, pt.P, pt.Q, self.geoFor(n, im), pt.K);
        if (!n.mesh.quiet) out.pins[n.id] = { mesh: pt.Q.map(function (q) { return M.ap(Mx, q[0], q[1]); }) };      // quiet pins shape the part; they are not handles in Perform
        if (!c.ghost) out.meshAt[n.id] = { Q: pt.Q, M: Mx };      // where each of its pins is drawn now, every shape it wears (FACE-1: face pins show there)
      }
      if (brOn) {
        var br = n.breathe, kk = (br.amt == null ? 1 : br.amt) * 0.05 * s.breath, r2 = br.r * br.r, o2 = v === g.v ? new Float32Array(v) : v;
        for (i = 0; i < o2.length; i += 2) { var dx = o2[i] - br.cx, dy = o2[i + 1] - br.cy, d2 = dx * dx + dy * dy; if (d2 < r2) { var f = 1 - d2 / r2; f *= f * kk; o2[i] += dx * f; o2[i + 1] += dy * f * 0.6; } }
        v = o2;
      }
      if (bendOn) v = bendy(n, v === g.v ? new Float32Array(v) : v, Mx, c, s);
      var w = new Float32Array(v.length);
      for (i = 0; i < v.length; i += 2) { w[i] = Mx[0] * v[i] + Mx[2] * v[i + 1] + Mx[4]; w[i + 1] = Mx[1] * v[i] + Mx[3] * v[i + 1] + Mx[5]; }
      var item = { id: n.id, img: n.img, v: w, uv: g.uv, idx: g.idx, a: c.a * (n.opacity == null ? 1 : n.opacity), under: c.lift ? null : (n.zUnder || c.under || null), ghost: !!c.ghost, nx: g.nx, ny: g.ny, clip: c.clip || null, tag: c.tag || '', nc: !!c.nc };
      if (c.zo && !c.lift && !c.ghost) { item.under = null; item.zo = c.zo; }
      if (behind && !zs && !zt) { var ki = [], gi = g.idx; for (i = 0; i < gi.length; i += 3) if (!behind[gi[i]] && !behind[gi[i + 1]] && !behind[gi[i + 2]]) ki.push(gi[i], gi[i + 1], gi[i + 2]); if (!ki.length) return; item.idx = new Uint16Array(ki); }
      if (zs) {
        /* An arm that comes round from behind: the same drawing in two pieces, one under the body and one over it, meeting on a line across the
           limb. Put the line where the limb is clear of the body (the elbow, usually) and it cannot be seen. */
        var back = {}; for (var kk in item) back[kk] = item[kk];
        back.idx = g.back; back.under = zs.under || n.zUnder || null; item.idx = g.front; item.under = null;
        if (back.idx.length) out.items.push(back);
        if (!item.idx.length) return;
      }
      if (zt) { var zb = {}; for (var kz in item) zb[kz] = item[kz]; zb.idx = g.back; item.idx = g.front; item.zo = zFront(zt); if (zb.idx.length) out.items.push(zb); if (!item.idx.length) return; }
      out.items.push(item);
    }

    /*
     * STIFF: a chain link, an earring, a charm. It never bends; it swings from its root pin, knocks against its
     * limits (the rattle), and anything hung from it rides along, so links chained root-to-tip behave like a chain.
     */
    function stepStiff(n, base) {
      var q = st.swing[n.id] || (st.swing[n.id] = { th: 0, om: 0, x: null, f: -9 });
      if (q.f === frame) return q;
      var sw = n.swing, p = pivotOf(n), r = M.ap(base, p.x, p.y), tp = M.ap(base, sw.tip.x, sw.tip.y), dx = tp[0] - r[0], dy = tp[1] - r[1], len = hyp(dx, dy) || 1;
      if (q.x == null || frame - q.f > 2) { q.x = r[0]; q.y = r[1]; q.vx = q.vy = 0; q.th = q.om = 0; }
      q.f = frame; q.sgn = (base[0] * base[3] - base[1] * base[2]) < 0 ? -1 : 1;
      var vx = (r[0] - q.x) / dt, vy = (r[1] - q.y) / dt, ax = (vx - q.vx) / dt, ay = (vy - q.vy) / dt; q.x = r[0]; q.y = r[1]; q.vx = vx; q.vy = vy;
      var rest = Math.atan2(dy, dx), want = 0;
      /* hangs as drawn, whatever it hangs from has turned to */
      if (sw.hang) { want = Math.atan2(sw.tip.y - p.y, (sw.tip.x - p.x) * q.sgn) - rest; while (want > Math.PI) want -= 2 * Math.PI; while (want < -Math.PI) want += 2 * Math.PI; }
      var k = 18 + (sw.stiff == null ? 0.3 : sw.stiff) * 260, cd = 0.5 + (sw.damp == null ? 0.25 : sw.damp) * 16, follow = sw.follow == null ? 1 : sw.follow, lim = (sw.limit == null ? 80 : sw.limit) * DEG, sens = sw.wind == null ? 0.4 : sw.wind, h = dt / 2;
      for (var i = 0; i < 2; i++) {
        var a = rest + q.th, nx = -Math.sin(a), ny = Math.cos(a);
        var acc = -k * (q.th - want) - cd * q.om - clamp((ax * nx + ay * ny) / len, -600, 600) * follow + windAcc(r[0]) * sens * nx / len;
        q.om += acc * h; q.th += q.om * h;
        if (q.th - want > lim) { q.th = want + lim; q.om = -q.om * 0.5; } else if (q.th - want < -lim) { q.th = want - lim; q.om = -q.om * 0.5; }
      }
      q.out = q.th + (sw.wag || 0) * Math.sin(S.t * 3.4 + r[0] * 0.02);
      return q;
    }

    /*
     * BENDY: a tail, a braid, a feather, a lock of hair. A jointed chain from root to tip, each joint trying to
     * carry straight on from the one before it; the root is dragged by whatever it grows from and the rest
     * catches up late. The drawing is skinned along the chain. (Phone: DangleSim, with more joints.)
     */
    function bendy(n, v, Mx, c, s) {
      var sw = n.swing, p = pivotOf(n), N = clamp(Math.round(sw.segs || 4), 1, 10), Mi = M.mul(invRoot, Mx), i, k2, it;
      var key = n.id + (c.tag || ''), nameKey = c.vs ? c.vs + ':' + (n.role || '') + ':' + n.name : null;
      var r = M.ap(Mi, p.x, p.y), tp = M.ap(Mi, sw.tip.x, sw.tip.y), dx = tp[0] - r[0], dy = tp[1] - r[1], len = hyp(dx, dy) || 1, L = len / N, ux = dx / len, uy = dy / len;
      var q = st.swing[key];
      if (!q || q.n !== N || frame - q.f > 2 || hyp(q.p[0] - r[0], q.p[1] - r[1]) > len * 2.5) {
        /* a fresh drawing of the same lock of hair (the head just swapped angle): start it in the bend, and at the speed, the last one had */
        var sh = nameKey && st.shape ? st.shape[nameKey] : null, okS = sh && sh.n === N && frame - sh.f <= 3, b0 = Math.atan2(uy, ux), ca = r[0], cb = r[1], oa = r[0], ob = r[1];
        q = st.swing[key] = { n: N, p: [r[0], r[1]], o: [r[0], r[1]] };
        for (i = 0; i < N; i++) { var an = b0 + (okS ? sh.rel[i] : 0), aw = b0 + (okS ? sh.was[i] : 0); ca += Math.cos(an) * L; cb += Math.sin(an) * L; oa += Math.cos(aw) * L; ob += Math.sin(aw) * L; q.p.push(ca, cb); q.o.push(oa, ob); }
      }
      q.f = frame;
      var stiff = 0.012 + (sw.stiff == null ? 0.35 : sw.stiff) * 0.22, damp = 0.015 + (sw.damp == null ? 0.3 : sw.damp) * 0.2, follow = sw.follow == null ? 1 : sw.follow, sens = sw.wind == null ? 1 : sw.wind, flut = sw.flutter || 0, hang = sw.hang ? 0.5 : 0;
      var sgn = (Mi[0] * Mi[3] - Mi[1] * Mi[2]) < 0 ? -1 : 1, hl = hyp(sw.tip.x - p.x, sw.tip.y - p.y) || 1, hx = (sw.tip.x - p.x) / hl * sgn, hy = (sw.tip.y - p.y) / hl;
      /* how much of the root's move the rest is spared: 0 = rides rigidly, 1 = left behind and catches up, more = flung */
      var mx = r[0] - q.p[0], my = r[1] - q.p[1], keep = 1 - clamp(follow, 0, 1), fling = Math.max(0, follow - 1);
      /* a big sudden move of the root (picked up, a jump, a landing) is mostly CARRIED, not left behind: only so much of one frame's
         move can whip the chain, or a tail lifted off the ground spins like a propeller */
      var jump = hyp(mx, my), capJ = L * 0.45, carry = jump > capJ ? 1 - capJ / jump : 0, kp2 = keep + (1 - keep) * carry, ko2 = keep + fling * (1 - carry) + (1 - keep) * carry;
      for (i = 1; i <= N; i++) { q.p[i * 2] += mx * kp2; q.p[i * 2 + 1] += my * kp2; q.o[i * 2] += mx * ko2; q.o[i * 2 + 1] += my * ko2; }
      q.p[0] = r[0]; q.p[1] = r[1];
      var h = dt / 2, vcap = L * 0.5, lim = (sw.limit == null ? 80 : sw.limit) * DEG, b00 = Math.atan2(uy, ux);
      for (k2 = 0; k2 < 2; k2++) {
        for (i = 1; i <= N; i++) {
          var j = i * 2, vx = (q.p[j] - q.o[j]) * (1 - damp), vy = (q.p[j + 1] - q.o[j + 1]) * (1 - damp); q.o[j] = q.p[j]; q.o[j + 1] = q.p[j + 1];
          var vl = hyp(vx, vy); if (vl > vcap) { vx *= vcap / vl; vy *= vcap / vl; }                 // nothing travels faster than half a joint a step
          var w = (windAcc(q.p[j]) * (i / N) + flut * windIn * 1100 * Math.sin(S.t * 21 + i * 1.7 + r[0])) * sens;
          q.p[j] += vx + w * h * h; q.p[j + 1] += vy;
        }
        for (it = 0; it < 2; it++) for (i = 1; i <= N; i++) {
          var jj = i * 2, px = q.p[jj - 2], py = q.p[jj - 1], dxp = ux, dyp = uy;
          if (i > 1) { dxp = px - q.p[jj - 4]; dyp = py - q.p[jj - 3]; var dl = hyp(dxp, dyp) || 1; dxp /= dl; dyp /= dl; }
          if (hang) { dxp = dxp * (1 - hang) + hx * hang; dyp = dyp * (1 - hang) + hy * hang; var hl2 = hyp(dxp, dyp) || 1; dxp /= hl2; dyp /= hl2; }
          if (windIn && sens) {
            var along = i / N, gust = 0.65 + 0.35 * Math.sin(S.t * 1.7 + r[0] * 0.02), wv = Math.abs(windIn) * sens * along;
            var ripple = 0.26 * Math.sin(S.t * 5.4 - i * 0.95 + r[0] * 0.03) + flut * 0.3 * Math.sin(S.t * 23 - i * 1.9 + r[1] * 0.05);
            var lx = dxp + windIn * sens * 0.6 * gust * along - dyp * ripple * wv, ly = dyp + dxp * ripple * wv, ll = hyp(lx, ly) || 1; dxp = lx / ll; dyp = ly / ll;
          }
          q.p[jj] += (px + dxp * L - q.p[jj]) * stiff; q.p[jj + 1] += (py + dyp * L - q.p[jj + 1]) * stiff;
          var ex = q.p[jj] - px, ey = q.p[jj + 1] - py, el = hyp(ex, ey) || 1; q.p[jj] = px + ex / el * L; q.p[jj + 1] = py + ey / el * L;
          /* "Swings at most": no joint bends further from how it was drawn than its share of the limit (more toward the tip).
             It meets the limit softly and loses most of its speed there, so it cannot wind itself round its root. */
          var dv = wrapA(Math.atan2(q.p[jj + 1] - py, q.p[jj] - px) - b00), mxa = lim * (0.3 + 0.7 * i / N);
          if (dv > mxa || dv < -mxa) { var ca2 = b00 + clamp(dv, -mxa, mxa); q.p[jj] = px + Math.cos(ca2) * L; q.p[jj + 1] = py + Math.sin(ca2) * L; q.o[jj] += (q.p[jj] - q.o[jj]) * 0.7; q.o[jj + 1] += (q.p[jj + 1] - q.o[jj + 1]) * 0.7; }
        }
      }
      if (nameKey) { var bb0 = Math.atan2(uy, ux), old = st.shape ? st.shape[nameKey] : null, rel = []; for (i = 0; i < N; i++) rel.push(wrapA(Math.atan2(q.p[i * 2 + 3] - q.p[i * 2 + 1], q.p[i * 2 + 2] - q.p[i * 2]) - bb0)); (st.shape = st.shape || {})[nameKey] = { n: N, f: frame, rel: rel, was: old && old.n === N && old.f === frame - 1 ? old.rel : (old && old.f === frame ? old.was : rel) }; }
      /* skin the drawing along the chain */
      var inv = M.inv(Mi), ax0 = sw.tip.x - p.x, ay0 = sw.tip.y - p.y, al = hyp(ax0, ay0) || 1, axx = ax0 / al, axy = ay0 / al, restA = Math.atan2(axy, axx), Ll = al / N, pl = [], ang = [];
      for (i = 0; i <= N; i++) pl.push(M.ap(inv, q.p[i * 2], q.p[i * 2 + 1]));
      var wag = (sw.wag || 0) * Math.sin(s.t * Math.PI * (sw.wagRate == null ? 2.2 : sw.wagRate));
      for (i = 0; i < N; i++) ang.push(Math.atan2(pl[i + 1][1] - pl[i][1], pl[i + 1][0] - pl[i][0]) - restA + wag * Math.pow((i + 1) / N, 1.4));
      function seg(i, x, y) { var rx = x - (p.x + axx * Ll * i), ry = y - (p.y + axy * Ll * i), cs = Math.cos(ang[i]), sn = Math.sin(ang[i]); return [pl[i][0] + cs * rx - sn * ry, pl[i][1] + sn * rx + cs * ry]; }
      for (i = 0; i < v.length; i += 2) {
        var x = v[i], y = v[i + 1], t = ((x - p.x) * axx + (y - p.y) * axy) / al, f = t * N - 0.5, i0 = Math.floor(f), wt = smooth(f - i0);
        var a0 = seg(clamp(i0, 0, N - 1), x, y), a1 = seg(clamp(i0 + 1, 0, N - 1), x, y), nxp = a0[0] + (a1[0] - a0[0]) * wt, nyp = a0[1] + (a1[1] - a0[1]) * wt;
        var grip = smooth(clamp(t * N * 2, 0, 1));                     // nothing moves at, or behind, the root
        v[i] = x + (nxp - x) * grip; v[i + 1] = y + (nyp - y) * grip;
      }
      out.pins[n.id] = { chain: pl.map(function (q2) { return M.ap(Mx, q2[0], q2[1]); }) };
      if (!c.ghost) out.tips[n.id] = { p: M.ap(Mx, pl[N][0], pl[N][1]), ang: wAng(Mx, Math.atan2(pl[N][1] - pl[N - 1][1], pl[N][0] - pl[N - 1][0])), restP: M.ap(Mx, sw.tip.x, sw.tip.y), rest: wAng(Mx, restA) };
      return v;
    }

    walk(ch.root, { M: rootM, a: 1, mir: false, P: null, rootIn: rootM, reach: null });
    /* riders whose limb is drawn above them: now the limb is posed, draw them, and put them back where they belong in the stack */
    if (late.length) { var added = 0; late.forEach(function (q) { var n0 = out.items.length; walk(q.n, also(q.c, { late: true })); var made = out.items.splice(n0, out.items.length - n0); if (made.length) { Array.prototype.splice.apply(out.items, [q.at + added, 0].concat(made)); added += made.length; } }); }

    /* masks: "show only inside X" and "hidden where X is". X may be drawn too, or be an invisible cutter. */
    out.items.forEach(function (it) {
      if (!it.clip) return;
      var inside = null, cuts = [], more = [], key = '';
      it.clip.forEach(function (q) { var m = masks[q.by + it.tag] || masks[q.by]; if (!m || !m.length) { if (!q.cut) it.dead = true; return; } key += (q.cut ? '-' : '+') + q.by; if (q.cut) cuts.push(m); else if (!inside) inside = m; else if (m !== inside) more.push(m); });      // inside one shape AND inside another: an eyelid in its eye, the eye in the head's outline
      it.mask = (inside || cuts.length) ? { key: key + it.tag, inside: inside, cuts: cuts, more: more } : null;
    });
    out.items = out.items.filter(function (it) { return !it.dead; });

    /* drawings that belong UNDER another part (an arm that passes behind the body) */
    var moved = out.items.filter(function (it) { return it.under && out.first[it.under] && out.first[it.under] !== it; });
    if (moved.length) {
      var keep = out.items.filter(function (it) { return moved.indexOf(it) < 0; });
      moved.forEach(function (it) { var at = keep.indexOf(out.first[it.under]); if (at < 0) keep.unshift(it); else keep.splice(at, 0, it); });
      out.items = keep;
    }
    /* a stance's own order: these go last, so they have the final say */
    var zm = out.items.filter(function (it) { return it.zo; });
    if (zm.length) {
      var rest = out.items.filter(function (it) { return !it.zo; }), nb = 0, pend = zm, zg = 0;
      /* A part may be placed against a part that has an order of its own (the body behind an arm that is itself behind the head).
         That one has to be on the list first, so anything still waiting for its partner takes a later turn; "in front of everything"
         waits for all that can be placed without it. force = nothing more can be settled (two parts each waiting for the other). */
      var put = function (it, force) {
        var z = it.zo, tf = z.of && out.first[z.of], tl = z.of && out.last[z.of], at;
        if (z.how === 'back') { rest.splice(nb++, 0, it); return true; }
        if (z.how === 'front' && !force) return false;
        if (z.how === 'under' && tf) { if ((at = rest.indexOf(tf)) >= 0) { rest.splice(at, 0, it); return true; } if (!force && tf.zo && tf.zo !== z) return false; }
        else if (z.how === 'over' && tl) { if ((at = rest.indexOf(tl)) >= 0) { at++; while (at < rest.length && rest[at].zo === z) at++; rest.splice(at, 0, it); return true; } if (!force && tl.zo && tl.zo !== z) return false; }
        rest.push(it); return true;
      };
      while (pend.length && zg++ < 12) {
        var wait = pend.filter(function (it) { return !put(it, false); });
        if (wait.length === pend.length) {                       // stuck: the fronts go on now, and the rest get another turn; with no fronts, they simply go on top
          var fr = wait.filter(function (it) { return it.zo.how === 'front'; });
          (fr.length ? fr : wait).forEach(function (it) { put(it, true); });
          wait = fr.length ? wait.filter(function (it) { return it.zo.how !== 'front'; }) : [];
        }
        pend = wait;
      }
      pend.forEach(function (it) { rest.push(it); });
      out.items = rest;
    }
    /* COLOUR SETS in play: the drawing step recolours with them (see al-gl.js). The wave crosses the character's own box. */
    var tones = []; (ch.palettes || []).forEach(function (p) { var w = opt.tones && opt.tones[p.id] != null ? opt.tones[p.id] : (S.tone ? S.tone[p.id] || 0 : 0); if (w > 0.002) (p.stops || []).forEach(function (q) { if (tones.length < 8) tones.push({ from: q.from, to: q.to, sat: q.sat, val: q.val, width: q.width, lv: w, soft: p.soft, blotch: p.blotch }); }); });
    if (tones.length) { var k0 = M.ap(rootM, 0, 0), k1 = M.ap(rootM, ch.w, ch.h); out.colour = { stops: tones, box: [Math.min(k0[0], k1[0]), Math.min(k0[1], k1[1]), Math.max(k0[0], k1[0]), Math.max(k0[1], k1[1])], dir: (ch.settings || {}).toneDir == null ? 25 : ch.settings.toneDir }; }
    return out;
  };

  /** Which drawn part is under this point? Top-first, by real pixels (alpha), through the posed mesh. */
  AL.hit = function (out, x, y, minAlpha, only) {
    for (var n = out.items.length - 1; n >= 0; n--) {
      var it = out.items[n]; if (it.ghost || (it.a0 != null ? it.a0 : it.a) < 0.3 || (only && !only[it.id])) continue;
      var v = it.v, idx = it.idx, uv = it.uv, im = AL.img.get(it.img); if (!im) continue;
      for (var t = 0; t < idx.length; t += 3) {
        var a = idx[t] * 2, b = idx[t + 1] * 2, c = idx[t + 2] * 2;
        var x0 = v[a], y0 = v[a + 1], x1 = v[b], y1 = v[b + 1], x2 = v[c], y2 = v[c + 1];
        var den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2); if (Math.abs(den) < 1e-9) continue;
        var l0 = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / den, l1 = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / den, l2 = 1 - l0 - l1;
        if (l0 < 0 || l1 < 0 || l2 < 0) continue;
        var u = l0 * uv[a] + l1 * uv[b] + l2 * uv[c], w = l0 * uv[a + 1] + l1 * uv[b + 1] + l2 * uv[c + 1];
        var px = clamp(Math.floor(u * im.w), 0, im.w - 1), py = clamp(Math.floor(w * im.h), 0, im.h - 1);
        if (AL.img.alpha(it.img)[py * im.w + px] > (minAlpha == null ? 24 : minAlpha)) return it;
        break;
      }
    }
    return null;
  };
  AL.solveLimb = solveLimb; AL.solveHose = solveHose;
})(typeof self !== 'undefined' ? self : this);
