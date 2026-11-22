/* AvatarLab: THE SCENE, DRAWN. The frame loop while a scene is open, and fingers on its stage.
 *
 * Every frame: the clock moves, the camera finds its shot, every performer is solved by its own engine, and the stage is
 * drawn back to front: backdrops, then everything that stands (sorted by where its feet are: higher on the floor = further
 * back = earlier), then foregrounds. One GL surface; nothing here is a second renderer.
 *
 * The character in hand goes through the SAME path as when it is alone (app.inp, app.solver, app.out, app.view), so every
 * finger, the voice, the face camera, walking and the take lanes work in a scene with no change to them.
 *
 * SET UP and ARRANGE (the scene in hand, held still)  the whole stage is shown, the frame drawn on it when it is on. Tap a
 *          thing to take it. In Arrange drag it (up = further back) and the round handle resizes it. Two taps on a character
 *          puts that character in hand, alone on the plain stage.
 * MOTION and PERFORM (alive)  the FRAME with room round it on every side, so a character can go part-way out. Touch a
 *          character to take it in hand. Drag the empty stage, pinch or roll the wheel to frame a shot by hand (that needs
 *          the frame, so it turns the frame on). In Motion a touch on an object takes it, for its wind and loop.
 *
 * Phone: pointer capture becomes pointerInput on the stage Box; the overlay canvas is a Compose Canvas.
 */
(function (root) {
  'use strict';
  var AL = root.AL, scene = AL.scene, app = AL.app, W = scene.W, H = scene.H;
  function $(id) { return document.getElementById(id); }
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  var QI = new Uint16Array([0, 1, 2, 1, 3, 2]);

  function imgOf(it, t) { var id = it.frames && it.frames.length ? it.frames[Math.floor(t * (it.fps || 8)) % it.frames.length] : it.img, im = id ? AL.img.get(id) : null; return im ? { id: id, im: im } : null; }
  /* a backdrop or foreground: covers the stage whatever its own shape, with a little spare so the camera never finds its edge */
  function paneQuad(it, im) {
    var k = Math.max(W / im.w, H / im.h) * 1.06 * (it.s || 1), w = im.w * k, h = im.h * k, x0 = W / 2 + (it.x || 0) - w / 2, y0 = H / 2 + (it.y || 0) - h / 2;
    return { v: new Float32Array([x0, y0, x0 + w, y0, x0, y0 + h, x0 + w, y0 + h]), uv: new Float32Array(it.flip ? [1, 0, 0, 0, 1, 1, 0, 1] : [0, 0, 1, 0, 0, 1, 1, 1]), idx: QI, box: [x0, y0, x0 + w, y0 + h] };
  }
  /* that quad cut to a rectangle on the glass (V = the view it is drawn with): what is cut away is not drawn at all */
  function clipQuad(g, clip, V, flip) {
    var b = g.box, cx0 = (clip.x - V.ox) / V.s, cy0 = (clip.y - V.oy) / V.s, cx1 = (clip.x + clip.w - V.ox) / V.s, cy1 = (clip.y + clip.h - V.oy) / V.s;
    var x0 = Math.max(b[0], cx0), y0 = Math.max(b[1], cy0), x1 = Math.min(b[2], cx1), y1 = Math.min(b[3], cy1); if (x1 <= x0 || y1 <= y0) return null;
    function u(x) { var t = (x - b[0]) / (b[2] - b[0]); return flip ? 1 - t : t; } function v(y) { return (y - b[1]) / (b[3] - b[1]); }
    return { v: new Float32Array([x0, y0, x1, y0, x0, y1, x1, y1]), uv: new Float32Array([u(x0), v(y0), u(x1), v(y0), u(x0), v(y1), u(x1), v(y1)]), idx: QI, box: b };
  }
  /* an object: stands on its bottom edge at (x, y); in the wind its top leans and the foot stays put */
  function objQuad(d, it, im, t) {
    var k = (it.s || 1) * scene.depth(d, it.y), w = im.w * k, h = im.h * k, n = it.sway ? 6 : 1, v = new Float32Array((n + 1) * 4), uv = new Float32Array((n + 1) * 4), idx = new Uint16Array(n * 6), r;
    var sw = it.sway ? (Math.sin(t * 1.6 + it.x * 0.013) * 0.6 + Math.sin(t * 2.9 + it.x * 0.021) * 0.3) * it.sway * (d.wind == null ? 0.5 : d.wind) * 0.14 * h : 0;
    for (r = 0; r <= n; r++) { var f = r / n, up = 1 - f, dx = sw * up * up, y = it.y - h * up, o = r * 4; v[o] = it.x - w / 2 + dx; v[o + 1] = y; v[o + 2] = it.x + w / 2 + dx; v[o + 3] = y; uv[o] = it.flip ? 1 : 0; uv[o + 1] = f; uv[o + 2] = it.flip ? 0 : 1; uv[o + 3] = f; }
    for (r = 0; r < n; r++) { var a = r * 2, q = r * 6; idx[q] = a; idx[q + 1] = a + 1; idx[q + 2] = a + 2; idx[q + 3] = a + 1; idx[q + 4] = a + 3; idx[q + 5] = a + 2; }
    return { v: v, uv: uv, idx: idx, box: [it.x - w / 2, it.y - h, it.x + w / 2, it.y] };
  }
  function viewOf(R, cam) { var s = R.f * cam.z; return { s: s, ox: R.cx - cam.x * s, oy: R.cy - cam.y * s }; }
  function px(v, dpr) { return { s: v.s * dpr, ox: v.ox * dpr, oy: v.oy * dpr }; }
  /* the box a performer fills on the stage right now (stage units) */
  function castBox(a) {
    var x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9, T = a.T; if (!a.out || !T) return null;
    a.out.items.forEach(function (it) { if (it.ghost) return; for (var i = 0; i < it.v.length; i += 2) { var x = it.v[i], y = it.v[i + 1]; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; } });
    return x0 > x1 ? null : [x0 * T.k + T.tx, y0 * T.k + T.ty, x1 * T.k + T.tx, y1 * T.k + T.ty];
  }
  scene.boxOf = function (R, it) { if (it.kind === 'cast') { var a = R.actors[it.id]; return a ? castBox(a) : null; } return it._box || null; };

  scene.draw = function (app, ts) {
    var R = app.scene, d = R.data, dt = clamp((ts - (app._ts || ts)) / 1000, 0.001, 0.05); app._ts = ts;
    var st = $('stage'), gl = $('gl'), ov = $('ov'), dpr = Math.min(2.5, root.devicePixelRatio || 1), w = st.clientWidth, hh = st.clientHeight; if (!w || !hh) return;
    if (gl.width !== Math.round(w * dpr) || gl.height !== Math.round(hh * dpr)) { gl.width = ov.width = Math.round(w * dpr); gl.height = ov.height = Math.round(hh * dpr); }
    R.t += dt; var live = R.stage === 'perform' || R.stage === 'motion', G = app.G, fr = scene.frame(d), camOn = live && AL.frame.on();
    var fa = R.focus ? R.actors[R.focus] || null : null; if (fa && app.inp === fa.inp) { fa.ch = app.ch; fa.solver = app.solver; }
    if (live) scene.tick(app, dt);

    /* where the stage sits on the glass. Alive: the FRAME, with room round it on every side (it stays put whether the frame line
       is shown or not, so turning the frame on and off moves nothing). Held still: the whole stage, the frame drawn on it. */
    var A = AL.frame.area(app);
    if (live) { var F0 = AL.frame.rect(app, fr.w / fr.h, true); R.f = F0.w / fr.w; R.cx = F0.x + F0.w / 2; R.cy = F0.y + F0.h / 2; }      // (the strip pushes it aside as Studio's transcript does; never smaller)
    else { R.f = Math.min(A.w / (W + 150), A.h / (H + 110)); R.cx = A.x + A.w / 2; R.cy = A.y + A.h / 2; }
    var cam = camOn ? scene.camNow(app, fr, dt) : (R.cam = { x: W / 2, y: H / 2, z: 1 }), V = R.V = viewOf(R, cam);
    R.fr = live ? F0 : { x: V.ox + fr.x * V.s, y: V.oy + fr.y * V.s, w: fr.w * V.s, h: fr.h * V.s };
    /* the picture area: the frame when it is on and the scene is alive, otherwise the stage itself. The set is cut to it, and round it
       is Video Studio's canvas texture; whatever stands part-way out is still drawn over that. */
    R.clip = live && AL.frame.on() ? R.fr : { x: V.ox, y: V.oy, w: W * V.s, h: H * V.s }; AL.frame.desk(R.clip);

    var L = layers(d), stand = L.stand;

    /* every performer, solved by its own engine */
    var lead = fa ? scene.item(R, R.focus) : null;
    stand.forEach(function (it) {
      if (it.kind !== 'cast') return; var a = R.actors[it.id]; if (!a) return;
      var T = a.T = scene.place(d, it, a.ch), S; a.view = { s: V.s * T.k, ox: V.ox + V.s * T.tx, oy: V.oy + V.s * T.ty };
      if (a === fa) { app.view = { s: a.view.s, ox: a.view.ox, oy: a.view.oy }; app.vt = null; app.userView = true; }
      if (live && a === fa) { S = app._S = app.inp.step(dt); a.out = app.out = app.solver.eval(S, { live: true, dt: dt, force: app.lockForce || null, show: null, onion: null, lift: false }); }
      else if (live) {
        S = a.inp.step(dt);
        if (lead && !a._follow) { var dir = clamp((lead.x - it.x) / 420, -1, 1) * (S.flip ? -1 : 1); a.lk += (dir - a.lk) * (1 - Math.exp(-3 * dt)); S.lookX = clamp((S.lookX || 0) + a.lk * 0.85, -1, 1); S.turnX = clamp((S.turnX || 0) + a.lk * 0.4, -1, 1); }      // nobody is driving this one: it watches whoever is in hand
        a.out = a.solver.eval(S, { live: true, dt: dt, force: null, show: null });
      } else { S = a.rest || (a.rest = AL.restSignals()); a.out = a.solver.eval(S, { live: false, dt: dt, force: null, show: null, lift: false }); if (a === fa) app.out = a.out; }
    });

    /* back to front */
    G.keep = false; G.draw([], px(V, dpr), null, null); G.keep = true;
    try { paint(R, G, cam, dpr, L, null); } finally { G.keep = false; }

    var g2 = ov.getContext('2d');
    if (live && fa && fa.out) app.drawOverlay(g2, dpr, true, fa.out); else { g2.setTransform(dpr, 0, 0, dpr, 0, 0); g2.clearRect(0, 0, w, hh); }
    scene.overlay(app, g2, w, hh, live);
    for (var i = 0; i < app.hooks.length; i++) app.hooks[i](dt, live && !!fa);
  };
  /* the scene's things in drawing order: backdrops, what stands (higher on the floor = further back = earlier), foregrounds */
  function layers(d) {
    var backs = [], stand = [], fronts = [];
    d.items.forEach(function (it, i) { it._i = i; (it.kind === 'back' ? backs : it.kind === 'front' ? fronts : stand).push(it); });
    stand.sort(function (a, b) { return a.y - b.y || a._i - b._i; });
    return { backs: backs, stand: stand, fronts: fronts };
  }
  /* Draw the scene, back to front, as the camera sees it through R.f / R.cx / R.cy. skip = { set: true, <item id>: true } leaves
     things out (export). The performers are drawn as they were solved this frame: nothing here moves anyone. */
  function paint(R, G, cam, dpr, L, skip) {
    var d = R.data, V = viewOf(R, cam), made = L.backs.filter(function (q) { return q.made; })[0], hz = made && scene.SETS[made.made] ? scene.SETS[made.made].haze : null, hk = d.haze == null ? 0.5 : d.haze;
    function pane(it, par) {
      var q = imgOf(it, R.t); if (!q) return;
      var c = { x: W / 2 + (cam.x - W / 2) * (1 - par), y: H / 2 + (cam.y - H / 2) * (1 - par), z: 1 + (cam.z - 1) * (1 - par) }, g = paneQuad(it, q.im), V2 = viewOf(R, c);
      it._box = g.box; if (R.clip && !(skip && skip.noClip)) { g = clipQuad(g, R.clip, V2, !!it.flip); if (!g) return; }      // a backdrop stops at the picture's edge: outside it is Studio's canvas
      g.img = q.id; g.a = 1; G.draw([g], px(V2, dpr), null, null);
    }
    if (!(skip && skip.set)) L.backs.forEach(function (it) { pane(it, it.par == null ? 0.22 : it.par); });
    L.stand.forEach(function (it) {
      if (skip && (skip[it.id] || (skip.set && it.kind !== 'cast'))) return;
      var far = clamp(1 - (it.y / H - d.floor.far) / (d.floor.near - d.floor.far), 0, 1) * hk * 0.22, tint = hz && far > 0.01 ? [hz[0], hz[1], hz[2], far] : null;
      if (it.kind === 'cast') { var a = R.actors[it.id], T = a && a.T; if (!a || !a.out || !T) return; G.draw(skip ? a.out.items.filter(function (q) { return !q.ghost; }) : a.out.items, px({ s: V.s * T.k, ox: V.ox + V.s * T.tx, oy: V.oy + V.s * T.ty }, dpr), tint ? function (q) { return q.ghost ? null : tint; } : null, a.out.colour); }
      else { var q = imgOf(it, R.t); if (!q) return; var g = objQuad(d, it, q.im, R.t); g.img = q.id; g.a = 1; it._box = g.box; G.draw([g], px(V, dpr), tint ? function () { return tint; } : null, null); }
    });
    if (!(skip && skip.set)) L.fronts.forEach(function (it) { pane(it, it.par == null ? -0.15 : it.par); });
  }
  /** One picture of the shot, w x h pixels, into its own canvas: what export saves. skip as in paint(). Returns a canvas or null. */
  scene.picture = function (app, w, h, skip) {
    var R = app.scene; if (!R || !R.cam) return null;
    var X = AL.frame.gl(w, h), G = X.G, fr = scene.frame(R.data), keep = { f: R.f, cx: R.cx, cy: R.cy, t: R.t, clip: R.clip };
    R.f = w / fr.w; R.cx = w / 2; R.cy = h / 2; R.clip = null;                // (the picture is the frame already: nothing to cut)
    try { G.keep = false; G.draw([], { s: 1, ox: 0, oy: 0 }, null, null); G.keep = true; paint(R, G, AL.frame.on() ? R.cam : { x: W / 2, y: H / 2, z: 1 }, 1, layers(R.data), skip || {}); }
    finally { G.keep = false; R.f = keep.f; R.cx = keep.cx; R.cy = keep.cy; R.t = keep.t; R.clip = keep.clip; }
    var out = document.createElement('canvas'); out.width = w; out.height = h; out.getContext('2d').drawImage(X.c, 0, 0);
    return out;
  };

  /* what is drawn over the picture: the stage's edge, the frame (when it is on), the margins, the thing in hand */
  scene.overlay = function (app, g, w, hh, live) {
    var R = app.scene, d = R.data, F = R.fr, V = R.V;
    g.save();
    if (!AL.frame.on()) AL.frame.dim(g, w, hh, { x: V.ox, y: V.oy, w: W * V.s, h: H * V.s }, 'rgba(9,9,11,.24)');      // beyond the stage: Studio's canvas, a little darker
    else AL.frame.paint(g, w, hh, F, d.safe, d.shape + (live ? '  ' + scene.shotName(app, R.shot).toUpperCase() : ''));
    if (live) {
      var run = scene.running(app);
      if (run === 'rec') { g.fillStyle = '#ef4444'; g.beginPath(); g.arc(F.x + F.w - 12, F.y + 12, 5, 0, 6.3); g.fill(); }
      var fa = R.actors[R.focus], fi = fa && scene.item(R, R.focus);
      if (fa && fi && scene.cast(R).length > 1) {                  // who is in hand: a cyan mark at their feet, wherever they have walked to
        var fb = castBox(fa), fx = V.ox + (fb ? (fb[0] + fb[2]) / 2 : fi.x) * V.s, fy = V.oy + (fb ? Math.max(fi.y, fb[3] - 4) : fi.y) * V.s, rw = Math.max(16, 70 * fa.T.k * V.s);
        g.strokeStyle = 'rgba(34,211,238,.9)'; g.lineWidth = 2; g.beginPath(); g.ellipse(fx, fy, rw, rw * 0.22, 0, 0, 6.3); g.stroke();
      }
    }
    R.handle = null; g.font = '600 10px ui-monospace, monospace'; g.textBaseline = 'top';
    if (!live || (R.stage === 'motion' && R.sel && (scene.item(R, R.sel) || {}).kind === 'obj')) {
      if (R.drag && R.drag.kind === 'move' && R.drag.stands) {     // the floor, while something is being walked across it
        g.setLineDash([3, 5]); g.strokeStyle = 'rgba(255,255,255,.35)'; [d.floor.far, d.floor.near].forEach(function (q) { var y = V.oy + q * H * V.s; g.beginPath(); g.moveTo(V.ox, y); g.lineTo(V.ox + W * V.s, y); g.stroke(); }); g.setLineDash([]);
        g.fillStyle = 'rgba(255,255,255,.55)'; g.fillText('BACK', V.ox + 4, V.oy + d.floor.far * H * V.s - 13); g.fillText('FRONT', V.ox + 4, V.oy + d.floor.near * H * V.s + 4);
      }
      var it = R.sel ? scene.item(R, R.sel) : null, b = it ? scene.boxOf(R, it) : null;
      if (b) {
        var x0 = V.ox + b[0] * V.s, y0 = V.oy + b[1] * V.s, x1 = V.ox + b[2] * V.s, y1 = V.oy + b[3] * V.s;
        if (it.kind === 'back' || it.kind === 'front') { x0 = Math.max(x0, 3); y0 = Math.max(y0, 3); x1 = Math.min(x1, w - 3); y1 = Math.min(y1, hh - 3); }
        g.strokeStyle = '#22d3ee'; g.lineWidth = 1.5; g.setLineDash([6, 4]); g.strokeRect(x0, y0, x1 - x0, y1 - y0); g.setLineDash([]);
        if (R.stage === 'fit') { R.handle = { x: x1, y: y0 }; g.fillStyle = '#22d3ee'; g.beginPath(); g.arc(x1, y0, 8, 0, 6.3); g.fill(); g.strokeStyle = '#0b0b0d'; g.lineWidth = 1.5; g.beginPath(); g.moveTo(x1 - 3, y0 + 3); g.lineTo(x1 + 3, y0 - 3); g.moveTo(x1 + 3, y0 - 3); g.lineTo(x1 - 0.5, y0 - 3); g.moveTo(x1 + 3, y0 - 3); g.lineTo(x1 + 3, y0 + 0.5); g.stroke(); }      // (resizing is Arrange's)
        if (it.kind === 'obj' || it.kind === 'cast') { g.fillStyle = '#22d3ee'; g.beginPath(); g.arc(V.ox + it.x * V.s, V.oy + it.y * V.s, 3.5, 0, 6.3); g.fill(); }
      }
    }
    g.restore();
  };

  /** A small picture of the shot, for the scene's card. */
  scene.thumb = function (app, px0) {
    var R = app.scene, gl = $('gl'), st = $('stage'), F = R && R.fr; if (!F || !gl.width || !st.clientWidth) return null;
    try {
      var k = gl.width / st.clientWidth, c = document.createElement('canvas'); c.width = px0; c.height = Math.max(8, Math.round(px0 * F.h / F.w)); var g = c.getContext('2d');
      g.fillStyle = '#111114'; g.fillRect(0, 0, c.width, c.height); g.drawImage(gl, F.x * k, F.y * k, F.w * k, F.h * k, 0, 0, c.width, c.height);
      return c.toDataURL('image/webp', 0.8);
    } catch (e) { return null; }
  };

  /* ── the frame loop is the scene's while one is open (and not stepped into a character) ── */
  var frame0 = app.frame;
  app.frame = function (ts) {
    if (!scene.on(app)) return frame0(ts);
    requestAnimationFrame(app.frame);
    try { scene.draw(app, ts); } catch (e) { if (!scene._err) { scene._err = true; console.error(e); } }
  };

  /* ── fingers ── */
  function hitCast(a, p) { return !!(a && a.out && a.view && AL.hit(a.out, (p.x - a.view.ox) / a.view.s, (p.y - a.view.oy) / a.view.s)); }
  /** The thing under a point on the glass: the nearest one first. castOnly = performers only. */
  scene.pick = function (R, p, castOnly) {
    var V = R.V, wx = (p.x - V.ox) / V.s, wy = (p.y - V.oy) / V.s, l = R.data.items.filter(function (q) { return q.kind === 'cast' || (!castOnly && q.kind === 'obj'); });
    l.sort(function (a, b) { return b.y - a.y || b._i - a._i; });
    for (var i = 0; i < l.length; i++) { var it = l[i]; if (it.kind === 'cast') { if (hitCast(R.actors[it.id], p)) return it; } else { var b = it._box; if (b && wx >= b[0] && wx <= b[2] && wy >= b[1] && wy <= b[3]) return it; } }
    return null;
  };
  function snap(R, label) { R.hist.past.push({ s: JSON.stringify(R.data, function (k, v) { return k.charAt(0) === '_' ? undefined : v; }), l: label }); if (R.hist.past.length > 60) R.hist.past.shift(); R.hist.future.length = 0; app.syncUndo(); }
  scene.snap = function (app, label) { snap(app.scene, label); };
  function wire() {
    var st = $('stage'), ptr = {}, pinch = null;
    function pos(e) { var r = st.getBoundingClientRect(), k = st.clientWidth / r.width; return { x: (e.clientX - r.left) * k, y: (e.clientY - r.top) * k }; }
    function n() { return Object.keys(ptr).length; }
    function live(R) { return R.stage === 'perform' || R.stage === 'motion'; }
    /* framing by hand is a shot, and shots need the frame: the first move turns it on */
    function frameOn() { if (AL.frame.on()) return; AL.frame.set(app, true); app.toast('The frame is on: you are framing the shot by hand.'); }
    st.addEventListener('pointerdown', function (e) {
      if (!scene.on(app) || (e.target.closest && e.target.closest('button,#strip,#roombtns'))) return;
      var R = app.scene, p = pos(e), it;
      if (live(R)) {
        var fa = R.actors[R.focus];
        if (fa && hitCast(fa, p)) return;                          // on the one in hand: theirs, handled as always
        it = scene.pick(R, p, true);
        if (it && it.id !== R.focus) { scene.hand(app, it.id); return; }      // touched someone else: they are in hand, and this same touch goes on to move them
        if (R.stage === 'motion') { var ob = scene.pick(R, p, false); if (ob && ob.kind === 'obj') { e.stopPropagation(); if (R.sel !== ob.id) { R.sel = ob.id; app.refreshPanel(true); } return; } }      // (Motion: an object is taken for its wind and its loop)
        e.stopPropagation(); try { st.setPointerCapture(e.pointerId); } catch (x) { }
        ptr[e.pointerId] = p; if (n() === 2) { var ks = Object.keys(ptr); pinch = { d: Math.hypot(ptr[ks[0]].x - ptr[ks[1]].x, ptr[ks[0]].y - ptr[ks[1]].y) }; R.drag = null; } else R.drag = { kind: 'cam', id: e.pointerId, p0: p, moved: false };
        return;
      }
      e.stopPropagation(); try { st.setPointerCapture(e.pointerId); } catch (x) { }
      var sel = R.sel ? scene.item(R, R.sel) : null;
      if (R.stage === 'fit' && sel && R.handle && Math.hypot(p.x - R.handle.x, p.y - R.handle.y) < 18) {
        var b = scene.boxOf(R, sel) || [0, 0, 1, 1], ax = sel.kind === 'obj' || sel.kind === 'cast' ? R.V.ox + sel.x * R.V.s : R.V.ox + (b[0] + b[2]) / 2 * R.V.s, ay = sel.kind === 'obj' || sel.kind === 'cast' ? R.V.oy + sel.y * R.V.s : R.V.oy + (b[1] + b[3]) / 2 * R.V.s;
        R.drag = { kind: 'size', id: e.pointerId, it: sel.id, s0: sel.s || 1, d0: Math.max(8, Math.hypot(p.x - ax, p.y - ay)), ax: ax, ay: ay, moved: false }; return;
      }
      it = scene.pick(R, p) || (sel && (sel.kind === 'back' || sel.kind === 'front') ? sel : null);
      if (!it) { if (R.sel) { R.sel = null; app.refreshPanel(); } return; }
      if (R.sel !== it.id) { R.sel = it.id; app.refreshPanel(); }
      if (R.stage === 'fit') R.drag = { kind: 'move', id: e.pointerId, it: it.id, p0: p, x0: it.x || 0, y0: it.y || 0, stands: it.kind === 'obj' || it.kind === 'cast', moved: false };      // (Set up holds still: it only takes the thing)
    }, true);
    st.addEventListener('pointermove', function (e) {
      if (!scene.on(app)) return; var R = app.scene, D = R.drag, p;
      if (pinch && ptr[e.pointerId]) { e.stopPropagation(); ptr[e.pointerId] = pos(e); var ks = Object.keys(ptr); if (ks.length >= 2) { var dd = Math.hypot(ptr[ks[0]].x - ptr[ks[1]].x, ptr[ks[0]].y - ptr[ks[1]].y), f = dd / (pinch.d || 1); pinch.d = dd; frameOn(); scene.freeCam(app, function (c) { c.z *= f; }); } return; }
      if (!D || D.id !== e.pointerId) return;
      e.stopPropagation(); p = pos(e);
      if (D.kind === 'cam') { if (!D.moved && Math.hypot(p.x - D.p0.x, p.y - D.p0.y) < 5) return; D.moved = true; frameOn(); var dx = p.x - D.p0.x, dy = p.y - D.p0.y; D.p0 = p; scene.freeCam(app, function (c) { c.x -= dx / (R.f * c.z); c.y -= dy / (R.f * c.z); }); return; }
      var it = scene.item(R, D.it); if (!it) return;
      if (!D.moved) { if (D.kind === 'move' && Math.hypot(p.x - D.p0.x, p.y - D.p0.y) < 4) return; D.moved = true; snap(R, (D.kind === 'size' ? 'Resize ' : 'Move ') + it.name); }
      if (D.kind === 'size') it.s = Math.round(clamp(D.s0 * Math.hypot(p.x - D.ax, p.y - D.ay) / D.d0, 0.05, 8) * 1000) / 1000;
      else { it.x = Math.round(D.x0 + (p.x - D.p0.x) / R.V.s); it.y = Math.round(D.y0 + (p.y - D.p0.y) / R.V.s); if (D.stands) { it.x = clamp(it.x, -500, W + 500); it.y = clamp(it.y, R.data.floor.far * H - 70, H * 1.14); } }
    }, true);
    function up(e) {
      if (!scene.on(app)) return; var R = app.scene, D = R.drag;
      if (ptr[e.pointerId]) { delete ptr[e.pointerId]; if (n() < 2) pinch = null; e.stopPropagation(); }
      if (!D || D.id !== e.pointerId) return;
      e.stopPropagation(); R.drag = null;
      if (D.moved && D.kind !== 'cam') { scene.touch(app); app.refreshPanel(true); }
    }
    st.addEventListener('pointerup', up, true); st.addEventListener('pointercancel', up, true);
    st.addEventListener('wheel', function (e) {
      if (!scene.on(app)) return; e.preventDefault(); e.stopPropagation(); var R = app.scene, f = Math.exp(-e.deltaY * 0.0015);
      if (live(R)) { frameOn(); scene.freeCam(app, function (c) { c.z *= f; }); }
      else if (R.stage === 'fit' && R.sel) { var it = scene.item(R, R.sel); if (!it) return; if (!R._wh || R.t - R._wh > 0.8) snap(R, 'Resize ' + it.name); R._wh = R.t; it.s = Math.round(clamp((it.s || 1) * f, 0.05, 8) * 1000) / 1000; scene.touch(app); }
    }, { passive: false, capture: true });
    st.addEventListener('dblclick', function (e) {
      if (!scene.on(app)) return; var R = app.scene, p = pos(e);
      if (live(R)) { var fa = R.actors[R.focus]; if (!(fa && hitCast(fa, p))) e.stopPropagation(); return; }
      e.stopPropagation(); var it = scene.pick(R, p, true); if (it) scene.diveIn(app, it.id, R.stage);      // two taps on a character: it is in hand, alone, on this stage
    }, true);
  }
  wire();
})(typeof self !== 'undefined' ? self : this);
