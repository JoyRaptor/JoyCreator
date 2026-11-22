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

  /**
   * The setup screen (the owner's design, 11-22). A switch between Pick and Live:
   *   Live  the camera picture, mirrored like a mirror, with the face's points riding on your face as you move;
   *   Pick  tap a point. Coming back from Live freezes that moment (your face, mid-expression, under its points);
   *         "Plain map" drops the picture for the map of your neutral face.
   * The picture is only ever on this screen and in memory: never stored, dropped when the camera goes off.
   * Tap a point that has no pin: then tap the character where it should pull. Tap a pinned (cyan) point: its pins, each
   * with a strength slider and a Remove button.
   */
  fp.open = function (app, opts) {
    opts = opts || {};
    var ui = app.ui, ch = app.ch, inp = app.inp, cam = inp.cam, F = AL.face;
    var full = app.full('Face pins', function () { stop(); app.refreshPanel(true); });
    var mode = opts.mode || fp._mode || 'pick', allPts = !!fp._all, sel = null, live = null, raf = 0;
    if (!cam.on) fp._frozen = null;                                     // the camera is off: no picture is kept
    var cv = h('canvas', { 'aria-label': 'Your face: tap a point' });
    full.body.appendChild(cv);
    var named = {}; ((F && F.NAMED) || []).forEach(function (q) { named[q[0]] = q; });
    function pinnedCount() { var o = {}; fp.list(ch).forEach(function (q) { o[q.point] = (o[q.point] || 0) + 1; }); return o; }
    var pinned = pinnedCount();
    var foot = full.foot;

    function stop() { if (raf) cancelAnimationFrame(raf); raf = 0; }

    /** What is drawn now: {img: the picture (or null), lm: points in the picture's 0..1 space, mirrored}, or the neutral map. */
    function source() {
      if (mode === 'live') {
        if (cam.on && cam.v && cam.v.readyState >= 2) { inp.watch(); return { img: cam.v, lm: cam.marks, w: cam.v.videoWidth, h: cam.v.videoHeight, mirror: !cam.facing }; }
        return null;
      }
      if (fp._frozen) return fp._frozen;
      var cal = calibration(app); if (!cal || !cal.points) return null;
      var pts = cal.points, side = (cam.facing ? -1 : 1) * (cal.sign && cal.sign.lean ? cal.sign.lean : -1);
      return { map: pts, side: side };
    }
    /** Point i on screen for the current source, or null. */
    var view = { s: 1, ox: 0, oy: 0, dpr: 1 };
    function xy(src, i) {
      if (src.map) return [view.ox + src.map[i * 3] * src.side * view.s, view.oy + src.map[i * 3 + 1] * view.s];
      var q = src.lm && src.lm[i]; if (!q) return null;
      var x = src.mirror ? 1 - q.x : q.x;
      return [view.ox + x * src.w * view.s, view.oy + q.y * src.h * view.s];
    }
    function fit(src) {
      var r = cv.getBoundingClientRect(), dpr = Math.min(2.5, root.devicePixelRatio || 1);
      if (cv.width !== Math.round(r.width * dpr) || cv.height !== Math.round(r.height * dpr)) { cv.width = Math.max(1, Math.round(r.width * dpr)); cv.height = Math.max(1, Math.round(r.height * dpr)); }
      view.dpr = dpr;
      if (src.map) {
        var x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9, n = src.map.length / 3;
        for (var i = 0; i < n; i++) { var x = src.map[i * 3] * src.side, y = src.map[i * 3 + 1]; x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
        view.s = Math.min((r.width - 40) / (x1 - x0), (r.height - 40) / (y1 - y0));
        view.ox = r.width / 2 - (x0 + x1) / 2 * view.s; view.oy = r.height / 2 - (y0 + y1) / 2 * view.s;
      } else {                                                           // the picture, whole, centred
        view.s = Math.min(r.width / src.w, r.height / src.h);
        view.ox = (r.width - src.w * view.s) / 2; view.oy = (r.height - src.h * view.s) / 2;
      }
    }
    function draw() {
      var g = cv.getContext('2d'), src = source(), r = cv.getBoundingClientRect(), i;
      g.setTransform(1, 0, 0, 1, 0, 0); g.clearRect(0, 0, cv.width, cv.height);
      if (!src) {
        g.setTransform(view.dpr, 0, 0, view.dpr, 0, 0); g.fillStyle = GREY; g.font = '500 14px system-ui, sans-serif'; g.textAlign = 'center';
        g.fillText(mode === 'live' ? 'The camera is off.' : 'No face yet.', r.width / 2, r.height / 2);
        return;
      }
      fit(src);
      g.setTransform(view.dpr, 0, 0, view.dpr, 0, 0);
      if (src.img) {                                                     // the picture, mirrored like a mirror
        g.save(); g.globalAlpha = 0.85;
        if (src.mirror) { g.translate(view.ox + src.w * view.s, view.oy); g.scale(-view.s, view.s); } else { g.translate(view.ox, view.oy); g.scale(view.s, view.s); }
        g.drawImage(src.img, 0, 0, src.w, src.h); g.restore();
      }
      var n = src.map ? src.map.length / 3 : (src.lm ? src.lm.length : 0);
      for (i = 0; i < n; i++) {                                        // every point, faint: the shape of the face
        var p = xy(src, i); if (!p) continue;
        g.fillStyle = allPts ? 'rgba(161,161,170,0.9)' : src.img ? 'rgba(228,228,231,0.35)' : FAINT;
        g.beginPath(); g.arc(p[0], p[1], allPts ? 2.2 : 1.2, 0, 7); g.fill();
      }
      for (i = 0; i < n; i++) {
        if (!allPts && !named[i] && !pinned[i] && i !== sel) continue;
        var q = xy(src, i); if (!q) continue;
        var on = !!pinned[i], big = i === sel;
        if (allPts && !on && !big) continue;
        g.beginPath(); g.arc(q[0], q[1], big ? 9 : on ? 7 : 6, 0, 7);
        g.fillStyle = big || on ? CYAN : 'rgba(39,39,42,0.85)'; g.fill();
        g.lineWidth = 1.5; g.strokeStyle = big || on ? CYAN : INK; g.stroke();
      }
      if (sel != null) { var s = xy(src, sel); if (s) { g.fillStyle = INK; g.font = '600 13px system-ui, sans-serif'; g.textAlign = 'center'; g.fillText(fp.nameOf(sel), s[0], s[1] - 16); } }
    }
    function loop() { draw(); raf = mode === 'live' ? requestAnimationFrame(loop) : 0; }

    /** Leaving Live freezes the moment: the picture and its points, in memory only. */
    function freeze() {
      if (!cam.on || !cam.v || cam.v.readyState < 2 || !cam.marks) return;
      var c = document.createElement('canvas'); c.width = cam.v.videoWidth; c.height = cam.v.videoHeight;
      c.getContext('2d').drawImage(cam.v, 0, 0);
      fp._frozen = { img: c, lm: cam.marks.map(function (q) { return { x: q.x, y: q.y }; }), w: c.width, h: c.height, mirror: !cam.facing };
    }
    function setMode(m) {
      if (m === mode) return;
      if (mode === 'live' && m === 'pick') freeze();
      mode = fp._mode = m; sel = null; renderFoot(); stop(); loop();
    }

    function pick(e) {
      if (mode === 'live') return;
      var src = source(); if (!src) return;
      var r = cv.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top, best = null, bd = allPts ? 16 : 30, i, n = src.map ? src.map.length / 3 : (src.lm ? src.lm.length : 0);
      for (i = 0; i < n; i++) {
        if (!allPts && !named[i] && !pinned[i]) continue;
        var p = xy(src, i); if (!p) continue;
        var d = Math.hypot(p[0] - x, p[1] - y);
        if (d < bd) { bd = d; best = i; }
      }
      if (best == null) return;
      if (pinned[best] && sel !== best) { sel = best; renderFoot(); draw(); return; }   // a pinned point: show its pins
      stop(); app.closeFull(); fp.place(app, best);
    }
    cv.addEventListener('pointerup', pick);

    function pinRow(q) {
      var part = AL.node(ch, q.node);
      return h('div', { class: 'rowf', style: 'align-items:center;gap:6px;flex-wrap:wrap' }, [
        h('span', { class: 'note', style: 'margin:0;flex:1 1 100%;min-width:0', text: fp.nameOf(q.point) + ' → ' + (part ? part.name || 'a part' : 'a part') }),
        h('div', { style: 'flex:1;min-width:0' }, [ui.slider('Strength', { min: 0, max: 4, step: 0.05, value: q.amt == null ? 1 : q.amt, fmt: function (v) { return v.toFixed(2) + '×'; },
          title: 'How far this pin follows your face: 1 is as it moved, 2 twice as far, 0 not at all',
          set: function (v) { fp.strength(app, q.id, v); } })]),
        ui.chip('Remove', { icon: 'x', title: 'Take this face pin off (one Undo brings it back)', fn: function () { fp.remove(app, q.id); pinned = pinnedCount(); if (!pinned[sel]) sel = null; renderFoot(); draw(); } })
      ]);
    }
    function renderFoot() {
      foot.innerHTML = '';
      foot.appendChild(ui.row([
        ui.seg([['pick', 'Pick', 'Tap a point to pin it (coming back from Live keeps that moment of your face)'], ['live', 'Live', 'See the points ride on your face as you move']], mode, function (v) { setMode(v); }, 'Pick a point, or watch them live'),
        ui.toggle('All 478 points', allPts, function (v) { fp._all = v; allPts = v; draw(); }, null, 'Show every point the camera follows, not only the named ones'),
        mode === 'pick' && fp._frozen ? ui.chip('Plain map', { title: 'Drop the picture: the map of your neutral face', fn: function () { fp._frozen = null; renderFoot(); draw(); } }) : null
      ]));
      if (!cam.on) {
        foot.appendChild(ui.row([ui.chip('Turn on the face camera', { cls: 'go', icon: 'cam', title: 'Start the face camera here (the picture is never kept)', fn: function () {
          app.toast('Starting the face camera…');
          inp.camStart().then(function () { cam.mode = 'always'; app.toast('Look at the phone for a moment, relaxed: that is your neutral face.'); renderFoot(); setMode('live'); })
            .catch(function () { app.toast('The face camera could not start here. It needs a camera and the PC page.'); });
        } })]));
      }
      var list = fp.list(ch), mine = sel != null ? list.filter(function (q) { return q.point === sel; }) : list;
      foot.appendChild(ui.note(mode === 'live' ? 'Move your face and watch the points. Switch to <b>Pick</b> to freeze this moment and tap a point.'
        : sel != null ? '<b>' + fp.nameOf(sel) + '</b>: its pins. Tap another point to pin that one.'
        : 'Tap a point of <b>your face</b>, then tap the <b>character</b> where it should pull. ' + (list.length ? list.length + ' face pin' + (list.length > 1 ? 's' : '') + ' live; tap a cyan point to change or remove it.' : 'No face pins yet.')));
      if (mode === 'pick') mine.forEach(function (q) { foot.appendChild(pinRow(q)); });
    }
    renderFoot();
    requestAnimationFrame(loop);
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

  /** After a point is chosen: the next tap on the character says where it pulls. A banner says so, with Cancel. */
  fp.place = function (app, pt) {
    var st = document.getElementById('stage'), down = null;
    if (!st) return;
    var banner = h('div', { class: 'chip on', role: 'status',
      style: 'position:absolute;left:50%;top:10px;transform:translateX(-50%);z-index:30;display:flex;gap:10px;align-items:center;pointer-events:auto' }, [
      h('span', { text: 'Tap the character where ' + fp.nameOf(pt) + ' should pull' }),
      h('button', { class: 'chip', title: 'Do not pin it', onclick: function () { done(); app.toast('No face pin made.'); } }, 'Cancel')
    ]);
    (st.parentNode || document.body).appendChild(banner);
    function pos(e) { var r = st.getBoundingClientRect(), k = st.clientWidth / r.width; return { x: (e.clientX - r.left) * k, y: (e.clientY - r.top) * k }; }
    function swallow(e) { e.stopImmediatePropagation(); e.stopPropagation(); e.preventDefault(); }
    function onDown(e) { swallow(e); down = pos(e); }
    function onMove(e) { swallow(e); }
    function onUp(e) {
      swallow(e);
      var p = pos(e);
      if (!down || Math.hypot(p.x - down.x, p.y - down.y) > 14) { down = null; return; }
      down = null;
      if (fp.pinAt(app, pt, p)) done();
    }
    function done() {
      st.removeEventListener('pointerdown', onDown, true); st.removeEventListener('pointermove', onMove, true); st.removeEventListener('pointerup', onUp, true);
      if (banner.parentNode) banner.parentNode.removeChild(banner);
    }
    st.addEventListener('pointerdown', onDown, true); st.addEventListener('pointermove', onMove, true); st.addEventListener('pointerup', onUp, true);
  };

  /** Pin face point pt to the character at stage pixel p. One undo step. Returns true when it pinned. */
  fp.pinAt = function (app, pt, p) {
    var c = app.toChar(p.x, p.y), hit = app.out ? AL.hit(app.out, c[0], c[1]) : null;
    if (!hit) { app.toast('Nothing there. Tap on the character itself.'); return false; }
    var n = AL.node(app.ch, hit.id), m = n && app.out.M[n.id];
    if (!n || !m) { app.toast('That part cannot take a pin.'); return false; }
    if (AL.fit && AL.fit.can && !AL.fit.can(app, n)) { app.toast('Arms and legs follow the hands and feet. Try the head, the mouth, the brows or the cheeks.'); return false; }
    var l = M.ap(M.inv(m), c[0], c[1]);
    var made = false, pin = null, reach = 28 / Math.max(0.05, app.view.s);
    app.edit(function () {
      if (!n.mesh) n.mesh = { pins: [], quiet: true };
      n.mesh.pins = n.mesh.pins || [];
      if (!(n.shapes || []).some(function (q) { return q.id === 'fix'; })) (n.shapes = n.shapes || []).unshift({ id: 'fix', name: 'Reshaped', sig: 'always', amt: 1, d: {} });
      var bd = reach;
      n.mesh.pins.forEach(function (q) { var d = Math.hypot(q.x - l[0], q.y - l[1]); if (d < bd) { bd = d; pin = q; } });
      if (!pin) { pin = AL.fit.dropPin(app, n, l); made = true; }
      fp.bind(app.ch, n, pin, pt, made);
    }, false, 'Face pin');
    app.toast('Pinned: ' + fp.nameOf(pt) + ' pulls ' + (n.name || 'this part') + '. Move that part of your face to try it.');
    return true;
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
