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
  AL.bridgeFor = function (ch, set, A, B) {
    var bs = set.bridges || [], ax = set.axisX == null ? ch.w / 2 : set.axisX;
    function find(a, b) { for (var i = 0; i < bs.length; i++) { if (bs[i].a === a && bs[i].b === b) return { br: bs[i], rev: false }; if (bs[i].a === b && bs[i].b === a) return { br: bs[i], rev: true }; } return null; }
    var hit = find(A.id, B.id), flip = false;
    if (!hit && (A.type === 'mirror' || B.type === 'mirror')) {     // the far side of the face: the mirror image of a bridge matched by hand
      var a2 = A.type === 'mirror' ? A.of : (A.at && Math.abs(A.at[0]) < 0.05 ? A.id : null), b2 = B.type === 'mirror' ? B.of : (B.at && Math.abs(B.at[0]) < 0.05 ? B.id : null);
      if (a2 && b2) { hit = find(a2, b2); flip = !!hit; }
    }
    if (!hit || !hit.br.pts || !hit.br.pts.length) return null;
    var meet = hit.br.meet == null ? 0.5 : hit.br.meet;
    return hit.br.pts.map(function (q) {
      var pa = hit.rev ? q.b : q.a, pb = hit.rev ? q.a : q.b, m = hit.rev ? 1 - meet : meet, fx = pa[0], tx = pa[0] + (pb[0] - pa[0]) * m;
      if (flip) { fx = 2 * ax - fx; tx = 2 * ax - tx; }
      return { from: [fx, pa[1]], to: [tx, pa[1] + (pb[1] - pa[1]) * m] };
    });
  };

  AL.restSignals = function () {
    return { turnX: 0, turnY: 0, roll: 0, lookX: 0, lookY: 0, blinkL: 0, blinkR: 0, browL: 0, browR: 0, mouth: null, viseme: 'Neutral',
      lidUp: 0, lidLow: 0, lidTilt: 0, wind: 0, breath: 0, sway: 0, bounce: 0, hand: { L: null, R: null, FL: null, FR: null }, root: { x: 0, y: 0 }, rootScale: 1, walk: 0, dial: {}, trig: {}, pin: {}, flip: false, t: 0 };
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
    for (var i = 0; i < src.length; i += 2) {
      var x = src[i], y = src[i + 1];
      var ux = ax + c1 * (x - ax) - s1 * (y - ay), uy = ay + s1 * (x - ax) + c1 * (y - ay);
      var qx = x - bx, qy = y - by;
      var s = ((qx * d1x + qy * d1y) + (qx * d2x + qy * d2y)) * 0.5;
      var w = smooth((s + R) / (2 * R));
      if (w <= 0) { out[i] = ux; out[i + 1] = uy; continue; }
      var lx = k.e[0] + c2 * qx - s2 * qy, ly = k.e[1] + s2 * qx + c2 * qy;
      out[i] = ux + (lx - ux) * w; out[i + 1] = uy + (ly - uy) * w;
    }
    return out;
  }

  /* ── moving-least-squares, rigid (phone: PuppetRigSolver) ── */
  function warpMLS(src, P, Q, geo) {
    var out = new Float32Array(src.length), n = P.length;
    if (!n) { out.set(src); return out; }
    for (var i = 0; i < src.length; i += 2) {
      var x = src[i], y = src[i + 1], sw = 0, px = 0, py = 0, qx = 0, qy = 0, j, w, W = [], gi = -1;
      if (geo) gi = clamp(Math.floor((y - geo.y) / geo.cell), 0, geo.ch - 1) * geo.cw + clamp(Math.floor((x - geo.x) / geo.cell), 0, geo.cw - 1);
      for (j = 0; j < n; j++) {
        var dx = P[j][0] - x, dy = P[j][1] - y, d2 = dx * dx + dy * dy;
        if (geo) { var gd = geo.f[j][gi] * geo.cell; d2 = Math.max(d2, gd * gd); }          // the long way round, when there is one
        w = 1 / Math.pow(d2 + 1e-3, 1.15); W.push(w); sw += w; px += w * P[j][0]; py += w * P[j][1]; qx += w * Q[j][0]; qy += w * Q[j][1];
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
  AL.Solver.prototype.geoFor = function (n, im) {
    var key = im.ver + '|' + n.x + ',' + n.y + '|' + n.mesh.pins.map(function (p) { return p.id + ':' + Math.round(p.x) + ',' + Math.round(p.y); }).join(';'), g = this.geo[n.id];
    if (g && g.key === key) return g;
    g = this.geo[n.id] = geoFields(n, im, AL.img.alpha(n.img)); g.key = key; return g;
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
    var out = { items: [], pins: {}, M: {}, picks: {}, first: {}, rootM: null, flipped: false };

    /* whole-character mirror: the art faces one way, the character can face both */
    var F = set.facing === 'left' ? -1 : set.facing === 'right' ? 1 : 0, want = st.flipWant || 0;
    if (live && set.autoFlip && F) { var th = set.flipAt == null ? 0.4 : set.flipAt, x = S.turnX * F; if (x < -th) want = 1; else if (x > -th * 0.45) want = 0; }
    st.flipWant = want;
    var target = (want ? 1 : 0) ^ (S.flip ? 1 : 0);
    if (!live || set.flipStyle === 'snap') st.flip = target; else st.flip = st.flip < target ? Math.min(target, st.flip + dt / 0.16) : Math.max(target, st.flip - dt / 0.16);
    var fx = Math.cos(st.flip * Math.PI); if (Math.abs(fx) < 0.03) fx = fx < 0 ? -0.03 : 0.03;
    var axis = set.axisX == null ? ch.w / 2 : set.axisX;
    /* where the character stands on the stage (walking), how big (further away = smaller), then the mirror */
    var rt = S.root || { x: 0, y: 0 }, rs = S.rootScale || 1, gy = (set.walk && set.walk.groundY) || ch.h;
    var TS = M.trs(rt.x, rt.y, 0, rs, rs, axis, gy), rootM = out.rootM = M.mul(TS, M.trs(0, 0, 0, fx, 1, axis, 0)), flipped = out.flipped = st.flip > 0.5;

    /* springs and wind run in the character's own space, so turning the whole character around does not whip the hair */
    var invRoot = M.mul(TS, M.inv(rootM)), moving = live || !!opt.tween, frame = (st.frame = (st.frame || 0) + 1), windIn = (S.wind || 0) * (flipped ? -1 : 1);
    function windAcc(x) { return windIn * 1500 * (0.6 + 0.4 * Math.sin(S.t * 1.9 + x * 0.013) + 0.25 * Math.sin(S.t * 4.7 + x * 0.031)); }
    function ctx(c, Mx, over) { var k = { M: Mx, a: c.a, mir: c.mir, P: c.P, rootIn: c.rootIn, clip: c.clip, tag: c.tag, vs: c.vs, ghost: c.ghost, warp: c.warp, reach: null }; if (over) for (var e in over) k[e] = over[e]; return k; }
    if (this._mkIx !== ix) { this._mkIx = ix; var mk = this.maskIds = {}; AL.walk(ch.root, function (n) { if (n.clips) n.clips.forEach(function (q) { mk[q.by] = 1; }); }); }
    var maskIds = this.maskIds, masks = {};

    /* signals as the art sees them: inside a mirror, left is right */
    function sigs(mir) {
      if (!mir) return S;
      return { turnX: -S.turnX, turnY: S.turnY, roll: -S.roll, lookX: -S.lookX, lookY: S.lookY, blinkL: S.blinkR, blinkR: S.blinkL, browL: S.browR, browR: S.browL,
        mouth: S.mouth, viseme: S.viseme, lidUp: S.lidUp, lidLow: S.lidLow, lidTilt: S.lidTilt, wind: -S.wind, breath: S.breath, sway: -S.sway, bounce: S.bounce, hand: S.hand, walk: -(S.walk || 0), trig: S.trig, pin: S.pin, t: S.t };
    }
    var SM = [S, sigs(true)];
    function sg(c) { return SM[(c.mir ? 1 : 0) ^ (flipped ? 1 : 0)]; }

    function pivotOf(n) {
      if (n.pivot) return n.pivot;
      if (n.type === 'art') { var im = AL.img.get(n.img); if (im) return { x: n.x + im.w / 2, y: n.y + im.h / 2 }; }
      return { x: 0, y: 0 };
    }
    function local(n, c) {
      var s = sg(c), tx = 0, ty = 0, rot = 0, sx = 1, sy = 1, any = false, i;
      if (n.links) for (i = 0; i < n.links.length; i++) {
        var l = n.links[i], v = s[l.sig]; if (v == null || typeof v !== 'number') continue; any = true; v += l.bias || 0;
        if (l.prop === 'x') tx += v * l.amt; else if (l.prop === 'y') ty += v * l.amt; else if (l.prop === 'rot') rot += v * l.amt * DEG;
        else if (l.prop === 'sx') sx *= 1 + v * l.amt; else if (l.prop === 'sy') sy *= 1 + v * l.amt; else if (l.prop === 's') { sx *= 1 + v * l.amt; sy *= 1 + v * l.amt; }
      }
      if (moving && n.depth && c.P) { tx += n.depth * c.P[0]; ty += n.depth * c.P[1]; any = true; }
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

    function walk(n, c) {
      if (n.hidden && !c.picked && !show[n.id]) return;
      if (gated(n, c)) return;
      var Mx = c.M;
      if (n.attach && ix.n[n.attach]) Mx = M.mul(c.rootIn, worldOf(n.attach));
      if (live && n.swing && n.swing.kind === 'stiff' && n.swing.on !== false) stepStiff(n, M.mul(invRoot, Mx));
      var L = local(n, c); if (L) Mx = M.mul(Mx, L);
      out.M[n.id] = Mx;
      if (n.clips && n.clips.length) c = ctx(c, c.M, { clip: (c.clip || []).concat(n.clips), reach: c.reach, picked: c.picked });
      var mark = out.items.length;
      if (n.type === 'art') emit(n, Mx, c);
      else if (n.type === 'set') walkSet(n, Mx, c);
      else if (n.kids) for (var i = n.kids.length - 1; i >= 0; i--) walk(n.kids[i], ctx(c, Mx));
      if (out.items.length > mark) out.first[n.id] = out.items[mark];
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
        for (i = 0; i < kids.length; i++) { k = kids[i]; if (!k.at) continue; var d = hyp(k.at[0] - tx, k.at[1] - ty); if (d < bd) { bd = d; best = k; } if (k === prev) pd = d; }
        cur = (live && prev && prev.at && pd - bd < 0.07) ? prev : best;
      } else if (pk.by === 'reach') {
        var hand = s.hand[pk.hand], foot = !!pk.hand && pk.hand.charAt(0) === 'F', restKid = ix.n[pk.rest] || null;
        if (!restKid || !restKid.limb) for (i = 0; i < kids.length; i++) if (kids[i].limb) { restKid = kids[i]; break; }
        var Tw = hand ? (foot ? [hand.x, hand.y] : M.ap(c.rootIn, hand.x, hand.y)) : (restKid ? M.ap(Mx, restKid.limb.c.x, restKid.limb.c.y) : null);
        if (!hand && !foot && restKid && live && s.walk) {             // nobody is holding this hand: it swings with the walk
          var sa = (set.walk && set.walk.armSwing != null ? set.walk.armSwing : 18) * DEG * s.walk * (pk.hand === 'L' ? 1 : -1), la = restKid.limb.a, lc = restKid.limb.c, cs = Math.cos(sa), sn = Math.sin(sa);
          Tw = M.ap(Mx, la.x + cs * (lc.x - la.x) - sn * (lc.y - la.y), la.y + sn * (lc.x - la.x) + cs * (lc.y - la.y));
        }
        if (Tw) { var iv = M.inv(Mx), tl = M.ap(iv, Tw[0], Tw[1]); T = { x: tl[0], y: tl[1] }; }
        if (T) {
          var bk = null, bdist = 1e9, pdist = 1e9;
          for (i = 0; i < kids.length; i++) { k = kids[i]; if (!k.limb) continue; var dd = hyp(k.limb.c.x - T.x, k.limb.c.y - T.y); if (dd < bdist) { bdist = dd; bk = k; } if (k === prev) pdist = dd; }
          cur = (live && prev && prev.limb && pk.swap !== false && pdist < bdist * 1.18 + 3) ? prev : bk;
          if (pk.swap === false && restKid) cur = restKid;
        } else cur = restKid || kids[0];
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

      /* a swap fades in over the old drawing, both solved to the same pose, so it reads as a turn and not a pop */
      var fade = st.fade[n.id];
      if (live && prev && prev !== cur && (pk.by === 'turn' || pk.by === 'reach') && (pk.fade == null ? 110 : pk.fade) > 0) fade = st.fade[n.id] = { from: prev.id, t: 0 };
      st.pick[n.id] = cur.id; out.picks[n.id] = cur.id;
      if (fade) { fade.t += dt * 1000 / Math.max(1, pk.fade == null ? 110 : pk.fade); if (fade.t >= 1 || !live || fade.from === cur.id || !ix.n[fade.from]) { delete st.fade[n.id]; fade = null; } }

      function one(kid, a, ghost) {
        var extra = { ghost: ghost || c.ghost };
        if (pk.by === 'reach') { extra.reach = (ghost && kid.limb) ? { x: kid.limb.c.x, y: kid.limb.c.y } : T; extra.lift = !live && forced === kid; }
        if (pk.by === 'turn') extra.vs = n.id;
        if (pk.by === 'turn' && kid.at) { var dpx = pk.depth == null ? 14 : pk.depth; extra.P = [(tx - kid.at[0]) * dpx, (ty - kid.at[1]) * dpx]; }
        var m2 = Mx, src = kid, ax = n.axisX;
        if (pk.by === 'turn' && moving && !ghost && kid.at && n.bridges && n.bridges.length) {
          /* warp this angle toward each neighbour it is turning to */
          var BP = [], BQ = [];
          kids.forEach(function (o) {
            if (o === kid || !o.at) return;
            var br = AL.bridgeFor(ch, n, kid, o); if (!br) return;
            var ddx = o.at[0] - kid.at[0], ddy = o.at[1] - kid.at[1], d2 = ddx * ddx + ddy * ddy || 1, tt = ((tx - kid.at[0]) * ddx + (ty - kid.at[1]) * ddy) / d2, w = smooth(clamp(tt / 0.5, 0, 1));
            if (w < 0.002) return;
            br.forEach(function (q) { var fxp = q.from[0], txp = q.from[0] + (q.to[0] - q.from[0]) * w; if (kid.type === 'mirror') { var axm = ax == null ? ch.w / 2 : ax; fxp = 2 * axm - fxp; txp = 2 * axm - txp; } BP.push([fxp, q.from[1]]); BQ.push([txp, q.from[1] + (q.to[1] - q.from[1]) * w]); });
          });
          if (BP.length) extra.warp = { P: BP, Q: BQ };
        }
        if (kid.type === 'mirror') {
          src = ix.n[kid.of]; if (!src) return;
          if (ax == null) { var b = AL.boundsOf(src); ax = b ? (b.x0 + b.x1) / 2 : ch.w / 2; }
          m2 = M.mul(Mx, M.flipX(ax)); extra.mir = !c.mir; extra.tag = (c.tag || '') + 'm'; if (extra.P) extra.P = [-extra.P[0], extra.P[1]];
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
      var dense = meshOn ? 2 : (n.limb || (n.swing && n.swing.kind !== 'stiff') || n.breathe || c.warp) ? 1 : 0;
      var g = self.grid(n, im, dense), v = g.v, i;
      if (c.warp) v = warpMLS(v, c.warp.P, c.warp.Q);
      if (limbOn) {
        var k = solveLimb(n.limb, c.reach);
        if (k) { v = warpLimb(v, n.limb, k); var pa = M.ap(Mx, n.limb.a.x, n.limb.a.y); out.pins[n.id] = { a: pa, b: M.ap(Mx, k.e[0], k.e[1]), c: M.ap(Mx, k.w[0], k.w[1]) }; }
      } else if (n.limb && c.reach && n.limb.bend === false) {
        /* stiff: turn the whole drawing about the shoulder to point at the hand */
        var la = n.limb, r = Math.atan2(c.reach.y - la.a.y, c.reach.x - la.a.x) - Math.atan2(la.c.y - la.a.y, la.c.x - la.a.x);
        Mx = M.mul(Mx, M.trs(0, 0, r, 1, 1, la.a.x, la.a.y));
      }
      if (meshOn) {
        var P = [], Q = [];
        for (i = 0; i < n.mesh.pins.length; i++) {
          var p = n.mesh.pins[i], q = [p.x, p.y], hp = p.role === 'handL' ? s.hand.L : p.role === 'handR' ? s.hand.R : null;
          if (s.pin[p.id]) q = [s.pin[p.id].x, s.pin[p.id].y];
          else if (hp) q = [hp.x, hp.y];
          else if (p.role === 'head') q = [p.x + s.turnX * (n.mesh.headMove == null ? 10 : n.mesh.headMove), p.y + s.turnY * 7 - s.breath * 1.5];
          P.push([p.x, p.y]); Q.push(q);
        }
        v = warpMLS(v, P, Q, self.geoFor(n, im)); out.pins[n.id] = { mesh: Q.map(function (q) { return M.ap(Mx, q[0], q[1]); }) };
      }
      if (brOn) {
        var br = n.breathe, kk = (br.amt == null ? 1 : br.amt) * 0.05 * s.breath, r2 = br.r * br.r, o2 = v === g.v ? new Float32Array(v) : v;
        for (i = 0; i < o2.length; i += 2) { var dx = o2[i] - br.cx, dy = o2[i + 1] - br.cy, d2 = dx * dx + dy * dy; if (d2 < r2) { var f = 1 - d2 / r2; f *= f * kk; o2[i] += dx * f; o2[i + 1] += dy * f * 0.6; } }
        v = o2;
      }
      if (bendOn) v = bendy(n, v === g.v ? new Float32Array(v) : v, Mx, c, s);
      var w = new Float32Array(v.length);
      for (i = 0; i < v.length; i += 2) { w[i] = Mx[0] * v[i] + Mx[2] * v[i + 1] + Mx[4]; w[i + 1] = Mx[1] * v[i] + Mx[3] * v[i + 1] + Mx[5]; }
      out.items.push({ id: n.id, img: n.img, v: w, uv: g.uv, idx: g.idx, a: c.a * (n.opacity == null ? 1 : n.opacity), under: c.lift ? null : (n.zUnder || null), ghost: !!c.ghost, nx: g.nx, ny: g.ny, clip: c.clip || null, tag: c.tag || '' });
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
      for (i = 1; i <= N; i++) { q.p[i * 2] += mx * keep; q.p[i * 2 + 1] += my * keep; q.o[i * 2] += mx * (keep + fling); q.o[i * 2 + 1] += my * (keep + fling); }
      q.p[0] = r[0]; q.p[1] = r[1];
      var h = dt / 2;
      for (k2 = 0; k2 < 2; k2++) {
        for (i = 1; i <= N; i++) {
          var j = i * 2, vx = (q.p[j] - q.o[j]) * (1 - damp), vy = (q.p[j + 1] - q.o[j + 1]) * (1 - damp); q.o[j] = q.p[j]; q.o[j + 1] = q.p[j + 1];
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
      return v;
    }

    walk(ch.root, { M: rootM, a: 1, mir: false, P: null, rootIn: rootM, reach: null });

    /* masks: "show only inside X" and "hidden where X is". X may be drawn too, or be an invisible cutter. */
    out.items.forEach(function (it) {
      if (!it.clip) return;
      var inside = null, cuts = [], key = '';
      it.clip.forEach(function (q) { var m = masks[q.by + it.tag] || masks[q.by]; if (!m || !m.length) { if (!q.cut) it.dead = true; return; } key += (q.cut ? '-' : '+') + q.by; if (q.cut) cuts.push(m); else if (!inside) inside = m; });
      it.mask = (inside || cuts.length) ? { key: key + it.tag, inside: inside, cuts: cuts } : null;
    });
    out.items = out.items.filter(function (it) { return !it.dead; });

    /* drawings that belong UNDER another part (an arm that passes behind the body) */
    var moved = out.items.filter(function (it) { return it.under && out.first[it.under]; });
    if (moved.length) {
      var keep = out.items.filter(function (it) { return moved.indexOf(it) < 0; });
      moved.forEach(function (it) { var at = keep.indexOf(out.first[it.under]); if (at < 0) keep.push(it); else keep.splice(at, 0, it); });
      out.items = keep;
    }
    return out;
  };

  /** Which drawn part is under this point? Top-first, by real pixels (alpha), through the posed mesh. */
  AL.hit = function (out, x, y, minAlpha) {
    for (var n = out.items.length - 1; n >= 0; n--) {
      var it = out.items[n]; if (it.ghost || it.a < 0.3) continue;
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
  AL.solveLimb = solveLimb;
})(typeof self !== 'undefined' ? self : this);
