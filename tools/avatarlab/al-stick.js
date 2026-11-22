/* AvatarLab: THE JOYSTICK. A second pad in Perform, for the other thumb (the owner's mock-up, 11-21).
 *
 * In a scene the characters are small, too small to grab by the body with a finger on a phone. So beside Mic, Voice file and Face
 * there is a Joystick button. Tap it and a pad opens on the right, the same size as the head pad on the left: one thumb turns the
 * head, the other pilots the body. Hold to talk steps in toward the middle, and Mic, Voice file and Face become their pictures
 * only (their words are in their hover labels). Tap it again and everything goes back as it was.
 *
 * It is a JOYSTICK, not a second picture to drag: push toward an edge and the body goes that way and keeps going, and the further
 * out, the faster (gentle near the middle, quick at the rim). Let go and it springs back and the body stops where it is. It drives
 * exactly what dragging the body drives (the walker; or the lean of a character that does not walk), so recording, a second pass
 * and the walk itself work with no change. The character in hand is the one it moves.
 *
 * Phone: a Canvas in the Perform panel's pad row with pointerInput; each frame the same Walker.drag the body's own drag uses.
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h, stick = AL.stick = {}, NS = 'http://www.w3.org/2000/svg';
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  stick.SPEED = 1.0;                          // body heights a second at the rim
  stick.CURVE = 1.7;                          // how much gentler the middle is than the rim

  document.head.appendChild(h('style', { text: [
    '.padrow.stick{gap:8px} .padrow.stick .side{flex:1 1 0;align-items:center} .padrow.stick .meter{align-self:stretch}',
    '.padrow.stick .talk{height:44px;font-size:12.5px;gap:5px;padding:0 6px;width:100%} .padrow.stick .talk svg.ic{width:16px;height:16px}',
    '.rowf.ico{justify-content:center;gap:5px} .rowf .chip.ico{padding:0 9px;gap:0;font-size:0} .rowf .chip.ico svg.ic{width:16px;height:16px}',
    '.chip svg.js{width:15px;height:15px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}',
    '.pad.stickpad{cursor:grab}'
  ].join('\n') }));

  /* its picture: a pad with a stick and a knob */
  function glyph() {
    var s = document.createElementNS(NS, 'svg'); s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('class', 'ic js'); s.setAttribute('aria-hidden', 'true');
    var p = document.createElementNS(NS, 'path'); p.setAttribute('d', 'M12 3.5l-2 2.2M12 3.5l2 2.2M12 20.5l-2-2.2M12 20.5l2-2.2M3.5 12l2.2-2M3.5 12l2.2 2M20.5 12l-2.2-2M20.5 12l-2.2 2M12 8.6a3.4 3.4 0 100 6.8 3.4 3.4 0 000-6.8z'); s.appendChild(p);
    return s;
  }
  stick.open = function () { try { return localStorage.getItem('al.stick') === '1'; } catch (e) { return false; } };
  stick.set = function (app, on) { try { localStorage.setItem('al.stick', on ? '1' : '0'); } catch (e) { } stick.release(app); app.refreshPanel(true); };
  /** The button beside Voice file. */
  stick.chip = function (app) {
    var on = stick.open(), c = app.ui.chip(on ? 'Joystick' : 'Joystick', { on: on, title: on ? 'Put the joystick away' : 'Joystick: a pad for your other thumb that walks the body left, right, up and down', fn: function () { stick.set(app, !stick.open()); } });
    var old = c.querySelector('svg'); if (old) old.replaceWith(glyph()); else c.insertBefore(glyph(), c.firstChild);
    return c;
  };

  /* ── what it drives: the body, through the walker, as a drag on the body would ── */
  var K = { on: false, x: 0, y: 0, inp: null, walk: false, v: null };
  stick.state = K;
  function grab(app) {
    var inp = app.inp; K.inp = inp; K.walk = !!inp.walker.travels(); inp.held.body = true;
    if (K.walk) { inp.walker.grab([0, 0]); K.v = [0, 0]; }
  }
  /** Let go: the body stops where it is (a walker), or stands up straight (a lean). */
  stick.release = function () {
    var inp = K.inp; K.on = false; K.x = K.y = 0; if (!inp) return;
    if (K.walk) inp.walker.release(); else { inp.pose.sway = 0; inp.pose.bounce = 0; }
    inp.held.body = false; K.inp = null; K.v = null;
  };
  function size(app) { var b = AL.boundsOf(app.ch.root) || { x0: 0, y0: 0, x1: app.ch.w, y1: app.ch.h }; return { w: Math.max(20, b.x1 - b.x0), h: Math.max(20, b.y1 - b.y0) }; }
  /** Once a frame while a thumb is on it: push the body on at the speed the knob asks for. */
  stick.step = function (app, dt) {
    if (!K.on || !K.inp) return;
    if (K.inp !== app.inp) { stick.release(app); return; }                // (the character in hand changed under the thumb)
    var inp = K.inp, gx = Math.sign(K.x) * Math.pow(Math.abs(K.x), stick.CURVE), gy = Math.sign(K.y) * Math.pow(Math.abs(K.y), stick.CURVE);
    inp.held.body = true;
    if (!K.walk) { inp.pose.sway = clamp(K.x, -1, 1); inp.pose.bounce = clamp(K.y, -1, 1); return; }      // a character that does not walk leans the way the knob is pushed
    var w = inp.walker, z = size(app), g0 = w.g0; if (!g0) { grab(app); g0 = w.g0; if (!g0) return; }
    var sp = stick.SPEED * z.h * dt, lim = w.lim || [-z.w * 0.7, z.w * 0.7];                         // (alone: the room to walk the view keeps each side, app.fit. In a scene: across the stage and off either side, al-stage-walk.js)
    K.v[0] = clamp(K.v[0] + gx * sp, lim[0] - g0.tx, lim[1] - g0.tx);                                // stays within reach of the picture, or of the stage
    K.v[1] = clamp(K.v[1] + gy * sp, w.base - z.h - g0.ty, w.base + z.h * 0.5 - g0.ty);              // up toward a jump, down toward a crouch, never off the ground for good
    w.drag(K.v);
  };
  AL.app.hooks.push(function (dt) { if (K.on) stick.step(AL.app, dt || 1 / 30); });

  /** The pad: the same size and look as the head pad beside it. */
  stick.pad = function (app) {
    var c = h('canvas'), pad = h('div', { class: 'pad stickpad', role: 'slider', 'aria-label': 'Joystick for the body', title: 'Joystick: push toward an edge and the body goes that way, faster the further out. Let go and it stops.' }, c);
    function draw() {
      var s = pad.clientWidth || 132, dpr = root.devicePixelRatio || 1; if (c.width !== Math.round(s * dpr)) { c.width = c.height = Math.round(s * dpr); }
      var g = c.getContext('2d'), m = s / 2, r = s * 0.4, live = AL.app.mode === 'perform' || AL.app.live; g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, s, s);
      g.strokeStyle = '#2c2c35'; g.lineWidth = 1; g.beginPath(); g.arc(m, m, r, 0, 7); g.stroke(); g.beginPath(); g.arc(m, m, r * 0.45, 0, 7); g.stroke();
      g.strokeStyle = '#71717a'; g.lineWidth = 1.6; g.lineCap = 'round'; g.lineJoin = 'round';
      [[0, -1], [0, 1], [-1, 0], [1, 0]].forEach(function (d) { var tx = m + d[0] * (r + 9), ty = m + d[1] * (r + 9), px = -d[1], py = d[0]; g.beginPath(); g.moveTo(tx - d[0] * 5 + px * 4, ty - d[1] * 5 + py * 4); g.lineTo(tx, ty); g.lineTo(tx - d[0] * 5 - px * 4, ty - d[1] * 5 - py * 4); g.stroke(); });
      var kx = m + K.x * r, ky = m + K.y * r;
      if (K.on) { g.strokeStyle = 'rgba(244,63,142,.45)'; g.lineWidth = 3; g.beginPath(); g.moveTo(m, m); g.lineTo(kx, ky); g.stroke(); }
      g.beginPath(); g.arc(kx, ky, 13, 0, 7); g.fillStyle = K.on ? '#f43f8e' : '#1c1c22'; g.fill(); g.strokeStyle = live ? '#f43f8e' : '#e4e4e7'; g.lineWidth = 2; g.stroke();
      var fl = stick.flash; if (fl && performance.now() < fl.until) { g.font = '600 10.5px "IBM Plex Sans", system-ui, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'bottom'; g.fillStyle = 'rgba(9,9,11,.75)'; var tw = g.measureText(fl.text).width + 12; g.fillRect(m - tw / 2, s - 22, tw, 16); g.fillStyle = '#f43f8e'; g.fillText(fl.text, m, s - 8); g.textAlign = 'left'; }      // (what a gesture just did, for a moment)
    }
    (app.padDraws = app.padDraws || []).push(draw);
    function at(e) { var b = pad.getBoundingClientRect(), s = b.width, x = (e.clientX - b.left) / s * 2 - 1, y = (e.clientY - b.top) / s * 2 - 1, d = Math.hypot(x, y) / 0.8; if (d > 1) { x /= d; y /= d; } K.x = clamp(x / 0.8, -1, 1); K.y = clamp(y / 0.8, -1, 1); }
    function raw(e) { var b = pad.getBoundingClientRect(); return [(e.clientX - b.left) / b.width * 2 - 1, (e.clientY - b.top) / b.width * 2 - 1]; }
    pad.addEventListener('pointerdown', function (e) {
      if (e.target.closest && e.target.closest('.padset')) return;
      e.preventDefault(); try { pad.setPointerCapture(e.pointerId); } catch (x) { } K.pid = e.pointerId; K.on = true; grab(app); at(e);
      var w = K.inp && K.inp.walker; K.g = { t0: performance.now(), p0: raw(e), p: raw(e), far: 0, tx: w ? w.tx : 0, ty: w ? w.ty : 0 };
    });
    pad.addEventListener('pointermove', function (e) { if (!K.on || e.pointerId !== K.pid) return; at(e); if (K.g) { K.g.p = raw(e); K.g.far = Math.max(K.g.far, Math.hypot(K.g.p[0] - K.g.p0[0], K.g.p[1] - K.g.p0[1])); } });
    ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(function (ev) { pad.addEventListener(ev, function (e) {
      if (!K.on || e.pointerId !== K.pid) return;
      var G = K.g, inp = K.inp, kind = ev === 'pointerup' && G ? stick.gestureOf(G, performance.now()) : null; K.g = null; stick.release(app);
      if (!kind) return;
      if (inp && inp.walker && inp.walker.travels()) { inp.walker.tx = G.tx; inp.walker.ty = G.ty; }      // a flick or a tap is not a steer: the little the body was nudged is taken back
      if (AL.stickMoves) AL.stickMoves.gesture(app, kind);
    }); });
    if (AL.stickMoves) pad.appendChild(AL.stickMoves.button(app));
    return pad;
  };

  /* ── a gesture, or a steer: a quick touch is a TAP, a quick flick is a SWIPE, anything held is the joystick ── */
  stick.TAP = { ms: 230, move: 0.2 };       // shorter than this and moved less than this (pad radii)
  stick.SWIPE = { ms: 360, dist: 0.45 };    // shorter than this and moved at least this far
  stick.gestureOf = function (G, now) {
    var dt = now - G.t0, dx = G.p[0] - G.p0[0], dy = G.p[1] - G.p0[1], d = Math.hypot(dx, dy);
    if (dt < stick.TAP.ms && G.far < stick.TAP.move) return 'tap';
    if (dt < stick.SWIPE.ms && d >= stick.SWIPE.dist) return Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up');
    return null;
  };
})(typeof self !== 'undefined' ? self : this);
