/* AvatarLab: THE FRAME. Only a shape: it shows where the picture's edges are and tells export what to cut. It is not a mode.
 *
 * The frame button sits at the bottom right of the stage in Perform, beside the director's chair (al-scene-strip.js), and shows
 * the shape in brackets.  Tap: the frame on or off (on = solid cyan).  Long press: the shapes and the margins.
 * The frame has room round it on every side, so a character can go part-way out of the picture: above, below, to the sides.
 * It is a zinc-grey hairline with the stage a little darker outside it.
 * In a scene the shape is the scene's own. In THE EMPTY SCENE (no scene open: the plain stage, exactly as it was) it is kept for
 * the empty scene.
 *
 * THE EMPTY SCENE'S SHOTS. A scene has its camera (al-scene-take.js). The plain stage has its view, so here a shot moves that view:
 * Wide (the character fitted in the frame), Close on <character> (its head, and it follows), and views framed by hand and kept.
 * Shots need the frame: with the frame off the plain stage is fitted exactly as it always was.
 *
 * Phone: the frame is drawn on the stage's Compose Canvas; the button is a 36 dp IconButton with combinedClickable(onLongClick);
 * these few settings are a small JSON beside the room's other settings.
 */
(function (root) {
  'use strict';
  var AL = root.AL, app = AL.app, h = AL.h, frame = AL.frame = {}, scene = AL.scene, NS = 'http://www.w3.org/2000/svg';
  function $(id) { return document.getElementById(id); }
  function load(k, d) { try { var v = JSON.parse(localStorage.getItem(k) || 'null'); return v && typeof v === 'object' ? v : d; } catch (e) { return d; } }
  function save(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { } }

  /* what is kept between visits: the frame on or off, which parts of the strip are up, cut or glide; and the empty scene's shape and shots */
  var ROOM = frame.room = load('al.room', { frame: false, sc: false, ch: false, sh: false, glide: false });
  var E = frame.empty = load('al.empty', { shape: '16:9', safe: 'off', shots: {} }); if (!E.shots) E.shots = {}; E.shot = 'wide';
  frame.keep = function () { save('al.room', { frame: !!ROOM.frame, sc: !!ROOM.sc, ch: !!ROOM.ch, sh: !!ROOM.sh, glide: !!ROOM.glide }); save('al.empty', { shape: E.shape, safe: E.safe, shots: E.shots }); };
  function changed(app) { if (AL.strip) AL.strip.sync(app, true); }

  frame.on = function () { return !!ROOM.frame; };
  /** The frame on or off. Off: there are no shots, so the camera goes back to the whole frame and the Shots part folds away. */
  frame.set = function (app, on) {
    ROOM.frame = !!on;
    if (!on) { ROOM.sh = false; var R = app.scene; if (R) { R.shot = 'wide'; R.free = null; R.easing = null; R.cutNow = true; } E.shot = 'wide'; }
    frame.keep(); changed(app); frame.refit(app);
  };
  frame.shape = function (app) { return app.scene ? app.scene.data.shape : E.shape || '16:9'; };
  frame.safe = function (app) { return (app.scene ? app.scene.data.safe : E.safe) || 'off'; };
  frame.ar = function (app) { var s = frame.shape(app), ar = 16 / 9; scene.SHAPES.forEach(function (q) { if (q[0] === s) ar = q[1]; }); return ar; };
  frame.setShape = function (app, s) { if (app.scene) scene.shape(app, s); else { E.shape = s; frame.keep(); } changed(app); frame.refit(app); };
  frame.setSafe = function (app, v) { if (app.scene) scene.edit(app, 'Margins', function (d) { d.safe = v; }, true); else { E.safe = v; frame.keep(); } changed(app); };

  /* ── where it is on the glass ── */
  /** The room the picture has: the stage, less the hint line at the top and the strip and the round buttons at the bottom. */
  frame.area = function (app) {
    var st = $('stage'), w = st.clientWidth, hh = st.clientHeight, co = $('coach'), top = Math.max(46, co && co.offsetHeight ? co.offsetTop + co.offsetHeight + 6 : 0), bot = 8;
    ['strip', 'roombtns'].forEach(function (id) { var e = $(id); if (e && e.offsetHeight) bot = Math.max(bot, hh - e.offsetTop + 6); });
    return { x: 8, y: top, w: Math.max(40, w - 16), h: Math.max(80, hh - top - bot) };
  };
  frame.CUSHION = 0.06;
  /** The frame's rectangle in an area: as big as fits with room round it on every side (6% of its size, and 14 px more). */
  frame.fit = function (A, ar) {
    var c = frame.CUSHION, m = 14, fw = Math.max(24, Math.min((A.w - 2 * m) / (1 + 2 * c), (A.h - 2 * m) / (1 + 2 * c) * ar)), fh = fw / ar;
    return { x: A.x + (A.w - fw) / 2, y: A.y + (A.h - fh) / 2, w: fw, h: fh };
  };
  /** The frame on the plain stage (the empty scene), on the glass. */
  frame.plainRect = function (app) { return frame.fit(frame.area(app), frame.ar(app)); };

  /* ── drawing it ── */
  frame.dim = function (g, w, hh, F, fill) { g.fillStyle = fill; g.fillRect(0, 0, w, Math.max(0, F.y)); g.fillRect(0, F.y + F.h, w, Math.max(0, hh - F.y - F.h)); g.fillRect(0, F.y, Math.max(0, F.x), F.h); g.fillRect(F.x + F.w, F.y, Math.max(0, w - F.x - F.w), F.h); };
  /** The frame itself: a little darker outside, a zinc hairline, the margins when asked for, and its shape written quietly above it. */
  frame.paint = function (g, w, hh, F, safe, label) {
    g.save();
    frame.dim(g, w, hh, F, 'rgba(9,9,11,.34)');
    g.strokeStyle = 'rgba(161,161,170,.6)'; g.lineWidth = 1; g.strokeRect(Math.round(F.x) + 0.5, Math.round(F.y) + 0.5, Math.round(F.w) - 1, Math.round(F.h) - 1);
    if (safe === 'title') {
      g.setLineDash([5, 4]); g.strokeStyle = 'rgba(34,211,238,.7)'; g.strokeRect(F.x + F.w * 0.05, F.y + F.h * 0.05, F.w * 0.9, F.h * 0.9);
      g.strokeStyle = 'rgba(232,121,249,.75)'; g.strokeRect(F.x + F.w * 0.1, F.y + F.h * 0.1, F.w * 0.8, F.h * 0.8); g.setLineDash([]);
    } else if (safe === 'social') {                                  // where an app's own buttons and words sit over an upright video (about; every app differs)
      g.fillStyle = 'rgba(232,121,249,.14)'; g.fillRect(F.x, F.y, F.w, F.h * 0.12); g.fillRect(F.x, F.y + F.h * 0.78, F.w, F.h * 0.22); g.fillRect(F.x + F.w * 0.86, F.y + F.h * 0.12, F.w * 0.14, F.h * 0.66);
      g.setLineDash([5, 4]); g.strokeStyle = 'rgba(232,121,249,.75)'; g.strokeRect(F.x + 1, F.y + F.h * 0.12, F.w * 0.86 - 1, F.h * 0.66); g.setLineDash([]);
    }
    if (label) { g.font = '500 9.5px "IBM Plex Mono", ui-monospace, monospace'; g.fillStyle = 'rgba(161,161,170,.78)'; var up = F.y > 16; g.textBaseline = up ? 'bottom' : 'top'; g.fillText(label, F.x + 1, up ? F.y - 3 : F.y + 5); }
    g.restore();
  };
  /** The frame button's picture: the shape written inside brackets. */
  frame.glyph = function (shape) {
    var s = document.createElementNS(NS, 'svg'); s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('class', 'frg'); s.setAttribute('aria-hidden', 'true');
    var p = document.createElementNS(NS, 'path'); p.setAttribute('d', 'M2.5 9V5.5H6M18 5.5h3.5V9M21.5 15v3.5H18M6 18.5H2.5V15'); s.appendChild(p);
    var t = document.createElementNS(NS, 'text'); t.setAttribute('x', '12'); t.setAttribute('y', '14.4'); t.setAttribute('text-anchor', 'middle'); t.textContent = shape; s.appendChild(t);
    return s;
  };
  /** A shape as itself, for the list of shapes. */
  frame.shapePic = function (ar) {
    var s = document.createElementNS(NS, 'svg'), w = ar >= 1 ? 26 : 26 * ar, hh = ar >= 1 ? 26 / ar : 26; s.setAttribute('viewBox', '0 0 30 30'); s.setAttribute('class', 'frp'); s.setAttribute('aria-hidden', 'true');
    var r = document.createElementNS(NS, 'rect'); r.setAttribute('x', String(15 - w / 2)); r.setAttribute('y', String(15 - hh / 2)); r.setAttribute('width', String(w)); r.setAttribute('height', String(hh)); r.setAttribute('rx', '1.5'); s.appendChild(r);
    return s;
  };

  /* ── the long press: shapes and margins ── */
  frame.sheet = function (app) {
    var ui = app.ui, cur = frame.shape(app), body = h('div'), row = h('div', { class: 'frshapes' });
    body.appendChild(ui.toggle('Show the frame', frame.on(), function (v) { frame.set(app, v); frame.sheet(app); }, 'Where the picture\'s edges are. It only shows them: nothing on the stage moves.'));
    body.appendChild(ui.lab('The shape'));
    scene.SHAPES.forEach(function (q) { row.appendChild(h('button', { class: 'frsh' + (q[0] === cur ? ' on' : ''), title: q[0] + ': ' + q[2], 'aria-pressed': String(q[0] === cur), onclick: function () { frame.setShape(app, q[0]); if (!frame.on()) frame.set(app, true); frame.sheet(app); } }, [frame.shapePic(q[1]), h('span', { text: q[0] })])); });
    body.appendChild(row);
    body.appendChild(ui.lab('Margins'));
    body.appendChild(ui.row([ui.seg([['off', 'None', 'Just the frame'], ['title', 'Title safe', 'The classic margins: keep action inside the cyan line and words inside the pink one'], ['social', 'App buttons', 'About where an app lays its own buttons and words over an upright video. Every app differs: treat it as a guide.']], frame.safe(app), function (v) { frame.setSafe(app, v); if (!frame.on()) frame.set(app, true); frame.sheet(app); }, 'Safe margins')]));
    body.appendChild(ui.note('Changing the shape moves nothing: the frame is a window on the same stage. Export cuts the picture to it.' + (app.scene ? '' : ' This is the empty scene\'s frame; each scene keeps its own.')));
    app.sheet('The frame', body, [{ label: 'Done', cls: 'quiet', fn: app.closeSheet }]);
  };

  /* ── THE EMPTY SCENE: the plain stage's view fitted to the frame, and its shots ── */
  function plainPerform(app) { return !app.scene && app.mode === 'perform' && !!app.ch; }
  function stripUp() { var s = $('strip'); return !!(s && s.offsetHeight); }
  function into(b, A, pad) {
    var w = Math.max(8, b.x1 - b.x0), hh = Math.max(8, b.y1 - b.y0), z = app.stageSize ? app.stageSize() : { w: A.w, h: A.h }, full = app.ch ? Math.min(z.w / app.ch.w, z.h / app.ch.h) : 1;
    var s = Math.min((A.w - pad * 2) / w, (A.h - pad * 1.4) / hh, full * 5);
    return { s: s, ox: A.x + (A.w - w * s) / 2 - b.x0 * s, oy: A.y + (A.h - hh * s) / 2 - b.y0 * s };
  }
  /* In Perform on the plain stage, with the frame on the picture is fitted INTO the frame; with the strip up it is fitted above the
     strip. With neither it is the room's own fitting, untouched. */
  var fitTo0 = app.fitTo;
  app.fitTo = function (b, pad, snap) {
    if (!plainPerform(app) || !(ROOM.frame || stripUp())) return fitTo0(b, pad, snap);
    var v = into(b, ROOM.frame ? frame.plainRect(app) : frame.area(app), ROOM.frame ? 4 : pad);
    if (snap) { app.view = v; app.vt = null; } else app.vt = v;
  };
  function same(a, b) { return !!(a && b && Math.abs(a.s - b.s) < 1e-3 * Math.max(1, a.s) && Math.abs(a.ox - b.ox) < 0.6 && Math.abs(a.oy - b.oy) < 0.6); }
  function chKey(app) { var c = app.ch; return c ? (c.uid || 'stock:' + (c.demo || c.name)) : '-'; }
  function wide(app) { var keep = app.vt, uv = app.userView; app.userView = false; app.fit(false); var v = app.vt; app.vt = keep; app.userView = uv; return v || app.view; }
  function headBox(app) {
    var out = app.out, hd = AL.firstRole(app.ch.root, 'head') || AL.firstRole(app.ch.root, 'face'), ids = null, b = null; if (!out) return null;
    if (hd) { ids = {}; AL.walk(hd, function (k) { if (k.type === 'art') ids[k.id] = 1; }); }
    out.items.forEach(function (it) { if (it.ghost || (ids && !ids[it.id])) return; for (var i = 0; i < it.v.length; i += 2) { var x = it.v[i], y = it.v[i + 1]; if (!b) b = { x0: x, y0: y, x1: x, y1: y }; else { if (x < b.x0) b.x0 = x; if (x > b.x1) b.x1 = x; if (y < b.y0) b.y0 = y; if (y > b.y1) b.y1 = y; } } });
    if (b && !ids) b.y1 = b.y0 + (b.y1 - b.y0) * 0.45;                // nothing named the head: the top of it
    return b;
  }
  function mine(app) { return E.shots[chKey(app)] || (E.shots[chKey(app)] = []); }
  /** The shots on the plain stage: Wide, Close on the character, and the ones kept by hand. */
  frame.plainShots = function (app) {
    var l = [{ key: 'wide', name: 'Wide', kind: 'wide' }];
    if (app.ch) l.push({ key: 'close', name: app.ch.name, kind: 'close', pic: AL.shelf && AL.shelf.thumb ? thumbOf(app) : null });
    mine(app).forEach(function (s) { l.push({ key: s.id, name: s.name, kind: 'own' }); });
    return l;
  };
  var TH = { ch: null, url: null };
  function thumbOf(app) { if (TH.ch !== app.ch) { TH.ch = app.ch; try { TH.url = AL.shelf.thumb({ ch: app.ch }, 96); } catch (e) { TH.url = null; } } return TH.url; }
  frame.plainShot = function () { return E.shot; };
  function target(app, key) {
    var F = frame.plainRect(app), w0 = wide(app);
    if (key === 'close') {
      var b = headBox(app); if (!b) return w0; var hh = Math.max(20, b.y1 - b.y0), s = Math.max(w0.s * 1.15, Math.min(F.h / (hh * 2.3), F.w / ((b.x1 - b.x0) * 1.5))), cx = (b.x0 + b.x1) / 2, cy = (b.y0 + b.y1) / 2 + hh * 0.22;
      return { s: s, ox: F.x + F.w / 2 - cx * s, oy: F.y + F.h / 2 - cy * s };
    }
    var o = mine(app).filter(function (q) { return q.id === key; })[0];
    if (o) { var s2 = w0.s * o.k; return { s: s2, ox: F.x + F.w / 2 - o.cx * s2, oy: F.y + F.h / 2 - o.cy * s2 }; }
    return w0;
  }
  /** Go to a shot on the plain stage. ease = glide there (otherwise cut). Shots need the frame: it is turned on. */
  frame.goPlain = function (app, key, ease) {
    if (!app.ch) return false; if (!ROOM.frame) frame.set(app, true);
    if (key !== 'wide' && key !== 'close' && !mine(app).some(function (q) { return q.id === key; })) return false;
    E.shot = key; var v = target(app, key); E.tgt = v;
    if (ease) app.vt = v; else { app.view = { s: v.s, ox: v.ox, oy: v.oy }; app.vt = null; }
    app.userView = key !== 'wide'; E.last = { s: app.view.s, ox: app.view.ox, oy: app.view.oy }; changed(app);
    return true;
  };
  /** Keep the view as it is now as a shot of the plain stage (for this character). */
  frame.keepPlain = function (app, name) {
    var F = frame.plainRect(app), w0 = wide(app), V = app.view, l = mine(app), s = { id: AL.id('h'), name: name || 'Shot ' + (l.length + 1), cx: Math.round((F.x + F.w / 2 - V.ox) / V.s * 10) / 10, cy: Math.round((F.y + F.h / 2 - V.oy) / V.s * 10) / 10, k: Math.round(V.s / w0.s * 1000) / 1000 };
    l.push(s); frame.keep(); E.shot = s.id; E.tgt = { s: V.s, ox: V.ox, oy: V.oy }; changed(app); return s;
  };
  frame.renamePlain = function (app, id, name) { mine(app).forEach(function (q) { if (q.id === id && name) q.name = name; }); frame.keep(); changed(app); };
  frame.reframePlain = function (app, id) { var F = frame.plainRect(app), w0 = wide(app), V = app.view; mine(app).forEach(function (q) { if (q.id !== id) return; q.cx = Math.round((F.x + F.w / 2 - V.ox) / V.s * 10) / 10; q.cy = Math.round((F.y + F.h / 2 - V.oy) / V.s * 10) / 10; q.k = Math.round(V.s / w0.s * 1000) / 1000; }); E.shot = id; frame.keep(); changed(app); };
  frame.dropPlain = function (app, id) { E.shots[chKey(app)] = mine(app).filter(function (q) { return q.id !== id; }); if (E.shot === id) frame.goPlain(app, 'wide', 0); frame.keep(); changed(app); };
  frame.plainShotName = function (app) { if (E.shot === 'free') return 'By hand'; var n = 'Wide'; frame.plainShots(app).forEach(function (s) { if (s.key === E.shot) n = s.kind === 'close' ? 'Close on ' + s.name : s.name; }); return n; };
  /** Fit again after the frame, its shape or the strip changed (a shot stays the shot it was; a view framed by hand goes back to Wide). */
  frame.refit = function (app) {
    if (!plainPerform(app)) return;
    setTimeout(function () { if (!plainPerform(app)) return; if (ROOM.frame && E.shot !== 'wide' && E.shot !== 'free') frame.goPlain(app, E.shot, 0.5); else { E.shot = 'wide'; app.userView = false; app.fit(false); E.tgt = null; } }, 0);
  };

  /* every frame on the plain stage in Perform: the frame on the glass, a close-up keeping up, and a view moved by hand is "By hand" */
  app.hooks.push(function () {
    if (!plainPerform(app) || !ROOM.frame) { E.last = null; return; }
    var ov = $('ov'), st = $('stage'), w = st.clientWidth, hh = st.clientHeight, dpr = Math.min(2.5, root.devicePixelRatio || 1), g = ov.getContext('2d');
    g.save(); g.setTransform(dpr, 0, 0, dpr, 0, 0); frame.paint(g, w, hh, frame.plainRect(app), E.safe, E.shape + '  ' + frame.plainShotName(app).toUpperCase()); g.restore();
    var V = app.view;
    if (E.shot === 'wide' && !app.userView) E.tgt = wide(app);
    if (E.last && !app.vt && E.shot !== 'free' && !same(V, E.last) && !same(V, E.tgt)) { E.shot = 'free'; changed(app); }      // a pinch, a pan, the wheel: framed by hand
    if (E.shot === 'close') { E.tgt = target(app, 'close'); app.vt = E.tgt; }
    E.last = { s: V.s, ox: V.ox, oy: V.oy };
  });

  /* ── a picture of the frame (export) ── */
  var XG = null;
  /** One drawing surface for pictures that leave the room, made once (a page may only hold a few). */
  frame.gl = function (w, hh) { if (!XG) { var c = document.createElement('canvas'); XG = { c: c, G: new AL.GL(c) }; } XG.c.width = w; XG.c.height = hh; return XG; };
  /** The output size of one frame: 1920 on its long side. */
  frame.outSize = function (app) { var ar = frame.ar(app); return ar >= 1 ? { w: 1920, h: Math.round(1920 / ar) } : { w: Math.round(1920 * ar), h: 1920 }; };
  /** The plain stage inside its frame, as a picture (the character as it is drawn right now; see-through behind it). */
  frame.plainPicture = function (app, w, hh) {
    if (!app.out) return null;
    var F = frame.plainRect(app), k = w / F.w, V = app.view, X = frame.gl(w, hh);
    X.G.keep = false; X.G.draw(app.out.items.filter(function (it) { return !it.ghost; }), { s: V.s * k, ox: (V.ox - F.x) * k, oy: (V.oy - F.y) * k }, null, app.out.colour);
    var c = document.createElement('canvas'); c.width = w; c.height = hh; c.getContext('2d').drawImage(X.c, 0, 0); return c;
  };
  /** The plain dark stage behind a picture, as on the glass. */
  frame.ground = function (g, w, hh) { var r = g.createRadialGradient(w * 0.5, hh * 0.38, 0, w * 0.5, hh * 0.38, Math.max(w, hh) * 0.75); r.addColorStop(0, '#1a1a22'); r.addColorStop(0.62, '#0d0d10'); r.addColorStop(1, '#050507'); g.fillStyle = r; g.fillRect(0, 0, w, hh); };
})(typeof self !== 'undefined' ? self : this);
