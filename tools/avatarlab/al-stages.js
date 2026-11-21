/* AvatarLab: STAGES. The room is laid out in the order a character is made.
 *
 *   SET UP    what the pieces are and where they hinge.   The picture holds still and shows PINS.
 *   FIT       make it look right: here, then in every pose. The picture holds still and shows THE BOX on the part in hand.
 *   MOTION    how it moves by itself and when it is pulled. The picture is ALIVE: drag it while the sliders change.
 *   PERFORM   play it and record.
 *
 * Every control answers one of three questions (what exists, how it looks, how it moves) and lives in that stage and nowhere
 * else, so a card is short and nothing is hunted for. Two more things say where you are, and both are always on screen:
 *   WHAT   the body map (and a tap on the picture, and the Layers list)
 *   WHERE  in Fit, the pose the fit is for: Standing, or one of the stances (the row on the picture)
 * So there are no hidden modes: "Try it" is the Motion stage, "Move it" is the Fit stage, a stance is a pose chosen in Fit.
 *
 * A card says which stage each of its sections belongs to: ui.lab('Pins', 'setup'). 'all' (or nothing) shows it everywhere.
 * The earlier layout (one long card per part, Build / Perform at the top) is kept: Menu, "One long card per part".
 * Phone: a four-way tab row at the top of the bottom panel; the same tags on the same sections.
 */
(function (root) {
  'use strict';
  var AL = root.AL, stages = AL.stages = {}, $ = function (id) { return document.getElementById(id); };
  stages.LIST = [
    ['setup', 'Set up', 'What the pieces are, and where they hinge. The picture holds still and shows the pins.'],
    ['fit', 'Fit', 'Make it look right, here and then in every pose. Drag a part to move it; the box resizes and turns it.'],
    ['motion', 'Motion', 'How it moves. It is alive here: drag it while you change things.'],
    ['perform', 'Perform', 'Play it and record.']
  ];
  var SAY = { setup: 'to set up', fit: 'to fit', motion: 'that moves' };
  stages.on = function () { if (stages._on == null) { try { stages._on = localStorage.getItem('al.layout') !== 'classic'; } catch (e) { stages._on = true; } } return stages._on; };
  stages.set = function (app, on) {
    stages._on = !!on; try { localStorage.setItem('al.layout', on ? 'stages' : 'classic'); } catch (e) { }
    $('app').classList.toggle('stages', !!on); app.poseEdit = null; app.fitOn = null; app.moveArt = false; app.pickPiece = false;
    if (app.mode === 'perform') app.refreshPanel(); else app.setLive(!!on && app.stage === 'motion');
  };
  stages.cur = function (app) { return app.mode === 'perform' ? 'perform' : (app.stage || 'setup'); };
  stages.is = function (app, s) { return stages.on() && stages.cur(app) === s; };

  stages.go = function (app, s) {
    var from = stages.cur(app), ch = app.ch, pb = $('panelbody'), carry = null;
    (app._sc = app._sc || {})[app._cardKey] = pb ? pb.scrollTop : 0; app._scUse = true;          // each stage keeps its place on the card
    if (s === 'perform') { stages.letGo(app); if (app.mode !== 'perform') app.setMode('perform'); return; }
    /* dipping between Fit and Motion keeps the pose: the stance being fitted is the stance it moves in, and the other way round */
    if (from === 'fit' && s === 'motion') carry = app.poseEdit || 0;
    if (from === 'motion' && s === 'fit' && app.inp) { carry = 0; (ch.poses || []).forEach(function (p) { if (p.trig && app.inp.trig[p.trig]) carry = p.id; }); }
    if (s === 'motion') { stages.letGo(app); if (from === 'setup' || from === 'fit') app._from = from; }
    app.stage = s; try { localStorage.setItem('al.stage', s); } catch (e) { }
    if (s !== 'fit') { app.poseEdit = null; app.fitOn = null; app.moveArt = false; app.orderPick = null; }
    else if (from === 'motion') { app.poseEdit = carry || null; app.poseAmt = null; }
    app.pickPiece = false; app.bridge = null;
    if (app.mode !== 'build') { app.live = false; app.setMode('build'); }
    app.setLive(s === 'motion');
    if (s === 'motion' && carry !== null && app.inp) (ch.poses || []).forEach(function (p) { if (p.trig) app.inp.setTrig(p.trig, p.id === carry); });
    if (s === 'motion' && carry) app.refreshPanel(true);              // (the body map shows it on four legs straight away)
  };
  /** Freeze the living picture exactly as it is and go to Set up: to mend a pin or a joint while it is bent the way that shows the fault. */
  stages.hold = function (app) {
    if (!app._S || !app.out) return;
    var S = JSON.parse(JSON.stringify(app._S)), ch = app.ch, was = {};
    stages.letGo(app);
    AL.findAll(ch.root, function (q) { return q.type === 'set' && q.pick && q.pick.by === 'reach'; }).forEach(function (set) { var k = app.out.picks && app.out.picks[set.id]; if (k) { was[set.id] = { from: app.force[set.id] || null, to: k }; app.force[set.id] = k; } });
    stages.go(app, 'setup'); app.held = S; app._heldCh = ch; app._heldWas = was; app.refreshPanel(true); app.toast('Held. Mend it here, then tap Held pose to let go.');
  };

  /** Let a held pose go. Holding also froze each limb on the drawing it was showing; that goes back too, or the arm would stay
      raised in Set up and Fit after the pose was let go. (A drawing he chose himself while it was held stays his choice.) */
  stages.letGo = function (app) {
    var was = app._heldWas; app._heldWas = null; app.held = null;
    if (was && app._heldCh === app.ch) for (var id in was) { if (app.force[id] !== was[id].to) continue; if (was[id].from) app.force[id] = was[id].from; else delete app.force[id]; }
  };

  /* ── WHERE YOU ARE: the path, as slanted tabs ──
     Topps > Head > Brows > Left eyebrow, each with a picture of itself. The last is where you are; tap any earlier one to step
     back out to it (the ones after it fold away into it). A tap on empty space on the picture steps out one level too. */
  stages.path = function (app) {
    var ch = app.ch, sel = app.sel, P = [{ id: 'ch', name: ch.name || 'Character', node: ch.root, go: function () { app._hitId = null; app.tab = 'parts'; app.select('body'); } }];
    if (app.tab !== 'parts' || !sel.zone) return P;
    var zn = sel.zone !== 'extra' && sel.zone !== 'ground' ? app.zoneNode(sel.zone) : null;
    if (sel.zone !== 'body') P.push({ id: 'z:' + sel.zone, name: zn ? AL.cards.nameOf(app, zn) : (sel.zone === 'ground' ? 'Ground' : 'Extras'), node: zn, go: function () { app._hitId = null; app.select(sel.zone); } });
    if (sel.feature) {
      var F = app.FEAT[sel.feature], list = app.featureNodes(sel.feature) || [];
      P.push({ id: 'f:' + sel.feature, name: F ? F.label : sel.feature, node: list[0] || null, go: function () { app._hitId = null; app.select(sel.zone, sel.feature); } });
      var t = stages.target(app); if (t && list.length > 1 && app._hitId) P.push({ id: 'n:' + t.id, name: t.name, node: t, go: function () { } });
    } else if (sel.node) {
      var n = AL.node(ch, sel.node);
      if (n && n !== zn) {
        var ix = AL.ix(ch), mid = [], q = ix.p[n.id];                       // the folders between the part and the layer (the head angle it belongs to) are named too
        while (q && q !== zn && q !== ch.root && mid.length < 4) { if (q.type !== 'set') mid.unshift(q); q = ix.p[q.id]; }
        if (zn && q === zn) mid.forEach(function (m) { P.push({ id: 'n:' + m.id, name: m.name, node: m, go: function () { stages.open(app, m.id); } }); });
        P.push({ id: 'n:' + n.id, name: n.name, node: n, go: function () { } });
      }
    }
    return P;
  };
  /** Open one layer or folder as the part in hand, keeping the stage and the stance. */
  stages.open = function (app, id, stay) {
    var keep = app.poseEdit, amt = app.poseAmt, v = app.view, v0 = stay ? { s: v.s, ox: v.ox, oy: v.oy } : null, uv = app.userView;
    app._hitId = id; app.tab = 'parts'; app.select(AL.zoneOf(app.ch, id), null, id);
    if (v0) { app.view = v0; app.vt = null; app.userView = uv; }                // stepping in by a tap: the picture stays where it is, so the next tap lands where you see it
    if (keep && app.poseEdit !== keep) { app.poseEdit = keep; app.poseAmt = amt; app.refreshPanel(); }
  };
  /**
   * A second tap goes IN. The first tap on the head takes the whole head; a tap on it again takes the very layer under the finger,
   * at the angle the head is showing (its face, a horn). Inside, a tap on another layer of the same part goes straight to that layer.
   * Features with their own card (eyes, mouth, brows) and limbs keep opening as they did. Returns true when it took the tap.
   */
  stages.stepIn = function (app, id) {
    var ch = app.ch, sel = app.sel; if (app.tab !== 'parts' || app.live || app.selOff) return false;
    var zone = AL.zoneOf(ch, id); if (zone !== sel.zone || zone === 'extra' || zone === 'ground') return false;
    if (AL.ancestorWith(ch, id, function (a) { return a.type === 'set' && a.pick && a.pick.by === 'reach'; })) return false;
    var feat = null; if (zone === 'head') for (var f in app.FEAT) { if (AL.ancestorWith(ch, id, function (a) { return a.role && app.FEAT[f].roles.indexOf(a.role) >= 0; })) { feat = f; break; } }
    if (feat) return false;                                                   // an eye, the mouth: its own card
    if (sel.feature) return false;                                            // from a feature, a tap on the plain head goes back to the head
    if (sel.node === id) return true;                                         // already in hand
    stages.open(app, id, true); return true;
  };
  stages.up = function (app) { var P = stages.path(app); if (P.length > 2) P[P.length - 2].go(); };
  stages.crumbs = function (app) {
    var h = AL.h, P = stages.path(app), was = stages._path || [], same = 0, row = h('div', { class: 'crumbs' });
    while (same < P.length && same < was.length && P[same].id === was[same]) same++;
    P.forEach(function (q, i) {
      var last = i === P.length - 1, url = q.node && AL.shelf ? AL.shelf.partPic(app, q.node, 48) : null;
      var b = h('button', { class: 'crumb' + (last ? (app.selOff ? ' on off' : ' on') : '') + (i >= same && was.length ? ' in' : ''), title: last ? (app.selOff ? q.name + ': tap to show its box and marks again' : q.name + ': where you are. Tap to see it bare, with no box or marks; you stay here.') : 'Back out to ' + q.name, onclick: last ? function () { app.selOff = !app.selOff; app.refreshPanel(true); } : function () {
        Array.prototype.slice.call(row.children, i + 1).forEach(function (c) { c.classList.add('out'); });      // the deeper ones fold away into this one, then it is chosen
        setTimeout(q.go, 150);
      } }, [url ? h('img', { src: url, alt: '' }) : null, h('span', { text: q.name })]);
      row.appendChild(b);
    });
    stages._path = P.map(function (q) { return q.id; });
    setTimeout(function () { row.scrollLeft = row.scrollWidth; }, 0);
    return row;
  };

  /** The four-way row. */
  stages.rail = function (app) {
    var h = AL.h, cur = stages.cur(app), r = h('div', { class: 'rail', role: 'tablist', 'aria-label': 'What you are doing' });
    stages.LIST.forEach(function (q) {
      r.appendChild(h('button', { class: (q[0] === cur ? 'on' : '') + (q[0] === 'perform' ? ' live' : ''), 'data-s': q[0], role: 'tab', 'aria-selected': String(q[0] === cur), title: q[1] + ': ' + q[2], text: q[1], onclick: function () { if (q[0] !== cur) stages.go(app, q[0]); } }));
    });
    return r;
  };

  /** The part the box is for: the layer picked, else the feature, else the whole part chosen on the map. */
  stages.target = function (app) {
    if (app.tab !== 'parts') return null;
    if (app.sel.node) return AL.node(app.ch, app.sel.node);
    if (app.sel.feature) {
      var list = app.featureNodes(app.sel.feature) || [], ix = AL.ix(app.ch), q = app._hitId ? ix.n[app._hitId] : null;
      while (q) { if (list.indexOf(q) >= 0) return q; q = ix.p[q.id]; }
      return list[0] || null;
    }
    return app.sel.zone && app.sel.zone !== 'ground' && app.sel.zone !== 'extra' ? app.zoneNode(app.sel.zone) : null;      // (the body too: its torso takes the box)
  };
  /** In Fit the box is simply there on whatever is in hand (a limb, outside a stance, slides whole instead: pins and all). */
  stages.before = function (app) {
    if (!stages.on() || app.mode !== 'build') return;
    if ((app.held && (app._heldCh !== app.ch || app.live)) || (!app.held && app._heldWas)) stages.letGo(app);      // (however it was let go, the limbs are let go with it)
    if (app.bridge) { app.fitOn = null; app.moveArt = false; if (app._br !== app.bridge) { app._br = app.bridge; setTimeout(function () { app.userView = false; app.focusSel(); }, 0); } } else app._br = null;      // matching two angles: taps belong to the landmarks, so the box steps aside
    if (app.held) app.poseEdit = null;                          // (a held pose is the pose: no stance over it)
    if (app.stage !== 'fit' || app.live || app.bridge) { if (app.stage !== 'fit') app.moveArt = false; return; }
    var n = stages.target(app), fit = AL.fit;
    if (app.selOff) { app.fitOn = null; app.moveArt = false; return; }          // looking at it bare: no box, no marks, same card
    if (app.fitOn && app.fitOn.pins && n && app.fitOn.node === n.id) { app.moveArt = false; return; }      // reshaping with pins: leave it be
    if (app.fitOn && app.fitOn.box && n && app.fitOn.node !== n.id) { var k = AL.node(app.ch, app.fitOn.node), ixx = AL.ix(app.ch); while (k && k !== n) k = ixx.p[k.id]; if (k) { app.moveArt = false; return; } }      // the box was put on a piece of it (just this angle): leave it there
    if (n && fit.can(app, n)) { if (!n.pivot && n.type !== 'art') fit.needPivot(app, n); app.fitOn = { node: n.id, box: true }; app.moveArt = false; }
    else { app.fitOn = null; app.moveArt = !!(n && n.type === 'set' && n.pick && n.pick.by === 'reach'); }
  };
  /** Between drawing the card and sorting its sections: what a stage adds to every card. */
  stages.extra = function (app, pb) {
    if (!stages.on() || app.mode !== 'build' || app.bridge || app.tab !== 'parts' || !pb.children.length) return;
    var ui = app.ui, n = stages.target(app);
    if (app.stage === 'fit' && n && AL.fit.can(app, n)) {
      /* every part can be fitted, whether or not its card thought to offer it */
      var has = Array.prototype.some.call(pb.querySelectorAll('.lab'), function (l) { return /^Fit it by hand/.test(l.textContent); });
      if (!has) { var tmp = document.createElement('div'), first = pb.querySelector(':scope > .lab'); AL.fit.section(app, tmp, n); if (!pb.querySelector('.stk') && !tmp.querySelector('.stk')) AL.stack.section(app, tmp, n, { quiet: true }); while (tmp.firstChild) pb.insertBefore(tmp.firstChild, first); }      // (it goes first: it is what Fit is for)
    }
    if (app.stage === 'motion' && app.live) { pb.appendChild(ui.lab('Play with it', 'motion')); pb.appendChild(AL.perform.quick(app)); }
  };
  stages.after = function (app) {
    if (!stages.on() || app.mode !== 'build' || app.bridge) return;
    var n = stages.target(app), nm = n ? AL.cards.nameOf(app, n) : '', pose = AL.fit.pose(app);
    if (app.live) { app.pinProvider = null; app.coach('<b>Motion.</b> It is alive: drag the head, a hand, the body. Tap a part to open what moves it.'); return; }
    if (app.stage !== 'fit' || app.tab !== 'parts') return;
    if (!(app.fitOn && (app.fitOn.box || app.fitOn.pins))) app.pinProvider = null;            // Fit shows the box, never the wiring pins
    if (app.fitOn && app.fitOn.pins) return;                                                    // (reshaping has its own hint)
    var forPose = pose ? ' For the stance <b>' + pose.name + '</b> only.' : '';
    if (app.fitOn && app.fitOn.box) { var bn = AL.node(app.ch, app.fitOn.node); app.coach('<b>Fit.</b> Drag <b>' + (bn ? AL.cards.nameOf(app, bn) : nm) + '</b> to move it. A corner resizes it, the round handle turns it.' + forPose); }
    else if (app.moveArt && n) app.coach('<b>Fit.</b> Drag <b>' + nm + '</b> to slide it, pins and all, to where it joins the body.');
    else app.coach('<b>Fit.</b> Tap a part on the picture or the map, then drag it.' + forPose);
  };
  /** Motion: the "Try:" sliders on a card (talk, look, the lids) work on the living picture too, while they are off their rest. */
  stages.tryOn = function (app, S) {
    var t = app.test;
    if (t.mouth != null) { S.mouth = t.mouth; if (t.vis) S.viseme = t.vis; }
    if (t.lookX || t.lookY) { S.lookX = t.lookX; S.lookY = t.lookY; }
    if (t.blink) S.blinkL = S.blinkR = t.blink;
    if (t.brow) S.browL = S.browR = t.brow;
    if (t.lidUp) S.lidUp = t.lidUp; if (t.lidLow) S.lidLow = t.lidLow; if (t.lidTilt) S.lidTilt = t.lidTilt;
  };

  /* a stance's button shows the character in that stance (and Standing shows it standing): the thing itself, not a sign for it */
  stages.poseThumb = function (app, id) {
    var ch = app.ch, p = id ? AL.stance.find(ch, id) : null;
    if (stages._tc !== ch) { stages._tc = ch; stages._th = {}; }
    var key = (id || 'stand') + ':' + app.hist.past.length + ':' + app.hist.future.length + (p ? ':' + JSON.stringify(p.d || {}).length + JSON.stringify(p.fours || 0) : '');
    var hit = stages._th[id || 'stand']; if (hit && hit.key === key) return hit.url;
    var eo = {}; if (id) { eo.poses = {}; eo.poses[id] = 1; }
    var url = AL.shelf ? AL.shelf.snap(app, eo, 56) : null; stages._th[id || 'stand'] = { key: key, url: url };
    return url;
  };
  /** Motion's pose row shows the stance the character is in right now (it changes by itself when it is pushed low). */
  stages.motionRow = function (app) {
    var row = stages._mrow; if (!row || !row.length || !row[0].b.parentNode || !app.inp) return;
    var cur = null; row.forEach(function (r) { if (r.p && app.inp.trig[r.p.trig]) cur = r.p; });
    row.forEach(function (r) { var on = r.p === cur; if (r.b.classList.contains('on') !== on) r.b.classList.toggle('on', on); });
  };
  if (AL.app && AL.app.hooks) AL.app.hooks.push(function () { if (AL.app.live && stages._mrow) stages.motionRow(AL.app); });
  /** The row on the picture. Fit: the pose this fit is for. Set up: nothing in the way. Motion: a way to make it talk. */
  stages.bar = function (app, sb) {
    var ui = app.ui, h = AL.h, ch = app.ch, st = stages.cur(app);
    sb.innerHTML = '';
    if (st === 'motion') {
      if (app._from) sb.appendChild(ui.chip('Back to ' + (app._from === 'fit' ? 'Fit' : 'Set up'), { cls: 'veil', icon: 'back', title: 'Back to where you were, exactly as you left it', fn: function () { stages.go(app, app._from); } }));
      sb.appendChild(ui.chip('Hold this pose', { cls: 'veil', icon: 'pin', title: 'Freeze it exactly as it is now and go to Set up, to mend a pin or a joint while it is bent this way', fn: function () { stages.hold(app); } }));
      /* the same pose row as in Fit, alive: tap a stance and it goes into it, tap Standing and it gets up. It follows what the
         character does by itself (pushed low it drops to all fours, and the row shows it). */
      var mlist = (ch.poses || []).filter(function (p) { return p.trig; });
      if (mlist.length && app.inp) {
        var mseg = h('span', { class: 'seg veil poses', role: 'group', title: 'The stance it is in. Tap one to send it there.' }); stages._mrow = [];
        var mone = function (p) {
          var pic = stages.poseThumb(app, p ? p.id : null), b = h('button', { title: p ? 'Go into “' + p.name + '”' : 'Stand up', onclick: function () { mlist.forEach(function (q) { app.inp.setTrig(q.trig, q === p); }); } }, [pic ? h('img', { src: pic, alt: '' }) : null, p ? p.name : 'Standing']);
          stages._mrow.push({ b: b, p: p }); mseg.appendChild(b);
        };
        mone(null); mlist.forEach(mone); sb.appendChild(mseg); stages.motionRow(app);
      }
    }
    if (app.held && st !== 'motion') sb.appendChild(ui.chip('Held pose', { cls: 'veil on', icon: 'x', title: 'The character is frozen in the pose you held. Tap to let it go back to standing.', fn: function () { stages.letGo(app); app.refreshPanel(true); } }));
    if (st === 'fit' && !app.held) {
      var seg = h('span', { class: 'seg veil poses', role: 'group', title: 'The pose this fit is for' }), list = ch.poses || [];
      function one(id, name, title) { var pic = stages.poseThumb(app, id); seg.appendChild(h('button', { class: (app.poseEdit || null) === id ? 'on' : '', title: title, onclick: function () { if ((app.poseEdit || null) === id) return; app.poseEdit = id; app.poseAmt = null; app.fitOn = null; app.orderPick = null; app.userView = false; app.refreshPanel(); } }, [pic ? h('img', { src: pic, alt: '' }) : null, name])); }
      one(null, 'Standing', 'As it was drawn. What you move here is moved for good.');
      list.forEach(function (p) { one(p.id, p.name, 'The stance “' + p.name + '”: what you move is moved for this stance only'); });
      seg.appendChild(h('button', { class: 'add', title: 'A new stance: a whole-body pose with its own button', 'aria-label': 'Add a stance', onclick: function () { stages.addStance(app); } }, AL.icon('plus')));
      sb.appendChild(seg);
      var n = stages.target(app);
      if (n && AL.fit.can(app, n) && AL.fit.moved(app, n)) sb.appendChild(ui.chip('Put it back', { cls: 'veil', title: app.poseEdit ? 'Forget where this part was put for this stance' : 'Forget the move, the turn and the size: back to how it was drawn', fn: function () { app.edit(function () { AL.fit.reset(app, n); }, false, 'Put it back'); } }));
      var tz = app.toneTry && AL.tone.find(ch, app.toneTry);
      if (tz) sb.appendChild(ui.chip(tz.name, { cls: 'veil on', icon: 'x', title: 'Showing this colour set. Tap to go back to its own colours.', fn: function () { app.toneTry = null; app.eyedrop = null; app.refreshPanel(true); } }));
    }
  };
  stages.addStance = function (app) {
    var ch = app.ch, S = AL.stance, acts = [];
    function made(p) { if (p) { app.poseEdit = p.id; app.poseAmt = null; } app.closeSheet(); app.tab = 'parts'; app.select('body'); }
    if (!S.fours(ch) && S.parts(ch)) acts.push({ label: 'All fours, worked out for me', cls: 'go', icon: 'wand', title: 'Tips the body forward about the hips until the hands reach the ground, and lets the hands walk as front feet', fn: function () { var p; app.edit(function () { p = S.makeFours(ch); }, true, 'Make an all-fours stance'); made(p); } });
    acts.push({ label: 'A stance of my own', icon: 'plus', fn: function () { app.closeSheet(); app.promptText('Name this stance', 'Sitting', function (v) { var p; app.edit(function () { p = S.add(ch, v); }, true, 'Add a stance'); made(p); }); } });
    app.sheet('A new stance', app.ui.note('A stance is a whole-body pose it melts into at the press of a button: all fours, sitting, a crouch. You place the parts for it with the same box as anywhere else.'), [{ label: 'Cancel', cls: 'quiet', fn: app.closeSheet }].concat(acts));
  };

  /** Keep the sections that belong to this stage. Returns them; says so when the part has nothing here. */
  stages.filter = function (app, secs, pb) {
    var st = stages.cur(app), has = { setup: 0, fit: 0, motion: 0 }, keep = [];
    secs.forEach(function (s) {
      var t = s.lab.getAttribute('data-st') || 'all';
      ['setup', 'fit', 'motion'].forEach(function (k) { if (t.indexOf(k) >= 0) has[k]++; });
      if (t === 'all' || t.indexOf(st) >= 0) keep.push(s); else { pb.removeChild(s.lab); pb.removeChild(s.body); }
    });
    var rail = $('panelhead').querySelector('.rail');
    if (rail) Array.prototype.forEach.call(rail.children, function (b) { var k = b.getAttribute('data-s'); if (has[k] === 0 && secs.length) b.classList.add('none'); });
    if (secs.length && !has[st] && !keep.some(function (s) { return (s.lab.getAttribute('data-st') || 'all') === 'all'; })) {
      var h2 = $('panelhead').querySelector('h2'), nm = h2 && h2.firstChild ? h2.firstChild.textContent : 'this part', row = [];
      stages.LIST.forEach(function (q) { if (has[q[0]]) row.push(app.ui.chip(q[1], { title: q[2], fn: function () { stages.go(app, q[0]); } })); });
      var pic = st === 'setup' && app.pinProvider ? 'Its pins are on the picture: drag them. Nothing else ' + SAY[st] + ' here.' : st === 'fit' && stages.target(app) ? 'Drag it on the picture.' : 'Nothing ' + SAY[st] + ' on <b>' + nm + '</b>.';
      pb.appendChild(app.ui.note(pic + (row.length ? ' More about it:' : '')));
      if (row.length) pb.appendChild(app.ui.row(row));
    }
    return keep;
  };
})(typeof window !== 'undefined' ? window : globalThis);
