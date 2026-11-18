/* AvatarLab: getting art in and getting characters out.
 * Four doors, because artists keep their parts four ways: a layered file, loose pictures, one sheet
 * with the parts laid out, or one finished drawing. All four end in the same character tree.
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h;
  function $(id) { return document.getElementById(id); }
  var intake = AL.intake = {};
  var ZL = { head: 'Head', body: 'Body', armL: 'Left arm', armR: 'Right arm', legL: 'Left leg', legR: 'Right leg', tail: 'Tail', extra: 'Extras' };

  function readBuf(file) { return new Promise(function (res, rej) { var r = new FileReader(); r.onload = function () { res(r.result); }; r.onerror = function () { rej(r.error); }; r.readAsArrayBuffer(file); }); }
  function readText(file) { return new Promise(function (res, rej) { var r = new FileReader(); r.onload = function () { res(r.result); }; r.onerror = function () { rej(r.error); }; r.readAsText(file); }); }
  function loadCanvas(file) {
    return new Promise(function (res, rej) {
      var url = URL.createObjectURL(file), im = new Image();
      im.onload = function () { var c = document.createElement('canvas'); c.width = im.naturalWidth; c.height = im.naturalHeight; c.getContext('2d', { willReadFrequently: true }).drawImage(im, 0, 0); URL.revokeObjectURL(url); res(c); };
      im.onerror = function () { rej(new Error('That picture would not open.')); }; im.src = url;
    });
  }
  /** Cut away the empty border. Returns the picture and where it sat. */
  function trim(c, name) {
    var g = c.getContext('2d', { willReadFrequently: true }), d = g.getImageData(0, 0, c.width, c.height).data, x0 = c.width, y0 = c.height, x1 = -1, y1 = -1;
    for (var y = 0; y < c.height; y++) for (var x = 0; x < c.width; x++) if (d[(y * c.width + x) * 4 + 3] > 10) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    if (x1 < 0) return null;
    var t = document.createElement('canvas'); t.width = x1 - x0 + 1; t.height = y1 - y0 + 1; t.getContext('2d').drawImage(c, -x0, -y0);
    return { canvas: t, name: name, ox: x0, oy: y0, fullW: c.width, fullH: c.height };
  }
  function nice(name) { return name.replace(/\.[a-z0-9]+$/i, '').replace(/[_]+/g, ' ').trim(); }

  /* ── saving a file ── */
  /* Inside a Claude artifact a page may not start a download by itself: it asks the viewer through the
     "downloads" capability. On the plain local page it is an ordinary download. */
  intake.hosted = !!(root.claude && root.claude.use);
  var dlReady = intake.hosted ? root.claude.use('downloads').catch(function () { return null; }) : Promise.resolve(null);
  intake.saveFile = function (name, blob) {
    var app = AL.app;
    if (intake.saveHook) return Promise.resolve(intake.saveHook(name, blob));
    return dlReady.then(function (dl) {
      if (dl) return dl.save({ filename: name, data: blob }).then(function () { app.toast('Saved “' + name + '”'); }, function (e) { app.toast(e && e.code === 'declined' ? 'Not saved.' : 'Saving a file is not available in this view. Open the page in a browser.'); });
      if (intake.hosted) { app.toast('Saving a file is not available in this view.'); return; }
      var a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name; document.body.appendChild(a); a.click(); a.remove(); setTimeout(function () { URL.revokeObjectURL(a.href); }, 4000);
      app.toast('Saved “' + name + '” to your downloads');
    });
  };
  intake.saveCharacter = function (app) {
    var o = AL.toJson(app.ch, true); if (app.inp.take && app.inp.take.n) o.take = app.inp.take;
    intake.saveFile((app.ch.name || 'character').replace(/[^\w\- ]+/g, '') + '.avatar.json', new Blob([JSON.stringify(o)], { type: 'application/json' }));
  };

  /* ── a layered file ── */
  intake.openPsd = function (app, buf, name) {
    var old = AL.img.map; AL.img.map = {};
    return AL.importPsd(buf, name).then(function (doc) { var ch = AL.autoRig(doc); app.setMode('build'); app.setCharacter(ch); intake.review(app, doc.layers); }).catch(function (e) { AL.img.map = old; throw e; });
  };
  intake.openJson = function (app, text) {
    var o = JSON.parse(text); if (!o || !o.root) throw new Error('This is not a character file.');
    var take = o.take; delete o.take; AL.img.map = {};
    return AL.fromJson(o).then(function (ch) { app.setMode('build'); app.setCharacter(ch); if (take) app.inp.take = take; app.toast('Opened “' + ch.name + '”'); });
  };
  intake.openUrl = function (app, url) {
    return fetch(url).then(function (r) { if (!r.ok) throw new Error('not found'); return /\.json$/i.test(url) ? r.text().then(function (t) { return intake.openJson(app, t); }) : r.arrayBuffer().then(function (b) { return intake.openPsd(app, b, nice(url.split('/').pop())); }); });
  };
  intake.review = function (app, layerCount) {
    var d = AL.describe(app.ch), ch = app.ch, body = h('div'), ui = app.ui;
    body.appendChild(ui.note('Read <b>' + (layerCount || '') + ' layers</b> and rigged them from their names. Nothing was placed by hand.'));
    var list = h('div', { class: 'menu' });
    function line(label, text, zone, feature) { list.appendChild(h('button', { title: 'Open ' + label, onclick: function () { app.closeSheet(); app.tab = 'parts'; app.select(zone, feature); } }, [h('span', { class: 'badge ok', text: '✓' }), h('span', { text: label }), h('small', { text: text })])); }
    if (d.head) line('Head', d.head, 'head');
    var cv = app.curView(); if (cv && cv.node) { var fe = Object.keys(app.FEAT).filter(function (f) { return app.featureNodes(f, cv.node).length; }); if (fe.indexOf('eyes') >= 0) line('Eyes', 'pupils follow, blink by themselves', 'head', 'eyes'); if (fe.indexOf('mouth') >= 0) { var m = app.featureNodes('mouth', cv.node)[0], a = m.type === 'set' ? AL.node(ch, m.pick.rest) : m; line('Mouth', m.pick && m.pick.by === 'viseme' ? m.kids.length + ' shapes, picked by the voice' : a && a.hinge ? 'talks on a hinge' : a && a.squash ? 'talks by squashing' : 'swaps', 'head', 'mouth'); } if (fe.indexOf('brows') >= 0) line('Brows', 'lift', 'head', 'brows'); }
    if (d.armR) line('Right arm', d.armR, 'armR'); if (d.armL) line('Left arm', d.armL, 'armL');
    if (d.body) line('Body', d.body, 'body'); if (d.legR || d.legL) line('Legs', 'planted', d.legR ? 'legR' : 'legL'); if (d.tail) line('Tail', d.tail, 'tail');
    var ex = AL.cards.extras(app).length; if (ex) list.appendChild(h('button', { onclick: function () { app.closeSheet(); app.select('extra'); } }, [h('span', { class: 'badge warn', text: '?' }), h('span', { text: 'Not placed' }), h('small', { text: ex + ' layers: say what they are' })]));
    if (ch.triggers.length) list.appendChild(h('button', { onclick: function () { app.closeSheet(); app.select('extra'); } }, [h('span', { class: 'badge', text: '⚡' }), h('span', { text: 'Buttons' }), h('small', { text: ch.triggers.map(function (t) { return t.name; }).join(', ') })]));
    body.appendChild(list);
    body.appendChild(ui.note('The <b>pins</b> (shoulder, elbow, hand, hinge) were guessed from the pixels. Have a look at the arms first; drag any pin that is off.'));
    app.sheet('Here is what I found', body, [{ label: 'Check the arms', cls: 'quiet', fn: function () { app.closeSheet(); app.select(d.armL ? 'armL' : d.armR ? 'armR' : 'head'); } }, { label: 'Perform it', cls: 'go', icon: 'play', fn: function () { app.closeSheet(); app.setMode('perform'); } }]);
  };

  /* ── where a new part goes ── */
  function sockets(app) {
    var ch = app.ch, t = app.zoneNode('body'), b = t ? AL.boundsOf(t) : null;
    if (!b) b = { x0: ch.w * 0.35, x1: ch.w * 0.65, y0: ch.h * 0.42, y1: ch.h * 0.78 };
    var w = b.x1 - b.x0, hh = b.y1 - b.y0, cx = (b.x0 + b.x1) / 2;
    return { body: { x: cx, y: (b.y0 + b.y1) / 2 }, head: { x: cx, y: b.y0 + hh * 0.06 }, armL: { x: b.x1 - w * 0.14, y: b.y0 + hh * 0.2 }, armR: { x: b.x0 + w * 0.14, y: b.y0 + hh * 0.2 },
      legL: { x: cx + w * 0.2, y: b.y1 - hh * 0.1 }, legR: { x: cx - w * 0.2, y: b.y1 - hh * 0.1 }, tail: { x: b.x1 - w * 0.12, y: b.y1 - hh * 0.22 }, extra: { x: cx, y: b.y0 - hh * 0.2 } };
  }
  var RANK = { armL: 0, armR: 0, extra: 1, head: 1, body: 2, legL: 3, legR: 3, tail: 4 };
  function insertByRank(ch, node, zone) {
    var kids = ch.root.kids, at = kids.length;
    for (var i = 0; i < kids.length; i++) { var kz = AL.zoneOf(ch, kids[i].id); if ((RANK[kz] == null ? 1 : RANK[kz]) > RANK[zone]) { at = i; break; } }
    kids.splice(at, 0, node);
  }
  /** Add pictures as the art of one body part. Must run inside app.edit(). */
  intake.place = function (app, zone, pics) {
    var ch = app.ch, P = AL._px, first = !AL.find(ch.root, function (n) { return n.type === 'art'; });
    if (first) { var big = Math.max(pics[0].canvas.width, pics[0].canvas.height); ch.h = ch.w = Math.max(256, Math.round(zone === 'body' ? pics[0].canvas.height / 0.38 : big * 2.4)); }
    var so = sockets(app), s = so[zone] || so.extra, made = [];
    pics.forEach(function (p, i) {
      var id = AL.id('i'); AL.img.put(id, p.canvas, p.name);
      var art = { id: AL.id('n'), name: p.name || (ZL[zone] + ' ' + (i + 1)), type: 'art', img: id, x: 0, y: 0 }, w = p.canvas.width, hh = p.canvas.height;
      var aligned = p.fullW === ch.w && p.fullH === ch.h && !first;
      if (aligned) { art.x = p.ox; art.y = p.oy; }
      else if (zone === 'armL' || zone === 'armR' || zone === 'tail') {
        var e = P.plainEndFirst(art) || [{ x: w / 2, y: 0 }, { x: w / 2, y: hh }], a = zone === 'tail' ? e[1] : e[0];   // an arm joins at its plain end, a tail at its thick end
        art.x = Math.round(s.x - a.x); art.y = Math.round(s.y - a.y);
      } else if (zone === 'head') { art.x = Math.round(s.x - w / 2); art.y = Math.round(s.y - hh * 0.94); }
      else if (zone === 'legL' || zone === 'legR') { art.x = Math.round(s.x - w / 2); art.y = Math.round(s.y - hh * 0.08); }
      else { art.x = Math.round(s.x - w / 2); art.y = Math.round(s.y - hh / 2); }
      made.push(art);
    });
    if (zone === 'armL' || zone === 'armR') {
      var set = app.zoneNode(zone);
      if (!set) { set = { id: AL.id('n'), name: ZL[zone], type: 'set', role: zone, kids: [] }; insertByRank(ch, set, zone); }
      made.forEach(function (a) { set.kids.push(a); }); AL.reindex(ch); AL.applyRole(ch, set, zone);
    } else if (zone === 'head') {
      var hd = app.zoneNode('head');
      if (!hd) { hd = { id: AL.id('n'), name: 'Head', type: 'set', role: 'head', kids: [], pick: { by: 'turn', depth: 12, fade: 110 } }; insertByRank(ch, hd, 'head'); }
      if (hd.type !== 'set') { var old = { id: AL.id('n'), name: 'Front', type: 'group', kids: hd.kids || [], at: [0, 0] }; hd.type = 'set'; hd.kids = [old]; hd.pick = { by: 'turn', depth: 12, fade: 110, rest: old.id }; }
      made.forEach(function (a, i) {
        var at = P.viewPos(a.name), n = hd.kids.length; if (!at || hd.kids.some(function (k) { return k.at && k.at[0] === at[0] && k.at[1] === at[1]; })) at = n === 0 ? [0, 0] : [[-1, 0], [1, 0], [0, -1], [0, 1], [-1, -1], [1, -1], [-1, 1], [1, 1]][(n - 1) % 8];
        a.role = 'face'; hd.kids.push({ id: AL.id('n'), name: a.name, type: 'group', at: at, kids: [a] }); void i;
      });
      if (!hd.pick.rest) hd.pick.rest = hd.kids[0].id;
      AL.reindex(ch); AL.applyRole(ch, hd, 'head');
    } else made.forEach(function (a) { insertByRank(ch, a, zone); AL.reindex(ch); AL.applyRole(ch, a, zone === 'body' ? 'torso' : zone === 'extra' ? 'prop' : zone); });
    AL.reindex(ch);
    return made;
  };
  intake.addPictures = function (app, files, zone) {
    return Promise.all(Array.prototype.map.call(files, function (f) { return loadCanvas(f).then(function (c) { return trim(c, nice(f.name)); }); })).then(function (pics) {
      pics = pics.filter(Boolean); if (!pics.length) throw new Error('Those pictures were empty.');
      app.edit(function () {
        if (zone) intake.place(app, zone, pics);
        else pics.forEach(function (p) { var r = AL._px.roleOf(p.name, null, null), z = r && AL.ROLES[r] ? AL.ROLES[r].zone : 'extra'; intake.place(app, z, [p]); });
      });
      $('empty').style.display = 'none'; app.tab = 'parts'; app.userView = false; app.fit(true); app.select(zone || app.sel.zone || 'extra');
      app.toast(pics.length + (pics.length === 1 ? ' drawing' : ' drawings') + ' added' + (zone ? ' to the ' + ZL[zone].toLowerCase() : ''));
    });
  };
  function pickImages(cb, many) { var f = $('fileImg'); f.multiple = many !== false; f.value = ''; f.onchange = function () { if (f.files.length) cb(f.files); }; f.click(); }

  /* ── one sheet with the parts laid out ── */
  intake.cutSheet = function (app, zone) {
    pickImages(function (files) {
      loadCanvas(files[0]).then(function (c) { cutter(app, c, zone, nice(files[0].name)); }).catch(function (e) { app.toast(e.message); });
    }, false);
  };
  intake.cutter = cutter;
  function cutter(app, sheet, zone0, name) {
    var W = sheet.width, H = sheet.height, sg = sheet.getContext('2d', { willReadFrequently: true }), id = sg.getImageData(0, 0, W, H), d = id.data, i, any = false;
    for (i = 3; i < d.length; i += 4 * 97) if (d[i] < 250) { any = true; break; }
    if (!any) { /* no see-through background: knock out the colour of the corner */
      var r0 = d[0], g0 = d[1], b0 = d[2];
      for (i = 0; i < d.length; i += 4) if (Math.abs(d[i] - r0) + Math.abs(d[i + 1] - g0) + Math.abs(d[i + 2] - b0) < 46) d[i + 3] = 0;
      sg.putImageData(id, 0, 0);
    }
    var f = Math.max(1, Math.ceil(Math.max(W, H) / 560)), lw = Math.ceil(W / f), lh = Math.ceil(H / f), mask = new Uint8Array(lw * lh), lab = new Int32Array(lw * lh), x, y;
    for (y = 0; y < H; y += 1) for (x = 0; x < W; x += 1) if (d[(y * W + x) * 4 + 3] > 40) mask[((y / f) | 0) * lw + ((x / f) | 0)] = 1;
    var comps = [], stack = [];
    for (i = 0; i < mask.length; i++) {
      if (!mask[i] || lab[i]) continue;
      var cid = comps.length + 1, c = { id: cid, x0: lw, y0: lh, x1: 0, y1: 0, n: 0, sel: 0, used: false }; stack.push(i); lab[i] = cid;
      while (stack.length) {
        var p = stack.pop(), px = p % lw, py = (p / lw) | 0; c.n++; if (px < c.x0) c.x0 = px; if (px > c.x1) c.x1 = px; if (py < c.y0) c.y0 = py; if (py > c.y1) c.y1 = py;
        for (var dy = -2; dy <= 2; dy++) for (var dx = -2; dx <= 2; dx++) { var qx = px + dx, qy = py + dy; if (qx < 0 || qy < 0 || qx >= lw || qy >= lh) continue; var q = qy * lw + qx; if (mask[q] && !lab[q]) { lab[q] = cid; stack.push(q); } }
      }
      comps.push(c);
    }
    comps = comps.filter(function (c) { return c.n >= 14; });
    var ui = app.ui, full = app.full('Cut parts from “' + name + '”'), cv = h('canvas'), order = 0, zone = zone0 || 'head';
    full.body.appendChild(cv);
    var noteEl = ui.note(''), zsel = h('select', { 'aria-label': 'Which part', onchange: function (e) { zone = e.target.value; paint(); } }, Object.keys(ZL).map(function (z) { return h('option', { value: z, text: ZL[z], selected: z === zone }); }));
    var go = ui.chip('Add', { cls: 'go', icon: 'plus', fn: add });
    full.foot.appendChild(noteEl); full.foot.appendChild(h('div', { class: 'rowf' }, [h('span', { class: 'note', style: 'margin:0', text: 'These are the' }), zsel, h('span', { style: 'flex:1' }), ui.chip('Done', { cls: 'quiet', fn: function () { app.closeFull(); app.refreshPanel(); } }), go]));
    var view = { s: 1, ox: 0, oy: 0 };
    function paint() {
      var w = full.body.clientWidth, hh = full.body.clientHeight, dpr = root.devicePixelRatio || 1; cv.width = w * dpr; cv.height = hh * dpr;
      var g = cv.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, w, hh);
      view.s = Math.min((w - 24) / W, (hh - 24) / H); view.ox = (w - W * view.s) / 2; view.oy = (hh - H * view.s) / 2;
      g.drawImage(sheet, view.ox, view.oy, W * view.s, H * view.s);
      comps.forEach(function (c) {
        var rx = view.ox + c.x0 * f * view.s - 3, ry = view.oy + c.y0 * f * view.s - 3, rw = (c.x1 - c.x0 + 1) * f * view.s + 6, rh = (c.y1 - c.y0 + 1) * f * view.s + 6;
        g.lineWidth = c.sel ? 2.5 : 1.2; g.setLineDash(c.sel ? [] : [4, 4]); g.strokeStyle = c.used ? 'rgba(113,113,122,.5)' : c.sel ? '#22d3ee' : 'rgba(228,228,231,.55)'; g.beginPath(); g.roundRect(rx, ry, rw, rh, 6); g.stroke(); g.setLineDash([]);
        if (c.sel) { g.fillStyle = '#22d3ee'; g.beginPath(); g.arc(rx + 2, ry + 2, 10, 0, 7); g.fill(); g.fillStyle = '#03181c'; g.font = '700 11px sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(String(c.sel), rx + 2, ry + 3); }
        if (c.used) { g.fillStyle = 'rgba(13,13,16,.55)'; g.fillRect(rx, ry, rw, rh); }
      });
      var n = comps.filter(function (c) { return c.sel; }).length;
      noteEl.innerHTML = 'Found <b>' + comps.length + ' parts</b>. Tap every drawing of one body part' + (zone === 'armL' || zone === 'armR' ? ' (an arm at several angles is fine: it will swap between them)' : '') + ', then <b>Add</b>.';
      go.lastChild.textContent = n ? 'Add ' + n + ' to ' + ZL[zone].toLowerCase() : 'Add'; go.disabled = !n;
    }
    cv.addEventListener('click', function (e) {
      var r = cv.getBoundingClientRect(), k = cv.clientWidth / r.width, px = ((e.clientX - r.left) * k - view.ox) / view.s / f, py = ((e.clientY - r.top) * k - view.oy) / view.s / f, hit = null;
      comps.forEach(function (c) { if (!c.used && px >= c.x0 - 1 && px <= c.x1 + 1 && py >= c.y0 - 1 && py <= c.y1 + 1 && (!hit || c.n < hit.n)) hit = c; });
      if (!hit) return; if (hit.sel) { hit.sel = 0; } else hit.sel = ++order; paint();
    });
    function add() {
      var chosen = comps.filter(function (c) { return c.sel; }).sort(function (a, b) { return a.sel - b.sel; }); if (!chosen.length) return;
      var pics = chosen.map(function (c, n) {
        var x0 = c.x0 * f, y0 = c.y0 * f, w = Math.min(W - x0, (c.x1 - c.x0 + 1) * f), hh = Math.min(H - y0, (c.y1 - c.y0 + 1) * f), t = document.createElement('canvas'); t.width = w; t.height = hh;
        var tg = t.getContext('2d', { willReadFrequently: true }); tg.drawImage(sheet, -x0, -y0); var td = tg.getImageData(0, 0, w, hh), q = td.data;
        for (var yy = 0; yy < hh; yy++) for (var xx = 0; xx < w; xx++) if (lab[(((y0 + yy) / f) | 0) * lw + (((x0 + xx) / f) | 0)] !== c.id) q[(yy * w + xx) * 4 + 3] = 0;
        tg.putImageData(td, 0, 0); return trim(t, ZL[zone] + ' ' + (n + 1));
      }).filter(Boolean);
      app.edit(function () { intake.place(app, zone, pics); }, true);
      chosen.forEach(function (c) { c.sel = 0; c.used = true; }); order = 0; $('empty').style.display = 'none';
      app.toast(pics.length + ' added to the ' + ZL[zone].toLowerCase() + '. Pick the next part, or Done.');
      if (zone0) { app.closeFull(); app.tab = 'parts'; app.fit(true); app.select(zone); } else { app.sel.zone = zone; paint(); }
    }
    setTimeout(paint, 30);
  }

  /* ── one finished drawing: tap where the head, hands and feet are ── */
  intake.wholeDrawing = function (app) {
    pickImages(function (files) {
      loadCanvas(files[0]).then(function (c) { intake.wholeFrom(app, c, nice(files[0].name)); }).catch(function (e) { app.toast(e.message); });
    }, false);
  };
  intake.wholeFrom = function (app, c, name) {
        var p = trim(c, name); if (!p) throw new Error('That picture was empty.');
        var ch = AL.newCharacter(p.name, Math.round(p.canvas.width * 1.7), Math.round(p.canvas.height * 1.35)); AL.img.map = {};
        var id = AL.id('i'); AL.img.put(id, p.canvas, p.name);
        var art = { id: AL.id('n'), name: p.name, type: 'art', role: 'torso', img: id, x: Math.round((ch.w - p.canvas.width) / 2), y: Math.round((ch.h - p.canvas.height) / 2), mesh: { pins: [] } };
        ch.root.kids.push(art); ch.settings.breathe = false; ch.settings.idle = false; AL.reindex(ch);
        app.setMode('build'); app.setCharacter(ch); intake.pinWizard(app, art);
  };
  var STEPS = [['head', 'Head', 'the middle of the head'], ['handL', 'Left hand', 'its left hand (on your right if it faces you)'], ['handR', 'Right hand', 'its right hand'], ['hips', 'Hips', 'the hips. This pin holds the body still'], ['footL', 'Left foot', 'its left foot'], ['footR', 'Right foot', 'its right foot'], ['tail', 'Tail tip', 'the tip of the tail, if it has one']];
  intake.pinWizard = function (app, art) {
    var im = AL.img.get(art.img), ui = app.ui, full = app.full('Pin “' + art.name + '”'), cv = h('canvas'), pins = [], step = 0, view = { s: 1, ox: 0, oy: 0 };
    full.body.appendChild(cv);
    var noteEl = ui.note(''), bar = h('div', { class: 'rowf' });
    full.foot.appendChild(noteEl); full.foot.appendChild(bar);
    function paint() {
      var w = full.body.clientWidth, hh = full.body.clientHeight, dpr = root.devicePixelRatio || 1; cv.width = w * dpr; cv.height = hh * dpr;
      var g = cv.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, w, hh);
      view.s = Math.min((w - 40) / im.w, (hh - 40) / im.h); view.ox = (w - im.w * view.s) / 2; view.oy = (hh - im.h * view.s) / 2;
      g.drawImage(im.canvas, view.ox, view.oy, im.w * view.s, im.h * view.s);
      g.font = '600 11px sans-serif'; g.textBaseline = 'middle';
      pins.forEach(function (p) { var x = view.ox + p.lx * view.s, y = view.oy + p.ly * view.s; g.beginPath(); g.arc(x, y, 9, 0, 7); g.fillStyle = 'rgba(3,24,28,.85)'; g.fill(); g.beginPath(); g.arc(x, y, 7, 0, 7); g.fillStyle = '#22d3ee'; g.fill(); var tw = g.measureText(p.name).width; g.fillStyle = 'rgba(13,13,16,.8)'; g.beginPath(); g.roundRect(x + 12, y - 10, tw + 12, 20, 10); g.fill(); g.fillStyle = '#e4e4e7'; g.fillText(p.name, x + 18, y); });
      var s = STEPS[step];
      noteEl.innerHTML = s ? 'Tap <b>' + s[2] + '</b>.' : '<b>' + pins.length + ' pins.</b> That is enough to puppeteer it. Add more any time.';
      bar.innerHTML = '';
      if (pins.length) bar.appendChild(ui.chip('Back', { cls: 'quiet', icon: 'back', fn: function () { pins.pop(); step = Math.max(0, step - 1); paint(); } }));
      if (s) bar.appendChild(ui.chip('Skip ' + s[1].toLowerCase(), { cls: 'quiet', fn: function () { step++; paint(); } }));
      bar.appendChild(h('span', { style: 'flex:1' }));
      bar.appendChild(ui.chip('Done', { cls: 'go', disabled: pins.length < 2, title: pins.length < 2 ? 'Place at least two pins' : 'Use these pins', fn: finish }));
    }
    cv.addEventListener('click', function (e) {
      var s = STEPS[step]; if (!s) return;
      var r = cv.getBoundingClientRect(), k = cv.clientWidth / r.width, lx = ((e.clientX - r.left) * k - view.ox) / view.s, ly = ((e.clientY - r.top) * k - view.oy) / view.s;
      if (lx < 0 || ly < 0 || lx > im.w || ly > im.h) return;
      pins.push({ id: AL.id('p'), name: s[1], role: s[0], lx: lx, ly: ly }); step++; paint();
    });
    function finish() {
      app.edit(function () { art.mesh = { pins: pins.map(function (p) { return { id: p.id, name: p.name, role: p.role, x: art.x + p.lx, y: art.y + p.ly }; }) }; });
      app.closeFull(); app.tab = 'parts'; app.select('body'); app.setMode('perform'); app.toast('Pinned. Drag any pin to move it; double-tap empty space to let go.');
    }
    setTimeout(paint, 30);
  };

  /* ── the first screen: a dotted body with a plus on every part ── */
  intake.emptyPanel = function (app) {
    var ph = $('panelhead'), pb = $('panelbody'), e = $('empty'), ui = app.ui;
    e.innerHTML = ''; e.appendChild(AL.cards.bodyMap(app, true));
    ph.innerHTML = ''; ph.appendChild(h('div', { class: 'ptitle' }, [h('h2', { text: 'Start a character' }), h('p', { text: 'Tap a + on the body to add that part, or bring a whole character in one go.' })]));
    app.coach('<b>New character.</b> Tap a <b>+</b> to add art for that part.');
    pb.appendChild(doors(app));
  };
  function door(icon, title, sub, fn, plain) { return h('button', { class: 'door' + (plain ? ' plain' : ''), title: title, onclick: fn }, [h('span', { class: 'g' }, AL.icon(icon)), h('span', null, [h('b', { text: title }), h('small', { text: sub })])]); }
  function doors(app) {
    return h('div', { class: 'doors' }, [
      door('layers', 'Open a layered file', 'A .psd with folders named Head, Left Arm, Mouth, Pupil, Blink… It rigs itself from the names.', function () { app.closeSheet(); $('filePsd').value = ''; $('filePsd').click(); }),
      door('cut', 'Cut parts from a sheet', 'One picture with the parts laid out. Tap each part and say what it is.', function () { app.closeSheet(); intake.cutSheet(app, null); }),
      door('pin', 'Pin one whole drawing', 'A finished drawing in one piece. Tap the head, hands and feet and it bends.', function () { app.closeSheet(); intake.wholeDrawing(app); }),
      door('smile', 'Meet Pip', 'A practice character that is already rigged. Poke at it.', function () { app.closeSheet(); AL.img.map = {}; app.setMode('build'); app.setCharacter(AL.demo()); }, true)
    ]);
  }
  intake.addTo = function (app, zone) {
    var body = h('div'), ui = app.ui;
    body.appendChild(ui.note(zone === 'armL' || zone === 'armR' ? 'One drawing bends from shoulder and elbow. <b>Several drawings at different angles</b> are better: it swaps to the nearest and bends the rest of the way.' : zone === 'head' ? 'One drawing, or one for each direction it looks (name them Front, Left, Up…). Mouth, eyes and brows can be added on top.' : zone === 'tail' ? 'It will swing by itself.' : 'Pick the art for this part.'));
    body.appendChild(h('div', { class: 'doors' }, [
      door('image', 'Pictures', 'One file for each drawing (PNG with a see-through background).', function () { app.closeSheet(); pickImages(function (files) { intake.addPictures(app, files, zone).catch(function (e) { app.toast(e.message); }); }); }),
      door('cut', 'From a sheet', 'Tap the drawings of this part on one big picture.', function () { app.closeSheet(); intake.cutSheet(app, zone); })
    ]));
    app.sheet('Add: ' + ZL[zone], body, []);
  };

  intake.menu = function (app) {
    var m = h('div', { class: 'menu' });
    function item(icon, label, fn, hint) { m.appendChild(h('button', { title: label, onclick: function () { app.closeSheet(); fn(); } }, [AL.icon(icon), h('span', { text: label }), hint ? h('small', { text: hint }) : null])); }
    item('plus', 'New character', function () { app.ask('Start a new character?', 'This one is put away. Save it first if you want to keep it.', 'Start new', function () { AL.img.map = {}; app.setMode('build'); app.setCharacter(AL.newCharacter('New character', 512, 512)); }); });
    item('layers', 'Open a layered file', function () { $('filePsd').value = ''; $('filePsd').click(); }, '.psd');
    item('cut', 'Cut parts from a sheet', function () { intake.cutSheet(app, null); });
    item('pin', 'Pin one whole drawing', function () { intake.wholeDrawing(app); });
    item('image', 'Add pictures', function () { pickImages(function (files) { intake.addPictures(app, files, null).catch(function (e) { app.toast(e.message); }); }); }, 'named by part');
    m.appendChild(h('hr'));
    item('file', 'Open a character file', function () { $('fileJson').value = ''; $('fileJson').click(); }, '.avatar.json');
    item('save', 'Save this character', function () { intake.saveCharacter(app); }, '.avatar.json');
    item('send', 'Send to Studio', function () { intake.send(app); });
    m.appendChild(h('hr'));
    item('smile', 'Meet Pip, the practice character', function () { AL.img.map = {}; app.setMode('build'); app.setCharacter(AL.demo()); });
    item('help', 'What can this do?', function () { intake.about(app); });
    app.sheet('Avatar Studio', m, []);
  };
  intake.about = function (app) {
    app.sheet('What Avatar Studio does', h('div', null, [
      app.ui.note('<b>Build</b> a character from a layered file, a sheet, loose pictures or one whole drawing. The body map shows what you have; each part has a card that says what it does.'),
      app.ui.note('<b>Heads</b> turn through as many angles as you drew, and missing sides can be mirrored. <b>Arms</b> swap between drawings and bend to reach. <b>Mouths</b> talk on a hinge, by squashing, or by swapping shapes to the voice. <b>Eyes</b> follow and blink. <b>Tails</b> swing. The <b>chest</b> breathes.'),
      app.ui.note('<b>Perform</b> by dragging the character itself, talking, and tapping buttons. Record in passes: a second pass only replaces what you touch.'),
      app.ui.note('This is the web pilot. Everything here is planned for the phone; the pilot is where the design gets settled first.')
    ]), [{ label: 'Close', cls: 'quiet', fn: app.closeSheet }]);
  };

  /* ── out to the rest of Joy Creator ── */
  intake.send = function (app) {
    var has = app.inp.take && app.inp.take.n, ui = app.ui;
    app.sheet('Send to Studio', h('div', null, [
      ui.note('On the phone this drops <b>' + app.ch.name + '</b> into your video as one layer' + (has ? ', with the take you just recorded' : '') + '. You can still grab the head and hands there.'),
      ui.note('In this web pilot it saves the same character file the phone will read, or a sprite sheet that Sprite Lab, VisLab and Studio already open today.')
    ]), [{ label: 'Sprite sheet', icon: 'grid', fn: function () { app.closeSheet(); intake.bakeSheet(app); } }, { label: 'Save the character file', cls: 'go', icon: 'save', fn: function () { app.closeSheet(); intake.saveCharacter(app); } }]);
  };
  /** Bake the take (or two seconds of idle life) into a sprite sheet. Same solver as the stage. */
  intake.bakeSheet = function (app) {
    var ch = app.ch, inp = app.inp, tk = inp.take && inp.take.n ? inp.take : null, fps = 12, dur = tk ? Math.min(8, tk.n / tk.fps) : 2.5, n = Math.max(2, Math.min(64, Math.round(dur * fps)));
    var b = AL.boundsOf(ch.root) || { x0: 0, y0: 0, x1: ch.w, y1: ch.h }, padX = (b.x1 - b.x0) * 0.25, padY = (b.y1 - b.y0) * 0.12;
    b = { x0: b.x0 - padX, y0: b.y0 - padY, x1: b.x1 + padX, y1: b.y1 + padY };
    var cell = 256, s = Math.min(cell / (b.x1 - b.x0), cell / (b.y1 - b.y0)), cols = Math.ceil(Math.sqrt(n)), rows = Math.ceil(n / cols);
    var gc = document.createElement('canvas'); gc.width = gc.height = cell; var G = new AL.GL(gc), sol = new AL.Solver(ch);
    var sheet = document.createElement('canvas'); sheet.width = cols * cell; sheet.height = rows * cell; var sg = sheet.getContext('2d');
    var probe = new AL.Inputs(app); probe.take = tk; probe.auto = !tk;
    for (var i = 0; i < n; i++) {
      var S; if (tk) { probe.playing = true; probe.ph = i / fps; S = probe.step(1 / fps); } else S = probe.step(1 / fps);
      var out = sol.eval(S, { live: true, dt: 1 / fps });
      G.draw(out.items, { s: s, ox: (cell - (b.x1 - b.x0) * s) / 2 - b.x0 * s, oy: (cell - (b.y1 - b.y0) * s) / 2 - b.y0 * s });
      sg.drawImage(gc, (i % cols) * cell, ((i / cols) | 0) * cell);
    }
    var base = (ch.name || 'character').replace(/[^\w\- ]+/g, '');
    sheet.toBlob(function (blob) {
      var first = intake.saveFile(base + '.png', blob);
      /* field for field what Sprite Lab's SpriteSheet.fromJson() reads */
      var frames = []; for (var k = 0; k < n; k++) frames.push(k);
      var side = { spriteSchemaVersion: 1, name: base, sheetUri: base + '.png', cols: cols, rows: rows, fps: fps, pivotX: 0.5, pivotY: 0.5, presets: [{ id: 'p1', name: tk ? 'take' : 'idle', type: 'loop', fps: fps, frames: frames }], x_avatarstudio: { bakedFrom: ch.name, seconds: dur } };
      first.then(function () { return new Promise(function (r) { setTimeout(r, 500); }); }).then(function () { intake.saveFile(base + '.sprite.json', new Blob([JSON.stringify(side, null, 2)], { type: 'application/json' })); });
    }, 'image/png');
  };

  intake.wire = function (app) {
    function fail(e) { console.error(e); app.toast(e && e.message ? e.message : 'That would not open.'); }
    $('filePsd').onchange = function () { var f = this.files[0]; if (!f) return; app.toast('Reading “' + f.name + '”…'); readBuf(f).then(function (b) { return intake.openPsd(app, b, nice(f.name)); }).catch(fail); };
    $('fileJson').onchange = function () { var f = this.files[0]; if (f) readText(f).then(function (t) { return intake.openJson(app, t); }).catch(fail); };
    $('fileAudio').onchange = function () { var f = this.files[0]; if (f) app.inp.playVoice(f).then(function () { app.refreshPanel(true); }).catch(function () { app.toast('That sound would not play.'); }); };
    document.addEventListener('dragover', function (e) { e.preventDefault(); });
    document.addEventListener('drop', function (e) {
      e.preventDefault(); var fs = e.dataTransfer && e.dataTransfer.files; if (!fs || !fs.length) return; var f = fs[0];
      if (/\.ps[db]$/i.test(f.name)) readBuf(f).then(function (b) { return intake.openPsd(app, b, nice(f.name)); }).catch(fail);
      else if (/\.json$/i.test(f.name)) readText(f).then(function (t) { return intake.openJson(app, t); }).catch(fail);
      else if (/^audio\//.test(f.type)) app.inp.playVoice(f).then(function () { app.setMode('perform'); });
      else intake.addPictures(app, fs, null).catch(fail);
    });
  };
})(typeof self !== 'undefined' ? self : this);
