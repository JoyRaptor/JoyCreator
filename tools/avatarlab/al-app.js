/* AvatarLab: the app shell. The stage (draw, zoom, tap, drag), selection, undo, sheets, autosave,
 * and the small kit of controls the panels are built from. Panels live in al-cards / al-perform / al-intake.
 */
(function (root) {
  'use strict';
  var AL = root.AL, M = AL.M, NS = 'http://www.w3.org/2000/svg';
  function $(id) { return document.getElementById(id); }
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }

  function h(tag, a, kids) {
    var e = document.createElement(tag), k;
    if (a) for (k in a) {
      var v = a[k]; if (v == null || v === false) continue;
      if (k === 'class') e.className = v; else if (k === 'text') e.textContent = v; else if (k === 'html') e.innerHTML = v;
      else if (k.slice(0, 2) === 'on') e.addEventListener(k.slice(2), v); else e.setAttribute(k, v === true ? '' : v);
    }
    if (kids != null) (Array.isArray(kids) ? kids : [kids]).forEach(function (c) { if (c != null && c !== false) e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return e;
  }
  AL.h = h;
  var ICON = {
    menu: 'M4 7h16M4 12h16M4 17h16', undo: 'M9 14L4 9l5-5M4 9h10a6 6 0 010 12h-3', redo: 'M15 14l5-5-5-5M20 9H10a6 6 0 000 12h3', x: 'M6 6l12 12M18 6L6 18',
    fit: 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5', onion: 'M9 4a8 8 0 100 16M15 4a8 8 0 110 16M15 4a8 8 0 000 16', mesh: 'M4 4h16v16H4zM4 12h16M12 4v16M4 20L20 4',
    flip: 'M12 3v18M8 8l-4 4 4 4M16 8l4 4-4 4', play: 'M8 5l11 7-11 7z', stop: 'M7 7h10v10H7z', mic: 'M12 3a3 3 0 00-3 3v6a3 3 0 006 0V6a3 3 0 00-3-3zM5 11a7 7 0 0014 0M12 18v3',
    cam: 'M3 8h12v8H3zM15 11l6-3v8l-6-3', eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 9a3 3 0 100 6 3 3 0 000-6z', eyeoff: 'M3 3l18 18M10 5.3A9.6 9.6 0 0112 5c6 0 10 7 10 7a17 17 0 01-3 3.7M6.4 6.6A16.8 16.8 0 002 12s4 7 10 7a9.6 9.6 0 004.3-1',
    layers: 'M12 4l9 5-9 5-9-5zM3 14l9 5 9-5', plus: 'M12 5v14M5 12h14', back: 'M15 5l-7 7 7 7', fwd: 'M9 5l7 7-7 7', wand: 'M4 20L15 9M17 3l1 3 3 1-3 1-1 3-1-3-3-1 3-1zM6 4v4M4 6h4',
    file: 'M6 3h8l5 5v13H6zM14 3v5h5', grid: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z', person: 'M12 4a3 3 0 100 6 3 3 0 000-6zM6 21v-3a6 6 0 0112 0v3',
    save: 'M5 4h11l3 3v13H5zM8 4v5h7M8 20v-6h8v6', star: 'M12 3l2.7 5.6 6.3.9-4.5 4.4 1 6.1-5.5-3-5.5 3 1-6.1L3 9.5l6.3-.9z', move: 'M12 3v18M3 12h18M9 6l3-3 3 3M9 18l3 3 3-3M6 9l-3 3 3 3M18 9l3 3-3 3',
    send: 'M4 12l16-8-6 16-3-7z', trash: 'M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13', loop: 'M17 2l3 3-3 3M4 11V9a4 4 0 014-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 01-4 4H4',
    pin: 'M12 21s7-6.3 7-12a7 7 0 10-14 0c0 5.7 7 12 7 12zM12 6.5a2.5 2.5 0 100 5 2.5 2.5 0 000-5z', home: 'M4 11l8-7 8 7M6 10v10h12V10', note: 'M9 18V5l11-2v13M9 18a3 3 0 11-6 0 3 3 0 016 0zM20 16a3 3 0 11-6 0 3 3 0 016 0z',
    smile: 'M12 3a9 9 0 100 18 9 9 0 000-18zM8 14a5 5 0 008 0M9 9.5v.5M15 9.5v.5', bolt: 'M13 3L5 14h6l-1 7 8-11h-6z', help: 'M12 3a9 9 0 100 18 9 9 0 000-18zM9.5 9.5a2.5 2.5 0 114 2c-.9.6-1.5 1.2-1.5 2.3M12 17v.5',
    mirror: 'M12 3v18M9 7l-5 5 5 5zM15 7l5 5-5 5z', target: 'M12 2v5M12 17v5M2 12h5M17 12h5M12 8.5a3.5 3.5 0 100 7 3.5 3.5 0 000-7z',
    cut: 'M6 6a2.5 2.5 0 100 5 2.5 2.5 0 000-5zM6 13a2.5 2.5 0 100 5 2.5 2.5 0 000-5zM8 10l12-6M8 14l12 6', image: 'M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15 9.5a1 1 0 100 .1'
  };
  AL.icon = function (name) {
    var s = document.createElementNS(NS, 'svg'); s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('class', 'ic'); s.setAttribute('aria-hidden', 'true');
    var P = AL.PICS && AL.PICS[name];                          // a picture in up to three parts: grey lines, a solid shape and lines in the mark colour
    if (P) { if (P.f || P.a) s.setAttribute('class', 'ic two'); [['g', P.d], ['f', P.f], ['a', P.a]].forEach(function (q) { if (!q[1]) return; var e = document.createElementNS(NS, 'path'); e.setAttribute('class', q[0]); e.setAttribute('d', q[1]); s.appendChild(e); }); return s; }
    var p = document.createElementNS(NS, 'path'); p.setAttribute('d', ICON[name] || ICON.plus); s.appendChild(p); return s;
  };

  var app = AL.app = {
    ch: null, solver: null, G: null, inp: null, out: null, hist: new AL.History(),
    mode: 'build', live: false, sel: { zone: null, feature: null, node: null }, selIds: null, tab: 'parts',
    force: {}, show: {}, onion: false, mesh: false, moveArt: false, bt: { x: 0, y: 0 },
    test: { mouth: null, blink: 0, lookX: 0, lookY: 0, brow: 0, lidUp: 0, lidLow: 0, lidTilt: 0, trig: {} },
    view: { s: 1, ox: 0, oy: 0 }, vt: null, pinProvider: null, hooks: []
  };

  /* ── small control kit ── */
  function pic(kind, label, title) { var id = AL.picFor ? AL.picFor(kind, label, title) : null; return id ? AL.icon(id) : null; }
  app.ui = {
    chip: function (label, o) { o = o || {}; return h('button', { class: 'chip' + (o.on ? ' on' : '') + (o.cls ? ' ' + o.cls : ''), title: o.title || label, onclick: o.fn ? function (ev) { app.nextLabel = o.undo || label; try { o.fn.call(this, ev); } finally { app.nextLabel = null; } } : null, disabled: o.disabled, 'aria-pressed': o.on == null ? null : String(!!o.on) }, [pic('chip', label) || (o.icon ? AL.icon(o.icon) : null), label]); },
    lab: function (text, stage) { var e = h('div', { class: 'lab', text: text }); if (stage) e.setAttribute('data-st', stage); return e; },
    note: function (html) { return h('p', { class: 'note', html: html }); },
    row: function (kids) { return h('div', { class: 'rowf' }, kids); },
    seg: function (opts, val, fn, title) { var s = h('span', { class: 'seg', role: 'group', title: title }); opts.forEach(function (o) { s.appendChild(h('button', { class: o[0] === val ? 'on' : '', title: o[2] || o[1], onclick: function () { app.nextLabel = (title ? title.replace(/\?$/, '') + ': ' : '') + o[1]; try { fn(o[0]); } finally { app.nextLabel = null; } } }, [pic('seg', o[1], title), o[1]])); }); return s; },
    toggle: function (label, on, fn, sub, title) { return h('button', { class: 'tg' + (on ? ' on' : ''), title: title || label, 'aria-pressed': String(!!on), onclick: function () { app.nextLabel = label + (on ? ' off' : ' on'); try { fn(!on); } finally { app.nextLabel = null; } } }, [h('i'), pic('tg', label), h('span', null, [label, sub ? h('small', { text: sub }) : null])]); },
    /** A slider that is one undo step per drag. get/set work on the model directly. */
    slider: function (label, o) {
      var out = h('output', { text: (o.fmt || String)(o.value) }), first = true;
      var inp = h('input', { type: 'range', min: o.min, max: o.max, step: o.step || 1, value: o.value, title: o.title || label, 'aria-label': label,
        onpointerdown: function () { first = true; }, onkeydown: function () { first = true; },
        oninput: function () { var v = parseFloat(inp.value); if (first && o.undo !== false) { app.hist.push(app.ch, label); app.syncUndo(); first = false; } o.set(v); out.textContent = (o.fmt || String)(v); app.touch(); },
        onchange: function () { first = true; if (o.done) o.done(); } });
      return h('div', { class: 'sl' }, [h('label', { text: label }), inp, out]);
    }
  };

  /* ── changing the character ── */
  app.edit = function (fn, quiet, label) { app.hist.push(app.ch, label || app.nextLabel || 'Change'); fn(); AL.reindex(app.ch); app.touch(); app.syncUndo(); if (!quiet) app.refreshPanel(); };
  app.touch = function () { app.dirty = true; app.saveState('unsaved'); clearTimeout(app._sv); app._sv = setTimeout(app.autosave, 1500); };
  /** The dot by the name: steady = saved here, amber = not saved yet, red = this browser refused to keep it. */
  app.saveState = function (st) {
    var dot = $('bname') && $('bname').firstElementChild; if (!dot) return; app._state = st;
    dot.style.background = st === 'failed' ? 'var(--danger)' : st === 'saved' ? '' : st === 'fresh' ? 'var(--faint)' : 'var(--warn)';
    if (st === 'fresh') { $('bname').title = 'Nothing has been changed yet, so there is nothing to keep. Tap to rename.'; return; }
    $('bname').title = (st === 'failed' ? 'Not saved: this browser would not keep it. Use Menu, Save this character.' : st === 'saved' ? 'Saved in this browser, with its take.' : 'Saving\u2026') + ' Tap to rename.';
  };
  app.syncUndo = function () {
    var p = app.hist.past, f = app.hist.future;
    $('bundo').disabled = !p.length; $('bredo').disabled = !f.length;
    $('bundo').title = p.length ? 'Undo: ' + p[p.length - 1].l + '  (hold for the list)' : 'Nothing to undo';
    $('bredo').title = f.length ? 'Redo: ' + f[f.length - 1].l : 'Nothing to redo';
  };
  /** Hold Undo: every step, newest first. Tap one to go back to just before it. */
  app.historySheet = function () {
    var p = app.hist.past, body = h('div', { class: 'menu' });
    if (!p.length) body.appendChild(app.ui.note('Nothing has been changed yet.'));
    for (var i = p.length - 1; i >= 0; i--) (function (i) { body.appendChild(h('button', { title: 'Go back to before this', onclick: function () { app.closeSheet(); for (var k = p.length - i; k > 0; k--) app.undo(false, true); app.toast('Went back ' + (p.length - i) + (p.length - i === 1 ? ' step' : ' steps')); } }, [AL.icon('undo'), h('span', { text: p[i].l }), h('small', { text: i === p.length - 1 ? 'latest' : '' })])); })(i);
    app.sheet('History', body, []);
  };
  app.undo = function (redo, quiet) {
    var o = redo ? app.hist.redo(app.ch) : app.hist.undo(app.ch); if (!o) return;
    app.ch = o; app.solver.ch = o; app.solver.grids = {}; if (app.bridge && !AL.node(o, app.bridge.set)) app.bridge = null; app.keepSel(); app.syncUndo(); app.touch(); app.refreshPanel(); if (!quiet) app.toast((redo ? 'Redone: ' : 'Undone: ') + app.hist.said);
  };
  app.keepSel = function () { if (app.sel.node && !AL.node(app.ch, app.sel.node)) app.sel = { zone: app.sel.zone, feature: null, node: null }; app.resolveSel(); };

  app.setCharacter = function (ch, o) {
    o = o || {};
    if (app.inp) { app.inp.micStop(); app.inp.camStop(); }                      // the old character's microphone and camera go with it
    app.ch = ch; AL.reindex(ch); app.solver = new AL.Solver(ch); if (app.G) app.G.forget();
    try { if (AL.stance && AL.stance.heal(ch)) setTimeout(function () { app.toast('All fours was put right: the parts you placed by hand follow the tip again.'); }, 1200); } catch (e) { }      // a stance left broken by an older page (see al-stance.js)
    /* a leg made before feet had a bar gets one guessed, so it walks heel-to-toe without being asked (null = switched off on purpose) */
    ['legL', 'legR'].forEach(function (r) { var s = AL.firstRole(ch.root, r); if (s && s.kids) s.kids.forEach(function (k) { if (k.limb && k.limb.foot === undefined && k.limb.style !== 'hose') k.limb.foot = AL.guessFoot(ch, k); }); });
    app.inp = new AL.Inputs(app); app.hist = new AL.History(); app.syncUndo();
    app.force = {}; app.show = {}; app.bridge = null; app.morph = null; app.shapeEdit = null; app.lockForce = null; app.btd = {}; app.bt = { x: 0, y: 0 }; app.test = { mouth: null, blink: 0, lookX: 0, lookY: 0, brow: 0, lidUp: 0, lidLow: 0, lidTilt: 0, trig: {} }; app.live = false;
    $('chname').textContent = ch.name;
    var first = null; AL.ZONES.forEach(function (z) { if (!first && app.zoneNode(z.id)) first = z.id; });
    app.sel = { zone: first, feature: null, node: null }; app.resolveSel();
    $('empty').style.display = first ? 'none' : 'flex';
    app.vt = null; app.userView = false; app.fit(true);
    app.refreshPanel(); if (!o.quiet) app.touch(); else if (ch.demo && !ch.edited) app.saveState('fresh');
  };

  /* ── what is selected ── */
  app.zoneNode = function (zone) {
    var r = app.ch.root;
    if (zone === 'head') return AL.firstRole(r, 'head') || AL.firstRole(r, 'face');
    if (zone === 'body') return AL.firstRole(r, 'torso') || AL.firstRole(r, 'body');
    if (zone === 'extra') return null;
    return AL.firstRole(r, zone);
  };
  /** The head drawing on show right now (its source, when the angle is a mirror). */
  app.curView = function () {
    var head = app.zoneNode('head'); if (!head) return null;
    if (head.type !== 'set') return { node: head, mirror: false, kid: head };
    var id = (app.out && app.out.picks[head.id]) || (head.pick && head.pick.rest), kid = AL.node(app.ch, id) || head.kids[0];
    if (kid && kid.type === 'mirror') return { node: AL.node(app.ch, kid.of), mirror: true, kid: kid };
    return { node: kid, mirror: false, kid: kid };
  };
  var FEAT = app.FEAT = {
    eyes: { label: 'Eyes', roles: ['eyeL', 'eyeR', 'pupil', 'eyeball', 'blink'] }, brows: { label: 'Brows', roles: ['browL', 'browR'] }, mouth: { label: 'Mouth', roles: ['mouth'] },
    jaw: { label: 'Jaw', roles: ['jaw'] }, nose: { label: 'Nose', roles: ['nose'] }, hair: { label: 'Hair, ears, frill', roles: ['hair'] }
  };
  app.featureNodes = function (f, scope) {
    scope = scope || (app.curView() || {}).node; if (!scope) return [];
    var roles = FEAT[f].roles, top = [];
    AL.walk(scope, function (n) { if (n.role && roles.indexOf(n.role) >= 0) { top.push(n); return false; } });
    return top;
  };
  app.resolveSel = function () {
    var s = app.sel, ids = {}, nodes = [];
    if (s.node && AL.node(app.ch, s.node)) nodes = [AL.node(app.ch, s.node)];
    else if (s.zone === 'head' && s.feature) nodes = app.featureNodes(s.feature);
    else if (s.zone) { var z = app.zoneNode(s.zone); if (z) nodes = [z]; }
    nodes.forEach(function (n) { AL.walk(n, function (k) { ids[k.id] = 1; if (k.type === 'mirror') { var src = AL.node(app.ch, k.of); if (src) AL.walk(src, function (q) { ids[q.id] = 1; }); } }); });
    app.selNodes = nodes; app.selIds = nodes.length ? ids : null;
  };
  app.select = function (zone, feature, nodeId) {
    app.sel = { zone: zone, feature: feature || null, node: nodeId || null }; app.selOff = false; app.live = app.mode === 'perform' ? app.live : !!(AL.stages && AL.stages.is(app, 'motion'));
    app.test.mouth = null; app.test.lidUp = app.test.lidLow = app.test.lidTilt = 0; app.show = {}; app.showLids = false; app.bridge = null; app.morph = null; app.shapeEdit = null; app.fitOn = null; app.orderPick = null; app.eyedrop = null; app.lockForce = null; app.resolveSel(); app.userView = false; app.focusSel(); app.refreshPanel();
  };
  app.selectByNode = function (id) {
    app._hitId = id;                                           // which of a pair (this eye, that eye) the finger was on
    var ch = app.ch, zone = AL.zoneOf(ch, id), feature = null;
    if (zone === 'head') for (var f in FEAT) { if (AL.ancestorWith(ch, id, function (a) { return a.role && FEAT[f].roles.indexOf(a.role) >= 0; })) { feature = f; break; } }
    var keepNode = zone === 'extra' ? id : null;
    var set = AL.ancestorWith(ch, id, function (a) { return a.type === 'set' && a.pick && a.pick.by === 'reach'; });
    if (set) app.force[set.id] = AL.ancestorWith(ch, id, function (a) { return AL.parentOf(ch, a.id) === set; }).id;
    app.select(zone, feature, keepNode);
  };

  /* ── the view: fit, focus, pan, pinch ── */
  app.stageSize = function () { var s = $('stage'); return { w: s.clientWidth, h: s.clientHeight }; };
  /* One-off, pilot only: copies of the built-in Topps kept in a browser from before 11-21 carry two faults the owner reported and could
     not reach to fix (the crest layer turned on its side; no order for the arms on all fours). Put right once, as one undo step. */
  function mendTopps() {
    var ch0 = app.ch, f0 = ch0 && AL.stance ? AL.stance.fours(ch0) : null;
    if (f0 && f0.fours && !f0.fours.v) {                          // an all-fours stance left broken by an older page: put right once
      var healed = false; app.edit(function () { healed = AL.stance.heal(ch0); }, false, 'All fours put right');
      if (healed) setTimeout(function () { app.toast('All fours was put right: the parts you placed by hand follow the tip again. Undo brings it back as it was.'); }, 1200);
    }
    var ch = app.ch; if (!ch || ch.demo !== 'topps' || ch.mended || !AL.stance) return;
    var fr = AL.node(ch, 'n5byqt'), p = AL.stance.fours(ch), hd = AL.node(ch, 'n3ubb0'), jobs = [];
    if (fr && fr.rot && Math.abs(fr.rot) > 60) jobs.push(function () { delete fr.rot; });
    if (p && hd) ['n3fn72', 'n3oyl7'].forEach(function (id, i) { var d = p.d && p.d[id]; if (d && !d.z && AL.node(ch, id)) jobs.push(function () { d.z = i ? { how: 'under', of: hd.id, at: 0.35 } : { how: 'under', of: hd.id, at: 0.35, tip: 0.5 }; }); });
    if (!jobs.length) return;
    app.edit(function () { jobs.forEach(function (f) { f(); }); ch.mended = 1; }, false, 'Topps put right (crest, arms behind the head)');
    setTimeout(function () { app.toast('Topps\u2019s crest and front legs were put right. Undo brings them back as they were.'); }, 900);
  }
  /* In Build, a stance that puts the hands on the ground shows them planted there, as Perform will: the still picture must not
     say something the moving one does not. (The same test the walker makes: is the ground within the arm's reach?) */
  app.plantPreview = function (S) {
    var ch = app.ch, legs = app.inp && app.inp.walker.legs(), list = legs ? legs.all : [], st = ch.settings, wk = st.walk || {};
    var p = app.poseEdit && AL.stance ? AL.stance.find(ch, app.poseEdit) : null, amt = app.poseAmt == null ? 1 : app.poseAmt, face = st.facing === 'left' ? -1 : st.facing === 'right' ? 1 : 0;
    /* Whether a stance puts a hand on the ground is settled by the WHOLE stance, seen at full; the "See it" slider then carries the
       hand there a little at a time. (It used to be asked afresh at every slider position, so the hand snapped to the ground part-way.) */
    var full = amt >= 0.999, pid = p ? p.id : null, memo = app._plant && app._plant.ch === ch && app._plant.pose === pid ? app._plant : (app._plant = { ch: ch, pose: pid, on: {} });
    var kk = full ? 1 : clamp((amt - 0.15) / 0.7, 0, 1); kk = kk * kk * (3 - 2 * kk);
    list.forEach(function (q) {
      if (!q.arm || !q.lift) return;
      var a = S.anchor && S.anchor[q.key], want = null;
      if (p && kk > 0 && a && wk.on !== false && wk.groundY != null) {
        var far = q.mate ? Math.max(0, wk.groundY - q.mate.foot.y) : 0, hy = wk.groundY - q.palm - far, hgt = hy - a[1];
        var can = hgt > 2 && hgt < q.len * (1 + q.stretch) * 1.12;
        if (full) memo.on[q.key] = can; else if (memo.on[q.key] != null) can = memo.on[q.key] && hgt > 2;
        if (can) { want = { x: a[0] + face * q.len * 0.1, y: hy, w: true, pv: true }; if (kk < 1) want.k = kk; }
      }
      if (want) S.hand[q.key] = want; else if (S.hand[q.key] && S.hand[q.key].pv) S.hand[q.key] = null;
    });
    /* the hips come down as far as the stance says, and the feet stay where they stand (so the legs fold): what the walker will do */
    var drop = p && p.fours ? (p.fours.drop || 0) * amt : 0;
    S.root.y = drop;
    list.forEach(function (q) { if (q.arm) return; if (drop) S.hand[q.key] = { x: q.foot.x, y: q.foot.y, pv: true }; else if (S.hand[q.key] && S.hand[q.key].pv) S.hand[q.key] = null; });
  };
  /** The box the drawn picture of these parts fills right now (their pose, not how they were painted). null = everything. */
  app.drawnBounds = function (nodes) {
    var out = app.out, ids = null, b = null; if (!out) return null;
    if (nodes) { ids = {}; nodes.forEach(function (n) { AL.walk(n.type === 'mirror' ? AL.node(app.ch, n.of) || n : n, function (k) { if (k.type === 'art') ids[k.id] = 1; }); }); }
    out.items.forEach(function (it) {
      if (it.ghost || (ids && !ids[it.id])) return; var v = it.v;
      for (var i = 0; i < v.length; i += 2) { var x = v[i], y = v[i + 1]; if (!b) b = { x0: x, y0: y, x1: x, y1: y }; else { if (x < b.x0) b.x0 = x; if (x > b.x1) b.x1 = x; if (y < b.y0) b.y0 = y; if (y > b.y1) b.y1 = y; } }
    });
    return b;
  };
  app.fitTo = function (b, pad, snap) {
    /* the picture is fitted into what is left of the stage once the hint line (top) and the row of buttons (bottom) have taken their room,
       so neither ever covers the feet, a pin or the box's turn handle */
    var z = app.stageSize(), co = $('coach'), sb = $('stagebar'), w = Math.max(8, b.x1 - b.x0), hh = Math.max(8, b.y1 - b.y0);
    var top = Math.max(44, co && co.offsetHeight ? co.offsetTop + co.offsetHeight + 4 : 0), bot = sb && sb.children.length && sb.offsetHeight ? z.h - sb.offsetTop + 4 : 0;
    if (z.h - top - bot < 140) { top = 44; bot = 0; }
    var s = Math.min((z.w - pad * 2) / w, (z.h - top - bot - pad * 1.4) / hh), full = app.ch ? Math.min(z.w / app.ch.w, z.h / app.ch.h) : 1;
    s = Math.min(s, full * 5);
    var v = { s: s, ox: (z.w - w * s) / 2 - b.x0 * s, oy: top + (z.h - top - bot - hh * s) / 2 - b.y0 * s };
    if (snap) { app.view = v; app.vt = null; } else app.vt = v;
  };
  app.fit = function (snap) {
    var b = ((app.poseEdit || app.held) && app.mode !== 'perform' && !app.live && app.drawnBounds(null)) || AL.boundsOf(app.ch.root) || { x0: 0, y0: 0, x1: app.ch.w, y1: app.ch.h };
    if ((app.mode === 'perform' || app.live) && app.inp && app.inp.walker.travels()) { var w = b.x1 - b.x0, hh = b.y1 - b.y0; b = { x0: b.x0 - w * 0.7, x1: b.x1 + w * 0.7, y0: b.y0 - hh * 0.1, y1: b.y1 + hh * 0.05 }; }   // room to walk
    app.fitTo(b, 14, snap);
  };
  app.focusSel = function () {
    if (app.userView) return;
    var liveNow = app.mode === 'perform' || app.live, b = null;
    if (!liveNow && app.selNodes && app.selNodes.length && app.sel.zone !== 'body') {
      app.selNodes.forEach(function (n) { var q = n.type === 'mirror' ? AL.boundsOf(AL.node(app.ch, n.of)) : (n.type === 'set' && n.pick && n.pick.by === 'turn' ? AL.boundsOf((app.curView() || {}).node || n) : AL.boundsOf(n)); if (q) b = b ? { x0: Math.min(b.x0, q.x0), y0: Math.min(b.y0, q.y0), x1: Math.max(b.x1, q.x1), y1: Math.max(b.y1, q.y1) } : q; });
    }
    if (b && (app.poseEdit || app.held)) b = app.drawnBounds(app.selNodes) || b;          // in a stance (or a held pose) the part is where it is drawn, not where it was painted
    if (!liveNow && app.bridge) { var bs = AL.node(app.ch, app.bridge.set); b = (bs && app.drawnBounds([bs])) || b; }      // matching two angles: close in on the head, so a landmark can be put where it is meant
    else if (b && AL.stages && AL.stages.is(app, 'fit') && !app.sel.feature && !app.sel.node) b = null;      // fitting a whole part: keep the whole character in view, to see what it sits against
    if (!b) return app.fit(false);
    var w = b.x1 - b.x0, hh = b.y1 - b.y0, grow = app.bridge ? 14 : app.sel.feature ? Math.max(w, hh) * 0.9 + 26 : (AL.stages && AL.stages.is(app, 'fit')) ? Math.max(w, hh) * 0.6 + 24 : Math.max(w, hh) * 0.16 + 10;      // (fitting wants to see what the part sits against)
    app.fitTo({ x0: b.x0 - grow, y0: b.y0 - grow, x1: b.x1 + grow, y1: b.y1 + grow }, 8, false);
  };
  app.toChar = function (px, py) { var v = app.view; return [(px - v.ox) / v.s, (py - v.oy) / v.s]; };
  app.toInner = function (cx, cy) { return app.out ? M.ap(M.inv(app.out.rootM), cx, cy) : [cx, cy]; };

  /* ── signals while building: everything at rest, plus whatever is being tried ── */
  app.buildSignals = function () {
    var S = app._bs || (app._bs = AL.restSignals()), t = app.test;
    S.turnX = app.bt.x; S.turnY = app.bt.y; S.mouth = t.mouth; S.viseme = t.vis || 'Aa'; S.blinkL = S.blinkR = t.blink; S.lookX = t.lookX; S.lookY = t.lookY; S.dial = app.btd || {}; S.browL = S.browR = t.brow; S.lidUp = t.lidUp || 0; S.lidLow = t.lidLow || 0; S.lidTilt = t.lidTilt || 0; S.trig = t.trig; S.breath = 0;
    return S;
  };

  /* ── the frame ── */
  app.frame = function (ts) {
    requestAnimationFrame(app.frame);
    if (!app.ch) return;
    var dt = clamp((ts - (app._ts || ts)) / 1000, 0.001, 0.05); app._ts = ts;
    var st = $('stage'), gl = $('gl'), ov = $('ov'), dpr = Math.min(2.5, root.devicePixelRatio || 1), w = st.clientWidth, hh = st.clientHeight;
    if (!w || !hh) return;
    if (gl.width !== Math.round(w * dpr) || gl.height !== Math.round(hh * dpr)) { gl.width = ov.width = Math.round(w * dpr); gl.height = ov.height = Math.round(hh * dpr); if (!app.userView) { app.vt = null; app.focusSel(); if (app.vt) { app.view = app.vt; app.vt = null; } } }
    if (app.vt) { var v = app.view, q = app.vt, k = 1 - Math.exp(-14 * dt); v.s += (q.s - v.s) * k; v.ox += (q.ox - v.ox) * k; v.oy += (q.oy - v.oy) * k; if (Math.abs(q.s - v.s) < 0.001 && Math.abs(q.ox - v.ox) < 0.3 && Math.abs(q.oy - v.oy) < 0.3) { app.view = q; app.vt = null; } }
    var liveNow = app.mode === 'perform' || app.live;
    var S = liveNow ? app.inp.step(dt) : (app.held || app.buildSignals());                   // (a held pose: the living picture frozen, to be mended)
    app._S = S;
    if (!liveNow && !app.held) app.plantPreview(S);
    if (!liveNow && !app.held) { var pzt = app.poseEdit && AL.stance ? AL.stance.find(app.ch, app.poseEdit) : null, tr0 = app.test.trig || {}; if (pzt && pzt.trig && (app.poseAmt == null || app.poseAmt > 0.5)) { var tr1 = {}; for (var tk in tr0) tr1[tk] = tr0[tk]; tr1[pzt.trig] = true; S.trig = tr1; } }      // in a stance, what its button brings in (a shape made for the stance) is in too
    else if (AL.stages && AL.stages.is(app, 'motion')) AL.stages.tryOn(app, S);
    var onion = null; if (!liveNow && app.onion && app.selNodes && app.selNodes[0] && app.selNodes[0].type === 'set') onion = app.selNodes[0].id;
    var fitNow = !liveNow && !!(AL.stages && AL.stages.is(app, 'fit'));
    var bridge = !liveNow ? app.bridge : null, eo = { live: liveNow, dt: dt, force: liveNow ? (app.lockForce || null) : app.force, show: liveNow ? null : app.show, onion: onion, lift: app.liftArt !== false && !app.poseEdit && !fitNow };
    if (bridge) {                                            // matching two head angles: one on stage, the other ghosted over it; or the real turn, to preview it
      if (bridge.t == null) { var fo = {}; for (var fk in app.force) fo[fk] = app.force[fk]; fo[bridge.set] = bridge.edit === 'a' ? bridge.a : bridge.b; eo.force = fo; eo.onion = bridge.set; eo.onionKid = bridge.edit === 'a' ? bridge.b : bridge.a; eo.onionAlpha = 0.6; eo.onionOver = true; }
      else eo.tween = true;
    } else if (!liveNow && app.ballTry) eo.tween = true;                     // previewing a round head turning, without leaving Build
    if (!liveNow && app.shapeEdit) eo.shapes = app.shapeEdit.w;                 // posing one shape of a part: that shape at full, the others off
    if (!liveNow && app.morph) { eo.morph = app.morph; eo.tween = true; }
    if (!liveNow && app.poseEdit) { eo.poses = {}; eo.poses[app.poseEdit] = app.poseAmt == null ? 1 : app.poseAmt; }      // working on a stance: the character stands in it
    if (!liveNow && app.poseEdit && eo.force) { var fo2 = {}; for (var fk2 in eo.force) { var fn2 = AL.node(app.ch, fk2); if (!(fn2 && fn2.type === 'set' && fn2.pick && fn2.pick.by === 'reach')) fo2[fk2] = eo.force[fk2]; } eo.force = fo2; }      // (so Fit and Motion show the same arm)
    if (!liveNow && app.toneTry) { eo.tones = {}; eo.tones[app.toneTry] = app.toneAmt == null ? 1 : app.toneAmt; }        // looking at a colour set
    var out = app.out = app.solver.eval(S, eo);
    if (!liveNow && app._pe !== (app.poseEdit || null)) { app._pe = app.poseEdit || null; setTimeout(function () { app.userView = false; app.focusSel(); }, 0); }      // into or out of a stance: frame it again, now that it is drawn there
    if (bridge && bridge.ids && bridge.t == null) out.items.forEach(function (it) { if (!bridge.ids[it.id]) { it.a0 = it.a; it.a *= it.ghost ? 0 : 0.22; } });      // matching one piece: the rest steps back
    var ghostTint = bridge ? AL.bridge.TINT[bridge.edit === 'a' ? 'b' : 'a'] : null;
    var ids = (!liveNow && app.selIds && app.sel.zone !== 'body' ) ? app.selIds : null;
    if (ids && !bridge && app.liftArt !== false && !app.poseEdit && !fitNow) {            // (not while looking at how a limb really sits behind the body)
      var firstSel = -1, ii; for (ii = 0; ii < out.items.length; ii++) if (ids[out.items[ii].id]) { firstSel = ii; break; }
      if (firstSel >= 0) for (ii = firstSel + 1; ii < out.items.length; ii++) { var fi = out.items[ii]; if (!ids[fi.id] && !fi.ghost) { fi.a0 = fi.a; fi.a *= 0.14; } }
    }
    /* FIT: a part that sits behind another is still the part in hand. A faint cyan echo of it is drawn over everything, so it can be seen and placed. */
    if (fitNow && !bridge && !app.selOff && app.selIds && app.tab === 'parts' && app.sel.zone !== 'body') {
      var lastSel = -1, lastAny = -1, xi; for (xi = 0; xi < out.items.length; xi++) { var xq = out.items[xi]; if (xq.ghost) continue; lastAny = xi; if (app.selIds[xq.id]) lastSel = xi; }
      if (lastSel >= 0 && lastSel < lastAny) { var xr = []; out.items.forEach(function (it) { if (it.ghost || !app.selIds[it.id]) return; var g = {}; for (var gk in it) g[gk] = it[gk]; g.ghost = true; g.xr = true; g.a = 0.45; xr.push(g); }); out.items = out.items.concat(xr); }
    }
    app.G.draw(out.items, { s: app.view.s * dpr, ox: app.view.ox * dpr, oy: app.view.oy * dpr }, function (it) {
      if (it.xr) return [0.13, 0.83, 0.93, 0.62];
      if (it.ghost) return ghostTint || [0.65, 0.55, 0.98, 0.55];
      if (ids && !ids[it.id] && !fitNow) return [0.03, 0.03, 0.05, 0.52];      // (Fit shows the true picture: the box and the echo mark the part)
      return null;
    }, out.colour);
    app.drawOverlay(ov.getContext('2d'), dpr, liveNow, out);
    for (var i = 0; i < app.hooks.length; i++) app.hooks[i](dt, liveNow);
  };

  /* ── pins and guides, drawn over the picture ── */
  app.pinsNow = function () {
    if (!app.pinProvider || !app.out) return [];
    var list = app.pinProvider() || [];
    list.forEach(function (p) { var m = (p.node && app.out.M[p.node]) || app.out.rootM, w = M.ap(m, p.x, p.y); p.sx = w[0] * app.view.s + app.view.ox + (p.ox || 0); p.sy = w[1] * app.view.s + app.view.oy + (p.oy || 0); p.m = m; });
    return list;
  };
  /* THE PART IN HAND, SHOWN. Five ways, to be compared in use (the round button on the stage cycles them):
       Fine dashes  a hairline of moving dashes along the art's own outline, with gaps the art shows through
       Glow         a soft cyan halo outside the outline, nothing on the art
       Spotlight    everything else steps back into shadow, nothing on the art
       Corners      four small corners round it and a brief flash when it is chosen, then nothing on the art
       Wash         a faint magenta wash over the part, gently pulsing, no line
     All are made from the same thing: the selected pieces drawn alone into a second surface (their true silhouette). */
  app.LOOKS = [['ants', 'Fine dashes'], ['glow', 'Glow'], ['spot', 'Spotlight'], ['corners', 'Corners'], ['wash', 'Wash']];
  app.look = function () { if (!app._look) { try { app._look = localStorage.getItem('al.look2') || 'spot'; } catch (e) { app._look = 'spot'; } } return app._look; };
  app.nextLook = function () { var i = 0; app.LOOKS.forEach(function (q, k) { if (q[0] === app.look()) i = k; }); var n = app.LOOKS[(i + 1) % app.LOOKS.length]; app._look = n[0]; try { localStorage.setItem('al.look2', n[0]); } catch (e) { } app.toast('Selection shown as: ' + n[1]); app.renderTools(); };
  app.outline = function (g, dpr, out) {
    if (!(AL.stages && AL.stages.on()) || app.mode !== 'build' || app.bridge || app.tab !== 'parts' || !out || app.selOff) return;
    var ids = null;
    if (app.fitOn && app.fitOn.box) { var bn = AL.node(app.ch, app.fitOn.node); if (bn) { ids = {}; AL.walk(bn, function (k) { ids[k.id] = 1; }); } }
    else if (app.selIds && (app.sel.feature || app.sel.node || app.stage === 'fit')) ids = app.selIds;
    if (!ids) return;
    var items = out.items.filter(function (it) { return !it.ghost && ids[it.id]; }); if (!items.length) return;
    var W = g.canvas.width, H = g.canvas.height, O = app._ol || (app._ol = {}), mode = app.look(), now = performance.now();
    try {
      if (!O.gc) { O.gc = document.createElement('canvas'); O.G = new AL.GL(O.gc); O.t = document.createElement('canvas'); O.p = document.createElement('canvas'); O.p.width = O.p.height = 10; var pg = O.p.getContext('2d'); pg.fillStyle = '#fff'; pg.fillRect(0, 0, 5, 10); }
      if (O.ch !== app.ch) { if (O.G.forget) O.G.forget(); O.ch = app.ch; }
      if (O.gc.width !== W || O.gc.height !== H) { O.gc.width = O.t.width = W; O.gc.height = O.t.height = H; }
      var key = items.length + ':' + items[0].id; if (key !== O.key) { O.key = key; O.t0 = now; }
      O.G.draw(items, { s: app.view.s * dpr, ox: app.view.ox * dpr, oy: app.view.oy * dpr }, null, null);
      var t = O.t.getContext('2d'), k, alpha = 1;
      t.setTransform(1, 0, 0, 1, 0, 0); t.globalCompositeOperation = 'source-over'; t.filter = 'none'; t.clearRect(0, 0, W, H);
      if (mode === 'ants') {
        var r = Math.max(1, Math.round(dpr * 0.75));
        [[r, 0], [-r, 0], [0, r], [0, -r]].forEach(function (q) { t.drawImage(O.gc, q[0], q[1]); });
        t.globalCompositeOperation = 'destination-out'; for (k = 0; k < 4; k++) t.drawImage(O.gc, 0, 0);
        t.globalCompositeOperation = 'source-in'; t.translate(Math.floor(now / 80) % 10, 0); t.fillStyle = t.createPattern(O.p, 'repeat'); t.fillRect(-10, 0, W + 20, H);
      } else if (mode === 'glow') {
        t.filter = 'blur(' + Math.round(5 * dpr) + 'px)'; t.drawImage(O.gc, 0, 0); t.drawImage(O.gc, 0, 0); t.filter = 'none';
        t.globalCompositeOperation = 'source-in'; t.fillStyle = '#22d3ee'; t.fillRect(0, 0, W, H);
        t.globalCompositeOperation = 'destination-out'; for (k = 0; k < 4; k++) t.drawImage(O.gc, 0, 0);
        alpha = 0.75 + 0.25 * Math.sin(now / 420);
      } else if (mode === 'spot') {
        t.fillStyle = 'rgba(0,0,0,.58)'; t.fillRect(0, 0, W, H);
        t.globalCompositeOperation = 'destination-out'; for (k = 0; k < 4; k++) t.drawImage(O.gc, 0, 0);
      } else if (mode === 'wash') {
        t.drawImage(O.gc, 0, 0); t.globalCompositeOperation = 'source-in'; t.fillStyle = '#f43f8e'; t.fillRect(0, 0, W, H);
        alpha = 0.16 + 0.1 * Math.sin(now / 380);
      } else {                                                      // corners
        var age = now - (O.t0 || 0);
        if (age < 480) { t.drawImage(O.gc, 0, 0); t.globalCompositeOperation = 'source-in'; t.fillStyle = '#ffffff'; t.fillRect(0, 0, W, H); alpha = 0.55 * (1 - age / 480); }
      }
      t.setTransform(1, 0, 0, 1, 0, 0); t.globalCompositeOperation = 'source-over';
      g.save(); g.setTransform(1, 0, 0, 1, 0, 0); g.globalAlpha = alpha; g.drawImage(O.t, 0, 0); g.restore();
      if (mode === 'corners') {
        var b = null; items.forEach(function (it) { var v = it.v; for (var i = 0; i < v.length; i += 2) { var x = v[i], y = v[i + 1]; if (!b) b = { x0: x, y0: y, x1: x, y1: y }; else { if (x < b.x0) b.x0 = x; if (x > b.x1) b.x1 = x; if (y < b.y0) b.y0 = y; if (y > b.y1) b.y1 = y; } } });
        if (b) { var vw = app.view, x0 = b.x0 * vw.s + vw.ox - 4, y0 = b.y0 * vw.s + vw.oy - 4, x1 = b.x1 * vw.s + vw.ox + 4, y1 = b.y1 * vw.s + vw.oy + 4, L = 9;
          g.strokeStyle = '#ffffff'; g.lineWidth = 1; g.beginPath();
          [[x0, y0, 1, 1], [x1, y0, -1, 1], [x1, y1, -1, -1], [x0, y1, 1, -1]].forEach(function (c) { g.moveTo(c[0] + c[2] * L, c[1]); g.lineTo(c[0], c[1]); g.lineTo(c[0], c[1] + c[3] * L); }); g.stroke(); }
      }
    } catch (e) { }
  };
  /* GROUND card, in Motion: the three bands a finger moves the hips through (jump above, upright between, all fours below) */
  app.bands = function (g) {
    if (!(app.live && AL.stages && AL.stages.is(app, 'motion') && app.sel.zone === 'ground' && app.inp && app.inp.walker.band)) return;
    var B = app.inp.walker.band, v = app.view, Z = app.stageSize(), yU = (B.y - B.up) * v.s + v.oy, yD = (B.y + B.down) * v.s + v.oy, has4 = !!(AL.stance && AL.stance.fours(app.ch));
    function line(y, col) { g.strokeStyle = col; g.setLineDash([2, 6]); g.lineWidth = 1.5; g.beginPath(); g.moveTo(0, y); g.lineTo(Z.w, y); g.stroke(); g.setLineDash([]); }
    function pic(id, y, col) { var P = AL.PICS && AL.PICS[id]; if (!P) return; g.save(); g.translate(8, y - 9); g.scale(0.75, 0.75); g.lineWidth = 2; g.lineCap = g.lineJoin = 'round'; g.strokeStyle = col; g.fillStyle = col; [P.d, P.a].forEach(function (d) { if (d) g.stroke(new Path2D(d)); }); if (P.f) g.fill(new Path2D(P.f)); g.restore(); }
    g.save();
    line(yU, '#f43f8e'); if (has4) line(yD, '#a78bfa');
    pic('air-jump', Math.max(40, yU - 16), '#f43f8e');                       // above the top line: a jump
    pic('stand', (yU + (has4 ? yD : yU + 70)) / 2, '#a1a1aa');                 // between: upright
    if (has4) pic('legs-four', yD + 16, '#a78bfa');                          // below the bottom line: all fours
    g.restore();
  };
  app.drawOverlay = function (g, dpr, liveNow, out) {
    var v = app.view, i;
    g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, g.canvas.width, g.canvas.height);
    function sx(x) { return x * v.s + v.ox; } function sy(y) { return y * v.s + v.oy; }
    if (!liveNow) app.outline(g, dpr, out); else app.bands(g);
    if (app.mesh && (app.selIds || liveNow)) {
      g.lineWidth = 0.6; g.strokeStyle = 'rgba(167,139,250,.75)'; g.beginPath();
      out.items.forEach(function (it) {
        if (it.ghost || (!liveNow && !app.selIds[it.id]) || it.nx < 2) return;
        for (var t = 0; t < it.idx.length; t += 3) { var a = it.idx[t] * 2, b = it.idx[t + 1] * 2, c = it.idx[t + 2] * 2; g.moveTo(sx(it.v[a]), sy(it.v[a + 1])); g.lineTo(sx(it.v[b]), sy(it.v[b + 1])); g.lineTo(sx(it.v[c]), sy(it.v[c + 1])); g.closePath(); }
      });
      g.stroke();
    }
    if (liveNow) {
      var wc = app.inp.walker.cfg();
      if (wc && app.inp.walker.grounded()) { var gyy = sy(wc.groundY + app.inp.walker.base); g.beginPath(); g.moveTo(0, gyy); g.lineTo(g.canvas.width / dpr, gyy); g.strokeStyle = 'rgba(228,228,231,.16)'; g.lineWidth = 1; g.setLineDash([2, 6]); g.stroke(); g.setLineDash([]); }
      /* quiet rings on the things you can grab */
      for (var id in out.pins) { var p = out.pins[id]; if (p.c) { g.beginPath(); g.arc(sx(p.c[0]), sy(p.c[1]), 13, 0, 7); g.strokeStyle = 'rgba(255,255,255,.34)'; g.lineWidth = 1.5; g.stroke(); } if (p.mesh) p.mesh.forEach(function (q) { g.beginPath(); g.arc(sx(q[0]), sy(q[1]), 9, 0, 7); g.strokeStyle = 'rgba(255,255,255,.34)'; g.lineWidth = 1.5; g.stroke(); }); }
      return;
    }
    var pins = app.pinsNow(); app._pins = pins;
    /* links between pins (shoulder - elbow - hand) */
    var byKey = {}; pins.forEach(function (p) { byKey[p.key] = p; });
    g.lineWidth = 2; g.strokeStyle = app.bridge ? 'rgba(228,228,231,.55)' : 'rgba(34,211,238,.8)';
    pins.forEach(function (p) { if (p.to && byKey[p.to]) { g.beginPath(); g.moveTo(p.sx, p.sy); g.lineTo(byKey[p.to].sx, byKey[p.to].sy); g.stroke(); } if (p.ring) { g.beginPath(); g.setLineDash([5, 5]); g.arc(p.sx, p.sy, p.ring * v.s, 0, 7); g.stroke(); g.setLineDash([]); } if (p.oval) { g.beginPath(); g.setLineDash([5, 5]); g.ellipse(p.sx, p.sy, Math.max(1, p.oval[0] * v.s), Math.max(1, p.oval[1] * v.s), 0, 0, 7); g.stroke(); g.setLineDash([]); } });
    g.font = '600 11px "IBM Plex Sans",system-ui,sans-serif'; g.textBaseline = 'middle';
    pins.forEach(function (p) {                             // a transform box: its outline, drawn through the part's own move, turn and size
      if (!p.box) return; g.beginPath();
      p.box.forEach(function (q, bi) { var w = M.ap(p.m, q[0], q[1]); if (bi) g.lineTo(sx(w[0]), sy(w[1])); else g.moveTo(sx(w[0]), sy(w[1])); });
      g.closePath();
      /* the part in hand is ringed in moving white dashes: they show on any colour of art, cyan included */
      g.strokeStyle = '#22d3ee'; g.lineWidth = 1; g.stroke();      // (the box is only the handles' frame: the moving dashes go round the art itself, see app.outline)
    });
    pins.forEach(function (p) {
      if (p.kind === 'move') {                               // drag anywhere inside the box: a quiet four-way cross marks the middle
        g.strokeStyle = 'rgba(3,24,28,.9)'; g.lineWidth = 4; g.beginPath(); g.moveTo(p.sx - 8, p.sy); g.lineTo(p.sx + 8, p.sy); g.moveTo(p.sx, p.sy - 8); g.lineTo(p.sx, p.sy + 8); g.stroke();
        g.strokeStyle = '#e4e4e7'; g.lineWidth = 1.8; g.stroke(); return;
      }
      if (p.kind === 'corner') { g.fillStyle = 'rgba(3,24,28,.9)'; g.fillRect(p.sx - 7, p.sy - 7, 14, 14); g.fillStyle = '#22d3ee'; g.fillRect(p.sx - 5, p.sy - 5, 10, 10); return; }
      if (p.kind === 'turn') {                               // the turn handle hangs below the box on a short stalk
        g.strokeStyle = '#22d3ee'; g.lineWidth = 1.5; g.beginPath(); g.moveTo(p.sx - (p.ox || 0), p.sy - (p.oy || 0)); g.lineTo(p.sx, p.sy); g.stroke();
        g.beginPath(); g.arc(p.sx, p.sy, 10, 0, 7); g.fillStyle = 'rgba(3,24,28,.9)'; g.fill(); g.beginPath(); g.arc(p.sx, p.sy, 8, 0, 7); g.fillStyle = '#22d3ee'; g.fill();
        g.strokeStyle = '#03181c'; g.lineWidth = 1.6; g.beginPath(); g.arc(p.sx, p.sy, 4, Math.PI * 0.2, Math.PI * 1.6); g.stroke(); g.beginPath(); g.moveTo(p.sx + 1.2, p.sy - 6.8); g.lineTo(p.sx + 1.4, p.sy - 3.2); g.lineTo(p.sx - 2.4, p.sy - 3.8); g.stroke(); return;
      }
      if (p.ghost) {                                         // the other drawing's point: seen, not touched
        g.beginPath(); g.arc(p.sx, p.sy, 5.5, 0, 7); g.fillStyle = 'rgba(3,24,28,.85)'; g.fill(); g.beginPath(); g.arc(p.sx, p.sy, 3.5, 0, 7); g.fillStyle = p.color || '#f43f8e'; g.fill();
        if (p.label) { g.beginPath(); g.arc(p.sx, p.sy, 10 + 3 * Math.sin(Date.now() / 160), 0, 7); g.strokeStyle = p.color || '#f43f8e'; g.lineWidth = 1.5; g.stroke(); }
        return;
      }
      if (p.tap) {                                           // a small helper you tap, not drag
        g.beginPath(); g.arc(p.sx, p.sy, 10, 0, 7); g.fillStyle = p.on ? '#22d3ee' : 'rgba(13,13,16,.85)'; g.fill(); g.strokeStyle = p.on ? '#03181c' : '#e4e4e7'; g.lineWidth = 1.6;
        g.beginPath(); g.arc(p.sx, p.sy, 5, Math.PI * 0.15, Math.PI * 1.55); g.stroke(); g.beginPath(); g.moveTo(p.sx + 1, p.sy - 8.5); g.lineTo(p.sx + 1.5, p.sy - 4); g.lineTo(p.sx - 3.2, p.sy - 4.6); g.stroke();
        return;
      }
      var hot = app.drag && app.drag.pin && app.drag.pin.key === p.key, r = hot ? 10 : p.small ? 5.5 : 7.5;
      if (p.pulse || p.hot) { g.beginPath(); g.arc(p.sx, p.sy, r + 6 + (p.pulse ? 3 * Math.sin(Date.now() / 160) : 0), 0, 7); g.strokeStyle = p.color || '#22d3ee'; g.lineWidth = 1.5; g.stroke(); }
      g.beginPath(); g.arc(p.sx, p.sy, r + 2.5, 0, 7); g.fillStyle = 'rgba(3,24,28,.85)'; g.fill();
      g.beginPath(); g.arc(p.sx, p.sy, r, 0, 7); g.fillStyle = p.color || '#22d3ee'; g.fill();
      g.beginPath(); g.arc(p.sx, p.sy, 2, 0, 7); g.fillStyle = '#03181c'; g.fill();
      if (p.label) {
        var tw = g.measureText(p.label).width, lx = p.sx + 14, ly = p.sy + (p.dy || 0); if (lx + tw + 14 > g.canvas.width / dpr) lx = p.sx - 22 - tw;
        g.fillStyle = 'rgba(13,13,16,.78)'; g.beginPath(); g.roundRect(lx - 6, ly - 10, tw + 12, 20, 10); g.fill(); g.fillStyle = '#e4e4e7'; g.fillText(p.label, lx, ly + 0.5);
      }
    });
    /* a magnifier above the finger while a pin is dragged */
    if (app.drag && app.drag.pin && app.drag.moved) {
      var dk = app.drag.pin.key, pp = pins.filter(function (q) { return q.key === dk; })[0] || app.drag.pin, R = 46, zx = clamp(pp.sx, R + 6, g.canvas.width / dpr - R - 6), zy = pp.sy - 96 < R + 6 ? pp.sy + 96 : pp.sy - 96, z = 2.6, src = $('gl');
      g.save(); g.beginPath(); g.arc(zx, zy, R, 0, 7); g.clip(); g.fillStyle = '#0d0d10'; g.fill();
      g.drawImage(src, (pp.sx - R / z) * dpr, (pp.sy - R / z) * dpr, (2 * R / z) * dpr, (2 * R / z) * dpr, zx - R, zy - R, 2 * R, 2 * R);
      g.restore(); g.beginPath(); g.arc(zx, zy, R, 0, 7); g.strokeStyle = '#22d3ee'; g.lineWidth = 2; g.stroke();
      g.beginPath(); g.moveTo(zx - 8, zy); g.lineTo(zx + 8, zy); g.moveTo(zx, zy - 8); g.lineTo(zx, zy + 8); g.strokeStyle = 'rgba(255,255,255,.9)'; g.lineWidth = 1; g.stroke();
    }
  };

  /* ── fingers on the stage ──
     Perform: every finger that lands on a hand, a foot, the head, the body or a pin OWNS that thing until it lifts,
     so two hands, a hand and a foot, or two feet and the body can all be puppeteered at once. Fingers that land
     on nothing move the view: one pans, two pinch. Build: one finger drags pins, taps select. */
  function wire() {
    var st = $('stage'), ptr = {}, grabs = {}, pinch = null;
    function pos(e) { var r = st.getBoundingClientRect(), k = st.clientWidth / r.width; return { x: (e.clientX - r.left) * k, y: (e.clientY - r.top) * k }; }
    function count() { return Object.keys(ptr).length; }
    function isLive() { return app.mode === 'perform' || app.live; }
    /** The fingers that are on the character let go of it, as if lifted (in a scene: another character was taken in hand, and a finger still down on the first must move nobody). */
    app.dropFingers = function () { grabs = {}; };
    st.addEventListener('pointerdown', function (e) {
      if (e.target.closest && e.target.closest('button')) return;
      try { st.setPointerCapture(e.pointerId); } catch (x) { }
      var p = pos(e);
      if (isLive()) { var snap0 = AL.stages && AL.stages.is(app, 'motion') ? JSON.stringify([app.inp.pose, app.inp.held]) : null, gr = liveGrab(p); if (gr) { gr.snap = snap0; gr.x0 = p.x; gr.y0 = p.y; grabs[e.pointerId] = gr; return; } }
      ptr[e.pointerId] = p;
      if (count() === 2) { endDrag(true); var ks = Object.keys(ptr), a = ptr[ks[0]], b = ptr[ks[1]]; pinch = { d: Math.hypot(a.x - b.x, a.y - b.y), mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2 }; return; }
      if (count() === 1) begin(p);
    });
    st.addEventListener('pointermove', function (e) {
      if (grabs[e.pointerId]) { liveMove(grabs[e.pointerId], pos(e)); return; }
      if (!ptr[e.pointerId]) return; var p = ptr[e.pointerId] = pos(e);
      if (pinch && count() >= 2) {
        var ks = Object.keys(ptr), a = ptr[ks[0]], b = ptr[ks[1]], d = Math.hypot(a.x - b.x, a.y - b.y), mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2, v = app.view, f = d / (pinch.d || 1);
        v.ox = mx - (pinch.mx - v.ox) * f; v.oy = my - (pinch.my - v.oy) * f; v.s *= f; pinch = { d: d, mx: mx, my: my }; app.vt = null; app.userView = true; return;
      }
      if (app.drag) move(p);
    });
    function up(e) {
      if (grabs[e.pointerId]) { var g0 = grabs[e.pointerId], p0 = pos(e); liveEnd(g0); delete grabs[e.pointerId]; if (Math.hypot(p0.x - g0.x0, p0.y - g0.y0) < 6) { if (g0.snap) { var sp = JSON.parse(g0.snap), pk0; for (pk0 in app.inp.pose) if (!(pk0 in sp[0])) delete app.inp.pose[pk0]; for (pk0 in sp[0]) app.inp.pose[pk0] = sp[0][pk0]; for (pk0 in app.inp.held) app.inp.held[pk0] = !!sp[1][pk0]; } liveTap(p0); } return; }
      if (!ptr[e.pointerId]) return; delete ptr[e.pointerId]; if (count() < 2) pinch = null; if (count() === 0) endDrag(false);
    }
    st.addEventListener('pointerup', up); st.addEventListener('pointercancel', up);
    st.addEventListener('wheel', function (e) { e.preventDefault(); var p = pos(e), v = app.view, f = Math.exp(-e.deltaY * 0.0015); v.ox = p.x - (p.x - v.ox) * f; v.oy = p.y - (p.y - v.oy) * f; v.s *= f; app.vt = null; app.userView = true; }, { passive: false });
    st.addEventListener('dblclick', function (e) { var p = pos(e); if (isLive()) liveDouble(p); });

    function liveTap(p) { if (!(AL.stages && AL.stages.is(app, 'motion')) || !app.out) return; var c = app.toChar(p.x, p.y), hit = AL.hit(app.out, c[0], c[1]); if (hit) { app.tab = 'parts'; app.selectByNode(hit.id); } }
    function begin(p) {
      var c = app.toChar(p.x, p.y);
      app.drag = { x0: p.x, y0: p.y, x: p.x, y: p.y, moved: false };
      if (isLive()) return;
      /* the nearest pin; where two sit on top of each other, the one that says it comes first (prio) */
      var best = null, bd = 1e9; (app._pins || []).forEach(function (q) { if (q.ghost || q.fixed) return; var d = Math.hypot(q.sx - p.x, q.sy - p.y); if (d > (q.rad || (q.tap ? 16 : 26))) return; d -= (q.prio || 0); if (d < bd) { bd = d; best = q; } });
      if (best) { app.drag.pin = best; app.drag.off = { x: best.sx - p.x, y: best.sy - p.y }; return; }
      if (app.moveArt && app.selIds) { var hit = AL.hit(app.out, c[0], c[1], null, app.selIds); if (hit) { var ls = AL.ancestorWith(app.ch, hit.id, function (q) { return q.type === 'set' && q.pick && q.pick.by === 'reach'; }); app.drag.art = ls ? ls.id : hit.id; app.drag.last = c; } }      // only the selected part, even under something faded
    }
    function move(p) {
      var d = app.drag;
      if (!d.moved && Math.hypot(p.x - d.x0, p.y - d.y0) > 5) { d.moved = true; if (((d.pin && !d.pin.tap) || d.art) && !isLive()) { app.hist.push(app.ch, d.pin ? (d.pin.undo || 'Move ' + (d.pin.label || 'pin')) : 'Move art'); app.syncUndo(); } }
      if (!d.moved) return;
      if (!isLive()) {
        if (d.pin && !d.pin.tap) { var c = app.toChar(p.x + d.off.x - (d.pin.ox || 0), p.y + d.off.y - (d.pin.oy || 0)), l = M.ap(M.inv(d.pin.m), c[0], c[1]); d.pin.set(l[0], l[1]); d.pin.x = l[0]; d.pin.y = l[1]; app.touch(); return; }
        if (d.art) { var c2 = app.toChar(p.x, p.y); app.nudge(AL.node(app.ch, d.art), c2[0] - d.last[0], c2[1] - d.last[1]); d.last = c2; app.touch(); return; }
        if (d.pin) return;
      }
      app.view.ox += p.x - d.x; app.view.oy += p.y - d.y; d.x = p.x; d.y = p.y; app.vt = null; app.userView = true;
    }
    function endDrag(cancel) {
      var d = app.drag; app.drag = null; if (!d || cancel) return;
      if (isLive()) { if (!d.moved) liveTap({ x: d.x0, y: d.y0 }); return; }
      if (!d.moved) {
        if (d.pin && d.pin.tap) { d.pin.tap(); return; }
        var c = app.toChar(d.x0, d.y0);
        if (app.bridge) { AL.bridge.tap(app, c, d.pin); return; }
        if (d.pin && d.pin.kind !== 'move') { if (d.pin.pick) d.pin.pick(); return; }                                                    // (a tap inside the box is a tap on the picture: it chooses what is under the finger)
        if (app.eyedrop) { var ed = app.eyedrop; app.eyedrop = null; ed(AL.tone.colourAt(app.out, c[0], c[1])); return; }                 // tap a colour on the character
        if (app.fitOn && app.fitOn.pins && AL.fit.tapPin(app, c)) return;                                                         // reshaping: a tap on the part drops a pin there
        if (app.orderPick) { var oh = AL.hit(app.out, c[0], c[1]); if (oh) AL.fit.orderTap(app, oh.id); return; }                    // in a stance: which part this one passes behind, or in front of
        if (app.pickPiece) { var ph = AL.hit(app.out, c[0], c[1]); if (ph) { AL.fit.pick(app, ph.id); return; } }                // pick a piece: the very layer under the finger
        var staged = AL.stages && AL.stages.on();
        /* stages: a tap takes whatever is in FRONT under the finger (a horn over the frill), and a tap on empty space steps out one level */
        var hit = staged ? AL.hit(app.out, c[0], c[1]) : ((app.selIds && AL.hit(app.out, c[0], c[1], null, app.selIds)) || AL.hit(app.out, c[0], c[1]));
        if (hit && staged && AL.stages.stepIn(app, hit.id)) return;                                                              // a second tap on the part in hand goes into it: the layer under the finger
        if (hit) app.selectByNode(hit.id); else if (staged) AL.stages.up(app); return;
      }
      if (d.pin || d.art) { if (d.pin && d.pin.pick) d.pin.pick(); app.refreshPanel(true); }
    }

    /* Perform: what did this finger land on? */
    function nearestTip(c, roles, radius) {
      var out = app.out, ch = app.ch, best = null, bd = radius;
      roles.forEach(function (r) { var set = AL.firstRole(ch.root, r[0]); if (!set || set.type !== 'set') return; var pk = out.pins[out.picks[set.id]]; if (pk && pk.c) { var dd = Math.hypot(pk.c[0] - c[0], pk.c[1] - c[1]); if (dd < bd) { bd = dd; best = { sd: r[1], w: pk.c }; } } });
      return best;
    }
    function tipOf(role) { var set = AL.firstRole(app.ch.root, role), pk = set && app.out.pins[app.out.picks[set.id]]; return pk && pk.c ? pk.c : null; }
    function liveGrab(p) {
      var out = app.out, inp = app.inp, ch = app.ch, c = app.toChar(p.x, p.y), inner = app.toInner(c[0], c[1]), near = AL.stageWalk ? AL.stageWalk.near(app) : 1, rad = 30 / app.view.s * near;      // (near: in a scene a character is small, and its hands and feet must leave room to take the body; al-stage-walk.js)
      var hand = nearestTip(c, [['armL', 'L'], ['armR', 'R']], rad), foot = nearestTip(c, [['legL', 'L'], ['legR', 'R']], rad);
      if (!hand && !foot) AL.findAll(ch.root, function (q) { return q.type === 'set' && q.pick && q.pick.by === 'reach' && q.pick.hand && q.pick.hand.charAt(0) === 'F' && q.pick.hand !== 'FL' && q.pick.hand !== 'FR'; }).forEach(function (q) {      // the other legs of something with many
        var pk = out.pins[out.picks[q.id]]; if (!pk || !pk.c) return; var dd = Math.hypot(pk.c[0] - c[0], pk.c[1] - c[1]); if (dd < rad && (!foot || dd < foot.d)) foot = { sd: q.pick.hand.slice(1), w: pk.c, d: dd };
      });
      if (hand && foot) { if (Math.hypot(hand.w[0] - c[0], hand.w[1] - c[1]) <= Math.hypot(foot.w[0] - c[0], foot.w[1] - c[1])) foot = null; else hand = null; }
      var hit = hand || foot ? null : AL.hit(out, c[0], c[1]), zone = hit ? AL.zoneOf(ch, hit.id) : null, w;
      if (!hand && (zone === 'armL' || zone === 'armR') && (w = tipOf(zone))) hand = { sd: zone === 'armL' ? 'L' : 'R', w: w };
      if (!foot && (zone === 'legL' || zone === 'legR') && (w = tipOf(zone))) foot = { sd: zone === 'legL' ? 'L' : 'R', w: w };
      if (hand) { var wi = app.toInner(hand.w[0], hand.w[1]); inp.held['hand' + hand.sd] = true; inp.pose['hand' + hand.sd] = { x: wi[0], y: wi[1] }; return { kind: 'hand', sd: hand.sd, off: [wi[0] - inner[0], wi[1] - inner[1]] }; }
      if (foot) { inp.held['foot' + foot.sd] = true; inp.pose['foot' + foot.sd] = { x: foot.w[0], y: foot.w[1] }; return { kind: 'foot', sd: foot.sd, off: [foot.w[0] - c[0], foot.w[1] - c[1]] }; }
      var mp = null, md = 34 / app.view.s * near; for (var id in out.pins) if (out.pins[id].mesh) { var n = AL.node(ch, id); out.pins[id].mesh.forEach(function (q, i) { var dd = Math.hypot(q[0] - c[0], q[1] - c[1]); if (dd < md && n.mesh.pins[i]) { md = dd; mp = { pin: n.mesh.pins[i], q: q }; } }); }
      if (mp) { var qi = app.toInner(mp.q[0], mp.q[1]); if (inp.recording) inp.pinTouched[mp.pin.id] = true; return { kind: 'pin', pinId: mp.pin.id, off: [qi[0] - inner[0], qi[1] - inner[1]] }; }
      if (zone === 'head') { var hb = AL.boundsOf(app.zoneNode('head')); inp.held.head = true; return { kind: 'head', t0: [inp.pose.turnX, inp.pose.turnY], R: Math.max(40, (hb ? hb.x1 - hb.x0 : 160) * 0.42) }; }
      if (zone === 'body' || zone === 'tail' || zone === 'legL' || zone === 'legR') { inp.held.body = true; if (inp.walker.travels()) { inp.walker.grab(c); return { kind: 'walk' }; } return { kind: 'body' }; }
      return null;
    }
    function liveMove(g, p) {
      var inp = app.inp, c = app.toChar(p.x, p.y), inner = app.toInner(c[0], c[1]);
      if (g.kind === 'hand') inp.pose['hand' + g.sd] = { x: inner[0] + g.off[0], y: inner[1] + g.off[1] };
      else if (g.kind === 'foot') inp.pose['foot' + g.sd] = { x: c[0] + g.off[0], y: c[1] + g.off[1] };
      else if (g.kind === 'pin') inp.S.pin[g.pinId] = { x: inner[0] + g.off[0], y: inner[1] + g.off[1] };
      else if (g.kind === 'head') { inp.pose.turnX = clamp(g.t0[0] + (p.x - g.x0) / app.view.s / g.R, -1, 1); inp.pose.turnY = clamp(g.t0[1] + (p.y - g.y0) / app.view.s / g.R, -1, 1); }
      else if (g.kind === 'walk') inp.walker.drag(c);
      else if (g.kind === 'body') { inp.pose.sway = clamp((p.x - g.x0) / app.view.s / 70, -1, 1); inp.pose.bounce = clamp((p.y - g.y0) / app.view.s / 40, -1, 1); }
    }
    function liveEnd(g) {
      var inp = app.inp;
      if (g.kind === 'hand') inp.held['hand' + g.sd] = false;
      else if (g.kind === 'foot') inp.held['foot' + g.sd] = false;
      else if (g.kind === 'head') inp.held.head = false;
      else if (g.kind === 'walk') { inp.walker.release(); inp.held.body = false; }
      else if (g.kind === 'body') { inp.pose.sway = 0; inp.pose.bounce = 0; inp.held.body = false; }
    }
    function liveDouble(p) {
      var c = app.toChar(p.x, p.y), hit = AL.hit(app.out, c[0], c[1]), zone = hit ? AL.zoneOf(app.ch, hit.id) : null, inp = app.inp;
      if (zone === 'head') { inp.pose.turnX = inp.pose.turnY = inp.pose.roll = 0; } else if (zone === 'armL') inp.pose.handL = null; else if (zone === 'armR') inp.pose.handR = null;
      else if (zone === 'body') inp.walker.home(); else if (!hit) { inp.resetPose(); inp.S.pin = {}; }
    }
  }
  app.say = function () { };
  /**
   * JOYBOT. His button sits in the upper right corner of every room, the same orb as in the Studio. On the phone it opens his
   * chat. Here in the pilot it opens a short list of things to do from where you are, each with "take me there".
   */
  app.joybot = function () {
    var body = h('div', { class: 'joytips' }), ch = app.ch, live = app.mode === 'perform';
    function tip(title, text, fn) { body.appendChild(h('button', { title: title, onclick: function () { app.closeSheet(); fn(); } }, [h('div', null, [h('b', { text: title }), h('span', { text: text })])])); }
    function build(fn) { return function () { if (app.mode !== 'build') app.setMode('build'); app.tab = 'parts'; fn(); }; }
    tip('Start a new character', 'It is in the menu, top left. I will show you.', function () { var m = $('bmenu'); m.classList.add('nudge'); setTimeout(function () { m.classList.remove('nudge'); m.click(); }, 1500); });
    tip('Put a piece in the right place', 'Tap any piece on the picture, then move, turn or resize it with the box. Or pin it and stretch it.', build(function () { app.pickPiece = true; app.refreshPanel(true); app.toast('Tap a piece on the picture.'); }));
    if (app.zoneNode('body')) {
      tip('Make it go onto all fours', 'A stance: the body tips forward and the hands walk as front feet. Any other whole-body pose works the same way.', build(function () { app.select('body'); setTimeout(function () { var el = Array.prototype.filter.call(document.querySelectorAll('#panelbody .lab'), function (q) { return /Stances/.test(q.textContent); })[0]; if (el) el.scrollIntoView({ block: 'start', behavior: 'smooth' }); }, 80); }));
      tip('Change its colours', 'A colour set repaints the same art at the press of a button. Give it a picture and it works the change out.', build(function () { app.select('body'); setTimeout(function () { var el = Array.prototype.filter.call(document.querySelectorAll('#panelbody .lab'), function (q) { return /Colour sets/.test(q.textContent); })[0]; if (el) el.scrollIntoView({ block: 'start', behavior: 'smooth' }); }, 80); }));
    }
    if (!live) tip('See it alive', 'Perform: drag the head, the hands, the body. Talk. Record a take.', function () { app.setMode('perform'); });
    else tip('Go back to building', 'Build: everything holds still so you can place pins and parts.', function () { app.setMode('build'); });
    body.appendChild(app.ui.note('On the phone this button opens Joybot himself, and he flies to the thing he is showing you.'));
    app.sheet('Joybot', body, []);
  };
  /** Slide a drawing, and everything pinned to it, by (dx, dy). */
  app.nudge = function (n, dx, dy) {
    if (!n) return;
    AL.walk(n, function (k) {
      if (k.type === 'art') { k.x += dx; k.y += dy; }
      if (k.pivot) { k.pivot.x += dx; k.pivot.y += dy; }
      if (k.limb) ['a', 'b', 'c'].forEach(function (q) { k.limb[q].x += dx; k.limb[q].y += dy; });
      if (k.swing) { k.swing.tip.x += dx; k.swing.tip.y += dy; }
      if (k.breathe) { k.breathe.cx += dx; k.breathe.cy += dy; }
      if (k.mesh) k.mesh.pins.forEach(function (q) { q.x += dx; q.y += dy; });
    });
  };

  /* ── chrome: coach line, sheets, toasts ── */
  app.coach = function (html) { var c = $('coach'); c.firstElementChild.innerHTML = html || ''; c.style.display = html ? 'flex' : 'none'; c.className = (app.mode === 'perform' || app.live) ? 'live' : ''; };
  app.toast = function (msg) { var t = $('toast'); t.textContent = msg; t.style.display = 'block'; clearTimeout(app._tt); app._tt = setTimeout(function () { t.style.display = 'none'; }, 2600); };
  app.sheet = function (title, body, actions, tools) {
    var s = $('sheet'); s.querySelector('h3').textContent = title; var sb = s.querySelector('.sb'); sb.innerHTML = ''; sb.appendChild(body);
    /* a few round buttons beside the close button, set a little apart from it (the drawer: open, save, send) */
    var hx = s.querySelector('.shx') || s.querySelector('header').insertBefore(h('span', { class: 'shx' }), $('sheetx')); hx.innerHTML = '';
    (tools || []).forEach(function (t) { hx.appendChild(h('button', { class: 'ib', title: t.title, 'aria-label': t.title, onclick: t.fn }, AL.icon(t.icon))); });
    var f = s.querySelector('footer'); f.innerHTML = ''; (actions || []).forEach(function (a) { f.appendChild(app.ui.chip(a.label, { cls: a.cls, fn: a.fn, icon: a.icon, title: a.title })); }); f.style.display = actions && actions.length ? 'flex' : 'none';
    s.style.display = 'flex'; $('scrim').style.display = 'block';
  };
  app.closeSheet = function () { $('sheet').style.display = 'none'; $('scrim').style.display = 'none'; };
  app.ask = function (title, text, okLabel, fn, danger) { app.sheet(title, app.ui.note(text), [{ label: 'Cancel', cls: 'quiet', fn: app.closeSheet }, { label: okLabel, cls: danger ? 'live' : 'go', fn: function () { app.closeSheet(); fn(); } }]); };
  app.promptText = function (title, value, fn) {
    var inp = h('input', { type: 'text', value: value, id: 'ptext', 'aria-label': title });
    app.sheet(title, inp, [{ label: 'Cancel', cls: 'quiet', fn: app.closeSheet }, { label: 'Done', cls: 'go', fn: function () { app.closeSheet(); if (inp.value.trim()) fn(inp.value.trim()); } }]);
    setTimeout(function () { inp.focus(); inp.select(); }, 50);
  };
  app.full = function (title, onClose) {
    var f = $('full'); f.innerHTML = ''; f.style.display = 'flex';
    var head = h('header', null, [h('button', { class: 'ib', title: 'Back', onclick: function () { app.closeFull(); if (onClose) onClose(); } }, AL.icon('back')), h('h3', { text: title })]);
    var fb = h('div', { class: 'fb' }), ff = h('div', { class: 'ff' }); f.appendChild(head); f.appendChild(fb); f.appendChild(ff);
    return { head: head, body: fb, foot: ff };
  };
  app.closeFull = function () { $('full').style.display = 'none'; $('full').innerHTML = ''; };

  app.setMode = function (m) {
    app.mode = m; app.live = !!(m === 'build' && AL.stages && AL.stages.on() && app.stage === 'motion'); app.test.mouth = null; app.show = {};
    Array.prototype.forEach.call($('modes').children, function (b) { b.className = (b.getAttribute('data-m') === m ? 'on' : '') + (m === 'perform' && b.getAttribute('data-m') === m ? ' live' : ''); });
    $('app').classList.toggle('perform', m === 'perform');
    if (!app.ch || !app.inp) return;
    if (m === 'build') { app.inp.stop(); app.inp.micStop(); app.inp.camStop(); app.inp.walker.reset(); app.inp.pose.flip = false; } else { app.solver.reset(); }
    app.bridge = null; app.lockForce = null;
    app.userView = false; app.resolveSel(); app.refreshPanel(); app.focusSel();
  };
  app.setLive = function (on) { if (AL.stages && AL.stages.on() && app.mode === 'build') app.stage = on ? 'motion' : (app.stage === 'motion' ? 'setup' : app.stage); app.live = on; if (on) { app.solver.reset(); } else { app.lockForce = null; app.inp.walker.reset(); app.inp.pose.flip = false; } app.userView = false; app.refreshPanel(); app.focusSel(); };
  app.refreshPanel = function (keepScroll) {
    var pb = $('panelbody'), sc = pb.scrollTop;
    app.pinProvider = null; app.padDraw = null; app.padDraws = [];
    if (AL.stages) AL.stages.before(app);
    if (app.mode === 'perform') AL.perform.render(app); else AL.cards.render(app);
    if (AL.stages && app.mode !== 'perform') AL.stages.extra(app, pb);
    if (app.mode !== 'perform') AL.cards.fold(app);
    if (AL.stages) AL.stages.after(app);
    if (AL.stages && AL.stages.on() && app.mode === 'perform') { var prh = $('panelhead'); prh.insertBefore(AL.stages.rail(app), prh.firstChild); }
    if (app.mode !== 'perform' && !app.live && !app.bridge && app.fitOn && app.fitOn.box) { var bx = AL.node(app.ch, app.fitOn.node); if (bx) app.pinProvider = function () { return AL.fit.pins(app, bx); }; }      // the box shows on whichever card is open
    app.renderTools();
    /* the same card drawn again stays where it was scrolled to; a different card opens at its top */
    var h2 = $('panelhead').querySelector('h2'), key = app.mode + '|' + app.tab + '|' + (app.stage || '') + '|' + (app.live ? 1 : 0) + '|' + (h2 && h2.firstChild ? h2.firstChild.textContent : '');
    pb.scrollTop = (keepScroll || key === app._cardKey) ? sc : (app._scUse && app._sc && app._sc[key]) || 0; app._scUse = false; app._cardKey = key;      // (a stage keeps its place on the card while you dip into another)
  };
  app.renderTools = function () {
    var t = $('tools'), liveNow = app.mode === 'perform' || app.live; t.innerHTML = '';
    function b(icon, title, on, fn) { t.appendChild(h('button', { class: 'ib' + (on ? ' on' : ''), title: title, 'aria-pressed': String(!!on), onclick: fn }, AL.icon(icon))); }
    b('fit', 'Fit: show the whole character', false, function () { app.userView = false; if (liveNow || !app.selIds) app.fit(false); else if (app.vt == null && app._fitAll) { app._fitAll = false; app.focusSel(); } else { app._fitAll = true; app.fit(false); } });
    if (!liveNow) {
      b('mesh', 'Show the bend mesh of the selected part', app.mesh, function () { app.mesh = !app.mesh; app.renderTools(); });
      if (app.selNodes && app.selNodes[0] && app.selNodes[0].type === 'set') b('onion', 'Onion skin: ghost the other drawings of this part', app.onion, function () { app.onion = !app.onion; app.renderTools(); });
      if (!(AL.stages && AL.stages.on())) b('move', 'Move art: when ON, dragging the selected drawing slides it (pins come along)', app.moveArt, function () { app.moveArt = !app.moveArt; app.renderTools(); app.toast(app.moveArt ? 'Move art is ON: drag the selected drawing' : 'Move art is off'); });
    } else {
      b('mesh', 'Show the bend mesh', app.mesh, function () { app.mesh = !app.mesh; app.renderTools(); });
      b('flip', 'Turn the whole character around', app.inp.pose.flip, function () { app.inp.pose.flip = !app.inp.pose.flip; app.inp.held.body = true; setTimeout(function () { app.inp.held.body = false; }, 400); app.renderTools(); });
    }
  };

  /* ── autosave: leaving the page never loses the character ── */
  function idb(fn) {
    return new Promise(function (res, rej) {
      try { var rq = indexedDB.open('avatarlab', 1); rq.onupgradeneeded = function () { rq.result.createObjectStore('kv'); }; rq.onerror = function () { rej(rq.error); };
        rq.onsuccess = function () { var tx = rq.result.transaction('kv', 'readwrite'), r = fn(tx.objectStore('kv')); tx.oncomplete = function () { res(r && r.result); }; tx.onerror = function () { rej(tx.error); }; };
      } catch (e) { rej(e); }
    });
  }
  app.autosave = function () {
    if (!app.ch || !app.dirty) return; app.dirty = false;
    if (AL.shelf) AL.shelf.tag(app.ch);
    var o = AL.toJson(app.ch, true); if (app.inp && app.inp.take && app.inp.take.n) o.take = app.inp.take;      // character and take are one document
    return idb(function (s) { return s.put(o, 'current'); }).then(function () { if (!app.dirty) app.saveState('saved'); if (AL.shelf) AL.shelf.keep(app, o); }, function () { app.dirty = true; app.saveState('failed'); });
  };
  app.idb = idb;
  app.loadSaved = function () { return idb(function (s) { return s.get('current'); }).then(function (o) { if (!o || AL.validate(o)) return null; app._take = o.take && o.take.ch ? o.take : null; delete o.take; return AL.fromJson(o); }).catch(function () { return null; }); };

  /* ── the desk around the phone ── */
  var SIZES = { tall: [411, 846], wide: [846, 411], tab: [1180, 740] };
  app.layout = function () {
    var small = root.innerWidth <= 620 || root.innerHeight <= 520, kind = small ? 'fill' : (app.dev || 'tall'), dev = $('device'), wrap = $('devwrap');
    document.body.classList.toggle('fill', kind === 'fill');
    if (kind !== 'fill') { var z = SIZES[kind]; dev.style.width = z[0] + 'px'; dev.style.height = z[1] + 'px'; var s = Math.min(1, (wrap.clientWidth - 16) / z[0], (wrap.clientHeight - 10) / z[1]); dev.style.transform = s < 1 ? 'scale(' + s + ')' : ''; }
    else { dev.style.width = dev.style.height = ''; dev.style.transform = ''; }
    var land = dev.clientWidth > dev.clientHeight * 1.05; $('app').classList.toggle('land', land);
    Array.prototype.forEach.call($('devseg').children, function (b) { b.className = b.getAttribute('data-d') === (app.dev || 'tall') ? 'on' : ''; });
  };

  app.boot = function () {
    $('bmenu').appendChild(AL.icon('menu')); $('bundo').appendChild(AL.icon('undo')); $('bredo').appendChild(AL.icon('redo')); $('sheetx').appendChild(AL.icon('x'));
    app.G = new AL.GL($('gl'));
    var held = false, ht = null, ub = $('bundo');
    ub.addEventListener('pointerdown', function () { held = false; ht = setTimeout(function () { held = true; app.historySheet(); }, 480); });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach(function (ev) { ub.addEventListener(ev, function () { clearTimeout(ht); }); });
    ub.onclick = function () { if (held) { held = false; return; } app.undo(false); }; $('bredo').onclick = function () { app.undo(true); };
    ub.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    $('sheetx').onclick = app.closeSheet; $('scrim').onclick = app.closeSheet;
    $('bmenu').onclick = function () { AL.intake.menu(app); };
    try { app.stage = localStorage.getItem('al.stage') || 'setup'; } catch (e) { app.stage = 'setup'; }
    if (app.stage !== 'fit') app.stage = 'setup';
    $('app').classList.toggle('stages', AL.stages.on());
    $('bjoy').onclick = function () { app.joybot(); };
    $('bname').onclick = function () { app.promptText('Name this character', app.ch.name, function (v) { app.edit(function () { app.ch.name = v; app.ch.root.name = v; }); $('chname').textContent = v; }); };
    Array.prototype.forEach.call($('modes').children, function (b) { b.onclick = function () { app.setMode(b.getAttribute('data-m')); }; });
    Array.prototype.forEach.call($('devseg').children, function (b) { b.onclick = function () { app.dev = b.getAttribute('data-d'); try { localStorage.setItem('al.dev', app.dev); } catch (e) { } app.layout(); app.userView = false; }; });
    try { app.dev = localStorage.getItem('al.dev') || 'tall'; } catch (e) { app.dev = 'tall'; }
    root.addEventListener('resize', app.layout); app.layout();
    root.addEventListener('keydown', function (e) {
      if (e.target && /INPUT|TEXTAREA|SELECT/.test(e.target.tagName) && e.target.type !== 'range') return;
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); app.undo(e.shiftKey); }
      else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') { e.preventDefault(); app.undo(true); }
      else if (app.mode === 'perform' && !e.repeat) AL.perform.key(app, e, true);
    });
    root.addEventListener('keyup', function (e) { if (app.mode === 'perform') AL.perform.key(app, e, false); });
    root.addEventListener('pagehide', function () { app.dirty = true; app.autosave(); });
    wire(); AL.intake.wire(app);
    var q = /[?&]open=([^&]+)/.exec(location.search), fresh = /[?&]demo\b/.test(location.search);
    var start = q ? AL.intake.openUrl(app, decodeURIComponent(q[1])) : (fresh ? Promise.resolve(null) : app.loadSaved()).then(function (ch) {
      if (ch && !(ch.demo && !ch.edited && ch.pilot !== AL.PILOT)) { app.setCharacter(ch, { quiet: true }); if (app._take) app.inp.take = app._take; app._take = null; app.saveState('saved'); mendTopps(); return; }
      var want = /[?&]demo=joybot\b/.test(location.search) || (ch && ch.demo === 'joybot') ? 'joybot' : /[?&]demo=pip\b/.test(location.search) || (ch && ch.demo === 'pip') ? 'pip' : 'topps';
      return AL.intake.builtIn(app, want).catch(function () { AL.img.map = {}; app.setCharacter(AL.demo(), { quiet: true }); });
    });
    start.catch(function (e) { console.error(e); app.setCharacter(AL.demo(), { quiet: true }); app.toast('That file would not open: ' + e.message); }).then(function () { requestAnimationFrame(app.frame); });
  };
})(typeof self !== 'undefined' ? self : this);
