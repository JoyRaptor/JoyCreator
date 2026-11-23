/* AvatarLab: face pins (job FACE-1; the owner's idea, 11-22).
 * Tap a point on your face map, then tap the character where that point should pull: from then on that pin follows the
 * point's movement from your neutral face, with the head's turn, tilt and distance taken out (al-face.js points), so a
 * smile pulls the drawing's mouth corner and turning your head turns the head instead of dragging every pin.
 *
 * Built on what the room already has. A face pin is two SHAPES on the part (one across, one up and down), each driven by
 * the face point's signal ('pt.<mesh index>.x' / '.y'), so it adds on top of everything else that moves that pin (the
 * head's own movement, other shapes) and its strength is simply the shapes' amount. The signals ride the take's channels,
 * so a pass records them and a replay plays them like any other lane ("Buttons, wind, pins" on the strip).
 * Data: ch.facePins = [{ id, point, node, pin, amt, made }] (saved and undoable with the character).
 * Phone: com.fadcam.ui.faditor.avatar.face.FacePoints and FaceCapture.watchPoints (FACE-1 card).
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h, M = AL.M, fp = AL.facepins = {};
  var CYAN = '#22d3ee', GREY = '#71717a', FAINT = '#3f3f46', INK = '#e4e4e7';
  /** A new pin starts at twice the face's own movement: at 1x the owner could not see it pull (11-22). */
  var START = 2;

  fp.list = function (ch) { return ch.facePins || []; };
  /** The face points the character's pins follow (each once). */
  fp.points = function (ch) {
    var seen = {}, out = [];
    fp.list(ch).forEach(function (q) { if (!seen[q.point]) { seen[q.point] = 1; out.push(q.point); } });
    return out;
  };
  /** A point's name in the owner's words ("your right mouth corner", "point 212"). */
  fp.nameOf = function (pt) {
    var N = (AL.face && AL.face.NAMED) || [];
    for (var i = 0; i < N.length; i++) if (N[i][0] === pt) return (N[i][1] > 0 ? 'your right ' : N[i][1] < 0 ? 'your left ' : 'your ') + N[i][2];
    return 'face point ' + pt;
  };

  /**
   * Each frame, from al-inputs.js step (after the face camera, before the take): the stage is told which points to put
   * out, and their movement goes into the signals, eased like the mouth. With no face (or the camera off) the pins ease
   * back to rest.
   */
  fp.feed = function (inp, face, dt) {
    var ch = inp.app.ch, pts = fp.points(ch), S = inp.S, st = inp.cam && inp.cam._faceStage, k;
    if (st && st.watchPoints) st.watchPoints(pts);
    var want = {}, sm = inp.smPins || (inp.smPins = {}), a = 1 - Math.exp(-30 * (dt || 0));
    pts.forEach(function (i) {
      var kx = 'pt.' + i + '.x', ky = 'pt.' + i + '.y';
      want[kx] = face && face[kx] != null ? face[kx] : 0;
      want[ky] = face && face[ky] != null ? face[ky] : 0;
    });
    for (k in want) { var cur = sm[k] == null ? want[k] : sm[k]; cur += (want[k] - cur) * a; sm[k] = cur; S[k] = cur; }
    for (k in S) if (k.slice(0, 3) === 'pt.' && !(k in want)) { delete S[k]; delete sm[k]; }   // a removed pin lets go
    fp.expressions(inp, face, dt);
  };

  /**
   * Your face's expressions as signals any part can follow through Links and Shapes (the owner, 11-22: "puffing out my
   * cheek to puff out an animal's stomach"; earlier: "eyes wide, more fire"). Named face.<name>; offered in al-cards.js.
   */
  fp.EXPR = ['cheekPuff', 'smile', 'jaw', 'mouthWide', 'pucker', 'wide', 'squint', 'browInner', 'frown', 'sneer'];
  function exprOf(f, k) {
    if (k === 'wide') return Math.max(+f.wideL || 0, +f.wideR || 0);
    if (k === 'squint') return Math.max(+f.squintL || 0, +f.squintR || 0);
    if (k === 'frown') return ((+f.frownL || 0) + (+f.frownR || 0)) / 2;
    return +f[k] || 0;
  }
  fp.expressions = function (inp, face, dt) {
    var S = inp.S, sm = inp.smExpr || (inp.smExpr = {}), a = 1 - Math.exp(-20 * (dt || 0));
    fp.EXPR.forEach(function (n) { var k = 'face.' + n, v = face ? exprOf(face, n) : 0, cur = sm[k] == null ? v : sm[k]; cur += (v - cur) * a; sm[k] = cur; S[k] = cur; });
  };

  /** Where you build and where it moves (Set up and Motion): a part's card offers Face pins. Any part but an arm or a leg. */
  fp.section = function (app, pb, n) {
    if (AL.intake && AL.intake.hosted) return;
    if (n && AL.fit && AL.fit.can && !AL.fit.can(app, n)) return;
    var ui = app.ui, mine = n ? fp.list(app.ch).filter(function (q) { return q.node === n.id; }).length : 0;
    pb.appendChild(ui.lab('Your face', 'setup motion'));
    pb.appendChild(ui.row([fp.chip(app)]));
    pb.appendChild(ui.note(mine ? mine + ' face pin' + (mine > 1 ? 's' : '') + ' pull this part. Tap <b>Face pins</b> to add, try or change them.'
      : 'Pin a point of your face (a mouth corner, a cheek, a brow) to any spot on this part: it follows that point. Or let it follow an expression (cheeks puffed, a smile, wide eyes) in its <b>Links</b> or <b>Shapes</b>.'));
  };

  /** The Perform chip that opens the setup (shown while the face camera is on, or once there are face pins). */
  fp.chip = function (app) {
    var n = fp.list(app.ch).length;
    return app.ui.chip(n ? 'Face pins: ' + n : 'Face pins', { icon: 'pin',
      title: 'Pin a point of your face to the character: tap the point on your face map, then tap where it should pull. A smile then pulls the mouth corner.',
      fn: function () { fp.open(app); } });
  };

  function calibration(app) {
    var st = app.inp.cam && app.inp.cam._faceStage;
    return st ? st.cal : (AL.face ? AL.face.load() : null);
  }

  /*
   * THE FACE PINS SCREEN (the owner's design, 11-22, from his mockup: "Build it like this").
   * Your face in the panel (Pick / Live, pinch and pan, Fit) and the character on the stage beside it (pinch and pan as
   * always), both in view the whole time. Tap a point of your face, then tap the character where it should pull: a pin,
   * numbered and coloured the same on both. Again and again, as many as you like. Only face pins show on the stage here.
   * Tap a pin on the character to pick it; drag it to move where it pulls (one undo step). Test (round 5, his "place, test,
   * place, test"): your face drives the character and its pins (even in Set up, where the picture otherwise holds still) and
   * shows live; Stop holds everything still to place and drag. The pins are drawn inside the stage, where the solver draws
   * them (a reshape included). A row for each pin: where it comes from, what it pulls, Strength, Remove. Done closes.
   * The camera picture lives only on this screen and in memory, never stored.
   */
  var COLORS = ['#22d3ee', '#f472b6', '#facc15', '#a3e635', '#60a5fa', '#fb923c', '#c084fc', '#34d399'];
  fp.colorOf = function (i) { return COLORS[i % COLORS.length]; };
  function partName(app, q) { var n = AL.node(app.ch, q.node); return n ? (n.name || 'a part') : 'a part'; }

  fp.open = function (app, opts) {
    opts = opts || {};
    var cam = app.inp.cam;
    if (!app.fpMode) {
      var W = app.fpMode = { sel: null, pin: null, follow: false, mode: fp._mode || 'pick', all: !!fp._all, prevMode: cam.mode, camWasOn: !!cam.on,
        shapeEdit: app.shapeEdit || null, face: { k: 1, x: 0, y: 0 } };
      app.shapeEdit = null;                                  // a part held in its reshape would hold the face pins still
      if (!cam.on) fp._frozen = null;                        // the camera is off: no picture is kept
      stageOn(app); markersOn(app); fp.setFollow(app, W.follow);
      var ph = document.getElementById('panelhead'); if (ph) { W.headShown = ph.style.display; ph.style.display = 'none'; }   // the whole panel for your face (Done brings it back)
      W.raf = requestAnimationFrame(function loop() { if (app.fpMode !== W) return; drawFace(app); W.raf = requestAnimationFrame(loop); });
    }
    app.refreshPanel(true);
    if (!cam.on && !(AL.intake && AL.intake.hosted) && opts.camera !== false) startCam(app);
  };
  /** Done: back to the card you came from; the stage as it was (Set up holds still again). */
  fp.close = function (app) {
    var W = app.fpMode; if (!W) return;
    stageOff(app); markersOff(app);
    if (W.hook) { var i = app.hooks.indexOf(W.hook); if (i >= 0) app.hooks.splice(i, 1); W.hook = null; }
    clearStatic(app);
    var cam = app.inp.cam; if (cam.on) cam.mode = W.camWasOn && W.prevMode && W.prevMode !== 'paused' ? W.prevMode : 'always';
    if (W.raf) cancelAnimationFrame(W.raf);
    if (W.shapeEdit && !app.shapeEdit) app.shapeEdit = W.shapeEdit;
    var ph = document.getElementById('panelhead'); if (ph) ph.style.display = W.headShown || '';
    app.fpMode = null;
    app.refreshPanel(true);
  };
  function startCam(app) {
    var inp = app.inp, cam = inp.cam;
    app.toast('Starting the face camera…');
    inp.camStart().then(function () {
      cam.mode = app.fpMode && !app.fpMode.follow ? 'paused' : 'always';
      app.toast('Look at the phone for a moment, relaxed: that is your neutral face.');
      if (app.fpMode) app.refreshPanel(true);
    }).catch(function () { app.toast('The face camera could not start here. It needs a camera and the PC page.'); if (app.fpMode) app.refreshPanel(true); });
  }

  /** Test. On: your face drives the character and, where the picture holds still (Set up), its face pins; your face shows live
      beside it. Off (Stop): nothing moves with you, so you can place and drag. The camera follows always while testing, whatever
      it was left on (talk-only included), and holds while not. */
  fp.setFollow = function (app, on) {
    var W = app.fpMode; if (!W) return;
    var cam = app.inp.cam;
    W.follow = !!on;
    if (cam.on) cam.mode = on ? 'always' : 'paused';
    if (on && W.mode !== 'live') { W.testFrom = W.mode; W.mode = 'live'; W.face = { k: 1, x: 0, y: 0 }; }
    else if (!on && W.testFrom) { W.mode = W.testFrom; W.testFrom = null; W.face = { k: 1, x: 0, y: 0 }; }
    if (on && !W.hook) {
      W.hook = function (dt, liveNow) {
        if (liveNow || !cam.on) return;
        app.inp.watch(); fp.feed(app.inp, cam.face, dt);
        [app._bs, app.held].forEach(function (S) { if (!S) return; for (var k in app.inp.S) if (k.slice(0, 3) === 'pt.' || k.slice(0, 5) === 'face.') S[k] = app.inp.S[k]; });
      };
      app.hooks.push(W.hook);
    } else if (!on && W.hook) { app.hooks.splice(app.hooks.indexOf(W.hook), 1); W.hook = null; clearStatic(app); }
  };
  function clearStatic(app) { [app._bs, app.held].forEach(function (S) { if (!S) return; for (var k in S) if (k.slice(0, 3) === 'pt.' || k.slice(0, 5) === 'face.') delete S[k]; }); }

  /* ── the pins on the stage: numbered, coloured, where they pull now ── */
  function pinLocal(app, q) {
    var n = AL.node(app.ch, q.node), pin = n && n.mesh && (n.mesh.pins || []).filter(function (p) { return p.id === q.pin; })[0];
    if (!pin) return null;
    var x = pin.x, y = pin.y, S = app._S || {};
    (n.shapes || []).forEach(function (sh) {
      if (sh.face !== q.id || !sh.d || !sh.d[q.pin]) return;
      var v = S[sh.sig]; if (typeof v !== 'number') return;
      var w = Math.max(-1.5, Math.min(1.5, v * (sh.amt == null ? 1 : sh.amt)));
      x += sh.d[q.pin][0] * w; y += sh.d[q.pin][1] * w;
    });
    return { n: n, x: x, y: y };
  }
  /** Where the pin's spot is drawn now, in stage pixels: the solver's own pin targets, so a part's reshape (Topps's body on
      all fours) and every shape it wears are in it. Null until the part has been drawn with that pin. */
  fp._drawnAt = function (app, q) {
    var n = AL.node(app.ch, q.node), at = app.out && app.out.meshAt && app.out.meshAt[q.node], pins = n && n.mesh && n.mesh.pins;
    if (!at || !pins || at.Q.length !== pins.length) return null;
    for (var i = 0; i < pins.length; i++) if (pins[i].id === q.pin) { var w = M.ap(at.M, at.Q[i][0], at.Q[i][1]), v = app.view; return { x: w[0] * v.s + v.ox, y: w[1] * v.s + v.oy }; }
    return null;
  };
  function stagePos(app, q) {
    var at = fp._drawnAt(app, q); if (at) return at;
    var L = pinLocal(app, q); if (!L) return null;                         // (not drawn yet: where it rests, with its face shape)
    var m = app.out && app.out.M[L.n.id]; if (!m) return null;
    var w = M.ap(m, L.x, L.y), v = app.view;
    return { x: w[0] * v.s + v.ox, y: w[1] * v.s + v.oy };
  }
  /** The markers live inside the stage, so they sit exactly on the picture whatever is above it (on the phone: the top bar). */
  fp._layerHost = function (st) { return st; };
  function markersOn(app) {
    var W = app.fpMode, st = document.getElementById('stage'); if (!st) return;
    W.layer = h('div', { 'aria-hidden': 'true', style: 'position:absolute;left:0;top:0;right:0;bottom:0;pointer-events:none;z-index:25;overflow:hidden' });
    (fp._layerHost(st) || st).appendChild(W.layer);
    W.mk = {};
    W.mhook = function () { fp._drawMarkers(app); };
    app.hooks.push(W.mhook);
  }
  function markersOff(app) {
    var W = app.fpMode;
    if (W.mhook) { var i = app.hooks.indexOf(W.mhook); if (i >= 0) app.hooks.splice(i, 1); }
    if (W.layer && W.layer.parentNode) W.layer.parentNode.removeChild(W.layer);
  }
  fp._drawMarkers = function (app) {
    var W = app.fpMode; if (!W || !W.layer) return;
    var seen = {};
    fp.list(app.ch).forEach(function (q, i) {
      var at = W.dragAt && W.dragAt.id === q.id ? W.dragAt : stagePos(app, q); if (!at) return;
      var el = W.mk[q.id];
      if (!el) { el = W.mk[q.id] = h('div', { style: 'position:absolute;width:26px;height:26px;margin:-13px 0 0 -13px;border-radius:50%;display:flex;align-items:center;justify-content:center;font:750 13px system-ui,sans-serif;color:#000;border:2px solid #000;box-sizing:border-box' }); W.layer.appendChild(el); }
      el.textContent = String(i + 1); el.style.background = COLORS[i % COLORS.length];
      el.style.boxShadow = W.pin === q.id ? '0 0 0 3px #fff' : '0 0 0 2px rgba(255,255,255,0.3)';
      el.style.left = at.x + 'px'; el.style.top = at.y + 'px';
      seen[q.id] = 1;
    });
    for (var id in W.mk) if (!seen[id]) { if (W.mk[id].parentNode) W.mk[id].parentNode.removeChild(W.mk[id]); delete W.mk[id]; }
  };
  /** Where each face pin shows on the stage (stage pixels), in pin order, read from the drawn markers' own boxes: what the eye sees, and what a finger grabs. */
  fp.markers = function (app) {
    var W = app.fpMode, st = document.getElementById('stage'); if (!W || !W.mk || !st) return [];
    var r = st.getBoundingClientRect(), k = st.clientWidth / (r.width || 1);
    return fp.list(app.ch).map(function (q) {
      var el = W.mk[q.id]; if (!el || !el.isConnected) return null; var b = el.getBoundingClientRect(); if (!b.width) return null;
      return { id: q.id, x: (b.left + b.width / 2 - r.left) * k, y: (b.top + b.height / 2 - r.top) * k };
    }).filter(Boolean);
  };
  function markerAt(app, p) {
    var best = null, bd = 30;                                              // a fingertip's reach around a 26-point pin
    fp.markers(app).forEach(function (m) { var d = Math.hypot(m.x - p.x, m.y - p.y); if (d < bd) { bd = d; best = m; } });
    return best;
  }

  /* ── the stage while Face pins is open: tap to pin or pick, drag a pin to move it, one finger pans, two pinch ── */
  function stageOn(app) {
    var W = app.fpMode, st = document.getElementById('stage'), ptrs = {}, g = null;
    if (!st) return;
    function pos(e) { var r = st.getBoundingClientRect(), k = st.clientWidth / r.width; return { x: (e.clientX - r.left) * k, y: (e.clientY - r.top) * k }; }
    function swallow(e) { e.stopImmediatePropagation(); e.stopPropagation(); if (e.cancelable) e.preventDefault(); }
    function down(e) {
      swallow(e); try { st.setPointerCapture(e.pointerId); } catch (x) { /* a made-up event */ }
      var p = pos(e), ids; ptrs[e.pointerId] = p; ids = Object.keys(ptrs);
      if (ids.length === 2) { var a = ptrs[ids[0]], b = ptrs[ids[1]]; g = { kind: 'pinch', d: Math.hypot(a.x - b.x, a.y - b.y) || 1, mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2 }; W.dragAt = null; return; }
      var hit = markerAt(app, p);
      g = hit ? { kind: 'pin', id: hit.id, at: p, moved: 0 } : { kind: 'pan', at: p, moved: 0 };
    }
    function move(e) {
      if (!ptrs[e.pointerId]) return; swallow(e);
      var p = pos(e), v = app.view; ptrs[e.pointerId] = p;
      if (!g) return;
      if (g.kind === 'pinch') {
        var ids = Object.keys(ptrs); if (ids.length < 2) return;
        var a = ptrs[ids[0]], b = ptrs[ids[1]], d = Math.hypot(a.x - b.x, a.y - b.y) || 1, mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2, f = d / g.d;
        v.ox = mx - (g.mx - v.ox) * f; v.oy = my - (g.my - v.oy) * f; v.s *= f; g.d = d; g.mx = mx; g.my = my; app.vt = null; app.userView = true; return;
      }
      g.moved += Math.hypot(p.x - g.at.x, p.y - g.at.y);
      if (g.kind === 'pan' && g.moved > 8) { v.ox += p.x - g.at.x; v.oy += p.y - g.at.y; app.vt = null; app.userView = true; }
      if (g.kind === 'pin' && g.moved > 6) W.dragAt = { id: g.id, x: p.x, y: p.y };
      g.at = p;
    }
    function up(e) {
      if (!ptrs[e.pointerId]) return; swallow(e); delete ptrs[e.pointerId];
      if (Object.keys(ptrs).length) return;
      var k = g; g = null; W.dragAt = null;
      if (!k || k.kind === 'pinch') return;
      var p = pos(e);
      if (k.kind === 'pin' && k.moved > 6) { fp.movePin(app, k.id, p); return; }
      if (k.moved > 8) return;                                // that was a pan
      fp._tap(app, p);
    }
    W.stage = { st: st, down: down, move: move, up: up };
    st.addEventListener('pointerdown', down, true); st.addEventListener('pointermove', move, true);
    st.addEventListener('pointerup', up, true); st.addEventListener('pointercancel', up, true);
  }
  function stageOff(app) {
    var S = app.fpMode.stage; if (!S) return;
    S.st.removeEventListener('pointerdown', S.down, true); S.st.removeEventListener('pointermove', S.move, true);
    S.st.removeEventListener('pointerup', S.up, true); S.st.removeEventListener('pointercancel', S.up, true);
  }
  /** A tap on the character: with a point of your face chosen, pin it there; else pick the pin under the finger, or name the part. */
  fp._tap = function (app, p) {
    var W = app.fpMode; if (!W) return;
    if (W.sel != null) {
      var id = fp.pinAt(app, W.sel, p);
      if (id) { W.pin = typeof id === 'string' ? id : null; W.sel = null; }
      app.refreshPanel(true); return;
    }
    var hit = markerAt(app, p);
    if (hit) { W.pin = hit.id; app.refreshPanel(true); return; }
    W.pin = null;
    var c = app.toChar(p.x, p.y), it = app.out ? AL.hit(app.out, c[0], c[1]) : null, n = it && AL.node(app.ch, it.id);
    app.toast(n ? (n.name || 'This part') + ': tap a point on your face first, then here, to pin it.' : 'Tap a point on your face first, then the character.');
    app.refreshPanel(true);
  };
  /** Drag a pin to a new spot: it pulls from there (on whatever part is under it). Keeps its number and strength. One undo step. */
  fp.movePin = function (app, id, p) {
    var list = fp.list(app.ch), q = list.filter(function (r) { return r.id === id; })[0]; if (!q) return false;
    var c = app.toChar(p.x, p.y), it = app.out ? AL.hit(app.out, c[0], c[1]) : null;
    if (!it) { app.toast('Drop it on the character itself.'); return false; }
    var n = AL.node(app.ch, it.id), m = n && app.out.M[n.id];
    if (!n || !m) { app.toast('That part cannot take a pin.'); return false; }
    if (AL.fit && AL.fit.can && !AL.fit.can(app, n)) { app.toast('Arms and legs follow the hands and feet. Try another spot.'); return false; }
    var l = M.ap(M.inv(m), c[0], c[1]), at = list.indexOf(q), amt = q.amt == null ? START : q.amt, point = q.point, nid = null;
    app.edit(function () {
      fp.unbind(app.ch, id);
      if (!n.mesh) n.mesh = { pins: [], quiet: true };
      n.mesh.pins = n.mesh.pins || [];
      if (!(n.shapes || []).some(function (s) { return s.id === 'fix'; })) (n.shapes = n.shapes || []).unshift({ id: 'fix', name: 'Reshaped', sig: 'always', amt: 1, d: {} });
      var pin = AL.fit.dropPin(app, n, l);
      nid = fp.bind(app.ch, n, pin, point, true);
      var L = app.ch.facePins, moved = L.pop(); L.splice(at, 0, moved);         // its number stays
      moved.amt = amt; (n.shapes || []).forEach(function (s) { if (s.face === nid) s.amt = amt; });
    }, false, 'Move a face pin');
    if (app.fpMode) app.fpMode.pin = nid;
    app.refreshPanel(true);
    return true;
  };

  /* ── your face, in the panel ── */
  var named = null;
  function namedPoints() { if (!named) { named = {}; ((AL.face && AL.face.NAMED) || []).forEach(function (q) { named[q[0]] = q; }); } return named; }
  function faceSource(app) {
    var W = app.fpMode, cam = app.inp.cam;
    if (W.mode === 'live') {
      if (cam.on && cam.v && cam.v.readyState >= 2) { app.inp.watch(); return { img: cam.v, lm: cam.marks, w: cam.v.videoWidth, h: cam.v.videoHeight, mirror: !cam.facing }; }
      return null;
    }
    if (fp._frozen) return fp._frozen;
    var cal = calibration(app); if (!cal || !cal.points) return null;
    var side = (cam.facing ? -1 : 1) * (cal.sign && cal.sign.lean ? cal.sign.lean : -1);
    return { map: cal.points, side: side };
  }
  /** Fit the source into the canvas, then the finger's own zoom and pan on top. */
  function faceView(W, src, r) {
    var v = { s: 1, ox: 0, oy: 0 };
    if (src.map) {
      var x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9, n = src.map.length / 3;
      for (var i = 0; i < n; i++) { var x = src.map[i * 3] * src.side, y = src.map[i * 3 + 1]; x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
      v.s = Math.min((r.width - 40) / (x1 - x0), (r.height - 40) / (y1 - y0)); v.ox = r.width / 2 - (x0 + x1) / 2 * v.s; v.oy = r.height / 2 - (y0 + y1) / 2 * v.s;
    } else { v.s = Math.min(r.width / src.w, r.height / src.h); v.ox = (r.width - src.w * v.s) / 2; v.oy = (r.height - src.h * v.s) / 2; }
    var f = W.face; return { s: v.s * f.k, ox: v.ox * f.k + f.x, oy: v.oy * f.k + f.y };
  }
  function faceXY(src, v, i) {
    if (src.map) return [v.ox + src.map[i * 3] * src.side * v.s, v.oy + src.map[i * 3 + 1] * v.s];
    var q = src.lm && src.lm[i]; if (!q) return null;
    return [v.ox + (src.mirror ? 1 - q.x : q.x) * src.w * v.s, v.oy + q.y * src.h * v.s];
  }
  function drawFace(app) {
    var W = app.fpMode, cv = W && W.cv; if (!cv || !cv.isConnected) return;
    var r = cv.getBoundingClientRect(), dpr = Math.min(2.5, root.devicePixelRatio || 1);
    if (cv.width !== Math.round(r.width * dpr) || cv.height !== Math.round(r.height * dpr)) { cv.width = Math.max(1, Math.round(r.width * dpr)); cv.height = Math.max(1, Math.round(r.height * dpr)); }
    var g = cv.getContext('2d'), src = faceSource(app), i;
    g.setTransform(1, 0, 0, 1, 0, 0); g.clearRect(0, 0, cv.width, cv.height); g.setTransform(dpr, 0, 0, dpr, 0, 0);
    W.xy = null;
    if (!src) { g.fillStyle = GREY; g.font = '500 14px system-ui, sans-serif'; g.textAlign = 'center'; g.fillText(W.mode === 'live' ? 'The camera is off.' : 'No face yet: turn on the camera and look at the phone.', r.width / 2, r.height / 2); return; }
    var v = faceView(W, src, r); W.xy = function (k) { return faceXY(src, v, k); };
    if (src.img) {
      g.save(); g.globalAlpha = 0.85;
      if (src.mirror) { g.translate(v.ox + src.w * v.s, v.oy); g.scale(-v.s, v.s); } else { g.translate(v.ox, v.oy); g.scale(v.s, v.s); }
      try { g.drawImage(src.img, 0, 0, src.w, src.h); } catch (e) { /* not a picture yet */ }
      g.restore();
      if (!src.lm || !src.lm.length) { g.fillStyle = 'rgba(9,9,11,.72)'; g.fillRect(0, r.height / 2 - 18, r.width, 36); g.fillStyle = INK; g.font = '600 14px system-ui, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText('Looking for your face\u2026 face the phone', r.width / 2, r.height / 2); g.textBaseline = 'alphabetic'; }
    }
    var nm = namedPoints(), list = fp.list(app.ch), first = {};
    list.forEach(function (q, k) { if (first[q.point] == null) first[q.point] = k; });
    var n = src.map ? src.map.length / 3 : (src.lm ? src.lm.length : 0);
    for (i = 0; i < n; i++) { var p = faceXY(src, v, i); if (!p) continue; g.fillStyle = W.all ? 'rgba(161,161,170,0.9)' : src.img ? 'rgba(228,228,231,0.35)' : FAINT; g.beginPath(); g.arc(p[0], p[1], W.all ? 2.2 : 1.2, 0, 7); g.fill(); }
    for (i = 0; i < n; i++) {
      var k2 = first[i], isSel = i === W.sel;
      if (!nm[i] && k2 == null && !isSel && !W.all) continue;
      if (W.all && k2 == null && !isSel && !nm[i]) continue;
      var q2 = faceXY(src, v, i); if (!q2) continue;
      if (k2 != null) {                                              // a pinned point: its number and colour, as on the character
        g.beginPath(); g.arc(q2[0], q2[1], 11, 0, 7); g.fillStyle = COLORS[k2 % COLORS.length]; g.fill(); g.lineWidth = 2; g.strokeStyle = '#000'; g.stroke();
        if (list[k2].id === W.pin) { g.beginPath(); g.arc(q2[0], q2[1], 14, 0, 7); g.strokeStyle = '#fff'; g.lineWidth = 2.5; g.stroke(); }
        g.fillStyle = '#000'; g.font = '750 12px system-ui, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(String(k2 + 1), q2[0], q2[1] + 0.5);
      } else { g.beginPath(); g.arc(q2[0], q2[1], isSel ? 9 : 6, 0, 7); g.fillStyle = isSel ? CYAN : 'rgba(63,63,70,0.95)'; g.fill(); g.lineWidth = 1.5; g.strokeStyle = isSel ? CYAN : '#0c0c0e'; g.stroke(); }
      if (isSel) { g.beginPath(); g.arc(q2[0], q2[1], 15, 0, 7); g.strokeStyle = 'rgba(34,211,238,0.45)'; g.lineWidth = 4; g.stroke(); }
    }
    if (W.sel != null) { var s = faceXY(src, v, W.sel); if (s) { g.fillStyle = INK; g.font = '600 13px system-ui, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'alphabetic'; g.fillText(fp.nameOf(W.sel), s[0], s[1] - 18); } }
  }
  /** Where face point i is on the screen now (for a finger, or a test), or null. */
  fp.facePos = function (app, i) {
    var W = app.fpMode; if (!W || !W.cv) return null;
    if (!W.xy) drawFace(app);
    var p = W.xy && W.xy(i); if (!p) return null;
    var r = W.cv.getBoundingClientRect(); return { el: W.cv, x: r.left + p[0], y: r.top + p[1] };
  };
  function faceHandlers(app, cv) {
    var ptrs = {}, g = null;
    function local(e) { var r = cv.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }
    cv.addEventListener('pointerdown', function (e) {
      var W = app.fpMode; if (!W) return; e.preventDefault(); try { cv.setPointerCapture(e.pointerId); } catch (x) { /* a made-up event */ }
      var p = local(e), ids; ptrs[e.pointerId] = p; ids = Object.keys(ptrs);
      if (ids.length === 2) { var a = ptrs[ids[0]], b = ptrs[ids[1]]; g = { kind: 'pinch', d: Math.hypot(a.x - b.x, a.y - b.y) || 1, mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2 }; return; }
      g = { kind: 'pan', at: p, moved: 0 };
    });
    cv.addEventListener('pointermove', function (e) {
      var W = app.fpMode; if (!W || !ptrs[e.pointerId] || !g) return;
      var p = local(e), f = W.face; ptrs[e.pointerId] = p;
      if (g.kind === 'pinch') { var ids = Object.keys(ptrs); if (ids.length < 2) return; var a = ptrs[ids[0]], b = ptrs[ids[1]], d = Math.hypot(a.x - b.x, a.y - b.y) || 1, mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2, k = d / g.d;
        f.x = mx - (g.mx - f.x) * k; f.y = my - (g.my - f.y) * k; f.k *= k; g.d = d; g.mx = mx; g.my = my; return; }
      g.moved += Math.hypot(p.x - g.at.x, p.y - g.at.y);
      if (g.moved > 8) { f.x += p.x - g.at.x; f.y += p.y - g.at.y; }
      g.at = p;
    });
    function up(e) {
      var W = app.fpMode; if (!W || !ptrs[e.pointerId]) return; delete ptrs[e.pointerId];
      if (Object.keys(ptrs).length) return;
      var k = g; g = null; if (!k || k.kind !== 'pan' || k.moved > 8) return;
      var p = local(e), best = null, bd = W.all ? 16 : 30, nm = namedPoints(), list = fp.list(app.ch), pinned = {};
      list.forEach(function (q) { pinned[q.point] = 1; });
      if (!W.xy) return;
      var n = 478; for (var i = 0; i < n; i++) {
        if (!W.all && !nm[i] && !pinned[i]) continue;
        var q = W.xy(i); if (!q) continue;
        var d = Math.hypot(q[0] - p.x, q[1] - p.y); if (d < bd) { bd = d; best = i; }
      }
      if (best == null) { W.sel = null; app.refreshPanel(true); return; }
      W.sel = W.sel === best ? null : best; W.pin = null;
      app.refreshPanel(true);
    }
    cv.addEventListener('pointerup', up); cv.addEventListener('pointercancel', up);
  }
  function setMode(app, m) {
    var W = app.fpMode, cam = app.inp.cam;
    if (m === W.mode) return;
    if (W.mode === 'live' && m === 'pick' && cam.on && cam.v && cam.v.readyState >= 2 && cam.marks) {   // leaving Live keeps that moment (in memory)
      var c = document.createElement('canvas'); c.width = cam.v.videoWidth; c.height = cam.v.videoHeight; c.getContext('2d').drawImage(cam.v, 0, 0);
      fp._frozen = { img: c, lm: cam.marks.map(function (q) { return { x: q.x, y: q.y }; }), w: c.width, h: c.height, mirror: !cam.facing };
    }
    W.mode = fp._mode = m; W.face = { k: 1, x: 0, y: 0 };
    app.refreshPanel(true);
  }

  /** The panel while Face pins is open (al-app.js refreshPanel asks this first). Your face gets all the room the panel shows. */
  fp.panel = function (app) {
    var W = app.fpMode; if (!W) return false;
    var pb = document.getElementById('panelbody'), ui = app.ui, cam = app.inp.cam, list = fp.list(app.ch);
    app.pinProvider = null; app.padDraw = null; app.padDraws = [];      // only face pins on the stage here
    if (app.coach) app.coach('<b>Face pins.</b> Tap a point of your face below, then the character where it should pull. Drag a numbered pin to move it.');
    pb.innerHTML = '';
    var follow = ui.chip(W.follow ? 'Stop' : 'Test', { on: W.follow, icon: W.follow ? 'stop' : 'play', cls: W.follow ? '' : 'go',
      title: W.follow ? 'Stop testing: the character holds still again, to place and drag pins' : 'Test your pins: move your face and watch the character follow. You can still drag a pin while testing',
      fn: function () { if (!cam.on) startCam(app); fp.setFollow(app, !W.follow); app.refreshPanel(true); } });
    follow.style.flex = 'none';
    var seg = ui.seg([['pick', 'Pick', 'Tap a point of your face to pin it (coming back from Live keeps that moment of your face)'], ['live', 'Live', 'Watch the points ride on your face (the camera picture, only here, never kept)']],
      W.mode, function (v) { setMode(app, v); }, 'How your face shows');
    seg.style.flex = 'none';
    pb.appendChild(h('div', { style: 'display:flex;flex-wrap:nowrap;align-items:center;gap:8px;margin:2px 0 4px' }, [
      seg, follow, h('span', { style: 'flex:1' }),
      ui.chip('Done', { cls: 'go', title: 'Close Face pins and go back to where you were', fn: function () { fp.close(app); } })
    ]));
    // your face: the map, with its own small controls on top and the next step written under it
    if (!W.cv) { W.cv = h('canvas', { 'aria-label': 'Your face: tap a point, pinch to zoom, drag to pan', title: 'Your face: tap a point, then tap the character where it should pull. Pinch to zoom, drag to pan' }); faceHandlers(app, W.cv); }
    W.cv.style.cssText = 'display:block;width:100%;height:100%;touch-action:none';
    var pinNo = W.pin ? list.map(function (q) { return q.id; }).indexOf(W.pin) + 1 : 0;
    var hint = W.sel != null ? 'Now tap the <b>character</b> where <b>' + fp.nameOf(W.sel) + '</b> should pull'
      : W.follow ? (pinNo ? 'Move your face: pin <b>' + pinNo + '</b> should pull. Drag it to adjust, or <b>Stop</b>' : 'Move your face and watch the character. <b>Stop</b> to place more')
      : pinNo ? 'Pin <b>' + pinNo + '</b>: tap <b>Test</b> and move your face, or drag it to move it'
      : list.length ? 'Tap the next point of <b>your face</b>, then the character' : 'Tap a point of <b>your face</b>, then the character where it should pull';
    var tools = h('div', { style: 'position:absolute;right:8px;top:8px;display:flex;gap:6px;flex-wrap:wrap;justify-content:flex-end' }, [
      W.mode === 'pick' && fp._frozen ? ui.chip('Plain map', { title: 'Drop the picture: the map of your neutral face', fn: function () { fp._frozen = null; app.refreshPanel(true); } }) : null,
      ui.chip('All points', { on: W.all, title: 'Show every point the camera follows (478), not only the named ones', fn: function () { W.all = fp._all = !W.all; app.refreshPanel(true); } }),
      ui.chip('Fit', { title: 'Show your whole face again', fn: function () { W.face = { k: 1, x: 0, y: 0 }; } })
    ].filter(Boolean));
    var box = h('div', { style: 'position:relative;background:#0c0c0e;border:1px solid #27272a;border-radius:12px;overflow:hidden;margin:2px 0 6px' }, [
      W.cv,
      h('div', { style: 'position:absolute;left:10px;top:10px;font-size:11px;letter-spacing:.08em;color:#a1a1aa;text-transform:uppercase;pointer-events:none', text: 'Your face' + (list.length ? '  ·  ' + list.length + ' pin' + (list.length > 1 ? 's' : '') : '') }),
      tools,
      h('div', { style: 'position:absolute;left:8px;right:8px;bottom:8px;text-align:center;font-size:13px;color:#d4d4d8;background:rgba(9,9,11,.72);border-radius:999px;padding:5px 10px;pointer-events:none', html: hint })
    ]);
    pb.appendChild(box);
    if (!cam.on) pb.appendChild(ui.row([ui.chip('Turn on the face camera', { cls: 'go', icon: 'cam', title: 'Start the face camera (the picture is never kept)', fn: function () { startCam(app); } })]));
    list.forEach(function (q, i) {
      var on = W.pin === q.id;
      var row = h('div', { style: 'padding:6px 6px 2px;border-radius:10px;margin-top:4px;' + (on ? 'background:#18181b;outline:1px solid #3f3f46' : '') }, [
        h('div', { class: 'rowf', style: 'align-items:center;gap:8px' }, [
          h('span', { style: 'width:22px;height:22px;border-radius:50%;flex:none;display:flex;align-items:center;justify-content:center;font:750 12px system-ui,sans-serif;color:#000;background:' + COLORS[i % COLORS.length], text: String(i + 1) }),
          h('span', { style: 'flex:1;min-width:0;font-size:13px', text: fp.nameOf(q.point) + ' \u2192 ' + partName(app, q) }),
          ui.chip('Remove', { icon: 'x', title: 'Take this face pin off (one Undo brings it back)', fn: function () { fp.remove(app, q.id); if (W.pin === q.id) W.pin = null; app.refreshPanel(true); } })
        ]),
        ui.slider('Strength', { min: 0, max: 4, step: 0.05, value: q.amt == null ? 1 : q.amt, fmt: function (v) { return (Math.round(v * 10) / 10) + '\u00d7'; },
          title: 'How far this spot moves for how far your face moves', set: function (v) { fp.strength(app, q.id, v); } })
      ]);
      row.addEventListener('click', function (e) { if (e.target.closest && e.target.closest('button,input')) return; W.pin = q.id; W.sel = null; app.refreshPanel(true); });
      pb.appendChild(row);
    });
    if (!list.length) pb.appendChild(ui.note('No face pins yet. Each one you make gets a row here: where it comes from, what it pulls, its strength and Remove.'));
    // the face fills what the panel shows (the pins' rows scroll on below it)
    var top = box.getBoundingClientRect().top, pr = pb.getBoundingClientRect(), bottom = Math.min(root.innerHeight || 800, pr.bottom || 800);
    box.style.height = Math.max(200, Math.round(bottom - top - 10)) + 'px';
    drawFace(app);
    return true;
  };

  /** The direction check, in the pilot: five short moves, each answered by itself or skipped after 8 seconds. */
  fp.directions = function (app) {
    var cam = app.inp.cam, st = cam._faceStage, F = AL.face;
    if (!cam.on || !st || !F) { app.toast('Turn on Face first.'); return; }
    var steps = [[F.HEAD_YAW, 'Turn your head to your right'], [F.HEAD_PITCH, 'Look down at your chin'], [F.HEAD_ROLL, 'Tilt your head toward your right shoulder'],
      [F.FACE_X, 'Lean your whole head to your right'], ['eye', 'Close your right eye']], k = 0, done = 0, since = 0, timer = 0;
    var line = h('p', { class: 'note', style: 'font-size:15px' }), wasMode = cam.mode;
    cam.mode = 'always';
    function ask() { since = Date.now(); st.lastCheck = null; st.check(steps[k][0]); line.innerHTML = '<b>' + (k + 1) + ' of ' + steps.length + ':</b> ' + steps[k][1]; }
    function tick() {
      var sh = document.getElementById('sheet');
      if (sh && sh.style.display === 'none') { clearInterval(timer); cam.mode = wasMode; st.checking = null; return; }   // closed with its X
      var c = st.lastCheck, ok = c && c.what === steps[k][0] && c.answer;
      if (!ok && Date.now() - since < 8000) return;
      if (ok) done++;
      if (++k >= steps.length) { finish(); return; }
      ask();
    }
    function finish() {
      clearInterval(timer); cam.mode = wasMode; st.checking = null; app.closeSheet();
      app.toast(done === 0 ? 'No face seen, so nothing changed.' : done === steps.length ? 'Directions set for this device.' : 'Directions set for ' + done + ' of ' + steps.length + ' (the rest are as they were).');
    }
    app.sheet('Check directions', h('div', null, [line, app.ui.note('Each move is read by itself; hold it until the next one shows.')]),
      [{ label: 'Stop', fn: function () { finish(); } }]);
    ask(); timer = setInterval(tick, 150);
  };

  /** Pin face point pt to the character at stage pixel p. One undo step. Returns true when it pinned. */
  fp.pinAt = function (app, pt, p) {
    var c = app.toChar(p.x, p.y), hit = app.out ? AL.hit(app.out, c[0], c[1]) : null;
    if (!hit) { app.toast('Nothing there. Tap on the character itself.'); return false; }
    var n = AL.node(app.ch, hit.id), m = n && app.out.M[n.id];
    if (!n || !m) { app.toast('That part cannot take a pin.'); return false; }
    if (AL.fit && AL.fit.can && !AL.fit.can(app, n)) { app.toast('Arms and legs follow the hands and feet. Try the head, the mouth, the brows or the cheeks.'); return false; }
    var l = M.ap(M.inv(m), c[0], c[1]);
    var made = false, pin = null, newId = null, at = app.out.meshAt && app.out.meshAt[n.id], v = app.view;
    var near = null, bd = 12;                                              // a pin goes where you tap (an existing one only when drawn right there)
    if (at && n.mesh && n.mesh.pins && at.Q.length === n.mesh.pins.length) n.mesh.pins.forEach(function (q, i) {
      var w = M.ap(at.M, at.Q[i][0], at.Q[i][1]), d = Math.hypot(w[0] * v.s + v.ox - p.x, w[1] * v.s + v.oy - p.y); if (d < bd) { bd = d; near = q.id; }
    });
    app.edit(function () {
      if (!n.mesh) n.mesh = { pins: [], quiet: true };
      n.mesh.pins = n.mesh.pins || [];
      if (!(n.shapes || []).some(function (q) { return q.id === 'fix'; })) (n.shapes = n.shapes || []).unshift({ id: 'fix', name: 'Reshaped', sig: 'always', amt: 1, d: {} });
      if (near) pin = n.mesh.pins.filter(function (q) { return q.id === near; })[0] || null;
      if (!pin) { pin = AL.fit.dropPin(app, n, l); made = true; }
      newId = fp.bind(app.ch, n, pin, pt, made);
    }, false, 'Face pin');
    app.toast('Pinned: ' + fp.nameOf(pt) + ' pulls ' + (n.name || 'this part') + '.');
    return newId || true;
  };

  /**
   * The model half of a face pin (no screen, so the break test can call it): the two shapes that carry the point's
   * movement to the pin, and the record. K turns face-size units into the character's own: half the head's width.
   */
  fp.bind = function (ch, n, pin, pt, made) {
    var hd = AL.firstRole(ch.root, 'head') || n, b = AL.boundsOf(hd), K = b ? Math.max(20, (b.x1 - b.x0) * 0.5) : 60;
    var id = AL.id('fp'), name = fp.nameOf(pt), dx = {}, dy = {};
    dx[pin.id] = [K, 0]; dy[pin.id] = [0, K];
    (n.shapes = n.shapes || []).push(
      { id: 'fp:' + id + ':x', name: 'Face pin: ' + name + ' (across)', sig: 'pt.' + pt + '.x', amt: START, d: dx, face: id },
      { id: 'fp:' + id + ':y', name: 'Face pin: ' + name + ' (up and down)', sig: 'pt.' + pt + '.y', amt: START, d: dy, face: id });
    (ch.facePins = ch.facePins || []).push({ id: id, point: pt, node: n.id, pin: pin.id, amt: START, made: !!made });
    return id;
  };

  /** The strength slider: both of the pin's shapes (the slider itself makes the one undo step). */
  fp.strength = function (app, id, v) {
    var q = fp.list(app.ch).filter(function (r) { return r.id === id; })[0], n = q && AL.node(app.ch, q.node);
    if (!q || !n) return;
    q.amt = v;
    (n.shapes || []).forEach(function (s) { if (s.face === id) s.amt = v; });
  };

  /** Take a face pin off (and the pin itself, if it was made for it and nothing else uses it). One undo step. */
  fp.remove = function (app, id) {
    app.edit(function () { fp.unbind(app.ch, id); }, false, 'Remove a face pin');
  };
  fp.unbind = function (ch, id) {
    var q = fp.list(ch).filter(function (r) { return r.id === id; })[0]; if (!q) return;
    var n = AL.node(ch, q.node);
    ch.facePins = fp.list(ch).filter(function (r) { return r.id !== id; });
    if (!ch.facePins.length) delete ch.facePins;
    if (!n) return;
    n.shapes = (n.shapes || []).filter(function (s) { return s.face !== id; });
    var used = (n.shapes || []).some(function (s) { return s.d && s.d[q.pin] && (s.d[q.pin][0] || s.d[q.pin][1]); }) ||
      fp.list(ch).some(function (r) { return r.pin === q.pin; });
    if (q.made && !used && n.mesh && n.mesh.pins) {
      n.mesh.pins = n.mesh.pins.filter(function (p) { return p.id !== q.pin; });
      (n.shapes || []).forEach(function (s) { if (s.d) delete s.d[q.pin]; });
    }
  };
})(typeof self !== 'undefined' ? self : this);
