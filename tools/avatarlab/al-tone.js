/* AvatarLab: colour sets.
 *
 * A COLOUR SET repaints the character at the press of a button: a chameleon changing, a blush, night colours,
 * a team strip. It is a short lookup, "this colour becomes that colour", by HUE: every green turns coral, the
 * yellow belly stays yellow, the red marks turn purple. Greys, blacks and whites are left alone, so eyes,
 * claws and outlines keep. Nothing is repainted and no second set of art is needed.
 *
 *   ch.palettes = [{ id, name, trig, time, soft, blotch, stops: [{ from, to, sat, val, width }] }]
 *     from / to   hue in degrees        sat / val   how much more colour, how much lighter (1 = the same)
 *     width       how far either side of `from` the stop reaches, in degrees
 *
 * FROM A PICTURE: give it a picture of the character in its other colours and the lookup is worked out by
 * itself: the main colours of the art and of the picture are found, biggest first, and paired off.
 *
 * The change arrives as a wave with a blotchy front (al-gl.js). Each set has a button; sets take turns.
 */
(function (root) {
  'use strict';
  var AL = root.AL, tone = AL.tone = {};
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function hsv(r, g, b) { var mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn, h = 0; if (d > 0) { if (mx === r) h = ((g - b) / d) % 6; else if (mx === g) h = (b - r) / d + 2; else h = (r - g) / d + 4; h *= 60; if (h < 0) h += 360; } return [h, mx ? d / mx : 0, mx / 255]; }
  function hdist(a, b) { var d = Math.abs(a - b) % 360; return d > 180 ? 360 - d : d; }
  tone.css = function (h, s, v) { return 'hsl(' + Math.round(((h % 360) + 360) % 360) + ',' + Math.round((s == null ? 0.7 : s) * 100) + '%,' + Math.round((v == null ? 0.5 : v) * 100) + '%)'; };
  tone.find = function (ch, id) { var ps = ch.palettes || []; for (var i = 0; i < ps.length; i++) if (ps[i].id === id) return ps[i]; return null; };

  /** The colour of the ART under a point on the stage (as painted, whatever colour set is showing). Null on nothing. */
  tone.colourAt = function (out, x, y) {
    for (var n = out.items.length - 1; n >= 0; n--) {
      var it = out.items[n]; if (it.ghost || (it.a0 != null ? it.a0 : it.a) < 0.3) continue;
      var v = it.v, idx = it.idx, uv = it.uv, im = AL.img.get(it.img); if (!im) continue;
      for (var t = 0; t < idx.length; t += 3) {
        var a = idx[t] * 2, b = idx[t + 1] * 2, c = idx[t + 2] * 2, x0 = v[a], y0 = v[a + 1], x1 = v[b], y1 = v[b + 1], x2 = v[c], y2 = v[c + 1];
        var den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2); if (Math.abs(den) < 1e-9) continue;
        var l0 = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / den, l1 = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / den, l2 = 1 - l0 - l1;
        if (l0 < 0 || l1 < 0 || l2 < 0) continue;
        var px = clamp(Math.floor((l0 * uv[a] + l1 * uv[b] + l2 * uv[c]) * im.w), 0, im.w - 1), py = clamp(Math.floor((l0 * uv[a + 1] + l1 * uv[b + 1] + l2 * uv[c + 1]) * im.h), 0, im.h - 1);
        var d = im.canvas.getContext('2d').getImageData(px, py, 1, 1).data; if (d[3] < 40) break;
        var q = hsv(d[0], d[1], d[2]); return { h: q[0], s: q[1], v: q[2], rgb: [d[0], d[1], d[2]] };
      }
    }
    return null;
  };

  /* ── the main colours of a picture: a histogram of hue, weighted by how colourful and how solid each pixel is ── */
  function histNew() { return { n: new Float64Array(72), s: new Float64Array(72), v: new Float64Array(72), all: 0 }; }
  function histAdd(H, data, step) {
    for (var i = 0; i < data.length; i += 4 * (step || 1)) {
      var al = data[i + 3]; if (al < 128) continue;
      var q = hsv(data[i], data[i + 1], data[i + 2]); if (q[1] < 0.16 || q[2] < 0.14) continue;             // greys, blacks: not a colour to change
      if (q[1] < 0.2 && q[2] > 0.9) continue;                                                               // a near-white background
      var w = q[1] * q[2], bn = Math.floor(q[0] / 5) % 72; H.n[bn] += w; H.s[bn] += q[1] * w; H.v[bn] += q[2] * w; H.all += w;
    }
  }
  function peaks(H) {
    var sm = new Float64Array(72), i, k, list = [], K = [1, 2, 3, 2, 1];
    for (i = 0; i < 72; i++) for (k = -2; k <= 2; k++) sm[i] += H.n[(i + k + 72) % 72] * K[k + 2];
    for (i = 0; i < 72; i++) {
      if (sm[i] <= 0 || sm[i] < sm[(i + 71) % 72] || sm[i] <= sm[(i + 1) % 72]) continue;
      var m = 0, s = 0, v = 0, hx = 0, hy = 0;
      for (k = -3; k <= 3; k++) { var j = (i + k + 72) % 72, w = H.n[j]; m += w; s += H.s[j]; v += H.v[j]; hx += Math.cos((j * 5 + 2.5) * Math.PI / 180) * w; hy += Math.sin((j * 5 + 2.5) * Math.PI / 180) * w; }
      if (m > H.all * 0.004) list.push({ h: (Math.atan2(hy, hx) * 180 / Math.PI + 360) % 360, m: m, s: s / m, v: v / m });
    }
    list.sort(function (a, b) { return b.m - a.m; });
    var keep = []; list.forEach(function (p) { if (keep.length < 6 && !keep.some(function (q) { return hdist(q.h, p.h) < 24; })) keep.push(p); });
    return keep;
  }
  tone.ofCharacter = function (ch) {
    var H = histNew();
    AL.walk(ch.root, function (n) { if (n.hidden) return false; if (n.type !== 'art') return; var im = AL.img.get(n.img); if (!im || !im.w || !im.h) return; histAdd(H, im.canvas.getContext('2d').getImageData(0, 0, im.w, im.h).data, 2); });
    return peaks(H);
  };
  tone.ofCanvas = function (cv) { var H = histNew(); histAdd(H, cv.getContext('2d').getImageData(0, 0, cv.width, cv.height).data, 1); return peaks(H); };
  /** The character as it stands at rest, painted small, to compare with a picture of it. */
  function restPicture(ch, size) {
    var gc = document.createElement('canvas'); gc.width = gc.height = size; var G = new AL.GL(gc); if (!G.ok) return null;
    var out = new AL.Solver(ch).eval(AL.restSignals(), {}), k = size / Math.max(ch.w, ch.h);
    G.draw(out.items, { s: k, ox: (size - ch.w * k) / 2, oy: (size - ch.h * k) / 2 });
    var cv = document.createElement('canvas'); cv.width = cv.height = size; cv.getContext('2d').drawImage(gc, 0, 0); G.forget(); return cv;
  }
  /** The part of a picture that is the character: everything solid that is not a pale, plain background. */
  function figure(cv) {
    var d = cv.getContext('2d').getImageData(0, 0, cv.width, cv.height).data, x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1, x, y;
    function solid(i) { if (d[i + 3] < 128) return false; var mx = Math.max(d[i], d[i + 1], d[i + 2]), mn = Math.min(d[i], d[i + 1], d[i + 2]); return !(mx > 235 && mx - mn < 14); }
    for (y = 0; y < cv.height; y++) for (x = 0; x < cv.width; x++) if (solid((y * cv.width + x) * 4)) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    return x1 < x0 ? null : { d: d, w: cv.width, x0: x0, y0: y0, x1: x1, y1: y1, solid: solid };
  }
  /**
   * The lookup that turns this character's colours into the picture's.
   * The picture is laid over the character (figure to figure), and every spot votes: "here the art is this colour and the
   * picture is that colour". Each main colour of the art then becomes whatever most of its spots voted for. So the green
   * body goes to the picture's body colour and the yellow belly to its belly colour, even when the pose is not quite the same.
   * Where the two cannot be compared, the main colours are paired off biggest to biggest instead.
   */
  tone.fromPicture = function (ch, cv) {
    var A = tone.ofCharacter(ch), B = tone.ofCanvas(cv), stops = [], N = 96, rc = restPicture(ch, 256), fa = rc ? figure(rc) : null, fb = figure(cv);
    var votes = [], i, j;
    for (i = 0; i < 72; i++) votes.push({ n: new Float64Array(72), s: 0, v: 0, m: 0 });
    if (fa && fb) for (j = 0; j < N; j++) for (i = 0; i < N; i++) {
      var ax = Math.round(fa.x0 + (fa.x1 - fa.x0) * (i + 0.5) / N), ay = Math.round(fa.y0 + (fa.y1 - fa.y0) * (j + 0.5) / N), bx = Math.round(fb.x0 + (fb.x1 - fb.x0) * (i + 0.5) / N), by = Math.round(fb.y0 + (fb.y1 - fb.y0) * (j + 0.5) / N);
      var ia = (ay * fa.w + ax) * 4, ib = (by * fb.w + bx) * 4; if (!fa.solid(ia) || !fb.solid(ib)) continue;
      var qa = hsv(fa.d[ia], fa.d[ia + 1], fa.d[ia + 2]), qb = hsv(fb.d[ib], fb.d[ib + 1], fb.d[ib + 2]); if (qa[1] < 0.16 || qa[2] < 0.14 || qb[1] < 0.14 || qb[2] < 0.14) continue;
      var w = qa[1] * qa[2], vt = votes[Math.floor(qa[0] / 5) % 72]; vt.n[Math.floor(qb[0] / 5) % 72] += w; vt.s += (qb[1] / Math.max(0.05, qa[1])) * w; vt.v += (qb[2] / Math.max(0.05, qa[2])) * w; vt.m += w;
    }
    A.forEach(function (pk, idx) {
      /* every vote cast by spots near this colour of the art */
      var tally = new Float64Array(72), m = 0, sr = 0, vr = 0, k, c;
      for (k = -3; k <= 3; k++) { var vt = votes[(Math.floor(pk.h / 5) + k + 72) % 72]; for (c = 0; c < 72; c++) tally[c] += vt.n[c]; m += vt.m; sr += vt.s; vr += vt.v; }
      var to = null, sat = 1, val = 1;
      if (m > 4) {
        var best = 0, bi = 0; for (c = 0; c < 72; c++) { var t = tally[(c + 71) % 72] + 2 * tally[c] + tally[(c + 1) % 72]; if (t > best) { best = t; bi = c; } }
        var hx = 0, hy = 0; for (k = -2; k <= 2; k++) { var cc = (bi + k + 72) % 72; hx += Math.cos((cc * 5 + 2.5) * Math.PI / 180) * tally[cc]; hy += Math.sin((cc * 5 + 2.5) * Math.PI / 180) * tally[cc]; }
        to = (Math.atan2(hy, hx) * 180 / Math.PI + 360) % 360; sat = sr / m; val = vr / m;
        var tot = 0, nearSum = 0; for (c = 0; c < 72; c++) tot += tally[c]; for (k = -3; k <= 3; k++) nearSum += tally[(bi + k + 72) % 72]; if (nearSum < tot * 0.3) to = null;          // its spots could not agree: leave this colour alone
      } else if (B[idx]) { to = B[idx].h; sat = B[idx].s / Math.max(0.05, pk.s); val = B[idx].v / Math.max(0.05, pk.v); }
      if (to == null) return;
      var near = 90; A.forEach(function (q) { if (q !== pk) near = Math.min(near, hdist(q.h, pk.h)); });
      if (hdist(to, pk.h) < 6 && Math.abs(sat - 1) < 0.12 && Math.abs(val - 1) < 0.12) return;          // this colour stays as it is: no stop needed
      stops.push({ from: Math.round(pk.h), to: Math.round(to), sat: Math.round(clamp(sat, 0.6, 1.7) * 100) / 100, val: Math.round(clamp(val, 0.7, 1.4) * 100) / 100, width: Math.round(clamp(near, 14, 60)) });
    });
    return stops.slice(0, 8);
  };
  tone.add = function (ch, name, stops) {
    var t = { id: AL.id('t'), name: name }, p = { id: AL.id('c'), name: name, trig: t.id, time: 0.9, soft: 0.22, blotch: 0.12, stops: stops || [] };
    (ch.triggers = ch.triggers || []).push(t); (ch.palettes = ch.palettes || []).push(p); return p;
  };
  tone.remove = function (ch, p) { ch.palettes = (ch.palettes || []).filter(function (q) { return q !== p; }); ch.triggers = (ch.triggers || []).filter(function (t) { return t.id !== p.trig; }); };
  function loadCanvas(src, done) { var im = new Image(); im.onload = function () { var k = Math.min(1, 400 / Math.max(im.width, im.height)), cv = document.createElement('canvas'); cv.width = Math.max(1, Math.round(im.width * k)); cv.height = Math.max(1, Math.round(im.height * k)); cv.getContext('2d').drawImage(im, 0, 0, cv.width, cv.height); done(cv); }; im.onerror = function () { done(null); }; im.src = src; }
  /** Make a colour set from a picture file or address. */
  tone.fromUrl = function (app, src, name, done) {
    loadCanvas(src, function (cv) {
      if (!cv) { app.toast('That picture could not be opened.'); if (done) done(null); return; }
      var stops = tone.fromPicture(app.ch, cv), made = null;
      if (!stops.length) { app.toast('No clear colours were found in that picture.'); if (done) done(null); return; }
      app.edit(function () { made = tone.add(app.ch, name, stops); }, true, 'Colours from a picture');
      app.toneTry = made.id; app.toneAmt = null; app.refreshPanel(true); if (done) done(made);
    });
  };

  /* ── the card ── */
  function swatch(h, s, v) { return AL.h('span', { style: 'display:inline-block;width:22px;height:22px;border-radius:7px;flex:none;border:1px solid rgba(255,255,255,.18);background:' + tone.css(h, s == null ? 0.72 : s, v == null ? 0.5 : v) }); }
  tone.section = function (app, pb) {
    var ui = app.ui, h = AL.h, ch = app.ch, list = ch.palettes || [], ed = app.toneTry ? tone.find(ch, app.toneTry) : null;
    pb.appendChild(ui.lab('Colour sets', 'fit'));
    if (!list.length) pb.appendChild(ui.note('Another set of colours for the same art, changed at the press of a button: a chameleon, a blush, night. Give it a picture of the character in its other colours and the change is worked out for you.'));
    var row = list.map(function (p) { return ui.chip(p.name, { on: ed === p, title: ed === p ? 'Back to its own colours' : 'Show this colour set and work on it', fn: function () { app.toneTry = ed === p ? null : p.id; app.toneAmt = null; app.eyedrop = null; app.refreshPanel(true); } }); });
    /* a colour set's button shows its colours: the three it changes most */
    row.forEach(function (c, i) { var sw = h('span', { class: 'sw' }); (list[i].stops || []).slice(0, 3).forEach(function (q) { var d = h('i'); d.style.background = 'hsl(' + Math.round(q.to) + ',' + Math.round(Math.min(1, 0.72 * (q.sat || 1)) * 100) + '%,' + Math.round(Math.min(0.7, 0.5 * (q.val || 1)) * 100) + '%)'; sw.appendChild(d); }); if (sw.children.length) c.insertBefore(sw, c.firstChild); });
    row.push(ui.chip('From a picture…', { icon: 'wand', cls: list.length ? '' : 'go', title: 'Choose a picture of the character in other colours. Its main colours are found and matched to this art, biggest to biggest.', fn: function () {
      var inp = h('input', { type: 'file', accept: 'image/png,image/webp,image/jpeg' }); inp.onchange = function () { var f = inp.files && inp.files[0]; if (!f) return; var url = URL.createObjectURL(f); tone.fromUrl(app, url, 'Colours ' + (list.length + 1), function () { URL.revokeObjectURL(url); }); }; inp.click();
    } }));
    row.push(ui.chip('Add by hand', { icon: 'plus', title: 'Start an empty colour set, then tap a colour on the character to change it', fn: function () { app.promptText('Name this colour set', 'Colours ' + (list.length + 1), function (v) { var made; app.edit(function () { made = tone.add(ch, v, []); }, true, 'Add a colour set'); app.toneTry = made.id; app.refreshPanel(true); }); } }));
    pb.appendChild(ui.row(row));
    if (!ed) return;
    pb.appendChild(ui.slider('See it', { min: 0, max: 100, value: Math.round((app.toneAmt == null ? 1 : app.toneAmt) * 100), undo: false, set: function (v) { app.toneAmt = v / 100; }, fmt: function (v) { return v >= 100 ? 'changed' : v <= 0 ? 'its own' : v + '%'; }, title: 'Slide to watch the colours sweep across' }));
    ed.stops.forEach(function (q, i) {
      pb.appendChild(h('div', { class: 'rowf', style: 'margin-top:8px;gap:8px' }, [
        swatch(q.from), h('span', { class: 'note', style: 'margin:0', text: 'becomes' }), swatch(q.to, clamp(0.72 * (q.sat == null ? 1 : q.sat), 0.1, 1), clamp(0.5 * (q.val == null ? 1 : q.val), 0.15, 0.8)),
        ui.chip('Pick on the picture', { on: app.eyedrop && app.eyedropAt === i, icon: 'pin', title: 'Then tap the colour on the character that this row should change', fn: function () { app.eyedropAt = i; app.eyedrop = function (c) { if (!c || c.s < 0.12) { app.toast('That is a grey, a black or a white: those are left alone. Tap a colour.'); app.refreshPanel(true); return; } app.edit(function () { q.from = Math.round(c.h); }, false, 'Pick a colour'); }; app.toast('Tap the colour on the character.'); app.refreshPanel(true); } }),
        h('button', { class: 'ib', title: 'Remove this colour from the set', onclick: function () { app.edit(function () { ed.stops.splice(i, 1); }, false, 'Remove a colour'); } }, AL.icon('x'))
      ]));
      pb.appendChild(ui.slider('Becomes', { min: 0, max: 359, value: Math.round(((q.to % 360) + 360) % 360), set: function (v) { q.to = v; }, done: function () { app.refreshPanel(true); }, fmt: function (v) { return ['red', 'orange', 'yellow', 'lime', 'green', 'teal', 'cyan', 'sky', 'blue', 'violet', 'magenta', 'pink'][Math.round(v / 30) % 12]; }, title: 'The colour it turns into' }));
      pb.appendChild(ui.slider('Reaches', { min: 8, max: 90, value: q.width == null ? 40 : q.width, set: function (v) { q.width = v; }, fmt: function (v) { return v < 22 ? 'only that colour' : v > 55 ? 'its neighbours too' : 'colours near it'; }, title: 'How far from the picked colour this change spreads' }));
      pb.appendChild(ui.slider('More colour', { min: 30, max: 200, step: 5, value: Math.round((q.sat == null ? 1 : q.sat) * 100), set: function (v) { q.sat = v / 100; }, fmt: function (v) { return v + '%'; } }));
      pb.appendChild(ui.slider('Lighter', { min: 50, max: 160, step: 5, value: Math.round((q.val == null ? 1 : q.val) * 100), set: function (v) { q.val = v / 100; }, fmt: function (v) { return v + '%'; } }));
    });
    pb.appendChild(ui.row([ed.stops.length < 8 ? ui.chip('Another colour', { icon: 'plus', title: 'Tap a colour on the character; it joins this set and you choose what it becomes', fn: function () { app.eyedropAt = -1; app.eyedrop = function (c) { if (!c || c.s < 0.12) { app.toast('Tap a colour (greys, blacks and whites are left alone).'); app.refreshPanel(true); return; } app.edit(function () { ed.stops.push({ from: Math.round(c.h), to: Math.round((c.h + 180) % 360), sat: 1, val: 1, width: 30 }); }, false, 'Another colour'); }; app.toast('Tap the colour on the character.'); } }) : null]));
    pb.appendChild(ui.lab('How it arrives', 'fit'));
    pb.appendChild(ui.slider('It takes', { min: 0.1, max: 4, step: 0.1, value: ed.time == null ? 0.9 : ed.time, set: function (v) { ed.time = v; }, fmt: function (v) { return v.toFixed(1) + ' s'; } }));
    pb.appendChild(ui.slider('Edge of the wave', { min: 2, max: 100, value: Math.round((ed.soft == null ? 0.22 : ed.soft) * 100), set: function (v) { ed.soft = v / 100; }, fmt: function (v) { return v < 12 ? 'sharp' : v > 60 ? 'all at once' : 'soft'; }, title: 'A sharp line crossing the character, or a soft one, or the whole thing melting together' }));
    pb.appendChild(ui.slider('Blotchy', { min: 0, max: 40, value: Math.round((ed.blotch == null ? 0.12 : ed.blotch) * 100), set: function (v) { ed.blotch = v / 100; }, fmt: function (v) { return v ? v + '%' : 'smooth' ; }, title: 'How ragged the front of the wave is: skin changing in patches' }));
    pb.appendChild(ui.slider('It sweeps', { min: 0, max: 345, step: 15, value: ch.settings.toneDir == null ? 25 : ch.settings.toneDir, set: function (v) { ch.settings.toneDir = v; }, fmt: function (v) { return ['left to right', 'down and right', 'top to bottom', 'down and left', 'right to left', 'up and left', 'bottom to top', 'up and right'][Math.round(v / 45) % 8]; }, title: 'Which way the change crosses the character' }));
    pb.appendChild(ui.row([
      ui.chip('Rename', { cls: 'quiet', fn: function () { app.promptText('Name this colour set', ed.name, function (v) { app.edit(function () { var t = (ch.triggers || []).filter(function (x) { return x.id === ed.trig; })[0]; if (t) t.name = v; ed.name = v; }, false, 'Rename a colour set'); }); } }),
      ui.chip('Remove', { cls: 'quiet', icon: 'trash', title: 'Take this colour set away. Undo brings it back.', fn: function () { app.edit(function () { tone.remove(ch, ed); app.toneTry = null; }, false, 'Remove a colour set'); } }),
      ui.chip('Done', { cls: 'go', fn: function () { app.toneTry = null; app.eyedrop = null; app.refreshPanel(true); } })
    ]));
    pb.appendChild(ui.note('Its button is in Perform. A part can be left out: open it and switch on <b>Keeps its own colours</b>.'));
  };
  /** On any part's card: leave this part out of every colour set. */
  tone.partToggle = function (app, pb, n) {
    if (!(app.ch.palettes || []).length) return;
    pb.appendChild(app.ui.toggle('Keeps its own colours', !!n.noTone, function (v) { app.edit(function () { if (v) n.noTone = true; else delete n.noTone; }, false, 'Keeps its colours'); }, 'On: colour sets leave this part alone'));
  };
})(typeof self !== 'undefined' ? self : this);
