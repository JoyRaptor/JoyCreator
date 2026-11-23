/* AvatarLab: THE SCENE IN HAND. Its Set up, Arrange and Motion cards, the sheets, and the menu row.
 *
 * The room's four stages work on whatever is in hand (al-scene-room.js). With THE SCENE in hand:
 *   SET UP   brings things in. The body map's place shows the scene's picture, and a tap on it chooses or adds scenes. Then: what
 *            can be added (a character, an object, a backdrop, a foreground), the set, everything in the scene, its name.
 *   ARRANGE  places them: drag, size, how far back, flip; and the frame's shapes and margins to try.
 *   MOTION   the scene alive with nothing recorded: the wind, the loops, the haze, and the shots to try.
 *   PERFORM  the room as always: the character in hand (its expressions are the card) and the one take bar.
 * A character in the scene is put in hand by "Open" here, two taps on it on the stage, or holding its card in Perform. Then the
 * same four stages are that character's, alone on the plain stage, and the path tab with the scene's name brings the scene back.
 *
 * Phone: the cards are the same LazyColumn sections as a character's; the scene's picture is a 74 x 92 dp tile in the body map's place.
 */
(function (root) {
  'use strict';
  var AL = root.AL, scene = AL.scene, app = AL.app, h = AL.h, frame = AL.frame, H = scene.H;
  function $(id) { return document.getElementById(id); }
  function esc(s) { return String(s).replace(/[&<>]/g, function (c) { return c === '&' ? '&amp;' : c === '<' ? '&lt;' : '&gt;'; }); }

  var CSS = [
    '#app.scene #tools,#app.scene #stagebar,#app.scene #empty{display:none!important}',
    '#app.scene #panelhead>.rail{flex:1 0 100%}',
    '.scpic{flex:none;width:74px;height:92px;border-radius:12px;background:var(--raised);display:flex;flex-direction:column;overflow:hidden;color:var(--dim);box-shadow:inset 0 0 0 1px var(--line2)}',
    '.scpic:hover{color:var(--ink);box-shadow:inset 0 0 0 1px var(--faint)}',
    '.scpic img,.scpic>i{flex:1;min-height:0;width:100%;object-fit:cover;display:grid;place-items:center;color:var(--faint);background:#0b0b0e}',
    '.scpic span{flex:none;height:22px;display:flex;align-items:center;justify-content:center;gap:2px;font:600 10.5px/1 var(--f-body)} .scpic span svg.ic{width:12px;height:12px}',
    '.scl{display:flex;flex-direction:column;gap:4px}',
    '.scl button{display:flex;align-items:center;gap:9px;min-height:42px;padding:4px 10px 4px 5px;border-radius:11px;background:var(--raised);text-align:left;font:500 13px/1.2 var(--f-body);color:var(--ink)}',
    '.scl button.on{box-shadow:inset 0 0 0 2px var(--sel)}',
    '.scl img,.scl i{width:34px;height:34px;flex:none;object-fit:contain;border-radius:7px;background:rgba(0,0,0,.35);display:grid;place-items:center;color:var(--faint)}',
    '.scl small{margin-left:auto;font:500 9px/1 var(--f-mono);letter-spacing:.06em;text-transform:uppercase;color:var(--faint)}',
    '.setrow{display:flex;gap:6px;overflow-x:auto;scrollbar-width:none;padding:1px} .setrow::-webkit-scrollbar{display:none}',
    '.setrow button{flex:none;width:70px;height:58px;border-radius:10px;background:var(--raised);position:relative;overflow:hidden;display:flex;align-items:flex-end;color:var(--ink)}',
    '.setrow button img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover} .setrow button.plain{background:radial-gradient(120% 90% at 50% 38%,#1a1a22 0%,#0d0d10 62%,#050507 100%)}',
    '.setrow button span{position:relative;flex:1;font:600 10px/1 var(--f-body);padding:3px 4px 4px;background:rgba(9,9,11,.72);text-align:center}',
    '.setrow button.on{box-shadow:inset 0 0 0 2px var(--sel)} .setrow button.on span{background:var(--sel);color:var(--on-sel)}',
    '.sctx{width:100%;height:42px;border-radius:11px;background:var(--raised);color:var(--ink);padding:0 12px;font:500 15px/1 var(--f-body);border:1px solid var(--line2);margin:4px 0 8px}',
    '.shelf.scn .sc{height:84px} .shelf.scn .sc img{object-fit:cover;border-radius:8px} .shelf.scn .sc.new svg.ic{width:24px;height:24px} .shelf.scn .sc.new{gap:4px}',
    '.shelf.scn .sc.blank>i{border-radius:8px;background:radial-gradient(120% 90% at 50% 38%,#1a1a22 0%,#0d0d10 62%,#050507 100%)}',
    '.shelfhr{border:0;border-top:1px solid var(--line2);margin:0 0 12px}'
  ].join('\n');
  document.head.appendChild(h('style', { text: CSS }));

  /* ── small things ── */
  function sld(label, o) { var first = true; return app.ui.slider(label, { min: o.min, max: o.max, step: o.step, value: o.value, fmt: o.fmt, title: o.title, undo: false, set: function (v) { if (first) { scene.snap(app, label); first = false; } o.set(v); scene.touch(app); }, done: function () { first = true; if (o.done) o.done(); } }); }
  function toCanvas(file) { return new Promise(function (res) { var u = URL.createObjectURL(file), im = new Image(); im.onload = function () { var k = Math.min(1, 2400 / Math.max(im.width, im.height)), c = document.createElement('canvas'); c.width = Math.max(1, Math.round(im.width * k)); c.height = Math.max(1, Math.round(im.height * k)); c.getContext('2d').drawImage(im, 0, 0, c.width, c.height); URL.revokeObjectURL(u); res(c); }; im.onerror = function () { URL.revokeObjectURL(u); res(null); }; im.src = u; }); }
  function pick(many, cb) {
    var f = pick.el || (pick.el = h('input', { type: 'file', accept: 'image/*', style: 'display:none' })); if (!f.parentNode) document.body.appendChild(f);
    f.multiple = !!many; f.value = ''; f.onchange = function () { var files = Array.prototype.slice.call(f.files || []); if (!files.length) return; Promise.all(files.map(toCanvas)).then(function (cs) { cs = cs.filter(Boolean); if (!cs.length) { app.toast('That picture could not be read.'); return; } cb(cs, files[0].name.replace(/\.[^.]+$/, '').replace(/[_-]+/g, ' ')); }); }; f.click();
  }
  scene.addFromFiles = function (app, kind) { pick(kind === 'obj', function (cs, name) { var it = scene.addPictures(app, kind, kind === 'obj' ? cs : [cs[0]], name); if (it && cs.length > 1 && kind === 'obj') app.toast(cs.length + ' pictures: they play as a loop.'); }); };
  /** A small picture of one thing in the scene (its card, its path tab). */
  scene.picOf = function (R, it) {
    if (it.kind === 'cast') return R.actors[it.id] ? R.actors[it.id].pic : null;
    var im = AL.img.get(it.frames ? it.frames[0] : it.img); if (!im) return null; if (im._t) return im._t;
    try { var c = document.createElement('canvas'), k = Math.min(1, 72 / Math.max(im.w, im.h)); c.width = Math.max(1, Math.round(im.w * k)); c.height = Math.max(1, Math.round(im.h * k)); c.getContext('2d').drawImage(im.canvas, 0, 0, c.width, c.height); return (im._t = c.toDataURL('image/webp', 0.8)); } catch (e) { return null; }
  };
  var setPics = {};
  scene.setPic = function (k) { if (setPics[k]) return setPics[k]; var c = document.createElement('canvas'); c.width = 192; c.height = 108; c.getContext('2d').drawImage(scene.paintSet(k), 0, 0, 192, 108); return (setPics[k] = c.toDataURL('image/webp', 0.85)); };
  /** The scene's own picture, as last kept. */
  scene.picNow = function (R) { var me = (scene._list || []).filter(function (e) { return e.uid === R.data.uid; })[0]; return me && me.pic ? me.pic : null; };
  function stripOn(k) { if (AL.strip) AL.strip.toggle(app, k, true); }

  /* ── sheets ── */
  /** Add a character. In the empty scene there is room for one, so a new scene is made for the two of them (the empty scene stays as it is). */
  scene.castSheet = function (app) {
    var row = h('div', { class: 'shelf' }), stock = [['topps', 'Topps'], ['pip', 'Pip']], fresh = !app.scene; if (AL.intake.hasJoybot()) stock.push(['joybot', 'Joybot']);
    function put(src, name) {
      app.closeSheet();
      var go = fresh ? scene.list(app).then(function (l) { return scene.create(app, 'plain', 'Scene ' + (l.length + 1)); }).then(function () { return scene.addCast(app, src); }) : scene.addCast(app, src);
      go.then(function (it) { if (it && it.lost) app.toast(name + ' could not be brought in.'); else if (app.scene && app.scene.stage === 'perform') { stripOn('ch'); if (it && app.scene.actors[it.id]) scene.hand(app, it.id); } });
    }
    function card(pic, name, sub, src) { return h('button', { class: 'sc own', title: 'Put ' + name + ' in the scene', onclick: function () { put(src, name); } }, [pic ? h('img', { src: pic, alt: '' }) : h('i', null, AL.icon('smile')), h('b', { text: name }), sub ? h('small', { text: sub }) : null]); }
    function fill(mine) { row.innerHTML = ''; mine.forEach(function (e) { row.appendChild(card(e.pic, e.name || 'Character', null, { uid: e.uid, name: e.name })); }); stock.forEach(function (s) { row.appendChild(card(AL.shelf.stockPic(s[0]), s[1], 'built in', { stock: s[0], name: s[1] })); }); }
    fill([]); AL.shelf.list(app).then(fill);
    app.sheet('Add a character', h('div', null, [h('div', { class: 'shelfw' }, row), app.ui.note(fresh ? 'The empty scene holds one character. A new scene is made for the two of them, on the plain stage; the empty scene stays as it is.' : 'The same character can be in a scene more than once. Change a character later and every scene it is in follows.')]), []);
  };
  /** A new scene: pick the set to start on (Plain is the dark stage, with nothing behind). It opens on the stage you are on, or
      o.stage (the menu opens it in Perform). */
  scene.newSheet = function (app, o) {
    var row = h('div', { class: 'shelf' }), was = (o && o.stage) || (app.scene ? (app.scene.dive ? (app.mode === 'perform' ? 'perform' : app.stage) : app.scene.stage) : app.mode === 'perform' ? 'perform' : app.stage);
    function make(k, label) { return h('button', { class: 'sc own' + (k === 'plain' ? ' blank' : ''), title: k === 'plain' ? 'Start on the plain dark stage' : 'Start on the ' + label + ' set', onclick: function () { app.closeSheet(); scene.list(app).then(function (l) { return scene.create(app, k, 'Scene ' + (l.length + 1), { stage: was === 'perform' || !was ? 'perform' : was }); }).then(function () { if (app.scene && app.scene.stage === 'perform') stripOn('sc'); }); } }, [k === 'plain' ? h('i') : h('img', { src: scene.setPic(k), alt: '', style: 'object-fit:cover;border-radius:8px' }), h('b', { text: label })]); }
    row.appendChild(make('plain', 'Plain'));
    Object.keys(scene.SETS).forEach(function (k) { row.appendChild(make(k, scene.SETS[k].name)); });
    row.classList.add('scn');
    app.sheet('A new scene', h('div', null, [h('div', { class: 'shelfw' }, row), app.ui.note('Pick a set to start on. ' + (app.scene ? '' : 'The character you are on walks in with you. ') + 'Everything can be changed after: your own backdrop, more characters, objects, the frame.')]), []);
  };
  /** The scenes you have, the empty scene first: from the scene's picture in Set up, Arrange and Motion. Choosing one keeps the stage. */
  function scenesSheet() {
    var row = h('div', { class: 'shelf scn' }), cur = app.scene ? app.scene.data.uid : scene.EMPTY, st = app.scene ? app.scene.stage : app.stage;
    function fill(l) {
      row.innerHTML = '';
      row.appendChild(h('button', { class: 'sc own blank' + (cur === scene.EMPTY ? ' on' : ''), title: 'The empty scene: the plain stage, one character, nothing else', onclick: function () { app.closeSheet(); if (app.scene) scene.close(app); } }, [h('i'), h('b', { text: 'Empty' }), cur === scene.EMPTY ? h('small', { text: 'in use' }) : null]));
      l.forEach(function (e) { row.appendChild(h('button', { class: 'sc own' + (e.uid === cur ? ' on' : ''), title: e.uid === cur ? '“' + e.name + '” is the scene in use' : 'Change to “' + e.name + '”', onclick: function () { app.closeSheet(); if (e.uid !== cur) scene.openId(app, e.uid, { stage: st, hand: 'scene' }); } }, [e.pic ? h('img', { src: e.pic, alt: '' }) : h('i', null, AL.icon('image')), h('b', { text: e.name || 'Scene' }), e.uid === cur ? h('small', { text: 'in use' }) : null])); });
      row.appendChild(h('button', { class: 'sc new', title: 'A new scene', onclick: function () { app.closeSheet(); scene.newSheet(app); } }, [AL.icon('plus'), h('b', { text: 'New scene' })]));
    }
    fill(scene._list || []); scene.list(app).then(function (l) { scene._list = l; fill(l); });
    app.sheet('Scenes', h('div', null, [h('div', { class: 'shelfw' }, row), app.ui.note('The empty scene is the plain stage. It is always first and it cannot be deleted.')]), []);
  }
  scene.scenesSheet = scenesSheet;
  scene.saveFile = function (app) { var o = scene.toJson(app, true); return AL.intake.saveFile((o.name || 'scene').replace(/[^\w\- ]+/g, '') + '.scene.json', new Blob([JSON.stringify(o)], { type: 'application/json' })); };
  function renameSheet() {
    var d = app.scene.data, inp = h('input', { class: 'sctx', type: 'text', value: d.name, maxlength: 40, 'aria-label': 'The name of this scene', onkeydown: function (e) { if (e.key === 'Enter') { app.closeSheet(); scene.rename(app, inp.value); } } });
    app.sheet('Name this scene', inp, [{ label: 'Cancel', cls: 'quiet', fn: app.closeSheet }, { label: 'Keep the name', cls: 'go', fn: function () { app.closeSheet(); scene.rename(app, inp.value); } }]);
    setTimeout(function () { try { inp.focus(); inp.select(); } catch (e) { } }, 60);
  }

  /* ── the cards for the scene in hand ── */
  var WHAT = { setup: 'Bring things in: characters, objects, a backdrop, a foreground.', fit: 'Place them: drag, size, how far back. Try the frame\'s shapes.', motion: 'The scene alive. Nothing is recorded here.' };
  function head(R) {
    var ph = $('panelhead'), pic = scene.picNow(R); ph.innerHTML = '';
    if (!scene._list && !head.asked) { head.asked = true; scene.list(app).then(function (l) { scene._list = l; if (app.scene === R && !R.dive && R.stage !== 'perform') app.refreshPanel(true); }); }      // (just after a reload: the scenes' pictures are read once)
    ph.appendChild(h('button', { class: 'scpic', title: 'Scenes: change scene, or make a new one', 'aria-label': 'Scenes', onclick: scenesSheet }, [pic ? h('img', { src: pic, alt: '' }) : h('i', null, AL.icon('image')), h('span', null, ['Scenes'])]));
    ph.appendChild(h('div', { class: 'ptitle' }, [h('div', { class: 'ph1' }, [h('h2', null, [R.data.name]), AL.stages.crumbs(app)]), AL.stages.rail(app), h('p', { html: WHAT[R.stage] || '' })]));
  }
  function list(pb, R, onPick) {
    var l = h('div', { class: 'scl' }), order = R.data.items.slice().sort(function (p, q) { var k = { front: 0, cast: 1, obj: 1, back: 2 }; return k[p.kind] - k[q.kind] || (q.y || 0) - (p.y || 0); });
    order.forEach(function (q) { var pic = scene.picOf(R, q), on = R.stage === 'motion' && q.kind === 'cast' ? R.focus === q.id : R.sel === q.id; l.appendChild(h('button', { class: on ? 'on' : '', title: 'Take ' + q.name + ' in hand', onclick: function () { onPick(q); } }, [pic ? h('img', { src: pic, alt: '' }) : h('i', null, AL.icon(q.kind === 'cast' ? 'person' : 'image')), h('span', { text: q.name + (q.lost ? ' (missing)' : '') }), h('small', { text: scene.KIND[q.kind] })])); });
    if (!order.length) l.appendChild(app.ui.note('Nothing yet. Add a character, or pick a set.'));
    pb.appendChild(l);
  }
  function select(q) { var R = app.scene; R.sel = R.sel === q.id ? null : q.id; app.refreshPanel(true); }
  function setRow(pb, d) {
    var made = d.items.filter(function (q) { return q.made; })[0], row = h('div', { class: 'setrow', role: 'group', 'aria-label': 'The set' });
    row.appendChild(h('button', { class: 'plain' + (!made && !d.items.some(function (q) { return q.kind === 'back'; }) ? ' on' : ''), title: 'No set: the plain dark stage behind', onclick: function () { if (made) scene.edit(app, 'No set', function (dd) { dd.items = dd.items.filter(function (q) { return !q.made; }); }); } }, [h('span', { text: 'Plain' })]));
    Object.keys(scene.SETS).forEach(function (k) { row.appendChild(h('button', { class: made && made.made === k ? 'on' : '', title: 'The ' + scene.SETS[k].name + ' set that comes with the room', onclick: function () { scene.setSet(app, k); } }, [h('img', { src: scene.setPic(k), alt: '' }), h('span', { text: scene.SETS[k].name })])); });
    pb.appendChild(row);
  }
  function setupCard(pb, R) {
    var d = R.data, ui = app.ui, it = R.sel ? scene.item(R, R.sel) : null, stands = it && (it.kind === 'obj' || it.kind === 'cast');
    app.coach(it ? '<b>' + esc(it.name) + '.</b> Place it in <b>' + AL.stages.WORD2 + '</b>.' + (it.kind === 'cast' ? ' Open it to work on the character itself.' : '') : '<b>Set up.</b> Bring things in. Tap one on the stage to take it.');
    if (it) {
      pb.appendChild(ui.lab(scene.KIND[it.kind] + ': ' + it.name));
      if (it.lost) pb.appendChild(ui.note('This character is no longer on your shelf, so it cannot be shown.'));
      var row = [];
      if (it.kind === 'cast' && !it.lost) row.push(ui.chip('Open ' + it.name, { cls: 'go', icon: 'person', title: 'Put ' + it.name + ' in hand: its parts, its fit, its motion, alone on the plain stage. The tab with the scene\'s name brings the scene back. (Two taps on it on the stage do the same.)', fn: function () { scene.diveIn(app, it.id, 'setup'); } }));
      if (stands && !it.lost) row.push(ui.chip('Another one', { icon: 'plus', title: 'One more of this, beside it', fn: function () { scene.copy(app, it.id); } }));
      row.push(ui.chip('Remove', { cls: 'quiet', icon: 'trash', title: 'Take it out of the scene (Undo brings it back)', fn: function () { scene.remove(app, it.id); } }));
      pb.appendChild(ui.row(row));
    }
    pb.appendChild(ui.lab('Add to the scene'));
    pb.appendChild(ui.row([
      ui.chip('Character', { cls: it ? '' : 'go', icon: 'person', title: 'Someone from your shelf, or one that comes with the room', fn: function () { scene.castSheet(app); } }),
      ui.chip('Object', { icon: 'image', title: 'A picture that stands on the floor: a tree, a chair, a sign. Pick several pictures and they play as a loop.', fn: function () { scene.addFromFiles(app, 'obj'); } }),
      ui.chip('Backdrop', { icon: 'layers', title: 'A picture behind everything. It fills the stage.', fn: function () { scene.addFromFiles(app, 'back'); } }),
      ui.chip('Foreground', { icon: 'layers', title: 'A picture in front of everything: leaves at the edge, a window frame', fn: function () { scene.addFromFiles(app, 'front'); } })
    ]));
    pb.appendChild(ui.lab('The set')); setRow(pb, d);
    pb.appendChild(ui.lab('In this scene')); list(pb, R, select);
    pb.appendChild(ui.lab('This scene'));
    pb.appendChild(ui.row([
      ui.chip('Name it', { icon: 'note', title: 'Give this scene a name', fn: renameSheet }),
      ui.chip('Delete the scene', { cls: 'quiet', icon: 'trash', title: 'Throw this scene away. The characters in it stay on your shelf. You are back in the empty scene.', fn: function () { app.ask('Delete “' + d.name + '”?', 'The scene, its takes and its camera lane are thrown away. This cannot be undone. The characters in it stay on your shelf, and you are back in the empty scene.', 'Delete it', function () { scene.forget(app, d.uid).then(function () { app.toast('The scene was deleted.'); }); }, true); } })
    ]));
  }
  function arrangeCard(pb, R) {
    var d = R.data, ui = app.ui, it = R.sel ? scene.item(R, R.sel) : null, stands = it && (it.kind === 'obj' || it.kind === 'cast'), f = d.floor;
    app.coach(it ? '<b>' + esc(it.name) + '.</b> ' + (stands ? 'Drag it. <b>Up the floor</b> sends it back. The round handle resizes it.' : 'Drag to slide it. The round handle resizes it.') : '<b>' + AL.stages.WORD2 + '.</b> Tap a thing to take it. Drag it <b>up the floor</b> and it goes back.');
    if (it && !it.lost) {
      pb.appendChild(ui.lab(scene.KIND[it.kind] + ': ' + it.name));
      pb.appendChild(sld('Size', { min: 5, max: stands ? 300 : 400, value: Math.round((it.s || 1) * 100), fmt: function (v) { return v + '%'; }, set: function (v) { it.s = v / 100; }, title: 'How big it is. The round handle on the stage does the same.' }));
      if (stands) pb.appendChild(sld('How far back', { min: -15, max: 110, value: Math.round((f.near - it.y / H) / (f.near - f.far) * 100), fmt: function (v) { return v <= 2 ? 'at the front' : v >= 98 ? 'at the back' : v + '%'; }, set: function (v) { it.y = Math.round(H * (f.near - v / 100 * (f.near - f.far))); }, title: 'Where it stands on the floor. Further back is smaller, and behind whatever stands in front. Dragging it up the stage does the same.' }));
      else pb.appendChild(sld('Depth when the camera moves', { min: -40, max: 70, value: Math.round((it.par == null ? (it.kind === 'back' ? 0.22 : -0.15) : it.par) * 100), fmt: function (v) { return v > 4 ? 'far (' + v + ')' : v < -4 ? 'near (' + v + ')' : 'on the stage'; }, set: function (v) { it.par = v / 100; }, title: 'A far backdrop slides less than the stage when the camera moves; a near foreground slides more. That is what makes a close-up feel deep.' }));
      pb.appendChild(ui.row([ui.chip('Flip', { on: !!it.flip, icon: 'flip', title: it.kind === 'cast' ? 'Face the other way' : 'Mirror it', fn: function () { scene.set(app, it.id, { flip: !it.flip }, 'Flip ' + it.name); } })]));
    }
    pb.appendChild(ui.lab('The frame'));
    pb.appendChild(ui.toggle('Show the frame', frame.on(), function (v) { frame.set(app, v); app.refreshPanel(true); }, 'Where the picture\'s edges are. It only shows them: nothing moves.'));
    pb.appendChild(ui.row([ui.seg(scene.SHAPES.map(function (q) { return [q[0], q[0], q[2]]; }), d.shape, function (v) { frame.setShape(app, v); if (!frame.on()) frame.set(app, true); }, 'The shape of the picture')]));
    pb.appendChild(ui.row([ui.seg([['off', 'No margins', 'Just the frame'], ['title', 'Title safe', 'The classic margins: keep action inside the cyan line and words inside the pink one'], ['social', 'App buttons', 'About where an app lays its own buttons and words over an upright video. Every app differs: treat it as a guide.']], d.safe || 'off', function (v) { scene.edit(app, 'Margins', function (q) { q.safe = v; }); if (!frame.on()) frame.set(app, true); }, 'Safe margins')]));
    pb.appendChild(ui.note('Changing the shape never moves anything: the frame is a window on the same stage. What is outside it stays there, ready to walk in.'));
    pb.appendChild(ui.lab('In this scene')); list(pb, R, select);
  }
  function motionCard(pb, R) {
    var d = R.data, ui = app.ui, it = R.sel ? scene.item(R, R.sel) : null;
    app.coach('<b>Motion.</b> The scene is alive and nothing is recorded. Touch a character to move it, an object for its wind.');
    if (it && it.kind === 'obj') {
      pb.appendChild(ui.lab('Object: ' + it.name));
      pb.appendChild(ui.toggle('Sways in the wind', !!it.sway, function (v) { scene.set(app, it.id, { sway: v ? 0.6 : 0 }, 'Sways in the wind'); }, 'Its top leans with the breeze and its foot stays put: grass, a tree, a flag'));
      if (it.sway) pb.appendChild(sld('How much it sways', { min: 5, max: 100, value: Math.round(it.sway * 100), fmt: function (v) { return v + '%'; }, set: function (v) { it.sway = v / 100; } }));
      if (it.frames && it.frames.length > 1) pb.appendChild(sld('Loop speed', { min: 1, max: 24, value: it.fps || 8, fmt: function (v) { return v + ' a second'; }, set: function (v) { it.fps = v; }, title: 'How fast its ' + it.frames.length + ' pictures play' }));
    }
    pb.appendChild(ui.lab('The air'));
    pb.appendChild(sld('Wind', { min: 0, max: 100, value: Math.round((d.wind == null ? 0.5 : d.wind) * 100), fmt: function (v) { return v ? v + '%' : 'still'; }, set: function (v) { d.wind = v / 100; }, title: 'How hard the breeze blows on everything that sways' }));
    pb.appendChild(sld('Haze', { min: 0, max: 100, value: Math.round((d.haze == null ? 0.5 : d.haze) * 100), fmt: function (v) { return v ? v + '%' : 'clear'; }, set: function (v) { d.haze = v / 100; }, title: 'Things at the back take a little of the colour of the air: it reads as distance' }));
    pb.appendChild(ui.lab('Try the shots'));
    if (!frame.on()) { pb.appendChild(ui.note('Shots need the frame.')); pb.appendChild(ui.row([ui.chip('Show the frame', { icon: 'fit', title: 'Turn the frame on, then try the shots', fn: function () { frame.set(app, true); app.refreshPanel(true); } })])); }
    else pb.appendChild(ui.row(scene.shots(app).map(function (s) { return ui.chip(s.kind === 'close' ? 'Close on ' + s.name : s.name, { on: R.shot === s.key, title: 'Send the camera there', fn: function () { scene.shot(app, s.key, frame.room.glide ? 0.9 : 0); app.refreshPanel(true); } }); })));
    pb.appendChild(ui.lab('In this scene')); list(pb, R, function (q) { if (q.kind === 'cast' && R.actors[q.id]) { R.sel = null; scene.hand(app, q.id); } else select(q); });
  }
  function scenePanel(keep) {
    var R = app.scene, pb = $('panelbody'), sc = pb.scrollTop;
    app.pinProvider = null; app.padDraw = null; app.padDraws = []; $('stagebar').innerHTML = '';
    head(R); pb.innerHTML = ''; $('chname').textContent = R.data.name;
    if (R.stage === 'setup') setupCard(pb, R); else if (R.stage === 'motion') motionCard(pb, R); else arrangeCard(pb, R);
    var key = 'scene|' + R.stage; pb.scrollTop = keep || app._cardKey === key ? sc : 0; app._cardKey = key;
  }
  /* Perform with nobody in hand: the rail, and a way to bring someone in */
  function nobody() {
    var R = app.scene, ph = $('panelhead'), pb = $('panelbody'), ui = app.ui, loading = Object.keys(R.loading).length > 0;
    app.pinProvider = null; app.padDraw = null; app.padDraws = []; $('stagebar').innerHTML = '';
    ph.innerHTML = ''; ph.appendChild(AL.stages.rail(app)); pb.innerHTML = ''; $('chname').textContent = R.data.name;
    if (loading) { app.coach('<b>Perform.</b> The cast is coming in…'); pb.appendChild(ui.note('Bringing the cast in…')); return; }
    app.coach('<b>Perform.</b> Nobody is in this scene yet.');
    pb.appendChild(ui.note('Put a character in the scene and it comes alive here: you take it in hand, and its expressions are on this card.'));
    pb.appendChild(ui.row([ui.chip('Add a character', { cls: 'go', icon: 'person', fn: function () { scene.castSheet(app); } })]));
  }

  /* ── the room's own wiring, taught about scenes ── */
  var refresh0 = app.refreshPanel, undo0 = app.undo, sync0 = app.syncUndo, joy0 = app.joybot;
  function after() { if (AL.strip) AL.strip.sync(app, true); if (scene.remember) scene.remember(app); }
  app.refreshPanel = function (keep) {
    var R = app.scene, el = $('app'); el.classList.toggle('dive', !!(R && R.dive)); el.classList.toggle('scene', !!(R && !R.dive));
    if (!R || R.dive) { refresh0(keep); after(); return; }
    if (R.stage === 'perform') {
      if (R.focus && R.actors[R.focus] && app.inp === R.actors[R.focus].inp) { refresh0(keep); $('chname').textContent = R.data.name + ' · ' + app.ch.name; }
      else nobody();
    } else scenePanel(keep);
    after();
  };
  function arranging() { var R = app.scene; return !!(R && !R.dive && R.stage !== 'perform'); }
  app.undo = function (redo, quiet) { return arranging() ? scene.undo(app, redo, quiet) : undo0(redo, quiet); };
  app.syncUndo = function () {
    if (!arranging()) return sync0();
    var p = app.scene.hist.past, f = app.scene.hist.future;
    $('bundo').disabled = !p.length; $('bredo').disabled = !f.length;
    $('bundo').title = p.length ? 'Undo: ' + p[p.length - 1].l : 'Nothing to undo'; $('bredo').title = f.length ? 'Redo: ' + f[f.length - 1].l : 'Nothing to redo';
  };
  $('bname').addEventListener('click', function (e) { if (scene.on(app)) { e.stopImmediatePropagation(); renameSheet(); } }, true);
  /* Joybot in a scene: the same button, the things a scene can do. Each is one named act (al-agent.js), so on the phone he can do them, not only say them. */
  app.joybot = function () {
    if (!scene.on(app)) return joy0();
    var R = app.scene, body = h('div', { class: 'joytips' }), cast = scene.cast(R);
    function tip(title, text, fn) { body.appendChild(h('button', { title: title, onclick: function () { app.closeSheet(); fn(); } }, [h('div', null, [h('b', { text: title }), h('span', { text: text })])])); }
    tip(cast.length ? 'Give them someone to talk to' : 'Put someone in the scene', 'A second character stands beside the first. Whoever you are not moving watches whoever you are.', function () { scene.castSheet(app); });
    tip('Put something on the stage', 'A tree, a chair, a sign: any picture. Drag it up the floor and it goes back, smaller, behind whatever stands in front.', function () { if (R.stage !== 'setup') scene.go(app, 'setup'); scene.addFromFiles(app, 'obj'); });
    if (cast.length) tip('Cut to a close-up', 'Shots are on the strip in Perform: the director\'s chair, then Shots. Tap one to go there. A close-up follows the character.', function () { scene.go(app, 'perform'); if (AL.strip) AL.strip.toggle(app, 'sh', true); scene.shot(app, 'close:' + (R.focus || cast[0].id), 0.9); });
    tip('Make it upright for a phone', 'The frame becomes 9:16. Nothing moves: it is a window on the same stage, so the same performance can go out both ways.', function () { frame.setShape(app, R.data.shape === '9:16' ? '16:9' : '9:16'); if (!frame.on()) frame.set(app, true); });
    if (cast.length > 1) tip('Record everyone, one at a time', 'Take one character in hand and record. Then take the next: the first plays back while you perform the second.', function () { scene.go(app, 'perform'); if (AL.strip) AL.strip.toggle(app, 'ch', true); app.toast('Tap a character on the strip, then the red button.'); });
    body.appendChild(app.ui.note('On the phone Joybot does these for you when you ask, and so can a model you connect: every act in this room is a named tool.'));
    app.sheet('Joybot', body, []);
  };

  /* ── the menu: Create new avatar, a line, Create new scene and the scenes you have. A scene opens in Perform with the Scene part up. ── */
  scene.row = function (app) {
    var row = h('div', { class: 'shelf scn' }), wrap = h('div', { class: 'shelfw' }, row); row.style.setProperty('--scw', '88px');
    function fill(l) {
      row.innerHTML = ''; var cur = app.scene && app.scene.data.uid;
      row.appendChild(h('button', { class: 'sc new', title: 'Create a new scene: a set, things standing in it, a cast, a camera', onclick: function () { app.closeSheet(); scene.newSheet(app, { stage: 'perform' }); } }, [AL.icon('plus'), h('b', { text: 'Create new scene' })]));
      l.forEach(function (e) { row.appendChild(h('button', { class: 'sc own' + (e.uid === cur ? ' on' : ''), title: e.uid === cur ? '“' + e.name + '” is the scene in use: open it in Perform' : 'Open the scene “' + e.name + '” in Perform', onclick: function () { app.closeSheet(); (e.uid === cur ? Promise.resolve(scene.go(app, 'perform')) : scene.openId(app, e.uid, { stage: 'perform' })).then(function () { stripOn('sc'); }); } }, [e.pic ? h('img', { src: e.pic, alt: '' }) : h('i', null, AL.icon('image')), h('b', { text: e.name || 'Scene' }), e.uid === cur ? h('small', { text: 'in use' }) : null])); });
    }
    fill([]); scene.list(app).then(function (l) { scene._list = l; fill(l); });
    return h('div', null, [h('hr', { class: 'shelfhr' }), wrap]);
  };
})(typeof self !== 'undefined' ? self : this);
