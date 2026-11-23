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
    intake.saveFile((app.ch.name || 'character').replace(/[^\w\- ]+/g, '') + '.character.json', new Blob([JSON.stringify(o)], { type: 'application/json' }));
  };

  /* ── the built-in characters ── */
  intake.builtIn = function (app, which) { return builtIn0(app, which).then(function (r) { setTimeout(function () { AL.shelf.stockKeep(app, which); }, 400); return r; }); };
  function builtIn0(app, which) {
    if (which === 'pip') { AL.img.map = {}; var p = AL.demo(); p.demo = 'pip'; p.pilot = AL.PILOT; app.setMode('build'); app.setCharacter(p, { quiet: true }); return Promise.resolve(); }
    function get(url) { return fetch(url).then(function (r) { if (!r.ok) throw new Error('missing'); return r.json(); }); }
    if (which === 'joybot') {                                     // a draft built from the owner's kit; it rides in his own page, not in the repository
      var jb = document.getElementById('builtin-joybot');
      return (jb ? Promise.resolve().then(function () { return JSON.parse(jb.textContent); }) : get('local/joybot.character.json')).then(function (o) { delete o.take; AL.img.map = {}; return AL.fromJson(o).then(function (ch) { ch.demo = 'joybot'; ch.pilot = AL.PILOT; app.setMode('build'); app.setCharacter(ch, { quiet: true }); }); });
    }
    /* in the published page he rides inside it; on the local page he is the file in characters/ */
    var inside = document.getElementById('builtin-topps');
    return (inside ? Promise.resolve().then(function () { return JSON.parse(inside.textContent); }) : get('characters/topps.character.json')).then(function (o) {
      delete o.take; AL.img.map = {};
      return AL.fromJson(o).then(function (ch) { ch.demo = 'topps'; ch.pilot = AL.PILOT; app.setMode('build'); app.setCharacter(ch, { quiet: true }); });
    });
  };
  /** Pilot only: write a file next to the page (tools/avatarlab/local/), through serve.mjs. */
  intake.saveLocal = function (name, text) { return fetch('local/' + name, { method: 'PUT', body: text }).then(function (r) { if (!r.ok) throw new Error('could not write local/' + name); }); };

  /* ── a layered file ── */
  intake.openPsd = function (app, buf, name) {
    var old = AL.img.map; AL.img.map = {};
    return AL.importPsd(buf, name, function (done, all) { app.toast('Reading layer ' + done + ' of ' + all + '\u2026'); }).then(function (doc) { var ch = AL.autoRig(doc); app.setMode('build'); app.setCharacter(ch); intake.review(app, doc.layers); }).catch(function (e) { AL.img.map = old; throw e; });
  };
  intake.openJson = function (app, text) {
    var o; try { o = JSON.parse(text); } catch (e) { throw new Error('That file could not be read as a character.'); }
    if (o && o.format === 'joycreator.scene/1' && AL.scene) return AL.scene.open(app, o).then(function (ok) { if (ok) app.toast('Opened the scene “' + o.name + '”'); });      // a scene file: its set, its cast, its takes
    var bad = AL.validate(o); if (bad) throw new Error(bad);
    var take = o.take && o.take.ch ? o.take : null, old = AL.img.map; delete o.take; AL.img.map = {};
    return AL.fromJson(o).then(function (ch) {
      var lost = AL.findAll(ch.root, function (n) { return n.type === 'art' && !AL.img.get(n.img); }).length;
      app.setMode('build'); app.setCharacter(ch); app.inp.take = take;
      app.toast('Opened \u201c' + ch.name + '\u201d' + (take ? ', with its take' : '') + (lost ? '. ' + lost + (lost === 1 ? ' picture' : ' pictures') + ' inside it could not be read.' : ''));
    }, function (e) { AL.img.map = old; throw e; });
  };
  /**
   * The artist repainted the layered file. Bring the new paint in and keep every pin, hinge, mask and setting.
   * Layers are matched by their folder path and name, then by name alone where that name is used once.
   * New layers arrive in Extras; layers that are gone from the file stay as they were.
   */
  /**
   * THE SAME CHARACTER, PAINTED BIGGER. A layered file of the same shape as the character but a different size (Topps rigged at 512,
   * repainted at 1024 or 2000) is brought into the character's own space: each layer's place is divided down, and its paint is kept at
   * full resolution as finer art (AL.img.putFine). Nothing in the rig is touched, so nothing in the rig can break.
   * Each layer's box is grown to whole character pixels first, so the paint sits exactly where it was painted.
   * Returns the factor (0 = the file is the character's size, or a different shape: left alone).
   */
  intake.fitSize = function (doc, ch) {
    var k = doc.w / ch.w; if (!(k > 0) || Math.abs(doc.h / ch.h - k) > 0.01 * k || Math.abs(k - 1) < 0.005) return 0;
    (function w(L) {
      if (L.kind === 'group') { (L.kids || []).forEach(w); return; }
      var im = AL.img.get(L.img); if (!im || !im.w || !im.h) { L.x = Math.round(L.x / k); L.y = Math.round(L.y / k); return; }
      var lx = Math.floor(L.x / k), ly = Math.floor(L.y / k), lw = Math.max(1, Math.ceil((L.x + im.w) / k) - lx), lh = Math.max(1, Math.ceil((L.y + im.h) / k) - ly);
      var c = document.createElement('canvas'), g;
      if (k > 1) { c.width = Math.round(lw * k); c.height = Math.round(lh * k); c.getContext('2d').drawImage(im.canvas, Math.round(L.x - lx * k), Math.round(L.y - ly * k)); AL.img.putFine(L.img, c, lw, lh, im.name); }
      else { c.width = lw; c.height = lh; g = c.getContext('2d'); g.imageSmoothingEnabled = true; g.imageSmoothingQuality = 'high'; g.drawImage(im.canvas, L.x / k - lx, L.y / k - ly, im.w / k, im.h / k); AL.img.put(L.img, c, im.name); }
      L.x = lx; L.y = ly;
    })({ kind: 'group', kids: doc.kids });
    return k;
  };
  intake.updateArt = function (app, buf, name) {
    var ch = app.ch;
    function key(path) { return path.filter(function (p) { return String(p).trim() !== ''; }).map(function (p) { return String(p).replace(/^\s*\+\s*/, '').replace(/\s+/g, ' ').trim().toLowerCase(); }).join('/'); }      // (as the rig names them: a leading + mark and stray spaces are not part of a name)
    return AL.importPsd(buf, name).then(function (doc) {
      var kq = intake.fitSize(doc, ch);
      var byPath = {}, byName = {};
      (function w(n, path) { if (n.type === 'art') { var k = key(path.concat(n.name)); (byPath[k] = byPath[k] || []).push(n); var nm = key([n.name]); (byName[nm] = byName[nm] || []).push(n); } (n.kids || []).forEach(function (q) { w(q, n === ch.root ? [] : path.concat(n.name)); }); })(ch.root, []);
      var hit = 0, moved = 0, fresh = [], later = [], taken = {};
      /* the file says where each drawing sits (owner, 11-21: where things were moved in the pilot was experiment, the file is the truth) */
      function place(n, L) { if (n.x !== L.x || n.y !== L.y) moved++; n.x = L.x; n.y = L.y; n.img = L.img; hit++; taken[n.id] = 1; }
      app.edit(function () {
        (function w(L, path) {
          if (L.kind === 'group') { L.kids.forEach(function (q) { w(q, path.concat(L.name)); }); return; }
          var list = byPath[key(path.concat(L.name))];
          if (!list || !list.length) { later.push(L); return; }
          var same = list.every(function (q) { return q.shared; }) ? list : [list.shift()];      // a drawing copied onto every head angle is one layer in the file
          same.forEach(function (n) { place(n, L); });
        })({ kind: 'group', name: '', kids: doc.kids }, []);
        /* not where the file has it (moved to another folder in this room): by its name, if only one drawing of that name is still unclaimed */
        later.forEach(function (L) {
          var c = (byName[key([L.name])] || []).filter(function (q) { return !taken[q.id]; });
          if (c.length === 1 || (c.length && c.every(function (q) { return q.shared; }))) c.forEach(function (n) { place(n, L); }); else fresh.push(L);
        });
        fresh.forEach(function (L) { ch.root.kids.unshift({ id: AL.id('n'), name: String(L.name).replace(/^\s*\+\s*/, '').trim() || 'Layer', type: 'art', img: L.img, x: L.x, y: L.y, role: 'prop' }); });
        AL.reindex(ch);
      }, false, 'Update the art');
      app.solver.grids = {}; app.solver.geo = {}; if (app.G) app.G.forget();
      app.toast(hit + ' drawings repainted, rigging kept' + (fresh.length ? '. ' + fresh.length + ' new ' + (fresh.length === 1 ? 'layer is' : 'layers are') + ' in Extras' : '') + (kq > 1 ? '. The file is ' + (Math.round(kq * 10) / 10) + ' times the size: it came in as finer art, and every pin is where it was' : kq ? '. The file is smaller than the character, so the art is coarser now' : doc.w !== ch.w || doc.h !== ch.h ? '. The file is a different shape, so check where things sit' : ''));
    });
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
    var nt = ch.importNotes, hdN = AL.firstRole(ch.root, 'head');
    if (hdN && hdN.sharedCount) body.appendChild(ui.note(hdN.sharedCount + (hdN.sharedCount === 1 ? ' layer' : ' layers') + ' beside the head angles (a hat, say) ' + (hdN.sharedCount === 1 ? 'was' : 'were') + ' copied onto every angle.'));
    if (nt && (nt.clip || nt.blend)) body.appendChild(ui.note('<b>Heads up:</b> ' + [nt.clip ? nt.clip + ' clipping ' + (nt.clip === 1 ? 'layer' : 'layers') : '', nt.blend ? nt.blend + (nt.blend === 1 ? ' layer' : ' layers') + ' with a blend mode' : ''].filter(Boolean).join(' and ') + ' came in as plain layers. If one looks wrong, flatten it in the art program.'));
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
    /* exported "one file per layer, all the same size": they are already where they belong */
    var common = pics.every(function (p) { return p.fullW === pics[0].fullW && p.fullH === pics[0].fullH; }) && (pics.length > 1 || pics[0].canvas.width < pics[0].fullW * 0.9 || pics[0].canvas.height < pics[0].fullH * 0.9);
    if (first) { if (common) { ch.w = pics[0].fullW; ch.h = pics[0].fullH; } else { var big = Math.max(pics[0].canvas.width, pics[0].canvas.height); ch.h = ch.w = Math.max(256, Math.round(zone === 'body' ? pics[0].canvas.height / 0.38 : big * 2.4)); } }
    var so = sockets(app), s = so[zone] || so.extra, made = [];
    pics.forEach(function (p, i) {
      var id = AL.id('i'); AL.img.put(id, p.canvas, p.name);
      var art = { id: AL.id('n'), name: p.name || (ZL[zone] + ' ' + (i + 1)), type: 'art', img: id, x: 0, y: 0 }, w = p.canvas.width, hh = p.canvas.height;
      var aligned = p.fullW === ch.w && p.fullH === ch.h && (!first || common);
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
  /**
   * Frames of one loop (a flame, a spinning ring, a plasma arc): one part that plays through them.
   * On an arm or leg they become one limb that plays its frames while it reaches. Must run inside app.edit().
   */
  intake.placeLoop = function (app, zone, pics, fps, swap) {
    var ch = app.ch, limb = /^(arm|leg)/.test(zone);
    if (limb) {
      var made = intake.place(app, zone, pics), set = app.zoneNode(zone), f0 = made[0];
      made.forEach(function (a) { a.x = f0.x; a.y = f0.y; if (f0.limb) a.limb = JSON.parse(JSON.stringify(f0.limb)); });
      set.pick.loop = fps || 12; set.pick.swap = false; set.pick.fade = 0; return made;
    }
    var set2 = { id: AL.id('n'), name: (pics[0].name || 'Loop').replace(/\s*\d+$/, '') || 'Loop', type: 'set', role: zone === 'extra' ? 'prop' : null, pick: { by: 'cycle', fps: fps || 12 }, kids: [] };
    var one = intake.place(app, zone, [pics[0]])[0], par = AL.parentOf(ch, one.id), at = par.kids.indexOf(one);
    pics.forEach(function (p, i) { var a = one; if (i) { var id = AL.id('i'); AL.img.put(id, p.canvas, p.name); a = { id: AL.id('n'), name: p.name, type: 'art', img: id, x: one.x, y: one.y }; } set2.kids.push(a); });
    if (one.role) { set2.role = one.role; delete one.role; }
    par.kids.splice(at, 1, set2); set2.pick.rest = set2.kids[0].id;
    if (swap) { set2.pick = { by: 'default', rest: set2.kids[0].id }; set2.kids.forEach(function (k, i) { if (i) AL.buttonFor(ch, k); }); }      // one part, and a button for each of its other drawings
    AL.reindex(ch);
    return [set2];
  };
  /** A separate drawing carried by the end of a limb: a hand, a glove, a shoe, a sword. Several drawings become one part that swaps. */
  /** Put a different picture in a drawing. Its place (by its middle), its pins, its button and everything set on it are kept. */
  intake.swapPicture = function (app, art) {
    pickImages(function (files) {
      loadCanvas(files[0]).then(function (c) { return trim(c, nice(files[0].name)); }).then(function (p) {
        if (!p) throw new Error('That picture was empty.');
        var old = AL.img.get(art.img), cx = art.x + (old ? old.w / 2 : 0), cy = art.y + (old ? old.h / 2 : 0);
        app.edit(function () { var id = AL.id('i'); AL.img.put(id, p.canvas, p.name); art.img = id; art.x = Math.round(cx - p.canvas.width / 2); art.y = Math.round(cy - p.canvas.height / 2); }, false, 'Change a picture');
        app.toast('Picture changed. Its place and settings are kept.');
      }).catch(function (e) { app.toast(e.message); });
    });
  };
  /** More pictures as ANGLES of something that turns (a body's front view, its other side): each gets its own dot on the pad. */
  intake.addAngle = function (app, set) {
    pickImages(function (files) {
      Promise.all(Array.prototype.map.call(files, function (f) { return loadCanvas(f).then(function (c) { return trim(c, nice(f.name)); }); })).then(function (pics) {
        pics = pics.filter(Boolean); if (!pics.length) throw new Error('Those pictures were empty.');
        var b = AL.boundsOf(set) || { x0: 0, y0: 0, x1: app.ch.w, y1: app.ch.h }, cx = (b.x0 + b.x1) / 2, cy = (b.y0 + b.y1) / 2, last = null, spots = [[0, 0], [0.6, 0], [-0.6, 0], [1, 0], [-1, 0], [0, -0.6], [0, 0.6], [0.3, 0], [-0.3, 0]];
        app.edit(function () { pics.forEach(function (p) {
          var id = AL.id('i'), at = spots.filter(function (q) { return !set.kids.some(function (k) { return k.at && Math.hypot(k.at[0] - q[0], k.at[1] - q[1]) < 0.2; }); })[0] || [0, 0.3];
          AL.img.put(id, p.canvas, p.name); last = { id: AL.id('n'), name: p.name, type: 'art', img: id, x: Math.round(cx - p.canvas.width / 2), y: Math.round(cy - p.canvas.height / 2), at: [at[0], at[1]] }; set.kids.push(last);
        }); }, true, 'Add an angle');
        if (last) { app.bt.x = last.at[0]; app.bt.y = last.at[1]; app.moveDots = true; } app.refreshPanel(true);
        app.toast(pics.length === 1 ? 'Angle added. Drag its dot to where it should show.' : pics.length + ' angles added. Drag their dots to where they should show.');
      }).catch(function (e) { app.toast(e.message); });
    });
  };
  /** More pictures as looks of a part that swaps: each lands where the part is. */
  intake.addLook = function (app, set) {
    pickImages(function (files) {
      Promise.all(Array.prototype.map.call(files, function (f) { return loadCanvas(f).then(function (c) { return trim(c, nice(f.name)); }); })).then(function (pics) {
        pics = pics.filter(Boolean); if (!pics.length) throw new Error('Those pictures were empty.');
        var b = AL.boundsOf(set) || { x0: 0, y0: 0, x1: app.ch.w, y1: app.ch.h }, cx = (b.x0 + b.x1) / 2, cy = (b.y0 + b.y1) / 2, last = null;
        app.edit(function () { pics.forEach(function (p) { var id = AL.id('i'); AL.img.put(id, p.canvas, p.name); last = { id: AL.id('n'), name: p.name, type: 'art', img: id, x: Math.round(cx - p.canvas.width / 2), y: Math.round(cy - p.canvas.height / 2) }; set.kids.push(last); }); }, true, 'Add looks');
        if (last) app.force[set.id] = last.id; app.refreshPanel(true);
        app.toast(pics.length === 1 ? 'Look added.' : pics.length + ' looks added.');
      }).catch(function (e) { app.toast(e.message); });
    });
  };
  intake.addRider = function (app, set) {
    pickImages(function (files) {
      Promise.all(Array.prototype.map.call(files, function (f) { return loadCanvas(f).then(function (c) { return trim(c, nice(f.name)); }); })).then(function (pics) {
        pics = pics.filter(Boolean); if (!pics.length) throw new Error('Those pictures were empty.');
        var ch = app.ch, rest = AL.node(ch, set.pick && set.pick.rest) || set.kids.filter(function (k) { return k.limb; })[0], at = rest && rest.limb ? rest.limb.c : { x: ch.w / 2, y: ch.h / 2 }, node = null;
        app.edit(function () {
          var arts = pics.map(function (p) { var id = AL.id('i'); AL.img.put(id, p.canvas, p.name); return { id: AL.id('n'), name: p.name, type: 'art', img: id, x: Math.round(at.x - p.canvas.width / 2), y: Math.round(at.y - p.canvas.height / 2) }; });
          node = arts.length === 1 ? arts[0] : { id: AL.id('n'), name: (AL.ROLES[set.role] ? AL.ROLES[set.role].label : set.name) + ' hand', type: 'set', pick: { by: 'default', rest: arts[0].id }, kids: arts };
          node.ride = { of: set.id, turn: 1 }; node.role = 'prop'; if (node.type === 'set') node.kids.forEach(function (k, i) { if (i) AL.buttonFor(ch, k); });
          var top = set, par; while ((par = AL.parentOf(ch, top.id)) && par !== ch.root) top = par;      // straight above the limb it belongs to
          ch.root.kids.splice(Math.max(0, ch.root.kids.indexOf(top)), 0, node); AL.reindex(ch);
        }, false, 'Add a hand');
        app.sel = { zone: 'extra', feature: null, node: node.id }; app.resolveSel(); app.refreshPanel();
        app.toast('It is carried by the end of the ' + (/^leg/.test(set.role) ? 'leg' : 'arm') + '. Use Move art to seat it, then Try it.');
      }).catch(function (e) { app.toast(e.message); });
    });
  };
  intake.trim = trim;      // (a picture cut to its paint, remembering where on the whole canvas it sat: al-agent.js brings parts in the same way)
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
    if (!any) {
      /* no see-through background: take away the corner's colour, but only where it is joined to the edge, so the
         white of an eye or a highlight inside a part is left alone */
      var r0 = d[0], g0 = d[1], b0 = d[2], seen = new Uint8Array(W * H), todo = new Int32Array(W * H), sp = 0, e;
      var seed = function (q) { if (seen[q]) return; var o = q * 4; if (Math.abs(d[o] - r0) + Math.abs(d[o + 1] - g0) + Math.abs(d[o + 2] - b0) < 46) { seen[q] = 1; todo[sp++] = q; } };
      for (e = 0; e < W; e++) { seed(e); seed((H - 1) * W + e); }
      for (e = 0; e < H; e++) { seed(e * W); seed(e * W + W - 1); }
      while (sp) { var q0 = todo[--sp], qx0 = q0 % W; d[q0 * 4 + 3] = 0; if (qx0 > 0) seed(q0 - 1); if (qx0 < W - 1) seed(q0 + 1); if (q0 >= W) seed(q0 - W); if (q0 < W * (H - 1)) seed(q0 + W); }
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
    var found = comps, mode = 'find', gc = 4, gr = 3, loop = false, grp = 'parts';
    /* an even grid: every cell is one drawing (a mouth chart, a row of frames) */
    function cells() { var out = [], cw = W / gc, chh = H / gr; for (var r = 0; r < gr; r++) for (var q = 0; q < gc; q++) out.push({ id: -1 - out.length, cell: { x: Math.round(q * cw), y: Math.round(r * chh), w: Math.round(cw), h: Math.round(chh) }, x0: q * cw / f, y0: r * chh / f, x1: (q + 1) * cw / f - 1, y1: (r + 1) * chh / f - 1, n: 1e9, sel: 0, used: false }); return out; }
    var ui = app.ui, full = app.full('Cut parts from “' + name + '”'), cv = h('canvas'), order = 0, zone = zone0 || 'head';
    full.body.appendChild(cv);
    var tools = h('div', { class: 'rowf' });
    function drawTools() {
      tools.innerHTML = '';
      tools.appendChild(ui.seg([['find', 'Find the parts', 'Each separate drawing on the sheet is one part'], ['grid', 'Even grid', 'The sheet is rows and columns of the same size: a mouth chart, frames of a loop']], mode, function (v) { mode = v; comps = v === 'grid' ? cells() : found; order = 0; found.forEach(function (c) { c.sel = 0; }); drawTools(); paint(); }, 'How the sheet is cut'));
      if (mode === 'grid') {
        var num = function (label, val, fn) { return h('label', { class: 'note', style: 'margin:0;display:flex;align-items:center;gap:4px' }, [label, h('input', { type: 'number', min: '1', max: '24', value: String(val), style: 'width:52px', 'aria-label': label, onchange: function (ev) { fn(Math.max(1, Math.min(24, Math.round(+ev.target.value || 1)))); comps = cells(); order = 0; paint(); } })]); };
        tools.appendChild(num('across', gc, function (v) { gc = v; })); tools.appendChild(num('down', gr, function (v) { gr = v; }));
        tools.appendChild(ui.chip('All', { title: 'Choose every cell, in reading order', fn: function () { order = 0; comps.forEach(function (c) { if (!c.used) c.sel = ++order; }); paint(); } }));
      }
      tools.appendChild(ui.seg([['parts', 'Separate drawings', 'Each one you tap is added by itself'], ['swap', 'One part that swaps', 'The drawings you tap are ONE part with several looks (expressions, hand shapes). The first is its usual look; each of the others gets a button'], ['loop', 'Frames of a loop', 'The drawings you tap are ONE part that plays through them in the order tapped (a flame, a spinning ring, a glowing arm)']], grp, function (v) { grp = v; loop = v !== 'parts'; drawTools(); paint(); }, 'What the drawings you tap are'));
    }
    full.foot.appendChild(tools);
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
      noteEl.innerHTML = (mode === 'grid' ? '<b>' + comps.length + ' cells.</b> ' : 'Found <b>' + comps.length + ' parts</b>. ') + (grp === 'loop' ? 'Tap the frames in the order they play, then <b>Add</b>.' : grp === 'swap' ? 'Tap its usual look first, then its other looks, then <b>Add</b>.' : 'Tap every drawing of one body part') + (loop ? '' : '') + (zone === 'armL' || zone === 'armR' ? ' (an arm at several angles is fine: it will swap between them)' : '') + ', then <b>Add</b>.';
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
        var x0 = c.cell ? c.cell.x : c.x0 * f, y0 = c.cell ? c.cell.y : c.y0 * f, w = c.cell ? c.cell.w : Math.min(W - x0, (c.x1 - c.x0 + 1) * f), hh = c.cell ? c.cell.h : Math.min(H - y0, (c.y1 - c.y0 + 1) * f), t = document.createElement('canvas'); t.width = w; t.height = hh;
        var tg = t.getContext('2d', { willReadFrequently: true }); tg.drawImage(sheet, -x0, -y0); var td = tg.getImageData(0, 0, w, hh), q = td.data;
        if (!c.cell) { for (var yy = 0; yy < hh; yy++) for (var xx = 0; xx < w; xx++) if (lab[(((y0 + yy) / f) | 0) * lw + (((x0 + xx) / f) | 0)] !== c.id) q[(yy * w + xx) * 4 + 3] = 0; tg.putImageData(td, 0, 0); }
        return loop ? { canvas: t, name: ZL[zone] + ' ' + (n + 1) } : trim(t, ZL[zone] + ' ' + (n + 1));
      }).filter(Boolean);
      if (loop) {
        /* frames must stay registered: every frame is cropped to the SAME box, the one that holds all of them */
        var bx0 = 1e9, by0 = 1e9, bx1 = -1, by1 = -1, mw = 0, mh = 0;
        pics.forEach(function (p) { var t2 = trim(p.canvas, p.name); mw = Math.max(mw, p.canvas.width); mh = Math.max(mh, p.canvas.height); if (!t2) return; bx0 = Math.min(bx0, t2.ox); by0 = Math.min(by0, t2.oy); bx1 = Math.max(bx1, t2.ox + t2.canvas.width); by1 = Math.max(by1, t2.oy + t2.canvas.height); });
        if (bx1 < 0) return;
        pics = pics.map(function (p) { var t3 = document.createElement('canvas'); t3.width = bx1 - bx0; t3.height = by1 - by0; t3.getContext('2d').drawImage(p.canvas, -bx0, -by0); return { canvas: t3, name: p.name, ox: bx0, oy: by0, fullW: mw, fullH: mh }; });
      }
      if (!pics.length) return;
      app.edit(function () { if (loop && pics.length > 1) intake.placeLoop(app, zone, pics, 12, grp === 'swap'); else intake.place(app, zone, pics); }, true, grp === 'loop' ? 'Add a loop' : 'Add drawings');
      chosen.forEach(function (c) { c.sel = 0; c.used = true; }); order = 0; $('empty').style.display = 'none';
      app.toast(grp === 'swap' && pics.length > 1 ? 'One part with ' + pics.length + ' looks was added. Its buttons are in Perform.' : loop && pics.length > 1 ? 'A loop of ' + pics.length + ' frames was added to the ' + ZL[zone].toLowerCase() + '.' : pics.length + ' added to the ' + ZL[zone].toLowerCase() + '. Pick the next part, or Done.');
      if (zone0) { app.closeFull(); app.tab = 'parts'; app.fit(true); app.select(zone); } else { app.sel.zone = zone; paint(); }
    }
    drawTools(); setTimeout(paint, 30);
  }

  /* ── one finished drawing: tap where the head, hands and feet are ── */
  intake.wholeDrawing = function (app) {
    pickImages(function (files) {
      loadCanvas(files[0]).then(function (c) { intake.wholeFrom(app, c, nice(files[0].name)); }).catch(function (e) { app.toast(e.message); });
    }, false);
  };
  intake.wholeFrom = function (app, c, name) {
    var p = trim(c, name); if (!p) throw new Error('That picture was empty.');
    var was = { ch: app.ch, map: AL.img.map, take: app.inp ? app.inp.take : null, hist: app.hist, sel: app.sel };
    var ch = AL.newCharacter(p.name, Math.round(p.canvas.width * 1.7), Math.round(p.canvas.height * 1.35)); AL.img.map = {};
    var id = AL.id('i'); AL.img.put(id, p.canvas, p.name);
    var art = { id: AL.id('n'), name: p.name, type: 'art', role: 'torso', img: id, x: Math.round((ch.w - p.canvas.width) / 2), y: Math.round((ch.h - p.canvas.height) / 2), mesh: { pins: [] } };
    ch.root.kids.push(art); ch.settings.breathe = false; ch.settings.idle = false; AL.reindex(ch);
    app.setMode('build'); app.setCharacter(ch, { quiet: true });               // not saved over your character until the pins are done
    intake.pinWizard(app, art, function () {
      AL.img.map = was.map;
      if (was.ch) { app.setCharacter(was.ch, { quiet: true }); app.inp.take = was.take; app.hist = was.hist; app.sel = was.sel; app.resolveSel(); app.syncUndo(); app.refreshPanel(); }
      app.toast('Nothing was changed.');
    });
  };
  var STEPS = [['head', 'Head', 'the middle of the head'], ['handL', 'Left hand', 'its left hand (on your right if it faces you)'], ['handR', 'Right hand', 'its right hand'], ['hips', 'Hips', 'the hips. This pin holds the body still'], ['footL', 'Left foot', 'its left foot'], ['footR', 'Right foot', 'its right foot'], ['tail', 'Tail tip', 'the tip of the tail, if it has one']];
  intake.pinWizard = function (app, art, onCancel) {
    var im = AL.img.get(art.img), ui = app.ui, full = app.full('Pin “' + art.name + '”', onCancel), cv = h('canvas'), pins = [], step = 0, did = [], view = { s: 1, ox: 0, oy: 0 };
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
      if (did.length) bar.appendChild(ui.chip('Back', { cls: 'quiet', icon: 'back', title: 'Take back the last step', fn: function () { if (did.pop() === 'pin') pins.pop(); step = Math.max(0, step - 1); paint(); } }));
      if (s) bar.appendChild(ui.chip('Skip ' + s[1].toLowerCase(), { cls: 'quiet', fn: function () { did.push('skip'); step++; paint(); } }));
      bar.appendChild(h('span', { style: 'flex:1' }));
      bar.appendChild(ui.chip('Done', { cls: 'go', disabled: pins.length < 2, title: pins.length < 2 ? 'Place at least two pins' : 'Use these pins', fn: finish }));
    }
    cv.addEventListener('click', function (e) {
      var s = STEPS[step]; if (!s) return;
      var r = cv.getBoundingClientRect(), k = cv.clientWidth / r.width, lx = ((e.clientX - r.left) * k - view.ox) / view.s, ly = ((e.clientY - r.top) * k - view.oy) / view.s;
      if (lx < 0 || ly < 0 || lx > im.w || ly > im.h) return;
      pins.push({ id: AL.id('p'), name: s[1], role: s[0], lx: lx, ly: ly }); did.push('pin'); step++; paint();
    });
    function finish() {
      app.edit(function () { art.mesh = { pins: pins.map(function (p) { return { id: p.id, name: p.name, role: p.role, x: art.x + p.lx, y: art.y + p.ly }; }) }; });
      app.closeFull(); app.touch(); app.tab = 'parts'; app.select('body'); app.setMode('perform'); app.toast('Pinned. Drag any pin to move it; double-tap empty space to let go.');
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
      door('smile', 'Meet Topps', 'A finished character to poke at: seven head angles, arms that swap and bend, and he walks.', function () { app.closeSheet(); intake.builtIn(app, 'topps').catch(function () { intake.builtIn(app, 'pip'); }); }, true),
      intake.hasJoybot() ? door('smile', 'Meet Joybot (draft)', 'Built only from general tools: a head that turns like a ball, hose arms of looping plasma, hands that ride them, a stream of hover rings.', function () { app.closeSheet(); intake.builtIn(app, 'joybot').catch(function () { app.toast('Joybot is not with this copy of the page.'); }); }, true) : null,
      door('smile', 'Meet Pip', 'A simple practice character, drawn in code.', function () { app.closeSheet(); intake.builtIn(app, 'pip'); }, true)
    ]);
  }
  intake.hasJoybot = function () { return !!document.getElementById('builtin-joybot') || /^(localhost|127\.0\.0\.1)$/.test(location.hostname); };
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
    /* the characters you have, as a row of cards; the three file buttons sit in the header beside the close button */
    /* leaving a character: it is kept first; if this browser would not keep it, say so before anything replaces it */
    function away(then) { if (AL.scene && app.scene) AL.scene.close(app, true);      /* a character is being opened: the scene is kept and left */
      function go() { if (app._state === 'failed') app.ask('Leave this character?', 'This browser would not keep it. Save it to a file first if you want it.', 'Leave it', then, true); else then(); } app.dirty = !!app.ch && (app.dirty || app._state === 'unsaved'); Promise.resolve(app.autosave()).then(go, go); }
    function blank() { AL.img.map = {}; app.setMode('build'); app.setCharacter(AL.newCharacter('New character', 512, 512)); }
    function fresh(which, miss) { return function () { return intake.builtIn(app, which).catch(function () { app.toast(miss); }); }; }
    var stock = [{ key: 'topps', name: 'Topps', title: 'Topps as he comes: seven head angles, arms that swap and bend, and he walks', fn: fresh('topps', 'Topps is not with this copy of the page.') }];
    if (intake.hasJoybot()) stock.push({ key: 'joybot', name: 'Joybot', hint: 'draft', title: 'Joybot, a draft built from your own kit', fn: fresh('joybot', 'Joybot is not with this copy of the page.') });
    stock.push({ key: 'pip', name: 'Pip', title: 'Pip: a small character the page draws by itself', fn: function () { return intake.builtIn(app, 'pip'); } });
    stock.forEach(function (q) { q.pic = AL.shelf.stockPic(q.key); });
    var box = h('div', null, [AL.shelf.row(app, stock, function () {
      app.closeSheet();
      away(blank);
    }, away), AL.scene ? AL.scene.row(app) : null, m]);
    item('layers', 'Update the art', function () { if (!AL.find(app.ch.root, function (n) { return n.type === 'art'; })) { app.toast('There is no character to update yet.'); return; } intake.psdMode = 'update'; $('filePsd').value = ''; $('filePsd').click(); }, 'repainted .psd, rigging kept');
    item('image', 'Add pictures', function () { pickImages(function (files) { intake.addPictures(app, files, null).catch(function (e) { app.toast(e.message); }); }); }, 'named by part');
    m.appendChild(h('hr'));
    item('help', 'What can this do?', function () { intake.about(app); });
    m.appendChild(app.ui.toggle('One long card per part', !AL.stages.on(), function (v) { app.closeSheet(); AL.stages.set(app, !v); }, 'The earlier layout: Build and Perform at the top, and everything about a part on one card'));
    app.sheet('Avatar Studio', box, [], [
      { icon: 'file', title: 'Open a character file', fn: function () { app.closeSheet(); $('fileJson').value = ''; $('fileJson').click(); } },
      { icon: 'save', title: 'Save this character to a file, with its take', fn: function () { app.closeSheet(); if (AL.scene && AL.scene.on(app)) AL.scene.saveFile(app); else intake.saveCharacter(app); } },
      { icon: 'export', title: 'Export: the Joy Creator file, or one frame as a picture', fn: function () { app.closeSheet(); intake.send(app); } }
    ]);
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
  /** Bake the take (or a few seconds of idle life) into a sprite sheet. Same solver as the stage. */
  intake.bakeSheet = function (app) {
    var ch = app.ch, inp = app.inp, tk = inp.take && inp.take.n ? inp.take : null, dur = tk ? tk.n / tk.fps : 2.5;
    var fps = 12, MAXF = 96; if (dur * fps > MAXF) fps = Math.max(4, Math.floor(MAXF / dur));
    var n = Math.max(2, Math.min(MAXF, Math.round(dur * fps))), cut = tk && n / fps < dur - 0.2;
    function run(each) {
      var sol = new AL.Solver(ch), probe = new AL.Inputs(app); probe.take = tk; probe.auto = !tk;
      for (var i = 0; i < n; i++) { if (tk) { probe.playing = true; probe.ph = i / fps; } each(sol.eval(probe.step(1 / fps), { live: true, dt: 1 / fps }), i); }
    }
    /* first pass: everything the performance reaches, so a raised arm or a walk is not cut off */
    var b = { x0: 1e9, y0: 1e9, x1: -1e9, y1: -1e9 };
    run(function (out) { out.items.forEach(function (it) { if (it.ghost) return; for (var k = 0; k < it.v.length; k += 2) { if (it.v[k] < b.x0) b.x0 = it.v[k]; if (it.v[k] > b.x1) b.x1 = it.v[k]; if (it.v[k + 1] < b.y0) b.y0 = it.v[k + 1]; if (it.v[k + 1] > b.y1) b.y1 = it.v[k + 1]; } }); });
    if (b.x1 < b.x0) b = { x0: 0, y0: 0, x1: ch.w, y1: ch.h };
    var pad = Math.max(b.x1 - b.x0, b.y1 - b.y0) * 0.03; b = { x0: b.x0 - pad, y0: b.y0 - pad, x1: b.x1 + pad, y1: b.y1 + pad };
    var cell = 256, s = Math.min(cell / (b.x1 - b.x0), cell / (b.y1 - b.y0)), cols = Math.ceil(Math.sqrt(n)), rows = Math.ceil(n / cols);
    var gc = document.createElement('canvas'); gc.width = gc.height = cell; var G = new AL.GL(gc);
    var sheet = document.createElement('canvas'); sheet.width = cols * cell; sheet.height = rows * cell; var sg = sheet.getContext('2d');
    run(function (out, i) { G.draw(out.items, { s: s, ox: (cell - (b.x1 - b.x0) * s) / 2 - b.x0 * s, oy: (cell - (b.y1 - b.y0) * s) / 2 - b.y0 * s }); sg.drawImage(gc, (i % cols) * cell, ((i / cols) | 0) * cell); });
    var base = (ch.name || 'character').replace(/[^\w\- ]+/g, ''), frames = []; for (var k = 0; k < n; k++) frames.push(k);
    /* field for field what Sprite Lab's SpriteSheet.fromJson() reads */
    var side = { spriteSchemaVersion: 1, name: base, sheetUri: base + '.png', cols: cols, rows: rows, fps: fps, pivotX: 0.5, pivotY: 0.5, presets: [{ id: 'p1', name: tk ? 'take' : 'idle', type: 'loop', fps: fps, frames: frames }], x_avatarstudio: { bakedFrom: ch.name, seconds: n / fps } };
    var ui = app.ui, body = h('div'), prev = h('canvas', { style: 'width:100%;max-height:38vh;object-fit:contain;border-radius:10px;background:#000' }); prev.width = sheet.width; prev.height = sheet.height; prev.getContext('2d').drawImage(sheet, 0, 0);
    body.appendChild(prev);
    body.appendChild(ui.note('<b>' + n + ' frames</b> at ' + fps + ' a second (' + (Math.round(n / fps * 10) / 10) + ' s)' + (tk ? ', from your take' : ', of it idling') + (cut ? '. The take is longer than a sheet can hold, so this is its start' : '') + '. Sprite Lab, VisLab and Studio read the picture together with its settings file: save both.'));
    /* two taps, two files: a browser will not let a page save a second file by itself */
    app.sheet('Sprite sheet', body, [
      { label: 'Save the picture', cls: 'go', icon: 'image', keep: true, fn: function () { sheet.toBlob(function (blob) { intake.saveFile(base + '.png', blob); }, 'image/png'); } },
      { label: 'Save its settings', icon: 'save', keep: true, fn: function () { intake.saveFile(base + '.sprite.json', new Blob([JSON.stringify(side, null, 2)], { type: 'application/json' })); } },
      { label: 'Done', cls: 'quiet', fn: app.closeSheet }
    ]);
  };

  intake.wire = function (app) {
    function fail(e) { console.error(e); app.toast(e && e.message ? e.message : 'That would not open.'); }
    $('filePsd').onchange = function () { var f = this.files[0], upd = intake.psdMode === 'update'; intake.psdMode = null; if (!f) return; app.toast('Reading “' + f.name + '”…'); readBuf(f).then(function (b) { return upd ? intake.updateArt(app, b, nice(f.name)) : intake.openPsd(app, b, nice(f.name)); }).catch(fail); };
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
