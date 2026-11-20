/* AvatarLab: walking. Drag the body and the feet look after themselves.
 *
 * There is no walk-cycle animation. Each foot is PLANTED at a spot on the stage and stays there (so nothing
 * slides) until the body has moved far enough that the foot is left behind; then that foot lifts, swings to
 * a spot ahead of the body and plants again. The legs are ordinary bending limbs reaching from the hips to
 * the feet. Arms swing against the legs, the body bobs and leans, and the tail follows because it already
 * swings from whatever it is fixed to.
 *
 * Ground:  'line'  a side view: one ground line. Lift the body past the reach of the legs and the feet let go;
 *                  the legs tuck (a hop) or hang, and on release the body falls back and lands.
 *          'plane' an angled view from above: every direction is walking, and further up can be further away.
 * Gait:    'walk'  one foot at a time.   'hop'  both feet together, the body arcs.
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

  /** Sensible walking for a character, worked out from its legs. Null when it does not have two bending legs. */
  AL.walkDefaults = function (ch) {
    var L = AL.firstRole(ch.root, 'legL'), R = AL.firstRole(ch.root, 'legR'); if (!L || !R || L.type !== 'set' || R.type !== 'set') return null;
    var a = AL.node(ch, L.pick && L.pick.rest) || L.kids[0], b = AL.node(ch, R.pick && R.pick.rest) || R.kids[0]; if (!a || !b || !a.limb || !b.limb) return null;
    var len = (hyp(a.limb.c.x - a.limb.a.x, a.limb.c.y - a.limb.a.y) + hyp(b.limb.c.x - b.limb.a.x, b.limb.c.y - b.limb.a.y)) / 2;
    return { on: true, ground: 'line', air: 'tuck', fall: true, gait: 'walk', stride: Math.round(Math.max(18, len * 0.5)), stepH: Math.round(Math.max(5, len * 0.16)), bob: 0.8, lean: 0.5, armSwing: 18, face: true, depth: 0.35, groundY: Math.round(Math.max(a.limb.c.y, b.limb.c.y)) };
  };

  AL.Walker = function (app, inp) { this.app = app; this.inp = inp || null; this.reset(); };
  var W = AL.Walker.prototype;
  W.reset = function () { this.x = this.y = this.tx = this.ty = 0; this.vx = this.vy = 0; this.fallV = 0; this.held = false; this.feet = null; this.sq = 0; this.idle = 0; this.swing = 0; this.lift = 0; };
  W.legs = function () {
    var ch = this.app.ch, ix = AL.ix(ch); if (this._ix === ix) return this._legs;
    this._ix = ix; var out = {}, n = 0;
    [['L', 'legL'], ['R', 'legR']].forEach(function (p) {
      var set = AL.firstRole(ch.root, p[1]); if (!set || set.type !== 'set' || !set.pick) return;
      var k = AL.node(ch, set.pick.rest) || set.kids[0]; if (!k || !k.limb) return;
      var l = k.limb; out[p[0]] = { hip: l.a, foot: l.c, len: hyp(l.b.x - l.a.x, l.b.y - l.a.y) + hyp(l.c.x - l.b.x, l.c.y - l.b.y), stretch: l.stretch == null ? 0.12 : l.stretch }; n++;
    });
    return (this._legs = n === 2 ? out : null);
  };
  W.cfg = function () { var w = this.app.ch.settings.walk; return w && w.on !== false && this.legs() ? w : null; };
  W.grab = function (c) { this.held = true; this.g0 = { x: c[0], y: c[1], tx: this.x, ty: this.y }; this.fallV = 0; };
  W.drag = function (c) { if (!this.g0) return; this.tx = this.g0.tx + (c[0] - this.g0.x); this.ty = this.g0.ty + (c[1] - this.g0.y); };
  W.release = function () { this.held = false; this.g0 = null; };
  W.home = function () { this.tx = 0; this.ty = 0; this.held = false; };

  /** One frame. Writes where the character stands, where its feet are and how its arms swing into S. */
  W.step = function (dt, S) {
    var app = this.app, ch = app.ch, set = ch.settings, legs = this.legs(), cfg = this.cfg(), inp = this.inp || app.inp, self = this;
    S.root = S.root || { x: 0, y: 0 }; S.rootScale = 1; S.walk = 0;
    if (!legs) { S.root.x = S.root.y = 0; S.hand.FL = S.hand.FR = null; return; }
    var axis = set.axisX == null ? ch.w / 2 : set.axisX, flip = !!inp.pose.flip;
    function mx(x) { return flip ? 2 * axis - x : x; }
    if (!cfg) {                                    // not a walker: feet simply stay where they were drawn while the body leans
      this.x = this.y = this.tx = this.ty = 0; S.root.x = S.root.y = 0;
      S.hand.FL = { x: mx(legs.L.foot.x), y: legs.L.foot.y }; S.hand.FR = { x: mx(legs.R.foot.x), y: legs.R.foot.y };
      ['L', 'R'].forEach(function (nm) { var u = inp.pose['foot' + nm]; if (u) S.hand['F' + nm] = { x: u.x, y: u.y }; });
      return;
    }
    var plane = cfg.ground === 'plane', gy = cfg.groundY || ch.h, stride = Math.max(8, cfg.stride || 60), stepH = cfg.stepH == null ? 14 : cfg.stepH;
    var legLen = (legs.L.len + legs.R.len) / 2, crouch = legLen * 0.2;
    if (!plane) this.ty = Math.min(this.ty, crouch);

    /* 1. the body goes where the finger takes it, no faster than these legs can carry it */
    var px = this.x, py = this.y, k = 1 - Math.exp(-(this.held ? 24 : 7) * dt), dx = (this.tx - this.x) * k, dy = (this.ty - this.y) * k, cap = stride * 11 * dt, dl = Math.abs(dx);
    if (dl > cap) dx *= cap / dl;
    this.x += dx;
    if (plane) { var dly = Math.abs(dy), capY = stride * 8 * dt; if (dly > capY) dy *= capY / dly; this.y += dy; }
    else if (this.held || cfg.fall === false) { this.y += dy; this.fallV = 0; }
    else if (this.y < -0.5) { this.fallV += 2600 * dt; this.y += this.fallV * dt; if (this.y >= 0) { this.sq = clamp(this.fallV / 700, 0.2, 1.2); this.y = 0; this.fallV = 0; } this.ty = this.y; }
    else { this.ty = 0; this.y += (0 - this.y) * k; this.fallV = 0; }
    this.vx += ((this.x - px) / dt - this.vx) * (1 - Math.exp(-14 * dt)); this.vy += ((this.y - py) / dt - this.vy) * (1 - Math.exp(-14 * dt));
    var speed = plane ? hyp(this.vx, this.vy) : Math.abs(this.vx), going = speed > stride * 0.35;
    this.idle = going || this.held ? 0 : this.idle + dt;

    /* 2. face the way it is going */
    if (cfg.face !== false && (set.facing === 'left' || set.facing === 'right') && Math.abs(this.vx) > stride * 0.8) { inp.pose.flip = (this.vx > 0) !== (set.facing === 'right'); flip = !!inp.pose.flip; }

    /* 3. further up the stage is further away (angled view only) */
    var sc = plane ? clamp(1 + (this.y / ch.h) * (cfg.depth || 0) * 2, 0.35, 2.2) : 1;
    var slack = Math.max(3, Math.min(legs.L.len * (1 + legs.L.stretch) - (gy - legs.L.hip.y), legs.R.len * (1 + legs.R.stretch) - (gy - legs.R.hip.y)));
    var air = !plane && -this.y > slack;

    /* 4. the feet */
    var F = this.feet || (this.feet = { L: { pos: null }, R: { pos: null } }), names = ['L', 'R'], dirx = speed > 1 ? this.vx / speed : 0, diry = plane && speed > 1 ? this.vy / speed : 0;
    var reachAhead = stride * 0.5 * clamp(speed / (stride * 2.2), 0, 1), bodyLift = 0, anyStep = false;
    names.forEach(function (nm) {
      var L = legs[nm], f = F[nm];
      f.home = plane ? { x: axis + (mx(L.foot.x) - axis) * sc + self.x, y: gy + (L.foot.y - gy) * sc + self.y } : { x: mx(L.foot.x) + self.x, y: L.foot.y };
      f.hip = { x: axis + (mx(L.hip.x) - axis) * sc + self.x, y: gy + (L.hip.y - gy) * sc + self.y };
      if (!f.pos) { f.pos = { x: f.home.x, y: f.home.y }; f.planted = true; }
      if (f.step) anyStep = true;
    });
    names.forEach(function (nm) {
      var L = legs[nm], f = F[nm], other = F[nm === 'L' ? 'R' : 'L'], user = inp.held['foot' + nm] ? inp.pose['foot' + nm] : null;
      if (user) { f.pos = { x: user.x, y: user.y }; f.step = null; f.user = true; f.planted = true; return; }
      if (f.user) { f.user = false; f.placed = true; if (!plane && !air) f.step = { t: 0, dur: 0.1, from: { x: f.pos.x, y: f.pos.y }, fix: { x: f.pos.x, y: f.home.y }, h: 0 }; }
      if (air) {                                 // off the ground: tuck up for a hop, or hang
        var rvx = mx(L.foot.x) - mx(L.hip.x), rvy = L.foot.y - L.hip.y, tuck = cfg.air !== 'hang', q = tuck ? 0.66 : 1;
        var tx = f.hip.x + rvx * q - clamp(self.vx * (tuck ? 0.02 : 0.06), -L.len * 0.4, L.len * 0.4) + (tuck ? (nm === 'L' ? 1 : -1) * L.len * 0.05 : 0), ty = f.hip.y + rvy * q;
        var e = 1 - Math.exp(-16 * dt); f.pos.x += (tx - f.pos.x) * e; f.pos.y += (ty - f.pos.y) * e; f.planted = false; f.step = null; f.placed = false; return;
      }
      if (!f.planted) { f.planted = true; f.step = { t: 0, dur: 0.08, from: { x: f.pos.x, y: f.pos.y }, h: 0 }; }          // landing
      if (f.step) {
        var st = f.step; st.t += dt / st.dur;
        var to = st.fix || { x: f.home.x + dirx * reachAhead * (st.hop ? 0.6 : 1), y: f.home.y + diry * reachAhead }, e2 = smooth(st.t);
        f.pos.x = st.from.x + (to.x - st.from.x) * e2; f.pos.y = st.from.y + (to.y - st.from.y) * e2 - st.h * Math.sin(Math.PI * clamp(st.t, 0, 1));
        if (st.hop) bodyLift = Math.max(bodyLift, stepH * 2.4 * Math.sin(Math.PI * clamp(st.t, 0, 1)));
        else self.lift = Math.max(self.lift, Math.sin(Math.PI * clamp(st.t, 0, 1)));
        if (st.t >= 1) { f.pos.x = to.x; f.pos.y = to.y; f.step = null; f.placed = false; self.last = nm; }
        return;
      }
      var off = hyp(f.home.x - f.pos.x, f.home.y - f.pos.y), offOther = hyp(other.home.x - other.pos.x, other.home.y - other.pos.y);
      var need = going ? stride * 0.5 : (self.idle > 0.3 && !f.placed ? stride * 0.16 : 1e9);
      var free = !other.step || (other.step.t > 0.55 && off > stride * 0.95);
      if (cfg.gait === 'hop') {
        if (!anyStep && (off + offOther) / 2 > (going ? stride * 0.45 : need)) { var hd = clamp(0.5 * stride / Math.max(speed, 1) + 0.12, 0.2, 0.34); names.forEach(function (m) { F[m].step = { t: 0, dur: hd, from: { x: F[m].pos.x, y: F[m].pos.y }, h: stepH * 2, hop: true }; }); anyStep = true; }
      } else if (off > need && free && (off >= offOther || other.step || other.user) && !(self.last === nm && offOther > need && !other.step && !other.user)) {
        f.step = { t: 0, dur: clamp(0.55 * stride / Math.max(speed, 1), 0.1, 0.3), from: { x: f.pos.x, y: f.pos.y }, h: stepH }; anyStep = true;
      }
    });

    /* 5. out */
    this.lift *= Math.exp(-9 * dt); this.sq *= Math.exp(-9 * dt);
    S.root.x = this.x; S.root.y = this.y - bodyLift; S.rootScale = sc;
    S.hand.FL = { x: F.L.pos.x, y: F.L.pos.y }; S.hand.FR = { x: F.R.pos.x, y: F.R.pos.y };
    var sw = clamp(((F.L.pos.x - F.L.home.x) - (F.R.pos.x - F.R.home.x)) / stride, -1, 1) * (air ? 0.3 : 1);
    this.swing += (sw - this.swing) * (1 - Math.exp(-14 * dt)); S.walk = this.swing;
    S.sway += clamp(this.vx / (stride * 9), -1, 1) * (cfg.lean == null ? 0.5 : cfg.lean) * 1.6;
    S.bounce += -(cfg.bob == null ? 0.8 : cfg.bob) * this.lift * 0.9 + this.sq * 1.4 + (air ? -0.4 : 0);
    this.air = air;
  };
})(typeof self !== 'undefined' ? self : this);
