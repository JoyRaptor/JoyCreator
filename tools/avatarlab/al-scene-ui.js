/* AvatarLab: THE SCENE, IN THE HAND. Arrange, the dock, the sheets, the menu row.
 *
 * ARRANGE (the panel)  the thing in hand first (size, how far back, flip, wind, open the character), then what can be added,
 *                      the frame's shape and margins, a list of everything in the scene, the set, and where it goes next.
 * THE DOCK (on the stage, always there)
 *     Scenes   a card for every scene you have: one tap changes scene, without leaving Perform
 *     Cast     a card for everyone in this scene: one tap puts them in hand, and the panel is their expressions
 *     Shots    Wide, Two-shot, Close on each, your own: tap cuts, hold eases
 *   On a phone it is one row with three small tabs. On a tablet all three rows are there at once.
 *   Record and Play sit on the dock too, so they are in reach whatever the panel is scrolled to.
 * A character in the scene opens into its own four stages (two taps on it, or "Open") and "Back to the scene" returns.
 *
 * Phone: the dock is three LazyRows in a frosted Surface; cards are 58 x 52 dp; hold = combinedClickable(onLongClick).
 */
(function (root) {
  'use strict';
  var AL = root.AL, scene = AL.scene, app = AL.app, h = AL.h, W = scene.W, H = scene.H;
  function $(id) { return document.getElementById(id); }
  function esc(s) { return String(s).replace(/[&<>]/g, function (c) { return c === '&' ? '&amp;' : c === '<' ? '&lt;' : '&gt;'; }); }
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }

  var CSS = [
    '#app.scene #tools,#app.scene #stagebar,#app.scene #empty{display:none!important}',
    '#app.scene #panelhead>.rail{flex:1 0 100%}',
    '#scenedock{position:absolute;left:6px;right:6px;bottom:6px;display:none;flex-direction:column;gap:4px;pointer-events:none;z-index:3}',
    '#app.scene #scenedock{display:flex}', '#scenedock>*{pointer-events:auto}',
    '.sdbar{display:flex;align-items:center;gap:6px;pointer-events:none!important}', '.sdbar>*{pointer-events:auto}',
    '.sdtabs,.sdgo,.sdrow{background:var(--veil);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,.07)}',
    '.sdtabs{display:inline-flex;border-radius:999px;padding:2px}',
    '.sdtabs button{height:28px;padding:0 11px;border-radius:999px;font:600 11.5px/1 var(--f-body);color:var(--dim)}',
    '.sdtabs button.on{background:var(--sel);color:var(--on-sel)}',
    '.sdgo{margin-left:auto;display:inline-flex;align-items:center;gap:4px;border-radius:999px;padding:2px 9px 2px 2px}',
    '.sdgo .recb{width:32px;height:32px} .sdgo .recb i{width:13px;height:13px} .sdgo .recb.on i{width:11px;height:11px}',
    '.sdgo .ib{width:30px;height:30px;color:var(--ink)} .sdgo .ib.on{background:var(--sel);color:var(--on-sel)}',
    '.sdgo output{font:500 10px/1 var(--f-mono);color:var(--dim);min-width:30px;text-align:right}',
    '.sdrow{display:flex;align-items:stretch;gap:6px;border-radius:14px;padding:5px}',
    '.sdrow>label{writing-mode:vertical-rl;transform:rotate(180deg);font:500 8.5px/1 var(--f-mono);letter-spacing:.1em;text-transform:uppercase;color:var(--faint);text-align:center;display:none}',
    '#scenedock.all .sdrow>label{display:block} #scenedock.all .sdtabs{display:none}',
    '#scenedock.one .sdrow{display:none} #scenedock.one .sdrow.on{display:flex}',
    '.sdcards{display:flex;gap:5px;overflow-x:auto;scrollbar-width:none;flex:1;min-width:0} .sdcards::-webkit-scrollbar{display:none}',
    '.sdc{flex:none;width:58px;height:52px;border-radius:10px;background:var(--raised);position:relative;overflow:hidden;display:flex;flex-direction:column;align-items:center;justify-content:flex-end;color:var(--dim)}',
    '.sdc:active{transform:scale(.95)}',
    '.sdc img{position:absolute;left:0;top:0;width:100%;height:100%;object-fit:cover}',
    '.sdc.fit img{object-fit:contain;height:calc(100% - 11px)}',
    '.sdc.close img{object-fit:cover;object-position:50% 0;transform:scale(2);transform-origin:50% 6%}',
    '.sdc span{position:relative;z-index:1;font:600 9px/1 var(--f-body);padding:2px 3px 3px;align-self:stretch;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;background:rgba(9,9,11,.72);color:var(--ink);text-align:center}',
    '.sdc.on{box-shadow:inset 0 0 0 2px var(--sel)} .sdc.on span{background:var(--sel);color:var(--on-sel)}',
    '.sdc.new{background:linear-gradient(135deg,#CC27FF,#5C43FD);color:#fff;justify-content:center;gap:1px} .sdc.new span{background:none;color:#fff}',
    '.sdc.plain{justify-content:center;gap:1px} .sdc.plain span{background:none;color:var(--dim)}',
    '.sdc svg.ic{position:relative;z-index:1}',
    '.sdc i.fr{position:absolute;left:11px;right:11px;top:8px;bottom:19px;border:1.5px solid currentColor;border-radius:3px;display:flex;align-items:flex-end;justify-content:center;gap:3px;padding-bottom:2px}',
    '.sdc i.fr b{width:5px;height:9px;border-radius:3px 3px 1px 1px;background:currentColor}', '.sdc i.fr.own{border-style:dashed}', '.sdc.on i.fr{color:var(--sel)}',
    '#sceneback{position:absolute;left:8px;top:8px;z-index:4;display:none}', '#app.dive #sceneback{display:inline-flex}', '#app.dive #coach{top:46px}',
    '.scl{display:flex;flex-direction:column;gap:4px}',
    '.scl button{display:flex;align-items:center;gap:9px;min-height:42px;padding:4px 10px 4px 5px;border-radius:11px;background:var(--raised);text-align:left;font:500 13px/1.2 var(--f-body);color:var(--ink)}',
    '.scl button.on{box-shadow:inset 0 0 0 2px var(--sel)}',
    '.scl img,.scl i{width:34px;height:34px;flex:none;object-fit:contain;border-radius:7px;background:rgba(0,0,0,.35);display:grid;place-items:center;color:var(--faint)}',
    '.scl small{margin-left:auto;font:500 9px/1 var(--f-mono);letter-spacing:.06em;text-transform:uppercase;color:var(--faint)}',
    '.sctx{width:100%;height:42px;border-radius:11px;background:var(--raised);color:var(--ink);padding:0 12px;font:500 15px/1 var(--f-body);border:1px solid var(--line2)}',
    '.shelf.scn .sc{height:84px} .shelf.scn .sc img{object-fit:cover;border-radius:8px} .shelf.scn .sc.new svg.ic{width:24px;height:24px} .shelf.scn .sc.new{gap:4px}', '.shelfhr{border:0;border-top:1px solid var(--line2);margin:0 0 12px}'
  ].join('\n');
  document.head.appendChild(h('style', { text: CSS }));
  var st0 = $('stage');
  st0.appendChild(h('div', { id: 'scenedock' }));
  st0.appendChild(h('button', { id: 'sceneback', class: 'chip on', title: 'Back to the scene, as you left it', onclick: function () { scene.undive(app); } }, [AL.icon('back'), h('span', { text: 'Back to the scene' })]));
  var bx = h('button', { class: 'ib', id: 'bexport', title: 'Send to Studio, or save a file', 'aria-label': 'Send to Studio, or save a file', onclick: function () { AL.intake.send(app); } }, AL.icon('send'));
  $('bjoy').parentNode.insertBefore(bx, $('bjoy'));

  /* "Send to Studio", wherever it is pressed: in a scene it is the scene that goes */
  var send0 = AL.intake.send; AL.intake.send = function (a) { return scene.on(a) ? scene.sendSheet(a) : send0(a); };

  /* ── small things ── */
  function sld(label, o) { var first = true; return app.ui.slider(label, { min: o.min, max: o.max, step: o.step, value: o.value, fmt: o.fmt, title: o.title, undo: false, set: function (v) { if (first) { scene.snap(app, label); first = false; } o.set(v); scene.touch(app); }, done: function () { first = true; if (o.done) o.done(); } }); }
  function toCanvas(file) { return new Promise(function (res) { var u = URL.createObjectURL(file), im = new Image(); im.onload = function () { var k = Math.min(1, 2400 / Math.max(im.width, im.height)), c = document.createElement('canvas'); c.width = Math.max(1, Math.round(im.width * k)); c.height = Math.max(1, Math.round(im.height * k)); c.getContext('2d').drawImage(im, 0, 0, c.width, c.height); URL.revokeObjectURL(u); res(c); }; im.onerror = function () { URL.revokeObjectURL(u); res(null); }; im.src = u; }); }
  function pick(many, cb) {
    var f = pick.el || (pick.el = h('input', { type: 'file', accept: 'image/*', style: 'display:none' })); if (!f.parentNode) document.body.appendChild(f);
    f.multiple = !!many; f.value = ''; f.onchange = function () { var files = Array.prototype.slice.call(f.files || []); if (!files.length) return; Promise.all(files.map(toCanvas)).then(function (cs) { cs = cs.filter(Boolean); if (!cs.length) { app.toast('That picture could not be read.'); return; } cb(cs, files[0].name.replace(/\.[^.]+$/, '').replace(/[_-]+/g, ' ')); }); }; f.click();
  }
  scene.addFromFiles = function (app, kind) { pick(kind === 'obj', function (cs, name) { var it = scene.addPictures(app, kind, kind === 'obj' ? cs : [cs[0]], name); if (it && cs.length > 1 && kind === 'obj') app.toast(cs.length + ' pictures: they play as a loop.'); }); };
  function picOf(R, it) {
    if (it.kind === 'cast') return R.actors[it.id] ? R.actors[it.id].pic : null;
    var im = AL.img.get(it.frames ? it.frames[0] : it.img); if (!im) return null; if (im._t) return im._t;
    try { var c = document.createElement('canvas'), k = Math.min(1, 72 / Math.max(im.w, im.h)); c.width = Math.max(1, Math.round(im.w * k)); c.height = Math.max(1, Math.round(im.h * k)); c.getContext('2d').drawImage(im.canvas, 0, 0, c.width, c.height); return (im._t = c.toDataURL('image/webp', 0.8)); } catch (e) { return null; }
  }
  var setPics = {};
  function setPic(k) { if (setPics[k]) return setPics[k]; var c = document.createElement('canvas'); c.width = 192; c.height = 108; c.getContext('2d').drawImage(scene.paintSet(k), 0, 0, 192, 108); return (setPics[k] = c.toDataURL('image/webp', 0.85)); }

  /* ── sheets ── */
  function castSheet() {
    var row = h('div', { class: 'shelf' }), stock = [['topps', 'Topps'], ['pip', 'Pip']]; if (AL.intake.hasJoybot()) stock.push(['joybot', 'Joybot']);
    function card(pic, name, sub, src) { return h('button', { class: 'sc own', title: 'Put ' + name + ' in the scene', onclick: function () { app.closeSheet(); scene.addCast(app, src).then(function (it) { if (it && it.lost) app.toast(name + ' could not be brought in.'); }); } }, [pic ? h('img', { src: pic, alt: '' }) : h('i', null, AL.icon('smile')), h('b', { text: name }), sub ? h('small', { text: sub }) : null]); }
    function fill(mine) { row.innerHTML = ''; mine.forEach(function (e) { row.appendChild(card(e.pic, e.name || 'Character', null, { uid: e.uid, name: e.name })); }); stock.forEach(function (s) { row.appendChild(card(AL.shelf.stockPic(s[0]), s[1], 'built in', { stock: s[0], name: s[1] })); }); }
    fill([]); AL.shelf.list(app).then(fill);
    app.sheet('Add a character', h('div', null, [h('div', { class: 'shelfw' }, row), app.ui.note('The same character can be in a scene more than once. Change a character later and every scene it is in follows.')]), []);
  }
  scene.castSheet = castSheet;
  /** A new scene: pick the set to start on. */
  scene.newSheet = function (app) {
    var row = h('div', { class: 'shelf' });
    Object.keys(scene.SETS).forEach(function (k) { row.appendChild(h('button', { class: 'sc own', title: 'Start on the ' + scene.SETS[k].name + ' set', onclick: function () { app.closeSheet(); scene.list(app).then(function (l) { var was = app.scene && app.scene.stage; return scene.create(app, k, 'Scene ' + (l.length + 1)).then(function () { if (was === 'perform') scene.go(app, 'perform'); }); }); } }, [h('img', { src: setPic(k), alt: '', style: 'object-fit:cover;border-radius:8px' }), h('b', { text: scene.SETS[k].name })])); });
    app.sheet('A new scene', h('div', null, [h('div', { class: 'shelfw' }, row), app.ui.note('Pick a set to start on. ' + (app.scene ? '' : 'The character you are on walks in with you. ') + 'Everything can be changed after: your own backdrop, more characters, objects, the frame.')]), []);
  };
  scene.saveFile = function (app) { var o = scene.toJson(app, true); return AL.intake.saveFile((o.name || 'scene').replace(/[^\w\- ]+/g, '') + '.scene.json', new Blob([JSON.stringify(o)], { type: 'application/json' })); };
  scene.sendSheet = function (app) {
    var R = app.scene, d = R.data, ui = app.ui, snd = d.send || (d.send = { bg: 'scene', skip: {} }), body = h('div');
    body.appendChild(ui.note('On the phone this lands in Studio as <b>one scene layer that is still alive</b>. Studio draws it at whatever size the video is, and you can come back here to change a take. Nothing is flattened until the video itself is exported.'));
    body.appendChild(ui.lab('What goes'));
    d.items.forEach(function (it) { body.appendChild(ui.toggle(it.name, !snd.skip[it.id], function (v) { if (v) delete snd.skip[it.id]; else snd.skip[it.id] = 1; scene.touch(app); scene.sendSheet(app); }, scene.KIND[it.kind])); });
    body.appendChild(ui.lab('Behind it'));
    body.appendChild(ui.seg([['scene', 'The scene'], ['clear', 'See-through'], ['key', 'A key colour']], snd.bg, function (v) { snd.bg = v; scene.touch(app); scene.sendSheet(app); }, 'What is behind'));
    body.appendChild(ui.note('In this web pilot it saves the scene file the phone will read: the set, every character with its rig, every take and the camera lane, in one file.'));
    app.sheet('Send to Studio', body, [{ label: 'Save the scene file', cls: 'go', icon: 'save', fn: function () { app.closeSheet(); scene.saveFile(app); } }]);
  };
  function renameSheet() {
    var d = app.scene.data, inp = h('input', { class: 'sctx', type: 'text', value: d.name, maxlength: 40, 'aria-label': 'The name of this scene', onkeydown: function (e) { if (e.key === 'Enter') { app.closeSheet(); scene.rename(app, inp.value); } } });
    app.sheet('Name this scene', inp, [{ label: 'Cancel', cls: 'quiet', fn: app.closeSheet }, { label: 'Keep the name', cls: 'go', fn: function () { app.closeSheet(); scene.rename(app, inp.value); } }]);
    setTimeout(function () { try { inp.focus(); inp.select(); } catch (e) { } }, 60);
  }

  /* ── ARRANGE: the panel ── */
  function rail() {
    var R = app.scene, r = h('div', { class: 'rail', role: 'tablist', 'aria-label': 'What you are doing in the scene' });
    [['arrange', 'Arrange', 'Put the scene together: the set, what stands in it, who is in it, the frame'], ['perform', 'Perform', 'Everything is alive. Take a character in hand, pick shots, record']].forEach(function (q) {
      r.appendChild(h('button', { class: (R.stage === q[0] ? 'on' : '') + (q[0] === 'perform' ? ' live' : ''), role: 'tab', 'aria-selected': String(R.stage === q[0]), title: q[1] + ': ' + q[2], text: q[1], onclick: function () { scene.go(app, q[0]); } }));
    });
    return r;
  }
  function itemCard(pb, it) {
    var R = app.scene, d = R.data, ui = app.ui, stands = it.kind === 'obj' || it.kind === 'cast', f = d.floor, a = R.actors[it.id];
    pb.appendChild(ui.lab(scene.KIND[it.kind] + ': ' + it.name));
    if (it.lost) { pb.appendChild(ui.note('This character is no longer on your shelf, so it cannot be shown.')); pb.appendChild(ui.row([ui.chip('Remove', { cls: 'quiet', icon: 'trash', fn: function () { scene.remove(app, it.id); } })])); return; }
    if (it.kind === 'cast') pb.appendChild(ui.row([ui.chip('Open ' + it.name, { cls: 'go', icon: 'person', title: 'Step into this character: its parts, its fit, its motion. The scene waits. (Two taps on the stage does the same.)', fn: function () { scene.diveIn(app, it.id); } })]));
    pb.appendChild(sld('Size', { min: 5, max: stands ? 300 : 400, value: Math.round((it.s || 1) * 100), fmt: function (v) { return v + '%'; }, set: function (v) { it.s = v / 100; }, title: 'How big it is. The round handle on the stage does the same.' }));
    if (stands) pb.appendChild(sld('How far back', { min: -15, max: 110, value: Math.round((f.near - it.y / H) / (f.near - f.far) * 100), fmt: function (v) { return v <= 2 ? 'at the front' : v >= 98 ? 'at the back' : v + '%'; }, set: function (v) { it.y = Math.round(H * (f.near - v / 100 * (f.near - f.far))); }, title: 'Where it stands on the floor. Further back is smaller, and behind whatever stands in front. Dragging it up the stage does the same.' }));
    else pb.appendChild(sld('Depth when the camera moves', { min: -40, max: 70, value: Math.round((it.par == null ? (it.kind === 'back' ? 0.22 : -0.15) : it.par) * 100), fmt: function (v) { return v > 4 ? 'far (' + v + ')' : v < -4 ? 'near (' + v + ')' : 'on the stage'; }, set: function (v) { it.par = v / 100; }, title: 'A far backdrop slides less than the stage when the camera moves; a near foreground slides more. That is what makes a close-up feel deep.' }));
    var row = [ui.chip('Flip', { on: !!it.flip, icon: 'flip', title: it.kind === 'cast' ? 'Face the other way' : 'Mirror it', fn: function () { scene.set(app, it.id, { flip: !it.flip }, 'Flip ' + it.name); } })];
    if (stands) row.push(ui.chip('Another one', { icon: 'plus', title: 'One more of this, beside it', fn: function () { scene.copy(app, it.id); } }));
    row.push(ui.chip('Remove', { cls: 'quiet', icon: 'trash', title: 'Take it out of the scene (Undo brings it back)', fn: function () { scene.remove(app, it.id); } }));
    pb.appendChild(ui.row(row));
    if (it.kind === 'obj') {
      pb.appendChild(ui.toggle('Sways in the wind', !!it.sway, function (v) { scene.set(app, it.id, { sway: v ? 0.6 : 0 }, 'Sways in the wind'); }, 'Its top leans with the breeze and its foot stays put: grass, a tree, a flag'));
      if (it.sway) pb.appendChild(sld('How much it sways', { min: 5, max: 100, value: Math.round(it.sway * 100), fmt: function (v) { return v + '%'; }, set: function (v) { it.sway = v / 100; } }));
      if (it.frames && it.frames.length > 1) pb.appendChild(sld('Loop speed', { min: 1, max: 24, value: it.fps || 8, fmt: function (v) { return v + ' a second'; }, set: function (v) { it.fps = v; }, title: 'How fast its ' + it.frames.length + ' pictures play' }));
    }
    if (a && a.inp.take && a.inp.take.n) pb.appendChild(ui.note('Has a take of <b>' + (Math.round(a.inp.duration() * 10) / 10) + ' s</b> in this scene.'));
  }
  function panel(keep) {
    var R = app.scene, d = R.data, ui = app.ui, pb = $('panelbody'), ph = $('panelhead'), sc = pb.scrollTop;
    app.pinProvider = null; app.padDraw = null; app.padDraws = []; $('stagebar').innerHTML = '';
    ph.innerHTML = ''; ph.appendChild(rail()); pb.innerHTML = ''; $('chname').textContent = d.name;
    if (R.stage === 'perform') {
      app.coach('<b>Perform.</b> Nobody is in this scene yet.');
      pb.appendChild(ui.note('Put a character in the scene and it comes alive here: you take it in hand, and its expressions are on this card.'));
      pb.appendChild(ui.row([ui.chip('Add a character', { cls: 'go', icon: 'person', fn: castSheet })])); return;
    }
    var it = R.sel ? scene.item(R, R.sel) : null, stands = it && (it.kind === 'obj' || it.kind === 'cast');
    app.coach(it ? '<b>' + esc(it.name) + '.</b> ' + (stands ? 'Drag it. <b>Up the floor</b> sends it back. The round handle resizes it.' : 'Drag to slide it. The round handle resizes it.') : '<b>Arrange.</b> Tap a thing to take it. Drag it <b>up the floor</b> and it goes back.');
    if (it) itemCard(pb, it);
    pb.appendChild(ui.lab('Add to the scene'));
    pb.appendChild(ui.row([
      ui.chip('Character', { cls: it ? '' : 'go', icon: 'person', title: 'Someone from your shelf, or one that comes with the room', fn: castSheet }),
      ui.chip('Object', { icon: 'image', title: 'A picture that stands on the floor: a tree, a chair, a sign. Pick several pictures and they play as a loop.', fn: function () { scene.addFromFiles(app, 'obj'); } }),
      ui.chip('Backdrop', { icon: 'layers', title: 'A picture behind everything. It fills the stage.', fn: function () { scene.addFromFiles(app, 'back'); } }),
      ui.chip('Foreground', { icon: 'layers', title: 'A picture in front of everything: leaves at the edge, a window frame', fn: function () { scene.addFromFiles(app, 'front'); } })
    ]));
    pb.appendChild(ui.lab('The frame'));
    pb.appendChild(ui.row([ui.seg(scene.SHAPES.map(function (q) { return [q[0], q[0], q[2]]; }), d.shape, function (v) { scene.shape(app, v); }, 'The shape of the picture')]));
    pb.appendChild(ui.row([ui.seg([['off', 'No margins', 'Just the frame'], ['title', 'Title safe', 'The classic margins: keep action inside the cyan line and words inside the pink one'], ['social', 'App buttons', 'About where an app lays its own buttons and words over an upright video. Every app differs: treat it as a guide.']], d.safe || 'off', function (v) { scene.edit(app, 'Margins', function (q) { q.safe = v; }); }, 'Safe margins')]));
    pb.appendChild(ui.note('Changing the shape never moves anything: the frame is a window on the same stage. What is outside it stays there, dimmed, ready to walk in.'));
    pb.appendChild(ui.lab('In this scene'));
    var list = h('div', { class: 'scl' }), order = d.items.slice().sort(function (p, q) { var k = { front: 0, cast: 1, obj: 1, back: 2 }; return k[p.kind] - k[q.kind] || (q.y || 0) - (p.y || 0); });
    order.forEach(function (q) { var pic = picOf(R, q); list.appendChild(h('button', { class: R.sel === q.id ? 'on' : '', title: 'Take ' + q.name + ' in hand', onclick: function () { R.sel = q.id; app.refreshPanel(); } }, [pic ? h('img', { src: pic, alt: '' }) : h('i', null, AL.icon(q.kind === 'cast' ? 'person' : 'image')), h('span', { text: q.name + (q.lost ? ' (missing)' : '') }), h('small', { text: scene.KIND[q.kind] })])); });
    if (!order.length) list.appendChild(ui.note('Nothing yet. Add a character, or pick a set below.'));
    pb.appendChild(list);
    pb.appendChild(ui.lab('The set'));
    var made = d.items.filter(function (q) { return q.made; })[0];
    pb.appendChild(ui.row(Object.keys(scene.SETS).map(function (k) { return ui.chip(scene.SETS[k].name, { on: !!(made && made.made === k), title: 'The ' + scene.SETS[k].name + ' set that comes with the room', fn: function () { scene.setSet(app, k); } }); })));
    pb.appendChild(sld('Wind', { min: 0, max: 100, value: Math.round((d.wind == null ? 0.5 : d.wind) * 100), fmt: function (v) { return v ? v + '%' : 'still'; }, set: function (v) { d.wind = v / 100; }, title: 'How hard the breeze blows on everything that sways' }));
    pb.appendChild(sld('Haze', { min: 0, max: 100, value: Math.round((d.haze == null ? 0.5 : d.haze) * 100), fmt: function (v) { return v ? v + '%' : 'clear'; }, set: function (v) { d.haze = v / 100; }, title: 'Things at the back take a little of the colour of the air: it reads as distance' }));
    if (d.shots.length) { pb.appendChild(ui.lab('Shots you kept')); pb.appendChild(ui.row(d.shots.map(function (s) { return ui.chip(s.name, { icon: 'trash', cls: 'quiet', title: 'Remove the shot “' + s.name + '”', fn: function () { scene.dropShot(app, s.id); } }); }))); }
    pb.appendChild(ui.lab('Use it'));
    pb.appendChild(ui.row([
      ui.chip('Send to Studio', { cls: 'go', icon: 'send', title: 'The whole scene, still alive, as one layer in your video', fn: function () { scene.sendSheet(app); } }),
      ui.chip('Name it', { icon: 'note', fn: renameSheet }),
      ui.chip('Leave the scene', { cls: 'quiet', icon: 'back', title: 'It is kept. You are back on the character you were on.', fn: function () { scene.close(app); } }),
      ui.chip('Delete the scene', { cls: 'quiet', icon: 'trash', title: 'Throw this scene away. The characters in it stay on your shelf.', fn: function () { app.ask('Delete “' + d.name + '”?', 'The scene, its takes and its camera lane are thrown away. This cannot be undone. The characters in it stay on your shelf.', 'Delete it', function () { scene.forget(app, d.uid).then(function () { app.toast('The scene was deleted.'); }); }, true); } })
    ]));
    pb.scrollTop = keep ? sc : 0;
  }

  /* ── THE DOCK ── */
  var tab = null, DK = { sig: '' };
  function fmt(t) { var s = Math.max(0, t); return Math.floor(s / 60) + ':' + ('0' + Math.floor(s % 60)).slice(-2); }
  function shotCard(s) {
    var R = app.scene, held = false, tm = null, kids;
    if (s.kind === 'close') kids = [s.pic ? h('img', { src: s.pic, alt: '' }) : h('i', { class: 'fr' }, [h('b')]), h('span', { text: s.name })];
    else kids = [h('i', { class: 'fr' + (s.kind === 'own' ? ' own' : '') }, s.kind === 'two' ? [h('b'), h('b')] : s.kind === 'own' ? [] : [h('b', { style: 'height:6px;width:4px' })]), h('span', { text: s.name })];
    var b = h('button', { class: 'sdc' + (s.kind === 'close' ? ' close' : '') + (R.shot === s.key ? ' on' : ''), 'data-shot': s.key, title: (s.kind === 'close' ? 'Close on ' + s.name : s.name) + '. Tap to cut there, hold to glide there.', 'aria-pressed': String(R.shot === s.key) }, kids);
    b.onpointerdown = function () { held = false; clearTimeout(tm); tm = setTimeout(function () { held = true; scene.shot(app, s.key, 0.9); mark(); }, 380); };
    b.onpointerup = b.onpointerleave = b.onpointercancel = function () { clearTimeout(tm); };
    b.onclick = function () { if (held) { held = false; return; } scene.shot(app, s.key, 0); mark(); };
    b.oncontextmenu = function (e) { e.preventDefault(); };
    return b;
  }
  function mark() { var R = app.scene; if (!R) return; Array.prototype.forEach.call($('scenedock').querySelectorAll('[data-shot]'), function (b) { var on = b.getAttribute('data-shot') === R.shot; b.classList.toggle('on', on); b.setAttribute('aria-pressed', String(on)); }); DK.shot = R.shot; }
  function cards(k) {
    var R = app.scene, c = h('div', { class: 'sdcards' }), live = R.stage === 'perform';
    if (k === 'scenes') {
      var l = scene._list || [], cur = R.data.uid, me = l.filter(function (e) { return e.uid === cur; })[0];
      c.appendChild(h('button', { class: 'sdc on', title: '“' + R.data.name + '” is the scene you are in' }, [me && me.pic ? h('img', { src: me.pic, alt: '' }) : null, h('span', { text: R.data.name })]));
      l.forEach(function (e) { if (e.uid === cur) return; c.appendChild(h('button', { class: 'sdc', title: 'Change to the scene “' + e.name + '”', onclick: function () { scene.openId(app, e.uid).then(function (ok) { if (ok && live) scene.go(app, 'perform'); }); } }, [e.pic ? h('img', { src: e.pic, alt: '' }) : null, h('span', { text: e.name })])); });
      c.appendChild(h('button', { class: 'sdc new', title: 'Create a new scene', onclick: function () { scene.newSheet(app); } }, [AL.icon('plus'), h('span', { text: 'New scene' })]));
    } else if (k === 'cast') {
      scene.cast(R).forEach(function (it) { var a = R.actors[it.id]; c.appendChild(h('button', { class: 'sdc fit' + (R.focus === it.id ? ' on' : ''), title: R.focus === it.id ? it.name + ' is in hand: your fingers and voice move them, and the card below is theirs' : 'Take ' + it.name + ' in hand', 'aria-pressed': String(R.focus === it.id), onclick: function () { if (R.focus !== it.id) scene.hand(app, it.id); } }, [a.pic ? h('img', { src: a.pic, alt: '' }) : null, h('span', { text: it.name })])); });
      c.appendChild(h('button', { class: 'sdc new', title: 'Add a character to the scene', onclick: castSheet }, [AL.icon('plus'), h('span', { text: 'Add' })]));
    } else {
      scene.shots(app).forEach(function (s) { c.appendChild(shotCard(s)); });
      if (R.shot === 'free') c.appendChild(h('button', { class: 'sdc new', title: 'Keep the view you framed by hand as a shot of this scene', onclick: function () { scene.keepShot(app); } }, [AL.icon('star'), h('span', { text: 'Keep shot' })]));
      if (R.data.cam.length) c.appendChild(h('button', { class: 'sdc plain', title: 'Forget the ' + R.data.cam.length + ' camera changes recorded in this scene', onclick: function () { scene.clearCam(app); } }, [AL.icon('trash'), h('span', { text: 'Clear cuts' })]));
    }
    return c;
  }
  function dock() {
    var el = $('scenedock'), R = app.scene; if (!R || R.dive) { el.innerHTML = ''; DK.sig = ''; return; }
    var live = R.stage === 'perform', all = $('app').classList.contains('land') && $('stage').clientHeight >= 500, rows = live ? ['scenes', 'cast', 'shots'] : ['scenes'], run = live ? scene.running(app) : null;
    if (!tab || rows.indexOf(tab) < 0 || DK.stage !== R.stage) tab = live ? (scene.cast(R).length > 1 ? 'cast' : 'shots') : 'scenes';      // (coming into Perform: who is in hand, or the shots)
    DK.stage = R.stage;
    el.className = all ? 'all' : 'one'; el.innerHTML = '';
    var bar = h('div', { class: 'sdbar' });
    if (rows.length > 1) { var tb = h('div', { class: 'sdtabs', role: 'tablist' }); [['scenes', 'Scenes'], ['cast', 'Cast'], ['shots', 'Shots']].forEach(function (q) { tb.appendChild(h('button', { class: tab === q[0] ? 'on' : '', role: 'tab', title: q[1], text: q[1], onclick: function () { tab = q[0]; dock(); } })); }); bar.appendChild(tb); }
    if (live) {
      DK.out = h('output', { text: '0:00' });
      bar.appendChild(h('div', { class: 'sdgo' }, [
        h('button', { class: 'recb' + (run === 'rec' ? ' on' : ''), title: run === 'rec' ? 'Stop recording' : 'Record the character in hand. Everyone else plays back in step.', 'aria-pressed': String(run === 'rec'), onclick: function () { scene.rec(app); } }, h('i')),
        h('button', { class: 'ib' + (run === 'play' ? ' on' : ''), title: run ? 'Stop' : 'Play the whole scene', onclick: function () { scene.play(app); } }, AL.icon(run ? 'stop' : 'play')), DK.out]));
    } else DK.out = null;
    if (bar.children.length) el.appendChild(bar);
    rows.forEach(function (k) { el.appendChild(h('div', { class: 'sdrow' + (k === tab || rows.length === 1 ? ' on' : ''), 'data-k': k }, [h('label', { text: k }), cards(k)])); });
    DK.sig = sig(); DK.shot = R.shot;
    if (!scene._listAt || Date.now() - scene._listAt > 1500) { scene._listAt = Date.now(); scene.list(app).then(function (l) { var s0 = JSON.stringify((scene._list || []).map(function (e) { return e.uid + e.at; })), s1 = JSON.stringify(l.map(function (e) { return e.uid + e.at; })); scene._list = l; if (s0 !== s1 && app.scene) dock(); }); }
  }
  function sig() { var R = app.scene; return [R.stage, R.focus, scene.running(app), R.shot === 'free', R.data.cam.length ? 1 : 0, $('app').classList.contains('land') && $('stage').clientHeight >= 500, scene.cast(R).length, R.data.shots.length].join('|'); }
  /** Once a frame: the clock on the dock, the shot in use, and a rebuild if anything it shows has changed. */
  scene.dockTick = function (app) {
    var R = app.scene; if (!R) return;
    if (DK.sig !== sig()) { dock(); if (R.stage === 'perform') app.refreshPanel(true); return; }
    if (DK.shot !== R.shot) mark();
    if (DK.out) DK.out.textContent = fmt(R.clock.on || scene.running(app) ? (R.actors[R.focus] && scene.running(app) && R.actors[R.focus].inp.take ? R.actors[R.focus].inp.ph : R.clock.ph) : scene.length(app));
  };
  scene.dockSoon = function () { DK.sig = ''; };

  /* ── the room's own wiring, taught about scenes ── */
  var refresh0 = app.refreshPanel, undo0 = app.undo, sync0 = app.syncUndo, joy0 = app.joybot;
  app.refreshPanel = function (keep) {
    var R = app.scene; $('app').classList.toggle('dive', !!(R && R.dive));
    if (!R) { $('scenedock').innerHTML = ''; return refresh0(keep); }
    if (R.dive) { $('scenedock').innerHTML = ''; return refresh0(keep); }
    if (R.stage === 'perform' && R.focus && R.actors[R.focus] && app.inp === R.actors[R.focus].inp) {
      refresh0(keep);
      var ph = $('panelhead'); Array.prototype.slice.call(ph.querySelectorAll('.rail')).forEach(function (r) { r.remove(); }); ph.insertBefore(rail(), ph.firstChild);
      $('chname').textContent = R.data.name + ' · ' + app.ch.name;
    } else panel(keep);
    dock();
  };
  function arranging() { var R = app.scene; return !!(R && !R.dive && R.stage === 'arrange'); }
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
    tip(cast.length ? 'Give them someone to talk to' : 'Put someone in the scene', 'A second character stands beside the first. Whoever you are not moving watches whoever you are.', castSheet);
    tip('Put something on the stage', 'A tree, a chair, a sign: any picture. Drag it up the floor and it goes back, smaller, behind whatever stands in front.', function () { if (R.stage !== 'arrange') scene.go(app, 'arrange'); scene.addFromFiles(app, 'obj'); });
    if (cast.length) tip('Cut to a close-up', 'Shots are on the dock in Perform. Tap one to cut, hold it to glide. A close-up follows the character.', function () { scene.go(app, 'perform'); tab = 'shots'; scene.shot(app, 'close:' + (R.focus || cast[0].id), 0.9); dock(); });
    tip('Make it upright for a phone', 'The frame becomes 9:16. Nothing moves: it is a window on the same stage, so the same performance can go out both ways.', function () { scene.shape(app, R.data.shape === '9:16' ? '16:9' : '9:16'); });
    if (cast.length > 1) tip('Record everyone, one at a time', 'Take one character in hand and record. Then take the next: the first plays back while you perform the second.', function () { scene.go(app, 'perform'); tab = 'cast'; dock(); app.toast('Tap a character on the dock, then the red button.'); });
    body.appendChild(app.ui.note('On the phone Joybot does these for you when you ask, and so can a model you connect: every act in this room is a named tool.'));
    app.sheet('Joybot', body, []);
  };

  /* ── the menu: Create new avatar, a line, Create new scene and the scenes you have ── */
  scene.row = function (app) {
    var row = h('div', { class: 'shelf scn' }), wrap = h('div', { class: 'shelfw' }, row); row.style.setProperty('--scw', '88px');
    function fill(l) {
      row.innerHTML = ''; var cur = app.scene && app.scene.data.uid;
      row.appendChild(h('button', { class: 'sc new', title: 'Create a new scene: a set, things standing in it, a cast, a camera', onclick: function () { app.closeSheet(); scene.newSheet(app); } }, [AL.icon('plus'), h('b', { text: 'Create new scene' })]));
      l.forEach(function (e) { row.appendChild(h('button', { class: 'sc own' + (e.uid === cur ? ' on' : ''), title: e.uid === cur ? '“' + e.name + '” is the scene you are in' : 'Open the scene “' + e.name + '”', onclick: function () { app.closeSheet(); if (e.uid !== cur) scene.openId(app, e.uid); } }, [e.pic ? h('img', { src: e.pic, alt: '' }) : h('i', null, AL.icon('image')), h('b', { text: e.name || 'Scene' }), e.uid === cur ? h('small', { text: 'open now' }) : null])); });
    }
    fill([]); scene.list(app).then(fill);
    return h('div', null, [h('hr', { class: 'shelfhr' }), wrap]);
  };

  /* back where you were: the scene that was open when the page was last used opens again */
  var tries = 0, tm = setInterval(function () {
    if (++tries > 40 || root.__alNoScene) { clearInterval(tm); return; }
    if (!app.ch || !app.G) return; clearInterval(tm);
    try { var id = localStorage.getItem('al.scene'); if (id && !app.scene) scene.openId(app, id); } catch (e) { }
  }, 400);
})(typeof self !== 'undefined' ? self : this);
