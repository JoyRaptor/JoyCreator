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

  AL.restSignals = function () {
    return { turnX: 0, turnY: 0, roll: 0, lookX: 0, lookY: 0, blinkL: 0, blinkR: 0, browL: 0, browR: 0, mouth: null, viseme: 'Neutral',
      breath: 0, sway: 0, bounce: 0, hand: { L: null, R: null }, trig: {}, pin: {}, flip: false, t: 0 };
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
    if (Math.abs(cr) > 0.03 * L1 * L2) side = cr > 0 ? 1 : -1;
    else { var base = Math.atan2(uy, ux); side = Math.sin(base + A) >= Math.sin(base - A) ? 1 : -1; }   // a straight arm: the elbow drops
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

  AL.Solver = function (ch) { this.ch = ch; this.st = { pick: {}, fade: {}, dangle: {}, flip: 0 }; this.grids = {}; this.geo = {}; };
  AL.Solver.prototype.geoFor = function (n, im) {
    var key = im.ver + '|' + n.x + ',' + n.y + '|' + n.mesh.pins.map(function (p) { return p.id + ':' + Math.round(p.x) + ',' + Math.round(p.y); }).join(';'), g = this.geo[n.id];
    if (g && g.key === key) return g;
    g = this.geo[n.id] = geoFields(n, im, AL.img.alpha(n.img)); g.key = key; return g;
  };
  AL.Solver.prototype.reset = function () { this.st = { pick: {}, fade: {}, dangle: {}, flip: this.st.flip }; };

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
    if (!live) st.flip = S.flip ? 1 : 0; else st.flip = st.flip < target ? Math.min(target, st.flip + dt / 0.16) : Math.max(target, st.flip - dt / 0.16);
    var fx = Math.cos(st.flip * Math.PI); if (Math.abs(fx) < 0.03) fx = fx < 0 ? -0.03 : 0.03;
    var axis = set.axisX == null ? ch.w / 2 : set.axisX;
    var rootM = out.rootM = M.trs(0, 0, 0, fx, 1, axis, 0), flipped = out.flipped = st.flip > 0.5;

    /* signals as the art sees them: inside a mirror, left is right */
    function sigs(mir) {
      if (!mir) return S;
      return { turnX: -S.turnX, turnY: S.turnY, roll: -S.roll, lookX: -S.lookX, lookY: S.lookY, blinkL: S.blinkR, blinkR: S.blinkL, browL: S.browR, browR: S.browL,
        mouth: S.mouth, viseme: S.viseme, breath: S.breath, sway: -S.sway, bounce: S.bounce, hand: S.hand, trig: S.trig, pin: S.pin, t: S.t };
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
      if (live && n.depth && c.P) { tx += n.depth * c.P[0]; ty += n.depth * c.P[1]; any = true; }
      if (n.look) { tx += s.lookX * n.look.rx; ty += s.lookY * n.look.ry; any = true; }
      if (s.mouth != null) {
        if (n.hinge) { rot += (n.hinge.rest === 'closed' ? s.mouth : 1 - s.mouth) * n.hinge.angle * DEG; any = true; }
        if (n.squash) { var open = n.squash.rest === 'closed' ? 1 - s.mouth : s.mouth; sy *= n.squash.min + (1 - n.squash.min) * open; any = true; }
      }
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
      var base = par ? worldOf(par) : M.id(), L = local(n, { mir: false, P: null });
      return (wm[id] = L ? M.mul(base, L) : base);
    }

    function gated(n, c) {
      if (show[n.id]) return false;
      var s = sg(c);
      if (n.blink) { var b = n.side === 'R' ? s.blinkR : s.blinkL; if (n.blink === 'open' ? b > 0.5 : b <= 0.5) return true; }
      if (n.trig && !c.picked && !s.trig[n.trig]) return true;
      if (n.hideOn) for (var i = 0; i < n.hideOn.length; i++) if (s.trig[n.hideOn[i]]) return true;
      return false;
    }

    function walk(n, c) {
      if (n.hidden && !c.picked && !show[n.id]) return;
      if (gated(n, c)) return;
      var Mx = c.M;
      if (n.attach && ix.n[n.attach]) Mx = M.mul(c.rootIn, worldOf(n.attach));
      var L = local(n, c); if (L) Mx = M.mul(Mx, L);
      out.M[n.id] = Mx;
      var mark = out.items.length;
      if (n.type === 'art') emit(n, Mx, c);
      else if (n.type === 'set') walkSet(n, Mx, c);
      else if (n.kids) for (var i = n.kids.length - 1; i >= 0; i--) walk(n.kids[i], { M: Mx, a: c.a, mir: c.mir, P: c.P, rootIn: c.rootIn, reach: null });
      if (out.items.length > mark) out.first[n.id] = out.items[mark];
    }

    function kidCtx(c, Mx, a, extra) { var k = { M: Mx, a: a, mir: c.mir, P: c.P, rootIn: c.rootIn, reach: null, picked: true }; if (extra) for (var e in extra) k[e] = extra[e]; return k; }

    function walkSet(n, Mx, c) {
      var s = sg(c), pk = n.pick || {}, kids = n.kids || [], cur = null, i, k;
      if (!kids.length) return;
      var forced = force[n.id] && ix.n[force[n.id]] && ix.p[force[n.id]] === n ? ix.n[force[n.id]] : null;
      var trigKid = null;
      for (i = 0; i < kids.length; i++) if (kids[i].trig && s.trig[kids[i].trig]) { trigKid = kids[i]; break; }
      var prevId = st.pick[n.id], prev = prevId && ix.p[prevId] === n ? ix.n[prevId] : null, T = null;

      if (pk.by === 'turn') {
        var tx = s.turnX, ty = s.turnY, best = null, bd = 1e9, pd = 1e9;
        for (i = 0; i < kids.length; i++) { k = kids[i]; if (!k.at) continue; var d = hyp(k.at[0] - tx, k.at[1] - ty); if (d < bd) { bd = d; best = k; } if (k === prev) pd = d; }
        cur = (live && prev && prev.at && pd - bd < 0.07) ? prev : best;
      } else if (pk.by === 'reach') {
        var hand = s.hand[pk.hand] , restKid = ix.n[pk.rest] || null;
        if (!restKid || !restKid.limb) for (i = 0; i < kids.length; i++) if (kids[i].limb) { restKid = kids[i]; break; }
        var Tw = hand ? M.ap(c.rootIn, hand.x, hand.y) : (restKid ? M.ap(Mx, restKid.limb.c.x, restKid.limb.c.y) : null);
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

      /* a swap fades in over the old drawing, both solved to the same pose, so it reads as a turn and not a pop */
      var fade = st.fade[n.id];
      if (live && prev && prev !== cur && (pk.by === 'turn' || pk.by === 'reach') && (pk.fade == null ? 110 : pk.fade) > 0) fade = st.fade[n.id] = { from: prev.id, t: 0 };
      st.pick[n.id] = cur.id; out.picks[n.id] = cur.id;
      if (fade) { fade.t += dt * 1000 / Math.max(1, pk.fade == null ? 110 : pk.fade); if (fade.t >= 1 || !live || fade.from === cur.id || !ix.n[fade.from]) { delete st.fade[n.id]; fade = null; } }

      function one(kid, a, ghost) {
        var extra = { ghost: ghost || c.ghost };
        if (pk.by === 'reach') extra.reach = T;
        if (pk.by === 'turn' && kid.at) { var dpx = pk.depth == null ? 14 : pk.depth; extra.P = [(s.turnX - kid.at[0]) * dpx, (s.turnY - kid.at[1]) * dpx]; }
        var m2 = Mx, src = kid;
        if (kid.type === 'mirror') {
          src = ix.n[kid.of]; if (!src) return;
          var ax = n.axisX; if (ax == null) { var b = AL.boundsOf(src); ax = b ? (b.x0 + b.x1) / 2 : ch.w / 2; }
          m2 = M.mul(Mx, M.flipX(ax)); extra.mir = !c.mir; if (extra.P) extra.P = [-extra.P[0], extra.P[1]];
          out.M[kid.id] = m2;
        }
        walk(src, kidCtx(c, m2, a, extra));
      }
      if (opt.onion === n.id) for (i = kids.length - 1; i >= 0; i--) if (kids[i] !== cur && !kids[i].trig) one(kids[i], c.a * 0.2, true);
      if (fade) { one(ix.n[fade.from], c.a); one(cur, c.a * smooth(fade.t)); } else one(cur, c.a);
    }

    function emit(n, Mx, c) {
      var im = AL.img.get(n.img); if (!im) return;
      var s = sg(c), limbOn = n.limb && c.reach && n.limb.bend !== false, dangleOn = n.dangle && live && n.dangle.on !== false, brOn = n.breathe && n.breathe.on !== false && s.breath, meshOn = n.mesh && n.mesh.pins && n.mesh.pins.length;
      var dense = meshOn ? 2 : (n.limb || n.dangle || n.breathe) ? 1 : 0;
      var g = self.grid(n, im, dense), v = g.v, i;
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
      if (dangleOn) v = dangle(n, v === g.v ? new Float32Array(v) : v, Mx, s);
      var w = new Float32Array(v.length);
      for (i = 0; i < v.length; i += 2) { w[i] = Mx[0] * v[i] + Mx[2] * v[i + 1] + Mx[4]; w[i + 1] = Mx[1] * v[i] + Mx[3] * v[i + 1] + Mx[5]; }
      out.items.push({ id: n.id, img: n.img, v: w, uv: g.uv, idx: g.idx, a: c.a * (n.opacity == null ? 1 : n.opacity), under: n.zUnder || null, ghost: !!c.ghost, nx: g.nx, ny: g.ny });
    }

    /* a swinging tail, ear or lock of hair (phone: DangleSim) */
    function dangle(n, v, Mx, s) {
      var d = n.dangle, p = pivotOf(n), tipx = d.tip.x - p.x, tipy = d.tip.y - p.y, len = hyp(tipx, tipy) || 1, ux = tipx / len, uy = tipy / len;
      var q = st.dangle[n.id] || (st.dangle[n.id] = { th: 0, om: 0, x: null, y: null, vx: 0, vy: 0 });
      var wp = M.ap(Mx, p.x, p.y);
      if (q.x == null) { q.x = wp[0]; q.y = wp[1]; }
      var vx = (wp[0] - q.x) / dt, vy = (wp[1] - q.y) / dt, ax = (vx - q.vx) / dt, ay = (vy - q.vy) / dt;
      q.x = wp[0]; q.y = wp[1]; q.vx = vx; q.vy = vy;
      var c0 = Math.cos(q.th), s0 = Math.sin(q.th), nx = -(ux * s0 + uy * c0), ny = ux * c0 - uy * s0;   // normal of the swung axis
      var stiff = d.stiff == null ? 70 : d.stiff, damp = d.damp == null ? 7 : d.damp, swing = d.swing == null ? 1 : d.swing;
      var acc = -stiff * q.th - damp * q.om - clamp((ax * nx + ay * ny) / len, -400, 400) * swing;
      q.om += acc * dt; q.th = clamp(q.th + q.om * dt, -1.3, 1.3);
      var wag = (d.wag == null ? 0.05 : d.wag) * Math.sin(s.t * (d.wagRate == null ? 2.2 : d.wagRate) * 2 * Math.PI * 0.5);
      for (var i = 0; i < v.length; i += 2) {
        var rx = v[i] - p.x, ry = v[i + 1] - p.y, t = clamp((rx * ux + ry * uy) / len, 0, 1.25);
        var ang = (q.th + wag) * Math.pow(t, 1.4); if (!ang) continue;
        var c = Math.cos(ang), sn = Math.sin(ang);
        v[i] = p.x + c * rx - sn * ry; v[i + 1] = p.y + sn * rx + c * ry;
      }
      return v;
    }

    walk(ch.root, { M: rootM, a: 1, mir: false, P: null, rootIn: rootM, reach: null });

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
