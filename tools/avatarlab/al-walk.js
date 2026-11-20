/* AvatarLab: walking. Drag the body and the feet look after themselves.
 *
 * There is no walk-cycle animation. Each foot is PLANTED at a spot on the stage and stays there (so nothing
 * slides) until the body has moved far enough that the foot is left behind; then that foot lifts, swings to
 * a spot ahead of the body and plants again. The legs are ordinary bending limbs reaching from the hips to
 * the feet. Arms swing against the legs, the body bobs and leans, and the tail follows because it already
 * swings from whatever it is fixed to.
 *
 * GROUND is a switch, not a kind of scene:
 *   ON   there is a ground line where the character stands. Pull it up and it stretches, goes up on its toes,
 *        and only then leaves the ground: the legs fold on the way up and swing forward on the way down, ready
 *        to land. Let go and it falls back to the line.
 *   OFF  it is always on the ground, wherever it is: left, right, up the stage, down the stage are all walking
 *        (stairs, a hillside, a floor seen from above). Switch Ground back on and the line is wherever it stands.
 *
 * FEET. A leg with a foot bar (heel to toe) keeps its foot level and rolls it: the heel reaches out and strikes,
 * the foot flattens, the heel peels up and the toe pushes off. "Heel to toe" says how much; "Step" slides from
 * heel-first to tiptoe. A leg without a bar ends in whatever was drawn (a ball, a peg) and simply follows.
 *
 * A foot held by a finger is that finger's: it goes where it is put and stays there until the body moves on.
 * Everything here is arithmetic on a few numbers per frame; it ports to the phone as it stands.
 */
(function (root) {
  'use strict';
  var AL = root.AL;
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function hyp(x, y) { return Math.sqrt(x * x + y * y); }
  function smooth(t) { t = clamp(t, 0, 1); return t * t * (3 - 2 * t); }
  function mix(a, b, t) { return a + (b - a) * t; }

  /** Sensible walking for a character, worked out from its legs. Null when it does not have two bending legs. */
  AL.walkDefaults = function (ch) {
    var L = AL.firstRole(ch.root, 'legL'), R = AL.firstRole(ch.root, 'legR'); if (!L || !R || L.type !== 'set' || R.type !== 'set') return null;
    var a = AL.node(ch, L.pick && L.pick.rest) || L.kids[0], b = AL.node(ch, R.pick && R.pick.rest) || R.kids[0]; if (!a || !b || !a.limb || !b.limb) return null;
    var len = (hyp(a.limb.c.x - a.limb.a.x, a.limb.c.y - a.limb.a.y) + hyp(b.limb.c.x - b.limb.a.x, b.limb.c.y - b.limb.a.y)) / 2;
    return { on: true, ground: 'line', air: 'tuck', fall: true, gait: 'walk', stride: Math.round(Math.max(18, len * 0.5)), stepH: Math.round(Math.max(5, len * 0.16)), bob: 0.8, lean: 0.5, armSwing: 18, face: true, depth: 0.35, roll: 1, style: -0.4, groundY: Math.round(Math.max(a.limb.c.y, b.limb.c.y)) };
  };
  /** A first guess at a leg's foot bar: the bottom of the leg drawing, heel to toe. The toe is the longer side from the ankle. */
  AL.guessFoot = function (ch, kid) {
    var l = kid && kid.limb, im = kid && kid.type === 'art' ? AL.img.get(kid.img) : null; if (!l) return null;
    var x0 = l.c.x - 12, x1 = l.c.x + 12, y = l.c.y + 4;
    if (im) {
      var al = AL.img.alpha(kid.img), top = Math.max(0, Math.min(im.h - 1, Math.round(l.c.y - kid.y))), lo = 1e9, hi = -1e9, bot = top, px, py;
      for (py = top; py < im.h; py++) for (px = 0; px < im.w; px++) if (al[py * im.w + px] > 60) { if (px < lo) lo = px; if (px > hi) hi = px; bot = py; }
      if (hi > lo) { x0 = kid.x + lo; x1 = kid.x + hi; y = kid.y + bot; }
    }
    var toeRight = (x1 - l.c.x) >= (l.c.x - x0), fc = (ch.settings || {}).facing; if (fc === 'left') toeRight = false; else if (fc === 'right') toeRight = true;
    return toeRight ? { heel: { x: x0, y: y }, toe: { x: x1, y: y } } : { heel: { x: x1, y: y }, toe: { x: x0, y: y } };
  };

  AL.Walker = function (app, inp) { this.app = app; this.inp = inp || null; this.reset(); };
  var W = AL.Walker.prototype;
  W.reset = function () { this.x = this.y = this.tx = this.ty = 0; this.base = 0; this.vx = this.vy = 0; this.fallV = 0; this.held = false; this.feet = null; this.sq = 0; this.idle = 0; this.swing = 0; this.lift = 0; this.air = false; this.free = null; };
  W.legs = function () {
    var ch = this.app.ch, ix = AL.ix(ch); if (this._ix === ix) return this._legs;
    this._ix = ix; var out = {}, n = 0;
    [['L', 'legL'], ['R', 'legR']].forEach(function (p) {
      var set = AL.firstRole(ch.root, p[1]); if (!set || set.type !== 'set' || !set.pick) return;
      var k = AL.node(ch, set.pick.rest) || set.kids[0]; if (!k || !k.limb) return;
      var l = k.limb; out[p[0]] = { hip: l.a, foot: l.c, bar: l.foot || null, len: hyp(l.b.x - l.a.x, l.b.y - l.a.y) + hyp(l.c.x - l.b.x, l.c.y - l.b.y), stretch: l.stretch == null ? 0.12 : l.stretch }; n++;
    });
    return (this._legs = n === 2 ? out : null);
  };
  W.cfg = function () { var w = this.app.ch.settings.walk; return w && w.on !== false && this.legs() ? w : null; };
  /** No legs, but it travels: a hovering robot, a ghost, a fish, a balloon. */
  W.hover = function () { var hv = this.app.ch.settings.hover; return hv && hv.on !== false && !this.legs() ? hv : null; };
  /** Does dragging the body take the character somewhere? */
  W.travels = function () { return !!(this.cfg() || this.hover()); };
  /** Is there a ground line right now? (Off = it walks wherever it is.) */
  W.grounded = function () { var c = this.cfg(); if (!c) return false; return this.free == null ? c.ground !== 'plane' : !this.free; };
  /** Ground ON: the line is wherever the character stands now. Ground OFF: it walks anywhere. */
  W.setGround = function (on) { this.free = !on; if (on) { this.base = this.y; this.ty = this.y; this.fallV = 0; } this.air = false; };
  W.grab = function (c) { this.held = true; this.g0 = { x: c[0], y: c[1], tx: this.x, ty: this.y }; this.fallV = 0; };
  W.drag = function (c) { if (!this.g0) return; this.tx = this.g0.tx + (c[0] - this.g0.x); this.ty = this.g0.ty + (c[1] - this.g0.y); };
  W.release = function () { this.held = false; this.g0 = null; };
  W.home = function () { this.tx = 0; this.ty = 0; this.base = 0; this.held = false; };

  /** One frame. Writes where the character stands, where its feet are, how they roll and how its arms swing into S. */
  W.step = function (dt, S) {
    var app = this.app, ch = app.ch, set = ch.settings, legs = this.legs(), cfg = this.cfg(), inp = this.inp || app.inp, self = this;
    S.root = S.root || { x: 0, y: 0 }; S.rootScale = 1; S.walk = 0; S.foot = S.foot || { FL: 0, FR: 0 }; S.foot.FL = S.foot.FR = 0;
    if (!legs) {
      var hv = this.hover(); S.hand.FL = S.hand.FR = null;
      if (!hv) { S.root.x = S.root.y = 0; return; }
      /* hovering: it drifts after the finger, leans into the move and bobs as it stops. Things that hang from it lag behind by themselves. */
      var hk = 1 - Math.exp(-(hv.quick == null ? 9 : hv.quick) * dt), ox = this.x, oy = this.y;
      this.x += (this.tx - this.x) * hk; this.y += (this.ty - this.y) * hk;
      this.vx += ((this.x - ox) / dt - this.vx) * (1 - Math.exp(-12 * dt)); this.vy += ((this.y - oy) / dt - this.vy) * (1 - Math.exp(-12 * dt));
      S.root.x = this.x; S.root.y = this.y;
      S.sway += clamp(this.vx / 520, -1, 1) * (hv.lean == null ? 1 : hv.lean); S.bounce += clamp(this.vy / 620, -1, 1) * 0.8;
      return;
    }
    var axis = set.axisX == null ? ch.w / 2 : set.axisX, flip = !!inp.pose.flip;
    function mx(x) { return flip ? 2 * axis - x : x; }
    if (!cfg) {                                    // not a walker: feet simply stay where they were drawn while the body leans
      this.x = this.y = this.tx = this.ty = 0; S.root.x = S.root.y = 0;
      S.hand.FL = { x: mx(legs.L.foot.x), y: legs.L.foot.y }; S.hand.FR = { x: mx(legs.R.foot.x), y: legs.R.foot.y };
      ['L', 'R'].forEach(function (nm) { var u = inp.pose['foot' + nm]; if (u) S.hand['F' + nm] = { x: u.x, y: u.y }; });
      return;
    }
    var line = this.grounded(), deep = cfg.ground === 'plane', gy = cfg.groundY || ch.h, stride = Math.max(8, cfg.stride || 60), stepH = cfg.stepH == null ? 14 : cfg.stepH;
    var rollAmt = cfg.roll == null ? 1 : cfg.roll, style = cfg.style == null ? -0.4 : cfg.style, heel = Math.max(0, -style), tip = Math.max(0, style);
    var legLen = (legs.L.len + legs.R.len) / 2, crouch = legLen * 0.45;
    var toeLen = Math.min(legs.L.bar ? hyp(legs.L.bar.toe.x - legs.L.foot.x, legs.L.bar.toe.y - legs.L.foot.y) : 0, legs.R.bar ? hyp(legs.R.bar.toe.x - legs.R.foot.x, legs.R.bar.toe.y - legs.R.foot.y) : 0);
    /* how far the hips can rise with both feet still down: legs straight, a little stretch, and up on the toes */
    var slack = Math.max(3, Math.min(legs.L.len * (1 + legs.L.stretch) - (gy - legs.L.hip.y), legs.R.len * (1 + legs.R.stretch) - (gy - legs.R.hip.y))) + toeLen * 0.55 * Math.min(1, rollAmt);
    var leave = slack + legLen * 0.38;             // the finger has to pull well past that before the feet let go: a small wobble of the hand is not a jump

    /* 1. the body goes where the finger takes it, no faster than these legs can carry it */
    var px = this.x, py = this.y, k = 1 - Math.exp(-(this.held ? 24 : 7) * dt), dx = (this.tx - this.x) * k, cap = stride * 11 * dt, dl = Math.abs(dx);
    if (dl > cap) dx *= cap / dl;
    this.x += dx;
    if (!line) {                                   // no ground line: up and down the stage is walking too
      var dy = (this.ty - this.y) * k, dly = Math.abs(dy), capY = stride * 8 * dt; if (dly > capY) dy *= capY / dly; this.y += dy; this.air = false; this.fallV = 0; this.base = this.y;
    } else {
      var want = this.ty - this.base;              // where the finger wants the hips, measured from the ground line (up is negative)
      if (want > crouch) { want = crouch; this.ty = this.base + crouch; }
      if (!this.air && this.held && -want > leave) this.air = true;
      if (this.air) {
        if (this.held || cfg.fall === false) { this.y += (this.base + want - this.y) * k; this.fallV = 0; if (-want < slack * 0.7) this.air = false; }
        else { this.fallV += 2600 * dt; this.y += this.fallV * dt; if (this.y >= this.base - slack * 0.5) { this.sq = clamp(this.fallV / 700, 0.2, 1.2); this.y = Math.min(this.y, this.base); this.fallV = 0; this.air = false; this.ty = this.base; } else this.ty = this.y; }
      } else {
        var goal = this.held ? Math.max(want, -slack) : 0;        // on the ground it only rises as far as its legs and toes allow
        this.y += (this.base + goal - this.y) * k; this.fallV = 0; if (!this.held) this.ty = this.base;
      }
    }
    this.vx += ((this.x - px) / dt - this.vx) * (1 - Math.exp(-14 * dt)); this.vy += ((this.y - py) / dt - this.vy) * (1 - Math.exp(-14 * dt));
    var speed = line ? Math.abs(this.vx) : hyp(this.vx, this.vy), going = speed > stride * 0.35;
    this.idle = going || this.held ? 0 : this.idle + dt;

    /* 2. face the way it is going */
    if (cfg.face !== false && (set.facing === 'left' || set.facing === 'right') && Math.abs(this.vx) > stride * 0.8) { inp.pose.flip = (this.vx > 0) !== (set.facing === 'right'); flip = !!inp.pose.flip; }

    /* 3. further up the stage is further away (a floor seen from above only) */
    var sc = deep ? clamp(1 + ((line ? this.base : this.y) / ch.h) * (cfg.depth || 0) * 2, 0.35, 2.2) : 1;
    var air = line && this.air, up = line ? -(this.y - this.base) : 0, tiptoe = air ? 0 : clamp((up - (slack - toeLen * 0.55)) / Math.max(1, toeLen * 0.55), 0, 1);

    /* 4. the feet */
    var F = this.feet || (this.feet = { L: { pos: null, roll: 0 }, R: { pos: null, roll: 0 } }), names = ['L', 'R'], dirx = speed > 1 ? this.vx / speed : 0, diry = !line && speed > 1 ? this.vy / speed : 0;
    var reachAhead = stride * (0.5 + heel * 0.12 - tip * 0.1) * clamp(speed / (stride * 2.2), 0, 1), bodyLift = 0, anyStep = false, yBase = line ? this.base : this.y;
    names.forEach(function (nm) {
      var L = legs[nm], f = F[nm];
      f.home = { x: axis + (mx(L.foot.x) - axis) * sc + self.x, y: gy + (L.foot.y - gy) * sc + yBase };
      f.hip = { x: axis + (mx(L.hip.x) - axis) * sc + self.x, y: gy + (L.hip.y - gy) * sc + self.y };
      if (!f.pos) { f.pos = { x: f.home.x, y: f.home.y }; f.planted = true; }
      if (f.step) anyStep = true;
    });
    names.forEach(function (nm) {
      var L = legs[nm], f = F[nm], other = F[nm === 'L' ? 'R' : 'L'], user = inp.held['foot' + nm] ? inp.pose['foot' + nm] : null, want = 0;
      var toeDir = L.bar ? ((mx(L.bar.toe.x) - mx(L.bar.heel.x)) >= 0 ? 1 : -1) : 0, fwd = Math.abs(self.vx) > 24 ? (self.vx > 0 ? 1 : -1) : toeDir;
      function settle(rate) { f.roll += (want * rollAmt - f.roll) * (1 - Math.exp(-rate * dt)); }
      if (user) { f.pos = { x: user.x, y: user.y }; f.step = null; f.user = true; f.planted = true; settle(12); return; }
      if (f.user) { f.user = false; f.placed = true; if (line && !air) f.step = { t: 0, dur: 0.1, from: { x: f.pos.x, y: f.pos.y }, fix: { x: f.pos.x, y: f.home.y }, h: 0 }; }
      if (air) {
        /* off the ground. Going up the legs fold under it; coming down they swing forward and reach for the landing; held still they hang a little bent. */
        var rvx = mx(L.foot.x) - mx(L.hip.x), rvy = L.foot.y - L.hip.y, hang = cfg.air === 'hang', rise = hang ? 0 : smooth(-self.vy / 420), fall = hang ? 0 : smooth(self.vy / 380), sp = nm === 'L' ? 1 : -1;
        var hx = rvx * 0.92 - clamp(self.vx * 0.05, -L.len * 0.35, L.len * 0.35), hy = rvy * (hang ? 0.98 : 0.86);                 // hanging
        var ux = rvx * 0.5 - fwd * L.len * 0.16 + sp * L.len * 0.06, uy = rvy * 0.5;                                                // folded up
        var lx = rvx * 0.9 + fwd * L.len * (0.34 + sp * 0.07) + clamp(self.vx * 0.05, -L.len * 0.3, L.len * 0.3), ly = rvy * 0.97;  // reaching for the ground
        var tx = mix(mix(hx, ux, rise), lx, fall), ty = mix(mix(hy, uy, rise), ly, fall), e = 1 - Math.exp(-15 * dt);
        f.pos.x += (f.hip.x + tx * sc - f.pos.x) * e; f.pos.y += (f.hip.y + ty * sc - f.pos.y) * e;
        if (f.pos.y > f.home.y) f.pos.y = f.home.y;                                                                                  // never through the floor
        f.planted = false; f.step = null; f.placed = false; want = mix(mix(-0.35, -0.75, rise), 0.4, fall); settle(12); return;       // toes point down going up, lift for the landing
      }
      if (!f.planted) { f.planted = true; f.step = { t: 0, dur: 0.08, from: { x: f.pos.x, y: f.pos.y }, h: 0 }; }          // landing
      if (f.step) {
        var st = f.step; st.t += dt / st.dur;
        var to = st.fix || { x: f.home.x + dirx * reachAhead * (st.hop ? 0.6 : 1), y: f.home.y + diry * reachAhead }, tt = clamp(st.t, 0, 1), e2 = smooth(tt);
        f.pos.x = st.from.x + (to.x - st.from.x) * e2; f.pos.y = st.from.y + (to.y - st.from.y) * e2 - st.h * Math.sin(Math.PI * tt);
        if (st.hop) bodyLift = Math.max(bodyLift, stepH * 2.4 * Math.sin(Math.PI * tt));
        else self.lift = Math.max(self.lift, Math.sin(Math.PI * tt));
        /* the foot through the air: toe trailing as it leaves, level in the middle, then heel first (or toe first, on tiptoe) as it comes down */
        want = st.h ? (tt < 0.45 ? mix(-0.7, -0.15, tt / 0.45) : mix(-0.15, 0.55 * heel - 0.5 * tip, (tt - 0.45) / 0.55)) : 0;
        f.roll += (want * rollAmt - f.roll) * (1 - Math.exp(-26 * dt));
        if (st.t >= 1) { f.pos.x = to.x; f.pos.y = to.y; f.step = null; f.placed = false; self.last = nm; }
        return;
      }
      var off = hyp(f.home.x - f.pos.x, f.home.y - f.pos.y), offOther = hyp(other.home.x - other.pos.x, other.home.y - other.pos.y);
      var need = going ? stride * 0.5 : (self.idle > 0.3 && !f.placed ? stride * 0.16 : 1e9);
      var free = !other.step || (other.step.t > 0.55 && off > stride * 0.95);
      /* standing on it: flat; as the body passes over and leaves it behind the heel peels up; reaching up, it goes onto its toes */
      var behind = (self.x - (f.pos.x - (f.home.x - self.x))) * (dirx || 0);
      want = Math.min(-0.42 * tip, -0.6 * tiptoe, going ? -0.62 * smooth((behind - stride * 0.12) / (stride * 0.45)) : 0); settle(16);
      if (cfg.gait === 'hop') {
        if (!anyStep && (off + offOther) / 2 > (going ? stride * 0.45 : need)) { var hd = clamp(0.5 * stride / Math.max(speed, 1) + 0.12, 0.2, 0.34); names.forEach(function (m) { F[m].step = { t: 0, dur: hd, from: { x: F[m].pos.x, y: F[m].pos.y }, h: stepH * 2, hop: true }; }); anyStep = true; }
      } else if (off > need && free && (off >= offOther || other.step || other.user) && !(self.last === nm && offOther > need && !other.step && !other.user)) {
        f.step = { t: 0, dur: clamp(0.55 * stride / Math.max(speed, 1), 0.1, 0.3), from: { x: f.pos.x, y: f.pos.y }, h: stepH }; anyStep = true;
      }
    });

    /* 5. out. A rolled foot turns about its heel (toe up) or its toe (heel up), so the ankle rises and the leg has further to reach. */
    this.lift *= Math.exp(-9 * dt); this.sq *= Math.exp(-9 * dt);
    var bob = (cfg.bob == null ? 0.8 : cfg.bob);
    S.root.x = this.x; S.root.y = this.y - bodyLift - bob * this.lift * legLen * 0.03 * (air ? 0 : 1); S.rootScale = sc;
    names.forEach(function (nm) {
      var L = legs[nm], f = F[nm], ax = f.pos.x, ay = f.pos.y;
      if (L.bar && f.roll) {
        var pv = f.roll > 0 ? L.bar.heel : L.bar.toe, vx0 = (mx(L.foot.x) - mx(pv.x)) * sc, vy0 = (L.foot.y - pv.y) * sc, td = (mx(L.bar.toe.x) - mx(L.bar.heel.x)) >= 0 ? 1 : -1, an = -f.roll * td, cs = Math.cos(an), sn = Math.sin(an);
        ax += cs * vx0 - sn * vy0 - vx0; ay += sn * vx0 + cs * vy0 - vy0;
      }
      S.hand['F' + nm] = { x: ax, y: ay }; S.foot['F' + nm] = L.bar ? f.roll : 0;
    });
    var sw = clamp(((F.L.pos.x - F.L.home.x) - (F.R.pos.x - F.R.home.x)) / stride, -1, 1) * (air ? 0.3 : 1);
    this.swing += (sw - this.swing) * (1 - Math.exp(-14 * dt)); S.walk = this.swing;
    S.sway += clamp(this.vx / (stride * 9), -1, 1) * (cfg.lean == null ? 0.5 : cfg.lean) * 1.6;
    S.bounce += -bob * this.lift * 0.9 + this.sq * 1.4 + (air ? -0.4 * smooth(-this.vy / 420) : 0);
  };
})(typeof self !== 'undefined' ? self : this);
