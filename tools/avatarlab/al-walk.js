/* AvatarLab: walking. Drag the body and the feet look after themselves.
 *
 * There is no walk-cycle animation. Each foot is PLANTED at a spot on the stage and stays there (so nothing
 * slides) until the body has moved far enough that the foot is left behind; then that foot lifts, swings to
 * a spot ahead of the body and plants again. The legs are ordinary bending limbs reaching from the hips to
 * the feet. Arms swing against the legs, the body bobs and leans, and the tail follows because it already
 * swings from whatever it is fixed to.
 *
 * ANY NUMBER OF LEGS. Every limb that walks takes part: two legs, four, a centipede's worth. Each has a STEP
 * TIMING (0..1, its place in the round of steps). Feet with the same timing step together; the others wait
 * their turn, in order, so a biped alternates, a four-legged walk goes hind-left, fore-left, hind-right,
 * fore-right, and a row of legs with timings spread along it moves as a wave. Hurry it and turns overlap by
 * themselves (a walk becomes a trot).
 *
 * LIMBS THAT MAY BECOME FEET. An arm (or any limb) told "you may walk" plants on the ground whenever the
 * ground is within its reach, and is an arm again when it is lifted out of reach. So a character that tips
 * forward onto all fours starts walking on its hands with no other switch, and the front of a long body
 * lifted off the ground gets its arms back.
 *
 * GROUND is a switch, not a kind of scene:
 *   ON   there is a ground line where the character stands. Pull it up and it stretches, goes up on its toes,
 *        and only then leaves the ground: the legs fold on the way up and swing forward on the way down, ready
 *        to land. Let go and it falls back to the line. Push it well down and, if it has an all-fours stance,
 *        it goes down onto its hands instead of squatting deeper; pull it well up and it stands again.
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
  /** how far apart two step timings are, round the clock (0..0.5) */
  function isFootKey(key) { return !!key && key.charAt(0) === 'F'; }
  function pdist(a, b) { var d = Math.abs(a - b) % 1; return d > 0.5 ? 1 - d : d; }
  var SAME = 0.13;                                   // timings this close step in the same wave

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
  /** How far the drawing goes on past the end pin of a limb (the hand beyond the wrist), measured along the forearm. */
  /** How high above the ground line a walking hand's wrist pin stands when nothing says otherwise: so the lowest point of the hand is as low
      as the lowest point of the feet. (A paw whose claws are drawn past the line, like the feet's, stands ON the line.) */
  AL.palmGuess = function (ch, kid) {
    var hand = AL.handReach(kid) * 0.8, sole = 0, n = 0;
    ['legL', 'legR'].forEach(function (r) { var s = AL.firstRole(ch.root, r), k = s && s.kids ? (AL.node(ch, s.pick && s.pick.rest) || s.kids.filter(function (q) { return q.limb; })[0]) : null; if (k && k.limb) { sole += AL.handReach(k) * 0.8; n++; } });
    return Math.max(0, Math.round(hand - (n ? sole / n : 0)));
  };
  AL.handReach = function (kid) {
    var l = kid && kid.limb, im = kid && kid.type === 'art' ? AL.img.get(kid.img) : null; if (!l || !im) return 0;
    var dx = l.c.x - l.b.x, dy = l.c.y - l.b.y, d = hyp(dx, dy) || 1, ux = dx / d, uy = dy / d, al = AL.img.alpha(kid.img), far = 0, wide = d * 0.7, px, py;
    for (py = 0; py < im.h; py += 2) for (px = 0; px < im.w; px += 2) {
      if (al[py * im.w + px] < 90) continue;
      var rx = kid.x + px - l.c.x, ry = kid.y + py - l.c.y, along = rx * ux + ry * uy; if (along <= far || along > d) continue;
      if (Math.abs(rx * -uy + ry * ux) < wide) far = along;
    }
    return far;
  };
  /** Is this set a limb that takes part in walking? (A leg always; anything else once it has been told it may.) */
  AL.walksOn = function (set) { return !!(set && set.type === 'set' && set.pick && set.pick.by === 'reach' && set.pick.hand && (set.pick.walk ? set.pick.walk.on !== false : (set.role === 'legL' || set.role === 'legR'))); };

  AL.Walker = function (app, inp) { this.app = app; this.inp = inp || null; this.reset(); };
  var W = AL.Walker.prototype;
  W.reset = function () { this.x = this.y = this.tx = this.ty = 0; this.base = 0; this.vx = this.vy = 0; this.fallV = 0; this.held = false; this.feet = null; this.sq = 0; this.idle = 0; this.swing = 0; this.lift = 0; this.air = false; this.free = null; this.lastPh = 0; this.low = 0; this.lim = null; this.led = false; };
  /** Every limb that walks, in the order they are found. */
  W.limbs = function () {
    var ch = this.app.ch, ix = AL.ix(ch); if (this._ix === ix) return this._limbs;
    this._ix = ix; var list = [], L = null, R = null, used = {};
    AL.walk(ch.root, function (s) {
      if (!AL.walksOn(s) || used[s.pick.hand]) return;
      var k = AL.node(ch, s.pick.rest); if (!k || !k.limb) k = (s.kids || []).filter(function (q) { return q.limb; })[0]; if (!k) return;
      if (!isFootKey(s.pick.hand) && s.pick.walk && s.pick.walk.kid) { var wkd = AL.node(ch, s.pick.walk.kid); if (wkd && wkd.limb && AL.parentOf(ch, wkd.id) === s) k = wkd; }      // the drawing this arm walks on
      var l = k.limb, key = s.pick.hand, isFoot = key.charAt(0) === 'F', wk = s.pick.walk || {}, side = /L$/.test(s.role || '') ? 'L' : /R$/.test(s.role || '') ? 'R' : '';
      var rec = { key: key, held: isFoot ? 'foot' + key.slice(1) : 'hand' + key, id: s.id, side: side, hip: l.a, foot: l.c, bar: isFoot ? (l.foot || null) : null,
        len: hyp(l.b.x - l.a.x, l.b.y - l.a.y) + hyp(l.c.x - l.b.x, l.c.y - l.b.y), stretch: l.stretch == null ? 0.12 : l.stretch,
        phase: wk.phase != null ? wk.phase : (side === 'R' ? 0.5 : 0) + (isFoot ? 0 : 0.25), autoPhase: wk.phase == null && !isFoot, lift: wk.lift != null ? !!wk.lift : !isFoot, arm: !isFoot, palm: wk.palm == null ? (isFoot ? 0 : AL.palmGuess(ch, k)) : wk.palm };
      used[key] = 1; list.push(rec); if (s.role === 'legL' && !L) L = rec; if (s.role === 'legR' && !R) R = rec;
    });
    var base = list.filter(function (q) { return !q.lift; });
    /* which leg is on the same side as each walking arm: the one whose hip is drawn nearest its shoulder (layer names cannot be trusted
       for this: a file may call the far leg "left" and the near arm "left"). The arm steps a quarter-turn after that leg, and stands
       as much higher on the picture as that leg's foot does. */
    list.forEach(function (q) {
      if (!q.arm || !base.length) return; var m = base[0]; base.forEach(function (b) { if (Math.abs(b.hip.x - q.hip.x) < Math.abs(m.hip.x - q.hip.x)) m = b; }); q.mate = m;
      if (q.autoPhase) q.phase = (m.phase + 0.25) % 1;
    });
    this._legs = base.length >= 2 ? { L: L || base[0], R: R || base[base[0] === L ? 1 : 0] || base[1], all: list, base: base } : null;
    return (this._limbs = list);
  };
  /** The two legs it stands on (and, under .all, every limb that walks). Null when it has fewer than two legs that always walk. */
  W.legs = function () { this.limbs(); return this._legs; };
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
  /** The stance it drops into when pushed low (all fours), if it has one. */
  W.stance = function () { var ch = this.app.ch, w = ch.settings.walk, id = w && w.fours; if (!id) return null; var ps = ch.poses || []; for (var i = 0; i < ps.length; i++) if (ps[i].id === id) return ps[i]; return null; };

  /** One frame. Writes where the character stands, where its feet are, how they roll and how its arms swing into S. */
  W.step = function (dt, S) {
    var app = this.app, ch = app.ch, set = ch.settings, legs = this.legs(), cfg = this.cfg(), inp = this.inp || app.inp, self = this;
    S.root = S.root || { x: 0, y: 0 }; S.rootScale = 1; S.walk = 0; S.foot = S.foot || {};
    if (this.lim) this.tx = clamp(this.tx, this.lim[0], this.lim[1]);      // on a scene's stage it may go this far to either side and no further (al-stage-walk.js); alone on the plain stage there is no such line
    if (!legs) {
      var hv = this.hover(); S.hand.FL = S.hand.FR = null; S.foot.FL = S.foot.FR = 0;
      if (!hv) { S.root.x = S.root.y = 0; return; }
      /* hovering: it drifts after the finger, leans into the move and bobs as it stops. Things that hang from it lag behind by themselves. */
      var hk = 1 - Math.exp(-(hv.quick == null ? 9 : hv.quick) * dt), ox = this.x, oy = this.y;
      this.x += (this.tx - this.x) * hk; this.y += (this.ty - this.y) * hk;
      this.vx += ((this.x - ox) / dt - this.vx) * (1 - Math.exp(-12 * dt)); this.vy += ((this.y - oy) / dt - this.vy) * (1 - Math.exp(-12 * dt));
      S.root.x = this.x; S.root.y = this.y;
      S.sway += clamp(this.vx / 520, -1, 1) * (hv.lean == null ? 1 : hv.lean); S.bounce += clamp(this.vy / 620, -1, 1) * 0.8;
      return;
    }
    var all = legs.all, base = legs.base;
    all.forEach(function (q) { if (!q.arm) S.foot[q.key] = 0; });
    var axis = set.axisX == null ? ch.w / 2 : set.axisX, flip = !!inp.pose.flip;
    function mx(x) { return flip ? 2 * axis - x : x; }
    if (!cfg) {                                    // not a walker: feet simply stay where they were drawn while the body leans
      this.x = this.y = this.tx = this.ty = 0; S.root.x = S.root.y = 0;
      all.forEach(function (q) { if (q.arm) return; S.hand[q.key] = { x: mx(q.foot.x), y: q.foot.y }; var u = inp.pose[q.held]; if (u) S.hand[q.key] = { x: u.x, y: u.y }; });
      return;
    }
    var line = this.grounded(), deep = cfg.ground === 'plane', gy = cfg.groundY || ch.h, stride = Math.max(8, cfg.stride || 60), stepH = cfg.stepH == null ? 14 : cfg.stepH;
    var rollAmt = cfg.roll == null ? 1 : cfg.roll, style = cfg.style == null ? -0.4 : cfg.style, heel = Math.max(0, -style), tip = Math.max(0, style);
    var legLen = 0, toeLen = 1e9, slack = 1e9;
    base.forEach(function (q) {
      legLen += q.len / base.length;
      toeLen = Math.min(toeLen, q.bar ? hyp(q.bar.toe.x - q.foot.x, q.bar.toe.y - q.foot.y) : 0);
      slack = Math.min(slack, q.len * (1 + q.stretch) - (gy - q.hip.y));       // how far the hips can rise with every foot still down: legs straight, a little stretch
    });
    var crouch = legLen * 0.45, fourAt = cfg.fourAt == null ? 0.82 : cfg.fourAt;
    slack = Math.max(3, slack) + toeLen * 0.55 * Math.min(1, rollAmt);         // ...and up on the toes
    var leave = slack + legLen * (cfg.jumpAt == null ? 0.7 : cfg.jumpAt);
    this.band = { y: gy + this.base - legLen, down: crouch * fourAt, up: leave };          // where the hips are, and how far a finger must push or pull them: drawn on the picture by the Ground card             // the finger has to pull well past that before the feet let go: a small wobble of the hand is not a jump
    /* the all-fours stance, if there is one: how far into it the character is, and whether it has been asked for */
    var fp = this.stance(), lv4 = fp && inp.poseLvl ? clamp(inp.poseLvl[fp.id] || 0, 0, 1) : 0, on4 = !!(fp && fp.trig && inp.trig[fp.trig]);

    /* 1. the body goes where the finger takes it, no faster than these legs can carry it */
    var px = this.x, py = this.y, k = 1 - Math.exp(-(this.held || this.led ? 24 : 7) * dt), dx = (this.tx - this.x) * k, cap = stride * 11 * dt, dl = Math.abs(dx);      // (led: a take that is playing is leading it, al-take-walk.js)
    if (dl > cap) dx *= cap / dl;
    this.x += dx;
    if (!line) {                                   // no ground line: up and down the stage is walking too
      var dy = (this.ty - this.y) * k, dly = Math.abs(dy), capY = stride * 8 * dt; if (dly > capY) dy *= capY / dly; this.y += dy; this.air = false; this.fallV = 0; this.base = this.y;
    } else {
      var want = this.ty - this.base;              // where the finger wants the hips, measured from the ground line (up is negative)
      /* pushed well down, and kept there a moment: onto all fours instead of a deeper squat. Pulled well up: back onto two legs. */
      if (fp && fp.trig && cfg.autoFours !== false && this.held && !this.air) {
        if (!on4) { this.low = want > crouch * fourAt ? this.low + dt : 0; if (this.low > 0.32) { inp.setTrig(fp.trig, true); on4 = true; this.low = 0; } }
        else if (-want > legLen * 0.22) { inp.setTrig(fp.trig, false); on4 = false; }
      }
      if (want > crouch) { want = crouch; this.ty = this.base + crouch; }
      if (!this.air && this.held && -want > leave && lv4 < 0.3) this.air = true;
      if (this.air) {
        if (this.held || cfg.fall === false) { this.y += (this.base + want - this.y) * k; this.fallV = 0; if (-want < slack * 0.7) this.air = false; }
        else { this.fallV += 2600 * dt; this.y += this.fallV * dt; if (this.y >= this.base - slack * 0.5) { this.sq = clamp(this.fallV / 700, 0.2, 1.2); this.y = Math.min(this.y, this.base); this.fallV = 0; this.air = false; this.ty = this.base; } else this.ty = this.y; }
      } else {
        var goal = this.held ? Math.max(want, -slack) : 0;        // on the ground it only rises as far as its legs and toes allow
        if (lv4 > 0) {                                            // on all fours it does not squat: the hips sit where the stance puts them, dipping a little on the way down and up
          if (goal > 0) goal *= 1 - lv4;
          goal += ((fp.fours && fp.fours.drop) || 0) * lv4 + Math.sin(Math.PI * lv4) * legLen * 0.05;
        }
        this.y += (this.base + goal - this.y) * k; this.fallV = 0; if (!this.held) this.ty = this.base;
      }
    }
    this.vx += ((this.x - px) / dt - this.vx) * (1 - Math.exp(-14 * dt)); this.vy += ((this.y - py) / dt - this.vy) * (1 - Math.exp(-14 * dt));
    var speed = line ? Math.abs(this.vx) : hyp(this.vx, this.vy), going = speed > stride * 0.35;
    this.idle = going || this.held ? 0 : this.idle + dt;

    /* 2. face the way it is going */
    if (cfg.face !== false && (set.facing === 'left' || set.facing === 'right') && Math.abs(this.vx) > stride * 0.8) { var nf = (this.vx > 0) !== (set.facing === 'right'); if (nf !== !!inp.pose.flip && !inp.held.head) inp.pose.turnX = -(inp.pose.turnX || 0); inp.pose.flip = nf; flip = nf; }

    /* 3. further up the stage is further away (a floor seen from above only) */
    var sc = deep ? clamp(1 + ((line ? this.base : this.y) / ch.h) * (cfg.depth || 0) * 2, 0.35, 2.2) : 1;
    var air = line && this.air, up = line ? -(this.y - this.base) : 0, tiptoe = air ? 0 : clamp((up - (slack - toeLen * 0.55)) / Math.max(1, toeLen * 0.55), 0, 1);

    /* 4. the feet: where each one belongs, and whether the ground is within its reach */
    var F = this.feet || (this.feet = {}), dirx = speed > 1 ? this.vx / speed : 0, diry = !line && speed > 1 ? this.vy / speed : 0;
    var reachAhead = stride * (0.5 + heel * 0.12 - tip * 0.1) * clamp(speed / (stride * 2.2), 0, 1), bodyLift = 0, yBase = line ? this.base : this.y;
    var anch = S.anchor || {}, tips = S.tips || {}, face = (set.facing === 'left' ? -1 : set.facing === 'right' ? 1 : 0) * (flip ? -1 : 1);
    all.forEach(function (L) {
      var f = F[L.key] || (F[L.key] = { pos: null, roll: 0, down: !L.lift }), a = anch[L.key];
      f.hip = (L.lift && a) ? { x: a[0], y: a[1] } : { x: axis + (mx(L.hip.x) - axis) * sc + self.x, y: gy + (L.hip.y - gy) * sc + self.y };
      if (L.arm) {
        /* a hand that walks stands under its shoulder, a little ahead; the far-side hand a little higher, as the far-side foot is */
        var palm = L.palm * sc, far = L.mate ? Math.max(0, gy - L.mate.foot.y) * sc : 0;
        f.home = { x: f.hip.x + (Math.abs(self.vx) > 24 ? (self.vx > 0 ? 1 : -1) : face) * L.len * 0.1 * sc, y: gy + yBase - palm - far };
      } else f.home = { x: axis + (mx(L.foot.x) - axis) * sc + self.x, y: gy + (L.foot.y - gy) * sc + yBase };
      if (L.lift) {
        var hgt = f.home.y - f.hip.y, maxr = L.len * (1 + L.stretch) * sc, can = !air && hgt > 2 && hgt < maxr * (f.down ? 1.12 : 0.99) && !(L.arm && inp.held[L.held]);
        if (can && !f.down) {                      // the ground has come within reach: reach down and plant
          var tp = tips[L.key]; f.down = true; f.planted = true; f.user = false;
          f.pos = tp ? { x: tp[0], y: tp[1] } : { x: f.hip.x, y: f.hip.y + hgt * 0.6 };
          f.step = { t: 0, dur: 0.26, from: { x: f.pos.x, y: f.pos.y }, h: L.len * 0.08, fix: null, land: true };
        } else if (!can && f.down) { f.down = false; f.step = null; f.pos = null; f.planted = false; }      // lifted out of reach (or taken by a finger): let go
        /* on its way down onto all fours the hands go first: they reach for the ground before it is within reach, and the body follows them down */
        f.reach = L.arm && !f.down && on4 && !air && !inp.held[L.held];
      }
      if (f.down && !f.pos) { f.pos = { x: f.home.x, y: f.home.y }; f.planted = true; }
    });
    function settle(f, want, rate) { f.roll += (want * rollAmt - f.roll) * (1 - Math.exp(-rate * dt)); }
    var swinging = [], cands = [];
    all.forEach(function (L) {
      var f = F[L.key], user = !L.arm && inp.held[L.held] ? inp.pose[L.held] : null, want = 0;
      if (!f.down) {
        if (L.arm) return;                          // an arm again: the hand does whatever hands do
        /* a leg that has let go of the ground hangs from its hip, a little bent */
        var e0 = 1 - Math.exp(-12 * dt), hxp = f.hip.x + (mx(L.foot.x) - mx(L.hip.x)) * 0.9 * sc, hyp0 = f.hip.y + (L.foot.y - L.hip.y) * 0.9 * sc;
        f.hang = f.hang || { x: hxp, y: hyp0 }; f.hang.x += (hxp - f.hang.x) * e0; f.hang.y += (hyp0 - f.hang.y) * e0; settle(f, -0.3, 10); return;
      }
      f.hang = null;
      var toeDir = L.bar ? ((mx(L.bar.toe.x) - mx(L.bar.heel.x)) >= 0 ? 1 : -1) : 0, fwd = Math.abs(self.vx) > 24 ? (self.vx > 0 ? 1 : -1) : (toeDir || face);
      if (user) { f.pos = { x: user.x, y: user.y }; f.step = null; f.user = true; f.planted = true; settle(f, 0, 12); return; }
      if (f.user) { f.user = false; f.placed = true; if (line && !air) f.step = { t: 0, dur: 0.1, from: { x: f.pos.x, y: f.pos.y }, fix: { x: f.pos.x, y: f.home.y }, h: 0 }; }
      if (air) {
        /* off the ground. Going up the legs fold under it. The moment it starts to come down (let go, or lowered by the finger) they
           swing forward and reach for the landing, well before it gets there; held still they hang a little bent. */
        var rvx = mx(L.foot.x) - mx(L.hip.x), rvy = L.foot.y - L.hip.y, hang = cfg.air === 'hang', sp = (L.phase % 1) < 0.5 ? 1 : -1;
        var fall = hang ? 0 : Math.max(smooth(self.vy / 150), (!self.held && cfg.fall !== false) ? 0.6 + 0.4 * smooth(self.vy / 320) : 0), rise = hang ? 0 : smooth(-self.vy / 420) * (1 - fall);
        var hx = rvx * 0.92 - clamp(self.vx * 0.05, -L.len * 0.35, L.len * 0.35), hy = rvy * (hang ? 0.98 : 0.86);                 // hanging
        var ux = rvx * 0.5 - fwd * L.len * 0.16 + sp * L.len * 0.06, uy = rvy * 0.5;                                                // folded up
        var lx = rvx * 0.9 + fwd * L.len * (0.36 + sp * 0.08) + clamp(self.vx * 0.05, -L.len * 0.3, L.len * 0.3), ly = rvy * 0.97;  // reaching for the ground
        var tx = mix(mix(hx, ux, rise), lx, fall), ty = mix(mix(hy, uy, rise), ly, fall), e = 1 - Math.exp(-(15 + 9 * fall) * dt);
        f.pos.x += (f.hip.x + tx * sc - f.pos.x) * e; f.pos.y += (f.hip.y + ty * sc - f.pos.y) * e;
        if (f.pos.y > f.home.y) f.pos.y = f.home.y;                                                                                  // never through the floor
        f.planted = false; f.step = null; f.placed = false; want = mix(mix(-0.35, -0.75, rise), 0.4, fall); settle(f, want, 12); return;       // toes point down going up, lift for the landing
      }
      if (!f.planted) { f.planted = true; f.step = { t: 0, dur: 0.08, from: { x: f.pos.x, y: f.pos.y }, h: 0 }; }          // landing
      if (f.step) {
        var st = f.step; st.t += dt / st.dur;
        var to = st.fix || (st.land ? f.home : { x: f.home.x + dirx * reachAhead * (st.hop ? 0.6 : 1), y: f.home.y + diry * reachAhead }), tt = clamp(st.t, 0, 1), e2 = smooth(tt);
        f.pos.x = st.from.x + (to.x - st.from.x) * e2; f.pos.y = st.from.y + (to.y - st.from.y) * e2 - st.h * Math.sin(Math.PI * tt);
        if (st.hop) bodyLift = Math.max(bodyLift, stepH * 2.4 * Math.sin(Math.PI * tt));
        else if (!st.land) self.lift = Math.max(self.lift, Math.sin(Math.PI * tt) * (L.arm ? 0.6 : 1));
        /* the foot through the air: toe trailing as it leaves, level in the middle, then heel first (or toe first, on tiptoe) as it comes down */
        want = st.h && L.bar ? (tt < 0.45 ? mix(-0.7, -0.15, tt / 0.45) : mix(-0.15, 0.55 * heel - 0.5 * tip, (tt - 0.45) / 0.55)) : 0;
        f.roll += (want * rollAmt - f.roll) * (1 - Math.exp(-26 * dt));
        if (st.t >= 1) { f.pos.x = to.x; f.pos.y = to.y; f.step = null; f.placed = false; self.lastPh = L.phase; } else swinging.push(L);
        return;
      }
      var off = hyp(f.home.x - f.pos.x, f.home.y - f.pos.y);
      /* standing on it: flat; as the body passes over and leaves it behind the heel peels up; reaching up, it goes onto its toes */
      var behind = (self.x - (f.pos.x - (f.home.x - self.x))) * (dirx || 0);
      want = L.bar ? Math.min(-0.42 * tip, -0.6 * tiptoe, going ? -0.62 * smooth((behind - stride * 0.12) / (stride * 0.45)) : 0) : 0; settle(f, want, 16);
      cands.push({ L: L, f: f, off: off, need: going ? stride * 0.5 : (self.idle > 0.3 && !f.placed ? stride * 0.16 : 1e9) });
    });
    if (cfg.gait === 'hop') {
      /* a hop: every foot leaves and lands together */
      var avg = 0, least = 1e9; cands.forEach(function (q) { avg += q.off / cands.length; least = Math.min(least, q.need); });
      if (!swinging.length && cands.length && avg > (going ? stride * 0.45 : least)) { var hd = clamp(0.5 * stride / Math.max(speed, 1) + 0.12, 0.2, 0.34); cands.forEach(function (q) { q.f.step = { t: 0, dur: hd, from: { x: q.f.pos.x, y: q.f.pos.y }, h: stepH * 2, hop: true }; }); }
    } else {
      /* whose turn: the next timing after the foot that stepped last; then, among equals, whoever is furthest behind */
      cands = cands.filter(function (q) { return q.off > q.need; });
      cands.sort(function (a, b) { var ka = (((a.L.phase - self.lastPh) % 1) + 1) % 1 || 1, kb = (((b.L.phase - self.lastPh) % 1) + 1) % 1 || 1; return (ka - kb) || (b.off - a.off); });
      cands.forEach(function (q) {
        /* it may go if everything in the air belongs to its own wave, or the one in the air is nearly down and this one is badly behind (a hurry) */
        var ok = swinging.every(function (o) { var fo = F[o.key]; return pdist(o.phase, q.L.phase) <= SAME || (fo.step && fo.step.t > 0.55 && q.off > stride * 0.95); });
        if (!ok) return;
        q.f.step = { t: 0, dur: clamp(0.55 * stride / Math.max(speed, 1), 0.1, 0.3), from: { x: q.f.pos.x, y: q.f.pos.y }, h: stepH * (q.L.arm ? 0.75 : 1) }; swinging.push(q.L);
      });
    }

    /* 5. out. A rolled foot turns about its heel (toe up) or its toe (heel up), so the ankle rises and the leg has further to reach. */
    this.lift *= Math.exp(-9 * dt); this.sq *= Math.exp(-9 * dt);
    this.mv = (this.mv || 0) + ((going && !air ? 1 : 0) - (this.mv || 0)) * (1 - Math.exp(-5 * dt));
    var bob = (cfg.bob == null ? 0.8 : cfg.bob);
    /* the body rides a wave with every step: low as the weight comes down onto a foot, high as the other leg passes it */
    S.root.x = this.x; S.root.y = this.y - bodyLift - bob * (this.lift - 0.42 * this.mv) * legLen * 0.11 * (air ? 0 : 1); S.rootScale = sc;
    all.forEach(function (L) {
      var f = F[L.key];
      if (L.arm) { if (f.down && f.pos) S.hand[L.key] = { x: f.pos.x, y: f.pos.y, w: true }; else if (f.reach) S.hand[L.key] = { x: f.home.x, y: f.home.y, w: true }; return; }      // a walking hand is planted on the stage, like a foot
      if (!f.down) { if (f.hang) S.hand[L.key] = { x: f.hang.x, y: f.hang.y }; S.foot[L.key] = L.bar ? f.roll : 0; return; }
      var ax = f.pos.x, ay = f.pos.y;
      if (L.bar && f.roll) {
        var pv = f.roll > 0 ? L.bar.heel : L.bar.toe, vx0 = (mx(L.foot.x) - mx(pv.x)) * sc, vy0 = (L.foot.y - pv.y) * sc, td = (mx(L.bar.toe.x) - mx(L.bar.heel.x)) >= 0 ? 1 : -1, an = -f.roll * td, cs = Math.cos(an), sn = Math.sin(an);
        ax += cs * vx0 - sn * vy0 - vx0; ay += sn * vx0 + cs * vy0 - vy0;
      }
      S.hand[L.key] = { x: ax, y: ay }; S.foot[L.key] = L.bar ? f.roll : 0;
    });
    var fl = F[legs.L.key], fr = F[legs.R.key], sw = fl.pos && fr.pos ? clamp(((fl.pos.x - fl.home.x) - (fr.pos.x - fr.home.x)) / stride, -1, 1) * (air ? 0.3 : 1) : 0;
    this.swing += (sw - this.swing) * (1 - Math.exp(-14 * dt)); S.walk = this.swing;
    /* contra-body: an arm swings against the leg on ITS side (found by where it is drawn, since layer names cannot be trusted for sides) */
    S.armSide = {}; all.forEach(function (q) { if (q.arm && q.mate) S.armSide[q.key] = q.mate === legs.L ? 1 : -1; });
    S.sway += clamp(this.vx / (stride * 9), -1, 1) * (cfg.lean == null ? 0.5 : cfg.lean) * 1.6 * (1 - lv4 * 0.6);
    S.bounce += -bob * this.lift * 0.9 + this.sq * 1.4 + (air ? -0.4 * smooth(-this.vy / 420) : 0);
  };
})(typeof self !== 'undefined' ? self : this);
