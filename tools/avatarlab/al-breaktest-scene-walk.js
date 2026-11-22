/* AvatarLab: BREAK TEST, walking across the stage in a scene (al-stage-walk.js, al-take-walk.js, the walker's limit).
 * It turns on simulated time itself (AL.breakTest.kit.sim(true)): reload the page after it, to get the page's own clock back.
 * Load after al-breaktest.js, al-breaktest-fit.js and al-breaktest-walk.js. Uses the owner's saved Topps
 * (local/owner-topps.character.json, hand edits and an all-fours stance) when it is there.
 *
 *   await AL.breakTest.sceneWalk.run()                              every check
 *   await AL.breakTest.sceneWalk.run({ only: ['far', 'replay'] })   some parts only (the names below)
 *   await AL.breakTest.sceneWalk.proof()                            feeds each main check the fault it is for; every one must be caught
 *
 * Rule of the room: a check only counts once it has been seen to FAIL on the fault. The parts, and the faults planted for them:
 *   far      short      the walk stops at the stage's edge (it cannot leave the picture)
 *            nolimit    there is no limit at all
 *   floor    floor      the character is moved up the floor in the middle of its walk
 *   same     stride     the scene's copy walks with a longer stride than the same character alone
 *   home     home       "Back to the middle" does nothing
 *            rest       the walker is not put back when the scene is held still
 *   replay   replay     the take's body lane is squeezed (the body is no longer over its feet)
 *   fours    hands      the take forgets which hands were walking
 *            leg        a leg is pulled off the body in the middle of the walk
 *   shape    shape      the character is moved while the frame changes shape
 *   shot     shot       where a character stands depends on the camera
 *            close      a close-up does not follow
 *            turn       a replay begins with a turn-round that is not in it (it was left facing the other way)
 *   reopen   reopen     the feet's lane is shifted in the saved file
 *   passes   passes     the first character's walk is wiped before the second pass
 *   putdown  putdown    the one put down is not let go of
 *            stale      a finger left on it still moves somebody
 *   edge     edge       a finger at the edge of the glass means nothing
 *   tab      tab        no tab for a character that cannot be seen
 *            tabpush    a finger on the tab pushes it further out
 *   stick    stickroom  the joystick keeps to the character's own room, as alone
 *   watch    watch      the others watch where the one in hand started
 *   led      led        a playing take does not lead the walker
 *   keep     handback   a walked body is handed back to the older take
 *   small    bigtips    the hands' and feet's circles are as wide as alone (the body cannot be taken)
 *   empty    empty      the plain stage is given a scene's limit
 */
(function (root) {
  'use strict';
  var AL = root.AL, T = AL.breakTest, K = T.kit, Wk = T.walk, SW = T.sceneWalk = {}, scene = AL.scene, sw = AL.stageWalk, tw = AL.takeWalk, W = scene.W, H = scene.H;
  var PARTS = ['far', 'floor', 'same', 'home', 'replay', 'fours', 'shape', 'shot', 'reopen', 'passes', 'putdown', 'edge', 'tab', 'stick', 'watch', 'led', 'keep', 'small', 'empty'];
  function realWait(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function q(sel) { return document.querySelector(sel); }
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function r1(v) { return Math.round(v * 10) / 10; }
  function isLimb(n) { return n.type === 'set' && n.pick && n.pick.by === 'reach' && n.pick.hand; }

  /** The owner's saved Topps as the character you are on, else the one that comes with the room. */
  async function ownTopps(app) {
    var o = null; try { o = await fetch('local/owner-topps.character.json').then(function (r) { return r.ok ? r.json() : null; }); } catch (e) { o = null; }
    if (!o) { await AL.intake.builtIn(app, 'topps'); return 'built in'; }
    delete o.take; AL.img.map = {}; var ch = await AL.fromJson(o); app.setMode('build'); app.setCharacter(ch, { quiet: true }); return 'the owner\'s';
  }

  /* ── measuring ── */
  /** A performer's feet and hands on the STAGE (stage units), by the limb's key: FL, FR, L, R ... */
  function pts(a) { var o = {}, Tm = a.T; AL.findAll(a.ch.root, isLimb).forEach(function (n) { var pk = a.out.pins[a.out.picks[n.id]]; if (pk && pk.c) o[n.pick.hand] = [pk.c[0] * Tm.k + Tm.tx, pk.c[1] * Tm.k + Tm.ty]; }); return o; }
  function dist(a, b) { var d = 0, k; for (k in a) d = Math.max(d, b[k] ? Math.hypot(a[k][0] - b[k][0], a[k][1] - b[k][1]) : 1e9); return d; }
  function place(X, it) { return sw.at(X.R, it); }
  /** How far anything has come off the body of the one in hand: the box gap and the joint gap of the walking test, over what they are standing (or in the stance). */
  function apart(X) {
    var app = AL.app, m = T.measure(app), jg = Wk.jointGaps(app), w = { gap: 0, joint: 0, part: '', bad: !!m.bad }, fp = AL.stance.fours(app.ch), lv = fp && app.inp.poseLvl ? clamp(app.inp.poseLvl[fp.id] || 0, 0, 1) : 0, k;
    for (k in m.gaps) { var g = m.gaps[k]; if (g >= 0 && g - Math.max(0, X.base[k] || 0) > w.gap) { w.gap = g - Math.max(0, X.base[k] || 0); w.part = k; } }
    for (k in jg) { var b0 = X.jbase[k] || 0, b1 = X.jbaseS && X.jbaseS[k] != null ? X.jbaseS[k] : b0, e = jg[k] - (b0 + (b1 - b0) * lv); if (e > w.joint) { w.joint = e; w.jpart = k; } }
    app.out.items.forEach(function (it) { for (var i = 0; i < it.v.length; i++) if (!isFinite(it.v[i])) w.bad = true; });
    return w;
  }
  function whole(w) { return !w.bad && w.gap <= 40 && w.joint <= 20; }
  function worse(a, b) { return { gap: Math.max(a.gap, b.gap), joint: Math.max(a.joint, b.joint), bad: a.bad || b.bad, part: b.gap > a.gap ? b.part : a.part, jpart: b.joint > a.joint ? b.jpart : a.jpart }; }
  function say(w) { return { gap: Math.round(w.gap), part: w.part, joint: Math.round(w.joint), jpart: w.jpart, bad: w.bad }; }
  /** The middle of a performer's head on the stage (across). */
  function headAt(a) {
    var hd = AL.firstRole(a.ch.root, 'head'), ids = {}, x0 = 1e9, x1 = -1e9; if (!hd) return null; AL.walk(hd, function (k) { if (k.type === 'art') ids[k.id] = 1; });
    a.out.items.forEach(function (it) { if (it.ghost || !ids[it.id]) return; for (var i = 0; i < it.v.length; i += 2) { if (it.v[i] < x0) x0 = it.v[i]; if (it.v[i] > x1) x1 = it.v[i]; } });
    return x0 > x1 ? null : (x0 + x1) / 2 * a.T.k + a.T.tx;
  }

  /* ── driving, as a finger on the body does ── */
  function grabBody(inp) { inp.held.body = true; inp.walker.grab([0, 0]); }
  function letBody(inp) { inp.walker.release(); inp.held.body = false; }
  /** The finger goes from dx0 to dx1 (the character's own units from where it went down), `speed` units a frame. each(i) after every frame. */
  async function walk(dx0, dx1, speed, each) { var inp = AL.app.inp, n = Math.max(1, Math.ceil(Math.abs(dx1 - dx0) / speed)); for (var i = 1; i <= n; i++) { inp.walker.drag([dx0 + (dx1 - dx0) * i / n, 0]); await K.raf(1); if (each) each(i); } }
  /* real pointer events on the glass (stage px) */
  function client(g) { var st = q('#stage'), r = st.getBoundingClientRect(), k = r.width / st.clientWidth; return { x: r.left + g[0] * k, y: r.top + g[1] * k }; }
  function pe(el, type, g, id) { var c = client(g); el.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, pointerId: id, clientX: c.x, clientY: c.y, button: 0, isPrimary: id === 7 })); }
  async function slide(el, from, to, id, steps) { for (var i = 1; i <= steps; i++) { pe(el, 'pointermove', [from[0] + (to[0] - from[0]) * i / steps, from[1] + (to[1] - from[1]) * i / steps], id); await K.raf(1); } }
  /** Glass points where a finger takes the BODY of the one in hand, as al-app.js decides it (on the torso or the tail, clear of every hand, foot and pin), best first. */
  function bodyPoints() {
    var app = AL.app, V = app.view, out = app.out, ch = app.ch, near = sw.near(app), rad = 30 / V.s * near * 1.08, md = 34 / V.s * near * 1.08, tips = [], pins = [], x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9, list = [];
    AL.findAll(ch.root, isLimb).forEach(function (n) { var pk = out.pins[out.picks[n.id]]; if (pk && pk.c) tips.push(pk.c); });
    for (var id in out.pins) if (out.pins[id].mesh) out.pins[id].mesh.forEach(function (m) { pins.push(m); });
    out.items.forEach(function (it) { if (it.ghost) return; for (var i = 0; i < it.v.length; i += 2) { x0 = Math.min(x0, it.v[i]); x1 = Math.max(x1, it.v[i]); y0 = Math.min(y0, it.v[i + 1]); y1 = Math.max(y1, it.v[i + 1]); } });
    for (var gy = 1; gy < 28; gy++) for (var gx = 1; gx < 20; gx++) {
      var cx = x0 + (x1 - x0) * gx / 20, cy = y0 + (y1 - y0) * gy / 28, hit = AL.hit(out, cx, cy), zone = hit ? AL.zoneOf(ch, hit.id) : null, d = 1e9;
      if (zone !== 'body' && zone !== 'tail') continue;
      tips.forEach(function (t) { d = Math.min(d, Math.hypot(t[0] - cx, t[1] - cy) - rad); }); pins.forEach(function (t) { d = Math.min(d, Math.hypot(t[0] - cx, t[1] - cy) - md); });
      if (d > 0) list.push({ g: [V.ox + cx * V.s, V.oy + cy * V.s], d: d });
    }
    list.sort(function (a, b) { return b.d - a.d; });
    return list.map(function (p) { return p.g; });
  }
  /** Put a finger down on the body of the one in hand. Returns the glass point, or null when no point takes the body. */
  function fingerOnBody(id) {
    var app = AL.app, ov = q('#ov'), c = bodyPoints();
    for (var i = 0; i < Math.min(4, c.length); i++) { pe(ov, 'pointerdown', c[i], id); if (app.inp.walker.held) return c[i]; pe(ov, 'pointermove', [c[i][0] + 9, c[i][1]], id); pe(ov, 'pointerup', [c[i][0] + 9, c[i][1]], id); app.inp.resetPose(); }
    return null;
  }

  /* ── the stage, as each part wants to find it ── */
  function bind(X) { var R = X.R = AL.app.scene, cast = scene.cast(R); X.tp = cast[0]; X.A = R.actors[X.tp.id]; X.other = cast[1] || null; X.B = X.other ? R.actors[X.other.id] : null; }
  async function fresh(X) {
    var app = AL.app, R = X.R;
    if (AL.stick.state.on) AL.stick.release(app);
    scene.stop(app); if (R.stage !== 'perform') scene.go(app, 'perform');
    scene.hand(app, X.tp.id);
    scene.cast(R).forEach(function (it) { var a = R.actors[it.id], i = a.inp; i.clearTake(); i.resetPose(); i.held = {}; i.walker.reset(); i.pose.flip = false; });
    R.data.cam = []; R.shot = 'wide'; R.free = null; R.easing = null; R.cutNow = true; R.camAt = null;
    if (R.data.shape !== '16:9') scene.shape(app, '16:9');
    X.tp.x = X.x0; X.tp.y = X.y0; X.tp.s = X.s0; if (X.other) { X.other.x = 1400; X.other.y = X.y0; }
    await K.raf(50);
  }
  /** A second character that walks (the Topps that comes with the room), standing to the right. */
  async function second(X) {
    if (X.other) return; var app = AL.app;
    var it = await scene.addCast(app, { stock: 'topps', name: 'Topps' }); await K.raf(6);
    scene.set(app, it.id, { x: 1400, y: X.y0 }); bind(X);
    X.B.ch.settings.idle = false; X.B.ch.settings.blinkAuto = false; await K.raf(6);
  }
  /** Record the one in hand walking. o.fours: on all fours. o.far: from out of the picture on the left, across, out on the right, then a little way back (a turn-round);
      otherwise from where it stands to o.to (its own units) and back to o.back. Every recorded frame is sampled: { ph, p, at, w }. each(i, inp) is called every frame of the walk. */
  async function take(X, o, each) {
    var app = AL.app, inp = app.inp, fp = AL.stance.fours(app.ch), live = [], n = 0;
    await fresh(X);
    if (o.fours && fp) { inp.setTrig(fp.trig, true); await K.raf(70); }
    if (o.far) { grabBody(inp); await walk(0, -6000, 40); letBody(inp); await K.raf(30); }
    function sample() { live.push({ ph: inp.ph, p: pts(X.A), at: place(X, X.tp), w: apart(X), flip: !!inp.S.flip, lv: fp ? inp.poseLvl[fp.id] || 0 : 0, hw: !!(inp.S.hand.L && inp.S.hand.L.w) && !!(inp.S.hand.R && inp.S.hand.R.w) }); n++; if (each) each(n, inp); }
    scene.rec(app);
    for (var i = 0; i < 6; i++) { await K.raf(1); sample(); }                    // (it stands a moment before it sets off)
    grabBody(inp);
    if (o.far) { await walk(0, 4200, 15, sample); await walk(4200, 3500, 15, sample); }
    else { await walk(0, o.to, 14, sample); if (o.back != null) await walk(o.to, o.back, 14, sample); }
    for (i = 0; i < 12; i++) { await K.raf(1); sample(); }
    letBody(inp); for (i = 0; i < 6; i++) { await K.raf(1); sample(); }
    scene.rec(app); await K.raf(4);
    return live;
  }
  /** Play the scene and sample the one in hand for `frames` frames. each(i) is called every frame. */
  async function playback(X, frames, each) {
    var app = AL.app, inp = X.A.inp, out = [], fp = AL.stance.fours(X.A.ch);
    X.A.inp.walker.home(); await K.raf(40);                                       // (the walker itself is somewhere else when the take starts)
    scene.play(app);
    for (var i = 0; i < frames; i++) { await K.raf(1); out.push({ ph: inp.ph, p: pts(X.A), at: place(X, X.tp), w: app.inp === inp ? apart(X) : null, lv: fp ? inp.poseLvl[fp.id] || 0 : 0 }); if (each) each(i + 1); }
    scene.stop(app); await K.raf(3);
    return out;
  }
  /** The worst difference between a recording and a replay of it, frame against the same frame: feet and hands on the stage, and where the body stands. */
  function against(live, pb) {
    var o = { n: 0, feet: 0, body: 0, at: -1 }, byPh = {}; pb.forEach(function (s) { byPh[s.ph.toFixed(5)] = s; });
    live.forEach(function (l, i) { var s = byPh[l.ph.toFixed(5)]; if (!s || !i) return; o.n++; var d = dist(l.p, s.p); if (d > o.feet) { o.feet = d; o.at = i; } o.body = Math.max(o.body, Math.abs(l.at - s.at)); });      // (not the very first frame: at 33.3 ms a step the first two frames of a 30-a-second take share its first slot, so the replay starts on the second)
    o.feet = r1(o.feet); o.body = r1(o.body); return o;
  }
  function same(c, live) { return c.n >= live.length * 0.9 && c.feet <= 1 && c.body <= 0.5; }

  /* ── the same scripted walk, measured in the character's own units (for "the same walk as alone") ── */
  async function script(fours) {
    var app = AL.app, inp = app.inp, wk = inp.walker, fp = AL.stance.fours(app.ch), out = [];
    inp.clearTake(); inp.resetPose(); inp.held = {}; wk.reset(); inp.pose.flip = false; if (fours && fp) inp.setTrig(fp.trig, true); await K.raf(80);
    function s() { var S = inp.S, o = { x: S.root.x, y: S.root.y, flip: S.flip ? 1 : 0, FL: [S.hand.FL.x, S.hand.FL.y], FR: [S.hand.FR.x, S.hand.FR.y] }; ['L', 'R'].forEach(function (k) { if (S.hand[k] && S.hand[k].w) o[k] = [S.hand[k].x, S.hand[k].y]; }); out.push(o); }
    grabBody(inp); await walk(0, 520, 13, s); await walk(520, -260, 13, s); for (var i = 0; i < 25; i++) { await K.raf(1); s(); } letBody(inp); await K.raf(10);
    if (fours && fp) inp.setTrig(fp.trig, false);
    return out;
  }
  function scriptDiff(a, b) {
    var d = 0, n = Math.min(a.length, b.length), steps = 0, turns = 0, fourFrames = 0;
    for (var i = 0; i < n; i++) {
      d = Math.max(d, Math.abs(a[i].x - b[i].x), Math.abs(a[i].y - b[i].y), a[i].flip === b[i].flip ? 0 : 999);
      ['FL', 'FR', 'L', 'R'].forEach(function (k) { if (!!a[i][k] !== !!b[i][k]) d = Math.max(d, 999); else if (a[i][k]) d = Math.max(d, Math.hypot(a[i][k][0] - b[i][k][0], a[i][k][1] - b[i][k][1])); });
      if (i && Math.abs(a[i].FL[0] - a[i - 1].FL[0]) > 4 && Math.abs(a[i - 1].FL[0] - (a[i - 2] || a[i - 1]).FL[0]) <= 4) steps++;      // (a foot sets off: it was planted, now it swings)
      if (i && a[i].flip !== a[i - 1].flip) turns++; if (a[i].L && a[i].R) fourFrames++;
    }
    return { d: a.length === b.length ? d : 999, frames: n, steps: steps, turns: turns, fourFrames: fourFrames };
  }

  /* ── the parts ── */
  var part = {};
  part.far = async function (X, F, plant) {
    await fresh(X); var R = X.R, it = X.tp, inp = AL.app.inp, B0 = sw.BEYOND;
    if (plant === 'short') sw.BEYOND = 0; if (plant === 'nolimit') sw.BEYOND = 9;
    try {
      grabBody(inp); await walk(0, 6000, 40); await K.raf(45); var r = place(X, it), br = scene.boxOf(R, it), wr = apart(X);
      await walk(6000, -6000, 40); await K.raf(45); var l = place(X, it), bl = scene.boxOf(R, it), wl = apart(X); letBody(inp);
      F('far: it walks right out of the picture on either side (its whole body past the stage)', br[0] > W + 20 && bl[2] < -20, { right: br.map(Math.round), left: bl.map(Math.round) });
      F('far: ...and stops a third of the stage past either side, no further', Math.abs(r - (W + W / 3)) < 4 && Math.abs(l + W / 3) < 4, { right: Math.round(r), left: Math.round(l), want: [Math.round(-W / 3), Math.round(W + W / 3)] });
      F('far: at either end of its travel nothing has come off the body', whole(wr) && whole(wl), { right: say(wr), left: say(wl) });
    } finally { sw.BEYOND = B0; }
  };
  part.floor = async function (X, F, plant) {
    await fresh(X); var it = X.tp, A = X.A, inp = AL.app.inp, k0 = A.T.k, stand = pts(A), below = 0, lift = 0, sc = 0, win = { FL: 1e9, FR: 1e9 };
    grabBody(inp);
    await walk(0, 1500, 14, function (i) {
      if (plant === 'floor' && i === 40) it.y -= 60;
      var p = pts(A); ['FL', 'FR'].forEach(function (k) { var d = p[k][1] - stand[k][1]; below = Math.max(below, d); win[k] = Math.min(win[k], -d); });
      if (i % 25 === 0) { lift = Math.max(lift, win.FL, win.FR); win = { FL: 1e9, FR: 1e9 }; }      // (every foot comes down onto the line again within any 25 frames)
      if (Math.abs(inp.S.rootScale - 1) > 1e-6 || Math.abs(A.T.k - k0) > 1e-9) sc++;
    });
    letBody(inp);
    F('floor: walking across the stage its feet stay on its own floor line (neither goes below it, and each comes down onto it at every step)', below < 3 && lift < 3, { below: r1(below), offTheLine: r1(lift) });
    F('floor: ...and how far back it stands does not change (the same size all the way)', sc === 0, { frames: sc });
    it.y = X.y0;
  };
  part.same = async function (X, F, plant) {
    await fresh(X); var wkc = X.A.ch.settings.walk, st0 = wkc.stride;
    if (plant === 'stride') wkc.stride = st0 * 1.4;
    try {
      await script(false); var a = await script(false), b = AL.stance.fours(X.A.ch) ? await script(true) : null;      // (one walk first: the engine keeps a little from earlier frames, so the first walk after opening is not quite the same as the next, alone too)
      var d1 = scriptDiff(X.solo.stand, a), d2 = b && X.solo.fours ? scriptDiff(X.solo.fours, b) : null;
      F('same: in a scene it walks exactly as it does alone: the same steps on the same spots, the same turn-round', d1.d < 0.5 && d1.steps >= 4 && d1.turns >= 1, d1);
      if (d2) F('same: ...and the same four-legged walk, hands planted like feet', d2.d < 0.5 && d2.steps >= 4 && d2.turns >= 1 && d2.fourFrames > d2.frames * 0.7, d2); else X.note.push('same: no all fours on this character');
    } finally { wkc.stride = st0; }
  };
  part.home = async function (X, F, plant) {
    await fresh(X); var app = AL.app, it = X.tp, A = X.A, inp = app.inp, stand = pts(A), home0 = AL.Walker.prototype.home, reset0 = AL.Walker.prototype.reset;
    grabBody(inp); await walk(0, -6000, 40); letBody(inp); await K.raf(20); var out = place(X, it);
    if (plant === 'home') AL.Walker.prototype.home = function () { };
    try {
      app.refreshPanel(); await K.raf(2);
      var chip = [].filter.call(document.querySelectorAll('#panelbody button'), function (b) { return b.textContent.trim() === 'Back to the middle'; })[0];
      if (chip) chip.click(); await K.raf(280);
      var p = pts(A), under = Math.abs((p.FL[0] + p.FR[0]) / 2 - it.x), up = Math.max(Math.abs(p.FL[1] - stand.FL[1]), Math.abs(p.FR[1] - stand.FR[1]));      // (it comes home turned round, so its feet have changed sides: under it, and on the line)
      F('home: "Back to the middle" (its button on the Perform card) walks it home from out of the picture, feet under it on its floor line', !!chip && !!chip.title && out < -400 && Math.abs(place(X, it) - it.x) < 1.5 && under < 60 && up < 3 && whole(apart(X)), { button: !!chip, was: Math.round(out), now: Math.round(place(X, it)), place: it.x, feetFromPlace: r1(under), feetOffLine: r1(up) });
    } finally { AL.Walker.prototype.home = home0; }
    grabBody(inp); await walk(0, 700, 30); letBody(inp); await K.raf(10);
    if (plant === 'rest') AL.Walker.prototype.reset = function () { var x = this.x, tx = this.tx; reset0.call(this); this.x = x; this.tx = tx; };
    try {
      scene.go(app, 'fit'); await K.raf(4); scene.go(app, 'perform'); await K.raf(30); var back = place(X, it);
      scene.go(app, 'fit'); await K.raf(3); scene.set(app, it.id, { x: 700 }); await K.raf(3); scene.go(app, 'perform'); await K.raf(30); var moved = place(X, it);
      F('home: held still it is back at its place whatever it had walked, and Arrange moves that place', Math.abs(back - X.x0) < 1.5 && Math.abs(moved - 700) < 1.5, { afterArrange: Math.round(back), place: X.x0, movedTo: Math.round(moved) });
    } finally { AL.Walker.prototype.reset = reset0; }
  };
  part.replay = async function (X, F, plant) {
    var live = await take(X, { far: true }), tk = X.A.inp.take, w = { gap: 0, joint: 0 }; live.forEach(function (s) { w = worse(w, s.w); });
    F('replay: a walk from out of the picture on the left, right across and out on the right, is kept as that character\'s take', !!(tk && tk.n >= live.length - 2 && tk.ch.rootX) && live[0].at < -W / 3 + 6 && live[live.length - 1].at > W + 300, { n: tk && tk.n, from: Math.round(live[0].at), to: Math.round(live[live.length - 1].at) });
    if (plant === 'replay') tk.ch.rootX = tk.ch.rootX.map(function (v) { return v * 0.9; });
    var wp = { gap: 0, joint: 0 }, pb = await playback(X, live.length - 2), c = against(live, pb); pb.forEach(function (s) { wp = worse(wp, s.w); });
    F('replay: played back, the feet are on the same spots of the stage and the body over them, frame by frame', same(c, live), c);
    F('replay: nothing comes off the body, walking or played back', whole(w) && whole(wp), { walking: say(w), played: say(wp) });
  };
  part.fours = async function (X, F, plant) {
    var fp = AL.stance.fours(X.A.ch); if (!fp) { X.note.push('fours: not run, no all fours can be worked out for this character'); return; }
    var leg = AL.firstRole(X.A.ch.root, 'legR'), dx0 = leg && leg.dx;
    var live = await take(X, { fours: true, far: true }, function (n) { if (plant === 'leg' && leg) { if (n === 120) leg.dx = (dx0 || 0) + 200; if (n === 150) { if (dx0 == null) delete leg.dx; else leg.dx = dx0; } } });
    if (leg) { if (dx0 == null) delete leg.dx; else leg.dx = dx0; }
    var tk = X.A.inp.take, w = { gap: 0, joint: 0 }, on4 = 0, hw = 0, turns = 0; live.forEach(function (s, i) { w = worse(w, s.w); if (s.lv > 0.95) on4++; if (s.hw) hw++; if (i && s.flip !== live[i - 1].flip) turns++; });
    F('fours: walking on all fours right across the stage, out of the picture and turned round: every limb stays joined to the body (joints against the worked-out stance)', whole(w) && on4 > live.length * 0.9 && hw > live.length * 0.6 && turns >= 1, { worst: say(w), onFours: on4, handsPlanted: hw, frames: live.length, turns: turns });
    if (plant === 'hands') { delete tk.ch.hLw; delete tk.ch.hRw; }
    var wp = { gap: 0, joint: 0 }, pb = await playback(X, live.length - 2), c = against(live, pb); pb.forEach(function (s) { wp = worse(wp, s.w); });
    F('fours: played back, feet AND walking hands are on the same spots of the stage, frame by frame', same(c, live), c);
    F('fours: ...and nothing comes off the body in the replay', whole(wp), say(wp));
    X.A.inp.setTrig(fp.trig, false);
  };
  part.shape = async function (X, F, plant) {
    var app = AL.app, live = await take(X, { to: 900, back: 300 }), moved = [];
    for (var i = 0; i < scene.SHAPES.length; i++) {
      scene.shape(app, scene.SHAPES[i][0]); if (plant === 'shape' && i === 2) X.tp.x += 40; await K.raf(3);
      var c = against(live, await playback(X, live.length - 2)); if (!same(c, live)) moved.push(scene.SHAPES[i][0] + ' feet ' + c.feet + ' body ' + c.body);
    }
    F('shape: the same take in every frame shape: the feet land on the same spots of the stage', moved.length === 0, moved);
    scene.shape(app, '16:9'); X.tp.x = X.x0;
  };
  part.shot = async function (X, F, plant) {
    var app = AL.app, R = X.R, A = X.A, place0 = scene.place, target0 = scene.target, fr = scene.frame(R.data), snap0 = tw.snap;
    var live = await take(X, { to: 620, back: -420 });                              // (it ends facing the other way from how it started: the replay must start facing as recorded)
    if (plant === 'turn') tw.snap = false;
    AL.frame.set(app, true); await K.raf(3);
    if (plant === 'shot') scene.place = function (d, it, ch) { var o = place0(d, it, ch); o.tx += (app.scene.cam.x - W / 2) * 0.2; return o; };
    if (plant === 'close') scene.target = function (a, key, f) { return key.slice(0, 6) === 'close:' ? { x: W / 2, y: H * 0.6, z: 2 } : target0(a, key, f); };
    try {
      var n = live.length - 2, lost = 0, seen = 0, zmin = 9;
      var pb = await playback(X, n, function (i) {
        if (i === 12) scene.shot(app, 'close:' + X.tp.id, 0); if (i === n - 14) scene.shot(app, 'wide', 0);
        if (i > 30 && i < n - 16) { var hx = headAt(A), c = R.cam, half = fr.w / (2 * c.z); seen++; zmin = Math.min(zmin, c.z); if (hx == null || Math.abs(hx - c.x) > half * 0.6) lost++; }
      });
      var c = against(live, pb);
      F('shot: switching the camera shot while it plays moves nothing on the stage', same(c, live), c);
      F('shot: a close-up on the walker follows it across the stage (its head stays in the picture)', seen > 20 && lost === 0 && zmin > 1.1, { frames: seen, lost: lost, zoom: r1(zmin) });
    } finally { scene.place = place0; scene.target = target0; tw.snap = snap0; scene.shot(app, 'wide', 0); }
  };
  part.reopen = async function (X, F, plant) {
    var app = AL.app, live = await take(X, { to: 900, back: 300 }), id = X.tp.id;
    var again = JSON.parse(JSON.stringify(scene.toJson(app, true)));
    if (plant === 'reopen') again.takes[id].ch.fLx = again.takes[id].ch.fLx.map(function (v) { return v == null ? v : v + 30; });
    await scene.close(app, true); await K.raf(3); await scene.open(app, again, { stage: 'perform' }); await K.raf(10); bind(X);
    var tk = X.A.inp.take, c = against(live, await playback(X, live.length - 2));
    F('reopen: saved, closed and opened again, the walk plays with the feet on the same spots of the stage', !!(tk && tk.ch.rootX) && same(c, live), c);
  };
  part.passes = async function (X, F, plant) {
    await second(X); var app = AL.app, A = X.A, B = X.B, itB = X.other;
    var liveA = await take(X, { to: 620 });                                        // pass 1: the first walks to the right
    scene.hand(app, itB.id); await K.raf(4);
    if (plant === 'passes') A.inp.take.ch.rootX = A.inp.take.ch.rootX.map(function () { return 0; });
    /* a frame is known by the clock when it was played: the one recording (or leading the playback) moves its clock on after the frame, a follower is set to the
       leader's clock before it. So a recorded sample is keyed one step back, a follower's by its own clock. */
    var DT = 0.0333, byPh = {}; liveA.forEach(function (s) { byPh[(s.ph - DT).toFixed(4)] = s; });
    var off = 0, seen = 0, liveB = [], inp = app.inp;
    function sample() { liveB.push({ ph: inp.ph, at: place(X, itB), p: pts(B) }); var s = byPh[A.inp.ph.toFixed(4)]; if (s && A._follow) { seen++; if (Math.abs(place(X, X.tp) - s.at) > 0.5 || dist(pts(A), s.p) > 1) off++; } }
    scene.rec(app); for (var i = 0; i < 6; i++) { await K.raf(1); sample(); }
    grabBody(inp); await walk(0, -560, 14, sample); letBody(inp); for (i = 0; i < 6; i++) { await K.raf(1); sample(); }
    scene.rec(app); await K.raf(4);
    F('passes: while the second character records its own walk, the first plays its walk back in step, feet on the same spots', seen >= 30 && off === 0, { seen: seen, off: off });
    F('passes: each has its own take with its own walk', !!(A.inp.take && A.inp.take.ch.rootX && B.inp.take && B.inp.take.ch.rootX) && A.inp.take !== B.inp.take && Math.min.apply(null, B.inp.take.ch.rootX) < -400, { a: A.inp.take && A.inp.take.n, b: B.inp.take && B.inp.take.n });
    var bPh = {}, offA = 0, offB = 0, n = 0; liveB.forEach(function (s) { bPh[s.ph.toFixed(5)] = s; });
    A.inp.walker.home(); B.inp.walker.home(); await K.raf(30); scene.play(app);
    for (i = 0; i < liveB.length - 3; i++) { await K.raf(1); var sa = byPh[A.inp.ph.toFixed(4)], sb = bPh[B.inp.ph.toFixed(5)]; if (sa && sb && A._follow) { n++; if (Math.abs(place(X, X.tp) - sa.at) > 0.5 || dist(pts(A), sa.p) > 1) offA++; if (Math.abs(place(X, itB) - sb.at) > 0.5 || dist(pts(B), sb.p) > 1) offB++; } }
    scene.stop(app);
    F('passes: played together, both walk where each walked in its own pass', n >= 30 && offA === 0 && offB === 0, { frames: n, firstOff: offA, secondOff: offB });
    scene.hand(app, X.tp.id);
  };
  part.putdown = async function (X, F, plant) {
    await second(X); await fresh(X); var app = AL.app, A = X.A, B = X.B, itB = X.other, letGo0 = sw.letGo, drop0 = app.dropFingers, ov = q('#ov');
    if (plant === 'putdown') sw.letGo = function () { };
    if (plant === 'stale') app.dropFingers = function () { };
    try {
      grabBody(A.inp); A.inp.walker.drag([800, 0]); await K.raf(8);
      var xa = A.inp.walker.x; scene.hand(app, itB.id); await K.raf(60);
      F('putdown: taking another character in hand in the middle of a walk leaves the first where it is: it stops, and no finger holds it', xa > 60 && Math.abs(A.inp.walker.x - xa) < 30 && !A.inp.walker.held && !A.inp.held.body && app.inp === B.inp, { at: Math.round(xa), then: Math.round(A.inp.walker.x), held: A.inp.walker.held, body: !!A.inp.held.body });
      /* with real fingers: one on the first character's body, a second takes the other by ITS body, then the first finger moves */
      await fresh(X); var p1 = fingerOnBody(7);
      if (!p1) { F('putdown: a finger left on the one put down moves nobody', false, 'no point on the body takes the body'); return; }
      await slide(ov, p1, [p1[0] + 30, p1[1]], 7, 4);
      scene.hand(app, itB.id); await K.raf(3);                                    // (what a touch on the other character does first)
      var p2 = fingerOnBody(9); await K.raf(3);
      var before = JSON.stringify([B.inp.walker.tx, B.inp.walker.ty, B.inp.pose]), ax0 = A.inp.walker.tx;
      await slide(ov, [p1[0] + 30, p1[1]], [p1[0] - 120, p1[1] - 40], 7, 6);
      F('putdown: a finger left on the one put down moves nobody', !!p2 && JSON.stringify([B.inp.walker.tx, B.inp.walker.ty, B.inp.pose]) === before && A.inp.walker.tx === ax0, { secondOnBody: !!p2, otherMoved: JSON.stringify([B.inp.walker.tx, B.inp.walker.ty, B.inp.pose]) !== before, firstMoved: A.inp.walker.tx !== ax0 });
      pe(ov, 'pointerup', [p1[0] - 120, p1[1] - 40], 7); if (p2) pe(ov, 'pointerup', p2, 9); await K.raf(2);
    } finally { sw.letGo = letGo0; app.dropFingers = drop0; A.inp.held = {}; B.inp.held = {}; A.inp.walker.release(); B.inp.walker.release(); scene.hand(app, X.tp.id); }
  };
  part.edge = async function (X, F, plant) {
    await fresh(X); var app = AL.app, R = X.R, it = X.tp, ov = q('#ov'), gw = q('#stage').clientWidth, E0 = sw.EDGE;
    if (plant === 'edge') sw.EDGE = 0;
    try {
      var p = fingerOnBody(7); if (!p) { F('edge: held at the edge of the glass, the body walks on out of the picture', false, 'no point on the body takes the body'); return; }
      var at0 = place(X, it); await slide(ov, p, [gw - 5, p[1]], 7, 12); await K.raf(170);
      var out = place(X, it), wait = sw.waiting(R)[it.id], w1 = apart(X);
      F('edge: a finger holding the body at the edge of the glass means keep going: it walks on, out of the picture', wait === 'r' && out > W + 150 && whole(w1), { at: Math.round(out), waiting: wait, worst: say(w1) });
      var mid = [gw * 0.5, p[1]]; await slide(ov, [gw - 5, p[1]], mid, 7, 12); await K.raf(220);
      var want = at0 + (mid[0] - p[0]) / R.V.s, back = place(X, it);
      F('edge: bring the finger back and it comes back to the finger', Math.abs(back - want) < 25 && !sw.waiting(R)[it.id] && whole(apart(X)), { at: Math.round(back), finger: Math.round(want) });
      pe(ov, 'pointerup', mid, 7); await K.raf(3);
    } finally { sw.EDGE = E0; }
  };
  part.tab = async function (X, F, plant) {
    await fresh(X); var app = AL.app, R = X.R, it = X.tp, inp = app.inp, ov = q('#ov'), st = q('#stage'), gw = st.clientWidth, waiting0 = sw.waiting, push0 = sw.push;
    if (plant === 'tab') sw.waiting = function () { return {}; };
    if (plant === 'tabpush') sw.push = function (a, dt) { var g = a.inp && a.inp.walker.g0; if (g) g.tab = false; return push0(a, dt); };
    try {
      grabBody(inp); await walk(0, -6000, 40); letBody(inp); await K.raf(30);
      var el = q('.waittab.l'), sr = st.getBoundingClientRect(), k = st.clientWidth / sr.width, tr = el && el.getBoundingClientRect();
      F('tab: a character that cannot be seen has a tab at that edge of the glass, with its picture and a hover label', !!el && el.tagName === 'BUTTON' && el.title.indexOf(it.name) >= 0 && el.getAttribute('aria-label') === el.title && !!el.querySelector('img') && tr.left - sr.left < 3, { tab: !!el, title: el && el.title });
      if (!el) return;
      var g0 = [(tr.left - sr.left) * k + 8, (tr.top - sr.top + tr.height / 2) * k]; pe(el, 'pointerdown', g0, 11); await K.raf(100);
      var came = place(X, it);
      F('tab: a press on the tab brings it to the edge (it walks in, and is in hand)', came > -160 && came < 260 && inp.walker.held && R.focus === it.id, { at: Math.round(came) });
      var mid = [gw * 0.5, g0[1]]; await slide(ov, g0, mid, 11, 16); await K.raf(160); var under = (mid[0] - R.V.ox) / R.V.s, now = place(X, it);
      pe(ov, 'pointerup', mid, 11); await K.raf(4);
      F('tab: dragged in from the tab it walks in after the finger, and the tab goes once it can be seen', Math.abs(now - under) < 25 && !q('.waittab') && !inp.walker.held && !inp.held.body && whole(apart(X)), { at: Math.round(now), finger: Math.round(under), tabs: document.querySelectorAll('.waittab').length });
    } finally { sw.waiting = waiting0; sw.push = push0; }
  };
  part.stick = async function (X, F, plant) {
    await fresh(X); var app = AL.app, R = X.R, it = X.tp, reach0 = sw.reach, ev = function (el, type, x, y) { el.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, pointerId: 5, clientX: x, clientY: y, button: 0, isPrimary: true })); };
    if (plant === 'stickroom') sw.reach = function () { var b = AL.boundsOf(app.ch.root), z = (b.x1 - b.x0) * 0.7; return [-z, z]; };
    try {
      AL.stick.set(app, true); await K.raf(3); var pad = q('.stickpad'); if (!pad) { F('stick: the joystick walks it out of the picture, to the same limit', false, 'no joystick pad'); return; }
      var pr = pad.getBoundingClientRect(), y = pr.top + pr.height / 2;
      ev(pad, 'pointerdown', pr.right - 2, y); await K.raf(300); await realWait(420); ev(pad, 'pointerup', pr.right - 2, y); await K.raf(40);
      var at = place(X, it);
      F('stick: the joystick walks it out of the picture, to the same limit', sw.waiting(R)[it.id] === 'r' && Math.abs(at - (W + W / 3)) < 4 && whole(apart(X)), { at: Math.round(at), waiting: sw.waiting(R)[it.id] });
    } finally { sw.reach = reach0; AL.stick.release(app); AL.stick.set(app, false); await K.raf(2); }
  };
  part.watch = async function (X, F, plant) {
    await second(X); await fresh(X); var B = X.B, inp = AL.app.inp, at0 = sw.at;
    if (plant === 'watch') sw.at = function (R, it) { return it.x; };
    try {
      X.tp.x = 500; await K.raf(70); var before = B.lk;
      grabBody(inp); await walk(0, 2000, 30); await K.raf(60); letBody(inp); var after = B.lk, where = place(X, X.tp);
      F('watch: the other character watches the one in hand where it IS: its look follows the walk across', before < -0.5 && after > 0.5 && where > 1400, { before: r1(before), after: r1(after), walkerAt: Math.round(where), watcherAt: 1400 });
    } finally { sw.at = at0; X.tp.x = X.x0; }
  };
  part.led = async function (X, F, plant) {
    var app = AL.app, live = await take(X, { to: 820 }), inp = app.inp, L0 = tw.leads, half = Math.round(live.length * 0.6);
    if (plant === 'led') tw.leads = false;
    try {
      inp.walker.home(); await K.raf(80); scene.play(app); await K.raf(half);
      var seen = place(X, X.tp); grabBody(inp); await K.raf(1); var took = place(X, X.tp); letBody(inp); scene.stop(app); await K.raf(4);
      F('led: taking the body half way through a playing take, it goes on from where it is seen (no jump across the stage)', seen > X.x0 + 150 && Math.abs(took - seen) < 40, { seen: Math.round(seen), took: Math.round(took) });
      inp.walker.home(); await K.raf(80); scene.play(app); await K.raf(half); var seen2 = place(X, X.tp); scene.stop(app); await K.raf(25); var stays = place(X, X.tp);
      F('led: stopped half way, it stays where the take had it', Math.abs(stays - seen2) < 40 && whole(apart(X)), { seen: Math.round(seen2), stays: Math.round(stays) });
    } finally { tw.leads = L0; }
  };
  part.keep = async function (X, F, plant) {
    await fresh(X); var app = AL.app, inp = app.inp, K0 = tw.keep, i, low = 1e9, k = X.A.T.k;
    scene.rec(app); await K.raf(75); scene.rec(app); await K.raf(3);              // an older take: it stands where it is
    if (plant === 'handback') tw.keep = false;
    try {
      scene.rec(app); await K.raf(6); grabBody(inp); await walk(0, 400, 14); for (i = 0; i < 4; i++) await K.raf(1); letBody(inp);
      for (i = 0; i < 28; i++) { await K.raf(1); if (i > 2) low = Math.min(low, place(X, X.tp)); }
      scene.rec(app); await K.raf(3);
      F('keep: in a later pass, a body that walked and was let go stays where it was left to the end of the pass (it does not slide back to the older take\'s place)', low > X.x0 + 400 * k - 30, { lowest: Math.round(low), leftAt: Math.round(X.x0 + 400 * k) });
    } finally { tw.keep = K0; }
  };
  part.small = async function (X, F, plant) {
    await fresh(X); var app = AL.app, it = X.tp, near0 = sw.near, b = scene.boxOf(X.R, it);
    it.s = Math.min(1, 84 / ((b[3] - b[1]) * X.R.V.s)); await K.raf(6);            // about 84 px tall on the glass, whatever the window: a character in a scene on a phone
    if (plant === 'bigtips') sw.near = function () { return 1; };
    try {
      var p = fingerOnBody(7), held = !!p && app.inp.walker.held; if (p) { pe(q('#ov'), 'pointermove', [p[0] + 40, p[1]], 7); await K.raf(12); }
      var walked = app.inp.walker.tx > 20; if (p) pe(q('#ov'), 'pointerup', [p[0] + 40, p[1]], 7);
      F('small: a character as small as it is in a scene on a phone can still be taken by the body and walked (its hands and feet leave room for it)', held && walked, { point: !!p, held: held, walked: walked, reach: r1(30 * near0(app)) });
    } finally { sw.near = near0; it.s = X.s0; app.inp.resetPose(); }
  };
  part.empty = async function (X, F, plant) {
    var app = AL.app, step0 = AL.Walker.prototype.step;
    if (app.scene) await scene.close(app, true); await K.raf(4); AL.stages.go(app, 'perform'); await K.raf(10);
    var inp = app.inp, wk = inp.walker, bw = AL.boundsOf(app.ch.root), room = (bw.x1 - bw.x0) * 0.7, ev = function (el, type, x, y) { el.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, pointerId: 5, clientX: x, clientY: y, button: 0, isPrimary: true })); };
    inp.clearTake(); inp.resetPose(); wk.reset(); await K.raf(30);
    if (plant === 'empty') AL.Walker.prototype.step = function (dt, S) { this.lim = [-3000, 3000]; return step0.call(this, dt, S); };
    try {
      AL.stick.set(app, true); await K.raf(3); var pad = q('.stickpad'), pr = pad.getBoundingClientRect(), y = pr.top + pr.height / 2;
      ev(pad, 'pointerdown', pr.right - 2, y); await K.raf(150); await realWait(420); ev(pad, 'pointerup', pr.right - 2, y); await K.raf(30);
      F('empty: the joystick, alone on the plain stage, keeps to the room the view keeps for walking, as before', wk.tx > room * 0.6 && wk.tx <= room + 0.5, { went: Math.round(wk.tx), room: Math.round(room) });
      AL.stick.release(app); AL.stick.set(app, false); await K.raf(2);
      wk.home(); await K.raf(90); inp.record(); await K.raf(4); grabBody(inp); await walk(0, 150, 10); letBody(inp); await K.raf(4); inp.stop();
      wk.home(); await K.raf(90); inp.play(); await K.raf(14); var lead = wk.x, drawn = inp.S.root.x; inp.stop();
      F('empty: alone, a playing take is drawn where it was recorded and leaves the walker where it was left, as before', Math.abs(lead) < 3 && drawn > 40 && !q('.waittab'), { walker: Math.round(lead), drawn: Math.round(drawn) });
      F('empty: alone, the walker has no stage limit', wk.lim == null || plant === 'empty', wk.lim);
    } finally { AL.Walker.prototype.step = step0; AL.stick.release(app); AL.stick.set(app, false); inp.clearTake(); wk.reset(); }
  };

  SW.run = async function (opt) {
    K.sim(true);      // (every count of frames here is a count of 33 ms steps, as in a take: on the page's own clock, 60 a second, they would be half as long. Reload the page afterwards)
    opt = opt || {}; var app = AL.app, X = K.mk(), F = X.F, plant = opt.plant || '', only = opt.only || PARTS;
    var room0 = JSON.stringify(AL.frame.room), keep = { beyond: sw.BEYOND, edge: sw.EDGE, k: tw.keep, l: tw.leads };
    try {
      if (app.scene) await scene.close(app, true);
      X.note.push('Topps: ' + await ownTopps(app)); await K.raf(6);
      var st = app.ch.settings, st0 = { idle: st.idle, blinkAuto: st.blinkAuto, breathe: st.breathe }; st.idle = false; st.blinkAuto = false; st.breathe = false;      // (nothing random in a walk that is compared frame by frame)
      K.stand(app); await K.raf(3); X.base = T.measure(app).gaps; X.jbase = Wk.jointGaps(app); X.jbaseS = await Wk.stanceJoints(app);
      if (only.indexOf('same') >= 0) { AL.stages.go(app, 'perform'); await K.raf(10); await script(false); X.solo = { stand: await script(false), fours: AL.stance.fours(app.ch) ? await script(true) : null }; app.inp.resetPose(); app.inp.walker.reset(); await K.raf(10); }
      await scene.create(app, 'meadow', 'Walk test'); await K.raf(8); bind(X);
      st.idle = st0.idle; st.blinkAuto = st0.blinkAuto; st.breathe = st0.breathe;      // (the scene has its own copy; the character you were on is as it was)
      X.x0 = X.tp.x; X.y0 = X.tp.y; X.s0 = X.tp.s || 1;
      F('0 a scene opens in Perform with the character in hand, and its walker has the stage for its room', !!X.R && X.R.stage === 'perform' && app.inp === X.A.inp && !!X.A.inp.walker.lim && X.A.inp.walker.travels(), { stage: X.R && X.R.stage, lim: X.A.inp.walker.lim });
      for (var i = 0; i < PARTS.length; i++) if (only.indexOf(PARTS[i]) >= 0) { await part[PARTS[i]](X, F, plant); if (scene._err) { F(PARTS[i] + ': nothing thrown in the frame loop', false); scene._err = false; } }
      F('99 nothing thrown and no bad numbers by the end', !scene._err, { err: !!scene._err });
    } catch (e) { X.fails.push({ label: 'THROWN', info: String(e && e.stack || e).slice(0, 700) }); }
    sw.BEYOND = keep.beyond; sw.EDGE = keep.edge; tw.keep = keep.k; tw.leads = keep.l;
    try { var r0 = JSON.parse(room0), RM = AL.frame.room; for (var rk in r0) RM[rk] = r0[rk]; AL.frame.keep(); } catch (e6) { }      // (the room's own settings, as they were)
    try { if (AL.app.scene) await scene.close(AL.app, true); } catch (e2) { }
    try { var mine = (await scene.list(AL.app)).filter(function (e) { return e.name === 'Walk test'; }); for (var m = 0; m < mine.length; m++) await scene.forget(AL.app, mine[m].uid); } catch (e4) { }      // (the test tidies its own scenes off the shelf)
    try { localStorage.removeItem('al.scene'); } catch (e3) { }
    return { n: X.n, fails: X.fails, note: X.note };
  };
  SW.WANT = { short: ['far', /^far: it walks right out/], nolimit: ['far', /^far: \.\.\.and stops/], floor: ['floor', /^floor: walking across/], stride: ['same', /^same: in a scene/], home: ['home', /^home: "Back/], rest: ['home', /^home: held still/],
    replay: ['replay', /^replay: played back/], hands: ['fours', /^fours: played back/], leg: ['fours', /^fours: walking on all fours/], shape: ['shape', /^shape: /], shot: ['shot', /^shot: switching/], turn: ['shot', /^shot: switching/], close: ['shot', /^shot: a close-up/],
    reopen: ['reopen', /^reopen: /], passes: ['passes', /^passes: while the second/], putdown: ['putdown', /^putdown: taking another/], stale: ['putdown', /^putdown: a finger left/], edge: ['edge', /^edge: a finger holding/],
    tab: ['tab', /^tab: a character that cannot be seen/], tabpush: ['tab', /^tab: a press on the tab/], stickroom: ['stick', /^stick: /], watch: ['watch', /^watch: /], led: ['led', /^led: taking the body/], handback: ['keep', /^keep: /],
    bigtips: ['small', /^small: /], empty: ['empty', /^empty: the joystick/] };
  /** Every planted fault must be caught by the check it is for. only = a list of fault names, to run some. */
  SW.proof = async function (only) {
    var out = {}, want = SW.WANT;
    for (var k in want) { if (only && only.indexOf(k) < 0) continue; var r = await SW.run({ plant: k, only: [want[k][0]] }); out[k] = { caught: r.fails.some(function (f) { return want[k][1].test(f.label); }), fails: r.fails.map(function (f) { return f.label.slice(0, 70); }).slice(0, 4) }; }
    return out;
  };
})(typeof self !== 'undefined' ? self : this);
