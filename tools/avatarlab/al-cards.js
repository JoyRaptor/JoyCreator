/* AvatarLab: the Build panel. A body map to say WHICH part, and one card per part that says, in
 * plain words, what the part does, with the few controls that change it. No control is shown that
 * nothing reads.
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h, NS = 'http://www.w3.org/2000/svg';
  function $(id) { return document.getElementById(id); }
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function round1(v) { return Math.round(v * 10) / 10; }
  var cards = AL.cards = {};

  /* ── the body map: the character facing you, so ITS left arm is on your right ── */
  var SHAPES = [
    ['tail', 'path', { d: 'M65 80 Q88 86 89 64' }, true, [95, 55]], ['legR', 'path', { d: 'M43 84 L41 112' }, true, [38, 116]], ['legL', 'path', { d: 'M57 84 L59 112' }, true, [62, 116]],
    ['armR', 'path', { d: 'M34 50 L17 76' }, true, [12, 80]], ['armL', 'path', { d: 'M66 50 L83 76' }, true, [88, 80]],
    ['body', 'rect', { x: 33, y: 43, width: 34, height: 42, rx: 13 }, false, [50, 64]], ['head', 'circle', { cx: 50, cy: 23, r: 17 }, false, [50, 23]],
    ['extra', 'rect', { x: 82, y: 6, width: 13, height: 13, rx: 4 }, false, [88.5, 12.5]]
  ];
  var ZLABEL = { head: 'Head', body: 'Body', armL: 'Left arm', armR: 'Right arm', legL: 'Left leg', legR: 'Right leg', tail: 'Tail', extra: 'Extras' };
  cards.bodyMap = function (app, big) {
    var svg = document.createElementNS(NS, 'svg'); svg.setAttribute('viewBox', big ? '-14 -4 128 132' : '0 0 100 122'); svg.setAttribute('class', 'bmap' + (big ? ' big' : ''));
    svg.setAttribute('role', 'group'); svg.setAttribute('aria-label', 'Body map');
    SHAPES.forEach(function (s) {
      var zone = s[0], has = zone === 'extra' ? cards.extras(app).length > 0 : !!app.zoneNode(zone);
      if (big && zone === 'extra') return;
      var g = document.createElementNS(NS, 'g'); g.setAttribute('class', 'z' + (s[3] ? ' line' : '') + (has ? ' has' : '') + (!big && app.sel.zone === zone && app.tab === 'parts' ? ' sel' : ''));
      g.setAttribute('tabindex', '0'); g.setAttribute('role', 'button');
      var t = document.createElementNS(NS, 'title'); t.textContent = ZLABEL[zone] + (has ? '' : ': nothing here yet. Tap to add art.'); g.appendChild(t);
      var e = document.createElementNS(NS, s[1]); for (var k in s[2]) e.setAttribute(k, s[2][k]); e.setAttribute('class', 's'); g.appendChild(e);
      /* a fat invisible twin so a thin arm is easy to tap */
      var hit = e.cloneNode(); hit.setAttribute('class', ''); hit.setAttribute('style', 'fill:none;stroke:transparent;stroke-width:16;pointer-events:stroke'); if (!s[3]) hit.setAttribute('style', 'fill:transparent;stroke:transparent;stroke-width:6'); g.appendChild(hit);
      if (!has && (big || zone !== 'extra')) {
        var c = document.createElementNS(NS, 'circle'); c.setAttribute('cx', s[4][0]); c.setAttribute('cy', s[4][1]); c.setAttribute('r', big ? 5.6 : 6.5); c.setAttribute('class', 'plus'); g.appendChild(c);
        var p = document.createElementNS(NS, 'path'); var r = big ? 2.6 : 3; p.setAttribute('d', 'M' + (s[4][0] - r) + ' ' + s[4][1] + 'h' + (2 * r) + 'M' + s[4][0] + ' ' + (s[4][1] - r) + 'v' + (2 * r)); g.appendChild(p);
      }
      if (big) { var tx = document.createElementNS(NS, 'text'); tx.setAttribute('class', 'lbl'); tx.setAttribute('text-anchor', zone === 'legR' ? 'end' : zone === 'legL' ? 'start' : 'middle'); tx.setAttribute('x', s[4][0] + (zone === 'legR' ? 5 : zone === 'legL' ? -5 : 0)); tx.setAttribute('y', s[4][1] + (zone === 'head' || zone === 'body' ? 12 : 11)); tx.textContent = ZLABEL[zone]; g.appendChild(tx); }
      function go() { if (has) { app.tab = 'parts'; app.select(zone); } else if (zone !== 'extra') AL.intake.addTo(app, zone); else { app.tab = 'parts'; app.select('extra'); } }
      g.addEventListener('click', go); g.addEventListener('keydown', function (ev) { if (ev.key === 'Enter' || ev.key === ' ') go(); });
      svg.appendChild(g);
    });
    return svg;
  };
  /** Top-level things that belong to no body part. */
  cards.extras = function (app) {
    var out = [], ch = app.ch;
    AL.walk(ch.root, function (n, par, depth) { if (n === ch.root) return; if (n.role && AL.ROLES[n.role] && AL.ROLES[n.role].zone !== 'extra') return false; if (n.type === 'art' || (n.type !== 'art' && depth >= 2) ) { if (AL.zoneOf(ch, n.id) === 'extra') out.push(n); if (n.type !== 'art') return false; } });
    return out;
  };

  /* ── thumbnails ── */
  var thumbCache = {};
  function thumb(node) {
    var arts = node.type === 'art' ? [node] : AL.findAll(node.type === 'mirror' ? AL.node(AL.app.ch, node.of) || node : node, function (k) { return k.type === 'art' && !k.hidden && !k.trig && k.blink !== 'closed'; });
    var key = arts.map(function (a) { var im = AL.img.get(a.img); return a.img + ':' + (im ? im.ver : 0) + ':' + a.x + ',' + a.y; }).join('|') + (node.type === 'mirror' ? 'm' : '');
    if (thumbCache[key]) { var cc = document.createElement('canvas'); cc.width = cc.height = 104; cc.getContext('2d').drawImage(thumbCache[key], 0, 0); return cc; }
    var c = document.createElement('canvas'); c.width = c.height = 104; var g = c.getContext('2d'), b = null;
    arts.forEach(function (a) { var im = AL.img.get(a.img); if (!im) return; b = b ? { x0: Math.min(b.x0, a.x), y0: Math.min(b.y0, a.y), x1: Math.max(b.x1, a.x + im.w), y1: Math.max(b.y1, a.y + im.h) } : { x0: a.x, y0: a.y, x1: a.x + im.w, y1: a.y + im.h }; });
    if (b) { var s = Math.min(96 / (b.x1 - b.x0), 96 / (b.y1 - b.y0)); g.translate(52, 52); if (node.type === 'mirror') g.scale(-1, 1); g.scale(s, s); g.translate(-(b.x0 + b.x1) / 2, -(b.y0 + b.y1) / 2); for (var i = arts.length - 1; i >= 0; i--) { var im = AL.img.get(arts[i].img); if (im) g.drawImage(im.canvas, arts[i].x, arts[i].y); } }
    thumbCache[key] = c; var keys = Object.keys(thumbCache); if (keys.length > 400) thumbCache = {};
    var c2 = document.createElement('canvas'); c2.width = c2.height = 104; c2.getContext('2d').drawImage(c, 0, 0); return c2;
  }
  function th(node, label, on, star, fn, title) { return h('button', { class: 'th' + (on ? ' on' : ''), title: title || label, onclick: fn }, [thumb(node), star ? h('i', { class: 'star', text: '★' }) : null, h('span', { text: label })]); }

  /* ── the panel ── */
  function head(app, title, badge, desc, badgeCls) {
    var ph = $('panelhead'); ph.innerHTML = '';
    ph.appendChild(cards.bodyMap(app, false));
    var tabs = h('div', { class: 'tabs' }, [
      app.ui.chip('Parts', { on: app.tab === 'parts', icon: 'person', title: 'Work part by part, from the body map', fn: function () { app.tab = 'parts'; app.refreshPanel(); } }),
      app.ui.chip('Layers', { on: app.tab === 'layers', icon: 'layers', title: 'Every layer of the file, as a list', fn: function () { app.tab = 'layers'; app.refreshPanel(); } })
    ]);
    ph.appendChild(h('div', { class: 'ptitle' }, [h('h2', null, [title, badge ? h('span', { class: 'badge ' + (badgeCls || ''), text: badge }) : null]), desc ? h('p', { html: desc }) : null, tabs]));
  }
  function tryBar(app, canTry) {
    var sb = $('stagebar'); sb.innerHTML = '';
    if (app.live) sb.appendChild(app.ui.chip('Done trying', { cls: 'veil on', icon: 'stop', title: 'Back to the still pose, where pins can be moved', fn: function () { app.setLive(false); } }));
    else if (canTry) sb.appendChild(app.ui.chip('Try it', { cls: 'veil', icon: 'play', title: 'Bring the character to life here: drag the head, the hands, the body', fn: function () { app.setLive(true); } }));
  }
  function all(app, role, fn) { AL.byRole(app.ch.root, role).forEach(fn); }

  cards.render = function (app) {
    var pb = $('panelbody'); pb.innerHTML = ''; app.onionOk = false;
    var hasArt = !!AL.find(app.ch.root, function (n) { return n.type === 'art'; });
    $('empty').style.display = hasArt ? 'none' : 'flex';
    if (!hasArt) { $('stagebar').innerHTML = ''; return AL.intake.emptyPanel(app); }
    if (app.live) { tryBar(app, true); head(app, 'Trying it', 'alive', 'Drag the <b>head</b> to turn it, a <b>hand</b> to reach, the <b>body</b> to lean. Double-tap a part to let it go back.'); app.coach('<b>Trying it.</b> Everything moves. Tap <b>Done trying</b> to go back to the pins.'); pb.appendChild(AL.perform.quick(app)); return; }
    tryBar(app, true);
    if (app.tab === 'layers') return layers(app, pb);
    var z = app.sel.zone;
    if (app.sel.node && AL.node(app.ch, app.sel.node)) return nodeCard(app, pb, AL.node(app.ch, app.sel.node));
    if (z === 'head') return app.sel.feature ? feature(app, pb, app.sel.feature) : headCard(app, pb);
    if (z === 'armL' || z === 'armR') return armCard(app, pb, z);
    if (z === 'body') return bodyCard(app, pb);
    if (z === 'tail') return tailCard(app, pb);
    if (z === 'legL' || z === 'legR') return legCard(app, pb, z);
    return extraCard(app, pb);
  };

  /* ── a pad you turn the head with; dots are the drawings ── */
  cards.turnPad = function (app, opts) {
    opts = opts || {};
    var c = h('canvas'), pad = h('div', { class: 'pad', title: 'Drag to turn the head. Each dot is one drawing.' }, c), dragDot = null, pushed = false;
    function headSet() { var hd = app.zoneNode('head'); return hd && hd.type === 'set' ? hd : null; }
    function toPx(v, size) { return (v * 0.4 + 0.5) * size; } function fromPx(p, size) { return clamp((p / size - 0.5) / 0.4, -1, 1); }
    function draw() {
      var size = pad.clientWidth || 132, dpr = root.devicePixelRatio || 1; if (c.width !== Math.round(size * dpr)) { c.width = c.height = Math.round(size * dpr); }
      var g = c.getContext('2d'), hs = headSet(), live = app.mode === 'perform' || app.live, S = live ? app.inp.S : app.bt, tx = live ? S.turnX : S.x, ty = live ? S.turnY : S.y;
      g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, size, size);
      g.strokeStyle = '#2c2c35'; g.lineWidth = 1; g.beginPath(); g.moveTo(size / 2, 8); g.lineTo(size / 2, size - 8); g.moveTo(8, size / 2); g.lineTo(size - 8, size / 2); g.stroke();
      var pick = hs && app.out ? app.out.picks[hs.id] : null;
      if (hs) hs.kids.forEach(function (k) {
        if (!k.at) return; var x = toPx(k.at[0], size), y = toPx(k.at[1], size), on = k.id === pick;
        g.beginPath(); g.arc(x, y, on ? 8 : 6, 0, 7);
        if (k.type === 'mirror') { g.strokeStyle = on ? '#22d3ee' : '#71717a'; g.lineWidth = 2; g.stroke(); } else { g.fillStyle = on ? '#22d3ee' : '#a1a1aa'; g.fill(); }
      });
      g.beginPath(); g.arc(toPx(tx, size), toPx(ty, size), 12, 0, 7); g.strokeStyle = live ? '#f43f8e' : '#e4e4e7'; g.lineWidth = 2; g.stroke();
    }
    app.padDraw = draw;
    function at(e) { var r = pad.getBoundingClientRect(), k = pad.clientWidth / r.width; return [(e.clientX - r.left) * k, (e.clientY - r.top) * k]; }
    function setTurn(p, snap) {
      var size = pad.clientWidth, x = fromPx(p[0], size), y = fromPx(p[1], size), hs = headSet(), live = app.mode === 'perform' || app.live;
      if (live) { app.inp.pose.turnX = x; app.inp.pose.turnY = y; return; }
      if (snap && hs) { var best = null, bd = 9; hs.kids.forEach(function (k) { if (!k.at) return; var d = Math.hypot(k.at[0] - x, k.at[1] - y); if (d < bd) { bd = d; best = k; } }); if (best) { x = best.at[0]; y = best.at[1]; } }
      app.bt.x = x; app.bt.y = y;
    }
    pad.addEventListener('pointerdown', function (e) {
      pad.setPointerCapture(e.pointerId); var p = at(e), size = pad.clientWidth, hs = headSet(); dragDot = null; pushed = false;
      if (opts.moveDots && opts.moveDots() && hs) hs.kids.forEach(function (k) { if (k.at && Math.hypot(toPx(k.at[0], size) - p[0], toPx(k.at[1], size) - p[1]) < 14) dragDot = k; });
      if (!dragDot) { if (app.mode === 'perform' || app.live) app.inp.held.head = true; setTurn(p, false); }
      pad._down = true;
    });
    pad.addEventListener('pointermove', function (e) {
      if (!pad._down) return; var p = at(e), size = pad.clientWidth;
      if (dragDot) { if (!pushed) { app.hist.push(app.ch); app.syncUndo(); pushed = true; } dragDot.at = [Math.round(fromPx(p[0], size) * 20) / 20, Math.round(fromPx(p[1], size) * 20) / 20]; app.bt.x = dragDot.at[0]; app.bt.y = dragDot.at[1]; app.touch(); }
      else setTurn(p, false);
    });
    function up(e) { if (!pad._down) return; pad._down = false; app.inp.held.head = false; if (!dragDot) { setTurn(at(e), true); } if (!(app.mode === 'perform' || app.live)) { setTimeout(function () { app.resolveSel(); app.userView = false; app.focusSel(); app.refreshPanel(true); }, 30); } }
    pad.addEventListener('pointerup', up); pad.addEventListener('pointercancel', up);
    return pad;
  };

  function mirrorNote(app, pb, cv) {
    if (!cv || !cv.mirror) return false;
    pb.appendChild(app.ui.note('This angle is a <b>mirror</b> of “' + cv.node.name + '”. Change that drawing and this one follows.'));
    pb.appendChild(app.ui.row([app.ui.chip('Go to ' + cv.node.name, { icon: 'flip', fn: function () { app.bt.x = cv.node.at[0]; app.bt.y = cv.node.at[1]; setTimeout(function () { app.resolveSel(); app.focusSel(); app.refreshPanel(); }, 40); } })]));
    return true;
  }

  function headCard(app, pb) {
    var hd = app.zoneNode('head'), isSet = hd.type === 'set', cv = app.curView(), ui = app.ui;
    head(app, 'Head', isSet ? hd.kids.length + ' angles' : 'one drawing', isSet ? 'Shows the drawing nearest to where the head is turned, and slides the face parts a little in between.' : 'One drawing for now. Add more angles and it will turn.');
    app.coach(isSet ? '<b>Head.</b> Drag in the pad to turn it. Tap a dot to work on that drawing.' : '<b>Head.</b> One drawing. Drag the <b>Neck</b> pin to where it tilts from.');
    if (isSet) {
      var side = h('div', { class: 'side' }, [
        h('div', { class: 'rowf' }, [h('span', { class: 'badge', text: cv && cv.kid ? cv.kid.name : '' })]),
        ui.toggle('Move the dots', !!app.moveDots, function (v) { app.moveDots = v; app.refreshPanel(true); }, 'Drag a dot to change when its drawing shows', 'When ON, dragging a dot in the pad moves it'),
        mirrorChip(app, hd)
      ]);
      pb.appendChild(h('div', { class: 'padrow' }, [cards.turnPad(app, { moveDots: function () { return app.moveDots; } }), side]));
    }
    var feats = Object.keys(app.FEAT).filter(function (f) { return app.featureNodes(f).length; });
    if (feats.length) { pb.appendChild(ui.lab(isSet ? 'On this angle' : 'Face parts')); pb.appendChild(ui.row(feats.map(function (f) { return ui.chip(app.FEAT[f].label, { title: 'Open ' + app.FEAT[f].label, fn: function () { app.select('head', f); } }); }))); }
    pb.appendChild(ui.lab('How it moves'));
    if (isSet) {
      pb.appendChild(ui.slider('Depth', { min: 0, max: 30, value: hd.pick.depth == null ? 12 : hd.pick.depth, set: function (v) { hd.pick.depth = v; }, fmt: function (v) { return v + ' px'; }, title: 'How far the nose, eyes and frill slide between two angles. More depth feels rounder.' }));
      pb.appendChild(ui.slider('Cross-fade', { min: 0, max: 300, step: 10, value: hd.pick.fade == null ? 110 : hd.pick.fade, set: function (v) { hd.pick.fade = v; }, fmt: function (v) { return v ? v + ' ms' : 'cut'; }, title: 'How softly one angle gives way to the next' }));
    }
    var roll = (hd.links || []).filter(function (l) { return l.sig === 'roll'; })[0];
    if (roll) pb.appendChild(ui.slider('Tilt', { min: 0, max: 40, value: roll.amt, set: function (v) { roll.amt = v; }, fmt: function (v) { return v + '°'; }, title: 'How far the head can tip from the neck pin' }));
    app.pinProvider = function () { return hd.pivot ? [{ key: 'neck', label: 'Neck', x: hd.pivot.x, y: hd.pivot.y, node: null, set: function (x, y) { hd.pivot.x = x; hd.pivot.y = y; } }] : []; };
  }
  function mirrorChip(app, hd) {
    var auto = hd.kids.filter(function (k) { return k.type === 'mirror'; }), L = hd.kids.some(function (k) { return k.type !== 'mirror' && k.at && k.at[0] < -0.1; }), R = hd.kids.some(function (k) { return k.type !== 'mirror' && k.at && k.at[0] > 0.1; });
    if (auto.length) return app.ui.chip('Remove the ' + auto.length + ' mirrored', { cls: 'quiet', icon: 'flip', title: 'Take away the angles that were made by mirroring', fn: function () { app.edit(function () { hd.kids = hd.kids.filter(function (k) { return k.type !== 'mirror'; }); app.bt.x = 0; app.bt.y = 0; }); } });
    if (L !== R) return app.ui.chip('Mirror the other side', { icon: 'flip', title: 'Make the missing side by flipping the drawings you have', fn: function () { app.edit(function () { hd.kids.slice().forEach(function (k) { if (k.at && Math.abs(k.at[0]) > 0.1) hd.kids.push({ id: AL.id('n'), name: k.name + ' (mirrored)', type: 'mirror', of: k.id, at: [-k.at[0], k.at[1]], auto: true }); }); }); } });
    return null;
  }

  function backToHead(app, pb, f) { pb.appendChild(app.ui.row([app.ui.chip('Head', { icon: 'back', cls: 'quiet', title: 'Back to the whole head', fn: function () { app.select('head'); } }), h('span', { class: 'badge', text: ((app.curView() || {}).kid || {}).name || '' })])); void f; }

  function feature(app, pb, f) {
    var ui = app.ui, cv = app.curView(), nodes = app.featureNodes(f), F = app.FEAT[f];
    backToHead(app, pb, f);
    var mir = mirrorNote(app, pb, cv);
    if (f === 'mouth') return mouthCard(app, pb, nodes[0], cv, mir);
    if (f === 'eyes') {
      head(app, 'Eyes', null, 'The pupils drift toward where the head is going, and the eyes blink by themselves.');
      app.coach('<b>Eyes.</b> Set how far the pupils travel, and how often they blink.');
      var pup = AL.byRole(app.ch.root, 'pupil').filter(function (p) { return p.look; }), rx = pup.length ? pup[0].look.rx : 0, st = app.ch.settings;
      if (pup.length) pb.appendChild(ui.slider('Pupils move', { min: 0, max: 14, step: 0.5, value: round1(rx), set: function (v) { pup.forEach(function (p) { p.look.rx = v; p.look.ry = v * 0.65; }); }, fmt: function (v) { return v + ' px'; }, title: 'How far a pupil slides when the eyes look to the side' }));
      pb.appendChild(ui.slider('Try: look', { min: -1, max: 1, step: 0.05, value: app.test.lookX, undo: false, set: function (v) { app.test.lookX = v; }, fmt: function (v) { return v < -0.05 ? 'left' : v > 0.05 ? 'right' : 'ahead'; } }));
      pb.appendChild(ui.lab('Blinking'));
      pb.appendChild(ui.toggle('Blink by itself', st.blinkAuto !== false, function (v) { app.edit(function () { st.blinkAuto = v; }); }, 'Uneven, like a living thing: sometimes twice'));
      pb.appendChild(ui.slider('About every', { min: 1.5, max: 9, step: 0.1, value: st.blinkEvery || 3.6, set: function (v) { st.blinkEvery = v; }, fmt: function (v) { return v + ' s'; } }));
      pb.appendChild(ui.toggle('Eyes lead the head', st.eyesLead !== false, function (v) { app.edit(function () { st.eyesLead = v; }); }, 'The eyes get there first, then the head follows'));
      pb.appendChild(ui.row([ui.chip('Blink now', { icon: 'eye', fn: function () { app.test.blink = 1; setTimeout(function () { app.test.blink = 0; }, 260); } })]));
      var hidden = nodes.filter(function (n) { return n.hidden; });
      hidden.forEach(function (n) { pb.appendChild(ui.toggle('Show the ' + (AL.ROLES[n.role] || {}).label.toLowerCase() + ' at this angle', false, function () { app.edit(function () { delete n.hidden; }); }, 'It was hidden in the file, as if turned away')); });
      return;
    }
    if (f === 'brows') {
      head(app, 'Brows', null, 'They lift with surprise and when the mouth opens wide.'); app.coach('<b>Brows.</b> Set how far they lift.');
      var br = AL.byRole(app.ch.root, 'browL').concat(AL.byRole(app.ch.root, 'browR')), amt = br.length && br[0].links ? -br[0].links[0].amt : 4;
      pb.appendChild(ui.slider('Lift', { min: 0, max: 16, step: 0.5, value: round1(amt), set: function (v) { br.forEach(function (b) { if (b.links && b.links[0]) b.links[0].amt = -v; }); }, fmt: function (v) { return v + ' px'; } }));
      pb.appendChild(ui.slider('Try', { min: -1, max: 1, step: 0.05, value: app.test.brow, undo: false, set: function (v) { app.test.brow = v; }, fmt: function (v) { return v > 0.05 ? 'up' : v < -0.05 ? 'down' : 'rest'; } }));
      return;
    }
    if (f === 'jaw') {
      head(app, 'Jaw', null, 'Drops a little as the mouth opens, so the chin joins in.'); app.coach('<b>Jaw.</b> Set how far it drops with the mouth.');
      var jw = AL.byRole(app.ch.root, 'jaw'), ja = jw.length && jw[0].links ? jw[0].links[0].amt : 3;
      pb.appendChild(ui.slider('Drops', { min: 0, max: 14, step: 0.5, value: ja, set: function (v) { jw.forEach(function (j) { if (j.links && j.links[0]) j.links[0].amt = v; }); }, fmt: function (v) { return v + ' px'; } }));
      mouthTry(app, pb);
      return;
    }
    head(app, F.label, null, 'Slides a little more or less than the face as the head turns, which is what makes a flat drawing feel round.');
    app.coach('<b>' + F.label + '.</b> Things in front slide further; things behind slide the other way.');
    var role = F.roles[0], list = AL.byRole(app.ch.root, role), dp = list.length ? (list[0].depth || 0) : 0;
    pb.appendChild(ui.slider('Depth', { min: -1.5, max: 1.5, step: 0.1, value: dp, set: function (v) { list.forEach(function (n) { n.depth = v; }); }, fmt: function (v) { return v > 0.05 ? 'front ' + v : v < -0.05 ? 'behind ' + (-v) : 'flat'; }, title: 'Front slides with the turn, behind slides against it' }));
    if (nodes[0]) follows(app, pb, nodes[0]);
  }
  function mouthTry(app, pb) {
    pb.appendChild(app.ui.slider('Try: talk', { min: 0, max: 1, step: 0.02, value: app.test.mouth == null ? 1 : app.test.mouth, undo: false, set: function (v) { app.test.mouth = v; }, fmt: function (v) { return v < 0.05 ? 'shut' : v > 0.95 ? 'open' : Math.round(v * 100) + '%'; }, title: 'Slide to see the mouth open and shut' }));
  }
  function mouthCard(app, pb, m, cv, mir) {
    var ui = app.ui, ch = app.ch;
    if (!m) { head(app, 'Mouth', 'none', 'No mouth was found at this angle.'); return; }
    if (m.hidden) pb.appendChild(ui.toggle('Show a mouth at this angle', false, function () { app.edit(function () { delete m.hidden; }); }, 'It was hidden in the file'));
    var isSet = m.type === 'set', kids = isSet ? m.kids : [m], forced = isSet ? AL.node(ch, app.force[m.id]) : null;
    if (isSet && m.pick.by === 'viseme') {
      head(app, 'Mouth', kids.length + ' shapes', 'One drawing per sound. The voice picks the shape, crisp, with no fade.');
      app.coach('<b>Mouth.</b> Tap a shape to see it. A voice in Perform chooses between them.');
      pb.appendChild(h('div', { class: 'thumbs' }, kids.map(function (k) { return th(k, k.shape || k.name, forced === k, false, function () { app.force[m.id] = k.id; app.test.mouth = null; app.refreshPanel(true); }); })));
      if (forced && !mir) pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'This is the sound' }), h('select', { 'aria-label': 'Sound', onchange: function (e) { var v = e.target.value; app.edit(function () { forced.shape = v; }); } }, AL.SHAPES.map(function (s) { return h('option', { value: s, text: s, selected: forced.shape === s }); })), h('span')]));
      pb.appendChild(ui.lab('Try a sound'));
      pb.appendChild(ui.row(kids.filter(function (k) { return k.shape; }).map(function (k) { return ui.chip(k.shape, { on: app.test.mouth != null && app.test.vis === k.shape && !app.force[m.id], fn: function () { delete app.force[m.id]; app.test.mouth = 1; app.test.vis = k.shape; app.refreshPanel(true); } }); })));
      return;
    }
    var art = isSet ? (AL.node(ch, m.pick.rest) || kids[0]) : m, mode = art.hinge ? 'hinge' : art.squash ? 'squash' : 'swap', cfg = art.hinge || art.squash;
    head(app, 'Mouth', mode === 'hinge' ? 'on a hinge' : mode === 'squash' ? 'squashes' : 'swaps', mode === 'hinge' ? 'One drawing that swings shut on a hinge as the voice goes quiet, tucking under the nose.' : mode === 'squash' ? 'One drawing that flattens shut as the voice goes quiet.' : 'Stays as drawn. Other mouths swap in from their buttons.');
    app.coach(mode === 'hinge' ? '<b>Mouth.</b> Drag the <b>Hinge</b> pin to the corner of the mouth, then slide <b>Try: talk</b>.' : '<b>Mouth.</b> Slide <b>Try: talk</b> to see it move.');
    if (mir) return;
    function setMode(v) { app.edit(function () { var keep = cfg ? cfg.rest : 'open'; delete art.hinge; delete art.squash; if (v === 'hinge') { AL.guessMouth(ch, art, cv.node); if (!art.hinge) { delete art.squash; art.hinge = { rest: keep, angle: 30 }; } art.hinge.rest = keep; } else if (v === 'squash') { var b = AL._px.boxOf(art); art.squash = { rest: keep, min: 0.14 }; art.pivot = { x: b.cx, y: b.y0 + b.h * 0.15 }; } }); }
    pb.appendChild(ui.row([ui.seg([['hinge', 'Hinge', 'Swings open and shut around a pin'], ['squash', 'Squash', 'Flattens shut toward a line'], ['swap', 'Swap only', 'Does not move by itself']], mode, setMode, 'How the mouth talks')]));
    if (cfg) {
      pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'As drawn it is' }), ui.seg([['open', 'Open'], ['closed', 'Shut']], cfg.rest, function (v) { app.edit(function () { cfg.rest = v; }); }, 'Is the drawing an open mouth or a shut one?'), h('span')]));
      if (art.hinge) pb.appendChild(ui.slider('Swing', { min: -80, max: 80, value: art.hinge.angle, set: function (v) { art.hinge.angle = v; }, fmt: function (v) { return v + '°'; }, title: 'How far it turns to shut. Minus swings the other way.' }));
      else pb.appendChild(ui.slider('Shuts to', { min: 0, max: 0.6, step: 0.02, value: art.squash.min, set: function (v) { art.squash.min = v; }, fmt: function (v) { return Math.round(v * 100) + '%'; } }));
      mouthTry(app, pb);
      pb.appendChild(ui.row([
        ui.chip('Guess again', { icon: 'wand', title: 'Work the hinge out from the art again', fn: function () { app.edit(function () { AL.guessMouth(ch, art, cv.node); }); } }),
        ui.chip('Same on every angle', { title: 'Copy this way of talking to the mouth at every head angle (each keeps its own hinge pin)', fn: function () { app.edit(function () { AL.byRole(ch.root, 'mouth').forEach(function (o) { var a = o.type === 'set' ? AL.node(ch, o.pick && o.pick.rest) : o; if (!a || a === art || a.type !== 'art') return; var b = AL._px.boxOf(a), sign = a.hinge ? (a.hinge.angle < 0 ? -1 : 1) : 1; if (art.hinge) { if (!a.hinge) { delete a.squash; AL.guessMouth(ch, a, null); delete a.squash; a.hinge = a.hinge || { rest: 'open', angle: 30 }; a.pivot = a.pivot || { x: b.cx, y: b.y0 }; } a.hinge.rest = art.hinge.rest; a.hinge.angle = Math.abs(art.hinge.angle) * (a.hinge.angle < 0 ? -1 : sign); } else { delete a.hinge; a.squash = { rest: art.squash.rest, min: art.squash.min }; a.pivot = { x: b.cx, y: b.y0 + b.h * 0.15 }; } }); }); app.toast('Copied to every angle'); } })
      ]));
    }
    var others = kids.filter(function (k) { return k !== art; });
    if (others.length) {
      pb.appendChild(ui.lab('Other mouths (each gets a button in Perform)'));
      pb.appendChild(h('div', { class: 'thumbs' }, [th(art, 'talking', !forced || forced === art, true, function () { delete app.force[m.id]; app.refreshPanel(true); })].concat(others.map(function (k) { return th(k, k.name, forced === k, false, function () { app.force[m.id] = k.id; app.test.mouth = null; app.refreshPanel(true); }, 'Show “' + k.name + '” so it can be placed'); }))));
    }
    app.pinProvider = function () {
      if (forced && forced !== art) return [];
      return art.pivot && (art.hinge || art.squash) ? [{ key: 'hinge', label: art.hinge ? 'Hinge' : 'Shuts toward', x: art.pivot.x, y: art.pivot.y, node: art.id, color: '#fbbf24', set: function (x, y) { art.pivot.x = x; art.pivot.y = y; } }] : [];
    };
  }

  function armCard(app, pb, zone) {
    var ui = app.ui, ch = app.ch, set = app.zoneNode(zone), kids = (set.kids || []).filter(function (k) { return k.type === 'art'; });
    var cur = AL.node(ch, app.force[set.id]); if (!cur || AL.parentOf(ch, cur.id) !== set) cur = AL.node(ch, set.pick && set.pick.rest) || kids[0];
    app.force[set.id] = cur.id;
    var many = kids.length > 1, idx = kids.indexOf(cur);
    head(app, ZLABEL[zone], many ? kids.length + ' drawings' : 'one drawing', many ? 'Shows the drawing whose hand is nearest to where you want the hand, then bends it the rest of the way.' : 'Bends from the shoulder and elbow so the hand lands where you drag it.');
    app.coach('<b>' + ZLABEL[zone] + '.</b> Three pins per drawing: <b>shoulder, elbow, hand</b>. They were guessed from the art; drag any that are off.');
    pb.appendChild(h('div', { class: 'thumbs' }, kids.map(function (k, i) { return th(k, (i + 1) + '. ' + k.name, k === cur, set.pick && set.pick.rest === k.id, function () { app.force[set.id] = k.id; app.refreshPanel(true); }, 'Drawing ' + (i + 1) + (set.pick.rest === k.id ? ' (the resting pose)' : '')); })));
    var shared = app.sharedShoulder !== false;
    pb.appendChild(ui.row([
      many ? ui.chip('‹', { title: 'Previous drawing', fn: function () { app.force[set.id] = kids[(idx + kids.length - 1) % kids.length].id; app.refreshPanel(true); } }) : null,
      many ? ui.chip('›', { title: 'Next drawing', fn: function () { app.force[set.id] = kids[(idx + 1) % kids.length].id; app.refreshPanel(true); } }) : null,
      ui.chip('Resting pose', { on: set.pick.rest === cur.id, icon: 'star', title: 'Use this drawing when the arm is left alone', fn: function () { app.edit(function () { set.pick.rest = cur.id; }); } }),
      ui.chip('Flip elbow', { on: !!(cur.limb && cur.limb.flip), title: 'Bend this drawing the other way', fn: function () { app.edit(function () { if (cur.limb) cur.limb.flip = !cur.limb.flip; }); } }),
      ui.chip('Guess pins again', { icon: 'wand', title: 'Work out every pin of this arm from the art again', fn: function () { app.edit(function () { var tz = app.zoneNode('body'), b = tz ? AL._px.boxOf(tz) : null; AL.guessFan(ch, set, b ? { x: b.cx, y: b.cy } : null); }); app.toast('Pins guessed from the art'); } })
    ]));
    pb.appendChild(ui.lab('How it moves'));
    if (many) pb.appendChild(ui.toggle('Swap drawings', set.pick.swap !== false, function (v) { app.edit(function () { set.pick.swap = v; }); }, 'Off: always the resting drawing, bent'));
    pb.appendChild(ui.toggle('Bend to reach', !kids.some(function (k) { return k.limb && k.limb.bend === false; }), function (v) { app.edit(function () { kids.forEach(function (k) { if (k.limb) k.limb.bend = v; }); }); }, 'Off: the drawing only swings from the shoulder'));
    pb.appendChild(ui.toggle('One shoulder for all', shared, function (v) { app.sharedShoulder = v; app.refreshPanel(true); }, 'Moving the shoulder pin moves it on every drawing'));
    if (many) pb.appendChild(ui.slider('Cross-fade', { min: 0, max: 300, step: 10, value: set.pick.fade == null ? 110 : set.pick.fade, set: function (v) { set.pick.fade = v; }, fmt: function (v) { return v ? v + ' ms' : 'cut'; } }));
    pb.appendChild(ui.slider('Stretch', { min: 0, max: 40, value: Math.round((cur.limb && cur.limb.stretch != null ? cur.limb.stretch : 0.12) * 100), set: function (v) { kids.forEach(function (k) { if (k.limb) k.limb.stretch = v / 100; }); }, fmt: function (v) { return v + '%'; }, title: 'How far past its drawn length the arm may stretch to reach' }));
    pb.appendChild(ui.slider('Elbow softness', { min: 5, max: 60, value: Math.round((cur.limb && cur.limb.soft != null ? cur.limb.soft : 0.3) * 100), set: function (v) { kids.forEach(function (k) { if (k.limb) k.limb.soft = v / 100; }); }, fmt: function (v) { return v + '%'; }, title: 'A soft elbow curves like a noodle; a hard one creases' }));
    app.pinProvider = function () {
      var l = cur.limb; if (!l) return [];
      return [
        { key: 'a', label: 'Shoulder', x: l.a.x, y: l.a.y, node: cur.id, to: 'b', set: function (x, y) { var dx = x - l.a.x, dy = y - l.a.y; (app.sharedShoulder !== false ? kids : [cur]).forEach(function (k) { if (k.limb) { k.limb.a.x += dx; k.limb.a.y += dy; } }); } },
        { key: 'b', label: 'Elbow', x: l.b.x, y: l.b.y, node: cur.id, to: 'c', set: function (x, y) { l.b.x = x; l.b.y = y; } },
        { key: 'c', label: 'Hand', x: l.c.x, y: l.c.y, node: cur.id, set: function (x, y) { l.c.x = x; l.c.y = y; } }
      ];
    };
  }

  function bodyCard(app, pb) {
    var ui = app.ui, st = app.ch.settings, t = app.zoneNode('body');
    head(app, 'Body', null, 'The chest breathes and the body leans. Head, arms and tail ride on it.');
    app.coach('<b>Body.</b> The ring is where the chest swells. The <b>Hips</b> pin is what it leans from.');
    pb.appendChild(ui.toggle('Breathing', st.breathe !== false, function (v) { app.edit(function () { st.breathe = v; }); }, 'A slow swell of the chest; head and arms ride it'));
    if (t.breathe) pb.appendChild(ui.slider('How deep', { min: 0, max: 3, step: 0.1, value: t.breathe.amt == null ? 1 : t.breathe.amt, set: function (v) { t.breathe.amt = v; }, fmt: function (v) { return v + '×'; } }));
    pb.appendChild(ui.slider('Breaths a minute', { min: 6, max: 40, value: Math.round((st.breathRate || 0.28) * 60), set: function (v) { st.breathRate = v / 60; }, fmt: String }));
    pb.appendChild(ui.toggle('Never quite still', st.idle !== false, function (v) { app.edit(function () { st.idle = v; }); }, 'A slight drift of head and body when nothing else is happening'));
    pb.appendChild(ui.lab('Which way it faces'));
    pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'Drawn facing' }), ui.seg([['left', 'Left'], ['front', 'Front'], ['right', 'Right']], st.facing || 'front', function (v) { app.edit(function () { st.facing = v; }); }, 'Which way the body was drawn'), h('span')]));
    pb.appendChild(ui.toggle('Turn around by itself', !!st.autoFlip, function (v) { app.edit(function () { st.autoFlip = v; if (v && st.facing === 'front') st.facing = 'left'; }); }, 'When the head turns the other way the whole character flips, so one three-quarter drawing faces both ways'));
    if (st.autoFlip) pb.appendChild(ui.slider('Flips past', { min: 0.1, max: 0.9, step: 0.05, value: st.flipAt == null ? 0.4 : st.flipAt, set: function (v) { st.flipAt = v; }, fmt: function (v) { return Math.round(v * 100) + '% turned'; } }));
    pb.appendChild(ui.row([ui.chip('See it flipped', { on: !!app._bs && app._bs.flip, icon: 'flip', title: 'Show the mirrored side while building', fn: function () { app.buildSignals().flip = !app._bs.flip; app.refreshPanel(true); } })]));
    app.pinProvider = function () {
      var pins = [];
      if (t.breathe) pins.push({ key: 'chest', label: 'Chest', x: t.breathe.cx, y: t.breathe.cy, node: null, ring: t.breathe.r, set: function (x, y) { t.breathe.cx = x; t.breathe.cy = y; } });
      if (t.breathe) pins.push({ key: 'size', label: 'Size', x: t.breathe.cx + t.breathe.r, y: t.breathe.cy, node: null, color: '#a78bfa', set: function (x, y) { t.breathe.r = Math.max(8, Math.hypot(x - t.breathe.cx, y - t.breathe.cy)); } });
      if (t.pivot) pins.push({ key: 'hips', label: 'Hips', x: t.pivot.x, y: t.pivot.y, node: null, color: '#fbbf24', set: function (x, y) { t.pivot.x = x; t.pivot.y = y; } });
      return pins;
    };
  }

  function tailCard(app, pb) {
    var ui = app.ui, ch = app.ch, t = app.zoneNode('tail'), art = t.type === 'art' ? t : AL.find(t, function (k) { return k.type === 'art'; });
    head(app, 'Tail', art && art.dangle ? 'swings' : 'in place', 'Swings by itself when the body moves, and settles again. Nothing to animate.');
    app.coach('<b>Tail.</b> <b>Root</b> is where it joins the body, <b>Tip</b> is the loose end.');
    if (!art) return;
    if (!art.dangle) { pb.appendChild(ui.row([ui.chip('Make it swing', { icon: 'wand', cls: 'go', fn: function () { app.edit(function () { var tz = app.zoneNode('body'), b = tz ? AL._px.boxOf(tz) : null; AL.guessTail(ch, art, b ? { x: b.cx, y: b.cy } : null); }); } })])); return; }
    var d = art.dangle;
    pb.appendChild(ui.toggle('Swing', d.on !== false, function (v) { app.edit(function () { d.on = v; }); }));
    pb.appendChild(ui.slider('Springiness', { min: 15, max: 220, value: d.stiff, set: function (v) { d.stiff = v; }, fmt: String, title: 'Low is floppy, high snaps back' }));
    pb.appendChild(ui.slider('Settle', { min: 1, max: 20, step: 0.5, value: d.damp, set: function (v) { d.damp = v; }, fmt: String, title: 'How quickly it comes to rest' }));
    pb.appendChild(ui.slider('Follow-through', { min: 0, max: 3, step: 0.1, value: d.swing, set: function (v) { d.swing = v; }, fmt: function (v) { return v + '×'; }, title: 'How hard a body movement throws it' }));
    pb.appendChild(ui.slider('Idle wag', { min: 0, max: 0.3, step: 0.01, value: d.wag, set: function (v) { d.wag = v; }, fmt: function (v) { return Math.round(v * 100) + ''; } }));
    pb.appendChild(ui.row([ui.chip('Guess pins again', { icon: 'wand', fn: function () { app.edit(function () { var tz = app.zoneNode('body'), b = tz ? AL._px.boxOf(tz) : null; AL.guessTail(ch, art, b ? { x: b.cx, y: b.cy } : null); }); } })]));
    app.pinProvider = function () { return [{ key: 'root', label: 'Root', x: art.pivot.x, y: art.pivot.y, node: null, to: 'tip', color: '#fbbf24', set: function (x, y) { art.pivot.x = x; art.pivot.y = y; } }, { key: 'tip', label: 'Tip', x: d.tip.x, y: d.tip.y, node: null, set: function (x, y) { d.tip.x = x; d.tip.y = y; } }]; };
  }
  function legCard(app, pb, zone) {
    var n = app.zoneNode(zone);
    head(app, ZLABEL[zone], 'planted', 'Stays where it is drawn, so the feet do not slide while the body leans.');
    app.coach('<b>' + ZLABEL[zone] + '.</b> Planted. Turn on <b>Move art</b> (right edge) to slide it.');
    follows(app, pb, n);
  }

  /* ── "follows": any part can ride any signal. This is how a frill lifts with the mouth or an ear twitches on a blink. ── */
  var SIGS = [['mouth', 'the mouth opening'], ['turnX', 'the head turning left and right'], ['turnY', 'the head turning up and down'], ['roll', 'the head tilting'], ['lookX', 'the eyes looking sideways'], ['lookY', 'the eyes looking up and down'], ['blinkL', 'a blink'], ['browL', 'the brows'], ['breath', 'breathing'], ['sway', 'the body leaning'], ['bounce', 'the body bouncing']];
  var PROPS = [['y', 'slides up and down'], ['x', 'slides sideways'], ['rot', 'turns'], ['s', 'grows and shrinks'], ['sy', 'stretches tall'], ['sx', 'stretches wide']];
  function follows(app, pb, n) {
    var ui = app.ui; n.links = n.links || [];
    pb.appendChild(ui.lab('Follows'));
    if (!n.links.length) pb.appendChild(ui.note('Make this part move with something else: lift with the mouth, twitch on a blink, bob with the breath.'));
    n.links.forEach(function (l, i) {
      var sName = (SIGS.filter(function (s) { return s[0] === l.sig; })[0] || [0, l.sig])[1], pName = (PROPS.filter(function (p) { return p[0] === l.prop; })[0] || [0, l.prop])[1];
      pb.appendChild(h('div', { class: 'rowf', style: 'margin-top:4px' }, [h('span', { class: 'note', style: 'margin:0;flex:1;min-width:0', html: 'It <b>' + pName + '</b> with <b>' + sName + '</b>' }), h('button', { class: 'ib', title: 'Remove this', onclick: function () { app.edit(function () { n.links.splice(i, 1); }); } }, AL.icon('x'))]));
      pb.appendChild(ui.slider('How much', { min: -40, max: 40, step: l.prop === 's' || l.prop === 'sx' || l.prop === 'sy' ? 0.02 : 0.5, value: l.prop === 's' || l.prop === 'sx' || l.prop === 'sy' ? clamp(l.amt, -1, 1) : l.amt, set: function (v) { l.amt = v; }, fmt: function (v) { return round1(v) + (l.prop === 'rot' ? '°' : l.prop.charAt(0) === 's' ? '×' : ' px'); } }));
      if (l.prop === 's' || l.prop === 'sx' || l.prop === 'sy') pb.lastChild.querySelector('input').min = -1, pb.lastChild.querySelector('input').max = 1;
    });
    var sigSel = h('select', { 'aria-label': 'Follows what' }, SIGS.map(function (s) { return h('option', { value: s[0], text: s[1] }); })), propSel = h('select', { 'aria-label': 'How it moves' }, PROPS.map(function (p) { return h('option', { value: p[0], text: p[1] }); }));
    pb.appendChild(h('div', { class: 'rowf', style: 'margin-top:6px' }, [propSel, h('span', { class: 'note', style: 'margin:0', text: 'with' }), sigSel, ui.chip('Add', { icon: 'plus', title: 'Add this link', fn: function () { app.edit(function () { var pr = propSel.value; n.links.push({ sig: sigSel.value, prop: pr, amt: pr === 'rot' ? 8 : pr.charAt(0) === 's' ? 0.15 : 4 }); if (!n.pivot && n.type !== 'art') { var b = AL.boundsOf(n); if (b) n.pivot = { x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 }; } }); } })]));
  }

  /* ── one layer, whatever it is ── */
  function nodeCard(app, pb, n) {
    var ui = app.ui, ch = app.ch, role = n.role && AL.ROLES[n.role];
    head(app, n.name, role ? role.label : (n.type === 'art' ? 'drawing' : n.type === 'set' ? 'one of' : 'folder'), 'Tell Avatar Studio what this is and it will rig it; or make it follow something.');
    app.coach('<b>' + n.name + '.</b> Not on the body map yet. Say what it is, or leave it as it is.');
    pb.appendChild(ui.row([
      ui.chip('What is this?', { icon: 'wand', cls: 'go', title: 'Say which part of the body this is', fn: function () { rolePicker(app, n); } }),
      ui.chip(n.hidden ? 'Hidden' : 'Shown', { on: !n.hidden, icon: n.hidden ? 'eyeoff' : 'eye', title: 'Show or hide this', fn: function () { app.edit(function () { if (n.hidden) delete n.hidden; else n.hidden = true; }); } }),
      ui.chip('Rename', { fn: function () { app.promptText('Name', n.name, function (v) { app.edit(function () { n.name = v; }); }); } }),
      ui.chip('Remove', { cls: 'quiet', icon: 'trash', title: 'Take this out of the character (Undo brings it back)', fn: function () { app.edit(function () { AL.remove(ch, n.id); app.sel.node = null; }); app.toast('Removed. Undo brings it back.'); } })
    ]));
    pb.appendChild(ui.slider('Depth', { min: -1.5, max: 1.5, step: 0.1, value: n.depth || 0, set: function (v) { n.depth = v; }, fmt: function (v) { return v > 0.05 ? 'front ' + v : v < -0.05 ? 'behind ' + (-v) : 'flat'; } }));
    follows(app, pb, n);
    if (n.type === 'art' && n.mesh) meshPins(app, pb, n);
    if (n.pivot) app.pinProvider = function () { var p = [{ key: 'pv', label: 'Turns about', x: n.pivot.x, y: n.pivot.y, node: null, color: '#fbbf24', set: function (x, y) { n.pivot.x = x; n.pivot.y = y; } }]; if (n.mesh) n.mesh.pins.forEach(function (q) { p.push({ key: q.id, label: q.name, x: q.x, y: q.y, node: null, set: function (x, y) { q.x = x; q.y = y; } }); }); return p; };
    else if (n.mesh) app.pinProvider = function () { return n.mesh.pins.map(function (q) { return { key: q.id, label: q.name, x: q.x, y: q.y, node: null, set: function (x, y) { q.x = x; q.y = y; } }; }); };
  }
  function meshPins(app, pb, n) {
    pb.appendChild(app.ui.lab('Pins on this drawing'));
    pb.appendChild(app.ui.row(n.mesh.pins.map(function (q) { return app.ui.chip(q.name, { icon: 'pin', title: 'Remove this pin', fn: function () { app.edit(function () { n.mesh.pins = n.mesh.pins.filter(function (k) { return k !== q; }); }); } }); }).concat([app.ui.chip('Pin it again', { icon: 'wand', fn: function () { AL.intake.pinWizard(app, n); } })])));
  }
  function rolePicker(app, n) {
    var body = h('div', { class: 'menu' });
    [['armL', 'Left arm'], ['armR', 'Right arm'], ['head', 'Head'], ['torso', 'Torso (the body)'], ['tail', 'Tail (swings)'], ['legL', 'Left leg'], ['legR', 'Right leg'], ['mouth', 'Mouth'], ['pupil', 'Pupil (follows the eyes)'], ['nose', 'Nose'], ['jaw', 'Jaw or chin'], ['hair', 'Hair, ear or frill'], ['browL', 'Brow'], ['prop', 'Something else']].forEach(function (r) {
      body.appendChild(h('button', { text: r[1], onclick: function () { app.closeSheet(); app.edit(function () { AL.applyRole(app.ch, n, r[0]); }); app.sel.node = null; app.tab = 'parts'; app.select(AL.ROLES[r[0]].zone); app.toast(n.name + ' is now: ' + r[1]); } }));
    });
    app.sheet('What is “' + n.name + '”?', body, []);
  }

  function extraCard(app, pb) {
    var ui = app.ui, ch = app.ch, list = cards.extras(app);
    head(app, 'Extras', list.length ? String(list.length) : null, 'Layers that are not part of the body map yet, and the buttons that swap drawings in.');
    app.coach('<b>Extras.</b> Tap a layer and say what it is, and it joins the body map.');
    if (ch.triggers.length) {
      pb.appendChild(ui.lab('Buttons (in Perform)'));
      pb.appendChild(ui.row(ch.triggers.map(function (t) { return ui.chip(t.name, { icon: 'bolt', on: !!app.test.trig[t.id], title: 'Hold to see “' + t.name + '”. Rename from the menu on it.', fn: function () { app.test.trig[t.id] = !app.test.trig[t.id]; app.refreshPanel(true); } }); })));
      pb.appendChild(ui.row(ch.triggers.map(function (t) { return ui.chip('Rename ' + t.name, { cls: 'quiet', fn: function () { app.promptText('Button name', t.name, function (v) { app.edit(function () { t.name = v; }); }); } }); })));
    }
    pb.appendChild(ui.lab('Not on the body map'));
    if (!list.length) pb.appendChild(ui.note('Nothing left over. Every layer belongs to a part.'));
    var tree = h('div', { class: 'tree' });
    list.forEach(function (n) { tree.appendChild(rowFor(app, n, 0)); });
    pb.appendChild(tree);
  }

  /* ── every layer, as a list ── */
  function rowFor(app, n, depth, open) {
    var kind = n.role && AL.ROLES[n.role] ? AL.ROLES[n.role].label : n.type === 'set' ? 'one of' : n.type === 'mirror' ? 'mirror' : '';
    var has = n.kids && n.kids.length, isOpen = open && open[n.id];
    return h('div', { class: 'tr' + (n.hidden ? ' hid' : '') + (app.sel.node === n.id ? ' sel' : ''), style: 'padding-left:' + (4 + depth * 12) + 'px', title: n.name, onclick: function () { app.sel = { zone: AL.zoneOf(app.ch, n.id), feature: null, node: n.id }; app.resolveSel(); app.userView = false; app.focusSel(); app.refreshPanel(true); } }, [
      h('span', { class: 'tw', text: has ? (isOpen ? '▾' : '▸') : '', onclick: has ? function (e) { e.stopPropagation(); open[n.id] = !open[n.id]; app.refreshPanel(true); } : null }),
      h('span', { class: 'nm', text: n.name }), kind ? h('span', { class: 'k', text: kind }) : null,
      n.type !== 'mirror' ? h('button', { class: 'ey', title: n.hidden ? 'Show' : 'Hide', onclick: function (e) { e.stopPropagation(); app.edit(function () { if (n.hidden) delete n.hidden; else n.hidden = true; }, true); app.refreshPanel(true); } }, AL.icon(n.hidden ? 'eyeoff' : 'eye')) : null
    ]);
  }
  function layers(app, pb) {
    var ch = app.ch, open = app.open || (app.open = {}), sel = app.sel.node && AL.node(ch, app.sel.node);
    if (!app._opened) { app._opened = true; AL.walk(ch.root, function (n, p, d) { if (d < 2) open[n.id] = true; }); }
    head(app, 'Layers', null, 'Every layer of the file. Tap one to light it up on the stage; the eye hides it.');
    app.coach(sel ? '<b>' + sel.name + '.</b> Lit on the stage.' : '<b>Layers.</b> Tap a layer to find it on the stage.');
    if (sel) pb.appendChild(app.ui.row([app.ui.chip('Open “' + sel.name + '”', { icon: 'wand', cls: 'go', title: 'Rig this layer: say what it is, make it follow something', fn: function () { app.tab = 'parts'; app.refreshPanel(); } }), app.ui.chip('Clear', { cls: 'quiet', fn: function () { app.sel.node = null; app.resolveSel(); app.refreshPanel(true); } })]));
    var tree = h('div', { class: 'tree', style: 'margin-top:8px' });
    (function walk(n, d) { if (n !== ch.root) tree.appendChild(rowFor(app, n, d, open)); if (n.kids && (n === ch.root || open[n.id])) n.kids.forEach(function (k) { walk(k, n === ch.root ? 0 : d + 1); }); })(ch.root, 0);
    pb.appendChild(tree);
  }
})(typeof self !== 'undefined' ? self : this);
