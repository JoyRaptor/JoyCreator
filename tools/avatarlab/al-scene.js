/* AvatarLab: THE SCENE. A stage with a backdrop, things standing on it, and a cast.
 *
 * A character is one performer. A scene is the level above: where they stand, what is round them, how the camera sees it.
 * It is its own thing on the shelf (the menu: Create new avatar, a line, Create new scene), not a tab inside a character.
 *
 * THE RULES THAT KEEP IT EASY
 *   One rule for depth   Everything that stands, stands on its feet at (x, y). Drag it UP the floor and it goes back: it
 *                        shrinks, and passes behind whatever stands lower. There is no layer list to manage.
 *   Four kinds of thing  back   a backdrop: fills the stage, behind everything (several may be stacked)
 *                        obj    an object: stands on the floor. One picture, or several as a loop. May sway in the wind.
 *                        front  a foreground: in front of everything
 *                        cast   a character from the shelf, with its own engine, hands, voice and take
 *   The frame is the shot The stage is always 1920 x 1080. The frame (16:9, 4:3, 1:1, 4:5, 9:16) is a window on it, so
 *                        changing the shape never moves anything: the same performance can go out wide and upright.
 *                        What is outside the frame is still there, dimmed: that is where an entrance waits.
 *
 * FILES   al-scene.js       this: the scene itself, the cast, keeping it, the acts (every act is one plain function)
 *         al-scene-draw.js  the frame loop: camera, depth, drawing, fingers on the stage
 *         al-scene-take.js  one clock for the scene: passes, the camera lane, shots
 *         al-scene-ui.js    Arrange, the dock in Perform (scenes, cast, shots), the menu row
 *         al-agent.js       the same acts as named tools, for Joybot and a connected model
 *
 * Format: joycreator.scene/1. Phone: the stage is a Compose Canvas over the same GL surface; each cast member is one
 * PuppetRig instance; the dock is a LazyRow; the scene document is one hand-readable JSON beside its pictures.
 */
(function (root) {
  'use strict';
  var AL = root.AL, scene = AL.scene = {}, W = 1920, H = 1080;
  scene.W = W; scene.H = H;
  scene.SHAPES = [['16:9', 16 / 9, 'Wide: a TV, a laptop, YouTube'], ['4:3', 4 / 3, 'A classic screen'], ['1:1', 1, 'Square'], ['4:5', 4 / 5, 'A tall post'], ['9:16', 9 / 16, 'An upright phone: Shorts, Reels, TikTok']];
  scene.KIND = { back: 'Backdrop', obj: 'Object', front: 'Foreground', cast: 'Character' };
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function uid() { return 's' + Date.now().toString(36) + Math.floor(Math.random() * 1e6).toString(36); }
  function canvas(w, h) { var c = document.createElement('canvas'); c.width = w; c.height = h; return c; }
  scene.on = function (app) { return !!(app.scene && !app.scene.dive); };
  /* the document as text, without what is only worked out while it is on the stage (names that begin with _) */
  function text(d) { return JSON.stringify(d, function (k, v) { return k.charAt(0) === '_' ? undefined : v; }); }

  /* ── sets that come with the room: a scene is never an empty black box ── */
  function rnd(seed) { var s = seed; return function () { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; }
  var PAINT = {
    meadow: function (g, w, h) {
      var r = rnd(7), k = g.createLinearGradient(0, 0, 0, h * 0.62); k.addColorStop(0, '#6fbcf0'); k.addColorStop(1, '#dff3fb'); g.fillStyle = k; g.fillRect(0, 0, w, h);
      var s = g.createRadialGradient(w * 0.78, h * 0.2, 0, w * 0.78, h * 0.2, h * 0.42); s.addColorStop(0, 'rgba(255,250,214,.95)'); s.addColorStop(0.16, 'rgba(255,246,200,.55)'); s.addColorStop(1, 'rgba(255,246,200,0)'); g.fillStyle = s; g.fillRect(0, 0, w, h);
      [['#a9d7a0', 0.5, 0.11], ['#86c57f', 0.55, 0.08]].forEach(function (q, i) { g.fillStyle = q[0]; g.beginPath(); g.moveTo(0, h * 0.64); for (var x = 0; x <= w; x += w / 8) g.quadraticCurveTo(x + w / 16, h * (q[1] - q[2] * r()), x + w / 8, h * (q[1] + 0.05 + 0.03 * r())); g.lineTo(w, h); g.lineTo(0, h); g.fill(); });
      k = g.createLinearGradient(0, h * 0.6, 0, h); k.addColorStop(0, '#8fd06c'); k.addColorStop(1, '#3f9345'); g.fillStyle = k; g.fillRect(0, h * 0.6, w, h * 0.4);
      g.strokeStyle = 'rgba(40,110,50,.35)'; g.lineWidth = 2; for (var i = 0; i < 160; i++) { var x0 = r() * w, y0 = h * (0.62 + 0.38 * r()), l = 6 + (y0 / h - 0.6) * 46; g.beginPath(); g.moveTo(x0, y0); g.lineTo(x0 + (r() - 0.5) * 8, y0 - l); g.stroke(); }
    },
    room: function (g, w, h) {
      var k = g.createLinearGradient(0, 0, 0, h * 0.66); k.addColorStop(0, '#efe3cf'); k.addColorStop(1, '#dccbb0'); g.fillStyle = k; g.fillRect(0, 0, w, h);
      g.fillStyle = '#cfe6f5'; g.fillRect(w * 0.62, h * 0.12, w * 0.22, h * 0.3); g.strokeStyle = '#fff'; g.lineWidth = 10; g.strokeRect(w * 0.62, h * 0.12, w * 0.22, h * 0.3); g.beginPath(); g.moveTo(w * 0.73, h * 0.12); g.lineTo(w * 0.73, h * 0.42); g.moveTo(w * 0.62, h * 0.27); g.lineTo(w * 0.84, h * 0.27); g.lineWidth = 6; g.stroke();
      k = g.createLinearGradient(0, h * 0.66, 0, h); k.addColorStop(0, '#b78f66'); k.addColorStop(1, '#8a6642'); g.fillStyle = k; g.fillRect(0, h * 0.66, w, h * 0.34);
      g.strokeStyle = 'rgba(70,45,25,.28)'; g.lineWidth = 2; for (var i = -8; i <= 16; i++) { g.beginPath(); g.moveTo(w * 0.5 + (i - 4) * w * 0.06, h * 0.66); g.lineTo(w * 0.5 + (i - 4) * w * 0.2, h); g.stroke(); }
      g.fillStyle = '#f6efe2'; g.fillRect(0, h * 0.635, w, h * 0.03); g.fillStyle = 'rgba(0,0,0,.12)'; g.fillRect(0, h * 0.665, w, h * 0.012);
    },
    night: function (g, w, h) {
      var r = rnd(21), k = g.createLinearGradient(0, 0, 0, h * 0.64); k.addColorStop(0, '#070b24'); k.addColorStop(1, '#2a3566'); g.fillStyle = k; g.fillRect(0, 0, w, h);
      for (var i = 0; i < 150; i++) { g.fillStyle = 'rgba(255,255,255,' + (0.3 + 0.7 * r()) + ')'; var z = 0.6 + 1.6 * r(); g.fillRect(r() * w, r() * h * 0.6, z, z); }
      var m = g.createRadialGradient(w * 0.2, h * 0.2, 0, w * 0.2, h * 0.2, h * 0.3); m.addColorStop(0, 'rgba(240,244,255,1)'); m.addColorStop(0.2, 'rgba(240,244,255,1)'); m.addColorStop(0.22, 'rgba(190,205,255,.28)'); m.addColorStop(1, 'rgba(190,205,255,0)'); g.fillStyle = m; g.fillRect(0, 0, w, h);
      g.fillStyle = '#18233f'; g.beginPath(); g.moveTo(0, h * 0.62); for (var x = 0; x <= w; x += w / 6) g.quadraticCurveTo(x + w / 12, h * (0.5 + 0.08 * r()), x + w / 6, h * (0.6 + 0.03 * r())); g.lineTo(w, h); g.lineTo(0, h); g.fill();
      k = g.createLinearGradient(0, h * 0.62, 0, h); k.addColorStop(0, '#1d2f3a'); k.addColorStop(1, '#0c1519'); g.fillStyle = k; g.fillRect(0, h * 0.62, w, h * 0.38);
    },
    studio: function (g, w, h) {
      var k = g.createLinearGradient(0, 0, 0, h); k.addColorStop(0, '#1b1b20'); k.addColorStop(0.6, '#2c2c34'); k.addColorStop(0.68, '#34343d'); k.addColorStop(1, '#17171b'); g.fillStyle = k; g.fillRect(0, 0, w, h);
      g.save(); g.translate(w / 2, h * 0.86); g.scale(1, 0.26); var s = g.createRadialGradient(0, 0, 0, 0, 0, w * 0.42); s.addColorStop(0, 'rgba(255,255,255,.2)'); s.addColorStop(1, 'rgba(255,255,255,0)'); g.fillStyle = s; g.beginPath(); g.arc(0, 0, w * 0.42, 0, 6.3); g.fill(); g.restore();
    }
  };
  /* haze: what the air does to things far away (a thing at the back takes a little of this colour) */
  scene.SETS = { meadow: { name: 'Meadow', haze: [0.80, 0.91, 0.97] }, room: { name: 'Room', haze: [0.90, 0.85, 0.76] }, night: { name: 'Night', haze: [0.10, 0.13, 0.27] }, studio: { name: 'Studio', haze: [0.16, 0.16, 0.19] } };
  scene.paintSet = function (key) { var c = canvas(1280, 720); (PAINT[key] || PAINT.studio)(c.getContext('2d'), 1280, 720); return c; };

  /* ── the document ── */
  scene.blank = function (name, set) {
    var d = { format: 'joycreator.scene/1', uid: uid(), name: name || 'New scene', shape: '16:9', safe: 'off', floor: { near: 0.93, far: 0.62, shrink: 0.5 }, wind: 0.5, haze: 0.5, items: [], shots: [], cam: [] };
    if (set) d.items.push({ id: AL.id('k'), kind: 'back', name: scene.SETS[set].name, made: set, x: 0, y: 0, s: 1 });
    return d;
  };
  /** The frame: the window on the stage that is the shot. */
  scene.frame = function (d) { var ar = 16 / 9; scene.SHAPES.forEach(function (q) { if (q[0] === d.shape) ar = q[1]; }); var fw = Math.min(W, H * ar); return { x: (W - fw) / 2, y: 0, w: fw, h: H, ar: ar }; };
  /** How big a thing is drawn for where it stands: 1 at the front of the floor, floor.shrink at the back. */
  scene.depth = function (d, y) { var f = d.floor, t = (y / H - f.far) / (f.near - f.far); return f.shrink + (1 - f.shrink) * clamp(t, -0.35, 1.4); };
  scene.item = function (R, id) { for (var i = 0; i < R.data.items.length; i++) if (R.data.items[i].id === id) return R.data.items[i]; return null; };
  scene.cast = function (R) { return R.data.items.filter(function (q) { return q.kind === 'cast' && R.actors[q.id]; }); };
  /** Where a character's own drawing space lands on the stage: its feet (ground line, middle) on (x, y). */
  scene.place = function (d, it, ch) {
    var st = ch.settings || {}, b = AL.boundsOf(ch.root) || { x0: 0, y0: 0, x1: ch.w, y1: ch.h }, ax = st.axisX != null ? st.axisX : (b.x0 + b.x1) / 2, ay = st.walk && st.walk.groundY != null ? st.walk.groundY : b.y1;
    var k = 0.42 * H / Math.max(20, ay - b.y0) * (it.s || 1) * scene.depth(d, it.y);
    return { k: k, tx: it.x - ax * k, ty: it.y - ay * k, ax: ax, ay: ay, top: b.y0 };
  };

  /* ── the cast: each one a whole performer ── */
  function mkActor(app, it, ch, take) {
    AL.reindex(ch);
    var a = { id: it.id, ch: ch, solver: new AL.Solver(ch), hist: new AL.History(), out: null, view: null, lk: 0 };
    try { if (AL.stance) AL.stance.heal(ch); } catch (e) { }
    ['legL', 'legR'].forEach(function (r) { var s = AL.firstRole(ch.root, r); if (s && s.kids) s.kids.forEach(function (k) { if (k.limb && k.limb.foot === undefined && k.limb.style !== 'hose') k.limb.foot = AL.guessFoot(ch, k); }); });
    /* the engine that listens to fingers and voice thinks it belongs to an app: this is the little of an app it needs */
    var mini = { touch: function () { scene.touch(app); }, refreshPanel: function (k) { if (app.inp === a.inp) app.refreshPanel(k); } };
    Object.defineProperty(mini, 'ch', { get: function () { return a.ch; } });
    Object.defineProperty(mini, 'mode', { get: function () { return app.mode; } });
    Object.defineProperty(mini, 'inp', { get: function () { return a.inp; } });
    a.inp = new AL.Inputs(mini); if (take && take.ch && take.n) a.inp.take = take;
    a.inp.pose.flip = !!it.flip;
    if (scene.wire) scene.wire(app, a);
    try { a.pic = AL.shelf.thumb({ ch: ch }, 96); } catch (e) { a.pic = null; }
    return a;
  }
  /** Bring one character's file in for a cast member. src = { uid } (yours, from the shelf), { stock } (one that comes with the room) or { doc } (carried inside a scene file). */
  function fetchCh(app, src) {
    if (src.doc) { var o0 = JSON.parse(JSON.stringify(src.doc)); delete o0.take; return AL.fromJson(o0); }
    if (src.uid) return app.idb(function (s) { return s.get('c:' + src.uid); }).then(function (o) { if (!o || AL.validate(o)) throw new Error('gone'); delete o.take; return AL.fromJson(o); });
    if (src.stock === 'pip') { var p = AL.demo(); p.demo = 'pip'; p.pilot = AL.PILOT; return Promise.resolve(p); }
    var el = document.getElementById('builtin-' + src.stock), url = src.stock === 'joybot' ? 'local/joybot.character.json' : 'characters/' + src.stock + '.character.json';
    return (el ? Promise.resolve().then(function () { return JSON.parse(el.textContent); }) : fetch(url).then(function (r) { if (!r.ok) throw new Error('gone'); return r.json(); })).then(function (o) { delete o.take; return AL.fromJson(o).then(function (ch) { ch.demo = src.stock; ch.pilot = AL.PILOT; return ch; }); });
  }
  /** Every cast member that has no performer yet gets one. Resolves when the stage is complete. */
  scene.sync = function (app, docs, takes) {
    var R = app.scene, jobs = [];
    R.data.items.forEach(function (it) {
      if (it.kind !== 'cast' || R.actors[it.id] || R.loading[it.id]) return;
      R.loading[it.id] = true;
      jobs.push(fetchCh(app, docs && docs[it.id] ? { doc: docs[it.id] } : it.uid ? { uid: it.uid } : { stock: it.stock || 'topps' }).then(function (ch) {
        delete R.loading[it.id]; if (app.scene !== R) return;
        R.actors[it.id] = mkActor(app, it, ch, takes && takes[it.id]); it.name = ch.name; delete it.lost;
      }, function () { delete R.loading[it.id]; it.lost = true; }));
    });
    return Promise.all(jobs).then(function () { if (app.scene === R) { if (!R.focus || !R.actors[R.focus]) { var c = scene.cast(R)[0]; R.focus = c ? c.id : null; if (c && !R.dive) scene.hand(app, c.id, true); } app.refreshPanel(); } });
  };
  /** Put a performer in hand: fingers, voice, the panel and Undo are theirs now. (Nothing is thrown away: each keeps its own engine.) */
  scene.hand = function (app, id, quiet) {
    var R = app.scene, a = R && R.actors[id]; if (!a) return false;
    if (app.inp && app.inp !== a.inp) { if (app.inp.recording || app.inp.playing) app.inp.stop(); app.inp.micStop(); app.inp.camStop(); }      // the microphone goes with the one in hand
    R.focus = id;
    app.ch = a.ch; app.solver = a.solver; app.inp = a.inp; app.hist = a.hist;
    app.force = {}; app.show = {}; app.bridge = null; app.morph = null; app.shapeEdit = null; app.lockForce = null; app.btd = {}; app.bt = { x: 0, y: 0 }; app.held = null; app.poseEdit = null; app.fitOn = null;
    app.test = { mouth: null, blink: 0, lookX: 0, lookY: 0, brow: 0, lidUp: 0, lidLow: 0, lidTilt: 0, trig: {} };
    var first = null; AL.ZONES.forEach(function (z) { if (!first && app.zoneNode(z.id)) first = z.id; });
    app.sel = { zone: first, feature: null, node: null }; app.resolveSel(); app.syncUndo();
    if (a.out) app.out = a.out; if (a.view) app.view = { s: a.view.s, ox: a.view.ox, oy: a.view.oy };
    if (!quiet) app.refreshPanel();
    return true;
  };

  /* ── opening, keeping, leaving ── */
  function pictures(R, withAll) {
    var out = {};
    R.data.items.forEach(function (it) {
      if (it.made) return; var ids = it.frames || (it.img ? [it.img] : []);
      ids.forEach(function (id) { var im = AL.img.get(id); if (im && !out[id]) out[id] = (im.hi || im.canvas).toDataURL(it.kind === 'back' ? 'image/webp' : 'image/png', 0.92); });
    });
    return out;
  }
  /** The scene as one document. carry = with every character inside it (a file that can travel); otherwise the shelf's characters are named, not copied. */
  scene.toJson = function (app, carry) {
    var R = app.scene, o = JSON.parse(text(R.data)); o.pictures = pictures(R); o.takes = {};
    scene.cast(R).forEach(function (it) { var a = R.actors[it.id]; if (a.inp.take && a.inp.take.n) o.takes[it.id] = a.inp.take; });
    if (carry) { o.characters = {}; scene.cast(R).forEach(function (it) { o.characters[it.id] = AL.toJson(R.actors[it.id].ch, true); }); }
    return o;
  };
  scene.list = function (app) { return app.idb(function (s) { return s.get('scenes'); }).then(function (l) { return Array.isArray(l) ? l : []; }, function () { return []; }); };
  scene.keep = function (app) {
    var R = app.scene; if (!R) return Promise.resolve();
    R.dirty = false; var doc = scene.toJson(app, false), id = R.data.uid, pic = scene.thumb ? scene.thumb(app, 160) : null;
    return scene.list(app).then(function (l) {
      var old = l.filter(function (e) { return e.uid === id; })[0], rest = l.filter(function (e) { return e.uid !== id; });
      rest.unshift({ uid: id, name: R.data.name, at: Date.now(), pic: pic || (old && old.pic) || null, shape: R.data.shape });
      var drop = rest.slice(24); rest = rest.slice(0, 24);
      return app.idb(function (s) { s.put(doc, 's:' + id); drop.forEach(function (e) { s.delete('s:' + e.uid); }); return s.put(rest, 'scenes'); });
    }).catch(function () { R.dirty = true; });
  };
  scene.touch = function (app) { var R = app.scene; if (!R) return; R.dirty = true; clearTimeout(R._sv); R._sv = setTimeout(function () { if (app.scene === R && R.dirty) scene.keep(app); }, 1400); };
  /** Open a scene document. The character you were on is put aside, whole, and comes back when the scene is left. */
  scene.open = function (app, doc) {
    if (!doc || doc.format !== 'joycreator.scene/1' || !Array.isArray(doc.items)) { app.toast('That is not a scene this room can open.'); return Promise.resolve(false); }
    var back = app.scene ? scene.close(app, true) : Promise.resolve();
    return back.then(function () {
      var pics = doc.pictures || {}, takes = doc.takes || {}, docs = doc.characters || null, jobs = [];
      delete doc.pictures; delete doc.takes; delete doc.characters;
      Object.keys(pics).forEach(function (id) { jobs.push(new Promise(function (res) { var im = new Image(); im.onload = function () { var c = canvas(im.width, im.height); c.getContext('2d').drawImage(im, 0, 0); AL.img.put(id, c, id); res(); }; im.onerror = function () { res(); }; im.src = pics[id]; })); });
      return Promise.all(jobs).then(function () {
        if (app.inp) { app.inp.stop(); app.inp.micStop(); app.inp.camStop(); }
        var R = app.scene = { data: doc, actors: {}, loading: {}, stage: 'arrange', sel: null, focus: null, dive: null, t: 0,
          cam: { x: W / 2, y: H / 2, z: 1 }, shot: 'wide', free: null, clock: { run: null, ph: 0, on: false }, hist: { past: [], future: [] },
          solo: { ch: app.ch, solver: app.solver, inp: app.inp, hist: app.hist, stage: app.stage } };
        doc.floor = doc.floor || { near: 0.93, far: 0.62, shrink: 0.5 }; doc.shots = doc.shots || []; doc.cam = doc.cam || [];
        doc.items.forEach(function (it) { if (it.made) { it.img = it.img || AL.id('i'); if (!AL.img.get(it.img)) AL.img.put(it.img, scene.paintSet(it.made), it.name); } });
        app.stage = 'setup'; app.mode = 'build'; app.live = false; app.fitOn = null; app.poseEdit = null; app.held = null;
        document.getElementById('app').classList.add('scene'); document.getElementById('app').classList.remove('perform');
        try { localStorage.setItem('al.scene', doc.uid); } catch (e) { }
        app.syncUndo(); app.refreshPanel();
        return scene.sync(app, docs, takes).then(function () { scene.touch(app); return true; });
      });
    });
  };
  scene.openId = function (app, id) { return app.idb(function (s) { return s.get('s:' + id); }).then(function (o) { if (!o) { app.toast('That scene could not be opened.'); return false; } return scene.open(app, o); }); };
  /** Leave the scene (it is kept), and be back on the character you were on. */
  scene.close = function (app, quiet) {
    var R = app.scene; if (!R) return Promise.resolve();
    if (R.dive) scene.undive(app, true);
    Object.keys(R.actors).forEach(function (id) { var a = R.actors[id]; a.inp.stop(); a.inp.micStop(); a.inp.camStop(); });
    clearTimeout(R._sv); var kept = scene.keep(app);
    app.scene = null; try { localStorage.removeItem('al.scene'); } catch (e) { }
    var so = R.solo; app.ch = so.ch; app.solver = so.solver; app.inp = so.inp; app.hist = so.hist; app.stage = so.stage || 'setup';
    app.force = {}; app.show = {}; app.held = null; app.poseEdit = null; app.fitOn = null; app.live = false;
    document.getElementById('app').classList.remove('scene');
    document.getElementById('chname').textContent = app.ch.name;
    var first = null; AL.ZONES.forEach(function (z) { if (!first && app.zoneNode(z.id)) first = z.id; });
    app.sel = { zone: first, feature: null, node: null }; app.resolveSel(); app.syncUndo();
    app.setMode('build'); app.userView = false; app.fit(true); if (!quiet) app.refreshPanel();
    return kept;
  };

  /** Throw a scene away for good (the characters in it are not touched: they live on the shelf). */
  scene.forget = function (app, id) {
    var left = app.scene && app.scene.data.uid === id ? scene.close(app) : Promise.resolve();
    return left.then(function () { return scene.list(app); }).then(function (l) { return app.idb(function (s) { s.delete('s:' + id); return s.put(l.filter(function (e) { return e.uid !== id; }), 'scenes'); }); });
  };

  /* ── undo, for the scene itself (a character in hand keeps its own) ── */
  scene.edit = function (app, label, fn, quiet) {
    var R = app.scene; R.hist.past.push({ s: text(R.data), l: label || 'Change' }); if (R.hist.past.length > 60) R.hist.past.shift(); R.hist.future.length = 0;
    fn(R.data); scene.touch(app); app.syncUndo(); if (!quiet) app.refreshPanel(true);
  };
  scene.undo = function (app, redo, quiet) {
    var R = app.scene, from = redo ? R.hist.future : R.hist.past, to = redo ? R.hist.past : R.hist.future, e = from.pop(); if (!e) return;
    to.push({ s: text(R.data), l: e.l }); var uidKeep = R.data.uid; R.data = JSON.parse(e.s); R.data.uid = uidKeep;
    if (R.sel && !scene.item(R, R.sel)) R.sel = null;
    scene.touch(app); app.syncUndo(); scene.sync(app); app.refreshPanel(true); if (!quiet) app.toast((redo ? 'Redone: ' : 'Undone: ') + e.l);
  };

  /* ── the acts. Each is one plain function: the buttons call them, and so can Joybot or a connected model (al-agent.js) ── */
  /** A new scene, on one of the sets that come with the room, with the character you are on already standing in it. */
  scene.create = function (app, set, name) {
    var d = scene.blank(name || 'New scene', set || 'meadow'), ch = app.scene ? null : app.ch;
    if (ch && AL.find(ch.root, function (n) { return n.type === 'art'; })) {
      if (AL.shelf) AL.shelf.tag(ch);
      d.items.push({ id: AL.id('k'), kind: 'cast', name: ch.name, uid: ch.uid || null, stock: ch.uid ? null : (ch.demo || 'topps'), x: W * 0.5, y: H * 0.9, s: 1 });
      d.characters = {}; d.characters[d.items[d.items.length - 1].id] = AL.toJson(ch, false);      // as it is this minute (its pictures are already here)
      if (ch.uid) { app.dirty = true; app.autosave(); }                                               // and kept on the shelf, where the scene will look for it next time
    }
    return scene.open(app, d);
  };
  /** Pictures into the scene. kind: back | obj | front. Several pictures as one object are a loop. Returns the new item. */
  scene.addPictures = function (app, kind, canvases, name) {
    var R = app.scene, it = null; canvases = canvases.filter(Boolean); if (!canvases.length) return null;
    scene.edit(app, 'Add ' + scene.KIND[kind].toLowerCase(), function (d) {
      var ids = canvases.map(function (c) { var id = AL.id('i'); AL.img.put(id, c, name || 'picture'); return id; }), c0 = canvases[0], fr = scene.frame(d);
      it = { id: AL.id('k'), kind: kind, name: name || scene.KIND[kind], img: ids[0], x: 0, y: 0, s: 1 };
      if (ids.length > 1) { it.frames = ids; it.fps = 8; }
      if (kind === 'obj') { it.x = Math.round(fr.x + fr.w * (0.2 + 0.6 * Math.random())); it.y = Math.round(H * 0.86); it.s = Math.round(clamp(0.34 * H / c0.height, 0.05, 4) * 100) / 100; }
      if (kind === 'back') { var at = 0; d.items.forEach(function (q, i) { if (q.kind === 'back') at = i + 1; }); d.items.splice(at, 0, it); } else d.items.push(it);
      R.sel = it.id;
    });
    return it;
  };
  /** A character onto the stage. src = { uid } from your shelf, or { stock: 'topps' | 'pip' | 'joybot' }. */
  scene.addCast = function (app, src) {
    var R = app.scene, n = scene.cast(R).length, fr = scene.frame(R.data), it = null;
    scene.edit(app, 'Add a character', function (d) {
      it = { id: AL.id('k'), kind: 'cast', name: src.name || 'Character', uid: src.uid || null, stock: src.uid ? null : (src.stock || 'topps'), x: Math.round(fr.x + fr.w * [0.5, 0.28, 0.72, 0.4, 0.6, 0.16, 0.84][n % 7]), y: Math.round(H * (0.9 - 0.02 * (n % 3))), s: 1 };
      d.items.push(it); R.sel = it.id;
    }, true);
    return scene.sync(app).then(function () { return it; });
  };
  scene.remove = function (app, id) { var R = app.scene, it = scene.item(R, id); if (!it) return; scene.edit(app, 'Remove ' + it.name, function (d) { d.items = d.items.filter(function (q) { return q.id !== id; }); d.cam = d.cam.filter(function (c) { return c.shot !== 'close:' + id; }); if (R.sel === id) R.sel = null; if (R.focus === id) { var a = R.actors[id]; if (a) { a.inp.stop(); a.inp.micStop(); a.inp.camStop(); } R.focus = null; var c = scene.cast({ data: d, actors: R.actors })[0]; if (c) scene.hand(app, c.id, true); } if (R.shot === 'close:' + id) R.shot = 'wide'; }); };
  scene.copy = function (app, id) { var R = app.scene, it = scene.item(R, id), n = null; if (!it) return null; scene.edit(app, 'Another ' + it.name, function (d) { n = JSON.parse(text(it)); n.id = AL.id('k'); n.x = Math.round(it.x + 90); R.sel = n.id; d.items.splice(d.items.indexOf(it) + 1, 0, n); }, true); return scene.sync(app).then(function () { return n; }); };
  /** Change what an item is: any of x, y, s, flip, sway, fps, name. One undo step. */
  scene.set = function (app, id, what, label) { var R = app.scene, it = scene.item(R, id); if (!it) return false; scene.edit(app, label || 'Change ' + it.name, function () { for (var k in what) if (/^(x|y|s|flip|sway|fps|name|par)$/.test(k)) it[k] = what[k]; var a = R.actors[id]; if (a && what.flip != null) a.inp.pose.flip = !!what.flip; }); return true; };
  scene.shape = function (app, shape) { scene.edit(app, 'Frame ' + shape, function (d) { d.shape = shape; }); };
  scene.setSet = function (app, key) {
    scene.edit(app, 'Set: ' + scene.SETS[key].name, function (d) {
      var it = d.items.filter(function (q) { return q.made; })[0];
      if (!it) { it = { id: AL.id('k'), kind: 'back', x: 0, y: 0, s: 1 }; d.items.unshift(it); }
      it.made = key; it.name = scene.SETS[key].name; it.img = AL.id('i'); AL.img.put(it.img, scene.paintSet(key), it.name);
    });
  };
  scene.rename = function (app, name) { name = String(name || '').trim(); if (!name) return; scene.edit(app, 'Rename the scene', function (d) { d.name = name; }); };

  /** Step into one character's own four stages, and come back. The scene waits as it is. */
  scene.diveIn = function (app, id) {
    var R = app.scene; if (!R || !R.actors[id]) return;
    if (R.stage === 'perform') scene.go(app, 'arrange');
    scene.hand(app, id, true); R.dive = id; app.stage = 'fit'; document.getElementById('app').classList.remove('scene');
    document.getElementById('chname').textContent = app.ch.name; app.setMode('build'); app.userView = false; app.fit(true); app.refreshPanel();
  };
  scene.undive = function (app, quiet) {
    var R = app.scene; if (!R || !R.dive) return;
    var a = R.actors[R.dive], it = scene.item(R, R.dive);
    if (app.mode !== 'build') app.setMode('build'); app.live = false; app.stage = 'setup'; app.poseEdit = null; app.fitOn = null; app.held = null;
    if (a) { a.ch = app.ch; a.solver = app.solver; a.solver.grids = {}; try { a.pic = AL.shelf.thumb({ ch: a.ch }, 96); } catch (e) { } }
    if (it && a && a.ch.uid && it.uid !== a.ch.uid) { it.uid = a.ch.uid; it.stock = null; }      // a built-in one that was changed is yours now: the scene follows your copy
    if (it && a) it.name = a.ch.name;
    R.dive = null; document.getElementById('app').classList.add('scene'); scene.touch(app); app.syncUndo(); if (!quiet) app.refreshPanel();
  };
  /** Arrange or Perform. */
  scene.go = function (app, stage) {
    var R = app.scene; if (!R || R.stage === stage) return;
    R.stage = stage; R.sel = stage === 'perform' ? null : R.sel;
    if (stage === 'perform') {
      if (!R.focus || !R.actors[R.focus]) { var c = scene.cast(R)[0]; R.focus = c ? c.id : null; }
      if (R.focus) scene.hand(app, R.focus, true);
      app.setMode('perform');
      scene.cast(R).forEach(function (it) { var a = R.actors[it.id]; a.solver.reset(); a.inp.pose.flip = !!it.flip; });
    } else {
      if (scene.stop) scene.stop(app);
      Object.keys(R.actors).forEach(function (id) { var a = R.actors[id]; a.inp.stop(); a.inp.micStop(); a.inp.camStop(); a.inp.walker.reset(); });
      R.shot = 'wide'; R.free = null; app.setMode('build'); app.live = false;
    }
    app.syncUndo(); app.refreshPanel();
  };
})(typeof self !== 'undefined' ? self : this);
