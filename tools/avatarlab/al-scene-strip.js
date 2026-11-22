/* AvatarLab: THE DIRECTOR'S CHAIR AND THE STRIP (the owner's sketches, 11-21).
 *
 * In Perform two quiet grey round buttons sit at the bottom right of the stage: the frame (al-frame.js) and the director's chair.
 * The chair opens upward into three toggles, SCENE, CHARACTER and SHOTS, each grey until it is on (solid cyan). A strip above the
 * stage rail shows whichever are on and shares its whole width between them: one alone has all of it, two a half each, three a
 * third each. Each part scrolls sideways by itself and ends in its own add card.
 *
 *   A card     tap: choose it (the scene changes; the character is in hand and its expressions fill the card below; the camera
 *              goes to the shot). Long press, or a second tap on the one already chosen: set it up (a scene or a character goes
 *              in hand at the second stage; a shot is framed by hand, named, kept or removed).
 *   Scene      the empty scene first (the plain stage: it cannot be deleted), your scenes, then a new one
 *   Character  everyone in the scene (in the empty scene, the one character), then add
 *   Shots      Cut or Glide (a two-way switch), Wide, Two-shot, Close on each, yours, then keep this view. Shots need the frame:
 *              turning Shots on turns the frame on, and turning the frame off folds Shots away.
 *
 * Phone: the strip is a Row of weight(1f) LazyRows in a frosted Surface; cards are 58 x 52 dp with combinedClickable(onLongClick);
 * the two buttons are 36 dp IconButtons in the stage's bottom-end corner.
 */
(function (root) {
  'use strict';
  var AL = root.AL, app = AL.app, h = AL.h, scene = AL.scene, frame = AL.frame, ROOM = frame.room, strip = AL.strip = {}, NS = 'http://www.w3.org/2000/svg';
  function $(id) { return document.getElementById(id); }

  var CSS = [
    '#strip{position:absolute;left:6px;right:6px;bottom:6px;display:none;gap:6px;z-index:3}',
    '.spart{flex:1 1 0;min-width:0;display:flex;flex-direction:column;gap:3px;padding:4px 5px 5px;border-radius:14px;background:var(--veil);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,.07);box-shadow:inset 0 2px 0 -0.5px var(--pc)}',
    '.spart.p-sc{--pc:#34d399} .spart.p-ch{--pc:#c084fc} .spart.p-sh{--pc:#fbbf24}',
    '.sph{display:flex;align-items:center;gap:6px;height:20px;padding:0 2px 0 4px}',
    '.sph b{font:500 8.5px/1 var(--f-mono);letter-spacing:.1em;text-transform:uppercase;color:var(--pc);flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '.sph .seg{padding:1px;background:rgba(0,0,0,.35)} .sph .seg button{height:18px;padding:0 7px;font-size:10px}',
    '.spc{display:flex;gap:5px;overflow-x:auto;scrollbar-width:none;min-width:0} .spc::-webkit-scrollbar{display:none}',
    '.sdc{flex:none;width:58px;height:52px;border-radius:10px;background:var(--raised);position:relative;overflow:hidden;display:flex;flex-direction:column;align-items:center;justify-content:flex-end;color:var(--dim);touch-action:pan-x;-webkit-touch-callout:none}',
    '.sdc:active{transform:scale(.95)}',
    '.sdc img{position:absolute;left:0;top:0;width:100%;height:100%;object-fit:cover;pointer-events:none}',
    '.sdc.fit img{object-fit:contain;height:calc(100% - 11px)}',
    '.sdc.close img{object-fit:cover;object-position:50% 0;transform:scale(2);transform-origin:50% 6%}',
    '.sdc span{position:relative;z-index:1;font:600 9px/1 var(--f-body);padding:2px 3px 3px;align-self:stretch;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;background:rgba(9,9,11,.72);color:var(--ink);text-align:center;pointer-events:none}',
    '.sdc.on{box-shadow:inset 0 0 0 2px var(--sel)} .sdc.on span{background:var(--sel);color:var(--on-sel)}',
    '.sdc.add{width:28px;background:none;box-shadow:inset 0 0 0 1.5px var(--pc);border-radius:9px;justify-content:center;color:var(--pc);opacity:.8} .sdc.add:hover{opacity:1;background:rgba(255,255,255,.04)} .sdc.add svg.ic{width:16px;height:16px}',
    '.sdc.blank{background:radial-gradient(120% 90% at 50% 38%,#1a1a22 0%,#0d0d10 62%,#050507 100%)}',
    '.sdc.blank::before{content:"";position:absolute;inset:0;background-image:linear-gradient(var(--line) 1px,transparent 1px),linear-gradient(90deg,var(--line) 1px,transparent 1px);background-size:8px 8px;opacity:.22;pointer-events:none}',
    '.sdc i.fr{position:absolute;left:11px;right:11px;top:8px;bottom:19px;border:1.5px solid currentColor;border-radius:3px;display:flex;align-items:flex-end;justify-content:center;gap:3px;padding-bottom:2px;pointer-events:none}',
    '.sdc i.fr b{width:5px;height:9px;border-radius:3px 3px 1px 1px;background:currentColor}', '.sdc i.fr.own{border-style:dashed}', '.sdc.on i.fr{color:var(--sel)}',
    '#roombtns{position:absolute;right:8px;bottom:8px;display:none;gap:8px;z-index:4;align-items:flex-end}',
    '.rb{width:36px;height:36px;border-radius:50%;display:grid;place-items:center;background:var(--veil);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,.08);color:var(--dim);touch-action:none;-webkit-touch-callout:none}',
    '.rb:hover{color:var(--ink);border-color:rgba(255,255,255,.16)} .rb.on{background:var(--sel);color:var(--on-sel);border-color:transparent} .rb.open{background:var(--pressed);color:var(--ink)}',
    '.rb svg{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round;pointer-events:none}',
    '.rb svg.frg text{font:700 6.3px/1 var(--f-mono);fill:currentColor;stroke:none;letter-spacing:-.02em}',
    '#chairmenu{position:absolute;right:0;bottom:calc(100% + 6px);display:none;flex-direction:column;align-items:flex-end;gap:5px}',
    '#chairmenu.open{display:flex} #chairmenu .chip{height:30px;padding:0 11px 0 9px;font-size:12px} #chairmenu .chip svg.ic{width:15px;height:15px}',
    '.frshapes{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:4px}',
    '.frsh{width:58px;height:58px;border-radius:12px;background:var(--raised);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:3px;color:var(--dim);font:600 11px/1 var(--f-mono)}',
    '.frsh:hover{background:var(--pressed);color:var(--ink)} .frsh.on{background:var(--sel);color:var(--on-sel)}',
    '.frsh svg.frp{width:28px;height:28px;fill:none;stroke:currentColor;stroke-width:1.6}',
    /* from the side (an upright frame): the parts share its height, each scrolls up and down by itself */
    '#strip.side{left:auto;right:6px;width:92px;flex-direction:column}',
    '#strip.side .spart{flex:1 1 0;min-height:0;padding:4px 5px 5px}',
    '#strip.side .sph{height:auto;flex-direction:column;align-items:stretch;gap:3px;padding:2px 1px 1px}',
    '#strip.side .sph b{text-align:center} #strip.side .sph .seg{display:flex} #strip.side .sph .seg button{flex:1;padding:0;justify-content:center}',
    '#strip.side .spc{flex-direction:column;overflow-x:hidden;overflow-y:auto;flex:1;min-height:0}',
    '#strip.side .sdc{width:100%;touch-action:pan-y} #strip.side .sdc.add{height:26px}'
  ].join('\n');
  document.head.appendChild(h('style', { text: CSS }));

  /* the director's chair, drawn: a backrest band on two posts, the seat, the crossed legs */
  function chair() {
    var s = document.createElementNS(NS, 'svg'); s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('aria-hidden', 'true');
    var p = document.createElementNS(NS, 'path'); p.setAttribute('d', 'M6.5 3.5v9M17.5 3.5v9M6.5 4.5h11v3.6h-11M4.5 12.5h15M7 12.5l10 8M17 12.5l-10 8M5.5 20.5h3M15.5 20.5h3'); s.appendChild(p);
    return s;
  }
  var st = $('stage'), menu = h('div', { id: 'chairmenu', role: 'group', 'aria-label': 'What the strip shows' }), bf = h('button', { class: 'rb', id: 'bframe' }), bc = h('button', { class: 'rb', id: 'bchair', 'aria-expanded': 'false', title: 'The director\'s chair: Scene, Character and Shots on the strip', 'aria-label': 'The director\'s chair' }, chair());
  var box = h('div', { id: 'strip', role: 'region', 'aria-label': 'Scene, character and shots' }), btns = h('div', { id: 'roombtns' }, [menu, bf, bc]);
  st.appendChild(box); st.appendChild(btns);
  var open = false;

  /* ── long press, or a second tap on the one already chosen ── */
  strip.HOLD = 450;
  function press(b, isOn, choose, setup) {
    var tm = null, held = false, x0 = 0, y0 = 0;
    b.addEventListener('pointerdown', function (e) { held = false; x0 = e.clientX; y0 = e.clientY; clearTimeout(tm); tm = setTimeout(function () { tm = null; held = true; setup(); }, strip.HOLD); });
    b.addEventListener('pointermove', function (e) { if (tm && Math.hypot(e.clientX - x0, e.clientY - y0) > 8) { clearTimeout(tm); tm = null; } });      // (a sideways scroll is not a hold)
    ['pointerup', 'pointerleave', 'pointercancel'].forEach(function (ev) { b.addEventListener(ev, function () { clearTimeout(tm); tm = null; }); });
    b.addEventListener('click', function () { if (held) { held = false; return; } if (isOn()) setup(); else choose(); });
    b.addEventListener('contextmenu', function (e) { e.preventDefault(); clearTimeout(tm); tm = null; if (!held) { held = true; setup(); } });      // (a mouse's right button, a pen's side button: the same as holding)
  }
  strip.press = press;

  /* ── the frame button: tap on or off, hold for the shapes ── */
  press(bf, function () { return false; }, function () { frame.set(app, !frame.on()); if (app.scene) app.scene.cutNow = true; }, function () { frame.sheet(app); });
  bc.onclick = function () { open = !open; strip.sync(app, true); };
  st.addEventListener('pointerdown', function (e) { if (open && !(e.target.closest && e.target.closest('#roombtns'))) { open = false; strip.sync(app, true); } }, true);

  var NAMES = { sc: 'Scene', ch: 'Character', sh: 'Shots' }, ICONS = { sc: 'image', ch: 'person', sh: 'cam' };
  var TIPS = { sc: 'Scenes: the empty scene and yours. Tap one to change scene.', ch: 'Who is in the scene. Tap one to take it in hand.', sh: 'Shots: where the camera is. They need the frame.' };
  /** A part of the strip on or off (Shots turns the frame on). */
  strip.toggle = function (app, k, on) { ROOM[k] = on == null ? !ROOM[k] : !!on; if (k === 'sh' && ROOM.sh && !frame.on()) frame.set(app, true); frame.keep(); strip.sync(app, true); frame.refit(app); };
  strip.parts = function () { return ['sc', 'ch', 'sh'].filter(function (k) { return ROOM[k] && (k !== 'sh' || frame.on()); }); };
  strip.isOpen = function () { return open; };
  strip.open = function (v) { open = !!v; strip.sync(app, true); };

  /* ── what each part holds ── */
  function shots() { return app.scene ? scene.shots(app) : frame.plainShots(app); }
  function curShot() { return app.scene ? app.scene.shot : frame.plainShot(); }
  /** Go to a shot, cut or glide as the switch says. */
  strip.goShot = function (app, key) { var ease = ROOM.glide ? 0.9 : 0; if (app.scene) { if (!frame.on()) frame.set(app, true); scene.shot(app, key, ease); } else frame.goPlain(app, key, ease); mark(); };
  strip.keepShot = function (app) { if (!frame.on()) frame.set(app, true); var s = app.scene ? scene.keepShot(app) : frame.keepPlain(app); app.toast('Kept as “' + s.name + '”. Hold its card to name it.'); strip.sync(app, true); return s; };
  function shotSheet(s) {
    var ui = app.ui, own = s.kind === 'own', body = h('div'), inp = own ? h('input', { class: 'sctx', type: 'text', value: s.name, maxlength: 40, 'aria-label': 'The name of this shot' }) : null;
    function named() { var v = inp && inp.value.trim(); if (!v || v === s.name) return; if (app.scene) { scene.edit(app, 'Rename a shot', function (d) { d.shots.forEach(function (q) { if (q.id === s.key) q.name = v; }); }, true); } else frame.renamePlain(app, s.key, v); }
    body.appendChild(ui.note(own ? 'Your shot. To frame it again, close this, drag the empty stage, pinch or roll the wheel, then hold its card and use the view.' : 'This shot is worked out by itself' + (s.kind === 'close' ? ', and it follows ' + s.name : '') + '. To make your own: close this, drag the empty stage, pinch or roll the wheel, then keep the view.'));
    if (inp) body.appendChild(inp);
    var acts = own ? [
      { label: 'Remove', cls: 'quiet', icon: 'trash', title: 'Take this shot away', fn: function () { app.closeSheet(); if (app.scene) scene.dropShot(app, s.key); else frame.dropPlain(app, s.key); strip.sync(app, true); } },
      { label: 'Use the view now', icon: 'cam', title: 'This shot becomes the view the camera has now', fn: function () { named(); app.closeSheet(); if (app.scene) { var R = app.scene, c = R.cam; scene.edit(app, 'Frame a shot again', function (d) { d.shots.forEach(function (q) { if (q.id === s.key) { q.x = Math.round(c.x); q.y = Math.round(c.y); q.z = Math.round(c.z * 100) / 100; } }); }, true); R.shot = s.key; R.free = null; } else frame.reframePlain(app, s.key); strip.sync(app, true); } },
      { label: 'Done', cls: 'go', fn: function () { named(); app.closeSheet(); strip.sync(app, true); } }
    ] : [
      { label: 'Keep the view as a shot', icon: 'star', title: 'The view the camera has now, kept as your own shot', fn: function () { app.closeSheet(); strip.keepShot(app); } },
      { label: 'Done', cls: 'quiet', fn: app.closeSheet }
    ];
    app.sheet(s.kind === 'close' ? 'Close on ' + s.name : s.name, body, acts);
  }
  function card(cls, kids, title, data) { var b = h('button', { class: 'sdc' + (cls ? ' ' + cls : ''), title: title, 'aria-label': title }, kids); if (data) b.setAttribute(data[0], data[1]); return b; }
  function addCard(title, fn, icon) { return h('button', { class: 'sdc add', title: title, 'aria-label': title, onclick: fn }, AL.icon(icon || 'plus')); }
  function cards(k) {
    var c = h('div', { class: 'spc', 'data-k': k }), R = app.scene, b;
    if (k === 'sc') {
      var cur = R ? R.data.uid : scene.EMPTY;
      b = card('blank' + (!R ? ' on' : ''), [h('span', { text: 'Empty' })], !R ? 'The empty scene: the plain stage. It is where you are. Tap again to arrange the character on it.' : 'The empty scene: the plain stage, one character, nothing else', ['data-sc', scene.EMPTY]);
      press(b, function () { return !app.scene; }, function () { scene.close(app); }, function () { if (app.scene) scene.close(app).then(function () { AL.stages.go(app, 'fit'); }); else AL.stages.go(app, 'fit'); });
      c.appendChild(b);
      var L = (scene._list || []).slice(); if (R && !L.some(function (e) { return e.uid === R.data.uid; })) L.unshift({ uid: R.data.uid, name: R.data.name, pic: null });      // (a scene just made is on the shelf a moment later)
      L.forEach(function (e) {
        b = card(e.uid === cur ? 'on' : '', [e.pic ? h('img', { src: e.pic, alt: '' }) : null, h('span', { text: e.name || 'Scene' })], e.uid === cur ? '“' + e.name + '” is the scene in use. Tap again (or hold) to set it up.' : 'Change to the scene “' + e.name + '”. Hold to set it up.', ['data-sc', e.uid]);
        press(b, function () { return !!(app.scene && app.scene.data.uid === e.uid); }, function () { scene.openId(app, e.uid, { stage: 'perform' }); }, function () { if (app.scene && app.scene.data.uid === e.uid) { app.scene.hand = 'scene'; scene.go(app, 'fit'); } else scene.openId(app, e.uid, { stage: 'fit', hand: 'scene' }); });
        c.appendChild(b);
      });
      c.appendChild(addCard('A new scene: pick a set to start on', function () { scene.newSheet(app); }));
    } else if (k === 'ch') {
      if (R) scene.cast(R).forEach(function (it) {
        var a = R.actors[it.id];
        b = card('fit' + (R.focus === it.id ? ' on' : ''), [a.pic ? h('img', { src: a.pic, alt: '' }) : null, h('span', { text: it.name })], R.focus === it.id ? it.name + ' is in hand: your fingers and voice move them, and the card below is theirs. Tap again (or hold) to set them up.' : 'Take ' + it.name + ' in hand. Hold to set them up.', ['data-ch', it.id]);
        press(b, function () { return app.scene === R && R.focus === it.id; }, function () { scene.hand(app, it.id); mark(); }, function () { scene.diveIn(app, it.id, 'fit'); });
        c.appendChild(b);
      });
      else if (app.ch) {
        var pic = null; try { pic = AL.shelf.thumb({ ch: app.ch }, 96); } catch (e) { }
        b = card('fit on', [pic ? h('img', { src: pic, alt: '' }) : null, h('span', { text: app.ch.name })], app.ch.name + ' is in hand. Tap again (or hold) to set them up.', ['data-ch', 'solo']);
        press(b, function () { return true; }, function () { }, function () { AL.stages.go(app, 'fit'); });
        c.appendChild(b);
      }
      c.appendChild(addCard(R ? 'Add a character to the scene' : 'Add a character: a new scene is made for the two of them', function () { scene.castSheet(app); }));
    } else {
      var on = curShot();
      shots().forEach(function (s) {
        var kids;
        if (s.kind === 'close') kids = [s.pic ? h('img', { src: s.pic, alt: '' }) : h('i', { class: 'fr' }, [h('b')]), h('span', { text: s.name })];
        else kids = [h('i', { class: 'fr' + (s.kind === 'own' ? ' own' : '') }, s.kind === 'two' ? [h('b'), h('b')] : s.kind === 'own' ? [] : [h('b', { style: 'height:6px;width:4px' })]), h('span', { text: s.name })];
        b = card((s.kind === 'close' ? 'close' : '') + (on === s.key ? ' on' : ''), kids, (s.kind === 'close' ? 'Close on ' + s.name : s.name) + '. Tap to go there; hold (or tap it again) to set it up.', ['data-shot', s.key]);
        press(b, function () { return curShot() === s.key; }, function () { strip.goShot(app, s.key); }, function () { if (curShot() !== s.key) strip.goShot(app, s.key); shotSheet(s); });
        c.appendChild(b);
      });
      c.appendChild(addCard('Keep the view the camera has now as a shot', function () { strip.keepShot(app); }, 'star'));
    }
    return c;
  }
  function part(k) {
    var head = h('div', { class: 'sph' }, [h('b', { text: NAMES[k], title: TIPS[k] })]);
    if (k === 'sh') head.appendChild(app.ui.seg([['cut', 'Cut', 'A shot changes at once'], ['glide', 'Glide', 'The camera eases to the shot']], ROOM.glide ? 'glide' : 'cut', function (v) { ROOM.glide = v === 'glide'; frame.keep(); strip.sync(app, true); }, 'How the camera goes to a shot'));
    return h('div', { class: 'spart p-' + k, 'data-k': k }, [head, cards(k)]);      // (p-: .sc is already the shelf's card)
  }
  /* the chosen card, without building the strip again (a shot changes many times a second while a take plays) */
  function mark() {
    var cur = curShot(), R = app.scene;
    Array.prototype.forEach.call(box.querySelectorAll('[data-shot]'), function (b) { b.classList.toggle('on', b.getAttribute('data-shot') === cur); });
    Array.prototype.forEach.call(box.querySelectorAll('[data-ch]'), function (b) { var id = b.getAttribute('data-ch'); b.classList.toggle('on', id === 'solo' || !!(R && R.focus === id)); });
  }

  /* ── when it shows, and building it ── */
  function visible() { var R = app.scene; return !!(app.ch && app.mode === 'perform' && !(R && R.dive) && AL.find(app.ch.root, function (n) { return n.type === 'art'; })); }
  var S = { sig: null, vis: null, at: 0 };
  function sig(vis) {
    var R = app.scene, L = (scene._list || []).map(function (e) { return e.uid + ':' + e.at + ':' + e.name; }).join(','), cast = R ? scene.cast(R).map(function (it) { return it.id + it.name; }).join(',') : (app.ch ? app.ch.name : '');
    var sh = strip.parts().indexOf('sh') >= 0 ? shots().map(function (s) { return s.key + s.name; }).join(',') : '';
    return [vis, open, ROOM.frame, ROOM.sc, ROOM.ch, ROOM.sh, ROOM.glide, frame.shape(app), vis ? frame.side(app) : '', R ? R.data.uid : '-', R ? R.stage : '', L, cast, sh, app.ch && app.ch.uid].join('|');
  }
  /** Keep the buttons and the strip true to the room. force = build it again even if nothing it shows has changed. */
  strip.sync = function (app, force) {
    var vis = visible(), s = sig(vis);
    if (vis && ROOM.sc && (!S.at || Date.now() - S.at > 1500)) { S.at = Date.now(); scene.list(app).then(function (l) { var a = JSON.stringify((scene._list || []).map(function (e) { return e.uid + e.at + e.name; })), b = JSON.stringify(l.map(function (e) { return e.uid + e.at + e.name; })); scene._list = l; if (a !== b) strip.sync(app, true); }); }
    if (!force && s === S.sig) return mark();
    var keep = {}; Array.prototype.forEach.call(box.querySelectorAll('.spc'), function (c) { keep[c.getAttribute('data-k')] = c.scrollLeft; });
    S.sig = s;
    btns.style.display = vis ? 'flex' : 'none';
    var parts = vis ? strip.parts() : [], side = parts.length && frame.side(app) === 'side', room = vis + ':' + parts.length + ':' + side;      // (what pushes the picture aside)
    box.innerHTML = ''; box.classList.toggle('side', !!side); parts.forEach(function (k) { box.appendChild(part(k)); }); box.style.display = parts.length ? 'flex' : 'none';
    if (side) { var co = $('coach'), tl = $('tools'); box.style.top = Math.max(8, co && co.offsetHeight ? co.offsetTop + co.offsetHeight + 6 : 8, tl && tl.offsetHeight ? tl.offsetTop + tl.offsetHeight + 8 : 8) + 'px'; } else box.style.top = '';      // (below the hint line, and below the room's own round tools when they are there)
    btns.style.bottom = (parts.length && !side ? box.offsetHeight + 12 : 8) + 'px'; btns.style.right = (side ? box.offsetWidth + 12 : 8) + 'px';      // the two buttons ride beside the strip
    Array.prototype.forEach.call(box.querySelectorAll('.spc'), function (c) { var k = c.getAttribute('data-k'); if (keep[k] != null) c.scrollLeft = keep[k]; });
    bf.className = 'rb' + (frame.on() ? ' on' : ''); bf.innerHTML = ''; bf.appendChild(frame.glyph(frame.shape(app)));
    bf.title = (frame.on() ? 'The frame is on (' + frame.shape(app) + '). Tap to hide it.' : 'Show the frame (' + frame.shape(app) + '): where the picture\'s edges are.') + ' Hold for its shapes and margins.';
    bf.setAttribute('aria-pressed', String(frame.on())); bf.setAttribute('aria-label', 'The frame, ' + frame.shape(app));
    bc.className = 'rb' + (open ? ' open' : ''); bc.setAttribute('aria-expanded', String(open));
    menu.innerHTML = ''; menu.className = open && vis ? 'open' : '';
    ['sc', 'ch', 'sh'].forEach(function (k) { menu.appendChild(app.ui.chip(NAMES[k], { cls: 'veil', on: !!ROOM[k] && (k !== 'sh' || frame.on()), icon: ICONS[k], title: (ROOM[k] ? 'Hide ' : 'Show ') + NAMES[k].toLowerCase() + ' on the strip. ' + TIPS[k], fn: function () { strip.toggle(app, k); } })); });
    if (S.room !== room) { S.room = room; frame.refit(app); }      // the strip came or went: the plain stage is fitted again
    mark();
  };
  app.hooks.push(function () { strip.sync(app); });
})(typeof self !== 'undefined' ? self : this);
