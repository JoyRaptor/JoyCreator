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

  /** The setup: your face map, the pins there are, a strength slider each. */
  fp.open = function (app) {
    var ui = app.ui, ch = app.ch, cal = calibration(app), F = AL.face;
    var full = app.full('Face pins', function () { app.refreshPanel(true); });
    if (!F || !cal || !cal.points) {
      full.body.appendChild(h('div', { style: 'padding:16px' }, [ui.note('Turn on <b>Face</b> (in Perform) and look at the phone for a moment, relaxed. ' +
        'Your face map is made from your own face, then you can pin any point of it to the character.')]));
      return;
    }
    var side = (app.inp.cam && app.inp.cam.swap ? -1 : 1) * (cal.sign && cal.sign.lean ? cal.sign.lean : -1);
    var pts = cal.points, n = pts.length / 3, allPts = !!fp._all, sel = null;
    var cv = h('canvas', { 'aria-label': 'Your face map: tap a point' });
    full.body.appendChild(cv);
    var named = {}; (F.NAMED || []).forEach(function (q) { named[q[0]] = q; });
    var pinned = {}; fp.list(ch).forEach(function (q) { pinned[q.point] = (pinned[q.point] || 0) + 1; });
    var view = { s: 1, ox: 0, oy: 0 };
    function xy(i) { return [view.ox + pts[i * 3] * side * view.s, view.oy + pts[i * 3 + 1] * view.s]; }
    function fit() {
      var r = cv.getBoundingClientRect(), dpr = Math.min(2.5, root.devicePixelRatio || 1), x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9, i;
      cv.width = Math.max(1, r.width * dpr); cv.height = Math.max(1, r.height * dpr);
      for (i = 0; i < n; i++) { var x = pts[i * 3] * side, y = pts[i * 3 + 1]; x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
      view.s = Math.min((r.width - 40) / (x1 - x0), (r.height - 40) / (y1 - y0));
      view.ox = r.width / 2 - (x0 + x1) / 2 * view.s; view.oy = r.height / 2 - (y0 + y1) / 2 * view.s;
      view.dpr = dpr;
    }
    function draw() {
      fit();
      var g = cv.getContext('2d'), i; g.setTransform(view.dpr, 0, 0, view.dpr, 0, 0); g.clearRect(0, 0, cv.width, cv.height);
      for (i = 0; i < n; i++) {                                   // every point, faint: the shape of your face
        var p = xy(i); g.fillStyle = allPts ? GREY : FAINT; g.beginPath(); g.arc(p[0], p[1], allPts ? 2.2 : 1.2, 0, 7); g.fill();
      }
      var show = allPts ? null : named;
      for (i = 0; i < n; i++) {
        if (show && !show[i] && !pinned[i]) continue;
        var q = xy(i), on = pinned[i], big = i === sel;
        if (!show && !on && !big) continue;
        g.beginPath(); g.arc(q[0], q[1], big ? 9 : on ? 7 : 6, 0, 7);
        g.fillStyle = big || on ? CYAN : '#27272a'; g.fill();
        g.lineWidth = 1.5; g.strokeStyle = big || on ? CYAN : GREY; g.stroke();
      }
      if (sel != null) { var s = xy(sel); g.fillStyle = INK; g.font = '600 13px system-ui, sans-serif'; g.textAlign = 'center'; g.fillText(fp.nameOf(sel), s[0], s[1] - 16); }
    }
    function pick(e) {
      var r = cv.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top, best = null, bd = allPts ? 16 : 30, i;
      for (i = 0; i < n; i++) {
        if (!allPts && !named[i] && !pinned[i]) continue;
        var p = xy(i), d = Math.hypot(p[0] - x, p[1] - y);
        if (d < bd) { bd = d; best = i; }
      }
      if (best == null) return;
      sel = best; draw();
      app.closeFull();
      fp.place(app, best);
    }
    cv.addEventListener('pointerup', pick);
    requestAnimationFrame(draw);

    var foot = full.foot;
    foot.appendChild(ui.note('Tap a point of <b>your face</b>, then tap the <b>character</b> where it should pull. ' +
      (fp.list(ch).length ? fp.list(ch).length + ' face pin' + (fp.list(ch).length > 1 ? 's' : '') + ' live.' : 'No face pins yet.')));
    foot.appendChild(ui.row([ui.toggle('All 478 points', allPts, function (v) { fp._all = v; allPts = v; draw(); }, null,
      'Show every point the camera follows, not only the named ones')]));
    fp.list(ch).forEach(function (q) {
      var part = AL.node(ch, q.node);
      foot.appendChild(h('div', { class: 'rowf', style: 'align-items:center;gap:6px' }, [
        h('span', { class: 'note', style: 'margin:0;flex:1;min-width:0', text: fp.nameOf(q.point) + ' → ' + (part ? part.name || 'a part' : 'a part') }),
        ui.slider('Strength', { min: 0, max: 4, step: 0.05, value: q.amt == null ? 1 : q.amt, fmt: function (v) { return v.toFixed(2) + '×'; },
          title: 'How far this pin follows your face: 1 is as it moved, 2 twice as far, 0 not at all',
          set: function (v) { fp.strength(app, q.id, v); } }),
        h('button', { class: 'ib', title: 'Remove this face pin', onclick: function () { fp.remove(app, q.id); app.closeFull(); fp.open(app); } }, AL.icon('x'))
      ]));
    });
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
    app.toast('Pinned: ' + fp.nameOf(pt) + ' pulls ' + (n.name || 'this part') + '.');
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
      { id: 'fp:' + id + ':x', name: 'Face pin: ' + name + ' (across)', sig: 'pt.' + pt + '.x', amt: 1, d: dx, face: id },
      { id: 'fp:' + id + ':y', name: 'Face pin: ' + name + ' (up and down)', sig: 'pt.' + pt + '.y', amt: 1, d: dy, face: id });
    (ch.facePins = ch.facePins || []).push({ id: id, point: pt, node: n.id, pin: pin.id, amt: 1, made: !!made });
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
